package dev.jisaku.hanashi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jisaku.hanashi.Messenger
import dev.jisaku.hanashi.Msg
import dev.jisaku.hanashi.label
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.mndt.dm.Delivery
import org.mndt.dm.DmCodec
import java.time.LocalDate

private sealed interface Row {
    data class Day(val date: LocalDate) : Row
    data class Bubble(val msg: Msg) : Row
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(messenger: Messenger, peer: String, onBack: () -> Unit) {
    val chats by messenger.chats.collectAsStateWithLifecycle()
    val messages = chats.messages.filter { it.peer == peer }.sortedBy { it.createdAt }
    // Newest first, for a bottom-anchored reversed list; a date chip follows (renders above) each day's messages.
    val rows = messages.groupBy { day(it.createdAt) }.flatMap { (date, list) -> listOf(Row.Day(date)) + list.map(Row::Bubble) }.asReversed()
    val now by produceState(System.currentTimeMillis() / 1000) { while (true) { delay(30_000); value = System.currentTimeMillis() / 1000 } }
    DisposableEffect(peer) { messenger.openPeer = peer; onDispose { messenger.openPeer = null } }
    LaunchedEffect(messages.size) { messenger.markRead(peer) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val tooLong = draft.trim().toByteArray().size > DmCodec.MAX_TEXT_BYTES
    Scaffold(
        containerColor = Hanashi.chatBackground,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } },
                title = {
                    Column {
                        Text(chats.nameOf(peer), fontWeight = FontWeight.Bold)
                        Text("Mosimo 経由 · オフライン", fontSize = 12.sp, color = Hanashi.muted)
                    }
                },
            )
        },
        bottomBar = {
            Row(Modifier.background(Color.White).navigationBarsPadding().imePadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), placeholder = { Text("メッセージを入力") }, maxLines = 4,
                    shape = RoundedCornerShape(24.dp), isError = tooLong)
                IconButton(enabled = draft.isNotBlank() && !tooLong && !sending, onClick = {
                    sending = true
                    scope.launch {
                        if (messenger.send(peer, draft)) draft = ""
                        else snackbar.showSnackbar("送信できませんでした（送信の上限中）。少し待ってから再送してください")
                        sending = false
                    }
                }) { Icon(Icons.AutoMirrored.Filled.Send, "送信", tint = if (draft.isNotBlank() && !tooLong) Hanashi.green else Hanashi.muted) }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), reverseLayout = true, contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(rows, key = { if (it is Row.Bubble) it.msg.id else (it as Row.Day).date.toString() }) { row ->
                when (row) {
                    is Row.Day -> Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                        Text(dayLabel(row.date), color = Color.White, fontSize = 12.sp,
                            modifier = Modifier.background(Color(0x33000000), CircleShape).padding(horizontal = 10.dp, vertical = 3.dp))
                    }
                    is Row.Bubble -> Bubble(row.msg, now)
                }
            }
        }
    }
}

@Composable
private fun Bubble(msg: Msg, now: Long) {
    val meta = @Composable {
        Column(horizontalAlignment = if (msg.mine) Alignment.End else Alignment.Start, modifier = Modifier.padding(horizontal = 4.dp)) {
            if (msg.mine) {
                val delivery = msg.delivery(now)
                Text(delivery.label(), fontSize = 10.sp, color = if (delivery == Delivery.EXPIRED) Color(0xFFB3261E) else Color.White)
            }
            Text(clock(msg.createdAt), fontSize = 10.sp, color = Color.White)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        if (msg.mine) meta()
        Text(msg.text, Modifier.widthIn(max = 260.dp)
            .background(if (msg.mine) Hanashi.myBubble else Color.White, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 15.sp)
        if (!msg.mine) meta()
    }
}
