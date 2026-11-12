import Foundation

/// Receiver-side authorization of importer re-posts (`attest.importer` §3) before they enter the mesh from the
/// Internet. Trust comes only from the local [Config]; a relay's or server's own verification claims are ignored.
public enum PublicIngress {
    public static let bsaf = "dev.jisaku.mosimo.public.bsaf"
    public static let priorityType = "dev.jisaku.mosimo.priority"
    public static let maxRoots = 8
    public static let maxSelected = 64

    public struct Selection: Hashable, Sendable { public let botDid: String; public let type: String }
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
        public var identity: Bool { roles.contains(Attest.roleIdentity) }
        public var publicInformation: Bool { roles.contains(Attest.rolePublicInformation) }
        public var confirmedNow: Bool { state == .verified && expiredNow == false && revocation == .notRevokedAsKnown }
    }

    /// Authorization record kept with an inserted post (importer §3): never "official", only who re-posted what.
    public struct Receipt: Sendable, CustomStringConvertible {
        public let rootPubkey, leafId, botDid, type, orgId, orgName: String
        public let priority: Int
        public let checkedAt: String
        public let leafValidUntil: String?
        public var description: String {
            "root=\(rootPubkey.prefix(8)) leaf=\(leafId.prefix(8)) org=\(orgName)(\(orgId)) bot=\(botDid) type=\(type) priority=\(priority) checked=\(checkedAt)"
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
        let selected: [Selection] = try selectedNodes.map { s in
            try require(s.keys == ["botDid", "type"], "Unexpected/missing JSON fields")
            guard let did = s["botDid"]?.string, matches(AtRepository.didPattern, did), let type = s["type"]?.string, (1...128).contains(type.utf8.count) else {
                throw CoreError("Invalid selection")
            }
            return Selection(botDid: did, type: type)
        }
        try require(Set(selected).count == selected.count, "Duplicate selection")
        return Config(roots: roots, selected: Set(selected))
    }

    /// priority §2: exactly one valid extension gives the hint; anything else is level 1. Never raised by itself.
    public static func priority(_ event: NostrEvent) -> Int {
        guard let ext = try? AtRepository.extensions(event) else { return 1 }
        let p = ext.filter { $0["$type"] == .text(priorityType) }
        guard p.count == 1, let level = p[0]["level"]?.int, (0...3).contains(level) else { return 1 }
        return Int(level)
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

    /// Evaluates every chain of [event] against [roots]; the first chain whose leaf belongs to the event decides.
    /// [now] nil means the clock is untrusted: the result is unknownTime, never verified.
    public static func chainView(_ event: NostrEvent, roots: [Attest.Root], revocations: RevocationStore, now: Instant?, seenAt: Instant) -> ChainView? {
        let chains: [Attest.Chain]
        do { chains = try Attest.chains(event) } catch {
            return ChainView(state: .invalid, expiredNow: nil, revocation: nil, name: nil, orgId: nil, roles: [], trustLevel: nil, reason: "malformed chain: \(error)")
        }
        if chains.isEmpty { return nil }
        let createdAt = Instant(epochSecond: event.createdAt)
        var best: ChainView?
        for chain in chains {
            let leaf = chain.attestations.last!
            let result: Attest.ChainResult
            do {
                try Attest.bindLeaf(leaf, chain.binding, event)
                result = Attest.verifyChain(chain.attestations, roots: roots, createdAt: createdAt, now: now)
            } catch { result = Attest.ChainResult(state: .invalid, reason: "leaf: \(error)") }
            let revocation = result.root != nil ? Attest.revocationState(result, known: revocations.of, createdAt: createdAt, seenAt: seenAt) : nil
            let view = ChainView(state: result.state, expiredNow: result.expiredNow, revocation: revocation, name: result.leaf?.displayName,
                                 orgId: result.leaf?.orgId, roles: result.leaf?.roles ?? [], trustLevel: result.leaf?.trustLevel, reason: result.reason)
            if view.confirmedNow { return view }
            if best.map({ view.state.rawValue > $0.state.rawValue }) ?? true { best = view }
        }
        return best
    }

    /// importer §3: local selection, a chain verified at both creation and now whose leaf has the `import` role, an
    /// `importer` block covering (botDid, type) and an `organization`, and no known revocation.
    public static func verify(_ event: NostrEvent, _ config: Config, _ revocations: RevocationStore, _ now: Instant?, seenAt: Instant? = nil) -> Decision {
        do {
            try require(event.tags.count <= 64 && event.tags.filter { $0.first?.hasPrefix(AtRepository.extPrefix) == true }.count <= 16, "Event resource limit")
            try require(event.verify(), "Invalid author signature")
            try require(try EventCodec.encode(event).count <= MeshPolicy.maxPayloadBytes && AtRepository.verifyEnvelope(event), "Invalid envelope/resource limit")
            let original = try bsafOriginal(event)
            try require(config.selected.contains(Selection(botDid: original.botDid, type: original.type)), "Bot/type not selected")
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
                                 roles: leaf.roles, trustLevel: leaf.trustLevel, reason: result.reason)
                if result.state != .verified { failure = result.reason; continue }
                if result.expiredNow == true { failure = "chain expired now"; continue }
                if !leaf.roles.contains(Attest.roleImport) { failure = "leaf lacks import role"; continue }
                guard let sources = leaf.importerSources else { failure = "leaf lacks importer block"; continue }
                guard let org = leaf.orgId else { failure = "leaf lacks organization"; continue }
                if sources[original.botDid]?.contains(original.type) != true { failure = "importer block does not cover \(original.botDid)/\(original.type)"; continue }
                if revocation != .notRevokedAsKnown { failure = "revocation: \(String(describing: revocation))"; continue }
                let receipt = Receipt(rootPubkey: result.root!.pubkeyHex, leafId: hex(leaf.id), botDid: original.botDid, type: original.type, orgId: org,
                                      orgName: leaf.orgName ?? leaf.ownerName, priority: priority(event), checkedAt: now.description,
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
