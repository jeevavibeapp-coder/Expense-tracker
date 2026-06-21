"""End-to-end tests for the SpendWise Flask web app."""
from __future__ import annotations

from spendwise import parsing
from spendwise.app import create_app


def _add(client, **kw):
    data = {"amount": "100.00", "type": "expense", "merchant": "", "category_id": "",
            "notes": "", "occurred_at": ""}
    data.update(kw)
    return client.post("/transactions", data=data, follow_redirects=True)


def test_health(client):
    assert client.get("/healthz").get_json()["status"] == "ok"


def test_login_page_and_redirect(client):
    assert client.get("/login").status_code == 200
    r = client.get("/dashboard")
    assert r.status_code == 302 and "/login" in r.headers["Location"]


def test_signup_creates_account_with_categories(auth_client):
    page = auth_client.get("/dashboard")
    assert page.status_code == 200 and b"Total balance" in page.data
    cats = auth_client.get("/categories")
    assert b"Food &amp; Dining" in cats.data or b"Food & Dining" in cats.data


def test_duplicate_signup_rejected(client):
    client.post("/signup", data={"full_name": "B", "email": "b@x.com", "password": "password1"})
    client.post("/logout")
    r = client.post("/signup", data={"full_name": "B", "email": "b@x.com",
                                     "password": "password1"})
    assert r.status_code == 409


def test_add_transaction_confirmed(auth_client):
    r = _add(auth_client, amount="250.00", merchant="Starbucks")
    assert r.status_code == 200
    # User-provided merchant is auto-confirmed at 100% confidence.
    assert b"Starbucks" in r.data and b"100%" in r.data


def test_learning_then_live_resolve(auth_client):
    for _ in range(4):
        _add(auth_client, amount="250.00", merchant="Starbucks")
    r = auth_client.post("/transactions/resolve", data={"merchant": "Starbucks", "amount": "250"})
    assert r.status_code == 200
    assert b"Starbucks" in r.data and b"%" in r.data


def test_confirm_pending_merchant_learns(auth_client):
    # Create a needs-review tx via the import flow (raw merchant, unknown).
    auth_client.post("/import/create", data={
        "amount": "80.00", "type": "expense", "raw_merchant": "SURESH",
        "reference_number": "", "occurred_at": ""})
    page = auth_client.get("/transactions")
    assert b"Review" in page.data or b"Confirm?" in page.data
    # Find the tx id from the confirm form action.
    import re
    m = re.search(rb"/transactions/([0-9a-f]+)/confirm", page.data)
    assert m
    tx_id = m.group(1).decode()
    auth_client.post(f"/transactions/{tx_id}/confirm", data={"merchant": "A2B"},
                     follow_redirects=True)
    learned = auth_client.post("/transactions/resolve", data={"merchant": "SURESH"})
    assert b"A2B" in learned.data


def test_sms_parse_and_create(auth_client):
    parsed = auth_client.post("/import/parse", data={
        "sms": "Rs.450.00 debited from a/c **1234 on 05-Jan-2024 to ZOMATO Ref 883120114455 UPI"})
    assert parsed.status_code == 200
    assert b"450" in parsed.data and b"ZOMATO" in parsed.data.upper()
    created = auth_client.post("/import/create", data={
        "amount": "450.00", "type": "expense", "raw_merchant": "ZOMATO",
        "reference_number": "883120114455", "occurred_at": "2024-01-05"})
    assert b"saved" in created.data.lower()


def test_search_and_delete(auth_client):
    _add(auth_client, amount="100.00", merchant="KFC")
    _add(auth_client, amount="500.00", merchant="Zomato")
    found = auth_client.get("/transactions?q=kfc")
    assert b"KFC" in found.data and b"Zomato" not in found.data
    import re
    m = re.search(rb"/transactions/([0-9a-f]+)/delete", found.data)
    tx_id = m.group(1).decode()
    auth_client.post(f"/transactions/{tx_id}/delete", follow_redirects=True)
    assert b"KFC" not in auth_client.get("/transactions?q=kfc").data


def test_duplicate_fraud_alert(auth_client):
    iso = "2024-01-01"
    _add(auth_client, amount="500.00", merchant="KFC", occurred_at=iso)
    _add(auth_client, amount="500.00", merchant="KFC", occurred_at=iso)
    fraud = auth_client.get("/fraud")
    assert b"Duplicate" in fraud.data


def test_dashboard_real_data(auth_client):
    empty = auth_client.get("/dashboard")
    assert b"0.00" in empty.data
    _add(auth_client, amount="200.00", type="income", merchant="Salary")
    _add(auth_client, amount="50.00", merchant="KFC")
    dash = auth_client.get("/dashboard")
    # Balance = 200 income - 50 expense = 150.00, and KFC is a top merchant.
    assert b"150.00" in dash.data and b"KFC" in dash.data


def test_categories_crud(auth_client):
    added = auth_client.post("/categories", data={"name": "Coffee Runs", "type": "expense",
                                                  "color": "#123456"})
    assert b"Coffee Runs" in added.data
    dup = auth_client.post("/categories", data={"name": "Coffee Runs", "type": "expense",
                                                "color": "#123456"})
    assert b"already exists" in dup.data


def test_settings_roundtrip(auth_client):
    upd = auth_client.post("/settings", data={
        "currency": "USD", "theme": "dark", "auto_save_threshold": "90",
        "confirm_threshold": "40", "high_value_amount": "25000"}, follow_redirects=True)
    assert b"Settings saved" in upd.data and b"USD" in upd.data


def test_single_user_mode_auto_login(tmp_path):
    app = create_app(db_path=str(tmp_path / "su.db"), single_user=True, secret_key="x")
    c = app.test_client()
    # No login performed, yet the dashboard is accessible.
    assert c.get("/dashboard").status_code == 200


def test_sms_parser_unit():
    r = parsing.parse_sms("INR 25,000.00 credited to your account from ACME on 01/02/2024")
    assert r.amount == 25000.0 and r.type == "income"
    assert parsing.parse_sms("Your OTP is 4567.").matched is False


def _su_client(tmp_path, name="s.db"):
    app = create_app(db_path=str(tmp_path / name), single_user=True, secret_key="t")
    return app.test_client()


def test_sms_ingest_auto_captures(tmp_path):
    c = _su_client(tmp_path)
    sms = "Rs.450.00 spent at SWIGGY on 12-06-2025 ref 553201998877 UPI"
    r = c.post("/sms/ingest", data={"sender": "VK-HDFCBK", "body": sms})
    j = r.get_json()
    assert j["captured"] is True and j["needs_category"] is True
    # The transaction is now in the app without any pasting.
    page = c.get("/transactions")
    assert b"SWIGGY" in page.data.upper()
    # And the "which category?" popup is shown when the app opens.
    dash = c.get("/dashboard")
    assert b"New transaction from SMS" in dash.data


def test_sms_ingest_ignores_non_financial(tmp_path):
    c = _su_client(tmp_path)
    r = c.post("/sms/ingest", data={"sender": "AX-OTP", "body": "Your OTP is 4567."})
    assert r.get_json()["captured"] is False


def test_sms_ingest_dedup_by_reference(tmp_path):
    c = _su_client(tmp_path)
    sms = "Rs.200 debited to UBER on 01/02/2025 ref ABCXYZ123456 UPI"
    assert c.post("/sms/ingest", data={"body": sms}).get_json()["captured"] is True
    assert c.post("/sms/ingest", data={"body": sms}).get_json()["reason"] == "duplicate"


def test_sms_categorize_teaches_engine(tmp_path):
    c = _su_client(tmp_path)
    first = c.post("/sms/ingest", data={
        "body": "Rs.350 debited to DOMINOS on 01/02/2025 ref REF111222333 UPI"}).get_json()
    tx_id = first["id"]
    # Pick a real category from the popup's chips (auto-provisioned defaults).
    import re
    cat_id = re.search(rb'name="category_id" value="([0-9a-f]+)"',
                       c.get("/dashboard").data)
    assert cat_id, "category chips should render in the popup"
    cid = cat_id.group(1).decode()
    c.post(f"/transactions/{tx_id}/categorize", data={"category_id": cid})
    # Popup is gone, and a second DOMINOS SMS is auto-categorised (no prompt).
    assert b"New transaction from SMS" not in c.get("/dashboard").data
    second = c.post("/sms/ingest", data={
        "body": "Rs.420 debited to DOMINOS on 02/02/2025 ref REF444555666 UPI"}).get_json()
    assert second["captured"] is True and second["needs_category"] is False


def test_sms_permission_banner(tmp_path):
    c = _su_client(tmp_path)
    # No banner until the device reports a denial.
    assert b"Auto-capture is paused" not in c.get("/dashboard").data
    c.post("/device/state", data={"sms_permission": "denied"})
    assert b"Auto-capture is paused" in c.get("/dashboard").data
    # Granting clears it.
    c.post("/device/state", data={"sms_permission": "granted"})
    assert b"Auto-capture is paused" not in c.get("/dashboard").data


def test_sms_prompts_dismiss_all(tmp_path):
    c = _su_client(tmp_path)
    c.post("/sms/ingest", data={"body": "Rs.99 debited to ACME on 01/02/2025 UPI"})
    c.post("/sms/ingest", data={"body": "Rs.88 debited to BETA on 01/02/2025 UPI"})
    assert b"New transaction from SMS" in c.get("/dashboard").data
    c.post("/sms/prompts/dismiss")
    assert b"New transaction from SMS" not in c.get("/dashboard").data
