"""Unit tests for the merchant resolution + confidence scoring engine."""
from __future__ import annotations

import datetime as dt
import uuid
from decimal import Decimal

from app import models
from app.services import learning_engine
from app.services.merchant_engine import (
    DECISION_AUTO, DECISION_CONFIRM, DECISION_MANUAL, normalize_merchant, resolve,
)


def _user(db) -> models.User:
    u = models.User(email="m@example.com", full_name="M", hashed_password="x")
    db.add(u)
    db.flush()
    return u


def test_normalize_strips_upi_noise():
    assert normalize_merchant("UPI/RAJESH KUMAR/9876543210@okhdfc") == "RAJESH KUMAR"
    assert normalize_merchant("  starbucks   ") == "STARBUCKS"
    assert normalize_merchant("P2M-ZOMATO-123456") == "ZOMATO"
    assert normalize_merchant("") == ""


def test_unknown_raw_name_requires_manual(db_session):
    user = _user(db_session)
    res = resolve(db_session, user_id=user.id, raw_name="RAJESH")
    assert res.best is None
    assert res.decision == DECISION_MANUAL


def test_learning_then_high_confidence_autosave(db_session):
    user = _user(db_session)
    occurred = dt.datetime(2024, 1, 1, 9, 0, tzinfo=dt.timezone.utc)
    # The user repeatedly confirms RAJESH == Starbucks around the same amount/time.
    for _ in range(4):
        learning_engine.record_confirmation(
            db_session, user_id=user.id, raw_name="RAJESH", merchant_name="Starbucks",
            amount=Decimal("250"), occurred_at=occurred, is_correction=False,
        )
    db_session.flush()
    res = resolve(db_session, user_id=user.id, raw_name="RAJESH",
                  amount=Decimal("250"), occurred_at=occurred)
    assert res.best is not None
    assert res.best.merchant_name == "Starbucks"
    assert res.best.confidence >= 80
    assert res.decision == DECISION_AUTO
    # Breakdown weights never exceed their ceilings.
    bd = res.best.breakdown
    assert bd.past_mapping <= 40 and bd.amount_pattern <= 20
    assert bd.category_pattern <= 15 and bd.correction_history <= 15
    assert bd.time_pattern <= 10


def test_single_observation_needs_confirmation(db_session):
    user = _user(db_session)
    learning_engine.record_confirmation(
        db_session, user_id=user.id, raw_name="SURESH", merchant_name="A2B",
        amount=Decimal("120"), occurred_at=dt.datetime(2024, 1, 1, 13, 0, tzinfo=dt.timezone.utc),
        is_correction=False,
    )
    db_session.flush()
    res = resolve(db_session, user_id=user.id, raw_name="SURESH", amount=Decimal("120"),
                  occurred_at=dt.datetime(2024, 1, 1, 13, 0, tzinfo=dt.timezone.utc))
    # One confirmation: known but not yet auto-save strength.
    assert res.best.merchant_name == "A2B"
    assert res.decision in (DECISION_CONFIRM, DECISION_MANUAL)
    assert res.best.confidence < 80


def test_correction_penalises_wrong_candidate(db_session):
    user = _user(db_session)
    # First learned wrong, then corrected to the right merchant.
    learning_engine.record_confirmation(
        db_session, user_id=user.id, raw_name="KUMAR", merchant_name="Tea Shop",
        amount=Decimal("30"), is_correction=False,
    )
    for _ in range(3):
        learning_engine.record_confirmation(
            db_session, user_id=user.id, raw_name="KUMAR", merchant_name="KFC",
            amount=Decimal("400"), is_correction=True,
        )
    db_session.flush()
    res = resolve(db_session, user_id=user.id, raw_name="KUMAR", amount=Decimal("400"))
    assert res.best.merchant_name == "KFC"
    # The corrected-away candidate should rank below the winner.
    names = [c.merchant_name for c in res.candidates]
    assert names[0] == "KFC"


def test_amount_pattern_influences_score(db_session):
    user = _user(db_session)
    for amt in ("100", "110", "90", "105"):
        learning_engine.record_confirmation(
            db_session, user_id=user.id, raw_name="SHOP", merchant_name="Tea Shop",
            amount=Decimal(amt), is_correction=False,
        )
    db_session.flush()
    close = resolve(db_session, user_id=user.id, raw_name="SHOP", amount=Decimal("100"))
    far = resolve(db_session, user_id=user.id, raw_name="SHOP", amount=Decimal("5000"))
    assert close.best.breakdown.amount_pattern > far.best.breakdown.amount_pattern
