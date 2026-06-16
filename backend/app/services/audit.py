"""Audit logging helper."""
from __future__ import annotations

from typing import Optional

from sqlalchemy.orm import Session

from app import models


def record(db: Session, *, action: str, user_id=None, entity_type: Optional[str] = None,
           entity_id: Optional[str] = None, ip: Optional[str] = None,
           user_agent: Optional[str] = None, meta: Optional[dict] = None) -> None:
    log = models.AuditLog(
        user_id=user_id, action=action, entity_type=entity_type,
        entity_id=str(entity_id) if entity_id is not None else None,
        ip=ip, user_agent=(user_agent or "")[:300], meta=meta or {},
    )
    db.add(log)
