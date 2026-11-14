import Foundation

/// Local notification of important hazard information from other senders (same rules as Android). Decisions only;
/// the app delivers them and keeps the notified ids across restarts.
public enum HazardAlerts {
    /// What a decision needs from a post. [official]: the app's `officialHazard` (chain confirmed now, ending in
    /// public-information or an authorized importer), decided from the post alone. [danger]: the text kind 「［危険］」.
    public struct Candidate: Equatable, Sendable {
        public let id: String, pubkey: String
        /// Nostr created_at, seconds.
        public let createdAt: Int64
        public let official: Bool
        public let danger: Bool
        public let ext: PostExtensions
        public init(id: String, pubkey: String, createdAt: Int64, official: Bool, danger: Bool = false, ext: PostExtensions) {
            self.id = id; self.pubkey = pubkey; self.createdAt = createdAt; self.official = official; self.danger = danger; self.ext = ext
        }
    }

    /// Notification kinds, each with its own switch.
    public enum Genre: String, CaseIterable, Sendable {
        /// Official notices by their type-specific extension: earthquake; weather and cyclone; the rest (tsunami, volcano, none).
        case earthquake, weather, otherOfficial
        /// A user's 「［危険］」 post near here; unverified.
        case nearbyDanger
    }
    public struct Alert: Equatable, Sendable {
        public let post: Candidate
        public let genre: Genre
    }

    /// Older official posts are not news: a first sync can bring a long backlog.
    public static let maxAgeSeconds: Int64 = 6 * 3600
    /// A user's danger report is about the moment.
    public static let dangerMaxAgeSeconds: Int64 = 3600
    /// A user's danger report counts when its origin, or an area element or its centre, is this close to here.
    public static let dangerNearMeters: Double = 2000
    /// Alerts arriving within this time of a notification's first one are folded into it (「ほかN件」).
    public static let batchMillis: Int64 = 30_000

    /// The posts of [posts] to notify now, official Extreme first, other official, then danger reports; newest first within.
    /// All: not ours ([me]), not in [notified], not replaced by a newer post of its series (jma §5.3).
    /// Official (genres A–C): `Actual`, no all-clear, at most [maxAgeSeconds] old; then, with [here] inside its area,
    /// `Extreme`/`Severe`/`Moderate`; outside, never; with no area, [here] unknown or the containment unknown (geo §4.2),
    /// `Extreme` only.
    /// Danger (genre D): not official, at most [dangerMaxAgeSeconds] old, [here] known and [near] it.
    public static func due(_ posts: [Candidate], me: String?, here: GeoPoint?, nowSeconds: Int64, notified: Set<String>) -> [Alert] {
        let fresh = posts.compactMap { p -> Alert? in
            guard !notified.contains(p.id), p.pubkey != me else { return nil }
            if p.official, let h = p.ext.hazard {
                guard h.status == "Actual", !h.allClear, p.createdAt >= nowSeconds - maxAgeSeconds else { return nil }
                switch here.flatMap({ contains(p.ext.areas, $0) }) {
                case true?: guard ["Extreme", "Severe", "Moderate"].contains(h.severity) else { return nil }
                case false?: return nil
                case nil: guard h.severity == "Extreme" else { return nil }
                }
                return Alert(post: p, genre: genre(h))
            }
            guard !p.official, p.danger, p.createdAt >= nowSeconds - dangerMaxAgeSeconds, let here, near(p.ext, here) else { return nil }
            return Alert(post: p, genre: .nearbyDanger)
        }
        guard !fresh.isEmpty else { return [] }
        // The replacement looks at every official post, already notified or not, as the map does.
        let replaced = JmaDisplay.superseded(posts.compactMap { p in p.official ? p.ext.jma.map { (p.id, $0) } : nil })
        func rank(_ a: Alert) -> Int { a.genre == .nearbyDanger ? 2 : a.post.ext.hazard?.severity == "Extreme" ? 0 : 1 }
        return fresh.filter { !replaced.contains($0.post.id) }.sorted { a, b in
            rank(a) != rank(b) ? rank(a) < rank(b) : a.post.createdAt > b.post.createdAt
        }
    }

    public enum Permission: Sendable { case granted, refused, unknown }
    /// Which [alerts] to show and which to record as notified. A switched-off genre, or a refused permission, records
    /// without showing, so a later opt-in does not ring for all of them at once; while the permission is still unknown,
    /// alerts of switched-on genres are left for later.
    public static func split(_ alerts: [Alert], on: Set<Genre>, permission: Permission) -> (show: [Alert], record: [Alert]) {
        let show = permission == .granted ? alerts.filter { on.contains($0.genre) } : []
        return (show, alerts.filter { !on.contains($0.genre) || permission != .unknown })
    }

    static func genre(_ h: Hazard) -> Genre {
        switch h.kind {
        case .earthquake?: return .earthquake
        case .weather?, .cyclone?: return .weather
        case .tsunami?, .volcano?, nil: return .otherOfficial
        }
    }

    /// Within [dangerNearMeters] of [here]: the origin, an area element containing it, or an element's centre.
    static func near(_ ext: PostExtensions, _ here: GeoPoint) -> Bool {
        func close(_ lat: Double, _ lng: Double) -> Bool { distanceMeters(GeoPoint(lat: lat, lng: lng), here) <= dangerNearMeters }
        if let o = ext.origin, close(Double(o.lat) / 1e6, Double(o.lon) / 1e6) { return true }
        return ext.regions.contains { r in
            if contains(r, here) { return true }
            switch r {
            case let .circle(lat, lon, _): return close(Double(lat) / 1e6, Double(lon) / 1e6)
            case let .geohash(h): return geohashBounds(h).map { close(($0.0 + $0.2) / 2, ($0.1 + $0.3) / 2) } ?? false
            }
        }
    }

    /// geo §4.2 over the union of [areas]: true inside a readable element; false only when every element was read and none
    /// contains [p]; nil (unknown) otherwise, including a post with no area.
    public static func contains(_ areas: [Area], _ p: GeoPoint) -> Bool? {
        let regions = areas.flatMap(\.regions)
        if regions.contains(where: { contains($0, p) }) { return true }
        return regions.isEmpty || areas.contains { !$0.complete } ? nil : false
    }

    public static func contains(_ region: Region, _ p: GeoPoint) -> Bool {
        switch region {
        case let .circle(lat, lon, r):
            return distanceMeters(GeoPoint(lat: Double(lat) / 1e6, lng: Double(lon) / 1e6), p) <= Double(r) / 1000
        case let .geohash(h):
            guard let (s, w, n, e) = geohashBounds(h) else { return false }
            return (s...n).contains(p.lat) && (w...e).contains(p.lng)
        }
    }

    /// Great-circle distance on the mean Earth radius.
    static func distanceMeters(_ a: GeoPoint, _ b: GeoPoint) -> Double {
        let rad = Double.pi / 180
        let dLat = (b.lat - a.lat) * rad, dLng = (b.lng - a.lng) * rad
        let h = pow(sin(dLat / 2), 2) + cos(a.lat * rad) * cos(b.lat * rad) * pow(sin(dLng / 2), 2)
        return 2 * 6_371_008.8 * asin(min(1, sqrt(h)))
    }

    /// One shown notification and the alerts folded into it.
    public struct Batch: Equatable, Sendable {
        /// Notification identifier: re-adding it replaces the shown notification.
        public let id: String
        public let startedAtMillis: Int64
        /// The post the notification names and opens.
        public let primary: String
        public let count: Int
        public var others: Int { count - 1 }
    }

    /// Folds [ids] (non-empty, in `due` order) into [current] while it is under [batchMillis] old, else starts a new one.
    public static func fold(_ current: Batch?, _ ids: [String], nowMillis: Int64) -> Batch {
        if let c = current, nowMillis - c.startedAtMillis < batchMillis {
            return Batch(id: c.id, startedAtMillis: c.startedAtMillis, primary: c.primary, count: c.count + ids.count)
        }
        return Batch(id: ids[0], startedAtMillis: nowMillis, primary: ids[0], count: ids.count)
    }

    /// Notified ids (to created_at) still young enough to come up again; older ones can never be due ([maxAgeSeconds] is the longest).
    public static func prune(_ notified: [String: Int64], nowSeconds: Int64) -> [String: Int64] {
        notified.filter { $0.value >= nowSeconds - maxAgeSeconds }
    }

    /// The first [lines] non-blank lines of [text], trimmed.
    public static func excerpt(_ text: String, lines: Int = 3) -> String {
        text.split(whereSeparator: \.isNewline).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
            .prefix(lines).joined(separator: "\n")
    }
}
