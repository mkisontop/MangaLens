// mangalenz.net: the few things the page does. Everything works without it; this only adds.
(function () {
  "use strict";
  var root = document.documentElement;
  var reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  // ---- theme: follows the system until the reader picks one ----
  var dark = window.matchMedia("(prefers-color-scheme: dark)");
  var toggle = document.querySelector("[data-theme-toggle]");
  function theme() { return root.getAttribute("data-theme") || (dark.matches ? "dark" : "light"); }
  function syncToggle() {
    if (!toggle) return;
    var isDark = theme() === "dark";
    toggle.setAttribute("aria-label", isDark ? "Switch to light theme" : "Switch to dark theme");
    toggle.setAttribute("aria-pressed", String(isDark));
    document.querySelectorAll('meta[name="theme-color"]').forEach(function (m) {
      m.setAttribute("content", isDark ? "#15101A" : "#FFF4DC");
    });
  }
  if (toggle) {
    toggle.addEventListener("click", function () {
      var next = theme() === "dark" ? "light" : "dark";
      root.setAttribute("data-theme", next);
      try { localStorage.setItem("ml-theme", next); } catch (e) {}
      syncToggle();
    });
    if (dark.addEventListener) dark.addEventListener("change", syncToggle);
    syncToggle();
  }

  // ---- menu on small screens ----
  var menu = document.querySelector("[data-menu]");
  var nav = document.getElementById("nav");
  if (menu && nav) {
    function setOpen(open) {
      nav.classList.toggle("open", open);
      menu.setAttribute("aria-expanded", String(open));
    }
    menu.addEventListener("click", function () { setOpen(!nav.classList.contains("open")); });
    nav.addEventListener("click", function (e) { if (e.target.closest("a")) setOpen(false); });
    document.addEventListener("keydown", function (e) { if (e.key === "Escape") setOpen(false); });
  }

  // ---- compare: raw page against the English one ----
  var cmp = document.querySelector("[data-compare]");
  if (cmp) {
    var range = cmp.querySelector('input[type="range"]');
    var set = function (v) {
      v = Math.max(0, Math.min(100, Number(v)));
      cmp.style.setProperty("--split", v + "%");
      if (range) {
        range.value = String(Math.round(v));
        range.setAttribute("aria-valuetext", Math.round(v) + "% English");
      }
    };
    var at = function (e) {
      var r = cmp.getBoundingClientRect();
      return ((e.clientX - r.left) / r.width) * 100;
    };
    var drag = null;
    cmp.addEventListener("pointerdown", function (e) {
      if (e.button !== undefined && e.button !== 0) return;
      drag = { id: e.pointerId, x: e.clientX, y: e.clientY, live: e.pointerType === "mouse" };
      if (drag.live) { set(at(e)); cmp.setPointerCapture(e.pointerId); e.preventDefault(); }
    });
    cmp.addEventListener("pointermove", function (e) {
      if (!drag || drag.id !== e.pointerId) return;
      if (!drag.live) {
        // a finger: only a sideways drag is ours; up and down still scrolls the page
        var dx = Math.abs(e.clientX - drag.x), dy = Math.abs(e.clientY - drag.y);
        if (dx < 6 && dy < 6) return;
        if (dy > dx) { drag = null; return; }
        drag.live = true;
        try { cmp.setPointerCapture(e.pointerId); } catch (err) {}
      }
      set(at(e));
    });
    var end = function () { drag = null; };
    cmp.addEventListener("pointerup", end);
    cmp.addEventListener("pointercancel", end);
    if (range) {
      range.addEventListener("input", function () { set(range.value); });
      cmp.addEventListener("keydown", function (e) {
        if (e.target === range) return;
        if (e.key === "ArrowLeft" || e.key === "ArrowRight") {
          set(Number(range.value) + (e.key === "ArrowLeft" ? -5 : 5));
          e.preventDefault();
        }
      });
    }
    set(range ? range.value : 50);

    // a first glimpse: the English sweeps in, then settles half way
    if (!reduced && "IntersectionObserver" in window) {
      set(0);
      var shown = new IntersectionObserver(function (entries) {
        if (!entries[0].isIntersecting) return;
        shown.disconnect();
        var t0 = null;
        var step = function (t) {
          if (drag) return;
          if (t0 === null) t0 = t;
          var k = Math.min(1, (t - t0) / 1400);
          var e = k < .5 ? 2 * k * k : 1 - Math.pow(-2 * k + 2, 2) / 2;
          set(e * 50 + (k < 1 ? Math.sin(k * Math.PI) * 30 : 0));
          if (k < 1) requestAnimationFrame(step);
        };
        setTimeout(function () { requestAnimationFrame(step); }, 350);
      }, { threshold: .5 });
      shown.observe(cmp);
    }
  }

  // ---- dark gaps levels ----
  var gaps = document.querySelector("[data-gaps]");
  if (gaps) {
    document.querySelectorAll('input[name="gaps"]').forEach(function (r) {
      r.addEventListener("change", function () {
        gaps.style.setProperty("--gap", r.value);
        gaps.classList.toggle("on", Number(r.value) > 0);
      });
    });
  }

  // ---- the release, as GitHub has it right now ----
  var slots = document.querySelectorAll("[data-version]");
  var lines = document.querySelectorAll("[data-version-line]");
  if ((slots.length || lines.length) && window.fetch) {
    fetch("https://api.github.com/repos/mkisontop/MangaLens/releases/latest", { headers: { Accept: "application/vnd.github+json" } })
      .then(function (r) { return r.ok ? r.json() : Promise.reject(r.status); })
      .then(function (rel) {
        if (!rel || !rel.tag_name) return;
        var version = String(rel.tag_name).replace(/^v/i, "");
        var apk = (rel.assets || []).filter(function (a) { return a.name === "MangaLens.apk"; })[0];
        var size = apk ? (apk.size / 1048576).toFixed(0) + " MB" : "";
        var date = rel.published_at ? new Date(rel.published_at).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" }) : "";
        slots.forEach(function (el) { el.textContent = "v" + version + (size ? " · " + size : "") + " · free"; });
        // documentation as it stands in the release people download, not on an older branch
        document.querySelectorAll("[data-at-release]").forEach(function (a) {
          a.href = "https://github.com/mkisontop/MangaLens/blob/" + encodeURIComponent(rel.tag_name) + "/" + a.getAttribute("data-at-release");
        });
        lines.forEach(function (el) { el.textContent = "Version " + version + (date ? ", " + date : "") + " · Android 8.0 or later"; });
      })
      .catch(function () {});
  }

  // ---- panels slide in as they arrive ----
  if (!reduced && "IntersectionObserver" in window) {
    root.classList.add("js-reveal");
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting) { en.target.classList.add("in"); io.unobserve(en.target); }
      });
    }, { rootMargin: "0px 0px -6% 0px" });
    document.querySelectorAll(".reveal").forEach(function (el) { io.observe(el); });
  }

  var year = document.querySelector("[data-year]");
  if (year) year.textContent = String(new Date().getFullYear());
})();
