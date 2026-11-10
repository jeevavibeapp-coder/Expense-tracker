"""Local-only spending intelligence for the report screen.

Everything here is computed from the user's own SQLite rows on the device.
There is no model to download, no server to ask and nothing to send: the
"insight" is arithmetic over the ledger, phrased in a sentence.

Design rules these functions follow, learned from the first pass at insights:

  * Say the number that produced the claim. "You spent more on food" is a
    horoscope; "Food is 34% above your own 3-month average of Rs.8,200" can
    be checked and argued with.
  * Never speak from too little data. Every function has a minimum-evidence
    gate and returns nothing rather than a confident guess.
  * Never invent a recommendation the numbers do not support. A savings
    opportunity is only shown when there is a specific, quantified sum
    attached to it.
"""
from __future__ import annotations

import datetime as dt

from . import db
from .analytics import _finite, _now, _rupees


# ── shared helpers ────────────────────────────────────────────────────────
def _month_bounds(month: str) -> tuple[dt.datetime, dt.datetime]:
    y, mo = int(month[:4]), int(month[5:7])
    start = dt.datetime(y, mo, 1, tzinfo=dt.timezone.utc)
    end = dt.datetime(y + (1 if mo == 12 else 0), 1 if mo == 12 else mo + 1, 1,
                      tzinfo=dt.timezone.utc)
    return start, end


def _shift_month(start: dt.datetime, back: int) -> dt.datetime:
    y, mo = start.year, start.month - back
    while mo <= 0:
        mo += 12
        y -= 1
    return dt.datetime(y, mo, 1, tzinfo=dt.timezone.utc)


def _median(values: list[float]) -> float:
    if not values:
        return 0.0
    s = sorted(values)
    n = len(s)
    return s[n // 2] if n % 2 else (s[n // 2 - 1] + s[n // 2]) / 2


def _days_in(start: dt.datetime, end: dt.datetime) -> int:
    return max(1, (end - start).days)


# ── 1. Spending forecast ──────────────────────────────────────────────────
def forecast(conn, user_id: str, month: str) -> dict | None:
    """Project where this month lands, from the run-rate so far plus the
    recurring charges still due before it ends.

    Only ever computed for the month in progress — projecting a month that has
    already finished is just its total, dressed up as a prediction. Needs four
    elapsed days before it will say anything: a run-rate off one or two days
    swings by hundreds of percent and would be actively misleading.
    """
    start, end = _month_bounds(month)
    now = _now()
    if not (start <= now < end):
        return None
    elapsed = (now.date() - start.date()).days + 1
    total_days = (end.date() - start.date()).days
    if elapsed < 4 or elapsed >= total_days:
        return None

    spent = _finite(db.one(
        conn,
        "SELECT COALESCE(SUM(amount),0) s FROM transactions WHERE user_id=? "
        "AND type='expense' AND is_deleted=0 AND occurred_at>=? AND occurred_at<?",
        (user_id, start.isoformat(), end.isoformat()))["s"])
    if spent <= 0:
        return None

    remaining_days = total_days - elapsed
    run_rate = spent / elapsed
    projected = spent + run_rate * remaining_days

    # Recurring charges due later this month are known money, not a guess, so
    # they are added on top of the run-rate rather than assumed to be inside
    # it. Without this, a rent debit on the 28th makes every forecast before
    # the 28th far too low, every month.
    from .analytics import detect_recurring
    committed = 0.0
    upcoming = []
    for r in detect_recurring(conn, user_id):
        try:
            due = dt.date.fromisoformat(r["next_due"])
        except (TypeError, ValueError):
            continue
        if now.date() < due < end.date():
            committed += _finite(r["amount"])
            upcoming.append({"name": r["name"], "amount": _finite(r["amount"]),
                             "next_due": r["next_due"]})
    projected += committed

    prev_start = _shift_month(start, 1)
    prev_total = _finite(db.one(
        conn,
        "SELECT COALESCE(SUM(amount),0) s FROM transactions WHERE user_id=? "
        "AND type='expense' AND is_deleted=0 AND occurred_at>=? AND occurred_at<?",
        (user_id, prev_start.isoformat(), start.isoformat()))["s"])

    return {
        "spent": round(spent, 2),
        # Rounded to the nearest hundred on purpose. A projection is not a
        # measurement, and printing "Rs.62,811.47 projected" claims a
        # precision the arithmetic does not have — it invites the user to
        # trust a run-rate to the paisa.
        "projected": round(projected / 100.0) * 100.0,
        "run_rate": round(run_rate, 2),
        "committed": round(committed, 2),
        "upcoming": sorted(upcoming, key=lambda x: x["next_due"])[:4],
        "elapsed_days": elapsed,
        "remaining_days": remaining_days,
        "prev_total": round(prev_total, 2) if prev_total > 0 else None,
        "vs_prev": (round((round(projected / 100.0) * 100.0) - prev_total, 2)
                    if prev_total > 0 else None),
    }


# ── 2. Cash-flow timeline ─────────────────────────────────────────────────
def cash_flow(conn, user_id: str, months: int = 6) -> list[dict]:
    """Income, expense, net and a running net for the last N calendar months.

    The running total is the part the per-month bars cannot show: three months
    of small overspend look harmless side by side and are obvious the moment
    they are accumulated.
    """
    now = _now()
    first = _shift_month(now.replace(day=1, hour=0, minute=0, second=0,
                                     microsecond=0), months - 1)
    rows = db.all_rows(
        conn,
        "SELECT substr(occurred_at,1,7) p, type, COALESCE(SUM(amount),0) v "
        "FROM transactions WHERE user_id=? AND is_deleted=0 AND occurred_at>=? "
        "GROUP BY p, type", (user_id, first.isoformat()))
    buckets: dict[str, dict] = {}
    for r in rows:
        b = buckets.setdefault(r["p"], {"income": 0.0, "expense": 0.0})
        if r["type"] in b:
            b[r["type"]] += _finite(r["v"])

    out, running = [], 0.0
    cursor = first
    for _ in range(months):
        key = cursor.strftime("%Y-%m")
        b = buckets.get(key, {"income": 0.0, "expense": 0.0})
        net = b["income"] - b["expense"]
        running += net
        out.append({"period": key, "label": cursor.strftime("%b"),
                    "income": round(b["income"], 2),
                    "expense": round(b["expense"], 2),
                    "net": round(net, 2), "running": round(running, 2),
                    "empty": b["income"] == 0 and b["expense"] == 0})
        cursor = _shift_month(cursor, -1)
    return out


# ── 3. Category trends ────────────────────────────────────────────────────
def category_trends(conn, user_id: str, months: int = 6,
                    limit: int = 6) -> list[dict]:
    """Per-category spend across the last N months, with a direction.

    Direction compares the recent half against the older half rather than
    last month against the month before: one heavy grocery run should not be
    reported as a trend. A category needs spend in at least three of the
    months before it is called rising or falling at all.
    """
    now = _now().replace(day=1, hour=0, minute=0, second=0, microsecond=0)
    first = _shift_month(now, months - 1)
    keys = []
    cursor = first
    for _ in range(months):
        keys.append(cursor.strftime("%Y-%m"))
        cursor = _shift_month(cursor, -1)

    rows = db.all_rows(
        conn,
        "SELECT COALESCE(c.name,'Uncategorised') n, COALESCE(c.color,'#6d6d80') col, "
        "substr(t.occurred_at,1,7) p, COALESCE(SUM(t.amount),0) v "
        "FROM transactions t LEFT JOIN categories c ON c.id = t.category_id "
        "WHERE t.user_id=? AND t.type='expense' AND t.is_deleted=0 "
        "AND t.occurred_at>=? GROUP BY n, p", (user_id, first.isoformat()))

    by_cat: dict[str, dict] = {}
    for r in rows:
        c = by_cat.setdefault(r["n"], {"color": r["col"], "months": {}})
        c["months"][r["p"]] = round(_finite(r["v"]), 2)

    half = months // 2
    out = []
    for name, c in by_cat.items():
        series = [c["months"].get(k, 0.0) for k in keys]
        total = sum(series)
        if total <= 0:
            continue
        active = sum(1 for v in series if v > 0)
        older, recent = series[:half], series[half:]
        avg_old = sum(older) / len(older) if older else 0.0
        avg_new = sum(recent) / len(recent) if recent else 0.0
        direction, change = "steady", 0.0
        if active >= 3 and avg_old > 0:
            change = (avg_new - avg_old) / avg_old * 100
            if change >= 15:
                direction = "rising"
            elif change <= -15:
                direction = "falling"
        out.append({
            "name": name, "color": c["color"], "series": series,
            "labels": keys, "total": round(total, 2),
            "avg": round(total / months, 2),
            "direction": direction, "change_pct": round(change),
            "months_active": active,
        })
    out.sort(key=lambda x: x["total"], reverse=True)
    return out[:limit]


# ── 4. Merchant insights ──────────────────────────────────────────────────
def merchant_insights(conn, user_id: str, month: str,
                      limit: int = 5) -> list[dict]:
    """For this month's top merchants: visits, average ticket, and how the
    month compares with that merchant's own preceding three months.

    Comparing a merchant against itself is the only comparison that means
    anything — Rs.9,000 is alarming at a coffee shop and unremarkable at a
    landlord.
    """
    start, end = _month_bounds(month)
    base = _shift_month(start, 3)

    cur = db.all_rows(
        conn,
        "SELECT merchant_name n, COALESCE(SUM(amount),0) v, COUNT(*) c "
        "FROM transactions WHERE user_id=? AND type='expense' AND is_deleted=0 "
        "AND merchant_name IS NOT NULL AND merchant_name != '' "
        "AND occurred_at>=? AND occurred_at<? GROUP BY merchant_name "
        "ORDER BY v DESC LIMIT ?", (user_id, start.isoformat(), end.isoformat(), limit))
    if not cur:
        return []

    names = [r["n"] for r in cur]
    marks = ",".join("?" for _ in names)
    prior = {r["n"]: (_finite(r["v"]), r["c"], r["m"]) for r in db.all_rows(
        conn,
        f"SELECT merchant_name n, COALESCE(SUM(amount),0) v, COUNT(*) c, "
        f"COUNT(DISTINCT substr(occurred_at,1,7)) m FROM transactions "
        f"WHERE user_id=? AND type='expense' AND is_deleted=0 "
        f"AND merchant_name IN ({marks}) AND occurred_at>=? AND occurred_at<? "
        f"GROUP BY merchant_name",
        (user_id, *names, base.isoformat(), start.isoformat()))}

    out = []
    for r in cur:
        total, count = round(_finite(r["v"]), 2), r["c"]
        pv, pc, pm = prior.get(r["n"], (0.0, 0, 0))
        # Their own monthly average before this month. Fewer than two months
        # of history is not a baseline, so no comparison is offered.
        baseline = round(pv / pm, 2) if pm >= 2 else None
        change, multiple = None, None
        if baseline and baseline > 0:
            change = round((total - baseline) / baseline * 100)
            # Past roughly a tripling a percentage stops being readable —
            # "up 1265%" is a number nobody parses. A multiple is the same
            # fact in a form that lands: "13x what you usually spend here".
            if change >= 200:
                multiple = round(total / baseline, 1)
        out.append({
            "name": r["n"], "total": total, "visits": count,
            "avg_ticket": round(total / count, 2) if count else 0.0,
            "baseline": baseline, "change_pct": change, "multiple": multiple,
            "prior_visits": pc,
        })
    return out


# ── 5. Anomaly explanations ───────────────────────────────────────────────
ANOMALY_MULTIPLE = 2.5      # times the merchant's own median
ANOMALY_FLOOR = 300.0       # below this, a multiple is noise, not a spike
ANOMALY_MIN_HISTORY = 4     # prior charges needed before "usual" means anything


def anomalies(conn, user_id: str, month: str, limit: int = 4) -> list[dict]:
    """This month's charges that are far above what that merchant normally
    costs, each with the arithmetic that flagged it.

    Deliberately not a fraud signal — /fraud already owns that. This answers
    the quieter question "why was this month expensive?", where the answer is
    usually two or three unusually large but entirely legitimate charges.

    The median is used rather than the mean because one previous spike would
    drag a mean up far enough to hide the next one.
    """
    start, end = _month_bounds(month)
    hist_start = _shift_month(start, 6)

    rows = db.all_rows(
        conn,
        "SELECT id, merchant_name n, amount, occurred_at FROM transactions "
        "WHERE user_id=? AND type='expense' AND is_deleted=0 "
        "AND merchant_name IS NOT NULL AND merchant_name != '' "
        "AND occurred_at>=? AND occurred_at<?",
        (user_id, hist_start.isoformat(), end.isoformat()))

    history: dict[str, list[float]] = {}
    current: list[dict] = []
    a, b = start.isoformat(), end.isoformat()
    for r in rows:
        amt = _finite(r["amount"])
        if a <= r["occurred_at"] < b:
            current.append({"id": r["id"], "name": r["n"], "amount": amt,
                            "occurred_at": r["occurred_at"]})
        else:
            history.setdefault(r["n"], []).append(amt)

    out = []
    for t in current:
        past = history.get(t["name"], [])
        if len(past) < ANOMALY_MIN_HISTORY:
            continue
        usual = _median(past)
        if usual <= 0 or t["amount"] < ANOMALY_FLOOR:
            continue
        mult = t["amount"] / usual
        if mult < ANOMALY_MULTIPLE:
            continue
        out.append({
            "id": t["id"], "name": t["name"], "amount": round(t["amount"], 2),
            "usual": round(usual, 2), "multiple": round(mult, 1),
            "excess": round(t["amount"] - usual, 2),
            "date": t["occurred_at"][:10],
            "explanation": (f"{_rupees(t['amount'])} at {t['name']} is "
                            f"{round(mult, 1)}x your usual {_rupees(usual)} "
                            f"there ({len(past)} earlier charges)."),
        })
    out.sort(key=lambda x: x["excess"], reverse=True)
    return out[:limit]


# ── 6. Savings opportunities ──────────────────────────────────────────────
SMALL_TICKET = 200.0        # "just a small one" threshold
SMALL_TICKET_MIN_COUNT = 8  # below this the total is not worth a sentence


def savings_opportunities(conn, user_id: str, month: str,
                          limit: int = 4) -> list[dict]:
    """Concrete, quantified places money is leaking — never generic advice.

    Each opportunity carries the sum it is worth, because "consider reducing
    discretionary spending" is not something anyone can act on and "your 34
    orders under Rs.200 came to Rs.4,180 this month" is.
    """
    start, end = _month_bounds(month)
    out: list[dict] = []

    # a) Committed recurring spend. Money already promised before the month
    #    begins is the number people are most often surprised by.
    from .analytics import detect_recurring
    rec = detect_recurring(conn, user_id)
    monthly_equiv = 0.0
    for r in rec:
        amt = _finite(r["amount"])
        monthly_equiv += {"weekly": amt * 52 / 12, "monthly": amt,
                          "quarterly": amt / 3}.get(r["cadence"], 0.0)
    if len(rec) >= 2 and monthly_equiv > 0:
        out.append({
            "kind": "recurring", "amount": round(monthly_equiv, 2),
            "title": f"{len(rec)} recurring charges",
            "detail": (f"About {_rupees(monthly_equiv)} a month is committed "
                       f"before you spend anything. That is "
                       f"{_rupees(monthly_equiv * 12)} a year."),
            "items": [r["name"] for r in rec[:4]],
        })

    # b) Death by a thousand small payments. Individually invisible, and the
    #    single most common surprise in a UPI-era ledger.
    small = db.one(
        conn,
        "SELECT COUNT(*) c, COALESCE(SUM(amount),0) v FROM transactions "
        "WHERE user_id=? AND type='expense' AND is_deleted=0 AND amount < ? "
        "AND occurred_at>=? AND occurred_at<?",
        (user_id, SMALL_TICKET, start.isoformat(), end.isoformat()))
    if small and small["c"] >= SMALL_TICKET_MIN_COUNT:
        v = _finite(small["v"])
        if v > 0:
            out.append({
                "kind": "small", "amount": round(v, 2),
                "title": f"{small['c']} payments under {_rupees(SMALL_TICKET)}",
                "detail": (f"They add up to {_rupees(v)} this month — "
                           f"{_rupees(v / small['c'])} at a time."),
                "items": [],
            })

    # c) Categories running above the user's own recent average. Their own
    #    history is the only fair benchmark; a national average is not.
    base = _shift_month(start, 3)
    cur_rows = {r["n"]: _finite(r["v"]) for r in db.all_rows(
        conn,
        "SELECT COALESCE(c.name,'Uncategorised') n, COALESCE(SUM(t.amount),0) v "
        "FROM transactions t LEFT JOIN categories c ON c.id=t.category_id "
        "WHERE t.user_id=? AND t.type='expense' AND t.is_deleted=0 "
        "AND t.occurred_at>=? AND t.occurred_at<? GROUP BY n",
        (user_id, start.isoformat(), end.isoformat()))}
    base_rows = {r["n"]: (_finite(r["v"]), r["m"]) for r in db.all_rows(
        conn,
        "SELECT COALESCE(c.name,'Uncategorised') n, COALESCE(SUM(t.amount),0) v, "
        "COUNT(DISTINCT substr(t.occurred_at,1,7)) m "
        "FROM transactions t LEFT JOIN categories c ON c.id=t.category_id "
        "WHERE t.user_id=? AND t.type='expense' AND t.is_deleted=0 "
        "AND t.occurred_at>=? AND t.occurred_at<? GROUP BY n",
        (user_id, base.isoformat(), start.isoformat()))}
    over = []
    for name, v in cur_rows.items():
        # "Uncategorised is running high" is not an opportunity — it is a
        # data-quality state, and the action it implies (spend less on
        # Uncategorised) is not a thing anyone can do.
        if name == "Uncategorised":
            continue
        pv, pm = base_rows.get(name, (0.0, 0))
        if pm < 2 or pv <= 0:
            continue
        avg = pv / pm
        if v > avg * 1.25 and (v - avg) >= 500:
            over.append((v - avg, name, v, avg))
    over.sort(reverse=True)
    for excess, name, v, avg in over[:2]:
        out.append({
            "kind": "category", "amount": round(excess, 2),
            "title": f"{name} is running high",
            "detail": (f"{_rupees(v)} this month against your own 3-month "
                       f"average of {_rupees(avg)} — {_rupees(excess)} more."),
            "items": [],
        })

    out.sort(key=lambda x: x["amount"], reverse=True)
    return out[:limit]


# ── assembly ──────────────────────────────────────────────────────────────
def build_insights(conn, user_id: str, month: str) -> dict:
    """Everything the report screen's intelligence section needs, in one call.

    Each part is independent and any of them may legitimately be empty; the
    template shows only what has evidence behind it, so a two-week-old ledger
    renders a short honest page instead of a wall of placeholders.
    """
    return {
        "forecast": forecast(conn, user_id, month),
        "cash_flow": cash_flow(conn, user_id),
        "category_trends": category_trends(conn, user_id),
        "merchants": merchant_insights(conn, user_id, month),
        "anomalies": anomalies(conn, user_id, month),
        "savings": savings_opportunities(conn, user_id, month),
    }
