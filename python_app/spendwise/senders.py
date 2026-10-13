"""Sender verification and phishing defence for auto-captured SMS.

Why this exists
---------------
Until now the parser was the *only* gate: if a message contained an amount, a
money-movement verb and some account evidence, it became a transaction. That
is a content-only decision, and content is exactly what an attacker controls.
A message reading

    "Rs.4,999.00 debited from A/c XX8821 towards ICICI Bank. Not you? Call
     9812345678 to reverse immediately."

parses cleanly, lands in the ledger as a real expense, and hands the user a
phone number to call while they are alarmed. That is the standard Indian
"fake debit alert" fraud, and the parser cannot see it.

What an attacker cannot easily control is the *sender*. Transactional SMS in
India moves over TRAI's DLT rails: banks register a 6-character principal
entity header (HDFCBK, ICICIB, SBIINB...) and messages arrive as ``AD-HDFCBK``
— a two-character operator/route prefix, a hyphen, then the header. A real
bank never sends a transaction alert from a 10-digit personal mobile number.
So sender shape is a genuine, cheap signal, and it composes with content
indicators.

Design rules
------------
* **Never silently discard.** Every message reaches one of four terminal
  states: captured, captured-for-review, quarantined, or recorded as a parse
  miss. Nothing is dropped on the floor. A false positive here would mean a
  real transaction vanishing with no trace, which is worse than a bad row the
  user can delete.
* **Downgrade, don't delete.** A suspicious-but-parsable message becomes a
  low-confidence row needing review, not a silent rejection.
* **Learn from the user.** A sender the user repeatedly confirms becomes
  trusted; a sender they reject becomes blocked. The static allowlist is a
  cold-start prior, not the authority.
* **No network.** Everything here is local pattern analysis. There is no
  reputation service to call and none will be added.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Optional

# Bumped when scoring changes so stored assessments can be re-evaluated.
SENDER_MODEL_VERSION = "2026.07.1"

# ── Known principal entity headers ────────────────────────────────────────
# The 6-character DLT header registered by each institution. This is a
# cold-start prior only: an unknown header is "unknown", never "hostile", and
# the learned registry overrides it in both directions.
KNOWN_ENTITIES: dict[str, str] = {
    # Public sector banks
    "SBIINB": "State Bank of India", "SBIUPI": "State Bank of India",
    "SBICRD": "SBI Card", "SBIPSG": "State Bank of India",
    "PNBSMS": "Punjab National Bank", "CANBNK": "Canara Bank",
    "UNIONB": "Union Bank of India", "BOIIND": "Bank of India",
    "BOBTXN": "Bank of Baroda", "BOBSMS": "Bank of Baroda",
    "IOBCHN": "Indian Overseas Bank", "CBSSBI": "State Bank of India",
    "INDBNK": "Indian Bank", "UCOBNK": "UCO Bank",
    # Private sector banks
    "HDFCBK": "HDFC Bank", "HDFCBN": "HDFC Bank",
    "ICICIB": "ICICI Bank", "ICICIT": "ICICI Bank",
    "AXISBK": "Axis Bank", "AXISBN": "Axis Bank",
    "KOTAKB": "Kotak Mahindra Bank", "INDUSB": "IndusInd Bank",
    "YESBNK": "Yes Bank", "IDFCFB": "IDFC First Bank",
    "RBLBNK": "RBL Bank", "FEDBNK": "Federal Bank",
    "SIBSMS": "South Indian Bank", "KVBANK": "Karur Vysya Bank",
    "CITIBK": "Citibank", "SCBANK": "Standard Chartered",
    "HSBCIN": "HSBC India", "DBSBNK": "DBS Bank",
    "AUBANK": "AU Small Finance Bank", "BANDHN": "Bandhan Bank",
    # Payments banks / wallets / UPI apps
    "PYTMBK": "Paytm Payments Bank", "PAYTMB": "Paytm",
    "PHONPE": "PhonePe", "PHNPAY": "PhonePe",
    "GPAYIN": "Google Pay", "GOOGPY": "Google Pay",
    "AMZNPY": "Amazon Pay", "BHIMUP": "BHIM UPI",
    "AIRTLB": "Airtel Payments Bank", "JIOPAY": "Jio Payments Bank",
    "MOBIKW": "MobiKwik", "FREECH": "Freecharge",
    # Cards / NBFCs
    "ONECRD": "OneCard", "SLICEI": "Slice", "BAJFIN": "Bajaj Finance",
    "HDFCCC": "HDFC Credit Card", "AMEXIN": "American Express",
}

# ── Sender shape ──────────────────────────────────────────────────────────
# DLT transactional header: two-letter operator/route prefix, hyphen, then the
# 6-char principal entity. Some circles emit a trailing "-S"/"-T" route tag.
_DLT_RE = re.compile(r"^([A-Z]{2})-([A-Z0-9]{5,8})(?:-[A-Z])?$")
# Header without the operator prefix — common on older handsets/CDMA stacks.
_BARE_HEADER_RE = re.compile(r"^([A-Z]{5,8})$")
# Indian mobile: optional +91/0, then 6-9 followed by nine digits.
_MOBILE_RE = re.compile(r"^(?:\+?91|0)?([6-9]\d{9})$")
# Short codes (121, 51969, ...) — operator/service, not a bank transaction rail.
_SHORTCODE_RE = re.compile(r"^\d{3,8}$")
# Whitespace plus the Unicode invisibles a spoofed sender pads an ID with:
# ZWSP/ZWNJ/ZWJ and the LTR/RTL marks (U+200B-U+200F), the bidi embedding
# controls (U+202A-U+202E), the word joiner (U+2060) and the BOM (U+FEFF).
# Written as escapes rather than literal characters so the class is
# reviewable in a diff — the whole point is that these are invisible.
_SENDER_NOISE_RE = re.compile(
    "[\\s\\u200b-\\u200f\\u202a-\\u202e\\u2060\\ufeff]")

TRUST_TRUSTED = "trusted"
TRUST_KNOWN = "known"
TRUST_UNKNOWN = "unknown"
TRUST_SUSPICIOUS = "suspicious"
TRUST_BLOCKED = "blocked"

ACTION_ACCEPT = "accept"
ACTION_REVIEW = "review"
ACTION_QUARANTINE = "quarantine"


def normalize_sender(raw: Optional[str]) -> str:
    """Canonical form used as the registry key.

    Uppercased, whitespace and punctuation-noise stripped, mobile numbers
    reduced to their ten national digits so ``+919812345678``,
    ``09812345678`` and ``9812345678`` are one sender rather than three.
    """
    s = (raw or "").strip().upper()
    # Strip whitespace and the Unicode invisibles (ZWSP, ZWNJ/ZWJ, bidi marks
    # and embedding controls) that spoofed senders use to break exact matching.
    # The hyphen is deliberately preserved — it separates the DLT operator
    # prefix from the entity header and removing it would destroy the shape
    # this module classifies on.
    s = _SENDER_NOISE_RE.sub("", s)
    m = _MOBILE_RE.match(s)
    if m:
        return m.group(1)
    return s


@dataclass
class SenderIdentity:
    raw: str
    normalized: str
    kind: str                       # dlt | header | mobile | shortcode | other
    entity: Optional[str] = None    # 6-char DLT header when present
    bank: Optional[str] = None      # institution name when recognised
    base_score: int = 0             # 0..100 prior trust from shape alone
    reasons: list[str] = field(default_factory=list)


def identify(raw: Optional[str]) -> SenderIdentity:
    """Classify a sender by shape alone — no user history involved."""
    norm = normalize_sender(raw)
    ident = SenderIdentity(raw=(raw or ""), normalized=norm, kind="other")
    if not norm:
        # SMS with no originating address at all. Some OEM stacks lose it on
        # multipart messages. Crucially this is NOT attacker-controlled — an
        # attacker sends from some number and cannot make the field vanish —
        # so absence carries no hostile signal and must not downgrade an
        # otherwise-clean message. Content risk still governs below.
        ident.kind = "missing"
        ident.base_score = 70
        ident.reasons.append("sender_missing")
        return ident

    m = _DLT_RE.match(norm)
    if m:
        ident.kind = "dlt"
        ident.entity = m.group(2)
        ident.bank = KNOWN_ENTITIES.get(ident.entity)
        if ident.bank:
            ident.base_score = 90
            ident.reasons.append("dlt_known_entity")
        else:
            # Correct rails, unrecognised institution. India has ~1,500
            # registered entities and this list holds ~50, so an unknown
            # header is overwhelmingly a real bank we simply do not list.
            # Scored as accept-worthy on its own: sending a DLT-shaped header
            # requires a registered entity, and treating every unlisted bank
            # as suspect would push most users' real transactions into review
            # — the exact review-queue flood this app already had to fix.
            ident.base_score = 75
            ident.reasons.append("dlt_unknown_entity")
        return ident

    m = _BARE_HEADER_RE.match(norm)
    if m:
        ident.kind = "header"
        ident.entity = m.group(1)
        ident.bank = KNOWN_ENTITIES.get(ident.entity)
        ident.base_score = 75 if ident.bank else 55
        ident.reasons.append("header_known_entity" if ident.bank
                             else "header_unknown_entity")
        return ident

    if _MOBILE_RE.match(norm) or re.fullmatch(r"[6-9]\d{9}", norm):
        ident.kind = "mobile"
        ident.base_score = 10
        # This is the single strongest structural signal available. Indian
        # banks cannot send transactional SMS from a personal number: DLT
        # forbids it. A "bank alert" from a mobile is a forgery or, at best,
        # a person forwarding one.
        ident.reasons.append("personal_mobile_number")
        return ident

    if _SHORTCODE_RE.match(norm):
        ident.kind = "shortcode"
        ident.base_score = 30
        ident.reasons.append("numeric_shortcode")
        return ident

    ident.base_score = 35
    ident.reasons.append("unrecognised_sender_format")
    return ident


# ── Content-based phishing indicators ─────────────────────────────────────
# Each entry: (indicator name, compiled pattern, risk weight).
# Weights are additive and capped at 100. They were chosen so that any single
# indicator alone cannot quarantine a message — it takes either one severe
# signal plus a weak sender, or two independent signals.
_INDICATORS: list[tuple[str, re.Pattern, int]] = [
    # Callback numbers. A genuine bank alert DOES say "Not you? Call
    # 18002586161" — a toll-free line printed on the card. The fraud says
    # "call 9812345678". So the discriminator is the number FORM, not the
    # instruction to call. (?<!\d)/(?!\d) matter: without them the toll-free
    # 18002586161 matches [6-9]\d{9} on the substring 8002586161 and every
    # real HDFC alert is flagged — measured at risk 95 before this guard.
    ("callback_mobile_number",
     re.compile(r"\b(?:call|contact|dial|whatsapp|sms)\b[^.\n]{0,40}?"
                r"(?<!\d)(?:\+?91[\-\s]?)?[6-9]\d{9}(?!\d)", re.I), 45),
    # URL shorteners: the defining feature of SMS phishing — they hide the
    # destination inside a 160-character limit.
    ("url_shortener",
     re.compile(r"\b(?:bit\.ly|tinyurl|t\.co|goo\.gl|rb\.gy|cutt\.ly|is\.gd|"
                r"ow\.ly|shorturl|tiny\.cc|rebrand\.ly)\b", re.I), 40),
    # Direct APK delivery — always malware on this rail.
    ("apk_download", re.compile(r"\.apk\b|download.{0,20}app.{0,20}http", re.I), 60),
    # Credential / KYC harvesting.
    ("credential_request",
     re.compile(r"\b(?:enter|share|send|confirm|update|verify)\b[^.\n]{0,30}"
                r"\b(?:otp|pin|cvv|password|mpin|upi\s*pin|card\s*(?:no|number)|"
                r"aadha?ar|pan\s*card|kyc)\b", re.I), 55),
    # Manufactured urgency + account-loss threat.
    ("account_block_threat",
     re.compile(r"\b(?:account|a/?c|card|sim|kyc)\b[^.\n]{0,30}"
                r"\b(?:will\s+be\s+)?(?:block|suspend|deactivat|freez|expir|"
                r"clos)\w*", re.I), 40),
    ("urgency_pressure",
     re.compile(r"\b(?:immediately|within\s+\d+\s*(?:hour|hrs|minute|min)|"
                r"urgent(?:ly)?|last\s+chance|act\s+now|expires\s+today)\b", re.I), 20),
    # "Not you? Reverse it" — the hook that makes the victim call. Weighted
    # LOW on purpose: HDFC, ICICI and SBI all ship "Not you? Call <toll-free>"
    # in genuine alerts, so this is only meaningful in combination with a
    # mobile callback. Weighting it as a strong signal quarantined every real
    # HDFC message in testing.
    ("reversal_bait",
     re.compile(r"\b(?:if\s+)?not\s+(?:you|done\s+by\s+you)\b|"
                r"\breverse\s+(?:this|the)?\s*(?:transaction|txn|payment)\b|"
                r"\bto\s+cancel\b[^.\n]{0,30}\bcall\b", re.I), 15),
    # Prize/refund lures wrapped around a credit alert.
    ("prize_or_refund_lure",
     re.compile(r"\b(?:you\s+have\s+won|lucky\s+winner|cash\s*prize|lottery|"
                r"claim\s+your\s+(?:refund|reward|cashback)|congratulations)\b", re.I), 45),
    # Any bare link at all is mild on its own — plenty of real bank messages
    # link to a statement — but it compounds with the signals above.
    ("contains_link", re.compile(r"https?://|\bwww\.", re.I), 12),
    # Non-institutional TLDs / free hosting used to imitate a bank portal.
    ("suspicious_domain",
     re.compile(r"https?://[^\s]*\.(?:xyz|top|club|online|site|icu|buzz|link|"
                r"tk|ml|ga|cf|gq|ru|cn)\b", re.I), 45),
    # Requests to send money out — an alert never asks for this.
    ("payment_request",
     re.compile(r"\b(?:pay|send|transfer|deposit)\b[^.\n]{0,25}"
                r"(?:rs\.?|inr|₹)\s*[\d,]+[^.\n]{0,25}"
                r"\b(?:to\s+(?:this|the\s+following)|immediately|now)\b", re.I), 40),
]

# A message claiming to be from a bank while arriving from a personal mobile
# is treated as a compound indicator, because neither half is conclusive alone.
_BANK_CLAIM_RE = re.compile(
    r"\b(?:bank|a/?c|account|upi|atm|debit\s*card|credit\s*card|netbanking)\b", re.I)


@dataclass
class RiskAssessment:
    sender: SenderIdentity
    risk: int                          # 0..100 content risk
    indicators: list[str]
    trust: str                         # trusted|known|unknown|suspicious|blocked
    action: str                        # accept|review|quarantine
    confidence_delta: int              # added to merchant confidence (<= 0)
    reasons: list[str]

    def as_dict(self) -> dict:
        return {"sender": self.sender.normalized, "kind": self.sender.kind,
                "bank": self.sender.bank, "risk": self.risk,
                "indicators": list(self.indicators), "trust": self.trust,
                "action": self.action, "confidence_delta": self.confidence_delta,
                "reasons": list(self.reasons),
                "model_version": SENDER_MODEL_VERSION}


def phishing_indicators(body: str) -> tuple[int, list[str]]:
    """Return (risk 0..100, indicator names) for a message body."""
    text = body or ""
    risk = 0
    found: list[str] = []
    for name, pattern, weight in _INDICATORS:
        if pattern.search(text):
            found.append(name)
            risk += weight
    return min(risk, 100), found


def assess(sender: Optional[str], body: str,
           registry: Optional[dict] = None) -> RiskAssessment:
    """Combine sender shape, learned trust and content risk into one verdict.

    ``registry`` is the stored row for this sender (or None if never seen):
    ``{"trust": ..., "confirmed_count": int, "quarantined_count": int}``.
    A user decision always wins over the heuristics — they can see things the
    patterns cannot, and overriding them would make the app untrustworthy.
    """
    ident = identify(sender)
    risk, indicators = phishing_indicators(body or "")
    reasons = list(ident.reasons)

    # A bank claim from a personal mobile is the classic forgery shape.
    if ident.kind == "mobile" and _BANK_CLAIM_RE.search(body or ""):
        risk = min(100, risk + 35)
        indicators.append("bank_claim_from_mobile")

    stored_trust = (registry or {}).get("trust")
    confirmed = int((registry or {}).get("confirmed_count") or 0)

    # 1. Explicit user decisions are final.
    if stored_trust == TRUST_BLOCKED:
        return RiskAssessment(ident, risk, indicators, TRUST_BLOCKED,
                              ACTION_QUARANTINE, -100,
                              reasons + ["user_blocked_sender"])
    if stored_trust == TRUST_TRUSTED:
        # Still quarantine on a severe content signal: a trusted header can be
        # spoofed by an SMS gateway, and "the user trusted this sender once"
        # must not become a blanket bypass of phishing detection.
        if risk >= 85:
            return RiskAssessment(ident, risk, indicators, TRUST_TRUSTED,
                                  ACTION_QUARANTINE, -60,
                                  reasons + ["trusted_sender_severe_content_risk"])
        return RiskAssessment(ident, risk, indicators, TRUST_TRUSTED,
                              ACTION_REVIEW if risk >= 45 else ACTION_ACCEPT,
                              -20 if risk >= 45 else 0,
                              reasons + ["user_trusted_sender"])

    # 2. Otherwise combine the structural prior with observed history.
    score = ident.base_score + min(confirmed * 5, 25)
    if confirmed >= 3:
        reasons.append("history_confirmed_transactions")

    # 3. Content risk pulls the effective trust down.
    effective = score - risk

    if risk >= 70 or (risk >= 50 and ident.base_score < 60):
        trust, action, delta = TRUST_SUSPICIOUS, ACTION_QUARANTINE, -100
        reasons.append("high_content_risk")
    elif effective >= 70:
        trust = TRUST_KNOWN if ident.bank else TRUST_UNKNOWN
        action, delta = ACTION_ACCEPT, 0
    elif effective >= 30:
        trust = TRUST_UNKNOWN
        action, delta = ACTION_REVIEW, -25
        reasons.append("unverified_sender_needs_review")
    else:
        trust, action, delta = TRUST_SUSPICIOUS, ACTION_QUARANTINE, -100
        reasons.append("low_sender_trust")

    return RiskAssessment(ident, risk, indicators, trust, action, delta, reasons)


def explain(assessment: RiskAssessment) -> str:
    """One-line, user-facing explanation. Shown next to quarantined messages
    so the decision is never a black box the user has to just accept."""
    bits = []
    ident = assessment.sender
    if ident.bank:
        bits.append(f"Sender matches {ident.bank}")
    elif ident.kind == "mobile":
        bits.append("Sent from a personal mobile number, not a bank sender ID")
    elif ident.kind == "dlt":
        bits.append(f"Registered sender ID ({ident.entity}) we don't recognise")
    elif ident.kind == "missing":
        bits.append("Message arrived with no sender")
    else:
        bits.append("Unrecognised sender format")
    labels = {
        "callback_mobile_number": "asks you to call a mobile number",
        "url_shortener": "contains a shortened link",
        "apk_download": "links to an app download",
        "credential_request": "asks for an OTP, PIN or KYC details",
        "account_block_threat": "threatens to block your account",
        "urgency_pressure": "pressures you to act immediately",
        "reversal_bait": "offers to reverse the transaction if you call",
        "prize_or_refund_lure": "promises a prize or refund",
        "suspicious_domain": "links to an untrusted domain",
        "payment_request": "asks you to send money",
        "contains_link": "contains a link",
        "bank_claim_from_mobile": "claims to be a bank but came from a mobile",
    }
    named = [labels[i] for i in assessment.indicators if i in labels]
    if named:
        bits.append("and it " + ", ".join(named))
    return "; ".join(bits) + "."
