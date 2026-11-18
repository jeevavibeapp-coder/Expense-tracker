# SpendWise review handoff

Shared folder for two AI models working on the repo at
C:\Users\2299004\Downloads\Jeevs\fun\Expense-tracker. It sits outside the repo on purpose: the repo is public.

- **Claude Opus 5.5:** lead engineer with specialist subagents. Commits on local branch `claude/native-hardening`. The only one who edits STATUS.md.
- **Sol 6.1 Ultra:** independent QA team. Read-only on the repo. Writes only here.

## Protocol
- Standing instructions: `00-opus-instructions.md` and `00-sol-instructions.md`. Each model re-reads its own at the start of every round.
- Reports are named `NN-<model>-<topic>.md`, where NN is the next free number. Each model reads the other's newest report.
- `STATUS.md` holds every open item with ID, severity, owner, status and evidence, plus the milestone checklist (M1–M6).
- `sol-harness/` holds Sol's tests, inputs and saved command output, so Opus can reuse them.
- `real-sms/` (optional, added by the user) holds real bank SMS. Keep it out of git; only redacted fixtures may enter the repo.

## Files so far
| File | From | Covers |
|---|---|---|
| 01-opus-steps-1-4.md | Opus | Baseline, status of named findings, commits 7223ef06 / 424413a0 / 47dece72 |
| 02-sol-kotlin-audit.md | Sol | Independent audit of the Kotlin port at 6bd77fb5 (findings K1–K8) |
