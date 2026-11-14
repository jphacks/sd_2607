package org.mndt.app.ui.map

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mndt.core.Attest
import org.mndt.core.PublicIngress
import org.mndt.mesh.Hazard
import org.mndt.mesh.HazardKind
import org.mndt.mesh.Jma
import org.mndt.mesh.JmaCode
import org.mndt.mesh.PostExtensions
import org.mndt.mesh.PostView
import org.mndt.mesh.Scale

/** jma §5.3 display replacement and the earthquake zoom thresholds. */
class JmaMapTest {
    private val official = PublicIngress.ChainView(Attest.ChainState.VERIFIED, false, Attest.RevocationState.NOT_REVOKED_AS_KNOWN,
        "org", "org", listOf("import"), null, "", importAuthorized = true)
    private fun hazard(vararg shindo: String, kind: HazardKind? = HazardKind.Earthquake("event", HazardKind.Hypocenter(38_000_000, 142_000_000, null, null))) =
        Hazard("Actual", listOf("Geo"), "Severe", "Past", "Observed", scales = shindo.map { Scale("jp.jma.shindo", it, "observed") }, kind = kind)
    private fun post(id: String, jma: Jma?, chain: PublicIngress.ChainView? = official, hazard: Hazard? = hazard("4")) =
        PostView(id, "k", id, hop = 0, relayAccepted = false, ext = PostExtensions(hazard = hazard, jma = jma), chain = chain)

    private val miyagi = JmaCode("04", "地震情報／都道府県等")
    private val iwate = JmaCode("03", "地震情報／都道府県等")
    private fun quake(time: String, pref: JmaCode? = miyagi, eventId: String = "E1", title: String = "震源・震度に関する情報", status: String = "通常") =
        Jma(true, title, status, "2026-11-11T$time+09:00", eventId, whole = pref == null, pref = pref)
    private fun warning(time: String, area: String? = "040000", title: String = "気象警報・注意報（Ｒ０６）（大雨）") =
        Jma(false, title, "通常", "2026-11-11T$time+09:00", "", whole = true, forecastArea = area)

    @Test fun earthquakeReplacedBySamePrefOrNewerWhole() {
        val old = post("old", quake("09:00:00"))
        val otherPref = post("other", quake("09:05:00", iwate))
        assertEquals(emptySet<String>(), supersededJma(listOf(old, otherPref)))
        assertEquals(setOf("old"), supersededJma(listOf(old, post("new", quake("09:05:00")))))
        assertEquals(setOf("old", "other"), supersededJma(listOf(old, otherPref, post("whole", quake("09:10:00", pref = null)))))
        // Another series: other EventID, title or status.
        assertEquals(emptySet<String>(), supersededJma(listOf(old, post("e2", quake("09:05:00", eventId = "E2")),
            post("t", quake("09:05:00", title = "震度速報")), post("s", quake("09:05:00", status = "訓練")))))
        // An older post never replaces a newer one, whatever the arrival order; an equal time replaces neither.
        assertEquals(setOf("old"), supersededJma(listOf(post("new", quake("09:05:00")), old)))
        assertEquals(emptySet<String>(), supersededJma(listOf(old, post("same", quake("09:00:00")))))
        // Offsets are compared as instants.
        assertEquals(setOf("old"), supersededJma(listOf(old, post("utc", quake("09:00:00").copy(dateTime = "2026-11-11T00:01:00Z")))))
    }

    @Test fun onlyOfficialPostsTakePart() {
        val old = post("old", quake("09:00:00"))
        assertEquals(emptySet<String>(), supersededJma(listOf(old, post("new", quake("09:05:00"), chain = null))))
        assertEquals(emptySet<String>(), supersededJma(listOf(old, post("new", quake("09:05:00"), chain = official.copy(importAuthorized = false)))))
        assertEquals(emptySet<String>(), supersededJma(listOf(post("old", quake("09:00:00"), chain = null), post("new", quake("09:05:00")))))
        assertEquals(emptySet<String>(), supersededJma(listOf(old, post("bad", quake("09:05:00").copy(dateTime = "not a time")))))
    }

    @Test fun r06WarningReplacedByAnyNewerOfTheSameAreaAndGroup() {
        val old = post("old", warning("09:00:00"))
        assertEquals(setOf("old"), supersededJma(listOf(old, post("new", warning("10:00:00")))))
        assertEquals(emptySet<String>(), supersededJma(listOf(old, post("pref", warning("10:00:00", area = "030000")))))
        assertEquals(emptySet<String>(), supersededJma(listOf(old, post("group", warning("10:00:00", title = "気象警報・注意報（Ｒ０６）（土砂）")))))
        // Other weather titles have no fixed series yet (weather §7).
        assertEquals(emptySet<String>(), supersededJma(listOf(post("a", warning("09:00:00", title = "土砂災害警戒情報")),
            post("b", warning("10:00:00", title = "土砂災害警戒情報")))))
        assertEquals(emptySet<String>(), supersededJma(listOf(post("a", warning("09:00:00", area = null)), post("b", warning("10:00:00", area = null)))))
    }

    @Test fun replacedPostsLeaveTheMapOnly() {
        val old = post("old", quake("09:00:00"))
        val new = post("new", quake("09:05:00"))
        assertEquals(listOf("new"), mapPosts(listOf(old, new)).map { it.id })
        // Pinned at the hypocentre.
        assertEquals(38.0 to 142.0, new.mapPoint())
    }

    @Test fun earthquakeZoomFollowsShindo() {
        fun zoom(vararg s: String) = post("p", null, hazard = hazard(*s)).minZoom
        assertEquals(8.0, zoom("1"), 0.0)
        assertEquals(8.0, zoom("2"), 0.0)
        assertEquals(6.0, zoom("3"), 0.0)
        assertEquals(5.0, zoom("4"), 0.0)
        assertEquals(0.0, zoom("5-"), 0.0)
        assertEquals(0.0, zoom("7"), 0.0)
        assertEquals(5.0, zoom("2", "4"), 0.0)          // the highest shindo counts
        assertEquals(0.0, zoom(), 0.0)                  // no shindo scale: as before
        assertEquals(0.0, zoom("unknown"), 0.0)
        // Not an earthquake, or not official: unchanged.
        assertEquals(0.0, post("p", null, hazard = hazard("1", kind = HazardKind.Tsunami())).minZoom, 0.0)
        assertEquals(POST_MIN_ZOOM, post("p", null, chain = null, hazard = hazard("1")).minZoom, 0.0)
        assertEquals(ALERT_ZOOM_STEPS, listOf(0.0, 5.0, 6.0, 8.0))
    }
}
