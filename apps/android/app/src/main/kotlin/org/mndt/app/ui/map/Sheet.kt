package org.mndt.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import org.mndt.app.ui.theme.Mosimo
import kotlin.math.roundToInt

/** Sheet stops, lowest first. Which ones exist depends on what the sheet shows. */
enum class Snap { Hidden, Low, Mid, High }

private val sheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
val SHEET_HANDLE = 20.dp

/**
 * Non-modal bottom sheet over the map, as in Google Maps: it tracks the finger and snaps to the anchors of [state]
 * (top offsets in px within the parent, which should clip). Scrolling content inside hands over to the sheet at its edges.
 */
@Composable
fun SnapSheet(state: AnchoredDraggableState<Snap>, hiddenPx: Float, onHandle: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().offset { IntOffset(0, state.offset.takeUnless { it.isNaN() }?.roundToInt() ?: hiddenPx.roundToInt()) }
        .shadow(8.dp, sheetShape).background(Mosimo.bgSurface, sheetShape)
        .nestedScroll(remember(state) { handOver(state) })
        .anchoredDraggable(state, Orientation.Vertical, flingBehavior = AnchoredDraggableDefaults.flingBehavior(state))) {
        Box(Modifier.fillMaxWidth().clickable(onClickLabel = "広げる・たたむ", onClick = onHandle).padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(Mosimo.borderStrong))
        }
        content()
    }

/** Up-scrolls raise the sheet before the content scrolls; leftover down-scrolls lower it (as M3's sheet does). */
private fun handOver(state: AnchoredDraggableState<Snap>) = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource) =
        if (available.y < 0 && source == NestedScrollSource.UserInput) Offset(0f, state.dispatchRawDelta(available.y)) else Offset.Zero
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) =
        if (source == NestedScrollSource.UserInput) Offset(0f, state.dispatchRawDelta(available.y)) else Offset.Zero
    override suspend fun onPreFling(available: Velocity) =
        if (available.y < 0 && state.offset > state.anchors.minPosition()) { state.flingTo(available.y); available } else Velocity.Zero
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity { state.flingTo(available.y); return available }
}

/** A fast fling goes to the next stop its way, a slow release to the nearest. */
private suspend fun AnchoredDraggableState<Snap>.flingTo(velocity: Float) {
    val y = offset.takeUnless { it.isNaN() } ?: return
    val stops = Snap.entries.filter { anchors.hasPositionFor(it) }
    val next = when {
        velocity < -FLING -> stops.filter { anchors.positionOf(it) < y - 1 }.maxByOrNull { anchors.positionOf(it) }
        velocity > FLING -> stops.filter { anchors.positionOf(it) > y + 1 }.minByOrNull { anchors.positionOf(it) }
        else -> null
    } ?: anchors.closestAnchor(y) ?: return
    animateTo(next)
}

/** px/s; ponytail: fixed, scale by density if flings feel off on other screens. */
private const val FLING = 1500f
