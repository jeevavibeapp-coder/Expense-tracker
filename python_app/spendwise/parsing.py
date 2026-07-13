"""Deterministic SMS / UPI transaction parser (standard library only).

Tuned against real Indian bank & UPI formats: HDFC, SBI ("debited by 199.0",
no Rs prefix), ICICI ("; SWIGGY credited."), Axis ("UPI/P2M/<ref>/ZOMATO/…"),
Kotak ("Sent Rs.20.00 from Kotak Bank AC X1234 to swiggy8@ybl"), PhonePe /
GPay / Paytm UPI alerts, card alerts and EMI debits.
"""
from __future__ import annotations

import datetime as dt
import re
from dataclasses import dataclass
from typing import Optional

_CURRENCY = r"(?:rs\.?|inr|₹)"
_NUM = r"([0-9][0-9,]*(?:\.[0-9]{1,2})?)"

# Amount with an explicit currency marker: "Rs.450.00", "INR 1,234".
_AMOUNT_RE = re.compile(rf"{_CURRENCY}\s*{_NUM}", re.IGNORECASE)
# SBI-style verb-anchored amount with no currency marker:
# "A/C X9218 debited by 199.0", "credited with 5,000".
_VERB_AMOUNT_RE = re.compile(
    rf"\b(?:debited|credited)\s+(?:by|with|for|of)\s+(?:{_CURRENCY}\s*)?{_NUM}",
    re.IGNORECASE)
# Amounts that are balances, not the transaction: "Avl Bal Rs 12,430.50".
_BALANCE_CTX_RE = re.compile(
    r"(?:avl|avail(?:able)?|a/c|account|total|closing|updated)\s*(?:bal(?:ance)?)?\s*"
    r"(?:bal(?:ance)?)?\s*(?:is|:|-)?\s*$", re.IGNORECASE)

_REF_RE = re.compile(
    r"(?:ref(?:erence)?(?:\s*(?:no|number|id))?|txn(?:\s*id)?|utr|upi\s*ref)"
    r"[:\s.#-]*([A-Za-z0-9]{6,})", re.IGNORECASE)
_CREDIT_RE = re.compile(r"\b(credited|received|deposit(?:ed)?)\b", re.IGNORECASE)
_DEBIT_RE = re.compile(r"\b(debited|spent|paid|sent|withdrawn|purchase(?:d)?)\b",
                       re.IGNORECASE)
# The transactional gate: a money movement verb must be present, otherwise the
# message is promotional / informational and must not be auto-captured.
_TXN_VERB_RE = re.compile(
    r"\b(debited|credited|spent|paid|sent|received|withdrawn|purchase(?:d)?|"
    r"deposit(?:ed)?|transferred|payment)\b", re.IGNORECASE)
# Messages describing money that has NOT moved: offers, UPI collect requests,
# autopay pre-debit reminders, EMI due notices, declined transactions.
_NON_TXN_RE = re.compile(
    r"(\boff\b|\boffer|cashback|discount|coupon|% ?off|flat \d|"
    r"payment request|requested|collect request|is requesting|"
    r"will be (?:debited|deducted|charged)|due on|due by|overdue|"
    r"e-?mandate|autopay.{0,20}(?:scheduled|upcoming)|"
    r"declined|failed|reversed|refund initiated|otp|one.?time password)",
    re.IGNORECASE)

_BOUNDARY = r"(?=\s+(?:on|ref|txn|utr|upi|avl|a/c|bal|info|via|using|to|not|is)\b|[.,;]|$)"
# Ordered payee markers for debits; "from" is the payer side and only trusted
# for credits (Kotak: "Sent Rs.20 from Kotak Bank AC X1234 to swiggy8@ybl").
_TO_RE = re.compile(
    rf"(?:\bto\b|\bat\b|towards|\bvpa\b|info[:\-])\s*"
    rf"([A-Za-z0-9][A-Za-z0-9 &._@/-]{{1,60}}?){_BOUNDARY}", re.IGNORECASE)
_FROM_RE = re.compile(
    rf"\bfrom\b\s*([A-Za-z0-9][A-Za-z0-9 &._@/-]{{1,60}}?){_BOUNDARY}", re.IGNORECASE)
# Axis card/UPI path: "UPI/P2M/519023481234/ZOMATO/…"
_UPI_PATH_RE = re.compile(r"UPI/(?:P2[MA]/)?\d{6,}/([A-Za-z0-9 &._-]{2,40})",
                          re.IGNORECASE)
# ICICI style: "…; SWIGGY credited." names the payee before 'credited'.
_PAYEE_CREDITED_RE = re.compile(
    r"[;.]\s*([A-Za-z0-9][A-Za-z0-9 &._-]{1,40}?)\s+credited", re.IGNORECASE)

_DATE_RES = [
    re.compile(r"\bon\s+(\d{1,2}[-/][A-Za-z]{3,9}[-/]\d{2,4})", re.IGNORECASE),
    re.compile(r"\bon\s+(\d{1,2}[-/]\d{1,2}[-/]\d{2,4})", re.IGNORECASE),
    re.compile(r"\bon\s+(\d{1,2}[A-Za-z]{3}\d{2,4})", re.IGNORECASE),  # SBI 08Jul26
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


def _to_float(raw: str) -> Optional[float]:
    try:
        return float(raw.replace(",", ""))
    except ValueError:
        return None


def _parse_amount(text: str) -> Optional[float]:
    # Verb-anchored wins: it is unambiguously the transaction amount.
    m = _VERB_AMOUNT_RE.search(text)
    if m:
        return _to_float(m.group(1))
    # Otherwise take the first currency-marked amount that is NOT a balance.
    for m in _AMOUNT_RE.finditer(text):
        ctx = text[max(0, m.start() - 24):m.start()]
        if _BALANCE_CTX_RE.search(ctx):
            continue
        return _to_float(m.group(1))
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
                "%d-%m-%Y", "%d-%m-%y", "%d/%m/%Y", "%d/%m/%y",
                "%d%b%y", "%d%b%Y", "%Y-%m-%d"):
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


def _clean_merchant(raw: str) -> Optional[str]:
    name = raw.strip(" .,-/").split("@", 1)[0].strip()
    # Reject pure digits / account fragments ("X1234", "919…").
    if not name or not re.search(r"[A-Za-z]{2}", name):
        return None
    return name


def _parse_merchant(text: str, type_: str) -> Optional[str]:
    """Direction-aware payee/payer extraction, trying bank-specific shapes."""
    strategies = ([_TO_RE, _UPI_PATH_RE, _PAYEE_CREDITED_RE, _FROM_RE]
                  if type_ == "expense" else [_FROM_RE, _TO_RE, _UPI_PATH_RE])
    for rx in strategies:
        m = rx.search(text)
        if m:
            name = _clean_merchant(m.group(1))
            if name:
                return name
    return None


def parse_sms(text: str) -> ParsedSMS:
    text = (text or "").strip()
    result = ParsedSMS()
    if not text:
        return result
    result.amount = _parse_amount(text)
    result.type = ("income" if (_CREDIT_RE.search(text) and not _DEBIT_RE.search(text))
                   else "expense")
    result.raw_merchant = _parse_merchant(text, result.type)
    ref = _REF_RE.search(text)
    result.reference_number = ref.group(1) if ref else None
    result.occurred_at = _parse_date(text)
    # Only a message with an amount AND a money-movement verb AND no
    # promo/request/pre-debit language is a real transaction.
    result.matched = bool(result.amount is not None
                          and _TXN_VERB_RE.search(text)
                          and not _NON_TXN_RE.search(text))
    return result
