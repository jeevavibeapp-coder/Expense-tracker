"""Application configuration, loaded from environment / .env."""
from __future__ import annotations

from functools import lru_cache
from typing import List

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env", env_file_encoding="utf-8", extra="ignore"
    )

    # Application
    app_name: str = "ExpenseTracker"
    environment: str = "development"
    debug: bool = True
    api_v1_prefix: str = "/api/v1"

    # Security
    secret_key: str = "dev-insecure-secret-change-me"
    access_token_expire_minutes: int = 30
    refresh_token_expire_days: int = 14
    rate_limit_per_minute: int = 120
    jwt_algorithm: str = "HS256"

    # Database
    database_url: str = "sqlite:///./expense.db"

    # Redis / Celery
    redis_url: str = "redis://localhost:6379/0"
    celery_broker_url: str = "redis://localhost:6379/1"
    celery_result_backend: str = "redis://localhost:6379/2"

    # Storage
    storage_backend: str = "local"
    storage_local_dir: str = "./storage"
    s3_endpoint_url: str = "http://localhost:9000"
    s3_region: str = "us-east-1"
    s3_bucket: str = "receipts"
    s3_access_key: str = "minioadmin"
    s3_secret_key: str = "minioadmin"

    # CORS / uploads
    cors_origins: str = "*"
    max_upload_mb: int = 10

    @field_validator("database_url")
    @classmethod
    def _normalise_db_url(cls, v: str) -> str:
        # Accept the bare "postgres://" form some providers emit.
        if v.startswith("postgres://"):
            return v.replace("postgres://", "postgresql+psycopg2://", 1)
        return v

    @property
    def cors_origin_list(self) -> List[str]:
        if self.cors_origins.strip() == "*":
            return ["*"]
        return [o.strip() for o in self.cors_origins.split(",") if o.strip()]

    @property
    def is_sqlite(self) -> bool:
        return self.database_url.startswith("sqlite")


@lru_cache
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
