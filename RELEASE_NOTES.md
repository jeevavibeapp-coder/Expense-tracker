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
