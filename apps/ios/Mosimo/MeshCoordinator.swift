import CoreBluetooth
import CryptoKit
import Foundation
import MosimoCore
import Network
import os
import Security

/// SDK entry point, the single serialized owner of mesh, relay and disk state (main actor; Android uses one actor coroutine).
@MainActor
final class MeshCoordinator: ObservableObject, LinkListener {
    static let shared = MeshCoordinator()
    @Published private(set) var state = MeshState()

    /// User switch 「オフライン中継を有効にする」; persisted, resumes on launch.
    var relayEnabled: Bool {
        get { defaults.object(forKey: "meshEnabled") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "meshEnabled") }
    }

    // TODO(undecided): no production PDS/PLC policy is selected; same values as the Android manifest metadata.
    private let pds = "https://pds.oriom.dev"
    private let plcDirectory = URL(string: "https://plc.directory")!
    private let allowRegistration = true
    private static let rejectDeferMillis: Int64 = 10 * 60_000

    private let log = Logger(subsystem: "dev.jisaku.mosimo", category: "Mesh")
    private let defaults = UserDefaults.standard
    private let store = EncryptedStore(file: FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("mosimo-state.aesgcm"), key: StateKey.load)
    private var disk: DiskState?
    private var dirty = false
    private var fatal = false
    private var running = false
    private var foreground = false
    private let engine = MeshEngine()
    private var author: Author?
    private let node = Secp.randomBytes(8).map { String(format: "%02x", $0) }.joined()
    private let gatt = GattLink()
    private let lan = LanLink()
    private var links: [Link] { [gatt, lan] }
    private lazy var exchange = Exchange(engine: engine, node: node, now: { Self.now() }, elapsed: { Self.elapsed() },
        note: { [weak self] in self?.note($0) }, log: { [weak self] text in
            self?.log.info("\(text, privacy: .public)")
            #if DEBUG
            print("[exchange] \(text)")
            #endif
        },
        blocked: { [weak self] in self?.blocked.contains($0) ?? false },
        accepted: { [weak self] _ in try self?.persist(); self?.drainRelay(); self?.received(online: false) })
    private var lastSync: Int64 = 0
    private var blocked: [String] { disk?.blockedDids ?? [] }
    private let bootTime = MeshCoordinator.kernelBootTime()
    private var timer: Timer?
    private let pathMonitor = NWPathMonitor()
    private var network = false

    // Public information (0020) download and ingress.
    private var publicConfig = PublicIngress.Config()
    private var publicGeneration: Int64 = 0
    private var publicBusy = false
    private var publicNext: Int64 = 0
    private var publicTask: Task<Void, Never>?
    private var publicEvents = [NostrEvent]()

    // Relay export and display subscription.
    private var socket: RelaySocket?
    private var socketGeneration: Int64 = 0
    private var socketOpen = false
    private var activeRelay = ""
    private var relayIndex = 0
    private let relaySubscription = RelaySubscription()
    private var relayReadDeadline: Int64 = 0
    private var relayPendingId: String?
    private var relayDeadline: Int64 = 0
    private var relayRetryAt = [String: Int64]()
    private var relayFailures = [String: Int]()
    private var relayDeferred = [String: [String: Int64]]()
    private var relayLastAttempt = [String: String]()
    private var didCache = [String: String?]()

    // PLC registration (0001 §1.1.1) and proxy registration of genesis events carried over the mesh.
    private var registrationBusy = false
    private var registrationRetryAt: Int64 = 0
    private var registrationFailures = 0
    private var plcObservation = PlcObservation()
    private var proxyNext = [String: (Int, Int64)]()
    private var proxyBusy = false

    private init() {
        engine.clockConfidence = defaults.object(forKey: "clockTrusted") as? Bool ?? true ? .trusted : .unknown
        engine.relayed = { [unowned self] id in disk?.relayAccepted.values.contains { $0.contains(id) } == true }
        // Author-DID block: such events never enter the store, so they are never advertised or relayed either.
        engine.admit = { [unowned self] e in !blocked.contains(AtRepository.did(e) ?? "") }
        // PDS OK confirms event storage, not atproto publication; keep our base chain until then.
        engine.canEvict = { [unowned self] entry in
            entry.event.pubkey != author?.nostrPubkey || disk?.relayAccepted[DiskState.pdsRelay]?.contains(entry.event.id) == true
        }
        gatt.onState = { [weak self] on in self?.bluetooth(on) }
        state.bluetoothAllowed = !GattLink.denied
        load()
        pathMonitor.pathUpdateHandler = { [weak self] path in
            let up = path.status == .satisfied
            Task { @MainActor in self?.networkChanged(up) }
        }
        pathMonitor.start(queue: DispatchQueue(label: "mosimo.path"))
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in Task { @MainActor in self?.tick() } }
        // Background relaunch (Core Bluetooth restoration) never shows UI: resume relaying here when already authorized.
        if relayEnabled && GattLink.authorized { start() }
        #if DEBUG
        // Lab-only: `-mosimo.test.post "text"` publishes one located post at launch (device mesh test loop).
        if let text = defaults.string(forKey: "mosimo.test.post") {
            publish("［安全］" + text, extensions: [Extensions.origin(Origin(latitudeMicrodegrees: 35_681_236, longitudeMicrodegrees: 139_767_125))],
                    deliveryExpiresAtSeconds: Self.now() / 1000 + 3600)
        }
        #endif
    }

    private func load() {
        do {
            let saved: DiskState
            if let loaded = try store.load() { saved = loaded } else {
                var fresh = DiskState(keys: SecretKeys.generate(), pdsEndpoint: pds)
                #if DEBUG
                // Lab-only: `-mosimo.test.relays wss://a,wss://b` seeds the first state so test posts reach only those relays.
                if let relays = defaults.string(forKey: "mosimo.test.relays") { fresh.relays = relays.split(separator: ",").map(String.init) }
                #endif
                try store.save(fresh, [], bootTime: bootTime)
                saved = fresh
            }
            disk = saved
            author = try Author(keys: saved.keys, pdsEndpoint: saved.pdsEndpoint)
            let wall = Self.now(), monotonic = Self.elapsed()
            // One unreadable bundle (e.g. after a policy change) must not take the rest of the store down.
            var lost = 0
            for b in saved.bundles {
                do { try engine.restore(b.bytes, receivedAtMillis: b.receivedAtMillis, nowMillis: wall, elapsedMillis: monotonic,
                                        downtimeMillis: b.downtime(currentBoot: bootTime, elapsed: monotonic)) }
                catch is BlockedAuthor {} catch { lost += 1 }
            }
            if lost > 0 { note("保存済み Bundle \(lost) 件を読めず破棄") }
            try persist(saved)
            note("インストール固有の3鍵・DIDを準備済み")
            reloadPublicConfig()
        } catch {
            fatal = true
            issue("暗号化状態を開けません。鍵は再生成せず停止しました")
        }
        refresh()
    }

    // MARK: clocks

    nonisolated static func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
    /// Monotonic, includes sleep (Android elapsedRealtime).
    nonisolated static func elapsed() -> Int64 { Int64(clock_gettime_nsec_np(CLOCK_MONOTONIC) / 1_000_000) }
    /// Kernel boot time identifies one boot, like Android's BOOT_COUNT for saved monotonic stamps.
    nonisolated static func kernelBootTime() -> Int64? {
        var tv = timeval(); var size = MemoryLayout<timeval>.size
        var mib: [Int32] = [CTL_KERN, KERN_BOOTTIME]
        return sysctl(&mib, 2, &tv, &size, nil, 0) == 0 ? Int64(tv.tv_sec) : nil
    }

    // MARK: diagnostics

    private func received(online: Bool) { state.lastReceivedAtMillis = Self.now(); state.lastReceivedOnline = online }
    private func note(_ text: String) {
        log.info("\(text, privacy: .public)")
        #if DEBUG
        print("[mosimo] \(text)") // `devicectl … process launch --console` streams this for the device test loop
        #endif
        state.diagnostics = Array((state.diagnostics + [text]).suffix(16))
    }
    private func issue(_ text: String) { state.error = text; note(text) }
    func clearError() { state.error = nil }
    func reportError(_ text: String) { issue(String(text.prefix(160))) }

    // MARK: LinkListener (links hop to the main queue before calling these)

    nonisolated func onConnected(_ link: Link, _ peer: String) {
        MainActor.assumeIsolated { if running { exchange.connected(link, peer) } else { link.disconnect(peer) }; refresh() }
    }
    nonisolated func onFrame(_ link: Link, _ peer: String, _ frame: [UInt8]) {
        MainActor.assumeIsolated {
            guard running else { return }
            exchange.frame(link, peer, frame)
            if fatal { stopInternal() }
            refresh()
        }
    }
    nonisolated func onDisconnected(_ link: Link, _ peer: String) {
        MainActor.assumeIsolated { exchange.disconnected(link, peer); refresh() }
    }

    private func bluetooth(_ on: Bool) {
        state.bluetoothAllowed = on && !GattLink.denied
        if !on && running { note("Bluetooth が利用できません（オフ・権限なし）") }
        refresh()
    }

    // MARK: user actions

    /// Local operator policy, not clock synchronization or network consensus. Trusted from install until the user says otherwise.
    func setClockConfidence(_ value: ClockConfidence) {
        defaults.set(value == .trusted, forKey: "clockTrusted")
        // A display-only download under the old clock must not throttle the authorized one.
        if engine.clockConfidence != value { cancelPublicDownload(); publicNext = 0 }
        engine.clockConfidence = value
        exchange.broadcastInventory()
        if value == .trusted { requestPublic(manual: false) }
        refresh()
    }

    /// [extensions]: protocol/0011 maps (see [Extensions]); [deliveryExpiresAtSeconds] defaults to 24 h.
    func publish(_ text: String, extensions: [[String: Cbor]] = [], deliveryExpiresAtSeconds: Int64? = nil) {
        defer { state.publishAttempt += 1; refresh() }
        do {
            guard !fatal, let author, var current = disk else { throw CoreError("not ready") }
            try requireText(text)
            let post = try author.createPost(text, nowMillis: Self.now(), previous: current.repository,
                                             deliveryExpiresAtSeconds: deliveryExpiresAtSeconds ?? Self.now() / 1000 + 86400, extensions: extensions)
            do { try engine.originate(post.event, nowMillis: Self.now(), elapsedMillis: Self.elapsed()) } catch {
                issue("投稿の保存容量・配送条件・サイズ・発信頻度の制限に達しました。保存・発信はしていません")
                return
            }
            // The repository head is saved before anything leaves the device, so a crash cannot reuse a TID/commit.
            current.repository = post.repository
            try persist(current)
            state.lastPublishedId = post.event.id
            state.error = nil
            note("投稿を署名・暗号化保存済み \(post.event.id.prefix(8))")
            exchange.broadcastInventory()
            drainRelay()
        } catch { if !fatal { issue("処理に失敗しました（設定・権限・サイズを確認）") } }
    }

    private func requireText(_ text: String) throws {
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || text.utf8.count > 2000 { throw CoreError("Invalid text") }
    }

    func addRelay(_ endpoint: String) {
        defer { refresh() }
        guard !fatal, var current = disk else { return }
        let value = endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        let parsed = URLComponents(string: value)
        guard value.hasPrefix("wss://") || value.hasPrefix("ws://"), value.utf8.count <= 2048, !(parsed?.host ?? "").isEmpty,
              parsed?.user == nil, parsed?.password == nil, parsed?.fragment == nil else { issue("リレーの URL が不正です"); return }
        if current.relays.contains(value) { return }
        guard current.relays.count < DiskState.maxRelays else { issue("リレーは \(DiskState.maxRelays) 件までです"); return }
        current.relays.append(value)
        try? persist(current)
        note("リレー追加 \(value)")
        drainRelay()
    }

    func removeRelay(_ endpoint: String) {
        defer { refresh() }
        guard !fatal, var current = disk else { return }
        current.relays.removeAll { $0 == endpoint }
        try? persist(current)
        if endpoint == activeRelay { closeRelay(); relaySubscription.clear() }
        note("リレー削除 \(endpoint)")
    }

    /// Hides one author's posts everywhere; their device still relays other authors' posts to us.
    func block(_ did: String) {
        defer { refresh() }
        guard !fatal, var current = disk, did != author?.did, did.hasPrefix("did:plc:"), !current.blockedDids.contains(did),
              current.blockedDids.count < DiskState.maxBlocked else { return }
        engine.remove { $0.did == did }
        current.blockedDids.append(did)
        try? persist(current)
        note("ブロック \(did)")
        exchange.broadcastInventory()
    }

    func unblock(_ did: String) {
        defer { refresh() }
        guard !fatal, var current = disk else { return }
        current.blockedDids.removeAll { $0 == did }
        try? persist(current)
        exchange.forgetRefused()
        note("ブロック解除 \(did)")
    }

    func setOnlineEnabled(_ enabled: Bool) {
        defer { refresh() }
        guard !fatal, var current = disk else { return }
        current.onlineEnabled = enabled
        try? persist(current)
        if !onlineAllowed() { goOffline() } else { drainRelay() }
        note(enabled ? "オンライン搬出を有効化" : "オンライン搬出を停止。搬出待ちは保持")
    }

    /// Scene visibility permits online IO even while the mesh is stopped.
    func setOnlineForeground(_ visible: Bool) {
        foreground = visible
        if !onlineAllowed() { goOffline() } else { drainRelay(); requestPublic(manual: false) }
        refresh()
    }

    func start() {
        defer { refresh() }
        guard !fatal, author != nil else {
            issue("発信者の初期化が完了していないため開始できません")
            return
        }
        if running { return }
        running = true
        for link in links { link.start(node: node, listener: self) }
        note("BLE・LAN の探索と中継を開始（node \(node)）")
        drainRelay()
    }

    func stop() { stopInternal(); refresh() }

    private func stopInternal() {
        if dirty && !fatal { try? persist() }
        running = false
        for link in links { link.stop() }
        exchange.clear()
        if !onlineAllowed() { goOffline() }
        note("中継を停止。保存済み投稿とリレー設定は保持")
    }

    func trustSources() -> [TrustSource] {
        guard let json = Self.trustAsset(), let n = try? Json.parse(json) else { return [] }
        var selected = [String: [String]]()
        for s in n["selected"]?.array ?? [] { if let d = s["botDid"]?.string, let t = s["type"]?.string { selected[d, default: []].append(t) } }
        return (n["roots"]?.array ?? []).filter { $0["enabled"]?.bool == true }.flatMap { root -> [TrustSource] in
            let org = (root["scope"]?["orgIds"]?.array ?? []).compactMap(\.string).joined(separator: " / ")
            return (root["scope"]?["bsaf"]?.array ?? []).compactMap { bot in
                guard let did = bot["botDid"]?.string else { return nil }
                return TrustSource(org: org, botDid: did, types: (bot["types"]?.array ?? []).compactMap(\.string).filter { selected[did]?.contains($0) == true })
            }
        }
    }

    private static func trustAsset() -> String? {
        Bundle.main.url(forResource: "mosimo-public-trust", withExtension: "json").flatMap { try? String(contentsOf: $0, encoding: .utf8) }
    }

    // MARK: state

    private func persist(_ next: DiskState? = nil) throws {
        guard !fatal, var clean = next ?? disk else { throw CoreError("Persistence is disabled after a fatal error") }
        let entries = engine.entries()
        let ids = Set(entries.map(\.event.id))
        clean.publicAudit = clean.publicAudit.filter { ids.contains($0.key) }
        clean.relayAccepted = clean.relayAccepted.filter { clean.relays.contains($0.key) }.mapValues { $0.intersection(ids) }
        let wall = Self.now(), monotonic = Self.elapsed()
        do {
            try store.save(clean, try entries.map { try engine.checkpoint($0, nowMillis: wall, elapsedMillis: monotonic) }, bootTime: bootTime)
            disk = clean; dirty = false
        } catch {
            fatal = true
            stopInternal()
            issue("永続化に失敗。未保存データを送信せず停止しました（再起動が必要）")
            throw error
        }
    }

    private func didOf(_ event: NostrEvent) -> String? {
        if let hit = didCache[event.id] { return hit }
        if didCache.count > 1024 { didCache.removeAll() }
        let d = AtRepository.did(event)
        didCache[event.id] = d
        return d
    }

    private func acceptedAnywhere(_ id: String) -> Bool { disk?.relayAccepted.values.contains { $0.contains(id) } == true }

    private func refresh() {
        var s = state
        s.ready = author != nil && !fatal
        s.running = running
        s.clockConfidence = engine.clockConfidence
        s.onlineEnabled = disk?.onlineEnabled == true
        s.onlineActive = onlineAllowed()
        s.network = network
        s.sendingRelay = socketOpen ? activeRelay : nil
        let relays = disk?.relays ?? []
        s.queued = Dictionary(uniqueKeysWithValues: relays.map { ($0, pendingFor($0).count) })
        let retry = relays.filter { !pendingFor($0).isEmpty }.map { relayRetryAt[$0] ?? 0 }.min() ?? 0
        s.retryInSeconds = max(0, (retry - Self.elapsed()) / 1000)
        s.pdsConfigured = disk.map { configuredHttps($0.pdsEndpoint) != nil } ?? true
        s.did = author?.did ?? ""
        s.publicKey = author?.nostrPubkey ?? ""
        s.relays = relays
        s.registration = ((disk?.plcRegistered ?? false) ? "PLC 初期操作登録確認済み" : "PLC 初期操作登録未確認") + " / " + {
            let at = plcObservation.checkedAtMillis.map(String.init) ?? "-"
            switch plcObservation.current {
            case .checked: return "Directory 現在鍵・PDS設定一致（確認時刻 \(at)）"
            case .changed: return "Directory 鍵・PDS設定変更あり（確認時刻 \(at)）"
            case .tombstoned: return "Directory 無効化操作あり（確認時刻 \(at)）"
            case .unknown: return registrationBusy ? "現在状態確認中" : "現在状態不明（オフライン・未設定・未確認）"
            }
        }() + " / PDS 受入・公開未確認"
        // One entry per node: the same phone may be reachable over BLE and LAN (or two BLE links) at once.
        var groups = [String: [Session]](), keys = [String]()
        for session in exchange.sessions {
            let key = session.peerNode ?? "\(session.link.name):\(session.handle)"
            if groups[key] == nil { keys.append(key) }
            groups[key, default: []].append(session)
        }
        s.neighbors = keys.map { key in
            let list = groups[key]!
            let named = list.first { $0.peerNode != nil }
            return Neighbor(endpoint: key, name: named?.peerNode ?? "?", status: named == nil ? "HELLO 待ち"
                : "同期中（\(list.filter { $0.peerNode != nil }.map(\.link.name).joined(separator: "・"))）")
        }
        if fatal { s.posts = [] } else {
            let now = Self.now()
            let accepted = disk?.relayAccepted ?? [:]
            var posts = engine.entries().reversed().filter { $0.event.kind != AtRepository.plcGenesisKind }.map { e in
                PostView(id: e.event.id, pubkey: e.event.pubkey, content: e.event.content, hop: e.hop, relayAccepted: acceptedAnywhere(e.event.id),
                         deliveryDeadline: engine.deadline(e.event, nowMillis: now), acceptedBy: accepted.filter { $0.value.contains(e.event.id) }.map(\.key),
                         publicAuthorization: disk?.publicAudit[e.event.id], did: e.did, createdAt: e.event.createdAt, ext: Extensions.of(e.event))
            }
            let meshIds = Set(posts.map(\.id))
            var seen = Set<String>()
            let online = (relaySubscription.events() + publicEvents).filter { seen.insert($0.id).inserted }
            let publicIds = Set(publicEvents.map(\.id))
            posts += online.reversed().filter { !meshIds.contains($0.id) }.map {
                PostView(id: $0.id, pubkey: $0.pubkey, content: $0.content, hop: nil, relayAccepted: false,
                         sourceRelay: publicIds.contains($0.id) ? DiskState.pdsRelay : activeRelay, volatile: true,
                         did: didOf($0), createdAt: $0.createdAt, ext: Extensions.of($0))
            }
            s.posts = posts.filter { !blocked.contains($0.did ?? "") }
        }
        s.publicBusy = publicBusy
        s.blockedDids = blocked
        if s != state { state = s }
    }

    // MARK: online

    private func onlineAllowed() -> Bool {
        guard let d = disk else { return false }
        return d.onlineEnabled && (foreground || running) && !fatal && !d.relays.isEmpty
    }

    private func goOffline() { cancelPublicDownload(); closeRelay(); relaySubscription.clear() }

    /// [up]: a usable default network. A new network drops the socket bound to the old one without counting a failure.
    private func networkChanged(_ up: Bool) {
        let fresh = up && !network
        network = up
        defer { refresh() }
        guard up, !fatal, disk != nil else { return }
        if fresh, socket != nil { closeRelay(graceful: false) }
        relayFailures.removeAll(); relayRetryAt.removeAll(); relayDeferred.removeAll(); relayIndex = 0; registrationRetryAt = 0
        drainRelay()
        requestPublic(manual: false)
    }

    private func configuredHttps(_ value: String) -> URL? {
        guard let c = URLComponents(string: value), c.scheme == "https", let host = c.host, !host.isEmpty, !host.hasSuffix(".invalid"),
              c.user == nil, c.password == nil, c.query == nil, c.fragment == nil else { return nil }
        return c.url
    }

    private func reloadPublicConfig() {
        cancelPublicDownload()
        publicGeneration += 1
        publicConfig = PublicIngress.Config()
        do {
            guard let json = Self.trustAsset() else { throw CoreError("missing asset") }
            publicConfig = try PublicIngress.config(json)
            state.publicStatus = "公的情報: 有効 root \(publicConfig.roots.filter(\.enabled).count) / 選択 Bot/type \(publicConfig.selected.count)（未設定は投入不可）"
        } catch {
            state.publicStatus = "公的情報: config 読込失敗 / fail closed（既存鍵・失効履歴は保持）"
        }
    }

    private func cancelPublicDownload() {
        publicGeneration += 1
        publicTask?.cancel(); publicTask = nil; publicBusy = false
    }

    /// Pull-to-refresh; also auto-run on foreground, network change and clock trust.
    func requestPublicInformation() { requestPublic(manual: true); refresh() }

    /// Auto triggers skip guard failures silently; only a manual request explains them. An untrusted clock only blocks ingress.
    private func requestPublic(manual: Bool) {
        func skip(_ status: String) { if manual { state.publicStatus = status } }
        if fatal || disk == nil || publicBusy { return }
        if !onlineAllowed() || !network { return skip("公的情報: オンライン接続が必要 / 保留") }
        if Self.elapsed() < publicNext { return skip("公的情報: 取得頻度制限（30秒）") }
        publicNext = Self.elapsed() + 30_000
        publicBusy = true
        publicGeneration += 1
        let generation = publicGeneration
        let config = publicConfig
        let startingMemory = disk!.publicTrust
        let confidence = engine.clockConfidence
        state.publicStatus = "PDS relay から priority-v1 取得中…"
        state.publicResults = []
        publicTask = Task.detached(priority: .utility) { [weak self] in
            // Network IO and crypto run off the main actor; results are applied back on it under the generation fence.
            var snapshots = [String: [UInt8]]()
            for root in config.roots where root.enabled {
                if let bytes = try? await PublicDownload.snapshot(root) { snapshots[root.rootId] = bytes }
            }
            let batch: Result<PublicDownload.Batch, Error>
            do { batch = .success(try await PublicDownload.fetch(token: generation)) } catch { batch = .failure(error) }
            if Task.isCancelled { return }
            let clock = PublicDownload.ingressClock(confidence, MeshCoordinator.now())
            var memory = startingMemory
            var failures = [String]()
            for root in config.roots where root.enabled {
                if let bytes = snapshots[root.rootId] {
                    do { memory = try PublicIngress.acceptSnapshot(root, bytes, memory, clock) }
                    catch { failures.append("snapshot \(root.rootId.prefix(8)): \(error)") }
                } else { failures.append("snapshot \(root.rootId.prefix(8)): 取得失敗（有効な保存 snapshot のみ利用可）") }
            }
            var decisions = [(NostrEvent, PublicIngress.Decision)]()
            for event in (try? batch.get())?.events ?? [] {
                let d = PublicIngress.verify(event, config, memory, clock)
                memory = d.memory
                decisions.append((event, d))
            }
            decisions.sort { ($0.1.receipt?.priority ?? min(PublicIngress.priority($0.0), 1)) > ($1.1.receipt?.priority ?? min(PublicIngress.priority($1.0), 1)) }
            let finalMemory = memory, finalFailures = failures, finalDecisions = decisions
            await self?.completePublic(generation, batch, finalMemory, finalFailures, finalDecisions, clock == nil)
            guard case .success(let first) = batch else { return }
            let pages = await PublicDownload.more(token: generation, first: first)
            await self?.completePages(generation, first, pages)
        }
        refresh()
    }

    private func publicSummary(_ fallback: Bool, _ imported: Int, _ clockUnknown: Bool) -> String {
        "PDS \(fallback ? "通常REQ fallback" : "priority-v1"): \(publicEvents.count) 件取得 / \(imported) 件投入（原Bot署名・現在PLC認証ではない）" +
            (clockUnknown ? " / 時計 unknown・認可保留" : "")
    }

    private func completePublic(_ generation: Int64, _ batch: Result<PublicDownload.Batch, Error>, _ memory: PublicIngress.Memory,
                                _ failures: [String], _ decisions: [(NostrEvent, PublicIngress.Decision)], _ clockUnknown: Bool) {
        defer { refresh() }
        guard generation == publicGeneration, !fatal, onlineAllowed(), var current = disk else { return }
        // A successful first batch stays busy while display-only older pages load.
        if case .failure = batch { publicBusy = false; publicTask = nil }
        // Atomically write high-water, sticky CRL and serial history BEFORE considering any ingress.
        current.publicTrust = memory
        guard (try? persist(current)) != nil else { return }
        switch batch {
        case .failure(let e):
            state.publicStatus = "公的情報取得失敗: \(e)"
            state.publicResults = failures
        case .success(let result):
            let known = Set(publicEvents.map(\.id) + engine.entries().map(\.event.id))
            if result.events.contains(where: { !known.contains($0.id) }) { received(online: true) }
            publicEvents = result.events
            var reports = [String](), imported = 0
            for (event, decision) in decisions {
                if fatal { break }
                let insertion = engine.clockConfidence == .trusted ? Instant(epochMillis: Self.now()) : nil
                let outcome: String
                if let receipt = decision.receipt {
                    if !PublicIngress.readyToInsert(receipt, event, disk!.publicTrust, insertion) { outcome = "投入保留: 待機中に証明・snapshot・期限・時計が無効化" }
                    else if engine.entries().contains(where: { $0.event.id == event.id }) { outcome = "既存 event ID / 重複投入なし" }
                    else {
                        do {
                            try engine.originate(event, nowMillis: Self.now(), elapsedMillis: Self.elapsed())
                            var d = disk!
                            d.publicAudit[event.id] = "取込時の転載者認可（現在の公式認証ではない）: \(receipt)"
                            try persist(d)
                            imported += 1
                            outcome = "認可・暗号化保存済み / priority \(receipt.priority)"
                        } catch { outcome = "通常 mesh 資源制限: \(error)" }
                    }
                } else { outcome = "投入拒否/保留: \(decision.reason)" }
                reports.append("\(event.id.prefix(8)) \(outcome)\n\(decision.status)")
            }
            state.publicStatus = publicSummary(result.fallback, imported, clockUnknown) + (result.events.count >= PublicDownload.limit ? " / 続きを取得中…" : "")
            state.publicResults = Array((failures + reports).prefix(40))
            if imported > 0 { exchange.broadcastInventory(); drainRelay() }
        }
    }

    private func completePages(_ generation: Int64, _ first: PublicDownload.Batch, _ pages: PublicDownload.Pages) {
        defer { refresh() }
        guard generation == publicGeneration, !fatal, onlineAllowed() else { return }
        publicBusy = false; publicTask = nil
        let known = Set(publicEvents.map(\.id) + engine.entries().map(\.event.id))
        if pages.events.contains(where: { !known.contains($0.id) }) { received(online: true) }
        var seen = Set<String>()
        publicEvents = Array((first.events + pages.events).filter { seen.insert($0.id).inserted }.prefix(PublicDownload.maxEvents))
        state.publicStatus = publicSummary(first.fallback, 0, engine.clockConfidence != .trusted) + (pages.failure.map { " / 続き取得中断: \($0)" } ?? "")
    }

    // MARK: relay export (mndt §6)

    private func backoff(_ attempts: Int) -> Int64 { min(1000 << Int64(min(max(attempts, 0), 6)), 60_000) + Int64.random(in: 0..<1000) }

    // Genesis control events stay in the mesh: relays accept only kinds 1 and 5, and online nodes submit them to PLC.
    private func pendingFor(_ relay: String) -> [StoredBundle] {
        guard let d = disk else { return [] }
        let done = d.relayAccepted[relay] ?? []
        return engine.entries().filter { $0.event.kind != AtRepository.plcGenesisKind && !done.contains($0.event.id) }
    }

    /// Rotate actual attempts so a disconnecting or rejecting first event cannot starve the queue.
    private func nextRelayId(_ ids: [String], _ lastAttempt: String?, _ deferred: [String: Int64], _ now: Int64) -> String? {
        let start = (lastAttempt.flatMap { ids.firstIndex(of: $0) } ?? -1) + 1
        return (Array(ids.dropFirst(start)) + ids.prefix(start)).first { now >= (deferred[$0] ?? 0) }
    }

    /// Select from the current round-robin position without changing the saved relay order.
    private func nextRelayIndex(_ relays: [String], _ start: Int, _ now: Int64) -> Int? {
        relays.indices.map { (start + $0) % relays.count }.first { i in
            let relay = relays[i]
            return now >= (relayRetryAt[relay] ?? 0) && nextRelayId(pendingFor(relay).map(\.event.id), nil, relayDeferred[relay] ?? [:], now) != nil
        }
    }

    private func closeRelayRead() {
        if let close = relaySubscription.close(), socketOpen, socket?.send(close) != true { relayFailure() }
        relayReadDeadline = 0
    }

    private func closeRelay(graceful: Bool = true) {
        if graceful { closeRelayRead() }
        relaySubscription.disconnect()
        relayReadDeadline = 0
        socketGeneration += 1
        socket?.close()
        socket = nil; socketOpen = false; relayPendingId = nil; relayDeadline = 0
    }

    private func relayFailure() {
        let failures = (relayFailures[activeRelay] ?? 0) + 1
        relayFailures[activeRelay] = failures
        let retry = Self.elapsed() + backoff(failures)
        if let id = relayPendingId { relayDeferred[activeRelay, default: [:]][id] = retry }
        relayRetryAt[activeRelay] = retry
        closeRelay(graceful: false)
        relayIndex += 1
        note("リレー未受理。キューを保持し、次のリレーで再試行")
    }

    /// Rotates through the configured relays so every one eventually receives every held event (mndt §6).
    private func drainRelay() {
        guard let current = disk, onlineAllowed(), !current.relays.isEmpty else { return }
        let now = Self.elapsed()
        if socket == nil {
            let relays = current.relays
            guard let index = nextRelayIndex(relays, relayIndex, now) ??
                    relays.indices.map({ (relayIndex + $0) % relays.count }).first(where: { now >= (relayRetryAt[relays[$0]] ?? 0) }) else { return }
            relayIndex = index
            activeRelay = relays[index]
            guard let url = URL(string: activeRelay) else { relayFailure(); return }
            // Rotation keeps the display cache; only relay removal or going offline clears it.
            relaySubscription.disconnect()
            socketGeneration += 1
            let gen = socketGeneration
            relayDeadline = now + 20_000
            socket = RelaySocket(url: url, onOpen: { [weak self] in
                guard let self, gen == self.socketGeneration else { return }
                guard self.onlineAllowed() else { self.closeRelay(); return }
                self.socketOpen = true; self.relayDeadline = 0; self.relayFailures[self.activeRelay] = nil
                if let req = self.relaySubscription.open(gen) {
                    self.relayReadDeadline = Self.elapsed() + 20_000
                    if self.socket?.send(req) != true { self.relayFailure(); return }
                }
                self.drainRelay()
                self.refresh()
            }, onMessage: { [weak self] text in
                guard let self, gen == self.socketGeneration else { return }
                self.relayMessage(text)
                self.refresh()
            }, onFailure: { [weak self] in
                guard let self, gen == self.socketGeneration else { return }
                self.relayFailure()
                self.refresh()
            })
            return
        }
        guard socketOpen, relayPendingId == nil else { return }
        let pending = pendingFor(activeRelay)
        guard let nextId = nextRelayId(pending.map(\.event.id), relayLastAttempt[activeRelay], relayDeferred[activeRelay] ?? [:], now) else {
            // Stay subscribed only while no other relay has a sendable event.
            if nextRelayIndex(current.relays, relayIndex + 1, now) != nil { closeRelay(); relayIndex += 1 }
            return
        }
        let next = pending.first { $0.event.id == nextId }!
        // Forward the signed original; never re-author or add mutable route tags.
        relayLastAttempt[activeRelay] = nextId
        guard let json = try? next.event.toJson(), ("[\"EVENT\"," + json + "]").utf8.count <= 262144 else {
            relayDeferred[activeRelay, default: [:]][nextId] = now + 60_000
            note("リレー形式のサイズ上限を超過。投稿を保持し、後続を優先")
            return
        }
        relayPendingId = nextId
        relayDeadline = now + 20_000
        if socket?.send("[\"EVENT\"," + json + "]") != true { relayFailure() }
    }

    private func relayMessage(_ text: String) {
        let reply: RelayReply?
        do { reply = try relaySubscription.receive(socketGeneration, text, Self.elapsed(), meshIds: Set(engine.entries().map(\.event.id))) }
        catch { relayFailure(); return }
        switch reply {
        // General subscription remains display-only; authorized ingress uses the explicit public request.
        case .event: received(online: true); if relaySubscription.atCapacity { closeRelayRead() }
        case .full: closeRelayRead()
        case .eose, .closed: relayReadDeadline = 0
        case .ok(let id, let accepted): relayOk(id, accepted)
        case nil: break
        }
    }

    private func relayOk(_ id: String, _ accepted: Bool) {
        guard let pending = relayPendingId, pending == id, var current = disk else { return }
        relayPendingId = nil; relayDeadline = 0
        if !accepted {
            // OK false is relay policy, not transport; deferring avoids a reconnect loop.
            relayDeferred[activeRelay, default: [:]][id] = Self.elapsed() + Self.rejectDeferMillis
            note("リレー拒否 \(id.prefix(8))（\(activeRelay)）。投稿は保持")
            closeRelay(); relayIndex += 1
            drainRelay()
            return
        }
        current.relayAccepted[activeRelay, default: []].insert(id)
        disk = current
        dirty = true
        relayFailures[activeRelay] = nil
        relayRetryAt[activeRelay] = nil
        relayDeferred[activeRelay]?[id] = nil
        note("リレー OK \(id.prefix(8))（\(activeRelay) / PLC・PDS・AppView 完了ではない）")
        closeRelay(); relayIndex += 1
        drainRelay()
    }

    // MARK: PLC

    private func registerIfConfigured() {
        guard let current = disk else { return }
        if fatal || configuredHttps(current.pdsEndpoint) == nil || !network {
            plcObservation = PlcObservation(genesisConfirmed: current.plcRegistered)
            return
        }
        guard !registrationBusy, Self.elapsed() >= registrationRetryAt, let identity = author else { return }
        registrationBusy = true
        plcObservation = PlcObservation(genesisConfirmed: current.plcRegistered)
        let directory = plcDirectory, did = identity.did, op = identity.initialOperationJson, previously = current.plcRegistered, allow = allowRegistration
        Task { [weak self] in
            let result = await PlcDirectory.check(directory: directory, did: did, genesisJson: op, previouslyConfirmed: previously, allowRegistration: allow)
            guard let self else { return }
            self.registrationBusy = false
            guard !self.fatal, var latest = self.disk else { return }
            if result.genesisConfirmed && !latest.plcRegistered { latest.plcRegistered = true; try? self.persist(latest) }
            self.plcObservation = self.network ? result : PlcObservation(genesisConfirmed: result.genesisConfirmed)
            if self.plcObservation.current == .unknown {
                self.registrationFailures += 1
                self.registrationRetryAt = Self.elapsed() + self.backoff(self.registrationFailures)
                self.note("PLC 現在状態不明。保存済み鍵・DID・署名済み初期操作を保持")
            } else {
                self.registrationFailures = 0
                // ponytail: local observation throttle, not a common protocol retry policy.
                self.registrationRetryAt = Self.elapsed() + 60_000
                self.note("PLC Directory 状態 \(self.plcObservation.current)。保存済み鍵・DIDは変更せず、PDS受入は未確認")
            }
            self.refresh()
        }
    }

    /// Unregistered with posts held: carry the signed genesis operation as its own control event (0001 §1.1.1).
    private func originateGenesisIfUnregistered() {
        guard let current = disk, let me = author, !current.plcRegistered, configuredHttps(current.pdsEndpoint) != nil else { return }
        let own = engine.entries().filter { $0.event.pubkey == me.nostrPubkey }
        if !own.contains(where: { $0.event.kind == 1 }) || own.contains(where: { $0.event.kind == AtRepository.plcGenesisKind }) { return }
        // A rate-limited or rejected attempt is retried on a later tick.
        guard let event = try? me.createPlcGenesisEvent(nowMillis: Self.now()),
              (try? engine.originate(event, nowMillis: Self.now(), elapsedMillis: Self.elapsed())) != nil else { return }
        try? persist()
        note("PLC 未登録のため署名済み初期操作をメッシュで配送（代理登録用）")
        exchange.broadcastInventory()
    }

    /// Online nodes submit received genesis operations unchanged; PlcDirectory.check never POSTs over an existing log.
    private func proxyRegister() {
        guard !fatal, allowRegistration, !proxyBusy, network else { return }
        let clock = Self.elapsed()
        let candidates = engine.entries().filter { $0.event.kind == AtRepository.plcGenesisKind && $0.event.pubkey != author?.nostrPubkey }
        guard let (did, operation) = candidates.lazy.compactMap({ e in (try? AtRepository.plcDid(e.event.content)).map { ($0, e.event.content) } })
                .first(where: { clock >= (self.proxyNext[$0.0]?.1 ?? 0) }) else { return }
        proxyBusy = true
        let directory = plcDirectory
        Task { [weak self] in
            let confirmed = await PlcDirectory.check(directory: directory, did: did, genesisJson: operation, previouslyConfirmed: false, allowRegistration: true).genesisConfirmed
            guard let self else { return }
            self.proxyBusy = false
            // ponytail: per-DID retry forever at most once a minute; add a give-up rule once 0003 defines load limits.
            let failures = confirmed ? 0 : (self.proxyNext[did]?.0 ?? 0) + 1
            self.proxyNext[did] = (failures, confirmed ? Int64.max : Self.elapsed() + self.backoff(failures))
            self.note(confirmed ? "PLC 代理登録: \(did) の初期操作をディレクトリで確認" : "PLC 代理登録: \(did) 未確認。再試行")
            self.refresh()
        }
    }

    private func tick() {
        defer { refresh() }
        guard !fatal, disk != nil else { return }
        if dirty { try? persist() }
        registerIfConfigured()
        originateGenesisIfUnregistered()
        proxyRegister()
        let clock = Self.elapsed()
        if relayDeadline > 0 && clock >= relayDeadline { relayFailure() }
        if relayReadDeadline > 0 && clock >= relayReadDeadline { closeRelayRead() }
        drainRelay()
        guard running else { return }
        exchange.dropStale()
        if clock - lastSync >= 10_000 { lastSync = clock; exchange.broadcastInventory() }
    }
}

/// AES-256 key for the state file in the Keychain (this device only, readable after first unlock for background relaying).
enum StateKey {
    private static let service = "dev.jisaku.mosimo.state", account = "mosimo-state-v1"
    nonisolated(unsafe) private static var cached: SymmetricKey?

    static func load() throws -> SymmetricKey {
        if let cached { return cached }
        let key = try read()
        cached = key
        return key
    }

    private static func read() throws -> SymmetricKey {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                    kSecAttrAccount as String: account, kSecReturnData as String: true]
        var out: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &out)
        if status == errSecSuccess, let data = out as? Data, data.count == 32 { return SymmetricKey(data: data) }
        guard status == errSecItemNotFound else { throw CoreError("Keychain unavailable (\(status))") }
        // A missing key with existing state is corruption, never a silent identity reset.
        let file = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("mosimo-state.aesgcm")
        guard !FileManager.default.fileExists(atPath: file.path) else { throw CoreError("Encryption key unavailable") }
        let key = SymmetricKey(size: .bits256)
        let data = key.withUnsafeBytes { Data($0) }
        let add: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account,
                                  kSecValueData as String: data, kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let added = SecItemAdd(add as CFDictionary, nil)
        guard added == errSecSuccess else { throw CoreError("Keychain write failed (\(added))") }
        return key
    }
}
