"""Fraud / anomaly detection over the user's own transaction history."""
from __future__ import annotations

import datetime as dt
import json
import statistics
from typing import Optional

from . import db

DUPLICATE_WINDOW_MIN = 10
HIGH_VALUE_SIGMA = 3.0
ABNORMAL_DAY_FACTOR = 3.0
MIN_HISTORY = 8

SEV_LOW, SEV_MEDIUM, SEV_HIGH = "low", "medium", "high"


def _parse(ts) -> dt.datetime:
    if isinstance(ts, dt.datetime):
        return ts
    return dt.datetime.fromisoformat(ts)


def _expense_amounts(conn, user_id: str, exclude_id: Optional[str] = None) -> list[float]:
    rows = db.all_rows(
        conn,
        "SELECT amount FROM transactions WHERE user_id=? AND type='expense' "
        "AND is_deleted=0 AND id != ?",
        (user_id, exclude_id or ""))
    return [float(r["amount"]) for r in rows]


def _add_alert(conn, *, user_id, tx_id, alert_type, severity, message, details) -> str:
    aid = db.new_id()
    db.execute(conn,
               "INSERT INTO fraud_alerts(id,user_id,transaction_id,alert_type,severity,"
               "message,details,status,created_at) VALUES (?,?,?,?,?,?,?, 'open', ?)",
               (aid, user_id, tx_id, alert_type, severity, message,
                json.dumps(details), dt.datetime.now(dt.timezone.utc).isoformat()))
    return aid


def evaluate_transaction(conn, *, user_id: str, tx: dict,
                         high_value_limit: float = 0.0) -> list[str]:
    """Run all detectors for a newly created expense transaction; return alert ids."""
    if tx["type"] != "expense" or tx.get("is_deleted"):
        return []
    alerts: list[str] = []
    amount = float(tx["amount"])
    name = tx.get("merchant_name") or tx.get("raw_merchant")
    occurred = _parse(tx["occurred_at"])

    # 1. Duplicate within a short window (same amount + merchant).
    lo = (occurred - dt.timedelta(minutes=DUPLICATE_WINDOW_MIN)).isoformat()
    hi = (occurred + dt.timedelta(minutes=DUPLICATE_WINDOW_MIN)).isoformat()
    params = [user_id, tx["id"], amount, lo, hi]
    sql = ("SELECT COUNT(*) c FROM transactions WHERE user_id=? AND id!=? "
           "AND is_deleted=0 AND amount=? AND occurred_at>=? AND occurred_at<=?")
    if name and tx.get("merchant_name"):
        sql += " AND merchant_name=?"
        params.append(tx["merchant_name"])
    if db.one(conn, sql, tuple(params))["c"] > 0:
        alerts.append(_add_alert(
            conn, user_id=user_id, tx_id=tx["id"], alert_type="duplicate",
            severity=SEV_MEDIUM,
            message=f"Possible duplicate charge of {amount:.2f}" + (f" at {name}" if name else ""),
            details={"amount": amount, "merchant": name, "window_minutes": DUPLICATE_WINDOW_MIN}))

    history = _expense_amounts(conn, user_id, exclude_id=tx["id"])

    # 2. High-value outlier vs the user's own distribution.
    if len(history) >= MIN_HISTORY:
        mean = statistics.fmean(history)
        stdev = statistics.pstdev(history) or 1.0
        z = (amount - mean) / stdev
        if z >= HIGH_VALUE_SIGMA and amount > mean:
            alerts.append(_add_alert(
                conn, user_id=user_id, tx_id=tx["id"], alert_type="high_value_outlier",
                severity=SEV_HIGH,
                message=f"High-value transaction {amount:.2f} (~{z:.1f}σ above your average)",
                details={"amount": amount, "mean": round(mean, 2),
                         "stdev": round(stdev, 2), "sigma": round(z, 2)}))
    if high_value_limit and amount >= high_value_limit:
        alerts.append(_add_alert(
            conn, user_id=user_id, tx_id=tx["id"], alert_type="high_value_outlier",
            severity=SEV_HIGH,
            message=f"Transaction {amount:.2f} exceeds your high-value limit {high_value_limit:.2f}",
            details={"amount": amount, "limit": high_value_limit}))

    # 3. Abnormal daily spend.
    day_start = occurred.replace(hour=0, minute=0, second=0, microsecond=0)
    next_day = day_start + dt.timedelta(days=1)
    today_total = float(db.one(
        conn,
        "SELECT COALESCE(SUM(amount),0) s FROM transactions WHERE user_id=? AND type='expense' "
        "AND is_deleted=0 AND occurred_at>=? AND occurred_at<?",
        (user_id, day_start.isoformat(), next_day.isoformat()))["s"])
    if len(history) >= MIN_HISTORY:
        daily_avg = _daily_average(conn, user_id, before=day_start)
        if daily_avg > 0 and today_total >= daily_avg * ABNORMAL_DAY_FACTOR:
            alerts.append(_add_alert(
                conn, user_id=user_id, tx_id=tx["id"], alert_type="abnormal_spend",
                severity=SEV_MEDIUM,
                message=f"Today's spend {today_total:.2f} is well above your daily average {daily_avg:.2f}",
                details={"today": round(today_total, 2), "daily_average": round(daily_avg, 2)}))

    # 4. Unusual (first-seen) merchant at a high value.
    if name and tx.get("merchant_name") and len(history) >= MIN_HISTORY:
        seen = db.one(
            conn,
            "SELECT COUNT(*) c FROM transactions WHERE user_id=? AND id!=? AND is_deleted=0 "
            "AND merchant_name=?", (user_id, tx["id"], tx["merchant_name"]))["c"]
        mean = statistics.fmean(history)
        if seen == 0 and amount > mean * 2:
            alerts.append(_add_alert(
                conn, user_id=user_id, tx_id=tx["id"], alert_type="unusual_merchant",
                severity=SEV_LOW,
                message=f"First transaction with {name} and it's a large amount ({amount:.2f})",
                details={"merchant": name, "amount": amount, "average": round(mean, 2)}))
    return alerts


def _daily_average(conn, user_id: str, *, before: dt.datetime) -> float:
    rows = db.all_rows(
        conn,
        "SELECT SUM(amount) s FROM transactions WHERE user_id=? AND type='expense' "
        "AND is_deleted=0 AND occurred_at<? GROUP BY substr(occurred_at,1,10)",
        (user_id, before.isoformat()))
    totals = [float(r["s"]) for r in rows if r["s"] is not None]
    return statistics.fmean(totals) if totals else 0.0
