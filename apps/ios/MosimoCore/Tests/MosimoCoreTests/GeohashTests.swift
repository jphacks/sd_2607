import XCTest
@testable import MosimoCore

/// Geohash union outline and pin anchor (same cases as Android GeoTest).
final class GeohashTests: XCTestCase {
    func testBounds() throws {
        let (s, w, n, e) = try XCTUnwrap(geohashBounds("ezs42"))
        XCTAssertEqual((s + n) / 2, 42.605, accuracy: 0.01)
        XCTAssertEqual((w + e) / 2, -5.603, accuracy: 0.01)
        XCTAssertNil(geohashBounds("xna"))
    }

    func testOutline() {
        // Two neighbours share one side: 4 + 4 sides minus the shared pair.
        XCTAssertEqual(geohashOutline(["xn76", "xn77"])?.count, 6)
        XCTAssertEqual(geohashOutline(["xn7"])?.count, 4)
        // A parent with one of its own children: traced on the child grid (4 × 8 children), the child adds no edges.
        XCTAssertEqual(geohashOutline(["xn7", "xn76"])?.count, 2 * 4 + 2 * 8)
        // Too many fine cells to trace.
        XCTAssertNil(geohashOutline(["x", "xn76kz"]))
        XCTAssertNil(geohashOutline([]))
    }

    func testAnchorInside() throws {
        let cells = ["xn76", "xn77", "xn7k"]
        func inside(_ p: GeoPoint) -> Bool {
            cells.contains { h in geohashBounds(h).map { b in (b.0...b.2).contains(p.lat) && (b.1...b.3).contains(p.lng) } ?? false }
        }
        XCTAssertTrue(inside(try XCTUnwrap(geohashAnchor(cells))))
        // Far-off islands (like Tokyo's Ogasawara) don't drag the pin away from the largest part.
        XCTAssertTrue(inside(try XCTUnwrap(geohashAnchor(cells + ["xn10", "xn0b"]))))
    }
}
