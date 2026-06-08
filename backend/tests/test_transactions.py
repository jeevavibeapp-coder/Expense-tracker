"""Transaction, SMS-parse, merchant-confirmation and offline-sync API tests."""
from __future__ import annotations


def _create(client, headers, **kw):
    payload = {"amount": "100.00", "type": "expense", **kw}
    return client.post("/api/v1/transactions", json=payload, headers=headers)


def test_manual_transaction_with_merchant_is_confirmed(auth):
    client, headers, _ = auth
    resp = _create(client, headers, amount="250.00", merchant_name="Starbucks",
                   raw_merchant="RAJESH")
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["transaction"]["merchant_name"] == "Starbucks"
    assert body["transaction"]["status"] == "confirmed"
    assert body["decision"] == "auto_saved"


def test_learning_drives_autosave_over_time(auth):
    client, headers, _ = auth
    # Teach RAJESH -> Starbucks four times via confirmed manual entries.
    for _ in range(4):
        _create(client, headers, amount="250.00", merchant_name="Starbucks",
                raw_merchant="RAJESH")
    # Now a raw-only transaction should auto-resolve.
    resp = _create(client, headers, amount="250.00", raw_merchant="RAJESH")
    body = resp.json()
    assert body["resolved_merchant"] == "Starbucks"
    assert body["confidence"] >= 80
    assert body["decision"] == "auto_saved"
    assert body["breakdown"]["total"] >= 80


def test_unknown_raw_name_needs_review(auth):
    client, headers, _ = auth
    resp = _create(client, headers, amount="99.00", raw_merchant="UNKNOWNGUY")
    body = resp.json()
    assert body["decision"] == "manual_required"
    assert body["transaction"]["status"] == "needs_review"


def test_confirm_merchant_endpoint(auth):
    client, headers, _ = auth
    tx_id = _create(client, headers, amount="80.00", raw_merchant="SURESH").json()["transaction"]["id"]
    resp = client.post("/api/v1/merchants/confirm", headers=headers, json={
        "transaction_id": tx_id, "merchant_name": "A2B"})
    assert resp.status_code == 200
    assert resp.json()["merchant_name"] == "A2B"
    assert resp.json()["status"] == "confirmed"
    # The learning store now knows SURESH -> A2B.
    learning = client.get("/api/v1/merchants/learning", headers=headers).json()
    assert any(l["merchant_name"] == "A2B" for l in learning)


def test_sms_parse_and_create(auth):
    client, headers, _ = auth
    sms = ("Rs.450.00 debited from a/c **1234 on 05-Jan-2024 to ZOMATO "
           "Ref 883120114455 UPI")
    parsed = client.post("/api/v1/transactions/parse-sms", headers=headers,
                         json={"text": sms}).json()
    assert parsed["matched"] is True
    assert parsed["amount"] == "450.00"
    assert parsed["raw_merchant"] and "ZOMATO" in parsed["raw_merchant"].upper()
    assert parsed["reference_number"] == "883120114455"


def test_list_and_search(auth):
    client, headers, _ = auth
    _create(client, headers, amount="100.00", merchant_name="KFC")
    _create(client, headers, amount="500.00", merchant_name="Starbucks")
    page = client.get("/api/v1/transactions", headers=headers).json()
    assert page["total"] == 2
    found = client.get("/api/v1/transactions?q=kfc", headers=headers).json()
    assert found["total"] == 1
    by_amount = client.get("/api/v1/transactions?min_amount=200", headers=headers).json()
    assert by_amount["total"] == 1


def test_update_and_delete(auth):
    client, headers, _ = auth
    tx_id = _create(client, headers, amount="100.00").json()["transaction"]["id"]
    upd = client.patch(f"/api/v1/transactions/{tx_id}", headers=headers,
                       json={"amount": "175.50", "notes": "lunch"})
    assert upd.status_code == 200
    assert upd.json()["amount"] == "175.50"
    assert client.delete(f"/api/v1/transactions/{tx_id}", headers=headers).status_code == 200
    assert client.get(f"/api/v1/transactions/{tx_id}", headers=headers).status_code == 404


def test_offline_sync_is_idempotent(auth):
    client, headers, _ = auth
    payload = {"transactions": [
        {"amount": "60.00", "type": "expense", "merchant_name": "Tea Shop",
         "client_id": "offline-1"},
        {"amount": "60.00", "type": "expense", "merchant_name": "Tea Shop",
         "client_id": "offline-1"},  # duplicate client_id
    ]}
    res = client.post("/api/v1/transactions/sync", headers=headers, json=payload).json()
    assert res["applied"] == 1
    assert res["skipped_duplicates"] == 1
    # Re-sending the same batch applies nothing new.
    res2 = client.post("/api/v1/transactions/sync", headers=headers, json=payload).json()
    assert res2["applied"] == 0
