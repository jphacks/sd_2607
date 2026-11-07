import Foundation

/// Contact control message (mndt §3). [node]/[max]/[didCap] are HELLO capability fields; [ids] are Nostr event IDs.
/// [dids] is an optional inventory hint parallel to [ids] (author DID or ""), unverified.
public struct Control: Equatable, Sendable {
    public var type: String
    public var ids: [String] = []
    public var node = ""
    public var max = 0
    public var dids: [String] = []
    public var didCap = false
    public init(type: String, ids: [String] = [], node: String = "", max: Int = 0, dids: [String] = [], didCap: Bool = false) {
        self.type = type; self.ids = ids; self.node = node; self.max = max; self.dids = dids; self.didCap = didCap
    }
}

/// Provisional JSON control messages (protocol/mndt.md §9); bundles travel as raw BPv7 CBOR frames.
public enum Wire {
    public static let maxBytes = 32768
    static let idPattern = try! NSRegularExpression(pattern: "^[0-9a-f]{64}$")
    static let nodePattern = try! NSRegularExpression(pattern: "^[0-9a-f]{16}$")

    public static func encode(_ c: Control) throws -> [UInt8] {
        var fields: [(String, Json)] = [("v", .int(1)), ("type", .string(c.type))]
        if c.type == "hello" { fields += [("node", .string(c.node)), ("max", .int(Int64(c.max))), ("dids", .int(1))] }
        else { fields.append(("ids", .array(c.ids.map { .string($0) }))) }
        if !c.dids.isEmpty { fields.append(("dids", .array(c.dids.map { .string($0) }))) }
        let bytes = Array(Json.object(fields).serialized.utf8)
        _ = try decode(bytes)
        return bytes
    }

    public static func decode(_ bytes: [UInt8]) throws -> Control {
        try require(!bytes.isEmpty && bytes.count <= maxBytes && bytes[0] == UInt8(ascii: "{"))
        let node = try Json.parse(bytes)
        try require(node["v"] == .int(1))
        guard let type = node["type"]?.string, ["hello", "inventory", "want", "ack"].contains(type) else { throw CoreError("Unknown control type") }
        if type == "hello" {
            // Later capability fields are ignored so peers can add them without a version bump.
            guard let id = node["node"]?.string, matches(nodePattern, id), let max = node["max"]?.int, (1...Int64(maxBytes)).contains(max) else {
                throw CoreError("Invalid hello")
            }
            return Control(type: type, node: id, max: Int(max), didCap: node["dids"] == .int(1))
        }
        try require(node.keys == ["v", "type", "ids"] || type == "inventory" && node.keys == ["v", "type", "ids", "dids"])
        guard let ids = node["ids"]?.array, ids.count <= 128 else { throw CoreError("Invalid ids") }
        let values = try ids.map { v -> String in guard let s = v.string, matches(idPattern, s) else { throw CoreError("Invalid id") }; return s }
        try require(Set(values).count == values.count)
        try require(type != "ack" || values.count == 1)
        var dids = [String]()
        if let array = node["dids"] {
            guard let a = array.array, a.count == values.count else { throw CoreError("Invalid dids") }
            dids = try a.map { v in
                guard let s = v.string, s.isEmpty || matches(AtRepository.didPattern, s) else { throw CoreError("Invalid did") }
                return s
            }
        }
        return Control(type: type, ids: values, dids: dids)
    }
}

/// Provisional framing (protocol/mndt.md §9): 4-byte big-endian length, then the frame, on any byte stream.
public enum Frames {
    public static let maxFrame = Wire.maxBytes
    public static func encode(_ frame: [UInt8]) throws -> [UInt8] {
        try require(!frame.isEmpty && frame.count <= maxFrame, "Frame size")
        let n = UInt32(frame.count)
        return [UInt8(n >> 24), UInt8((n >> 16) & 0xff), UInt8((n >> 8) & 0xff), UInt8(n & 0xff)] + frame
    }
}

/// Reassembles frames from arbitrary chunk boundaries (GATT writes, TCP reads). Throws on a bad length.
public struct FrameReader {
    private var pending = [UInt8]()
    public init() {}
    public mutating func feed(_ chunk: [UInt8]) throws -> [[UInt8]] {
        pending += chunk
        var frames = [[UInt8]]()
        var at = 0
        while pending.count - at >= 4 {
            let n = Int(pending[at]) << 24 | Int(pending[at + 1]) << 16 | Int(pending[at + 2]) << 8 | Int(pending[at + 3])
            try require((1...Frames.maxFrame).contains(n), "Frame size")
            if pending.count - at - 4 < n { break }
            frames.append(Array(pending[(at + 4)..<(at + 4 + n)]))
            at += 4 + n
        }
        pending.removeFirst(at)
        return frames
    }
}
