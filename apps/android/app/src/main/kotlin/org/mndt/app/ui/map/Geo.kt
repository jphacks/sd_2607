package org.mndt.app.ui.map

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import java.util.Locale
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

/** Geohash cell as (south, west, north, east) degrees. */
fun geohashBounds(hash: String): DoubleArray {
    val lat = doubleArrayOf(-90.0, 90.0)
    val lng = doubleArrayOf(-180.0, 180.0)
    var even = true
    for (c in hash.lowercase()) {
        val v = BASE32.indexOf(c).also { require(it >= 0) { "bad geohash $hash" } }
        for (bit in 4 downTo 0) {
            val r = if (even) lng else lat
            val mid = (r[0] + r[1]) / 2
            if (v shr bit and 1 == 1) r[0] = mid else r[1] = mid
            even = !even
        }
    }
    return doubleArrayOf(lat[0], lng[0], lat[1], lng[1])
}

/** Closed ring of (lat, lng) [radiusMeters] around a centre on a sphere; fine for the few-km circles posts carry. */
fun circleRing(lat: Double, lng: Double, radiusMeters: Double, steps: Int = 48): List<Pair<Double, Double>> {
    val d = radiusMeters / 6_371_008.8
    val p1 = Math.toRadians(lat)
    val l1 = Math.toRadians(lng)
    val ring = (0 until steps).map { i ->
        val bearing = 2 * PI * i / steps
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(bearing))
        val l2 = l1 + atan2(sin(bearing) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        Math.toDegrees(p2) to Math.toDegrees(l2)
    }
    return ring + ring.first()
}

fun boundsRing(b: DoubleArray): List<Pair<Double, Double>> =
    listOf(b[0] to b[1], b[0] to b[3], b[2] to b[3], b[2] to b[1], b[0] to b[1])

fun Double.micro(): Long = (this * 1e6).roundToLong()

/** 「±12m」「±2.0km」 */
fun accuracyLabel(meters: Float): String = if (meters < 1000) "±${meters.toInt()}m" else "±%.1fkm".format(Locale.ROOT, meters / 1000)

fun coordsLabel(lat: Double, lng: Double): String = "%.5f, %.5f".format(Locale.ROOT, lat, lng)

fun polygonFeature(ring: List<Pair<Double, Double>>, color: String) =
    """{"type":"Feature","properties":{"color":"$color"},"geometry":{"type":"Polygon","coordinates":[[${ring.joinToString(",") { "[${it.second},${it.first}]" }}]]}}"""

fun pointFeature(lat: Double, lng: Double) = """{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[$lng,$lat]}}"""

fun featureCollection(features: List<String>) = """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

private val DIRS = listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0, 1 to -1, 1 to 1, -1 to 1, -1 to -1)

/**
 * Greedy screen-space declutter in list order (earlier rects win their true spot). Each rect keeps its place or takes the
 * first offset on rings of half its own size (8 directions, [rings] rings) that clears every placed rect by [gap].
 * Returns the shift per rect.
 */
// ponytail: O(n²·candidates), fine for a few hundred pins; switch to clustering if counts grow.
fun declutter(rects: List<IntRect>, gap: Int, rings: Int = 4): List<IntOffset> {
    val placed = ArrayList<IntRect>(rects.size)
    return rects.map { r ->
        val sx = (r.width + gap) / 2
        val sy = (r.height + gap) / 2
        val candidates = listOf(IntOffset.Zero) + (1..rings).flatMap { i -> DIRS.map { (dx, dy) -> IntOffset(dx * i * sx, dy * i * sy) } }
        // ponytail: a cluster denser than the outer ring overlaps there; clustering is the fix if that shows up.
        val shift = candidates.firstOrNull { o -> r.translate(o).inflate(gap).let { c -> placed.none { it.overlaps(c) } } } ?: candidates.last()
        placed += r.translate(shift)
        shift
    }
}
