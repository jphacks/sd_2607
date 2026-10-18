package org.mndt.core

import java.net.URI
import java.time.Instant
import java.util.Base64

/** Experimental 0020 wire-v1. Trust comes only from local configuration, never an event. */
object PublicIngress {
    const val IDENTITY = "dev.oriom.mosimo.defs#identity"
    const val BSAF = "dev.oriom.mosimo.defs#bsafV1"
    const val PRIORITY = "dev.oriom.mosimo.defs#priority"
    const val MAX_ROOTS = 8
    const val MAX_SERIALS = 256
    private const val SAFE = 9007199254740991L
    private val did = Regex("did:plc:[a-z2-7]{24}")
    data class Selection(val botDid: String, val type: String)
    data class Scope(val orgIds: Set<String>, val bsaf: Set<Selection>)
    data class Root(val rootId: String, val pubkey: String, val enabled: Boolean, val scope: Scope,
        val snapshotUrl: String, val minSequence: Long)
    data class Config(val roots: List<Root> = emptyList(), val selected: Set<Selection> = emptySet())
    /** Raw verified snapshot retains sequence/body hash/issuedAt/CRL even after expiry or root removal. */
    data class Memory(val snapshots: Map<String, ByteArray> = emptyMap(), val serials: Map<String, String> = emptyMap())
    data class Status(val authorSignature: String = "unknown", val binding: String = "unknown",
        val chainScope: String = "unknown", val certificateTime: String = "unknown", val revocation: String = "unknown")
    data class Receipt(val rootId: String, val certId: String, val botDid: String, val type: String,
        val sequence: Long, val issuedAt: String, val validUntil: String, val checkedAt: String, val priority: Int,
        val certificateNotBefore: String, val certificateNotAfter: String, val issuerSerial: String)
    data class Decision(val receipt: Receipt?, val status: Status, val reason: String, val memory: Memory)

    /** Cheap insertion-time check after worker validation; caller must fence configuration/history changes. */
    fun readyToInsert(receipt: Receipt, event: NostrEvent, memory: Memory, now: Instant?): Boolean = now != null &&
        memory.serials[receipt.issuerSerial] == receipt.certId &&
        now >= Instant.parse(receipt.certificateNotBefore) && now < Instant.parse(receipt.certificateNotAfter) &&
        now >= Instant.parse(receipt.issuedAt) && now < Instant.parse(receipt.validUntil) &&
        Instant.ofEpochSecond(event.createdAt) <= now && AtRepository.deliveryExpiresAt(event)?.let { it > now } == true

    private fun map(v: Any?): Map<*, *> = v as? Map<*, *> ?: error("Expected map")
    private fun exact(v: Any?, vararg fields: String): Map<*, *> = map(v).also { require(it.keys == fields.toSet()) { "Unexpected fields" } }
    private fun text(v: Any?): String = v as? String ?: error("Expected text")
    private fun number(v: Any?): Long = (v as? Long ?: error("Expected integer")).also { require(it in 0..SAFE) }
    private fun list(v: Any?): List<*> = v as? List<*> ?: error("Expected array")
    private fun label(v: Any?, max: Int): String = text(v).also {
        require(utf8(it).size in 1..max && it.none { c -> c < ' ' || c == '\u007f' }) { "Invalid label" }
    }
    private fun publicKey(v: Any?): String = text(v).also {
        val bytes = unhex(it, 33); require(bytes[0] == 2.toByte() || bytes[0] == 3.toByte())
        require(secp.pubKeyCompress(secp.pubkeyParse(bytes)).contentEquals(bytes)) { "Invalid curve point" }
    }
    private fun hashId(v: Any?): String = text(v).also { unhex(it, 32) }
    private fun time(v: Any?): Instant = text(v).let {
        require(it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")))
        Instant.parse(it).also { t -> require(t.epochSecond >= 0 && t.toString() == it) }
    }
    /** Source timestamps can have offsets/fractions; validate the calendar without normalizing the signed text. */
    private fun originalTime(v: Any?) {
        val match = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.[0-9]+)?(?:Z|[+-]([0-9]{2}):([0-9]{2}))").matchEntire(text(v))
            ?: error("Invalid original timestamp")
        val parts = (1..6).map { match.groupValues[it].toInt() }
        require(parts[0] >= 1 && parts[3] in 0..23 && parts[4] in 0..59 && parts[5] in 0..59) { "Invalid original calendar date" }
        java.time.LocalDate.of(parts[0], parts[1], parts[2])
        if (match.groupValues[7].isNotEmpty()) require(match.groupValues[7].toInt() in 0..23 && match.groupValues[8].toInt() in 0..59) { "Invalid original timestamp offset" }
    }
    private val lexical = Comparator<String> { a, b ->
        val x = utf8(a); val y = utf8(b)
        var result = 0
        for (i in 0 until minOf(x.size, y.size)) { result = (x[i].toInt() and 255).compareTo(y[i].toInt() and 255); if (result != 0) break }
        if (result != 0) result else x.size.compareTo(y.size)
    }
    private fun sorted(v: Any?, max: Int, min: Int = 1, check: (Any?) -> String): List<String> = list(v).map(check).also {
        require(it.size in min..max && it.zipWithNext().all { (a, b) -> lexical.compare(a, b) < 0 }) { "Unsorted/duplicate/oversized list" }
    }
    private fun scope(v: Any?): Scope {
        val s = exact(v, "orgIds", "bsaf")
        val orgs = sorted(s["orgIds"], 16) { label(it, 128) }.toSet()
        val bots = list(s["bsaf"]); require(bots.size in 1..16)
        val names = mutableListOf<String>()
        val pairs = bots.flatMap {
            val b = exact(it, "botDid", "types"); val bot = text(b["botDid"]).also { d -> require(did.matches(d)) }; names += bot
            sorted(b["types"], 32) { t -> label(t, 128) }.map { t -> Selection(bot, t) }
        }
        require(names.zipWithNext().all { (a, b) -> lexical.compare(a, b) < 0 } && pairs.size <= 64)
        return Scope(orgs, pairs.toSet())
    }
    private fun subset(child: Scope, parent: Scope) = parent.orgIds.containsAll(child.orgIds) && parent.bsaf.containsAll(child.bsaf)
    private fun newObject(v: Any?) {
        when (v) {
            is String, is Boolean -> Unit
            is Long -> number(v)
            is Map<*, *> -> v.forEach { (k, value) -> text(k); newObject(value) }
            is List<*> -> v.forEach(::newObject)
            else -> error("Unsupported wire-v1 value")
        }
    }
    private fun signature(sig: ByteArray, body: Any?, key: String) {
        require(sig.size == 64 && !secp.signatureNormalize(sig).second) { "Non-low-S/compact signature" }
        require(secp.verify(sig, sha256(CanonicalCbor.encode(body)), unhex(key, 33))) { "Invalid ECDSA signature" }
    }
    private fun signed(v: Any?, key: String): Map<*, *> {
        newObject(v)
        val s = exact(v, "body", "sig"); val body = map(s["body"])
        signature(unhex(text(s["sig"]), 64), body, key)
        return body
    }
    private fun digest(body: Any?) = hex(sha256(CanonicalCbor.encode(body)))

    fun config(json: String): Config {
        val node = strictJson(json)
        val value = CanonicalCbor.decode(CanonicalCbor.encode(jsonMapper.convertValue(node, Map::class.java)))
        val c = exact(value, "roots", "selected")
        val roots = list(c["roots"]).also { require(it.size <= MAX_ROOTS) }.map {
            val r = exact(it, "rootId", "rootPubkey", "enabled", "scope", "snapshotUrl", "minSequence")
            val key = publicKey(r["rootPubkey"]); val id = hashId(r["rootId"])
            require(id == hex(sha256(unhex(key, 33)))) { "rootId mismatch" }
            val url = text(r["snapshotUrl"]); val uri = URI(url)
            require(url.length <= 2048 && uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.userInfo == null && uri.fragment == null)
            Root(id, key, r["enabled"] as? Boolean ?: error("enabled required"), scope(r["scope"]), url,
                number(r["minSequence"]).also { require(it >= 1) })
        }
        require(roots.map { it.rootId }.distinct().size == roots.size)
        val selection = list(c["selected"]).also { require(it.size <= 64) }.map {
            val s = exact(it, "botDid", "type"); Selection(text(s["botDid"]).also { d -> require(did.matches(d)) }, label(s["type"], 128))
        }
        require(selection.distinct().size == selection.size)
        return Config(roots, selection.toSet())
    }

    private data class Snapshot(val body: Map<*, *>, val sequence: Long, val issued: Instant, val until: Instant,
        val certs: Set<String>, val keys: Set<String>)
    private fun snapshot(bytes: ByteArray, root: Root): Snapshot {
        require(bytes.size in 1..32768) { "Snapshot size limit" }
        val b = signed(CanonicalCbor.decode(bytes), root.pubkey)
        exact(b, "type", "version", "rootId", "sequence", "issuedAt", "validUntil", "revokedCertIds", "revokedPubkeys")
        require(b["type"] == "mosimo.oriom.dev/trust-snapshot" && b["version"] == 1L && b["rootId"] == root.rootId)
        val seq = number(b["sequence"]); require(seq >= 1)
        val issued = time(b["issuedAt"]); val until = time(b["validUntil"])
        require(until.epochSecond - issued.epochSecond in 1..3600)
        val certs = sorted(b["revokedCertIds"], 256, 0, ::hashId).toSet()
        val keys = sorted(b["revokedPubkeys"], 256, 0, ::publicKey).toSet()
        require(certs.size + keys.size <= 256)
        return Snapshot(b, seq, issued, until, certs, keys)
    }
    /** Caller MUST durably save returned memory before authorizing any event with it. */
    fun acceptSnapshot(root: Root, bytes: ByteArray, memory: Memory, now: Instant?): Memory {
        require(now != null) { "Clock unknown" }
        val next = snapshot(bytes, root)
        require(now >= next.issued && now < next.until) { "Snapshot stale/future" }
        require(next.sequence >= root.minSequence) { "Snapshot below configured minimum" }
        memory.snapshots[root.rootId]?.let {
            val old = snapshot(it, root)
            require(next.sequence >= old.sequence) { "Snapshot rollback" }
            if (next.sequence == old.sequence) require(digest(next.body) == digest(old.body)) { "Snapshot equivocation" }
            else require(next.issued >= old.issued && next.certs.containsAll(old.certs) && next.keys.containsAll(old.keys)) { "CRL rollback" }
        }
        require(root.rootId in memory.snapshots || memory.snapshots.size < MAX_ROOTS) { "Trust history full" }
        return memory.copy(snapshots = memory.snapshots + (root.rootId to bytes.copyOf()))
    }

    private fun extension(event: NostrEvent, type: String): Map<*, *> {
        val tag = event.tags.single { it.firstOrNull() == AtRepository.EXT_PREFIX + type }
        require(tag.size == 2)
        return map(CanonicalCbor.decode(B64.decode(tag[1]) ?: error("Invalid Base64")))
    }
    fun priority(event: NostrEvent): Int = runCatching {
        val p = extension(event, PRIORITY); exact(p, "version", "level")
        require(p["version"] == 1L); number(p["level"]).also { require(it <= 3) }.toInt()
    }.getOrDefault(1)

    fun verify(event: NostrEvent, config: Config, memory: Memory, now: Instant?): Decision {
        var status = Status()
        var stage = "resources"
        var updated = memory
        return try {
            require(event.tags.size <= 64 && event.tags.count { it.firstOrNull()?.startsWith(AtRepository.EXT_PREFIX) == true } <= 16) { "Event resource limit" }
            stage = "authorSignature"
            require(event.verify()) { "Invalid author signature" }
            status = status.copy(authorSignature = "valid")
            stage = "chainScope"
            require(EventCodec.encode(event).size <= 32768 && AtRepository.verifyEnvelope(event)) { "Invalid envelope/resource limit" }
            val identity = extension(event, IDENTITY); exact(identity, "version", "chain", "binding"); newObject(identity)
            require(CanonicalCbor.encode(identity + mapOf("\$type" to IDENTITY)).size <= 20480 && identity["version"] == 1L)
            val bsaf = extension(event, BSAF)
            require(bsaf.keys == setOf("tags", "langs", "original") || bsaf.keys == setOf("tags", "langs", "original", "botDefinition"))
            val tags = list(bsaf["tags"]).map { text(it).also { s -> require(utf8(s).size <= 640) } }; require(tags.size <= 8)
            require(tags.count { it.startsWith("bsaf:") } == 1 && "bsaf:v1" in tags)
            for (name in listOf("type", "value", "time", "target", "source")) require(tags.count { it.startsWith("$name:") } == 1 && tags.single { it.startsWith("$name:") }.length > name.length + 1)
            val langs = list(bsaf["langs"]); require(langs.size <= 3)
            langs.forEach {
                val lang = text(it)
                require(lang.length in 1..64 && lang.all { c -> c.code <= 127 })
                java.util.Locale.Builder().setLanguageTag(lang).build() // Validate only; retain the original spelling.
            }
            require("botDefinition" !in bsaf || bsaf["botDefinition"] != null)
            bsaf["botDefinition"]?.let { value ->
                val definition = map(value)
                require(definition.keys.contains("url") && definition.keys.all { it in setOf("url", "retrievedAt", "sha256") })
                val url = URI(label(definition["url"], 2048))
                require(url.isAbsolute && !url.host.isNullOrEmpty() && (url.scheme.equals("https", true) || url.scheme.equals("http", true))) { "Invalid bot definition URL" }
                if ("retrievedAt" in definition) originalTime(definition["retrievedAt"])
                if ("sha256" in definition) require((definition["sha256"] as? ByteArray)?.size == 32)
            }
            val original = exact(bsaf["original"], "uri", "cid", "createdAt")
            val uri = text(original["uri"])
            require(uri.matches(Regex("at://did:plc:[a-z2-7]{24}/app\\.bsky\\.feed\\.post/[A-Za-z0-9_~.:-]{1,512}")) && uri.substringAfterLast('/') !in listOf(".", "..")) { "Invalid original URI" }
            cidBytes(text(original["cid"])); originalTime(original["createdAt"])
            val selected = Selection(uri.removePrefix("at://").substringBefore('/'), label(tags.single { it.startsWith("type:") }.removePrefix("type:"), 128))
            require(selected in config.selected) { "Bot/type not selected" }
            val chain = list(identity["chain"]); require(chain.size == 1) { "Unsupported chain: root-to-leaf only" }
            require(CanonicalCbor.encode(chain[0]).size <= 4096)
            val rawBody = map(map(chain[0])["body"])
            val root = config.roots.singleOrNull { it.enabled && it.rootId == rawBody["rootId"] } ?: error("Unknown/disabled root")
            val b = signed(chain[0], root.pubkey)
            exact(b, "type", "version", "serial", "rootId", "issuerPubkey", "subjectPubkey", "subjectDid", "orgId", "orgName", "role", "permissions", "canDelegate", "scope", "notBefore", "notAfter")
            require(b["type"] == "mosimo.oriom.dev/identity-certificate" && b["version"] == 1L)
            val serial = text(b["serial"]); unhex(serial, 16)
            val certId = digest(b); val serialKey = root.pubkey + ":" + serial
            if (memory.serials[serialKey]?.let { it != certId } == true) {
                updated = memory.copy(serials = memory.serials + (serialKey to "equivocation"))
                error("Certificate serial equivocation")
            }
            val key = publicKey(b["subjectPubkey"])
            require(b["issuerPubkey"] == root.pubkey && key != root.pubkey && b["role"] == "importer" && b["canDelegate"] == false && b["permissions"] == listOf("bsaf-import")) { "Invalid leaf authority" }
            val subjectDid = text(b["subjectDid"]); require(did.matches(subjectDid))
            val org = label(b["orgId"], 128); label(b["orgName"], 256)
            val s = scope(b["scope"])
            require(s.orgIds == setOf(org) && subset(s, root.scope) && selected in s.bsaf) { "Scope violation" }
            status = status.copy(chainScope = "valid (root-to-leaf, bsaf-import only)")
            stage = "binding"
            val binding = exact(identity["binding"], "declaration", "atprotoSig", "nostrSig")
            val declaration = exact(binding["declaration"], "type", "version", "did", "atprotoPubkey", "nostrPubkey", "consent")
            require(declaration["type"] == "mndt.keyBinding" && declaration["version"] == 1L && declaration["did"] == subjectDid && declaration["atprotoPubkey"] == key && declaration["nostrPubkey"] == event.pubkey && declaration["consent"] == "Use this key pair for this DID's MNDT publications") { "Binding mismatch" }
            val atSig = text(binding["atprotoSig"]); val decoded = Base64.getDecoder().decode(atSig)
            require(atSig.length == 88 && Base64.getEncoder().encodeToString(decoded) == atSig)
            signature(decoded, declaration, key)
            require(secp.verifySchnorr(unhex(text(binding["nostrSig"]), 64), sha256(CanonicalCbor.encode(declaration)), unhex(event.pubkey, 32))) { "Invalid reciprocal signature" }
            val commit = map(CanonicalCbor.decode(AtRepository.restore(event, unhex(key, 33)).commitBytes))
            require(commit["did"] == subjectDid) { "Commit DID mismatch" }
            status = status.copy(binding = "valid (both keys + commit; not current PLC authentication)")
            stage = "certificateTime"
            val before = time(b["notBefore"]); val after = time(b["notAfter"])
            require(after.epochSecond - before.epochSecond in 1..31622400)
            require(now != null) { "Clock unknown" }
            require(now >= before && now < after && event.createdAt >= before.epochSecond && event.createdAt < after.epochSecond && Instant.ofEpochSecond(event.createdAt) <= now && AtRepository.deliveryExpiresAt(event)!! > now) { "Certificate/event/deadline outside validity" }
            status = status.copy(certificateTime = "valid at reception and creation")
            stage = "revocation"
            require(serialKey in memory.serials || memory.serials.size < MAX_SERIALS) { "Certificate history full" }
            updated = memory.copy(serials = memory.serials + (serialKey to certId))
            val bytes = memory.snapshots[root.rootId] ?: error("Snapshot unknown")
            val snap = snapshot(bytes, root)
            status = status.copy(revocation = "stale")
            require(snap.sequence >= root.minSequence && now >= snap.issued && now < snap.until) { "Snapshot stale/future/below minimum" }
            status = status.copy(revocation = "revoked")
            require(certId !in snap.certs && root.pubkey !in snap.keys && key !in snap.keys) { "Certificate/key revoked" }
            status = status.copy(revocation = "fresh-as-of(${snap.issued}, ${snap.until}, ${snap.sequence})")
            Decision(Receipt(root.rootId, certId, selected.botDid, selected.type, snap.sequence, snap.issued.toString(), snap.until.toString(), now.toString(), priority(event), before.toString(), after.toString(), serialKey), status, "Authorized importer testimony; original Bot signature/inclusion not verified", updated)
        } catch (e: Exception) {
            status = when (stage) {
                "authorSignature" -> status.copy(authorSignature = "invalid")
                "chainScope" -> status.copy(chainScope = "not-authorized/unsupported")
                "binding" -> status.copy(binding = "invalid")
                "certificateTime" -> status.copy(certificateTime = if (now == null) "unknown" else "invalid")
                else -> status
            }
            Decision(null, status, e.message ?: "Invalid/unsupported ingress", updated)
        }
    }
}
