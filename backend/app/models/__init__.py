"""SQLAlchemy ORM models for the Expense Tracker domain.

Enum-like columns are stored as short strings (portable across PostgreSQL and
SQLite) with the allowed values declared as constants next to each model.
Monetary values use Numeric(14, 2). UUID primary keys (portable GUID type)
make offline-created rows safe to sync without id collisions.
"""
from __future__ import annotations

import datetime as dt
import uuid

from sqlalchemy import (
    Boolean, CheckConstraint, DateTime, ForeignKey, Integer, JSON, Numeric,
    String, Text, UniqueConstraint, Index, func,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.database import GUID, Base, new_uuid


def _utcnow() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc)


class TimestampMixin:
    created_at: Mapped[dt.datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, server_default=func.now(), nullable=False
    )
    updated_at: Mapped[dt.datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, onupdate=_utcnow,
        server_default=func.now(), nullable=False,
    )


# ── Constants ────────────────────────────────────────────────────────────────
TX_INCOME, TX_EXPENSE = "income", "expense"
SOURCE_MANUAL, SOURCE_SMS, SOURCE_IMPORT = "manual", "sms", "import"
TX_CONFIRMED, TX_PENDING, TX_REVIEW = "confirmed", "pending_confirmation", "needs_review"

ROLE_USER, ROLE_ADMIN = "user", "admin"

SUGGESTION_PENDING, SUGGESTION_ACCEPTED, SUGGESTION_REJECTED = "pending", "accepted", "rejected"

FRAUD_DUPLICATE = "duplicate"
FRAUD_ABNORMAL = "abnormal_spend"
FRAUD_UNUSUAL_MERCHANT = "unusual_merchant"
FRAUD_HIGH_VALUE = "high_value_outlier"
FRAUD_OPEN, FRAUD_DISMISSED, FRAUD_RESOLVED = "open", "dismissed", "resolved"
SEVERITY_LOW, SEVERITY_MEDIUM, SEVERITY_HIGH = "low", "medium", "high"


# ── Users & auth ─────────────────────────────────────────────────────────────
class User(TimestampMixin, Base):
    __tablename__ = "users"

    id: Mapped[uuid.UUID] = mapped_column(GUID, primary_key=True, default=new_uuid)
    email: Mapped[str] = mapped_column(String(320), unique=True, index=True, nullable=False)
    full_name: Mapped[str] = mapped_column(String(120), nullable=False)
    hashed_password: Mapped[str] = mapped_column(String(255), nullable=False)
    role: Mapped[str] = mapped_column(String(16), default=ROLE_USER, nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)

    __table_args__ = (
        CheckConstraint("role in ('user','admin')", name="ck_users_role"),
    )


class RefreshToken(TimestampMixin, Base):
    __tablename__ = "refresh_tokens"

    id: Mapped[uuid.UUID] = mapped_column(GUID, primary_key=True, default=new_uuid)
    user_id: Mapped[uuid.UUID] = mapped_column(GUID, ForeignKey("users.id", ondelete="CASCADE"),
                                    index=True, nullable=False)
    jti: Mapped[str] = mapped_column(String(64), unique=True, index=True, nullable=False)
    expires_at: Mapped[dt.datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    revoked: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)


# ── Categories ───────────────────────────────────────────────────────────────
class Category(TimestampMixin, Base):
    __tablename__ = "categories"

    id: Mapped[uuid.UUID] = mapped_column(GUID, primary_key=True, default=new_uuid)
    user_id: Mapped[uuid.UUID] = mapped_column(GUID, ForeignKey("users.id", ondelete="CASCADE"),
                                    index=True, nullable=False)
    name: Mapped[str] = mapped_column(String(80), nullable=False)
    type: Mapped[str] = mapped_column(String(16), default=TX_EXPENSE, nullable=False)
    icon: Mapped[str] = mapped_column(String(40), default="Tag", nullable=False)
    color: Mapped[str] = mapped_column(String(16), default="#6366f1", nullable=False)
    is_archived: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)

    __table_args__ = (
        UniqueConstraint("user_id", "name", name="uq_category_user_name"),
        CheckConstraint("type in ('income','expense')", name="ck_category_type"),
    )


# ── Merchant directory / mappings ────────────────────────────────────────────
class MerchantMapping(TimestampMixin, Base):
    """Canonical per-user merchant directory (the real businesses)."""
    __tablename__ = "merchant_mappings"

    id: Mapped[uuid.UUID] = mapped_column(GUID, primary_key=True, default=new_uuid)
    user_id: Mapped[uuid.UUID] = mapped_column(GUID, ForeignKey("users.id", ondelete="CASCADE"),
                                    index=True, nullable=False)
    canonical_name: Mapped[str] = mapped_column(String(160), nullable=False)
    category_id: Mapped[uuid.UUID] = mapped_column(
        GUID, ForeignKey("categories.id", ondelete="SET NULL"), nullable=True
    )

    __table_args__ = (
        UniqueConstraint("user_id", "canonical_name", name="uq_merchant_user_name"),
    )


class MerchantLearning(TimestampMixin, Base):
    """What the engine has learned about a raw name -> real merchant."""
    __tablename__ = "merchant_learning"

    id: Mapped[uuid.UUID] = mapped_column(GUID, primary_key=True, default=new_uuid)
    user_id: Mapped[uuid.UUID] = mapped_column(GUID, ForeignKey("users.id", ondelete="CASCADE"),
                                    index=True, nullable=False)
    raw_name: Mapped[str] = mapped_column(String(160), index=True, nullable=False)
    merchant_id: Mapped[uuid.UUID] = mapped_column(
        GUID, ForeignKey("merchant_mappings.id", ondelete="CASCADE"), nullable=False
    )
    merchant_name: Mapped[str] = mapped_column(String(160), nullable=False)
    category_id: Mapped[uuid.UUID] = mapped_column(
        GUID, ForeignKey("categories.id", ondelete="SET NULL"), nullable=True
    )

    confidence: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    correction_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    confirmation_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    sample_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)

    avg_amount: Mapped[float] = mapped_column(Numeric(14, 2), default=0, nullable=False)
    amount_min: Mapped[float] = mapped_column(Numeric(14, 2), default=0, nullable=False)
    amount_max: Mapped[float] = mapped_column(Numeric(14, 2), default=0, nullable=False)
    # 24-slot histogram of transaction hours, as JSON list[int].
    hour_histogram: Mapped[dict] = mapped_column(JSON, default=list, nullable=False)
    last_seen_at: Mapped[dt.datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, nullable=False
    )

    __table_args__ = (
        UniqueConstraint("user_id", "raw_name", "merchant_id", name="uq_learning_key"),
        Index("ix_learning_user_raw", "user_id", "raw_name"),
    )


class MerchantSuggestion(TimestampMixin, Base):
    """A suggestion presented to the user for a transaction needing confirmation."""
    __tablename__ = "merchant_suggestions"

    id: Mapped[uuid.UUID] = mapped_column(GUID, primary_key=True, default=new_uuid)
    user_id: Mapped[uuid.UUID] = mapped_column(GUID, ForeignKey("users.id", ondelete="CASCADE"),
                                    index=True, nullable=False)
    transaction_id: Mapped[uuid.UUID] = mapped_column(
        GUID, ForeignKey("transactions.id", ondelete="CASCADE"), index=True, nullable=False
    )
    raw_name: Mapped[str] = mapped_column(String(160), nullable=False)
    suggested_merchant_id: Mapped[uuid.UUID] = mapped_column(
        GUID, ForeignKey("merchant_mappings.id", ondelete="SET NULL"), nullable=True
    )
    suggested_merchant_name: Mapped[str] = mapped_column(String(160), nullable=True)
    confidence: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    breakdown: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)
    status: Mapped[str] = mapped_column(String(16), default=SUGGESTION_PENDING, nullable=False)
    resolved_at: Mapped[dt.datetime] = mapped_column(DateTime(timezone=True), nullable=True)


# ── Transactions ─────────────────────────────────────────────────────────────
class Transaction(TimestampMixin, Base):
    __tablename__ = "transactions"

    id: Mapped[uuid.UUID] = mapped_column(GUID, primary_key=True, default=new_uuid)
    user_id: Mapped[uuid.UUID] = mapped_column(GUID, ForeignKey("users.id", ondelete="CASCADE"),
                                    index=True, nullable=False)
    amount: Mapped[float] = mapped_column(Numeric(14, 2), nullable=False)
    type: Mapped[str] = mapped_column(String(16), default=TX_EXPENSE, nullable=False)
    category_id: Mapped[uuid.UUID] = mapped_column(
        GUID, ForeignKey("categories.id", ondelete="SET NULL"), index=True, nullable=True
    )

    raw_merchant: Mapped[str] = mapped_column(String(160), index=True, nullable=True)
    merchant_id: Mapped[uuid.UUID] = mapped_column(
        GUID, ForeignKey("merchant_mappings.id", ondelete="SET NULL"), nullable=True
    )
    merchant_name: Mapped[str] = mapped_column(String(160), index=True, nullable=True)

    notes: Mapped[str] = mapped_column(Text, nullable=True)
    reference_number: Mapped[str] = mapped_column(String(80), index=True, nullable=True)
    occurred_at: Mapped[dt.datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, index=True, nullable=False
    )
    source: Mapped[str] = mapped_column(String(16), default=SOURCE_MANUAL, nullable=False)
    confidence: Mapped[int] = mapped_column(Integer, nullable=True)
    status: Mapped[str] = mapped_column(String(24), default=TX_CONFIRMED, nullable=False)

    # Offline idempotency: client-generated id, unique per user.
    client_id: Mapped[str] = mapped_column(String(64), nullable=True)
    is_deleted: Mapped[bool] = mapped_column(Boolean, default=False, index=True, nullable=False)

    receipts: Mapped[list["Receipt"]] = relationship(
        back_populates="transaction", cascade="all, delete-orphan"
    )

    __table_args__ = (
        UniqueConstraint("user_id", "client_id", name="uq_tx_user_client"),
        CheckConstraint("type in ('income','expense')", name="ck_tx_type"),
        CheckConstraint("amount >= 0", name="ck_tx_amount_nonneg"),
        Index("ix_tx_user_occurred", "user_id", "occurred_at"),
        Index("ix_tx_user_merchant", "user_id", "merchant_name"),
    )


# ── Receipts ─────────────────────────────────────────────────────────────────
class Receipt(TimestampMixin, Base):
    __tablename__ = "receipts"

    id: Mapped[uuid.UUID] = mapped_column(GUID, primary_key=True, default=new_uuid)
    user_id: Mapped[uuid.UUID] = mapped_column(GUID, ForeignKey("users.id", ondelete="CASCADE"),
                                    index=True, nullable=False)
    transaction_id: Mapped[uuid.UUID] = mapped_column(
        GUID, ForeignKey("transactions.id", ondelete="CASCADE"), index=True, nullable=True
    )
    storage_key: Mapped[str] = mapped_column(String(512), nullable=False)
    filename: Mapped[str] = mapped_column(String(255), nullable=False)
    content_type: Mapped[str] = mapped_column(String(120), nullable=False)
    size_bytes: Mapped[int] = mapped_column(Integer, default=0, nullable=False)

    transaction: Mapped["Transaction"] = relationship(back_populates="receipts")


# ── Fraud ────────────────────────────────────────────────────────────────────
class FraudAlert(TimestampMixin, Base):
    __tablename__ = "fraud_alerts"

    id: Mapped[uuid.UUID] = mapped_column(GUID, primary_key=True, default=new_uuid)
    user_id: Mapped[uuid.UUID] = mapped_column(GUID, ForeignKey("users.id", ondelete="CASCADE"),
                                    index=True, nullable=False)
    transaction_id: Mapped[uuid.UUID] = mapped_column(
        GUID, ForeignKey("transactions.id", ondelete="CASCADE"), nullable=True
    )
    alert_type: Mapped[str] = mapped_column(String(32), nullable=False)
    severity: Mapped[str] = mapped_column(String(16), default=SEVERITY_LOW, nullable=False)
    message: Mapped[str] = mapped_column(String(400), nullable=False)
    details: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)
    status: Mapped[str] = mapped_column(String(16), default=FRAUD_OPEN, nullable=False)
    resolved_at: Mapped[dt.datetime] = mapped_column(DateTime(timezone=True), nullable=True)

    __table_args__ = (
        Index("ix_fraud_user_status", "user_id", "status"),
    )


# ── Settings ─────────────────────────────────────────────────────────────────
class Setting(TimestampMixin, Base):
    __tablename__ = "settings"

    id: Mapped[uuid.UUID] = mapped_column(GUID, primary_key=True, default=new_uuid)
    user_id: Mapped[uuid.UUID] = mapped_column(GUID, ForeignKey("users.id", ondelete="CASCADE"),
                                    unique=True, index=True, nullable=False)
    currency: Mapped[str] = mapped_column(String(8), default="INR", nullable=False)
    theme: Mapped[str] = mapped_column(String(16), default="system", nullable=False)
    auto_save_threshold: Mapped[int] = mapped_column(Integer, default=80, nullable=False)
    confirm_threshold: Mapped[int] = mapped_column(Integer, default=50, nullable=False)
    high_value_amount: Mapped[float] = mapped_column(Numeric(14, 2), nullable=True)
    data: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)


# ── Audit ────────────────────────────────────────────────────────────────────
class AuditLog(Base):
    __tablename__ = "audit_logs"

    id: Mapped[uuid.UUID] = mapped_column(GUID, primary_key=True, default=new_uuid)
    user_id: Mapped[uuid.UUID] = mapped_column(GUID, ForeignKey("users.id", ondelete="SET NULL"),
                                    index=True, nullable=True)
    action: Mapped[str] = mapped_column(String(80), nullable=False)
    entity_type: Mapped[str] = mapped_column(String(60), nullable=True)
    entity_id: Mapped[str] = mapped_column(String(64), nullable=True)
    ip: Mapped[str] = mapped_column(String(64), nullable=True)
    user_agent: Mapped[str] = mapped_column(String(300), nullable=True)
    meta: Mapped[dict] = mapped_column("metadata", JSON, default=dict, nullable=False)
    created_at: Mapped[dt.datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, server_default=func.now(),
        index=True, nullable=False,
    )


__all__ = [
    "Base", "User", "RefreshToken", "Category", "MerchantMapping",
    "MerchantLearning", "MerchantSuggestion", "Transaction", "Receipt",
    "FraudAlert", "Setting", "AuditLog",
]
