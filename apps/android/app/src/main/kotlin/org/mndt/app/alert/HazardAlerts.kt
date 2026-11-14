package org.mndt.app.alert

import org.mndt.app.ui.map.distanceMeters
import org.mndt.app.ui.map.regionsContain
import org.mndt.app.ui.map.regionsDistanceMeters
import org.mndt.app.ui.map.supersededJma
import org.mndt.app.ui.post.Category
import org.mndt.app.ui.post.allClear
import org.mndt.app.ui.post.category
import org.mndt.app.ui.post.officialHazard
import org.mndt.app.ui.post.scaleLabel
import org.mndt.app.ui.post.title
import org.mndt.mesh.HazardKind
import org.mndt.mesh.PostView

/** Official notices this recent (by created_at) are alerted: the first sync after install or a long gap must not ring for old ones. */
const val ALERT_WINDOW_SECONDS = 6 * 3600L
/** A user's danger report is alerted only this recent. */
const val NEARBY_WINDOW_SECONDS = 3600L
/** A user's danger report is alerted when its origin or area comes this close to the phone. */
const val NEARBY_METERS = 2_000.0
/** Alerts within this long of the first one fold into its notification as 「ほかN件」. */
const val ALERT_BATCH_MILLIS = 30_000L
/** Severities alerted when the phone is known to be inside the notice's area; outside it nothing is. */
private val inAreaSeverities = setOf("Extreme", "Severe", "Moderate")

/** Notification genres, each with its own settings switch (same split as iOS). */
enum class AlertGenre(val label: String) {
    /** Official, HazardKind.Earthquake. */
    EARTHQUAKE("地震"),
    /** Official, HazardKind.Weather or HazardKind.Cyclone. */
    WEATHER("気象警報・注意報"),
    /** Official, anything else: tsunami, volcano, or no type-specific extension. */
    OTHER_OFFICIAL("その他の公式情報（津波・火山など）"),
    /** Someone's unverified 「［危険］」 post close by. */
    NEARBY_DANGER("近くの危険情報"),
}

data class Alert(val post: PostView, val genre: AlertGenre)

/**
 * Posts to alert on now (same conditions as iOS), never our own nor [handled] ones.
 * - Official (hazard §8) Actual hazards, not all-clears, created within [ALERT_WINDOW_SECONDS] and not replaced by a newer
 *   post of their series (jma §5.3): Extreme, Severe or Moderate when [here] is known and inside the area; nothing when it is
 *   known and outside; only Extreme when the area or the fix is unknown.
 * - Anyone else's 「［危険］」 post that is not an official hazard, created within [NEARBY_WINDOW_SECONDS], whose origin or
 *   area is within [NEARBY_METERS] of a known [here].
 * [here] is null when the fix is unknown or too old.
 */
fun alertCandidates(posts: List<PostView>, me: String, nowSeconds: Long, here: Pair<Double, Double>?, handled: Set<String>): List<Alert> {
    val fresh = posts.filter { it.id !in handled && it.pubkey != me }.mapNotNull { p ->
        if (p.officialHazard) officialGenre(p, nowSeconds, here)?.let { Alert(p, it) }
        else Alert(p, AlertGenre.NEARBY_DANGER).takeIf { nearbyDanger(p, nowSeconds, here) }
    }
    if (fresh.none { it.genre != AlertGenre.NEARBY_DANGER }) return fresh
    // Replacement needs the whole list: the newer post of the series may be one already handled.
    val superseded = supersededJma(posts)
    return fresh.filter { it.post.id !in superseded }
}

private fun officialGenre(p: PostView, nowSeconds: Long, here: Pair<Double, Double>?): AlertGenre? {
    val h = p.ext.hazard ?: return null
    if (h.status != "Actual" || h.allClear || nowSeconds - p.createdAt > ALERT_WINDOW_SECONDS) return null
    val regions = p.ext.regions
    val alerted = if (here != null && regions.isNotEmpty()) regionsContain(regions, here.first, here.second) && h.severity in inAreaSeverities
        else h.severity == "Extreme"
    if (!alerted) return null
    return when (h.kind) {
        is HazardKind.Earthquake -> AlertGenre.EARTHQUAKE
        is HazardKind.Weather, is HazardKind.Cyclone -> AlertGenre.WEATHER
        else -> AlertGenre.OTHER_OFFICIAL
    }
}

private fun nearbyDanger(p: PostView, nowSeconds: Long, here: Pair<Double, Double>?): Boolean {
    if (here == null || p.category() != Category.DANGER || nowSeconds - p.createdAt > NEARBY_WINDOW_SECONDS) return false
    val (lat, lng) = here
    val origin = p.ext.origin?.let { distanceMeters(it.lat / 1e6, it.lon / 1e6, lat, lng) }
    return listOfNotNull(origin, regionsDistanceMeters(p.ext.regions, lat, lng)).any { it <= NEARBY_METERS }
}

/** Official Extreme first, then Severe, Moderate, and users' reports last: the head of a new batch. */
private fun Alert.rank() = if (genre == AlertGenre.NEARBY_DANGER) 3
    else when (post.ext.hazard?.severity) { "Extreme" -> 0; "Severe" -> 1; else -> 2 }

/** One shown notification; later alerts within [ALERT_BATCH_MILLIS] of [startedAt] update it instead of ringing again. */
data class AlertBatch(val notificationId: Int, val startedAt: Long, val head: Alert, val more: Int)

/** The notification to show for [fresh]: [previous] grown by them while its window is open, else a new one headed by the most severe. */
fun nextBatch(previous: AlertBatch?, fresh: List<Alert>, nowMillis: Long): AlertBatch? = when {
    fresh.isEmpty() -> null
    previous != null && nowMillis - previous.startedAt < ALERT_BATCH_MILLIS -> previous.copy(more = previous.more + fresh.size)
    else -> AlertBatch((nowMillis / 1000 % Int.MAX_VALUE).toInt(), nowMillis, fresh.minBy { it.rank() }, fresh.size - 1)
}

/**
 * 「大雨 警戒レベル5」 for an official notice, 「近くの危険情報（利用者の投稿）」 for a user's report (never presented as
 * verified), with 「ほか2件」 when others were folded in.
 */
fun AlertBatch.title(): String {
    val h = head.post.ext.hazard
    val main = if (head.genre == AlertGenre.NEARBY_DANGER || h == null) listOf("近くの危険情報（利用者の投稿）")
        else listOfNotNull(h.title(), h.scales.firstOrNull()?.let(::scaleLabel))
    return (main + listOfNotNull("ほか${more}件".takeIf { more > 0 })).joinToString(" ")
}

/** The head post's first few non-blank lines. */
fun AlertBatch.text(lines: Int = 3): String = head.post.content.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(lines).joinToString("\n")

/** Persisted handled IDs as "createdAt:id"; entries past the longest window can never alert again, so they are dropped. */
fun pruneHandled(entries: Set<String>, nowSeconds: Long): Set<String> =
    entries.filterTo(HashSet()) { e -> e.substringBefore(':').toLongOrNull()?.let { nowSeconds - it <= ALERT_WINDOW_SECONDS } == true }

fun handledIds(entries: Set<String>): Set<String> = entries.mapTo(HashSet()) { it.substringAfter(':') }

fun handledEntry(post: PostView) = "${post.createdAt}:${post.id}"
