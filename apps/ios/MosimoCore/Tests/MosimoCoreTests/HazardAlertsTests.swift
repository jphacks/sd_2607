import XCTest
@testable import MosimoCore

/// Which received posts raise a local notification, and how they are folded (same cases as Android).
final class HazardAlertsTests: XCTestCase {
    let now: Int64 = 1_792_195_200
    /// Tokyo station, inside geohash xn76 and the circle below.
    let tokyo = GeoPoint(lat: 35.681, lng: 139.767)
    let osaka = GeoPoint(lat: 34.702, lng: 135.495)
    let circle = Area(regions: [.circle(lat: 35_681_000, lon: 139_767_000, r: 10_000_000)], approximation: false)

    func hazard(status: String = "Actual", severity: String = "Extreme", responses: [String] = [], kind: HazardKind? = nil) -> Hazard {
        Hazard(status: status, category: ["Met"], severity: severity, urgency: "Immediate", certainty: "Likely", responseTypes: responses,
               onset: nil, event: nil, scales: [], glide: nil, kind: kind)
    }
    func post(_ id: String, pubkey: String = "other", age: Int64 = 60, official: Bool = true, hazard h: Hazard? = nil,
              areas: [Area] = [], jma: Jma? = nil) -> HazardAlerts.Candidate {
        var ext = PostExtensions()
        ext.hazard = h ?? hazard()
        ext.areas = areas
        ext.jma = jma
        return HazardAlerts.Candidate(id: id, pubkey: pubkey, createdAt: now - age, official: official, ext: ext)
    }
    func danger(_ id: String, age: Int64 = 60, official: Bool = false, danger: Bool = true, origin: GeoPoint? = nil, areas: [Area] = []) -> HazardAlerts.Candidate {
        var ext = PostExtensions()
        ext.origin = origin.map { Origin(lat: Int64(($0.lat * 1e6).rounded()), lon: Int64(($0.lng * 1e6).rounded())) }
        ext.areas = areas
        return HazardAlerts.Candidate(id: id, pubkey: "other", createdAt: now - age, official: official, danger: danger, ext: ext)
    }
    func due(_ posts: [HazardAlerts.Candidate], here: GeoPoint? = nil, notified: Set<String> = []) -> [String] {
        HazardAlerts.due(posts, me: "me", here: here, nowSeconds: now, notified: notified).map(\.post.id)
    }
    func severities(_ areas: [Area], here: GeoPoint?) -> [String] {
        due(["Extreme", "Severe", "Moderate", "Minor", "Unknown"].map { post($0, hazard: hazard(severity: $0), areas: areas) }, here: here)
    }

    func testCommonConditions() {
        XCTAssertEqual(due([post("a")]), ["a"])
        XCTAssertEqual(due([post("a", pubkey: "me")]), [])
        XCTAssertEqual(due([post("a"), post("b")], notified: ["a"]), ["b"])
        // Official: a user claim, no hazard, not Actual, all-clear
        XCTAssertEqual(due([post("a", official: false)]), [])
        XCTAssertEqual(due([HazardAlerts.Candidate(id: "a", pubkey: "other", createdAt: now, official: true, ext: PostExtensions())]), [])
        XCTAssertEqual(due([post("a", hazard: hazard(status: "Exercise")), post("b", hazard: hazard(status: "Test"))]), [])
        XCTAssertEqual(due([post("a", hazard: hazard(responses: ["Monitor", "AllClear"]))]), [])
        // Six hours old at most; a future clock is not "old"
        XCTAssertEqual(due([post("a", age: 6 * 3600), post("b", age: 6 * 3600 + 1), post("c", age: -600)]), ["c", "a"])
    }

    func testSeverityByArea() {
        let cell = Area(regions: [.geohash("xn76")], approximation: true)
        let partial = Area(regions: [.geohash("xn76")], approximation: false, complete: false)
        // Inside: Extreme, Severe, Moderate.
        XCTAssertEqual(severities([circle], here: tokyo), ["Extreme", "Severe", "Moderate"])
        XCTAssertEqual(severities([cell], here: tokyo), ["Extreme", "Severe", "Moderate"])
        // Outside: none, even Extreme.
        XCTAssertEqual(severities([circle, cell], here: osaka), [])
        // No area, unknown position, or unknown containment: Extreme only.
        XCTAssertEqual(severities([], here: tokyo), ["Extreme"])
        XCTAssertEqual(severities([circle], here: nil), ["Extreme"])
        XCTAssertEqual(severities([partial], here: osaka), ["Extreme"])
        XCTAssertEqual(HazardAlerts.contains([Area(regions: [], approximation: false)], tokyo), nil)
        XCTAssertEqual(HazardAlerts.contains([circle], GeoPoint(lat: 35.75, lng: 139.767)), true) // ~7.6 km north
        XCTAssertEqual(HazardAlerts.contains([circle], GeoPoint(lat: 35.78, lng: 139.767)), false) // ~11 km north
    }

    func testGenres() {
        func genre(_ kind: HazardKind?) -> HazardAlerts.Genre? {
            HazardAlerts.due([post("a", hazard: hazard(kind: kind))], me: nil, here: nil, nowSeconds: now, notified: []).first?.genre
        }
        XCTAssertEqual(genre(.earthquake(kind: "event", hypocenter: nil, magnitude: nil)), .earthquake)
        XCTAssertEqual(genre(.weather(phenomena: ["大雨"])), .weather)
        XCTAssertEqual(genre(.cyclone(.init(name: nil, designation: nil, center: nil, pressure: nil, maxWind: nil, stormRadius: nil))), .weather)
        XCTAssertEqual(genre(.tsunami(maxHeight: nil, arrival: nil)), .otherOfficial)
        XCTAssertEqual(genre(.volcano(phenomenon: "eruption", volcano: nil)), .otherOfficial)
        XCTAssertEqual(genre(nil), .otherOfficial)
    }

    func testSplitByGenreAndPermission() {
        let alerts = HazardAlerts.due([post("q", age: 10, hazard: hazard(kind: .earthquake(kind: "event", hypocenter: nil, magnitude: nil))),
                                       post("o", age: 20)], me: nil, here: nil, nowSeconds: now, notified: [])
        func ids(_ a: [HazardAlerts.Alert]) -> [String] { a.map(\.post.id) }
        let all = Set(HazardAlerts.Genre.allCases), noQuake = all.subtracting([.earthquake])
        var r = HazardAlerts.split(alerts, on: all, permission: .granted)
        XCTAssertEqual([ids(r.show), ids(r.record)], [["q", "o"], ["q", "o"]])
        // A switched-off genre is recorded, not shown.
        r = HazardAlerts.split(alerts, on: noQuake, permission: .granted)
        XCTAssertEqual([ids(r.show), ids(r.record)], [["o"], ["q", "o"]])
        // Refused: recorded only. Still unknown: only switched-off genres are recorded.
        r = HazardAlerts.split(alerts, on: all, permission: .refused)
        XCTAssertEqual([ids(r.show), ids(r.record)], [[], ["q", "o"]])
        r = HazardAlerts.split(alerts, on: noQuake, permission: .unknown)
        XCTAssertEqual([ids(r.show), ids(r.record)], [[], ["q"]])
    }

    func testNearbyDanger() {
        let near = GeoPoint(lat: 35.695, lng: 139.767) // ~1.6 km north
        let far = GeoPoint(lat: 35.705, lng: 139.767) // ~2.7 km north
        XCTAssertEqual(due([danger("a", origin: near)], here: tokyo), ["a"])
        XCTAssertEqual(HazardAlerts.due([danger("a", origin: near)], me: nil, here: tokyo, nowSeconds: now, notified: []).first?.genre, .nearbyDanger)
        XCTAssertEqual(due([danger("a", origin: far)], here: tokyo), [])
        // Area: containing here, or an element's centre within 2 km.
        XCTAssertEqual(due([danger("a", origin: far, areas: [circle])], here: GeoPoint(lat: 35.75, lng: 139.767)), ["a"])
        XCTAssertEqual(due([danger("a", areas: [Area(regions: [.circle(lat: 35_695_000, lon: 139_767_000, r: 100_000)], approximation: false)])], here: tokyo), ["a"])
        XCTAssertEqual(due([danger("a", areas: [Area(regions: [.circle(lat: 35_705_000, lon: 139_767_000, r: 100_000)], approximation: false)])], here: tokyo), [])
        // Unknown position, no position on the post, older than an hour, not 「危険」, official: none.
        XCTAssertEqual(due([danger("a", origin: near)]), [])
        XCTAssertEqual(due([danger("a")], here: tokyo), [])
        XCTAssertEqual(due([danger("a", age: 3600, origin: near), danger("b", age: 3601, origin: near)], here: tokyo), ["a"])
        XCTAssertEqual(due([danger("a", danger: false, origin: near)], here: tokyo), [])
        XCTAssertEqual(due([danger("a", official: true, origin: near)], here: tokyo), [])
        // An official post marked 「危険」 is judged as official only (here: Severe with no area is not due).
        var official = post("b", hazard: hazard(severity: "Severe"))
        official = HazardAlerts.Candidate(id: "b", pubkey: "other", createdAt: official.createdAt, official: true, danger: true,
                                          ext: { var e = official.ext; e.origin = Origin(lat: 35_695_000, lon: 139_767_000); return e }())
        XCTAssertEqual(due([official], here: tokyo), [])
    }

    func testSupersededSeriesIsNotNotified() {
        func quake(_ dateTime: String, official: Bool = true, _ id: String) -> HazardAlerts.Candidate {
            post(id, official: official, jma: Jma(kind: .earthquake, title: "震源・震度に関する情報", status: "通常", dateTime: dateTime, eventID: "e1",
                                                 whole: true, prefCode: nil, forecastAreaCode: nil))
        }
        XCTAssertEqual(due([quake("2026-11-14T01:00:00Z", "old"), quake("2026-11-14T01:05:00Z", "new")]), ["new"])
        // Even when the newer one is already notified, the older stays replaced.
        XCTAssertEqual(due([quake("2026-11-14T01:00:00Z", "old"), quake("2026-11-14T01:05:00Z", "new")], notified: ["new"]), [])
        // An unconfirmed post neither replaces nor is replaced.
        XCTAssertEqual(due([quake("2026-11-14T01:00:00Z", "old"), quake("2026-11-14T01:05:00Z", official: false, "new")]), ["old"])
    }

    func testDecodedAreaMarksUnreadElements() throws {
        let author = try Author(keys: SecretKeys(nostr: key(3), atproto: key(4), plc: key(5)), pdsEndpoint: "https://pds.invalid")
        var area = Extensions.area([.geohash("xn76")])
        area["regions"] = .array((area["regions"]?.array ?? []) + [.map(["shape": .text("polygon")])])
        let p = Extensions.decode(try author.createPost("本文", nowMillis: now * 1000, extensions: [area]).event)
        XCTAssertEqual(p.areas.map(\.complete), [false])
        XCTAssertEqual(Extensions.decode(try author.createPost("本文", nowMillis: now * 1000, extensions: [Extensions.area([.geohash("xn76")])]).event)
            .areas.map(\.complete), [true])
    }

    func testOrder() {
        // Official Extreme, other official, then danger reports; newest first within each.
        XCTAssertEqual(due([danger("d", age: 5, origin: tokyo), post("old", age: 300, hazard: hazard(severity: "Severe"), areas: [circle]),
                            post("new", age: 10, hazard: hazard(severity: "Moderate"), areas: [circle]), post("extreme", age: 900)], here: tokyo),
                       ["extreme", "new", "old", "d"])
    }

    func testFold() {
        let first = HazardAlerts.fold(nil, ["a", "b"], nowMillis: 1_000)
        XCTAssertEqual(first, .init(id: "a", startedAtMillis: 1_000, primary: "a", count: 2))
        let more = HazardAlerts.fold(first, ["c"], nowMillis: 30_999)
        XCTAssertEqual(more, .init(id: "a", startedAtMillis: 1_000, primary: "a", count: 3))
        XCTAssertEqual(more.others, 2)
        XCTAssertEqual(HazardAlerts.fold(more, ["d"], nowMillis: 31_000), .init(id: "d", startedAtMillis: 31_000, primary: "d", count: 1))
    }

    func testPruneAndExcerpt() {
        XCTAssertEqual(HazardAlerts.prune(["a": now - 6 * 3600, "b": now - 6 * 3600 - 1, "c": now + 60], nowSeconds: now), ["a": now - 6 * 3600, "c": now + 60])
        XCTAssertEqual(HazardAlerts.excerpt("一行目\n\n  二行目  \r\n三行目\n四行目"), "一行目\n二行目\n三行目")
        XCTAssertEqual(HazardAlerts.excerpt(""), "")
    }
}
