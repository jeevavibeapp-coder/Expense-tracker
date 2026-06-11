# SpendWise — Python app (embeddable in the Android APK)

A complete, self-contained **Python** expense tracker: Flask + Jinja2 +
standard-library `sqlite3`, with the same smart merchant-resolution engine,
confidence scoring, learning, SMS parsing, fraud detection and analytics as the
FastAPI service — but with **pure-Python dependencies only** so it can be
embedded inside the Android APK via **Chaquopy** and run fully offline.

## Why a second Python app?

The FastAPI edition (`/backend`) is the cloud/server build. It depends on
`pydantic-core` (Rust) and `bcrypt` (C), which Chaquopy cannot build for
Android. This edition uses only pure-Python packages (just Flask) and stdlib
`sqlite3`, so the **whole app runs on-device** inside the APK's WebView.

## Run it as a normal web app

```bash
cd python_app
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
python run.py            # http://localhost:5000
# Single-user (no login, like the mobile build):
SPENDWISE_SINGLE_USER=1 python run.py
```

## Test

```bash
cd python_app
pip install -r requirements.txt pytest
pytest -q                # 21 tests, in-memory/temp SQLite, no services needed
```

## How it ships in the APK

The existing Capacitor Android project embeds this app with Chaquopy:

1. `android/app/build.gradle` applies `com.chaquo.python`, installs `Flask`,
   and points Chaquopy's Python source set at `../../python_app`.
2. `MainActivity` starts the Python interpreter, calls
   `spendwise.android_entry.start_server(filesDir)` on a background thread
   (Flask on `127.0.0.1:8765`), waits until it responds, then loads it in the
   Capacitor WebView. Fully offline — no server required.

See `android/CHAQUOPY_NOTES.md` for the version matrix and rationale.

## Feature parity

Auth (PBKDF2, session; auto local user in single-user mode), dashboard +
insights, transactions with live confidence preview and inline merchant
confirmation, SMS import (decision + confidence breakdown), categories, fraud
alerts, settings. All analytics come from the user's own data — no mock data.

## Layout

```
python_app/
  spendwise/
    app.py            # Flask factory + routes + transaction orchestration
    engine.py         # merchant resolution + confidence + learning
    parsing.py        # SMS/UPI parser
    fraud.py          # anomaly detection
    analytics.py      # dashboard aggregation
    auth.py           # PBKDF2 hashing + accounts
    db.py             # sqlite schema + helpers
    android_entry.py  # Chaquopy entry point (start_server)
    templates/ static/
  tests/              # pytest suite
  run.py              # dev/server entry point
```
