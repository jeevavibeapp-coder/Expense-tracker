"""Dashboard & analytics endpoints."""
from __future__ import annotations

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app import models
from app.core.database import get_db
from app.core.deps import get_current_user
from app.schemas import DashboardOut, NamedValue, TrendPoint
from app.services import dashboard_service

router = APIRouter(tags=["dashboard"])


@router.get("/dashboard", response_model=DashboardOut)
def dashboard(user: models.User = Depends(get_current_user),
              db: Session = Depends(get_db)):
    data = dashboard_service.build_dashboard(db, user)
    return DashboardOut(
        currency=data["currency"], daily_spend=data["daily_spend"],
        weekly_spend=data["weekly_spend"], monthly_spend=data["monthly_spend"],
        total_income=data["total_income"], total_expense=data["total_expense"],
        balance=data["balance"],
        top_merchants=[NamedValue(**m) for m in data["top_merchants"]],
        category_breakdown=[NamedValue(**c) for c in data["category_breakdown"]],
        merchant_breakdown=[NamedValue(**m) for m in data["merchant_breakdown"]],
        trend=[TrendPoint(**t) for t in data["trend"]],
        insights=data["insights"], open_fraud_alerts=data["open_fraud_alerts"],
        pending_confirmations=data["pending_confirmations"],
    )


@router.get("/analytics/trend", response_model=list[TrendPoint])
def trend(months: int = Query(6, ge=1, le=24),
          user: models.User = Depends(get_current_user),
          db: Session = Depends(get_db)):
    from app.services.dashboard_service import _trend
    return [TrendPoint(**t) for t in _trend(db, user.id, months=months)]
