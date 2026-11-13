//! Batch runs over adoption rates and seeds, to measure what the protocol changes.

use std::collections::BTreeMap;
use std::sync::atomic::{AtomicUsize, Ordering};

use rayon::prelude::*;
use serde::{Deserialize, Serialize};

use crate::metrics::Summary;
use crate::sim::Sim;
use crate::spec::{ScenarioSpec, SimParams};

/// Which adoption rate the sweep varies.
#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum SweepTarget {
    /// Everyone (students follow the same rate).
    All,
    /// Residents and staff; students stay at their configured rate.
    Residents,
    /// Students only (the university app on 川内キャンパス).
    Students,
    /// Car navigation units only; phone adoption stays as configured.
    Carnav,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct SweepRequest {
    pub adoptions: Vec<f64>,
    pub seeds: u32,
    pub target: SweepTarget,
    /// Stop each run after this many hours (defaults to the scenario's duration).
    #[serde(default)]
    pub hours: Option<f64>,
}

#[derive(Clone, Debug, Serialize)]
pub struct SweepPoint {
    pub adoption: f64,
    pub mean: BTreeMap<String, f64>,
    pub min: BTreeMap<String, f64>,
    pub max: BTreeMap<String, f64>,
    pub runs: Vec<Summary>,
}

/// Numeric indicators compared across adoption rates (Japanese labels are added by the UI).
pub fn indicators(s: &Summary) -> Vec<(&'static str, f64)> {
    let h = |x: Option<f32>| x.map(|v| v as f64).unwrap_or(f64::NAN);
    vec![
        ("trapped_dead", s.trapped_dead as f64),
        ("rescued_by_team", s.rescued_by_team as f64),
        ("trapped_to_hq_median_h", h(s.trapped_to_hq.median_h)),
        ("trapped_reached_hq", s.trapped_to_hq.reached as f64 / s.trapped_to_hq.count.max(1) as f64),
        ("needs_to_ops_median_h", h(s.needs_to_ops.median_h)),
        ("road_cut_to_ops_median_h", h(s.road_cut_to_ops.median_h)),
        ("isolated_to_ops_median_h", h(s.isolated_to_ops.median_h)),
        ("anpi_24h", s.anpi_24h as f64),
        ("anpi_72h", s.anpi_72h as f64),
        ("anpi_7d", s.anpi_7d as f64),
        ("anpi_hq_24h", s.anpi_hq_24h as f64),
        ("anpi_hq_72h", s.anpi_hq_72h as f64),
        ("warned_at_arrival", s.warned_at_arrival as f64),
        ("shortage_shelter_hours", s.shortage_shelter_hours),
        ("concentration_events", s.concentration_events as f64),
        ("fake_dispatches", s.fake_dispatches as f64),
        ("unknown_cut_hits", s.unknown_cut_hits as f64),
        ("isolated_unknown_events", s.isolated_unknown_events as f64),
        ("shelters_unknown_events", s.shelters_unknown_events as f64),
        ("mesh_exchanges", s.mesh_exchanges as f64),
        ("nav_exchanges", s.nav_exchanges as f64),
        ("mesh_missed_share", s.mesh_missed as f64 / (s.mesh_missed + s.mesh_exchanges).max(1) as f64),
    ]
}

pub fn run_sweep(spec: &ScenarioSpec, base: &SimParams, req: &SweepRequest, progress: impl Fn(usize, usize) + Sync) -> Vec<SweepPoint> {
    let jobs: Vec<(usize, f64, u64)> = req
        .adoptions
        .iter()
        .enumerate()
        .flat_map(|(ai, &a)| (0..req.seeds).map(move |s| (ai, a, s as u64 + 1)))
        .collect();
    let total = jobs.len();
    let done = AtomicUsize::new(0);
    let results: Vec<(usize, Summary)> = jobs
        .par_iter()
        .map(|&(ai, adoption, seed)| {
            let mut p = base.clone();
            p.seed = base.seed.wrapping_mul(1000).wrapping_add(seed);
            match req.target {
                SweepTarget::All => {
                    p.adoption = adoption;
                    p.adoption_students = adoption;
                    p.staff_adoption = adoption;
                }
                SweepTarget::Residents => {
                    p.adoption = adoption;
                    p.staff_adoption = adoption;
                }
                SweepTarget::Students => p.adoption_students = adoption,
                SweepTarget::Carnav => p.carnav_adoption = adoption,
            }
            let mut sim = Sim::new(spec.clone(), p);
            let end = req.hours.map(|h| (h * 60.0) as f32).unwrap_or(sim.end).min(sim.end);
            while sim.t < end {
                sim.step();
            }
            let mut s = sim.summary();
            s.adoption = adoption;
            progress(done.fetch_add(1, Ordering::Relaxed) + 1, total);
            (ai, s)
        })
        .collect();
    req.adoptions
        .iter()
        .enumerate()
        .map(|(ai, &adoption)| {
            let runs: Vec<Summary> = results.iter().filter(|r| r.0 == ai).map(|r| r.1.clone()).collect();
            let mut mean = BTreeMap::new();
            let mut min = BTreeMap::new();
            let mut max = BTreeMap::new();
            for r in &runs {
                for (k, v) in indicators(r) {
                    if v.is_nan() {
                        continue;
                    }
                    *mean.entry(k.to_string()).or_insert(0.0) += v;
                    let lo = min.entry(k.to_string()).or_insert(f64::INFINITY);
                    *lo = f64::min(*lo, v);
                    let hi = max.entry(k.to_string()).or_insert(f64::NEG_INFINITY);
                    *hi = f64::max(*hi, v);
                }
            }
            for (k, v) in mean.iter_mut() {
                let n = runs.iter().filter(|r| indicators(r).iter().any(|(kk, vv)| kk == k && !vv.is_nan())).count();
                *v /= n.max(1) as f64;
            }
            SweepPoint { adoption, mean, min, max, runs }
        })
        .collect()
}
