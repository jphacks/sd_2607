import Foundation

/// Link callbacks may arrive on any thread; the coordinator re-serializes them onto its own queue.
public protocol LinkListener: AnyObject {
    /// [node] is the peer's advertised node ID when this side discovered it (central / dialer); nil otherwise.
    func onConnected(_ link: Link, _ peer: String, node: String?)
    /// One complete common exchange message (mesh §5), cut from the link's CBOR sequence.
    func onMessage(_ link: Link, _ peer: String, _ message: [UInt8])
    /// The link's outbound queue for [peer] drained; the exchange may push the next fragments.
    func onDrained(_ link: Link, _ peer: String)
    func onDisconnected(_ link: Link, _ peer: String)
}

/// One transport (BLE GATT, LAN TCP). It discovers and connects on its own and moves whole CBOR messages as an
/// ordered byte stream (mesh §7); store-carry-forward stays in the coordinator and MeshEngine. Peer IDs are
/// link-local connection handles.
public protocol Link: AnyObject {
    var name: String { get }
    /// [node] is this process's 16-hex node ID, used only to break ties so two nodes open one connection.
    func start(node: String, listener: LinkListener)
    func stop()
    /// Queues one whole message of at most [Messages.maxMessageBytes] bytes; false when the peer is gone or backlogged.
    func send(_ peer: String, _ message: [UInt8]) -> Bool
    /// Number of queued messages not yet handed to the OS for [peer]; the exchange keeps this small so control messages get through.
    func queued(_ peer: String) -> Int
    func disconnect(_ peer: String)
    /// Reconciliation with [peer] completed; a link may remember the peer's advertised summary to skip idle reconnects (mesh.adv §4).
    func synced(_ peer: String)
    /// [peer] broke the exchange framing (mesh §7, e.g. a pre-v1 build); a link may stop redialing its node for a while.
    func rejected(_ peer: String)
}

public extension Link {
    func rejected(_ peer: String) {}
}

/// One link connection's contact state (mesh §4-5).
public final class Session {
    public let link: Link
    public let handle: String
    let began: Int64
    public let node: String?
    let nonce = Secp.randomBytes(8)
    var peer: (maxBundleBytes: Int, initialCredit: Int, maxMessageBytes: Int, nonce: [UInt8])?
    /// Mesh §5.4 side number after HELLO (0 = smaller nonce); -1 before.
    var side = -1
    var peerSide: Int { 1 - side }
    var nextRequest: Int64 = 0, nextTransfer: Int64 = 0, nextReconcile: Int64 = 0
    var reconcile: Negentropy?
    var reconcileId: Int64 = -1
    var reconcileStarted: Int64 = 0
    var reconcileCandidates = Set<String>()
    var reconciledSummary: [UInt8]?
    var sawReconcile = false
    /// Requests we issued: request ID → event IDs (hex) still open.
    var requests = [Int64: Set<String>]()
    /// Event IDs (hex) the peer is known to hold.
    var peerHas = Set<String>()
    var offered = Set<String>()
    /// Peer WANTs not yet started: (event ID hex, peer's request ID).
    var queue = [(String, Int64)]()
    var outgoing = [Int64: OutTransfer]()
    var sending: OutTransfer?
    var incoming = [Int64: InTransfer]()
    var closedIncoming = [Int64]()
    var window: Int64 = 0
    var messages = 0
    public var ready: Bool { peer != nil }
    /// Display label: the advertised node when known, else the session nonce pair.
    public var label: String {
        if let node { return node }
        guard let p = peer else { return handle }
        return hex(side == 0 ? nonce + p.nonce : p.nonce + nonce)
    }

    init(link: Link, handle: String, began: Int64, node: String?) { self.link = link; self.handle = handle; self.began = began; self.node = node }

    final class OutTransfer {
        let id: Int64, eventId: String, bytes: [UInt8], requestId: Int64, startedAt: Int64
        var credit: Int
        var sent = 0
        var complete: Bool { sent >= bytes.count }
        init(id: Int64, eventId: String, bytes: [UInt8], requestId: Int64, credit: Int, startedAt: Int64) {
            self.id = id; self.eventId = eventId; self.bytes = bytes; self.requestId = requestId; self.credit = credit; self.startedAt = startedAt
        }
    }
    final class InTransfer {
        let id: Int64, eventId: String, requestId: Int64, length: Int
        var buffer = [UInt8]()
        var granted = 0
        var lastActivity: Int64
        init(id: Int64, eventId: String, requestId: Int64, length: Int, startedAt: Int64) {
            self.id = id; self.eventId = eventId; self.requestId = requestId; self.length = length; lastActivity = startedAt
        }
    }
}

/// Contact exchange over any [Link] (mesh §4-5): HELLO → RECONCILE (Negentropy) → WANT/INVENTORY → BEGIN/DATA with
/// CREDIT flow control → RESULT. Peers are auto-accepted: trust comes from the Nostr signature check in [MeshEngine]
/// plus resource caps. Not thread-safe; call only from the coordinator's queue.
public final class Exchange {
    public static let maxSessions = 16, maxRefused = 1024
    /// Our receive limit per common message (mesh §6); links cut their byte streams against it.
    public static let messageLimit = 16_384
    /// Mesh §5.4 initial credit: bytes a peer may send after BEGIN before our CREDIT.
    public static let initialCredit = 4096
    /// Mesh §4.5: transfers per direction awaiting RESULT.
    static let maxOutstanding = 8
    static let maxQueuedFragments = 2
    static let messagesPerMinute = 4096
    static let helloTimeoutMs: Int64 = 60_000, transferTimeoutMs: Int64 = 90_000, reconcileTimeoutMs: Int64 = 60_000

    private let engine: MeshEngine
    private let now: () -> Int64, elapsed: () -> Int64
    private let note: (String) -> Void
    private let log: (String) -> Void
    // Optional test isolation only: node IDs are ephemeral and are NOT peer authentication.
    private let allowedPeerNode: String?
    // (event id, peer label): a peer stored or already holds a bundle we sent it.
    private let forwarded: (String, String) -> Void
    // Must durably save before returning.
    private let accepted: (StoredBundle) throws -> Void
    /// mesh.time: our HELLO hint (nil = none) and the peer's observed hint (label, delta seconds, confidence).
    private let timeHint: () -> [String: Cbor]?
    private let onTimeHint: (String, Int64, Int) -> Void
    private let messageLimit: Int, initialCredit: Int
    /// Event IDs not worth requesting (targets stopped by reports); refused again by MeshEngine.admit if they arrive.
    private let skip: (String) -> Bool
    public private(set) var sessions = [Session]()
    // ponytail: in-memory FIFO; a restarted app re-wants a refused bundle once.
    private var refused = [String]()

    public init(engine: MeshEngine, now: @escaping () -> Int64, elapsed: @escaping () -> Int64, note: @escaping (String) -> Void = { _ in },
                log: @escaping (String) -> Void = { _ in }, allowedPeerNode: String? = nil,
                forwarded: @escaping (String, String) -> Void = { _, _ in }, accepted: @escaping (StoredBundle) throws -> Void = { _ in },
                timeHint: @escaping () -> [String: Cbor]? = { nil }, onTimeHint: @escaping (String, Int64, Int) -> Void = { _, _, _ in },
                messageLimit: Int = Exchange.messageLimit, initialCredit: Int = Exchange.initialCredit, skip: @escaping (String) -> Bool = { _ in false }) {
        self.skip = skip
        self.engine = engine; self.now = now; self.elapsed = elapsed; self.note = note; self.log = log
        self.allowedPeerNode = allowedPeerNode; self.forwarded = forwarded; self.accepted = accepted
        self.timeHint = timeHint; self.onTimeHint = onTimeHint; self.messageLimit = messageLimit; self.initialCredit = initialCredit
    }

    private func session(_ link: Link, _ handle: String) -> Session? { sessions.first { $0.link === link && $0.handle == handle } }

    public func connected(_ link: Link, _ handle: String, node: String?) {
        if sessions.count >= Self.maxSessions || node != nil && allowedPeerNode != nil && node != allowedPeerNode { link.disconnect(handle); return }
        let s = Session(link: link, handle: handle, began: elapsed(), node: node)
        sessions.append(s)
        let hello = Msg.hello(nonce: s.nonce, maxBundleBytes: MeshPolicy.maxBundleBytes, routes: Messages.routeGatt | Messages.routeLan,
                              initialCredit: initialCredit, maxMessageBytes: messageLimit, extensions: timeHint().map { [$0] } ?? [])
        if !send(s, hello) { drop(s) }
    }

    public func disconnected(_ link: Link, _ handle: String) {
        guard let s = session(link, handle) else { return }
        sessions.removeAll { $0 === s }
        if s.sawReconcile { s.link.synced(s.handle) }
    }

    public func drained(_ link: Link, _ handle: String) { if let s = session(link, handle), s.ready { pump(s) } }

    public func message(_ link: Link, _ handle: String, _ bytes: [UInt8]) {
        guard let s = session(link, handle) else { return }
        if elapsed() - s.window >= 60_000 { s.window = elapsed(); s.messages = 0 }
        s.messages += 1
        if s.messages > Self.messagesPerMinute { drop(s); return }
        do {
            let m = try Messages.decode(bytes)
            if case let .hello(nonce, maxBundle, _, credit, maxMessage, ext) = m { try hello(s, nonce, maxBundle, credit, maxMessage, ext); return }
            try require(s.ready, "HELLO required")
            switch m {
            case let .reconcile(id, message): try reconcile(s, id, message)
            case let .inventory(ids):
                if s.sawReconcile { s.link.synced(s.handle) }
                for id in ids { s.peerHas.insert(hex(id)) }
                want(s, ids.map(hex))
            case let .want(r, ids): if s.sawReconcile { s.link.synced(s.handle) }; want(s, r, ids)
            case let .begin(t, r, target, length, fragment): begin(s, t, r, target, length, fragment)
            case let .data(t, fragment): try data(s, t, fragment)
            case let .credit(t, limit): credit(s, t, limit)
            case let .result(target, code, reason): result(s, target, code, reason)
            case let .fetch(r, hashes):
                for h in hashes { _ = send(s, .result(target: .object(side: s.peerSide, requestId: r, hash: h), code: Messages.resultUnavailable, reason: Messages.reasonMissing)) }
            case let .unknown(type): log("ignoring unknown message type \(type) from \(s.label)")
            case .hello: break
            }
        } catch {
            log("reject message from \(s.label): \(error)")
            note("不正・制限超過メッセージを拒否")
            s.link.rejected(s.handle)
            drop(s)
        }
    }

    /// Offers newly held candidates to every peer; side 0 also re-reconciles when its set changed since the last pass.
    public func broadcastInventory() {
        for s in sessions where s.ready {
            inventory(s)
            if s.side == 0 && s.reconcile == nil && s.reconciledSummary != engine.summary(nowMillis: now(), elapsedMillis: elapsed()) { startReconcile(s) }
        }
    }

    /// Drops connections that never completed HELLO and times out stalled transfers, so a silent peer cannot hold resources.
    public func dropStale() {
        let t = elapsed()
        for s in sessions where !s.ready && t - s.began > Self.helloTimeoutMs { drop(s) }
        for s in sessions {
            for i in s.incoming.values where t - i.lastActivity > Self.transferTimeoutMs {
                s.incoming[i.id] = nil; closeIncoming(s, i.id)
                _ = send(s, .result(target: .transfer(side: s.peerSide, transferId: i.id), code: Messages.resultRejected, reason: Messages.reasonResources))
                s.requests[i.requestId]?.remove(i.eventId)
            }
            for o in s.outgoing.values where o.complete && t - o.startedAt > Self.transferTimeoutMs { s.outgoing[o.id] = nil }
            if s.reconcile != nil && t - s.reconcileStarted > Self.reconcileTimeoutMs { s.reconcile = nil; log("reconciliation timed out with \(s.label)") }
            s.requests = s.requests.filter { !$0.value.isEmpty }
            if s.ready { pump(s) }
        }
    }

    public func clear() { sessions.removeAll() }

    /// After an unblock; still-blocked authors are simply refused once more.
    public func forgetRefused() { refused.removeAll() }

    private func hello(_ s: Session, _ nonce: [UInt8], _ maxBundle: Int, _ credit: Int, _ maxMessage: Int, _ ext: [[String: Cbor]]) throws {
        try require(!s.ready, "Duplicate HELLO")
        if nonce == s.nonce { drop(s); return }
        s.peer = (maxBundle, credit, maxMessage, nonce)
        s.side = s.nonce.lexicographicallyPrecedes(nonce) ? 0 : 1
        if let hint = ext.first(where: { $0["$type"] == .text(Messages.timeHint) }), let peerNow = hint["now"]?.int,
           let confidence = hint["confidence"]?.int, (0...3).contains(confidence) {
            onTimeHint(s.label, peerNow - now() / 1000, Int(confidence))
        }
        log("hello from \(s.label) on \(s.link.name):\(s.handle) side=\(s.side) max=\(maxBundle) msg=\(maxMessage) credit=\(credit)")
        if s.side == 0 { startReconcile(s) }
    }

    private func candidates() -> [Negentropy.Item] { engine.candidates(nowMillis: now(), elapsedMillis: elapsed()) }

    private func frameLimit(_ s: Session) -> Int { min(messageLimit, s.peer!.maxMessageBytes) }

    private func startReconcile(_ s: Session) {
        let items = candidates()
        guard let neg = try? Negentropy(items, frameSizeLimit: frameLimit(s) - Messages.reconcileOverhead) else { return }
        s.reconcile = neg; s.reconcileId = s.nextReconcile; s.nextReconcile += 1; s.reconcileStarted = elapsed()
        s.reconcileCandidates = Set(items.map { hex($0.id) })
        s.sawReconcile = true
        _ = send(s, .reconcile(id: s.reconcileId, message: neg.initiate()))
    }

    private func reconcile(_ s: Session, _ id: Int64, _ message: [UInt8]) throws {
        s.sawReconcile = true
        if let ours = s.reconcile, id == s.reconcileId {
            if let next = try ours.reconcile(message) { _ = send(s, .reconcile(id: id, message: next)); return }
            let have = Set(ours.haveIds.map(hex))
            s.peerHas.formUnion(s.reconcileCandidates.subtracting(have))
            s.reconcile = nil
            s.reconciledSummary = engine.summary(nowMillis: now(), elapsedMillis: elapsed())
            log("reconciled with \(s.label): have \(have.count) need \(ours.needIds.count)")
            want(s, ours.needIds.map(hex))
            inventory(s, have)
            s.link.synced(s.handle)
            return
        }
        // Peer-initiated: answer from our current candidate set.
        let responder = try Negentropy(candidates(), frameSizeLimit: frameLimit(s) - Messages.reconcileOverhead)
        if let reply = try responder.reconcile(message) { _ = send(s, .reconcile(id: id, message: reply)) }
    }

    private func want(_ s: Session, _ ids: [String]) {
        let owned = Set(engine.entries().map(\.event.id))
        let open = Set(s.requests.values.flatMap { $0 })
        var seen = Set<String>()
        let missing = ids.filter { !owned.contains($0) && !refused.contains($0) && !open.contains($0) && !skip($0) && seen.insert($0).inserted }
        if missing.isEmpty { return }
        for chunk in missing.chunked(Messages.idsPerMessage(frameLimit(s))) {
            let id = s.nextRequest; s.nextRequest += 1
            s.requests[id] = Set(chunk)
            _ = send(s, .want(requestId: id, ids: chunk.compactMap { try? unhex($0, 32) }))
        }
        log("want \(missing.count) from \(s.label)")
    }

    private func inventory(_ s: Session, _ candidates: Set<String>? = nil) {
        let ids = candidates.map(Array.init) ?? engine.inventory(nowMillis: now(), elapsedMillis: elapsed())
        let fresh = ids.filter { !s.peerHas.contains($0) && !s.offered.contains($0) }
        if fresh.isEmpty { return }
        for chunk in fresh.chunked(Messages.idsPerMessage(frameLimit(s))) { _ = send(s, .inventory(ids: chunk.compactMap { try? unhex($0, 32) })) }
        s.offered.formUnion(fresh)
    }

    private func want(_ s: Session, _ requestId: Int64, _ ids: [[UInt8]]) {
        let eligible = Set(engine.inventory(nowMillis: now(), elapsedMillis: elapsed()))
        for id in ids {
            let h = hex(id)
            if eligible.contains(h) { s.queue.append((h, requestId)) }
            else { _ = send(s, .result(target: .event(side: s.peerSide, requestId: requestId, id: id), code: Messages.resultUnavailable, reason: Messages.reasonMissing)) }
        }
        log("want \(ids.count) from \(s.label), queued \(s.queue.count)")
        pump(s)
    }

    /// Starts queued transfers (≤ 8 awaiting RESULT) and pushes DATA within credit, keeping the link queue short.
    private func pump(_ s: Session) {
        guard let peer = s.peer else { return }
        let fragmentMax = frameLimit(s) - Messages.dataOverhead
        while true {
            guard let t = s.sending else {
                if s.outgoing.count >= Self.maxOutstanding || s.queue.isEmpty { return }
                let (id, requestId) = s.queue.removeFirst()
                guard let eventId = try? unhex(id, 32) else { continue }
                guard let bytes = engine.forward(id, nowMillis: now(), elapsedMillis: elapsed()) else {
                    _ = send(s, .result(target: .event(side: s.peerSide, requestId: requestId, id: eventId), code: Messages.resultUnavailable, reason: Messages.reasonMissing))
                    continue
                }
                if bytes.count > peer.maxBundleBytes {
                    _ = send(s, .result(target: .event(side: s.peerSide, requestId: requestId, id: eventId), code: Messages.resultUnavailable, reason: Messages.reasonSize))
                    continue
                }
                let transfer = Session.OutTransfer(id: s.nextTransfer, eventId: id, bytes: bytes, requestId: requestId,
                                                   credit: min(peer.initialCredit, bytes.count), startedAt: elapsed())
                let first = Array(bytes.prefix(min(transfer.credit, frameLimit(s) - Messages.beginOverhead)))
                if !send(s, .begin(transferId: transfer.id, requestId: requestId, target: eventId, length: bytes.count, fragment: first.isEmpty ? nil : first)) {
                    s.queue.insert((id, requestId), at: 0) // link backlogged: retry on the next drain
                    return
                }
                s.nextTransfer += 1
                s.outgoing[transfer.id] = transfer
                s.sending = transfer
                transfer.sent = first.count
                log("bundle \(id.prefix(8)) \(bytes.count)B to \(s.label): begin")
                continue
            }
            if t.complete { s.sending = nil; continue }
            if t.sent >= t.credit { return } // waiting for CREDIT
            if s.link.queued(s.handle) >= Self.maxQueuedFragments { return }
            let end = min(t.credit, t.sent + fragmentMax)
            if !send(s, .data(transferId: t.id, fragment: Array(t.bytes[t.sent..<end]))) { return }
            t.sent = end
        }
    }

    private func credit(_ s: Session, _ transferId: Int64, _ limit: Int) {
        guard let t = s.outgoing[transferId] else { return }
        if limit > t.credit { t.credit = min(limit, t.bytes.count) }
        pump(s)
    }

    private func result(_ s: Session, _ target: Msg.Target, _ code: Int, _ reason: Int) {
        switch target {
        case let .transfer(side, transferId):
            guard side == s.side, let t = s.outgoing.removeValue(forKey: transferId) else { return }
            if s.sending === t { s.sending = nil }
            if code == Messages.resultStored || code == Messages.resultAlreadyHeld { s.peerHas.insert(t.eventId); forwarded(t.eventId, s.label) }
            else { log("peer \(s.label) refused \(t.eventId.prefix(8)): code \(code) reason \(reason)") }
            pump(s)
        case let .event(side, requestId, id):
            if side == s.side { s.requests[requestId]?.remove(hex(id)) }
        case .object: break
        }
    }

    private func begin(_ s: Session, _ transferId: Int64, _ requestId: Int64, _ target: [UInt8], _ length: Int, _ fragment: [UInt8]?) {
        let id = hex(target)
        func reply(_ code: Int, _ reason: Int) {
            closeIncoming(s, transferId)
            _ = send(s, .result(target: .transfer(side: s.peerSide, transferId: transferId), code: code, reason: reason))
        }
        guard let request = s.requests[requestId], request.contains(id) else { reply(Messages.resultRejected, Messages.reasonPolicy); return }
        if engine.entries().contains(where: { $0.event.id == id }) {
            s.requests[requestId]?.remove(id); s.peerHas.insert(id); reply(Messages.resultAlreadyHeld, Messages.reasonNone); return
        }
        if length > MeshPolicy.maxBundleBytes { s.requests[requestId]?.remove(id); reply(Messages.resultRejected, Messages.reasonSize); return }
        if s.incoming.count >= Self.maxOutstanding || s.incoming[transferId] != nil { reply(Messages.resultRejected, Messages.reasonResources); return }
        let t = Session.InTransfer(id: transferId, eventId: id, requestId: requestId, length: length, startedAt: elapsed())
        t.granted = min(initialCredit, length)
        s.incoming[transferId] = t
        if let fragment { append(s, t, fragment) } else { grant(s, t) }
    }

    private func data(_ s: Session, _ transferId: Int64, _ fragment: [UInt8]) throws {
        guard let t = s.incoming[transferId] else { try require(s.closedIncoming.contains(transferId), "DATA for unknown transfer"); return }
        append(s, t, fragment)
    }

    private func append(_ s: Session, _ t: Session.InTransfer, _ fragment: [UInt8]) {
        t.lastActivity = elapsed()
        if t.buffer.count + fragment.count > t.granted || t.buffer.count + fragment.count > t.length {
            s.incoming[t.id] = nil; closeIncoming(s, t.id)
            s.requests[t.requestId]?.remove(t.eventId)
            _ = send(s, .result(target: .transfer(side: s.peerSide, transferId: t.id), code: Messages.resultRejected, reason: Messages.reasonInvalid))
            return
        }
        t.buffer += fragment
        if t.buffer.count == t.length { finish(s, t) } else { grant(s, t) }
    }

    /// Mesh §5.9: a whole-Bundle grant; the engine bounds the size and the assembly buffer is the only resource.
    private func grant(_ s: Session, _ t: Session.InTransfer) {
        if t.granted >= t.length { return }
        t.granted = t.length
        _ = send(s, .credit(transferId: t.id, limit: t.length))
    }

    private func finish(_ s: Session, _ t: Session.InTransfer) {
        s.incoming[t.id] = nil; closeIncoming(s, t.id)
        s.requests[t.requestId]?.remove(t.eventId)
        let target = Msg.Target.transfer(side: s.peerSide, transferId: t.id)
        let entry: StoredBundle?
        do { entry = try engine.accept(t.buffer, nowMillis: now(), elapsedMillis: elapsed()) }
        catch let e as BlockedAuthor {
            refused.append(e.id)
            if refused.count > Self.maxRefused { refused.removeFirst() }
            log("refused blocked \(e.id.prefix(8))")
            _ = send(s, .result(target: target, code: Messages.resultRejected, reason: Messages.reasonPolicy))
            return
        } catch {
            let why = "\(error)"
            let reason = why.contains("size") || why.contains("limit") ? Messages.reasonSize
                : why.contains("expired") || why.contains("deadline") ? Messages.reasonExpired
                : why.contains("capacity") || why.contains("rate") ? Messages.reasonResources : Messages.reasonInvalid
            log("rejected bundle from \(s.label): \(why)")
            _ = send(s, .result(target: target, code: Messages.resultRejected, reason: reason))
            return
        }
        guard let entry else {
            s.peerHas.insert(t.eventId)
            _ = send(s, .result(target: target, code: Messages.resultAlreadyHeld, reason: Messages.reasonNone))
            return
        }
        guard entry.event.id == t.eventId else {
            log("bundle from \(s.label) carries a different event than announced")
            drop(s); return
        }
        do { try accepted(entry) } catch { log("persist failed: \(error)"); drop(s); return }
        s.peerHas.insert(t.eventId)
        _ = send(s, .result(target: target, code: Messages.resultStored, reason: Messages.reasonNone))
        note("署名検証・保存 \(entry.event.id.prefix(8)) / 観測 hop \(entry.hop)")
        broadcastInventory()
    }

    private func closeIncoming(_ s: Session, _ id: Int64) {
        s.closedIncoming.append(id)
        if s.closedIncoming.count > 64 { s.closedIncoming.removeFirst() }
    }

    private func send(_ s: Session, _ m: Msg) -> Bool {
        guard let bytes = try? Messages.encode(m) else { return false }
        let limit = s.peer?.maxMessageBytes ?? Messages.helloMaxBytes
        if bytes.count > limit { log("message of \(bytes.count)B exceeds peer limit \(limit)"); return false }
        return s.link.send(s.handle, bytes)
    }
    private func drop(_ s: Session) { sessions.removeAll { $0 === s }; s.link.disconnect(s.handle) }
}

extension Array {
    func chunked(_ size: Int) -> [[Element]] {
        stride(from: 0, to: count, by: size).map { Array(self[$0..<Swift.min($0 + size, count)]) }
    }
}
