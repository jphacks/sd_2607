package org.mndt.core

import java.math.BigInteger
import java.net.URI
import java.time.Instant
import java.util.Base64
import java.util.Collections

data class RepositoryChange(val path: String, val recordBytes: ByteArray, val commitBytes: ByteArray,
    val applyFromCommit: String?, val atprotoPubkey: String) {
    val recordCid: String get() = cid(recordBytes)
    val commitCid: String get() = cid(commitBytes)
}

/** Standard v3 commits; the surrounding field-by-field MNDT bindings are experimental. */
object AtRepository {
    // TODO(undecided): local binding version, tag namespace and Lexicon NSID/schema, not shared wire constants.
    const val VERSION = "2"
    const val TAG_PREFIX = "org.mndt.experimental.v2."
    const val COLLECTION = "xxx.disaster.post"
    const val MAX_RECORDS = 128
    private val b64 = Base64.getEncoder()
    private val b64url = Base64.getUrlEncoder().withoutPadding()

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

    internal fun create(keys: SecretKeys, did: String, text: String, nowMillis: Long, previous: RepositoryState,
        deliveryExpiresAtSeconds: Long, extensions: List<Map<String, Any?>>): Pair<List<List<String>>, RepositoryState> {
        require(previous.records.size < MAX_RECORDS) { "Demo repository limit: $MAX_RECORDS records" }
        require(previous.lastRevMicros >= 0 && (previous.previousCommit == null) == previous.records.isEmpty()) { "Inconsistent repository state" }
        previous.previousCommit?.let { cidBytes(it) }
        val micros = maxOf(Math.multiplyExact(nowMillis, 1000L), Math.addExact(previous.lastRevMicros, 1))
        val rev = tid(micros)
        val path = "$COLLECTION/$rev"
        require(path !in previous.records) { "Refusing record overwrite" }
        // Open unions need free-form data-model objects; do not rebuild them from known display fields.
        val extensionTags = extensions.mapIndexed { index, extension ->
            listOf(TAG_PREFIX + "extension:" + extensionType(extension), index.toString(), AtDataJson.encode(extension))
        }
        // TODO(undecided): field name and UTC timestamp representation follow the draft's placement proposal.
        val record = CanonicalCbor.encode(mapOf("\$type" to COLLECTION, "text" to text, "createdAt" to Instant.ofEpochMilli(nowMillis).toString(),
            "deliveryExpiresAt" to Instant.ofEpochSecond(deliveryExpiresAtSeconds).toString(), "extensions" to extensions))
        val recordCid = cid(record)
        val records = previous.records + (path to recordCid)
        val unsigned = linkedMapOf<String, Any?>("did" to did, "version" to 3, "data" to CidLink(mstRoot(records)), "rev" to rev, "prev" to null)
        val signature = secp.sign(sha256(CanonicalCbor.encode(unsigned)), keys.atproto)
        val commit = CanonicalCbor.encode(unsigned + ("sig" to signature))
        val commitCid = cid(commit)
        // No MST nodes/inclusion proof on this wire: the receiver rebuilds from its saved state.
        val tags = listOf(
            listOf(TAG_PREFIX + "commit", b64.encodeToString(commit)),
            listOf(TAG_PREFIX + "recordCid", recordCid),
            listOf(TAG_PREFIX + "base", previous.previousCommit ?: ""),
            listOf(TAG_PREFIX + "path", path),
            listOf(TAG_PREFIX + "action", "create"),
            listOf(TAG_PREFIX + "key", hex(compressed(keys.atproto))),
            listOf(TAG_PREFIX + "type", COLLECTION),
            listOf(TAG_PREFIX + "createdAt", Instant.ofEpochMilli(nowMillis).toString()),
        )
        return (tags + extensionTags) to RepositoryState(Collections.unmodifiableMap(records), commitCid, micros)
    }

    /**
     * Checks Nostr, exact record bytes/CID/body and low-S compact ECDSA commit signature.
     * The supplied key is NOT DID authentication. This does NOT establish MST inclusion:
     * a receiver with the applyFromCommit state must separately rebuild and compare data.
     */
    fun verifyEnvelope(event: NostrEvent): Boolean = runCatching { restore(event); true }.getOrDefault(false)

    /** Restores exact bytes; neither DID authentication nor MST inclusion verification. */
    fun restore(event: NostrEvent): RepositoryChange {
        require(event.verify() && event.kind == 1)
        val legacy = event.tags.any { it.firstOrNull() == "atproto" }
        val change = if (legacy) {
            require(event.tags.none { it.firstOrNull()?.startsWith(TAG_PREFIX) == true }) { "Mixed binding versions" }
            legacyChange(event)
        } else {
            require(event.tags.count { it == listOf("t", "mndt-experimental-v2") } == 1)
            fun field(name: String): String = event.tags.single { it.firstOrNull() == TAG_PREFIX + name }
                .also { require(it.size == 2) }[1]
            require(field("action") == "create")
            // TODO(undecided): per-type JSON tags with explicit array positions are local v2 bindings.
            val extensions = event.tags.filter { it.firstOrNull()?.startsWith(TAG_PREFIX + "extension:") == true }.map { tag ->
                require(tag.size == 3 && tag[1].matches(Regex("0|[1-9][0-9]{0,2}")))
                val extension = AtDataJson.decode(tag[2]) as? Map<*, *> ?: error("Expected extension object")
                require(tag[0] == TAG_PREFIX + "extension:" + extensionType(extension)) { "Extension type mismatch" }
                tag[1].toInt() to extension
            }.sortedBy { it.first }.mapIndexed { index, (position, extension) ->
                require(position == index) { "Missing/duplicate extension position" }
                extension
            }
            val record = mutableMapOf<String, Any?>("\$type" to field("type"), "text" to event.content, "createdAt" to field("createdAt"),
                "deliveryExpiresAt" to Instant.ofEpochSecond(deliveryExpiry(event)).toString(), "extensions" to extensions)
            for (tag in event.tags.filter { it.firstOrNull()?.startsWith(TAG_PREFIX + "field:") == true }) {
                require(tag.size == 2)
                val name = tag[0].removePrefix(TAG_PREFIX + "field:")
                require(name !in record) { "Duplicate/overridden record field" }
                record[name] = AtDataJson.decode(tag[1])
            }
            RepositoryChange(field("path"), CanonicalCbor.encode(record), decode64(field("commit")), field("base").ifEmpty { null }, field("key"))
                .also { require(it.recordCid == field("recordCid")) { "Record binding mismatch" } }
        }
        val record = CanonicalCbor.decode(change.recordBytes) as? Map<*, *> ?: error("Invalid record")
        require(record.keys.containsAll(setOf("\$type", "text", "createdAt")) && record["\$type"] == COLLECTION && record["text"] == event.content)
        if (record.containsKey("extensions")) {
            val extensions = record["extensions"] as? List<*> ?: error("Expected extensions array")
            extensions.forEach { extensionType(it as? Map<*, *> ?: error("Expected extension object")) }
        }
        require(Instant.parse(record["createdAt"] as String).epochSecond == event.createdAt)
        if (record.containsKey("deliveryExpiresAt")) {
            require(Instant.parse(record["deliveryExpiresAt"] as String) == Instant.ofEpochSecond(deliveryExpiry(event)))
        }
        val commit = CanonicalCbor.decode(change.commitBytes) as? Map<*, *> ?: error("Invalid commit")
        require(commit.keys == setOf("did", "version", "data", "rev", "prev", "sig"))
        require(commit["version"] == 3L && commit["prev"] == null && commit["data"] is CidLink)
        cidBytes((commit["data"] as CidLink).value)
        val did = commit["did"] as? String ?: error("Invalid DID")
        require(did.matches(Regex("did:plc:[a-z2-7]{24}")))
        val rev = commit["rev"] as? String ?: error("Invalid revision")
        require(rev.matches(Regex("[234567abcdefghij][234567abcdefghijklmnopqrstuvwxyz]{12}")))
        require(change.path == "$COLLECTION/$rev")
        change.applyFromCommit?.let { cidBytes(it) }
        val pub = unhex(change.atprotoPubkey, 33)
        require(pub[0] == 2.toByte() || pub[0] == 3.toByte())
        val sig = commit["sig"] as? ByteArray ?: error("Invalid signature")
        require(sig.size == 64 && !secp.signatureNormalize(sig).second) { "Non-low-S signature" }
        val unsigned = commit.filterKeys { it != "sig" }
        require(secp.verify(sig, sha256(CanonicalCbor.encode(unsigned)), pub)) { "Invalid commit signature" }
        return change
    }

    private fun deliveryExpiry(event: NostrEvent): Long = event.tags.single { it.firstOrNull() == "mndt-exp" }
        .also { require(it.size == 2 && it[1].matches(Regex("[1-9][0-9]{0,14}"))) }[1].toLong()

    private fun legacyChange(event: NostrEvent): RepositoryChange {
        val tag = event.tags.single { it.firstOrNull() == "atproto" }
        require(tag.size == 3 && tag[1] == "1")
        val env = strictJson(String(decode64(tag[2]), Charsets.UTF_8))
        env.exactFields(setOf("version", "did", "atprotoPubkey", "path", "recordCid", "commitCid", "applyFromCommit", "recordCbor", "commitCbor"))
        require(env["version"].integer() == 1L)
        val change = RepositoryChange(env["path"].string(), decode64(env["recordCbor"].string()), decode64(env["commitCbor"].string()),
            env["applyFromCommit"].takeUnless { it.isNull }?.string(), env["atprotoPubkey"].string())
        require(change.recordCid == env["recordCid"].string() && change.commitCid == env["commitCid"].string())
        require((CanonicalCbor.decode(change.commitBytes) as? Map<*, *>)?.get("did") == env["did"].string())
        return change
    }

    // ponytail: rebuild all <=128 entries; use persistent MST nodes beyond the demo ceiling.
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
    private fun tid(micros: Long): String {
        require(micros in 1..(Long.MAX_VALUE ushr 10)) { "Timestamp outside TID range" }
        val alphabet = "234567abcdefghijklmnopqrstuvwxyz"
        val value = micros shl 10 // clock ID zero is sufficient for this single-writer installation.
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
    private fun decode64(s: String): ByteArray {
        require(s.length <= CanonicalCbor.MAX_BYTES)
        return Base64.getDecoder().decode(s).also { require(b64.encodeToString(it) == s) { "Non-canonical Base64" } }
    }
}
