package org.mndt.app.ui.map

import android.location.Location
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.mndt.app.location.LocationRepository
import org.mndt.app.ui.post.Category
import org.mndt.mesh.Extensions
import org.mndt.mesh.MeshCoordinator
import org.mndt.mesh.Origin
import org.mndt.mesh.PostView
import org.mndt.mesh.Region
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong
import org.mndt.app.ui.post.latLng

/** Posts drawn on the map: those with a position (origin or area), minus annotations whose target is shown (they live in its detail). */
fun mapPosts(posts: List<PostView>): List<PostView> {
    val ids = posts.mapTo(HashSet()) { it.id }
    return posts.filter { it.latLng() != null && it.ext.annotation?.targetEventId !in ids }
}

class MapViewModel(private val mesh: MeshCoordinator, val location: LocationRepository) : ViewModel() {
    val state = mesh.state
    var filter by mutableStateOf(emptySet<Category>())
    var selected by mutableStateOf<String?>(null)
    /** Posts of the tapped pin stack shown in the detail panel, newest first; [selected] is the one opened from it. */
    var stack by mutableStateOf(emptyList<String>())
    var hereCollapsed by mutableStateOf(false)
    var handledFocus: String? = null
    var centered = false
    /** Last camera, restored when the MapView is recreated (tab switch). */
    var camera: CameraPosition? = null

    var composing by mutableStateOf(false)
    var picking by mutableStateOf(false)
    var category by mutableStateOf<Category?>(null)
    var detail by mutableStateOf("")
    /** Meters; 0 = no area. */
    var radius by mutableStateOf(0)
    var hours by mutableStateOf(6)
    /** Point placed on the map; null = use the current fix. */
    var picked by mutableStateOf<Pair<Double, Double>?>(null)
    var sending by mutableStateOf(false)
    private var sentAttempt = 0L
    private var sentId: String? = null

    init {
        viewModelScope.launch {
            mesh.state.collect { s ->
                if (!sending || s.publishAttempt == sentAttempt) return@collect
                sending = false
                if (s.lastPublishedId != sentId) composing = false
            }
        }
    }

    fun compose(preset: Category?) {
        category = preset; detail = ""; radius = 0; hours = 6; picked = null; composing = true
    }

    fun publish(fix: Location?, address: String?) {
        val c = category ?: return
        val (lat, lng) = picked ?: fix?.let { it.latitude to it.longitude } ?: return
        // geo §3: #origin is only ever this device's own fix. A point picked on the map is where the report is
        // about, so it travels as #area (a circle; default 200 m when no radius was chosen), never as #origin.
        val accuracy = if (fix?.hasAccuracy() == true) (fix.accuracy * 1000.0).roundToLong() else null
        val ends = Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofHours(hours.toLong()))
        val text = c.prefix + detail.trim().ifEmpty { if (picked == null) address.orEmpty() else "" }
        val areaMeters = if (radius > 0) radius.toLong() else if (picked != null) 200L else 0L
        val ext = listOfNotNull(
            if (picked == null && fix != null) Extensions.origin(Origin(lat.micro(), lng.micro(), accuracy)) else null,
            areaMeters.takeIf { it > 0 }?.let { Extensions.circle(Region.Circle(lat.micro(), lng.micro(), it * 1000L)) },
            Extensions.validity(until = ends.toString()),
        )
        sentAttempt = state.value.publishAttempt
        sentId = state.value.lastPublishedId
        sending = true
        mesh.publish(text, ext)
    }
}
