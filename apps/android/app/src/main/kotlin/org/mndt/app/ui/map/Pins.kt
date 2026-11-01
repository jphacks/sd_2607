package org.mndt.app.ui.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.maplibre.android.geometry.LatLng
import org.mndt.app.ui.post.latLng
import org.mndt.app.ui.theme.Mosimo
import org.mndt.mesh.PostView
import kotlin.math.roundToInt

private val PIN_ANCHOR = 16.dp

/**
 * All pins in one layout, decluttered in screen space on every camera move: newest keeps its true spot, the rest step
 * aside (icon + label never overlap) and get a leader line plus dot back to their real point.
 */
@Composable
fun Pins(handle: MapHandle, posts: List<PostView>, onClick: (String) -> Unit) {
    val pinned = remember(posts) { posts.mapNotNull { p -> p.latLng()?.let { p to it } }.sortedByDescending { it.first.createdAt } }
    var leaders by remember { mutableStateOf(emptyList<Pair<Offset, Offset>>()) }
    Layout({ pinned.forEach { (p, _) -> key(p.id) { Pin(p, Modifier) { onClick(p.id) } } } }, Modifier.fillMaxSize().drawBehind {
        leaders.forEach { (from, to) ->
            drawLine(Mosimo.textSecondary, from, to, 1.dp.toPx())
            drawCircle(Mosimo.textSecondary, 3.dp.toPx(), from)
        }
    }) { measurables, constraints ->
        val pins = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            handle.tick.intValue
            val projection = handle.map?.projection ?: return@layout
            val anchor = PIN_ANCHOR.roundToPx()
            val points = pinned.map { (_, ll) -> projection.toScreenLocation(LatLng(ll.first, ll.second)) }
            val rects = pins.mapIndexed { i, p -> IntRect(IntOffset(points[i].x.roundToInt() - p.width / 2, points[i].y.roundToInt() - anchor), IntSize(p.width, p.height)) }
            val shifts = declutter(rects, gap = 4.dp.roundToPx())
            leaders = pins.indices.filter { shifts[it] != IntOffset.Zero }.map { i ->
                val r = rects[i].translate(shifts[i])
                Offset(points[i].x, points[i].y) to Offset(r.left + pins[i].width / 2f, (r.top + anchor).toFloat())
            }
            pins.forEachIndexed { i, p -> rects[i].translate(shifts[i]).let { p.place(it.left, it.top) } }
        }
    }
}
