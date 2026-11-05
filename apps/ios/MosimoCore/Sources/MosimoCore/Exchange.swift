import Foundation

/// Link callbacks may arrive on any thread; the coordinator re-serializes them onto its own queue.
public protocol LinkListener: AnyObject {
    func onConnected(_ link: Link, _ peer: String)
    func onFrame(_ link: Link, _ peer: String, _ frame: [UInt8])
    func onDisconnected(_ link: Link, _ peer: String)
}

/// One transport (BLE GATT, LAN TCP). It discovers and connects on its own and moves whole frames;
/// store-carry-forward stays in the coordinator and MeshEngine. Peer IDs are link-local connection handles.
public protocol Link: AnyObject {
    var name: String { get }
    /// [node] is this process's 16-hex node ID, used only to break ties so two nodes open one connection.
    func start(node: String, listener: LinkListener)
    func stop()
    /// Queues one whole frame of at most [Frames.maxFrame] bytes; false when the peer is gone or backlogged.
    func send(_ peer: String, _ frame: [UInt8]) -> Bool
    func disconnect(_ peer: String)
    /// The peer's HELLO named [node]; a link may use it to avoid opening a second connection to the same node.
    func identified(_ peer: String, node: String)
}

/// One link connection's contact state.
public final class Session {
    public let link: Link
    public let handle: String
    let began: Int64
    public var peerNode: String?
    var peerMax = 0
    var peerDids = false
    var sent = [String: Int64]()
    var window: Int64 = 0
    var messages = 0
    init(link: Link, handle: String, began: Int64) { self.link = link; self.handle = handle; self.began = began }
}

/// Contact exchange over any [Link] (mndt §3): hello (capabilities) → inventory → want → bundle → ack.
/// Peers are auto-accepted: trust comes from the Nostr signature check in [MeshEngine] plus resource caps.
/// Not thread-safe; call only from the coordinator's queue.
public final class Exchange {
    public static let maxSessions = 16, maxRefused = 1024
    private let engine: MeshEngine
    private let node: String
    private let now: () -> Int64, elapsed: () -> Int64
    private let note: (String) -> Void
    private let log: (String) -> Void
    // Author-DID predicate; only skips wants. MeshEngine.admit is the authoritative check after signature verification.
    private let blocked: (String) -> Bool
    // Optional test isolation only: node IDs are ephemeral and are NOT peer authentication.
    private let allowedPeerNode: String?
    // Must durably save before returning.
    private let accepted: (StoredBundle) throws -> Void
    public private(set) var sessions = [Session]()
    // ponytail: in-memory FIFO; a restarted app re-wants a refused bundle once.
    private var refused = [String]()

    public init(engine: MeshEngine, node: String, now: @escaping () -> Int64, elapsed: @escaping () -> Int64, note: @escaping (String) -> Void = { _ in },
                log: @escaping (String) -> Void = { _ in }, allowedPeerNode: String? = nil, blocked: @escaping (String) -> Bool = { _ in false },
                accepted: @escaping (StoredBundle) throws -> Void = { _ in }) {
        self.engine = engine; self.node = node; self.now = now; self.elapsed = elapsed; self.note = note; self.log = log
        self.allowedPeerNode = allowedPeerNode; self.blocked = blocked; self.accepted = accepted
    }

    private func session(_ link: Link, _ handle: String) -> Session? { sessions.first { $0.link === link && $0.handle == handle } }

    public func connected(_ link: Link, _ handle: String) {
        if sessions.count >= Self.maxSessions { link.disconnect(handle); return }
        let s = Session(link: link, handle: handle, began: elapsed())
        sessions.append(s)
        if let hello = try? Wire.encode(Control(type: "hello", node: node, max: MeshPolicy.maxBundleBytes)) { _ = send(s, hello) }
    }

    public func disconnected(_ link: Link, _ handle: String) { sessions.removeAll { $0.link === link && $0.handle == handle } }

    public func frame(_ link: Link, _ handle: String, _ bytes: [UInt8]) {
        guard let s = session(link, handle) else { return }
        if elapsed() - s.window >= 60_000 { s.window = elapsed(); s.messages = 0 }
        s.messages += 1
        if s.messages > 512 { drop(s); return }
        do {
            try require(!bytes.isEmpty)
            switch bytes[0] {
            case UInt8(ascii: "{"): try control(s, try Wire.decode(bytes))
            case 0x9f: try bundle(s, bytes)
            default: throw CoreError("Unknown frame")
            }
        } catch {
            log("reject frame from \(s.handle): \(error)")
            note("不正・制限超過フレームを拒否")
            if s.peerNode == nil { drop(s) }
        }
    }

    public func broadcastInventory() { for s in sessions where s.peerNode != nil { inventory(s) } }

    /// Drops connections that never completed HELLO, so a silent peer cannot hold a slot.
    public func dropStale() { for s in sessions where s.peerNode == nil && elapsed() - s.began > 60_000 { drop(s) } }

    public func clear() { sessions.removeAll() }

    /// After an unblock; still-blocked authors are simply refused once more.
    public func forgetRefused() { refused.removeAll() }

    private func control(_ s: Session, _ c: Control) throws {
        if c.type == "hello" {
            try require(s.peerNode == nil, "Duplicate HELLO")
            if c.node == node || allowedPeerNode.map({ c.node != $0 }) == true { drop(s); return }
            s.peerNode = c.node; s.peerMax = c.max; s.peerDids = c.didCap
            s.link.identified(s.handle, node: c.node)
            log("hello from \(c.node) on \(s.link.name):\(s.handle)")
            inventory(s)
            return
        }
        try require(s.peerNode != nil, "HELLO required")
        switch c.type {
        case "inventory":
            let owned = Set(engine.entries().map(\.event.id))
            let missing = c.ids.enumerated().filter { i, id in
                let did = i < c.dids.count ? c.dids[i] : ""
                return !owned.contains(id) && !refused.contains(id) && !(did.isEmpty ? false : blocked(did))
            }.map(\.element)
            log("inventory \(c.ids.count) from \(s.peerNode!), want \(missing.count)")
            if !missing.isEmpty { _ = send(s, try Wire.encode(Control(type: "want", ids: missing))) }
            for id in c.ids { s.sent[id] = nil }
        case "want":
            let wanted = Set(c.ids)
            log("want \(c.ids.count) from \(s.peerNode!)")
            let eligible = Set(engine.inventory(nowMillis: now(), elapsedMillis: elapsed()))
            let entries = engine.entries().filter { eligible.contains($0.event.id) && wanted.contains($0.event.id) }.sorted {
                let a = AtRepository.deliveryExpiresAt($0.event), b = AtRepository.deliveryExpiresAt($1.event)
                return a != b ? (a.map { x in b.map { x < $0 } ?? false } ?? true) : $0.bytes.count < $1.bytes.count
            }
            for entry in entries {
                let id = entry.event.id
                if elapsed() - (s.sent[id] ?? -30_000) < 10_000 { continue }
                guard let bundle = engine.forward(id, nowMillis: now(), elapsedMillis: elapsed()) else { continue }
                let ok = bundle.count <= s.peerMax && send(s, bundle)
                log("bundle \(id.prefix(8)) \(bundle.count)B to \(s.peerNode!): \(ok ? "queued" : "NOT sent")")
                if ok { s.sent[id] = elapsed() }
            }
        case "ack": s.sent[c.ids[0]] = nil
        default: break
        }
    }

    private func bundle(_ s: Session, _ bytes: [UInt8]) throws {
        try require(s.peerNode != nil, "HELLO required")
        let entry: StoredBundle?
        do { entry = try engine.accept(bytes, nowMillis: now(), elapsedMillis: elapsed()) } catch let e as BlockedAuthor {
            refused.append(e.id)
            if refused.count > Self.maxRefused { refused.removeFirst() }
            log("refused blocked \(e.id.prefix(8))")
            return
        }
        log("bundle \(bytes.count)B from \(s.peerNode!): \(entry.map { "accepted \($0.event.id)" } ?? "duplicate")")
        guard let entry else { inventory(s); return }
        try accepted(entry)
        _ = send(s, try Wire.encode(Control(type: "ack", ids: [entry.event.id])))
        note("署名検証・保存 \(entry.event.id.prefix(8)) / 観測 hop \(entry.hop)")
        broadcastInventory()
    }

    private func inventory(_ s: Session) {
        let allowed = Dictionary(engine.entries().map { ($0.event.id, $0) }, uniquingKeysWith: { a, _ in a })
        let ids = Array(engine.inventory(nowMillis: now(), elapsedMillis: elapsed()).filter { allowed[$0] != nil }.suffix(128))
        // Legacy peers (ESP32) disconnect on unknown inventory fields, so dids go only to peers advertising the capability.
        let dids = s.peerDids ? ids.map { allowed[$0]?.did ?? "" } : []
        if let frame = try? Wire.encode(Control(type: "inventory", ids: ids, dids: dids)) { _ = send(s, frame) }
    }

    private func send(_ s: Session, _ frame: [UInt8]) -> Bool { s.link.send(s.handle, frame) }
    private func drop(_ s: Session) { sessions.removeAll { $0 === s }; s.link.disconnect(s.handle) }
}
