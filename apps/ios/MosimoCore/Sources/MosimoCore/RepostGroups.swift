import Foundation

/// public §8.1: authorized re-posts that likely tell of the same event, shown and notified as one group. Display and
/// notification only: never used for delivery, deletion, replacement or effective priority, since the conditions prove no identity.
public enum RepostGroups {
    /// What grouping needs from a post. Callers pass only authorized re-posts (importer §3).
    public struct Post: Equatable, Sendable {
        public let id: String
        /// Nostr created_at, seconds.
        public let createdAt: Int64
        public let ext: PostExtensions
        public init(id: String, createdAt: Int64, ext: PostExtensions) { self.id = id; self.createdAt = createdAt; self.ext = ext }
    }
    /// One group of two or more posts: [representative] stands for it on the map and in lists; [members] (all, the
    /// representative included) in detail order, feed re-posts before Bot re-posts.
    public struct Group: Equatable, Sendable {
        public let representative: String
        public let members: [String]
    }

    public static let onsetSeconds: Int64 = 60
    public static let weatherSeconds: Int64 = 30 * 60

    /// `Extreme` > `Severe` > `Moderate` > `Minor` > `Unknown`; unknown values rank as `Unknown`.
    public static func severity(_ value: String?) -> Int { value.flatMap { ["Minor", "Moderate", "Severe", "Extreme"].firstIndex(of: $0) }.map { $0 + 1 } ?? 0 }
    static func feed(_ e: PostExtensions) -> Bool { e.feed != nil && e.bsaf == nil }
    static func bot(_ e: PostExtensions) -> Bool { e.bsaf?.imported == true && e.feed == nil }

    /// Whether [x] and [y] are directly related (public §8.1); symmetric.
    public static func related(_ x: Post, _ y: Post) -> Bool {
        guard let hx = x.ext.hazard, let hy = y.ext.hazard, hx.status == hy.status, hx.allClear == hy.allClear else { return false }
        switch (hx.kind, hy.kind) {
        case let (.earthquake(kx, _, _)?, .earthquake(ky, _, _)?):
            // Any route and any region: JMA's per-prefecture posts of one quake join too.
            guard kx == "event", ky == "event", let tx = hx.onset.flatMap(Instant.parse), let ty = hy.onset.flatMap(Instant.parse) else { return false }
            return abs(tx.epochSecond - ty.epochSecond) <= onsetSeconds
        case let (.weather(px)?, .weather(py)?):
            // One feed re-post and one Bot re-post: posts of one feed differ by region (series replacement handles updates).
            guard (feed(x.ext) && bot(y.ext)) || (bot(x.ext) && feed(y.ext)) else { return false }
            return !Set(px).isDisjoint(with: py) && abs(x.createdAt - y.createdAt) <= weatherSeconds && overlap(x.ext, y.ext)
        default:
            return false
        }
    }

    /// The groups of [posts]: connected components of [related] with two or more members.
    public static func groups(_ posts: [Post]) -> [Group] {
        let candidates = posts.filter { p in
            switch p.ext.hazard?.kind { case .earthquake?, .weather?: return true; default: return false }
        }
        var parent = Array(candidates.indices)
        func find(_ i: Int) -> Int { var i = i; while parent[i] != i { parent[i] = parent[parent[i]]; i = parent[i] }; return i }
        for i in candidates.indices { for j in candidates.indices where j > i && find(i) != find(j) && related(candidates[i], candidates[j]) { parent[find(j)] = find(i) } }
        return Dictionary(grouping: candidates.indices, by: find).values.filter { $0.count > 1 }.map { indices in
            let members = indices.map { candidates[$0] }
            return Group(representative: representativeOrder(members)[0].id,
                         members: members.sorted { a, b in
                             feed(a.ext) != feed(b.ext) ? feed(a.ext) : before(a, b)
                         }.map(\.id))
        }.sorted { $0.representative < $1.representative }
    }

    /// Highest severity first, then a feed re-post, then the newest.
    public static func representativeOrder(_ posts: [Post]) -> [Post] {
        posts.sorted { a, b in
            let sa = severity(a.ext.hazard?.severity), sb = severity(b.ext.hazard?.severity)
            if sa != sb { return sa > sb }
            if feed(a.ext) != feed(b.ext) { return feed(a.ext) }
            return before(a, b)
        }
    }
    private static func before(_ a: Post, _ b: Post) -> Bool {
        let sa = severity(a.ext.hazard?.severity), sb = severity(b.ext.hazard?.severity)
        return sa != sb ? sa > sb : a.createdAt != b.createdAt ? a.createdAt > b.createdAt : a.id < b.id
    }

    /// geo#area: one geohash cell of one post is a prefix of a cell of the other. Posts without cells never overlap.
    static func overlap(_ a: PostExtensions, _ b: PostExtensions) -> Bool {
        func cells(_ e: PostExtensions) -> [String] { e.regions.compactMap { if case let .geohash(h) = $0 { return h } else { return nil } } }
        let cb = cells(b)
        return cells(a).contains { x in cb.contains { y in x.hasPrefix(y) || y.hasPrefix(x) } }
    }
}
