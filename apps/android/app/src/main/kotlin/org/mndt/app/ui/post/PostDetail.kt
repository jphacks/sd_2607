package org.mndt.app.ui.post

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Verified
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
import org.mndt.core.Attest
import org.mndt.mesh.MeshCoordinator
import org.mndt.mesh.PostView
import org.mndt.mesh.Relation
import java.time.Instant
import java.time.OffsetDateTime

private val sourceNames = mapOf("jma" to "気象庁")
/** BSAF `source:` tag, e.g. `jma` → 「気象庁」. */
fun PostView.bsafSource(): String = ext.bsaf?.source?.ifBlank { null }?.let { sourceNames[it.lowercase()] ?: it } ?: "公的情報"

/**
 * importer §3.2: 「orgName が転載した botDid の情報」. The BSAF `source:` tag is self-declared (bsaf §7), so it is
 * shown only as the post's own claim. Never claims the content is 公式.
 */
@Composable
fun BsafVerification(post: PostView) {
    val importer = post.chain?.takeIf { it.confirmedNow }?.name
    val bot = post.ext.bsaf?.botDid?.let { shortDid(it, "") } ?: "Bot"
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (post.importerAuthorized) {
                Icon(Icons.Outlined.Check, contentDescription = null, tint = Mosimo.safe, modifier = Modifier.size(14.dp))
                Text(" ${importer ?: "転載者"}が転載した $bot の情報", style = MaterialTheme.typography.bodySmall, color = Mosimo.textSecondary)
            } else Text("転載された情報・転載者の認可を確認できません", style = MaterialTheme.typography.bodySmall, color = Mosimo.textTertiary)
        }
        Text("出典の表示: ${post.bsafSource()}（投稿内の申告）", style = MaterialTheme.typography.labelSmall, color = Mosimo.textTertiary)
    }
}

/**
 * attest.chain §4.3: distinguishes confirmed now, confirmed at creation only, revoked, and unconfirmed. Never
 * shows an organization name without the `identity` role and a current confirmation.
 */
@Composable
fun SenderVerification(post: PostView) {
    val chain = post.chain
    val named = { c: org.mndt.core.PublicIngress.ChainView -> c.name?.takeIf { c.identity } }
    val (icon, color, text) = when {
        chain == null -> Triple(Icons.Outlined.Info, Mosimo.textTertiary, "発信者未確認（署名チェーンなし）")
        chain.revocation == Attest.RevocationState.REVOKED -> Triple(Icons.Outlined.Info, Mosimo.danger, "この発信鍵は失効しています")
        chain.confirmedNow -> named(chain)?.let {
            Triple(Icons.Outlined.Verified, Mosimo.safe, "発信者確認済み: $it" + (chain.trustLevel?.let { l -> "（確認度 $l）" } ?: ""))
        } ?: Triple(Icons.Outlined.Verified, Mosimo.textSecondary, "鍵は確認済み（組織名の表示は認められていません）")
        chain.state == Attest.ChainState.VERIFIED && chain.revocation == Attest.RevocationState.REVOKED_LATER ->
            Triple(Icons.Outlined.Info, Mosimo.warning, "作成時点では確認済み・その後に鍵が失効" + (named(chain)?.let { ": $it" } ?: ""))
        chain.state == Attest.ChainState.VERIFIED ->
            Triple(Icons.Outlined.Info, Mosimo.warning, (if (chain.expiredNow == true) "作成時点では確認済み（現在は期限切れ）" else "作成時点では確認済み（現在の有効性は時計未確認）") +
                (named(chain)?.let { ": $it" } ?: ""))
        chain.state == Attest.ChainState.EXPIRED -> Triple(Icons.Outlined.Info, Mosimo.danger, "作成時点で鍵の有効期間外です（発信者未確認）")
        chain.state == Attest.ChainState.UNKNOWN_TIME -> Triple(Icons.Outlined.Info, Mosimo.warning, "端末時計が未確認のため検証できません")
        chain.state == Attest.ChainState.UNVERIFIABLE -> Triple(Icons.Outlined.Info, Mosimo.textTertiary, "信頼していない発行元の証明です")
        else -> Triple(Icons.Outlined.Info, Mosimo.danger, "署名チェーンを検証できません")
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Text(" $text", style = MaterialTheme.typography.bodySmall, color = color)
        }
        chain?.takeIf { it.revocation == Attest.RevocationState.NOT_REVOKED_AS_KNOWN }?.let {
            Text("失効情報は手元の通知時点のものです", style = MaterialTheme.typography.labelSmall, color = Mosimo.textTertiary)
        }
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
    var confirmDelete by remember { mutableStateOf(false) }
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
            Fact(if (post.isPublic) "転載者" else "投稿者", post.senderName ?: shortDid(post.did, post.pubkey))
            post.ext.validity?.let { v ->
                // validity: from <= t < until; an omitted bound is unknown, never inferred.
                Fact("情報の有効期間", listOfNotNull(v.from?.let { endsAt(it) + " から" }, v.until?.let { endsAt(it) + " まで" }).joinToString("・").ifEmpty { "不明" })
            }
            if (post.isPublic) {
                BsafVerification(post)
                post.publicAuthorization?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Mosimo.textTertiary) }
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Check, contentDescription = null, tint = Mosimo.safe, modifier = Modifier.size(14.dp))
                Text(" 署名検証済み", style = MaterialTheme.typography.bodySmall, color = Mosimo.safe)
            }
            if (post.ext.hasChain || post.chain != null) SenderVerification(post)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onShowOnMap != null && post.latLng() != null) OutlinedButton(onClick = onShowOnMap) { Text("マップで見る") }
            if (post.pubkey != state.publicKey) TextButton(onClick = { confirmBlock = true }) { Text("ブロック", color = Mosimo.danger) }
            else if (!post.volatile) TextButton(onClick = { confirmDelete = true }) { Text("削除を依頼", color = Mosimo.danger) }
        }
        HorizontalDivider(color = Mosimo.border)
        if (post.isPublic) Text("確認した ${endorsers(annotations).size}人", style = MaterialTheme.typography.bodySmall, color = Mosimo.safe)
        else AnnotationActions(post, annotations)
        annotations.filter { it.ext.annotation?.relation != Relation.ENDORSEMENT }.forEach { AnnotationItem(it, post) }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        containerColor = Mosimo.bgElevated,
        title = { Text("この投稿の削除を依頼しますか？") },
        text = { Text("削除要求を署名して配送し、この端末では表示・中継をやめます。既に受け取った端末やサーバーから確実に消えるとは限りません。") },
        confirmButton = {
            TextButton(onClick = { confirmDelete = false; mesh.delete(post.id); onBlocked() }) { Text("削除を依頼", color = Mosimo.danger) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("キャンセル") } },
    )
    if (confirmBlock) AlertDialog(
        onDismissRequest = { confirmBlock = false },
        containerColor = Mosimo.bgElevated,
        title = { Text("この投稿者をブロックしますか？") },
        text = { Text("この発信鍵の投稿はすべて表示されず、中継もしません。相手の端末との通信は止めません。") },
        confirmButton = {
            TextButton(onClick = { confirmBlock = false; mesh.block(post.pubkey); onBlocked() }) { Text("ブロック", color = Mosimo.danger) }
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
