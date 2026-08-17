"""SpendWise Flask application — full server-rendered app (offline-capable).

Wires the merchant engine, learning, SMS parsing, fraud detection and analytics
into a complete UI. Runs as a normal web server and, via Chaquopy, embedded
inside the Android APK (single-user mode).
"""
from __future__ import annotations

import datetime as dt
import hashlib
import hmac
import json
import os
from typing import Optional

import csv
import io
import re
import sqlite3

from flask import (
    Flask, Response, abort, g, redirect, render_template, request, session, url_for,
)

from . import (analytics, auth, calibration, categorizer, db, engine,
               fraud, insights, search, senders)
from .parsing import MAX_AMOUNT, PARSER_VERSION, parse_sms, safe_amount


def parsing_version() -> str:
    return PARSER_VERSION

TX_CONFIRMED, TX_PENDING, TX_REVIEW = "confirmed", "pending_confirmation", "needs_review"


def _safe_mode_app(app: Flask, status: dict) -> Flask:
    """Minimal server used when the database could not be upgraded.

    Deliberately tiny: it touches no application table, so it cannot fail for
    the same reason the migration did. Its whole job is to keep the process
    alive and answerable so the user gets an explanation instead of a launch
    loop, and to tell them exactly where their data is.
    """
    backup = status.get("backup") or "the app's backup folder"
    detail = status.get("migration_error") or "unknown error"

    @app.get("/healthz")
    def healthz():                       # noqa: D401 - native readiness probe
        return {"status": "safe_mode", "ok": False,
                "error": detail, "backup": backup}, 200

    @app.get("/", defaults={"_path": ""})
    @app.get("/<path:_path>")
    def safe_mode_page(_path):
        return Response(
            "<!doctype html><meta name=viewport content='width=device-width,"
            "initial-scale=1'><style>body{margin:0;min-height:100vh;display:flex;"
            "align-items:center;justify-content:center;font-family:-apple-system,"
            "Roboto,sans-serif;background:#12121a;color:#fff;text-align:center}"
            "div{padding:28px;max-width:32rem}h1{font-size:20px;margin:0 0 10px}"
            "p{opacity:.85;font-size:14px;line-height:1.55;margin:0 0 12px}"
            "code{font-size:12px;opacity:.7;word-break:break-all}</style><div>"
            "<h1>SpendWise couldn't finish updating</h1>"
            "<p><b>Your transactions are safe.</b> The update was undone and "
            "your data has been restored to how it was before.</p>"
            "<p>Reinstalling or clearing app data would delete it, so please "
            "don't. A backup copy is kept at:</p>"
            f"<code>{backup}</code></div>",
            mimetype="text/html", status=503)

    app.config["SAFE_MODE"] = True
    return app


def create_app(db_path: Optional[str] = None, single_user: bool = False,
               secret_key: Optional[str] = None, device_token: Optional[str] = None) -> Flask:
    app = Flask(__name__)
    app.config["DB_PATH"] = db_path or os.environ.get("SPENDWISE_DB", "spendwise.db")
    app.config["SINGLE_USER"] = single_user or os.environ.get("SPENDWISE_SINGLE_USER") == "1"
    # When set (on-device), native→server calls must carry this token, so a
    # co-installed app can't POST to the loopback ingest endpoints.
    app.config["DEVICE_TOKEN"] = device_token or os.environ.get("SPENDWISE_DEVICE_TOKEN") or None

    # Open with integrity verification, pre-migration backup and automatic
    # recovery. db_status records anything unusual so the UI can be honest
    # about it rather than failing silently.
    init_conn, db_status = db.open_database(app.config["DB_PATH"])
    app.config["DB_STATUS"] = db_status

    # A migration that fails twice in a row leaves the ledger rolled back to
    # the schema the previous build wrote. The normal routes query columns
    # that schema does not have, so serving them would crash on every request.
    # Serve a minimal, honest app instead — critically it answers /healthz, so
    # the native layer sees a live server and shows this page rather than
    # "Couldn't start SpendWise" with a Retry button that re-runs the same
    # failing migration forever.
    if db_status.get("safe_mode"):
        init_conn.close()
        return _safe_mode_app(app, db_status)

    # Session-signing key. On-device this is supplied by the Android Keystore
    # (SecretVault -> android_entry.start_server -> here), so it never touches
    # the database. Off-device (desktop/dev/tests) there is no keystore, so we
    # fall back to a generated key persisted in app_state — same as before.
    secret = secret_key or os.environ.get("SPENDWISE_SECRET")
    if secret:
        app.config["SECRET_SOURCE"] = "external"
        # Upgrade path: builds before the Keystore work wrote this key as
        # plaintext into the ledger file. Now that an external key is
        # authoritative the stored copy is pure liability, so erase it. Doing
        # this on every boot (not just once) means a downgrade-then-upgrade
        # cycle cannot leave a stale plaintext key behind.
        purged = db.execute(
            init_conn, "DELETE FROM app_state WHERE key='secret_key'")
        init_conn.commit()
        app.config["SECRET_PURGED_LEGACY"] = bool(getattr(purged, "rowcount", 0))
    else:
        row = db.one(init_conn, "SELECT value FROM app_state WHERE key='secret_key'")
        if row and row["value"]:
            secret = row["value"]
            app.config["SECRET_SOURCE"] = "database"
        else:
            secret = os.urandom(32).hex()
            db.execute(init_conn, "INSERT OR REPLACE INTO app_state(key, value) "
                       "VALUES ('secret_key', ?)", (secret,))
            init_conn.commit()
            app.config["SECRET_SOURCE"] = "database-new"
        app.config["SECRET_PURGED_LEGACY"] = False
    app.secret_key = secret
    init_conn.close()

    # Session hardening. Secure=False is deliberate (loopback HTTP inside the
    # WebView); SameSite=Lax so cookies ride top-level WebView navigations
    # reliably. Sensitive ingest endpoints are still gated by the device token
    # (device_authorized); GET pages are loopback-only and single-user, so a
    # per-navigation gate is not worth the WebView-cookie fragility it caused.
    app.config.update(
        SESSION_COOKIE_HTTPONLY=True,
        SESSION_COOKIE_SAMESITE="Lax",
        SEND_FILE_MAX_AGE_DEFAULT=31536000,  # static/ is immutable per APK build
    )
    app.jinja_env.globals["asset_v"] = os.environ.get("SPENDWISE_ASSET_V", "2")

    @app.after_request
    def _security_headers(resp):
        resp.headers.setdefault("X-Content-Type-Options", "nosniff")
        resp.headers.setdefault("Referrer-Policy", "no-referrer")
        return resp

    # ── Authentication model ─────────────────────────────────────────────
    # Every endpoint is in exactly one class. There is no implicit
    # authentication: an endpoint not listed as PUBLIC or DEVICE requires a
    # granted session, and the gate below fails closed for anything it does
    # not recognise.
    #
    # Why this exists: the embedded server listens on 127.0.0.1, and on
    # Android *any* app holding the normal-level INTERNET permission can open
    # a socket to it. Previously `before_request` minted a session for every
    # caller, so 36 of 38 endpoints were reachable with no cookie and no
    # token — a co-installed app could GET /export.csv (the entire ledger),
    # read raw bank SMS from /sms/misses.csv, POST forged transactions, purge
    # data, and approve quarantined phishing messages.
    #
    # Two earlier attempts at a gate were reverted for real reasons, and this
    # design avoids both: an all-route loopback/device gate also blocked
    # /static, which black-screened the app; and an Origin check rejected the
    # `Origin: null` that Android WebViews send on form POSTs, which broke
    # add/edit. So: static assets are explicitly PUBLIC, and nothing depends
    # on the Origin header.
    PUBLIC_ENDPOINTS = frozenset({
        "static",        # app.js / app.css — no user data, and gating it
                         # black-screened the app the last time it was tried
        "healthz",       # native readiness poll, before the grant happens
    })
    DEVICE_ENDPOINTS = frozenset({
        "sms_ingest",    # native -> server, authenticated by header token
        "device_state",
    })
    # Endpoints reachable without a session in multi-user web mode only.
    LOGIN_ENDPOINTS = frozenset({"login", "signup"})

    def _valid_device_token(value: str) -> bool:
        token = app.config["DEVICE_TOKEN"]
        return bool(token) and hmac.compare_digest(value or "", token)

    def _grant_session() -> None:
        """Mark this session as belonging to the app itself."""
        session["dev_grant"] = True
        session.permanent = False
        if app.config["SINGLE_USER"]:
            session["user_id"] = auth.ensure_local_user(g.conn)

    @app.before_request
    def _authenticate():
        g.conn = db.connect(app.config["DB_PATH"])
        endpoint = request.endpoint

        # Unknown endpoint -> let Flask 404 it; there is nothing to protect.
        if endpoint is None:
            return None
        if endpoint in PUBLIC_ENDPOINTS:
            return None
        if endpoint in DEVICE_ENDPOINTS:
            return None            # the route itself calls device_authorized()

        # Everything below is session-authenticated.
        if not app.config["SINGLE_USER"]:
            return None            # multi-user web mode uses the login flow

        # Loopback is necessary but NOT sufficient — on Android every
        # co-installed app is also on loopback. It is kept as defence in depth.
        if request.remote_addr not in ("127.0.0.1", "::1"):
            abort(403)

        token = app.config["DEVICE_TOKEN"]
        if not token:
            # No token configured: desktop/dev/test. There is no secret to
            # authenticate with, so loopback is the whole boundary. This mode
            # never runs on a device — android_entry always supplies a token
            # (asserted by a test).
            app.config["AUTH_MODE"] = "loopback-only"
            if "user_id" not in session:
                session["user_id"] = auth.ensure_local_user(g.conn)
            return None

        app.config["AUTH_MODE"] = "device-token"
        # A caller presenting the token is the app itself; grant on the spot.
        if _valid_device_token(request.headers.get("X-SpendWise-Token", "")):
            _grant_session()
            return None
        # Or a one-time grant carried on the WebView's initial navigation.
        key = request.args.get("k")
        if key is not None:
            if not _valid_device_token(key):
                abort(403)
            _grant_session()
            # Redirect so the token does not linger in the page URL, and so
            # a reload cannot replay it from history.
            return redirect(url_for("dashboard"))

        # Otherwise the signed session cookie is the only credential. It is
        # signed with the Keystore-held secret, so a co-installed app can
        # neither read nor forge it.
        if not session.get("dev_grant"):
            abort(403)
        if "user_id" not in session:
            session["user_id"] = auth.ensure_local_user(g.conn)
        return None

    @app.teardown_request
    def _close_db(exc):
        conn = g.pop("conn", None)
        if conn is not None:
            # Safety net: a route that forgot g.conn.commit() must not lose
            # the user's data silently; a route that raised must not persist
            # a half-applied write.
            try:
                if exc is None:
                    conn.commit()
                else:
                    conn.rollback()
            except sqlite3.Error:
                pass
            conn.close()

    @app.errorhandler(500)
    def _server_error(exc):
        if request.path.startswith(("/sms/", "/device/")):
            return {"captured": False, "reason": "error"}, 500
        return ("<div style='font-family:sans-serif;padding:40px;text-align:center'>"
                "<h2>Something went wrong</h2><p>Your data is safe. "
                "<a href='/dashboard'>Back to SpendWise</a></p></div>"), 500

    # ── Helpers ──────────────────────────────────────────────────────────
    def current_user():
        uid = session.get("user_id")
        if not uid:
            return None
        cached = getattr(g, "_user", None)
        if cached is None or cached["id"] != uid:
            cached = g._user = auth.get_user(g.conn, uid)
        return cached

    # Bounded in-memory rate limit for the device endpoints. Even with a valid
    # token, a malfunctioning receiver or a hostile co-installed app must not
    # be able to drive unbounded writes into the ledger.
    _ingest_hits: list = []
    INGEST_MAX_PER_MIN = 120

    def rate_limited() -> bool:
        now = dt.datetime.now().timestamp()
        cutoff = now - 60
        while _ingest_hits and _ingest_hits[0] < cutoff:
            _ingest_hits.pop(0)
        if len(_ingest_hits) >= INGEST_MAX_PER_MIN:
            return True
        _ingest_hits.append(now)
        return False

    def device_authorized() -> bool:
        """Loopback-only AND (when a device token is configured) a matching
        token header — so a co-installed app can't reach these endpoints.

        In plain multi-user web mode (no device token, not single-user) the
        device endpoints are disabled outright: remote_addr can't be trusted
        behind a reverse proxy, and these endpoints write to the auto-created
        local user."""
        if not (app.config["SINGLE_USER"] or app.config["DEVICE_TOKEN"]):
            return False
        if request.remote_addr not in ("127.0.0.1", "::1"):
            return False
        token = app.config["DEVICE_TOKEN"]
        if token:
            sent = request.headers.get("X-SpendWise-Token", "")
            return hmac.compare_digest(sent, token)
        return True

    def settings_for(uid: str, fresh: bool = False) -> dict:
        cache = getattr(g, "_settings", None)
        if cache is None:
            cache = g._settings = {}
        if fresh or uid not in cache:
            row = db.one(g.conn, "SELECT * FROM settings WHERE user_id=?", (uid,))
            if row is None:
                db.execute(g.conn, "INSERT INTO settings(user_id) VALUES (?)", (uid,))
                g.conn.commit()
                row = db.one(g.conn, "SELECT * FROM settings WHERE user_id=?", (uid,))
            cache[uid] = dict(row)
        return cache[uid]

    def categories_for(uid: str, include_archived: bool = False):
        sql = "SELECT * FROM categories WHERE user_id=?"
        if not include_archived:
            sql += " AND is_archived=0"
        return db.all_rows(g.conn, sql + " ORDER BY name", (uid,))

    def parse_amount(raw: str) -> Optional[float]:
        """Parse a user-entered amount. None for anything that is not money.

        Goes through safe_amount so a hand-typed "inf", "nan" or 400-digit
        string is rejected here exactly as it is on the SMS path.
        """
        value = safe_amount(raw)
        if value is None:
            return None
        return round(value, 2)

    def parse_date(raw: str) -> Optional[dt.datetime]:
        if not raw:
            return None
        try:
            return dt.datetime.fromisoformat(raw).replace(tzinfo=dt.timezone.utc)
        except ValueError:
            try:
                d = dt.datetime.strptime(raw, "%Y-%m-%d")
                return d.replace(tzinfo=dt.timezone.utc)
            except ValueError:
                return None

    app.jinja_env.globals["now"] = lambda: dt.datetime.now()

    # UI helpers (presentation only — no behaviour change).
    _AVATAR_COLORS = [
        "#7c5cff", "#5b8cff", "#36d39a", "#ff6b81", "#fbbf24", "#22c1c3",
        "#a78bfa", "#f472b6", "#34d399", "#fb923c", "#60a5fa", "#e879f9",
    ]

    def _initials(name):
        parts = [p for p in (name or "").strip().split() if p]
        if not parts:
            return "?"
        if len(parts) == 1:
            return parts[0][:2].upper()
        return (parts[0][0] + parts[1][0]).upper()

    def _avatar_color(name):
        s = sum(ord(ch) for ch in (name or "?"))
        return _AVATAR_COLORS[s % len(_AVATAR_COLORS)]

    # ── Money presentation ───────────────────────────────────────────────
    # A finance app that renders "INR 73103.00" does not look like one. Indian
    # users read amounts in the lakh/crore grouping (1,23,456), not the western
    # thousands grouping (123,456), and a currency SYMBOL reads as money where
    # an ISO code reads as data.
    _CURRENCY_SYMBOLS = {"INR": "\u20b9", "USD": "$", "EUR": "\u20ac",
                         "GBP": "\u00a3", "AED": "\u062f.\u0625", "SGD": "S$",
                         "AUD": "A$", "CAD": "C$", "JPY": "\u00a5"}

    def _symbol(code):
        return _CURRENCY_SYMBOLS.get((code or "INR").upper(), (code or "") + " ")

    def _group_indian(whole: str) -> str:
        """1234567 -> 12,34,567. Last three digits, then pairs."""
        if len(whole) <= 3:
            return whole
        head, tail = whole[:-3], whole[-3:]
        parts = []
        while len(head) > 2:
            parts.insert(0, head[-2:])
            head = head[:-2]
        if head:
            parts.insert(0, head)
        return ",".join(parts + [tail])

    def _money(value, decimals=None):
        """Format an amount for display.

        Decimals are dropped when the amount is whole, because ".00" on every
        row is visual noise that makes a list harder to scan — but they are
        kept when they carry information (a real 290.50).
        """
        try:
            v = float(value)
        except (TypeError, ValueError):
            return "0"
        if v != v or v in (float("inf"), float("-inf")):
            return "0"
        neg = v < 0
        v = abs(v)
        if decimals is None:
            decimals = 0 if abs(v - round(v)) < 0.005 else 2
        text = f"{v:.{decimals}f}"
        whole, _, frac = text.partition(".")
        out = _group_indian(whole) + (("." + frac) if frac else "")
        return ("-" + out) if neg else out

    def _money_compact(value):
        """Short form for dense contexts (chart centres, day headers).

        Uses the Indian scale the user actually thinks in: K, L (lakh),
        Cr (crore) — not the western M/B.
        """
        try:
            v = abs(float(value))
        except (TypeError, ValueError):
            return "0"
        if v != v or v == float("inf"):
            return "0"
        if v >= 1e7:
            return f"{v / 1e7:.2f}".rstrip("0").rstrip(".") + "Cr"
        if v >= 1e5:
            return f"{v / 1e5:.2f}".rstrip("0").rstrip(".") + "L"
        if v >= 1000:
            return f"{v / 1000:.1f}".rstrip("0").rstrip(".") + "K"
        return _money(v)

    # ── Merchant display name ────────────────────────────────────────────
    _UPI_NOISE = re.compile(
        r"^(?:vpa|upi|p2[am]|imps|neft|pos|ach|mmt|bil|inft|ecom|nach)[\s/:-]+",
        re.IGNORECASE)
    _TRAILING_REF = re.compile(r"[\s/-]+\d{6,}$")
    _KNOWN_CASE = {"upi": "UPI", "atm": "ATM", "sbi": "SBI", "hdfc": "HDFC",
                   "icici": "ICICI", "kfc": "KFC", "dmart": "DMart",
                   "bigbasket": "BigBasket", "phonepe": "PhonePe",
                   "paytm": "Paytm", "ola": "Ola", "irctc": "IRCTC",
                   "bookmyshow": "BookMyShow", "jio": "Jio", "tata": "TATA"}

    def _merchant_display(name):
        """Make an unresolved raw payee readable.

        A resolved merchant already carries a clean canonical name. Everything
        else falls back to the raw SMS payee, which is how the ledger ended up
        showing "VPA AMAZON" one row above "Amazon", and "swiggy" above
        "Swiggy" — the same shop, three times, which makes the whole list look
        broken even though the data is correct.

        Presentation only: nothing is written back, so this cannot corrupt the
        merchant engine's learning key (which must stay the raw text).
        """
        raw = (name or "").strip()
        if not raw:
            return ""
        raw = raw.split("@", 1)[0]                 # swiggy@ybl -> swiggy
        raw = _UPI_NOISE.sub("", raw)              # VPA AMAZON -> AMAZON
        raw = raw.rsplit("/", 1)[-1]               # UPI/P2M/123/BLINKIT -> BLINKIT
        raw = _TRAILING_REF.sub("", raw).strip(" .,-_")
        if not raw:
            return (name or "").strip()
        words = []
        for w in raw.split():
            low = w.lower()
            if low in _KNOWN_CASE:
                words.append(_KNOWN_CASE[low])
            elif w.isupper() and len(w) <= 4:
                words.append(w)                    # keep genuine acronyms
            else:
                words.append(w[:1].upper() + w[1:].lower() if w.isupper() else
                             w[:1].upper() + w[1:])
        return " ".join(words)

    app.jinja_env.filters["initials"] = _initials
    app.jinja_env.filters["avatar_color"] = _avatar_color
    app.jinja_env.filters["symbol"] = _symbol
    app.jinja_env.filters["money"] = _money
    app.jinja_env.filters["money_compact"] = _money_compact
    app.jinja_env.filters["merchant"] = _merchant_display

    @app.context_processor
    def _inject_globals():
        theme, nav_fraud, nav_review, nav_held = "system", 0, 0, 0
        cat_prompts, prompt_categories = [], []
        st = db.one(g.conn, "SELECT value FROM app_state WHERE key='sms_permission'")
        sms_denied = bool(st and st["value"] == "denied")
        uid = session.get("user_id")
        if uid:
            theme = settings_for(uid)["theme"]
            counts = db.one(
                g.conn,
                "SELECT (SELECT COUNT(*) FROM fraud_alerts WHERE user_id=:u AND "
                "status='open') f, (SELECT COUNT(*) FROM transactions WHERE "
                "user_id=:u AND is_deleted=0 AND status IN "
                "('pending_confirmation','needs_review')) r, "
                "(SELECT COUNT(*) FROM sms_quarantine WHERE user_id=:u AND "
                "status='pending') q", {"u": uid})
            nav_fraud, nav_review, nav_held = counts["f"], counts["r"], counts["q"]
            # Auto-captured SMS transactions still awaiting a category → popup.
            # Never on the bulk-review screen: the popup is a modal overlay and
            # would cover the very page built to clear these in bulk.
            if request.endpoint == "review_page":
                return {"app_name": "SpendWise", "single_user": app.config["SINGLE_USER"],
                        "theme": theme, "nav_fraud": nav_fraud, "nav_review": nav_review,
                        "nav_held": nav_held, "cat_prompts": [], "prompt_categories": [],
                        "nav_categories": categories_for(uid), "sms_denied": sms_denied}
            cat_prompts = db.all_rows(
                g.conn, "SELECT * FROM transactions WHERE user_id=? AND category_id IS NULL "
                "AND source='sms' AND is_deleted=0 AND COALESCE(category_prompted,0)=0 "
                "ORDER BY occurred_at DESC, created_at DESC LIMIT 20", (uid,))
            if cat_prompts:
                prompt_categories = db.all_rows(
                    g.conn, "SELECT * FROM categories WHERE user_id=? AND is_archived=0 "
                    "ORDER BY type DESC, name", (uid,))
        # The add-transaction sheet lives in base.html (FAB opens it on every
        # page), so every page needs the category list.
        nav_categories = categories_for(uid) if uid else []
        return {"app_name": "SpendWise", "single_user": app.config["SINGLE_USER"],
                "theme": theme, "nav_fraud": nav_fraud, "nav_review": nav_review,
                "nav_held": nav_held,
                "cat_prompts": cat_prompts, "prompt_categories": prompt_categories,
                "nav_categories": nav_categories, "sms_denied": sms_denied}

    def effective_thresholds(uid: str) -> dict:
        """The engine thresholds to use for this user, right now.

        Adapts to the measured correction rate unless the user has set their
        own values (then their choice stands). Cached per request: it runs a
        single aggregate over `learning`, but every transaction in a bulk
        import would otherwise repeat it.
        """
        cached = getattr(g, "_thresholds", None)
        if cached and cached[0] == uid:
            return cached[1]
        s_ = settings_for(uid)
        state = calibration.thresholds(
            g.conn, uid, s_["auto_save_threshold"], s_["confirm_threshold"],
            user_set=_thresholds_are_user_set(uid))
        g._thresholds = (uid, state)
        return state

    def _thresholds_are_user_set(uid: str) -> bool:
        """True once the user has moved either slider off its default."""
        s_ = settings_for(uid)
        return not (int(s_["auto_save_threshold"]) == 80
                    and int(s_["confirm_threshold"]) == 50)

    def require_login():
        if not session.get("user_id") or current_user() is None:
            return None
        return session["user_id"]

    # ── Transaction orchestration ────────────────────────────────────────
    def create_transaction(uid: str, *, amount: float, type_: str, category_id=None,
                           merchant=None, raw_merchant=None, notes=None,
                           reference_number=None, occurred_at: Optional[dt.datetime] = None,
                           source="manual", resolve=True, dedup_key=None,
                           sms_body=None, sms_sender=None, assessment=None) -> dict:
        # Storage gate. Every caller already validates, but this is the last
        # point before a value becomes permanent, and a non-finite amount in
        # the ledger is not a bad row — it poisons every aggregate that reads
        # it and crashes detect_transfers outright. Refuse rather than store.
        amount = safe_amount(amount)
        if amount is None:
            raise ValueError("amount must be a finite value in (0, %g]" % MAX_AMOUNT)
        s = settings_for(uid)
        # Same clock domain as stored SMS times: local wall-clock stamped UTC.
        occ = occurred_at or dt.datetime.now().replace(microsecond=0,
                                                       tzinfo=dt.timezone.utc)
        if type_ not in ("income", "expense"):
            type_ = "expense"
        raw = (raw_merchant or merchant or "").strip() or None
        tx_id = db.new_id()
        merchant_id = None
        merchant_name = None
        confidence = None
        status = TX_CONFIRMED
        decision = "none"
        breakdown = None

        if merchant and merchant.strip():
            m = engine.get_or_create_merchant(g.conn, user_id=uid,
                                              canonical_name=merchant.strip(),
                                              category_id=category_id)
            merchant_id, merchant_name, confidence = m["id"], m["canonical_name"], 100
            decision = engine.DECISION_AUTO
            if raw:
                engine.record_confirmation(g.conn, user_id=uid, raw_name=raw,
                                           merchant_name=m["canonical_name"], amount=amount,
                                           category_id=category_id, occurred_at=occ)
        elif resolve and raw:
            res = engine.resolve(g.conn, user_id=uid, raw_name=raw, amount=amount,
                                 category_id=category_id, occurred_at=occ,
                                 auto=effective_thresholds(uid)["auto"],
                                 confirm=effective_thresholds(uid)["confirm"])
            decision = res["decision"]
            if res["best"]:
                best = res["best"]
                confidence = best["confidence"]
                breakdown = best["breakdown"]
                if decision == engine.DECISION_AUTO:
                    merchant_id, merchant_name = best["merchant_id"], best["merchant_name"]
                    status = TX_CONFIRMED
                    engine.record_confirmation(g.conn, user_id=uid, raw_name=raw,
                                               merchant_name=best["merchant_name"], amount=amount,
                                               category_id=category_id, occurred_at=occ)
                elif decision == engine.DECISION_CONFIRM:
                    merchant_id, merchant_name = best["merchant_id"], best["merchant_name"]
                    status = TX_PENDING
                else:
                    status = TX_REVIEW
            else:
                status = TX_REVIEW
                decision = engine.DECISION_MANUAL

        # Sender trust modulates merchant confidence. A perfectly-matched
        # merchant from an unverified sender is still an unverified claim, so
        # the row is demoted to needs_review rather than auto-confirmed. This
        # is the "confidence downgrade" half of the phishing defence: the
        # message is kept, but it is never quietly treated as fact.
        sender_trust = sender_risk = None
        if assessment is not None:
            sender_trust = assessment.trust
            sender_risk = assessment.risk
            if assessment.confidence_delta:
                if confidence is not None:
                    confidence = max(0, confidence + assessment.confidence_delta)
                if status == TX_CONFIRMED and source == "sms":
                    status = TX_REVIEW
                if decision == engine.DECISION_AUTO:
                    decision = engine.DECISION_CONFIRM

        # Inherit the resolved merchant's learned category when the caller did
        # not specify one (e.g. an auto-captured SMS) — so we only have to ask
        # the user about genuinely unknown merchants.
        if merchant_id and category_id is None:
            mrow = db.one(g.conn, "SELECT category_id FROM merchants WHERE id=?", (merchant_id,))
            if mrow and mrow["category_id"]:
                category_id = mrow["category_id"]

        db.execute(g.conn,
                   "INSERT INTO transactions(id,user_id,amount,type,category_id,raw_merchant,"
                   "merchant_id,merchant_name,notes,reference_number,occurred_at,source,"
                   "confidence,status,dedup_key,sms_body,sms_sender,sender_trust,"
                   "sender_risk,is_deleted,created_at) "
                   "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?)",
                   (tx_id, uid, amount, type_, category_id, raw, merchant_id, merchant_name,
                    notes, reference_number, occ.isoformat(), source, confidence, status,
                    dedup_key, sms_body, sms_sender, sender_trust, sender_risk or 0,
                    dt.datetime.now(dt.timezone.utc).isoformat()))

        alert_ids = fraud.evaluate_transaction(
            g.conn, user_id=uid,
            tx={"id": tx_id, "amount": amount, "type": type_, "merchant_name": merchant_name,
                "raw_merchant": raw, "occurred_at": occ, "is_deleted": False},
            high_value_limit=float(s["high_value_amount"] or 0))
        g.conn.commit()
        return {"id": tx_id, "decision": decision, "confidence": confidence,
                "resolved_merchant": merchant_name, "breakdown": breakdown,
                "status": status, "fraud_alert_ids": alert_ids}

    # ── Routes: auth ─────────────────────────────────────────────────────
    @app.get("/")
    def index():
        return redirect(url_for("dashboard") if session.get("user_id") else url_for("login"))

    @app.route("/login", methods=["GET", "POST"])
    def login():
        if session.get("user_id"):
            return redirect(url_for("dashboard"))
        if request.method == "POST":
            try:
                uid = auth.authenticate(g.conn, email=request.form.get("email", ""),
                                        password=request.form.get("password", ""))
            except auth.AuthError as e:
                return render_template("login.html", error=e.message,
                                       email=request.form.get("email", "")), 401
            session["user_id"] = uid
            return redirect(url_for("dashboard"))
        return render_template("login.html")

    @app.route("/signup", methods=["GET", "POST"])
    def signup():
        if session.get("user_id"):
            return redirect(url_for("dashboard"))
        if request.method == "POST":
            try:
                uid = auth.create_user(g.conn, email=request.form.get("email", ""),
                                       full_name=request.form.get("full_name", ""),
                                       password=request.form.get("password", ""))
            except auth.AuthError as e:
                return render_template("signup.html", error=e.message,
                                       email=request.form.get("email", ""),
                                       full_name=request.form.get("full_name", "")), 409
            session["user_id"] = uid
            return redirect(url_for("dashboard"))
        return render_template("signup.html")

    @app.post("/logout")
    def logout():
        session.clear()
        return redirect(url_for("login"))

    # ── Routes: dashboard ────────────────────────────────────────────────
    @app.get("/dashboard")
    def dashboard():
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        s = settings_for(uid)
        d = analytics.build_dashboard(g.conn, uid, currency=s["currency"])
        return render_template("dashboard.html", d=d, user=current_user(), active="dashboard")

    @app.get("/report")
    def report_page():
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        cur_m = dt.datetime.now().strftime("%Y-%m")
        m = (request.args.get("m") or cur_m).strip()
        try:
            # Normalise (strptime accepts unpadded "2025-7", which would break
            # the report's zero-padded day keys and string comparisons).
            m = dt.datetime.strptime(m, "%Y-%m").strftime("%Y-%m")
        except ValueError:
            m = cur_m
        if m > cur_m:  # no reports for the future
            m = cur_m
        s = settings_for(uid)
        r = analytics.build_report(g.conn, uid, m)
        # Computed on the device from the user's own rows. Nothing here leaves
        # the phone, and nothing here needs a network.
        ins = insights.build_insights(g.conn, uid, m)
        return render_template("report.html", r=r, ins=ins, cur_m=cur_m,
                               currency=s["currency"],
                               user=current_user(), active="dashboard",
                               back_href="/dashboard")

    # ── Routes: transactions ─────────────────────────────────────────────
    @app.get("/transactions")
    def transactions():
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        q = (request.args.get("q") or "").strip()
        f = (request.args.get("f") or "").strip()
        # Join the category name so the row can show something the user can
        # act on instead of the engine's confidence score.
        sql = ("SELECT t.*, c.name AS category_name FROM transactions t "
               "LEFT JOIN categories c ON c.id = t.category_id "
               "WHERE t.user_id=? AND t.is_deleted=0")
        params = [uid]
        if q:
            # Try the FTS5 index first. It returns None when the index is
            # absent (older SQLite) or matched nothing, in which case we fall
            # back to the substring scan so the optimisation can never make a
            # transaction unfindable that used to be findable.
            hits = search.search_ids(g.conn, uid, q, limit=200)
            if hits is not None:
                sql += " AND t.id IN (%s)" % ",".join("?" * len(hits))
                params += hits
            else:
                like = f"%{q.lower()}%"
                sql += (" AND (lower(COALESCE(t.merchant_name,'')) LIKE ? OR "
                        "lower(COALESCE(t.raw_merchant,'')) LIKE ? OR "
                        "lower(COALESCE(t.notes,'')) LIKE ? OR "
                        "lower(COALESCE(t.reference_number,'')) LIKE ?)")
                params += [like, like, like, like]
        if f == "expense":
            sql += " AND t.type='expense'"
        elif f == "income":
            sql += " AND t.type='income'"
        elif f == "sms":
            sql += " AND t.source='sms'"
        elif f == "review":
            sql += " AND t.status IN ('pending_confirmation','needs_review')"
        sql += " ORDER BY t.occurred_at DESC, t.created_at DESC LIMIT 200"
        rows = db.all_rows(g.conn, sql, tuple(params))
        # Deep link from a fraud alert: make sure the transaction is present
        # and rendered expanded + highlighted.
        focus_tx = (request.args.get("tx") or "").strip()
        if focus_tx and not any(r["id"] == focus_tx for r in rows):
            extra = db.one(g.conn, "SELECT * FROM transactions WHERE id=? AND user_id=? "
                           "AND is_deleted=0", (focus_tx, uid))
            if extra:
                rows = sorted(rows + [extra], key=lambda r: (r["occurred_at"],
                              r["created_at"]), reverse=True)
        day_totals: dict = {}
        for r in rows:
            if r["type"] == "expense":
                day = r["occurred_at"][:10]
                day_totals[day] = round(day_totals.get(day, 0.0) + r["amount"], 2)
        undo_id = (request.args.get("undo") or "").strip()
        undo_tx = db.one(g.conn, "SELECT * FROM transactions WHERE id=? AND user_id=? "
                         "AND is_deleted=1", (undo_id, uid)) if undo_id else None
        # One-tap merchant confirmation: give the review queue the engine's
        # ranked candidates as chips instead of forcing typing.
        s = settings_for(uid)
        suggestions: dict = {}
        for r in rows:
            if (r["status"] in (TX_PENDING, TX_REVIEW) and r["raw_merchant"]
                    and len(suggestions) < 12):
                res = engine.resolve(g.conn, user_id=uid, raw_name=r["raw_merchant"],
                                     amount=r["amount"],
                                     auto=effective_thresholds(uid)["auto"],
                                     confirm=effective_thresholds(uid)["confirm"])
                names, seen = [], set()
                for cand in res["candidates"][:3]:
                    if cand["merchant_name"].lower() not in seen:
                        names.append(cand["merchant_name"])
                        seen.add(cand["merchant_name"].lower())
                if names:
                    suggestions[r["id"]] = names
        return render_template("transactions.html", transactions=rows, total=len(rows),
                               q=q, f=f, categories=categories_for(uid), user=current_user(),
                               currency=s["currency"], focus_tx=focus_tx,
                               day_totals=day_totals, undo_tx=undo_tx,
                               suggestions=suggestions, active="transactions")

    @app.post("/transactions")
    def transactions_create():
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        amount = parse_amount(request.form.get("amount", ""))
        if amount is None or amount <= 0:
            return redirect(url_for("transactions", error="amount"))
        create_transaction(
            uid, amount=amount, type_=request.form.get("type", "expense"),
            category_id=request.form.get("category_id") or None,
            merchant=request.form.get("merchant", ""), notes=request.form.get("notes", "") or None,
            occurred_at=parse_date(request.form.get("occurred_at", "")), source="manual")
        return redirect(url_for("transactions", added=1))

    @app.post("/transactions/resolve")
    def transactions_resolve():
        uid = require_login()
        if not uid:
            abort(401)
        name = (request.form.get("merchant") or "").strip()
        if not name:
            return ""
        s = settings_for(uid)
        res = engine.resolve(g.conn, user_id=uid, raw_name=name,
                             amount=parse_amount(request.form.get("amount", "")),
                             auto=effective_thresholds(uid)["auto"],
                                 confirm=effective_thresholds(uid)["confirm"])
        return render_template("_resolve.html", best=res["best"], decision=res["decision"],
                               breakdown=res["best"]["breakdown"] if res["best"] else None)

    @app.post("/transactions/<tx_id>/confirm")
    def transactions_confirm(tx_id):
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        tx = db.one(g.conn, "SELECT * FROM transactions WHERE id=? AND user_id=? AND is_deleted=0",
                    (tx_id, uid))
        merchant = (request.form.get("merchant") or "").strip()
        if tx and merchant:
            cat = request.form.get("category_id") or tx["category_id"]
            is_correction = bool(tx["merchant_name"] and
                                 tx["merchant_name"].lower() != merchant.lower())
            m = engine.get_or_create_merchant(g.conn, user_id=uid, canonical_name=merchant,
                                              category_id=cat)
            db.execute(g.conn,
                       "UPDATE transactions SET merchant_id=?, merchant_name=?, category_id=?, "
                       "status=?, confidence=100 WHERE id=?",
                       (m["id"], m["canonical_name"], cat, TX_CONFIRMED, tx_id))
            occ = dt.datetime.fromisoformat(tx["occurred_at"])
            if tx["raw_merchant"]:
                engine.record_confirmation(g.conn, user_id=uid, raw_name=tx["raw_merchant"],
                                           merchant_name=m["canonical_name"], amount=tx["amount"],
                                           category_id=cat, occurred_at=occ,
                                           is_correction=is_correction)
            g.conn.commit()
        return redirect(url_for("transactions", confirmed=1))

    @app.post("/transactions/<tx_id>/categorize")
    def transactions_categorize(tx_id):
        """Answer the 'which category?' popup for an auto-captured SMS txn.

        Picking a category also teaches the engine so the same merchant is
        categorised automatically next time. Submitting with no category just
        dismisses the prompt for this transaction.
        """
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        tx = db.one(g.conn, "SELECT * FROM transactions WHERE id=? AND user_id=? AND is_deleted=0",
                    (tx_id, uid))
        if tx:
            cat = request.form.get("category_id") or None
            # Inline "create & assign" from the popup — no context switch.
            new_name = (request.form.get("new_category") or "").strip()
            if not cat and new_name:
                existing = db.one(g.conn, "SELECT id FROM categories WHERE user_id=? "
                                  "AND lower(name)=?", (uid, new_name.lower()))
                if existing:
                    cat = existing["id"]
                else:
                    cat = db.new_id()
                    db.execute(g.conn, "INSERT INTO categories(id,user_id,name,type,icon,color) "
                               "VALUES (?,?,?,?,?,?)",
                               (cat, uid, new_name[:40], "expense", "Tag", "#7c5cff"))
            valid = cat and db.one(g.conn, "SELECT id FROM categories WHERE id=? AND user_id=?",
                                   (cat, uid))
            if valid and tx["raw_merchant"]:
                canonical = tx["merchant_name"] or tx["raw_merchant"]
                occ = dt.datetime.fromisoformat(tx["occurred_at"])
                engine.record_confirmation(g.conn, user_id=uid, raw_name=tx["raw_merchant"],
                                           merchant_name=canonical, amount=tx["amount"],
                                           category_id=cat, occurred_at=occ)
                m = engine.get_or_create_merchant(g.conn, user_id=uid,
                                                  canonical_name=canonical, category_id=cat)
                db.execute(g.conn,
                           "UPDATE transactions SET category_id=?, merchant_id=?, merchant_name=?, "
                           "category_prompted=1 WHERE id=?", (cat, m["id"], canonical, tx_id))
            elif valid:
                db.execute(g.conn, "UPDATE transactions SET category_id=?, category_prompted=1 "
                           "WHERE id=?", (cat, tx_id))
            else:
                db.execute(g.conn, "UPDATE transactions SET category_prompted=1 WHERE id=?", (tx_id,))
            g.conn.commit()
        return redirect(request.referrer or url_for("dashboard"))

    @app.get("/review")
    def review_page():
        """Bulk review: group everything awaiting review BY MERCHANT.

        Reviewing hundreds of captures one at a time is not realistic — but
        they come from far fewer merchants, so one decision per merchant
        clears them all.
        """
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        rows = db.all_rows(
            g.conn,
            "SELECT COALESCE(NULLIF(merchant_name,''), NULLIF(raw_merchant,''), '') k, "
            "COUNT(*) n, SUM(amount) total, MIN(occurred_at) first_at, "
            "MAX(occurred_at) last_at, type, MAX(sms_body) sample, MAX(sms_sender) sender "
            "FROM transactions WHERE user_id=? AND is_deleted=0 AND source='sms' "
            "AND (category_id IS NULL OR status IN (?,?)) "
            "GROUP BY k, type ORDER BY n DESC, total DESC",
            (uid, TX_PENDING, TX_REVIEW))
        groups = [dict(r) for r in rows]
        # Pre-fill each group with a category suggestion learned from the
        # user's own confirmed history. This is the difference between "pick a
        # category for 40 merchants" and "confirm 40 pre-filled guesses".
        # Suggestions are never applied automatically — the user still taps.
        cat_names = {c["id"]: c["name"] for c in categories_for(uid)}
        for gp in groups:
            hint = categorizer.suggest(
                g.conn, uid, "%s %s" % (gp.get("k") or "", gp.get("sample") or ""))
            if hint and hint["category_id"] in cat_names:
                gp["suggested_id"] = hint["category_id"]
                gp["suggested_name"] = cat_names[hint["category_id"]]
                gp["suggested_confidence"] = hint["confidence"]
                gp["suggested_because"] = hint["because"]
        pending_total = sum(gp["n"] for gp in groups)
        suggested_count = sum(1 for gp in groups if gp.get("suggested_id"))
        s = settings_for(uid)
        return render_template("review.html", groups=groups, pending_total=pending_total,
                               suggested_count=suggested_count,
                               categories=categories_for(uid), currency=s["currency"],
                               user=current_user(), active="transactions",
                               back_href="/transactions")

    @app.post("/review/bulk")
    def review_bulk():
        """Apply one decision to every unreviewed capture from a merchant."""
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        key = (request.form.get("key") or "").strip()
        type_ = request.form.get("type") or "expense"
        action = request.form.get("action") or "categorize"
        rows = db.all_rows(
            g.conn,
            "SELECT * FROM transactions WHERE user_id=? AND is_deleted=0 AND source='sms' "
            "AND COALESCE(NULLIF(merchant_name,''), NULLIF(raw_merchant,''), '')=? "
            "AND type=? AND (category_id IS NULL OR status IN (?,?))",
            (uid, key, type_, TX_PENDING, TX_REVIEW))
        if not rows:
            return redirect(url_for("review_page"))

        if action == "delete":
            # "Not a transaction" — junk from one sender, cleared in one tap.
            # Single statement: a 200-row group was previously 200 UPDATEs.
            db.executemany(g.conn, "UPDATE transactions SET is_deleted=1 WHERE id=?",
                           [(r["id"],) for r in rows])
            g.conn.commit()
            return redirect(url_for("review_page", removed=len(rows)))

        cat = request.form.get("category_id") or None
        new_name = (request.form.get("new_category") or "").strip()
        if not cat and new_name:
            existing = db.one(g.conn, "SELECT id FROM categories WHERE user_id=? "
                              "AND lower(name)=?", (uid, new_name.lower()))
            if existing:
                cat = existing["id"]
            else:
                cat = db.new_id()
                db.execute(g.conn, "INSERT INTO categories(id,user_id,name,type,icon,color) "
                           "VALUES (?,?,?,?,?,?)",
                           (cat, uid, new_name[:40], type_, "Tag", "#7c5cff"))
        if cat and not db.one(g.conn, "SELECT id FROM categories WHERE id=? AND user_id=?",
                              (cat, uid)):
            cat = None
        if not cat:
            return redirect(url_for("review_page"))

        # Name the merchant once for the whole group, and teach the engine so
        # future messages from it are categorised automatically.
        canonical = (request.form.get("merchant") or key).strip() or key
        merchant = engine.get_or_create_merchant(
            g.conn, user_id=uid, canonical_name=canonical, category_id=cat) if canonical else None
        db.executemany(
            g.conn,
            "UPDATE transactions SET category_id=?, category_prompted=1, status=?, "
            "merchant_id=COALESCE(?, merchant_id), "
            "merchant_name=COALESCE(?, merchant_name) WHERE id=?",
            [(cat, TX_CONFIRMED,
              merchant["id"] if merchant else None,
              merchant["canonical_name"] if merchant else None, r["id"]) for r in rows])
        if merchant:
            # Learn from EVERY transaction the user just confirmed, not just
            # one — a bulk confirmation is that many pieces of evidence, so the
            # engine ends up genuinely confident about this merchant's amounts
            # and timing. Capped so a huge group stays fast.
            for taught in rows[:25]:
                if not taught["raw_merchant"]:
                    continue
                engine.record_confirmation(
                    g.conn, user_id=uid, raw_name=taught["raw_merchant"],
                    merchant_name=merchant["canonical_name"], amount=taught["amount"],
                    category_id=cat,
                    occurred_at=dt.datetime.fromisoformat(taught["occurred_at"]))
        g.conn.commit()
        return redirect(url_for("review_page", done=len(rows)))

    @app.post("/sms/purge")
    def sms_purge():
        """Delete unreviewed SMS captures in one tap.

        Needed after a bad-precision build let promotional/scam messages in:
        re-running the (now stricter) parser over hundreds of rows by hand is
        not reasonable. Only untouched auto-captures are removed — anything
        confirmed, categorised or hand-edited is kept — and rows are
        soft-deleted, so nothing is destroyed.
        """
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        cur = db.execute(
            g.conn,
            "UPDATE transactions SET is_deleted=1 WHERE user_id=? AND source='sms' "
            "AND is_deleted=0 AND status IN (?,?) AND category_id IS NULL",
            (uid, TX_PENDING, TX_REVIEW))
        removed = cur.rowcount if cur else 0
        # Their fraud alerts are meaningless once the transactions are gone.
        db.execute(g.conn, "UPDATE fraud_alerts SET status='dismissed' WHERE user_id=? "
                   "AND status='open' AND transaction_id IN (SELECT id FROM transactions "
                   "WHERE user_id=? AND is_deleted=1)", (uid, uid))
        g.conn.commit()
        return redirect(url_for("settings_page", purged=removed))

    @app.post("/sms/prompts/dismiss")
    def sms_prompts_dismiss():
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        db.execute(g.conn, "UPDATE transactions SET category_prompted=1 WHERE user_id=? AND "
                   "category_id IS NULL AND source='sms' AND is_deleted=0", (uid,))
        g.conn.commit()
        return redirect(request.referrer or url_for("dashboard"))

    @app.post("/transactions/<tx_id>/delete")
    def transactions_delete(tx_id):
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        db.execute(g.conn, "UPDATE transactions SET is_deleted=1 WHERE id=? AND user_id=?",
                   (tx_id, uid))
        g.conn.commit()
        return redirect(url_for("transactions", undo=tx_id))

    @app.post("/transactions/<tx_id>/restore")
    def transactions_restore(tx_id):
        """Undo a delete (soft-deleted rows are kept, so this is loss-free)."""
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        db.execute(g.conn, "UPDATE transactions SET is_deleted=0 WHERE id=? AND user_id=?",
                   (tx_id, uid))
        g.conn.commit()
        return redirect(url_for("transactions", restored=1))

    @app.post("/transactions/<tx_id>/edit")
    def transactions_edit(tx_id):
        """Edit any field of an existing transaction. A changed merchant is
        learned (confirmed at 100%) so future resolutions improve."""
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        tx = db.one(g.conn, "SELECT * FROM transactions WHERE id=? AND user_id=? AND is_deleted=0",
                    (tx_id, uid))
        if not tx:
            return redirect(url_for("transactions"))
        amount = parse_amount(request.form.get("amount", ""))
        if amount is None or amount <= 0:
            return redirect(url_for("transactions", error="amount"))
        type_ = request.form.get("type", tx["type"])
        if type_ not in ("income", "expense"):
            type_ = tx["type"]
        cat = request.form.get("category_id") or None
        if cat and not db.one(g.conn, "SELECT id FROM categories WHERE id=? AND user_id=?",
                              (cat, uid)):
            cat = tx["category_id"]
        occ = parse_date(request.form.get("occurred_at", ""))
        occ_iso = occ.isoformat() if occ else tx["occurred_at"]
        notes = (request.form.get("notes") or "").strip() or None
        merchant = (request.form.get("merchant") or "").strip()
        merchant_id, merchant_name = tx["merchant_id"], tx["merchant_name"]
        confidence, status = tx["confidence"], tx["status"]
        if merchant and merchant != (tx["merchant_name"] or ""):
            mrow = engine.get_or_create_merchant(g.conn, user_id=uid,
                                                 canonical_name=merchant, category_id=cat)
            merchant_id, merchant_name = mrow["id"], mrow["canonical_name"]
            confidence, status = 100, TX_CONFIRMED
            if tx["raw_merchant"]:
                engine.record_confirmation(
                    g.conn, user_id=uid, raw_name=tx["raw_merchant"],
                    merchant_name=merchant_name, amount=amount, category_id=cat,
                    occurred_at=dt.datetime.fromisoformat(occ_iso),
                    is_correction=bool(tx["merchant_name"]))
        db.execute(g.conn,
                   "UPDATE transactions SET amount=?, type=?, category_id=?, occurred_at=?, "
                   "notes=?, merchant_id=?, merchant_name=?, confidence=?, status=? WHERE id=?",
                   (amount, type_, cat, occ_iso, notes, merchant_id, merchant_name,
                    confidence, status, tx_id))
        g.conn.commit()
        return redirect(url_for("transactions", edited=1))

    @app.get("/merchant")
    def merchant_page():
        """Drill-down for one merchant: stats, monthly trend, full history."""
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        name = (request.args.get("n") or "").strip()
        if not name:
            return redirect(url_for("transactions"))
        rows = db.all_rows(
            g.conn, "SELECT * FROM transactions WHERE user_id=? AND is_deleted=0 AND "
            "merchant_name=? ORDER BY occurred_at DESC LIMIT 100", (uid, name))
        total = round(sum(r["amount"] for r in rows if r["type"] == "expense"), 2)
        monthly: dict = {}
        for r in rows:
            if r["type"] == "expense":
                p = r["occurred_at"][:7]
                monthly[p] = round(monthly.get(p, 0.0) + r["amount"], 2)
        trend = [{"period": p, "value": monthly[p]} for p in sorted(monthly)][-6:]
        # Merchant intelligence: what the engine has learned about this
        # merchant — aliases, confirmations, corrections, typical amounts.
        learning = db.all_rows(
            g.conn, "SELECT l.*, c.name category_name FROM learning l "
            "LEFT JOIN categories c ON c.id = l.category_id "
            "WHERE l.user_id=? AND l.merchant_name=? "
            "ORDER BY l.confirmation_count DESC", (uid, name))
        s = settings_for(uid)
        return render_template("merchant.html", name=name, transactions=rows,
                               learning=learning,
                               total=total, count=len(rows), trend=trend,
                               avg=round(total / max(1, sum(1 for r in rows
                                         if r["type"] == "expense")), 2),
                               currency=s["currency"], user=current_user(),
                               active="transactions", back_href="/transactions")

    # ── Routes: SMS import ───────────────────────────────────────────────
    @app.get("/import")
    def import_page():
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        recent_sms = db.all_rows(
            g.conn, "SELECT * FROM transactions WHERE user_id=? AND source='sms' "
            "AND is_deleted=0 ORDER BY created_at DESC LIMIT 8", (uid,))
        return render_template("import.html", user=current_user(), active="import",
                               currency=settings_for(uid)["currency"],
                               recent_sms=recent_sms, back_href="/transactions")

    @app.post("/import/parse")
    def import_parse():
        uid = require_login()
        if not uid:
            abort(401)
        parsed = parse_sms(request.form.get("sms", ""))
        s = settings_for(uid)
        preview = None
        if parsed.raw_merchant:
            res = engine.resolve(g.conn, user_id=uid, raw_name=parsed.raw_merchant,
                                 amount=parsed.amount, occurred_at=parsed.occurred_at,
                                 auto=effective_thresholds(uid)["auto"],
                                 confirm=effective_thresholds(uid)["confirm"])
            preview = {"best": res["best"], "decision": res["decision"],
                       "breakdown": res["best"]["breakdown"] if res["best"] else None}
        return render_template("_import_preview.html", parsed=parsed, preview=preview)

    @app.post("/import/create")
    def import_create():
        uid = require_login()
        if not uid:
            abort(401)
        amount = parse_amount(request.form.get("amount", ""))
        if amount is None or amount <= 0:
            return '<p class="error">Could not read the amount.</p>'
        result = create_transaction(
            uid, amount=amount, type_=request.form.get("type", "expense"),
            raw_merchant=request.form.get("raw_merchant", ""),
            reference_number=request.form.get("reference_number", "") or None,
            occurred_at=parse_date(request.form.get("occurred_at", "")),
            source="sms", resolve=True)
        return render_template("_import_result.html", result=result,
                               breakdown=result["breakdown"])

    @app.post("/sms/ingest")
    def sms_ingest():
        """Auto-capture a finance SMS pushed by the Android SMS receiver.

        Called directly by the device (no pasting, no session cookie), so it is
        restricted to loopback and resolves the single on-device user. Returns
        JSON describing whether a transaction was captured and if it still needs
        a category from the user.
        """
        if not device_authorized():
            abort(403)
        if rate_limited():
            # 429 tells the receiver to re-queue rather than drop the message.
            return {"captured": False, "reason": "rate_limited"}, 429
        uid = auth.ensure_local_user(g.conn)
        body = request.form.get("body") or request.form.get("sms") or ""
        raw_sender = request.form.get("sender")
        parsed = parse_sms(body, raw_sender)

        # Sender verification runs on EVERY message, parsable or not, so the
        # registry reflects real traffic rather than only the captures.
        assessment = senders.assess(
            raw_sender, body, _sender_row(uid, raw_sender))
        _touch_sender(uid, raw_sender, assessment)

        if not parsed.matched or not parsed.amount or parsed.amount <= 0:
            # Record what we could not read. Banks change formats without
            # notice, and the failure is otherwise SILENT — transactions just
            # stop appearing. Stored on-device only; nothing is transmitted.
            _record_parse_miss(uid, body, raw_sender,
                               "no_amount" if not parsed.amount else "not_transactional")
            return {"captured": False, "reason": "not_financial"}, 200

        if assessment.action == senders.ACTION_QUARANTINE:
            # Held, never dropped: the user can approve it into the ledger from
            # /sms/quarantine. Silently discarding would mean a real
            # transaction disappearing with no trace, which is a worse failure
            # than a suspicious row the user can reject.
            qid = _quarantine(uid, raw_sender, body, parsed, assessment)
            return {"captured": False, "reason": "quarantined", "id": qid,
                    "risk": assessment.risk,
                    "indicators": assessment.indicators,
                    "explanation": senders.explain(assessment)}, 200
        # Idempotency: the same message must not be captured twice (the receiver
        # may both POST live and re-queue on a flaky connection). Prefer the bank
        # reference; fall back to a content hash for messages that have none.
        dedup_key = parsed.reference_number or hashlib.sha1(
            ("%s|%s|%s|%s|%s" % (uid, parsed.amount, parsed.type,
             parsed.occurred_at or "", body.strip())).encode("utf-8")).hexdigest()
        dup = db.one(g.conn, "SELECT id, category_id FROM transactions WHERE user_id=? "
                     "AND source='sms' AND dedup_key=? AND is_deleted=0", (uid, dedup_key))
        if dup:
            return {"captured": False, "reason": "duplicate", "id": dup["id"],
                    "needs_category": dup["category_id"] is None}, 200
        try:
            result = create_transaction(
                uid, amount=parsed.amount, type_=parsed.type,
                raw_merchant=parsed.raw_merchant, reference_number=parsed.reference_number,
                occurred_at=parsed.occurred_at, source="sms", resolve=True,
                dedup_key=dedup_key, sms_body=body.strip()[:400],
                sms_sender=(raw_sender or "").strip()[:32] or None,
                assessment=assessment)
        except sqlite3.IntegrityError:
            # Lost the check-then-insert race (live POST + queue drain landing
            # together) — the unique dedup index made the second insert fail.
            g.conn.rollback()
            dup = db.one(g.conn, "SELECT id, category_id FROM transactions WHERE user_id=? "
                         "AND source='sms' AND dedup_key=? AND is_deleted=0",
                         (uid, dedup_key))
            return {"captured": False, "reason": "duplicate",
                    "id": dup["id"] if dup else None,
                    "needs_category": bool(dup and dup["category_id"] is None)}, 200
        tx = db.one(g.conn, "SELECT category_id FROM transactions WHERE id=?", (result["id"],))
        return {"captured": True, "id": result["id"],
                "merchant": result["resolved_merchant"] or parsed.raw_merchant,
                "decision": result["decision"],
                "needs_category": tx["category_id"] is None}, 200

    @app.post("/device/state")
    def device_state():
        """Native layer reports whether SMS capture is currently permitted, so
        the web UI can show a 'grant access' banner when it isn't."""
        if not device_authorized():
            abort(403)
        perm = request.form.get("sms_permission")
        if perm in ("granted", "denied"):
            db.execute(g.conn, "INSERT OR REPLACE INTO app_state(key, value) "
                       "VALUES ('sms_permission', ?)", (perm,))
            g.conn.commit()
        return {"ok": True}, 200

    # ── Routes: categories & budgets ─────────────────────────────────────
    def _month_start_iso() -> str:
        # Local wall-clock stamped UTC — same domain as stored occurred_at.
        now = dt.datetime.now().replace(tzinfo=dt.timezone.utc)
        return now.replace(day=1, hour=0, minute=0, second=0, microsecond=0).isoformat()

    @app.get("/categories")
    def categories_page():
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        s = settings_for(uid)
        return render_template("categories.html", categories=categories_for(uid, True),
                               spent_by_cat=analytics.month_category_spend(
                                   g.conn, uid, _month_start_iso()),
                               currency=s["currency"],
                               user=current_user(), active="categories")

    @app.post("/categories/<cat_id>/budget")
    def categories_budget(cat_id):
        """Set (or clear, with an empty value) a category's monthly budget."""
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        raw = (request.form.get("budget_amount") or "").strip()
        amount = parse_amount(raw) if raw else None
        if request.form.get("clear") or (amount is not None and amount <= 0):
            amount = None
        db.execute(g.conn, "UPDATE categories SET budget_amount=? WHERE id=? AND user_id=?",
                   (amount, cat_id, uid))
        g.conn.commit()
        return redirect(url_for("categories_page"))

    @app.post("/categories")
    def categories_create():
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        name = (request.form.get("name") or "").strip()
        type_ = request.form.get("type", "expense")
        error = ""
        if not name:
            error = "Enter a category name."
        elif db.one(g.conn, "SELECT id FROM categories WHERE user_id=? AND lower(name)=?",
                    (uid, name.lower())):
            error = "A category with that name already exists."
        else:
            color = request.form.get("color") or "#6366f1"
            if not re.fullmatch(r"#[0-9a-fA-F]{3,8}", color):
                color = "#6366f1"  # colour lands in a style attribute — hex only
            db.execute(g.conn,
                       "INSERT INTO categories(id,user_id,name,type,icon,color) VALUES (?,?,?,?,?,?)",
                       (db.new_id(), uid, name, type_ if type_ in ("income", "expense") else "expense",
                        "Tag", color))
            g.conn.commit()
        s = settings_for(uid)
        return render_template("categories.html", categories=categories_for(uid, True),
                               spent_by_cat=analytics.month_category_spend(
                                   g.conn, uid, _month_start_iso()),
                               currency=s["currency"],
                               user=current_user(), active="categories", error=error,
                               flash="" if error else "Category added.")

    @app.post("/categories/<cat_id>/delete")
    def categories_delete(cat_id):
        """Delete a category — but if transactions still reference it, archive
        instead: a hard delete would orphan category_id everywhere and make
        the donut/report silently disagree with the headline totals."""
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        used = db.one(g.conn, "SELECT COUNT(*) c FROM transactions WHERE user_id=? "
                      "AND category_id=? AND is_deleted=0", (uid, cat_id))["c"]
        if used:
            db.execute(g.conn, "UPDATE categories SET is_archived=1, budget_amount=NULL "
                       "WHERE id=? AND user_id=?", (cat_id, uid))
        else:
            db.execute(g.conn, "DELETE FROM categories WHERE id=? AND user_id=?",
                       (cat_id, uid))
        g.conn.commit()
        return redirect(url_for("categories_page"))

    # ── Routes: fraud ────────────────────────────────────────────────────
    @app.get("/fraud")
    def fraud_page():
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        alerts = db.all_rows(
            g.conn,
            "SELECT a.*, t.merchant_name tx_merchant, t.raw_merchant tx_raw, "
            "t.amount tx_amount, t.occurred_at tx_occurred, t.type tx_type "
            "FROM fraud_alerts a LEFT JOIN transactions t ON t.id = a.transaction_id "
            "WHERE a.user_id=? ORDER BY a.created_at DESC", (uid,))
        return render_template("fraud.html", alerts=alerts, user=current_user(),
                               currency=settings_for(uid)["currency"], active="fraud")

    @app.post("/fraud/<alert_id>/status")
    def fraud_update(alert_id):
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        status = request.form.get("status", "")
        if status in ("open", "dismissed", "resolved"):
            db.execute(g.conn, "UPDATE fraud_alerts SET status=? WHERE id=? AND user_id=?",
                       (status, alert_id, uid))
            g.conn.commit()
        return redirect(url_for("fraud_page"))

    # ── Sender trust registry & phishing quarantine ──────────────────────
    def _sender_row(uid: str, raw_sender) -> Optional[dict]:
        norm = senders.normalize_sender(raw_sender)
        if not norm:
            return None
        row = db.one(g.conn, "SELECT * FROM sms_senders WHERE user_id=? AND sender=?",
                     (uid, norm))
        return dict(row) if row else None

    def _touch_sender(uid: str, raw_sender, assessment) -> None:
        """Record that this sender was seen. Runs for every message, including
        ones the parser could not read, so the registry shows real traffic.

        The stored `trust` is only ever written by the heuristics when the user
        has not made a decision — a user's trust/block must never be silently
        overwritten by a later heuristic verdict.
        """
        norm = assessment.sender.normalized
        if not norm:
            return
        now = dt.datetime.now().isoformat()
        quarantined = 1 if assessment.action == senders.ACTION_QUARANTINE else 0
        db.execute(
            g.conn,
            "INSERT INTO sms_senders(id,user_id,sender,display,kind,entity,bank,trust,"
            "message_count,captured_count,confirmed_count,quarantined_count,last_risk,"
            "first_seen_at,last_seen_at) VALUES (?,?,?,?,?,?,?,?,1,0,0,?,?,?,?) "
            "ON CONFLICT(user_id, sender) DO UPDATE SET "
            "message_count = message_count + 1, "
            "quarantined_count = quarantined_count + excluded.quarantined_count, "
            "last_risk = excluded.last_risk, "
            "last_seen_at = excluded.last_seen_at, "
            "kind = excluded.kind, entity = excluded.entity, bank = excluded.bank, "
            # Heuristic verdicts only fill in a sender the user has not judged.
            "trust = CASE WHEN sms_senders.trust IN ('trusted','blocked') "
            "             THEN sms_senders.trust ELSE excluded.trust END",
            (db.new_id(), uid, norm, (assessment.sender.raw or "")[:32],
             assessment.sender.kind, assessment.sender.entity, assessment.sender.bank,
             assessment.trust, quarantined, assessment.risk, now, now))
        g.conn.commit()

    def _quarantine(uid: str, raw_sender, body: str, parsed, assessment) -> str:
        """Hold a suspicious message in full rather than discarding it."""
        body = (body or "").strip()
        digest = hashlib.sha1(body.encode("utf-8")).hexdigest()
        now = dt.datetime.now().isoformat()
        qid = db.new_id()
        db.execute(
            g.conn,
            "INSERT INTO sms_quarantine(id,user_id,sender,body,body_hash,risk,indicators,"
            "reason,amount,type,raw_merchant,occurred_at,reference_number,status,"
            "seen_count,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'pending',1,?) "
            "ON CONFLICT(user_id, body_hash) DO UPDATE SET "
            "seen_count = seen_count + 1, risk = excluded.risk, "
            "indicators = excluded.indicators, reason = excluded.reason",
            (qid, uid, (raw_sender or "")[:32] or None, body[:800], digest,
             assessment.risk, json.dumps(assessment.indicators),
             senders.explain(assessment), parsed.amount, parsed.type,
             parsed.raw_merchant,
             parsed.occurred_at.isoformat() if parsed.occurred_at else None,
             parsed.reference_number, now))
        g.conn.commit()
        row = db.one(g.conn, "SELECT id FROM sms_quarantine WHERE user_id=? AND body_hash=?",
                     (uid, digest))
        return row["id"] if row else qid

    @app.get("/sms/quarantine")
    def quarantine_page():
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        rows = db.all_rows(
            g.conn,
            "SELECT * FROM sms_quarantine WHERE user_id=? AND status='pending' "
            "ORDER BY created_at DESC LIMIT 200", (uid,))
        items = []
        for r in rows:
            d = dict(r)
            try:
                d["indicator_list"] = json.loads(d.get("indicators") or "[]")
            except ValueError:
                d["indicator_list"] = []
            items.append(d)
        sender_rows = db.all_rows(
            g.conn,
            "SELECT * FROM sms_senders WHERE user_id=? "
            "ORDER BY (trust='blocked') DESC, message_count DESC LIMIT 200", (uid,))
        return render_template("quarantine.html", items=items,
                               senders=[dict(r) for r in sender_rows],
                               user=current_user(),
                               currency=settings_for(uid)["currency"],
                               active="quarantine")

    @app.post("/sms/quarantine/<qid>")
    def quarantine_resolve(qid):
        """Approve a held message into the ledger, or reject it.

        Approving also trusts the sender — the user has now vouched for it
        with full sight of the indicators, which is better evidence than any
        heuristic this app can run.
        """
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        action = request.form.get("action", "")
        row = db.one(g.conn, "SELECT * FROM sms_quarantine WHERE id=? AND user_id=?",
                     (qid, uid))
        if not row:
            return redirect(url_for("quarantine_page"))
        now = dt.datetime.now().isoformat()
        norm = senders.normalize_sender(row["sender"])

        if action == "approve" and row["amount"]:
            occ = None
            if row["occurred_at"]:
                try:
                    occ = dt.datetime.fromisoformat(row["occurred_at"])
                except ValueError:
                    occ = None
            dedup_key = row["reference_number"] or row["body_hash"]
            try:
                create_transaction(
                    uid, amount=float(row["amount"]), type_=row["type"] or "expense",
                    raw_merchant=row["raw_merchant"],
                    reference_number=row["reference_number"], occurred_at=occ,
                    source="sms", resolve=True, dedup_key=dedup_key,
                    sms_body=(row["body"] or "")[:400],
                    sms_sender=(row["sender"] or "")[:32] or None)
            except sqlite3.IntegrityError:
                g.conn.rollback()   # already captured by another path
            if norm:
                db.execute(g.conn, "UPDATE sms_senders SET trust='trusted', "
                           "confirmed_count = confirmed_count + 1, "
                           "captured_count = captured_count + 1 "
                           "WHERE user_id=? AND sender=?", (uid, norm))
            db.execute(g.conn, "UPDATE sms_quarantine SET status='approved', "
                       "resolved_at=? WHERE id=?", (now, qid))
        elif action in ("reject", "reject_block"):
            db.execute(g.conn, "UPDATE sms_quarantine SET status='rejected', "
                       "resolved_at=? WHERE id=?", (now, qid))
            if action == "reject_block" and norm:
                db.execute(g.conn, "UPDATE sms_senders SET trust='blocked' "
                           "WHERE user_id=? AND sender=?", (uid, norm))
        g.conn.commit()
        return redirect(url_for("quarantine_page"))

    @app.post("/sms/senders/<sid>")
    def sender_trust_update(sid):
        """Manual override of a sender's trust. The user always wins."""
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        trust = request.form.get("trust", "")
        if trust in (senders.TRUST_TRUSTED, senders.TRUST_BLOCKED, senders.TRUST_UNKNOWN):
            db.execute(g.conn, "UPDATE sms_senders SET trust=? WHERE id=? AND user_id=?",
                       (trust, sid, uid))
            g.conn.commit()
        return redirect(url_for("quarantine_page"))

    def _record_parse_miss(uid: str, body: str, sender, reason: str) -> None:
        """Local-only log of unreadable bank messages (see /sms/misses).

        Only messages that already looked financial reach ingest, so this stays
        small. Deduplicated by content hash with a seen counter so a repeated
        format shows its true frequency.
        """
        body = (body or "").strip()
        if not body:
            return
        digest = hashlib.sha1(body.encode("utf-8")).hexdigest()
        now = dt.datetime.now().isoformat()
        try:
            db.execute(
                g.conn,
                "INSERT INTO parse_misses(id,user_id,sender,body,body_hash,reason,"
                "parser_version,seen_count,first_seen_at,last_seen_at) "
                "VALUES (?,?,?,?,?,?,?,1,?,?) "
                "ON CONFLICT(user_id, body_hash) DO UPDATE SET "
                "seen_count = seen_count + 1, last_seen_at = excluded.last_seen_at, "
                "parser_version = excluded.parser_version",
                (db.new_id(), uid, (sender or "")[:32] or None, body[:600], digest,
                 reason, parsing_version(), now, now))
            g.conn.commit()
        except sqlite3.DatabaseError:
            pass          # diagnostics must never break capture

    def _csv_cell(v):
        """Neutralise spreadsheet formula injection (=, +, -, @ prefixes)."""
        text = "" if v is None else str(v)
        return "'" + text if text[:1] in ("=", "+", "-", "@") else text

    def sms_status(uid: str) -> dict:
        """Diagnostics for the auto-capture pipeline, so the user can SEE
        whether SMS capture is working instead of guessing."""
        row = db.one(g.conn, "SELECT COUNT(*) c, MAX(created_at) last FROM transactions "
                     "WHERE user_id=? AND source='sms' AND is_deleted=0", (uid,))
        st = db.one(g.conn, "SELECT value FROM app_state WHERE key='sms_permission'")
        perm = st["value"] if st else None
        # The offline queue lives beside the database in the app's files dir.
        queued = 0
        try:
            qpath = os.path.join(os.path.dirname(os.path.abspath(app.config["DB_PATH"])),
                                 "sms_inbox.jsonl")
            if os.path.exists(qpath):
                with open(qpath, "r", encoding="utf-8") as f:
                    queued = sum(1 for line in f if line.strip())
        except OSError:
            queued = 0
        try:
            misses = db.one(g.conn, "SELECT COUNT(*) c, COALESCE(SUM(seen_count),0) n "
                            "FROM parse_misses WHERE user_id=?", (uid,))
            miss_kinds, miss_total = misses["c"], misses["n"]
        except sqlite3.DatabaseError:
            miss_kinds, miss_total = 0, 0
        unreviewed = db.one(
            g.conn, "SELECT COUNT(*) c FROM transactions WHERE user_id=? AND source='sms' "
            "AND is_deleted=0 AND status IN (?,?) AND category_id IS NULL",
            (uid, TX_PENDING, TX_REVIEW))["c"]
        return {"captured": row["c"], "last": row["last"], "permission": perm,
                "queued": queued, "unreviewed": unreviewed,
                "miss_kinds": miss_kinds, "miss_total": miss_total}

    # ── Routes: profile & settings ───────────────────────────────────────
    @app.post("/profile")
    def profile_update():
        """Set/change the display name (used to personalise the greeting)."""
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        name = (request.form.get("full_name") or "").strip()[:40]
        if name:
            db.execute(g.conn, "UPDATE users SET full_name=? WHERE id=?", (name, uid))
            g.conn.commit()
        return redirect(request.referrer or url_for("dashboard"))

    # ── Routes: settings ─────────────────────────────────────────────────
    @app.route("/settings", methods=["GET", "POST"])
    def settings_page():
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        flash = ""
        if request.method == "POST":
            hv = parse_amount(request.form.get("high_value_amount", "")) \
                if (request.form.get("high_value_amount") or "").strip() else None
            theme = request.form.get("theme", "system")

            def _pct(field, default):
                try:
                    return max(0, min(100, int(request.form.get(field) or default)))
                except (TypeError, ValueError):
                    return default

            db.execute(g.conn,
                       "UPDATE settings SET currency=?, theme=?, auto_save_threshold=?, "
                       "confirm_threshold=?, high_value_amount=? WHERE user_id=?",
                       ((request.form.get("currency") or "INR")[:8],
                        theme if theme in ("system", "light", "dark") else "system",
                        _pct("auto_save_threshold", 80), _pct("confirm_threshold", 50),
                        hv, uid))
            g.conn.commit()
            flash = "Settings saved."
        return render_template("settings.html", s=settings_for(uid, fresh=True),
                               user=current_user(), active="settings", flash=flash,
                               sms=sms_status(uid))

    # ── Routes: data export ──────────────────────────────────────────────
    @app.get("/export.csv")
    def export_csv():
        """Download every (non-deleted) transaction as a CSV file."""
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        rows = db.all_rows(
            g.conn,
            "SELECT t.*, c.name category_name FROM transactions t "
            "LEFT JOIN categories c ON c.id = t.category_id "
            "WHERE t.user_id=? AND t.is_deleted=0 ORDER BY t.occurred_at DESC", (uid,))
        buf = io.StringIO()
        w = csv.writer(buf)
        w.writerow(["date", "type", "amount", "merchant", "category", "notes",
                    "reference", "source", "status"])
        for r in rows:
            w.writerow([r["occurred_at"], r["type"], r["amount"],
                        _csv_cell(r["merchant_name"] or r["raw_merchant"]),
                        _csv_cell(r["category_name"]), _csv_cell(r["notes"]),
                        _csv_cell(r["reference_number"]), r["source"], r["status"]])
        return Response(buf.getvalue(), mimetype="text/csv", headers={
            "Content-Disposition": "attachment; filename=spendwise-transactions.csv"})

    @app.get("/sms/misses.csv")
    def export_parse_misses():
        """Export unreadable bank messages so a parser gap can be diagnosed.

        Explicit user action, local file — consistent with zero telemetry:
        nothing is ever transmitted automatically.
        """
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        try:
            rows = db.all_rows(
                g.conn, "SELECT sender, body, reason, parser_version, seen_count, "
                "first_seen_at, last_seen_at FROM parse_misses WHERE user_id=? "
                "ORDER BY seen_count DESC", (uid,))
        except sqlite3.DatabaseError:
            rows = []
        buf = io.StringIO()
        w = csv.writer(buf)
        w.writerow(["sender", "body", "reason", "parser_version", "seen_count",
                    "first_seen_at", "last_seen_at"])
        for r in rows:
            w.writerow([_csv_cell(r["sender"]), _csv_cell(r["body"]), r["reason"],
                        r["parser_version"], r["seen_count"], r["first_seen_at"],
                        r["last_seen_at"]])
        return Response(buf.getvalue(), mimetype="text/csv", headers={
            "Content-Disposition": "attachment; filename=spendwise-parse-misses.csv"})

    @app.get("/healthz")
    def healthz():
        return {"status": "ok", "single_user": app.config["SINGLE_USER"]}

    return app
