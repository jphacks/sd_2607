package org.mndt.app.alert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mndt.app.ui.post.originalSource
import org.mndt.app.ui.post.originalUrl
import org.mndt.core.Attest
import org.mndt.core.PublicIngress
import org.mndt.mesh.Area
import org.mndt.mesh.Bsaf
import org.mndt.mesh.FeedRef
import org.mndt.mesh.Hazard
import org.mndt.mesh.HazardKind
import org.mndt.mesh.PostExtensions
import org.mndt.mesh.PostView
import org.mndt.mesh.Region
import org.mndt.mesh.SourceLink

/** public §8.1: posts of one event group into one item for the map, lists and notifications. */
class HazardGroupsTest {
    private val authorized = PublicIngress.ChainView(Attest.ChainState.VERIFIED, false, Attest.RevocationState.NOT_REVOKED_AS_KNOWN,
        "Oriom Cluster", "org:oriom", listOf("import"), null, "", importAuthorized = true)
    private val now = 1_800_000_000L
    private val sendai = 38.268 to 140.869
    private val onset = "2026-11-11T08:55:00+09:00"
    private val botDid = "did:plc:vwi7zhfyamyvrg5v6ycjgzxi"
    private fun quake(severity: String = "Severe", onset: String? = this.onset, kind: String = "event", status: String = "Actual", responses: List<String> = emptyList()) =
        Hazard(status, listOf("Geo"), severity, "Immediate", "Observed", responseTypes = responses, onset = onset, kind = HazardKind.Earthquake(kind))
    private fun rain(severity: String = "Severe", phenomena: List<String> = listOf("rain")) =
        Hazard("Actual", listOf("Met"), severity, "Immediate", "Likely", kind = HazardKind.Weather(phenomena))
    private val bsaf = Bsaf("earthquake", null, null, "jma", listOf("bsaf:v1", "type:earthquake", "source:jma"), imported = true, botDid = botDid,
        originalUri = "at://$botDid/app.bsky.feed.post/3lxyzabc")
    private val feed = FeedRef(PublicIngress.FEED_JMA, null, "震源・震度に関する情報")
    private fun post(id: String, hazard: Hazard, bot: Boolean, createdAt: Long, cells: List<String>, chain: PublicIngress.ChainView?) =
        PostView(id, "importer", "地震情報", hop = 0, relayAccepted = false, createdAt = createdAt,
            ext = PostExtensions(hazard = hazard, bsaf = bsaf.takeIf { bot }, feed = feed.takeUnless { bot },
                areas = if (cells.isEmpty()) emptyList() else listOf(Area(cells.map { Region.Geohash(it) }, false))), chain = chain)
    private fun bot(id: String = "a", hazard: Hazard = quake(), createdAt: Long = now - 60, cells: List<String> = listOf("xnu"), chain: PublicIngress.ChainView? = authorized) =
        post(id, hazard, true, createdAt, cells, chain)
    private fun fed(id: String = "p", hazard: Hazard = quake(), createdAt: Long = now - 60, cells: List<String> = listOf("xnu"), chain: PublicIngress.ChainView? = authorized) =
        post(id, hazard, false, createdAt, cells, chain)
    private fun ids(groups: List<HazardGroup>) = groups.map { g -> g.representative.id to g.members.map { it.id } }

    @Test fun earthquakesGroupByOnsetWhateverTheRoute() {
        assertTrue(related(bot(), fed()))
        assertTrue(related(fed(), bot()))
        // Region posts of the same feed group too.
        assertTrue(related(fed("p1", cells = listOf("xnu")), fed("p2", cells = listOf("xn7"))))
        assertTrue(related(bot(hazard = quake(onset = "2026-11-11T08:56:00+09:00")), fed()))
        assertFalse(related(bot(hazard = quake(onset = "2026-11-11T08:56:01+09:00")), fed()))
        assertTrue(related(bot(hazard = quake(onset = "2026-11-10T23:55:30Z")), fed()))
        assertFalse(related(bot(hazard = quake(onset = null)), fed()))
        assertFalse(related(bot(hazard = quake(kind = "hypocenter")), fed(hazard = quake(kind = "hypocenter"))))
    }

    @Test fun commonConditions() {
        // Same status and AllClear membership; severity does not matter.
        assertFalse(related(bot(hazard = quake(status = "Exercise")), fed()))
        assertTrue(related(bot(hazard = quake("Extreme")), fed(hazard = quake("Minor"))))
        assertFalse(related(bot(hazard = quake(responses = listOf("AllClear"))), fed()))
        assertTrue(related(bot(hazard = quake(responses = listOf("AllClear"))), fed(hazard = quake(responses = listOf("Monitor", "AllClear")))))
        // Only authorized re-posts now.
        assertFalse(related(bot(chain = authorized.copy(importAuthorized = false)), fed()))
        assertFalse(related(bot(), fed(chain = authorized.copy(expiredNow = true))))
        assertFalse(related(bot(), fed(chain = null)))
        assertFalse(related(bot(hazard = rain()), fed()))
    }

    @Test fun weatherGroupsAFeedAndABotRePost() {
        assertTrue(related(bot(hazard = rain(phenomena = listOf("rain", "landslide"))), fed(hazard = rain(phenomena = listOf("landslide")))))
        assertFalse(related(bot(hazard = rain(phenomena = listOf("wind"))), fed(hazard = rain())))
        assertTrue(related(bot(hazard = rain(), createdAt = now - GROUP_CREATED_SECONDS), fed(hazard = rain(), createdAt = now)))
        assertFalse(related(bot(hazard = rain(), createdAt = now - GROUP_CREATED_SECONDS - 1), fed(hazard = rain(), createdAt = now)))
        assertTrue(related(bot(hazard = rain(), cells = listOf("xn7", "xnu")), fed(hazard = rain(), cells = listOf("xnuv"))))
        assertTrue(related(bot(hazard = rain(), cells = listOf("xnuvq")), fed(hazard = rain(), cells = listOf("xn"))))
        assertFalse(related(bot(hazard = rain(), cells = listOf("xn7")), fed(hazard = rain(), cells = listOf("xnu"))))
        assertFalse(related(bot(hazard = rain(), cells = emptyList()), fed(hazard = rain())))
        // Two posts of the same route never group as weather (different regions of one feed).
        assertFalse(related(fed("p1", hazard = rain()), fed("p2", hazard = rain())))
        assertFalse(related(bot("a1", hazard = rain()), bot("a2", hazard = rain())))
    }

    @Test fun groupsAreConnectedComponentsWithARepresentative() {
        // p1–a within 60 s, a–p2 within 60 s, p1–p2 not: one group through a.
        val p1 = fed("p1", quake("Severe", "2026-11-11T08:54:29+09:00"))
        val a = bot("a", quake("Extreme", "2026-11-11T08:55:00+09:00"))
        val p2 = fed("p2", quake("Severe", "2026-11-11T08:55:31+09:00"), createdAt = now)
        val other = fed("x", quake("Severe", "2026-11-11T10:00:00+09:00"))
        // The most severe represents, even a Bot re-post; members list feed re-posts first.
        assertEquals(listOf("a" to listOf("p2", "p1", "a")), ids(hazardGroups(listOf(p1, a, p2, other))))
        // Same severity: the feed re-post, then the newest.
        val b = bot("a", quake("Severe"))
        assertEquals(listOf("p2" to listOf("p2", "p1", "a")), ids(hazardGroups(listOf(p1, b, p2))))
        assertEquals(setOf("p1", "a"), groupedAway(hazardGroups(listOf(p1, b, p2))))
        assertEquals(emptyList<HazardGroup>(), hazardGroups(listOf(other, bot(chain = null))))
    }

    @Test fun groupedPostsAreNotifiedOnce() {
        val alerts = { posts: List<PostView>, handled: Set<String> ->
            alertCandidates(posts, "me", now, sendai, handled).map { it.post.id to it.silent } }
        // Arriving together: the representative rings, the rest are recorded silently.
        assertEquals(listOf("a" to true, "p" to false), alerts(listOf(bot(), fed()), emptySet()))
        // The Bot re-post rang first: the feed re-post at most as severe is silent.
        assertEquals(listOf("p" to true), alerts(listOf(bot(), fed()), setOf("a")))
        // More severe than what rang: it rings.
        assertEquals(listOf("p" to false), alerts(listOf(bot(hazard = quake("Moderate")), fed(hazard = quake("Extreme"))), setOf("a")))
        // The feed re-post rang first: a later Bot re-post of the same event is silent.
        assertEquals(listOf("a" to true), alerts(listOf(bot(), fed()), setOf("p")))
        // Unrelated events both ring.
        assertEquals(listOf("a" to false, "p" to false), alerts(listOf(bot(hazard = quake(onset = "2026-11-11T10:00:00+09:00")), fed()), emptySet()))
    }

    @Test fun originalSourceAndLinks() {
        assertEquals("気象庁", fed().originalSource())
        assertEquals("気象庁", bot().originalSource())
        assertEquals("org.example.alerts.cap（urn:oid:2.49.0.1.840.0）",
            fed().let { it.copy(ext = it.ext.copy(feed = FeedRef("org.example.alerts.cap", "urn:oid:2.49.0.1.840.0", "Flood"))) }.originalSource())
        assertEquals("kikikuru", bot().let { it.copy(ext = it.ext.copy(bsaf = bsaf.copy(source = "kikikuru"))) }.originalSource())
        assertEquals("https://bsky.app/profile/$botDid/post/3lxyzabc", bot().originalUrl())
        // A feed re-post opens its first web link, an HTML page first; none means no link.
        assertNull(fed().originalUrl())
        val links = listOf(SourceLink("urn:x", null), SourceLink("https://example.org/a.pdf", "application/pdf"),
            SourceLink("https://www.jma.go.jp/bosai/map.html#contents=earthquake_map", "text/html"))
        assertEquals("https://www.jma.go.jp/bosai/map.html#contents=earthquake_map", fed().let { it.copy(ext = it.ext.copy(links = links)) }.originalUrl())
        assertEquals("https://example.org/a.pdf", fed().let { it.copy(ext = it.ext.copy(links = links.take(2))) }.originalUrl())
    }
}
