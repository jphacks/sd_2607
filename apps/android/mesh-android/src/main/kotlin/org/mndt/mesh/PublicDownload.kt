package org.mndt.mesh

import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import okio.ByteString
import org.mndt.core.NostrEvent
import org.mndt.core.PublicIngress
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/** Optional new fields on the existing encrypted state; never reset identity on malformed history. */
internal fun readPublicTrust(node: JsonNode?): PublicIngress.Memory {
    if (node == null) return PublicIngress.Memory()
    require(node.isObject && node.fieldNames().asSequence().toSet() == setOf("snapshots", "serials"))
    val snapshots = node["snapshots"]; val serials = node["serials"]
    require(snapshots.isObject && snapshots.size() <= PublicIngress.MAX_ROOTS && serials.isObject && serials.size() <= PublicIngress.MAX_SERIALS)
    val saved = snapshots.properties().associate { (id, value) ->
        require(id.matches(Regex("[0-9a-f]{64}")) && value.isTextual && value.textValue().length <= 43692)
        id to Base64.getDecoder().decode(value.textValue()).also { require(it.size in 1..32768) }
    }
    val history = serials.properties().associate { (id, value) ->
        require(id.matches(Regex("0[23][0-9a-f]{64}:[0-9a-f]{32}")) && value.isTextual && (value.textValue() == "equivocation" || value.textValue().matches(Regex("[0-9a-f]{64}"))))
        id to value.textValue()
    }
    return PublicIngress.Memory(saved, history)
}

internal object PublicDownload {
    const val LIMIT = 32
    fun request(id: String, priority: Boolean): String {
        val filter = mapOf("kinds" to listOf(1), "#t" to listOf("mosimo.oriom.dev"), "limit" to LIMIT)
        return json.writeValueAsString(if (priority) listOf("MOSIMO-REQ", id, mapOf("order" to "priority-v1", "filter" to filter)) else listOf("REQ", id, filter))
    }
    fun snapshot(http: OkHttpClient, root: PublicIngress.Root): ByteArray = http.newCall(Request.Builder()
        .url(root.snapshotUrl).header("Accept", "application/cbor").build()).execute().use { response ->
        require(response.isSuccessful && response.request.url.isHttps) { "Snapshot HTTPS failure" }
        val body = response.body ?: error("Missing snapshot")
        require(body.contentType()?.let { it.type == "application" && it.subtype == "cbor" } == true) { "Snapshot must be application/cbor" }
        require(body.contentLength() <= 32768)
        body.byteStream().use { it.readBounded(32768) }
    }
    data class Batch(val events: List<NostrEvent>, val fallback: Boolean)
    private data class Frame(val text: String? = null, val opened: Boolean = false, val bytes: Int = 0)

    private class Budget {
        val events = linkedMapOf<String, NostrEvent>()
        var totalBytes = 0
        var frames = 0
    }

    /** Each attempt gets a fresh socket: old relays may disconnect on an unknown verb. */
    suspend fun fetch(http: OkHttpClient, token: Long, relay: String = DiskState.PDS_RELAY, timeoutMillis: Long = 15000): Batch {
        val budget = Budget()
        for (priority in listOf(true, false)) {
            if (attempt(http, relay, "public-$token-${if (priority) "p" else "r"}", priority, timeoutMillis, budget))
                return Batch(budget.events.values.toList(), fallback = !priority)
        }
        error("Ordinary REQ failed/timed out; not an empty success")
    }

    private suspend fun attempt(http: OkHttpClient, relay: String, id: String, priority: Boolean,
        timeoutMillis: Long, budget: Budget): Boolean {
        val frames = Channel<Frame>(LIMIT + 2)
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
            if (!ws.send(request(id, priority))) return false
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
                            require(event.kind == 1 && event.tags.any { it == listOf("t", "mosimo.oriom.dev") })
                            if (event.id !in budget.events) {
                                val bytes = event.toJson().toByteArray(Charsets.UTF_8).size
                                require(budget.events.size < LIMIT && bytes <= RelaySubscription.MAX_BYTES - budget.totalBytes) { "Public request capacity" }
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
