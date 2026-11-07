import Foundation

public struct RepositoryChange: Sendable {
    public let path: String
    public let recordBytes: [UInt8]
    public let commitBytes: [UInt8]
    public let applyFromCommit: String?
    public var recordCid: String { cid(recordBytes) }
    public var commitCid: String { cid(commitBytes) }
}

/// Standard v3 commits carried in the protocol/0012 §3.2.4 base tags of one kind-1 Nostr event.
public enum AtRepository {
    public static let namespace = "mosimo.oriom.dev"
    public static let tagPrefix = namespace + ":"
    /// TODO(undecided): provisional control-event kind for the signed PLC genesis operation (0012 §3.4, mndt §9.5).
    public static let plcGenesisKind = 8173
    public static let extPrefix = tagPrefix + "ext:"
    public static let collection = "dev.oriom.mosimo.post"
    // ponytail: every post rebuilds the MST from all records (O(n)); keep persistent MST nodes if this grows.
    public static let maxRecords = 10_000
    static let timestamp = try! NSRegularExpression(pattern: "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?Z$")
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

    static func binding(_ keys: SecretKeys, _ did: String, _ nostrPubkey: String) throws -> String {
        // TODO(undecided): declaration schema, consent wording, signature encoding and delivery.
        let body: [(String, Json)] = [("type", .string("mndt.keyBinding")), ("version", .int(1)), ("did", .string(did)),
            ("atprotoPubkey", .string(hex(try Secp.compressedPublicKey(keys.atproto)))), ("nostrPubkey", .string(nostrPubkey)),
            ("consent", .string("Use this key pair for this DID's MNDT publications"))]
        let hash = sha256(try CanonicalCbor.encode(Json.object(body).toCbor()))
        return Json.object([("declaration", .object(body)), ("atprotoSig", .string(B64.padded(try Secp.sign(hash, keys.atproto)))),
                            ("nostrSig", .string(hex(try Secp.signSchnorr(hash, keys.nostr))))]).serialized
    }

    /// Returns the 0012 §3.2.4 tags (protocol `t` tag included) and the next repository state.
    static func create(_ keys: SecretKeys, _ did: String, _ text: String, _ nowMillis: Int64, _ previous: RepositoryState,
                       _ deliveryExpiresAtSeconds: Int64, _ extensions: [[String: Cbor]]) throws -> ([[String]], RepositoryState) {
        try require(previous.records.count < maxRecords, "Repository limit: \(maxRecords) records")
        try require(previous.lastRevMicros >= 0 && (previous.previousCommit == nil) == previous.records.isEmpty, "Inconsistent repository state")
        if let p = previous.previousCommit { _ = try cidBytes(p) }
        let micros = max(nowMillis * 1000, previous.lastRevMicros + 1)
        // rev and record key are separate identifiers (0012 §2.1.1); one monotonic TID may serve both.
        let rev = try tid(micros, previous.clockId)
        let path = "\(collection)/\(rev)"
        try require(previous.records[path] == nil, "Refusing record overwrite")
        let createdAt = Instant(epochMillis: nowMillis).description
        let deliveryExpiresAt = Instant(epochSecond: deliveryExpiresAtSeconds).description
        // Open unions need free-form data-model objects; do not rebuild them from known display fields.
        let extensionTags = try extensions.map { e -> [String] in
            var fields = e; fields["$type"] = nil
            return [extPrefix + (try extensionType(e)), B64.encode(try CanonicalCbor.encode(.map(fields)))]
        }
        let record = try CanonicalCbor.encode(.map(["$type": .text(collection), "text": .text(text), "createdAt": .text(createdAt),
            "deliveryExpiresAt": .text(deliveryExpiresAt), "extensions": .array(extensions.map { .map($0) })]))
        var records = previous.records
        records[path] = cid(record)
        var unsigned: [String: Cbor] = ["did": .text(did), "version": .int(3), "data": .cid(try mstRoot(records)), "rev": .text(rev), "prev": .null]
        let signature = try Secp.sign(sha256(CanonicalCbor.encode(.map(unsigned))), keys.atproto)
        unsigned["sig"] = .bytes(signature)
        let commit = try CanonicalCbor.encode(.map(unsigned))
        // No MST nodes/inclusion proof on this wire: the receiver rebuilds from its saved state.
        let tags = [
            ["t", namespace],
            [tagPrefix + "version", "1"],
            [tagPrefix + "commit", B64.encode(commit)],
            [tagPrefix + "base", previous.previousCommit ?? ""],
            [tagPrefix + "path", path],
            [tagPrefix + "created-at", createdAt],
            [tagPrefix + "delivery-expires-at", deliveryExpiresAt],
        ]
        var next = previous
        next.records = records; next.previousCommit = cid(commit); next.lastRevMicros = micros
        return (tags + extensionTags, next)
    }

    /// The signed delivery deadline (0012 §3.2.4); nil when the event lacks a valid one.
    public static func deliveryExpiresAt(_ event: NostrEvent) -> Instant? {
        (try? timestampTag(event, "delivery-expires-at")).flatMap(Instant.parse)
    }

    /// Checks Nostr, record reconstruction and commit shape. With [atprotoPubkey] (from the DID document) it also
    /// checks the low-S ECDSA commit signature. Neither is DID authentication nor MST inclusion.
    public static func verifyEnvelope(_ event: NostrEvent, atprotoPubkey: [UInt8]? = nil) -> Bool {
        (try? restore(event, atprotoPubkey: atprotoPubkey)) != nil
    }

    /// Rebuilds the exact record/commit bytes from the 0012 §3.2.4 tags.
    public static func restore(_ event: NostrEvent, atprotoPubkey: [UInt8]? = nil) throws -> RepositoryChange {
        try require(event.verify() && event.kind == 1)
        try require(event.tags.filter { $0 == ["t", namespace] }.count == 1)
        try require(try field(event, "version") == "1", "Unsupported base version")
        let extensions: [Cbor] = try event.tags.filter { $0.first?.hasPrefix(extPrefix) == true }.map { tag in
            try require(tag.count == 2)
            guard let raw = B64.decode(tag[1]) else { throw CoreError("Non-canonical Base64") }
            guard var fields = try CanonicalCbor.decode(raw).map else { throw CoreError("Expected extension object") }
            try require(fields["$type"] == nil)
            fields["$type"] = .text(String(tag[0].dropFirst(extPrefix.count)))
            _ = try extensionType(fields)
            return .map(fields)
        }
        let path = try field(event, "path")
        try require(path.hasPrefix(collection + "/") && matches(tidPattern, String(path.dropFirst(collection.count + 1))), "Invalid repository path")
        let createdAt = try timestampTag(event, "created-at")
        try require(Instant.parse(createdAt)?.epochSecond == event.createdAt)
        let record = try CanonicalCbor.encode(.map(["$type": .text(collection), "text": .text(event.content), "createdAt": .text(createdAt),
            "deliveryExpiresAt": .text(try timestampTag(event, "delivery-expires-at")), "extensions": .array(extensions)]))
        guard let commitBytes = B64.decode(try field(event, "commit")) else { throw CoreError("Non-canonical Base64") }
        let base = try field(event, "base")
        let change = RepositoryChange(path: path, recordBytes: record, commitBytes: commitBytes, applyFromCommit: base.isEmpty ? nil : base)
        guard let commit = try CanonicalCbor.decode(commitBytes).map else { throw CoreError("Invalid commit") }
        try require(Set(commit.keys) == ["did", "version", "data", "rev", "prev", "sig"])
        guard commit["version"] == .int(3), commit["prev"] == .null, case .cid(let data)? = commit["data"] else { throw CoreError("Invalid commit") }
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

    /// Author DID from the commit; nil for non-post events (e.g. PLC genesis), which are never blocked.
    public static func did(_ event: NostrEvent) -> String? {
        guard let change = try? restore(event), let commit = try? CanonicalCbor.decode(change.commitBytes) else { return nil }
        return commit["did"]?.text
    }

    static func field(_ event: NostrEvent, _ name: String) throws -> String {
        let found = event.tags.filter { $0.first == tagPrefix + name }
        try require(found.count == 1 && found[0].count == 2, "Missing or duplicate \(name)")
        return found[0][1]
    }
    static func timestampTag(_ event: NostrEvent, _ name: String) throws -> String {
        let value = try field(event, name)
        try require(matches(timestamp, value) && Instant.parse(value) != nil, "Timestamp must be UTC with Z")
        return value
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

    static func tid(_ micros: Int64, _ clockId: Int) throws -> String {
        try require(micros >= 1 && micros <= Int64.max >> 10 && (0...1023).contains(clockId), "Timestamp outside TID range")
        let alphabet = Array("234567abcdefghijklmnopqrstuvwxyz")
        let value = UInt64(micros) << 10 | UInt64(clockId)
        return String((0...12).reversed().map { alphabet[Int((value >> (UInt64($0) * 5)) & 31)] })
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
