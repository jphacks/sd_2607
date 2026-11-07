package org.mndt.app.ui.settings

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.mndt.app.ui.Navigator
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.koin.compose.koinInject
import org.mndt.app.ui.post.bsafLabel
import org.mndt.app.ui.startRelay
import org.mndt.app.ui.theme.Mosimo
import org.mndt.core.ClockConfidence
import org.mndt.mesh.MeshCoordinator
import org.mndt.mesh.MeshPermissions
import org.mndt.mesh.MeshService

/** One trusted Bot DID under an enabled root, with the BSAF types selected for it. */
internal class TrustSource(val org: String, val botDid: String, val types: List<String>)

private fun JSONArray?.objects() = if (this == null) emptyList() else (0 until length()).map(::getJSONObject)
private fun JSONArray?.strings() = if (this == null) emptyList() else (0 until length()).map(::getString)

internal fun trustSources(context: Context): List<TrustSource> = runCatching {
    val json = JSONObject(context.assets.open("mosimo-public-trust.json").bufferedReader().use { it.readText() })
    val selected = json.optJSONArray("selected").objects().groupBy({ it.getString("botDid") }, { it.getString("type") })
    json.getJSONArray("roots").objects().filter { it.optBoolean("enabled") }.flatMap { root ->
        val scope = root.getJSONObject("scope")
        val org = scope.optJSONArray("orgIds").strings().joinToString(" / ")
        scope.optJSONArray("bsaf").objects().map { bot ->
            val did = bot.getString("botDid")
            TrustSource(org, did, bot.optJSONArray("types").strings().filter { it in selected[did].orEmpty() })
        }
    }
}.getOrDefault(emptyList())

@Composable
internal fun TrustScreen(nav: Navigator) = SubScreen("公式情報とみなす発信元", nav) {
    val context = LocalContext.current
    val sources = remember { trustSources(context) }
    if (sources.isEmpty()) Text("なし", style = MaterialTheme.typography.bodyMedium, color = Mosimo.textSecondary)
    sources.forEach { source ->
        Group {
            SettingRow(Icons.Outlined.VerifiedUser, Mosimo.safe, source.org.ifEmpty { "発信元" }, source.botDid)
            Line()
            Text(source.types.joinToString("・") { bsafLabel(it) }.ifEmpty { "選択なし" }, Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium, color = Mosimo.textSecondary)
        }
    }
}

@Composable
internal fun AdvancedScreen(nav: Navigator) = SubScreen("詳細設定", nav) {
    val context = LocalContext.current
    val mesh = koinInject<MeshCoordinator>()
    val state by mesh.state.collectAsStateWithLifecycle()
    var relay by rememberSaveable { mutableStateOf("") }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { startRelay(context) }
    val radios = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!MeshPermissions.granted(context)) mesh.reportError("近くのデバイスへの権限が必要です")
        else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notify.launch(Manifest.permission.POST_NOTIFICATIONS)
        else startRelay(context)
    }
    Header("通信")
    Group {
        SwitchRow(Icons.Outlined.SyncAlt, Mosimo.info, "オフライン中継を有効にする", "圏外時に近くの端末と情報を交換", state.running, state.ready || state.running) {
            if (!it) MeshService.stop(context) else radios.launch(MeshPermissions.required())
        }
        Line()
        SwitchRow(Icons.Outlined.Schedule, Mosimo.info, "端末時計を信頼", "公的情報の検証に必要", state.clockConfidence == ClockConfidence.TRUSTED) {
            mesh.setClockConfidence(if (it) ClockConfidence.TRUSTED else ClockConfidence.UNKNOWN)
        }
    }
    Header("オンライン搬出先リレー")
    Group {
        state.relays.forEachIndexed { i, url ->
            if (i > 0) Line()
            SettingRow(null, Mosimo.info, url, state.queued[url]?.takeIf { it > 0 }?.let { "未送信 ${it}件" }, trailing = {
                TextButton(enabled = state.ready, onClick = { mesh.removeRelay(url) }) { Text("削除", color = Mosimo.danger) }
            })
        }
        if (state.relays.isEmpty()) Text("なし", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = Mosimo.textSecondary)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(relay, { relay = it }, Modifier.weight(1f), placeholder = { Text("wss://… または ws://…") }, singleLine = true)
        TextButton(enabled = state.ready && relay.isNotBlank(), onClick = { mesh.addRelay(relay); relay = "" }) { Text("追加") }
    }
    Group {
        SwitchRow(Icons.Outlined.CloudUpload, Mosimo.info, "オンライン搬出", when {
            !state.onlineEnabled -> "停止中・搬出待ちは保持"
            !state.onlineActive -> "中継開始または画面表示で接続"
            !state.network -> "ネットワークなし・接続されたらすぐに送信"
            state.sendingRelay != null -> "接続中: ${state.sendingRelay}"
            state.retryInSeconds > 0 -> "接続失敗・${state.retryInSeconds}秒後に再試行"
            else -> "接続待ち"
        }, state.onlineEnabled, state.ready) { mesh.setOnlineEnabled(it) }
    }
    Header("表示")
    Group {
        SwitchRow(Icons.Outlined.VisibilityOff, Mosimo.info, "失効した Nostr イベントを非表示", "配信期限を過ぎた投稿を一覧・地図に出さない（端末時計を信頼しているときのみ）",
            state.hideExpired) { mesh.setHideExpired(it) }
    }
    Header("近隣端末 ${state.neighbors.size}")
    Group {
        state.neighbors.forEachIndexed { i, peer ->
            if (i > 0) Line()
            SettingRow(Icons.Outlined.Devices, Mosimo.info, "node ${peer.name}（未認証）", "${peer.endpoint} / ${peer.status}")
        }
        if (state.neighbors.isEmpty()) Text("なし", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = Mosimo.textSecondary)
    }
    Header("発信者・公的情報")
    Group {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(state.registration, style = MaterialTheme.typography.bodySmall, color = Mosimo.textPrimary)
            if (!state.pdsConfigured) Text("PDS 未設定のため PDS・Web には公開されません（データ消去で再作成）",
                style = MaterialTheme.typography.bodySmall, color = Mosimo.warning)
            Text(state.publicStatus, style = MaterialTheme.typography.bodySmall, color = Mosimo.textSecondary)
            state.publicResults.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Mosimo.textSecondary) }
        }
    }
    Header("診断")
    Group {
        Text(state.diagnostics.takeLast(16).joinToString("\n").ifEmpty { "なし" }, Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Mosimo.textSecondary)
    }
}

@Composable
internal fun IdentityScreen(nav: Navigator) = SubScreen("署名鍵とID", nav) {
    val state by koinInject<MeshCoordinator>().state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Group {
        listOf("DID" to state.did, "Nostr 公開鍵" to state.publicKey).forEachIndexed { i, (label, value) ->
            if (i > 0) Line()
            SettingRow(null, Mosimo.textSecondary, label, value.ifEmpty { "準備中" }, trailing = {
                IconButton(enabled = value.isNotEmpty(), onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, value))) } }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "コピー", tint = Mosimo.textSecondary)
                }
            })
        }
    }
}

@Composable
internal fun BlockedScreen(nav: Navigator) = SubScreen("ブロックリスト", nav) {
    val mesh = koinInject<MeshCoordinator>()
    val state by mesh.state.collectAsStateWithLifecycle()
    Text("投稿者 DID 単位で非表示にします。端末・中継は遮断しません。", style = MaterialTheme.typography.bodySmall, color = Mosimo.textSecondary)
    if (state.blockedDids.isEmpty()) Text("ブロック中の投稿者はいません", Modifier.padding(vertical = 24.dp).fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium, color = Mosimo.textTertiary)
    else Group {
        state.blockedDids.forEachIndexed { i, did ->
            if (i > 0) Line()
            SettingRow(Icons.Outlined.Block, Mosimo.danger, did, trailing = {
                TextButton(enabled = state.ready, onClick = { mesh.unblock(did) }) { Text("解除") }
            })
        }
    }
}
