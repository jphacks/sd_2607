package org.mndt.app.ui.map

import android.Manifest
import android.location.Location
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import org.mndt.app.ui.post.dateTime
import org.mndt.app.ui.post.shortDid
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import org.maplibre.android.geometry.LatLng
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.mndt.app.ui.Banner
import org.mndt.app.ui.MapKey
import org.mndt.app.ui.openAppSettings
import org.mndt.app.ui.post.Category
import org.mndt.app.ui.post.PostDetail
import org.mndt.app.ui.post.body
import org.mndt.app.ui.post.category
import org.mndt.app.ui.post.distanceFrom
import org.mndt.app.ui.post.latLng
import org.mndt.app.ui.theme.Mosimo
import org.mndt.mesh.MeshState
import org.mndt.mesh.PostView
import org.mndt.mesh.Region

val mapModule = module { viewModel { MapViewModel(get(), get()) } }

fun EntryProviderScope<NavKey>.mapEntries() {
    entry<MapKey> { MapScreen(it.focus) }
}

private const val NEARBY_METERS = 1000f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapScreen(focus: String?, vm: MapViewModel = koinViewModel()) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val activity = LocalActivity.current
    val state by vm.state.collectAsStateWithLifecycle()
    val fix by vm.location.location.collectAsStateWithLifecycle()
    val address by vm.location.address.collectAsStateWithLifecycle()
    val busy by vm.location.busy.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(vm.location.granted()) }
    // Re-check on every return: the user may grant in system settings. Also takes a fresh fix whenever the map opens.
    LifecycleResumeEffect(Unit) { granted = vm.location.granted(); vm.location.refresh(); onPauseOrDispose {} }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = vm.location.granted()
        if (granted) vm.location.refresh()
        else if (activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)) openAppSettings(context)
    }
    fun askLocation() = ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))

    val handle = remember { MapHandle() }
    val all = remember(state.posts) { mapPosts(state.posts) }
    val shown = remember(all, vm.filter) { if (vm.filter.isEmpty()) all else all.filter { it.category() in vm.filter } }
    val regions = remember(shown) {
        featureCollection(shown.flatMap { p ->
            val color = (p.category()?.color ?: Mosimo.info).hex()
            p.ext.regions.mapNotNull { r ->
                when (r) {
                    is Region.Circle -> circleRing(r.latitudeMicrodegrees / 1e6, r.longitudeMicrodegrees / 1e6, r.radiusMillimeters / 1000.0)
                    is Region.Geohash -> runCatching { boundsRing(geohashBounds(r.hash)) }.getOrNull()
                }?.let { polygonFeature(it, color) }
            }
        })
    }
    val me = remember(fix) {
        featureCollection(fix?.let { f ->
            listOfNotNull(f.takeIf { it.hasAccuracy() }?.let { polygonFeature(circleRing(it.latitude, it.longitude, it.accuracy.toDouble()), Mosimo.info.hex()) },
                pointFeature(f.latitude, f.longitude))
        }.orEmpty())
    }
    val map = handle.map
    LaunchedEffect(map) { if (map != null) snapshotFlow { handle.tick.intValue }.collect { vm.camera = map.cameraPosition } }
    LaunchedEffect(map, fix != null) {
        val f = fix ?: return@LaunchedEffect
        if (map != null && !vm.centered) { vm.centered = true; handle.moveTo(f.latitude, f.longitude, 14.0) }
    }
    LaunchedEffect(focus, map, state.posts) {
        if (focus == null || focus == vm.handledFocus || map == null) return@LaunchedEffect
        val post = state.posts.firstOrNull { it.id == focus } ?: return@LaunchedEffect
        vm.handledFocus = focus
        post.latLng()?.let { (lat, lng) -> vm.centered = true; handle.moveTo(lat, lng, 15.0, 0.5) }
        vm.stack = listOf(focus); vm.selected = focus
    }
    val selected = vm.selected?.let { id -> state.posts.firstOrNull { it.id == id } }
    val stacked = remember(vm.stack, state.posts) { vm.stack.mapNotNull { id -> state.posts.firstOrNull { it.id == id } } }
    // Drops posts that left the list (e.g. their author was blocked from the panel).
    LaunchedEffect(vm.selected, selected == null) { if (vm.selected != null && selected == null && state.posts.isNotEmpty()) vm.selected = null }
    fun closePanel() { vm.stack = emptyList(); vm.selected = null }
    val point = vm.picked ?: fix?.let { it.latitude to it.longitude }
    LaunchedEffect(vm.composing) { if (vm.composing) point?.let { (lat, lng) -> handle.moveTo(lat, lng, 15.0, 0.75) } }

    fun pick() {
        vm.composing = false; vm.picking = true
        // Always re-centre with zero padding: the crosshair is the view centre, which must equal the camera target.
        (point ?: handle.center())?.let { (lat, lng) -> handle.moveTo(lat, lng, if (point != null) 15.0 else handle.map?.cameraPosition?.zoom) }
    }
    fun endPick(confirm: Boolean) {
        if (confirm) handle.center()?.let { vm.picked = it }
        vm.picking = false; vm.composing = true
    }
    BackHandler(vm.picking) { endPick(false) }

    Box(Modifier.fillMaxSize().background(Mosimo.bgBase)) {
        val groups = pinGroups(handle, shown)
        fun open(ids: List<String>) { vm.stack = ids; vm.selected = ids.singleOrNull() }
        MapLibreView(handle, regions, me, onClick = { lat, lng ->
            if (vm.picking) handle.moveTo(lat, lng)
            else handle.map?.projection?.toScreenLocation(LatLng(lat, lng))?.let { pt -> density.pinAt(groups, pt.x, pt.y) }?.let { open(it.ids) }
        }, modifier = Modifier.fillMaxSize(), camera = vm.camera)
        if (map != null) Box(Modifier.fillMaxSize()) {
            Pins(handle, groups, ::open)
            if (vm.composing) point?.let { (lat, lng) ->
                Text("この位置に投稿されます", Modifier.at(handle, lat, lng).padding(bottom = 14.dp).clip(RoundedCornerShape(8.dp))
                    .background(Mosimo.bgBase.copy(alpha = 0.85f)).border(1.dp, Mosimo.info, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = Mosimo.info)
            }
        }
        if (vm.picking) Icon(Icons.Filled.Place, null, Modifier.align(Alignment.Center).offset(y = (-20).dp).size(40.dp), tint = Mosimo.info)

        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding()) {
            if (!granted) Banner("位置情報の権限がありません", "地図は使えます。投稿時は地図で位置を指定します", "許可する", Mosimo.warning) { askLocation() }
            if (vm.picking) Banner("投稿する位置を選ぶ", "地図を動かすかタップして、中央の印を合わせてください", null)
            else {
                Connectivity(state)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Category.entries.forEach { c -> MosimoChip(c.label, c in vm.filter) { vm.filter = if (c in vm.filter) vm.filter - c else vm.filter + c } }
                }
                Legend(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                if (state.posts.isNotEmpty() && all.isEmpty()) Hint("位置付きの投稿はまだありません")
                else if (all.isNotEmpty() && shown.isEmpty()) Hint("この種類の投稿はありません")
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                Text("地理院タイル", Modifier.background(Mosimo.bgBase.copy(alpha = 0.6f)).padding(horizontal = 4.dp, vertical = 1.dp),
                    fontSize = 10.sp, color = Mosimo.textSecondary)
                Spacer(Modifier.weight(1f))
                SmallFloatingActionButton({
                    if (!granted) askLocation()
                    fix?.let { handle.moveTo(it.latitude, it.longitude, 15.0) }
                    vm.location.refresh()
                }, containerColor = Mosimo.bgSurface, contentColor = Mosimo.textPrimary) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Mosimo.info)
                    else Icon(Icons.Filled.MyLocation, "現在地")
                }
            }
            if (vm.picking) Row(Modifier.fillMaxWidth().background(Mosimo.bgSurface).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton({ endPick(false) }, Modifier.weight(1f).height(52.dp)) { Text("キャンセル", color = Mosimo.textPrimary) }
                Button({ endPick(true) }, Modifier.weight(1f).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = Mosimo.primary, contentColor = Mosimo.onPrimary)) {
                    Text("この位置にする")
                }
            } else HereSheet(fix, address, granted, busy, all.count { (it.distanceFrom(fix) ?: Float.MAX_VALUE) <= NEARBY_METERS }, vm.hereCollapsed,
                { vm.hereCollapsed = it }, vm::compose)
        }
    }

    if (selected != null || stacked.isNotEmpty()) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        MapSheet(::closePanel, sheet) {
            PostPanel(selected, stacked, onSelect = { vm.selected = it }, onBack = { vm.selected = null },
                onClose = { scope.launch { sheet.hide() }.invokeOnCompletion { closePanel() } })
        }
    }
    if (vm.composing) Composer(vm, fix, address, state.network && state.onlineActive, ::pick)
}

private val sheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
private fun Modifier.sheet() = fillMaxWidth().clip(sheetShape).background(Mosimo.bgSurface).border(1.dp, Mosimo.border, sheetShape)

/** Content of the modal sheet for a tapped pin: a stack lists its posts; one post shows its detail. Back returns to the list. */
@Composable
private fun PostPanel(selected: PostView?, stack: List<PostView>, onSelect: (String) -> Unit, onBack: () -> Unit, onClose: () -> Unit) {
    BackHandler(selected != null && stack.size > 1, onBack)
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected != null && stack.size > 1) IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "一覧に戻る", tint = Mosimo.textPrimary) }
            Text(if (selected != null) "投稿" else "この地点の投稿 ${stack.size}件", Modifier.weight(1f).padding(start = 12.dp),
                style = MaterialTheme.typography.titleMedium, color = Mosimo.textPrimary)
            IconButton(onClose) { Icon(Icons.Filled.Close, "閉じる", tint = Mosimo.textSecondary) }
        }
        if (selected != null) PostDetail(selected, Modifier.weight(1f, fill = false).padding(horizontal = 16.dp), onShowOnMap = null)
        else LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(bottom = 8.dp)) {
            items(stack, key = { it.id }) { p -> StackRow(p) { onSelect(p.id) } }
        }
    }
}

@Composable
private fun StackRow(post: PostView, onClick: () -> Unit) {
    val c = post.category()
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(c?.color ?: Mosimo.info), contentAlignment = Alignment.Center) {
            Icon(icon(c), null, Modifier.size(16.dp), tint = Mosimo.bgBase)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(post.body().lineSequence().first().ifBlank { c?.label.orEmpty() }, style = MaterialTheme.typography.bodyMedium, color = Mosimo.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${dateTime(post.createdAt)}・${shortDid(post.did, post.pubkey)}", style = MaterialTheme.typography.bodySmall, color = Mosimo.textTertiary)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Mosimo.textTertiary)
    }
}

/** Persistent bottom card (Figma 5:233): where am I, how many posts nearby, quick-post chips. */
@Composable
private fun HereSheet(fix: Location?, address: String?, granted: Boolean, busy: Boolean, nearby: Int, collapsed: Boolean,
    onCollapse: (Boolean) -> Unit, onCompose: (Category?) -> Unit) {
    val threshold = with(LocalDensity.current) { 24.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    // Swipe anywhere on the card: down leaves only the title and location, up brings the chips and CTA back. The handle also toggles on tap.
    Column(Modifier.sheet().draggable(rememberDraggableState { drag += it }, Orientation.Vertical, onDragStarted = { drag = 0f },
        onDragStopped = { if (drag > threshold) onCollapse(true) else if (drag < -threshold) onCollapse(false) })
        .animateContentSize().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().clickable(onClickLabel = if (collapsed) "広げる" else "たたむ") { onCollapse(!collapsed) }
            .padding(top = 10.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(Mosimo.borderStrong))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("今、ここの状況は？", style = MaterialTheme.typography.titleLarge, color = Mosimo.textPrimary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Place, null, Modifier.size(14.dp), tint = Mosimo.textSecondary)
                    Text(when {
                        fix != null -> listOfNotNull("${address ?: coordsLabel(fix.latitude, fix.longitude)} 付近",
                            fix.takeIf { it.hasAccuracy() }?.let { "精度 ${accuracyLabel(it.accuracy)}" }).joinToString("・")
                        !granted -> "位置情報なし・地図で位置を指定して投稿できます"
                        busy -> "現在地を取得中…"
                        else -> "現在地を取得できません・地図で位置を指定できます"
                    }, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodySmall, color = Mosimo.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (fix != null) Row(Modifier.clip(RoundedCornerShape(8.dp)).background(Mosimo.bgSurface2).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Groups, null, Modifier.size(16.dp), tint = Mosimo.textSecondary)
                Text("周辺 ${nearby}件", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodySmall, color = Mosimo.textSecondary)
            }
        }
        if (!collapsed) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Category.SAFE, Category.CAUTION, Category.DANGER, Category.SHELTER, Category.BLOCKED).forEach { c -> MosimoChip(c.label, false) { onCompose(c) } }
        }
        if (!collapsed) Button({ onCompose(null) }, Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = Mosimo.primary, contentColor = Mosimo.onPrimary)) {
            Icon(Icons.Filled.Place, null)
            Text("この場所の状況を投稿する", Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun Connectivity(state: MeshState) {
    val mesh = "メッシュ接続中 ${state.neighbors.size}台"
    if (state.network && state.onlineActive) Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("オンライン", Mosimo.safe)
        Pill(mesh, Mosimo.info)
    } else Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(MaterialTheme.shapes.medium).background(Mosimo.info.copy(alpha = 0.18f))
            .border(1.dp, Mosimo.info.copy(alpha = 0.4f), MaterialTheme.shapes.medium).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.WifiOff, null, tint = Mosimo.info)
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text("オフライン中でも投稿・受信できます", style = MaterialTheme.typography.titleMedium, color = Mosimo.textPrimary)
            Text("近くの端末へ中継して届けます", style = MaterialTheme.typography.bodySmall, color = Mosimo.textSecondary)
        }
        Pill(mesh, Mosimo.info)
    }
}

@Composable
private fun Pill(text: String, color: Color) = Row(
    Modifier.clip(CircleShape).background(Mosimo.bgBase.copy(alpha = 0.85f)).border(1.dp, color.copy(alpha = 0.5f), CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Box(Modifier.size(6.dp).clip(CircleShape).background(color))
    Text(text, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelSmall, color = Mosimo.textPrimary)
}

@Composable
private fun Hint(text: String) = Text(text, Modifier.padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(8.dp))
    .background(Mosimo.bgSurface.copy(alpha = 0.9f)).padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = Mosimo.textSecondary)

@Composable
private fun Legend(modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(Mosimo.bgSurface.copy(alpha = 0.9f)).clickable { open = !open }.padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("凡例", style = MaterialTheme.typography.labelSmall, color = Mosimo.textSecondary)
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (open) "凡例を閉じる" else "凡例を開く", Modifier.size(16.dp), tint = Mosimo.textSecondary)
        }
        if (open) listOf(Mosimo.danger to "危険", Mosimo.warning to "注意", Mosimo.safe to "安全・避難所", Mosimo.info to "自分・周辺端末").forEach { (c, l) ->
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                Text(l, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelSmall, color = Mosimo.textPrimary)
            }
        }
    }
}

private fun icon(c: Category?): ImageVector = when (c) {
    Category.SAFE -> Icons.Filled.Check
    Category.CAUTION -> Icons.Filled.PriorityHigh
    Category.DANGER -> Icons.Filled.Warning
    Category.SHELTER -> Icons.Filled.Home
    Category.BLOCKED -> Icons.Filled.Block
    Category.SUPPLIES -> Icons.Filled.Inventory2
    Category.HELP -> Icons.Filled.Sos
    null -> Icons.Filled.ChatBubble
}

/** Icon circle in the category colour over a dark label pill (Figma 5:233); the icon centre (16dp) is the anchor. */
@Composable
fun Pin(post: PostView, modifier: Modifier, count: Int = 1, onClick: () -> Unit) {
    val c = post.category()
    val color = c?.color ?: Mosimo.info
    val label = post.body().lineSequence().first().trim().let { if (it.length > 10) it.take(10) + "…" else it }.ifEmpty { c?.label.orEmpty() }
    // Semantics only, no pointer input: touches fall through to the map (see pinAt).
    Column(modifier.semantics { onClick(c?.label ?: "投稿") { onClick(); true } }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Box(Modifier.size(32.dp).clip(CircleShape).background(color).border(2.dp, Mosimo.bgBase, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon(c), c?.label ?: "投稿", Modifier.size(18.dp), tint = Mosimo.bgBase)
            }
            if (count > 1) Text("$count", Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-6).dp).clip(CircleShape).background(Mosimo.textPrimary)
                .padding(horizontal = 5.dp), style = MaterialTheme.typography.labelSmall, color = Mosimo.bgBase, fontWeight = FontWeight.Bold)
        }
        if (label.isNotEmpty()) Text(label, Modifier.padding(top = 4.dp).clip(RoundedCornerShape(6.dp)).background(Mosimo.bgBase.copy(alpha = 0.88f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall, color = Mosimo.textPrimary, maxLines = 1)
    }
}

/** Selected = primary yellow (the only place besides the CTA where it appears). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MosimoChip(label: String, selected: Boolean, onClick: () -> Unit) = FilterChip(
    selected, onClick, { Text(label) },
    colors = FilterChipDefaults.filterChipColors(containerColor = Mosimo.bgSurface.copy(alpha = 0.92f), labelColor = Mosimo.textPrimary,
        selectedContainerColor = Mosimo.primary, selectedLabelColor = Mosimo.onPrimary),
    border = FilterChipDefaults.filterChipBorder(true, selected, borderColor = Mosimo.border, selectedBorderColor = Mosimo.primary),
)
