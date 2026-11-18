"""End-to-end tests for the SpendWise Flask web app."""
from __future__ import annotations

from spendwise import parsing
from spendwise import db as _dbm
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
    assert b"Starbucks" in r.data
    # A user-typed merchant is auto-confirmed at full confidence. Asserted
    # against the stored value rather than a badge: the list no longer prints
    # the engine's confidence score, because a percentage is engine internals
    # that a person cannot act on. The behaviour is unchanged.
    conn = _dbm.connect(auth_client.application.config["DB_PATH"])
    row = conn.execute("SELECT confidence, status FROM transactions "
                       "WHERE merchant_name='Starbucks'").fetchone()
    conn.close()
    assert row is not None and row[0] == 100 and row[1] == "confirmed"


def test_learning_then_live_resolve(auth_client):
    for _ in range(4):
        _add(auth_client, amount="250.00", merchant="Starbucks")
    r = auth_client.post("/transactions/resolve", data={"merchant": "Starbucks", "amount": "250"})
    assert r.status_code == 200
    assert b"Starbucks" in r.data and b"%" in r.data


def _tx_id_for(app, raw_merchant):
    """Look the transaction id up in the database.

    Previously these tests regexed a confirm-form action out of the Activity
    page. Activity no longer embeds one form per row — that work moved to
    /review, where a single decision clears a whole merchant — so scraping the
    markup coupled the tests to a presentation detail instead of to the
    behaviour they actually exercise. The /transactions/<id>/confirm endpoint
    they drive is unchanged.
    """
    from spendwise import db as _dbm
    conn = _dbm.connect(app.config["DB_PATH"])
    row = conn.execute(
        "SELECT id FROM transactions WHERE raw_merchant=? ORDER BY created_at DESC",
        (raw_merchant,)).fetchone()
    conn.close()
    return row[0] if row else None


def test_confirm_pending_merchant_learns(auth_client):
    # Create a needs-review tx via the import flow (raw merchant, unknown).
    auth_client.post("/import/create", data={
        "amount": "80.00", "type": "expense", "raw_merchant": "SURESH",
        "reference_number": "", "occurred_at": ""})
    # Activity surfaces the pending count and routes to /review, where the
    # decision is made once per merchant instead of once per row.
    page = auth_client.get("/transactions")
    assert b"to review" in page.data
    tx_id = _tx_id_for(auth_client.application, "SURESH")
    assert tx_id, "the capture was not recorded"
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
    # The hero splits symbol and number into separate spans for styling, so
    # assert on the parts rather than a contiguous byte match.
    assert "\u20b9".encode() in empty.data           # symbol, not the ISO code
    assert b"INR " not in empty.data
    _add(auth_client, amount="200.00", type="income", merchant="Salary")
    _add(auth_client, amount="50.00", merchant="KFC")
    dash = auth_client.get("/dashboard")
    # Balance = 200 income - 50 expense = 150, and KFC is a top merchant.
    # Whole amounts drop the ".00" — it is noise on every row — and the ISO
    # code is replaced by the symbol, which is what reads as money.
    assert b">150<" in dash.data and b"KFC" in dash.data
    assert "\u20b9".encode() in dash.data
    assert b"150.00" not in dash.data, "trailing .00 is back"
    assert b"INR" not in dash.data, "ISO code is back in the UI"


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
    # No login performed, yet the dashboard is reachable. On a brand-new
    # install it redirects to the first-run introduction rather than to
    # /login — which is the thing this test is about.
    first = c.get("/dashboard")
    assert first.status_code == 302 and "/welcome" in first.headers["Location"]
    c.post("/welcome/done")
    assert c.get("/dashboard").status_code == 200


def test_sms_parser_unit():
    r = parsing.parse_sms("INR 25,000.00 credited to your account from ACME on 01/02/2024")
    assert r.amount == 25000.0 and r.type == "income"
    assert parsing.parse_sms("Your OTP is 4567.").matched is False


def _su_client(tmp_path, name="s.db"):
    app = create_app(db_path=str(tmp_path / name), single_user=True, secret_key="t")
    c = app.test_client()
    # Skip the first-run introduction: these tests are about the app after
    # setup, not about the introduction itself, which has its own tests.
    c.post("/welcome/done")
    return c


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


def test_no_endpoint_is_reachable_without_a_grant(tmp_path):
    """B1 regression. This test previously asserted the OPPOSITE — that pages
    "load on loopback" — which is exactly the vulnerability: on Android every
    co-installed app is also on loopback, so that made the whole ledger
    readable and writable by any app holding the normal-level INTERNET
    permission.
    """
    app = create_app(db_path=str(tmp_path / "g.db"), single_user=True,
                     secret_key="t", device_token="sekret")
    c = app.test_client()

    # No grant yet: nothing that touches user data is reachable.
    for path in ("/dashboard", "/export.csv", "/transactions", "/sms/misses.csv",
                 "/review", "/settings", "/sms/quarantine", "/categories"):
        assert c.get(path).status_code == 403, path
    assert c.post("/transactions", data={"amount": "1", "type": "expense",
                                         "merchant": "X"}).status_code == 403
    assert c.post("/sms/purge", data={"scope": "all"}).status_code == 403

    # Public endpoints stay public — gating /static is what black-screened the
    # app the last time an all-route gate was tried.
    assert c.get("/healthz").status_code == 200
    assert c.get("/static/app.js").status_code in (200, 304)

    # Device endpoints still need the header token.
    sms = "Rs.50 spent at TEA on 01-01-2025 ref AAA111222333 UPI"
    assert c.post("/sms/ingest", data={"body": sms}).status_code == 403
    assert c.post("/device/state", data={"sms_permission": "denied"}).status_code == 403

    # After the grant the app works normally.
    c.get("/?k=sekret")
    c.post("/welcome/done")          # past the first-run introduction
    assert c.get("/dashboard").status_code == 200
    assert c.get("/export.csv").status_code == 200



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
        tx_id = _tx_id_for(auth_client.application, "SWIGGY GOURMET")
        if tx_id:
            auth_client.post(f"/transactions/{tx_id}/confirm",
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


def test_sms_diagnostics_panel(tmp_path):
    c = _su_client(tmp_path)
    # Fresh install: panel is honest that nothing has been captured, and
    # surfaces the OEM autostart hint that actually explains most failures.
    page = c.get("/settings")
    assert b"SMS auto-capture" in page.data
    assert b"No captures yet" in page.data
    assert b"Autostart" in page.data
    # After a capture it reports Working with a count.
    c.post("/sms/ingest", data={
        "body": "Rs.450.00 debited from a/c **1234 on 05-Jan-24 to ZOMATO Ref 883120114455 UPI"})
    page = c.get("/settings")
    assert b"Working" in page.data
    # Denied permission is called out with a fix action.
    c.post("/device/state", data={"sms_permission": "denied"})
    page = c.get("/settings")
    assert b"Permission off" in page.data and b"Allow SMS access" in page.data


def test_queued_sms_counted_in_diagnostics(tmp_path):
    # The queue file lives beside the DB; the panel must surface a backlog
    # (i.e. messages captured by the receiver but not yet ingested).
    db_path = tmp_path / "q.db"
    app = create_app(db_path=str(db_path), single_user=True, secret_key="t")
    (tmp_path / "sms_inbox.jsonl").write_text(
        '{"sender":"X","body":"Rs.1 debited to A"}\n'
        '{"sender":"Y","body":"Rs.2 debited to B"}\n', encoding="utf-8")
    page = app.test_client().get("/settings")
    assert b"In queue" in page.data


PROMO_JUNK = [
    "Improve your credit score now! Get pre-approved loan of Rs 70481.31. Proceed to bit.ly/xy",
    "Rs 70481.31 paid to improve your credit score. Check CIBIL now",
    "Your card bill of Rs 12,340.00 is generated for 11-JUN-26. Total amount due Rs 12340",
    "Congratulations! Rs 5000.00 credited as bonus. Proceed to claim now",
    "Rs 743.00 spent on 11-JUN-26. Apply now for instant loan, low interest",
    "URGENT: account will be blocked. Verify KYC to receive Rs 10000 refund",
    "Recharge of Rs 239 successful for 9876543210. Plan validity 28 days",
    "You have won Rs 50000 lucky draw! Claim now, T&C apply",
    "EMI of Rs 4,500 due on 15-07-26 for your personal loan",
]

REAL_BANK = [
    "Rs.450.00 debited from a/c **1234 on 05-Jan-24 to ZOMATO Ref 883120114455 UPI",
    "Dear UPI user A/C X1234 debited by 199.0 on date 08Jul26 trf to SWIGGY Refno 553201998877",
    "ICICI Bank Acct XX823 debited for Rs 320.00 on 08-Jul-26; SWIGGY credited. UPI:519023481234",
    "Sent Rs.20.00 from Kotak Bank AC X1234 to swiggy8@ybl on 08-07-26. UPI Ref 519023481234",
    "Rs.99 spent on your HDFC Bank Card xx1234 at SPOTIFY on 08-07-25. Avl Lmt Rs 45,000",
]


def test_promotional_and_scam_sms_never_captured():
    # These flooded a real device's ledger (balance went to -18 lakh).
    for body in PROMO_JUNK:
        assert parsing.parse_sms(body).matched is False, f"leaked: {body}"


def test_real_bank_sms_still_captured():
    # Precision must not cost recall.
    for body in REAL_BANK:
        assert parsing.parse_sms(body).matched is True, f"missed: {body}"


def test_garbage_merchant_names_rejected():
    # Dates, call-to-actions and scraped prose are not merchants.
    for body, bad in [
        ("Rs.500 debited from a/c XX12 on 11-JUN-26 Ref 123456789012", "11-JUN-26"),
        ("Rs.500 debited a/c XX12 to Proceed Ref 123456789012", "Proceed"),
    ]:
        r = parsing.parse_sms(body)
        assert r.raw_merchant != bad, f"garbage merchant kept: {r.raw_merchant}"


def test_ingest_stores_sms_context_for_later_review(tmp_path):
    # Without the original message you cannot identify a month-old capture.
    # Use a merchant the seed doesn't know, so the categorize popup appears.
    c = _su_client(tmp_path)
    body = "Rs.450.00 debited from a/c **1234 on 05-Jan-24 to RAJUKIRANA Ref 883120114455 UPI"
    c.post("/sms/ingest", data={"sender": "VK-HDFCBK", "body": body})
    dash = c.get("/dashboard").data
    assert b"VK-HDFCBK" in dash            # sender shown
    assert b"debited from a/c" in dash     # original message shown
    assert b"05-Jan-24" in dash            # date context


def test_sms_purge_clears_unreviewed_only(tmp_path):
    c = _su_client(tmp_path)
    # One unreviewed auto-capture (unknown merchant -> needs review)…
    c.post("/sms/ingest", data={
        "body": "Rs.500 debited from a/c XX12 to RANDOMSHOP99 on 01/02/2025 Ref 123456789012"})
    # …and one the user already categorised.
    c.post("/sms/ingest", data={
        "body": "Rs.450.00 spent at SWIGGY on 12-06-2025 ref 553201998877 UPI"})
    before = c.get("/transactions").data
    assert b"SWIGGY" in before.upper()
    c.post("/sms/purge")
    after = c.get("/transactions").data
    assert b"RANDOMSHOP99" not in after.upper()   # junk gone
    assert b"SWIGGY" in after.upper()             # auto-categorised kept


# Corpus audit: the SMS gate must stay strict WITHOUT losing real transactions.
# Both lists are regression locks — precision and recall are equally load-bearing.
BANK_CORPUS = [
    "Rs.450.00 debited from a/c **1234 on 05-Jan-24 to ZOMATO Ref 883120114455 UPI",
    "Dear UPI user A/C X1234 debited by 199.0 on date 08Jul26 trf to SWIGGY Refno 553201998877",
    "ICICI Bank Acct XX823 debited for Rs 320.00 on 08-Jul-26; SWIGGY credited. UPI:519023481234",
    "INR 460.00 debited A/c no. XX1234 08-07-26 UPI/P2M/519023481234/ZOMATO/pay",
    "Sent Rs.20.00 from Kotak Bank AC X1234 to swiggy8@ybl on 08-07-26. UPI Ref 519023481234",
    "Rs.1500.00 debited from A/c XX4567 on 08-07-26 to RAMESH STORE. UPI Ref 512345678901 -PNB",
    "Dear Customer, Rs.750.00 debited from A/c XX8899 on 08Jul26 UPI/512345678901/BIGBAZAAR",
    "INR 2,300.00 debited from YES BANK A/c XX3344 on 08-Jul-26 towards MYNTRA. Ref 883120114455",
    "Rs 899 debited from IDFC FIRST Bank A/c XX7788 on 08-07-26 to NETFLIX UPI Ref 519023481234",
    "Rs.340.00 spent using AU Bank Card xx5566 at DMART on 08-07-26",
    "Rs.120 paid to CHAIWALA from Paytm Payments Bank A/c XX2211. UPI Ref 553201998877",
    "Paid Rs.240 to SHARMA STORES via PhonePe. UPI Ref 553201998877",
    "You paid Rs.150 to Ramesh Kumar using Google Pay. UPI transaction ID: 519023481234",
    "Payment of Rs.349 made to CLOUDTAIL INDIA on 08-07-26 via Amazon Pay UPI. Ref 553201998877",
    "Rs.85 paid to teastall@upi via BHIM. UPI Ref No 512345678901",
    "Rs.99 spent on your HDFC Bank Card xx1234 at SPOTIFY on 08-07-25. Avl Lmt Rs 45,000",
    "Rs.2000.00 withdrawn from A/c XX1234 at ATM on 08-07-26. Avl Bal Rs 15,000.00",
    "INR 45,000.00 credited to A/c XX1234 by NEFT from ACME PVT LTD on 08-07-26 Ref N123456789012",
    "Rs.5,000.00 credited to your A/c XX9012 via IMPS Ref 512345678901 from RAHUL",
    "INR 62,500.00 credited to A/c XX1234 on 01-07-26 towards SALARY JUL26. Ref 998877665544",
    "Rs.599.00 credited to your A/c XX1234 on 08-07-26 from AMAZON refund. Ref 553201998877",
    "Rs.199.00 debited from A/c XX1234 for NETFLIX autopay on 08-07-26. UPI Ref 512345678901",
]

# Junk that deliberately carries account numbers and reference ids — the
# hardest false positives, since account evidence alone must not be enough.
JUNK_WITH_ACCOUNT_EVIDENCE = [
    "Your HDFC Card XX1234 statement: total amount due Rs 12,340.00 by 15-07-26. Ref 553201998877",
    "EMI of Rs 4,500 for loan A/c XX9988 is due on 15-07-26. Ref 512345678901",
    "Payment request of Rs.999 from netflix@icici on your UPI A/c. Ref 512345678901",
    "Rs.199 will be debited from A/c XX1234 on 15-07 for NETFLIX autopay. Ref 512345678901",
    "Txn of Rs.5000 on Card XX1234 at AMAZON was declined on 08-07-26. Ref 553201998877",
    "OTP 456789 for txn of Rs.2,500 on your A/c XX1234. Do not share. Ref 512345678901",
    "Pre-approved personal loan of Rs 5,00,000 on your A/c XX1234. Apply now Ref OFFER123456",
    "Your CIBIL score updated. Get credit card on A/c XX1234. Ref 553201998877",
    "URGENT: A/c XX1234 will be blocked. Verify KYC to receive Rs 10000. Ref 512345678901",
    "Congratulations! Rs 50000 credited to A/c XX1234. Claim now! Ref 553201998877",
    "Recharge of Rs 239 successful for 9876543210. Txn ID 512345678901. Plan validity 28 days",
    "Rs.500 transaction on A/c XX1234 has been reversed on 08-07-26. Ref 512345678901",
    "Your A/c XX1234 balance is Rs 15,230.50 as on 08-07-26",
    "Dear customer, minimum balance in A/c XX1234 is Rs 10,000. Maintain to avoid charges",
]


def test_recall_across_major_indian_banks():
    """Strict filtering must not silently drop real transactions."""
    missed = [s for s in BANK_CORPUS if not parsing.parse_sms(s).matched]
    assert not missed, f"real bank SMS rejected: {missed}"


def test_no_false_positives_even_with_account_evidence():
    """An account number or ref id alone must never make junk a transaction."""
    leaked = [s for s in JUNK_WITH_ACCOUNT_EVIDENCE if parsing.parse_sms(s).matched]
    assert not leaked, f"junk captured as transactions: {leaked}"


def test_bulk_review_groups_by_merchant(tmp_path):
    """209 captures one-by-one is unusable; they must group by merchant."""
    c = _su_client(tmp_path)
    # Three captures from one unknown merchant + one from another.
    for i, ref in enumerate(("111111111111", "222222222222", "333333333333")):
        c.post("/sms/ingest", data={"sender": "VK-HDFCBK", "body":
               f"Rs.{100 + i}.00 debited from a/c XX12 to RAJUKIRANA on 0{i+1}-07-26 Ref {ref}"})
    c.post("/sms/ingest", data={"body":
           "Rs.500.00 debited from a/c XX12 to LOCALSTORE on 05-07-26 Ref 444444444444"})
    page = c.get("/review")
    assert page.status_code == 200
    assert b"RAJUKIRANA" in page.data.upper() and b"LOCALSTORE" in page.data.upper()
    assert b"3 transactions" in page.data          # grouped, not listed individually


def test_bulk_categorize_clears_whole_group_and_teaches(tmp_path):
    c = _su_client(tmp_path)
    for i, ref in enumerate(("111111111111", "222222222222", "333333333333")):
        c.post("/sms/ingest", data={"body":
               f"Rs.{100 + i}.00 debited from a/c XX12 to RAJUKIRANA on 0{i+1}-07-26 Ref {ref}"})
    import re
    cat_id = re.search(rb'name="category_id" value="([0-9a-f]+)"',
                       c.get("/review").data).group(1).decode()
    c.post("/review/bulk", data={"key": "RAJUKIRANA", "type": "expense",
                                 "category_id": cat_id})
    # All three sorted in one action.
    assert b"All caught up" in c.get("/review").data
    assert b"New transaction from SMS" not in c.get("/dashboard").data
    # And the engine learned it: a new RAJUKIRANA message needs no category.
    j = c.post("/sms/ingest", data={"body":
        "Rs.999.00 debited from a/c XX12 to RAJUKIRANA on 09-07-26 Ref 555555555555"}).get_json()
    assert j["captured"] is True and j["needs_category"] is False


def test_bulk_delete_removes_junk_group(tmp_path):
    c = _su_client(tmp_path)
    for ref in ("111111111111", "222222222222"):
        c.post("/sms/ingest", data={"body":
               f"Rs.700.00 debited from a/c XX12 to WEIRDSENDER on 01-07-26 Ref {ref}"})
    c.post("/review/bulk", data={"key": "WEIRDSENDER", "type": "expense",
                                 "action": "delete"})
    assert b"WEIRDSENDER" not in c.get("/transactions").data.upper()
    assert b"All caught up" in c.get("/review").data


def test_self_transfer_not_counted_as_spending(tmp_path):
    """A debit+credit of the same amount minutes apart is the user's own money
    moving — counting it as both spend and income makes totals simply wrong."""
    from spendwise import analytics, db as sdb
    import datetime as _dt
    path = str(tmp_path / "tf.db")
    c = create_app(db_path=path, single_user=True, secret_key="s").test_client()
    today = _dt.date.today().isoformat()
    for kind in ("expense", "income"):
        c.post("/transactions", data={"amount": "5000.00", "type": kind,
               "merchant": "SELF", "category_id": "", "notes": "", "occurred_at": today})
    conn = sdb.connect(path)
    uid = sdb.one(conn, "SELECT id FROM users")["id"]
    flow = analytics.money_flow(conn, uid)
    assert len(flow["transfers"]) == 1
    assert flow["transfer_total"] == 5000.0
    assert flow["expense_net"] == 0.0 and flow["income_net"] == 0.0


def test_refund_is_netted_against_the_original_spend(tmp_path):
    from spendwise import analytics, db as sdb
    import datetime as _dt
    path = str(tmp_path / "rf.db")
    c = create_app(db_path=path, single_user=True, secret_key="s").test_client()
    today = _dt.date.today()
    c.post("/transactions", data={"amount": "1200.00", "type": "expense",
           "merchant": "Amazon", "category_id": "", "notes": "",
           "occurred_at": (today - _dt.timedelta(days=5)).isoformat()})
    c.post("/transactions", data={"amount": "1200.00", "type": "income",
           "merchant": "Amazon", "category_id": "", "notes": "",
           "occurred_at": today.isoformat()})
    conn = sdb.connect(path)
    uid = sdb.one(conn, "SELECT id FROM users")["id"]
    flow = analytics.money_flow(conn, uid)
    assert len(flow["refunds"]) == 1 and flow["refund_total"] == 1200.0
    assert flow["expense_net"] == 0.0


def test_refund_larger_than_purchase_is_not_matched(tmp_path):
    """A credit bigger than any prior debit is income, not a refund."""
    from spendwise import analytics, db as sdb
    import datetime as _dt
    path = str(tmp_path / "rf2.db")
    c = create_app(db_path=path, single_user=True, secret_key="s").test_client()
    today = _dt.date.today()
    c.post("/transactions", data={"amount": "100.00", "type": "expense",
           "merchant": "Acme", "category_id": "", "notes": "",
           "occurred_at": (today - _dt.timedelta(days=3)).isoformat()})
    c.post("/transactions", data={"amount": "50000.00", "type": "income",
           "merchant": "Acme", "category_id": "", "notes": "",
           "occurred_at": today.isoformat()})
    conn = sdb.connect(path)
    uid = sdb.one(conn, "SELECT id FROM users")["id"]
    assert analytics.money_flow(conn, uid)["refunds"] == []


def test_ingest_rate_limited(tmp_path):
    """Even with a valid token, ingest must not accept unbounded writes."""
    c = _su_client(tmp_path)
    codes = set()
    for i in range(140):
        r = c.post("/sms/ingest", data={"body":
            f"Rs.{10 + i}.00 debited from a/c XX12 to SHOP{i} on 01-07-26 Ref {i:012d}"})
        codes.add(r.status_code)
    assert 429 in codes, "rate limit never engaged"


def test_a_search_with_no_matches_does_not_claim_the_ledger_is_empty(auth_client):
    """Activity had one empty state for two different situations. Searching
    for something with no matches told a user with a full ledger "No
    transactions yet — your bank SMS are captured automatically": it claims
    their data is gone, hides that a filter is applied, and offers to add a
    transaction when what they want is to clear the search.
    """
    _add(auth_client, amount="300.00", merchant="Swiggy")
    _add(auth_client, amount="400.00", merchant="Zomato")

    miss = auth_client.get("/transactions?q=zzzznothing").data
    assert b"No matches" in miss
    assert b"No transactions yet" not in miss, \
        "a filtered-to-nothing view claimed the ledger was empty"
    assert b"still there" in miss, "it did not reassure that the data remains"
    assert b"Clear search" in miss, "no way back from an empty result"

    # A genuinely empty ledger must still get the onboarding message.
    empty = auth_client.get("/transactions?q=Swiggy").data
    assert b"Swiggy" in empty


def test_a_build_without_sms_access_reports_unavailable_not_denied(tmp_path):
    """The no-SMS flavour exists because Play Protect refuses to sideload any
    app that requests READ_SMS. On that build there is no permission to
    grant, so reporting "denied" would nag the user forever to allow
    something the APK never asks for.
    """
    app = create_app(db_path=str(tmp_path / "nosms.db"), single_user=True,
                     secret_key="t", device_token="tok")
    c = app.test_client()
    c.get("/?k=tok")
    c.post("/welcome/done")

    r = c.post("/device/state", data={"sms_permission": "unavailable"},
               headers={"X-SpendWise-Token": "tok"})
    assert r.status_code == 200

    settings = c.get("/settings").data
    assert b"Not in this build" in settings
    assert b"Allow SMS access" not in settings, \
        "offered to grant a permission this build never requests"
    assert b"paste a bank message" in settings.lower() or b"Import" in settings, \
        "no route to the thing that still works"

    # And the nag banner must not appear on any screen.
    for path in ("/dashboard", "/transactions", "/settings"):
        assert b"Auto-capture is paused" not in c.get(path).data, \
            f"{path} nagged about a permission that cannot exist"


def test_a_real_refusal_still_gets_the_banner(tmp_path):
    """The opposite case must keep working: someone who actually declined
    should still be offered a way to turn capture on."""
    app = create_app(db_path=str(tmp_path / "denied.db"), single_user=True,
                     secret_key="t", device_token="tok")
    c = app.test_client()
    c.get("/?k=tok")
    c.post("/welcome/done")
    c.post("/device/state", data={"sms_permission": "denied"},
           headers={"X-SpendWise-Token": "tok"})
    settings = c.get("/settings").data
    assert b"Permission off" in settings
    assert b"Allow SMS access" in settings
    assert b"Auto-capture is paused" in c.get("/dashboard").data
