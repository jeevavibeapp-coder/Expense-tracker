"""Dashboard & analytics aggregation (all from the user's real data)."""
from __future__ import annotations

import datetime as dt

from . import db


def _now() -> dt.datetime:
    """Device-local wall-clock time stamped as UTC.

    Stored occurred_at values are local wall-clock times labelled +00:00 (the
    SMS parser keeps the printed local time, and the manual form stores the
    user's local date), so "now" must live in the same clock domain or every
    day/week/month boundary drifts by the UTC offset.
    """
    return dt.datetime.now().replace(microsecond=0, tzinfo=dt.timezone.utc)


def _tomorrow_iso() -> str:
    """Exclusive upper bound for 'so far' queries: start of tomorrow. Keeps
    future-dated transactions out of today/week/month tiles and budgets."""
    day_start = _now().replace(hour=0, minute=0, second=0, microsecond=0)
    return (day_start + dt.timedelta(days=1)).isoformat()


def _sum_expense_since(conn, user_id: str, since_iso: str, until_iso: str) -> float:
    return float(db.one(
        conn,
        "SELECT COALESCE(SUM(amount),0) s FROM transactions WHERE user_id=? AND type='expense' "
        "AND is_deleted=0 AND occurred_at>=? AND occurred_at<?",
        (user_id, since_iso, until_iso))["s"])


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
    """Spend so far this month, keyed by category id (future-dated excluded)."""
    rows = db.all_rows(
        conn,
        "SELECT category_id cid, SUM(amount) v FROM transactions WHERE user_id=? "
        "AND type='expense' AND is_deleted=0 AND category_id IS NOT NULL "
        "AND occurred_at>=? AND occurred_at<? GROUP BY category_id",
        (user_id, month_start_iso, _tomorrow_iso()))
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


def daily_series(conn, user_id: str, days: int = 14) -> list[dict]:
    """Expense totals per day for the last N days (zero-filled)."""
    now = _now()
    start = (now - dt.timedelta(days=days - 1)).replace(
        hour=0, minute=0, second=0, microsecond=0)
    rows = db.all_rows(
        conn,
        "SELECT substr(occurred_at,1,10) d, SUM(amount) v FROM transactions "
        "WHERE user_id=? AND type='expense' AND is_deleted=0 AND occurred_at>=? "
        "GROUP BY d", (user_id, start.isoformat()))
    got = {r["d"]: round(float(r["v"]), 2) for r in rows}
    return [{"d": (start + dt.timedelta(days=i)).strftime("%Y-%m-%d"),
             "v": got.get((start + dt.timedelta(days=i)).strftime("%Y-%m-%d"), 0.0)}
            for i in range(days)]


def no_spend_stats(conn, user_id: str) -> dict:
    """Gamification: current no-spend streak + no-spend days this month."""
    now = _now()
    start = (now - dt.timedelta(days=59)).replace(hour=0, minute=0, second=0, microsecond=0)
    spent_days = {r["d"] for r in db.all_rows(
        conn,
        "SELECT DISTINCT substr(occurred_at,1,10) d FROM transactions WHERE user_id=? "
        "AND type='expense' AND is_deleted=0 AND occurred_at>=?",
        (user_id, start.isoformat()))}
    today = now.date()
    streak, d = 0, today
    while d >= start.date() and d.strftime("%Y-%m-%d") not in spent_days:
        streak += 1
        d -= dt.timedelta(days=1)
    month_free = sum(1 for i in range(1, today.day + 1)
                     if today.replace(day=i).strftime("%Y-%m-%d") not in spent_days)
    return {"streak": streak, "month_free": month_free}


def detect_recurring(conn, user_id: str) -> list[dict]:
    """Find repeating charges (subscriptions / bills) and predict the next due
    date. A merchant qualifies with ≥3 charges (≥4 for weekly) at a
    weekly/monthly/quarterly cadence and a stable amount (range within 25% of
    the mean) — two coincidental purchases are not a bill."""
    rows = db.all_rows(
        conn,
        "SELECT merchant_name n, amount, occurred_at FROM transactions WHERE user_id=? "
        "AND type='expense' AND is_deleted=0 AND merchant_name IS NOT NULL "
        "AND merchant_name != '' ORDER BY merchant_name, occurred_at", (user_id,))
    by_merchant: dict[str, list] = {}
    for r in rows:
        by_merchant.setdefault(r["n"], []).append((r["occurred_at"][:10], float(r["amount"])))

    out = []
    today = _now().date()
    for name, txs in by_merchant.items():
        if len(txs) < 2:
            continue
        dates = [dt.date.fromisoformat(t[0]) for t in txs]
        gaps = [(dates[i + 1] - dates[i]).days for i in range(len(dates) - 1)]
        gaps = [gv for gv in gaps if gv > 0]
        if not gaps:
            continue
        med = sorted(gaps)[len(gaps) // 2]
        if 6 <= med <= 8:
            cadence = "weekly"
        elif 25 <= med <= 35:
            cadence = "monthly"
        elif 85 <= med <= 95:
            cadence = "quarterly"
        else:
            continue
        # Two data points can't establish a pattern; weekly needs even more.
        if len(txs) < (4 if cadence == "weekly" else 3):
            continue
        amounts = [t[1] for t in txs[-4:]]
        mean = sum(amounts) / len(amounts)
        if mean <= 0 or (max(amounts) - min(amounts)) > 0.25 * mean:
            continue
        next_due = dates[-1] + dt.timedelta(days=med)
        days_left = (next_due - today).days
        if days_left < -med:  # a full period overdue — probably cancelled
            continue
        out.append({"name": name, "amount": round(amounts[-1], 2), "cadence": cadence,
                    "next_due": next_due.isoformat(), "days_left": days_left})
    out.sort(key=lambda x: x["days_left"])
    return out[:6]


def _sum_between(conn, user_id: str, type_: str, a: str, b: str) -> float:
    return float(db.one(
        conn,
        "SELECT COALESCE(SUM(amount),0) s FROM transactions WHERE user_id=? AND type=? "
        "AND is_deleted=0 AND occurred_at>=? AND occurred_at<?",
        (user_id, type_, a, b))["s"])


def build_report(conn, user_id: str, month: str) -> dict:
    """Everything for the monthly report screen: totals, savings rate, per-
    category spend with vs-last-month deltas, top merchants, daily bars and
    headline stats for the given 'YYYY-MM'."""
    y, mo = int(month[:4]), int(month[5:7])
    start = dt.datetime(y, mo, 1, tzinfo=dt.timezone.utc)
    end = dt.datetime(y + (1 if mo == 12 else 0), 1 if mo == 12 else mo + 1, 1,
                      tzinfo=dt.timezone.utc)
    prev = dt.datetime(y - (1 if mo == 1 else 0), 12 if mo == 1 else mo - 1, 1,
                       tzinfo=dt.timezone.utc)
    a, b, pa = start.isoformat(), end.isoformat(), prev.isoformat()

    now = _now()
    is_current = start <= now < end

    income = round(_sum_between(conn, user_id, "income", a, b), 2)
    expense = round(_sum_between(conn, user_id, "expense", a, b), 2)
    # For the in-progress month, compare like with like: previous month only
    # up to the same day-of-month, not its full total.
    if is_current:
        prev_cut = min(prev + dt.timedelta(days=now.day), start)
        prev_expense = round(_sum_between(conn, user_id, "expense", pa,
                                          prev_cut.isoformat()), 2)
    else:
        prev_expense = round(_sum_between(conn, user_id, "expense", pa, a), 2)

    cat_sql = ("SELECT c.name n, c.color col, SUM(t.amount) v FROM transactions t "
               "JOIN categories c ON c.id = t.category_id WHERE t.user_id=? "
               "AND t.type='expense' AND t.is_deleted=0 AND t.occurred_at>=? "
               "AND t.occurred_at<? GROUP BY c.id ORDER BY v DESC")
    prev_rows = {r["n"]: (float(r["v"]), r["col"])
                 for r in db.all_rows(conn, cat_sql, (user_id, pa, a))}
    categories = [{"name": r["n"], "color": r["col"], "value": round(float(r["v"]), 2),
                   "delta": round(float(r["v"]) - prev_rows.get(r["n"], (0.0,))[0], 2)}
                  for r in db.all_rows(conn, cat_sql, (user_id, a, b))]
    # Categories with spend last month but none this month must still show
    # their drop, or per-category deltas stop explaining the headline delta.
    seen = {c["name"] for c in categories}
    for name, (pv, col) in prev_rows.items():
        if name not in seen and pv > 0:
            categories.append({"name": name, "color": col, "value": 0.0,
                               "delta": round(-pv, 2)})

    merchants = [{"name": r["n"], "value": round(float(r["v"]), 2)} for r in db.all_rows(
        conn,
        "SELECT merchant_name n, SUM(amount) v FROM transactions WHERE user_id=? "
        "AND type='expense' AND is_deleted=0 AND merchant_name IS NOT NULL "
        "AND merchant_name != '' AND occurred_at>=? AND occurred_at<? "
        "GROUP BY merchant_name ORDER BY v DESC LIMIT 5", (user_id, a, b))]

    day_rows = db.all_rows(
        conn,
        "SELECT substr(occurred_at,1,10) d, SUM(amount) v FROM transactions "
        "WHERE user_id=? AND type='expense' AND is_deleted=0 AND occurred_at>=? "
        "AND occurred_at<? GROUP BY d ORDER BY d", (user_id, a, b))
    spent = {r["d"]: round(float(r["v"]), 2) for r in day_rows}
    elapsed = now.day if is_current else (end - start).days
    daily = [{"d": i + 1, "v": spent.get(f"{month}-{i + 1:02d}", 0.0)}
             for i in range(elapsed)]
    highest = max(daily, key=lambda x: x["v"]) if daily else None

    tx_count = db.one(
        conn, "SELECT COUNT(*) c FROM transactions WHERE user_id=? AND is_deleted=0 "
        "AND occurred_at>=? AND occurred_at<?", (user_id, a, b))["c"]

    return {
        "month": month, "label": start.strftime("%B %Y"),
        "prev_m": prev.strftime("%Y-%m"), "next_m": end.strftime("%Y-%m"),
        "income": income, "expense": expense, "saved": round(income - expense, 2),
        "save_rate": round((income - expense) / income * 100) if income > 0 else None,
        "prev_expense": prev_expense,
        "expense_delta": round(expense - prev_expense, 2),
        "categories": categories, "merchants": merchants, "daily": daily,
        "highest_day": highest if highest and highest["v"] > 0 else None,
        "no_spend_days": sum(1 for x in daily if x["v"] == 0),
        "tx_count": tx_count,
    }


def _trend(conn, user_id: str, months: int = 6) -> list[dict]:
    rows = db.all_rows(
        conn,
        "SELECT substr(occurred_at,1,7) p, type, SUM(amount) v FROM transactions "
        "WHERE user_id=? AND is_deleted=0 GROUP BY p, type ORDER BY p", (user_id,))
    buckets: dict[str, dict] = {}
    for r in rows:
        b = buckets.setdefault(r["p"], {"income": 0.0, "expense": 0.0})
        if r["type"] in b:
            b[r["type"]] += float(r["v"])
    if not buckets:
        return []
    # Zero-fill gaps so adjacent bars are always consecutive calendar months.
    periods = sorted(buckets)
    y, mth = int(periods[0][:4]), int(periods[0][5:7])
    out = []
    while True:
        p = f"{y:04d}-{mth:02d}"
        b = buckets.get(p, {"income": 0.0, "expense": 0.0})
        out.append({"period": p, "income": round(b["income"], 2),
                    "expense": round(b["expense"], 2)})
        if p >= periods[-1]:
            break
        mth += 1
        if mth == 13:
            y, mth = y + 1, 1
    return out[-months:]


def _insights(monthly, weekly, top_merchants, category_breakdown, pending, fraud_open,
              budgets=(), recurring=(), streak=0) -> list[str]:
    out = []
    over = [b for b in budgets if b["pct"] > 100]
    near = [b for b in budgets if 85 <= b["pct"] <= 100]
    if over:
        out.append(f"You're over budget in {over[0]['name']} "
                   f"({over[0]['spent']:.0f} of {over[0]['budget']:.0f}).")
    elif near:
        out.append(f"{near[0]['name']} is at {near[0]['pct']}% of its monthly budget.")
    due_soon = [r for r in recurring if 0 <= r["days_left"] <= 5]
    if due_soon:
        r = due_soon[0]
        when = "today" if r["days_left"] == 0 else (
            "tomorrow" if r["days_left"] == 1 else f"in {r['days_left']} days")
        out.append(f"{r['name']} (~{r['amount']:.0f}) is due {when}.")
    if streak >= 2:
        out.append(f"You're on a {streak}-day no-spend streak — keep it going!")
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

    until = _tomorrow_iso()
    # One conditional-aggregate pass for all three "so far" tiles.
    lo = min(week_start, month_start).isoformat()
    tiles = db.one(
        conn,
        "SELECT COALESCE(SUM(CASE WHEN occurred_at>=:d THEN amount END),0) d, "
        "COALESCE(SUM(CASE WHEN occurred_at>=:w THEN amount END),0) w, "
        "COALESCE(SUM(CASE WHEN occurred_at>=:m THEN amount END),0) m "
        "FROM transactions WHERE user_id=:u AND type='expense' AND is_deleted=0 "
        "AND occurred_at>=:lo AND occurred_at<:until",
        {"u": user_id, "d": day_start.isoformat(), "w": week_start.isoformat(),
         "m": month_start.isoformat(), "lo": lo, "until": until})
    daily, weekly, monthly = (round(float(tiles["d"]), 2), round(float(tiles["w"]), 2),
                              round(float(tiles["m"]), 2))
    # One pass for all-time totals + transaction count.
    totals = {r["type"]: (float(r["s"]), r["c"]) for r in db.all_rows(
        conn, "SELECT type, COALESCE(SUM(amount),0) s, COUNT(*) c FROM transactions "
        "WHERE user_id=? AND is_deleted=0 GROUP BY type", (user_id,))}
    income = round(totals.get("income", (0.0, 0))[0], 2)
    expense = round(totals.get("expense", (0.0, 0))[0], 2)
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
    recurring = detect_recurring(conn, user_id)
    streak = no_spend_stats(conn, user_id)
    tx_count = sum(v[1] for v in totals.values())
    # A streak is only meaningful once there's spending history — a brand-new
    # install would otherwise claim a "60-day no-spend streak".
    if expense <= 0:
        streak = {"streak": 0, "month_free": 0}

    return {
        "currency": currency, "daily_spend": daily, "weekly_spend": weekly,
        "monthly_spend": monthly, "total_income": income, "total_expense": expense,
        "balance": round(income - expense, 2), "top_merchants": top,
        "merchant_breakdown": top, "category_breakdown": cats,
        "trend": _trend(conn, user_id), "budgets": budgets,
        "recurring": recurring, "streak": streak, "tx_count": tx_count,
        "daily_series": daily_series(conn, user_id),
        "insights": _insights(monthly, weekly, top, cats_full, pending, fraud_open,
                              budgets, recurring, streak["streak"]),
        "open_fraud_alerts": fraud_open, "pending_confirmations": pending,
    }
