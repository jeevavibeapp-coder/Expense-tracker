"""Dashboard & analytics aggregation (all from the user's real data)."""
from __future__ import annotations

import datetime as dt
from decimal import Decimal
from typing import List

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app import models


def _now() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc)


def _sum_expense(db: Session, user_id, *, since: dt.datetime) -> Decimal:
    val = db.execute(
        select(func.coalesce(func.sum(models.Transaction.amount), 0)).where(
            models.Transaction.user_id == user_id,
            models.Transaction.type == models.TX_EXPENSE,
            models.Transaction.is_deleted.is_(False),
            models.Transaction.occurred_at >= since,
        )
    ).scalar_one()
    return Decimal(val).quantize(Decimal("0.01"))


def _totals(db: Session, user_id):
    income = db.execute(
        select(func.coalesce(func.sum(models.Transaction.amount), 0)).where(
            models.Transaction.user_id == user_id,
            models.Transaction.type == models.TX_INCOME,
            models.Transaction.is_deleted.is_(False),
        )
    ).scalar_one()
    expense = db.execute(
        select(func.coalesce(func.sum(models.Transaction.amount), 0)).where(
            models.Transaction.user_id == user_id,
            models.Transaction.type == models.TX_EXPENSE,
            models.Transaction.is_deleted.is_(False),
        )
    ).scalar_one()
    return Decimal(income).quantize(Decimal("0.01")), Decimal(expense).quantize(Decimal("0.01"))


def _breakdown_by(db: Session, user_id, column) -> List[dict]:
    rows = db.execute(
        select(column, func.sum(models.Transaction.amount)).where(
            models.Transaction.user_id == user_id,
            models.Transaction.type == models.TX_EXPENSE,
            models.Transaction.is_deleted.is_(False),
            column.isnot(None),
        ).group_by(column).order_by(func.sum(models.Transaction.amount).desc())
    ).all()
    return [{"name": str(name), "value": Decimal(val).quantize(Decimal("0.01"))}
            for name, val in rows if name]


def _category_breakdown(db: Session, user_id) -> List[dict]:
    rows = db.execute(
        select(models.Category.name, func.sum(models.Transaction.amount)).join(
            models.Category, models.Category.id == models.Transaction.category_id
        ).where(
            models.Transaction.user_id == user_id,
            models.Transaction.type == models.TX_EXPENSE,
            models.Transaction.is_deleted.is_(False),
        ).group_by(models.Category.name).order_by(func.sum(models.Transaction.amount).desc())
    ).all()
    return [{"name": name, "value": Decimal(val).quantize(Decimal("0.01"))}
            for name, val in rows]


def _trend(db: Session, user_id, months: int = 6) -> List[dict]:
    start = (_now().replace(day=1, hour=0, minute=0, second=0, microsecond=0)
             - dt.timedelta(days=31 * (months - 1))).replace(day=1)
    rows = db.execute(
        select(models.Transaction.occurred_at, models.Transaction.type,
               models.Transaction.amount).where(
            models.Transaction.user_id == user_id,
            models.Transaction.is_deleted.is_(False),
            models.Transaction.occurred_at >= start,
        )
    ).all()
    buckets: dict[str, dict] = {}
    for occurred_at, type_, amount in rows:
        key = occurred_at.strftime("%Y-%m")
        b = buckets.setdefault(key, {"income": Decimal("0"), "expense": Decimal("0")})
        b[type_] = b[type_] + Decimal(amount)
    out = []
    for key in sorted(buckets):
        b = buckets[key]
        out.append({"period": key,
                    "income": b["income"].quantize(Decimal("0.01")),
                    "expense": b["expense"].quantize(Decimal("0.01"))})
    return out


def _insights(daily: Decimal, weekly: Decimal, monthly: Decimal,
              top_merchants: List[dict], category_breakdown: List[dict],
              pending: int, fraud_open: int) -> List[str]:
    out: List[str] = []
    if monthly > 0:
        out.append(f"You've spent {monthly:.0f} so far this month.")
    if category_breakdown:
        top = category_breakdown[0]
        out.append(f"Your biggest category is {top['name']} ({top['value']:.0f}).")
    if top_merchants:
        tm = top_merchants[0]
        out.append(f"You spend the most at {tm['name']} ({tm['value']:.0f}).")
    if weekly > 0 and monthly > 0:
        weekly_share = (weekly / monthly) * 100 if monthly else 0
        if weekly_share > 40:
            out.append("A large share of this month's spending happened in the last 7 days.")
    if pending:
        out.append(f"{pending} transaction(s) need merchant confirmation.")
    if fraud_open:
        out.append(f"{fraud_open} fraud alert(s) need your attention.")
    if not out:
        out.append("Add your first transaction to start seeing insights.")
    return out


def build_dashboard(db: Session, user: models.User) -> dict:
    now = _now()
    day_start = now.replace(hour=0, minute=0, second=0, microsecond=0)
    week_start = day_start - dt.timedelta(days=day_start.weekday())
    month_start = day_start.replace(day=1)

    daily = _sum_expense(db, user.id, since=day_start)
    weekly = _sum_expense(db, user.id, since=week_start)
    monthly = _sum_expense(db, user.id, since=month_start)
    income, expense = _totals(db, user.id)

    top_merchants = _breakdown_by(db, user.id, models.Transaction.merchant_name)[:10]
    merchant_breakdown = top_merchants
    category_breakdown = _category_breakdown(db, user.id)

    pending = db.execute(
        select(func.count()).select_from(models.Transaction).where(
            models.Transaction.user_id == user.id,
            models.Transaction.is_deleted.is_(False),
            models.Transaction.status.in_([models.TX_PENDING, models.TX_REVIEW]),
        )
    ).scalar_one()
    fraud_open = db.execute(
        select(func.count()).select_from(models.FraudAlert).where(
            models.FraudAlert.user_id == user.id,
            models.FraudAlert.status == models.FRAUD_OPEN,
        )
    ).scalar_one()

    settings_row = db.execute(
        select(models.Setting).where(models.Setting.user_id == user.id)
    ).scalar_one_or_none()
    currency = settings_row.currency if settings_row else "INR"

    return {
        "currency": currency,
        "daily_spend": daily,
        "weekly_spend": weekly,
        "monthly_spend": monthly,
        "total_income": income,
        "total_expense": expense,
        "balance": (income - expense).quantize(Decimal("0.01")),
        "top_merchants": top_merchants,
        "category_breakdown": category_breakdown,
        "merchant_breakdown": merchant_breakdown,
        "trend": _trend(db, user.id),
        "insights": _insights(daily, weekly, monthly, top_merchants,
                              category_breakdown, pending, fraud_open),
        "open_fraud_alerts": fraud_open,
        "pending_confirmations": pending,
    }
