"""SQLite data layer (Python standard library only).

A single file-backed database; on Android it lives in the app's private files
directory. UUID text primary keys keep rows portable.
"""
from __future__ import annotations

import os
import sqlite3
import uuid
from typing import Any, Optional

SCHEMA = """
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
CREATE INDEX IF NOT EXISTS ix_learning_user_raw ON learning(user_id, raw_name);
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


def new_id() -> str:
    return uuid.uuid4().hex


def connect(path: str) -> sqlite3.Connection:
    directory = os.path.dirname(os.path.abspath(path))
    if directory:
        os.makedirs(directory, exist_ok=True)
    conn = sqlite3.connect(path, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL;")
    conn.execute("PRAGMA foreign_keys=ON;")
    return conn


def init_db(conn: sqlite3.Connection) -> None:
    conn.executescript(SCHEMA)
    _migrate(conn)
    conn.commit()


def _migrate(conn: sqlite3.Connection) -> None:
    """Lightweight, additive migrations for databases created by older builds
    (CREATE TABLE IF NOT EXISTS never adds new columns to an existing table)."""
    cols = {row["name"] for row in conn.execute("PRAGMA table_info(transactions)")}
    if "category_prompted" not in cols:
        conn.execute("ALTER TABLE transactions ADD COLUMN "
                     "category_prompted INTEGER NOT NULL DEFAULT 0")


def one(conn: sqlite3.Connection, sql: str, params: tuple = ()) -> Optional[sqlite3.Row]:
    cur = conn.execute(sql, params)
    return cur.fetchone()


def all_rows(conn: sqlite3.Connection, sql: str, params: tuple = ()) -> list[sqlite3.Row]:
    return list(conn.execute(sql, params).fetchall())


def execute(conn: sqlite3.Connection, sql: str, params: tuple = ()) -> Any:
    cur = conn.execute(sql, params)
    return cur
