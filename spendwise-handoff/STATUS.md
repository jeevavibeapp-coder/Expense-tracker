# SpendWise STATUS (edited only by Opus)

Branch `claude/native-hardening` (local; push returns 403). Base 6bd77fb5. Updated 2026-11-18, round 3.

Labels: VERIFIED (ran it this session), TRACED (read the code), UNVERIFIED (assumption).

## Open items

| ID | Sev | Title | Owner | Status | Evidence |
|---|---|---|---|---|---|
| K3 | High | Dedup merged distinct same-day payments and debit/credit pairs; Axis path ref unread | core + android | FIXED in 78958255, follow-ups K3-a/K3-b below | DedupTest 203/0 incl. "80 messages checked against 45 banked 6bd77fb5 keys, 0 new rows"; run-tests exit 0 (19 suites, 0 failed); typecheck "no errors in 15 app source files" — VERIFIED |
| K3-b | High | Before keys ship: bucket from epoch ms, not local clock (travel refiles every unreferenced SMS); a reference needs a digit ("Txn amount" → ref "amount") and the ref key carries the day (reused EMI refs); paste commit keeps its paste-day alias | core + android | IN PROGRESS | security review H1/M1, UX review #1 (TRACED) |
| K3-a | Medium | An SMS with no reference that was captured and then pasted is not flagged on the paste card; saving adds it twice (the old shared key caught it) | core + android | OPEN (next round: field look-alike warning) | android-engineer doubt 1, security L1 (TRACED) |
| K3-c | Medium | Recover payments the old key merged on rescan | core + android | OPEN (with K6: needs a grouped scan; unsafe per message) | K3-spec "Recovery" |
| UX-1 | Medium | Paste card copy after K3: undated-duplicate edit path, silent K3-a gap, Help "removes exact duplicates automatically" now false, "Added from this message" may be a look-alike | android | IN PROGRESS | UX review (TRACED): PasteScreen.kt:214,226,309-314; SettingsAndOnboarding.kt:897 |
| SEC-L2 | Low | Exported backup carries v2 dedup-key hashes; brute-forcing them can reveal account last-4 digits and balances from known templates | user decision | NEEDS-USER | security review L2 (TRACED) |
| SEC-L3 | Low | approveHeld keys a scan-held message by scan time; quarantine is not in backups, so after restore the scan refiles approved held messages | android | OPEN | security review L3 (TRACED) |
| K1 | High | Completed cashback / recharge / reversal rejected by NON_TXN; `refunded` missing from TXN_VERB. Includes O-4 | core | OPEN (next round; must land before K3 keys ship: direction is in the ref key) | Sol 02 §K1; SolRegressionTest K1-0..3 |
| K2 | Medium | Refund direction/payee: payee null (want AMAZON); "refund of the amount debited earlier" still expense | core | OPEN | SolRegressionTest K2-merchant, K2-historical-debit |
| S-001 | Medium | Regression from 424413a0: ACCOUNT_REF rejects payees starting with "your" (YOUR SALON) | core | OPEN | SolRegressionTest S-001-your-merchant |
| K6 | Medium | Inbox scan never reaches messages beyond the newest 500 | android | OPEN | Sol 02 §K6 |
| K5 | Medium | Duplicate-charge alert fires when the merchant is unresolved | core | OPEN | Sol 02 §K5 |
| K4 | Medium | First-word seed match at confidence 90 (AMAZON CAFE); includes O-3 Instamart | core | OPEN | Sol 02 §K4 |
| K7 | Medium | Backup import drops UTC offsets, rejects negative offsets | core | OPEN | Sol 02 §K7 |
| K8 | Medium | Backup document I/O on the main thread; unbounded readBytes() | android | OPEN | Sol 02 §K8 |
| T-1 | Medium | `25:61` becomes 23:59; seconds dropped | core | OPEN | SolRegressionTest T1-* |
| L-1 | Low | SmsIntake comment says the receiver never fires while the process is dead | android | OPEN | Sol 02 |
| R-1 | High | Committed keystore is public; release falls back to it | release | OPEN (M2) | user answer |
| U-1 | — | `git push` returns 403 for jeeva-dev06 | user | NEEDS-USER | round 3 push attempts |
| U-2 | — | Project subagent types not registered in this session (created mid-session); roles run as general-purpose agents reading .claude/agents/<role>.md | user | NEEDS-USER (restart) | Agent tool error "Agent type 'test-engineer' not found" |
| C-1 | — | Opus 01 count "26 passed, 3 failed" came from an intermediate AuditTest; committed test gives 29/3. Sol is right | Opus | ACKNOWLEDGED | sol-harness/README.md |

## Closed

| ID | Closed by | Evidence |
|---|---|---|
| account mask as payee | 424413a0 | Opus 01 (but see S-001) |
| refund that says "paid" is income | 47dece72 | Opus 01 (but see K2) |
| Step 0 team | 29ded32f | 7 agents in .claude/agents |
| Step 0 hooks | local (.claude/settings.local.json, gitignored) | 03 report "Hook firings" |

## Milestones

- [ ] M1 Correctness: K3 (done, K3-a/b/c follow-ups) → K1 → K6 → K2 → K5 → K4 → K7 → K8 → T-1 → new Sol findings (S-001)
- [ ] M2 Release safety
- [ ] M3 Feature parity
- [ ] M4 Quality bars
- [ ] M5 Coverage
- [ ] M6 Ship
