import Foundation

/// Negentropy v1 range-based set reconciliation (NIP-77) over `(created_at, event ID)` items, as the mesh §5.5
/// `RECONCILE` payload. One instance holds one side's candidate set for one reconciliation. The initiator calls
/// [initiate] and then [reconcile] on each reply until it returns nil; the responder calls [reconcile] on each
/// query. [frameSizeLimit] bounds one message (0 = unlimited); each `RECONCILE` must still fit the peer's limit.
public final class Negentropy {
    public static let protocolVersion: UInt8 = 0x61
    public static let idSize = 32
    public static let fingerprintSize = 16
    private static let buckets = 16
    private static let modeSkip: UInt64 = 0, modeFingerprint: UInt64 = 1, modeIdList: UInt64 = 2
    private static let infinity = Bound(timestamp: Int64.max, prefix: [])

    public struct Item: Sendable {
        public let timestamp: Int64
        public let id: [UInt8]
        public init(timestamp: Int64, id: [UInt8]) throws {
            try require(timestamp >= 0 && id.count == Negentropy.idSize)
            self.timestamp = timestamp; self.id = id
        }
    }
    /// A range boundary: a timestamp and an ID prefix; [infinity] is the open upper end.
    private struct Bound { let timestamp: Int64; let prefix: [UInt8] }

    private let items: [Item]
    private let frameSizeLimit: Int
    private var isInitiator = false
    private var have = [[UInt8]]()
    private var need = [[UInt8]]()
    private var lastTimestampIn: Int64 = 0
    private var lastTimestampOut: Int64 = 0

    /// IDs only this side holds (after the initiator finishes).
    public var haveIds: [[UInt8]] { have }
    /// IDs only the peer holds.
    public var needIds: [[UInt8]] { need }

    public init(_ items: [Item], frameSizeLimit: Int = 0) throws {
        try require(frameSizeLimit == 0 || frameSizeLimit >= 512, "Frame size limit too small")
        let sorted = items.sorted { Self.compare($0.timestamp, $0.id, $1.timestamp, $1.id) < 0 }
        for i in sorted.indices.dropFirst() {
            try require(Self.compare(sorted[i - 1].timestamp, sorted[i - 1].id, sorted[i].timestamp, sorted[i].id) != 0, "Duplicate item")
        }
        self.items = sorted
        self.frameSizeLimit = frameSizeLimit
    }

    public func initiate() -> [UInt8] {
        isInitiator = true
        lastTimestampOut = 0
        var out: [UInt8] = [Self.protocolVersion]
        splitRange(0, items.count, Self.infinity, &out)
        return out
    }

    /// Processes one message from the peer; returns the next message, or nil when an initiator has finished.
    public func reconcile(_ query: [UInt8]) throws -> [UInt8]? {
        let out = try reconcileAux(query)
        return out.count == 1 && isInitiator ? nil : out
    }

    private func reconcileAux(_ queryBytes: [UInt8]) throws -> [UInt8] {
        lastTimestampIn = 0; lastTimestampOut = 0
        var query = Reader(queryBytes)
        var full: [UInt8] = [Self.protocolVersion]
        let version = try query.byte()
        try require((0x60...0x6f).contains(version), "Invalid negentropy protocol version")
        if version != Self.protocolVersion {
            try require(!isInitiator, "Unsupported negentropy protocol version")
            return full
        }
        var prevBound = Bound(timestamp: 0, prefix: [])
        var prevIndex = 0
        // Index up to which ranges have actually been written to [full]; a dropped range is re-fingerprinted from here.
        var writtenIndex = 0
        var skip = false
        while !query.isEmpty {
            var o = [UInt8]()
            func doSkip() { if skip { skip = false; encodeBound(prevBound, &o); Self.encodeVarInt(Self.modeSkip, &o) } }
            let currBound = try decodeBound(&query)
            let mode = try Self.decodeVarInt(&query)
            let lower = prevIndex
            var upper = findLowerBound(prevIndex, items.count, currBound)
            switch mode {
            case Self.modeSkip: skip = true
            case Self.modeFingerprint:
                let theirs = try query.bytes(Self.fingerprintSize)
                if theirs != fingerprint(lower, upper) { doSkip(); splitRange(lower, upper, currBound, &o) } else { skip = true }
            case Self.modeIdList:
                let count = try Self.decodeVarInt(&query)
                try require(count <= 1_000_000, "ID list too long")
                var theirs = [[UInt8]: [UInt8]]()
                var theirOrder = [[UInt8]]()
                for _ in 0..<count {
                    let id = try query.bytes(Self.idSize)
                    if theirs[id] == nil { theirOrder.append(id) }
                    theirs[id] = id
                }
                if isInitiator {
                    skip = true
                    for i in lower..<upper where theirs.removeValue(forKey: items[i].id) == nil { have.append(items[i].id) }
                    need += theirOrder.filter { theirs[$0] != nil }
                } else {
                    doSkip()
                    var ids = [UInt8]()
                    var responded: UInt64 = 0
                    var endBound = currBound
                    for i in lower..<upper {
                        if exceeded(full.count + o.count + ids.count) { endBound = Bound(timestamp: items[i].timestamp, prefix: items[i].id); upper = i; break }
                        ids += items[i].id; responded += 1
                    }
                    encodeBound(endBound, &o); Self.encodeVarInt(Self.modeIdList, &o); Self.encodeVarInt(responded, &o); o += ids
                    full += o; o.removeAll()
                    writtenIndex = upper
                }
            default: throw CoreError("Unexpected negentropy mode")
            }
            if exceeded(full.count + o.count) {
                // Stop here and fingerprint everything the peer has not yet seen covered, up to infinity.
                encodeBound(Self.infinity, &full); Self.encodeVarInt(Self.modeFingerprint, &full); full += fingerprint(writtenIndex, items.count)
                break
            }
            if !o.isEmpty { full += o; writtenIndex = upper }
            prevIndex = upper
            prevBound = currBound
        }
        return full
    }

    private func splitRange(_ lower: Int, _ upper: Int, _ upperBound: Bound, _ o: inout [UInt8]) {
        let numElems = upper - lower
        // An ID list must fit one frame; with small frames, split instead (still a valid message for any peer).
        if numElems < Self.buckets * 2 && (frameSizeLimit == 0 || numElems * Self.idSize + 64 <= frameSizeLimit - 200) {
            encodeBound(upperBound, &o); Self.encodeVarInt(Self.modeIdList, &o); Self.encodeVarInt(UInt64(numElems), &o)
            for i in lower..<upper { o += items[i].id }
            return
        }
        let buckets = min(Self.buckets, numElems)
        let perBucket = numElems / buckets
        let extra = numElems % buckets
        var curr = lower
        for i in 0..<buckets {
            let size = perBucket + (i < extra ? 1 : 0)
            let fp = fingerprint(curr, curr + size)
            curr += size
            let next = curr == upper ? upperBound : minimalBound(items[curr - 1], items[curr])
            encodeBound(next, &o); Self.encodeVarInt(Self.modeFingerprint, &o); o += fp
        }
    }

    private func minimalBound(_ prev: Item, _ curr: Item) -> Bound {
        if curr.timestamp != prev.timestamp { return Bound(timestamp: curr.timestamp, prefix: []) }
        var shared = 0
        while shared < Self.idSize && curr.id[shared] == prev.id[shared] { shared += 1 }
        return Bound(timestamp: curr.timestamp, prefix: Array(curr.id.prefix(shared + 1)))
    }

    private func findLowerBound(_ from: Int, _ to: Int, _ bound: Bound) -> Int {
        var lo = from, hi = to
        while lo < hi {
            let mid = (lo + hi) / 2
            if Self.compare(bound.timestamp, bound.prefix, items[mid].timestamp, items[mid].id) <= 0 { hi = mid } else { lo = mid + 1 }
        }
        return lo
    }

    /// Sum of the IDs as 256-bit little-endian integers mod 2^256, then SHA-256 over sum || varint(count), first 16 bytes.
    private func fingerprint(_ from: Int, _ to: Int) -> [UInt8] {
        var acc = [Int](repeating: 0, count: Self.idSize)
        for i in from..<to {
            var carry = 0
            for b in 0..<Self.idSize {
                let sum = acc[b] + Int(items[i].id[b]) + carry
                acc[b] = sum & 255; carry = sum >> 8
            }
        }
        var input = acc.map { UInt8($0) }
        Self.encodeVarInt(UInt64(to - from), &input)
        return Array(sha256(input).prefix(Self.fingerprintSize))
    }

    private func exceeded(_ size: Int) -> Bool { frameSizeLimit != 0 && size > frameSizeLimit - 200 }

    private func encodeBound(_ bound: Bound, _ o: inout [UInt8]) {
        encodeTimestampOut(bound.timestamp, &o)
        Self.encodeVarInt(UInt64(bound.prefix.count), &o)
        o += bound.prefix
    }
    private func decodeBound(_ r: inout Reader) throws -> Bound {
        let timestamp = try decodeTimestampIn(&r)
        let length = try Self.decodeVarInt(&r)
        try require(length <= UInt64(Self.idSize), "ID prefix too long")
        return Bound(timestamp: timestamp, prefix: try r.bytes(Int(length)))
    }
    private func encodeTimestampOut(_ timestamp: Int64, _ o: inout [UInt8]) {
        if timestamp == Int64.max { lastTimestampOut = Int64.max; Self.encodeVarInt(0, &o); return }
        let delta = timestamp - lastTimestampOut
        lastTimestampOut = timestamp
        Self.encodeVarInt(UInt64(delta + 1), &o)
    }
    private func decodeTimestampIn(_ r: inout Reader) throws -> Int64 {
        let raw = try Self.decodeVarInt(&r)
        try require(raw <= UInt64(Int64.max), "Timestamp overflow")
        let timestamp = raw == 0 ? Int64.max : Int64(raw) - 1
        if lastTimestampIn == Int64.max || timestamp == Int64.max { lastTimestampIn = Int64.max; return Int64.max }
        let (absolute, overflow) = timestamp.addingReportingOverflow(lastTimestampIn)
        try require(!overflow, "Timestamp overflow")
        lastTimestampIn = absolute
        return absolute
    }

    private struct Reader {
        private let bytes: [UInt8]
        private var at = 0
        init(_ bytes: [UInt8]) { self.bytes = bytes }
        var isEmpty: Bool { at >= bytes.count }
        mutating func byte() throws -> UInt8 { try require(at < bytes.count, "Truncated negentropy message"); defer { at += 1 }; return bytes[at] }
        mutating func bytes(_ n: Int) throws -> [UInt8] {
            try require(at + n <= bytes.count, "Truncated negentropy message")
            defer { at += n }
            return Array(bytes[at..<at + n])
        }
    }

    /// Timestamp first, then the ID (or prefix) lexicographically; a shorter equal prefix sorts first.
    private static func compare(_ t1: Int64, _ id1: [UInt8], _ t2: Int64, _ id2: [UInt8]) -> Int {
        if t1 != t2 { return t1 < t2 ? -1 : 1 }
        for (a, b) in zip(id1, id2) where a != b { return a < b ? -1 : 1 }
        return id1.count == id2.count ? 0 : id1.count < id2.count ? -1 : 1
    }
    static func encodeVarInt(_ value: UInt64, _ o: inout [UInt8]) {
        if value == 0 { o.append(0); return }
        var groups = [UInt8]()
        var n = value
        while n != 0 { groups.insert(UInt8(n & 0x7f), at: 0); n >>= 7 }
        for (i, g) in groups.enumerated() { o.append(i < groups.count - 1 ? g | 0x80 : g) }
    }
    private static func decodeVarInt(_ r: inout Reader) throws -> UInt64 {
        var value: UInt64 = 0
        var count = 0
        while true {
            let b = try r.byte()
            count += 1
            try require(count <= 10 && value <= UInt64(Int64.max) >> 7, "Varint overflow")
            value = value << 7 | UInt64(b & 0x7f)
            if b & 0x80 == 0 { return value }
        }
    }
}
