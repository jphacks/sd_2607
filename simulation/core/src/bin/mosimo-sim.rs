//! Headless runs: `mosimo-sim <scenario> [adoption ...] [--seeds N] [--hours H] [--scale S]`.
//! Prints one JSON summary per adoption rate (mean over seeds) to stdout.

use std::time::Instant;

use mosimo_sim_core::spec::builtin_scenarios;
use mosimo_sim_core::sweep::{run_sweep, SweepRequest, SweepTarget};
use mosimo_sim_core::{Sim, SimParams};

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let Some(id) = args.first() else {
        eprintln!("usage: mosimo-sim <shelter|kawauchi|noto> [adoption ...] [--seeds N] [--hours H] [--scale S] [--direct]");
        std::process::exit(2);
    };
    let spec = builtin_scenarios().into_iter().find(|s| &s.id == id).unwrap_or_else(|| {
        eprintln!("unknown scenario {id}");
        std::process::exit(2);
    });
    let mut params = SimParams::for_scenario(&spec);
    let mut adoptions = Vec::new();
    let mut seeds = 1;
    let mut hours = None;
    let mut target = SweepTarget::All;
    let mut trace = false;
    let mut series = false;
    let mut it = args.iter().skip(1);
    while let Some(a) = it.next() {
        match a.as_str() {
            "--seeds" => seeds = it.next().and_then(|v| v.parse().ok()).unwrap_or(1),
            "--hours" => hours = it.next().and_then(|v| v.parse().ok()),
            "--scale" => params.population_scale = it.next().and_then(|v| v.parse().ok()).unwrap_or(1.0),
            "--direct" => params.starlink_direct = true,
            "--rescue" => params.rescue_capacity_factor = it.next().and_then(|v| v.parse().ok()).unwrap_or(1.0),
            "--direct-data" => {
                params.starlink_direct = true;
                params.starlink_direct_data = true;
            }
            "--students" => target = SweepTarget::Students,
            "--carnav-sweep" => target = SweepTarget::Carnav,
            "--carnav" => params.carnav_adoption = it.next().and_then(|v| v.parse().ok()).unwrap_or(0.0),
            "--adoption" => params.adoption = it.next().and_then(|v| v.parse().ok()).unwrap_or(0.0),
            "--trace" => trace = true,
            "--series" => series = true,
            "--residents" => target = SweepTarget::Residents,
            v => adoptions.push(v.parse().expect("adoption must be a number")),
        }
    }
    if adoptions.is_empty() {
        adoptions.push(params.adoption);
    }
    if trace {
        // One run per adoption rate; events grouped by kind and cause (the text before the timing).
        for &a in &adoptions {
            let mut p = params.clone();
            p.adoption = a;
            let mut sim = Sim::new(spec.clone(), p);
            let end = hours.map(|h| (h * 60.0) as f32).unwrap_or(sim.end);
            while sim.t < end {
                sim.step();
            }
            let mut groups: std::collections::BTreeMap<String, u32> = Default::default();
            for e in &sim.events {
                let cause = e.text.split(['（', ':']).nth(1).unwrap_or(&e.text).to_string();
                *groups.entry(format!("{} / {}", e.label, cause)).or_default() += 1;
            }
            println!("adoption {a}: trapped {} victims", sim.victims.len());
            if series {
                println!("  t_h  evacuees  isolated(areas/people)  towers_up  trapped(pending/rescued/dead)");
                for s in sim.series.iter().filter(|s| [6.0, 24.0, 48.0, 72.0, 96.0, 168.0, 240.0, 336.0, 504.0].contains(&s.t_h.round())) {
                    println!("  {:4.0}  {:8}  {:4}/{:6}  {:4.0}%  {}/{}/{}", s.t_h, s.evacuees, s.isolated_settlements, s.isolated_people, s.towers_up * 100.0, s.trapped_pending, s.rescued, s.trapped_dead);
                }
            }
            let mut outcomes: std::collections::BTreeMap<String, u32> = Default::default();
            for v in &sim.victims {
                let key = format!("{} {:?}", if v.severe { "重傷" } else { "軽傷" }, v.outcome);
                *outcomes.entry(key).or_default() += 1;
            }
            for (k, v) in outcomes {
                println!("  {v:5}  {k}");
            }
            for (k, v) in groups {
                println!("  {v:5}  {k}");
            }
        }
        return;
    }
    let req = SweepRequest { adoptions, seeds, target, hours };
    let start = Instant::now();
    let points = run_sweep(&spec, &params, &req, |d, n| eprint!("\r{d}/{n}"));
    eprintln!("\rdone in {:.1}s", start.elapsed().as_secs_f64());
    for p in points {
        println!("{}", serde_json::json!({ "adoption": p.adoption, "mean": p.mean, "events": p.runs.first().map(|r| &r.events_by_kind) }));
    }
}
