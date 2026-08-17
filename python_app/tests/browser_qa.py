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
        page.on("response", lambda r: console_errors.append(
            f"HTTP {r.status}: {r.url}") if r.status >= 400 else None)

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

        # 6) INSTANT (client-side) navigation: mark the window, click a tab, and
        #    verify the marker survived (no full document reload) while the
        #    heading + URL changed.
        page.goto(BASE + "/dashboard", wait_until="networkidle")
        page.evaluate("window.__spa_probe = 'alive'")
        page.click("a.tab[href='/transactions']")
        page.wait_for_selector("h1:has-text('Activity')", timeout=4000)
        survived = page.evaluate("window.__spa_probe")
        check(survived == "alive",
              "Tab nav did a FULL page reload (window marker lost) — instant nav not active")
        check("/transactions" in page.url, "URL did not update after instant nav")

        # 7) Back button restores the previous page's content (client-side).
        page.go_back()
        page.wait_for_selector("text=Total balance", timeout=4000)
        check("/dashboard" in page.url, "Back did not return to the dashboard")

        # 8) The #add hash link (CSS :target sheet) is NOT hijacked by the nav
        #    interceptor — the sheet must still open, and window marker survives.
        page.evaluate("window.__spa_probe2 = 'alive'")
        page.click(".fab")
        page.wait_for_timeout(300)
        sheet2 = page.query_selector(".sheet[aria-label='Add transaction']")
        check(sheet2 is not None and sheet2.is_visible(),
              "FAB #add hash link was hijacked by the nav interceptor (sheet didn't open)")
        check(page.evaluate("window.__spa_probe2") == "alive",
              "Opening the #add sheet caused a page reload")

        # 9) Overlays outside <main> must not go stale across instant-nav:
        #    the SMS popup must not linger on top of the bulk-review screen.
        page.goto(BASE + "/dashboard", wait_until="networkidle")
        if page.locator(".cat-modal").count() > 0:
            page.evaluate("document.querySelector('.cat-modal a[href=\"/review\"]')"
                          "?.click()")
            page.wait_for_timeout(900)
            check(page.locator(".cat-modal").count() == 0,
                  "SMS popup stayed mounted after navigating to /review")

        # 9b) Loading states. Navigation used to hold the OLD page until new
        #     HTML arrived — a frozen screen on a slow device. A skeleton must
        #     appear while a navigation is in flight, be shaped like the
        #     destination, and ALWAYS be cleared.
        #
        #     The threshold is forced to 0 rather than simulating a slow
        #     network: racing a real delay made this flaky, and a sleep inside
        #     a sync-API route handler blocks Playwright's own loop.
        page.goto(BASE + "/dashboard", wait_until="networkidle")
        page.evaluate("window.__skDelay = 0;"
                      "window.__sk = {seen: 0, busy: 0};"
                      "new MutationObserver(function(){"
                      "  var m = document.querySelector('main');"
                      "  if (m && m.querySelector('.sk')) window.__sk.seen++;"
                      "  if (m && m.getAttribute('aria-busy') === 'true') window.__sk.busy++;"
                      "}).observe(document.body,{childList:true,subtree:true,attributes:true});")
        page.evaluate("document.querySelector('a[href=\"/transactions\"]').click()")
        page.wait_for_timeout(900)
        sk = page.evaluate("window.__sk")
        check(sk["seen"] > 0, "no skeleton appeared while a navigation was in flight")
        check(sk["busy"] > 0,
              "loading region was never marked aria-busy for screen readers")
        check(page.locator("main .sk").count() == 0,
              "skeleton was still mounted after content arrived")
        check(page.locator("main[aria-busy='true']").count() == 0,
              "aria-busy was not cleared after loading finished")
        check("Activity" in page.content(), "the real page never rendered")

        # 9c) With the normal threshold a fast local navigation must NOT flash
        #     a skeleton — a 40ms flicker reads as a glitch, which is worse
        #     than no feedback at all.
        page.goto(BASE + "/dashboard", wait_until="networkidle")
        page.evaluate("window.__sawSkeleton = false;"
                      "new MutationObserver(function(){"
                      "if(document.querySelector('main .sk')) window.__sawSkeleton = true;"
                      "}).observe(document.querySelector('main'),{childList:true,subtree:true});")
        page.evaluate("document.querySelector('a[href=\"/transactions\"]').click()")
        page.wait_for_timeout(900)
        check(page.evaluate("window.__sawSkeleton") is False,
              "a fast local navigation flashed a skeleton")

        # 9d) Accessibility contract. Measured before this was enforced:
        #     10 unlabelled controls and 51 sub-44px touch targets across five
        #     screens. Both classes of defect existed DESPITE rules being
        #     written for them — `min-height` silently does nothing on
        #     display:inline, and visible <label> elements were never
        #     associated with their inputs, so TalkBack announced a bare
        #     "edit box".
        A11Y = """() => {
          let unlabelled = 0, small = 0, jumps = 0;
          const bad = [];
          document.querySelectorAll(
            'a[href],button,input,select,textarea,[role=\"button\"]').forEach(el => {
            if (el.offsetParent === null) return;
            if (el.type === 'radio' || el.type === 'checkbox') return; // label is the target
            const name = (el.getAttribute('aria-label') || el.getAttribute('title') ||
                          (el.textContent || '').trim() ||
                          (el.labels && el.labels.length ? 'l' : '') ||
                          el.getAttribute('placeholder') || '');
            if (!name) { unlabelled++; bad.push('unlabelled ' + el.tagName); }
            const r = el.getBoundingClientRect();
            const overlay = parseFloat(getComputedStyle(el, '::after').height) || 0;
            if (r.width > 0 && Math.max(r.height, overlay) < 44) {
              small++; bad.push('small ' + el.tagName + '.' +
                                String(el.className).slice(0, 14) + ' ' + Math.round(r.height));
            }
          });
          let prev = 0;
          document.querySelectorAll('h1,h2,h3,h4').forEach(h => {
            const lvl = +h.tagName[1];
            if (prev && lvl > prev + 1) jumps++;
            prev = lvl;
          });
          return {unlabelled, small, jumps, bad: bad.slice(0, 4)};
        }"""
        for a11y_path in ["/dashboard", "/transactions", "/categories",
                          "/review", "/fraud", "/sms/quarantine"]:
            page.goto(BASE + a11y_path, wait_until="networkidle")
            a = page.evaluate(A11Y)
            check(a["unlabelled"] == 0,
                  f"{a11y_path}: {a['unlabelled']} controls a screen reader "
                  f"cannot name {a['bad']}")
            check(a["small"] == 0,
                  f"{a11y_path}: {a['small']} touch targets under 44px {a['bad']}")
            check(a["jumps"] == 0,
                  f"{a11y_path}: heading levels skip a step ({a['jumps']}), which "
                  f"breaks screen-reader heading navigation")

        # 10) No icon may render oversized (a bare viewBox svg once filled the sheet).
        page.goto(BASE + "/dashboard", wait_until="networkidle")
        page.click(".fab")
        page.wait_for_timeout(400)
        oversized = page.evaluate(
            "()=>[...document.querySelectorAll('svg')]"
            ".filter(s=>s.getBoundingClientRect().width>80).length")
        check(oversized == 0, f"{oversized} oversized icon(s) rendered in the add sheet")

        # 11) The export.csv download link must not be intercepted (has download attr).
        page.goto(BASE + "/settings", wait_until="networkidle")
        dl = page.query_selector("a[href='/export.csv']")
        check(dl is not None and dl.get_attribute("download") is not None,
              "export.csv link lost its download attribute (would be intercepted)")

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
