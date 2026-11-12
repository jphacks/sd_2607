import Foundation
import Network
import os

/// One radio node per device (mesh §4.6). Every Mosimo app on a device shares one Bluetooth controller and one GATT
/// database: two GATT servers put two identical services in it and remote centrals only ever use the first, and two of
/// our centrals on one peer interleave their writes on its single ATT bearer. LAN works per app, but would put one node
/// per app on the network.
///
/// So the app holding the first free of [ports] on 127.0.0.1 runs [radios] (BLE, LAN) for the whole device; the others
/// connect to it there, exchange like any peer, and keep their radios off. A port whose listener does not answer with
/// HELLO belongs to some other app and is skipped. A holder on a fallback port keeps checking the earlier ones and moves
/// there or follows a holder found there, so every app ends up behind one holder. When it stops, a follower takes over.
/// State lives on [queue]; [elect] blocks on its own queue; listener calls hop to the main queue.
// ponytail: no peer auth on loopback (as LanLink); a local app speaking HELLO on the port keeps our radios off.
public final class LocalLink: Link {
    public let name = "local"
    /// Mesh §4.6, in order.
    public static let ports: [UInt16] = [29584, 26741, 23771]
    static let maxConns = 8, maxQueue = 64
    static let helloSeconds = 10.0, retrySeconds = 5.0, recheckSeconds = 60.0

    private final class Conn {
        let connection: NWConnection
        var reader = CborSequenceReader(limit: Exchange.messageLimit)
        var queued = 0
        var open = false
        init(_ c: NWConnection) { connection = c }
    }

    private enum Answer { case holder, otherApp, nobody }

    private let radios: [Link]
    private let ports: [UInt16]
    private let queue = DispatchQueue(label: "mosimo.local")
    private let election = DispatchQueue(label: "mosimo.local.elect")
    private let log = Logger(subsystem: "dev.jisaku.mosimo", category: "LocalLink")
    private func trace(_ s: String) {
        log.info("\(s, privacy: .public)")
        #if DEBUG
        print("[local] \(s)")
        #endif
    }
    private var gen = 0
    private var node = ""
    private weak var listener: LinkListener?
    private var server: NWListener?
    private var held = -1 // index in [ports] of [server]
    private var holder: String? // our connection to the holder, while we follow it or check its port
    private var holderOk = false // the holder said HELLO
    private var hello: DispatchSemaphore? // signalled when the checked port says HELLO or hangs up
    private var radiosOn = false
    private var conns = [String: Conn]()

    public init(radios: [Link], ports: [UInt16] = LocalLink.ports) { self.radios = radios; self.ports = ports }

    public func start(node: String, listener: LinkListener) {
        queue.async { [self] in
            stopLocked()
            self.node = node; self.listener = listener
            let g = gen
            election.async { [self] in elect(g) }
        }
    }

    /// On [election]. Not holding: follow a holder on any port, and take the first free port only if there is none, so
    /// a port another app frees cannot start a second holder. Holding: move to a free earlier port or follow its holder.
    private func elect(_ g: Int) {
        guard let current = queue.sync(execute: { g == gen && holder == nil ? held : nil }) else { return }
        var otherApps = Set<Int>()
        if current < 0 {
            for i in ports.indices {
                switch ask(g, i) {
                case nil, .holder: return
                case .otherApp: otherApps.insert(i)
                case .nobody: break
                }
            }
        }
        for i in 0..<(current >= 0 ? current : ports.count) where !otherApps.contains(i) {
            if let l = bind(g, i) { hold(g, i, l); return }
            switch ask(g, i) { case nil, .holder: return; default: break } // taken since the scan above
        }
        if current == 0 { return }
        if current < 0 {
            trace("every port is used by another app; running the radios here")
            queue.sync { if g == gen { setRadios(true) } }
        }
        later(g, Self.recheckSeconds) { [self] in elect(g) }
    }

    /// A listener on ports[i], or nil when the port is taken.
    private func bind(_ g: Int, _ i: Int) -> NWListener? {
        let params = NWParameters.tcp
        params.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: NWEndpoint.Port(rawValue: ports[i])!)
        guard let l = try? NWListener(using: params) else { return nil }
        let done = DispatchSemaphore(value: 0)
        var ready = false
        l.stateUpdateHandler = { s in
            switch s {
            case .ready: ready = true; done.signal()
            case .failed, .waiting, .cancelled: done.signal()
            default: break
            }
        }
        l.newConnectionHandler = { [weak self] c in _ = self?.adopt(g, c, follow: false, started: false) }
        l.start(queue: queue)
        if done.wait(timeout: .now() + 2) == .success, queue.sync(execute: { ready }) { return l }
        l.cancel()
        return nil
    }

    private func hold(_ g: Int, _ i: Int, _ l: NWListener) {
        let (ok, old) = queue.sync { () -> (Bool, NWListener?) in
            guard g == gen else { return (false, nil) }
            let old = server
            server = l; held = i; setRadios(true)
            return (true, old)
        }
        guard ok else { l.cancel(); return }
        old?.cancel() // its followers stay connected; newcomers find us on the earlier port
        trace("holding the radios for this device on port \(ports[i])")
        if i > 0 { later(g, Self.recheckSeconds) { [self] in elect(g) } }
    }

    /// .holder: a Mosimo holder said HELLO on ports[i] and we now follow it. Nil: stopped.
    private func ask(_ g: Int, _ i: Int) -> Answer? {
        let c = NWConnection(host: .ipv4(.loopback), port: NWEndpoint.Port(rawValue: ports[i])!, using: .tcp)
        let connected = DispatchSemaphore(value: 0)
        var ready = false
        c.stateUpdateHandler = { s in
            switch s {
            case .ready: ready = true; connected.signal()
            case .failed, .waiting, .cancelled: connected.signal() // refused: .waiting(ECONNREFUSED)
            default: break
            }
        }
        c.start(queue: queue)
        guard connected.wait(timeout: .now() + 1) == .success, queue.sync(execute: { ready }) else { c.cancel(); return .nobody }
        let said = DispatchSemaphore(value: 0)
        guard let h = queue.sync(execute: { hello = said; return adopt(g, c, follow: true, started: true) }) else { return nil }
        _ = said.wait(timeout: .now() + Self.helloSeconds)
        guard case let (ok, drop)? = queue.sync(execute: { () -> (Bool, [Conn])? in
            guard g == gen else { return nil }
            if holder == h && holderOk {
                server?.cancel(); server = nil; held = -1; setRadios(false)
                return (true, conns.filter { $0.key != h }.map(\.value)) // our followers: let them find the holder
            }
            if holder == h { holder = nil } // its hang-up below is not losing a holder
            return (false, conns[h].map { [$0] } ?? [])
        }) else { return nil }
        for d in drop { d.connection.cancel() }
        trace(ok ? "following the holder on port \(ports[i])" : "port \(ports[i]) is used by another app")
        return ok ? .holder : .otherApp
    }

    /// On [queue].
    private func adopt(_ g: Int, _ c: NWConnection, follow: Bool, started: Bool) -> String? {
        guard g == gen, listener != nil, conns.count < Self.maxConns else { c.cancel(); return nil }
        let handle = "local:\(UUID().uuidString.prefix(8))"
        let conn = Conn(c)
        conns[handle] = conn
        if follow { holder = handle; holderOk = false }
        c.stateUpdateHandler = { [weak self] s in
            switch s {
            case .ready: self?.opened(handle, conn)
            case .failed, .cancelled: self?.close(handle, conn)
            default: break
            }
        }
        if started { opened(handle, conn) } else { c.start(queue: queue) }
        return handle
    }

    private func opened(_ handle: String, _ conn: Conn) {
        guard conns[handle] === conn, !conn.open else { return }
        conn.open = true
        emit { [weak self] in if let self { $0.onConnected(self, handle, node: nil) } } // no advertised node: lab peer isolation never applies
        receive(handle, conn)
    }

    private func receive(_ handle: String, _ conn: Conn) {
        conn.connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, done, error in
            guard let self, self.conns[handle] === conn else { return }
            if let data, !data.isEmpty {
                do {
                    for m in try conn.reader.feed(Array(data)) { self.emit { [weak self] in if let self { $0.onMessage(self, handle, m) } } }
                } catch { conn.connection.cancel(); return }
            }
            if done || error != nil { conn.connection.cancel() } else { self.receive(handle, conn) }
        }
    }

    private func close(_ handle: String, _ conn: Conn) {
        guard conns[handle] === conn else { return }
        conns[handle] = nil
        // Losing the holder we followed: it freed its port, so elect again. A checked port's hang-up is ask()'s.
        if holder == handle {
            holder = nil; hello?.signal()
            if holderOk { holderOk = false; let g = gen; later(g, Self.retrySeconds) { [self] in elect(g) } }
        }
        if conn.open { emit { [weak self] in if let self { $0.onDisconnected(self, handle) } } }
    }

    public func hello(_ peer: String) {
        queue.async { [self] in if peer == holder { holderOk = true; hello?.signal() } }
    }

    /// On [queue].
    private func setRadios(_ on: Bool) {
        guard radiosOn != on else { return }
        radiosOn = on
        trace(on ? "radios on" : "radios off: the holder relays for this device")
        for r in radios { if !on { r.stop() } else if let l = listener { r.start(node: node, listener: l) } }
    }

    private func later(_ g: Int, _ seconds: Double, _ block: @escaping () -> Void) {
        election.asyncAfter(deadline: .now() + seconds) { [weak self] in
            guard let self, self.queue.sync(execute: { self.gen }) == g else { return }
            block()
        }
    }

    private func emit(_ f: @escaping (LinkListener) -> Void) {
        guard let l = listener else { return }
        DispatchQueue.main.async { f(l) }
    }

    public func send(_ peer: String, _ message: [UInt8]) -> Bool {
        guard !message.isEmpty, message.count <= Messages.maxMessageBytes else { return false }
        return queue.sync {
            guard let conn = conns[peer], conn.open, conn.queued < Self.maxQueue else { return false }
            conn.queued += 1
            conn.connection.send(content: Data(message), completion: .contentProcessed { [weak self] error in
                guard let self else { return }
                conn.queued -= 1
                if error != nil { conn.connection.cancel() }
                else if conn.queued == 0 { self.emit { [weak self] in if let self { $0.onDrained(self, peer) } } }
            })
            return true
        }
    }

    public func queued(_ peer: String) -> Int { queue.sync { conns[peer]?.queued ?? 0 } }
    public func disconnect(_ peer: String) { queue.async { [self] in conns[peer]?.connection.cancel() } }
    public func synced(_ peer: String) {}

    public func stop() { queue.async { [self] in stopLocked() } }

    private func stopLocked() {
        gen += 1
        server?.cancel(); server = nil; held = -1 // first, so a follower can bind once its connection drops
        setRadios(false)
        hello?.signal(); hello = nil
        holder = nil; holderOk = false
        for (h, c) in conns { c.connection.cancel(); close(h, c) }
        listener = nil
    }
}
