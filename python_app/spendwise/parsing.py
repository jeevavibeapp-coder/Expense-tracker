"""Deterministic SMS / UPI transaction parser (standard library only)."""
from __future__ import annotations

import datetime as dt
import re
from dataclasses import dataclass
from typing import Optional

_AMOUNT_RE = re.compile(r"(?:rs\.?|inr|₹)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)", re.IGNORECASE)
_REF_RE = re.compile(
    r"(?:ref(?:erence)?(?:\s*(?:no|number|id))?|txn(?:\s*id)?|utr|upi\s*ref)"
    r"[:\s.#-]*([A-Za-z0-9]{6,})", re.IGNORECASE)
_CREDIT_RE = re.compile(r"\b(credited|received|deposit(?:ed)?)\b", re.IGNORECASE)
_DEBIT_RE = re.compile(r"\b(debited|spent|paid|sent|withdrawn|purchase)\b", re.IGNORECASE)
_MERCHANT_RE = re.compile(
    r"(?:\bto\b|\bat\b|\bfrom\b|\bvpa\b|towards|info[:\-])\s*"
    r"([A-Za-z0-9][A-Za-z0-9 &._@/-]{1,60}?)"
    r"(?=\s+(?:on|ref|txn|utr|upi|avl|a/c|bal|info|via)\b|[.,]|$)", re.IGNORECASE)
_DATE_RES = [
    re.compile(r"\bon\s+(\d{1,2}[-/][A-Za-z]{3,9}[-/]\d{2,4})", re.IGNORECASE),
    re.compile(r"\bon\s+(\d{1,2}[-/]\d{1,2}[-/]\d{2,4})", re.IGNORECASE),
    re.compile(r"\b(\d{4}-\d{2}-\d{2})\b"),
]
_TIME_RE = re.compile(r"\b(\d{1,2}:\d{2}(?::\d{2})?)\b")


@dataclass
class ParsedSMS:
    amount: Optional[float] = None
    type: str = "expense"
    raw_merchant: Optional[str] = None
    reference_number: Optional[str] = None
    occurred_at: Optional[dt.datetime] = None
    matched: bool = False


def _parse_amount(text: str) -> Optional[float]:
    m = _AMOUNT_RE.search(text)
    if not m:
        return None
    try:
        return float(m.group(1).replace(",", ""))
    except ValueError:
        return None


def _parse_date(text: str) -> Optional[dt.datetime]:
    raw = None
    for rx in _DATE_RES:
        m = rx.search(text)
        if m:
            raw = m.group(1)
            break
    if not raw:
        return None
    parsed = None
    for fmt in ("%d-%b-%Y", "%d-%b-%y", "%d/%b/%Y", "%d/%b/%y",
                "%d-%m-%Y", "%d-%m-%y", "%d/%m/%Y", "%d/%m/%y", "%Y-%m-%d"):
        try:
            parsed = dt.datetime.strptime(raw, fmt).date()
            break
        except ValueError:
            continue
    if parsed is None:
        return None
    hour, minute = 0, 0
    tm = _TIME_RE.search(text)
    if tm:
        parts = tm.group(1).split(":")
        hour, minute = int(parts[0]), int(parts[1])
    return dt.datetime(parsed.year, parsed.month, parsed.day, hour, minute,
                       tzinfo=dt.timezone.utc)


def _parse_merchant(text: str) -> Optional[str]:
    m = _MERCHANT_RE.search(text)
    if not m:
        return None
    name = m.group(1).strip(" .,-").split("@", 1)[0].strip()
    return name or None


def parse_sms(text: str) -> ParsedSMS:
    text = (text or "").strip()
    result = ParsedSMS()
    if not text:
        return result
    result.amount = _parse_amount(text)
    result.type = "income" if (_CREDIT_RE.search(text) and not _DEBIT_RE.search(text)) else "expense"
    result.raw_merchant = _parse_merchant(text)
    ref = _REF_RE.search(text)
    result.reference_number = ref.group(1) if ref else None
    result.occurred_at = _parse_date(text)
    result.matched = result.amount is not None or result.raw_merchant is not None
    return result
