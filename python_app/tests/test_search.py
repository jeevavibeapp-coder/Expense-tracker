"""FTS5 search: index synchronisation, query safety and the fallback.

The risk in adding an index is not that it is slow — it is that it silently
disagrees with the table. A transaction that exists but cannot be found looks
exactly like data loss to the user. Most of these tests are about keeping the
index honest, not about speed.
"""
from __future__ import annotations

import sqlite3

import pytest

from spendwise import db, migrations, search
from spendwise.app import create_app

TOKEN = "tok"


def _client(tmp_path, name="s.db"):
    app = create_app(db_path=str(tmp_path / name), single_user=True,
                     secret_key="s", device_token=TOKEN)
    c = app.test_client()
    # Authenticate exactly as the WebView does: a one-time ?k=<device token>
    # grant on the first navigation, which mints the signed session cookie.
    c.get(f"/?k={TOKEN}", environ_overrides={"REMOTE_ADDR": "127.0.0.1"})
    return app, c


def _add(c, merchant, amount="100", notes=""):
    return c.post("/transactions", data={"amount": amount, "type": "expense",
                                         "merchant": merchant, "notes": notes},
                  follow_redirects=True)


def _ids(conn, uid, q):
    return search.search_ids(conn, uid, q) or []


# ── Query construction ────────────────────────────────────────────────────
def test_tokens_become_quoted_prefix_terms():
    assert search.build_match_query("netfl") == '"netfl"*'
    assert search.build_match_query("swiggy inst") == '"swiggy"* "inst"*'


def test_fts5_operators_in_user_input_are_neutralised():
    """A user typing FTS5 syntax must get a literal search, not a syntax
    error and not a query they did not ask for."""
    assert search.build_match_query('a OR b') == '"a"* "OR"* "b"*'
    assert search.build_match_query('"; DROP') == '"DROP"*'
    assert search.build_match_query('NEAR(a b)') == '"NEAR"* "a"* "b"*'
    assert search.build_match_query('*') is None
    assert search.build_match_query('^:()"') is None


def test_query_is_bounded_so_a_pasted_paragraph_cannot_blow_up():
    q = " ".join(f"word{i}" for i in range(50))
    assert search.build_match_query(q).count("*") == search.MAX_TERMS


def test_unicode_merchant_names_tokenize():
    assert search.build_match_query("ज़ोमैटो") == '"ज़ोमैटो"*'


# ── Index synchronisation ─────────────────────────────────────────────────
def test_index_is_populated_on_insert(tmp_path):
    app, c = _client(tmp_path)
    _add(c, "Netflix")
    conn = db.connect(app.config["DB_PATH"])
    uid = conn.execute("SELECT id FROM users").fetchone()[0]
    assert len(_ids(conn, uid, "netflix")) == 1
    conn.close()


def test_update_trigger_removes_the_old_terms(tmp_path):
    """The UPDATE trigger must delete the OLD values before inserting the new
    ones. Without that the index accumulates stale terms and a row stays
    findable under a name it no longer has."""
    path = str(tmp_path / "s2.db")
    conn, _ = db.open_database(path, backup=False)
    conn.execute("INSERT INTO users VALUES ('u1','a@b.c','U','x','2024-01-01')")
    conn.execute(
        "INSERT INTO transactions(id,user_id,amount,type,merchant_name,raw_merchant,"
        "occurred_at,source,status,created_at) VALUES ('t1','u1',10,'expense',"
        "'Netflix','NETFLIX IN','2025-01-01','manual','confirmed','2025-01-01')")
    conn.commit()
    assert _ids(conn, "u1", "netflix") == ["t1"]

    conn.execute("UPDATE transactions SET merchant_name='Spotify', "
                 "raw_merchant='SPOTIFY IN' WHERE id='t1'")
    conn.commit()
    assert _ids(conn, "u1", "spotify") == ["t1"]
    assert _ids(conn, "u1", "netflix") == [], "stale term survived the update"
    assert search.integrity_ok(conn)
    conn.close()


def test_editing_a_merchant_makes_the_new_name_findable(tmp_path):
    """App-level counterpart. Note the edit route deliberately KEEPS
    raw_merchant (it is the merchant engine's learning key), so the original
    text stays searchable on purpose — only the display name changes."""
    app, c = _client(tmp_path, "s2b.db")
    _add(c, "Netflix")
    conn = db.connect(app.config["DB_PATH"])
    uid = conn.execute("SELECT id FROM users").fetchone()[0]
    tx_id = conn.execute("SELECT id FROM transactions").fetchone()[0]
    conn.close()

    c.post(f"/transactions/{tx_id}/edit",
           data={"amount": "100", "type": "expense", "merchant": "Spotify",
                 "notes": "moved"}, follow_redirects=True)

    conn = db.connect(app.config["DB_PATH"])
    assert _ids(conn, uid, "spotify") == [tx_id]
    assert _ids(conn, uid, "moved") == [tx_id]
    assert search.integrity_ok(conn)
    conn.close()


def test_index_follows_a_hard_delete(tmp_path):
    app, c = _client(tmp_path, "s3.db")
    _add(c, "Netflix")
    conn = db.connect(app.config["DB_PATH"])
    uid = conn.execute("SELECT id FROM users").fetchone()[0]
    conn.execute("DELETE FROM transactions")
    conn.commit()
    assert _ids(conn, uid, "netflix") == []
    assert search.integrity_ok(conn)
    conn.close()


def test_soft_deleted_transactions_are_excluded(tmp_path):
    """is_deleted is a column, not a row removal, so the index still holds
    the terms — the query has to filter them out."""
    app, c = _client(tmp_path, "s4.db")
    _add(c, "Netflix")
    conn = db.connect(app.config["DB_PATH"])
    uid = conn.execute("SELECT id FROM users").fetchone()[0]
    conn.execute("UPDATE transactions SET is_deleted=1")
    conn.commit()
    assert _ids(conn, uid, "netflix") == []
    conn.close()


def test_index_is_consistent_after_many_mutations(tmp_path):
    """FTS5's own integrity-check is the authority on whether the triggers
    have kept up."""
    app, c = _client(tmp_path, "s5.db")
    for i in range(20):
        _add(c, f"Merchant{i}", amount=str(100 + i))
    conn = db.connect(app.config["DB_PATH"])
    ids = [r[0] for r in conn.execute("SELECT id FROM transactions").fetchall()]
    conn.close()
    for tx_id in ids[:8]:
        c.post(f"/transactions/{tx_id}/edit",
               data={"amount": "55", "type": "expense", "merchant": "Renamed",
                     "notes": "edited"}, follow_redirects=True)
    for tx_id in ids[8:14]:
        c.post(f"/transactions/{tx_id}/delete", follow_redirects=True)

    conn = db.connect(app.config["DB_PATH"])
    assert search.integrity_ok(conn), "index diverged from the table"
    conn.close()


def test_backfill_indexes_a_preexisting_ledger(tmp_path):
    """Users upgrading already have transactions. If the migration did not
    backfill, their entire history would become unsearchable."""
    path = str(tmp_path / "s6.db")
    conn, _ = db.open_database(path, backup=False)
    conn.execute("INSERT INTO users VALUES ('u1','a@b.c','U','x','2024-01-01')")
    for i in range(5):
        conn.execute(
            "INSERT INTO transactions(id,user_id,amount,type,merchant_name,"
            "occurred_at,source,status,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
            (f"t{i}", "u1", 10.0, "expense", "Blinkit",
             "2025-01-01T00:00:00", "sms", "confirmed", "2025-01-01T00:00:00"))
    conn.commit()
    # Drop the index and re-run the migration body to simulate the upgrade.
    conn.execute("DROP TABLE tx_fts")
    migrations._m7_fts_search(conn)
    conn.commit()
    assert len(_ids(conn, "u1", "blinkit")) == 5
    conn.close()


def test_rebuild_index_recovers_a_diverged_index(tmp_path):
    path = str(tmp_path / "s7.db")
    conn, _ = db.open_database(path, backup=False)
    conn.execute("INSERT INTO users VALUES ('u1','a@b.c','U','x','2024-01-01')")
    conn.execute(
        "INSERT INTO transactions(id,user_id,amount,type,merchant_name,"
        "occurred_at,source,status,created_at) VALUES ('t1','u1',10,'expense',"
        "'Zomato','2025-01-01T00:00:00','sms','confirmed','2025-01-01T00:00:00')")
    conn.commit()
    conn.execute("INSERT INTO tx_fts(tx_fts) VALUES('delete-all')")
    conn.commit()
    assert _ids(conn, "u1", "zomato") == []
    assert search.rebuild_index(conn) is True
    assert _ids(conn, "u1", "zomato") == ["t1"]
    conn.close()


# ── Fallback behaviour ────────────────────────────────────────────────────
def test_search_reports_unavailable_rather_than_empty(tmp_path):
    """None and [] mean different things: None sends the caller to the
    substring scan, [] would silently narrow what the user can find."""
    path = str(tmp_path / "s8.db")
    conn, _ = db.open_database(path, backup=False)
    conn.execute("DROP TABLE tx_fts")
    conn.commit()
    assert search.available(conn) is False
    assert search.search_ids(conn, "u1", "anything") is None
    conn.close()


def test_substring_match_still_works_via_the_fallback(tmp_path):
    """FTS5 matches token prefixes, so 'wiggy' cannot match 'Swiggy'. The old
    scan could, and the route must still find it."""
    app, c = _client(tmp_path, "s9.db")
    _add(c, "Swiggy")
    conn = db.connect(app.config["DB_PATH"])
    uid = conn.execute("SELECT id FROM users").fetchone()[0]
    assert search.search_ids(conn, uid, "wiggy") is None   # index cannot help
    conn.close()
    r = c.get("/transactions?q=wiggy")
    assert r.status_code == 200
    assert b"Swiggy" in r.data, "the fallback scan was skipped"


def test_route_search_finds_by_prefix_and_by_reference(tmp_path):
    app, c = _client(tmp_path, "s10.db")
    _add(c, "Netflix")
    _add(c, "BigBasket")
    assert b"Netflix" in c.get("/transactions?q=netfl").data
    assert b"BigBasket" in c.get("/transactions?q=bigb").data
    # A prefix that matches neither must return neither.
    page = c.get("/transactions?q=qqqq").data
    assert b"Netflix" not in page and b"BigBasket" not in page


def test_search_never_crosses_users(tmp_path):
    """The index is shared across users; the join must scope by user_id."""
    path = str(tmp_path / "s11.db")
    conn, _ = db.open_database(path, backup=False)
    for uid in ("u1", "u2"):
        conn.execute("INSERT INTO users VALUES (?,?,?,?,?)",
                     (uid, f"{uid}@x.c", "U", "x", "2024-01-01"))
        conn.execute(
            "INSERT INTO transactions(id,user_id,amount,type,merchant_name,"
            "occurred_at,source,status,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
            (f"t-{uid}", uid, 10.0, "expense", "Netflix",
             "2025-01-01T00:00:00", "manual", "confirmed", "2025-01-01T00:00:00"))
    conn.commit()
    assert _ids(conn, "u1", "netflix") == ["t-u1"]
    assert _ids(conn, "u2", "netflix") == ["t-u2"]
    conn.close()


def test_a_malformed_match_does_not_500_the_page(tmp_path):
    app, c = _client(tmp_path, "s12.db")
    _add(c, "Netflix")
    for q in ['"', "*", "()", "^", "NEAR", "a AND OR b", "-" * 300]:
        assert c.get(f"/transactions?q={q}").status_code == 200


# ── Availability degradation ──────────────────────────────────────────────
def test_migration_survives_a_sqlite_without_fts5(tmp_path, monkeypatch):
    """FTS5 is a compile-time option and the device's SQLite is not ours to
    choose. Wedging the upgrade over a search optimisation would be a far
    worse outcome than a slower search."""
    monkeypatch.setattr(migrations, "fts5_available", lambda conn: False)
    path = str(tmp_path / "s13.db")
    conn, status = db.open_database(path, backup=False)
    assert status["version"] == migrations.SCHEMA_VERSION
    assert search.available(conn) is False
    marker = conn.execute("SELECT value FROM app_state WHERE key='fts5'").fetchone()
    assert marker[0] == "unavailable"
    conn.close()


def test_app_still_searches_without_fts5(tmp_path, monkeypatch):
    monkeypatch.setattr(migrations, "fts5_available", lambda conn: False)
    app, c = _client(tmp_path, "s14.db")
    _add(c, "Netflix")
    assert b"Netflix" in c.get("/transactions?q=netflix").data
    assert b"Netflix" in c.get("/transactions?q=etfli").data   # substring
