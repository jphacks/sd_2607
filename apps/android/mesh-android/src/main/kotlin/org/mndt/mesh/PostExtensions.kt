package org.mndt.mesh

import org.mndt.core.AtRepository
import org.mndt.core.Attest
import org.mndt.core.NostrEvent
import org.mndt.core.PublicIngress

/** Known official extensions decoded for display. Unknown or malformed ones are ignored, never rewritten. */
data class Origin(val lat: Long, val lon: Long, val acc: Long? = null, val at: String? = null)
sealed interface Region {
    data class Circle(val lat: Long, val lon: Long, val r: Long) : Region
    data class Geohash(val hash: String) : Region
}
/** geo#area: the union of [regions]; [approximation] marks a covering approximation of an original area. */
data class Area(val regions: List<Region>, val approximation: Boolean)
/** validity: `from <= t < until`; an absent bound is unknown, never inferred. */
data class Validity(val from: String?, val until: String?)
/** BSAF original tags; `time:` is the source event time, not the import time. */
data class Bsaf(val type: String?, val time: String?, val target: String?, val source: String?, val tags: List<String>, val imported: Boolean,
    /** The re-posted Bot's DID, from `original.uri`. */
    val botDid: String? = null)
enum class Relation(val wire: String) { CORRECTION("correction"), SUPPLEMENT("supplement"), ENDORSEMENT("endorsement") }
/** [targetEventId] is the Nostr target (hex); atproto references are kept as [targetUri]. */
data class Annotation(val relation: Relation, val targetEventId: String?, val targetUri: String? = null)
data class LocalizedText(val text: String, val lang: String?)
data class Scale(val scheme: String, val value: String, val basis: String? = null)
/** hazard: CAP 1.2 values kept as sent (unknown ones stay raw); [kind] is the single type-specific extension, if any. */
data class Hazard(val status: String, val category: List<String>, val severity: String, val urgency: String, val certainty: String,
    val responseTypes: List<String> = emptyList(), val onset: String? = null, val event: LocalizedText? = null,
    val scales: List<Scale> = emptyList(), val glide: String? = null, val kind: HazardKind? = null)
/** hazard.* (hazard §7). Integers as on the wire: coordinates in micro-degrees, lengths in mm, pressure in Pa, speed in mm/s. */
sealed interface HazardKind {
    data class Earthquake(val kind: String, val hypocenter: Hypocenter? = null, val magnitude: Magnitude? = null) : HazardKind
    data class Hypocenter(val lat: Long?, val lon: Long?, val depth: Long?, val name: LocalizedText?)
    /** [value] is magnitude × 10. */
    data class Magnitude(val value: Long, val type: String?)
    data class Tsunami(val maxHeight: Height? = null, val arrival: String? = null) : HazardKind
    data class Height(val value: Long?, val text: LocalizedText?, val basis: String?)
    data class Volcano(val phenomenon: String, val volcano: VolcanoInfo? = null) : HazardKind
    data class VolcanoInfo(val name: LocalizedText?, val gvp: String?, val lat: Long?, val lon: Long?)
    data class Weather(val phenomena: List<String>) : HazardKind
    data class Cyclone(val name: LocalizedText? = null, val designation: String? = null, val center: Pair<Long, Long>? = null,
        val pressure: Long? = null, val maxWind: Long? = null, val stormRadius: Long? = null) : HazardKind
}
data class PostExtensions(
    val origin: Origin? = null, val areas: List<Area> = emptyList(), val validity: Validity? = null,
    val priority: Int = 1, val bsaf: Bsaf? = null, val annotation: Annotation? = null,
    /** An `attest.chain` extension is present (verification is the coordinator's, see [PostView.chain]). */
    val hasChain: Boolean = false,
    val hazard: Hazard? = null,
) {
    val regions: List<Region> get() = areas.flatMap { it.regions }
}

object Extensions {
    const val ORIGIN = "dev.jisaku.mosimo.geo#origin"
    const val AREA = "dev.jisaku.mosimo.geo#area"
    const val VALIDITY = "dev.jisaku.mosimo.validity"
    const val ANNOTATION = "dev.jisaku.mosimo.annotation"
    const val PRIORITY = PublicIngress.PRIORITY

    fun origin(o: Origin): Map<String, Any?> = buildMap {
        put("\$type", ORIGIN); put("lat", o.lat); put("lon", o.lon)
        o.acc?.let { put("acc", it) }; o.at?.let { put("at", it) }
    }
    fun area(regions: List<Region>, approximation: Boolean = false): Map<String, Any?> = buildMap {
        put("\$type", AREA)
        put("regions", regions.map { r -> when (r) {
            is Region.Circle -> mapOf("shape" to "circle", "lat" to r.lat, "lon" to r.lon, "r" to r.r)
            is Region.Geohash -> mapOf("shape" to "geohash", "hash" to r.hash)
        } })
        if (approximation) put("approximation", true)
    }
    fun circle(c: Region.Circle): Map<String, Any?> = area(listOf(c))
    /** At least one bound is required (validity §3). */
    fun validity(from: String? = null, until: String? = null): Map<String, Any?> {
        require(from != null || until != null) { "validity needs from or until" }
        return buildMap { put("\$type", VALIDITY); from?.let { put("from", it) }; until?.let { put("until", it) } }
    }
    /** Nostr target reference; the event ID travels as 32 bytes inside the CBOR (annotation §2). */
    fun annotation(relation: Relation, targetEventId: String): Map<String, Any?> = mapOf("\$type" to ANNOTATION,
        "relation" to relation.wire, "target" to mapOf("network" to "nostr", "eventId" to unhex(targetEventId)))
    fun priority(level: Int): Map<String, Any?> = mapOf("\$type" to PRIORITY, "level" to level.toLong().also { require(it in 0..3) })

    private val cache = object : LinkedHashMap<String, PostExtensions>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PostExtensions>) = size > 1024
    }
    /** Memoized by event ID: the UI snapshot is rebuilt every second. */
    fun of(event: NostrEvent): PostExtensions = synchronized(cache) { cache.getOrPut(event.id) { decode(event) } }

    fun decode(event: NostrEvent): PostExtensions {
        val ext = runCatching { AtRepository.extensions(event) }.getOrElse {
            // One malformed extension must not hide the others: decode tag by tag, skipping the bad one.
            event.tags.filter { it.size == 2 && it[0].startsWith(AtRepository.EXT_PREFIX) }.mapNotNull { tag ->
                runCatching { AtRepository.extensions(event.copy(tags = listOf(tag))).single() }.getOrNull()
            }
        }
        fun first(type: String) = ext.firstOrNull { it["\$type"] == type }
        return PostExtensions(
            origin = first(ORIGIN)?.let { m -> runCatching { Origin(m.long("lat"), m.long("lon"), m["acc"] as? Long, m["at"] as? String) }.getOrNull() },
            areas = ext.filter { it["\$type"] == AREA }.map { m ->
                Area((m["regions"] as? List<*>).orEmpty().mapNotNull { r -> region(r as? Map<*, *>) }, m["approximation"] == true)
            },
            validity = first(VALIDITY)?.let { m -> Validity(m["from"] as? String, m["until"] as? String).takeIf { it.from != null || it.until != null } },
            priority = PublicIngress.priority(event),
            bsaf = first(PublicIngress.BSAF)?.let { m ->
                val tags = (m["tags"] as? List<*>).orEmpty().filterIsInstance<String>()
                fun tag(name: String) = tags.filter { it.startsWith("$name:") }.singleOrNull()?.removePrefix("$name:")
                val uri = (m["original"] as? Map<*, *>)?.get("uri") as? String
                Bsaf(tag("type"), tag("time"), tag("target"), tag("source"), tags, m["original"] != null,
                    uri?.removePrefix("at://")?.substringBefore('/')?.takeIf { it.startsWith("did:") })
            },
            annotation = first(ANNOTATION)?.let { m ->
                val relation = Relation.entries.firstOrNull { it.wire == m["relation"] }
                val target = m["target"] as? Map<*, *>
                val eventId = (target?.get("eventId") as? ByteArray)?.takeIf { target["network"] == "nostr" && it.size == 32 }?.let(::hex)
                val uri = (target?.get("uri") as? String)?.takeIf { target["network"] == "atproto" }
                if (relation != null && (eventId != null || uri != null)) Annotation(relation, eventId, uri) else null
            },
            hasChain = ext.any { it["\$type"] == Attest.CHAIN },
            hazard = first(HAZARD)?.let { m -> runCatching { hazard(m, ext) }.getOrNull() },
        )
    }

    const val HAZARD = "dev.jisaku.mosimo.hazard"
    private val kinds = listOf("earthquake", "tsunami", "volcano", "weather", "cyclone").map { "$HAZARD.$it" }

    /** Required fields missing or mistyped → throws (no hazard). Type-specific only when exactly one is present (hazard §7). */
    private fun hazard(m: Map<*, *>, ext: List<Map<String, Any?>>): Hazard {
        val category = (m["category"] as List<*>).map { it as String }.also { require(it.isNotEmpty()) }
        val typed = ext.filter { it["\$type"] in kinds }
        return Hazard(m["status"] as String, category, m["severity"] as String, m["urgency"] as String, m["certainty"] as String,
            (m["responseTypes"] as? List<*>).orEmpty().filterIsInstance<String>(), m["onset"] as? String, text(m["event"]),
            (m["scales"] as? List<*>).orEmpty().mapNotNull { s ->
                (s as? Map<*, *>)?.let { val scheme = it["scheme"] as? String; val value = it["value"] as? String
                    if (scheme != null && value != null) Scale(scheme, value, it["basis"] as? String) else null }
            }, m["glide"] as? String, typed.singleOrNull()?.let { runCatching { kind(it) }.getOrNull() })
    }

    private fun kind(m: Map<String, Any?>): HazardKind = when (m["\$type"]) {
        "$HAZARD.earthquake" -> HazardKind.Earthquake(m["kind"] as String,
            (m["hypocenter"] as? Map<*, *>)?.let { HazardKind.Hypocenter(it.opt("lat"), it.opt("lon"), it.opt("depth"), text(it["name"])) },
            (m["magnitude"] as? Map<*, *>)?.let { mg -> mg.opt("value")?.let { HazardKind.Magnitude(it, mg["type"] as? String) } })
        "$HAZARD.tsunami" -> HazardKind.Tsunami(
            (m["maxHeight"] as? Map<*, *>)?.let { HazardKind.Height(it.opt("value"), text(it["text"]), it["basis"] as? String) }, m["arrival"] as? String)
        "$HAZARD.volcano" -> HazardKind.Volcano(m["phenomenon"] as String,
            (m["volcano"] as? Map<*, *>)?.let { HazardKind.VolcanoInfo(text(it["name"]), it["gvp"] as? String, it.opt("lat"), it.opt("lon")) })
        "$HAZARD.weather" -> HazardKind.Weather((m["phenomena"] as List<*>).filterIsInstance<String>())
        else -> HazardKind.Cyclone(text(m["name"]), m["designation"] as? String,
            (m["center"] as? Map<*, *>)?.let { c -> c.opt("lat")?.let { lat -> c.opt("lon")?.let { lat to it } } },
            m.opt("pressure"), m.opt("maxWind"), m.opt("stormRadius"))
    }
    private fun text(v: Any?): LocalizedText? = (v as? Map<*, *>)?.let { m -> (m["text"] as? String)?.let { LocalizedText(it, m["lang"] as? String) } }
    private fun Map<*, *>.opt(key: String) = (this[key] as? Number)?.toLong()

    private fun region(m: Map<*, *>?): Region? = runCatching {
        when (m?.get("shape")) {
            "circle" -> Region.Circle(m.long("lat"), m.long("lon"), m.long("r"))
            "geohash" -> Region.Geohash((m["hash"] as String).also { require(it.matches(Regex("[0-9b-hjkmnp-z]{1,12}"))) })
            else -> null
        }
    }.getOrNull()
    private fun Map<*, *>.long(key: String) = this[key] as Long
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun unhex(s: String): ByteArray { require(s.matches(Regex("[0-9a-f]{64}"))); return ByteArray(32) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
}
