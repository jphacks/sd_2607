package org.mndt.app.ui.settings

import androidx.core.content.edit

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
import org.mndt.app.ui.post.dateTime
import org.mndt.app.ui.startRelay
import org.mndt.app.ui.theme.Mosimo
import org.mndt.core.ClockConfidence
import org.mndt.core.PublicIngress
import org.mndt.mesh.MeshCoordinator
import org.mndt.mesh.MeshPermissions
import org.mndt.mesh.MeshService

/** The selected types of one source in the local trust config: a Bot DID (BSAF `type:` values) or a feed (importer §1.1). */
internal class TrustSource(val name: String, val feed: Boolean, val types: List<String>)
private fun JSONArray?.objects() = if (this == null) emptyList() else (0 until length()).map(::getJSONObject)

private fun trustConfig(context: Context): JSONObject? =
    runCatching { JSONObject(context.assets.open("mosimo-public-trust.json").bufferedReader().use { it.readText() }) }.getOrNull()


internal fun trustSources(context: Context): List<TrustSource> = runCatching {
    trustConfig(context)!!.optJSONArray("selected").objects()
        .groupBy({ (it.optString("feed").ifEmpty { null } ?: it.getString("botDid")) to it.has("feed") }, { it.getString("type") })
        .map { (source, types) -> TrustSource(source.first, source.second, types) }
}.getOrDefault(emptyList())

@Composable
internal fun TrustScreen(nav: Navigator) = SubScreen("公式情報とみなす発信元", nav) {
    val context = LocalContext.current
    val mesh = koinInject<MeshCoordinator>()
    val state by mesh.state.collectAsStateWithLifecycle()
    val roots = state.trustRoots
    val sources = remember { trustSources(context) }
    Text("信頼の起点はこの端末の設定だけで決まります。受信したデータから追加されることはありません。",
        style = MaterialTheme.typography.bodySmall, color = Mosimo.textSecondary)
    Header("信頼する root 鍵 ${roots.count { it.root.enabled }}")
    if (roots.isEmpty()) Group { Text("なし（公的情報の投入は無効）", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = Mosimo.textSecondary) }
    roots.forEach { (root, origin) ->
        Group {
            SwitchRow(Icons.Outlined.VerifiedUser, if (root.enabled) Mosimo.safe else Mosimo.textTertiary, root.name,
                "${originLabel(origin)}・鍵 ${root.pubkeyHex.take(16)}…", root.enabled) { mesh.setRootEnabled(root.pubkeyHex, it) }
            Line()
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("許可する役割: ${root.roles.sorted().joinToString("・").ifEmpty { "なし" }}", Modifier.weight(1f).padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium, color = Mosimo.textSecondary)
                if (origin == PublicIngress.RootOrigin.USER) TextButton({ mesh.removeRoot(root.pubkeyHex) }) { Text("削除", color = Mosimo.danger) }
            }
        }
    }
    AddRoot(mesh)
    Header("取り込みを選択した Bot・フィードと種別")
    if (sources.isEmpty()) Group { Text("なし", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = Mosimo.textSecondary) }
    sources.forEach { source ->
        Group {
            SettingRow(Icons.Outlined.Download, Mosimo.info, if (source.feed) feedLabel(source.name) else source.name, value = "${source.types.size}種別")
            Line()
            Text(source.types.joinToString("・") { if (source.feed) it else bsafLabel(it) }, Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium, color = Mosimo.textSecondary)
        }
    }
    Header("失効情報")
    Group {
        SettingRow(Icons.Outlined.Block, Mosimo.textSecondary, "保持している失効通知", value = "${state.revocationCount}件")
        if (state.revocationChecked.isNotEmpty()) {
            Line()
            Text(state.revocationChecked.entries.joinToString("\n") { (domain, at) -> "$domain: ${dateTime(at)} に確認" },
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = Mosimo.textSecondary)
        }
    }
}

private fun feedLabel(feed: String) = if (feed == PublicIngress.FEED_JMA_XML) "気象庁防災情報XML（$feed）" else feed

private fun originLabel(o: PublicIngress.RootOrigin) = when (o) {
    PublicIngress.RootOrigin.BUILTIN -> "アプリに同梱"
    PublicIngress.RootOrigin.TEST -> "テスト投稿用（開発版のみ）"
    PublicIngress.RootOrigin.USER -> "自分で追加"
}

/** Adds a root by its 64-hex public key. Only what the user types here becomes a trust anchor (attest §3). */
@Composable
private fun AddRoot(mesh: MeshCoordinator) {
    var key by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Header("root 鍵を追加")
    Group {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("信頼する発行元から公式の経路で受け取った公開鍵（64桁の16進数）だけを追加してください。追加した root から署名された転載・発表は公式情報として表示されます。",
                style = MaterialTheme.typography.bodySmall, color = Mosimo.textSecondary)
            OutlinedTextField(key, { key = it; error = false }, Modifier.fillMaxWidth(), label = { Text("公開鍵") }, singleLine = true,
                isError = error, supportingText = if (error) {{ Text("64桁の16進数の公開鍵を入力してください") }} else null,
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace))
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("名前（例: ○○市）") }, singleLine = true)
            Button({ if (mesh.addRoot(key, name)) { key = ""; name = "" } else error = true }, Modifier.align(Alignment.End), enabled = key.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Mosimo.primary, contentColor = Mosimo.onPrimary)) { Text("追加する") }
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
    // Privacy policy §3: anonymous usage statistics, on unless the user turns them off. Nothing is sent yet.
    var usageStats by remember { mutableStateOf(UsageStats.enabled(context)) }
    Header("利用状況")
    Group {
        SwitchRow(Icons.Outlined.BarChart, Mosimo.info, "利用状況の統計を送信", "個人を識別しない利用状況を送信し、アプリの改善と統計に使います", usageStats) {
            usageStats = it; UsageStats.set(context, it)
        }
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
        SwitchRow(Icons.Outlined.VisibilityOff, Mosimo.info, "配送期限を過ぎた投稿を非表示", "配送期限を過ぎた投稿を一覧・地図に出さない（端末時計を信頼しているときのみ）",
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
    if (state.clockSkews.isNotEmpty()) {
        Header("近隣端末の時刻ヒント（未検証）")
        Group {
            Text(state.clockSkews.joinToString("\n") { "${it.peer.take(12)}: ${if (it.deltaSeconds >= 0) "+" else ""}${it.deltaSeconds}秒（申告 confidence ${it.confidence}）" },
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = Mosimo.textSecondary)
            Line()
            Text("相手の自己申告であり、時計の同期・証明には使いません。", Modifier.padding(16.dp),
                style = MaterialTheme.typography.labelSmall, color = Mosimo.textTertiary)
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
    Text("発信鍵（Nostr 公開鍵）単位で非表示にし、中継もしません。相手の端末との通信は遮断しません。",
        style = MaterialTheme.typography.bodySmall, color = Mosimo.textSecondary)
    if (state.blockedPubkeys.isEmpty()) Text("ブロック中の投稿者はいません", Modifier.padding(vertical = 24.dp).fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium, color = Mosimo.textTertiary)
    else Group {
        state.blockedPubkeys.forEachIndexed { i, pubkey ->
            if (i > 0) Line()
            SettingRow(Icons.Outlined.Block, Mosimo.danger, "鍵 ${pubkey.take(16)}…", trailing = {
                TextButton(enabled = state.ready, onClick = { mesh.unblock(pubkey) }) { Text("解除") }
            })
        }
    }
}

/** Privacy policy §3 switch, shared with a future usage reporter (none sends yet). */
object UsageStats {
    private const val KEY = "usageStats"
    fun enabled(context: Context): Boolean = context.getSharedPreferences("app", Context.MODE_PRIVATE).getBoolean(KEY, true)
    fun set(context: Context, value: Boolean) = context.getSharedPreferences("app", Context.MODE_PRIVATE).edit { putBoolean(KEY, value) }
}
