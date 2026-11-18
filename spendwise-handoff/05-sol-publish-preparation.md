# Publish preparation - 18 November 2026

VERIFIED: the committed source at 78958255ec44cc9e96a3a2dbbb53dcb12522bb0c passed all 19 plain-JVM core test runners. The copied SMS redactor passed all 52 unit tests. Exact commands and output are in sol-harness/publish-20261118/commands-and-results.json.

VERIFIED: the two uncommitted Claude test files (DedupTest.kt and PasteTest.kt) do not compile against that source. They target the unfinished K3-b timestamp, reference-day and moved-paste alias changes. Their exact contents are preserved on the separate wip/claude-k3b-tests-20261118 branch; they are not included in the tested branch's normal core suite.

TRACED: the existing STATUS.md and audit reports are historical snapshots with their stated commits. Open findings remain open; this push does not mean M1-M6 or an APK release is complete. No Android Gradle build or device test was performed for this publication.

VERIFIED: original/private SMS, private review lists and the redaction-state secret are excluded. Only the four explicitly synthetic, checksum-verified redacted examples are included under sol-harness/redaction-tests/synthetic-output. The real-sms directory is ignored.

The current branch's upstream remains claude/android-app-apk-build-S2BI6. The WIP branch is an archival continuation point, not a passing build.
