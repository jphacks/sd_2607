//! What the UI reads: static layout once, a compact binary frame of agents, and a JSON status.

use serde::Serialize;

use crate::model::*;
use crate::sim::{Counters, Sample, Sim, INF};
use crate::spec::{SimParams, SourceRef, View};

#[derive(Serialize)]
pub struct StaticInfo {
    pub id: String,
    pub name: String,
    pub description: String,
    pub start: String,
    pub duration_h: f64,
    pub view: View,
    pub projection: crate::util::Projection,
    pub params: SimParams,
    pub sites: Vec<SiteInfo>,
    pub settlements: Vec<SettlementInfo>,
    pub roads: Vec<RoadInfo>,
    pub towers: Vec<TowerInfo>,
    pub carriers: Vec<NameId>,
    pub zones: Vec<NameId>,
    pub msg_kinds: Vec<KindInfo>,
    pub sources: Vec<SourceRef>,
    pub notes: Vec<String>,
    pub victims: u32,
}

#[derive(Serialize)]
pub struct NameId {
    pub id: String,
    pub name: String,
}

#[derive(Serialize)]
pub struct KindInfo {
    pub id: MsgKind,
    pub label: &'static str,
    pub priority: u8,
}

#[derive(Serialize)]
pub struct SiteInfo {
    pub id: String,
    pub name: String,
    pub kind: crate::spec::SiteKind,
    pub lat: f64,
    pub lon: f64,
    pub radius: f32,
    pub floor: Option<[f32; 2]>,
    pub capacity: u32,
    pub designated: bool,
    pub peak_observed: Option<u32>,
    pub starlink_from_h: Option<f32>,
    pub note: Option<String>,
}

#[derive(Serialize)]
pub struct SettlementInfo {
    pub name: String,
    pub lat: f64,
    pub lon: f64,
    pub pop: u32,
    pub radius: f32,
}

#[derive(Serialize)]
pub struct RoadInfo {
    pub geom: Vec<[f64; 2]>,
    pub name: String,
    pub a: [f64; 2],
    pub b: [f64; 2],
    pub cut_from_h: Option<f32>,
    pub cut_until_h: Option<f32>,
    pub foot_ok: bool,
    pub cause: String,
}

#[derive(Serialize)]
pub struct TowerInfo {
    pub lat: f64,
    pub lon: f64,
    pub carrier: Option<u8>,
    pub radius: f32,
    pub temporary: bool,
    pub name: String,
    pub from_h: f32,
    pub until_h: Option<f32>,
}

#[derive(Serialize)]
pub struct SiteState {
    pub present: u32,
    pub peak: u32,
    pub water_days: f32,
    pub food_days: f32,
    pub internet: bool,
    pub starlink: bool,
    pub known: bool,
    pub short: bool,
    pub need: bool,
    pub surplus: bool,
    pub board: u32,
}

#[derive(Serialize)]
pub struct MsgInfo {
    pub id: u32,
    pub kind: MsgKind,
    pub label: &'static str,
    pub text: String,
    pub created_h: f32,
    pub internet_h: Option<f32>,
    pub hq_h: Option<f32>,
    pub ops_h: Option<f32>,
    pub sns_h: Option<f32>,
    pub mesh_h: Option<f32>,
    pub reach: u32,
    pub via_mesh: u32,
    pub lat: f64,
    pub lon: f64,
    pub fake: bool,
}

#[derive(Serialize)]
pub struct Status {
    pub t: f32,
    pub label: String,
    pub done: bool,
    pub now: Sample,
    pub counters: Counters,
    pub towers_up: Vec<bool>,
    pub roads: Vec<[bool; 2]>,
    pub sites: Vec<SiteState>,
    pub events: Vec<SimEvent>,
    pub events_total: usize,
    pub series: Vec<Sample>,
    pub series_total: usize,
    pub messages: Vec<MsgInfo>,
    pub messages_total: usize,
    pub sd_demand: f64,
    pub sd_capacity: f64,
    pub depot_water: f64,
}

fn hours(t: f32) -> Option<f32> {
    if t < INF {
        Some(t / 60.0)
    } else {
        None
    }
}

impl Sim {
    pub fn static_info(&self) -> StaticInfo {
        let ll = |p: crate::util::Vec2| self.proj.to_latlon(p);
        StaticInfo {
            id: self.spec.id.clone(),
            name: self.spec.name.clone(),
            description: self.spec.description.clone(),
            start: self.spec.start.clone(),
            duration_h: self.spec.duration_h,
            view: self.spec.view.clone(),
            projection: self.proj,
            params: self.params.clone(),
            sites: self
                .sites
                .iter()
                .zip(&self.spec.sites)
                .map(|(s, sp)| {
                    let p = ll(s.pos);
                    SiteInfo {
                        id: s.id.clone(),
                        name: s.name.clone(),
                        kind: s.kind,
                        lat: p[0],
                        lon: p[1],
                        radius: s.radius,
                        floor: s.floor,
                        capacity: s.capacity,
                        designated: s.designated,
                        peak_observed: s.peak_observed,
                        starlink_from_h: hours(s.starlink_from),
                        note: sp.note.clone(),
                    }
                })
                .collect(),
            settlements: self
                .settlements
                .iter()
                .map(|s| {
                    let p = ll(s.pos);
                    SettlementInfo { name: s.name.clone(), lat: p[0], lon: p[1], pop: s.pop, radius: s.radius }
                })
                .collect(),
            roads: self
                .edges
                .iter()
                .zip(&self.spec.roads)
                .map(|(e, r)| RoadInfo {
                    geom: r.geom.iter().map(|p| [(p[0] * 1e5).round() / 1e5, (p[1] * 1e5).round() / 1e5]).collect(),
                    name: e.name.clone(),
                    a: ll(self.nodes[e.a as usize]),
                    b: ll(self.nodes[e.b as usize]),
                    cut_from_h: hours(e.cut_from),
                    cut_until_h: hours(e.cut_until),
                    foot_ok: e.foot_ok,
                    cause: e.cause.clone(),
                })
                .collect(),
            towers: self
                .towers
                .iter()
                .map(|t| {
                    let p = ll(t.pos);
                    TowerInfo {
                        lat: p[0],
                        lon: p[1],
                        carrier: if t.temporary { None } else { Some(t.carrier) },
                        radius: t.radius,
                        temporary: t.temporary,
                        name: t.name.clone(),
                        from_h: t.from / 60.0,
                        until_h: hours(t.until),
                    }
                })
                .collect(),
            carriers: self.spec.carriers.iter().map(|c| NameId { id: c.id.clone(), name: c.name.clone() }).collect(),
            zones: self.zones.iter().map(|z| NameId { id: z.id.clone(), name: z.name.clone() }).collect(),
            msg_kinds: MsgKind::ALL.iter().map(|&k| KindInfo { id: k, label: k.label(), priority: k.priority() }).collect(),
            sources: self.spec.sources.clone(),
            notes: self.spec.notes.clone(),
            victims: self.victims.len() as u32,
        }
    }

    /// Agents as little-endian records of `x: f32, y: f32, flags: u32` (meters from the origin).
    ///
    /// Flags: 0 phone on, 1 app, 2 online, 3 安否 delivered, 4 knows the focus, 5 trapped, 6 dead,
    /// 7 in a shelter, 8-11 role, 12 moving, 13 off the map, 14 in the tsunami area and not evacuated,
    /// 15 exchanged over the mesh in the last 10 minutes, 16 a car navigation unit running the protocol is on.
    pub fn frame(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(8 + self.agents.len() * 12);
        out.extend_from_slice(&self.t.to_le_bytes());
        out.extend_from_slice(&(self.agents.len() as u32).to_le_bytes());
        let focus_mask = match self.focus {
            Some(crate::sim::Focus::Kind(k)) => Some(&self.kind_mask[k.index()]),
            _ => None,
        };
        let focus_msg = match self.focus {
            Some(crate::sim::Focus::Message(m)) => Some(m),
            _ => None,
        };
        for (i, a) in self.agents.iter().enumerate() {
            let mut f = 0u32;
            if a.phone_on() {
                f |= 1;
            }
            if a.app {
                f |= 1 << 1;
            }
            if a.online {
                f |= 1 << 2;
            }
            if a.anpi_out <= self.t {
                f |= 1 << 3;
            }
            let knows = &self.knows[i];
            let focused = match (focus_mask, focus_msg) {
                (Some(mask), _) => knows.intersects(mask),
                (_, Some(m)) => knows.get(m),
                _ => false,
            };
            if focused {
                f |= 1 << 4;
            }
            match a.health {
                Health::Trapped => f |= 1 << 5,
                Health::Dead => f |= 1 << 6,
                Health::Ok => {}
            }
            if a.site != NONE && self.sites[a.site as usize].kind == crate::spec::SiteKind::Shelter {
                f |= 1 << 7;
            }
            f |= (a.role.code() as u32) << 8;
            if a.moving() {
                f |= 1 << 12;
            }
            if !a.on_map() {
                f |= 1 << 13;
            }
            if a.in_tsunami_zone && !a.tsunami_evacuated {
                f |= 1 << 14;
            }
            if self.t - a.meshed_at <= 10.0 {
                f |= 1 << 15;
            }
            if a.nav && self.nav_active(i as u32) {
                f |= 1 << 16;
            }
            out.extend_from_slice(&a.pos.x.to_le_bytes());
            out.extend_from_slice(&a.pos.y.to_le_bytes());
            out.extend_from_slice(&f.to_le_bytes());
        }
        out
    }

    /// Status for the panels; events and samples are sent from the given cursors on.
    pub fn status(&self, events_from: usize, series_from: usize) -> Status {
        let t = self.t;
        let sp = &self.spec.supply;
        let sites = self
            .sites
            .iter()
            .map(|s| {
                let wday = s.present as f64 * sp.water_l_per_person_day;
                let fday = s.present as f64 * sp.food_per_person_day;
                SiteState {
                    present: s.present,
                    peak: s.peak,
                    water_days: if wday > 0.0 { (s.water / wday) as f32 } else { -1.0 },
                    food_days: if fday > 0.0 { (s.food / fday) as f32 } else { -1.0 },
                    internet: self.site_internet_at(s),
                    starlink: t >= s.starlink_from,
                    known: s.known_initially || s.hq_known || s.ops_known,
                    short: s.present > 0 && (s.water <= 0.0 || s.food <= 0.0),
                    need: s.need_water != NONE || s.need_food != NONE,
                    surplus: s.surplus != NONE,
                    board: s.board.count(),
                }
            })
            .collect();
        let start = self.msgs.len().saturating_sub(60);
        let messages = self.msgs[start..]
            .iter()
            .rev()
            .map(|m| {
                let p = self.proj.to_latlon(m.pos);
                MsgInfo {
                    id: m.id,
                    kind: m.kind,
                    label: m.kind.label(),
                    text: m.text.clone(),
                    created_h: m.created / 60.0,
                    internet_h: hours(m.internet_at),
                    hq_h: hours(m.hq_at),
                    ops_h: hours(m.ops_at),
                    sns_h: hours(m.sns_at),
                    mesh_h: hours(m.first_mesh_at),
                    reach: m.reach,
                    via_mesh: m.via_mesh,
                    lat: p[0],
                    lon: p[1],
                    fake: m.fake,
                }
            })
            .collect();
        Status {
            t,
            label: self.time_label(),
            done: self.done(),
            now: self.compute_sample(),
            counters: self.counters.clone(),
            towers_up: self.towers.iter().map(|t| t.up).collect(),
            roads: self
                .edges
                .iter()
                .map(|e| [e.cut_at(t), e.cut_msg != NONE && self.ops.get(e.cut_msg) && !(e.open_msg != NONE && self.ops.get(e.open_msg))])
                .collect(),
            sites,
            events: self.events.get(events_from..).unwrap_or(&[]).to_vec(),
            events_total: self.events.len(),
            series: self.series.get(series_from..).unwrap_or(&[]).to_vec(),
            series_total: self.series.len(),
            messages,
            messages_total: self.msgs.len(),
            sd_demand: self.sd_demand,
            sd_capacity: self.sd_capacity,
            depot_water: self.depot_water,
        }
    }

    fn site_internet_at(&self, s: &Site) -> bool {
        self.t >= s.starlink_from || self.t >= s.fixed_from
    }
}
