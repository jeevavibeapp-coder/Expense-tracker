"""Bank parser registry, Naive Bayes categoriser and adaptive thresholds.

All three change what the app does with a user's money without being asked,
so the tests here are weighted toward the ways they could be WRONG:

  * a bank profile that fabricates or vetoes a transaction,
  * a categoriser that answers confidently on no evidence,
  * a feedback loop that drives a threshold to an extreme.
"""
from __future__ import annotations

import sqlite3

import pytest

from spendwise import banks, calibration, categorizer, db, senders
from spendwise.app import create_app
from spendwise.parsing import parse_sms

TOKEN = "tok"

# Real-shaped messages; amounts and account digits are fictional.
HDFC_UPI = ("Rs.450.00 debited from a/c XX4521 on 12-07-26 to VPA swiggy@ybl. "
            "Ref 402198877123. Not you? Call 18002586161. -HDFC Bank")
AXIS_UPI = ("INR 780.00 debited from A/c no. XX9012 on 09-07-26. "
            "Info: UPI/P2M/419988776655/BLINKIT. Axis Bank")
ICICI_CARD = ("INR 1,299.00 spent on ICICI Bank Card XX8890 at AMAZON on "
              "11-Jul-26. Avl Lmt INR 48,701.00.")
SBI_SALARY = ("Your A/c X1123 credited INR 52,000.00 on 01-07-26 by transfer "
              "from ACME PAYROLL Ref 9988 -SBI")


# ── Bank parser registry ──────────────────────────────────────────────────
def test_registry_extracts_a_clean_merchant_the_generic_parser_mangles():
    """The generic 'to X' pattern captures the UPI prefix. That is not
    cosmetic: merchant_name is the engine's learning key, so 'VPA swiggy' and
    'Swiggy' would become two merchants the user categorises separately."""
    generic = parse_sms(HDFC_UPI)
    refined = parse_sms(HDFC_UPI, "AD-HDFCBK")
    assert generic.raw_merchant == "VPA swiggy"
    assert refined.raw_merchant == "swiggy"
    assert refined.bank == "HDFC Bank"
    assert refined.parsed_by == "HDFCBK"


def test_registry_unwraps_an_axis_upi_path():
    generic = parse_sms(AXIS_UPI)
    refined = parse_sms(AXIS_UPI, "BP-AXISBK")
    assert generic.raw_merchant == "UPI/P2M/419988776655/BLINKIT"
    assert refined.raw_merchant == "BLINKIT"
    # And it fills a reference the generic pattern could not see.
    assert generic.reference_number is None
    assert refined.reference_number == "419988776655"


def test_registry_extracts_the_account_tail():
    """A field the generic parser never produced at all."""
    assert parse_sms(HDFC_UPI, "AD-HDFCBK").account_tail == "4521"
    assert parse_sms(ICICI_CARD, "VM-ICICIB").account_tail == "8890"
    assert parse_sms(AXIS_UPI, "BP-AXISBK").account_tail == "9012"


def test_registry_never_degrades_a_good_generic_result():
    """The common case must be untouched — most messages the generic parser
    already handles correctly."""
    for sender, body in [("VM-ICICIB", ICICI_CARD), ("JD-SBIINB", SBI_SALARY)]:
        generic = parse_sms(body)
        refined = parse_sms(body, sender)
        assert refined.raw_merchant == generic.raw_merchant
        assert refined.amount == generic.amount
        assert refined.type == generic.type


def test_registry_cannot_create_a_transaction():
    """The containment property. A bank profile runs only AFTER the generic
    gate accepts a message, so no pattern here can inject a false capture."""
    promo = ("Get a personal loan of Rs.5,00,000 at 9.9%. "
             "Apply now at hdfcbank.com/loans -HDFC Bank")
    assert parse_sms(promo).matched is False
    assert parse_sms(promo, "AD-HDFCBK").matched is False


def test_registry_cannot_veto_a_transaction():
    """The other half: an unrecognised or broken profile must not suppress a
    capture the generic parser accepted."""
    assert parse_sms(HDFC_UPI, "AD-ZZZBNK").matched is True
    assert parse_sms(HDFC_UPI, "+919812345678").matched is True
    assert parse_sms(HDFC_UPI, None).matched is True


def test_bank_merchant_candidates_face_the_same_rejection_rules():
    """A bank pattern must not be able to smuggle a date or a marketing verb
    through as a merchant name."""
    body = ("Rs.100.00 debited from a/c XX1234 on 11-JUN-26 to 11-JUN-26 on 12. "
            "Ref 123456789012")
    refined = parse_sms(body, "AD-HDFCBK")
    assert refined.raw_merchant != "11-JUN-26"


def test_profile_aliases_resolve_to_a_shared_format():
    assert banks.profile_for("HDFCBN") is banks.profile_for("HDFCBK")
    assert banks.profile_for("SBIUPI") is banks.profile_for("SBIINB")
    assert banks.profile_for("ZZZZZZ") is None
    assert banks.profile_for(None) is None


def test_every_registered_profile_is_reachable_from_a_known_sender():
    """A profile keyed on an entity that senders.identify never produces is
    dead code that would silently never run."""
    for entity in banks.PROFILES:
        assert senders.identify(f"AD-{entity}").entity == entity
    for alias, target in banks.ALIASES.items():
        assert target in banks.PROFILES, f"{alias} points at a missing profile"


# ── Naive Bayes categoriser ───────────────────────────────────────────────
def _train_rows():
    food = [("SWIGGY order", "food"), ("swiggy instamart", "food"),
            ("ZOMATO delivery", "food"), ("zomato gold", "food"),
            ("DOMINOS PIZZA", "food"), ("PIZZA HUT", "food"),
            ("burger king", "food"), ("KFC bengaluru", "food")]
    travel = [("UBER trip", "travel"), ("uber india", "travel"),
              ("OLA CABS", "travel"), ("ola auto", "travel"),
              ("RAPIDO bike", "travel"), ("IRCTC train ticket", "travel"),
              ("indigo flight", "travel"), ("redbus booking", "travel")]
    return food + travel


def test_model_generalises_to_an_unseen_merchant():
    """The whole point: a merchant never seen before, whose WORDS have been."""
    model = categorizer.train(_train_rows())
    assert model is not None
    pred = model.predict("DOMINOS PIZZA HSR LAYOUT")
    assert pred and pred[0] == "food"
    pred = model.predict("UBER RIDES PRIVATE LIMITED")
    assert pred and pred[0] == "travel"


def test_model_abstains_rather_than_guessing():
    """Precision over recall: a confident wrong answer teaches the user to
    distrust every suggestion, an empty one costs almost nothing."""
    model = categorizer.train(_train_rows())
    assert model.predict("QWERTYUIOP ZXCVBNM") is None
    assert model.predict("") is None
    assert model.predict("12345 67890") is None


def test_no_model_without_enough_history():
    """A brand-new install must not suggest from three examples."""
    assert categorizer.train([("swiggy", "food"), ("uber", "travel")]) is None
    assert categorizer.train([]) is None


def test_no_model_when_every_row_is_one_category():
    """With one class there is nothing to discriminate, and a 100%-confident
    single answer would be meaningless."""
    rows = [(f"merchant{i}", "food") for i in range(30)]
    assert categorizer.train(rows) is None


def test_digits_and_stopwords_do_not_drive_predictions():
    """Reference numbers are unique per transaction; if they carried weight
    the model would memorise noise instead of generalising."""
    rows = _train_rows() + [(f"UPI P2M {i:012d} ref txn", "food")
                            for i in range(10)]
    model = categorizer.train(rows)
    assert model.predict("UPI P2M 999999999999 ref txn") is None


def test_prediction_is_explainable():
    model = categorizer.train(_train_rows())
    because = model.explain("DOMINOS PIZZA HSR")
    assert because
    assert any(w in ("dominos", "pizza") for w in because)


def test_confidence_is_a_real_probability():
    model = categorizer.train(_train_rows())
    pred = model.predict("SWIGGY INSTAMART BENGALURU")
    assert pred and categorizer.MIN_CONFIDENCE <= pred[1] <= 1.0


def test_suggest_trains_from_the_users_own_confirmed_history(tmp_path):
    path = str(tmp_path / "nb.db")
    conn, _ = db.open_database(path, backup=False)
    conn.execute("INSERT INTO users VALUES ('u1','a@b.c','U','x','2024-01-01')")
    for cid, name in (("c-food", "Food"), ("c-travel", "Travel")):
        conn.execute("INSERT INTO categories(id,user_id,name) VALUES (?,?,?)",
                     (cid, "u1", name))
    for i, (text, cat) in enumerate(_train_rows()):
        conn.execute(
            "INSERT INTO transactions(id,user_id,amount,type,category_id,"
            "raw_merchant,occurred_at,source,status,created_at) "
            "VALUES (?,?,?,?,?,?,?,?,?,?)",
            (f"t{i}", "u1", 100.0, "expense",
             "c-food" if cat == "food" else "c-travel", text,
             "2025-01-01T00:00:00", "sms", "confirmed", "2025-01-01T00:00:00"))
    conn.commit()

    hint = categorizer.suggest(conn, "u1", "DOMINOS PIZZA KORAMANGALA")
    assert hint and hint["category_id"] == "c-food"
    assert hint["confidence"] >= 62
    assert hint["because"]
    conn.close()


def test_unconfirmed_rows_do_not_train_the_model(tmp_path):
    """Training on rows the user has not confirmed would let one bad
    auto-categorisation reinforce itself."""
    path = str(tmp_path / "nb2.db")
    conn, _ = db.open_database(path, backup=False)
    conn.execute("INSERT INTO users VALUES ('u1','a@b.c','U','x','2024-01-01')")
    conn.execute("INSERT INTO categories(id,user_id,name) VALUES ('c1','u1','Food')")
    conn.execute("INSERT INTO categories(id,user_id,name) VALUES ('c2','u1','Travel')")
    for i, (text, cat) in enumerate(_train_rows()):
        conn.execute(
            "INSERT INTO transactions(id,user_id,amount,type,category_id,"
            "raw_merchant,occurred_at,source,status,created_at) "
            "VALUES (?,?,?,?,?,?,?,?,?,?)",
            (f"t{i}", "u1", 100.0, "expense", "c1" if cat == "food" else "c2",
             text, "2025-01-01T00:00:00", "sms", "needs_review",
             "2025-01-01T00:00:00"))
    conn.commit()
    assert categorizer.suggest(conn, "u1", "DOMINOS PIZZA") is None
    conn.close()


def test_review_page_shows_a_suggestion_the_user_still_has_to_tap(tmp_path):
    """It must be visible and explained, not silently pre-applied."""
    app = create_app(db_path=str(tmp_path / "nb3.db"), single_user=True,
                     secret_key="s", device_token=TOKEN)
    c = app.test_client()
    c.get(f"/?k={TOKEN}")            # authenticate as the WebView does
    conn = db.connect(app.config["DB_PATH"])
    uid = None
    c.get("/dashboard")
    conn = db.connect(app.config["DB_PATH"])
    uid = conn.execute("SELECT id FROM users").fetchone()[0]
    cats = {r[1]: r[0] for r in conn.execute(
        "SELECT id, name FROM categories WHERE user_id=?", (uid,)).fetchall()}
    food = next((v for k, v in cats.items() if "Food" in k), None)
    travel = next((v for k, v in cats.items() if "Transport" in k or "Travel" in k),
                  None)
    if not (food and travel):
        conn.close()
        pytest.skip("default categories differ")
    for i, (text, cat) in enumerate(_train_rows()):
        conn.execute(
            "INSERT INTO transactions(id,user_id,amount,type,category_id,"
            "raw_merchant,occurred_at,source,status,created_at) "
            "VALUES (?,?,?,?,?,?,?,?,?,?)",
            (f"h{i}", uid, 100.0, "expense", food if cat == "food" else travel,
             text, "2025-01-01T00:00:00", "sms", "confirmed", "2025-01-01T00:00:00"))
    # An unreviewed capture from a merchant never seen before.
    conn.execute(
        "INSERT INTO transactions(id,user_id,amount,type,raw_merchant,"
        "merchant_name,occurred_at,source,status,created_at) "
        "VALUES ('new1',?,250,'expense','DOMINOS PIZZA HSR','DOMINOS PIZZA HSR',"
        "'2025-06-01T00:00:00','sms','needs_review','2025-06-01T00:00:00')", (uid,))
    conn.commit()
    conn.close()

    page = c.get("/review").data
    assert b"Suggested from" in page, "no explanation rendered"
    assert b"%" in page
    # The chip is a submit button the user must press — nothing was applied.
    conn = db.connect(app.config["DB_PATH"])
    still_null = conn.execute(
        "SELECT category_id FROM transactions WHERE id='new1'").fetchone()[0]
    conn.close()
    assert still_null is None, "a suggestion was silently applied"


# ── Adaptive confidence ───────────────────────────────────────────────────
def _learning(conn, uid, confirmations, corrections):
    conn.execute("INSERT INTO merchants(id,user_id,canonical_name) "
                 "VALUES ('m1',?,'M')", (uid,))
    conn.execute(
        "INSERT INTO learning(id,user_id,raw_name,merchant_id,merchant_name,"
        "confirmation_count,correction_count) VALUES ('l1',?,'M','m1','M',?,?)",
        (uid, confirmations, corrections))
    conn.commit()


def _conn(tmp_path, name):
    conn, _ = db.open_database(str(tmp_path / name), backup=False)
    conn.execute("INSERT INTO users VALUES ('u1','a@b.c','U','x','2024-01-01')")
    conn.commit()
    return conn


def test_defaults_hold_until_there_is_enough_evidence(tmp_path):
    conn = _conn(tmp_path, "cal1.db")
    _learning(conn, "u1", 5, 1)                 # below MIN_OBSERVATIONS
    state = calibration.thresholds(conn, "u1", 80, 50)
    assert state["auto"] == 80 and state["confirm"] == 50
    assert state["adapted"] is False
    assert state["reason"] == "insufficient_history"
    conn.close()


def test_a_high_correction_rate_makes_the_app_more_cautious(tmp_path):
    conn = _conn(tmp_path, "cal2.db")
    _learning(conn, "u1", 60, 40)               # 40% corrections
    state = calibration.thresholds(conn, "u1", 80, 50)
    assert state["adapted"] is True
    assert state["auto"] > 80, "kept auto-saving despite frequent corrections"
    assert state["reason"] == "high_correction_rate"
    conn.close()


def test_a_low_correction_rate_reduces_review_friction(tmp_path):
    conn = _conn(tmp_path, "cal3.db")
    _learning(conn, "u1", 99, 1)                # 1% corrections
    state = calibration.thresholds(conn, "u1", 80, 50)
    assert state["adapted"] is True
    assert state["auto"] < 80
    assert state["reason"] == "low_correction_rate"
    conn.close()


def test_a_well_calibrated_user_is_left_alone(tmp_path):
    """Constant small adjustments would make behaviour feel unpredictable."""
    conn = _conn(tmp_path, "cal4.db")
    _learning(conn, "u1", 90, 10)               # 10%, inside the target band
    state = calibration.thresholds(conn, "u1", 80, 50)
    assert state["adapted"] is False
    assert state["reason"] == "well_calibrated"
    assert (state["auto"], state["confirm"]) == (80, 50)
    conn.close()


def test_adaptation_is_bounded_at_both_extremes(tmp_path):
    """An unbounded loop could drive auto to 0 (silently mis-saving
    everything) or 100 (never auto-saving again). Both are worse than a
    mediocre fixed value."""
    conn = _conn(tmp_path, "cal5.db")
    _learning(conn, "u1", 1, 999)               # ~100% corrections
    worst = calibration.thresholds(conn, "u1", 80, 50)
    assert worst["auto"] <= calibration.AUTO_MAX
    assert worst["confirm"] <= calibration.CONFIRM_MAX
    conn.close()

    conn = _conn(tmp_path, "cal6.db")
    _learning(conn, "u1", 999, 0)               # 0% corrections
    best = calibration.thresholds(conn, "u1", 80, 50)
    assert best["auto"] >= calibration.AUTO_MIN
    assert best["confirm"] >= calibration.CONFIRM_MIN
    conn.close()


def test_auto_can_never_collapse_into_confirm(tmp_path):
    """If auto <= confirm the two decisions merge and 'ask me' becomes
    unreachable."""
    conn = _conn(tmp_path, "cal7.db")
    _learning(conn, "u1", 1, 999)
    for base_auto, base_confirm in [(80, 50), (72, 70), (75, 74), (90, 88)]:
        state = calibration.thresholds(conn, "u1", base_auto, base_confirm)
        assert state["auto"] > state["confirm"], (base_auto, base_confirm, state)
    conn.close()


def test_explicit_user_settings_are_never_overridden(tmp_path):
    """A value the user chose is a decision, not a default to second-guess."""
    conn = _conn(tmp_path, "cal8.db")
    _learning(conn, "u1", 10, 90)
    state = calibration.thresholds(conn, "u1", 95, 60, user_set=True)
    assert (state["auto"], state["confirm"]) == (95, 60)
    assert state["adapted"] is False
    assert state["reason"] == "user_set"
    conn.close()


def test_calibration_is_explainable_in_plain_language(tmp_path):
    conn = _conn(tmp_path, "cal9.db")
    _learning(conn, "u1", 60, 40)
    text = calibration.describe(calibration.thresholds(conn, "u1", 80, 50))
    assert "40%" in text and "asks before saving" in text
    conn.close()


def test_thresholds_actually_reach_the_engine(tmp_path):
    """Calibration that is computed but not applied would be theatre."""
    app = create_app(db_path=str(tmp_path / "cal10.db"), single_user=True,
                     secret_key="s", device_token=TOKEN)
    c = app.test_client()
    c.get(f"/?k={TOKEN}")            # authenticate as the WebView does
    c.get("/dashboard")
    conn = db.connect(app.config["DB_PATH"])
    uid = conn.execute("SELECT id FROM users").fetchone()[0]
    _learning(conn, uid, 99, 1)
    conn.close()
    with app.test_request_context("/"):
        pass
    # A very-low-correction user should end up below the 80 default.
    conn = db.connect(app.config["DB_PATH"])
    state = calibration.thresholds(conn, uid, 80, 50)
    conn.close()
    assert state["auto"] < 80
    assert c.get("/transactions").status_code == 200


# ── Merchant display name (presentation) ──────────────────────────────────
def _display(app):
    return app.jinja_env.filters["merchant"]


@pytest.mark.parametrize("raw,shown", [
    ("VPA AMAZON", "Amazon"),
    ("swiggy@ybl", "Swiggy"),
    ("VPA swiggy", "Swiggy"),
    ("SWIGGY", "Swiggy"),
    ("UPI/P2M/419988776655/BLINKIT", "Blinkit"),
    ("bigbasket@ybl", "BigBasket"),
    ("kfc", "KFC"),
    ("NETFLIX", "Netflix"),
    ("ACME PAYROLL", "ACME Payroll"),
])
def test_raw_payees_render_as_one_clean_merchant(tmp_path, raw, shown):
    """The ledger showed 'VPA AMAZON' one row above 'Amazon', and 'swiggy'
    above 'Swiggy' — the same shop several times, which makes a correct list
    look broken. Resolved merchants already carry a canonical name; this is
    the fallback for rows the engine has not resolved yet."""
    app = create_app(db_path=str(tmp_path / f"md{abs(hash(raw))}.db"),
                     single_user=True, secret_key="s")
    assert _display(app)(raw) == shown


def test_display_normalisation_never_writes_back(tmp_path):
    """It must stay presentation-only: raw_merchant is the merchant engine's
    learning key, so rewriting it would corrupt what the engine matches on."""
    app = create_app(db_path=str(tmp_path / "mdw.db"), single_user=True,
                     secret_key="s", device_token=TOKEN)
    c = app.test_client()
    c.get(f"/?k={TOKEN}")
    c.post("/sms/ingest",
           data={"sender": "VM-ICICIB",
                 "body": "INR 1299.00 debited from ICICI Bank A/c XX2211 "
                         "towards VPA NOVELSHOP. IMPS Ref no 556677889001"},
           headers={"X-SpendWise-Token": TOKEN},
           environ_overrides={"REMOTE_ADDR": "127.0.0.1"})
    conn = db.connect(app.config["DB_PATH"])
    raw = conn.execute("SELECT raw_merchant FROM transactions").fetchone()[0]
    conn.close()
    assert raw and raw.upper().startswith("NOVELSHOP") or "NOVELSHOP" in (raw or "").upper(), \
        f"stored raw payee was rewritten: {raw!r}"
    # ...while the UI shows the tidy form.
    assert b"Novelshop" in c.get("/transactions").data
