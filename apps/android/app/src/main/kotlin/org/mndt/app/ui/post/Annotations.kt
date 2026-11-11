package org.mndt.app.ui.post

import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import org.mndt.app.location.LocationRepository
import org.mndt.app.ui.theme.Mosimo
import org.mndt.mesh.Extensions
import org.mndt.mesh.MeshCoordinator
import org.mndt.mesh.Origin
import org.mndt.mesh.PostView
import org.mndt.mesh.Relation
import kotlin.math.roundToLong

/** Annotation posts keyed by target event id. */
fun annotationsByTarget(posts: List<PostView>): Map<String, List<PostView>> =
    posts.mapNotNull { p -> p.ext.annotation?.targetEventId?.let { it to p } }.groupBy({ it.first }, { it.second })

/** Annotations whose target is present are nested under it, so they get no card of their own. */
fun standalone(posts: List<PostView>): List<PostView> {
    val ids = posts.mapTo(HashSet()) { it.id }
    return posts.filter { it.ext.annotation?.targetEventId !in ids }
}

/** Distinct endorsing authors (DID, else Nostr key). */
fun endorsers(annotations: List<PostView>): Set<String> =
    annotations.filter { it.ext.annotation?.relation == Relation.ENDORSEMENT }.mapTo(HashSet()) { it.did ?: it.pubkey }

private fun Location.origin() = Extensions.origin(Origin((latitude * 1e6).roundToLong(), (longitude * 1e6).roundToLong(),
    if (hasAccuracy()) (accuracy * 1000).roundToLong() else null))

/** 「✓ 確認した N」「補足する」「訂正を報告」 for a user post; publishes #annotation posts, with origin when a fix exists. */
@Composable
fun AnnotationActions(target: PostView, annotations: List<PostView>, modifier: Modifier = Modifier) {
    val mesh = koinInject<MeshCoordinator>()
    val location = koinInject<LocationRepository>()
    val state by mesh.state.collectAsStateWithLifecycle()
    val here by location.location.collectAsStateWithLifecycle()
    val endorsed = endorsers(annotations)
    var sent by remember(target.id) { mutableStateOf(false) }
    var writing by remember { mutableStateOf<Relation?>(null) }
    fun send(relation: Relation, text: String) =
        mesh.publish(text, listOfNotNull(Extensions.annotation(relation, target.id), here?.origin()))
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val mine = sent || state.did in endorsed || state.publicKey in endorsed
        Action("確認した ${endorsed.size}", Icons.Outlined.Check, Mosimo.safe, enabled = !mine) {
            sent = true
            send(Relation.ENDORSEMENT, "確認しました：" + target.body().take(20))
        }
        Action("補足する", Icons.Outlined.Edit, Mosimo.textSecondary) { writing = Relation.SUPPLEMENT }
        Action("訂正を報告", Icons.Outlined.Flag, Mosimo.textSecondary) { writing = Relation.CORRECTION }
    }
    writing?.let { relation ->
        AnnotateDialog(if (relation == Relation.SUPPLEMENT) "補足する" else "訂正を報告", onDismiss = { writing = null }) {
            send(relation, it)
            writing = null
        }
    }
}

@Composable
private fun Action(label: String, icon: ImageVector, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.clip(MaterialTheme.shapes.small).background(Mosimo.bgSurface2).clickable(enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.6f).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun AnnotateDialog(title: String, onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Mosimo.bgElevated,
        title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it.take(140) }, Modifier.fillMaxWidth(), minLines = 3,
                supportingText = { Text("${text.length}/140") })
        },
        confirmButton = { TextButton(onClick = { onSend(text.trim()) }, enabled = text.isNotBlank()) { Text("送信") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}
