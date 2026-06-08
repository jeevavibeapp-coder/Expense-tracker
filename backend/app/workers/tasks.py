"""Background tasks.

These run inside a Celery worker with their own DB session. They are written to
be idempotent and side-effect free unless given real input.
"""
from __future__ import annotations

import uuid

from app.core.database import SessionLocal
from app.workers.celery_app import celery_app


@celery_app.task(name="fraud.rescan_user")
def rescan_user_fraud(user_id: str) -> dict:
    """Re-run fraud detection across a user's recent transactions.

    Useful after bulk imports or threshold changes. Returns a small summary.
    """
    from app import models
    from app.services import fraud
    from sqlalchemy import select

    db = SessionLocal()
    try:
        user = db.get(models.User, uuid.UUID(user_id))
        if user is None:
            return {"user_id": user_id, "status": "not_found"}
        setting = db.execute(
            select(models.Setting).where(models.Setting.user_id == user.id)
        ).scalar_one_or_none()
        limit = float(setting.high_value_amount or 0) if setting else 0.0
        recent = db.execute(
            select(models.Transaction).where(
                models.Transaction.user_id == user.id,
                models.Transaction.is_deleted.is_(False),
                models.Transaction.type == models.TX_EXPENSE,
            ).order_by(models.Transaction.occurred_at.desc()).limit(200)
        ).scalars().all()
        created = 0
        for tx in recent:
            created += len(fraud.evaluate_transaction(
                db, user=user, tx=tx, high_value_limit=limit))
        db.commit()
        return {"user_id": user_id, "scanned": len(recent), "alerts_created": created}
    finally:
        db.close()
