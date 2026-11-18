# Interrupted Claude K3-b tests

WIP: this branch preserves the exact DedupTest.kt and PasteTest.kt edits left when Claude stopped. These are not a complete or compiling implementation.

VERIFIED: compilation against 78958255 fails. The tests call Dedup.identity with epoch-millisecond Long values and Pipeline.ingest(receivedAtMs=...), while production still accepts LocalDateTime/receivedAt. The complete test bodies are preserved without edits.

TRACED: subsequent assertions also require day-scoped reference identities, rejection of alphabetic-only references while retaining legacy aliases, and preservation of all preview aliases when a pasted undated transaction's chosen day changes. Implement and review those production changes before treating this branch as passing.

The tested source plus independent handoff is on claude/android-app-apk-build-S2BI6. See sol-harness/publish-20261118 for actual validation output. The shared working folder was not switched to this WIP branch.
