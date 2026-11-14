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
    const val JMA_EARTHQUAKE = "dev.jisaku.mosimo.public.jma.earthquake"
    const val JMA_WEATHER = "dev.jisaku.mosimo.public.jma.weather"
    /** importer §1.1: the feed of re-posted JMA disaster-information XML; its types are the original `Control/Title`. */
    const val FEED_JMA_XML = "jma-xml"
    const val MAX_ROOTS = 8
    /** importer §4: (botDid, type) and (feed, type) pairs together. */
    const val MAX_SELECTED = 64
    /** One re-post source a receiver may select (importer §3): a Bot's `(botDid, type)` or a feed's `(feed, type)`. */
    sealed interface Selection {
        val type: String
        data class Bot(val botDid: String, override val type: String) : Selection { override fun toString() = "bot=$botDid type=$type" }
        data class Feed(val feed: String, override val type: String) : Selection { override fun toString() = "feed=$feed type=$type" }
    }
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
        val orgId: String?, val roles: List<String>, val trustLevel: Long?, val reason: String,
        /** importer §3 without the mesh-insertion conditions: an `import` leaf with an organization whose scope covers the post's
         *  (Bot DID, type) or (feed, type), and that pair locally selected. Decided from the post alone, however it arrived. */
        val importAuthorized: Boolean = false) {
        val identity: Boolean get() = Attest.ROLE_IDENTITY in roles
        val publicInformation: Boolean get() = Attest.ROLE_PUBLIC_INFORMATION in roles
        val confirmedNow: Boolean get() = state == Attest.ChainState.VERIFIED && expiredNow == false && revocation == Attest.RevocationState.NOT_REVOKED_AS_KNOWN
    }

    /** Authorization record kept with an inserted post (importer §3): never "official", only who re-posted what. */
    data class Receipt(val rootPubkey: String, val leafId: String, val source: Selection, val orgId: String, val orgName: String,
        val priority: Int, val checkedAt: String, val leafValidUntil: String?) {
        override fun toString() = "root=${rootPubkey.take(8)} leaf=${leafId.take(8)} org=$orgName($orgId) $source priority=$priority checked=$checkedAt"
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
            val type = s["type"]?.string()?.also { require(utf8(it).size in 1..128) } ?: error("Expected type")
            if (s.has("feed")) {
                s.exactFields(setOf("feed", "type"))
                Selection.Feed(s["feed"].string().also { require(utf8(it).size in 1..128) }, type)
            } else {
                s.exactFields(setOf("botDid", "type"))
                Selection.Bot(s["botDid"].string().also { require(AtRepository.didPattern.matches(it)) }, type)
            }
        }
        require(selected.distinct().size == selected.size) { "Duplicate selection" }
        return Config(roots, selected.toSet())
    }

    /** Where a trusted root came from: shipped with the app, shipped only in development builds, or added by the user. */
    enum class RootOrigin { BUILTIN, TEST, USER }
    data class RootEntry(val root: Attest.Root, val origin: RootOrigin)

    /**
     * The user's local trust choices: roots switched off (by key, hex) and roots added by hand. Kept apart from the shipped
     * config so an app update can change its roots without losing them. Never filled from received data (attest §3).
     */
    data class TrustChoices(val disabled: Set<String> = emptySet(), val added: List<Pair<String, String>> = emptyList()) {
        fun toJson(): String = mapper.writeValueAsString(mapOf("disabled" to disabled.sorted(),
            "added" to added.map { (key, name) -> mapOf("pubkey" to key, "name" to name) }))
        companion object {
            private val mapper = com.fasterxml.jackson.databind.ObjectMapper()
            fun parse(json: String?): TrustChoices = runCatching {
                val n = mapper.readTree(json!!)
                TrustChoices(n["disabled"].map { it.textValue()!! }.toSet(), n["added"].map { it["pubkey"].textValue()!! to it["name"].textValue()!! })
            }.getOrDefault(TrustChoices())
        }
    }
    /** Roles and depth given to a user-added root: it may certify importers and public-information issuers below it. */
    val USER_ROOT_ROLES = setOf(Attest.ROLE_IMPORT, Attest.ROLE_PUBLIC_INFORMATION, "attest")
    const val USER_ROOT_DEPTH = 2

    /** A root key typed by the user: 64 hex digits (x-only, as in Nostr and the attest chain). */
    fun rootKey(text: String): String? = text.trim().lowercase().takeIf { it.matches(Regex("[0-9a-f]{64}")) && runCatching { Attest.publicKey(unhex(it, 32)) }.isSuccess }

    /**
     * The config in force: the shipped [builtin] and, in development builds, [test] roots, plus user-added ones, each
     * switched by [choices]. Selections come from the shipped configs. A key listed twice keeps its first origin.
     */
    fun effective(builtin: Config, test: Config?, choices: TrustChoices): Pair<Config, List<RootEntry>> {
        val entries = LinkedHashMap<String, RootEntry>()
        fun add(root: Attest.Root, origin: RootOrigin) { entries.putIfAbsent(root.pubkeyHex, RootEntry(root, origin)) }
        builtin.roots.forEach { add(it, RootOrigin.BUILTIN) }
        test?.roots?.forEach { add(it, RootOrigin.TEST) }
        choices.added.forEach { (key, name) ->
            rootKey(key)?.let { add(Attest.Root(unhex(it, 32), name.take(64), USER_ROOT_ROLES, USER_ROOT_DEPTH), RootOrigin.USER) }
        }
        val list = entries.values.take(MAX_ROOTS).map { e -> e.copy(root = e.root.copy(enabled = e.root.enabled && e.root.pubkeyHex !in choices.disabled)) }
        return Config(list.map { it.root }, builtin.selected + test?.selected.orEmpty()) to list
    }

    /** priority §2: exactly one valid extension gives the hint; anything else is level 1. Never raised by itself. */
    fun priority(event: NostrEvent): Int = runCatching {
        val p = AtRepository.extensions(event).filter { it["\$type"] == PRIORITY }
        if (p.size != 1) return 1
        (p[0]["level"] as? Long ?: error("level"))!!.also { require(it in 0..3) }.toInt()
    }.getOrDefault(1)

    /**
     * priority §2: the author's hint when the sender is authorized now (a confirmed chain; for an `import` leaf, only a
     * locally authorized re-post), otherwise at most 1. Independent of how the event arrived.
     */
    fun effectivePriority(event: NostrEvent, view: ChainView?): Int {
        val hint = priority(event)
        val authorized = view != null && view.confirmedNow && (view.importAuthorized || Attest.ROLE_IMPORT !in view.roles)
        return if (authorized) hint else minOf(hint, 1)
    }

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
     * The pair a re-post claims to carry (importer §1.1, §2): `public.bsaf` with `original` gives (botDid, type); otherwise
     * exactly one `public.jma.earthquake` / `public.jma.weather` gives (`jma-xml`, its `message.control.title`).
     * Throws when the post is neither.
     */
    fun importSelection(event: NostrEvent): Selection {
        val ext = AtRepository.extensions(event)
        if (ext.any { it["\$type"] == BSAF }) return bsafOriginal(event).let { Selection.Bot(it.botDid, it.type) }
        val jma = ext.filter { it["\$type"] == JMA_EARTHQUAKE || it["\$type"] == JMA_WEATHER }.singleOrNull() ?: error("Not a bsaf or jma-xml re-post")
        val title = Attest.text(Attest.map(Attest.map(jma["message"])["control"])["title"])
        require(utf8(title).size in 1..128) { "Invalid Control/Title" }
        return Selection.Feed(FEED_JMA_XML, title)
    }

    /** importer §1: the leaf's `importer` block lists [s] among its pairs of the same kind. */
    fun covers(leaf: Attest.Attestation, s: Selection): Boolean = when (s) {
        is Selection.Bot -> leaf.importerSources?.get(s.botDid)?.contains(s.type) == true
        is Selection.Feed -> leaf.importerFeeds?.get(s.feed)?.contains(s.type) == true
    }

    /**
     * Evaluates every chain of [event] against [roots]; the first chain whose leaf belongs to the event decides.
     * [now] null means the clock is untrusted: the result is UNKNOWN_TIME, never VERIFIED.
     */
    fun chainView(event: NostrEvent, roots: List<Attest.Root>, revocations: RevocationStore, now: Instant?, seenAt: Instant,
        selected: Set<Selection> = emptySet()): ChainView? {
        val chains = runCatching { Attest.chains(event) }.getOrElse { return ChainView(Attest.ChainState.INVALID, null, null, null, null, emptyList(), null, "malformed chain: ${it.message}") }
        if (chains.isEmpty()) return null
        val createdAt = Instant.ofEpochSecond(event.createdAt)
        val selection = runCatching { importSelection(event) }.getOrNull()?.takeIf { it in selected }
        var best: ChainView? = null
        for (chain in chains) {
            val leaf = chain.attestations.last()
            val bound = runCatching { Attest.bindLeaf(leaf, chain.binding, event) }
            val result = if (bound.isFailure) Attest.ChainResult(Attest.ChainState.INVALID, "leaf: ${bound.exceptionOrNull()?.message}")
                else Attest.verifyChain(chain.attestations, roots, createdAt, now)
            val revocation = if (result.root != null) Attest.revocationState(result, revocations::of, createdAt, seenAt) else null
            val imported = selection != null && result.state == Attest.ChainState.VERIFIED && Attest.ROLE_IMPORT in leaf.roles &&
                leaf.orgId != null && covers(leaf, selection)
            val view = ChainView(result.state, result.expiredNow, revocation, result.leaf?.displayName, result.leaf?.orgId,
                result.leaf?.roles.orEmpty(), result.leaf?.trustLevel, result.reason, imported)
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
     * `importer` block covering (botDid, type) or (feed, type) and an `organization`, and no known revocation.
     */
    fun verify(event: NostrEvent, config: Config, revocations: RevocationStore, now: Instant?, seenAt: Instant = now ?: Instant.ofEpochSecond(event.createdAt)): Decision {
        return try {
            require(event.tags.size <= 64 && event.tags.count { it.firstOrNull()?.startsWith(AtRepository.EXT_PREFIX) == true } <= 16) { "Event resource limit" }
            require(event.verify()) { "Invalid author signature" }
            require(EventCodec.encode(event).size <= MeshPolicy.MAX_PAYLOAD_BYTES && AtRepository.verifyEnvelope(event)) { "Invalid envelope/resource limit" }
            val selection = importSelection(event)
            require(selection in config.selected) { "Source/type not selected" }
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
                if (leaf.importerSources == null) { failure = "leaf lacks importer block"; continue }
                val org = leaf.orgId ?: run { failure = "leaf lacks organization"; null } ?: continue
                if (!covers(leaf, selection)) { failure = "importer block does not cover $selection"; continue }
                if (revocation != Attest.RevocationState.NOT_REVOKED_AS_KNOWN) { failure = "revocation: $revocation"; continue }
                val receipt = Receipt(result.root!!.pubkeyHex, hex(leaf.id), selection, org, leaf.orgName ?: leaf.ownerName,
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
