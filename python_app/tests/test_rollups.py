"""Daily rollup tables and their incremental maintenance.

A summary table is only useful if it can never disagree with what it
summarises. A stale rollup shows the user a wrong number with total
confidence, which is worse than a slow query. So almost every test here is
about agreement with the raw ledger under mutation, not about speed.

Honest note on the speed: at 12,000 rows the rollups take the dashboard's
tile query from 0.061ms to 0.030ms. That is a real halving but an
irrelevant one — the query was already served by the covering index
ix_tx_analytics and is 0.06ms of a 55ms dashboard. The value is asymptotic:
a month-range total now costs O(days in range) instead of O(transactions in
range), so it stops growing as history accumulates.
"""
from __future__ import annotations

import datetime as dt
import sqlite3

import pytest

from spendwise import analytics, db
from spendwise.app import create_app


def _fresh(tmp_path, name="r.db"):
    path = str(tmp_path / name)
    conn, _ = db.open_database(path, backup=False)
    conn.execute("INSERT INTO users VALUES ('u1','a@b.c','U','x','2024-01-01')")
    conn.commit()
    return path, conn


def _add(conn, tx_id, amount, day, type_="expense", uid="u1"):
    conn.execute(
        "INSERT INTO transactions(id,user_id,amount,type,occurred_at,source,"
        "status,created_at) VALUES (?,?,?,?,?,?,?,?)",
        (tx_id, uid, amount, type_, f"{day}T10:00:00+00:00", "manual",
         "confirmed", f"{day}T10:00:00+00:00"))
    conn.commit()


def _rollup(conn, day, type_="expense", uid="u1"):
    row = conn.execute("SELECT total, tx_count FROM daily_rollups "
                       "WHERE user_id=? AND day=? AND type=?",
                       (uid, day, type_)).fetchone()
    return (round(row[0], 2), row[1]) if row else (0.0, 0)


def _agrees_with_ledger(conn, uid="u1") -> bool:
    """The invariant the whole feature rests on."""
    raw = conn.execute(
        "SELECT type, substr(occurred_at,1,10) d, ROUND(SUM(amount),2), COUNT(*) "
        "FROM transactions WHERE user_id=? AND is_deleted=0 "
        "GROUP BY type, d ORDER BY d, type", (uid,)).fetchall()
    roll = conn.execute(
        "SELECT type, day, ROUND(total,2), tx_count FROM daily_rollups "
        "WHERE user_id=? ORDER BY day, type", (uid,)).fetchall()
    return [tuple(r) for r in raw] == [tuple(r) for r in roll]


# ── Maintenance ───────────────────────────────────────────────────────────
def test_insert_marks_the_day_dirty_and_refresh_builds_it(tmp_path):
    path, conn = _fresh(tmp_path)
    _add(conn, "t1", 100.0, "2025-03-05")
    assert conn.execute("SELECT COUNT(*) FROM rollup_dirty").fetchone()[0] == 1
    assert analytics.refresh_rollups(conn, "u1") == 1
    assert _rollup(conn, "2025-03-05") == (100.0, 1)
    assert conn.execute("SELECT COUNT(*) FROM rollup_dirty").fetchone()[0] == 0
    conn.close()


def test_refresh_is_a_no_op_when_nothing_changed(tmp_path):
    """The common case. If this did work it would be a tax on every read."""
    path, conn = _fresh(tmp_path, "r2.db")
    _add(conn, "t1", 100.0, "2025-03-05")
    analytics.refresh_rollups(conn, "u1")
    assert analytics.refresh_rollups(conn, "u1") == 0
    conn.close()


def test_amount_edit_is_reflected(tmp_path):
    path, conn = _fresh(tmp_path, "r3.db")
    _add(conn, "t1", 100.0, "2025-03-05")
    analytics.refresh_rollups(conn, "u1")
    conn.execute("UPDATE transactions SET amount=250 WHERE id='t1'")
    conn.commit()
    analytics.refresh_rollups(conn, "u1")
    assert _rollup(conn, "2025-03-05") == (250.0, 1)
    assert _agrees_with_ledger(conn)
    conn.close()


def test_moving_a_transaction_to_another_day_updates_both_days(tmp_path):
    """The trigger must mark the OLD day as well as the new one, or the
    original day keeps counting money that is no longer there."""
    path, conn = _fresh(tmp_path, "r4.db")
    _add(conn, "t1", 100.0, "2025-03-05")
    analytics.refresh_rollups(conn, "u1")
    conn.execute("UPDATE transactions SET occurred_at='2025-03-09T10:00:00+00:00' "
                 "WHERE id='t1'")
    conn.commit()
    analytics.refresh_rollups(conn, "u1")
    assert _rollup(conn, "2025-03-05") == (0.0, 0), "old day still counts the money"
    assert _rollup(conn, "2025-03-09") == (100.0, 1)
    assert _agrees_with_ledger(conn)
    conn.close()


def test_soft_delete_removes_it_from_the_totals(tmp_path):
    """is_deleted is a column, not a row removal — so this is an UPDATE and
    only the UPDATE trigger can catch it."""
    path, conn = _fresh(tmp_path, "r5.db")
    _add(conn, "t1", 100.0, "2025-03-05")
    _add(conn, "t2", 40.0, "2025-03-05")
    analytics.refresh_rollups(conn, "u1")
    assert _rollup(conn, "2025-03-05") == (140.0, 2)
    conn.execute("UPDATE transactions SET is_deleted=1 WHERE id='t1'")
    conn.commit()
    analytics.refresh_rollups(conn, "u1")
    assert _rollup(conn, "2025-03-05") == (40.0, 1)
    assert _agrees_with_ledger(conn)
    conn.close()


def test_restore_after_soft_delete_brings_it_back(tmp_path):
    path, conn = _fresh(tmp_path, "r6.db")
    _add(conn, "t1", 100.0, "2025-03-05")
    conn.execute("UPDATE transactions SET is_deleted=1 WHERE id='t1'")
    conn.commit()
    analytics.refresh_rollups(conn, "u1")
    assert _rollup(conn, "2025-03-05") == (0.0, 0)
    conn.execute("UPDATE transactions SET is_deleted=0 WHERE id='t1'")
    conn.commit()
    analytics.refresh_rollups(conn, "u1")
    assert _rollup(conn, "2025-03-05") == (100.0, 1)
    conn.close()


def test_hard_delete_is_reflected(tmp_path):
    path, conn = _fresh(tmp_path, "r7.db")
    _add(conn, "t1", 100.0, "2025-03-05")
    analytics.refresh_rollups(conn, "u1")
    conn.execute("DELETE FROM transactions WHERE id='t1'")
    conn.commit()
    analytics.refresh_rollups(conn, "u1")
    assert _rollup(conn, "2025-03-05") == (0.0, 0)
    assert _agrees_with_ledger(conn)
    conn.close()


def test_income_and_expense_are_kept_separate(tmp_path):
    path, conn = _fresh(tmp_path, "r8.db")
    _add(conn, "t1", 100.0, "2025-03-05", "expense")
    _add(conn, "t2", 5000.0, "2025-03-05", "income")
    analytics.refresh_rollups(conn, "u1")
    assert _rollup(conn, "2025-03-05", "expense") == (100.0, 1)
    assert _rollup(conn, "2025-03-05", "income") == (5000.0, 1)
    conn.close()


def test_changing_type_moves_the_money_between_buckets(tmp_path):
    path, conn = _fresh(tmp_path, "r9.db")
    _add(conn, "t1", 100.0, "2025-03-05", "expense")
    analytics.refresh_rollups(conn, "u1")
    conn.execute("UPDATE transactions SET type='income' WHERE id='t1'")
    conn.commit()
    analytics.refresh_rollups(conn, "u1")
    assert _rollup(conn, "2025-03-05", "expense") == (0.0, 0)
    assert _rollup(conn, "2025-03-05", "income") == (100.0, 1)
    conn.close()


def test_rollups_never_leak_between_users(tmp_path):
    path, conn = _fresh(tmp_path, "r10.db")
    conn.execute("INSERT INTO users VALUES ('u2','c@d.e','V','x','2024-01-01')")
    conn.commit()
    _add(conn, "t1", 100.0, "2025-03-05", uid="u1")
    _add(conn, "t2", 700.0, "2025-03-05", uid="u2")
    analytics.refresh_rollups(conn, "u1")
    assert _rollup(conn, "2025-03-05", uid="u1") == (100.0, 1)
    # u2's day is still dirty — refreshing one user must not touch another's.
    assert _rollup(conn, "2025-03-05", uid="u2") == (0.0, 0)
    analytics.refresh_rollups(conn, "u2")
    assert _rollup(conn, "2025-03-05", uid="u2") == (700.0, 1)
    conn.close()


def test_deleting_a_merchant_does_not_abort_on_the_dirty_trigger(tmp_path):
    """Regression: the trigger used INSERT OR IGNORE, but an ON CONFLICT
    clause inside a trigger body is overridden by the conflict policy of the
    statement that fired it. Deleting a merchant runs ON DELETE SET NULL,
    whose implicit UPDATE fires the trigger twice for the same day, and the
    delete aborted with a UNIQUE violation."""
    path, conn = _fresh(tmp_path, "r11.db")
    conn.execute("INSERT INTO merchants(id,user_id,canonical_name) "
                 "VALUES ('m1','u1','Swiggy')")
    conn.execute(
        "INSERT INTO transactions(id,user_id,amount,type,merchant_id,occurred_at,"
        "source,status,created_at) VALUES ('t1','u1',100,'expense','m1',"
        "'2025-03-05T10:00:00+00:00','sms','confirmed','2025-03-05T10:00:00+00:00')")
    conn.commit()
    conn.execute("DELETE FROM merchants WHERE id='m1'")   # must not raise
    conn.commit()
    analytics.refresh_rollups(conn, "u1")
    assert _rollup(conn, "2025-03-05") == (100.0, 1)
    conn.close()


def test_bulk_mutations_keep_the_rollups_in_agreement(tmp_path):
    """Fuzz-ish: many mixed writes, then assert the invariant directly."""
    path, conn = _fresh(tmp_path, "r12.db")
    base = dt.date(2025, 1, 1)
    for i in range(120):
        _add(conn, f"t{i}", 10.0 + i, (base + dt.timedelta(days=i % 30)).isoformat(),
             "income" if i % 7 == 0 else "expense")
    analytics.refresh_rollups(conn, "u1")
    assert _agrees_with_ledger(conn)

    conn.execute("UPDATE transactions SET amount = amount * 2 WHERE id LIKE 't1_'")
    conn.execute("UPDATE transactions SET is_deleted=1 WHERE id IN ('t3','t4','t5')")
    conn.execute("UPDATE transactions SET occurred_at='2025-06-06T10:00:00+00:00' "
                 "WHERE id IN ('t20','t21')")
    conn.execute("DELETE FROM transactions WHERE id IN ('t60','t61')")
    conn.commit()
    analytics.refresh_rollups(conn, "u1")
    assert _agrees_with_ledger(conn)
    conn.close()


# ── The dashboard actually uses them, and gets the same answer ────────────
def test_dashboard_tiles_match_the_direct_scan(tmp_path):
    """The rollup path and the scan it replaced must agree exactly, or the
    optimisation silently changes the user's numbers."""
    path, conn = _fresh(tmp_path, "r13.db")
    now = analytics._now()
    for i in range(40):
        _add(conn, f"t{i}", 100.0 + i,
             (now - dt.timedelta(days=i)).strftime("%Y-%m-%d"))
    analytics.refresh_rollups(conn, "u1")

    day_start = now.replace(hour=0, minute=0, second=0, microsecond=0)
    week_start = day_start - dt.timedelta(days=day_start.weekday())
    month_start = day_start.replace(day=1)
    until = analytics._tomorrow_iso()
    scan = conn.execute(
        "SELECT COALESCE(SUM(CASE WHEN occurred_at>=:d THEN amount END),0) d, "
        "COALESCE(SUM(CASE WHEN occurred_at>=:w THEN amount END),0) w, "
        "COALESCE(SUM(CASE WHEN occurred_at>=:m THEN amount END),0) m "
        "FROM transactions WHERE user_id=:u AND type='expense' AND is_deleted=0 "
        "AND occurred_at<:until",
        {"u": "u1", "d": day_start.isoformat(), "w": week_start.isoformat(),
         "m": month_start.isoformat(), "until": until}).fetchone()

    d = analytics.build_dashboard(conn, "u1")
    assert abs(d["daily_spend"] - round(scan[0], 2)) < 0.01
    assert abs(d["weekly_spend"] - round(scan[1], 2)) < 0.01
    assert abs(d["monthly_spend"] - round(scan[2], 2)) < 0.01
    conn.close()


def test_dashboard_refreshes_stale_rollups_on_read(tmp_path):
    """No background job: a write followed immediately by a read must show
    the new number, not a stale one."""
    path, conn = _fresh(tmp_path, "r14.db")
    today = analytics._now().strftime("%Y-%m-%d")
    _add(conn, "t1", 100.0, today)
    assert analytics.build_dashboard(conn, "u1")["daily_spend"] == 100.0
    _add(conn, "t2", 55.0, today)
    assert analytics.build_dashboard(conn, "u1")["daily_spend"] == 155.0
    conn.close()


def test_dashboard_still_works_on_a_database_without_rollups(tmp_path):
    """Defence in depth: the scan fallback must survive if the tables are
    somehow absent."""
    path, conn = _fresh(tmp_path, "r15.db")
    today = analytics._now().strftime("%Y-%m-%d")
    _add(conn, "t1", 100.0, today)
    conn.execute("DROP TRIGGER rollup_dirty_ai")
    conn.execute("DROP TRIGGER rollup_dirty_au")
    conn.execute("DROP TRIGGER rollup_dirty_ad")
    conn.execute("DROP TABLE daily_rollups")
    conn.execute("DROP TABLE rollup_dirty")
    conn.commit()
    assert analytics.rollups_available(conn) is False
    assert analytics.build_dashboard(conn, "u1")["daily_spend"] == 100.0
    conn.close()


def test_migration_seeds_rollups_from_an_existing_ledger(tmp_path):
    """Upgrading users already have history; without a seed their tiles would
    read zero until every old day happened to be touched again."""
    from spendwise import migrations
    path, conn = _fresh(tmp_path, "r16.db")
    for i in range(10):
        _add(conn, f"t{i}", 100.0, f"2025-02-{i + 1:02d}")
    conn.execute("DELETE FROM daily_rollups")
    conn.execute("DELETE FROM rollup_dirty")
    conn.commit()
    migrations._m8_daily_rollups(conn)
    conn.commit()
    assert conn.execute("SELECT COUNT(*) FROM daily_rollups").fetchone()[0] == 10
    assert _agrees_with_ledger(conn)
    conn.close()
