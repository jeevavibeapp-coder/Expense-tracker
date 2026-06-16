"""Unit tests for the deterministic SMS parser."""
from __future__ import annotations

from decimal import Decimal

from app.services.sms_parser import parse_sms


def test_debit_upi_sms():
    r = parse_sms("Rs.450.00 debited from a/c **1234 on 05-Jan-2024 to ZOMATO "
                  "Ref 883120114455 UPI")
    assert r.amount == Decimal("450.00")
    assert r.type == "expense"
    assert "ZOMATO" in (r.raw_merchant or "").upper()
    assert r.reference_number == "883120114455"
    assert r.occurred_at is not None and r.occurred_at.year == 2024


def test_credit_sms_is_income():
    r = parse_sms("INR 25,000.00 credited to your account from ACME PAYROLL on 01/02/2024")
    assert r.amount == Decimal("25000.00")
    assert r.type == "income"


def test_vpa_handle_stripped():
    r = parse_sms("Paid Rs 120 to suresh@okhdfcbank via UPI Ref ABC123XYZ")
    assert r.amount == Decimal("120")
    assert r.raw_merchant is not None and "@" not in r.raw_merchant


def test_non_transaction_sms():
    r = parse_sms("Your OTP is 4567. Do not share it with anyone.")
    assert r.matched is False
    assert r.amount is None
