//! Agent-based simulation of how disaster information travels — by voice, SMS, SNS, broadcast,
//! paper boards, runners and the Mosimo mesh — from the quake until the internet comes back.

mod comms;
mod engine;
pub mod frame;
pub mod metrics;
pub mod model;
mod ops;
pub mod sim;
pub mod spec;
pub mod sweep;
pub mod util;

pub use metrics::Summary;
pub use sim::{Focus, Sim};
pub use spec::{builtin_scenarios, ScenarioSpec, SimParams};
