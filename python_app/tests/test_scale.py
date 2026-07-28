"""Scale, concurrency and performance-regression tests.

Everything else in the suite runs with <30 rows, which proves correctness but
says nothing about behaviour at real volume. These build a realistic multi-year
ledger and assert that the hot paths stay bounded — turning previously
hand-measured, one-off performance claims into enforced guarantees.

Thresholds are deliberately loose (CI machines vary wildly). They are set to
catch ORDER-OF-MAGNITUDE regressions — e.g. reintroducing a full-history scan
or an N+1 — not to police small fluctuations.
"""
from __future__ import annotations

import datetime as dt
import os
import random
import sqlite3
import threading
import time

import pytest

from spendwise import analytics, db, engine, migrations
from spendwise.app import create_app

MERCHANTS = ["Swiggy", "Zomato", "Amazon", "Uber", "Netflix", "BigBasket",
             "Jio", "Apollo", "DMart", "Rapido", "Myntra", "Blinkit"]
CATEGORIES = ["Food & Dining", "Groceries", "Shopping", "Transport",
              "Bills & Utilities", "Entertainment", "Health"]


def _seed_large(path: str, n_tx: int = 12_000, seed: int = 7) -> str:
    """Build a ~3-year ledger directly via SQL (fast) and return the user id."""
    rnd = random.Random(seed)
    conn, _ = db.open_database(path, backup=False)
    uid = "u-scale"
    now = dt.datetime.now()
    conn.execute("INSERT INTO users VALUES (?,?,?,?,?)",
                 (uid, "scale@test", "Scale User", "x", now.isoformat()))
    cat_ids = []
    for i, name in enumerate(CATEGORIES):
        cid = f"c{i}"
        cat_ids.append(cid)
        conn.execute("INSERT INTO categories(id,user_id,name,type) VALUES (?,?,?,?)",
                     (cid, uid, name, "expense"))
    merch_ids = []
    for i, name in enumerate(MERCHANTS):
        mid = f"m{i}"
        merch_ids.append((mid, name))
        conn.execute("INSERT INTO merchants(id,user_id,canonical_name,category_id) "
                     "VALUES (?,?,?,?)", (mid, uid, name, rnd.choice(cat_ids)))

    rows = []
    for i in range(n_tx):
        mid, mname = rnd.choice(merch_ids)
        when = now - dt.timedelta(days=rnd.randint(0, 1000),
                                  minutes=rnd.randint(0, 1439))
        rows.append((
            f"t{i}", uid, round(rnd.uniform(20, 5000), 2),
            "income" if i % 40 == 0 else "expense", rnd.choice(cat_ids),
            mname, mid, mname, None, f"REF{i:012d}", when.isoformat(),
            "sms" if i % 3 else "manual", 90, "confirmed", 1,
            f"DEDUP{i:012d}", None, None, 0, when.isoformat()))
    conn.executemany(
        "INSERT INTO transactions(id,user_id,amount,type,category_id,raw_merchant,"
        "merchant_id,merchant_name,notes,reference_number,occurred_at,source,"
        "confidence,status,category_prompted,dedup_key,sms_body,sms_sender,"
        "is_deleted,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        rows)
    # Learning rows so the merchant engine has a realistic table to search.
    conn.executemany(
        "INSERT INTO learning(id,user_id,raw_name,merchant_id,merchant_name,"
        "category_id,confirmation_count,sample_count,avg_amount,amount_min,"
        "amount_max,hour_histogram) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
        [(f"l{i}", uid, name.upper(), mid, name, cat_ids[i % len(cat_ids)],
          5, 5, 500.0, 100.0, 900.0, "[0]*24".replace("[0]*24", "[]"))
         for i, (mid, name) in enumerate(merch_ids)])
    conn.commit()
    conn.close()
    return uid


@pytest.fixture(scope="module")
def large_db(tmp_path_factory):
    path = str(tmp_path_factory.mktemp("scale") / "large.db")
    uid = _seed_large(path)
    return path, uid


def _timed(fn, *a, **kw):
    start = time.perf_counter()
    out = fn(*a, **kw)
    return out, (time.perf_counter() - start) * 1000


# ── Bounded hot paths ─────────────────────────────────────────────────────
def test_dashboard_bounded_at_12k_transactions(large_db):
    """The most-visited screen must not scale with total history.

    Guards the fix for the unbounded detect_recurring/_trend scans.
    """
    path, uid = large_db
    conn = db.connect(path)
    d, ms = _timed(analytics.build_dashboard, conn, uid)
    conn.close()
    assert d["tx_count"] == 12_000
    # Measured ~89ms at 12k rows. Threshold catches order-of-magnitude
    # regressions (a reintroduced full-history scan), not CI jitter.
    assert ms < 1500, f"dashboard took {ms:.0f}ms at 12k rows — likely a full scan"


def test_recurring_detection_is_window_bounded(large_db):
    path, uid = large_db
    conn = db.connect(path)
    _, ms = _timed(analytics.detect_recurring, conn, uid)
    conn.close()
    assert ms < 1200, f"detect_recurring took {ms:.0f}ms — window bound may be gone"


def test_money_flow_bounded(large_db):
    """Transfer/refund detection is O(debits x credits) in the worst case;
    verify the windowing keeps it usable at real volume."""
    path, uid = large_db
    conn = db.connect(path)
    flow, ms = _timed(analytics.money_flow, conn, uid)
    conn.close()
    assert "expense_net" in flow
    # Measured 34ms after amount-bucketing (was 132ms with the naive
    # O(debits x credits) scan). A large regression means the index was lost.
    assert ms < 800, f"money_flow took {ms:.0f}ms at 12k rows — amount index lost?"


def test_report_bounded(large_db):
    path, uid = large_db
    conn = db.connect(path)
    month = dt.datetime.now().strftime("%Y-%m")
    _, ms = _timed(analytics.build_report, conn, uid, month)
    conn.close()
    assert ms < 1500, f"monthly report took {ms:.0f}ms at 12k rows"


def test_merchant_resolution_does_not_rescan_learning_per_call(large_db):
    """Regression guard for the N+1: 12 resolves must not cost 12x one resolve."""
    path, uid = large_db
    conn = db.connect(path)
    _, first = _timed(engine.resolve, conn, user_id=uid, raw_name="UNSEEN MERCHANT A")
    start = time.perf_counter()
    for i in range(12):
        engine.resolve(conn, user_id=uid, raw_name=f"UNSEEN MERCHANT {i}")
    twelve = (time.perf_counter() - start) * 1000
    conn.close()
    # With the per-request pool cache, 12 lookups should cost far less than
    # 12 independent scans. Allow generous headroom for CI noise.
    assert twelve < max(first * 8, 400), (
        f"12 resolves took {twelve:.0f}ms vs {first:.0f}ms for one — cache lost?")


def test_activity_page_bounded_at_scale(large_db):
    """Rendering Activity must stay bounded (it also runs suggestion lookups)."""
    path, _ = large_db
    app = create_app(db_path=path, single_user=True, secret_key="s")
    c = app.test_client()
    with c.session_transaction() as sess:
        sess["user_id"] = "u-scale"
    start = time.perf_counter()
    r = c.get("/transactions")
    ms = (time.perf_counter() - start) * 1000
    assert r.status_code == 200
    assert ms < 3000, f"/transactions took {ms:.0f}ms at 12k rows"


def test_search_returns_correct_results_at_scale(large_db):
    path, _ = large_db
    app = create_app(db_path=path, single_user=True, secret_key="s")
    c = app.test_client()
    with c.session_transaction() as sess:
        sess["user_id"] = "u-scale"
    r = c.get("/transactions?q=netflix")
    assert r.status_code == 200
    assert b"Netflix" in r.data


# ── Concurrency ───────────────────────────────────────────────────────────
def test_concurrent_writers_do_not_corrupt_or_deadlock(tmp_path):
    """The receiver, the queue drain and the UI all hit SQLite at once.

    busy_timeout=5000 was an untested bet; this exercises it. WAL allows one
    writer at a time, so contention must resolve by waiting, never by losing
    a write or raising 'database is locked'.
    """
    path = str(tmp_path / "conc.db")
    conn, _ = db.open_database(path, backup=False)
    conn.execute("INSERT INTO users VALUES ('u1','a@b.c','U','x','2024-01-01')")
    conn.commit()
    conn.close()

    errors: list[str] = []
    per_thread = 40

    def writer(tag: int) -> None:
        c = db.connect(path)
        try:
            for i in range(per_thread):
                c.execute(
                    "INSERT INTO transactions(id,user_id,amount,type,occurred_at,"
                    "source,status,created_at) VALUES (?,?,?,?,?,?,?,?)",
                    (f"t{tag}-{i}", "u1", 10.0 + i, "expense",
                     "2025-01-01T00:00:00+00:00", "sms", "confirmed",
                     "2025-01-01T00:00:00+00:00"))
                c.commit()
        except sqlite3.Error as exc:      # noqa: PERF203 - we want the message
            errors.append(f"writer{tag}: {exc}")
        finally:
            c.close()

    threads = [threading.Thread(target=writer, args=(t,)) for t in range(4)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=60)

    assert not errors, f"concurrent writes failed: {errors}"
    check = db.connect(path)
    total = check.execute("SELECT COUNT(*) FROM transactions").fetchone()[0]
    integrity = check.execute("PRAGMA integrity_check").fetchone()[0]
    check.close()
    assert total == 4 * per_thread, f"lost writes: {total} of {4 * per_thread}"
    assert integrity == "ok"


def test_reader_sees_consistent_data_during_writes(tmp_path):
    """WAL must let a reader proceed while a writer is active (no blocking)."""
    path = str(tmp_path / "rw.db")
    conn, _ = db.open_database(path, backup=False)
    conn.execute("INSERT INTO users VALUES ('u1','a@b.c','U','x','2024-01-01')")
    conn.commit()
    conn.close()

    stop = threading.Event()
    errors: list[str] = []

    def writer() -> None:
        c = db.connect(path)
        i = 0
        try:
            while not stop.is_set() and i < 200:
                c.execute(
                    "INSERT INTO transactions(id,user_id,amount,type,occurred_at,"
                    "source,status,created_at) VALUES (?,?,?,?,?,?,?,?)",
                    (f"w{i}", "u1", 5.0, "expense", "2025-01-01T00:00:00+00:00",
                     "sms", "confirmed", "2025-01-01T00:00:00+00:00"))
                c.commit()
                i += 1
        except sqlite3.Error as exc:
            errors.append(f"writer: {exc}")
        finally:
            c.close()

    t = threading.Thread(target=writer)
    t.start()
    try:
        reader = db.connect(path)
        for _ in range(50):
            n = reader.execute("SELECT COUNT(*) FROM transactions").fetchone()[0]
            assert n >= 0            # never raises "database is locked"
        reader.close()
    finally:
        stop.set()
        t.join(timeout=30)
    assert not errors, errors


# ── Durability at scale ───────────────────────────────────────────────────
def test_backup_and_verify_at_scale(large_db):
    from spendwise import maintenance
    path, _ = large_db
    conn = db.connect(path)
    backup, ms = _timed(maintenance.create_backup, conn, path)
    conn.close()
    assert backup and maintenance.verify_backup(backup)
    assert os.path.getsize(backup) > 100_000
    assert ms < 10_000, f"backup of a 12k-row DB took {ms:.0f}ms"


# ── Migration cost at scale ───────────────────────────────────────────────
def test_foreign_key_rebuild_is_bounded_on_a_real_sized_ledger(tmp_path):
    """v6 rebuilds every table to add constraints. That is a copy of the whole
    ledger, and it runs on the UI's critical path at first launch after an
    upgrade — so its cost has to stay in the "user sees a spinner" range, not
    the "user thinks the app hung" range.

    Measured ~380ms for 12k rows. The threshold catches an accidental O(n^2)
    (e.g. a per-row subquery in the orphan repair), not CI jitter.
    """
    from tests.test_migrations import LEGACY_V1
    path = str(tmp_path / "legacy-big.db")
    raw = sqlite3.connect(path)
    raw.executescript(LEGACY_V1)
    raw.execute("INSERT INTO users VALUES ('u1','a@b.c','U','x','2024-01-01')")
    cats = []
    for i in range(len(CATEGORIES)):
        raw.execute("INSERT INTO categories(id,user_id,name) VALUES (?,?,?)",
                    (f"c{i}", "u1", CATEGORIES[i]))
        cats.append(f"c{i}")
    for i, name in enumerate(MERCHANTS):
        raw.execute("INSERT INTO merchants(id,user_id,canonical_name) VALUES (?,?,?)",
                    (f"m{i}", "u1", name))
    rnd = random.Random(11)
    now = dt.datetime.now()
    raw.executemany(
        "INSERT INTO transactions(id,user_id,amount,type,category_id,raw_merchant,"
        "merchant_id,merchant_name,occurred_at,source,confidence,status,is_deleted,"
        "created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0,?)",
        [(f"t{i}", "u1", round(rnd.uniform(20, 5000), 2), "expense",
          rnd.choice(cats), MERCHANTS[i % len(MERCHANTS)], f"m{i % len(MERCHANTS)}",
          MERCHANTS[i % len(MERCHANTS)],
          (now - dt.timedelta(days=rnd.randint(0, 1000))).isoformat(),
          "sms", 90, "confirmed", now.isoformat())
         for i in range(12_000)])
    raw.commit()
    raw.close()

    conn, (status, ms) = None, (None, None)
    start = time.perf_counter()
    conn, status = db.open_database(path, backup=False)
    ms = (time.perf_counter() - start) * 1000

    assert status["version"] == migrations.SCHEMA_VERSION
    # Every row survived the copy, and the constraints hold.
    assert conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0] == 12_000
    assert conn.execute("PRAGMA foreign_key_check").fetchall() == []
    conn.close()
    assert ms < 8000, f"the v6 rebuild took {ms:.0f}ms for 12k rows"


def test_indexed_search_beats_the_scan_on_a_rare_term(large_db):
    """The LIKE scan's cost is set by TABLE size, not result size, so its
    worst case is a term that matches little or nothing — exactly the
    reference-number lookup a user does when reconciling a statement.

    Measured at 12k rows: LIKE 12.1ms, FTS5 0.04ms. The assertion is
    relative (index must beat the scan) rather than an absolute millisecond
    figure, so it survives a slow CI runner while still failing if the index
    stops being used.
    """
    from spendwise import search
    path, uid = large_db
    conn = db.connect(path)
    if not search.available(conn):
        conn.close()
        pytest.skip("FTS5 not available in this SQLite build")

    term = "REF000000011111"
    like = f"%{term.lower()}%"

    def scan():
        return conn.execute(
            "SELECT id FROM transactions WHERE user_id=? AND is_deleted=0 AND ("
            "lower(COALESCE(merchant_name,'')) LIKE ? OR "
            "lower(COALESCE(raw_merchant,'')) LIKE ? OR "
            "lower(COALESCE(notes,'')) LIKE ? OR "
            "lower(COALESCE(reference_number,'')) LIKE ?) LIMIT 200",
            (uid, like, like, like, like)).fetchall()

    scan(); search.search_ids(conn, uid, term)          # warm
    t = time.perf_counter()
    for _ in range(10):
        scan_rows = scan()
    scan_ms = (time.perf_counter() - t) / 10 * 1000
    t = time.perf_counter()
    for _ in range(10):
        fts_rows = search.search_ids(conn, uid, term)
    fts_ms = (time.perf_counter() - t) / 10 * 1000
    conn.close()

    # Same answer — an index that is fast but wrong is worthless.
    assert len(fts_rows or []) == len(scan_rows)
    assert fts_ms < scan_ms, (
        f"FTS5 {fts_ms:.2f}ms did not beat the scan {scan_ms:.2f}ms — "
        "the index is probably not being used")


def test_fts_index_stays_consistent_at_scale(large_db):
    from spendwise import search
    path, _ = large_db
    conn = db.connect(path)
    ok = search.integrity_ok(conn)
    conn.close()
    assert ok
