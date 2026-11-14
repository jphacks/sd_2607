package org.mndt.app.ui.post

import android.content.Context
import android.location.Location
import android.net.ConnectivityManager
import androidx.compose.ui.graphics.Color
import org.mndt.app.ui.theme.Mosimo
import org.mndt.mesh.PostView
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Our own post extension, not an official dev.jisaku.mosimo type (core §10.3; apps/lexicons/dev/jisaku/bosai/connectivity.json):
 * whether the OS has a network now. Every post this app publishes passes it explicitly.
 */
fun connectivity(context: Context): Map<String, Any?> = mapOf("\$type" to "dev.jisaku.bosai.connectivity",
    "online" to (context.getSystemService(ConnectivityManager::class.java).activeNetwork != null))

/** Situation kind, carried as a plain-text prefix 「［危険］」 so any client can read it (no protocol field). */
enum class Category(val label: String, val color: Color) {
    SAFE("安全", Mosimo.safe), CAUTION("注意", Mosimo.warning), DANGER("危険", Mosimo.danger),
    SHELTER("避難所", Mosimo.safe), BLOCKED("通行不可", Mosimo.warning), SUPPLIES("物資あり", Mosimo.primary),
    HELP("人が必要", Mosimo.danger);
    val prefix get() = "［$label］"
}

private val prefix = Regex("^\\s*[［\\[]([^］\\]]{1,8})[］\\]]\\s*")
/** Category from the text prefix, and the text without it. */
fun PostView.category(): Category? = prefix.find(content)?.groupValues?.get(1)?.let { l -> Category.entries.firstOrNull { it.label == l } }
fun PostView.body(): String = if (category() != null) content.replaceFirst(prefix, "") else content

/** A re-posted BSAF alert (`original` present) or JMA XML message; native BSAF posts of our own are ordinary posts. */
val PostView.isPublic get() = ext.bsaf?.imported == true || ext.jma != null

/**
 * importer §3: the post's own chain, confirmed now against the local roots, ends in an importer authorized for its
 * (Bot, type). Decided from the post alone, so it reads the same however the post arrived (relay, mesh or ingress).
 */
val PostView.importerAuthorized get() = chain?.let { it.confirmedNow && it.importAuthorized } == true

/** attest.chain §4.1: the sender may be shown by organization name only with the `identity` role. */
val PostView.senderName: String? get() = chain?.takeIf { it.identity && it.confirmedNow }?.name

/** BSAF `type:` → short Japanese label; unknown types show the raw value. */
private val bsafLabels = mapOf(
    "earthquake" to "地震情報", "tsunami" to "津波情報", "eruption" to "噴火情報", "ashfall" to "降灰予報", "nankai-trough" to "南海トラフ地震情報",
    "heavy-rain" to "大雨情報", "heavy-rain-warning" to "大雨警報・注意報", "flood-warning" to "洪水警報・注意報",
    "landslide-warning" to "土砂災害警戒情報", "linear-rainband-warning" to "線状降水帯情報", "storm-surge-warning" to "高潮警報・注意報",
    "wave-warning" to "波浪警報・注意報", "wind-warning" to "暴風警報・強風注意報", "typhoon" to "台風情報", "tornado-warning" to "竜巻注意情報",
    "thunderstorm-warning" to "雷注意報", "snow-warning" to "大雪警報・注意報", "significant-snowfall-warning" to "顕著な大雪情報",
    "avalanche-warning" to "なだれ注意報", "snow-accretion-warning" to "着雪注意報", "melting-snow-warning" to "融雪注意報",
    "icing-warning" to "着氷注意報", "frost-warning" to "霜注意報", "low-temperature-warning" to "低温注意報",
    "dry-air-warning" to "乾燥注意報", "dense-fog-warning" to "濃霧注意報", "other-warning" to "その他の警報・注意報",
    "weather-info" to "気象情報",
)
fun bsafLabel(type: String?) = type?.let { bsafLabels[it] ?: it } ?: "公的情報"

/** Heading label of a re-post: the BSAF type, or the JMA `Control/Title` as sent. */
fun PostView.publicLabel(): String = ext.jma?.title ?: bsafLabel(ext.bsaf?.type)

/** #priority colors: 3 urgent → danger, 2 important → warning, else neutral. Unauthorized is capped at 1 by protocol. */
fun PostView.priorityColor(): Color = when (if (importerAuthorized) ext.priority else minOf(ext.priority, 1)) {
    3 -> Mosimo.danger; 2 -> Mosimo.warning; else -> Mosimo.textSecondary
}

fun epochOf(t: String): Long? = runCatching { Instant.parse(t).epochSecond }.recoverCatching { OffsetDateTime.parse(t).toEpochSecond() }.getOrNull()

/** Event time: hazard `onset`, then BSAF `time:` (source event) when parseable, else Nostr created_at. Epoch seconds. */
fun PostView.eventTime(): Long = ext.hazard?.onset?.let(::epochOf) ?: ext.bsaf?.time?.let(::epochOf) ?: createdAt

private val zone get() = ZoneId.systemDefault()
fun hhmm(epochSeconds: Long): String = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochSecond(epochSeconds).atZone(zone))
fun dateTime(epochSeconds: Long): String = DateTimeFormatter.ofPattern("M/d HH:mm").format(Instant.ofEpochSecond(epochSeconds).atZone(zone))

/** `did:plc:4k…9a`; falls back to the Nostr key when the commit carried no DID. */
fun shortDid(did: String?, pubkey: String): String = did?.let { "did:plc:${it.removePrefix("did:plc:").take(2)}…${it.takeLast(2)}" } ?: "鍵 ${pubkey.take(6)}…"

/** The sender's own position (#origin), else the center of the first circular #area (a point picked on the map). */
fun PostView.latLng(): Pair<Double, Double>? = ext.origin?.let { it.lat / 1e6 to it.lon / 1e6 }
    ?: ext.regions.filterIsInstance<org.mndt.mesh.Region.Circle>().firstOrNull()?.let { it.lat / 1e6 to it.lon / 1e6 }

/** Meters from [here], or null when either side has no position. */
fun PostView.distanceFrom(here: Location?): Float? {
    val (lat, lng) = latLng() ?: return null
    here ?: return null
    return FloatArray(1).also { Location.distanceBetween(here.latitude, here.longitude, lat, lng, it) }[0]
}
fun distanceLabel(meters: Float): String = if (meters < 1000) "${(meters / 10).toInt() * 10}m" else "%.1fkm".format(meters / 1000)

/** 「直接受信」「3ホップ」「オンライン経由」 */
fun PostView.routeLabel(): String = when {
    volatile -> "オンライン経由"
    hop == null || hop == 0 -> "直接受信"
    else -> "${hop}ホップ"
}
