import Foundation

/// Ordered JSON tree. Integers stay exact; floats are kept only to be rejected where the protocol forbids them.
public indirect enum Json: Equatable, Sendable {
    case null
    case bool(Bool)
    case int(Int64)
    case float(String)
    case string(String)
    case array([Json])
    case object([(String, Json)])

    public static func == (a: Json, b: Json) -> Bool {
        switch (a, b) {
        case (.null, .null): return true
        case let (.bool(x), .bool(y)): return x == y
        case let (.int(x), .int(y)): return x == y
        case let (.float(x), .float(y)): return x == y
        case let (.string(x), .string(y)): return x == y
        case let (.array(x), .array(y)): return x == y
        // Field order is not significant for equality (Jackson ObjectNode semantics).
        case let (.object(x), .object(y)):
            return x.count == y.count && x.allSatisfy { k, v in y.first(where: { $0.0 == k })?.1 == v }
        default: return false
        }
    }

    public subscript(key: String) -> Json? { if case .object(let o) = self { return o.first { $0.0 == key }?.1 }; return nil }
    public subscript(index: Int) -> Json? { if case .array(let a) = self, a.indices.contains(index) { return a[index] }; return nil }
    public var string: String? { if case .string(let s) = self { return s }; return nil }
    public var int: Int64? { if case .int(let n) = self { return n }; return nil }
    public var bool: Bool? { if case .bool(let b) = self { return b }; return nil }
    public var array: [Json]? { if case .array(let a) = self { return a }; return nil }
    public var object: [(String, Json)]? { if case .object(let o) = self { return o }; return nil }
    public var keys: Set<String> { Set(object?.map(\.0) ?? []) }

    /// Strict parse: duplicate keys, trailing data and over-deep nesting are errors.
    public static func parse(_ text: String, maxDepth: Int = 32, maxBytes: Int = 8_000_000) throws -> Json {
        let bytes = Array(text.utf8)
        try require(bytes.count <= maxBytes, "JSON size limit")
        return try parse(bytes, maxDepth: maxDepth)
    }

    public static func parse(_ bytes: [UInt8], maxDepth: Int = 32) throws -> Json {
        var p = JsonParser(bytes: bytes, maxDepth: maxDepth)
        p.skip()
        let v = try p.value(0)
        p.skip()
        try require(p.pos == bytes.count, "Trailing JSON")
        return v
    }

    /// Compact serialization in field order (Jackson-like escaping: only `"`, `\` and control characters).
    public var serialized: String {
        switch self {
        case .null: return "null"
        case .bool(let b): return b ? "true" : "false"
        case .int(let n): return String(n)
        case .float(let s): return s
        case .string(let s): return quoteJson(s)
        case .array(let a): return "[" + a.map(\.serialized).joined(separator: ",") + "]"
        case .object(let o): return "{" + o.map { quoteJson($0.0) + ":" + $0.1.serialized }.joined(separator: ",") + "}"
        }
    }

    /// JSON → data model (integers, strings, booleans, null, arrays, maps); floats are rejected.
    public func toCbor() throws -> Cbor {
        switch self {
        case .null: return .null
        case .bool(let b): return .bool(b)
        case .int(let n): return .int(n)
        case .float: throw CoreError("Floats forbidden")
        case .string(let s): return .text(s)
        case .array(let a): return .array(try a.map { try $0.toCbor() })
        case .object(let o): return .map(Dictionary(try o.map { ($0.0, try $0.1.toCbor()) }, uniquingKeysWith: { a, _ in a }))
        }
    }

    public func containsFloat() -> Bool {
        switch self {
        case .float: return true
        case .array(let a): return a.contains { $0.containsFloat() }
        case .object(let o): return o.contains { $0.1.containsFloat() }
        default: return false
        }
    }
}

private struct JsonParser {
    let bytes: [UInt8]
    let maxDepth: Int
    var pos = 0

    mutating func skip() { while pos < bytes.count, [0x20, 0x09, 0x0a, 0x0d].contains(bytes[pos]) { pos += 1 } }

    mutating func expect(_ literal: String) throws {
        let l = Array(literal.utf8)
        try require(pos + l.count <= bytes.count && Array(bytes[pos..<pos + l.count]) == l, "Invalid JSON literal")
        pos += l.count
    }

    mutating func value(_ depth: Int) throws -> Json {
        try require(depth <= maxDepth && pos < bytes.count, "Invalid JSON")
        switch bytes[pos] {
        case UInt8(ascii: "{"):
            pos += 1; skip()
            var fields = [(String, Json)](); var seen = Set<String>()
            if pos < bytes.count, bytes[pos] == UInt8(ascii: "}") { pos += 1; return .object(fields) }
            while true {
                skip()
                try require(pos < bytes.count && bytes[pos] == UInt8(ascii: "\""), "Expected key")
                let key = try string()
                try require(seen.insert(key).inserted, "Duplicate JSON key")
                skip(); try expect(":"); skip()
                fields.append((key, try value(depth + 1)))
                skip()
                try require(pos < bytes.count, "Truncated JSON")
                if bytes[pos] == UInt8(ascii: ",") { pos += 1; continue }
                try expect("}")
                return .object(fields)
            }
        case UInt8(ascii: "["):
            pos += 1; skip()
            var items = [Json]()
            if pos < bytes.count, bytes[pos] == UInt8(ascii: "]") { pos += 1; return .array(items) }
            while true {
                skip(); items.append(try value(depth + 1)); skip()
                try require(pos < bytes.count, "Truncated JSON")
                if bytes[pos] == UInt8(ascii: ",") { pos += 1; continue }
                try expect("]")
                return .array(items)
            }
        case UInt8(ascii: "\""): return .string(try string())
        case UInt8(ascii: "t"): try expect("true"); return .bool(true)
        case UInt8(ascii: "f"): try expect("false"); return .bool(false)
        case UInt8(ascii: "n"): try expect("null"); return .null
        default: return try number()
        }
    }

    mutating func number() throws -> Json {
        let start = pos
        if pos < bytes.count, bytes[pos] == UInt8(ascii: "-") { pos += 1 }
        let digits = pos
        while pos < bytes.count, (48...57).contains(bytes[pos]) { pos += 1 }
        try require(pos > digits && (bytes[digits] != 48 || pos == digits + 1), "Invalid JSON number")
        var float = false
        if pos < bytes.count, bytes[pos] == UInt8(ascii: ".") {
            float = true; pos += 1
            let f = pos; while pos < bytes.count, (48...57).contains(bytes[pos]) { pos += 1 }
            try require(pos > f, "Invalid JSON number")
        }
        if pos < bytes.count, bytes[pos] == UInt8(ascii: "e") || bytes[pos] == UInt8(ascii: "E") {
            float = true; pos += 1
            if pos < bytes.count, bytes[pos] == UInt8(ascii: "+") || bytes[pos] == UInt8(ascii: "-") { pos += 1 }
            let e = pos; while pos < bytes.count, (48...57).contains(bytes[pos]) { pos += 1 }
            try require(pos > e, "Invalid JSON number")
        }
        let text = String(decoding: bytes[start..<pos], as: UTF8.self)
        try require(text.count <= 20, "JSON number too long")
        if float { return .float(text) }
        guard let n = Int64(text) else { throw CoreError("JSON integer overflow") }
        return .int(n)
    }

    mutating func string() throws -> String {
        pos += 1
        var out = [UInt8]()
        while true {
            try require(pos < bytes.count, "Truncated JSON string")
            let c = bytes[pos]; pos += 1
            switch c {
            case UInt8(ascii: "\""): return try strictUtf8(out)
            case UInt8(ascii: "\\"):
                try require(pos < bytes.count, "Truncated JSON escape")
                let e = bytes[pos]; pos += 1
                switch e {
                case UInt8(ascii: "\""): out.append(0x22)
                case UInt8(ascii: "\\"): out.append(0x5c)
                case UInt8(ascii: "/"): out.append(0x2f)
                case UInt8(ascii: "b"): out.append(0x08)
                case UInt8(ascii: "f"): out.append(0x0c)
                case UInt8(ascii: "n"): out.append(0x0a)
                case UInt8(ascii: "r"): out.append(0x0d)
                case UInt8(ascii: "t"): out.append(0x09)
                case UInt8(ascii: "u"):
                    var unit = try hex4()
                    if (0xd800...0xdbff).contains(unit) {
                        try expect("\\u")
                        let low = try hex4()
                        try require((0xdc00...0xdfff).contains(low), "Lone surrogate")
                        unit = 0x10000 + ((unit - 0xd800) << 10) + (low - 0xdc00)
                    } else { try require(!(0xdc00...0xdfff).contains(unit), "Lone surrogate") }
                    out += Array(String(Character(Unicode.Scalar(unit)!)).utf8)
                default: throw CoreError("Invalid JSON escape")
                }
            default:
                try require(c >= 0x20, "Control character in JSON string")
                out.append(c)
            }
        }
    }

    mutating func hex4() throws -> UInt32 {
        try require(pos + 4 <= bytes.count, "Truncated JSON escape")
        guard let n = UInt32(String(decoding: bytes[pos..<pos + 4], as: UTF8.self), radix: 16) else { throw CoreError("Invalid JSON escape") }
        pos += 4
        return n
    }
}

/// Dedicated NIP-01 signing escaping, independent of any general JSON writer.
public func quoteJson(_ s: String) -> String {
    var out = "\""
    for u in s.unicodeScalars {
        switch u {
        case "\"": out += "\\\""
        case "\\": out += "\\\\"
        case "\u{08}": out += "\\b"
        case "\t": out += "\\t"
        case "\n": out += "\\n"
        case "\u{0c}": out += "\\f"
        case "\r": out += "\\r"
        default:
            if u.value < 0x20 { out += String(format: "\\u%04x", u.value) } else { out.unicodeScalars.append(u) }
        }
    }
    return out + "\""
}
