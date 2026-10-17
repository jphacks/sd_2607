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
import androidx.lifecycle.compose.LifecycleStartEffect
import org.mndt.mesh.MeshCoordinator
import org.mndt.mesh.MeshPermissions
import org.mndt.mesh.MeshService
import org.mndt.mesh.OnlineService
import org.mndt.core.ClockConfidence
import org.mndt.core.DeliveryDeadline

class MeshApplication : Application() {
    override fun onCreate() { super.onCreate(); MeshCoordinator.get(this) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mesh = MeshCoordinator.get(this)
        setContent { MaterialTheme { Demo(mesh) } }
    }
    override fun onStart() { super.onStart(); MeshCoordinator.get(this).setOnlineForeground(true) }
    override fun onStop() { MeshCoordinator.get(this).setOnlineForeground(false); super.onStop() }
}

@Composable
private fun Demo(mesh: MeshCoordinator) {
    val state by mesh.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleStartEffect(state.ready, state.onlineEnabled) {
        if (state.ready && state.onlineEnabled) runCatching { OnlineService.start(context) }
            .onFailure { mesh.reportError("オンライン前景サービスを開始できません。画面から再試行してください") }
        onStopOrDispose { /* OnlineService owns background IO; hiding this screen must not stop it. */ }
    }
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
        if (!granted) permissionNotice = "通知は無効ですが通信は継続します。停止はこの画面から行えます。"
    }
    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    fun begin() {
        runCatching { MeshService.start(context) }.onFailure { mesh.reportError("前景サービス開始失敗。権限とシステム設定を確認してください") }
        requestNotifications()
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(checked = state.clockConfidence == ClockConfidence.TRUSTED,
                            onCheckedChange = { mesh.setClockConfidence(if (it) ClockConfidence.TRUSTED else ClockConfidence.UNKNOWN) })
                        Text("端末時計を信頼して配送期限を判定（手動・再起動で解除）", style = MaterialTheme.typography.bodySmall)
                    }
                } }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = state.ready && !state.running, onClick = {
                        permissionNotice = null
                        if (MeshPermissions.granted(context)) begin() else nearby.launch(MeshPermissions.required())
                    }) { Text("Nearby 開始") }
                    OutlinedButton(enabled = state.running, onClick = { MeshService.stop(context) }) { Text("Nearby 停止") }
                }
                Text(if (state.running) "Nearby 中継中 / Bluetooth・Wi-Fi を有効にしてください" else "Nearby 停止中 / 保存・オンライン通信とは独立")
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
                    TextButton(enabled = state.ready, onClick = { mesh.configureRelay(relay); if (relay.isNotBlank()) requestNotifications() }) { Text("保存してオンライン通信を有効化") }
                    TextButton(enabled = state.ready && state.relay.isNotEmpty(), onClick = {
                        mesh.setOnlineEnabled(!state.onlineEnabled)
                        if (!state.onlineEnabled) requestNotifications()
                    }) {
                        Text(if (state.onlineEnabled) "オンライン停止" else "オンライン再開")
                    }
                }
                Text(when {
                    state.onlineBackgroundActive -> "オンライン通信有効（画面非表示時も継続・Nearby 不要）"
                    state.onlineActive -> "オンライン通信有効（画面内のみ / 前景サービス開始待ち）"
                    else -> "オンライン停止 / 搬出待ちは保持"
                }, style = MaterialTheme.typography.bodySmall)
                Text("通知付き前景サービスで画面非表示時も送信・購読します。Android 15 以降は原則24時間中6時間の上限があり、停止後はアプリを開くと再開します。OS の省電力・強制停止・プロセス終了時の即時配送は保証しません。避難所 LAN でも接続を試みます。ws:// は暗号化されません。", style = MaterialTheme.typography.bodySmall)
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
                    if (post.volatile) {
                        Text("オンライン取得 / 揮発表示（ディスク保存・メッシュ投入なし）", style = MaterialTheme.typography.bodySmall)
                        Text("取得元: ${post.sourceRelay}（情報源の本人性・信頼は未確認）", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("観測 hop ${post.hop}（完全な経路の証明ではない）", style = MaterialTheme.typography.bodySmall)
                        Text(when (post.deliveryDeadline) {
                            DeliveryDeadline.UNKNOWN -> "配送期限の判定不能 / 中継は Bundle Age・hop・ローカル上限に従う"
                            DeliveryDeadline.WITHIN_DEADLINE -> "信頼すると指定した端末時計では配送期限内（情報の有効性とは別）"
                            DeliveryDeadline.EXPIRED -> "配送期限切れ / 本文とオンライン搬出待ちは保持"
                        }, style = MaterialTheme.typography.bodySmall)
                        Text(if (post.relayAccepted) "設定先リレー OK true / PDS・AppView は未確認" else "リレー未受理・未設定 / キュー保持", style = MaterialTheme.typography.bodySmall)
                    }
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
