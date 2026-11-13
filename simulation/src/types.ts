// Shapes of what the Rust side returns (see core/src/frame.rs, spec.rs, sweep.rs).

export type MsgKind =
  | "tsunami_warning" | "official" | "trapped" | "fake_rescue" | "road_cut" | "road_open"
  | "need_water" | "need_food" | "surplus" | "roster" | "isolated" | "fire";

export type SiteKind = "shelter" | "hq" | "fire" | "hospital" | "building" | "open" | "depot" | "signal_spot" | "high_ground";

export interface SimParams {
  seed: number;
  adoption: number;
  adoption_students: number;
  elderly_adoption_factor: number;
  staff_adoption: number;
  population_scale: number;
  mesh_range_m: number;
  mesh_range_indoor_m: number;
  ios_share: number;
  discovery_s: number;
  discovery_ios_s: number;
  discovery_ios_pair_s: number;
  mesh_contacts_per_min: number;
  mesh_kbps: number;
  mesh_setup_s: number;
  carnav_adoption: number;
  carnav_range_m: number;
  carnav_connected: number;
  starlink_fixed: boolean;
  starlink_direct: boolean;
  starlink_direct_data: boolean;
  ships: boolean;
  mobile_bs: boolean;
  hq_appview: boolean;
  roster_aggregation: boolean;
  priority: boolean;
  starlink_capacity_factor: number;
  rescue_capacity_factor: number;
}

export interface ScenarioInfo {
  id: string;
  name: string;
  description: string;
  start: string;
  duration_h: number;
  defaults: SimParams;
}

export interface SiteInfo {
  id: string;
  name: string;
  kind: SiteKind;
  lat: number;
  lon: number;
  radius: number;
  floor: [number, number] | null;
  capacity: number;
  designated: boolean;
  peak_observed: number | null;
  starlink_from_h: number | null;
  note: string | null;
}

export interface RoadInfo {
  geom: [number, number][];
  name: string;
  a: [number, number];
  b: [number, number];
  cut_from_h: number | null;
  cut_until_h: number | null;
  foot_ok: boolean;
  cause: string;
}

export interface TowerInfo {
  lat: number;
  lon: number;
  carrier: number | null;
  radius: number;
  temporary: boolean;
  name: string;
  from_h: number;
  until_h: number | null;
}

export interface StaticInfo {
  id: string;
  name: string;
  description: string;
  start: string;
  duration_h: number;
  view: { center: [number, number]; zoom: number; max_zoom: number };
  projection: { lat0: number; lon0: number; kx: number; ky: number };
  params: SimParams;
  sites: SiteInfo[];
  settlements: { name: string; lat: number; lon: number; pop: number; radius: number }[];
  roads: RoadInfo[];
  towers: TowerInfo[];
  carriers: { id: string; name: string }[];
  zones: { id: string; name: string }[];
  msg_kinds: { id: MsgKind; label: string; priority: number }[];
  sources: { label: string; url: string }[];
  notes: string[];
  victims: number;
}

export interface Sample {
  t_h: number;
  population: number;
  anpi_ratio: number;
  anpi_hq_ratio: number;
  warned_ratio: number;
  phones_alive: number;
  online_ratio: number;
  towers_up: number;
  trapped_pending: number;
  trapped_known_ops: number;
  rescued: number;
  trapped_dead: number;
  shelters_open: number;
  shelters_short: number;
  shelters_unknown: number;
  evacuees: number;
  isolated_unknown: number;
  msgs: number;
  msgs_internet: number;
  msgs_ops: number;
  mesh_exchanges: number;
  gateways: number;
  sd_load: number;
  starlink_load: number;
}

export interface SimEvent {
  t: number;
  kind: string;
  label: string;
  severity: number;
  text: string;
  lat: number;
  lon: number;
  count: number;
}

export interface SiteState {
  present: number;
  peak: number;
  water_days: number;
  food_days: number;
  internet: boolean;
  starlink: boolean;
  known: boolean;
  short: boolean;
  need: boolean;
  surplus: boolean;
  board: number;
}

export interface MsgInfo {
  id: number;
  kind: MsgKind;
  label: string;
  text: string;
  created_h: number;
  internet_h: number | null;
  hq_h: number | null;
  ops_h: number | null;
  sns_h: number | null;
  mesh_h: number | null;
  reach: number;
  via_mesh: number;
  lat: number;
  lon: number;
  fake: boolean;
}

export interface Counters {
  mesh_exchanges: number;
  mesh_transfers: number;
  mesh_missed: number;
  nav_exchanges: number;
  nav_uploads: number;
  mesh_truncated: number;
  mesh_bytes: number;
  gateway_uploads: number;
  talks: number;
  calls_ok: number;
  calls_failed: number;
  calls_119_ok: number;
  calls_119_busy: number;
  sd_sent: number;
  sd_dropped: number;
  fake_dispatches: number;
  unknown_cut_hits: number;
  deliveries: number;
  rescued_by_team: number;
  rescued_by_neighbors: number;
  trapped_dead: number;
}

export interface Status {
  t: number;
  label: string;
  done: boolean;
  now: Sample;
  counters: Counters;
  towers_up: boolean[];
  roads: [boolean, boolean][];
  sites: SiteState[];
  events: SimEvent[];
  events_total: number;
  series: Sample[];
  series_total: number;
  messages: MsgInfo[];
  messages_total: number;
  sd_demand: number;
  sd_capacity: number;
  depot_water: number;
}

export type Focus = { type: "kind"; value: MsgKind } | { type: "message"; value: number } | null;

export type SweepTarget = "all" | "residents" | "students" | "carnav";

export interface SweepRequest {
  adoptions: number[];
  seeds: number;
  target: SweepTarget;
  hours: number | null;
}

export interface SweepPoint {
  adoption: number;
  mean: Record<string, number>;
  min: Record<string, number>;
  max: Record<string, number>;
}

/** One decoded frame: per agent x, y (meters) and flags (see Sim::frame). */
export interface Frame {
  t: number;
  n: number;
  xy: Float32Array;
  flags: Uint32Array;
}

export const FLAG = {
  phone: 1 << 0,
  app: 1 << 1,
  online: 1 << 2,
  anpi: 1 << 3,
  focus: 1 << 4,
  trapped: 1 << 5,
  dead: 1 << 6,
  shelter: 1 << 7,
  moving: 1 << 12,
  offmap: 1 << 13,
  tsunami: 1 << 14,
  meshed: 1 << 15,
  nav: 1 << 16,
} as const;

export const role = (f: number) => (f >> 8) & 0xf; // 0 resident, 1 student, 2 staff, 3 rescuer, 4 truck, 5 liaison
