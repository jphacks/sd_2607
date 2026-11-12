import Foundation

/// Known official extensions decoded for display. Unknown or malformed ones are ignored, never rewritten.
public struct Origin: Equatable, Sendable {
    public let lat: Int64, lon: Int64
    public let acc: Int64?
    public let at: String?
    public init(lat: Int64, lon: Int64, acc: Int64? = nil, at: String? = nil) { self.lat = lat; self.lon = lon; self.acc = acc; self.at = at }
}
public enum Region: Equatable, Sendable {
    case circle(lat: Int64, lon: Int64, r: Int64)
    case geohash(String)
}
/// geo#area: the union of [regions]; [approximation] marks a covering approximation of an original area.
public struct Area: Equatable, Sendable { public let regions: [Region]; public let approximation: Bool }
/// validity: `from <= t < until`; an absent bound is unknown, never inferred.
public struct Validity: Equatable, Sendable { public let from: String?; public let until: String? }
/// BSAF original tags; `time:` is the source event time, not the import time.
public struct Bsaf: Equatable, Sendable {
    public let type: String?, time: String?, target: String?, source: String?
    public let tags: [String]
    /// The post is an import (`original` present).
    public let imported: Bool
    /// The re-posted Bot's DID, from `original.uri`.
    public let botDid: String?
}
public enum Relation: String, CaseIterable, Sendable { case correction, supplement, endorsement }
/// [targetEventId] is the Nostr target (hex); atproto references are kept as [targetUri].
public struct Annotation: Equatable, Sendable { public let relation: Relation; public let targetEventId: String?; public let targetUri: String? }
public struct PostExtensions: Equatable, Sendable {
    public var origin: Origin?
    public var areas: [Area] = []
    public var validity: Validity?
    public var priority = 1
    public var bsaf: Bsaf?
    public var annotation: Annotation?
    /// An `attest.chain` extension is present (verification is the coordinator's).
    public var hasChain = false
    public var regions: [Region] { areas.flatMap(\.regions) }
    public init() {}
}

public enum Extensions {
    public static let origin = "dev.jisaku.mosimo.geo#origin"
    public static let area = "dev.jisaku.mosimo.geo#area"
    public static let validity = "dev.jisaku.mosimo.validity"
    public static let annotation = "dev.jisaku.mosimo.annotation"
    public static let priority = PublicIngress.priorityType

    public static func origin(_ o: Origin) -> [String: Cbor] {
        var m: [String: Cbor] = ["$type": .text(origin), "lat": .int(o.lat), "lon": .int(o.lon)]
        if let a = o.acc { m["acc"] = .int(a) }
        if let at = o.at { m["at"] = .text(at) }
        return m
    }
    public static func area(_ regions: [Region], approximation: Bool = false) -> [String: Cbor] {
        var m: [String: Cbor] = ["$type": .text(area), "regions": .array(regions.map { r in
            switch r {
            case .circle(let lat, let lon, let radius): return .map(["shape": .text("circle"), "lat": .int(lat), "lon": .int(lon), "r": .int(radius)])
            case .geohash(let h): return .map(["shape": .text("geohash"), "hash": .text(h)])
            }
        })]
        if approximation { m["approximation"] = .bool(true) }
        return m
    }
    public static func circle(lat: Int64, lon: Int64, r: Int64) -> [String: Cbor] { area([.circle(lat: lat, lon: lon, r: r)]) }
    /// At least one bound is required (validity §3).
    public static func validity(from: String? = nil, until: String? = nil) -> [String: Cbor] {
        precondition(from != nil || until != nil, "validity needs from or until")
        var m: [String: Cbor] = ["$type": .text(validity)]
        if let from { m["from"] = .text(from) }
        if let until { m["until"] = .text(until) }
        return m
    }
    /// Nostr target reference; the event ID travels as 32 bytes inside the CBOR (annotation §2).
    public static func annotation(_ relation: Relation, targetEventId: String) throws -> [String: Cbor] {
        ["$type": .text(annotation), "relation": .text(relation.rawValue),
         "target": .map(["network": .text("nostr"), "eventId": .bytes(try unhex(targetEventId, 32))])]
    }
    public static func priority(_ level: Int) -> [String: Cbor] {
        precondition((0...3).contains(level))
        return ["$type": .text(priority), "level": .int(Int64(level))]
    }

    nonisolated(unsafe) private static var cache = [String: PostExtensions]()
    private static let lock = NSLock()
    /// Memoized by event ID: the UI snapshot is rebuilt every second.
    public static func of(_ event: NostrEvent) -> PostExtensions {
        lock.lock(); defer { lock.unlock() }
        if let hit = cache[event.id] { return hit }
        if cache.count > 1024 { cache.removeAll() }
        let decoded = decode(event)
        cache[event.id] = decoded
        return decoded
    }

    public static func decode(_ event: NostrEvent) -> PostExtensions {
        // One malformed extension must not hide the others: decode tag by tag, skipping the bad one.
        let ext: [[String: Cbor]] = (try? AtRepository.extensions(event)) ?? event.tags.filter { $0.count == 2 && $0[0].hasPrefix(AtRepository.extPrefix) }
            .compactMap { (try? AtRepository.extensions([$0]))?.first }
        func first(_ type: String) -> [String: Cbor]? { ext.first { $0["$type"] == .text(type) } }
        var p = PostExtensions()
        if let m = first(origin), let lat = m["lat"]?.int, let lon = m["lon"]?.int {
            p.origin = Origin(lat: lat, lon: lon, acc: m["acc"]?.int, at: m["at"]?.text)
        }
        p.areas = ext.filter { $0["$type"] == .text(area) }.map { m in
            Area(regions: (m["regions"]?.array ?? []).compactMap { region($0.map) }, approximation: m["approximation"] == .bool(true))
        }
        if let m = first(validity) {
            let v = Validity(from: m["from"]?.text, until: m["until"]?.text)
            if v.from != nil || v.until != nil { p.validity = v }
        }
        p.priority = PublicIngress.priority(event)
        if let m = first(PublicIngress.bsaf) {
            let tags = (m["tags"]?.array ?? []).compactMap(\.text)
            func tag(_ name: String) -> String? {
                let found = tags.filter { $0.hasPrefix(name + ":") }
                return found.count == 1 ? String(found[0].dropFirst(name.count + 1)) : nil
            }
            let uri = m["original"]?["uri"]?.text
            let did = uri.map { String($0.replacingOccurrences(of: "at://", with: "").prefix { $0 != "/" }) }.flatMap { $0.hasPrefix("did:") ? $0 : nil }
            p.bsaf = Bsaf(type: tag("type"), time: tag("time"), target: tag("target"), source: tag("source"), tags: tags,
                          imported: m["original"] != nil, botDid: did)
        }
        if let m = first(annotation), let relation = m["relation"]?.text.flatMap(Relation.init(rawValue:)), let target = m["target"]?.map {
            let eventId = target["network"] == .text("nostr") ? target["eventId"]?.bytes.flatMap { $0.count == 32 ? hex($0) : nil } : nil
            let uri = target["network"] == .text("atproto") ? target["uri"]?.text : nil
            if eventId != nil || uri != nil { p.annotation = Annotation(relation: relation, targetEventId: eventId, targetUri: uri) }
        }
        p.hasChain = ext.contains { $0["$type"] == .text(Attest.chain) }
        return p
    }

    private static let geohashPattern = try! NSRegularExpression(pattern: "^[0-9b-hjkmnp-z]{1,12}$")
    private static func region(_ m: [String: Cbor]?) -> Region? {
        switch m?["shape"]?.text {
        case "circle":
            guard let lat = m?["lat"]?.int, let lon = m?["lon"]?.int, let r = m?["r"]?.int else { return nil }
            return .circle(lat: lat, lon: lon, r: r)
        case "geohash":
            guard let h = m?["hash"]?.text, matches(geohashPattern, h) else { return nil }
            return .geohash(h)
        default: return nil
        }
    }
}
