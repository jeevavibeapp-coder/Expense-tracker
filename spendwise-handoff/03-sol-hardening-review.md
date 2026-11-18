# Sol review: native hardening through 47dece72

## 1. Scope

Reviewed these immutable commits, not the concurrent working tree:

- Base: 6bd77fb5e104a5911b0c50a12af2e171973cb689.
- 7223ef063a9cc7e13b93dab00f9c0e91e4994d2d.
- 424413a08a30660b1e59419938e9a27616234478.
- 47dece72ab90a6f6a8a137a89ced08134fdb2124.

The shared repository was read-only. I used git show/archive and a temporary export, saved the attached instructions verbatim, read the handoff files, and copied the previous harness/results into sol-harness. The saved instruction bytes match the attachment.

I compiled the Kotlin core with compiler 2.1.0 and Java 17.0.21. At 47dece72, all **18 shipped test runners passed: 1,567 checks, zero failures**. I reran the baseline binaries: **17 runners, 1,521 checks, zero failures**. I also reran the original AuditHarness, added independent regressions, ran 10,000 deterministic mutations, compared old/new keys, and executed SQLite constraints and migration SQL. The 15 native source files compiled against the repository's handwritten stubs. That is not a Gradle/SDK/KSP/Compose-plugin build.

No APK, emulator, physical phone, TalkBack, OEM power-management behavior, or real Room MigrationTestHelper test was run. No real SMS corpus was supplied; **every SMS here is synthetic**. Specialist passes covered claims, parser, data integrity, Android lifecycle, security, UX and release criteria. Only root-executed results are labelled VERIFIED.

Evidence: [all run summaries](sol-harness/47dece72/summary.json), [core commands/output](sol-harness/47dece72/evidence/commands-and-results.json), [independent commands/output](sol-harness/47dece72/extra-evidence/commands-and-results.json), [pinned source with line numbers](sol-harness/47dece72/verification/source-evidence-47dece72.txt).

## 2. Claim checks against 01-opus-steps-1-4.md

| Exact claim being checked | Result | Evidence and scope |
|---|---|---|
| ?All 17 core suites pass: 1,521 checks, 0 failed? at 6bd77fb5 | Confirmed | Fresh execution of the preserved baseline classes: 17 / 1,521 / 0. |
| ?OTP / failed / reversed rejection ? FIXED ON BRANCH? | Confirmed only for the six negative probes; refuted as K1 closure | Those probes pass. Completed reversal credits, cashback and recharge still return NotFinancial; plain refunded still misses the gate. |
| ?Account masks ? OPEN, now fixed? | Partly confirmed | The committed examples stop producing account fragments. Exact K2 input now loses its real later payee; S-001 is a new overbroad-filter regression. |
| ?Duplicate checks only on expenses ? FIXED ON BRANCH? | Confirmed narrowly | Income followed by equal-value expense produces no duplicate alert. K5, involving two expenses and an unresolved payee, remains. |
| Dedup ?FIXED for the same message seen twice? | Confirmed narrowly | SQLite accepts the first copy and ignores the replay. Distinct payments and debit/credit pairs are still incorrectly treated the same way. |
| ?#3 committed keystore ? OPEN? | Confirmed by source | Native release still selects devDebug when the signing environment is absent; R-1 remains open. No APK signature was tested. |
| Multipart/process death: ?Fixed in code, not proven by any test? | Partly confirmed / cannot reproduce device behavior | Assembly and goAsync are present; no device proof. Recovery cannot be complete while K6 caps the scan. |
| ?#7 SMS logging ? FIXED ON BRANCH, no test? | Confirmed as a source observation only | Intake logs fixed labels plus exceptions; no direct body logging found in this path. No runtime logcat/privacy claim is made. |
| ?#9 refund that also says paid ? OPEN, now fixed? | Confirmed for all three committed examples; incomplete generally | Reproduced 43 passed / 3 failed before the direction change; all three pass afterward. K2's merchant and historical-debit wording remain problematic. |
| ?#11 timezones ? FIXED ON BRANCH? | Refuted as closure of K7/T-1 | Four local-clock/budget assertions pass. They do not test offsets; UTC import is shifted, negative-offset import drops a row, seconds are lost and invalid times are clamped. |
| ?#12 merchant ties ? FIXED ON BRANCH by design? | Confirmed for the old multi-row representation | A unique rawName mapping removes that exact tie setup. It does not resolve K4 or prove all learning semantics correct. |
| ?key includes the payee and direction? | Inaccurate description | Actual serialization is amount \| legacy merchant \| reference \| date. Direction affects extraction order indirectly; it is not an identity component. |
| Frozen keyMerchant/keyType retain old keys | Confirmed for ten fixtures | Actual baseline/current key outputs match 10/10. Old-row/new-key SQLite replay leaves one row in all ten cases. This is not full Android upgrade certification. |
| Historical ?26 passed, 3 failed? account run | Cannot reproduce that exact count from committed test | The committed 424413a0 test on pre-fix production gives **29 passed, 3 failed**; same three failures, plus three committed key checks. The exact earlier uncommitted test snapshot is unavailable. |
| Historical ?43 passed, 3 failed? refund run | Confirmed | Replayed current AuditTest against the 424413a0 parser. |
| Latest ?1,567 checks, 0 failed? | Confirmed | 18 shipped runners, 1,567 / 0. Independent failures below are separate. |
| Typecheck ?no errors in 15 app source files? | Confirmed with a scope limit | Equivalent direct Kotlin invocation compiled all 15 native files and the checked-in stubs, exit 0. Real Android build/CI status remains unverified. |
| ?READ_SMS ? Keep it? | Confirmed for this implementation | InboxScanner queries the SMS provider. READ_SMS serves this history read; RECEIVE_SMS serves broadcasts. |

[Android permission reference](https://developer.android.com/reference/android/Manifest.permission#READ_SMS) supports the permission distinction. [BroadcastReceiver documentation](https://developer.android.com/reference/android/content/BroadcastReceiver#goAsync()) documents bounded asynchronous work; goAsync is not an unlimited execution guarantee. Play's [SMS permission policy](https://support.google.com/googleplay/android-developer/answer/10208820) lists SMS-based money management among exception use cases, but this app's declaration/approval was not inspected.

## 3. Per-commit verdicts

| Commit | Verdict | Reason |
|---|---|---|
| 7223ef06 | **Approve with nits** | Useful negative-input and boundary regressions. Describe their scope accurately. The local-clock test currently expects seconds to disappear; update it when T-1 is fixed. It is not offset-import coverage. |
| 424413a0 | **Request changes** | Account-fragment rejection helps and old keys are preserved, but the filter rejects legitimate names beginning with ?YOUR? (S-001), and a rejected first candidate prevents extraction of a later valid payee. |
| 47dece72 | **Request changes** | Fixes the three ?paid? examples and preserves tested legacy keys. ?Debited earlier? still defeats a completed refund credit. Resolve K2 by identifying the account movement, retaining the ICICI debit control. |

I found no demonstrated Android compilation regression from these three changes. A passing stub compile is insufficient to approve the Android CI build.

## 4. New findings

All findings below apply to **47dece72ab90a6f6a8a137a89ced08134fdb2124**.

### S-002 ? medium ? VERIFIED calculation, source-traced UI binding: light-theme labels fail contrast

- **Where:** android/nativeapp/src/main/java/com/jeevavibeapp/spendwise/ui/SettingsAndOnboarding.kt:1077; Theme.kt:41, :43, :55, :108.
- **Exact input/action:** Appearance = Light; open Settings ? Smart engine with thresholds 80 and 50.
- **Expected:** normal text meets the requested AA minimum 4.5:1.
- **Actual:** the 14sp labels use Income #36D39A and Warn #FBBF24 on #FFFFFF: **1.919354:1 and 1.669364:1**. These are active informational labels, not disabled controls. No device rendering was claimed.
- **Smallest fix:** use light-theme semantic text colors with sufficient contrast, or use the normal on-surface text color for the labels.
- **Proof test:** SolContrastTest.kt calculates the current bound colors; two failures. Follow with actual light/dark accessibility UI checks after the palette/binding fix.

The threshold and source-color method follow [W3C contrast guidance](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html). [Source](sol-harness/47dece72/verification/source-SettingsAndOnboarding.kt.txt), [theme](sol-harness/47dece72/verification/source-Theme.kt.txt), [calculation and commands](sol-harness/47dece72/verification/contrast-commands.json).

### S-001 ? low ? VERIFIED: account filter removes a legitimate merchant

- **Introduced by:** 424413a0.
- **Where:** core/src/main/kotlin/com/jeevavibeapp/spendwise/core/Parsing.kt:134 and :286.
- **Exact synthetic input:** `INR 600.00 paid to YOUR SALON on 18-Nov-2026 from account XX1234. UTR 123456789012.`
- **Expected:** expense, rawMerchant = YOUR SALON.
- **Actual:** baseline returns YOUR SALON; current returns null, while matched remains true. The retained keyMerchant still says YOUR SALON.
- **Smallest fix:** reject bare ?your? and ?your <account/card reference>?; do not reject every merchant starting with that word.
- **Proof test:** SolRegressionTest.kt assertion S-001-your-merchant; the old/new probe also demonstrates the regression.

### S-003 ? low ? TRACED: backup export omits the required plaintext warning

- **Where:** android/nativeapp/src/main/java/com/jeevavibeapp/spendwise/ui/SettingsAndOnboarding.kt:432 and :641; NativeMainActivity.kt:312.
- **Exact input/action:** open either Settings or Privacy ? Download a full backup ? choose a document destination.
- **Expected:** before export, disclose that the file is unencrypted and contains financial history, as M4 explicitly requires.
- **Actual:** both routes discuss keeping a backup, but neither explains plaintext sensitivity. The implementation writes ordinary JSON through the selected provider. This is a disclosure gap, not an allegation that deliberate plaintext export is inherently a vulnerability.
- **Smallest fix:** a shared pre-export note/dialog saying the backup is unencrypted and should be stored privately.
- **Proof test:** a Compose test must visit both entry points and assert disclosure before the document launcher is invoked. This device/UI test was not run; it is specified in ANDROID_SCENARIOS.md.

## 5. Earlier findings and leftovers

All file references and results in this section are at **47dece72**. VERIFIED means execution of core/SQLite, not Android instrumentation.

### K1 ? high ? VERIFIED ? untouched

**Where:** core/src/main/kotlin/com/jeevavibeapp/spendwise/core/Parsing.kt:80, :88; core/src/main/kotlin/com/jeevavibeapp/spendwise/core/Pipeline.kt:80.

Exact synthetic inputs still rejected as NotFinancial:

- `Cashback of Rs.100.00 credited to your account XX1234. Ref 123456789012.`
- `INR 399.00 debited from your account XX1234 for mobile recharge. UPI Ref 123456789012.`
- `Rs.500.00 credited to account XX1234 for reversed UPI transaction. Ref 123456789012.`
- `Rs.500.00 refunded to your A/c XX1234 on 18-Nov-2026.`

Expected: completed movements captured with the first/third/fourth as income and the second as expense. Actual: all four disappear from capture. **Smallest fix:** status/clause-aware exclusions and completed-refund recognition. **Proof:** K1-0 through K1-3 in SolRegressionTest; retain paired OTP/failed/pending negatives. This also covers O-4.

### K2 ? medium ? VERIFIED ? partly fixed

**Where:** core/src/main/kotlin/com/jeevavibeapp/spendwise/core/Parsing.kt:298 and :313.

Exact original input:

`INR 500.00 credited to account XX1234 as refund for the amount paid to AMAZON. UTR 123456789012.`

Expected: income, counterparty AMAZON. Actual: income is now correct and the account fragment is gone, but rawMerchant is null. The first TO candidate is rejected and the same regex is not searched again.

Additional synthetic input:

`INR 500.00 credited to account XX1234 as refund of the amount debited earlier. UTR 123456789012.`

Expected income; actual expense. **Smallest fix:** continue searching valid merchant candidates and distinguish the current credit clause from historical debit language. Preserve the ICICI ?account debited; merchant credited? expense control. **Proof:** K2-income passes; K2-merchant and K2-historical-debit fail.

### K3 ? high ? VERIFIED core + SQLite ? untouched; compatibility improved

**Where:** core/src/main/kotlin/com/jeevavibeapp/spendwise/core/Pipeline.kt:173; android/nativeapp/src/main/java/com/jeevavibeapp/spendwise/data/Db.kt:34; Repo.kt:455 and :461.

Exact synthetic inputs:

- `INR 500.00 debited from account XX1234 to AMAZON on 18-Nov-2026 at 12:00.`
- `INR 500.00 debited from account XX1234 to AMAZON on 18-Nov-2026 at 18:00.`
- `INR 500.00 credited to account XX1234 from AMAZON on 18-Nov-2026 at 12:05.`

Expected two rows for either distinct-purchase or debit/credit pair; actual one row. All keys are `500.0|AMAZON||2026-11-18`; inserts return [1, 0]. Identical replay also returns [1, 0], correctly.

Axis input `INR 500.00 debited from A/C XX1234 on 18-Nov-2026 UPI/P2M/519023481234/ZOMATO/UPI` still has referenceNumber = null.

**Smallest safe fix:** extract path references; introduce a versioned source identity using account/direction/reference, or original sender/timestamp/body for reference-free SMS. Keep the old key only for upgrade reconciliation, with an explicit paste policy. **Proof:** K3 assertions plus the saved schema/replay runner; add a real Room upgrade/replay test.

The freeze does not introduce a new demonstrated defect. Ten original keys remain identical, which protects those replays. It does mean a future K3 fix must separate legacy matching from new identity. Simply changing the current unique key duplicates historical imports; keeping the coarse key unique forever continues dropping legitimate payments. When multiple old SMS share one old key and the stored row has no original body/timestamp identity, automatic recovery cannot determine reliably which one was stored. Route such ambiguity to review instead of claiming lossless automatic repair. This covers O-1 and the identity portion of O-2.

### K4 ? medium ? VERIFIED core, repository path TRACED ? untouched

**Where:** core/src/main/kotlin/com/jeevavibeapp/spendwise/core/Engine.kt:110; android/nativeapp/src/main/java/com/jeevavibeapp/spendwise/data/Repo.kt:640.

Input `INR 450.00 debited from account XX1234 to AMAZON CAFE on 18-Nov-2026 Ref 123456789012.` ? seed Amazon / Shopping, confidence 90, confirmed in the harness. Expected: no automatic Amazon identity merely from the first word. SWIGGY INSTAMART similarly returns Swiggy / Food & Dining.

**Smallest fix:** curated full aliases; first-token similarity should not supply automatic identity confidence. **Proof:** K4-prefix and K4-instamart fail. This covers O-3.

### K5 ? medium ? VERIFIED ? untouched

**Where:** core/src/main/kotlin/com/jeevavibeapp/spendwise/core/Fraud.kt:99; android/nativeapp/src/main/java/com/jeevavibeapp/spendwise/data/Repo.kt:507.

Exact input objects: earlier Tx("other", 500.0, "expense", 2026-11-18T12:00, "Grocery Shop", null); incoming Tx("purchase", 500.0, "expense", 2026-11-18T12:02, null, null).

Expected no asserted same-merchant duplicate; actual duplicate alert. Replacing the earlier expense with an income correctly produces no alert. **Smallest fix:** require merchant/counterparty evidence before issuing this duplicate signal. **Proof:** K5-unresolved fails and K5-credit-control passes.

### K6 ? medium ? TRACED ? untouched

**Where:** android/nativeapp/src/main/java/com/jeevavibeapp/spendwise/sms/SmsIntake.kt:81 and :99.

Exact fixture specification: 500 newer nonfinancial inbox records with body "hello", plus one older record within 90 days containing K3's 12:00 debit and sender VM-HDFCBK. Open twice.

Expected the financial row is eventually visited and captured once; actual control flow stops after the same newest 500 records every time. **Smallest fix:** stable date/_id pagination with persisted progress and safe overlap. **Proof required:** provider-cursor and emulator >500-row tests; not executed here. See ANDROID_SCENARIOS.md.

### K7 ? medium ? VERIFIED ? untouched

**Where:** core/src/main/kotlin/com/jeevavibeapp/spendwise/core/Backup.kt:699; android/nativeapp/src/main/java/com/jeevavibeapp/spendwise/data/Repo.kt:37.

Each fixture is format-1 SpendWise JSON with one transaction of amount 100, type expense, and occurred_at/created_at both set to the following timestamp:

| Timestamp | Expected IST local time | Actual |
|---|---|---|
| 2026-11-18T00:30:00Z | 2026-11-18T06:00 | 2026-11-18T00:30 |
| 2026-11-18T00:30:00+05:30 | 2026-11-18T00:30 | 2026-11-18T00:30 |
| 2026-11-18T00:30:00-05:00 | 2026-11-18T11:00 | Transaction dropped |

The exact JSON construction is in SolRegressionTest.kt and AuditHarness.kt. **Smallest fix:** parse offset-bearing timestamps as instants/OffsetDateTime and convert to the declared zone; retain a separate floating-local path. **Proof:** K7-utc and K7-negative fail; positive-offset control passes.

### K8 ? medium ? TRACED ? untouched

**Where:** android/nativeapp/src/main/java/com/jeevavibeapp/spendwise/NativeMainActivity.kt:315, :337, :1212 and :1236.

Exact test input specification: a chosen document stream containing 104,857,600 spaces followed by {}; separately, a test document provider delaying each read/write chunk by two seconds.

Expected background, bounded I/O and responsive UI. Actual call chain does synchronous unbounded readBytes and stream writes on the default main dispatcher. No ANR/OOM was device-reproduced. **Smallest fix:** move stream I/O to Dispatchers.IO and enforce a byte cap while reading. **Proof required:** StrictMode plus delayed/oversized provider tests for both import and export; specified, not run.

### Remaining earlier items

- **T-1 ? medium, VERIFIED, untouched:** Parsing.kt:240?243. `Rs.100 paid to KFC on 18-Nov-2026 23:30:45 Ref 123456789012` loses :45; the same input with 25:61 becomes 23:59. Expected preservation of valid seconds and rejection/fallback for invalid time. Fix with strict time validation. Two direct failures and 2,000 invalid-time mutations prove the remaining defect.
- **L-1 ? low, TRACED, untouched:** SmsIntake.kt:66?68 still says a manifest receiver never fires when the process is dead. Fix the comment; [Android documents manifest receiver process launching](https://developer.android.com/reference/android/content/BroadcastReceiver). Test actual cold-process delivery separately.
- **R-1 ? high, TRACED, untouched:** nativeapp/build.gradle:43 and :65. With SPENDWISE_KEYSTORE_FILE absent, release selects the committed devDebug key. Expected private external release signing. Remove the release fallback and test missing-secret failure plus configured certificate identity. No release APK was built here.
- **O-2, TRACED, still a boundary risk:** live SmsIntake.kt:53 does not pass the original SMS timestamp, whereas history passes it at :116. For an undated message processed across midnight, the fallback dates can differ. Ordinary rescans already use inbox date; do not claim every next-day scan duplicates every undated SMS.
- **Earlier OTP/failed examples:** still rejected correctly.
- **Old two-candidate tie and auto-confirmation feedback loop:** remain addressed in their original form; unique learning rawName and no recordConfirmation call in SMS capture. Do not reopen them as K4.
- **Zero-variance fraud result:** same old synthetic eight ?50 KFC expenses followed by ?50,000, no configured high-value threshold, still yields no alert. This is a detector-coverage limitation, not promoted to a new defect without a specified rule requiring it.
- **Learning correction statistics / multi-query backup consistency:** reviewed as possible concerns; no repository-level reproduction, so no new finding.
- **Money:** Double is stored at Db.kt:39. See the saved precision probe for binary addition and displayed formatting; no storage-corruption claim is made from Double alone.

## 6. Tests and evidence handed off

| Artifact | Result at reviewed commit |
|---|---|
| Existing 18 core runners | 1,567 passed / 0 failed |
| Original AuditHarness.kt | Reran; full observations saved |
| SolRegressionTest.kt | **4 passed / 17 failed**, covering K1?K5, K7, T-1 and S-001 |
| SolFuzzTest.kt | **8,000 passed / 2,000 failed**; 10,000 inputs; 0 crashes, 0 negative/non-finite values; all failures are invented invalid times |
| SolLegacyKeyProbe.kt | Diagnostic, not a pass/fail test: 10/10 old/current keys equal |
| SolContrastTest.kt | **0 passed / 2 failed** for the two light-theme text colors |
| verify_schema_and_keys.py | v3?v4 SQL matches exported v4; correct replay and incorrect distinct-payment suppression reproduced |
| Native source/stub compilation | Exit 0, 15 app files; not an Android build |
| ANDROID_SCENARIOS.md | K6/K8/S-003/lifecycle/permission/a11y test specifications; **not executed** |

Drop-in Kotlin sources are in [sol-harness](sol-harness/README.md). The mutation corpus covers case, whitespace, invisible marks and Devanagari/Tamil/Telugu digits; it is not proof of all Indic scripts or all current bank templates.

## 7. M1?M6 release gates

| Milestone / acceptance criterion | Status | Evidence |
|---|---|---|
| M1 K3 identities, account/direction-scoped references, Axis path refs, paste policy | Not met | K3 and Axis failures. |
| M1 zero duplicate rows for old-key messages after upgrade | Partly demonstrated | Ten key/SQLite fixtures pass; no installed Room upgrade or full 90-day corpus test. |
| M1 K1/K6/K2/K5/K4/K7/K8 and strict seconds/time handling | Not met | Results in ?5. |
| M1 old user rows unchanged unless repair approved | Met for these commits, source-traced | No persisted-row repair/migration in this diff. |
| M2 environment-only release signing, no committed-key fallback | Not met | build.gradle:43, :65?68. |
| M2 new private key and documented secret setup | Can't tell | Not created or inspected by this reviewer. |
| M2 phone export/uninstall/install/import checklist and Flask migration proof | Not met as validated outcome | Offset-bearing legacy import fails; no phone migration executed. |
| M2 R8 and tests on minified release | Not met | minifyEnabled false at build.gradle:64. |
| M2 versionCode progression and APK sizes | Not demonstrated | versionCode remains 1; no APK measurement/release-history validation. |
| M3 three-app parity matrix and gap closure | Not demonstrated | No parity matrix found; README still describes the legacy architecture. Feature completeness was not inferred from core tests. |
| M4 no main-thread disk/database I/O; debug StrictMode | Not met | K8 remains; no StrictMode setup found. |
| M4 10,000-ledger list/report/backup timings | Can't tell | Not measured on Android. |
| M4 labels, actual 48dp targets, 200% fonts | Can't tell | Source uses a 48dp token and semantic labels in many places; no full device audit. |
| M4 AA contrast | Not met | S-002 measured failure. |
| M4 no INTERNET, no direct SMS body logging, allowBackup=false, justified exports | Source checks satisfied, device result unverified | Manifest and intake source; launcher activity and permission-guarded receiver are the declared exported components. |
| M4 explicit unencrypted backup disclosure | Not met | S-003. |
| M5 JVM tests in CI | Configured; local tests pass | core-tests.yml:50; no CI run at these local commits was verified. |
| M5 JaCoCo ?85% for Parsing/Pipeline/Backup | Not demonstrated | No measured coverage or JaCoCo setup found. Assertion count is not coverage. |
| M5 Room MigrationTestHelper 1?2?3?4 | Not demonstrated | SQL v3?v4 checked; no native androidTest sources found. |
| M5 Compose main-flow tests | Not demonstrated | No native instrumentation test sources found. |
| M5 emulator SMS/multipart/am-kill/>500/permission/backup coverage | Not demonstrated | Existing smoke workflow is not evidence of these unrun acceptance cases. |
| M5 10,000 fuzz inputs with no crashes, negative amounts or invented times | Not met | No crash/negative failures, but 2,000 invented-time failures. |
| M5 redacted real-SMS corpus if supplied | Not applicable this round | No real-sms input supplied. |
| M6 native README/privacy/release notes/phone checklist | Partly present, not complete | In-app privacy/help exist, but S-003 remains; root README/release notes still describe legacy behavior. No complete phone checklist was validated. |
| M6 successful phone QA/migration; approved deletion and main fast-forward | Can't tell / not performed | No phone signoff inspected. This review did not delete modules or move branches. |

For Android backup behavior, allowBackup=false should not be described as a guarantee against every OEM transfer path; [Android's application documentation](https://developer.android.com/guide/topics/manifest/application-element#allowbackup) states the platform qualification. No new exposure is alleged without device/configuration evidence.

**Release verdict: not ready.** Passing shipped unit tests does not close the reproduced high-severity K1/K3 issues or the release-signing requirement.

## 8. Gate check G1?G5

- **G1: PASS.** Every formal new/prior finding has a label, severity, pinned commit, file:line, exact input/action, expected/actual behavior, smallest fix and proof test. Unproved concerns are not promoted to findings.
- **G2: PASS.** Every VERIFIED result has saved command/output. Source/UI claims are explicitly TRACED; computed contrast is not represented as a device test.
- **G3: PARTIAL.** Every previously open K1?K8 and listed leftover was revisited at 47dece72. Executable core/SQLite cases were rerun; K6/K8/lifecycle still lack Android execution. Their unchanged source does not close the runtime gate.
- **G4: PASS.** ?2 identifies each exact report claim and records confirmation, refutation, or reproduction limits, including the historical count discrepancy.
- **G5: PASS.** K1 includes O-4; K3 includes O-1/O-2 identity; K4 includes O-3. S-001 is the new merchant-filter regression; S-002 contrast and S-003 disclosure have distinct causes and are not duplicates of K8.
