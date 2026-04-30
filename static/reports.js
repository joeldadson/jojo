(() => {
  const select = document.getElementById("stockSelect");
  const canvas = document.getElementById("stockChart");
  const meta = document.getElementById("chartMeta");
  if (!select || !canvas) return;

  const ctx = canvas.getContext("2d");
  if (!ctx) return;

  let lastPayload = null;

  function fmtTime(ms) {
    const d = new Date(ms);
    return d.toLocaleString();
  }

  function resizeCanvas() {
    const dpr = Math.max(1, Math.floor(window.devicePixelRatio || 1));
    const rect = canvas.getBoundingClientRect();
    const w = Math.max(320, Math.floor(rect.width));
    const h = Math.max(220, Math.floor(rect.height || 260));
    canvas.width = w * dpr;
    canvas.height = h * dpr;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    if (lastPayload) draw(lastPayload);
  }

  function draw(payload) {
    const rect = canvas.getBoundingClientRect();
    const W = Math.floor(rect.width);
    const H = Math.floor(rect.height || 260);
    ctx.clearRect(0, 0, W, H);

    const points = (payload && payload.points) ? payload.points : [];
    const label = (payload && payload.label) ? payload.label : "Stock";

    const pad = 16;
    const top = 10;
    const left = pad;
    const right = W - pad;
    const bottom = H - 36;

    // Frame
    ctx.strokeStyle = "rgba(255,255,255,0.16)";
    ctx.lineWidth = 1;
    ctx.strokeRect(left, top, right - left, bottom - top);

    // Title
    ctx.fillStyle = "rgba(255,255,255,0.86)";
    ctx.font = "700 13px ui-sans-serif, system-ui, Segoe UI, Roboto, Arial";
    ctx.fillText(label, left + 10, top + 18);

    if (points.length < 2) {
      ctx.fillStyle = "rgba(255,255,255,0.55)";
      ctx.font = "500 12px ui-sans-serif, system-ui, Segoe UI, Roboto, Arial";
      ctx.fillText("Not enough data yet. Change a quantity to see the graph move.", left + 10, top + 42);
      if (meta) meta.textContent = points.length === 1 ? ("Last point: " + points[0].q + " at " + fmtTime(points[0].t)) : "";
      return;
    }

    let minT = points[0].t, maxT = points[0].t;
    let minQ = points[0].q, maxQ = points[0].q;
    for (const p of points) {
      if (p.t < minT) minT = p.t;
      if (p.t > maxT) maxT = p.t;
      if (p.q < minQ) minQ = p.q;
      if (p.q > maxQ) maxQ = p.q;
    }
    minQ = Math.min(0, minQ);
    if (maxQ === minQ) maxQ = minQ + 1;
    if (maxT === minT) maxT = minT + 1;

    // Grid lines (Y)
    ctx.strokeStyle = "rgba(255,255,255,0.08)";
    ctx.lineWidth = 1;
    for (let i = 1; i <= 3; i++) {
      const y = top + ((bottom - top) * i) / 4;
      ctx.beginPath();
      ctx.moveTo(left, y);
      ctx.lineTo(right, y);
      ctx.stroke();
    }

    function xOf(t) {
      return left + ((t - minT) / (maxT - minT)) * (right - left);
    }
    function yOf(q) {
      const v = (q - minQ) / (maxQ - minQ);
      return bottom - v * (bottom - top);
    }

    // Line
    ctx.strokeStyle = "rgba(88,215,198,0.95)";
    ctx.lineWidth = 2;
    ctx.beginPath();
    for (let i = 0; i < points.length; i++) {
      const p = points[i];
      const x = xOf(p.t);
      const y = yOf(p.q);
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    }
    ctx.stroke();

    // Dots
    ctx.fillStyle = "rgba(88,215,198,0.95)";
    for (const p of points) {
      const x = xOf(p.t);
      const y = yOf(p.q);
      ctx.beginPath();
      ctx.arc(x, y, 3, 0, Math.PI * 2);
      ctx.fill();
    }

    // Axis labels
    ctx.fillStyle = "rgba(255,255,255,0.55)";
    ctx.font = "600 11px ui-monospace, SFMono-Regular, Menlo, Consolas, monospace";
    ctx.fillText(String(maxQ), left + 6, top + 28);
    ctx.fillText(String(minQ), left + 6, bottom - 6);
    ctx.textAlign = "right";
    ctx.fillText(fmtTime(minT), right - 6, H - 14);
    ctx.textAlign = "left";
    ctx.fillText(fmtTime(maxT), left + 6, H - 14);
    ctx.textAlign = "left";

    const last = points[points.length - 1];
    if (meta) {
      meta.textContent = "Last: " + last.q + " at " + fmtTime(last.t) + " (points: " + points.length + ")";
    }
  }

  async function load() {
    const id = (select.value || "").trim();
    const url = id ? ("/reports/stock.json?id=" + encodeURIComponent(id)) : "/reports/stock.json";
    try {
      const res = await fetch(url, { headers: { "Accept": "application/json" } });
      if (!res.ok) throw new Error("HTTP " + res.status);
      const payload = await res.json();
      lastPayload = payload;
      draw(payload);
    } catch (e) {
      lastPayload = null;
      draw({ label: "Stock", points: [] });
      if (meta) meta.textContent = "Failed to load chart data.";
    }
  }

  select.addEventListener("change", load);

  // Initial layout and load
  resizeCanvas();
  load();

  // Keep responsive
  if (window.ResizeObserver) {
    new ResizeObserver(() => resizeCanvas()).observe(canvas);
  } else {
    window.addEventListener("resize", () => resizeCanvas());
  }
})();

