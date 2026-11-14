package org.mndt.app.ui.map

import android.content.Context
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
import org.mndt.app.ui.post.connectivity
import org.mndt.mesh.Extensions
import org.mndt.mesh.MeshCoordinator
import org.mndt.mesh.Origin
import org.mndt.mesh.PostView
import org.mndt.mesh.Region
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong
import org.mndt.app.ui.post.latLng
import org.mndt.app.ui.post.allClear
import org.mndt.app.ui.post.officialHazard
import org.mndt.mesh.HazardKind
import org.mndt.mesh.Jma

/** An official hazard notice: shown at every zoom and above ordinary pins. A user's own hazard claim is an ordinary post. */
val PostView.alert get() = ext.hazard?.takeIf { officialHazard }?.allClear == false
/** An official all-clear: the danger is over, so it is an ordinary pin and its area is not drawn. */
val PostView.allClearNotice get() = ext.hazard?.takeIf { officialHazard }?.allClear == true

/**
 * Where a post's pin goes: its own position, else for a hazard the epicentre / volcano / cyclone centre it carries,
 * else a point inside its area (the cell nearest the middle of a geohash union, or the first circle's centre).
 */
fun PostView.mapPoint(): Pair<Double, Double>? = latLng() ?: ext.hazard?.let { h ->
    when (val k = h.kind) {
        is HazardKind.Earthquake -> k.hypocenter?.let { c -> c.lat?.let { lat -> c.lon?.let { lat to it } } }
        is HazardKind.Volcano -> k.volcano?.let { v -> v.lat?.let { lat -> v.lon?.let { lat to it } } }
        is HazardKind.Cyclone -> k.center
        else -> null
    }?.let { (lat, lon) -> lat / 1e6 to lon / 1e6 }
        ?: geohashAnchor(ext.regions.filterIsInstance<Region.Geohash>().map { it.hash })
        ?: ext.regions.filterIsInstance<Region.Circle>().firstOrNull()?.let { it.lat / 1e6 to it.lon / 1e6 }
}

/**
 * Posts drawn on the map: those with a pin position ([mapPoint]), minus annotations whose target is shown (they live in its
 * detail) and JMA posts a newer one of the same series replaces ([supersededJma]).
 */
fun mapPosts(posts: List<PostView>): List<PostView> {
    val ids = posts.mapTo(HashSet()) { it.id }
    val superseded = supersededJma(posts)
    return posts.filter { it.id !in superseded && it.mapPoint() != null && it.ext.annotation?.targetEventId !in ids }
}

private const val R06 = "気象警報・注意報（Ｒ０６）（"

/**
 * jma §5.3: official JMA posts that a newer official post of the same series replaces on the map. Display only: the
 * timeline and detail keep them, and delivery is unchanged. Newer means a later `Control/DateTime`; one that does not
 * parse never replaces nor is replaced. Earthquakes (series Title + Status + EventID) are replaced by a newer post of the
 * same Pref or a newer whole one; R06 warnings (weather §7.1, series Title + Status + 府県予報区等 code) by any newer post.
 */
fun supersededJma(posts: List<PostView>): Set<String> {
    fun series(j: Jma): List<String>? = when {
        j.earthquake -> listOf("earthquake", j.title, j.status, j.eventId)
        j.title.startsWith(R06) -> j.forecastArea?.let { listOf("r06", j.title, j.status, it) }
        else -> null
    }
    class Entry(val id: String, val jma: Jma, val at: Instant)
    return posts.mapNotNull { p ->
        p.ext.jma?.takeIf { p.officialHazard }?.let { j -> runCatching { OffsetDateTime.parse(j.dateTime).toInstant() }.getOrNull()?.let { Entry(p.id, j, it) } }
    }.groupBy { series(it.jma) }.filterKeys { it != null }.values.flatMap { group ->
        group.filter { old -> group.any { new -> new.at > old.at && (!new.jma.earthquake || new.jma.whole || (new.jma.pref != null && new.jma.pref == old.jma.pref)) } }
    }.mapTo(HashSet()) { it.id }
}

/** Shindo values in order (hazard §5 `jp.jma.shindo`) and the zoom from which an earthquake of that shindo shows; unlisted: every zoom. */
private val shindoOrder = listOf("0", "1", "2", "3", "4", "5-", "5+", "6-", "6+", "7")
private val shindoZoom = mapOf("0" to 8.0, "1" to 8.0, "2" to 8.0, "3" to 6.0, "4" to 5.0)
/** Zooms at which some alert appears or disappears ([minZoom]); the area layer is rebuilt only when the camera crosses one. */
val ALERT_ZOOM_STEPS = listOf(0.0, 5.0, 6.0, 8.0)

/**
 * The lowest zoom at which a post's pin and area show. Ordinary posts from [POST_MIN_ZOOM]; alerts at every zoom, except
 * an earthquake with a shindo scale: 1–2 from zoom 8, 3 from 6, 4 from 5, 5- and above everywhere (the highest shindo counts).
 */
val PostView.minZoom: Double get() {
    if (!alert) return POST_MIN_ZOOM
    val hazard = ext.hazard?.takeIf { it.kind is HazardKind.Earthquake } ?: return 0.0
    val top = hazard.scales.filter { it.scheme == "jp.jma.shindo" }.map { shindoOrder.indexOf(it.value) }.filter { it >= 0 }.maxOrNull() ?: return 0.0
    return shindoZoom[shindoOrder[top]] ?: 0.0
}

class MapViewModel(private val mesh: MeshCoordinator, val location: LocationRepository, private val context: Context) : ViewModel() {
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
            connectivity(context),
        )
        sentAttempt = state.value.publishAttempt
        sentId = state.value.lastPublishedId
        sending = true
        mesh.publish(text, ext)
    }
}
