"""Transaction orchestration: create / update / delete / confirm / offline sync.

Ties together merchant resolution, the confidence decision, the learning
engine and fraud detection into one consistent flow.
"""
from __future__ import annotations

import datetime as dt
import uuid
from dataclasses import dataclass, field
from decimal import Decimal
from typing import List, Optional

from sqlalchemy.orm import Session

from app import models
from app.repositories import CategoryRepository, TransactionRepository
from app.schemas import TransactionCreate, TransactionUpdate
from app.services import fraud, learning_engine
from app.services.merchant_engine import (
    DECISION_AUTO, DECISION_CONFIRM, DECISION_MANUAL, resolve,
)


class TransactionError(Exception):
    def __init__(self, message: str, status_code: int = 400):
        super().__init__(message)
        self.message = message
        self.status_code = status_code


@dataclass
class CreateResult:
    transaction: models.Transaction
    decision: str
    confidence: Optional[int] = None
    resolved_merchant: Optional[str] = None
    breakdown: Optional[dict] = None
    suggestion_id: Optional[uuid.UUID] = None
    fraud_alert_ids: List[uuid.UUID] = field(default_factory=list)
    is_duplicate: bool = False


def _now() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc)


def _settings(db: Session, user: models.User) -> models.Setting:
    from sqlalchemy import select
    s = db.execute(
        select(models.Setting).where(models.Setting.user_id == user.id)
    ).scalar_one_or_none()
    if s is None:
        s = models.Setting(user_id=user.id)
        db.add(s)
        db.flush()
    return s


def _validate_category(db: Session, user: models.User,
                       category_id: Optional[uuid.UUID]) -> Optional[uuid.UUID]:
    if category_id is None:
        return None
    cat = CategoryRepository(db).get_for_user(user.id, category_id)
    if cat is None:
        raise TransactionError("Category not found", 404)
    return cat.id


def create_transaction(db: Session, *, user: models.User, payload: TransactionCreate
                       ) -> CreateResult:
    repo = TransactionRepository(db)

    # Idempotent offline creation: a repeated client_id returns the original.
    if payload.client_id:
        existing = repo.get_by_client_id(user.id, payload.client_id)
        if existing:
            return CreateResult(transaction=existing, decision="none",
                                confidence=existing.confidence,
                                resolved_merchant=existing.merchant_name,
                                is_duplicate=True)

    category_id = _validate_category(db, user, payload.category_id)
    occurred_at = payload.occurred_at or _now()
    raw_merchant = (payload.raw_merchant or payload.merchant_name or "").strip() or None

    tx = models.Transaction(
        user_id=user.id, amount=Decimal(payload.amount), type=payload.type,
        category_id=category_id, raw_merchant=raw_merchant,
        notes=payload.notes, reference_number=payload.reference_number,
        occurred_at=occurred_at, source=payload.source, client_id=payload.client_id,
        status=models.TX_CONFIRMED,
    )

    settings_row = _settings(db, user)
    decision = "none"
    breakdown = None
    suggestion_id = None

    user_provided_merchant = bool(payload.merchant_name and payload.merchant_name.strip())

    if user_provided_merchant:
        # Ground truth from the user: confirm + learn.
        merchant = learning_engine.get_or_create_merchant(
            db, user_id=user.id, canonical_name=payload.merchant_name.strip(),
            category_id=category_id,
        )
        tx.merchant_id = merchant.id
        tx.merchant_name = merchant.canonical_name
        tx.confidence = 100
        tx.status = models.TX_CONFIRMED
        decision = DECISION_AUTO
        if raw_merchant:
            learning_engine.record_confirmation(
                db, user_id=user.id, raw_name=raw_merchant,
                merchant_name=merchant.canonical_name, amount=Decimal(payload.amount),
                category_id=category_id, occurred_at=occurred_at, is_correction=False,
            )
    elif payload.resolve_merchant and raw_merchant:
        res = resolve(
            db, user_id=user.id, raw_name=raw_merchant, amount=Decimal(payload.amount),
            category_id=category_id, occurred_at=occurred_at,
            auto_threshold=settings_row.auto_save_threshold,
            confirm_threshold=settings_row.confirm_threshold,
        )
        decision = res.decision
        if res.best:
            breakdown = res.best.breakdown.as_dict()
            tx.confidence = res.best.confidence
            if res.decision == DECISION_AUTO:
                tx.merchant_id = res.best.merchant_id
                tx.merchant_name = res.best.merchant_name
                tx.status = models.TX_CONFIRMED
                learning_engine.record_confirmation(
                    db, user_id=user.id, raw_name=raw_merchant,
                    merchant_name=res.best.merchant_name, amount=Decimal(payload.amount),
                    category_id=category_id, occurred_at=occurred_at, is_correction=False,
                )
            elif res.decision == DECISION_CONFIRM:
                tx.merchant_id = res.best.merchant_id
                tx.merchant_name = res.best.merchant_name
                tx.status = models.TX_PENDING
            else:
                tx.status = models.TX_REVIEW
        else:
            tx.status = models.TX_REVIEW
            decision = DECISION_MANUAL
    else:
        decision = "none"

    repo.add(tx)

    # Create a suggestion row for anything the user must act on.
    if decision in (DECISION_CONFIRM, DECISION_MANUAL) and raw_merchant:
        sug = models.MerchantSuggestion(
            user_id=user.id, transaction_id=tx.id, raw_name=raw_merchant,
            suggested_merchant_id=(tx.merchant_id if decision == DECISION_CONFIRM else None),
            suggested_merchant_name=(tx.merchant_name if decision == DECISION_CONFIRM else None),
            confidence=tx.confidence or 0, breakdown=breakdown or {},
        )
        db.add(sug)
        db.flush()
        suggestion_id = sug.id

    alerts = fraud.evaluate_transaction(
        db, user=user, tx=tx,
        high_value_limit=float(settings_row.high_value_amount or 0),
    )

    return CreateResult(
        transaction=tx, decision=decision, confidence=tx.confidence,
        resolved_merchant=tx.merchant_name, breakdown=breakdown,
        suggestion_id=suggestion_id, fraud_alert_ids=[a.id for a in alerts],
    )


def update_transaction(db: Session, *, user: models.User, tx: models.Transaction,
                       payload: TransactionUpdate) -> models.Transaction:
    if payload.amount is not None:
        tx.amount = Decimal(payload.amount)
    if payload.type is not None:
        tx.type = payload.type
    if payload.category_id is not None:
        tx.category_id = _validate_category(db, user, payload.category_id)
    if payload.notes is not None:
        tx.notes = payload.notes
    if payload.occurred_at is not None:
        tx.occurred_at = payload.occurred_at
    if payload.merchant_name is not None and payload.merchant_name.strip():
        merchant = learning_engine.get_or_create_merchant(
            db, user_id=user.id, canonical_name=payload.merchant_name.strip(),
            category_id=tx.category_id,
        )
        is_correction = bool(tx.merchant_name and tx.merchant_name != merchant.canonical_name)
        tx.merchant_id = merchant.id
        tx.merchant_name = merchant.canonical_name
        tx.status = models.TX_CONFIRMED
        tx.confidence = 100
        if tx.raw_merchant:
            learning_engine.record_confirmation(
                db, user_id=user.id, raw_name=tx.raw_merchant,
                merchant_name=merchant.canonical_name, amount=Decimal(tx.amount),
                category_id=tx.category_id, occurred_at=tx.occurred_at,
                is_correction=is_correction,
            )
    db.flush()
    return tx


def confirm_merchant(db: Session, *, user: models.User, tx: models.Transaction,
                     merchant_name: str, category_id: Optional[uuid.UUID] = None
                     ) -> models.Transaction:
    """User confirms/corrects the real merchant for a pending transaction."""
    category_id = _validate_category(db, user, category_id) if category_id else tx.category_id
    is_correction = bool(tx.merchant_name and tx.merchant_name.strip().lower()
                         != merchant_name.strip().lower())
    merchant = learning_engine.get_or_create_merchant(
        db, user_id=user.id, canonical_name=merchant_name.strip(), category_id=category_id,
    )
    tx.merchant_id = merchant.id
    tx.merchant_name = merchant.canonical_name
    tx.category_id = category_id
    tx.status = models.TX_CONFIRMED
    tx.confidence = 100
    if tx.raw_merchant:
        learning_engine.record_confirmation(
            db, user_id=user.id, raw_name=tx.raw_merchant,
            merchant_name=merchant.canonical_name, amount=Decimal(tx.amount),
            category_id=category_id, occurred_at=tx.occurred_at,
            is_correction=is_correction,
        )
    # Resolve any open suggestions for this transaction.
    from sqlalchemy import select
    for sug in db.execute(
        select(models.MerchantSuggestion).where(
            models.MerchantSuggestion.transaction_id == tx.id,
            models.MerchantSuggestion.status == models.SUGGESTION_PENDING,
        )
    ).scalars().all():
        accepted = (sug.suggested_merchant_name or "").strip().lower() == \
            merchant.canonical_name.lower()
        sug.status = models.SUGGESTION_ACCEPTED if accepted else models.SUGGESTION_REJECTED
        sug.resolved_at = _now()
    db.flush()
    return tx


def soft_delete(db: Session, *, tx: models.Transaction) -> None:
    tx.is_deleted = True
    db.flush()
