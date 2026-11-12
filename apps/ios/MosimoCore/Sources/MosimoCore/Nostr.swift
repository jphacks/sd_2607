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

func expTag(_ deliveryExpiresAtSeconds: Int64) throws -> [String] {
    let exp = Instant(epochSecond: deliveryExpiresAtSeconds).description
    try require(matches(AtRepository.secondsTimestamp, exp), "Deadline outside the basic Timestamp form")
    return [AtRepository.tagExp, exp]
}

public final class Author: @unchecked Sendable {
    public let keys: SecretKeys
    /// Derived offline; this is NOT a claim of PLC registration or DID authentication.
    public let did: String
    public let initialOperationJson: String
    public let nostrPubkey: String
    /// attest.chain binding declaration signed by both keys (data-model map).
    public let binding: [String: Cbor]
    /// Compressed atproto signing key; what a PDS would read from the DID document.
    public let atprotoPubkey: [UInt8]

    public init(keys: SecretKeys, pdsEndpoint: String) throws {
        self.keys = keys
        (did, initialOperationJson) = try AtRepository.identity(keys, pdsEndpoint)
        nostrPubkey = hex(try Secp.xOnlyPublicKey(keys.nostr))
        atprotoPubkey = try Secp.compressedPublicKey(keys.atproto)
        binding = try AtRepository.binding(keys, did, nostrPubkey)
    }

    public func createPost(_ text: String, nowMillis: Int64, previous: RepositoryState = RepositoryState(),
                           deliveryExpiresAtSeconds: Int64? = nil, extensions: [[String: Cbor]] = []) throws -> CreatedPost {
        let expires = deliveryExpiresAtSeconds ?? nowMillis / 1000 + 86400
        try require(nowMillis >= 0 && text.utf8.count <= 16384)
        try require(expires > nowMillis / 1000 && expires <= 253402300799, "Invalid delivery deadline")
        let (tags, repository) = try AtRepository.create(keys, did, text, nowMillis, previous, expires, extensions)
        return CreatedPost(event: try sign(1, tags, text, nowMillis), repository: repository)
    }

    /// PDS §6: the signed genesis operation as its own control event, never inside posts, for online nodes to
    /// submit unchanged. Tags: protocol topic, `mosimo.jisaku.dev/plc`, and the signed delivery deadline.
    public func createPlcGenesisEvent(nowMillis: Int64, deliveryExpiresAtSeconds: Int64? = nil) throws -> NostrEvent {
        let expires = deliveryExpiresAtSeconds ?? nowMillis / 1000 + 86400
        try require(nowMillis >= 0 && expires > nowMillis / 1000)
        return try sign(AtRepository.controlKind, [AtRepository.topicTag, ["t", AtRepository.plcTopic], try expTag(expires)], initialOperationJson, nowMillis)
    }

    /// delete §1-2: NIP-09 deletion request for one of this author's events. A post also carries the commit of the
    /// repository without it, which every later post builds on; the returned state must be saved before sending.
    public func createDeletion(_ target: NostrEvent, nowMillis: Int64, previous: RepositoryState) throws -> CreatedPost {
        try require(target.pubkey == nostrPubkey, "Only this author's events can be deleted")
        let expires = Self.moderationDeadline(target, nowMillis)
        var tags = [AtRepository.topicTag, ["e", target.id], ["k", String(target.kind)]]
        var repository = previous
        if target.kind == 1 {
            let rkey = try AtRepository.restore(target).path.split(separator: "/").last.map(String.init) ?? ""
            let (commit, base, next) = try AtRepository.delete(keys, did, rkey, nowMillis, previous)
            tags += [[AtRepository.tagCommit, B64.encode(commit)], [AtRepository.tagBase, base], [AtRepository.tagRkey, rkey]]
            repository = next
        }
        return CreatedPost(event: try sign(5, tags + [try expTag(expires)], "", nowMillis), repository: repository)
    }

    /// report §1: NIP-56 report of another author's event; [type] is a NIP-56 report type.
    public func createReport(_ target: NostrEvent, type: String, comment: String = "", nowMillis: Int64) throws -> NostrEvent {
        try require(target.pubkey != nostrPubkey, "Own events are not reported")
        try require(Moderation.reportTypes.contains(type) && comment.utf8.count <= 280, "Invalid report")
        let tags = [AtRepository.topicTag, ["e", target.id, type], ["p", target.pubkey, type], try expTag(Self.moderationDeadline(target, nowMillis))]
        return try sign(1984, tags, comment, nowMillis)
    }

    /// delete §1 / report §1: no earlier than the target's own deadline, else 24 h from now.
    static func moderationDeadline(_ target: NostrEvent, _ nowMillis: Int64) -> Int64 {
        max(AtRepository.deliveryExpiresAt(target)?.epochSecond ?? 0, nowMillis / 1000 + 86400)
    }

    /// Any non-post kind (e.g. another app's DM, kind 4). Only the signed delivery deadline the mesh requires is appended.
    public func signEvent(_ kind: Int, _ tags: [[String]], _ content: String, nowMillis: Int64, deliveryExpiresAtSeconds: Int64? = nil) throws -> NostrEvent {
        let expires = deliveryExpiresAtSeconds ?? nowMillis / 1000 + 86400
        try require(kind != 1, "Use createPost")
        try require(nowMillis >= 0 && expires > nowMillis / 1000)
        try require(!tags.contains { $0.first == AtRepository.tagExp }, "Deadline is appended here")
        return try sign(kind, tags + [try expTag(expires)], content, nowMillis)
    }

    private func sign(_ kind: Int, _ tags: [[String]], _ content: String, _ nowMillis: Int64) throws -> NostrEvent {
        let digest = sha256(nostrSigningBytes(nostrPubkey, nowMillis / 1000, kind, tags, content))
        let sig = try Secp.signSchnorr(digest, keys.nostr)
        let event = NostrEvent(id: hex(digest), pubkey: nostrPubkey, createdAt: nowMillis / 1000, kind: kind, tags: tags, content: content, sig: hex(sig))
        try require(event.verify())
        return event
    }
}

/// Mesh §2.1 CBOR v1 payload: the fixed 7-element array, the §2.1.1 reversible tag-value typing and the §2.1.2
/// omitted tags. Attestation references (§2.1.3) are not produced or accepted: this node does not announce the
/// capability, so a payload must arrive fully expanded.
public enum EventCodec {
    public static let version: Int64 = 1
    static func bytesTag(_ name: String) -> Bool {
        name == AtRepository.tagCommit || name.hasPrefix(AtRepository.extPrefix) && name.count > AtRepository.extPrefix.count
    }

    /// The tags a payload omits for this kind; they are reinserted at the front on decode.
    static func omitted(_ kind: Int) -> [[String]] {
        switch kind {
        case 1: return [AtRepository.topicTag, AtRepository.versionTag]
        case AtRepository.controlKind: return [AtRepository.topicTag]
        default: return []
        }
    }

    public static func encode(_ event: NostrEvent) throws -> [UInt8] {
        try require(event.verify(), "Invalid Nostr event")
        let fixed = omitted(event.kind)
        try require(Array(event.tags.prefix(fixed.count)) == fixed, "Event does not follow the fixed tag order")
        let tags: [Cbor] = event.tags.dropFirst(fixed.count).map { tag in
            tag.count != 2 ? .array(tag.map { .text($0) }) : .array([.text(tag[0]), typed(tag[0], tag[1], event.createdAt)])
        }
        return try CanonicalCbor.encode(.array([.int(version), .bytes(try unhex(event.pubkey, 32)), .int(event.createdAt), .int(Int64(event.kind)),
                                                .array(tags), .text(event.content), .bytes(try unhex(event.sig, 64))]))
    }

    /// Mesh §2.1.1: a value is typed only when the typed form restores exactly the original string.
    static func typed(_ name: String, _ value: String, _ createdAt: Int64) -> Cbor {
        if bytesTag(name) { return B64.decode(value).map { .bytes($0) } ?? .text(value) }
        if name == AtRepository.tagBase { return value.isEmpty ? .bytes([]) : Multibase32.decode(value).map { .bytes($0) } ?? .text(value) }
        if name == AtRepository.tagRkey { return AtRepository.tidValue(value).map { .int(Int64($0)) } ?? .text(value) }
        if name == AtRepository.tagExp, let delta = expDelta(value, createdAt) { return .int(delta) }
        return .text(value)
    }
    static func expDelta(_ value: String, _ createdAt: Int64) -> Int64? {
        guard matches(AtRepository.secondsTimestamp, value), let seconds = Instant.parse(value)?.epochSecond else { return nil }
        let delta = seconds - createdAt
        return delta >= 0 && expString(createdAt, delta) == value ? delta : nil
    }
    static func expString(_ createdAt: Int64, _ delta: Int64) -> String? {
        let (sum, overflow) = createdAt.addingReportingOverflow(delta)
        guard !overflow, sum <= 253402300799 else { return nil }
        let s = Instant(epochSecond: sum).description
        return matches(AtRepository.secondsTimestamp, s) ? s : nil
    }

    public static func decode(_ bytes: [UInt8]) throws -> NostrEvent {
        try require(bytes.count <= MeshPolicy.maxPayloadBytes, "payload size limit")
        // Only the CDDL shape is fixed (mesh §2.1); signed inner bytes (commit, extensions) stay strictly canonical.
        guard let a = try CanonicalCbor.decode(bytes, canonical: false).array, a.count == 7, a[0] == .int(version) else {
            throw CoreError("Unsupported mesh encoding version")
        }
        guard let pub = a[1].bytes, pub.count == 32, let created = a[2].int, created >= 0, let kind = a[3].int, (0...65535).contains(kind),
              let tagValues = a[4].array, let content = a[5].text, let sig = a[6].bytes, sig.count == 64 else { throw CoreError("Invalid payload") }
        let tags: [[String]] = try tagValues.map { t in
            guard let tag = t.array, let name = tag.first?.text else { throw CoreError("Expected tag array") }
            return try tag.enumerated().map { i, v in
                if let s = v.text { return s }
                guard i == 1 && tag.count == 2 else { throw CoreError("Typed value outside position 1 of a two-element tag") }
                if let b = v.bytes, bytesTag(name) { return B64.encode(b) }
                if let b = v.bytes, name == AtRepository.tagBase { return b.isEmpty ? "" : Multibase32.encode(b) }
                if let n = v.int, n >= 0, name == AtRepository.tagRkey {
                    let s = AtRepository.tidOf(UInt64(n))
                    try require(matches(AtRepository.tidPattern, s), "Invalid record key")
                    return s
                }
                if let n = v.int, n >= 0, name == AtRepository.tagExp {
                    guard let s = expString(created, n) else { throw CoreError("Deadline outside the Timestamp range") }
                    return s
                }
                throw CoreError("Unexpected typed tag value")
            }
        }
        let pubkey = hex(pub)
        let full = omitted(Int(kind)) + tags
        let id = hex(sha256(nostrSigningBytes(pubkey, created, Int(kind), full, content)))
        let event = NostrEvent(id: id, pubkey: pubkey, createdAt: created, kind: Int(kind), tags: full, content: content, sig: hex(sig))
        try require(event.verify(), "Invalid Nostr signature")
        return event
    }
}
