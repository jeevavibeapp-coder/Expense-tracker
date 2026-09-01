# core — the domain logic, as pure Kotlin

Everything in here is plain Kotlin with **no Android, Room or framework
dependency**, so it compiles and runs on a bare JVM in about twenty seconds,
with no SDK, no emulator and no device:

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
| `Budgets.kt` | budget code in `analytics.py` + `app.py` | 70 checks: per-category rollups, daily bars, the monthly report |
| `Backup.kt` | `backup.py` | 94 checks: the file format, validation, and the restore plan |

**366 checks, all passing** — the count `core/run-tests.sh` prints, as six
suites:

| Suite | Checks |
|---|---|
| `BackupTest` | 94 |
| `BudgetsTest` | 70 |
| `EngineTest` | 58 |
| `InsightsTest` | 50 |
| `ParsingTest` | 70 |
| `PipelineTest` | 24 |
| **total** | **366** |

`Pipeline.kt` is the most consequential file: it decides what lands in
someone's ledger and what is held back. Lookups are injected rather than a
database being reached into, so cases that are awkward to reproduce on a
device — a spoofed header, a blocked sender, two rails describing one
payment — are ordinary unit tests.

## Deliberate differences from the Python original

The port is otherwise behaviour-for-behaviour faithful — checked by running
both against the same inputs — with four exceptions, all bugs that were not
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

## Type-checking the Android layer

`:core` is verified by running it. The Android module above it cannot be, in
this environment: Gradle needs the Android SDK and `dl.google.com`, and
neither is reachable. So the app sources are type-checked instead, against
hand-transcribed declarations:

```bash
./tools/typecheck.sh        # summary
./tools/typecheck.sh -v     # every error, with file and line
```

`kotlinc` compiles the real, unmodified app sources together with the 46 stub
files in `tools/compose-stubs/` — Compose, Material3, Room, lifecycle and the
framework classes the app touches, transcribed from the pinned versions
(Compose BOM 2024.09.03, Room 2.6.1, activity-compose 1.9.2, coroutines
1.8.1). It takes about seventeen seconds.

**What it does verify.** That every name resolves, every signature matches,
every type lines up, `when` blocks are exhaustive, nullability is respected,
and no file is truncated or unbalanced. A stub error is reported separately
from an app error, because a wrong stub is a bug in the harness and must
never be answered by bending correct app code to fit it.

**What it cannot verify — a green run does not mean the app works.**

- **KSP-generated code.** Room's annotation processor never runs. The
  generated DAO implementations, the `@Database` wiring and Room's own check
  that `MIGRATION_1_2` leaves the `settings` table in the shape
  `SettingsEntity` describes are all absent. Every `@Query` string is an
  opaque literal here; a misspelled column is a KSP error at Gradle time, not
  a type error now.
- **Anything Gradle does.** Resource linking (AAPT2), the manifest merge
  across the `sms` / `nosms` flavours, dependency resolution, and the Compose
  compiler plugin are all outside this check. The stubs are also compiled by
  Kotlin 2.1 while Gradle is pinned to 2.0.21.
- **Runtime behaviour.** Composition, recomposition, state, coroutine
  dispatch, navigation, permissions and every SQL statement's actual result.
- **The device.** Layout, overflow, contrast, touch targets, screen-reader
  output, dark mode, and anything about how it looks or feels.

Treat it as what it is: a fast check that the Android layer is *well-typed*.
The behavioural guarantee in this project lives in `:core` and its 366
checks, which is exactly why so much of the logic was put there.
