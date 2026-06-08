"""Aggregate router for API v1."""
from fastapi import APIRouter

from app.api.v1.endpoints import (
    auth, categories, fraud, insights, merchants, receipts, settings, transactions,
)

api_router = APIRouter()
api_router.include_router(auth.router)
api_router.include_router(categories.router)
api_router.include_router(transactions.router)
api_router.include_router(merchants.router)
api_router.include_router(receipts.router)
api_router.include_router(insights.router)
api_router.include_router(fraud.router)
api_router.include_router(settings.router)
