// Labels and number formatting shared by the panels.

export const pct = (v: number) => `${Math.round(v * 100)}%`;

export const num = (v: number) => (Math.abs(v) >= 10000 ? `${(v / 10000).toFixed(1)}万` : Math.round(v).toLocaleString("ja-JP"));

export function elapsed(minutes: number): string {
  const d = Math.floor(minutes / 1440);
  const h = Math.floor((minutes % 1440) / 60);
  const m = Math.floor(minutes % 60);
  return `${d}日 ${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`;
}

export function hoursLabel(h: number | null | undefined): string {
  if (h == null || !isFinite(h)) return "—";
  if (h < 1) return `${Math.round(h * 60)}分`;
  if (h < 48) return `${h.toFixed(1)}時間`;
  return `${(h / 24).toFixed(1)}日`;
}

const clock = new Intl.DateTimeFormat("ja-JP", {
  timeZone: "Asia/Tokyo",
  month: "numeric",
  day: "numeric",
  weekday: "short",
  hour: "2-digit",
  minute: "2-digit",
});

export function wallClock(startIso: string, minutes: number): string {
  return clock.format(new Date(new Date(startIso).getTime() + minutes * 60000));
}

export const SITE_LABEL: Record<string, string> = {
  shelter: "避難所",
  hq: "災害対策本部",
  fire: "消防",
  hospital: "病院",
  building: "建物",
  open: "一時集合場所",
  depot: "物資・救助拠点",
  signal_spot: "電波が届く高台",
  high_ground: "高台",
};

/** Indicators compared in sweeps: label, unit, and whether larger is better. */
export const INDICATORS: { key: string; label: string; unit: "%" | "h" | "count"; better: "up" | "down" }[] = [
  { key: "trapped_dead", label: "救助が間に合わなかった人", unit: "count", better: "down" },
  { key: "rescued_by_team", label: "救助隊による救出", unit: "count", better: "up" },
  { key: "trapped_to_hq_median_h", label: "要救助が本部に届くまで（中央値）", unit: "h", better: "down" },
  { key: "trapped_reached_hq", label: "要救助が本部に届いた割合", unit: "%", better: "up" },
  { key: "anpi_24h", label: "家族に安否が届いた（24時間）", unit: "%", better: "up" },
  { key: "anpi_72h", label: "家族に安否が届いた（72時間）", unit: "%", better: "up" },
  { key: "anpi_hq_24h", label: "本部が安否を把握（24時間）", unit: "%", better: "up" },
  { key: "anpi_hq_72h", label: "本部が安否を把握（72時間）", unit: "%", better: "up" },
  { key: "needs_to_ops_median_h", label: "避難所の不足が県に届くまで", unit: "h", better: "down" },
  { key: "road_cut_to_ops_median_h", label: "道路寸断が県に届くまで", unit: "h", better: "down" },
  { key: "isolated_to_ops_median_h", label: "孤立集落が県に届くまで", unit: "h", better: "down" },
  { key: "warned_at_arrival", label: "津波到達時、残留者の警報受信率", unit: "%", better: "up" },
  { key: "shortage_shelter_hours", label: "物資が尽きた避難所×時間", unit: "count", better: "down" },
  { key: "concentration_events", label: "物資が集中しすぎた回数", unit: "count", better: "down" },
  { key: "fake_dispatches", label: "偽の救助要請への出動", unit: "count", better: "down" },
  { key: "unknown_cut_hits", label: "寸断を知らず立ち往生した車両", unit: "count", better: "down" },
  { key: "isolated_unknown_events", label: "48時間把握されない孤立集落", unit: "count", better: "down" },
  { key: "shelters_unknown_events", label: "3日間把握されない避難所", unit: "count", better: "down" },
  { key: "mesh_exchanges", label: "メッシュでの交換回数", unit: "count", better: "up" },
];

export function indicatorValue(unit: string, v: number | undefined): string {
  if (v == null || !isFinite(v)) return "—";
  if (unit === "%") return pct(v);
  if (unit === "h") return hoursLabel(v);
  return num(v);
}

export function el<K extends keyof HTMLElementTagNameMap>(tag: K, cls?: string, text?: string): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}
