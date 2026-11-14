//! Headless runs: `mosimo-sim <scenario> [adoption ...] [--seeds N] [--hours H] [--scale S]`.
//! Prints one JSON summary per adoption rate (mean over seeds) to stdout.

use std::time::Instant;

use mosimo_sim_core::spec::{builtin_scenarios, SiteKind};
use mosimo_sim_core::sweep::{run_sweep, SweepRequest, SweepTarget};
use mosimo_sim_core::{Sim, SimParams};

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let Some(id) = args.first() else {
        eprintln!("usage: mosimo-sim <shelter|kawauchi|noto> [adoption ...] [--seeds N] [--hours H] [--scale S] [--direct]");
        std::process::exit(2);
    };
    let mut spec = builtin_scenarios().into_iter().find(|s| &s.id == id).unwrap_or_else(|| {
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
    let mut diag = false;
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
            "--diag" => {
                trace = true;
                diag = true;
            }
            // Multiplies the Starlink Direct beam capacity.
            "--beam" => spec.starlink_direct.beam_msgs_per_min *= it.next().and_then(|v| v.parse::<f64>().ok()).unwrap_or(1.0),
            // Brings every site's Starlink forward to at most H hours after the quake.
            "--starlink-by" => {
                let h: f64 = it.next().and_then(|v| v.parse().ok()).unwrap_or(0.0);
                for site in &mut spec.sites {
                    if site.kind == SiteKind::Shelter || site.kind == SiteKind::Hq {
                        site.starlink_from_h = Some(site.starlink_from_h.map_or(h, |x| x.min(h)));
                    }
                }
            }
            "--starlink-cap" => params.starlink_capacity_factor = it.next().and_then(|v| v.parse().ok()).unwrap_or(1.0),
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
            // Hourly watch: Starlink Direct demand, and when unknown shelters fill and become known.
            let mut sd_peak = 0.0f64;
            let mut sd_over_steps = 0u32;
            let mut sl_peak = 0.0f32;
            let mut shelter_seen: Vec<(f32, f32)> = vec![(f32::INFINITY, f32::INFINITY); sim.sites.len()];
            let mut next_h = 0.0f32;
            while sim.t < end {
                sim.step();
                sd_peak = sd_peak.max(if sim.sd_capacity > 0.0 { sim.sd_demand / sim.sd_capacity } else { 0.0 });
                sd_over_steps += (sim.sd_capacity > 0.0 && sim.sd_demand > sim.sd_capacity) as u32;
                sl_peak = sl_peak.max(sim.starlink_load);
                if diag && sim.t >= next_h {
                    next_h += 60.0;
                    for (k, site) in sim.sites.iter().enumerate() {
                        if site.kind != SiteKind::Shelter || site.known_initially {
                            continue;
                        }
                        if site.present >= 10 && shelter_seen[k].0.is_infinite() {
                            shelter_seen[k].0 = sim.t;
                        }
                        if (site.hq_known || site.ops_known) && shelter_seen[k].1.is_infinite() {
                            shelter_seen[k].1 = sim.t;
                        }
                    }
                }
            }
            if diag {
                let sm = sim.summary();
                let med = |l: &mosimo_sim_core::metrics::Latency| l.median_h.map_or("-".into(), |h| format!("{h:.2}h"));
                println!(
                    "  trapped->HQ {} / road cut->ops {} / isolated->ops {} / needs->ops {} / anpi HQ 24h {:.0}% 72h {:.0}%",
                    med(&sm.trapped_to_hq),
                    med(&sm.road_cut_to_ops),
                    med(&sm.isolated_to_ops),
                    med(&sm.needs_to_ops),
                    sm.anpi_hq_24h * 100.0,
                    sm.anpi_hq_72h * 100.0
                );
                let c = &sim.counters;
                println!(
                    "  Starlink Direct: sent {} dropped {} / beam demand peak {:.0}% of capacity, {} steps over / Starlink cell load peak {:.0}%",
                    c.sd_sent,
                    c.sd_dropped,
                    sd_peak * 100.0,
                    sd_over_steps,
                    sl_peak * 100.0
                );
                println!("  calls ok {} failed {} / 119 ok {} busy {} / gateway uploads {} / mesh exchanges {}", c.calls_ok, c.calls_failed, c.calls_119_ok, c.calls_119_busy, c.gateway_uploads, c.mesh_exchanges);
                let fmt = |x: f32| if x.is_finite() { format!("{:.0}h", x / 60.0) } else { "-".into() };
                println!("  isolated settlements: name zone pop | isolated_at hq_at ops_at | residents with app");
                for (k, st) in sim.settlements.iter().enumerate() {
                    if !st.isolated_since.is_finite() && st.isolated_msg == u32::MAX {
                        continue;
                    }
                    let m = st.isolated_msg;
                    let (hq, ops) = if m != u32::MAX { (sim.msgs[m as usize].hq_at, sim.msgs[m as usize].ops_at) } else { (f32::INFINITY, f32::INFINITY) };
                    let apps = sim.agents.iter().filter(|a| a.settlement == k as u32 && a.app).count();
                    println!("    {} {} {} | {} {} {} | {}", st.name, sim.zones[st.zone as usize].name, st.pop, fmt(st.isolated_since), fmt(hq), fmt(ops), apps);
                }
                println!("  shelters unknown at first: name zone | 10+ people at, known at, people now");
                for (k, site) in sim.sites.iter().enumerate() {
                    let (seen, known) = shelter_seen[k];
                    if seen.is_finite() {
                        println!("    {} {} | {} {} {}", site.name, sim.zones[site.zone as usize].name, fmt(seen), fmt(known), site.present);
                    }
                }
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
