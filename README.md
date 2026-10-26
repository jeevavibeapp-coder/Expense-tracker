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

**If you are in India, Singapore, Thailand or Brazil, download
`app-NOSMS-installs-anywhere`.** Everywhere else, try the full build first.

| Artifact | SMS auto-capture | Play Protect |
|---|---|---|
| `app-NOSMS-installs-anywhere` | no | installs normally |
| `app-FULL-sms-autocapture` | yes | may be blocked — see below |
| `app-debug-for-adb-only` | yes | blocked; `adb install` only |

Both release builds are signed with the same key and the same
`applicationId`, so you can start on the no-SMS build and move to the full
one later **without losing your ledger**.

### Play Protect blocks the full build

Play Protect refuses to sideload any app that requests `READ_SMS` or
`RECEIVE_SMS`:

```
INSTALL_FAILED_VERIFICATION_FAILURE: Install not allowed for file:///data/app/...
```

It is reacting to the permission being present in the manifest. **Signing,
the `debuggable` flag and app behaviour make no difference** — a correctly
signed, non-debuggable release is blocked exactly the same. Under Google's
enhanced fraud protection (India, Singapore, Thailand, Brazil) there is often
no "install anyway" override at all.

Three ways round it, most reliable first:

1. **Install `app-NOSMS-installs-anywhere`.** No SMS permission, nothing for
   Play Protect to object to. You lose automatic capture; paste bank messages
   into the Import screen instead. Everything else is identical.
2. **`adb install app-sms-release.apk`** from a computer. This skips the
   installer-side check entirely and is the only reliable way to get the full
   build onto a device in the affected countries.
3. **Turn Play Protect scanning off**, install, turn it back on: Play Store →
   profile picture → Play Protect → gear → *Scan apps with Play Protect*. On
   Xiaomi/Redmi/POCO also check Settings → Privacy protection → Special
   permissions → Install unknown apps, and turn off **MIUI optimization** in
   Developer options. This does not work where enhanced fraud protection is
   active.

The no-SMS build reports its state to the app as `unavailable` rather than
`denied`, so it explains itself instead of asking you to grant a permission
it never requests.

### Why Play Store expense trackers install fine and this one does not

Two separate mechanisms, and conflating them wastes days.

**1. The block is a *sideloading* check, not a verdict on the app.** Google's
enhanced fraud protection (India, Singapore, Thailand, Brazil) inspects apps
arriving from "Internet-sideloading sources" and refuses any that declare
`RECEIVE_SMS`, `READ_SMS`, notification-listener binding, or accessibility
services. Apps installed *from the Play Store* never go through it. Money
View, Axio and the rest are not built differently — they arrive by a route
that is not scanned.

Note the four permissions: **swapping SMS reading for a
`NotificationListenerService` is blocked by exactly the same check.** It is
not a way out of sideloading, only out of the Play Store policy below.

**2. Getting onto the Play Store with `READ_SMS` is the hard part.** SMS and
Call Log permissions are limited to a fixed list of use cases — default SMS
handler, default phone handler, and similar. Reading bank SMS for expense
tracking is not on it, and developers report rejection on the grounds that
manual entry is an available alternative. The established Indian apps predate
the 2019 policy or hold negotiated approvals; a new tracker submitting today
should expect rejection.

So the realistic options are, in order of how well they work:

| Route | Auto-capture | Works in India |
|---|---|---|
| `adb install` from a computer | yes | yes — ADB is not subject to the block |
| Shizuku + an ADB-privileged installer | yes | yes, without a computer each time |
| `app-NOSMS-installs-anywhere` | no | yes |
| F-Droid | yes | still a sideload, so still blocked |
| Play Store | no | requires dropping SMS entirely |

F-Droid is where comparable open-source trackers live (Cashiro, PennyWise AI)
— it solves the *policy* problem, not the Play Protect one.

The `SMS User Consent API` needs no permission at all and is therefore never
blocked, but it asks the user to approve **one message at a time** and only
while the app is actively listening. For continuous capture that is worse
than pasting into the Import screen.

Python tests run in `python-app-ci.yml` (`pytest`, 460 tests), plus
`tests/use_cases.py` (14 end-to-end journeys) and `tests/browser_qa.py`.

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
