package dev.jisaku.hanashi.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.mndt.mesh.Handoff
import org.mndt.mesh.MeshState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private const val SLOTS = 12
/** A peer stays on the ring this long after its last handoff: BLE links are released a few seconds after each exchange. */
private const val RECENT_MS = 60_000L
/** The arrow travels for the first [ARRIVE] of a flight, then fades while a ring marks where it landed. */
private const val FLIGHT_MS = 2_200
private const val ARRIVE = 0.75f

/** One handoff seen from this phone; [inbound]: the bundle came to us. */
private data class Move(val id: String, val step: Handoff, val inbound: Boolean)
/** A device around this phone, by its session or advert label; [live]: in a session now, not only seen nearby. */
private data class Peer(val label: String, val links: Set<String>, val live: Boolean)
/** A bundle on its way along one spoke; [label] says what happens: 受信, 中継 or 送信. */
private class Flight(val peer: String, val inbound: Boolean, val color: Color, val label: String) { val progress = Animatable(0f) }

/** Demo view: this phone in the middle, the devices around it, and an arrow for each bundle that moves between them. */
@Composable
fun RelayGraph(mesh: MeshState, modifier: Modifier = Modifier) {
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(5_000); value = System.currentTimeMillis() } }
    val events = remember(mesh.events) { mesh.events.associateBy { it.id } }
    val moves = remember(mesh.transits) {
        mesh.transits.flatMap { (id, t) -> listOfNotNull(t.received?.let { Move(id, it, true) }) + t.forwarded.map { Move(id, it, false) } }
    }
    // Sessions and adverts now, plus peers that just traded bundles.
    val peers = remember(mesh.neighbors, moves, now) {
        (mesh.neighbors.map { Peer(it.endpoint, it.links.toSet(), it.syncing) } +
            moves.filter { now - it.step.atMillis < RECENT_MS }.map { Peer(it.step.peer, setOf(it.step.link), false) })
            .groupBy { it.label }.map { (label, all) -> Peer(label, all.flatMapTo(HashSet()) { it.links }, all.any { it.live }) }
    }
    val (inside, outside) = peers.partition { "local" in it.links }
    // A peer keeps its place on the ring while it stays; a newcomer takes the first free one.
    val slots = remember { HashMap<String, Int>() }
    val placed = remember(outside) {
        slots.keys.retainAll(outside.mapTo(HashSet()) { it.label })
        outside.forEach { p -> if (p.label !in slots) (0 until SLOTS).firstOrNull { it !in slots.values }?.let { slots[p.label] = it } }
        slots.toMap()
    }
    val flights = remember { mutableStateListOf<Flight>() }
    // What had already happened when the view opened is history, not a flight.
    val seen = remember { moves.toHashSet() }
    // Not the effect's own scope: a newer handoff restarts the effect and must not cut a flight short.
    val scope = rememberCoroutineScope()
    LaunchedEffect(moves) {
        for (m in moves) if (seen.add(m)) {
            val e = events[m.id]
            val f = Flight(m.step.peer, m.inbound, e?.let { kind(it).second } ?: Hanashi.other,
                if (m.inbound) "受信" else if (e?.pubkey == mesh.publicKey) "送信" else "中継")
            flights += f
            scope.launch { f.progress.animateTo(1f, tween(FLIGHT_MS, easing = LinearEasing)); flights -= f }
        }
    }
    val measurer = rememberTextMeasurer()
    val tags = remember(measurer) {
        listOf("受信", "中継", "送信").associateWith { measurer.measure(it, TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)) }
    }
    val bluetooth = rememberVectorPainter(Icons.Outlined.Bluetooth)
    val wifi = rememberVectorPainter(Icons.Outlined.Wifi)
    val app = rememberVectorPainter(Icons.Outlined.Apps)
    val hub = rememberVectorPainter(Icons.Outlined.Hub)
    val homeLabel = measurer.measure("この端末", TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Hanashi.green))
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val s = size.minDimension
            val home = s * 0.2f
            val node = s * 0.055f
            fun at(radius: Float, angle: Double) = center + Offset((radius * cos(angle)).toFloat(), (radius * sin(angle)).toFloat())
            val spots = HashMap<String, Offset>()
            // Leaves room outside the ring for the 受信/中継 tags.
            placed.forEach { (label, slot) -> spots[label] = at(s * 0.34f, -PI / 2 + 2 * PI * slot / SLOTS) }
            inside.forEachIndexed { i, p -> spots[p.label] = at(home * 0.7f, PI / 2 + (i - (inside.size - 1) / 2.0) * 0.8) }
            // This phone, with the other Mosimo apps on it: they reach other phones only through the app running the radios (mesh §4.6).
            drawCircle(Hanashi.green.copy(alpha = 0.08f), home, center)
            val dashed = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
            for (p in peers) spots[p.label]?.let { drawLine(Hanashi.line, center, it, 2.dp.toPx(), pathEffect = if (p.live || p in inside) null else dashed) }
            drawText(homeLabel, topLeft = center - Offset(homeLabel.size.width / 2f, home * 0.9f))
            for (p in outside) spots[p.label]?.let {
                node(it, node, if (p.live) Hanashi.green else Hanashi.muted, if ("ble" in p.links) bluetooth else wifi)
            }
            for (p in inside) spots[p.label]?.let { node(it, node * 0.75f, Hanashi.green, app) }
            val middle = node * 1.3f
            drawCircle(Hanashi.green, middle, center)
            icon(hub, center, middle, Color.White)
            // On top, so a bundle stays visible as it passes a node: an arrowhead with chevrons flowing behind it (the way
            // is readable even in a still frame), the tag beside the other device, and a ring where it lands.
            for (f in flights) {
                val peer = spots[f.peer] ?: continue
                val radius = if (inside.any { it.label == f.peer }) node * 0.75f else node
                val (from, to) = if (f.inbound) peer to center else center to peer
                val p = f.progress.value
                val fade = if (p < ARRIVE) 1f else (1 - p) / (1 - ARRIVE)
                val color = f.color.copy(alpha = fade)
                val (fromRadius, toRadius) = if (f.inbound) radius to middle else middle to radius
                val way = (to - from) / (to - from).getDistance()
                // Edge to edge, so the arrow never covers a node's icon.
                val start = from + way * fromRadius
                val length = (to - way * toRadius - start).getDistance()
                val travelled = length * FastOutSlowInEasing.transform((p / ARRIVE).coerceAtMost(1f))
                val head = minOf(s * 0.035f, length * 0.9f)
                drawLine(f.color.copy(alpha = 0.3f * fade), start, start + way * travelled, 4.dp.toPx())
                val gap = 16.dp.toPx()
                var d = p * 10 * gap % gap
                while (d < travelled - head - gap / 2) { chevron(start + way * d, way, 5.dp.toPx(), color); d += gap }
                arrowhead(start + way * travelled, way, head, color)
                if (p > ARRIVE) drawCircle(color, toRadius * (2 - fade * 0.7f), to, style = Stroke(3.dp.toPx()))
                tags[f.label]?.let { tag ->
                    val out = (peer - center) / (peer - center).getDistance()
                    val half = Size(tag.size.width / 2f + 6.dp.toPx(), tag.size.height / 2f + 2.dp.toPx())
                    val at = peer + out * (radius + 4.dp.toPx() + abs(out.x) * half.width + abs(out.y) * half.height)
                    pill(tag, Offset(at.x.coerceIn(half.width, size.width - half.width), at.y.coerceIn(half.height, size.height - half.height)), half, color)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Key(Icons.Outlined.Bluetooth, "Bluetooth")
            Key(Icons.Outlined.Wifi, "Wi-Fi")
            Key(Icons.Outlined.Apps, "同じ端末のアプリ")
        }
        Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Dot(Hanashi.bosai, "防災アプリの投稿")
            Dot(Hanashi.green, "トーク")
            Dot(Hanashi.other, "その他")
        }
        val last = moves.maxByOrNull { it.step.atMillis }
        Text(last?.let { m ->
            val e = events[m.id]
            val verb = if (m.inbound) "から受信" else if (e?.pubkey == mesh.publicKey) "へ送信" else "へ中継"
            "${clockSeconds(m.step.atMillis)}  ${e?.let { kind(it).first } ?: "データ"}を ${linkLabel(m.step.link)} $verb"
        } ?: "受信・中継すると、矢印が線の上を動きます", Modifier.fillMaxWidth().padding(16.dp, 4.dp, 16.dp, 16.dp), fontSize = 13.sp, color = Hanashi.muted)
    }
}

private fun DrawScope.node(at: Offset, radius: Float, color: Color, painter: VectorPainter) {
    drawCircle(Color.White, radius, at)
    drawCircle(color, radius, at, style = Stroke(2.dp.toPx()))
    icon(painter, at, radius, color)
}

private fun DrawScope.icon(painter: VectorPainter, at: Offset, size: Float, tint: Color) =
    translate(at.x - size / 2, at.y - size / 2) { with(painter) { draw(Size(size, size), colorFilter = ColorFilter.tint(tint)) } }

/** "›" pointing along [way] (a unit vector), its tip at [at]. */
private fun DrawScope.chevron(at: Offset, way: Offset, size: Float, color: Color) {
    val back = at - way * size
    val side = Offset(-way.y, way.x) * (size * 0.8f)
    drawLine(color, back + side, at, 2.5.dp.toPx(), cap = StrokeCap.Round)
    drawLine(color, back - side, at, 2.5.dp.toPx(), cap = StrokeCap.Round)
}

private fun DrawScope.arrowhead(tip: Offset, way: Offset, size: Float, color: Color) {
    val back = tip - way * size
    val side = Offset(-way.y, way.x) * (size * 0.6f)
    drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(back.x + side.x, back.y + side.y); lineTo(back.x - side.x, back.y - side.y); close() }, color)
}

/** [text] in white on a [color] pill centered at [at]; [half] is half the pill's size. */
private fun DrawScope.pill(text: TextLayoutResult, at: Offset, half: Size, color: Color) {
    drawRoundRect(color, at - Offset(half.width, half.height), half * 2f, CornerRadius(half.height))
    drawText(text, topLeft = at - Offset(text.size.width / 2f, text.size.height / 2f), alpha = color.alpha)
}

@Composable
private fun Key(icon: ImageVector, label: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, Modifier.size(16.dp), tint = Hanashi.muted)
    Spacer(Modifier.width(4.dp))
    Text(label, fontSize = 12.sp, color = Hanashi.muted)
}

@Composable
private fun Dot(color: Color, label: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(10.dp).background(color, CircleShape))
    Spacer(Modifier.width(4.dp))
    Text(label, fontSize = 12.sp, color = Hanashi.muted)
}
