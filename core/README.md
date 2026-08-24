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

**178 checks, all passing**, in about four seconds.

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
