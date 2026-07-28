"""SQLite data layer (Python standard library only).

A single file-backed database; on Android it lives in the app's private files
directory. UUID text primary keys keep rows portable.

Schema changes live in ``migrations.py`` (versioned via PRAGMA user_version).
Durability — integrity checks, backups, recovery — lives in ``maintenance.py``.
"""
from __future__ import annotations

import os
import sqlite3
import uuid
from typing import Any, Optional

from . import maintenance, migrations

# Kept for callers/tests that referenced the original constant.
SCHEMA = migrations.BASELINE_SCHEMA
SCHEMA_VERSION = migrations.SCHEMA_VERSION


def new_id() -> str:
    return uuid.uuid4().hex


class Connection(sqlite3.Connection):
    """sqlite3.Connection that accepts attributes.

    Two modules cache per-request work on the connection object (the merchant
    engine's learning pool, the categoriser's trained model) because a
    connection lives exactly one request — the ideal scope for a cache that
    must not go stale across a write.

    Plain sqlite3.Connection has no __dict__, so `conn.x = v` raises
    AttributeError. Both call sites caught that exception and carried on,
    which meant the caches silently never cached: measured, the categoriser
    retrained its model on EVERY suggestion (7.6ms each, so ~40 groups on the
    review page would have cost ~300ms), and the merchant engine re-read the
    learning table on every resolve — the exact N+1 the pool was added to
    prevent. Subclassing gives the instances a __dict__ and makes both caches
    real.

    No __slots__ here on purpose: declaring it would suppress the __dict__
    this class exists to provide.
    """


def connect(path: str) -> sqlite3.Connection:
    directory = os.path.dirname(os.path.abspath(path))
    if directory:
        os.makedirs(directory, exist_ok=True)
    conn = sqlite3.connect(path, check_same_thread=False, factory=Connection)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL;")
    conn.execute("PRAGMA foreign_keys=ON;")
    conn.execute("PRAGMA busy_timeout=5000;")
    # A finance ledger must survive power loss: NORMAL can lose the last
    # committed transaction on an abrupt shutdown, FULL cannot. Write volume
    # here is tiny (a few rows per SMS), so the fsync cost is irrelevant.
    conn.execute("PRAGMA synchronous=FULL;")
    # Bound WAL growth; without this the -wal file grows unbounded in a
    # long-lived process and slows crash recovery.
    conn.execute("PRAGMA wal_autocheckpoint=512;")
    return conn


def init_db(conn: sqlite3.Connection) -> int:
    """Bring the schema up to date. Returns the resulting schema version."""
    return migrations.upgrade(conn)


def open_database(path: str, *, backup: bool = True) -> tuple[sqlite3.Connection, dict]:
    """Open, verify, migrate and (if needed) recover the database.

    This is the entry point the app should use on startup. It returns the
    connection plus a status dict describing anything unusual that happened,
    so the UI can tell the user the truth instead of failing silently.
    """
    status: dict = {"recovered": False, "reset": False, "backup": None,
                    "version": None, "integrity": "ok", "repaired": {}}

    def _fresh() -> sqlite3.Connection:
        return connect(path)

    try:
        conn = _fresh()
        report = maintenance.integrity_report(conn)
        status["integrity"] = report["structural"]
        if not report["ok"] and report["structural"] != "ok":
            conn.close()
            rec = maintenance.recover(path)
            status["recovered"] = rec["restored"]
            status["reset"] = rec["reset"]
            conn = _fresh()
    except sqlite3.DatabaseError:
        # Cannot even open it — corrupt header or truncated file.
        rec = maintenance.recover(path)
        status["recovered"] = rec["restored"]
        status["reset"] = rec["reset"]
        status["integrity"] = "unreadable"
        conn = _fresh()

    # Back up BEFORE migrating so a failed upgrade is recoverable.
    pending = migrations.current_version(conn) < migrations.SCHEMA_VERSION
    if backup and pending:
        status["backup"] = maintenance.create_backup(conn, path)

    status["version"] = init_db(conn)

    orphans = maintenance.integrity_report(conn).get("orphans") or {}
    if orphans:
        status["repaired"] = maintenance.repair_orphans(conn)
    return conn, status


def one(conn: sqlite3.Connection, sql: str, params: tuple = ()) -> Optional[sqlite3.Row]:
    cur = conn.execute(sql, params)
    return cur.fetchone()


def all_rows(conn: sqlite3.Connection, sql: str, params: tuple = ()) -> list[sqlite3.Row]:
    return list(conn.execute(sql, params).fetchall())


def execute(conn: sqlite3.Connection, sql: str, params: tuple = ()) -> Any:
    cur = conn.execute(sql, params)
    return cur


def executemany(conn: sqlite3.Connection, sql: str, seq) -> Any:
    """Batch writes in one statement — bulk paths used to loop row-by-row."""
    return conn.executemany(sql, seq)
