package org.mndt.app.alert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mndt.app.ui.map.regionsContain
import org.mndt.app.ui.map.regionsDistanceMeters
import org.mndt.core.Attest
import org.mndt.core.PublicIngress
import org.mndt.mesh.Area
import org.mndt.mesh.Hazard
import org.mndt.mesh.HazardKind
import org.mndt.mesh.Jma
import org.mndt.mesh.JmaCode
import org.mndt.mesh.Origin
import org.mndt.mesh.PostExtensions
import org.mndt.mesh.PostView
import org.mndt.mesh.Region
import org.mndt.mesh.Scale

/** Hazard notification genres and conditions, single alerting and batching. */
class HazardAlertsTest {
    private val official = PublicIngress.ChainView(Attest.ChainState.VERIFIED, false, Attest.RevocationState.NOT_REVOKED_AS_KNOWN,
        "org", "org", listOf("import"), null, "", importAuthorized = true)
    private val now = 1_800_000_000L
    private val sendai = 38.268 to 140.869
    private val tokyo = 35.681 to 139.767
    private fun hazard(severity: String = "Severe", status: String = "Actual", responses: List<String> = emptyList()) =
        Hazard(status, listOf("Met"), severity, "Immediate", "Likely", responseTypes = responses,
            scales = listOf(Scale("jp.jma.warning-level", "5")), kind = HazardKind.Weather(listOf("rain")))
    private fun post(id: String, hazard: Hazard? = hazard(), chain: PublicIngress.ChainView? = official, pubkey: String = "bot",
        createdAt: Long = now - 60, regions: List<Region> = emptyList(), jma: Jma? = null, content: String = "大雨特別警報\n\n宮城県\n仙台市\n石巻市") =
        PostView(id, pubkey, content, hop = 0, relayAccepted = false, createdAt = createdAt,
            ext = PostExtensions(hazard = hazard, jma = jma, areas = if (regions.isEmpty()) emptyList() else listOf(Area(regions, false))), chain = chain)
    private fun alerts(posts: List<PostView>, here: Pair<Double, Double>? = null, handled: Set<String> = emptySet()) =
        alertCandidates(posts, "me", now, here, handled)
    private fun ids(posts: List<PostView>, here: Pair<Double, Double>? = null, handled: Set<String> = emptySet()) =
        alerts(posts, here, handled).map { it.post.id }
    private val miyagi = listOf(Region.Geohash("xnu"))

    @Test fun onlyOthersOfficialActualHazards() {
        assertEquals(listOf("x"), ids(listOf(post("x", hazard("Extreme")))))
        // Our own post.
        assertEquals(emptyList<String>(), ids(listOf(post("a", hazard("Extreme"), pubkey = "me"))))
        // Not official, no hazard, not Actual.
        assertEquals(emptyList<String>(), ids(listOf(post("a", hazard("Extreme"), chain = null),
            post("b", hazard("Extreme"), chain = official.copy(importAuthorized = false)),
            post("c", hazard("Extreme"), chain = official.copy(expiredNow = true)), post("d", hazard = null),
            post("e", hazard("Extreme", status = "Exercise")), post("f", hazard("Extreme", status = "Test")))))
        // publicInformation role counts as official too.
        assertEquals(listOf("p"), ids(listOf(post("p", hazard("Extreme"), chain = official.copy(roles = listOf(Attest.ROLE_PUBLIC_INFORMATION), importAuthorized = false)))))
        // All-clears never.
        assertEquals(emptyList<String>(), ids(listOf(post("a", hazard("Extreme", responses = listOf("Monitor", "AllClear"))))))
        assertEquals(listOf("a"), ids(listOf(post("a", hazard("Extreme", responses = listOf("Evacuate"))))))
    }

    @Test fun severityDependsOnAreaAndFix() {
        // Known fix inside the area: Extreme, Severe, Moderate; not Minor.
        assertEquals(listOf("x", "s", "m"), ids(listOf(post("x", hazard("Extreme"), regions = miyagi), post("s", hazard("Severe"), regions = miyagi),
            post("m", hazard("Moderate"), regions = miyagi), post("n", hazard("Minor"), regions = miyagi)), here = sendai))
        // Known fix outside: nothing, even Extreme.
        assertEquals(emptyList<String>(), ids(listOf(post("x", hazard("Extreme"), regions = miyagi), post("s", hazard("Severe"), regions = miyagi)), here = tokyo))
        // No area: Extreme only.
        assertEquals(listOf("x"), ids(listOf(post("x", hazard("Extreme")), post("s", hazard("Severe")), post("m", hazard("Moderate"))), here = sendai))
        // Unknown fix: Extreme only.
        assertEquals(listOf("x"), ids(listOf(post("x", hazard("Extreme"), regions = miyagi), post("s", hazard("Severe"), regions = miyagi),
            post("m", hazard("Moderate"), regions = miyagi)), here = null))
        // A circle area.
        assertEquals(listOf("c"), ids(listOf(post("c", hazard("Moderate"), regions = listOf(Region.Circle(38_268_000, 140_869_000, 5_000_000)))), here = sendai))
    }

    @Test fun genresFollowTheKindExtension() {
        fun genre(kind: HazardKind?) = alerts(listOf(post("a", hazard("Extreme").copy(kind = kind)))).single().genre
        assertEquals(AlertGenre.EARTHQUAKE, genre(HazardKind.Earthquake("event")))
        assertEquals(AlertGenre.WEATHER, genre(HazardKind.Weather(listOf("rain"))))
        assertEquals(AlertGenre.WEATHER, genre(HazardKind.Cyclone()))
        assertEquals(AlertGenre.OTHER_OFFICIAL, genre(HazardKind.Tsunami()))
        assertEquals(AlertGenre.OTHER_OFFICIAL, genre(HazardKind.Volcano("eruption")))
        assertEquals(AlertGenre.OTHER_OFFICIAL, genre(null))
    }

    @Test fun replacedPostsAreNotAlerted() {
        fun quake(time: String) = Jma(true, "震源・震度に関する情報", "通常", "2026-11-11T$time+09:00", "E1", whole = false, pref = JmaCode("04", "地震情報／都道府県等"))
        val old = post("old", hazard("Extreme"), jma = quake("09:00:00"))
        val new = post("new", hazard("Extreme"), jma = quake("09:05:00"))
        assertEquals(listOf("new"), ids(listOf(old, new)))
        // A replacement already alerted still replaces the older one.
        assertEquals(emptyList<String>(), ids(listOf(old, new), handled = setOf("new")))
    }

    @Test fun onlyRecentOfficialPosts() {
        assertEquals(listOf("a"), ids(listOf(post("a", hazard("Extreme"), createdAt = now - ALERT_WINDOW_SECONDS))))
        assertEquals(emptyList<String>(), ids(listOf(post("a", hazard("Extreme"), createdAt = now - ALERT_WINDOW_SECONDS - 1))))
    }

    @Test fun nearbyDangerReports() {
        fun report(id: String, origin: Pair<Double, Double>? = null, regions: List<Region> = emptyList(), text: String = "［危険］川が溢れそう",
            createdAt: Long = now - 60, chain: PublicIngress.ChainView? = null, hazard: Hazard? = null) =
            post(id, hazard, chain = chain, createdAt = createdAt, regions = regions, content = text).let { p ->
                p.copy(ext = p.ext.copy(origin = origin?.let { Origin((it.first * 1e6).toLong(), (it.second * 1e6).toLong()) }))
            }
        val near = 38.285 to 140.869 // about 1.9 km north of the fix
        val far = 38.290 to 140.869 // about 2.4 km
        val reports = alerts(listOf(report("o", origin = near)), here = sendai)
        assertEquals(listOf("o" to AlertGenre.NEARBY_DANGER), reports.map { it.post.id to it.genre })
        assertEquals(emptyList<String>(), ids(listOf(report("o", origin = far)), here = sendai))
        // An area counts by its nearest edge: a 1 km circle 2.9 km away is 1.9 km from its edge.
        assertEquals(listOf("c"), ids(listOf(report("c", regions = listOf(Region.Circle(38_294_000, 140_869_000, 1_000_000)))), here = sendai))
        assertEquals(listOf("g"), ids(listOf(report("g", regions = miyagi)), here = sendai))
        assertEquals(emptyList<String>(), ids(listOf(report("g", regions = listOf(Region.Geohash("xn7")))), here = sendai))
        // Unknown fix, no position, other categories, older than an hour, our own: never.
        assertEquals(emptyList<String>(), ids(listOf(report("o", origin = near)), here = null))
        assertEquals(emptyList<String>(), ids(listOf(report("n")), here = sendai))
        assertEquals(emptyList<String>(), ids(listOf(report("s", origin = near, text = "［注意］路面凍結"), report("t", origin = near, text = "川が溢れそう")), here = sendai))
        assertEquals(listOf("h"), ids(listOf(report("h", origin = near, createdAt = now - NEARBY_WINDOW_SECONDS)), here = sendai))
        assertEquals(emptyList<String>(), ids(listOf(report("h", origin = near, createdAt = now - NEARBY_WINDOW_SECONDS - 1)), here = sendai))
        assertEquals(emptyList<String>(), alertCandidates(listOf(report("o", origin = near)), "bot", now, sendai, emptySet()))
        // An official hazard is never a user's report, even with the 「［危険］」 prefix (it follows the official rules).
        assertEquals(emptyList<String>(), ids(listOf(report("f", origin = near, chain = official, hazard = hazard("Severe"))), here = sendai))
        // A user's own hazard claim is still a user's report.
        assertEquals(listOf("u"), ids(listOf(report("u", origin = near, hazard = hazard("Minor"))), here = sendai))
    }

    @Test fun regionContainmentAndDistance() {
        assertTrue(regionsContain(listOf(Region.Geohash("xn7"), Region.Geohash("xnu")), sendai.first, sendai.second))
        assertFalse(regionsContain(listOf(Region.Geohash("xn7")), sendai.first, sendai.second))
        // 5 km circle: about 4.4 km north is inside, 5.6 km is not.
        val c = Region.Circle(38_268_000, 140_869_000, 5_000_000)
        assertTrue(regionsContain(listOf(c), 38.308, 140.869))
        assertFalse(regionsContain(listOf(c), 38.318, 140.869))
        assertEquals(0.0, regionsDistanceMeters(listOf(c), 38.308, 140.869)!!, 0.0)
        assertEquals(556.0, regionsDistanceMeters(listOf(c), 38.318, 140.869)!!, 5.0)
        assertNull(regionsDistanceMeters(emptyList(), 38.0, 140.0))
    }

    @Test fun handledPostsAlertOnce() {
        val a = post("a", hazard("Extreme"))
        assertEquals(emptyList<String>(), ids(listOf(a), handled = setOf("a")))
        val entries = setOf(handledEntry(a), handledEntry(post("old", createdAt = now - ALERT_WINDOW_SECONDS - 10)), "garbage")
        val kept = pruneHandled(entries, now)
        assertEquals(setOf(handledEntry(a)), kept)
        assertEquals(setOf("a"), handledIds(kept))
    }

    @Test fun alertsWithin30SecondsFoldIntoOne() {
        val start = now * 1000
        fun alert(id: String, severity: String = "Severe") = Alert(post(id, hazard(severity)), AlertGenre.WEATHER)
        val danger = Alert(post("d", hazard = null, content = "［危険］川が溢れそう\n橋の付近"), AlertGenre.NEARBY_DANGER)
        assertNull(nextBatch(null, emptyList(), start))
        val first = nextBatch(null, listOf(danger, alert("a"), alert("x", "Extreme")), start)!!
        // Headed by the most severe official notice; the others are counted.
        assertEquals("x", first.head.post.id)
        assertEquals(2, first.more)
        val grown = nextBatch(first, listOf(alert("b")), start + ALERT_BATCH_MILLIS - 1)!!
        assertEquals(first.notificationId, grown.notificationId)
        assertEquals(3, grown.more)
        assertEquals("大雨 警戒レベル5 ほか3件", grown.title())
        val later = nextBatch(grown, listOf(alert("c")), start + ALERT_BATCH_MILLIS)!!
        assertTrue(later.notificationId != grown.notificationId)
        assertEquals("c", later.head.post.id)
        assertEquals(0, later.more)
        assertEquals("大雨 警戒レベル5", later.title())
        assertEquals("大雨特別警報\n宮城県\n仙台市", later.text())
        // A user's report says so in the title.
        val report = nextBatch(null, listOf(danger), start)!!
        assertEquals("近くの危険情報（利用者の投稿）", report.title())
        assertEquals("［危険］川が溢れそう\n橋の付近", report.text())
    }
}
