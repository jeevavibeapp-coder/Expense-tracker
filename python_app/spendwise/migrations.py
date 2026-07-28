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
* **Recoverable** — ``db.open_database`` takes a verified file-level backup
  before upgrading and, if a migration fails, restores it and retries once.
  A second failure degrades to safe mode (see ``app._safe_mode_app``) rather
  than raising, because raising killed the server thread and the native
  Retry button re-ran the same migration forever.
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


def _m5_sender_trust(conn: sqlite3.Connection) -> None:
    """v5 — SMS sender registry and the phishing quarantine.

    ``sms_senders`` is the learned trust store: every sender ever seen is
    recorded with counts, so the user's own confirmations (not just a static
    allowlist) decide what is trusted.

    ``sms_quarantine`` is what makes "never silently discard" true. A message
    that looks like phishing is held here in full, with the indicators that
    triggered it, and can be approved into the ledger or rejected by the user.
    Without it, blocking a message would mean destroying a possibly-real
    transaction with no trace.
    """
    conn.execute("""
        CREATE TABLE IF NOT EXISTS sms_senders (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            sender TEXT NOT NULL,
            display TEXT,
            kind TEXT NOT NULL DEFAULT 'other',
            entity TEXT,
            bank TEXT,
            trust TEXT NOT NULL DEFAULT 'unknown',
            message_count INTEGER NOT NULL DEFAULT 0,
            captured_count INTEGER NOT NULL DEFAULT 0,
            confirmed_count INTEGER NOT NULL DEFAULT 0,
            quarantined_count INTEGER NOT NULL DEFAULT 0,
            last_risk INTEGER NOT NULL DEFAULT 0,
            first_seen_at TEXT NOT NULL,
            last_seen_at TEXT NOT NULL,
            UNIQUE(user_id, sender)
        )""")
    conn.execute("CREATE INDEX IF NOT EXISTS ix_sms_senders_user "
                 "ON sms_senders(user_id, trust, last_seen_at)")
    conn.execute("""
        CREATE TABLE IF NOT EXISTS sms_quarantine (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            sender TEXT,
            body TEXT NOT NULL,
            body_hash TEXT NOT NULL,
            risk INTEGER NOT NULL DEFAULT 0,
            indicators TEXT NOT NULL DEFAULT '[]',
            reason TEXT,
            amount REAL,
            type TEXT,
            raw_merchant TEXT,
            occurred_at TEXT,
            reference_number TEXT,
            status TEXT NOT NULL DEFAULT 'pending',
            seen_count INTEGER NOT NULL DEFAULT 1,
            created_at TEXT NOT NULL,
            resolved_at TEXT,
            UNIQUE(user_id, body_hash)
        )""")
    conn.execute("CREATE INDEX IF NOT EXISTS ix_sms_quarantine_user "
                 "ON sms_quarantine(user_id, status, created_at)")
    # Transactions captured from an unverified sender carry the assessment so
    # the review screen can explain WHY it is asking, and so a later model
    # change can re-score historical rows.
    _add_column_if_missing(conn, "transactions", "sender_trust", "TEXT")
    _add_column_if_missing(conn, "transactions", "sender_risk",
                           "INTEGER NOT NULL DEFAULT 0")


def _rebuild(conn: sqlite3.Connection, table: str, create_sql: str,
             columns: list[str], select_sql: str | None = None) -> None:
    """Replace ``table`` with a new definition, preserving its rows.

    SQLite cannot add a constraint to an existing table, so the only way to
    introduce real foreign keys is the documented twelve-step rebuild:
    create the replacement, copy, drop, rename. The caller is responsible for
    running this with foreign-key ENFORCEMENT disabled (``upgrade`` does) and
    for rebuilding parents before children.

    ``columns`` is the explicit column list — never ``SELECT *``. A future
    migration that adds a column would otherwise silently shift every value
    one position to the left.
    """
    cols = ", ".join(columns)
    tmp = f"_rebuild_{table}"
    conn.execute(f"DROP TABLE IF EXISTS {tmp}")
    conn.execute(create_sql.replace(f"__TABLE__", tmp))
    conn.execute(f"INSERT INTO {tmp}({cols}) "
                 f"{select_sql or f'SELECT {cols} FROM {table}'}")
    conn.execute(f"DROP TABLE {table}")
    conn.execute(f"ALTER TABLE {tmp} RENAME TO {table}")


def _m6_foreign_keys(conn: sqlite3.Connection) -> None:
    """v6 — declare real FOREIGN KEY constraints.

    Connections have always opened with ``PRAGMA foreign_keys=ON``, but no
    table declared a constraint, so the pragma enforced nothing. Referential
    integrity was maintained only by application code and repaired after the
    fact by ``maintenance.repair_orphans`` — i.e. the database could not stop
    a bug from writing a transaction pointing at a category that never
    existed, it could only notice afterwards.

    Delete behaviour is chosen per relationship, not uniformly:

    * ``ON DELETE CASCADE`` from ``users`` — a row belonging to a deleted user
      is unreachable by construction.
    * ``ON DELETE SET NULL`` for ``transactions.category_id`` and
      ``.merchant_id`` — deleting a category must never destroy the record of
      the user's money. This is the same conservative choice repair_orphans
      already made.
    * ``ON DELETE CASCADE`` for ``learning.merchant_id`` and
      ``fraud_alerts.transaction_id`` — both rows are meaningless without
      their parent.

    Orphans are repaired BEFORE the constraints go on, otherwise the copy
    would produce a table that immediately fails foreign_key_check.
    """
    # 1. Repair existing violations. Same policy as maintenance.repair_orphans:
    #    never delete a transaction, only detach its dangling references.
    conn.execute("UPDATE transactions SET category_id=NULL WHERE category_id IS NOT NULL "
                 "AND category_id NOT IN (SELECT id FROM categories)")
    conn.execute("UPDATE transactions SET merchant_id=NULL WHERE merchant_id IS NOT NULL "
                 "AND merchant_id NOT IN (SELECT id FROM merchants)")
    conn.execute("UPDATE merchants SET category_id=NULL WHERE category_id IS NOT NULL "
                 "AND category_id NOT IN (SELECT id FROM categories)")
    conn.execute("UPDATE learning SET category_id=NULL WHERE category_id IS NOT NULL "
                 "AND category_id NOT IN (SELECT id FROM categories)")
    conn.execute("DELETE FROM learning WHERE merchant_id NOT IN (SELECT id FROM merchants)")
    conn.execute("UPDATE fraud_alerts SET transaction_id=NULL WHERE transaction_id IS NOT NULL "
                 "AND transaction_id NOT IN (SELECT id FROM transactions)")
    # Rows whose owning user is gone cannot be attached to anything. These are
    # only reachable on a database that lost its users table content.
    for tbl in ("transactions", "categories", "merchants", "learning",
                "fraud_alerts", "settings", "parse_misses"):
        conn.execute(f"DELETE FROM {tbl} WHERE user_id NOT IN (SELECT id FROM users)")

    # 2. Rebuild parents first, then children.
    _rebuild(conn, "categories", """
        CREATE TABLE __TABLE__ (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            name TEXT NOT NULL,
            type TEXT NOT NULL DEFAULT 'expense',
            icon TEXT NOT NULL DEFAULT 'Tag',
            color TEXT NOT NULL DEFAULT '#6366f1',
            budget_amount REAL,
            is_archived INTEGER NOT NULL DEFAULT 0,
            UNIQUE(user_id, name)
        )""", ["id", "user_id", "name", "type", "icon", "color",
               "budget_amount", "is_archived"])

    _rebuild(conn, "merchants", """
        CREATE TABLE __TABLE__ (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            canonical_name TEXT NOT NULL,
            category_id TEXT REFERENCES categories(id) ON DELETE SET NULL,
            UNIQUE(user_id, canonical_name)
        )""", ["id", "user_id", "canonical_name", "category_id"])

    _rebuild(conn, "transactions", """
        CREATE TABLE __TABLE__ (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            amount REAL NOT NULL,
            type TEXT NOT NULL DEFAULT 'expense',
            category_id TEXT REFERENCES categories(id) ON DELETE SET NULL,
            raw_merchant TEXT,
            merchant_id TEXT REFERENCES merchants(id) ON DELETE SET NULL,
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
            sender_trust TEXT,
            sender_risk INTEGER NOT NULL DEFAULT 0,
            is_deleted INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL
        )""", ["id", "user_id", "amount", "type", "category_id", "raw_merchant",
               "merchant_id", "merchant_name", "notes", "reference_number",
               "occurred_at", "source", "confidence", "status",
               "category_prompted", "dedup_key", "sms_body", "sms_sender",
               "sender_trust", "sender_risk", "is_deleted", "created_at"])

    _rebuild(conn, "learning", """
        CREATE TABLE __TABLE__ (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            raw_name TEXT NOT NULL,
            merchant_id TEXT NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
            merchant_name TEXT NOT NULL,
            category_id TEXT REFERENCES categories(id) ON DELETE SET NULL,
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
        )""", ["id", "user_id", "raw_name", "merchant_id", "merchant_name",
               "category_id", "confidence", "confirmation_count",
               "correction_count", "sample_count", "avg_amount", "amount_min",
               "amount_max", "hour_histogram", "last_seen_at"])

    _rebuild(conn, "fraud_alerts", """
        CREATE TABLE __TABLE__ (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            transaction_id TEXT REFERENCES transactions(id) ON DELETE CASCADE,
            alert_type TEXT NOT NULL,
            severity TEXT NOT NULL DEFAULT 'low',
            message TEXT NOT NULL,
            details TEXT NOT NULL DEFAULT '{}',
            status TEXT NOT NULL DEFAULT 'open',
            created_at TEXT NOT NULL
        )""", ["id", "user_id", "transaction_id", "alert_type", "severity",
               "message", "details", "status", "created_at"])

    _rebuild(conn, "settings", """
        CREATE TABLE __TABLE__ (
            user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
            currency TEXT NOT NULL DEFAULT 'INR',
            theme TEXT NOT NULL DEFAULT 'system',
            auto_save_threshold INTEGER NOT NULL DEFAULT 80,
            confirm_threshold INTEGER NOT NULL DEFAULT 50,
            high_value_amount REAL
        )""", ["user_id", "currency", "theme", "auto_save_threshold",
               "confirm_threshold", "high_value_amount"])

    conn.execute("DELETE FROM parse_misses WHERE user_id NOT IN (SELECT id FROM users)")
    _rebuild(conn, "parse_misses", """
        CREATE TABLE __TABLE__ (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            sender TEXT,
            body TEXT NOT NULL,
            body_hash TEXT NOT NULL,
            reason TEXT,
            parser_version TEXT,
            seen_count INTEGER NOT NULL DEFAULT 1,
            first_seen_at TEXT NOT NULL,
            last_seen_at TEXT NOT NULL,
            UNIQUE(user_id, body_hash)
        )""", ["id", "user_id", "sender", "body", "body_hash", "reason",
               "parser_version", "seen_count", "first_seen_at", "last_seen_at"])

    for tbl in ("sms_senders", "sms_quarantine"):
        conn.execute(f"DELETE FROM {tbl} WHERE user_id NOT IN (SELECT id FROM users)")
    _rebuild(conn, "sms_senders", """
        CREATE TABLE __TABLE__ (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            sender TEXT NOT NULL,
            display TEXT,
            kind TEXT NOT NULL DEFAULT 'other',
            entity TEXT,
            bank TEXT,
            trust TEXT NOT NULL DEFAULT 'unknown',
            message_count INTEGER NOT NULL DEFAULT 0,
            captured_count INTEGER NOT NULL DEFAULT 0,
            confirmed_count INTEGER NOT NULL DEFAULT 0,
            quarantined_count INTEGER NOT NULL DEFAULT 0,
            last_risk INTEGER NOT NULL DEFAULT 0,
            first_seen_at TEXT NOT NULL,
            last_seen_at TEXT NOT NULL,
            UNIQUE(user_id, sender)
        )""", ["id", "user_id", "sender", "display", "kind", "entity", "bank",
               "trust", "message_count", "captured_count", "confirmed_count",
               "quarantined_count", "last_risk", "first_seen_at", "last_seen_at"])
    _rebuild(conn, "sms_quarantine", """
        CREATE TABLE __TABLE__ (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            sender TEXT,
            body TEXT NOT NULL,
            body_hash TEXT NOT NULL,
            risk INTEGER NOT NULL DEFAULT 0,
            indicators TEXT NOT NULL DEFAULT '[]',
            reason TEXT,
            amount REAL,
            type TEXT,
            raw_merchant TEXT,
            occurred_at TEXT,
            reference_number TEXT,
            status TEXT NOT NULL DEFAULT 'pending',
            seen_count INTEGER NOT NULL DEFAULT 1,
            created_at TEXT NOT NULL,
            resolved_at TEXT,
            UNIQUE(user_id, body_hash)
        )""", ["id", "user_id", "sender", "body", "body_hash", "risk",
               "indicators", "reason", "amount", "type", "raw_merchant",
               "occurred_at", "reference_number", "status", "seen_count",
               "created_at", "resolved_at"])

    # 3. DROP TABLE also drops that table's indexes, so every index defined by
    #    an earlier migration has to be recreated here.
    _m2_indexes(conn)
    _m4_integrity_indexes(conn)
    conn.execute("CREATE INDEX IF NOT EXISTS ix_tx_user_occurred "
                 "ON transactions(user_id, occurred_at)")
    conn.execute("CREATE INDEX IF NOT EXISTS ix_parse_misses_user "
                 "ON parse_misses(user_id, last_seen_at)")
    conn.execute("CREATE INDEX IF NOT EXISTS ix_sms_senders_user "
                 "ON sms_senders(user_id, trust, last_seen_at)")
    conn.execute("CREATE INDEX IF NOT EXISTS ix_sms_quarantine_user "
                 "ON sms_quarantine(user_id, status, created_at)")

    # 4. Prove it. A rebuild that silently produced violations would be worse
    #    than no constraints at all, because the app would now trust them.
    bad = conn.execute("PRAGMA foreign_key_check").fetchall()
    if bad:
        raise RuntimeError(f"foreign_key_check failed after rebuild: {bad[:5]}")


FTS_COLUMNS = ("merchant_name", "raw_merchant", "notes", "reference_number")

# Kept out of _m7 so search.rebuild_index() can reuse the exact same DDL — two
# copies of this would drift and the index would silently stop matching the
# triggers.
FTS_CREATE = (
    "CREATE VIRTUAL TABLE IF NOT EXISTS tx_fts USING fts5("
    "merchant_name, raw_merchant, notes, reference_number, "
    "content='transactions', content_rowid='rowid', tokenize='unicode61')")

# External-content FTS5 does not track its base table by itself; these keep
# the two in lockstep. Written against `rowid` because transactions.id is TEXT
# (so the table still has an implicit integer rowid).
FTS_TRIGGERS = (
    """CREATE TRIGGER IF NOT EXISTS tx_fts_ai AFTER INSERT ON transactions BEGIN
        INSERT INTO tx_fts(rowid, merchant_name, raw_merchant, notes, reference_number)
        VALUES (new.rowid, new.merchant_name, new.raw_merchant, new.notes,
                new.reference_number);
    END""",
    """CREATE TRIGGER IF NOT EXISTS tx_fts_ad AFTER DELETE ON transactions BEGIN
        INSERT INTO tx_fts(tx_fts, rowid, merchant_name, raw_merchant, notes,
                           reference_number)
        VALUES ('delete', old.rowid, old.merchant_name, old.raw_merchant, old.notes,
                old.reference_number);
    END""",
    # UPDATE must delete the OLD values before inserting the new ones, or the
    # index accumulates stale terms and a renamed merchant stays findable
    # under its old name forever.
    """CREATE TRIGGER IF NOT EXISTS tx_fts_au AFTER UPDATE ON transactions BEGIN
        INSERT INTO tx_fts(tx_fts, rowid, merchant_name, raw_merchant, notes,
                           reference_number)
        VALUES ('delete', old.rowid, old.merchant_name, old.raw_merchant, old.notes,
                old.reference_number);
        INSERT INTO tx_fts(rowid, merchant_name, raw_merchant, notes, reference_number)
        VALUES (new.rowid, new.merchant_name, new.raw_merchant, new.notes,
                new.reference_number);
    END""",
)


def fts5_available(conn: sqlite3.Connection) -> bool:
    """Whether this SQLite build has the FTS5 module compiled in.

    Not a given: FTS5 is an optional extension, and the app runs on whatever
    SQLite the Chaquopy runtime provides on the device. Everything downstream
    treats FTS5 as an optimisation that may be absent, never a requirement.
    """
    try:
        conn.execute("CREATE VIRTUAL TABLE IF NOT EXISTS _fts5_probe USING fts5(x)")
        conn.execute("DROP TABLE IF EXISTS _fts5_probe")
        return True
    except sqlite3.DatabaseError:
        return False


def _m7_fts_search(conn: sqlite3.Connection) -> None:
    """v7 — full-text index over the searchable transaction fields.

    Search was ``LIKE '%term%'`` across four columns, which no index can
    serve: every query read every row the user has ever recorded.

    If FTS5 is missing from the device's SQLite this migration is a no-op and
    the version still advances. Failing here would wedge the database on an
    upgrade for the sake of a search optimisation, which is a catastrophic
    trade; search simply keeps using the scan (see search.py).
    """
    if not fts5_available(conn):
        conn.execute("INSERT OR REPLACE INTO app_state(key, value) "
                     "VALUES ('fts5', 'unavailable')")
        return
    conn.execute(FTS_CREATE)
    for trigger in FTS_TRIGGERS:
        conn.execute(trigger)
    # Backfill from the existing ledger. 'rebuild' is FTS5's own command for
    # this and is far faster than re-inserting row by row.
    conn.execute("INSERT INTO tx_fts(tx_fts) VALUES('rebuild')")
    conn.execute("INSERT OR REPLACE INTO app_state(key, value) VALUES ('fts5', 'ready')")


ROLLUP_TRIGGERS = (
    # Any write that could change a day's totals marks that day dirty. UPDATE
    # marks BOTH days, because occurred_at itself can move a transaction from
    # one day to another and both totals then need recomputing. is_deleted is
    # a column, not a row removal, so a soft delete is an UPDATE and is
    # covered by the same trigger.
    #
    # `INSERT ... WHERE NOT EXISTS`, deliberately NOT `INSERT OR IGNORE`: an
    # ON CONFLICT clause inside a trigger body is overridden by the conflict
    # policy of the statement that FIRED the trigger. Deleting a merchant runs
    # an ON DELETE SET NULL on transactions, whose implicit UPDATE fires this
    # trigger twice for the same day, and OR IGNORE did not apply — the delete
    # aborted with a UNIQUE violation. This form has no conflict to resolve.
    """CREATE TRIGGER IF NOT EXISTS rollup_dirty_ai AFTER INSERT ON transactions BEGIN
        INSERT INTO rollup_dirty(user_id, day)
        SELECT new.user_id, substr(new.occurred_at, 1, 10)
        WHERE NOT EXISTS (SELECT 1 FROM rollup_dirty
                          WHERE user_id = new.user_id
                            AND day = substr(new.occurred_at, 1, 10));
    END""",
    """CREATE TRIGGER IF NOT EXISTS rollup_dirty_ad AFTER DELETE ON transactions BEGIN
        INSERT INTO rollup_dirty(user_id, day)
        SELECT old.user_id, substr(old.occurred_at, 1, 10)
        WHERE NOT EXISTS (SELECT 1 FROM rollup_dirty
                          WHERE user_id = old.user_id
                            AND day = substr(old.occurred_at, 1, 10));
    END""",
    """CREATE TRIGGER IF NOT EXISTS rollup_dirty_au AFTER UPDATE ON transactions BEGIN
        INSERT INTO rollup_dirty(user_id, day)
        SELECT old.user_id, substr(old.occurred_at, 1, 10)
        WHERE NOT EXISTS (SELECT 1 FROM rollup_dirty
                          WHERE user_id = old.user_id
                            AND day = substr(old.occurred_at, 1, 10));
        INSERT INTO rollup_dirty(user_id, day)
        SELECT new.user_id, substr(new.occurred_at, 1, 10)
        WHERE NOT EXISTS (SELECT 1 FROM rollup_dirty
                          WHERE user_id = new.user_id
                            AND day = substr(new.occurred_at, 1, 10));
    END""",
)


def _m8_daily_rollups(conn: sqlite3.Connection) -> None:
    """v8 — pre-aggregated daily totals with incremental maintenance.

    The dashboard's "today / this week / this month" tiles and the spending
    trend all aggregate over raw transactions, so their cost grows with total
    history even though they only ever display a handful of numbers.

    Grain is ``(user_id, day, type)``. Deliberately NOT per-category: that
    would multiply the row count by the number of categories and approach the
    size of the table it summarises, while the per-category donut is already
    served in ~1ms by ix_tx_analytics.

    Maintenance is incremental, not scheduled. Triggers record which days
    changed; ``analytics.refresh_rollups`` recomputes exactly those. A normal
    session dirties one day, so the refresh is effectively free — and there is
    no background job to fail silently and serve stale numbers.
    """
    conn.execute("""
        CREATE TABLE IF NOT EXISTS daily_rollups (
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            day TEXT NOT NULL,
            type TEXT NOT NULL,
            total REAL NOT NULL DEFAULT 0,
            tx_count INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (user_id, day, type)
        )""")
    conn.execute("""
        CREATE TABLE IF NOT EXISTS rollup_dirty (
            user_id TEXT NOT NULL,
            day TEXT NOT NULL,
            PRIMARY KEY (user_id, day)
        )""")
    conn.execute("CREATE INDEX IF NOT EXISTS ix_daily_rollups_user "
                 "ON daily_rollups(user_id, day)")
    for trigger in ROLLUP_TRIGGERS:
        conn.execute(trigger)
    # Seed from the existing ledger in one pass.
    conn.execute("DELETE FROM daily_rollups")
    conn.execute(
        "INSERT INTO daily_rollups(user_id, day, type, total, tx_count) "
        "SELECT user_id, substr(occurred_at, 1, 10), type, "
        "       COALESCE(SUM(amount), 0), COUNT(*) "
        "FROM transactions WHERE is_deleted = 0 "
        "GROUP BY user_id, substr(occurred_at, 1, 10), type")
    conn.execute("DELETE FROM rollup_dirty")


def _m9_sanitise_amounts(conn: sqlite3.Connection) -> None:
    """v9 — quarantine non-finite / absurd amounts already in the ledger.

    Builds before this one accepted any string ``float()`` would parse, so a
    400-digit SMS amount became ``inf`` and was stored. A single such row made
    ``detect_transfers`` raise OverflowError on ``int(amount * 100)``, which
    permanently 500'd the dashboard — the app's launch screen — for anyone who
    received one message. Devices in the field can already be in that state,
    so the write-side fix is not enough on its own.

    The rows are soft-deleted rather than repaired or dropped: the amount is
    unrecoverable (the real value is not derivable from ``inf``), but the SMS
    body is still on the row, so the user can read it and re-enter the
    transaction. Silently deleting a record of the user's money would be the
    wrong trade even when the record is broken.

    SQLite has no isnan()/isinf(), so the predicate is written with the two
    properties that identify them in SQL: NaN is the only value not equal to
    itself, and both infinities fall outside the finite bound.
    """
    conn.execute(
        "UPDATE transactions SET is_deleted = 1 "
        "WHERE is_deleted = 0 AND ("
        "     amount IS NULL"
        "  OR amount != amount"          # NaN
        "  OR amount > 1e12"             # +inf and absurd magnitudes
        "  OR amount < -1e12"            # -inf
        "  OR amount <= 0)")
    # The rollups derived from those rows are now wrong; force a rebuild.
    conn.execute("DELETE FROM daily_rollups")
    conn.execute(
        "INSERT INTO daily_rollups(user_id, day, type, total, tx_count) "
        "SELECT user_id, substr(occurred_at, 1, 10), type, "
        "       COALESCE(SUM(amount), 0), COUNT(*) "
        "FROM transactions WHERE is_deleted = 0 "
        "GROUP BY user_id, substr(occurred_at, 1, 10), type")
    conn.execute("DELETE FROM rollup_dirty")


# Ordered list. Index + 1 == the schema version the entry produces.
MIGRATIONS: list[Callable[[sqlite3.Connection], None]] = [
    _m1_baseline,
    _m2_indexes,
    _m3_parse_misses,
    _m4_integrity_indexes,
    _m5_sender_trust,
    _m6_foreign_keys,
    _m7_fts_search,
    _m8_daily_rollups,
    _m9_sanitise_amounts,
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
    # As with v1: the tables alone are not proof. v5 also adds two columns to
    # transactions, so require those before claiming it completed.
    if ({"sms_senders", "sms_quarantine"} <= tables
            and {"sender_trust", "sender_risk"} <= tx_cols):
        version = 5
    # v6 is only complete when the constraints actually exist — the table
    # names and columns are identical before and after, so the foreign-key
    # list is the only honest evidence.
    if version == 5 and conn.execute(
            "PRAGMA foreign_key_list(transactions)").fetchall():
        version = 6
    if version == 6 and ("tx_fts" in tables or _fts_marked_unavailable(conn)):
        version = 7
    if version == 7 and {"daily_rollups", "rollup_dirty"} <= tables:
        version = 8
    # v9 leaves no schema trace (it only rewrites rows), so it cannot be
    # detected — deliberately return 8 and let it re-run. It is idempotent.
    return version


def _fts_marked_unavailable(conn: sqlite3.Connection) -> bool:
    """v7 legitimately creates no table when FTS5 is missing, so the marker
    row is the only evidence it ran."""
    try:
        row = conn.execute(
            "SELECT value FROM app_state WHERE key='fts5'").fetchone()
    except sqlite3.DatabaseError:
        return False
    return bool(row and row[0] == "unavailable")


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

    if version >= SCHEMA_VERSION:
        return version

    # Foreign-key ENFORCEMENT must be off while a migration rebuilds a table:
    # the documented procedure drops the old table (temporarily dangling every
    # reference to it) before renaming the replacement into place. This pragma
    # is a no-op inside a transaction, so it has to be set out here, around
    # the whole loop — not inside a migration body.
    #
    # legacy_alter_table=ON stops ALTER TABLE ... RENAME from rewriting other
    # tables' REFERENCES clauses to point at the temporary name.
    fk_was_on = bool(conn.execute("PRAGMA foreign_keys").fetchone()[0])
    conn.execute("PRAGMA foreign_keys=OFF")
    conn.execute("PRAGMA legacy_alter_table=ON")
    try:
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
    finally:
        conn.execute("PRAGMA legacy_alter_table=OFF")
        if fk_was_on:
            conn.execute("PRAGMA foreign_keys=ON")
    return version


class MigrationError(RuntimeError):
    def __init__(self, version: int, cause: Exception):
        super().__init__(f"migration to v{version} failed: {cause}")
        self.version = version
        self.cause = cause
