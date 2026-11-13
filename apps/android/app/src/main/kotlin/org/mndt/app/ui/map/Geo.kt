package org.mndt.app.ui.map

import androidx.compose.ui.unit.IntOffset
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
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

/** Cells of a geohash union on one grid (the finest code length present), as integer (x, y) indices; null past [limit] cells. */
private class GeohashGrid(val dLat: Double, val dLng: Double, val cells: Set<Pair<Int, Int>>)
private fun geohashGrid(hashes: List<String>, limit: Int = 20_000): GeohashGrid? {
    val valid = hashes.map { it.lowercase() }.filter { h -> h.isNotEmpty() && h.length <= 12 && h.all { it in BASE32 } }
    val depth = valid.maxOfOrNull { it.length } ?: return null
    if (valid.sumOf { 32.0.pow(depth - it.length) } > limit) return null
    val dLng = 360.0 / (1L shl (5 * depth + 1) / 2); val dLat = 180.0 / (1L shl 5 * depth / 2)
    val cells = HashSet<Pair<Int, Int>>()
    for (h in valid) {
        val b = geohashBounds(h)
        val x0 = ((b[1] + 180) / dLng).roundToInt(); val x1 = ((b[3] + 180) / dLng).roundToInt()
        val y0 = ((b[0] + 90) / dLat).roundToInt(); val y1 = ((b[2] + 90) / dLat).roundToInt()
        for (x in x0 until x1) for (y in y0 until y1) cells += x to y
    }
    return GeohashGrid(dLat, dLng, cells)
}

/**
 * The outer edges of a geohash union: every cell side with no covered neighbour, as (lat, lng) segments, so a
 * prefecture reads as one outlined shape instead of a grid. Null when the union is too large to trace.
 */
fun geohashOutline(hashes: List<String>): List<List<Pair<Double, Double>>>? {
    val g = geohashGrid(hashes) ?: return null
    fun p(x: Int, y: Int) = (y * g.dLat - 90) to (x * g.dLng - 180)
    return g.cells.flatMap { (x, y) ->
        listOfNotNull(
            if (x to y - 1 !in g.cells) listOf(p(x, y), p(x + 1, y)) else null,
            if (x to y + 1 !in g.cells) listOf(p(x, y + 1), p(x + 1, y + 1)) else null,
            if (x - 1 to y !in g.cells) listOf(p(x, y), p(x, y + 1)) else null,
            if (x + 1 to y !in g.cells) listOf(p(x + 1, y), p(x + 1, y + 1)) else null,
        )
    }
}

/**
 * A point inside a geohash union for its pin: within its largest connected part (so outlying islands don't pull it
 * out to sea), the centre of the cell nearest that part's mean centre.
 */
fun geohashAnchor(hashes: List<String>): Pair<Double, Double>? {
    val g = geohashGrid(hashes) ?: return null
    val left = g.cells.toMutableSet()
    var part = emptyList<Pair<Int, Int>>()
    while (left.isNotEmpty()) {
        val seen = mutableListOf(left.first().also(left::remove))
        var i = 0
        while (i < seen.size) {
            val (x, y) = seen[i++]
            for (n in listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) if (left.remove(n)) seen += n
        }
        if (seen.size > part.size) part = seen
    }
    val mx = part.sumOf { it.first + 0.5 } / part.size; val my = part.sumOf { it.second + 0.5 } / part.size
    val (x, y) = part.minByOrNull { (x, y) -> (x + 0.5 - mx).pow(2) + (y + 0.5 - my).pow(2) } ?: return null
    return ((y + 0.5) * g.dLat - 90) to ((x + 0.5) * g.dLng - 180)
}

fun boundsRing(b: DoubleArray): List<Pair<Double, Double>> =
    listOf(b[0] to b[1], b[0] to b[3], b[2] to b[3], b[2] to b[1], b[0] to b[1])

fun Double.micro(): Long = (this * 1e6).roundToLong()

/** 「±12m」「±2.0km」 */
fun accuracyLabel(meters: Float): String = if (meters < 1000) "±${meters.toInt()}m" else "±%.1fkm".format(Locale.ROOT, meters / 1000)

fun coordsLabel(lat: Double, lng: Double): String = "%.5f, %.5f".format(Locale.ROOT, lat, lng)

/** [layer] picks the map layer that draws the feature (see the regions layers in MapLibreView). */
fun polygonFeature(ring: List<Pair<Double, Double>>, color: String, layer: String = "post-fill") =
    """{"type":"Feature","properties":{"color":"$color","layer":"$layer"},"geometry":{"type":"Polygon","coordinates":[[${ring.joinToString(",") { "[${it.second},${it.first}]" }}]]}}"""

fun lineFeature(lines: List<List<Pair<Double, Double>>>, color: String, layer: String) =
    """{"type":"Feature","properties":{"color":"$color","layer":"$layer"},"geometry":{"type":"MultiLineString","coordinates":[${
        lines.joinToString(",") { line -> "[${line.joinToString(",") { "[${it.second},${it.first}]" }}]" }}]}}"""

fun pointFeature(lat: Double, lng: Double) = """{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[$lng,$lat]}}"""

fun featureCollection(features: List<String>) = """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

/**
 * Screen-space stacking in list order (put newest first): each point joins the first earlier stack whose top lies within
 * [w]×[h] of it (the pin footprint), else starts a new one. Returns the stack's top index for every point.
 */
// ponytail: greedy O(n·stacks), fine for a few hundred pins; real clustering (supercluster) if counts grow.
fun stacks(points: List<IntOffset>, w: Int, h: Int): List<Int> {
    val tops = ArrayList<Int>()
    return points.mapIndexed { i, p ->
        tops.firstOrNull { t -> abs(points[t].x - p.x) < w && abs(points[t].y - p.y) < h } ?: i.also { tops += i }
    }
}
