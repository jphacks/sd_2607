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
    @Published private(set) var state = MeshState() {
        didSet {
            #if DEBUG
            if state.publicStatus != oldValue.publicStatus { print("[public] \(state.publicStatus)") }
            if state.publicResults != oldValue.publicResults { for r in state.publicResults { print("[public] \(r.replacingOccurrences(of: "\n", with: " | "))") } }
            #endif
        }
    }

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
    /// Per-process random node id; DEBUG `-mosimo.test.node` pins it and `-mosimo.test.peer.node` isolates a lab pair (as Android's metadata).
    private let node = MeshCoordinator.testValue("mosimo.test.node") ?? Secp.randomBytes(8).map { String(format: "%02x", $0) }.joined()
    private let allowedPeerNode = MeshCoordinator.testValue("mosimo.test.peer.node")
    private lazy var gatt = GattLink(allowedPeerNode: allowedPeerNode)
    private let lan = LanLink()
    private var radios: [Link] {
        #if DEBUG
        // Lab-only: `-mosimo.test.links ble` or `lan` runs one transport, to test each in isolation.
        switch Self.lab("mosimo.test.links") { case "ble": return [gatt]; case "lan": return [lan]; default: break }
        #endif
        return [gatt, lan]
    }
    // BLE and LAN run only in the app that holds this device's LocalLink port; other Mosimo apps on it relay through it (mesh §4.6).
    private lazy var links: [Link] = [LocalLink(radios: radios)]
    private lazy var exchange = Exchange(engine: engine, now: { Self.now() }, elapsed: { Self.elapsed() },
        note: { [weak self] in self?.note($0) }, log: { [weak self] text in
            self?.log.info("\(text, privacy: .public)")
            #if DEBUG
            print("[exchange] \(text)")
            #endif
        },
        allowedPeerNode: allowedPeerNode,
        forwarded: { [weak self] id, peer in self?.forwarded[id, default: []].insert(peer) },
        accepted: { [weak self] entry in
            guard let self else { return }
            self.seenAt[entry.event.id] = self.seenAt[entry.event.id] ?? Instant(epochMillis: Self.now())
            self.absorbControl(entry.event)
            try self.persist(); self.drainRelay()
            if entry.event.kind == 1 { self.received(online: false) }
        },
        timeHint: { [weak self] in self?.timeHint() }, onTimeHint: { [weak self] peer, delta, confidence in self?.noteSkew(peer, delta, confidence) },
        skip: { [weak self] in self?.moderation.skip($0) ?? false })
    private var lastSync: Int64 = 0
    private var blocked: [String] { disk?.blockedPubkeys ?? [] }
    // ponytail: in-memory only; apps that need it across restarts persist their own delivery status.
    private var forwarded = [String: Set<String>]()
    /// Posts reported from this device in this run (the report events themselves are in the store).
    private var reported = Set<String>()
    private var revocations = PublicIngress.RevocationStore()
    /// Event ID → when this device first observed it; `seenAt` in the revocation rules.
    private var seenAt = [String: Instant]()
    private var chainCache = [String: PublicIngress.ChainView?]()
    /// Effective priority per event ID; dropped together with [chainCache].
    private var priorityCache = [String: Int]()
    private var lastChainFlush: Int64 = 0
    private var revocationBusy = false
    private var revocationNext: Int64 = 0
    private var clockSkews = [ClockSkew]()
    /// Deletion requests (delete §3) and reports (report §2) seen so far; rebuilt from the store at launch.
    private let moderation = Moderation()
    private static let revocationIntervalMs: Int64 = 15 * 60_000
    /// A node seen by a radio within this long counts as nearby.
    private static let nearbySeconds: Double = 60
    private var hideExpired: Bool { defaults.object(forKey: "hideExpired") as? Bool ?? true }
    private let filter = ContentFilter(base64: Bundle.main.url(forResource: "blocked-words", withExtension: "b64").flatMap { try? String(contentsOf: $0, encoding: .utf8) })
    private let bootTime = MeshCoordinator.kernelBootTime()
    private var timer: Timer?
    private let pathMonitor = NWPathMonitor()
    private var network = false
    #if DEBUG
    /// Lab-only `-mosimo.test.post` text, published on the first path update so its connectivity extension is known.
    private var labPost: String?
    #endif

    // Public information download and importer ingress (attest.importer §3).
    private var publicConfig = PublicIngress.Config()
    private var trustEntries = [PublicIngress.RootEntry]()
    private var publicGeneration: Int64 = 0
    private var publicBusy = false
    private var publicNext: Int64 = 0
    /// A relay-subscription event arrived since the last public request; the next allowed tick re-runs ingress.
    private var publicStale = false
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

    // PLC registration (PDS §6) and proxy registration of genesis events carried over the mesh.
    private var registrationBusy = false
    private var registrationRetryAt: Int64 = 0
    private var registrationFailures = 0
    private var plcObservation = PlcObservation()
    private var proxyNext = [String: (Int, Int64)]()
    private var proxyBusy = false

    private init() {
        #if DEBUG
        setvbuf(stdout, nil, _IOLBF, 0) // a piped console (devicectl --console) is otherwise block-buffered
        #endif
        engine.clockConfidence = defaults.object(forKey: "clockTrusted") as? Bool ?? true ? .trusted : .unknown
        engine.relayed = { [unowned self] id in disk?.relayAccepted.values.contains { $0.contains(id) } == true }
        // Author block (mesh §10, by Nostr key): such events never enter the store, so they are never advertised or relayed either.
        // Stopped targets (deleted by their author, or reported enough) are refused too (delete §3, report §2).
        engine.admit = { [unowned self] e in !blocked.contains(e.pubkey) && !moderation.stopped(e) }
        engine.suppressed = { [unowned self] e in moderation.stopped(e) || chainOf(e)?.revocation == .revoked }
        // Sorting asks for this often; chain checks are memoized per event until the chain cache is invalidated.
        engine.priority = { [unowned self] e in
            if let p = priorityCache[e.id] { return p }
            let p = PublicIngress.effectivePriority(e, chainOf(e))
            priorityCache[e.id] = p
            return p
        }
        // PDS OK confirms event storage, not atproto publication; keep our base chain until then.
        engine.canEvict = { [unowned self] entry in
            // Kinds the PDS never accepts would otherwise be pinned forever and fill the store.
            entry.event.pubkey != author?.nostrPubkey
                || !DiskState.relayWants(DiskState.pdsRelay, entry.event)
                || disk?.relayAccepted[DiskState.pdsRelay]?.contains(entry.event.id) == true
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
        // Lab-only: `-mosimo.test.post "text"` publishes one located post at launch (device mesh test loop), in networkChanged.
        labPost = defaults.string(forKey: "mosimo.test.post")
        // `-mosimo.test.report <id prefix>` / `-mosimo.test.delete <id prefix>` act on a held event.
        if let p = defaults.string(forKey: "mosimo.test.report"), let e = engine.entries().first(where: { $0.event.id.hasPrefix(p) }) {
            report(e.event.id, type: "spam", comment: "試験", block: false)
        }
        if let p = defaults.string(forKey: "mosimo.test.delete"), let e = engine.entries().first(where: { $0.event.id.hasPrefix(p) }) { delete(e.event.id) }
        #endif
    }

    private func load() {
        do {
            var saved: DiskState
            if let loaded = try store.load() { saved = loaded } else {
                saved = DiskState(keys: SecretKeys.generate(), pdsEndpoint: pds)
                try store.save(saved, [], bootTime: bootTime)
            }
            #if DEBUG
            // Lab-only: `-mosimo.test.relays wss://a,wss://b` replaces the relay list so test posts reach only those relays.
            if let relays = Self.lab("mosimo.test.relays") { saved.relays = relays.split(separator: ",").map(String.init) }
            #endif
            disk = saved
            author = try Author(keys: saved.keys, pdsEndpoint: saved.pdsEndpoint)
            revocations = PublicIngress.RevocationStore(saved.revocations)
            let wall = Self.now(), monotonic = Self.elapsed()
            // One unreadable bundle (e.g. after a policy change) must not take the rest of the store down.
            var lost = 0
            for b in saved.bundles {
                do { try engine.restore(b.bytes, receivedAtMillis: b.receivedAtMillis, nowMillis: wall, elapsedMillis: monotonic,
                                        downtimeMillis: b.downtime(currentBoot: bootTime, elapsed: monotonic)) }
                catch is BlockedAuthor {} catch { lost += 1 }
            }
            if lost > 0 { note("保存済み Bundle \(lost) 件を読めず破棄") }
            for e in engine.entries() {
                seenAt[e.event.id] = seenAt[e.event.id] ?? Instant(epochMillis: e.receivedAtMillis)
                absorbControl(e.event, quiet: true)
            }
            try persist(saved)
            note("インストール固有の3鍵・DIDを準備済み")
            reloadPublicConfig()
        } catch {
            fatal = true
            issue("暗号化状態を開けません。鍵は再生成せず停止しました")
        }
        refresh()
    }

    /// DEBUG lab settings: a launch argument wins and is remembered, so Core Bluetooth background relaunches
    /// (which get no arguments) keep the same isolation. `-mosimo.test.reset 1` forgets them.
    nonisolated static func lab(_ key: String) -> String? {
        #if DEBUG
        let d = UserDefaults.standard
        if d.string(forKey: "mosimo.test.reset") == "1" { for k in ["node", "peer.node", "links", "relays"] { d.removeObject(forKey: "lab.mosimo.test." + k) } }
        if let launch = d.volatileDomain(forName: UserDefaults.argumentDomain)[key] as? String {
            d.set(launch, forKey: "lab." + key)
            return launch
        }
        return d.string(forKey: "lab." + key)
        #else
        return nil
        #endif
    }

    private nonisolated static func testValue(_ key: String) -> String? {
        guard let v = lab(key), v.count == 16, v.allSatisfy({ $0.isHexDigit && !$0.isUppercase }) else { return nil }
        return v
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

    nonisolated func onConnected(_ link: Link, _ peer: String, node: String?) {
        MainActor.assumeIsolated { if running { exchange.connected(link, peer, node: node) } else { link.disconnect(peer) }; refresh() }
    }
    nonisolated func onMessage(_ link: Link, _ peer: String, _ message: [UInt8]) {
        MainActor.assumeIsolated {
            guard running else { return }
            exchange.message(link, peer, message)
            if fatal { stopInternal() }
            refresh()
        }
    }
    nonisolated func onDrained(_ link: Link, _ peer: String) {
        MainActor.assumeIsolated { if running { exchange.drained(link, peer) } }
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

    /// Hides expired posts from the UI only; they stay stored and exported. Persisted.
    func setHideExpired(_ value: Bool) {
        defaults.set(value, forKey: "hideExpired")
        refresh()
    }

    /// Local operator policy, not clock synchronization or network consensus. Trusted from install until the user says otherwise.
    func setClockConfidence(_ value: ClockConfidence) {
        defaults.set(value == .trusted, forKey: "clockTrusted")
        // A display-only download under the old clock must not throttle the authorized one.
        if engine.clockConfidence != value { cancelPublicDownload(); publicNext = 0; chainCache.removeAll(); priorityCache.removeAll() }
        engine.clockConfidence = value
        exchange.broadcastInventory()
        if value == .trusted { requestPublic(manual: false) }
        refresh()
    }

    /// mesh.time §1: our hint, with the confidence the operator's clock setting justifies. Never used as a clock.
    private func timeHint() -> [String: Cbor] {
        // iOS keeps network time by default; an operator-trusted clock is "recently synchronized" at best.
        ["$type": .text(Messages.timeHint), "now": .int(Self.now() / 1000), "confidence": .int(engine.clockConfidence == .trusted ? 2 : 0)]
    }
    private func noteSkew(_ peer: String, _ delta: Int64, _ confidence: Int) {
        clockSkews.removeAll { $0.peer == peer }
        clockSkews.insert(ClockSkew(peer: peer, deltaSeconds: delta, confidence: confidence), at: 0)
        if clockSkews.count > 8 { clockSkews.removeLast() }
        // mesh.time §2: only a warning, never a clock. Two or more confident peers far off hint at our own clock.
        let suspicious = clockSkews.filter { $0.confidence >= 2 && abs($0.deltaSeconds) > 300 }
        if suspicious.count >= 2 {
            note("近隣端末 \(suspicious.count) 台と時刻が\(suspicious.map { abs($0.deltaSeconds) }.max()! / 60)分以上ずれています（時計の確認を推奨）")
        }
    }

    /// attest.chain verification, memoized per event; the cache is dropped when roots, revocations or the clock change.
    private func chainOf(_ event: NostrEvent) -> PublicIngress.ChainView? {
        if let hit = chainCache[event.id] { return hit }
        if chainCache.count > 1024 { chainCache.removeAll(); priorityCache.removeAll() }
        let view = PublicIngress.chainView(event, roots: publicConfig.roots, revocations: revocations,
                                           now: PublicDownload.ingressClock(engine.clockConfidence, Self.now()),
                                           seenAt: seenAt[event.id] ?? Instant(epochMillis: Self.now()), selected: publicConfig.selected)
        chainCache[event.id] = .some(view)
        return view
    }
    /// Applies control data held in the store: deletion requests and reports (delete §3, report §2, recorded even
    /// before their target arrives) and revocation control events (attest.revocation §5, verified by their inner
    /// signatures only).
    private func absorbControl(_ event: NostrEvent, quiet: Bool = false) {
        if let target = moderation.absorb(event) {
            if pruneStopped() && !quiet { note("\(event.kind == 5 ? "削除要求" : "報告") により \(target.prefix(8)) の表示・中継を停止") }
            return
        }
        guard let revocation = (try? Attest.revocationOf(event)) ?? nil else { return }
        if (try? revocations.add(revocation)) == true {
            chainCache.removeAll(); priorityCache.removeAll()
            dirty = true
            if !quiet { note("失効通知を受信・永続保存（配送候補を再評価）") }
        }
    }

    /// Erases stopped posts (delete §3, report §2). Our own stays held, hidden and off the mesh, only until the PDS
    /// has it: the PDS needs it to verify the removal commit and every later post.
    @discardableResult
    private func pruneStopped() -> Bool {
        let me = author?.nostrPubkey
        let atPds = disk?.relayAccepted[DiskState.pdsRelay] ?? []
        let removed = engine.remove { moderation.stopped($0.event) && ($0.event.pubkey != me || atPds.contains($0.event.id)) }
        if removed { dirty = true }
        return removed
    }

    /// delete §1-2: asks for deletion of our own post with the commit of the repository without it. Other holders
    /// may keep copies; this is a request, not erasure.
    func delete(_ eventId: String) {
        defer { refresh() }
        guard !fatal, let me = author, var current = disk else { return }
        guard let target = engine.entries().first(where: { $0.event.id == eventId })?.event, target.pubkey == me.nostrPubkey else {
            issue("自分の投稿だけ削除を依頼できます"); return
        }
        do {
            let request = try me.createDeletion(target, nowMillis: Self.now(), previous: current.repository)
            try engine.originate(request.event, nowMillis: Self.now(), elapsedMillis: Self.elapsed())
            seenAt[request.event.id] = Instant(epochMillis: Self.now())
            // The removal commit is the next base: save it before anything leaves the device.
            current.repository = request.repository
            try persist(current)
            absorbControl(request.event)
        } catch { issue("削除要求を保存できませんでした（容量・発信頻度の制限）"); return }
        try? persist()
        note("削除要求を署名・配送 \(eventId.prefix(8))（受信済みの端末から消える保証はありません）")
        exchange.broadcastInventory()
        drainRelay()
    }

    /// report §1: publishes a NIP-56 report of another author's post; [block] also blocks its key on this device.
    func report(_ eventId: String, type: String, comment: String, block: Bool) {
        defer { refresh() }
        guard !fatal, let me = author else { return }
        let held = engine.entries().first(where: { $0.event.id == eventId })?.event
        guard let target = held ?? (relaySubscription.events() + publicEvents).first(where: { $0.id == eventId }), target.pubkey != me.nostrPubkey else {
            issue("この投稿は報告できません"); return
        }
        var text = comment.trimmingCharacters(in: .whitespacesAndNewlines)
        while text.utf8.count > 280 { text.removeLast() }
        do {
            let event = try me.createReport(target, type: type, comment: text, nowMillis: Self.now())
            try engine.originate(event, nowMillis: Self.now(), elapsedMillis: Self.elapsed())
            seenAt[event.id] = Instant(epochMillis: Self.now())
            absorbControl(event)
            reported.insert(eventId)
        } catch { issue("報告を保存できませんでした（容量・発信頻度の制限）"); return }
        try? persist()
        note("報告を署名・配送 \(eventId.prefix(8))")
        exchange.broadcastInventory()
        drainRelay()
        if block { self.block(target.pubkey) }
    }

    /// [extensions]: official extension maps (see [Extensions]); [deliveryExpiresAtSeconds] defaults to 24 h.
    func publish(_ text: String, extensions: [[String: Cbor]] = [], deliveryExpiresAtSeconds: Int64? = nil) {
        defer { state.publishAttempt += 1; refresh() }
        do {
            guard !fatal, let author, var current = disk else { throw CoreError("not ready") }
            try requireText(text)
            if filter.blocks(text) { issue("不適切な表現が含まれているため投稿できません"); return }
            let post = try author.createPost(text, nowMillis: Self.now(), previous: current.repository,
                                             deliveryExpiresAtSeconds: deliveryExpiresAtSeconds ?? Self.now() / 1000 + 86400, extensions: extensions)
            do { try engine.originate(post.event, nowMillis: Self.now(), elapsedMillis: Self.elapsed()) } catch {
                issue("投稿の保存容量・配送条件・サイズ・発信頻度の制限に達しました。保存・発信はしていません")
                return
            }
            seenAt[post.event.id] = Instant(epochMillis: Self.now())
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

    /// Hides one author's posts everywhere (mesh §10, by Nostr key); their device still relays other authors' posts to us.
    func block(_ pubkey: String) {
        defer { refresh() }
        guard !fatal, var current = disk, (try? unhex(pubkey, 32)) != nil, pubkey != author?.nostrPubkey,
              !current.blockedPubkeys.contains(pubkey), current.blockedPubkeys.count < DiskState.maxBlocked else { return }
        engine.remove { $0.event.pubkey == pubkey }
        current.blockedPubkeys.append(pubkey)
        try? persist(current)
        note("ブロック \(pubkey.prefix(8))")
        exchange.broadcastInventory()
    }

    func unblock(_ pubkey: String) {
        defer { refresh() }
        guard !fatal, var current = disk else { return }
        current.blockedPubkeys.removeAll { $0 == pubkey }
        try? persist(current)
        exchange.forgetRefused()
        note("ブロック解除 \(pubkey.prefix(8))")
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
    /// mesh §12: an event fetched from [relay] joins the mesh store like one received over a link; effective priority
    /// (engine.priority), deadlines and capacity decide what is kept and sent first. False if it was not stored.
    @discardableResult
    private func keepOnline(_ event: NostrEvent, _ relay: String) -> Bool {
        guard !fatal, var d = disk, !engine.entries().contains(where: { $0.event.id == event.id }),
              engine.deadline(event, nowMillis: Self.now()) != .expired,
              (try? engine.originate(event, nowMillis: Self.now(), elapsedMillis: Self.elapsed())) != nil else { return false }
        seenAt[event.id] = Instant(epochMillis: Self.now())
        absorbControl(event)
        // It came from this relay, so there is nothing to export back to it. A relay backlog arrives in bursts:
        // the tick persists once a second, and an entry lost to a crash is simply fetched again.
        d.relayAccepted[relay, default: []].insert(event.id)
        disk = d
        // The tick offers it to peers within 10 s; a backlog burst must not re-offer per event.
        dirty = true
        return true
    }

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
        advertiseSummary()
        // DEBUG lab isolation persists across launches; name it, or a later interop test silently finds no peers.
        let lab = [allowedPeerNode.map { "peer \($0) のみ" }, Self.lab("mosimo.test.links").map { "\($0) のみ" }].compactMap { $0 }
        note("BLE・LAN の探索と中継を開始（node \(node)\(lab.isEmpty ? "" : "／ラボ隔離 " + lab.joined(separator: "・"))）")
        drainRelay()
    }

    func stop() { stopInternal(); refresh() }

    private func stopInternal() {
        if dirty && !fatal { try? persist() }
        running = false
        for link in links { link.stop() }
        exchange.clear()
        clockSkews.removeAll()
        if !onlineAllowed() { goOffline() }
        note("中継を停止。保存済み投稿とリレー設定は保持")
    }

    /// The Bot/type pairs selected for ingress by the shipped config, as shown in settings (roots are in `state.trustRoots`).
    func trustSources() -> [TrustSource] {
        guard let json = Self.trustAsset("mosimo-public-trust"), let n = try? Json.parse(json) else { return [] }
        var order = [String](), types = [String: [String]]()
        for s in n["selected"]?.array ?? [] {
            guard let d = s["botDid"]?.string, let t = s["type"]?.string else { continue }
            if types[d] == nil { order.append(d) }
            types[d, default: []].append(t)
        }
        return order.map { TrustSource(botDid: $0, types: types[$0]!) }
    }

    /// A trust config bundled with the app; Debug builds alone also bundle `mosimo-public-trust-test` (project.yml).
    private static func trustAsset(_ name: String) -> String? {
        Bundle.main.url(forResource: name, withExtension: "json").flatMap { try? String(contentsOf: $0, encoding: .utf8) }
    }
    private static let trustChoicesKey = "publicTrustChoices"
    private static let testTrustAsset = "mosimo-public-trust-test"

    /// Public-information relay; a Debug build may point it at a local test relay (MOSIMO_TEST_PUBLIC_RELAY).
    private static let publicRelay: String = {
        #if DEBUG
        if let relay = testSetting("MosimoTestPublicRelay") { return relay }
        #endif
        return PublicDownload.pdsRelay
    }()

    private static func testSetting(_ key: String) -> String? {
        (Bundle.main.object(forInfoDictionaryKey: key) as? String).flatMap { $0.isEmpty ? nil : $0 }
    }

    // MARK: state

    private func persist(_ next: DiskState? = nil) throws {
        guard !fatal, var clean = next ?? disk else { throw CoreError("Persistence is disabled after a fatal error") }
        let entries = engine.entries()
        let ids = Set(entries.map(\.event.id))
        clean.publicAudit = clean.publicAudit.filter { ids.contains($0.key) }
        clean.relayAccepted = clean.relayAccepted.filter { clean.relays.contains($0.key) }.mapValues { $0.intersection(ids) }
        clean.revocations = revocations.all()
        let wall = Self.now(), monotonic = Self.elapsed()
        do {
            try store.save(clean, try entries.map { try engine.checkpoint($0, nowMillis: wall, elapsedMillis: monotonic) }, bootTime: bootTime)
            disk = clean; dirty = false
            seenAt = seenAt.filter { ids.contains($0.key) }
            advertiseSummary()
        } catch {
            fatal = true
            stopInternal()
            issue("永続化に失敗。未保存データを送信せず停止しました（再起動が必要）")
            throw error
        }
    }

    /// mesh.adv: keep the summary we compare against advertised ones in step with the store.
    private func advertiseSummary() {
        guard running else { return }
        gatt.setSummary(engine.summary(nowMillis: Self.now(), elapsedMillis: Self.elapsed()))
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
        // BLE links are released after each exchange (mesh §3.4), so "nearby" is what the radios saw recently plus the
        // peers in session now; one entry per node even when it is reachable over BLE and LAN at once.
        var links = [String: [String]](), nodes = [String]()
        for session in exchange.sessions where session.ready {
            let node = session.label
            if links[node] == nil { nodes.append(node) }
            links[node, default: []].append(session.link.name)
        }
        var seen = [String: [String]]()
        for link in [gatt as Link, lan] { for n in link.nearby(within: Self.nearbySeconds) where links[n] == nil { seen[n, default: []].append(link.name) } }
        s.neighbors = nodes.map { Neighbor(endpoint: $0, name: $0, status: "同期中（\(links[$0]!.joined(separator: "・"))）") }
            + seen.keys.sorted().map { Neighbor(endpoint: $0, name: $0, status: "近く（\(seen[$0]!.joined(separator: "・"))）") }
        if fatal { s.posts = [] } else {
            let now = Self.now()
            let accepted = disk?.relayAccepted ?? [:]
            // Other apps' kinds (DMs, profiles) are relayed but are not posts.
            var posts = engine.entries().reversed().filter { $0.event.kind == 1 }.map { e in
                PostView(id: e.event.id, pubkey: e.event.pubkey, content: e.event.content, hop: e.hop, relayAccepted: acceptedAnywhere(e.event.id),
                         deliveryDeadline: engine.deadline(e.event, nowMillis: now), acceptedBy: accepted.filter { $0.value.contains(e.event.id) }.map(\.key),
                         publicAuthorization: disk?.publicAudit[e.event.id], did: e.did, createdAt: e.event.createdAt, ext: Extensions.of(e.event),
                         chain: chainOf(e.event))
            }
            let meshIds = Set(posts.map(\.id))
            var seen = Set<String>()
            let online = (relaySubscription.events() + publicEvents).filter { seen.insert($0.id).inserted }
            let publicIds = Set(publicEvents.map(\.id))
            posts += online.reversed().filter { !meshIds.contains($0.id) }.map {
                PostView(id: $0.id, pubkey: $0.pubkey, content: $0.content, hop: nil, relayAccepted: false,
                         sourceRelay: publicIds.contains($0.id) ? DiskState.pdsRelay : activeRelay, volatile: true,
                         deliveryDeadline: engine.deadline($0, nowMillis: now), did: didOf($0), createdAt: $0.createdAt, ext: Extensions.of($0),
                         chain: chainOf($0))
            }
            let hide = hideExpired
            let stopped = Set(engine.entries().map(\.event).filter(moderation.stopped).map(\.id) + online.filter(moderation.stopped).map(\.id))
            s.posts = posts.filter { !blocked.contains($0.pubkey) && !stopped.contains($0.id) &&
                !(hide && $0.deliveryDeadline == .expired) && !filter.blocks($0.content) }
        }
        s.publicBusy = publicBusy
        s.blockedPubkeys = blocked
        s.roots = publicConfig.roots
        s.trustRoots = trustEntries
        s.revocationCount = revocations.size
        s.revocationChecked = disk?.revocationChecked ?? [:]
        s.clockSkews = clockSkews
        s.reported = reported
        let live = Set(engine.entries().map(\.event.id))
        forwarded = forwarded.filter { live.contains($0.key) }
        s.forwardedTo = forwarded.mapValues(\.count)
        s.hideExpired = hideExpired
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
        #if DEBUG
        if let text = labPost {
            labPost = nil
            publish("［安全］" + text, extensions: [Extensions.origin(Origin(lat: 35_681_236, lon: 139_767_125)), connectivity(online: up)],
                    deliveryExpiresAtSeconds: Self.now() / 1000 + 3600)
        }
        #endif
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
        chainCache.removeAll(); priorityCache.removeAll()
        do {
            guard let json = Self.trustAsset("mosimo-public-trust") else { throw CoreError("missing asset") }
            let builtin = try PublicIngress.config(json)
            // Development builds alone ship the test-post root; release builds have no such resource.
            let test = Self.trustAsset(Self.testTrustAsset).flatMap { try? PublicIngress.config($0) }
            (publicConfig, trustEntries) = PublicIngress.effective(builtin, test, trustChoices())
            state.publicStatus = "公的情報: 有効 root \(publicConfig.roots.filter(\.enabled).count) / 選択 Bot/type \(publicConfig.selected.count)（未設定は投入不可）"
        } catch {
            state.publicStatus = "公的情報: config 読込失敗 / fail closed（既存鍵・失効履歴は保持）"
        }
    }

    private func trustChoices() -> PublicIngress.TrustChoices { .parse(defaults.string(forKey: Self.trustChoicesKey)) }
    /// Local trust choices (attest §3): only the user changes them, then the config and every chain verdict are rebuilt.
    private func updateTrustChoices(_ change: (PublicIngress.TrustChoices) -> PublicIngress.TrustChoices) {
        defaults.set(change(trustChoices()).toJson(), forKey: Self.trustChoicesKey)
        reloadPublicConfig()
        refresh()
    }
    func setRootEnabled(_ pubkey: String, _ enabled: Bool) {
        updateTrustChoices { c in var c = c; if enabled { c.disabled.remove(pubkey) } else { c.disabled.insert(pubkey) }; return c }
    }
    /// False when [key] is not a 64-hex public key; the root is then not added.
    func addRoot(_ key: String, _ name: String) -> Bool {
        guard let hex = PublicIngress.rootKey(key) else { return false }
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        updateTrustChoices { c in
            var c = c
            c.added = c.added.filter { $0.pubkey != hex } + [.init(pubkey: hex, name: trimmed.isEmpty ? "追加した root" : trimmed)]
            return c
        }
        return true
    }
    func removeRoot(_ pubkey: String) {
        updateTrustChoices { c in var c = c; c.added.removeAll { $0.pubkey == pubkey }; c.disabled.remove(pubkey); return c }
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
        publicStale = false
        publicBusy = true
        publicGeneration += 1
        let generation = publicGeneration
        let config = publicConfig
        let confidence = engine.clockConfidence
        let held = revocations
        state.publicStatus = "PDS relay から priority-v1 取得中…"
        state.publicResults = []
        publicTask = Task.detached(priority: .utility) { [weak self] in
            // Network IO and crypto run off the main actor; results are applied back on it under the generation fence.
            let batch: Result<PublicDownload.Batch, Error>
            let since = MeshCoordinator.now() / 1000 - PublicDownload.ingressWindowSeconds
            do { batch = .success(try await PublicDownload.fetch(token: generation, relay: MeshCoordinator.publicRelay, since: since)) } catch { batch = .failure(error) }
            if Task.isCancelled { return }
            let clock = PublicDownload.ingressClock(confidence, MeshCoordinator.now())
            // Decisions are made against the revocations held at request time; insertion re-checks them.
            var decisions = ((try? batch.get())?.events ?? []).map { ($0, PublicIngress.verify($0, config, held, clock)) }
            decisions.sort { ($0.1.receipt?.priority ?? min(PublicIngress.priority($0.0), 1)) > ($1.1.receipt?.priority ?? min(PublicIngress.priority($1.0), 1)) }
            let finalDecisions = decisions
            await self?.completePublic(generation, batch, finalDecisions, clock == nil)
            guard case .success(let first) = batch else { return }
            let pages = await PublicDownload.more(token: generation, first: first, relay: MeshCoordinator.publicRelay)
            await self?.completePages(generation, first, pages)
        }
        refresh()
    }

    private func publicSummary(_ fallback: Bool, _ imported: Int, _ clockUnknown: Bool) -> String {
        "PDS \(fallback ? "通常REQ fallback" : "priority-v1"): \(publicEvents.count) 件取得 / \(imported) 件投入（原Bot署名・現在PLC認証ではない）" +
            (clockUnknown ? " / 時計 unknown・認可保留" : "")
    }

    private func completePublic(_ generation: Int64, _ batch: Result<PublicDownload.Batch, Error>,
                                _ decisions: [(NostrEvent, PublicIngress.Decision)], _ clockUnknown: Bool) {
        defer { refresh() }
        guard generation == publicGeneration, !fatal, onlineAllowed(), disk != nil else { return }
        // A successful first batch stays busy while display-only older pages load.
        if case .failure = batch { publicBusy = false; publicTask = nil }
        switch batch {
        case .failure(let e):
            state.publicStatus = "公的情報取得失敗: \(e)"
        case .success(let result):
            let known = Set(publicEvents.map(\.id) + engine.entries().map(\.event.id))
            if result.events.contains(where: { !known.contains($0.id) }) { received(online: true) }
            publicEvents = result.events
            var reports = [String](), imported = 0
            for (event, decision) in decisions {
                if fatal { break }
                let insertion = PublicDownload.ingressClock(engine.clockConfidence, Self.now())
                let outcome: String
                if let receipt = decision.receipt {
                    // importer §3: re-check the whole authorization (incl. revocations learned meanwhile) at insertion.
                    if !PublicIngress.readyToInsert(receipt, event, insertion) || PublicIngress.verify(event, publicConfig, revocations, insertion).receipt == nil {
                        outcome = "投入保留: 待機中に証明・失効・期限・時計が無効化"
                    }
                    else if engine.entries().contains(where: { $0.event.id == event.id }) { outcome = "既存 event ID / 重複投入なし" }
                    else {
                        do {
                            try engine.originate(event, nowMillis: Self.now(), elapsedMillis: Self.elapsed())
                            seenAt[event.id] = Instant(epochMillis: Self.now())
                            var d = disk!
                            d.publicAudit[event.id] = "取込時の転載者認可（現在の公式認証ではない）: \(receipt)"
                            try persist(d)
                            imported += 1
                            outcome = "認可・暗号化保存済み / priority \(receipt.priority)"
                        } catch { outcome = "通常 mesh 資源制限: \(error)" }
                    }
                } else { outcome = (keepOnline(event, Self.publicRelay) ? "通常投稿として保持" : "保持済み・対象外") + " / 認可なし: \(decision.reason)" }
                reports.append("\(event.id.prefix(8)) \(outcome)\n\(decision.chain?.reason ?? decision.reason)")
            }
            state.publicStatus = publicSummary(result.fallback, imported, clockUnknown) + (result.events.count >= PublicDownload.limit ? " / 続きを取得中…" : "")
            state.publicResults = Array(reports.prefix(40))
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

    /// attest.revocation.list §5: with Internet access, fetch the revocation lists of the issuer domains seen in held
    /// chains, verify each notice and keep it permanently. A failed fetch keeps the previous state.
    private func fetchRevocations() {
        guard !fatal, disk != nil, !revocationBusy, onlineAllowed(), network, Self.elapsed() >= revocationNext else { return }
        var domains = [String]()
        for e in engine.entries() {
            for chain in (try? Attest.chains(e.event)) ?? [] {
                for a in chain.attestations { if let d = a.issuerDomain, !domains.contains(d) { domains.append(d) } }
            }
        }
        domains = Array(domains.prefix(8))
        if domains.isEmpty { return }
        revocationNext = Self.elapsed() + Self.revocationIntervalMs
        revocationBusy = true
        Task.detached(priority: .utility) { [weak self] in
            var fetched = [(String, Result<[Attest.Revocation], Error>)]()
            for d in domains {
                do { fetched.append((d, .success(try Attest.revocationList(try await PublicDownload.revocationList(domain: d))))) }
                catch { fetched.append((d, .failure(error))) }
            }
            let results = fetched
            await self?.completeRevocations(results)
        }
    }

    private func completeRevocations(_ fetched: [(String, Result<[Attest.Revocation], Error>)]) {
        defer { refresh() }
        revocationBusy = false
        guard !fatal, var current = disk else { return }
        var added = 0
        for (domain, result) in fetched {
            switch result {
            case .success(let list):
                current.revocationChecked[domain] = Self.now() / 1000
                for r in list where (try? revocations.add(r)) == true { added += 1 }
            case .failure(let e): note("失効リスト取得失敗 \(domain): \(e)")
            }
        }
        if added > 0 || current.revocationChecked != disk?.revocationChecked {
            chainCache.removeAll(); priorityCache.removeAll()
            try? persist(current)
            if added > 0 { note("失効通知 \(added) 件を取得・永続保存（配送候補を再評価）") }
        }
    }

    // MARK: relay export (mesh §12)

    private func backoff(_ attempts: Int) -> Int64 { min(1000 << Int64(min(max(attempts, 0), 6)), 60_000) + Int64.random(in: 0..<1000) }

    private func pendingFor(_ relay: String) -> [StoredBundle] {
        guard let d = disk else { return [] }
        let done = d.relayAccepted[relay] ?? []
        // A stopped post (our own, kept for the PDS) goes to the PDS relay only (delete §3).
        return engine.entries().filter { DiskState.relayWants(relay, $0.event) && !done.contains($0.event.id) &&
            (relay == DiskState.pdsRelay || !moderation.stopped($0.event)) }
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

    /// Rotates through the configured relays so every one eventually receives every held event (mesh §12).
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
        // mesh §12: a relay event joins the mesh store like one from a link; it also re-triggers the public request
        // (tick), which records importer authorization for display and priority.
        case .event(let event): received(online: true); keepOnline(event, activeRelay); publicStale = true; if relaySubscription.atCapacity { closeRelayRead() }
        case .full: closeRelayRead()
        case .eose, .closed: relayReadDeadline = 0
        case .ok(let id, let accepted, let message): relayOk(id, accepted, message)
        case nil: break
        }
    }

    private func relayOk(_ id: String, _ accepted: Bool, _ message: String) {
        guard let pending = relayPendingId, pending == id, var current = disk else { return }
        relayPendingId = nil; relayDeadline = 0
        if !accepted {
            // OK false is relay policy, not transport; deferring avoids a reconnect loop.
            relayDeferred[activeRelay, default: [:]][id] = Self.elapsed() + Self.rejectDeferMillis
            note("リレー拒否 \(id.prefix(8))（\(activeRelay)）: \(message.isEmpty ? "理由なし" : message)。投稿は保持")
            closeRelay(); relayIndex += 1
            drainRelay()
            return
        }
        current.relayAccepted[activeRelay, default: []].insert(id)
        disk = current
        dirty = true
        pruneStopped()
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

    /// Unregistered with posts held: carry the signed genesis operation as its own control event (PDS §6).
    private func originateGenesisIfUnregistered() {
        guard let current = disk, let me = author, !current.plcRegistered, configuredHttps(current.pdsEndpoint) != nil else { return }
        let own = engine.entries().filter { $0.event.pubkey == me.nostrPubkey }
        if !own.contains(where: { $0.event.kind == 1 }) || own.contains(where: { AtRepository.controlTopic($0.event) == AtRepository.plcTopic }) { return }
        // A rate-limited or rejected attempt is retried on a later tick.
        guard let event = try? me.createPlcGenesisEvent(nowMillis: Self.now()),
              (try? engine.originate(event, nowMillis: Self.now(), elapsedMillis: Self.elapsed())) != nil else { return }
        seenAt[event.id] = Instant(epochMillis: Self.now())
        try? persist()
        note("PLC 未登録のため署名済み初期操作をメッシュで配送（代理登録用）")
        exchange.broadcastInventory()
    }

    /// Online nodes submit received genesis operations unchanged; PlcDirectory.check never POSTs over an existing log.
    private func proxyRegister() {
        guard !fatal, allowRegistration, !proxyBusy, network else { return }
        let clock = Self.elapsed()
        let candidates = engine.entries().filter { AtRepository.controlTopic($0.event) == AtRepository.plcTopic && $0.event.pubkey != author?.nostrPubkey }
        guard let (did, operation) = candidates.lazy.compactMap({ e in (try? AtRepository.plcDid(e.event.content)).map { ($0, e.event.content) } })
                .first(where: { clock >= (self.proxyNext[$0.0]?.1 ?? 0) }) else { return }
        proxyBusy = true
        let directory = plcDirectory
        Task { [weak self] in
            let confirmed = await PlcDirectory.check(directory: directory, did: did, genesisJson: operation, previouslyConfirmed: false, allowRegistration: true).genesisConfirmed
            guard let self else { return }
            self.proxyBusy = false
            // ponytail: per-DID retry forever at most once a minute; add a give-up rule once load limits are defined.
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
        fetchRevocations()
        let clock = Self.elapsed()
        if clock - lastChainFlush >= 60_000 { lastChainFlush = clock; chainCache.removeAll(); priorityCache.removeAll() }
        if relayDeadline > 0 && clock >= relayDeadline { relayFailure() }
        if relayReadDeadline > 0 && clock >= relayReadDeadline { closeRelayRead() }
        drainRelay()
        if publicStale && clock >= publicNext { requestPublic(manual: false) }
        guard running else { return }
        exchange.dropStale()
        if clock - lastSync >= 10_000 { lastSync = clock; exchange.broadcastInventory(); advertiseSummary() }
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
