"""Backup, restore, onboarding and the help/privacy screens.

The backup file is the only thing standing between "my phone is the only
copy" and "my ledger is gone", so these tests are weighted towards the ways
a restore can hurt someone: half-applying a damaged file, duplicating a
ledger that is restored twice, or trusting numbers out of a text file the
user can edit by hand.
"""
from __future__ import annotations

import json

import pytest

from spendwise import backup, db
from spendwise.app import create_app


TOKEN = "tok"


def _client(tmp_path, name="b.db"):
    app = create_app(db_path=str(tmp_path / name), single_user=True,
                     secret_key="s", device_token=TOKEN)
    c = app.test_client()
    c.get(f"/?k={TOKEN}", environ_overrides={"REMOTE_ADDR": "127.0.0.1"})
    c.post("/welcome/done")
    return app, c


def _add(c, merchant="Cafe", amount="250.00", type_="expense"):
    return c.post("/transactions", data={
        "amount": amount, "type": type_, "merchant": merchant,
        "category_id": "", "notes": "", "occurred_at": ""},
        follow_redirects=True)


def _conn(app):
    return db.connect(app.config["DB_PATH"])


def _uid(conn):
    return conn.execute("SELECT id FROM users LIMIT 1").fetchone()[0]


# ── the file ──────────────────────────────────────────────────────────────
def test_backup_carries_what_csv_cannot(tmp_path):
    """The CSV export drops merchant links, learning and every id. If the
    backup did the same it would not be a backup, just a second report."""
    app, c = _client(tmp_path)
    for _ in range(4):
        _add(c, merchant="Swiggy")
    doc = json.loads(c.get("/backup.json").data)
    assert doc["app"] == "SpendWise"
    tables = doc["tables"]
    assert tables["transactions"], "no transactions in the backup"
    assert tables["merchants"], "merchants were not carried"
    assert tables["learning"], "the learned merchant mapping was not carried"
    assert all(t.get("id") for t in tables["transactions"]), \
        "transactions were exported without ids, so a restore cannot dedupe"


def test_backup_excludes_raw_sms_bodies(tmp_path):
    """Raw bank messages are the most sensitive text on the device and they
    are recoverable by rescanning the inbox. A backup file that carries a
    year of them is a much worse thing to lose than one that does not."""
    app, c = _client(tmp_path)
    body = "Rs.450.00 spent at RAJUKIRANA on 12-06-2025 ref 553201998877 UPI"
    c.post("/sms/ingest", data={"sender": "VK-HDFCBK", "body": body},
           headers={"X-SpendWise-Token": TOKEN})
    raw = c.get("/backup.json").data.decode()
    assert "RAJUKIRANA" in raw, "the transaction itself should be backed up"
    assert "553201998877" in raw, "the reference number is part of the record"
    assert body not in raw, "the raw SMS body was written into the backup"


def test_backup_is_downloaded_as_a_file(tmp_path):
    app, c = _client(tmp_path)
    r = c.get("/backup.json")
    assert r.status_code == 200
    assert "attachment" in r.headers["Content-Disposition"]
    assert ".json" in r.headers["Content-Disposition"]


# ── validation ────────────────────────────────────────────────────────────
@pytest.mark.parametrize("raw, fragment", [
    (b"", "empty"),
    (b"not json at all", "JSON"),
    (b'{"hello": "world"}', "isn't a SpendWise backup"),
    (b'["a", "b"]', "isn't a SpendWise backup"),
    (b'{"app": "SpendWise", "format": 99, "tables": {}}', "newer version"),
    (b'{"app": "SpendWise", "format": 1, "tables": "nope"}', "missing its data"),
])
def test_bad_files_are_refused_with_a_sentence_a_person_can_act_on(raw, fragment):
    with pytest.raises(backup.RestoreError) as exc:
        backup.parse_backup(raw)
    assert fragment in str(exc.value)


def test_a_csv_export_is_refused_and_says_where_to_go(tmp_path):
    """Confusing the CSV export with the backup is the single most likely
    mistake here, so the error names the screen that does handle CSV."""
    app, c = _client(tmp_path)
    _add(c)
    csv_bytes = c.get("/export.csv").data
    with pytest.raises(backup.RestoreError) as exc:
        backup.parse_backup(csv_bytes)
    assert "Import" in str(exc.value)


# ── restoring ─────────────────────────────────────────────────────────────
def test_restore_rebuilds_a_ledger_on_an_empty_install(tmp_path):
    app_a, c_a = _client(tmp_path, "src.db")
    for mm in ("Swiggy", "Uber", "BigBasket"):
        _add(c_a, merchant=mm, amount="300.00")
    doc = backup.parse_backup(c_a.get("/backup.json").data)

    app_b, c_b = _client(tmp_path, "dst.db")
    conn = _conn(app_b)
    result = backup.restore(conn, _uid(conn), doc)
    assert result["added"]["transactions"] == 3
    page = c_b.get("/transactions")
    for mm in ("Swiggy", "Uber", "BigBasket"):
        assert mm.encode() in page.data


def test_restoring_the_same_file_twice_changes_nothing(tmp_path):
    """Someone unsure whether a restore worked will do it again. Doing so must
    not double their ledger."""
    app_a, c_a = _client(tmp_path, "src.db")
    _add(c_a, merchant="Swiggy")
    doc = backup.parse_backup(c_a.get("/backup.json").data)

    app_b, c_b = _client(tmp_path, "dst.db")
    conn = _conn(app_b)
    uid = _uid(conn)
    first = backup.restore(conn, uid, doc)
    second = backup.restore(conn, uid, doc)
    assert first["added"]["transactions"] == 1
    assert second["added"]["transactions"] == 0
    assert conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0] == 1


def test_merge_keeps_what_is_already_there(tmp_path):
    app_a, c_a = _client(tmp_path, "src.db")
    _add(c_a, merchant="FromBackup")
    doc = backup.parse_backup(c_a.get("/backup.json").data)

    app_b, c_b = _client(tmp_path, "dst.db")
    _add(c_b, merchant="AlreadyHere")
    conn = _conn(app_b)
    backup.restore(conn, _uid(conn), doc, replace=False)
    page = c_b.get("/transactions").data
    assert b"AlreadyHere" in page and b"FromBackup" in page


def test_replace_clears_first(tmp_path):
    app_a, c_a = _client(tmp_path, "src.db")
    _add(c_a, merchant="FromBackup")
    doc = backup.parse_backup(c_a.get("/backup.json").data)

    app_b, c_b = _client(tmp_path, "dst.db")
    _add(c_b, merchant="AlreadyHere")
    conn = _conn(app_b)
    backup.restore(conn, _uid(conn), doc, replace=True)
    page = c_b.get("/transactions").data
    assert b"AlreadyHere" not in page
    assert b"FromBackup" in page


def test_a_damaged_file_changes_nothing_at_all(tmp_path):
    """For a ledger, a half-applied restore is worse than a failed one: the
    user cannot tell which half arrived. The whole thing is one transaction."""
    app_a, c_a = _client(tmp_path, "src.db")
    for i in range(5):
        _add(c_a, merchant=f"M{i}")
    doc = backup.parse_backup(c_a.get("/backup.json").data)
    # A row that will blow up mid-insert: an id of the wrong type entirely.
    doc["tables"]["transactions"].append({"id": {"not": "a string"}})

    app_b, c_b = _client(tmp_path, "dst.db")
    conn = _conn(app_b)
    before = conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0]
    result = backup.restore(conn, _uid(conn), doc)
    # The bad row is skipped by validation, not by exploding — but if any row
    # HAD exploded, the count below is what proves nothing was left behind.
    assert result["skipped"]["transactions"] >= 1
    after = conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0]
    assert after == before + 5


def test_a_hand_edited_amount_cannot_poison_the_ledger(tmp_path):
    """The backup is a text file the user can open in an editor. Amounts go
    through the same gate as the SMS parser, because an infinite amount once
    permanently 500'd the dashboard."""
    app_a, c_a = _client(tmp_path, "src.db")
    _add(c_a, merchant="Good", amount="100.00")
    doc = backup.parse_backup(c_a.get("/backup.json").data)
    doc["tables"]["transactions"].append(
        {**doc["tables"]["transactions"][0], "id": "poison", "amount": 1e400})
    doc["tables"]["transactions"].append(
        {**doc["tables"]["transactions"][0], "id": "neg", "amount": -5})

    app_b, c_b = _client(tmp_path, "dst.db")
    conn = _conn(app_b)
    backup.restore(conn, _uid(conn), doc)
    rows = conn.execute("SELECT amount FROM transactions").fetchall()
    assert [r[0] for r in rows] == [100.0]
    assert c_b.get("/dashboard").status_code == 200


def test_a_hand_edited_threshold_is_clamped(tmp_path):
    app_a, c_a = _client(tmp_path, "src.db")
    doc = backup.parse_backup(c_a.get("/backup.json").data)
    doc["settings"]["auto_save_threshold"] = 900
    doc["settings"]["theme"] = "<script>"

    app_b, c_b = _client(tmp_path, "dst.db")
    conn = _conn(app_b)
    backup.restore(conn, _uid(conn), doc)
    s = conn.execute("SELECT auto_save_threshold, theme FROM settings").fetchone()
    assert s[0] == 100
    assert s[1] in ("system", "light", "dark")


def test_restore_never_writes_into_another_users_ledger(tmp_path):
    """The backup carries ids but not user ids: a restore always lands in the
    ledger of whoever is signed in."""
    app_a, c_a = _client(tmp_path, "src.db")
    _add(c_a, merchant="Swiggy")
    doc = backup.parse_backup(c_a.get("/backup.json").data)
    assert all("user_id" not in t for t in doc["tables"]["transactions"])

    app_b, c_b = _client(tmp_path, "dst.db")
    conn = _conn(app_b)
    uid = _uid(conn)
    backup.restore(conn, uid, doc)
    owners = {r[0] for r in conn.execute("SELECT user_id FROM transactions")}
    assert owners == {uid}


def test_round_trip_preserves_categories_and_budgets(tmp_path):
    app_a, c_a = _client(tmp_path, "src.db")
    cats = _conn(app_a).execute(
        "SELECT id, name FROM categories LIMIT 1").fetchone()
    c_a.post(f"/categories/{cats[0]}/budget", data={"budget_amount": "5000"})
    doc = backup.parse_backup(c_a.get("/backup.json").data)

    app_b, c_b = _client(tmp_path, "dst.db")
    conn = _conn(app_b)
    backup.restore(conn, _uid(conn), doc, replace=True)
    row = conn.execute("SELECT budget_amount FROM categories WHERE id=?",
                       (cats[0],)).fetchone()
    assert row is not None and row[0] == 5000.0


# ── the screens ───────────────────────────────────────────────────────────
def test_restore_screen_previews_before_it_restores(tmp_path):
    """Nobody should press Restore without being told what is in the file."""
    app_a, c_a = _client(tmp_path, "src.db")
    for i in range(3):
        _add(c_a, merchant=f"M{i}")
    blob = c_a.get("/backup.json").data

    app_b, c_b = _client(tmp_path, "dst.db")
    import io
    page = c_b.post("/restore", data={"backup": (io.BytesIO(blob), "b.json")},
                    content_type="multipart/form-data")
    assert page.status_code == 200
    assert b"This backup contains" in page.data
    # ...and nothing was written by merely looking at the file.
    conn = _conn(app_b)
    assert conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0] == 0


def test_restore_screen_reports_a_bad_file_without_touching_anything(tmp_path):
    app, c = _client(tmp_path)
    _add(c, merchant="Keep")
    import io
    page = c.post("/restore", data={"backup": (io.BytesIO(b"junk"), "b.json"),
                                    "confirm": "yes"},
                  content_type="multipart/form-data")
    assert page.status_code == 200
    assert b"Nothing was changed" in page.data
    assert b"Keep" in c.get("/transactions").data


def test_restore_screen_actually_restores_on_confirm(tmp_path):
    app_a, c_a = _client(tmp_path, "src.db")
    _add(c_a, merchant="Swiggy")
    blob = c_a.get("/backup.json").data

    app_b, c_b = _client(tmp_path, "dst.db")
    import io
    page = c_b.post("/restore", data={"backup": (io.BytesIO(blob), "b.json"),
                                      "confirm": "yes", "mode": "merge"},
                    content_type="multipart/form-data")
    assert b"Restored" in page.data
    assert b"Swiggy" in c_b.get("/transactions").data


# ── onboarding, privacy, help ─────────────────────────────────────────────
def test_first_run_lands_on_the_introduction(tmp_path):
    """The app should say what it will read from the phone before it reads
    anything."""
    app = create_app(db_path=str(tmp_path / "n.db"), single_user=True,
                     secret_key="s", device_token=TOKEN)
    c = app.test_client()
    c.get(f"/?k={TOKEN}")
    r = c.get("/dashboard")
    assert r.status_code == 302 and "/welcome" in r.headers["Location"]
    page = c.get("/welcome")
    assert b"What SpendWise reads" in page.data
    assert b"Never messages from people" in page.data


def test_the_introduction_is_shown_once(tmp_path):
    app, c = _client(tmp_path)          # the helper dismisses it
    assert c.get("/dashboard").status_code == 200
    # ...but it stays reachable, because a promise nobody can re-read is not
    # a promise.
    assert c.get("/welcome").status_code == 200


def test_a_deep_link_is_never_hijacked_by_the_introduction(tmp_path):
    """Onboarding redirects from the dashboard only. A notification tap that
    lands on a transaction must not be swallowed by an introduction."""
    app = create_app(db_path=str(tmp_path / "d.db"), single_user=True,
                     secret_key="s", device_token=TOKEN)
    c = app.test_client()
    c.get(f"/?k={TOKEN}")
    assert c.get("/transactions").status_code == 200
    assert c.get("/settings").status_code == 200


def test_existing_installs_are_not_shown_an_introduction(tmp_path):
    """Someone who has been using the app for months should not be walked
    through an introduction because they updated it."""
    path = str(tmp_path / "old.db")
    app, c = _client(tmp_path, "old.db")
    _add(c, merchant="History")
    # Simulate an install that predates the onboarding column.
    conn = db.connect(path)
    conn.execute("UPDATE settings SET onboarded_at = NULL")
    conn.commit()
    from spendwise import migrations
    migrations._m10_onboarding(conn)
    conn.commit()
    row = conn.execute("SELECT onboarded_at FROM settings").fetchone()
    assert row[0] is not None, "an established ledger was marked as new"


def test_privacy_and_help_screens_render(tmp_path):
    app, c = _client(tmp_path)
    privacy = c.get("/privacy")
    assert privacy.status_code == 200
    assert b"Everything stays on this phone" in privacy.data
    help_page = c.get("/help")
    assert help_page.status_code == 200
    assert b"Autostart" in help_page.data          # the real OEM fix
    assert b"I am getting a new phone" in help_page.data


def test_settings_links_to_backup_restore_privacy_and_help(tmp_path):
    app, c = _client(tmp_path)
    page = c.get("/settings").data
    for href in (b"/backup.json", b"/restore", b"/privacy", b"/help"):
        assert href in page, f"settings does not link to {href!r}"


def test_backup_and_restore_need_a_grant(tmp_path):
    """These two endpoints read and write the entire ledger. B1 regression."""
    app = create_app(db_path=str(tmp_path / "g.db"), single_user=True,
                     secret_key="s", device_token=TOKEN)
    c = app.test_client()
    assert c.get("/backup.json").status_code == 403
    assert c.get("/restore").status_code == 403
    assert c.post("/restore").status_code == 403
    assert c.get("/privacy").status_code == 403
    assert c.get("/help").status_code == 403
    assert c.get("/welcome").status_code == 403


def test_a_failure_mid_restore_rolls_the_whole_thing_back(tmp_path, monkeypatch):
    """The previous test proves bad rows are skipped. This one proves the
    other half of the promise: if something raises after rows are already
    written, the ledger goes back to exactly where it was.

    Forced through the settings step, which runs last, so real rows are
    definitely in the table by the time it blows up.
    """
    app_a, c_a = _client(tmp_path, "src.db")
    for i in range(5):
        _add(c_a, merchant=f"M{i}")
    doc = backup.parse_backup(c_a.get("/backup.json").data)

    app_b, c_b = _client(tmp_path, "dst.db")
    _add(c_b, merchant="AlreadyHere")
    conn = _conn(app_b)

    def boom(*a, **kw):
        raise RuntimeError("disk gave up")

    monkeypatch.setattr(backup, "_restore_settings", boom)
    with pytest.raises(backup.RestoreError) as exc:
        backup.restore(conn, _uid(conn), doc)
    assert "nothing was changed" in str(exc.value)

    names = {r[0] for r in conn.execute(
        "SELECT merchant_name FROM transactions")}
    assert names == {"AlreadyHere"}, \
        f"a failed restore left rows behind: {sorted(names)}"
