"""Server-rendered web UI (Jinja2 + HTMX) for the Expense Tracker.

These routes call the same service layer as the JSON API, so the smart merchant
resolution, learning, fraud detection and analytics are all exercised through
the browser. Forms work without JavaScript; HTMX progressively enhances them.
"""
from __future__ import annotations

import datetime as dt
import uuid
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, Depends, Form, Request
from fastapi.responses import HTMLResponse, RedirectResponse
from fastapi.templating import Jinja2Templates
from sqlalchemy import select
from sqlalchemy.orm import Session

from app import models
from app.core.database import get_db
from app.repositories import (
    CategoryRepository, FraudRepository, TransactionRepository,
)
from app.schemas import TransactionCreate
from app.services import auth_service, dashboard_service, transaction_service
from app.services.auth_service import AuthError
from app.services.merchant_engine import resolve
from app.services.sms_parser import parse_sms
from app.web import deps

BASE_DIR = Path(__file__).resolve().parent
templates = Jinja2Templates(directory=str(BASE_DIR / "templates"))
templates.env.globals["now"] = lambda: dt.datetime.now(dt.timezone.utc)

router = APIRouter(include_in_schema=False)


def _is_htmx(request: Request) -> bool:
    return request.headers.get("HX-Request") == "true"


def _categories(db: Session, user: models.User):
    return CategoryRepository(db).list(user.id)


def _settings(db: Session, user: models.User) -> models.Setting:
    s = db.execute(
        select(models.Setting).where(models.Setting.user_id == user.id)
    ).scalar_one_or_none()
    if s is None:
        s = models.Setting(user_id=user.id)
        db.add(s)
        db.commit()
        db.refresh(s)
    return s


def _ctx(request: Request, user: Optional[models.User], **extra) -> dict:
    base = {"request": request, "user": user, "app_name": "SpendWise"}
    base.update(extra)
    return base


# ── Public landing / auth ────────────────────────────────────────────────────
@router.get("/", response_class=HTMLResponse)
def index(user: Optional[models.User] = Depends(deps.optional_user)):
    return RedirectResponse("/dashboard" if user else "/login", status_code=303)


@router.get("/login", response_class=HTMLResponse)
def login_page(request: Request, user: Optional[models.User] = Depends(deps.optional_user)):
    if user:
        return RedirectResponse("/dashboard", status_code=303)
    return templates.TemplateResponse("login.html", _ctx(request, None))


@router.post("/login", response_class=HTMLResponse)
def login_submit(request: Request, email: str = Form(...), password: str = Form(...),
                 db: Session = Depends(get_db)):
    try:
        user, tokens = auth_service.login(db, email=email, password=password)
        db.commit()
    except AuthError as exc:
        return templates.TemplateResponse(
            "login.html", _ctx(request, None, error=exc.message, email=email),
            status_code=exc.status_code,
        )
    resp = RedirectResponse("/dashboard", status_code=303)
    deps.set_auth_cookies(resp, tokens)
    return resp


@router.get("/signup", response_class=HTMLResponse)
def signup_page(request: Request, user: Optional[models.User] = Depends(deps.optional_user)):
    if user:
        return RedirectResponse("/dashboard", status_code=303)
    return templates.TemplateResponse("signup.html", _ctx(request, None))


@router.post("/signup", response_class=HTMLResponse)
def signup_submit(request: Request, full_name: str = Form(...), email: str = Form(...),
                  password: str = Form(...), db: Session = Depends(get_db)):
    try:
        user, tokens = auth_service.signup(
            db, email=email, full_name=full_name, password=password)
        db.commit()
    except AuthError as exc:
        return templates.TemplateResponse(
            "signup.html",
            _ctx(request, None, error=exc.message, email=email, full_name=full_name),
            status_code=exc.status_code,
        )
    resp = RedirectResponse("/dashboard", status_code=303)
    deps.set_auth_cookies(resp, tokens)
    return resp


@router.post("/logout")
def logout(refresh_token: Optional[str] = None, db: Session = Depends(get_db)):
    resp = RedirectResponse("/login", status_code=303)
    deps.clear_auth_cookies(resp)
    return resp


# ── Dashboard ────────────────────────────────────────────────────────────────
@router.get("/dashboard", response_class=HTMLResponse)
def dashboard(request: Request, user: models.User = Depends(deps.require_user),
              db: Session = Depends(get_db)):
    data = dashboard_service.build_dashboard(db, user)
    return templates.TemplateResponse(
        "dashboard.html", _ctx(request, user, d=data, active="dashboard"))


# ── Transactions ─────────────────────────────────────────────────────────────
def _parse_amount(raw: str) -> Optional[Decimal]:
    try:
        return Decimal(raw).quantize(Decimal("0.01"))
    except (InvalidOperation, TypeError):
        return None


@router.get("/transactions", response_class=HTMLResponse)
def transactions_page(request: Request, q: Optional[str] = None,
                      user: models.User = Depends(deps.require_user),
                      db: Session = Depends(get_db)):
    rows, total = TransactionRepository(db).search(user.id, q=q, page=1, page_size=100)
    return templates.TemplateResponse("transactions.html", _ctx(
        request, user, transactions=rows, total=total, q=q or "",
        categories=_categories(db, user), active="transactions"))


@router.post("/transactions", response_class=HTMLResponse)
def transactions_create(request: Request, amount: str = Form(...),
                        type: str = Form("expense"), merchant: str = Form(""),
                        category_id: str = Form(""), notes: str = Form(""),
                        occurred_at: str = Form(""),
                        user: models.User = Depends(deps.require_user),
                        db: Session = Depends(get_db)):
    amt = _parse_amount(amount)
    if amt is None:
        return _tx_list_response(request, user, db, error="Enter a valid amount.")
    cat_id = uuid.UUID(category_id) if category_id else None
    occ = None
    if occurred_at:
        try:
            occ = dt.datetime.fromisoformat(occurred_at).replace(tzinfo=dt.timezone.utc)
        except ValueError:
            occ = None
    payload = TransactionCreate(
        amount=amt, type=type, category_id=cat_id,
        merchant_name=merchant.strip() or None, notes=notes.strip() or None,
        occurred_at=occ, source="manual",
    )
    try:
        transaction_service.create_transaction(db, user=user, payload=payload)
        db.commit()
    except transaction_service.TransactionError as exc:
        return _tx_list_response(request, user, db, error=exc.message)
    return _tx_list_response(request, user, db, flash="Transaction added.")


@router.post("/transactions/resolve", response_class=HTMLResponse)
def transactions_resolve(request: Request, merchant: str = Form(""),
                         amount: str = Form(""),
                         user: models.User = Depends(deps.require_user),
                         db: Session = Depends(get_db)):
    """Live confidence preview as the user types a payee (HTMX)."""
    name = merchant.strip()
    if not name:
        return HTMLResponse("")
    settings_row = _settings(db, user)
    res = resolve(db, user_id=user.id, raw_name=name, amount=_parse_amount(amount),
                  auto_threshold=settings_row.auto_save_threshold,
                  confirm_threshold=settings_row.confirm_threshold)
    best = res.best
    return templates.TemplateResponse("_resolve.html", _ctx(
        request, user, best=best, decision=res.decision,
        breakdown=best.breakdown.as_dict() if best else None))


@router.post("/transactions/{tx_id}/confirm", response_class=HTMLResponse)
def transactions_confirm(request: Request, tx_id: uuid.UUID,
                         merchant: str = Form(...), category_id: str = Form(""),
                         user: models.User = Depends(deps.require_user),
                         db: Session = Depends(get_db)):
    tx = TransactionRepository(db).get_for_user(user.id, tx_id)
    if tx:
        cat_id = uuid.UUID(category_id) if category_id else None
        transaction_service.confirm_merchant(
            db, user=user, tx=tx, merchant_name=merchant.strip(), category_id=cat_id)
        db.commit()
    return _tx_list_response(request, user, db, flash="Merchant confirmed and learned.")


@router.post("/transactions/{tx_id}/delete", response_class=HTMLResponse)
def transactions_delete(request: Request, tx_id: uuid.UUID,
                        user: models.User = Depends(deps.require_user),
                        db: Session = Depends(get_db)):
    tx = TransactionRepository(db).get_for_user(user.id, tx_id)
    if tx:
        transaction_service.soft_delete(db, tx=tx)
        db.commit()
    return _tx_list_response(request, user, db, flash="Transaction deleted.")


def _tx_list_response(request: Request, user: models.User, db: Session, *,
                      flash: str = "", error: str = "") -> HTMLResponse:
    rows, total = TransactionRepository(db).search(user.id, page=1, page_size=100)
    ctx = _ctx(request, user, transactions=rows, total=total,
               categories=_categories(db, user), flash=flash, error=error,
               active="transactions")
    if _is_htmx(request):
        return templates.TemplateResponse("_tx_panel.html", ctx)
    return templates.TemplateResponse("transactions.html", {**ctx, "q": ""})


# ── SMS import (showcases the resolution engine) ─────────────────────────────
@router.get("/import", response_class=HTMLResponse)
def import_page(request: Request, user: models.User = Depends(deps.require_user),
                db: Session = Depends(get_db)):
    return templates.TemplateResponse("import.html", _ctx(
        request, user, categories=_categories(db, user), active="import"))


@router.post("/import/parse", response_class=HTMLResponse)
def import_parse(request: Request, sms: str = Form(""),
                 user: models.User = Depends(deps.require_user),
                 db: Session = Depends(get_db)):
    parsed = parse_sms(sms)
    settings_row = _settings(db, user)
    preview = None
    if parsed.raw_merchant:
        res = resolve(db, user_id=user.id, raw_name=parsed.raw_merchant,
                      amount=parsed.amount, occurred_at=parsed.occurred_at,
                      auto_threshold=settings_row.auto_save_threshold,
                      confirm_threshold=settings_row.confirm_threshold)
        preview = {"best": res.best, "decision": res.decision,
                   "breakdown": res.best.breakdown.as_dict() if res.best else None}
    return templates.TemplateResponse("_import_preview.html", _ctx(
        request, user, parsed=parsed, preview=preview))


@router.post("/import/create", response_class=HTMLResponse)
def import_create(request: Request, amount: str = Form(...), type: str = Form("expense"),
                  raw_merchant: str = Form(""), occurred_at: str = Form(""),
                  reference_number: str = Form(""),
                  user: models.User = Depends(deps.require_user),
                  db: Session = Depends(get_db)):
    amt = _parse_amount(amount)
    if amt is None:
        return HTMLResponse('<p class="error">Could not read the amount.</p>')
    occ = None
    if occurred_at:
        try:
            occ = dt.datetime.fromisoformat(occurred_at).replace(tzinfo=dt.timezone.utc)
        except ValueError:
            occ = None
    payload = TransactionCreate(
        amount=amt, type=type, raw_merchant=raw_merchant.strip() or None,
        reference_number=reference_number.strip() or None, occurred_at=occ,
        source="sms", resolve_merchant=True,
    )
    res = transaction_service.create_transaction(db, user=user, payload=payload)
    db.commit()
    return templates.TemplateResponse("_import_result.html", _ctx(
        request, user, result=res,
        breakdown=res.breakdown))


# ── Fraud ────────────────────────────────────────────────────────────────────
@router.get("/fraud", response_class=HTMLResponse)
def fraud_page(request: Request, user: models.User = Depends(deps.require_user),
               db: Session = Depends(get_db)):
    alerts = FraudRepository(db).list(user.id)
    return templates.TemplateResponse("fraud.html", _ctx(
        request, user, alerts=alerts, active="fraud"))


@router.post("/fraud/{alert_id}/status", response_class=HTMLResponse)
def fraud_update(request: Request, alert_id: uuid.UUID, status: str = Form(...),
                 user: models.User = Depends(deps.require_user),
                 db: Session = Depends(get_db)):
    repo = FraudRepository(db)
    alert = repo.get_for_user(user.id, alert_id)
    if alert:
        alert.status = status if status in ("open", "dismissed", "resolved") else alert.status
        if alert.status != "open":
            alert.resolved_at = dt.datetime.now(dt.timezone.utc)
        db.commit()
    alerts = repo.list(user.id)
    ctx = _ctx(request, user, alerts=alerts, active="fraud")
    if _is_htmx(request):
        return templates.TemplateResponse("_fraud_list.html", ctx)
    return templates.TemplateResponse("fraud.html", ctx)


# ── Settings ─────────────────────────────────────────────────────────────────
@router.get("/settings", response_class=HTMLResponse)
def settings_page(request: Request, user: models.User = Depends(deps.require_user),
                  db: Session = Depends(get_db)):
    return templates.TemplateResponse("settings.html", _ctx(
        request, user, s=_settings(db, user), active="settings"))


@router.post("/settings", response_class=HTMLResponse)
def settings_update(request: Request, currency: str = Form("INR"),
                    theme: str = Form("system"), auto_save_threshold: int = Form(80),
                    confirm_threshold: int = Form(50), high_value_amount: str = Form(""),
                    user: models.User = Depends(deps.require_user),
                    db: Session = Depends(get_db)):
    s = _settings(db, user)
    s.currency = currency[:8] or "INR"
    s.theme = theme if theme in ("system", "light", "dark") else "system"
    s.auto_save_threshold = max(0, min(100, auto_save_threshold))
    s.confirm_threshold = max(0, min(100, confirm_threshold))
    hv = _parse_amount(high_value_amount) if high_value_amount.strip() else None
    s.high_value_amount = hv
    db.commit()
    db.refresh(s)
    return templates.TemplateResponse("settings.html", _ctx(
        request, user, s=s, active="settings", flash="Settings saved."))
