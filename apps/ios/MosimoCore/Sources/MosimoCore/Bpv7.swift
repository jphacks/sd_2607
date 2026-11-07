import Foundation

/// RFC 9171 bundle subset byte-compatible with the vendored dtn7-kotlin `cborMarshal` (Android, ESP32):
/// indefinite outer array, primary block kept byte-exact as received, canonical blocks re-encoded with fresh CRCs.
public struct Bundle: Equatable {
    public enum CrcType: UInt64 { case none = 0, crc16 = 1, crc32 = 2 }

    public struct Primary: Equatable {
        public var version: UInt64 = 7
        public var flags: UInt64 = 0
        public var crcType: CrcType = .crc32
        public var destination: String
        public var source: String
        public var reportTo: String = "dtn:none"
        public var creationTimestamp: UInt64 = 0
        public var sequenceNumber: UInt64
        public var lifetime: UInt64
        public var fragmentOffset: UInt64 = 0
        public var appDataLength: UInt64 = 0
        var isFragment: Bool { flags & 1 != 0 }
        var isAdminRecord: Bool { flags & 2 != 0 }
        static let mustNotFragment: UInt64 = 1 << 2
    }

    public struct Block: Equatable {
        public var type: UInt64
        public var number: UInt64
        public var flags: UInt64 = 0
        public var crcType: CrcType = .crc32
        /// Block-type-specific data, the content of the block's byte string.
        public var data: [UInt8]
    }

    public var primary: Primary
    /// Received primary block encoding; reused verbatim while [primary] is unchanged.
    var primaryWire: [UInt8]?
    var primaryWireFields: Primary?
    public var blocks: [Block]

    public init(primary: Primary, blocks: [Block]) { self.primary = primary; self.blocks = blocks }

    public static let payload: UInt64 = 1, previousNode: UInt64 = 6, bundleAge: UInt64 = 7, hopCount: UInt64 = 10

    public func block(_ type: UInt64) -> Block? { blocks.first { $0.type == type } }

    public var hop: (limit: UInt64, count: UInt64)? {
        guard let d = block(Self.hopCount)?.data, var r = try? BpReader(d), let n = try? r.arrayCount(), n == 2,
              let limit = try? r.uint(), let count = try? r.uint(), r.atEnd else { return nil }
        return (limit, count)
    }
    public var age: UInt64? {
        guard let d = block(Self.bundleAge)?.data, var r = try? BpReader(d), let a = try? r.uint(), r.atEnd else { return nil }
        return a
    }
    public var payloadData: [UInt8]? { block(Self.payload)?.data }

    public mutating func setHop(limit: UInt64, count: UInt64) {
        guard let i = blocks.firstIndex(where: { $0.type == Self.hopCount }) else { return }
        var w = BpWriter(); w.head(4, 2); w.number(limit); w.number(count)
        blocks[i].data = w.out
    }
    public mutating func setAge(_ age: UInt64) {
        guard let i = blocks.firstIndex(where: { $0.type == Self.bundleAge }) else { return }
        var w = BpWriter(); w.number(age)
        blocks[i].data = w.out
    }

    /// Adds [block] numbered like dtn7-kotlin `addBlock`: payload is 1, others take max+1, all inserted before the payload.
    public mutating func add(type: UInt64, data: [UInt8]) {
        if type == Self.payload { blocks.append(Block(type: 1, number: 1, data: data)); return }
        let next = max(1, blocks.map(\.number).max() ?? 1) + 1
        let at = blocks.firstIndex { $0.type == Self.payload } ?? blocks.count
        blocks.insert(Block(type: type, number: next, data: data), at: at)
    }

    public func marshal() -> [UInt8] {
        var out: [UInt8] = [0x9f]
        if let wire = primaryWire, primaryWireFields == primary { out += wire } else { out += Self.encodePrimary(primary) }
        for b in blocks { out += Self.encodeBlock(b) }
        out.append(0xff)
        return out
    }

    static func encodePrimary(_ p: Primary) -> [UInt8] {
        var w = BpWriter()
        w.head(4, 8 + (p.crcType != .none ? 1 : 0) + (p.isFragment ? 2 : 0))
        w.number(p.version); w.number(p.flags); w.number(p.crcType.rawValue)
        w.eid(p.destination); w.eid(p.source); w.eid(p.reportTo)
        w.head(4, 2); w.number(p.creationTimestamp); w.number(p.sequenceNumber)
        w.number(p.lifetime)
        if p.isFragment { w.number(p.fragmentOffset); w.number(p.appDataLength) }
        return withCrc(w.out, p.crcType)
    }

    static func encodeBlock(_ b: Block) -> [UInt8] {
        var w = BpWriter()
        w.head(4, b.crcType == .none ? 5 : 6)
        w.number(b.type); w.number(b.number); w.number(b.flags); w.number(b.crcType.rawValue)
        w.head(2, UInt64(b.data.count)); w.out += b.data
        return withCrc(w.out, b.crcType)
    }

    private static func withCrc(_ body: [UInt8], _ type: CrcType) -> [UInt8] {
        switch type {
        case .none: return body
        case .crc16: let c = crc16(body + [0x42, 0, 0]); return body + [0x42] + c
        case .crc32: let c = crc32c(body + [0x44, 0, 0, 0, 0]); return body + [0x44] + c
        }
    }

    /// Parses and CRC-checks one bundle; [bytes] must contain exactly one bundle.
    public static func unmarshal(_ bytes: [UInt8]) throws -> Bundle {
        var r = try BpReader(bytes)
        let outer = try r.arrayCount()
        let primaryStart = r.pos
        let pcount = try r.arrayCount()
        var p = Primary(destination: "", source: "", sequenceNumber: 0, lifetime: 0)
        p.version = try r.uint(); p.flags = try r.uint()
        guard let crc = CrcType(rawValue: try r.uint()) else { throw CoreError("Unknown CRC type") }
        p.crcType = crc
        p.destination = try r.eid(); p.source = try r.eid(); p.reportTo = try r.eid()
        try require(try r.arrayCount() == 2, "Invalid creation timestamp")
        p.creationTimestamp = try r.uint(); p.sequenceNumber = try r.uint()
        p.lifetime = try r.uint()
        if p.isFragment { p.fragmentOffset = try r.uint(); p.appDataLength = try r.uint() }
        let expected = 8 + (crc != .none ? 1 : 0) + (p.isFragment ? 2 : 0)
        try r.crc(crc, from: primaryStart)
        try r.close(pcount, expected)
        var bundle = Bundle(primary: p, blocks: [])
        bundle.primaryWire = Array(bytes[primaryStart..<r.pos])
        bundle.primaryWireFields = p
        var items = 1
        while outer == nil ? !(try r.peekBreak()) : items < outer! {
            let start = r.pos
            let count = try r.arrayCount()
            var b = Block(type: try r.uint(), number: try r.uint(), flags: try r.uint(), data: [])
            guard let bc = CrcType(rawValue: try r.uint()) else { throw CoreError("Unknown CRC type") }
            b.crcType = bc
            b.data = try r.byteString()
            try r.crc(bc, from: start)
            try r.close(count, bc == .none ? 5 : 6)
            bundle.blocks.append(b)
            items += 1
        }
        if outer == nil { try r.expectBreak() }
        try require(r.atEnd, "trailing CBOR data")
        return bundle
    }
}

// The vendored Jackson generator writes ints minimally while they fit an Int32, else as a full 8-byte uint64.
struct BpWriter {
    var out = [UInt8]()
    mutating func head(_ major: UInt8, _ n: UInt64) {
        let m = major << 5
        switch n {
        case 0..<24: out.append(m | UInt8(n))
        case 24...0xff: out += [m | 24, UInt8(n)]
        case 0x100...0xffff: out += [m | 25, UInt8(n >> 8), UInt8(n & 0xff)]
        case 0x10000...0xffff_ffff: out.append(m | 26); for i in (0..<4).reversed() { out.append(UInt8((n >> (UInt64(i) * 8)) & 0xff)) }
        default: out.append(m | 27); for i in (0..<8).reversed() { out.append(UInt8((n >> (UInt64(i) * 8)) & 0xff)) }
        }
    }
    mutating func number(_ n: UInt64) {
        if n <= UInt64(Int32.max) { head(0, n) } else { out.append(0x1b); for i in (0..<8).reversed() { out.append(UInt8((n >> (UInt64(i) * 8)) & 0xff)) } }
    }
    mutating func eid(_ uri: String) {
        head(4, 2)
        if uri == "dtn:none" { number(1); number(0); return }
        if uri.hasPrefix("ipn:") {
            let parts = uri.dropFirst(4).split(separator: ".").compactMap { UInt64($0) }
            number(2); head(4, 2); number(parts.first ?? 0); number(parts.last ?? 0); return
        }
        number(1)
        let s = Array(uri.dropFirst(4).utf8)
        head(3, UInt64(s.count)); out += s
    }
}

/// Bounded envelope reader (mirrors Android `BoundedCbor` + the Jackson parser's acceptance).
struct BpReader {
    let bytes: [UInt8]
    var pos = 0
    var atEnd: Bool { pos == bytes.count }

    init(_ bytes: [UInt8]) throws {
        try BpReader.preflight(bytes)
        self.bytes = bytes
    }

    mutating func byte() throws -> UInt8 { try require(pos < bytes.count, "truncated CBOR"); defer { pos += 1 }; return bytes[pos] }

    /// (major, value); value nil for indefinite.
    mutating func head() throws -> (UInt8, UInt64?) {
        let h = try byte()
        let major = h >> 5, ai = h & 31
        if ai == 31 { return (major, nil) }
        if ai < 24 { return (major, UInt64(ai)) }
        try require(ai <= 27, "invalid CBOR argument")
        var n: UInt64 = 0
        for _ in 0..<(1 << (ai - 24)) { n = n << 8 | UInt64(try byte()) }
        return (major, n)
    }
    mutating func uint() throws -> UInt64 {
        let (m, v) = try head()
        guard m == 0, let v else { throw CoreError("expected unsigned integer") }
        return v
    }
    /// Definite count, or nil for an indefinite array.
    mutating func arrayCount() throws -> Int? {
        let (m, v) = try head()
        try require(m == 4, "expected array")
        return v.map { Int($0) }
    }
    mutating func close(_ count: Int?, _ expected: Int) throws {
        if let count { try require(count == expected, "unexpected array length") } else { try expectBreak() }
    }
    mutating func peekBreak() throws -> Bool { try require(pos < bytes.count, "truncated CBOR"); return bytes[pos] == 0xff }
    mutating func expectBreak() throws { try require(try byte() == 0xff, "expected break") }
    mutating func byteString() throws -> [UInt8] {
        let (m, v) = try head()
        guard m == 2, let n = v, n <= UInt64(bytes.count - pos) else { throw CoreError("block data must be a definite-length byte string") }
        defer { pos += Int(n) }
        return Array(bytes[pos..<pos + Int(n)])
    }
    mutating func eid() throws -> String {
        try require(try arrayCount() == 2, "invalid eid")
        switch try uint() {
        case 1:
            let (m, v) = try head()
            if m == 0 { try require(v == 0, "invalid dtn eid"); return "dtn:none" }
            guard m == 3, let n = v, n <= UInt64(bytes.count - pos) else { throw CoreError("invalid dtn scheme-specific part") }
            let s = try strictUtf8(bytes[pos..<pos + Int(n)])
            pos += Int(n)
            return "dtn:" + s
        case 2:
            try require(try arrayCount() == 2, "invalid ipn eid")
            return "ipn:\(try uint()).\(try uint())"
        default: throw CoreError("eid unsupported")
        }
    }
    /// Reads the CRC byte string and checks it over [start, end-of-CRC) with the value zeroed.
    mutating func crc(_ type: Bundle.CrcType, from start: Int) throws {
        if type == .none { return }
        let size = type == .crc16 ? 2 : 4
        let received = try byteString()
        try require(received.count == size && bytes[pos - size - 1] == UInt8(0x40 + size), "invalid CRC field")
        var wire = Array(bytes[start..<pos])
        for i in (wire.count - size)..<wire.count { wire[i] = 0 }
        // An indefinite block array's break follows the CRC and is part of the checked bytes.
        if pos < bytes.count, bytes[start] & 31 == 31, bytes[pos] == 0xff { wire.append(0xff) }
        try require((type == .crc16 ? crc16(wire) : crc32c(wire)) == received, "wrong crc")
    }

    /// Shape preflight before allocating claimed lengths: depth/items bounded, no tags, no floats.
    static func preflight(_ bytes: [UInt8]) throws {
        var at = 0, items = 0
        func arg(_ ai: UInt8) throws -> UInt64 {
            if ai < 24 { return UInt64(ai) }
            let width: Int
            switch ai { case 24: width = 1; case 25: width = 2; case 26: width = 4; case 27: width = 8; default: throw CoreError("invalid CBOR argument") }
            try require(at + width <= bytes.count, "truncated CBOR")
            var v: UInt64 = 0
            for _ in 0..<width { let next = UInt64(bytes[at]); at += 1; try require(v <= (UInt64(Int64.max) - next) / 256, "CBOR integer overflow"); v = v * 256 + next }
            return v
        }
        func read(_ depth: Int) throws {
            items += 1
            try require(depth <= 16 && items <= 4096 && at < bytes.count, "CBOR resource limit")
            let head = bytes[at]; at += 1
            let major = head >> 5, ai = head & 31
            if ai == 31 {
                try require(major == 4 || major == 5, "unsupported indefinite CBOR")
                var count = 0
                while at < bytes.count && bytes[at] != 0xff { try read(depth + 1); count += 1 }
                try require(at < bytes.count && (major != 5 || count % 2 == 0), "truncated CBOR container")
                at += 1
                return
            }
            let count = try arg(ai)
            switch major {
            case 0, 1: break
            case 2, 3: try require(count <= UInt64(bytes.count - at), "truncated CBOR string"); at += Int(count)
            case 4, 5:
                try require(count <= 4096, "CBOR container limit")
                for _ in 0..<(Int(count) * (major == 5 ? 2 : 1)) { try read(depth + 1) }
            case 6: throw CoreError("CBOR tags are not part of the BPv7 envelope")
            default: try require((20...22).contains(ai), "unsupported CBOR simple value")
            }
        }
        try read(0)
        try require(at == bytes.count, "trailing CBOR data")
    }
}

private let crc32cTable: [UInt32] = (0..<256).map { i -> UInt32 in
    var c = UInt32(i)
    for _ in 0..<8 { c = c & 1 != 0 ? (c >> 1) ^ 0x82f6_3b78 : c >> 1 }
    return c
}
private let crc16Table: [UInt16] = (0..<256).map { i -> UInt16 in
    var c = UInt16(i)
    for _ in 0..<8 { c = c & 1 != 0 ? (c >> 1) ^ 0x8408 : c >> 1 }
    return c
}
/// Reflected CRC-32C (Castagnoli), big-endian output.
func crc32c(_ b: [UInt8]) -> [UInt8] {
    var sum: UInt32 = 0xffff_ffff
    for x in b { sum = (sum >> 8) ^ crc32cTable[Int((sum ^ UInt32(x)) & 0xff)] }
    sum = ~sum
    return [UInt8(sum >> 24), UInt8((sum >> 16) & 0xff), UInt8((sum >> 8) & 0xff), UInt8(sum & 0xff)]
}
/// Reflected X.25 CRC-16, big-endian output.
func crc16(_ b: [UInt8]) -> [UInt8] {
    var sum: UInt16 = 0xffff
    for x in b { sum = (sum >> 8) ^ crc16Table[Int((sum ^ UInt16(x)) & 0xff)] }
    sum = ~sum
    return [UInt8(sum >> 8), UInt8(sum & 0xff)]
}
