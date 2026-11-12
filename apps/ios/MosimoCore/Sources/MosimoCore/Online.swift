import Foundation

public enum RelayReply: Equatable {
    /// NIP-01 `OK`; message is the relay's machine-readable prefix and reason ("blocked: …", "invalid: …").
    case ok(id: String, accepted: Bool, message: String)
    case event(NostrEvent)
    case eose, closed, full
}

/// Serialized by the coordinator. Display cache only: never a MeshEngine ingress or disk store.
public final class RelaySubscription {
    /// PDS §4.3: the discovery topic. Local limits below are not a shared relay profile.
    public static let tag = AtRepository.namespace
    public static let initialLimit = 32
    public static let maxEvents = PublicDownload.maxEvents
    public static let maxBytes = 2 * 1024 * 1024
    public static let maxFrameBytes = 262144
    public static let maxFramesPerMinute = 256
    public static let perAuthorPerMinute = 12
    private var connection: Int64?
    public private(set) var subscriptionId: String?
    public private(set) var eose = false
    private var order = [String]()
    private var retained = [String: NostrEvent]()
    private var retainedBytes = 0
    private var full = false
    private var frameTimes = [Int64]()
    private var authorTimes = [String: [Int64]]()

    public init() {}
    public func events() -> [NostrEvent] { order.compactMap { retained[$0] } }
    public var atCapacity: Bool { full }

    public func open(_ generation: Int64) -> String? {
        connection = generation
        eose = false
        if full { return nil }
        let id = "mosimo-\(generation)"
        subscriptionId = id
        return Json.array([.string("REQ"), .string(id), .object([("kinds", .array([.int(1)])), ("#t", .array([.string(Self.tag)])),
                                                                ("limit", .int(Int64(Self.initialLimit)))])]).serialized
    }

    public func close() -> String? {
        let id = subscriptionId
        subscriptionId = nil
        eose = false
        return id.map { Json.array([.string("CLOSE"), .string($0)]).serialized }
    }

    public func disconnect() { _ = close(); connection = nil }
    public func clear() {
        disconnect()
        order.removeAll(); retained.removeAll(); retainedBytes = 0; full = false
        // Rate windows survive reconnect, endpoint changes and stop/start.
    }

    public func receive(_ generation: Int64, _ text: String, _ elapsed: Int64, meshIds: Set<String> = []) throws -> RelayReply? {
        if connection != generation { return nil }
        try require(text.utf8.count <= Self.maxFrameBytes, "Relay frame size limit")
        let frame = try? Json.parse(text, maxDepth: 32, maxBytes: Self.maxFrameBytes)
        let a = frame?.array ?? []
        // OKs answer our own one-at-a-time EVENTs, so a backlog flush must not spend the receive window.
        if a.count == 4, a[0].string == "OK", let id = a[1].string, let accepted = a[2].bool, let message = a[3].string { return .ok(id: id, accepted: accepted, message: String(message.prefix(160))) }
        // A rotation's re-sent backlog is dropped unverified by id, so reconnects cannot spend the window either.
        if a.count == 3, a[0].string == "EVENT", let id = a[2]["id"]?.string, retained[id] != nil || meshIds.contains(id) { return nil }
        frameTimes.removeAll { elapsed - $0 >= 60_000 || elapsed < $0 }
        try require(frameTimes.count < Self.maxFramesPerMinute, "Relay receive rate limit")
        frameTimes.append(elapsed)
        guard a.count >= 2, let verb = a[0].string, let sub = a[1].string, let subscriptionId, sub == subscriptionId else { return nil }
        switch verb {
        case "EOSE": if a.count == 2 { eose = true; return .eose }; return nil
        case "CLOSED": if a.count == 3, a[2].string != nil { _ = close(); return .closed }; return nil
        case "EVENT":
            guard a.count == 3, a[2].object != nil else { return nil }
            // Reuse the dedicated NIP-01 ID + BIP-340 verifier; no authoring or rebundling.
            guard let event = try? NostrEvent.fromJson(a[2]), event.kind == 1,
                  event.tags.contains(where: { $0.first == "t" && $0.count > 1 && $0[1] == Self.tag }) else { return nil }
            if retained[event.id] != nil || meshIds.contains(event.id) { return nil }
            for (k, v) in authorTimes { authorTimes[k] = v.filter { !(elapsed - $0 >= 60_000 || elapsed < $0) } }
            authorTimes = authorTimes.filter { !$0.value.isEmpty }
            // Per-author pacing is for live events; the bounded backlog before EOSE is history.
            if eose, (authorTimes[event.pubkey]?.count ?? 0) >= Self.perAuthorPerMinute { return nil }
            let size = (try? event.toJson())?.utf8.count ?? 0
            if full || retained.count >= Self.maxEvents || size > Self.maxBytes - retainedBytes { full = true; return .full }
            if eose { authorTimes[event.pubkey, default: []].append(elapsed) }
            retained[event.id] = event; order.append(event.id)
            retainedBytes += size
            full = retained.count == Self.maxEvents || retainedBytes == Self.maxBytes
            return .event(event)
        default: return nil
        }
    }
}

/// A URLSession that never follows redirects (snapshot and PLC reads must stay on the configured host).
public final class NoRedirect: NSObject, URLSessionTaskDelegate {
    public static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 20; c.timeoutIntervalForResource = 25
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: c, delegate: NoRedirect(), delegateQueue: nil)
    }()
    public func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                           newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

struct Timeout: Error {}
func withTimeout<T: Sendable>(_ seconds: Double, _ body: @escaping @Sendable () async throws -> T) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask { try await body() }
        group.addTask { try await Task.sleep(nanoseconds: UInt64(seconds * 1e9)); throw Timeout() }
        defer { group.cancelAll() }
        return try await group.next()!
    }
}

public enum PublicDownload {
    public static let limit = 32
    /// Display-only pages after the first: plain REQ walked back by `until`, never engine ingress or disk.
    public static let pageLimit = 100
    public static let maxEvents = 500
    // ponytail: fixed 8 MiB in-memory display cap; size it from device memory if 500 events ever stop fitting.
    public static let maxTotalBytes = 8 * 1024 * 1024
    public static let pdsRelay = "wss://pds.oriom.dev"

    /// Display pages download on any clock; only authorized ingress gets one, and PublicIngress fails closed on nil.
    public static func ingressClock(_ confidence: ClockConfidence, _ nowMillis: Int64) -> Instant? {
        confidence == .trusted ? Instant(epochMillis: nowMillis) : nil
    }

    public static func request(_ id: String, priority: Bool, limit: Int = limit, until: Int64? = nil) -> String {
        var filter: [(String, Json)] = [("kinds", .array([.int(1)])), ("#t", .array([.string(AtRepository.namespace)])), ("limit", .int(Int64(limit)))]
        if let until { filter.append(("until", .int(until))) }
        let msg: Json = priority ? .array([.string("MOSIMO-REQ"), .string(id), .object([("order", .string("priority-v1")), ("filter", .object(filter))])])
            : .array([.string("REQ"), .string(id), .object(filter)])
        return msg.serialized
    }

    /// attest.revocation.list §2: default full response bound.
    public static let maxRevocationListBytes = 1_048_576
    /// attest.revocation.list §1: `https://D/.well-known/mosimo/attest/revocations` as canonical CBOR, no redirects.
    public static func revocationList(domain: String) async throws -> [UInt8] {
        let pattern = try! NSRegularExpression(pattern: "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")
        try require(matches(pattern, domain) && domain.utf8.count <= 253, "Invalid issuer domain")
        guard let url = URL(string: "https://\(domain)/.well-known/mosimo/attest/revocations") else { throw CoreError("Invalid issuer domain") }
        var req = URLRequest(url: url)
        req.setValue("application/cbor", forHTTPHeaderField: "Accept")
        let (data, response) = try await NoRedirect.session.data(for: req)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode), http.url?.scheme == "https" else { throw CoreError("Revocation list HTTPS failure") }
        let type = http.value(forHTTPHeaderField: "Content-Type")?.split(separator: ";").first?.trimmingCharacters(in: .whitespaces).lowercased()
        try require(type == "application/cbor", "Revocation list must be application/cbor")
        try require(data.count <= maxRevocationListBytes, "Revocation list size limit")
        return Array(data)
    }

    public struct Batch: Sendable { public let events: [NostrEvent]; public let fallback: Bool }
    /// [events] excludes the first batch; [failure] keeps the pages fetched before it.
    public struct Pages: Sendable { public let events: [NostrEvent]; public let failure: String? }

    /// Each attempt gets a fresh socket: old relays may disconnect on an unknown verb.
    public static func fetch(token: Int64, relay: String = pdsRelay, timeout: Double = 15) async throws -> Batch {
        for priority in [true, false] {
            if let events = try? await attempt(relay, "public-\(token)-\(priority ? "p" : "r")", priority: priority, timeout: timeout) {
                return Batch(events: events, fallback: !priority)
            }
        }
        throw CoreError("Ordinary REQ failed/timed out; not an empty success")
    }

    /// Older pages after a full [first] batch, until a short page, [maxEvents] or [maxTotalBytes]; no age cutoff.
    /// priority-v1 has no time order, so its batch never seeds the cursor; `until` is inclusive, hence the id dedupe.
    public static func more(token: Int64, first: Batch, relay: String = pdsRelay, timeout: Double = 15) async -> Pages {
        if first.events.count < limit { return Pages(events: [], failure: nil) }
        var seen = Set(first.events.map(\.id))
        var bytes = first.events.reduce(0) { $0 + ((try? $1.toJson())?.utf8.count ?? 0) }
        var extra = [NostrEvent]()
        var until: Int64? = first.fallback ? first.events.map(\.createdAt).min() : nil
        var page = 0
        while seen.count < maxEvents {
            page += 1
            let events: [NostrEvent]
            do { events = try await attempt(relay, "public-\(token)-m\(page)", priority: false, timeout: timeout, limit: pageLimit, until: until) }
            catch { return Pages(events: extra, failure: "page \(page) failed/timed out") }
            var added = 0
            for event in events where !seen.contains(event.id) {
                let size = (try? event.toJson())?.utf8.count ?? 0
                if seen.count >= maxEvents || bytes + size > maxTotalBytes { return Pages(events: extra, failure: nil) }
                seen.insert(event.id); extra.append(event); bytes += size; added += 1
            }
            // No progress: a full page sharing one created_at cannot be walked past with an inclusive cursor.
            guard let oldest = events.map(\.createdAt).min(), events.count >= pageLimit, added > 0 else { break }
            until = oldest
        }
        return Pages(events: extra, failure: nil)
    }

    static func attempt(_ relay: String, _ id: String, priority: Bool, timeout: Double, limit: Int = limit, until: Int64? = nil) async throws -> [NostrEvent] {
        guard let url = URL(string: relay) else { throw CoreError("Invalid relay URL") }
        let task = URLSession.shared.webSocketTask(with: url)
        task.maximumMessageSize = RelaySubscription.maxFrameBytes
        task.resume()
        defer { task.cancel(with: .goingAway, reason: nil) }
        let request = Self.request(id, priority: priority, limit: limit, until: until)
        try await withTimeout(15) { try await task.send(.string(request)) }
        return try await withTimeout(timeout) {
            var events = [String: NostrEvent](), order = [String](), total = 0, frames = 0
            while true {
                guard case .string(let text) = try await task.receive() else { throw CoreError("Binary relay frame unsupported") }
                frames += 1
                try require(frames <= RelaySubscription.maxFramesPerMinute, "Frame limit")
                guard let n = try? Json.parse(text, maxDepth: 32, maxBytes: RelaySubscription.maxFrameBytes), let a = n.array, a.count >= 2, a[1].string == id else { continue }
                switch a[0].string {
                case "EOSE":
                    try require(a.count == 2)
                    try? await task.send(.string(Json.array([.string("CLOSE"), .string(id)]).serialized))
                    return order.compactMap { events[$0] }
                case "CLOSED": throw CoreError("CLOSED")
                case "EVENT":
                    try require(a.count == 3)
                    let event = try NostrEvent.fromJson(a[2])
                    try require(event.kind == 1 && event.tags.contains(AtRepository.topicTag))
                    if events[event.id] == nil {
                        let size = try event.toJson().utf8.count
                        try require(events.count < limit && size <= RelaySubscription.maxBytes - total, "Public request capacity")
                        events[event.id] = event; order.append(event.id); total += size
                    }
                default: continue
                }
            }
        }
    }
}

public enum PlcCurrentState: Sendable { case unknown, checked, changed, tombstoned }
public struct PlcObservation: Sendable {
    public var genesisConfirmed = false
    public var current = PlcCurrentState.unknown
    public var checkedAtMillis: Int64?
    public init(genesisConfirmed: Bool = false, current: PlcCurrentState = .unknown, checkedAtMillis: Int64? = nil) {
        self.genesisConfirmed = genesisConfirmed; self.current = current; self.checkedAtMillis = checkedAtMillis
    }
}

/// Observes the configured Directory, not an independently audited history or PDS acceptance.
/// /log excludes nullified operations; /data is current PLC state, not a DID Document.
/// A positive observation is time-scoped and must never be restored as current authority.
public enum PlcDirectory {
    static let stateFields = ["rotationKeys", "verificationMethods", "alsoKnownAs", "services"]

    public static func check(directory: URL, did: String, genesisJson: String, previouslyConfirmed: Bool, allowRegistration: Bool = false) async -> PlcObservation {
        var confirmed = previouslyConfirmed
        do {
            try require(matches(AtRepository.didPattern, did))
            let expected = try parse(Array(genesisJson.utf8))
            try operation(expected, genesis: true)
            let target = directory.appendingPathComponent(did)
            let logUrl = target.appendingPathComponent("log")
            var response = try await get(logUrl)
            // Never POST over an existing/malformed registration or after historical confirmation.
            if response.0 == 404 && !previouslyConfirmed && allowRegistration {
                // Lost/duplicate submission responses do not decide registration or authority.
                var req = URLRequest(url: target)
                req.httpMethod = "POST"
                req.setValue("application/json", forHTTPHeaderField: "Content-Type")
                req.httpBody = Data(genesisJson.utf8)
                _ = try? await NoRedirect.session.data(for: req)
                response = try await get(logUrl)
            }
            try require((200..<300).contains(response.0))
            // ponytail: bounded whole-log observation; >128 operations stays unknown, not truncated.
            guard let log = response.1?.array, (1...128).contains(log.count), log[0] == expected else { throw CoreError("Unexpected PLC log") }
            confirmed = true
            for (i, op) in log.enumerated() {
                try operation(op, genesis: i == 0)
                try require(op["type"]?.string != "plc_tombstone" || i == log.count - 1)
            }
            let latest = log[log.count - 1]
            let current: PlcCurrentState
            if latest["type"]?.string == "plc_tombstone" { current = .tombstoned } else {
                let resolved = try await get(target.appendingPathComponent("data"))
                try require((200..<300).contains(resolved.0))
                guard let data = resolved.1, data.object != nil, data["did"]?.string == did else { throw CoreError("Unexpected PLC data") }
                try state(data)
                // Different reads may straddle an update. Do not bless a mixed snapshot.
                try require(stateFields.allSatisfy { data[$0] == latest[$0] })
                current = ["rotationKeys", "verificationMethods"].allSatisfy({ data[$0] == expected[$0] }) &&
                    data["services"]?["atproto_pds"] == expected["services"]?["atproto_pds"] ? .checked : .changed
            }
            return PlcObservation(genesisConfirmed: confirmed, current: current, checkedAtMillis: Int64(Date().timeIntervalSince1970 * 1000))
        } catch { return PlcObservation(genesisConfirmed: confirmed) }
    }

    private static func get(_ url: URL) async throws -> (Int, Json?) {
        var req = URLRequest(url: url)
        req.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        let (data, response) = try await NoRedirect.session.data(for: req)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        return (code, (200..<300).contains(code) ? try parse(Array(data)) : nil)
    }

    private static func parse(_ bytes: [UInt8]) throws -> Json {
        try require((1...262144).contains(bytes.count))
        return try Json.parse(bytes, maxDepth: 16)
    }

    private static func operation(_ op: Json, genesis: Bool) throws {
        try require(op.object != nil && !(op["sig"]?.string ?? "").isEmpty)
        try require(genesis ? op["prev"] == .null : !(op["prev"]?.string ?? "").isEmpty)
        switch op["type"]?.string {
        case "plc_operation": try require(op.keys == Set(stateFields + ["type", "prev", "sig"])); try state(op)
        case "plc_tombstone": try require(!genesis && op.keys == ["type", "prev", "sig"])
        default: throw CoreError("Unsupported PLC operation")
        }
    }

    private static func state(_ node: Json) throws {
        guard let keys = node["rotationKeys"]?.array, (1...5).contains(keys.count),
              keys.allSatisfy({ $0.string?.hasPrefix("did:key:") == true }), Set(keys.compactMap(\.string)).count == keys.count else { throw CoreError("Invalid rotationKeys") }
        guard let methods = node["verificationMethods"]?.object, methods.count <= 10, methods.allSatisfy({ $0.1.string?.hasPrefix("did:key:") == true }) else {
            throw CoreError("Invalid verificationMethods")
        }
        guard let aliases = node["alsoKnownAs"]?.array, aliases.allSatisfy({ $0.string != nil }) else { throw CoreError("Invalid alsoKnownAs") }
        guard let services = node["services"]?.object, services.allSatisfy({ $0.1["type"]?.string != nil && $0.1["endpoint"]?.string != nil }) else {
            throw CoreError("Invalid services")
        }
    }
}
