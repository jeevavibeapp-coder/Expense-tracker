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

        # 0) First run: the app must explain what it will read from the phone
        #    before it reads anything. Everything after this point is the app
        #    as a returning user sees it, so onboarding is completed here.
        page.goto(BASE + "/dashboard", wait_until="networkidle")
        check("/welcome" in page.url,
              f"a brand-new install did not land on the introduction (at {page.url})")
        check(page.locator("text=What SpendWise reads").count() > 0,
              "the introduction does not say what the app reads")
        check(page.locator("nav.tabbar").count() == 0,
              "the introduction shows the tab bar, which invites skipping it")
        page.click("button[type=submit]")
        page.wait_for_load_state("networkidle")
        check("/dashboard" in page.url,
              "finishing the introduction did not land on the dashboard")
        page.goto(BASE + "/dashboard", wait_until="networkidle")
        check("/welcome" not in page.url,
              "the introduction was shown again after being completed")

        # 1) Every screen renders with VISIBLE content (opacity > 0).
        for path, marker in [("/dashboard", "Total balance"),
                             ("/transactions", "Activity"),
                             ("/categories", "Spending"),
                             ("/fraud", "clear"),
                             ("/settings", "Preferences"),
                             ("/import", "Paste"),
                             ("/privacy", "stays on this phone"),
                             ("/help", "Autostart"),
                             ("/restore", "Choose a backup file")]:
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
            // WCAG 2.5.5 exempts a link sitting INSIDE a sentence: forcing a
            // 44px box on it would break the line it lives in, and the rest of
            // the sentence is not a competing target. Detected as an anchor
            // whose parent also holds text of its own.
            if (el.tagName === 'A' && el.parentElement) {
              const own = [...el.parentElement.childNodes]
                .filter(n => n.nodeType === 3)
                .map(n => n.textContent.trim()).join('');
              if (own.length > 20) return;
            }
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
                          "/review", "/fraud", "/sms/quarantine", "/report",
                          "/settings", "/privacy", "/help", "/restore",
                          "/welcome"]:
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

        # 9e) THE APP MUST SCROLL. Reported by a user and reproduced:
        #     `body:has(.cat-modal) { overflow: hidden }` locked scrolling
        #     whenever the categoriser element merely EXISTED in the DOM.
        #     That was harmless while it was rendered only when visible, but
        #     became a total scroll lock once it changed to
        #     always-present-hidden-until-:target — anyone with a pending SMS
        #     capture could not scroll ANY screen.
        #
        #     Uses a real wheel gesture, not window.scrollTo: programmatic
        #     scrolling still worked while touch was dead, so the obvious
        #     check would have passed straight through the bug.
        for scroll_path in ["/dashboard", "/transactions", "/categories",
                            "/review", "/settings"]:
            page.goto(BASE + scroll_path, wait_until="networkidle")
            page.evaluate("window.scrollTo(0, 0)")
            height = page.evaluate("() => document.documentElement.scrollHeight "
                                   "- document.documentElement.clientHeight")
            if height < 200:
                continue                      # nothing to scroll on this screen
            page.mouse.move(195, 500)
            page.mouse.wheel(0, 900)
            page.wait_for_timeout(350)
            moved = page.evaluate("() => window.scrollY")
            overflow = page.evaluate(
                "() => getComputedStyle(document.body).overflowY")
            check(moved > 50,
                  f"{scroll_path} did not scroll on a real gesture "
                  f"(scrollY={moved}, body overflow-y={overflow})")

        # ...and the lock must still engage while a sheet is genuinely open,
        # or the page scrolls away behind the sheet.
        page.goto(BASE + "/dashboard", wait_until="networkidle")
        if page.locator(".cat-modal").count() > 0:
            page.evaluate("location.hash = 'categorize'")
            page.wait_for_timeout(350)
            check(page.evaluate(
                      "() => getComputedStyle(document.body).overflowY") == "hidden",
                  "background still scrolls while the categoriser sheet is open")
            page.evaluate("location.hash = ''")
            page.wait_for_timeout(250)

        # 9f) Swipe-to-delete. Two properties must hold AT THE SAME TIME, and
        #     the second is the one a gesture handler usually breaks:
        #       a) a decisive left swipe deletes the row (and undo is offered),
        #       b) a small nudge does NOT, and vertical scrolling still works.
        #     Driven through CDP touch events because Playwright's touchscreen
        #     API can only tap, and a mouse drag would not exercise the
        #     touch-action: pan-y contract this relies on.
        def touch_swipe(px, py, dx, steps=14):
            cdp.send("Input.dispatchTouchEvent",
                     {"type": "touchStart",
                      "touchPoints": [{"x": px, "y": py}]})
            for i in range(1, steps + 1):
                cdp.send("Input.dispatchTouchEvent",
                         {"type": "touchMove",
                          "touchPoints": [{"x": px + dx * i / steps, "y": py}]})
            cdp.send("Input.dispatchTouchEvent",
                     {"type": "touchEnd", "touchPoints": []})

        cdp = page.context.new_cdp_session(page)
        page.goto(BASE + "/transactions", wait_until="networkidle")
        page.evaluate("""async () => {
          for (const n of ['SwipeA','SwipeB','SwipeC','SwipeD']) {
            await fetch('/transactions', {method: 'POST',
              headers: {'Content-Type': 'application/x-www-form-urlencoded'},
              body: new URLSearchParams({amount: '42', merchant: n,
                                         type: 'expense'})});
          }
        }""")
        page.goto(BASE + "/transactions", wait_until="networkidle")
        before = page.locator("details.tx-item").count()
        check(before >= 4, f"swipe test needs rows to swipe, found {before}")

        if before >= 4:
            # a) A nudge below the commit threshold must leave the row alone.
            box = page.locator("details.tx-item").first.bounding_box()
            touch_swipe(box["x"] + box["width"] - 30,
                        box["y"] + box["height"] / 2, -40)
            page.wait_for_timeout(600)
            check(page.locator("details.tx-item").count() == before,
                  "a 40px nudge deleted a transaction — the commit threshold "
                  "is too low to be safe")

            # b) A decisive swipe deletes, and undo must be offered.
            box = page.locator("details.tx-item").first.bounding_box()
            touch_swipe(box["x"] + box["width"] - 30,
                        box["y"] + box["height"] / 2, -260)
            page.wait_for_timeout(1200)
            page.wait_for_load_state("networkidle")
            check(page.locator("details.tx-item").count() == before - 1,
                  f"a full swipe did not delete the row "
                  f"({page.locator('details.tx-item').count()} rows, "
                  f"expected {before - 1})")
            check(page.locator("form[action$='/restore']").count() > 0,
                  "swipe-delete offered no undo — a mis-swipe would be "
                  "unrecoverable")

            # c) The gesture must not have stolen the vertical axis.
            page.goto(BASE + "/transactions", wait_until="networkidle")
            page.evaluate("window.scrollTo(0, 0)")
            if page.evaluate("() => document.documentElement.scrollHeight "
                             "- document.documentElement.clientHeight") > 200:
                page.mouse.move(195, 500)
                page.mouse.wheel(0, 900)
                page.wait_for_timeout(350)
                check(page.evaluate("() => window.scrollY") > 50,
                      "the swipe handler broke vertical scrolling on Activity")

        # 9g) Pull-to-refresh. The dangerous failure is not "it didn't
        #     refresh" — it is "it ate the scroll", because the handler is the
        #     only non-passive touch listener in the app and it calls
        #     preventDefault(). So both directions are asserted.
        page.goto(BASE + "/transactions", wait_until="networkidle")
        page.evaluate("window.scrollTo(0, 0)")
        # a) A short pull must not refresh: no spinner is left behind.
        def pull(px, py, dy, steps=14):
            cdp.send("Input.dispatchTouchEvent",
                     {"type": "touchStart", "touchPoints": [{"x": px, "y": py}]})
            for i in range(1, steps + 1):
                cdp.send("Input.dispatchTouchEvent",
                         {"type": "touchMove",
                          "touchPoints": [{"x": px, "y": py + dy * i / steps}]})
            cdp.send("Input.dispatchTouchEvent",
                     {"type": "touchEnd", "touchPoints": []})

        pull(195, 140, 30)
        page.wait_for_timeout(400)
        check(page.locator(".ptr.spin").count() == 0,
              "a 30px pull triggered a refresh — the threshold is too low")

        # b) A decisive pull must actually refetch the page, and must ALWAYS
        #    clear its spinner. Counting fetches is what makes this a real
        #    assertion: "the spinner is gone and the page still says Activity"
        #    would also pass if the gesture did nothing at all.
        page.evaluate("""() => {
          window.__fetches = 0;
          const orig = window.fetch;
          window.fetch = function (u, o) {
            if (String(u).indexOf('/transactions') === 0) window.__fetches++;
            return orig.apply(this, arguments);
          };
        }""")
        pull(195, 140, 220)
        page.wait_for_timeout(1600)
        check(page.evaluate("() => window.__fetches") > 0,
              "a 220px pull did not refetch the page — pull-to-refresh is dead")
        check(page.locator(".ptr.spin").count() == 0,
              "the pull-to-refresh spinner was left spinning after the reload")
        check("Activity" in page.content(),
              "the page did not survive a pull-to-refresh")

        # c) ...and scrolling must still work after all that.
        page.evaluate("window.scrollTo(0, 0)")
        if page.evaluate("() => document.documentElement.scrollHeight "
                         "- document.documentElement.clientHeight") > 200:
            page.mouse.move(195, 500)
            page.mouse.wheel(0, 900)
            page.wait_for_timeout(350)
            check(page.evaluate("() => window.scrollY") > 50,
                  "pull-to-refresh broke vertical scrolling on Activity")

        # 9h) A sheet must be dismissable by dragging it down, and must NOT be
        #     dismissed by a small drag (that would eat form interaction).
        page.goto(BASE + "/dashboard", wait_until="networkidle")
        page.click(".fab")
        page.wait_for_timeout(400)
        box = page.locator(".sheet[aria-label='Add transaction']").bounding_box()
        check(box is not None, "add sheet did not open for the drag test")
        if box:
            top = box["y"] + 20
            pull(195, top, 25)                      # small drag: must stay open
            page.wait_for_timeout(450)
            check(page.locator(".sheet[aria-label='Add transaction']").is_visible(),
                  "a 25px drag closed the sheet — a scroll would dismiss it")
            pull(195, top, 320)                     # decisive drag: must close
            page.wait_for_timeout(600)
            closed = (page.evaluate(
                "() => !document.querySelector('.sheet-target:target')"))
            check(closed, "dragging the sheet down did not dismiss it")

        # 9i) The report screen is where all the locally-computed intelligence
        #     lands. Every section there is conditional on having evidence, so
        #     the failure mode is a 500 on one of the branches — which only
        #     shows up when the page is actually rendered in a browser.
        page.goto(BASE + "/report", wait_until="networkidle")
        check("Monthly report" in page.content(), "the report screen did not render")
        check(page.locator("text=Forbidden").count() == 0,
              "the report screen hit an auth error")
        # Whatever sections did render must not overflow the viewport: the
        # cash-flow chart and the trend sparks are the widest things in the app.
        overflow = page.evaluate(
            "() => document.documentElement.scrollWidth > "
            "document.documentElement.clientWidth + 1")
        check(not overflow,
              "the report screen scrolls horizontally — a chart is too wide")

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
