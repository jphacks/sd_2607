package org.mndt.core

import com.fasterxml.jackson.databind.JsonNode
import java.util.Base64

/** atproto JSON values, not a CBOR/Base64 wrapper around an extension. */
internal object AtDataJson {
    fun encode(value: Any?): String {
        CanonicalCbor.encode(value) // Validate types and resource limits before traversing user data.
        fun json(v: Any?): Any? = when (v) {
            is ByteArray -> mapOf("\$bytes" to Base64.getEncoder().encodeToString(v))
            is CidLink -> mapOf("\$link" to v.value)
            is List<*> -> v.map(::json)
            is Map<*, *> -> {
                require("\$bytes" !in v && "\$link" !in v) { "Reserved JSON marker used as object field" }
                v.entries.associate { it.key as String to json(it.value) }
            }
            else -> v
        }
        return jsonMapper.writeValueAsString(json(value)).also { require(utf8(it).size <= CanonicalCbor.MAX_BYTES) }
    }

    fun decode(text: String): Any? {
        var items = 0
        fun data(n: JsonNode, depth: Int): Any? {
            require(depth <= 16 && ++items <= 8192) { "JSON resource limit" }
            return when {
                n.isNull -> null
                n.isBoolean -> n.booleanValue()
                n.isTextual -> n.string()
                n.isIntegralNumber -> n.integer()
                n.isArray -> n.map { data(it, depth + 1) }
                n.isObject && n.has("\$bytes") -> {
                    n.exactFields(setOf("\$bytes"))
                    val s = n["\$bytes"].string()
                    require(s.matches(Regex("[A-Za-z0-9+/]*={0,2}")))
                    Base64.getDecoder().decode(s).also {
                        val canonical = Base64.getEncoder().encodeToString(it)
                        require(s == canonical || s == canonical.trimEnd('=')) { "Invalid Base64" }
                    }
                }
                n.isObject && n.has("\$link") -> {
                    n.exactFields(setOf("\$link"))
                    CidLink(n["\$link"].string().also { cidBytes(it, allowRaw = true) })
                }
                n.isObject -> n.properties().associate { it.key to data(it.value, depth + 1) }
                else -> error("Invalid atproto JSON value")
            }
        }
        return data(strictJson(text), 0).also { CanonicalCbor.encode(it) }
    }
}

internal fun extensionType(extension: Map<*, *>): String {
    val type = extension["\$type"] as? String ?: error("Extension requires a Lexicon type reference")
    val parts = type.split('#')
    val nsid = parts[0]
    require(nsid.length <= 317 && nsid.substringBeforeLast('.').length <= 253)
    require(nsid.matches(Regex("[a-zA-Z]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+(\\.[a-zA-Z][a-zA-Z0-9]{0,62})")))
    require(parts.size == 1 || parts.size == 2 && parts[1] != "main" && parts[1].matches(Regex("[a-zA-Z][a-zA-Z0-9_]*")))
    return type
}
