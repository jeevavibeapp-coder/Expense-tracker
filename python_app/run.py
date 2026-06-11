"""Local/dev server entry point: `python run.py` (or `flask`-style via gunicorn).

For production behind a real server use a WSGI server, e.g.:
    gunicorn "spendwise.app:create_app()"
"""
from __future__ import annotations

import os

from spendwise.app import create_app

app = create_app()

if __name__ == "__main__":
    port = int(os.environ.get("PORT", "5000"))
    app.run(host="0.0.0.0", port=port, debug=bool(os.environ.get("DEBUG")))
