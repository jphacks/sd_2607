import Foundation

/// Known protocol/0011 extensions decoded for display. Unknown or malformed ones are ignored, never rewritten.
public struct Origin: Equatable, Sendable {
    public let latitudeMicrodegrees: Int64, longitudeMicrodegrees: Int64
    public let accuracyMillimeters: Int64?
    public init(latitudeMicrodegrees: Int64, longitudeMicrodegrees: Int64, accuracyMillimeters: Int64? = nil) {
        self.latitudeMicrodegrees = latitudeMicrodegrees; self.longitudeMicrodegrees = longitudeMicrodegrees; self.accuracyMillimeters = accuracyMillimeters
    }
}
public enum Region: Equatable, Sendable {
    case circle(latitudeMicrodegrees: Int64, longitudeMicrodegrees: Int64, radiusMillimeters: Int64)
    case geohash(String)
}
/// BSAF original tags; `time:` is the source event time, not the import time.
public struct Bsaf: Equatable, Sendable { public let type: String?, time: String?, target: String?; public let tags: [String] }
public enum Relation: String, CaseIterable, Sendable { case correction, supplement, endorsement }
public struct Annotation: Equatable, Sendable { public let relation: Relation; public let targetEventId: String }
public struct PostExtensions: Equatable, Sendable {
    public var origin: Origin?
    public var regions: [Region] = []
    public var validityEndsAt: String?
    public var priority = 1
    public var bsaf: Bsaf?
    public var annotation: Annotation?
    public init() {}
}

public enum Extensions {
    public static let origin = "dev.oriom.mosimo.defs#origin"
    public static let area = "dev.oriom.mosimo.defs#area"
    public static let validity = "dev.oriom.mosimo.defs#validity"
    public static let annotation = "dev.oriom.mosimo.defs#annotation"

    public static func origin(_ o: Origin) -> [String: Cbor] {
        var m: [String: Cbor] = ["$type": .text(origin), "latitudeMicrodegrees": .int(o.latitudeMicrodegrees), "longitudeMicrodegrees": .int(o.longitudeMicrodegrees)]
        if let a = o.accuracyMillimeters { m["accuracyMillimeters"] = .int(a) }
        return m
    }
    public static func circle(latitudeMicrodegrees: Int64, longitudeMicrodegrees: Int64, radiusMillimeters: Int64) -> [String: Cbor] {
        ["$type": .text(area), "regions": .array([.map(["shape": .text("circle"), "latitudeMicrodegrees": .int(latitudeMicrodegrees),
            "longitudeMicrodegrees": .int(longitudeMicrodegrees), "radiusMillimeters": .int(radiusMillimeters)])])]
    }
    public static func validity(endsAt: String) -> [String: Cbor] { ["$type": .text(validity), "endsAt": .text(endsAt)] }
    // ponytail: nostr eventId only; follow 0011 §7 once the reference form is fixed.
    public static func annotation(_ relation: Relation, targetEventId: String) -> [String: Cbor] {
        ["$type": .text(annotation), "relation": .text(relation.rawValue), "target": .map(["network": .text("nostr"), "eventId": .text(targetEventId)])]
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
        let ext: [(String, [String: Cbor])] = event.tags.compactMap { tag in
            guard tag.count == 2, tag[0].hasPrefix(AtRepository.extPrefix),
                  let raw = Data(base64Encoded: tag[1] + String(repeating: "=", count: (4 - tag[1].utf8.count % 4) % 4)),
                  let m = (try? CanonicalCbor.decode(Array(raw)))?.map else { return nil }
            return (String(tag[0].dropFirst(AtRepository.extPrefix.count)), m)
        }
        func first(_ type: String) -> [String: Cbor]? { ext.first { $0.0 == type }?.1 }
        var p = PostExtensions()
        if let m = first(origin), let lat = m["latitudeMicrodegrees"]?.int, let lng = m["longitudeMicrodegrees"]?.int {
            p.origin = Origin(latitudeMicrodegrees: lat, longitudeMicrodegrees: lng, accuracyMillimeters: m["accuracyMillimeters"]?.int)
        }
        p.regions = ext.filter { $0.0 == area }.flatMap { ($0.1["regions"]?.array ?? []).compactMap { region($0.map) } }
        p.validityEndsAt = first(validity)?["endsAt"]?.text
        p.priority = PublicIngress.priority(event)
        if let m = first(PublicIngress.bsaf) {
            let tags = (m["tags"]?.array ?? []).compactMap(\.text)
            func tag(_ name: String) -> String? { tags.first { $0.hasPrefix(name + ":") }.map { String($0.dropFirst(name.count + 1)) } }
            p.bsaf = Bsaf(type: tag("type"), time: tag("time"), target: tag("target"), tags: tags)
        }
        if let m = first(annotation), let relation = m["relation"]?.text.flatMap(Relation.init(rawValue:)),
           let target = m["target"]?.map, target["network"] == .text("nostr"), let id = target["eventId"]?.text {
            p.annotation = Annotation(relation: relation, targetEventId: id)
        }
        return p
    }

    private static let geohashPattern = try! NSRegularExpression(pattern: "^[0-9b-hjkmnp-z]{1,12}$")
    private static func region(_ m: [String: Cbor]?) -> Region? {
        switch m?["shape"]?.text {
        case "circle":
            guard let lat = m?["latitudeMicrodegrees"]?.int, let lng = m?["longitudeMicrodegrees"]?.int, let r = m?["radiusMillimeters"]?.int else { return nil }
            return .circle(latitudeMicrodegrees: lat, longitudeMicrodegrees: lng, radiusMillimeters: r)
        case "geohash":
            guard let h = m?["hash"]?.text, matches(geohashPattern, h) else { return nil }
            return .geohash(h)
        default: return nil
        }
    }
}
