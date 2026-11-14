package org.mndt.app.ui.settings

import android.Manifest
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import org.koin.dsl.module
import dev.jisaku.mosimo.BuildConfig
import org.mndt.app.alert.AlertGenre
import org.mndt.app.alert.HazardNotifier
import org.mndt.app.location.LocationRepository
import org.mndt.app.ui.Navigator
import org.mndt.app.ui.SettingsKey
import org.mndt.app.ui.Tab
import org.mndt.app.ui.openAppSettings
import org.mndt.app.ui.post.CONTACT_ADDRESS
import org.mndt.app.ui.post.PolicyText
import org.mndt.app.ui.post.TermsText
import org.mndt.app.ui.post.shortDid
import org.mndt.app.ui.theme.Mosimo
import org.mndt.mesh.MeshCoordinator
import kotlin.math.roundToInt

@Serializable private data object TrustKey : NavKey
@Serializable private data object AdvancedKey : NavKey
@Serializable private data object IdentityKey : NavKey
@Serializable private data object BlockedKey : NavKey
@Serializable private data object TermsKey : NavKey
@Serializable private data object PolicyKey : NavKey

val settingsModule = module { }

fun EntryProviderScope<NavKey>.settingsEntries(nav: Navigator) {
    entry<SettingsKey> { SettingsScreen(nav) }
    entry<TrustKey> { TrustScreen(nav) }
    entry<AdvancedKey> { AdvancedScreen(nav) }
    entry<IdentityKey> { IdentityScreen(nav) }
    entry<BlockedKey> { BlockedScreen(nav) }
    entry<TermsKey> { SubScreen("利用規約", nav) { TermsText(Modifier.padding(top = 8.dp)) } }
    entry<PolicyKey> { SubScreen("プライバシーポリシー", nav) { PolicyText(Modifier.padding(top = 8.dp)) } }
}

@Composable
private fun SettingsScreen(nav: Navigator) {
    val context = LocalContext.current
    val mesh = koinInject<MeshCoordinator>()
    val state by mesh.state.collectAsStateWithLifecycle()
    val roots = state.trustRoots.map { it.root }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("設定", Modifier.padding(top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.titleLarge, color = Mosimo.textPrimary)
        LocationCard(nav)
        AlertSwitch()
        Header("通信（もしもプロトコル）")
        Group {
            SettingRow(Icons.Outlined.VerifiedUser, Mosimo.safe, "公式情報とみなす発信元",
                roots.filter { it.enabled }.joinToString(" / ") { it.name }.ifEmpty { "信頼する root 鍵が未設定" },
                "${roots.count { it.enabled }}件") { nav.push(TrustKey) }
            Line()
            SettingRow(Icons.Outlined.Tune, Mosimo.textSecondary, "詳細設定", "利用状況・オフライン中継・端末時計・リレー・診断") { nav.push(AdvancedKey) }
        }
        Header("あなたの情報")
        Group {
            SettingRow(Icons.Outlined.Key, Mosimo.primary, "署名鍵とID",
                if (state.ready) "${shortDid(state.did.ifEmpty { null }, state.publicKey)}・端末で安全に保管" else "準備中") { nav.push(IdentityKey) }
            Line()
            SettingRow(Icons.Outlined.Block, Mosimo.danger, "ブロックリスト", value = "${state.blockedPubkeys.size}件") { nav.push(BlockedKey) }
        }
        Header("このアプリについて")
        Group {
            val uri = LocalUriHandler.current
            SettingRow(Icons.Outlined.PanTool, Mosimo.textSecondary, "プライバシーポリシー") { nav.push(PolicyKey) }
            Line()
            SettingRow(Icons.Outlined.Description, Mosimo.textSecondary, "利用規約") { nav.push(TermsKey) }
            Line()
            SettingRow(Icons.Outlined.Email, Mosimo.textSecondary, "お問い合わせ", CONTACT_ADDRESS) { uri.openUri("mailto:$CONTACT_ADDRESS") }
            Line()
            SettingRow(Icons.Outlined.Info, Mosimo.textSecondary, "バージョン", value = BuildConfig.VERSION_NAME)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun LocationCard(nav: Navigator) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val repo = koinInject<LocationRepository>()
    val location by repo.location.collectAsStateWithLifecycle()
    val address by repo.address.collectAsStateWithLifecycle()
    val busy by repo.busy.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(repo.granted()) }
    // Re-check on resume: the user may grant in system settings and come back.
    LifecycleResumeEffect(Unit) {
        granted = repo.granted()
        if (granted && repo.location.value == null) repo.refresh()
        onPauseOrDispose { }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = repo.granted()
        if (granted) repo.refresh()
        else if (activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)) openAppSettings(context)
    }
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(60_000); value = System.currentTimeMillis() } }
    val fix = location.takeIf { granted }
    Column(Modifier.surface().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(Icons.Outlined.Place, Mosimo.info, 48.dp)
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("あなたの位置", style = MaterialTheme.typography.bodySmall, color = Mosimo.textSecondary)
                    fix?.let { Text(age(now - it.time), style = MaterialTheme.typography.bodySmall, color = Mosimo.textTertiary) }
                }
                Text(when {
                    !granted -> "位置情報の権限がありません"
                    fix == null -> if (busy) "取得中…" else "位置を取得できていません"
                    else -> address ?: "%.5f, %.5f".format(fix.latitude, fix.longitude)
                }, style = MaterialTheme.typography.titleMedium, color = Mosimo.textPrimary)
                fix?.takeIf { it.hasAccuracy() }?.let {
                    Text("精度 ±${it.accuracy.roundToInt()}m・投稿にはこの位置がそのまま付きます", style = MaterialTheme.typography.bodySmall, color = Mosimo.textTertiary)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (granted) ActionButton(Icons.Outlined.MyLocation, "現在地を更新", enabled = !busy, onClick = repo::refresh)
            else ActionButton(Icons.Outlined.LocationOn, "権限を許可") {
                ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            ActionButton(Icons.Outlined.Map, "地図で確認") {
                nav.select(Tab.MAP)
            }
        }
    }
}

/** Hazard notifications per genre ([HazardNotifier]); switching one on asks for POST_NOTIFICATIONS when it is missing (Android 13+). */
@Composable
private fun AlertSwitch() {
    val context = LocalContext.current
    val on = remember { mutableStateMapOf(*AlertGenre.entries.map { it to HazardNotifier.enabled(context, it) }.toTypedArray()) }
    var allowed by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleResumeEffect(Unit) {
        allowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    Header("通知")
    if (!allowed && on.values.any { it }) Text("通知が許可されていないため届きません", Modifier.padding(start = 4.dp),
        style = MaterialTheme.typography.bodySmall, color = Mosimo.warning)
    Group {
        AlertGenre.entries.forEachIndexed { i, genre ->
            if (i > 0) Line()
            val (icon, subtitle) = when (genre) {
                AlertGenre.EARTHQUAKE -> Icons.Outlined.Vibration to "公式の地震情報"
                AlertGenre.WEATHER -> Icons.Outlined.Thunderstorm to "公式の気象警報・注意報、台風情報"
                AlertGenre.OTHER_OFFICIAL -> Icons.Outlined.Campaign to "公式の津波・火山などの情報"
                AlertGenre.NEARBY_DANGER -> Icons.Outlined.WarningAmber to "現在地から2km以内の利用者の「危険」投稿（未確認）"
            }
            SwitchRow(icon, Mosimo.danger, genre.label, subtitle, on[genre] == true) {
                on[genre] = it; HazardNotifier.setEnabled(context, genre, it)
                if (it && !allowed) {
                    if (android.os.Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS) else openAppSettings(context)
                }
            }
        }
    }
}

private fun age(millis: Long): String = (millis / 60_000).coerceAtLeast(0).let { m ->
    when { m < 1 -> "たった今"; m < 60 -> "${m}分前"; m < 1440 -> "${m / 60}時間前"; else -> "${m / 1440}日前" }
}

@Composable
private fun RowScope.ActionButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) = OutlinedButton(
    onClick, Modifier.weight(1f), enabled = enabled, shape = MaterialTheme.shapes.medium,
    colors = ButtonDefaults.outlinedButtonColors(containerColor = Mosimo.bgSurface2, contentColor = Mosimo.textPrimary),
    border = BorderStroke(1.dp, Mosimo.borderStrong),
    contentPadding = PaddingValues(horizontal = 8.dp),
) {
    Icon(icon, contentDescription = null, Modifier.size(18.dp))
    Spacer(Modifier.width(6.dp))
    Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
}

// Shared building blocks for the settings screens (Figma 13:599: grouped rows on surfaces).

private val card = RoundedCornerShape(16.dp)
internal fun Modifier.surface() = fillMaxWidth().clip(card).background(Mosimo.bgSurface).border(1.dp, Mosimo.border, card)

@Composable
internal fun Group(content: @Composable ColumnScope.() -> Unit) = Column(Modifier.surface(), content = content)

@Composable
internal fun Line() = HorizontalDivider(color = Mosimo.border)

@Composable
internal fun Header(text: String) = Text(text, Modifier.padding(start = 4.dp, top = 12.dp), style = MaterialTheme.typography.bodySmall,
    fontWeight = FontWeight.Bold, color = Mosimo.textSecondary)

@Composable
private fun IconTile(icon: ImageVector, tint: Color, size: Dp = 36.dp) = Box(
    Modifier.size(size).clip(MaterialTheme.shapes.medium).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center,
) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size / 2 + 2.dp)) }

@Composable
internal fun SettingRow(
    icon: ImageVector?, tint: Color, title: String, subtitle: String? = null, value: String? = null,
    trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null,
) = Row(
    Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
) {
    icon?.let { IconTile(it, tint) }
    Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = Mosimo.textPrimary)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Mosimo.textSecondary) }
    }
    value?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Mosimo.textSecondary) }
    when {
        trailing != null -> trailing()
        onClick != null -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Mosimo.textTertiary)
    }
}

@Composable
internal fun SwitchRow(icon: ImageVector?, tint: Color, title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) =
    Box(Modifier.toggleable(checked, enabled, Role.Switch, onValueChange = onChange)) {
        SettingRow(icon, tint, title, subtitle, trailing = { Switch(checked, onCheckedChange = null, enabled = enabled) })
    }

/** Sub-screens have no bottom bar, so they carry their own back bar. */
@Composable
internal fun SubScreen(title: String, nav: Navigator, content: @Composable ColumnScope.() -> Unit) = Column(Modifier.fillMaxSize().statusBarsPadding()) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton({ nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る", tint = Mosimo.textPrimary) }
        Text(title, style = MaterialTheme.typography.titleLarge, color = Mosimo.textPrimary)
    }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}
