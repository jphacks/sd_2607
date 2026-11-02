package org.mndt.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import org.koin.compose.koinInject
import org.mndt.app.location.LocationRepository
import org.mndt.app.ui.map.mapEntries
import org.mndt.app.ui.settings.settingsEntries
import org.mndt.app.ui.theme.Mosimo
import org.mndt.app.ui.timeline.timelineEntries
import org.mndt.mesh.MeshCoordinator
import org.mndt.mesh.MeshPermissions
import org.mndt.mesh.MeshService

/** Calibration knob: slightly softer than the default spring so the slide reads as following the finger. */
private val slideSpec = spring(stiffness = 300f, visibilityThreshold = IntOffset.VisibilityThreshold)

private fun AnimatedContentTransitionScope<*>.slide(forward: Boolean): ContentTransform {
    val direction = if (forward) SlideDirection.Start else SlideDirection.End
    return slideIntoContainer(direction, slideSpec) togetherWith slideOutOfContainer(direction, slideSpec)
}

@Composable
fun MosimoApp() {
    val nav = rememberNavigator()
    FirstLaunchPermissions()
    val mesh = koinInject<MeshCoordinator>()
    val error = mesh.state.collectAsStateWithLifecycle().value.error
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(error) { if (error != null) { snackbar.showSnackbar(error); mesh.clearError() } }
    val context = LocalContext.current
    val relayBlocked = nav.atTabRoot && !mesh.state.collectAsStateWithLifecycle().value.running && !MeshPermissions.granted(context)
    // Edge to edge: no top inset here, so the map runs under the status bar; other screens pad themselves.
    Scaffold(
        containerColor = Mosimo.bgBase,
        contentWindowInsets = WindowInsets.navigationBars,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if (nav.atTabRoot) BottomBar(nav) },
    ) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding)) {
            if (relayBlocked) Box(Modifier.statusBarsPadding()) {
                Banner("中継が止まっています", "近くのデバイスへの権限が必要です", "許可する", Mosimo.warning) { openAppSettings(context) }
            }
            NavDisplay(
                nav.backStack, Modifier.weight(1f).then(if (relayBlocked) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier), onBack = nav::pop,
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
                transitionSpec = { slide(nav.forward) },
                popTransitionSpec = { slide(nav.forward) },
                // Predictive back: the outgoing screen follows the finger toward the end edge.
                predictivePopTransitionSpec = { slide(forward = false) },
                entryProvider = entryProvider {
                    timelineEntries(nav)
                    mapEntries()
                    settingsEntries(nav)
                },
            )
        }
    }
}

@Composable
private fun BottomBar(nav: Navigator) {
    Row(
        Modifier.fillMaxWidth().background(Mosimo.bgSurface).drawBehind { drawLine(Mosimo.border, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
            .navigationBarsPadding().height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabItem("タイムライン", Icons.AutoMirrored.Outlined.List, nav.tab == Tab.TIMELINE) { nav.select(Tab.TIMELINE) }
        // Center tab is the raised yellow circle (Figma Nav Bar 3:178).
        val mapActive = nav.tab == Tab.MAP
        Column(Modifier.weight(1f).clickable { nav.select(Tab.MAP) }, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.offset(y = (-10).dp).size(52.dp).clip(CircleShape).background(Mosimo.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Place, contentDescription = null, tint = Mosimo.onPrimary)
            }
            Text("状況マップ", Modifier.offset(y = (-6).dp), style = MaterialTheme.typography.labelSmall,
                color = if (mapActive) Mosimo.primary else Mosimo.textTertiary, fontWeight = if (mapActive) FontWeight.Bold else null)
        }
        TabItem("設定", Icons.Outlined.Settings, nav.tab == Tab.SETTINGS) { nav.select(Tab.SETTINGS) }
    }
}

@Composable
private fun RowScope.TabItem(label: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    val color = if (active) Mosimo.textPrimary else Mosimo.textTertiary
    Column(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(icon, contentDescription = null, tint = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = if (active) FontWeight.Bold else null)
    }
}

/** First launch: nearby devices → notifications → location, then start relaying if nearby was granted. Later launches resume relaying unless switched off. */
@Composable
private fun FirstLaunchPermissions() {
    val context = LocalContext.current
    val location = koinInject<LocationRepository>()
    val prefs = remember { context.getSharedPreferences("app", Context.MODE_PRIVATE) }
    if (prefs.getBoolean("permissionsAsked", false)) {
        LaunchedEffect(Unit) { if (MeshService.enabled(context) && MeshPermissions.granted(context)) startRelay(context) }
        return
    }
    val locate = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        prefs.edit { putBoolean("permissionsAsked", true) }
        location.refresh()
    }
    fun askLocation() = locate.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { askLocation() }
    val nearby = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (MeshPermissions.granted(context)) startRelay(context)
        if (Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS) else askLocation()
    }
    LaunchedEffect(Unit) {
        if (MeshPermissions.granted(context)) { startRelay(context); askLocation() } else nearby.launch(MeshPermissions.required())
    }
}

fun startRelay(context: Context) {
    runCatching { MeshService.start(context) }.onFailure { MeshCoordinator.get(context).reportError("中継を開始できませんでした。権限とシステム設定を確認してください") }
}

fun openAppSettings(context: Context) = context.startActivity(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

/** Full-width notice row with one action; shared by permission and status banners. */
@Composable
fun Banner(title: String, detail: String?, action: String?, color: androidx.compose.ui.graphics.Color = Mosimo.info, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(MaterialTheme.shapes.medium)
            .background(color.copy(alpha = 0.14f)).border(1.dp, color.copy(alpha = 0.4f), MaterialTheme.shapes.medium).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Mosimo.textPrimary)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Mosimo.textSecondary) }
        }
        action?.let { TextButton(onClick = onAction) { Text(it, color = color) } }
    }
}
