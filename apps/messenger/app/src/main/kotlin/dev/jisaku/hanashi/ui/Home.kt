package dev.jisaku.hanashi.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.jisaku.hanashi.Chats
import dev.jisaku.hanashi.Messenger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.mndt.mesh.MeshPermissions
import org.mndt.mesh.MeshService
import org.mndt.mesh.MeshState

@Serializable data object HomeKey : NavKey
@Serializable data class ChatKey(val peer: String) : NavKey

@Composable
fun HanashiRoot(messenger: Messenger, openPeer: MutableStateFlow<String?>) {
    val chats by messenger.chats.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    if (chats.name == null) { Onboarding { name -> scope.launch { messenger.setName(name) } }; return }
    val startMesh = rememberMeshStarter()
    val back = rememberNavBackStack(HomeKey)
    val pending by openPeer.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        val peer = pending ?: return@LaunchedEffect
        back.removeAll { it is ChatKey }
        back.add(ChatKey(peer))
        openPeer.value = null
    }
    NavDisplay(backStack = back, onBack = { back.removeLastOrNull() }, entryProvider = entryProvider {
        entry<HomeKey> { Home(messenger, chats, startMesh) { back.add(ChatKey(it)) } }
        entry<ChatKey> { ChatScreen(messenger, it.peer) { back.removeLastOrNull() } }
    })
}

/** Asks for Bluetooth (and notification) permissions once, then starts the mesh service; returns a retry action. */
@Composable
private fun rememberMeshStarter(): () -> Unit {
    val context = LocalContext.current
    val permissions = remember {
        MeshPermissions.required() + if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (MeshPermissions.granted(context)) MeshService.start(context)
    }
    val start = { if (MeshPermissions.granted(context)) MeshService.start(context) else launcher.launch(permissions) }
    LaunchedEffect(Unit) {
        if (permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) MeshService.start(context)
        else launcher.launch(permissions)
    }
    return start
}

@Composable
private fun Onboarding(onDone: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding().imePadding().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("もしもトーク", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Hanashi.green)
        Text("Mosimo Talk · Mosimo プロトコル デモアプリ", fontSize = 13.sp, color = Hanashi.muted)
        Spacer(Modifier.height(16.dp))
        Text("電波がなくても、近くの端末を経由して届くメッセージアプリ", textAlign = TextAlign.Center, color = Hanashi.muted)
        Spacer(Modifier.height(40.dp))
        OutlinedTextField(name, { name = it.take(40) }, label = { Text("表示名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Button({ onDone(name) }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("はじめる") }
    }
}

private enum class Tab(val label: String) { CHATS("トーク"), NEARBY("近くの人"), SETTINGS("設定") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Home(messenger: Messenger, chats: Chats, startMesh: () -> Unit, open: (String) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.CHATS) }
    val mesh by messenger.mesh.state.collectAsStateWithLifecycle()
    val unread = chats.messages.count { !it.mine && !it.read }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text(tab.label, fontWeight = FontWeight.Bold) })
                MosimoBanner(mesh, startMesh)
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) }, icon = {
                        val icon = when (t) { Tab.CHATS -> Icons.AutoMirrored.Outlined.Chat; Tab.NEARBY -> Icons.Outlined.People; Tab.SETTINGS -> Icons.Outlined.Settings }
                        BadgedBox(badge = { if (t == Tab.CHATS && unread > 0) Badge { Text("$unread") } }) { Icon(icon, null) }
                    })
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.CHATS -> ChatList(chats, open)
                Tab.NEARBY -> Nearby(chats, open)
                Tab.SETTINGS -> SettingsTab(messenger, chats, mesh)
            }
        }
    }
}

/** The demo's story: this app is "offline", so everything goes through Mosimo. */
@Composable
private fun MosimoBanner(mesh: MeshState, startMesh: () -> Unit) {
    val text = if (mesh.running) "オフライン · Mosimo 経由で送信（近くの端末 ${mesh.neighbors.size} 台）"
        else "Mosimo 停止中 · タップして開始"
    Row(Modifier.fillMaxWidth().background(if (mesh.running) Color(0xFF3C4650) else Color(0xFFB3261E))
        .clickable(enabled = !mesh.running, onClick = startMesh).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.WifiOff, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun Empty(text: String) = Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Text(text, color = Hanashi.muted, textAlign = TextAlign.Center)
}

@Composable
private fun ChatList(chats: Chats, open: (String) -> Unit) {
    val threads = chats.messages.groupBy { it.peer }.map { (peer, list) -> Triple(peer, list.maxBy { it.createdAt }, list.count { !it.mine && !it.read }) }
        .sortedByDescending { it.second.createdAt }
    if (threads.isEmpty()) return Empty("まだトークはありません\n「近くの人」から話しかけてみましょう")
    LazyColumn {
        items(threads, key = { it.first }) { (peer, last, unread) ->
            Row(Modifier.fillMaxWidth().clickable { open(peer) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(peer, chats.nameOf(peer))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(chats.nameOf(peer), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(last.text, color = Hanashi.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(shortTime(last.createdAt), color = Hanashi.muted, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    if (unread > 0) Box(Modifier.size(22.dp).background(Hanashi.green, CircleShape), contentAlignment = Alignment.Center) {
                        Text("$unread", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun Nearby(chats: Chats, open: (String) -> Unit) {
    val people = chats.contacts.values.sortedByDescending { it.seenAt }
    if (people.isEmpty()) return Empty("近くでもしもトークを使っている人が見つかると、ここに表示されます")
    LazyColumn {
        items(people, key = { it.pubkey }) { c ->
            Row(Modifier.fillMaxWidth().clickable { open(c.pubkey) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(c.pubkey, c.name)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, fontWeight = FontWeight.Bold)
                    Text("ID ${c.pubkey.take(8)}…", color = Hanashi.muted, fontSize = 12.sp)
                }
                Text("トーク", color = Hanashi.green, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SettingsTab(messenger: Messenger, chats: Chats, mesh: MeshState) {
    var editing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(mesh.publicKey, chats.name.orEmpty(), 64.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(chats.name.orEmpty(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                TextButton({ editing = true }, contentPadding = PaddingValues(0.dp)) { Text("名前を変更") }
            }
        }
        Column {
            Text("あなたの ID（公開鍵）", color = Hanashi.muted, fontSize = 12.sp)
            SelectionContainer { Text(mesh.publicKey, fontSize = 13.sp) }
        }
        Column {
            Text("通信", color = Hanashi.muted, fontSize = 12.sp)
            Text(if (mesh.running) "Mosimo 稼働中 · 近くの端末 ${mesh.neighbors.size} 台" else "Mosimo 停止中")
            Text("このデモは常に Mosimo（Bluetooth・LAN のメッシュ）で送受信し、インターネットは使いません。", color = Hanashi.muted, fontSize = 13.sp)
        }
    }
    if (editing) {
        var name by remember { mutableStateOf(chats.name.orEmpty()) }
        AlertDialog(onDismissRequest = { editing = false }, title = { Text("名前を変更") },
            text = { OutlinedTextField(name, { name = it.take(40) }, singleLine = true) },
            confirmButton = { TextButton({ scope.launch { messenger.setName(name) }; editing = false }, enabled = name.isNotBlank()) { Text("保存") } },
            dismissButton = { TextButton({ editing = false }) { Text("キャンセル") } })
    }
}
