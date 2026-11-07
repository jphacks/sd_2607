import XCTest
@testable import MosimoCore

/// Repository fixtures, read in place (never copied): apps/android and apps/esp32 test vectors.
func fixture(_ path: String) throws -> Json {
    let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../../../..").standardized
    return try Json.parse(String(contentsOf: root.appendingPathComponent(path), encoding: .utf8))
}
func key(_ n: UInt8) -> [UInt8] { [UInt8](repeating: 0, count: 31) + [n] }

final class FixtureTests: XCTestCase {
    func testServerPublisherPostsRestoreAuthorizeAndRelay() throws {
        let f = try fixture("apps/android/bundle-core/src/test/resources/server-publisher-wire-v1.json")
        let config = try PublicIngress.config(f["config"]!.serialized)
        let now = Instant(epochSecond: f["checkedAtSeconds"]!.int!)
        let atKey = try unhex(f["certificate"]!["body"]!["subjectPubkey"]!.string!, 33)
        var memory = try PublicIngress.acceptSnapshot(config.roots[0], try unhex(f["snapshotHex"]!.string!), PublicIngress.Memory(), now)
        XCTAssertEqual(try AtRepository.plcDid(f["plcGenesis"]!.serialized), f["certificate"]!["body"]!["subjectDid"]!.string)
        let mesh = MeshEngine(clockConfidence: .trusted), recipient = MeshEngine(clockConfidence: .trusted)
        var records = [String: String]()
        for (i, vector) in f["posts"]!.array!.enumerated() {
            let original = vector["event"]!.serialized
            let event = try NostrEvent.fromJson(original)
            let change = try AtRepository.restore(event, atprotoPubkey: atKey)
            XCTAssertEqual(hex(change.recordBytes), vector["recordHex"]!.string)
            XCTAssertEqual(hex(change.commitBytes), vector["commitHex"]!.string)
            XCTAssertEqual(hex(try EventCodec.encode(event)), vector["meshPayloadHex"]!.string)
            XCTAssertEqual(change.recordCid, vector["recordCid"]!.string)
            XCTAssertEqual(change.commitCid, vector["commitCid"]!.string)
            records[change.path] = change.recordCid
            XCTAssertEqual(try AtRepository.mstRoot(records), vector["mstRoot"]!.string)
            let decision = PublicIngress.verify(event, config, memory, now)
            let receipt = try XCTUnwrap(decision.receipt, decision.reason)
            XCTAssertEqual(receipt.certId, f["certId"]!.string)
            XCTAssertEqual(receipt.priority, 3)
            XCTAssertEqual(receipt.sequence, 7)
            XCTAssertTrue(decision.status.revocation.hasPrefix("fresh-as-of"))
            memory = decision.memory
            XCTAssertTrue(PublicIngress.readyToInsert(receipt, event, memory, now))
            try mesh.originate(event, nowMillis: now.epochMillis, elapsedMillis: Int64(i))
            let forwarded = try XCTUnwrap(mesh.forward(event.id, nowMillis: now.epochMillis, elapsedMillis: Int64(i) + 10))
            let received = try XCTUnwrap(try recipient.accept(forwarded, nowMillis: now.epochMillis, elapsedMillis: Int64(i) + 10))
            XCTAssertEqual(received.event, event)
            XCTAssertEqual(try Json.parse(try received.event.toJson()), try Json.parse(original))
            XCTAssertNil(try recipient.accept(forwarded, nowMillis: now.epochMillis, elapsedMillis: Int64(i) + 11))
        }
        let event = try NostrEvent.fromJson(f["event"]!.serialized)
        XCTAssertNil(PublicIngress.verify(event, PublicIngress.Config(roots: config.roots), memory, now).receipt)
        XCTAssertNil(PublicIngress.verify(event, config, PublicIngress.Memory(), now).receipt)
        XCTAssertNil(PublicIngress.verify(event, config, memory, nil).receipt)
    }

    func testCombinedFixtureAuthorizesAll28Types() throws {
        let f = try fixture("apps/esp32/test_bundle/wire-v1-combined-server-fixture.json")
        let selected: [Json] = f["selection"]!.array!.flatMap { b in b["types"]!.array!.map { Json.object([("botDid", b["botDid"]!), ("type", $0)]) } }
        let config = try PublicIngress.config(Json.object([("roots", .array([f["root"]!])), ("selected", .array(selected))]).serialized)
        XCTAssertEqual(config.selected.count, 28)
        let now = Instant(epochSecond: f["nowSeconds"]!.int!)
        var memory = try PublicIngress.acceptSnapshot(config.roots[0], try unhex(f["snapshotHex"]!.string!), PublicIngress.Memory(), now)
        var accepted = Set<PublicIngress.Selection>()
        for vector in f["events"]!.array! {
            let event = try NostrEvent.fromJson(vector["event"]!.serialized)
            let decision = PublicIngress.verify(event, config, memory, now)
            let receipt = try XCTUnwrap(decision.receipt, decision.reason)
            accepted.insert(.init(botDid: receipt.botDid, type: receipt.type))
            memory = decision.memory
            XCTAssertEqual(hex(try EventCodec.encode(event)), vector["payloadHex"]!.string)
            XCTAssertNil(PublicIngress.verify(event, PublicIngress.Config(roots: config.roots), memory, now).receipt)
        }
        XCTAssertEqual(accepted, config.selected)
        XCTAssertNil(PublicIngress.verify(try NostrEvent.fromJson(f["unselectedEvent"]!.serialized), config, memory, now).receipt)
    }

    func testEsp32BundleFixtureParsesAndRoundTrips() throws {
        let f = try fixture("apps/esp32/test_bundle/fixtures.json")
        let bytes = try unhex(f["valid_bundle_hex"]!.string!)
        let bundle = try Bundle.unmarshal(bytes)
        XCTAssertEqual(bundle.marshal(), bytes)
        let event = try EventCodec.decode(bundle.payloadData!)
        XCTAssertEqual(event.id, f["event_id"]!.string)
        XCTAssertEqual(event.content, f["text"]!.string)
        XCTAssertEqual(hex(bundle.payloadData!), f["payload_hex"]!.string)
        XCTAssertEqual(hex(event.signingBytes()), f["signing_json_hex"]!.string)
        let tampered = try unhex(f["tampered_signature_bundle_hex"]!.string!)
        XCTAssertThrowsError(try MeshEngine().accept(tampered, nowMillis: 0, elapsedMillis: 0))
        XCTAssertNotNil(try MeshEngine().accept(bytes, nowMillis: 0, elapsedMillis: 0))
    }

    func testAndroidBase64Cases() throws {
        let f = try fixture("apps/esp32/test_bundle/android-base64-fixtures.json")
        for c in f["cases"]!.array! {
            let event = try NostrEvent.fromJson(c["event_json"]!.string!)
            XCTAssertEqual(event.id, c["event_id"]!.string)
            XCTAssertEqual(try event.toJson(), c["event_json"]!.string)
            if let payload = c["payload_hex"]?.string { XCTAssertEqual(hex(try EventCodec.encode(event)), payload) }
            if let bundle = c["bundle_hex"]?.string {
                XCTAssertEqual(try EventCodec.decode(try Bundle.unmarshal(try unhex(bundle)).payloadData!), event)
            }
        }
    }
}
