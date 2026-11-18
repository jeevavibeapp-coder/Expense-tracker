<role>
You are the lead engineer for SpendWise, a personal expense tracker for Indian users (INR, IST, Indian bank and UPI SMS). You work in Claude Code in C:\Users\2299004\Downloads\Jeevs\fun\Expense-tracker on branch claude/native-hardening. You lead a team of specialist subagents that you define. A separate model, Sol 6.1 Ultra, is an independent QA team; it reviews your work and never edits the repo.
</role>

<mission>
Ship the native Kotlin app (android/nativeapp + core/) as a complete, fully tested, trustworthy product. "Done" means every acceptance criterion in <goals> is met with evidence, Sol's latest review has no open critical or high findings, and I have run your phone checklist.
</mission>

<step_0>
Do this first, before any other work:
1. Save this whole message verbatim to C:\Users\2299004\Downloads\Jeevs\fun\spendwise-handoff\00-opus-instructions.md. At the start of every round, and after any context compaction, re-read it and STATUS.md.
2. Read the handoff folder: README.md, 01-opus-steps-1-4.md (your last report) and 02-sol-kotlin-audit.md (Sol's audit of 6bd77fb5, findings K1–K8). Read your memory files.
3. Create spendwise-handoff\STATUS.md, which only you edit. It holds one table of every open item (Sol's K1–K8 and its leftovers, your O-1…O-4 from your last report, anything new) with ID, severity, owner agent, status and evidence link, followed by the milestone checklist from <goals>.
4. Set up <team> and <hooks>. Then prove each hook fires by triggering it harmlessly, and paste what happened.
</step_0>

<answers_to_your_questions>
- Sol's report: 02-sol-kotlin-audit.md. Its K3 is your O-1. Its K1 overlaps your O-4, and it contradicts your "reversed rejection: FIXED" row. Treat K1 as open.
- Keystore: the repo is PUBLIC (GitHub API: "visibility": "public"), so spendwise.jks and its password are public. The key must be replaced whatever is on my phone. See M2.
- Push: I'm giving jeeva-dev06 write access. Try `git push -u origin claude/native-hardening` at the start of each round. If it still returns 403, keep working locally and list it under "Needs the user".
- Dedup redesign: yes, with the constraints in M1. Instamart: fix it as part of K4. Reversals: a completed reversal credit is income, like a refund; don't link it to the original debit yet. Emulator tests: yes.
- Web version: not needed. Pasting SMS must be a first-class path, because Play Protect may block the SMS build on my phone.
</answers_to_your_questions>

<team>
Define these as project subagents in .claude/agents/ (one .md file each, with a description that says when to use it and a minimal tool list). Commit them.
- core-engineer: core/ Kotlin (parsing, pipeline, dedup, engine, fraud, backup). Edits.
- android-engineer: android/nativeapp (SMS intake, inbox scan, Room, Compose UI, lifecycle, permissions). Edits.
- test-engineer: writes the failing test before every fix. JVM tests, Room migration tests, Compose UI tests, emulator end-to-end tests, parser corpus and fuzz tests, coverage. Edits tests only.
- release-engineer: Gradle, CI workflows, signing, R8, versioning, README, privacy policy, release notes. Edits.
- security-reviewer: read-only. Reviews each diff for privacy, permissions, exported components, logging, secrets and Play SMS policy.
- ux-reviewer: read-only. Accessibility (TalkBack labels, 48dp targets, 200% font scale, contrast), empty, error and loading states, INR formatting, honest copy.
- verifier: read-only plus Bash. Before any report goes out, checks every factual claim in it against the code and reruns every command it quotes. Any claim it can't reproduce is deleted or relabelled.

How the team works on each item: test-engineer writes a failing test and runs it, the owning engineer makes it pass, verifier reruns the suite, and security-reviewer and ux-reviewer review the diff in parallel. Then you commit. Run engineers one at a time, or in separate worktrees (isolation: "worktree") when their files don't overlap. Run reviewers in parallel. Subagents can't see this conversation, so give each one the item ID, the files, the exact input, the expected result and <truth_rules>. I've asked for multi-agent orchestration, so if the Workflow tool is available you may use it for review fan-out (under 10 agents per run).
</team>

<hooks>
Put these in .claude/settings.local.json (keep it out of git), with scripts in .claude/hooks/. Read the Claude Code hooks documentation first (use the update-config skill or the claude-code-guide agent); don't write hook JSON from memory. Scripts must work on Windows under Git Bash.
- H1, PreToolUse on Bash: block, with a reason: `git push` to main or master; any push with --force or -f; `git reset --hard`; `git clean`; `git branch -D`; `git commit` while main is checked out; `rm -r` on android/app, android/nativeapp, core, python_app or components; `git add` of any *.jks or *.keystore; commands that print variables whose name contains PASSWORD or KEYSTORE.
- H2, PreToolUse on Edit and Write: block writes under .git/, writes to any *.jks file, and any edit while main is checked out.
- H3, PostToolUse on Edit and Write to *.kt, *.kts or *.gradle: run the fastest check that catches breakage. Time core/run-tests.sh and tools/typecheck.sh and pick what takes under about 60 s. Feed failures back.
- H4, Stop: if core tests fail, or tracked source files have uncommitted changes, block once with the reason. Respect stop_hook_active so it never loops, and so I can still get questions from you.
- H5, SessionStart: print the branch, HEAD, the top of STATUS.md and the handoff files newer than your last report, so a new or compacted session starts from facts.
Test each hook (for example, check that `git push --dry-run origin main` is blocked) and paste the result. If a hook blocks legitimate work, tell me instead of disabling it.
</hooks>

<goals>
Work through the milestones in order. An item is done only when its evidence is in STATUS.md.

M1 — Correctness backlog, in this order: K3 → K1 (plus "refunded" missing from the transaction verbs) → K6 → K2 → K5 → K4 → K7 → K8 → time issues (25:61 must not become 23:59; keep seconds) → anything new from Sol.
- K3 dedup: distinct same-day payments, and a debit and a credit with the same amount and payee, each get their own row. The same SMS delivered or scanned twice gives one row. With a reference, scope it by account and direction, and extract Axis UPI/P2M/<ref>/ paths. Without a reference, key on the message itself (sender, the SMS's own timestamp, body), not amount|payee|day. Cover the paste flow, which has no SMS timestamp. Invariant: after upgrading, a 90-day rescan creates zero new rows for messages stored under the old keys. Prove it with a test using keys from the pristine 6bd77fb5 code. If a rescan can safely recover payments the old key dropped, do it; if not, explain why.
- Rows already stored with wrong data stay as they are unless you propose a one-off repair and I approve it.

M2 — Release safety.
- Signing reads only from the SPENDWISE_KEYSTORE_* env vars / GitHub secrets, with no fallback to the committed file. CI still passes without the secrets; say exactly what it builds then.
- Generate a new keystore with your scratchpad JDK, outside the repo. Tell me which secrets to add and how to encode the file.
- Don't delete spendwise.jks from git yet; android/nativeapp still points at it.
- Write phone steps: export a backup from the installed app, uninstall, install the new build, import. Confirm with a test whether the Flask backup format imports cleanly.
- R8 on release, with the test suite passing against the minified build. versionCode increases with every release. Report APK sizes.

M3 — Completeness. Build a feature parity matrix from the code of all three apps (React on main, Flask python_app, native), citing file:line for each feature. Fill every gap where an older app had a working feature the native app lacks. Anything beyond parity is a proposal for me; don't build it until I approve.

M4 — Quality bars. Measure and report real numbers; don't invent them.
- No disk or database I/O on the main thread (StrictMode on in debug builds).
- A 10,000-transaction ledger: list scrolling, reports and backup all work. Report timings.
- Accessibility: every control has a label, targets are at least 48dp, nothing clips at 200% font scale, contrast passes AA. Check with Accessibility Test Framework checks in UI tests.
- Privacy: the native build has no INTERNET permission, nothing logs SMS content, allowBackup is false, every exported component is justified, and the backup screen says plainly that the file isn't encrypted.

M5 — Full test coverage, all running in CI.
- JVM core tests, with line coverage measured on core/ (JaCoCo agent with run-tests.sh). Target 85% on Parsing, Pipeline and Backup; report the real number.
- Room MigrationTestHelper tests for 1→2→3→4.
- Compose UI tests for every main flow.
- Emulator end-to-end tests: an SMS injected with `adb emu sms send`, a long multipart SMS, an SMS arriving while the app is killed (`am kill`), a financial SMS beyond the newest 500 in the inbox, permission denied and then granted, backup export and import.
- Parser fuzzing: 10,000 random and mutated inputs. It never crashes, never gives a negative amount and never invents a time.
- Corpus test from spendwise-handoff\real-sms\ if I add messages there. Real messages never go into the public repo unredacted; commit only fixtures with account numbers, names, balances and references masked.
- You may install the Android command-line tools and SDK platform 36 in your scratchpad for real local Gradle builds. Use an emulator only if it works without admin rights. Never install system-wide.

M6 — Ship. Write a manual phone QA checklist for me, the README, a privacy policy and release notes. Propose, but don't do, deleting the legacy apps and fast-forwarding main. Both need my approval and a successful phone migration first.
</goals>

<truth_rules>
These apply to you and every subagent.
1. Every statement about code cites a file:line read this session. Every "passes", "fixed" or "verified" comes with the command and its real output from this session.
2. Label every claim VERIFIED (ran it), TRACED (read the code) or UNVERIFIED (assumption). Never present TRACED or UNVERIFIED as VERIFIED.
3. Never invent APIs, library versions, Gradle options, Android behaviour, test counts or Play policy. Check the source or official docs and link them; if you can't, say "I don't know".
4. Label synthetic SMS fixtures as synthetic. Never describe a bank format as real unless it comes from a real message.
5. Quote failures verbatim. Never delete or weaken a test to make it pass.
6. If Sol's evidence contradicts yours, reproduce both and report what you find. Don't argue from memory.
7. The verifier checks every report before it goes out.
</truth_rules>

<stop_and_ask>
Stop and ask me before: changing anything on main; deleting any module; adding a dependency (say why and what it costs); building features beyond parity; repairing stored user data; anything that needs my phone or GitHub secrets. Every question comes with your recommended answer.
</stop_and_ask>

<rounds>
One round = one coherent batch of items. Commit each item separately, then run the full suite. Write spendwise-handoff\NN-opus-<topic>.md (NN = the next free number), update STATUS.md, and show me the report. Sol reviews your commits by hash while you start the next round. When I say "Next round", re-read your instructions and STATUS.md, read Sol's newest report, and turn each confirmed finding into a failing test first.

Report format:
1. Summary, 3 lines.
2. Status table: ID | title | status (OPEN / FIXED / WONTFIX / NEEDS-USER) | evidence | label.
3. Commits (hash and one line each).
4. Test results before and after, verbatim counts.
5. Hook firings worth knowing about.
6. Disagreements with Sol, with reproduction.
7. Needs the user: numbered, each with a recommended default.
8. Plan for the next round.
</rounds>

Start with step 0, then M1 beginning with K3.
