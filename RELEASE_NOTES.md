# SpendWise 1.2 — Production hardening

Security, data-integrity and scale work. No new product surface beyond the
held-messages screen; everything here is about the app being correct and
trustworthy under conditions the earlier releases did not cover.

## Security

- **Android Keystore.** The loopback device token and the Flask session key
  were both stored in plaintext — the session key inside the ledger file
  itself, so any backup of the ledger leaked it. Both are now AES-256/GCM
  ciphertext under a non-exportable AndroidKeyStore key. The legacy plaintext
  token is rotated (not imported) and erased durably; the plaintext session
  key is deleted from `app_state` on every boot, so a downgrade/upgrade cycle
  cannot leave one behind. Degrades to a marked, observable fallback rather
  than losing SMS capture on OEMs with broken keystores.
- **SMS sender verification and phishing defence.** The parser judged content,
  and content is what an attacker controls — the standard Indian fake-debit
  fraud parsed cleanly and entered the ledger. Sender shape (TRAI DLT headers
  vs personal mobiles), twelve content indicators and learned per-sender trust
  now decide between accept / review / quarantine. Nothing is ever silently
  discarded: every message lands in `transactions`, `sms_quarantine` or
  `parse_misses`, and held messages are kept in full with a plain-English
  explanation you can approve or reject.

## Data integrity

- **Real foreign keys** (schema v6), added via a full table rebuild, with
  per-relationship delete actions. Deleting a category can no longer destroy
  the record of your money.
- **First-launch crash fixed.** Concurrent requests on a brand-new install
  both tried to create the local user; the loser returned a 500 on the very
  first screen.

## Performance

Measured on a 12,000-transaction ledger:

| | Before | After |
|---|---|---|
| Dashboard | 101.7 ms | 55.9 ms |
| Reference-number search | 12.10 ms | 0.08 ms |
| Category suggestion | 7.64 ms | 0.006 ms |
| Merchant resolution (x12) | 0.63 ms | 0.19 ms |

- **FTS5 search** (v7) with trigger sync, falling back to the previous scan
  when the device's SQLite lacks the module or the index cannot answer — so
  the optimisation can never make a transaction unfindable.
- **Daily rollups** (v8) with incremental, read-triggered maintenance.
- Two caches that claimed to exist but never did (`sqlite3.Connection` has no
  `__dict__`) now actually cache.

## Intelligence

- **Bank parser registry** — per-institution patterns for HDFC, ICICI, SBI,
  Axis, Kotak, PhonePe, Google Pay and Paytm. Fixes merchant names the generic
  parser mangled (`VPA swiggy` → `swiggy`), which matters because the merchant
  name is the learning key. Cannot create or veto a transaction by design.
- **Category suggestions** — Naive Bayes trained only on your own confirmed
  history, shown as an explained chip you still have to tap. Abstains rather
  than guessing.
- **Adaptive confidence** — thresholds move against your measured correction
  rate, bounded, and never override settings you chose yourself.

## Reliability

- **WorkManager fallback** — a 6-hourly inbox sweep for devices where the SMS
  broadcast never fires (MIUI/ColorOS "Autostart") or the process was dead.

## Testing

240 automated tests, up from 111. Browser QA: 0 failures.

---

# SpendWise 1.0

The first production release. Fully offline, on-device personal finance for
Indian UPI users.

## Highlights

- **Automatic SMS capture** — bank & UPI messages become transactions the
  moment they arrive; nothing to paste. Offline queue guarantees no message is
  lost while the app is closed, and reference/content-hash dedup guarantees
  none is counted twice.
- **Merchant learning** — a weighted-confidence engine resolves raw SMS
  merchant strings to real merchants, learns from every confirmation and
  correction, and explains its score (past mapping, amount, category,
  corrections, time-of-day).
- **One-tap categorisation** — unknown merchants ask once with a popup;
  the answer teaches the engine permanently.
- **Budgets** — monthly limit per category with progress bars, over-budget
  states and insights.
- **Monthly report** — spend vs last month over the same days, day-by-day
  bars, category deltas (including categories you *stopped* spending on),
  top merchants, savings rate.
- **Upcoming bills** — recurring charges (≥3 at a weekly/monthly/quarterly
  cadence, stable amount) predicted with due-date pills and due-soon insights.
- **Streaks** — no-spend streaks and free-days gamification (only once
  there's real history).
- **Fraud alerts** — duplicates, high-value anomalies, unusual activity.
- **Activity** — timeline with per-day totals, filter chips, search, inline
  editing, undo delete, merchant drill-down pages.
- **First-run experience** — compact setup checklist (SMS permission, first
  transaction, budget) and name personalisation.
- **Data ownership** — one-tap CSV export; everything stays on the device.

## Platform

- Android 7.0+ (API 24), arm64-v8a / armeabi-v7a / x86 / x86_64
- Edge-to-edge on Android 15, display cutouts, keyboard-safe insets,
  theme-aware status bar, light/dark/system themes
- Embedded Python 3.11 (Chaquopy) + Flask served on loopback; cleartext
  restricted to 127.0.0.1

## Security

- Per-install device token on ingest endpoints (co-installed apps can't post)
- Cross-origin POST rejection; device endpoints disabled in multi-user web mode
- Unique dedup index closes ingest races; CSV export formula-injection safe
- No network egress, no analytics, no cloud

## Quality

- 51 end-to-end Python tests, green in CI
- Two adversarial multi-agent review campaigns (design, analytics
  correctness, backend security, Android platform, product) — all confirmed
  findings fixed and re-verified
