"""Full-fidelity backup and restore, entirely on the device.

The CSV export was never a backup. It is a report: it drops merchant links,
learning, category budgets, sender trust and every id, so restoring from it
would produce a ledger that looks similar and behaves differently. For an
app whose whole premise is that your data lives on your phone and nowhere
else, "your phone is the only copy" is not a feature — a lost phone is a
lost ledger, and there is no server to recover it from.

So: a single JSON file the user holds. It restores the parts of the ledger
that carry meaning — transactions, categories, merchants, learned merchant
mappings, sender trust and preferences.

Two rules shape everything here:

  * A restore must never half-happen. Every write runs inside one
    transaction; a malformed file leaves the ledger exactly as it was.
  * A restore must never trust the file. It is a document the user can edit
    with a text editor, so every row is validated on the way in with the
    same rules the SMS and manual paths use.

Deliberately NOT included: raw SMS bodies, quarantined messages and parse
misses. They are the most sensitive text on the device and they are
recoverable — the inbox is still there and rescanning rebuilds them. A
backup file that contains a year of bank messages is a much worse thing to
lose than one that does not.
"""
from __future__ import annotations

import datetime as dt
import json

from . import db
from .parsing import safe_amount


BACKUP_FORMAT = 1

# Column lists are explicit rather than SELECT *: a future migration that
# adds a column should not silently change what a backup contains, and a
# backup written by a newer build must still restore into an older one.
_TABLES: dict[str, tuple[str, ...]] = {
    "categories": ("id", "name", "type", "icon", "color", "budget_amount",
                   "is_archived"),
    "merchants": ("id", "canonical_name", "category_id"),
    "learning": ("id", "raw_name", "merchant_id", "merchant_name", "category_id",
                 "confidence", "confirmation_count", "correction_count",
                 "sample_count", "avg_amount", "amount_min", "amount_max",
                 "hour_histogram", "last_seen_at"),
    "transactions": ("id", "amount", "type", "category_id", "raw_merchant",
                     "merchant_id", "merchant_name", "notes", "reference_number",
                     "occurred_at", "source", "confidence", "status",
                     "category_prompted", "dedup_key", "is_deleted", "created_at"),
    "sms_senders": ("id", "sender", "display", "kind", "entity", "bank", "trust",
                    "message_count", "captured_count", "confirmed_count",
                    "quarantined_count", "last_risk", "first_seen_at",
                    "last_seen_at"),
}

_SETTINGS_KEYS = ("currency", "theme", "auto_save_threshold", "confirm_threshold",
                  "high_value_amount")


class RestoreError(Exception):
    """The file is not a backup this build can restore. The message is shown
    to the user, so it says what is wrong rather than naming a Python type."""


# ── export ────────────────────────────────────────────────────────────────
def build_backup(conn, user_id: str) -> dict:
    """Everything worth restoring, as a plain dict ready for json.dumps."""
    out: dict = {
        "format": BACKUP_FORMAT,
        "app": "SpendWise",
        "created_at": dt.datetime.now().replace(microsecond=0).isoformat(),
        "tables": {},
    }
    for table, cols in _TABLES.items():
        rows = db.all_rows(
            conn, f"SELECT {', '.join(cols)} FROM {table} WHERE user_id=?",
            (user_id,))
        out["tables"][table] = [dict(zip(cols, tuple(r))) for r in rows]

    s = db.one(conn, "SELECT * FROM settings WHERE user_id=?", (user_id,))
    out["settings"] = {k: s[k] for k in _SETTINGS_KEYS} if s else {}
    out["counts"] = {t: len(rows) for t, rows in out["tables"].items()}
    return out


def backup_bytes(conn, user_id: str) -> bytes:
    return json.dumps(build_backup(conn, user_id), ensure_ascii=False,
                      separators=(",", ":")).encode("utf-8")


# ── validation ────────────────────────────────────────────────────────────
def _clean_text(value, limit: int = 500):
    if value is None:
        return None
    if not isinstance(value, (str, int, float)):
        return None
    return str(value)[:limit]


def parse_backup(raw: bytes | str) -> dict:
    """Turn an uploaded file into a validated backup document.

    Raises RestoreError with a sentence the user can act on. This is the only
    place that decides whether a file is acceptable; restore() below assumes
    it has already run.
    """
    if isinstance(raw, bytes):
        try:
            raw = raw.decode("utf-8")
        except UnicodeDecodeError:
            raise RestoreError("That file isn't a SpendWise backup — it isn't "
                               "text this app can read.")
    if not raw.strip():
        raise RestoreError("That file is empty.")
    try:
        doc = json.loads(raw)
    except (json.JSONDecodeError, ValueError):
        raise RestoreError("That file isn't a SpendWise backup — it isn't valid "
                           "JSON. If you meant to import a CSV, use Import "
                           "instead.")
    if not isinstance(doc, dict):
        raise RestoreError("That file isn't a SpendWise backup.")
    fmt = doc.get("format")
    if fmt is None or doc.get("app") != "SpendWise":
        raise RestoreError("That file isn't a SpendWise backup.")
    if not isinstance(fmt, int) or fmt > BACKUP_FORMAT:
        raise RestoreError(
            f"That backup was written by a newer version of SpendWise "
            f"(format {fmt}). Update the app, then restore.")
    tables = doc.get("tables")
    if not isinstance(tables, dict):
        raise RestoreError("That backup is missing its data.")
    for table in _TABLES:
        rows = tables.get(table, [])
        if not isinstance(rows, list):
            raise RestoreError(f"The {table} section of that backup is damaged.")
    return doc


def summarise(doc: dict) -> dict:
    """What the user is about to restore, for the confirmation screen. Nobody
    should press a button called Restore without being told what is in the
    file."""
    tables = doc.get("tables", {})
    txs = [t for t in tables.get("transactions", []) if isinstance(t, dict)]
    dates = sorted(str(t.get("occurred_at") or "")[:10] for t in txs
                   if t.get("occurred_at"))
    return {
        "created_at": doc.get("created_at"),
        "transactions": len(txs),
        "categories": len([c for c in tables.get("categories", [])
                           if isinstance(c, dict)]),
        "merchants": len([mm for mm in tables.get("merchants", [])
                          if isinstance(mm, dict)]),
        "senders": len([s for s in tables.get("sms_senders", [])
                        if isinstance(s, dict)]),
        "first": dates[0] if dates else None,
        "last": dates[-1] if dates else None,
    }


# ── restore ───────────────────────────────────────────────────────────────
def restore(conn, user_id: str, doc: dict, *, replace: bool = False) -> dict:
    """Write a validated backup into this user's ledger.

    replace=False (merge) keeps everything already here and adds only rows
    whose id is not present. It is the safe default and it is idempotent:
    restoring the same file twice changes nothing the second time.

    replace=True clears this user's rows first. It is the "new phone" path,
    and the only one that can lose data, so the UI asks for it explicitly.

    The whole thing is one transaction. A file that turns out to be damaged
    halfway through leaves the ledger untouched rather than half-merged,
    which for a ledger is the difference between a failed restore and a
    corrupted one.
    """
    tables = doc.get("tables", {})
    added = {t: 0 for t in _TABLES}
    skipped = {t: 0 for t in _TABLES}

    try:
        conn.execute("BEGIN IMMEDIATE")
    except Exception:                       # already inside a transaction
        pass

    try:
        if replace:
            # Children first: FKs are real since the v6 migration.
            for table in ("learning", "transactions", "merchants",
                          "sms_senders", "categories"):
                conn.execute(f"DELETE FROM {table} WHERE user_id=?", (user_id,))

        # Insertion order matters: a transaction referencing a category must
        # not be written before that category exists, or the FK rejects it.
        for table in ("categories", "merchants", "learning", "transactions",
                      "sms_senders"):
            cols = _TABLES[table]
            rows = tables.get(table, [])
            for row in rows:
                if not isinstance(row, dict):
                    skipped[table] += 1
                    continue
                values = _coerce_row(table, row, cols, user_id)
                if values is None:
                    skipped[table] += 1
                    continue
                names = ("user_id",) + cols
                marks = ",".join("?" for _ in names)
                cur = conn.execute(
                    f"INSERT OR IGNORE INTO {table}({','.join(names)}) "
                    f"VALUES ({marks})", values)
                if cur.rowcount:
                    added[table] += 1
                else:
                    skipped[table] += 1

        s = doc.get("settings")
        if isinstance(s, dict):
            _restore_settings(conn, user_id, s)

        conn.commit()
    except Exception as exc:                # noqa: BLE001 - reported to the user
        conn.rollback()
        raise RestoreError(
            "The backup could not be restored, so nothing was changed. "
            f"({type(exc).__name__})") from exc

    return {"added": added, "skipped": skipped,
            "total_added": sum(added.values())}


def _coerce_row(table: str, row: dict, cols: tuple[str, ...], user_id: str):
    """Validate one row and return its INSERT values, or None to skip it.

    The backup file is a text document the user can edit, so nothing in it is
    trusted: ids must be present and sane, amounts go through the same
    safe_amount() gate as the SMS parser, and foreign keys that point at rows
    the file never carried are dropped to NULL rather than failing the whole
    restore.
    """
    rid = row.get("id")
    if not isinstance(rid, str) or not (0 < len(rid) <= 64):
        return None

    if table == "transactions":
        amount = safe_amount(row.get("amount"))
        if amount is None or amount <= 0:
            return None
        occurred = _clean_text(row.get("occurred_at"), 40)
        if not occurred:
            return None
        if row.get("type") not in ("expense", "income"):
            return None
    if table == "categories" and not _clean_text(row.get("name"), 60):
        return None
    if table == "merchants" and not _clean_text(row.get("canonical_name"), 120):
        return None
    if table == "sms_senders" and not _clean_text(row.get("sender"), 40):
        return None
    if table == "learning" and not isinstance(row.get("merchant_id"), str):
        return None

    out = [user_id]
    for c in cols:
        v = row.get(c)
        if c == "amount":
            v = safe_amount(v)
        elif c in ("budget_amount", "high_value_amount", "avg_amount",
                   "amount_min", "amount_max"):
            v = safe_amount(v)
            if v is None:
                v = 0.0 if c in ("avg_amount", "amount_min", "amount_max") else None
        elif c in ("is_archived", "is_deleted", "category_prompted", "confidence",
                   "confirmation_count", "correction_count", "sample_count",
                   "message_count", "captured_count", "confirmed_count",
                   "quarantined_count", "last_risk"):
            try:
                v = int(v) if v is not None else 0
            except (TypeError, ValueError):
                v = 0
        elif c == "hour_histogram":
            v = v if isinstance(v, str) else "[]"
        else:
            v = _clean_text(v, 2000)
        out.append(v)

    # NOT NULL columns that a hand-edited file might have emptied.
    defaults = {"created_at": _clean_text(row.get("occurred_at"), 40) or
                dt.datetime.now().isoformat(),
                "source": "restore", "status": "confirmed", "type": "expense",
                "kind": "other", "trust": "unknown", "icon": "Tag",
                "color": "#6366f1", "first_seen_at": dt.datetime.now().isoformat(),
                "last_seen_at": dt.datetime.now().isoformat()}
    for i, c in enumerate(cols, start=1):
        if out[i] is None and c in defaults:
            out[i] = defaults[c]
    return tuple(out)


def _restore_settings(conn, user_id: str, s: dict) -> None:
    """Preferences are restored with the same clamps the settings form uses —
    a hand-edited backup must not be able to set a 900% threshold."""
    row = db.one(conn, "SELECT user_id FROM settings WHERE user_id=?", (user_id,))
    if row is None:
        conn.execute("INSERT INTO settings(user_id) VALUES (?)", (user_id,))

    currency = _clean_text(s.get("currency"), 8)
    theme = s.get("theme") if s.get("theme") in ("system", "light", "dark") else None
    hv = safe_amount(s.get("high_value_amount"))

    def _pct(value, fallback):
        try:
            return max(0, min(100, int(value)))
        except (TypeError, ValueError):
            return fallback

    cur = db.one(conn, "SELECT * FROM settings WHERE user_id=?", (user_id,))
    conn.execute(
        "UPDATE settings SET currency=?, theme=?, auto_save_threshold=?, "
        "confirm_threshold=?, high_value_amount=? WHERE user_id=?",
        (currency or cur["currency"], theme or cur["theme"],
         _pct(s.get("auto_save_threshold"), cur["auto_save_threshold"]),
         _pct(s.get("confirm_threshold"), cur["confirm_threshold"]),
         hv if hv is not None else cur["high_value_amount"], user_id))
