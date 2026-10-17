package org.mndt.core

import java.math.BigInteger
import java.net.URI
import java.time.Instant
import java.util.Base64
import java.util.Collections

data class RepositoryChange(val path: String, val recordBytes: ByteArray, val commitBytes: ByteArray, val applyFromCommit: String?) {
    val recordCid: String get() = cid(recordBytes)
    val commitCid: String get() = cid(commitBytes)
}

/** Standard v3 commits carried in the protocol/0012 §3.2.4 base tags of one kind-1 Nostr event. */
object AtRepository {
    const val NAMESPACE = "mosimo.oriom.dev"
    const val TAG_PREFIX = "$NAMESPACE:"
    const val EXT_PREFIX = "${TAG_PREFIX}ext:"
    const val COLLECTION = "dev.oriom.mosimo.post"
    // ponytail: every post rebuilds the MST from all records (O(n)); keep persistent MST nodes if this grows.
    const val MAX_RECORDS = 10_000
    private val b64 = Base64.getEncoder()
    private val b64url = Base64.getUrlEncoder().withoutPadding()
    private val timestamp = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?Z")
    private val tidPattern = Regex("[234567abcdefghij][234567abcdefghijklmnopqrstuvwxyz]{12}")

    internal fun identity(keys: SecretKeys, endpoint: String): Pair<String, String> {
        // TODO(undecided): demo-only endpoint; the generated DID is NOT registered here.
        val serviceEndpoint = endpoint.ifBlank { "https://pds.invalid" }
        val uri = URI(serviceEndpoint)
        require(uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.userInfo == null && uri.fragment == null) { "PDS endpoint must be an absolute HTTPS URL" }
        val unsigned = linkedMapOf<String, Any?>(
            "type" to "plc_operation",
            "rotationKeys" to listOf(didKey(keys.plc)),
            "verificationMethods" to mapOf("atproto" to didKey(keys.atproto)),
            "alsoKnownAs" to emptyList<String>(),
            "services" to mapOf("atproto_pds" to mapOf("type" to "AtprotoPersonalDataServer", "endpoint" to serviceEndpoint)),
            "prev" to null
        )
        val signed = unsigned + ("sig" to b64url.encodeToString(secp.sign(sha256(CanonicalCbor.encode(unsigned)), keys.plc)))
        val did = "did:plc:" + base32(sha256(CanonicalCbor.encode(signed))).take(24)
        return did to jsonMapper.writeValueAsString(signed)
    }

    internal fun binding(keys: SecretKeys, did: String, nostrPubkey: String): String {
        // TODO(undecided): declaration schema, consent wording, signature encoding and delivery.
        val body = mapOf("type" to "mndt.keyBinding", "version" to 1, "did" to did,
            "atprotoPubkey" to hex(compressed(keys.atproto)), "nostrPubkey" to nostrPubkey,
            "consent" to "Use this key pair for this DID's MNDT publications")
        val hash = sha256(CanonicalCbor.encode(body))
        return jsonMapper.writeValueAsString(mapOf("declaration" to body,
            "atprotoSig" to b64.encodeToString(secp.sign(hash, keys.atproto)),
            "nostrSig" to hex(secp.signSchnorr(hash, keys.nostr, ByteArray(32).also(random::nextBytes)))))
    }

    /** Returns the 0012 §3.2.4 tags (protocol `t` tag included) and the next repository state. */
    internal fun create(keys: SecretKeys, did: String, text: String, nowMillis: Long, previous: RepositoryState,
        deliveryExpiresAtSeconds: Long, extensions: List<Map<String, Any?>>): Pair<List<List<String>>, RepositoryState> {
        require(previous.records.size < MAX_RECORDS) { "Repository limit: $MAX_RECORDS records" }
        require(previous.lastRevMicros >= 0 && (previous.previousCommit == null) == previous.records.isEmpty()) { "Inconsistent repository state" }
        previous.previousCommit?.let { cidBytes(it) }
        val micros = maxOf(Math.multiplyExact(nowMillis, 1000L), Math.addExact(previous.lastRevMicros, 1))
        // rev and record key are separate identifiers (0012 §2.1.1); one monotonic TID may serve both.
        val rev = tid(micros, previous.clockId)
        val path = "$COLLECTION/$rev"
        require(path !in previous.records) { "Refusing record overwrite" }
        val createdAt = Instant.ofEpochMilli(nowMillis).toString()
        val deliveryExpiresAt = Instant.ofEpochSecond(deliveryExpiresAtSeconds).toString()
        // Open unions need free-form data-model objects; do not rebuild them from known display fields.
        val extensionTags = extensions.map { extension ->
            listOf(EXT_PREFIX + extensionType(extension), B64.encode(CanonicalCbor.encode(extension - "\$type")))
        }
        val record = CanonicalCbor.encode(mapOf("\$type" to COLLECTION, "text" to text, "createdAt" to createdAt,
            "deliveryExpiresAt" to deliveryExpiresAt, "extensions" to extensions))
        val records = previous.records + (path to cid(record))
        val unsigned = linkedMapOf<String, Any?>("did" to did, "version" to 3, "data" to CidLink(mstRoot(records)), "rev" to rev, "prev" to null)
        val signature = secp.sign(sha256(CanonicalCbor.encode(unsigned)), keys.atproto)
        val commit = CanonicalCbor.encode(unsigned + ("sig" to signature))
        // No MST nodes/inclusion proof on this wire: the receiver rebuilds from its saved state.
        val tags = listOf(
            listOf("t", NAMESPACE),
            listOf(TAG_PREFIX + "version", "1"),
            listOf(TAG_PREFIX + "commit", B64.encode(commit)),
            listOf(TAG_PREFIX + "base", previous.previousCommit ?: ""),
            listOf(TAG_PREFIX + "path", path),
            listOf(TAG_PREFIX + "created-at", createdAt),
            listOf(TAG_PREFIX + "delivery-expires-at", deliveryExpiresAt),
        )
        return (tags + extensionTags) to previous.copy(records = Collections.unmodifiableMap(records), previousCommit = cid(commit), lastRevMicros = micros)
    }

    /** The signed delivery deadline (0012 §3.2.4); null when the event lacks a valid one. */
    fun deliveryExpiresAt(event: NostrEvent): Instant? = runCatching { Instant.parse(timestampTag(event, "delivery-expires-at")) }.getOrNull()

    /**
     * Checks Nostr, record reconstruction and commit shape. With [atprotoPubkey] (from the DID document) it also
     * checks the low-S ECDSA commit signature. Neither is DID authentication nor MST inclusion.
     */
    fun verifyEnvelope(event: NostrEvent, atprotoPubkey: ByteArray? = null): Boolean =
        runCatching { restore(event, atprotoPubkey); true }.getOrDefault(false)

    /** Rebuilds the exact record/commit bytes from the 0012 §3.2.4 tags. */
    fun restore(event: NostrEvent, atprotoPubkey: ByteArray? = null): RepositoryChange {
        require(event.verify() && event.kind == 1)
        require(event.tags.count { it == listOf("t", NAMESPACE) } == 1)
        require(field(event, "version") == "1") { "Unsupported base version" }
        val extensions = event.tags.filter { it.firstOrNull()?.startsWith(EXT_PREFIX) == true }.map { tag ->
            require(tag.size == 2)
            val fields = CanonicalCbor.decode(B64.decode(tag[1]) ?: error("Non-canonical Base64")) as? Map<*, *> ?: error("Expected extension object")
            require("\$type" !in fields)
            buildMap<String, Any?> { put("\$type", tag[0].removePrefix(EXT_PREFIX)); fields.forEach { (k, v) -> put(k as String, v) } }
                .also { extensionType(it) }
        }
        val path = field(event, "path")
        require(path.startsWith("$COLLECTION/") && tidPattern.matches(path.removePrefix("$COLLECTION/"))) { "Invalid repository path" }
        val createdAt = timestampTag(event, "created-at")
        require(Instant.parse(createdAt).epochSecond == event.createdAt)
        val record = CanonicalCbor.encode(mapOf("\$type" to COLLECTION, "text" to event.content, "createdAt" to createdAt,
            "deliveryExpiresAt" to timestampTag(event, "delivery-expires-at"), "extensions" to extensions))
        val change = RepositoryChange(path, record, B64.decode(field(event, "commit")) ?: error("Non-canonical Base64"),
            field(event, "base").ifEmpty { null })
        val commit = CanonicalCbor.decode(change.commitBytes) as? Map<*, *> ?: error("Invalid commit")
        require(commit.keys == setOf("did", "version", "data", "rev", "prev", "sig"))
        require(commit["version"] == 3L && commit["prev"] == null && commit["data"] is CidLink)
        cidBytes((commit["data"] as CidLink).value)
        require((commit["did"] as? String ?: error("Invalid DID")).matches(Regex("did:plc:[a-z2-7]{24}")))
        require(tidPattern.matches(commit["rev"] as? String ?: error("Invalid revision")))
        change.applyFromCommit?.let { cidBytes(it) }
        val sig = commit["sig"] as? ByteArray ?: error("Invalid signature")
        require(sig.size == 64 && !secp.signatureNormalize(sig).second) { "Non-low-S signature" }
        if (atprotoPubkey != null) {
            require(atprotoPubkey.size == 33 && (atprotoPubkey[0] == 2.toByte() || atprotoPubkey[0] == 3.toByte()))
            require(secp.verify(sig, sha256(CanonicalCbor.encode(commit.filterKeys { it != "sig" })), atprotoPubkey)) { "Invalid commit signature" }
        }
        return change
    }

    private fun field(event: NostrEvent, name: String): String =
        event.tags.single { it.firstOrNull() == TAG_PREFIX + name }.also { require(it.size == 2) }[1]
    private fun timestampTag(event: NostrEvent, name: String): String =
        field(event, name).also { require(timestamp.matches(it)) { "Timestamp must be UTC with Z" }; Instant.parse(it) }

    internal fun mstRoot(records: Map<String, String>): String {
        require(records.size <= MAX_RECORDS)
        val sorted = records.entries.sortedBy { it.key }
        val pathPattern = Regex("[a-zA-Z0-9_~.:-]+/[a-zA-Z0-9_~.:-]+")
        sorted.forEach { require(it.key.length <= 1024 && pathPattern.matches(it.key)); cidBytes(it.value) }
        val layers = sorted.map { layer(it.key) }
        fun node(from: Int, until: Int, depth: Int): CidLink? {
            if (from == until) return null
            require(depth >= 0)
            val pivots = (from until until).filter { layers[it] == depth }
            val left = node(from, pivots.firstOrNull() ?: until, depth - 1)
            val entries = mutableListOf<Map<String, Any?>>()
            var lastKey = ""
            for ((i, pivot) in pivots.withIndex()) {
                val key = sorted[pivot].key
                var prefix = 0
                while (prefix < minOf(lastKey.length, key.length) && lastKey[prefix] == key[prefix]) prefix++
                val right = node(pivot + 1, pivots.getOrNull(i + 1) ?: until, depth - 1)
                entries += mapOf("p" to prefix, "k" to utf8(key.substring(prefix)), "v" to CidLink(sorted[pivot].value), "t" to right)
                lastKey = key
            }
            return CidLink(cid(CanonicalCbor.encode(mapOf("l" to left, "e" to entries))))
        }
        return node(0, sorted.size, layers.maxOrNull() ?: 0)?.value ?: cid(CanonicalCbor.encode(mapOf("l" to null, "e" to emptyList<Any>())))
    }

    private fun layer(key: String): Int {
        var zeros = 0
        for (b in sha256(utf8(key))) {
            val n = b.toInt() and 255
            for (shift in 6 downTo 0 step 2) { if (((n ushr shift) and 3) != 0) return zeros; zeros++ }
        }
        return zeros
    }
    private fun tid(micros: Long, clockId: Int): String {
        require(micros in 1..(Long.MAX_VALUE ushr 10) && clockId in 0..1023) { "Timestamp outside TID range" }
        val alphabet = "234567abcdefghijklmnopqrstuvwxyz"
        val value = (micros shl 10) or clockId.toLong()
        return (12 downTo 0).joinToString("") { alphabet[((value ushr (it * 5)) and 31).toInt()].toString() }
    }
    private fun compressed(key: ByteArray): ByteArray = secp.pubKeyCompress(secp.pubkeyCreate(key))
    private fun didKey(key: ByteArray): String {
        val bytes = byteArrayOf(0xe7.toByte(), 1) + compressed(key)
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var n = BigInteger(1, bytes)
        val out = StringBuilder()
        while (n.signum() != 0) { val qr = n.divideAndRemainder(BigInteger.valueOf(58)); out.append(alphabet[qr[1].toInt()]); n = qr[0] }
        return "did:key:z" + out.reverse()
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
}

/** protocol/0012 §3.5: RFC 4648 standard alphabet, no padding, one canonical spelling. */
internal object B64 {
    private val encoder = Base64.getEncoder().withoutPadding()
    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)
    fun decode(s: String): ByteArray? {
        if (s.length > CanonicalCbor.MAX_BYTES * 4 / 3 + 4 || !s.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' }) return null
        return runCatching { Base64.getDecoder().decode(s) }.getOrNull()?.takeIf { encode(it) == s }
    }
}
