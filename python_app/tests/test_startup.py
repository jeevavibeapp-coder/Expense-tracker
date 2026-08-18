"""B4 regression: no expensive work on the Android UI thread.

The defect: `MainActivity.onCreate` -> `bootstrap()` ran, synchronously and
before spawning any worker, two AndroidKeyStore round trips (including
first-run `KeyGenerator.generateKey()`, which costs hundreds of milliseconds
on StrongBox), three synchronous SharedPreferences `commit()` disk writes,
`getFilesDir()`, and `WorkManager.getInstance()` — which opens a Room
database. `onResume` then called into the keystore again on EVERY return to
the app.

There is no device or emulator in this environment, so an ANR cannot be
measured here. What CAN be verified — and what actually determines whether
the work is on the UI thread — is the call graph: which expensive calls are
reachable from a lifecycle callback without first crossing a thread boundary.
This module parses MainActivity.java, strips comments and string literals,
brace-matches every `new Thread(...)` body, removes it, and asserts that no
expensive call survives in what remains.

That is a real, checkable invariant. It is not a substitute for measuring
cold start on hardware, which stays on the device test plan.
"""
from __future__ import annotations

import os
import re

import pytest

JAVA = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
    "android", "app", "src", "main", "java", "com", "jeevavibeapp", "spendwise",
    "MainActivity.java")

# Calls that block on disk, IPC to keystored, or database initialisation.
EXPENSIVE = {
    "SecretVault.migrate": "AndroidKeyStore + synchronous prefs commit()",
    "SecretVault.getOrCreate": "AndroidKeyStore round trip / key generation",
    "getDeviceToken()": "AndroidKeyStore round trip",
    "getSessionSecret()": "AndroidKeyStore round trip",
    "getSharedPreferences": "blocking disk read on first access",
    "SmsCatchUpWorker.schedule": "WorkManager.getInstance() opens a Room DB",
    "WorkManager.getInstance": "opens a Room database",
    "Python.start": "unpacks the Python runtime on first launch",
    "getFilesDir": "filesystem access",
    "SmsInboxScanner.scan": "content-provider query over the SMS inbox",
}

# Lifecycle callbacks: anything reachable from these without crossing a thread
# boundary runs on the UI thread.
LIFECYCLE = ["public void onCreate", "public void onResume",
             "public void onRequestPermissionsResult"]


def _source() -> str:
    with open(JAVA, encoding="utf-8") as f:
        return f.read()


def _strip_noise(src: str) -> str:
    """Blank out comments and string literals with a single left-to-right scan.

    Deliberately NOT two regex passes. Stripping comments first destroys the
    file, because `//` appears inside the string literal
    "http://127.0.0.1:8765" — the rest of that line vanishes including its
    closing quote, and every subsequent string boundary is then wrong. A
    scanner that tracks which construct it is inside is the only correct way
    to do this.
    """
    out = []
    i, n = 0, len(src)
    while i < n:
        ch = src[i]
        if ch == '"':                       # string literal
            out.append('""')
            i += 1
            while i < n and src[i] != '"':
                i += 2 if src[i] == "\\" else 1
            i += 1
        elif ch == "'":                     # char literal
            out.append("''")
            i += 1
            while i < n and src[i] != "'":
                i += 2 if src[i] == "\\" else 1
            i += 1
        elif src.startswith("//", i):       # line comment
            while i < n and src[i] != "\n":
                i += 1
        elif src.startswith("/*", i):       # block comment
            end = src.find("*/", i)
            i = n if end == -1 else end + 2
        else:
            out.append(ch)
            i += 1
    return "".join(out)


def _block_after(src: str, start: int) -> tuple[int, int]:
    """Span of the brace-delimited block that follows `start`."""
    i = src.index("{", start)
    depth = 0
    for j in range(i, len(src)):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                return i, j + 1
    raise AssertionError("unbalanced braces")


def _method_body(src: str, signature: str) -> str:
    idx = src.index(signature)
    a, b = _block_after(src, idx)
    return src[a:b]


def _without_worker_threads(body: str) -> str:
    """Delete every `new Thread(...)` body — that code is off the UI thread.

    Brace-matched rather than regex-matched, because these blocks contain
    nested anonymous classes (runOnUiThread) that defeat a non-greedy regex.
    """
    while True:
        m = re.search(r"new\s+Thread\s*\(", body)
        if not m:
            return body
        try:
            a, b = _block_after(body, m.start())
        except (ValueError, AssertionError):
            return body
        # Consume the trailing `, "name").start();` too.
        tail = body.find(";", b)
        body = body[:m.start()] + body[(tail + 1 if tail != -1 else b):]


def _ui_thread_calls(signature: str) -> list[str]:
    src = _strip_noise(_source())
    body = _method_body(src, signature)

    # Inline the private helpers a lifecycle callback calls synchronously, so
    # the check follows the call graph rather than stopping at one frame.
    for helper in ("private void bootstrap", "private void requestSmsPermissions",
                   "private void reportPermissionState"):
        name = helper.split()[-1]
        if re.search(rf"\b{name}\s*\(", body):
            body += _method_body(src, helper)

    remaining = _without_worker_threads(body)
    return sorted({call for call in EXPENSIVE if call in remaining})


# ── The invariant ─────────────────────────────────────────────────────────
@pytest.mark.parametrize("signature", LIFECYCLE)
def test_no_expensive_work_reachable_from_a_lifecycle_callback(signature):
    found = _ui_thread_calls(signature)
    detail = "; ".join(f"{c} ({EXPENSIVE[c]})" for c in found)
    assert not found, f"{signature} still does this on the UI thread: {detail}"


def test_the_checker_would_actually_catch_a_regression():
    """A guard that cannot fail proves nothing. This feeds the checker the
    pre-fix shape and asserts it is flagged."""
    pre_fix = """
    public void onCreate(Bundle b) {
        super.onCreate(b);
        SecretVault.migrate(appContext);
        final String token = getDeviceToken();
        SmsCatchUpWorker.schedule(getApplicationContext());
        new Thread(new Runnable() {
            public void run() { Python.start(x); }
        }, "worker").start();
    }
    """
    body = _without_worker_threads(_strip_noise(pre_fix))
    flagged = sorted({c for c in EXPENSIVE if c in body})
    assert "SecretVault.migrate" in flagged
    assert "getDeviceToken()" in flagged
    assert "SmsCatchUpWorker.schedule" in flagged
    # And the work correctly moved into the thread is NOT flagged.
    assert "Python.start" not in flagged


def test_the_bootstrap_worker_still_does_the_work():
    """Moving work off the UI thread must not mean dropping it."""
    src = _strip_noise(_source())
    body = _method_body(src, "private void bootstrap")
    for required in ("SecretVault.migrate", "getDeviceToken()",
                     "getSessionSecret()", "Python.start",
                     "SmsCatchUpWorker.schedule"):
        assert required in body, f"bootstrap no longer performs {required}"
    # ...and all of it inside the thread, none before it.
    assert not _ui_thread_calls("private void bootstrap")


def test_onresume_uses_the_cached_token_not_the_keystore():
    """onResume runs on every return to the app — the hottest main-thread
    path. It must read the cached value, not call into keystored."""
    src = _strip_noise(_source())
    assert "volatile String cachedDeviceToken" in src
    body = _method_body(src, "private void reportPermissionState")
    assert "cachedDeviceToken" in body
    assert not _ui_thread_calls("public void onResume")


def test_bootstrap_is_still_guarded_against_concurrent_starts():
    """Retry taps must not stack bootstraps now that more work is on the
    worker — a second thread would race on the keystore and the server."""
    src = _strip_noise(_source())
    body = _method_body(src, "private void bootstrap")
    assert "bootstrapping" in body
    assert "volatile boolean bootstrapping" in src


def test_the_webview_authenticates_itself_to_the_server():
    """B1 depends on this: without the grant every page returns 403, so the
    two fixes have to stay consistent with each other."""
    raw = _source()
    assert "/?k=" in raw, "the WebView no longer performs the auth grant"
    assert "Uri.encode(token)" in raw


@pytest.mark.filterwarnings("ignore::pytest.PytestUnhandledThreadExceptionWarning")
def test_a_dead_server_thread_reports_why(monkeypatch):
    """start_server() returns as soon as the thread is spawned, so an
    exception raised while building or serving the app never reached the
    Android activity. It polled /healthz, gave up, and showed "the app engine
    didn't respond" — a message that describes the symptom and hides the
    cause. The reason is now recorded where Java can ask for it.
    """
    import time
    from spendwise import android_entry as ae

    monkeypatch.setattr(ae, "_startup_error", None)
    monkeypatch.setattr(ae, "_server_thread", None)

    def boom(*a, **kw):
        raise RuntimeError("simulated on-device failure")

    monkeypatch.setattr(ae, "_serve", boom)
    ae.start_server("/tmp", "tok", "sec", port=8998)

    for _ in range(60):
        if ae.startup_error():
            break
        time.sleep(0.05)
    assert "simulated on-device failure" in ae.startup_error()
    assert "RuntimeError" in ae.startup_error()


def test_no_startup_error_is_reported_when_nothing_failed(monkeypatch):
    """The activity shows this string to the user, so a healthy launch must
    not put a stray value on the error screen."""
    from spendwise import android_entry as ae
    monkeypatch.setattr(ae, "_startup_error", None)
    assert ae.startup_error() == ""


def test_the_startup_timeout_leaves_room_for_a_cold_chaquopy_launch():
    """A first launch unpacks Python and the stdlib, imports Flask and
    waitress, and runs every schema migration against a database that does
    not exist yet. Twenty seconds covered a fast phone and not a slow one,
    where the activity reported a failure for a server that was still
    starting."""
    import pathlib
    src = pathlib.Path(__file__).resolve().parents[2] / (
        "android/app/src/main/java/com/jeevavibeapp/spendwise/MainActivity.java")
    raw = src.read_text()
    import re
    m = re.search(r"SERVER_TIMEOUT_MS\s*=\s*(\d+)L", raw)
    assert m, "the startup timeout constant moved"
    assert int(m.group(1)) >= 60000, \
        f"startup timeout is {m.group(1)}ms — too tight for a cold launch"


def test_the_startup_error_screen_shows_the_reason():
    """A retry button and nothing else asks the user to guess."""
    import pathlib
    src = pathlib.Path(__file__).resolve().parents[2] / (
        "android/app/src/main/java/com/jeevavibeapp/spendwise/MainActivity.java")
    raw = src.read_text()
    assert "pythonStartupError()" in raw, \
        "the activity no longer asks Python why it failed"
    assert "showStartupError(reason)" in raw
    assert "htmlEncode(reason)" in raw, \
        "the reason is interpolated into HTML without escaping"


def test_startup_reports_which_step_it_is_on(tmp_path):
    """A splash reading "Starting your money engine…" for ninety seconds and
    then failing tells nobody anything. The stage turns a hang into a located
    hang — "opening the database" and "importing the app" are different bugs
    — without needing a cable and adb.
    """
    import time
    from spendwise import android_entry as ae

    seen = []
    ae.start_server(str(tmp_path), "tok", "secret", port=8973)
    for _ in range(200):
        st = ae.startup_stage()
        if not seen or seen[-1] != st:
            seen.append(st)
        if st == "serving":
            break
        time.sleep(0.05)

    assert "serving" in seen, f"never reached serving; stages were {seen}"
    assert "opening the database" in seen, \
        f"the database step was never announced; stages were {seen}"
    # Every stage must read as a phrase for a person, not an identifier.
    for st in seen:
        assert st == st.lower() or " " in st, f"{st!r} is not a readable phrase"
        assert "_" not in st, f"{st!r} looks like an identifier"


def test_the_activity_puts_the_stage_on_the_loading_screen():
    """Java has to actually surface it, or the Python side is decoration."""
    import pathlib
    src = pathlib.Path(__file__).resolve().parents[2] / (
        "android/app/src/main/java/com/jeevavibeapp/spendwise/MainActivity.java")
    raw = src.read_text()
    assert "startup_stage" in raw, "the activity never asks Python for the stage"
    assert "showStage(" in raw
    assert "evaluateJavascript" in raw
    assert "JSONObject.quote(stage)" in raw, \
        "the stage is injected into JS without quoting"
    assert "Still at: " in raw, \
        "a stuck start with no exception still reports nothing"
