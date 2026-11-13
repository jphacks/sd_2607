import "./style.css";
import { api } from "./api";
import { lineChart } from "./charts";
import { el, elapsed, hoursLabel, indicatorValue, INDICATORS, num, pct, wallClock } from "./format";
import { LEGEND, MapView, RESPONDER_LEGEND, type ColorMode } from "./map";
import type { Focus, MsgKind, Sample, ScenarioInfo, SimEvent, SimParams, StaticInfo, Status, SweepPoint, SweepTarget } from "./types";

const $ = <T extends HTMLElement>(sel: string) => document.querySelector(sel) as T;

const state = {
  scenarios: [] as ScenarioInfo[],
  scenario: "shelter",
  params: null as SimParams | null,
  info: null as StaticInfo | null,
  status: null as Status | null,
  running: false,
  speed: 60 as number | null,
  events: [] as SimEvent[],
  series: [] as Sample[],
  tab: "overview",
  focus: { type: "kind", value: "tsunami_warning" } as Focus,
  minSeverity: 1,
  sweep: null as SweepPoint[] | null,
  sweepChart: "trapped_to_hq_median_h",
  sweepBusy: false,
};

const map = new MapView($("#map"), $("#agents"));

// ---------------------------------------------------------------- top bar

const SPEEDS: [string, number | null][] = [
  ["×60", 60],
  ["×600", 600],
  ["×3600", 3600],
  ["最速", null],
];

function renderTopbar() {
  const chips = $("#scenario-chips");
  chips.replaceChildren(
    ...state.scenarios.map((s) => {
      const b = el("button", `chip${s.id === state.scenario ? " selected" : ""}`, s.name);
      b.onclick = () => selectScenario(s.id);
      return b;
    }),
  );
  const speeds = $("#speed-chips");
  speeds.replaceChildren(
    ...SPEEDS.map(([label, v]) => {
      const b = el("button", `chip small${v === state.speed ? " selected" : ""}`, label);
      b.title = v ? `1秒で${v >= 60 ? `${v / 60}時間` : `${v}分`}進める` : "計算できる限り速く";
      b.onclick = async () => {
        state.speed = v;
        await api.setSpeed(v);
        renderTopbar();
      };
      return b;
    }),
  );
  const play = $("#play");
  play.textContent = state.running ? "一時停止" : state.status?.done ? "終了" : "再生";
  play.classList.toggle("running", state.running);
}

$("#play").onclick = async () => {
  if (state.status?.done) return;
  state.running = !state.running;
  await api.setRunning(state.running);
  renderTopbar();
};
$("#step").onclick = async () => {
  await api.advance(60);
  poll(true);
};
$("#reset").onclick = () => restart();

function renderClock() {
  const s = state.status;
  const info = state.info;
  if (!s || !info) return;
  $("#clock-wall").textContent = wallClock(info.start, s.t);
  $("#clock-elapsed").textContent = `発災から ${elapsed(s.t)}`;
  const frac = Math.min(1, s.t / (info.duration_h * 60));
  ($("#progress-bar") as HTMLElement).style.width = `${frac * 100}%`;
}

// ---------------------------------------------------------------- parameters

type Field =
  | { kind: "slider"; key: keyof SimParams; label: string; min: number; max: number; step: number; fmt: (v: number) => string; hint?: string; only?: string[] }
  | { kind: "toggle"; key: keyof SimParams; label: string; hint?: string; only?: string[] }
  | { kind: "number"; key: keyof SimParams; label: string; hint?: string };

const FIELDS: { title: string; fields: Field[] }[] = [
  {
    title: "もしもアプリの導入率",
    fields: [
      { kind: "slider", key: "adoption", label: "住民", min: 0, max: 1, step: 0.05, fmt: pct, hint: "スマートフォンを持つ人のうち、プロトコル入りのアプリを入れている割合" },
      { kind: "slider", key: "adoption_students", label: "学生（とんペディア）", min: 0, max: 1, step: 0.05, fmt: pct, only: ["kawauchi"] },
      { kind: "slider", key: "staff_adoption", label: "自治体・避難所の職員", min: 0, max: 1, step: 0.05, fmt: pct },
      { kind: "slider", key: "elderly_adoption_factor", label: "高齢者の導入しやすさ", min: 0, max: 1, step: 0.05, fmt: (v) => `×${v.toFixed(2)}` },
    ],
  },
  {
    title: "カーナビ",
    fields: [
      { kind: "slider", key: "carnav_adoption", label: "対応カーナビの普及率", min: 0, max: 1, step: 0.05, fmt: pct, hint: "世帯の車のうち、もしもプロトコル対応のナビを積む割合。走行中と車中泊（アイドリング）の間だけ動く" },
      { kind: "slider", key: "carnav_range_m", label: "カーナビの到達距離", min: 20, max: 200, step: 10, fmt: (v) => `${v}m`, hint: "ルーフのアンテナで携帯より遠くまで届く" },
      { kind: "slider", key: "carnav_connected", label: "通信モジュール付きの割合", min: 0, max: 1, step: 0.05, fmt: pct, hint: "コネクテッドカー。基地局が生きていればアップロードできる" },
    ],
  },
  {
    title: "通信手段",
    fields: [
      { kind: "toggle", key: "starlink_fixed", label: "避難所へのStarlink配備", hint: "記録どおりの日時に避難所へ届く" },
      { kind: "slider", key: "starlink_capacity_factor", label: "Starlink 衛星セルの容量", min: 0.05, max: 2, step: 0.05, fmt: (v) => `×${v.toFixed(2)}`, hint: "下げると輻輳を再現できる" },
      { kind: "toggle", key: "ships", label: "船上基地局", only: ["noto"] },
      { kind: "toggle", key: "mobile_bs", label: "移動・可搬型基地局" },
      { kind: "toggle", key: "starlink_direct", label: "Starlink Direct（反実仮想）", hint: "au対応端末が屋外で衛星とSMS" },
      { kind: "toggle", key: "starlink_direct_data", label: "Starlink Directでアプリも通信", hint: "2025年8月以降の限定データ通信" },
    ],
  },
  {
    title: "プロトコルと運用",
    fields: [
      { kind: "toggle", key: "hq_appview", label: "本部がAppViewを見る", hint: "位置付き・署名付きの投稿を一覧で把握" },
      { kind: "toggle", key: "roster_aggregation", label: "避難所の名簿を集約して送る", hint: "職員が名簿を1件の投稿として中継" },
      { kind: "toggle", key: "priority", label: "優先度拡張", hint: "細い回線では要救助を先に送る" },
      { kind: "slider", key: "mesh_range_m", label: "端末間の到達距離（屋外）", min: 10, max: 100, step: 5, fmt: (v) => `${v}m` },
      { kind: "slider", key: "mesh_range_indoor_m", label: "端末間の到達距離（屋内）", min: 3, max: 40, step: 1, fmt: (v) => `${v}m`, hint: "家と家の間は壁でさらに6割に" },
      { kind: "slider", key: "mesh_kbps", label: "接触中の通信速度", min: 10, max: 2000, step: 10, fmt: (v) => `${v}kbps`, hint: "BLE GATT 約100kbps、L2CAP・Wi-Fiなら数Mbps" },
      { kind: "slider", key: "discovery_ios_pair_s", label: "iPhone同士が見つけ合うまで", min: 10, max: 1800, step: 10, fmt: (v) => `${Math.round(v)}秒`, hint: "バックグラウンドのiPhone同士はほぼ見つからない" },
      { kind: "slider", key: "ios_share", label: "iPhoneの割合", min: 0, max: 1, step: 0.05, fmt: pct },
    ],
  },
  {
    title: "規模",
    fields: [
      { kind: "slider", key: "population_scale", label: "人口倍率", min: 0.05, max: 1, step: 0.05, fmt: (v) => `×${v.toFixed(2)}`, hint: "能登全体は1/4で約3.4万人。大きくすると重くなる" },
      { kind: "slider", key: "rescue_capacity_factor", label: "救助隊の規模", min: 0.25, max: 4, step: 0.25, fmt: (v) => `×${v.toFixed(2)}` },
      { kind: "number", key: "seed", label: "乱数シード" },
    ],
  },
];

function renderParams() {
  const root = $("#params");
  const p = state.params!;
  root.replaceChildren();
  for (const group of FIELDS) {
    const sec = el("section", "param-group");
    sec.append(el("h3", "group-title", group.title));
    for (const f of group.fields) {
      if ("only" in f && f.only && !f.only.includes(state.scenario)) continue;
      const row = el("label", `field ${f.kind}`);
      const head = el("div", "field-head");
      head.append(el("span", "field-label", f.label));
      if (f.kind === "slider") {
        const val = el("span", "field-value", f.fmt(p[f.key] as number));
        head.append(val);
        const input = el("input") as HTMLInputElement;
        input.type = "range";
        input.min = String(f.min);
        input.max = String(f.max);
        input.step = String(f.step);
        input.value = String(p[f.key]);
        input.oninput = () => {
          (p[f.key] as number) = Number(input.value);
          val.textContent = f.fmt(Number(input.value));
          markDirty();
        };
        row.append(head, input);
      } else if (f.kind === "toggle") {
        const input = el("input") as HTMLInputElement;
        input.type = "checkbox";
        input.className = "switch";
        input.checked = p[f.key] as boolean;
        input.onchange = () => {
          (p[f.key] as boolean) = input.checked;
          markDirty();
        };
        head.append(input);
        row.append(head);
      } else {
        const input = el("input") as HTMLInputElement;
        input.type = "number";
        input.className = "num-input";
        input.value = String(p[f.key]);
        input.onchange = () => {
          (p[f.key] as number) = Math.max(1, Math.floor(Number(input.value) || 1));
          markDirty();
        };
        head.append(input);
        row.append(head);
      }
      if (f.hint) row.append(el("div", "field-hint", f.hint));
      sec.append(row);
    }
    root.append(sec);
  }
}

function markDirty() {
  $("#apply").classList.add("attention");
  $("#apply").textContent = "この条件で開始し直す";
}

$("#apply").onclick = () => restart();

// ---------------------------------------------------------------- scenario lifecycle

async function selectScenario(id: string) {
  state.scenario = id;
  const s = state.scenarios.find((x) => x.id === id)!;
  state.params = structuredClone(s.defaults);
  state.sweep = null;
  renderParams();
  await restart();
}

async function restart() {
  state.running = false;
  await api.setRunning(false);
  state.info = await api.createSim(state.scenario, state.params!);
  state.events = [];
  state.series = [];
  state.status = null;
  map.load(state.info);
  await api.setFocus(state.focus);
  $("#apply").classList.remove("attention");
  $("#apply").textContent = "最初から";
  renderTopbar();
  renderTab();
  await poll(true);
}

// ---------------------------------------------------------------- polling

let polling = false;
let lastStatusAt = 0;

async function poll(forceStatus = false) {
  if (polling || !state.info) return;
  polling = true;
  try {
    const f = await api.frame();
    if (f) map.setFrame(f);
    const now = performance.now();
    if (forceStatus || now - lastStatusAt > 400) {
      lastStatusAt = now;
      const s = await api.status(state.events.length, state.series.length);
      if (s) {
        state.status = s;
        state.events.push(...s.events);
        state.series.push(...s.series);
        if (s.done && state.running) {
          state.running = false;
          renderTopbar();
        }
        map.update(s);
        renderClock();
        renderTab();
      }
    }
  } finally {
    polling = false;
  }
}

// Frames are larger with more people; poll less often so the IPC keeps up.
setInterval(() => poll(), 150);

// ---------------------------------------------------------------- map controls

function renderMapControls() {
  const modes: [ColorMode, string][] = [
    ["comm", "通信"],
    ["anpi", "安否"],
    ["focus", "情報"],
  ];
  const box = $("#map-modes");
  box.replaceChildren(
    ...modes.map(([m, label]) => {
      const b = el("button", `chip small${map.mode === m ? " selected" : ""}`, label);
      b.onclick = () => {
        map.mode = m;
        renderMapControls();
        map.draw();
      };
      return b;
    }),
  );
  const towers = el("button", `chip small${towersOn ? " selected" : ""}`, "基地局");
  towers.onclick = () => {
    towersOn = !towersOn;
    map.showTowers(towersOn);
    renderMapControls();
  };
  box.append(towers);
  const legend = $("#legend");
  legend.replaceChildren(
    ...[...LEGEND[map.mode], ...RESPONDER_LEGEND].map(([c, label], i) => {
      const row = el("div", "legend-row");
      const sw = el("span", i >= LEGEND[map.mode].length ? "swatch square" : "swatch");
      sw.style.background = c;
      row.append(sw, el("span", "", label));
      return row;
    }),
  );
  if (map.mode === "focus") legend.prepend(el("div", "legend-title", focusLabel()));
}

let towersOn = false;

function focusLabel(): string {
  const f = state.focus;
  if (!f) return "";
  if (f.type === "kind") return state.info?.msg_kinds.find((k) => k.id === f.value)?.label ?? "";
  const m = state.status?.messages.find((x) => x.id === f.value);
  return m ? m.text : `情報 #${f.value}`;
}

async function setFocus(f: Focus) {
  state.focus = f;
  map.mode = "focus";
  await api.setFocus(f);
  renderMapControls();
  poll(true);
}

map.onSiteClick = (i) => {
  const s = state.info?.sites[i];
  if (s) map.flyTo(s.lat, s.lon);
};

// ---------------------------------------------------------------- right panel

const TABS: [string, string][] = [
  ["overview", "概況"],
  ["events", "重大イベント"],
  ["flow", "情報の流れ"],
  ["sweep", "比較実験"],
  ["about", "出典"],
];

function renderTabs() {
  $("#tabs").replaceChildren(
    ...TABS.map(([id, label]) => {
      const b = el("button", `tab${state.tab === id ? " selected" : ""}`, label);
      if (id === "events" && state.events.some((e) => e.severity >= 3)) b.append(el("span", "badge", String(state.events.filter((e) => e.severity >= 3).length)));
      b.onclick = () => {
        state.tab = id;
        renderTabs();
        renderTab(true);
      };
      return b;
    }),
  );
}

let lastTabRender = 0;

function renderTab(force = false) {
  // Interactive tabs re-render only on demand so inputs keep focus.
  if (!force && (state.tab === "sweep" || state.tab === "about")) return;
  const now = performance.now();
  if (!force && now - lastTabRender < 350) return;
  lastTabRender = now;
  renderTabs();
  const body = $("#tab-body");
  const scroll = body.scrollTop;
  body.replaceChildren();
  switch (state.tab) {
    case "overview":
      renderOverview(body);
      break;
    case "events":
      renderEvents(body);
      break;
    case "flow":
      renderFlow(body);
      break;
    case "sweep":
      renderSweep(body);
      break;
    case "about":
      renderAbout(body);
      break;
  }
  body.scrollTop = scroll;
}

function kpi(label: string, value: string, sub?: string, tone?: string) {
  const card = el("div", `kpi${tone ? ` ${tone}` : ""}`);
  card.append(el("div", "kpi-label", label), el("div", "kpi-value", value));
  if (sub) card.append(el("div", "kpi-sub", sub));
  return card;
}

function renderOverview(root: HTMLElement) {
  const s = state.status;
  if (!s) return;
  const n = s.now;
  const grid = el("div", "kpi-grid");
  grid.append(
    kpi("家族に安否が届いた", pct(n.anpi_ratio), "家族が域外にいる人のうち"),
    kpi("本部が安否を把握", pct(n.anpi_hq_ratio), state.info?.id === "kawauchi" ? "大学の安否確認" : "名簿・来訪・投稿"),
    kpi("津波警報を知っている", pct(n.warned_ratio), "沿岸の住民のうち"),
    kpi("今つながる端末", pct(n.online_ratio), `電池が残る端末 ${pct(n.phones_alive)}`),
    kpi("基地局", pct(n.towers_up), "稼働している割合"),
    kpi("要救助", `${n.trapped_pending}人`, `本部が把握 ${n.trapped_known_ops} ・ 救出 ${n.rescued} ・ 死亡 ${n.trapped_dead}`, n.trapped_dead > 0 ? "danger" : undefined),
    kpi("避難所", `${n.shelters_open}か所`, `避難者 ${num(n.evacuees)}人 ・ 不足 ${n.shelters_short} ・ 本部未把握 ${n.shelters_unknown}`, n.shelters_short > 0 ? "warning" : undefined),
    kpi("孤立集落（未把握）", `${n.isolated_unknown}`, "道路が断たれ、本部が知らない集落"),
    kpi("メッシュ交換", num(s.counters.mesh_exchanges), `ゲートウェイ ${n.gateways}台 ・ アップロード ${num(s.counters.gateway_uploads)}件`, "accent"),
    kpi("カーナビ経由の交換", num(s.counters.nav_exchanges), `カーナビからのアップロード ${num(s.counters.nav_uploads)}件`, "accent"),
    kpi("すれ違いで逃した接触", num(s.counters.mesh_missed), `時間切れで途中まで ${num(s.counters.mesh_truncated)} ・ 転送 ${(s.counters.mesh_bytes / 1e6).toFixed(1)}MB`),
    kpi("119番", `${num(s.counters.calls_119_ok)}件`, `話し中 ${num(s.counters.calls_119_busy)} ・ 通話失敗 ${num(s.counters.calls_failed)}`),
    kpi("Starlink 負荷", pct(n.starlink_load), `Starlink Direct ${s.sd_capacity > 0 ? pct(s.sd_demand / s.sd_capacity) : "未使用"}`),
    kpi("情報", `${num(n.msgs)}件`, `ネット上 ${num(n.msgs_internet)} ・ 県が把握 ${num(n.msgs_ops)}`),
  );
  root.append(grid);
  const xs = state.series.map((x) => x.t_h);
  const c1 = el("canvas", "chart");
  root.append(el("h4", "chart-title", "安否と通信"), legendRow([["#34C759", "家族に安否"], ["#F2F4F7", "本部が把握"], ["#4DA3FF", "つながる端末"], ["#6B7480", "基地局"]]), c1);
  const c2 = el("canvas", "chart");
  root.append(el("h4", "chart-title", "要救助者"), legendRow([["#FF8A00", "救助待ち"], ["#FFB900", "本部が把握"], ["#34C759", "救出"], ["#FF3B30", "死亡"]]), c2);
  requestAnimationFrame(() => {
    lineChart(
      c1,
      xs,
      [
        { label: "安否", color: "#34C759", values: state.series.map((x) => x.anpi_ratio) },
        { label: "本部", color: "#F2F4F7", values: state.series.map((x) => x.anpi_hq_ratio) },
        { label: "通信", color: "#4DA3FF", values: state.series.map((x) => x.online_ratio) },
        { label: "基地局", color: "#6B7480", values: state.series.map((x) => x.towers_up) },
      ],
      { max: 1 },
    );
    lineChart(c2, xs, [
      { label: "待ち", color: "#FF8A00", values: state.series.map((x) => x.trapped_pending) },
      { label: "把握", color: "#FFB900", values: state.series.map((x) => x.trapped_known_ops) },
      { label: "救出", color: "#34C759", values: state.series.map((x) => x.rescued) },
      { label: "死亡", color: "#FF3B30", values: state.series.map((x) => x.trapped_dead) },
    ]);
  });
}

function legendRow(items: [string, string][]) {
  const row = el("div", "legend-inline");
  for (const [c, label] of items) {
    const sw = el("span", "swatch line");
    sw.style.background = c;
    row.append(sw, el("span", "", label));
  }
  return row;
}

function renderEvents(root: HTMLElement) {
  const filter = el("div", "chip-row");
  for (const [lv, label] of [
    [1, "すべて"],
    [2, "重要以上"],
    [3, "重大のみ"],
  ] as [number, string][]) {
    const b = el("button", `chip small${state.minSeverity === lv ? " selected" : ""}`, label);
    b.onclick = () => {
      state.minSeverity = lv;
      renderTab(true);
    };
    filter.append(b);
  }
  root.append(filter);
  const counts = new Map<string, number>();
  for (const e of state.events) counts.set(e.label, (counts.get(e.label) ?? 0) + 1);
  const summary = el("div", "event-summary");
  for (const [label, c] of [...counts.entries()].sort((a, b) => b[1] - a[1])) {
    summary.append(el("span", "pill", `${label} ${c}`));
  }
  root.append(summary);
  const list = el("div", "event-list");
  const shown = state.events.filter((e) => e.severity >= state.minSeverity).slice(-300).reverse();
  if (shown.length === 0) list.append(el("div", "empty", "まだ記録はありません"));
  for (const e of shown) {
    const row = el("button", `event sev${e.severity}`);
    row.append(el("div", "event-time", elapsed(e.t)), el("div", "event-label", e.label), el("div", "event-text", e.text));
    row.onclick = () => map.flyTo(e.lat, e.lon);
    list.append(row);
  }
  root.append(list);
}

function renderFlow(root: HTMLElement) {
  root.append(el("p", "explain", "地図の「情報」表示で、選んだ情報を誰が知っているかを色分けします。"));
  const chips = el("div", "chip-row wrap");
  for (const k of state.info?.msg_kinds ?? []) {
    const sel = state.focus?.type === "kind" && state.focus.value === k.id;
    const b = el("button", `chip small${sel ? " selected" : ""}`, k.label);
    b.onclick = () => setFocus({ type: "kind", value: k.id as MsgKind });
    chips.append(b);
  }
  root.append(chips);
  const list = el("div", "msg-list");
  for (const m of state.status?.messages ?? []) {
    const sel = state.focus?.type === "message" && state.focus.value === m.id;
    const row = el("button", `msg${sel ? " selected" : ""}${m.fake ? " fake" : ""}`);
    const head = el("div", "msg-head");
    head.append(el("span", `kind k-${m.kind}`, m.label), el("span", "msg-time", `${hoursLabel(m.created_h)}後`));
    const route = el("div", "msg-route");
    const step = (label: string, h: number | null) => {
      const s = el("span", `hop${h != null ? " done" : ""}`, h != null ? `${label} +${hoursLabel(Math.max(0, h - m.created_h))}` : label);
      route.append(s);
    };
    step("メッシュ", m.mesh_h);
    step("ネット", m.internet_h);
    step("SNS", m.sns_h);
    step("市町", m.hq_h);
    step("県・救助", m.ops_h);
    row.append(head, el("div", "msg-text", m.text), route, el("div", "msg-reach", `${num(m.reach)}人が知る（うちメッシュ経由 ${num(m.via_mesh)}）`));
    row.onclick = () => {
      setFocus({ type: "message", value: m.id });
      map.flyTo(m.lat, m.lon);
    };
    list.append(row);
  }
  root.append(list);
}

const SWEEP_LEVELS = [0, 0.1, 0.3, 0.5, 0.7, 0.9];
const sweepForm = { levels: new Set([0, 0.3, 0.6, 0.9].map(String)), seeds: 2, target: "all" as SweepTarget, hours: "" };

function renderSweep(root: HTMLElement) {
  root.append(
    el(
      "p",
      "explain",
      "左の条件のまま、導入率だけを変えて最後まで計算し、結果を比べます。同じシードでは人の配置や被害が同じになり、導入率の違いだけを比べられます。",
    ),
  );
  const form = el("div", "sweep-form");
  const levels = el("div", "chip-row wrap");
  for (const v of [...SWEEP_LEVELS, 0.6, 1]) {
    const key = String(v);
    const b = el("button", `chip small${sweepForm.levels.has(key) ? " selected" : ""}`, pct(v));
    b.onclick = () => {
      if (sweepForm.levels.has(key)) sweepForm.levels.delete(key);
      else sweepForm.levels.add(key);
      renderTab(true);
    };
    levels.append(b);
  }
  form.append(el("div", "form-label", "導入率"), levels);
  const target = el("div", "chip-row");
  const targets: [SweepTarget, string][] =
    state.scenario === "kawauchi"
      ? [
          ["students", "学生だけ変える"],
          ["residents", "教職員だけ"],
          ["all", "全員"],
        ]
      : [
          ["all", "スマホ（全員）"],
          ["residents", "スマホ（住民と職員）"],
          ["carnav", "カーナビだけ"],
        ];
  if (!targets.some(([t]) => t === sweepForm.target)) sweepForm.target = targets[0][0];
  for (const [t, label] of targets) {
    const b = el("button", `chip small${sweepForm.target === t ? " selected" : ""}`, label);
    b.onclick = () => {
      sweepForm.target = t;
      renderTab(true);
    };
    target.append(b);
  }
  form.append(el("div", "form-label", "変えるもの"), target);
  const row = el("div", "form-row");
  const seeds = el("input") as HTMLInputElement;
  seeds.type = "number";
  seeds.min = "1";
  seeds.max = "20";
  seeds.value = String(sweepForm.seeds);
  seeds.className = "num-input";
  seeds.onchange = () => (sweepForm.seeds = Math.max(1, Math.min(20, Number(seeds.value) || 1)));
  const hours = el("input") as HTMLInputElement;
  hours.type = "number";
  hours.placeholder = `${state.info?.duration_h ?? ""}`;
  hours.value = sweepForm.hours;
  hours.className = "num-input";
  hours.onchange = () => (sweepForm.hours = hours.value);
  row.append(el("span", "form-label", "シード数"), seeds, el("span", "form-label", "計算する時間(h)"), hours);
  form.append(row);
  const run = el("button", "primary-btn", state.sweepBusy ? "計算中…" : "比較を実行");
  run.disabled = state.sweepBusy;
  run.onclick = runSweep;
  const prog = el("div", "progress");
  prog.id = "sweep-progress";
  prog.append(el("div", "progress-fill"));
  form.append(run, prog);
  root.append(form);
  if (state.sweep) root.append(sweepResults(state.sweep));
}

async function runSweep() {
  if (!state.params || state.sweepBusy) return;
  const adoptions = [...sweepForm.levels].map(Number).sort((a, b) => a - b);
  if (adoptions.length === 0) return;
  state.sweepBusy = true;
  renderTab(true);
  const unlisten = await api.onSweepProgress(({ done, total }) => {
    const fill = document.querySelector("#sweep-progress .progress-fill") as HTMLElement | null;
    if (fill) fill.style.width = `${(done / total) * 100}%`;
  });
  try {
    const hours = sweepForm.hours ? Number(sweepForm.hours) : null;
    state.sweep = await api.sweep(state.scenario, state.params, { adoptions, seeds: sweepForm.seeds, target: sweepForm.target, hours });
  } finally {
    unlisten();
    state.sweepBusy = false;
    renderTab(true);
  }
}

function sweepResults(points: SweepPoint[]) {
  const wrap = el("div", "sweep-results");
  const table = el("table", "sweep-table");
  const head = el("tr");
  head.append(el("th", "", "指標"));
  for (const p of points) head.append(el("th", "num", pct(p.adoption)));
  table.append(head);
  for (const ind of INDICATORS) {
    if (!points.some((p) => ind.key in p.mean)) continue;
    const tr = el("tr", state.sweepChart === ind.key ? "selected" : "");
    tr.append(el("td", "", ind.label));
    const base = points[0].mean[ind.key];
    for (const p of points) {
      const v = p.mean[ind.key];
      const td = el("td", "num", indicatorValue(ind.unit, v));
      if (base != null && v != null && isFinite(base) && isFinite(v) && p !== points[0] && Math.abs(v - base) > 1e-9) {
        const better = ind.better === "up" ? v > base : v < base;
        td.classList.add(better ? "better" : "worse");
      }
      const lo = p.min[ind.key];
      const hi = p.max[ind.key];
      if (lo != null && hi != null && lo !== hi) td.title = `範囲 ${indicatorValue(ind.unit, lo)} 〜 ${indicatorValue(ind.unit, hi)}`;
      tr.append(td);
    }
    tr.onclick = () => {
      state.sweepChart = ind.key;
      renderTab(true);
    };
    table.append(tr);
  }
  const ind = INDICATORS.find((i) => i.key === state.sweepChart) ?? INDICATORS[0];
  const chart = el("canvas", "chart");
  wrap.append(el("h4", "chart-title", `${ind.label}（行を選ぶと切り替え）`), chart, table);
  wrap.append(el("p", "explain small", "緑は導入率0%より良く、赤は悪い。セルに乗せると、シード間の最小〜最大が出ます。"));
  requestAnimationFrame(() =>
    lineChart(
      chart,
      points.map((p) => p.adoption * 100),
      [
        { label: "max", color: "#3A4049", values: points.map((p) => p.max[ind.key]) },
        { label: "min", color: "#3A4049", values: points.map((p) => p.min[ind.key]) },
        { label: ind.label, color: "#FFB900", values: points.map((p) => p.mean[ind.key]) },
      ],
      { max: ind.unit === "%" ? 1 : undefined, xLabel: (x) => `${Math.round(x)}%` },
    ),
  );
  return wrap;
}

function renderAbout(root: HTMLElement) {
  const info = state.info;
  if (!info) return;
  root.append(el("h3", "about-title", info.name), el("p", "explain", info.description));
  root.append(el("h4", "chart-title", "モデル化の前提と推定"));
  const ul = el("ul", "notes");
  for (const n of info.notes) ul.append(el("li", "", n));
  root.append(ul);
  root.append(el("h4", "chart-title", "出典"));
  const src = el("ul", "sources");
  for (const s of info.sources) {
    const li = el("li");
    li.append(el("div", "", s.label), el("div", "url", s.url));
    src.append(li);
  }
  root.append(src);
  root.append(
    el(
      "p",
      "explain small",
      "主な数値は内閣府・石川県・総務省・各通信事業者の公表資料に基づきます。公表されていない値（輻輳時の通話成功率、地区別人口、船上基地局の到達範囲など）は推定で、比較実験で感度を確かめてください。",
    ),
  );
}

// ---------------------------------------------------------------- start

async function main() {
  state.scenarios = await api.listScenarios();
  await api.setSpeed(state.speed);
  renderMapControls();
  await selectScenario(state.scenario);
}

main().catch((e) => {
  document.body.append(el("pre", "fatal", String(e)));
});
