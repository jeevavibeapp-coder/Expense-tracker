"""Transactions: CRUD, search, SMS parsing and offline sync."""
from __future__ import annotations

import datetime as dt
import uuid
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from app import models
from app.core.database import get_db
from app.core.deps import RequestContext, get_current_user, get_request_context
from app.repositories import TransactionRepository
from app.schemas import (
    ConfidenceBreakdown, MessageOut, Page, SMSParseRequest, SMSParseResult,
    SyncRequest, SyncResult, TransactionCreate, TransactionOut, TransactionResult,
    TransactionUpdate,
)
from app.services import audit, transaction_service
from app.services.sms_parser import parse_sms
from app.services.transaction_service import TransactionError

router = APIRouter(prefix="/transactions", tags=["transactions"])


def _to_result(res: transaction_service.CreateResult) -> TransactionResult:
    breakdown = ConfidenceBreakdown(**res.breakdown) if res.breakdown else None
    return TransactionResult(
        transaction=TransactionOut.model_validate(res.transaction),
        resolved_merchant=res.resolved_merchant, confidence=res.confidence,
        decision=res.decision, breakdown=breakdown, suggestion_id=res.suggestion_id,
        fraud_alert_ids=res.fraud_alert_ids,
    )


@router.post("", response_model=TransactionResult, status_code=201)
def create_transaction(body: TransactionCreate,
                       ctx: RequestContext = Depends(get_request_context),
                       user: models.User = Depends(get_current_user),
                       db: Session = Depends(get_db)):
    try:
        res = transaction_service.create_transaction(db, user=user, payload=body)
    except TransactionError as exc:
        raise HTTPException(exc.status_code, exc.message)
    audit.record(db, action="transaction.create", user_id=user.id,
                 entity_type="transaction", entity_id=res.transaction.id,
                 ip=ctx.ip, user_agent=ctx.user_agent,
                 meta={"decision": res.decision, "confidence": res.confidence})
    db.commit()
    db.refresh(res.transaction)
    return _to_result(res)


@router.get("", response_model=Page[TransactionOut])
def list_transactions(
    q: Optional[str] = Query(None, max_length=160),
    category_id: Optional[uuid.UUID] = None,
    type: Optional[str] = Query(None, pattern="^(income|expense)$"),
    status: Optional[str] = Query(None),
    min_amount: Optional[float] = Query(None, ge=0),
    max_amount: Optional[float] = Query(None, ge=0),
    date_from: Optional[dt.datetime] = None,
    date_to: Optional[dt.datetime] = None,
    page: int = Query(1, ge=1),
    page_size: int = Query(50, ge=1, le=200),
    user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    rows, total = TransactionRepository(db).search(
        user.id, q=q, category_id=category_id, type_=type, status=status,
        min_amount=min_amount, max_amount=max_amount,
        date_from=date_from, date_to=date_to, page=page, page_size=page_size,
    )
    return Page[TransactionOut](
        items=[TransactionOut.model_validate(r) for r in rows],
        total=total, page=page, page_size=page_size,
    )


@router.post("/parse-sms", response_model=SMSParseResult)
def parse_sms_endpoint(body: SMSParseRequest,
                       user: models.User = Depends(get_current_user)):
    parsed = parse_sms(body.text)
    return SMSParseResult(
        amount=parsed.amount, type=parsed.type, raw_merchant=parsed.raw_merchant,
        reference_number=parsed.reference_number, occurred_at=parsed.occurred_at,
        matched=parsed.matched,
    )


@router.post("/sync", response_model=SyncResult)
def sync(body: SyncRequest, user: models.User = Depends(get_current_user),
         db: Session = Depends(get_db)):
    """Bidirectional offline sync.

    Client pushes transactions created offline (idempotent via client_id) and
    receives any server-side changes since ``last_synced_at``.
    """
    applied, skipped = 0, 0
    results = []
    for item in body.transactions:
        try:
            res = transaction_service.create_transaction(db, user=user, payload=item)
        except TransactionError:
            skipped += 1
            continue
        if res.is_duplicate:
            skipped += 1
        else:
            applied += 1
        db.flush()
        results.append(_to_result(res))

    server_changes = TransactionRepository(db).changed_since(user.id, body.last_synced_at)
    db.commit()
    return SyncResult(
        applied=applied, skipped_duplicates=skipped, results=results,
        server_changes=[TransactionOut.model_validate(t) for t in server_changes
                        if not t.is_deleted],
        synced_at=dt.datetime.now(dt.timezone.utc),
    )


@router.get("/{transaction_id}", response_model=TransactionOut)
def get_transaction(transaction_id: uuid.UUID,
                    user: models.User = Depends(get_current_user),
                    db: Session = Depends(get_db)):
    tx = TransactionRepository(db).get_for_user(user.id, transaction_id)
    if not tx:
        raise HTTPException(404, "Transaction not found")
    return TransactionOut.model_validate(tx)


@router.patch("/{transaction_id}", response_model=TransactionOut)
def update_transaction(transaction_id: uuid.UUID, body: TransactionUpdate,
                       user: models.User = Depends(get_current_user),
                       db: Session = Depends(get_db)):
    repo = TransactionRepository(db)
    tx = repo.get_for_user(user.id, transaction_id)
    if not tx:
        raise HTTPException(404, "Transaction not found")
    try:
        tx = transaction_service.update_transaction(db, user=user, tx=tx, payload=body)
    except TransactionError as exc:
        raise HTTPException(exc.status_code, exc.message)
    db.commit()
    db.refresh(tx)
    return TransactionOut.model_validate(tx)


@router.delete("/{transaction_id}", response_model=MessageOut)
def delete_transaction(transaction_id: uuid.UUID,
                       ctx: RequestContext = Depends(get_request_context),
                       user: models.User = Depends(get_current_user),
                       db: Session = Depends(get_db)):
    repo = TransactionRepository(db)
    tx = repo.get_for_user(user.id, transaction_id)
    if not tx:
        raise HTTPException(404, "Transaction not found")
    transaction_service.soft_delete(db, tx=tx)
    audit.record(db, action="transaction.delete", user_id=user.id,
                 entity_type="transaction", entity_id=tx.id, ip=ctx.ip,
                 user_agent=ctx.user_agent)
    db.commit()
    return MessageOut(message="Transaction deleted")
