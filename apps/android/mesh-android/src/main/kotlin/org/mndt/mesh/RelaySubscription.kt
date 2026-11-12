package org.mndt.mesh

import org.mndt.core.AtRepository
import org.mndt.core.DeliveryDeadline
import org.mndt.core.NostrEvent

internal sealed interface RelayReply {
    /** NIP-01 `OK`; message is the relay's machine-readable prefix and reason ("blocked: …", "invalid: …"). */
    data class Ok(val id: String, val accepted: Boolean, val message: String = "") : RelayReply
    data class Event(val event: NostrEvent) : RelayReply
    data object Eose : RelayReply
    data object Closed : RelayReply
    data object Full : RelayReply
}

/** Serialized by the coordinator. Display cache only: never a MeshEngine ingress or disk store. */
internal class RelaySubscription {
    companion object {
        /** PDS §4.3: the discovery topic. Local limits below are not a shared relay profile. */
        const val TAG = AtRepository.NAMESPACE
        const val INITIAL_LIMIT = 32
        // The cache survives relay rotation, so it is sized like the public history rather than one backlog.
        const val MAX_EVENTS = PublicDownload.MAX_EVENTS
        const val MAX_BYTES = 2 * 1024 * 1024
        const val MAX_FRAME_BYTES = 262144
        const val MAX_FRAMES_PER_MINUTE = 256
        const val PER_AUTHOR_PER_MINUTE = 12
    }
    private var connection: Long? = null
    var subscriptionId: String? = null
        private set
    var eose = false
        private set
    private val retained = linkedMapOf<String, NostrEvent>()
    private var retainedBytes = 0
    private var full = false
    private val frameTimes = ArrayDeque<Long>()
    private val authorTimes = mutableMapOf<String, MutableList<Long>>()

    fun events(): List<NostrEvent> = retained.values.toList()

    fun open(generation: Long): String? {
        connection = generation
        eose = false
        if (full) return null
        val id = "mosimo-$generation"
        subscriptionId = id
        return json.writeValueAsString(listOf("REQ", id, mapOf("kinds" to listOf(1), "#t" to listOf(TAG), "limit" to INITIAL_LIMIT)))
    }

    fun close(): String? {
        val id = subscriptionId
        subscriptionId = null
        eose = false
        return id?.let { json.writeValueAsString(listOf("CLOSE", it)) }
    }

    fun disconnect() { close(); connection = null }
    fun clear() {
        disconnect()
        retained.clear(); retainedBytes = 0; full = false
        // Rate windows survive reconnect, endpoint changes and stop/start.
    }

    fun receive(generation: Long, text: String, elapsed: Long, meshIds: Set<String> = emptySet()): RelayReply? {
        if (connection != generation) return null
        require(text.length <= MAX_FRAME_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_FRAME_BYTES) { "Relay frame size limit" }
        val frame = runCatching { json.readTree(text) }.getOrNull()
        // OKs answer our own one-at-a-time EVENTs, so a backlog flush must not spend the receive window.
        if (frame != null && frame.isArray && frame.size() == 4 && frame[0].textValue() == "OK" && frame[1].isTextual &&
            frame[2].isBoolean && frame[3].isTextual) return RelayReply.Ok(frame[1].textValue(), frame[2].booleanValue(), frame[3].textValue().take(160))
        // A rotation's re-sent backlog is dropped unverified by id, so reconnects cannot spend the window either.
        if (frame != null && frame.isArray && frame.size() == 3 && frame[0].textValue() == "EVENT" &&
            frame[2].path("id").textValue()?.let { it in retained || it in meshIds } == true) return null
        while (frameTimes.isNotEmpty() && (elapsed - frameTimes.first() >= 60_000 || elapsed < frameTimes.first())) frameTimes.removeFirst()
        require(frameTimes.size < MAX_FRAMES_PER_MINUTE) { "Relay receive rate limit" }
        frameTimes.addLast(elapsed)
        if (frame == null || !frame.isArray || frame.size() < 2 || !frame[0].isTextual || !frame[1].isTextual) return null
        if (subscriptionId == null || frame[1].textValue() != subscriptionId) return null
        return when (frame[0].textValue()) {
            "EOSE" -> if (frame.size() == 2) { eose = true; RelayReply.Eose } else null
            "CLOSED" -> if (frame.size() == 3 && frame[2].isTextual) { close(); RelayReply.Closed } else null
            "EVENT" -> {
                if (frame.size() != 3 || !frame[2].isObject) return null
                // Reuse the dedicated NIP-01 ID + BIP-340 verifier; no authoring or rebundling.
                val event = runCatching { NostrEvent.fromJson(frame[2].toString()) }.getOrNull() ?: return null
                if (event.kind != 1 || event.tags.none { it.firstOrNull() == "t" && it.getOrNull(1) == TAG }) return null
                if (event.id in retained || event.id in meshIds) return null
                authorTimes.values.forEach { times -> times.removeAll { elapsed - it >= 60_000 || elapsed < it } }
                authorTimes.entries.removeAll { it.value.isEmpty() }
                // Per-author pacing is for live events; the bounded backlog before EOSE is history.
                val times = if (eose) authorTimes.getOrPut(event.pubkey) { mutableListOf() } else null
                if (times != null && times.size >= PER_AUTHOR_PER_MINUTE) return null
                val size = event.toJson().toByteArray(Charsets.UTF_8).size
                if (full || retained.size >= MAX_EVENTS || size > MAX_BYTES - retainedBytes) {
                    full = true
                    return RelayReply.Full
                }
                times?.add(elapsed)
                retained[event.id] = event
                retainedBytes += size
                full = retained.size == MAX_EVENTS || retainedBytes == MAX_BYTES
                RelayReply.Event(event)
            }
            else -> null
        }
    }

    val atCapacity: Boolean get() = full
}

private val dids = object : LinkedHashMap<String, String?>(256, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>) = size > 1024
}
/** Memoized by event ID (null DIDs too): refresh() rebuilds up to PublicDownload.MAX_EVENTS display posts every second. */
internal fun didOf(event: NostrEvent): String? = synchronized(dids) {
    if (event.id in dids) dids[event.id] else AtRepository.did(event).also { dids[event.id] = it }
}

internal fun mergePostViews(mesh: List<PostView>, online: List<NostrEvent>, relay: String,
    deadline: (NostrEvent) -> DeliveryDeadline = { DeliveryDeadline.UNKNOWN },
    chain: (NostrEvent) -> org.mndt.core.PublicIngress.ChainView? = { null }): List<PostView> {
    val meshIds = mesh.map { it.id }.toSet()
    return mesh + online.asReversed().filterNot { it.id in meshIds }.map {
        PostView(it.id, it.pubkey, it.content, null, false, sourceRelay = relay, volatile = true, deliveryDeadline = deadline(it), did = didOf(it),
            createdAt = it.createdAt, ext = Extensions.of(it), chain = chain(it))
    }
}
