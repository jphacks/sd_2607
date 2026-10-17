package org.mndt.core

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom
import java.util.Collections

internal val secp = Secp256k1.get()
internal val random = SecureRandom()
internal val jsonMapper = ObjectMapper(JsonFactory().apply {
    enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(CanonicalCbor.MAX_BYTES).maxNumberLength(20).build())
})
internal fun strictJson(json: String): JsonNode {
    require(utf8(json).size <= CanonicalCbor.MAX_BYTES) { "JSON size limit" }
    return jsonMapper.factory.createParser(json).use { p ->
        val tree = jsonMapper.readTree<JsonNode>(p) ?: error("Empty JSON")
        require(p.nextToken() == null) { "Trailing JSON" }
        fun check(n: JsonNode) { require(!n.isFloatingPointNumber) { "Floats forbidden" }; if (n.isContainerNode) n.forEach { check(it) } }
        check(tree)
        tree
    }
}
internal fun JsonNode.exactFields(names: Set<String>) {
    require(isObject && fieldNames().asSequence().toSet() == names) { "Unexpected/missing JSON fields" }
}
internal fun JsonNode.string(): String { require(isTextual); return textValue() }
internal fun JsonNode.integer(): Long { require(isIntegralNumber && canConvertToLong()); return longValue() }
internal fun frozenTags(tags: List<List<String>>): List<List<String>> = Collections.unmodifiableList(tags.map { Collections.unmodifiableList(it.toList()) })

/** Three independent keys. Caller must encrypt these before persistence. */
data class SecretKeys(val nostr: ByteArray, val atproto: ByteArray, val plc: ByteArray) {
    init {
        require(listOf(nostr, atproto, plc).all { secp.secKeyVerify(it) }) { "Invalid secret key" }
        require(!nostr.contentEquals(atproto) && !nostr.contentEquals(plc) && !atproto.contentEquals(plc)) { "Key reuse forbidden" }
    }
    override fun toString(): String = "SecretKeys(nostr=<redacted>, atproto=<redacted>, plc=<redacted>)"
    companion object {
        fun generate(): SecretKeys {
            fun key(): ByteArray { while (true) { val b = ByteArray(32).also(random::nextBytes); if (secp.secKeyVerify(b)) return b } }
            return SecretKeys(key(), key(), key())
        }
    }
}

data class RepositoryState(val records: Map<String, String> = emptyMap(), val previousCommit: String? = null, val lastRevMicros: Long = 0)
data class CreatedPost(val event: NostrEvent, val repository: RepositoryState)

/** The ordinary NIP-01 fields; tags are never normalized or sorted. */
data class NostrEvent(val id: String, val pubkey: String, val createdAt: Long, val kind: Int, val tags: List<List<String>>, val content: String, val sig: String) {
    fun verify(): Boolean = runCatching {
        validate()
        val digest = sha256(signingBytes())
        id == hex(digest) && secp.verifySchnorr(unhex(sig, 64), digest, unhex(pubkey, 32))
    }.getOrDefault(false)

    internal fun validate() {
        unhex(id, 32); unhex(pubkey, 32); unhex(sig, 64)
        require(createdAt in 0..9007199254740991L && kind in 0..65535)
        // TODO(undecided): local demo limits; preserve all unknown author-signed tags.
        require(tags.size <= 256 && tags.all { it.size <= 32 })
        require(utf8(content).size <= 16384)
        require(signingBytes().size <= CanonicalCbor.MAX_BYTES)
    }
    internal fun signingBytes(): ByteArray = nostrSigningBytes(pubkey, createdAt, kind, tags, content)
    fun toJson(): String {
        validate()
        return "{\"id\":" + quoteJson(id) + ",\"pubkey\":" + quoteJson(pubkey) + ",\"created_at\":" + createdAt + ",\"kind\":" + kind + ",\"tags\":" + tagsJson(tags) + ",\"content\":" + quoteJson(content) + ",\"sig\":" + quoteJson(sig) + "}"
    }
    companion object {
        fun fromJson(json: String): NostrEvent {
            val n = strictJson(json)
            n.exactFields(setOf("id", "pubkey", "created_at", "kind", "tags", "content", "sig"))
            val k = n["kind"].integer(); require(k in 0..65535)
            require(n["tags"].isArray && n["tags"].size() <= 256)
            val tags = n["tags"].map { tag -> require(tag.isArray && tag.size() <= 32); tag.map { it.string() } }
            val event = NostrEvent(n["id"].string(), n["pubkey"].string(), n["created_at"].integer(), k.toInt(), frozenTags(tags), n["content"].string(), n["sig"].string())
            require(event.verify()) { "Invalid Nostr event ID/signature" }
            return event
        }
    }
}

/** Dedicated NIP-01 signing JSON, independent of Jackson's output defaults. */
internal fun quoteJson(s: String): String {
    utf8(s) // Reject lone surrogates instead of silently signing replacement characters.
    return buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\t' -> append("\\t")
            '\n' -> append("\\n")
            '\u000c' -> append("\\f")
            '\r' -> append("\\r")
            else -> if (c < ' ') append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
        }
        append('"')
    }
}
internal fun tagsJson(tags: List<List<String>>): String = tags.joinToString(",", "[", "]") { it.joinToString(",", "[", "]", transform = ::quoteJson) }
internal fun nostrSigningBytes(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): ByteArray =
    utf8("[0," + quoteJson(pubkey) + "," + createdAt + "," + kind + "," + tagsJson(tags) + "," + quoteJson(content) + "]")

class Author(val keys: SecretKeys, val pdsEndpoint: String) {
    private val identity = AtRepository.identity(keys, pdsEndpoint)
    /** Derived offline; this is NOT a claim of PLC registration or DID authentication. */
    val did: String = identity.first
    val initialOperationJson: String = identity.second
    val nostrPubkey: String = hex(secp.pubkeyCreate(keys.nostr).copyOfRange(1, 33))
    val bindingJson: String = AtRepository.binding(keys, did, nostrPubkey)

    fun createPost(text: String, nowMillis: Long, previous: RepositoryState = RepositoryState(),
        deliveryExpiresAtSeconds: Long = Math.addExact(nowMillis / 1000, 86400),
        extensions: List<Map<String, Any?>> = emptyList()): CreatedPost {
        require(nowMillis >= 0 && utf8(text).size <= 16384)
        require(deliveryExpiresAtSeconds > nowMillis / 1000 && deliveryExpiresAtSeconds <= 999999999999999L) { "Invalid delivery deadline" }
        val restored = AtRepository.create(keys, did, text, nowMillis, previous, deliveryExpiresAtSeconds, extensions)
        // TODO(undecided): experimental tag values, EID and lifetime; old signed events are never rewritten.
        val tags = frozenTags(listOf(
            listOf("mndt", "1", "dtn://mndt.local/~demo", "8", "1"),
            listOf("mndt-exp", deliveryExpiresAtSeconds.toString()),
            listOf("t", "mndt-experimental-v2"),
        ) + restored.first)
        val digest = sha256(nostrSigningBytes(nostrPubkey, nowMillis / 1000, 1, tags, text))
        val sig = secp.signSchnorr(digest, keys.nostr, ByteArray(32).also(random::nextBytes))
        val event = NostrEvent(hex(digest), nostrPubkey, nowMillis / 1000, 1, tags, text, hex(sig))
        require(event.verify())
        return CreatedPost(event, restored.second)
    }
}

/** TODO(undecided): provisional MNDT compact wire array from protocol/mndt.md. */
object EventCodec {
    fun encode(event: NostrEvent): ByteArray {
        require(event.verify()) { "Invalid Nostr event" }
        return CanonicalCbor.encode(listOf(1, unhex(event.pubkey, 32), event.createdAt, event.kind, event.tags, event.content, unhex(event.sig, 64)))
    }
    fun decode(bytes: ByteArray): NostrEvent {
        val a = CanonicalCbor.decode(bytes) as? List<*> ?: error("Expected payload array")
        require(a.size == 7 && a[0] == 1L)
        val pubkey = hex((a[1] as? ByteArray ?: error("Expected pubkey bytes")).also { require(it.size == 32) })
        val created = a[2] as? Long ?: error("Expected time integer")
        val kind = a[3] as? Long ?: error("Expected kind integer"); require(kind in 0..65535)
        val tags = (a[4] as? List<*> ?: error("Expected tags")).map { t -> (t as? List<*> ?: error("Expected tag array")).map { it as? String ?: error("Expected tag string") } }
        val content = a[5] as? String ?: error("Expected content")
        val sig = hex((a[6] as? ByteArray ?: error("Expected signature bytes")).also { require(it.size == 64) })
        val id = hex(sha256(nostrSigningBytes(pubkey, created, kind.toInt(), tags, content)))
        return NostrEvent(id, pubkey, created, kind.toInt(), frozenTags(tags), content, sig).also { require(it.verify()) { "Invalid Nostr signature" } }
    }
}
