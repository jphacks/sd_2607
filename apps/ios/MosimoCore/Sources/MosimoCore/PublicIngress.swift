import Foundation

/// Experimental 0020 wire-v1. Trust comes only from local configuration, never an event.
public enum PublicIngress {
    public static let identity = "dev.oriom.mosimo.defs#identity"
    public static let bsaf = "dev.oriom.mosimo.defs#bsafV1"
    public static let priorityType = "dev.oriom.mosimo.defs#priority"
    public static let maxRoots = 8
    public static let maxSerials = 256
    static let safe: Int64 = 9007199254740991

    public struct Selection: Hashable, Sendable { public let botDid: String; public let type: String }
    public struct Scope: Sendable { public let orgIds: Set<String>; public let bsaf: Set<Selection> }
    public struct Root: Sendable {
        public let rootId: String, pubkey: String
        public let enabled: Bool
        public let scope: Scope
        public let snapshotUrl: String
        public let minSequence: Int64
    }
    public struct Config: Sendable {
        public var roots: [Root] = []
        public var selected: Set<Selection> = []
        public init(roots: [Root] = [], selected: Set<Selection> = []) { self.roots = roots; self.selected = selected }
    }
    /// Raw verified snapshot retains sequence/body hash/issuedAt/CRL even after expiry or root removal.
    public struct Memory: Equatable, Sendable {
        public var snapshots: [String: [UInt8]] = [:]
        public var serials: [String: String] = [:]
        public init(snapshots: [String: [UInt8]] = [:], serials: [String: String] = [:]) { self.snapshots = snapshots; self.serials = serials }
    }
    public struct Status: Sendable, CustomStringConvertible {
        public var authorSignature = "unknown", binding = "unknown", chainScope = "unknown", certificateTime = "unknown", revocation = "unknown"
        public var description: String {
            "Status(authorSignature=\(authorSignature), binding=\(binding), chainScope=\(chainScope), certificateTime=\(certificateTime), revocation=\(revocation))"
        }
    }
    public struct Receipt: Sendable, CustomStringConvertible {
        public let rootId, certId, botDid, type: String
        public let sequence: Int64
        public let issuedAt, validUntil, checkedAt: String
        public let priority: Int
        public let certificateNotBefore, certificateNotAfter, issuerSerial: String
        public var description: String {
            "Receipt(rootId=\(rootId), certId=\(certId), botDid=\(botDid), type=\(type), sequence=\(sequence), issuedAt=\(issuedAt), validUntil=\(validUntil), checkedAt=\(checkedAt), priority=\(priority), certificateNotBefore=\(certificateNotBefore), certificateNotAfter=\(certificateNotAfter), issuerSerial=\(issuerSerial))"
        }
    }
    public struct Decision: Sendable {
        public let receipt: Receipt?
        public let status: Status
        public let reason: String
        public let memory: Memory
    }

    /// Cheap insertion-time check after worker validation; caller must fence configuration/history changes.
    public static func readyToInsert(_ r: Receipt, _ event: NostrEvent, _ memory: Memory, _ now: Instant?) -> Bool {
        guard let now, memory.serials[r.issuerSerial] == r.certId,
              let nb = Instant.parse(r.certificateNotBefore), let na = Instant.parse(r.certificateNotAfter),
              let issued = Instant.parse(r.issuedAt), let until = Instant.parse(r.validUntil),
              let deadline = AtRepository.deliveryExpiresAt(event) else { return false }
        return now >= nb && now < na && now >= issued && now < until && Instant(epochSecond: event.createdAt) <= now && deadline > now
    }

    private static func map(_ v: Cbor?) throws -> [String: Cbor] { guard let m = v?.map else { throw CoreError("Expected map") }; return m }
    private static func exact(_ v: Cbor?, _ fields: Set<String>) throws -> [String: Cbor] {
        let m = try map(v); try require(Set(m.keys) == fields, "Unexpected fields"); return m
    }
    private static func text(_ v: Cbor?) throws -> String { guard let s = v?.text else { throw CoreError("Expected text") }; return s }
    private static func number(_ v: Cbor?) throws -> Int64 {
        guard let n = v?.int else { throw CoreError("Expected integer") }
        try require(n >= 0 && n <= safe); return n
    }
    private static func list(_ v: Cbor?) throws -> [Cbor] { guard let a = v?.array else { throw CoreError("Expected array") }; return a }
    private static func label(_ v: Cbor?, _ max: Int) throws -> String {
        let s = try text(v)
        try require((1...max).contains(s.utf8.count) && !s.unicodeScalars.contains { $0.value < 0x20 || $0.value == 0x7f }, "Invalid label")
        return s
    }
    private static func publicKey(_ v: Cbor?) throws -> String {
        let s = try text(v)
        let bytes = try unhex(s, 33)
        try require(bytes[0] == 2 || bytes[0] == 3)
        try require(try Secp.compress(bytes) == bytes, "Invalid curve point")
        return s
    }
    private static func hashId(_ v: Cbor?) throws -> String { let s = try text(v); _ = try unhex(s, 32); return s }
    private static let timePattern = try! NSRegularExpression(pattern: "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")
    private static func time(_ v: Cbor?) throws -> Instant {
        let s = try text(v)
        guard matches(timePattern, s), let t = Instant.parse(s), t.epochSecond >= 0, t.description == s else { throw CoreError("Invalid time") }
        return t
    }
    private static let originalPattern = try! NSRegularExpression(pattern:
        "^([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.[0-9]+)?(?:Z|[+-]([0-9]{2}):([0-9]{2}))$")
    /// Source timestamps can have offsets/fractions; validate the calendar without normalizing the signed text.
    private static func originalTime(_ v: Cbor?) throws {
        let s = try text(v)
        guard matches(originalPattern, s), let m = originalPattern.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) else { throw CoreError("Invalid original timestamp") }
        func group(_ i: Int) -> Int? { Range(m.range(at: i), in: s).flatMap { Int(s[$0]) } }
        let p = (1...6).map { group($0)! }
        try require(p[0] >= 1 && (0...23).contains(p[3]) && (0...59).contains(p[4]) && (0...59).contains(p[5]), "Invalid original calendar date")
        var dc = DateComponents(); dc.year = p[0]; dc.month = p[1]; dc.day = p[2]
        try require((1...12).contains(p[1]) && dc.isValidDate(in: Calendar(identifier: .iso8601)), "Invalid original calendar date")
        if let oh = group(7), let om = group(8) { try require((0...23).contains(oh) && (0...59).contains(om), "Invalid original timestamp offset") }
    }
    private static func sorted(_ v: Cbor?, _ max: Int, min: Int = 1, _ check: (Cbor) throws -> String) throws -> [String] {
        let items = try list(v).map(check)
        try require((min...max).contains(items.count) && zip(items, items.dropFirst()).allSatisfy { lexical($0, $1) }, "Unsorted/duplicate/oversized list")
        return items
    }
    private static func scope(_ v: Cbor?) throws -> Scope {
        let s = try exact(v, ["orgIds", "bsaf"])
        let orgs = Set(try sorted(s["orgIds"], 16) { try label($0, 128) })
        let bots = try list(s["bsaf"]); try require((1...16).contains(bots.count))
        var names = [String]()
        var pairs = [Selection]()
        for item in bots {
            let b = try exact(item, ["botDid", "types"])
            let bot = try text(b["botDid"]); try require(matches(AtRepository.didPattern, bot)); names.append(bot)
            pairs += try sorted(b["types"], 32) { try label($0, 128) }.map { Selection(botDid: bot, type: $0) }
        }
        try require(zip(names, names.dropFirst()).allSatisfy { lexical($0, $1) } && pairs.count <= 64)
        return Scope(orgIds: orgs, bsaf: Set(pairs))
    }
    private static func subset(_ child: Scope, _ parent: Scope) -> Bool { child.orgIds.isSubset(of: parent.orgIds) && child.bsaf.isSubset(of: parent.bsaf) }
    private static func newObject(_ v: Cbor) throws {
        switch v {
        case .text, .bool: break
        case .int: _ = try number(v)
        case .map(let m): for x in m.values { try newObject(x) }
        case .array(let a): for x in a { try newObject(x) }
        default: throw CoreError("Unsupported wire-v1 value")
        }
    }
    private static func signature(_ sig: [UInt8], _ body: Cbor, _ key: String) throws {
        try require(sig.count == 64 && !Secp.isHighS(sig), "Non-low-S/compact signature")
        try require(Secp.verify(sig, sha256(try CanonicalCbor.encode(body)), try unhex(key, 33)), "Invalid ECDSA signature")
    }
    private static func signed(_ v: Cbor, _ key: String) throws -> [String: Cbor] {
        try newObject(v)
        let s = try exact(v, ["body", "sig"])
        let body = try map(s["body"])
        try signature(try unhex(try text(s["sig"]), 64), .map(body), key)
        return body
    }
    private static func digest(_ body: [String: Cbor]) throws -> String { hex(sha256(try CanonicalCbor.encode(.map(body)))) }

    public static func config(_ json: String) throws -> Config {
        let node = try Json.parse(json, maxDepth: 16, maxBytes: CanonicalCbor.maxBytes)
        try require(!node.containsFloat(), "Floats forbidden")
        let value = try CanonicalCbor.decode(try CanonicalCbor.encode(node.toCbor()))
        let c = try exact(value, ["roots", "selected"])
        let rootItems = try list(c["roots"]); try require(rootItems.count <= maxRoots)
        let roots: [Root] = try rootItems.map {
            let r = try exact($0, ["rootId", "rootPubkey", "enabled", "scope", "snapshotUrl", "minSequence"])
            let key = try publicKey(r["rootPubkey"]); let id = try hashId(r["rootId"])
            try require(id == hex(sha256(try unhex(key, 33))), "rootId mismatch")
            let url = try text(r["snapshotUrl"]); let u = URLComponents(string: url)
            try require(url.utf8.count <= 2048 && u?.scheme == "https" && !(u?.host ?? "").isEmpty && u?.user == nil && u?.fragment == nil)
            guard let enabled = r["enabled"]?.bool else { throw CoreError("enabled required") }
            let minSequence = try number(r["minSequence"]); try require(minSequence >= 1)
            return Root(rootId: id, pubkey: key, enabled: enabled, scope: try scope(r["scope"]), snapshotUrl: url, minSequence: minSequence)
        }
        try require(Set(roots.map(\.rootId)).count == roots.count)
        let selItems = try list(c["selected"]); try require(selItems.count <= 64)
        let selection: [Selection] = try selItems.map {
            let s = try exact($0, ["botDid", "type"])
            let did = try text(s["botDid"]); try require(matches(AtRepository.didPattern, did))
            return Selection(botDid: did, type: try label(s["type"], 128))
        }
        try require(Set(selection).count == selection.count)
        return Config(roots: roots, selected: Set(selection))
    }

    private struct Snapshot { let body: [String: Cbor]; let sequence: Int64; let issued: Instant; let until: Instant; let certs: Set<String>; let keys: Set<String> }
    private static func snapshot(_ bytes: [UInt8], _ root: Root) throws -> Snapshot {
        try require((1...32768).contains(bytes.count), "Snapshot size limit")
        let b = try signed(try CanonicalCbor.decode(bytes), root.pubkey)
        _ = try exact(.map(b), ["type", "version", "rootId", "sequence", "issuedAt", "validUntil", "revokedCertIds", "revokedPubkeys"])
        try require(b["type"] == .text("mosimo.oriom.dev/trust-snapshot") && b["version"] == .int(1) && b["rootId"] == .text(root.rootId))
        let seq = try number(b["sequence"]); try require(seq >= 1)
        let issued = try time(b["issuedAt"]), until = try time(b["validUntil"])
        try require((1...3600).contains(until.epochSecond - issued.epochSecond))
        let certs = Set(try sorted(b["revokedCertIds"], 256, min: 0) { try hashId($0) })
        let keys = Set(try sorted(b["revokedPubkeys"], 256, min: 0) { try publicKey($0) })
        try require(certs.count + keys.count <= 256)
        return Snapshot(body: b, sequence: seq, issued: issued, until: until, certs: certs, keys: keys)
    }

    /// Caller MUST durably save returned memory before authorizing any event with it.
    public static func acceptSnapshot(_ root: Root, _ bytes: [UInt8], _ memory: Memory, _ now: Instant?) throws -> Memory {
        guard let now else { throw CoreError("Clock unknown") }
        let next = try snapshot(bytes, root)
        try require(now >= next.issued && now < next.until, "Snapshot stale/future")
        try require(next.sequence >= root.minSequence, "Snapshot below configured minimum")
        if let saved = memory.snapshots[root.rootId] {
            let old = try snapshot(saved, root)
            try require(next.sequence >= old.sequence, "Snapshot rollback")
            if next.sequence == old.sequence { try require(try digest(next.body) == digest(old.body), "Snapshot equivocation") }
            else { try require(next.issued >= old.issued && next.certs.isSuperset(of: old.certs) && next.keys.isSuperset(of: old.keys), "CRL rollback") }
        }
        try require(memory.snapshots[root.rootId] != nil || memory.snapshots.count < maxRoots, "Trust history full")
        var m = memory; m.snapshots[root.rootId] = bytes
        return m
    }

    private static func extensionFields(_ event: NostrEvent, _ type: String) throws -> [String: Cbor] {
        let found = event.tags.filter { $0.first == AtRepository.extPrefix + type }
        try require(found.count == 1 && found[0].count == 2, "Expected one \(type)")
        guard let raw = B64.decode(found[0][1]) else { throw CoreError("Invalid Base64") }
        return try map(try CanonicalCbor.decode(raw))
    }

    public static func priority(_ event: NostrEvent) -> Int {
        guard let p = try? extensionFields(event, priorityType), Set(p.keys) == ["version", "level"], p["version"] == .int(1),
              let level = try? number(p["level"]), level <= 3 else { return 1 }
        return Int(level)
    }

    public static func verify(_ event: NostrEvent, _ config: Config, _ memory: Memory, _ now: Instant?) -> Decision {
        var status = Status()
        var stage = "resources"
        var updated = memory
        do {
            try require(event.tags.count <= 64 && event.tags.filter { $0.first?.hasPrefix(AtRepository.extPrefix) == true }.count <= 16, "Event resource limit")
            stage = "authorSignature"
            try require(event.verify(), "Invalid author signature")
            status.authorSignature = "valid"
            stage = "chainScope"
            try require((try EventCodec.encode(event)).count <= 32768 && AtRepository.verifyEnvelope(event), "Invalid envelope/resource limit")
            let identity = try extensionFields(event, Self.identity)
            _ = try exact(.map(identity), ["version", "chain", "binding"]); try newObject(.map(identity))
            var typed = identity; typed["$type"] = .text(Self.identity)
            try require((try CanonicalCbor.encode(.map(typed))).count <= 20480 && identity["version"] == .int(1))
            let bsaf = try extensionFields(event, Self.bsaf)
            try require(Set(bsaf.keys) == ["tags", "langs", "original"] || Set(bsaf.keys) == ["tags", "langs", "original", "botDefinition"])
            let tags = try list(bsaf["tags"]).map { v -> String in let s = try text(v); try require(s.utf8.count <= 640); return s }
            try require(tags.count <= 8)
            try require(tags.filter { $0.hasPrefix("bsaf:") }.count == 1 && tags.contains("bsaf:v1"))
            for name in ["type", "value", "time", "target", "source"] {
                let found = tags.filter { $0.hasPrefix(name + ":") }
                try require(found.count == 1 && found[0].count > name.count + 1)
            }
            let langs = try list(bsaf["langs"]); try require(langs.count <= 3)
            for l in langs {
                let lang = try text(l)
                try require((1...64).contains(lang.count) && lang.unicodeScalars.allSatisfy { $0.value <= 127 } && validLanguageTag(lang))
            }
            if bsaf.keys.contains("botDefinition") {
                try require(bsaf["botDefinition"] != .null)
                let definition = try map(bsaf["botDefinition"])
                try require(definition.keys.contains("url") && definition.keys.allSatisfy { ["url", "retrievedAt", "sha256"].contains($0) })
                let url = URLComponents(string: try label(definition["url"], 2048))
                let scheme = url?.scheme?.lowercased()
                try require(!(url?.host ?? "").isEmpty && (scheme == "https" || scheme == "http"), "Invalid bot definition URL")
                if definition["retrievedAt"] != nil { try originalTime(definition["retrievedAt"]) }
                if definition["sha256"] != nil { try require(definition["sha256"]?.bytes?.count == 32) }
            }
            let original = try exact(bsaf["original"], ["uri", "cid", "createdAt"])
            let uri = try text(original["uri"])
            let uriPattern = try! NSRegularExpression(pattern: "^at://did:plc:[a-z2-7]{24}/app\\.bsky\\.feed\\.post/[A-Za-z0-9_~.:-]{1,512}$")
            let rkey = uri.split(separator: "/", omittingEmptySubsequences: false).last.map(String.init) ?? ""
            try require(matches(uriPattern, uri) && rkey != "." && rkey != "..", "Invalid original URI")
            _ = try cidBytes(try text(original["cid"])); try originalTime(original["createdAt"])
            let botDid = String(uri.dropFirst(5).prefix { $0 != "/" })
            let selected = Selection(botDid: botDid, type: try label(.text(String(tags.first { $0.hasPrefix("type:") }!.dropFirst(5))), 128))
            try require(config.selected.contains(selected), "Bot/type not selected")
            let chain = try list(identity["chain"]); try require(chain.count == 1, "Unsupported chain: root-to-leaf only")
            try require((try CanonicalCbor.encode(chain[0])).count <= 4096)
            let rawBody = try map(try map(chain[0])["body"])
            guard let root = config.roots.first(where: { $0.enabled && .text($0.rootId) == rawBody["rootId"] }),
                  config.roots.filter({ $0.enabled && .text($0.rootId) == rawBody["rootId"] }).count == 1 else { throw CoreError("Unknown/disabled root") }
            let b = try signed(chain[0], root.pubkey)
            _ = try exact(.map(b), ["type", "version", "serial", "rootId", "issuerPubkey", "subjectPubkey", "subjectDid", "orgId", "orgName", "role",
                                    "permissions", "canDelegate", "scope", "notBefore", "notAfter"])
            try require(b["type"] == .text("mosimo.oriom.dev/identity-certificate") && b["version"] == .int(1))
            let serial = try text(b["serial"]); _ = try unhex(serial, 16)
            let certId = try digest(b); let serialKey = root.pubkey + ":" + serial
            if let seen = memory.serials[serialKey], seen != certId {
                updated.serials[serialKey] = "equivocation"
                throw CoreError("Certificate serial equivocation")
            }
            let key = try publicKey(b["subjectPubkey"])
            try require(b["issuerPubkey"] == .text(root.pubkey) && key != root.pubkey && b["role"] == .text("importer") && b["canDelegate"] == .bool(false) &&
                        b["permissions"] == .array([.text("bsaf-import")]), "Invalid leaf authority")
            let subjectDid = try text(b["subjectDid"]); try require(matches(AtRepository.didPattern, subjectDid))
            let org = try label(b["orgId"], 128); _ = try label(b["orgName"], 256)
            let s = try scope(b["scope"])
            try require(s.orgIds == [org] && subset(s, root.scope) && s.bsaf.contains(selected), "Scope violation")
            status.chainScope = "valid (root-to-leaf, bsaf-import only)"
            stage = "binding"
            let binding = try exact(identity["binding"], ["declaration", "atprotoSig", "nostrSig"])
            let declaration = try exact(binding["declaration"], ["type", "version", "did", "atprotoPubkey", "nostrPubkey", "consent"])
            try require(declaration["type"] == .text("mndt.keyBinding") && declaration["version"] == .int(1) && declaration["did"] == .text(subjectDid) &&
                        declaration["atprotoPubkey"] == .text(key) && declaration["nostrPubkey"] == .text(event.pubkey) &&
                        declaration["consent"] == .text("Use this key pair for this DID's MNDT publications"), "Binding mismatch")
            let atSig = try text(binding["atprotoSig"])
            guard let decoded = Data(base64Encoded: atSig).map(Array.init), atSig.utf8.count == 88, B64.padded(decoded) == atSig else { throw CoreError("Invalid binding signature encoding") }
            try signature(decoded, .map(declaration), key)
            try require(Secp.verifySchnorr(try unhex(try text(binding["nostrSig"]), 64), sha256(try CanonicalCbor.encode(.map(declaration))), try unhex(event.pubkey, 32)),
                        "Invalid reciprocal signature")
            let commit = try map(try CanonicalCbor.decode(try AtRepository.restore(event, atprotoPubkey: try unhex(key, 33)).commitBytes))
            try require(commit["did"] == .text(subjectDid), "Commit DID mismatch")
            status.binding = "valid (both keys + commit; not current PLC authentication)"
            stage = "certificateTime"
            let before = try time(b["notBefore"]), after = try time(b["notAfter"])
            try require((1...31622400).contains(after.epochSecond - before.epochSecond))
            guard let now else { throw CoreError("Clock unknown") }
            try require(now >= before && now < after && event.createdAt >= before.epochSecond && event.createdAt < after.epochSecond &&
                        Instant(epochSecond: event.createdAt) <= now && AtRepository.deliveryExpiresAt(event)! > now, "Certificate/event/deadline outside validity")
            status.certificateTime = "valid at reception and creation"
            stage = "revocation"
            try require(memory.serials[serialKey] != nil || memory.serials.count < maxSerials, "Certificate history full")
            updated.serials[serialKey] = certId
            guard let bytes = memory.snapshots[root.rootId] else { throw CoreError("Snapshot unknown") }
            let snap = try snapshot(bytes, root)
            status.revocation = "stale"
            try require(snap.sequence >= root.minSequence && now >= snap.issued && now < snap.until, "Snapshot stale/future/below minimum")
            status.revocation = "revoked"
            try require(!snap.certs.contains(certId) && !snap.keys.contains(root.pubkey) && !snap.keys.contains(key), "Certificate/key revoked")
            status.revocation = "fresh-as-of(\(snap.issued), \(snap.until), \(snap.sequence))"
            let receipt = Receipt(rootId: root.rootId, certId: certId, botDid: selected.botDid, type: selected.type, sequence: snap.sequence,
                issuedAt: snap.issued.description, validUntil: snap.until.description, checkedAt: now.description, priority: priority(event),
                certificateNotBefore: before.description, certificateNotAfter: after.description, issuerSerial: serialKey)
            return Decision(receipt: receipt, status: status, reason: "Authorized importer testimony; original Bot signature/inclusion not verified", memory: updated)
        } catch {
            switch stage {
            case "authorSignature": status.authorSignature = "invalid"
            case "chainScope": status.chainScope = "not-authorized/unsupported"
            case "binding": status.binding = "invalid"
            case "certificateTime": status.certificateTime = now == nil ? "unknown" : "invalid"
            default: break
            }
            return Decision(receipt: nil, status: status, reason: (error as? CoreError)?.description ?? "Invalid/unsupported ingress", memory: updated)
        }
    }

    /// BCP 47 well-formedness, as `Locale.Builder().setLanguageTag` checks it (the original spelling is kept).
    static func validLanguageTag(_ tag: String) -> Bool {
        let subtags = tag.split(separator: "-", omittingEmptySubsequences: false).map(String.init)
        guard let lang = subtags.first, (2...8).contains(lang.count), lang.allSatisfy(\.isLetter) else { return false }
        return subtags.dropFirst().allSatisfy { (1...8).contains($0.count) && $0.allSatisfy { $0.isLetter || $0.isNumber } }
    }
}
