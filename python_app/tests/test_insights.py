"""Local spending intelligence: forecasts, trends, anomalies, opportunities.

These functions produce sentences a user will act on, so the tests are
mostly about the ways an insight can be *confidently wrong*: speaking from
two data points, comparing a merchant against unrelated merchants, letting
one past spike hide the next one, or projecting a month from a single day.

Every function is expected to say nothing rather than guess, and each test
below that asserts an empty result is asserting exactly that.
"""
from __future__ import annotations

import datetime as dt

import pytest

from spendwise import analytics, db, insights


def _fresh(tmp_path, name="i.db"):
    path = str(tmp_path / name)
    conn, _ = db.open_database(path, backup=False)
    conn.execute("INSERT INTO users VALUES ('u1','a@b.c','U','x','2024-01-01')")
    conn.commit()
    return conn


def _cat(conn, cid, name, color="#abcdef", uid="u1"):
    conn.execute("INSERT INTO categories(id,user_id,name,color) VALUES (?,?,?,?)",
                 (cid, uid, name, color))
    conn.commit()


def _add(conn, amount, day, *, merchant=None, type_="expense", cat=None,
         uid="u1", tx_id=None):
    tid = tx_id or db.new_id()
    conn.execute(
        "INSERT INTO transactions(id,user_id,amount,type,occurred_at,source,"
        "status,created_at,merchant_name,category_id) "
        "VALUES (?,?,?,?,?,?,?,?,?,?)",
        (tid, uid, amount, type_, f"{day}T10:00:00+00:00", "manual",
         "confirmed", f"{day}T10:00:00+00:00", merchant, cat))
    conn.commit()
    return tid


FROZEN = dt.datetime(2025, 6, 17, 12, 0, 0, tzinfo=dt.timezone.utc)


@pytest.fixture(autouse=True)
def frozen_clock(monkeypatch):
    """Pin "now" for every test in this file.

    An earlier draft of these tests read the real clock and skipped whole
    cases on the wrong day of the month — a forecast test that only runs
    between the 4th and the 20th silently covers nothing for two thirds of
    the year. Both modules are patched because detect_recurring() reads
    analytics' own clock.
    """
    monkeypatch.setattr(insights, "_now", lambda: FROZEN)
    monkeypatch.setattr(analytics, "_now", lambda: FROZEN)


def _today():
    return FROZEN.date()


def _month_of(d):
    return d.strftime("%Y-%m")


def _shift(d, months):
    y, m = d.year, d.month - months
    while m <= 0:
        m += 12
        y -= 1
    return dt.date(y, m, 1)


# ── forecast ──────────────────────────────────────────────────────────────
def test_forecast_is_silent_for_a_finished_month(tmp_path):
    """A month that has already ended has a total, not a projection. Dressing
    a known number up as a forecast is theatre."""
    conn = _fresh(tmp_path)
    past = _shift(_today(), 2)
    _add(conn, 500, past.strftime("%Y-%m-05"))
    assert insights.forecast(conn, "u1", _month_of(past)) is None


def test_forecast_is_silent_in_the_first_days_of_a_month(tmp_path, monkeypatch):
    """A run-rate off one or two days swings by hundreds of percent. Better to
    say nothing than to tell someone they are on track for six times their
    income because they bought a laptop on the 1st."""
    conn = _fresh(tmp_path)
    early = FROZEN.replace(day=2)
    monkeypatch.setattr(insights, "_now", lambda: early)
    monkeypatch.setattr(analytics, "_now", lambda: early)
    _add(conn, 5000, early.date().isoformat())
    assert insights.forecast(conn, "u1", _month_of(early.date())) is None


def test_forecast_projects_from_the_run_rate(tmp_path):
    conn = _fresh(tmp_path)
    today = _today()
    # Spend 100/day for every elapsed day.
    for d in range(1, today.day + 1):
        _add(conn, 100, today.replace(day=d).isoformat())
    f = insights.forecast(conn, "u1", _month_of(today))
    assert f is not None
    assert f["run_rate"] == pytest.approx(100, abs=1)
    days = (insights._month_bounds(_month_of(today))[1].date()
            - insights._month_bounds(_month_of(today))[0].date()).days
    assert f["projected"] == pytest.approx(100 * days, abs=50), \
        "the projection is rounded to the nearest 100, but not by more"
    assert f["projected"] % 100 == 0, \
        "a projection printed to the paisa claims precision it does not have"


def test_forecast_adds_recurring_charges_still_due(tmp_path):
    """A rent debit on the 28th makes every forecast before the 28th too low,
    every single month, unless known commitments are added on top."""
    conn = _fresh(tmp_path)
    today = _today()
    for d in range(1, today.day + 1):
        _add(conn, 50, today.replace(day=d).isoformat())
    # A monthly charge on the 25th, three months running.
    for back in (3, 2, 1):
        prior = _shift(today, back)
        _add(conn, 9000, prior.replace(day=25).isoformat(), merchant="Landlord")

    f = insights.forecast(conn, "u1", _month_of(today))
    assert f is not None
    assert f["committed"] == pytest.approx(9000, abs=1), \
        "the rent due later this month was not counted as committed"
    assert f["projected"] > f["run_rate"] * 28, \
        "the projection ignored a charge it can already see coming"


# ── cash flow ─────────────────────────────────────────────────────────────
def test_cash_flow_running_total_accumulates(tmp_path):
    """Three months of small overspend look harmless side by side. The running
    total is the only column that makes them obvious."""
    conn = _fresh(tmp_path)
    today = _today()
    for back in (2, 1, 0):
        d = _shift(today, back).replace(day=2)
        _add(conn, 1000, d.isoformat(), type_="income")
        _add(conn, 1200, d.isoformat())

    flow = insights.cash_flow(conn, "u1", months=6)
    assert len(flow) == 6
    live = [f for f in flow if not f["empty"]]
    assert live, "no month had any activity"
    running = 0.0
    for f in flow:
        running += f["net"]
        assert f["running"] == pytest.approx(running, abs=0.01)
    assert live[-1]["running"] < 0, "consistent overspend should show as negative"


def test_cash_flow_zero_fills_quiet_months(tmp_path):
    """A gap month must appear as a zero bar, not be skipped — otherwise two
    non-adjacent months render side by side and the shape of the chart lies."""
    conn = _fresh(tmp_path)
    flow = insights.cash_flow(conn, "u1", months=6)
    assert len(flow) == 6
    assert all(f["empty"] for f in flow)
    periods = [f["period"] for f in flow]
    assert periods == sorted(periods)


# ── category trends ───────────────────────────────────────────────────────
def test_category_trend_needs_three_active_months(tmp_path):
    """Two heavy months in a row is not a trend, and calling it one teaches
    the user to distrust every other number on the screen."""
    conn = _fresh(tmp_path)
    _cat(conn, "c1", "Food")
    today = _today()
    for back in (1, 0):
        d = _shift(today, back).replace(day=3)
        _add(conn, 5000, d.isoformat(), cat="c1")
    trends = insights.category_trends(conn, "u1")
    assert all(t["direction"] == "steady" for t in trends), \
        "a direction was claimed from fewer than three active months"


def test_category_trend_detects_a_real_rise(tmp_path):
    conn = _fresh(tmp_path)
    _cat(conn, "c1", "Food")
    today = _today()
    # Older half small, recent half large, across six months.
    for back, amount in ((5, 1000), (4, 1000), (3, 1000),
                         (2, 4000), (1, 4000), (0, 4000)):
        d = _shift(today, back).replace(day=3)
        _add(conn, amount, d.isoformat(), cat="c1")
    t = [x for x in insights.category_trends(conn, "u1") if x["name"] == "Food"]
    assert t, "the category did not appear at all"
    assert t[0]["direction"] == "rising"
    assert t[0]["change_pct"] > 50


def test_category_trends_keep_uncategorised_visible(tmp_path):
    """Uncategorised is usually the largest 'category' in a fresh ledger.
    Dropping it makes the totals on this screen not add up."""
    conn = _fresh(tmp_path)
    _add(conn, 2500, _today().isoformat())
    names = [t["name"] for t in insights.category_trends(conn, "u1")]
    assert "Uncategorised" in names


# ── merchant insights ─────────────────────────────────────────────────────
def test_merchant_baseline_needs_two_prior_months(tmp_path):
    """One prior month is a data point, not a baseline. Comparing against it
    produces confident percentages built on nothing."""
    conn = _fresh(tmp_path)
    today = _today()
    _add(conn, 500, _shift(today, 1).replace(day=4).isoformat(), merchant="Cafe")
    _add(conn, 900, today.replace(day=1).isoformat(), merchant="Cafe")
    ms = insights.merchant_insights(conn, "u1", _month_of(today))
    assert ms and ms[0]["name"] == "Cafe"
    assert ms[0]["baseline"] is None
    assert ms[0]["change_pct"] is None


def test_merchant_is_compared_against_itself_only(tmp_path):
    """Rs.9,000 is alarming at a coffee shop and unremarkable at a landlord.
    A merchant's own history is the only fair benchmark."""
    conn = _fresh(tmp_path)
    today = _today()
    for back in (3, 2, 1):
        d = _shift(today, back).replace(day=5)
        _add(conn, 1000, d.isoformat(), merchant="Cafe")
        _add(conn, 40000, d.isoformat(), merchant="Landlord")
    _add(conn, 2000, today.replace(day=1).isoformat(), merchant="Cafe")
    _add(conn, 40000, today.replace(day=1).isoformat(), merchant="Landlord")

    by = {m["name"]: m for m in insights.merchant_insights(conn, "u1",
                                                           _month_of(today))}
    assert by["Cafe"]["change_pct"] == pytest.approx(100, abs=2), \
        "the cafe doubled and should say so"
    assert abs(by["Landlord"]["change_pct"]) <= 2, \
        "the landlord was flat and must not be flagged just for being large"


# ── anomalies ─────────────────────────────────────────────────────────────
def test_anomaly_needs_enough_history(tmp_path):
    conn = _fresh(tmp_path)
    today = _today()
    _add(conn, 200, _shift(today, 1).replace(day=2).isoformat(), merchant="Shop")
    _add(conn, 5000, today.replace(day=1).isoformat(), merchant="Shop")
    assert insights.anomalies(conn, "u1", _month_of(today)) == []


def test_anomaly_uses_the_median_so_a_past_spike_cannot_hide_the_next(tmp_path):
    """With a mean, one earlier spike raises the bar enough that the next one
    goes unreported — exactly when the user most needs to see it."""
    conn = _fresh(tmp_path)
    today = _today()
    prev = _shift(today, 1)
    for day, amt in ((2, 400), (5, 400), (8, 400), (12, 400), (15, 9000)):
        _add(conn, amt, prev.replace(day=day).isoformat(), merchant="Shop")
    _add(conn, 4000, today.replace(day=1).isoformat(), merchant="Shop")

    found = insights.anomalies(conn, "u1", _month_of(today))
    assert found, "a 10x charge went unreported because of an earlier spike"
    assert found[0]["usual"] == pytest.approx(400, abs=1)
    assert found[0]["multiple"] == pytest.approx(10.0, abs=0.2)
    assert "10.0x your usual" in found[0]["explanation"]


def test_anomaly_ignores_small_multiples_of_small_numbers(tmp_path):
    """Rs.60 where Rs.20 is usual is a 3x 'anomaly' and complete noise."""
    conn = _fresh(tmp_path)
    today = _today()
    prev = _shift(today, 1)
    for day in (2, 5, 8, 12):
        _add(conn, 20, prev.replace(day=day).isoformat(), merchant="Tea")
    _add(conn, 60, today.replace(day=1).isoformat(), merchant="Tea")
    assert insights.anomalies(conn, "u1", _month_of(today)) == []


def test_anomaly_explanation_quotes_the_numbers_behind_it(tmp_path):
    """An insight the user cannot check is a horoscope."""
    conn = _fresh(tmp_path)
    today = _today()
    prev = _shift(today, 1)
    for day in (2, 5, 8, 12):
        _add(conn, 1000, prev.replace(day=day).isoformat(), merchant="Swiggy")
    _add(conn, 5000, today.replace(day=1).isoformat(), merchant="Swiggy")
    e = insights.anomalies(conn, "u1", _month_of(today))[0]["explanation"]
    assert "Swiggy" in e and "₹5,000" in e and "₹1,000" in e
    assert "4 earlier charges" in e


# ── savings opportunities ─────────────────────────────────────────────────
def test_no_opportunities_from_an_empty_ledger(tmp_path):
    """A fresh install must not be told how to save money it has not spent."""
    conn = _fresh(tmp_path)
    assert insights.savings_opportunities(conn, "u1",
                                          _month_of(_today())) == []


def test_small_payments_are_totalled(tmp_path):
    conn = _fresh(tmp_path)
    today = _today()
    for i in range(12):
        _add(conn, 150, today.replace(day=1).isoformat(), merchant=f"M{i}")
    opps = {o["kind"]: o for o in insights.savings_opportunities(
        conn, "u1", _month_of(today))}
    assert "small" in opps
    assert opps["small"]["amount"] == pytest.approx(1800, abs=1)
    assert "12 payments under" in opps["small"]["title"]


def test_small_payments_below_the_count_gate_stay_quiet(tmp_path):
    conn = _fresh(tmp_path)
    today = _today()
    for i in range(3):
        _add(conn, 150, today.replace(day=1).isoformat(), merchant=f"M{i}")
    kinds = [o["kind"] for o in insights.savings_opportunities(
        conn, "u1", _month_of(today))]
    assert "small" not in kinds


def test_category_overspend_is_measured_against_the_users_own_average(tmp_path):
    conn = _fresh(tmp_path)
    _cat(conn, "c1", "Food")
    today = _today()
    for back in (3, 2, 1):
        _add(conn, 2000, _shift(today, back).replace(day=4).isoformat(), cat="c1")
    _add(conn, 6000, today.replace(day=1).isoformat(), cat="c1")
    opps = [o for o in insights.savings_opportunities(conn, "u1",
                                                      _month_of(today))
            if o["kind"] == "category"]
    assert opps, "a 3x category month was not surfaced"
    assert "Food" in opps[0]["title"]
    assert "3-month average" in opps[0]["detail"]
    assert opps[0]["amount"] == pytest.approx(4000, abs=1)


def test_every_opportunity_carries_a_number(tmp_path):
    """'Consider reducing discretionary spending' is not actionable. Every
    opportunity must quantify itself or it should not be shown."""
    conn = _fresh(tmp_path)
    today = _today()
    for i in range(15):
        _add(conn, 120, today.replace(day=1).isoformat(), merchant=f"M{i}")
    for o in insights.savings_opportunities(conn, "u1", _month_of(today)):
        assert o["amount"] > 0
        assert any(ch.isdigit() for ch in o["detail"])


# ── assembly ──────────────────────────────────────────────────────────────
def test_build_insights_survives_an_empty_ledger(tmp_path):
    """The report screen must render for a user who installed the app today."""
    conn = _fresh(tmp_path)
    out = insights.build_insights(conn, "u1", _month_of(_today()))
    assert out["forecast"] is None
    assert out["category_trends"] == []
    assert out["merchants"] == []
    assert out["anomalies"] == []
    assert out["savings"] == []
    assert len(out["cash_flow"]) == 6


def test_build_insights_ignores_deleted_rows(tmp_path):
    """A deleted transaction that still moves an insight would make the undo
    bar a lie."""
    conn = _fresh(tmp_path)
    today = _today()
    tid = _add(conn, 90000, today.replace(day=1).isoformat(), merchant="Ghost")
    conn.execute("UPDATE transactions SET is_deleted=1 WHERE id=?", (tid,))
    conn.commit()
    out = insights.build_insights(conn, "u1", _month_of(today))
    assert out["merchants"] == []
    assert all(f["expense"] == 0 for f in out["cash_flow"])


def test_insights_never_leak_across_users(tmp_path):
    conn = _fresh(tmp_path)
    conn.execute("INSERT INTO users VALUES ('u2','b@b.c','V','x','2024-01-01')")
    conn.commit()
    today = _today()
    for i in range(12):
        _add(conn, 300, today.replace(day=1).isoformat(), merchant=f"M{i}",
             uid="u2")
    out = insights.build_insights(conn, "u1", _month_of(today))
    assert out["savings"] == []
    assert out["merchants"] == []
    assert all(f["expense"] == 0 for f in out["cash_flow"])


def test_poisoned_amounts_cannot_take_the_screen_down(tmp_path):
    """The ledger file is user-writable and older builds could store
    non-finite amounts. They must read as zero, not raise."""
    conn = _fresh(tmp_path)
    today = _today()
    conn.execute(
        "INSERT INTO transactions(id,user_id,amount,type,occurred_at,source,"
        "status,created_at,merchant_name) VALUES "
        "('bad','u1',9e999,'expense',?,'manual','confirmed',?,'Poison')",
        (f"{today.replace(day=1).isoformat()}T10:00:00+00:00",
         f"{today.isoformat()}T10:00:00+00:00"))
    conn.commit()
    out = insights.build_insights(conn, "u1", _month_of(today))
    assert all(f["expense"] == 0 for f in out["cash_flow"])


# ── the screen itself ─────────────────────────────────────────────────────
def _seeded_client(tmp_path):
    """A client whose ledger is rich enough to trigger every insight."""
    from spendwise.app import create_app
    app = create_app(db_path=str(tmp_path / "app.db"), single_user=True,
                     secret_key="k")
    c = app.test_client()
    # Seeded against the frozen clock, not the wall clock: the insight
    # functions are pinned to FROZEN by the autouse fixture, so real dates
    # would land in a month those functions never look at.
    today = _today()
    for back in range(5):
        d = _shift(today, back).replace(day=min(5, today.day))
        for amount, merchant, kind in ((50000, "Employer", "income"),
                                       (9000, "Landlord", "expense"),
                                       (1000, "Swiggy", "expense")):
            c.post("/transactions", data={"amount": str(amount),
                                          "merchant": merchant, "type": kind,
                                          "occurred_at": d.isoformat()})
        for i in range(10):
            c.post("/transactions", data={"amount": "150", "merchant": f"S{i}",
                                          "type": "expense",
                                          "occurred_at": d.isoformat()})
    c.post("/transactions", data={"amount": "5200", "merchant": "Swiggy",
                                 "type": "expense",
                                 "occurred_at": today.replace(day=1).isoformat()})
    return c


def test_report_screen_renders_every_insight_section(tmp_path):
    body = _seeded_client(tmp_path).get(
        f"/report?m={_month_of(_today())}").data.decode()
    for section in ("Where this month lands", "Cash flow", "Category trends",
                    "Merchants this month", "Where the money goes",
                    "Unusually large"):
        assert section in body, f"the report is missing the {section!r} section"


def test_report_screen_renders_for_a_brand_new_install(tmp_path):
    """The insight sections are all conditional. If any of them assumed data,
    the first screen a new user opens would be a 500."""
    from spendwise.app import create_app
    app = create_app(db_path=str(tmp_path / "new.db"), single_user=True,
                     secret_key="k")
    r = app.test_client().get("/report")
    assert r.status_code == 200
    body = r.data.decode()
    assert "Where this month lands" not in body
    assert "Category trends" not in body


def test_anomaly_links_to_a_transaction_the_ledger_can_open(tmp_path):
    """A 'View' button that lands on a page which cannot find the row is
    worse than no button."""
    c = _seeded_client(tmp_path)
    body = c.get(f"/report?m={_month_of(_today())}").data.decode()
    import re
    ids = re.findall(r'/transactions\?tx=([A-Za-z0-9_-]+)', body)
    assert ids, "no anomaly deep link was rendered"
    page = c.get(f"/transactions?tx={ids[0]}")
    assert page.status_code == 200
    assert ids[0] in page.data.decode(), "the linked row did not open"


def test_uncategorised_is_never_a_savings_opportunity(tmp_path):
    """'Uncategorised is running high' tells the user to spend less on a
    bucket that does not exist. The action it implies is impossible."""
    conn = _fresh(tmp_path)
    today = _today()
    for back in (3, 2, 1):
        _add(conn, 2000, _shift(today, back).replace(day=4).isoformat())
    _add(conn, 9000, today.replace(day=1).isoformat())
    titles = [o["title"] for o in insights.savings_opportunities(
        conn, "u1", _month_of(today))]
    assert not any("Uncategorised" in t for t in titles)


def test_extreme_merchant_growth_is_reported_as_a_multiple(tmp_path):
    """'Up 1265%' is a number nobody parses. Past a tripling the same fact is
    told as a multiple instead."""
    conn = _fresh(tmp_path)
    today = _today()
    for back in (3, 2, 1):
        _add(conn, 500, _shift(today, back).replace(day=4).isoformat(),
             merchant="Swiggy")
    _add(conn, 7000, today.replace(day=1).isoformat(), merchant="Swiggy")
    mi = insights.merchant_insights(conn, "u1", _month_of(today))[0]
    assert mi["change_pct"] > 200
    assert mi["multiple"] == pytest.approx(14.0, abs=0.5)


def test_modest_merchant_growth_stays_a_percentage(tmp_path):
    conn = _fresh(tmp_path)
    today = _today()
    for back in (3, 2, 1):
        _add(conn, 1000, _shift(today, back).replace(day=4).isoformat(),
             merchant="Cafe")
    _add(conn, 1400, today.replace(day=1).isoformat(), merchant="Cafe")
    mi = insights.merchant_insights(conn, "u1", _month_of(today))[0]
    assert mi["multiple"] is None
    assert mi["change_pct"] == pytest.approx(40, abs=2)
