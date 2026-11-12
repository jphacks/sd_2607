import Foundation

/// Common exchange messages (mesh §5): CBOR arrays whose first element is the numeric type. Encoded with the
/// deterministic encoder; decoded leniently so other implementations' definite-length encodings are accepted.
public enum Msg: Equatable, Sendable {
    public enum Target: Equatable, Sendable {
        case event(side: Int, requestId: Int64, id: [UInt8])
        case transfer(side: Int, transferId: Int64)
        case object(side: Int, requestId: Int64, hash: [UInt8])
    }
    case hello(nonce: [UInt8], maxBundleBytes: Int, routes: Int, initialCredit: Int, maxMessageBytes: Int, extensions: [[String: Cbor]])
    case inventory(ids: [[UInt8]])
    case want(requestId: Int64, ids: [[UInt8]])
    /// [target] is an event ID (WANT) or an object hash (FETCH); [fragment] is the optional first fragment.
    case begin(transferId: Int64, requestId: Int64, target: [UInt8], length: Int, fragment: [UInt8]?)
    case data(transferId: Int64, fragment: [UInt8])
    case credit(transferId: Int64, limit: Int)
    case result(target: Target, code: Int, reason: Int)
    case reconcile(id: Int64, message: [UInt8])
    case fetch(requestId: Int64, hashes: [[UInt8]])
    /// Mesh §5.2: unknown types are ignored.
    case unknown(type: Int64)
}

public enum Messages {
    public static let protocolVersion: Int64 = 1
    /// Mesh §6: the floor every implementation receives, and the ceiling.
    public static let minMessageBytes = 1024
    public static let maxMessageBytes = 65_536
    public static let helloMaxBytes = 1024
    public static let routeGatt = 1, routeLan = 2, routeNearby = 4
    public static let resultStored = 0, resultAlreadyHeld = 1, resultRejected = 2, resultUnavailable = 3, resultCancelled = 4
    public static let reasonNone = 0, reasonResources = 1, reasonSize = 2, reasonInvalid = 3, reasonExpired = 4, reasonPolicy = 5, reasonMissing = 6
    public static let timeHint = "dev.jisaku.mosimo.mesh.time"
    /// Bytes of the `RECONCILE` wrapping subtracted from the message limit (mesh §5.5).
    public static let reconcileOverhead = 24
    /// Upper bound of a `BEGIN` wrapping around its optional first fragment.
    public static let beginOverhead = 64
    /// Bytes of a `DATA` wrapping around one fragment.
    public static let dataOverhead = 24
    /// Per 32-byte ID in an `INVENTORY`/`WANT` list (2-byte bstr header + payload).
    static let idItemBytes = 34

    public static func encode(_ m: Msg) throws -> [UInt8] {
        func ids(_ list: [[UInt8]]) -> Cbor { .array(list.map { .bytes($0) }) }
        let value: Cbor
        switch m {
        case let .hello(nonce, maxBundle, routes, credit, maxMessage, ext):
            value = .array([.int(0), .int(protocolVersion), .bytes(nonce), .int(Int64(maxBundle)), .int(Int64(routes)), .int(Int64(credit)),
                            .int(Int64(maxMessage)), .array(ext.map { .map($0) })])
        case let .inventory(list): value = .array([.int(1), ids(list)])
        case let .want(r, list): value = .array([.int(2), .int(r), ids(list)])
        case let .begin(t, r, target, length, fragment):
            value = .array([.int(3), .int(t), .int(r), .bytes(target), .int(Int64(length))] + (fragment.map { [.bytes($0)] } ?? []))
        case let .data(t, fragment): value = .array([.int(4), .int(t), .bytes(fragment)])
        case let .credit(t, limit): value = .array([.int(5), .int(t), .int(Int64(limit))])
        case let .result(target, code, reason):
            let t: Cbor
            switch target {
            case let .event(side, r, id): t = .array([.int(0), .int(Int64(side)), .int(r), .bytes(id)])
            case let .transfer(side, id): t = .array([.int(1), .int(Int64(side)), .int(id)])
            case let .object(side, r, hash): t = .array([.int(2), .int(Int64(side)), .int(r), .bytes(hash)])
            }
            value = .array([.int(6), t, .int(Int64(code)), .int(Int64(reason))])
        case let .reconcile(id, message): value = .array([.int(7), .int(id), .bytes(message)])
        case let .fetch(r, hashes): value = .array([.int(8), .int(r), ids(hashes)])
        case .unknown: throw CoreError("Cannot encode an unknown message")
        }
        return try CanonicalCbor.encode(value)
    }

    public static func decode(_ bytes: [UInt8]) throws -> Msg {
        try require((1...maxMessageBytes).contains(bytes.count), "Message size")
        guard let a = try CanonicalCbor.decode(bytes, canonical: false).array, let type = a.first?.int else { throw CoreError("Expected message array") }
        func uint(_ i: Int) throws -> Int64 { guard i < a.count, let n = a[i].int, n >= 0 else { throw CoreError("Expected unsigned integer at \(i)") }; return n }
        func int(_ i: Int, _ range: ClosedRange<Int64>) throws -> Int {
            let n = try uint(i); try require(range.contains(n), "Value at \(i) out of range"); return Int(n)
        }
        func bstr(_ i: Int, _ size: Int? = nil) throws -> [UInt8] {
            guard i < a.count, let b = a[i].bytes, size == nil || b.count == size else { throw CoreError("Expected bytes at \(i)") }
            return b
        }
        func ids(_ i: Int) throws -> [[UInt8]] {
            guard i < a.count, let list = a[i].array else { throw CoreError("Expected list at \(i)") }
            let out = try list.map { v -> [UInt8] in guard let b = v.bytes, b.count == 32 else { throw CoreError("Expected 32-byte ID") }; return b }
            try require(out.count <= 2048 && Set(out).count == out.count, "Duplicate or oversized ID list")
            return out
        }
        switch type {
        case 0:
            try require(a.count == 8 && bytes.count <= helloMaxBytes, "HELLO shape")
            try require(try uint(1) == protocolVersion, "Unsupported exchange protocol version")
            guard let extList = a[7].array else { throw CoreError("Expected extensions") }
            let ext = try extList.map { e -> [String: Cbor] in
                guard let m = e.map, m["$type"]?.text != nil else { throw CoreError("Extension requires $type") }
                return m
            }
            try require(Set(ext.compactMap { $0["$type"]?.text }).count == ext.count, "Duplicate HELLO extension")
            return .hello(nonce: try bstr(2, 8), maxBundleBytes: try int(3, 1...65_536), routes: try int(4, 0...0xffff), initialCredit: try int(5, 0...65_536),
                          maxMessageBytes: try int(6, Int64(minMessageBytes)...Int64(maxMessageBytes)), extensions: ext)
        case 1: try require(a.count == 2); return .inventory(ids: try ids(1))
        case 2: try require(a.count == 3); return .want(requestId: try uint(1), ids: try ids(2))
        case 3:
            try require(a.count == 5 || a.count == 6)
            return .begin(transferId: try uint(1), requestId: try uint(2), target: try bstr(3, 32), length: try int(4, 1...Int64(maxMessageBytes)),
                          fragment: a.count == 6 ? try bstr(5) : nil)
        case 4: try require(a.count == 3); return .data(transferId: try uint(1), fragment: try bstr(2))
        case 5: try require(a.count == 3); return .credit(transferId: try uint(1), limit: try int(2, 0...65_536))
        case 6:
            try require(a.count == 4)
            guard let t = a[1].array else { throw CoreError("Expected result target") }
            func tu(_ i: Int) throws -> Int64 { guard i < t.count, let n = t[i].int, n >= 0 else { throw CoreError("Expected unsigned integer in target") }; return n }
            let side = try tu(1)
            try require(side <= 1)
            let target: Msg.Target
            switch try tu(0) {
            case 0:
                guard t.count == 4, let id = t[3].bytes, id.count == 32 else { throw CoreError("Expected ID") }
                target = .event(side: Int(side), requestId: try tu(2), id: id)
            case 1: try require(t.count == 3); target = .transfer(side: Int(side), transferId: try tu(2))
            case 2:
                guard t.count == 4, let h = t[3].bytes, h.count == 32 else { throw CoreError("Expected hash") }
                target = .object(side: Int(side), requestId: try tu(2), hash: h)
            default: throw CoreError("Unknown result target")
            }
            return .result(target: target, code: try int(2, 0...0xffff), reason: try int(3, 0...0xffff))
        case 7: try require(a.count == 3); return .reconcile(id: try uint(1), message: try bstr(2))
        case 8: try require(a.count == 3); return .fetch(requestId: try uint(1), hashes: try ids(2))
        default: return .unknown(type: type)
        }
    }

    /// How many 32-byte IDs fit one list message under [limit] bytes, leaving room for the array/ID wrapping.
    public static func idsPerMessage(_ limit: Int) -> Int { min(max((limit - 16) / idItemBytes, 1), 2048) }
}
