//! The step pipeline: infrastructure, official broadcasts, hazards, decisions, movement and shelters.
//! Communication is in `comms.rs`, response operations in `ops.rs`.

use rand::Rng;

use crate::model::*;
use crate::sim::{Sim, Via, INF};
use crate::spec::{BroadcastKind, Channel, SiteKind};
use crate::util::Vec2;

fn exp_sample(rng: &mut impl Rng, mean: f32) -> f32 {
    -mean * (1.0 - rng.gen::<f32>()).ln()
}

impl Sim {
    pub fn done(&self) -> bool {
        self.t >= self.end
    }

    pub fn step(&mut self) {
        self.dt = self.dt_at(self.t);
        self.update_infra();
        self.spawn_responders();
        self.broadcasts_step();
        self.hazards_step();
        self.decide_step();
        self.move_step();
        self.shelters_step();
        self.phones_step();
        self.contacts_step();
        self.ops_step();
        self.t += self.dt;
        if self.t >= self.next_sample {
            self.sample();
            self.next_sample += 60.0;
        }
    }

    pub fn run_to_end(&mut self) {
        while !self.done() {
            self.step();
        }
    }

    /// Hour of day (local) now.
    pub fn clock_hour(&self) -> f32 {
        (self.start_hour + self.t / 60.0) % 24.0
    }

    fn update_infra(&mut self) {
        let t = self.t;
        self.power_out = self.spec.zones.iter().map(|z| z.power_out.at(t as f64) as f32).collect();
        for tw in self.towers.iter_mut() {
            tw.users_prev = tw.users;
            tw.users = 0;
            if tw.temporary {
                tw.up = t >= tw.from && t < tw.until;
            } else {
                let z = &self.spec.zones[tw.zone as usize];
                let factor = self.spec.carriers[tw.carrier as usize].outage_factor;
                let ratio = (z.cell_out.at(t as f64) * factor).min(1.0) as f32;
                tw.up = tw.rank >= ratio;
            }
        }
        // Roads reopened by emergency clearance (道路啓開) are announced officially.
        for e in 0..self.edges.len() {
            let ed = &self.edges[e];
            if ed.cut_until <= t && ed.open_msg == NONE && ed.cut_until < INF {
                let pos = self.nodes[ed.a as usize].lerp(self.nodes[ed.b as usize], 0.5);
                let zone = self.zone_of_pos(pos);
                let text = format!("{} 通行可能", if ed.name.is_empty() { "道路" } else { &ed.name });
                let m = self.new_msg(MsgKind::RoadOpen, pos, zone, text);
                self.msgs[m as usize].edge = e as u32;
                self.edges[e].open_msg = m;
                self.ops_learn(m);
                self.put_internet(m);
                for z in 0..self.zones.len() {
                    self.hq_learn(z as u16, m);
                }
            }
        }
        let dt = self.dt as f64;
        let lines = self.spec.congestion.fire_lines_per_h * self.params.population_scale.max(0.05);
        for z in self.zones.iter_mut() {
            z.calls_budget = (z.calls_budget + lines * dt / 60.0).min(lines);
        }
    }

    fn broadcasts_step(&mut self) {
        let t = self.t;
        let dt = self.dt;
        // Issue broadcasts whose time has come.
        while self.broadcast_msgs.len() < self.spec.broadcasts.len() {
            let i = self.broadcast_msgs.len();
            let b = self.spec.broadcasts[i].clone();
            if (b.t_h * 60.0) as f32 > t {
                break;
            }
            let kind = match b.kind {
                BroadcastKind::TsunamiWarning | BroadcastKind::TsunamiUpdate => MsgKind::TsunamiWarning,
                BroadcastKind::Official => MsgKind::Official,
            };
            let center = self.proj.to_xy(self.spec.view.center);
            let m = self.new_msg(kind, center, 0, b.text.clone());
            self.msgs[m as usize].expires = b.expires_h.map(|h| (h * 60.0) as f32).unwrap_or(INF);
            self.broadcast_msgs.push(m);
            self.put_internet(m);
            self.ops_learn(m);
            for z in 0..self.zones.len() {
                self.hq_learn(z as u16, m);
            }
            if b.channels.contains(&Channel::CellBroadcast) {
                for a in 0..self.agents.len() {
                    if self.agents[a].phone_on() && self.in_zones(a, &b.zones) && self.cell_tower(a as u32).is_some() {
                        self.learn(a as u32, m, Via::Broadcast);
                    }
                }
            }
        }
        // Ongoing TV/radio and municipal loudspeakers for active broadcasts.
        for bi in 0..self.broadcast_msgs.len() {
            let m = self.broadcast_msgs[bi];
            let msg = &self.msgs[m as usize];
            if msg.expires <= t {
                continue;
            }
            let created = msg.created;
            let b = self.spec.broadcasts[bi].clone();
            let tv = b.channels.iter().any(|c| matches!(c, Channel::Tv | Channel::Radio));
            let musen = b.channels.contains(&Channel::Musen) && t < created + 180.0;
            let p_tv = 1.0 - (-(self.spec.behavior.broadcast_per_h as f32) * dt / 60.0).exp();
            let radio_np = self.spec.behavior.radio_without_power as f32;
            for a in 0..self.agents.len() {
                let ag = &self.agents[a];
                if !ag.alive() || self.knows[a].get(m) || !self.in_zones(a, &b.zones) {
                    continue;
                }
                if !ag.on_map() {
                    self.learn(a as u32, m, Via::Broadcast);
                    continue;
                }
                let mut p = 0.0;
                if tv {
                    let powered = self.has_power(a);
                    let radio = (ag.charge_rank * 7919.0).fract() < radio_np || ag.moving() && ag.car;
                    if powered || radio {
                        p = p_tv;
                    }
                }
                if musen {
                    let ok = self.zones[ag.zone as usize].musen_ok as f32;
                    let hear = if ag.indoor { 0.35 } else { 0.75 };
                    p = 1.0 - (1.0 - p) * (1.0 - ok * hear * (dt / 30.0).min(1.0));
                }
                if p > 0.0 && self.rng.gen::<f32>() < p {
                    self.learn(a as u32, m, Via::Broadcast);
                }
            }
        }
    }

    fn in_zones(&self, a: usize, zones: &[String]) -> bool {
        zones.is_empty() || zones.iter().any(|z| self.zones[self.agents[a].zone as usize].id == *z)
    }

    /// Whether agent `a` can use grid or generator power where it is.
    pub fn has_power(&self, a: usize) -> bool {
        let ag = &self.agents[a];
        if !ag.on_map() {
            return true;
        }
        if ag.site != NONE {
            let s = &self.sites[ag.site as usize];
            if self.t >= s.power_from {
                return true;
            }
        }
        if ag.site == NONE && !ag.moving() && ag.pos.dist2(ag.home) < 400.0 {
            return ag.charge_rank >= self.power_out[ag.zone as usize];
        }
        if ag.site != NONE && self.sites[ag.site as usize].kind == SiteKind::Building {
            return (self.sites[ag.site as usize].pos.x.abs() * 0.001).fract() >= self.power_out[ag.zone as usize];
        }
        false
    }

    fn hazards_step(&mut self) {
        let t = self.t;
        let dt = self.dt;
        // Tsunami arrival: count people still in the inundation area.
        for s in 0..self.settlements.len() {
            let st = &self.settlements[s];
            if st.tsunami_done || st.tsunami_arrival > t {
                continue;
            }
            self.settlements[s].tsunami_done = true;
            let (mut exposed, mut unwarned) = (0u32, 0u32);
            for a in &self.agents {
                if a.settlement == s as u32 && a.inundation && !a.tsunami_evacuated && a.alive() && a.on_map() {
                    exposed += 1;
                    if a.warned_at > t {
                        unwarned += 1;
                    }
                }
            }
            if exposed > 0 {
                self.counters.tsunami_exposed += exposed;
                self.counters.tsunami_exposed_unwarned += unwarned;
                let pos = self.settlements[s].pos;
                let name = self.settlements[s].name.clone();
                self.event(EventKind::TsunamiExposed, pos, exposed, format!("{name}: {exposed}人が浸水域に残留（うち警報未受信 {unwarned}人）"));
            }
        }

        // Fires: people nearby see them; everyone else hears second hand.
        while self.fires_started < self.spec.hazards.fires.len() {
            let f = self.spec.hazards.fires[self.fires_started].clone();
            if (f.from_h * 60.0) as f32 > t {
                break;
            }
            self.fires_started += 1;
            let pos = self.proj.to_xy(f.pos);
            let zone = self.zone_of_pos(pos);
            let m = self.new_msg(MsgKind::Fire, pos, zone, format!("{}で火災", f.name));
            for a in 0..self.agents.len() as u32 {
                let ag = &self.agents[a as usize];
                if ag.mobile() && ag.on_map() && ag.pos.dist2(pos) < 400.0 * 400.0 {
                    self.learn(a, m, Via::Origin);
                }
            }
        }

        // Trapped people.
        if t == 0.0 {
            self.find_by_family();
        }
        for v in 0..self.victims.len() {
            let vic = self.victims[v].clone();
            if vic.outcome != VictimOutcome::Pending {
                continue;
            }
            let agent = vic.agent;
            if vic.msg == NONE {
                // The victim knows about itself; a phone lets it call or post.
                let pos = self.agents[agent as usize].pos;
                let zone = self.agents[agent as usize].zone;
                let name = self.settlements[vic.settlement as usize].name.clone();
                let m = self.new_msg(MsgKind::Trapped, pos, zone, format!("{name}で倒壊家屋に閉じ込め"));
                self.msgs[m as usize].victim = v as u32;
                self.msgs[m as usize].settlement = vic.settlement;
                self.victims[v].msg = m;
                self.learn(agent, m, Via::Origin);
            }
            if t >= vic.death_at {
                self.victim_died(v as u32);
                continue;
            }
            if vic.found_at <= t && vic.finder != NONE {
                let m = self.victims[v].msg;
                self.learn(vic.finder, m, Via::Origin);
                if !vic.severe && self.victims[v].neighbor_free_at == INF {
                    let mean = (self.spec.rescue.neighbor_rescue_h * 60.0) as f32;
                    self.victims[v].neighbor_free_at = t + exp_sample(&mut self.rng, mean);
                }
            }
            if self.victims[v].neighbor_free_at <= t {
                self.free_victim(v as u32, VictimOutcome::RescuedByNeighbors);
            }
        }
        let _ = dt;
    }

    /// Family or neighbours within earshot notice a trapped person soon after the quake.
    fn find_by_family(&mut self) {
        for v in 0..self.victims.len() {
            let a = self.victims[v].agent;
            let pos = self.agents[a as usize].pos;
            let st = self.agents[a as usize].settlement as usize;
            let mut best = (NONE, 30.0f32 * 30.0);
            for (_, members) in &self.settlements[st].households {
                for &j in members {
                    let b = &self.agents[j as usize];
                    if j != a && b.mobile() && b.role == Role::Resident {
                        let d = b.pos.dist2(pos);
                        if d < best.1 {
                            best = (j, d);
                        }
                    }
                }
            }
            if best.0 != NONE {
                self.victims[v].finder = best.0;
                self.victims[v].found_at = exp_sample(&mut self.rng, 30.0);
            }
        }
    }

    pub fn free_victim(&mut self, v: u32, how: VictimOutcome) {
        let t = self.t;
        let vic = &mut self.victims[v as usize];
        vic.outcome = how;
        vic.resolved_at = t;
        let a = vic.agent as usize;
        match how {
            VictimOutcome::RescuedByNeighbors => self.counters.rescued_by_neighbors += 1,
            VictimOutcome::RescuedByTeam => self.counters.rescued_by_team += 1,
            _ => {}
        }
        let ag = &mut self.agents[a];
        ag.health = Health::Ok;
        ag.intent = 1;
        ag.evac_done = false;
        ag.next_decision = t + 30.0;
    }

    fn victim_died(&mut self, v: u32) {
        let t = self.t;
        let vic = self.victims[v as usize].clone();
        self.victims[v as usize].outcome = VictimOutcome::Dead;
        self.victims[v as usize].resolved_at = t;
        self.agents[vic.agent as usize].health = Health::Dead;
        self.counters.trapped_dead += 1;
        let msg = &self.msgs[vic.msg as usize];
        let known = msg.ops_at <= t || msg.hq_at <= t;
        let reason = if vic.found_at > t && msg.reach <= 1 {
            "誰にも発見されず"
        } else if !known {
            "要救助の情報が本部・救助機関に届かず"
        } else if vic.team == NONE {
            "本部は把握していたが救助隊が割り当てられず"
        } else {
            "救助隊が到着・救出する前に"
        };
        let name = self.settlements[vic.settlement as usize].name.clone();
        let pos = self.agents[vic.agent as usize].pos;
        self.event(EventKind::RescueTooLate, pos, 1, format!("{name}: {reason}（閉じ込めから{:.0}時間）", t / 60.0));
    }

    fn decide_step(&mut self) {
        let t = self.t;
        let b = self.spec.behavior.clone();
        let hour = self.clock_hour();
        let daytime = (7.0..19.0).contains(&hour);
        for i in 0..self.agents.len() {
            let ag = &self.agents[i];
            if !ag.mobile() || !ag.role.is_population() || ag.role == Role::ShelterStaff && ag.evac_done {
                continue;
            }
            // Tsunami flight takes precedence over everything else.
            if ag.in_tsunami_zone && !ag.tsunami_evacuated && !ag.moving() && ag.on_map() {
                let stage = ag.tsunami_stage;
                let roll = match stage {
                    0 => Some(b.self_evac_tsunami),
                    1 if ag.warned_at <= t => Some(b.warned_evac_tsunami),
                    _ => None,
                };
                if let Some(p) = roll {
                    self.agents[i].tsunami_stage = stage + 1;
                    if self.rng.gen::<f64>() < p {
                        let hg = self.settlements[self.agents[i].settlement as usize].high_ground;
                        let a = self.rng.gen::<f32>() * std::f32::consts::TAU;
                        let dest = Vec2::new(hg.x + 40.0 * a.cos(), hg.y + 40.0 * a.sin());
                        self.plan_move(i as u32, dest, NONE, Arrive::Stop);
                        // Most who fled go on to a shelter once the warning eases.
                        if self.agents[i].intent == UNDECIDED {
                            self.agents[i].intent = if self.rng.gen::<f32>() < 0.75 { 1 } else { 0 };
                        }
                        self.agents[i].next_decision = t + 60.0 * (3.0 + self.rng.gen::<f32>() * 3.0);
                        continue;
                    }
                }
            }
            let ag = &self.agents[i];
            let leading = ag.leader && ag.on_map() && !ag.moving();
            if leading && self.on_rounds(i as u32) {
                continue;
            }
            let ag = &self.agents[i];
            if ag.next_decision > t || ag.moving() {
                continue;
            }
            match ag.plan.clone() {
                Plan::Stay { until, next } => {
                    if until <= t {
                        self.agents[i].plan = Plan::Idle;
                        self.do_next(i as u32, next);
                    }
                    continue;
                }
                Plan::Move { .. } => continue,
                Plan::Idle => {}
            }
            if !self.agents[i].evac_done {
                self.first_decision(i as u32);
                continue;
            }
            // Settled: occasional trips, and leaving the area for good.
            let mean_gap = (24.0 * 60.0 / b.trips_per_day.max(0.01)) as f32;
            self.agents[i].next_decision = t + exp_sample(&mut self.rng, mean_gap);
            let ag = &self.agents[i];
            if !daytime || ag.age == 0 || !ag.on_map() {
                continue;
            }
            let in_shelter = ag.site != NONE && self.sites[ag.site as usize].kind == SiteKind::Shelter;
            if in_shelter && ag.car && self.rng.gen::<f64>() < b.leave_per_day * mean_gap as f64 / 1440.0 / b.trips_per_day.max(0.01) {
                if let Some(x) = self.pick_exit() {
                    let node = self.exits[x as usize].node;
                    if self.plan_move(i as u32, self.nodes[node as usize], node, Arrive::Exit(x)) {
                        self.agents[i].left_for_good = true;
                        continue;
                    }
                }
            }
            self.start_trip(i as u32, in_shelter);
        }
    }

    fn pick_exit(&mut self) -> Option<u16> {
        if self.exits.is_empty() {
            None
        } else {
            Some(self.rng.gen_range(0..self.exits.len()) as u16)
        }
    }

    fn first_decision(&mut self, i: u32) {
        let t = self.t;
        let b = self.spec.behavior.clone();
        let a = &self.agents[i as usize];
        if a.settlement == NONE {
            // Campus: assemble outdoors, then go home or to a shelter.
            if a.site != NONE && self.sites[a.site as usize].kind == SiteKind::Building && a.intent == UNDECIDED {
                let assembly = self.nearest_site(a.pos, |s| s.kind == SiteKind::Open);
                self.agents[i as usize].intent = 3;
                if let Some(s) = assembly {
                    let dest = self.random_in_site(s);
                    self.plan_move(i, dest, NONE, Arrive::Site(s));
                }
                let stay = 60.0 + self.rng.gen::<f32>() * 120.0;
                self.agents[i as usize].next_decision = t + stay;
                return;
            }
            self.agents[i as usize].evac_done = true;
            let go_home = self.rng.gen::<f64>() < 0.6;
            if go_home && !self.exits.is_empty() {
                let x = self.rng.gen_range(0..self.exits.len()) as u16;
                let node = self.exits[x as usize].node;
                if self.plan_move(i, self.nodes[node as usize], node, Arrive::Exit(x)) {
                    self.agents[i as usize].left_for_good = true;
                    return;
                }
            }
            let a = &self.agents[i as usize];
            if let Some(s) = self.nearest_site(a.pos, |s| s.kind == SiteKind::Shelter) {
                let dest = self.random_in_site(s);
                self.agents[i as usize].shelter = s;
                self.plan_move(i, dest, self.sites[s as usize].node, Arrive::Site(s));
            }
            return;
        }
        let st = &self.settlements[a.settlement as usize];
        let st_spec = &self.spec.settlements[a.settlement as usize];
        if a.intent == UNDECIDED {
            // Choose once: shelter, car or stay; people whose house collapsed lean to the shelter.
            let u = self.rng.gen::<f64>();
            let tsunami = a.in_tsunami_zone;
            let p_shelter = (st_spec.evac_ratio * if tsunami { 1.4 } else { 1.0 }).min(0.95);
            let intent = if u < p_shelter * (1.0 - st_spec.car_ratio) {
                1
            } else if u < p_shelter {
                2
            } else {
                0
            };
            let _ = st;
            self.agents[i as usize].intent = intent;
            let delay = exp_sample(&mut self.rng, (b.evac_delay_h * 60.0) as f32);
            self.agents[i as usize].next_decision = t + delay.max(1.0);
            return;
        }
        self.agents[i as usize].evac_done = true;
        let mean_gap = (24.0 * 60.0 / b.trips_per_day.max(0.01)) as f32;
        let next = t + exp_sample(&mut self.rng, mean_gap);
        match self.agents[i as usize].intent {
            1 => {
                if !self.go_to_shelter(i) {
                    self.agents[i as usize].intent = 0;
                }
            }
            2 => {
                // Sleep in the car (outdoors, engine idling, can charge): half by the house, half in a shelter car park.
                let st = self.agents[i as usize].settlement;
                let lot = self.settlements.get(st as usize).and_then(|s| s.shelters.first().copied());
                match lot {
                    Some(s) if self.rng.gen::<f32>() < 0.5 => {
                        let c = self.sites[s as usize].pos;
                        let r = self.sites[s as usize].radius + 15.0 + self.rng.gen::<f32>() * 40.0;
                        let ang = self.rng.gen::<f32>() * std::f32::consts::TAU;
                        let dest = Vec2::new(c.x + r * ang.cos(), c.y + r * ang.sin());
                        self.plan_move(i, dest, self.sites[s as usize].node, Arrive::Stop);
                    }
                    _ => {
                        let h = self.agents[i as usize].home;
                        self.plan_move(i, Vec2::new(h.x + 8.0, h.y + 8.0), NONE, Arrive::Stop);
                    }
                }
            }
            _ => {
                let h = self.agents[i as usize].home;
                if self.agents[i as usize].pos.dist(h) > 30.0 {
                    self.plan_move(i, h, NONE, Arrive::Home);
                }
            }
        }
        self.agents[i as usize].next_decision = next;
    }

    /// A leader's door-to-door round (区長・民生委員が安否を確かめて回る). Returns false once rounds are over.
    fn on_rounds(&mut self, i: u32) -> bool {
        let t = self.t;
        let a = &self.agents[i as usize];
        let start = if a.in_tsunami_zone { 270.0 } else { 20.0 };
        let hour = self.clock_hour();
        if t < start || t > (self.spec.behavior.rounds_h * 60.0) as f32 || !(6.0..22.0).contains(&hour) {
            return false;
        }
        if let Plan::Stay { until, .. } = a.plan {
            if until > t {
                return true;
            }
        }
        let st = a.settlement;
        if st == NONE || self.settlements[st as usize].households.len() < 2 {
            return false;
        }
        let visits = self.rounds.get(&i).map(|r| r.0).unwrap_or(0);
        if visits % 10 == 9 {
            // Report at the shelter what was found.
            if let Some(&s) = self.settlements[st as usize].shelters.first() {
                let dest = self.random_in_site(s);
                let node = self.sites[s as usize].node;
                self.plan_move(i, dest, node, Arrive::Site(s));
            }
        } else {
            let n = self.settlements[st as usize].households.len();
            let h = self.rng.gen_range(0..n) as u32;
            let pos = self.settlements[st as usize].households[h as usize].0;
            self.plan_move(i, Vec2::new(pos.x + 4.0, pos.y + 4.0), NONE, Arrive::Visit(h));
        }
        self.rounds.entry(i).or_default().0 += 1;
        true
    }

    pub fn nearest_site(&self, p: Vec2, pred: impl Fn(&Site) -> bool) -> Option<u32> {
        self.sites
            .iter()
            .enumerate()
            .filter(|(_, s)| pred(s))
            .min_by(|a, b| a.1.pos.dist2(p).partial_cmp(&b.1.pos.dist2(p)).unwrap_or(std::cmp::Ordering::Equal))
            .map(|(i, _)| i as u32)
    }

    fn go_to_shelter(&mut self, i: u32) -> bool {
        let a = &self.agents[i as usize];
        let st = a.settlement;
        if st == NONE {
            return false;
        }
        let options = self.settlements[st as usize].shelters.clone();
        let mut choice = NONE;
        for &s in &options {
            let site = &self.sites[s as usize];
            let cap = site.peak_observed.unwrap_or(site.capacity).max(site.capacity) as f32 * 1.3;
            if (site.present as f32) < cap.max(20.0) {
                choice = s;
                break;
            }
        }
        if choice == NONE {
            choice = *options.first().unwrap_or(&NONE);
        }
        if choice == NONE {
            return false;
        }
        let dest = self.random_in_site(choice);
        self.agents[i as usize].shelter = choice;
        let node = self.sites[choice as usize].node;
        self.plan_move(i, dest, node, Arrive::Site(choice))
    }

    fn start_trip(&mut self, i: u32, in_shelter: bool) {
        let share = self.spec.behavior.signal_trip_share;
        let (car, pos, settlement) = {
            let a = &self.agents[i as usize];
            (a.car, a.pos, a.settlement)
        };
        let u = self.rng.gen::<f64>();
        let stay = 30.0 + self.rng.gen::<f32>() * 90.0;
        if car && u < share {
            // Drive to a place with signal (a hill, or off the map) and come back.
            let spot = self.nearest_site(pos, |s| s.kind == SiteKind::SignalSpot);
            if let Some(s) = spot {
                let dest = self.random_in_site(s);
                if self.plan_move(i, dest, self.sites[s as usize].node, Arrive::Site(s)) {
                    self.agents[i as usize].next_decision = self.t + stay + 60.0;
                    self.agents[i as usize].intent = if in_shelter { 1 } else { 0 };
                    return;
                }
            }
            if let Some(x) = self.pick_exit() {
                let node = self.exits[x as usize].node;
                self.plan_move(i, self.nodes[node as usize], node, Arrive::Exit(x));
                return;
            }
        }
        if in_shelter {
            let h = self.agents[i as usize].home;
            self.plan_move(i, h, NONE, Arrive::Home);
        } else {
            // Residents staying home visit the shelter for water and news.
            if let Some(&s) = self.settlements.get(settlement as usize).and_then(|s| s.shelters.first()) {
                let dest = self.random_in_site(s);
                self.plan_move(i, dest, self.sites[s as usize].node, Arrive::Site(s));
            }
        }
    }

    fn do_next(&mut self, i: u32, next: Next) {
        match next {
            Next::Nothing => {}
            Next::ReturnShelter | Next::GoShelter => {
                self.go_to_shelter(i);
            }
            Next::ReturnHome => {
                let h = self.agents[i as usize].home;
                self.plan_move(i, h, NONE, Arrive::Home);
            }
            Next::ReturnFromExit => {
                let x = self.agents[i as usize].offmap;
                if x == NO_EXIT {
                    return;
                }
                let node = self.exits[x as usize].node;
                self.agents[i as usize].offmap = NO_EXIT;
                self.agents[i as usize].pos = self.nodes[node as usize];
                let back = self.agents[i as usize].shelter;
                if back != NONE {
                    let dest = self.random_in_site(back);
                    self.plan_move(i, dest, self.sites[back as usize].node, Arrive::Site(back));
                } else {
                    let h = self.agents[i as usize].home;
                    self.plan_move(i, h, NONE, Arrive::Home);
                }
            }
        }
    }

    fn move_step(&mut self) {
        let t = self.t;
        let dt = self.dt;
        for i in 0..self.agents.len() {
            if !self.agents[i].mobile() {
                continue;
            }
            let Plan::Move { pts, edges, mut seg, mut along, speed, foot, arrive, started } = self.agents[i].plan.clone() else {
                continue;
            };
            self.agents[i].indoor = false;
            let mut budget = speed * dt;
            let mut blocked = false;
            while budget > 0.0 && seg + 1 < pts.len() {
                // Entering a road segment: discover whether it is actually passable.
                if along == 0.0 && edges[seg] != NONE {
                    let e = edges[seg];
                    let ed = &self.edges[e as usize];
                    if ed.cut_at(t) && !(foot && ed.foot_ok) {
                        blocked = true;
                        break;
                    }
                }
                let len = pts[seg].dist(pts[seg + 1]);
                let len = if edges[seg] != NONE { self.edges[edges[seg] as usize].len.max(len) } else { len };
                if along + budget >= len {
                    budget -= len - along;
                    seg += 1;
                    along = 0.0;
                } else {
                    along += budget;
                    budget = 0.0;
                }
            }
            if blocked {
                let e = edges[seg];
                self.hit_cut(i as u32, e, started);
                // Re-plan from here with the new knowledge.
                let dest = *pts.last().unwrap();
                let here = pts[seg];
                self.agents[i].pos = here;
                self.agents[i].plan = Plan::Idle;
                if !self.plan_move(i as u32, dest, NONE, arrive) {
                    self.agents[i].plan = Plan::Idle;
                    self.agents[i].next_decision = t + 120.0;
                    if self.agents[i].team != NONE {
                        let tm = self.agents[i].team as usize;
                        self.teams[tm].job = TeamJob::None;
                    }
                }
                continue;
            }
            if seg + 1 >= pts.len() {
                self.agents[i].pos = *pts.last().unwrap();
                self.agents[i].plan = Plan::Idle;
                self.arrive(i as u32, arrive);
            } else {
                let len = pts[seg].dist(pts[seg + 1]).max(1e-3);
                let rl = if edges[seg] != NONE { self.edges[edges[seg] as usize].len.max(len) } else { len };
                self.agents[i].pos = pts[seg].lerp(pts[seg + 1], (along / rl).min(1.0));
                self.agents[i].plan = Plan::Move { pts, edges, seg, along, speed, foot, arrive, started };
            }
        }
    }

    /// An agent finds a road cut it did not know about.
    fn hit_cut(&mut self, i: u32, e: u32, started: f32) {
        let ed = &self.edges[e as usize];
        let mut m = ed.cut_msg;
        if m == NONE {
            let pos = self.nodes[ed.a as usize].lerp(self.nodes[ed.b as usize], 0.5);
            let zone = self.zone_of_pos(pos);
            let name = if ed.name.is_empty() { "道路".to_string() } else { ed.name.clone() };
            let cause = if ed.cause.is_empty() { String::new() } else { format!("（{}）", ed.cause) };
            m = self.new_msg(MsgKind::RoadCut, pos, zone, format!("{name}が通行不能{cause}"));
            self.msgs[m as usize].edge = e;
            self.edges[e as usize].cut_msg = m;
        }
        self.learn(i, m, Via::Origin);
        let role = self.agents[i as usize].role;
        if matches!(role, Role::Rescuer | Role::Truck | Role::Liaison) {
            // Responders report it over their own radio (消防・自衛隊無線).
            self.ops_learn(m);
            let zone = self.msgs[m as usize].zone;
            self.hq_learn(zone, m);
            self.counters.unknown_cut_hits += 1;
            self.counters.wasted_vehicle_min += (self.t - started).max(0.0) as f64;
            let what = match role {
                Role::Rescuer => "救助隊",
                Role::Truck => "物資トラック",
                _ => "巡回車",
            };
            let pos = self.agents[i as usize].pos;
            let name = self.edges[e as usize].name.clone();
            self.event(EventKind::UnknownRoadCut, pos, 1, format!("{what}が{name}の寸断に行き当たり迂回（{:.0}分ロス）", self.t - started));
        }
    }

    fn arrive(&mut self, i: u32, arrive: Arrive) {
        let t = self.t;
        match arrive {
            Arrive::Home => {
                let a = &mut self.agents[i as usize];
                a.site = NONE;
                a.indoor = true;
                if a.shelter != NONE && a.evac_done && a.role.is_population() {
                    let stay = 60.0 + self.rng.gen::<f32>() * 120.0;
                    self.agents[i as usize].plan = Plan::Stay { until: t + stay, next: Next::ReturnShelter };
                }
            }
            Arrive::Site(s) => {
                let kind = self.sites[s as usize].kind;
                self.agents[i as usize].site = s;
                self.agents[i as usize].indoor = !matches!(kind, SiteKind::Open | SiteKind::SignalSpot | SiteKind::HighGround);
                if let Some((_, safe)) = self.rounds.get_mut(&i) {
                    let safe = std::mem::take(safe);
                    if kind == SiteKind::Hq {
                        for &p in &safe {
                            self.agents[p as usize].anpi_hq = self.agents[p as usize].anpi_hq.min(t);
                        }
                    } else if kind == SiteKind::Shelter {
                        self.sites[s as usize].reported_safe.extend(safe);
                    }
                }
                match kind {
                    SiteKind::Shelter => self.deposit_at_shelter(i, s),
                    SiteKind::Hq => self.deposit_at_hq(i, s),
                    SiteKind::SignalSpot => {
                        let stay = 30.0 + self.rng.gen::<f32>() * 60.0;
                        let next = if self.agents[i as usize].shelter != NONE { Next::ReturnShelter } else { Next::ReturnHome };
                        self.agents[i as usize].plan = Plan::Stay { until: t + stay, next };
                    }
                    _ => {}
                }
                let a = &self.agents[i as usize];
                if kind == SiteKind::Shelter && a.shelter != s && a.role.is_population() && a.evac_done {
                    // Visiting another shelter or getting water: go back afterwards.
                    let stay = 30.0 + self.rng.gen::<f32>() * 60.0;
                    let next = if a.shelter != NONE { Next::ReturnShelter } else { Next::ReturnHome };
                    self.agents[i as usize].plan = Plan::Stay { until: t + stay, next };
                }
            }
            Arrive::Exit(x) => {
                let a = &mut self.agents[i as usize];
                a.offmap = x;
                a.site = NONE;
                if a.left_for_good {
                    a.plan = Plan::Stay { until: INF, next: Next::Nothing };
                } else {
                    let stay = 120.0 + self.rng.gen::<f32>() * 240.0;
                    let travel = (self.exits[x as usize].travel_h.at(t as f64) * 60.0) as f32;
                    a.plan = Plan::Stay { until: t + stay + 2.0 * travel, next: Next::ReturnFromExit };
                }
            }
            Arrive::Stop => {
                let a = &mut self.agents[i as usize];
                if a.in_tsunami_zone {
                    a.tsunami_evacuated = true;
                }
                a.indoor = false;
            }
            Arrive::Job => {}
            Arrive::Visit(h) => {
                // Ask after everyone at home and swap news with them.
                let st = self.agents[i as usize].settlement as usize;
                let (pos, members) = self.settlements[st].households[h as usize].clone();
                let mut items = std::mem::take(&mut self.scratch);
                let k = self.spec.behavior.talk_items + 2;
                for m in members {
                    let b = &self.agents[m as usize];
                    if b.alive() && b.on_map() && b.pos.dist2(pos) < 900.0 {
                        if b.health == Health::Ok {
                            self.rounds.entry(i).or_default().1.push(m);
                        }
                        self.tell(i, m, k, &mut items);
                        self.tell(m, i, k, &mut items);
                    }
                }
                self.scratch = items;
                let stay = 4.0 + self.rng.gen::<f32>() * 8.0;
                self.agents[i as usize].plan = Plan::Stay { until: t + stay, next: Next::Nothing };
                self.agents[i as usize].indoor = false;
            }
        }
    }

    /// Someone arriving at a shelter tells the reception what they know; staff post it on the board.
    pub fn deposit_at_shelter(&mut self, i: u32, s: u32) {
        let mut items = std::mem::take(&mut self.scratch);
        self.sites[s as usize].board.missing_from(&self.knows[i as usize], Some(&self.talk_mask), &mut items);
        for &m in &items {
            if self.msgs[m as usize].kind.priority() >= 2 {
                self.sites[s as usize].board.insert(m);
            }
        }
        self.knows[i as usize].missing_from(&self.sites[s as usize].board, None, &mut items);
        for &m in &items {
            self.learn(i, m, Via::Board);
        }
        self.scratch = items;
    }

    pub fn deposit_at_hq(&mut self, i: u32, s: u32) {
        let zone = self.sites[s as usize].zone;
        let t = self.t;
        let a = &mut self.agents[i as usize];
        a.anpi_hq = a.anpi_hq.min(t);
        let known: Vec<u32> = self.knows[i as usize].iter().collect();
        for m in known {
            self.hq_learn(zone, m);
        }
        let mut items = std::mem::take(&mut self.scratch);
        self.knows[i as usize].missing_from(&self.zones[zone as usize].knows, Some(&self.talk_mask), &mut items);
        for &m in &items {
            self.learn(i, m, Via::Board);
        }
        self.scratch = items;
    }

    fn shelters_step(&mut self) {
        let t = self.t;
        let dt = self.dt as f64;
        let sp = self.spec.supply.clone();
        for s in self.sites.iter_mut() {
            s.present = 0;
            s.people.clear();
        }
        for (i, a) in self.agents.iter().enumerate() {
            if a.site != NONE && a.alive() && a.on_map() && a.role.is_population() {
                let s = &mut self.sites[a.site as usize];
                s.present += 1;
                s.people.push(i as u32);
            }
        }
        let any_short: Vec<u32> = (0..self.sites.len() as u32)
            .filter(|&s| {
                let x = &self.sites[s as usize];
                x.kind == SiteKind::Shelter && x.present > 0 && x.water <= 0.0
            })
            .collect();
        for s in 0..self.sites.len() {
            if self.sites[s].kind != SiteKind::Shelter {
                continue;
            }
            let site = &mut self.sites[s];
            site.peak = site.peak.max(site.present);
            let pop = site.present as f64;
            if pop == 0.0 {
                continue;
            }
            let wday = pop * sp.water_l_per_person_day;
            let fday = pop * sp.food_per_person_day;
            site.water = (site.water - wday * dt / 1440.0).max(0.0);
            site.food = (site.food - fday * dt / 1440.0).max(0.0);
            if site.water <= 0.0 {
                if site.water_short_since == INF {
                    site.water_short_since = t;
                }
            } else if site.water_short_since < INF {
                site.short_hours += ((t - site.water_short_since) / 60.0) as f64;
                site.water_short_since = INF;
                site.reported_short = false;
            }
            if site.food <= 0.0 {
                if site.food_short_since == INF {
                    site.food_short_since = t;
                }
            } else {
                site.food_short_since = INF;
                site.food_reported = false;
            }
            let (pos, zone, name) = (site.pos, site.zone, site.name.clone());
            if site.water_short_since <= t - 720.0 && !site.reported_short {
                site.reported_short = true;
                let p = site.present;
                self.event(EventKind::WaterShortage, pos, p, format!("{name}: 水が12時間以上尽きている（{p}人）"));
            }
            let site = &mut self.sites[s];
            if site.food_short_since <= t - 720.0 && !site.food_reported {
                site.food_reported = true;
                let p = site.present;
                self.event(EventKind::FoodShortage, pos, p, format!("{name}: 食料が12時間以上尽きている（{p}人）"));
            }
            // Needs and surplus as the staff see them.
            let site = &self.sites[s];
            let (water, food, present) = (site.water, site.food, site.present);
            let stale_w = site.need_water != NONE && t - self.msgs[site.need_water as usize].created >= 720.0;
            let stale_f = site.need_food != NONE && t - self.msgs[site.need_food as usize].created >= 720.0;
            if water < wday && (site.need_water == NONE || stale_w) {
                let m = self.shelter_msg(s as u32, MsgKind::NeedWater, format!("{name}: 水が不足（{present}人）"));
                self.sites[s].need_water = m;
                self.sites[s].surplus = NONE;
            } else if water > 2.0 * wday && site.need_water != NONE {
                self.sites[s].need_water = NONE;
            }
            if food < fday && (self.sites[s].need_food == NONE || stale_f) {
                let m = self.shelter_msg(s as u32, MsgKind::NeedFood, format!("{name}: 食料が不足（{present}人）"));
                self.sites[s].need_food = m;
            } else if food > 2.0 * fday && self.sites[s].need_food != NONE {
                self.sites[s].need_food = NONE;
            }
            if water > sp.surplus_days * wday && self.sites[s].surplus == NONE && self.sites[s].deliveries > 0 {
                let m = self.shelter_msg(s as u32, MsgKind::Surplus, format!("{name}: 水・物資が余っている"));
                self.sites[s].surplus = m;
            } else if water < 2.0 * wday {
                self.sites[s].surplus = NONE;
            }
            // Daily roster: the aggregated 安否 of everyone here.
            if present >= 3 && t - self.sites[s].last_roster >= 1440.0 && t >= self.sites[s].open_at + 60.0 {
                self.sites[s].last_roster = t;
                let mut members = self.sites[s].people.clone();
                // Neighbours a leader reported safe here are on the roster too.
                members.extend(self.sites[s].reported_safe.drain(..));
                members.sort_unstable();
                members.dedup();
                let m = self.shelter_msg(s as u32, MsgKind::Roster, format!("{name}: 避難者{present}人の名簿"));
                self.msgs[m as usize].members = members;
            }
            // Concentration: a delivery piled up stock here while another shelter has none.
            let site = &self.sites[s];
            if site.water > sp.surplus_days * wday * 1.5
                && site.deliveries > 0
                && !any_short.is_empty()
                && t - site.last_concentration > 1440.0
                && t - site.last_delivery < 60.0
            {
                self.sites[s].last_concentration = t;
                self.counters.concentration_events += 1;
                let days = self.sites[s].water / wday;
                self.event(
                    EventKind::SupplyConcentration,
                    pos,
                    any_short.len() as u32,
                    format!("{name}: 水が{days:.1}日分に積み上がる一方、{}か所の避難所で水が尽きている", any_short.len()),
                );
            }
            let _ = zone;
        }
        self.board_step();
    }

    /// Creates a message about shelter `s` that its staff know and post on the board.
    fn shelter_msg(&mut self, s: u32, kind: MsgKind, text: String) -> u32 {
        let site = &self.sites[s as usize];
        let (pos, zone, present) = (site.pos, site.zone, site.present);
        let m = self.new_msg(kind, pos, zone, text);
        self.msgs[m as usize].site = s;
        self.msgs[m as usize].value = present as f32;
        self.sites[s as usize].board.insert(m);
        let staff = self.sites[s as usize].staff.clone();
        for a in staff {
            if self.agents[a as usize].site == s {
                self.learn(a, m, Via::Origin);
            }
        }
        m
    }

    /// Staff keep the board in sync with what they know; evacuees read it now and then.
    fn board_step(&mut self) {
        let dt = self.dt;
        let p_read = (dt / (self.spec.behavior.board_read_h as f32 * 60.0)).min(1.0);
        let mut items = std::mem::take(&mut self.scratch);
        for s in 0..self.sites.len() {
            if self.sites[s].kind != SiteKind::Shelter || self.sites[s].present == 0 {
                continue;
            }
            if self.sites[s].staff.iter().all(|&a| self.agents[a as usize].site != s as u32) {
                // A resident-run shelter: the first person present takes the role.
                if let Some(a) = self.sites[s].people.iter().copied().find(|&a| {
                    let ag = &self.agents[a as usize];
                    ag.mobile() && ag.age == 1 && ag.role == Role::Resident
                }) {
                    self.sites[s].staff.push(a);
                }
            }
            let staff = self.sites[s].staff.clone();
            for a in staff {
                if self.agents[a as usize].site != s as u32 {
                    continue;
                }
                self.sites[s].board.missing_from(&self.knows[a as usize], Some(&self.talk_mask), &mut items);
                for &m in &items {
                    self.sites[s].board.insert(m);
                }
                self.knows[a as usize].missing_from(&self.sites[s].board, None, &mut items);
                for &m in &items {
                    self.learn(a, m, Via::Board);
                }
            }
        }
        for s in 0..self.sites.len() {
            if self.sites[s].kind != SiteKind::Shelter || self.sites[s].board.count() == 0 {
                continue;
            }
            let people = std::mem::take(&mut self.sites[s].people);
            for &a in &people {
                if !self.agents[a as usize].mobile() || self.rng.gen::<f32>() >= p_read {
                    continue;
                }
                self.knows[a as usize].missing_from(&self.sites[s].board, None, &mut items);
                for &m in &items {
                    self.learn(a, m, Via::Board);
                }
            }
            self.sites[s].people = people;
        }
        self.scratch = items;
    }
}
