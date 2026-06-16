"""Pluggable object storage for receipts: local filesystem or S3/MinIO."""
from __future__ import annotations

import os
import uuid
from abc import ABC, abstractmethod
from pathlib import Path
from typing import Optional

from app.core.config import settings

ALLOWED_CONTENT_TYPES = {
    "image/jpeg", "image/png", "image/webp", "image/heic", "application/pdf",
}


class StorageError(Exception):
    pass


class Storage(ABC):
    @abstractmethod
    def save(self, *, user_id: str, data: bytes, filename: str, content_type: str) -> str:
        ...

    @abstractmethod
    def load(self, key: str) -> bytes:
        ...

    @abstractmethod
    def delete(self, key: str) -> None:
        ...


class LocalStorage(Storage):
    def __init__(self, base_dir: str):
        self.base = Path(base_dir)
        self.base.mkdir(parents=True, exist_ok=True)

    def _path(self, key: str) -> Path:
        p = (self.base / key).resolve()
        if not str(p).startswith(str(self.base.resolve())):
            raise StorageError("invalid storage key")
        return p

    def save(self, *, user_id: str, data: bytes, filename: str, content_type: str) -> str:
        ext = os.path.splitext(filename)[1][:10]
        key = f"{user_id}/{uuid.uuid4().hex}{ext}"
        path = self._path(key)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return key

    def load(self, key: str) -> bytes:
        path = self._path(key)
        if not path.exists():
            raise StorageError("object not found")
        return path.read_bytes()

    def delete(self, key: str) -> None:
        path = self._path(key)
        if path.exists():
            path.unlink()


class S3Storage(Storage):
    def __init__(self):
        import boto3  # imported lazily so local mode needs no AWS deps at runtime

        self._client = boto3.client(
            "s3",
            endpoint_url=settings.s3_endpoint_url or None,
            region_name=settings.s3_region,
            aws_access_key_id=settings.s3_access_key,
            aws_secret_access_key=settings.s3_secret_key,
        )
        self._bucket = settings.s3_bucket
        self._ensure_bucket()

    def _ensure_bucket(self) -> None:
        try:
            self._client.head_bucket(Bucket=self._bucket)
        except Exception:
            try:
                self._client.create_bucket(Bucket=self._bucket)
            except Exception as exc:  # pragma: no cover - infra dependent
                raise StorageError(f"cannot access bucket: {exc}") from exc

    def save(self, *, user_id: str, data: bytes, filename: str, content_type: str) -> str:
        ext = os.path.splitext(filename)[1][:10]
        key = f"{user_id}/{uuid.uuid4().hex}{ext}"
        self._client.put_object(Bucket=self._bucket, Key=key, Body=data,
                                ContentType=content_type)
        return key

    def load(self, key: str) -> bytes:
        obj = self._client.get_object(Bucket=self._bucket, Key=key)
        return obj["Body"].read()

    def delete(self, key: str) -> None:
        self._client.delete_object(Bucket=self._bucket, Key=key)


_storage: Optional[Storage] = None


def get_storage() -> Storage:
    global _storage
    if _storage is None:
        if settings.storage_backend == "s3":
            _storage = S3Storage()
        else:
            _storage = LocalStorage(settings.storage_local_dir)
    return _storage
