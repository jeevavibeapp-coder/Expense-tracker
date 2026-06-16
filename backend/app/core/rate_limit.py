"""Lightweight fixed-window rate limiter.

Uses Redis when available (shared across workers); otherwise falls back to an
in-process counter. Keyed by client IP + path bucket.
"""
from __future__ import annotations

import threading
import time
from typing import Optional

from app.core.config import settings


class _MemoryLimiter:
    def __init__(self):
        self._lock = threading.Lock()
        self._hits: dict[str, tuple[int, float]] = {}

    def allow(self, key: str, limit: int, window: int) -> bool:
        now = time.time()
        with self._lock:
            count, reset = self._hits.get(key, (0, now + window))
            if now > reset:
                count, reset = 0, now + window
            count += 1
            self._hits[key] = (count, reset)
            return count <= limit


class _RedisLimiter:
    def __init__(self, url: str):
        import redis  # lazy

        self._redis = redis.Redis.from_url(url)

    def allow(self, key: str, limit: int, window: int) -> bool:
        pipe = self._redis.pipeline()
        pipe.incr(key, 1)
        pipe.expire(key, window)
        count, _ = pipe.execute()
        return int(count) <= limit


class RateLimiter:
    def __init__(self):
        self._backend = _MemoryLimiter()
        self._window = 60
        self._limit = settings.rate_limit_per_minute
        if settings.environment == "production":
            try:
                self._backend = _RedisLimiter(settings.redis_url)
            except Exception:
                # Redis unavailable -> degrade gracefully to in-memory limiting.
                self._backend = _MemoryLimiter()

    def allow(self, identifier: str) -> bool:
        if self._limit <= 0:
            return True
        bucket = int(time.time() // self._window)
        return self._backend.allow(f"rl:{identifier}:{bucket}", self._limit, self._window)


limiter: Optional[RateLimiter] = None


def get_limiter() -> RateLimiter:
    global limiter
    if limiter is None:
        limiter = RateLimiter()
    return limiter
