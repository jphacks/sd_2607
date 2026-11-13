//! Scenario description loaded from JSON (`core/scenarios/*.json`) and user-adjustable parameters.
//!
//! All times in scenario files are hours after the quake (`*_h`), and curves are `[[hours, value], ...]`.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use crate::util::Curve;

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ScenarioSpec {
    pub id: String,
    pub name: String,
    pub description: String,
    /// Local time of the quake, ISO 8601 with offset.
    pub start: String,
    pub duration_h: f64,
    /// `[from_h, dt_min]`: the step length in minutes from that hour on.
    pub dt_schedule: Vec<[f64; 2]>,
    /// Projection origin `[lat, lon]`.
    pub origin: [f64; 2],
    pub view: View,
    #[serde(default)]
    pub defaults: ParamOverrides,
    pub carriers: Vec<CarrierSpec>,
    pub zones: Vec<ZoneSpec>,
    #[serde(default)]
    pub settlements: Vec<SettlementSpec>,
    /// Embedded household file (`core/scenarios/data/<name>`) with `lat,lon,people,elderly,district,elev_m,coast_m`.
    /// When set, residents are placed on these households instead of in settlement disks.
    #[serde(default)]
    pub households: Option<String>,
    pub sites: Vec<SiteSpec>,
    #[serde(default)]
    pub road_nodes: Vec<RoadNodeSpec>,
    #[serde(default)]
    pub roads: Vec<RoadSpec>,
    #[serde(default)]
    pub exits: Vec<ExitSpec>,
    #[serde(default)]
    pub towers: TowerGen,
    pub congestion: Congestion,
    #[serde(default)]
    pub broadcasts: Vec<BroadcastSpec>,
    #[serde(default)]
    pub ships: Vec<ShipSpec>,
    #[serde(default)]
    pub mobile_bs: Vec<MobileBsSpec>,
    #[serde(default)]
    pub starlink: StarlinkSpec,
    #[serde(default)]
    pub starlink_direct: DirectSpec,
    #[serde(default)]
    pub rescue: RescueSpec,
    #[serde(default)]
    pub supply: SupplySpec,
    #[serde(default)]
    pub behavior: BehaviorSpec,
    #[serde(default)]
    pub hazards: HazardSpec,
    /// Evidence behind the numbers, shown in the app.
    #[serde(default)]
    pub sources: Vec<SourceRef>,
    /// Modelling notes and known deviations from reality, shown in the app.
    #[serde(default)]
    pub notes: Vec<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct View {
    pub center: [f64; 2],
    pub zoom: f64,
    #[serde(default = "default_max_zoom")]
    pub max_zoom: f64,
}

fn default_max_zoom() -> f64 {
    18.0
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct SourceRef {
    pub label: String,
    pub url: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct CarrierSpec {
    pub id: String,
    pub name: String,
    pub share: f64,
    /// Carrier whose towers this one roams on when its own are absent (楽天 → au).
    #[serde(default)]
    pub roams_on: Option<String>,
    /// Multiplier on the zone's tower-outage ratio.
    #[serde(default = "one")]
    pub outage_factor: f64,
    /// Supports Starlink direct-to-cell (au Starlink Direct).
    #[serde(default)]
    pub direct_to_cell: bool,
}

fn one() -> f64 {
    1.0
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ZoneSpec {
    pub id: String,
    pub name: String,
    /// Fraction of households without power.
    pub power_out: Curve,
    /// Fraction of mobile towers out of service (before the carrier factor).
    pub cell_out: Curve,
    /// Fraction of fixed lines (and fixed internet) out of service.
    pub fixed_out: Curve,
    /// Fraction of households without tap water.
    #[serde(default)]
    pub water_out: Curve,
    /// Fraction of outdoor loudspeakers of the municipal radio (防災行政無線) that work.
    #[serde(default = "half")]
    pub musen_ok: f64,
}

fn half() -> f64 {
    0.5
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct SettlementSpec {
    pub id: String,
    pub name: String,
    pub zone: String,
    pub pos: [f64; 2],
    /// Residents present at the quake (including New Year visitors for Noto).
    pub pop: u32,
    pub radius_m: f64,
    #[serde(default = "elderly_default")]
    pub elderly: f64,
    /// Fraction of residents inside the tsunami inundation area.
    #[serde(default)]
    pub tsunami_zone: f64,
    #[serde(default)]
    pub tsunami_arrival_h: Option<f64>,
    /// Fraction of houses that collapsed (全壊).
    #[serde(default)]
    pub collapse: f64,
    /// Fraction of residents who move to a shelter at the peak.
    #[serde(default = "evac_default")]
    pub evac_ratio: f64,
    /// Fraction of evacuees who sleep in their car instead of a shelter.
    #[serde(default)]
    pub car_ratio: f64,
    /// Road node the settlement connects to (defaults to the nearest node).
    #[serde(default)]
    pub node: Option<String>,
    /// Preferred shelters, nearest first; defaults to the nearest shelters in the zone.
    #[serde(default)]
    pub shelters: Vec<String>,
    /// With household data: houses at or below this height near the sea are inundated.
    #[serde(default)]
    pub tsunami_height_m: Option<f64>,
    /// With household data: residents at or below this height near the sea flee from the warning.
    #[serde(default)]
    pub tsunami_flee_m: Option<f64>,
    /// Leaders (区長・民生委員) who go door to door after the quake, per 100 residents.
    #[serde(default)]
    pub leaders_per_100: Option<f64>,
}

fn elderly_default() -> f64 {
    0.3
}
fn evac_default() -> f64 {
    0.3
}

#[derive(Clone, Copy, Debug, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum SiteKind {
    Shelter,
    Hq,
    Fire,
    Hospital,
    Building,
    Open,
    Depot,
    /// A hill or spot where a signal was reachable.
    SignalSpot,
    HighGround,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct SiteSpec {
    pub id: String,
    pub name: String,
    pub kind: SiteKind,
    pub zone: String,
    pub pos: [f64; 2],
    #[serde(default = "site_radius")]
    pub radius_m: f64,
    /// Indoor floor as a rectangle `[width_m, depth_m]`; agents are laid out on it.
    #[serde(default)]
    pub floor: Option<[f64; 2]>,
    #[serde(default)]
    pub capacity: u32,
    /// 指定避難所 (designated) vs 自主避難所 (opened by residents).
    #[serde(default)]
    pub designated: bool,
    /// Whether the municipal HQ knows this shelter exists at the start.
    #[serde(default)]
    pub known_to_hq: Option<bool>,
    #[serde(default)]
    pub stock_water_l: f64,
    #[serde(default)]
    pub stock_food: f64,
    /// When a generator or the grid powers the site (phones can charge).
    #[serde(default)]
    pub power_from_h: Option<f64>,
    /// When a Starlink terminal provides Wi-Fi.
    #[serde(default)]
    pub starlink_from_h: Option<f64>,
    /// When a fixed line / special public phone (特設公衆電話) works.
    #[serde(default)]
    pub fixed_from_h: Option<f64>,
    /// When a satellite phone is available to staff.
    #[serde(default)]
    pub satphone_from_h: Option<f64>,
    /// People in the building at the quake (campus buildings).
    #[serde(default)]
    pub occupancy: u32,
    /// Share of `occupancy` who are students (adoption follows `adoption_students`).
    #[serde(default)]
    pub student_share: f64,
    /// For shelters: the hour it opens (defaults to 0).
    #[serde(default)]
    pub open_h: f64,
    /// Peak evacuees observed, used to steer shelter choice and for comparison.
    #[serde(default)]
    pub peak_observed: Option<u32>,
    #[serde(default)]
    pub note: Option<String>,
}

fn site_radius() -> f64 {
    40.0
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct RoadNodeSpec {
    pub id: String,
    pub pos: [f64; 2],
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct RoadSpec {
    pub a: String,
    pub b: String,
    #[serde(default)]
    pub name: String,
    /// Detour factor over the straight line.
    #[serde(default = "winding")]
    pub winding: f64,
    /// Speed limit in km/h for vehicles (before damage slowdown).
    #[serde(default = "road_speed")]
    pub speed_kmh: f64,
    #[serde(default)]
    pub cut: Option<CutSpec>,
    /// Measured length along the road; overrides the straight line × winding.
    #[serde(default)]
    pub length_m: Option<f64>,
    /// Road shape `[[lat, lon], ...]` for drawing.
    #[serde(default)]
    pub geom: Vec<[f64; 2]>,
}

fn winding() -> f64 {
    1.35
}
fn road_speed() -> f64 {
    35.0
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct CutSpec {
    #[serde(default)]
    pub from_h: f64,
    /// When the road becomes passable again (emergency opening).
    pub until_h: f64,
    /// Whether people can still pass on foot.
    #[serde(default)]
    pub foot_ok: bool,
    #[serde(default)]
    pub cause: String,
}

/// A way off the map (e.g. 金沢), reached via a road node.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ExitSpec {
    pub id: String,
    pub name: String,
    pub node: String,
    /// Extra one-way travel time beyond the map edge.
    pub travel_h: Curve,
    /// Whether full internet is available there (always, for 金沢).
    #[serde(default = "yes")]
    pub internet: bool,
}

fn yes() -> bool {
    true
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct TowerGen {
    /// Coverage radius of a rural macro cell.
    pub radius_m: f64,
    /// Residents served per tower per carrier (sets the number around a settlement).
    pub people_per_tower: f64,
    /// Explicit extra towers `[lat, lon]` placed for every carrier (roads, hills).
    #[serde(default)]
    pub extra: Vec<[f64; 2]>,
    /// Hours until a tower on battery dies once commercial power is lost.
    #[serde(default = "battery_h")]
    pub battery_h: [f64; 2],
}

fn battery_h() -> [f64; 2] {
    [3.0, 24.0]
}

impl Default for TowerGen {
    fn default() -> Self {
        Self { radius_m: 3000.0, people_per_tower: 1500.0, extra: vec![], battery_h: battery_h() }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Congestion {
    /// Probability that a voice call attempt passes the carrier's call restriction (発信規制).
    pub voice_success: Curve,
    /// Probability that an SMS/data session succeeds on a working tower.
    pub data_success: Curve,
    /// Calls to 119 that the fire HQ can answer per hour (per zone).
    #[serde(default = "calls_119")]
    pub fire_lines_per_h: f64,
}

fn leaders() -> f64 {
    1.0
}
fn rounds_h() -> f64 {
    36.0
}

fn calls_119() -> f64 {
    40.0
}

#[derive(Clone, Copy, Debug, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum Channel {
    Tv,
    Radio,
    Musen,
    CellBroadcast,
    Internet,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct BroadcastSpec {
    pub t_h: f64,
    pub kind: BroadcastKind,
    pub text: String,
    /// Zones it concerns; empty means all.
    #[serde(default)]
    pub zones: Vec<String>,
    pub channels: Vec<Channel>,
    #[serde(default)]
    pub expires_h: Option<f64>,
}

#[derive(Clone, Copy, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum BroadcastKind {
    TsunamiWarning,
    TsunamiUpdate,
    Official,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ShipSpec {
    pub name: String,
    pub pos: [f64; 2],
    pub from_h: f64,
    pub until_h: f64,
    pub radius_m: f64,
    pub carriers: Vec<String>,
    /// Users that can be served at once.
    pub capacity: f64,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct MobileBsSpec {
    pub name: String,
    pub pos: [f64; 2],
    pub from_h: f64,
    pub radius_m: f64,
    pub carriers: Vec<String>,
    #[serde(default)]
    pub satellite: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct StarlinkSpec {
    /// Usable downlink per terminal.
    pub terminal_mbps: f64,
    /// Capacity of the satellite cell covering the area, shared by all terminals.
    pub cell_capacity_mbps: f64,
    /// Demand of one active user.
    pub user_kbps: f64,
    /// Wi-Fi radius around a terminal.
    pub wifi_radius_m: f64,
}

impl Default for StarlinkSpec {
    fn default() -> Self {
        Self { terminal_mbps: 100.0, cell_capacity_mbps: 2000.0, user_kbps: 500.0, wifi_radius_m: 60.0 }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct DirectSpec {
    /// Messages per minute one beam can carry over the area (shared by all users).
    pub beam_msgs_per_min: f64,
    /// Probability a satellite is in view in a given minute (outdoors).
    pub visibility: f64,
    /// Probability of a usable link indoors (near a window).
    pub indoor_factor: f64,
    /// Share of the carrier's phones that are compatible.
    pub device_share: f64,
    /// Messages a user tries to send per hour when it has nothing else.
    pub attempts_per_h: f64,
}

impl Default for DirectSpec {
    fn default() -> Self {
        Self { beam_msgs_per_min: 120.0, visibility: 0.9, indoor_factor: 0.1, device_share: 0.6, attempts_per_h: 2.0 }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct RescueSpec {
    /// Site id where outside teams gather.
    #[serde(default)]
    pub base: Option<String>,
    /// Professional teams operating in the area over time (fire, police, SDF).
    pub teams: Curve,
    /// Of `teams`, how many belong to local fire departments at the start.
    #[serde(default)]
    pub local_teams: u32,
    /// Hours one rescue takes on site `[min, max]`.
    pub duration_h: [f64; 2],
    /// Probability per team-hour of finding an unreported victim while searching a damaged area.
    pub search_find_per_h: f64,
    /// When systematic house-to-house search starts.
    pub search_from_h: f64,
    /// Survival of a seriously trapped person vs hours trapped.
    pub survival: Curve,
    /// Share of trapped people whom family/neighbours can free.
    pub light_share: f64,
    /// Mean hours for neighbours to free a light case once found.
    pub neighbor_rescue_h: f64,
    /// Trapped people per collapsed house occupant.
    pub trapped_per_person_collapsed: f64,
    /// Helicopters for isolated settlements over time.
    #[serde(default)]
    pub helicopters: Curve,
}

impl Default for RescueSpec {
    fn default() -> Self {
        Self {
            base: None,
            teams: Curve::constant(5.0),
            local_teams: 2,
            duration_h: [1.0, 4.0],
            search_find_per_h: 0.1,
            search_from_h: 24.0,
            survival: Curve(vec![[0.0, 1.0], [24.0, 0.75], [48.0, 0.45], [72.0, 0.25], [96.0, 0.1], [168.0, 0.02]]),
            light_share: 0.7,
            neighbor_rescue_h: 2.0,
            trapped_per_person_collapsed: 0.1,
            helicopters: Curve::default(),
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct SupplySpec {
    /// Site id of the supply base (物資拠点); per-zone depots fall back to the HQ.
    #[serde(default)]
    pub depot: Option<String>,
    /// Liters of water arriving at the base per hour.
    pub water_l_per_h: Curve,
    /// Meals arriving per hour.
    pub food_per_h: Curve,
    /// Trucks available for delivery.
    pub trucks: Curve,
    pub truck_water_l: f64,
    pub truck_food: f64,
    pub water_l_per_person_day: f64,
    pub food_per_person_day: f64,
    /// Days of stock above which a shelter reports surplus.
    pub surplus_days: f64,
    /// Hours between delivery planning rounds.
    pub plan_every_h: f64,
    /// Municipal staff cars per zone that visit shelters and bring reports back (巡回・伝令).
    #[serde(default = "liaisons")]
    pub liaisons_per_zone: u32,
    #[serde(default = "liaison_from")]
    pub liaison_from_h: f64,
}

fn liaisons() -> u32 {
    2
}
fn liaison_from() -> f64 {
    6.0
}

impl Default for SupplySpec {
    fn default() -> Self {
        Self {
            depot: None,
            water_l_per_h: Curve::constant(0.0),
            food_per_h: Curve::constant(0.0),
            trucks: Curve::constant(0.0),
            truck_water_l: 2000.0,
            truck_food: 2000.0,
            water_l_per_person_day: 3.0,
            food_per_person_day: 3.0,
            surplus_days: 4.0,
            plan_every_h: 6.0,
            liaisons_per_zone: liaisons(),
            liaison_from_h: liaison_from(),
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct BehaviorSpec {
    /// Mean hours before a resident leaves for the shelter.
    pub evac_delay_h: f64,
    /// Probability that someone in the tsunami zone flees to high ground on the shaking alone.
    pub self_evac_tsunami: f64,
    /// Probability of fleeing once warned.
    pub warned_evac_tsunami: f64,
    /// Face-to-face conversations per waking hour.
    pub talk_per_h: f64,
    /// Items passed on in one conversation.
    pub talk_items: u32,
    /// Trips per adult per day (home check, family, water, other shelters).
    pub trips_per_day: f64,
    /// Of those trips, the share driven to a place with signal or off the map.
    pub signal_trip_share: f64,
    /// Share of evacuees leaving the area for good per day (家族の迎え, 2次避難).
    pub leave_per_day: f64,
    /// Smartphone ownership by age group: child, adult, elderly.
    pub phone_by_age: [f64; 3],
    pub family_outside: f64,
    /// Phone use attempts per hour while something is unsent.
    pub phone_tries_per_h: f64,
    /// Battery drain per hour (standby, searching for signal) and extra for the mesh.
    pub drain_per_h: f64,
    pub mesh_drain_per_h: f64,
    pub charge_per_h: f64,
    /// Share of people able to charge from a car.
    pub car_charge: f64,
    /// Probability that someone online posts what they know on SNS.
    pub sns_post: f64,
    /// Probability per hour that someone with TV/radio access hears a broadcast.
    pub broadcast_per_h: f64,
    /// Share of people with access to TV or radio (battery radio, car radio) without grid power.
    pub radio_without_power: f64,
    /// Hours between reads of the shelter bulletin board.
    pub board_read_h: f64,
    /// After failing to call, probability of going in person to report a trapped person.
    pub report_in_person: f64,
    /// Residents represented by one agent's household (for layout).
    pub household_size: f64,
    /// Shelter staff per evacuee.
    pub staff_ratio: f64,
    /// Door-to-door leaders per 100 residents (区長・民生委員), and how long their rounds last.
    #[serde(default = "leaders")]
    pub leaders_per_100: f64,
    #[serde(default = "rounds_h")]
    pub rounds_h: f64,
    /// Probability per online phone use of reporting one's own safety to the HQ (a university's 安否確認).
    #[serde(default)]
    pub report_safety_to_hq: f64,
}

impl Default for BehaviorSpec {
    fn default() -> Self {
        Self {
            evac_delay_h: 1.0,
            self_evac_tsunami: 0.6,
            warned_evac_tsunami: 0.85,
            talk_per_h: 2.0,
            talk_items: 2,
            trips_per_day: 0.5,
            signal_trip_share: 0.2,
            leave_per_day: 0.01,
            phone_by_age: [0.4, 0.95, 0.7],
            family_outside: 0.8,
            phone_tries_per_h: 2.0,
            drain_per_h: 0.03,
            mesh_drain_per_h: 0.008,
            charge_per_h: 0.4,
            car_charge: 0.3,
            sns_post: 0.2,
            broadcast_per_h: 0.5,
            radio_without_power: 0.3,
            board_read_h: 6.0,
            report_in_person: 0.5,
            household_size: 2.3,
            staff_ratio: 0.02,
            leaders_per_100: leaders(),
            rounds_h: rounds_h(),
            report_safety_to_hq: 0.0,
        }
    }
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct HazardSpec {
    /// Fires with `[lat, lon]`, start and extent; residents nearby learn and report.
    #[serde(default)]
    pub fires: Vec<FireSpec>,
    /// Probability per message that an SNS rescue request is fake.
    #[serde(default)]
    pub fake_sns_rate: f64,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct FireSpec {
    pub name: String,
    pub pos: [f64; 2],
    pub from_h: f64,
}

/// Scenario-specific defaults for the user parameters.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct ParamOverrides {
    #[serde(default)]
    pub population_scale: Option<f64>,
    #[serde(default)]
    pub adoption: Option<f64>,
    #[serde(default)]
    pub adoption_students: Option<f64>,
    #[serde(default)]
    pub mesh_range_m: Option<f64>,
}

/// Parameters the user adjusts in the app.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct SimParams {
    pub seed: u64,
    /// Share of smartphone owners with a Mosimo-enabled app.
    pub adoption: f64,
    /// Adoption among students (川内: the university app used by ~90% of students).
    pub adoption_students: f64,
    /// Relative adoption among the elderly.
    pub elderly_adoption_factor: f64,
    /// Adoption among municipal staff and shelter staff.
    pub staff_adoption: f64,
    pub population_scale: f64,
    /// BLE/Wi-Fi reach between phones outdoors, and indoors (walls and bodies attenuate).
    pub mesh_range_m: f64,
    pub mesh_range_indoor_m: f64,
    /// Share of iPhones; iOS discovers peers slowly in the background.
    pub ios_share: f64,
    /// Mean seconds to discover a peer in range: Android foreground service, with an iPhone, between two iPhones.
    pub discovery_s: f64,
    pub discovery_ios_s: f64,
    pub discovery_ios_pair_s: f64,
    /// Connections an app initiates per minute when peers are in range.
    pub mesh_contacts_per_min: f64,
    /// Link throughput (kbit/s) and per-contact setup (connect, HELLO, set reconciliation).
    pub mesh_kbps: f64,
    pub mesh_setup_s: f64,
    /// Share of household cars whose navigation unit runs the protocol. It works while the engine runs
    /// (driving, or idling for 車中泊) on the car battery, with a roof antenna.
    pub carnav_adoption: f64,
    pub carnav_range_m: f64,
    /// Of those, the share with a cellular module (connected car) that can upload when a tower is up.
    pub carnav_connected: f64,
    pub starlink_fixed: bool,
    pub starlink_direct: bool,
    /// Counterfactual: Starlink Direct also carries app data (not only SMS).
    pub starlink_direct_data: bool,
    pub ships: bool,
    pub mobile_bs: bool,
    /// The HQ watches the AppView (structured, located posts).
    pub hq_appview: bool,
    /// Shelter staff post the roster as one aggregated post through the app.
    pub roster_aggregation: bool,
    /// Forward high-priority posts first over narrow links (priority extension).
    pub priority: bool,
    /// Multiplier on the Starlink cell capacity (to probe congestion).
    pub starlink_capacity_factor: f64,
    /// Multiplier on rescue teams (to see where information, not capacity, is the limit).
    pub rescue_capacity_factor: f64,
}

impl Default for SimParams {
    fn default() -> Self {
        Self {
            seed: 1,
            adoption: 0.3,
            adoption_students: 0.9,
            elderly_adoption_factor: 0.5,
            staff_adoption: 0.5,
            population_scale: 1.0,
            mesh_range_m: 40.0,
            mesh_range_indoor_m: 15.0,
            ios_share: 0.6,
            discovery_s: 10.0,
            discovery_ios_s: 60.0,
            discovery_ios_pair_s: 600.0,
            mesh_contacts_per_min: 1.0,
            mesh_kbps: 100.0,
            mesh_setup_s: 4.0,
            carnav_adoption: 0.0,
            carnav_range_m: 80.0,
            carnav_connected: 0.3,
            starlink_fixed: true,
            starlink_direct: false,
            starlink_direct_data: false,
            ships: true,
            mobile_bs: true,
            hq_appview: true,
            roster_aggregation: true,
            priority: true,
            starlink_capacity_factor: 1.0,
            rescue_capacity_factor: 1.0,
        }
    }
}

impl SimParams {
    pub fn for_scenario(spec: &ScenarioSpec) -> Self {
        let mut p = Self::default();
        let d = &spec.defaults;
        if let Some(v) = d.population_scale {
            p.population_scale = v;
        }
        if let Some(v) = d.adoption {
            p.adoption = v;
        }
        if let Some(v) = d.adoption_students {
            p.adoption_students = v;
        }
        if let Some(v) = d.mesh_range_m {
            p.mesh_range_m = v;
        }
        p
    }
}

/// Built-in scenarios, embedded at compile time.
pub fn builtin_scenarios() -> Vec<ScenarioSpec> {
    let raw = [
        include_str!("../scenarios/shelter.json"),
        include_str!("../scenarios/kawauchi.json"),
        include_str!("../scenarios/noto.json"),
    ];
    raw.iter()
        .map(|s| serde_json::from_str(s).unwrap_or_else(|e| panic!("invalid built-in scenario: {e}")))
        .collect()
}

pub fn index_by_id<T>(items: &[T], id: impl Fn(&T) -> &str) -> BTreeMap<String, usize> {
    items.iter().enumerate().map(|(i, x)| (id(x).to_string(), i)).collect()
}
