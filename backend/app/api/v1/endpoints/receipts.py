"""Receipt upload / preview / delete."""
from __future__ import annotations

import uuid
from typing import List, Optional

from fastapi import (
    APIRouter, Depends, File, Form, HTTPException, Response, UploadFile,
)
from sqlalchemy.orm import Session

from app import models
from app.core.config import settings
from app.core.database import get_db
from app.core.deps import get_current_user
from app.repositories import ReceiptRepository, TransactionRepository
from app.schemas import MessageOut, ReceiptOut
from app.services.storage import ALLOWED_CONTENT_TYPES, StorageError, get_storage

router = APIRouter(prefix="/receipts", tags=["receipts"])


@router.get("", response_model=List[ReceiptOut])
def list_receipts(transaction_id: Optional[uuid.UUID] = None,
                  user: models.User = Depends(get_current_user),
                  db: Session = Depends(get_db)):
    return [ReceiptOut.model_validate(r)
            for r in ReceiptRepository(db).list(user.id, transaction_id)]


@router.post("", response_model=ReceiptOut, status_code=201)
async def upload_receipt(file: UploadFile = File(...),
                         transaction_id: Optional[uuid.UUID] = Form(None),
                         user: models.User = Depends(get_current_user),
                         db: Session = Depends(get_db)):
    if file.content_type not in ALLOWED_CONTENT_TYPES:
        raise HTTPException(415, f"Unsupported file type: {file.content_type}")
    data = await file.read()
    if not data:
        raise HTTPException(400, "Empty file")
    if len(data) > settings.max_upload_mb * 1024 * 1024:
        raise HTTPException(413, f"File exceeds {settings.max_upload_mb} MB limit")

    if transaction_id is not None:
        tx = TransactionRepository(db).get_for_user(user.id, transaction_id)
        if not tx:
            raise HTTPException(404, "Transaction not found")

    try:
        key = get_storage().save(user_id=str(user.id), data=data,
                                 filename=file.filename or "receipt",
                                 content_type=file.content_type)
    except StorageError as exc:
        raise HTTPException(502, f"Storage error: {exc}")

    receipt = models.Receipt(
        user_id=user.id, transaction_id=transaction_id, storage_key=key,
        filename=(file.filename or "receipt")[:255], content_type=file.content_type,
        size_bytes=len(data),
    )
    db.add(receipt)
    db.commit()
    db.refresh(receipt)
    return ReceiptOut.model_validate(receipt)


@router.get("/{receipt_id}/content")
def get_receipt_content(receipt_id: uuid.UUID,
                        user: models.User = Depends(get_current_user),
                        db: Session = Depends(get_db)):
    receipt = ReceiptRepository(db).get_for_user(user.id, receipt_id)
    if not receipt:
        raise HTTPException(404, "Receipt not found")
    try:
        data = get_storage().load(receipt.storage_key)
    except StorageError:
        raise HTTPException(404, "Receipt file missing")
    return Response(content=data, media_type=receipt.content_type)


@router.delete("/{receipt_id}", response_model=MessageOut)
def delete_receipt(receipt_id: uuid.UUID, user: models.User = Depends(get_current_user),
                   db: Session = Depends(get_db)):
    repo = ReceiptRepository(db)
    receipt = repo.get_for_user(user.id, receipt_id)
    if not receipt:
        raise HTTPException(404, "Receipt not found")
    try:
        get_storage().delete(receipt.storage_key)
    except StorageError:
        pass
    repo.delete(receipt)
    db.commit()
    return MessageOut(message="Receipt deleted")
