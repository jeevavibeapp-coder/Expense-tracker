"""Fraud alert endpoints."""
from __future__ import annotations

import uuid
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from app import models
from app.core.database import get_db
from app.core.deps import get_current_user
from app.repositories import FraudRepository
from app.schemas import FraudAlertOut, FraudStatusUpdate

router = APIRouter(prefix="/fraud", tags=["fraud"])


@router.get("/alerts", response_model=List[FraudAlertOut])
def list_alerts(status: Optional[str] = Query(None, pattern="^(open|dismissed|resolved)$"),
                user: models.User = Depends(get_current_user),
                db: Session = Depends(get_db)):
    return [FraudAlertOut.model_validate(a)
            for a in FraudRepository(db).list(user.id, status)]


@router.patch("/alerts/{alert_id}", response_model=FraudAlertOut)
def update_alert(alert_id: uuid.UUID, body: FraudStatusUpdate,
                 user: models.User = Depends(get_current_user),
                 db: Session = Depends(get_db)):
    import datetime as dt
    repo = FraudRepository(db)
    alert = repo.get_for_user(user.id, alert_id)
    if not alert:
        raise HTTPException(404, "Alert not found")
    alert.status = body.status
    if body.status in (models.FRAUD_DISMISSED, models.FRAUD_RESOLVED):
        alert.resolved_at = dt.datetime.now(dt.timezone.utc)
    db.commit()
    db.refresh(alert)
    return FraudAlertOut.model_validate(alert)
