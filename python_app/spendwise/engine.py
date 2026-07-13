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
    "PAYMENTS", "PAYMENT", "INDIA", "ONLINE", "RETAIL", "STORES", "STORE",
}
_NOISE_RE = re.compile(r"[^A-Z0-9& ]+")
_LONG_DIGITS_RE = re.compile(r"\b\d{4,}\b")
_GLUED_DIGITS_RE = re.compile(r"(?<=[A-Z])\d{1,3}\b")


def normalize_merchant(raw: str) -> str:
    if not raw:
        return ""
    s = raw.upper().strip()
    s = s.split("@", 1)[0]
    s = s.replace("/", " ").replace("-", " ").replace("_", " ").replace(".", " ")
    s = _LONG_DIGITS_RE.sub(" ", s)
    # VPA-style digit suffixes glued to the name: SWIGGY8 -> SWIGGY, so every
    # handle variant of the same merchant shares one learning row.
    s = _GLUED_DIGITS_RE.sub("", s)
    s = _NOISE_RE.sub(" ", s)
    tokens = [t for t in s.split() if t and t not in _NOISE_TOKENS]
    # Trailing pure-digit tokens are references, not names.
    while tokens and tokens[-1].isdigit():
        tokens.pop()
    return " ".join(tokens).strip()


# Built-in Indian merchant seed: instant recognition on a fresh install, before
# any learning exists. Keys are normalized first tokens; values are
# (display name, default category name from auth.DEFAULT_CATEGORIES).
SEED_MERCHANTS = {
    "SWIGGY": ("Swiggy", "Food & Dining"), "ZOMATO": ("Zomato", "Food & Dining"),
    "DOMINOS": ("Dominos", "Food & Dining"), "KFC": ("KFC", "Food & Dining"),
    "MCDONALD": ("McDonalds", "Food & Dining"), "MCDONALDS": ("McDonalds", "Food & Dining"),
    "STARBUCKS": ("Starbucks", "Food & Dining"), "PIZZAHUT": ("Pizza Hut", "Food & Dining"),
    "BLINKIT": ("Blinkit", "Groceries"), "ZEPTO": ("Zepto", "Groceries"),
    "BIGBASKET": ("BigBasket", "Groceries"), "DMART": ("DMart", "Groceries"),
    "JIOMART": ("JioMart", "Groceries"), "INSTAMART": ("Swiggy Instamart", "Groceries"),
    "AMAZON": ("Amazon", "Shopping"), "FLIPKART": ("Flipkart", "Shopping"),
    "MYNTRA": ("Myntra", "Shopping"), "AJIO": ("Ajio", "Shopping"),
    "MEESHO": ("Meesho", "Shopping"), "NYKAA": ("Nykaa", "Shopping"),
    "UBER": ("Uber", "Transport"), "OLA": ("Ola", "Transport"),
    "RAPIDO": ("Rapido", "Transport"), "IRCTC": ("IRCTC", "Transport"),
    "REDBUS": ("RedBus", "Transport"), "INDIGO": ("IndiGo", "Transport"),
    "JIO": ("Jio", "Bills & Utilities"), "AIRTEL": ("Airtel", "Bills & Utilities"),
    "VODAFONE": ("Vi", "Bills & Utilities"), "BSNL": ("BSNL", "Bills & Utilities"),
    "TATAPOWER": ("Tata Power", "Bills & Utilities"), "BESCOM": ("BESCOM", "Bills & Utilities"),
    "NETFLIX": ("Netflix", "Entertainment"), "HOTSTAR": ("Disney+ Hotstar", "Entertainment"),
    "SPOTIFY": ("Spotify", "Entertainment"), "PRIMEVIDEO": ("Prime Video", "Entertainment"),
    "BOOKMYSHOW": ("BookMyShow", "Entertainment"), "SONYLIV": ("SonyLIV", "Entertainment"),
    "APOLLO": ("Apollo Pharmacy", "Health"), "PHARMEASY": ("PharmEasy", "Health"),
    "NETMEDS": ("Netmeds", "Health"), "PRACTO": ("Practo", "Health"),
}
SEED_CONFIDENCE = 90


def seed_lookup(normalized: str):
    """Return (display_name, category_name) for a known Indian merchant."""
    if not normalized:
        return None
    key = normalized.replace(" ", "")
    if key in SEED_MERCHANTS:
        return SEED_MERCHANTS[key]
    first = normalized.split()[0]
    return SEED_MERCHANTS.get(first)


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


def _token_overlap(a: str, b: str) -> float:
    """Jaccard overlap between token sets — 'SWIGGY INSTAMART' vs 'SWIGGY'
    still finds the learning the user already did."""
    ta, tb = set(a.split()), set(b.split())
    if not ta or not tb:
        return 0.0
    return len(ta & tb) / len(ta | tb)


def explain(breakdown: dict, row=None) -> list[str]:
    """Human-readable reasons for a prediction — users should see WHY."""
    reasons = []
    if breakdown.get("seeded"):
        reasons.append("Well-known Indian merchant (built-in)")
        return reasons
    if row is not None:
        n = row["confirmation_count"]
        if n:
            reasons.append(f"You confirmed this match {n} time{'s' if n != 1 else ''}")
        if breakdown.get("amount_pattern", 0) >= W_AMOUNT * 0.5 and row["sample_count"]:
            lo, hi = row["amount_min"], row["amount_max"]
            reasons.append(f"Amount fits its usual {lo:.0f}–{hi:.0f} range")
        if breakdown.get("time_pattern", 0) >= W_TIME * 0.5:
            reasons.append("Usually paid around this time of day")
        if breakdown.get("category_pattern", 0) >= W_CATEGORY:
            reasons.append("Matches its learned category")
        if row["correction_count"]:
            reasons.append(f"But corrected away {row['correction_count']} "
                           f"time{'s' if row['correction_count'] != 1 else ''}")
    if not reasons:
        reasons.append("Closest match to what you've taught SpendWise")
    return reasons


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
    # Fuzzy fallback: a new alias of a known merchant ("SWIGGY INSTAMART" vs
    # learned "SWIGGY") should reuse the training, mildly discounted.
    fuzzy_penalty = 1.0
    if not rows:
        all_rows_ = db.all_rows(
            conn, "SELECT * FROM learning WHERE user_id=? ORDER BY "
            "confirmation_count DESC LIMIT 400", (user_id,))
        scored = [(r, _token_overlap(normalized, r["raw_name"])) for r in all_rows_]
        scored = [(r, o) for r, o in scored if o >= 0.5]
        if scored:
            best_overlap = max(o for _, o in scored)
            rows = [r for r, o in scored if o == best_overlap]
            fuzzy_penalty = 0.85
    candidates = []
    for row in rows:
        bd = score(row, amount=amount, category_id=category_id, occurred_at=occurred_at)
        if fuzzy_penalty < 1.0:
            bd["total"] = int(round(bd["total"] * fuzzy_penalty))
            bd["fuzzy"] = True
        candidates.append({"merchant_id": row["merchant_id"],
                           "merchant_name": row["merchant_name"],
                           "confidence": bd["total"], "breakdown": bd,
                           "reasons": explain(bd, row)})
    candidates.sort(key=lambda c: c["confidence"], reverse=True)

    # Cold start: no learning yet, but the merchant is a well-known Indian
    # brand — resolve it (with its category) from the built-in seed so the
    # flagship feature works on day one.
    if not candidates:
        seeded = seed_lookup(normalized)
        if seeded:
            display, cat_name = seeded
            cat = db.one(conn, "SELECT id FROM categories WHERE user_id=? AND name=? "
                         "AND is_archived=0", (user_id, cat_name))
            merchant = get_or_create_merchant(
                conn, user_id=user_id, canonical_name=display,
                category_id=cat["id"] if cat else None)
            bd = {"past_mapping": 0.0, "amount_pattern": 0.0, "category_pattern": 0.0,
                  "correction_history": 0.0, "time_pattern": 0.0,
                  "total": SEED_CONFIDENCE, "seeded": True}
            candidates = [{"merchant_id": merchant["id"],
                           "merchant_name": merchant["canonical_name"],
                           "confidence": SEED_CONFIDENCE, "breakdown": bd,
                           "reasons": explain(bd)}]

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
