"""Migration, durability and recovery tests.

These cover the failure modes that destroy user data rather than merely
annoying the user, and which had ZERO coverage before: upgrading a database
created by an older build, and surviving corruption.
"""
from __future__ import annotations

import os
import sqlite3

import pytest

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


def test_orphans_are_now_rejected_at_write_time(tmp_path):
    """Since v6 the database refuses to create the orphan at all.

    This is the point of declaring real constraints: previously the app could
    write a transaction pointing at a category that never existed and only
    find out later during a maintenance sweep.
    """
    path = str(tmp_path / "o.db")
    conn, _ = db.open_database(path)
    conn.execute("INSERT INTO users VALUES ('u1','a@b.c','U','x','2024-01-01')")
    conn.commit()
    with pytest.raises(sqlite3.IntegrityError):
        conn.execute(
            "INSERT INTO transactions(id,user_id,amount,type,category_id,occurred_at,"
            "source,status,created_at) VALUES ('t1','u1',10,'expense','GHOST',"
            "'2024-01-01T00:00:00+00:00','manual','confirmed','2024-01-01T00:00:00+00:00')")
    conn.rollback()
    conn.close()


def test_orphan_detection_and_repair_still_works_on_legacy_data(tmp_path):
    """Constraints stop NEW orphans; they do not clean up old ones.

    A database written by a pre-v6 build can already contain dangling links,
    so the repair path must keep working. Enforcement is disabled here to
    reproduce exactly what such a file looks like.
    """
    path = str(tmp_path / "o2.db")
    conn, _ = db.open_database(path)
    conn.execute("INSERT INTO users VALUES ('u1','a@b.c','U','x','2024-01-01')")
    conn.commit()
    conn.execute("PRAGMA foreign_keys=OFF")
    conn.execute(
        "INSERT INTO transactions(id,user_id,amount,type,category_id,occurred_at,"
        "source,status,created_at) VALUES ('t1','u1',10,'expense','GHOST',"
        "'2024-01-01T00:00:00+00:00','manual','confirmed','2024-01-01T00:00:00+00:00')")
    conn.commit()
    conn.execute("PRAGMA foreign_keys=ON")

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


# ── v6: real foreign keys ─────────────────────────────────────────────────
def _fk_map(conn, table):
    """{column: (referenced_table, on_delete)} for a table's declared FKs."""
    return {r[3]: (r[2], r[6])
            for r in conn.execute(f"PRAGMA foreign_key_list({table})")}


def test_every_relationship_is_declared_with_the_intended_delete_action(tmp_path):
    """Delete behaviour is a data-loss decision, so it is pinned explicitly.

    In particular transactions.category_id must be SET NULL, never CASCADE:
    deleting a category must not destroy the record of the user's money.
    """
    conn, _ = db.open_database(str(tmp_path / "fk.db"))
    tx = _fk_map(conn, "transactions")
    assert tx["user_id"] == ("users", "CASCADE")
    assert tx["category_id"] == ("categories", "SET NULL")
    assert tx["merchant_id"] == ("merchants", "SET NULL")

    assert _fk_map(conn, "categories")["user_id"] == ("users", "CASCADE")
    assert _fk_map(conn, "merchants")["category_id"] == ("categories", "SET NULL")

    learning = _fk_map(conn, "learning")
    assert learning["merchant_id"] == ("merchants", "CASCADE")
    assert learning["category_id"] == ("categories", "SET NULL")

    assert _fk_map(conn, "fraud_alerts")["transaction_id"] == ("transactions", "CASCADE")
    assert _fk_map(conn, "settings")["user_id"] == ("users", "CASCADE")
    for tbl in ("parse_misses", "sms_senders", "sms_quarantine"):
        assert _fk_map(conn, tbl)["user_id"] == ("users", "CASCADE")
    conn.close()


def test_deleting_a_category_keeps_the_transaction(tmp_path):
    """SET NULL, not CASCADE. This is the assertion that protects real money."""
    conn, _ = db.open_database(str(tmp_path / "fk2.db"))
    conn.execute("INSERT INTO users VALUES ('u1','a@b.c','U','x','2024-01-01')")
    conn.execute("INSERT INTO categories(id,user_id,name) VALUES ('c1','u1','Food')")
    conn.execute(
        "INSERT INTO transactions(id,user_id,amount,type,category_id,occurred_at,"
        "source,status,created_at) VALUES ('t1','u1',250,'expense','c1',"
        "'2024-05-01T10:00:00+00:00','manual','confirmed','2024-05-01T10:00:00+00:00')")
    conn.commit()
    conn.execute("DELETE FROM categories WHERE id='c1'")
    conn.commit()
    row = conn.execute("SELECT amount, category_id FROM transactions WHERE id='t1'").fetchone()
    assert row is not None, "deleting a category destroyed a transaction"
    assert row["amount"] == 250.0
    assert row["category_id"] is None
    conn.close()


def test_deleting_a_merchant_cascades_only_to_its_learning_rows(tmp_path):
    conn, _ = db.open_database(str(tmp_path / "fk3.db"))
    conn.execute("INSERT INTO users VALUES ('u1','a@b.c','U','x','2024-01-01')")
    conn.execute("INSERT INTO merchants(id,user_id,canonical_name) VALUES ('m1','u1','Swiggy')")
    conn.execute(
        "INSERT INTO learning(id,user_id,raw_name,merchant_id,merchant_name) "
        "VALUES ('l1','u1','SWIGGY','m1','Swiggy')")
    conn.execute(
        "INSERT INTO transactions(id,user_id,amount,type,merchant_id,occurred_at,"
        "source,status,created_at) VALUES ('t1','u1',250,'expense','m1',"
        "'2024-05-01T10:00:00+00:00','sms','confirmed','2024-05-01T10:00:00+00:00')")
    conn.commit()
    conn.execute("DELETE FROM merchants WHERE id='m1'")
    conn.commit()
    assert conn.execute("SELECT COUNT(*) FROM learning").fetchone()[0] == 0
    tx = conn.execute("SELECT merchant_id FROM transactions WHERE id='t1'").fetchone()
    assert tx is not None and tx["merchant_id"] is None
    conn.close()


def test_the_rebuild_preserves_every_row_and_column_value(tmp_path):
    """A twelve-step rebuild copies rows by hand — the failure mode is a
    silently shifted or dropped column, which no constraint would catch."""
    path = str(tmp_path / "fk4.db")
    _legacy_db(path)
    conn = sqlite3.connect(path)
    conn.execute("UPDATE transactions SET notes='keep me', reference_number='REF999', "
                 "confidence=77, raw_merchant='SWIGGY BANGALORE' WHERE id='t1'")
    conn.commit()
    conn.close()

    conn2, status = db.open_database(path)
    assert status["version"] == migrations.SCHEMA_VERSION
    row = conn2.execute("SELECT * FROM transactions WHERE id='t1'").fetchone()
    assert row["amount"] == 250.0
    assert row["type"] == "expense"
    assert row["category_id"] == "c1"
    assert row["notes"] == "keep me"
    assert row["reference_number"] == "REF999"
    assert row["confidence"] == 77
    assert row["raw_merchant"] == "SWIGGY BANGALORE"
    assert row["occurred_at"] == "2024-05-01T10:00:00+00:00"
    assert row["source"] == "manual"
    assert row["is_deleted"] == 0
    conn2.close()


def test_migration_repairs_preexisting_orphans_before_adding_constraints(tmp_path):
    """A pre-v6 database can already hold dangling links. If the rebuild did
    not repair them first, the upgrade would fail (or worse, install
    constraints the data violates)."""
    path = str(tmp_path / "fk5.db")
    _legacy_db(path)
    conn = sqlite3.connect(path)
    conn.execute(
        "INSERT INTO transactions(id,user_id,amount,type,category_id,occurred_at,"
        "source,status,created_at) VALUES ('t2','u1',99,'expense','GHOSTCAT',"
        "'2024-06-01T10:00:00+00:00','sms','confirmed','2024-06-01T10:00:00+00:00')")
    conn.execute(
        "INSERT INTO learning(id,user_id,raw_name,merchant_id,merchant_name) "
        "VALUES ('l1','u1','X','GHOSTMERCH','X')")
    conn.commit()
    conn.close()

    conn2, status = db.open_database(path)
    assert status["version"] == migrations.SCHEMA_VERSION
    # The transaction survives with its dead link cleared; the meaningless
    # learning row is dropped.
    row = conn2.execute("SELECT amount, category_id FROM transactions WHERE id='t2'").fetchone()
    assert row is not None and row["amount"] == 99.0 and row["category_id"] is None
    assert conn2.execute("SELECT COUNT(*) FROM learning").fetchone()[0] == 0
    assert conn2.execute("PRAGMA foreign_key_check").fetchall() == []
    conn2.close()


def test_indexes_survive_the_table_rebuild(tmp_path):
    """DROP TABLE drops its indexes. Losing ux_tx_dedup would silently
    re-enable duplicate SMS capture; losing ix_tx_analytics would turn the
    dashboard back into a full scan."""
    conn, _ = db.open_database(str(tmp_path / "fk6.db"))
    idx = {r[0] for r in conn.execute(
        "SELECT name FROM sqlite_master WHERE type='index'")}
    assert {"ux_tx_dedup", "ix_tx_analytics", "ix_tx_user_status",
            "ix_tx_user_merchant", "ix_tx_cat_prompts", "ix_learning_user_raw",
            "ix_tx_user_category", "ix_tx_deleted", "ix_merchants_user",
            "ix_tx_user_occurred", "ix_parse_misses_user",
            "ix_sms_senders_user", "ix_sms_quarantine_user"} <= idx
    conn.close()


def test_dedup_uniqueness_still_enforced_after_rebuild(tmp_path):
    conn, _ = db.open_database(str(tmp_path / "fk7.db"))
    conn.execute("INSERT INTO users VALUES ('u1','a@b.c','U','x','2024-01-01')")
    for i in (1, 2):
        stmt = ("INSERT INTO transactions(id,user_id,amount,type,occurred_at,source,"
                "status,created_at,dedup_key) VALUES (?,?,?,?,?,?,?,?,'SAME')")
        args = (f"t{i}", "u1", 10.0, "expense", "2025-01-01T00:00:00",
                "sms", "confirmed", "2025-01-01T00:00:00")
        if i == 1:
            conn.execute(stmt, args)
        else:
            with pytest.raises(sqlite3.IntegrityError):
                conn.execute(stmt, args)
    conn.rollback()
    conn.close()


def test_upgrade_is_idempotent_across_the_fk_rebuild(tmp_path):
    """Re-opening must not rebuild again — a repeated rebuild is where data
    loss would hide."""
    path = str(tmp_path / "fk8.db")
    _legacy_db(path)
    conn, _ = db.open_database(path)
    conn.close()
    conn2, status = db.open_database(path)
    assert status["version"] == migrations.SCHEMA_VERSION
    assert conn2.execute("SELECT COUNT(*) FROM transactions").fetchone()[0] == 1
    assert conn2.execute("PRAGMA foreign_key_check").fetchall() == []
    conn2.close()


def test_legacy_detection_requires_the_constraints_not_just_the_tables(tmp_path):
    """A v5 database has identical table and column names to a v6 one. Only
    the foreign-key list distinguishes them, so detection must read that."""
    path = str(tmp_path / "fk9.db")
    conn, _ = db.open_database(path)
    conn.close()
    raw = sqlite3.connect(path)
    raw.execute("PRAGMA user_version = 0")     # simulate an unversioned file
    raw.commit()
    detected = migrations._detect_legacy_version(raw)
    raw.close()
    # v9 rewrites rows and leaves no schema trace, so it is deliberately not
    # detectable — detection stops at 8 and v9 re-runs (it is idempotent).
    assert detected == 8


def test_foreign_keys_are_enforced_on_every_app_connection(tmp_path):
    """The constraints are worthless if a connection forgets the pragma."""
    path = str(tmp_path / "fk10.db")
    conn, _ = db.open_database(path)
    conn.close()
    c = db.connect(path)
    assert c.execute("PRAGMA foreign_keys").fetchone()[0] == 1
    c.close()
