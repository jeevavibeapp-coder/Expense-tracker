"""Migration, durability and recovery tests.

These cover the failure modes that destroy user data rather than merely
annoying the user, and which had ZERO coverage before: upgrading a database
created by an older build, and surviving corruption.
"""
from __future__ import annotations

import os
import sqlite3

from spendwise import db, maintenance, migrations
from spendwise.app import create_app


# ── Legacy schema fixtures ────────────────────────────────────────────────
# The v1.0 shape, before budget_amount / dedup_key / sms_body existed.
LEGACY_V1 = """
CREATE TABLE users (id TEXT PRIMARY KEY, email TEXT UNIQUE NOT NULL,
    full_name TEXT NOT NULL, pw_hash TEXT NOT NULL, created_at TEXT NOT NULL);
CREATE TABLE categories (id TEXT PRIMARY KEY, user_id TEXT NOT NULL,
    name TEXT NOT NULL, type TEXT NOT NULL DEFAULT 'expense',
    icon TEXT NOT NULL DEFAULT 'Tag', color TEXT NOT NULL DEFAULT '#6366f1',
    is_archived INTEGER NOT NULL DEFAULT 0, UNIQUE(user_id, name));
CREATE TABLE merchants (id TEXT PRIMARY KEY, user_id TEXT NOT NULL,
    canonical_name TEXT NOT NULL, category_id TEXT, UNIQUE(user_id, canonical_name));
CREATE TABLE learning (id TEXT PRIMARY KEY, user_id TEXT NOT NULL,
    raw_name TEXT NOT NULL, merchant_id TEXT NOT NULL, merchant_name TEXT NOT NULL,
    category_id TEXT, confidence INTEGER NOT NULL DEFAULT 0,
    confirmation_count INTEGER NOT NULL DEFAULT 0,
    correction_count INTEGER NOT NULL DEFAULT 0, sample_count INTEGER NOT NULL DEFAULT 0,
    avg_amount REAL NOT NULL DEFAULT 0, amount_min REAL NOT NULL DEFAULT 0,
    amount_max REAL NOT NULL DEFAULT 0, hour_histogram TEXT NOT NULL DEFAULT '[]',
    last_seen_at TEXT, UNIQUE(user_id, raw_name, merchant_id));
CREATE TABLE transactions (id TEXT PRIMARY KEY, user_id TEXT NOT NULL,
    amount REAL NOT NULL, type TEXT NOT NULL DEFAULT 'expense', category_id TEXT,
    raw_merchant TEXT, merchant_id TEXT, merchant_name TEXT, notes TEXT,
    reference_number TEXT, occurred_at TEXT NOT NULL,
    source TEXT NOT NULL DEFAULT 'manual', confidence INTEGER,
    status TEXT NOT NULL DEFAULT 'confirmed', is_deleted INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL);
CREATE TABLE fraud_alerts (id TEXT PRIMARY KEY, user_id TEXT NOT NULL,
    transaction_id TEXT, alert_type TEXT NOT NULL,
    severity TEXT NOT NULL DEFAULT 'low', message TEXT NOT NULL,
    details TEXT NOT NULL DEFAULT '{}', status TEXT NOT NULL DEFAULT 'open',
    created_at TEXT NOT NULL);
CREATE TABLE settings (user_id TEXT PRIMARY KEY, currency TEXT NOT NULL DEFAULT 'INR',
    theme TEXT NOT NULL DEFAULT 'system', auto_save_threshold INTEGER NOT NULL DEFAULT 80,
    confirm_threshold INTEGER NOT NULL DEFAULT 50, high_value_amount REAL);
CREATE TABLE app_state (key TEXT PRIMARY KEY, value TEXT);
"""


def _legacy_db(path: str) -> None:
    conn = sqlite3.connect(path)
    conn.executescript(LEGACY_V1)
    conn.execute("INSERT INTO users VALUES ('u1','a@b.c','Old User','x','2024-01-01')")
    conn.execute("INSERT INTO categories(id,user_id,name) VALUES ('c1','u1','Food')")
    conn.execute(
        "INSERT INTO transactions(id,user_id,amount,type,category_id,occurred_at,"
        "source,status,created_at) VALUES "
        "('t1','u1',250.0,'expense','c1','2024-05-01T10:00:00+00:00','manual',"
        "'confirmed','2024-05-01T10:00:00+00:00')")
    conn.commit()
    conn.close()


def test_upgrade_from_legacy_database_preserves_data(tmp_path):
    """The migration path most likely to destroy real user data."""
    path = str(tmp_path / "legacy.db")
    _legacy_db(path)

    conn, status = db.open_database(path)
    assert status["version"] == migrations.SCHEMA_VERSION

    # Every pre-existing row survives.
    assert conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0] == 1
    row = conn.execute("SELECT * FROM transactions WHERE id='t1'").fetchone()
    assert row["amount"] == 250.0 and row["category_id"] == "c1"
    # And the new columns exist with safe defaults.
    cols = {r[1] for r in conn.execute("PRAGMA table_info(transactions)")}
    assert {"dedup_key", "sms_body", "sms_sender", "category_prompted"} <= cols
    cat_cols = {r[1] for r in conn.execute("PRAGMA table_info(categories)")}
    assert "budget_amount" in cat_cols
    conn.close()


def test_migration_is_idempotent(tmp_path):
    path = str(tmp_path / "idem.db")
    _legacy_db(path)
    conn, _ = db.open_database(path)
    v1 = migrations.current_version(conn)
    conn.close()
    # Re-opening must not re-run anything or change the version.
    conn2, _ = db.open_database(path)
    assert migrations.current_version(conn2) == v1 == migrations.SCHEMA_VERSION
    assert conn2.execute("SELECT COUNT(*) FROM transactions").fetchone()[0] == 1
    conn2.close()


def test_fresh_database_lands_on_current_version(tmp_path):
    conn, status = db.open_database(str(tmp_path / "fresh.db"))
    assert status["version"] == migrations.SCHEMA_VERSION
    tables = {r[0] for r in conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table'")}
    assert {"transactions", "categories", "parse_misses"} <= tables
    conn.close()


def test_failed_migration_rolls_back_and_keeps_version(tmp_path, monkeypatch):
    """A migration that raises must leave the DB on the previous version."""
    path = str(tmp_path / "rollback.db")
    _legacy_db(path)
    conn = db.connect(path)
    migrations.upgrade(conn)
    before = migrations.current_version(conn)

    def boom(_conn):
        _conn.execute("CREATE TABLE canary(x)")
        raise RuntimeError("simulated failure")

    monkeypatch.setattr(migrations, "MIGRATIONS",
                        migrations.MIGRATIONS + [boom])
    monkeypatch.setattr(migrations, "SCHEMA_VERSION", before + 1)
    try:
        migrations.upgrade(conn)
        raise AssertionError("expected MigrationError")
    except migrations.MigrationError as exc:
        assert exc.version == before + 1
    # Version unchanged and the partial work was rolled back.
    assert migrations.current_version(conn) == before
    tables = {r[0] for r in conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table'")}
    assert "canary" not in tables
    conn.close()


def test_dedup_index_survives_preexisting_duplicates(tmp_path):
    """An older DB may already contain duplicate dedup_keys; the migration must
    de-duplicate rather than fail to apply the unique index."""
    path = str(tmp_path / "dupes.db")
    _legacy_db(path)
    conn = sqlite3.connect(path)
    conn.execute("ALTER TABLE transactions ADD COLUMN dedup_key TEXT")
    for i in (2, 3):
        conn.execute(
            "INSERT INTO transactions(id,user_id,amount,type,occurred_at,source,"
            "status,created_at,dedup_key) VALUES (?,?,?,?,?,?,?,?,?)",
            (f"t{i}", "u1", 100.0, "expense", "2024-05-0%dT10:00:00+00:00" % i,
             "sms", "confirmed", "2024-05-0%dT10:00:00+00:00" % i, "SAMEKEY"))
    conn.commit()
    conn.close()

    conn2, status = db.open_database(path)
    assert status["version"] == migrations.SCHEMA_VERSION
    idx = {r[0] for r in conn2.execute(
        "SELECT name FROM sqlite_master WHERE type='index'")}
    assert "ux_tx_dedup" in idx          # constraint actually applied
    live = conn2.execute("SELECT COUNT(*) FROM transactions WHERE dedup_key='SAMEKEY' "
                         "AND is_deleted=0").fetchone()[0]
    assert live == 1                      # duplicates collapsed, not lost
    conn2.close()


# ── Durability / recovery ─────────────────────────────────────────────────
def test_backup_is_created_and_verified(tmp_path):
    path = str(tmp_path / "b.db")
    conn, _ = db.open_database(path)
    backup = maintenance.create_backup(conn, path)
    conn.close()
    assert backup and os.path.exists(backup)
    assert maintenance.verify_backup(backup) is True


def test_truncated_backup_is_rejected(tmp_path):
    path = str(tmp_path / "t.db")
    conn, _ = db.open_database(path)
    backup = maintenance.create_backup(conn, path)
    conn.close()
    with open(backup, "wb") as f:
        f.write(b"not a database")
    assert maintenance.verify_backup(backup) is False


def test_corrupt_database_is_recovered_from_backup(tmp_path):
    """The scenario that would otherwise mean total loss of the user's ledger."""
    path = str(tmp_path / "c.db")
    app = create_app(db_path=path, single_user=True, secret_key="s")
    c = app.test_client()
    c.post("/transactions", data={"amount": "500.00", "type": "expense",
           "merchant": "BackupCafe", "category_id": "", "notes": "", "occurred_at": ""})

    conn = db.connect(path)
    assert maintenance.create_backup(conn, path)
    conn.close()

    # Corrupt the file the way a bad flash write would.
    with open(path, "r+b") as f:
        f.seek(0)
        f.write(b"\x00" * 8192)

    conn2, status = db.open_database(path)
    assert status["recovered"] is True and status["reset"] is False
    names = [r["merchant_name"] for r in conn2.execute(
        "SELECT merchant_name FROM transactions").fetchall()]
    assert "BackupCafe" in names          # the user's data came back
    conn2.close()


def test_unrecoverable_database_resets_instead_of_crash_looping(tmp_path):
    path = str(tmp_path / "u.db")
    conn, _ = db.open_database(path)
    conn.close()
    with open(path, "r+b") as f:
        f.seek(0)
        f.write(b"\x00" * 8192)
    conn2, status = db.open_database(path)     # no backups exist
    assert status["reset"] is True
    assert conn2.execute("SELECT COUNT(*) FROM transactions").fetchone()[0] == 0
    conn2.close()
    # The damaged file is preserved for manual salvage, never silently deleted.
    assert any(f.startswith("u.db.corrupt-") for f in os.listdir(tmp_path))


def test_orphan_detection_and_repair(tmp_path):
    path = str(tmp_path / "o.db")
    conn, _ = db.open_database(path)
    conn.execute("INSERT INTO users VALUES ('u1','a@b.c','U','x','2024-01-01')")
    conn.execute(
        "INSERT INTO transactions(id,user_id,amount,type,category_id,occurred_at,"
        "source,status,created_at) VALUES ('t1','u1',10,'expense','GHOST',"
        "'2024-01-01T00:00:00+00:00','manual','confirmed','2024-01-01T00:00:00+00:00')")
    conn.commit()
    report = maintenance.integrity_report(conn)
    assert report["orphans"].get("tx_category") == 1
    fixed = maintenance.repair_orphans(conn)
    assert fixed.get("tx_category") == 1
    # The transaction is KEPT (it is real money) — only the dead link is cleared.
    row = conn.execute("SELECT category_id FROM transactions WHERE id='t1'").fetchone()
    assert row["category_id"] is None
    assert conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0] == 1
    conn.close()


def test_purge_soft_deleted_respects_age(tmp_path):
    path = str(tmp_path / "p.db")
    conn, _ = db.open_database(path)
    conn.execute("INSERT INTO users VALUES ('u1','a@b.c','U','x','2024-01-01')")
    conn.execute(
        "INSERT INTO transactions(id,user_id,amount,type,occurred_at,source,status,"
        "created_at,is_deleted) VALUES ('old','u1',10,'expense','2020-01-01T00:00:00',"
        "'manual','confirmed','2020-01-01T00:00:00',1)")
    conn.execute(
        "INSERT INTO transactions(id,user_id,amount,type,occurred_at,source,status,"
        "created_at,is_deleted) VALUES ('new','u1',10,'expense','2099-01-01T00:00:00',"
        "'manual','confirmed','2099-01-01T00:00:00',1)")
    conn.commit()
    removed = maintenance.purge_soft_deleted(conn, older_than_days=90)
    assert removed == 1
    remaining = {r[0] for r in conn.execute("SELECT id FROM transactions")}
    assert remaining == {"new"}           # recent undo window preserved
    conn.close()
