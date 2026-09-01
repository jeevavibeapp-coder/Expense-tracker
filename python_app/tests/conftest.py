"""Pytest fixtures for the SpendWise Flask app."""
from __future__ import annotations

import os
import sys

# Make the `spendwise` package importable when pytest runs from anywhere.
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest  # noqa: E402

from spendwise.app import create_app  # noqa: E402
from spendwise import db as _db  # noqa: E402


@pytest.fixture()
def app(tmp_path):
    return create_app(db_path=str(tmp_path / "test.db"), single_user=False,
                      secret_key="test-secret")


@pytest.fixture()
def client(app):
    return app.test_client()


@pytest.fixture()
def conn(tmp_path):
    c = _db.connect(str(tmp_path / "engine.db"))
    _db.init_db(c)
    yield c
    c.close()


def signup(client, email="alice@example.com"):
    return client.post("/signup", data={
        "full_name": "Alice", "email": email, "password": "supersecret1",
    }, follow_redirects=True)


@pytest.fixture()
def auth_client(client):
    resp = signup(client)
    assert resp.status_code == 200
    # Skip the first-run introduction. Every test using this fixture is about
    # the app after setup; onboarding has its own tests, which do not use it.
    client.post("/welcome/done")
    return client
