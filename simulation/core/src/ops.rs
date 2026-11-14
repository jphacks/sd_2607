//! Response operations: municipal HQs and the prefecture/rescue coordination ("ops"),
//! rescue teams, supply trucks and the staff cars that visit shelters.

use rand::Rng;

use crate::model::*;
use crate::sim::{Sim, Via, INF};
use crate::spec::SiteKind;
use crate::util::Vec2;

/// Kinds the HQ republishes on its website / 通れるマップ once it knows them.
const PUBLISHED: [MsgKind; 3] = [MsgKind::RoadCut, MsgKind::Isolated, MsgKind::Fire];

impl Sim {
    pub(crate) fn spawn_responders(&mut self) {
        let t = self.t;
        let scale = self.params.population_scale.max(0.05);
        let rs = self.spec.rescue.clone();
        let target = (rs.teams.at(t as f64) * scale * self.params.rescue_capacity_factor).round() as u32;
        let base = rs
            .base
            .as_ref()
            .and_then(|b| self.sites.iter().position(|s| &s.id == b))
            .map(|i| i as u32)
            .or_else(|| self.nearest_site(self.proj.to_xy(self.spec.view.center), |s| s.kind == SiteKind::Hq));
        let fire: Vec<u32> = (0..self.sites.len() as u32).filter(|&s| self.sites[s as usize].kind == SiteKind::Fire).collect();
        while self.teams_spawned < target {
            let local = self.teams_spawned < ((rs.local_teams as f64 * scale).round() as u32).max(1) && !fire.is_empty();
            let site = if local { fire[self.teams_spawned as usize % fire.len()] } else { base.unwrap_or(0) };
            self.spawn_team(Role::Rescuer, site, local, false);
            self.teams_spawned += 1;
        }
        let helis = (rs.helicopters.at(t as f64) * scale).round() as u32;
        while self.helis_spawned < helis {
            self.spawn_team(Role::Rescuer, base.unwrap_or(0), false, true);
            self.helis_spawned += 1;
        }
        let sp = self.spec.supply.clone();
        if self.depot != NONE {
            let trucks = (sp.trucks.at(t as f64) * scale).round() as u32;
            while self.trucks_spawned < trucks {
                self.spawn_team(Role::Truck, self.depot, false, false);
                self.trucks_spawned += 1;
            }
            let dt = self.dt as f64;
            self.depot_water += sp.water_l_per_h.at(t as f64) * scale * dt / 60.0;
            self.depot_food += sp.food_per_h.at(t as f64) * scale * dt / 60.0;
        }
        if !self.liaisons_spawned && t >= (sp.liaison_from_h * 60.0) as f32 {
            self.liaisons_spawned = true;
            for z in 0..self.zones.len() {
                let hq = self.zones[z].hq;
                if hq == NONE {
                    continue;
                }
                for _ in 0..sp.liaisons_per_zone {
                    self.spawn_team(Role::Liaison, hq, true, false);
                }
            }
        }
    }

    fn spawn_team(&mut self, role: Role, site: u32, local: bool, heli: bool) {
        let pos = self.sites[site as usize].pos;
        let zone = self.sites[site as usize].zone;
        let id = self.agents.len();
        let staff_app = self.rng.gen::<f64>() < self.params.staff_adoption;
        let mut a = self.agents[0].clone();
        a.pos = pos;
        a.home = pos;
        a.role = role;
        a.zone = zone;
        a.settlement = NONE;
        a.site = site;
        a.shelter = site;
        a.age = 1;
        a.health = Health::Ok;
        a.victim = NONE;
        a.phone = true;
        a.battery = 1.0;
        a.app = staff_app;
        a.car = true;
        a.family_outside = false;
        a.anpi_out = INF;
        a.anpi_posted = true;
        a.plan = Plan::Idle;
        a.in_tsunami_zone = false;
        a.tsunami_evacuated = false;
        a.offmap = NO_EXIT;
        a.left_for_good = false;
        a.called = Vec::new();
        a.team = self.teams.len() as u32;
        // Emergency vehicles carry a navigation unit at the same rate (deterministic per vehicle).
        let h = (self.teams.len() as u64).wrapping_mul(2_654_435_761) % 1000;
        a.nav = !heli && (h as f64) < self.params.carnav_adoption * 1000.0;
        a.nav_connected = a.nav && (((h * 7) % 1000) as f64) < self.params.carnav_connected * 1000.0;
        a.evac_done = true;
        a.warned_at = INF;
        self.agents.push(a);
        self.knows.push(self.ops.clone());
        self.carry.push(Vec::new());
        self.anpi_uploaded.push(true);
        self.teams.push(Team {
            agent: id as u32,
            local_zone: if local { zone } else { u16::MAX },
            job: TeamJob::None,
            busy_until: INF,
            heli,
            water: 0.0,
            food: 0.0,
            base: site,
            last_visit: vec![-INF; self.sites.len()],
            next_try: 0.0,
        });
    }

    pub(crate) fn ops_step(&mut self) {
        self.shelter_reports();
        self.hq_sync();
        if self.t >= self.next_hourly {
            self.next_hourly += 60.0;
            self.isolation_check();
            self.unknown_shelter_check();
        }
        self.teams_step();
    }

    /// HQ ↔ coordination sync, monitoring of the internet and SNS, and official republishing.
    fn hq_sync(&mut self) {
        let t = self.t;
        let dt = self.dt;
        let appview_p = 1.0 - (-dt / if self.params.hq_appview { 30.0 } else { 720.0 }).exp();
        let sns_p = 1.0 - (-dt / 720.0f32).exp();
        let publish_p = 1.0 - (-dt / 180.0f32).exp();
        let mut items = std::mem::take(&mut self.scratch);
        for z in 0..self.zones.len() {
            let hq = self.zones[z].hq;
            if hq == NONE {
                continue;
            }
            let internet = self.site_internet(hq) || self.hq_cell(hq);
            let satphone = t >= self.sites[hq as usize].satphone_from;
            // Up to the coordination.
            self.ops.missing_from(&self.zones[z].knows, None, &mut items);
            if internet {
                for &m in &items {
                    self.ops_learn(m);
                }
            } else if satphone {
                items.sort_by_key(|&m| std::cmp::Reverse(self.msgs[m as usize].kind.priority()));
                let n = ((dt / 60.0) * 20.0).ceil() as usize;
                let top: Vec<u32> = items.iter().take(n).copied().collect();
                for m in top {
                    self.ops_learn(m);
                }
            }
            // Down from the coordination (road status, official notices).
            if internet || satphone {
                self.zones[z].knows.missing_from(&self.ops, Some(&self.talk_mask), &mut items);
                for &m in &items {
                    if PUBLISHED.contains(&self.msgs[m as usize].kind) || self.msgs[m as usize].official {
                        self.hq_learn(z as u16, m);
                    }
                }
            }
            if !internet {
                continue;
            }
            // Watching the internet: AppView (structured, located posts) or plain web search.
            self.zones[z].knows.missing_from(&self.internet, None, &mut items);
            for &m in &items {
                if self.msgs[m as usize].zone as usize == z && self.rng.gen::<f32>() < appview_p {
                    self.hq_learn(z as u16, m);
                }
            }
            self.zones[z].knows.missing_from(&self.sns, None, &mut items);
            for &m in &items {
                if self.msgs[m as usize].zone as usize == z && self.rng.gen::<f32>() < sns_p {
                    self.hq_learn(z as u16, m);
                }
            }
            // Republishing what the HQ knows about roads and isolation.
            self.internet.missing_from(&self.zones[z].knows, None, &mut items);
            for &m in &items {
                if PUBLISHED.contains(&self.msgs[m as usize].kind) && self.rng.gen::<f32>() < publish_p {
                    self.put_internet(m);
                }
            }
        }
        // The coordination itself watches SNS too (prefecture SNS team, police).
        self.ops.missing_from(&self.sns, None, &mut items);
        for &m in &items {
            if self.rng.gen::<f32>() < sns_p {
                self.ops_learn(m);
            }
        }
        if self.params.hq_appview {
            self.ops.missing_from(&self.internet, None, &mut items);
            for &m in &items {
                if self.rng.gen::<f32>() < appview_p {
                    self.ops_learn(m);
                }
            }
        }
        self.scratch = items;
    }

    fn hq_cell(&self, hq: u32) -> bool {
        let s = &self.sites[hq as usize];
        self.towers.iter().any(|tw| tw.up && tw.pos.dist2(s.pos) <= tw.radius * tw.radius)
    }

    /// Settlements cut off from their HQ by road; residents report it once they realise.
    fn isolation_check(&mut self) {
        if self.nodes.is_empty() {
            return;
        }
        let t = self.t;
        // A settlement is isolated when no vehicle can reach it from outside help (any HQ, the depot or off the map).
        let reach = self.reachable_from_outside(t);
        for s in 0..self.settlements.len() {
            let st = &self.settlements[s];
            if st.node == NONE {
                continue;
            }
            let reachable = reach[st.node as usize];
            let st = &mut self.settlements[s];
            if reachable {
                st.isolated_since = INF;
                continue;
            }
            if st.isolated_since == INF {
                st.isolated_since = t;
            }
            let since = st.isolated_since;
            if st.isolated_msg == NONE && t - since >= 180.0 {
                let (pos, zone, name, pop) = (st.pos, st.zone, st.name.clone(), st.pop);
                let m = self.new_msg(MsgKind::Isolated, pos, zone, format!("{name}が孤立（約{pop}人）"));
                self.msgs[m as usize].settlement = s as u32;
                self.msgs[m as usize].value = pop as f32;
                self.settlements[s].isolated_msg = m;
                // Local leaders and those present know first.
                let locals: Vec<u32> = (0..self.agents.len() as u32)
                    .filter(|&a| {
                        let ag = &self.agents[a as usize];
                        ag.settlement == s as u32 && ag.mobile() && ag.age == 1 && ag.on_map()
                    })
                    .take(5)
                    .collect();
                for a in locals {
                    self.learn(a, m, Via::Origin);
                }
            }
            let st = &self.settlements[s];
            let m = st.isolated_msg;
            let known = m != NONE && (self.msgs[m as usize].ops_at <= t || self.msgs[m as usize].hq_at <= t);
            if !known && t - since >= 48.0 * 60.0 && !st.isolated_reported {
                let (pos, name, pop) = (st.pos, st.name.clone(), st.pop);
                self.settlements[s].isolated_reported = true;
                self.event(EventKind::IsolatedUnknown, pos, pop, format!("{name}（約{pop}人）が孤立して48時間、本部は把握していない"));
            }
        }
    }

    fn unknown_shelter_check(&mut self) {
        let t = self.t;
        for s in 0..self.sites.len() {
            let site = &self.sites[s];
            if site.kind != SiteKind::Shelter || site.unknown_flagged || site.known_initially || site.hq_known || site.ops_known {
                continue;
            }
            if site.present >= 10 && site.peak > 0 && t >= 72.0 * 60.0 {
                let (pos, name, p) = (site.pos, site.name.clone(), site.present);
                self.sites[s].unknown_flagged = true;
                self.event(EventKind::ShelterUnknown, pos, p, format!("{name}（{p}人）の存在を本部が3日たっても把握していない"));
            }
        }
    }

    /// Knowledge a team acts on: its municipality's for local fire departments, otherwise the coordination's.
    fn team_knows(&self, team: u32) -> &crate::util::BitSet {
        let z = self.teams[team as usize].local_zone;
        if z != u16::MAX {
            &self.zones[z as usize].knows
        } else {
            &self.ops
        }
    }

    fn teams_step(&mut self) {
        let t = self.t;
        let dt = self.dt;
        let rs = self.spec.rescue.clone();
        let sp = self.spec.supply.clone();
        for k in 0..self.teams.len() {
            let ag = self.teams[k].agent;
            if self.agents[ag as usize].moving() {
                continue;
            }
            let role = self.agents[ag as usize].role;
            let job = self.teams[k].job;
            match (role, job) {
                (Role::Rescuer, TeamJob::Victim(v)) => {
                    let vic = &self.victims[v as usize];
                    if vic.outcome != VictimOutcome::Pending {
                        self.teams[k].job = TeamJob::None;
                        self.teams[k].busy_until = INF;
                        continue;
                    }
                    if self.teams[k].busy_until == INF {
                        let d = self.rng.gen_range(rs.duration_h[0]..=rs.duration_h[1]) as f32 * 60.0;
                        self.teams[k].busy_until = t + d;
                    } else if t >= self.teams[k].busy_until {
                        if t < vic.death_at {
                            self.free_victim(v, VictimOutcome::RescuedByTeam);
                        }
                        self.teams[k].job = TeamJob::None;
                        self.teams[k].busy_until = INF;
                    }
                }
                (Role::Rescuer, TeamJob::Fake(m)) => {
                    if self.teams[k].busy_until == INF {
                        self.teams[k].busy_until = t + 60.0;
                        self.counters.fake_dispatches += 1;
                        let pos = self.msgs[m as usize].pos;
                        self.event(EventKind::FakeDispatch, pos, 1, "SNSの救助要請で出動したが要救助者はいなかった".into());
                    } else if t >= self.teams[k].busy_until {
                        self.teams[k].job = TeamJob::None;
                        self.teams[k].busy_until = INF;
                    }
                }
                (Role::Rescuer, TeamJob::Search(s)) => {
                    self.settlements[s as usize].search_effort += dt / 60.0;
                    let p = (rs.search_find_per_h as f32 * dt / 60.0).min(1.0);
                    if self.rng.gen::<f32>() < p {
                        let unfound = (0..self.victims.len() as u32).find(|&v| {
                            let x = &self.victims[v as usize];
                            x.settlement == s && x.outcome == VictimOutcome::Pending && x.team == NONE && {
                                let m = &self.msgs[x.msg as usize];
                                m.ops_at > t && m.hq_at > t
                            }
                        });
                        if let Some(v) = unfound {
                            self.counters.found_by_search += 1;
                            let m = self.victims[v as usize].msg;
                            if self.victims[v as usize].found_at > t {
                                self.victims[v as usize].found_at = t;
                                self.victims[v as usize].finder = ag;
                            }
                            self.learn(ag, m, Via::Origin);
                            self.ops_learn(m);
                            let zone = self.msgs[m as usize].zone;
                            self.hq_learn(zone, m);
                            self.victims[v as usize].team = k as u32;
                            self.teams[k].job = TeamJob::Victim(v);
                            self.teams[k].busy_until = INF;
                            let vp = self.agents[self.victims[v as usize].agent as usize].pos;
                            self.agents[ag as usize].plan = Plan::Move {
                                pts: vec![self.agents[ag as usize].pos, vp],
                                edges: vec![NONE],
                                seg: 0,
                                along: 0.0,
                                speed: 60.0,
                                foot: true,
                                arrive: Arrive::Job,
                                started: t,
                            };
                            continue;
                        }
                    }
                    if t >= self.teams[k].busy_until {
                        self.teams[k].job = TeamJob::None;
                        self.teams[k].busy_until = INF;
                    }
                }
                (Role::Rescuer, _) => {
                    if t >= self.teams[k].next_try {
                        self.assign_rescue(k as u32);
                        if self.teams[k].job == TeamJob::None {
                            self.teams[k].next_try = t + 30.0;
                        }
                    }
                }
                (Role::Truck, TeamJob::Deliver(s)) => {
                    self.deliver(k as u32, s);
                    let depot = self.teams[k].base;
                    self.teams[k].job = TeamJob::Return;
                    let dest = self.sites[depot as usize].pos;
                    if !self.plan_move(ag, dest, self.sites[depot as usize].node, Arrive::Site(depot)) {
                        self.agents[ag as usize].pos = dest;
                    }
                }
                (Role::Truck, _) => {
                    if t >= self.next_plan || job == TeamJob::Return {
                        self.teams[k].job = TeamJob::None;
                        self.assign_delivery(k as u32, &sp);
                    }
                }
                (Role::Liaison, TeamJob::Visit(s)) => {
                    // Exchange with the shelter, then drive back to the HQ to report.
                    self.deposit_at_shelter(ag, s);
                    self.sites[s as usize].hq_known = true;
                    self.teams[k].last_visit[s as usize] = t;
                    let hq = self.teams[k].base;
                    self.teams[k].job = TeamJob::Return;
                    let dest = self.sites[hq as usize].pos;
                    if !self.plan_move(ag, dest, self.sites[hq as usize].node, Arrive::Site(hq)) {
                        self.teams[k].job = TeamJob::None;
                    }
                }
                (Role::Liaison, _) => {
                    if t >= self.teams[k].next_try {
                        self.assign_visit(k as u32);
                        if self.teams[k].job == TeamJob::None {
                            self.teams[k].next_try = t + 30.0;
                        }
                    }
                }
                _ => {}
            }
        }
        if t >= self.next_plan {
            self.next_plan = t + (sp.plan_every_h * 60.0) as f32;
        }
    }

    fn assign_rescue(&mut self, k: u32) {
        let t = self.t;
        let ag = self.teams[k as usize].agent;
        let pos = self.agents[ag as usize].pos;
        // Known, unassigned, pending trapped people; then fake requests from SNS.
        let mut cands: Vec<(f32, TeamJob, Vec2)> = Vec::new();
        let knows = self.team_knows(k);
        let pool: Vec<u32> = self.kind_mask[MsgKind::Trapped.index()]
            .iter()
            .chain(self.kind_mask[MsgKind::FakeRescue.index()].iter())
            .filter(|&m| knows.get(m))
            .collect();
        for m in pool {
            let msg = &self.msgs[m as usize];
            let (job, when) = match msg.kind {
                MsgKind::Trapped => {
                    let v = msg.victim;
                    let vic = &self.victims[v as usize];
                    // Reports say when family or neighbours are already freeing someone; teams leave those.
                    if vic.outcome != VictimOutcome::Pending || vic.team != NONE || vic.neighbor_free_at < INF {
                        continue;
                    }
                    // Triage: someone pinned and injured comes before someone who is merely shut in.
                    let when = msg.ops_at.min(msg.hq_at) + if vic.severe { 0.0 } else { 720.0 };
                    (TeamJob::Victim(v), when)
                }
                MsgKind::FakeRescue => {
                    // Most fakes were copies or named places that do not exist, and get screened out.
                    let screened = (m as u64).wrapping_mul(2_654_435_761) % 100 < 70;
                    if screened || self.fakes_dispatched.contains(&m) {
                        continue;
                    }
                    (TeamJob::Fake(m), msg.ops_at.min(msg.hq_at))
                }
                _ => continue,
            };
            // Reports seen only on SNS (unsigned, unlocated, often copied) rank below ones that came
            // by phone, in person or as signed, located posts.
            let sns_only = msg.sns_at < INF && msg.internet_at == INF && msg.hq_at == INF;
            // Nearest first (travel minutes); a report waiting 10 h counts like an hour less of driving.
            let score = pos.dist(msg.pos) / 500.0 + (when - t) * 0.1 + if sns_only { 360.0 } else { 0.0 } + (when - msg.ops_at.min(msg.hq_at));
            cands.push((score, job, msg.pos));
        }
        cands.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(std::cmp::Ordering::Equal));
        if !cands.is_empty() {
            self.brief(ag);
            // Take the first job the team can actually get to; unreachable ones wait for roads or helicopters.
            for (_, job, dest) in cands.into_iter().take(8) {
                if self.try_dispatch(k, job, dest) {
                    return;
                }
            }
        }
        let rs = &self.spec.rescue;
        {
            // Nothing reported: search the worst-hit area not yet searched.
            if t < (rs.search_from_h * 60.0) as f32 || self.teams[k as usize].heli {
                return;
            }
            let mut order: Vec<usize> = (0..self.settlements.len()).filter(|&s| self.settlements[s].collapse > 0.0).collect();
            let need = |st: &crate::model::Settlement| st.collapse * st.pop as f64 / (1.0 + st.search_effort as f64);
            order.sort_by(|&a, &b| need(&self.settlements[b]).partial_cmp(&need(&self.settlements[a])).unwrap_or(std::cmp::Ordering::Equal));
            self.brief(ag);
            for s in order.into_iter().take(5) {
                let dest = self.settlements[s].pos;
                let node = self.settlements[s].node;
                if self.plan_move(ag, dest, node, Arrive::Job) {
                    self.teams[k as usize].job = TeamJob::Search(s as u32);
                    self.teams[k as usize].busy_until = t + 360.0;
                    self.settlements[s].search_effort += 1.0;
                    return;
                }
            }
        }
    }

    fn try_dispatch(&mut self, k: u32, job: TeamJob, dest: Vec2) -> bool {
        let t = self.t;
        let ag = self.teams[k as usize].agent;
        let pos = self.agents[ag as usize].pos;
        let reached = if self.teams[k as usize].heli {
            self.agents[ag as usize].plan = Plan::Move {
                pts: vec![pos, dest],
                edges: vec![NONE],
                seg: 0,
                along: 0.0,
                speed: 2500.0,
                foot: true,
                arrive: Arrive::Job,
                started: t,
            };
            true
        } else {
            self.plan_move(ag, dest, NONE, Arrive::Job) || self.plan_on_foot(ag, dest)
        };
        if !reached {
            return false;
        }
        match job {
            TeamJob::Victim(v) => self.victims[v as usize].team = k,
            TeamJob::Fake(m) => self.fakes_dispatched.push(m),
            _ => {}
        }
        self.teams[k as usize].job = job;
        self.teams[k as usize].busy_until = INF;
        true
    }

    /// A responder is briefed with what the coordination knows before setting out.
    fn brief(&mut self, ag: u32) {
        let mut items = std::mem::take(&mut self.scratch);
        self.knows[ag as usize].missing_from(&self.ops, None, &mut items);
        for &m in &items {
            self.learn(ag, m, Via::Phone);
        }
        self.scratch = items;
    }

    /// Rescue teams walk in over roads passable on foot when vehicles cannot get through.
    fn plan_on_foot(&mut self, ag: u32, dest: Vec2) -> bool {
        let pos = self.agents[ag as usize].pos;
        let from = self.nearest_node(pos);
        let to = self.nearest_node(dest);
        let t = self.t;
        let knows = &self.knows[ag as usize];
        let Some((nodes, redges, _)) = self.route(from, to, true, |e| self.believed_cut(knows, e) && !self.edges[e as usize].foot_ok) else {
            return false;
        };
        let mut pts = vec![pos];
        let mut edges = vec![NONE];
        for (k, &n) in nodes.iter().enumerate() {
            pts.push(self.nodes[n as usize]);
            if k > 0 {
                edges.push(redges[k - 1]);
            }
        }
        pts.push(dest);
        edges.push(NONE);
        let a = &mut self.agents[ag as usize];
        a.site = NONE;
        a.plan = Plan::Move { pts, edges, seg: 0, along: 0.0, speed: 60.0, foot: true, arrive: Arrive::Job, started: t };
        true
    }

    fn assign_delivery(&mut self, k: u32, sp: &crate::spec::SupplySpec) {
        let t = self.t;
        if self.depot_water < 200.0 && self.depot_food < 200.0 {
            return;
        }
        // The coordination sends to shelters it knows: requests first, then by believed headcount.
        let mut best: Option<(f64, u32)> = None;
        for s in 0..self.sites.len() {
            let site = &self.sites[s];
            if site.kind != SiteKind::Shelter || !(site.ops_known || site.known_initially) || t < site.open_at {
                continue;
            }
            if self.teams.iter().any(|tm| tm.job == TeamJob::Deliver(s as u32)) {
                continue;
            }
            let pop = if site.ops_pop >= 0.0 { site.ops_pop as f64 } else { site.capacity as f64 * 0.5 };
            let since = ((t - site.last_delivery) / 60.0) as f64;
            let mut score = pop * since;
            if site.ops_need {
                score += 1e7;
            }
            if best.map(|b| score > b.0).unwrap_or(true) {
                best = Some((score, s as u32));
            }
        }
        let Some((_, s)) = best else { return };
        let ag = self.teams[k as usize].agent;
        let water = sp.truck_water_l.min(self.depot_water);
        let food = sp.truck_food.min(self.depot_food);
        self.brief(ag);
        let dest = self.sites[s as usize].pos;
        if !self.plan_move(ag, dest, self.sites[s as usize].node, Arrive::Site(s)) {
            // No route known: mark as tried so it is not chosen again right away.
            self.sites[s as usize].last_delivery = t;
            return;
        }
        self.depot_water -= water;
        self.depot_food -= food;
        self.teams[k as usize].water = water;
        self.teams[k as usize].food = food;
        self.teams[k as usize].job = TeamJob::Deliver(s);
        self.sites[s as usize].last_delivery = t;
        self.sites[s as usize].ops_need = false;
    }

    fn deliver(&mut self, k: u32, s: u32) {
        let t = self.t;
        let tm = &mut self.teams[k as usize];
        let (w, f) = (tm.water, tm.food);
        tm.water = 0.0;
        tm.food = 0.0;
        let site = &mut self.sites[s as usize];
        site.water += w;
        site.food += f;
        site.delivered_water += w;
        site.deliveries += 1;
        site.last_delivery = t;
        self.counters.deliveries += 1;
        // The driver hears the shelter's news and brings it back.
        let ag = self.teams[k as usize].agent;
        self.deposit_at_shelter(ag, s);
        let known: Vec<u32> = self.sites[s as usize].board.iter().collect();
        for m in known {
            self.ops_learn(m);
        }
    }

    fn assign_visit(&mut self, k: u32) {
        let t = self.t;
        let ag = self.teams[k as usize].agent;
        let hq = self.teams[k as usize].base;
        let zone = self.sites[hq as usize].zone;
        if self.agents[ag as usize].site == hq {
            self.deposit_at_hq(ag, hq);
        }
        let mut best: Option<(f32, u32)> = None;
        for s in 0..self.sites.len() {
            let site = &self.sites[s];
            if site.kind != SiteKind::Shelter || site.zone != zone || t < site.open_at {
                continue;
            }
            if !(site.known_initially || site.hq_known) {
                continue;
            }
            if self.teams.iter().any(|tm| tm.job == TeamJob::Visit(s as u32)) {
                continue;
            }
            let last = self.teams.iter().map(|tm| tm.last_visit[s]).fold(-INF, f32::max);
            if t - last < 12.0 * 60.0 {
                continue;
            }
            if best.map(|b| last < b.0).unwrap_or(true) {
                best = Some((last, s as u32));
            }
        }
        let Some((_, s)) = best else { return };
        let dest = self.sites[s as usize].pos;
        if self.plan_move(ag, dest, self.sites[s as usize].node, Arrive::Site(s)) {
            self.teams[k as usize].job = TeamJob::Visit(s);
        } else {
            self.teams[k as usize].last_visit[s as usize] = t;
        }
    }
}
