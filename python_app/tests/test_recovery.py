"""B3 regression: a failed migration must never brick the app.

The defect: `db.open_database` called `init_db` unguarded, so a MigrationError
propagated out of `create_app` into `android_entry._run` and killed the server
thread. The native layer then timed out waiting for /healthz and showed
"Couldn't start SpendWise" with a Retry button — and Retry re-ran the identical
migration. A deterministic permanent brick, with a verified backup sitting
unused on disk. `maintenance.recover()` existed but was wired only to
corruption, never to migration failure.

Worse, `migrations.py` documented the guarantee as "Recoverable ... see
maintenance.safe_upgrade" — and `maintenance.safe_upgrade` does not exist.
"""
from __future__ import annotations

import os
import resource
import sqlite3

import pytest

from spendwise import db, maintenance, migrations
from spendwise.app import create_app
from tests.test_migrations import LEGACY_V1


def _legacy(path: str, rows: int = 200) -> None:
    conn = sqlite3.connect(path)
    conn.executescript(LEGACY_V1)
    conn.execute("INSERT INTO users VALUES ('u1','a@b.c','U','x','2024-01-01')")
    conn.execute("INSERT INTO categories(id,user_id,name) VALUES ('c1','u1','Food')")
    conn.executemany(
        "INSERT INTO transactions(id,user_id,amount,type,category_id,occurred_at,"
        "source,status,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
        [(f"t{i}", "u1", 100.0 + i, "expense", "c1", "2025-01-01T10:00:00",
          "manual", "confirmed", "2025-01-01T10:00:00") for i in range(rows)])
    conn.commit()
    conn.close()


class _Boom(Exception):
    pass


def _break_migration(monkeypatch, index: int, *, always: bool):
    """Replace migration `index` with one that fails.

    ``always=False`` fails once then succeeds, modelling a transient cause
    (full disk, locked file, OOM kill). ``always=True`` models a deterministic
    bug in the migration itself.
    """
    original = migrations.MIGRATIONS[index]
    state = {"calls": 0}

    def broken(conn):
        state["calls"] += 1
        if always or state["calls"] == 1:
            original(conn)                    # do the work, THEN fail
            raise _Boom("simulated migration failure")
        return original(conn)

    patched = list(migrations.MIGRATIONS)
    patched[index] = broken
    monkeypatch.setattr(migrations, "MIGRATIONS", patched)
    return state


# ── Transient failure: recovers automatically ─────────────────────────────
def test_a_transient_migration_failure_recovers_on_the_retry(tmp_path, monkeypatch):
    path = str(tmp_path / "transient.db")
    _legacy(path)
    state = _break_migration(monkeypatch, 5, always=False)   # v6 table rebuild

    conn, status = db.open_database(path)

    assert state["calls"] >= 2, "no retry was attempted"
    assert status["version"] == migrations.SCHEMA_VERSION
    assert status["recovered"] is True
    assert status["migration_rolled_back"] is True
    assert status["safe_mode"] is False
    assert conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0] == 200
    conn.close()


# ── Deterministic failure: safe state, no crash, no loop ──────────────────
def test_a_deterministic_migration_failure_does_not_raise(tmp_path, monkeypatch):
    """The core of B3: open_database must not propagate the exception."""
    path = str(tmp_path / "det.db")
    _legacy(path)
    _break_migration(monkeypatch, 5, always=True)

    conn, status = db.open_database(path)     # must NOT raise

    assert status["safe_mode"] is True
    assert status["migration_error"]
    assert conn is not None
    conn.close()


def test_a_deterministic_failure_leaves_the_data_intact_and_readable(tmp_path, monkeypatch):
    path = str(tmp_path / "det2.db")
    _legacy(path)
    _break_migration(monkeypatch, 5, always=True)

    conn, status = db.open_database(path)
    assert conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0] == 200
    total = conn.execute("SELECT ROUND(SUM(amount),2) FROM transactions").fetchone()[0]
    assert total == round(sum(100.0 + i for i in range(200)), 2)
    # Rolled back to a schema the PREVIOUS build could open.
    assert status["version"] < migrations.SCHEMA_VERSION
    conn.close()


def test_the_app_serves_a_safe_mode_page_instead_of_crashing(tmp_path, monkeypatch):
    """create_app must return a working app, not raise. This is what turns a
    brick into an explanation."""
    path = str(tmp_path / "det3.db")
    _legacy(path)
    _break_migration(monkeypatch, 5, always=True)

    app = create_app(db_path=path, single_user=True, secret_key="s",
                     device_token="tok")
    assert app.config.get("SAFE_MODE") is True
    c = app.test_client()

    # /healthz must answer, or the native layer concludes the server never
    # started and shows its own error screen with a Retry that loops.
    hz = c.get("/healthz")
    assert hz.status_code == 200
    assert hz.get_json()["status"] == "safe_mode"

    page = c.get("/")
    assert page.status_code == 503
    assert b"transactions are safe" in page.data
    assert b"don" in page.data              # "please don't reinstall"


def test_safe_mode_never_crash_loops(tmp_path, monkeypatch):
    """Simulates the user tapping Retry repeatedly: every attempt must end in
    the same safe state, never an exception and never data loss."""
    path = str(tmp_path / "loop.db")
    _legacy(path)
    _break_migration(monkeypatch, 5, always=True)

    for attempt in range(5):
        app = create_app(db_path=path, single_user=True, secret_key="s",
                         device_token="tok")
        assert app.config.get("SAFE_MODE") is True, f"attempt {attempt}"
        assert app.test_client().get("/healthz").status_code == 200

    conn = sqlite3.connect(path)
    assert conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0] == 200
    conn.close()


def test_recovery_from_a_failure_in_every_migration_step(tmp_path, monkeypatch):
    """Not just v6. Any step can fail, and each must land safely."""
    for index in range(len(migrations.MIGRATIONS)):
        mp = pytest.MonkeyPatch()
        try:
            path = str(tmp_path / f"step{index}.db")
            _legacy(path)
            _break_migration(mp, index, always=True)
            conn, status = db.open_database(path)
            assert conn is not None, index
            # Either it recovered, or it degraded safely — never an exception.
            assert status["safe_mode"] or status["version"] == migrations.SCHEMA_VERSION
            n = conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0]
            assert n == 200, f"step {index} lost data: {n}"
            conn.close()
        finally:
            mp.undo()


# ── Backup / restore / rollback primitives ────────────────────────────────
def test_a_pre_migration_backup_is_taken_and_is_verifiable(tmp_path):
    path = str(tmp_path / "bk.db")
    _legacy(path)
    conn, status = db.open_database(path)
    conn.close()
    assert status["backup"], "no pre-migration backup was taken"
    assert maintenance.verify_backup(status["backup"])
    b = sqlite3.connect(status["backup"])
    assert b.execute("SELECT COUNT(*) FROM transactions").fetchone()[0] == 200
    b.close()


def test_restore_preserves_the_damaged_file_for_salvage(tmp_path, monkeypatch):
    path = str(tmp_path / "salv.db")
    _legacy(path)
    _break_migration(monkeypatch, 5, always=True)
    conn, _ = db.open_database(path)
    conn.close()
    leftovers = [f for f in os.listdir(tmp_path) if ".corrupt-" in f]
    assert leftovers, "the failed database was destroyed rather than kept"


def test_restore_refuses_an_unverifiable_backup(tmp_path):
    """Restoring garbage over a working ledger would be worse than failing."""
    path = str(tmp_path / "v.db")
    _legacy(path)
    bad = str(tmp_path / "bad.bak")
    with open(bad, "wb") as f:
        f.write(b"not a database at all")
    assert maintenance.verify_backup(bad) is False
    assert maintenance.restore_backup(path, bad) is False
    conn = sqlite3.connect(path)
    assert conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0] == 200
    conn.close()


def test_a_truncated_backup_is_rejected(tmp_path):
    path = str(tmp_path / "tr.db")
    _legacy(path)
    conn, status = db.open_database(path)
    conn.close()
    with open(status["backup"], "r+b") as f:
        f.truncate(200)
    assert maintenance.verify_backup(status["backup"]) is False


# ── Environmental failures ────────────────────────────────────────────────
def test_a_full_disk_during_migration_does_not_lose_data(tmp_path):
    """Storage pressure is the most likely real cause of a failed upgrade."""
    path = str(tmp_path / "full.db")
    _legacy(path, rows=400)
    before = os.path.getsize(path)
    soft, hard = resource.getrlimit(resource.RLIMIT_FSIZE)
    resource.setrlimit(resource.RLIMIT_FSIZE, (before + 8192, hard))
    try:
        conn, status = db.open_database(path)   # must not raise
        conn.close()
    except sqlite3.DatabaseError:
        pass                                    # acceptable: surfaced, not silent
    finally:
        resource.setrlimit(resource.RLIMIT_FSIZE, (soft, hard))

    conn = sqlite3.connect(path)
    assert conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    assert conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0] == 400
    conn.close()


def test_a_locked_database_does_not_destroy_the_ledger(tmp_path):
    """Another process (the SMS receiver) holding a write lock during upgrade."""
    path = str(tmp_path / "lock.db")
    _legacy(path)
    holder = sqlite3.connect(path, timeout=0.1)
    holder.execute("BEGIN EXCLUSIVE")
    try:
        try:
            conn, status = db.open_database(path)
            conn.close()
        except sqlite3.OperationalError:
            pass                                # surfaced as a lock error
    finally:
        holder.rollback()
        holder.close()
    conn = sqlite3.connect(path)
    assert conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0] == 200
    conn.close()


def test_power_loss_mid_migration_leaves_a_consistent_file(tmp_path, monkeypatch):
    """Each migration runs in its own transaction; an abrupt stop must leave
    the file on the last COMPLETED version, never half-applied."""
    path = str(tmp_path / "power.db")
    _legacy(path)
    _break_migration(monkeypatch, 5, always=True)
    conn, status = db.open_database(path)
    version = migrations.current_version(conn)
    conn.close()

    raw = sqlite3.connect(path)
    assert raw.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    # The version on disk is a real, fully-applied one.
    assert 0 <= version <= migrations.SCHEMA_VERSION
    assert raw.execute("SELECT COUNT(*) FROM transactions").fetchone()[0] == 200
    raw.close()


def test_corruption_recovery_still_works(tmp_path):
    """The pre-existing path must not have regressed."""
    path = str(tmp_path / "corrupt.db")
    _legacy(path)
    conn, _ = db.open_database(path)          # creates a verified backup
    conn.close()
    with open(path, "r+b") as f:
        f.seek(0)
        f.write(b"\x00" * 4096)
    conn, status = db.open_database(path)
    assert status["recovered"] or status["reset"]
    conn.close()


def test_the_documented_recovery_helper_actually_exists():
    """migrations.py promised `maintenance.safe_upgrade`, which did not exist.
    A guarantee documented but not implemented is worse than none."""
    doc = migrations.__doc__ or ""
    if "safe_upgrade" in doc:
        assert hasattr(maintenance, "safe_upgrade"), \
            "the docstring names a recovery helper that does not exist"


def test_lock_contention_does_not_trigger_spurious_safe_mode(tmp_path):
    """Found by the red-team pass, not known beforehand.

    Under 16-way concurrent startup one instance entered safe mode and showed
    "SpendWise couldn't finish updating" — because a migration lost a lock
    race, and the second-failure path treated any MigrationError as
    deterministic. Contention is transient: the next launch succeeds, so
    re-raising (and letting the caller retry) is correct, while degrading is a
    frightening and wrong message.
    """
    import threading
    path = str(tmp_path / "contend.db")
    codes: list[int] = []
    errors: list[str] = []
    lock = threading.Lock()
    barrier = threading.Barrier(12)

    def boot() -> None:
        try:
            barrier.wait(timeout=30)
            app = create_app(db_path=path, single_user=True, secret_key="s",
                             device_token="TOK")
            c = app.test_client()
            c.get("/?k=TOK")
            with lock:
                codes.append(c.get("/dashboard").status_code)
        except Exception as exc:                      # noqa: BLE001
            with lock:
                errors.append(f"{type(exc).__name__}: {exc}")

    threads = [threading.Thread(target=boot) for _ in range(12)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=60)

    assert not errors, errors
    assert 503 not in codes, "lock contention produced a spurious safe-mode page"
    assert set(codes) == {200}, sorted(set(codes))
    conn = sqlite3.connect(path)
    assert conn.execute("SELECT COUNT(*) FROM users").fetchone()[0] == 1
    assert conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    conn.close()


def test_contention_is_distinguished_from_damage():
    """The single discriminator both recovery paths depend on."""
    assert db._is_contention(sqlite3.OperationalError("database is locked"))
    assert db._is_contention(sqlite3.OperationalError("database table is busy"))
    assert not db._is_contention(sqlite3.DatabaseError("database disk image is malformed"))
    assert not db._is_contention(RuntimeError("simulated migration failure"))
    assert not db._is_contention(None)
