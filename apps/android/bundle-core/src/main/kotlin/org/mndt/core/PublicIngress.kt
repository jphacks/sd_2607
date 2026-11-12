package org.mndt.core

import java.net.URI
import java.time.Instant

/**
 * Receiver-side authorization of importer re-posts (`attest.importer` §3) before they enter the mesh from the
 * Internet. Trust comes only from the local [Config]; a relay's or server's own verification claims are ignored.
 */
object PublicIngress {
    const val BSAF = "dev.jisaku.mosimo.public.bsaf"
    const val PRIORITY = "dev.jisaku.mosimo.priority"
    const val MAX_ROOTS = 8
    const val MAX_SELECTED = 64
    data class Selection(val botDid: String, val type: String)
    data class Config(val roots: List<Attest.Root> = emptyList(), val selected: Set<Selection> = emptySet())

    /** Verified revocations held locally, keyed by subject key (hex). Never rolled back. */
    class RevocationStore(initial: Collection<Attest.Revocation> = emptyList()) {
        private val bySubject = linkedMapOf<String, MutableList<Attest.Revocation>>()
        init { initial.forEach(::add) }
        fun add(r: Attest.Revocation): Boolean {
            val list = bySubject.getOrPut(hex(r.subject)) { mutableListOf() }
            if (list.any { it.sameAs(r) }) return false
            require(size < MAX_REVOCATIONS) { "Revocation store full" }
            list += r
            return true
        }
        fun of(subject: ByteArray): List<Attest.Revocation> = bySubject[hex(subject)].orEmpty()
        fun all(): List<Attest.Revocation> = bySubject.values.flatten()
        val size: Int get() = bySubject.values.sumOf { it.size }
        companion object { const val MAX_REVOCATIONS = 4096 }
    }

    /** What the UI shows about a post's sender confirmation (attest.chain §4.3). */
    data class ChainView(val state: Attest.ChainState, val expiredNow: Boolean?, val revocation: Attest.RevocationState?, val name: String?,
        val orgId: String?, val roles: List<String>, val trustLevel: Long?, val reason: String) {
        val identity: Boolean get() = Attest.ROLE_IDENTITY in roles
        val publicInformation: Boolean get() = Attest.ROLE_PUBLIC_INFORMATION in roles
        val confirmedNow: Boolean get() = state == Attest.ChainState.VERIFIED && expiredNow == false && revocation == Attest.RevocationState.NOT_REVOKED_AS_KNOWN
    }

    /** Authorization record kept with an inserted post (importer §3): never "official", only who re-posted what. */
    data class Receipt(val rootPubkey: String, val leafId: String, val botDid: String, val type: String, val orgId: String, val orgName: String,
        val priority: Int, val checkedAt: String, val leafValidUntil: String?) {
        override fun toString() = "root=${rootPubkey.take(8)} leaf=${leafId.take(8)} org=$orgName($orgId) bot=$botDid type=$type priority=$priority checked=$checkedAt"
    }
    data class Decision(val receipt: Receipt?, val chain: ChainView?, val reason: String)

    fun config(json: String): Config {
        val node = strictJson(json)
        node.exactFields(setOf("roots", "selected"))
        require(node["roots"].isArray && node["roots"].size() <= MAX_ROOTS)
        val roots = node["roots"].map { r ->
            r.exactFields(setOf("pubkey", "name", "roles", "maxDepth", "enabled"))
            val key = r["pubkey"].string().let { unhex(it, it.length / 2) }
            require(r["roles"].isArray && r["roles"].size() <= 32)
            Attest.Root(key, r["name"].string(), r["roles"].map { it.string() }.toSet(), r["maxDepth"].integer().toInt(), r["enabled"].asBoolean())
        }
        require(roots.map { it.pubkeyHex }.distinct().size == roots.size) { "Duplicate root" }
        require(node["selected"].isArray && node["selected"].size() <= MAX_SELECTED)
        val selected = node["selected"].map { s ->
            s.exactFields(setOf("botDid", "type"))
            Selection(s["botDid"].string().also { require(AtRepository.didPattern.matches(it)) }, s["type"].string().also { require(utf8(it).size in 1..128) })
        }
        require(selected.distinct().size == selected.size) { "Duplicate selection" }
        return Config(roots, selected.toSet())
    }

    /** priority §2: exactly one valid extension gives the hint; anything else is level 1. Never raised by itself. */
    fun priority(event: NostrEvent): Int = runCatching {
        val p = AtRepository.extensions(event).filter { it["\$type"] == PRIORITY }
        if (p.size != 1) return 1
        (p[0]["level"] as? Long ?: error("level"))!!.also { require(it in 0..3) }.toInt()
    }.getOrDefault(1)

    /** Parsed BSAF extension of an import candidate: the original post's author DID and the `type:` tag value. */
    data class BsafOriginal(val botDid: String, val type: String, val uri: String, val cid: String)
    fun bsafOriginal(event: NostrEvent): BsafOriginal {
        val bsaf = AtRepository.extensions(event).filter { it["\$type"] == BSAF }.singleOrNull() ?: error("Exactly one bsaf extension required")
        require(bsaf.keys.all { it in setOf("\$type", "tags", "langs", "original", "botDefinition") })
        val tags = (bsaf["tags"] as? List<*> ?: error("tags")).map { Attest.text(it).also { t -> require(utf8(t).size <= 640) } }
        require(tags.size <= 8 && "bsaf:v1" in tags)
        val type = tags.filter { it.startsWith("type:") }.singleOrNull()?.removePrefix("type:")?.takeIf { it.isNotEmpty() } ?: error("type tag")
        val langs = bsaf["langs"] as? List<*> ?: error("langs"); require(langs.size <= 3); langs.forEach { Attest.text(it) }
        val original = Attest.map(bsaf["original"] ?: error("original required for imports"))
        require(original.keys == setOf("uri", "cid", "createdAt"))
        val uri = Attest.text(original["uri"])
        require(uri.matches(Regex("at://did:plc:[a-z2-7]{24}/app\\.bsky\\.feed\\.post/[A-Za-z0-9_~.:-]{1,512}"))) { "Invalid original URI" }
        val cid = Attest.text(original["cid"]); cidBytes(cid, allowRaw = true)
        Attest.text(original["createdAt"])
        bsaf["botDefinition"]?.let { v ->
            val d = Attest.map(v)
            require(d.keys.contains("url") && d.keys.all { it in setOf("url", "retrievedAt", "sha256") })
            val url = URI(Attest.text(d["url"]).also { require(it.length <= 2048) })
            require(url.isAbsolute && !url.host.isNullOrEmpty() && (url.scheme.equals("https", true) || url.scheme.equals("http", true))) { "Invalid bot definition URL" }
            d["retrievedAt"]?.let(Attest::time)
            d["sha256"]?.let { Attest.bytes(it, 32) }
        }
        return BsafOriginal(uri.removePrefix("at://").substringBefore('/'), type, uri, cid)
    }

    /**
     * Evaluates every chain of [event] against [roots]; the first chain whose leaf belongs to the event decides.
     * [now] null means the clock is untrusted: the result is UNKNOWN_TIME, never VERIFIED.
     */
    fun chainView(event: NostrEvent, roots: List<Attest.Root>, revocations: RevocationStore, now: Instant?, seenAt: Instant): ChainView? {
        val chains = runCatching { Attest.chains(event) }.getOrElse { return ChainView(Attest.ChainState.INVALID, null, null, null, null, emptyList(), null, "malformed chain: ${it.message}") }
        if (chains.isEmpty()) return null
        val createdAt = Instant.ofEpochSecond(event.createdAt)
        var best: ChainView? = null
        for (chain in chains) {
            val leaf = chain.attestations.last()
            val bound = runCatching { Attest.bindLeaf(leaf, chain.binding, event) }
            val result = if (bound.isFailure) Attest.ChainResult(Attest.ChainState.INVALID, "leaf: ${bound.exceptionOrNull()?.message}")
                else Attest.verifyChain(chain.attestations, roots, createdAt, now)
            val revocation = if (result.root != null) Attest.revocationState(result, revocations::of, createdAt, seenAt) else null
            val view = ChainView(result.state, result.expiredNow, revocation, result.leaf?.displayName, result.leaf?.orgId,
                result.leaf?.roles.orEmpty(), result.leaf?.trustLevel, result.reason)
            if (view.confirmedNow) return view
            if (best == null || rank(view) > rank(best)) best = view
        }
        return best
    }
    private fun rank(v: ChainView) = when (v.state) {
        Attest.ChainState.VERIFIED -> 4; Attest.ChainState.EXPIRED -> 3; Attest.ChainState.UNKNOWN_TIME -> 2; Attest.ChainState.UNVERIFIABLE -> 1; Attest.ChainState.INVALID -> 0
    }

    /**
     * importer §3: local selection, a chain verified at both creation and now whose leaf has the `import` role, an
     * `importer` block covering (botDid, type) and an `organization`, and no known revocation.
     */
    fun verify(event: NostrEvent, config: Config, revocations: RevocationStore, now: Instant?, seenAt: Instant = now ?: Instant.ofEpochSecond(event.createdAt)): Decision {
        return try {
            require(event.tags.size <= 64 && event.tags.count { it.firstOrNull()?.startsWith(AtRepository.EXT_PREFIX) == true } <= 16) { "Event resource limit" }
            require(event.verify()) { "Invalid author signature" }
            require(EventCodec.encode(event).size <= MeshPolicy.MAX_PAYLOAD_BYTES && AtRepository.verifyEnvelope(event)) { "Invalid envelope/resource limit" }
            val original = bsafOriginal(event)
            val selection = Selection(original.botDid, original.type)
            require(selection in config.selected) { "Bot/type not selected" }
            require(now != null) { "Clock unknown" }
            val createdAt = Instant.ofEpochSecond(event.createdAt)
            require(!createdAt.isAfter(now) && AtRepository.deliveryExpiresAt(event)?.isAfter(now) == true) { "Future event or past deadline" }
            val chains = Attest.chains(event)
            require(chains.isNotEmpty()) { "No attestation chain" }
            var view: ChainView? = null
            var failure = "No chain authorizes the import"
            for (chain in chains) {
                val leaf = chain.attestations.last()
                val bound = runCatching { Attest.bindLeaf(leaf, chain.binding, event) }
                if (bound.isFailure) { failure = "leaf: ${bound.exceptionOrNull()?.message}"; continue }
                val result = Attest.verifyChain(chain.attestations, config.roots, createdAt, now)
                val revocation = result.root?.let { Attest.revocationState(result, revocations::of, createdAt, seenAt) }
                view = ChainView(result.state, result.expiredNow, revocation, leaf.displayName, leaf.orgId, leaf.roles, leaf.trustLevel, result.reason)
                if (result.state != Attest.ChainState.VERIFIED) { failure = result.reason; continue }
                if (result.expiredNow == true) { failure = "chain expired now"; continue }
                if (Attest.ROLE_IMPORT !in leaf.roles) { failure = "leaf lacks import role"; continue }
                val sources = leaf.importerSources ?: run { failure = "leaf lacks importer block"; null } ?: continue
                val org = leaf.orgId ?: run { failure = "leaf lacks organization"; null } ?: continue
                if (sources[original.botDid]?.contains(original.type) != true) { failure = "importer block does not cover ${original.botDid}/${original.type}"; continue }
                if (revocation != Attest.RevocationState.NOT_REVOKED_AS_KNOWN) { failure = "revocation: $revocation"; continue }
                val receipt = Receipt(result.root!!.pubkeyHex, hex(leaf.id), original.botDid, original.type, org, leaf.orgName ?: leaf.ownerName,
                    priority(event), now.toString(), leaf.validUntil?.toString())
                return Decision(receipt, view, "Authorized importer testimony; original Bot signature/inclusion not verified")
            }
            Decision(null, view, failure)
        } catch (e: Exception) {
            Decision(null, null, e.message ?: "Invalid/unsupported ingress")
        }
    }

    /** Cheap re-check at insertion time: the leaf is still within validity and the deadline has not passed. */
    fun readyToInsert(receipt: Receipt, event: NostrEvent, now: Instant?): Boolean = now != null &&
        (receipt.leafValidUntil == null || now.isBefore(Instant.parse(receipt.leafValidUntil))) &&
        !Instant.ofEpochSecond(event.createdAt).isAfter(now) && AtRepository.deliveryExpiresAt(event)?.isAfter(now) == true
}
