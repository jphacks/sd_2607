//! Hourly samples and the end-of-run summary used to compare adoption rates.

use serde::Serialize;

use crate::model::*;
use crate::sim::{Sample, Sim, INF};
use crate::spec::SiteKind;

#[derive(Clone, Debug, Default, Serialize)]
pub struct Latency {
    pub count: u32,
    pub reached: u32,
    pub median_h: Option<f32>,
    pub p90_h: Option<f32>,
}

fn latency(mut v: Vec<f32>, total: u32) -> Latency {
    v.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let q = |p: f32| v.get(((v.len() as f32 - 1.0) * p).round() as usize).map(|x| x / 60.0);
    Latency { count: total, reached: v.len() as u32, median_h: q(0.5), p90_h: q(0.9) }
}

/// Outcome of one run, the unit of comparison between adoption rates.
#[derive(Clone, Debug, Default, Serialize)]
pub struct Summary {
    pub adoption: f64,
    pub seed: u64,
    pub population: u32,
    pub app_users: u32,
    pub trapped: u32,
    pub trapped_dead: u32,
    pub rescued_by_team: u32,
    pub rescued_by_neighbors: u32,
    pub found_by_search: u32,
    /// From trapping to the municipal HQ or the coordination knowing it.
    pub trapped_to_hq: Latency,
    /// Shelter needs (water/food) from creation to the coordination.
    pub needs_to_ops: Latency,
    pub road_cut_to_ops: Latency,
    pub isolated_to_ops: Latency,
    pub anpi_24h: f32,
    pub anpi_72h: f32,
    pub anpi_7d: f32,
    pub anpi_end: f32,
    /// Share of people whose 安否 the HQ knows (university 安否確認 / municipal 安否不明 list).
    pub anpi_hq_24h: f32,
    pub anpi_hq_72h: f32,
    pub anpi_hq_end: f32,
    pub warned_at_arrival: f32,
    pub tsunami_exposed: u32,
    pub tsunami_exposed_unwarned: u32,
    pub shortage_shelter_hours: f64,
    pub concentration_events: u32,
    pub fake_dispatches: u32,
    pub unknown_cut_hits: u32,
    pub wasted_vehicle_hours: f64,
    pub isolated_unknown_events: u32,
    pub shelters_unknown_events: u32,
    pub mesh_exchanges: u64,
    pub mesh_missed: u64,
    pub mesh_truncated: u64,
    pub nav_exchanges: u64,
    pub nav_uploads: u64,
    pub gateway_uploads: u64,
    pub sd_dropped: u64,
    pub events_by_kind: Vec<(String, u32)>,
}

impl Sim {
    fn anpi_ratio_at(&self, t: f32) -> f32 {
        let (mut n, mut ok) = (0u32, 0u32);
        for a in &self.agents {
            if a.role.is_population() && a.family_outside && a.alive() {
                n += 1;
                if a.anpi_out <= t {
                    ok += 1;
                }
            }
        }
        if n == 0 {
            0.0
        } else {
            ok as f32 / n as f32
        }
    }

    fn anpi_hq_ratio_at(&self, t: f32) -> f32 {
        let (mut n, mut ok) = (0u32, 0u32);
        for a in &self.agents {
            if a.role.is_population() && a.role != Role::ShelterStaff && a.alive() {
                n += 1;
                if a.anpi_hq <= t {
                    ok += 1;
                }
            }
        }
        if n == 0 {
            0.0
        } else {
            ok as f32 / n as f32
        }
    }

    pub(crate) fn sample(&mut self) {
        let s = self.compute_sample();
        self.series.push(s);
        self.flag_anpi();
    }

    pub fn compute_sample(&self) -> Sample {
        let t = self.t;
        let mut s = Sample { t_h: t / 60.0, ..Default::default() };
        let (mut phones, mut alive_phones, mut online) = (0u32, 0u32, 0u32);
        let (mut zone_people, mut warned) = (0u32, 0u32);
        for a in &self.agents {
            if !a.role.is_population() || !a.alive() {
                continue;
            }
            s.population += 1;
            if a.phone {
                phones += 1;
                if a.battery > 0.0 {
                    alive_phones += 1;
                }
                if a.online {
                    online += 1;
                }
            }
            if a.settlement != NONE && self.spec.settlements[a.settlement as usize].tsunami_zone > 0.0 || a.settlement == NONE {
                zone_people += 1;
                if a.warned_at <= t {
                    warned += 1;
                }
            }
        }
        s.anpi_ratio = self.anpi_ratio_at(t);
        s.anpi_hq_ratio = self.anpi_hq_ratio_at(t);
        s.warned_ratio = if zone_people > 0 { warned as f32 / zone_people as f32 } else { 0.0 };
        s.phones_alive = if phones > 0 { alive_phones as f32 / phones as f32 } else { 0.0 };
        s.online_ratio = if phones > 0 { online as f32 / phones as f32 } else { 0.0 };
        let fixed: Vec<&Tower> = self.towers.iter().filter(|t| !t.temporary).collect();
        s.towers_up = if fixed.is_empty() { 1.0 } else { fixed.iter().filter(|t| t.up).count() as f32 / fixed.len() as f32 };
        for v in &self.victims {
            match v.outcome {
                VictimOutcome::Pending => {
                    s.trapped_pending += 1;
                    let known = self.msgs.get(v.msg as usize).map(|m| m.ops_at <= t || m.hq_at <= t);
                    if known == Some(true) {
                        s.trapped_known_ops += 1;
                    }
                }
                VictimOutcome::Dead => s.trapped_dead += 1,
                _ => s.rescued += 1,
            }
        }
        for site in &self.sites {
            if site.kind != SiteKind::Shelter || site.present == 0 {
                continue;
            }
            s.shelters_open += 1;
            s.evacuees += site.present;
            if site.water <= 0.0 || site.food <= 0.0 {
                s.shelters_short += 1;
            }
            if !(site.known_initially || site.hq_known || site.ops_known) {
                s.shelters_unknown += 1;
            }
        }
        for st in &self.settlements {
            if st.isolated_since <= t {
                s.isolated_settlements += 1;
                s.isolated_people += st.pop;
                let m = st.isolated_msg;
                if m == NONE || (self.msgs[m as usize].ops_at > t && self.msgs[m as usize].hq_at > t) {
                    s.isolated_unknown += 1;
                }
            }
        }
        s.msgs = self.msgs.len() as u32;
        s.msgs_internet = self.internet.count();
        s.msgs_ops = self.ops.count();
        s.mesh_exchanges = self.counters.mesh_exchanges;
        s.gateways = self.agents.iter().filter(|a| a.app && a.online).count() as u32;
        s.sd_load = if self.sd_capacity > 0.0 { (self.sd_demand / self.sd_capacity) as f32 } else { 0.0 };
        s.starlink_load = self.starlink_load;
        s
    }

    fn flag_anpi(&mut self) {
        let t = self.t;
        if t >= 72.0 * 60.0 && !self.anpi_flagged {
            self.anpi_flagged = true;
            let unknown = self
                .agents
                .iter()
                .filter(|a| a.role.is_population() && a.family_outside && a.alive() && a.anpi_out > t)
                .count() as u32;
            if unknown > 0 {
                let pos = self.proj.to_xy(self.spec.view.center);
                let total = self.agents.iter().filter(|a| a.role.is_population() && a.family_outside && a.alive()).count();
                self.event(
                    EventKind::AnpiUnknownLong,
                    pos,
                    unknown,
                    format!("発災72時間後も{unknown}人（{:.0}%）の安否が家族に届いていない", unknown as f32 / total.max(1) as f32 * 100.0),
                );
            }
        }
    }

    pub fn summary(&self) -> Summary {
        let t = self.t;
        let c = &self.counters;
        let mut trapped_lat = Vec::new();
        for v in &self.victims {
            if v.msg == NONE {
                continue;
            }
            let m = &self.msgs[v.msg as usize];
            let known = m.ops_at.min(m.hq_at);
            if known < INF {
                trapped_lat.push(known);
            }
        }
        let lat_of = |kinds: &[MsgKind]| {
            let mut v = Vec::new();
            let mut n = 0;
            for m in &self.msgs {
                if kinds.contains(&m.kind) {
                    n += 1;
                    if m.ops_at < INF {
                        v.push(m.ops_at - m.created);
                    }
                }
            }
            latency(v, n)
        };
        let mut by_kind: std::collections::BTreeMap<&str, u32> = Default::default();
        for e in &self.events {
            *by_kind.entry(e.label).or_default() += 1;
        }
        Summary {
            adoption: self.params.adoption,
            seed: self.params.seed,
            population: self.agents.iter().filter(|a| a.role.is_population()).count() as u32,
            app_users: self.agents.iter().filter(|a| a.role.is_population() && a.app).count() as u32,
            trapped: self.victims.len() as u32,
            trapped_dead: c.trapped_dead,
            rescued_by_team: c.rescued_by_team,
            rescued_by_neighbors: c.rescued_by_neighbors,
            found_by_search: c.found_by_search,
            trapped_to_hq: latency(trapped_lat, self.victims.len() as u32),
            needs_to_ops: lat_of(&[MsgKind::NeedWater, MsgKind::NeedFood]),
            road_cut_to_ops: lat_of(&[MsgKind::RoadCut]),
            isolated_to_ops: lat_of(&[MsgKind::Isolated]),
            anpi_24h: self.anpi_ratio_at(24.0 * 60.0),
            anpi_72h: self.anpi_ratio_at(72.0 * 60.0),
            anpi_7d: self.anpi_ratio_at(168.0 * 60.0),
            anpi_end: self.anpi_ratio_at(t),
            anpi_hq_24h: self.anpi_hq_ratio_at(24.0 * 60.0),
            anpi_hq_72h: self.anpi_hq_ratio_at(72.0 * 60.0),
            anpi_hq_end: self.anpi_hq_ratio_at(t),
            warned_at_arrival: if c.tsunami_exposed > 0 { 1.0 - c.tsunami_exposed_unwarned as f32 / c.tsunami_exposed as f32 } else { 1.0 },
            tsunami_exposed: c.tsunami_exposed,
            tsunami_exposed_unwarned: c.tsunami_exposed_unwarned,
            shortage_shelter_hours: self
                .sites
                .iter()
                .map(|s| s.short_hours + if s.water_short_since < INF { ((t - s.water_short_since) / 60.0) as f64 } else { 0.0 })
                .sum(),
            concentration_events: c.concentration_events,
            fake_dispatches: c.fake_dispatches,
            unknown_cut_hits: c.unknown_cut_hits,
            wasted_vehicle_hours: c.wasted_vehicle_min / 60.0,
            isolated_unknown_events: self.events.iter().filter(|e| e.kind == EventKind::IsolatedUnknown).count() as u32,
            shelters_unknown_events: self.events.iter().filter(|e| e.kind == EventKind::ShelterUnknown).count() as u32,
            mesh_exchanges: c.mesh_exchanges,
            mesh_missed: c.mesh_missed,
            mesh_truncated: c.mesh_truncated,
            nav_exchanges: c.nav_exchanges,
            nav_uploads: c.nav_uploads,
            gateway_uploads: c.gateway_uploads,
            sd_dropped: c.sd_dropped,
            events_by_kind: by_kind.into_iter().map(|(k, v)| (k.to_string(), v)).collect(),
        }
    }
}
