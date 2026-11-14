import Foundation

/// Receiver-side authorization of importer re-posts (`attest.importer` §3) before they enter the mesh from the
/// Internet. Trust comes only from the local [Config]; a relay's or server's own verification claims are ignored.
public enum PublicIngress {
    public static let bsaf = "dev.jisaku.mosimo.public.bsaf"
    public static let jmaEarthquake = "dev.jisaku.mosimo.public.jma.earthquake"
    public static let jmaWeather = "dev.jisaku.mosimo.public.jma.weather"
    /// public §5.1: the source extension; its `feed` declares a feed re-post (importer §1.2).
    public static let sourceType = "dev.jisaku.mosimo.public#source"
    /// importer §1.1, jma §12: the feed of re-posted JMA disaster-prevention XML; no origin, its types are the original `Control/Title`.
    public static let jmaFeed = "dev.jisaku.mosimo.public.jma"
    public static let priorityType = "dev.jisaku.mosimo.priority"
    public static let maxRoots = 8
    public static let maxSelected = 64

    /// A re-posted (botDid, type) or (feed, origin, type) pair (importer §1), or one entry of the local selection (§3):
    /// exactly one of [botDid] and [feed] is set. As a post's pair, [type] is always set and a nil [origin] means the post
    /// has none; as a selection entry of a feed, a nil [origin] or [type] selects every issuer or type (importer §1.3).
    public struct Selection: Hashable, Sendable {
        public let botDid: String?, feed: String?, origin: String?
        public let type: String?
        public init(botDid: String, type: String) { self.botDid = botDid; feed = nil; origin = nil; self.type = type }
        public init(feed: String, origin: String? = nil, type: String? = nil) { botDid = nil; self.feed = feed; self.origin = origin; self.type = type }
        public var description: String { botDid.map { "bot=\($0)" } ?? "feed=\(feed ?? "")\(origin.map { " origin=\($0)" } ?? "")" }
    }
    /// importer §3, §1.3: whether the local selection [entries] holds a post's [pair].
    public static func covers(_ entries: Set<Selection>, _ pair: Selection) -> Bool {
        if pair.botDid != nil { return entries.contains(pair) }
        guard let feed = pair.feed else { return false }
        return entries.contains { e in
            e.feed == feed && (e.origin == nil || (pair.origin != nil && e.origin == pair.origin)) && (e.type == nil || (pair.type != nil && e.type == pair.type))
        }
    }
    public struct Config: Sendable {
        public var roots: [Attest.Root] = []
        public var selected: Set<Selection> = []
        public init(roots: [Attest.Root] = [], selected: Set<Selection> = []) { self.roots = roots; self.selected = selected }
    }

    /// Verified revocations held locally, keyed by subject key (hex). Never rolled back.
    public final class RevocationStore: @unchecked Sendable {
        public static let maxRevocations = 4096
        private var bySubject = [String: [Attest.Revocation]]()
        private var order = [String]()
        public init(_ initial: [Attest.Revocation] = []) { for r in initial { _ = try? add(r) } }
        @discardableResult
        public func add(_ r: Attest.Revocation) throws -> Bool {
            let key = hex(r.subject)
            if bySubject[key]?.contains(where: { $0.sameAs(r) }) == true { return false }
            try require(size < Self.maxRevocations, "Revocation store full")
            if bySubject[key] == nil { order.append(key) }
            bySubject[key, default: []].append(r)
            return true
        }
        public func of(_ subject: [UInt8]) -> [Attest.Revocation] { bySubject[hex(subject)] ?? [] }
        public func all() -> [Attest.Revocation] { order.flatMap { bySubject[$0] ?? [] } }
        public var size: Int { bySubject.values.reduce(0) { $0 + $1.count } }
    }

    /// What the UI shows about a post's sender confirmation (attest.chain §4.3).
    public struct ChainView: Equatable, Sendable {
        public let state: Attest.ChainState
        public let expiredNow: Bool?
        public let revocation: Attest.RevocationState?
        public let name: String?, orgId: String?
        public let roles: [String]
        public let trustLevel: Int64?
        public let reason: String
        /// importer §3 without the mesh-insertion conditions: an `import` leaf with an organization whose scope covers the post's
        /// (Bot DID, type) or (feed, origin, type), and that pair locally selected. Decided from the post alone, however it arrived.
        public let importAuthorized: Bool
        public var identity: Bool { roles.contains(Attest.roleIdentity) }
        public var publicInformation: Bool { roles.contains(Attest.rolePublicInformation) }
        public var confirmedNow: Bool { state == .verified && expiredNow == false && revocation == .notRevokedAsKnown }
    }

    /// Authorization record kept with an inserted post (importer §3): never "official", only who re-posted what.
    public struct Receipt: Sendable, CustomStringConvertible {
        public let rootPubkey, leafId, orgId, orgName: String
        public let source: Selection
        public let priority: Int
        public let checkedAt: String
        public let leafValidUntil: String?
        public var description: String {
            "root=\(rootPubkey.prefix(8)) leaf=\(leafId.prefix(8)) org=\(orgName)(\(orgId)) \(source.description) type=\(source.type ?? "") priority=\(priority) checked=\(checkedAt)"
        }
    }
    public struct Decision: Sendable { public let receipt: Receipt?; public let chain: ChainView?; public let reason: String }

    public static func config(_ json: String) throws -> Config {
        let node = try Json.parse(json, maxDepth: 16, maxBytes: CanonicalCbor.maxBytes)
        try require(!node.containsFloat() && node.keys == ["roots", "selected"], "Unexpected/missing JSON fields")
        guard let rootNodes = node["roots"]?.array, rootNodes.count <= maxRoots else { throw CoreError("Invalid roots") }
        let roots: [Attest.Root] = try rootNodes.map { r in
            try require(r.keys == ["pubkey", "name", "roles", "maxDepth", "enabled"], "Unexpected/missing JSON fields")
            guard let key = r["pubkey"]?.string, let name = r["name"]?.string, let roles = r["roles"]?.array, roles.count <= 32,
                  let depth = r["maxDepth"]?.int, let enabled = r["enabled"]?.bool else { throw CoreError("Invalid root") }
            return try Attest.Root(pubkey: try unhex(key), name: name, roles: Set(try roles.map { guard let s = $0.string else { throw CoreError("Invalid role") }; return s }),
                                   maxDepth: Int(depth), enabled: enabled)
        }
        try require(Set(roots.map(\.pubkeyHex)).count == roots.count, "Duplicate root")
        guard let selectedNodes = node["selected"]?.array, selectedNodes.count <= maxSelected else { throw CoreError("Invalid selected") }
        // Each element names a Bot or a feed: {"botDid", "type"} or {"feed", "origin"?, "type"?} (importer §1.3: an absent origin
        // or type selects every issuer or type of the feed).
        let selected: [Selection] = try selectedNodes.map { s in
            func text(_ key: String) throws -> String? {
                guard let v = s[key] else { return nil }
                guard let t = v.string, (1...128).contains(t.utf8.count) || key != "type" else { throw CoreError("Invalid selection") }
                return t
            }
            if s.keys.contains("feed") {
                try require(s.keys.isSubset(of: ["feed", "origin", "type"]), "Unexpected/missing JSON fields")
                guard let feed = try text("feed"), Attest.isFeedId(feed), feed != bsaf else { throw CoreError("Invalid feed ID") }
                let origin = try text("origin")
                try require(origin.map(Attest.isOrigin) ?? true, "Invalid feed origin")
                return Selection(feed: feed, origin: origin, type: try text("type"))
            }
            try require(s.keys == ["botDid", "type"], "Unexpected/missing JSON fields")
            guard let type = try text("type"), let did = s["botDid"]?.string, matches(AtRepository.didPattern, did) else { throw CoreError("Invalid selection") }
            return Selection(botDid: did, type: type)
        }
        try require(Set(selected).count == selected.count, "Duplicate selection")
        return Config(roots: roots, selected: Set(selected))
    }

    /// Where a trusted root came from: shipped with the app, shipped only in development builds, or added by the user.
    public enum RootOrigin: Sendable { case builtin, test, user }
    public struct RootEntry: Equatable, Sendable {
        public let root: Attest.Root
        public let origin: RootOrigin
        public init(root: Attest.Root, origin: RootOrigin) { self.root = root; self.origin = origin }
    }

    /// The user's local trust choices: roots switched off (by key, hex) and roots added by hand. Kept apart from the shipped
    /// config so an app update can change its roots without losing them. Never filled from received data (attest §3).
    public struct TrustChoices: Equatable, Sendable {
        public struct Added: Equatable, Sendable {
            public let pubkey, name: String
            public init(pubkey: String, name: String) { self.pubkey = pubkey; self.name = name }
        }
        public var disabled: Set<String> = []
        public var added: [Added] = []
        public init(disabled: Set<String> = [], added: [Added] = []) { self.disabled = disabled; self.added = added }

        public func toJson() -> String {
            Json.object([("disabled", .array(disabled.sorted().map { .string($0) })),
                         ("added", .array(added.map { .object([("pubkey", .string($0.pubkey)), ("name", .string($0.name))]) }))]).serialized
        }
        /// Anything unreadable gives the empty choices, as on Android.
        public static func parse(_ json: String?) -> TrustChoices {
            guard let json, let n = try? Json.parse(json), let d = n["disabled"]?.array, let a = n["added"]?.array else { return TrustChoices() }
            let disabled = d.compactMap(\.string), added = a.compactMap { e in e["pubkey"]?.string.flatMap { k in e["name"]?.string.map { Added(pubkey: k, name: $0) } } }
            guard disabled.count == d.count, added.count == a.count else { return TrustChoices() }
            return TrustChoices(disabled: Set(disabled), added: added)
        }
    }
    /// Roles and depth given to a user-added root: it may certify importers and public-information issuers below it, up to
    /// the receivers' maximum delegation depth (attest §5), so the issuer's own chain decides its shape.
    public static let userRootRoles: Set<String> = [Attest.roleImport, Attest.rolePublicInformation, "attest"]
    public static let userRootDepth = 16

    private static let rootKeyPattern = try! NSRegularExpression(pattern: "^[0-9a-f]{64}$")
    /// A root key typed by the user: 64 hex digits (x-only, as in Nostr and the attest chain).
    public static func rootKey(_ text: String) -> String? {
        let key = text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard matches(rootKeyPattern, key), let bytes = try? unhex(key, 32), (try? Attest.publicKey(.bytes(bytes))) != nil else { return nil }
        return key
    }

    /// The config in force: the shipped [builtin] and, in development builds, [test] roots, plus user-added ones, each
    /// switched by [choices]. Selections come from the shipped configs. A key listed twice keeps its first origin.
    public static func effective(_ builtin: Config, _ test: Config?, _ choices: TrustChoices) -> (Config, [RootEntry]) {
        var entries = [RootEntry]()
        func add(_ root: Attest.Root, _ origin: RootOrigin) {
            if !entries.contains(where: { $0.root.pubkeyHex == root.pubkeyHex }) { entries.append(RootEntry(root: root, origin: origin)) }
        }
        builtin.roots.forEach { add($0, .builtin) }
        test?.roots.forEach { add($0, .test) }
        for a in choices.added {
            // Same cut as Kotlin's take(64): 64 UTF-16 units, so the name always fits the root's byte limit.
            guard let key = rootKey(a.pubkey), let bytes = try? unhex(key, 32),
                  let root = try? Attest.Root(pubkey: bytes, name: String(decoding: Array(a.name.utf16.prefix(64)), as: UTF16.self),
                                              roles: userRootRoles, maxDepth: userRootDepth) else { continue }
            add(root, .user)
        }
        let list: [RootEntry] = entries.prefix(maxRoots).compactMap { e in
            let r = e.root
            return (try? Attest.Root(pubkey: r.pubkey, name: r.name, roles: r.roles, maxDepth: r.maxDepth,
                                     enabled: r.enabled && !choices.disabled.contains(r.pubkeyHex))).map { RootEntry(root: $0, origin: e.origin) }
        }
        return (Config(roots: list.map(\.root), selected: builtin.selected.union(test?.selected ?? [])), list)
    }

    /// priority §2: exactly one valid extension gives the hint; anything else is level 1. Never raised by itself.
    public static func priority(_ event: NostrEvent) -> Int {
        guard let ext = try? AtRepository.extensions(event) else { return 1 }
        let p = ext.filter { $0["$type"] == .text(priorityType) }
        guard p.count == 1, let level = p[0]["level"]?.int, (0...3).contains(level) else { return 1 }
        return Int(level)
    }

    /// priority §2: the author's hint when the sender is authorized now (a confirmed chain; for an `import` leaf, only a
    /// locally authorized re-post), otherwise at most 1. Independent of how the event arrived.
    public static func effectivePriority(_ event: NostrEvent, _ view: ChainView?) -> Int {
        let hint = priority(event)
        let authorized = view.map { $0.confirmedNow && ($0.importAuthorized || !$0.roles.contains(Attest.roleImport)) } ?? false
        return authorized ? hint : min(hint, 1)
    }

    /// Parsed BSAF extension of an import candidate: the original post's author DID and the `type:` tag value.
    public struct BsafOriginal: Sendable { public let botDid, type, uri, cid: String }
    private static let originalUri = try! NSRegularExpression(pattern: "^at://did:plc:[a-z2-7]{24}/app\\.bsky\\.feed\\.post/[A-Za-z0-9_~.:-]{1,512}$")
    public static func bsafOriginal(_ event: NostrEvent) throws -> BsafOriginal {
        let all = try AtRepository.extensions(event).filter { $0["$type"] == .text(bsaf) }
        try require(all.count == 1, "Exactly one bsaf extension required")
        let b = all[0]
        try require(b.keys.allSatisfy { ["$type", "tags", "langs", "original", "botDefinition"].contains($0) })
        guard let tagList = b["tags"]?.array else { throw CoreError("tags") }
        let tags = try tagList.map { v in let t = try Attest.text(v); try require(t.utf8.count <= 640); return t }
        try require(tags.count <= 8 && tags.contains("bsaf:v1"))
        let types = tags.filter { $0.hasPrefix("type:") }
        guard types.count == 1, case let type = String(types[0].dropFirst(5)), !type.isEmpty else { throw CoreError("type tag") }
        guard let langs = b["langs"]?.array, langs.count <= 3 else { throw CoreError("langs") }
        for l in langs { _ = try Attest.text(l) }
        guard let originalValue = b["original"] else { throw CoreError("original required for imports") }
        let original = try Attest.map(originalValue)
        try require(Set(original.keys) == ["uri", "cid", "createdAt"])
        let uri = try Attest.text(original["uri"])
        try require(matches(originalUri, uri), "Invalid original URI")
        let cidText = try Attest.text(original["cid"]); _ = try cidBytes(cidText, allowRaw: true)
        _ = try Attest.text(original["createdAt"])
        if let v = b["botDefinition"] {
            let d = try Attest.map(v)
            try require(d.keys.contains("url") && d.keys.allSatisfy { ["url", "retrievedAt", "sha256"].contains($0) })
            let urlText = try Attest.text(d["url"])
            try require(urlText.count <= 2048)
            let url = URLComponents(string: urlText)
            try require(["https", "http"].contains(url?.scheme?.lowercased() ?? "") && !(url?.host ?? "").isEmpty, "Invalid bot definition URL")
            if let r = d["retrievedAt"] { _ = try Attest.time(r) }
            if let h = d["sha256"] { _ = try Attest.bytes(h, 32) }
        }
        let did = String(uri.dropFirst(5).prefix { $0 != "/" })
        return BsafOriginal(botDid: did, type: type, uri: uri, cid: cidText)
    }

    /// The re-posted pair of [event] (importer §1.2, §2): a Bot re-post has exactly one `public.bsaf` with `original` and gives
    /// (botDid, type); a feed re-post has no `public.bsaf` and exactly one `public#source` declaring `feed: {id, origin?, type}`.
    /// A declaration of a feed this app knows (`dev.jisaku.mosimo.public.jma`) must also match the content; an unknown feed is
    /// taken as declared. Throws for anything else.
    public static func importPair(_ event: NostrEvent) throws -> Selection {
        let ext = try AtRepository.extensions(event)
        let sources = ext.filter { $0["$type"] == .text(sourceType) }
        if ext.contains(where: { $0["$type"] == .text(bsaf) }) {
            try require(!sources.contains { $0["feed"] != nil }, "A BSAF re-post with a feed declaration")
            let o = try bsafOriginal(event)
            return Selection(botDid: o.botDid, type: o.type)
        }
        try require(sources.count == 1, "Expected exactly one BSAF extension or feed source")
        guard let value = sources[0]["feed"] else { throw CoreError("Feed re-post without a feed declaration") }
        let declared = try Attest.map(value)
        try require(declared["id"] != nil && declared["type"] != nil && declared.keys.allSatisfy { ["id", "origin", "type"].contains($0) },
                    "Unexpected feed declaration fields")
        let id = try Attest.text(declared["id"])
        try require(Attest.isFeedId(id) && id != bsaf, "Invalid feed ID")
        let origin = try declared["origin"].map { try Attest.text($0) }
        try require(origin.map(Attest.isOrigin) ?? true, "Invalid feed origin")
        let type = try Attest.text(declared["type"])
        try require((1...128).contains(type.utf8.count), "Invalid feed type")
        if id == jmaFeed {
            let jma = ext.filter { $0["$type"] == .text(jmaEarthquake) || $0["$type"] == .text(jmaWeather) }
            try require(jma.count == 1 && origin == nil && jma[0]["message"]?["control"]?["title"]?.text == type,
                        "Feed declaration does not match the JMA extension")
        }
        return Selection(feed: id, origin: origin, type: type)
    }

    /// Evaluates every chain of [event] against [roots]; the first chain whose leaf belongs to the event decides.
    /// [now] nil means the clock is untrusted: the result is unknownTime, never verified.
    public static func chainView(_ event: NostrEvent, roots: [Attest.Root], revocations: RevocationStore, now: Instant?, seenAt: Instant,
                                 selected: Set<Selection> = []) -> ChainView? {
        let chains: [Attest.Chain]
        do { chains = try Attest.chains(event) } catch {
            return ChainView(state: .invalid, expiredNow: nil, revocation: nil, name: nil, orgId: nil, roles: [], trustLevel: nil, reason: "malformed chain: \(error)",
                             importAuthorized: false)
        }
        if chains.isEmpty { return nil }
        let createdAt = Instant(epochSecond: event.createdAt)
        let pair = (try? importPair(event)).flatMap { covers(selected, $0) ? $0 : nil }
        var best: ChainView?
        for chain in chains {
            let leaf = chain.attestations.last!
            let result: Attest.ChainResult
            do {
                try Attest.bindLeaf(leaf, chain.binding, event)
                result = Attest.verifyChain(chain.attestations, roots: roots, createdAt: createdAt, now: now)
            } catch { result = Attest.ChainResult(state: .invalid, reason: "leaf: \(error)") }
            let revocation = result.root != nil ? Attest.revocationState(result, known: revocations.of, createdAt: createdAt, seenAt: seenAt) : nil
            let imported = pair.map { p in
                result.state == .verified && leaf.roles.contains(Attest.roleImport) && leaf.orgId != nil && leaf.importerBlock?.covers(p) == true
            } ?? false
            let view = ChainView(state: result.state, expiredNow: result.expiredNow, revocation: revocation, name: result.leaf?.displayName,
                                 orgId: result.leaf?.orgId, roles: result.leaf?.roles ?? [], trustLevel: result.leaf?.trustLevel, reason: result.reason,
                                 importAuthorized: imported)
            if view.confirmedNow { return view }
            if best.map({ view.state.rawValue > $0.state.rawValue }) ?? true { best = view }
        }
        return best
    }

    /// importer §3: local selection, a chain verified at both creation and now whose leaf has the `import` role, an
    /// `importer` block covering (botDid, type) or (feed, origin, type) and an `organization`, and no known revocation.
    public static func verify(_ event: NostrEvent, _ config: Config, _ revocations: RevocationStore, _ now: Instant?, seenAt: Instant? = nil) -> Decision {
        do {
            try require(event.tags.count <= 64 && event.tags.filter { $0.first?.hasPrefix(AtRepository.extPrefix) == true }.count <= 16, "Event resource limit")
            try require(event.verify(), "Invalid author signature")
            try require(try EventCodec.encode(event).count <= MeshPolicy.maxPayloadBytes && AtRepository.verifyEnvelope(event), "Invalid envelope/resource limit")
            let pair = try importPair(event)
            try require(covers(config.selected, pair), "Bot/feed type not selected")
            guard let now else { throw CoreError("Clock unknown") }
            let seen = seenAt ?? now
            let createdAt = Instant(epochSecond: event.createdAt)
            try require(createdAt <= now && (AtRepository.deliveryExpiresAt(event).map { $0 > now } ?? false), "Future event or past deadline")
            let chains = try Attest.chains(event)
            try require(!chains.isEmpty, "No attestation chain")
            var view: ChainView?
            var failure = "No chain authorizes the import"
            for chain in chains {
                let leaf = chain.attestations.last!
                do { try Attest.bindLeaf(leaf, chain.binding, event) } catch { failure = "leaf: \(error)"; continue }
                let result = Attest.verifyChain(chain.attestations, roots: config.roots, createdAt: createdAt, now: now)
                let revocation = result.root.map { _ in Attest.revocationState(result, known: revocations.of, createdAt: createdAt, seenAt: seen) }
                view = ChainView(state: result.state, expiredNow: result.expiredNow, revocation: revocation, name: leaf.displayName, orgId: leaf.orgId,
                                 roles: leaf.roles, trustLevel: leaf.trustLevel, reason: result.reason, importAuthorized: false)
                if result.state != .verified { failure = result.reason; continue }
                if result.expiredNow == true { failure = "chain expired now"; continue }
                if !leaf.roles.contains(Attest.roleImport) { failure = "leaf lacks import role"; continue }
                guard let block = leaf.importerBlock else { failure = "leaf lacks importer block"; continue }
                guard let org = leaf.orgId else { failure = "leaf lacks organization"; continue }
                if !block.covers(pair) { failure = "importer block does not cover \(pair.description)/\(pair.type ?? "")"; continue }
                if revocation != .notRevokedAsKnown { failure = "revocation: \(String(describing: revocation))"; continue }
                let receipt = Receipt(rootPubkey: result.root!.pubkeyHex, leafId: hex(leaf.id), orgId: org,
                                      orgName: leaf.orgName ?? leaf.ownerName, source: pair, priority: priority(event), checkedAt: now.description,
                                      leafValidUntil: leaf.validUntil?.description)
                return Decision(receipt: receipt, chain: view, reason: "Authorized importer testimony; original Bot signature/inclusion not verified")
            }
            return Decision(receipt: nil, chain: view, reason: failure)
        } catch {
            return Decision(receipt: nil, chain: nil, reason: "\(error)")
        }
    }

    /// Cheap re-check at insertion time: the leaf is still within validity and the deadline has not passed.
    public static func readyToInsert(_ receipt: Receipt, _ event: NostrEvent, _ now: Instant?) -> Bool {
        guard let now else { return false }
        return (receipt.leafValidUntil.flatMap(Instant.parse).map { now < $0 } ?? true) &&
            Instant(epochSecond: event.createdAt) <= now && (AtRepository.deliveryExpiresAt(event).map { $0 > now } ?? false)
    }
}
