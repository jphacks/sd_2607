package org.mndt.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.maplibre.android.geometry.LatLng
import org.mndt.app.ui.post.latLng
import org.mndt.app.ui.theme.Mosimo
import org.mndt.mesh.PostView
import kotlin.math.roundToInt

private val PIN_ANCHOR = 16.dp
/** Pin footprint (icon + ~10-char label) used to decide which pins would overlap. */
private val PIN_W = 120.dp
private val PIN_H = 56.dp
private val FAN_STEP = 60.dp

/**
 * Pins that would overlap at the current zoom collapse into one stack: the newest on top with a count badge. Tapping a
 * stack fans its posts out upward from the stack's point ([expanded] = top post id); tapping a fanned pin opens it.
 */
@Composable
fun Pins(handle: MapHandle, posts: List<PostView>, expanded: String?, onExpand: (String) -> Unit, onClick: (String) -> Unit) {
    val pinned = remember(posts) { posts.mapNotNull { p -> p.latLng()?.let { p to it } }.sortedByDescending { it.first.createdAt } }
    val projection = handle.map?.projection ?: return
    handle.tick.intValue // regroup on every camera move
    val density = LocalDensity.current
    val points = pinned.map { (_, ll) -> projection.toScreenLocation(LatLng(ll.first, ll.second)).let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } }
    val top = with(density) { stacks(points, PIN_W.roundToPx(), PIN_H.roundToPx()) }
    val fan = pinned.indices.filter { pinned[top[it]].first.id == expanded }
    if (fan.size > 1) Canvas(Modifier.fillMaxSize()) {
        val from = points[fan.first()].let { Offset(it.x.toFloat(), it.y.toFloat()) }
        drawLine(Mosimo.textSecondary, from, from.copy(y = from.y - FAN_STEP.toPx() * (fan.size - 1)), 1.dp.toPx())
        fan.forEach { i -> drawCircle(Mosimo.textSecondary, 3.dp.toPx(), Offset(points[i].x.toFloat(), points[i].y.toFloat())) }
    }
    pinned.forEachIndexed { i, (p, _) ->
        if (top[i] != i && i !in fan) return@forEachIndexed
        // A fanned stack hangs from its top post's point, newest lowest.
        val (lat, lng) = pinned[top[i]].second
        val count = top.count { it == i }
        key(p.id) {
            Pin(p, Modifier.at(handle, lat, lng, anchorY = PIN_ANCHOR).offset(y = -FAN_STEP * fan.indexOf(i).coerceAtLeast(0)),
                count = if (i in fan) 1 else count) { if (count > 1 && i !in fan) onExpand(p.id) else onClick(p.id) }
        }
    }
}
