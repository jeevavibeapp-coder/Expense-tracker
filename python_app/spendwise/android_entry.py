"""Entry point used by the Android app (Chaquopy) to run SpendWise on-device.

The Android `MainActivity` starts a background thread that calls
``start_server(files_dir)``; this launches the Flask app on 127.0.0.1 so the
Capacitor WebView can load it. Everything runs fully offline inside the APK.
"""
from __future__ import annotations

import json
import os
import threading
import time
import urllib.parse
import urllib.request

_server_thread: "threading.Thread | None" = None
_lock = threading.Lock()

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8765

# Finance SMS that arrived while the app was closed are appended here by the
# Android SMS receiver, then drained into the app on the next launch.
INBOX_NAME = "sms_inbox.jsonl"


def _run(db_path: str, host: str, port: int) -> None:
    from spendwise.app import create_app

    app = create_app(db_path=db_path, single_user=True)
    # Werkzeug's dev server is sufficient for a single on-device user.
    app.run(host=host, port=port, threaded=True, use_reloader=False, debug=False)


def _drain_inbox(base: str, host: str, port: int) -> None:
    """Wait for the server, then replay any SMS queued while it was offline."""
    path = os.path.join(base, INBOX_NAME)
    base_url = f"http://{host}:{port}"
    # Wait (up to ~30s) for the server to accept requests.
    for _ in range(120):
        try:
            urllib.request.urlopen(f"{base_url}/healthz", timeout=1)
            break
        except Exception:
            time.sleep(0.25)
    if not os.path.exists(path):
        return
    try:
        with open(path, "r", encoding="utf-8") as f:
            lines = f.readlines()
    except OSError:
        return
    remaining = []
    for line in lines:
        line = line.strip()
        if not line:
            continue
        try:
            item = json.loads(line)
        except ValueError:
            continue  # drop unparseable lines
        try:
            data = urllib.parse.urlencode(
                {"sender": item.get("sender") or "", "body": item.get("body") or ""}).encode()
            urllib.request.urlopen(urllib.request.Request(f"{base_url}/sms/ingest", data=data),
                                   timeout=5)
        except Exception:
            remaining.append(line)  # keep for the next launch on failure
    try:
        if remaining:
            with open(path, "w", encoding="utf-8") as f:
                f.write("\n".join(remaining) + "\n")
        else:
            os.remove(path)
    except OSError:
        pass


def start_server(files_dir: str = None, host: str = DEFAULT_HOST,
                 port: int = DEFAULT_PORT) -> str:
    """Start the embedded server once; return the URL the WebView should load.

    Safe to call multiple times — only the first call starts the thread.
    Called from Java via Chaquopy: ``getModule("spendwise.android_entry")
    .callAttr("start_server", filesDir)``.
    """
    global _server_thread
    base = files_dir or os.getcwd()
    db_path = os.path.join(base, "spendwise.db")
    with _lock:
        if _server_thread is None or not _server_thread.is_alive():
            _server_thread = threading.Thread(
                target=_run, args=(db_path, host, port), daemon=True,
                name="spendwise-server")
            _server_thread.start()
            # Replay queued SMS once the server is ready (off the main thread).
            threading.Thread(target=_drain_inbox, args=(base, host, port),
                             daemon=True, name="spendwise-sms-drain").start()
    return f"http://{host}:{port}"


def is_running() -> bool:
    return _server_thread is not None and _server_thread.is_alive()
