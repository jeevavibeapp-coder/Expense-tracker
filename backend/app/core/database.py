"""Database engine, session factory, declarative base and a portable GUID type."""
from __future__ import annotations

import uuid
from typing import Iterator

from sqlalchemy import create_engine, types
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import DeclarativeBase, sessionmaker, Session

from app.core.config import settings


class GUID(types.TypeDecorator):
    """Platform-independent UUID type.

    Uses PostgreSQL's native UUID type when available, otherwise stores the
    value as a 36-char string (e.g. on SQLite). This keeps the same models
    working in production (Postgres) and in the test suite (SQLite).
    """

    impl = types.CHAR
    cache_ok = True

    def load_dialect_impl(self, dialect):
        if dialect.name == "postgresql":
            return dialect.type_descriptor(PG_UUID(as_uuid=True))
        return dialect.type_descriptor(types.CHAR(36))

    def process_bind_param(self, value, dialect):
        if value is None:
            return value
        if not isinstance(value, uuid.UUID):
            value = uuid.UUID(str(value))
        if dialect.name == "postgresql":
            return value
        return str(value)

    def process_result_value(self, value, dialect):
        if value is None:
            return value
        if isinstance(value, uuid.UUID):
            return value
        return uuid.UUID(str(value))


def new_uuid() -> uuid.UUID:
    return uuid.uuid4()


class Base(DeclarativeBase):
    pass


def _make_engine(url: str):
    connect_args = {}
    kwargs = {"pool_pre_ping": True, "future": True}
    if url.startswith("sqlite"):
        connect_args["check_same_thread"] = False
        # A shared in-memory/file DB needs StaticPool only for ":memory:";
        # file-based SQLite is fine with the default pool.
    return create_engine(url, connect_args=connect_args, **kwargs)


engine = _make_engine(settings.database_url)
SessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False, future=True)


def get_db() -> Iterator[Session]:
    """FastAPI dependency that yields a transactional session."""
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
