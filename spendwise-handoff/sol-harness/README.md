# Sol audit harness evidence

Reviewed commit: 47dece72ab90a6f6a8a137a89ced08134fdb2124; baseline 6bd77fb5e104a5911b0c50a12af2e171973cb689.
All audit SMS inputs are synthetic. No real SMS was supplied. The real-sms directory now contains generated synthetic redaction examples.

Drop SolRegressionTest.kt and SolFuzzTest.kt into core/src/test/kotlin of an isolated checkout, then run the normal core/run-tests.sh. They deliberately fail on the reviewed commit. SolLegacyKeyProbe.kt is diagnostic: compile/run at baseline and reviewed commit and compare its third TSV column; it does not assert.

AuditHarness.kt and audit-*.json/tsv at the parent directory preserve the previous audit. Current results are under 47dece72/evidence and 47dece72/extra-evidence. Every JVM invocation is recorded, including exact arguments, exit code and stdout, in the respective commands-and-results.json. native-stub-typecheck is a compiler pass with the repository's handwritten stubs, NOT an Android SDK, Gradle, KSP, Compose plugin or emulator build.

SolFuzzTest uses seed 20261118; 10,000 synthetic case/spacing/invisible-character/Devanagari/Tamil/Telugu digit mutations, including 2,000 deliberate invalid clock strings. Its no-crash/no-negative properties passing is distinct from its invalid-clock property failing. This is not a corpus of 10,000 real bank messages.

Historical count caution: the committed 424413a0 AuditTest rerun on the preceding production code prints 29 passed, 3 failed. The report's 26 passed, 3 failed cannot be reproduced exactly from that committed test, which includes three later compatibility checks. The 43 passed, 3 failed refund run is reproduced against the 424413a0 parser.

SolContrastTest.kt reads the actual Theme.kt constants and checks the two Settings label colors. Run it with an explicit path to the reviewed Theme.kt, or from the export root/core directory. This is source-based contrast calculation, not UI instrumentation. MoneyPrecisionProbe.kt is diagnostic only.

## How to redact bank SMS before sharing

This tool runs on your computer using Python 3. It does not upload anything. It helps remove identifying details, but you must check its output yourself. A successful command is not a guarantee that every private detail was recognized.

1. Inside the spendwise-handoff folder, create a folder named real-sms, then a folder inside it named private-input.
2. Put your SMS text file or SMS-backup XML file in private-input. For a text file, use one complete message per line. Do not paste private messages into a chat.
3. Open PowerShell in the spendwise-handoff folder. For a file named messages.txt, run this exact command:

~~~powershell
python .\sol-harness\redact_sms.py .\real-sms\private-input\messages.txt
~~~

For an XML file named messages.xml, run:

~~~powershell
python .\sol-harness\redact_sms.py .\real-sms\private-input\messages.xml
~~~

You can pass several input files after the script name. Do not give two files the same filename in one command. Python 3 must be installed and available through the python command.

4. Open the resulting sms-....redacted.txt or sms-....redacted.xml file inside real-sms\redacted. Only these redacted copies are candidates for sharing. The filename is anonymized too.
5. Open the matching .review.txt file inside real-sms\private. It lists uncertain tokens with their original text and message number. Check each one against the redacted copy. Also read every complete redacted message: names, unexpected account formats, personal email addresses, addresses and free-form notes may need manual removal.
6. Before sharing, check that people's names, account/card digits, UPI handles, references, balances and phone numbers are no longer real. Check that transaction amounts, dates, times, verbs, bank sender IDs and the merchant names you want tested are still correct. An uncertain business name may have been retained and flagged; decide whether it is really a public merchant or someone's identity. Unfamiliar merchants may be masked as PERSON_1 until you approve them.
7. Share only the inspected copies from real-sms\redacted. Keep the input, the entire private folder, the review lists and redaction-state.json on your computer. They must not be shared. The state file contains a secret key and preserves consistent replacements across later runs; keep it to retain that consistency.

To preserve an unfamiliar merchant, create a UTF-8 file called merchants.txt in real-sms\private-input containing one approved merchant name per line. Then run:

~~~powershell
python .\sol-harness\redact_sms.py .\real-sms\private-input\messages.txt --merchants .\real-sms\private-input\merchants.txt
~~~

Only list public merchant names. Listing a person's name tells the tool to keep that name. A parser-shaped account or reference is a fake, not a valid payment destination. Compare repeated accounts/references across copies if the test depends on them. Deleting the private state file changes replacements on the next run.

XML layouts tested: SMS Backup & Restore style sms attributes (address/date/body); namespaced or plain message child elements (sender/timestamp/body); and direct text inside sms elements with from/date attributes. XML output is a small normalized messages file, not a backup for restoring to a phone. Unrelated metadata such as contacts and service-center numbers is omitted and listed privately. Conflicting body attributes and child elements are flagged in the private review list; the attribute is used. MMS attachments, arbitrary vendor schemas and malformed XML are not supported; files with no recognized messages fail with an error. Check the reported message count against your export; mixed vendor layouts can omit unrecognized records. XML containing a DTD or entity declaration is rejected.

Input defaults to UTF-8, with UTF-8/UTF-16 BOM detection. For another known encoding, add --encoding followed by the encoding name. Files larger than 64 MiB are rejected. The tool always reports HUMAN REVIEW REQUIRED, even if it found no uncertain tokens.

Synthetic evidence is in sol-harness\redaction-tests. To rerun the standard-library unit tests:

~~~powershell
python -B -X utf8 .\sol-harness\test_redact_sms.py
~~~
