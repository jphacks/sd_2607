import XCTest
@testable import MosimoCore

/// importer §1 `feeds`, §1.1 feed identifiers, §1.2 the declaration, §1.3 containment, §3 authorization of (feed, origin, type),
/// and the jma §5.3 display replacement (mirrors pds/attest.test.mjs 'importer feeds: ...').
/// Chains are signed here with throwaway keys: root key(40) → intermediate key(41) → the post author key(3).
final class ImporterFeedTests: XCTestCase {
    private let now: Int64 = 1_792_195_200_000
    private let rootKey = key(40), midKey = key(41)
    private let quake = "震源・震度に関する情報"
    private let rain = "気象警報・注意報（Ｒ０６）（大雨）"
    private let bot = "did:plc:kxwz5cz7o6g4jlmxh6doyfsm"
    private let jma = PublicIngress.jmaFeed
    private let cap = "org.example.alerts.cap", us = "urn:oid:2.49.0.1.840.0", jp = "urn:oid:2.49.0.1.392.0"
    private struct F { let feed: String; var origin: String? = nil; var types: [String]? = nil }

    private func author() throws -> Author { try Author(keys: SecretKeys(nostr: key(3), atproto: key(4), plc: key(5)), pdsEndpoint: "https://pds.invalid") }
    private func root() throws -> Attest.Root { try Attest.Root(pubkey: try Secp.xOnlyPublicKey(rootKey), name: "test", roles: ["attest", "import"], maxDepth: 2) }

    private func pairs(_ key: String, _ list: [(String, [String])]) -> Cbor {
        .array(list.map { .map([key: .text($0.0), "types": .array($0.1.map { .text($0) })]) })
    }
    private func importer(sources: [(String, [String])]? = nil, feeds: [F]? = nil) -> [String: Cbor] {
        var m: [String: Cbor] = ["$type": .text(Attest.importer)]
        if let sources { m["sources"] = pairs("botDid", sources) }
        if let feeds {
            m["feeds"] = .array(feeds.map { f in
                var e: [String: Cbor] = ["feed": .text(f.feed)]
                if let o = f.origin { e["origin"] = .text(o) }
                if let t = f.types { e["types"] = .array(t.map { .text($0) }) }
                return .map(e)
            })
        }
        return m
    }
    private func sorted(_ types: [String]) -> [String] { types.sorted { Attest.lexicalCompare($0, $1) < 0 } }
    /// `public#source` with the feed declaration [feed] ({id, origin?, type}), or none.
    private func source(_ id: String?, origin: String? = nil, type: String = "") -> [String: Cbor] {
        var m: [String: Cbor] = ["$type": .text(PublicIngress.sourceType), "document": .map(["format": .text("jma-xml-1.3")])]
        if let id {
            var f: [String: Cbor] = ["id": .text(id), "type": .text(type)]
            if let origin { f["origin"] = .text(origin) }
            m["feed"] = .map(f)
        }
        return m
    }
    /// A JMA earthquake re-post's extensions, declaring the JMA feed with [declared] (default: the title itself).
    private func jmaPost(_ title: String, declared: String? = nil, origin: String? = nil, pref: String? = "13") -> [[String: Cbor]] {
        [earthquake(title, pref: pref), source(jma, origin: origin, type: declared ?? title)]
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

    func testFeedIdentifiersAndValidation() throws {
        func parse(_ block: [String: Cbor]) throws -> Attest.Importer? {
            try Attest.attestation(try signed(rootKey, try Secp.xOnlyPublicKey(midKey), roles: ["import"], blocks: [block])).importerBlock
        }
        let b = try XCTUnwrap(try parse(importer(sources: [(bot, ["earthquake"])], feeds: [F(feed: jma, types: sorted([rain, quake]))])))
        XCTAssertEqual(b.feeds, [.init(feed: jma, types: [quake, rain])])
        XCTAssertTrue(b.covers(.init(feed: jma, type: quake)))
        XCTAssertFalse(b.covers(.init(feed: jma, type: "震度速報")))
        XCTAssertFalse(b.covers(.init(feed: cap, type: quake)))
        // An entry without origin covers every issuer (the JMA declaration itself never has one, importer §1.2).
        XCTAssertTrue(b.covers(.init(feed: jma, origin: us, type: quake)))
        XCTAssertTrue(b.covers(.init(botDid: bot, type: "earthquake")))
        XCTAssertNil(try parse(importer(feeds: [F(feed: jma, types: [quake])]))?.sources)
        // Neither list, empty lists, unsorted or duplicate types, empty types, unknown fields: rejected.
        XCTAssertThrowsError(try parse(importer()))
        XCTAssertThrowsError(try parse(importer(feeds: [])))
        XCTAssertThrowsError(try parse(importer(sources: [], feeds: [F(feed: jma, types: [quake])])))
        XCTAssertThrowsError(try parse(importer(feeds: [F(feed: jma, types: ["b", "a"])])))
        XCTAssertThrowsError(try parse(importer(feeds: [F(feed: jma, types: ["x", "x"])])))
        XCTAssertThrowsError(try parse(importer(feeds: [F(feed: jma, types: [])])))
        XCTAssertThrowsError(try parse(importer(feeds: [F(feed: jma, types: [""])])))
        XCTAssertThrowsError(try parse(importer(feeds: [F(feed: jma, types: [String(repeating: "a", count: 129)])])))
        var extra = importer(feeds: [F(feed: jma)]); extra["other"] = .int(1)
        XCTAssertThrowsError(try parse(extra))
        // Feed IDs are NSIDs with a lowercase authority; the BSAF NSID is not a feed; origins are absolute URIs.
        for feed in ["jma-xml", "Dev.example.feed", "example.feed", "org.example.my-feed", "org.example.1feed", PublicIngress.bsaf,
                     "org." + String(repeating: "a", count: 64) + ".feed"] {
            XCTAssertThrowsError(try parse(importer(feeds: [F(feed: feed)])), feed)
            if feed != PublicIngress.bsaf { XCTAssertFalse(Attest.isFeedId(feed), feed) }
        }
        XCTAssertTrue(Attest.isFeedId("org.example.alerts.Cap2"))
        XCTAssertThrowsError(try parse(importer(feeds: [F(feed: cap, origin: "not a uri")])))
        XCTAssertThrowsError(try parse(importer(feeds: [F(feed: cap, origin: "urn:" + String(repeating: "a", count: 253))])))
        XCTAssertNoThrow(try parse(importer(feeds: [F(feed: cap, origin: "urn:" + String(repeating: "a", count: 252))])))
        XCTAssertFalse(Attest.isOrigin("urn:oid:2.49\u{7}"))
        XCTAssertTrue(Attest.isOrigin("https://www.example.go.xx/"))
        // Sorted by feed, then origin with the absent origin first; (feed, origin) unique.
        let mixed = try XCTUnwrap(try parse(importer(feeds: [F(feed: jma, types: [quake]), F(feed: cap), F(feed: cap, origin: jp, types: ["Flood"]), F(feed: cap, origin: us)])))
        XCTAssertEqual(mixed.feeds?.count, 4)
        XCTAssertThrowsError(try parse(importer(feeds: [F(feed: cap, origin: us), F(feed: cap)])))
        XCTAssertThrowsError(try parse(importer(feeds: [F(feed: cap, origin: us), F(feed: cap, origin: us, types: ["x"])])))
        XCTAssertThrowsError(try parse(importer(feeds: [F(feed: "org.example.b"), F(feed: "org.example.a")])))
        XCTAssertThrowsError(try parse(importer(feeds: [F(feed: cap), F(feed: jma)])))
        // At most 32 entries; explicit types over both lists at most 64; an entry without types counts none.
        XCTAssertNoThrow(try parse(importer(feeds: (0..<32).map { F(feed: String(format: "org.example.f%02d", $0)) })))
        XCTAssertThrowsError(try parse(importer(feeds: (0..<33).map { F(feed: String(format: "org.example.f%02d", $0)) })))
        let types32 = (0..<32).map { String(format: "t%02d", $0) }
        XCTAssertNoThrow(try parse(importer(sources: [(bot, types32)], feeds: [F(feed: jma), F(feed: cap, types: types32)])))
        XCTAssertThrowsError(try parse(importer(sources: [(bot, types32)], feeds: [F(feed: jma, types: ["x"]), F(feed: cap, types: types32)])))
        // Absent origin or types stand for every issuer or type; a post without origin needs an entry without origin.
        func covers(_ feeds: [F], _ origin: String?, _ type: String) -> Bool {
            Attest.Importer(sources: nil, feeds: feeds.map { .init(feed: $0.feed, origin: $0.origin, types: $0.types.map(Set.init)) })
                .covers(.init(feed: cap, origin: origin, type: type))
        }
        XCTAssertTrue(covers([F(feed: cap)], us, "Flood"))
        XCTAssertTrue(covers([F(feed: cap, origin: us)], us, "Flood"))
        XCTAssertFalse(covers([F(feed: cap, origin: us)], jp, "Flood"))
        XCTAssertFalse(covers([F(feed: cap, origin: us)], nil, "Flood"))
        XCTAssertTrue(covers([F(feed: cap)], nil, "Flood"))
        XCTAssertFalse(covers([F(feed: cap, types: ["Fire"])], us, "Flood"))
    }

    func testFeedsContainment() throws {
        let r = try root()
        func state(_ parent: [String: Cbor]?, _ leaf: [String: Cbor]?) throws -> Attest.ChainState {
            Attest.verifyChain(try chain(parent: parent, leaf: leaf).map(Attest.attestation), roots: [r],
                               createdAt: Instant(epochSecond: now / 1000), now: Instant(epochSecond: now / 1000)).state
        }
        let parent = importer(sources: [(bot, ["earthquake"])], feeds: [F(feed: jma, types: sorted([quake, rain])), F(feed: cap, origin: us)])
        XCTAssertEqual(try state(parent, importer(feeds: [F(feed: jma, types: [quake])])), .verified)
        XCTAssertEqual(try state(parent, importer(sources: [(bot, ["earthquake"])], feeds: [F(feed: jma, types: [rain])])), .verified)
        XCTAssertEqual(try state(parent, importer(feeds: [F(feed: cap, origin: us)])), .verified)
        XCTAssertEqual(try state(parent, importer(feeds: [F(feed: cap, origin: us, types: ["Flood"])])), .verified)
        // Wider type, other feed, other origin, or a list the parent lacks: invalid.
        XCTAssertEqual(try state(parent, importer(feeds: [F(feed: jma, types: ["震度速報"])])), .invalid)
        XCTAssertEqual(try state(parent, importer(feeds: [F(feed: "org.example.other", types: [quake])])), .invalid)
        XCTAssertEqual(try state(parent, importer(feeds: [F(feed: cap, origin: jp)])), .invalid)
        XCTAssertEqual(try state(importer(sources: [(bot, ["earthquake"])]), importer(feeds: [F(feed: jma, types: [quake])])), .invalid)
        XCTAssertEqual(try state(importer(feeds: [F(feed: jma, types: [quake])]), importer(sources: [(bot, ["earthquake"])])), .invalid)
        // An origin-wide child needs an origin-wide parent; a type-wide child a type-wide parent.
        XCTAssertEqual(try state(parent, importer(feeds: [F(feed: cap)])), .invalid)
        XCTAssertEqual(try state(parent, importer(feeds: [F(feed: jma)])), .invalid)
        XCTAssertEqual(try state(importer(feeds: [F(feed: cap)]), importer(feeds: [F(feed: cap), F(feed: cap, origin: jp, types: ["Flood"])])), .verified)
        // A block-less issuer leaves the child free.
        XCTAssertEqual(try state(nil, importer(feeds: [F(feed: cap)])), .verified)
    }

    /// importer §1: the nearest ancestor's block bounds the leaf; a block-less intermediate does not widen it (as in pds/attest.mjs).
    func testBlocklessIntermediateInheritsTheScope() throws {
        let r = try Attest.Root(pubkey: try Secp.xOnlyPublicKey(rootKey), name: "test", roles: ["attest", "import"], maxDepth: 3)
        let interKey = key(42)
        func state(_ ca: [String: Cbor]?, _ leaf: [String: Cbor]) throws -> Attest.ChainState {
            let a = try signed(rootKey, try Secp.xOnlyPublicKey(midKey), roles: ["attest", "import"],
                               blocks: [["$type": .text(Attest.delegation), "maxDepth": .int(2)]] + (ca.map { [$0] } ?? []))
            let b = try signed(midKey, try Secp.xOnlyPublicKey(interKey), roles: ["attest", "import"],
                               blocks: [["$type": .text(Attest.delegation), "maxDepth": .int(1)], ["$type": .text(Attest.keyValidity), "until": .text("2100-01-01T00:00:00Z")]])
            let c = try signed(interKey, try Secp.xOnlyPublicKey(key(3)), roles: ["import"],
                               blocks: [["$type": .text(Attest.keyValidity), "until": .text("2100-01-01T00:00:00Z")], leaf])
            let at = Instant(epochSecond: now / 1000)
            return Attest.verifyChain(try [a, b, c].map(Attest.attestation), roots: [r], createdAt: at, now: at).state
        }
        let ca = importer(feeds: [F(feed: jma, types: [quake])])
        XCTAssertEqual(try state(ca, importer(feeds: [F(feed: jma, types: [quake])])), .verified)
        XCTAssertEqual(try state(ca, importer(feeds: [F(feed: jma, types: ["震度速報"])])), .invalid)
        XCTAssertEqual(try state(ca, importer(feeds: [F(feed: jma)])), .invalid)
        XCTAssertEqual(try state(ca, importer(sources: [(bot, ["earthquake"])])), .invalid)
        // No block anywhere above: free while the direct parent has `import`.
        XCTAssertEqual(try state(nil, importer(feeds: [F(feed: cap)])), .verified)
    }

    func testDeclaredFeedRepostAuthorization() throws {
        let c = try chain(parent: nil, leaf: importer(feeds: [F(feed: jma, types: [quake]), F(feed: cap, origin: us, types: ["Flood Warning"])]))
        let e = try post(jmaPost(quake), chain: c)
        XCTAssertEqual(try PublicIngress.importPair(e), .init(feed: jma, type: quake))
        let selected: Set<PublicIngress.Selection> = [.init(feed: jma, type: quake)]
        let v = try view(e, selected)
        XCTAssertTrue(v.confirmedNow && v.importAuthorized)
        XCTAssertTrue(try view(e, [.init(feed: jma)]).importAuthorized)
        let receipt = PublicIngress.verify(e, PublicIngress.Config(roots: [try root()], selected: selected), PublicIngress.RevocationStore(),
                                           Instant(epochSecond: e.createdAt + 60)).receipt
        XCTAssertEqual(receipt?.source, .init(feed: jma, type: quake))
        // Not selected locally, or a title the block does not cover: not authorized.
        XCTAssertFalse(try view(e, []).importAuthorized)
        XCTAssertFalse(try view(e, [.init(botDid: bot, type: quake)]).importAuthorized)
        XCTAssertFalse(try view(e, [.init(feed: jma, type: "震度速報")]).importAuthorized)
        XCTAssertFalse(try view(e, [.init(feed: jma, origin: us)]).importAuthorized)
        XCTAssertFalse(try view(try post(jmaPost("震度速報"), chain: c), [.init(feed: jma)]).importAuthorized)
        // A known feed's declaration must match the content: the title, no origin, exactly one JMA extension.
        XCTAssertThrowsError(try PublicIngress.importPair(try post(jmaPost(quake, declared: "震度速報"), chain: c)))
        XCTAssertThrowsError(try PublicIngress.importPair(try post(jmaPost(quake, origin: us), chain: c)))
        let both = try post(jmaPost(quake) + [weather(rain, dateTime: "2026-11-14T01:00:00Z")], chain: c)
        XCTAssertThrowsError(try PublicIngress.importPair(both))
        XCTAssertFalse(try view(both, selected).importAuthorized)
        // No declaration, two sources, the old feed name, or a declaration next to public.bsaf: no feed re-post.
        XCTAssertThrowsError(try PublicIngress.importPair(try post([earthquake(quake), source(nil)], chain: c)))
        XCTAssertThrowsError(try PublicIngress.importPair(try post([earthquake(quake)], chain: c)))
        XCTAssertThrowsError(try PublicIngress.importPair(try post(jmaPost(quake) + [source(nil)], chain: c)))
        XCTAssertThrowsError(try PublicIngress.importPair(try post([earthquake(quake), source("jma-xml", type: quake)], chain: c)))
        XCTAssertThrowsError(try PublicIngress.importPair(try post([source(PublicIngress.bsaf, type: quake)], chain: c)))
        // An unknown feed is authorized as declared, by origin and type.
        let capPost = try post([source(cap, origin: us, type: "Flood Warning")], chain: c)
        XCTAssertEqual(try PublicIngress.importPair(capPost), .init(feed: cap, origin: us, type: "Flood Warning"))
        XCTAssertEqual(Extensions.decode(capPost).feed, FeedRef(id: cap, origin: us, type: "Flood Warning"))
        XCTAssertNil(Extensions.decode(try post(jmaPost(quake) + [source(nil)], chain: c)).feed)
        XCTAssertTrue(try view(capPost, [.init(feed: cap)]).importAuthorized)
        XCTAssertTrue(try view(capPost, [.init(feed: cap, origin: us, type: "Flood Warning")]).importAuthorized)
        XCTAssertFalse(try view(capPost, [.init(feed: cap, origin: jp)]).importAuthorized)
        let otherOrigin = try post([source(cap, origin: jp, type: "Flood Warning")], chain: c)
        XCTAssertFalse(try view(otherOrigin, [.init(feed: cap)]).importAuthorized)
        XCTAssertNil(PublicIngress.verify(otherOrigin, PublicIngress.Config(roots: [try root()], selected: [.init(feed: cap)]), PublicIngress.RevocationStore(),
                                          Instant(epochSecond: otherOrigin.createdAt + 60)).receipt)
        let noOrigin = try post([source(cap, type: "Flood Warning")], chain: c)
        XCTAssertFalse(try view(noOrigin, [.init(feed: cap)]).importAuthorized)
        XCTAssertThrowsError(try PublicIngress.importPair(try post([source(cap, origin: "not a uri", type: "x")], chain: c)))
        // A BSAF-only leaf covers no feed.
        let bsafLeaf = try chain(parent: nil, leaf: importer(sources: [(bot, ["earthquake"])]))
        XCTAssertFalse(try view(try post(jmaPost(quake), chain: bsafLeaf), [.init(feed: jma)]).importAuthorized)
    }

    func testSourceLinksAndOriginalPage() throws {
        let c = try chain(parent: nil, leaf: importer(feeds: [F(feed: jma, types: [quake])]))
        var src = source(jma, type: quake)
        src["links"] = .array([.map(["url": .text("ftp://example.org/a.xml")]), .map(["url": .text("https://example.org/a.xml"), "mediaType": .text("application/xml")]),
                               .map(["url": .text("https://www.jma.go.jp/bosai/map.html"), "mediaType": .text("text/html")])])
        let e = Extensions.decode(try post([earthquake(quake), src], chain: c))
        XCTAssertEqual(e.sourceLinks.count, 3)
        XCTAssertEqual(e.sourceWebUrl?.absoluteString, "https://www.jma.go.jp/bosai/map.html")
        var other = PostExtensions()
        other.sourceLinks = [SourceLink(url: "ftp://x/y", mediaType: "text/html"), SourceLink(url: "http://example.org/", mediaType: nil)]
        XCTAssertEqual(other.sourceWebUrl?.absoluteString, "http://example.org/")
        XCTAssertNil(PostExtensions().sourceWebUrl)
        let b = Bsaf(type: nil, time: nil, target: nil, source: nil, tags: [], imported: true, botDid: bot,
                     originalUri: "at://\(bot)/app.bsky.feed.post/3lbx2abc")
        XCTAssertEqual(b.originalWebUrl?.absoluteString, "https://bsky.app/profile/\(bot)/post/3lbx2abc")
        XCTAssertNil(Bsaf(type: nil, time: nil, target: nil, source: nil, tags: [], imported: true, botDid: bot, originalUri: "at://\(bot)/app.bsky.feed.like/x").originalWebUrl)
    }

    func testConfigAcceptsFeedSelections() throws {
        let c = try PublicIngress.config(#"{"roots":[],"selected":[{"botDid":"\#(bot)","type":"earthquake"},{"feed":"\#(jma)","type":"\#(quake)"},"#
                                         + #"{"feed":"\#(cap)"},{"feed":"\#(cap)","origin":"\#(us)","type":"Flood"}]}"#)
        XCTAssertEqual(c.selected, [.init(botDid: bot, type: "earthquake"), .init(feed: jma, type: quake), .init(feed: cap), .init(feed: cap, origin: us, type: "Flood")])
        for bad in [#"{"botDid":"\#(bot)","feed":"\#(jma)","type":"x"}"#, #"{"feed":"","type":"x"}"#, #"{"feed":"jma-xml","type":"x"}"#,
                    #"{"feed":"\#(PublicIngress.bsaf)"}"#, #"{"feed":"\#(cap)","origin":"not a uri"}"#, #"{"feed":"\#(cap)","type":""}"#,
                    #"{"feed":"\#(cap)","types":["x"]}"#, #"{"botDid":"\#(bot)"}"#] {
            XCTAssertThrowsError(try PublicIngress.config(#"{"roots":[],"selected":["# + bad + "]}"), bad)
        }
    }

    func testJmaExtensionDecodes() throws {
        let c = try chain(parent: nil, leaf: importer(feeds: [F(feed: jma, types: [quake])]))
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
