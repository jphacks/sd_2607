import XCTest
@testable import MosimoCore

/// hazard and hazard.* decoding (tolerant) and the Japanese labels.
final class HazardTests: XCTestCase {
    let now: Int64 = 1_792_195_200_000
    let author = try! Author(keys: SecretKeys(nostr: key(3), atproto: key(4), plc: key(5)), pdsEndpoint: "https://pds.invalid")

    func common(_ extra: [String: Cbor] = [:]) -> [String: Cbor] {
        ["$type": .text(Extensions.hazard), "status": .text("Actual"), "category": .array([.text("Met")]), "severity": .text("Severe"),
         "urgency": .text("Immediate"), "certainty": .text("Likely")].merging(extra) { $1 }
    }
    func kind(_ name: String, _ fields: [String: Cbor]) -> [String: Cbor] { fields.merging(["$type": .text("\(Extensions.hazard).\(name)")]) { $1 } }
    func decode(_ ext: [[String: Cbor]]) throws -> PostExtensions { Extensions.decode(try author.createPost("本文", nowMillis: now, extensions: ext).event) }
    func text(_ t: String, _ lang: String = "ja") -> Cbor { .map(["text": .text(t), "lang": .text(lang)]) }

    func testDecodesFullHazardWithEarthquake() throws {
        let full = common([
            "category": .array([.text("Geo")]), "severity": .text("Extreme"), "urgency": .text("Past"), "certainty": .text("Observed"),
            "responseTypes": .array([.text("Evacuate"), .text("Avoid")]), "onset": .text("2026-10-18T03:00:00Z"),
            "event": text("震源・震度に関する情報"), "glide": .text("EQ-2026-000001-JPN"),
            "scales": .array([.map(["scheme": .text("jp.jma.shindo"), "value": .text("6+"), "basis": .text("observed")]), .map(["scheme": .int(1)])]),
        ])
        let eq = kind("earthquake", ["kind": .text("event"),
                                     "hypocenter": .map(["lat": .int(37_500_000), "lon": .int(137_200_000), "depth": .int(10_000_000), "name": text("石川県能登地方")]),
                                     "magnitude": .map(["value": .int(65), "type": .text("Mj")])])
        let p = try decode([full, eq, Extensions.validity(until: "2026-10-19T00:00:00Z")])
        let h = try XCTUnwrap(p.hazard)
        XCTAssertEqual(h.status, "Actual")
        XCTAssertEqual(h.category, ["Geo"])
        XCTAssertEqual(h.responseTypes, ["Evacuate", "Avoid"])
        XCTAssertEqual(h.onset, "2026-10-18T03:00:00Z")
        XCTAssertEqual(h.event, LocalizedText(text: "震源・震度に関する情報", lang: "ja"))
        XCTAssertEqual(h.scales, [HazardScale(scheme: "jp.jma.shindo", value: "6+", basis: "observed")]) // malformed scale skipped
        XCTAssertEqual(h.glide, "EQ-2026-000001-JPN")
        XCTAssertEqual(h.kind, .earthquake(kind: "event", hypocenter: .init(lat: 37_500_000, lon: 137_200_000, depth: 10_000_000, name: LocalizedText(text: "石川県能登地方", lang: "ja")),
                                           magnitude: .init(value: 65, type: "Mj")))
        XCTAssertNotNil(p.validity)
        XCTAssertEqual(HazardLabels.title(h), "地震")
        XCTAssertEqual(HazardLabels.earthquakeSummary(h), "M6.5（Mj）・深さ約10km・震源 石川県能登地方")
        XCTAssertEqual(HazardLabels.scale(h.scales[0]), "震度6強（観測）")
        XCTAssertEqual(HazardLabels.responses(h.responseTypes), "避難・近づかない")
    }

    func testDecodesEachTypeSpecificExtension() throws {
        let tsunami = try decode([common(), kind("tsunami", ["maxHeight": .map(["value": .int(3000), "text": text("3m"), "basis": .text("forecast")]),
                                                             "arrival": .text("2026-10-18T03:10:00Z")])]).hazard
        XCTAssertEqual(tsunami?.kind, .tsunami(maxHeight: .init(value: 3000, text: LocalizedText(text: "3m", lang: "ja"), basis: "forecast"), arrival: "2026-10-18T03:10:00Z"))
        let volcano = try decode([common(), kind("volcano", ["phenomenon": .text("eruption"), "volcano": .map(["name": text("桜島"), "gvp": .text("282080")])])]).hazard
        XCTAssertEqual(volcano?.kind, .volcano(phenomenon: "eruption", volcano: .init(name: LocalizedText(text: "桜島", lang: "ja"), gvp: "282080", lat: nil, lon: nil)))
        let weather = try decode([common(), kind("weather", ["phenomena": .array([.text("wind"), .text("snow"), .text("future-thing")])])]).hazard
        XCTAssertEqual(weather?.kind, .weather(phenomena: ["wind", "snow", "future-thing"]))
        let cyclone = try decode([common(), kind("cyclone", ["name": text("ハギビス", "ja-Kana"), "center": .map(["lat": .int(20_000_000), "lon": .int(135_000_000)]),
                                                             "pressure": .int(97_000), "maxWind": .int(35_000), "stormRadius": .int(150_000_000)])]).hazard
        XCTAssertEqual(cyclone?.kind, .cyclone(.init(name: LocalizedText(text: "ハギビス", lang: "ja-Kana"), designation: nil, center: .init(lat: 20_000_000, lon: 135_000_000),
                                                     pressure: 97_000, maxWind: 35_000, stormRadius: 150_000_000)))
        XCTAssertEqual(HazardLabels.pressure(97_000), "中心気圧 970hPa")
        XCTAssertEqual(HazardLabels.wind(35_000), "最大風速 35m/s")
    }

    func testToleratesMalformedHazard() throws {
        // A missing required field drops the hazard only; other extensions survive.
        var noSeverity = common(); noSeverity["severity"] = nil
        let p = try decode([noSeverity, Extensions.validity(until: "2026-10-19T00:00:00Z")])
        XCTAssertNil(p.hazard)
        XCTAssertNotNil(p.validity)
        XCTAssertNil(try decode([common(["category": .array([])])]).hazard)
        XCTAssertNil(try decode([common(["urgency": .int(1)])]).hazard)
        // Two type-specific extensions: the hazard stays, the kind does not.
        let two = try decode([common(), kind("weather", ["phenomena": .array([.text("rain")])]), kind("tsunami", [:])])
        XCTAssertNotNil(two.hazard)
        XCTAssertNil(two.hazard?.kind)
        // A malformed type-specific extension and a type-specific one without the common one.
        XCTAssertNil(try decode([common(), kind("earthquake", ["magnitude": .map(["value": .int(65)])])]).hazard?.kind)
        XCTAssertNil(try decode([kind("weather", ["phenomena": .array([.text("rain")])])]).hazard)
        // Unknown enum values stay raw and read as 不明.
        let odd = try XCTUnwrap(try decode([common(["severity": .text("Catastrophic"), "category": .array([.text("Space"), .text("Fire")])])]).hazard)
        XCTAssertEqual(odd.severity, "Catastrophic")
        XCTAssertEqual(HazardLabels.severity(odd.severity), "不明")
        XCTAssertEqual(HazardLabels.kind(odd), "火災")
    }

    func testLabels() throws {
        func h(_ kind: HazardKind?, category: [String] = ["Met"], responses: [String] = []) -> Hazard {
            Hazard(status: "Actual", category: category, severity: "Minor", urgency: "Past", certainty: "Observed", responseTypes: responses,
                   onset: nil, event: nil, scales: [], glide: nil, kind: kind)
        }
        XCTAssertEqual(HazardLabels.kind(h(.earthquake(kind: "early-warning", hypocenter: nil, magnitude: nil))), "緊急地震速報")
        XCTAssertEqual(HazardLabels.kind(h(.earthquake(kind: "assessment", hypocenter: nil, magnitude: nil))), "地震の評価")
        XCTAssertEqual(HazardLabels.kind(h(.volcano(phenomenon: "ashfall", volcano: nil))), "降灰")
        XCTAssertEqual(HazardLabels.kind(h(.volcano(phenomenon: "new", volcano: nil))), "火山")
        XCTAssertEqual(HazardLabels.kind(h(.weather(phenomena: ["wind", "snow"]))), "暴風・大雪")
        XCTAssertEqual(HazardLabels.kind(h(.weather(phenomena: ["unknown"]))), "気象")
        XCTAssertEqual(HazardLabels.kind(h(.cyclone(.init(name: nil, designation: nil, center: nil, pressure: nil, maxWind: nil, stormRadius: nil)))), "台風・熱帯低気圧")
        XCTAssertEqual(HazardLabels.kind(h(nil, category: ["Unknown"])), "災害情報")
        XCTAssertEqual(HazardLabels.title(h(.weather(phenomena: ["rain"]), responses: ["AllClear"])), "大雨 解除")
        XCTAssertEqual(HazardLabels.status("Exercise"), "訓練")
        XCTAssertEqual(HazardLabels.status("Test"), "試験")
        XCTAssertNil(HazardLabels.status("Actual"))

        func scale(_ scheme: String, _ value: String, _ basis: String? = nil) -> String { HazardLabels.scale(HazardScale(scheme: scheme, value: value, basis: basis)) }
        XCTAssertEqual(scale("jp.jma.shindo", "5-", "forecast"), "震度5弱（予測）")
        XCTAssertEqual(scale("jp.jma.shindo", "4"), "震度4")
        XCTAssertEqual(scale("jp.jma.warning-level", "4"), "警戒レベル4")
        XCTAssertEqual(scale("jp.jma.warning-class", "special-warning"), "特別警報級")
        XCTAssertEqual(scale("jp.jma.tsunami", "major-warning"), "大津波警報")
        XCTAssertEqual(scale("jp.jma.volcanic-alert-level", "3"), "噴火警戒レベル3")
        XCTAssertEqual(scale("jp.jma.typhoon-intensity", "very-strong"), "非常に強い")
        XCTAssertEqual(scale("jp.jma.nankai-trough", "alert"), "巨大地震警戒")
        XCTAssertEqual(scale("intl.mmi", "VII"), "MMI VII")
        XCTAssertEqual(scale("intl.saffir-simpson", "5"), "カテゴリー5")
        XCTAssertEqual(scale("intl.meteoalarm.awareness-level", "orange"), "警戒色 橙")
        XCTAssertEqual(scale("org.example.flood-stage", "major"), "org.example.flood-stage major")
        XCTAssertEqual(scale("jp.jma.shindo", "8"), "jp.jma.shindo 8")

        XCTAssertEqual(HazardLabels.magnitude(.init(value: 70, type: nil)), "M7.0")
        XCTAssertEqual(HazardLabels.depth(10_000_000), "深さ約10km")
        XCTAssertEqual(HazardLabels.depth(500_000), "深さ1km未満")
        XCTAssertEqual(HazardLabels.tsunamiHeight(.init(value: 3000, text: nil, basis: "forecast")), "最大の高さ 3m（予測）")
        XCTAssertEqual(HazardLabels.tsunamiHeight(.init(value: 10000, text: LocalizedText(text: "10m超", lang: "ja"), basis: nil)), "最大の高さ 10m超")
        XCTAssertEqual(HazardLabels.tsunamiHeight(.init(value: 1500, text: nil, basis: "observed")), "最大の高さ 1.5m（観測）")
        XCTAssertNil(HazardLabels.earthquakeSummary(h(.earthquake(kind: "event", hypocenter: nil, magnitude: nil))))
    }
}
