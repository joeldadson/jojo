(() => {
  function qs(sel, root = document) {
    return root.querySelector(sel);
  }

  function qsa(sel, root = document) {
    return Array.from(root.querySelectorAll(sel));
  }

  function getParam(name) {
    const url = new URL(window.location.href);
    return url.searchParams.get(name);
  }

  function clearParam(name) {
    const url = new URL(window.location.href);
    url.searchParams.delete(name);
    window.history.replaceState({}, "", url.toString());
  }

  function showToast(message) {
    const el = qs("#toast");
    if (!el) return;
    el.textContent = message;
    el.classList.add("show");
    window.setTimeout(() => el.classList.remove("show"), 4200);
  }

  // Toast from URL (?toast=...)
  const toast = getParam("toast");
  if (toast) {
    showToast(toast);
    clearParam("toast");
  }

  // Cmd/Ctrl+K focuses the first search input; / focuses too.
  window.addEventListener("keydown", (e) => {
    const isMac = navigator.platform.toLowerCase().includes("mac");
    const mod = isMac ? e.metaKey : e.ctrlKey;
    if ((mod && (e.key === "k" || e.key === "K")) || e.key === "/") {
      const input = qs("form.search input[name='q']");
      if (input) {
        e.preventDefault();
        input.focus();
        input.select();
      }
    }
  });

  // Sortable tables
  function parseCellValue(text) {
    const t = (text || "").trim();
    if (!t) return { kind: "text", v: "" };

    // Currency/number
    const numish = t.replace(/[$,]/g, "");
    if (/^-?\d+(\.\d+)?$/.test(numish)) return { kind: "num", v: parseFloat(numish) };

    // ISO-ish date
    if (t.includes("T") && (t.endsWith("Z") || t.includes("+"))) {
      const ms = Date.parse(t);
      if (!Number.isNaN(ms)) return { kind: "num", v: ms };
    }

    // Fallback text
    return { kind: "text", v: t.toLowerCase() };
  }

  function makeSortable(table) {
    const thead = table.querySelector("thead");
    const tbody = table.querySelector("tbody");
    if (!thead || !tbody) return;

    const headers = qsa("th", thead);
    headers.forEach((th, idx) => {
      th.classList.add("sortTh");
      th.tabIndex = 0;
      th.addEventListener("click", () => sortBy(idx));
      th.addEventListener("keydown", (e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          sortBy(idx);
        }
      });
    });

    function sortBy(idx) {
      const current = table.getAttribute("data-sort-idx");
      const currentDir = table.getAttribute("data-sort-dir") || "asc";
      const nextDir = (String(current) === String(idx) && currentDir === "asc") ? "desc" : "asc";

      const rows = qsa("tr", tbody);
      const decorated = rows.map((tr, i) => {
        const td = tr.children[idx];
        const val = parseCellValue(td ? td.textContent : "");
        return { tr, i, val };
      });

      decorated.sort((a, b) => {
        // Stable by original index.
        if (a.val.kind === "num" && b.val.kind === "num") {
          if (a.val.v === b.val.v) return a.i - b.i;
          return a.val.v < b.val.v ? -1 : 1;
        }
        const av = String(a.val.v);
        const bv = String(b.val.v);
        if (av === bv) return a.i - b.i;
        return av < bv ? -1 : 1;
      });

      if (nextDir === "desc") decorated.reverse();

      tbody.innerHTML = "";
      for (const d of decorated) tbody.appendChild(d.tr);

      table.setAttribute("data-sort-idx", String(idx));
      table.setAttribute("data-sort-dir", nextDir);
      headers.forEach((h) => h.classList.remove("sortedAsc", "sortedDesc"));
      headers[idx].classList.add(nextDir === "asc" ? "sortedAsc" : "sortedDesc");
    }
  }

  qsa("table.sortable").forEach(makeSortable);

  // Add/Edit book: allow custom category when not in dropdown.
  (function bookCategoryEnhance() {
    const select = qs("#categorySelect");
    const wrap = qs("#categoryCustomWrap");
    const input = qs("#categoryCustom");
    if (!select || !wrap || !input) return;

    function isCustomSelected() {
      return String(select.value || "").toLowerCase() === "__custom__";
    }

    function update() {
      const custom = isCustomSelected();
      wrap.hidden = !custom;
      input.disabled = !custom;
      if (custom) {
        // If the input already has value (server-rendered), keep it.
        window.setTimeout(() => input.focus(), 0);
      } else {
        input.value = "";
      }
    }

    select.addEventListener("change", update);
    update();
  })();

  // Checkout live summary
  (function checkoutEnhance() {
    const form = qs("#checkoutForm");
    if (!form) return;
    const qty = qs("input[name='order_quantity']", form);
    const totalEl = qs("#checkoutTotal");
    const remainingEl = qs("#checkoutRemaining");
    if (!qty || !totalEl || !remainingEl) return;

    const unitCents = parseInt(form.getAttribute("data-unit-cents") || "0", 10);
    const available = parseInt(form.getAttribute("data-available") || "0", 10);

    function fmtUsd(cents) {
      const s = Math.abs(cents);
      const dollars = Math.floor(s / 100);
      const rem = s % 100;
      const out = "$" + dollars + "." + (rem < 10 ? "0" + rem : rem);
      return cents < 0 ? "-" + out : out;
    }

    function clampInt(v, min, max) {
      v = Math.floor(v);
      if (Number.isNaN(v)) return min;
      if (v < min) return min;
      if (v > max) return max;
      return v;
    }

    function update() {
      const q = clampInt(parseInt(qty.value || "0", 10), 0, 9999);
      const total = unitCents * q;
      const rem = Math.max(0, available - q);
      totalEl.textContent = fmtUsd(total);
      totalEl.setAttribute("data-cents", String(total));
      remainingEl.textContent = String(rem);
      if (q > available) remainingEl.classList.add("bad");
      else remainingEl.classList.remove("bad");
    }

    qty.addEventListener("input", update);
    update();

    form.addEventListener("submit", () => {
      // Lightweight UX: let server validate, but warn if someone typed a card number.
      const maybeCard = qsa("input", form).some((i) => /\b\d{12,19}\b/.test(i.value || ""));
      if (maybeCard) showToast("Do not enter card numbers. Use a payment reference only.");
    });
  })();
})();
