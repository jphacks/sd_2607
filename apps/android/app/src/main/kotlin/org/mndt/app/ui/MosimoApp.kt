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
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.koin.compose.koinInject
import org.mndt.app.location.LocationRepository
import org.mndt.app.ui.map.mapGraph
import org.mndt.app.ui.settings.settingsGraph
import org.mndt.app.ui.theme.Mosimo
import org.mndt.app.ui.timeline.timelineGraph
import org.mndt.mesh.MeshCoordinator
import org.mndt.mesh.MeshPermissions
import org.mndt.mesh.MeshService

private val tabs = listOf(Routes.TIMELINE, Routes.MAP, Routes.SETTINGS)

/** Pushes slide in from the end; switching tabs slides toward the tab's side of the bottom bar. */
private fun slideDirection(from: NavBackStackEntry, to: NavBackStackEntry): SlideDirection {
    val a = tabs.indexOf(from.destination.route)
    val b = tabs.indexOf(to.destination.route)
    return if (a >= 0 && b >= 0 && b < a) SlideDirection.End else SlideDirection.Start
}

@Composable
fun MosimoApp() {
    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    FirstLaunchPermissions()
    val mesh = koinInject<MeshCoordinator>()
    val error = mesh.state.collectAsStateWithLifecycle().value.error
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(error) { if (error != null) { snackbar.showSnackbar(error); mesh.clearError() } }
    Scaffold(
        containerColor = Mosimo.bgBase,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if (route in tabs) BottomBar(route, nav) },
    ) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding)) {
            if (route in tabs) RelayPermissionBanner()
            NavHost(nav, startDestination = Routes.MAP, Modifier.weight(1f),
                enterTransition = { slideIntoContainer(slideDirection(initialState, targetState)) },
                exitTransition = { slideOutOfContainer(slideDirection(initialState, targetState)) },
                popEnterTransition = { slideIntoContainer(SlideDirection.End) },
                popExitTransition = { slideOutOfContainer(SlideDirection.End) }) {
                timelineGraph(nav)
                mapGraph(nav)
                settingsGraph(nav)
            }
        }
    }
}

@Composable
private fun BottomBar(route: String?, nav: NavController) {
    fun go(target: String) = nav.navigate(if (target == Routes.MAP) Routes.map() else target) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    Row(
        Modifier.fillMaxWidth().background(Mosimo.bgSurface).drawBehind { drawLine(Mosimo.border, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
            .navigationBarsPadding().height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab("タイムライン", Icons.AutoMirrored.Outlined.List, route == Routes.TIMELINE) { go(Routes.TIMELINE) }
        // Center tab is the raised yellow circle (Figma Nav Bar 3:178).
        val mapActive = route == Routes.MAP
        Column(Modifier.weight(1f).clickable { go(Routes.MAP) }, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.offset(y = (-10).dp).size(52.dp).clip(CircleShape).background(Mosimo.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Place, contentDescription = null, tint = Mosimo.onPrimary)
            }
            Text("状況マップ", Modifier.offset(y = (-6).dp), style = MaterialTheme.typography.labelSmall,
                color = if (mapActive) Mosimo.primary else Mosimo.textTertiary, fontWeight = if (mapActive) FontWeight.Bold else null)
        }
        Tab("設定", Icons.Outlined.Settings, route == Routes.SETTINGS) { go(Routes.SETTINGS) }
    }
}

@Composable
private fun RowScope.Tab(label: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    val color = if (active) Mosimo.textPrimary else Mosimo.textTertiary
    Column(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(icon, contentDescription = null, tint = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = if (active) FontWeight.Bold else null)
    }
}

/** First launch only: nearby devices → notifications → location, then start relaying if nearby was granted. */
@Composable
private fun FirstLaunchPermissions() {
    val context = LocalContext.current
    val location = koinInject<LocationRepository>()
    val prefs = remember { context.getSharedPreferences("app", Context.MODE_PRIVATE) }
    if (prefs.getBoolean("permissionsAsked", false)) return
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

@Composable
private fun RelayPermissionBanner() {
    val context = LocalContext.current
    val mesh = koinInject<MeshCoordinator>()
    val state by mesh.state.collectAsStateWithLifecycle()
    // Re-read on every state tick: the user may grant in system settings and come back.
    if (state.running || MeshPermissions.granted(context)) return
    Banner("中継が止まっています", "近くのデバイスへの権限が必要です", "許可する", Mosimo.warning) { openAppSettings(context) }
}

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
