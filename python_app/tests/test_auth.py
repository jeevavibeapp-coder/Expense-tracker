"""B1 regression: the loopback server must authenticate every request.

The defect: `before_request` minted a session for *any* caller, so 36 of 38
endpoints were reachable with no cookie and no token. On Android every
co-installed app holding the normal-level INTERNET permission — granted at
install with no prompt — shares 127.0.0.1 with the embedded server. Such an
app could GET /export.csv (the entire ledger), read raw bank SMS from
/sms/misses.csv, POST forged transactions, purge the ledger, and approve
quarantined phishing messages into it.

Two earlier gates were reverted for real reasons, and the tests here pin both
so a future fix cannot reintroduce either regression:
  * an all-route gate also blocked /static, which black-screened the app;
  * an Origin check rejected the `Origin: null` Android WebViews send on form
    POSTs, which broke add and edit.
"""
from __future__ import annotations

import json
import urllib.parse

import pytest

from spendwise import db
from spendwise.app import create_app

TOKEN = "SECRET-DEVICE-TOKEN"

# Every endpoint that can read or write user data. Kept explicit rather than
# derived so that ADDING a route without classifying it fails this test.
DATA_GET = ["/dashboard", "/transactions", "/report", "/review", "/categories",
            "/fraud", "/merchant", "/import", "/settings", "/export.csv",
            "/sms/misses.csv", "/sms/quarantine"]
DATA_POST = [
    ("/transactions", {"amount": "1", "type": "expense", "merchant": "X"}),
    ("/transactions/resolve", {"raw": "X"}),
    ("/review/bulk", {"key": "X", "type": "expense"}),
    ("/sms/purge", {"scope": "all"}),
    ("/sms/prompts/dismiss", {}),
    ("/categories", {"name": "New"}),
    ("/profile", {"full_name": "Attacker"}),
    ("/settings", {"currency": "USD"}),
    ("/import/create", {"count": "0"}),
    ("/sms/quarantine/anything", {"action": "approve"}),
    ("/sms/senders/anything", {"trust": "trusted"}),
]


def _app(tmp_path, name="auth.db", token=TOKEN):
    return create_app(db_path=str(tmp_path / name), single_user=True,
                      secret_key="session-signing-key", device_token=token)


def _seed(app):
    """One real transaction, via the legitimate device path."""
    c = app.test_client()
    c.post("/sms/ingest",
           data={"sender": "AD-HDFCBK",
                 "body": "Rs.450.00 debited from a/c XX4521 on 12-07-26 to VPA "
                         "swiggy@ybl. Ref 402198877123"},
           headers={"X-SpendWise-Token": TOKEN},
           environ_overrides={"REMOTE_ADDR": "127.0.0.1"})


# ── The exploit, refused ──────────────────────────────────────────────────
@pytest.mark.parametrize("path", DATA_GET)
def test_unauthenticated_read_is_refused(tmp_path, path):
    app = _app(tmp_path, f"r{abs(hash(path))}.db")
    _seed(app)
    r = app.test_client().get(path)          # fresh client: no cookie, no token
    assert r.status_code == 403, f"{path} was readable without authentication"
    assert b"402198877123" not in r.data


@pytest.mark.parametrize("path,data", DATA_POST)
def test_unauthenticated_write_is_refused(tmp_path, path, data):
    app = _app(tmp_path, f"w{abs(hash(path))}.db")
    r = app.test_client().post(path, data=data)
    assert r.status_code == 403, f"{path} was writable without authentication"


def test_the_full_ledger_is_not_exfiltratable(tmp_path):
    """The headline exploit: GET /export.csv returned the whole ledger."""
    app = _app(tmp_path, "exfil.db")
    _seed(app)
    r = app.test_client().get("/export.csv")
    assert r.status_code == 403
    assert b"swiggy" not in r.data.lower()


def test_a_forged_transaction_cannot_be_injected(tmp_path):
    import sqlite3
    app = _app(tmp_path, "forge.db")
    _seed(app)
    app.test_client().post("/transactions", data={
        "amount": "99999", "type": "expense", "merchant": "FORGED"})
    conn = sqlite3.connect(app.config["DB_PATH"])
    names = [r[0] for r in conn.execute("SELECT merchant_name FROM transactions")]
    conn.close()
    assert "FORGED" not in names


# ── Credential forgery ────────────────────────────────────────────────────
@pytest.mark.parametrize("cookie", [
    "abc123",
    "eyJkZXZfZ3JhbnQiOnRydWV9.aaaaaaaa.bbbbbbbbbbbb",     # shaped like Flask's
    urllib.parse.quote(json.dumps({"dev_grant": True, "user_id": "x"})),
    ".eJyrVkpMTlbSUcpNzMlJLVKyUkpJLElMTgUAWZQHrQ==.aaaa.bbbb",
    "",
])
def test_a_forged_session_cookie_is_rejected(tmp_path, cookie):
    """The cookie is signed with the Keystore-held secret; a co-installed app
    can neither read nor forge it."""
    app = _app(tmp_path, f"c{abs(hash(cookie))}.db")
    c = app.test_client()
    c.set_cookie("session", cookie, domain="localhost")
    assert c.get("/export.csv").status_code == 403


def test_a_session_signed_with_a_different_key_is_rejected(tmp_path):
    """Simulates an attacker who guessed the cookie FORMAT but not the key."""
    other = create_app(db_path=str(tmp_path / "other.db"), single_user=True,
                       secret_key="attacker-key", device_token=TOKEN)
    oc = other.test_client()
    oc.get(f"/?k={TOKEN}")
    stolen = oc.get_cookie("session")
    assert stolen is not None

    victim = _app(tmp_path, "victim.db")
    vc = victim.test_client()
    vc.set_cookie("session", stolen.value, domain="localhost")
    assert vc.get("/export.csv").status_code == 403


@pytest.mark.parametrize("token", [
    "wrong", "", "SECRET", "secret-device-token",          # prefix / case
    TOKEN + "x", "x" + TOKEN, TOKEN[:-1], " " + TOKEN,
])
def test_an_invalid_device_token_grants_nothing(tmp_path, token):
    app = _app(tmp_path, f"t{abs(hash(token))}.db")
    c = app.test_client()
    assert c.get("/export.csv", headers={"X-SpendWise-Token": token}).status_code == 403
    assert c.get(f"/?k={urllib.parse.quote(token)}").status_code == 403


def test_a_rejected_grant_does_not_leave_a_usable_session(tmp_path):
    """A failed grant attempt must not partially authenticate the client."""
    app = _app(tmp_path, "partial.db")
    c = app.test_client()
    c.get("/?k=wrong")
    assert c.get("/export.csv").status_code == 403
    assert c.get("/dashboard").status_code == 403


def test_a_stale_session_from_a_rotated_key_is_rejected(tmp_path):
    """SecretVault.rotate() changes the signing key; old cookies must die."""
    path = str(tmp_path / "rot.db")
    first = create_app(db_path=path, single_user=True, secret_key="key-one",
                       device_token=TOKEN)
    c1 = first.test_client()
    c1.get(f"/?k={TOKEN}")
    old_cookie = c1.get_cookie("session")

    second = create_app(db_path=path, single_user=True, secret_key="key-two",
                        device_token=TOKEN)
    c2 = second.test_client()
    c2.set_cookie("session", old_cookie.value, domain="localhost")
    assert c2.get("/export.csv").status_code == 403


# ── Replay and concurrency ────────────────────────────────────────────────
def test_a_replayed_grant_url_still_requires_the_real_token(tmp_path):
    """The grant URL lands in WebView history. Replaying it is harmless — it
    carries the genuine token — but replaying a CAPTURED one from a different
    install must not work, because the token is per-install."""
    app_a = _app(tmp_path, "ra.db", token="token-install-A")
    app_b = _app(tmp_path, "rb.db", token="token-install-B")
    cb = app_b.test_client()
    assert cb.get("/?k=token-install-A").status_code == 403
    assert cb.get("/export.csv").status_code == 403


def test_concurrent_unauthenticated_requests_never_slip_through(tmp_path):
    """A race in the gate would be worth more to an attacker than a bypass."""
    import threading
    app = _app(tmp_path, "conc.db")
    _seed(app)
    codes: list[int] = []
    lock = threading.Lock()
    barrier = threading.Barrier(12)

    def hit() -> None:
        c = app.test_client()
        barrier.wait(timeout=20)
        r = c.get("/export.csv")
        with lock:
            codes.append(r.status_code)

    threads = [threading.Thread(target=hit) for _ in range(12)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=30)
    assert codes and set(codes) == {403}, f"gate raced: {sorted(set(codes))}"


def test_a_granted_session_is_not_shared_with_other_clients(tmp_path):
    """One authenticated WebView must not authenticate everyone else."""
    app = _app(tmp_path, "share.db")
    _seed(app)
    good = app.test_client()
    good.get(f"/?k={TOKEN}")
    assert good.get("/export.csv").status_code == 200

    attacker = app.test_client()             # same server, no cookie
    assert attacker.get("/export.csv").status_code == 403


# ── Malformed requests must fail closed ───────────────────────────────────
@pytest.mark.parametrize("path", [
    "/EXPORT.CSV", "/export.csv/", "//export.csv", "/static/../export.csv",
    "/./export.csv", "/%2e%2e/export.csv", "/export.csv%00",
])
def test_malformed_paths_do_not_bypass_the_gate(tmp_path, path):
    app = _app(tmp_path, f"m{abs(hash(path))}.db")
    _seed(app)
    # follow_redirects matters: Werkzeug answers "//export.csv" with a 308 to
    # the canonical path. A redirect is not a bypass, but only FOLLOWING it
    # proves the gate is applied at the destination.
    r = app.test_client().get(path, follow_redirects=True)
    assert r.status_code in (403, 404, 400), f"{path} -> {r.status_code}"
    assert b"402198877123" not in r.data


@pytest.mark.parametrize("headers", [
    {"X-Forwarded-For": "127.0.0.1"},
    {"X-Real-IP": "127.0.0.1"},
    {"Host": "localhost"},
    {"Origin": "null"},
    {"Referer": "http://127.0.0.1:8765/dashboard"},
    {"Cookie": "session=; session=forged"},
])
def test_header_spoofing_does_not_bypass_the_gate(tmp_path, headers):
    app = _app(tmp_path, f"h{abs(hash(str(headers)))}.db")
    _seed(app)
    assert app.test_client().get("/export.csv", headers=headers).status_code == 403


def test_a_non_loopback_caller_is_refused_even_with_a_valid_token(tmp_path):
    """Defence in depth: loopback is necessary as well as insufficient."""
    app = _app(tmp_path, "remote.db")
    r = app.test_client().get("/export.csv",
                              headers={"X-SpendWise-Token": TOKEN},
                              environ_overrides={"REMOTE_ADDR": "10.0.0.5"})
    assert r.status_code == 403


# ── The two regressions that killed earlier gates ─────────────────────────
def test_static_assets_stay_public(tmp_path):
    """Gating /static black-screened the app: the page rendered but app.js
    403'd, so nothing was interactive."""
    app = _app(tmp_path, "static.db")
    r = app.test_client().get("/static/app.js")
    assert r.status_code in (200, 304)


def test_healthz_stays_public(tmp_path):
    """MainActivity.waitForServer() polls this BEFORE it can grant, so gating
    it would make the app conclude the server never started."""
    app = _app(tmp_path, "hz.db")
    r = app.test_client().get("/healthz")
    assert r.status_code == 200
    assert b"402198877123" not in r.data


def test_form_posts_work_with_origin_null(tmp_path):
    """Android WebViews send `Origin: null` on form POSTs. An Origin-based
    CSRF check rejected those, which broke add and edit on-device."""
    app = _app(tmp_path, "origin.db")
    c = app.test_client()
    c.get(f"/?k={TOKEN}")
    r = c.post("/transactions",
               data={"amount": "250", "type": "expense", "merchant": "Origin Test"},
               headers={"Origin": "null"}, follow_redirects=True)
    assert r.status_code == 200
    conn = db.connect(app.config["DB_PATH"])
    names = [x[0] for x in conn.execute("SELECT merchant_name FROM transactions")]
    conn.close()
    assert "Origin Test" in names


# ── Classification completeness ───────────────────────────────────────────
def test_every_route_is_explicitly_classified(tmp_path):
    """A new route must not default to reachable. Anything not PUBLIC and not
    DEVICE has to be refused without a grant — this enumerates the real URL
    map rather than a hand-written list, so adding a route fails here."""
    app = _app(tmp_path, "classify.db")
    _seed(app)
    public = {"static", "healthz"}
    device = {"sms_ingest", "device_state"}
    unprotected = []
    for rule in app.url_map.iter_rules():
        if rule.endpoint in public or rule.endpoint in device:
            continue
        if "GET" not in rule.methods:
            continue
        path = rule.rule
        if "<" in path:                       # needs an id; substitute a dummy
            path = path.replace("<tx_id>", "x").replace("<cat_id>", "x") \
                       .replace("<alert_id>", "x").replace("<qid>", "x") \
                       .replace("<sid>", "x")
        if "<" in path:
            continue
        code = app.test_client().get(path).status_code
        if code not in (403, 404, 400):
            unprotected.append((rule.endpoint, path, code))
    assert not unprotected, f"reachable without authentication: {unprotected}"


def test_android_entry_always_supplies_a_device_token():
    """The loopback-only fallback mode must never be reachable on a device.
    MainActivity always passes a Keystore-backed token, so create_app always
    gets one — this pins the call contract that guarantees it."""
    import inspect
    from spendwise import android_entry
    # The whole module, not one function: create_app has already moved once
    # (from _run into _serve, when startup failures started being recorded),
    # and this contract must survive the next refactor too.
    src = inspect.getsource(android_entry)
    assert "device_token=token" in src
    params = list(inspect.signature(android_entry.start_server).parameters)
    assert params[:3] == ["files_dir", "token", "secret"]


def test_only_the_exact_token_content_grants_access(tmp_path):
    """A red-team run flagged `X-SpendWise-Token: <token>\\t` as succeeding
    against the real waitress server. It is not a bypass.

    RFC 7230 s3.2.4 has the HTTP parser strip optional whitespace around a
    field value, so the padded variant arrives as EXACTLY the token — the
    caller still had to know the full secret. Verified at socket level against
    waitress: token/token+TAB/token+SPACE/TAB+token all returned 200, while
    token[:-1], token+"X" and "WRONG" all returned 403.

    (Flask's test client does not model that stripping and passes the value
    verbatim, so it returns 403 for the padded forms. Both behaviours are
    safe, which is why this test asserts the property that holds in both:
    changing the token's CONTENT never grants access.)
    """
    app = _app(tmp_path, "ws.db")
    assert app.test_client().get(
        "/export.csv", headers={"X-SpendWise-Token": TOKEN}).status_code == 200
    for wrong in (TOKEN[:-1], TOKEN + "X", TOKEN[1:], TOKEN.replace("-", "_"),
                  TOKEN.lower(), "WRONG", TOKEN * 2):
        assert app.test_client().get(
            "/export.csv",
            headers={"X-SpendWise-Token": wrong}).status_code == 403, wrong
