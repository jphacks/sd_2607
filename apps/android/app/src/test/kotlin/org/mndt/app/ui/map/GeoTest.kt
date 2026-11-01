package org.mndt.app.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class GeoTest {
    @Test fun geohash() {
        val (s, w, n, e) = geohashBounds("ezs42").toList()
        assertEquals(42.605, (s + n) / 2, 0.01)
        assertEquals(-5.603, (w + e) / 2, 0.01)
        val b = geohashBounds("u4pruydqqvj")
        assertTrue(b[0] <= 57.64911 && 57.64911 <= b[2] && b[1] <= 10.40744 && 10.40744 <= b[3])
        assertEquals(5, boundsRing(b).size)
    }

    @Test fun circle() {
        val ring = circleRing(38.26, 140.87, 200.0)
        assertEquals(ring.first(), ring.last())
        ring.forEach { (lat, lng) -> assertEquals(200.0, haversine(38.26, 140.87, lat, lng), 0.5) }
    }

    @Test fun labels() {
        assertEquals(140_871_230L, 140.87123.micro()); assertEquals(-5_603_000L, (-5.603).micro())
        assertEquals("±12m", accuracyLabel(12.7f))
        assertEquals("±2.0km", accuracyLabel(2000f))
    }

    private fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lng2 - lng1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * 6_371_008.8 * asin(sqrt(a))
    }
}
