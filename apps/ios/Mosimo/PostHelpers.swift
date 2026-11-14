import CoreLocation
import MosimoCore
import SwiftUI

/// Situation kind, carried as a plain-text prefix 「［危険］」 so any client can read it (no protocol field).
enum Category: String, CaseIterable, Identifiable {
    case safe = "安全", caution = "注意", danger = "危険", shelter = "避難所", blocked = "通行不可", supplies = "物資あり", help = "人が必要"
    var id: String { rawValue }
    var label: String { rawValue }
    var prefix: String { "［\(label)］" }
    var color: Color {
        switch self {
        case .safe, .shelter: return Mosimo.safe
        case .caution, .blocked: return Mosimo.warning
        case .danger, .help: return Mosimo.danger
        case .supplies: return Mosimo.primary
        }
    }
    var symbol: String {
        switch self {
        case .safe: return "checkmark"
        case .caution: return "exclamationmark"
        case .danger: return "exclamationmark.triangle.fill"
        case .shelter: return "house.fill"
        case .blocked: return "nosign"
        case .supplies: return "shippingbox.fill"
        case .help: return "sos"
        }
    }
    static func symbol(_ c: Category?) -> String { c?.symbol ?? "bubble.left.fill" }
}

private let categoryPrefix = try! NSRegularExpression(pattern: "^\\s*[［\\[]([^］\\]]{1,8})[］\\]]\\s*")

extension PostView {
    /// Category from the text prefix.
    var category: Category? {
        let ns = content as NSString
        guard let m = categoryPrefix.firstMatch(in: content, range: NSRange(location: 0, length: ns.length)) else { return nil }
        return Category(rawValue: ns.substring(with: m.range(at: 1)))
    }
    /// The text without the category prefix.
    var body: String {
        guard category != nil else { return content }
        let ns = content as NSString
        return categoryPrefix.stringByReplacingMatches(in: content, range: NSRange(location: 0, length: ns.length), withTemplate: "")
    }
    /// A re-posted BSAF original (`original` present), not just any post carrying the extension.
    /// A re-post by an importer: a BSAF original or a JMA XML message (importer §1.1).
    var isPublic: Bool { ext.bsaf?.imported == true || ext.jma != nil }
    /// Card label of a re-post: the JMA Control/Title, else the BSAF type.
    var publicLabel: String { ext.jma?.title ?? bsafLabel(ext.bsaf?.type) }
    /// The confirmed sender name (attest.chain §4.3): shown only when the chain is confirmed now and the leaf may name itself.
    var senderName: String? { chain.flatMap { $0.identity && $0.confirmedNow ? $0.name : nil } }
    /// The post's own chain, confirmed now, ends in an importer authorized for its (Bot, type) (importer §3). Decided from
    /// the post alone, so relay, mesh and import show the same verdict; `publicAuthorization` is only the import-time record.
    var importerAuthorized: Bool { chain.map { $0.confirmedNow && $0.importAuthorized } == true }

    /// #priority colors: 3 urgent → danger, 2 important → warning, else neutral. Unauthorized is capped at 1 by protocol.
    var priorityColor: Color {
        switch importerAuthorized ? ext.priority : min(ext.priority, 1) {
        case 3: return Mosimo.danger
        case 2: return Mosimo.warning
        default: return Mosimo.textSecondary
        }
    }
    /// Event time: hazard `onset`, else BSAF `time:` (source event) when parseable, else Nostr created_at. Epoch seconds.
    var eventTime: Int64 {
        (ext.hazard?.onset.flatMap(Instant.parse) ?? ext.bsaf?.time.flatMap(Instant.parse))?.epochSecond ?? createdAt
    }

    /// The origin, else the centre of the first circular area (a point picked on the map is sent as geo#area).
    var coordinate: CLLocationCoordinate2D? {
        if let o = ext.origin { return CLLocationCoordinate2D(latitude: Double(o.lat) / 1e6, longitude: Double(o.lon) / 1e6) }
        for case let .circle(lat, lon, _) in ext.regions { return CLLocationCoordinate2D(latitude: Double(lat) / 1e6, longitude: Double(lon) / 1e6) }
        return nil
    }
    /// Meters from [here], or nil when either side has no position.
    func distance(from here: CLLocation?) -> Double? {
        guard let c = coordinate, let here else { return nil }
        return here.distance(from: CLLocation(latitude: c.latitude, longitude: c.longitude))
    }
    /// 「直接受信」「3ホップ」「オンライン経由」
    var routeLabel: String {
        if volatile { return "オンライン経由" }
        guard let hop, hop > 0 else { return "直接受信" }
        return "\(hop)ホップ"
    }
    /// BSAF `source:` tag, e.g. `jma` → 「気象庁」.
    var bsafSource: String {
        if ext.jma != nil { return "気象庁防災情報XML" }
        guard let s = ext.bsaf?.source, !s.isEmpty else { return "公的情報" }
        return s.lowercased() == "jma" ? "気象庁" : s
    }
    /// 「6/1 12:00 から・6/2 18:00 まで」 from the validity extension, independent of the delivery deadline.
    var validityLabel: String? {
        guard let v = ext.validity else { return nil }
        func t(_ s: String) -> String { Instant.parse(s).map { dateTime($0.epochSecond) } ?? s }
        let parts = [v.from.map { "\(t($0)) から" }, v.until.map { "\(t($0)) まで" }].compactMap { $0 }
        return parts.isEmpty ? "不明" : parts.joined(separator: "・")
    }
}

/// BSAF `type:` → short Japanese label; unknown types show the raw value.
private let bsafLabels = [
    "earthquake": "地震情報", "tsunami": "津波情報", "eruption": "噴火情報", "ashfall": "降灰予報", "nankai-trough": "南海トラフ地震情報",
    "heavy-rain": "大雨情報", "heavy-rain-warning": "大雨警報・注意報", "flood-warning": "洪水警報・注意報",
    "landslide-warning": "土砂災害警戒情報", "linear-rainband-warning": "線状降水帯情報", "storm-surge-warning": "高潮警報・注意報",
    "wave-warning": "波浪警報・注意報", "wind-warning": "暴風警報・強風注意報", "typhoon": "台風情報", "tornado-warning": "竜巻注意情報",
    "thunderstorm-warning": "雷注意報", "snow-warning": "大雪警報・注意報", "significant-snowfall-warning": "顕著な大雪情報",
    "avalanche-warning": "なだれ注意報", "snow-accretion-warning": "着雪注意報", "melting-snow-warning": "融雪注意報",
    "icing-warning": "着氷注意報", "frost-warning": "霜注意報", "low-temperature-warning": "低温注意報",
    "dry-air-warning": "乾燥注意報", "dense-fog-warning": "濃霧注意報", "other-warning": "その他の警報・注意報",
    "weather-info": "気象情報",
]
func bsafLabel(_ type: String?) -> String { type.map { bsafLabels[$0] ?? $0 } ?? "公的情報" }

private func format(_ pattern: String, _ epochSeconds: Int64) -> String {
    let f = DateFormatter()
    f.locale = Locale(identifier: "ja_JP")
    f.dateFormat = pattern
    return f.string(from: Date(timeIntervalSince1970: TimeInterval(epochSeconds)))
}
func hhmm(_ epochSeconds: Int64) -> String { format("HH:mm", epochSeconds) }
func dateTime(_ epochSeconds: Int64) -> String { format("M/d HH:mm", epochSeconds) }
func dayLabel(_ day: Date) -> String {
    let cal = Calendar.current
    let prefix = cal.isDateInToday(day) ? "きょう " : cal.isDateInYesterday(day) ? "きのう " : ""
    return prefix + format("M/d (E)", Int64(day.timeIntervalSince1970))
}

/// `did:plc:4k…9a`; falls back to the Nostr key when the commit carried no DID.
func shortDid(_ did: String?, _ pubkey: String) -> String {
    guard let did else { return "鍵 \(pubkey.prefix(6))…" }
    let rest = did.hasPrefix("did:plc:") ? String(did.dropFirst(8)) : did
    return "did:plc:\(rest.prefix(2))…\(rest.suffix(2))"
}
func distanceLabel(_ meters: Double) -> String {
    meters < 1000 ? "\(Int(meters / 10) * 10)m" : String(format: "%.1fkm", meters / 1000)
}
/// 「±12m」「±2.0km」
func accuracyLabel(_ meters: Double) -> String { meters < 1000 ? "±\(Int(meters))m" : String(format: "±%.1fkm", meters / 1000) }
func coordsLabel(_ lat: Double, _ lng: Double) -> String { String(format: "%.5f, %.5f", lat, lng) }
func micro(_ degrees: Double) -> Int64 { Int64((degrees * 1e6).rounded()) }

/// Our own post extension, not an official dev.jisaku.mosimo type (core §10.3; apps/lexicons/dev/jisaku/bosai/connectivity.json):
/// whether the OS has a network now. Every post this app publishes passes it explicitly.
func connectivity(online: Bool) -> [String: Cbor] { ["$type": .text("dev.jisaku.bosai.connectivity"), "online": .bool(online)] }

/// Annotation posts keyed by target event id.
func annotationsByTarget(_ posts: [PostView]) -> [String: [PostView]] {
    Dictionary(grouping: posts.filter { $0.ext.annotation?.targetEventId != nil }) { $0.ext.annotation!.targetEventId! }
}
/// Annotations whose target is present are nested under it, so they get no card of their own.
func standalone(_ posts: [PostView]) -> [PostView] {
    let ids = Set(posts.map(\.id))
    return posts.filter { $0.ext.annotation?.targetEventId.map { !ids.contains($0) } ?? true }
}
/// Distinct endorsing authors (DID, else Nostr key).
func endorsers(_ annotations: [PostView]) -> Set<String> {
    Set(annotations.filter { $0.ext.annotation?.relation == .endorsement }.map { $0.did ?? $0.pubkey })
}
/// Posts drawn on the map: those with a pin position ([mapPoint]), minus annotations whose target is shown (they live in its detail)
/// and official JMA re-posts a newer one of the same series replaces (jma §5.3; the timeline and details still show them).
@MainActor func mapPosts(_ posts: [PostView]) -> [PostView] {
    let ids = Set(posts.map(\.id))
    let replaced = JmaDisplay.superseded(posts.compactMap { p in p.officialHazard ? p.ext.jma.map { (p.id, $0) } : nil })
    return posts.filter { $0.mapPoint != nil && !replaced.contains($0.id) && !($0.ext.annotation?.targetEventId.map { ids.contains($0) } ?? false) }
}

/// Geohash anchors keyed by their cell list: tracing a prefecture cover is too slow to redo on every view update.
@MainActor private var anchorCache = [[String]: GeoPoint?]()
@MainActor private func cachedAnchor(_ hashes: [String]) -> GeoPoint? {
    if let hit = anchorCache[hashes] { return hit }
    if anchorCache.count > 512 { anchorCache.removeAll() }
    let a = geohashAnchor(hashes)
    anchorCache[hashes] = a
    return a
}

extension PostView {
    /// An official hazard notice: shown at every zoom and above ordinary pins. A user's own hazard claim is an ordinary post.
    var alert: Bool { ext.hazard.map { officialHazard && !$0.allClear } ?? false }
    /// An official all-clear: the danger is over, so it is an ordinary pin and its area is not drawn.
    var allClearNotice: Bool { ext.hazard.map { officialHazard && $0.allClear } ?? false }

    /// Web-mercator zoom from which the pin and area show: ordinary posts at [postMinZoom], official alerts at every zoom,
    /// except an earthquake with a shindo scale, whose reach follows its maximum shindo (a shindo 1 is a local event).
    var minZoom: Double {
        guard alert else { return postMinZoom }
        guard case .earthquake? = ext.hazard?.kind else { return 0 }
        let zooms = ext.hazard?.scales.filter { $0.scheme == "jp.jma.shindo" }.compactMap { shindoMinZoom[$0.value] } ?? []
        return zooms.min() ?? 0
    }

    /// Where a post's pin goes: its own position, else for a hazard the epicentre / volcano / cyclone centre it carries,
    /// else a point inside its area (the cell nearest the middle of a geohash union, or the first circle's centre).
    @MainActor var mapPoint: CLLocationCoordinate2D? {
        if let c = coordinate { return c }
        guard let h = ext.hazard else { return nil }
        func point(_ lat: Int64?, _ lon: Int64?) -> CLLocationCoordinate2D? {
            guard let lat, let lon else { return nil }
            return CLLocationCoordinate2D(latitude: Double(lat) / 1e6, longitude: Double(lon) / 1e6)
        }
        let kindPoint: CLLocationCoordinate2D?
        switch h.kind {
        case let .earthquake(_, hypo, _)?: kindPoint = point(hypo?.lat, hypo?.lon)
        case let .volcano(_, v)?: kindPoint = point(v?.lat, v?.lon)
        case let .cyclone(c)?: kindPoint = point(c.center?.lat, c.center?.lon)
        default: kindPoint = nil
        }
        if let kindPoint { return kindPoint }
        let hashes = ext.regions.compactMap { r -> String? in if case let .geohash(g) = r { return g } else { return nil } }
        if !hashes.isEmpty, let a = cachedAnchor(hashes) { return CLLocationCoordinate2D(latitude: a.lat, longitude: a.lng) }
        // coordinate already falls back to the first circle's centre.
        return nil
    }
}

/// `jp.jma.shindo` → the zoom an earthquake alert shows from; 5弱 and above show at every zoom. Unknown values are not listed.
private let shindoMinZoom: [String: Double] = ["0": 8, "1": 8, "2": 8, "3": 6, "4": 5, "5-": 0, "5+": 0, "6-": 0, "6+": 0, "7": 0]
/// Zooms at which the set of shown posts can change: the shindo steps and [postMinZoom].
let mapZoomSteps = Set(shindoMinZoom.values.filter { $0 > 0 } + [postMinZoom]).sorted()

private let severityRank = ["Extreme", "Severe", "Moderate", "Minor"]
/// Pin stack order: official alerts first (most severe, then newest), then ordinary posts newest first.
func pinOrder(_ a: PostView, _ b: PostView) -> Bool {
    func rank(_ p: PostView) -> (Int, Int) {
        guard p.alert else { return (1, severityRank.count) }
        return (0, p.ext.hazard.flatMap { severityRank.firstIndex(of: $0.severity) } ?? severityRank.count)
    }
    let ra = rank(a), rb = rank(b)
    return ra != rb ? ra < rb : a.createdAt > b.createdAt
}
