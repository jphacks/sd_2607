import Foundation

/// Mesh §2.2 / §6 limits plus local storage policy (§11 defaults).
public enum MeshPolicy {
    public static let destination = "dtn://mosimo.jisaku.dev/~post"
    /// §6: event payload (the 7-element CBOR array) and whole encoded Bundle.
    public static let maxPayloadBytes = 32_768
    public static let maxBundleBytes = 65_536
    // Internal snapshots may grow by at most eight bytes as the CBOR Bundle Age integer grows.
    public static let maxSavedBundleBytes = maxBundleBytes + 8
    public static let maxEntries = 128
    public static let maxHops: UInt64 = 8
    public static let maxResidenceMillis: Int64 = 86_400_000
    public static let perAuthorPerMinute = 12
}

public struct StoredBundle: Sendable {
    public let event: NostrEvent
    public let bytes: [UInt8]
    public let hop: Int
    public let receivedAtMillis: Int64
    public let receivedElapsedMillis: Int64
    public let did: String?

    init(event: NostrEvent, bytes: [UInt8], hop: Int, receivedAtMillis: Int64, receivedElapsedMillis: Int64) {
        self.event = event; self.bytes = bytes; self.hop = hop
        self.receivedAtMillis = receivedAtMillis; self.receivedElapsedMillis = receivedElapsedMillis
        did = AtRepository.did(event)
    }
}

public struct BlockedAuthor: Error { public let id: String }

public enum ClockConfidence: Sendable { case unknown, trusted }
public enum DeliveryDeadline: Sendable { case unknown, withinDeadline, expired }

/// Call from one serialized coordinator. Links only move bytes; this is the store-carry-forward router.
public final class MeshEngine {
    public var clockConfidence: ClockConfidence
    private var order = [String]()
    private var stored = [String: StoredBundle]()
    /// Event IDs already accepted by an online relay; such entries are evicted first once they stop forwarding.
    public var relayed: (String) -> Bool = { _ in false }
    /// Local retention policy, independent of mesh forwarding eligibility and relay priority.
    public var canEvict: (StoredBundle) -> Bool = { _ in true }
    /// Author block filter (mesh §10, by Nostr pubkey); refused events are never stored, so they are never advertised or forwarded either.
    public var admit: (NostrEvent) -> Bool = { _ in true }
    /// Held but no longer offered or forwarded: author-deleted (core §11) or revoked (attest.chain §4.2) events.
    public var suppressed: (NostrEvent) -> Bool = { _ in false }
    private var rates = [String: [Int64]]()
    private var sendRates = [String: [Int64]]()

    public init(clockConfidence: ClockConfidence = .unknown) { self.clockConfidence = clockConfidence }

    @discardableResult
    public func originate(_ event: NostrEvent, nowMillis: Int64, elapsedMillis: Int64) throws -> StoredBundle {
        try require(event.verify(), "invalid author signature")
        let expiry = try self.expiry(event)
        try require(deadline(event, nowMillis: nowMillis) != .expired, "delivery deadline expired")
        let lifetime = clockConfidence == .trusted ? min(max(expiry.epochMillis - nowMillis, 1), MeshPolicy.maxResidenceMillis) : MeshPolicy.maxResidenceMillis
        // TODO(undecided): originating node EID; the author key prefix stands in for a node ID.
        // ponytail: 60-bit event-derived sequence; a persistent allocator if bundle-ID collisions matter.
        var bundle = Bundle(primary: .init(flags: Bundle.Primary.mustNotFragment, destination: MeshPolicy.destination,
            source: "dtn://n\(event.pubkey.prefix(32))/post", sequenceNumber: UInt64(event.id.prefix(15), radix: 16)!, lifetime: UInt64(lifetime)), blocks: [])
        var hop = BpWriter(); hop.head(4, 2); hop.number(MeshPolicy.maxHops); hop.number(0)
        bundle.add(type: Bundle.hopCount, data: hop.out)
        bundle.add(type: Bundle.bundleAge, data: [0])
        bundle.add(type: Bundle.payload, data: try EventCodec.encode(event))
        guard let entry = try accept(bundle.marshal(), nowMillis: nowMillis, elapsedMillis: elapsedMillis) else { throw CoreError("already stored") }
        return entry
    }

    public func accept(_ bytes: [UInt8], nowMillis: Int64, elapsedMillis: Int64) throws -> StoredBundle? {
        try insert(bytes, nowMillis, elapsedMillis, applyRateLimit: true)
    }

    /// Without a monotonic downtime (reboot) the wall-clock gap counts as residence; a clock going backwards counts as zero.
    @discardableResult
    public func restore(_ bytes: [UInt8], receivedAtMillis: Int64, nowMillis: Int64, elapsedMillis: Int64, downtimeMillis: Int64? = nil) throws -> StoredBundle? {
        var bundle = try parse(bytes, saved: true)
        guard let age = bundle.age else { throw CoreError("missing bundle age") }
        try require(downtimeMillis == nil || downtimeMillis! >= 0)
        // ponytail: an untrusted wall clock may under-count reboot downtime; maxResidence still bounds uptime.
        let downtime = downtimeMillis ?? positiveDifference(nowMillis, receivedAtMillis)
        bundle.setAge(saturatedAdd(age, UInt64(downtime)))
        // Expired entries remain available to the relay backlog, but never to mesh forwarding.
        return try insert(bundle.marshal(), nowMillis, elapsedMillis, applyRateLimit: false, restoring: true)
    }

    public func entries() -> [StoredBundle] { order.compactMap { stored[$0] } }

    @discardableResult
    public func remove(_ predicate: (StoredBundle) -> Bool) -> Bool {
        let gone = entries().filter(predicate).map(\.event.id)
        for id in gone { stored[id] = nil }
        order.removeAll { stored[$0] == nil }
        return !gone.isEmpty
    }

    public func deadline(_ event: NostrEvent, nowMillis: Int64) -> DeliveryDeadline {
        guard clockConfidence == .trusted, let expiry = AtRepository.deliveryExpiresAt(event) else { return .unknown }
        return expiry.epochMillis > nowMillis ? .withinDeadline : .expired
    }

    /// Snapshot accumulated monotonic age without modifying the stored original or signed event.
    public func checkpoint(_ entry: StoredBundle, nowMillis: Int64, elapsedMillis: Int64) throws -> StoredBundle {
        var bundle = try parse(entry.bytes, saved: true)
        bundle.setAge(try currentAge(entry, elapsedMillis))
        let bytes = bundle.marshal()
        try require(bytes.count <= MeshPolicy.maxSavedBundleBytes)
        return StoredBundle(event: entry.event, bytes: bytes, hop: entry.hop, receivedAtMillis: nowMillis, receivedElapsedMillis: elapsedMillis)
    }

    public func inventory(nowMillis: Int64, elapsedMillis: Int64) -> [String] {
        entries().filter { eligible($0, nowMillis, elapsedMillis) }.map(\.event.id)
    }

    /// Mesh §5.5 reconciliation set: forwardable events as `(created_at, id)` items.
    public func candidates(nowMillis: Int64, elapsedMillis: Int64) -> [Negentropy.Item] {
        entries().filter { eligible($0, nowMillis, elapsedMillis) }.compactMap { e in
            (try? unhex(e.event.id, 32)).flatMap { try? Negentropy.Item(timestamp: e.event.createdAt, id: $0) }
        }
    }

    /// mesh.adv §2: SHA-256 over the sorted candidate IDs, first 3 bytes.
    public func summary(nowMillis: Int64, elapsedMillis: Int64) -> [UInt8] {
        let ids = candidates(nowMillis: nowMillis, elapsedMillis: elapsedMillis).map(\.id).sorted { $0.lexicographicallyPrecedes($1) }
        return Array(sha256(ids.flatMap { $0 }).prefix(3))
    }

    /// Each retry derives a fresh send view; it never increments the stored hop count.
    public func forward(_ id: String, nowMillis: Int64, elapsedMillis: Int64) -> [UInt8]? {
        guard let entry = stored[id], eligible(entry, nowMillis, elapsedMillis) else { return nil }
        var sent = (sendRates[entry.event.pubkey] ?? []).filter { !(elapsedMillis - $0 >= 60_000 || elapsedMillis < $0) }
        if sent.count >= 36 { sendRates[entry.event.pubkey] = sent; return nil }
        sent.append(elapsedMillis)
        sendRates[entry.event.pubkey] = sent
        guard var bundle = try? parse(entry.bytes, saved: true), let hop = bundle.hop, let age = try? currentAge(entry, elapsedMillis) else { return nil }
        bundle.blocks.removeAll { $0.type == Bundle.previousNode }
        bundle.setHop(limit: hop.limit, count: hop.count + 1)
        bundle.setAge(age)
        let out = bundle.marshal()
        return out.count <= MeshPolicy.maxBundleBytes ? out : nil
    }

    private func insert(_ bytes: [UInt8], _ nowMillis: Int64, _ elapsedMillis: Int64, applyRateLimit: Bool, restoring: Bool = false) throws -> StoredBundle? {
        var bundle = try parse(bytes, saved: restoring)
        // Structure is validated without the wall clock; offline restore keeps expired bundles for the relay queue.
        try validPrimary(bundle)
        for b in bundle.blocks { try validBlock(b, bundle) }
        try require(bundle.primary.crcType != .none, "primary CRC required")
        try require(!bundle.primary.isFragment && !bundle.primary.isAdminRecord, "unsupported bundle category")
        try require(bundle.blocks.filter { $0.type == 1 }.count == 1, "one payload required")
        try require(bundle.blocks.last?.type == 1, "payload must be last")
        try require(Set(bundle.blocks.map(\.number)).count == bundle.blocks.count, "duplicate block numbers")
        for type: UInt64 in [6, 7, 10] { try require(bundle.blocks.filter { $0.type == type }.count <= 1, "duplicate extension") }
        try require(!bundle.blocks.contains { $0.type == 11 || $0.type == 12 }, "BPSec not supported by this profile")
        guard let hop = bundle.hop else { throw CoreError("missing hop count") }
        guard let age = bundle.age else { throw CoreError("missing age") }
        try require(bundle.payloadData!.count <= MeshPolicy.maxPayloadBytes, "payload size limit")
        let event = try EventCodec.decode(bundle.payloadData!)
        try require(event.verify(), "invalid author signature")
        _ = try expiry(event)
        try require(bundle.primary.destination == MeshPolicy.destination, "unknown mesh destination")
        try require((1...255).contains(hop.limit) && hop.count <= min(hop.limit, MeshPolicy.maxHops), "hop limit exceeded")
        try require(bundle.primary.lifetime >= 1 && bundle.primary.lifetime <= UInt64(MeshPolicy.maxResidenceMillis), "lifetime exceeds local policy")
        if !restoring {
            try require(age < bundle.primary.lifetime, "bundle lifetime expired")
            try require(creationTimeEligible(bundle, nowMillis), "bundle expired")
        }
        // A past signed deadline only stops forwarding (eligible); the event is still kept for gateway export.
        let known: Set<UInt64> = [1, 6, 7, 10]
        try require(!bundle.blocks.contains { !known.contains($0.type) && $0.flags & 0x04 != 0 }, "unknown block requires bundle deletion")
        bundle.blocks.removeAll { !known.contains($0.type) && $0.flags & 0x10 != 0 }
        if stored[event.id] != nil { return nil }
        if !admit(event) { throw BlockedAuthor(id: event.id) }
        var victimId: String?
        if stored.count >= MeshPolicy.maxEntries {
            guard let v = victim(nowMillis, elapsedMillis) else { throw CoreError("storage capacity reached: all entries protected") }
            victimId = v
        }
        if applyRateLimit {
            for (k, samples) in rates { rates[k] = samples.filter { !(elapsedMillis - $0 >= 60_000 || elapsedMillis < $0) } }
            rates = rates.filter { !$0.value.isEmpty }
            var samples = rates[event.pubkey] ?? []
            try require(samples.count < MeshPolicy.perAuthorPerMinute, "author rate limit")
            samples.append(elapsedMillis)
            rates[event.pubkey] = samples
        }
        let entry = StoredBundle(event: event, bytes: bundle.marshal(), hop: Int(hop.count), receivedAtMillis: nowMillis, receivedElapsedMillis: elapsedMillis)
        if let v = victimId { stored[v] = nil; order.removeAll { $0 == v } }
        stored[event.id] = entry
        order.append(event.id)
        return entry
    }

    // ponytail: no per-author fairness; the per-author accept rate is the only flood brake.
    private func victim(_ nowMillis: Int64, _ elapsedMillis: Int64) -> String? {
        let candidates = entries().filter { canEvict($0) }
        let dead = candidates.filter { !eligible($0, nowMillis, elapsedMillis) }
        return (dead.first { relayed($0.event.id) } ?? dead.first ?? candidates.first)?.event.id
    }

    private func eligible(_ entry: StoredBundle, _ nowMillis: Int64, _ elapsedMillis: Int64) -> Bool {
        guard !suppressed(entry.event), let bundle = try? parse(entry.bytes, saved: true), let hop = bundle.hop,
              let age = try? currentAge(entry, elapsedMillis) else { return false }
        return deadline(entry.event, nowMillis: nowMillis) != .expired && hop.count < min(hop.limit, MeshPolicy.maxHops) &&
            age < bundle.primary.lifetime && creationTimeEligible(bundle, nowMillis)
    }

    private func creationTimeEligible(_ bundle: Bundle, _ nowMillis: Int64) -> Bool {
        if clockConfidence != .trusted || bundle.primary.creationTimestamp == 0 { return true }
        // DTN time is milliseconds since 2000-01-01.
        let now = nowMillis - 946_684_800_000
        let (end, overflow) = bundle.primary.creationTimestamp.addingReportingOverflow(bundle.primary.lifetime)
        return overflow || now < 0 || UInt64(now) <= end
    }

    private func currentAge(_ entry: StoredBundle, _ elapsedMillis: Int64) throws -> UInt64 {
        if elapsedMillis < entry.receivedElapsedMillis { return UInt64(Int64.max) }
        guard let age = try parse(entry.bytes, saved: true).age else { throw CoreError("missing age") }
        return saturatedAdd(age, UInt64(positiveDifference(elapsedMillis, entry.receivedElapsedMillis)))
    }

    private func expiry(_ event: NostrEvent) throws -> Instant {
        guard let e = AtRepository.deliveryExpiresAt(event) else { throw CoreError("signed delivery deadline required") }
        return e
    }

    private func parse(_ bytes: [UInt8], saved: Bool = false) throws -> Bundle {
        try require(bytes.count >= 1 && bytes.count <= (saved ? MeshPolicy.maxSavedBundleBytes : MeshPolicy.maxBundleBytes), "bundle size limit")
        return try Bundle.unmarshal(bytes)
    }

    private func validEid(_ uri: String) -> Bool {
        if uri == "dtn:none" || uri.hasPrefix("ipn:") { return true }
        // dtn://node/demux: an authority and a non-empty path.
        guard uri.hasPrefix("dtn://") else { return false }
        let rest = uri.dropFirst(6)
        guard let slash = rest.firstIndex(of: "/") else { return false }
        return slash != rest.startIndex
    }

    private func validPrimary(_ b: Bundle) throws {
        let p = b.primary
        try require(p.version == 7, "invalid primary fields")
        let statusFlags: UInt64 = 1 << 14 | 1 << 16 | 1 << 17 | 1 << 18
        try require(!(p.isAdminRecord && p.flags & statusFlags != 0), "administrative bundles must not request status reports")
        try require(!(p.isFragment && p.flags & Bundle.Primary.mustNotFragment != 0), "fragment with must-not-fragment")
        try require(validEid(p.source) && validEid(p.reportTo) && validEid(p.destination), "eid unintelligible")
        if p.source == "dtn:none" {
            try require(p.flags & Bundle.Primary.mustNotFragment != 0 && p.flags & statusFlags == 0, "anonymous bundle constraints")
        }
    }

    private func validBlock(_ block: Bundle.Block, _ b: Bundle) throws {
        try require(block.number > 0 && block.type > 0, "canonical block type and number must be positive")
        try require(!((b.primary.isAdminRecord || b.primary.source == "dtn:none") && block.flags & 2 != 0), "anonymous block status")
        switch block.type {
        case Bundle.payload: try require(block.number == 1, "payload block must have number 1")
        case Bundle.bundleAge:
            var r = try BpReader(block.data); _ = try r.uint(); try require(r.atEnd, "invalid bundle age block")
        case Bundle.hopCount:
            var r = try BpReader(block.data)
            try require(try r.arrayCount() == 2, "invalid hop count block")
            let limit = try r.uint(); _ = try r.uint()
            try require(r.atEnd && (1...255).contains(limit), "invalid hop limit/count")
        case Bundle.previousNode:
            var r = try BpReader(block.data); try require(validEid(try r.eid()) && r.atEnd, "invalid previous node EID")
        default: break
        }
    }
}

private func positiveDifference(_ now: Int64, _ then: Int64) -> Int64 {
    if now <= then { return 0 }
    let (d, overflow) = now.subtractingReportingOverflow(then)
    return overflow ? Int64.max : d
}

private func saturatedAdd(_ a: UInt64, _ b: UInt64) -> UInt64 {
    let cap = UInt64(Int64.max)
    return a > cap - min(b, cap) ? cap : a + b
}
