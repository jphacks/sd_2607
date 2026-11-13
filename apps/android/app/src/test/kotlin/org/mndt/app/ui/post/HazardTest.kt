package org.mndt.app.ui.post

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mndt.core.Attest
import org.mndt.core.PublicIngress
import org.mndt.app.ui.theme.Mosimo
import org.mndt.mesh.Bsaf
import org.mndt.mesh.Hazard
import org.mndt.mesh.HazardKind
import org.mndt.mesh.LocalizedText
import org.mndt.mesh.PostExtensions
import org.mndt.mesh.PostView
import org.mndt.mesh.Scale

class HazardTest {
    private fun hazard(kind: HazardKind? = null, category: List<String> = listOf("Met"), responseTypes: List<String> = emptyList(),
        status: String = "Actual", severity: String = "Severe") =
        Hazard(status, category, severity, "Expected", "Likely", responseTypes, kind = kind)

    @Test fun kindLabels() {
        assertEquals("緊急地震速報", hazard(HazardKind.Earthquake("early-warning")).kindLabel())
        assertEquals("津波", hazard(HazardKind.Tsunami()).kindLabel())
        assertEquals("降灰", hazard(HazardKind.Volcano("ashfall")).kindLabel())
        assertEquals("火山", hazard(HazardKind.Volcano("glowing")).kindLabel())
        assertEquals("大雨・竜巻", hazard(HazardKind.Weather(listOf("rain", "sleet", "tornado"))).kindLabel())
        assertEquals("気象", hazard(HazardKind.Weather(listOf("sleet"))).kindLabel())
        assertEquals("台風・熱帯低気圧", hazard(HazardKind.Cyclone()).kindLabel())
        assertEquals("火災", hazard(category = listOf("Wildfire", "Fire")).kindLabel())
        assertEquals("災害情報", hazard(category = listOf("Wildfire")).kindLabel())
    }

    @Test fun allClearAndStatusAreAlwaysInTheTitle() {
        val cleared = hazard(HazardKind.Weather(listOf("rain")), responseTypes = listOf("AllClear"), severity = "Minor")
        assertEquals("大雨 解除", cleared.title())
        assertEquals(Mosimo.safe, cleared.color(true))
        assertEquals("訓練 津波", hazard(HazardKind.Tsunami(), status = "Exercise").title())
        assertEquals("試験 気象", hazard(status = "Test").title())
        assertEquals("避難・解除", hazard(responseTypes = listOf("Evacuate", "Bogus", "AllClear")).actions())
    }

    @Test fun userClaimsNeverGetAlertColors() {
        assertEquals(Mosimo.extreme, hazard(severity = "Extreme").color(true))
        assertEquals(Mosimo.textSecondary, hazard(severity = "Extreme").color(false))
        assertEquals(Mosimo.textSecondary, hazard(severity = "Catastrophic").color(true))
        assertEquals("不明", severityLabel("Catastrophic"))
        val post = PostView("p", "k", "x", hop = 0, relayAccepted = false, ext = PostExtensions(hazard = hazard()))
        assertEquals(false, post.officialHazard)
        // Decided by the post's own chain, not by how it arrived: an import record alone is not enough.
        assertEquals(false, post.copy(publicAuthorization = "receipt").officialHazard)
        fun chain(confirmed: Boolean, imported: Boolean, roles: List<String> = listOf("import")) = PublicIngress.ChainView(
            if (confirmed) Attest.ChainState.VERIFIED else Attest.ChainState.UNKNOWN_TIME, false, Attest.RevocationState.NOT_REVOKED_AS_KNOWN,
            "org", "org", roles, null, "", imported)
        assertEquals(true, post.copy(chain = chain(confirmed = true, imported = true)).officialHazard)
        assertEquals(false, post.copy(chain = chain(confirmed = false, imported = true)).officialHazard)
        assertEquals(false, post.copy(chain = chain(confirmed = true, imported = false)).officialHazard)
        assertEquals(true, post.copy(chain = chain(confirmed = true, imported = false, roles = listOf("public-information"))).officialHazard)
    }

    @Test fun scales() {
        assertEquals("震度5強（観測）", scaleLabel(Scale("jp.jma.shindo", "5+", "observed")))
        assertEquals("震度7", scaleLabel(Scale("jp.jma.shindo", "7")))
        assertEquals("警戒レベル4", scaleLabel(Scale("jp.jma.warning-level", "4")))
        assertEquals("特別警報級", scaleLabel(Scale("jp.jma.warning-class", "special-warning")))
        assertEquals("大津波警報（予測）", scaleLabel(Scale("jp.jma.tsunami", "major-warning", "forecast")))
        assertEquals("噴火警戒レベル3", scaleLabel(Scale("jp.jma.volcanic-alert-level", "3")))
        assertEquals("非常に強い", scaleLabel(Scale("jp.jma.typhoon-intensity", "very-strong")))
        assertEquals("巨大地震注意", scaleLabel(Scale("jp.jma.nankai-trough", "caution")))
        assertEquals("MMI VII", scaleLabel(Scale("intl.mmi", "VII")))
        assertEquals("カテゴリー3", scaleLabel(Scale("intl.saffir-simpson", "3")))
        assertEquals("警戒色 橙", scaleLabel(Scale("intl.meteoalarm.awareness-level", "orange")))
        assertEquals("jp.jma.shindo 8", scaleLabel(Scale("jp.jma.shindo", "8")))
        assertEquals("org.example.flood-stage 2（観測）", scaleLabel(Scale("org.example.flood-stage", "2", "observed")))
    }

    @Test fun quantities() {
        val quake = hazard(HazardKind.Earthquake("event", HazardKind.Hypocenter(null, null, 10_000_000, LocalizedText("石川県能登地方", "ja")),
            HazardKind.Magnitude(65, null)), category = listOf("Geo"))
        assertEquals("M6.5・深さ約10km・震源 石川県能登地方", quake.quakeSummary())
        assertEquals("M7.0（Mj）", magnitudeLabel(HazardKind.Magnitude(70, "Mj")))
        assertEquals("1km未満", depthLabel(500_000))
        assertNull(hazard(HazardKind.Earthquake("assessment")).quakeSummary())
        assertEquals(listOf("最大の高さ" to "3m（予測）"), hazard(HazardKind.Tsunami(HazardKind.Height(3000, null, "forecast"))).details())
        assertEquals(listOf("最大の高さ" to "巨大"), hazard(HazardKind.Tsunami(HazardKind.Height(null, LocalizedText("巨大", "ja"), null))).details())
        assertEquals(listOf("名称" to "ハギビス", "中心気圧" to "970hPa", "最大風速" to "35m/s"),
            hazard(HazardKind.Cyclone(LocalizedText("ハギビス", "ja-Kana"), pressure = 97_000, maxWind = 35_000)).details())
    }

    @Test fun eventTimePrefersOnset() {
        val bsaf = Bsaf("earthquake", "2026-01-01T07:00:00Z", null, null, emptyList(), true)
        val post = PostView("p", "k", "x", hop = 0, relayAccepted = false, createdAt = 5,
            ext = PostExtensions(bsaf = bsaf, hazard = hazard().copy(onset = "2026-01-01T07:10:00Z")))
        assertEquals(1_767_251_400L, post.eventTime())
        assertEquals(1_767_250_800L, post.copy(ext = post.ext.copy(hazard = hazard().copy(onset = "nonsense"))).eventTime())
        assertEquals(5L, post.copy(ext = PostExtensions()).eventTime())
    }
}
