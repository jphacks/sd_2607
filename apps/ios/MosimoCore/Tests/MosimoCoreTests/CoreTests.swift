import CryptoKit
import XCTest
@testable import MosimoCore

/// Ports of the Android bundle-core / mesh-android vectors that pin byte-level behaviour.
final class CoreTests: XCTestCase {
    func author(_ n: UInt8 = 3) throws -> Author { try Author(keys: SecretKeys(nostr: key(n), atproto: key(n + 1), plc: key(n + 2)), pdsEndpoint: "https://pds.invalid") }
    let now: Int64 = 1_792_195_200_000

    func testBip340VectorZero() throws {
        let pub = "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"
        let sig = "e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca821525f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0"
        XCTAssertEqual(hex(try Secp.xOnlyPublicKey(key(3))), pub)
        XCTAssertEqual(hex(try Secp.signSchnorr([UInt8](repeating: 0, count: 32), key(3), aux: [UInt8](repeating: 0, count: 32))), sig)
        XCTAssertTrue(Secp.verifySchnorr(try unhex(sig, 64), [UInt8](repeating: 0, count: 32), try unhex(pub, 32)))
    }

    func testCborCanonicalRulesAndLenientDecode() throws {
        for bad in ["1801", "9fff", "f90000", "a2616101616102", "0100", "5b7fffffffffffffff", "61ff", "d82a4100"] {
            XCTAssertThrowsError(try CanonicalCbor.decode(try unhex(bad)), bad)
        }
        XCTAssertEqual(hex(try CanonicalCbor.encode(.map(["aa": .int(1), "b": .int(2)]))), "a261620262616101")
        // Non-minimal integer heads are accepted only leniently (other implementations' exchange messages).
        XCTAssertEqual(try CanonicalCbor.decode(try unhex("1801"), canonical: false), .int(1))
        XCTAssertThrowsError(try CanonicalCbor.decode(try unhex("9fff"), canonical: false))
    }

    func testCborSequenceCutsArbitraryChunks() throws {
        let a = try Messages.encode(.want(requestId: 1, ids: [[UInt8](repeating: 7, count: 32)]))
        let b = try Messages.encode(.data(transferId: 300, fragment: [UInt8](repeating: 1, count: 700)))
        let stream = a + b
        var reader = CborSequenceReader(limit: 1024)
        var out = [[UInt8]]()
        for chunk in stream.chunked(17) { out += try reader.feed(chunk) }
        XCTAssertEqual(out, [a, b])
        var small = CborSequenceReader(limit: 100)
        XCTAssertThrowsError(try small.feed(Array(b.prefix(10))))
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

    func testPostUsesFixedTagOrderAndRestores() throws {
        let a = try author()
        let ext = [Extensions.origin(Origin(lat: 35_681_236, lon: 139_767_125, acc: 5000)), Extensions.validity(until: "2026-10-17T06:00:00Z")]
        let first = try a.createPost("［安全］本文", nowMillis: now, extensions: ext)
        let tags = first.event.tags
        XCTAssertEqual(tags.prefix(6).map { $0[0] }, ["t", "mosimo:v", "mosimo:commit", "mosimo:base", "mosimo:rkey", "mosimo:exp"])
        XCTAssertEqual(tags[0], ["t", "mosimo.jisaku.dev"])
        XCTAssertEqual(tags[3][1], "")
        XCTAssertEqual(tags[5][1], "2026-10-18T00:00:00Z") // default deadline: created + 24 h
        XCTAssertEqual(tags[6][0], "mosimo:ext:dev.jisaku.mosimo.geo#origin")
        let change = try AtRepository.restore(first.event, atprotoPubkey: a.atprotoPubkey)
        XCTAssertEqual(first.repository.records[change.path], change.recordCid)
        XCTAssertEqual(first.repository.previousCommit, change.commitCid)
        let second = try a.createPost("二件目", nowMillis: now, previous: first.repository)
        XCTAssertEqual(second.event.tags[3][1], first.repository.previousCommit)
        XCTAssertGreaterThan(second.event.tags[4][1], first.event.tags[4][1])
        let decoded = Extensions.decode(first.event)
        XCTAssertEqual(decoded.origin, Origin(lat: 35_681_236, lon: 139_767_125, acc: 5000))
        XCTAssertEqual(decoded.validity, Validity(from: nil, until: "2026-10-17T06:00:00Z"))
        XCTAssertEqual(AtRepository.did(first.event), a.did)
        // Swapping two base tags breaks the fixed order even with a valid signature.
        XCTAssertFalse(AtRepository.verifyEnvelope(try resign(a, first.event) { t in t.swapAt(3, 4) }))
    }

    func testMeshPayloadTypesTagsReversibly() throws {
        let a = try author()
        let post = try a.createPost("本文", nowMillis: now, extensions: [Extensions.circle(lat: 1, lon: 2, r: 3)]).event
        let payload = try EventCodec.encode(post)
        guard let arr = try CanonicalCbor.decode(payload).array, let tags = arr[4].array else { return XCTFail() }
        // t and mosimo:v omitted; commit/ext bytes, base empty bytes, rkey uint, exp delta.
        XCTAssertEqual(tags.count, post.tags.count - 2)
        XCTAssertNotNil(tags[0].array?[1].bytes)
        XCTAssertEqual(tags[1].array?[1], .bytes([]))
        XCTAssertNotNil(tags[2].array?[1].int)
        XCTAssertEqual(tags[3].array?[1], .int(86400))
        XCTAssertNotNil(tags[4].array?[1].bytes)
        XCTAssertEqual(try EventCodec.decode(payload), post)
        // A value that does not round-trip stays a string ("001" is never an integer).
        let odd = try a.signEvent(4, [["mosimo:rkey", "001"], ["mosimo:commit", "AA=="]], "x", nowMillis: now)
        XCTAssertEqual(try EventCodec.decode(try EventCodec.encode(odd)), odd)
        let plc = try a.createPlcGenesisEvent(nowMillis: now)
        XCTAssertEqual(AtRepository.controlTopic(plc), AtRepository.plcTopic)
        XCTAssertEqual(try EventCodec.decode(try EventCodec.encode(plc)), plc)
    }

    /// delete §2: deleting the last post leaves an empty repository that later posts still build on.
    func testPostingContinuesAfterLastPostDeleted() throws {
        let a = try author()
        let only = try a.createPost("唯一", nowMillis: now)
        let del = try a.createDeletion(only.event, nowMillis: now + 1000, previous: only.repository)
        XCTAssertTrue(del.repository.records.isEmpty)
        let next = try a.createPost("削除後", nowMillis: now + 2000, previous: del.repository)
        XCTAssertEqual(next.event.tags[3][1], cid(try XCTUnwrap(AtRepository.deletion(del.event)?.change).commitBytes))
        XCTAssertEqual(next.repository.records.count, 1)
        var broken = next.repository; broken.previousCommit = nil
        XCTAssertThrowsError(try a.createPost("不整合", nowMillis: now + 3000, previous: broken))
    }

    /// delete §1-2: the request carries the commit of the repository without the post, and the next post builds on it.
    func testDeletionCarriesRemovalCommit() throws {
        let a = try author()
        let first = try a.createPost("残す", nowMillis: now)
        let target = try a.createPost("消す", nowMillis: now + 1, previous: first.repository)
        let del = try a.createDeletion(target.event, nowMillis: now + 2000, previous: target.repository)
        XCTAssertEqual(del.event.kind, 5)
        XCTAssertEqual(del.event.tags.map { $0[0] }, ["t", "e", "k", "mosimo:commit", "mosimo:base", "mosimo:rkey", "mosimo:exp"])
        let parsed = try XCTUnwrap(AtRepository.deletion(del.event))
        XCTAssertEqual(parsed.target, target.event.id)
        XCTAssertEqual(parsed.change?.applyFromCommit, target.repository.previousCommit)
        let removed = try AtRepository.restore(target.event).path
        XCTAssertNil(del.repository.records[removed])
        XCTAssertEqual(del.repository.records.count, 1)
        // The removal commit's MST root is the root of the remaining records, signed by the atproto key.
        let commit = try XCTUnwrap(try CanonicalCbor.decode(parsed.change!.commitBytes).map)
        XCTAssertEqual(commit["data"], .cid(try AtRepository.mstRoot(del.repository.records)))
        var unsigned = commit; unsigned["sig"] = nil
        XCTAssertTrue(Secp.verify(commit["sig"]!.bytes!, sha256(try CanonicalCbor.encode(.map(unsigned))), a.atprotoPubkey))
        let next = try a.createPost("次", nowMillis: now + 3000, previous: del.repository)
        XCTAssertEqual(next.event.tags[3][1], cid(parsed.change!.commitBytes))
        XCTAssertEqual(try EventCodec.decode(try EventCodec.encode(del.event)), del.event)
        XCTAssertTrue(DiskState.relayWants(DiskState.pdsRelay, del.event))
        XCTAssertThrowsError(try author(10).createDeletion(target.event, nowMillis: now, previous: RepositoryState()))
    }

    /// delete §3 and report §2: deletion by the author stops at once; reports stop at two distinct reporters.
    func testModerationStopsTargets() throws {
        let a = try author(), b = try author(10), c = try author(20)
        let post = try a.createPost("対象", nowMillis: now)
        let m = Moderation()
        // A stranger's deletion request has no effect.
        let forged = try b.signEvent(5, [AtRepository.topicTag, ["e", post.event.id], ["k", "1"]], "", nowMillis: now)
        m.absorb(forged)
        XCTAssertFalse(m.stopped(post.event))
        let r1 = try b.createReport(post.event, type: "spam", nowMillis: now)
        XCTAssertEqual(AtRepository.report(r1)?.type, "spam")
        m.absorb(r1); m.absorb(try b.createReport(post.event, type: "other", nowMillis: now + 1000))
        XCTAssertFalse(m.stopped(post.event), "one reporter counts once")
        let r3 = try c.createReport(post.event, type: "illegal", comment: "理由", nowMillis: now)
        m.absorb(r3)
        XCTAssertTrue(m.stopped(post.event) && m.skip(post.event.id))
        // delete §5: withdrawing a report drops it from the count.
        m.absorb(try c.createDeletion(r3, nowMillis: now + 2000, previous: RepositoryState()).event)
        XCTAssertFalse(m.stopped(post.event))
        XCTAssertThrowsError(try a.createReport(post.event, type: "spam", nowMillis: now))
        XCTAssertThrowsError(try b.createReport(post.event, type: "bogus", nowMillis: now))
        let deletion = try a.createDeletion(post.event, nowMillis: now + 1000, previous: post.repository).event
        m.absorb(deletion)
        XCTAssertTrue(m.deleted(post.event))
        XCTAssertEqual(try EventCodec.decode(try EventCodec.encode(r3)), r3)
    }

    func testNegentropyFindsSymmetricDifference() throws {
        var rng = SystemRandomNumberGenerator()
        func item(_ t: Int64) throws -> Negentropy.Item { try Negentropy.Item(timestamp: t, id: (0..<32).map { _ in UInt8.random(in: 0...255, using: &rng) }) }
        for (shared, onlyA, onlyB) in [(0, 0, 0), (0, 3, 0), (10, 0, 4), (300, 40, 70), (2000, 1, 1)] {
            let common = try (0..<shared).map { _ in try item(1_700_000_000 + Int64.random(in: 0..<5000)) }
            let a = try (0..<onlyA).map { _ in try item(1_700_000_000 + Int64.random(in: 0..<5000)) }
            let b = try (0..<onlyB).map { _ in try item(42) }
            for frame in [1000, 4096] {
                let initiator = try Negentropy(common + a, frameSizeLimit: frame)
                var msg: [UInt8]? = initiator.initiate()
                var rounds = 0
                while let m = msg {
                    XCTAssertLessThanOrEqual(m.count, frame)
                    let reply = try Negentropy(common + b, frameSizeLimit: frame).reconcile(m)!
                    XCTAssertLessThanOrEqual(reply.count, frame)
                    msg = try initiator.reconcile(reply)
                    rounds += 1; XCTAssertLessThan(rounds, 500)
                }
                XCTAssertEqual(Set(initiator.haveIds), Set(a.map(\.id)))
                XCTAssertEqual(Set(initiator.needIds), Set(b.map(\.id)))
            }
        }
        // Reference implementation: version 0x61, infinity bound, IdList with 0 IDs.
        XCTAssertEqual(try Negentropy([]).initiate(), [0x61, 0, 0, 2, 0])
    }

    func testMessagesRoundTrip() throws {
        let id = [UInt8](repeating: 9, count: 32)
        let all: [Msg] = [
            .hello(nonce: [1, 2, 3, 4, 5, 6, 7, 8], maxBundleBytes: 65536, routes: 3, initialCredit: 4096, maxMessageBytes: 16384,
                   extensions: [["$type": .text(Messages.timeHint), "now": .int(1), "confidence": .int(2)]]),
            .inventory(ids: [id]), .want(requestId: 3, ids: [id]), .begin(transferId: 1, requestId: 3, target: id, length: 10, fragment: [1]),
            .begin(transferId: 2, requestId: 3, target: id, length: 10, fragment: nil), .data(transferId: 1, fragment: [2, 3]),
            .credit(transferId: 1, limit: 10), .result(target: .transfer(side: 1, transferId: 1), code: 0, reason: 0),
            .result(target: .event(side: 0, requestId: 3, id: id), code: 3, reason: 6), .reconcile(id: 0, message: [0x61]), .fetch(requestId: 4, hashes: [id]),
        ]
        for m in all { XCTAssertEqual(try Messages.decode(try Messages.encode(m)), m) }
        XCTAssertEqual(try Messages.decode(try CanonicalCbor.encode(.array([.int(99), .int(1)]))), .unknown(type: 99))
        XCTAssertThrowsError(try Messages.decode(try CanonicalCbor.encode(.array([.int(1), .array([.bytes(id), .bytes(id)])]))))
    }

    /// Two exchanges over an in-memory link: HELLO → RECONCILE → WANT/INVENTORY → BEGIN/DATA/CREDIT → RESULT, both ways,
    /// with a 1 KiB peer message limit and a small initial credit so CREDIT and multi-fragment DATA are exercised.
    func testExchangeLoopbackSyncsBothWays() throws {
        let clock = { self.now }
        let x = MeshEngine(clockConfidence: .trusted), y = MeshEngine(clockConfidence: .trusted)
        let a = try author(3), b = try author(10)
        var repo = RepositoryState()
        for i in 0..<5 {
            let p = try a.createPost(String(repeating: "あ", count: 400) + "\(i)", nowMillis: now + Int64(i), previous: repo)
            repo = p.repository
            try x.originate(p.event, nowMillis: now, elapsedMillis: 0)
        }
        try y.originate(try b.createPost("Bから", nowMillis: now).event, nowMillis: now, elapsedMillis: 0)
        try x.originate(try a.createDeletion(x.entries()[0].event, nowMillis: now, previous: repo).event, nowMillis: now, elapsedMillis: 0)
        let link = Loopback()
        let ex = Exchange(engine: x, now: clock, elapsed: { 0 }, messageLimit: 1024, initialCredit: 512)
        let ey = Exchange(engine: y, now: clock, elapsed: { 0 }, messageLimit: 4096, initialCredit: 0)
        link.ends = [("x", ex), ("y", ey)]
        ex.connected(link, "x", node: nil); ey.connected(link, "y", node: nil)
        link.run()
        XCTAssertEqual(Set(x.entries().map(\.event.id)), Set(y.entries().map(\.event.id)))
        XCTAssertEqual(y.entries().count, 7)
        XCTAssertEqual(x.summary(nowMillis: now, elapsedMillis: 0), y.summary(nowMillis: now, elapsedMillis: 0))
    }

    private func resign(_ a: Author, _ e: NostrEvent, _ edit: (inout [[String]]) -> Void) throws -> NostrEvent {
        var tags = e.tags; edit(&tags)
        let digest = sha256(nostrSigningBytes(e.pubkey, e.createdAt, e.kind, tags, e.content))
        return NostrEvent(id: hex(digest), pubkey: e.pubkey, createdAt: e.createdAt, kind: e.kind, tags: tags, content: e.content,
                          sig: hex(try Secp.signSchnorr(digest, a.keys.nostr)))
    }
}

/// Delivers each queued message to the other end until both sides go quiet.
final class Loopback: Link {
    let name = "loop"
    var ends = [(String, Exchange)]()
    private var queue = [(String, [UInt8])]()
    func start(node: String, listener: LinkListener) {}
    func stop() {}
    func send(_ peer: String, _ message: [UInt8]) -> Bool { queue.append((peer, message)); return true }
    func queued(_ peer: String) -> Int { 0 }
    func disconnect(_ peer: String) { XCTFail("disconnected \(peer)") }
    func synced(_ peer: String) {}
    func run() {
        var steps = 0
        while !queue.isEmpty {
            let (from, message) = queue.removeFirst()
            let (to, ex) = ends.first { $0.0 != from }!
            ex.message(self, to, message)
            steps += 1
            if steps > 10_000 { XCTFail("no convergence"); return }
            if queue.isEmpty { for (h, e) in ends { e.drained(self, h) } }
        }
    }
}
