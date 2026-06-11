"""Smart merchant resolution + confidence scoring + learning.

Confidence weights (total 100):
    Past Mapping ........ 40
    Amount Pattern ...... 20
    Category Pattern .... 15
    Correction History .. 15
    Time Pattern ........ 10

Decision: score >= auto -> auto_saved; >= confirm -> confirmation_required;
else manual_required. This mirrors the FastAPI engine exactly, with no external
dependencies so it runs embedded on-device.
"""
from __future__ import annotations

import datetime as dt
import json
import re
from typing import Optional

from . import db

W_PAST_MAPPING = 40.0
W_AMOUNT = 20.0
W_CATEGORY = 15.0
W_CORRECTION = 15.0
W_TIME = 10.0
_FULL_TRUST = 5.0

DECISION_AUTO = "auto_saved"
DECISION_CONFIRM = "confirmation_required"
DECISION_MANUAL = "manual_required"

_NOISE_TOKENS = {
    "UPI", "VPA", "P2M", "P2A", "POS", "NEFT", "IMPS", "RTGS", "ACH",
    "PVT", "LTD", "LIMITED", "PRIVATE", "AND", "THE",
}
_NOISE_RE = re.compile(r"[^A-Z0-9& ]+")
_LONG_DIGITS_RE = re.compile(r"\b\d{4,}\b")


def normalize_merchant(raw: str) -> str:
    if not raw:
        return ""
    s = raw.upper().strip()
    s = s.split("@", 1)[0]
    s = s.replace("/", " ").replace("-", " ").replace("_", " ").replace(".", " ")
    s = _LONG_DIGITS_RE.sub(" ", s)
    s = _NOISE_RE.sub(" ", s)
    tokens = [t for t in s.split() if t and t not in _NOISE_TOKENS]
    return " ".join(tokens).strip()


def _f(v) -> float:
    return float(v) if v is not None else 0.0


def _score_past(row) -> float:
    strength = (row["confirmation_count"] + 0.5 * row["sample_count"]
                - row["correction_count"])
    return W_PAST_MAPPING * max(0.0, min(1.0, strength / _FULL_TRUST))


def _score_amount(row, amount: Optional[float]) -> float:
    if amount is None or row["sample_count"] <= 0:
        return 0.0
    a = float(amount)
    avg = _f(row["avg_amount"])
    lo, hi = _f(row["amount_min"]), _f(row["amount_max"])
    tolerance = max(avg * 0.25, (hi - lo) / 2.0, 1.0)
    closeness = max(0.0, 1.0 - abs(a - avg) / (tolerance * 2.0))
    if lo <= a <= hi:
        closeness = max(closeness, 0.6)
    return W_AMOUNT * closeness


def _score_category(row, category_id: Optional[str]) -> float:
    if row["category_id"] is None:
        return 0.0
    if category_id is None:
        return W_CATEGORY * 0.5
    return W_CATEGORY if str(row["category_id"]) == str(category_id) else 0.0


def _score_correction(row) -> float:
    denom = row["confirmation_count"] + row["correction_count"] + 1
    return W_CORRECTION * ((row["confirmation_count"] + 1) / denom)


def _score_time(row, occurred_at: Optional[dt.datetime]) -> float:
    try:
        hist = json.loads(row["hour_histogram"] or "[]")
    except (ValueError, TypeError):
        hist = []
    if occurred_at is None or not hist or sum(hist) <= 0:
        return 0.0
    hour = occurred_at.hour % 24
    peak = max(hist) or 1
    return W_TIME * (hist[hour] / peak if hour < len(hist) else 0.0)


def score(row, *, amount, category_id, occurred_at) -> dict:
    past = _score_past(row)
    amt = _score_amount(row, amount)
    cat = _score_category(row, category_id)
    corr = _score_correction(row)
    tm = _score_time(row, occurred_at)
    total = int(round(max(0.0, min(100.0, past + amt + cat + corr + tm))))
    return {"past_mapping": round(past, 2), "amount_pattern": round(amt, 2),
            "category_pattern": round(cat, 2), "correction_history": round(corr, 2),
            "time_pattern": round(tm, 2), "total": total}


def decide(total: int, *, auto: int, confirm: int) -> str:
    if total >= auto:
        return DECISION_AUTO
    if total >= confirm:
        return DECISION_CONFIRM
    return DECISION_MANUAL


def resolve(conn, *, user_id: str, raw_name: str, amount=None, category_id=None,
            occurred_at: Optional[dt.datetime] = None, auto: int = 80,
            confirm: int = 50) -> dict:
    normalized = normalize_merchant(raw_name)
    if not normalized:
        return {"raw_name": raw_name, "decision": DECISION_MANUAL,
                "best": None, "candidates": []}
    rows = db.all_rows(
        conn, "SELECT * FROM learning WHERE user_id=? AND raw_name=?",
        (user_id, normalized))
    candidates = []
    for row in rows:
        bd = score(row, amount=amount, category_id=category_id, occurred_at=occurred_at)
        candidates.append({"merchant_id": row["merchant_id"],
                           "merchant_name": row["merchant_name"],
                           "confidence": bd["total"], "breakdown": bd})
    candidates.sort(key=lambda c: c["confidence"], reverse=True)
    best = candidates[0] if candidates else None
    decision = (decide(best["confidence"], auto=auto, confirm=confirm)
                if best else DECISION_MANUAL)
    return {"raw_name": normalized, "decision": decision,
            "best": best, "candidates": candidates}


def get_or_create_merchant(conn, *, user_id: str, canonical_name: str,
                           category_id=None) -> dict:
    name = canonical_name.strip()
    row = db.one(conn, "SELECT * FROM merchants WHERE user_id=? AND canonical_name=?",
                 (user_id, name))
    if row:
        if category_id and row["category_id"] != category_id:
            db.execute(conn, "UPDATE merchants SET category_id=? WHERE id=?",
                       (category_id, row["id"]))
        return {"id": row["id"], "canonical_name": row["canonical_name"]}
    mid = db.new_id()
    db.execute(conn, "INSERT INTO merchants(id, user_id, canonical_name, category_id) "
                     "VALUES (?,?,?,?)", (mid, user_id, name, category_id))
    return {"id": mid, "canonical_name": name}


def _baseline_confidence(row) -> int:
    strength = (row["confirmation_count"] + 0.5 * row["sample_count"]
                - row["correction_count"])
    past = W_PAST_MAPPING * max(0.0, min(1.0, strength / _FULL_TRUST))
    denom = row["confirmation_count"] + row["correction_count"] + 1
    correction = W_CORRECTION * ((row["confirmation_count"] + 1) / denom)
    category = W_CATEGORY if row["category_id"] is not None else 0.0
    return int(round(max(0.0, min(100.0, past + correction + category))))


def record_confirmation(conn, *, user_id: str, raw_name: str, merchant_name: str,
                        amount=None, category_id=None,
                        occurred_at: Optional[dt.datetime] = None,
                        is_correction: bool = False) -> None:
    normalized = normalize_merchant(raw_name)
    if not normalized:
        return
    merchant = get_or_create_merchant(conn, user_id=user_id,
                                      canonical_name=merchant_name, category_id=category_id)
    row = db.one(conn, "SELECT * FROM learning WHERE user_id=? AND raw_name=? AND merchant_id=?",
                 (user_id, normalized, merchant["id"]))
    now = (occurred_at or dt.datetime.now(dt.timezone.utc)).isoformat()
    if row is None:
        lid = db.new_id()
        db.execute(conn,
                   "INSERT INTO learning(id,user_id,raw_name,merchant_id,merchant_name,"
                   "category_id,hour_histogram,last_seen_at) VALUES (?,?,?,?,?,?,?,?)",
                   (lid, user_id, normalized, merchant["id"], merchant["canonical_name"],
                    category_id, json.dumps([0] * 24), now))
        row = db.one(conn, "SELECT * FROM learning WHERE id=?", (lid,))

    sample_count = row["sample_count"]
    avg_amount = _f(row["avg_amount"])
    amount_min = _f(row["amount_min"])
    amount_max = _f(row["amount_max"])
    if amount is not None:
        a = float(amount)
        avg_amount = (avg_amount * sample_count + a) / (sample_count + 1)
        amount_min = a if sample_count == 0 else min(amount_min, a)
        amount_max = a if sample_count == 0 else max(amount_max, a)
        sample_count += 1

    try:
        hist = json.loads(row["hour_histogram"] or "[]")
    except (ValueError, TypeError):
        hist = []
    if len(hist) < 24:
        hist = (hist + [0] * 24)[:24]
    if occurred_at is not None:
        hist[occurred_at.hour % 24] += 1

    category = category_id if category_id is not None else row["category_id"]
    confirmation_count = row["confirmation_count"] + 1
    updated = {
        "sample_count": sample_count, "avg_amount": round(avg_amount, 2),
        "amount_min": round(amount_min, 2), "amount_max": round(amount_max, 2),
        "hour_histogram": json.dumps(hist), "category_id": category,
        "merchant_name": merchant["canonical_name"],
        "confirmation_count": confirmation_count, "last_seen_at": now,
        "correction_count": row["correction_count"],
    }
    db.execute(conn,
               "UPDATE learning SET sample_count=?, avg_amount=?, amount_min=?, "
               "amount_max=?, hour_histogram=?, category_id=?, merchant_name=?, "
               "confirmation_count=?, last_seen_at=? WHERE id=?",
               (updated["sample_count"], updated["avg_amount"], updated["amount_min"],
                updated["amount_max"], updated["hour_histogram"], updated["category_id"],
                updated["merchant_name"], updated["confirmation_count"],
                updated["last_seen_at"], row["id"]))

    if is_correction:
        others = db.all_rows(
            conn, "SELECT * FROM learning WHERE user_id=? AND raw_name=? AND merchant_id!=?",
            (user_id, normalized, merchant["id"]))
        for other in others:
            db.execute(conn, "UPDATE learning SET correction_count=correction_count+1 WHERE id=?",
                       (other["id"],))
            refreshed = db.one(conn, "SELECT * FROM learning WHERE id=?", (other["id"],))
            db.execute(conn, "UPDATE learning SET confidence=? WHERE id=?",
                       (_baseline_confidence(refreshed), other["id"]))

    final = db.one(conn, "SELECT * FROM learning WHERE id=?", (row["id"],))
    db.execute(conn, "UPDATE learning SET confidence=? WHERE id=?",
               (_baseline_confidence(final), row["id"]))
