"""Deterministic SMS / UPI transaction parser (standard library only).

Tuned against real Indian bank & UPI formats: HDFC, SBI ("debited by 199.0",
no Rs prefix), ICICI ("; SWIGGY credited."), Axis ("UPI/P2M/<ref>/ZOMATO/…"),
Kotak ("Sent Rs.20.00 from Kotak Bank AC X1234 to swiggy8@ybl"), PhonePe /
GPay / Paytm UPI alerts, card alerts and EMI debits.
"""
from __future__ import annotations

import datetime as dt
import re
import unicodedata
from dataclasses import dataclass
from typing import Optional

# Bumped whenever matching behaviour changes, so parse-miss records can be
# attributed to the parser that produced them and re-evaluated after a fix.
PARSER_VERSION = "2025.11.1"

# Indian-script digits seen in regional-language bank SMS. Unicode NFKC folds
# most, but these are mapped explicitly so amounts survive normalisation.
_DIGIT_MAP = {
    ord("०"): "0", ord("१"): "1", ord("२"): "2", ord("३"): "3", ord("४"): "4",
    ord("५"): "5", ord("६"): "6", ord("७"): "7", ord("८"): "8", ord("९"): "9",
    ord("௦"): "0", ord("௧"): "1", ord("௨"): "2", ord("௩"): "3", ord("௪"): "4",
    ord("௫"): "5", ord("௬"): "6", ord("௭"): "7", ord("௮"): "8", ord("௯"): "9",
    ord("౦"): "0", ord("౧"): "1", ord("౨"): "2", ord("౩"): "3", ord("౪"): "4",
    ord("౫"): "5", ord("౬"): "6", ord("౭"): "7", ord("౮"): "8", ord("౯"): "9",
}
# Zero-width and directional marks that banks/telcos inject and that silently
# break otherwise-correct regexes.
_INVISIBLE_RE = re.compile(r"[\u200b-\u200f\u202a-\u202e\u2060\ufeff]")


def normalize_text(text: str) -> str:
    """Canonicalise a message before any pattern is applied.

    NFKC folds full-width/compatibility forms (e.g. ￥, ｒｓ) to their ASCII
    equivalents; Indian-script digits are mapped to ASCII; invisible marks are
    stripped; runs of whitespace (including NBSP) collapse. Without this a
    perfectly ordinary message can fail to parse for invisible reasons.
    """
    if not text:
        return ""
    text = unicodedata.normalize("NFKC", text)
    text = text.translate(_DIGIT_MAP)
    text = _INVISIBLE_RE.sub("", text)
    text = text.replace("\u00a0", " ")
    return re.sub(r"[ \t]+", " ", text).strip()

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
    r"declined|failed|reversed|refund initiated|otp|one.?time password|"
    # Marketing / lending / scam vocabulary — these carry amounts and
    # sometimes even transaction verbs, but no money has moved.
    r"credit score|cibil|pre-?approved|pre-?qualified|eligible for|"
    r"loan offer|personal loan|instant loan|apply now|click here|"
    r"congratulations|you have won|claim now|lucky (?:winner|draw)|"
    r"limited period|hurry|t&c apply|terms and conditions apply|"
    r"unsubscribe|click .{0,12}(?:link|bit\.ly|tinyurl)|bit\.ly|tinyurl|"
    r"verify (?:your )?kyc|kyc (?:update|pending|expired)|will be blocked|"
    r"account will be (?:blocked|suspended|closed)|"
    r"bill (?:is )?generated|statement (?:is )?generated|minimum amount due|"
    r"total amount due|outstanding|emi of|recharge|plan validity|"
    r"interest rate|low interest|no documents|approved)",
    re.IGNORECASE)

# Positive evidence that this is a REAL bank/UPI transaction: essentially every
# genuine debit/credit alert cites an account, card, UPI handle or reference.
# Promotional and scam messages carry amounts but never this. Requiring it is
# the single highest-precision signal available offline.
_ACCOUNT_EVIDENCE_RE = re.compile(
    r"(a/c|a/c no|acct|account\s*(?:no|number|xx|\*|ending)|"
    r"\bac\b\s*[xX*\d]|card\s*(?:no\.?\s*)?[xX*\d]|"
    r"\bupi\b|\bvpa\b|@[a-z]{2,}|"
    r"ref(?:erence)?\s*(?:no|number|id)?[:\s.#-]*[A-Za-z0-9]{6,}|"
    r"\butr\b|\btxn\b|transaction\s*id|\bimps\b|\bneft\b|\brtgs\b|"
    r"[xX*]{2,}\d{3,}|\d{4,}\b(?=\s*(?:on|dated)))",
    re.IGNORECASE)

# Merchant strings that are obviously not a merchant: bare dates, generic
# call-to-action verbs, and marketing phrases the payee regex can latch onto.
_BAD_MERCHANT_RE = re.compile(
    r"^(?:\d{1,2}[-/][A-Za-z]{3,9}[-/]\d{2,4}|\d{1,2}[-/]\d{1,2}[-/]\d{2,4}|"
    r"\d[\d,.]*|"
    r"(?:proceed|continue|click|apply|claim|verify|confirm|know more|"
    r"activate|register|download|login|update|check|call|contact|"
    r"low interest|no documents|improve.*|your .*score.*|avail.*|get .*)"
    r")$", re.IGNORECASE)

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
    # Filled in by the bank registry when the sender is recognised.
    bank: Optional[str] = None
    account_tail: Optional[str] = None
    parsed_by: str = "generic"


# Upper bound on a single transaction, in rupees. Generous to the point of
# absurdity (1 lakh crore) — the goal is not to second-guess the user, it is
# to guarantee the value is finite and survives int(amount * 100) without
# overflowing, which is what every downstream money calculation assumes.
MAX_AMOUNT = 1e12


def safe_amount(value) -> Optional[float]:
    """Return ``value`` as a usable rupee amount, or None if it is not one.

    Rejects NaN, +/-Infinity and anything outside (0, MAX_AMOUNT].

    This exists because ``float()`` accepts far more than money: a 400-digit
    string becomes ``inf``, "nan" becomes NaN, and both compare as > 0 so they
    passed the transaction gate. A stored ``inf`` then propagated into
    ``detect_transfers``, where ``int(round(amount * 100))`` raised
    OverflowError and permanently 500'd the dashboard — a denial of service
    triggerable by anyone who can send the device an SMS.
    """
    try:
        f = float(value)
    except (TypeError, ValueError, OverflowError):
        return None
    # NaN != NaN, and both infinities are excluded by the range check that
    # follows; this comparison is written to be explicit about NaN.
    if f != f:
        return None
    if not (0 < f <= MAX_AMOUNT):
        return None
    return f


def _to_float(raw: str) -> Optional[float]:
    try:
        return safe_amount(raw.replace(",", ""))
    except (AttributeError, ValueError):
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
    # Reject dates, call-to-action verbs and marketing phrases the payee
    # regex can latch onto ("11-JUN-26", "Proceed", "improve your credit score").
    if _BAD_MERCHANT_RE.match(name):
        return None
    # A real payee is short. Anything sentence-like is scraped prose.
    if len(name.split()) > 4:
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


def parse_sms(text: str, sender: Optional[str] = None) -> ParsedSMS:
    """Parse a finance SMS.

    ``sender`` is optional and only ever *improves* the result: it selects a
    bank-specific profile whose patterns are tried for fields the generic
    parser could not fill. It can never change whether a message counts as a
    transaction — that gate stays entirely generic below, so a wrong bank
    pattern cannot inject a false transaction into the ledger.
    """
    # Normalise first so Unicode/format noise cannot defeat the patterns.
    text = normalize_text(text or "")
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
    # A real transaction needs ALL of: an amount, a money-movement verb, no
    # promo/request/pre-debit language, and positive account evidence (an
    # account/card/UPI/reference). The last condition is what keeps
    # promotional and scam messages — which happily carry amounts and even
    # verbs — out of the user's ledger.
    result.matched = bool(result.amount is not None
                          and result.amount > 0
                          and _TXN_VERB_RE.search(text)
                          and not _NON_TXN_RE.search(text)
                          and _ACCOUNT_EVIDENCE_RE.search(text))

    # Bank-specific refinement. Runs AFTER the match gate so it can only ever
    # enrich a message already accepted as a transaction.
    if result.matched and sender:
        from . import banks, senders
        profile = banks.profile_for(senders.identify(sender).entity)
        extra = banks.refine(profile, text, result, _clean_merchant)
        if extra:
            if extra.get("raw_merchant"):
                result.raw_merchant = extra["raw_merchant"]
                result.parsed_by = profile.entity
            if extra.get("reference_number"):
                result.reference_number = extra["reference_number"]
            result.account_tail = extra.get("account_tail")
            result.bank = extra.get("bank")
    return result
