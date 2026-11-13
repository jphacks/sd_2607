//! Phones (cell, Wi-Fi via Starlink, Starlink Direct), gateways to the internet, face-to-face talk and the Mosimo mesh.

use std::collections::HashMap;

use rand::Rng;

use crate::model::*;
use crate::sim::{Sim, Via, INF};
use crate::spec::SiteKind;
use crate::util::merge_sorted;

fn exp_sample(rng: &mut impl Rng, mean: f32) -> f32 {
    -mean * (1.0 - rng.gen::<f32>()).ln()
}

/// Kinds a non-app user picks up from news and official websites when online.
const NEWS: [MsgKind; 5] = [MsgKind::TsunamiWarning, MsgKind::Official, MsgKind::RoadOpen, MsgKind::RoadCut, MsgKind::Isolated];
/// Kinds people post on SNS.
const SNS: [MsgKind; 5] = [MsgKind::Trapped, MsgKind::Isolated, MsgKind::NeedWater, MsgKind::NeedFood, MsgKind::Fire];

struct Link {
    voice: bool,
    data: bool,
    direct: bool,
}

impl Sim {
    /// A working tower of the agent's carrier (or its roaming partner) covering it.
    pub fn cell_tower(&self, i: u32) -> Option<u32> {
        let a = &self.agents[i as usize];
        if !a.on_map() {
            return None;
        }
        let mask = 1u32 << a.carrier | 1u32 << self.carrier_roam[a.carrier as usize];
        let key = ((a.pos.x / 5000.0).floor() as i32, (a.pos.y / 5000.0).floor() as i32);
        self.tower_grid.get(&key)?.iter().copied().find(|&ti| {
            let tw = &self.towers[ti as usize];
            tw.up && tw.carriers & mask != 0 && tw.pos.dist2(a.pos) <= tw.radius * tw.radius
        })
    }

    fn update_starlink(&mut self) {
        let t = self.t;
        let sl = &self.spec.starlink;
        for s in self.sites.iter_mut() {
            s.starlink_users = 0;
        }
        for a in &self.agents {
            if a.site != NONE && a.phone_on() && a.on_map() {
                self.sites[a.site as usize].starlink_users += 1;
            }
        }
        // Roughly a third of people present use the link at once.
        let active = 0.3;
        let mut total_mbps = 0.0;
        for s in &self.sites {
            if t >= s.starlink_from {
                total_mbps += s.starlink_users as f64 * active * sl.user_kbps / 1000.0;
            }
        }
        let cell_cap = sl.cell_capacity_mbps * self.params.starlink_capacity_factor;
        let cell_load = total_mbps / cell_cap.max(1e-6);
        self.starlink_load = cell_load as f32;
        for s in self.sites.iter_mut() {
            if t < s.starlink_from {
                s.starlink_ok = 1.0;
                continue;
            }
            let term = s.starlink_users as f64 * active * sl.user_kbps / 1000.0 / sl.terminal_mbps;
            s.starlink_ok = (1.0 / term.max(cell_load).max(1.0)) as f32;
        }
        if cell_load > 1.0 && !self.congestion_flag[0] {
            self.congestion_flag[0] = true;
            let pos = self.proj.to_xy(self.spec.view.center);
            self.event(
                EventKind::StarlinkCongestion,
                pos,
                0,
                format!("Starlinkの衛星セルが混雑（需要が容量の{:.0}%）", cell_load * 100.0),
            );
        } else if cell_load < 0.8 {
            self.congestion_flag[0] = false;
        }
    }

    pub(crate) fn phones_step(&mut self) {
        let t = self.t;
        let dt = self.dt;
        let b = self.spec.behavior.clone();
        let voice_p = self.spec.congestion.voice_success.at(t as f64) as f32;
        let data_p = self.spec.congestion.data_success.at(t as f64) as f32;
        self.update_starlink();
        let ds = self.spec.starlink_direct.clone();
        let sd_cap = if self.params.starlink_direct { ds.beam_msgs_per_min * dt as f64 } else { 0.0 };
        let sd_ok = if self.sd_prev_demand > 0.0 { (sd_cap / self.sd_prev_demand).min(1.0) } else { 1.0 } as f32;
        let mut sd_demand = 0.0f64;
        let wifi_r2 = (self.spec.starlink.wifi_radius_m as f32).powi(2);
        let drain = b.drain_per_h as f32 * dt / 60.0;
        let mesh_drain = b.mesh_drain_per_h as f32 * dt / 60.0;
        let charge = b.charge_per_h as f32 * dt / 60.0;
        for i in 0..self.agents.len() {
            let a = &self.agents[i];
            if !a.phone || !a.alive() || a.battery <= 0.0 {
                self.agents[i].online = false;
                continue;
            }
            let car_charge = a.car && a.charge_rank < b.car_charge as f32 && a.health == Health::Ok;
            let charging = a.health == Health::Ok && (self.has_power(i) || car_charge && a.moving());
            let a = &mut self.agents[i];
            if charging {
                a.battery = (a.battery + charge).min(1.0);
            } else {
                a.battery -= drain + if a.app { mesh_drain } else { 0.0 };
                if a.battery <= 0.0 {
                    a.battery = 0.0;
                    a.online = false;
                    continue;
                }
            }
            let due_sync = a.app && t >= a.next_sync;
            let due_phone = t >= a.next_phone;
            if !due_sync && !due_phone {
                continue;
            }
            let link = self.link(i as u32, voice_p, data_p, wifi_r2, &ds);
            self.agents[i].online = link.data;
            if due_sync {
                self.agents[i].next_sync = t + 10.0;
                if self.agents[i].app && !self.agents[i].anpi_posted && self.agents[i].health != Health::Dead {
                    self.agents[i].anpi_posted = true;
                    let id = i as u32;
                    merge_sorted(&mut self.carry[i], &[id], |_| true);
                }
                if link.data {
                    self.gateway_sync(i as u32);
                } else if link.direct && self.params.starlink_direct_data {
                    sd_demand += 1.0;
                    if self.rng.gen::<f32>() < sd_ok {
                        self.narrow_sync(i as u32);
                    } else {
                        self.counters.sd_dropped += 1;
                    }
                }
            }
            if due_phone {
                self.phone_attempt(i as u32, &link, sd_ok, &mut sd_demand);
            }
        }
        if sd_cap > 0.0 && sd_demand > sd_cap && !self.congestion_flag[1] {
            self.congestion_flag[1] = true;
            let pos = self.proj.to_xy(self.spec.view.center);
            self.event(
                EventKind::DirectCongestion,
                pos,
                sd_demand as u32,
                format!("Starlink Directのビームが混雑（送信要求が容量の{:.0}%）", sd_demand / sd_cap * 100.0),
            );
        } else if sd_demand < sd_cap * 0.8 {
            self.congestion_flag[1] = false;
        }
        // Connected cars upload what their navigation unit carries when a tower (or the internet off the map) is in reach.
        for i in 0..self.agents.len() as u32 {
            let a = &self.agents[i as usize];
            if !a.nav_connected || t < a.next_sync {
                continue;
            }
            let reach = if a.on_map() { self.nav_active(i) && self.cell_tower(i).is_some() } else { self.exits[a.offmap as usize].internet };
            if !reach {
                continue;
            }
            self.agents[i as usize].next_sync = t + 10.0;
            if self.rng.gen::<f32>() < data_p {
                let before = self.counters.gateway_uploads;
                self.gateway_sync(i);
                self.counters.nav_uploads += self.counters.gateway_uploads - before;
            }
        }
        self.sd_prev_demand = sd_demand;
        self.sd_demand = sd_demand;
        self.sd_capacity = sd_cap;
    }

    fn link(&mut self, i: u32, voice_p: f32, data_p: f32, wifi_r2: f32, ds: &crate::spec::DirectSpec) -> Link {
        let mut l = Link { voice: false, data: false, direct: false };
        let a = &self.agents[i as usize];
        if !a.on_map() {
            let x = &self.exits[a.offmap as usize];
            if x.internet {
                l.voice = true;
                l.data = true;
            } else {
                let z = &self.spec.zones[a.zone as usize];
                let up = (1.0 - z.cell_out.at(self.t as f64) as f32) * a.signal_q;
                l.data = self.rng.gen::<f32>() < up * data_p;
                l.voice = self.rng.gen::<f32>() < up * voice_p;
            }
            return l;
        }
        let tower = self.cell_tower(i);
        if let Some(ti) = tower {
            let tw = &mut self.towers[ti as usize];
            tw.users += 1;
            let cap = if tw.capacity.is_finite() { (tw.capacity / tw.users_prev.max(1) as f32).min(1.0) } else { 1.0 };
            let cap = cap * self.agents[i as usize].signal_q;
            l.data = self.rng.gen::<f32>() < data_p * cap;
            l.voice = self.rng.gen::<f32>() < voice_p * cap;
        }
        let a = &self.agents[i as usize];
        if a.site != NONE {
            let s = &self.sites[a.site as usize];
            if self.t >= s.starlink_from.min(s.fixed_from) && a.pos.dist2(s.pos) <= wifi_r2.max(s.radius * s.radius) {
                let ok = if self.t >= s.fixed_from { 1.0 } else { s.starlink_ok };
                if self.rng.gen::<f32>() < ok {
                    l.data = true;
                }
            }
        }
        if tower.is_none() && self.params.starlink_direct && a.direct {
            let vis = ds.visibility as f32 * if a.indoor { ds.indoor_factor as f32 } else { 1.0 };
            l.direct = self.rng.gen::<f32>() < vis;
        }
        l
    }

    /// An app with internet uploads what it carries and downloads what is new (the gateway role).
    fn gateway_sync(&mut self, i: u32) {
        let t = self.t;
        let mut items = std::mem::take(&mut self.scratch);
        self.internet.missing_from(&self.knows[i as usize], Some(&self.mesh_mask), &mut items);
        self.counters.gateway_uploads += items.len() as u64;
        for &m in &items {
            self.put_internet(m);
        }
        let carry = std::mem::take(&mut self.carry[i as usize]);
        for id in carry {
            if !self.anpi_uploaded[id as usize] {
                self.anpi_uploaded[id as usize] = true;
                self.counters.gateway_uploads += 1;
                let d = exp_sample(&mut self.rng, 120.0);
                let ag = &mut self.agents[id as usize];
                if ag.family_outside && ag.anpi_out > t + d {
                    ag.anpi_out = t + d;
                }
                self.anpi_online(id, t);
            }
        }
        self.knows[i as usize].missing_from(&self.internet, None, &mut items);
        for &m in &items {
            self.learn(i, m, Via::Internet);
        }
        self.scratch = items;
    }

    /// Over a narrow satellite link, send one item: the most urgent (priority extension) or the oldest.
    fn narrow_sync(&mut self, i: u32) {
        let mut items = std::mem::take(&mut self.scratch);
        self.internet.missing_from(&self.knows[i as usize], Some(&self.mesh_mask), &mut items);
        let pick = if self.params.priority {
            items.iter().copied().max_by_key(|&m| (self.msgs[m as usize].kind.priority(), u32::MAX - m))
        } else {
            items.first().copied()
        };
        let urgent = pick.map(|m| self.msgs[m as usize].kind.priority() >= 3).unwrap_or(false);
        let own_pending = !self.anpi_uploaded[i as usize] && self.carry[i as usize].binary_search(&i).is_ok();
        if own_pending && (!urgent || !self.params.priority) {
            self.anpi_uploaded[i as usize] = true;
            let t = self.t;
            let a = &mut self.agents[i as usize];
            if a.family_outside && a.anpi_out > t {
                a.anpi_out = t;
            }
            self.anpi_online(i, t);
        } else if let Some(m) = pick {
            self.put_internet(m);
        }
        self.counters.sd_sent += 1;
        self.scratch = items;
    }

    fn phone_attempt(&mut self, i: u32, link: &Link, sd_ok: f32, sd_demand: &mut f64) {
        let t = self.t;
        let b = &self.spec.behavior;
        let tries = b.phone_tries_per_h as f32;
        let sns_post = b.sns_post;
        let fake_rate = self.spec.hazards.fake_sns_rate;
        let a = &self.agents[i as usize];
        let zone = a.zone;
        let pending_anpi = a.family_outside && a.anpi_out > t && a.role.is_population();
        // Trapped people this person knows of and has not reported yet.
        let mut reports = Vec::new();
        for m in self.knows[i as usize].iter() {
            let msg = &self.msgs[m as usize];
            if msg.kind != MsgKind::Trapped || a.called.contains(&m) {
                continue;
            }
            let v = msg.victim;
            if v != NONE && self.victims[v as usize].outcome == VictimOutcome::Pending {
                reports.push(m);
            }
            if reports.len() >= 2 {
                break;
            }
        }
        // Bystanders who only heard of it call less often than the finder or the victim.
        let motivated: Vec<u32> = reports
            .iter()
            .copied()
            .filter(|&m| {
                let v = &self.victims[self.msgs[m as usize].victim as usize];
                v.agent == i || v.finder == i
            })
            .collect();
        for &m in &reports {
            if !motivated.contains(&m) && self.rng.gen::<f32>() > 0.3 {
                self.agents[i as usize].called.push(m);
            }
        }
        reports.retain(|m| !self.agents[i as usize].called.contains(m));

        if pending_anpi {
            if link.voice || link.data {
                self.agents[i as usize].anpi_out = t;
                self.counters.calls_ok += 1;
            } else if link.direct {
                *sd_demand += 1.0;
                if self.rng.gen::<f32>() < sd_ok {
                    self.agents[i as usize].anpi_out = t;
                    self.counters.sd_sent += 1;
                } else {
                    self.counters.sd_dropped += 1;
                }
            } else {
                self.counters.calls_failed += 1;
            }
        }
        for &m in &reports {
            if link.voice {
                if self.zones[zone as usize].calls_budget >= 1.0 {
                    self.zones[zone as usize].calls_budget -= 1.0;
                    self.hq_learn(zone, m);
                    self.agents[i as usize].called.push(m);
                    self.counters.calls_119_ok += 1;
                } else {
                    self.counters.calls_119_busy += 1;
                }
            } else if link.direct {
                // Starlink Direct relays an SOS text, not a voice call.
                *sd_demand += 1.0;
                if self.rng.gen::<f32>() < sd_ok {
                    self.hq_learn(zone, m);
                    self.agents[i as usize].called.push(m);
                    self.counters.sd_sent += 1;
                } else {
                    self.counters.sd_dropped += 1;
                }
            }
        }
        if (link.data || link.voice) && self.agents[i as usize].anpi_hq > t && self.rng.gen::<f64>() < self.spec.behavior.report_safety_to_hq {
            let a = &mut self.agents[i as usize];
            a.anpi_hq = t;
        }
        if link.data {
            let mut items = std::mem::take(&mut self.scratch);
            if !self.agents[i as usize].app {
                // Post on SNS what one knows; some rescue requests on SNS were fake.
                let known: Vec<u32> = self.knows[i as usize].iter().collect();
                for m in known {
                    let kind = self.msgs[m as usize].kind;
                    if !SNS.contains(&kind) || self.sns.get(m) || self.rng.gen::<f64>() >= sns_post {
                        continue;
                    }
                    self.sns.insert(m);
                    self.msgs[m as usize].sns_at = t;
                    if kind == MsgKind::Trapped && self.rng.gen::<f64>() < fake_rate / (1.0 - fake_rate).max(0.01) {
                        let base = self.msgs[m as usize].pos;
                        let pos = crate::util::Vec2::new(
                            base.x + self.rng.gen_range(-3000.0..3000.0),
                            base.y + self.rng.gen_range(-3000.0..3000.0),
                        );
                        let f = self.new_msg(MsgKind::FakeRescue, pos, zone, "SNS上の救助要請（実在しない）".into());
                        self.sns.insert(f);
                        self.msgs[f as usize].sns_at = t;
                    }
                }
            }
            // News and official sites.
            self.knows[i as usize].missing_from(&self.internet, None, &mut items);
            for &m in &items {
                if NEWS.contains(&self.msgs[m as usize].kind) && self.rng.gen::<f32>() < 0.5 {
                    self.learn(i, m, Via::Internet);
                }
            }
            self.scratch = items;
        }
        let pending = pending_anpi && self.agents[i as usize].anpi_out > t || !reports.is_empty();
        let mean = if pending { 60.0 / tries.max(0.1) } else { 240.0 };
        self.agents[i as usize].next_phone = t + exp_sample(&mut self.rng, mean).max(1.0);
    }

    pub(crate) fn contacts_step(&mut self) {
        let t = self.t;
        let dt = self.dt;
        let range = self.params.mesh_range_m as f32;
        let g = range.max(10.0);
        let mut grid: HashMap<(i32, i32), Vec<u32>> = HashMap::with_capacity(self.agents.len() / 4);
        let mut app_grid: HashMap<(i32, i32), Vec<u32>> = HashMap::new();
        for (i, a) in self.agents.iter().enumerate() {
            if !a.alive() || !a.on_map() {
                continue;
            }
            let key = ((a.pos.x / g).floor() as i32, (a.pos.y / g).floor() as i32);
            grid.entry(key).or_default().push(i as u32);
            if a.app && a.phone_on() || a.nav && self.nav_active(i as u32) {
                app_grid.entry(key).or_default().push(i as u32);
            }
        }
        // A running navigation unit offers a 「無事」 button: the driver posts for the family in the car.
        for i in 0..self.agents.len() as u32 {
            let a = &self.agents[i as usize];
            if !a.nav || a.anpi_posted || !self.nav_active(i) {
                continue;
            }
            let mut ids = vec![i];
            if let Some(&(si, h)) = self.household_of.get(i as usize).filter(|x| x.0 != NONE) {
                let pos = a.pos;
                for &m in &self.settlements[si as usize].households[h as usize].1 {
                    let b = &self.agents[m as usize];
                    if m != i && b.health == Health::Ok && !b.anpi_posted && b.pos.dist2(pos) < 400.0 {
                        ids.push(m);
                    }
                }
            }
            ids.sort_unstable();
            for &m in &ids {
                self.agents[m as usize].anpi_posted = true;
            }
            merge_sorted(&mut self.carry[i as usize], &ids, |_| true);
        }
        while (self.talk_from as usize) < self.msgs.len() && self.msgs[self.talk_from as usize].created < t - 2880.0 {
            self.talk_from += 1;
        }
        let b = self.spec.behavior.clone();
        let hour = self.clock_hour();
        let awake = if !(6.0..23.0).contains(&hour) { 0.15 } else { 1.0 };
        let p_talk = (b.talk_per_h as f32 * awake * dt / 60.0).min(1.0);

        // Passers-by notice trapped people nobody has found yet.
        for v in 0..self.victims.len() {
            let vic = &self.victims[v];
            if vic.outcome != VictimOutcome::Pending || vic.found_at <= t {
                continue;
            }
            let vp = self.agents[vic.agent as usize].pos;
            let key = ((vp.x / g).floor() as i32, (vp.y / g).floor() as i32);
            let Some(cell) = grid.get(&key) else { continue };
            let finder = cell.iter().copied().find(|&j| {
                let b = &self.agents[j as usize];
                j != vic.agent && b.mobile() && b.pos.dist2(vp) < 400.0
            });
            if let Some(f) = finder {
                if self.rng.gen::<f32>() < (dt / 30.0).min(1.0) {
                    self.victims[v].finder = f;
                    self.victims[v].found_at = t;
                }
            }
        }

        // Face-to-face talk.
        let mut items = std::mem::take(&mut self.scratch);
        for i in 0..self.agents.len() {
            let a = &self.agents[i];
            if !a.mobile() || !a.on_map() || self.rng.gen::<f32>() >= p_talk {
                continue;
            }
            let key = ((a.pos.x / g).floor() as i32, (a.pos.y / g).floor() as i32);
            let Some(cell) = grid.get(&key) else { continue };
            if cell.len() < 2 {
                continue;
            }
            let mut partner = NONE;
            for _ in 0..3 {
                let j = cell[self.rng.gen_range(0..cell.len())];
                if j as usize != i && self.agents[j as usize].mobile() && self.agents[j as usize].pos.dist2(a.pos) < 100.0 {
                    partner = j;
                    break;
                }
            }
            if partner == NONE {
                continue;
            }
            self.counters.talks += 1;
            self.tell(i as u32, partner, b.talk_items, &mut items);
            self.tell(partner, i as u32, b.talk_items, &mut items);
        }

        // Mosimo mesh: app-to-app exchange over BLE / Wi-Fi, limited by range, discovery and contact time.
        let rate = self.params.mesh_contacts_per_min as f32 * dt;
        let mut carry_buf: Vec<u32> = Vec::new();
        // HashMap order varies between processes; sort so a seed always gives the same run.
        let mut app_ids: Vec<u32> = app_grid.values().flatten().copied().collect();
        app_ids.sort_unstable();
        for &i in &app_ids {
            let pos = self.agents[i as usize].pos;
            let mut n = rate.floor() as u32;
            if self.rng.gen::<f32>() < rate.fract() {
                n += 1;
            }
            let (cx, cy) = ((pos.x / g).floor() as i32, (pos.y / g).floor() as i32);
            for _ in 0..n {
                let key = (cx + self.rng.gen_range(-1..=1), cy + self.rng.gen_range(-1..=1));
                let Some(cell) = app_grid.get(&key) else { continue };
                let j = cell[self.rng.gen_range(0..cell.len())];
                if j == i {
                    continue;
                }
                let Some(budget) = self.contact_budget(i, j) else { continue };
                self.mesh_exchange(i, j, budget, &mut items, &mut carry_buf);
            }
        }
        self.scratch = items;
    }

    fn speed_mps(&self, i: u32) -> f32 {
        match &self.agents[i as usize].plan {
            Plan::Move { speed, .. } => speed / 60.0,
            _ => 0.0,
        }
    }

    /// Bytes two phones can exchange during this step, or None if they are out of range or never connect.
    ///
    /// Reach is shorter indoors and between houses (walls); passing contacts last `≈1.4·r/v`;
    /// discovery takes seconds on Android and minutes between backgrounded iPhones.
    fn contact_budget(&mut self, i: u32, j: u32) -> Option<f64> {
        let (nav_a, nav_b) = (self.nav_active(i), self.nav_active(j));
        let (a, b) = (&self.agents[i as usize], &self.agents[j as usize]);
        let p = &self.params;
        // A running car's navigation unit has a roof antenna and stays on; otherwise the phone.
        let reach = |nav: bool, indoor: bool| if nav { p.carnav_range_m } else if indoor { p.mesh_range_indoor_m } else { p.mesh_range_m } as f32;
        let mut r = reach(nav_a, a.indoor).min(reach(nav_b, b.indoor));
        let d2 = a.pos.dist2(b.pos);
        if a.indoor && b.indoor && !nav_a && !nav_b && (a.site == NONE || a.site != b.site) && d2 > 100.0 {
            r *= 0.6;
        }
        if d2 > r * r {
            return None;
        }
        let step_s = self.dt * 60.0;
        let v = self.speed_mps(i) + self.speed_mps(j);
        let contact = if v < 0.2 { step_s } else { (1.4 * r / v).min(step_s) };
        let disc = match (a.ios && !nav_a, b.ios && !nav_b) {
            (false, false) => p.discovery_s,
            (true, true) => p.discovery_ios_pair_s,
            _ => p.discovery_ios_s,
        } as f32;
        if self.rng.gen::<f32>() >= 1.0 - (-contact / disc).exp() {
            self.counters.mesh_missed += 1;
            return None;
        }
        let usable = contact - (disc.min(contact) * 0.5) - p.mesh_setup_s as f32;
        if usable <= 0.0 {
            self.counters.mesh_missed += 1;
            return None;
        }
        if nav_a || nav_b {
            self.counters.nav_exchanges += 1;
        }
        Some(usable as f64 * p.mesh_kbps * 1000.0 / 8.0)
    }

    /// Pass up to `k` items `from` knows and `to` does not, most urgent and newest first.
    pub(crate) fn tell(&mut self, from: u32, to: u32, k: u32, items: &mut Vec<u32>) {
        if self.knows[to as usize].same_as(&self.knows[from as usize]) {
            return;
        }
        // Most urgent first, newest within a level.
        items.clear();
        for p in (0..self.prio_masks.len()).rev() {
            self.knows[to as usize].newest_missing(&self.knows[from as usize], &self.prio_masks[p], self.talk_from, k as usize, items);
            if items.len() >= k as usize {
                break;
            }
        }
        let picked: Vec<u32> = items.clone();
        for m in picked {
            self.learn(to, m, Via::Talk);
        }
    }

    /// Set reconciliation, then transfer within the byte budget: most urgent first with the priority
    /// extension, otherwise oldest first. 安否 posts ride along at the priority of a shelter need.
    fn mesh_exchange(&mut self, i: u32, j: u32, budget: f64, items: &mut Vec<u32>, carry_buf: &mut Vec<u32>) {
        let t = self.t;
        let same = self.knows[i as usize].same_as(&self.knows[j as usize]);
        let carries = self.carry[i as usize] != self.carry[j as usize];
        if same && !carries {
            return;
        }
        self.counters.mesh_exchanges += 1;
        self.agents[i as usize].meshed_at = t;
        self.agents[j as usize].meshed_at = t;
        // (priority, order key, is_anpi, id, receiver, bytes)
        let mut cand: Vec<(u8, u32, bool, u32, u32, u32)> = Vec::new();
        for (x, y) in [(i, j), (j, i)] {
            if !same {
                self.knows[y as usize].missing_from(&self.knows[x as usize], Some(&self.mesh_mask), items);
                for &m in items.iter() {
                    let msg = &self.msgs[m as usize];
                    cand.push((msg.kind.priority(), msg.created as u32, false, m, y, msg.bytes()));
                }
            }
            if carries {
                let (cx, cy) = (&self.carry[x as usize], &self.carry[y as usize]);
                let mut k = 0;
                for &id in cx {
                    while k < cy.len() && cy[k] < id {
                        k += 1;
                    }
                    if (k >= cy.len() || cy[k] != id) && !self.anpi_uploaded[id as usize] {
                        cand.push((2, 0, true, id, y, ANPI_BYTES));
                    }
                }
            }
        }
        let total: f64 = cand.iter().map(|c| c.5 as f64).sum();
        if total > budget {
            self.counters.mesh_truncated += 1;
            if self.params.priority {
                cand.sort_by_key(|c| (std::cmp::Reverse(c.0), c.1));
            } else {
                cand.sort_by_key(|c| c.1);
            }
        }
        let mut used = 0.0;
        let mut anpi_to_i = Vec::new();
        let mut anpi_to_j = Vec::new();
        for c in cand {
            if used + c.5 as f64 > budget {
                if total > budget {
                    break;
                }
            }
            used += c.5 as f64;
            if c.2 {
                if c.4 == i { anpi_to_i.push(c.3) } else { anpi_to_j.push(c.3) }
            } else {
                self.counters.mesh_transfers += 1;
                self.learn(c.4, c.3, Via::Mesh);
            }
        }
        self.counters.mesh_bytes += used as u64;
        for (dst, mut ids) in [(i, anpi_to_i), (j, anpi_to_j)] {
            if ids.is_empty() {
                continue;
            }
            ids.sort_unstable();
            carry_buf.clear();
            carry_buf.extend_from_slice(&ids);
            merge_sorted(&mut self.carry[dst as usize], carry_buf, |_| true);
        }
    }

    /// Shelter staff report to the municipal HQ over whatever link the shelter has.
    pub(crate) fn shelter_reports(&mut self) {
        let t = self.t;
        let voice_p = self.spec.congestion.voice_success.at(t as f64) as f32;
        let data_p = self.spec.congestion.data_success.at(t as f64) as f32;
        let mut items = std::mem::take(&mut self.scratch);
        for s in 0..self.sites.len() {
            let site = &self.sites[s];
            if site.kind != SiteKind::Shelter || site.present == 0 || site.next_report > t {
                continue;
            }
            let zone = site.zone;
            if self.zones[zone as usize].hq == NONE {
                continue;
            }
            self.sites[s].next_report = t + 120.0 + self.rng.gen::<f32>() * 120.0;
            let internet = self.site_internet(s as u32);
            let satphone = t >= self.sites[s].satphone_from;
            // Any staff phone with signal can carry a short report.
            let mut staff_data = false;
            let mut staff_voice = false;
            for &a in &self.sites[s].staff.clone() {
                if self.agents[a as usize].site != s as u32 || !self.agents[a as usize].phone_on() {
                    continue;
                }
                if self.cell_tower(a).is_some() {
                    staff_data |= self.rng.gen::<f32>() < data_p;
                    staff_voice |= self.rng.gen::<f32>() < voice_p;
                }
            }
            self.zones[zone as usize].knows.missing_from(&self.sites[s].board, None, &mut items);
            if internet || staff_data {
                for &m in &items {
                    self.hq_learn(zone, m);
                }
            } else if satphone || staff_voice {
                // A voice call conveys only the few most urgent points.
                items.sort_by_key(|&m| (std::cmp::Reverse(self.msgs[m as usize].kind.priority()), std::cmp::Reverse(m)));
                let top: Vec<u32> = items.iter().take(4).copied().collect();
                for m in top {
                    self.hq_learn(zone, m);
                }
                // The roster's headcount fits in a call even if the names do not.
                let roster = (0..self.msgs.len() as u32)
                    .rev()
                    .find(|&m| self.msgs[m as usize].kind == MsgKind::Roster && self.msgs[m as usize].site == s as u32);
                if let Some(r) = roster {
                    self.sites[s].hq_known = true;
                    let _ = r;
                }
            }
        }
        self.scratch = items;
        let _ = INF;
    }
}
