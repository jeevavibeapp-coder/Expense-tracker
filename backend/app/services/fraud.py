"""Fraud / anomaly detection over a user's own transaction history.

Detectors (all data-driven, no thresholds invented out of thin air):
  - duplicate            : same amount+merchant within a short window
  - high_value_outlier   : amount far above the user's expense distribution
  - abnormal_spend       : today's spend far above the trailing daily average
  - unusual_merchant     : a never-before-seen merchant at high value
"""
from __future__ import annotations

import datetime as dt
import statistics
from decimal import Decimal
from typing import List

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app import models

DUPLICATE_WINDOW_MIN = 10
HIGH_VALUE_SIGMA = 3.0
ABNORMAL_DAY_FACTOR = 3.0
MIN_HISTORY = 8


def _f(v) -> float:
    return float(v) if v is not None else 0.0


def _expense_amounts(db: Session, user_id, exclude_id=None) -> List[float]:
    q = select(models.Transaction.amount).where(
        models.Transaction.user_id == user_id,
        models.Transaction.type == models.TX_EXPENSE,
        models.Transaction.is_deleted.is_(False),
    )
    if exclude_id is not None:
        q = q.where(models.Transaction.id != exclude_id)
    return [_f(a) for (a,) in db.execute(q).all()]


def _add_alert(db: Session, *, user_id, tx, alert_type: str, severity: str,
               message: str, details: dict) -> models.FraudAlert:
    alert = models.FraudAlert(
        user_id=user_id, transaction_id=tx.id, alert_type=alert_type,
        severity=severity, message=message, details=details,
    )
    db.add(alert)
    db.flush()
    return alert


def evaluate_transaction(db: Session, *, user, tx: models.Transaction,
                         high_value_limit: float = 0.0) -> List[models.FraudAlert]:
    """Run all detectors for a newly created/updated expense transaction."""
    alerts: List[models.FraudAlert] = []
    if tx.type != models.TX_EXPENSE or tx.is_deleted:
        return alerts

    amount = _f(tx.amount)
    name = tx.merchant_name or tx.raw_merchant

    # 1. Duplicate within a short window (same amount + merchant).
    window_start = tx.occurred_at - dt.timedelta(minutes=DUPLICATE_WINDOW_MIN)
    dup_q = select(func.count()).select_from(models.Transaction).where(
        models.Transaction.user_id == user.id,
        models.Transaction.id != tx.id,
        models.Transaction.is_deleted.is_(False),
        models.Transaction.amount == tx.amount,
        models.Transaction.occurred_at >= window_start,
        models.Transaction.occurred_at <= tx.occurred_at + dt.timedelta(minutes=DUPLICATE_WINDOW_MIN),
    )
    if name:
        dup_q = dup_q.where(models.Transaction.merchant_name == tx.merchant_name)
    if db.execute(dup_q).scalar_one() > 0:
        alerts.append(_add_alert(
            db, user_id=user.id, tx=tx, alert_type=models.FRAUD_DUPLICATE,
            severity=models.SEVERITY_MEDIUM,
            message=f"Possible duplicate charge of {amount:.2f}"
                    + (f" at {name}" if name else ""),
            details={"amount": amount, "merchant": name,
                     "window_minutes": DUPLICATE_WINDOW_MIN},
        ))

    history = _expense_amounts(db, user.id, exclude_id=tx.id)

    # 2. High-value outlier vs the user's own distribution.
    user_threshold = _f(high_value_limit)
    if len(history) >= MIN_HISTORY:
        mean = statistics.fmean(history)
        stdev = statistics.pstdev(history) or 1.0
        z = (amount - mean) / stdev
        if z >= HIGH_VALUE_SIGMA and amount > mean:
            alerts.append(_add_alert(
                db, user_id=user.id, tx=tx, alert_type=models.FRAUD_HIGH_VALUE,
                severity=models.SEVERITY_HIGH,
                message=f"High-value transaction {amount:.2f} (≈{z:.1f}σ above your average)",
                details={"amount": amount, "mean": round(mean, 2),
                         "stdev": round(stdev, 2), "sigma": round(z, 2)},
            ))
    if user_threshold and amount >= user_threshold:
        alerts.append(_add_alert(
            db, user_id=user.id, tx=tx, alert_type=models.FRAUD_HIGH_VALUE,
            severity=models.SEVERITY_HIGH,
            message=f"Transaction {amount:.2f} exceeds your high-value limit {user_threshold:.2f}",
            details={"amount": amount, "limit": user_threshold},
        ))

    # 3. Abnormal daily spend.
    day_start = tx.occurred_at.replace(hour=0, minute=0, second=0, microsecond=0)
    today_total = _f(db.execute(
        select(func.coalesce(func.sum(models.Transaction.amount), 0)).where(
            models.Transaction.user_id == user.id,
            models.Transaction.type == models.TX_EXPENSE,
            models.Transaction.is_deleted.is_(False),
            models.Transaction.occurred_at >= day_start,
            models.Transaction.occurred_at < day_start + dt.timedelta(days=1),
        )
    ).scalar_one())
    if len(history) >= MIN_HISTORY:
        daily_avg = _daily_average(db, user.id, before=day_start)
        if daily_avg > 0 and today_total >= daily_avg * ABNORMAL_DAY_FACTOR:
            alerts.append(_add_alert(
                db, user_id=user.id, tx=tx, alert_type=models.FRAUD_ABNORMAL,
                severity=models.SEVERITY_MEDIUM,
                message=f"Today's spend {today_total:.2f} is well above your daily average {daily_avg:.2f}",
                details={"today": round(today_total, 2), "daily_average": round(daily_avg, 2)},
            ))

    # 4. Unusual (first-seen) merchant at a high value.
    if name and len(history) >= MIN_HISTORY:
        seen = db.execute(
            select(func.count()).select_from(models.Transaction).where(
                models.Transaction.user_id == user.id,
                models.Transaction.id != tx.id,
                models.Transaction.is_deleted.is_(False),
                models.Transaction.merchant_name == tx.merchant_name,
            )
        ).scalar_one()
        mean = statistics.fmean(history)
        if seen == 0 and amount > mean * 2:
            alerts.append(_add_alert(
                db, user_id=user.id, tx=tx, alert_type=models.FRAUD_UNUSUAL_MERCHANT,
                severity=models.SEVERITY_LOW,
                message=f"First transaction with {name} and it's a large amount ({amount:.2f})",
                details={"merchant": name, "amount": amount, "average": round(mean, 2)},
            ))
    return alerts


def _daily_average(db: Session, user_id, *, before: dt.datetime) -> float:
    rows = db.execute(
        select(func.date(models.Transaction.occurred_at),
               func.sum(models.Transaction.amount)).where(
            models.Transaction.user_id == user_id,
            models.Transaction.type == models.TX_EXPENSE,
            models.Transaction.is_deleted.is_(False),
            models.Transaction.occurred_at < before,
        ).group_by(func.date(models.Transaction.occurred_at))
    ).all()
    totals = [_f(t) for (_, t) in rows]
    return statistics.fmean(totals) if totals else 0.0
