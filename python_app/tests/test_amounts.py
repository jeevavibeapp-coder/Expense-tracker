"""B2 regression: non-finite and absurd amounts must never reach the ledger.

The defect: `float()` accepts far more than money. A 400-digit SMS amount
became `inf`, and `inf > 0` passed the transaction gate, so it was stored.
`detect_transfers` then evaluated `int(round(amount * 100))` and raised
OverflowError, permanently returning 500 from /dashboard — the app's launch
screen — for anyone who received one message. No permission, no interaction,
no install: the attacker only needs the victim's phone number.

These tests fail against the pre-fix code and pass after it.
"""
from __future__ import annotations

import math
import sqlite3

import pytest

from spendwise import analytics, db, migrations
from spendwise.app import create_app
from spendwise.parsing import MAX_AMOUNT, parse_sms, safe_amount

TOKEN = "tok"

# The exact payload that took the dashboard down in the release audit.
KILLER_SMS = ("Rs." + "9" * 400 + " debited from a/c XX4521 on 12-07-26 "
              "to VPA evil@ybl. Ref 999999999999")


def _client(tmp_path, name="amt.db"):
    app = create_app(db_path=str(tmp_path / name), single_user=True,
                     secret_key="s", device_token=TOKEN)
    c = app.test_client()
    # Authenticate exactly as the WebView does: a one-time ?k=<device token>
    # grant on the first navigation, which mints the signed session cookie.
    c.get(f"/?k={TOKEN}", environ_overrides={"REMOTE_ADDR": "127.0.0.1"})
    # Skip the first-run introduction: these tests are about the app after
    # setup, not about the introduction itself, which has its own tests.
    c.post("/welcome/done")
    return app, c


def _ingest(c, body, sender="AD-HDFCBK"):
    return c.post("/sms/ingest", data={"sender": sender, "body": body},
                  headers={"X-SpendWise-Token": TOKEN},
                  environ_overrides={"REMOTE_ADDR": "127.0.0.1"})


# ── The validator itself ──────────────────────────────────────────────────
@pytest.mark.parametrize("bad", [
    "inf", "-inf", "Infinity", "-Infinity", "INF", "infinity",
    "nan", "NaN", "-nan", "NAN",
    "1e309", "1e400", "-1e309", "1e999999",
    "9" * 400, "9" * 4000,
    "-500", "-0.01", "0", "0.0", "-0",
    "1e-400", "1e-999",                 # underflow to zero
    "", "   ", None, "abc", "12abc", "0x10", "1,,000", "..", "+-5",
])
def test_safe_amount_rejects_everything_that_is_not_money(bad):
    assert safe_amount(bad) is None


@pytest.mark.parametrize("good,expect", [
    ("500.25", 500.25), ("1", 1.0), ("0.01", 0.01),
    ("1e12", 1e12), (str(MAX_AMOUNT), MAX_AMOUNT), (250, 250.0), (99.999, 99.999),
])
def test_safe_amount_accepts_real_money(good, expect):
    got = safe_amount(good)
    assert got is not None and math.isclose(got, expect)


def test_the_upper_bound_survives_the_arithmetic_that_crashed():
    """MAX_AMOUNT exists so int(amount * 100) — the exact expression that
    raised OverflowError — cannot overflow for any accepted value."""
    v = safe_amount(str(MAX_AMOUNT))
    assert int(round(v * 100)) == 10 ** 14      # no exception, fits in int64


# ── Fuzz: thousands of malformed numeric strings ──────────────────────────
def _fuzz_corpus():
    import itertools
    prefixes = ["", "-", "+", " ", "Rs.", "​"]
    cores = ["inf", "nan", "Infinity", "1e309", "1e-400", "0", "9" * 300,
             "1_0", "1,000", "0x1f", "1.2.3", "e5", ".", "1e", "١٢٣", "٠"]
    suffixes = ["", ".", "e", "e999", "%", "​", "0" * 100]
    for p, c, s in itertools.product(prefixes, cores, suffixes):
        yield p + c + s
    for n in range(1, 900):
        yield "9" * n
        yield "1e" + str(n * 10)
        yield "-" + "9" * n
        yield "0." + "0" * n + "1"


def test_fuzz_no_accepted_value_is_ever_unsafe():
    """The invariant that matters: whatever survives the validator must be
    finite, positive and safe for the money arithmetic downstream."""
    checked = accepted = 0
    for raw in _fuzz_corpus():
        checked += 1
        v = safe_amount(raw)
        if v is None:
            continue
        accepted += 1
        assert math.isfinite(v), raw
        assert 0 < v <= MAX_AMOUNT, raw
        int(round(v * 100))                  # must not raise
    assert checked > 2000, f"corpus too small ({checked})"
    assert accepted > 0, "fuzz accepted nothing — the test proves nothing"


def test_fuzz_parse_sms_never_yields_an_unsafe_matched_amount():
    """Same invariant, but through the real SMS parser."""
    bodies = [f"Rs.{raw} debited from a/c XX1234 to SHOP Ref 123456789012"
              for raw in _fuzz_corpus()]
    for body in bodies:
        r = parse_sms(body, "AD-HDFCBK")
        if r.matched:
            assert r.amount is not None and math.isfinite(r.amount), body[:60]
            assert 0 < r.amount <= MAX_AMOUNT, body[:60]
            int(round(r.amount * 100))


# ── The original exploit, end to end ──────────────────────────────────────
def test_the_killer_sms_is_no_longer_captured(tmp_path):
    app, c = _client(tmp_path)
    r = _ingest(c, KILLER_SMS)
    assert r.status_code == 200
    assert r.get_json()["captured"] is False

    conn = sqlite3.connect(app.config["DB_PATH"])
    n = conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0]
    conn.close()
    assert n == 0, "a non-finite amount reached the ledger"


def test_the_dashboard_survives_the_killer_sms(tmp_path):
    """The actual user-visible symptom: /dashboard returned 500 forever."""
    app, c = _client(tmp_path, "amt2.db")
    _ingest(c, "Rs.500.00 debited from a/c XX4521 on 01-07-26 to VPA swiggy@ybl. "
               "Ref 111111111111")
    _ingest(c, KILLER_SMS)
    assert c.get("/dashboard").status_code == 200
    assert c.get("/", follow_redirects=True).status_code == 200
    for path in ("/transactions", "/report", "/export.csv", "/review"):
        assert c.get(path).status_code == 200, path


def test_nothing_is_silently_discarded_when_an_amount_is_rejected(tmp_path):
    """Rejecting the amount must not delete the evidence — the message still
    has to be recorded so the user can see it was received."""
    app, c = _client(tmp_path, "amt3.db")
    _ingest(c, KILLER_SMS)
    conn = sqlite3.connect(app.config["DB_PATH"])
    misses = conn.execute("SELECT COUNT(*) FROM parse_misses").fetchone()[0]
    conn.close()
    assert misses == 1


# ── The manual entry path, which has its own parser ───────────────────────
@pytest.mark.parametrize("bad", ["inf", "nan", "1e400", "9" * 400, "-5", "0"])
def test_hand_typed_amounts_are_rejected_too(tmp_path, bad):
    app, c = _client(tmp_path, f"amt-m-{abs(hash(bad))}.db")
    c.post("/transactions", data={"amount": bad, "type": "expense",
                                  "merchant": "Typed"}, follow_redirects=True)
    conn = sqlite3.connect(app.config["DB_PATH"])
    n = conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0]
    conn.close()
    assert n == 0, f"{bad!r} was accepted from the manual form"


def test_the_storage_gate_holds_even_when_a_caller_skips_validation(tmp_path):
    """Defence in depth: /import/create feeds create_transaction from parsed
    rows. If a caller ever forgot to validate, the storage gate must still
    refuse rather than persist a value that poisons every aggregate."""
    app, c = _client(tmp_path, "amt4.db")
    # Drive the import path with an amount the row parser will hand through.
    r = c.post("/import/create", data={"count": "1", "amount_0": "inf",
                                       "type_0": "expense", "merchant_0": "X",
                                       "date_0": "2025-01-01"},
               follow_redirects=True)
    assert r.status_code in (200, 302, 400)
    conn = sqlite3.connect(app.config["DB_PATH"])
    bad = conn.execute(
        "SELECT COUNT(*) FROM transactions WHERE amount != amount "
        "OR amount > 1e12 OR amount <= 0").fetchone()[0]
    conn.close()
    assert bad == 0, "an unusable amount reached the ledger via import"


# ── Ledgers already poisoned in the field ─────────────────────────────────
def test_migration_quarantines_existing_infinite_amounts(tmp_path):
    """Devices can already hold a poisoned row. The write-side fix alone
    would leave those dashboards broken forever."""
    path = str(tmp_path / "amt5.db")
    conn, _ = db.open_database(path, backup=False)
    conn.execute("INSERT INTO users VALUES ('u1','a@b.c','U','x','2024-01-01')")
    # NOTE: float("nan") is NOT usable here — SQLite coerces NaN to NULL, so
    # the NOT NULL constraint on amount rejects it outright and NaN can never
    # reach the ledger by any path. Only the infinities and absurd magnitudes
    # are actually storable, which is what this fixture reproduces.
    for i, amount in enumerate([500.0, float("inf"), float("-inf"), -3.0, 1e300]):
        conn.execute(
            "INSERT INTO transactions(id,user_id,amount,type,merchant_name,"
            "occurred_at,source,status,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
            (f"t{i}", "u1", amount, "expense", f"M{i}",
             "2025-01-01T00:00:00", "sms", "confirmed", "2025-01-01T00:00:00"))
    conn.commit()
    # Simulate the upgrade arriving on that device.
    migrations._m9_sanitise_amounts(conn)
    conn.commit()

    live = conn.execute(
        "SELECT id, amount FROM transactions WHERE is_deleted=0").fetchall()
    assert [r[0] for r in live] == ["t0"], "poisoned rows still live"
    # Quarantined, NOT deleted — the SMS body is the user's only record.
    assert conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0] == 5
    assert analytics.build_dashboard(conn, "u1")["total_expense"] == 500.0
    conn.close()


def test_sanitising_migration_is_idempotent(tmp_path):
    path = str(tmp_path / "amt6.db")
    conn, _ = db.open_database(path, backup=False)
    conn.execute("INSERT INTO users VALUES ('u1','a@b.c','U','x','2024-01-01')")
    conn.execute(
        "INSERT INTO transactions(id,user_id,amount,type,occurred_at,source,"
        "status,created_at) VALUES ('ok','u1',250,'expense','2025-01-01',"
        "'manual','confirmed','2025-01-01')")
    conn.commit()
    for _ in range(3):
        migrations._m9_sanitise_amounts(conn)
        conn.commit()
    assert conn.execute(
        "SELECT COUNT(*) FROM transactions WHERE is_deleted=0").fetchone()[0] == 1
    conn.close()


def test_analytics_survive_a_poisoned_row_that_bypassed_every_gate(tmp_path):
    """Defence in depth. The DB file is user-writable, so read-side maths must
    not assume the write-side gate held."""
    path = str(tmp_path / "amt7.db")
    conn, _ = db.open_database(path, backup=False)
    conn.execute("INSERT INTO users VALUES ('u1','a@b.c','U','x','2024-01-01')")
    conn.execute(
        "INSERT INTO transactions(id,user_id,amount,type,merchant_name,"
        "occurred_at,source,status,created_at) VALUES ('good','u1',300,'expense',"
        "'Swiggy','2025-06-01T10:00:00','sms','confirmed','2025-06-01T10:00:00')")
    # Written directly, exactly as a corrupted or hand-edited file would be.
    for i, amount in enumerate([float("inf"), float("-inf"), 1e300]):
        conn.execute(
            "INSERT INTO transactions(id,user_id,amount,type,merchant_name,"
            "occurred_at,source,status,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
            (f"bad{i}", "u1", amount, "income" if i else "expense", "Evil",
             "2025-06-02T10:00:00", "sms", "confirmed", "2025-06-02T10:00:00"))
    conn.commit()

    # None of these may raise.
    analytics.build_dashboard(conn, "u1")
    analytics.money_flow(conn, "u1")
    analytics.detect_transfers(conn, "u1")
    analytics.detect_refunds(conn, "u1")
    analytics.detect_recurring(conn, "u1")
    analytics.build_report(conn, "u1", "2025-06")
    analytics.refresh_rollups(conn, "u1")
    conn.close()


def test_every_page_renders_with_a_poisoned_row_present(tmp_path):
    path = str(tmp_path / "amt8.db")
    app = create_app(db_path=path, single_user=True, secret_key="s",
                     device_token=TOKEN)
    c = app.test_client()
    c.get(f"/?k={TOKEN}")            # authenticate as the WebView does
    c.post("/welcome/done")          # past the first-run introduction
    c.get("/dashboard")
    conn = db.connect(path)
    uid = conn.execute("SELECT id FROM users").fetchone()[0]
    conn.execute(
        "INSERT INTO transactions(id,user_id,amount,type,merchant_name,"
        "occurred_at,source,status,created_at) VALUES ('bad',?,?,'expense',"
        "'Evil','2025-06-02T10:00:00','sms','confirmed','2025-06-02T10:00:00')",
        (uid, float("inf")))
    conn.commit()
    conn.close()
    for path_ in ("/dashboard", "/transactions", "/report", "/review",
                  "/categories", "/fraud", "/export.csv", "/sms/quarantine"):
        assert c.get(path_).status_code == 200, path_
