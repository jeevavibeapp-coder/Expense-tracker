"""End-to-end tests for the server-rendered web UI (Jinja + HTMX)."""
from __future__ import annotations


def _signup(client, email="web@example.com"):
    return client.post("/signup", data={
        "full_name": "Web User", "email": email, "password": "supersecret1",
    }, follow_redirects=True)


def test_login_page_renders(client):
    r = client.get("/login")
    assert r.status_code == 200
    assert "Sign in" in r.text


def test_unauthenticated_redirects_to_login(client):
    r = client.get("/dashboard", follow_redirects=False)
    assert r.status_code == 303
    assert r.headers["location"] == "/login"


def test_signup_then_dashboard(client):
    r = _signup(client)
    assert r.status_code == 200
    assert "Dashboard" in r.text
    # Auth cookie was set.
    assert "access_token" in client.cookies


def test_add_transaction_and_list(client):
    _signup(client, "tx@example.com")
    r = client.post("/transactions", data={
        "amount": "250.00", "type": "expense", "merchant": "Starbucks",
        "category_id": "", "notes": "coffee", "occurred_at": "",
    }, follow_redirects=True)
    assert r.status_code == 200
    assert "Starbucks" in r.text
    assert "Confirmed" in r.text  # user-provided merchant is auto-confirmed


def test_live_resolve_after_learning(client):
    _signup(client, "learn@example.com")
    # Record the same payee several times so the engine builds confidence.
    for _ in range(4):
        client.post("/transactions", data={
            "amount": "250.00", "type": "expense", "merchant": "Starbucks",
            "category_id": "", "notes": "", "occurred_at": "",
        }, follow_redirects=True)
    # Live resolve preview for the learned payee (HTMX endpoint).
    r = client.post("/transactions/resolve",
                    data={"merchant": "Starbucks", "amount": "250"},
                    headers={"HX-Request": "true"})
    assert r.status_code == 200
    assert "Starbucks" in r.text
    assert "%" in r.text  # confidence badge rendered


def test_sms_import_flow(client):
    _signup(client, "sms@example.com")
    parse = client.post("/import/parse", data={
        "sms": "Rs.450.00 debited from a/c **1234 on 05-Jan-2024 to ZOMATO "
               "Ref 883120114455 UPI"},
        headers={"HX-Request": "true"})
    assert parse.status_code == 200
    assert "450" in parse.text and "ZOMATO" in parse.text.upper()
    create = client.post("/import/create", data={
        "amount": "450.00", "type": "expense", "raw_merchant": "ZOMATO",
        "reference_number": "883120114455", "occurred_at": "2024-01-05",
    }, headers={"HX-Request": "true"})
    assert create.status_code == 200
    assert "saved" in create.text.lower()


def test_fraud_and_settings_pages(client):
    _signup(client, "pages@example.com")
    assert client.get("/fraud").status_code == 200
    s = client.get("/settings")
    assert s.status_code == 200 and "Currency" in s.text
    upd = client.post("/settings", data={
        "currency": "USD", "theme": "dark", "auto_save_threshold": "85",
        "confirm_threshold": "40", "high_value_amount": "25000",
    }, follow_redirects=True)
    assert upd.status_code == 200
    assert "Settings saved" in upd.text and "USD" in upd.text


def test_logout_clears_session(client):
    _signup(client, "out@example.com")
    client.post("/logout", follow_redirects=False)
    r = client.get("/dashboard", follow_redirects=False)
    assert r.status_code == 303
