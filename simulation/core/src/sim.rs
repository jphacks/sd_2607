//! The simulation state, its construction from a scenario, and helpers shared by the step phases.

use std::cmp::Ordering;
use std::collections::{BinaryHeap, HashMap};

use rand::Rng;
use rand::SeedableRng;
use rand_chacha::ChaCha8Rng;
use serde::Serialize;

use crate::model::*;
use crate::spec::{index_by_id, ScenarioSpec, SimParams, SiteKind};
use crate::util::{BitSet, Projection, Vec2};

pub const INF: f32 = f32::INFINITY;
const NODE_CELL: f32 = 2000.0;

/// Household files built by `core/scenarios/data/build_geo.py`.
fn household_csv(name: &str) -> &'static str {
    match name {
        "noto_households.csv" => include_str!("../scenarios/data/noto_households.csv"),
        "shelter_households.csv" => include_str!("../scenarios/data/shelter_households.csv"),
        _ => panic!("unknown household file {name}"),
    }
}

/// Cumulative counters reported in the summary.
#[derive(Clone, Debug, Default, Serialize)]
pub struct Counters {
    pub mesh_exchanges: u64,
    pub mesh_transfers: u64,
    /// Encounters in range that ended before the phones discovered each other or finished setup.
    pub mesh_missed: u64,
    /// Exchanges cut short by contact time, and bytes moved.
    pub mesh_truncated: u64,
    pub mesh_bytes: u64,
    /// Exchanges in which a car navigation unit took part.
    pub nav_exchanges: u64,
    pub nav_uploads: u64,
    pub gateway_uploads: u64,
    pub talks: u64,
    pub calls_ok: u64,
    pub calls_failed: u64,
    pub calls_119_ok: u64,
    pub calls_119_busy: u64,
    pub sd_sent: u64,
    pub sd_dropped: u64,
    pub fake_dispatches: u32,
    pub unknown_cut_hits: u32,
    pub wasted_vehicle_min: f64,
    pub concentration_events: u32,
    pub deliveries: u32,
    pub rescued_by_team: u32,
    pub rescued_by_neighbors: u32,
    pub trapped_dead: u32,
    pub found_by_search: u32,
    pub tsunami_exposed: u32,
    pub tsunami_exposed_unwarned: u32,
    pub tsunami_population: u32,
}

/// One row of the hourly time series.
#[derive(Clone, Debug, Default, Serialize)]
pub struct Sample {
    pub t_h: f32,
    pub population: u32,
    pub anpi_ratio: f32,
    pub anpi_hq_ratio: f32,
    pub warned_ratio: f32,
    pub phones_alive: f32,
    pub online_ratio: f32,
    pub towers_up: f32,
    pub trapped_pending: u32,
    pub trapped_known_ops: u32,
    pub rescued: u32,
    pub trapped_dead: u32,
    pub shelters_open: u32,
    pub shelters_short: u32,
    pub shelters_unknown: u32,
    pub evacuees: u32,
    pub isolated_unknown: u32,
    pub isolated_settlements: u32,
    pub isolated_people: u32,
    pub msgs: u32,
    pub msgs_internet: u32,
    pub msgs_ops: u32,
    pub mesh_exchanges: u64,
    pub gateways: u32,
    pub sd_load: f32,
    pub starlink_load: f32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Via {
    Origin,
    Talk,
    Board,
    Mesh,
    Internet,
    Broadcast,
    Phone,
}

pub struct Sim {
    pub spec: ScenarioSpec,
    pub params: SimParams,
    pub proj: Projection,
    pub rng: ChaCha8Rng,
    pub t: f32,
    pub end: f32,
    pub dt: f32,
    pub agents: Vec<Agent>,
    pub knows: Vec<BitSet>,
    /// 安否 posts (agent ids) an app carries that are not yet uploaded.
    pub carry: Vec<Vec<u32>>,
    pub anpi_uploaded: Vec<bool>,
    /// Household of each resident: (settlement, index), or NONE.
    pub household_of: Vec<(u32, u32)>,
    pub sites: Vec<Site>,
    pub settlements: Vec<Settlement>,
    pub zones: Vec<Zone>,
    pub towers: Vec<Tower>,
    pub tower_grid: HashMap<(i32, i32), Vec<u32>>,
    pub nodes: Vec<Vec2>,
    pub node_grid: HashMap<(i32, i32), Vec<u32>>,
    pub edges: Vec<RoadEdge>,
    pub adj: Vec<Vec<(u32, u32)>>,
    pub exits: Vec<Exit>,
    pub msgs: Vec<Message>,
    pub kind_mask: Vec<BitSet>,
    pub mesh_mask: BitSet,
    pub internet: BitSet,
    pub sns: BitSet,
    pub talk_mask: BitSet,
    /// Talk-eligible messages by priority level (0..=5).
    pub prio_masks: Vec<BitSet>,
    /// First message id people still talk about (news older than two days is not passed on).
    pub talk_from: u32,
    pub ops: BitSet,
    pub fakes_dispatched: Vec<u32>,
    /// Local clock hour at the quake.
    pub start_hour: f32,
    pub sd_prev_demand: f64,
    pub liaisons_spawned: bool,
    pub fires_started: usize,
    pub next_hourly: f32,
    /// Share of households without power in each zone, this step.
    pub power_out: Vec<f32>,
    /// Door-to-door leaders: houses visited and the people found safe, not yet reported.
    pub rounds: HashMap<u32, (u32, Vec<u32>)>,
    pub victims: Vec<Victim>,
    pub teams: Vec<Team>,
    pub depot: u32,
    pub depot_water: f64,
    pub depot_food: f64,
    pub next_plan: f32,
    pub events: Vec<SimEvent>,
    pub series: Vec<Sample>,
    pub counters: Counters,
    pub broadcast_msgs: Vec<u32>,
    pub carrier_roam: Vec<u8>,
    pub sd_capacity: f64,
    pub sd_demand: f64,
    pub starlink_load: f32,
    pub next_sample: f32,
    pub focus: Option<Focus>,
    pub teams_spawned: u32,
    pub trucks_spawned: u32,
    pub helis_spawned: u32,
    pub congestion_flag: [bool; 2],
    pub anpi_flagged: bool,
    pub scratch: Vec<u32>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Deserialize, Serialize)]
#[serde(tag = "type", content = "value", rename_all = "snake_case")]
pub enum Focus {
    Kind(MsgKind),
    Message(u32),
}

/// Local hour of day from an ISO time such as `2024-01-01T16:10:00+09:00`.
fn start_hour(iso: &str) -> f32 {
    iso.split('T')
        .nth(1)
        .and_then(|t| {
            let mut it = t.split(':');
            let h: f32 = it.next()?.parse().ok()?;
            let m: f32 = it.next()?.get(0..2)?.parse().ok()?;
            Some(h + m / 60.0)
        })
        .unwrap_or(12.0)
}

/// Inverse of a decreasing survival curve: the hour at which survival drops to `u`.
fn survival_time(curve: &crate::util::Curve, u: f64) -> f64 {
    let pts = &curve.0;
    for w in pts.windows(2) {
        let (a, b) = (w[0], w[1]);
        if u >= b[1] {
            if (a[1] - b[1]).abs() < 1e-12 {
                return a[0];
            }
            return a[0] + (b[0] - a[0]) * (a[1] - u) / (a[1] - b[1]);
        }
    }
    f64::INFINITY
}

impl Sim {
    pub fn new(spec: ScenarioSpec, params: SimParams) -> Self {
        let proj = Projection::new(spec.origin[0], spec.origin[1]);
        let mut rng = ChaCha8Rng::seed_from_u64(params.seed);
        let zone_ix = index_by_id(&spec.zones, |z| &z.id);
        let zone_of = |id: &str| *zone_ix.get(id).unwrap_or_else(|| panic!("unknown zone {id}")) as u16;

        // Road graph.
        let node_ix = index_by_id(&spec.road_nodes, |n| &n.id);
        let nodes: Vec<Vec2> = spec.road_nodes.iter().map(|n| proj.to_xy(n.pos)).collect();
        let mut adj = vec![Vec::new(); nodes.len()];
        let mut edges = Vec::new();
        for r in &spec.roads {
            let a = *node_ix.get(&r.a).unwrap_or_else(|| panic!("unknown road node {}", r.a)) as u32;
            let b = *node_ix.get(&r.b).unwrap_or_else(|| panic!("unknown road node {}", r.b)) as u32;
            let len = r.length_m.map(|l| l as f32).unwrap_or_else(|| nodes[a as usize].dist(nodes[b as usize]) * r.winding as f32);
            let (cut_from, cut_until, foot_ok, cause) = match &r.cut {
                Some(c) => ((c.from_h * 60.0) as f32, (c.until_h * 60.0) as f32, c.foot_ok, c.cause.clone()),
                None => (INF, INF, true, String::new()),
            };
            let e = edges.len() as u32;
            edges.push(RoadEdge {
                a,
                b,
                name: r.name.clone(),
                len,
                speed: (r.speed_kmh * 1000.0 / 60.0) as f32,
                cut_from,
                cut_until,
                foot_ok,
                cause,
                cut_msg: NONE,
                open_msg: NONE,
            });
            adj[a as usize].push((e, b));
            adj[b as usize].push((e, a));
        }
        let nearest_node = |p: Vec2| -> u32 {
            nodes
                .iter()
                .enumerate()
                .min_by(|a, b| a.1.dist2(p).partial_cmp(&b.1.dist2(p)).unwrap_or(Ordering::Equal))
                .map(|(i, _)| i as u32)
                .unwrap_or(NONE)
        };
        let node_or_nearest = |id: &Option<String>, p: Vec2| -> u32 {
            match id {
                Some(id) => *node_ix.get(id).unwrap_or_else(|| panic!("unknown road node {id}")) as u32,
                None => nearest_node(p),
            }
        };

        let h = |x: Option<f64>| x.map(|v| (v * 60.0) as f32).unwrap_or(INF);
        let mut sites: Vec<Site> = spec
            .sites
            .iter()
            .map(|s| {
                let pos = proj.to_xy(s.pos);
                Site {
                    id: s.id.clone(),
                    name: s.name.clone(),
                    kind: s.kind,
                    zone: zone_of(&s.zone),
                    pos,
                    radius: s.radius_m as f32,
                    floor: s.floor.map(|f| [f[0] as f32, f[1] as f32]),
                    capacity: s.capacity,
                    designated: s.designated,
                    known_initially: s.known_to_hq.unwrap_or(s.designated || s.kind != SiteKind::Shelter),
                    water: s.stock_water_l,
                    food: s.stock_food,
                    power_from: h(s.power_from_h),
                    starlink_from: if params.starlink_fixed { h(s.starlink_from_h) } else { INF },
                    fixed_from: h(s.fixed_from_h),
                    satphone_from: h(s.satphone_from_h),
                    open_at: (s.open_h * 60.0) as f32,
                    node: nearest_node(pos),
                    peak_observed: s.peak_observed,
                    present: 0,
                    peak: 0,
                    staff: Vec::new(),
                    board: BitSet::default(),
                    last_roster: -INF,
                    need_water: NONE,
                    need_food: NONE,
                    surplus: NONE,
                    water_short_since: INF,
                    food_short_since: INF,
                    short_hours: 0.0,
                    delivered_water: 0.0,
                    deliveries: 0,
                    next_report: 0.0,
                    starlink_users: 0,
                    reported_short: false,
                    food_reported: false,
                    ops_known: false,
                    hq_known: false,
                    ops_pop: -1.0,
                    ops_need: false,
                    last_delivery: 0.0,
                    last_concentration: -INF,
                    starlink_ok: 1.0,
                    unknown_flagged: false,
                    people: Vec::new(),
                    reported_safe: Vec::new(),
                }
            })
            .collect();
        let site_ix = index_by_id(&spec.sites, |s| &s.id);

        let mut zones: Vec<Zone> = spec
            .zones
            .iter()
            .map(|z| Zone {
                id: z.id.clone(),
                name: z.name.clone(),
                hq: NONE,
                knows: BitSet::default(),
                calls_budget: 0.0,
                musen_ok: z.musen_ok,
            })
            .collect();
        for (i, s) in sites.iter().enumerate() {
            if s.kind == SiteKind::Hq && zones[s.zone as usize].hq == NONE {
                zones[s.zone as usize].hq = i as u32;
            }
        }

        let settlements: Vec<Settlement> = spec
            .settlements
            .iter()
            .map(|s| {
                let pos = proj.to_xy(s.pos);
                let zone = zone_of(&s.zone);
                let mut shelters: Vec<u32> = s
                    .shelters
                    .iter()
                    .map(|id| *site_ix.get(id).unwrap_or_else(|| panic!("unknown shelter {id}")) as u32)
                    .collect();
                if shelters.is_empty() {
                    let mut c: Vec<(f32, u32)> = sites
                        .iter()
                        .enumerate()
                        .filter(|(_, x)| x.kind == SiteKind::Shelter && x.zone == zone)
                        .map(|(i, x)| (x.pos.dist(pos), i as u32))
                        .collect();
                    c.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(Ordering::Equal));
                    shelters = c.into_iter().take(4).map(|x| x.1).collect();
                }
                let high_ground = sites
                    .iter()
                    .filter(|x| x.kind == SiteKind::HighGround && x.zone == zone)
                    .min_by(|a, b| a.pos.dist2(pos).partial_cmp(&b.pos.dist2(pos)).unwrap_or(Ordering::Equal))
                    .map(|x| x.pos)
                    .unwrap_or_else(|| shelters.first().map(|&i| sites[i as usize].pos).unwrap_or(pos));
                Settlement {
                    households: Vec::new(),
                    id: s.id.clone(),
                    name: s.name.clone(),
                    zone,
                    pos,
                    radius: s.radius_m as f32,
                    pop: s.pop,
                    node: node_or_nearest(&s.node, pos),
                    shelters,
                    tsunami_arrival: h(s.tsunami_arrival_h),
                    tsunami_done: false,
                    high_ground,
                    isolated_since: INF,
                    isolated_msg: NONE,
                    isolated_reported: false,
                    search_effort: 0.0,
                    collapse: s.collapse,
                }
            })
            .collect();

        let exits = spec
            .exits
            .iter()
            .map(|e| Exit {
                id: e.id.clone(),
                name: e.name.clone(),
                node: *node_ix.get(&e.node).unwrap_or_else(|| panic!("unknown exit node {}", e.node)) as u32,
                travel_h: e.travel_h.clone(),
                internet: e.internet,
            })
            .collect();

        // Towers: per carrier around settlements and occupied sites, plus explicit extras.
        let carrier_ix = index_by_id(&spec.carriers, |c| &c.id);
        let carrier_roam: Vec<u8> = spec
            .carriers
            .iter()
            .enumerate()
            .map(|(i, c)| c.roams_on.as_ref().and_then(|r| carrier_ix.get(r)).map(|&x| x as u8).unwrap_or(i as u8))
            .collect();
        let mut towers = Vec::new();
        let tg = &spec.towers;
        let mut tower_sites: Vec<(Vec2, f32, u32, u16)> = settlements
            .iter()
            .map(|s| (s.pos, s.radius, s.pop, s.zone))
            .collect();
        for s in &spec.sites {
            if s.occupancy > 0 {
                tower_sites.push((proj.to_xy(s.pos), s.radius_m as f32, s.occupancy, zone_of(&s.zone)));
            }
        }
        for (ci, c) in spec.carriers.iter().enumerate() {
            if c.roams_on.is_some() {
                continue;
            }
            for &(pos, radius, pop, zone) in &tower_sites {
                let n = ((pop as f64 / tg.people_per_tower).ceil() as usize).max(1);
                for _ in 0..n {
                    let a = rng.gen::<f32>() * std::f32::consts::TAU;
                    let r = radius * 0.6 * rng.gen::<f32>().sqrt();
                    towers.push(Tower {
                        pos: Vec2::new(pos.x + r * a.cos(), pos.y + r * a.sin()),
                        carrier: ci as u8,
                        zone,
                        radius: tg.radius_m as f32,
                        rank: rng.gen(),
                        up: true,
                        temporary: false,
                        from: 0.0,
                        until: INF,
                        capacity: INF,
                        carriers: 1 << ci,
                        users: 0,
                        users_prev: 0,
                        name: String::new(),
                    });
                }
            }
            for p in &tg.extra {
                let pos = proj.to_xy(*p);
                let zone = Self::zone_near(&settlements, &sites, pos);
                towers.push(Tower {
                    pos,
                    carrier: ci as u8,
                    zone,
                    radius: tg.radius_m as f32,
                    rank: rng.gen(),
                    up: true,
                    temporary: false,
                    from: 0.0,
                    until: INF,
                    capacity: INF,
                    carriers: 1 << ci,
                    users: 0,
                    users_prev: 0,
                    name: String::new(),
                });
            }
        }
        let mask_of = |ids: &[String]| ids.iter().filter_map(|id| carrier_ix.get(id)).fold(0u32, |m, &i| m | 1 << i);
        if params.ships {
            for s in &spec.ships {
                let pos = proj.to_xy(s.pos);
                towers.push(Tower {
                    pos,
                    carrier: NONE as u8,
                    zone: Self::zone_near(&settlements, &sites, pos),
                    radius: s.radius_m as f32,
                    rank: 1.0,
                    up: false,
                    temporary: true,
                    from: (s.from_h * 60.0) as f32,
                    until: (s.until_h * 60.0) as f32,
                    capacity: s.capacity as f32,
                    carriers: mask_of(&s.carriers),
                    users: 0,
                    users_prev: 0,
                    name: s.name.clone(),
                });
            }
        }
        if params.mobile_bs {
            for s in &spec.mobile_bs {
                let pos = proj.to_xy(s.pos);
                towers.push(Tower {
                    pos,
                    carrier: NONE as u8,
                    zone: Self::zone_near(&settlements, &sites, pos),
                    radius: s.radius_m as f32,
                    rank: 1.0,
                    up: false,
                    temporary: true,
                    from: (s.from_h * 60.0) as f32,
                    until: INF,
                    capacity: INF,
                    carriers: mask_of(&s.carriers),
                    users: 0,
                    users_prev: 0,
                    name: s.name.clone(),
                });
            }
        }
        let mut tower_grid: HashMap<(i32, i32), Vec<u32>> = HashMap::new();
        for (i, tw) in towers.iter().enumerate() {
            let r = tw.radius;
            let (x0, x1) = (((tw.pos.x - r) / 5000.0).floor() as i32, ((tw.pos.x + r) / 5000.0).floor() as i32);
            let (y0, y1) = (((tw.pos.y - r) / 5000.0).floor() as i32, ((tw.pos.y + r) / 5000.0).floor() as i32);
            for gx in x0..=x1 {
                for gy in y0..=y1 {
                    tower_grid.entry((gx, gy)).or_default().push(i as u32);
                }
            }
        }

        let end = (spec.duration_h * 60.0) as f32;
        let depot = spec
            .supply
            .depot
            .as_ref()
            .and_then(|d| site_ix.get(d))
            .map(|&i| i as u32)
            .unwrap_or(NONE);
        let mut sim = Sim {
            proj,
            rng,
            t: 0.0,
            end,
            dt: 1.0,
            agents: Vec::new(),
            knows: Vec::new(),
            carry: Vec::new(),
            anpi_uploaded: Vec::new(),
            household_of: Vec::new(),
            sites: Vec::new(),
            settlements,
            zones: Vec::new(),
            towers,
            tower_grid,
            node_grid: {
                let mut g: HashMap<(i32, i32), Vec<u32>> = HashMap::new();
                for (i, p) in nodes.iter().enumerate() {
                    g.entry(((p.x / NODE_CELL).floor() as i32, (p.y / NODE_CELL).floor() as i32)).or_default().push(i as u32);
                }
                g
            },
            nodes,
            edges,
            adj,
            exits,
            msgs: Vec::new(),
            kind_mask: vec![BitSet::default(); MsgKind::ALL.len()],
            mesh_mask: BitSet::default(),
            internet: BitSet::default(),
            sns: BitSet::default(),
            talk_mask: BitSet::default(),
            prio_masks: vec![BitSet::default(); 6],
            talk_from: 0,
            ops: BitSet::default(),
            fakes_dispatched: Vec::new(),
            start_hour: start_hour(&spec.start),
            sd_prev_demand: 0.0,
            liaisons_spawned: false,
            fires_started: 0,
            next_hourly: 0.0,
            power_out: Vec::new(),
            rounds: HashMap::new(),
            victims: Vec::new(),
            teams: Vec::new(),
            depot,
            depot_water: 0.0,
            depot_food: 0.0,
            next_plan: 0.0,
            events: Vec::new(),
            series: Vec::new(),
            counters: Counters::default(),
            broadcast_msgs: Vec::new(),
            carrier_roam,
            sd_capacity: 0.0,
            sd_demand: 0.0,
            starlink_load: 0.0,
            next_sample: 0.0,
            focus: Some(Focus::Kind(MsgKind::TsunamiWarning)),
            teams_spawned: 0,
            trucks_spawned: 0,
            helis_spawned: 0,
            congestion_flag: [false; 2],
            anpi_flagged: false,
            scratch: Vec::new(),
            spec,
            params,
        };
        for s in sites.iter_mut() {
            s.node = if sim.nodes.is_empty() { NONE } else { s.node };
        }
        sim.sites = sites;
        sim.zones = std::mem::take(&mut zones);
        sim.populate();
        sim.household_of = vec![(NONE, NONE); sim.agents.len()];
        for (si, s) in sim.settlements.iter().enumerate() {
            for (h, (_, members)) in s.households.iter().enumerate() {
                for &m in members {
                    sim.household_of[m as usize] = (si as u32, h as u32);
                }
            }
        }
        sim.assign_navs();
        sim.place_victims();
        sim.dt = sim.dt_at(0.0);
        sim
    }

    fn zone_near(settlements: &[Settlement], sites: &[Site], p: Vec2) -> u16 {
        settlements
            .iter()
            .map(|s| (s.pos, s.zone))
            .chain(sites.iter().map(|s| (s.pos, s.zone)))
            .min_by(|a, b| a.0.dist2(p).partial_cmp(&b.0.dist2(p)).unwrap_or(Ordering::Equal))
            .map(|x| x.1)
            .unwrap_or(0)
    }

    pub fn dt_at(&self, t: f32) -> f32 {
        let h = t as f64 / 60.0;
        let mut dt = 1.0;
        for s in &self.spec.dt_schedule {
            if h >= s[0] {
                dt = s[1];
            }
        }
        dt as f32
    }

    fn new_agent(&mut self, pos: Vec2, role: Role, zone: u16, settlement: u32, age: u8, student: bool) -> u32 {
        let b = &self.spec.behavior;
        let p = &self.params;
        let rng = &mut self.rng;
        let staff = matches!(role, Role::ShelterStaff | Role::Rescuer | Role::Truck | Role::Liaison);
        let phone = staff || rng.gen::<f64>() < b.phone_by_age[age as usize];
        let mut carrier = 0u8;
        let mut u = rng.gen::<f64>();
        for (i, c) in self.spec.carriers.iter().enumerate() {
            if u < c.share {
                carrier = i as u8;
                break;
            }
            u -= c.share;
        }
        let adoption = if student {
            p.adoption_students
        } else if staff {
            p.staff_adoption
        } else if age == 2 {
            p.adoption * p.elderly_adoption_factor
        } else {
            p.adoption
        };
        let app = phone && rng.gen::<f64>() < adoption;
        let direct = phone
            && self.spec.carriers.get(carrier as usize).map(|c| c.direct_to_cell).unwrap_or(false)
            && rng.gen::<f64>() < self.spec.starlink_direct.device_share;
        let a = Agent {
            pos,
            home: pos,
            role,
            zone,
            settlement,
            site: NONE,
            shelter: NONE,
            age,
            health: Health::Ok,
            victim: NONE,
            phone,
            carrier,
            battery: if staff { 0.9 } else { rng.gen_range(0.25..1.0) },
            app,
            ios: rng.gen::<f64>() < p.ios_share,
            direct,
            car: age == 1 && rng.gen::<f64>() < 0.8,
            charge_rank: rng.gen(),
            signal_q: rng.gen_range(0.15..1.0),
            family_outside: role.is_population() && rng.gen::<f64>() < b.family_outside,
            anpi_out: INF,
            anpi_hq: INF,
            anpi_posted: false,
            plan: Plan::Idle,
            next_decision: 0.0,
            next_phone: rng.gen_range(1.0..20.0),
            next_sync: 0.0,
            in_tsunami_zone: false,
            inundation: false,
            leader: false,
            nav: false,
            nav_connected: false,
            tsunami_evacuated: false,
            warned_at: INF,
            offmap: NO_EXIT,
            left_for_good: false,
            indoor: true,
            online: false,
            meshed_at: -INF,
            called: Vec::new(),
            team: NONE,
            evac_done: false,
            intent: UNDECIDED,
            tsunami_stage: 0,
        };
        self.agents.push(a);
        self.knows.push(BitSet::default());
        self.carry.push(Vec::new());
        self.anpi_uploaded.push(false);
        (self.agents.len() - 1) as u32
    }

    fn populate(&mut self) {
        let scale = self.params.population_scale;
        let hh = self.spec.behavior.household_size.max(1.0);
        let specs = self.spec.settlements.clone();
        if let Some(file) = self.spec.households.clone() {
            self.populate_households(household_csv(&file));
        }
        for (si, s) in specs.iter().enumerate() {
            if self.spec.households.is_some() {
                break;
            }
            let n = (s.pop as f64 * scale).round() as u32;
            let center = self.settlements[si].pos;
            let zone = self.settlements[si].zone;
            let mut made = 0;
            while made < n {
                let size = ((self.rng.gen::<f64>() * 2.0 * hh).round() as u32).clamp(1, 6).min(n - made);
                let a = self.rng.gen::<f32>() * std::f32::consts::TAU;
                let r = s.radius_m as f32 * self.rng.gen::<f32>().sqrt();
                let hpos = Vec2::new(center.x + r * a.cos(), center.y + r * a.sin());
                let mut members = Vec::new();
                for _ in 0..size {
                    let u = self.rng.gen::<f64>();
                    let age = if u < s.elderly { 2 } else if u < s.elderly + 0.11 { 0 } else { 1 };
                    let jitter = Vec2::new(hpos.x + self.rng.gen_range(-4.0..4.0), hpos.y + self.rng.gen_range(-4.0..4.0));
                    let id = self.new_agent(jitter, Role::Resident, zone, si as u32, age, false);
                    let tz = self.rng.gen::<f64>() < s.tsunami_zone;
                    self.agents[id as usize].in_tsunami_zone = tz;
                    self.agents[id as usize].inundation = tz;
                    if tz {
                        self.counters.tsunami_population += 1;
                    }
                    members.push(id);
                }
                self.settlements[si].households.push((hpos, members));
                made += size;
            }
        }
        self.pick_leaders();
        // People inside buildings at the quake (campus).
        let site_specs = self.spec.sites.clone();
        for (i, s) in site_specs.iter().enumerate() {
            let n = (s.occupancy as f64 * scale).round() as u32;
            let student_share = s.student_share;
            for _ in 0..n {
                let pos = self.random_in_site(i as u32);
                let student = self.rng.gen::<f64>() < student_share;
                let age = if student { 1 } else if self.rng.gen::<f64>() < 0.1 { 2 } else { 1 };
                let zone = self.sites[i].zone;
                let id = self.new_agent(pos, if student { Role::Student } else { Role::Resident }, zone, NONE, age, student);
                let a = &mut self.agents[id as usize];
                a.site = i as u32;
                a.car = !student && a.car;
            }
        }
        // Municipal staff at designated shelters.
        let ratio = self.spec.behavior.staff_ratio;
        for i in 0..self.sites.len() {
            let s = &self.sites[i];
            if s.kind != SiteKind::Shelter || !s.designated {
                continue;
            }
            let n = ((s.capacity as f64 * ratio * scale).round() as u32).max(1);
            let zone = s.zone;
            let open = s.open_at;
            for _ in 0..n {
                let pos = self.random_in_site(i as u32);
                let id = self.new_agent(pos, Role::ShelterStaff, zone, NONE, 1, false);
                let a = &mut self.agents[id as usize];
                a.shelter = i as u32;
                a.site = i as u32;
                a.evac_done = true;
                a.plan = Plan::Stay { until: open, next: Next::Nothing };
                self.sites[i].staff.push(id);
            }
        }
    }

    /// Residents from household records; `population_scale` samples whole households.
    fn populate_households(&mut self, csv: &str) {
        let scale = self.params.population_scale;
        let index: HashMap<String, usize> = self.spec.settlements.iter().enumerate().map(|(i, s)| (s.id.clone(), i)).collect();
        for s in self.settlements.iter_mut() {
            s.pop = 0;
        }
        for line in csv.lines().skip(1) {
            let f: Vec<&str> = line.split(',').collect();
            if f.len() < 7 {
                continue;
            }
            if scale < 1.0 && self.rng.gen::<f64>() >= scale {
                continue;
            }
            let Some(&si) = index.get(f[4]) else { continue };
            let lat: f64 = f[0].parse().unwrap_or(0.0);
            let lon: f64 = f[1].parse().unwrap_or(0.0);
            let people: u32 = f[2].parse().unwrap_or(1);
            let elderly: f64 = f[3].parse().unwrap_or(0.3);
            let elev: f64 = f[5].parse().unwrap_or(10.0);
            let coast: f64 = f[6].parse().unwrap_or(5000.0);
            let spec = &self.spec.settlements[si];
            let near_sea = coast <= 1000.0;
            let flee = near_sea && elev <= spec.tsunami_flee_m.unwrap_or(0.0);
            let flooded = coast <= 800.0 && elev <= spec.tsunami_height_m.unwrap_or(0.0);
            let pos = self.proj.to_xy([lat, lon]);
            let zone = self.settlements[si].zone;
            let mut members = Vec::with_capacity(people as usize);
            for _ in 0..people {
                let u = self.rng.gen::<f64>();
                let age = if u < elderly { 2 } else if u < elderly + 0.11 { 0 } else { 1 };
                let p = Vec2::new(pos.x + self.rng.gen_range(-3.0..3.0), pos.y + self.rng.gen_range(-3.0..3.0));
                let id = self.new_agent(p, Role::Resident, zone, si as u32, age, false);
                let a = &mut self.agents[id as usize];
                a.in_tsunami_zone = flee || flooded;
                a.inundation = flooded;
                if flooded {
                    self.counters.tsunami_population += 1;
                }
                members.push(id);
            }
            self.settlements[si].pop += people;
            self.settlements[si].households.push((pos, members));
        }
    }

    /// One car per household with a driver; its navigation unit may run the protocol.
    /// A separate random stream keeps the rest of the population identical across nav adoption rates.
    fn assign_navs(&mut self) {
        let mut rng = ChaCha8Rng::seed_from_u64(self.params.seed ^ 0x6e61_7669);
        for si in 0..self.settlements.len() {
            for h in 0..self.settlements[si].households.len() {
                let driver = self.settlements[si].households[h].1.iter().copied().find(|&a| self.agents[a as usize].car);
                let (u, c) = (rng.gen::<f64>(), rng.gen::<f64>());
                if let Some(a) = driver {
                    let ag = &mut self.agents[a as usize];
                    ag.nav = u < self.params.carnav_adoption;
                    ag.nav_connected = ag.nav && c < self.params.carnav_connected;
                }
            }
        }
    }

    /// Whether this agent's car navigation unit is on: driving, or the engine idling for 車中泊.
    pub fn nav_active(&self, i: u32) -> bool {
        let a = &self.agents[i as usize];
        if !a.nav || !a.alive() || !a.on_map() || a.health != Health::Ok {
            return false;
        }
        match &a.plan {
            Plan::Move { speed, .. } => *speed >= 200.0,
            _ => matches!(a.role, Role::Rescuer | Role::Truck | Role::Liaison) || a.intent == 2 && a.evac_done && a.site == NONE,
        }
    }

    /// 区長・民生委員: about one per hundred residents goes door to door after the quake.
    fn pick_leaders(&mut self) {
        for si in 0..self.settlements.len() {
            let per100 = self.spec.settlements[si].leaders_per_100.unwrap_or(self.spec.behavior.leaders_per_100);
            let adults: Vec<u32> = self.settlements[si]
                .households
                .iter()
                .flat_map(|h| h.1.iter().copied())
                .filter(|&a| self.agents[a as usize].age != 0 && self.agents[a as usize].health == Health::Ok)
                .collect();
            if adults.is_empty() {
                continue;
            }
            let n = ((adults.len() as f64 * per100 / 100.0).round() as usize).clamp(usize::from(per100 > 0.0), adults.len());
            for _ in 0..n {
                let a = adults[self.rng.gen_range(0..adults.len())];
                self.agents[a as usize].leader = true;
            }
        }
    }

    pub fn random_in_site(&mut self, site: u32) -> Vec2 {
        let s = &self.sites[site as usize];
        let (c, r, floor) = (s.pos, s.radius, s.floor);
        match floor {
            Some([w, d]) => Vec2::new(c.x + (self.rng.gen::<f32>() - 0.5) * w, c.y + (self.rng.gen::<f32>() - 0.5) * d),
            None => {
                let a = self.rng.gen::<f32>() * std::f32::consts::TAU;
                let rr = r * self.rng.gen::<f32>().sqrt();
                Vec2::new(c.x + rr * a.cos(), c.y + rr * a.sin())
            }
        }
    }

    /// Picks trapped people in collapsed houses and samples when they would die without rescue.
    fn place_victims(&mut self) {
        let rs = self.spec.rescue.clone();
        let mut by_settlement: Vec<Vec<u32>> = vec![Vec::new(); self.settlements.len()];
        for (i, a) in self.agents.iter().enumerate() {
            if a.role == Role::Resident && a.settlement != NONE {
                by_settlement[a.settlement as usize].push(i as u32);
            }
        }
        for (si, people) in by_settlement.iter().enumerate() {
            let expected = people.len() as f64 * self.settlements[si].collapse * rs.trapped_per_person_collapsed;
            let mut n = expected.floor() as usize;
            if self.rng.gen::<f64>() < expected.fract() {
                n += 1;
            }
            for _ in 0..n.min(people.len()) {
                let a = people[self.rng.gen_range(0..people.len())];
                if self.agents[a as usize].health != Health::Ok {
                    continue;
                }
                let severe = self.rng.gen::<f64>() >= rs.light_share;
                let u = self.rng.gen::<f64>();
                let hours = if severe { survival_time(&rs.survival, u) } else { survival_time(&rs.survival, u) * 4.0 };
                let v = self.victims.len() as u32;
                self.victims.push(Victim {
                    agent: a,
                    severe,
                    death_at: (hours * 60.0) as f32,
                    found_at: INF,
                    msg: NONE,
                    neighbor_free_at: INF,
                    team: NONE,
                    outcome: VictimOutcome::Pending,
                    resolved_at: INF,
                    settlement: si as u32,
                    finder: NONE,
                });
                let ag = &mut self.agents[a as usize];
                ag.health = Health::Trapped;
                ag.victim = v;
                ag.evac_done = true;
                ag.in_tsunami_zone = false;
            }
        }
    }

    // ---- messages and knowledge ----

    #[allow(clippy::too_many_arguments)]
    pub fn new_msg(&mut self, kind: MsgKind, pos: Vec2, zone: u16, text: String) -> u32 {
        let id = self.msgs.len() as u32;
        let mesh_ok = kind != MsgKind::Roster || self.params.roster_aggregation;
        self.msgs.push(Message {
            id,
            kind,
            created: self.t,
            pos,
            zone,
            official: matches!(kind, MsgKind::TsunamiWarning | MsgKind::Official | MsgKind::RoadOpen),
            fake: kind == MsgKind::FakeRescue,
            mesh_ok,
            site: NONE,
            settlement: NONE,
            edge: NONE,
            victim: NONE,
            value: 0.0,
            members: Vec::new(),
            text,
            expires: INF,
            internet_at: INF,
            sns_at: INF,
            hq_at: INF,
            ops_at: INF,
            first_mesh_at: INF,
            reach: 0,
            via_mesh: 0,
        });
        self.kind_mask[kind.index()].insert(id);
        if mesh_ok {
            self.mesh_mask.insert(id);
        }
        if kind != MsgKind::Roster {
            self.talk_mask.insert(id);
            self.prio_masks[kind.priority() as usize].insert(id);
        }
        id
    }

    /// Agent `i` learns message `m`. Returns true if it was new to them.
    pub fn learn(&mut self, i: u32, m: u32, via: Via) -> bool {
        if !self.knows[i as usize].insert(m) {
            return false;
        }
        let t = self.t;
        let msg = &mut self.msgs[m as usize];
        msg.reach += 1;
        if via == Via::Mesh {
            msg.via_mesh += 1;
            if msg.first_mesh_at == INF {
                msg.first_mesh_at = t;
            }
        }
        match msg.kind {
            MsgKind::TsunamiWarning => {
                let a = &mut self.agents[i as usize];
                if a.warned_at == INF {
                    a.warned_at = t;
                }
            }
            MsgKind::Trapped => {
                // A neighbour who hears of someone trapped nearby goes to help.
                let v = msg.victim;
                if v != NONE {
                    let vic = &self.victims[v as usize];
                    let a = &self.agents[i as usize];
                    let vp = self.agents[vic.agent as usize].pos;
                    if vic.found_at == INF && vic.agent != i && a.mobile() && a.role.is_population() && a.pos.dist2(vp) < 500.0 * 500.0 {
                        let walk = a.pos.dist(vp) / 60.0;
                        let vic = &mut self.victims[v as usize];
                        vic.found_at = t + walk;
                        vic.finder = i;
                    }
                }
            }
            _ => {}
        }
        true
    }

    pub fn hq_learn(&mut self, zone: u16, m: u32) {
        if zone as usize >= self.zones.len() || !self.zones[zone as usize].knows.insert(m) {
            return;
        }
        let t = self.t;
        let site = self.msgs[m as usize].site;
        if site != NONE && matches!(self.msgs[m as usize].kind, MsgKind::Roster | MsgKind::NeedWater | MsgKind::NeedFood | MsgKind::Surplus) {
            self.sites[site as usize].hq_known = true;
        }
        if self.msgs[m as usize].hq_at == INF {
            self.msgs[m as usize].hq_at = t;
            if self.msgs[m as usize].kind == MsgKind::Roster {
                // Families who ask the municipality learn of the people on the roster.
                let members = std::mem::take(&mut self.msgs[m as usize].members);
                for &a in &members {
                    let d = 12.0 * 60.0 + self.rng.gen::<f32>() * 24.0 * 60.0;
                    let ag = &mut self.agents[a as usize];
                    ag.anpi_hq = ag.anpi_hq.min(t);
                    if ag.family_outside && ag.anpi_out > t + d {
                        ag.anpi_out = t + d;
                    }
                }
                self.msgs[m as usize].members = members;
            }
        }
    }

    /// The HQ reads 安否 posts on the AppView once they are online.
    pub fn anpi_online(&mut self, a: u32, at: f32) {
        let watch = if self.params.hq_appview { 60.0 } else { 24.0 * 60.0 };
        let d = -watch * (1.0 - self.rng.gen::<f32>()).ln();
        let ag = &mut self.agents[a as usize];
        ag.anpi_hq = ag.anpi_hq.min(at + d);
    }

    pub fn ops_learn(&mut self, m: u32) {
        if !self.ops.insert(m) {
            return;
        }
        let msg = &mut self.msgs[m as usize];
        if msg.ops_at == INF {
            msg.ops_at = self.t;
        }
        if msg.site != NONE {
            let site = &mut self.sites[msg.site as usize];
            match msg.kind {
                MsgKind::Roster => {
                    site.ops_known = true;
                    site.ops_pop = msg.value;
                }
                MsgKind::NeedWater | MsgKind::NeedFood => {
                    site.ops_known = true;
                    site.ops_need = true;
                    site.ops_pop = site.ops_pop.max(msg.value);
                }
                MsgKind::Surplus => {
                    site.ops_known = true;
                    site.ops_need = false;
                }
                _ => {}
            }
        }
    }

    /// Message `m` becomes available on the internet (posted through the app, or published officially).
    pub fn put_internet(&mut self, m: u32) {
        if !self.internet.insert(m) {
            return;
        }
        let t = self.t;
        self.msgs[m as usize].internet_at = t;
        if self.msgs[m as usize].kind == MsgKind::Roster {
            let members = std::mem::take(&mut self.msgs[m as usize].members);
            for &a in &members {
                let d = self.rng.gen::<f32>() * 6.0 * 60.0;
                let ag = &mut self.agents[a as usize];
                if ag.family_outside && ag.anpi_out > t + d {
                    ag.anpi_out = t + d;
                }
            }
            self.msgs[m as usize].members = members;
        }
    }

    pub fn event(&mut self, kind: EventKind, pos: Vec2, count: u32, text: String) {
        let ll = self.proj.to_latlon(pos);
        self.events.push(SimEvent {
            t: self.t,
            kind,
            label: kind.label(),
            severity: kind.severity(),
            text,
            lat: ll[0],
            lon: ll[1],
            count,
        });
    }

    // ---- roads ----

    /// Road edges `who` believes are cut: known cut reports without a known reopening.
    pub fn believed_cut(&self, knows: &BitSet, e: u32) -> bool {
        let ed = &self.edges[e as usize];
        ed.cut_msg != NONE && knows.get(ed.cut_msg) && !(ed.open_msg != NONE && knows.get(ed.open_msg))
    }

    /// Shortest path between nodes avoiding edges for which `blocked` is true. Returns (nodes, edges, minutes).
    pub fn route(&self, from: u32, to: u32, foot: bool, blocked: impl Fn(u32) -> bool) -> Option<(Vec<u32>, Vec<u32>, f32)> {
        if from == NONE || to == NONE {
            return None;
        }
        if from == to {
            return Some((vec![from], vec![], 0.0));
        }
        #[derive(PartialEq)]
        struct S(f32, u32);
        impl Eq for S {}
        impl PartialOrd for S {
            fn partial_cmp(&self, o: &Self) -> Option<Ordering> {
                Some(self.cmp(o))
            }
        }
        impl Ord for S {
            fn cmp(&self, o: &Self) -> Ordering {
                o.0.partial_cmp(&self.0).unwrap_or(Ordering::Equal)
            }
        }
        let n = self.nodes.len();
        let mut dist = vec![INF; n];
        let mut prev: Vec<(u32, u32)> = vec![(NONE, NONE); n];
        let mut heap = BinaryHeap::new();
        dist[from as usize] = 0.0;
        heap.push(S(0.0, from));
        while let Some(S(d, u)) = heap.pop() {
            if u == to {
                break;
            }
            if d > dist[u as usize] {
                continue;
            }
            for &(e, v) in &self.adj[u as usize] {
                if blocked(e) {
                    continue;
                }
                let ed = &self.edges[e as usize];
                let speed = if foot { 70.0 } else { ed.speed };
                let nd = d + ed.len / speed;
                if nd < dist[v as usize] {
                    dist[v as usize] = nd;
                    prev[v as usize] = (u, e);
                    heap.push(S(nd, v));
                }
            }
        }
        if dist[to as usize] == INF {
            return None;
        }
        let mut nodes = vec![to];
        let mut edges = Vec::new();
        let mut cur = to;
        while cur != from {
            let (p, e) = prev[cur as usize];
            edges.push(e);
            nodes.push(p);
            cur = p;
        }
        nodes.reverse();
        edges.reverse();
        Some((nodes, edges, dist[to as usize]))
    }

    pub fn nearest_node(&self, p: Vec2) -> u32 {
        if self.nodes.is_empty() {
            return NONE;
        }
        let (cx, cy) = ((p.x / NODE_CELL).floor() as i32, (p.y / NODE_CELL).floor() as i32);
        let mut best = (NONE, f32::INFINITY);
        // Search growing rings of cells; one ring beyond the first hit is enough.
        for r in 0..64 {
            for gx in cx - r..=cx + r {
                for gy in cy - r..=cy + r {
                    if (gx - cx).abs() != r && (gy - cy).abs() != r {
                        continue;
                    }
                    if let Some(v) = self.node_grid.get(&(gx, gy)) {
                        for &n in v {
                            let d = self.nodes[n as usize].dist2(p);
                            if d < best.1 {
                                best = (n, d);
                            }
                        }
                    }
                }
            }
            if best.0 != NONE && best.1.sqrt() < r as f32 * NODE_CELL {
                break;
            }
        }
        best.0
    }

    /// Plans a movement for agent `i` to `dest`, by road when far and a road graph exists.
    /// Returns false if no route is known to be open.
    pub fn plan_move(&mut self, i: u32, dest: Vec2, dest_node: u32, arrive: Arrive) -> bool {
        let a = &self.agents[i as usize];
        let start = a.pos;
        let vehicle = matches!(a.role, Role::Rescuer | Role::Truck | Role::Liaison) || a.car;
        let far = start.dist(dest) > 1500.0;
        let walk_speed = if a.age == 2 { 45.0 } else { 70.0 };
        if !far || self.nodes.is_empty() {
            let speed = if far && vehicle { 400.0 } else { walk_speed };
            self.agents[i as usize].plan =
                Plan::Move { pts: vec![start, dest], edges: vec![NONE], seg: 0, along: 0.0, speed, foot: !vehicle, arrive, started: self.t };
            self.agents[i as usize].site = NONE;
            return true;
        }
        let from = self.nearest_node(start);
        let to = if dest_node != NONE { dest_node } else { self.nearest_node(dest) };
        let foot = !vehicle;
        let knows = &self.knows[i as usize];
        let route = self.route(from, to, foot, |e| {
            let ed = &self.edges[e as usize];
            self.believed_cut(knows, e) && !(foot && ed.foot_ok)
        });
        let Some((nodes, redges, _)) = route else { return false };
        let mut pts = vec![start];
        let mut edges = vec![NONE];
        for (k, &n) in nodes.iter().enumerate() {
            pts.push(self.nodes[n as usize]);
            if k > 0 {
                edges.push(redges[k - 1]);
            }
        }
        pts.push(dest);
        edges.push(NONE);
        let speed = if vehicle { 500.0 } else { walk_speed };
        let a = &mut self.agents[i as usize];
        a.plan = Plan::Move { pts, edges, seg: 0, along: 0.0, speed, foot, arrive, started: self.t };
        a.site = NONE;
        true
    }

    pub fn site_internet(&self, s: u32) -> bool {
        let site = &self.sites[s as usize];
        self.t >= site.starlink_from || self.t >= site.fixed_from
    }

    pub fn zone_of_pos(&self, p: Vec2) -> u16 {
        Self::zone_near(&self.settlements, &self.sites, p)
    }

    pub fn time_label(&self) -> String {
        let d = (self.t / 1440.0).floor() as i32;
        let h = ((self.t % 1440.0) / 60.0).floor() as i32;
        let m = (self.t % 60.0).floor() as i32;
        format!("{d}日 {h:02}:{m:02}")
    }
}
