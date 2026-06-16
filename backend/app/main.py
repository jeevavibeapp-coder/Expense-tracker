"""FastAPI application factory and ASGI entrypoint."""
from __future__ import annotations

import logging
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles

from app import __version__
from app.api.v1.router import api_router
from app.core.config import settings
from app.core.rate_limit import get_limiter
from app.web.deps import RedirectToLogin
from app.web.router import router as web_router

logging.basicConfig(level=logging.INFO if not settings.debug else logging.DEBUG)
logger = logging.getLogger("expense.api")


def create_app() -> FastAPI:
    app = FastAPI(
        title="Expense Tracker — Smart Merchant Context",
        version=__version__,
        description=(
            "Expense tracking with an intelligent merchant resolution + learning "
            "engine, confidence scoring, fraud detection, analytics and offline sync."
        ),
        docs_url="/docs",
        redoc_url="/redoc",
        openapi_url="/openapi.json",
    )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origin_list,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    @app.middleware("http")
    async def rate_limit_middleware(request: Request, call_next):
        if request.url.path.startswith(settings.api_v1_prefix):
            client_ip = request.client.host if request.client else "anonymous"
            fwd = request.headers.get("x-forwarded-for")
            if fwd:
                client_ip = fwd.split(",")[0].strip()
            identifier = f"{client_ip}:{request.url.path}"
            if not get_limiter().allow(identifier):
                return JSONResponse(
                    status_code=429,
                    content={"detail": "Rate limit exceeded. Please slow down."},
                )
        return await call_next(request)

    app.include_router(api_router, prefix=settings.api_v1_prefix)

    # Server-rendered web UI (Jinja + HTMX).
    static_dir = Path(__file__).resolve().parent / "web" / "static"
    app.mount("/static", StaticFiles(directory=str(static_dir)), name="static")
    app.include_router(web_router)

    @app.exception_handler(RedirectToLogin)
    async def _redirect_to_login(request: Request, exc: RedirectToLogin):
        return RedirectResponse(url="/login", status_code=303)

    @app.get("/health", tags=["system"])
    def health():
        return {"status": "ok", "version": __version__,
                "environment": settings.environment}

    @app.get("/api", tags=["system"])
    def api_info():
        return {"name": "Expense Tracker API", "docs": "/docs",
                "version": __version__}

    return app


app = create_app()
