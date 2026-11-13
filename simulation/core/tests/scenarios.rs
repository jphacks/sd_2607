//! Every built-in scenario loads, runs with overflow checks on, and keeps basic invariants.

use mosimo_sim_core::model::{Health, VictimOutcome};
use mosimo_sim_core::{builtin_scenarios, Sim, SimParams};

fn run(id: &str, adoption: f64, hours: f32) -> Sim {
    let spec = builtin_scenarios().into_iter().find(|s| s.id == id).unwrap();
    let mut p = SimParams::for_scenario(&spec);
    p.adoption = adoption;
    p.population_scale = p.population_scale.min(0.1);
    p.starlink_direct = true;
    p.starlink_direct_data = true;
    let mut sim = Sim::new(spec, p);
    let _ = sim.status(0, 0);
    let _ = sim.frame();
    while sim.t < hours * 60.0 {
        sim.step();
    }
    let _ = sim.status(0, 0);
    let _ = sim.summary();
    sim
}

#[test]
fn all_scenarios_run() {
    for id in ["shelter", "kawauchi", "noto"] {
        for adoption in [0.0, 0.8] {
            let sim = run(id, adoption, 36.0);
            assert!(!sim.series.is_empty(), "{id}: no samples");
            for v in &sim.victims {
                let dead = sim.agents[v.agent as usize].health == Health::Dead;
                assert_eq!(dead, v.outcome == VictimOutcome::Dead, "{id}: victim state mismatch");
            }
            let s = sim.summary();
            assert!((0.0..=1.0).contains(&s.anpi_24h), "{id}: anpi out of range");
        }
    }
}

#[test]
fn same_seed_is_reproducible() {
    let a = run("shelter", 0.5, 12.0).summary();
    let b = run("shelter", 0.5, 12.0).summary();
    assert_eq!(serde_json::to_string(&a).unwrap(), serde_json::to_string(&b).unwrap());
}

#[test]
fn mesh_only_runs_with_app() {
    assert_eq!(run("shelter", 0.0, 12.0).counters.mesh_exchanges, 0);
    assert!(run("shelter", 0.8, 12.0).counters.mesh_exchanges > 0);
}

fn run_with(id: &str, hours: f32, tweak: impl Fn(&mut SimParams)) -> Sim {
    let spec = builtin_scenarios().into_iter().find(|s| s.id == id).unwrap();
    let mut p = SimParams::for_scenario(&spec);
    tweak(&mut p);
    let mut sim = Sim::new(spec, p);
    while sim.t < hours * 60.0 {
        sim.step();
    }
    sim
}

#[test]
fn car_navs_relay_only_when_present() {
    let off = run_with("shelter", 24.0, |p| {
        p.adoption = 0.0;
        p.staff_adoption = 0.0;
        p.carnav_adoption = 0.0;
    });
    assert_eq!(off.counters.nav_exchanges, 0);
    let on = run_with("shelter", 24.0, |p| {
        p.adoption = 0.0;
        p.staff_adoption = 0.0;
        p.carnav_adoption = 0.8;
    });
    assert!(on.counters.nav_exchanges > 0, "car navs never exchanged");
}

#[test]
fn slow_links_truncate_exchanges() {
    let sim = run_with("shelter", 24.0, |p| {
        p.adoption = 0.9;
        p.mesh_kbps = 1.0;
    });
    assert!(sim.counters.mesh_truncated > 0, "a 1 kbps link should cut exchanges short");
    assert!(sim.counters.mesh_missed > 0);
}

#[test]
fn households_come_from_census_data() {
    let sim = run_with("shelter", 0.0, |_| {});
    let residents = sim.agents.iter().filter(|a| a.role == mosimo_sim_core::model::Role::Resident).count();
    // 2,214 people in the 宝立 area (census 2020 scaled to 2024 and the New Year).
    assert!((2000..2500).contains(&residents), "{residents} residents");
}
