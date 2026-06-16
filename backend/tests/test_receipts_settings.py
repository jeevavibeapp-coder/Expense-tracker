"""Receipt upload/download/delete and settings tests."""
from __future__ import annotations

import io


def test_receipt_upload_download_delete(auth, tmp_path, monkeypatch):
    client, headers, _ = auth
    # Point local storage at a temp dir for isolation.
    from app.services import storage
    storage._storage = storage.LocalStorage(str(tmp_path))

    png = b"\x89PNG\r\n\x1a\n" + b"0" * 64
    resp = client.post("/api/v1/receipts", headers=headers,
                       files={"file": ("receipt.png", io.BytesIO(png), "image/png")})
    assert resp.status_code == 201, resp.text
    rid = resp.json()["id"]
    assert resp.json()["size_bytes"] == len(png)

    content = client.get(f"/api/v1/receipts/{rid}/content", headers=headers)
    assert content.status_code == 200 and content.content == png

    assert client.delete(f"/api/v1/receipts/{rid}", headers=headers).status_code == 200
    assert client.get(f"/api/v1/receipts/{rid}/content", headers=headers).status_code == 404
    storage._storage = None


def test_reject_unsupported_file_type(auth):
    client, headers, _ = auth
    resp = client.post("/api/v1/receipts", headers=headers,
                       files={"file": ("x.exe", b"MZ", "application/x-msdownload")})
    assert resp.status_code == 415


def test_settings_roundtrip(auth):
    client, headers, _ = auth
    got = client.get("/api/v1/settings", headers=headers).json()
    assert got["auto_save_threshold"] == 80
    upd = client.patch("/api/v1/settings", headers=headers,
                       json={"currency": "USD", "theme": "dark", "auto_save_threshold": 90})
    assert upd.status_code == 200
    assert upd.json()["currency"] == "USD"
    assert upd.json()["theme"] == "dark"
    assert upd.json()["auto_save_threshold"] == 90
