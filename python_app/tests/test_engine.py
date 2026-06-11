"""Unit tests for the merchant resolution + confidence + learning engine."""
from __future__ import annotations

import datetime as dt

from spendwise import auth, engine


def _user(conn):
    return auth.ensure_local_user(conn)


def test_normalize_strips_upi_noise():
    assert engine.normalize_merchant("UPI/RAJESH KUMAR/9876543210@okhdfc") == "RAJESH KUMAR"
    assert engine.normalize_merchant("  starbucks  ") == "STARBUCKS"
    assert engine.normalize_merchant("P2M-ZOMATO-123456") == "ZOMATO"
    assert engine.normalize_merchant("") == ""


def test_unknown_raw_requires_manual(conn):
    uid = _user(conn)
    res = engine.resolve(conn, user_id=uid, raw_name="RAJESH")
    assert res["best"] is None
    assert res["decision"] == engine.DECISION_MANUAL


def test_learning_drives_autosave(conn):
    uid = _user(conn)
    occurred = dt.datetime(2024, 1, 1, 9, 0, tzinfo=dt.timezone.utc)
    for _ in range(4):
        engine.record_confirmation(conn, user_id=uid, raw_name="RAJESH",
                                   merchant_name="Starbucks", amount=250.0,
                                   occurred_at=occurred)
    res = engine.resolve(conn, user_id=uid, raw_name="RAJESH", amount=250.0,
                         occurred_at=occurred)
    assert res["best"]["merchant_name"] == "Starbucks"
    assert res["best"]["confidence"] >= 80
    assert res["decision"] == engine.DECISION_AUTO
    b = res["best"]["breakdown"]
    assert b["past_mapping"] <= 40 and b["amount_pattern"] <= 20
    assert b["category_pattern"] <= 15 and b["correction_history"] <= 15 and b["time_pattern"] <= 10


def test_single_observation_needs_confirmation(conn):
    uid = _user(conn)
    occurred = dt.datetime(2024, 1, 1, 13, 0, tzinfo=dt.timezone.utc)
    engine.record_confirmation(conn, user_id=uid, raw_name="SURESH",
                               merchant_name="A2B", amount=120.0, occurred_at=occurred)
    res = engine.resolve(conn, user_id=uid, raw_name="SURESH", amount=120.0,
                         occurred_at=occurred)
    assert res["best"]["merchant_name"] == "A2B"
    assert res["best"]["confidence"] < 80


def test_correction_reinforces_new_and_penalises_old(conn):
    uid = _user(conn)
    engine.record_confirmation(conn, user_id=uid, raw_name="KUMAR",
                               merchant_name="Tea Shop", amount=30.0)
    for _ in range(3):
        engine.record_confirmation(conn, user_id=uid, raw_name="KUMAR",
                                   merchant_name="KFC", amount=400.0, is_correction=True)
    res = engine.resolve(conn, user_id=uid, raw_name="KUMAR", amount=400.0)
    assert res["best"]["merchant_name"] == "KFC"
    assert res["candidates"][0]["merchant_name"] == "KFC"


def test_amount_pattern_influences_score(conn):
    uid = _user(conn)
    for amt in (100.0, 110.0, 90.0, 105.0):
        engine.record_confirmation(conn, user_id=uid, raw_name="SHOP",
                                   merchant_name="Tea Shop", amount=amt)
    close = engine.resolve(conn, user_id=uid, raw_name="SHOP", amount=100.0)
    far = engine.resolve(conn, user_id=uid, raw_name="SHOP", amount=5000.0)
    assert close["best"]["breakdown"]["amount_pattern"] > far["best"]["breakdown"]["amount_pattern"]
