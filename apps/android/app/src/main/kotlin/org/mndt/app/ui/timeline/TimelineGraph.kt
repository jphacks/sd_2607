@file:OptIn(ExperimentalMaterial3Api::class)

package org.mndt.app.ui.timeline

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.mndt.app.location.LocationRepository
import org.mndt.app.ui.Banner
import org.mndt.app.ui.Routes
import org.mndt.app.ui.post.*
import org.mndt.app.ui.theme.Mosimo
import org.mndt.core.ClockConfidence
import org.mndt.mesh.MeshCoordinator
import org.mndt.mesh.PostView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val timelineModule = module {
    viewModel { TimelineViewModel(get(), androidContext().getSharedPreferences("app", Context.MODE_PRIVATE)) }
}

fun NavGraphBuilder.timelineGraph(nav: NavController) {
    composable(Routes.TIMELINE) { TimelineScreen(nav) }
    composable(Routes.POST) { entry -> PostScreen(entry.arguments?.getString("id").orEmpty(), nav) }
}

class TimelineViewModel(private val mesh: MeshCoordinator, private val prefs: SharedPreferences) : ViewModel() {
    val state = mesh.state
    /** Newest first by event time, annotations with a present target folded away. */
    val cards = mesh.state.map { s -> standalone(s.posts).sortedByDescending { it.eventTime() } to annotationsByTarget(s.posts) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<PostView>() to emptyMap())

    /** Previous open time (epoch s); stores now. ponytail: compares author created_at, not local arrival; skewed clocks miscount. */
    fun markOpened(): Long {
        val now = System.currentTimeMillis() / 1000
        return prefs.getLong("timelineOpenedAt", now).also { prefs.edit { putLong("timelineOpenedAt", now) } }
    }
    fun refresh() = mesh.requestPublicInformation()
    fun trustClock() = mesh.setClockConfidence(ClockConfidence.TRUSTED)
}

private val dayFormat = DateTimeFormatter.ofPattern("M/d (E)", Locale.JAPAN)
private fun day(epochSeconds: Long) = Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).toLocalDate()
private fun dayLabel(day: LocalDate): String = when (day) {
    LocalDate.now() -> "きょう "
    LocalDate.now().minusDays(1) -> "きのう "
    else -> ""
} + dayFormat.format(day)

/** First line as the card title, the rest as body. */
private fun String.titleAndRest(): Pair<String, String> = trim().let { it.substringBefore('\n') to it.substringAfter('\n', "").trim() }

@Composable
private fun TimelineScreen(nav: NavController, vm: TimelineViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val (all, annotations) = vm.cards.collectAsStateWithLifecycle().value
    val location = koinInject<LocationRepository>()
    val here by location.location.collectAsStateWithLifecycle()
    val since = rememberSaveable { vm.markOpened() }
    var publicOnly by rememberSaveable { mutableStateOf(false) }
    val cards = if (publicOnly) all.filter { it.isPublic } else all
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    LaunchedEffect(Unit) { location.refresh() }

    Column(Modifier.fillMaxSize()) {
        Text("タイムライン", Modifier.statusBarsPadding().padding(start = 16.dp, top = 16.dp, end = 16.dp), style = MaterialTheme.typography.titleLarge,
            color = Mosimo.textPrimary)
        FilterChip(publicOnly, { publicOnly = !publicOnly }, { Text("公式情報のみ") }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Mosimo.primary, selectedLabelColor = Mosimo.onPrimary))
        if (state.clockConfidence != ClockConfidence.TRUSTED)
            Banner("時計未確認のため検証できません", null, "信頼する", Mosimo.warning) { vm.trustClock() }
        state.lastReceivedAtMillis?.let { at ->
            val minutes = (now - at) / 60_000
            val fresh = all.count { it.createdAt > since }
            Banner("${if (state.lastReceivedOnline) "オンライン" else "メッシュ経由"}で${if (minutes < 1) "たった今" else "${minutes}分前に"}更新", null,
                if (fresh > 0) "新着 ${fresh}件" else null) { scope.launch { list.animateScrollToItem(0) } }
        }
        PullToRefreshBox(state.publicBusy, vm::refresh, Modifier.weight(1f)) {
            if (cards.isEmpty()) Empty(
                if (state.publicBusy) "読み込み中…" else if (publicOnly) "公的情報はまだありません" else "まだ投稿がありません",
                if (state.publicBusy) null else "下に引くと公的情報を取得します",
            ) else LazyColumn(Modifier.fillMaxSize(), list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                var last: LocalDate? = null
                cards.forEach { post ->
                    val d = day(post.eventTime())
                    if (d != last) {
                        last = d
                        item(key = "day-$d") { DayHeader(dayLabel(d)) }
                    }
                    item(key = post.id) {
                        Row(Modifier.height(IntrinsicSize.Min).padding(bottom = 12.dp)) {
                            Column(Modifier.width(52.dp).fillMaxHeight()) {
                                Text(hhmm(post.eventTime()), style = Mosimo.number.copy(fontSize = 13.sp), color = Mosimo.textSecondary)
                                Box(Modifier.padding(start = 18.dp, top = 6.dp).width(1.dp).weight(1f).background(Mosimo.border))
                            }
                            PostCard(post, annotations[post.id].orEmpty(), here, nav)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(label: String) = Text(label, Modifier.padding(vertical = 8.dp).clip(MaterialTheme.shapes.extraLarge).background(Mosimo.bgSurface2)
    .padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = Mosimo.textSecondary, fontWeight = FontWeight.Bold)

@Composable
private fun PostCard(post: PostView, annotations: List<PostView>, here: android.location.Location?, nav: NavController) {
    val (title, rest) = (if (post.isPublic) post.content else post.body()).titleAndRest()
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(Mosimo.bgSurface).border(1.dp, Mosimo.border, MaterialTheme.shapes.medium)
            .clickable { nav.navigate(Routes.post(post.id)) }.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindLabel(post)
            Spacer(Modifier.weight(1f))
            post.category()?.let { CategoryBadge(it) }
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = Mosimo.textPrimary)
        if (rest.isNotEmpty()) Text(rest, style = MaterialTheme.typography.bodyMedium, color = Mosimo.textSecondary, maxLines = 3)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (post.isPublic) BsafVerification(post)
                else Text(listOfNotNull(shortDid(post.did, post.pubkey), post.distanceFrom(here)?.let { distanceLabel(it) + "先" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = Mosimo.textTertiary)
                Text(" · ${post.routeLabel()}", style = MaterialTheme.typography.bodySmall, color = Mosimo.textTertiary)
            }
            if (post.isPublic) Link("詳細 >") { nav.navigate(Routes.post(post.id)) }
            else if (post.latLng() != null) Link("マップで見る >") { nav.navigate(Routes.map(post.id)) }
        }
        if (!post.isPublic) AnnotationActions(post, annotations)
    }
}

@Composable
private fun Link(label: String, onClick: () -> Unit) = Text(label, Modifier.clickable(onClick = onClick).padding(4.dp),
    style = MaterialTheme.typography.labelMedium, color = Mosimo.info, fontWeight = FontWeight.Bold)

@Composable
private fun Empty(title: String, detail: String?) = Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = Mosimo.textSecondary)
    detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Mosimo.textTertiary) }
}

@Composable
private fun PostScreen(id: String, nav: NavController) {
    val state by koinInject<MeshCoordinator>().state.collectAsStateWithLifecycle()
    val post = state.posts.firstOrNull { it.id == id }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る", tint = Mosimo.textPrimary) }
            Text("投稿", style = MaterialTheme.typography.titleMedium, color = Mosimo.textPrimary)
        }
        if (post == null) Empty("この投稿は見つかりません", null)
        else PostDetail(post, Modifier.weight(1f), onShowOnMap = { nav.navigate(Routes.map(post.id)) }, onBlocked = { nav.popBackStack() })
    }
}
