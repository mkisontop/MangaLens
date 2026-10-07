// Runs before the page paints, so a reader who chose a theme never sees the other one flash.
try {
  var t = localStorage.getItem("ml-theme");
  if (t === "light" || t === "dark") document.documentElement.setAttribute("data-theme", t);
} catch (e) {}
