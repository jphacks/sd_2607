package org.mndt.core

import java.time.Instant

/**
 * Key attestations (`dev.jisaku.mosimo.attest`), chains embedded in posts (`attest.chain`) and revocation
 * notices (`attest.revocation`). Trust starts only at locally configured [Root]s; nothing in received data can
 * add one. A verified chain proves issuer statements about a key, never the truth of a post.
 */
object Attest {
    const val KEY_ATTESTATION = "dev.jisaku.mosimo.attest#keyAttestation"
    const val KEY_VALIDITY = "dev.jisaku.mosimo.attest#keyValidity"
    const val DELEGATION = "dev.jisaku.mosimo.attest#delegation"
    const val ORGANIZATION = "dev.jisaku.mosimo.attest#organization"
    const val ISSUER_DOMAIN = "dev.jisaku.mosimo.attest#issuerDomain"
    const val TRUST_LEVEL = "dev.jisaku.mosimo.attest#trustLevel"
    const val SUBKEY = "dev.jisaku.mosimo.attest.revocation.subkey"
    const val IMPORTER = "dev.jisaku.mosimo.attest.importer"
    const val CHAIN = "dev.jisaku.mosimo.attest.chain"
    const val BINDING_DECLARATION = "dev.jisaku.mosimo.attest.chain#bindingDeclaration"
    const val CONSENT = "Use this key pair for this DID's Mosimo publications"
    const val REVOCATION = "dev.jisaku.mosimo.attest.revocation"
    const val NOTICE = "dev.jisaku.mosimo.attest.revocation#notice"
    const val ACTIVATION = "dev.jisaku.mosimo.attest.revocation#activation"
    const val REVOCATION_LIST = "dev.jisaku.mosimo.attest.revocation.list"
    const val ROLE_ATTEST = "attest"
    const val ROLE_IDENTITY = "identity"
    const val ROLE_PUBLIC_INFORMATION = "public-information"
    const val ROLE_IMPORT = "import"
    /** attest §7: one attestation; attest.chain §2: one chain extension. */
    const val MAX_ATTESTATION_BYTES = 4096
    const val MAX_CHAIN_BYTES = 16384
    const val MAX_TEXT_BYTES = 256

    /** A locally trusted root key (stratum 0) with the roles and delegation depth the operator grants it. */
    data class Root(val pubkey: ByteArray, val name: String, val roles: Set<String>, val maxDepth: Int, val enabled: Boolean = true) {
        val pubkeyHex: String get() = hex(pubkey)
        init { publicKey(pubkey); require(maxDepth in 0..16 && utf8(name).size <= MAX_TEXT_BYTES) }
    }

    /** One parsed, signature-checked key attestation; [body] keeps every field, known or not. */
    class Attestation(val body: Map<String, Any?>, val sig: ByteArray) {
        val id: ByteArray = sha256(CanonicalCbor.encode(body))
        val issuer: ByteArray = publicKey(body["issuer"])
        val subject: ByteArray = publicKey(body["subject"])
        val ownerId: String = text(map(body["owner"])["id"])
        val ownerName: String = text(map(body["owner"])["name"])
        val roles: List<String> = sortedTexts(body["roles"], 32)
        val blocks: Map<String, Map<String, Any?>> = (body["blocks"] as? List<*> ?: error("Expected blocks")).also { require(it.size <= 16) }
            .map { block -> map(block).let { b -> text(b["\$type"]) to b.entries.associate { (k, v) -> k as String to v } } }
            .also { list -> require(list.map { it.first }.distinct().size == list.size) { "Duplicate block type" } }.toMap()
        val validFrom: Instant? = blocks[KEY_VALIDITY]?.get("from")?.let(::time)
        val validUntil: Instant? = blocks[KEY_VALIDITY]?.get("until")?.let(::time)
        val maxDepth: Long = blocks[DELEGATION]?.let { number(it["maxDepth"]) } ?: 0
        val orgId: String? = blocks[ORGANIZATION]?.let { text(it["orgId"]) }
        val orgName: String? = blocks[ORGANIZATION]?.let { text(it["orgName"]) }
        val issuerDomain: String? = blocks[ISSUER_DOMAIN]?.let { text(it["domain"]).lowercase() }
        val trustLevel: Long? = blocks[TRUST_LEVEL]?.let { number(it["level"]).also { l -> require(l <= 3) } }
        val revocationSubkey: ByteArray? = blocks[SUBKEY]?.let { publicKey(it["key"]) }
        /** importer block: botDid → types. Null when the block is absent. */
        val importerSources: Map<String, Set<String>>? = blocks[IMPORTER]?.let { block ->
            val sources = block["sources"] as? List<*> ?: error("Expected sources")
            require(sources.size <= 16)
            val parsed = sources.map { s -> map(s).let { text(it["botDid"]).also { d -> require(AtRepository.didPattern.matches(d)) } to sortedTexts(it["types"], 32, min = 1).toSet() } }
            require(parsed.zipWithNext().all { (a, b) -> lexical.compare(a.first, b.first) < 0 }) { "Unsorted sources" }
            require(parsed.sumOf { it.second.size } <= 64)
            parsed.toMap()
        }
        /** Display name for `identity`: the organization if any, else the owner. */
        val displayName: String get() = orgName ?: ownerName

        init {
            require(body["\$type"] == KEY_ATTESTATION) { "Not a key attestation" }
            require(body.keys == setOf("\$type", "issuer", "subject", "owner", "roles", "blocks")) { "Unexpected attestation fields" }
            require(map(body["owner"]).keys == setOf("id", "name"))
            require(!issuer.contentEquals(subject)) { "Self-signed attestation" }
            require(utf8(ownerId).size <= MAX_TEXT_BYTES && utf8(ownerName).size <= MAX_TEXT_BYTES)
            orgId?.let { require(utf8(it).size in 1..MAX_TEXT_BYTES && it.contains(':')) { "orgId must be <prefix>:<id>" } }
            orgName?.let { require(utf8(it).size <= MAX_TEXT_BYTES) }
            blocks[KEY_VALIDITY]?.let { require(it.keys.all { k -> k == "\$type" || k == "from" || k == "until" }) }
            if (validFrom != null && validUntil != null) require(validFrom < validUntil) { "Empty validity" }
            verifySignature(sig, body, issuer)
            require(CanonicalCbor.encode(mapOf("body" to body, "sig" to sig)).size <= MAX_ATTESTATION_BYTES) { "Attestation too large" }
        }

        /** `keyValidity`: from <= t < until; absent bounds are unlimited. */
        fun validAt(t: Instant): Boolean = (validFrom == null || !t.isBefore(validFrom)) && (validUntil == null || t.isBefore(validUntil))
        fun toMap(): Map<String, Any?> = mapOf("body" to body, "sig" to sig)
    }

    /** `{body, sig}` as carried in chains and FETCH responses. */
    fun attestation(v: Any?): Attestation {
        val signed = map(v)
        require(signed.keys == setOf("body", "sig")) { "Expected Signed<T>" }
        return Attestation(map(signed["body"]).entries.associate { (k, v2) -> k as String to v2 }, bytes(signed["sig"], 64))
    }

    enum class ChainState { VERIFIED, EXPIRED, INVALID, UNVERIFIABLE, UNKNOWN_TIME }

    /**
     * attest §5 result at the post's creation time plus, with a trusted clock, whether the chain is still valid
     * now. [leaf] is the last attestation when the structure verified (also for EXPIRED/UNKNOWN_TIME), so the UI can
     * name a "verified at creation" sender.
     */
    data class ChainResult(val state: ChainState, val reason: String, val root: Root? = null, val attestations: List<Attestation> = emptyList(),
        val expiredNow: Boolean? = null) {
        val leaf: Attestation? get() = attestations.lastOrNull()
    }

    /** One `attest.chain` extension of a post. */
    class Chain(val attestations: List<Attestation>, val binding: Map<String, Any?>?)

    /** Parses every `attest.chain` extension; a malformed one throws (the whole post is then "unverified"). */
    fun chains(event: NostrEvent): List<Chain> = AtRepository.extensions(event).filter { it["\$type"] == CHAIN }.map { ext ->
        require(ext.keys.all { it == "\$type" || it == "attestations" || it == "binding" }) { "Unexpected chain fields" }
        require(CanonicalCbor.encode(ext - "\$type").size <= MAX_CHAIN_BYTES) { "Chain too large" }
        val list = ext["attestations"] as? List<*> ?: error("Expected attestations")
        require(list.size in 1..16)
        Chain(list.map(::attestation), ext["binding"]?.let { map(it).entries.associate { (k, v) -> k as String to v } })
    }

    /**
     * attest §5 / attest.chain §3: issuer of the first element is a configured root, signatures, linkage, role and
     * block containment, then validity at [createdAt] and (if [now] is known) at now.
     */
    fun verifyChain(attestations: List<Attestation>, roots: List<Root>, createdAt: Instant, now: Instant?): ChainResult {
        if (attestations.isEmpty()) return ChainResult(ChainState.INVALID, "empty chain")
        val root = roots.firstOrNull { it.enabled && it.pubkey.contentEquals(attestations[0].issuer) }
            ?: return ChainResult(ChainState.UNVERIFIABLE, "unknown root")
        var parentRoles: Set<String> = root.roles
        var parentDepth = root.maxDepth.toLong()
        var parentFrom: Instant? = null
        var parentUntil: Instant? = null
        var parentOrg: String? = null
        var parentSources: Map<String, Set<String>>? = null
        for ((i, a) in attestations.withIndex()) {
            val leaf = i == attestations.lastIndex
            fun invalid(why: String) = ChainResult(ChainState.INVALID, "attestation $i: $why", root)
            if (i > 0 && !attestations[i - 1].subject.contentEquals(a.issuer)) return invalid("issuer is not the previous subject")
            if (!parentRoles.containsAll(a.roles)) return invalid("roles exceed the issuer's")
            if (!leaf && ROLE_ATTEST !in a.roles) return invalid("intermediate without attest role")
            if (i > 0 && a.validUntil == null) return invalid("stratum 2+ requires until")
            if (parentFrom != null && (a.validFrom == null || a.validFrom.isBefore(parentFrom))) return invalid("from precedes the issuer's")
            if (parentUntil != null && (a.validUntil == null || a.validUntil.isAfter(parentUntil))) return invalid("until exceeds the issuer's")
            if (a.maxDepth > parentDepth - 1) return invalid("delegation depth exceeds the issuer's")
            if (!leaf && a.maxDepth < 1) return invalid("intermediate without delegation")
            if (parentOrg != null && a.orgId != parentOrg) return invalid("organization differs from the issuer's")
            val sources = a.importerSources
            if (parentSources != null && sources != null && !sources.all { (bot, types) -> parentSources!![bot]?.containsAll(types) == true }) return invalid("importer sources exceed the issuer's")
            parentRoles = a.roles.toSet(); parentDepth = a.maxDepth; parentFrom = a.validFrom; parentUntil = a.validUntil
            parentOrg = a.orgId ?: parentOrg; parentSources = sources // importer §1: a child of a block-less issuer is unconstrained
        }
        if (now == null) return ChainResult(ChainState.UNKNOWN_TIME, "clock unknown", root, attestations)
        if (!attestations.all { it.validAt(createdAt) }) return ChainResult(ChainState.EXPIRED, "outside validity at creation", root, attestations)
        return ChainResult(ChainState.VERIFIED, "verified", root, attestations, expiredNow = !attestations.all { it.validAt(now) })
    }

    /**
     * attest.chain §1: a 32-byte leaf is the Nostr key itself; a 33-byte leaf is the atproto key, bound to the Nostr
     * key and the commit DID by the reciprocal declaration. Throws when the leaf does not belong to [event].
     */
    fun bindLeaf(leaf: Attestation, binding: Map<String, Any?>?, event: NostrEvent) {
        if (leaf.subject.size == 32) { require(leaf.subject.contentEquals(unhex(event.pubkey, 32))) { "leaf is not the author key" }; return }
        val b = binding ?: error("33-byte leaf requires a binding")
        require(b.keys == setOf("declaration", "atprotoSig", "nostrSig")) { "Unexpected binding fields" }
        val d = map(b["declaration"])
        require(d.keys == setOf("\$type", "did", "atprotoPubkey", "nostrPubkey", "consent") && d["\$type"] == BINDING_DECLARATION && d["consent"] == CONSENT) { "Unexpected declaration" }
        require(bytes(d["atprotoPubkey"], 33).contentEquals(leaf.subject) && bytes(d["nostrPubkey"], 32).contentEquals(unhex(event.pubkey, 32))) { "Binding keys mismatch" }
        val did = text(d["did"]).also { require(AtRepository.didPattern.matches(it)) }
        val digest = sha256(CanonicalCbor.encode(d))
        verifyDigest(bytes(b["atprotoSig"], 64), digest, leaf.subject)
        verifyDigest(bytes(b["nostrSig"], 64), digest, unhex(event.pubkey, 32))
        val commit = CanonicalCbor.decode(AtRepository.restore(event, leaf.subject).commitBytes) as Map<*, *>
        require(commit["did"] == did) { "Commit DID differs from the binding" }
    }

    // --- revocation ---

    /**
     * A `Revocation` whose notice signature is verified (attest.revocation §1-2). When its activation is not signed by
     * the subject itself, [pendingActivation]/[pendingSig] hold it: it can only be checked against a revocation sub-key
     * attested in the chain being evaluated (subkey §2-3), so it is kept until such a chain is seen.
     */
    class Revocation(val id: ByteArray, val issuer: ByteArray, val subject: ByteArray, val issuedAt: Instant, val effectiveAt: Instant,
        val pendingActivation: ByteArray? = null, val pendingSig: ByteArray? = null) {
        init { require((pendingActivation == null) == (pendingSig == null)) }
        val needsSubkey: Boolean get() = pendingSig != null
        /** Same notice and same activation path: a re-send that adds nothing (§5). */
        fun sameAs(o: Revocation) = id.contentEquals(o.id) && effectiveAt == o.effectiveAt && needsSubkey == o.needsSubkey &&
            (pendingSig?.contentEquals(o.pendingSig) ?: true)
    }

    /** Verifies `{notice, activation?}`. Null when the notice is pre-issued and carries no activation. */
    fun revocation(v: Any?): Revocation? {
        val r = map(v)
        require(r.keys.all { it == "\$type" || it == "notice" || it == "activation" } && (r["\$type"] == null || r["\$type"] == REVOCATION))
        val notice = map(r["notice"]); require(notice.keys == setOf("body", "sig"))
        val body = map(notice["body"])
        require(body["\$type"] == NOTICE && body.keys.all { it in setOf("\$type", "issuer", "subject", "issuedAt", "effectiveAt", "blocks") })
        val issuer = publicKey(body["issuer"]); val subject = publicKey(body["subject"])
        val issuedAt = time(body["issuedAt"])
        (body["blocks"] as? List<*> ?: error("Expected blocks")).forEach { map(it) }
        verifySignature(bytes(notice["sig"], 64), body, issuer)
        val id = sha256(CanonicalCbor.encode(body))
        body["effectiveAt"]?.let { return Revocation(id, issuer, subject, issuedAt, time(it)) }
        val activation = r["activation"]?.let(::map) ?: return null
        require(activation.keys == setOf("body", "sig"))
        val ab = map(activation["body"])
        require(ab["\$type"] == ACTIVATION && ab.keys == setOf("\$type", "noticeId", "effectiveAt"))
        require(bytes(ab["noticeId"], 32).contentEquals(id)) { "Activation for another notice" }
        val effectiveAt = time(ab["effectiveAt"]); require(!effectiveAt.isBefore(issuedAt)) { "Activation precedes issue" }
        val sig = bytes(activation["sig"], 64)
        if (runCatching { verifySignature(sig, ab, subject); true }.getOrDefault(false)) return Revocation(id, issuer, subject, issuedAt, effectiveAt)
        return Revocation(id, issuer, subject, issuedAt, effectiveAt, CanonicalCbor.encode(ab), sig)
    }

    enum class RevocationState { REVOKED, REVOKED_LATER, NOT_REVOKED_AS_KNOWN }

    /**
     * attest.revocation §6 for one chain: a notice applies when its issuer is the root, an ancestor issuer, or the
     * subject itself. [known] lists the revocations held for a subject key. A sub-key activation counts only when the
     * attestation of that subject in THIS chain names the signing sub-key (subkey §2).
     */
    fun revocationState(chain: ChainResult, known: (ByteArray) -> List<Revocation>, createdAt: Instant, seenAt: Instant): RevocationState {
        val root = chain.root ?: return RevocationState.NOT_REVOKED_AS_KNOWN
        val ancestors = mutableListOf(root.pubkey)
        var later = false
        for (a in chain.attestations) {
            for (r in known(a.subject)) {
                if (r.needsSubkey) {
                    val subkey = a.revocationSubkey ?: continue
                    if (!ancestors.any { it.contentEquals(r.issuer) }) continue
                    if (runCatching { verifyDigest(r.pendingSig!!, sha256(r.pendingActivation!!), subkey); true }.getOrDefault(false).not()) continue
                } else if (!(ancestors.any { it.contentEquals(r.issuer) } || r.issuer.contentEquals(a.subject))) continue
                if (!createdAt.isBefore(r.effectiveAt) || !seenAt.isBefore(r.effectiveAt)) return RevocationState.REVOKED
                later = true
            }
            ancestors += a.subject
        }
        return if (later) RevocationState.REVOKED_LATER else RevocationState.NOT_REVOKED_AS_KNOWN
    }

    /** attest.revocation §5: the single `Revocation` inside a control event. The carrier's signature is not a source of trust. */
    fun revocationOf(event: NostrEvent): Revocation? {
        if (event.kind != AtRepository.CONTROL_KIND || AtRepository.controlTopic(event) != AtRepository.REVOCATION_TOPIC) return null
        val ext = AtRepository.extensions(event).filter { it["\$type"] == REVOCATION }
        require(ext.size == 1) { "Exactly one revocation per event" }
        return revocation(ext.single())
    }

    /** `Signed<ListBody>` from `https://D/.well-known/mosimo/attest/revocations`; returns every verifiable inner revocation. */
    fun revocationList(bytes: ByteArray): List<Revocation> {
        require(bytes.size <= 1_048_576) { "Revocation list too large" }
        val signed = map(CanonicalCbor.decode(bytes))
        val body = map(signed["body"])
        require(body["\$type"] == REVOCATION_LIST)
        // The list signature only states what the publisher served; each notice stands on its own signature.
        return (body["revocations"] as? List<*> ?: error("Expected revocations")).mapNotNull { runCatching { revocation(it) }.getOrNull() }
    }

    // --- shared primitives (attest §1) ---

    /** 32 bytes: BIP-340 x-only; 33 bytes: SEC1 compressed. Must be a valid curve point. */
    fun publicKey(v: Any?): ByteArray {
        val key = v as? ByteArray ?: error("Expected public key bytes")
        when (key.size) {
            32 -> require(runCatching { secp.pubkeyParse(byteArrayOf(2) + key) }.isSuccess) { "Invalid x-only key" }
            33 -> require((key[0] == 2.toByte() || key[0] == 3.toByte()) && secp.pubKeyCompress(secp.pubkeyParse(key)).contentEquals(key)) { "Invalid compressed key" }
            else -> error("Invalid public key length")
        }
        return key
    }
    /** Schnorr for 32-byte keys, low-S compact ECDSA for 33-byte keys, over SHA-256 of the canonical CBOR body. */
    fun verifySignature(sig: ByteArray, body: Any?, key: ByteArray) = verifyDigest(sig, sha256(CanonicalCbor.encode(body)), key)
    internal fun verifyDigest(sig: ByteArray, digest: ByteArray, key: ByteArray) {
        require(sig.size == 64) { "Signature must be 64 bytes" }
        val ok = when (key.size) {
            32 -> secp.verifySchnorr(sig, digest, key)
            33 -> !secp.signatureNormalize(sig).second && secp.verify(sig, digest, key)
            else -> false
        }
        require(ok) { "Invalid signature" }
    }

    internal fun map(v: Any?): Map<*, *> = v as? Map<*, *> ?: error("Expected map")
    internal fun text(v: Any?): String = v as? String ?: error("Expected text")
    internal fun number(v: Any?): Long = (v as? Long ?: error("Expected integer")).also { require(it in 0..9007199254740991L) }
    internal fun bytes(v: Any?, size: Int): ByteArray = (v as? ByteArray ?: error("Expected bytes")).also { require(it.size == size) { "Expected $size bytes" } }
    internal fun time(v: Any?): Instant = text(v).let { require(AtRepository.timestamp.matches(it)) { "Timestamp must be UTC with Z" }; Instant.parse(it) }
    internal val lexical = Comparator<String> { a, b -> lexicographic(utf8(a), utf8(b)) }
    private fun lexicographic(x: ByteArray, y: ByteArray): Int {
        for (i in 0 until minOf(x.size, y.size)) { val c = (x[i].toInt() and 255).compareTo(y[i].toInt() and 255); if (c != 0) return c }
        return x.size.compareTo(y.size)
    }
    private fun sortedTexts(v: Any?, max: Int, min: Int = 0): List<String> = (v as? List<*> ?: error("Expected array")).map { text(it).also { t -> require(utf8(t).size <= MAX_TEXT_BYTES) } }
        .also { require(it.size in min..max && it.zipWithNext().all { (a, b) -> lexicographic(utf8(a), utf8(b)) < 0 }) { "Unsorted/duplicate/oversized list" } }
}
