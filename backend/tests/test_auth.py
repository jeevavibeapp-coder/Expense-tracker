"""Authentication flow tests."""
from __future__ import annotations


def test_signup_creates_account_with_default_categories(auth):
    client, headers, body = auth
    assert body["user"]["email"] == "alice@example.com"
    assert body["tokens"]["access_token"]
    cats = client.get("/api/v1/categories", headers=headers).json()
    assert len(cats) >= 5  # standard category set provisioned


def test_duplicate_signup_rejected(client):
    payload = {"email": "bob@example.com", "full_name": "Bob", "password": "password123"}
    assert client.post("/api/v1/auth/signup", json=payload).status_code == 201
    assert client.post("/api/v1/auth/signup", json=payload).status_code == 409


def test_login_and_me(client):
    client.post("/api/v1/auth/signup", json={
        "email": "carol@example.com", "full_name": "Carol", "password": "password123"})
    resp = client.post("/api/v1/auth/login", json={
        "email": "carol@example.com", "password": "password123"})
    assert resp.status_code == 200
    token = resp.json()["tokens"]["access_token"]
    me = client.get("/api/v1/auth/me", headers={"Authorization": f"Bearer {token}"})
    assert me.status_code == 200
    assert me.json()["email"] == "carol@example.com"


def test_login_wrong_password(client):
    client.post("/api/v1/auth/signup", json={
        "email": "dave@example.com", "full_name": "Dave", "password": "password123"})
    resp = client.post("/api/v1/auth/login", json={
        "email": "dave@example.com", "password": "wrongpass"})
    assert resp.status_code == 401


def test_refresh_rotation(client):
    s = client.post("/api/v1/auth/signup", json={
        "email": "erin@example.com", "full_name": "Erin", "password": "password123"}).json()
    refresh = s["tokens"]["refresh_token"]
    r1 = client.post("/api/v1/auth/refresh", json={"refresh_token": refresh})
    assert r1.status_code == 200
    # The old refresh token is revoked after rotation.
    r2 = client.post("/api/v1/auth/refresh", json={"refresh_token": refresh})
    assert r2.status_code == 401


def test_forgot_and_reset_password(client):
    client.post("/api/v1/auth/signup", json={
        "email": "frank@example.com", "full_name": "Frank", "password": "password123"})
    forgot = client.post("/api/v1/auth/forgot-password",
                         json={"email": "frank@example.com"}).json()
    token = forgot["reset_token"]
    assert token
    reset = client.post("/api/v1/auth/reset-password",
                        json={"token": token, "new_password": "newpassword123"})
    assert reset.status_code == 200
    assert client.post("/api/v1/auth/login", json={
        "email": "frank@example.com", "password": "newpassword123"}).status_code == 200


def test_protected_route_requires_auth(client):
    assert client.get("/api/v1/transactions").status_code == 401


def test_forgot_password_unknown_email_does_not_leak(client):
    resp = client.post("/api/v1/auth/forgot-password", json={"email": "nobody@example.com"})
    assert resp.status_code == 200
    assert resp.json()["reset_token"] is None
