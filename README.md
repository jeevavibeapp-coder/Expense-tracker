# SpendWise

**A mobile-first personal finance app for Indian UPI users.** Bank and UPI SMS
are captured automatically the moment they arrive, resolved to real merchants
by a learning engine, and turned into budgets, reports, streaks and fraud
alerts — fully offline, entirely on-device.

The core value is **Merchant Identity Resolution + Smart Expense
Intelligence**: `"UPI-SWIGGY8098XXX"` becomes *Swiggy · Food & Dining* without
the user typing anything, and the engine gets better with every confirmation.

---

## Architecture

```
┌──────────────────────────── Android APK ────────────────────────────┐
│                                                                     │
│  MainActivity (Capacitor BridgeActivity)                            │
│    · edge-to-edge WebView (insets incl. keyboard)                   │
│    · starts embedded Python off the UI thread (Chaquopy)            │
│    · back gesture walks WebView history (loopback entries only)     │
│    · per-install device token in SharedPreferences                  │
│                                                                     │
│  SmsReceiver (BroadcastReceiver, goAsync)                           │
│    · pre-filters finance SMS (multipart-safe, CDMA format arg)      │
│    · POST 127.0.0.1:8765/sms/ingest  +  X-SpendWise-Token           │
│    · offline queue: filesDir/sms_inbox.jsonl (drained on launch)    │
│                                                                     │
│  Chaquopy → Python 3.11 → Flask (Werkzeug @ 127.0.0.1:8765)         │
│  ┌──────────────────── python_app/spendwise ─────────────────────┐  │
│  │ app.py        routes, device auth, CSRF origin guard          │  │
│  │ parsing.py    Indian bank/UPI SMS → amount/merchant/ref/date  │  │
│  │ engine.py     weighted merchant resolution + learning         │  │
│  │ analytics.py  dashboard, report, budgets, streaks, recurring  │  │
│  │ fraud.py      duplicate / high-value / anomaly alerts         │  │
│  │ db.py         SQLite (WAL, busy_timeout, additive migrations) │  │
│  │ templates/    server-rendered mobile UI (CRED-grade dark)     │  │
│  │ static/       styles.css + app.js (no frameworks, no CDN)     │  │
│  └────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────┘
```

Everything is served from loopback inside the APK. There is **no cloud, no
account, no network egress**; `network_security_config.xml` permits cleartext
only to `127.0.0.1`.

### The SMS pipeline

1. SMS arrives → `SmsReceiver` filters likely finance messages.
2. Live POST to `/sms/ingest` with the device token; if the server is down
   (app closed), the message is queued to `sms_inbox.jsonl`.
3. `android_entry._drain_inbox()` atomically rotates and replays the queue on
   every launch — nothing is lost, nothing is double-counted (dedup below).
4. `/sms/ingest` parses, resolves the merchant, and creates the transaction.
   Idempotency: bank reference number, or a content hash when there is none,
   enforced by a **unique partial index** on `(user_id, dedup_key)`.
5. If the merchant has no learned category, the app shows a one-tap
   category popup on next open; the choice teaches the engine.

### The merchant engine (`engine.py`)

Confidence is a weighted score out of 100:

| Signal              | Weight |
|---------------------|--------|
| Past mapping        | 40     |
| Amount pattern      | 20     |
| Category pattern    | 15     |
| Correction history  | 15     |
| Time-of-day pattern | 10     |

Thresholds (user-tunable in Settings): auto-save ≥ 80, ask-to-confirm ≥ 50,
else needs-review. Confirmations and corrections update the learning table;
corrected names are penalised.

---

## Database (SQLite)

| Table          | Purpose                                                        |
|----------------|----------------------------------------------------------------|
| `users`        | one auto-provisioned local user on device (multi-user on web) |
| `categories`   | name/type/color + `budget_amount` (monthly budget)            |
| `merchants`    | canonical merchant names + learned default category           |
| `learning`     | per (raw_name, merchant) stats: counts, amounts, hour histogram |
| `transactions` | amount/type/category/merchant/raw/ref/`dedup_key`/status/source |
| `fraud_alerts` | duplicate, high-value, anomaly alerts with status             |
| `settings`     | currency, theme, engine thresholds, high-value limit          |
| `app_state`    | persisted secret key, SMS permission state                    |

Migrations are additive (`db._migrate`) so old on-device databases upgrade in
place. Key indexes: `(user_id, occurred_at)` and the unique partial dedup
index.

---

## HTTP surface (server-rendered; JSON only where noted)

| Route | Purpose |
|---|---|
| `GET /dashboard` | balance hero, health, tiles, streak, sparkline, bills, budgets, donut, merchants, trend |
| `GET/POST /transactions` (+ `/edit` `/delete` `/restore` `/confirm` `/categorize` `/resolve`) | activity timeline, search, filter chips, inline edit, undo delete, merchant confirmation |
| `GET /report?m=YYYY-MM` | monthly report: totals, vs-last-month (same-days), day bars, category deltas |
| `GET /merchant?n=` | merchant drill-down: totals, trend, history |
| `GET/POST /categories` (+ `/budget` `/delete`) | budgets screen, per-category monthly limits |
| `GET /import` + `POST /import/parse` `/import/create` | manual SMS paste flow + recent auto-captures |
| `GET /fraud` + `POST /fraud/<id>/status` | alerts inbox |
| `GET/POST /settings`, `POST /profile` | preferences, engine thresholds, display name |
| `GET /export.csv` | full transaction export (formula-injection safe) |
| `POST /sms/ingest`, `POST /device/state` (JSON) | device-only: loopback + token required |
| `GET /healthz` (JSON) | native layer readiness poll |

Security: per-install device token (HMAC-compared) on device endpoints,
cross-origin POST rejection, hex-validated colours, PBKDF2 password hashing
(web mode), sessions with a persisted secret so restarts don't log out.

---

## Building

CI (`.github/workflows/android.yml`) builds on every push: Node 22 → JDK 21 +
Android SDK 36 → Python 3.11 for Chaquopy → `npx cap sync android &&
./gradlew assembleDebug assembleRelease`.

### Which APK to install

**Download `app-release-INSTALL-THIS`.**

| Artifact | Signed | Debuggable | Sideload it? |
|---|---|---|---|
| `app-release-INSTALL-THIS` | yes | no | **yes** |
| `app-debug-for-adb-only` | yes | **yes** | no — see below |

The release APK is always signed: with the `SPENDWISE_*` repository secrets
when they exist, and with the committed stable key otherwise. It is never
emitted unsigned, and CI fails the build if it ever is.

Do not sideload the debug APK. It is built `debuggable="true"`, and a
debuggable app that requests `READ_SMS` is the profile Play Protect and MIUI
block hardest:

```
INSTALL_FAILED_VERIFICATION_FAILURE: Install not allowed for file:///data/app/...
```

It is kept only for `adb install` during development.

### If Play Protect blocks the install

SpendWise reads bank SMS, and Play Protect warns about **any** sideloaded app
requesting SMS access — "This app can request access to sensitive data." It is
reacting to the permission being requested, not to anything found in the app.

Play Store → your profile picture → Play Protect → gear icon → turn off
**Scan apps with Play Protect** → install → **turn it back on**.

On Xiaomi/Redmi/POCO, also turn off Settings → Privacy protection → **Special
permissions** → Install unknown apps → scan restrictions, and disable **MIUI
optimization** in Developer options if the install still fails.

From a computer, `adb install app-release.apk` skips the prompt entirely.

### This app cannot go on the Play Store as built

Google restricts `READ_SMS` / `RECEIVE_SMS` to apps whose core function is SMS
— default SMS handlers and similar. A finance app that reads bank messages is
the category Google removed en masse in 2019, so a Play listing would be
rejected. The alternatives are sideloading (this repo), F-Droid, or replacing
SMS reading with a `NotificationListenerService` that reads bank
notifications, which falls outside that policy. That last one is an
architecture change, not a configuration switch.

Python tests run in `python-app-ci.yml` (`pytest`, 457 tests).

### Local development

```bash
cd python_app
pip install -r requirements.txt pytest
python -m flask --app "spendwise.app:create_app()" run   # web mode with login
SPENDWISE_SINGLE_USER=1 python -m flask --app "spendwise.app:create_app()" run  # device mode
pytest -q
```

The web app is pure Flask + Jinja + vanilla JS — any change is testable in a
desktop browser at mobile viewport before building the APK.

### Release signing

`android/app/spendwise.jks` is a committed **debug-only** keystore. Debug
signing material is not a secret by design — Android ships a world-known
debug keystore — and keeping a stable one means sideloaded debug builds
update over an existing install instead of forcing an uninstall, which would
destroy the user's on-device financial history.

Release builds are signed only when all four environment variables are set,
and are left unsigned otherwise rather than silently falling back to a key
that is public:

| Secret | Meaning |
|---|---|
| `SPENDWISE_KEYSTORE_FILE` | path to the keystore inside the checkout |
| `SPENDWISE_KEYSTORE_PASSWORD` | store password |
| `SPENDWISE_KEY_ALIAS` | key alias |
| `SPENDWISE_KEY_PASSWORD` | key password |

To produce a signed release, generate a key and add these as repository
secrets:

```bash
keytool -genkeypair -v -keystore release.jks -alias spendwise \
        -keyalg RSA -keysize 4096 -validity 10000
```

Keep that keystore. Losing it means never being able to update the app for
anyone who installed it.

---

## Repository layout

```
python_app/        the product (Flask app + tests)
android/           Capacitor + Chaquopy shell (embeds python_app)
App.tsx, dist/…    legacy React prototype kept only as the WebView boot splash
.github/workflows  APK build + Python CI
```
