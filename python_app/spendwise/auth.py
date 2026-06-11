"""Authentication helpers — PBKDF2 password hashing (stdlib hashlib).

No native crypto dependency, so this runs unchanged inside the Android APK.
"""
from __future__ import annotations

import datetime as dt
import hashlib
import hmac
import os
from typing import Optional

from . import db

_PBKDF2_ROUNDS = 240_000
_ALGO = "sha256"

DEFAULT_CATEGORIES = [
    ("Salary", "income", "Wallet", "#10b981"),
    ("Business", "income", "Briefcase", "#3b82f6"),
    ("Other Income", "income", "PlusCircle", "#64748b"),
    ("Food & Dining", "expense", "Utensils", "#f43f5e"),
    ("Groceries", "expense", "ShoppingCart", "#22c55e"),
    ("Shopping", "expense", "ShoppingBag", "#ec4899"),
    ("Transport", "expense", "Car", "#f59e0b"),
    ("Bills & Utilities", "expense", "Zap", "#3b82f6"),
    ("Entertainment", "expense", "Tv", "#8b5cf6"),
    ("Health", "expense", "HeartPulse", "#ef4444"),
    ("Other Expense", "expense", "PlusCircle", "#64748b"),
]


class AuthError(Exception):
    def __init__(self, message: str):
        super().__init__(message)
        self.message = message


def hash_password(password: str) -> str:
    salt = os.urandom(16)
    dk = hashlib.pbkdf2_hmac(_ALGO, password.encode("utf-8"), salt, _PBKDF2_ROUNDS)
    return f"pbkdf2${_ALGO}${_PBKDF2_ROUNDS}${salt.hex()}${dk.hex()}"


def verify_password(password: str, stored: str) -> bool:
    try:
        scheme, algo, rounds, salt_hex, hash_hex = stored.split("$")
        if scheme != "pbkdf2":
            return False
        dk = hashlib.pbkdf2_hmac(algo, password.encode("utf-8"),
                                 bytes.fromhex(salt_hex), int(rounds))
        return hmac.compare_digest(dk.hex(), hash_hex)
    except (ValueError, TypeError):
        return False


def _provision(conn, user_id: str) -> None:
    for name, type_, icon, color in DEFAULT_CATEGORIES:
        db.execute(conn,
                   "INSERT INTO categories(id,user_id,name,type,icon,color) VALUES (?,?,?,?,?,?)",
                   (db.new_id(), user_id, name, type_, icon, color))
    db.execute(conn, "INSERT INTO settings(user_id) VALUES (?)", (user_id,))
    conn.commit()


def create_user(conn, *, email: str, full_name: str, password: str) -> str:
    email = email.lower().strip()
    if db.one(conn, "SELECT id FROM users WHERE email=?", (email,)):
        raise AuthError("An account with this email already exists")
    uid = db.new_id()
    db.execute(conn,
               "INSERT INTO users(id,email,full_name,pw_hash,created_at) VALUES (?,?,?,?,?)",
               (uid, email, full_name.strip(), hash_password(password),
                dt.datetime.now(dt.timezone.utc).isoformat()))
    _provision(conn, uid)
    conn.commit()
    return uid


def authenticate(conn, *, email: str, password: str) -> str:
    row = db.one(conn, "SELECT * FROM users WHERE email=?", (email.lower().strip(),))
    if not row or not verify_password(password, row["pw_hash"]):
        raise AuthError("Invalid email or password")
    return row["id"]


def ensure_local_user(conn) -> str:
    """For the embedded single-user (mobile) mode: return the one local user,
    creating it on first launch so no login is required."""
    row = db.one(conn, "SELECT id FROM users ORDER BY created_at LIMIT 1")
    if row:
        return row["id"]
    uid = db.new_id()
    db.execute(conn,
               "INSERT INTO users(id,email,full_name,pw_hash,created_at) VALUES (?,?,?,?,?)",
               (uid, "local@spendwise.app", "You", hash_password(os.urandom(8).hex()),
                dt.datetime.now(dt.timezone.utc).isoformat()))
    _provision(conn, uid)
    conn.commit()
    return uid


def get_user(conn, user_id: str) -> Optional[db.sqlite3.Row]:
    return db.one(conn, "SELECT * FROM users WHERE id=?", (user_id,))
