import Foundation

/// java.time.Instant subset: exact UTC seconds + nanoseconds, ISO-8601 with `Z`.
public struct Instant: Comparable, Hashable, Sendable, CustomStringConvertible {
    public let epochSecond: Int64
    public let nano: Int32

    public init(epochSecond: Int64, nano: Int32 = 0) { self.epochSecond = epochSecond; self.nano = nano }
    public init(epochMillis ms: Int64) {
        let s = ms >= 0 ? ms / 1000 : (ms - 999) / 1000
        self.init(epochSecond: s, nano: Int32((ms - s * 1000) * 1_000_000))
    }
    public static func now() -> Instant { Instant(epochMillis: Int64(Date().timeIntervalSince1970 * 1000)) }
    public var epochMillis: Int64 { epochSecond * 1000 + Int64(nano) / 1_000_000 }

    public static func < (a: Instant, b: Instant) -> Bool { (a.epochSecond, a.nano) < (b.epochSecond, b.nano) }

    /// `Instant.toString()`: fraction printed in groups of three digits, omitted when zero.
    public var description: String {
        let days = epochSecond >= 0 ? epochSecond / 86400 : (epochSecond - 86399) / 86400
        let secs = epochSecond - days * 86400
        let (y, m, d) = civil(days)
        var s = String(format: "%04lld-%02lld-%02lldT%02lld:%02lld:%02lld", y, m, d, secs / 3600, secs / 60 % 60, secs % 60)
        if nano != 0 {
            var f = String(format: "%09d", nano)
            while f.hasSuffix("000") { f.removeLast(3) }
            s += "." + f
        }
        return s + "Z"
    }

    /// `Instant.parse` for `yyyy-MM-ddTHH:mm:ss[.f{1,9}](Z|±HH:MM)` with calendar validation.
    public static func parse(_ text: String) -> Instant? {
        let c = Array(text.utf8)
        func num(_ from: Int, _ len: Int) -> Int64? {
            guard from + len <= c.count, c[from..<from + len].allSatisfy({ (48...57).contains($0) }) else { return nil }
            return Int64(String(decoding: c[from..<from + len], as: UTF8.self))
        }
        guard c.count >= 20, c[4] == 45, c[7] == 45, c[10] == 84, c[13] == 58, c[16] == 58,
              let y = num(0, 4), let mo = num(5, 2), let d = num(8, 2), let h = num(11, 2), let mi = num(14, 2), let s = num(17, 2),
              (1...12).contains(mo), d >= 1, d <= daysIn(y, mo), h <= 23, mi <= 59, s <= 59 else { return nil }
        var i = 19
        var nano: Int64 = 0
        if c[i] == 46 {
            i += 1
            let start = i
            while i < c.count, (48...57).contains(c[i]) { i += 1 }
            guard (1...9).contains(i - start), let f = num(start, i - start) else { return nil }
            nano = f * Int64(pow(10.0, Double(9 - (i - start))))
        }
        var offset: Int64 = 0
        if i == c.count - 1, c[i] == 90 {
        } else if i == c.count - 6, c[i] == 43 || c[i] == 45, c[i + 3] == 58, let oh = num(i + 1, 2), let om = num(i + 4, 2), oh <= 18, om <= 59 {
            offset = (oh * 3600 + om * 60) * (c[i] == 45 ? -1 : 1)
        } else { return nil }
        let epoch = days(y, mo, d) * 86400 + h * 3600 + mi * 60 + s - offset
        return Instant(epochSecond: epoch, nano: Int32(nano))
    }
}

private func isLeap(_ y: Int64) -> Bool { y % 4 == 0 && (y % 100 != 0 || y % 400 == 0) }
private func daysIn(_ y: Int64, _ m: Int64) -> Int64 { m == 2 ? (isLeap(y) ? 29 : 28) : [4, 6, 9, 11].contains(m) ? 30 : 31 }

/// Days since 1970-01-01 (Howard Hinnant's days_from_civil).
private func days(_ y0: Int64, _ m: Int64, _ d: Int64) -> Int64 {
    let y = m <= 2 ? y0 - 1 : y0
    let era = (y >= 0 ? y : y - 399) / 400
    let yoe = y - era * 400
    let doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}

private func civil(_ z0: Int64) -> (Int64, Int64, Int64) {
    let z = z0 + 719468
    let era = (z >= 0 ? z : z - 146096) / 146097
    let doe = z - era * 146097
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    let mp = (5 * doy + 2) / 153
    let d = doy - (153 * mp + 2) / 5 + 1
    let m = mp < 10 ? mp + 3 : mp - 9
    return (yoe + era * 400 + (m <= 2 ? 1 : 0), m, d)
}
