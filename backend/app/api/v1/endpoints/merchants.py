"""Merchant resolution, confirmation and learning insight endpoints."""
from __future__ import annotations

import uuid
from typing import List

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from app import models
from app.core.database import get_db
from app.core.deps import get_current_user
from app.repositories import TransactionRepository
from app.schemas import (
    ConfidenceBreakdown, ConfirmMerchantRequest, MerchantCandidate,
    MerchantLearningOut, MerchantMappingOut, ResolveRequest, ResolveResult,
    TransactionOut,
)
from app.services import transaction_service
from app.services.merchant_engine import resolve
from app.services.transaction_service import TransactionError

router = APIRouter(prefix="/merchants", tags=["merchants"])


def _settings(db: Session, user_id):
    s = db.execute(
        select(models.Setting).where(models.Setting.user_id == user_id)
    ).scalar_one_or_none()
    return (s.auto_save_threshold, s.confirm_threshold) if s else (80, 50)


@router.post("/resolve", response_model=ResolveResult)
def resolve_merchant(body: ResolveRequest, user: models.User = Depends(get_current_user),
                     db: Session = Depends(get_db)):
    auto, confirm = _settings(db, user.id)
    res = resolve(db, user_id=user.id, raw_name=body.raw_name, amount=body.amount,
                  category_id=body.category_id, occurred_at=body.occurred_at,
                  auto_threshold=auto, confirm_threshold=confirm)

    def to_candidate(c) -> MerchantCandidate:
        return MerchantCandidate(
            merchant_id=c.merchant_id, merchant_name=c.merchant_name,
            confidence=c.confidence,
            breakdown=ConfidenceBreakdown(**c.breakdown.as_dict()),
        )

    return ResolveResult(
        raw_name=res.raw_name, decision=res.decision,
        best=to_candidate(res.best) if res.best else None,
        candidates=[to_candidate(c) for c in res.candidates],
    )


@router.post("/confirm", response_model=TransactionOut)
def confirm_merchant(body: ConfirmMerchantRequest,
                     user: models.User = Depends(get_current_user),
                     db: Session = Depends(get_db)):
    repo = TransactionRepository(db)
    tx = repo.get_for_user(user.id, body.transaction_id)
    if not tx:
        raise HTTPException(404, "Transaction not found")
    try:
        tx = transaction_service.confirm_merchant(
            db, user=user, tx=tx, merchant_name=body.merchant_name,
            category_id=body.category_id,
        )
    except TransactionError as exc:
        raise HTTPException(exc.status_code, exc.message)
    db.commit()
    db.refresh(tx)
    return TransactionOut.model_validate(tx)


@router.get("/learning", response_model=List[MerchantLearningOut])
def list_learning(user: models.User = Depends(get_current_user),
                  db: Session = Depends(get_db)):
    rows = db.execute(
        select(models.MerchantLearning).where(
            models.MerchantLearning.user_id == user.id
        ).order_by(models.MerchantLearning.confidence.desc())
    ).scalars().all()
    return [MerchantLearningOut.model_validate(r) for r in rows]


@router.get("/mappings", response_model=List[MerchantMappingOut])
def list_mappings(user: models.User = Depends(get_current_user),
                  db: Session = Depends(get_db)):
    rows = db.execute(
        select(models.MerchantMapping).where(
            models.MerchantMapping.user_id == user.id
        ).order_by(models.MerchantMapping.canonical_name)
    ).scalars().all()
    return [MerchantMappingOut.model_validate(r) for r in rows]
