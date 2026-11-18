# Independent M3 parity inventory and SMS redactor

**VERIFIED — scope and isolation.** Sol read the requested pinned source snapshots and wrote only to the sibling spendwise-handoff folder. No branch checkout, repository edit, commit, or Opus parity-matrix read was used for this task. Concurrent Opus work is outside this report.

**TRACED — inventory.** [sol-parity-inventory.md](sol-parity-inventory.md) independently inventories React at 2fa8dc04461e3966f2db9c60181ce3effe79fa24, and Flask plus native Kotlin at 47dece72ab90a6f6a8a137a89ced08134fdb2124. It contains 35 React entries, 96 Flask entries and 61 native entries. Every older-app row has source file:line evidence and native Full/Partial/Absent classification; native-only capabilities are listed too. Full means reachable source implementation, not successful Android runtime verification.

**TRACED — material parity differences.** Native lacks React's category/date-range Activity filters, selectable sorting, filtered CSV export, and manually created recurring transactions. It lacks Flask's display-name/currency controls and adaptive default confidence thresholds. Other differences concern dashboard content, historical time ranges, confidence explanations, category colors and alert history; the inventory gives the exact narrower behavior instead of treating any similarly named screen as parity.

**TRACED — older behavior that must not become a porting requirement.** The normal Android shell replaces React with Flask, and the React SMS bridge does not establish a durable working phone intake. Flask's merchant history and Activity browsing cap results at 100 and 200 respectively. Flask recurring bills are inferred predictions; its CSV import hint has no implemented import screen. React report charts use monthly buckets even for other selected periods. These limitations are explicitly marked in the inventory.

**VERIFIED — delivered redactor.** [sol-harness/redact_sms.py](sol-harness/redact_sms.py) uses only Python's standard library. It writes anonymized filenames and redacted copies to real-sms/redacted, and private original-token review lists plus the persistent mapping state to real-sms/private. It preserves numeric length/masking/punctuation, known PSP suffixes, and consistent replacements; people receive stable PERSON_n labels. Unknown merchants can be approved through a local merchant keep-list.

**VERIFIED — exercised behavior.** 52 unit tests pass. An additional command-line run processes 13 synthetic messages in four input files: one-line text; SMS Backup & Restore style attributes; namespaced child elements; and direct-text SMS XML. Sixteen end-to-end assertions pass, including repeated-run byte stability, removal of the supplied private values, preserved amounts/dates/times/merchant names, and omitted XML contact metadata. Regression cases cover names before a phone/VPA, trailing dates after UPI paths, identifiers embedded in P2M paths, account/UPI grammar preservation, XML sender/timestamp validation, explicit account-holder names, schemeless URLs, negative balances, and conflicting XML bodies. This is synthetic evidence, not real-bank-corpus validation.

**VERIFIED — evidence.** [test output](sol-harness/redaction-tests/test-output.txt), [unit-test command and exit code](sol-harness/redaction-tests/test-command.json), [CLI command and exit code](sol-harness/redaction-tests/cli-command.json), [end-to-end checks](sol-harness/redaction-tests/verification.json), and [synthetic inputs](sol-harness/redaction-tests/synthetic-input) are saved. Generated example copies are in [synthetic example outputs](sol-harness/redaction-tests/synthetic-output).

**TRACED — review boundary.** Name/merchant recognition is heuristic. Ambiguous business names may remain visible and flagged; unfamiliar merchants may instead be masked until added to the keep-list. Unsupported XML layouts are not universally understood. XML is normalized for review, not phone restoration. Check the reported message count and read every output before sharing. The private review lists contain original tokens; the state contains a secret. Neither belongs in a shared corpus.

**VERIFIED — how to run.** The non-technical steps are in [sol-harness/README.md](sol-harness/README.md). From the spendwise-handoff folder, place the file in real-sms/private-input and run:

~~~powershell
python .\sol-harness\redact_sms.py .\real-sms\private-input\messages.txt
~~~

Use messages.xml instead for an XML export. Inspect the matching private review list and all redacted messages; share only the checked copies from real-sms/redacted.
