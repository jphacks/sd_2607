import Foundation

/// Three independent keys. Caller must encrypt these before persistence.
public struct SecretKeys: Sendable, CustomStringConvertible {
    public let nostr: [UInt8], atproto: [UInt8], plc: [UInt8]
    public init(nostr: [UInt8], atproto: [UInt8], plc: [UInt8]) throws {
        try require([nostr, atproto, plc].allSatisfy(Secp.secKeyVerify), "Invalid secret key")
        try require(nostr != atproto && nostr != plc && atproto != plc, "Key reuse forbidden")
        self.nostr = nostr; self.atproto = atproto; self.plc = plc
    }
    public static func generate() -> SecretKeys { try! SecretKeys(nostr: Secp.generateKey(), atproto: Secp.generateKey(), plc: Secp.generateKey()) }
    public var description: String { "SecretKeys(nostr=<redacted>, atproto=<redacted>, plc=<redacted>)" }
}

/// [clockId] is the TID clock identifier, chosen once per repository and persisted with it.
public struct RepositoryState: Equatable, Sendable {
    public var records: [String: String] = [:]
    public var previousCommit: String?
    public var lastRevMicros: Int64 = 0
    public var clockId: Int = Int.random(in: 0..<1024)
    public init(records: [String: String] = [:], previousCommit: String? = nil, lastRevMicros: Int64 = 0, clockId: Int = Int.random(in: 0..<1024)) {
        self.records = records; self.previousCommit = previousCommit; self.lastRevMicros = lastRevMicros; self.clockId = clockId
    }
}

public struct CreatedPost: Sendable { public let event: NostrEvent; public let repository: RepositoryState }

/// The ordinary NIP-01 fields; tags are never normalized or sorted.
public struct NostrEvent: Equatable, Hashable, Sendable {
    public let id: String, pubkey: String
    public let createdAt: Int64
    public let kind: Int
    public let tags: [[String]]
    public let content: String
    public let sig: String

    public init(id: String, pubkey: String, createdAt: Int64, kind: Int, tags: [[String]], content: String, sig: String) {
        self.id = id; self.pubkey = pubkey; self.createdAt = createdAt; self.kind = kind; self.tags = tags; self.content = content; self.sig = sig
    }

    public func verify() -> Bool {
        guard (try? validate()) != nil, let s = try? unhex(sig, 64), let p = try? unhex(pubkey, 32) else { return false }
        let digest = sha256(signingBytes())
        return id == hex(digest) && Secp.verifySchnorr(s, digest, p)
    }

    func validate() throws {
        _ = try unhex(id, 32); _ = try unhex(pubkey, 32); _ = try unhex(sig, 64)
        try require((0...9007199254740991).contains(createdAt) && (0...65535).contains(kind))
        // TODO(undecided): local demo limits; preserve all unknown author-signed tags.
        try require(tags.count <= 256 && tags.allSatisfy { $0.count <= 32 })
        try require(content.utf8.count <= 16384)
        try require(signingBytes().count <= CanonicalCbor.maxBytes)
    }

    func signingBytes() -> [UInt8] { nostrSigningBytes(pubkey, createdAt, kind, tags, content) }

    public func toJson() throws -> String {
        try validate()
        return "{\"id\":" + quoteJson(id) + ",\"pubkey\":" + quoteJson(pubkey) + ",\"created_at\":" + String(createdAt) + ",\"kind\":" + String(kind) +
            ",\"tags\":" + tagsJson(tags) + ",\"content\":" + quoteJson(content) + ",\"sig\":" + quoteJson(sig) + "}"
    }

    public static func fromJson(_ text: String) throws -> NostrEvent { try fromJson(Json.parse(text, maxDepth: 16, maxBytes: CanonicalCbor.maxBytes)) }

    public static func fromJson(_ n: Json) throws -> NostrEvent {
        try require(!n.containsFloat(), "Floats forbidden")
        try require(n.keys == ["id", "pubkey", "created_at", "kind", "tags", "content", "sig"], "Unexpected/missing JSON fields")
        guard let k = n["kind"]?.int, (0...65535).contains(k), let created = n["created_at"]?.int, let tagNodes = n["tags"]?.array, tagNodes.count <= 256,
              let id = n["id"]?.string, let pubkey = n["pubkey"]?.string, let content = n["content"]?.string, let sig = n["sig"]?.string
        else { throw CoreError("Invalid Nostr event") }
        let tags: [[String]] = try tagNodes.map { tag in
            guard let a = tag.array, a.count <= 32 else { throw CoreError("Invalid tag") }
            return try a.map { guard let s = $0.string else { throw CoreError("Invalid tag value") }; return s }
        }
        let event = NostrEvent(id: id, pubkey: pubkey, createdAt: created, kind: Int(k), tags: tags, content: content, sig: sig)
        try require(event.verify(), "Invalid Nostr event ID/signature")
        return event
    }
}

func tagsJson(_ tags: [[String]]) -> String { "[" + tags.map { "[" + $0.map(quoteJson).joined(separator: ",") + "]" }.joined(separator: ",") + "]" }
func nostrSigningBytes(_ pubkey: String, _ createdAt: Int64, _ kind: Int, _ tags: [[String]], _ content: String) -> [UInt8] {
    Array(("[0," + quoteJson(pubkey) + "," + String(createdAt) + "," + String(kind) + "," + tagsJson(tags) + "," + quoteJson(content) + "]").utf8)
}

public final class Author: @unchecked Sendable {
    public let keys: SecretKeys
    public let did: String
    public let initialOperationJson: String
    public let nostrPubkey: String
    public let bindingJson: String
    /// Compressed atproto signing key; what a PDS would read from the DID document.
    public let atprotoPubkey: [UInt8]

    public init(keys: SecretKeys, pdsEndpoint: String) throws {
        self.keys = keys
        (did, initialOperationJson) = try AtRepository.identity(keys, pdsEndpoint)
        nostrPubkey = hex(try Secp.xOnlyPublicKey(keys.nostr))
        atprotoPubkey = try Secp.compressedPublicKey(keys.atproto)
        bindingJson = try AtRepository.binding(keys, did, nostrPubkey)
    }

    public func createPost(_ text: String, nowMillis: Int64, previous: RepositoryState = RepositoryState(),
                           deliveryExpiresAtSeconds: Int64? = nil, extensions: [[String: Cbor]] = []) throws -> CreatedPost {
        let expires = deliveryExpiresAtSeconds ?? nowMillis / 1000 + 86400
        try require(nowMillis >= 0 && text.utf8.count <= 16384)
        try require(expires > nowMillis / 1000 && expires <= 999999999999999, "Invalid delivery deadline")
        let (tags, repository) = try AtRepository.create(keys, did, text, nowMillis, previous, expires, extensions)
        return CreatedPost(event: try sign(1, tags, text, nowMillis), repository: repository)
    }

    /// 0001 §1.1.1 exception path: the signed genesis operation as its own control event, never inside posts,
    /// for online nodes to submit unchanged. Only the signed delivery deadline the mesh requires is added.
    public func createPlcGenesisEvent(nowMillis: Int64, deliveryExpiresAtSeconds: Int64? = nil) throws -> NostrEvent {
        let expires = deliveryExpiresAtSeconds ?? nowMillis / 1000 + 86400
        try require(nowMillis >= 0 && expires > nowMillis / 1000 && expires <= 999999999999999)
        let tags = [[AtRepository.tagPrefix + "delivery-expires-at", Instant(epochSecond: expires).description]]
        return try sign(AtRepository.plcGenesisKind, tags, initialOperationJson, nowMillis)
    }

    private func sign(_ kind: Int, _ tags: [[String]], _ content: String, _ nowMillis: Int64) throws -> NostrEvent {
        let digest = sha256(nostrSigningBytes(nostrPubkey, nowMillis / 1000, kind, tags, content))
        let sig = try Secp.signSchnorr(digest, keys.nostr)
        let event = NostrEvent(id: hex(digest), pubkey: nostrPubkey, createdAt: nowMillis / 1000, kind: kind, tags: tags, content: content, sig: hex(sig))
        try require(event.verify())
        return event
    }
}

/// protocol/0012 §4.2 mesh encoding v1: fixed 7-element array with the §4.2 reversible tag-value typing.
public enum EventCodec {
    static let commit = AtRepository.tagPrefix + "commit"
    static let version = AtRepository.tagPrefix + "version"
    static func bytesTag(_ name: String) -> Bool {
        name == commit || name.hasPrefix(AtRepository.extPrefix) && name.count > AtRepository.extPrefix.count
    }

    public static func encode(_ event: NostrEvent) throws -> [UInt8] {
        try require(event.verify(), "Invalid Nostr event")
        let tags: [Cbor] = event.tags.map { tag in
            if tag.count != 2 { return .array(tag.map { .text($0) }) }
            if bytesTag(tag[0]) { return .array([.text(tag[0]), B64.decode(tag[1]).map { .bytes($0) } ?? .text(tag[1])]) }
            if tag[0] == version && tag[1] == "1" { return .array([.text(tag[0]), .int(1)]) }
            return .array(tag.map { .text($0) })
        }
        return try CanonicalCbor.encode(.array([.int(1), .bytes(try unhex(event.pubkey, 32)), .int(event.createdAt), .int(Int64(event.kind)),
                                                .array(tags), .text(event.content), .bytes(try unhex(event.sig, 64))]))
    }

    public static func decode(_ bytes: [UInt8]) throws -> NostrEvent {
        guard let a = try CanonicalCbor.decode(bytes).array, a.count == 7, a[0] == .int(1) else { throw CoreError("Expected payload array") }
        guard let pub = a[1].bytes, pub.count == 32, let created = a[2].int, let kind = a[3].int, (0...65535).contains(kind),
              let tagValues = a[4].array, let content = a[5].text, let sig = a[6].bytes, sig.count == 64 else { throw CoreError("Invalid payload") }
        let tags: [[String]] = try tagValues.map { t in
            guard let tag = t.array, let name = tag.first?.text else { throw CoreError("Expected tag array") }
            return try tag.enumerated().map { i, v in
                if let s = v.text { return s }
                if i == 1 && tag.count == 2, let b = v.bytes, bytesTag(name) { return B64.encode(b) }
                if i == 1 && tag.count == 2 && v == .int(1) && name == version { return "1" }
                throw CoreError("Unexpected typed tag value")
            }
        }
        let pubkey = hex(pub)
        let id = hex(sha256(nostrSigningBytes(pubkey, created, Int(kind), tags, content)))
        let event = NostrEvent(id: id, pubkey: pubkey, createdAt: created, kind: Int(kind), tags: tags, content: content, sig: hex(sig))
        try require(event.verify(), "Invalid Nostr signature")
        return event
    }
}
