"""Authentication & account lifecycle service."""
from __future__ import annotations

import datetime as dt
import uuid

import jwt
from sqlalchemy import select
from sqlalchemy.orm import Session

from app import models
from app.core.config import settings
from app.core.security import (
    create_access_token, create_refresh_token, decode_token, hash_password,
    verify_password,
)
from app.repositories import UserRepository

# A standard, empty category set created at signup so the app is usable
# immediately. These are functional reference categories, not mock data.
DEFAULT_CATEGORIES = [
    ("Salary", "income", "Wallet", "#10b981"),
    ("Business", "income", "Briefcase", "#3b82f6"),
    ("Other Income", "income", "PlusCircle", "#64748b"),
    ("Food & Dining", "expense", "Utensils", "#f43f5e"),
    ("Groceries", "expense", "ShoppingCart", "#22c55e"),
    ("Shopping", "expense", "ShoppingBag", "#ec4899"),
    ("Transport", "expense", "Car", "#f59e0b"),
    ("Bills & Utilities", "expense", "Zap", "#3b82f6"),
    ("Entertainment", "expense", "Tv", "#8b5cf6"),
    ("Health", "expense", "HeartPulse", "#ef4444"),
    ("Other Expense", "expense", "PlusCircle", "#64748b"),
]


class AuthError(Exception):
    def __init__(self, message: str, status_code: int = 400):
        super().__init__(message)
        self.message = message
        self.status_code = status_code


def _now() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc)


def _provision_account(db: Session, user: models.User) -> None:
    for name, type_, icon, color in DEFAULT_CATEGORIES:
        db.add(models.Category(user_id=user.id, name=name, type=type_,
                               icon=icon, color=color))
    db.add(models.Setting(user_id=user.id))


def _issue_tokens(db: Session, user: models.User) -> dict:
    access = create_access_token(str(user.id), extra={"role": user.role})
    refresh = create_refresh_token(str(user.id))
    payload = decode_token(refresh, expected_type="refresh")
    db.add(models.RefreshToken(
        user_id=user.id, jti=payload["jti"],
        expires_at=dt.datetime.fromtimestamp(payload["exp"], tz=dt.timezone.utc),
    ))
    return {
        "access_token": access,
        "refresh_token": refresh,
        "token_type": "bearer",
        "expires_in": settings.access_token_expire_minutes * 60,
    }


def signup(db: Session, *, email: str, full_name: str, password: str) -> tuple[models.User, dict]:
    repo = UserRepository(db)
    email = email.lower().strip()
    if repo.get_by_email(email):
        raise AuthError("An account with this email already exists", 409)
    user = models.User(email=email, full_name=full_name.strip(),
                       hashed_password=hash_password(password))
    repo.add(user)
    _provision_account(db, user)
    tokens = _issue_tokens(db, user)
    return user, tokens


def login(db: Session, *, email: str, password: str) -> tuple[models.User, dict]:
    repo = UserRepository(db)
    user = repo.get_by_email(email.lower().strip())
    if not user or not verify_password(password, user.hashed_password):
        raise AuthError("Invalid email or password", 401)
    if not user.is_active:
        raise AuthError("Account is disabled", 403)
    tokens = _issue_tokens(db, user)
    return user, tokens


def refresh(db: Session, *, refresh_token: str) -> dict:
    try:
        payload = decode_token(refresh_token, expected_type="refresh")
    except jwt.ExpiredSignatureError:
        raise AuthError("Refresh token expired", 401)
    except jwt.InvalidTokenError:
        raise AuthError("Invalid refresh token", 401)

    stored = db.execute(
        select(models.RefreshToken).where(models.RefreshToken.jti == payload["jti"])
    ).scalar_one_or_none()
    if not stored or stored.revoked:
        raise AuthError("Refresh token has been revoked", 401)

    user = db.get(models.User, uuid.UUID(payload["sub"]))
    if not user or not user.is_active:
        raise AuthError("Account not found or disabled", 401)

    # Rotate: revoke the presented token, issue a fresh pair.
    stored.revoked = True
    return _issue_tokens(db, user)


def logout(db: Session, *, refresh_token: str) -> None:
    try:
        payload = decode_token(refresh_token, expected_type="refresh")
    except jwt.InvalidTokenError:
        return
    stored = db.execute(
        select(models.RefreshToken).where(models.RefreshToken.jti == payload["jti"])
    ).scalar_one_or_none()
    if stored:
        stored.revoked = True


def create_password_reset_token(db: Session, *, email: str) -> str | None:
    """Return a signed reset token, or None if no such account.

    Callers must not reveal which case occurred (avoid account enumeration).
    """
    user = UserRepository(db).get_by_email(email.lower().strip())
    if not user:
        return None
    now = _now()
    return jwt.encode(
        {"sub": str(user.id), "type": "reset", "iat": int(now.timestamp()),
         "exp": int((now + dt.timedelta(minutes=30)).timestamp()),
         "jti": uuid.uuid4().hex},
        settings.secret_key, algorithm=settings.jwt_algorithm,
    )


def reset_password(db: Session, *, token: str, new_password: str) -> None:
    try:
        payload = decode_token(token, expected_type="reset")
    except jwt.ExpiredSignatureError:
        raise AuthError("Reset link expired", 400)
    except jwt.InvalidTokenError:
        raise AuthError("Invalid reset link", 400)
    user = db.get(models.User, uuid.UUID(payload["sub"]))
    if not user:
        raise AuthError("Account not found", 404)
    user.hashed_password = hash_password(new_password)
    # Revoke all refresh tokens after a password change.
    for tok in db.execute(
        select(models.RefreshToken).where(models.RefreshToken.user_id == user.id)
    ).scalars().all():
        tok.revoked = True
