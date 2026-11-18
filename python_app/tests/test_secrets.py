"""Secret handling and the Android Keystore upgrade path.

Before this work the Flask session key was generated in Python and written as
plaintext into the `app_state` table of the ledger file, and the loopback
device token sat in plaintext SharedPreferences. Both are now supplied by
`SecretVault` (AES-256/GCM under an AndroidKeyStore key) and handed to the
server at launch.

The Java half needs a device to exercise; what is testable here — and what
actually decides whether the upgrade is safe — is the Python contract:

  * an externally supplied key is used verbatim, and
  * the plaintext copy an older install left in the database is erased,
  * without breaking desktop/dev use, where there is no keystore to ask.
"""
from __future__ import annotations

import inspect
import sqlite3

import pytest

from spendwise import android_entry, db
from spendwise.app import create_app


def _app_state(path: str, key: str):
    conn = sqlite3.connect(path)
    try:
        row = conn.execute("SELECT value FROM app_state WHERE key=?", (key,)).fetchone()
        return row[0] if row else None
    finally:
        conn.close()


def _legacy_plaintext_install(path: str, secret: str = "old-plaintext-secret") -> None:
    """Recreate exactly what a pre-Keystore build left on disk."""
    conn, _ = db.open_database(path, backup=False)
    conn.execute("INSERT OR REPLACE INTO app_state(key, value) VALUES ('secret_key', ?)",
                 (secret,))
    conn.commit()
    conn.close()


# ── The keystore path ─────────────────────────────────────────────────────
def test_external_secret_is_used_verbatim(tmp_path):
    app = create_app(db_path=str(tmp_path / "a.db"), single_user=True,
                     secret_key="from-keystore")
    assert app.secret_key == "from-keystore"
    assert app.config["SECRET_SOURCE"] == "external"


def test_upgrade_erases_the_plaintext_key_left_by_older_builds(tmp_path):
    """The whole point of the migration: no plaintext key survives it."""
    path = str(tmp_path / "b.db")
    _legacy_plaintext_install(path)
    assert _app_state(path, "secret_key") == "old-plaintext-secret"

    app = create_app(db_path=path, single_user=True, secret_key="from-keystore")

    assert _app_state(path, "secret_key") is None, "plaintext session key survived upgrade"
    assert app.config["SECRET_PURGED_LEGACY"] is True
    assert app.secret_key == "from-keystore"


def test_upgrade_does_not_report_a_purge_on_a_fresh_install(tmp_path):
    """Only an actual erase counts — otherwise the flag would be noise."""
    app = create_app(db_path=str(tmp_path / "c.db"), single_user=True,
                     secret_key="from-keystore")
    assert app.config["SECRET_PURGED_LEGACY"] is False


def test_purge_repeats_after_a_downgrade_reintroduces_plaintext(tmp_path):
    """Install keystore build -> sideload an old APK -> upgrade again.

    The old build rewrites the plaintext key. Purging on every boot rather
    than once means the second upgrade cleans it up again.
    """
    path = str(tmp_path / "d.db")
    create_app(db_path=path, single_user=True, secret_key="k1")
    # The downgraded build runs without a keystore and re-persists a key.
    downgraded = create_app(db_path=path, single_user=True)
    assert downgraded.config["SECRET_SOURCE"] == "database-new"
    assert _app_state(path, "secret_key") is not None
    # Upgrade again.
    app = create_app(db_path=path, single_user=True, secret_key="k2")
    assert _app_state(path, "secret_key") is None
    assert app.config["SECRET_PURGED_LEGACY"] is True


def test_no_secret_shaped_value_remains_in_app_state_after_keystore_boot(tmp_path):
    """Audit assertion: app_state must hold settings, never credentials."""
    path = str(tmp_path / "e.db")
    _legacy_plaintext_install(path)
    create_app(db_path=path, single_user=True, secret_key="from-keystore")
    conn = sqlite3.connect(path)
    keys = [r[0] for r in conn.execute("SELECT key FROM app_state").fetchall()]
    conn.close()
    for k in keys:
        assert not any(w in k.lower() for w in ("secret", "token", "key", "password")), \
            f"credential-shaped row still in app_state: {k}"


def test_sessions_work_with_a_keystore_supplied_key(tmp_path):
    """A supplied key must actually sign cookies, not just be stored."""
    app = create_app(db_path=str(tmp_path / "f.db"), single_user=True,
                     secret_key="a" * 64)
    c = app.test_client()
    # "/" redirects to the dashboard once a session exists, so following the
    # redirect proves the cookie was signed with this key and read back.
    r = c.get("/", follow_redirects=True)
    assert r.status_code == 200
    assert b"login" not in r.request.path.encode()
    with c.session_transaction() as sess:
        assert sess.get("user_id")


def test_rotating_the_key_invalidates_old_cookies_without_breaking_the_app(tmp_path):
    """SecretVault.rotate() must be survivable — the app re-establishes."""
    path = str(tmp_path / "g.db")
    first = create_app(db_path=path, single_user=True, secret_key="key-one")
    c1 = first.test_client()
    c1.get("/")
    cookie = c1.get_cookie("session")
    assert cookie is not None

    second = create_app(db_path=path, single_user=True, secret_key="key-two")
    c2 = second.test_client()
    c2.set_cookie("session", cookie.value, domain="localhost")
    r = c2.get("/", follow_redirects=True)
    # The old cookie fails signature verification. Rather than erroring, the
    # single-user hook establishes a fresh session, so the app still serves.
    assert r.status_code == 200
    assert r.request.path != "/login"
    assert c2.get_cookie("session").value != cookie.value


# ── The desktop/dev path must not regress ─────────────────────────────────
def test_generated_key_is_persisted_and_reused_across_restarts(tmp_path):
    path = str(tmp_path / "h.db")
    a = create_app(db_path=path, single_user=True)
    assert a.config["SECRET_SOURCE"] == "database-new"
    b = create_app(db_path=path, single_user=True)
    assert b.config["SECRET_SOURCE"] == "database"
    assert a.secret_key == b.secret_key, "sessions would break on every restart"


def test_env_var_secret_is_treated_as_external(tmp_path, monkeypatch):
    path = str(tmp_path / "i.db")
    _legacy_plaintext_install(path)
    monkeypatch.setenv("SPENDWISE_SECRET", "from-env")
    app = create_app(db_path=path, single_user=True)
    assert app.secret_key == "from-env"
    assert app.config["SECRET_SOURCE"] == "external"
    assert _app_state(path, "secret_key") is None


# ── The Java -> Python call contract ──────────────────────────────────────
def test_start_server_accepts_the_secret_as_the_third_positional_arg():
    """MainActivity calls callAttr("start_server", filesDir, token, secret).

    Chaquopy passes those positionally, so the parameter ORDER is the
    interface. Reordering it would silently pass the secret as the host.
    """
    params = list(inspect.signature(android_entry.start_server).parameters)
    assert params[:3] == ["files_dir", "token", "secret"]


def test_run_forwards_the_secret_into_create_app(monkeypatch, tmp_path):
    seen = {}

    def fake_create_app(**kw):
        seen.update(kw)
        raise RuntimeError("stop before serving")

    monkeypatch.setattr("spendwise.app.create_app", fake_create_app)
    with pytest.raises(RuntimeError):
        android_entry._run(str(tmp_path / "j.db"), "127.0.0.1", 0, "tok", "sec")
    assert seen["secret_key"] == "sec"
    assert seen["device_token"] == "tok"
    assert seen["single_user"] is True
