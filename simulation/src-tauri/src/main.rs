//! もしもシミュレーター: the Tauri shell. The simulation runs in Rust on a background thread;
//! the web view reads binary agent frames and JSON status, and only sends commands.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use mosimo_sim_core::frame::{StaticInfo, Status};
use mosimo_sim_core::sweep::{run_sweep, SweepPoint, SweepRequest};
use mosimo_sim_core::{builtin_scenarios, Focus, ScenarioSpec, Sim, SimParams};
use serde::Serialize;
use tauri::{Emitter, State};

/// Simulated minutes per real second; `f64::INFINITY` runs as fast as possible.
struct Runner {
    sim: Option<Sim>,
    running: bool,
    speed: f64,
}

type Shared = Arc<Mutex<Runner>>;

#[derive(Serialize)]
struct ScenarioInfo {
    id: String,
    name: String,
    description: String,
    start: String,
    duration_h: f64,
    defaults: SimParams,
}

fn scenario(id: &str) -> Result<ScenarioSpec, String> {
    builtin_scenarios().into_iter().find(|s| s.id == id).ok_or_else(|| format!("unknown scenario {id}"))
}

#[tauri::command]
fn list_scenarios() -> Vec<ScenarioInfo> {
    builtin_scenarios()
        .into_iter()
        .map(|s| ScenarioInfo {
            defaults: SimParams::for_scenario(&s),
            id: s.id,
            name: s.name,
            description: s.description,
            start: s.start,
            duration_h: s.duration_h,
        })
        .collect()
}

#[tauri::command]
fn create_sim(state: State<Shared>, id: String, params: SimParams) -> Result<StaticInfo, String> {
    let spec = scenario(&id)?;
    let sim = Sim::new(spec, params);
    let info = sim.static_info();
    let mut r = state.lock().unwrap();
    r.sim = Some(sim);
    r.running = false;
    Ok(info)
}

#[tauri::command]
fn set_running(state: State<Shared>, running: bool) {
    state.lock().unwrap().running = running;
}

#[tauri::command]
fn set_speed(state: State<Shared>, speed: Option<f64>) {
    state.lock().unwrap().speed = speed.unwrap_or(f64::INFINITY);
}

/// Advance by a number of simulated minutes while paused.
#[tauri::command]
fn advance(state: State<Shared>, minutes: f64) {
    let mut r = state.lock().unwrap();
    if let Some(sim) = r.sim.as_mut() {
        let end = sim.t + minutes as f32;
        while sim.t < end && !sim.done() {
            sim.step();
        }
    }
}

#[tauri::command]
fn frame(state: State<Shared>) -> tauri::ipc::Response {
    let r = state.lock().unwrap();
    tauri::ipc::Response::new(r.sim.as_ref().map(|s| s.frame()).unwrap_or_default())
}

#[tauri::command]
fn status(state: State<Shared>, events_from: usize, series_from: usize) -> Option<Status> {
    let r = state.lock().unwrap();
    r.sim.as_ref().map(|s| s.status(events_from, series_from))
}

#[tauri::command]
fn set_focus(state: State<Shared>, focus: Option<Focus>) {
    if let Some(sim) = state.lock().unwrap().sim.as_mut() {
        sim.focus = focus;
    }
}

#[derive(Clone, Serialize)]
struct Progress {
    done: usize,
    total: usize,
}

#[tauri::command]
async fn sweep(app: tauri::AppHandle, id: String, params: SimParams, request: SweepRequest) -> Result<Vec<SweepPoint>, String> {
    let spec = scenario(&id)?;
    tauri::async_runtime::spawn_blocking(move || {
        run_sweep(&spec, &params, &request, |done, total| {
            let _ = app.emit("sweep-progress", Progress { done, total });
        })
    })
    .await
    .map_err(|e| e.to_string())
}

/// Steps the simulation in real time at the requested speed, yielding the lock between slices.
fn runner_loop(shared: Shared) {
    let mut last = Instant::now();
    let mut budget = 0.0f64;
    loop {
        thread::sleep(Duration::from_millis(15));
        let now = Instant::now();
        let elapsed = now.duration_since(last).as_secs_f64();
        last = now;
        let mut r = shared.lock().unwrap();
        let (running, speed) = (r.running, r.speed);
        let Some(sim) = r.sim.as_mut() else { continue };
        if !running || sim.done() {
            budget = 0.0;
            if sim.done() {
                r.running = false;
            }
            continue;
        }
        let slice = Instant::now();
        if speed.is_finite() {
            budget = (budget + speed * elapsed).min(speed * 0.5);
            while budget >= sim.dt as f64 && !sim.done() && slice.elapsed() < Duration::from_millis(40) {
                budget -= sim.dt as f64;
                sim.step();
            }
        } else {
            while !sim.done() && slice.elapsed() < Duration::from_millis(40) {
                sim.step();
            }
        }
    }
}

fn main() {
    let shared: Shared = Arc::new(Mutex::new(Runner { sim: None, running: false, speed: 60.0 }));
    let bg = shared.clone();
    thread::spawn(move || runner_loop(bg));
    tauri::Builder::default()
        .manage(shared)
        .invoke_handler(tauri::generate_handler![list_scenarios, create_sim, set_running, set_speed, advance, frame, status, set_focus, sweep])
        .run(tauri::generate_context!())
        .expect("error while running the app");
}
