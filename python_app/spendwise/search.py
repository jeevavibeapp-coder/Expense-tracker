"""Transaction search.

Search used to be four ``LIKE '%term%'`` predicates OR'd together. No index
can serve a leading-wildcard LIKE, so every keystroke read every row the user
had ever recorded — the cost grew without bound while the result set did not.

This module puts an FTS5 index in front of that. Two properties matter more
than the speed:

* **FTS5 is optional.** It is a compile-time extension and this app runs on
  whatever SQLite the Chaquopy runtime provides on the device. If the module
  is absent, search must still work, so the scan remains as a fallback rather
  than being deleted.
* **No result is lost to the optimisation.** FTS5 matches whole tokens with
  an optional prefix, so ``wiggy`` would not find ``Swiggy`` even though the
  old substring scan did. Rather than silently narrowing what the user can
  find, an empty FTS result falls through to the scan. The scan then only
  runs when the fast path already found nothing — the case where the user is
  about to be told "no results" anyway.
"""
from __future__ import annotations

import re
import sqlite3
from typing import Optional

# Runs of word characters, Unicode-aware so Devanagari/Tamil merchant names
# A token is a run of characters that is neither whitespace nor ASCII
# punctuation. Deliberately NOT `\w`: Python's \w excludes Unicode combining
# marks, which split "ज़ोमैटो" into three fragments that FTS5's unicode61
# index does not contain, so the search silently returned nothing. Every
# character FTS5 treats as syntax (" * ^ : ( ) -) is ASCII punctuation and so
# is excluded here, which is what makes the query construction injection-proof.
_TOKEN_RE = re.compile(r"[^\s!-/:-@\[-`{-~]+")

# Guard against a pathological query (a pasted paragraph) turning into a
# hundred-term AND that is slower than the scan it replaced.
MAX_TERMS = 8


def available(conn: sqlite3.Connection) -> bool:
    """Whether the FTS index exists on this database."""
    row = conn.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='tx_fts'").fetchone()
    return row is not None


def build_match_query(raw: str) -> Optional[str]:
    """Turn user input into a safe FTS5 MATCH expression.

    Each token becomes a quoted prefix term, so ``netfl`` finds ``Netflix``
    and ``swiggy inst`` finds ``Swiggy Instamart``. Quoting is what makes this
    injection-proof: the query is data inside a string literal, never operator
    syntax, so ``a OR b`` searches for the three words rather than running a
    disjunction the user did not ask for.
    """
    tokens = _TOKEN_RE.findall(raw or "")[:MAX_TERMS]
    if not tokens:
        return None
    # A doubled quote is FTS5's escape for a literal quote; the tokenizer
    # regex already excludes quotes, but this keeps the construction safe if
    # that regex is ever widened.
    return " ".join('"%s"*' % t.replace('"', '""') for t in tokens)


def search_ids(conn: sqlite3.Connection, user_id: str, query: str,
               limit: int = 200) -> Optional[list[str]]:
    """Return matching transaction ids ranked by FTS5 relevance.

    ``None`` means "the index could not answer this" — either FTS5 is absent
    or it matched nothing — and the caller should fall back to the scan.
    An empty list is never returned for that reason: it would be
    indistinguishable from "no matches", and the caller would skip the
    fallback that still finds substring matches.
    """
    if not available(conn):
        return None
    match = build_match_query(query)
    if not match:
        return None
    try:
        rows = conn.execute(
            "SELECT t.id FROM tx_fts f JOIN transactions t ON t.rowid = f.rowid "
            "WHERE tx_fts MATCH ? AND t.user_id = ? AND t.is_deleted = 0 "
            "ORDER BY rank LIMIT ?", (match, user_id, limit)).fetchall()
    except sqlite3.DatabaseError:
        # A malformed MATCH should never take the page down with a 500.
        return None
    return [r[0] for r in rows] or None


def rebuild_index(conn: sqlite3.Connection) -> bool:
    """Re-derive the whole index from the base table.

    Needed after any operation that changes rowids behind the triggers' backs
    — a table rebuild, or a restore from backup. Returns False when FTS5 is
    unavailable so callers can report honestly instead of assuming success.
    """
    if not available(conn):
        return False
    conn.execute("INSERT INTO tx_fts(tx_fts) VALUES('rebuild')")
    conn.commit()
    return True


def integrity_ok(conn: sqlite3.Connection) -> bool:
    """Whether the index agrees with the base table.

    FTS5's own consistency check. Used by the maintenance sweep: a silently
    diverged index makes transactions unfindable, which looks like data loss
    to the user even though the ledger is intact.
    """
    if not available(conn):
        return True                     # nothing to be inconsistent with
    try:
        conn.execute("INSERT INTO tx_fts(tx_fts) VALUES('integrity-check')")
        return True
    except sqlite3.DatabaseError:
        return False
