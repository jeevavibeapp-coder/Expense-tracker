"""User settings endpoints."""
from __future__ import annotations

from decimal import Decimal

from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from app import models
from app.core.database import get_db
from app.core.deps import get_current_user
from app.schemas import SettingsOut, SettingsUpdate

router = APIRouter(prefix="/settings", tags=["settings"])


def _get_or_create(db: Session, user_id) -> models.Setting:
    s = db.execute(
        select(models.Setting).where(models.Setting.user_id == user_id)
    ).scalar_one_or_none()
    if s is None:
        s = models.Setting(user_id=user_id)
        db.add(s)
        db.flush()
    return s


@router.get("", response_model=SettingsOut)
def get_settings(user: models.User = Depends(get_current_user),
                 db: Session = Depends(get_db)):
    s = _get_or_create(db, user.id)
    db.commit()
    return SettingsOut.model_validate(s)


@router.patch("", response_model=SettingsOut)
def update_settings(body: SettingsUpdate, user: models.User = Depends(get_current_user),
                    db: Session = Depends(get_db)):
    s = _get_or_create(db, user.id)
    if body.currency is not None:
        s.currency = body.currency
    if body.theme is not None:
        s.theme = body.theme
    if body.auto_save_threshold is not None:
        s.auto_save_threshold = body.auto_save_threshold
    if body.confirm_threshold is not None:
        s.confirm_threshold = body.confirm_threshold
    if body.high_value_amount is not None:
        s.high_value_amount = Decimal(body.high_value_amount)
    if body.data is not None:
        s.data = body.data
    db.commit()
    db.refresh(s)
    return SettingsOut.model_validate(s)
