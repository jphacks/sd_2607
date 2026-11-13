import Foundation

/// A (lat, lng) point in degrees.
public struct GeoPoint: Equatable, Hashable, Sendable {
    public let lat: Double, lng: Double
    public init(lat: Double, lng: Double) { self.lat = lat; self.lng = lng }
}

private let base32 = Array("0123456789bcdefghjkmnpqrstuvwxyz")

/// Geohash cell as (south, west, north, east) degrees; nil for a character outside the alphabet.
public func geohashBounds(_ hash: String) -> (Double, Double, Double, Double)? {
    var lat = (-90.0, 90.0), lng = (-180.0, 180.0)
    var even = true
    for c in hash.lowercased() {
        guard let v = base32.firstIndex(of: c) else { return nil }
        for bit in stride(from: 4, through: 0, by: -1) {
            let on = (v >> bit) & 1 == 1
            if even { let mid = (lng.0 + lng.1) / 2; if on { lng.0 = mid } else { lng.1 = mid } }
            else { let mid = (lat.0 + lat.1) / 2; if on { lat.0 = mid } else { lat.1 = mid } }
            even.toggle()
        }
    }
    return (lat.0, lng.0, lat.1, lng.1)
}

private struct Cell: Hashable { let x: Int, y: Int }

/// Cells of a geohash union on one grid (the finest code length present), as integer (x, y) indices; nil past [limit] cells.
private struct GeohashGrid {
    let dLat: Double, dLng: Double
    let cells: Set<Cell>

    init?(_ hashes: [String], limit: Int = 20_000) {
        let valid = hashes.map { $0.lowercased() }.filter { h in !h.isEmpty && h.count <= 12 && h.allSatisfy(base32.contains) }
        guard let depth = valid.map(\.count).max() else { return nil }
        guard valid.reduce(0.0, { $0 + pow(32, Double(depth - $1.count)) }) <= Double(limit) else { return nil }
        dLng = 360 / Double(1 << ((5 * depth + 1) / 2))
        dLat = 180 / Double(1 << (5 * depth / 2))
        var cells = Set<Cell>()
        for h in valid {
            guard let (s, w, n, e) = geohashBounds(h) else { continue }
            let x0 = Int(((w + 180) / dLng).rounded()), x1 = Int(((e + 180) / dLng).rounded())
            let y0 = Int(((s + 90) / dLat).rounded()), y1 = Int(((n + 90) / dLat).rounded())
            for x in x0..<x1 { for y in y0..<y1 { cells.insert(Cell(x: x, y: y)) } }
        }
        self.cells = cells
    }

    func point(_ x: Double, _ y: Double) -> GeoPoint { GeoPoint(lat: y * dLat - 90, lng: x * dLng - 180) }
}

/// The outer edges of a geohash union: every cell side with no covered neighbour, as two-point segments, so a
/// prefecture reads as one outlined shape instead of a grid. Nil when the union is too large (or empty) to trace.
public func geohashOutline(_ hashes: [String]) -> [[GeoPoint]]? {
    guard let g = GeohashGrid(hashes) else { return nil }
    func p(_ x: Int, _ y: Int) -> GeoPoint { g.point(Double(x), Double(y)) }
    var out = [[GeoPoint]]()
    for c in g.cells {
        let (x, y) = (c.x, c.y)
        if !g.cells.contains(Cell(x: x, y: y - 1)) { out.append([p(x, y), p(x + 1, y)]) }
        if !g.cells.contains(Cell(x: x, y: y + 1)) { out.append([p(x, y + 1), p(x + 1, y + 1)]) }
        if !g.cells.contains(Cell(x: x - 1, y: y)) { out.append([p(x, y), p(x, y + 1)]) }
        if !g.cells.contains(Cell(x: x + 1, y: y)) { out.append([p(x + 1, y), p(x + 1, y + 1)]) }
    }
    return out
}

/// A point inside a geohash union for its pin: within its largest 4-connected part (so outlying islands don't pull it
/// out to sea), the centre of the cell nearest that part's mean centre.
public func geohashAnchor(_ hashes: [String]) -> GeoPoint? {
    guard let g = GeohashGrid(hashes) else { return nil }
    var left = g.cells
    var part = [Cell]()
    while let start = left.popFirst() {
        var seen = [start]
        var i = 0
        while i < seen.count {
            let c = seen[i]; i += 1
            for n in [Cell(x: c.x - 1, y: c.y), Cell(x: c.x + 1, y: c.y), Cell(x: c.x, y: c.y - 1), Cell(x: c.x, y: c.y + 1)] {
                if left.remove(n) != nil { seen.append(n) }
            }
        }
        if seen.count > part.count { part = seen }
    }
    guard !part.isEmpty else { return nil }
    let mx = part.reduce(0.0) { $0 + Double($1.x) + 0.5 } / Double(part.count)
    let my = part.reduce(0.0) { $0 + Double($1.y) + 0.5 } / Double(part.count)
    func d(_ c: Cell) -> Double { pow(Double(c.x) + 0.5 - mx, 2) + pow(Double(c.y) + 0.5 - my, 2) }
    guard let best = part.min(by: { d($0) < d($1) }) else { return nil }
    return g.point(Double(best.x) + 0.5, Double(best.y) + 0.5)
}
