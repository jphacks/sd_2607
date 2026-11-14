import Foundation

/// Key attestations (`dev.jisaku.mosimo.attest`), chains embedded in posts (`attest.chain`) and revocation
/// notices (`attest.revocation`). Trust starts only at locally configured [Root]s; nothing in received data can
/// add one. A verified chain proves issuer statements about a key, never the truth of a post.
public enum Attest {
    public static let keyAttestation = "dev.jisaku.mosimo.attest#keyAttestation"
    public static let keyValidity = "dev.jisaku.mosimo.attest#keyValidity"
    public static let delegation = "dev.jisaku.mosimo.attest#delegation"
    public static let organization = "dev.jisaku.mosimo.attest#organization"
    public static let issuerDomainType = "dev.jisaku.mosimo.attest#issuerDomain"
    public static let trustLevelType = "dev.jisaku.mosimo.attest#trustLevel"
    public static let subkey = "dev.jisaku.mosimo.attest.revocation.subkey"
    public static let importer = "dev.jisaku.mosimo.attest.importer"
    public static let chain = "dev.jisaku.mosimo.attest.chain"
    public static let bindingDeclaration = "dev.jisaku.mosimo.attest.chain#bindingDeclaration"
    public static let consent = "Use this key pair for this DID's Mosimo publications"
    public static let revocationType = "dev.jisaku.mosimo.attest.revocation"
    public static let notice = "dev.jisaku.mosimo.attest.revocation#notice"
    public static let activation = "dev.jisaku.mosimo.attest.revocation#activation"
    public static let revocationListType = "dev.jisaku.mosimo.attest.revocation.list"
    public static let roleAttest = "attest"
    public static let roleIdentity = "identity"
    public static let rolePublicInformation = "public-information"
    public static let roleImport = "import"
    /// attest §7: one attestation; attest.chain §2: one chain extension.
    public static let maxAttestationBytes = 4096
    public static let maxChainBytes = 16384
    public static let maxTextBytes = 256

    /// A locally trusted root key (stratum 0) with the roles and delegation depth the operator grants it.
    public struct Root: Equatable, Sendable {
        public let pubkey: [UInt8]
        public let name: String
        public let roles: Set<String>
        public let maxDepth: Int
        public let enabled: Bool
        public var pubkeyHex: String { hex(pubkey) }
        public init(pubkey: [UInt8], name: String, roles: Set<String>, maxDepth: Int, enabled: Bool = true) throws {
            _ = try publicKey(.bytes(pubkey))
            try require((0...16).contains(maxDepth) && name.utf8.count <= maxTextBytes)
            self.pubkey = pubkey; self.name = name; self.roles = roles; self.maxDepth = maxDepth; self.enabled = enabled
        }
    }

    /// One parsed, signature-checked key attestation; [body] keeps every field, known or not.
    public struct Attestation: Sendable {
        public let body: [String: Cbor]
        public let sig: [UInt8]
        public let id: [UInt8]
        public let issuer: [UInt8], subject: [UInt8]
        public let ownerId: String, ownerName: String
        public let roles: [String]
        public let blocks: [String: [String: Cbor]]
        public let validFrom: Instant?, validUntil: Instant?
        public let maxDepth: Int64
        public let orgId: String?, orgName: String?
        public let issuerDomain: String?
        public let trustLevel: Int64?
        public let revocationSubkey: [UInt8]?
        /// importer block (importer §1). Nil when the block is absent.
        public let importerBlock: Importer?
        /// Display name for `identity`: the organization if any, else the owner.
        public var displayName: String { orgName ?? ownerName }

        init(body: [String: Cbor], sig: [UInt8]) throws {
            self.body = body; self.sig = sig
            try require(body["$type"] == .text(keyAttestation), "Not a key attestation")
            try require(Set(body.keys) == ["$type", "issuer", "subject", "owner", "roles", "blocks"], "Unexpected attestation fields")
            id = sha256(try CanonicalCbor.encode(.map(body)))
            issuer = try publicKey(body["issuer"]); subject = try publicKey(body["subject"])
            let owner = try map(body["owner"])
            try require(Set(owner.keys) == ["id", "name"])
            ownerId = try text(owner["id"]); ownerName = try text(owner["name"])
            roles = try sortedTexts(body["roles"], 32)
            guard let list = body["blocks"]?.array, list.count <= 16 else { throw CoreError("Expected blocks") }
            var blocks = [String: [String: Cbor]]()
            for b in list {
                let m = try map(b)
                let type = try text(m["$type"])
                try require(blocks[type] == nil, "Duplicate block type")
                blocks[type] = m
            }
            self.blocks = blocks
            validFrom = try blocks[keyValidity]?["from"].map { try time($0) }
            validUntil = try blocks[keyValidity]?["until"].map { try time($0) }
            maxDepth = try blocks[delegation].map { try number($0["maxDepth"]) } ?? 0
            orgId = try blocks[organization].map { try text($0["orgId"]) }
            orgName = try blocks[organization].map { try text($0["orgName"]) }
            issuerDomain = try blocks[issuerDomainType].map { try text($0["domain"]).lowercased() }
            trustLevel = try blocks[trustLevelType].map { b in let l = try number(b["level"]); try require(l <= 3); return l }
            revocationSubkey = try blocks[subkey].map { try publicKey($0["key"]) }
            importerBlock = try blocks[importer].map { block in
                try require(block.keys.allSatisfy { ["$type", "sources", "feeds"].contains($0) }, "Unexpected importer fields")
                // importer §1, §4: `types` are sorted, unique, 1...32 texts of at most 128 bytes.
                func types(_ v: Cbor?) throws -> Set<String> { Set(try sortedTexts(v, 32, min: 1, maxBytes: 128)) }
                var sources: [String: Set<String>]?
                if let value = block["sources"] {
                    guard let list = value.array, (1...16).contains(list.count) else { throw CoreError("Expected sources") }
                    let parsed: [(String, Set<String>)] = try list.map { s in
                        let m = try map(s)
                        try require(Set(m.keys) == ["botDid", "types"], "Unexpected source fields")
                        let did = try text(m["botDid"])
                        try require(matches(AtRepository.didPattern, did), "Invalid botDid")
                        return (did, try types(m["types"]))
                    }
                    try require(zip(parsed, parsed.dropFirst()).allSatisfy { lexicalCompare($0.0, $1.0) < 0 }, "Unsorted sources")
                    sources = Dictionary(uniqueKeysWithValues: parsed)
                }
                var feeds: [Importer.Feed]?
                if let value = block["feeds"] {
                    guard let list = value.array, (1...32).contains(list.count) else { throw CoreError("Expected feeds") }
                    let parsed: [Importer.Feed] = try list.map { f in
                        let m = try map(f)
                        try require(m["feed"] != nil && m.keys.allSatisfy { ["feed", "origin", "types"].contains($0) }, "Unexpected feed fields")
                        let feed = try text(m["feed"])
                        try require(isFeedId(feed) && feed != PublicIngress.bsaf, "Invalid feed ID")
                        let origin = try m["origin"].map { try text($0) }
                        try require(origin.map(isOrigin) ?? true, "Invalid feed origin")
                        return Importer.Feed(feed: feed, origin: origin, types: try m["types"].map(types))
                    }
                    // importer §1.3: by (feed, origin) bytes, an absent origin first; no duplicate (feed, origin).
                    try require(zip(parsed, parsed.dropFirst()).allSatisfy { a, b in
                        let c = lexicalCompare(a.feed, b.feed)
                        return c < 0 || (c == 0 && lexicalCompare(a.origin ?? "", b.origin ?? "") < 0)
                    }, "Feeds must be sorted by (feed, origin) without duplicates")
                    feeds = parsed
                }
                try require(sources != nil || feeds != nil, "importer block without sources or feeds")
                let b = Importer(sources: sources, feeds: feeds)
                try require(b.pairCount <= 64, "Importer scope limit")
                return b
            }
            try require(issuer != subject, "Self-signed attestation")
            try require(ownerId.utf8.count <= maxTextBytes && ownerName.utf8.count <= maxTextBytes)
            if let orgId { try require((1...maxTextBytes).contains(orgId.utf8.count) && orgId.contains(":"), "orgId must be <prefix>:<id>") }
            if let orgName { try require(orgName.utf8.count <= maxTextBytes) }
            if let v = blocks[keyValidity] { try require(v.keys.allSatisfy { ["$type", "from", "until"].contains($0) }) }
            if let f = validFrom, let u = validUntil { try require(f < u, "Empty validity") }
            try verifySignature(sig, .map(body), issuer)
            try require(try CanonicalCbor.encode(.map(["body": .map(body), "sig": .bytes(sig)])).count <= maxAttestationBytes, "Attestation too large")
        }

        /// `keyValidity`: from <= t < until; absent bounds are unlimited.
        public func validAt(_ t: Instant) -> Bool { (validFrom.map { t >= $0 } ?? true) && (validUntil.map { t < $0 } ?? true) }
    }

    /// `dev.jisaku.mosimo.attest.importer`: the (botDid, type) pairs of `sources` and the `{feed, origin?, types?}` entries of
    /// `feeds` an importer may re-publish; each is nil when its list is absent.
    public struct Importer: Equatable, Sendable {
        /// importer §1: an absent [origin] or [types] stands for every issuer or every type of the feed.
        public struct Feed: Equatable, Sendable {
            public let feed: String, origin: String?
            public let types: Set<String>?
            public init(feed: String, origin: String? = nil, types: Set<String>? = nil) { self.feed = feed; self.origin = origin; self.types = types }
        }
        public let sources: [String: Set<String>]?
        public let feeds: [Feed]?
        /// importer §4: explicit types over both lists.
        var pairCount: Int { (sources?.values.reduce(0) { $0 + $1.count } ?? 0) + (feeds?.reduce(0) { $0 + ($1.types?.count ?? 0) } ?? 0) }
        public func covers(_ pair: PublicIngress.Selection) -> Bool {
            if let bot = pair.botDid { return pair.type.map { sources?[bot]?.contains($0) } == true }
            if let feed = pair.feed { return Self.includes(feeds, feed, pair.origin, pair.type) }
            return false
        }
        /// importer §1.3: whether [feeds] holds (feed, origin, type); a nil [origin] or [type] asks for every issuer or type,
        /// so only an entry without origin covers a post without origin.
        static func includes(_ feeds: [Feed]?, _ feed: String, _ origin: String?, _ type: String?) -> Bool {
            feeds?.contains { f in
                f.feed == feed && (f.origin == nil || (origin != nil && f.origin == origin)) && (f.types.map { t in type.map(t.contains) ?? false } ?? true)
            } ?? false
        }
        /// importer §1, §1.3: each child pair is in the issuer's list of the same kind; a list the issuer lacks, the child
        /// cannot have. An origin-wide or type-wide child entry needs an origin-wide or type-wide parent entry.
        func within(_ parent: Importer) -> Bool {
            let botsWithin = sources.map { child in
                parent.sources.map { p in child.allSatisfy { key, types in p[key].map { types.isSubset(of: $0) } ?? false } } ?? false
            } ?? true
            let feedsWithin = (feeds ?? []).allSatisfy { c in
                c.types.map { $0.allSatisfy { Self.includes(parent.feeds, c.feed, c.origin, $0) } } ?? Self.includes(parent.feeds, c.feed, c.origin, nil)
            }
            return botsWithin && feedsWithin
        }
    }

    /// importer §1.1: a feed ID is an NSID with a lowercase authority and at least three segments.
    private static let nsid = try! NSRegularExpression(pattern: "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+\\.[a-zA-Z][a-zA-Z0-9]{0,62}$")
    public static func isFeedId(_ value: String) -> Bool {
        value.utf8.count <= 317 && matches(nsid, value) && value.split(separator: ".", omittingEmptySubsequences: false).count >= 3
    }
    /// importer §1.1: an issuer is an absolute URI of at most 256 bytes without whitespace or control characters.
    private static let originPattern = try! NSRegularExpression(pattern: "^[a-zA-Z][a-zA-Z0-9+.-]*:[^\\s\\x00-\\x1f\\x7f]+$")
    public static func isOrigin(_ value: String) -> Bool { value.utf8.count <= 256 && matches(originPattern, value) }

    /// `{body, sig}` as carried in chains and FETCH responses.
    public static func attestation(_ v: Cbor?) throws -> Attestation {
        let signed = try map(v)
        try require(Set(signed.keys) == ["body", "sig"], "Expected Signed<T>")
        return try Attestation(body: try map(signed["body"]), sig: try bytes(signed["sig"], 64))
    }

    public enum ChainState: Int, Sendable { case invalid, unverifiable, unknownTime, expired, verified }

    /// attest §5 result at the post's creation time plus, with a trusted clock, whether the chain is still valid
    /// now. [leaf] is the last attestation when the structure verified (also for expired/unknownTime).
    public struct ChainResult: Sendable {
        public let state: ChainState
        public let reason: String
        public var root: Root? = nil
        public var attestations: [Attestation] = []
        public var expiredNow: Bool? = nil
        public var leaf: Attestation? { attestations.last }
    }

    /// One `attest.chain` extension of a post.
    public struct Chain: Sendable { public let attestations: [Attestation]; public let binding: [String: Cbor]? }

    /// Parses every `attest.chain` extension; a malformed one throws (the whole post is then "unverified").
    public static func chains(_ event: NostrEvent) throws -> [Chain] {
        try AtRepository.extensions(event).filter { $0["$type"] == .text(chain) }.map { ext in
            try require(ext.keys.allSatisfy { ["$type", "attestations", "binding"].contains($0) }, "Unexpected chain fields")
            var fields = ext; fields["$type"] = nil
            try require(try CanonicalCbor.encode(.map(fields)).count <= maxChainBytes, "Chain too large")
            guard let list = ext["attestations"]?.array, (1...16).contains(list.count) else { throw CoreError("Expected attestations") }
            return Chain(attestations: try list.map(attestation), binding: try ext["binding"].map { try map($0) })
        }
    }

    /// attest §5 / attest.chain §3: issuer of the first element is a configured root, signatures, linkage, role and
    /// block containment, then validity at [createdAt] and (if [now] is known) at now.
    public static func verifyChain(_ attestations: [Attestation], roots: [Root], createdAt: Instant, now: Instant?) -> ChainResult {
        guard let first = attestations.first else { return ChainResult(state: .invalid, reason: "empty chain") }
        guard let root = roots.first(where: { $0.enabled && $0.pubkey == first.issuer }) else { return ChainResult(state: .unverifiable, reason: "unknown root") }
        var parentRoles = root.roles
        var parentDepth = Int64(root.maxDepth)
        var parentFrom: Instant?, parentUntil: Instant?
        var parentOrg: String?
        // importer §1: the nearest ancestor's block bounds a child's; with none above, the direct parent needs `import`.
        var parentImporter: Importer?
        var parentHasImport = root.roles.contains(roleImport)
        for (i, a) in attestations.enumerated() {
            let leaf = i == attestations.count - 1
            func invalid(_ why: String) -> ChainResult { ChainResult(state: .invalid, reason: "attestation \(i): \(why)", root: root) }
            if i > 0 && attestations[i - 1].subject != a.issuer { return invalid("issuer is not the previous subject") }
            if !Set(a.roles).isSubset(of: parentRoles) { return invalid("roles exceed the issuer's") }
            if !leaf && !a.roles.contains(roleAttest) { return invalid("intermediate without attest role") }
            if i > 0 && a.validUntil == nil { return invalid("stratum 2+ requires until") }
            if let pf = parentFrom, a.validFrom.map({ $0 < pf }) ?? true { return invalid("from precedes the issuer's") }
            if let pu = parentUntil, a.validUntil.map({ $0 > pu }) ?? true { return invalid("until exceeds the issuer's") }
            if a.maxDepth > parentDepth - 1 { return invalid("delegation depth exceeds the issuer's") }
            if !leaf && a.maxDepth < 1 { return invalid("intermediate without delegation") }
            if let po = parentOrg, a.orgId != po { return invalid("organization differs from the issuer's") }
            if let ci = a.importerBlock, !(parentImporter.map(ci.within) ?? parentHasImport) { return invalid("importer block exceeds the issuer's") }
            parentRoles = Set(a.roles); parentDepth = a.maxDepth; parentFrom = a.validFrom; parentUntil = a.validUntil
            parentOrg = a.orgId ?? parentOrg
            parentImporter = a.importerBlock ?? parentImporter // a block-less intermediate does not widen the scope
            parentHasImport = a.roles.contains(roleImport)
        }
        guard let now else { return ChainResult(state: .unknownTime, reason: "clock unknown", root: root, attestations: attestations) }
        if !attestations.allSatisfy({ $0.validAt(createdAt) }) { return ChainResult(state: .expired, reason: "outside validity at creation", root: root, attestations: attestations) }
        return ChainResult(state: .verified, reason: "verified", root: root, attestations: attestations, expiredNow: !attestations.allSatisfy { $0.validAt(now) })
    }

    /// attest.chain §1: a 32-byte leaf is the Nostr key itself; a 33-byte leaf is the atproto key, bound to the Nostr
    /// key and the commit DID by the reciprocal declaration. Throws when the leaf does not belong to [event].
    public static func bindLeaf(_ leaf: Attestation, _ binding: [String: Cbor]?, _ event: NostrEvent) throws {
        let author = try unhex(event.pubkey, 32)
        if leaf.subject.count == 32 { try require(leaf.subject == author, "leaf is not the author key"); return }
        guard let b = binding else { throw CoreError("33-byte leaf requires a binding") }
        try require(Set(b.keys) == ["declaration", "atprotoSig", "nostrSig"], "Unexpected binding fields")
        let d = try map(b["declaration"])
        try require(Set(d.keys) == ["$type", "did", "atprotoPubkey", "nostrPubkey", "consent"] && d["$type"] == .text(bindingDeclaration) &&
            d["consent"] == .text(consent), "Unexpected declaration")
        try require(try bytes(d["atprotoPubkey"], 33) == leaf.subject && (try bytes(d["nostrPubkey"], 32)) == author, "Binding keys mismatch")
        let did = try text(d["did"])
        try require(matches(AtRepository.didPattern, did))
        let digest = sha256(try CanonicalCbor.encode(.map(d)))
        try verifyDigest(try bytes(b["atprotoSig"], 64), digest, leaf.subject)
        try verifyDigest(try bytes(b["nostrSig"], 64), digest, author)
        let commit = try CanonicalCbor.decode(try AtRepository.restore(event, atprotoPubkey: leaf.subject).commitBytes)
        try require(commit["did"] == .text(did), "Commit DID differs from the binding")
    }

    // MARK: - revocation

    /// A `Revocation` whose notice signature is verified (attest.revocation §1-2). When its activation is not signed by
    /// the subject itself, [pendingActivation]/[pendingSig] hold it: it can only be checked against a revocation sub-key
    /// attested in the chain being evaluated (subkey §2-3), so it is kept until such a chain is seen.
    public struct Revocation: Sendable {
        public let id: [UInt8], issuer: [UInt8], subject: [UInt8]
        public let issuedAt: Instant, effectiveAt: Instant
        public let pendingActivation: [UInt8]?, pendingSig: [UInt8]?
        public init(id: [UInt8], issuer: [UInt8], subject: [UInt8], issuedAt: Instant, effectiveAt: Instant,
                    pendingActivation: [UInt8]? = nil, pendingSig: [UInt8]? = nil) throws {
            try require((pendingActivation == nil) == (pendingSig == nil))
            self.id = id; self.issuer = issuer; self.subject = subject; self.issuedAt = issuedAt; self.effectiveAt = effectiveAt
            self.pendingActivation = pendingActivation; self.pendingSig = pendingSig
        }
        public var needsSubkey: Bool { pendingSig != nil }
        /// Same notice and same activation path: a re-send that adds nothing (§5).
        func sameAs(_ o: Revocation) -> Bool { id == o.id && effectiveAt == o.effectiveAt && needsSubkey == o.needsSubkey && (pendingSig == nil || pendingSig == o.pendingSig) }
    }

    /// Verifies `{notice, activation?}`. Nil when the notice is pre-issued and carries no activation.
    public static func revocation(_ v: Cbor?) throws -> Revocation? {
        let r = try map(v)
        try require(r.keys.allSatisfy { ["$type", "notice", "activation"].contains($0) } && (r["$type"] == nil || r["$type"] == .text(revocationType)))
        let notice = try map(r["notice"])
        try require(Set(notice.keys) == ["body", "sig"])
        let body = try map(notice["body"])
        try require(body["$type"] == .text(Self.notice) && body.keys.allSatisfy { ["$type", "issuer", "subject", "issuedAt", "effectiveAt", "blocks"].contains($0) })
        let issuer = try publicKey(body["issuer"]), subject = try publicKey(body["subject"])
        let issuedAt = try time(body["issuedAt"])
        guard let blocks = body["blocks"]?.array else { throw CoreError("Expected blocks") }
        for b in blocks { _ = try map(b) }
        try verifySignature(try bytes(notice["sig"], 64), .map(body), issuer)
        let id = sha256(try CanonicalCbor.encode(.map(body)))
        if let e = body["effectiveAt"] { return try Revocation(id: id, issuer: issuer, subject: subject, issuedAt: issuedAt, effectiveAt: try time(e)) }
        guard let a = r["activation"] else { return nil }
        let activation = try map(a)
        try require(Set(activation.keys) == ["body", "sig"])
        let ab = try map(activation["body"])
        try require(ab["$type"] == .text(Self.activation) && Set(ab.keys) == ["$type", "noticeId", "effectiveAt"])
        try require(try bytes(ab["noticeId"], 32) == id, "Activation for another notice")
        let effectiveAt = try time(ab["effectiveAt"])
        try require(effectiveAt >= issuedAt, "Activation precedes issue")
        let sig = try bytes(activation["sig"], 64)
        if (try? verifySignature(sig, .map(ab), subject)) != nil {
            return try Revocation(id: id, issuer: issuer, subject: subject, issuedAt: issuedAt, effectiveAt: effectiveAt)
        }
        return try Revocation(id: id, issuer: issuer, subject: subject, issuedAt: issuedAt, effectiveAt: effectiveAt,
                              pendingActivation: try CanonicalCbor.encode(.map(ab)), pendingSig: sig)
    }

    public enum RevocationState: Sendable { case revoked, revokedLater, notRevokedAsKnown }

    /// attest.revocation §6 for one chain: a notice applies when its issuer is the root, an ancestor issuer, or the
    /// subject itself. [known] lists the revocations held for a subject key. A sub-key activation counts only when the
    /// attestation of that subject in THIS chain names the signing sub-key (subkey §2).
    public static func revocationState(_ chain: ChainResult, known: ([UInt8]) -> [Revocation], createdAt: Instant, seenAt: Instant) -> RevocationState {
        guard let root = chain.root else { return .notRevokedAsKnown }
        var ancestors = [root.pubkey]
        var later = false
        for a in chain.attestations {
            for r in known(a.subject) {
                if r.needsSubkey {
                    guard let key = a.revocationSubkey, ancestors.contains(r.issuer),
                          (try? verifyDigest(r.pendingSig!, sha256(r.pendingActivation!), key)) != nil else { continue }
                } else if !(ancestors.contains(r.issuer) || r.issuer == a.subject) { continue }
                if createdAt >= r.effectiveAt || seenAt >= r.effectiveAt { return .revoked }
                later = true
            }
            ancestors.append(a.subject)
        }
        return later ? .revokedLater : .notRevokedAsKnown
    }

    /// attest.revocation §5: the single `Revocation` inside a control event. The carrier's signature is not a source of trust.
    public static func revocationOf(_ event: NostrEvent) throws -> Revocation? {
        guard event.kind == AtRepository.controlKind, AtRepository.controlTopic(event) == AtRepository.revocationTopic else { return nil }
        let ext = try AtRepository.extensions(event).filter { $0["$type"] == .text(revocationType) }
        try require(ext.count == 1, "Exactly one revocation per event")
        return try revocation(.map(ext[0]))
    }

    /// `Signed<ListBody>` from `https://D/.well-known/mosimo/attest/revocations`; returns every verifiable inner revocation.
    public static func revocationList(_ bytes: [UInt8]) throws -> [Revocation] {
        try require(bytes.count <= 1_048_576, "Revocation list too large")
        let signed = try map(try CanonicalCbor.decode(bytes))
        let body = try map(signed["body"])
        try require(body["$type"] == .text(revocationListType))
        // The list signature only states what the publisher served; each notice stands on its own signature.
        guard let list = body["revocations"]?.array else { throw CoreError("Expected revocations") }
        return list.compactMap { (try? revocation($0)) ?? nil }
    }

    // MARK: - shared primitives (attest §1)

    /// 32 bytes: BIP-340 x-only; 33 bytes: SEC1 compressed. Must be a valid curve point.
    public static func publicKey(_ v: Cbor?) throws -> [UInt8] {
        guard let key = v?.bytes else { throw CoreError("Expected public key bytes") }
        switch key.count {
        case 32: try require((try? Secp.compress([2] + key)) != nil, "Invalid x-only key")
        case 33: try require((key[0] == 2 || key[0] == 3) && (try? Secp.compress(key)) == key, "Invalid compressed key")
        default: throw CoreError("Invalid public key length")
        }
        return key
    }
    /// Schnorr for 32-byte keys, low-S compact ECDSA for 33-byte keys, over SHA-256 of the canonical CBOR body.
    public static func verifySignature(_ sig: [UInt8], _ body: Cbor, _ key: [UInt8]) throws { try verifyDigest(sig, sha256(try CanonicalCbor.encode(body)), key) }
    static func verifyDigest(_ sig: [UInt8], _ digest: [UInt8], _ key: [UInt8]) throws {
        try require(sig.count == 64, "Signature must be 64 bytes")
        let ok: Bool
        switch key.count {
        case 32: ok = Secp.verifySchnorr(sig, digest, key)
        case 33: ok = !Secp.isHighS(sig) && Secp.verify(sig, digest, key)
        default: ok = false
        }
        try require(ok, "Invalid signature")
    }

    static func map(_ v: Cbor?) throws -> [String: Cbor] { guard let m = v?.map else { throw CoreError("Expected map") }; return m }
    static func text(_ v: Cbor?) throws -> String { guard let s = v?.text else { throw CoreError("Expected text") }; return s }
    static func number(_ v: Cbor?) throws -> Int64 {
        guard let n = v?.int, (0...9007199254740991).contains(n) else { throw CoreError("Expected integer") }
        return n
    }
    static func bytes(_ v: Cbor?, _ size: Int) throws -> [UInt8] {
        guard let b = v?.bytes, b.count == size else { throw CoreError("Expected \(size) bytes") }
        return b
    }
    static func time(_ v: Cbor?) throws -> Instant {
        let s = try text(v)
        guard matches(AtRepository.timestamp, s), let t = Instant.parse(s) else { throw CoreError("Timestamp must be UTC with Z") }
        return t
    }
    static func lexicalCompare(_ a: String, _ b: String) -> Int {
        let x = Array(a.utf8), y = Array(b.utf8)
        for (p, q) in zip(x, y) where p != q { return p < q ? -1 : 1 }
        return x.count == y.count ? 0 : x.count < y.count ? -1 : 1
    }
    private static func sortedTexts(_ v: Cbor?, _ max: Int, min: Int = 0, maxBytes: Int? = nil) throws -> [String] {
        guard let a = v?.array else { throw CoreError("Expected array") }
        let list = try a.map { x in
            let t = try text(x)
            try require(maxBytes.map { (1...$0).contains(t.utf8.count) } ?? (t.utf8.count <= maxTextBytes))
            return t
        }
        try require((min...max).contains(list.count) && zip(list, list.dropFirst()).allSatisfy { lexicalCompare($0, $1) < 0 }, "Unsorted/duplicate/oversized list")
        return list
    }
}
