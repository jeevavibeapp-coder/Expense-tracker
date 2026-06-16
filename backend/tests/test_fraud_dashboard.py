"""Fraud detection and dashboard aggregation tests."""
from __future__ import annotations

import datetime as dt


def _create(client, headers, **kw):
    return client.post("/api/v1/transactions",
                       json={"amount": "100.00", "type": "expense", **kw}, headers=headers)


def test_duplicate_transaction_alert(auth):
    client, headers, _ = auth
    now = dt.datetime(2024, 1, 1, 12, 0, tzinfo=dt.timezone.utc).isoformat()
    _create(client, headers, amount="500.00", merchant_name="KFC", occurred_at=now)
    second = _create(client, headers, amount="500.00", merchant_name="KFC", occurred_at=now)
    assert len(second.json()["fraud_alert_ids"]) >= 1
    alerts = client.get("/api/v1/fraud/alerts", headers=headers).json()
    assert any(a["alert_type"] == "duplicate" for a in alerts)


def test_high_value_outlier_alert(auth):
    client, headers, _ = auth
    # Build a stable small-spend history, then a huge outlier.
    for i in range(10):
        _create(client, headers, amount="100.00", merchant_name=f"Shop{i}",
                occurred_at=dt.datetime(2024, 1, i + 1, 10, 0, tzinfo=dt.timezone.utc).isoformat())
    big = _create(client, headers, amount="50000.00", merchant_name="BigStore",
                  occurred_at=dt.datetime(2024, 2, 1, 10, 0, tzinfo=dt.timezone.utc).isoformat())
    alerts = big.json()["fraud_alert_ids"]
    assert len(alerts) >= 1
    types = {a["alert_type"] for a in client.get("/api/v1/fraud/alerts", headers=headers).json()}
    assert "high_value_outlier" in types


def test_dismiss_alert(auth):
    client, headers, _ = auth
    now = dt.datetime(2024, 1, 1, 12, 0, tzinfo=dt.timezone.utc).isoformat()
    _create(client, headers, amount="500.00", merchant_name="KFC", occurred_at=now)
    _create(client, headers, amount="500.00", merchant_name="KFC", occurred_at=now)
    alert_id = client.get("/api/v1/fraud/alerts", headers=headers).json()[0]["id"]
    upd = client.patch(f"/api/v1/fraud/alerts/{alert_id}", headers=headers,
                       json={"status": "dismissed"})
    assert upd.status_code == 200 and upd.json()["status"] == "dismissed"
    assert client.get("/api/v1/fraud/alerts?status=open", headers=headers).json() == [] \
        or all(a["id"] != alert_id
               for a in client.get("/api/v1/fraud/alerts?status=open", headers=headers).json())


def test_dashboard_reflects_real_data_only(auth):
    client, headers, _ = auth
    # Empty dashboard: no fabricated numbers.
    empty = client.get("/api/v1/dashboard", headers=headers).json()
    assert empty["total_expense"] == "0.00"
    assert empty["monthly_spend"] == "0.00"
    assert empty["top_merchants"] == []

    now = dt.datetime.now(dt.timezone.utc).isoformat()
    _create(client, headers, amount="200.00", type="income", merchant_name="Salary",
            occurred_at=now)
    _create(client, headers, amount="50.00", merchant_name="KFC", occurred_at=now)
    dash = client.get("/api/v1/dashboard", headers=headers).json()
    assert dash["total_income"] == "200.00"
    assert dash["total_expense"] == "50.00"
    assert dash["balance"] == "150.00"
    assert any(m["name"] == "KFC" for m in dash["top_merchants"])
    assert isinstance(dash["insights"], list) and dash["insights"]
