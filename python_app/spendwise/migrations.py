"""Versioned, transactional schema migrations.

Replaces column-sniffing (``PRAGMA table_info``) with an ordered, numbered
migration list tracked by ``PRAGMA user_version``. Column sniffing could only
ever express ``ADD COLUMN``; this can rebuild tables, backfill data, add
constraints and drop columns — which unblocks every future schema change.

Guarantees
----------
* **Atomic** — each migration runs inside its own transaction, so a failure
  leaves the database exactly as it was before that step.
* **Idempotent** — already-applied migrations are skipped by version number,
  never re-run.
* **Recoverable** — the caller takes a file-level backup before upgrading (see
  ``maintenance.safe_upgrade``), so even a SQLite-level crash is recoverable.
* **Forward-only** — downgrades are not supported (an older build must not open
  a newer database); ``rollback_to`` exists only to undo a *failed* upgrade
  from the pre-upgrade backup.

Adding a migration: append to ``MIGRATIONS``. Never edit or renumber an
existing entry — devices in the field have already applied it.
"""
from __future__ import annotations

import sqlite3
from typing import Callable

# ── Migration bodies ──────────────────────────────────────────────────────
# Each takes an open connection and performs its change. Do NOT commit inside
# a migration: the runner owns the transaction boundary.


def _m1_baseline(conn: sqlite3.Connection) -> None:
    """v1 — the schema as first shipped, plus every additive column added
    before versioning existed. Safe on both a fresh database and one created
    by an older build (all statements are IF NOT EXISTS / guarded)."""
    conn.executescript(BASELINE_SCHEMA)
    _add_column_if_missing(conn, "transactions", "category_prompted",
                           "INTEGER NOT NULL DEFAULT 0")
    _add_column_if_missing(conn, "transactions", "dedup_key", "TEXT")
    _add_column_if_missing(conn, "transactions", "sms_body", "TEXT")
    _add_column_if_missing(conn, "transactions", "sms_sender", "TEXT")
    _add_column_if_missing(conn, "categories", "budget_amount", "REAL")


def _m2_indexes(conn: sqlite3.Connection) -> None:
    """v2 — hot-path indexes and the dedup uniqueness guard."""
    # A database that already contains duplicates cannot take the unique index;
    # de-duplicate first so the constraint can be enforced from here on.
    conn.execute(
        "UPDATE transactions SET is_deleted=1 WHERE id IN ("
        "  SELECT id FROM ("
        "    SELECT id, ROW_NUMBER() OVER ("
        "      PARTITION BY user_id, dedup_key ORDER BY created_at"
        "    ) rn FROM transactions"
        "    WHERE dedup_key IS NOT NULL AND is_deleted=0"
        "  ) WHERE rn > 1)")
    conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_tx_dedup ON "
                 "transactions(user_id, dedup_key) "
                 "WHERE dedup_key IS NOT NULL AND is_deleted=0")
    for stmt in (
        "CREATE INDEX IF NOT EXISTS ix_tx_analytics ON transactions"
        "(user_id, type, occurred_at, amount, category_id, merchant_name) "
        "WHERE is_deleted=0",
        "CREATE INDEX IF NOT EXISTS ix_tx_user_status ON transactions"
        "(user_id, status) WHERE is_deleted=0",
        "CREATE INDEX IF NOT EXISTS ix_tx_user_merchant ON transactions"
        "(user_id, merchant_name) WHERE is_deleted=0",
        "CREATE INDEX IF NOT EXISTS ix_fraud_user_status ON fraud_alerts"
        "(user_id, status)",
        "CREATE INDEX IF NOT EXISTS ix_tx_cat_prompts ON transactions"
        "(user_id, occurred_at) "
        "WHERE source='sms' AND category_id IS NULL AND is_deleted=0",
        "CREATE INDEX IF NOT EXISTS ix_learning_user_raw ON learning(user_id, raw_name)",
    ):
        conn.execute(stmt)


def _m3_parse_misses(conn: sqlite3.Connection) -> None:
    """v3 — local-only record of bank messages the parser could not read.

    Without this a format change is silent: transactions simply stop appearing
    and the user has no signal. Stored on-device only; nothing is transmitted.
    """
    conn.execute("""
        CREATE TABLE IF NOT EXISTS parse_misses (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            sender TEXT,
            body TEXT NOT NULL,
            body_hash TEXT NOT NULL,
            reason TEXT,
            parser_version TEXT,
            seen_count INTEGER NOT NULL DEFAULT 1,
            first_seen_at TEXT NOT NULL,
            last_seen_at TEXT NOT NULL,
            UNIQUE(user_id, body_hash)
        )""")
    conn.execute("CREATE INDEX IF NOT EXISTS ix_parse_misses_user "
                 "ON parse_misses(user_id, last_seen_at)")


def _m4_integrity_indexes(conn: sqlite3.Connection) -> None:
    """v4 — indexes behind referential-integrity checks and cleanup sweeps."""
    conn.execute("CREATE INDEX IF NOT EXISTS ix_tx_user_category "
                 "ON transactions(user_id, category_id)")
    conn.execute("CREATE INDEX IF NOT EXISTS ix_tx_deleted "
                 "ON transactions(user_id, is_deleted, created_at)")
    conn.execute("CREATE INDEX IF NOT EXISTS ix_merchants_user "
                 "ON merchants(user_id, canonical_name)")


# Ordered list. Index + 1 == the schema version the entry produces.
MIGRATIONS: list[Callable[[sqlite3.Connection], None]] = [
    _m1_baseline,
    _m2_indexes,
    _m3_parse_misses,
    _m4_integrity_indexes,
]

SCHEMA_VERSION = len(MIGRATIONS)


BASELINE_SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    email TEXT UNIQUE NOT NULL,
    full_name TEXT NOT NULL,
    pw_hash TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS categories (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    name TEXT NOT NULL,
    type TEXT NOT NULL DEFAULT 'expense',
    icon TEXT NOT NULL DEFAULT 'Tag',
    color TEXT NOT NULL DEFAULT '#6366f1',
    budget_amount REAL,
    is_archived INTEGER NOT NULL DEFAULT 0,
    UNIQUE(user_id, name)
);
CREATE TABLE IF NOT EXISTS merchants (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    canonical_name TEXT NOT NULL,
    category_id TEXT,
    UNIQUE(user_id, canonical_name)
);
CREATE TABLE IF NOT EXISTS learning (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    raw_name TEXT NOT NULL,
    merchant_id TEXT NOT NULL,
    merchant_name TEXT NOT NULL,
    category_id TEXT,
    confidence INTEGER NOT NULL DEFAULT 0,
    confirmation_count INTEGER NOT NULL DEFAULT 0,
    correction_count INTEGER NOT NULL DEFAULT 0,
    sample_count INTEGER NOT NULL DEFAULT 0,
    avg_amount REAL NOT NULL DEFAULT 0,
    amount_min REAL NOT NULL DEFAULT 0,
    amount_max REAL NOT NULL DEFAULT 0,
    hour_histogram TEXT NOT NULL DEFAULT '[]',
    last_seen_at TEXT,
    UNIQUE(user_id, raw_name, merchant_id)
);
CREATE TABLE IF NOT EXISTS transactions (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    amount REAL NOT NULL,
    type TEXT NOT NULL DEFAULT 'expense',
    category_id TEXT,
    raw_merchant TEXT,
    merchant_id TEXT,
    merchant_name TEXT,
    notes TEXT,
    reference_number TEXT,
    occurred_at TEXT NOT NULL,
    source TEXT NOT NULL DEFAULT 'manual',
    confidence INTEGER,
    status TEXT NOT NULL DEFAULT 'confirmed',
    category_prompted INTEGER NOT NULL DEFAULT 0,
    dedup_key TEXT,
    sms_body TEXT,
    sms_sender TEXT,
    is_deleted INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_tx_user_occurred ON transactions(user_id, occurred_at);
CREATE TABLE IF NOT EXISTS fraud_alerts (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    transaction_id TEXT,
    alert_type TEXT NOT NULL,
    severity TEXT NOT NULL DEFAULT 'low',
    message TEXT NOT NULL,
    details TEXT NOT NULL DEFAULT '{}',
    status TEXT NOT NULL DEFAULT 'open',
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS settings (
    user_id TEXT PRIMARY KEY,
    currency TEXT NOT NULL DEFAULT 'INR',
    theme TEXT NOT NULL DEFAULT 'system',
    auto_save_threshold INTEGER NOT NULL DEFAULT 80,
    confirm_threshold INTEGER NOT NULL DEFAULT 50,
    high_value_amount REAL
);
CREATE TABLE IF NOT EXISTS app_state (
    key TEXT PRIMARY KEY,
    value TEXT
);
"""


def _add_column_if_missing(conn: sqlite3.Connection, table: str, column: str,
                           decl: str) -> None:
    cols = {r[1] for r in conn.execute(f"PRAGMA table_info({table})")}
    if column not in cols:
        conn.execute(f"ALTER TABLE {table} ADD COLUMN {column} {decl}")


def current_version(conn: sqlite3.Connection) -> int:
    return int(conn.execute("PRAGMA user_version").fetchone()[0])


def _detect_legacy_version(conn: sqlite3.Connection) -> int:
    """A database created before versioning reports user_version 0 even though
    its schema may already be current. Infer a starting point so those
    migrations are not pointlessly re-run (they are idempotent either way)."""
    tables = {r[0] for r in conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table'")}
    if not tables:
        return 0                      # brand-new database
    # Existing tables are NOT proof that v1 completed: a v1.0-era database has
    # the tables but not the columns v1 adds. Only claim v1 once its actual
    # outputs are present, otherwise re-run it (it is idempotent).
    tx_cols = {r[1] for r in conn.execute("PRAGMA table_info(transactions)")}
    cat_cols = {r[1] for r in conn.execute("PRAGMA table_info(categories)")}
    v1_done = ({"category_prompted", "dedup_key", "sms_body", "sms_sender"}
               <= tx_cols and "budget_amount" in cat_cols)
    if not v1_done:
        return 0
    version = 1
    idx = {r[0] for r in conn.execute(
        "SELECT name FROM sqlite_master WHERE type='index'")}
    if "ux_tx_dedup" in idx and "ix_tx_analytics" in idx:
        version = 2
    if "parse_misses" in tables:
        version = 3
    if "ix_tx_user_category" in idx:
        version = 4
    return version


def upgrade(conn: sqlite3.Connection) -> int:
    """Apply every pending migration. Returns the resulting version.

    Each step is atomic: on failure the step is rolled back and the exception
    is re-raised with the version that failed, leaving user_version pointing at
    the last successfully applied migration.
    """
    version = current_version(conn)
    if version == 0:
        version = _detect_legacy_version(conn)
        if version:
            conn.execute(f"PRAGMA user_version = {version}")
            conn.commit()

    while version < SCHEMA_VERSION:
        step = MIGRATIONS[version]
        target = version + 1
        try:
            conn.execute("BEGIN IMMEDIATE")
            step(conn)
            # PRAGMA user_version cannot be parameterised.
            conn.execute(f"PRAGMA user_version = {target}")
            conn.commit()
        except Exception as exc:                       # noqa: BLE001
            conn.rollback()
            raise MigrationError(target, exc) from exc
        version = target
    return version


class MigrationError(RuntimeError):
    def __init__(self, version: int, cause: Exception):
        super().__init__(f"migration to v{version} failed: {cause}")
        self.version = version
        self.cause = cause
