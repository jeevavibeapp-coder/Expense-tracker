"""Dashboard & analytics aggregation (all from the user's real data)."""
from __future__ import annotations

import datetime as dt

from . import db


def _now() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc)


def _sum_expense_since(conn, user_id: str, since_iso: str) -> float:
    return float(db.one(
        conn,
        "SELECT COALESCE(SUM(amount),0) s FROM transactions WHERE user_id=? AND type='expense' "
        "AND is_deleted=0 AND occurred_at>=?", (user_id, since_iso))["s"])


def _total(conn, user_id: str, type_: str) -> float:
    return float(db.one(
        conn,
        "SELECT COALESCE(SUM(amount),0) s FROM transactions WHERE user_id=? AND type=? "
        "AND is_deleted=0", (user_id, type_))["s"])


def _top_merchants(conn, user_id: str, limit: int = 10) -> list[dict]:
    rows = db.all_rows(
        conn,
        "SELECT merchant_name n, SUM(amount) v FROM transactions WHERE user_id=? AND type='expense' "
        "AND is_deleted=0 AND merchant_name IS NOT NULL AND merchant_name != '' "
        "GROUP BY merchant_name ORDER BY v DESC LIMIT ?", (user_id, limit))
    return [{"name": r["n"], "value": round(float(r["v"]), 2)} for r in rows]


def _category_breakdown(conn, user_id: str) -> list[dict]:
    rows = db.all_rows(
        conn,
        "SELECT c.name n, SUM(t.amount) v FROM transactions t JOIN categories c "
        "ON c.id = t.category_id WHERE t.user_id=? AND t.type='expense' AND t.is_deleted=0 "
        "GROUP BY c.name ORDER BY v DESC", (user_id,))
    return [{"name": r["n"], "value": round(float(r["v"]), 2)} for r in rows]


def _cap_breakdown(cats: list[dict], n: int = 5) -> list[dict]:
    """Keep the donut readable when one category dominates: show the top n and
    roll the long tail into a single 'Other' slice."""
    if len(cats) <= n + 1:
        return cats
    other = round(sum(c["value"] for c in cats[n:]), 2)
    return cats[:n] + [{"name": "Other", "value": other}]


def month_category_spend(conn, user_id: str, month_start_iso: str) -> dict[str, float]:
    """Spend so far this month, keyed by category id."""
    rows = db.all_rows(
        conn,
        "SELECT category_id cid, SUM(amount) v FROM transactions WHERE user_id=? "
        "AND type='expense' AND is_deleted=0 AND category_id IS NOT NULL "
        "AND occurred_at>=? GROUP BY category_id", (user_id, month_start_iso))
    return {r["cid"]: round(float(r["v"]), 2) for r in rows}


def budget_status(conn, user_id: str, month_start_iso: str) -> list[dict]:
    """Progress against each category's monthly budget, most-spent-first."""
    spent = month_category_spend(conn, user_id, month_start_iso)
    rows = db.all_rows(
        conn,
        "SELECT id, name, color, budget_amount FROM categories WHERE user_id=? "
        "AND type='expense' AND is_archived=0 AND budget_amount IS NOT NULL "
        "AND budget_amount > 0", (user_id,))
    out = []
    for r in rows:
        s = spent.get(r["id"], 0.0)
        b = float(r["budget_amount"])
        out.append({"id": r["id"], "name": r["name"], "color": r["color"],
                    "budget": round(b, 2), "spent": s,
                    "pct": round(s / b * 100) if b else 0})
    out.sort(key=lambda x: x["pct"], reverse=True)
    return out


def _trend(conn, user_id: str, months: int = 6) -> list[dict]:
    rows = db.all_rows(
        conn,
        "SELECT substr(occurred_at,1,7) p, type, SUM(amount) v FROM transactions "
        "WHERE user_id=? AND is_deleted=0 GROUP BY p, type ORDER BY p", (user_id,))
    buckets: dict[str, dict] = {}
    for r in rows:
        b = buckets.setdefault(r["p"], {"income": 0.0, "expense": 0.0})
        b[r["type"]] = b[r["type"]] + float(r["v"])
    out = [{"period": p, "income": round(buckets[p]["income"], 2),
            "expense": round(buckets[p]["expense"], 2)} for p in sorted(buckets)]
    return out[-months:]


def _insights(monthly, weekly, top_merchants, category_breakdown, pending, fraud_open,
              budgets=()) -> list[str]:
    out = []
    over = [b for b in budgets if b["pct"] > 100]
    near = [b for b in budgets if 85 <= b["pct"] <= 100]
    if over:
        out.append(f"You're over budget in {over[0]['name']} "
                   f"({over[0]['spent']:.0f} of {over[0]['budget']:.0f}).")
    elif near:
        out.append(f"{near[0]['name']} is at {near[0]['pct']}% of its monthly budget.")
    if monthly > 0:
        out.append(f"You've spent {monthly:.0f} so far this month.")
    if category_breakdown:
        out.append(f"Your biggest category is {category_breakdown[0]['name']} "
                   f"({category_breakdown[0]['value']:.0f}).")
    if top_merchants:
        out.append(f"You spend the most at {top_merchants[0]['name']} "
                   f"({top_merchants[0]['value']:.0f}).")
    if weekly > 0 and monthly > 0 and (weekly / monthly) * 100 > 40:
        out.append("A large share of this month's spending happened in the last 7 days.")
    if pending:
        out.append(f"{pending} transaction(s) need merchant confirmation.")
    if fraud_open:
        out.append(f"{fraud_open} fraud alert(s) need your attention.")
    if not out:
        out.append("Add your first transaction to start seeing insights.")
    return out


def build_dashboard(conn, user_id: str, currency: str = "INR") -> dict:
    now = _now()
    day_start = now.replace(hour=0, minute=0, second=0, microsecond=0)
    week_start = day_start - dt.timedelta(days=day_start.weekday())
    month_start = day_start.replace(day=1)

    daily = round(_sum_expense_since(conn, user_id, day_start.isoformat()), 2)
    weekly = round(_sum_expense_since(conn, user_id, week_start.isoformat()), 2)
    monthly = round(_sum_expense_since(conn, user_id, month_start.isoformat()), 2)
    income = round(_total(conn, user_id, "income"), 2)
    expense = round(_total(conn, user_id, "expense"), 2)
    top = _top_merchants(conn, user_id)
    cats_full = _category_breakdown(conn, user_id)
    cats = _cap_breakdown(cats_full)

    pending = db.one(
        conn,
        "SELECT COUNT(*) c FROM transactions WHERE user_id=? AND is_deleted=0 "
        "AND status IN ('pending_confirmation','needs_review')", (user_id,))["c"]
    fraud_open = db.one(
        conn, "SELECT COUNT(*) c FROM fraud_alerts WHERE user_id=? AND status='open'",
        (user_id,))["c"]
    budgets = budget_status(conn, user_id, month_start.isoformat())

    return {
        "currency": currency, "daily_spend": daily, "weekly_spend": weekly,
        "monthly_spend": monthly, "total_income": income, "total_expense": expense,
        "balance": round(income - expense, 2), "top_merchants": top,
        "merchant_breakdown": top, "category_breakdown": cats,
        "trend": _trend(conn, user_id), "budgets": budgets,
        "insights": _insights(monthly, weekly, top, cats_full, pending, fraud_open, budgets),
        "open_fraud_alerts": fraud_open, "pending_confirmations": pending,
    }
