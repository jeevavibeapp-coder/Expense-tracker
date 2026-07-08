# Performance + Code/UI Quality Review — SpendWise mobile (`python_app/`)

**Scope:** Flask/Jinja app at `python_app/spendwise/`, running inside an Android WebView (Chaquopy/Capacitor) with an in-process Python + sqlite server. Reviewed read-only.

**Mandate constraint:** UI-only refactor with **identical functionality and routes**. Every recommendation below is framed to preserve current behavior, route contracts, and rendered output. Nothing here proposes a behavior change.

**Headline:** The front end is already very light and fully offline — no CDNs, no images, no web fonts, no JS frameworks, ~7 KB of CSS, two tiny inline scripts. This is the right architecture for a low-powered embedded WebView. Findings are mostly polish (caching headers, debounce/loading states, accessibility, CSS token hygiene) rather than structural problems. There are no heavy or blocking patterns to remove.

---

## 1. Front-end / WebView performance

### Strengths (keep these)
- **No external dependencies / fully offline-safe.** Confirmed: zero `http(s)://`, CDN, `googleapis`, web-font, or `<img>` references in `templates/` or `static/` (`python_app/spendwise/static/styles.css`, all templates). The only "external-looking" features are a CSS `radial-gradient` (`styles.css:88`) and a `prefers-color-scheme` query (`styles.css:11`) — both render-local and safe in a WebView.
- **Tiny CSS.** `styles.css` is ~7 KB / 104 lines, single stylesheet, single `<link>` (`base.html:7`). Selectors are flat (mostly single-class), so style recalculation cost on the WebView is negligible.
- **No blocking JS.** The two inline scripts (`transactions.html:68-90`, `import.html:21-43`) are small IIFEs at the end of `<body>`; no render-blocking `<script>` in `<head>`.
- **Server-rendered, partial-swap interactions.** `/transactions/resolve`, `/import/parse`, `/import/create` return HTML fragments swapped via `innerHTML` — no client-side JSON rendering, no template engine on-device. Good fit for WebView.

### P2 — No cache headers on the static stylesheet
`styles.css` is served by Flask's default static handler. In an embedded WebView the stylesheet is effectively immutable per app build, but without a long-lived `Cache-Control`/`ETag` strategy the WebView may re-request/re-validate it on every navigation (every page is a full server round-trip — there is no SPA). Recommendation (config-only, no UI change): set `SEND_FILE_MAX_AGE_DEFAULT` (or serve `styles.css` with a far-future cache header + a build hash in the `url_for` query) so the WebView caches it across the many full-page loads this app does. Purely a serving optimization; output is identical.

### P2 — Debounce / keyup interaction on `/transactions/resolve`
`transactions.html:86-88` debounces the merchant `input` at **400 ms** and fires on the `input` event (good — not `keyup`-per-keystroke). Two refinements, all UI-layer:
- The request is **not cancelled** when a newer keystroke arrives, so a slow on-device response for an earlier prefix can overwrite a newer one (last-write-wins by network order, not by typing order). Add an `AbortController` (or a request-sequence guard) so stale fragments don't clobber the preview. Offline + in-process the latency is low, but on a cold Chaquopy worker the first request can be slow.
- No **loading/pending affordance**: between keystroke and fragment swap the preview area (`#resolve-preview`) is empty/stale with no skeleton. A lightweight `aria-busy`/spinner-dot (pure CSS, no asset) improves perceived performance. See §“Charts/animation” below for a dependency-free approach.

### P2 — `/import/parse` re-binds a submit listener each parse
`import.html:32-39`: after each parse the code finds `#create-form` and **adds** a `submit` listener. Because the fragment is replaced wholesale on every parse this currently works, but it is fragile and (if a fragment is ever swapped without replacing the node) could double-bind and double-POST. UI-safe fix: use a single delegated listener on the stable `#parse-out` container instead of re-binding per swap. Preserves the exact request/route flow.

### P3 — Perceived performance: no skeleton/loading states anywhere
Full-page navigations and the two fetch interactions show no loading state. On a cold embedded Python server the first request after app launch can feel slow. Add minimal, dependency-free affordances (CSS-only):
- Disable the Parse/Add buttons + show an inline “Working…” while the fetch is in flight (`import.html`, `transactions.html`).
- A CSS-only shimmer/pulse on `#resolve-preview` / `#parse-out` while loading.

### P3 — Charts / animation guidance (lightweight, offline, dependency-free)
The dashboard currently renders trend/category/merchant data as **tables** (`dashboard.html:22-37`) and the engine breakdown as **pure-CSS bars** (`_macros.html:27-42`, `.bd-track`/`.bd-fill` in `styles.css:83-87`). This is the correct pattern for a WebView — keep it. If richer visuals are wanted later, stay dependency-free:
- **Bars/progress:** extend the existing `.bd-fill` div technique (already used) — no library.
- **Sparklines / trend lines:** inline `<svg>` `<polyline>` generated in Jinja from `d.trend` — a few hundred bytes, no JS, GPU-friendly.
- **Donut/category split:** single inline-SVG `<circle>` with `stroke-dasharray`, computed server-side.
- **Animation:** prefer CSS `transform`/`opacity` transitions (compositor-only, cheap on WebView) over `width`/`height`/`top` animations which trigger layout. The existing sidebar uses `transition: left .2s` (`styles.css:100`) — animating `left` triggers layout/paint; switching to `transform: translateX()` would be smoother on low-end devices (P3, cosmetic).
- Avoid Chart.js/D3/Canvas libraries entirely — bundle weight + main-thread cost is wrong for this target.

### Notes (no action)
- `.cards` uses `repeat(auto-fit, minmax(180px,1fr))` and `.cols-2` collapses at 820px (`styles.css:39-40,96-104`) — responsive and cheap.
- `position: sticky` sidebar at `height:100vh` (`styles.css:25`) is fine; the mobile breakpoint switches it to off-canvas `position: fixed` (`styles.css:100`).

---

## 2. Back-end performance (serving the WebView)

### Per-request sqlite connection handling
- `app.py:37-47` opens a **fresh `sqlite3.connect()` on every request** (`before_request`) and closes it in `teardown_request`. `db.connect` (`db.py:102-110`) also runs `PRAGMA journal_mode=WAL` and `PRAGMA foreign_keys=ON` **on every connection**. For an in-process on-device server this is per-request overhead (connection open + 2 pragmas) on every navigation and every fragment fetch.
- **P2, behavior-preserving:** `journal_mode=WAL` is a database-level persistent setting — it does not need to be re-issued per connection (only `foreign_keys` is per-connection). This is a serving-cost note; since the mandate is UI-only, flag it for a future backend pass rather than changing it now. No UI impact either way.

### Indexes — present vs. missing (`db.py` SCHEMA)
Present:
- `ix_learning_user_raw` on `learning(user_id, raw_name)` — covers the hot resolve lookup (`engine.py:129-131`). Good.
- `ix_tx_user_occurred` on `transactions(user_id, occurred_at)` — covers the transactions list `ORDER BY occurred_at` (`app.py:236`) and the dashboard date-range sums. Good.

Missing / worth noting (informational — **do not add under a UI-only mandate**, but record for a backend pass):
- `fraud_alerts(user_id, status)` and `fraud_alerts(user_id, created_at)` — the fraud list (`app.py:397-399`) and the dashboard open-alert count (`analytics.py:98-100`) scan by `user_id`/`status` with no index.
- `transactions(user_id, type, is_deleted)` — the dashboard's repeated `SUM(amount) WHERE type=... AND is_deleted=0` aggregations (`analytics.py:13-42`) and `merchant_name GROUP BY` (`analytics.py:27-33`) are not covered by the occurred-at index.
- `categories(user_id)` / `merchants(user_id, canonical_name)` are backed by their `UNIQUE` constraints, so lookups there are fine.

On realistic personal-finance data volumes (hundreds–low-thousands of rows) these scans are cheap; the missing indexes matter only at scale and are out of scope for this refactor.

### Dashboard aggregation cost
`analytics.build_dashboard` (`analytics.py:80-110`) issues **~9 separate queries** per dashboard load: 3 date-range expense sums, 2 totals, top-merchants GROUP BY, category GROUP BY, trend GROUP BY, pending count, fraud count. Each is a separate full-or-partial scan of `transactions`. This is not N+1 (no per-row queries) but it is several independent passes over the same table on a single page. For the on-device server this is the heaviest page. **Informational only** under the UI mandate — a future backend pass could fold the three running-total sums into one grouped query. No UI change needed; the template consumes the same `d` dict.

### N+1 patterns
- No classic N+1 in the request paths. Dashboard, transactions list, categories, fraud all use set-based queries. The transactions list (`app.py:221-240`) is a single query with `LIMIT 200` — bounded and good.
- `engine.record_confirmation` (`engine.py:232-245`) does a small loop over "other" learning rows on a correction, with a re-`SELECT` per row inside the loop (`engine.py:239`). This is bounded by the number of competing mappings for one raw name (typically 0–2) and only runs on confirm/correction, not on read paths. Low impact; flag only.

### Merchant resolve path (the keyup-driven endpoint)
- `/transactions/resolve` → `engine.resolve` (`app.py:257-270`, `engine.py:122-143`): one indexed `SELECT` on `learning(user_id, raw_name)` + in-memory scoring of the (small) candidate set. This is **fast and index-backed** — well-suited to being hit on a debounce. Combined with the 400 ms client debounce, the load is fine.
- Minor: each resolve call also calls `settings_for(uid)` (`app.py:265`) which is a `SELECT * FROM settings` (PK lookup) — cheap, but note it runs on every keystroke-debounced request. No change required.

### Cold-start
The biggest on-device "slow" feeling will be **first request after app launch** (Chaquopy/Python worker warm-up + initial connection), not steady-state query cost. The loading/skeleton affordances in §1 are the right mitigation; the backend itself is appropriately lean.

---

## 3. Code / UI quality (templates + CSS)

### P1 — Accessibility
1. **Icon-only delete buttons have no accessible name.** `✕` buttons in `transactions.html:61` and `categories.html:35` render as a literal multiplication-sign glyph with no `aria-label`/visible text → screen readers announce nothing meaningful. Add `aria-label="Delete transaction"` / `"Delete category"`. (Markup-only, no behavior change.)
2. **Touch-target size.** `.btn-sm` is `padding: 6px 12px` (`styles.css:74`) and the `✕` button is tiny — below the ~44×44px recommended touch target for a finger on a phone. Bump min hit area (padding or `min-height`) for `.btn-sm` used as row actions. WebView/mobile-specific.
3. **Form inputs without `for`/`id` association.** Most `<label>`s in `transactions.html`, `categories.html`, `settings.html`, `import.html` wrap text but are **not** tied to their input via `for`/`id` (e.g. `transactions.html:11-24`, `settings.html:9-24`). The auth pages do it correctly (`login.html:9`, `signup.html:9`). Add matching `for`/`id` so taps on the label focus the field and SRs pair them.
4. **Color contrast of `--muted` on `--surface`.** `--muted: #6b7280` on `#ffffff`/`#f6f7fb` (`styles.css:2-3`) is borderline ~4.5:1 for the small `.label`/`.pill`/`small` text (`styles.css:44,49,53`) used heavily for table headers and badges. Verify against WCAG AA at the small sizes used (`.72rem`–`.8rem`); darken `--muted` slightly if it fails. In dark mode `--muted:#9aa3b2` on `#0b0d12` is likely fine.
5. **Off-canvas nav is not keyboard/SR accessible.** The hamburger toggles `body.nav-open` via inline `onclick` (`base.html:35`); when closed the sidebar is `left:-260px` (`styles.css:100`) but still in the tab order and not `aria-hidden`. No focus management. Low-risk to leave, but flag for the accessibility pass.

### P2 — CSS token / magic-number hygiene
1. **Hard-coded rgba colors that bypass the CSS variables.** Badge/pill backgrounds repeat literal `rgba(16,185,129,.15)`, `rgba(245,158,11,.16)`, `rgba(239,68,68,.15)`, etc. (`styles.css:54-61,77-78`) — these are the `--ok`/`--warn`/`--danger` colors re-expressed as fixed rgba and **will not follow theme changes**. Introduce `--ok-soft`/`--warn-soft`/`--danger-soft` tokens (or use `color-mix`/relative-color where the WebView's Chromium supports it) so the tinted backgrounds derive from the source token. Visual output unchanged if the tokens are seeded with today's values.
2. **One-off `--radius` vs. ad-hoc radii.** `--radius:14px` is defined (`styles.css:5`) but many elements hard-code `10px`, `9px`, `999px`, `18px`, `4px` (`styles.css:28,66,69,72,73,90,...`). Not wrong, but a small radius scale (`--radius-sm/-md/-pill`) would remove magic numbers and unify the look.
3. **Inline styles scattered through templates.** Layout/spacing is repeatedly inlined: `style="margin-top:16px"` on every dashboard grid (`dashboard.html:13,19,27`), `style="max-width:560px"` (`settings.html:5`), `style="background:var(--surface-2);padding:12px"` repeated across `_resolve.html:3`, `_import_preview.html:20`, `_import_result.html:3`, and the inline flex `display:flex;align-items:center;gap:8px;flex-wrap:wrap` repeated 3× (`_resolve.html:4`, `_import_preview.html:21`, `_import_result.html:4`). Promote these to utility classes (e.g. `.mt-16`, `.panel-soft`, `.row-inline`) to cut duplication and centralize the design tokens. Pure refactor — identical rendering.

### P2 — Template duplication / structure
1. **The `require_login()` + redirect preamble is repeated in ~12 routes** (`app.py` — every GET/POST view starts with the same 3 lines). Not a template issue but a maintainability one; a small decorator would remove it without changing any route's behavior or output. (Backend tidy — optional under UI mandate.)
2. **Soft-panel "Engine suggests / Resolves to / decision" block is near-duplicated** across `_resolve.html`, `_import_preview.html`, `_import_result.html` (same flex header + confidence badge + decision label + `breakdown_bars`). Extract a shared macro (e.g. `m.resolution_panel(...)`) in `_macros.html` to DRY it. Output identical.
3. **`merchant_breakdown` is dead/duplicate data.** `analytics.py:106` returns both `top_merchants` and `merchant_breakdown` set to the **same** list; the template only consumes `top_merchants` (`dashboard.html:22-25`). Harmless, but `merchant_breakdown` is unused — drop it in a cleanup (no UI impact).

### P3 — Minor consistency / maintainability
1. **Inline event handlers** (`base.html:35` `onclick=...`, `transactions.html:60` / `categories.html:34` `onsubmit="return confirm(...)"`). Works, but mixing inline handlers with the IIFE scripts is inconsistent; consider centralizing. (Note: `confirm()` dialogs in a WebView may need the host to allow JS dialogs — worth verifying the Capacitor/Chaquopy config surfaces them.)
2. **Emoji as brand/icon** (`base.html:13`, `login.html:5`, hamburger `☰`, delete `✕`). Renders fine but depends on the device emoji font; acceptable for a dependency-free app, just noted.
3. **`d.currency` is concatenated as a prefix** (`{{ cur }} {{ m.money(...) }}`) throughout `dashboard.html` — fine, but a single `money(value, currency)` macro would centralize formatting and spacing.
4. **`status_pill` falls through to raw status text** for unknown statuses (`_macros.html:17`) — minor, but could render an internal token to the user; low risk.

---

## Prioritized summary

| Pri | Area | Item | Refs |
|-----|------|------|------|
| P1 | a11y | Icon-only `✕` buttons lack accessible names | `transactions.html:61`, `categories.html:35` |
| P1 | a11y | Labels not associated (`for`/`id`) on main forms | `transactions.html`, `settings.html`, `categories.html`, `import.html` |
| P1 | a11y | Touch targets too small (`.btn-sm` row actions) | `styles.css:74` |
| P1 | a11y | `--muted` small-text contrast needs WCAG-AA check | `styles.css:2,44,49` |
| P2 | perf-FE | Add cache headers for `styles.css` (full-page nav app) | `base.html:7`, Flask config |
| P2 | perf-FE | Resolve fetch: cancel stale requests + loading state | `transactions.html:80-88` |
| P2 | perf-FE | `/import/parse`: delegate listener instead of per-swap re-bind | `import.html:32-39` |
| P2 | quality | Themed tints hard-coded as rgba (bypass tokens) | `styles.css:54-61,77-78` |
| P2 | quality | Repeated inline styles → utility classes | `dashboard.html`, `_resolve/_import_*.html` |
| P2 | quality | Extract shared resolution-panel macro | `_resolve.html`, `_import_preview.html`, `_import_result.html` |
| P3 | perf-FE | Skeleton/loading affordances; SVG sparklines if charts wanted | dashboard + fetch interactions |
| P3 | perf-FE | Animate sidebar via `transform` not `left` | `styles.css:100` |
| P3 | quality | Drop unused `merchant_breakdown`; centralize money/currency | `analytics.py:106`, `dashboard.html` |

**Backend perf (informational, out of UI scope but recorded):** per-request connection + redundant `WAL` pragma (`app.py:37`, `db.py:108`); dashboard does ~9 separate scans of `transactions` (`analytics.py:80-110`); missing indexes on `fraud_alerts(user_id,status)` and `transactions(user_id,type,is_deleted)`. None of these are N+1, and the resolve hot-path is properly indexed (`ix_learning_user_raw`).
