import Foundation

/// Receiver-side authorization of importer re-posts (`attest.importer` §3) before they enter the mesh from the
/// Internet. Trust comes only from the local [Config]; a relay's or server's own verification claims are ignored.
public enum PublicIngress {
    public static let bsaf = "dev.jisaku.mosimo.public.bsaf"
    public static let jmaEarthquake = "dev.jisaku.mosimo.public.jma.earthquake"
    public static let jmaWeather = "dev.jisaku.mosimo.public.jma.weather"
    /// importer §1.1: the feed of re-posted JMA disaster-prevention XML; its types are the original `Control/Title`.
    public static let jmaFeed = "jma-xml"
    public static let priorityType = "dev.jisaku.mosimo.priority"
    public static let maxRoots = 8
    public static let maxSelected = 64

    /// One re-posted (botDid, type) or (feed, type) pair (importer §1): exactly one of [botDid] and [feed] is set.
    public struct Selection: Hashable, Sendable {
        public let botDid: String?, feed: String?
        public let type: String
        public init(botDid: String, type: String) { self.botDid = botDid; feed = nil; self.type = type }
        public init(feed: String, type: String) { botDid = nil; self.feed = feed; self.type = type }
        public var description: String { botDid.map { "bot=\($0)" } ?? "feed=\(feed ?? "")" }
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
        /// (Bot DID, type) or (feed, type), and that pair locally selected. Decided from the post alone, however it arrived.
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
            "root=\(rootPubkey.prefix(8)) leaf=\(leafId.prefix(8)) org=\(orgName)(\(orgId)) \(source.description) type=\(source.type) priority=\(priority) checked=\(checkedAt)"
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
        // Each element names a Bot or a feed: {"botDid", "type"} or {"feed", "type"}.
        let selected: [Selection] = try selectedNodes.map { s in
            try require(s.keys == ["botDid", "type"] || s.keys == ["feed", "type"], "Unexpected/missing JSON fields")
            guard let type = s["type"]?.string, (1...128).contains(type.utf8.count) else { throw CoreError("Invalid selection") }
            if let feed = s["feed"]?.string, (1...Attest.maxTextBytes).contains(feed.utf8.count) { return Selection(feed: feed, type: type) }
            guard let did = s["botDid"]?.string, matches(AtRepository.didPattern, did) else { throw CoreError("Invalid selection") }
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
    /// Roles and depth given to a user-added root: it may certify importers and public-information issuers below it.
    public static let userRootRoles: Set<String> = [Attest.roleImport, Attest.rolePublicInformation, "attest"]
    public static let userRootDepth = 2

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

    /// The re-posted pair of [event] (importer §1.1): a BSAF import gives (botDid, type); otherwise exactly one
    /// `public.jma.earthquake` or `public.jma.weather` gives (`jma-xml`, its `message.control.title`). Throws for anything else.
    public static func importPair(_ event: NostrEvent) throws -> Selection {
        let ext = try AtRepository.extensions(event)
        if ext.contains(where: { $0["$type"] == .text(bsaf) }) {
            let o = try bsafOriginal(event)
            return Selection(botDid: o.botDid, type: o.type)
        }
        let jma = ext.filter { $0["$type"] == .text(jmaEarthquake) || $0["$type"] == .text(jmaWeather) }
        try require(jma.count == 1, "Neither a BSAF import nor exactly one JMA extension")
        guard let title = jma[0]["message"]?["control"]?["title"]?.text, !title.isEmpty else { throw CoreError("JMA message.control.title") }
        return Selection(feed: jmaFeed, type: title)
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
        let pair = (try? importPair(event)).flatMap { selected.contains($0) ? $0 : nil }
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
    /// `importer` block covering (botDid, type) or (feed, type) and an `organization`, and no known revocation.
    public static func verify(_ event: NostrEvent, _ config: Config, _ revocations: RevocationStore, _ now: Instant?, seenAt: Instant? = nil) -> Decision {
        do {
            try require(event.tags.count <= 64 && event.tags.filter { $0.first?.hasPrefix(AtRepository.extPrefix) == true }.count <= 16, "Event resource limit")
            try require(event.verify(), "Invalid author signature")
            try require(try EventCodec.encode(event).count <= MeshPolicy.maxPayloadBytes && AtRepository.verifyEnvelope(event), "Invalid envelope/resource limit")
            let pair = try importPair(event)
            try require(config.selected.contains(pair), "Bot/feed type not selected")
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
                if !block.covers(pair) { failure = "importer block does not cover \(pair.description)/\(pair.type)"; continue }
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
