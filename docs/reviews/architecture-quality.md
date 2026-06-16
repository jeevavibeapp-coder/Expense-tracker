# Architecture Quality Review

Scope: `python_app/spendwise/` (Flask + Jinja2 + stdlib-sqlite3), the app shipped inside
the Android APK. **Mandate constraint:** the upcoming work is a *UI-only refactor that
keeps functionality identical*. Findings below are framed around what helps or blocks a
clean UI/template/CSS refresh **without changing behavior**. No backend rewrites are
proposed; backend observations are included only as context or as low-risk seams.

## Summary scorecard

| Dimension | Rating | One-line |
|-----------|:------:|----------|
| Separation of concerns (services vs web) | Good | Engine/parsing/fraud/analytics/auth are pure, take `conn`, no Flask imports |
| Coupling | Mostly good | Services decoupled; `app.py` is a single large factory closure |
| Route/handler design | Good | Thin handlers, consistent auth gating, clear REST-ish verbs |
| Template structure | Fair | Good inheritance + macros, but inline styles and HTML-in-Python strings hurt a UI refactor |
| Testability | Good | Factory pattern, tmp_path DB fixtures, service-level + e2e tests |
| Architecture smells | Few | Mostly localized; see P2/P3 |

The codebase is small (≈1.4k LOC of app code) and unusually clean for its size: the
business logic is genuinely separated from the web layer, which is exactly what makes a
UI-only refactor safe. Most findings are about *template hygiene* that will make the
refactor faster and lower-risk, not correctness problems.

---

## P1 — Address before / during the UI refactor

### P1.1 Raw HTML strings returned from Python bypass the template layer
`app.py:340` and `app.py:264` return literal HTML/empty strings instead of templates:
- `app.py:340` `return '<p class="error">Could not read the amount.</p>'`
- `app.py:264` `return ""` (empty body when `/transactions/resolve` gets a blank merchant)

Why it matters for a UI refactor: these strings carry markup and a CSS class (`.error`)
that live *outside* the templates a UI pass will touch. If the refresh restyles `.error` or
changes the fragment container markup, these two responses silently diverge from the rest of
the UI. **Recommendation (behavior-preserving):** move these into tiny fragments (e.g. an
`_error.html` partial / extend `_import_result.html`) or have the JS render the empty/error
state, so all user-visible markup lives in templates. No backend logic changes — only where
the HTML string lives.

### P1.2 Score-bar max values are duplicated between engine and template
`_macros.html breakdown_bars` hard-codes `40/20/15/15/10`; the authoritative weights are in
`engine.py:23-27`. A UI refactor that touches the breakdown component could accidentally
edit these numbers (they look like styling), changing the meaning of the bars. **Keep them
in sync; treat the breakdown macro as logic-bearing, not decorative.** Documented in
`docs/architecture/components.md` and `merchant-learning.md` so future editors are warned.

---

## P2 — Strongly recommended cleanups (enable a clean UI pass)

### P2.1 Pervasive inline `style="..."` attributes
Inline styles defeat the stylesheet and make a CSS-driven refresh inconsistent. Locations:
- `_resolve.html:3,4` (`margin-top`, `padding`, `background`, flex layout)
- `_import_preview.html:20,21` (card background/padding)
- `_import_result.html:3,4,10,11,13` (background, padding, margins)
- `dashboard.html:13,17,19,27,32` (`margin-top`, conditional `expense` colour)
- `transactions.html:50,51,59` (inline flex confirm form, `max-width`)
- `categories.html:32` (colour swatch span)
- `settings.html:5` (`max-width:560px`)

**Recommendation:** lift these into named classes in `styles.css` (e.g. `.preview-card`,
`.confirm-form`, `.swatch`, `.settings-form`). This is purely presentational and preserves
behavior — the ideal first step of the UI refactor, and it makes subsequent restyling a
single-file change.

### P2.2 Sidebar nav + active-state logic is inline in `base.html`
`base.html:15-24` builds the nav list and active highlighting inline. It works, but the nav
model (keys, hrefs, labels) is not reusable and the `active` flag must be threaded through
every GET route's `render_template` call (`app.py:218,240,315,357,400,436`). **For the
refactor:** extract the nav into a macro or a small partial driven by a single list, so a
redesign (icons, grouping, bottom-tab-bar on mobile) is one edit. Behavior identical.

### P2.3 Hard-coded route paths in templates instead of `url_for`
Templates use literal paths (`/dashboard`, `/transactions`, `/transactions/{{ id }}/confirm`,
`/fraud`, `/logout`, etc.) throughout `base.html`, `transactions.html`, `categories.html`,
`fraud.html`, `_import_result.html`. The app otherwise uses `url_for` in Python. Literal
paths are fine while routes are stable, but during a UI refactor that reorganizes templates
it's easy to typo a path with no error. **Low-risk improvement:** prefer `url_for('endpoint')`
in templates. Optional, but it makes the markup self-checking.

### P2.4 Inline `<script>` blocks embedded in page templates
`transactions.html:68-90` and `import.html:21-43` contain the AJAX logic inline. For a UI
refactor this couples behavior to markup (e.g. element ids `#resolve-preview`, `#parse-out`,
`#create-form`, `#amt`, `#merchant` are referenced from JS). **Recommendation:** when
restructuring those pages, keep the element ids stable or move the scripts to a static JS
file so the contract between markup and behavior is explicit. Do not change the fetch
endpoints or payloads (that would change behavior).

---

## P3 — Minor / nice-to-have

### P3.1 `app.py` is one large factory closure (~440 lines)
All routes and several helpers (`create_transaction`, `settings_for`, `categories_for`,
`parse_amount/date`) are nested closures inside `create_app` (`app.py:23-442`). It reads
fine and aids testability via the factory, but the file mixes route wiring, request
helpers, and the fat `create_transaction` orchestration (`app.py:100-166`). This is a
backend concern and **out of scope for the UI mandate** — noted only so the UI work isn't
blamed for the file's size. No change recommended now.

### P3.2 Two macros encode overlapping status vocabularies
`status_pill` (tx status) and `decision_label` (engine decision) in `_macros.html` use
similar pill styles for parallel concepts. Fine as-is; if the refresh unifies status
visuals, consider a shared pill helper. Cosmetic.

### P3.3 Mixed emoji in markup (`💸`, `☰`, `⚠`, `✕`)
`base.html:13,35`, `transactions.html:61`, `_import_result.html:10`, etc. use emoji as
brand/icon/affordances. A redesign may swap these for an icon set (the data model already
stores lucide-style icon names like `Tag`, `Wallet` in categories). Purely visual.

### P3.4 `_macros.money` vs raw `'%.2f'|format` inconsistency
Some templates use the `money` macro; others format inline (e.g. `_import_preview.html:5`,
`settings.html:24`). Harmonizing on the macro would centralize currency formatting for the
refresh. Cosmetic, behavior-neutral.

---

## Testability notes

- Application-factory pattern (`create_app`) + `tmp_path` sqlite fixtures
  (`tests/conftest.py`) give fully isolated tests; `test_app.py` drives the real routes via
  `app.test_client()` and `test_engine.py` unit-tests the engine against a raw `conn`.
- Because services take `conn` explicitly and avoid Flask globals, they're testable without a
  request context — a strong foundation that a UI refactor will not disturb.
- **UI-refactor safety net:** the existing e2e tests assert on rendered bytes (e.g.
  `b"Food &amp; Dining"`, `b"Dashboard"`). A markup-restructuring pass may break these on
  cosmetic grounds; budget for updating assertions, and prefer asserting on stable text/ids
  over incidental markup.

## What a clean UI refactor should preserve (do-not-touch contract)

1. Endpoint paths, HTTP verbs, and form field names (`amount`, `type`, `merchant`,
   `category_id`, `raw_merchant`, `reference_number`, `occurred_at`, `notes`, `q`, `status`,
   `sms`, threshold fields) — handlers read these by name.
2. The AJAX element-id contract on Transactions and Import (`#resolve-preview`, `#parse-out`,
   `#create-form`, `#amt`, `#merchant`, `#parse-form`) and the fetch endpoints they call.
3. The status/decision/severity string vocabularies that drive pill/badge selection.
4. The breakdown weights (40/20/15/15/10) and the `data-theme` mechanism on `<html>`.

Everything else (layout, classes, colors, tokens, spacing, copy, iconography) is fair game
for the refresh.
