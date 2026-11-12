package org.mndt.mesh

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import org.mndt.core.AtRepository
import org.mndt.core.NostrEvent
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal val json = ObjectMapper(JsonFactory.builder()
    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(8_000_000).build())
    .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

/** Lab-only node override; normal installs keep per-process randomness. Not an identity or authentication. */
internal fun meshNode(configured: String?, randomNode: () -> String): String =
    configured?.takeIf { it.isNotEmpty() }?.also {
        require(it.matches(Regex("[0-9a-f]{16}"))) { "Invalid test node" }
    } ?: randomNode()

internal fun relayEventFrame(eventJson: String): String = "[\"EVENT\",$eventJson]".also {
    // JSON escaping can expand a 64 KiB bundle event well beyond its CBOR size.
    require(it.toByteArray(Charsets.UTF_8).size <= 262144) { "Relay event size limit" }
}

/** A relay's OK false is a policy decision; retrying that event sooner only churns sockets. */
internal const val RELAY_REJECT_DEFER_MILLIS = 10 * 60_000L

/** Rotate actual attempts so a disconnecting or rejecting first event cannot starve the queue. */
internal fun nextRelayId(ids: List<String>, lastAttempt: String?, deferred: Map<String, Long>, now: Long): String? {
    val start = ids.indexOf(lastAttempt) + 1
    return (ids.drop(start) + ids.take(start)).firstOrNull { now >= (deferred[it] ?: 0L) }
}

/** Select from the current round-robin position without changing the saved relay order. */
internal fun nextRelayIndex(relays: List<String>, start: Int, pending: (String) -> List<String>,
    deferred: Map<String, Map<String, Long>>, retryAt: Map<String, Long>, now: Long): Int? =
    relays.indices.map { (start + it) % relays.size }.firstOrNull { index ->
        val relay = relays[index]
        now >= (retryAt[relay] ?: 0L) && nextRelayId(pending(relay), null, deferred[relay].orEmpty(), now) != null
    }

/**
 * PDS §4.2 / §6 and DM §5: PLC genesis control events stay in the mesh; the PDS relay takes posts, deletion requests, reports and
 * revocation notices; general relays take everything else (DMs, profiles) too.
 */
internal fun relayWants(relay: String, event: NostrEvent): Boolean {
    val topic = AtRepository.controlTopic(event)
    if (event.kind == AtRepository.CONTROL_KIND && topic != AtRepository.REVOCATION_TOPIC) return false
    return relay != DiskState.PDS_RELAY || event.kind == 1 || event.kind == 5 || event.kind == 1984 || topic == AtRepository.REVOCATION_TOPIC
}

internal fun onlineRelayAllowed(enabled: Boolean, foreground: Boolean, fatal: Boolean, hasRelay: Boolean,
    serviceRunning: Boolean = false): Boolean = enabled && (foreground || serviceRunning) && !fatal && hasRelay

internal fun InputStream.readBounded(limit: Int): ByteArray {
    require(limit in 0 until Int.MAX_VALUE)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (count < 0) return output.toByteArray()
        if (count == 0) {
            val next = read()
            if (next < 0) return output.toByteArray()
            output.write(next)
        } else output.write(buffer, 0, count)
        require(output.size() <= limit) { "Input size limit" }
    }
}
