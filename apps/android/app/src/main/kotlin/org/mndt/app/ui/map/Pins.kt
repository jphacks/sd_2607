package org.mndt.app.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.maplibre.android.geometry.LatLng
import org.mndt.app.ui.post.latLng
import org.mndt.mesh.PostView
import kotlin.math.roundToInt

private val PIN_ANCHOR = 16.dp
/** Pin footprint (icon + ~10-char label) used to decide which pins would overlap. */
private val PIN_W = 120.dp
private val PIN_H = 56.dp

/** Pins that would overlap at the current zoom collapse into one stack: the newest on top with a count badge. [onClick] gets the stack, newest first. */
@Composable
fun Pins(handle: MapHandle, posts: List<PostView>, onClick: (List<String>) -> Unit) {
    val pinned = remember(posts) { posts.mapNotNull { p -> p.latLng()?.let { p to it } }.sortedByDescending { it.first.createdAt } }
    val projection = handle.map?.projection ?: return
    handle.tick.intValue // regroup on every camera move
    val points = pinned.map { (_, ll) -> projection.toScreenLocation(LatLng(ll.first, ll.second)).let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } }
    val top = with(LocalDensity.current) { stacks(points, PIN_W.roundToPx(), PIN_H.roundToPx()) }
    pinned.forEachIndexed { i, (p, ll) ->
        if (top[i] != i) return@forEachIndexed
        val members = pinned.indices.filter { top[it] == i }.map { pinned[it].first.id }
        key(p.id) { Pin(p, Modifier.at(handle, ll.first, ll.second, anchorY = PIN_ANCHOR), count = members.size) { onClick(members) } }
    }
}
