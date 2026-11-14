package org.mndt.app.alert

import org.mndt.app.ui.post.allClear
import org.mndt.app.ui.post.importerAuthorized
import org.mndt.mesh.HazardKind
import org.mndt.mesh.PostView
import org.mndt.mesh.Region
import java.time.OffsetDateTime
import kotlin.math.abs

/** public §8.1: earthquakes group when their onsets are this close. */
const val GROUP_ONSET_SECONDS = 60L
/** public §8.1: weather notices group when their created_at are this close. */
const val GROUP_CREATED_SECONDS = 30 * 60L

/** CAP severity order (hazard §3); a value outside it ranks as Unknown. */
fun severityRank(severity: String?): Int = when (severity) { "Extreme" -> 4; "Severe" -> 3; "Moderate" -> 2; "Minor" -> 1; else -> 0 }

/** An authorized Bot re-post (`public.bsaf` with `original`). */
val PostView.botRePost get() = importerAuthorized && ext.bsaf?.imported == true
/** An authorized feed re-post (`public#source` declaring `feed`). */
val PostView.feedRePost get() = importerAuthorized && ext.feed != null && ext.bsaf == null

/**
 * public §8.1: posts of one event, likely the same though nothing proves it. [representative] is the most severe, then a
 * feed re-post, then the newest; [members] lists feed re-posts first, then Bot re-posts, each most severe and newest first.
 * Display and notification only: never a reason to drop, stop or reprioritize a post.
 */
class HazardGroup(posts: List<PostView>) {
    val representative: PostView = posts.sortedWith(representativeOrder).first()
    val members: List<PostView> = posts.sortedWith(memberOrder)
}

/**
 * public §8.1: whether [x] and [y] belong together. Both authorized re-posts with the hazard extension, the same `status`
 * and AllClear membership, and either two earthquake events whose onsets are within [GROUP_ONSET_SECONDS] (any route and
 * region), or a feed and a Bot re-post of weather sharing a phenomenon, created within [GROUP_CREATED_SECONDS] with
 * overlapping areas.
 */
fun related(x: PostView, y: PostView): Boolean {
    if (x.id == y.id || !x.groupable || !y.groupable) return false
    val hx = x.ext.hazard!!; val hy = y.ext.hazard!!
    if (hx.status != hy.status || hx.allClear != hy.allClear) return false
    val kx = hx.kind; val ky = hy.kind
    return when {
        kx is HazardKind.Earthquake && ky is HazardKind.Earthquake -> {
            if (kx.kind != "event" || ky.kind != "event") return false
            val ox = instant(hx.onset) ?: return false
            val oy = instant(hy.onset) ?: return false
            abs(ox - oy) <= GROUP_ONSET_SECONDS
        }
        kx is HazardKind.Weather && ky is HazardKind.Weather -> x.feedRePost != y.feedRePost &&
            kx.phenomena.any { it in ky.phenomena } && abs(x.createdAt - y.createdAt) <= GROUP_CREATED_SECONDS && cellsOverlap(x, y)
        else -> false
    }
}

private val PostView.groupable get() = ext.hazard != null && (botRePost || feedRePost)

/** public §8.1: the groups of two or more posts in [posts], joined through [related] (connected components). */
fun hazardGroups(posts: List<PostView>): List<HazardGroup> {
    val candidates = posts.filter { it.groupable }
    if (candidates.size < 2) return emptyList()
    val parent = IntArray(candidates.size) { it }
    fun find(i: Int): Int { var r = i; while (parent[r] != r) r = parent[r]; parent[i] = r; return r }
    for (i in candidates.indices) for (j in i + 1 until candidates.size)
        if (related(candidates[i], candidates[j])) parent[find(i)] = find(j)
    return candidates.indices.groupBy(::find).values.filter { it.size >= 2 }.map { indices -> HazardGroup(indices.map { candidates[it] }) }
}

/** Groups by member ID. */
fun hazardGroupsById(posts: List<PostView>): Map<String, HazardGroup> =
    hazardGroups(posts).flatMap { g -> g.members.map { it.id to g } }.toMap()

/** Posts not shown on their own on the map and in lists: every member but the representative. */
fun groupedAway(groups: Collection<HazardGroup>): Set<String> =
    groups.flatMapTo(HashSet()) { g -> g.members.filter { it.id != g.representative.id }.map { it.id } }

/** Most severe, then a feed re-post, then newest. */
internal val representativeOrder = compareByDescending<PostView> { severityRank(it.ext.hazard?.severity) }
    .thenByDescending { it.feedRePost }.thenByDescending { it.createdAt }
private val memberOrder = compareByDescending<PostView> { it.feedRePost }
    .thenByDescending { severityRank(it.ext.hazard?.severity) }.thenByDescending { it.createdAt }

private fun instant(text: String?): Long? = text?.let { runCatching { OffsetDateTime.parse(it).toEpochSecond() }.getOrNull() }

/** geo#area overlap by geohash: a cell of one is a prefix of a cell of the other. Posts without cells never group as weather. */
private fun cellsOverlap(a: PostView, b: PostView): Boolean {
    val ca = a.ext.regions.filterIsInstance<Region.Geohash>().map { it.hash }
    val cb = b.ext.regions.filterIsInstance<Region.Geohash>().map { it.hash }
    return ca.any { x -> cb.any { y -> x.startsWith(y) || y.startsWith(x) } }
}
