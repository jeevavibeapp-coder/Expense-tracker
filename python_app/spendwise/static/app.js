/* SpendWise — micro-interactions + instant client-side navigation.
   Vanilla, offline, no libraries. Degrades gracefully: if this file is absent
   or throws, every <a> is a normal full-page navigation and all server-
   rendered content is visible without JS. */
(function () {
  "use strict";
  var reduce = window.matchMedia && matchMedia("(prefers-reduced-motion: reduce)").matches;
  /* Haptic tick. Gated on transient user activation because Chromium (and the
     Android WebView) refuse vibrate() outside a gesture and log a console
     error when refused — confetti fires on a freshly loaded /transactions
     ?added=1 document, where no tap has happened yet, so the call was always
     going to be blocked there. Skipping it silently is the same outcome
     without the noise. */
  var buzz = function (ms) {
    if (!navigator.vibrate) return;
    if (navigator.userActivation && !navigator.userActivation.isActive) return;
    try { navigator.vibrate(ms); } catch (e) {}
  };

  /* The balance is server-rendered with the correct value already, so there is
     no JS count-up: content is correct and instant, with no per-frame layout
     work competing with the page paint right after a navigation. */

  /* ── Ripple on press (event-delegated — survives swaps) ───────────── */
  var RIPPLE_SEL = ".tap,.btn,.btn-soft,.fab,.chip,.sheet-action,.icon-btn,.cat-chip";
  function ripple(el, x, y) {
    var r = el.getBoundingClientRect(), s = document.createElement("span");
    s.className = "ripple";
    var z = Math.max(r.width, r.height);
    s.style.width = s.style.height = z + "px";
    s.style.left = ((x || r.left + r.width / 2) - r.left - z / 2) + "px";
    s.style.top = ((y || r.top + r.height / 2) - r.top - z / 2) + "px";
    el.appendChild(s);
    buzz(7);
    setTimeout(function () { s.remove(); }, 600);
  }

  /* ── Confetti burst ───────────────────────────────────────────────── */
  function confetti() {
    if (reduce) return;
    var cv = document.createElement("canvas");
    cv.style.cssText = "position:fixed;inset:0;pointer-events:none;z-index:9999";
    document.body.appendChild(cv);
    var ctx = cv.getContext("2d"), W = cv.width = innerWidth, H = cv.height = innerHeight;
    var cols = ["#7c5cff", "#5b8cff", "#36d39a", "#ff6b81", "#fbbf24", "#a78bfa"];
    var P = [];
    for (var i = 0; i < 60; i++) P.push({
      x: W / 2 + (Math.random() - 0.5) * 120, y: H * 0.34,
      vx: (Math.random() - 0.5) * 9, vy: Math.random() * -11 - 4,
      r: Math.random() * 6 + 3, c: cols[(Math.random() * cols.length) | 0],
      a: 1, rot: Math.random() * 6
    });
    buzz(20);
    var t0 = performance.now();
    (function frame(t) {
      ctx.clearRect(0, 0, W, H);
      var done = true;
      P.forEach(function (p) {
        p.vy += 0.28; p.x += p.vx; p.y += p.vy; p.vx *= 0.99; p.rot += 0.2;
        p.a = Math.max(0, 1 - (t - t0) / 1600);
        if (p.a > 0.02) done = false;
        ctx.globalAlpha = p.a; ctx.fillStyle = p.c;
        ctx.save(); ctx.translate(p.x, p.y); ctx.rotate(p.rot);
        ctx.fillRect(-p.r / 2, -p.r / 2, p.r, p.r * 1.6); ctx.restore();
      });
      if (!done) requestAnimationFrame(frame); else cv.remove();
    })(t0);
  }

  /* ── Per-page enhancement — runs on first load AND after every swap ── */
  function enhance() {
    // Confetti only on genuine add/confirm celebrations — NOT on every .flash
    // (settings save, edit, restore, category add) where it janked the page.
    try {
      if (/[?&](added|confirmed)=1/.test(location.search)) setTimeout(confetti, 180);
    } catch (e) {}
  }

  /* ── Delegated listeners — attached ONCE, survive every swap ──────── */
  function attachDelegates() {
    document.addEventListener("pointerdown", function (e) {
      var el = e.target.closest && e.target.closest(RIPPLE_SEL);
      if (el) ripple(el, e.clientX, e.clientY);
    }, { passive: true });

    // Merchant resolve-preview for the app-wide #add sheet (delegated so it
    // survives instant-nav swaps without re-attaching).
    var timer = null, ctrl = null;
    document.addEventListener("input", function (e) {
      var t = e.target;
      if (!t || t.id !== "merchant") return;
      var out = document.getElementById("resolve-preview"), amt = document.getElementById("amt");
      if (!out) return;
      clearTimeout(timer);
      timer = setTimeout(function () {
        var name = t.value.trim();
        if (!name) { out.innerHTML = ""; return; }
        if (ctrl) ctrl.abort();
        ctrl = new AbortController();
        var sk = document.createElement("div");
        sk.className = "skeleton line";
        sk.style.cssText = "margin-top:10px;width:70%";
        out.innerHTML = "";
        out.appendChild(sk);
        fetch("/transactions/resolve", {
          method: "POST", signal: ctrl.signal,
          headers: { "Content-Type": "application/x-www-form-urlencoded" },
          body: new URLSearchParams({ merchant: name, amount: (amt && amt.value) || "" })
        }).then(function (r) { return r.text(); })
          .then(function (h) { out.innerHTML = h; })
          .catch(function () {});
      }, 400);
    });
  }

  /* ── Instant client-side navigation (hand-rolled, Turbo-style) ────── */
  function initNav() {
    if (!(window.history && history.pushState && window.DOMParser && window.fetch)) return;
    if ("scrollRestoration" in history) history.scrollRestoration = "manual";

    var origin = location.origin;
    var currentLoc = location.pathname + location.search;
    var seq = 0;
    var cache = {}; // url -> { t, p:Promise<{url,html}> }
    try { history.replaceState({ spa: 1, scroll: 0 }, ""); } catch (e) {}

    /* progress bar */
    var bar = null, trickle = null, fade = null;
    function barEl() {
      if (!bar) { bar = document.createElement("div"); bar.id = "nprogress"; document.body.appendChild(bar); }
      return bar;
    }
    function barStart() {
      var b = barEl();
      clearInterval(trickle); clearTimeout(fade);
      b.style.transition = "none"; b.style.width = "0%";
      void b.offsetWidth; // reflow so the reset is not animated
      b.style.transition = ""; b.classList.add("active");
      var w = 10; b.style.width = w + "%";
      trickle = setInterval(function () { w = Math.min(92, w + (92 - w) * 0.12 + 0.6); b.style.width = w + "%"; }, 130);
    }
    function barDone() {
      var b = barEl();
      clearInterval(trickle);
      b.style.width = "100%";
      fade = setTimeout(function () {
        b.classList.remove("active");
        fade = setTimeout(function () { b.style.transition = "none"; b.style.width = "0%"; }, 300);
      }, 180);
    }

    function sameDoc(a) { return a.pathname === location.pathname && a.search === location.search; }
    function eligible(a) {
      if (!a || !a.getAttribute || !a.getAttribute("href")) return false;
      if (a.origin !== origin) return false;                        // external host
      if (a.protocol !== "http:" && a.protocol !== "https:") return false; // mailto/tel
      if (a.hasAttribute("download")) return false;                 // /export.csv
      if (a.hasAttribute("data-native")) return false;              // explicit opt-out
      if (a.target && a.target !== "_self") return false;           // new window/tab
      if (a.getAttribute("rel") === "external") return false;
      if (a.hash && sameDoc(a)) return false; // in-page hash (#add,#!,#newcat) -> CSS :target sheets
      return true;
    }

    /* ── Loading states ──────────────────────────────────────────────────
       Navigation used to hold the OLD page until the new HTML arrived: on a
       slow device that is a frozen screen with no signal that anything is
       happening. These skeletons are SHAPED like the destination, so the
       screen tells you what is coming rather than just that something is.

       They appear only after SKELETON_DELAY. Most navigations here are local
       and finish in well under that, and a skeleton that flashes for 40ms is
       worse than none — it reads as a glitch. */
    /* Read at call time, and overridable, so the loading state is testable
       without timing races: a test sets window.__skDelay = 0 and asserts
       deterministically instead of racing a real slow network. */
    var SKELETON_DELAY = 120;
    function skDelay() {
      return (typeof window.__skDelay === "number") ? window.__skDelay : SKELETON_DELAY;
    }
    var skTimer = null;

    function skRows(n) {
      var out = "";
      for (var i = 0; i < n; i++) {
        out += '<div class="sk-row"><div class="sk sk-ava"></div>' +
               '<div class="sk-body"><div class="sk sk-line" style="width:' +
               (58 + (i % 3) * 12) + '%"></div>' +
               '<div class="sk sk-line sm" style="width:34%"></div></div>' +
               '<div class="sk sk-line" style="width:58px"></div></div>';
      }
      return out;
    }

    function skeletonFor(url) {
      var p = (url || "").split("?")[0];
      if (p.indexOf("/dashboard") === 0 || p === "/") {
        return '<div class="sk-stack">' +
               '<div class="sk sk-hero"></div>' +
               '<div class="sk-row"><div class="sk-body">' +
               '<div class="sk sk-line lg" style="width:44%"></div>' +
               '<div class="sk sk-line sm" style="width:70%"></div></div></div>' +
               '<div class="sk sk-chart"></div>' + skRows(3) + '</div>';
      }
      if (p.indexOf("/report") === 0 || p.indexOf("/categories") === 0) {
        return '<div class="sk-stack"><div class="sk sk-chart"></div>' + skRows(4) + '</div>';
      }
      /* transactions, review, quarantine, fraud, settings — all list-shaped */
      return '<div class="sk-stack">' +
             '<div class="sk sk-line lg" style="width:100%; height:2.6rem; border-radius:var(--r)"></div>' +
             skRows(6) + '</div>';
    }

    function skShow(url) {
      skClear();
      skTimer = setTimeout(function () {
        var main = document.querySelector("main");
        if (!main) return;
        main.setAttribute("aria-busy", "true");
        main.innerHTML = skeletonFor(url);
        skTimer = null;
      }, skDelay());
    }

    function skClear() {
      if (skTimer) { clearTimeout(skTimer); skTimer = null; }
      var main = document.querySelector("main");
      if (main) main.removeAttribute("aria-busy");
    }

    function fetchPage(url) {
      return fetch(url, { credentials: "same-origin", headers: { "X-SPA": "1" } }).then(function (r) {
        var ct = r.headers.get("content-type") || "";
        if (!r.ok || ct.indexOf("text/html") === -1) throw new Error("nonhtml");
        return r.text().then(function (t) { return { url: r.url, html: t }; }); // r.url = post-redirect
      });
    }
    function prefetch(url) {
      var e = cache[url];
      if (e && Date.now() - e.t < 10000) return;
      var p = fetchPage(url);
      cache[url] = { t: Date.now(), p: p };
      p.catch(function () { delete cache[url]; });
    }

    function execScripts(root) { // re-run inline <script>s that innerHTML left inert
      var l = root.querySelectorAll("script");
      for (var i = 0; i < l.length; i++) {
        var old = l[i], s = document.createElement("script");
        for (var j = 0; j < old.attributes.length; j++) s.setAttribute(old.attributes[j].name, old.attributes[j].value);
        s.textContent = old.textContent;
        old.parentNode.replaceChild(s, old); // insertion executes it, in order
      }
    }
    function swapRegion(doc, sel) {
      var cur = document.querySelector(sel), nxt = doc.querySelector(sel);
      if (cur && nxt) cur.innerHTML = nxt.innerHTML;
    }

    /* Overlays live OUTSIDE <main>, so a plain main-swap would leave them
       stale — e.g. the SMS categorize modal staying mounted on top of the
       bulk-review screen, which is exactly the page meant to clear it.
       Add / replace / remove them to match the incoming page. */
    function syncOverlay(doc, sel) {
      var cur = document.querySelector(sel), nxt = doc.querySelector(sel);
      if (nxt) {
        if (cur) cur.parentNode.replaceChild(nxt.cloneNode(true), cur);
        else {
          var host = document.querySelector(".app") || document.body;
          host.appendChild(nxt.cloneNode(true));
        }
      } else if (cur) {
        cur.parentNode.removeChild(cur);
      }
    }

    function render(payload, url, opts) {
      var doc;
      try { doc = new DOMParser().parseFromString(payload.html, "text/html"); }
      catch (e) { skClear(); location.href = url; return; }
      var nextMain = doc.querySelector("main");
      if (!nextMain) { skClear(); location.href = payload.url || url; return; } // auth page -> full load

      if (doc.title) document.title = doc.title;
      var th = doc.documentElement.getAttribute("data-theme");
      if (th) document.documentElement.setAttribute("data-theme", th);

      swapRegion(doc, "header.appbar"); // heading + subhead + back/avatar
      swapRegion(doc, "nav.tabbar");     // active tab + nav badge counts
      var main = document.querySelector("main");
      var wasBusy = main.getAttribute("aria-busy") === "true";
      skClear();
      main.innerHTML = nextMain.innerHTML; // swap ONLY the page body — shell stays mounted
      if (wasBusy) {                       // only animate if a skeleton was actually shown
        main.classList.remove("page-in");
        void main.offsetWidth;             // restart the animation
        main.classList.add("page-in");
      }
      execScripts(main);                   // re-run per-page inline scripts (import.html)
      syncOverlay(doc, ".cat-modal");      // SMS categorize popup
      syncOverlay(doc, ".perm-banner");    // SMS permission nudge

      var finalUrl = payload.url || url;
      if (opts.push) { history.pushState({ spa: 1, scroll: 0 }, "", finalUrl); window.scrollTo(0, 0); }
      else { window.scrollTo(0, opts.scroll || 0); }
      currentLoc = location.pathname + location.search;
      enhance();
    }

    function navigate(url, opts) {
      opts = opts || {};
      if (opts.push) { try { history.replaceState({ spa: 1, scroll: window.scrollY }, ""); } catch (e) {} }
      barStart();
      skShow(url);
      var mine = ++seq;
      var e = cache[url];
      var src = (e && Date.now() - e.t < 10000) ? e.p : fetchPage(url); // reuse prefetch if fresh
      delete cache[url];
      src.then(function (payload) {
        if (mine !== seq) return;          // a newer tap superseded this one
        render(payload, url, opts);        // render() clears the skeleton
        barDone();
      }).catch(function () {
        if (mine !== seq) return;
        skClear();                         // never leave a stuck skeleton behind
        barDone();
        location.href = url;               // graceful fallback -> real navigation
      });
    }

    /* ── Swipe to delete ─────────────────────────────────────────────────
       Deleting used to be: tap to expand, scroll the panel, find the button.
       Three actions for the most common correction in a ledger.

       Safety first, because a gesture handler that fights the scroller is
       worse than no gesture at all:
         - CSS sets touch-action: pan-y, so the browser keeps vertical
           scrolling and only hands us the horizontal axis.
         - We only start tracking once horizontal movement clearly dominates
           (2x vertical AND past a 10px slop), so a slightly-diagonal scroll
           is still a scroll.
         - Nothing is ever preventDefault()ed on the vertical axis.
       Deletion is soft and the existing undo bar appears, so a mis-swipe
       costs one tap to reverse. */
    var SWIPE_SLOP = 10;        // ignore movement below this — it is a tap
    var SWIPE_COMMIT = 0.32;    // fraction of row width that triggers delete
    var sw = null;

    function swipeRow(t) { return t && t.closest ? t.closest(".tx-item") : null; }

    document.addEventListener("touchstart", function (e) {
      if (e.touches.length !== 1) return;
      var row = swipeRow(e.target);
      if (!row || row.open) return;          // an expanded row is being edited
      sw = { row: row, x: e.touches[0].clientX, y: e.touches[0].clientY,
             dx: 0, active: false,
             sum: row.querySelector("summary.tx") };
    }, { passive: true });

    document.addEventListener("touchmove", function (e) {
      if (!sw || e.touches.length !== 1) return;
      var dx = e.touches[0].clientX - sw.x;
      var dy = e.touches[0].clientY - sw.y;
      if (!sw.active) {
        if (Math.abs(dy) > Math.abs(dx)) { sw = null; return; }   // it's a scroll
        if (Math.abs(dx) < SWIPE_SLOP || Math.abs(dx) < Math.abs(dy) * 2) return;
        sw.active = true;
        sw.row.classList.add("swiping");
        sw.row.classList.remove("swipe-settle");
      }
      sw.dx = Math.min(0, dx);               // left only; right does nothing
      sw.sum.style.transform = "translateX(" + sw.dx + "px)";
    }, { passive: true });

    function swipeEnd() {
      if (!sw) return;
      var s = sw; sw = null;
      if (!s.active) return;
      var width = s.row.getBoundingClientRect().width || 1;
      var committed = Math.abs(s.dx) > width * SWIPE_COMMIT;
      s.row.classList.remove("swiping");
      s.row.classList.add("swipe-settle");
      if (!committed) { s.sum.style.transform = ""; return; }

      /* Slide fully out, then submit the row's own delete form — the endpoint
         and CSRF-free POST shape stay defined in the template, not here. */
      s.sum.style.transform = "translateX(-100%)";
      /* No navigator.vibrate() here. Chromium gates it on activation from a
         TAP, and a swipe is not a tap — the call is refused and logged as a
         console error even mid-gesture with userActivation.isActive true.
         Shipping a call that is guaranteed to be blocked buys nothing; the
         slide-out and the undo bar already confirm what happened. */
      var id = s.row.getAttribute("data-tx-id");
      var form = s.row.querySelector('form[action$="/delete"]');
      setTimeout(function () {
        /* requestSubmit() over submit(): submit() on a form inside a collapsed
           <details> silently did nothing in testing, while requestSubmit()
           follows the normal submission path. The button click is the fallback
           for engines without requestSubmit (it is not in the Android 7
           WebView), and a direct POST is the last resort. */
        if (form && typeof form.requestSubmit === "function") {
          form.requestSubmit();
          return;
        }
        var btn = form && form.querySelector('button[type="submit"], button:not([type])');
        if (btn) { btn.click(); return; }
        if (id) {
          var f = document.createElement("form");
          f.method = "post";
          f.action = "/transactions/" + id + "/delete";
          document.body.appendChild(f);
          f.submit();
        }
      }, 180);
    }
    document.addEventListener("touchend", swipeEnd, { passive: true });
    document.addEventListener("touchcancel", function () {
      if (sw && sw.active) { sw.sum.style.transform = ""; sw.row.classList.remove("swiping"); }
      sw = null;
    }, { passive: true });

    document.addEventListener("click", function (e) {
      if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
      var a = e.target.closest && e.target.closest("a[href]");
      if (!eligible(a)) return;
      e.preventDefault();
      if (a.href !== location.href) navigate(a.href, { push: true });
    });

    document.addEventListener("pointerdown", function (e) { // warm the request ~100ms early
      var a = e.target.closest && e.target.closest("a[href]");
      if (eligible(a) && a.href !== location.href) prefetch(a.href);
    }, { passive: true });

    window.addEventListener("popstate", function (e) { // Back/Forward (Android back = wv.goBack)
      if (location.pathname + location.search === currentLoc) return; // hash-only sheet toggle -> ignore
      navigate(location.href, { push: false, scroll: (e.state && e.state.scroll) || 0 });
    });
  }

  /* ── Boot ─────────────────────────────────────────────────────────── */
  function boot() {
    try { attachDelegates(); } catch (e) {}
    try { initNav(); } catch (e) {}        // SPA failure must never break plain links
    try { enhance(); } catch (e) {}
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", boot);
  else boot();
})();
