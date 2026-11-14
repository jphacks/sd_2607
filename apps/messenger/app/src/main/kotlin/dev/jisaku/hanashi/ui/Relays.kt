package dev.jisaku.hanashi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.SouthWest
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jisaku.hanashi.Carried
import dev.jisaku.hanashi.Chats
import dev.jisaku.hanashi.carried
import org.mndt.core.AtRepository
import org.mndt.core.NostrEvent
import org.mndt.mesh.MeshState

/** Demo view of what this phone carries for others over Mosimo; the chat UI never shows other people's bundles. */
@Composable
fun Relays(mesh: MeshState, chats: Chats) {
    val items = remember(mesh.events, mesh.transits, mesh.publicKey) { carried(mesh) }
    val list = rememberLazyListState()
    // A new arrival lands above the first row, which the list keeps in place; follow it while at the top.
    LaunchedEffect(items.firstOrNull()?.event?.id) { if (list.firstVisibleItemIndex <= 1) list.animateScrollToItem(0) }
    Column(Modifier.fillMaxSize().background(Hanashi.canvas)) {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(vertical = 12.dp)) {
            Stat(items.size, "受信", Color.Black)
            Stat(items.count { it.transit.forwarded.isNotEmpty() }, "中継", Hanashi.green)
            Stat(mesh.neighbors.size, "近くの端末", Color.Black)
        }
        if (items.isEmpty()) Empty("まだ中継したデータはありません\n近くで Mosimo のアプリが使われると、ここに流れます")
        else LazyColumn(Modifier.fillMaxSize(), list, PaddingValues(12.dp)) {
            items(items, key = { it.event.id }) { CarriedRow(it, chats, mesh.publicKey, Modifier.animateItem()) }
        }
    }
}

@Composable
private fun RowScope.Stat(value: Int, label: String, color: Color) = Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
    Text("$value", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = color)
    Text(label, fontSize = 12.sp, color = Hanashi.muted)
}

@Composable
private fun CarriedRow(item: Carried, chats: Chats, me: String, modifier: Modifier) {
    val (event, transit) = item
    val (label, color) = kind(event)
    val did = remember(event.id) { AtRepository.did(event) }
    Row(modifier.height(IntrinsicSize.Min).padding(bottom = 12.dp)) {
        // Time rail as in Mosimo Bosai's timeline: when the bundle reached this phone.
        Column(Modifier.width(64.dp).fillMaxHeight()) {
            Text(transit.received?.let { clockSeconds(it.atMillis) } ?: "前回", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Hanashi.muted)
            Box(Modifier.padding(start = 20.dp, top = 6.dp).width(1.dp).weight(1f).background(Hanashi.line))
        }
        Column(Modifier.weight(1f).background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(label, color, color.copy(alpha = 0.12f))
                Spacer(Modifier.weight(1f))
                if (transit.forwarded.isNotEmpty()) Pill("中継 ${transit.forwarded.size}台", Color.White, Hanashi.green)
            }
            summary(event, chats, me)?.let { Text(it, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            Text(people(event, chats, me, did), fontSize = 12.sp, color = Hanashi.muted)
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Hanashi.line)
            Leg(Icons.Outlined.SouthWest, "受信", "${transit.received?.let { linkLabel(it.link) } ?: "前回の起動まで"} · ${hopLabel(transit.hop)}")
            transit.forwarded.forEach { Leg(Icons.Outlined.NorthEast, "中継", "${linkLabel(it.link)} · ${clockSeconds(it.atMillis)}") }
            if (transit.forwarded.isEmpty()) Leg(Icons.Outlined.NorthEast, "中継待ち", "持っていない端末に渡します", Hanashi.muted)
        }
    }
}

@Composable
private fun Pill(text: String, color: Color, background: Color) = Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold,
    modifier = Modifier.background(background, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp))

@Composable
private fun Leg(icon: ImageVector, label: String, detail: String, tint: Color = Hanashi.green) = Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
    Spacer(Modifier.width(6.dp))
    Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = tint)
    Spacer(Modifier.width(8.dp))
    Text(detail, fontSize = 13.sp, color = Hanashi.muted)
}

private fun isReceipt(e: NostrEvent) = e.tags.any { it.firstOrNull() == "e" }
private fun recipient(e: NostrEvent) = e.tags.singleOrNull { it.firstOrNull() == "p" }?.getOrNull(1)

/** Mosimo Bosai posts are kind 1; Talk sends kind 4 (messages, receipts) and kind 0 (names), protocol dm §1. */
private fun kind(e: NostrEvent): Pair<String, Color> = when (e.kind) {
    1 -> "防災の投稿" to Color(0xFFE8590C)
    4 -> (if (isReceipt(e)) "トークの配達通知" else "トークのメッセージ") to Hanashi.green
    0 -> "トークの表示名" to Color(0xFF1C7ED6)
    5 -> "削除要求" to Hanashi.muted
    1984 -> "報告" to Hanashi.muted
    AtRepository.CONTROL_KIND -> (if (AtRepository.controlTopic(e) == AtRepository.PLC_TOPIC) "ID 登録" else "制御データ") to Color(0xFF7048E8)
    else -> "kind ${e.kind}" to Hanashi.muted
}

/** The readable part; messages stay sealed, since only the two ends hold the key. */
private fun summary(e: NostrEvent, chats: Chats, me: String): String? = when (e.kind) {
    1 -> e.content.trim()
    4 -> if (isReceipt(e)) "受け取りの確認" else if (recipient(e) == me) "あなた宛てのメッセージ" else "🔒 暗号化されています。読めるのは宛先の人だけです"
    0 -> "「${chats.nameOf(e.pubkey)}」"
    else -> null
}

private fun people(e: NostrEvent, chats: Chats, me: String, did: String?): String {
    fun who(key: String) = if (key == me) "あなた" else chats.nameOf(key)
    return when {
        e.kind == 4 -> "${who(e.pubkey)} → ${recipient(e)?.let(::who) ?: "?"}"
        e.kind == 0 -> "ID ${e.pubkey.take(8)}…"
        // The same short forms as Mosimo Bosai's timeline, so a post can be matched across the two screens.
        did != null -> "did:plc:${did.removePrefix("did:plc:").take(2)}…${did.takeLast(2)}"
        else -> "鍵 ${e.pubkey.take(6)}…"
    }
}

/** Hop count on arrival: the author's own device sends with 1. */
private fun hopLabel(hop: Int) = if (hop <= 1) "作成者から直接" else "${hop - 1}台を経由"

private fun linkLabel(link: String) = when (link) {
    "ble" -> "Bluetooth"
    "lan" -> "Wi-Fi"
    "local" -> "同じ端末のアプリ"
    else -> link
}
