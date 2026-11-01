package org.mndt.app.ui.post

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import org.mndt.app.location.LocationRepository
import org.mndt.app.ui.theme.Mosimo
import org.mndt.mesh.MeshCoordinator
import org.mndt.mesh.PostView
import org.mndt.mesh.Relation
import java.time.Instant
import java.time.OffsetDateTime

private val sourceNames = mapOf("jma" to "気象庁")
/** BSAF `source:` tag, e.g. `jma` → 「気象庁」. */
fun PostView.bsafSource(): String = ext.bsaf?.tags?.firstOrNull { it.startsWith("source:") }?.removePrefix("source:")?.ifBlank { null }
    ?.let { sourceNames[it.lowercase()] ?: it } ?: "公的情報"

/** Import-time importer authorization only; never claims the content is 公式. */
@Composable
fun BsafVerification(post: PostView) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (post.importerAuthorized) {
            Icon(Icons.Outlined.Check, contentDescription = null, tint = Mosimo.safe, modifier = Modifier.size(14.dp))
            Text(" ${post.bsafSource()}（BSAF転載）・転載者認可済", style = MaterialTheme.typography.bodySmall, color = Mosimo.textSecondary)
        } else Text("${post.bsafSource()}（BSAF転載）・認可を確認できません", style = MaterialTheme.typography.bodySmall, color = Mosimo.textTertiary)
    }
}

@Composable
fun CategoryBadge(category: Category) = Text(category.label, Modifier.clip(MaterialTheme.shapes.small).background(category.color.copy(alpha = 0.16f))
    .padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium, color = category.color, fontWeight = FontWeight.Bold)

/** Tinted square icon + label heading a card: BSAF type or 「周辺の投稿」. */
@Composable
fun KindLabel(post: PostView) {
    val (icon, color, label) = if (post.isPublic) Triple(Icons.Outlined.Info, post.priorityColor(), bsafLabel(post.ext.bsaf?.type))
    else Triple(Icons.Outlined.Place, Mosimo.info, "周辺の投稿")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(26.dp).clip(MaterialTheme.shapes.small).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

private fun endsAt(value: String): String = runCatching { Instant.parse(value).epochSecond }
    .recoverCatching { OffsetDateTime.parse(value).toEpochSecond() }.map(::dateTime).getOrDefault(value)

/** Shared by the timeline (full screen) and the map (bottom sheet). [onShowOnMap] is null where already on the map. Scrolls itself. */
@Composable
fun PostDetail(post: PostView, modifier: Modifier = Modifier, onShowOnMap: (() -> Unit)? = null, onBlocked: () -> Unit = {}) {
    val mesh = koinInject<MeshCoordinator>()
    val state by mesh.state.collectAsStateWithLifecycle()
    val here by koinInject<LocationRepository>().location.collectAsStateWithLifecycle()
    val annotations = remember(state.posts, post.id) { annotationsByTarget(state.posts)[post.id].orEmpty().sortedBy { it.createdAt } }
    var confirmBlock by remember { mutableStateOf(false) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KindLabel(post)
            post.category()?.let { CategoryBadge(it) }
        }
        Text(if (post.isPublic) post.content else post.body(), style = MaterialTheme.typography.bodyLarge, color = Mosimo.textPrimary)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Fact("時刻", dateTime(post.eventTime()))
            if (post.isPublic) Fact("転載", dateTime(post.createdAt))
            post.distanceFrom(here)?.let { Fact("距離", distanceLabel(it) + "先") }
            Fact("経路", post.routeLabel())
            Fact(if (post.isPublic) "転載者" else "投稿者", shortDid(post.did, post.pubkey))
            post.ext.validityEndsAt?.let { Fact("有効期限", endsAt(it)) }
            if (post.isPublic) {
                BsafVerification(post)
                post.publicAuthorization?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Mosimo.textTertiary) }
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Check, contentDescription = null, tint = Mosimo.safe, modifier = Modifier.size(14.dp))
                Text(" 署名検証済み", style = MaterialTheme.typography.bodySmall, color = Mosimo.safe)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onShowOnMap != null && post.latLng() != null) OutlinedButton(onClick = onShowOnMap) { Text("マップで見る") }
            if (post.did != null && post.did != state.did) TextButton(onClick = { confirmBlock = true }) { Text("ブロック", color = Mosimo.danger) }
        }
        HorizontalDivider(color = Mosimo.border)
        if (post.isPublic) Text("確認した ${endorsers(annotations).size}人", style = MaterialTheme.typography.bodySmall, color = Mosimo.safe)
        else AnnotationActions(post, annotations)
        annotations.filter { it.ext.annotation?.relation != Relation.ENDORSEMENT }.forEach { AnnotationItem(it, post) }
    }
    if (confirmBlock) AlertDialog(
        onDismissRequest = { confirmBlock = false },
        containerColor = Mosimo.bgElevated,
        title = { Text("この投稿者をブロックしますか？") },
        text = { Text("この人の投稿はすべて表示されなくなります。") },
        confirmButton = {
            TextButton(onClick = { confirmBlock = false; post.did?.let(mesh::block); onBlocked() }) { Text("ブロック", color = Mosimo.danger) }
        },
        dismissButton = { TextButton(onClick = { confirmBlock = false }) { Text("キャンセル") } },
    )
}

@Composable
private fun Fact(label: String, value: String) = Row {
    Text(label, Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall, color = Mosimo.textTertiary)
    Text(value, style = MaterialTheme.typography.bodySmall, color = Mosimo.textSecondary)
}

@Composable
private fun AnnotationItem(annotation: PostView, target: PostView) {
    val (label, color) = when {
        annotation.ext.annotation?.relation == Relation.SUPPLEMENT -> "補足" to Mosimo.info
        (annotation.did ?: annotation.pubkey) == (target.did ?: target.pubkey) -> "投稿者本人による訂正" to Mosimo.warning
        else -> "他の人からの訂正報告" to Mosimo.warning
    }
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(Mosimo.bgSurface2).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold)
        Text(annotation.content, style = MaterialTheme.typography.bodyMedium, color = Mosimo.textPrimary)
        Text("${shortDid(annotation.did, annotation.pubkey)} · ${dateTime(annotation.createdAt)}", style = MaterialTheme.typography.labelSmall,
            color = Mosimo.textTertiary)
    }
}
