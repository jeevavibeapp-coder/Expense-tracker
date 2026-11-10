"""Property-based and fuzz tests for the SMS parser.

The parser consumes UNTRUSTED input (any SMS on the device), so example-based
tests alone are insufficient: they only prove the cases we thought of. These
assert invariants that must hold for *all* inputs, and fuzz the parser with
adversarial junk to prove it can never crash the ingest path.
"""
from __future__ import annotations

import datetime as dt
import string

from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

from spendwise import engine, parsing

SLOW_OK = settings(max_examples=200, deadline=None,
                   suppress_health_check=[HealthCheck.too_slow])


# ── Total-function invariants ─────────────────────────────────────────────
@given(st.text())
@SLOW_OK
def test_parser_never_raises_on_arbitrary_text(body):
    """Any SMS on the device reaches this function; it must never throw."""
    r = parsing.parse_sms(body)
    assert isinstance(r.matched, bool)


@given(st.binary(max_size=400).map(lambda b: b.decode("utf-8", "replace")))
@SLOW_OK
def test_parser_survives_binary_garbage(body):
    """Malformed encodings / control bytes must not crash the parser."""
    parsing.parse_sms(body)


@given(st.text())
@SLOW_OK
def test_matched_implies_usable_transaction(body):
    """The core safety invariant: if we claim a transaction, it MUST carry a
    positive amount — otherwise the ledger silently corrupts."""
    r = parsing.parse_sms(body)
    if r.matched:
        assert r.amount is not None and r.amount > 0
        assert r.type in ("income", "expense")


@given(st.text())
@SLOW_OK
def test_normalisation_is_idempotent(body):
    once = parsing.normalize_text(body)
    assert parsing.normalize_text(once) == once


@given(st.text(min_size=1))
@SLOW_OK
def test_merchant_is_never_junk(body):
    """A captured merchant must never be a bare date, a pure number, or
    scraped prose — those produced the '11-JUN-26 ₹743' rows on a real device."""
    r = parsing.parse_sms(body)
    if r.matched and r.raw_merchant:
        m = r.raw_merchant.strip()
        assert m, "empty merchant"
        assert len(m.split()) <= 4, f"prose captured as merchant: {m!r}"
        assert not m.replace(",", "").replace(".", "").isdigit(), f"numeric: {m!r}"


# ── Amount handling ───────────────────────────────────────────────────────
@given(st.integers(min_value=1, max_value=9_999_999),
       st.sampled_from(["Rs.", "Rs ", "INR ", "₹"]))
@SLOW_OK
def test_amount_roundtrip_for_wellformed_debits(amount, prefix):
    """A canonical debit must parse back to exactly the amount sent."""
    body = (f"{prefix}{amount}.00 debited from a/c XX1234 on 08-07-26 to TESTMERCHANT "
            f"Ref 553201998877 UPI")
    r = parsing.parse_sms(body)
    assert r.matched is True
    assert r.amount == float(amount)


@given(st.integers(min_value=1, max_value=99_999))
@SLOW_OK
def test_balance_is_never_mistaken_for_the_amount(balance):
    """'Avl Bal' must not become the transaction amount (a real past bug)."""
    body = (f"Rs.100.00 debited from a/c XX1234 on 08-07-26 to SHOP "
            f"Ref 553201998877. Avl Bal Rs {balance}.00")
    r = parsing.parse_sms(body)
    assert r.matched is True and r.amount == 100.0


# ── Unicode robustness ────────────────────────────────────────────────────
@given(st.sampled_from(["​", "‎", "⁠", "﻿", " "]))
@SLOW_OK
def test_invisible_characters_do_not_break_parsing(ch):
    body = (f"Rs.450.00{ch} debited from a/c XX1234 on 08-07-26 to ZOMATO "
            f"Ref 553201998877 UPI")
    r = parsing.parse_sms(body)
    assert r.matched is True and r.amount == 450.0


def test_devanagari_and_fullwidth_digits_parse():
    assert parsing.parse_sms(
        "Rs.४५०.00 debited from a/c XX1234 on 08-07-26 to ZOMATO Ref 553201998877"
    ).amount == 450.0
    assert parsing.parse_sms(
        "Ｒｓ.450.00 debited from a/c XX1234 on 08-07-26 to ZOMATO Ref 553201998877"
    ).amount == 450.0


# ── Injection / adversarial input ─────────────────────────────────────────
@given(st.sampled_from([
    "'; DROP TABLE transactions;--",
    "<script>alert(1)</script>",
    "{{7*7}}",
    "../../etc/passwd",
    "%s%s%s%n",
    "\x00\x01\x02",
]))
@SLOW_OK
def test_injection_payloads_are_inert(payload):
    """Payloads must be treated as ordinary text, never interpreted."""
    r = parsing.parse_sms(
        f"Rs.100.00 debited from a/c XX1234 on 08-07-26 to {payload} Ref 553201998877")
    if r.raw_merchant:
        assert "DROP TABLE" not in r.raw_merchant.upper()


@given(st.text(alphabet=string.printable, min_size=200, max_size=2000))
@SLOW_OK
def test_long_messages_are_bounded(body):
    """A very long SMS must not hang the parser (ingest is on a request path)."""
    start = dt.datetime.now()
    parsing.parse_sms(body)
    assert (dt.datetime.now() - start).total_seconds() < 1.0


# ── Merchant normalisation invariants ─────────────────────────────────────
@given(st.text())
@SLOW_OK
def test_normalize_merchant_never_raises(raw):
    out = engine.normalize_merchant(raw)
    assert isinstance(out, str)
    assert out == out.strip()


@given(st.text(alphabet=string.ascii_letters, min_size=3, max_size=12))
@SLOW_OK
def test_vpa_suffix_variants_collapse_to_one_identity(name):
    """Handle variants must share a learning row, or the user re-teaches the
    same merchant forever."""
    base = engine.normalize_merchant(name)
    assert engine.normalize_merchant(f"{name}8@ybl") == base
    assert engine.normalize_merchant(f"{name}@okaxis") == base
