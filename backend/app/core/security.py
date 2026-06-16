"""Password hashing and JWT access/refresh token helpers."""
from __future__ import annotations

import datetime as dt
import uuid
from typing import Any, Optional

import bcrypt
import jwt

from app.core.config import settings

# bcrypt has a hard 72-byte limit on the input; encode and truncate safely.
_BCRYPT_MAX_BYTES = 72


def hash_password(password: str) -> str:
    pw = password.encode("utf-8")[:_BCRYPT_MAX_BYTES]
    return bcrypt.hashpw(pw, bcrypt.gensalt()).decode("utf-8")


def verify_password(password: str, hashed: str) -> bool:
    try:
        pw = password.encode("utf-8")[:_BCRYPT_MAX_BYTES]
        return bcrypt.checkpw(pw, hashed.encode("utf-8"))
    except (ValueError, TypeError):
        return False


def _now() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc)


def _create_token(subject: str, token_type: str, expires_delta: dt.timedelta,
                  extra: Optional[dict[str, Any]] = None) -> str:
    now = _now()
    payload: dict[str, Any] = {
        "sub": subject,
        "type": token_type,
        "iat": int(now.timestamp()),
        "exp": int((now + expires_delta).timestamp()),
        "jti": uuid.uuid4().hex,
    }
    if extra:
        payload.update(extra)
    return jwt.encode(payload, settings.secret_key, algorithm=settings.jwt_algorithm)


def create_access_token(subject: str, extra: Optional[dict[str, Any]] = None) -> str:
    return _create_token(
        subject, "access",
        dt.timedelta(minutes=settings.access_token_expire_minutes), extra,
    )


def create_refresh_token(subject: str) -> str:
    return _create_token(
        subject, "refresh",
        dt.timedelta(days=settings.refresh_token_expire_days),
    )


def decode_token(token: str, expected_type: Optional[str] = None) -> dict[str, Any]:
    """Decode and validate a JWT. Raises jwt exceptions on failure."""
    payload = jwt.decode(
        token, settings.secret_key, algorithms=[settings.jwt_algorithm]
    )
    if expected_type is not None and payload.get("type") != expected_type:
        raise jwt.InvalidTokenError(
            f"expected {expected_type} token, got {payload.get('type')}"
        )
    return payload
