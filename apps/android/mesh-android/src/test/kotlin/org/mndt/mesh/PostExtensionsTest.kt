package org.mndt.mesh

import org.junit.Assert.*
import org.junit.Test
import org.mndt.core.Author
import org.mndt.core.SecretKeys

class PostExtensionsTest {
    private val author by lazy { Author(SecretKeys.generate(), "https://pds.invalid") }

    @Test fun knownExtensionsRoundTripThroughSignedTags() {
        val origin = Origin(38_260_000, 140_870_000, 12_000)
        val circle = Region.Circle(38_260_000, 140_870_000, 200_000)
        val event = author.createPost("［危険］遊歩道が冠水", 1_792_195_200_000L, extensions = listOf(
            Extensions.origin(origin), Extensions.circle(circle), Extensions.validity(until = "2026-11-02T06:00:00.000Z"),
            Extensions.annotation(Relation.SUPPLEMENT, "a".repeat(64)),
            mapOf("\$type" to "example.unknown.extension", "opaque" to "kept"))).event
        val ext = Extensions.decode(event)
        assertEquals(origin, ext.origin)
        assertEquals(listOf(circle), ext.regions)
        assertEquals("2026-11-02T06:00:00.000Z", ext.validity?.until)
        assertEquals(Annotation(Relation.SUPPLEMENT, "a".repeat(64)), ext.annotation)
        assertEquals(1, ext.priority)
        assertNull(ext.bsaf)
    }

    @Test fun plainPostHasNoExtensions() {
        assertEquals(PostExtensions(), Extensions.decode(author.createPost("本文のみ", 1_792_195_200_000L).event))
    }

    private fun decode(vararg ext: Map<String, Any?>) = Extensions.decode(author.createPost("本文", 1_792_195_200_000L, extensions = ext.toList()).event)
    private val common = mapOf("\$type" to Extensions.HAZARD, "status" to "Actual", "category" to listOf("Geo"), "severity" to "Severe",
        "urgency" to "Past", "certainty" to "Observed")

    @Test fun hazardWithEachKindDecodes() {
        val full = common + mapOf("responseTypes" to listOf("Shelter", "Mystery"), "onset" to "2026-01-01T07:10:00Z",
            "event" to mapOf("text" to "震源・震度情報", "lang" to "ja"), "glide" to "EQ-2026-000001-JPN",
            "scales" to listOf(mapOf("scheme" to "jp.jma.shindo", "value" to "5+", "basis" to "observed"), mapOf("scheme" to 1L)))
        val quake = decode(full, mapOf("\$type" to "${Extensions.HAZARD}.earthquake", "kind" to "event",
            "hypocenter" to mapOf("lat" to 37_500_000L, "lon" to 137_200_000L, "depth" to 10_000_000L, "name" to mapOf("text" to "石川県能登地方", "lang" to "ja")),
            "magnitude" to mapOf("value" to 65L, "type" to "Mj")), Extensions.area(listOf(Region.Geohash("xn76")), approximation = true))
        assertEquals(Hazard("Actual", listOf("Geo"), "Severe", "Past", "Observed", listOf("Shelter", "Mystery"), "2026-01-01T07:10:00Z",
            LocalizedText("震源・震度情報", "ja"), listOf(Scale("jp.jma.shindo", "5+", "observed")), "EQ-2026-000001-JPN",
            HazardKind.Earthquake("event", HazardKind.Hypocenter(37_500_000, 137_200_000, 10_000_000, LocalizedText("石川県能登地方", "ja")),
                HazardKind.Magnitude(65, "Mj"))), quake.hazard)
        assertTrue(quake.areas.single().approximation)
        assertEquals(HazardKind.Tsunami(HazardKind.Height(3000, LocalizedText("3m", "ja"), "forecast"), null),
            decode(common, mapOf("\$type" to "${Extensions.HAZARD}.tsunami", "maxHeight" to mapOf("value" to 3000L, "text" to mapOf("text" to "3m", "lang" to "ja"), "basis" to "forecast"))).hazard?.kind)
        assertEquals(HazardKind.Volcano("eruption", HazardKind.VolcanoInfo(LocalizedText("桜島", "ja"), null, null, null)),
            decode(common, mapOf("\$type" to "${Extensions.HAZARD}.volcano", "phenomenon" to "eruption", "volcano" to mapOf("name" to mapOf("text" to "桜島", "lang" to "ja")))).hazard?.kind)
        assertEquals(HazardKind.Weather(listOf("tornado")), decode(common, mapOf("\$type" to "${Extensions.HAZARD}.weather", "phenomena" to listOf("tornado"))).hazard?.kind)
        assertEquals(HazardKind.Cyclone(LocalizedText("ハギビス", "ja-Kana"), pressure = 97_000, maxWind = 35_000),
            decode(common, mapOf("\$type" to "${Extensions.HAZARD}.cyclone", "name" to mapOf("text" to "ハギビス", "lang" to "ja-Kana"), "pressure" to 97_000L, "maxWind" to 35_000L)).hazard?.kind)
    }

    @Test fun malformedHazardIsIgnoredWithoutHidingOthers() {
        val validity = Extensions.validity(until = "2026-11-02T06:00:00.000Z")
        val noSeverity = decode(common - "severity", validity)
        assertNull(noSeverity.hazard)
        assertEquals("2026-11-02T06:00:00.000Z", noSeverity.validity?.until)
        assertNull(decode(common + ("category" to emptyList<String>())).hazard)
        assertNull(decode(common + ("status" to 1L)).hazard)
        // Two type-specific extensions: neither is used; one alone without the common extension: no hazard.
        val two = decode(common, mapOf("\$type" to "${Extensions.HAZARD}.tsunami"), mapOf("\$type" to "${Extensions.HAZARD}.weather", "phenomena" to listOf("rain")))
        assertNotNull(two.hazard)
        assertNull(two.hazard?.kind)
        assertNull(decode(mapOf("\$type" to "${Extensions.HAZARD}.weather", "phenomena" to listOf("rain"))).hazard)
        // A type-specific extension missing its required field is dropped, the common one kept.
        assertNull(decode(common, mapOf("\$type" to "${Extensions.HAZARD}.earthquake")).hazard!!.kind)
    }

    private fun message(title: String, eventId: String = "20261111085500") = mapOf(
        "control" to mapOf("title" to title, "dateTime" to "2026-11-11T09:01:00+09:00", "status" to "通常"),
        "head" to mapOf("title" to title, "eventID" to eventId, "infoType" to "発表"))

    @Test fun jmaSeriesFieldsDecode() {
        val quake = decode(mapOf("\$type" to "dev.jisaku.mosimo.public.jma.earthquake", "message" to message("震源・震度に関する情報"),
            "selection" to mapOf("kind" to "region", "path" to listOf(1L)),
            "body" to mapOf("observation" to mapOf("maxInt" to "4", "prefs" to listOf(mapOf("name" to "宮城県", "code" to mapOf("value" to "04", "type" to "地震情報／都道府県等"), "maxInt" to "4", "areas" to listOf<Any>()))))))
        assertEquals(Jma(true, "震源・震度に関する情報", "通常", "2026-11-11T09:01:00+09:00", "20261111085500", whole = false,
            pref = JmaCode("04", "地震情報／都道府県等")), quake.jma)
        val warning = decode(mapOf("\$type" to "dev.jisaku.mosimo.public.jma.weather", "message" to message("気象警報・注意報（Ｒ０６）（大雨）", eventId = ""),
            "selection" to mapOf("kind" to "whole"), "headlineItems" to listOf<Any>(), "meteorologicalItems" to listOf<Any>(),
            "warnings" to listOf(
                mapOf("warningIndex" to 1L, "itemIndex" to 0L, "warningType" to "気象警報・注意報（市町村等）", "kinds" to listOf<Any>(), "areas" to listOf(mapOf("name" to "仙台市", "code" to mapOf("value" to "0410000")))),
                mapOf("warningIndex" to 0L, "itemIndex" to 0L, "warningType" to "気象警報・注意報（府県予報区等）", "kinds" to listOf<Any>(), "areas" to listOf(mapOf("name" to "宮城県", "code" to mapOf("value" to "040000")))))))
        assertEquals("040000", warning.jma?.forecastArea)
        assertTrue(warning.jma!!.whole && !warning.jma!!.earthquake && warning.jma!!.pref == null)
        // Two jma extensions, or one without its message, decode to nothing.
        val e = mapOf("\$type" to "dev.jisaku.mosimo.public.jma.earthquake", "message" to message("震度速報"), "selection" to mapOf("kind" to "whole"))
        assertNull(decode(e, e + ("\$type" to "dev.jisaku.mosimo.public.jma.weather")).jma)
        assertNull(decode(e - "message").jma)
    }
}
