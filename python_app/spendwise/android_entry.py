"""Entry point used by the Android app (Chaquopy) to run SpendWise on-device.

The Android `MainActivity` starts a background thread that calls
``start_server(files_dir)``; this launches the Flask app on 127.0.0.1 so the
Capacitor WebView can load it. Everything runs fully offline inside the APK.
"""
from __future__ import annotations

import os
import threading

_server_thread: "threading.Thread | None" = None
_lock = threading.Lock()

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8765


def _run(db_path: str, host: str, port: int) -> None:
    from spendwise.app import create_app

    app = create_app(db_path=db_path, single_user=True)
    # Werkzeug's dev server is sufficient for a single on-device user.
    app.run(host=host, port=port, threaded=True, use_reloader=False, debug=False)


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
    return f"http://{host}:{port}"


def is_running() -> bool:
    return _server_thread is not None and _server_thread.is_alive()
