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
    # Unknown merchant: captured, but the app must ask for a category.
    sms = "Rs.450.00 spent at RAJUKIRANA on 12-06-2025 ref 553201998877 UPI"
    r = c.post("/sms/ingest", data={"sender": "VK-HDFCBK", "body": sms})
    j = r.get_json()
    assert j["captured"] is True and j["needs_category"] is True
    # The transaction is now in the app without any pasting.
    page = c.get("/transactions")
    assert b"RAJUKIRANA" in page.data.upper()
    # And the "which category?" popup is shown when the app opens.
    dash = c.get("/dashboard")
    assert b"New transaction from SMS" in dash.data


def test_sms_ingest_seed_merchant_auto_categorised(tmp_path):
    c = _su_client(tmp_path)
    # SWIGGY is in the built-in Indian seed: resolved + categorised on day one.
    sms = "Rs.450.00 spent at SWIGGY on 12-06-2025 ref 553201998877 UPI"
    j = c.post("/sms/ingest", data={"body": sms}).get_json()
    assert j["captured"] is True and j["needs_category"] is False
    assert j["merchant"] == "Swiggy"
    assert b"New transaction from SMS" not in c.get("/dashboard").data


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
        "body": "Rs.350 debited to SHARMAJI on 01/02/2025 ref REF111222333 UPI"}).get_json()
    tx_id = first["id"]
    # Pick a real category from the popup's chips (auto-provisioned defaults).
    import re
    cat_id = re.search(rb'name="category_id" value="([0-9a-f]+)"',
                       c.get("/dashboard").data)
    assert cat_id, "category chips should render in the popup"
    cid = cat_id.group(1).decode()
    c.post(f"/transactions/{tx_id}/categorize", data={"category_id": cid})
    # Popup is gone, and a second SHARMAJI SMS is auto-categorised (no prompt).
    assert b"New transaction from SMS" not in c.get("/dashboard").data
    second = c.post("/sms/ingest", data={
        "body": "Rs.420 debited to SHARMAJI on 02/02/2025 ref REF444555666 UPI"}).get_json()
    assert second["captured"] is True and second["needs_category"] is False


def test_sms_ingest_dedup_refless(tmp_path):
    c = _su_client(tmp_path)
    sms = "Rs.120 debited to LOCALCAFE on 03-04-2025 UPI"  # no parseable ref
    assert c.post("/sms/ingest", data={"body": sms}).get_json()["captured"] is True
    assert c.post("/sms/ingest", data={"body": sms}).get_json()["reason"] == "duplicate"


def test_device_token_enforced(tmp_path):
    app = create_app(db_path=str(tmp_path / "t.db"), single_user=True,
                     secret_key="t", device_token="sekret")
    c = app.test_client()
    sms = "Rs.50 spent at TEA on 01-01-2025 ref AAA111222333 UPI"
    assert c.post("/sms/ingest", data={"body": sms}).status_code == 403          # no token
    assert c.post("/sms/ingest", data={"body": sms},
                  headers={"X-SpendWise-Token": "wrong"}).status_code == 403       # bad token
    ok = c.post("/sms/ingest", data={"body": sms}, headers={"X-SpendWise-Token": "sekret"})
    assert ok.status_code == 200 and ok.get_json()["captured"] is True            # good token


def test_ingest_gated_pages_open(tmp_path):
    # Sensitive ingest endpoints require the device token; GET pages load on
    # loopback without a per-navigation gate (that gate broke WebView
    # navigation because the session cookie wasn't reliably carried).
    app = create_app(db_path=str(tmp_path / "g.db"), single_user=True,
                     secret_key="t", device_token="sekret")
    c = app.test_client()
    assert c.get("/dashboard").status_code == 200            # pages load
    assert c.get("/export.csv").status_code == 200
    assert c.get("/healthz").status_code == 200
    # …but SMS injection still needs the token.
    sms = "Rs.50 spent at TEA on 01-01-2025 ref AAA111222333 UPI"
    assert c.post("/sms/ingest", data={"body": sms}).status_code == 403
    assert c.post("/device/state", data={"sms_permission": "denied"}).status_code == 403


def test_secret_key_persisted(tmp_path):
    db = str(tmp_path / "p.db")
    a1 = create_app(db_path=db, single_user=True)
    a2 = create_app(db_path=db, single_user=True)
    assert a1.secret_key and a1.secret_key == a2.secret_key  # survives "restart"


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


def test_budget_set_progress_and_insight(auth_client):
    import re
    # Grab an existing expense category id from the budgets page.
    page = auth_client.get("/categories")
    m = re.search(rb'id="budget-([0-9a-f]+)"', page.data)
    assert m, "expense categories should render budget sheets"
    cat_id = m.group(1).decode()
    # Set a monthly budget, then overspend it this month.
    auth_client.post(f"/categories/{cat_id}/budget", data={"budget_amount": "100"})
    _add(auth_client, amount="150.00", merchant="KFC", category_id=cat_id)
    dash = auth_client.get("/dashboard")
    assert b"Budgets" in dash.data and b"over budget" in dash.data
    # Clearing removes it from the dashboard.
    auth_client.post(f"/categories/{cat_id}/budget",
                     data={"budget_amount": "100", "clear": "1"})
    assert b"over budget" not in auth_client.get("/dashboard").data


def test_export_csv(auth_client):
    _add(auth_client, amount="123.45", merchant="ExportMart")
    r = auth_client.get("/export.csv")
    assert r.status_code == 200 and "text/csv" in r.headers["Content-Type"]
    assert b"ExportMart" in r.data and b"123.45" in r.data
    assert b"date,type,amount" in r.data  # header row


def test_import_page_lists_recent_sms(tmp_path):
    c = _su_client(tmp_path)
    c.post("/sms/ingest", data={
        "body": "Rs.777 debited to CAFERIO on 01/02/2025 ref REF777888999 UPI"})
    page = c.get("/import")
    assert b"Recently captured" in page.data and b"CAFERIO" in page.data.upper()


def test_recurring_bill_detection(auth_client):
    import datetime as dt
    today = dt.date.today()
    # Two similar charges are NOT a bill (coincidence guard) …
    for days_ago in (60, 30):
        _add(auth_client, amount="499.00", merchant="NETFLIX",
             occurred_at=(today - dt.timedelta(days=days_ago)).isoformat())
    assert b"Upcoming bills" not in auth_client.get("/dashboard").data
    # … but three at a monthly cadence are.
    _add(auth_client, amount="499.00", merchant="NETFLIX",
         occurred_at=(today - dt.timedelta(days=90)).isoformat())
    dash = auth_client.get("/dashboard")
    assert b"Upcoming bills" in dash.data and b"NETFLIX" in dash.data


def test_monthly_report_page(auth_client):
    _add(auth_client, amount="900.00", type="income", merchant="Salary")
    _add(auth_client, amount="300.00", merchant="BigBazaar")
    r = auth_client.get("/report")
    assert r.status_code == 200
    assert b"Monthly report" in r.data and b"BigBazaar" in r.data
    # Month navigation guards: future months clamp to the current month.
    assert auth_client.get("/report?m=2999-01").status_code == 200
    assert auth_client.get("/report?m=bogus").status_code == 200


def test_transaction_filters(auth_client):
    _add(auth_client, amount="100.00", type="income", merchant="PayIn")
    _add(auth_client, amount="50.00", type="expense", merchant="PayOut")
    inc = auth_client.get("/transactions?f=income")
    assert b"PayIn" in inc.data and b"PayOut" not in inc.data
    exp = auth_client.get("/transactions?f=expense")
    assert b"PayOut" in exp.data and b"PayIn" not in exp.data
    # The active chip row renders.
    assert b"Needs review" in exp.data


def test_dashboard_streak_card(auth_client):
    import datetime as dt
    # Expense yesterday, none today → a 1-day no-spend streak.
    _add(auth_client, amount="80.00", merchant="KFC",
         occurred_at=(dt.date.today() - dt.timedelta(days=1)).isoformat())
    dash = auth_client.get("/dashboard")
    assert b"no-spend streak" in dash.data


def test_edit_transaction(auth_client):
    import re
    _add(auth_client, amount="200.00", merchant="OldName")
    page = auth_client.get("/transactions")
    tx_id = re.search(rb"/transactions/([0-9a-f]+)/edit", page.data).group(1).decode()
    r = auth_client.post(f"/transactions/{tx_id}/edit", data={
        "amount": "250.00", "merchant": "NewName", "type": "expense",
        "occurred_at": "2026-01-15", "notes": "fixed"}, follow_redirects=True)
    assert b"NewName" in r.data and b"250.00" in r.data and b"fixed" in r.data
    # Invalid amount is rejected.
    bad = auth_client.post(f"/transactions/{tx_id}/edit", data={"amount": "0"})
    assert "error=amount" in bad.headers["Location"]


def test_undo_delete(auth_client):
    import re
    _add(auth_client, amount="75.00", merchant="Oops")
    page = auth_client.get("/transactions")
    tx_id = re.search(rb"/transactions/([0-9a-f]+)/delete", page.data).group(1).decode()
    gone = auth_client.post(f"/transactions/{tx_id}/delete", follow_redirects=True)
    assert b"Undo" in gone.data          # undo bar offered
    back = auth_client.post(f"/transactions/{tx_id}/restore", follow_redirects=True)
    assert b"Oops" in back.data          # transaction is back


def test_merchant_drilldown_page(auth_client):
    _add(auth_client, amount="120.00", merchant="ChaiPoint")
    _add(auth_client, amount="80.00", merchant="ChaiPoint")
    r = auth_client.get("/merchant?n=ChaiPoint")
    assert r.status_code == 200
    assert b"ChaiPoint" in r.data and b"Merchant history" in r.data
    assert auth_client.get("/merchant").status_code == 302  # no name → back


def test_future_dated_tx_excluded_from_tiles(tmp_path):
    import datetime as dt
    from spendwise import analytics, db as sdb
    path = str(tmp_path / "future.db")
    c = create_app(db_path=path, single_user=True, secret_key="t").test_client()
    future = (dt.date.today() + dt.timedelta(days=40)).isoformat()
    c.post("/transactions", data={"amount": "9999.00", "type": "expense",
           "merchant": "FutureRent", "category_id": "", "notes": "",
           "occurred_at": future})
    conn = sdb.connect(path)
    uid = sdb.one(conn, "SELECT id FROM users")["id"]
    d = analytics.build_dashboard(conn, uid)
    # Next month's rent must not inflate today/week/month "so far" tiles.
    assert d["daily_spend"] == 0 and d["weekly_spend"] == 0 and d["monthly_spend"] == 0


def test_report_shows_vanished_category_drop(auth_client):
    import datetime as dt, re
    # Spend in a category ONLY last month; this month's report must still
    # show the category with a negative delta.
    page = auth_client.get("/categories")
    cat_id = re.search(rb'id="budget-([0-9a-f]+)"', page.data).group(1).decode()
    last_month = (dt.date.today().replace(day=1) - dt.timedelta(days=15))
    _add(auth_client, amount="500.00", merchant="OneOff",
         category_id=cat_id, occurred_at=last_month.isoformat())
    r = auth_client.get("/report")
    assert "▼".encode() in r.data  # the drop arrow renders


def test_report_month_param_normalised(auth_client):
    _add(auth_client, amount="10.00", merchant="X")
    assert auth_client.get("/report?m=2025-7").status_code == 200  # unpadded ok


def test_zero_amount_rejected(auth_client):
    r = auth_client.post("/transactions", data={
        "amount": "0", "type": "expense", "merchant": "Zero", "category_id": "",
        "notes": "", "occurred_at": ""})
    assert "error=amount" in r.headers["Location"]
    assert b"Zero" not in auth_client.get("/transactions").data


def test_weird_type_does_not_break_dashboard(auth_client):
    auth_client.post("/transactions", data={
        "amount": "10.00", "type": "banana", "merchant": "M", "category_id": "",
        "notes": "", "occurred_at": ""})
    assert auth_client.get("/dashboard").status_code == 200  # no KeyError 500


def test_csv_formula_injection_neutralised(auth_client):
    _add(auth_client, amount="5.00", merchant="=cmd()")
    r = auth_client.get("/export.csv")
    assert b"'=cmd()" in r.data  # dangerous prefix quoted


def test_zero_amount_sms_and_import_rejected(tmp_path):
    c = _su_client(tmp_path)
    r = c.post("/sms/ingest", data={"body": "Rs.0.00 debited to GLITCH on 01/02/2025 UPI"})
    assert r.get_json()["captured"] is False
    bad = c.post("/import/create", data={
        "amount": "0", "type": "expense", "raw_merchant": "GLITCH",
        "reference_number": "", "occurred_at": ""})
    assert b"error" in bad.data.lower()
    assert c.get("/dashboard").status_code == 200  # no ZeroDivisionError


def test_post_works_with_any_origin_header(auth_client):
    # Android WebViews send Origin: null on form POSTs; add/edit must still
    # work (the old Origin-based CSRF check 403'd these on-device).
    for origin in ("null", "http://127.0.0.1:8765", None):
        headers = {"Origin": origin} if origin else {}
        r = auth_client.post("/transactions", data={
            "amount": "10.00", "type": "expense", "merchant": "M", "category_id": "",
            "notes": "", "occurred_at": ""}, headers=headers)
        assert r.status_code == 302  # accepted, not Forbidden


def test_device_endpoints_disabled_in_web_mode(client):
    # Multi-user web deployment (no single_user, no token): ingest is off.
    sms = "Rs.50 spent at TEA on 01-01-2025 ref AAA111222333 UPI"
    assert client.post("/sms/ingest", data={"body": sms}).status_code == 403
    assert client.post("/device/state", data={"sms_permission": "denied"}).status_code == 403


def test_first_run_dashboard_is_onboarding_not_streak(tmp_path):
    c = _su_client(tmp_path)
    dash = c.get("/dashboard")
    # No fake gamification on a fresh install…
    assert b"no-spend streak" not in dash.data
    assert b"Where your money goes" not in dash.data
    # …but a setup checklist and a name prompt instead.
    assert b"Get set up" in dash.data
    assert b"What should we call you?" in dash.data
    # After the first transaction, the real widgets appear.
    c.post("/transactions", data={"amount": "50.00", "type": "expense",
           "merchant": "Chai", "category_id": "", "notes": "", "occurred_at": ""})
    dash = c.get("/dashboard")
    assert b"Get set up" not in dash.data
    assert b"Where your money goes" in dash.data


def test_profile_name_update(tmp_path):
    c = _su_client(tmp_path)
    c.post("/profile", data={"full_name": "Jeeva"})
    dash = c.get("/dashboard")
    assert b"Hi, Jeeva" in dash.data
    assert b"What should we call you?" not in dash.data


def test_sms_parser_indian_bank_formats():
    # SBI: verb-anchored amount with no Rs prefix; trailing Avl Bal ignored.
    r = parsing.parse_sms("A/C X9218 debited by 199.0 on 08Jul26 trf to SWIGGY "
                          "Refno 553201998877. Avl Bal Rs 12,430.50")
    assert r.matched and r.amount == 199.0
    assert "SWIGGY" in r.raw_merchant.upper()
    # ICICI: payee appears before 'credited'; amount after 'debited for'.
    r = parsing.parse_sms("ICICI Bank Acct XX823 debited for Rs 320.00 on "
                          "08-Jul-26; SWIGGY credited. UPI:519023481234")
    assert r.matched and r.amount == 320.0 and r.type == "expense"
    assert "SWIGGY" in r.raw_merchant.upper()
    # Axis: merchant lives inside the UPI/P2M/<ref>/NAME path.
    r = parsing.parse_sms("INR 460.00 debited A/c no. XX1234 08-07-26 "
                          "UPI/P2M/519023481234/ZOMATO/pay. Not you? SMS BLOCKUPI to 919551")
    assert r.matched and r.amount == 460.0
    assert "ZOMATO" in r.raw_merchant.upper()
    # Kotak: the 'to' payee must win over the 'from' bank-account fragment.
    r = parsing.parse_sms("Sent Rs.20.00 from Kotak Bank AC X1234 to swiggy8@ybl "
                          "on 08-07-26. UPI Ref 519023481234")
    assert r.matched and r.amount == 20.0
    assert "SWIGGY" in r.raw_merchant.upper()


def test_sms_parser_rejects_non_transactions():
    # Promotions, UPI collect requests and pre-debit reminders are not money
    # movements and must never be auto-captured.
    assert not parsing.parse_sms(
        "Flat Rs.200 OFF on your first purchase at KFC! Order now").matched
    assert not parsing.parse_sms(
        "Payment request of Rs.999 from netflix@icici. Approve in your app").matched
    assert not parsing.parse_sms(
        "Rs.199 will be debited on 15-07 for NETFLIX autopay").matched


def test_engine_normalize_vpa_variants():
    from spendwise import engine
    # Handle variants of the same merchant share one learning row.
    assert engine.normalize_merchant("swiggy8@ybl") == "SWIGGY"
    assert engine.normalize_merchant("SWIGGY LIMITED") == "SWIGGY"
    assert engine.normalize_merchant("AMAZON PAY INDIA") == "AMAZON PAY"


def test_sms_parser_wallet_formats():
    # PhonePe / GPay / Paytm / BHIM confirmations.
    r = parsing.parse_sms("Paid Rs.240 to SHARMA STORES via PhonePe. UPI Ref 553201998877")
    assert r.matched and r.amount == 240.0 and "SHARMA" in r.raw_merchant.upper()
    r = parsing.parse_sms("You paid ₹150 to Ramesh Kumar using Google Pay. "
                          "UPI transaction ID: 519023481234")
    assert r.matched and r.amount == 150.0 and r.raw_merchant.startswith("Ramesh Kumar")
    r = parsing.parse_sms("Payment of Rs.349 made to CLOUDTAIL INDIA on 08-07-26 "
                          "via Amazon Pay UPI. Ref 553201998877")
    assert r.matched and r.amount == 349.0 and "CLOUDTAIL" in r.raw_merchant.upper()


def test_fuzzy_alias_reuses_learning(auth_client):
    # Teach the engine SWIGGY GOURMET a few times, then a NEW alias variant
    # should still resolve to the same merchant (discounted, not cold).
    for _ in range(4):
        auth_client.post("/import/create", data={
            "amount": "300.00", "type": "expense", "raw_merchant": "SWIGGY GOURMET",
            "reference_number": "", "occurred_at": ""})
        import re
        page = auth_client.get("/transactions")
        mm = re.search(rb"/transactions/([0-9a-f]+)/confirm", page.data)
        if mm:
            auth_client.post(f"/transactions/{mm.group(1).decode()}/confirm",
                             data={"merchant": "Swiggy Gourmet"})
    res = auth_client.post("/transactions/resolve",
                           data={"merchant": "SWIGGY GOURMET KITCHENS", "amount": "300"})
    assert b"Swiggy Gourmet" in res.data  # fuzzy token overlap found the training


def test_resolve_preview_explains_why(auth_client):
    for _ in range(3):
        _add(auth_client, amount="250.00", merchant="ChaiWala")
    res = auth_client.post("/transactions/resolve",
                           data={"merchant": "ChaiWala", "amount": "250"})
    assert b"You confirmed this match" in res.data  # human-readable reason


def test_merchant_intelligence_section(auth_client):
    for _ in range(2):
        _add(auth_client, amount="90.00", merchant="MilkVendor")
    page = auth_client.get("/merchant?n=MilkVendor")
    assert b"Merchant intelligence" in page.data
    assert b"Confirmed" in page.data


def test_behaviour_insight_most_visited(auth_client):
    for _ in range(4):
        _add(auth_client, amount="120.00", merchant="MetroCafe")
    dash = auth_client.get("/dashboard")
    assert b"4 times this month" in dash.data and b"MetroCafe" in dash.data
