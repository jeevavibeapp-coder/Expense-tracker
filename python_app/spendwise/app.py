"""SpendWise Flask application — full server-rendered app (offline-capable).

Wires the merchant engine, learning, SMS parsing, fraud detection and analytics
into a complete UI. Runs as a normal web server and, via Chaquopy, embedded
inside the Android APK (single-user mode).
"""
from __future__ import annotations

import datetime as dt
import os
from typing import Optional

from flask import (
    Flask, abort, g, redirect, render_template, request, session, url_for,
)

from . import analytics, auth, db, engine, fraud
from .parsing import parse_sms

TX_CONFIRMED, TX_PENDING, TX_REVIEW = "confirmed", "pending_confirmation", "needs_review"


def create_app(db_path: Optional[str] = None, single_user: bool = False,
               secret_key: Optional[str] = None) -> Flask:
    app = Flask(__name__)
    app.config["DB_PATH"] = db_path or os.environ.get("SPENDWISE_DB", "spendwise.db")
    app.config["SINGLE_USER"] = single_user or os.environ.get("SPENDWISE_SINGLE_USER") == "1"
    app.secret_key = (secret_key or os.environ.get("SPENDWISE_SECRET")
                      or os.urandom(32).hex())

    # Initialise the schema once at startup.
    init_conn = db.connect(app.config["DB_PATH"])
    db.init_db(init_conn)
    init_conn.close()

    # ── Request lifecycle ────────────────────────────────────────────────
    @app.before_request
    def _open_db():
        g.conn = db.connect(app.config["DB_PATH"])
        if app.config["SINGLE_USER"] and "user_id" not in session:
            session["user_id"] = auth.ensure_local_user(g.conn)

    @app.teardown_request
    def _close_db(exc):
        conn = g.pop("conn", None)
        if conn is not None:
            conn.close()

    # ── Helpers ──────────────────────────────────────────────────────────
    def current_user():
        uid = session.get("user_id")
        if not uid:
            return None
        return auth.get_user(g.conn, uid)

    def settings_for(uid: str) -> dict:
        row = db.one(g.conn, "SELECT * FROM settings WHERE user_id=?", (uid,))
        if row is None:
            db.execute(g.conn, "INSERT INTO settings(user_id) VALUES (?)", (uid,))
            g.conn.commit()
            row = db.one(g.conn, "SELECT * FROM settings WHERE user_id=?", (uid,))
        return dict(row)

    def categories_for(uid: str, include_archived: bool = False):
        sql = "SELECT * FROM categories WHERE user_id=?"
        if not include_archived:
            sql += " AND is_archived=0"
        return db.all_rows(g.conn, sql + " ORDER BY name", (uid,))

    def parse_amount(raw: str) -> Optional[float]:
        try:
            return round(float(raw), 2)
        except (TypeError, ValueError):
            return None

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

    app.jinja_env.globals["now"] = lambda: dt.datetime.now(dt.timezone.utc)

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

    app.jinja_env.filters["initials"] = _initials
    app.jinja_env.filters["avatar_color"] = _avatar_color

    @app.context_processor
    def _inject_globals():
        theme, nav_fraud, nav_review = "system", 0, 0
        uid = session.get("user_id")
        if uid:
            row = db.one(g.conn, "SELECT theme FROM settings WHERE user_id=?", (uid,))
            if row:
                theme = row["theme"]
            nav_fraud = db.one(g.conn, "SELECT COUNT(*) c FROM fraud_alerts WHERE "
                               "user_id=? AND status='open'", (uid,))["c"]
            nav_review = db.one(g.conn, "SELECT COUNT(*) c FROM transactions WHERE "
                                "user_id=? AND is_deleted=0 AND status IN "
                                "('pending_confirmation','needs_review')", (uid,))["c"]
        return {"app_name": "SpendWise", "single_user": app.config["SINGLE_USER"],
                "theme": theme, "nav_fraud": nav_fraud, "nav_review": nav_review}

    def require_login():
        if not session.get("user_id") or current_user() is None:
            return None
        return session["user_id"]

    # ── Transaction orchestration ────────────────────────────────────────
    def create_transaction(uid: str, *, amount: float, type_: str, category_id=None,
                           merchant=None, raw_merchant=None, notes=None,
                           reference_number=None, occurred_at: Optional[dt.datetime] = None,
                           source="manual", resolve=True) -> dict:
        s = settings_for(uid)
        occ = occurred_at or dt.datetime.now(dt.timezone.utc)
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
                                 auto=s["auto_save_threshold"], confirm=s["confirm_threshold"])
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

        db.execute(g.conn,
                   "INSERT INTO transactions(id,user_id,amount,type,category_id,raw_merchant,"
                   "merchant_id,merchant_name,notes,reference_number,occurred_at,source,"
                   "confidence,status,is_deleted,created_at) VALUES "
                   "(?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?)",
                   (tx_id, uid, amount, type_, category_id, raw, merchant_id, merchant_name,
                    notes, reference_number, occ.isoformat(), source, confidence, status,
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

    # ── Routes: transactions ─────────────────────────────────────────────
    @app.get("/transactions")
    def transactions():
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        q = (request.args.get("q") or "").strip()
        sql = ("SELECT * FROM transactions WHERE user_id=? AND is_deleted=0")
        params = [uid]
        if q:
            like = f"%{q.lower()}%"
            sql += (" AND (lower(COALESCE(merchant_name,'')) LIKE ? OR "
                    "lower(COALESCE(raw_merchant,'')) LIKE ? OR "
                    "lower(COALESCE(notes,'')) LIKE ? OR "
                    "lower(COALESCE(reference_number,'')) LIKE ?)")
            params += [like, like, like, like]
        sql += " ORDER BY occurred_at DESC, created_at DESC LIMIT 200"
        rows = db.all_rows(g.conn, sql, tuple(params))
        return render_template("transactions.html", transactions=rows, total=len(rows),
                               q=q, categories=categories_for(uid), user=current_user(),
                               active="transactions")

    @app.post("/transactions")
    def transactions_create():
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        amount = parse_amount(request.form.get("amount", ""))
        if amount is None or amount < 0:
            return redirect(url_for("transactions"))
        create_transaction(
            uid, amount=amount, type_=request.form.get("type", "expense"),
            category_id=request.form.get("category_id") or None,
            merchant=request.form.get("merchant", ""), notes=request.form.get("notes", "") or None,
            occurred_at=parse_date(request.form.get("occurred_at", "")), source="manual")
        return redirect(url_for("transactions"))

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
                             auto=s["auto_save_threshold"], confirm=s["confirm_threshold"])
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
        return redirect(url_for("transactions"))

    @app.post("/transactions/<tx_id>/delete")
    def transactions_delete(tx_id):
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        db.execute(g.conn, "UPDATE transactions SET is_deleted=1 WHERE id=? AND user_id=?",
                   (tx_id, uid))
        g.conn.commit()
        return redirect(url_for("transactions"))

    # ── Routes: SMS import ───────────────────────────────────────────────
    @app.get("/import")
    def import_page():
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        return render_template("import.html", user=current_user(), active="import")

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
                                 auto=s["auto_save_threshold"], confirm=s["confirm_threshold"])
            preview = {"best": res["best"], "decision": res["decision"],
                       "breakdown": res["best"]["breakdown"] if res["best"] else None}
        return render_template("_import_preview.html", parsed=parsed, preview=preview)

    @app.post("/import/create")
    def import_create():
        uid = require_login()
        if not uid:
            abort(401)
        amount = parse_amount(request.form.get("amount", ""))
        if amount is None:
            return '<p class="error">Could not read the amount.</p>'
        result = create_transaction(
            uid, amount=amount, type_=request.form.get("type", "expense"),
            raw_merchant=request.form.get("raw_merchant", ""),
            reference_number=request.form.get("reference_number", "") or None,
            occurred_at=parse_date(request.form.get("occurred_at", "")),
            source="sms", resolve=True)
        return render_template("_import_result.html", result=result,
                               breakdown=result["breakdown"])

    # ── Routes: categories ───────────────────────────────────────────────
    @app.get("/categories")
    def categories_page():
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        return render_template("categories.html", categories=categories_for(uid, True),
                               user=current_user(), active="categories")

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
            db.execute(g.conn,
                       "INSERT INTO categories(id,user_id,name,type,icon,color) VALUES (?,?,?,?,?,?)",
                       (db.new_id(), uid, name, type_ if type_ in ("income", "expense") else "expense",
                        "Tag", (request.form.get("color") or "#6366f1")[:16]))
            g.conn.commit()
        return render_template("categories.html", categories=categories_for(uid, True),
                               user=current_user(), active="categories", error=error,
                               flash="" if error else "Category added.")

    @app.post("/categories/<cat_id>/delete")
    def categories_delete(cat_id):
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        db.execute(g.conn, "DELETE FROM categories WHERE id=? AND user_id=?", (cat_id, uid))
        g.conn.commit()
        return redirect(url_for("categories_page"))

    # ── Routes: fraud ────────────────────────────────────────────────────
    @app.get("/fraud")
    def fraud_page():
        uid = require_login()
        if not uid:
            return redirect(url_for("login"))
        alerts = db.all_rows(g.conn,
                             "SELECT * FROM fraud_alerts WHERE user_id=? ORDER BY created_at DESC",
                             (uid,))
        return render_template("fraud.html", alerts=alerts, user=current_user(), active="fraud")

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
            db.execute(g.conn,
                       "UPDATE settings SET currency=?, theme=?, auto_save_threshold=?, "
                       "confirm_threshold=?, high_value_amount=? WHERE user_id=?",
                       ((request.form.get("currency") or "INR")[:8],
                        theme if theme in ("system", "light", "dark") else "system",
                        max(0, min(100, int(request.form.get("auto_save_threshold", 80) or 80))),
                        max(0, min(100, int(request.form.get("confirm_threshold", 50) or 50))),
                        hv, uid))
            g.conn.commit()
            flash = "Settings saved."
        return render_template("settings.html", s=settings_for(uid), user=current_user(),
                               active="settings", flash=flash)

    @app.get("/healthz")
    def healthz():
        return {"status": "ok", "single_user": app.config["SINGLE_USER"]}

    return app
