import Foundation
import MosimoCore
import Network
import os

/// Only the lexicographically smaller node dials, so two nodes open one connection (mesh §4.2).
func shouldDial(_ myNode: String, _ peerNode: String) -> Bool { myNode < peerNode }

/// LAN link (mesh §4.2): DNS-SD `_mosimo._tcp` with the node id as instance name, plain TCP on an ephemeral port,
/// carrying the same CBOR sequence of exchange messages as GATT (mesh §7). Interoperates with Android's NSD/ServerSocket LanLink.
// ponytail: no TLS or peer auth; payloads are public signed events. Networks that block mDNS (client isolation) are unreachable.
final class LanLink: Link {
    let name = "lan"
    /// Mesh §4.2: DNS-SD service type; the instance name is the node ID.
    static let serviceType = "_mosimo._tcp"
    static let maxConns = 8, maxQueue = 64

    private final class Conn {
        let connection: NWConnection
        var reader = CborSequenceReader(limit: Exchange.messageLimit)
        var queued = 0
        var open = false
        init(_ c: NWConnection) { connection = c }
    }

    private let queue = DispatchQueue(label: "mosimo.lan")
    private let log = Logger(subsystem: "dev.jisaku.mosimo", category: "LanLink")
    private func trace(_ s: String) {
        log.info("\(s, privacy: .public)")
        #if DEBUG
        print("[lan] \(s)")
        #endif
    }
    private var listener: NWListener?
    private var browser: NWBrowser?
    private weak var linkListener: LinkListener?
    private var node = ""
    private var gen = 0
    private var conns = [String: Conn]()
    private var outbound = [String: String]() // peer handle -> node we dialed
    private var dialing = Set<String>()
    private var found = [String: NWEndpoint]() // node -> endpoint seen on the LAN
    private var retry: DispatchSourceTimer?

    func start(node: String, listener l: LinkListener) {
        queue.async { [self] in
            stopLocked()
            gen += 1
            let g = gen
            self.node = node; linkListener = l
            do {
                let params = NWParameters.tcp
                params.includePeerToPeer = false
                let listener = try NWListener(using: params)
                listener.service = NWListener.Service(name: node, type: Self.serviceType)
                listener.newConnectionHandler = { [weak self] c in self?.queue.async { self?.adopt(g, c, nil) } }
                listener.stateUpdateHandler = { [weak self] s in
                    self?.trace("listener \(String(describing: s))")
                    if case .failed = s { self?.queue.async { if self?.gen == g { self?.listener = nil } } }
                }
                listener.start(queue: queue)
                self.listener = listener
            } catch { trace("listen failed: \(error.localizedDescription)") }
            let browser = NWBrowser(for: .bonjour(type: Self.serviceType, domain: nil), using: .tcp)
            browser.browseResultsChangedHandler = { [weak self] results, _ in
                guard let self, self.gen == g else { return }
                self.found = [:]
                for r in results {
                    if case .service(let name, _, _, _) = r.endpoint, name != self.node, name.count == 16,
                       name.allSatisfy({ $0.isHexDigit && !$0.isUppercase }) { self.found[name] = r.endpoint }
                }
                self.tick(g)
            }
            browser.stateUpdateHandler = { [weak self] s in self?.trace("browser \(String(describing: s))") }
            browser.start(queue: queue)
            self.browser = browser
            // Discovery is event driven; the timer only retries failed dials (Android retries every 10 s).
            let t = DispatchSource.makeTimerSource(queue: queue)
            t.schedule(deadline: .now() + 10, repeating: 10)
            t.setEventHandler { [weak self] in self?.tick(g) }
            t.resume()
            retry = t
        }
    }

    private func tick(_ g: Int) {
        guard g == gen else { return }
        for (peerNode, endpoint) in found where shouldDial(node, peerNode) && !dialing.contains(peerNode) && !outbound.values.contains(peerNode) {
            guard conns.count + dialing.count < Self.maxConns else { return }
            dialing.insert(peerNode)
            let c = NWConnection(to: endpoint, using: .tcp)
            adopt(g, c, peerNode)
        }
    }

    private func adopt(_ g: Int, _ c: NWConnection, _ peerNode: String?) {
        guard g == gen, linkListener != nil, conns.count < Self.maxConns else { c.cancel(); if let n = peerNode { dialing.remove(n) }; return }
        let handle = "lan:\(UUID().uuidString.prefix(8))"
        let conn = Conn(c)
        c.stateUpdateHandler = { [weak self] s in
            guard let self else { return }
            switch s {
            case .ready:
                if let n = peerNode { self.dialing.remove(n); self.outbound[handle] = n }
                guard g == self.gen, self.conns[handle] === conn else { c.cancel(); return }
                conn.open = true
                if case .hostPort(let host, let port) = c.currentPath?.remoteEndpoint { self.trace("connected \(handle) \(String(describing: host)):\(port.rawValue)") }
                self.emit { [weak self] in if let self { $0.onConnected(self, handle, node: peerNode) } }
                self.receive(handle, conn)
            case .failed, .cancelled:
                if let n = peerNode { self.dialing.remove(n) }
                self.close(handle, conn)
            case .waiting:
                // Dials that cannot reach the peer stay "waiting" forever; give the slot back.
                if peerNode != nil { c.cancel() }
            default: break
            }
        }
        conns[handle] = conn
        c.start(queue: queue)
        if peerNode != nil {
            queue.asyncAfter(deadline: .now() + 5) { if !conn.open { c.cancel() } }
        }
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
        outbound[handle] = nil
        if conn.open { emit { [weak self] in if let self { $0.onDisconnected(self, handle) } } }
    }

    private func emit(_ f: @escaping (LinkListener) -> Void) {
        guard let l = linkListener else { return }
        DispatchQueue.main.async { f(l) }
    }

    func send(_ peer: String, _ message: [UInt8]) -> Bool {
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

    func queued(_ peer: String) -> Int { queue.sync { conns[peer]?.queued ?? 0 } }
    /// Services currently announced on the LAN (the browser reports their loss).
    func nearby(within seconds: Double) -> Set<String> { queue.sync { Set(found.keys) } }
    func disconnect(_ peer: String) { queue.async { [self] in conns[peer]?.connection.cancel() } }
    func synced(_ peer: String) {}

    func stop() { queue.async { [self] in stopLocked() } }

    private func stopLocked() {
        gen += 1
        retry?.cancel(); retry = nil
        listener?.cancel(); listener = nil
        browser?.cancel(); browser = nil
        dialing.removeAll(); found.removeAll()
        for (h, c) in conns { c.connection.cancel(); close(h, c) }
        linkListener = nil
    }
}
