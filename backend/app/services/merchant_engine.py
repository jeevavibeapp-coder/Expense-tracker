"""Smart Merchant Resolution + Confidence Scoring engine.

Resolves a raw UPI/SMS name (e.g. "RAJESH") into the real business the user
actually transacts with (e.g. "Starbucks"), using only data the user has
generated: historical mappings, corrections, amount/category/time patterns.

Confidence weights (per product spec, total 100):
    Past Mapping ........ 40
    Amount Pattern ...... 20
    Category Pattern .... 15
    Correction History .. 15
    Time Pattern ........ 10

Decision thresholds are configurable per user (defaults 80 / 50):
    score >= auto_save_threshold      -> auto_saved
    confirm_threshold <= score < auto -> confirmation_required
    score < confirm_threshold         -> manual_required
"""
from __future__ import annotations

import datetime as dt
import re
import uuid
from dataclasses import dataclass
from decimal import Decimal
from typing import List, Optional

from sqlalchemy import select
from sqlalchemy.orm import Session

from app import models

# Weight ceilings for each signal.
W_PAST_MAPPING = 40.0
W_AMOUNT = 20.0
W_CATEGORY = 15.0
W_CORRECTION = 15.0
W_TIME = 10.0

DECISION_AUTO = "auto_saved"
DECISION_CONFIRM = "confirmation_required"
DECISION_MANUAL = "manual_required"

# Tokens that are noise in UPI/bank merchant strings.
_NOISE_TOKENS = {
    "UPI", "VPA", "P2M", "P2A", "POS", "NEFT", "IMPS", "RTGS", "ACH",
    "PVT", "LTD", "LIMITED", "PRIVATE", "AND", "THE",
}
_NOISE_RE = re.compile(r"[^A-Z0-9& ]+")
_LONG_DIGITS_RE = re.compile(r"\b\d{4,}\b")


def normalize_merchant(raw: str) -> str:
    """Canonicalise a raw merchant string for matching.

    Uppercases, strips bank/UPI noise tokens and long reference numbers, and
    collapses whitespace. Returns "" for empty input.
    """
    if not raw:
        return ""
    s = raw.upper().strip()
    # Drop everything after an '@' (VPA handles like name@okhdfcbank).
    s = s.split("@", 1)[0]
    s = s.replace("/", " ").replace("-", " ").replace("_", " ").replace(".", " ")
    s = _LONG_DIGITS_RE.sub(" ", s)
    s = _NOISE_RE.sub(" ", s)
    tokens = [t for t in s.split() if t and t not in _NOISE_TOKENS]
    return " ".join(tokens).strip()


@dataclass
class Breakdown:
    past_mapping: float
    amount_pattern: float
    category_pattern: float
    correction_history: float
    time_pattern: float

    @property
    def total(self) -> int:
        raw = (self.past_mapping + self.amount_pattern + self.category_pattern
               + self.correction_history + self.time_pattern)
        return int(round(max(0.0, min(100.0, raw))))

    def as_dict(self) -> dict:
        return {
            "past_mapping": round(self.past_mapping, 2),
            "amount_pattern": round(self.amount_pattern, 2),
            "category_pattern": round(self.category_pattern, 2),
            "correction_history": round(self.correction_history, 2),
            "time_pattern": round(self.time_pattern, 2),
            "total": self.total,
        }


@dataclass
class Candidate:
    merchant_id: Optional[uuid.UUID]
    merchant_name: str
    breakdown: Breakdown

    @property
    def confidence(self) -> int:
        return self.breakdown.total


def _f(value) -> float:
    if value is None:
        return 0.0
    if isinstance(value, Decimal):
        return float(value)
    return float(value)


# Number of consistent confirmations at which a mapping is fully trusted.
_FULL_TRUST = 5.0


def _score_past_mapping(learning: models.MerchantLearning) -> float:
    # Trust grows with confirmations (and, weakly, observations) and is eroded
    # by corrections; it reaches the full 40 once a mapping is well established.
    strength = (learning.confirmation_count + 0.5 * learning.sample_count
                - learning.correction_count)
    return W_PAST_MAPPING * max(0.0, min(1.0, strength / _FULL_TRUST))


def _score_amount(learning: models.MerchantLearning, amount: Optional[Decimal]) -> float:
    if amount is None or learning.sample_count <= 0:
        return 0.0
    a = _f(amount)
    avg = _f(learning.avg_amount)
    lo, hi = _f(learning.amount_min), _f(learning.amount_max)
    # Tolerance derived from the learned spread (or 25% of the average).
    tolerance = max(avg * 0.25, (hi - lo) / 2.0, 1.0)
    closeness = max(0.0, 1.0 - abs(a - avg) / (tolerance * 2.0))
    if lo <= a <= hi:
        closeness = max(closeness, 0.6)
    return W_AMOUNT * closeness


def _score_category(learning: models.MerchantLearning,
                    category_id: Optional[uuid.UUID]) -> float:
    if learning.category_id is None:
        return 0.0
    if category_id is None:
        return W_CATEGORY * 0.5  # learned category exists, none supplied
    return W_CATEGORY if str(learning.category_id) == str(category_id) else 0.0


def _score_correction(learning: models.MerchantLearning) -> float:
    denom = learning.confirmation_count + learning.correction_count + 1
    ratio = (learning.confirmation_count + 1) / denom
    return W_CORRECTION * ratio


def _score_time(learning: models.MerchantLearning,
                occurred_at: Optional[dt.datetime]) -> float:
    hist = learning.hour_histogram or []
    if occurred_at is None or not hist or sum(hist) <= 0:
        return 0.0
    hour = occurred_at.hour % 24
    peak = max(hist) or 1
    return W_TIME * (hist[hour] / peak if hour < len(hist) else 0.0)


def score_candidate(learning: models.MerchantLearning, *, amount: Optional[Decimal],
                    category_id: Optional[uuid.UUID],
                    occurred_at: Optional[dt.datetime]) -> Breakdown:
    return Breakdown(
        past_mapping=_score_past_mapping(learning),
        amount_pattern=_score_amount(learning, amount),
        category_pattern=_score_category(learning, category_id),
        correction_history=_score_correction(learning),
        time_pattern=_score_time(learning, occurred_at),
    )


def decide(score: int, *, auto_threshold: int, confirm_threshold: int) -> str:
    if score >= auto_threshold:
        return DECISION_AUTO
    if score >= confirm_threshold:
        return DECISION_CONFIRM
    return DECISION_MANUAL


@dataclass
class Resolution:
    raw_name: str
    decision: str
    best: Optional[Candidate]
    candidates: List[Candidate]


def resolve(db: Session, *, user_id, raw_name: str, amount: Optional[Decimal] = None,
            category_id: Optional[uuid.UUID] = None,
            occurred_at: Optional[dt.datetime] = None,
            auto_threshold: int = 80, confirm_threshold: int = 50) -> Resolution:
    """Resolve a raw merchant name to ranked real-merchant candidates."""
    normalized = normalize_merchant(raw_name)
    if not normalized:
        return Resolution(raw_name=raw_name, decision=DECISION_MANUAL, best=None, candidates=[])

    rows = db.execute(
        select(models.MerchantLearning).where(
            models.MerchantLearning.user_id == user_id,
            models.MerchantLearning.raw_name == normalized,
        )
    ).scalars().all()

    candidates: List[Candidate] = []
    for row in rows:
        bd = score_candidate(row, amount=amount, category_id=category_id,
                             occurred_at=occurred_at)
        candidates.append(Candidate(merchant_id=row.merchant_id,
                                    merchant_name=row.merchant_name, breakdown=bd))

    candidates.sort(key=lambda c: c.confidence, reverse=True)
    best = candidates[0] if candidates else None
    decision = (decide(best.confidence, auto_threshold=auto_threshold,
                       confirm_threshold=confirm_threshold)
                if best else DECISION_MANUAL)
    return Resolution(raw_name=normalized, decision=decision, best=best,
                      candidates=candidates)
