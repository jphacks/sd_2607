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

/**
 * Core protocol (`dev.jisaku.mosimo`): one post = one record, one signed v3 commit, one kind-1 Nostr event whose
 * base tags carry the commit, apply-from CID, record key and delivery deadline in a fixed order (core §7.2).
 */
object AtRepository {
    const val NAMESPACE = "mosimo.jisaku.dev"
    const val TAG_PREFIX = "mosimo:"
    const val EXT_PREFIX = "${TAG_PREFIX}ext:"
    const val COLLECTION = "dev.jisaku.mosimo.post"
    const val BASE_VERSION = "1"
    /** All protocol-specific control events share one kind; the `t` topic and the extension `$type` tell them apart (core §7.5). */
    const val CONTROL_KIND = 8173
    const val PLC_TOPIC = "$NAMESPACE/plc"
    const val REVOCATION_TOPIC = "$NAMESPACE/revocation"
    const val TAG_VERSION = "${TAG_PREFIX}v"
    const val TAG_COMMIT = "${TAG_PREFIX}commit"
    const val TAG_BASE = "${TAG_PREFIX}base"
    const val TAG_RKEY = "${TAG_PREFIX}rkey"
    const val TAG_EXP = "${TAG_PREFIX}exp"
    const val TAG_CREATED = "${TAG_PREFIX}created"
    val TOPIC_TAG: List<String> = listOf("t", NAMESPACE)
    val VERSION_TAG: List<String> = listOf(TAG_VERSION, BASE_VERSION)
    private val BASE_TAGS = setOf(TAG_VERSION, TAG_COMMIT, TAG_BASE, TAG_RKEY, TAG_EXP, TAG_CREATED)
    // ponytail: every post rebuilds the MST from all records (O(n)); keep persistent MST nodes if this grows.
    const val MAX_RECORDS = 10_000
    private val b64url = Base64.getUrlEncoder().withoutPadding()
    /** Core §3.1 Timestamp: UTC, uppercase Z, optional fraction. */
    internal val timestamp = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?Z")
    /** The basic seconds-precision form required for `exp`. */
    internal val secondsTimestamp = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")
    internal val tidPattern = Regex("[234567abcdefghij][234567abcdefghijklmnopqrstuvwxyz]{12}")
    internal val didPattern = Regex("did:plc:[a-z2-7]{24}")

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
        val json = jsonMapper.writeValueAsString(signed)
        return plcDid(json) to json
    }

    /** The did:plc a signed genesis operation derives (DID PLC spec): base32(sha256(DAG-CBOR)), 24 chars. */
    fun plcDid(signedOperationJson: String): String {
        val op = jsonMapper.convertValue(strictJson(signedOperationJson), Map::class.java).entries.associate { it.key as String to it.value }
        return "did:plc:" + base32(sha256(CanonicalCbor.encode(op))).take(24)
    }

    /**
     * attest.chain §1 binding: both keys sign H(declaration). Carried inside a chain extension, never as its own
     * event; this device has no attestation chain of its own yet, so it is kept for export and tests.
     */
    internal fun binding(keys: SecretKeys, did: String, nostrPubkey: String): Map<String, Any?> {
        val declaration = mapOf("\$type" to Attest.BINDING_DECLARATION, "did" to did,
            "atprotoPubkey" to compressed(keys.atproto), "nostrPubkey" to unhex(nostrPubkey, 32), "consent" to Attest.CONSENT)
        val hash = sha256(CanonicalCbor.encode(declaration))
        return mapOf("declaration" to declaration, "atprotoSig" to secp.sign(hash, keys.atproto),
            "nostrSig" to secp.signSchnorr(hash, keys.nostr, ByteArray(32).also(random::nextBytes)))
    }

    /** Returns the core §7.2 tags (protocol `t` tag included) and the next repository state. */
    internal fun create(keys: SecretKeys, did: String, text: String, nowMillis: Long, previous: RepositoryState,
        deliveryExpiresAtSeconds: Long, extensions: List<Map<String, Any?>>): Pair<List<List<String>>, RepositoryState> {
        require(previous.records.size < MAX_RECORDS) { "Repository limit: $MAX_RECORDS records" }
        // Records imply a commit, not the reverse: deleting the last post leaves an empty repository whose
        // removal commit is the next post's base (delete §2).
        require(previous.lastRevMicros >= 0 && (previous.records.isEmpty() || previous.previousCommit != null)) { "Inconsistent repository state" }
        previous.previousCommit?.let { cidBytes(it) }
        val micros = maxOf(Math.multiplyExact(nowMillis, 1000L), Math.addExact(previous.lastRevMicros, 1))
        // rev and record key are separate identifiers (core §6.2); one monotonic TID may serve both.
        val rev = tid(micros, previous.clockId)
        val path = "$COLLECTION/$rev"
        require(path !in previous.records) { "Refusing record overwrite" }
        val exp = Instant.ofEpochSecond(deliveryExpiresAtSeconds).toString()
        require(secondsTimestamp.matches(exp)) { "Deadline outside the basic Timestamp form" }
        // Open unions need free-form data-model objects; do not rebuild them from known display fields.
        val extensionTags = extensions.map { extension ->
            listOf(EXT_PREFIX + extensionType(extension), B64.encode(CanonicalCbor.encode(extension - "\$type")))
        }
        // created_at carries seconds precision, so the optional sub-second `created` is omitted.
        val record = CanonicalCbor.encode(mapOf("\$type" to COLLECTION, "text" to text, "exp" to exp, "ext" to extensions))
        val records = previous.records + (path to cid(record))
        val unsigned = linkedMapOf<String, Any?>("did" to did, "version" to 3, "data" to CidLink(mstRoot(records)), "rev" to rev, "prev" to null)
        val signature = secp.sign(sha256(CanonicalCbor.encode(unsigned)), keys.atproto)
        val commit = CanonicalCbor.encode(unsigned + ("sig" to signature))
        // No MST nodes/inclusion proof on this wire: the receiver rebuilds from its saved state.
        val tags = listOf(
            TOPIC_TAG,
            VERSION_TAG,
            listOf(TAG_COMMIT, B64.encode(commit)),
            listOf(TAG_BASE, previous.previousCommit ?: ""),
            listOf(TAG_RKEY, rev),
            listOf(TAG_EXP, exp),
        )
        return (tags + extensionTags) to previous.copy(records = Collections.unmodifiableMap(records), previousCommit = cid(commit), lastRevMicros = micros)
    }

    /**
     * delete §2: removes `collection/<rkey>` from the repository and signs the commit of the resulting state.
     * Returns the commit bytes, the apply-from commit CID and the next repository state.
     */
    internal fun delete(keys: SecretKeys, did: String, rkey: String, nowMillis: Long, previous: RepositoryState): Triple<ByteArray, String, RepositoryState> {
        val path = "$COLLECTION/$rkey"
        val base = previous.previousCommit
        require(path in previous.records && base != null) { "Record not in this repository" }
        val micros = maxOf(Math.multiplyExact(nowMillis, 1000L), Math.addExact(previous.lastRevMicros, 1))
        val rev = tid(micros, previous.clockId)
        val records = previous.records - path
        val unsigned = linkedMapOf<String, Any?>("did" to did, "version" to 3, "data" to CidLink(mstRoot(records)), "rev" to rev, "prev" to null)
        val commit = CanonicalCbor.encode(unsigned + ("sig" to secp.sign(sha256(CanonicalCbor.encode(unsigned)), keys.atproto)))
        return Triple(commit, base, previous.copy(records = Collections.unmodifiableMap(records), previousCommit = cid(commit), lastRevMicros = micros))
    }

    /** delete §1: target of a deletion request (`e`, `k`) and, for a post, its removal commit. Null when malformed. */
    data class Deletion(val target: String, val kind: Int, val change: RepositoryChange?)
    fun deletion(event: NostrEvent): Deletion? = runCatching {
        val t = event.tags
        require(event.kind == 5 && t.size >= 4 && t[0] == TOPIC_TAG && t[1].size == 2 && t[1][0] == "e" && t[2].size == 2 && t[2][0] == "k")
        unhex(t[1][1], 32)
        val kind = t[2][1].toInt().also { require(it in 0..65535 && t[2][1] == it.toString()) }
        if (kind != 1) { require(t.size == 4 && t[3].firstOrNull() == TAG_EXP); return@runCatching Deletion(t[1][1], kind, null) }
        require(t.size == 7 && t[3].size == 2 && t[3][0] == TAG_COMMIT && t[4].size == 2 && t[4][0] == TAG_BASE &&
            t[5].size == 2 && t[5][0] == TAG_RKEY && t[6].firstOrNull() == TAG_EXP && tidPattern.matches(t[5][1]))
        cidBytes(t[4][1])
        Deletion(t[1][1], 1, RepositoryChange("$COLLECTION/${t[5][1]}", ByteArray(0), B64.decode(t[3][1]) ?: error("Base64"), t[4][1]))
    }.getOrNull()

    /** report §1: target event ID, target author and report type of a well-formed report. Null when malformed. */
    data class Report(val target: String, val author: String, val type: String)
    fun report(event: NostrEvent): Report? = runCatching {
        val t = event.tags
        require(event.kind == 1984 && t.size >= 4 && t[0] == TOPIC_TAG && t[1].size == 3 && t[1][0] == "e" && t[2].size == 3 &&
            t[2][0] == "p" && t[1][2] == t[2][2] && t[3].firstOrNull() == TAG_EXP)
        unhex(t[1][1], 32); unhex(t[2][1], 32)
        Report(t[1][1], t[2][1], t[1][2])
    }.getOrNull()

    /** The signed delivery deadline `mosimo:exp` of any kind of event; null when the event lacks a valid one. */
    fun deliveryExpiresAt(event: NostrEvent): Instant? = runCatching {
        val tag = event.tags.single { it.firstOrNull() == TAG_EXP }
        require(tag.size == 2 && secondsTimestamp.matches(tag[1]))
        Instant.parse(tag[1])
    }.getOrNull()

    /** Control events (core §7.5): kind 8173 with the protocol topic first and a per-kind topic second. */
    fun controlTopic(event: NostrEvent): String? =
        if (event.kind != CONTROL_KIND || event.tags.size < 2 || event.tags[0] != TOPIC_TAG) null
        else event.tags[1].takeIf { it.size == 2 && it[0] == "t" && it[1].startsWith("$NAMESPACE/") }?.get(1)

    /**
     * Checks Nostr, record reconstruction and commit shape. With [atprotoPubkey] (from the DID document) it also
     * checks the low-S ECDSA commit signature. Neither is DID authentication nor MST inclusion.
     */
    fun verifyEnvelope(event: NostrEvent, atprotoPubkey: ByteArray? = null): Boolean =
        runCatching { restore(event, atprotoPubkey); true }.getOrDefault(false)

    /** Decodes the self-contained extension tags of [event] in array order (core §7.3); throws on a malformed one. */
    fun extensions(event: NostrEvent): List<Map<String, Any?>> =
        event.tags.filter { it.firstOrNull()?.startsWith(EXT_PREFIX) == true }.map { tag ->
            require(tag.size == 2) { "Extension tag shape" }
            val fields = CanonicalCbor.decode(B64.decode(tag[1]) ?: error("Non-canonical Base64")) as? Map<*, *> ?: error("Expected extension object")
            require("\$type" !in fields) { "Duplicate \$type" }
            buildMap<String, Any?> { put("\$type", tag[0].removePrefix(EXT_PREFIX)); fields.forEach { (k, v) -> put(k as String, v) } }
                .also { extensionType(it) }
        }

    /** Rebuilds the exact record/commit bytes from the core §7.2 tags, enforcing their fixed order. */
    fun restore(event: NostrEvent, atprotoPubkey: ByteArray? = null): RepositoryChange {
        require(event.verify() && event.kind == 1)
        val tags = event.tags
        require(tags.size >= 6 && tags[0] == TOPIC_TAG && tags[1] == VERSION_TAG) { "Not a version 1 post" }
        require(tags.count { it == TOPIC_TAG } == 1)
        fun base(index: Int, name: String): String = tags[index].also { require(it.size == 2 && it[0] == name) { "Base tag order" } }[1]
        val commitBase64 = base(2, TAG_COMMIT)
        val baseCid = base(3, TAG_BASE)
        val rkey = base(4, TAG_RKEY)
        val exp = base(5, TAG_EXP)
        var next = 6
        val created = tags.getOrNull(6)?.takeIf { it.firstOrNull() == TAG_CREATED }?.let { next = 7; require(it.size == 2); it[1] }
        // Extension tags follow the base tags contiguously; anything after them is foreign and never a base tag.
        val extensionEnd = (next until tags.size).firstOrNull { !tags[it][0].startsWith(EXT_PREFIX) } ?: tags.size
        require((extensionEnd until tags.size).none { tags[it][0] in BASE_TAGS || tags[it][0].startsWith(EXT_PREFIX) }) { "Misplaced base or extension tag" }
        val extensions = extensions(event)
        require(tidPattern.matches(rkey)) { "Invalid record key" }
        require(secondsTimestamp.matches(exp)) { "Deadline must be the basic UTC form" }
        Instant.parse(exp)
        if (created != null) {
            require(timestamp.matches(created) && Instant.parse(created).epochSecond == event.createdAt) { "created must match created_at" }
        }
        val record = CanonicalCbor.encode(buildMap<String, Any?> {
            put("\$type", COLLECTION); put("text", event.content); put("exp", exp)
            if (created != null) put("created", created)
            put("ext", extensions)
        })
        val change = RepositoryChange("$COLLECTION/$rkey", record, B64.decode(commitBase64) ?: error("Non-canonical Base64"), baseCid.ifEmpty { null })
        val commit = CanonicalCbor.decode(change.commitBytes) as? Map<*, *> ?: error("Invalid commit")
        require(commit.keys == setOf("did", "version", "data", "rev", "prev", "sig"))
        require(commit["version"] == 3L && (commit["prev"] == null || commit["prev"] is CidLink) && commit["data"] is CidLink)
        cidBytes((commit["data"] as CidLink).value)
        require((commit["did"] as? String ?: error("Invalid DID")).matches(didPattern))
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

    /** Author DID from the commit; null for non-post events (e.g. PLC genesis). */
    fun did(event: NostrEvent): String? = runCatching { (CanonicalCbor.decode(restore(event).commitBytes) as Map<*, *>)["did"] as String }.getOrNull()

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
    private const val TID_ALPHABET = "234567abcdefghijklmnopqrstuvwxyz"
    private fun tid(micros: Long, clockId: Int): String {
        require(micros in 1..(Long.MAX_VALUE ushr 10) && clockId in 0..1023) { "Timestamp outside TID range" }
        return tidOf((micros shl 10) or clockId.toLong())
    }
    /** 13 base32-sortable characters of a 64-bit value (mesh §2.1.1 `mosimo:rkey`). */
    internal fun tidOf(value: Long): String {
        require(value >= 0)
        return (12 downTo 0).joinToString("") { TID_ALPHABET[((value ushr (it * 5)) and 31).toInt()].toString() }
    }
    /** The 64-bit value of a TID; null when [s] is not a TID (so the tag value stays a string). */
    internal fun tidValue(s: String): Long? {
        if (!tidPattern.matches(s)) return null
        var value = 0L
        for (c in s) value = (value shl 5) or TID_ALPHABET.indexOf(c).toLong()
        return value
    }
    internal fun compressed(key: ByteArray): ByteArray = secp.pubKeyCompress(secp.pubkeyCreate(key))
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

/** Core §3.3: RFC 4648 standard alphabet, no padding, one canonical spelling. */
internal object B64 {
    private val encoder = Base64.getEncoder().withoutPadding()
    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)
    fun decode(s: String): ByteArray? {
        if (s.length > CanonicalCbor.MAX_BYTES * 4 / 3 + 4 || !s.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' }) return null
        return runCatching { Base64.getDecoder().decode(s) }.getOrNull()?.takeIf { encode(it) == s }
    }
}

/** Multibase `b` (lowercase base32, no padding) as used by CID strings; only exact round trips decode. */
internal object Multibase32 {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
    fun decode(s: String): ByteArray? {
        if (s.length < 2 || s[0] != 'b' || s.length > CanonicalCbor.MAX_BYTES || !s.drop(1).all { it in ALPHABET }) return null
        val out = java.io.ByteArrayOutputStream(); var acc = 0; var bits = 0
        for (c in s.drop(1)) { acc = (acc shl 5) or ALPHABET.indexOf(c); bits += 5; if (bits >= 8) { bits -= 8; out.write((acc ushr bits) and 255) } }
        return out.toByteArray().takeIf { encode(it) == s }
    }
    fun encode(bytes: ByteArray): String = "b" + base32(bytes)
}
