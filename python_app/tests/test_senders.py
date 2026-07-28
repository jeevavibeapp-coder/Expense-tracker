"""Sender verification, phishing defence and the quarantine workflow.

The parser is a content-only gate, and content is what an attacker controls.
These tests pin the behaviour that the sender-based gate adds on top:

  * a real bank alert from a real bank header still sails straight through
    (a security control that blocks genuine transactions is a regression, not
    a feature, and this is the case most at risk of over-tightening);
  * the classic Indian fake-debit fraud is held, not captured;
  * NOTHING is ever silently discarded.
"""
from __future__ import annotations

import json
import sqlite3

import pytest

from spendwise import db, senders
from spendwise.app import create_app

TOKEN = "test-device-token"

# Real-shaped messages. Amounts and account digits are fictional.
GENUINE_HDFC = ("Rs.450.00 debited from a/c XX4521 on 12-07-26 to VPA "
                "swiggy@ybl. Ref 402198877123. Not you? Call 18002586161. -HDFC Bank")
GENUINE_ICICI = ("INR 1,299.00 spent on ICICI Bank Card XX8890 at AMAZON on "
                 "11-Jul-26. Avl Lmt INR 48,701.00.")
GENUINE_SBI_CREDIT = ("Dear Customer, INR 52,000.00 credited to A/c XX1123 on "
                      "01-07-26 by SALARY. Ref 998877665544 -SBI")

# The fraud this module exists to stop: parses perfectly, comes from a mobile.
FAKE_DEBIT_ALERT = ("Rs.4,999.00 debited from A/c XX8821 towards ICICI Bank. "
                    "Ref 887766554433. If not you, call 9812345678 immediately "
                    "to reverse this transaction.")
PHISH_KYC = ("Dear customer your A/c XX2211 will be blocked today. INR 1.00 "
             "debited. Update KYC immediately at http://icici-verify.xyz/kyc "
             "or share OTP with our agent 9876543210.")


def _client(tmp_path, name="q.db"):
    app = create_app(db_path=str(tmp_path / name), single_user=True,
                     secret_key="s", device_token=TOKEN)
    c = app.test_client()
    # Authenticate exactly as the WebView does: a one-time ?k=<device token>
    # grant on the first navigation, which mints the signed session cookie.
    c.get(f"/?k={TOKEN}", environ_overrides={"REMOTE_ADDR": "127.0.0.1"})
    return app, c


def _ingest(c, sender, body):
    return c.post("/sms/ingest", data={"sender": sender, "body": body},
                  headers={"X-SpendWise-Token": TOKEN},
                  environ_overrides={"REMOTE_ADDR": "127.0.0.1"})


# ── Sender shape classification ───────────────────────────────────────────
@pytest.mark.parametrize("raw,kind,bank", [
    ("AD-HDFCBK", "dlt", "HDFC Bank"),
    ("VM-ICICIB", "dlt", "ICICI Bank"),
    ("JD-SBIINB", "dlt", "State Bank of India"),
    ("BP-AXISBK", "dlt", "Axis Bank"),
    ("AX-PHONPE", "dlt", "PhonePe"),
    ("HDFCBK", "header", "HDFC Bank"),
    ("+919812345678", "mobile", None),
    ("9812345678", "mobile", None),
    ("121", "shortcode", None),
])
def test_sender_shapes_are_classified(raw, kind, bank):
    ident = senders.identify(raw)
    assert ident.kind == kind
    assert ident.bank == bank


def test_mobile_numbers_normalise_to_one_identity():
    """+91 / 0 / bare prefixes must not create three separate senders — a
    blocked sender that reappears in another form is not blocked."""
    forms = ["+919812345678", "919812345678", "09812345678", "9812345678",
             " +91 98123 45678 "]
    assert {senders.normalize_sender(f) for f in forms} == {"9812345678"}


def test_zero_width_padding_cannot_forge_a_bank_header():
    """A spoofer pads the ID with invisibles so exact matching fails. It must
    normalise to the same identity, not slip through as a new sender."""
    assert senders.normalize_sender("AX-​HDFC‍BK") == "AX-HDFCBK"
    assert senders.identify("AX-​HDFC‍BK").bank == "HDFC Bank"


def test_unknown_dlt_header_is_neutral_not_hostile():
    """India has ~1,500 registered entities; the built-in list has ~50. An
    unlisted header is overwhelmingly a real bank, so it must not be treated
    as an attack."""
    ident = senders.identify("AD-ZZZBNK")
    assert ident.kind == "dlt"
    assert ident.bank is None
    assert ident.base_score >= 50


# ── Phishing indicators ───────────────────────────────────────────────────
def test_genuine_bank_messages_score_low_risk():
    for body in (GENUINE_HDFC, GENUINE_ICICI, GENUINE_SBI_CREDIT):
        risk, _ = senders.phishing_indicators(body)
        assert risk < 45, f"genuine message scored {risk}: {body[:40]}"


def test_fake_debit_alert_indicators_are_detected():
    risk, found = senders.phishing_indicators(FAKE_DEBIT_ALERT)
    assert "callback_mobile_number" in found
    assert "reversal_bait" in found
    assert risk >= 70


def test_kyc_phish_indicators_are_detected():
    risk, found = senders.phishing_indicators(PHISH_KYC)
    assert "credential_request" in found
    assert "account_block_threat" in found
    assert "suspicious_domain" in found
    assert risk >= 70


def test_a_single_weak_indicator_cannot_quarantine_a_bank_message():
    """Plenty of real bank SMS contain a link. One weak signal from a known
    bank header must not be enough to hold the message."""
    body = "Rs.200.00 debited from a/c XX1234. View statement at https://hdfcbank.com/stmt"
    a = senders.assess("AD-HDFCBK", body)
    assert a.action == senders.ACTION_ACCEPT


# ── Combined assessment ───────────────────────────────────────────────────
def test_genuine_bank_alert_from_known_header_is_accepted():
    a = senders.assess("AD-HDFCBK", GENUINE_HDFC)
    assert a.action == senders.ACTION_ACCEPT
    assert a.trust == senders.TRUST_KNOWN
    assert a.confidence_delta == 0


def test_fake_debit_alert_from_a_mobile_is_quarantined():
    a = senders.assess("+919812345678", FAKE_DEBIT_ALERT)
    assert a.action == senders.ACTION_QUARANTINE
    assert "bank_claim_from_mobile" in a.indicators


def test_unknown_sender_downgrades_rather_than_blocks():
    """An unremarkable message from a sender we can't place is captured with
    reduced confidence for review — not thrown away."""
    a = senders.assess("AD-ZZZBNK", GENUINE_ICICI)
    assert a.action in (senders.ACTION_ACCEPT, senders.ACTION_REVIEW)
    assert a.action != senders.ACTION_QUARANTINE


def test_user_block_overrides_a_perfectly_shaped_sender():
    a = senders.assess("AD-HDFCBK", GENUINE_HDFC, {"trust": "blocked"})
    assert a.action == senders.ACTION_QUARANTINE


def test_user_trust_does_not_become_a_blanket_phishing_bypass():
    """A trusted header can still be spoofed by an SMS gateway. Severe content
    risk must survive the user's trust decision."""
    a = senders.assess("AD-HDFCBK", PHISH_KYC, {"trust": "trusted"})
    assert a.action == senders.ACTION_QUARANTINE


def test_confirmation_history_raises_trust():
    low = senders.assess("AD-ZZZBNK", GENUINE_ICICI, {"trust": "unknown",
                                                      "confirmed_count": 0})
    high = senders.assess("AD-ZZZBNK", GENUINE_ICICI, {"trust": "unknown",
                                                       "confirmed_count": 8})
    assert high.confidence_delta >= low.confidence_delta


def test_explanation_is_human_readable_and_names_the_reason():
    a = senders.assess("+919812345678", FAKE_DEBIT_ALERT)
    text = senders.explain(a)
    assert "personal mobile" in text
    assert "call a mobile number" in text


# ── End-to-end through /sms/ingest ────────────────────────────────────────
def test_genuine_message_is_still_captured_end_to_end(tmp_path):
    """The control must not break the feature. This is the regression that
    would matter most to the user."""
    _, c = _client(tmp_path)
    r = _ingest(c, "AD-HDFCBK", GENUINE_HDFC)
    assert r.status_code == 200
    assert r.get_json()["captured"] is True


def test_fake_debit_alert_is_held_not_captured(tmp_path):
    app, c = _client(tmp_path, "q2.db")
    r = _ingest(c, "+919812345678", FAKE_DEBIT_ALERT)
    body = r.get_json()
    assert body["captured"] is False
    assert body["reason"] == "quarantined"

    conn = sqlite3.connect(app.config["DB_PATH"])
    txs = conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0]
    held = conn.execute(
        "SELECT body, risk, indicators, status FROM sms_quarantine").fetchall()
    conn.close()
    assert txs == 0, "a forged alert reached the ledger"
    assert len(held) == 1, "the message was discarded instead of held"
    assert held[0][0] == FAKE_DEBIT_ALERT, "the held copy is not the original"
    assert held[0][3] == "pending"
    assert "callback_mobile_number" in json.loads(held[0][2])


def test_nothing_is_ever_silently_discarded(tmp_path):
    """Every ingested message must land in exactly one of: transactions,
    sms_quarantine, or parse_misses. A message in none of them is lost."""
    app, c = _client(tmp_path, "q3.db")
    cases = [
        ("AD-HDFCBK", GENUINE_HDFC),                  # captured
        ("+919812345678", FAKE_DEBIT_ALERT),          # quarantined
        ("AD-HDFCBK", "Your balance enquiry is complete."),   # parse miss
        ("9876543210", PHISH_KYC),                    # quarantined
    ]
    for sender, body in cases:
        _ingest(c, sender, body)

    conn = sqlite3.connect(app.config["DB_PATH"])
    bodies = set()
    for (b,) in conn.execute("SELECT sms_body FROM transactions WHERE sms_body IS NOT NULL"):
        bodies.add(b.strip()[:400])
    for (b,) in conn.execute("SELECT body FROM sms_quarantine"):
        bodies.add(b.strip()[:400])
    for (b,) in conn.execute("SELECT body FROM parse_misses"):
        bodies.add(b.strip()[:400])
    conn.close()

    for _, body in cases:
        assert body.strip()[:400] in bodies, f"message vanished: {body[:50]}"


def test_every_sender_is_registered_even_when_unparsable(tmp_path):
    """The registry must reflect real traffic, not just successful captures,
    or the trust signal is biased by construction."""
    app, c = _client(tmp_path, "q4.db")
    _ingest(c, "AD-HDFCBK", "Your balance enquiry is complete.")
    conn = sqlite3.connect(app.config["DB_PATH"])
    row = conn.execute("SELECT sender, kind, bank, message_count "
                       "FROM sms_senders").fetchone()
    conn.close()
    assert row == ("AD-HDFCBK", "dlt", "HDFC Bank", 1)


def test_repeat_messages_from_one_sender_increment_not_duplicate(tmp_path):
    app, c = _client(tmp_path, "q5.db")
    for i in range(3):
        _ingest(c, "AD-HDFCBK", GENUINE_HDFC.replace("402198877123",
                                                     f"40219887712{i}"))
    conn = sqlite3.connect(app.config["DB_PATH"])
    rows = conn.execute("SELECT sender, message_count FROM sms_senders").fetchall()
    conn.close()
    assert rows == [("AD-HDFCBK", 3)]


def test_approving_a_held_message_creates_the_transaction_and_trusts_sender(tmp_path):
    app, c = _client(tmp_path, "q6.db")
    _ingest(c, "AD-ZZZBNK", "Rs.900 debited from a/c XX11 to SHOP. "
                            "Not you? call 9812345678 to reverse now")
    conn = sqlite3.connect(app.config["DB_PATH"])
    qid = conn.execute("SELECT id FROM sms_quarantine").fetchone()[0]
    conn.close()

    r = c.post(f"/sms/quarantine/{qid}", data={"action": "approve"})
    assert r.status_code in (200, 302)

    conn = sqlite3.connect(app.config["DB_PATH"])
    amount = conn.execute("SELECT amount FROM transactions").fetchone()
    status = conn.execute("SELECT status FROM sms_quarantine WHERE id=?", (qid,)).fetchone()[0]
    trust = conn.execute("SELECT trust FROM sms_senders").fetchone()[0]
    conn.close()
    assert amount and abs(amount[0] - 900.0) < 0.01
    assert status == "approved"
    assert trust == "trusted"


def test_rejecting_and_blocking_quarantines_future_messages(tmp_path):
    app, c = _client(tmp_path, "q7.db")
    _ingest(c, "AD-HDFCBK", GENUINE_HDFC)          # captured, sender learned
    conn = sqlite3.connect(app.config["DB_PATH"])
    sid = conn.execute("SELECT id FROM sms_senders").fetchone()[0]
    conn.close()

    c.post(f"/sms/senders/{sid}", data={"trust": "blocked"})

    r = _ingest(c, "AD-HDFCBK", GENUINE_ICICI)
    assert r.get_json()["reason"] == "quarantined", \
        "a blocked sender still reached the ledger"


def test_blocked_sender_can_be_unblocked(tmp_path):
    app, c = _client(tmp_path, "q8.db")
    _ingest(c, "AD-HDFCBK", GENUINE_HDFC)
    conn = sqlite3.connect(app.config["DB_PATH"])
    sid = conn.execute("SELECT id FROM sms_senders").fetchone()[0]
    conn.close()
    c.post(f"/sms/senders/{sid}", data={"trust": "blocked"})
    c.post(f"/sms/senders/{sid}", data={"trust": "unknown"})
    r = _ingest(c, "AD-HDFCBK", GENUINE_ICICI)
    assert r.get_json()["captured"] is True


def test_heuristics_never_overwrite_a_user_trust_decision(tmp_path):
    """A user's 'blocked' must survive later ingests that would otherwise
    classify the sender as known-good."""
    app, c = _client(tmp_path, "q9.db")
    _ingest(c, "AD-HDFCBK", GENUINE_HDFC)
    conn = sqlite3.connect(app.config["DB_PATH"])
    sid = conn.execute("SELECT id FROM sms_senders").fetchone()[0]
    conn.close()
    c.post(f"/sms/senders/{sid}", data={"trust": "blocked"})
    for _ in range(3):
        _ingest(c, "AD-HDFCBK", GENUINE_ICICI)
    conn = sqlite3.connect(app.config["DB_PATH"])
    trust = conn.execute("SELECT trust FROM sms_senders").fetchone()[0]
    conn.close()
    assert trust == "blocked"


def test_quarantine_page_renders_and_explains(tmp_path):
    app, c = _client(tmp_path, "q10.db")
    _ingest(c, "+919812345678", FAKE_DEBIT_ALERT)
    r = c.get("/sms/quarantine")
    assert r.status_code == 200
    assert b"personal mobile number" in r.data
    assert b"4,999" in r.data or b"4999" in r.data


def test_duplicate_phish_increments_seen_count_without_duplicating(tmp_path):
    app, c = _client(tmp_path, "q11.db")
    for _ in range(3):
        _ingest(c, "+919812345678", FAKE_DEBIT_ALERT)
    conn = sqlite3.connect(app.config["DB_PATH"])
    rows = conn.execute("SELECT seen_count FROM sms_quarantine").fetchall()
    conn.close()
    assert rows == [(3,)]


def test_sms_ingest_still_rejects_an_unauthorised_caller(tmp_path):
    """The new code path must not have widened the device gate."""
    app, c = _client(tmp_path, "q12.db")
    r = c.post("/sms/ingest", data={"sender": "AD-HDFCBK", "body": GENUINE_HDFC},
               environ_overrides={"REMOTE_ADDR": "127.0.0.1"})
    assert r.status_code == 403


def test_held_messages_surface_a_banner_on_every_page(tmp_path):
    """The quarantine has no tab of its own, so the banner is the only route
    to it. If it stops rendering, held messages become unreachable — which
    would silently break the "never discard" guarantee from the user's side.
    """
    app, c = _client(tmp_path, "q13.db")
    assert b"message held" not in c.get("/dashboard").data
    _ingest(c, "+919812345678", FAKE_DEBIT_ALERT)
    page = c.get("/dashboard").data
    assert b"1 message held" in page
    assert b"/sms/quarantine" in page


def test_banner_clears_once_the_message_is_resolved(tmp_path):
    app, c = _client(tmp_path, "q14.db")
    _ingest(c, "+919812345678", FAKE_DEBIT_ALERT)
    conn = sqlite3.connect(app.config["DB_PATH"])
    qid = conn.execute("SELECT id FROM sms_quarantine").fetchone()[0]
    conn.close()
    c.post(f"/sms/quarantine/{qid}", data={"action": "reject"})
    assert b"message held" not in c.get("/dashboard").data
