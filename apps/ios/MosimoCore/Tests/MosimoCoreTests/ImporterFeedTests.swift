import XCTest
@testable import MosimoCore

/// importer §1 `feeds`, §1.1 the `jma-xml` feed, §3 authorization of (feed, type), and the jma §5.3 display replacement.
/// Chains are signed here with throwaway keys: root key(40) → intermediate key(41) → the post author key(3).
final class ImporterFeedTests: XCTestCase {
    private let now: Int64 = 1_792_195_200_000
    private let rootKey = key(40), midKey = key(41)
    private let quake = "震源・震度に関する情報"
    private let rain = "気象警報・注意報（Ｒ０６）（大雨）"
    private let bot = "did:plc:kxwz5cz7o6g4jlmxh6doyfsm"

    private func author() throws -> Author { try Author(keys: SecretKeys(nostr: key(3), atproto: key(4), plc: key(5)), pdsEndpoint: "https://pds.invalid") }
    private func root() throws -> Attest.Root { try Attest.Root(pubkey: try Secp.xOnlyPublicKey(rootKey), name: "test", roles: ["attest", "import"], maxDepth: 2) }

    private func pairs(_ key: String, _ list: [(String, [String])]) -> Cbor {
        .array(list.map { .map([key: .text($0.0), "types": .array($0.1.map { .text($0) })]) })
    }
    private func importer(sources: [(String, [String])]? = nil, feeds: [(String, [String])]? = nil) -> [String: Cbor] {
        var m: [String: Cbor] = ["$type": .text(Attest.importer)]
        if let sources { m["sources"] = pairs("botDid", sources) }
        if let feeds { m["feeds"] = pairs("feed", feeds) }
        return m
    }
    private func signed(_ issuer: [UInt8], _ subject: [UInt8], roles: [String], blocks: [[String: Cbor]]) throws -> Cbor {
        let body: [String: Cbor] = [
            "$type": .text(Attest.keyAttestation), "issuer": .bytes(try Secp.xOnlyPublicKey(issuer)), "subject": .bytes(subject),
            "owner": .map(["id": .text("test:owner"), "name": .text("テスト")]), "roles": .array(roles.sorted().map { .text($0) }),
            "blocks": .array(blocks.map { .map($0) }),
        ]
        return .map(["body": .map(body), "sig": .bytes(try Secp.signSchnorr(sha256(try CanonicalCbor.encode(.map(body))), issuer))])
    }
    /// root → intermediate (with [parent] as its importer block, or none) → leaf (with [leaf] and an organization).
    private func chain(parent: [String: Cbor]?, leaf: [String: Cbor]?) throws -> [Cbor] {
        let mid = try signed(rootKey, try Secp.xOnlyPublicKey(midKey), roles: ["attest", "import"],
                             blocks: [["$type": .text(Attest.delegation), "maxDepth": .int(1)]] + (parent.map { [$0] } ?? []))
        let end = try signed(midKey, try Secp.xOnlyPublicKey(key(3)), roles: ["import"],
                             blocks: [["$type": .text(Attest.keyValidity), "until": .text("2100-01-01T00:00:00Z")],
                                      ["$type": .text(Attest.organization), "orgId": .text("test:org"), "orgName": .text("テスト転載")]] + (leaf.map { [$0] } ?? []))
        return [mid, end]
    }
    private func earthquake(_ title: String, dateTime: String = "2026-11-14T01:00:00Z", status: String = "通常", eventID: String = "20261114100000",
                            pref: String? = nil) -> [String: Cbor] {
        var body: [String: Cbor] = [:]
        if let pref { body["observation"] = .map(["maxInt": .text("3"), "prefs": .array([.map(["name": .text("県"), "code": .map(["value": .text(pref), "type": .text("地震情報／都道府県等")])])])]) }
        return ["$type": .text(PublicIngress.jmaEarthquake),
                "message": .map(["control": .map(["title": .text(title), "dateTime": .text(dateTime), "status": .text(status)]),
                                 "head": .map(["eventID": .text(eventID)])]),
                "selection": pref == nil ? .map(["kind": .text("whole")]) : .map(["kind": .text("region"), "path": .array([.int(0)])]),
                "body": .map(body)]
    }
    private func weather(_ title: String, dateTime: String, area: String = "130000") -> [String: Cbor] {
        ["$type": .text(PublicIngress.jmaWeather),
         "message": .map(["control": .map(["title": .text(title), "dateTime": .text(dateTime), "status": .text("通常")]), "head": .map(["eventID": .text("")])]),
         "selection": .map(["kind": .text("whole")]),
         "warnings": .array([.map(["warningType": .text("気象警報・注意報（市町村等）"), "areas": .array([.map(["code": .map(["value": .text("1310100")])])])]),
                             .map(["warningType": .text("気象警報・注意報（府県予報区等）"), "areas": .array([.map(["code": .map(["value": .text(area)])])])])])]
    }
    private func post(_ ext: [[String: Cbor]], chain: [Cbor]) throws -> NostrEvent {
        try author().createPost("転載", nowMillis: now, extensions: ext + [["$type": .text(Attest.chain), "attestations": .array(chain)]]).event
    }
    private func view(_ e: NostrEvent, _ selected: Set<PublicIngress.Selection>) throws -> PublicIngress.ChainView {
        let at = Instant(epochSecond: e.createdAt + 60)
        return try XCTUnwrap(PublicIngress.chainView(e, roots: [try root()], revocations: PublicIngress.RevocationStore(), now: at, seenAt: at, selected: selected))
    }

    func testFeedsParseAndValidate() throws {
        func parse(_ block: [String: Cbor]) throws -> Attest.Importer? {
            try Attest.attestation(try signed(rootKey, try Secp.xOnlyPublicKey(midKey), roles: ["import"], blocks: [block])).importerBlock
        }
        let b = try XCTUnwrap(try parse(importer(sources: [(bot, ["earthquake"])], feeds: [("jma-xml", [rain, quake].sorted { Attest.lexicalCompare($0, $1) < 0 })])))
        XCTAssertEqual(b.feeds?["jma-xml"], [quake, rain])
        XCTAssertTrue(b.covers(.init(feed: "jma-xml", type: quake)))
        XCTAssertFalse(b.covers(.init(feed: "jma-xml", type: "震度速報")))
        XCTAssertFalse(b.covers(.init(feed: "other", type: quake)))
        XCTAssertTrue(b.covers(.init(botDid: bot, type: "earthquake")))
        // feeds alone is enough; an unknown feed name parses but authorizes nothing else.
        XCTAssertNil(try parse(importer(feeds: [("jma-xml", [quake]), ("zz-unknown", ["x"])]))?.sources)
        // Neither list, empty lists, unsorted or duplicate feeds/types, empty types, more than 8 feeds or 64 pairs: rejected.
        XCTAssertThrowsError(try parse(importer()))
        XCTAssertThrowsError(try parse(importer(feeds: [])))
        XCTAssertThrowsError(try parse(importer(sources: [], feeds: [("jma-xml", [quake])])))
        XCTAssertThrowsError(try parse(importer(feeds: [("b", ["x"]), ("a", ["x"])])))
        XCTAssertThrowsError(try parse(importer(feeds: [("a", ["x"]), ("a", ["y"])])))
        XCTAssertThrowsError(try parse(importer(feeds: [("a", ["y", "x"])])))
        XCTAssertThrowsError(try parse(importer(feeds: [("a", ["x", "x"])])))
        XCTAssertThrowsError(try parse(importer(feeds: [("a", [])])))
        XCTAssertThrowsError(try parse(importer(feeds: (0..<9).map { ("f\($0)", ["x"]) })))
        let types32 = (0..<32).map { String(format: "t%02d", $0) }
        XCTAssertNoThrow(try parse(importer(sources: [(bot, types32)], feeds: [("a", types32)])))
        XCTAssertThrowsError(try parse(importer(sources: [(bot, types32)], feeds: [("a", types32), ("b", ["x"])])))
    }

    func testFeedsContainment() throws {
        let r = try root()
        func state(_ parent: [String: Cbor]?, _ leaf: [String: Cbor]?) throws -> Attest.ChainState {
            Attest.verifyChain(try chain(parent: parent, leaf: leaf).map(Attest.attestation), roots: [r],
                               createdAt: Instant(epochSecond: now / 1000), now: Instant(epochSecond: now / 1000)).state
        }
        let parent = importer(sources: [(bot, ["earthquake"])], feeds: [("jma-xml", [quake, rain].sorted { Attest.lexicalCompare($0, $1) < 0 })])
        XCTAssertEqual(try state(parent, importer(feeds: [("jma-xml", [quake])])), .verified)
        XCTAssertEqual(try state(parent, importer(sources: [(bot, ["earthquake"])], feeds: [("jma-xml", [rain])])), .verified)
        // Wider type, other feed, or a list the parent lacks: invalid.
        XCTAssertEqual(try state(parent, importer(feeds: [("jma-xml", ["震度速報"])])), .invalid)
        XCTAssertEqual(try state(parent, importer(feeds: [("other", [quake])])), .invalid)
        XCTAssertEqual(try state(importer(sources: [(bot, ["earthquake"])]), importer(feeds: [("jma-xml", [quake])])), .invalid)
        XCTAssertEqual(try state(importer(feeds: [("jma-xml", [quake])]), importer(sources: [(bot, ["earthquake"])])), .invalid)
        // A block-less issuer leaves the child free.
        XCTAssertEqual(try state(nil, importer(feeds: [("jma-xml", [quake])])), .verified)
    }

    func testFeedPairAuthorizesJmaRepost() throws {
        let c = try chain(parent: nil, leaf: importer(feeds: [("jma-xml", [quake])]))
        let e = try post([earthquake(quake, pref: "13")], chain: c)
        XCTAssertEqual(try PublicIngress.importPair(e), .init(feed: "jma-xml", type: quake))
        let selected: Set<PublicIngress.Selection> = [.init(feed: "jma-xml", type: quake)]
        let v = try view(e, selected)
        XCTAssertTrue(v.confirmedNow && v.importAuthorized)
        let receipt = PublicIngress.verify(e, PublicIngress.Config(roots: [try root()], selected: selected), PublicIngress.RevocationStore(),
                                           Instant(epochSecond: e.createdAt + 60)).receipt
        XCTAssertEqual(receipt?.source, .init(feed: "jma-xml", type: quake))
        // Not selected locally, or a title the block does not cover: not authorized.
        XCTAssertFalse(try view(e, []).importAuthorized)
        XCTAssertFalse(try view(e, [.init(botDid: bot, type: quake)]).importAuthorized)
        XCTAssertFalse(try view(try post([earthquake("震度速報")], chain: c), [.init(feed: "jma-xml", type: "震度速報")]).importAuthorized)
        // Two JMA extensions: not a jma-xml re-post at all.
        let both = try post([earthquake(quake), weather(rain, dateTime: "2026-11-14T01:00:00Z")], chain: c)
        XCTAssertThrowsError(try PublicIngress.importPair(both))
        XCTAssertFalse(try view(both, selected).importAuthorized)
    }

    func testConfigAcceptsFeedSelections() throws {
        let c = try PublicIngress.config(#"{"roots":[],"selected":[{"botDid":"\#(bot)","type":"earthquake"},{"feed":"jma-xml","type":"\#(quake)"}]}"#)
        XCTAssertEqual(c.selected, [.init(botDid: bot, type: "earthquake"), .init(feed: "jma-xml", type: quake)])
        XCTAssertThrowsError(try PublicIngress.config(#"{"roots":[],"selected":[{"botDid":"\#(bot)","feed":"jma-xml","type":"x"}]}"#))
        XCTAssertThrowsError(try PublicIngress.config(#"{"roots":[],"selected":[{"feed":"","type":"x"}]}"#))
    }

    func testJmaExtensionDecodes() throws {
        let c = try chain(parent: nil, leaf: importer(feeds: [("jma-xml", [quake])]))
        let q = try XCTUnwrap(Extensions.decode(try post([earthquake(quake, pref: "13")], chain: c)).jma)
        XCTAssertEqual(q.kind, .earthquake)
        XCTAssertEqual([q.title, q.status, q.dateTime, q.eventID], [quake, "通常", "2026-11-14T01:00:00Z", "20261114100000"])
        XCTAssertFalse(q.whole)
        XCTAssertEqual(q.prefCode, Jma.Code(value: "13", type: "地震情報／都道府県等"))
        let w = try XCTUnwrap(Extensions.decode(try post([weather(rain, dateTime: "2026-11-14T01:00:00Z")], chain: c)).jma)
        XCTAssertEqual(w.forecastAreaCode, Jma.Code(value: "130000", type: nil))
    }

    func testDisplayReplacement() throws {
        func jma(_ ext: [String: Cbor]) -> Jma { Extensions.jma(ext)! }
        let t0 = "2026-11-14T01:00:00Z", t1 = "2026-11-14T01:05:00Z"
        // Earthquake: a newer post of the same Pref replaces; another Pref does not; a newer whole post replaces all.
        XCTAssertEqual(JmaDisplay.superseded([("a", jma(earthquake(quake, dateTime: t0, pref: "13"))), ("b", jma(earthquake(quake, dateTime: t1, pref: "13"))),
                                              ("c", jma(earthquake(quake, dateTime: t0, pref: "14")))]), ["a"])
        XCTAssertEqual(JmaDisplay.superseded([("a", jma(earthquake(quake, dateTime: t0, pref: "13"))), ("c", jma(earthquake(quake, dateTime: t0, pref: "14"))),
                                              ("w", jma(earthquake(quake, dateTime: t1)))]), ["a", "c"])
        // Different event, status or title: another series. Same time: no replacement.
        XCTAssertEqual(JmaDisplay.superseded([("a", jma(earthquake(quake, dateTime: t0, pref: "13"))), ("b", jma(earthquake(quake, dateTime: t1, eventID: "x", pref: "13"))),
                                              ("c", jma(earthquake(quake, dateTime: t1, status: "訓練", pref: "13"))),
                                              ("d", jma(earthquake("震度速報", dateTime: t1, pref: "13"))), ("e", jma(earthquake(quake, dateTime: t0, pref: "13")))]), [])
        // Weather R06: series by title, status and district; any newer post replaces.
        XCTAssertEqual(JmaDisplay.superseded([("a", jma(weather(rain, dateTime: t0))), ("b", jma(weather(rain, dateTime: t1))),
                                              ("c", jma(weather(rain, dateTime: t0, area: "140000"))),
                                              ("d", jma(weather("気象警報・注意報（Ｒ０６）（土砂）", dateTime: t0)))]), ["a"])
        // Other weather titles have no series yet (weather §7).
        XCTAssertEqual(JmaDisplay.superseded([("a", jma(weather("気象警報・注意報（Ｈ２７）", dateTime: t0))), ("b", jma(weather("気象警報・注意報（Ｈ２７）", dateTime: t1)))]), [])
    }
}
