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
/// [complete] is false when an element could not be read, so a point outside [regions] is unknown, not outside (geo §4.2).
public struct Area: Equatable, Sendable { public let regions: [Region]; public let approximation: Bool; public var complete = true }
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
public struct LocalizedText: Equatable, Sendable { public let text: String, lang: String }
/// hazard §5: a regional scale kept in its original notation.
public struct HazardScale: Equatable, Sendable { public let scheme: String, value: String; public let basis: String? }
/// hazard §2: enum fields keep unknown values as raw strings (the UI treats them as Unknown).
public struct Hazard: Equatable, Sendable {
    public let status: String, category: [String], severity: String, urgency: String, certainty: String
    public let responseTypes: [String]
    public let onset: String?, event: LocalizedText?
    public let scales: [HazardScale]
    public let glide: String?
    /// The single type-specific extension (hazard §7); nil when absent, malformed or ambiguous.
    public let kind: HazardKind?
    public var allClear: Bool { responseTypes.contains("AllClear") }
}
public enum HazardKind: Equatable, Sendable {
    /// Coordinates in microdegrees, lengths in mm, magnitude in tenths, pressure in Pa, wind in mm/s.
    public struct Hypocenter: Equatable, Sendable { public let lat: Int64?, lon: Int64?, depth: Int64?; public let name: LocalizedText? }
    public struct Magnitude: Equatable, Sendable { public let value: Int64; public let type: String? }
    public struct Height: Equatable, Sendable { public let value: Int64?; public let text: LocalizedText?; public let basis: String? }
    public struct Volcano: Equatable, Sendable { public let name: LocalizedText?; public let gvp: String?; public let lat: Int64?, lon: Int64? }
    public struct Point: Equatable, Sendable { public let lat: Int64, lon: Int64 }
    public struct Cyclone: Equatable, Sendable {
        public let name: LocalizedText?, designation: String?
        public let center: Point?
        public let pressure: Int64?, maxWind: Int64?, stormRadius: Int64?
    }
    case earthquake(kind: String, hypocenter: Hypocenter?, magnitude: Magnitude?)
    case tsunami(maxHeight: Height?, arrival: String?)
    case volcano(phenomenon: String, volcano: Volcano?)
    case weather(phenomena: [String])
    case cyclone(Cyclone)
}
/// public.jma.earthquake / public.jma.weather, only what the display replacement needs (jma §5.3); original strings kept.
public struct Jma: Equatable, Sendable {
    public enum Kind: Sendable { case earthquake, weather }
    /// `JmaCode`: the value with its code system, compared together (jma §5.3: a Pref is matched by system and code).
    public struct Code: Equatable, Hashable, Sendable { public let value: String; public let type: String? }
    public let kind: Kind
    /// `message.control.title` / `status` / `dateTime` and `message.head.eventID`.
    public let title: String, status: String, dateTime: String
    public let eventID: String?
    /// earthquake: `selection.kind == "whole"`; false for a `region` selection.
    public let whole: Bool
    /// earthquake `region`: the selected Pref, `body.observation.prefs[0].code`.
    public let prefCode: Code?
    /// weather: the forecast district of the series, `areas[0].code` of the 「気象警報・注意報（府県予報区等）」 warning item.
    public let forecastAreaCode: Code?
}
public struct PostExtensions: Equatable, Sendable {
    public var origin: Origin?
    public var areas: [Area] = []
    public var validity: Validity?
    public var priority = 1
    public var bsaf: Bsaf?
    public var annotation: Annotation?
    public var hazard: Hazard?
    /// The single JMA extension (earthquake or weather); nil when absent, malformed or more than one.
    public var jma: Jma?
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
    public static let hazard = "dev.jisaku.mosimo.hazard"
    public static let hazardKinds = ["earthquake", "tsunami", "volcano", "weather", "cyclone"].map { "\(hazard).\($0)" }
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
            let raw = m["regions"]?.array ?? []
            let regions = raw.compactMap { region($0.map) }
            return Area(regions: regions, approximation: m["approximation"] == .bool(true), complete: regions.count == raw.count)
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
        // importer §1.1: a JMA re-post carries exactly one of the two; with both, neither is its subject.
        let jma = ext.filter { $0["$type"] == .text(PublicIngress.jmaEarthquake) || $0["$type"] == .text(PublicIngress.jmaWeather) }
        if jma.count == 1 { p.jma = Self.jma(jma[0]) }
        if let m = first(hazard) {
            // hazard §7: several type-specific extensions are forbidden, so none is trusted to be the subject.
            let kinds = ext.filter { e in hazardKinds.contains { e["$type"] == .text($0) } }
            p.hazard = Self.hazard(m, kind: kinds.count == 1 ? hazardKind(kinds[0]) : nil)
        }
        return p
    }

    private static func texts(_ c: Cbor?) -> [String]? { c?.array.map { $0.compactMap(\.text) } }
    private static func localized(_ c: Cbor?) -> LocalizedText? {
        guard let text = c?["text"]?.text, let lang = c?["lang"]?.text else { return nil }
        return LocalizedText(text: text, lang: lang)
    }
    /// Missing or wrong-typed required fields drop the hazard; wrong-typed optional fields read as absent.
    static func hazard(_ m: [String: Cbor], kind: HazardKind?) -> Hazard? {
        guard let status = m["status"]?.text, let category = texts(m["category"]), !category.isEmpty,
              let severity = m["severity"]?.text, let urgency = m["urgency"]?.text, let certainty = m["certainty"]?.text else { return nil }
        let scales = (m["scales"]?.array ?? []).compactMap { s -> HazardScale? in
            guard let scheme = s["scheme"]?.text, let value = s["value"]?.text else { return nil }
            return HazardScale(scheme: scheme, value: value, basis: s["basis"]?.text)
        }
        return Hazard(status: status, category: category, severity: severity, urgency: urgency, certainty: certainty,
                      responseTypes: texts(m["responseTypes"]) ?? [], onset: m["onset"]?.text, event: localized(m["event"]),
                      scales: scales, glide: m["glide"]?.text, kind: kind)
    }
    static func hazardKind(_ m: [String: Cbor]) -> HazardKind? {
        switch m["$type"]?.text.map({ String($0.dropFirst(hazard.count + 1)) }) {
        case "earthquake":
            guard let kind = m["kind"]?.text else { return nil }
            let h = m["hypocenter"]?.map.map { HazardKind.Hypocenter(lat: $0["lat"]?.int, lon: $0["lon"]?.int, depth: $0["depth"]?.int, name: localized($0["name"])) }
            let mag = m["magnitude"].flatMap { c in c["value"]?.int.map { HazardKind.Magnitude(value: $0, type: c["type"]?.text) } }
            return .earthquake(kind: kind, hypocenter: h, magnitude: mag)
        case "tsunami":
            let h = m["maxHeight"]?.map.map { HazardKind.Height(value: $0["value"]?.int, text: localized($0["text"]), basis: $0["basis"]?.text) }
            return .tsunami(maxHeight: h, arrival: m["arrival"]?.text)
        case "volcano":
            guard let phenomenon = m["phenomenon"]?.text else { return nil }
            let v = m["volcano"]?.map.map { HazardKind.Volcano(name: localized($0["name"]), gvp: $0["gvp"]?.text, lat: $0["lat"]?.int, lon: $0["lon"]?.int) }
            return .volcano(phenomenon: phenomenon, volcano: v)
        case "weather":
            return texts(m["phenomena"]).map { .weather(phenomena: $0) }
        case "cyclone":
            let center = m["center"].flatMap { c in c["lat"]?.int.flatMap { lat in c["lon"]?.int.map { HazardKind.Point(lat: lat, lon: $0) } } }
            return .cyclone(.init(name: localized(m["name"]), designation: m["designation"]?.text, center: center,
                                  pressure: m["pressure"]?.int, maxWind: m["maxWind"]?.int, stormRadius: m["stormRadius"]?.int))
        default: return nil
        }
    }

    static func jma(_ m: [String: Cbor]) -> Jma? {
        let control = m["message"]?["control"]
        guard let title = control?["title"]?.text, let status = control?["status"]?.text, let dateTime = control?["dateTime"]?.text else { return nil }
        func code(_ c: Cbor?) -> Jma.Code? { c?["value"]?.text.map { Jma.Code(value: $0, type: c?["type"]?.text) } }
        let eventID = m["message"]?["head"]?["eventID"]?.text
        if m["$type"] == .text(PublicIngress.jmaEarthquake) {
            let selection = m["selection"]
            guard let kind = selection?["kind"]?.text, kind == "whole" || kind == "region" else { return nil }
            let pref = kind == "region" ? code(m["body"]?["observation"]?["prefs"]?.array?.first?["code"]) : nil
            return Jma(kind: .earthquake, title: title, status: status, dateTime: dateTime, eventID: eventID, whole: kind == "whole",
                       prefCode: pref, forecastAreaCode: nil)
        }
        let district = (m["warnings"]?.array ?? []).first { $0["warningType"]?.text == "気象警報・注意報（府県予報区等）" }
        return Jma(kind: .weather, title: title, status: status, dateTime: dateTime, eventID: eventID, whole: m["selection"]?["kind"]?.text == "whole",
                   prefCode: nil, forecastAreaCode: code(district?["areas"]?.array?.first?["code"]))
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

/// jma §5.3: display-only replacement. Of the posts of one series, only the latest `Control/DateTime` stands for the current
/// state on the map; older ones stay in lists and details and are delivered and kept as before (no delivery stop).
public enum JmaDisplay {
    /// 「気象警報・注意報（Ｒ０６）（<現象群>）」: each message lists every municipality of its district (weather §7.1).
    public static let weatherR06Prefix = "気象警報・注意報（Ｒ０６）"

    /// Ids of [posts] a newer post of the same series replaces. Callers pass only posts whose sender is authorized: an
    /// unconfirmed post must neither replace nor be replaced. Posts without a parseable time or series key take no part.
    /// - earthquake: series `Control/Title` + `Status` + `Head/EventID`; a newer post of the same Pref, or a newer `whole` post.
    /// - weather R06: series `Control/Title` + `Status` + the district code; any newer post.
    public static func superseded(_ posts: [(id: String, jma: Jma)]) -> Set<String> {
        var series = [[String]: [(id: String, jma: Jma, at: Instant)]]()
        for p in posts {
            guard let at = Instant.parse(p.jma.dateTime), let key = seriesKey(p.jma) else { continue }
            series[key, default: []].append((p.id, p.jma, at))
        }
        var out = Set<String>()
        for members in series.values where members.count > 1 {
            for old in members where members.contains(where: { new in new.at > old.at && replaces(new.jma, old.jma) }) { out.insert(old.id) }
        }
        return out
    }

    static func seriesKey(_ j: Jma) -> [String]? {
        switch j.kind {
        case .earthquake:
            guard let id = j.eventID, !id.isEmpty else { return nil }
            return ["earthquake", j.title, j.status, id]
        case .weather:
            guard j.title.hasPrefix(weatherR06Prefix), let area = j.forecastAreaCode else { return nil }
            return ["weather", j.title, j.status, area.type ?? "", area.value]
        }
    }

    /// Within one series: a newer Pref post replaces only the same Pref (another Pref says nothing of this one's state).
    static func replaces(_ new: Jma, _ old: Jma) -> Bool {
        switch new.kind {
        case .earthquake: return new.whole || (new.prefCode != nil && new.prefCode == old.prefCode)
        case .weather: return true
        }
    }
}
