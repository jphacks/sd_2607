import Foundation

/// Node-side bookkeeping for deletion requests (`dev.jisaku.mosimo.delete`) and reports (`dev.jisaku.mosimo.report`).
/// A target stops being shown and forwarded once its author asked for deletion, or once [threshold] distinct
/// reporters reported it. The requests themselves are ordinary events and keep travelling. Not thread-safe.
public final class Moderation {
    public static let reportTypes: Set<String> = ["nudity", "malware", "profanity", "illegal", "spam", "impersonation", "other"]
    public static let maxTargets = 4096
    /// report §2: the reference implementations stop a target at two distinct reporters.
    public let threshold: Int
    /// Target event ID → pubkeys that asked for its deletion (applied only when the target's author asked).
    private var deletions = [String: Set<String>]()
    /// Target event ID → report event ID → reporter pubkey.
    private var reports = [String: [String: String]]()
    /// Reports withdrawn by their own author (delete §5).
    private var withdrawn = Set<String>()
    private var order = [String]()

    public init(threshold: Int = 2) { self.threshold = threshold }

    /// Records a deletion request or report; returns the target it concerns, nil for other events.
    @discardableResult
    public func absorb(_ event: NostrEvent) -> String? {
        if let d = AtRepository.deletion(event) {
            track(d.target)
            deletions[d.target, default: []].insert(event.pubkey)
            // delete §5: a report withdrawn by its reporter no longer counts.
            if d.kind == 1984 { withdrawn.insert(d.target + event.pubkey) }
            return d.target
        }
        if let r = AtRepository.report(event), r.author != event.pubkey {
            track(r.target)
            reports[r.target, default: [:]][event.id] = event.pubkey
            return r.target
        }
        return nil
    }

    /// The target's author asked for its deletion.
    public func deleted(_ event: NostrEvent) -> Bool { deletions[event.id]?.contains(event.pubkey) == true }

    /// Distinct reporters of [id] (the target's author never counts; withdrawn reports do not count).
    public func reporters(_ id: String, author: String? = nil) -> Int {
        Set((reports[id] ?? [:]).filter { !withdrawn.contains($0.key + $0.value) }.values).subtracting(author.map { [$0] } ?? []).count
    }

    /// Shown and forwarded no more: deleted by its author or reported by at least [threshold] reporters.
    public func stopped(_ event: NostrEvent) -> Bool { deleted(event) || reporters(event.id, author: event.pubkey) >= threshold }

    /// Not worth requesting from peers. Pending deletions are not trusted before the target's author is known.
    public func skip(_ id: String) -> Bool { reporters(id) >= threshold }

    private func track(_ id: String) {
        if deletions[id] == nil && reports[id] == nil { order.append(id) }
        while order.count > Self.maxTargets { let old = order.removeFirst(); deletions[old] = nil; reports[old] = nil }
    }
}
