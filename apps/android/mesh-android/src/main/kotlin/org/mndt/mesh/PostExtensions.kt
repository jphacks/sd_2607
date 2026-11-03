package org.mndt.mesh

import org.mndt.core.AtRepository
import org.mndt.core.CanonicalCbor
import org.mndt.core.NostrEvent
import org.mndt.core.PublicIngress
import java.util.Base64

/** Known protocol/0011 extensions decoded for display. Unknown or malformed ones are ignored, never rewritten. */
data class Origin(val latitudeMicrodegrees: Long, val longitudeMicrodegrees: Long, val accuracyMillimeters: Long? = null)
sealed interface Region {
    data class Circle(val latitudeMicrodegrees: Long, val longitudeMicrodegrees: Long, val radiusMillimeters: Long) : Region
    data class Geohash(val hash: String) : Region
}
/** BSAF original tags; `time:` is the source event time, not the import time. */
data class Bsaf(val type: String?, val time: String?, val target: String?, val tags: List<String>)
enum class Relation(val wire: String) { CORRECTION("correction"), SUPPLEMENT("supplement"), ENDORSEMENT("endorsement") }
data class Annotation(val relation: Relation, val targetEventId: String)
data class PostExtensions(
    val origin: Origin? = null, val regions: List<Region> = emptyList(), val validityEndsAt: String? = null,
    val priority: Int = 1, val bsaf: Bsaf? = null, val annotation: Annotation? = null,
)

object Extensions {
    const val ORIGIN = "dev.oriom.mosimo.defs#origin"
    const val AREA = "dev.oriom.mosimo.defs#area"
    const val VALIDITY = "dev.oriom.mosimo.defs#validity"
    const val ANNOTATION = "dev.oriom.mosimo.defs#annotation"

    fun origin(o: Origin): Map<String, Any?> = mapOf("\$type" to ORIGIN, "latitudeMicrodegrees" to o.latitudeMicrodegrees,
        "longitudeMicrodegrees" to o.longitudeMicrodegrees) + (o.accuracyMillimeters?.let { mapOf("accuracyMillimeters" to it) } ?: emptyMap())
    fun circle(c: Region.Circle): Map<String, Any?> = mapOf("\$type" to AREA, "regions" to listOf(mapOf("shape" to "circle",
        "latitudeMicrodegrees" to c.latitudeMicrodegrees, "longitudeMicrodegrees" to c.longitudeMicrodegrees, "radiusMillimeters" to c.radiusMillimeters)))
    fun validity(endsAt: String): Map<String, Any?> = mapOf("\$type" to VALIDITY, "endsAt" to endsAt)
    // ponytail: nostr eventId only; follow 0011 §7 once the reference form is fixed.
    fun annotation(relation: Relation, targetEventId: String): Map<String, Any?> = mapOf("\$type" to ANNOTATION,
        "relation" to relation.wire, "target" to mapOf("network" to "nostr", "eventId" to targetEventId))

    private val cache = object : LinkedHashMap<String, PostExtensions>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PostExtensions>) = size > 1024
    }
    /** Memoized by event ID: the UI snapshot is rebuilt every second. */
    fun of(event: NostrEvent): PostExtensions = synchronized(cache) { cache.getOrPut(event.id) { decode(event) } }

    fun decode(event: NostrEvent): PostExtensions {
        val ext = event.tags.filter { it.size == 2 && it[0].startsWith(AtRepository.EXT_PREFIX) }.mapNotNull { tag ->
            runCatching { tag[0].removePrefix(AtRepository.EXT_PREFIX) to (CanonicalCbor.decode(Base64.getDecoder().decode(tag[1])) as Map<*, *>) }.getOrNull()
        }
        fun first(type: String) = ext.firstOrNull { it.first == type }?.second
        return PostExtensions(
            origin = first(ORIGIN)?.let { m -> runCatching { Origin(m.long("latitudeMicrodegrees"), m.long("longitudeMicrodegrees"), m["accuracyMillimeters"] as? Long) }.getOrNull() },
            regions = ext.filter { it.first == AREA }.flatMap { (_, m) -> (m["regions"] as? List<*>).orEmpty().mapNotNull { r -> region(r as? Map<*, *>) } },
            validityEndsAt = first(VALIDITY)?.get("endsAt") as? String,
            priority = PublicIngress.priority(event),
            bsaf = first(PublicIngress.BSAF)?.let { m ->
                val tags = (m["tags"] as? List<*>).orEmpty().filterIsInstance<String>()
                fun tag(name: String) = tags.firstOrNull { it.startsWith("$name:") }?.removePrefix("$name:")
                Bsaf(tag("type"), tag("time"), tag("target"), tags)
            },
            annotation = first(ANNOTATION)?.let { m ->
                val relation = Relation.entries.firstOrNull { it.wire == m["relation"] }
                val target = (m["target"] as? Map<*, *>)?.takeIf { it["network"] == "nostr" }?.get("eventId") as? String
                if (relation != null && target != null) Annotation(relation, target) else null
            },
        )
    }

    private fun region(m: Map<*, *>?): Region? = runCatching {
        when (m?.get("shape")) {
            "circle" -> Region.Circle(m.long("latitudeMicrodegrees"), m.long("longitudeMicrodegrees"), m.long("radiusMillimeters"))
            "geohash" -> Region.Geohash((m["hash"] as String).also { require(it.matches(Regex("[0-9b-hjkmnp-z]{1,12}"))) })
            else -> null
        }
    }.getOrNull()
    private fun Map<*, *>.long(key: String) = this[key] as Long
}
