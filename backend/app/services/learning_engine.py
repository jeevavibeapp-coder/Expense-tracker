"""Learning engine: persists merchant knowledge and improves over time.

Every confirmed/corrected transaction feeds back here, updating the
raw-name -> real-merchant mapping with running statistics (amount distribution,
hour-of-day histogram, confirmation/correction counts) that the resolution
engine reads on the next prediction.
"""
from __future__ import annotations

import datetime as dt
import uuid
from decimal import Decimal
from typing import Optional

from sqlalchemy import select
from sqlalchemy.orm import Session

from app import models
from app.services.merchant_engine import (
    W_CATEGORY, W_CORRECTION, W_PAST_MAPPING, normalize_merchant,
)


def get_or_create_merchant(db: Session, *, user_id, canonical_name: str,
                           category_id: Optional[uuid.UUID] = None
                           ) -> models.MerchantMapping:
    name = canonical_name.strip()
    existing = db.execute(
        select(models.MerchantMapping).where(
            models.MerchantMapping.user_id == user_id,
            models.MerchantMapping.canonical_name == name,
        )
    ).scalar_one_or_none()
    if existing:
        if category_id and existing.category_id != category_id:
            existing.category_id = category_id
        return existing
    merchant = models.MerchantMapping(
        user_id=user_id, canonical_name=name, category_id=category_id
    )
    db.add(merchant)
    db.flush()
    return merchant


_FULL_TRUST = 5.0


def _baseline_confidence(row: models.MerchantLearning) -> int:
    """Transaction-independent strength of a mapping, for display/auto-apply."""
    strength = (row.confirmation_count + 0.5 * row.sample_count
                - row.correction_count)
    past = W_PAST_MAPPING * max(0.0, min(1.0, strength / _FULL_TRUST))
    denom = row.confirmation_count + row.correction_count + 1
    correction = W_CORRECTION * ((row.confirmation_count + 1) / denom)
    category = W_CATEGORY if row.category_id is not None else 0.0
    return int(round(max(0.0, min(100.0, past + correction + category))))


def record_confirmation(db: Session, *, user_id, raw_name: str, merchant_name: str,
                        amount: Optional[Decimal] = None,
                        category_id: Optional[uuid.UUID] = None,
                        occurred_at: Optional[dt.datetime] = None,
                        is_correction: bool = False) -> models.MerchantLearning:
    """Record that `raw_name` maps to `merchant_name` and update statistics.

    Set ``is_correction=True`` when the user overrode a previous prediction so
    competing mappings for the same raw name are penalised.
    """
    normalized = normalize_merchant(raw_name)
    merchant = get_or_create_merchant(
        db, user_id=user_id, canonical_name=merchant_name, category_id=category_id
    )

    row = db.execute(
        select(models.MerchantLearning).where(
            models.MerchantLearning.user_id == user_id,
            models.MerchantLearning.raw_name == normalized,
            models.MerchantLearning.merchant_id == merchant.id,
        )
    ).scalar_one_or_none()

    if row is None:
        row = models.MerchantLearning(
            user_id=user_id, raw_name=normalized, merchant_id=merchant.id,
            merchant_name=merchant.canonical_name, category_id=category_id,
            hour_histogram=[0] * 24, sample_count=0,
            avg_amount=Decimal("0"), amount_min=Decimal("0"), amount_max=Decimal("0"),
        )
        db.add(row)
        db.flush()

    # Running amount statistics.
    if amount is not None:
        amt = Decimal(amount)
        n = row.sample_count
        prev_avg = Decimal(row.avg_amount or 0)
        new_avg = (prev_avg * n + amt) / (n + 1)
        row.avg_amount = new_avg.quantize(Decimal("0.01"))
        row.amount_min = amt if n == 0 else min(Decimal(row.amount_min), amt)
        row.amount_max = amt if n == 0 else max(Decimal(row.amount_max), amt)
        row.sample_count = n + 1

    # Hour-of-day histogram.
    if occurred_at is not None:
        hist = list(row.hour_histogram or [0] * 24)
        if len(hist) < 24:
            hist = (hist + [0] * 24)[:24]
        hist[occurred_at.hour % 24] += 1
        row.hour_histogram = hist

    if category_id is not None:
        row.category_id = category_id
        row.merchant_name = merchant.canonical_name

    # The chosen merchant is the correct answer either way, so it is confirmed.
    row.confirmation_count += 1
    if is_correction:
        # A correction means a *different* candidate was previously wrong:
        # penalise the other mappings learned for this raw name.
        others = db.execute(
            select(models.MerchantLearning).where(
                models.MerchantLearning.user_id == user_id,
                models.MerchantLearning.raw_name == normalized,
                models.MerchantLearning.merchant_id != merchant.id,
            )
        ).scalars().all()
        for other in others:
            other.correction_count += 1
            other.confidence = _baseline_confidence(other)

    row.last_seen_at = occurred_at or dt.datetime.now(dt.timezone.utc)
    row.confidence = _baseline_confidence(row)
    db.flush()
    return row
