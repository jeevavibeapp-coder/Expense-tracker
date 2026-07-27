"""Database durability: integrity checking, backup, restore and recovery.

Rationale
---------
This app is the *only* copy of the user's financial history: no cloud, and
Android backup is deliberately disabled for privacy. SQLite corruption on
low-end Android flash is a routine event at scale (power loss mid-write, OEM
filesystem bugs), so without this module a single corruption is total,
unrecoverable data loss.

Everything here is local-only — backups never leave the device.
"""
from __future__ import annotations

import datetime as dt
import os
import shutil
import sqlite3
from typing import Optional

BACKUP_DIR = "backups"
BACKUP_PREFIX = "spendwise-"
BACKUP_SUFFIX = ".bak.db"
KEEP_BACKUPS = 3
# Below this a "backup" is an empty/truncated file, never a real database.
MIN_VALID_BYTES = 4096


# ── Integrity ─────────────────────────────────────────────────────────────
def integrity_report(conn: sqlite3.Connection) -> dict:
    """Structural + referential health. Cheap at this data size (a few ms)."""
    report: dict = {"ok": True, "structural": "ok", "orphans": {}, "errors": []}
    try:
        rows = conn.execute("PRAGMA integrity_check").fetchall()
        first = rows[0][0] if rows else "unknown"
        report["structural"] = first
        if first != "ok":
            report["ok"] = False
            report["errors"].append(f"integrity_check: {first}")
    except sqlite3.DatabaseError as exc:
        report["ok"] = False
        report["structural"] = "unreadable"
        report["errors"].append(f"integrity_check raised: {exc}")
        return report

    # Referential integrity. The schema historically had no FOREIGN KEYs, so
    # PRAGMA foreign_key_check cannot see these — verify explicitly.
    checks = {
        "tx_category": "SELECT COUNT(*) FROM transactions t LEFT JOIN categories c "
                       "ON c.id=t.category_id WHERE t.category_id IS NOT NULL "
                       "AND c.id IS NULL",
        "tx_merchant": "SELECT COUNT(*) FROM transactions t LEFT JOIN merchants m "
                       "ON m.id=t.merchant_id WHERE t.merchant_id IS NOT NULL "
                       "AND m.id IS NULL",
        "learning_merchant": "SELECT COUNT(*) FROM learning l LEFT JOIN merchants m "
                             "ON m.id=l.merchant_id WHERE m.id IS NULL",
        "fraud_tx": "SELECT COUNT(*) FROM fraud_alerts f LEFT JOIN transactions t "
                    "ON t.id=f.transaction_id WHERE f.transaction_id IS NOT NULL "
                    "AND t.id IS NULL",
    }
    for name, sql in checks.items():
        try:
            n = conn.execute(sql).fetchone()[0]
        except sqlite3.DatabaseError:
            continue                       # table absent on a partial schema
        if n:
            report["orphans"][name] = n
    return report


def repair_orphans(conn: sqlite3.Connection) -> dict:
    """Null out dangling references and drop unreachable learning rows.

    Conservative by design: it never deletes a transaction, because a
    transaction with a stale category is still the user's real money.
    """
    fixed: dict = {}
    stmts = {
        "tx_category": "UPDATE transactions SET category_id=NULL WHERE category_id "
                       "IS NOT NULL AND category_id NOT IN (SELECT id FROM categories)",
        "tx_merchant": "UPDATE transactions SET merchant_id=NULL WHERE merchant_id "
                       "IS NOT NULL AND merchant_id NOT IN (SELECT id FROM merchants)",
        "learning_merchant": "DELETE FROM learning WHERE merchant_id NOT IN "
                             "(SELECT id FROM merchants)",
        "fraud_tx": "UPDATE fraud_alerts SET status='dismissed' WHERE transaction_id "
                    "IS NOT NULL AND transaction_id NOT IN (SELECT id FROM transactions)",
    }
    for name, sql in stmts.items():
        try:
            cur = conn.execute(sql)
            if cur.rowcount and cur.rowcount > 0:
                fixed[name] = cur.rowcount
        except sqlite3.DatabaseError:
            continue
    conn.commit()
    return fixed


# ── Backups ───────────────────────────────────────────────────────────────
def backup_dir_for(db_path: str) -> str:
    return os.path.join(os.path.dirname(os.path.abspath(db_path)), BACKUP_DIR)


def list_backups(db_path: str) -> list[str]:
    """Newest first."""
    d = backup_dir_for(db_path)
    if not os.path.isdir(d):
        return []
    files = [os.path.join(d, f) for f in os.listdir(d)
             if f.startswith(BACKUP_PREFIX) and f.endswith(BACKUP_SUFFIX)]
    return sorted(files, key=lambda p: os.path.getmtime(p), reverse=True)


def create_backup(conn: sqlite3.Connection, db_path: str,
                  keep: int = KEEP_BACKUPS) -> Optional[str]:
    """Consistent online backup via SQLite's backup API, then verify it.

    Uses the backup API rather than copying the file so it is safe while other
    connections are writing (a raw copy of a WAL database can be torn).
    Returns the backup path, or None if it could not be verified.
    """
    d = backup_dir_for(db_path)
    os.makedirs(d, exist_ok=True)
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    dest_path = os.path.join(d, f"{BACKUP_PREFIX}{stamp}{BACKUP_SUFFIX}")
    tmp_path = dest_path + ".tmp"
    try:
        dest = sqlite3.connect(tmp_path)
        try:
            conn.backup(dest)
        finally:
            dest.close()
        if not verify_backup(tmp_path):
            os.remove(tmp_path)
            return None
        os.replace(tmp_path, dest_path)     # atomic publish
    except (sqlite3.DatabaseError, OSError):
        try:
            if os.path.exists(tmp_path):
                os.remove(tmp_path)
        except OSError:
            pass
        return None
    _prune_backups(db_path, keep)
    return dest_path


def verify_backup(path: str) -> bool:
    """A backup is only useful if it opens, passes integrity_check and holds
    the expected schema — verify all three rather than trusting the file."""
    try:
        if not os.path.exists(path) or os.path.getsize(path) < MIN_VALID_BYTES:
            return False
        c = sqlite3.connect(path)
        try:
            if c.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                return False
            tables = {r[0] for r in c.execute(
                "SELECT name FROM sqlite_master WHERE type='table'")}
            return {"transactions", "categories", "users"}.issubset(tables)
        finally:
            c.close()
    except (sqlite3.DatabaseError, OSError):
        return False


def _prune_backups(db_path: str, keep: int) -> None:
    for stale in list_backups(db_path)[keep:]:
        try:
            os.remove(stale)
        except OSError:
            pass


def restore_backup(db_path: str, backup_path: str) -> bool:
    """Replace the live database with a verified backup.

    The damaged database is preserved alongside as ``.corrupt-<stamp>`` so a
    later manual salvage is still possible — never silently destroyed.
    """
    if not verify_backup(backup_path):
        return False
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    try:
        if os.path.exists(db_path):
            shutil.move(db_path, f"{db_path}.corrupt-{stamp}")
        # WAL/SHM belong to the replaced database and must not be reused.
        for side in ("-wal", "-shm"):
            p = db_path + side
            if os.path.exists(p):
                os.remove(p)
        shutil.copyfile(backup_path, db_path)
        return True
    except OSError:
        return False


def recover(db_path: str) -> dict:
    """Bring an unusable database back to life.

    Order: newest verified backup wins; if none exists, move the damaged file
    aside so the app can start fresh rather than crash-loop on every launch.
    """
    result = {"restored": False, "from": None, "reset": False}
    for candidate in list_backups(db_path):
        if restore_backup(db_path, candidate):
            result["restored"] = True
            result["from"] = candidate
            return result
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    try:
        if os.path.exists(db_path):
            shutil.move(db_path, f"{db_path}.corrupt-{stamp}")
        for side in ("-wal", "-shm"):
            p = db_path + side
            if os.path.exists(p):
                os.remove(p)
        result["reset"] = True
    except OSError:
        pass
    return result


# ── Space & write-ahead log ───────────────────────────────────────────────
def checkpoint(conn: sqlite3.Connection, truncate: bool = True) -> None:
    """Fold the WAL back into the main database.

    Without this the -wal file grows without bound on a long-lived process,
    consuming storage and slowing recovery after a crash.
    """
    mode = "TRUNCATE" if truncate else "PASSIVE"
    try:
        conn.execute(f"PRAGMA wal_checkpoint({mode})")
    except sqlite3.DatabaseError:
        pass


def vacuum(conn: sqlite3.Connection) -> None:
    """Reclaim space from deleted rows. Rewrites the file, so callers should
    run it rarely (e.g. after a large purge), never on every launch."""
    try:
        conn.execute("VACUUM")
    except sqlite3.DatabaseError:
        pass


def purge_soft_deleted(conn: sqlite3.Connection, older_than_days: int = 90) -> int:
    """Permanently drop rows soft-deleted long ago.

    Undo only needs to work for minutes, but every query pays the
    ``is_deleted=0`` filter forever — so old tombstones are pure cost.
    """
    cutoff = (dt.datetime.now() - dt.timedelta(days=older_than_days)).isoformat()
    cur = conn.execute(
        "DELETE FROM transactions WHERE is_deleted=1 AND created_at < ?", (cutoff,))
    conn.commit()
    return cur.rowcount or 0
