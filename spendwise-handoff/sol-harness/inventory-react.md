# Independent React inventory
Commit: 2fa8dc04461e3966f2db9c60181ce3effe79fa24. Source read with git show only. No Opus parity matrix read.
**TRACED** applies to every capability/limitation below; no browser or device runtime claim. Native mappings belong to the parent reviewer's independently sourced cross-app inventory.

| ID | Capability | Source at 2fa8dc0 | Limits/behavior |
|---|---|---|---|
| R01 | Six tabs: Dashboard, History, Budgets, Categories, Summary, Reports | App.tsx:448 | Mobile footer App.tsx:525 omits Categories. |
| R02 | Light/dark toggle; initial system theme | main.tsx:10; components/ThemeToggle.tsx:19 | Uses next-themes. |
| R03 | Persistent local ledger, budgets, categories and recurring schedules | store.ts:22; store.ts:44; store.ts:53 | localStorage; no account or synchronization. Errors log or return empty fallback. |
| R04 | Eleven default categories | store.ts:30; store.ts:65 | Four income, seven expense; includes name/icon/color. |
| R05 | Manual income/expense entry: description, amount, category, date | components/TransactionModal.tsx:63; App.tsx:232; store.ts:161 | Amount input step 0.01; categories filtered to entry type. |
| R06 | Edit transaction | components/HistoryTab.tsx:233; components/HistoryTab.tsx:280; App.tsx:204; store.ts:169 | Changes individual record; cannot edit recurring schedule. |
| R07 | Delete transaction with confirmation | App.tsx:326; store.ts:180 | Permanent; no trash or undo. |
| R08 | Recurring daily/weekly/monthly/yearly entry creation | components/TransactionModal.tsx:140; App.tsx:247; store.ts:185 | Starts selected date. No schedule list/edit/cancel UI. |
| R09 | Catch up due recurring entries when ledger loads | store.ts:86; store.ts:154 | Not background scheduling; max1000 per schedule per call; JS month rollover and UTC-today quirks at store.ts:73/82 should not be preserved. |
| R10 | All-time balance/income/expense cards | components/StatsGrid.tsx:11; App.tsx:337 | Uses full ledger. |
| R11 | Dismissible spending-up alert | components/DashboardTab.tsx:55; App.tsx:379 | Current-month expenses > previous month by 10%, prior month >0; dismissal only in mounted state. |
| R12 | Expense-category or income-source donut, toggle, hover, tooltip, legend | components/DashboardTab.tsx:93; App.tsx:341; App.tsx:421 | All-time; first six legend labels; empty state DashboardTab.tsx:148. |
| R13 | Recent five transactions, View all, manual-add/AI-import shortcuts | components/DashboardTab.tsx:169; components/DashboardTab.tsx:212; App.tsx:476 | Date-descending via store.ts:154; empty-state guidance. |
| R14 | Responsive transaction history table/cards | components/HistoryTab.tsx:190; components/HistoryTab.tsx:252 | Date, description, category, type, amount and actions. |
| R15 | Description search | components/HistoryTab.tsx:58; App.tsx:355 | Case-insensitive; no amount/category free-text search. |
| R16 | Category filter | components/HistoryTab.tsx:79; App.tsx:357 | All or one configured category. |
| R17 | Inclusive date-range filter/reset | components/HistoryTab.tsx:111; App.tsx:358 | Start/end optional; string comparisons of ISO dates. |
| R18 | Sort date/amount/category, ascending or descending | components/HistoryTab.tsx:148; App.tsx:359 | Six combinations. |
| R19 | Filtered ledger CSV download | components/HistoryTab.tsx:68; App.tsx:367 | Date,Description,Category,Type,Amount; naive escaping breaks quoted descriptions/category commas/newlines. Preserve export capability, not that bug. |
| R20 | Set/update monthly expense-category budget | components/BudgetsTab.tsx:32; App.tsx:163; store.ts:218 | Saves on blur; no explicit remove action; zero/negative accepted. |
| R21 | Current-month budget spent/limit, percentage/progress, warning/exceeded | components/BudgetsTab.tsx:20; components/BudgetsTab.tsx:70 | Warning >80%; zero can yield NaN/Infinity. Empty state at :108. |
| R22 | Category list grouped by income/expense with color | components/CategoriesTab.tsx:25; components/CategoriesTab.tsx:45 | Mobile navigation omission noted R01. |
| R23 | Create category with name/type/palette color | components/CategoryModal.tsx:25; :63; :85; App.tsx:190; store.ts:127 | Case-insensitive trimmed duplicate rejection. Icon fixed Tag, no icon picker. |
| R24 | Delete category with confirmation | components/CategoriesTab.tsx:55; App.tsx:180; store.ts:145 | No rename/edit UI; existing transactions and budgets are not reassigned. |
| R25 | Monthly income/expense/savings/savings-rate summary | components/SummaryTab.tsx:43; App.tsx:379; App.tsx:496 | Rate zero with no income. |
| R26 | Historical monthly income-vs-expense bar chart | components/SummaryTab.tsx:63; App.tsx:348 | Months present in ledger, chronological, tooltip. |
| R27 | Budget adherence percent/progress | components/SummaryTab.tsx:95; App.tsx:386 | Remaining budget fraction vs all monthly expenses, clamped zero; label/formula can be improved. |
| R28 | Average monthly spending, top category average, category-average chart | components/SummaryTab.tsx:111; App.tsx:388; App.tsx:499 | Denominator is count of months represented anywhere in ledger. |
| R29 | Weekly/monthly/yearly current-period report selector | components/ReportsTab.tsx:45; App.tsx:393 | No arbitrary historical period navigation. |
| R30 | Report spending total, expense-category donut/list, tooltip/empty state | components/ReportsTab.tsx:64; :102; App.tsx:403 | Expense only. |
| R31 | Report spending trend chart | components/ReportsTab.tsx:115; App.tsx:409 | Aggregates by month even in weekly/monthly mode; typically only one bar. Do not copy wrong granularity. |
| R32 | Paste SMS and Gemini-prefill manual transaction | components/SMSModal.tsx:52; App.tsx:273; vite.config.ts:12 | Conditional on build-injected GEMINI_API_KEY and external model/network. Returned description/amount/category/type/date require Save. No-key alert opens manual form at App.tsx:276. Not a deterministic offline parser. |
| R33 | SMS parsing spinner/disabled empty or busy action | components/SMSModal.tsx:63 | Failure only logs at App.tsx:319; no user error banner. |
| R34 | INR/Indian-number display | utils.ts:8 | en-IN, INR, rounds display to whole rupees; amount input/storage can contain paise. |
| R35 | Initial loading/empty states | App.tsx:431; components/DashboardTab.tsx:148; :205; components/BudgetsTab.tsx:108; components/ReportsTab.tsx:94 | Loading is around synchronous local-store reads. |

## Working-boundary caveats (all TRACED)

1. **Automatic React SMS intake is not a proven working capability of the shipped Android path.** App.tsx:115 registers only a live Capacitor listener while React is mounted. android/app/src/main/java/com/jeevavibeapp/spendwise/MainActivity.java:63 replaces the React assets with Flask once ready. SpendWisePlugin.java:29 reports granted=true without a real permission request. SmsReceiver.java:28 forwards only to the current plugin instance and keeps no durable queue. Do not count a successful phone auto-import as something native must port from React. This is a structural trace, not evidence that no developer ever made a transient setup run.
2. **Manual browser SMS paste remains a conditional feature**, not automatically excluded by the phone disconnection; it may run with configured key/network. No live Gemini service was tested.
3. Express/server.ts is not the React data path here: App.tsx:51 imports store; reads at :128/:139/:154 call local store. Do not count unused endpoint features.
4. PWA metadata exists in manifest.json, but inspected tree has no service-worker file/registration. Local CRUD works from loaded assets without network; install/cold-load offline was not verified.
5. No exposed React sign-in, full-backup restore, inbox scan, fraud review, merchant-resolution queue, notification or recurring-schedule-management UI exists in App.tsx/components/store.ts. Source-absence conclusion is limited to this surface.
