# Screens

Every full page extends `base.html`. Authenticated pages fill the `content` block and
render inside the `app-shell` (sidebar + main). The two auth pages fill the `auth` block
instead (no sidebar). Fragments (`_*.html`) are returned by AJAX endpoints and injected
into an existing page.

| Screen | Route(s) | Template | Purpose | Data shown |
|--------|----------|----------|---------|------------|
| Login | GET/POST `/login` | `login.html` | Email/password sign-in | `error`, prefilled `email` |
| Sign up | GET/POST `/signup` | `signup.html` | Create account (provisions default categories + settings) | `error`, `email`, `full_name` |
| Dashboard | GET `/dashboard` | `dashboard.html` | At-a-glance financial summary + insights | `d` (analytics dict), `user` |
| Transactions | GET `/transactions` | `transactions.html` | List/search/add/confirm/delete transactions | `transactions` (≤200), `total`, `q`, `categories`, `user` |
| SMS Import | GET `/import` | `import.html` | Paste bank/UPI SMS, parse + preview + save | `user` (results come via fragments) |
| Categories | GET/POST `/categories` | `categories.html` | List/add/delete categories | `categories` (incl. archived), `flash`, `error` |
| Fraud Alerts | GET `/fraud` | `fraud.html` | Review/dismiss/resolve anomaly alerts | `alerts` |
| Settings | GET/POST `/settings` | `settings.html` | Currency, theme, thresholds, high-value limit | `s` (settings row), `flash` |

## Screen detail

### Login (`login.html`)
Centered `auth-card`. Posts to `/login`. On `AuthError` re-renders with a 401 and inline
`error`. Link to `/signup`.

### Sign up (`signup.html`)
Same `auth-card` shell. Full name + email + password (min 8 chars, enforced in HTML).
On duplicate email re-renders with 409 + `error`. Successful signup logs in and redirects
to the dashboard. Account creation provisions 11 default categories and a settings row
(`auth.py:18-30`, `auth._provision`).

### Dashboard (`dashboard.html`)
Two rows of stat cards (Balance, Income, Expense, This month / Today, This week, Pending
confirmations, Open fraud alerts), then two-column grids: Insights + Top merchants, then
By-category + Monthly trend tables. All values come from `analytics.build_dashboard`
(`app.py:217`), which aggregates directly from the user's transactions. Open-fraud and
pending counts double as call-to-action hints. Currency string prefixes each amount.

### Transactions (`transactions.html`)
Left card: "Add transaction" form (amount, type, merchant, category, date, notes) posting
to `/transactions`. The merchant input has a live preview (`#resolve-preview`) populated by
debounced POSTs to `/transactions/resolve` returning the `_resolve.html` fragment.
Right card: search form (GET `?q=`). Below, a table of up to 200 transactions with date,
merchant (plus original raw name when different), notes, status pill, confidence badge,
signed amount, and a delete button. Pending/needs-review rows render an inline "confirm"
form (merchant override → `/transactions/<id>/confirm`) that feeds the learning loop.

### SMS Import (`import.html`)
Left card: textarea to paste an SMS, posts to `/import/parse` (intercepted by JS).
Right card (`#parse-out`): shows the `_import_preview.html` fragment (editable parsed
fields + engine resolution preview), whose embedded create-form posts to `/import/create`
and is replaced by the `_import_result.html` fragment.

### Categories (`categories.html`)
Left card: add-category form (name, type, colour) → POST `/categories`. The POST handler
re-renders this same page with `flash`/`error`. Right card: explanatory copy. Table of all
categories (including archived) with name, type pill, colour swatch, and delete button
(hard delete via `/categories/<id>/delete`).

### Fraud Alerts (`fraud.html`)
Table of alerts ordered newest-first: severity (colour-coded), type, message, status pill,
and Dismiss/Resolve actions for open alerts (POST `/fraud/<id>/status`). Empty state
explains what the engine watches for.

### Settings (`settings.html`)
Single form (max-width 560px): currency, theme (system/light/dark), auto-save threshold,
confirm threshold, optional high-value alert amount. POSTs to `/settings`; handler clamps
thresholds to 0–100 and re-renders with a flash (`app.py:421-434`). The chosen theme is
applied app-wide via `data-theme` on `<html>` (`base.html:2`).

## Fragments (partial templates)

| Fragment | Returned by | Injected into | Shows |
|----------|-------------|---------------|-------|
| `_resolve.html` | `/transactions/resolve` | `#resolve-preview` on Transactions | suggested merchant, confidence badge, decision label, score breakdown |
| `_import_preview.html` | `/import/parse` | `#parse-out` on Import | editable parsed fields + create form + resolution preview |
| `_import_result.html` | `/import/create` | `#parse-out` on Import | save confirmation, decision/confidence, fraud-alert + confirm hints |
