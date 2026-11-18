# Opus report: Steps 1, 2 and 4 (written before seeing Sol's Kotlin audit)

Verbatim copy of Claude Opus 5.5's report. Links are relative to the repo root.

---

I ran Steps 1, 2 and 4 and fixed two findings, but your answers came through mostly blank, and nothing reached CI.

- **Your answers were mostly unfilled.** Q2–Q4 still have their bracketed placeholders, and Sol's report wasn't pasted. So the table below only covers the items you named in your message.
- **The push failed** with a 403. Git on this machine is logged in as GitHub user `jeeva-dev06`, which has no write access to the repo. My 3 commits are local only; CI hasn't run on them, and `main` is untouched at 2fa8dc0.
- **I didn't change the keystore.** You didn't say what's on your phone, so I followed your rule and left the key alone.

## Step 1: baseline

All 17 core suites pass: **1,521 checks, 0 failed** at 6bd77fb5. I ran `core/run-tests.sh` unmodified, using the same tools as CI (JDK 17.0.21 and kotlinc 2.1.0), installed only in the scratchpad.

## Step 2: status of the items you named

| Item | Status | Evidence |
|---|---|---|
| OTP / failed / reversed rejection | **FIXED ON BRANCH** | [Parsing.kt:82-97](core/src/main/kotlin/com/jeevavibeapp/spendwise/core/Parsing.kt#L82-L97). Tested by the junk corpus in `ParsingTest.kt` and my 6 probes (commit 7223ef06), which use your style of sample inputs, including "failed" and "declined" wording. |
| Account masks | **OPEN, now fixed** in 424413a0 | Masks were never read as the amount. But an SMS with no payee got `A/c XX1234` or `your` as its payee, so one confirmation taught the app to file every payee-less debit from that account the same way. |
| Duplicate checks only on expenses | **FIXED ON BRANCH** | [Fraud.kt:85](core/src/main/kotlin/com/jeevavibeapp/spendwise/core/Fraud.kt#L85), [Repo.kt:485](android/nativeapp/src/main/java/com/jeevavibeapp/spendwise/data/Repo.kt#L485). Tested in `FraudTest.kt:36-61`. |
| Dedup key | **FIXED for the same message seen twice** (`PipelineTest.kt:141-160`). **OPEN for a new case:** see new problem 1 below. | |
| #3 committed keystore | **OPEN, blocked on Q3** | `spendwise.jks` is still committed. Release builds fall back to it whenever the `SPENDWISE_KEYSTORE_*` variables are empty, in both [app/build.gradle:118-122](android/app/build.gradle#L118-L122) and [nativeapp/build.gradle:65-68](android/nativeapp/build.gradle#L65-L68). |
| #6 multipart SMS and process death | **Fixed in code, not proven by any test** | All parts are joined before parsing ([SmsIntake.kt:37-46](android/nativeapp/src/main/java/com/jeevavibeapp/spendwise/sms/SmsIntake.kt#L37-L46)). If the process dies, the launch-time inbox rescan recovers the SMS ([SmsIntake.kt:78-127](android/nativeapp/src/main/java/com/jeevavibeapp/spendwise/sms/SmsIntake.kt#L78-L127)). The JVM can't run the receiver, and the emulator test only sends one short SMS and never kills the app while it arrives. |
| #7 SMS logging | **FIXED ON BRANCH**, no test | Every log call in both Android modules is a fixed string plus an exception ([SmsIntake.kt:55](android/nativeapp/src/main/java/com/jeevavibeapp/spendwise/sms/SmsIntake.kt#L55), [:124](android/nativeapp/src/main/java/com/jeevavibeapp/spendwise/sms/SmsIntake.kt#L124), [SmsReceiver.java:125](android/app/src/main/java/com/jeevavibeapp/spendwise/SmsReceiver.java#L125), [:219](android/app/src/main/java/com/jeevavibeapp/spendwise/SmsReceiver.java#L219)). The Python app has no logging calls. I checked this by searching the code. |
| #9 refund that also says "paid" | **OPEN, now fixed** in 47dece72 | All 3 sample refunds were filed as expenses, which doubled the spend they should cancel. |
| #11 timezones | **FIXED ON BRANCH** | The core logic stores no timezone. Conversion uses the phone's timezone ([Repo.kt:37-41](android/nativeapp/src/main/java/com/jeevavibeapp/spendwise/data/Repo.kt#L37-L41)). My 4 checks for 00:10–00:30 just after midnight all pass. |
| #12 merchant ties | **FIXED ON BRANCH** by design (my reading) | There is one learned mapping per payee name (unique index at [Db.kt:77](android/nativeapp/src/main/java/com/jeevavibeapp/spendwise/data/Db.kt#L77)), so two can't tie. An exact tie in the category guesser declines rather than guessing (tested). Without Sol's report I'm guessing what #12 meant. |

## Step 3: commits on `claude/native-hardening` (local only)

1. **7223ef06** adds the probes that already pass, as regression checks.
2. **424413a0** stops account references (`a/c`, `acct`, `your`, XX or `**` masks) being taken as the payee.
3. **47dece72** files a refund with a credit verb as income, unless the message also says "debited" or "withdrawn".

**Both fixes would have duplicated rows on existing phones.** Each launch, the app rescans 90 days of SMS and matches messages it has already saved by recomputing a key that includes the payee and direction. Changing those would re-file every affected message. So the key now keeps reading the payee the old way, through a separate field, and the tests pin the exact keys produced by the unmodified 6bd77fb5 code. I didn't bump `PARSER_VERSION`, because it only tracks which messages count as unreadable, and that hasn't changed.

**Test results.** Before the fixes, the new tests failed as expected:
```
FAIL  payee from: Rs.100.00 debited from A/c XX1234 on 08-07-26  [want=null got=A/c XX1234]
FAIL  payee from: Rs.5,000.00 credited to your A/c XX9012 on 08  [want=null got=your]
FAIL  payee from: INR 62,500.00 credited to A/c XX1234 on 01-07  [want=null got=A/c XX1234]
26 passed, 3 failed
FAIL  income: Refund of Rs.500.00 for your order paid via UPI ha  [got=expense]
FAIL  income: Rs.250.00 credited to A/c XX1234 on 08-07-26 as re  [got=expense]
FAIL  income: You have received a refund of Rs.120 for the bill   [got=expense]
43 passed, 3 failed
```
After the fixes, the full suite gives exit 0, **1,567 checks, 0 failed**:
```
AuditTestKt 46 · BackupTestKt 94 · BudgetsTestKt 70 · CategoriesTestKt 53 · EngineTestKt 58
FraudTestKt 46 · InsightsTestKt 50 · ManualEntryTestKt 27 · MerchantBookTestKt 72
ParseMissesTestKt 538 · ParsingTestKt 70 · PasteTestKt 95 · PipelineTestKt 37 · PromptsTestKt 25
RecoverySequenceTestKt 124 · RecoveryTestKt 92 · ReviewTestKt 31 · SenderBookTestKt 39   (all "0 failed")
```
`tools/typecheck.sh` also reports "no errors in 15 app source files", so the Android code still compiles against the changed core.

## Step 4: READ_SMS

**Keep it.** Both SMS builds read the inbox on launch to catch messages that were missed: [NativeMainActivity.kt:106](android/nativeapp/src/main/java/com/jeevavibeapp/spendwise/NativeMainActivity.kt#L106) and [SmsInboxScanner.java:57](android/app/src/main/java/com/jeevavibeapp/spendwise/SmsInboxScanner.java#L57). The no-SMS builds already remove the permission, so no change was needed.

## New problems I found but didn't fix

1. **Two identical spends on the same day become one row.** Card and ATM alerts usually have no reference number. Two ₹2,000 ATM withdrawals on one day produce the same key, and the second is silently dropped. Fixing it means changing how the key is built, including the SMS's own timestamp, and staying compatible with keys already stored. I'd like your approval of that design first.
2. **An SMS arriving just before midnight can be filed twice.** If it carries no date, the live capture and the next rescan can land on different days. It's rare, and the fix above solves it too.
3. **"SWIGGY INSTAMART" is filed as Swiggy / Food & Dining** instead of Instamart / Groceries ([Engine.kt:110-114](core/src/main/kotlin/com/jeevavibeapp/spendwise/core/Engine.kt#L110-L114)). It's a one-line fix if you want it.
4. **A reversal SMS is ignored.** If the original debit was captured, the expense stays in the ledger. Whether a reversal should cancel the debit is your call.

Rows already saved (wrong payees, refunds filed as expenses) aren't rewritten by these fixes.

## What only CI or a real phone can verify

- **CI:**
  - The core tests on Linux.
  - A real Gradle build of `:app` and `:nativeapp` (my local check used stubs, not the real build tools).
  - The emulator smoke test.
- **Your phone:**
  - SMS arriving on your phone's brand (some brands need Autostart enabled).
  - Long, multipart messages.
  - Recovery after the app is killed.
  - Installing past Play Protect.
  - Your banks' real messages against both fixes.
  - The phone-timezone conversion.
  - Whether the Flask backup (format 1) imports into the native app.

## What I'd delete later (for your approval)

- **React web app:** the root React files and `components/`, `package*.json`, `server.ts`, `vite.config.ts`, `tsconfig.json`, `capacitor.config.ts` and `webshell/`.
- **Capacitor/Chaquopy app:** `android/app/`, its Capacitor and Chaquopy Gradle wiring, and `CHAQUOPY_NOTES.md`.
- **Python app:** `python_app/` and its CI workflow.
- **CI steps:** the Node, Python and `:app` steps in `android.yml`.
- **Old keystore:** `spendwise.jks`. The native build points at `../app/spendwise.jks`, so signing has to move before `android/app/` can go.

## Questions

1. Can you paste Sol's report and fill in Q2–Q4?
2. What's on your phone, and does it hold real data? That decides the keystore. Also, are the `SPENDWISE_KEYSTORE_*` GitHub secrets set? If not, every CI release APK so far was signed with the committed key.
3. Can you push the branch yourself (`git push -u origin claude/native-hardening`), or give `jeeva-dev06` write access?
4. Should I redesign the dedup key (new problems 1 and 2), fix Instamart (3), and make reversals cancel the original debit (4)?
5. Should I add emulator tests for #6 (a long multipart SMS, and an SMS sent while the app is killed)? They'd only run once the branch is pushed.

I saved this session's decisions to memory: architecture (c), the dedup-key rule, and the push and JDK setup.
