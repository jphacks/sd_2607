//! Runtime state of a simulation.

use serde::Serialize;

use crate::spec::SiteKind;
use crate::util::{BitSet, Vec2};

pub const NONE: u32 = u32::MAX;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Role {
    Resident,
    Student,
    ShelterStaff,
    Rescuer,
    Truck,
    Liaison,
}

impl Role {
    pub fn is_population(self) -> bool {
        matches!(self, Role::Resident | Role::Student | Role::ShelterStaff)
    }
    pub fn code(self) -> u16 {
        match self {
            Role::Resident => 0,
            Role::Student => 1,
            Role::ShelterStaff => 2,
            Role::Rescuer => 3,
            Role::Truck => 4,
            Role::Liaison => 5,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Health {
    Ok,
    Trapped,
    Dead,
}

/// Where a movement ends and what happens there.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Arrive {
    Home,
    Site(u32),
    /// Off the map through an exit.
    Exit(u16),
    /// A rescue job (victim index) or a fake request (message id).
    Job,
    /// Just stop (tsunami high ground, search area).
    Stop,
    /// A leader reaches a household (index in its settlement) on door-to-door rounds.
    Visit(u32),
}

#[derive(Clone, Debug)]
pub enum Plan {
    Idle,
    /// Polyline movement. `edges[i]` is the road edge of segment `i` (from `pts[i]` to `pts[i+1]`).
    Move { pts: Vec<Vec2>, edges: Vec<u32>, seg: usize, along: f32, speed: f32, foot: bool, arrive: Arrive, started: f32 },
    /// Staying at a site or off the map until a time, then `next`.
    Stay { until: f32, next: Next },
}

/// What to do once a stay is over.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Next {
    Nothing,
    ReturnShelter,
    ReturnHome,
    GoShelter,
    ReturnFromExit,
}

#[derive(Clone, Debug)]
pub struct Agent {
    pub pos: Vec2,
    pub home: Vec2,
    pub role: Role,
    pub zone: u16,
    pub settlement: u32,
    /// Site the agent is inside of, if any.
    pub site: u32,
    /// Assigned shelter (for residents) or post (staff, liaison home).
    pub shelter: u32,
    pub age: u8, // 0 child, 1 adult, 2 elderly
    pub health: Health,
    pub victim: u32,
    pub phone: bool,
    pub carrier: u8,
    pub battery: f32,
    pub app: bool,
    pub ios: bool,
    pub direct: bool,
    pub car: bool,
    pub charge_rank: f32,
    /// Persistent signal quality where this person spends time (indoors, coverage fringe), `0.15..1`.
    pub signal_q: f32,
    pub family_outside: bool,
    pub anpi_out: f32,
    /// When the HQ (municipality / university) learnt this person is safe.
    pub anpi_hq: f32,
    pub anpi_posted: bool,
    pub plan: Plan,
    pub next_decision: f32,
    pub next_phone: f32,
    pub next_sync: f32,
    /// Lives low and near the sea: flees when warned or shaken.
    pub in_tsunami_zone: bool,
    /// Home is below the tsunami's run-up height: counted as exposed if still there on arrival.
    pub inundation: bool,
    /// Neighbourhood leader (区長・民生委員) who goes door to door.
    pub leader: bool,
    /// Drives the household's car whose navigation unit runs the protocol; `nav_connected` if it has a cellular module.
    pub nav: bool,
    pub nav_connected: bool,
    pub tsunami_evacuated: bool,
    pub warned_at: f32,
    pub offmap: u16,
    pub left_for_good: bool,
    pub indoor: bool,
    pub online: bool,
    pub meshed_at: f32,
    /// Trapped reports this agent already passed to 119/HQ.
    pub called: Vec<u32>,
    pub team: u32,
    /// Has evacuated / settled its first plan.
    pub evac_done: bool,
    /// `UNDECIDED`, 0 stay home, 1 go to a shelter, 2 sleep in the car, 3 assembled on campus.
    pub intent: u8,
    /// 0 not yet, 1 decided on the shaking alone, 2 decided again after the warning.
    pub tsunami_stage: u8,
}

pub const NO_EXIT: u16 = u16::MAX;
pub const UNDECIDED: u8 = u8::MAX;

impl Agent {
    pub fn alive(&self) -> bool {
        self.health != Health::Dead
    }
    pub fn mobile(&self) -> bool {
        self.health == Health::Ok
    }
    pub fn on_map(&self) -> bool {
        self.offmap == NO_EXIT
    }
    pub fn phone_on(&self) -> bool {
        self.phone && self.battery > 0.0 && self.alive()
    }
    pub fn moving(&self) -> bool {
        matches!(self.plan, Plan::Move { .. })
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MsgKind {
    TsunamiWarning,
    Official,
    Trapped,
    FakeRescue,
    RoadCut,
    RoadOpen,
    NeedWater,
    NeedFood,
    Surplus,
    Roster,
    Isolated,
    Fire,
}

impl MsgKind {
    pub const ALL: [MsgKind; 12] = [
        MsgKind::TsunamiWarning,
        MsgKind::Official,
        MsgKind::Trapped,
        MsgKind::FakeRescue,
        MsgKind::RoadCut,
        MsgKind::RoadOpen,
        MsgKind::NeedWater,
        MsgKind::NeedFood,
        MsgKind::Surplus,
        MsgKind::Roster,
        MsgKind::Isolated,
        MsgKind::Fire,
    ];
    /// Delivery priority (higher first), following `dev.jisaku.mosimo.priority`.
    pub fn priority(self) -> u8 {
        match self {
            MsgKind::TsunamiWarning => 5,
            MsgKind::Trapped | MsgKind::FakeRescue | MsgKind::Fire => 4,
            MsgKind::Isolated => 3,
            MsgKind::NeedWater | MsgKind::NeedFood | MsgKind::RoadCut => 2,
            MsgKind::Official | MsgKind::RoadOpen | MsgKind::Surplus => 1,
            MsgKind::Roster => 0,
        }
    }
    pub fn label(self) -> &'static str {
        match self {
            MsgKind::TsunamiWarning => "津波警報",
            MsgKind::Official => "公的情報",
            MsgKind::Trapped => "要救助(生き埋め)",
            MsgKind::FakeRescue => "偽の救助要請",
            MsgKind::RoadCut => "道路寸断",
            MsgKind::RoadOpen => "道路啓開",
            MsgKind::NeedWater => "水が不足",
            MsgKind::NeedFood => "食料が不足",
            MsgKind::Surplus => "物資が余剰",
            MsgKind::Roster => "避難者名簿(集約安否)",
            MsgKind::Isolated => "孤立集落",
            MsgKind::Fire => "火災",
        }
    }
    pub fn index(self) -> usize {
        MsgKind::ALL.iter().position(|k| *k == self).unwrap()
    }
    /// Bytes on the mesh: a signed Nostr event carrying the atproto record and commit (about 2 KB);
    /// public information also carries its attestation chain.
    pub fn bytes(self) -> u32 {
        match self {
            MsgKind::TsunamiWarning | MsgKind::Official | MsgKind::RoadOpen => 4000,
            MsgKind::Roster => 2000,
            _ => 2000,
        }
    }
}

#[derive(Clone, Debug, Serialize)]
pub struct Message {
    pub id: u32,
    pub kind: MsgKind,
    pub created: f32,
    pub pos: Vec2,
    pub zone: u16,
    pub official: bool,
    pub fake: bool,
    /// Whether the app may carry it (rosters only with aggregation enabled).
    pub mesh_ok: bool,
    pub site: u32,
    pub settlement: u32,
    pub edge: u32,
    pub victim: u32,
    pub value: f32,
    #[serde(skip)]
    pub members: Vec<u32>,
    pub text: String,
    pub expires: f32,
    /// First time on the internet (PDS/AppView or an official site).
    pub internet_at: f32,
    pub sns_at: f32,
    /// First time the municipal HQ of its zone knew it.
    pub hq_at: f32,
    /// First time the prefecture / rescue coordination knew it.
    pub ops_at: f32,
    pub first_mesh_at: f32,
    pub reach: u32,
    pub via_mesh: u32,
}

impl Message {
    pub fn bytes(&self) -> u32 {
        self.kind.bytes() + 40 * self.members.len() as u32
    }
}

/// Bytes of a 安否 post on the mesh.
pub const ANPI_BYTES: u32 = 1500;

#[derive(Clone, Debug)]
pub struct Site {
    pub id: String,
    pub name: String,
    pub kind: SiteKind,
    pub zone: u16,
    pub pos: Vec2,
    pub radius: f32,
    pub floor: Option<[f32; 2]>,
    pub capacity: u32,
    pub designated: bool,
    pub known_initially: bool,
    pub water: f64,
    pub food: f64,
    pub power_from: f32,
    pub starlink_from: f32,
    pub fixed_from: f32,
    pub satphone_from: f32,
    pub open_at: f32,
    pub node: u32,
    pub peak_observed: Option<u32>,
    // dynamic
    pub present: u32,
    pub peak: u32,
    pub staff: Vec<u32>,
    pub board: BitSet,
    pub last_roster: f32,
    pub need_water: u32,
    pub need_food: u32,
    pub surplus: u32,
    pub water_short_since: f32,
    pub food_short_since: f32,
    pub short_hours: f64,
    pub delivered_water: f64,
    pub deliveries: u32,
    pub next_report: f32,
    pub starlink_users: u32,
    pub reported_short: bool,
    pub food_reported: bool,
    pub ops_known: bool,
    pub hq_known: bool,
    /// Evacuees the coordination believes are here (latest roster it knows), or negative if unknown.
    pub ops_pop: f32,
    pub ops_need: bool,
    pub last_delivery: f32,
    pub last_concentration: f32,
    pub starlink_ok: f32,
    pub unknown_flagged: bool,
    /// People present this step.
    pub people: Vec<u32>,
    /// People whose safety a door-to-door leader reported here; added to the next roster.
    pub reported_safe: Vec<u32>,
}

#[derive(Clone, Debug)]
pub struct Settlement {
    /// Households: home position and members.
    pub households: Vec<(Vec2, Vec<u32>)>,
    pub id: String,
    pub name: String,
    pub zone: u16,
    pub pos: Vec2,
    pub radius: f32,
    pub pop: u32,
    pub node: u32,
    pub shelters: Vec<u32>,
    pub tsunami_arrival: f32,
    pub tsunami_done: bool,
    pub high_ground: Vec2,
    pub isolated_since: f32,
    pub isolated_msg: u32,
    pub isolated_reported: bool,
    pub search_effort: f32,
    pub collapse: f64,
}

#[derive(Clone, Debug)]
pub struct Zone {
    pub id: String,
    pub name: String,
    pub hq: u32,
    pub knows: BitSet,
    pub calls_budget: f64,
    pub musen_ok: f64,
}

#[derive(Clone, Debug, Serialize)]
pub struct Tower {
    pub pos: Vec2,
    pub carrier: u8,
    pub zone: u16,
    pub radius: f32,
    /// Rank in `[0,1)`; a tower is down while its rank is below the zone's outage ratio.
    #[serde(skip)]
    pub rank: f32,
    pub up: bool,
    /// Ship, vehicle or portable satellite base station.
    pub temporary: bool,
    pub from: f32,
    pub until: f32,
    pub capacity: f32,
    #[serde(skip)]
    pub carriers: u32,
    #[serde(skip)]
    pub users: u32,
    #[serde(skip)]
    pub users_prev: u32,
    pub name: String,
}

#[derive(Clone, Debug, Serialize)]
pub struct RoadEdge {
    pub a: u32,
    pub b: u32,
    pub name: String,
    pub len: f32,
    pub speed: f32,
    pub cut_from: f32,
    pub cut_until: f32,
    pub foot_ok: bool,
    pub cause: String,
    #[serde(skip)]
    pub cut_msg: u32,
    #[serde(skip)]
    pub open_msg: u32,
}

impl RoadEdge {
    pub fn cut_at(&self, t: f32) -> bool {
        t >= self.cut_from && t < self.cut_until
    }
}

#[derive(Clone, Debug)]
pub struct Exit {
    pub id: String,
    pub name: String,
    pub node: u32,
    pub travel_h: crate::util::Curve,
    pub internet: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum VictimOutcome {
    Pending,
    RescuedByNeighbors,
    RescuedByTeam,
    Dead,
}

#[derive(Clone, Debug)]
pub struct Victim {
    pub agent: u32,
    pub severe: bool,
    pub death_at: f32,
    pub found_at: f32,
    pub msg: u32,
    pub neighbor_free_at: f32,
    pub team: u32,
    pub outcome: VictimOutcome,
    pub resolved_at: f32,
    pub settlement: u32,
    pub finder: u32,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum TeamJob {
    None,
    Victim(u32),
    Fake(u32),
    Search(u32),
    Deliver(u32),
    Visit(u32),
    Return,
}

#[derive(Clone, Debug)]
pub struct Team {
    pub agent: u32,
    pub local_zone: u16,
    pub job: TeamJob,
    pub busy_until: f32,
    pub heli: bool,
    pub water: f64,
    pub food: f64,
    pub base: u32,
    pub last_visit: Vec<f32>,
    /// After finding nothing it can reach, a team waits before trying again.
    pub next_try: f32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum EventKind {
    /// A trapped person died before rescue.
    RescueTooLate,
    TsunamiExposed,
    WaterShortage,
    FoodShortage,
    SupplyConcentration,
    IsolatedUnknown,
    UnknownRoadCut,
    FakeDispatch,
    StarlinkCongestion,
    DirectCongestion,
    AnpiUnknownLong,
    ShelterUnknown,
}

impl EventKind {
    pub fn label(self) -> &'static str {
        match self {
            EventKind::RescueTooLate => "救助が間に合わない",
            EventKind::TsunamiExposed => "津波到達時に浸水域に残留",
            EventKind::WaterShortage => "避難所の水が尽きた",
            EventKind::FoodShortage => "避難所の食料が尽きた",
            EventKind::SupplyConcentration => "物資が集中しすぎ",
            EventKind::IsolatedUnknown => "孤立集落を把握できていない",
            EventKind::UnknownRoadCut => "寸断を知らず車両が立ち往生",
            EventKind::FakeDispatch => "偽の救助要請に出動",
            EventKind::StarlinkCongestion => "Starlinkが輻輳",
            EventKind::DirectCongestion => "Starlink Directが輻輳",
            EventKind::AnpiUnknownLong => "安否不明が長期化",
            EventKind::ShelterUnknown => "避難所を本部が把握していない",
        }
    }
    pub fn severity(self) -> u8 {
        match self {
            EventKind::RescueTooLate | EventKind::TsunamiExposed => 3,
            EventKind::WaterShortage
            | EventKind::FoodShortage
            | EventKind::IsolatedUnknown
            | EventKind::SupplyConcentration
            | EventKind::ShelterUnknown => 2,
            _ => 1,
        }
    }
}

#[derive(Clone, Debug, Serialize)]
pub struct SimEvent {
    pub t: f32,
    pub kind: EventKind,
    pub label: &'static str,
    pub severity: u8,
    pub text: String,
    pub lat: f64,
    pub lon: f64,
    pub count: u32,
}
