import XCTest
@testable import MosimoCore

/// Repository fixtures, read in place (never copied): apps/android and apps/esp32 test vectors.
func fixture(_ path: String) throws -> Json {
    let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../../../..").standardized
    return try Json.parse(String(contentsOf: root.appendingPathComponent(path), encoding: .utf8))
}
func key(_ n: UInt8) -> [UInt8] { [UInt8](repeating: 0, count: 31) + [n] }

final class FixtureTests: XCTestCase {
    /// ESP32's mesh CBOR v1 vector (test_bundle.py): typed tags, omitted tags and the BPv7 envelope byte for byte.
    func testEsp32MeshPayloadVector() throws {
        let f = try fixture("apps/esp32/test_bundle/fixtures.json")
        let bytes = try unhex(f["valid_bundle_hex"]!.string!)
        let bundle = try Bundle.unmarshal(bytes)
        XCTAssertEqual(bundle.marshal(), bytes)
        let event = try EventCodec.decode(bundle.payloadData!)
        XCTAssertEqual(event.id, f["event_id"]!.string)
        XCTAssertEqual(event.content, f["text"]!.string)
        XCTAssertEqual(hex(event.signingBytes()), f["signing_json_hex"]!.string)
        XCTAssertEqual(hex(try EventCodec.encode(event)), f["payload_hex"]!.string)
        XCTAssertEqual(Array(event.tags.prefix(2)), [AtRepository.topicTag, AtRepository.versionTag])
        let tampered = try unhex(f["tampered_signature_bundle_hex"]!.string!)
        XCTAssertThrowsError(try MeshEngine().accept(tampered, nowMillis: 0, elapsedMillis: 0))
        XCTAssertNotNil(try MeshEngine().accept(bytes, nowMillis: 0, elapsedMillis: 0))
    }

    /// The shipped trust asset holds only the production root, its Bot/type selections and any jma-xml feed selections.
    func testShippedTrustAssetHasProductionRoot() throws {
        let json = try fixture("apps/android/mesh-android/src/main/assets/mosimo-public-trust.json").serialized
        let config = try PublicIngress.config(json)
        XCTAssertEqual(config.roots.filter(\.enabled).map(\.pubkeyHex), ["3b92115866d54bb1799529ac39312bcd60bbfc1f5235b5785f4f7db748c62510"])
        XCTAssertEqual(config.roots.count, 1)
        XCTAssertEqual(config.selected.filter { $0.botDid != nil }.count, 28)
        XCTAssertTrue(config.selected.allSatisfy { $0.botDid != nil || $0.feed == PublicIngress.jmaFeed })
    }
}
