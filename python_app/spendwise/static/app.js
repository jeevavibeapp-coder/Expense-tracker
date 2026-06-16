/* SpendWise — micro-interactions (vanilla, offline, ~CRED-smooth).
   Everything degrades gracefully and respects prefers-reduced-motion. */
(function () {
  "use strict";
  var reduce = window.matchMedia && matchMedia("(prefers-reduced-motion: reduce)").matches;
  var buzz = function (ms) { try { navigator.vibrate && navigator.vibrate(ms); } catch (e) {} };

  /* ── Animated number count-up ─────────────────────────────────────── */
  function countUp(el) {
    var target = parseFloat((el.getAttribute("data-countup") || el.textContent).replace(/[^0-9.\-]/g, ""));
    if (isNaN(target)) return;
    var dec = (el.getAttribute("data-dec") || "2") | 0;
    if (reduce) { el.textContent = fmt(target, dec); return; }
    var start = performance.now(), dur = 900;
    function step(t) {
      var p = Math.min(1, (t - start) / dur);
      var e = 1 - Math.pow(1 - p, 3); // easeOutCubic
      el.textContent = fmt(target * e, dec);
      if (p < 1) requestAnimationFrame(step);
    }
    requestAnimationFrame(step);
  }
  function fmt(n, dec) {
    return n.toLocaleString(undefined, { minimumFractionDigits: dec, maximumFractionDigits: dec });
  }

  /* ── Ripple on press ──────────────────────────────────────────────── */
  function ripple(e) {
    var el = e.currentTarget, r = el.getBoundingClientRect();
    var s = document.createElement("span");
    s.className = "ripple";
    var size = Math.max(r.width, r.height);
    s.style.width = s.style.height = size + "px";
    s.style.left = ((e.clientX || r.left + r.width / 2) - r.left - size / 2) + "px";
    s.style.top = ((e.clientY || r.top + r.height / 2) - r.top - size / 2) + "px";
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
    for (var i = 0; i < 110; i++) P.push({
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

  /* ── Scroll reveal ────────────────────────────────────────────────── */
  function reveals() {
    var els = document.querySelectorAll(".reveal");
    if (!("IntersectionObserver" in window) || reduce) {
      els.forEach(function (e) { e.classList.add("in"); }); return;
    }
    var io = new IntersectionObserver(function (es) {
      es.forEach(function (en) { if (en.isIntersecting) { en.target.classList.add("in"); io.unobserve(en.target); } });
    }, { threshold: 0.12 });
    els.forEach(function (e) { io.observe(e); });
  }

  document.addEventListener("DOMContentLoaded", function () {
    document.querySelectorAll("[data-countup]").forEach(countUp);
    document.querySelectorAll(".tap, .btn, .btn-soft, .fab, .chip, .sheet-action, .icon-btn")
      .forEach(function (el) { el.addEventListener("pointerdown", ripple); });
    reveals();
    // Celebrate successful actions.
    var q = location.search;
    if (/[?&](added|confirmed)=1/.test(q) || document.querySelector(".flash")) {
      setTimeout(confetti, 180);
    }
  });
})();
