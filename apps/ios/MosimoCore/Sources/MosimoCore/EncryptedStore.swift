import CryptoKit
import Foundation

public struct SavedBundle: Sendable {
    public let bytes: [UInt8]
    public let receivedAtMillis: Int64
    public let savedElapsedMillis: Int64?
    public let bootTime: Int64?
    /// Same boot (kernel boot time unchanged) and a monotonic clock that only moved forward: exact downtime.
    public func downtime(currentBoot: Int64?, elapsed: Int64) -> Int64? {
        guard let bootTime, bootTime == currentBoot, let saved = savedElapsedMillis, elapsed >= saved else { return nil }
        return elapsed - saved
    }
}

public struct DiskState: Sendable {
    public static let pdsRelay = PublicDownload.pdsRelay
    public static let defaultRelays = [pdsRelay, "wss://relay.damus.io", "wss://nos.lol"]
    public static let maxRelays = 8
    public static let maxBlocked = 1024
    public static let maxRevocations = 4096
    /// PDS §4.2 / §6 and DM §5: PLC genesis control events stay in the mesh; the PDS relay takes posts, deletion
    /// requests, reports and revocation notices; general relays take everything else (DMs, profiles) too.
    public static func relayWants(_ relay: String, _ event: NostrEvent) -> Bool {
        let topic = AtRepository.controlTopic(event)
        if event.kind == AtRepository.controlKind && topic != AtRepository.revocationTopic { return false }
        return relay != pdsRelay || [1, 5, 1984].contains(event.kind) || topic == AtRepository.revocationTopic
    }

    public var keys: SecretKeys
    public var repository = RepositoryState()
    public var bundles: [SavedBundle] = []
    public var relays = defaultRelays
    public var relayAccepted: [String: Set<String>] = [:]
    // Historical matching-genesis observation only; never restores current authority/PDS acceptance.
    public var plcRegistered = false
    public var pdsEndpoint = "https://pds.invalid"
    public var onlineEnabled = true
    /// Import-time importer authorization records by event ID (attest.importer §3).
    public var publicAudit: [String: String] = [:]
    /// Author Nostr public keys whose events are never stored (mesh §10); never peers or links.
    public var blockedPubkeys: [String] = []
    /// Verified revocation notices (attest.revocation §6); never rolled back.
    public var revocations: [Attest.Revocation] = []
    /// Issuer domain → last successful revocation-list fetch (epoch seconds); the UI's "失効情報の確認時点".
    public var revocationChecked: [String: Int64] = [:]

    public init(keys: SecretKeys, pdsEndpoint: String) { self.keys = keys; self.pdsEndpoint = pdsEndpoint }
}

/// One authenticated ciphertext; keys, repository head and outbox commit together.
/// Format as Android: version byte 1, 12-byte nonce, AES-256-GCM ciphertext‖tag, AAD `dev.jisaku.mosimo.state.v1`.
public final class EncryptedStore {
    static let maxDiskBytes = 16_000_000
    private let file: URL
    private let key: () throws -> SymmetricKey
    private let aad = Data("dev.jisaku.mosimo.state.v1".utf8)
    /// AAD before the dev.jisaku.mosimo rename: such state still opens, and the next save re-seals it under [aad].
    private let legacyAad = Data("dev.oriom.mosimo.state.v1".utf8)

    /// [key] must return the same key every time; a missing key with existing state is corruption, never a silent reset.
    public init(file: URL, key: @escaping () throws -> SymmetricKey) { self.file = file; self.key = key }

    public var exists: Bool { FileManager.default.fileExists(atPath: file.path) }

    public func load() throws -> DiskState? {
        guard exists else { return nil }
        let encrypted = try Data(contentsOf: file)
        try require((29...Self.maxDiskBytes).contains(encrypted.count) && encrypted.first == 1, "Corrupt state")
        let box = try AES.GCM.SealedBox(combined: encrypted.dropFirst()), k = try key()
        var plain: Data
        do { plain = try AES.GCM.open(box, using: k, authenticating: aad) }
        catch CryptoKitError.authenticationFailure { plain = try AES.GCM.open(box, using: k, authenticating: legacyAad) }
        defer { plain.resetBytes(in: 0..<plain.count) }
        let n = try Json.parse(Array(plain), maxDepth: 32)
        try require(n["v"] == .int(1))
        func secret(_ name: String) throws -> [UInt8] {
            guard let s = n[name]?.string, let d = Data(base64Encoded: s), d.count == 32 else { throw CoreError("Invalid key") }
            return Array(d)
        }
        var state = DiskState(keys: try SecretKeys(nostr: secret("nostr"), atproto: secret("atproto"), plc: secret("plc")),
                              pdsEndpoint: n["pdsEndpoint"]?.string ?? "https://pds.invalid")
        guard let records = n["records"]?.object, records.count <= 10_000, let clock = n["clockId"]?.int, (0...1023).contains(clock) else { throw CoreError("Invalid repository") }
        state.repository = RepositoryState(records: Dictionary(try records.map { k, v in
            guard let s = v.string else { throw CoreError("Invalid record") }; return (k, s)
        }, uniquingKeysWith: { a, _ in a }), previousCommit: n["previous"]?.string, lastRevMicros: n["lastRev"]?.int ?? 0, clockId: Int(clock))
        guard let bundles = n["bundles"]?.array, bundles.count <= MeshPolicy.maxEntries else { throw CoreError("Invalid bundles") }
        state.bundles = try bundles.map {
            guard let s = $0["bytes"]?.string, let d = Data(base64Encoded: s), (1...MeshPolicy.maxSavedBundleBytes).contains(d.count),
                  let received = $0["received"]?.int else { throw CoreError("Invalid bundle") }
            return SavedBundle(bytes: Array(d), receivedAtMillis: received, savedElapsedMillis: $0["elapsed"]?.int, bootTime: $0["boot"]?.int)
        }
        guard let accepted = n["accepted"]?.object, accepted.count <= 8 else { throw CoreError("Invalid accepted") }
        state.relayAccepted = Dictionary(try accepted.map { url, ids in
            guard let a = ids.array, a.count <= 128 else { throw CoreError("Invalid accepted") }
            return (url, Set(a.compactMap(\.string)))
        }, uniquingKeysWith: { a, _ in a })
        guard let relays = n["relays"]?.array, relays.count <= DiskState.maxRelays else { throw CoreError("Invalid relays") }
        state.relays = relays.compactMap(\.string)
        state.plcRegistered = n["plcRegistered"]?.bool ?? false
        state.onlineEnabled = n["onlineEnabled"]?.bool ?? true
        state.publicAudit = Dictionary((n["publicAudit"]?.object ?? []).compactMap { id, v in v.string.map { (id, $0) } }, uniquingKeysWith: { a, _ in a })
        // Pre-overhaul DID blocks are dropped: blocking is now by Nostr key (mesh §10).
        state.blockedPubkeys = try (n["blockedPubkeys"]?.array ?? []).map { v in
            guard let s = v.string, (try? unhex(s, 32)) != nil else { throw CoreError("Invalid blocked key") }
            return s
        }
        guard let revocations = (n["revocations"] ?? .array([])).array, revocations.count <= DiskState.maxRevocations else { throw CoreError("Invalid revocations") }
        state.revocations = try revocations.map { r in
            func key(_ name: String) throws -> [UInt8] {
                guard let s = r[name]?.string, s.utf8.count == 64 || s.utf8.count == 66 else { throw CoreError("Invalid revocation key") }
                return try unhex(s)
            }
            guard let issued = r["issuedAt"]?.string.flatMap(Instant.parse), let effective = r["effectiveAt"]?.string.flatMap(Instant.parse),
                  let id = r["id"]?.string else { throw CoreError("Invalid revocation") }
            return try Attest.Revocation(id: try unhex(id, 32), issuer: try key("issuer"), subject: try key("subject"), issuedAt: issued, effectiveAt: effective,
                                         pendingActivation: r["pendingActivation"]?.string.flatMap { Data(base64Encoded: $0) }.map(Array.init),
                                         pendingSig: r["pendingSig"]?.string.flatMap { Data(base64Encoded: $0) }.map(Array.init))
        }
        guard let checked = (n["revocationChecked"] ?? .object([])).object, checked.count <= 64 else { throw CoreError("Invalid revocationChecked") }
        state.revocationChecked = Dictionary(try checked.map { domain, at in
            guard domain.utf8.count <= 253, let t = at.int else { throw CoreError("Invalid revocationChecked") }
            return (domain, t)
        }, uniquingKeysWith: { a, _ in a })
        return state
    }

    public func save(_ state: DiskState, _ entries: [StoredBundle], bootTime: Int64?) throws {
        func b64(_ b: [UInt8]) -> Json { .string(Data(b).base64EncodedString()) }
        let node = Json.object([
            ("v", .int(1)),
            ("nostr", b64(state.keys.nostr)), ("atproto", b64(state.keys.atproto)), ("plc", b64(state.keys.plc)),
            ("onlineEnabled", .bool(state.onlineEnabled)), ("plcRegistered", .bool(state.plcRegistered)), ("pdsEndpoint", .string(state.pdsEndpoint)),
            ("previous", state.repository.previousCommit.map { .string($0) } ?? .null), ("lastRev", .int(state.repository.lastRevMicros)),
            ("clockId", .int(Int64(state.repository.clockId))),
            ("publicAudit", .object(state.publicAudit.sorted { $0.key < $1.key }.map { ($0.key, .string($0.value)) })),
            ("relays", .array(state.relays.map { .string($0) })),
            ("blockedPubkeys", .array(state.blockedPubkeys.map { .string($0) })),
            ("revocations", .array(state.revocations.map { r in
                var o: [(String, Json)] = [("id", .string(hex(r.id))), ("issuer", .string(hex(r.issuer))), ("subject", .string(hex(r.subject))),
                                           ("issuedAt", .string(r.issuedAt.description)), ("effectiveAt", .string(r.effectiveAt.description))]
                if let a = r.pendingActivation, let sig = r.pendingSig { o += [("pendingActivation", b64(a)), ("pendingSig", b64(sig))] }
                return .object(o)
            })),
            ("revocationChecked", .object(state.revocationChecked.sorted { $0.key < $1.key }.map { ($0.key, .int($0.value)) })),
            ("records", .object(state.repository.records.sorted { $0.key < $1.key }.map { ($0.key, .string($0.value)) })),
            ("bundles", .array(entries.map { .object([("bytes", b64($0.bytes)), ("received", .int($0.receivedAtMillis)),
                                                      ("elapsed", .int($0.receivedElapsedMillis)), ("boot", bootTime.map { .int($0) } ?? .null)]) })),
            ("accepted", .object(state.relayAccepted.sorted { $0.key < $1.key }.map { ($0.key, .array($0.value.sorted().map { .string($0) })) })),
        ])
        var plain = Data(node.serialized.utf8)
        defer { plain.resetBytes(in: 0..<plain.count) }
        guard let sealed = try AES.GCM.seal(plain, using: key(), authenticating: aad).combined else { throw CoreError("Seal failed") }
        let encrypted = Data([1]) + sealed
        try require(encrypted.count <= Self.maxDiskBytes)
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        #if os(iOS)
        try encrypted.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try encrypted.write(to: file, options: .atomic)
        #endif
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        var url = file
        try? url.setResourceValues(values)
    }
}
