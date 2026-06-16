"""Web (browser) auth helpers — cookie-based, reusing the JWT machinery.

The JSON API authenticates with a Bearer token; the server-rendered web UI
stores the same access token in an httpOnly cookie and reads it here.
"""
from __future__ import annotations

import uuid
from typing import Optional

import jwt
from fastapi import Cookie, Depends, Request
from fastapi.responses import RedirectResponse
from sqlalchemy.orm import Session

from app import models
from app.core.config import settings
from app.core.database import get_db
from app.core.security import decode_token

ACCESS_COOKIE = "access_token"
REFRESH_COOKIE = "refresh_token"


class RedirectToLogin(Exception):
    """Raised by web dependencies when the visitor is not authenticated."""


def optional_user(
    access_token: Optional[str] = Cookie(default=None),
    db: Session = Depends(get_db),
) -> Optional[models.User]:
    if not access_token:
        return None
    try:
        payload = decode_token(access_token, expected_type="access")
        user = db.get(models.User, uuid.UUID(payload["sub"]))
    except (jwt.InvalidTokenError, KeyError, ValueError):
        return None
    if user is None or not user.is_active:
        return None
    return user


def require_user(user: Optional[models.User] = Depends(optional_user)) -> models.User:
    if user is None:
        raise RedirectToLogin()
    return user


def set_auth_cookies(response, tokens: dict) -> None:
    secure = settings.environment == "production"
    response.set_cookie(
        ACCESS_COOKIE, tokens["access_token"], httponly=True, samesite="lax",
        secure=secure, max_age=settings.access_token_expire_minutes * 60, path="/",
    )
    response.set_cookie(
        REFRESH_COOKIE, tokens["refresh_token"], httponly=True, samesite="lax",
        secure=secure, max_age=settings.refresh_token_expire_days * 86400, path="/",
    )


def clear_auth_cookies(response) -> None:
    response.delete_cookie(ACCESS_COOKIE, path="/")
    response.delete_cookie(REFRESH_COOKIE, path="/")


def login_redirect() -> RedirectResponse:
    return RedirectResponse(url="/login", status_code=303)
