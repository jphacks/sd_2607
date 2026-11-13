package org.mndt.app.ui.post

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cyclone
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Thunderstorm
import androidx.compose.material.icons.outlined.Tsunami
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.Volcano
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mndt.app.ui.theme.Mosimo
import org.mndt.mesh.Hazard
import org.mndt.mesh.HazardKind
import org.mndt.mesh.PostView
import org.mndt.mesh.Scale
import java.util.Locale

private val categories = mapOf("Geo" to "地象", "Met" to "気象", "Safety" to "安全", "Security" to "治安・国民保護", "Rescue" to "救助",
    "Fire" to "火災", "Health" to "保健・医療", "Env" to "環境", "Transport" to "交通", "Infra" to "インフラ", "CBRNE" to "有害物質等", "Other" to "その他")
private val quakes = mapOf("event" to "地震", "early-warning" to "緊急地震速報", "assessment" to "地震の評価")
private val volcanoes = mapOf("eruption" to "噴火", "ashfall" to "降灰", "activity" to "火山活動", "pyroclastic-flow" to "火砕流",
    "lahar" to "火山泥流", "gas" to "火山ガス")
private val phenomena = mapOf("rain" to "大雨", "flood" to "洪水", "landslide" to "土砂災害", "storm-surge" to "高潮", "wave" to "高波",
    "wind" to "暴風", "tornado" to "竜巻", "thunderstorm" to "雷", "snow" to "大雪", "snowmelt" to "融雪", "avalanche" to "なだれ", "fog" to "濃霧",
    "dry-air" to "乾燥", "cold" to "低温", "heat" to "高温", "frost" to "霜", "icing" to "着氷", "snow-accretion" to "着雪")
private val severities = mapOf("Extreme" to "極めて重大", "Severe" to "重大", "Moderate" to "警戒", "Minor" to "注意")
private val urgencies = mapOf("Immediate" to "直ちに", "Expected" to "まもなく", "Future" to "今後", "Past" to "経過")
private val certainties = mapOf("Observed" to "発生", "Likely" to "可能性が高い", "Possible" to "可能性あり", "Unlikely" to "可能性は低い")
private val responses = mapOf("Shelter" to "安全確保", "Evacuate" to "避難", "Prepare" to "避難の準備", "Execute" to "計画に従う", "Avoid" to "近づかない",
    "Monitor" to "情報に注意", "Assess" to "状況を確認", "AllClear" to "解除", "None" to "行動不要")
/** hazard §5.2 enumerated scales; numeric ones are formatted in [scaleLabel]. */
private val scaleValues = mapOf(
    "jp.jma.warning-class" to mapOf("advisory" to "注意報級", "warning" to "警報級", "special-warning" to "特別警報級"),
    "jp.jma.tsunami" to mapOf("forecast" to "津波予報", "advisory" to "津波注意報", "warning" to "津波警報", "major-warning" to "大津波警報"),
    "jp.jma.typhoon-intensity" to mapOf("none" to "強さの階級なし", "strong" to "強い", "very-strong" to "非常に強い", "violent" to "猛烈な"),
    "jp.jma.nankai-trough" to mapOf("investigating" to "調査中", "caution" to "巨大地震注意", "alert" to "巨大地震警戒", "concluded" to "調査終了"),
    "intl.meteoalarm.awareness-level" to mapOf("green" to "警戒色 緑", "yellow" to "警戒色 黄", "orange" to "警戒色 橙", "red" to "警戒色 赤"),
)
private val shindo = mapOf("5-" to "5弱", "5+" to "5強", "6-" to "6弱", "6+" to "6強") + listOf("0", "1", "2", "3", "4", "7").associateWith { it }
private val levels = (1..5).map(Int::toString)
private val mmi = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII")

/** Kind of the hazard, most specific first: type-specific extension, then the first known CAP category. */
fun Hazard.kindLabel(): String = when (val k = kind) {
    is HazardKind.Earthquake -> quakes[k.kind] ?: "地震"
    is HazardKind.Tsunami -> "津波"
    is HazardKind.Volcano -> volcanoes[k.phenomenon] ?: "火山"
    is HazardKind.Weather -> k.phenomena.mapNotNull { phenomena[it] }.distinct().joinToString("・").ifEmpty { "気象" }
    is HazardKind.Cyclone -> "台風・熱帯低気圧"
    null -> category.firstNotNullOfOrNull { categories[it] } ?: "災害情報"
}
val Hazard.allClear get() = "AllClear" in responseTypes
/** Exercise and Test are always marked: never shown as a real alert. */
val Hazard.statusLabel: String? get() = when (status) { "Exercise" -> "訓練"; "Test" -> "試験"; else -> null }
/** 「訓練 大雨 解除」 */
fun Hazard.title(): String = listOfNotNull(statusLabel, kindLabel(), "解除".takeIf { allClear }).joinToString(" ")
/** Original notice name (e.g. 竜巻注意情報), when it says more than the kind label. */
fun Hazard.eventName(): String? = event?.text?.takeIf { it.isNotBlank() && it != kindLabel() }

fun severityLabel(v: String) = severities[v] ?: "不明"
fun urgencyLabel(v: String) = urgencies[v] ?: "不明"
fun certaintyLabel(v: String) = certainties[v] ?: "不明"
fun Hazard.actions(): String = responseTypes.mapNotNull { responses[it] }.joinToString("・")

/** Registered scales in Japanese; an unknown scheme or value is shown raw, never hidden. */
fun scaleLabel(s: Scale): String {
    val v = s.value
    val text = when (s.scheme) {
        "jp.jma.shindo" -> shindo[v]?.let { "震度$it" }
        "jp.jma.warning-level" -> "警戒レベル$v".takeIf { v in levels }
        "jp.jma.volcanic-alert-level" -> "噴火警戒レベル$v".takeIf { v in levels }
        "intl.mmi" -> "MMI $v".takeIf { v in mmi }
        "intl.saffir-simpson" -> "カテゴリー$v".takeIf { v in levels }
        else -> scaleValues[s.scheme]?.get(v)
    } ?: "${s.scheme} $v"
    return text + when (s.basis) { "observed" -> "（観測）"; "forecast" -> "（予測）"; else -> "" }
}

fun magnitudeLabel(m: HazardKind.Magnitude) = "M%.1f".format(Locale.ROOT, m.value / 10.0) + (m.type?.let { "（$it）" } ?: "")
/** Depth in mm → 「約10km」, 「1km未満」. */
fun depthLabel(mm: Long): String = (mm / 1_000_000).let { if (it < 1) "1km未満" else "約${it}km" }
private fun meters(mm: Long) = if (mm % 1000 == 0L) "${mm / 1000}m" else "%.1fm".format(Locale.ROOT, mm / 1000.0)
private fun basis(b: String?) = when (b) { "observed" -> "（観測）"; "forecast" -> "（予測）"; else -> "" }
private fun coords(lat: Long?, lon: Long?) = if (lat != null && lon != null) "%.3f, %.3f".format(Locale.ROOT, lat / 1e6, lon / 1e6) else null

/** Earthquake one-liner for cards: 「M6.5・深さ約10km・震源 石川県能登地方」. */
fun Hazard.quakeSummary(): String? = (kind as? HazardKind.Earthquake)?.let { q ->
    listOfNotNull(q.magnitude?.let(::magnitudeLabel), q.hypocenter?.depth?.let { "深さ" + depthLabel(it) },
        q.hypocenter?.name?.let { "震源 ${it.text}" }).joinToString("・").ifEmpty { null }
}

/** Type-specific quantities as (label, value) rows for the detail view. */
fun Hazard.details(): List<Pair<String, String>> = when (val k = kind) {
    is HazardKind.Earthquake -> listOfNotNull(k.hypocenter?.name?.let { "震源" to it.text },
        coords(k.hypocenter?.lat, k.hypocenter?.lon)?.let { "震源の位置" to it },
        k.magnitude?.let { "規模" to magnitudeLabel(it) }, k.hypocenter?.depth?.let { "深さ" to depthLabel(it) })
    is HazardKind.Tsunami -> listOfNotNull(k.maxHeight?.let { h -> (h.text?.text ?: h.value?.let(::meters))?.let { "最大の高さ" to it + basis(h.basis) } },
        k.arrival?.let { "到達予想" to timeLabel(it) })
    is HazardKind.Volcano -> listOfNotNull(k.volcano?.name?.let { "火山" to it.text }, k.volcano?.gvp?.let { "GVP番号" to it },
        coords(k.volcano?.lat, k.volcano?.lon)?.let { "火山の位置" to it })
    is HazardKind.Weather, null -> emptyList()
    is HazardKind.Cyclone -> listOfNotNull(k.name?.let { "名称" to it.text }, k.designation?.let { "番号" to it },
        k.center?.let { (lat, lon) -> coords(lat, lon)?.let { "中心" to it } }, k.pressure?.let { "中心気圧" to "${it / 100}hPa" },
        k.maxWind?.let { "最大風速" to "${it / 1000}m/s" }, k.stormRadius?.let { "暴風域" to "半径約${it / 1_000_000}km" })
}

/**
 * hazard §8: the post's own chain, confirmed now against the local roots, ends in `public-information` or in an importer
 * authorized for its (Bot, type); else a user claim. Checked from the post itself, so mesh, relay and import all agree.
 */
val PostView.officialHazard get() = chain?.let { it.confirmedNow && (it.publicInformation || it.importAuthorized) } == true

/** Severity color for official information, all-clear green; a user claim never gets alert colors. */
fun Hazard.color(official: Boolean): Color = when {
    !official -> Mosimo.textSecondary
    allClear -> Mosimo.safe
    else -> when (severity) {
        "Extreme" -> Mosimo.extreme; "Severe" -> Mosimo.danger; "Moderate" -> Mosimo.warning; "Minor" -> Mosimo.minor
        else -> Mosimo.textSecondary
    }
}

fun Hazard.icon(): ImageVector = when (kind) {
    is HazardKind.Earthquake -> Icons.Outlined.Vibration
    is HazardKind.Tsunami -> Icons.Outlined.Tsunami
    is HazardKind.Volcano -> Icons.Outlined.Volcano
    is HazardKind.Weather -> Icons.Outlined.Thunderstorm
    is HazardKind.Cyclone -> Icons.Outlined.Cyclone
    null -> Icons.Outlined.WarningAmber
}

private fun timeLabel(value: String) = epochOf(value)?.let(::dateTime) ?: value

/** Card header for a hazard post: kind icon, title and first scale in the severity color, and 「利用者の申告」 when unconfirmed. */
@Composable
fun HazardLabel(post: PostView, hazard: Hazard) {
    val official = post.officialHazard
    val color = hazard.color(official)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(26.dp).clip(MaterialTheme.shapes.small).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(hazard.icon(), contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        }
        Text(listOfNotNull(hazard.title(), hazard.scales.firstOrNull()?.let(::scaleLabel)).joinToString(" "),
            style = MaterialTheme.typography.labelLarge, color = color)
        if (!official) Text("利用者の申告", Modifier.clip(MaterialTheme.shapes.small).background(Mosimo.bgSurface2).padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall, color = Mosimo.textTertiary)
    }
}

/** Under the card header: the original notice name and the earthquake summary. */
@Composable
fun HazardLines(post: PostView) {
    val h = post.ext.hazard ?: return
    listOfNotNull(h.eventName(), h.quakeSummary()).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Mosimo.textSecondary) }
}

/** 「災害情報」 block of the post detail. */
@Composable
fun HazardSection(post: PostView) {
    val h = post.ext.hazard ?: return
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(Mosimo.bgSurface2).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("災害情報", style = MaterialTheme.typography.labelMedium, color = Mosimo.textTertiary, fontWeight = FontWeight.Bold)
        HazardLabel(post, h)
        h.eventName()?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Mosimo.textPrimary) }
        Fact("深刻度", severityLabel(h.severity))
        Fact("切迫度", urgencyLabel(h.urgency))
        Fact("確実性", certaintyLabel(h.certainty))
        h.actions().ifEmpty { null }?.let { Fact("取るべき行動", it) }
        if (h.scales.isNotEmpty()) Fact("階級", h.scales.joinToString("・", transform = ::scaleLabel))
        h.onset?.let { Fact("発生時刻", timeLabel(it)) }
        h.details().forEach { (label, value) -> Fact(label, value) }
        h.glide?.let { Fact("GLIDE", it) }
        val confirmed = post.chain?.let { it.confirmedNow && it.publicInformation } == true
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (icon, color, text) = when {
                confirmed -> Triple(Icons.Outlined.Verified, Mosimo.safe, "公的な発表として確認済み")
                // importer-only: hazard §8 allows 「orgName が転載した情報」 (BsafVerification), not an issuer's announcement.
                post.importerAuthorized -> Triple(Icons.Outlined.Verified, Mosimo.safe, "認可された転載者による情報")
                else -> Triple(Icons.Outlined.Info, Mosimo.textTertiary, "利用者の申告（未確認）")
            }
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Text(" $text", style = MaterialTheme.typography.bodySmall, color = color)
        }
        if (post.ext.areas.any { it.approximation }) Text("範囲は都道府県を覆う近似です", style = MaterialTheme.typography.labelSmall, color = Mosimo.textTertiary)
    }
}
