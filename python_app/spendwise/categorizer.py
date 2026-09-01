"""Naive Bayes category suggestion, trained on the user's own history.

The merchant engine already answers "which merchant is this?" well, and a
known merchant carries its category. The gap is the *first* time a merchant
appears: the engine has no mapping, so the transaction lands uncategorised
and the user is asked. With 209 uncategorised rows — the situation this app
was actually in — that is a lot of asking.

But an unseen merchant is rarely unseen *text*. "SWIGGY INSTAMART" is new
while "SWIGGY" is known; "DOMINOS PIZZA HSR" is new while "PIZZA" has been
seen in the Food category twenty times. A multinomial Naive Bayes over word
tokens generalises across exactly that, and it is the right size of model
here: it trains in one pass over rows already in memory, needs no library
beyond the standard one, and its output is explainable as "these words made
me think Food".

Design constraints this is built to:

* **The user's data only.** No pretrained weights, nothing shipped, nothing
  transmitted. A brand-new install has no model and simply does not suggest.
* **Suggest, never assert.** A prediction sets a *suggested* category with a
  probability; only the merchant engine's own high-confidence path may
  auto-apply. A wrong silent categorisation is worse than an empty one
  because the user has no signal to correct it.
* **Abstain when unsure.** Below MIN_CONFIDENCE the model returns nothing.
  Precision matters far more than recall: an unhelpful blank is a small cost,
  a confident wrong answer teaches the user to distrust every suggestion.
"""
from __future__ import annotations

import math
import re
import sqlite3
from typing import Optional

MODEL_VERSION = "2026.07.1"

# Below this many labelled transactions the class priors are noise.
MIN_TRAINING_ROWS = 12
# And a class seen once cannot support a generalisation.
MIN_ROWS_PER_CATEGORY = 2
# Posterior probability below which the model abstains.
MIN_CONFIDENCE = 0.62
# Laplace smoothing. 1.0 is the standard choice and behaves sanely on the
# very small vocabularies a single user produces.
ALPHA = 1.0

_TOKEN_RE = re.compile(r"[^\W_]+", re.UNICODE)

# Tokens that carry no category signal but appear constantly in SMS-derived
# merchant strings. Left in, they dominate the vocabulary and flatten every
# posterior toward the prior.
_STOPWORDS = frozenset("""
    upi p2a p2m pvt ltd limited india indian in the and for from via ref no
    txn transaction payment paid debit debited credit credited account acct ac
    bank card inr rs to at on by of a an it com www http https
""".split())


def _tokens(text: str) -> list[str]:
    out = []
    for raw in _TOKEN_RE.findall((text or "").lower()):
        # Pure digits are account/reference fragments — unique per
        # transaction, so they only ever add noise.
        if raw.isdigit() or len(raw) < 2 or raw in _STOPWORDS:
            continue
        out.append(raw)
    return out


class Model:
    """A trained multinomial Naive Bayes classifier over category labels."""

    __slots__ = ("log_prior", "log_likelihood", "vocab_size", "categories",
                 "default_logl", "rows")

    def __init__(self, log_prior, log_likelihood, vocab_size, categories,
                 default_logl, rows):
        self.log_prior = log_prior
        self.log_likelihood = log_likelihood
        self.vocab_size = vocab_size
        self.categories = categories
        self.default_logl = default_logl
        self.rows = rows

    def predict(self, text: str) -> Optional[tuple[str, float]]:
        """Return ``(category_id, probability)`` or None when unsure."""
        tokens = _tokens(text)
        if not tokens:
            return None
        scores = {}
        for cat, prior in self.log_prior.items():
            total = prior
            likelihood = self.log_likelihood[cat]
            fallback = self.default_logl[cat]
            for tok in tokens:
                total += likelihood.get(tok, fallback)
            scores[cat] = total
        if not scores:
            return None
        best = max(scores, key=scores.get)
        # Normalise to a real probability via log-sum-exp so the number shown
        # to the user means something, rather than being an arbitrary score.
        top = scores[best]
        denom = sum(math.exp(v - top) for v in scores.values())
        probability = 1.0 / denom
        if probability < MIN_CONFIDENCE:
            return None
        return best, probability

    def explain(self, text: str, limit: int = 3) -> list[str]:
        """The tokens that pushed hardest toward the winning category.

        A suggestion the user cannot interrogate is a suggestion they will
        eventually stop trusting.
        """
        pred = self.predict(text)
        if not pred:
            return []
        cat = pred[0]
        likelihood = self.log_likelihood[cat]
        fallback = self.default_logl[cat]
        others = [c for c in self.categories if c != cat]
        scored = []
        for tok in set(_tokens(text)):
            mine = likelihood.get(tok, fallback)
            rest = max((self.log_likelihood[c].get(tok, self.default_logl[c])
                        for c in others), default=fallback)
            scored.append((mine - rest, tok))
        scored.sort(reverse=True)
        return [tok for gain, tok in scored[:limit] if gain > 0]


def train(rows) -> Optional[Model]:
    """Fit from ``(text, category_id)`` pairs. None if there is too little."""
    docs = [(_tokens(text), cat) for text, cat in rows if cat]
    docs = [d for d in docs if d[0]]
    if len(docs) < MIN_TRAINING_ROWS:
        return None

    counts: dict[str, dict[str, int]] = {}
    totals: dict[str, int] = {}
    doc_counts: dict[str, int] = {}
    vocab: set[str] = set()
    for tokens, cat in docs:
        doc_counts[cat] = doc_counts.get(cat, 0) + 1
        bucket = counts.setdefault(cat, {})
        for tok in tokens:
            bucket[tok] = bucket.get(tok, 0) + 1
            totals[cat] = totals.get(cat, 0) + 1
            vocab.add(tok)

    # Drop classes with too few examples rather than letting them win on a
    # single coincidental token.
    keep = {c for c, n in doc_counts.items() if n >= MIN_ROWS_PER_CATEGORY}
    if len(keep) < 2:
        return None                     # nothing to discriminate between

    n_docs = sum(doc_counts[c] for c in keep)
    v = len(vocab)
    log_prior, log_likelihood, default_logl = {}, {}, {}
    for cat in keep:
        log_prior[cat] = math.log(doc_counts[cat] / n_docs)
        denom = totals.get(cat, 0) + ALPHA * (v + 1)
        log_likelihood[cat] = {
            tok: math.log((n + ALPHA) / denom) for tok, n in counts[cat].items()}
        # Unseen token: the smoothed probability with a zero numerator count.
        default_logl[cat] = math.log(ALPHA / denom)
    return Model(log_prior, log_likelihood, v, sorted(keep), default_logl, n_docs)


# ── Per-connection cache ──────────────────────────────────────────────────
# Training touches every categorised transaction, so it must not run once per
# suggestion. Cached on the connection object, which lives exactly one
# request — so a category the user just corrected is picked up on the next
# request rather than being stale for the process lifetime.
_CACHE_ATTR = "_sw_nb_model"


def for_user(conn: sqlite3.Connection, user_id: str) -> Optional[Model]:
    cached = getattr(conn, _CACHE_ATTR, None)
    if cached is not None and cached[0] == user_id:
        return cached[1]
    rows = conn.execute(
        # raw_merchant is the SMS text as received and carries the most signal;
        # merchant_name and notes add the user's own vocabulary.
        "SELECT COALESCE(raw_merchant,'') || ' ' || COALESCE(merchant_name,'') "
        "       || ' ' || COALESCE(notes,''), category_id "
        "FROM transactions WHERE user_id=? AND is_deleted=0 "
        "  AND category_id IS NOT NULL AND status='confirmed' "
        "ORDER BY occurred_at DESC LIMIT 2000", (user_id,)).fetchall()
    model = train([(r[0], r[1]) for r in rows])
    try:
        setattr(conn, _CACHE_ATTR, (user_id, model))
    except AttributeError:
        pass                            # a connection type that forbids attrs
    return model


def invalidate(conn: sqlite3.Connection) -> None:
    try:
        setattr(conn, _CACHE_ATTR, None)
    except AttributeError:
        pass


def suggest(conn: sqlite3.Connection, user_id: str,
            text: str) -> Optional[dict]:
    """Suggest a category for an uncategorised transaction.

    Returns ``{"category_id", "confidence", "because"}`` or None. Callers must
    treat this as a suggestion for the user to accept — never as a decision.
    """
    model = for_user(conn, user_id)
    if model is None:
        return None
    pred = model.predict(text)
    if pred is None:
        return None
    category_id, probability = pred
    return {"category_id": category_id,
            "confidence": int(round(probability * 100)),
            "because": model.explain(text),
            "model_version": MODEL_VERSION}
