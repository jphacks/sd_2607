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

    /// The shipped trust asset is still in the pre-overhaul shape; like Android, it must fail closed (ingress disabled).
    func testShippedTrustAssetFailsClosed() throws {
        let json = try fixture("apps/android/mesh-android/src/main/assets/mosimo-public-trust.json").serialized
        XCTAssertThrowsError(try PublicIngress.config(json))
    }
}
