package org.mndt.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.mndt.mesh.MeshCoordinator
import org.mndt.mesh.MeshPermissions
import org.mndt.mesh.MeshService

class MeshApplication : Application() {
    override fun onCreate() { super.onCreate(); MeshCoordinator.get(this) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mesh = MeshCoordinator.get(this)
        setContent { MaterialTheme { Demo(mesh) } }
    }
}

@Composable
private fun Demo(mesh: MeshCoordinator) {
    val state by mesh.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var draft by rememberSaveable { mutableStateOf("") }
    var relay by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var seenPublishedId by remember { mutableStateOf(state.lastPublishedId) }
    var permissionNotice by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.relay) { relay = state.relay }
    LaunchedEffect(state.lastPublishedId) {
        if (state.lastPublishedId != seenPublishedId) { draft = ""; seenPublishedId = state.lastPublishedId }
    }
    LaunchedEffect(state.publishAttempt) { sending = false }
    LaunchedEffect(state.error) { if (state.error != null) sending = false }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) permissionNotice = "通知は無効ですが Nearby は継続します。停止はこの画面から行えます。"
    }
    fun begin() {
        runCatching { MeshService.start(context) }.onFailure { mesh.reportError("前景サービス開始失敗。権限とシステム設定を確認してください") }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val nearby = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (MeshPermissions.granted(context)) begin()
        else permissionNotice = "近隣端末の権限が必要です。拒否が続く場合は Android のアプリ設定を確認してください。"
    }
    Surface(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("MNDT メッシュ実験", style = MaterialTheme.typography.headlineSmall)
                Text("${context.packageName} / 共通 P2P_CLUSTER", style = MaterialTheme.typography.labelSmall)
                Text("実験実装・公開投稿のみ。名前・電話番号・詳しい居場所を投稿しないでください。", color = MaterialTheme.colorScheme.error)
            }
            item {
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (state.ready) "発信者準備済み（このインストール専用）" else "発信者を準備中 / エラー時は停止")
                    SelectionContainer { Text("DID: ${state.did}\nNostr: ${state.publicKey}", style = MaterialTheme.typography.bodySmall) }
                    Text(state.registration, style = MaterialTheme.typography.bodySmall)
                    Text("Nostr 署名検証 ≠ DID 本人認証 ≠ 内容の真実性", style = MaterialTheme.typography.bodySmall)
                } }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = state.ready && !state.running, onClick = {
                        permissionNotice = null
                        if (MeshPermissions.granted(context)) begin() else nearby.launch(MeshPermissions.required())
                    }) { Text("メッシュ開始") }
                    OutlinedButton(enabled = state.running, onClick = { MeshService.stop(context) }) { Text("停止") }
                }
                Text(if (state.running) "中継中 / Bluetooth・Wi-Fi を有効にしてください" else "停止中 / 投稿は暗号化保存できます")
                permissionNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            state.error?.let { error -> item {
                Card { Column(Modifier.padding(12.dp)) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = mesh::clearError) { Text("閉じる") }
                } }
            } }
            item {
                OutlinedTextField(relay, { relay = it }, Modifier.fillMaxWidth(), label = { Text("リレー URL（未設定なら送信しません）") }, placeholder = { Text("wss://… または明示的なローカル ws://…") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = state.ready, onClick = { mesh.configureRelay(relay) }) { Text("設定を保存") }
                    TextButton(enabled = state.ready, onClick = { relay = ""; mesh.configureRelay("") }) { Text("送信を無効化") }
                }
                Text("中継中はこの宛先だけに元署名 JSON を送ります。外部ネットがない避難所 LAN でも接続を試みます。ws:// は暗号化されません。", style = MaterialTheme.typography.bodySmall)
            }
            item {
                OutlinedTextField(draft, { if (it.toByteArray(Charsets.UTF_8).size <= 2_000) draft = it }, Modifier.fillMaxWidth(), label = { Text("公開する本文（最大 2000 UTF-8 bytes）") }, minLines = 3)
                Button(enabled = state.ready && draft.isNotBlank() && !sending, onClick = { sending = true; mesh.clearError(); mesh.publish(draft) }) {
                    Text(if (sending) "署名・保存中…" else "署名して保存・配送")
                }
            }
            item { Text("近隣端末 ${state.neighbors.size}", style = MaterialTheme.typography.titleMedium) }
            items(state.neighbors, key = { it.endpoint }) { peer ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text("${peer.name}（未認証の表示名）")
                    Text("${peer.endpoint} / ${peer.status}", style = MaterialTheme.typography.bodySmall)
                    peer.code?.let { code ->
                        Text("照合コード: $code", style = MaterialTheme.typography.titleLarge)
                        Text("相手の画面と同じ数字か確認してください。一致しない・知らない相手は拒否。")
                        Row {
                            TextButton(onClick = { mesh.confirm(peer.endpoint, true) }) { Text("一致した・許可") }
                            TextButton(onClick = { mesh.confirm(peer.endpoint, false) }) { Text("拒否") }
                        }
                    }
                } }
            }
            item { Text("署名検証済み投稿 ${state.posts.size}", style = MaterialTheme.typography.titleMedium) }
            items(state.posts, key = { it.id }) { post ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SelectionContainer { Text(post.content) }
                    Text("${post.id.take(12)} / 鍵 ${post.pubkey.take(12)}", style = MaterialTheme.typography.labelSmall)
                    Text("観測 hop ${post.hop}（完全な経路の証明ではない）", style = MaterialTheme.typography.bodySmall)
                    Text(if (post.relayAccepted) "設定先リレー OK true / PDS・AppView は未確認" else "リレー未受理・未設定 / キュー保持", style = MaterialTheme.typography.bodySmall)
                } }
            }
            item {
                Text("診断（本文・秘密鍵は出力しません）", style = MaterialTheme.typography.titleMedium)
                Text(state.diagnostics.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                Text("Nearby transport SUCCESS と、相手の保存 ACK と、リレー OK は別の状態です。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
