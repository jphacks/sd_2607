// The map: 地理院タイル (shown dark), sites, roads, base stations, and agents drawn on a canvas overlay.

import L from "leaflet";
import "leaflet/dist/leaflet.css";
import { SITE_LABEL } from "./format";
import { FLAG, role, type Frame, type SiteState, type StaticInfo, type Status } from "./types";

export type ColorMode = "comm" | "anpi" | "focus";

const C = {
  primary: "#FFB900",
  primaryDim: "#9C7300",
  danger: "#FF3B30",
  warning: "#FF8A00",
  safe: "#34C759",
  info: "#4DA3FF",
  text2: "#9AA3AD",
  text3: "#6B7480",
  border: "#3A4049",
  dead: "#6E2420",
  nav: "#FFE27A",
};

const mercY = (lat: number) => Math.log(Math.tan(Math.PI / 4 + (lat * Math.PI) / 360));

export class MapView {
  map: L.Map;
  private canvas: HTMLCanvasElement;
  private info: StaticInfo | null = null;
  private frame: Frame | null = null;
  private siteLayer = L.layerGroup();
  private roadLayer = L.layerGroup();
  private towerLayer = L.layerGroup();
  private coverLayer = L.layerGroup();
  private siteMarkers: L.CircleMarker[] = [];
  private roadLines: L.Polyline[] = [];
  private towerMarkers: L.CircleMarker[] = [];
  private coverCircles: (L.Circle | null)[] = [];
  mode: ColorMode = "comm";
  onSiteClick: (i: number) => void = () => {};

  constructor(container: HTMLElement, canvas: HTMLCanvasElement) {
    this.canvas = canvas;
    this.map = L.map(container, { zoomControl: false, attributionControl: true, preferCanvas: true, zoomSnap: 0.25 });
    L.control.zoom({ position: "bottomright" }).addTo(this.map);
    L.tileLayer("https://cyberjapandata.gsi.go.jp/xyz/pale/{z}/{x}/{y}.png", {
      attribution: '<a href="https://maps.gsi.go.jp/development/ichiran.html">地理院タイル</a>',
      maxNativeZoom: 18,
      maxZoom: 21,
    }).addTo(this.map);
    this.coverLayer.addTo(this.map);
    this.roadLayer.addTo(this.map);
    this.siteLayer.addTo(this.map);
    this.map.on("move zoom resize viewreset", () => this.draw());
    new ResizeObserver(() => {
      this.map.invalidateSize();
      this.draw();
    }).observe(container);
  }

  showTowers(on: boolean) {
    if (on) this.towerLayer.addTo(this.map);
    else this.towerLayer.remove();
  }

  load(info: StaticInfo) {
    this.info = info;
    this.frame = null;
    this.map.setMaxZoom(Math.max(info.view.max_zoom, 18) + 1);
    this.map.setView(info.view.center, info.view.zoom);
    for (const g of [this.siteLayer, this.roadLayer, this.towerLayer, this.coverLayer]) g.clearLayers();
    this.roadLines = info.roads.map((r) => {
      const line = L.polyline(r.geom.length >= 2 ? r.geom : [r.a, r.b], { color: C.border, weight: 2.5, opacity: 0.9, interactive: true });
      const cut = r.cut_until_h != null ? `<br>寸断: 〜${Math.round(r.cut_until_h)}時間（${r.cause}${r.foot_ok ? "・徒歩可" : ""}）` : "";
      line.bindTooltip(`${r.name || "道路"}${cut}`, { sticky: true, className: "tip" });
      line.addTo(this.roadLayer);
      return line;
    });
    this.towerMarkers = info.towers.map((t) => {
      const m = L.circleMarker([t.lat, t.lon], {
        radius: t.temporary ? 6 : 2.5,
        color: C.info,
        weight: t.temporary ? 2 : 0,
        fillColor: C.info,
        fillOpacity: 0.6,
        interactive: t.temporary,
      });
      if (t.temporary) {
        m.bindTooltip(`${t.name}<br>${Math.round(t.from_h)}時間後から${t.until_h != null ? `〜${Math.round(t.until_h)}時間` : ""}`, { className: "tip" });
        m.addTo(this.coverLayer);
      } else m.addTo(this.towerLayer);
      return m;
    });
    this.coverCircles = info.towers.map((t) =>
      t.temporary
        ? L.circle([t.lat, t.lon], { radius: t.radius, color: C.info, weight: 1, dashArray: "4 4", fillOpacity: 0.04, interactive: false })
        : null,
    );
    this.siteMarkers = info.sites.map((s, i) => {
      const m = L.circleMarker([s.lat, s.lon], this.siteStyle(s.kind, null));
      m.bindTooltip(() => this.siteTip(i), { className: "tip" });
      m.on("click", () => this.onSiteClick(i));
      m.addTo(this.siteLayer);
      return m;
    });
    this.draw();
  }

  private lastStatus: Status | null = null;

  private siteTip(i: number): string {
    const s = this.info!.sites[i];
    const st = this.lastStatus?.sites[i];
    const lines = [`<b>${s.name}</b>`, SITE_LABEL[s.kind] + (s.kind === "shelter" ? (s.designated ? "（指定）" : "（自主）") : "")];
    if (st && s.kind === "shelter") {
      lines.push(`避難者 ${st.present}人（最大 ${st.peak}${s.peak_observed ? ` / 記録 ${s.peak_observed}` : ""}、収容 ${s.capacity}）`);
      if (st.water_days >= 0) lines.push(`水 ${st.water_days.toFixed(1)}日分 ・ 食料 ${st.food_days.toFixed(1)}日分`);
      lines.push(`${st.known ? "本部が把握" : "本部は未把握"}${st.starlink ? " ・ Starlink" : ""}${st.internet && !st.starlink ? " ・ 固定回線" : ""}`);
      lines.push(`掲示板 ${st.board}件`);
    }
    if (s.note) lines.push(`<span class="muted">${s.note}</span>`);
    return lines.join("<br>");
  }

  private siteStyle(kind: string, st: SiteState | null): L.CircleMarkerOptions {
    switch (kind) {
      case "shelter": {
        const r = st ? Math.max(5, Math.min(18, 3 + Math.sqrt(st.present) * 0.6)) : 6;
        const color = !st ? C.text2 : st.short ? C.danger : st.need ? C.warning : C.safe;
        return {
          radius: r,
          color: st?.starlink ? C.info : color,
          weight: st?.starlink ? 3 : 2,
          dashArray: st && !st.known ? "3 3" : undefined,
          fillColor: color,
          fillOpacity: 0.25,
        };
      }
      case "hq":
        return { radius: 8, color: "#F2F4F7", weight: 2, fillColor: "#F2F4F7", fillOpacity: 0.9 };
      case "fire":
        return { radius: 5, color: C.danger, weight: 2, fillColor: C.danger, fillOpacity: 0.6 };
      case "depot":
        return { radius: 7, color: C.safe, weight: 2, fillColor: C.safe, fillOpacity: 0.5 };
      case "signal_spot":
        return { radius: 6, color: C.info, weight: 2, dashArray: "2 3", fillOpacity: 0 };
      case "high_ground":
        return { radius: 6, color: C.safe, weight: 1, dashArray: "2 3", fillOpacity: 0 };
      case "open":
        return { radius: 7, color: C.text2, weight: 1, dashArray: "2 3", fillOpacity: 0 };
      default:
        return { radius: 5, color: C.text3, weight: 1, fillColor: C.text3, fillOpacity: 0.4 };
    }
  }

  update(status: Status) {
    this.lastStatus = status;
    if (!this.info) return;
    status.sites.forEach((st, i) => this.siteMarkers[i]?.setStyle(this.siteStyle(this.info!.sites[i].kind, st)));
    status.roads.forEach(([cut, known], i) => {
      const line = this.roadLines[i];
      if (!line) return;
      if (cut) line.setStyle({ color: known ? C.danger : C.warning, dashArray: known ? undefined : "6 6", opacity: 0.95, weight: 3 });
      else line.setStyle({ color: C.border, dashArray: undefined, opacity: 0.9, weight: 3 });
    });
    status.towers_up.forEach((up, i) => {
      const m = this.towerMarkers[i];
      const t = this.info!.towers[i];
      if (!m || !t) return;
      m.setStyle({ fillColor: up ? C.info : C.text3, color: up ? C.info : C.text3, fillOpacity: up ? 0.7 : 0.25 });
      const cover = this.coverCircles[i];
      if (cover) {
        if (up && !this.coverLayer.hasLayer(cover)) cover.addTo(this.coverLayer);
        if (!up && this.coverLayer.hasLayer(cover)) this.coverLayer.removeLayer(cover);
      }
    });
  }

  setFrame(f: Frame) {
    this.frame = f;
    this.draw();
  }

  flyTo(lat: number, lon: number) {
    this.map.flyTo([lat, lon], Math.max(this.map.getZoom(), this.info?.id === "noto" ? 13 : 17), { duration: 0.6 });
  }

  /** Agents: positions in meters are converted with the scenario projection, then Web Mercator. */
  draw() {
    const cv = this.canvas;
    const size = this.map.getSize();
    const dpr = window.devicePixelRatio || 1;
    if (cv.width !== size.x * dpr || cv.height !== size.y * dpr) {
      cv.width = size.x * dpr;
      cv.height = size.y * dpr;
      cv.style.width = `${size.x}px`;
      cv.style.height = `${size.y}px`;
    }
    const ctx = cv.getContext("2d")!;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, size.x, size.y);
    const f = this.frame;
    const info = this.info;
    if (!f || !info) return;
    const p = info.projection;
    // Linear in longitude and in Mercator y: fit from two reference points.
    const a = this.map.latLngToContainerPoint([p.lat0, p.lon0]);
    const b = this.map.latLngToContainerPoint([p.lat0 + 0.1, p.lon0 + 0.1]);
    const sx = (b.x - a.x) / 0.1;
    const my0 = mercY(p.lat0);
    const sy = (b.y - a.y) / (mercY(p.lat0 + 0.1) - my0);
    const zoom = this.map.getZoom();
    const r = Math.max(1.1, Math.min(4.5, Math.pow(2, (zoom - 13) / 2)));
    const buckets = new Map<string, number[]>();
    const push = (c: string, x: number, y: number) => {
      let arr = buckets.get(c);
      if (!arr) buckets.set(c, (arr = []));
      arr.push(x, y);
    };
    const responders: [number, number, string][] = [];
    const W = size.x;
    const H = size.y;
    for (let i = 0; i < f.n; i++) {
      const fl = f.flags[i];
      if (fl & FLAG.offmap) continue;
      const lon = p.lon0 + f.xy[i * 2] / p.kx;
      const lat = p.lat0 + f.xy[i * 2 + 1] / p.ky;
      const x = a.x + (lon - p.lon0) * sx;
      const y = a.y + (mercY(lat) - my0) * sy;
      if (x < -10 || y < -10 || x > W + 10 || y > H + 10) continue;
      const rl = role(fl);
      if (rl >= 3) {
        responders.push([x, y, rl === 3 ? C.warning : rl === 4 ? C.safe : C.info]);
        continue;
      }
      push(this.color(fl), x, y);
    }
    // Draw dim first so highlighted states stay on top.
    const order = [C.dead, C.text3, C.border, C.text2, C.warning, C.safe, C.info, C.primaryDim, C.primary, C.nav, C.danger];
    const keys = [...buckets.keys()].sort((x, y) => order.indexOf(x) - order.indexOf(y));
    for (const c of keys) {
      const arr = buckets.get(c)!;
      ctx.fillStyle = c;
      const rr = c === C.danger ? r * 1.8 : r;
      if (rr < 2) {
        for (let k = 0; k < arr.length; k += 2) ctx.fillRect(arr[k] - rr, arr[k + 1] - rr, rr * 2, rr * 2);
      } else {
        ctx.beginPath();
        for (let k = 0; k < arr.length; k += 2) {
          ctx.moveTo(arr[k] + rr, arr[k + 1]);
          ctx.arc(arr[k], arr[k + 1], rr, 0, Math.PI * 2);
        }
        ctx.fill();
      }
    }
    const rs = Math.max(3, r * 1.6);
    for (const [x, y, c] of responders) {
      ctx.fillStyle = c;
      ctx.strokeStyle = "#0B0D10";
      ctx.lineWidth = 1;
      ctx.fillRect(x - rs, y - rs, rs * 2, rs * 2);
      ctx.strokeRect(x - rs, y - rs, rs * 2, rs * 2);
    }
  }

  private color(fl: number): string {
    if (fl & FLAG.dead) return C.dead;
    if (fl & FLAG.trapped) return C.danger;
    switch (this.mode) {
      case "anpi":
        return fl & FLAG.anpi ? C.safe : C.warning;
      case "focus":
        return fl & FLAG.focus ? C.primary : C.border;
      default:
        if (fl & FLAG.nav) return C.nav;
        if (!(fl & FLAG.phone)) return C.text3;
        if (fl & FLAG.app) return fl & FLAG.meshed ? C.primary : C.primaryDim;
        if (fl & FLAG.online) return C.info;
        return C.text2;
    }
  }
}

export const LEGEND: Record<ColorMode, [string, string][]> = {
  comm: [
    [C.primary, "アプリ（直近にメッシュ交換）"],
    [C.primaryDim, "アプリ"],
    [C.nav, "対応カーナビ（走行中・車中泊）"],
    [C.info, "通信できる"],
    [C.text2, "圏外"],
    [C.text3, "電池切れ・携帯なし"],
    [C.danger, "閉じ込め"],
  ],
  anpi: [
    [C.safe, "家族に安否が届いた"],
    [C.warning, "まだ届いていない"],
    [C.danger, "閉じ込め"],
  ],
  focus: [
    [C.primary, "選んだ情報を知っている"],
    [C.border, "知らない"],
    [C.danger, "閉じ込め"],
  ],
};

export const RESPONDER_LEGEND: [string, string][] = [
  [C.warning, "救助隊"],
  [C.safe, "物資トラック"],
  [C.info, "巡回・伝令"],
];
