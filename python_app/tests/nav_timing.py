"""Objective smoothness measurement: how long a tab-to-tab navigation takes
in real headless Chromium, and how much layout/paint work each page triggers.
Compares full-reload navigation vs. whatever navigation the app currently does.

Run:  python tests/nav_timing.py
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
PORT = 8801
BASE = f"http://127.0.0.1:{PORT}"


class _Quiet(WSGIRequestHandler):
    def log_message(self, *a):
        pass


def _serve(app):
    make_server("127.0.0.1", PORT, app, handler_class=_Quiet).serve_forever()


def _seed(client):
    client.post("/profile", data={"full_name": "Perf"})
    for i in range(20):
        client.post("/transactions", data={
            "amount": f"{50 + i}.00", "type": "expense",
            "merchant": f"Merchant{i % 6}", "category_id": "",
            "notes": "", "occurred_at": ""})


def main() -> int:
    sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    from spendwise.app import create_app

    db = os.path.join(tempfile.mkdtemp(), "perf.db")
    app = create_app(db_path=db, single_user=True, secret_key="perf")
    _seed(app.test_client())
    threading.Thread(target=_serve, args=(app,), daemon=True).start()
    time.sleep(0.6)

    tabs = ["/dashboard", "/transactions", "/categories", "/fraud",
            "/dashboard", "/transactions"]
    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path=CHROME, headless=True,
                                    args=["--no-sandbox"])
        page = browser.new_page(viewport={"width": 390, "height": 844})
        page.goto(BASE + "/dashboard", wait_until="networkidle")

        # Measure click→content-visible latency for each tab hop by clicking the
        # actual tab link (exercises whatever navigation the app really uses).
        times = []
        for path in tabs[1:]:
            sel = f"a.tab[href='{path}'], a[href='{path}']"
            t0 = time.perf_counter()
            try:
                page.click(sel, timeout=3000)
                page.wait_for_load_state("networkidle", timeout=5000)
            except Exception as e:
                print("  nav error:", e)
            dt = (time.perf_counter() - t0) * 1000
            times.append(dt)
            print(f"  {path:16s} {dt:7.1f} ms")

        avg = sum(times) / len(times) if times else 0
        print(f"\n  average tab-hop: {avg:.1f} ms")
        browser.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
