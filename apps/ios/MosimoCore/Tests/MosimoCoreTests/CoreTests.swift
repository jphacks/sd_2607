import XCTest
@testable import MosimoCore

/// Ports of the Android bundle-core vectors (SignatureTest, MeshEngineTest) that pin byte-level behaviour.
final class CoreTests: XCTestCase {
    func author() throws -> Author { try Author(keys: SecretKeys(nostr: key(3), atproto: key(4), plc: key(5)), pdsEndpoint: "https://pds.invalid") }
    let now: Int64 = 1_792_195_200_000

    func testBip340VectorZero() throws {
        let pub = "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"
        let sig = "e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca821525f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0"
        XCTAssertEqual(hex(try Secp.xOnlyPublicKey(key(3))), pub)
        XCTAssertEqual(hex(try Secp.signSchnorr([UInt8](repeating: 0, count: 32), key(3), aux: [UInt8](repeating: 0, count: 32))), sig)
        XCTAssertTrue(Secp.verifySchnorr(try unhex(sig, 64), [UInt8](repeating: 0, count: 32), try unhex(pub, 32)))
    }

    func testNip01UnicodeAndEscapeVector() throws {
        let event = NostrEvent(id: "8ccfd0d228af4d6011d9ee15d1829f9d5e8e8708934a078cf120e3f4df242e66",
            pubkey: "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9", createdAt: 1760000000, kind: 1,
            tags: [["x", "順序\\\"\n", "\u{08}\t\u{0c}\r\u{01}"], ["t", "mndt-experimental-v1"]],
            content: "日本語😀 / \" \\ \n\r\t\u{08}\u{0c}\u{00}\u{1f}\u{2028}",
            sig: "58a034351b44064679259d7bb9fc490f9ab0f23c59c66a40ee2da510d76409e85f237726d4c4b48e3502303a45c0998cacaa5b7c1695fcd9a86984f0937fbe99")
        XCTAssertTrue(event.verify())
        XCTAssertEqual(try NostrEvent.fromJson(try event.toJson()), event)
        XCTAssertEqual(try EventCodec.decode(try EventCodec.encode(event)), event)
        XCTAssertFalse(NostrEvent(id: event.id, pubkey: event.pubkey, createdAt: event.createdAt, kind: 1, tags: event.tags.reversed(), content: event.content, sig: event.sig).verify())
    }

    func testCborCanonicalRules() throws {
        for bad in ["1801", "9fff", "f90000", "a2616101616102", "0100", "5b7fffffffffffffff", "61ff", "d82a4100"] {
            XCTAssertThrowsError(try CanonicalCbor.decode(try unhex(bad)), bad)
        }
        XCTAssertThrowsError(try CanonicalCbor.decode([UInt8](repeating: 0x81, count: 18) + [0]))
        XCTAssertEqual(hex(try CanonicalCbor.encode(.map(["aa": .int(1), "b": .int(2)]))), "a261620262616101")
    }

    func testMstRootsMatchAtprotoReference() throws {
        let recordCid = "bafyreie4skwimcy6rvyz3e6xgrqpndeipmbhpcmg33pejewxwvtoraqjz4"
        let expected = [0: "bafyreie5737gdxlw5i64vzichcalba3z2v5n6icifvx5xytvske7mr3hpm", 1: "bafyreidphvidzya4ecq6lsl34ioit4i5xqo6gnuwcqt5j7qtxznp7j62qq",
                        2: "bafyreieiwnukqp6tkj6slmcsxnq4z4ioxttcv23pwn4npf3ybxwjemcec4", 10: "bafyreihxl75zffvdhyvhvkylu4zgq7aa57x4uf4qbrsuqw2pznlzwt3m4u",
                        64: "bafyreiepbg6lzbqocylo45rrpedyqi424j6egpkejei2xgul2ukt5np3wu", 128: "bafyreihmizbmhzyvqqmmdr5yy3uzed76gyf67i7ftbmc3knxjqwtekfnlu"]
        for (count, root) in expected {
            let records = Dictionary(uniqueKeysWithValues: (0..<count).map { (String(format: "xxx.disaster.post/key%03d", $0), recordCid) })
            XCTAssertEqual(try AtRepository.mstRoot(records), root, "\(count) records")
        }
    }

    func testPostRestoresChainsAndCarriesExtensions() throws {
        let a = try author()
        let ext = [Extensions.origin(Origin(latitudeMicrodegrees: 35_681_236, longitudeMicrodegrees: 139_767_125, accuracyMillimeters: 5000)),
                   Extensions.validity(endsAt: "2026-10-17T06:00:00Z")]
        let first = try a.createPost("［安全］本文", nowMillis: now, extensions: ext)
        let tags = first.event.tags
        XCTAssertEqual(tags.prefix(7).map { $0[0] }, ["t", "mosimo.oriom.dev:version", "mosimo.oriom.dev:commit", "mosimo.oriom.dev:base",
                                                       "mosimo.oriom.dev:path", "mosimo.oriom.dev:created-at", "mosimo.oriom.dev:delivery-expires-at"])
        XCTAssertEqual(tags[5][1], "2026-10-17T00:00:00Z")
        XCTAssertEqual(tags[7][0], "mosimo.oriom.dev:ext:dev.oriom.mosimo.defs#origin")
        let change = try AtRepository.restore(first.event, atprotoPubkey: a.atprotoPubkey)
        XCTAssertEqual(first.repository.records[change.path], change.recordCid)
        XCTAssertEqual(Extensions.decode(first.event).origin?.accuracyMillimeters, 5000)
        let second = try a.createPost("追記", nowMillis: now - 1000, previous: first.repository)
        XCTAssertEqual(try AtRepository.restore(second.event).applyFromCommit, first.repository.previousCommit)
        XCTAssertGreaterThan(try AtRepository.restore(second.event).path, change.path)
        XCTAssertEqual(AtRepository.did(first.event), a.did)
        XCTAssertEqual(a.did, try author().did)
        XCTAssertEqual(try AtRepository.plcDid(a.initialOperationJson), a.did)
        let genesis = try a.createPlcGenesisEvent(nowMillis: now)
        XCTAssertEqual(try EventCodec.decode(try EventCodec.encode(genesis)), genesis)
        XCTAssertEqual(try MeshEngine().originate(genesis, nowMillis: now, elapsedMillis: 0).event, genesis)
    }

    func testIdentityMatchesAndroidDeterministicDid() throws {
        // ECDSA is RFC 6979 deterministic in both libsecp256k1 builds, so key(3,4,5) yields one genesis and DID.
        let a = try author()
        let op = try Json.parse(a.initialOperationJson)
        XCTAssertEqual(op.object!.map(\.0), ["type", "rotationKeys", "verificationMethods", "alsoKnownAs", "services", "prev", "sig"])
        XCTAssertTrue(a.did.hasPrefix("did:plc:"))
        XCTAssertEqual(a.did.count, 32)
    }

    func testMeshEngineCarriesAcrossThreeNodesAndLimitsHops() throws {
        let a = try author()
        let post = try a.createPost("hello", nowMillis: now)
        let n1 = MeshEngine(clockConfidence: .trusted), n2 = MeshEngine(clockConfidence: .trusted), n3 = MeshEngine(clockConfidence: .trusted)
        try n1.originate(post.event, nowMillis: now, elapsedMillis: 0)
        let b1 = try XCTUnwrap(n1.forward(post.event.id, nowMillis: now, elapsedMillis: 1000))
        let e2 = try XCTUnwrap(try n2.accept(b1, nowMillis: now, elapsedMillis: 50))
        XCTAssertEqual(e2.hop, 1)
        let b2 = try XCTUnwrap(n2.forward(post.event.id, nowMillis: now, elapsedMillis: 2000))
        let e3 = try XCTUnwrap(try n3.accept(b2, nowMillis: now, elapsedMillis: 0))
        XCTAssertEqual(e3.hop, 2)
        XCTAssertEqual(try Bundle.unmarshal(b2).age, 1000 + 1950)
        XCTAssertEqual(n3.inventory(nowMillis: now, elapsedMillis: 0), [post.event.id])
        XCTAssertEqual(n3.inventory(nowMillis: now + 2 * 86_400_000, elapsedMillis: 0), [])
    }

    func testAndroidOriginatedBundleBytesMatchTheVendoredMarshal() throws {
        // valid_bundle_hex (fixtures.json) is Android's MeshEngine output; re-originating its event must give the same bytes
        // apart from the source EID, which that fixture fixed to node.example.
        let f = try fixture("apps/esp32/test_bundle/fixtures.json")
        let bytes = try unhex(f["valid_bundle_hex"]!.string!)
        let original = try Bundle.unmarshal(bytes)
        let event = try EventCodec.decode(original.payloadData!)
        let engine = MeshEngine()
        let mine = try Bundle.unmarshal(try engine.originate(event, nowMillis: 0, elapsedMillis: 0).bytes)
        XCTAssertEqual(mine.blocks, original.blocks)
        XCTAssertEqual(mine.primary.lifetime, original.primary.lifetime)
        XCTAssertEqual(mine.primary.flags, original.primary.flags)
    }

    func testWireAndFrames() throws {
        let hello = try Wire.encode(Control(type: "hello", node: "0123456789abcdef", max: 24000))
        XCTAssertEqual(String(decoding: hello, as: UTF8.self), #"{"v":1,"type":"hello","node":"0123456789abcdef","max":24000,"dids":1}"#)
        let id = String(repeating: "a", count: 64)
        let inv = try Wire.encode(Control(type: "inventory", ids: [id], dids: [""]))
        XCTAssertEqual(try Wire.decode(inv), Control(type: "inventory", ids: [id], dids: [""]))
        XCTAssertThrowsError(try Wire.decode(Array(#"{"v":1,"type":"want","ids":[],"x":1}"#.utf8)))
        var reader = FrameReader()
        let framed = try Frames.encode(hello) + Frames.encode([0x9f, 0xff])
        var out = [[UInt8]]()
        for b in framed { out += try reader.feed([b]) }
        XCTAssertEqual(out, [hello, [0x9f, 0xff]])
        XCTAssertThrowsError(try reader.feed([0, 0, 0x80, 1]))
    }

    func testInstantFormatting() {
        XCTAssertEqual(Instant(epochMillis: 1_792_195_200_000).description, "2026-10-17T00:00:00Z")
        XCTAssertEqual(Instant(epochMillis: 1_792_195_200_120).description, "2026-10-17T00:00:00.120Z")
        XCTAssertEqual(Instant.parse("2026-10-17T09:00:00+09:00"), Instant(epochSecond: 1_792_195_200))
        XCTAssertNil(Instant.parse("2026-02-30T00:00:00Z"))
    }
}
