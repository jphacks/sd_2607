package org.mndt.mesh

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal val json = ObjectMapper(JsonFactory.builder()
    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(8_000_000).build())
    .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

/** Contact control message (mndt §3). [node]/[max] are HELLO capability fields; [ids] are Nostr event IDs. */
internal data class Control(val type: String, val ids: List<String> = emptyList(), val node: String = "", val max: Int = 0)

internal fun relayEventFrame(eventJson: String): String = "[\"EVENT\",$eventJson]".also {
    // JSON escaping can expand a compact 24 KB event well beyond a Nearby frame.
    require(it.toByteArray(Charsets.UTF_8).size <= 262144) { "Relay event size limit" }
}

/** Rotate actual attempts so a disconnecting or rejecting first event cannot starve the queue. */
internal fun nextRelayId(ids: List<String>, lastAttempt: String?, deferred: Map<String, Long>, now: Long): String? {
    val start = ids.indexOf(lastAttempt) + 1
    return (ids.drop(start) + ids.take(start)).firstOrNull { now >= (deferred[it] ?: 0L) }
}

internal fun onlineRelayAllowed(enabled: Boolean, foreground: Boolean, fatal: Boolean, hasRelay: Boolean,
    serviceRunning: Boolean = false): Boolean = enabled && (foreground || serviceRunning) && !fatal && hasRelay

/** Provisional JSON control messages (protocol/mndt.md §9); bundles travel as raw BPv7 CBOR frames. */
internal object Wire {
    const val MAX_BYTES = 32768
    private val idPattern = Regex("[0-9a-f]{64}")
    private val nodePattern = Regex("[0-9a-f]{16}")
    fun encode(control: Control): ByteArray {
        val root = json.createObjectNode().put("v", 1).put("type", control.type)
        if (control.type == "hello") root.put("node", control.node).put("max", control.max)
        else root.putArray("ids").also { a -> control.ids.forEach(a::add) }
        return json.writeValueAsBytes(root).also { decode(it) }
    }
    fun decode(bytes: ByteArray): Control {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES && bytes[0] == '{'.code.toByte())
        val node = json.readTree(bytes)
        require(node.isObject && node["v"]?.isIntegralNumber == true && node["v"].canConvertToInt() && node["v"].intValue() == 1)
        val type = node["type"]?.takeIf(JsonNode::isTextual)?.textValue()
        require(type in listOf("hello", "inventory", "want", "ack"))
        if (type == "hello") {
            // Later capability fields are ignored so peers can add them without a version bump.
            val id = node["node"]?.textValue()
            val max = node["max"]
            require(id != null && nodePattern.matches(id) && max != null && max.isIntegralNumber && max.canConvertToInt() && max.intValue() in 1..MAX_BYTES)
            return Control(type, node = id, max = max.intValue())
        }
        require(node.fieldNames().asSequence().toSet() == setOf("v", "type", "ids"))
        val ids = node["ids"]
        require(ids != null && ids.isArray && ids.size() <= 128)
        val values = ids.map { require(it.isTextual && idPattern.matches(it.textValue())); it.textValue() }
        require(values.distinct().size == values.size)
        require(type != "ack" || values.size == 1)
        return Control(type!!, values)
    }
}

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
