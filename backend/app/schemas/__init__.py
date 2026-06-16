"""Pydantic v2 schemas (API request/response contracts)."""
from __future__ import annotations

import datetime as dt
import uuid
from decimal import Decimal
from typing import Any, Generic, List, Optional, TypeVar

from pydantic import BaseModel, ConfigDict, EmailStr, Field, field_validator

T = TypeVar("T")


class ORMModel(BaseModel):
    model_config = ConfigDict(from_attributes=True)


# ── Auth ─────────────────────────────────────────────────────────────────────
class SignupRequest(BaseModel):
    email: EmailStr
    full_name: str = Field(min_length=1, max_length=120)
    password: str = Field(min_length=8, max_length=128)


class LoginRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=1, max_length=128)


class RefreshRequest(BaseModel):
    refresh_token: str


class ForgotPasswordRequest(BaseModel):
    email: EmailStr


class ResetPasswordRequest(BaseModel):
    token: str
    new_password: str = Field(min_length=8, max_length=128)


class TokenPair(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    expires_in: int


class UserOut(ORMModel):
    id: uuid.UUID
    email: EmailStr
    full_name: str
    role: str
    is_active: bool
    created_at: dt.datetime


class AuthResponse(BaseModel):
    user: UserOut
    tokens: TokenPair


# ── Pagination ───────────────────────────────────────────────────────────────
class Page(BaseModel, Generic[T]):
    items: List[T]
    total: int
    page: int
    page_size: int


# ── Categories ───────────────────────────────────────────────────────────────
class CategoryCreate(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    type: str = Field(default="expense")
    icon: str = Field(default="Tag", max_length=40)
    color: str = Field(default="#6366f1", max_length=16)

    @field_validator("type")
    @classmethod
    def _type(cls, v: str) -> str:
        if v not in ("income", "expense"):
            raise ValueError("type must be 'income' or 'expense'")
        return v


class CategoryUpdate(BaseModel):
    name: Optional[str] = Field(default=None, min_length=1, max_length=80)
    icon: Optional[str] = Field(default=None, max_length=40)
    color: Optional[str] = Field(default=None, max_length=16)
    is_archived: Optional[bool] = None


class CategoryOut(ORMModel):
    id: uuid.UUID
    name: str
    type: str
    icon: str
    color: str
    is_archived: bool


# ── Transactions ─────────────────────────────────────────────────────────────
class TransactionBase(BaseModel):
    amount: Decimal = Field(ge=0, max_digits=14, decimal_places=2)
    type: str = Field(default="expense")
    category_id: Optional[uuid.UUID] = None
    raw_merchant: Optional[str] = Field(default=None, max_length=160)
    merchant_name: Optional[str] = Field(default=None, max_length=160)
    notes: Optional[str] = Field(default=None, max_length=2000)
    reference_number: Optional[str] = Field(default=None, max_length=80)
    occurred_at: Optional[dt.datetime] = None

    @field_validator("type")
    @classmethod
    def _type(cls, v: str) -> str:
        if v not in ("income", "expense"):
            raise ValueError("type must be 'income' or 'expense'")
        return v


class TransactionCreate(TransactionBase):
    # client_id enables idempotent offline creation/sync.
    client_id: Optional[str] = Field(default=None, max_length=64)
    source: str = Field(default="manual")
    # When true the merchant engine resolves the raw name and may set status.
    resolve_merchant: bool = True


class TransactionUpdate(BaseModel):
    amount: Optional[Decimal] = Field(default=None, ge=0, max_digits=14, decimal_places=2)
    type: Optional[str] = None
    category_id: Optional[uuid.UUID] = None
    merchant_name: Optional[str] = Field(default=None, max_length=160)
    notes: Optional[str] = Field(default=None, max_length=2000)
    occurred_at: Optional[dt.datetime] = None

    @field_validator("type")
    @classmethod
    def _type(cls, v: Optional[str]) -> Optional[str]:
        if v is not None and v not in ("income", "expense"):
            raise ValueError("type must be 'income' or 'expense'")
        return v


class TransactionOut(ORMModel):
    id: uuid.UUID
    amount: Decimal
    type: str
    category_id: Optional[uuid.UUID]
    raw_merchant: Optional[str]
    merchant_id: Optional[uuid.UUID]
    merchant_name: Optional[str]
    notes: Optional[str]
    reference_number: Optional[str]
    occurred_at: dt.datetime
    source: str
    confidence: Optional[int]
    status: str
    client_id: Optional[str]
    created_at: dt.datetime
    updated_at: dt.datetime


class ConfidenceBreakdown(BaseModel):
    past_mapping: float
    amount_pattern: float
    category_pattern: float
    correction_history: float
    time_pattern: float
    total: int


class TransactionResult(BaseModel):
    """Returned on create: the transaction plus the engine's decision."""
    transaction: TransactionOut
    resolved_merchant: Optional[str] = None
    confidence: Optional[int] = None
    decision: str  # auto_saved | confirmation_required | manual_required | none
    breakdown: Optional[ConfidenceBreakdown] = None
    suggestion_id: Optional[uuid.UUID] = None
    fraud_alert_ids: List[uuid.UUID] = Field(default_factory=list)


# ── SMS parsing ──────────────────────────────────────────────────────────────
class SMSParseRequest(BaseModel):
    text: str = Field(min_length=1, max_length=2000)


class SMSParseResult(BaseModel):
    amount: Optional[Decimal] = None
    type: str = "expense"
    raw_merchant: Optional[str] = None
    reference_number: Optional[str] = None
    occurred_at: Optional[dt.datetime] = None
    matched: bool = False


# ── Merchant resolution / learning ───────────────────────────────────────────
class ResolveRequest(BaseModel):
    raw_name: str = Field(min_length=1, max_length=160)
    amount: Optional[Decimal] = Field(default=None, ge=0)
    category_id: Optional[uuid.UUID] = None
    occurred_at: Optional[dt.datetime] = None


class MerchantCandidate(BaseModel):
    merchant_id: Optional[uuid.UUID]
    merchant_name: str
    confidence: int
    breakdown: ConfidenceBreakdown


class ResolveResult(BaseModel):
    raw_name: str
    decision: str
    best: Optional[MerchantCandidate] = None
    candidates: List[MerchantCandidate] = Field(default_factory=list)


class ConfirmMerchantRequest(BaseModel):
    transaction_id: uuid.UUID
    merchant_name: str = Field(min_length=1, max_length=160)
    category_id: Optional[uuid.UUID] = None


class MerchantLearningOut(ORMModel):
    id: uuid.UUID
    raw_name: str
    merchant_id: uuid.UUID
    merchant_name: str
    confidence: int
    correction_count: int
    confirmation_count: int
    sample_count: int
    avg_amount: Decimal
    last_seen_at: dt.datetime


class MerchantMappingOut(ORMModel):
    id: uuid.UUID
    canonical_name: str
    category_id: Optional[uuid.UUID]


# ── Receipts ─────────────────────────────────────────────────────────────────
class ReceiptOut(ORMModel):
    id: uuid.UUID
    transaction_id: Optional[uuid.UUID]
    filename: str
    content_type: str
    size_bytes: int
    created_at: dt.datetime


# ── Fraud ────────────────────────────────────────────────────────────────────
class FraudAlertOut(ORMModel):
    id: uuid.UUID
    transaction_id: Optional[uuid.UUID]
    alert_type: str
    severity: str
    message: str
    details: dict
    status: str
    created_at: dt.datetime


class FraudStatusUpdate(BaseModel):
    status: str

    @field_validator("status")
    @classmethod
    def _status(cls, v: str) -> str:
        if v not in ("open", "dismissed", "resolved"):
            raise ValueError("invalid status")
        return v


# ── Settings ─────────────────────────────────────────────────────────────────
class SettingsOut(ORMModel):
    currency: str
    theme: str
    auto_save_threshold: int
    confirm_threshold: int
    high_value_amount: Optional[Decimal]
    data: dict


class SettingsUpdate(BaseModel):
    currency: Optional[str] = Field(default=None, max_length=8)
    theme: Optional[str] = None
    auto_save_threshold: Optional[int] = Field(default=None, ge=0, le=100)
    confirm_threshold: Optional[int] = Field(default=None, ge=0, le=100)
    high_value_amount: Optional[Decimal] = Field(default=None, ge=0)
    data: Optional[dict] = None

    @field_validator("theme")
    @classmethod
    def _theme(cls, v: Optional[str]) -> Optional[str]:
        if v is not None and v not in ("system", "light", "dark"):
            raise ValueError("invalid theme")
        return v


# ── Dashboard / analytics ────────────────────────────────────────────────────
class NamedValue(BaseModel):
    name: str
    value: Decimal


class TrendPoint(BaseModel):
    period: str
    income: Decimal
    expense: Decimal


class DashboardOut(BaseModel):
    currency: str
    daily_spend: Decimal
    weekly_spend: Decimal
    monthly_spend: Decimal
    total_income: Decimal
    total_expense: Decimal
    balance: Decimal
    top_merchants: List[NamedValue]
    category_breakdown: List[NamedValue]
    merchant_breakdown: List[NamedValue]
    trend: List[TrendPoint]
    insights: List[str]
    open_fraud_alerts: int
    pending_confirmations: int


# ── Offline sync ─────────────────────────────────────────────────────────────
class SyncRequest(BaseModel):
    transactions: List[TransactionCreate] = Field(default_factory=list)
    last_synced_at: Optional[dt.datetime] = None


class SyncResult(BaseModel):
    applied: int
    skipped_duplicates: int
    results: List[TransactionResult]
    server_changes: List[TransactionOut]
    synced_at: dt.datetime


class MessageOut(BaseModel):
    message: str


class TokenForReset(BaseModel):
    """Returned by forgot-password in non-production so flows are testable
    without an email provider. In production the token is emailed instead."""
    message: str
    reset_token: Optional[str] = None
