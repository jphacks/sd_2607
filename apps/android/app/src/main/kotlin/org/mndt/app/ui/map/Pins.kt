package org.mndt.app.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.maplibre.android.geometry.LatLng
import org.mndt.mesh.PostView
import kotlin.math.abs
import kotlin.math.roundToInt

private val PIN_ANCHOR = 16.dp
/** Pin footprint (icon + ~10-char label) used to decide which pins would overlap. */
private val PIN_W = 120.dp
private val PIN_H = 56.dp

/** One drawn pin: [post] on top, [ids] the whole stack newest first, [point] the anchor on screen. */
class PinGroup(val post: PostView, val lat: Double, val lng: Double, val ids: List<String>, val point: IntOffset)

private val severityRank = listOf("Extreme", "Severe", "Moderate", "Minor")

/**
 * Pins that would overlap at the current zoom collapse into one stack with a count badge. Each pin shows from its
 * [minZoom]: official alerts at every zoom (small earthquakes only closer in) and they win the top of a stack (most
 * severe, then newest); ordinary posts appear from [POST_MIN_ZOOM], newest on top. Regroups on every camera move.
 */
@Composable
fun pinGroups(handle: MapHandle, posts: List<PostView>): List<PinGroup> {
    val ranked = remember(posts) {
        posts.mapNotNull { p -> p.mapPoint()?.let { p to it } }.sortedWith(compareBy<Pair<PostView, Pair<Double, Double>>>(
            { if (it.first.alert) 0 else 1 },
            { (p, _) -> p.ext.hazard?.takeIf { p.alert }?.severity?.let(severityRank::indexOf)?.takeIf { it >= 0 } ?: severityRank.size },
        ).thenByDescending { it.first.createdAt })
    }
    val map = handle.map ?: return emptyList()
    val projection = map.projection
    handle.tick.intValue
    val zoom = map.cameraPosition.zoom
    val pinned = ranked.filter { zoom >= it.first.minZoom }
    val points = pinned.map { (_, ll) -> projection.toScreenLocation(LatLng(ll.first, ll.second)).let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } }
    val top = with(LocalDensity.current) { stacks(points, PIN_W.roundToPx(), PIN_H.roundToPx()) }
    return pinned.indices.filter { top[it] == it }.map { i ->
        PinGroup(pinned[i].first, pinned[i].second.first, pinned[i].second.second, pinned.indices.filter { top[it] == i }.map { pinned[it].first.id }, points[i])
    }
}

/** Pins take no touches, so a drag that starts on one still pans the map; taps reach them through [pinAt]. */
@Composable
fun Pins(handle: MapHandle, groups: List<PinGroup>, onClick: (List<String>) -> Unit) = groups.forEach { g ->
    key(g.post.id) { Pin(g.post, Modifier.at(handle, g.lat, g.lng, anchorY = PIN_ANCHOR), count = g.ids.size) { onClick(g.ids) } }
}

/** The pin under a tap at ([x], [y]) px: icon plus label below it, nearest anchor wins. */
fun Density.pinAt(groups: List<PinGroup>, x: Float, y: Float): PinGroup? = groups.filter { g ->
    abs(x - g.point.x) <= 30.dp.toPx() && y - g.point.y in -22.dp.toPx()..40.dp.toPx()
}.minByOrNull { g -> abs(x - g.point.x) + abs(y - g.point.y) }
