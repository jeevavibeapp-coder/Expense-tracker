Completed an independent, read-only inventory of the Flask application at **`47dece72ab90a6f6a8a137a89ced08134fdb2124`**. I read the routes, every served template, the interaction script, and the analytics/fraud/backup code wired to those screens. I did **not** read Opus’s parity matrix or another new report.

**All entries below are TRACED, not runtime verified.** No Flask/browser execution was performed. File references are relative to `python_app/spendwise/`; for example, `app.py:739` means `python_app/spendwise/app.py:739`.

## Entry, accounts, navigation and recovery

| ID | User-facing feature | Evidence and actual scope |
|---|---|---|
| FL-01 | First-run introduction and permission explanation | `app.py:739` redirects an un-onboarded user to `/welcome`; `templates/welcome.html:20` explains SMS capture, merchant learning and suspicious-message handling. |
| FL-02 | Complete onboarding and reopen introduction later | POST `/welcome/done` stores `onboarded_at`, `app.py:1748`; Settings links back to the introduction, `templates/settings.html:174`. |
| FL-03 | Web-mode signup | GET/POST `/signup`, `app.py:716`; name, email, password fields and inline errors in `templates/signup.html:9`. Eleven default income/expense categories are provisioned through `auth.py:18` and `auth.py:57`. |
| FL-04 | Web-mode login and logout | GET/POST `/login`, `app.py:701`; POST `/logout`, `app.py:733`; login form `templates/login.html:9`; logout visible only outside single-user mode, `templates/settings.html:182`. |
| FL-05 | Embedded single-user mode without interactive login | Local-user provisioning in `auth.py:90`, session establishment in `app.py:186`; native-mode screens hide account email/logout. |
| FL-06 | Four main tabs and secondary navigation | Home, Activity, Budgets, Alerts in `templates/base.html:91`; Settings icon at `:32`; back links at `:22`; app-wide Add button at `:110`. |
| FL-07 | In-app navigation with browser-back support and loading feedback | `static/app.js:116` implements same-origin client navigation; loading skeletons at `:165`, response rendering at `:277`, fallback full navigation at `:323`. |
| FL-08 | Pull to refresh | Touch gesture reloads the current page’s content at `static/app.js:422` and `:487`. This refreshes visible data; it is not itself an inbox scan. |
| FL-09 | Bottom-sheet dismissal | Close/scrim links in `templates/base.html:119`; drag-down dismissal at `static/app.js:506`. |
| FL-10 | Interaction effects and reduced-motion handling | Reduced-motion detection at `static/app.js:7`; press ripple/haptic handling at `:24`; confetti after add/confirm at `:38` and `:70`. |
| FL-11 | Migration-failure explanation | Safe-mode page at `app.py:36`/`:52`: explains failed update, advises against clearing data/reinstalling, and displays the retained backup location. |
| FL-12 | Generic server-error recovery link | HTML error screen with “Back to SpendWise,” `app.py:266`. |

## Home/dashboard

| ID | User-facing feature | Evidence and actual scope |
|---|---|---|
| FL-13 | Balance, income and expense summary | `templates/dashboard.html:10`; values are **all-time gross** sums from `analytics.py:531`–`:561`, with balance = income − expense. |
| FL-14 | Empty-ledger setup checklist | SMS capture, first manual transaction and monthly-budget links, `templates/dashboard.html:20`. |
| FL-15 | “Money health”/saved-income percentage | Savings ring and Healthy/On track/Watch spending labels, `templates/dashboard.html:47`; otherwise suggests adding income at `:68`. It uses the all-time income/expense totals. |
| FL-16 | Today, calendar-week and month spending | `templates/dashboard.html:78`; calculations in `analytics.py:501`–`:530`. Calendar week begins Monday through `weekday()`, rather than being a rolling seven-day total. |
| FL-17 | Detected transfer/refund explanation and adjusted spending | Separate “Not counted as spending” card at `templates/dashboard.html:88`; transfer/refund detection and adjusted sums in `analytics.py:592`, `:645`, `:698`; wired at `:549`. See the implementation limitation below. |
| FL-18 | No-spend streak and no-spend days this month | `templates/dashboard.html:105`; calculation `analytics.py:163`; empty/spending-free history handling at `:553`. |
| FL-19 | Last-14-days spending chart | `templates/dashboard.html:126`; zero-filled daily series at `analytics.py:147`. |
| FL-20 | Upcoming inferred bills/subscriptions | `templates/dashboard.html:147`; merchant, amount, cadence, due-today/overdue/days-left labels; shows first four results. Detection at `analytics.py:182`. |
| FL-21 | Merchant-review and open-alert prompts | `templates/dashboard.html:175` and `:189`, linking to Activity and Alerts. |
| FL-22 | Written spending insights | `templates/dashboard.html:199`; behavior/category change, frequent merchant, weekend concentration, budgets, due bills, streak and pending work from `analytics.py:357` and `:408`. At most four lines are displayed. |
| FL-23 | Monthly category-budget progress | `templates/dashboard.html:209`; first four budget rows, amount spent/budget and over-budget styling. |
| FL-24 | Category spending donut and percentage legend | `templates/dashboard.html:229`; top categories and aggregation from `analytics.py:99`/`:108`. |
| FL-25 | Top merchants with spending share and drill-down | `templates/dashboard.html:273`; first five merchants, amount and share of all spending; opens `/merchant?n=…`. |
| FL-26 | Six-month income/spending comparison | `templates/dashboard.html:299`; `_trend` at `analytics.py:324`. |
| FL-27 | Optional name personalization | Home form at `templates/dashboard.html:327`, handled by `/profile`, `app.py:1693`. |

## Activity and manual transactions

| ID | User-facing feature | Evidence and actual scope |
|---|---|---|
| FL-28 | Chronological transaction list grouped by day | `templates/transactions.html:74`; merchant, category/review status, notes and signed amount; day expense totals. Query at `app.py:779`. |
| FL-29 | Search by merchant/raw merchant, notes or reference | Search input `templates/transactions.html:35`; FTS lookup with substring fallback, `app.py:792`. |
| FL-30 | All/Spent/Received/From SMS/Needs review filters | `templates/transactions.html:44`; server filtering `app.py:808`. |
| FL-31 | Manual expense/income creation | Global Add sheet, `templates/base.html:120`; type, amount, merchant/payee, category, date and optional notes. POST `/transactions`, `app.py:859`. |
| FL-32 | Live merchant-resolution preview while adding | Debounced request at `static/app.js:86`; `/transactions/resolve`, `app.py:874`; merchant, confidence, decision, reasons and scoring breakdown in `templates/_resolve.html:2`. |
| FL-33 | Inline transaction editing | `templates/transactions.html:114`; amount, date, merchant, category and notes; backend `app.py:1138`. **The displayed form does not allow changing income/expense type**: type is a hidden input at template line `136`, even though the endpoint accepts it. |
| FL-34 | Merchant correction teaches future resolution | Merchant-change branch records a confirmation/correction at `app.py:1165`–`:1175`. See the category-only editing limitation below. |
| FL-35 | Delete and undo delete | POST handlers `app.py:1117` and `:1127`; delete button `templates/transactions.html:145`; undo bar at `:18`. Rows are soft deleted. |
| FL-36 | Swipe-left delete | `static/app.js:331`; submits the row’s delete form and uses the existing undo path. |
| FL-37 | Alert/anomaly deep link to a specific transaction | `app.py:818` loads an otherwise omitted transaction; expanded/highlighted row at `templates/transactions.html:88`. |
| FL-38 | Empty-ledger and no-search-results states | Distinct messages/actions at `templates/transactions.html:153`. |
| FL-39 | Indian amount grouping and currency symbols | `app.py:394`–`:459`: lakh/crore grouping, compact K/L/Cr representation, symbols for INR and several other codes. This is display formatting, not exchange-rate conversion. |

## SMS paste/import, automatic capture and review

| ID | User-facing feature | Evidence and actual scope |
|---|---|---|
| FL-40 | Paste one bank/UPI SMS and parse it | `/import`, `/import/parse`, `app.py:1220`/`:1232`; text area and AJAX parsing in `templates/import.html:18` and `:44`. |
| FL-41 | Review/edit a parsed SMS before saving | `templates/_import_preview.html:5`: expense/income, amount, date, raw merchant; parsed reference is retained in a hidden field. |
| FL-42 | Merchant-confidence preview and saved-result explanation | Parse preview at `templates/_import_preview.html:20`; save result, decision, confidence, score breakdown and alert link at `templates/_import_result.html:2`. |
| FL-43 | Save parsed SMS and import another | POST `/import/create`, `app.py:1249`; result links at `templates/_import_result.html:17`. Saved with source `sms`. |
| FL-44 | Unparseable-message feedback | `templates/_import_preview.html:34` shows “Couldn’t find a transaction…” rather than a save form. |
| FL-45 | Recent SMS captures | Last eight non-deleted SMS transactions on Import, `app.py:1225`, rendered at `templates/import.html:28`. |
| FL-46 | Automatic native SMS ingestion | `/sms/ingest`, `app.py:1266`; parsing, sender assessment, quarantine decision, deduplication and transaction creation at `:1281`–`:1342`. This is a native-facing endpoint whose resulting captures are exposed in the UI. |
| FL-47 | SMS-permission status and request button | Native reports status through `/device/state`, `app.py:1344`; denied-permission banner `templates/base.html:37`; request buttons invoke `AndroidSms.requestPermission()` in Welcome/Settings. |
| FL-48 | Capture diagnostics | Settings shows permission/build status, captured count, queued count, last capture, unreadable-message counts and OEM guidance, `templates/settings.html:60`; source data at `app.py:1661`. |
| FL-49 | Category prompt for newly captured SMS | Pending prompt selection at `app.py:541`; category modal `templates/base.html:159`; category chips, create category, skip, sort-all and dismiss-all actions at `:186`–`:206`. |
| FL-50 | Categorize one capture and learn its mapping | `/transactions/<id>/categorize`, `app.py:917`; accepts existing/new category and records a confirmation when raw merchant is available. |
| FL-51 | Dismiss all outstanding category prompts | `/sms/prompts/dismiss`, `app.py:1107`; sets `category_prompted`, preserving transactions and their availability in review. |
| FL-52 | Bulk review grouped by merchant and direction | `/review`, `app.py:965`; count, total, date range, sender and sample SMS in `templates/review.html:23`. Includes SMS rows with missing category or pending/review status. |
| FL-53 | Explained category suggestions in bulk review | `app.py:990`; suggested category, confidence and learned words at `templates/review.html:53`. |
| FL-54 | Bulk assign existing/new category, confirm and teach | `/review/bulk`, `app.py:1008` and `:1052`; category actions in `templates/review.html:50`. It teaches from up to 25 rows per group. |
| FL-55 | Bulk “Not a transaction” removal | `templates/review.html:83`; soft deletes matching group at `app.py:1026`. |
| FL-56 | Clear unreviewed uncategorized SMS captures | Settings action `templates/settings.html:120`; `/sms/purge`, `app.py:1081`, preserves other rows and dismisses alerts for deleted rows. |
| FL-57 | Export unreadable SMS for diagnosis | Settings/Help links to `/sms/misses.csv`; route at `app.py:1845`, columns include sender, body, reason, parser version, count and first/last seen. |

## Sender trust, held messages and fraud alerts

| ID | User-facing feature | Evidence and actual scope |
|---|---|---|
| FL-58 | Held-message screen with reasons and message disclosure | `/sms/quarantine`, `app.py:1536`; sender, risk, seen count, explanation, parsed transaction and expandable body at `templates/quarantine.html:12`. |
| FL-59 | Approve a held message | “It’s real—add it” at `templates/quarantine.html:52`; handler `app.py:1563` creates the transaction and trusts the sender. |
| FL-60 | Discard, or discard and block sender | `templates/quarantine.html:56`/`:59`; handler `app.py:1607`. |
| FL-61 | Sender registry and block/unblock | Sender/bank, kind, message/held count and trust in `templates/quarantine.html:71`; block/unblock form at `:88`, handler `app.py:1616`. The screen exposes **block/unblock**, not arbitrary trust-level selection; approval is the visible trust action. |
| FL-62 | Suspicious sender/content assessment | Wired to capture at `app.py:1285`; `senders.py:310` combines sender identity, stored trust and phishing signals, either quarantining or reducing confidence. |
| FL-63 | Automatic expense fraud/anomaly alerts | Wired at `app.py:686`; `fraud.py:44` detects possible duplicate charges, statistical high-value outliers, configured high-value limit breaches, abnormal daily spending and first-seen high-value merchants. |
| FL-64 | Alert list with severity and affected transaction | `/fraud`, `app.py:1444`; `templates/fraud.html:12` shows type, severity, explanation and transaction drill-down. |
| FL-65 | Dismiss or mark an alert safe; retain resolved history | `templates/fraud.html:36`; `/fraud/<id>/status`, `app.py:1458`. Non-open alerts remain visible, dimmed with their status. No visible reopen action. |

## Categories, budgets and merchant intelligence

| ID | User-facing feature | Evidence and actual scope |
|---|---|---|
| FL-66 | Income/expense category lists | `/categories`, `app.py:1367`; separate sections in `templates/categories.html:14` and `:44`, with current-month spending. |
| FL-67 | Create category with name, type and color | `templates/categories.html:63`; POST `/categories`, `app.py:1394`, including duplicate-name feedback. |
| FL-68 | Delete/retire a category | Template delete controls at `templates/categories.html:33` and `:51`; `app.py:1424` archives categories used by live transactions and deletes unused categories. |
| FL-69 | Set, change and remove an expense-category monthly budget | `templates/categories.html:80`; `/categories/<id>/budget`, `app.py:1379`; progress shown in category screen and Home. |
| FL-70 | Merchant drill-down | `/merchant?n=…`, `app.py:1184`; total spent, transaction/visit count, average expense, last visit, month-by-month spending and transaction history in `templates/merchant.html:9`. **Limited to the latest 100 matching transactions**, including the displayed totals. |
| FL-71 | Learned merchant aliases and evidence | `templates/merchant.html:30`: raw alias, confidence, confirmations/corrections, amount range and learned category. Data loaded at `app.py:1205`. |
| FL-72 | Adaptive resolution thresholds | User sliders override defaults; otherwise measured correction rate affects thresholds through `app.py:558`. This affects visible resolution decisions rather than introducing another screen. |

## Monthly report and advanced analytics

| ID | User-facing feature | Evidence and actual scope |
|---|---|---|
| FL-73 | Previous/next monthly report navigation | `/report?m=YYYY-MM`, `app.py:753`; future requests clamped; navigation at `templates/report.html:9`. |
| FL-74 | Monthly expense, income, saved amount/rate and prior-month comparison | `templates/report.html:21` and `:37`; `analytics.build_report`, `analytics.py:245`. Current-month comparison uses the comparable elapsed period. |
| FL-75 | Transaction count, no-spend days and priciest day | `templates/report.html:51`. |
| FL-76 | Daily spending bars | `templates/report.html:59`. |
| FL-77 | Category spending and change against prior month | `templates/report.html:76`. |
| FL-78 | Top merchants for selected month | `templates/report.html:96`. |
| FL-79 | End-of-current-month spending forecast | `templates/report.html:115`; forecast total, daily rate, remaining days, comparison and upcoming recurring commitments. Calculation at `insights.py:56`; gated to sufficient history/current month. |
| FL-80 | Unusually large transaction explanations | `templates/report.html:166`; amount versus usual merchant amount and transaction link; calculation `insights.py:291`. |
| FL-81 | Savings/opportunity observations | `templates/report.html:192`; amount and explanation for recurring commitments, small purchases and category increases; calculation `insights.py:353`. |
| FL-82 | Multi-month cash-flow chart and running net total | `templates/report.html:214`; calculation `insights.py:130`. |
| FL-83 | Category trends with average/month and rising/falling direction | `templates/report.html:252`; calculation `insights.py:168`. |
| FL-84 | Merchant monthly visits, average ticket, baseline and change | `templates/report.html:286`; calculation `insights.py:228`. |
| FL-85 | Empty-month feedback | `templates/report.html:317`. |

## Settings, exports, backup and help

| ID | User-facing feature | Evidence and actual scope |
|---|---|---|
| FL-86 | Edit display name | Settings form `templates/settings.html:9`; handler `app.py:1693`. |
| FL-87 | Display currency preference | `templates/settings.html:25`; persisted at `app.py:1723`. No conversion/account-currency model is exposed. |
| FL-88 | System/light/dark appearance | `templates/settings.html:28`; persisted at `app.py:1727`, injected globally at `app.py:513`. |
| FL-89 | Auto-save and ask-to-confirm confidence sliders | `templates/settings.html:36`; POST `/settings`, `app.py:1706`. |
| FL-90 | Optional high-value alert threshold | `templates/settings.html:49`; stored at `app.py:1723`, applied at `fraud.py:84`. |
| FL-91 | Download transaction CSV | Settings/Privacy links; `/export.csv`, `app.py:1822`: all non-deleted rows with date, type, amount, merchant, category, notes, reference, source and status. |
| FL-92 | Download JSON backup | `/backup.json`, `app.py:1776`; filename contains date. `backup.py:37`–`:92` writes format 1 JSON containing categories/budgets, merchants, learning, transactions including deleted rows, sender registry and five preferences. |
| FL-93 | Validate/preview a selected backup | `/restore`, `app.py:1793`; JSON validation at `backup.py:104`; counts/date range at `:144`; file input/check action `templates/restore.html:85`. |
| FL-94 | Merge or replace restore, with explicit confirmation | Modes at `templates/restore.html:60`; implementation `backup.py:167`. Merge inserts missing identities and skips collisions; replace clears covered tables before importing. Added/skipped results are displayed. |
| FL-95 | Privacy explanation and user-controlled export links | `/privacy`, `app.py:1758`; stored-data, never-done, backups and deletion explanations in `templates/privacy.html:20`, `:34`, `:49`, `:61`. |
| FL-96 | Help/troubleshooting | `/help`, `app.py:1766`; SMS permission/OEM capture issues, held messages, wrong category, duplicates, new-phone backup/restore, totals, offline use and unreadable-message export in `templates/help.html`. |

## Baseline limitations and incomplete/incorrect behavior

These are **TRACED** observations about the Flask baseline. They should not be treated as capabilities the native app must reproduce faithfully.

1. **“Full merchant history” is actually the latest 100 rows.**  
   `app.py:1195` applies `LIMIT 100` before calculating total, average, visit count and trend; `templates/merchant.html:72` labels the list “All transactions.” With 101 purchases, the oldest is omitted from both list and figures.

2. **Activity cannot page through more than 200 matching rows.**  
   `app.py:816` applies `LIMIT 200`; `templates/transactions.html` has no pagination/load-more control. Search may locate an older known transaction, but ordinary browsing cannot reach the full ledger.

3. **Transfer/refund detection does not correct the primary totals, despite Help saying it does.**  
   `analytics.py:535`–`:561` returns gross income/expense to the Home hero; `flow` is separate. The adjusted result appears only in the explanatory card at `templates/dashboard.html:88`. `templates/help.html:179`–`:186` says these movements are excluded from totals. A matching ₹100 debit/credit can therefore produce a detected transfer and adjusted spending of ₹0 while the Home “Spent” total still includes ₹100.

4. **Changing only a transaction’s category does not necessarily teach future categorization.**  
   The edit handler calls `record_confirmation` only inside the changed-merchant branch, `app.py:1165`–`:1175`; it otherwise updates the transaction category at `:1176`. That does not fulfill the general “correct a category once and it remembers” promise in `templates/welcome.html:39` or `templates/help.html:138`. The separate category-modal and bulk-review paths do teach.

5. **Import unconditionally claims automatic capture is on.**  
   `templates/import.html:7`–`:10` always renders “Automatic capture is on,” without checking the denied/unavailable state that Settings handles. This message is false for a no-SMS build or denied permission.

6. **There is no exposed CSV import workflow.**  
   Import accepts one SMS textarea (`templates/import.html:18`); the route parses SMS text (`app.py:1237`). The JSON restore error telling users to “import a CSV” through Import (`backup.py:123`) points to a capability that is not implemented in that screen.

7. **Recurring bills are inferred, read-only observations—not scheduled transactions.**  
   `analytics.py:182` detects cadence from existing merchant expenses and returns predictions; Home renders them at `templates/dashboard.html:147`. There is no route/form for creating or editing a recurring rule, scheduling a transaction, marking a bill paid, or dismissing an inferred bill.

8. **“Full backup” has explicit exclusions.**  
   The allowlist at `backup.py:42`–`:61` does not include fraud alerts/history, held messages, parse-miss diagnostics, raw transaction SMS bodies/senders, profile name or onboarding state. It preserves the listed ledger/learning data, but not every user-visible state.

9. **Some backend-supported operations are not exposed as UI features.**  
   The `/transactions/<id>/confirm` route exists (`app.py:890`), and Activity computes candidate names (`app.py:835`), but the current Activity template does not render those candidate/confirm controls. Merchant correction remains accessible through editing, and bulk review exposes category suggestions. Similarly, category rename/recolor, arbitrary sender trust selection, and alert reopening have no displayed controls.

No repository files were modified.