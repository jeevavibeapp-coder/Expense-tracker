"""Bank-specific SMS parser registry.

The generic parser in ``parsing.py`` has to work for every institution at
once, which means its merchant patterns are necessarily loose — and loose
patterns are what produce "Proceed", "11-JUN-26" and half a sentence as
merchant names. It also cannot extract anything institution-specific, because
it does not know which institution sent the message.

Once the sender is identified (``senders.identify``) we DO know. This module
maps a DLT entity header to a small set of patterns written against that
bank's actual format, tried before the generic ones. A bank module can only
ever *refine* the generic result:

* it never decides whether a message is a transaction — that stays with the
  generic gate, so a bad bank pattern cannot inject false transactions;
* it may replace the generic MERCHANT, because a pattern written against a
  known format beats a pattern that must fit every bank at once — but the
  bank's candidate is put through exactly the same rejection rules, so it
  cannot smuggle a date or a call-to-action verb through;
* it only ever FILLS a missing reference number, never replaces one, since
  the generic reference pattern is the more conservative of the two;
* an unregistered bank loses nothing, because the generic path is unchanged.

That containment is deliberate. A registry that could veto or fabricate
transactions would turn every new bank entry into a potential data-integrity
bug; as built, the worst a wrong pattern can do is produce a merchant name
the user has to correct.

Adding a bank: append a ``BankProfile``. The formats below are taken from the
message shapes these banks actually send; amounts and account digits in any
examples are fictional.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Optional

REGISTRY_VERSION = "2026.07.1"


@dataclass
class BankProfile:
    """Patterns for one institution. Each regex must capture in group(1)."""
    entity: str                      # DLT header, e.g. HDFCBK
    name: str
    merchant_expense: list = field(default_factory=list)
    merchant_income: list = field(default_factory=list)
    account: list = field(default_factory=list)
    reference: list = field(default_factory=list)


def _rx(*patterns: str) -> list:
    return [re.compile(p, re.IGNORECASE) for p in patterns]


# Shared shapes. Indian banks converge on a handful of UPI/card phrasings, so
# these cover most institutions and a profile only lists what it does
# differently.
_ACCOUNT_GENERIC = _rx(
    r"\ba/?c\s*(?:no\.?\s*)?(?:x+|\*+)?(\d{3,6})\b",
    r"\bcard\s*(?:no\.?\s*)?(?:x+|\*+)(\d{3,6})\b",
    r"\baccount\s*(?:x+|\*+)(\d{3,6})\b",
)

PROFILES: dict[str, BankProfile] = {
    "HDFCBK": BankProfile(
        entity="HDFCBK", name="HDFC Bank",
        # "to VPA swiggy@ybl", "spent on Card XX at AMAZON on", "to MERCHANT on"
        merchant_expense=_rx(
            r"\bto\s+VPA\s+([A-Za-z0-9._-]+)@",
            r"\bat\s+([A-Za-z0-9][A-Za-z0-9 &._'-]{1,40}?)\s+on\s+\d",
            r"\bto\s+([A-Za-z0-9][A-Za-z0-9 &._'-]{1,40}?)\s+on\s+\d",
        ),
        merchant_income=_rx(r"\bby\s+VPA\s+([A-Za-z0-9._-]+)@",
                            r"\bfrom\s+([A-Za-z0-9][A-Za-z0-9 &._'-]{1,40}?)\s+on\s+\d"),
        account=_ACCOUNT_GENERIC,
        reference=_rx(r"\bRef\s*(?:no\.?)?\s*[:.]?\s*(\d{6,20})\b"),
    ),
    "ICICIB": BankProfile(
        entity="ICICIB", name="ICICI Bank",
        # "spent on ICICI Bank Card XX8890 at AMAZON on 11-Jul-26"
        merchant_expense=_rx(
            r"\bat\s+([A-Za-z0-9][A-Za-z0-9 &._'-]{1,40}?)\s+on\s+\d",
            r"\btowards\s+([A-Za-z0-9][A-Za-z0-9 &._'-]{1,40}?)[.;]",
            r";\s*([A-Za-z0-9][A-Za-z0-9 &._'-]{1,40}?)\s+credited",
        ),
        merchant_income=_rx(r"\bfrom\s+([A-Za-z0-9][A-Za-z0-9 &._'-]{1,40}?)[.;]"),
        account=_ACCOUNT_GENERIC,
        reference=_rx(r"\bIMPS\s*(?:Ref\s*no\.?)?\s*[:.]?\s*(\d{6,20})",
                      r"\bRef\s*(?:no\.?)?\s*[:.]?\s*(\d{6,20})\b"),
    ),
    "SBIINB": BankProfile(
        entity="SBIINB", name="State Bank of India",
        # "trf to MERCHANT Ref No 1234", "debited by 100 ... transfer to X"
        merchant_expense=_rx(
            r"\btrf\s+to\s+([A-Za-z0-9][A-Za-z0-9 &._'-]{1,40}?)\s+Ref",
            r"\btransfer\s+to\s+([A-Za-z0-9][A-Za-z0-9 &._'-]{1,40}?)\s+Ref",
            r"\bto\s+([A-Za-z0-9][A-Za-z0-9 &._'-]{1,40}?)\s+Ref",
        ),
        merchant_income=_rx(r"\bby\s+transfer\s+from\s+([A-Za-z0-9][A-Za-z0-9 &'-]{1,40}?)\s*(?:Ref|on\s+\d|\.|,|$)",
                            r"\bby\s+([A-Z][A-Z ]{2,30}?)[.\s]*(?:Ref|$)"),
        account=_ACCOUNT_GENERIC,
        reference=_rx(r"\bRef\s*(?:No\.?)?\s*[:.]?\s*(\d{6,20})\b"),
    ),
    "AXISBK": BankProfile(
        entity="AXISBK", name="Axis Bank",
        merchant_expense=_rx(
            r"\bto\s+([A-Za-z0-9][A-Za-z0-9 &._'-]{1,40}?)\s+(?:UPI|on|Info)",
            # Terminator is required: without it the greedy class ran past the
            # payee and captured "BLINKIT. Axis Bank".
            r"\bInfo[:\-\s]+(?:UPI/)?(?:P2[AM]/)?\d*/?([A-Za-z][A-Za-z0-9 &'-]{1,30}?)\s*(?:\.|,|$)",
        ),
        merchant_income=_rx(r"\bfrom\s+([A-Za-z0-9][A-Za-z0-9 &._'-]{1,40}?)\s+(?:UPI|on|Info)"),
        account=_ACCOUNT_GENERIC,
        reference=_rx(r"\bUPI/(?:P2[AM]/)?(\d{6,20})", r"\bRef\s*[:.]?\s*(\d{6,20})\b"),
    ),
    "KOTAKB": BankProfile(
        entity="KOTAKB", name="Kotak Mahindra Bank",
        merchant_expense=_rx(
            r"\bsent\s+to\s+([A-Za-z0-9][A-Za-z0-9 &._'-]{1,40}?)(?:\s+on|\.|,)",
            r"\bto\s+([A-Za-z0-9._-]+)@",
        ),
        merchant_income=_rx(r"\breceived\s+from\s+([A-Za-z0-9][A-Za-z0-9 &._'-]{1,40}?)(?:\s+on|\.|,)"),
        account=_ACCOUNT_GENERIC,
        reference=_rx(r"\bUPI\s*Ref\s*[:.]?\s*(\d{6,20})", r"\bRef\s*[:.]?\s*(\d{6,20})\b"),
    ),
    "PHONPE": BankProfile(
        entity="PHONPE", name="PhonePe",
        merchant_expense=_rx(r"\bpaid\s+(?:Rs\.?\s*[\d,.]+\s+)?to\s+([A-Za-z0-9][A-Za-z0-9 &._'-]{1,40}?)(?:\s+on|\.|,|$)"),
        merchant_income=_rx(r"\breceived\s+(?:Rs\.?\s*[\d,.]+\s+)?from\s+([A-Za-z0-9][A-Za-z0-9 &._'-]{1,40}?)(?:\s+on|\.|,|$)"),
        reference=_rx(r"\b(?:Txn|Transaction)\s*ID\s*[:.]?\s*([A-Za-z0-9]{8,30})"),
    ),
    "GPAYIN": BankProfile(
        entity="GPAYIN", name="Google Pay",
        merchant_expense=_rx(r"\bto\s+([A-Za-z0-9][A-Za-z0-9 &._'-]{1,40}?)(?:\s+using|\s+on|\.|,|$)"),
        merchant_income=_rx(r"\bfrom\s+([A-Za-z0-9][A-Za-z0-9 &._'-]{1,40}?)(?:\s+using|\s+on|\.|,|$)"),
        reference=_rx(r"\bUPI\s*(?:transaction\s*)?ID\s*[:.]?\s*([A-Za-z0-9]{8,30})"),
    ),
    "PYTMBK": BankProfile(
        entity="PYTMBK", name="Paytm Payments Bank",
        merchant_expense=_rx(r"\bto\s+([A-Za-z0-9][A-Za-z0-9 &._'-]{1,40}?)(?:\s+on|\.|,|$)"),
        merchant_income=_rx(r"\bfrom\s+([A-Za-z0-9][A-Za-z0-9 &._'-]{1,40}?)(?:\s+on|\.|,|$)"),
        account=_ACCOUNT_GENERIC,
        reference=_rx(r"\bUPI\s*Ref\s*(?:No\.?)?\s*[:.]?\s*(\d{6,20})"),
    ),
}

# Institutions that share a format with one already listed.
ALIASES = {
    "HDFCBN": "HDFCBK", "HDFCCC": "HDFCBK",
    "ICICIT": "ICICIB",
    "SBIUPI": "SBIINB", "SBIPSG": "SBIINB", "CBSSBI": "SBIINB", "SBICRD": "SBIINB",
    "AXISBN": "AXISBK",
    "PHNPAY": "PHONPE",
    "GOOGPY": "GPAYIN",
    "PAYTMB": "PYTMBK",
}


def profile_for(entity: Optional[str]) -> Optional[BankProfile]:
    """Look up a bank profile by DLT entity header, following aliases."""
    if not entity:
        return None
    key = entity.upper()
    return PROFILES.get(key) or PROFILES.get(ALIASES.get(key, ""))


def refine(profile: Optional[BankProfile], text: str, parsed,
           clean_merchant) -> dict:
    """Apply a bank's patterns on top of a generic parse.

    ``clean_merchant`` is injected rather than imported so this module has no
    dependency on parsing.py (which would be circular) — and so a bank pattern
    is still subject to the SAME rejection rules as the generic ones. A bank
    profile cannot smuggle "Proceed" or a date through as a merchant.

    Returns only the fields it actually improved, so the caller can tell what
    the registry contributed.
    """
    out: dict = {}
    if profile is None or not text:
        return out

    # Merchant. The bank's pattern WINS when it produces a name, because the
    # generic pattern has to fit every institution and consequently drags in
    # surrounding syntax: HDFC's "to VPA swiggy@ybl" generically yields
    # "VPA swiggy", and Axis's "Info: UPI/P2M/419988776655/BLINKIT" yields the
    # whole path. Those are not cosmetic — the merchant name is the merchant
    # engine's learning key, so "VPA swiggy" and "Swiggy" become two different
    # merchants the user has to categorise separately.
    #
    # The candidate still goes through the SAME clean_merchant rejection as a
    # generic match, so a bad bank pattern cannot introduce a merchant the
    # generic path would have refused.
    patterns = (profile.merchant_income if parsed.type == "income"
                else profile.merchant_expense)
    for rx in patterns:
        m = rx.search(text)
        if not m:
            continue
        name = clean_merchant(m.group(1))
        if name:
            out["raw_merchant"] = name
            break

    # Reference number: only fill a gap, never replace one the generic parser
    # already found (its pattern is the more conservative of the two).
    if not parsed.reference_number:
        for rx in profile.reference:
            m = rx.search(text)
            if m:
                out["reference_number"] = m.group(1)
                break

    # Account tail — a field the generic parser never extracted at all. Lets
    # the UI say "HDFC ...4521" instead of just "HDFC", and gives future
    # multi-account support something to key on.
    for rx in profile.account:
        m = rx.search(text)
        if m:
            out["account_tail"] = m.group(1)[-4:]
            break

    out["bank"] = profile.name
    return out
