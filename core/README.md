# core — the domain logic, as pure Kotlin

Everything in here is plain Kotlin with **no Android, Room or framework
dependency**, so it compiles and runs on a bare JVM in about two seconds:

```bash
./core/run-tests.sh
```

That is not a stylistic preference. The value of this code is the edge cases
encoded in it — every one of which was learned from a real bank message that
parsed wrongly — and edge cases are only worth anything if re-verifying them
is cheap. A parser you can only test by building an APK is a parser nobody
re-tests.

## Why this exists

The app previously embedded a CPython interpreter running a Flask web server
on a loopback port, which the WebView then browsed. For an offline expense
tracker that meant: a multi-second cold start, tens of megabytes of
interpreter per CPU architecture, and a class of failure — "the app engine
didn't respond", "Address already in use" — that a normal Android app simply
cannot have, because it has no engine and no port.

## Ported so far

| Kotlin | From | Verified by |
|---|---|---|
| `Parsing.kt` | `parsing.py` | 70 checks incl. the full bank + junk corpus |
| `Engine.kt` | `engine.py` | merchant folding, the 5-part confidence score, decisions |
| `Categorizer.kt` | `categorizer.py` | Naive Bayes: trains, declines, explains |
| `Senders.kt` | `senders.py` | DLT shapes, phishing weights, the toll-free guard |
| `Analytics.kt` | `analytics.py` | recurring, self-transfers, refunds, money flow |
| `Insights.kt` | `insights.py` | forecast, cash flow, trends, anomalies, savings |
| `Pipeline.kt` | the SMS flow in `app.py` | the whole capture/hold/drop decision |

**202 checks, all passing**, in about four seconds.

`Pipeline.kt` is the most consequential file: it decides what lands in
someone's ledger and what is held back. Lookups are injected rather than a
database being reached into, so cases that are awkward to reproduce on a
device — a spoofed header, a blocked sender, two rails describing one
payment — are ordinary unit tests.

## Deliberate differences from the Python original

The port is otherwise behaviour-for-behaviour faithful — checked by running
both against the same inputs — with two exceptions, both bugs that were not
worth reproducing:

1. **Merchant names no longer swallow the reference number.** The old payee
   boundary stopped at the exact word `ref`, so `trf to SWIGGY Refno
   553201998877` produced the merchant *"SWIGGY Refno 553201998877"*. That
   became its own merchant in the ledger and never matched the real SWIGGY.
2. **`on date 08Jul26` now yields a date.** The old pattern required the
   digits to follow `on` immediately, so those messages silently lost their
   date and were filed under the day they happened to be received.
3. **`from` ends a merchant name.** `debited to ZOMATO from a/c **1234`
   produced the merchant *"ZOMATO from"* — a second, separate merchant that
   never matched the learning done on the real ZOMATO. Same class of bug as
   (1), found by the same kind of test.
4. **A scam that looks like a debit is now HELD, not dropped.** The parser
   refuses to bank `Rs.9,999 debited... not you? call 9812345678` because of
   its scam wording — correctly. But it was then discarded silently, which
   contradicts what the app tells the user the quarantine screen is for, and
   they may genuinely have been debited. `matched` and `looksTransactional`
   are now separate answers to separate questions: nothing scam-worded is
   ever banked, but if it has the shape of a debit the user gets to see it.
   A promo or a pre-debit notice has no such shape and is still just noise.
