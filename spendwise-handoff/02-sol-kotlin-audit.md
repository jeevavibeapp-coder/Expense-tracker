# Sol report: independent audit of the Kotlin port at 6bd77fb5

Verbatim copy of Sol 6.1 Ultra's report. It audited the committed snapshot 6bd77fb5, not the later
commits on claude/native-hardening. Links point at Sol's temporary export, which other processes
can't read; the same files exist in the repo at the same relative paths.

---

I audited **`origin/claude/android-app-apk-build-S2BI6` at `6bd77fb5`**, using an exported copy in a temporary directory. I did not check out a branch or edit the shared folder. The working tree now contains concurrent changes, so the findings below apply to that committed snapshot, not those uncommitted edits.

I compiled and ran the actual Kotlin core. **All 17 existing core test programs passed: 1,521 assertions.** My additional cases still reproduced several defects. Android lifecycle/UI behavior is traced from source; I did not run an APK.

Reproducible evidence is in the temporary export:

- [Audit harness and complete input strings](C:/Users/2299004/AppData/Local/Temp/spendwise-kotlin-audit-9zk27_xv/AuditHarness.kt)
- [Actual Kotlin results](C:/Users/2299004/AppData/Local/Temp/spendwise-kotlin-audit-9zk27_xv/audit-harness-results.tsv)
- [Existing test-suite results](C:/Users/2299004/AppData/Local/Temp/spendwise-kotlin-audit-9zk27_xv/audit-test-results.json)
- [Deduplication checks against the exported Room schema](C:/Users/2299004/AppData/Local/Temp/spendwise-kotlin-audit-9zk27_xv/audit-sqlite-results.json)

For the pipeline tests, I supplied a known HDFC sender, default thresholds, no sender history, and deterministic lookups using `Engine`'s seed table. I checked persistence consequences separately against SQLite and by tracing `Repo.kt`; these were not Android Room instrumentation tests.

**Results for the earlier findings 9–14**

| Earlier case | Actual Kotlin result | Verification |
|---|---|---|
| Amount-bearing OTP: `Use OTP 123456 … purchase of Rs.5,000.00 at AMAZON…` | `matched=false`, `looksTransactional=false`; pipeline returns `NotFinancial`. | **Ran. Fixed for this input.** |
| Failed UPI payment containing "No amount has been debited" | Amount ₹500 extracted, but `matched=false`; `NotFinancial`. | **Ran. Fixed for this input.** |
| Completed refund: `Rs.500.00 refunded to your A/c XX1234 on 18-Nov-2026.` | Type remains `expense`; `looksTransactional=false`; `NotFinancial`. The refund is not captured. | **Ran. Still incorrect, now as a missed refund.** |
| Original credit/refund sentence mentioning "amount paid," without an account identifier/reference | Type remains `expense`, but the account-evidence gate rejects it; `NotFinancial`. | **Ran. It no longer reaches the ledger for that exact input.** |
| Same mixed refund with `account XX1234` and `UTR 123456789012` | `matched=true`; pipeline captures a ₹500 **expense**, `needs_review`. | **Ran. Direction bug remains.** |
| Account-first debit ending `to ZOMATO Ref 883120114455 UPI` | Merchant is now `ZOMATO`; resolved to `Zomato`, confidence 90, confirmed. | **Ran. Earlier sample fixed.** |
| SMS time `23:30:45` | Produces `2026-11-18T23:30`; seconds are dropped. It is no longer falsely labelled UTC by the core parser. | **Ran. Partial improvement.** |
| Invalid SMS time `25:61` | Silently becomes `23:59` and is captured. | **Ran. Changed from an exception to invented valid time.** |
| Two learned `KUMAR` candidates with identical evidence | Each independently scores 100. However, Room now enforces one learning row per `rawName`, so the old two-candidate selection scenario cannot be represented through this schema. | **Ran scoring; traced schema/resolver. Old tie-selection finding does not carry over literally.** |
| Auto-save strengthens its own confirmation count | The SMS capture branch no longer calls `recordConfirmation()`. | **Traced. Earlier feedback-loop finding addressed.** |
| Import the identical referenced SMS twice | Same key; SQLite accepts the first row and ignores the second. | **Ran core key generation and SQLite constraint check. Fixed for identical input.** |
| ₹500 credit followed by ₹500 unresolved expense | `Fraud.evaluate()` returns `[]`. | **Ran. Credit-versus-expense false positive fixed.** |
| ₹500 expense at one merchant followed by an unrelated unresolved ₹500 expense | Still returns a duplicate-charge alert. | **Ran. Unresolved-merchant false positive remains.** |

I also ran representative SBI, ICICI, Axis and Kotak forms. They all passed the transaction gate and resolved their expected merchants. One important qualification: the Axis `UPI/P2M/519023481234/ZOMATO/...` form returned **`referenceNumber=null`**, which contributes to finding K3 below. These are regression inputs, not certification against every current bank template.

The sender checks also ran: a known HDFC header was accepted, an unknown but correctly shaped DLT header was accepted, and a personal mobile number carrying the bank message was quarantined.

**K1. High — completed transactions are rejected by descriptive words. Verified by execution.**

**Where:** [Parsing.kt:82](core/src/main/kotlin/com/jeevavibeapp/spendwise/core/Parsing.kt:82), `Pipeline.kt:80`, `ParseMisses.kt:101`.

**Inputs → actual results:**

- `Cashback of Rs.100.00 credited to your account XX1234. Ref 123456789012.` → valid amount/income/account evidence, but **`NotFinancial`**.
- `INR 399.00 debited from your account XX1234 for mobile recharge. UPI Ref 123456789012.` → **`NotFinancial`**.
- `Rs.500.00 credited to account XX1234 for reversed UPI transaction. Ref 123456789012.` → **`NotFinancial`**.

The unconditional `cashback`, `recharge` and `reversed` exclusions reject completed movements. These cases also bypass the unreadable-message log: they already have `looksTransactional=true`, so `ParseMisses.judge()` returns null.

Separately, plain `refunded` is absent from `TXN_VERB`, so the earlier completed-refund sample never reaches capture.

**Smallest fix:** make exclusions depend on transaction status and clause meaning. Retain rejection of pending/failed requests, but accept explicit completed debits/credits and recognize completed refunds. Add paired positive and negative tests for each keyword.

**K2. Medium — a completed refund can still be saved as an expense. Verified by execution.**

**Where:** [Parsing.kt:292](core/src/main/kotlin/com/jeevavibeapp/spendwise/core/Parsing.kt:292).

**Input:**

```text
INR 500.00 credited to account XX1234 as refund for the amount paid to AMAZON. UTR 123456789012.
```

**Actual:** ₹500, `type=expense`, `matched=true`; pipeline returns `Capture` with `needs_review`. The extracted merchant is also an account fragment: `account XX1234 as`.

**Smallest fix:** derive direction from the clause describing movement in the customer's account. Do not simply prefer every occurrence of "credited": the ICICI form "…account debited…; SWIGGY credited" correctly needs to remain an expense.

**K3. High — deduplication silently loses distinct payments and credits. Verified by Kotlin execution and SQLite insertion.**

**Where:** [Pipeline.kt:173](core/src/main/kotlin/com/jeevavibeapp/spendwise/core/Pipeline.kt:173), `Db.kt:34`, `Repo.kt:455–461`.

These three messages all generate:

```text
500.0|AMAZON||2026-11-18
```

- ₹500 debit to Amazon at 12:00.
- A separate ₹500 debit to Amazon at 18:00.
- ₹500 credit from Amazon at 12:05.

Against the exported Room schema, each two-message trial inserted **one row**, with the second insertion ignored. Time, direction, account and sender are absent from the key.

The Axis path makes this more serious: its embedded UPI reference is not extracted, so even messages containing distinct references can fall into the reference-free collision case.

**Smallest fix:** extract path-style UPI references and use a reference identity scoped to account/source and direction. Without a reliable reference, deduplicate repeated delivery of the *same SMS* using stable message evidence; do not make amount/merchant/day a unique payment identity.

**K4. Medium — first-word seed matching assigns unrelated merchants with automatic confidence. Verified by execution.**

**Where:** [Engine.kt:110](core/src/main/kotlin/com/jeevavibeapp/spendwise/core/Engine.kt:110), `Repo.kt:640`.

**Input:**

```text
INR 450.00 debited from account XX1234 to AMAZON CAFE on 18-Nov-2026 Ref 123456789012.
```

**Actual:** raw merchant `AMAZON CAFE` → seed lookup `Amazon` → **confidence 90, confirmed**.

A local café sharing a brand's first word is not sufficient identity evidence.

**Smallest fix:** reserve automatic seed resolution for complete, curated aliases. First-token matches can be suggestions with confirmation, rather than 90-confidence assignments.

**K5. Medium — unresolved merchants still produce unrelated duplicate-charge alerts. Verified by execution.**

**Where:** [Fraud.kt:99](core/src/main/kotlin/com/jeevavibeapp/spendwise/core/Fraud.kt:99), `Repo.kt:507`.

**Scenario:** ₹500 expense at `Grocery Shop` at noon, then an unrelated ₹500 expense with no resolved merchant at 12:02 → "Possible duplicate … twice within 10 minutes."

The expression `name == null || …` disables the merchant comparison precisely when merchant evidence is missing. `Repo.fraudTx()` does not supply raw counterparty evidence.

**Smallest fix:** require comparable counterparty evidence for a duplicate-charge claim. If the merchant is unknown, either omit that detector or use a distinctly weaker, accurately worded signal.

**K6. Medium — inbox recovery permanently stops behind the newest 500 messages. Verified from code; device scenario traced.**

**Where:** [SmsIntake.kt:81](android/nativeapp/src/main/java/com/jeevavibeapp/spendwise/sms/SmsIntake.kt:81), especially line 99.

**Scenario:** a bank debit is the 501st newest SMS, only five days old; newer messages include OTPs, promotions and personal messages → the scanner never reaches it. Every subsequent launch starts with the newest messages again, preserving the gap despite the 90-day lookback.

**Smallest fix:** paginate the requested date range using a stable cursor, or persist scan progress and complete successive bounded batches. Saving only the newest timestamp after the first capped batch would leave the same hole.

**K7. Medium — backup import discards timezone information and rejects valid negative-offset timestamps. Verified by execution.**

**Where:** [Backup.kt:699](core/src/main/kotlin/com/jeevavibeapp/spendwise/core/Backup.kt:699), `Repo.kt:37`.

**Actual import results:**

- `2026-11-18T00:30:00Z` → local `2026-11-18T00:30`, instead of `06:00` IST.
- `2026-11-18T00:30:00+05:30` → local `00:30`, after discarding the offset.
- `2026-11-18T00:30:00-05:00` → transaction rejected; dropped count increases.

The native exporter writes offset-free local times, so its ordinary same-zone round trip avoids this. However, the importer deliberately supports older Python files, and the original Python app stored timezone-aware UTC timestamps.

**Smallest fix:** parse offset-bearing timestamps as `Instant`/`OffsetDateTime`, then convert explicitly to the intended zone. Preserve the existing local-time path for offset-free native backups. Add an actual timezone-aware Python fixture; the current legacy test uses offset-free strings.

**K8. Medium — backup document reads/writes block the UI thread, with no read-size bound. Verified from code; ANR/memory exhaustion not device-tested.**

**Where:** [NativeMainActivity.kt:337](android/nativeapp/src/main/java/com/jeevavibeapp/spendwise/NativeMainActivity.kt:337), lines 1212 and 1236.

**Scenario:** choose a large file or a slow cloud-backed document → the default-main-thread `lifecycleScope.launch` calls synchronous `readDocument()`, which uses unbounded `readBytes()`. Export similarly calls synchronous `writeDocument()` after `backupText()` returns.

A coroutine alone does not move this I/O off the main thread.

**Smallest fix:** perform document I/O inside `withContext(Dispatchers.IO)`, impose a byte limit while streaming, and return a clear rejection before loading an oversized file into memory.

**SMS intake, permissions and process death**

The previous native-shell problems do **not** carry over wholesale:

- **Multipart:** the receiver joins PDU bodies before ingestion and uses the supplied PDU format. I verified this from source, not with injected binary PDUs.
- **Process death:** it uses the application context and `goAsync()`; it does not depend on an activity/plugin instance. A manifest receiver can start the process. The comment claiming it never fires while the process is dead is inaccurate.
- **Logging:** the direct sender/body log is gone. The remaining calls log generic failures plus exceptions.
- **Permissions:** `NativeMainActivity.kt:94` uses a real permission request contract; lines 1245–1261 inspect the declared and granted permissions. The no-SMS variant removes the permissions and receiver.
- **`READ_SMS` is now needed for an implemented feature:** `InboxScanner` queries `content://sms/inbox` for historical/catch-up messages. `RECEIVE_SMS` alone covers live delivery, not that provider query.

I have not measured whether database opening/recovery and ingestion always finish within the broadcast execution window, so I am not reporting a timeout defect as verified.

**Room and backup answers**

Room has a **schema version and an explicit migration path**:

- `Db.kt:619`: version **4**, `exportSchema=true`.
- `AppGraph.kt:140`: registers **1→2, 2→3 and 3→4**.
- `AppGraph.kt:333`: passes those migrations to Room; no destructive fallback.
- Exported schemas 3 and 4 are committed.
- I executed the **3→4 SQL** against schema 3 in isolated SQLite and compared tables, columns and indexes with exported schema 4: **they matched**. I did not run Android `MigrationTestHelper` or exercise actual installed v1/v2 databases.

`Backup.kt` writes **plaintext JSON, not an encrypted archive**. It contains transactions—including soft-deleted rows—categories/budgets, merchants, learning, sender registry/trust and preferences. It excludes raw SMS bodies, quarantine messages and parse misses.

The manual backup is written through Android's document picker, with a suggested `spendwise-backup-YYYY-MM-DD.json` filename. Its final location is the URI/provider the user selects, not a hardcoded public directory. Private recovery snapshots are a separate mechanism under the app's database directory. The manifest sets `allowBackup=false`.

Plaintext, deliberate user export is not by itself a vulnerability; the UI should accurately describe its contents and lack of encryption.

The most useful missing regressions are the completed cashback/recharge/reversal cases, mixed-clause refunds paired with the ICICI debit form, Axis path references, same-day distinct-payment and debit/credit deduplication, unrelated unresolved merchants, timezone-aware backup fixtures, and recovery of a financial SMS beyond the first 500 inbox rows. Those cases are not protected merely by the current 1,521 passing assertions.

Task 2 remains separate: I have not reviewed the concurrent working-tree changes or assigned verdicts to them.
