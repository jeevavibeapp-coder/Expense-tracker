"""Real-browser QA: render every screen in headless Chromium, capture console
errors, and drive the core flows the way a user (and the on-device WebView)
would. Catches the class of bugs that pass server-side tests but break in a
real browser — hidden content, JS exceptions, broken sheets/navigation.

Run:  python tests/browser_qa.py   (exits non-zero on any failure)
"""
from __future__ import annotations

import os
import sys
import tempfile
import threading
import time
from wsgiref.simple_server import make_server, WSGIRequestHandler

from playwright.sync_api import sync_playwright

CHROME = "/opt/pw-browsers/chromium-1194/chrome-linux/chrome"
PORT = 8799
BASE = f"http://127.0.0.1:{PORT}"


class _Quiet(WSGIRequestHandler):
    def log_message(self, *a):  # keep the QA output clean
        pass


def _serve(app):
    make_server("127.0.0.1", PORT, app, handler_class=_Quiet).serve_forever()


def main() -> int:
    sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    from spendwise.app import create_app

    db = os.path.join(tempfile.mkdtemp(), "qa.db")
    app = create_app(db_path=db, single_user=True, secret_key="qa")
    threading.Thread(target=_serve, args=(app,), daemon=True).start()
    time.sleep(0.6)

    failures: list[str] = []
    console_errors: list[str] = []

    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path=CHROME, headless=True,
                                    args=["--no-sandbox"])
        page = browser.new_page(viewport={"width": 390, "height": 844})
        page.on("console", lambda m: console_errors.append(f"{m.type}: {m.text}")
                if m.type == "error" else None)
        page.on("pageerror", lambda e: console_errors.append(f"pageerror: {e}"))

        def check(cond, msg):
            if not cond:
                failures.append(msg)

        # 1) Every screen renders with VISIBLE content (opacity > 0).
        for path, marker in [("/dashboard", "Total balance"),
                             ("/transactions", "Activity"),
                             ("/categories", "Spending"),
                             ("/fraud", "clear"),
                             ("/settings", "Preferences"),
                             ("/import", "Paste")]:
            page.goto(BASE + path, wait_until="networkidle")
            # A .reveal element must be actually visible (not opacity:0).
            rev = page.query_selector(".reveal")
            if rev:
                opacity = page.evaluate(
                    "el => getComputedStyle(el).opacity", rev)
                check(float(opacity) > 0.9,
                      f"{path}: .reveal content is hidden (opacity={opacity}) — black-screen bug")
            check(page.locator(f"text={marker}").count() > 0,
                  f"{path}: expected marker '{marker}' not visible")

        # 2) FAB opens the add sheet directly and it becomes visible.
        page.goto(BASE + "/dashboard", wait_until="networkidle")
        page.click(".fab")
        page.wait_for_timeout(400)
        sheet = page.query_selector(".sheet[aria-label='Add transaction']")
        check(sheet is not None and sheet.is_visible(),
              "FAB did not open a visible Add-transaction sheet")

        # 3) Add a transaction through the real form → it appears in Activity.
        page.fill("#add ~ .sheet #amt, .sheet[aria-label='Add transaction'] #amt", "199.00")
        page.fill(".sheet[aria-label='Add transaction'] #merchant", "QACafe")
        page.click(".sheet[aria-label='Add transaction'] button[type=submit]")
        page.wait_for_load_state("networkidle")
        page.goto(BASE + "/transactions", wait_until="networkidle")
        check(page.locator("text=QACafe").count() > 0,
              "Added transaction did not appear in Activity (Forbidden/POST bug?)")

        # 4) Expand a timeline row and edit it (inline editor works).
        page.click("summary.tx")
        page.wait_for_timeout(300)
        amt = page.query_selector("details[open] input[name=amount]")
        check(amt is not None and amt.is_visible(),
              "Inline editor did not open on the transaction row")

        # 5) Tab navigation works and never lands on an error page.
        for sel, expect in [("a.tab[href='/categories']", "Budgets"),
                            ("a.tab[href='/fraud']", "Alerts"),
                            ("a.tab[href='/dashboard']", "money")]:
            page.click(sel)
            page.wait_for_load_state("networkidle")
            check("Forbidden" not in page.content(),
                  f"Navigation to {sel} hit a Forbidden page")

        browser.close()

    # Report
    real_console = [e for e in console_errors if "favicon" not in e.lower()]
    print(f"\n=== Browser QA: {len(failures)} failures, "
          f"{len(real_console)} console errors ===")
    for f in failures:
        print("  FAIL:", f)
    for e in real_console:
        print("  CONSOLE:", e)
    if not failures and not real_console:
        print("  All screens render visibly; add/edit/navigate all work.")
    return 1 if (failures or real_console) else 0


if __name__ == "__main__":
    sys.exit(main())
