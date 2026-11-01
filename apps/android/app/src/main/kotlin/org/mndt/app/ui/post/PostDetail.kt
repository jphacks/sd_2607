package org.mndt.app.ui.post

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.mndt.mesh.PostView

/** Shared by the timeline (full screen) and the map (bottom sheet). [onShowOnMap] is null where already on the map. */
@Composable
fun PostDetail(post: PostView, modifier: Modifier = Modifier, onShowOnMap: (() -> Unit)? = null) {
    Column(modifier) { Text(post.content) }
}
