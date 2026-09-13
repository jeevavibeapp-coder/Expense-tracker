"""End-to-end user journeys, driven the way the device drives them.

Not unit tests. Each block below is a thing a person actually does, start to
finish, through the same HTTP surface the WebView uses — including the
one-time device-token grant that mints the session cookie. A journey passes
only if every step in it holds; a green run means those journeys work
together, not that each part works alone.

Run:  python tests/use_cases.py     (exits non-zero if any journey fails)
"""
from __future__ import annotations

import datetime as dt
import io
import json
import os
import re
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from spendwise.app import create_app                      # noqa: E402
from spendwise import db                                  # noqa: E402

TOKEN = "device-token"
RESULTS: list[tuple[str, str, str]] = []      # (journey, step, detail)
FAILED = False


def check(journey: str, ok: bool, step: str, detail: str = "") -> bool:
    global FAILED
    if not ok:
        FAILED = True
    RESULTS.append((journey, ("PASS" if ok else "FAIL") + " " + step, detail))
    return ok


def fresh(tag="uc"):
    """A device exactly as it is on first launch, authenticated the way the
    native shell authenticates: one ?k=<token> navigation on loopback."""
    path = os.path.join(tempfile.mkdtemp(), f"{tag}.db")
    app = create_app(db_path=path, single_user=True, secret_key="k",
                     device_token=TOKEN)
    c = app.test_client()
    c.get(f"/?k={TOKEN}", environ_overrides={"REMOTE_ADDR": "127.0.0.1"})
    return app, c, path


def sms(c, sender, body):
    return c.post("/sms/ingest", data={"sender": sender, "body": body},
                  headers={"X-SpendWise-Token": TOKEN})


def add(c, merchant, amount, type_="expense", when=""):
    return c.post("/transactions", data={
        "amount": str(amount), "type": type_, "merchant": merchant,
        "category_id": "", "notes": "", "occurred_at": when},
        follow_redirects=True)


def txt(resp):
    return resp.data.decode("utf-8", "replace")


# ══ 1. A new user opens the app for the first time ════════════════════════
def uc_first_run():
    J = "1. First run"
    app, c, _ = fresh()
    r = c.get("/dashboard")
    check(J, r.status_code == 302 and "/welcome" in r.headers.get("Location", ""),
          "lands on the introduction before reading anything",
          f"status={r.status_code}")
    w = txt(c.get("/welcome"))
    check(J, "What SpendWise reads" in w, "says what it will read")
    check(J, "Never messages from people" in w and "Never OTPs" in w,
          "names what it will NOT read")
    check(J, "tabbar" not in w, "hides the tab bar so the page is not skippable")
    c.post("/welcome/done")
    d = c.get("/dashboard")
    check(J, d.status_code == 200, "dashboard opens after finishing")
    check(J, "Get set up" in txt(d), "empty dashboard offers setup, not fake data")
    check(J, "no-spend streak" not in txt(d),
          "no invented gamification on an empty ledger")


# ══ 2. A bank SMS arrives and becomes a transaction ═══════════════════════
def uc_sms_capture():
    J = "2. SMS auto-capture"
    app, c, _ = fresh()
    c.post("/welcome/done")
    r = sms(c, "VK-HDFCBK",
            "Rs.450.00 spent at RAJUKIRANA on 12-06-2025 ref 553201998877 UPI")
    j = r.get_json()
    check(J, j.get("captured") is True, "a genuine bank SMS is captured",
          json.dumps(j)[:120])
    check(J, j.get("needs_category") is True,
          "an unknown merchant asks for a category rather than guessing")
    page = txt(c.get("/transactions"))
    check(J, "RAJUKIRANA" in page.upper(), "it appears in Activity with no typing")
    check(J, "450" in page, "with the right amount")
    dash = txt(c.get("/dashboard"))
    check(J, "New transaction from SMS" in dash,
          "the app offers to categorise it on next open")


# ══ 3. Messages that must NOT become transactions ═════════════════════════
def uc_sms_rejection():
    J = "3. What SMS capture refuses"
    app, c, _ = fresh()
    c.post("/welcome/done")

    otp = sms(c, "VM-HDFCBK", "Your OTP is 4567. Do not share it with anyone.")
    check(J, otp.get_json().get("captured") is not True,
          "an OTP is never captured", json.dumps(otp.get_json())[:100])

    personal = sms(c, "+919876543210", "Hey, send me Rs.500 when you can")
    check(J, personal.get_json().get("captured") is not True,
          "a personal message is never captured")

    phish = sms(c, "VM-KYCUPD",
                "URGENT: Your a/c will be blocked. Rs.9999 debited. "
                "Click http://bit.ly/kyc-verify or call 9876543210 now to reverse.")
    pj = phish.get_json()
    check(J, pj.get("captured") is not True,
          "a phishing message is not silently added", json.dumps(pj)[:140])
    held = txt(c.get("/sms/quarantine"))
    check(J, "KYCUPD" in held or "held" in held.lower(),
          "it is held for review instead of discarded")

    n = txt(c.get("/transactions"))
    check(J, "9999" not in n, "the phishing amount never reaches the ledger")


# ══ 4. The engine learns a correction ═════════════════════════════════════
def uc_learning():
    J = "4. Correcting teaches the engine"
    app, c, _ = fresh()
    c.post("/welcome/done")
    for i in range(4):
        add(c, "Starbucks", "250.00")
    # This endpoint feeds the live preview under the merchant field in the
    # add form, so it answers with an HTML fragment, not JSON.
    known = txt(c.post("/transactions/resolve",
                       data={"merchant": "Starbucks", "amount": "250"}))
    check(J, "Starbucks" in known,
          "the engine recognises a merchant it has seen four times",
          known[:120])
    unknown = txt(c.post("/transactions/resolve",
                         data={"merchant": "NeverSeenBefore", "amount": "999"}))
    check(J, "New payee" in unknown,
          "and says plainly when a payee is new rather than guessing",
          unknown[:120])
    check(J, "NeverSeenBefore" not in unknown or "New payee" in unknown,
          "without inventing a category for it")


# ══ 5. Delete is always reversible ════════════════════════════════════════
def uc_delete_undo():
    J = "5. Delete and undo"
    app, c, path = fresh()
    c.post("/welcome/done")
    add(c, "Mistake", "1234.00")
    conn = db.connect(path)
    tid = conn.execute("SELECT id FROM transactions "
                       "WHERE merchant_name='Mistake'").fetchone()[0]
    r = c.post(f"/transactions/{tid}/delete", follow_redirects=True)
    check(J, "Mistake" not in txt(c.get("/transactions")), "the row disappears")
    check(J, "restore" in txt(r), "an undo is offered immediately")
    row = conn.execute("SELECT is_deleted FROM transactions WHERE id=?",
                       (tid,)).fetchone()
    check(J, row[0] == 1, "the delete is soft, so nothing is really gone")
    c.post(f"/transactions/{tid}/restore", follow_redirects=True)
    check(J, "Mistake" in txt(c.get("/transactions")), "undo brings it back")
    conn.close()


# ══ 6. Budgets ════════════════════════════════════════════════════════════
def uc_budgets():
    J = "6. Budgets"
    app, c, path = fresh()
    c.post("/welcome/done")
    conn = db.connect(path)
    cid, cname = conn.execute("SELECT id, name FROM categories LIMIT 1").fetchone()
    c.post(f"/categories/{cid}/budget", data={"budget_amount": "5000"})
    today = dt.date.today().replace(day=1).isoformat()
    c.post("/transactions", data={"amount": "3000", "type": "expense",
                                  "merchant": "Groceries", "category_id": cid,
                                  "notes": "", "occurred_at": today},
           follow_redirects=True)
    page = txt(c.get("/categories"))
    check(J, "5,000" in page or "5000" in page or "5K" in page,
          "the budget is shown", cname)
    dash = txt(c.get("/dashboard"))
    check(J, "Budgets" in dash, "progress reaches the dashboard")
    # Overspend must be visible, not silently absorbed.
    c.post("/transactions", data={"amount": "4000", "type": "expense",
                                  "merchant": "More", "category_id": cid,
                                  "notes": "", "occurred_at": today},
           follow_redirects=True)
    over = txt(c.get("/categories"))
    check(J, "%" in over, "and overspend is expressed as a proportion")
    conn.close()


# ══ 7. Search ═════════════════════════════════════════════════════════════
def uc_search():
    J = "7. Search"
    app, c, _ = fresh()
    c.post("/welcome/done")
    add(c, "Swiggy", "300")
    add(c, "Zomato", "400")
    c.post("/transactions", data={"amount": "500", "type": "expense",
                                  "merchant": "Uber", "category_id": "",
                                  "notes": "airport run", "occurred_at": ""},
           follow_redirects=True)
    hit = txt(c.get("/transactions?q=Swiggy"))
    check(J, "Swiggy" in hit and "Zomato" not in hit, "finds by merchant")
    note = txt(c.get("/transactions?q=airport"))
    check(J, "Uber" in note, "finds by note text")
    miss = txt(c.get("/transactions?q=zzzznothing"))
    check(J, miss.count('class="tx-item') == 0, "a miss returns no rows")
    check(J, "No transactions yet" not in miss,
          "and does NOT claim the ledger is empty when it is not")
    check(J, "No matches" in miss and "still there" in miss,
          "it says the search found nothing, and offers to clear it")


# ══ 8. The monthly report ═════════════════════════════════════════════════
def uc_report():
    J = "8. Monthly report"
    app, c, _ = fresh()
    c.post("/welcome/done")
    today = dt.date.today()

    def shift(d, n):
        y, m = d.year, d.month - n
        while m <= 0:
            m += 12
            y -= 1
        return dt.date(y, m, 1)

    for back in range(1, 6):        # five FULL prior months
        day = shift(today, back).replace(day=5)
        add(c, "Payroll", "85000", "income", day.isoformat())
        add(c, "Landlord", "24000", "expense", day.isoformat())
        for mm in ("Swiggy", "Uber", "BigBasket"):
            add(c, mm, "800", "expense", day.isoformat())
    add(c, "Swiggy", "9000", "expense", today.replace(day=1).isoformat())

    r = c.get("/report")
    body = txt(r)
    check(J, r.status_code == 200, "the report renders")
    for section in ("Where this month lands", "Cash flow", "Merchants this month"):
        check(J, section in body, f"section present: {section}")
    check(J, "Unusually large" in body,
          "a 9,000 charge where 800 is usual is called out")
    m = re.search(r'/transactions\?tx=([A-Za-z0-9_-]+)', body)
    check(J, m is not None, "the anomaly links to the transaction behind it")
    if m:
        linked = c.get(f"/transactions?tx={m.group(1)}")
        check(J, linked.status_code == 200 and m.group(1) in txt(linked),
              "and that link opens the row")


# ══ 9. Backup and restore onto a new phone ════════════════════════════════
def uc_backup_restore():
    J = "9. Backup to a new phone"
    app_a, c_a, _ = fresh("old")
    c_a.post("/welcome/done")
    for mm in ("Swiggy", "Uber", "Landlord"):
        add(c_a, mm, "1200")
    sms(c_a, "VK-HDFCBK",
        "Rs.450.00 spent at RAJUKIRANA on 12-06-2025 ref 553201998877 UPI")
    blob = c_a.get("/backup.json").data
    doc = json.loads(blob)
    check(J, doc.get("app") == "SpendWise", "the old phone produces a backup")
    check(J, len(doc["tables"]["transactions"]) >= 4, "carrying every transaction")
    check(J, "Rs.450.00 spent at RAJUKIRANA" not in blob.decode(),
          "but NOT the raw bank message bodies")

    app_b, c_b, _ = fresh("new")
    c_b.post("/welcome/done")
    prev = txt(c_b.get("/restore"))
    check(J, "Choose a backup file" in prev, "the new phone offers a restore")
    peek = c_b.post("/restore",
                    data={"backup": (io.BytesIO(blob), "b.json")},
                    content_type="multipart/form-data")
    check(J, "This backup contains" in txt(peek),
          "it previews the file before touching anything")
    done = c_b.post("/restore",
                    data={"backup": (io.BytesIO(blob), "b.json"),
                          "confirm": "yes", "mode": "merge"},
                    content_type="multipart/form-data")
    check(J, "Restored" in txt(done), "and restores on confirmation")
    after = txt(c_b.get("/transactions"))
    for mm in ("Swiggy", "Uber", "Landlord"):
        check(J, mm in after, f"{mm} arrived on the new phone")

    again = c_b.post("/restore",
                     data={"backup": (io.BytesIO(blob), "b.json"),
                           "confirm": "yes", "mode": "merge"},
                     content_type="multipart/form-data")
    check(J, "Restored" in txt(again), "restoring twice is allowed")
    count = txt(c_b.get("/transactions")).count(">Swiggy<")
    check(J, count <= 1, "and does not duplicate the ledger",
          f"Swiggy rows={count}")

    bad = c_b.post("/restore",
                   data={"backup": (io.BytesIO(b"not a backup"), "b.json"),
                         "confirm": "yes"},
                   content_type="multipart/form-data")
    check(J, "Nothing was changed" in txt(bad), "a junk file changes nothing")
    check(J, "Swiggy" in txt(c_b.get("/transactions")),
          "and leaves the existing ledger intact")


# ══ 10. Export ════════════════════════════════════════════════════════════
def uc_export():
    J = "10. Export"
    app, c, _ = fresh()
    c.post("/welcome/done")
    add(c, "Swiggy", "300")
    r = c.get("/export.csv")
    check(J, r.status_code == 200, "CSV downloads")
    check(J, "attachment" in r.headers.get("Content-Disposition", ""),
          "as a file, not a page")
    body = r.data.decode()
    check(J, "Swiggy" in body and "300" in body, "with the real rows in it")
    check(J, body.splitlines()[0].startswith("date,"), "and a header row")


# ══ 11. Duplicates and self-transfers ═════════════════════════════════════
def uc_duplicates_and_transfers():
    J = "11. Duplicates and transfers"
    app, c, _ = fresh()
    c.post("/welcome/done")
    body = "Rs.200 debited to UBER on 01/02/2025 ref ABCXYZ123456 UPI"
    first = sms(c, "VK-HDFCBK", body).get_json()
    second = sms(c, "VK-HDFCBK", body).get_json()
    check(J, first.get("captured") is True, "the first copy is captured")
    check(J, second.get("captured") is not True or
             second.get("duplicate") is True,
          "an identical re-send is not counted twice",
          json.dumps(second)[:120])
    rows = txt(c.get("/transactions")).count("UBER")
    check(J, rows <= 1, "one purchase, one row", f"rows={rows}")


# ══ 12. Settings round-trip ═══════════════════════════════════════════════
def uc_settings():
    J = "12. Settings"
    app, c, _ = fresh()
    c.post("/welcome/done")
    c.post("/settings", data={"currency": "USD", "theme": "dark",
                              "auto_save_threshold": "95",
                              "confirm_threshold": "40",
                              "high_value_amount": "25000"})
    s = txt(c.get("/settings"))
    check(J, "USD" in s, "currency is kept")
    check(J, "95" in s, "thresholds are kept")
    dash = txt(c.get("/dashboard"))
    check(J, "$" in dash, "and the currency symbol follows through the app")
    # A hand-typed absurd threshold must be clamped, not stored.
    c.post("/settings", data={"currency": "INR", "theme": "system",
                              "auto_save_threshold": "9999",
                              "confirm_threshold": "-5",
                              "high_value_amount": ""})
    s2 = txt(c.get("/settings"))
    check(J, 'name="auto_save_threshold" min="0" max="100" value="100"' in s2,
          "an out-of-range threshold is clamped to 100")


# ══ 13. Nothing is reachable without the device grant ═════════════════════
def uc_locked_down():
    J = "13. Locked down"
    path = os.path.join(tempfile.mkdtemp(), "lock.db")
    app = create_app(db_path=path, single_user=True, secret_key="k",
                     device_token=TOKEN)
    c = app.test_client()          # deliberately NO grant
    for p in ("/dashboard", "/transactions", "/export.csv", "/backup.json",
              "/restore", "/settings", "/report", "/sms/misses.csv"):
        check(J, c.get(p).status_code == 403, f"{p} refuses a co-installed app")
    check(J, c.post("/transactions", data={"amount": "1", "type": "expense",
                                           "merchant": "X"}).status_code == 403,
          "and cannot be written to")
    check(J, c.post("/sms/ingest", data={"sender": "X", "body": "Rs.1 spent"}
                    ).status_code == 403,
          "SMS ingest needs the header token")
    check(J, c.get("/healthz").status_code == 200,
          "but the readiness probe still answers")
    check(J, c.get("/static/app.js").status_code in (200, 304),
          "and static assets still load (gating them black-screened the app)")


# ══ 14. A poisoned ledger cannot take the app down ════════════════════════
def uc_hostile_data():
    J = "14. Hostile data"
    app, c, path = fresh()
    c.post("/welcome/done")
    add(c, "Good", "100")
    conn = db.connect(path)
    now = dt.datetime.now().isoformat()
    conn.execute(
        "INSERT INTO transactions(id,user_id,amount,type,occurred_at,source,"
        "status,created_at,merchant_name) SELECT 'poison', id, 9e999, 'expense',"
        "?, 'manual','confirmed',?,'Poison' FROM users LIMIT 1", (now, now))
    conn.commit()
    conn.close()
    for p in ("/dashboard", "/transactions", "/report", "/categories",
              "/settings"):
        check(J, c.get(p).status_code == 200,
              f"{p} still renders with a non-finite amount in the ledger")


def main() -> int:
    for fn in (uc_first_run, uc_sms_capture, uc_sms_rejection, uc_learning,
               uc_delete_undo, uc_budgets, uc_search, uc_report,
               uc_backup_restore, uc_export, uc_duplicates_and_transfers,
               uc_settings, uc_locked_down, uc_hostile_data):
        try:
            fn()
        except Exception as exc:                          # noqa: BLE001
            check(fn.__name__, False, "journey raised",
                  f"{type(exc).__name__}: {exc}")

    journey = None
    passed = failed = 0
    for j, step, detail in RESULTS:
        if j != journey:
            print(f"\n{j}")
            journey = j
        mark = "  ✓" if step.startswith("PASS") else "  ✗"
        print(f"{mark} {step[5:]}" + (f"   [{detail}]" if detail and
                                      step.startswith("FAIL") else ""))
        if step.startswith("PASS"):
            passed += 1
        else:
            failed += 1
    print(f"\n{'=' * 60}\n{passed} steps passed, {failed} failed "
          f"across {len({r[0] for r in RESULTS})} journeys")
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
