//! The experiment suite: fixed comparisons across scenarios, seeds run in parallel.
//! `experiments <out_dir> [--quick] [--only name,...] [--seeds n] [--noto-hours h]` writes results.json and report.md.

use std::collections::BTreeMap;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Instant;

use mosimo_sim_core::spec::builtin_scenarios;
use mosimo_sim_core::sweep::indicators;
use mosimo_sim_core::{ScenarioSpec, Sim, SimParams, Summary};
use rayon::prelude::*;
use serde::Serialize;

type Tweak = fn(&mut SimParams, f64);

struct Experiment {
    name: &'static str,
    title: &'static str,
    scenario: &'static str,
    /// Label of the varied value, and the values.
    axis: &'static str,
    values: Vec<f64>,
    base: Tweak,
    vary: Tweak,
    /// Simulated hours (None: the whole scenario) and seeds (None: the command-line default).
    hours: Option<f64>,
    seeds: Option<u32>,
}

#[derive(Serialize)]
struct Row {
    value: f64,
    mean: BTreeMap<String, f64>,
    min: BTreeMap<String, f64>,
    max: BTreeMap<String, f64>,
}

#[derive(Serialize)]
struct Result {
    name: &'static str,
    title: &'static str,
    scenario: &'static str,
    axis: &'static str,
    seeds: u32,
    hours: Option<f64>,
    rows: Vec<Row>,
    minutes: f64,
}

fn none(_: &mut SimParams, _: f64) {}

fn experiments() -> Vec<Experiment> {
    let adoption = vec![0.0, 0.1, 0.3, 0.5, 0.7, 0.9];
    vec![
        Experiment { name: "shelter_adoption", title: "避難所: スマホの導入率", scenario: "shelter", axis: "導入率", values: adoption.clone(), base: none,
            vary: |p, v| { p.adoption = v; p.staff_adoption = v; }, hours: None, seeds: None },
        Experiment { name: "kawauchi_students", title: "川内: 学生の導入率（とんペディア）", scenario: "kawauchi", axis: "学生の導入率", values: adoption.clone(), base: none,
            vary: |p, v| p.adoption_students = v, hours: None, seeds: None },
        Experiment { name: "noto_adoption", title: "能登: スマホの導入率", scenario: "noto", axis: "導入率", values: adoption.clone(), base: none,
            vary: |p, v| { p.adoption = v; p.staff_adoption = v; }, hours: None, seeds: None },
        Experiment { name: "noto_carnav_only", title: "能登: カーナビだけ（スマホ0%）", scenario: "noto", axis: "対応カーナビの普及率", values: vec![0.0, 0.2, 0.4, 0.6, 0.8],
            base: |p, _| { p.adoption = 0.0; p.staff_adoption = 0.0; }, vary: |p, v| p.carnav_adoption = v, hours: Some(168.0), seeds: Some(2) },
        Experiment { name: "noto_carnav_with_phones", title: "能登: カーナビ（スマホ30%に加えて）", scenario: "noto", axis: "対応カーナビの普及率", values: vec![0.0, 0.2, 0.4, 0.6, 0.8],
            base: |p, _| { p.adoption = 0.3; p.staff_adoption = 0.3; }, vary: |p, v| p.carnav_adoption = v, hours: Some(168.0), seeds: Some(2) },
        Experiment { name: "shelter_carnav_only", title: "避難所: カーナビだけ（スマホ0%）", scenario: "shelter", axis: "対応カーナビの普及率", values: vec![0.0, 0.2, 0.4, 0.6, 0.8],
            base: |p, _| { p.adoption = 0.0; p.staff_adoption = 0.0; }, vary: |p, v| p.carnav_adoption = v, hours: None, seeds: None },
        Experiment { name: "noto_indoor_range", title: "能登: 屋内の到達距離（導入率50%）", scenario: "noto", axis: "屋内の到達距離(m)", values: vec![5.0, 10.0, 15.0, 25.0],
            base: |p, _| { p.adoption = 0.5; p.staff_adoption = 0.5; }, vary: |p, v| p.mesh_range_indoor_m = v, hours: Some(168.0), seeds: Some(2) },
        Experiment { name: "noto_ios_pair", title: "能登: iPhone同士の発見時間（導入率50%）", scenario: "noto", axis: "発見までの平均(秒)", values: vec![30.0, 120.0, 600.0, 3600.0],
            base: |p, _| { p.adoption = 0.5; p.staff_adoption = 0.5; }, vary: |p, v| p.discovery_ios_pair_s = v, hours: Some(168.0), seeds: Some(2) },
        Experiment { name: "noto_link_speed", title: "能登: 接触中の通信速度（導入率50%）", scenario: "noto", axis: "kbps", values: vec![20.0, 100.0, 1000.0],
            base: |p, _| { p.adoption = 0.5; p.staff_adoption = 0.5; }, vary: |p, v| p.mesh_kbps = v, hours: Some(168.0), seeds: Some(2) },
        Experiment { name: "noto_rescue_capacity", title: "能登: 救助隊の規模 × 導入率90%", scenario: "noto", axis: "救助隊の倍率", values: vec![1.0, 2.0, 3.0],
            base: |p, _| { p.adoption = 0.9; p.staff_adoption = 0.9; }, vary: |p, v| p.rescue_capacity_factor = v, hours: Some(168.0), seeds: Some(2) },
        Experiment { name: "noto_rescue_capacity_0", title: "能登: 救助隊の規模 × 導入率0%", scenario: "noto", axis: "救助隊の倍率", values: vec![1.0, 2.0, 3.0],
            base: |p, _| { p.adoption = 0.0; p.staff_adoption = 0.0; }, vary: |p, v| p.rescue_capacity_factor = v, hours: Some(168.0), seeds: Some(2) },
        Experiment { name: "noto_direct", title: "能登: Starlink Direct（0なし・1 SMS・2 SMS+データ）、導入率30%", scenario: "noto", axis: "Starlink Direct", values: vec![0.0, 1.0, 2.0],
            base: |p, _| { p.adoption = 0.3; p.staff_adoption = 0.3; },
            vary: |p, v| { p.starlink_direct = v >= 1.0; p.starlink_direct_data = v >= 2.0; }, hours: Some(168.0), seeds: Some(2) },
        Experiment { name: "noto_features", title: "能登: プロトコルの機能を外す（導入率50%）", scenario: "noto", axis: "0全部 1AppViewなし 2名簿集約なし 3優先度なし", values: vec![0.0, 1.0, 2.0, 3.0],
            base: |p, _| { p.adoption = 0.5; p.staff_adoption = 0.5; p.starlink_direct = true; p.starlink_direct_data = true; },
            vary: |p, v| { p.hq_appview = v != 1.0; p.roster_aggregation = v != 2.0; p.priority = v != 3.0; }, hours: Some(168.0), seeds: Some(2) },
        Experiment { name: "noto_starlink_congestion", title: "能登: Starlinkの衛星セル容量（導入率50%）", scenario: "noto", axis: "容量の倍率", values: vec![0.05, 0.2, 1.0],
            base: |p, _| { p.adoption = 0.5; p.staff_adoption = 0.5; }, vary: |p, v| p.starlink_capacity_factor = v, hours: Some(168.0), seeds: Some(2) },
    ]
}

fn run_one(spec: &ScenarioSpec, p: SimParams, hours: Option<f64>) -> Summary {
    let mut sim = Sim::new(spec.clone(), p);
    let end = hours.map(|h| (h * 60.0) as f32).unwrap_or(sim.end).min(sim.end);
    while sim.t < end {
        sim.step();
    }
    sim.summary()
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let out = std::path::PathBuf::from(args.first().cloned().unwrap_or_else(|| "experiments".into()));
    let quick = args.iter().any(|a| a == "--quick");
    let only: Option<Vec<String>> = args.iter().position(|a| a == "--only").and_then(|i| args.get(i + 1)).map(|s| s.split(',').map(String::from).collect());
    // Caps the simulated hours of the Noto runs, which take hours each over the whole period.
    let noto_hours: Option<f64> = args.iter().position(|a| a == "--noto-hours").and_then(|i| args.get(i + 1)).and_then(|s| s.parse().ok());
    let seeds: u32 = args.iter().position(|a| a == "--seeds").and_then(|i| args.get(i + 1)).and_then(|s| s.parse().ok()).unwrap_or(if quick { 1 } else { 3 });
    std::fs::create_dir_all(&out).expect("create output directory");
    let specs: BTreeMap<String, ScenarioSpec> = builtin_scenarios().into_iter().map(|s| (s.id.clone(), s)).collect();
    let list: Vec<Experiment> = experiments().into_iter().filter(|e| only.as_ref().map(|o| o.iter().any(|n| n == e.name)).unwrap_or(true)).collect();
    let mut results = Vec::new();
    for e in &list {
        let start = Instant::now();
        let spec = &specs[e.scenario];
        let seeds = e.seeds.map(|s| s.min(seeds)).unwrap_or(seeds);
        let hours = match (e.scenario, noto_hours) {
            ("noto", Some(cap)) => Some(e.hours.map_or(cap, |h| h.min(cap))),
            _ => e.hours,
        };
        let jobs: Vec<(usize, u64)> = (0..e.values.len()).flat_map(|i| (1..=seeds as u64).map(move |s| (i, s))).collect();
        let done = AtomicUsize::new(0);
        let runs: Vec<(usize, Summary)> = jobs
            .par_iter()
            .map(|&(i, seed)| {
                let mut p = SimParams::for_scenario(spec);
                if quick && e.scenario == "noto" {
                    p.population_scale = 0.25;
                }
                p.seed = seed;
                (e.base)(&mut p, e.values[i]);
                (e.vary)(&mut p, e.values[i]);
                let s = run_one(spec, p, hours);
                eprint!("\r{}: {}/{}", e.name, done.fetch_add(1, Ordering::Relaxed) + 1, jobs.len());
                (i, s)
            })
            .collect();
        let rows = e
            .values
            .iter()
            .enumerate()
            .map(|(i, &value)| {
                let mut sum: BTreeMap<String, (f64, u32, f64, f64)> = BTreeMap::new();
                for (_, s) in runs.iter().filter(|r| r.0 == i) {
                    for (k, v) in indicators(s) {
                        if v.is_finite() {
                            let x = sum.entry(k.to_string()).or_insert((0.0, 0, f64::INFINITY, f64::NEG_INFINITY));
                            x.0 += v;
                            x.1 += 1;
                            x.2 = x.2.min(v);
                            x.3 = x.3.max(v);
                        }
                    }
                }
                Row {
                    value,
                    mean: sum.iter().map(|(k, v)| (k.clone(), v.0 / v.1 as f64)).collect(),
                    min: sum.iter().map(|(k, v)| (k.clone(), v.2)).collect(),
                    max: sum.iter().map(|(k, v)| (k.clone(), v.3)).collect(),
                }
            })
            .collect();
        let minutes = start.elapsed().as_secs_f64() / 60.0;
        eprintln!("\r{}: done in {:.1} min", e.name, minutes);
        results.push(Result { name: e.name, title: e.title, scenario: e.scenario, axis: e.axis, seeds, hours, rows, minutes });
        // Save as we go: a long suite should not lose finished experiments.
        std::fs::write(out.join("results.json"), serde_json::to_string_pretty(&results).unwrap()).unwrap();
        std::fs::write(out.join("report.md"), report(&results)).unwrap();
    }
}

const SHOWN: [(&str, &str, &str); 16] = [
    ("trapped_dead", "救助が間に合わなかった人", "n"),
    ("trapped_to_hq_median_h", "要救助→本部（中央値, 時間）", "h"),
    ("trapped_reached_hq", "要救助が本部に届いた割合", "%"),
    ("anpi_24h", "家族に安否（24h）", "%"),
    ("anpi_72h", "家族に安否（72h）", "%"),
    ("anpi_hq_24h", "本部が安否を把握（24h）", "%"),
    ("anpi_hq_72h", "本部が安否を把握（72h）", "%"),
    ("needs_to_ops_median_h", "避難所の不足→県（時間）", "h"),
    ("road_cut_to_ops_median_h", "道路寸断→県（時間）", "h"),
    ("isolated_to_ops_median_h", "孤立集落→県（時間）", "h"),
    ("isolated_unknown_events", "48h把握されない孤立集落", "n"),
    ("shortage_shelter_hours", "物資が尽きた避難所×時間", "n"),
    ("unknown_cut_hits", "寸断を知らず立ち往生", "n"),
    ("mesh_exchanges", "メッシュ交換", "n"),
    ("nav_exchanges", "うちカーナビ", "n"),
    ("mesh_missed_share", "接触したが交換できなかった割合", "%"),
];

fn fmt(unit: &str, v: Option<&f64>) -> String {
    match v {
        None => "—".into(),
        Some(v) if !v.is_finite() => "—".into(),
        Some(v) => match unit {
            "%" => format!("{:.0}%", v * 100.0),
            "h" => format!("{v:.2}"),
            _ if v.abs() >= 100.0 => format!("{v:.0}"),
            _ => format!("{v:.1}"),
        },
    }
}

fn report(results: &[Result]) -> String {
    let mut s = String::from("# 実験結果\n\n各値はシードの平均。括弧内はシード間の最小〜最大。\n");
    for r in results {
        let span = r.hours.map(|h| format!("発災から{h:.0}時間")).unwrap_or_else(|| "全期間".into());
        s += &format!("\n## {}\n\nシナリオ `{}`、{}、シード {}、計算 {:.1} 分\n\n| 指標 |", r.title, r.scenario, span, r.seeds, r.minutes);
        for row in &r.rows {
            s += &format!(" {} = {} |", r.axis, row.value);
        }
        s += "\n|---|";
        for _ in &r.rows {
            s += "---|";
        }
        s += "\n";
        for (k, label, unit) in SHOWN {
            if r.rows.iter().all(|row| !row.mean.contains_key(k)) {
                continue;
            }
            s += &format!("| {label} |");
            for row in &r.rows {
                let (lo, hi) = (row.min.get(k), row.max.get(k));
                let spread = match (lo, hi) {
                    (Some(a), Some(b)) if r.seeds > 1 && (b - a).abs() > 1e-9 => format!(" ({}〜{})", fmt(unit, Some(a)), fmt(unit, Some(b))),
                    _ => String::new(),
                };
                s += &format!(" {}{} |", fmt(unit, row.mean.get(k)), spread);
            }
            s += "\n";
        }
    }
    s
}
