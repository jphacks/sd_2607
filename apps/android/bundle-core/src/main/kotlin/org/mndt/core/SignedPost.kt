package org.mndt.core

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom
import java.time.Instant
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

/** [clockId] is the TID clock identifier, chosen once per repository and persisted with it. */
data class RepositoryState(val records: Map<String, String> = emptyMap(), val previousCommit: String? = null, val lastRevMicros: Long = 0,
    val clockId: Int = random.nextInt(1024))
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

internal fun expTag(deliveryExpiresAtSeconds: Long): List<String> =
    listOf(AtRepository.TAG_EXP, Instant.ofEpochSecond(deliveryExpiresAtSeconds).toString().also { require(AtRepository.secondsTimestamp.matches(it)) })

class Author(val keys: SecretKeys, val pdsEndpoint: String) {
    private val identity = AtRepository.identity(keys, pdsEndpoint)
    /** Derived offline; this is NOT a claim of PLC registration or DID authentication. */
    val did: String = identity.first
    val initialOperationJson: String = identity.second
    val nostrPubkey: String = hex(secp.pubkeyCreate(keys.nostr).copyOfRange(1, 33))
    /** attest.chain binding declaration signed by both keys (data-model map; `Bytes` are byte arrays). */
    val binding: Map<String, Any?> = AtRepository.binding(keys, did, nostrPubkey)
    /** Compressed atproto signing key; what a PDS would read from the DID document. */
    val atprotoPubkey: ByteArray get() = secp.pubKeyCompress(secp.pubkeyCreate(keys.atproto))

    fun createPost(text: String, nowMillis: Long, previous: RepositoryState = RepositoryState(),
        deliveryExpiresAtSeconds: Long = Math.addExact(nowMillis / 1000, 86400),
        extensions: List<Map<String, Any?>> = emptyList()): CreatedPost {
        require(nowMillis >= 0 && utf8(text).size <= 16384)
        require(deliveryExpiresAtSeconds > nowMillis / 1000 && deliveryExpiresAtSeconds <= 253402300799L) { "Invalid delivery deadline" }
        val restored = AtRepository.create(keys, did, text, nowMillis, previous, deliveryExpiresAtSeconds, extensions)
        return CreatedPost(sign(1, restored.first, text, nowMillis), restored.second)
    }

    /**
     * PDS §6: the signed genesis operation as its own control event, never inside posts, for online nodes to
     * submit unchanged. Tags: protocol topic, `mosimo.jisaku.dev/plc`, and the signed delivery deadline.
     */
    fun createPlcGenesisEvent(nowMillis: Long, deliveryExpiresAtSeconds: Long = Math.addExact(nowMillis / 1000, 86400)): NostrEvent {
        require(nowMillis >= 0 && deliveryExpiresAtSeconds > nowMillis / 1000)
        val tags = listOf(AtRepository.TOPIC_TAG, listOf("t", AtRepository.PLC_TOPIC), expTag(deliveryExpiresAtSeconds))
        return sign(AtRepository.CONTROL_KIND, tags, initialOperationJson, nowMillis)
    }

    /**
     * NIP-09 deletion request (core §11) for this author's own events. The MST-removal commit is not carried: its
     * delivery format is still open in core/PDS, and an undelivered removal would break the PDS's rebuild of later posts.
     */
    fun createDeletion(targetIds: List<String>, nowMillis: Long): NostrEvent {
        require(targetIds.isNotEmpty() && targetIds.size <= 16 && targetIds.all { it.matches(Regex("[0-9a-f]{64}")) })
        return signEvent(5, targetIds.map { listOf("e", it) } + listOf(listOf("k", "1")), "", nowMillis)
    }

    /** Any non-post kind (e.g. another app's DM, kind 4). Only the signed delivery deadline the mesh requires is appended. */
    fun signEvent(kind: Int, tags: List<List<String>>, content: String, nowMillis: Long,
        deliveryExpiresAtSeconds: Long = Math.addExact(nowMillis / 1000, 86400)): NostrEvent {
        require(kind != 1) { "Use createPost" }
        require(nowMillis >= 0 && deliveryExpiresAtSeconds > nowMillis / 1000)
        require(tags.none { it.firstOrNull() == AtRepository.TAG_EXP }) { "Deadline is appended here" }
        return sign(kind, tags + listOf(expTag(deliveryExpiresAtSeconds)), content, nowMillis)
    }

    /** NIP-04/NIP-44 ECDH: x coordinate of our Nostr key times the peer's x-only pubkey (even y). */
    fun sharedSecret(peerPubkey: String): ByteArray =
        secp.pubKeyTweakMul(secp.pubkeyParse(byteArrayOf(2) + unhex(peerPubkey, 32)), keys.nostr).copyOfRange(1, 33)

    private fun sign(kind: Int, tags: List<List<String>>, content: String, nowMillis: Long): NostrEvent {
        val frozen = frozenTags(tags)
        val digest = sha256(nostrSigningBytes(nostrPubkey, nowMillis / 1000, kind, frozen, content))
        val sig = secp.signSchnorr(digest, keys.nostr, ByteArray(32).also(random::nextBytes))
        return NostrEvent(hex(digest), nostrPubkey, nowMillis / 1000, kind, frozen, content, hex(sig)).also { require(it.verify()) }
    }
}

/**
 * Mesh §2.1 CBOR v1 payload: the fixed 7-element array, the §2.1.1 reversible tag-value typing and the §2.1.2
 * omitted tags. Attestation references (§2.1.3) are not produced or accepted: this node does not announce the
 * capability, so a payload must arrive fully expanded.
 */
object EventCodec {
    const val VERSION = 1L
    private fun bytesTag(name: String) = name == AtRepository.TAG_COMMIT || name.startsWith(AtRepository.EXT_PREFIX) && name.length > AtRepository.EXT_PREFIX.length

    /** The tags a payload omits for this kind; they are reinserted at the front on decode. */
    private fun omitted(kind: Int): List<List<String>> = when (kind) {
        1 -> listOf(AtRepository.TOPIC_TAG, AtRepository.VERSION_TAG)
        AtRepository.CONTROL_KIND -> listOf(AtRepository.TOPIC_TAG)
        else -> emptyList()
    }

    fun encode(event: NostrEvent): ByteArray {
        require(event.verify()) { "Invalid Nostr event" }
        val fixed = omitted(event.kind)
        require(event.tags.take(fixed.size) == fixed) { "Event does not follow the fixed tag order" }
        val tags = event.tags.drop(fixed.size).map { tag -> if (tag.size != 2) tag else listOf(tag[0], typed(tag[0], tag[1], event.createdAt)) }
        return CanonicalCbor.encode(listOf(VERSION, unhex(event.pubkey, 32), event.createdAt, event.kind, tags, event.content, unhex(event.sig, 64)))
    }

    /** Mesh §2.1.1: a value is typed only when the typed form restores exactly the original string. */
    private fun typed(name: String, value: String, createdAt: Long): Any = when {
        bytesTag(name) -> B64.decode(value) ?: value
        name == AtRepository.TAG_BASE -> if (value.isEmpty()) ByteArray(0) else Multibase32.decode(value) ?: value
        name == AtRepository.TAG_RKEY -> AtRepository.tidValue(value) ?: value
        name == AtRepository.TAG_EXP -> expDelta(value, createdAt) ?: value
        else -> value
    }
    private fun expDelta(value: String, createdAt: Long): Long? {
        if (!AtRepository.secondsTimestamp.matches(value)) return null
        val seconds = runCatching { Instant.parse(value).epochSecond }.getOrNull() ?: return null
        return (seconds - createdAt).takeIf { it >= 0 && expString(createdAt, it) == value }
    }
    private fun expString(createdAt: Long, delta: Long): String? =
        runCatching { Instant.ofEpochSecond(Math.addExact(createdAt, delta)).toString() }.getOrNull()?.takeIf { AtRepository.secondsTimestamp.matches(it) }

    fun decode(bytes: ByteArray): NostrEvent {
        require(bytes.size <= MeshPolicy.MAX_PAYLOAD_BYTES) { "payload size limit" }
        // Only the CDDL shape is fixed (mesh §2.1); signed inner bytes (commit, extensions) stay strictly canonical.
        val a = CanonicalCbor.decode(bytes, canonical = false) as? List<*> ?: error("Expected payload array")
        require(a.size == 7 && a[0] == VERSION) { "Unsupported mesh encoding version" }
        val pubkey = hex((a[1] as? ByteArray ?: error("Expected pubkey bytes")).also { require(it.size == 32) })
        val created = a[2] as? Long ?: error("Expected time integer")
        val kind = (a[3] as? Long ?: error("Expected kind integer")).also { require(it in 0..65535) }.toInt()
        val tags = (a[4] as? List<*> ?: error("Expected tags")).map { t ->
            val tag = t as? List<*> ?: error("Expected tag array")
            val name = tag.firstOrNull() as? String ?: error("Expected tag name")
            tag.mapIndexed { i, v ->
                when {
                    v is String -> v
                    i != 1 || tag.size != 2 -> error("Typed value outside position 1 of a two-element tag")
                    v is ByteArray && bytesTag(name) -> B64.encode(v)
                    v is ByteArray && name == AtRepository.TAG_BASE -> if (v.isEmpty()) "" else Multibase32.encode(v)
                    v is Long && name == AtRepository.TAG_RKEY -> AtRepository.tidOf(v).also { require(AtRepository.tidPattern.matches(it)) }
                    v is Long && name == AtRepository.TAG_EXP -> expString(created, v) ?: error("Deadline outside the Timestamp range")
                    else -> error("Unexpected typed tag value")
                }
            }
        }
        val content = a[5] as? String ?: error("Expected content")
        val sig = hex((a[6] as? ByteArray ?: error("Expected signature bytes")).also { require(it.size == 64) })
        val full = omitted(kind) + tags
        val id = hex(sha256(nostrSigningBytes(pubkey, created, kind, full, content)))
        return NostrEvent(id, pubkey, created, kind, frozenTags(full), content, sig).also { require(it.verify()) { "Invalid Nostr signature" } }
    }
}
