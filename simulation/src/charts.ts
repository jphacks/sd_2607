// Minimal canvas line charts in the app's palette.

export interface Series {
  label: string;
  color: string;
  values: number[];
}

export function lineChart(canvas: HTMLCanvasElement, xs: number[], series: Series[], opts: { max?: number; xLabel?: (x: number) => string } = {}) {
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.clientWidth;
  const h = canvas.clientHeight;
  canvas.width = w * dpr;
  canvas.height = h * dpr;
  const ctx = canvas.getContext("2d")!;
  ctx.scale(dpr, dpr);
  ctx.clearRect(0, 0, w, h);
  const pad = { l: 34, r: 8, t: 8, b: 18 };
  const pw = w - pad.l - pad.r;
  const ph = h - pad.t - pad.b;
  if (xs.length < 2) {
    ctx.fillStyle = "#6B7480";
    ctx.font = "11px system-ui";
    ctx.fillText("データ待ち", pad.l, pad.t + 14);
    return;
  }
  const x0 = xs[0];
  const x1 = xs[xs.length - 1];
  const max = opts.max ?? Math.max(1e-9, ...series.flatMap((s) => s.values.filter(isFinite)));
  const px = (x: number) => pad.l + ((x - x0) / (x1 - x0 || 1)) * pw;
  const py = (v: number) => pad.t + ph - (v / max) * ph;
  ctx.strokeStyle = "#2A2F36";
  ctx.lineWidth = 1;
  ctx.fillStyle = "#6B7480";
  ctx.font = "10px ui-monospace, monospace";
  for (let i = 0; i <= 4; i++) {
    const v = (max * i) / 4;
    const y = py(v);
    ctx.beginPath();
    ctx.moveTo(pad.l, y);
    ctx.lineTo(w - pad.r, y);
    ctx.stroke();
    const label = opts.max === 1 ? `${Math.round(v * 100)}%` : v >= 100 ? Math.round(v).toString() : v.toFixed(v < 10 ? 1 : 0);
    ctx.fillText(label, 2, y + 3);
  }
  const fmt = opts.xLabel ?? ((x: number) => `${Math.round(x)}h`);
  ctx.fillText(fmt(x0), pad.l, h - 4);
  const end = fmt(x1);
  ctx.fillText(end, w - pad.r - ctx.measureText(end).width, h - 4);
  for (const s of series) {
    ctx.strokeStyle = s.color;
    ctx.lineWidth = 1.6;
    ctx.beginPath();
    let started = false;
    s.values.forEach((v, i) => {
      if (!isFinite(v)) return;
      const X = px(xs[i]);
      const Y = py(v);
      if (!started) {
        ctx.moveTo(X, Y);
        started = true;
      } else ctx.lineTo(X, Y);
    });
    ctx.stroke();
  }
}
