# SpendWise — Premium Fintech Mobile Design System Spec

**Status:** Implementation-ready · **Target:** Flask/Jinja mobile app in Android WebView (Capacitor)
**Constraints:** Pure server-rendered HTML/CSS + tiny vanilla JS. **Fully offline** — no CDNs, no React, no Tailwind, no chart libraries, no remote fonts/icons. Everything self-contained: system font stack, inline SVG, CSS.
**Inspirations:** CRED (depth, restraint, premium dark surfaces, confident accent), Fold Money (insight cards, spend story), Ivy Wallet (compact balance hero + colourful category grid), Google Pay (avatar-led transaction rows, thumb-reach FAB), Splitwise (date-grouped activity timeline, signed amounts), Monarch Money (5-item bottom nav + profile drawer, review queue), YNAB (confidence/assignment language, semantic money colour).

> This spec is **additive to the existing token contract**. The current `static/styles.css` already exposes `--bg --surface --surface-2 --text --muted --border --primary --primary-d --ok --warn --danger --review --radius --shadow` and the existing class names (`.card .stat .badge .pill .btn` etc.) are referenced across templates. **Keep every existing variable name and class name working** — extend, don't rename. New tokens are layered on top.

---

## 0. Design principles (the "feel")

1. **Dark-first, premium.** The default experience is a deep near-black canvas with layered elevated surfaces, one confident accent (indigo→violet), and money-semantic green/red. Light theme is a first-class mirror.
2. **Money is the hero, chrome is silent.** Big tabular numerals, generous negative space, no decorative borders where elevation can do the job. NOT an admin dashboard — no wall of equal-weight stat cards, no data tables on the primary surfaces.
3. **The merchant-learning / confidence engine is the signature feature.** Confidence is surfaced as a first-class visual citizen (rings, animated bars, pills), and the confirmation queue is a hero surface, not a footnote in a table.
4. **Thumb-first.** Primary actions live in the bottom 1/3 of the screen (bottom nav + FAB). The top app bar is for context/identity only.
5. **Tasteful motion.** Entrances stagger in, presses depress, the spend ring draws itself. All pure CSS / minimal JS, all respecting `prefers-reduced-motion`.

---

## 1. Design tokens

All tokens are CSS custom properties on `:root` (dark-first) with a `[data-theme="light"]` override and a `prefers-color-scheme` bridge for `theme="system"`. The base template already sets `<html data-theme="{{ s.theme }}">`.

> **Migration note:** today `:root` is the *light* palette and `[data-theme="dark"]` overrides it. **Flip this**: make `:root` the dark palette (premium default), add `[data-theme="light"]`, and keep the `prefers-color-scheme` bridge for `system`. The variable *names* stay identical so all templates keep working.

### 1.1 Colour — dark (default `:root`)

```css
:root {
  /* ---- Background layers (deepest → nearest) ---- */
  --bg:          #0A0B0F;   /* app canvas, behind everything */
  --bg-elevated: #101218;   /* scroll containers / sheets backdrop */
  --surface:     #15171F;   /* primary card surface */
  --surface-2:   #1C1F2A;   /* nested surface / input / track */
  --surface-3:   #252936;   /* hover / pressed / chip on surface-2 */

  /* ---- Text ---- */
  --text:        #ECEEF4;   /* primary */
  --text-2:      #A8AFBF;   /* secondary */
  --muted:       #6E7689;   /* tertiary / captions / disabled */
  --border:      #242838;   /* hairline 1px separators */
  --border-2:    #2F3445;   /* stronger separator / input border */

  /* ---- Brand / accent ---- */
  --primary:     #7C5CFF;   /* indigo-violet — primary CTA, active nav */
  --primary-d:   #6A48F0;   /* pressed */
  --primary-soft:rgba(124,92,255,.16);   /* tints, focus ring base */
  --accent:      #34E1C4;   /* teal — secondary highlight / "smart" engine cues */
  --accent-soft: rgba(52,225,196,.15);

  /* ---- Semantic money & status ---- */
  --income:  #2FD27A;   --income-soft: rgba(47,210,122,.15);   /* credit / positive */
  --expense: #FF5C6C;   --expense-soft: rgba(255,92,108,.15);  /* debit / negative */
  --ok:      #2FD27A;   /* alias for --income (existing class names) */
  --warn:    #FFB02E;   --warn-soft: rgba(255,176,46,.16);     /* confirm / mid confidence */
  --danger:  #FF5C6C;   /* alias for --expense */
  --review:  #4DA3FF;   --review-soft: rgba(77,163,255,.16);   /* needs-review / info */

  /* ---- Confidence scale (engine signature) ---- */
  --conf-high: var(--income);
  --conf-mid:  var(--warn);
  --conf-low:  var(--expense);

  /* ---- Radii ---- */
  --r-xs: 8px; --r-sm: 12px; --r-md: 16px; --radius: 16px; /* --radius kept as alias */
  --r-lg: 22px; --r-xl: 28px; --r-pill: 999px;

  /* ---- Elevation (shadows tuned for dark) ---- */
  --shadow:    0 1px 2px rgba(0,0,0,.40), 0 1px 3px rgba(0,0,0,.30); /* existing alias = e1 */
  --e1: 0 1px 2px rgba(0,0,0,.40), 0 1px 3px rgba(0,0,0,.30);
  --e2: 0 4px 12px rgba(0,0,0,.45), 0 2px 4px rgba(0,0,0,.30);
  --e3: 0 12px 32px rgba(0,0,0,.55), 0 4px 8px rgba(0,0,0,.35);   /* sheets, FAB */
  --glow-primary: 0 8px 24px rgba(124,92,255,.35);                /* FAB / hero accents */

  /* ---- Gradients (premium depth, cheap to render) ---- */
  --grad-hero:    linear-gradient(155deg, #1B1D2B 0%, #14151F 60%, #101119 100%);
  --grad-primary: linear-gradient(135deg, #8B6BFF 0%, #6A48F0 100%);
  --grad-accent:  linear-gradient(135deg, #3DEBCE 0%, #21B79E 100%);

  /* ---- Spacing scale (8pt grid; 4px sub-step) ---- */
  --s-1: 4px;  --s-2: 8px;  --s-3: 12px; --s-4: 16px;
  --s-5: 20px; --s-6: 24px; --s-7: 32px; --s-8: 40px; --s-9: 48px; --s-10: 64px;

  /* ---- Type scale (see §1.3) ---- */
  --fs-display: 34px; --fs-h1: 26px; --fs-h2: 20px; --fs-h3: 17px;
  --fs-body: 15px; --fs-sm: 13px; --fs-xs: 11px;
  --lh-tight: 1.15; --lh-snug: 1.3; --lh-normal: 1.5;
  --fw-regular: 400; --fw-medium: 500; --fw-semibold: 600; --fw-bold: 700; --fw-black: 800;

  /* ---- Motion ---- */
  --dur-1: 120ms;  --dur-2: 200ms;  --dur-3: 320ms;  --dur-4: 480ms;
  --ease-standard: cubic-bezier(.2,0,0,1);     /* enter/move (Material emphasized-ish) */
  --ease-out:      cubic-bezier(.16,1,.3,1);   /* expressive entrances */
  --ease-spring:   cubic-bezier(.34,1.56,.64,1); /* press release / pop */

  /* ---- Layout ---- */
  --bottomnav-h: 64px;
  --appbar-h: 56px;
  --content-max: 480px;     /* phone-first; centres on tablet */
  --safe-top: env(safe-area-inset-top, 0px);
  --safe-bottom: env(safe-area-inset-bottom, 0px);
}
```

### 1.2 Colour — light (`[data-theme="light"]` + system bridge)

```css
[data-theme="light"] {
  --bg:#F4F5F9; --bg-elevated:#FFFFFF; --surface:#FFFFFF; --surface-2:#F1F3F9; --surface-3:#E7EAF3;
  --text:#11131A; --text-2:#3F4658; --muted:#717789; --border:#E6E9F1; --border-2:#D7DBE8;
  --primary:#6A48F0; --primary-d:#5638DE; --primary-soft:rgba(106,72,240,.12);
  --accent:#12A594; --accent-soft:rgba(18,165,148,.12);
  --income:#0FA968; --income-soft:rgba(15,169,104,.12);
  --expense:#E5484D; --expense-soft:rgba(229,72,77,.12);
  --ok:#0FA968; --danger:#E5484D; --warn:#C7780A; --warn-soft:rgba(199,120,10,.14);
  --review:#2E7CE6; --review-soft:rgba(46,124,230,.12);
  --shadow:0 1px 2px rgba(16,24,40,.06), 0 1px 3px rgba(16,24,40,.10);
  --e1:0 1px 2px rgba(16,24,40,.06),0 1px 3px rgba(16,24,40,.08);
  --e2:0 4px 14px rgba(16,24,40,.10),0 2px 4px rgba(16,24,40,.06);
  --e3:0 14px 40px rgba(16,24,40,.16),0 4px 10px rgba(16,24,40,.08);
  --grad-hero:linear-gradient(155deg,#FFFFFF 0%,#F6F7FC 60%,#EEF0F8 100%);
}
/* "system" follows the OS */
@media (prefers-color-scheme: light) {
  html[data-theme="system"] {
    /* …repeat the [data-theme="light"] block… */
  }
}
```
> Rationale: the dark palette draws from CRED/Fold's near-black canvas with layered greys and a single saturated accent. The light palette mirrors it with WCAG-AA contrast (text-on-surface ≥ 7:1, muted ≥ 4.5:1).

### 1.3 Typography

**Offline-safe premium stack.** No web font download. We lean on the platform's best UI face and tune weight/tracking/tabular-numerals to feel designed. On Android WebView this resolves to **Roboto**; on iOS WebView to **SF**.

```css
:root {
  --font-sans: -apple-system, BlinkMacSystemFont, "SF Pro Text", "Segoe UI",
               Roboto, "Helvetica Neue", Arial, system-ui, sans-serif;
  /* Numerals: same stack but we always apply tabular-nums for money alignment */
  --font-num: var(--font-sans);
}
body { font-family: var(--font-sans); font-size: var(--fs-body);
       line-height: var(--lh-normal); -webkit-font-smoothing: antialiased;
       text-rendering: optimizeLegibility; }
```

> **Optional single bundled font (still offline):** if a stronger brand voice is wanted, bundle ONE variable font as a local `@font-face` `woff2` placed in `static/fonts/` (e.g. *Inter* for UI or *Sora*/*Space Grotesk* for headers). Self-hosted = offline-safe. Use only if approved; the system stack above ships zero bytes and is the default.

**Type scale** (mobile, 1.25-ish modular):

| Token | Size | Weight | LH | Tracking | Use |
|---|---|---|---|---|---|
| `--fs-display` | 34px | 800 | 1.1 | -0.02em | Balance hero number |
| `--fs-h1` | 26px | 700 | 1.15 | -0.02em | Screen title / amounts |
| `--fs-h2` | 20px | 700 | 1.2 | -0.01em | Section / card title |
| `--fs-h3` | 17px | 600 | 1.3 | -0.005em | Row title / merchant name |
| `--fs-body` | 15px | 400/500 | 1.5 | 0 | Body, inputs |
| `--fs-sm` | 13px | 500 | 1.4 | 0 | Secondary, captions |
| `--fs-xs` | 11px | 700 | 1.3 | 0.04em (UPPERCASE labels) | Eyebrow labels, pills |

**Money rule:** every currency figure uses `font-variant-numeric: tabular-nums; letter-spacing:-.01em;` so columns align and digits don't jiggle during the count-up animation.

### 1.4 Spacing, radii, elevation — quick reference

- **Spacing:** strict 8pt grid (`--s-2`=8 … `--s-10`=64), with a single 4px sub-step (`--s-1`) for tight icon gaps. Screen gutter = `--s-4` (16px). Card padding = `--s-5` (20px). Section rhythm = `--s-6/--s-7`.
- **Radii:** inputs/chips `--r-sm` (12), cards `--r-md/--r-lg` (16–22), hero/sheets `--r-xl` (28), pills `--r-pill`.
- **Elevation:** flat surfaces use `--e1`; floating/interactive (FAB, sheets, active row) use `--e2/--e3`; the FAB adds `--glow-primary`. Avoid borders + heavy shadow together — pick one per surface.

### 1.5 Motion tokens

- Durations: micro `--dur-1` (press/hover), standard `--dur-2` (most transitions), entrance `--dur-3`, hero draw `--dur-4`.
- Easings: `--ease-standard` for moves, `--ease-out` for entrances, `--ease-spring` for press-release pops.
- **Always** wrap non-essential motion:
```css
@media (prefers-reduced-motion: reduce){
  *,*::before,*::after{ animation-duration:.001ms!important; animation-iteration-count:1!important;
    transition-duration:.001ms!important; scroll-behavior:auto!important; }
}
```

---

## 2. Layout & navigation model

**Replace** the desktop `sidebar + hamburger` with a **mobile app shell**: a slim **top app bar** (context/identity) + a **bottom navigation bar** (primary destinations) + a **center FAB** (Add). This matches Google Pay / Monarch / Ivy Wallet.

### 2.1 App shell (rewrite of `base.html` logged-in block)

```
┌─────────────────────────────┐  ← --safe-top
│  App bar  [avatar] Title  ⚙ │  56px, sticky
├─────────────────────────────┤
│                             │
│   scrollable content        │  padding-bottom: nav + FAB + safe
│   (max-width 480, centered) │
│                             │
├──────────────┬──────────────┤
│ [home][txns] (+) [cats][me] │  bottom nav 64px + FAB notch
└─────────────────────────────┘  ← --safe-bottom
```

```html
{% if user %}
<div class="shell">
  <header class="appbar">
    <a class="appbar__id" href="/settings" aria-label="Profile">
      <span class="avatar avatar--sm">{{ user['full_name'][:1]|upper }}</span>
    </a>
    <h1 class="appbar__title">{% block heading %}{% endblock %}</h1>
    <a class="appbar__action" href="/fraud" aria-label="Alerts">
      {{ icon('shield') }}
      {% if alerts_open %}<span class="dot dot--danger"></span>{% endif %}
    </a>
  </header>

  <main class="screen">{% block content %}{% endblock %}</main>

  {% include "_nav.html" %}
</div>
{% else %}{% block auth %}{% endblock %}{% endif %}
```

```css
.shell{ min-height:100dvh; background:var(--bg); }
.appbar{ position:sticky; top:0; z-index:40; height:calc(var(--appbar-h) + var(--safe-top));
  padding:var(--safe-top) var(--s-4) 0; display:flex; align-items:center; gap:var(--s-3);
  background:color-mix(in srgb, var(--bg) 80%, transparent); backdrop-filter:blur(14px);
  border-bottom:1px solid var(--border); }
.appbar__title{ font-size:var(--fs-h2); margin:0; flex:1; letter-spacing:-.01em; }
.screen{ max-width:var(--content-max); margin:0 auto; padding:var(--s-4) var(--s-4)
  calc(var(--bottomnav-h) + var(--safe-bottom) + var(--s-8)); }
```

### 2.2 Bottom navigation (`_nav.html`)

5 thumb-targets; the **center slot is the FAB**. Items map to existing routes; secondary routes (SMS Import, Fraud, Settings) move into the **profile/⚙ area and FAB sheet** (Monarch's "tuck secondary into profile" pattern).

Primary destinations: **Home** `/dashboard` · **Activity** `/transactions` · **(＋ FAB)** · **Categories** `/categories` · **You** `/settings`. *(Import is reachable from the FAB sheet; Fraud from the app-bar shield + dashboard alert card.)*

```html
<nav class="bottomnav" aria-label="Primary">
  {% set items = [
     ('dashboard','/dashboard','home','Home'),
     ('transactions','/transactions','list','Activity'),
     (None, None, None, None),                       {# FAB slot #}
     ('categories','/categories','grid','Categories'),
     ('settings','/settings','user','You')] %}
  {% for key, href, ic, label in items %}
    {% if key is none %}
      <div class="bottomnav__fabslot"></div>
    {% else %}
      <a class="navitem {{ 'is-active' if active==key }}" href="{{ href }}"
         {% if active==key %}aria-current="page"{% endif %}>
        {{ icon(ic) }}<span class="navitem__label">{{ label }}</span>
      </a>
    {% endif %}
  {% endfor %}
</nav>

<button class="fab" type="button" aria-label="Add transaction"
        onclick="document.getElementById('addSheet').showModal()">
  {{ icon('plus') }}
</button>
{% include "_add_sheet.html" %}
```

```css
.bottomnav{ position:fixed; left:0; right:0; bottom:0; z-index:45;
  height:calc(var(--bottomnav-h) + var(--safe-bottom)); padding-bottom:var(--safe-bottom);
  display:grid; grid-template-columns:repeat(5,1fr); align-items:center;
  background:color-mix(in srgb, var(--surface) 92%, transparent); backdrop-filter:blur(16px);
  border-top:1px solid var(--border); }
.navitem{ display:flex; flex-direction:column; align-items:center; gap:3px;
  color:var(--muted); font-size:var(--fs-xs); font-weight:var(--fw-semibold);
  min-height:48px; justify-content:center; transition:color var(--dur-1) var(--ease-standard); }
.navitem svg{ width:24px; height:24px; }
.navitem.is-active{ color:var(--primary); }
.navitem.is-active svg{ filter:drop-shadow(0 0 10px var(--primary-soft)); }
.navitem:active{ transform:scale(.92); }

.fab{ position:fixed; left:50%; transform:translateX(-50%);
  bottom:calc(var(--safe-bottom) + var(--bottomnav-h) - 28px); z-index:46;
  width:58px; height:58px; border:none; border-radius:50%;
  background:var(--grad-primary); color:#fff; box-shadow:var(--e3), var(--glow-primary);
  display:grid; place-items:center; transition:transform var(--dur-1) var(--ease-spring); }
.fab svg{ width:26px; height:26px; }
.fab:active{ transform:translateX(-50%) scale(.9); }
```

### 2.3 Hierarchy & reachability rules

- **Top app bar** = identity + screen title + one secondary action max. Never the primary CTA.
- **Bottom 1/3** = all primary actions (nav + FAB). Confirm/correct buttons in the queue sit on the right edge of rows but are ≥44px tall.
- **One hero per screen.** Dashboard hero = balance/spend. Activity hero = the confirmation queue when items exist, else the timeline.
- **Spacing rhythm:** screen gutter 16 → card padding 20 → between cards 16 → section gap 32. Never less than 12 between tappables.
- **Information density:** lead with the number, support with one caption. No more than 2 stat tiles per row on a phone.

---

## 3. Core components

Icons: a tiny **inline-SVG icon set** (no font, no CDN). Define a Jinja macro `icon(name)` that emits `<svg viewBox="0 0 24 24" stroke="currentColor" fill="none" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">…</svg>`. Stroke icons inherit `currentColor`, so they recolour for free in nav/states. Keep ~14 glyphs: home, list, grid, user, plus, shield, sms, search, check, edit, trash, chevron, sparkle, wallet.

```jinja
{# _icons.html #}
{% macro icon(name) -%}
<svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
     stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
{%- if name=='home' %}<path d="M3 11l9-8 9 8M5 10v10h14V10"/>
{%- elif name=='list' %}<path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"/>
{%- elif name=='grid' %}<rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><rect x="14" y="14" width="7" height="7" rx="2"/>
{%- elif name=='plus' %}<path d="M12 5v14M5 12h14"/>
{%- elif name=='check' %}<path d="M20 6L9 17l-5-5"/>
{%- elif name=='sparkle' %}<path d="M12 3l1.8 4.7L18.5 9 13.8 10.8 12 15.5 10.2 10.8 5.5 9l4.7-1.3z"/>
{# …remaining glyphs… #}
{%- endif %}
</svg>
{%- endmacro %}
```

### 3.1 Cards & list rows (foundation)

```css
.card{ background:var(--surface); border:1px solid var(--border); border-radius:var(--r-lg);
  padding:var(--s-5); box-shadow:var(--e1); }
.card--flush{ padding:var(--s-2); }           /* for full-bleed lists */
.card--hero{ background:var(--grad-hero); border:1px solid var(--border-2);
  border-radius:var(--r-xl); padding:var(--s-6); box-shadow:var(--e2); }

.row{ display:flex; align-items:center; gap:var(--s-3); padding:var(--s-3) var(--s-4);
  border-radius:var(--r-md); min-height:56px; }
.row:active{ background:var(--surface-2); }
.row__body{ flex:1; min-width:0; }
.row__title{ font-size:var(--fs-h3); font-weight:var(--fw-semibold); white-space:nowrap;
  overflow:hidden; text-overflow:ellipsis; }
.row__sub{ font-size:var(--fs-sm); color:var(--muted); }
```

### 3.2 Avatar (merchant initial / colour)

Deterministic colour from the merchant name — pure CSS/Jinja, no images.

```jinja
{% macro avatar(name, size='md') %}
  {% set palette = ['#7C5CFF','#34E1C4','#FF8A5C','#4DA3FF','#FFB02E','#2FD27A','#FF5C8A','#9B6CFF'] %}
  {% set idx = (name|default('?')|length) % palette|length %}
  <span class="avatar avatar--{{ size }}" style="--av:{{ palette[idx] }}">
    {{ (name or '?')[:1]|upper }}
  </span>
{% endmacro %}
```
```css
.avatar{ display:grid; place-items:center; flex:none; border-radius:50%;
  font-weight:var(--fw-bold); color:#fff;
  background:radial-gradient(120% 120% at 30% 20%, color-mix(in srgb,var(--av) 75%,#fff 0%), var(--av)); }
.avatar--sm{ width:32px; height:32px; font-size:13px; }
.avatar--md{ width:40px; height:40px; font-size:16px; }
.avatar--lg{ width:48px; height:48px; font-size:18px; }
```

### 3.3 Pills & confidence badges (engine signature)

Extends existing `.badge-high/mid/low` and `.pill-*`. Add a **confidence chip with a mini meter**.

```css
.pill{ display:inline-flex; align-items:center; gap:4px; font-size:var(--fs-xs);
  font-weight:var(--fw-bold); padding:3px 9px; border-radius:var(--r-pill);
  background:var(--surface-2); color:var(--text-2); letter-spacing:.02em; }
.pill--income{ background:var(--income-soft); color:var(--income); }
.pill--expense{ background:var(--expense-soft); color:var(--expense); }
.pill--warn{ background:var(--warn-soft); color:var(--warn); }
.pill--review{ background:var(--review-soft); color:var(--review); }
.pill--accent{ background:var(--accent-soft); color:var(--accent); }

/* Confidence chip: dot colour + value, optionally a 3px meter */
.conf{ display:inline-flex; align-items:center; gap:6px; font-size:var(--fs-xs);
  font-weight:var(--fw-bold); padding:3px 8px 3px 7px; border-radius:var(--r-pill);
  background:var(--surface-2); }
.conf::before{ content:""; width:7px; height:7px; border-radius:50%; background:var(--c); }
.conf--high{ --c:var(--conf-high); color:var(--conf-high); }
.conf--mid{  --c:var(--conf-mid);  color:var(--conf-mid); }
.conf--low{  --c:var(--conf-low);  color:var(--conf-low); }
```
```jinja
{% macro confidence(score) %}
  {% if score is none %}<span class="conf">New</span>
  {% else %}
    {% set cls = 'high' if score>=80 else ('mid' if score>=50 else 'low') %}
    <span class="conf conf--{{ cls }}">{{ score }}%</span>
  {% endif %}
{% endmacro %}
```

### 3.4 Signed amount

```jinja
{% macro amount(value, type) %}
  <span class="money money--{{ type }}">
    {{ '+' if type=='income' else '−' }}{{ '%.2f'|format(value|float) }}
  </span>
{% endmacro %}
```
```css
.money{ font-variant-numeric:tabular-nums; font-weight:var(--fw-bold); letter-spacing:-.01em; }
.money--income{ color:var(--income); }
.money--expense{ color:var(--expense); }
```

### 3.5 Dashboard (`dashboard.html` rewrite) — compact, hero-led

Replaces the 8-tile wall + 4 tables. Structure: **Hero balance/spend** → **spend ring + this-month** → **insight chips** → **review queue teaser** (if any) → **top merchants (compact rows)** → **category mini-breakdown** → **(fraud alert card if open)**. All data fields already exist in `d` (`balance, monthly_spend, weekly_spend, daily_spend, total_income, total_expense, category_breakdown, top_merchants, insights, pending_confirmations, open_fraud_alerts, trend, currency`).

```html
<!-- HERO -->
<section class="card--hero hero">
  <p class="eyebrow">{{ d.currency }} · This month</p>
  <p class="hero__amount" data-count="{{ d.monthly_spend }}">
    {{ d.currency }} {{ '%.2f'|format(d.monthly_spend) }}</p>
  <div class="hero__meta">
    <span class="money money--income">▲ {{ '%.0f'|format(d.total_income) }} in</span>
    <span class="money money--expense">▼ {{ '%.0f'|format(d.total_expense) }} out</span>
    <span class="pill pill--accent">Balance {{ '%.0f'|format(d.balance) }}</span>
  </div>
</section>

<!-- SPEND RING + PERIODS -->
<section class="grid-2" style="margin-top:var(--s-4)">
  <div class="card ring-card">{{ donut(d.category_breakdown, d.monthly_spend, d.currency) }}</div>
  <div class="card period-stack">
    <div class="period"><span class="eyebrow">Today</span>
      <span class="period__val">{{ '%.0f'|format(d.daily_spend) }}</span></div>
    <div class="period"><span class="eyebrow">This week</span>
      <span class="period__val">{{ '%.0f'|format(d.weekly_spend) }}</span></div>
  </div>
</section>

<!-- INSIGHT CHIPS (horizontal scroll) -->
<div class="chips" role="list">
  {% for line in d.insights %}<span class="chip" role="listitem">{{ icon('sparkle') }}{{ line }}</span>{% endfor %}
</div>

<!-- REVIEW QUEUE TEASER -->
{% if d.pending_confirmations %}
<a class="card queue-teaser" href="/transactions#review">
  <span class="avatar avatar--md" style="--av:var(--warn)">{{ d.pending_confirmations }}</span>
  <span class="row__body"><span class="row__title">Merchants to confirm</span>
    <span class="row__sub">Confirm to teach the engine — improves auto-matching</span></span>
  {{ icon('chevron') }}
</a>{% endif %}

<!-- TOP MERCHANTS as rows, not a table -->
<h2 class="section-title">Top merchants</h2>
<div class="card card--flush">
  {% for mch in d.top_merchants %}
  <div class="row">{{ avatar(mch.name) }}
    <span class="row__body"><span class="row__title">{{ mch.name }}</span></span>
    <span class="money money--expense">{{ d.currency }} {{ '%.0f'|format(mch.value) }}</span>
  </div>{% else %}{{ empty_state('wallet','No spending yet','Add your first transaction with the ＋ button.') }}{% endfor %}
</div>
```
```css
.eyebrow{ font-size:var(--fs-xs); font-weight:var(--fw-bold); letter-spacing:.06em;
  text-transform:uppercase; color:var(--muted); margin:0 0 var(--s-2); }
.hero__amount{ font-size:var(--fs-display); font-weight:var(--fw-black); letter-spacing:-.02em;
  font-variant-numeric:tabular-nums; margin:0; }
.hero__meta{ display:flex; gap:var(--s-3); flex-wrap:wrap; margin-top:var(--s-3);
  font-size:var(--fs-sm); }
.grid-2{ display:grid; grid-template-columns:1fr 1fr; gap:var(--s-4); }
.period-stack{ display:flex; flex-direction:column; justify-content:center; gap:var(--s-4); }
.period__val{ font-size:var(--fs-h1); font-weight:var(--fw-bold); font-variant-numeric:tabular-nums; }
.chips{ display:flex; gap:var(--s-2); overflow-x:auto; padding:var(--s-1) 0; margin:var(--s-4) 0;
  scrollbar-width:none; }
.chip{ flex:none; display:inline-flex; align-items:center; gap:6px; padding:8px 12px;
  background:var(--surface-2); border-radius:var(--r-pill); font-size:var(--fs-sm);
  font-weight:var(--fw-medium); white-space:nowrap; }
.chip svg{ width:15px; height:15px; color:var(--accent); }
.queue-teaser{ display:flex; align-items:center; gap:var(--s-3); margin-top:var(--s-4);
  border:1px solid var(--warn-soft); }
```

### 3.6 Transaction timeline (`transactions.html` rewrite) — date-grouped rows

Replace the table with a **Splitwise/GPay-style grouped timeline**. The "Add transaction" form moves into the **FAB add sheet** (§3.8). Search stays as a slim pill field at the top. Confirmation rows route into the **review queue** at the top.

```html
{# group server-side or in-template by t['occurred_at'][:10] #}
<form class="searchbar" method="get" action="/transactions">
  {{ icon('search') }}
  <input name="q" value="{{ q }}" placeholder="Search merchant, note, ref…">
</form>

{# ── REVIEW QUEUE (hero when present) ── #}
{% set queue = transactions|selectattr('status','in',['pending_confirmation','needs_review'])|list %}
{% if queue %}
<section id="review" class="card queue">
  <header class="queue__head">
    <span class="row__title">Needs your confirmation</span>
    <span class="pill pill--warn">{{ queue|length }}</span>
  </header>
  {% for t in queue %}{% include "_queue_item.html" %}{% endfor %}
</section>{% endif %}

{# ── TIMELINE ── #}
{% set ns = namespace(day='') %}
<div class="timeline">
{% for t in transactions if t['status'] not in ['pending_confirmation','needs_review'] %}
  {% set day = t['occurred_at'][:10] %}
  {% if day != ns.day %}{% set ns.day = day %}
    <h3 class="timeline__date">{{ day }}</h3>{% endif %}
  <div class="row tx">
    {{ avatar(t['merchant_name'] or t['raw_merchant']) }}
    <span class="row__body">
      <span class="row__title">{{ t['merchant_name'] or t['raw_merchant'] or '—' }}</span>
      <span class="row__sub">
        {{ t['notes'] or '' }}
        {% if t['raw_merchant'] and t['merchant_name'] and t['raw_merchant']!=t['merchant_name'] %}
          · from “{{ t['raw_merchant'] }}”{% endif %}
      </span>
    </span>
    <span class="tx__right">
      {{ amount(t['amount'], t['type']) }}
      {{ confidence(t['confidence']) }}
    </span>
  </div>
{% else %}
  {{ empty_state('list','No transactions yet','Tap ＋ to add one, or import a bank SMS.') }}
{% endfor %}
</div>
```
```css
.searchbar{ display:flex; align-items:center; gap:var(--s-2); background:var(--surface-2);
  border-radius:var(--r-pill); padding:0 var(--s-4); height:44px; margin-bottom:var(--s-4); }
.searchbar svg{ width:18px; height:18px; color:var(--muted); }
.searchbar input{ border:none; background:transparent; padding:0; height:100%; }
.searchbar input:focus{ outline:none; box-shadow:none; }
.timeline__date{ font-size:var(--fs-sm); font-weight:var(--fw-bold); color:var(--muted);
  text-transform:uppercase; letter-spacing:.04em; margin:var(--s-5) var(--s-2) var(--s-2); }
.tx__right{ display:flex; flex-direction:column; align-items:flex-end; gap:4px; }
```

### 3.7 Merchant confirmation queue item (`_queue_item.html`) — hero surface

This is the **signature engine UX**. Each item shows the raw SMS name, the engine's suggestion + confidence + signal breakdown, and one-tap **Confirm** / **Correct**. Posts to existing `/transactions/<id>/confirm`.

```html
<div class="qitem">
  <div class="qitem__top">
    {{ avatar(t['merchant_name'] or t['raw_merchant']) }}
    <div class="row__body">
      <div class="row__title">{{ t['merchant_name'] or t['raw_merchant'] }}</div>
      <div class="row__sub">from “{{ t['raw_merchant'] }}” · {{ t['occurred_at'][:10] }}</div>
    </div>
    <div class="tx__right">{{ amount(t['amount'], t['type']) }}{{ confidence(t['confidence']) }}</div>
  </div>

  <form method="post" action="/transactions/{{ t['id'] }}/confirm" class="qitem__actions">
    {# default = accept engine suggestion; user can correct in the field #}
    <input class="qitem__edit" name="merchant" value="{{ t['merchant_name'] or '' }}"
           placeholder="Correct merchant…" required>
    <button class="btn btn--sm" type="submit">{{ icon('check') }} Confirm</button>
  </form>
  <p class="qitem__hint">Confirming teaches the engine — next time it auto-matches.</p>
</div>
```
```css
.queue{ border:1px solid var(--warn-soft); background:
  linear-gradient(180deg,var(--warn-soft),transparent 64px), var(--surface); margin-bottom:var(--s-5); }
.queue__head{ display:flex; align-items:center; justify-content:space-between; margin-bottom:var(--s-3); }
.qitem{ padding:var(--s-3) 0; border-top:1px solid var(--border); }
.qitem:first-of-type{ border-top:none; }
.qitem__top{ display:flex; align-items:center; gap:var(--s-3); }
.qitem__actions{ display:flex; gap:var(--s-2); margin-top:var(--s-3); }
.qitem__edit{ flex:1; height:40px; }
.qitem__hint{ font-size:var(--fs-xs); color:var(--muted); margin:6px 0 0; }
```
> Optional richer version: render the **signal breakdown** (`breakdown_bars`) inside an expandable `<details>` so power users see *why* the engine is confident (past mapping / amount / category / corrections / time). Keep collapsed by default for calm.

### 3.8 FAB add sheet (`_add_sheet.html`) — native `<dialog>`

Bottom action sheet using the platform `<dialog>` element (offline, no JS lib; opened via `showModal()`). Two paths: **Add manually** (the old transactions form) and **Import SMS** (→ `/import`). The manual form keeps the live confidence preview (existing `/transactions/resolve` fetch + `_resolve.html`).

```html
<dialog id="addSheet" class="sheet">
  <form method="dialog" class="sheet__grabber" aria-label="Close"><button></button></form>
  <h2 class="sheet__title">Add transaction</h2>

  <form method="post" action="/transactions" class="addform">
    <div class="field-row">
      <label class="field"><span>Amount</span>
        <input id="amt" name="amount" type="number" step="0.01" min="0" inputmode="decimal" required></label>
      <label class="field field--type"><span>Type</span>
        <select name="type"><option value="expense">Expense</option><option value="income">Income</option></select></label>
    </div>
    <label class="field"><span>Merchant / payee</span>
      <input id="merchant" name="merchant" autocomplete="off"></label>
    <div id="resolve-preview"></div>  {# live engine confidence, same as today #}
    <div class="field-row">
      <label class="field"><span>Category</span>
        <select name="category_id"><option value="">— none —</option>
          {% for c in categories %}<option value="{{ c['id'] }}">{{ c['name'] }}</option>{% endfor %}
        </select></label>
      <label class="field"><span>Date</span><input name="occurred_at" type="date"></label>
    </div>
    <button class="btn btn--block" type="submit">Add transaction</button>
  </form>
  <a class="btn btn--ghost btn--block" href="/import">{{ icon('sms') }} Import from SMS instead</a>
</dialog>
```
```css
.sheet{ width:100%; max-width:var(--content-max); margin:auto auto 0; padding:var(--s-5)
  var(--s-5) calc(var(--s-5) + var(--safe-bottom)); border:none; border-radius:var(--r-xl) var(--r-xl) 0 0;
  background:var(--bg-elevated); color:var(--text); box-shadow:var(--e3); }
.sheet::backdrop{ background:rgba(0,0,0,.55); backdrop-filter:blur(2px); }
.sheet[open]{ animation:sheet-up var(--dur-3) var(--ease-out); }
.sheet__grabber button{ display:block; width:40px; height:4px; margin:0 auto var(--s-4);
  border:none; border-radius:var(--r-pill); background:var(--border-2); }
@keyframes sheet-up{ from{ transform:translateY(100%); } to{ transform:translateY(0);} }
```

### 3.9 Categories grid (`categories.html` rewrite) — visual cards

Replace the table with a 2-up colour-tile grid (Ivy Wallet style). Add form goes into a small inline sheet/`<details>` or a secondary FAB-less "+ New" tile.

```html
<div class="cat-grid">
  {% for c in categories %}
  <div class="cat-tile" style="--av:{{ c['color'] }}">
    <span class="cat-tile__dot"></span>
    <span class="cat-tile__name">{{ c['name'] }}</span>
    <span class="pill {{ 'pill--income' if c['type']=='income' else 'pill--review' }}">{{ c['type']|capitalize }}</span>
    <form method="post" action="/categories/{{ c['id'] }}/delete" class="cat-tile__del"
          onsubmit="return confirm('Delete this category?')">
      <button aria-label="Delete">{{ icon('trash') }}</button></form>
  </div>{% endfor %}
  <button class="cat-tile cat-tile--add" onclick="document.getElementById('catSheet').showModal()">
    {{ icon('plus') }} New category</button>
</div>
```
```css
.cat-grid{ display:grid; grid-template-columns:1fr 1fr; gap:var(--s-3); }
.cat-tile{ position:relative; background:var(--surface); border:1px solid var(--border);
  border-radius:var(--r-lg); padding:var(--s-4); min-height:96px; display:flex;
  flex-direction:column; gap:var(--s-2); box-shadow:var(--e1);
  border-left:3px solid var(--av); }
.cat-tile__dot{ width:28px; height:28px; border-radius:9px;
  background:color-mix(in srgb, var(--av) 22%, transparent); }
.cat-tile__name{ font-weight:var(--fw-semibold); font-size:var(--fs-h3); }
.cat-tile--add{ align-items:center; justify-content:center; color:var(--muted);
  border-style:dashed; background:transparent; }
```

### 3.10 Charts — inline SVG (donut, bars, sparkline)

All charts are inline SVG generated in Jinja from existing data. No library. Stroke-dasharray for donut, `<rect>` for bars, `<polyline>` for sparkline.

**Donut / spend ring** — segments from `d.category_breakdown`. Uses a single circle per segment with `stroke-dasharray` offsets; centre shows total. Animate the draw with the `--ease-out` reveal (§4).

```jinja
{% macro donut(cats, total, currency) %}
{% set R = 52 %}{% set C = 2 * 3.14159 * R %}
{% set palette = ['#7C5CFF','#34E1C4','#FF8A5C','#4DA3FF','#FFB02E','#2FD27A'] %}
<svg class="donut" viewBox="0 0 120 120" width="120" height="120" role="img"
     aria-label="Spend by category">
  <circle cx="60" cy="60" r="{{ R }}" fill="none" stroke="var(--surface-2)" stroke-width="12"/>
  {% set ns = namespace(offset=0) %}
  {% for c in cats[:6] %}
    {% set frac = (c.value / total) if total else 0 %}
    {% set len = frac * C %}
    <circle class="donut__seg" cx="60" cy="60" r="{{ R }}" fill="none"
      stroke="{{ palette[loop.index0 % 6] }}" stroke-width="12" stroke-linecap="round"
      stroke-dasharray="{{ '%.2f'|format(len) }} {{ '%.2f'|format(C - len) }}"
      stroke-dashoffset="{{ '%.2f'|format(-ns.offset) }}"
      transform="rotate(-90 60 60)" style="--i:{{ loop.index0 }}"/>
    {% set ns.offset = ns.offset + len %}
  {% endfor %}
  <text x="60" y="56" text-anchor="middle" class="donut__total">{{ currency }}</text>
  <text x="60" y="74" text-anchor="middle" class="donut__amt">{{ '%.0f'|format(total) }}</text>
</svg>
{% endmacro %}
```
```css
.donut{ display:block; margin:0 auto; }
.donut__seg{ transition:stroke-dashoffset var(--dur-4) var(--ease-out); }
.donut__total{ fill:var(--muted); font-size:9px; font-weight:700; }
.donut__amt{ fill:var(--text); font-size:18px; font-weight:800; font-variant-numeric:tabular-nums; }
.donut[data-anim] .donut__seg{ animation:donut-draw var(--dur-4) var(--ease-out) backwards;
  animation-delay:calc(var(--i) * 90ms); }
@keyframes donut-draw{ from{ stroke-dasharray:0 999; } }
```

**Trend bars** — from `d.trend` (`period, income, expense`):
```jinja
{% macro trendbars(trend) %}
{% set peak = (trend|map(attribute='expense')|max) or 1 %}
<div class="bars">
{% for t in trend %}
  <div class="bars__col" title="{{ t.period }}">
    <span class="bars__bar" style="height:{{ (t.expense/peak*100)|round }}%"></span>
    <span class="bars__lbl">{{ t.period[-2:] }}</span>
  </div>{% endfor %}
</div>{% endmacro %}
```
```css
.bars{ display:flex; align-items:flex-end; gap:var(--s-2); height:96px; }
.bars__col{ flex:1; display:flex; flex-direction:column; align-items:center; gap:4px; height:100%; justify-content:flex-end; }
.bars__bar{ width:60%; min-height:4px; border-radius:6px 6px 2px 2px; background:var(--grad-primary);
  transition:height var(--dur-4) var(--ease-out); }
.bars__lbl{ font-size:var(--fs-xs); color:var(--muted); }
```

**Sparkline** (compact daily trend, optional):
```html
<svg class="spark" viewBox="0 0 100 30" preserveAspectRatio="none">
  <polyline fill="none" stroke="var(--accent)" stroke-width="2"
    points="0,24 14,18 28,20 42,8 56,14 70,6 84,11 100,4"/>
</svg>
```

**Signal breakdown bars** — keep the existing `breakdown_bars` macro / `.breakdown .bd-*` classes; just restyle the fill to `--grad-primary` and the track to `--surface-2`. This is the engine's "why" visual and already maps to the resolve/import data.

### 3.11 Inputs, buttons, forms

```css
.field{ display:flex; flex-direction:column; gap:6px; }
.field > span{ font-size:var(--fs-sm); font-weight:var(--fw-semibold); color:var(--text-2); }
.field-row{ display:flex; gap:var(--s-3); }
.field-row > .field{ flex:1; }
input,select,textarea{ width:100%; height:48px; padding:0 var(--s-4); font-size:var(--fs-body);
  font-family:inherit; color:var(--text); background:var(--surface-2);
  border:1px solid var(--border-2); border-radius:var(--r-sm); }
textarea{ height:auto; padding:var(--s-3) var(--s-4); }
input:focus,select:focus,textarea:focus{ outline:none; border-color:var(--primary);
  box-shadow:0 0 0 3px var(--primary-soft); }

.btn{ display:inline-flex; align-items:center; justify-content:center; gap:6px; height:48px;
  padding:0 var(--s-5); border:none; border-radius:var(--r-sm); cursor:pointer;
  font-size:var(--fs-body); font-weight:var(--fw-bold); color:#fff; background:var(--grad-primary);
  box-shadow:var(--e1); transition:transform var(--dur-1) var(--ease-spring), filter var(--dur-1); }
.btn:active{ transform:scale(.97); filter:brightness(.95); }
.btn--block{ width:100%; }
.btn--sm{ height:40px; padding:0 var(--s-4); font-size:var(--fs-sm); }
.btn--ghost{ background:transparent; color:var(--text); border:1px solid var(--border-2); box-shadow:none; }
.btn--danger{ background:var(--expense); }
.btn svg{ width:18px; height:18px; }
```

### 3.12 Empty states — premium

```jinja
{% macro empty_state(ic, title, body) %}
<div class="empty">
  <span class="empty__icon">{{ icon(ic) }}</span>
  <p class="empty__title">{{ title }}</p>
  <p class="empty__body">{{ body }}</p>
</div>{% endmacro %}
```
```css
.empty{ text-align:center; padding:var(--s-8) var(--s-4); }
.empty__icon{ display:grid; place-items:center; width:64px; height:64px; margin:0 auto var(--s-3);
  border-radius:50%; background:var(--surface-2); color:var(--muted); }
.empty__icon svg{ width:28px; height:28px; }
.empty__title{ font-size:var(--fs-h3); font-weight:var(--fw-semibold); margin:0 0 4px; }
.empty__body{ font-size:var(--fs-sm); color:var(--muted); margin:0; max-width:34ch; margin-inline:auto; }
```

---

## 4. Motion — pure CSS / minimal JS

| Interaction | Spec |
|---|---|
| **Screen entrance** | Stagger immediate children of `.screen` upward + fade: `.screen > * { animation: rise var(--dur-3) var(--ease-out) backwards; }` and set `animation-delay` via `:nth-child()` (e.g. 0/40/80/120ms for the first 4). |
| **Press** | All tappables (`.btn .fab .navitem .row .cat-tile`) scale 0.92–0.97 on `:active` with `--ease-spring`. No JS. |
| **Nav active** | Colour transition + a soft `drop-shadow` glow on the active icon. Optional: a 3px pill indicator above the active item that slides — pure CSS via a transformed `::after` on `.is-active`. |
| **FAB** | Entrance pop (`scale(0)→1` spring) on load; depress on `:active`. |
| **Sheet** | `<dialog>` slides up via `@keyframes sheet-up` on `[open]`; backdrop fades. Closing: rely on native; optional `.closing` class + `transitionend` for a slide-out (minimal JS). |
| **Donut draw** | Segments animate `stroke-dashoffset`/`dasharray` from empty, staggered by `--i`. Add `data-anim` after first paint (or use the keyframe with `backwards`). |
| **Count-up balance** | Tiny vanilla JS: read `data-count`, `requestAnimationFrame` lerp the number over `--dur-4`. ~12 lines, no lib. Skip when `prefers-reduced-motion`. |
| **Bars** | `height` transition on `--ease-out`, staggered. |

```css
@keyframes rise{ from{ opacity:0; transform:translateY(10px);} to{ opacity:1; transform:none;} }
.screen > *{ animation:rise var(--dur-3) var(--ease-out) backwards; }
.screen > *:nth-child(1){ animation-delay:0ms; }
.screen > *:nth-child(2){ animation-delay:40ms; }
.screen > *:nth-child(3){ animation-delay:80ms; }
.screen > *:nth-child(4){ animation-delay:120ms; }
```
Count-up (optional, gated):
```js
if(!matchMedia('(prefers-reduced-motion: reduce)').matches){
  document.querySelectorAll('[data-count]').forEach(function(el){
    var end=parseFloat(el.dataset.count)||0, t0=null, dur=480, pre=el.textContent.split(/[\d]/)[0];
    function step(t){ t0=t0||t; var k=Math.min(1,(t-t0)/dur);
      el.textContent=pre+(end*(1-Math.pow(1-k,3))).toFixed(2); if(k<1)requestAnimationFrame(step); }
    requestAnimationFrame(step);
  });
}
```

---

## 5. Fintech UX improvements specific to SpendWise

1. **Merchant-learning is the hero story.** The confidence engine is surfaced everywhere it adds trust:
   - Dashboard **queue teaser** card ("Merchants to confirm → teaches the engine").
   - Activity **review queue** as a pinned hero surface above the timeline.
   - Every row carries a **confidence chip**; corrected rows show "from <raw>".
   - Add/Import flows keep the **live resolve preview** with the animated **signal breakdown** ("why we think this is X") — restyled, not removed.
   - Microcopy reinforces the loop: *"Confirming teaches the engine — next time it auto-matches."*
2. **Stronger insights.** Turn `d.insights` strings into horizontally-scrollable **insight chips** with a sparkle icon; promote the most actionable (overspend vs last week, top category share) to the front. Add a spend-ring so "where the money goes" is glanceable.
3. **Premium empty states.** Replace bare "No transactions yet" text with iconed, two-line empty states that point at the ＋ FAB or SMS import — guiding, not dead-ending.
4. **Fraud as ambient, not a tab.** Open-alert count becomes a **dot on the app-bar shield** + a dashboard alert card; the full list (existing `/fraud`) is reached from there. Severities reuse `--expense/--warn/--muted`.
5. **Thumb-first money entry.** The primary "add" action is the center FAB → bottom sheet, reachable one-handed; SMS import is one tap deeper in the same sheet.
6. **Calm by default, depth on demand.** Signal breakdowns live in `<details>`; the surface stays quiet until the user wants the "why".

---

## 6. Implementation checklist (for the engineer)

- [ ] **Tokens:** flip `:root` to dark, add `[data-theme="light"]` + system bridge, add new vars (keep all existing names as aliases). Add `prefers-reduced-motion` guard and `@font-face` only if a bundled font is approved.
- [ ] **Shell:** rewrite `base.html` logged-in block → `.appbar` + `.screen` + `_nav.html`; delete sidebar/hamburger CSS (or leave dead — but remove `.app-shell/.sidebar` usage).
- [ ] **Macros:** add `_icons.html` (`icon`), and `avatar/confidence/amount/empty_state/donut/trendbars` (extend `_macros.html`; keep `money/confidence_badge/status_pill/decision_label/breakdown_bars`).
- [ ] **Partials:** `_nav.html`, `_add_sheet.html`, `_queue_item.html`, `_cat_sheet.html`.
- [ ] **Screens:** rewrite `dashboard.html` (hero+ring+chips+queue teaser+rows), `transactions.html` (search+queue+timeline; move add-form into sheet), `categories.html` (grid). Re-skin `import.html`, `_resolve.html`, `_import_preview.html`, `settings.html`, `login/signup` to new tokens/components.
- [ ] **Routes unchanged.** All forms/fetches keep posting to existing endpoints (`/transactions`, `/transactions/<id>/confirm`, `/transactions/<id>/delete`, `/transactions/resolve`, `/import/parse`, `/import/create`, `/categories`, `/fraud/<id>/status`, `/settings`). Pass `categories` to whatever template renders the add sheet; optionally pass `alerts_open` to base for the app-bar dot.
- [ ] **Offline check:** no external URLs anywhere; SVG inline; `color-mix`/`backdrop-filter` degrade gracefully (provide solid-colour fallbacks for the two nav/app-bar backgrounds).

---

## 7. Source inspirations (mapping)

- **CRED** — near-black layered surfaces, single confident accent, generous numerals, restraint → palette, hero, elevation.
- **Fold Money** — insight cards / spend story → insight chips, hero meta.
- **Ivy Wallet** — compact balance hero + colourful category grid → dashboard hero, categories grid.
- **Google Pay** — avatar-led rows, center FAB, thumb reach → transaction rows, FAB, bottom nav.
- **Splitwise** — date-grouped activity, signed amounts → timeline + `amount` macro.
- **Monarch Money** — 5-item bottom nav + profile drawer, transaction review queue → nav model, confirmation queue.
- **YNAB** — confidence/assignment language, semantic money colour → confidence chips, income/expense tokens, microcopy.

Research note: dark-first fintech palettes (deep canvas + layered greys + one saturated accent + colourful data-viz) are the prevailing premium pattern (Merixstudio fintech design guidance; fintech dark-UI palettes on ColorsWall/windmill.digital). Monarch's published mobile-nav model (5 bottom items + profile drawer + rule-based review) directly informs §2.2 and §3.7.
