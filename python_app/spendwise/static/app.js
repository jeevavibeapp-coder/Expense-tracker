/* SpendWise — micro-interactions + instant client-side navigation.
   Vanilla, offline, no libraries. Degrades gracefully: if this file is absent
   or throws, every <a> is a normal full-page navigation and all server-
   rendered content is visible without JS. */
(function () {
  "use strict";
  var reduce = window.matchMedia && matchMedia("(prefers-reduced-motion: reduce)").matches;
  var buzz = function (ms) { try { navigator.vibrate && navigator.vibrate(ms); } catch (e) {} };

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

    function render(payload, url, opts) {
      var doc;
      try { doc = new DOMParser().parseFromString(payload.html, "text/html"); }
      catch (e) { location.href = url; return; }
      var nextMain = doc.querySelector("main");
      if (!nextMain) { location.href = payload.url || url; return; } // auth page -> full load

      if (doc.title) document.title = doc.title;
      var th = doc.documentElement.getAttribute("data-theme");
      if (th) document.documentElement.setAttribute("data-theme", th);

      swapRegion(doc, "header.appbar"); // heading + subhead + back/avatar
      swapRegion(doc, "nav.tabbar");     // active tab + nav badge counts
      var main = document.querySelector("main");
      main.innerHTML = nextMain.innerHTML; // swap ONLY the page body — shell stays mounted
      execScripts(main);                   // re-run per-page inline scripts (import.html)

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
      var mine = ++seq;
      var e = cache[url];
      var src = (e && Date.now() - e.t < 10000) ? e.p : fetchPage(url); // reuse prefetch if fresh
      delete cache[url];
      src.then(function (payload) {
        if (mine !== seq) return;          // a newer tap superseded this one
        render(payload, url, opts);
        barDone();
      }).catch(function () {
        if (mine !== seq) return;
        barDone();
        location.href = url;               // graceful fallback -> real navigation
      });
    }

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
