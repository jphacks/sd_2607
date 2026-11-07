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
    /// The PDS accepts kinds 1 and 5 only; other kinds (DMs, profiles) still go to general relays.
    public static func relayWants(_ relay: String, kind: Int) -> Bool { relay != pdsRelay || kind == 1 || kind == 5 }

    public var keys: SecretKeys
    public var repository = RepositoryState()
    public var bundles: [SavedBundle] = []
    public var relays = defaultRelays
    public var relayAccepted: [String: Set<String>] = [:]
    // Historical matching-genesis observation only; never restores current authority/PDS acceptance.
    public var plcRegistered = false
    public var pdsEndpoint = "https://pds.invalid"
    public var onlineEnabled = true
    public var publicTrust = PublicIngress.Memory()
    public var publicAudit: [String: String] = [:]
    /// Author DIDs whose events are never stored; never peers or links.
    public var blockedDids: [String] = []

    public init(keys: SecretKeys, pdsEndpoint: String) { self.keys = keys; self.pdsEndpoint = pdsEndpoint }
}

/// One authenticated ciphertext; keys, repository head and outbox commit together.
/// Format as Android: version byte 1, 12-byte nonce, AES-256-GCM ciphertext‖tag, AAD `dev.oriom.mosimo.state.v1`.
public final class EncryptedStore {
    static let maxDiskBytes = 16_000_000
    private let file: URL
    private let key: () throws -> SymmetricKey
    private let aad = Data("dev.oriom.mosimo.state.v1".utf8)

    /// [key] must return the same key every time; a missing key with existing state is corruption, never a silent reset.
    public init(file: URL, key: @escaping () throws -> SymmetricKey) { self.file = file; self.key = key }

    public var exists: Bool { FileManager.default.fileExists(atPath: file.path) }

    public func load() throws -> DiskState? {
        guard exists else { return nil }
        let encrypted = try Data(contentsOf: file)
        try require((29...Self.maxDiskBytes).contains(encrypted.count) && encrypted.first == 1, "Corrupt state")
        var plain = try AES.GCM.open(AES.GCM.SealedBox(combined: encrypted.dropFirst()), using: key(), authenticating: aad)
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
        if let trust = n["publicTrust"] {
            guard trust.keys == ["snapshots", "serials"], let snaps = trust["snapshots"]?.object, snaps.count <= PublicIngress.maxRoots,
                  let serials = trust["serials"]?.object, serials.count <= PublicIngress.maxSerials else { throw CoreError("Invalid trust history") }
            state.publicTrust = PublicIngress.Memory(
                snapshots: Dictionary(try snaps.map { id, v in
                    guard let s = v.string, let d = Data(base64Encoded: s), (1...32768).contains(d.count) else { throw CoreError("Invalid snapshot") }
                    return (id, Array(d))
                }, uniquingKeysWith: { a, _ in a }),
                serials: Dictionary(try serials.map { id, v in guard let s = v.string else { throw CoreError("Invalid serial") }; return (id, s) },
                                    uniquingKeysWith: { a, _ in a }))
        }
        state.publicAudit = Dictionary((n["publicAudit"]?.object ?? []).compactMap { id, v in v.string.map { (id, $0) } }, uniquingKeysWith: { a, _ in a })
        state.blockedDids = (n["blockedDids"]?.array ?? []).compactMap(\.string)
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
            ("publicTrust", .object([("snapshots", .object(state.publicTrust.snapshots.sorted { $0.key < $1.key }.map { ($0.key, b64($0.value)) })),
                                     ("serials", .object(state.publicTrust.serials.sorted { $0.key < $1.key }.map { ($0.key, .string($0.value)) }))])),
            ("relays", .array(state.relays.map { .string($0) })),
            ("blockedDids", .array(state.blockedDids.map { .string($0) })),
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
