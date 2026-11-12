import Foundation

/// Cuts a CBOR sequence (RFC 8742) arriving in arbitrary chunks into complete items. Throws when an item is malformed
/// or larger than [limit]; the stream is then out of sync and the link must close (mesh §7).
public struct CborSequenceReader {
    private let limit: Int
    private var pending = [UInt8]()
    public init(limit: Int) { self.limit = limit }
    public mutating func feed(_ chunk: [UInt8]) throws -> [[UInt8]] {
        pending += chunk
        var items = [[UInt8]]()
        var at = 0
        while at < pending.count, let length = try CanonicalCbor.itemLength(pending, from: at, end: pending.count, limit: limit) {
            items.append(Array(pending[at..<at + length]))
            at += length
        }
        pending.removeFirst(at)
        try require(pending.count <= limit, "Incomplete message exceeds limit")
        return items
    }
    /// Bytes of an item still being assembled.
    public var incomplete: Int { pending.count }
}
