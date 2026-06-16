"""Celery application for background processing (receipt OCR hooks, periodic
fraud sweeps, etc.). Tasks are safe no-ops until wired to real producers."""
from __future__ import annotations

from celery import Celery

from app.core.config import settings

celery_app = Celery(
    "expense_tracker",
    broker=settings.celery_broker_url,
    backend=settings.celery_result_backend,
)
celery_app.conf.update(
    task_serializer="json",
    result_serializer="json",
    accept_content=["json"],
    timezone="UTC",
    enable_utc=True,
    task_track_started=True,
)

# Import task modules so they register with the worker.
from app.workers import tasks  # noqa: E402,F401
