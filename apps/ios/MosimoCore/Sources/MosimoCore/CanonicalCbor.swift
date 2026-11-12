import CryptoKit
import Foundation

public struct CoreError: Error, CustomStringConvertible {
    public let description: String
    public init(_ description: String) { self.description = description }
}

/// Throws [message] unless [condition]; the Swift counterpart of Kotlin `require`.
@inline(__always) func require(_ condition: Bool, _ message: @autoclosure () -> String = "Invalid input") throws {
    if !condition { throw CoreError(message()) }
}

/// Data-model value for the deterministic CBOR subset (atproto DRISL): no floats or arbitrary tags.
public indirect enum Cbor: Equatable, Hashable, Sendable {
    case null
    case bool(Bool)
    case int(Int64)
    case bytes([UInt8])
    case text(String)
    /// CID link (tag 42), base32 `b…` string form.
    case cid(String)
    case array([Cbor])
    case map([String: Cbor])

    public var text: String? { if case .text(let s) = self { return s }; return nil }
    public var int: Int64? { if case .int(let n) = self { return n }; return nil }
    public var bytes: [UInt8]? { if case .bytes(let b) = self { return b }; return nil }
    public var array: [Cbor]? { if case .array(let a) = self { return a }; return nil }
    public var map: [String: Cbor]? { if case .map(let m) = self { return m }; return nil }
    public var bool: Bool? { if case .bool(let b) = self { return b }; return nil }
    public var isNull: Bool { self == .null }
    public subscript(key: String) -> Cbor? { map?[key] }
}

/// Bounded deterministic CBOR subset: no floats, indefinite lengths, or arbitrary tags.
public enum CanonicalCbor {
    // TODO(undecided): experimental resource limits, not protocol-wide constants.
    public static let maxBytes = 262144
    static let maxItems = 8192
    static let maxDepth = 16

    public static func encode(_ value: Cbor) throws -> [UInt8] {
        var out = [UInt8]()
        var items = 0
        func head(_ major: UInt8, _ n: UInt64) {
            let m = major << 5
            switch n {
            case 0..<24: out.append(m | UInt8(n))
            case 24...0xff: out += [m | 24, UInt8(n)]
            case 0x100...0xffff: out += [m | 25, UInt8(n >> 8), UInt8(n & 0xff)]
            case 0x10000...0xffff_ffff: out.append(m | 26); for i in (0..<4).reversed() { out.append(UInt8((n >> (UInt64(i) * 8)) & 0xff)) }
            default: out.append(m | 27); for i in (0..<8).reversed() { out.append(UInt8((n >> (UInt64(i) * 8)) & 0xff)) }
            }
        }
        func write(_ v: Cbor, _ depth: Int) throws {
            items += 1
            try require(depth <= maxDepth && items <= maxItems, "CBOR resource limit")
            switch v {
            case .null: out.append(0xf6)
            case .bool(let b): out.append(b ? 0xf5 : 0xf4)
            case .int(let n): if n >= 0 { head(0, UInt64(n)) } else { head(1, UInt64(-1 - n)) }
            case .bytes(let b): head(2, UInt64(b.count)); out += b
            case .text(let s): let b = Array(s.utf8); head(3, UInt64(b.count)); out += b
            case .cid(let s): head(6, 42); try write(.bytes([0] + cidBytes(s, allowRaw: true)), depth + 1)
            case .array(let a): head(4, UInt64(a.count)); for x in a { try write(x, depth + 1) }
            case .map(let m):
                head(5, UInt64(m.count))
                for key in m.keys.sorted(by: { compareBytes(Array($0.utf8), Array($1.utf8)) < 0 }) {
                    try write(.text(key), depth + 1); try write(m[key]!, depth + 1)
                }
            }
            try require(out.count <= maxBytes, "CBOR size limit")
        }
        try write(value, 0)
        return out
    }

    /// [canonical] (the default) additionally requires the bytes to be the deterministic re-encoding of the value,
    /// which signed objects and mesh payloads must satisfy. Exchange messages from other implementations are
    /// decoded leniently (definite lengths only; floats, tags other than CID and duplicate keys still rejected).
    public static func decode(_ bytes: [UInt8], canonical: Bool = true) throws -> Cbor {
        try require(bytes.count <= maxBytes, "CBOR size limit")
        var pos = 0
        var items = 0
        func byte() throws -> UInt8 { try require(pos < bytes.count, "Truncated CBOR"); defer { pos += 1 }; return bytes[pos] }
        func read(_ depth: Int) throws -> Cbor {
            items += 1
            try require(depth <= maxDepth && items <= maxItems, "CBOR resource limit")
            let h = try byte()
            let major = h >> 5
            let ai = h & 31
            if major == 7 {
                switch ai { case 20: return .bool(false); case 21: return .bool(true); case 22: return .null
                default: throw CoreError("Unsupported CBOR simple/float") }
            }
            var n: UInt64
            switch ai {
            case 0...23: n = UInt64(ai)
            case 24...27:
                n = 0
                for _ in 0..<(1 << (ai - 24)) {
                    let b = try byte()
                    try require(n <= (UInt64(Int64.max) - UInt64(b)) / 256, "CBOR integer overflow")
                    n = n * 256 + UInt64(b)
                }
            default: throw CoreError("Indefinite/reserved CBOR")
            }
            func size() throws -> Int { try require(n <= UInt64(maxBytes) && n <= UInt64(bytes.count - pos)); return Int(n) }
            switch major {
            case 0: return .int(Int64(n))
            case 1: return .int(-1 - Int64(n))
            case 2: let end = pos + (try size()); defer { pos = end }; return .bytes(Array(bytes[pos..<end]))
            case 3:
                let end = pos + (try size())
                let s = try strictUtf8(bytes[pos..<end])
                pos = end
                return .text(s)
            case 4:
                try require(n <= UInt64(maxItems))
                var a = [Cbor](); for _ in 0..<n { a.append(try read(depth + 1)) }
                return .array(a)
            case 5:
                try require(n <= UInt64(maxItems))
                var m = [String: Cbor]()
                for _ in 0..<n {
                    guard case .text(let key) = try read(depth + 1) else { throw CoreError("Non-string CBOR key") }
                    try require(m[key] == nil, "Duplicate CBOR key")
                    m[key] = try read(depth + 1)
                }
                return .map(m)
            case 6:
                try require(n == 42, "Unknown CBOR tag")
                guard case .bytes(let b) = try read(depth + 1), b.count == 37, b[0] == 0 else { throw CoreError("Invalid CID link") }
                let cid = "b" + base32(Array(b[1...]))
                _ = try cidBytes(cid, allowRaw: true)
                return .cid(cid)
            default: throw CoreError("Unknown CBOR type")
            }
        }
        let value = try read(0)
        try require(pos == bytes.count, "Trailing CBOR")
        try require(!canonical || (try encode(value)) == bytes, "Non-canonical CBOR")
        return value
    }

    /// Length of the first complete CBOR data item in [bytes] starting at [from], or nil while it is still incomplete.
    /// Used to cut CBOR sequences (RFC 8742) on byte streams. Throws when the head is malformed, indefinite, or claims
    /// more than [limit] bytes, so the link can close rather than resynchronize.
    public static func itemLength(_ bytes: [UInt8], from: Int, end: Int, limit: Int) throws -> Int? {
        var at = from
        var steps = 0
        // Each pending container contributes its remaining item count (map entries counted twice).
        var pending: [Int64] = [1]
        while !pending.isEmpty {
            try require(at - from <= limit, "Message exceeds limit")
            if at + 1 > end { return nil }
            let head = bytes[at]
            let major = head >> 5
            let ai = head & 31
            try require(ai != 31, "Indefinite CBOR")
            let width: Int
            switch ai { case 0...23: width = 0; case 24: width = 1; case 25: width = 2; case 26: width = 4; case 27: width = 8
            default: throw CoreError("Reserved CBOR") }
            if at + 1 + width > end { return nil }
            var n: Int64 = 0
            for i in 0..<width {
                let b = Int64(bytes[at + 1 + i])
                try require(n <= (Int64.max - b) / 256, "CBOR integer overflow")
                n = n * 256 + b
            }
            if width == 0 { n = Int64(ai) }
            at += 1 + width
            let top = pending.removeLast() - 1
            if top > 0 { pending.append(top) }
            switch major {
            case 2, 3:
                try require(n <= Int64(limit), "Message exceeds limit")
                if at + Int(n) > end { return nil }
                at += Int(n)
            case 4: try require(n <= Int64(limit), "CBOR container limit"); if n > 0 { pending.append(n) }
            case 5: try require(n <= Int64(limit / 2), "CBOR container limit"); if n > 0 { pending.append(n * 2) }
            case 6: pending.append(1)
            case 7: try require((20...24).contains(ai), "Unsupported CBOR simple value")
            default: break
            }
            steps += 1
            try require(steps <= 1_000_000, "CBOR resource limit")
        }
        try require(at - from <= limit, "Message exceeds limit")
        return at - from
    }
}

/// UTF-8 that must round-trip exactly (no replacement characters, no BOM stripping).
func strictUtf8<C: Collection>(_ bytes: C) throws -> String where C.Element == UInt8 {
    let s = String(decoding: bytes, as: UTF8.self)
    try require(s.utf8.elementsEqual(bytes), "Invalid UTF-8")
    return s
}

func compareBytes(_ a: [UInt8], _ b: [UInt8]) -> Int {
    if a.count != b.count { return a.count < b.count ? -1 : 1 }
    for (x, y) in zip(a, b) where x != y { return x < y ? -1 : 1 }
    return 0
}

/// Plain bytewise order (shorter prefix first), for wire-v1 sorted lists.
func lexical(_ a: String, _ b: String) -> Bool { Array(a.utf8).lexicographicallyPrecedes(Array(b.utf8)) }

public func sha256(_ b: [UInt8]) -> [UInt8] { Array(SHA256.hash(data: b)) }
public func hex(_ b: [UInt8]) -> String { b.map { String(format: "%02x", $0) }.joined() }
public func unhex(_ s: String, _ size: Int) throws -> [UInt8] {
    let chars = Array(s.utf8)
    try require(chars.count == size * 2 && chars.allSatisfy { (48...57).contains($0) || (97...102).contains($0) }, "Invalid lowercase hex")
    func v(_ c: UInt8) -> UInt8 { c <= 57 ? c - 48 : c - 87 }
    return (0..<size).map { v(chars[$0 * 2]) << 4 | v(chars[$0 * 2 + 1]) }
}
/// Any-length lowercase hex, for test fixtures and stored values.
public func unhex(_ s: String) throws -> [UInt8] { try unhex(s, s.utf8.count / 2) }

private let b32: [Character] = Array("abcdefghijklmnopqrstuvwxyz234567")
public func base32(_ bytes: [UInt8]) -> String {
    var out = ""; var acc = 0; var bits = 0
    for b in bytes { acc = (acc << 8) | Int(b); bits += 8; while bits >= 5 { bits -= 5; out.append(b32[(acc >> bits) & 31]) }; acc &= 0xff }
    if bits > 0 { out.append(b32[(acc << (5 - bits)) & 31]) }
    return out
}
public func cid(_ bytes: [UInt8]) -> String { "b" + base32([1, 0x71, 0x12, 0x20] + sha256(bytes)) }
func cidBytes(_ s: String, allowRaw: Bool = false) throws -> [UInt8] {
    let chars = Array(s)
    try require(chars.count == 59 && chars[0] == "b", "Expected CIDv1 base32 sha256")
    var out = [UInt8](); var acc = 0; var bits = 0
    for c in chars.dropFirst() {
        guard let n = b32.firstIndex(of: c) else { throw CoreError("Invalid CID") }
        acc = (acc << 5) | n; bits += 5
        if bits >= 8 { bits -= 8; out.append(UInt8((acc >> bits) & 0xff)) }
        acc &= 0xfff
    }
    try require(out.count == 36 && out[0] == 1 && (out[1] == 0x71 || allowRaw && out[1] == 0x55) &&
        out[2] == 0x12 && out[3] == 0x20 && "b" + base32(out) == s, "Invalid CID")
    return out
}

/// Core §3.3: RFC 4648 standard alphabet, no padding, one canonical spelling.
public enum B64 {
    public static func encode(_ bytes: [UInt8]) -> String { Data(bytes).base64EncodedString().replacingOccurrences(of: "=", with: "") }
    public static func decode(_ s: String) -> [UInt8]? {
        guard s.utf8.count <= CanonicalCbor.maxBytes * 4 / 3 + 4,
              s.utf8.allSatisfy({ (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || $0 == 43 || $0 == 47 }),
              let d = Data(base64Encoded: s + String(repeating: "=", count: (4 - s.utf8.count % 4) % 4)) else { return nil }
        let b = Array(d)
        return encode(b) == s ? b : nil
    }
    /// Padded standard Base64 (Java `Base64.getEncoder()`), used only by the binding's `atprotoSig`.
    public static func padded(_ bytes: [UInt8]) -> String { Data(bytes).base64EncodedString() }
    public static func urlUnpadded(_ bytes: [UInt8]) -> String {
        encode(bytes).replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
    }
}

/// Multibase `b` (lowercase base32, no padding) as used by CID strings; only exact round trips decode.
public enum Multibase32 {
    public static func encode(_ bytes: [UInt8]) -> String { "b" + base32(bytes) }
    public static func decode(_ s: String) -> [UInt8]? {
        let chars = Array(s)
        guard chars.count >= 2, chars[0] == "b", chars.count <= CanonicalCbor.maxBytes else { return nil }
        var out = [UInt8](); var acc = 0; var bits = 0
        for c in chars.dropFirst() {
            guard let n = b32.firstIndex(of: c) else { return nil }
            acc = (acc << 5) | n; bits += 5
            if bits >= 8 { bits -= 8; out.append(UInt8((acc >> bits) & 0xff)) }
            acc &= 0xfff
        }
        return encode(out) == s ? out : nil
    }
}
