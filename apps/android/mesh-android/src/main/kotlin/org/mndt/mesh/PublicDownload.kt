package org.mndt.mesh

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import okio.ByteString
import org.mndt.core.AtRepository
import org.mndt.core.ClockConfidence
import org.mndt.core.NostrEvent
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

internal object PublicDownload {
    const val LIMIT = 32
    /** Display-only pages after the first: plain REQ walked back by `until`, never engine ingress or disk. */
    const val PAGE_LIMIT = 100
    const val MAX_EVENTS = 500
    // ponytail: fixed 8 MiB in-memory display cap; size it from device memory if 500 events ever stop fitting.
    const val MAX_TOTAL_BYTES = 8 * 1024 * 1024
    /** attest.revocation.list §2: default full response bound. */
    const val MAX_REVOCATION_LIST_BYTES = 1_048_576
    /**
     * Ingress window: only posts created this recently are asked for, so the relay's fixed priority candidate budget
     * (pds §7.2 answers CLOSED rather than a newest-only prefix) is not exhausted by long-expired posts. Mesh residence
     * is capped at 24 h too, so older posts could not be imported anyway.
     */
    const val INGRESS_WINDOW_SECONDS = 86_400L
    /** Display pages download on any clock; only authorized ingress gets one, and PublicIngress fails closed on null. */
    fun ingressClock(confidence: ClockConfidence, nowMillis: Long): Instant? =
        if (confidence == ClockConfidence.TRUSTED) Instant.ofEpochMilli(nowMillis) else null
    /** PDS §7: `MOSIMO-REQ` priority snapshot, or the standard `REQ` fallback with the same filter. */
    fun request(id: String, priority: Boolean, limit: Int = LIMIT, until: Long? = null, since: Long? = null): String {
        val filter = mapOf("kinds" to listOf(1), "#t" to listOf(AtRepository.NAMESPACE), "limit" to limit) +
            (until?.let { mapOf("until" to it) } ?: emptyMap()) + (since?.let { mapOf("since" to it) } ?: emptyMap())
        return json.writeValueAsString(if (priority) listOf("MOSIMO-REQ", id, mapOf("order" to "priority-v1", "filter" to filter)) else listOf("REQ", id, filter))
    }
    /** attest.revocation.list §1: `https://D/.well-known/mosimo/attest/revocations` as canonical CBOR, no redirects. */
    fun revocationList(http: OkHttpClient, domain: String): ByteArray {
        require(domain.matches(Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+")) && domain.length <= 253) { "Invalid issuer domain" }
        return http.newCall(Request.Builder().url("https://$domain/.well-known/mosimo/attest/revocations").header("Accept", "application/cbor").build()).execute().use { response ->
            require(response.isSuccessful && response.request.url.isHttps) { "Revocation list HTTPS failure" }
            val body = response.body ?: error("Missing revocation list")
            require(body.contentType()?.let { it.type == "application" && it.subtype == "cbor" } == true) { "Revocation list must be application/cbor" }
            require(body.contentLength() <= MAX_REVOCATION_LIST_BYTES)
            body.byteStream().use { it.readBounded(MAX_REVOCATION_LIST_BYTES) }
        }
    }
    data class Batch(val events: List<NostrEvent>, val fallback: Boolean)
    /** [events] excludes the first batch; [failure] keeps the pages fetched before it. */
    data class Pages(val events: List<NostrEvent>, val failure: String? = null)
    private data class Frame(val text: String? = null, val opened: Boolean = false, val bytes: Int = 0)

    private class Budget {
        val events = linkedMapOf<String, NostrEvent>()
        var totalBytes = 0
        var frames = 0
    }

    /** Each attempt gets a fresh socket: old relays may disconnect on an unknown verb. */
    suspend fun fetch(http: OkHttpClient, token: Long, relay: String = DiskState.PDS_RELAY, timeoutMillis: Long = 15000, since: Long? = null): Batch {
        val budget = Budget()
        for (priority in listOf(true, false)) {
            if (attempt(http, relay, "public-$token-${if (priority) "p" else "r"}", priority, timeoutMillis, budget, since = since))
                return Batch(budget.events.values.toList(), fallback = !priority)
        }
        error("Ordinary REQ failed/timed out; not an empty success")
    }

    /**
     * Older pages after a full [first] batch, until a short page, [MAX_EVENTS] or [MAX_TOTAL_BYTES]; no age cutoff,
     * since display history outlives the mesh delivery deadline. The relay caps each REQ, so pages walk back by `until`.
     * priority-v1 has no time order, so its batch never seeds the cursor; `until` is inclusive, hence the id dedupe.
     */
    suspend fun more(http: OkHttpClient, token: Long, first: Batch, relay: String = DiskState.PDS_RELAY,
        timeoutMillis: Long = 15000): Pages {
        if (first.events.size < LIMIT) return Pages(emptyList())
        val seen = first.events.mapTo(hashSetOf()) { it.id }
        var bytes = first.events.sumOf { it.toJson().toByteArray(Charsets.UTF_8).size }
        val extra = mutableListOf<NostrEvent>()
        var until = if (first.fallback) first.events.minOf { it.createdAt } else null
        var page = 0
        while (seen.size < MAX_EVENTS) {
            val budget = Budget()
            val ok = runCatching { attempt(http, relay, "public-$token-m${++page}", false, timeoutMillis, budget, PAGE_LIMIT, until) }
            if (ok.getOrNull() != true) return Pages(extra, ok.exceptionOrNull()?.message ?: "page $page failed/timed out")
            var added = 0
            for (event in budget.events.values) {
                if (event.id in seen) continue
                val size = event.toJson().toByteArray(Charsets.UTF_8).size
                if (seen.size >= MAX_EVENTS || bytes + size > MAX_TOTAL_BYTES) return Pages(extra)
                seen += event.id; extra += event; bytes += size; added++
            }
            val oldest = budget.events.values.minOfOrNull { it.createdAt } ?: break
            // No progress: a full page sharing one created_at cannot be walked past with an inclusive cursor.
            if (budget.events.size < PAGE_LIMIT || added == 0) break
            until = oldest
        }
        return Pages(extra)
    }

    private suspend fun attempt(http: OkHttpClient, relay: String, id: String, priority: Boolean,
        timeoutMillis: Long, budget: Budget, limit: Int = LIMIT, until: Long? = null, since: Long? = null): Boolean {
        val frames = Channel<Frame>(limit + 2)
        val queuedBytes = AtomicInteger()
        val ws = http.newWebSocket(Request.Builder().url(relay).build(), object : WebSocketListener() {
            private fun offer(socket: WebSocket, frame: Frame) {
                if (queuedBytes.addAndGet(frame.bytes) > RelaySubscription.MAX_BYTES || frames.trySend(frame).isFailure) {
                    queuedBytes.addAndGet(-frame.bytes); frames.close(IllegalArgumentException("Public relay queue limit")); socket.cancel()
                }
            }
            override fun onOpen(webSocket: WebSocket, response: Response) = offer(webSocket, Frame(opened = true))
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > RelaySubscription.MAX_FRAME_BYTES) { frames.close(IllegalArgumentException("Relay frame size limit")); webSocket.cancel(); return }
                val bytes = text.toByteArray(Charsets.UTF_8).size
                if (bytes > RelaySubscription.MAX_FRAME_BYTES) { frames.close(IllegalArgumentException("Relay frame size limit")); webSocket.cancel() }
                else offer(webSocket, Frame(text = text, bytes = bytes))
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { frames.close(IllegalArgumentException("Binary relay frame unsupported")); webSocket.cancel() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { frames.close() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { frames.close() }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { frames.close(); webSocket.close(code, null) }
        })
        try {
            if (withTimeoutOrNull(15000) { frames.receiveCatching().getOrNull()?.opened } != true) return false
            if (!ws.send(request(id, priority, limit, until, since))) return false
            val completed = withTimeoutOrNull(timeoutMillis) {
                while (true) {
                    val received = frames.receiveCatching()
                    received.exceptionOrNull()?.let { throw it }
                    val frame = received.getOrNull() ?: return@withTimeoutOrNull false
                    queuedBytes.addAndGet(-frame.bytes)
                    require(++budget.frames <= RelaySubscription.MAX_FRAMES_PER_MINUTE) { "Frame limit" }
                    val n = frame.text?.let { json.readTree(it) } ?: continue
                    if (!n.isArray || n.size() < 2 || n[1].textValue() != id) continue
                    when (n[0].textValue()) {
                        "EOSE" -> { require(n.size() == 2); return@withTimeoutOrNull true }
                        "CLOSED" -> return@withTimeoutOrNull false
                        "EVENT" -> {
                            require(n.size() == 3)
                            val event = NostrEvent.fromJson(n[2].toString())
                            require(event.kind == 1 && event.tags.any { it == AtRepository.TOPIC_TAG })
                            if (event.id !in budget.events) {
                                val bytes = event.toJson().toByteArray(Charsets.UTF_8).size
                                require(budget.events.size < limit && bytes <= RelaySubscription.MAX_BYTES - budget.totalBytes) { "Public request capacity" }
                                budget.events[event.id] = event; budget.totalBytes += bytes
                            }
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE") false
            }
            ws.send(json.writeValueAsString(listOf("CLOSE", id)))
            return completed == true
        } finally { ws.cancel(); frames.close() }
    }
}
