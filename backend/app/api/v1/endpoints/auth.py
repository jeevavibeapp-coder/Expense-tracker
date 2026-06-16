"""Authentication endpoints."""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.core.config import settings
from app.core.database import get_db
from app.core.deps import RequestContext, get_current_user, get_request_context
from app import models
from app.schemas import (
    AuthResponse, ForgotPasswordRequest, LoginRequest, MessageOut, RefreshRequest,
    ResetPasswordRequest, SignupRequest, TokenForReset, TokenPair, UserOut,
)
from app.services import audit, auth_service
from app.services.auth_service import AuthError

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post("/signup", response_model=AuthResponse, status_code=201)
def signup(body: SignupRequest, ctx: RequestContext = Depends(get_request_context),
           db: Session = Depends(get_db)):
    try:
        user, tokens = auth_service.signup(
            db, email=body.email, full_name=body.full_name, password=body.password
        )
    except AuthError as exc:
        raise HTTPException(exc.status_code, exc.message)
    audit.record(db, action="signup", user_id=user.id, entity_type="user",
                 entity_id=user.id, ip=ctx.ip, user_agent=ctx.user_agent)
    db.commit()
    return AuthResponse(user=UserOut.model_validate(user), tokens=TokenPair(**tokens))


@router.post("/login", response_model=AuthResponse)
def login(body: LoginRequest, ctx: RequestContext = Depends(get_request_context),
          db: Session = Depends(get_db)):
    try:
        user, tokens = auth_service.login(db, email=body.email, password=body.password)
    except AuthError as exc:
        raise HTTPException(exc.status_code, exc.message)
    audit.record(db, action="login", user_id=user.id, entity_type="user",
                 entity_id=user.id, ip=ctx.ip, user_agent=ctx.user_agent)
    db.commit()
    return AuthResponse(user=UserOut.model_validate(user), tokens=TokenPair(**tokens))


@router.post("/refresh", response_model=TokenPair)
def refresh(body: RefreshRequest, db: Session = Depends(get_db)):
    try:
        tokens = auth_service.refresh(db, refresh_token=body.refresh_token)
    except AuthError as exc:
        raise HTTPException(exc.status_code, exc.message)
    db.commit()
    return TokenPair(**tokens)


@router.post("/logout", response_model=MessageOut)
def logout(body: RefreshRequest, db: Session = Depends(get_db)):
    auth_service.logout(db, refresh_token=body.refresh_token)
    db.commit()
    return MessageOut(message="Logged out")


@router.post("/forgot-password", response_model=TokenForReset)
def forgot_password(body: ForgotPasswordRequest, db: Session = Depends(get_db)):
    token = auth_service.create_password_reset_token(db, email=body.email)
    db.commit()
    # Never reveal whether the account exists.
    message = "If an account exists for that email, a reset link has been sent."
    # Outside production, return the token so the flow is testable without email.
    if settings.environment != "production":
        return TokenForReset(message=message, reset_token=token)
    return TokenForReset(message=message, reset_token=None)


@router.post("/reset-password", response_model=MessageOut)
def reset_password(body: ResetPasswordRequest, db: Session = Depends(get_db)):
    try:
        auth_service.reset_password(db, token=body.token, new_password=body.new_password)
    except AuthError as exc:
        raise HTTPException(exc.status_code, exc.message)
    db.commit()
    return MessageOut(message="Password updated")


@router.get("/me", response_model=UserOut)
def me(user: models.User = Depends(get_current_user)):
    return UserOut.model_validate(user)
