package org.mndt.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** importer §1 `feeds`, §1.1–§1.3 feed identifiers, declarations and scopes, and §3 authorization of feed re-posts. */
class ImporterFeedTest {
    private class Key { val sk = SecretKeys.generate().nostr; val pub: ByteArray = secp.pubkeyCreate(sk).copyOfRange(1, 33) }
    private fun sign(body: Map<String, Any?>, k: Key): ByteArray = secp.signSchnorr(sha256(CanonicalCbor.encode(body)), k.sk, ByteArray(32))
    private val now = Instant.parse("2026-11-11T00:00:00Z")
    private val later = now.plusSeconds(60)

    private val quake = "震源・震度に関する情報"
    private val rain = "気象警報・注意報（Ｒ０６）（大雨）"
    private val bot = "did:plc:vwi7zhfyamyvrg5v6ycjgzxi"

    private val jma = PublicIngress.FEED_JMA
    private val cap = "org.example.alerts.cap"
    private val us = "urn:oid:2.49.0.1.840.0"
    private val jp = "urn:oid:2.49.0.1.392.0"
    private fun feed(name: String, vararg types: String, origin: String? = null): Map<String, Any?> =
        mapOf("feed" to name) + listOfNotNull(origin?.let { "origin" to it }, types.takeIf { it.isNotEmpty() }?.let { "types" to it.toList() })
    private fun source(did: String, vararg types: String) = mapOf("botDid" to did, "types" to types.toList())
    private fun importer(sources: List<Any>? = null, feeds: List<Any>? = null): Map<String, Any?> =
        mapOf("\$type" to Attest.IMPORTER) + listOfNotNull(sources?.let { "sources" to it }, feeds?.let { "feeds" to it })

    private fun attest(issuer: Key, subject: ByteArray, roles: List<String>, depth: Long = 0, importer: Map<String, Any?>? = null): Map<String, Any?> {
        val blocks = listOfNotNull(
            mapOf("\$type" to Attest.KEY_VALIDITY, "from" to "2026-01-01T00:00:00Z", "until" to "2027-01-01T00:00:00Z"),
            if (depth > 0) mapOf("\$type" to Attest.DELEGATION, "maxDepth" to depth) else null,
            mapOf("\$type" to Attest.ORGANIZATION, "orgId" to "org:jma-importer", "orgName" to "転載運営"),
            importer,
        )
        val body = mapOf("\$type" to Attest.KEY_ATTESTATION, "issuer" to issuer.pub, "subject" to subject,
            "owner" to mapOf("id" to "o", "name" to "担当"), "roles" to roles.sorted(), "blocks" to blocks)
        return mapOf("body" to body, "sig" to sign(body, issuer))
    }

    private val root = Key(); private val ca = Key()
    private val roots = listOf(Attest.Root(root.pub, "test root", setOf("attest", "import"), 3))
    private val author = Author(SecretKeys.generate(), "https://pds.invalid")
    private val leafKey = unhex(author.nostrPubkey, 32)
    private val both = listOf(quake, rain).sortedWith(Attest.lexical).toTypedArray()
    private val caBlock = importer(sources = listOf(source(bot, "earthquake")), feeds = listOf(feed(jma, *both)))
    private fun chain(leafBlock: Map<String, Any?>? = importer(feeds = listOf(feed(jma, quake))), parentBlock: Map<String, Any?>? = caBlock) =
        listOf(attest(root, ca.pub, listOf("attest", "import"), depth = 2, importer = parentBlock), attest(ca, leafKey, listOf("import"), importer = leafBlock))

    private fun message(title: String) = mapOf(
        "control" to mapOf("title" to title, "dateTime" to "2026-11-11T08:59:00+09:00", "status" to "通常", "editorialOffice" to "気象庁本庁", "publishingOffice" to "気象庁"),
        "head" to mapOf("title" to title, "reportDateTime" to "2026-11-11T08:59:00+09:00", "eventID" to "20261111085500", "infoType" to "発表",
            "serial" to "1", "infoKind" to "地震情報", "infoKindVersion" to "1.0_1"))
    private fun earthquake(title: String = quake) = mapOf("\$type" to PublicIngress.JMA_EARTHQUAKE, "message" to message(title), "selection" to mapOf("kind" to "whole"), "body" to mapOf<String, Any?>())
    private fun weather(title: String = rain) = mapOf("\$type" to PublicIngress.JMA_WEATHER, "message" to message(title), "selection" to mapOf("kind" to "whole"),
        "headlineItems" to listOf<Any>(), "warnings" to listOf<Any>(), "meteorologicalItems" to listOf<Any>())
    private fun declared(id: String = jma, type: String = quake, origin: String? = null): Map<String, Any?> =
        mapOf("id" to id, "type" to type) + listOfNotNull(origin?.let { "origin" to it })
    private fun source(feed: Map<String, Any?>?): Map<String, Any?> =
        mapOf("\$type" to PublicIngress.SOURCE, "document" to mapOf("format" to "jma-xml-1.3")) + listOfNotNull(feed?.let { "feed" to it })
    private fun post(c: List<Map<String, Any?>> = chain(), ext: List<Map<String, Any?>> = listOf(earthquake(), source(declared()))) =
        author.createPost("気象庁の地震情報", now.toEpochMilli(), extensions = listOf(mapOf("\$type" to Attest.CHAIN, "attestations" to c)) + ext).event

    private val selected = setOf<PublicIngress.Selection>(PublicIngress.Selection.Feed(jma, type = quake), PublicIngress.Selection.Feed(jma, type = rain))
    private val config = PublicIngress.Config(roots, selected)
    private fun authorized(e: NostrEvent, sel: Set<PublicIngress.Selection> = selected) =
        PublicIngress.chainView(e, roots, PublicIngress.RevocationStore(), later, later, sel)!!.importAuthorized

    @Test fun feedsParseAndValidate() {
        val leaf = Attest.attestation(chain()[1])
        assertEquals(listOf(Attest.FeedScope(jma, null, setOf(quake))), leaf.importerFeeds)
        assertEquals(emptyMap<String, Set<String>>(), leaf.importerSources)
        val parent = Attest.attestation(chain()[0])
        assertEquals(setOf(bot), parent.importerSources!!.keys)
        assertNull(Attest.attestation(attest(ca, leafKey, listOf("import"))).importerFeeds)
        fun accepted(block: Map<String, Any?>) = Attest.attestation(attest(ca, leafKey, listOf("import"), importer = block)).importerFeeds!!
        fun rejected(block: Map<String, Any?>) = assertThrows(Exception::class.java) { Attest.attestation(attest(ca, leafKey, listOf("import"), importer = block)) }
        rejected(importer())                                                                    // neither sources nor feeds
        rejected(importer(feeds = emptyList()))                                                 // present but empty
        rejected(importer(feeds = listOf(mapOf("feed" to jma, "types" to emptyList<String>()))))  // empty types
        rejected(importer(feeds = listOf(feed(jma, quake, quake))))                             // duplicate type
        rejected(importer(feeds = listOf(feed(jma, *both.reversedArray()))))                   // unsorted types
        rejected(importer(feeds = listOf(feed(jma) + ("extra" to 1))))                          // unknown field
        rejected(importer(feeds = (0 until 33).map { feed("org.example.f$it") }.sortedWith { a, b -> Attest.lexical.compare(a["feed"] as String, b["feed"] as String) }))
        rejected(importer(feeds = listOf(feed(cap, *Array(33) { "t%02d".format(it) }))))       // more than 32 types
        // Feed IDs are NSIDs with a lowercase authority; the BSAF NSID is not a feed; origins are absolute URIs.
        for (id in listOf("jma-xml", "Dev.example.feed", "example.feed", "org.example.my-feed", "org.example.1feed", PublicIngress.BSAF, "a." + "b".repeat(320) + ".c")) {
            assertFalse(id, Attest.isFeedId(id)); rejected(importer(feeds = listOf(feed(id))))
        }
        assertTrue(Attest.isFeedId(jma)); assertTrue(Attest.isFeedId("org.example.alerts.Cap1"))
        for (o in listOf("not a uri", "urn:", "1x:y", "https://example.org/a b", "https://example.org/\u3000", "https://example.org/\u00A0", "https://example.org/\uFEFF", "https://example.org/\u0007", "https://example.org/" + "a".repeat(240))) {
            assertFalse(o, Attest.isOrigin(o)); rejected(importer(feeds = listOf(feed(cap, origin = o))))
        }
        assertTrue(Attest.isOrigin(us)); assertTrue(Attest.isOrigin("https://www.example.go.xx/"))
        // Sorted by feed, then origin with the absent origin first; (feed, origin) unique; absent types stand for every type.
        assertEquals(listOf(Attest.FeedScope(jma, null, setOf(quake)), Attest.FeedScope(cap), Attest.FeedScope(cap, jp, setOf("Flood")), Attest.FeedScope(cap, us)),
            accepted(importer(feeds = listOf(feed(jma, quake), feed(cap), feed(cap, "Flood", origin = jp), feed(cap, origin = us)))))
        rejected(importer(feeds = listOf(feed(cap, origin = us), feed(cap))))
        rejected(importer(feeds = listOf(feed(cap, origin = us), feed(cap, "x", origin = us))))
        rejected(importer(feeds = listOf(feed("org.example.b"), feed("org.example.a"))))
        rejected(importer(feeds = listOf(feed(jma, "x"), feed(jma, "y"))))
        // Explicit types of sources and feeds together count against the 64 limit; type-wide entries count none.
        val many = listOf(feed(jma, *Array(32) { "t%02d".format(it) }), feed(cap, *Array(32) { "t%02d".format(it) }))
        assertEquals(2, accepted(importer(feeds = many)).size)
        rejected(importer(sources = listOf(source(bot, "earthquake")), feeds = many))
        assertEquals(33, accepted(importer(sources = listOf(source(bot, "earthquake")), feeds = many.take(1) + feed(cap) + feed("org.example.z", "a"))).sumOf { it.types?.size ?: 0 })
    }

    @Test fun feedScopeContainment() {
        // Absent origin or types stand for every issuer or type; a post without origin needs an entry without origin.
        assertTrue(Attest.includesFeed(listOf(Attest.FeedScope(cap)), cap, us, "Flood"))
        assertTrue(Attest.includesFeed(listOf(Attest.FeedScope(cap, us)), cap, us, "Flood"))
        assertFalse(Attest.includesFeed(listOf(Attest.FeedScope(cap, us)), cap, jp, "Flood"))
        assertFalse(Attest.includesFeed(listOf(Attest.FeedScope(cap, us)), cap, null, "Flood"))
        assertFalse(Attest.includesFeed(listOf(Attest.FeedScope(cap, null, setOf("Fire"))), cap, us, "Flood"))
        assertFalse(Attest.includesFeed(listOf(Attest.FeedScope(cap, null, setOf("Fire"))), cap, us, null))
        assertFalse(Attest.includesFeed(null, cap, us, "Flood"))
        // Local selections use the same comparison.
        val sel = setOf<PublicIngress.Selection>(PublicIngress.Selection.Feed(cap, us), PublicIngress.Selection.Feed(jma, type = quake))
        assertTrue(PublicIngress.selects(sel, PublicIngress.Selection.Feed(cap, us, "Flood")))
        assertFalse(PublicIngress.selects(sel, PublicIngress.Selection.Feed(cap, jp, "Flood")))
        assertFalse(PublicIngress.selects(sel, PublicIngress.Selection.Feed(cap, null, "Flood")))
        assertTrue(PublicIngress.selects(sel, PublicIngress.Selection.Feed(jma, null, quake)))
        assertFalse(PublicIngress.selects(sel, PublicIngress.Selection.Feed(jma, null, rain)))
        assertFalse(PublicIngress.selects(sel, PublicIngress.Selection.Bot(bot, quake)))
    }

    @Test fun feedsMustLieWithinTheIssuers() {
        fun state(c: List<Map<String, Any?>>) = Attest.verifyChain(c.map(Attest::attestation), roots, now, now).state
        assertEquals(Attest.ChainState.VERIFIED, state(chain()))
        assertEquals(Attest.ChainState.INVALID, state(chain(leafBlock = importer(feeds = listOf(feed(jma, "震度速報"))))))
        assertEquals(Attest.ChainState.INVALID, state(chain(leafBlock = importer(feeds = listOf(feed(cap, quake))))))
        // A type-wide child needs a type-wide parent entry.
        assertEquals(Attest.ChainState.INVALID, state(chain(leafBlock = importer(feeds = listOf(feed(jma))))))
        assertEquals(Attest.ChainState.VERIFIED, state(chain(leafBlock = importer(feeds = listOf(feed(jma))), parentBlock = importer(feeds = listOf(feed(jma))))))
        // An origin-wide child needs an origin-wide parent entry; a parent without origin covers every origin.
        val wideCa = importer(feeds = listOf(feed(jma), feed(cap)))
        assertEquals(Attest.ChainState.VERIFIED, state(chain(leafBlock = importer(feeds = listOf(feed(cap, "Flood Warning", origin = us))), parentBlock = wideCa)))
        assertEquals(Attest.ChainState.VERIFIED, state(chain(leafBlock = importer(feeds = listOf(feed(cap, origin = us))), parentBlock = importer(feeds = listOf(feed(cap, origin = us))))))
        assertEquals(Attest.ChainState.INVALID, state(chain(leafBlock = importer(feeds = listOf(feed(cap))), parentBlock = importer(feeds = listOf(feed(cap, origin = us))))))
        assertEquals(Attest.ChainState.INVALID, state(chain(leafBlock = importer(feeds = listOf(feed(cap, origin = jp))), parentBlock = importer(feeds = listOf(feed(cap, origin = us))))))
        // The issuer's block has no feeds: the child may not have any (likewise sources).
        assertEquals(Attest.ChainState.INVALID, state(chain(parentBlock = importer(sources = listOf(source(bot, "earthquake"))))))
        assertEquals(Attest.ChainState.INVALID, state(chain(leafBlock = importer(sources = listOf(source(bot, "earthquake"))),
            parentBlock = importer(feeds = listOf(feed(jma, quake))))))
        // An issuer without the block leaves the child unconstrained.
        assertEquals(Attest.ChainState.VERIFIED, state(chain(parentBlock = null)))
    }

    @Test fun scopeComesFromTheNearestAncestorBlock() {
        fun state(c: List<Map<String, Any?>>) = Attest.verifyChain(c.map(Attest::attestation), roots, now, now).state
        val mid = Key()
        fun chain3(caBlock: Map<String, Any?>?, leafBlock: Map<String, Any?>?, caRoles: List<String> = listOf("attest", "import"),
            midRoles: List<String> = listOf("attest", "import"), leafRoles: List<String> = listOf("import")) = listOf(
            attest(root, ca.pub, caRoles, depth = 2, importer = caBlock), attest(ca, mid.pub, midRoles, depth = 1),
            attest(mid, leafKey, leafRoles, importer = leafBlock))
        val narrow = importer(feeds = listOf(feed(jma, quake)))
        // A block-less intermediate widens nothing: the leaf stays within the CA's block.
        assertEquals(Attest.ChainState.VERIFIED, state(chain3(narrow, narrow)))
        assertEquals(Attest.ChainState.INVALID, state(chain3(narrow, importer(feeds = listOf(feed(jma, *both))))))
        assertEquals(Attest.ChainState.INVALID, state(chain3(narrow, importer(feeds = listOf(feed(jma))))))
        assertEquals(Attest.ChainState.INVALID, state(chain3(narrow, importer(sources = listOf(source(bot, "earthquake"))))))
        // No ancestor block: any scope, as long as the direct issuer has the import role.
        assertEquals(Attest.ChainState.VERIFIED, state(chain3(null, importer(feeds = listOf(feed(jma), feed(cap))))))
        val noImport = chain3(null, narrow, caRoles = listOf("attest"), midRoles = listOf("attest"), leafRoles = emptyList())
        assertTrue(Attest.verifyChain(noImport.map(Attest::attestation), roots, now, now).reason.contains("without import"))
        assertEquals(Attest.ChainState.VERIFIED, state(chain3(null, null, caRoles = listOf("attest"), midRoles = listOf("attest"), leafRoles = emptyList())))
    }

    @Test fun postPairComesFromItsDeclaration() {
        assertEquals(PublicIngress.Selection.Feed(jma, null, quake), PublicIngress.importSelection(post()))
        assertEquals(PublicIngress.Selection.Feed(jma, null, rain), PublicIngress.importSelection(post(ext = listOf(weather(), source(declared(type = rain))))))
        // A known feed's declaration must match the content: one JMA extension, its title, no origin.
        fun rejected(ext: List<Map<String, Any?>>) = assertThrows(Exception::class.java) { PublicIngress.importSelection(post(ext = ext)) }
        rejected(listOf(earthquake(), source(declared(type = "震度速報"))))
        rejected(listOf(earthquake(), source(declared(origin = us))))
        rejected(listOf(earthquake(), weather(), source(declared())))
        rejected(listOf(source(declared())))
        // No declaration, two sources, or an invalid declaration: not a feed re-post. Extensions alone are not sniffed.
        rejected(listOf(earthquake()))
        rejected(listOf(earthquake(), source(null)))
        rejected(listOf(earthquake(), source(declared()), source(declared())))
        rejected(listOf(source(declared(id = "jma-xml"))))
        rejected(listOf(source(declared(id = PublicIngress.BSAF))))
        rejected(listOf(source(declared(id = cap, origin = "not a uri"))))
        rejected(listOf(source(declared(id = cap) + ("extra" to 1))))
        // An unknown feed is taken as declared, with its origin.
        assertEquals(PublicIngress.Selection.Feed(cap, us, "Flood Warning"), PublicIngress.importSelection(post(ext = listOf(source(declared(cap, "Flood Warning", us))))))
        // With public.bsaf the post is a Bot re-post: it must carry `original` and declare no feed.
        val bsaf = mapOf("\$type" to PublicIngress.BSAF, "tags" to listOf("bsaf:v1", "type:earthquake"), "langs" to listOf("ja"))
        rejected(listOf(earthquake(), bsaf))
        rejected(listOf(bsaf, source(declared())))
    }

    @Test fun feedRePostIsAuthorizedOnlyWhenCoveredAndSelected() {
        val e = post()
        assertTrue(authorized(e))
        val receipt = PublicIngress.verify(e, config, PublicIngress.RevocationStore(), later).receipt!!
        assertEquals(PublicIngress.Selection.Feed(jma, null, quake), receipt.source)
        // A type-wide selection covers the post too.
        assertTrue(authorized(e, setOf(PublicIngress.Selection.Feed(jma))))
        // Not selected locally.
        assertFalse(authorized(e, setOf(PublicIngress.Selection.Feed(jma, type = rain))))
        assertFalse(authorized(e, setOf(PublicIngress.Selection.Feed(jma, us))))
        assertNull(PublicIngress.verify(e, PublicIngress.Config(roots, emptySet()), PublicIngress.RevocationStore(), later).receipt)
        // Selected, but the leaf's block does not list the title.
        val weatherPost = post(ext = listOf(weather(), source(declared(type = rain))))
        assertFalse(authorized(weatherPost))
        assertNull(PublicIngress.verify(weatherPost, config, PublicIngress.RevocationStore(), later).receipt)
        // A Bot-only leaf does not cover a feed pair even with the same type name.
        assertFalse(authorized(post(chain(leafBlock = importer(sources = listOf(source(bot, "earthquake")))))))
        // A declaration that does not match the content is never authorized.
        assertFalse(authorized(post(ext = listOf(earthquake(), source(declared(type = rain)))), setOf(PublicIngress.Selection.Feed(jma))))
        assertFalse(authorized(post(ext = listOf(earthquake(), weather(), source(declared())))))
    }

    @Test fun unknownFeedIsAuthorizedAsDeclared() {
        val caBlock = importer(sources = listOf(source(bot, "earthquake")), feeds = listOf(feed(jma, *both), feed(cap)))
        val leafBlock = importer(feeds = listOf(feed(jma, quake), feed(cap, "Flood Warning", origin = us)))
        val capPost = post(chain(leafBlock, caBlock), listOf(source(declared(cap, "Flood Warning", us))))
        val sel = setOf<PublicIngress.Selection>(PublicIngress.Selection.Feed(cap))
        assertTrue(authorized(capPost, sel))
        assertEquals(PublicIngress.Selection.Feed(cap, us, "Flood Warning"), PublicIngress.verify(capPost, PublicIngress.Config(roots, sel), PublicIngress.RevocationStore(), later).receipt!!.source)
        assertFalse(authorized(capPost, setOf(PublicIngress.Selection.Feed(cap, jp))))
        // Another issuer is outside the leaf's scope even when selected.
        assertFalse(authorized(post(chain(leafBlock, caBlock), listOf(source(declared(cap, "Flood Warning", jp)))), sel))
        // An origin-wide leaf below an origin-limited CA is an escalation.
        val narrow = chain(importer(feeds = listOf(feed(cap))), importer(feeds = listOf(feed(cap, origin = us))))
        assertFalse(authorized(post(narrow, listOf(source(declared(cap, "Flood Warning", us)))), sel))
    }

    @Test fun configAcceptsBotAndFeedSelections() {
        val c = PublicIngress.config("""{"roots":[],"selected":[{"botDid":"$bot","type":"earthquake"},{"feed":"$jma","type":"$quake"},{"feed":"$cap","origin":"$us"},{"feed":"$cap"}]}""")
        assertEquals(setOf(PublicIngress.Selection.Bot(bot, "earthquake"), PublicIngress.Selection.Feed(jma, type = quake),
            PublicIngress.Selection.Feed(cap, us), PublicIngress.Selection.Feed(cap)), c.selected)
        assertThrows(Exception::class.java) { PublicIngress.config("""{"roots":[],"selected":[{"feed":"$jma","botDid":"$bot","type":"x"}]}""") }
        assertThrows(Exception::class.java) { PublicIngress.config("""{"roots":[],"selected":[{"feed":"","type":"x"}]}""") }
        assertThrows(Exception::class.java) { PublicIngress.config("""{"roots":[],"selected":[{"feed":"jma-xml","type":"x"}]}""") }
        assertThrows(Exception::class.java) { PublicIngress.config("""{"roots":[],"selected":[{"feed":"$cap","origin":"not a uri"}]}""") }
        assertThrows(Exception::class.java) { PublicIngress.config("""{"roots":[],"selected":[{"feed":"$cap","types":["x"]}]}""") }
        assertThrows(Exception::class.java) { PublicIngress.config("""{"roots":[],"selected":[{"botDid":"$bot"}]}""") }
    }

    @Test fun shippedTrustConfigParsesWithinLimits() {
        val file = java.io.File("../mesh-android/src/main/assets/mosimo-public-trust.json")
        val c = PublicIngress.config(file.readText())
        assertTrue(c.selected.size <= PublicIngress.MAX_SELECTED)
        assertTrue(PublicIngress.selects(c.selected, PublicIngress.Selection.Feed(jma, null, quake)))
        assertTrue(PublicIngress.selects(c.selected, PublicIngress.Selection.Feed(jma, null, "気象警報・注意報（Ｒ０６）（その他注意報）")))
        assertFalse(PublicIngress.selects(c.selected, PublicIngress.Selection.Feed(jma, null, "震度速報")))
    }
}
