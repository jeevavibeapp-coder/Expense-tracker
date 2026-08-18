"""Entry point used by the Android app (Chaquopy) to run SpendWise on-device.

The Android `MainActivity` starts a background thread that calls
``start_server(files_dir, token)``; this launches the Flask app on 127.0.0.1 so
the Capacitor WebView can load it. Everything runs fully offline inside the APK.
"""
from __future__ import annotations

import json
import os
import threading
import time
import urllib.parse
import urllib.request

_server_thread: "threading.Thread | None" = None
_lock = threading.Lock()
_device_token: "str | None" = None
_session_secret: "str | None" = None
# Set by the server thread when startup fails, so the activity can show
# the real reason instead of a generic timeout message.
_startup_error: "str | None" = None

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8765

# Finance SMS that arrived while the app was closed are appended here by the
# Android SMS receiver, then drained into the app on the next launch.
INBOX_NAME = "sms_inbox.jsonl"


def _run(db_path: str, host: str, port: int, token: "str | None",
         secret: "str | None" = None) -> None:
    """Build the app and serve it. Runs on a daemon thread.

    Anything that goes wrong in here used to vanish. Java calls start_server(),
    which returns as soon as the thread is spawned, so an import error or a
    failed migration on THIS thread never reached the caller — the activity
    just polled /healthz for twenty seconds and put up "the app engine didn't
    respond", which says nothing about what actually happened. The failure is
    now recorded where Java can ask for it.
    """
    global _startup_error
    try:
        _serve(db_path, host, port, token, secret)
    except BaseException as exc:                     # noqa: BLE001
        _startup_error = f"{type(exc).__name__}: {exc}"
        try:
            import traceback
            traceback.print_exc()                    # lands in logcat
        except Exception:
            pass
        raise


def _serve(db_path: str, host: str, port: int, token: "str | None",
           secret: "str | None" = None) -> None:
    from spendwise.app import create_app

    # `secret` comes from the Android Keystore (SecretVault). Passing it here
    # means the session key never has to be persisted in the database;
    # create_app also erases any plaintext copy an older build left behind.
    app = create_app(db_path=db_path, single_user=True, device_token=token,
                     secret_key=secret)
    try:
        # waitress is pure Python (py3-none-any), so it runs under Chaquopy.
        # Werkzeug's dev server is explicitly not for production: it has no
        # connection cap or timeouts, so any co-installed app could open
        # sockets to the loopback port until the process is killed — taking an
        # in-flight ledger write with it. These limits bound that.
        from waitress import serve
        serve(app, host=host, port=port,
              threads=4,               # a single on-device user
              connection_limit=32,     # refuse floods instead of exhausting RAM
              channel_timeout=30,      # reap slow/stalled sockets (slowloris)
              ident=None,              # don't advertise the server banner
              clear_untrusted_proxy_headers=True)
    except ImportError:
        # Never leave the user without an app if the wheel is missing from a
        # given build; fall back with the same bounded intent.
        app.run(host=host, port=port, threaded=True, use_reloader=False, debug=False)


def _ingest(base_url: str, token: "str | None", item: dict) -> None:
    data = urllib.parse.urlencode(
        {"sender": item.get("sender") or "", "body": item.get("body") or ""}).encode()
    req = urllib.request.Request(f"{base_url}/sms/ingest", data=data)
    if token:
        req.add_header("X-SpendWise-Token", token)
    # Raises HTTPError on non-2xx, so failed ingests are kept in the queue.
    urllib.request.urlopen(req, timeout=5)


def _drain_inbox(base: str, host: str, port: int, token: "str | None") -> None:
    """Wait for the server, then replay any SMS queued while it was offline.

    Atomically rotates the queue file before processing so messages appended by
    the receiver *during* the drain are never clobbered.
    """
    path = os.path.join(base, INBOX_NAME)
    base_url = f"http://{host}:{port}"
    # Wait (up to ~30s) for the server to accept requests.
    for _ in range(120):
        try:
            urllib.request.urlopen(f"{base_url}/healthz", timeout=1)
            break
        except Exception:
            time.sleep(0.25)
    if not os.path.exists(path):
        return
    # Claim the current queue by renaming it; concurrent appends create a fresh
    # file that a later drain will pick up.
    work = "%s.%d.%d.draining" % (path, os.getpid(), int(time.time() * 1000))
    try:
        os.rename(path, work)
    except OSError:
        return  # another drain already claimed it, or it vanished
    try:
        with open(work, "r", encoding="utf-8") as f:
            lines = f.readlines()
    except OSError:
        return
    remaining = []
    for line in lines:
        line = line.strip()
        if not line:
            continue
        try:
            item = json.loads(line)
        except ValueError:
            continue  # drop unparseable lines
        try:
            _ingest(base_url, token, item)
        except Exception:
            remaining.append(line)  # keep for the next launch on failure
    # Re-queue failures by appending to the live file (which may now hold newly
    # arrived messages), then drop the work file.
    try:
        if remaining:
            with open(path, "a", encoding="utf-8") as f:
                f.write("\n".join(remaining) + "\n")
        os.remove(work)
    except OSError:
        pass


def start_server(files_dir: str = None, token: str = None, secret: str = None,
                 host: str = DEFAULT_HOST, port: int = DEFAULT_PORT) -> str:
    """Start the embedded server (once) and drain any queued SMS.

    Safe to call multiple times — only the first call starts the server thread,
    but every call kicks a drain (cheap; atomic rotation makes it idempotent).
    Called from Java via Chaquopy: ``getModule("spendwise.android_entry")
    .callAttr("start_server", filesDir, token)``.
    """
    global _server_thread, _device_token, _session_secret
    base = files_dir or os.getcwd()
    db_path = os.path.join(base, "spendwise.db")
    with _lock:
        if token:
            _device_token = token
        if secret:
            _session_secret = secret
        tok = _device_token
        sec = _session_secret
        if _server_thread is None or not _server_thread.is_alive():
            _server_thread = threading.Thread(
                target=_run, args=(db_path, host, port, tok, sec), daemon=True,
                name="spendwise-server")
            _server_thread.start()
    # Replay queued SMS once the server is ready (off the main thread). Runs on
    # every call so messages queued within a warm process still drain.
    threading.Thread(target=_drain_inbox, args=(base, host, port, tok),
                     daemon=True, name="spendwise-sms-drain").start()
    return f"http://{host}:{port}"


def is_running() -> bool:
    return _server_thread is not None and _server_thread.is_alive()


def startup_error() -> str:
    """The exception that killed the server thread, or "" if there was none.

    Called from Java when the readiness poll times out. A string rather than
    an object because it crosses the Chaquopy boundary and is only ever shown
    to a person.
    """
    return _startup_error or ""
