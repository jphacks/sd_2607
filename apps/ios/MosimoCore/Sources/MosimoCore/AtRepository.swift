import Foundation

public struct RepositoryChange: Sendable {
    public let path: String
    public let recordBytes: [UInt8]
    public let commitBytes: [UInt8]
    public let applyFromCommit: String?
    public var recordCid: String { cid(recordBytes) }
    public var commitCid: String { cid(commitBytes) }
}

/// Core protocol (`dev.jisaku.mosimo`): one post = one record, one signed v3 commit, one kind-1 Nostr event whose
/// base tags carry the commit, apply-from CID, record key and delivery deadline in a fixed order (core §7.2).
public enum AtRepository {
    public static let namespace = "mosimo.jisaku.dev"
    public static let tagPrefix = "mosimo:"
    public static let extPrefix = tagPrefix + "ext:"
    public static let collection = "dev.jisaku.mosimo.post"
    public static let baseVersion = "1"
    /// All protocol-specific control events share one kind; the `t` topic and the extension `$type` tell them apart (core §7.5).
    public static let controlKind = 8173
    public static let plcTopic = namespace + "/plc"
    public static let revocationTopic = namespace + "/revocation"
    public static let tagVersion = tagPrefix + "v"
    public static let tagCommit = tagPrefix + "commit"
    public static let tagBase = tagPrefix + "base"
    public static let tagRkey = tagPrefix + "rkey"
    public static let tagExp = tagPrefix + "exp"
    public static let tagCreated = tagPrefix + "created"
    public static let topicTag = ["t", namespace]
    public static let versionTag = [tagVersion, baseVersion]
    static let baseTags: Set<String> = [tagVersion, tagCommit, tagBase, tagRkey, tagExp, tagCreated]
    // ponytail: every post rebuilds the MST from all records (O(n)); keep persistent MST nodes if this grows.
    public static let maxRecords = 10_000
    /// Core §3.1 Timestamp: UTC, uppercase Z, optional fraction.
    static let timestamp = try! NSRegularExpression(pattern: "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?Z$")
    /// The basic seconds-precision form required for `exp`.
    static let secondsTimestamp = try! NSRegularExpression(pattern: "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")
    static let tidPattern = try! NSRegularExpression(pattern: "^[234567abcdefghij][234567abcdefghijklmnopqrstuvwxyz]{12}$")
    static let didPattern = try! NSRegularExpression(pattern: "^did:plc:[a-z2-7]{24}$")

    static func identity(_ keys: SecretKeys, _ endpoint: String) throws -> (String, String) {
        // TODO(undecided): demo-only endpoint; the generated DID is NOT registered here.
        let serviceEndpoint = endpoint.isEmpty ? "https://pds.invalid" : endpoint
        let url = URLComponents(string: serviceEndpoint)
        try require(url?.scheme == "https" && !(url?.host ?? "").isEmpty && url?.user == nil && url?.fragment == nil, "PDS endpoint must be an absolute HTTPS URL")
        let rotation = try didKey(keys.plc), atproto = try didKey(keys.atproto)
        let unsigned: [(String, Json)] = [
            ("type", .string("plc_operation")),
            ("rotationKeys", .array([.string(rotation)])),
            ("verificationMethods", .object([("atproto", .string(atproto))])),
            ("alsoKnownAs", .array([])),
            ("services", .object([("atproto_pds", .object([("type", .string("AtprotoPersonalDataServer")), ("endpoint", .string(serviceEndpoint))]))])),
            ("prev", .null),
        ]
        let sig = try Secp.sign(sha256(CanonicalCbor.encode(Json.object(unsigned).toCbor())), keys.plc)
        let json = Json.object(unsigned + [("sig", .string(B64.urlUnpadded(sig)))]).serialized
        return (try plcDid(json), json)
    }

    /// The did:plc a signed genesis operation derives (DID PLC spec): base32(sha256(DAG-CBOR)), 24 chars.
    public static func plcDid(_ signedOperationJson: String) throws -> String {
        let op = try Json.parse(signedOperationJson, maxDepth: 16, maxBytes: CanonicalCbor.maxBytes)
        try require(op.object != nil, "Expected operation object")
        return "did:plc:" + String(base32(sha256(try CanonicalCbor.encode(op.toCbor()))).prefix(24))
    }

    /// attest.chain §1 binding: both keys sign H(declaration). Carried inside a chain extension, never as its own
    /// event; this device has no attestation chain of its own yet, so it is kept for export and tests.
    static func binding(_ keys: SecretKeys, _ did: String, _ nostrPubkey: String) throws -> [String: Cbor] {
        let declaration: Cbor = .map(["$type": .text(Attest.bindingDeclaration), "did": .text(did),
            "atprotoPubkey": .bytes(try Secp.compressedPublicKey(keys.atproto)), "nostrPubkey": .bytes(try unhex(nostrPubkey, 32)),
            "consent": .text(Attest.consent)])
        let hash = sha256(try CanonicalCbor.encode(declaration))
        return ["declaration": declaration, "atprotoSig": .bytes(try Secp.sign(hash, keys.atproto)), "nostrSig": .bytes(try Secp.signSchnorr(hash, keys.nostr))]
    }

    /// Returns the core §7.2 tags (protocol `t` tag included) and the next repository state.
    static func create(_ keys: SecretKeys, _ did: String, _ text: String, _ nowMillis: Int64, _ previous: RepositoryState,
                       _ deliveryExpiresAtSeconds: Int64, _ extensions: [[String: Cbor]]) throws -> ([[String]], RepositoryState) {
        try require(previous.records.count < maxRecords, "Repository limit: \(maxRecords) records")
        try require(previous.lastRevMicros >= 0 && (previous.previousCommit == nil) == previous.records.isEmpty, "Inconsistent repository state")
        if let p = previous.previousCommit { _ = try cidBytes(p) }
        let micros = max(nowMillis * 1000, previous.lastRevMicros + 1)
        // rev and record key are separate identifiers (core §6.2); one monotonic TID may serve both.
        let rev = try tid(micros, previous.clockId)
        let path = "\(collection)/\(rev)"
        try require(previous.records[path] == nil, "Refusing record overwrite")
        let exp = Instant(epochSecond: deliveryExpiresAtSeconds).description
        try require(matches(secondsTimestamp, exp), "Deadline outside the basic Timestamp form")
        // Open unions need free-form data-model objects; do not rebuild them from known display fields.
        let extensionTags = try extensions.map { e -> [String] in
            var fields = e; fields["$type"] = nil
            return [extPrefix + (try extensionType(e)), B64.encode(try CanonicalCbor.encode(.map(fields)))]
        }
        // created_at carries seconds precision, so the optional sub-second `created` is omitted.
        let record = try CanonicalCbor.encode(.map(["$type": .text(collection), "text": .text(text), "exp": .text(exp),
                                                    "ext": .array(extensions.map { .map($0) })]))
        var records = previous.records
        records[path] = cid(record)
        var unsigned: [String: Cbor] = ["did": .text(did), "version": .int(3), "data": .cid(try mstRoot(records)), "rev": .text(rev), "prev": .null]
        let signature = try Secp.sign(sha256(CanonicalCbor.encode(.map(unsigned))), keys.atproto)
        unsigned["sig"] = .bytes(signature)
        let commit = try CanonicalCbor.encode(.map(unsigned))
        // No MST nodes/inclusion proof on this wire: the receiver rebuilds from its saved state.
        let tags = [topicTag, versionTag, [tagCommit, B64.encode(commit)], [tagBase, previous.previousCommit ?? ""], [tagRkey, rev], [tagExp, exp]]
        var next = previous
        next.records = records; next.previousCommit = cid(commit); next.lastRevMicros = micros
        return (tags + extensionTags, next)
    }

    /// delete §2: removes `collection/<rkey>` from the repository and signs the commit of the resulting state.
    /// Returns the commit bytes, the apply-from commit CID and the next repository state.
    static func delete(_ keys: SecretKeys, _ did: String, _ rkey: String, _ nowMillis: Int64, _ previous: RepositoryState) throws -> ([UInt8], String, RepositoryState) {
        let path = "\(collection)/\(rkey)"
        guard previous.records[path] != nil, let base = previous.previousCommit else { throw CoreError("Record not in this repository") }
        let micros = max(nowMillis * 1000, previous.lastRevMicros + 1)
        let rev = try tid(micros, previous.clockId)
        var records = previous.records
        records[path] = nil
        var unsigned: [String: Cbor] = ["did": .text(did), "version": .int(3), "data": .cid(try mstRoot(records)), "rev": .text(rev), "prev": .null]
        unsigned["sig"] = .bytes(try Secp.sign(sha256(CanonicalCbor.encode(.map(unsigned))), keys.atproto))
        let commit = try CanonicalCbor.encode(.map(unsigned))
        var next = previous
        next.records = records; next.previousCommit = cid(commit); next.lastRevMicros = micros
        return (commit, base, next)
    }

    /// delete §1: the target of a deletion request (`e`, `k`) and, for a post, its removal commit. Nil when malformed.
    public static func deletion(_ event: NostrEvent) -> (target: String, kind: Int, change: RepositoryChange?)? {
        let t = event.tags
        guard event.kind == 5, t.count >= 4, t[0] == topicTag, t[1].count == 2, t[1][0] == "e", (try? unhex(t[1][1], 32)) != nil,
              t[2].count == 2, t[2][0] == "k", let kind = Int(t[2][1]), (0...65535).contains(kind) else { return nil }
        if kind != 1 { return t.count == 4 && t[3].first == tagExp ? (t[1][1], kind, nil) : nil }
        guard t.count == 7, t[3].count == 2, t[3][0] == tagCommit, t[4].count == 2, t[4][0] == tagBase, t[5].count == 2, t[5][0] == tagRkey,
              t[6].first == tagExp, matches(tidPattern, t[5][1]), let commit = B64.decode(t[3][1]), (try? cidBytes(t[4][1])) != nil else { return nil }
        return (t[1][1], 1, RepositoryChange(path: "\(collection)/\(t[5][1])", recordBytes: [], commitBytes: commit, applyFromCommit: t[4][1]))
    }

    /// report §1: target event ID, target author and report type of a well-formed report. Nil when malformed.
    public static func report(_ event: NostrEvent) -> (target: String, author: String, type: String)? {
        let t = event.tags
        guard event.kind == 1984, t.count >= 4, t[0] == topicTag, t[1].count == 3, t[1][0] == "e", t[2].count == 3, t[2][0] == "p",
              t[1][2] == t[2][2], (try? unhex(t[1][1], 32)) != nil, (try? unhex(t[2][1], 32)) != nil, t[3].first == tagExp else { return nil }
        return (t[1][1], t[2][1], t[1][2])
    }

    /// The signed delivery deadline `mosimo:exp` of any kind of event; nil when the event lacks a valid one.
    public static func deliveryExpiresAt(_ event: NostrEvent) -> Instant? {
        let found = event.tags.filter { $0.first == tagExp }
        guard found.count == 1, found[0].count == 2, matches(secondsTimestamp, found[0][1]) else { return nil }
        return Instant.parse(found[0][1])
    }

    /// Control events (core §7.5): kind 8173 with the protocol topic first and a per-kind topic second.
    public static func controlTopic(_ event: NostrEvent) -> String? {
        guard event.kind == controlKind, event.tags.count >= 2, event.tags[0] == topicTag else { return nil }
        let t = event.tags[1]
        return t.count == 2 && t[0] == "t" && t[1].hasPrefix(namespace + "/") ? t[1] : nil
    }

    /// Checks Nostr, record reconstruction and commit shape. With [atprotoPubkey] (from the DID document) it also
    /// checks the low-S ECDSA commit signature. Neither is DID authentication nor MST inclusion.
    public static func verifyEnvelope(_ event: NostrEvent, atprotoPubkey: [UInt8]? = nil) -> Bool {
        (try? restore(event, atprotoPubkey: atprotoPubkey)) != nil
    }

    /// Decodes the self-contained extension tags of [event] in array order (core §7.3); throws on a malformed one.
    public static func extensions(_ event: NostrEvent) throws -> [[String: Cbor]] {
        try extensions(event.tags)
    }
    static func extensions(_ tags: [[String]]) throws -> [[String: Cbor]] {
        try tags.filter { $0.first?.hasPrefix(extPrefix) == true }.map { tag in
            try require(tag.count == 2, "Extension tag shape")
            guard let raw = B64.decode(tag[1]) else { throw CoreError("Non-canonical Base64") }
            guard var fields = try CanonicalCbor.decode(raw).map else { throw CoreError("Expected extension object") }
            try require(fields["$type"] == nil, "Duplicate $type")
            fields["$type"] = .text(String(tag[0].dropFirst(extPrefix.count)))
            _ = try extensionType(fields)
            return fields
        }
    }

    /// Rebuilds the exact record/commit bytes from the core §7.2 tags, enforcing their fixed order.
    public static func restore(_ event: NostrEvent, atprotoPubkey: [UInt8]? = nil) throws -> RepositoryChange {
        try require(event.verify() && event.kind == 1)
        let tags = event.tags
        try require(tags.count >= 6 && tags[0] == topicTag && tags[1] == versionTag, "Not a version 1 post")
        try require(tags.filter { $0 == topicTag }.count == 1)
        func base(_ index: Int, _ name: String) throws -> String {
            try require(tags[index].count == 2 && tags[index][0] == name, "Base tag order")
            return tags[index][1]
        }
        let commitBase64 = try base(2, tagCommit)
        let baseCid = try base(3, tagBase)
        let rkey = try base(4, tagRkey)
        let exp = try base(5, tagExp)
        var next = 6
        var created: String?
        if tags.count > 6, tags[6].first == tagCreated {
            try require(tags[6].count == 2)
            created = tags[6][1]; next = 7
        }
        // Extension tags follow the base tags contiguously; anything after them is foreign and never a base tag.
        let extensionEnd = (next..<tags.count).first { !(tags[$0].first ?? "").hasPrefix(extPrefix) } ?? tags.count
        try require(!(extensionEnd..<tags.count).contains { i in
            let name = tags[i].first ?? ""
            return baseTags.contains(name) || name.hasPrefix(extPrefix)
        }, "Misplaced base or extension tag")
        let extensions = try extensions(tags)
        try require(matches(tidPattern, rkey), "Invalid record key")
        try require(matches(secondsTimestamp, exp) && Instant.parse(exp) != nil, "Deadline must be the basic UTC form")
        if let created {
            try require(matches(timestamp, created) && Instant.parse(created)?.epochSecond == event.createdAt, "created must match created_at")
        }
        var fields: [String: Cbor] = ["$type": .text(collection), "text": .text(event.content), "exp": .text(exp), "ext": .array(extensions.map { .map($0) })]
        if let created { fields["created"] = .text(created) }
        let record = try CanonicalCbor.encode(.map(fields))
        guard let commitBytes = B64.decode(commitBase64) else { throw CoreError("Non-canonical Base64") }
        let change = RepositoryChange(path: "\(collection)/\(rkey)", recordBytes: record, commitBytes: commitBytes, applyFromCommit: baseCid.isEmpty ? nil : baseCid)
        guard let commit = try CanonicalCbor.decode(commitBytes).map else { throw CoreError("Invalid commit") }
        try require(Set(commit.keys) == ["did", "version", "data", "rev", "prev", "sig"])
        guard commit["version"] == .int(3), case .cid(let data)? = commit["data"] else { throw CoreError("Invalid commit") }
        if case .cid? = commit["prev"] {} else { try require(commit["prev"] == .null, "Invalid commit") }
        _ = try cidBytes(data)
        guard let did = commit["did"]?.text, matches(didPattern, did) else { throw CoreError("Invalid DID") }
        guard let rev = commit["rev"]?.text, matches(tidPattern, rev) else { throw CoreError("Invalid revision") }
        if let b = change.applyFromCommit { _ = try cidBytes(b) }
        guard let sig = commit["sig"]?.bytes, sig.count == 64, !Secp.isHighS(sig) else { throw CoreError("Non-low-S signature") }
        if let key = atprotoPubkey {
            try require(key.count == 33 && (key[0] == 2 || key[0] == 3))
            var unsigned = commit; unsigned["sig"] = nil
            try require(Secp.verify(sig, sha256(try CanonicalCbor.encode(.map(unsigned))), key), "Invalid commit signature")
        }
        return change
    }

    /// Author DID from the commit; nil for non-post events (e.g. PLC genesis).
    public static func did(_ event: NostrEvent) -> String? {
        guard let change = try? restore(event), let commit = try? CanonicalCbor.decode(change.commitBytes) else { return nil }
        return commit["did"]?.text
    }

    public static func mstRoot(_ records: [String: String]) throws -> String {
        try require(records.count <= maxRecords)
        let sorted = records.sorted { Array($0.key.utf8).lexicographicallyPrecedes(Array($1.key.utf8)) }
        let pathPattern = try! NSRegularExpression(pattern: "^[a-zA-Z0-9_~.:-]+/[a-zA-Z0-9_~.:-]+$")
        for (k, v) in sorted { try require(k.utf8.count <= 1024 && matches(pathPattern, k)); _ = try cidBytes(v) }
        let keys = sorted.map { Array($0.key.utf8) }
        let layers = keys.map(layer)
        func node(_ from: Int, _ until: Int, _ depth: Int) throws -> Cbor {
            if from == until { return .null }
            try require(depth >= 0)
            let pivots = (from..<until).filter { layers[$0] == depth }
            let left = try node(from, pivots.first ?? until, depth - 1)
            var entries = [Cbor]()
            var lastKey = [UInt8]()
            for (i, pivot) in pivots.enumerated() {
                let key = keys[pivot]
                var prefix = 0
                while prefix < min(lastKey.count, key.count) && lastKey[prefix] == key[prefix] { prefix += 1 }
                let right = try node(pivot + 1, i + 1 < pivots.count ? pivots[i + 1] : until, depth - 1)
                entries.append(.map(["p": .int(Int64(prefix)), "k": .bytes(Array(key[prefix...])), "v": .cid(sorted[pivot].value), "t": right]))
                lastKey = key
            }
            return .cid(cid(try CanonicalCbor.encode(.map(["l": left, "e": .array(entries)]))))
        }
        if case .cid(let root) = try node(0, sorted.count, layers.max() ?? 0) { return root }
        return cid(try CanonicalCbor.encode(.map(["l": .null, "e": .array([])])))
    }

    private static func layer(_ key: [UInt8]) -> Int {
        var zeros = 0
        for b in sha256(key) {
            for shift in stride(from: 6, through: 0, by: -2) { if (b >> shift) & 3 != 0 { return zeros }; zeros += 1 }
        }
        return zeros
    }

    private static let tidAlphabet = Array("234567abcdefghijklmnopqrstuvwxyz")
    static func tid(_ micros: Int64, _ clockId: Int) throws -> String {
        try require(micros >= 1 && micros <= Int64.max >> 10 && (0...1023).contains(clockId), "Timestamp outside TID range")
        return tidOf(UInt64(micros) << 10 | UInt64(clockId))
    }
    /// 13 base32-sortable characters of a 64-bit value (mesh §2.1.1 `mosimo:rkey`).
    static func tidOf(_ value: UInt64) -> String {
        String((0...12).reversed().map { tidAlphabet[Int((value >> (UInt64($0) * 5)) & 31)] })
    }
    /// The 64-bit value of a TID; nil when [s] is not a TID (so the tag value stays a string).
    static func tidValue(_ s: String) -> UInt64? {
        guard matches(tidPattern, s) else { return nil }
        var value: UInt64 = 0
        for c in s { value = value << 5 | UInt64(tidAlphabet.firstIndex(of: c)!) }
        return value
    }

    static func didKey(_ secret: [UInt8]) throws -> String {
        let bytes: [UInt8] = [0xe7, 1] + (try Secp.compressedPublicKey(secret))
        return "did:key:z" + base58(bytes)
    }

    static func extensionType(_ ext: [String: Cbor]) throws -> String {
        guard let type = ext["$type"]?.text else { throw CoreError("Extension requires a Lexicon type reference") }
        let parts = type.split(separator: "#", omittingEmptySubsequences: false).map(String.init)
        let nsid = parts[0]
        try require(nsid.utf8.count <= 317 && (nsid.range(of: ".", options: .backwards).map { nsid[..<$0.lowerBound].utf8.count } ?? 0) <= 253)
        try require(matches(try! NSRegularExpression(pattern:
            "^[a-zA-Z]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+(\\.[a-zA-Z][a-zA-Z0-9]{0,62})$"), nsid))
        try require(parts.count == 1 || parts.count == 2 && parts[1] != "main" && matches(try! NSRegularExpression(pattern: "^[a-zA-Z][a-zA-Z0-9_]*$"), parts[1]))
        return type
    }
}

/// Whole-string match (ICU `$` would also accept a trailing newline).
func matches(_ re: NSRegularExpression, _ s: String) -> Bool {
    let all = NSRange(s.startIndex..., in: s)
    return re.firstMatch(in: s, range: all)?.range == all
}

func base58(_ bytes: [UInt8]) -> String {
    let alphabet = Array("123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")
    var digits = [Int]()
    for b in bytes {
        var carry = Int(b)
        for i in digits.indices { carry += digits[i] << 8; digits[i] = carry % 58; carry /= 58 }
        while carry > 0 { digits.append(carry % 58); carry /= 58 }
    }
    // Leading zero bytes are dropped, as the Android BigInteger form does (the multicodec prefix is never zero).
    return String(digits.reversed().map { alphabet[$0] })
}
