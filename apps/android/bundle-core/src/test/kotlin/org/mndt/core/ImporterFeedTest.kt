package org.mndt.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** importer §1 `feeds`, §1.1 the `jma-xml` pair of a post, and §3 authorization of feed re-posts. */
class ImporterFeedTest {
    private class Key { val sk = SecretKeys.generate().nostr; val pub: ByteArray = secp.pubkeyCreate(sk).copyOfRange(1, 33) }
    private fun sign(body: Map<String, Any?>, k: Key): ByteArray = secp.signSchnorr(sha256(CanonicalCbor.encode(body)), k.sk, ByteArray(32))
    private val now = Instant.parse("2026-11-11T00:00:00Z")
    private val later = now.plusSeconds(60)

    private val quake = "震源・震度に関する情報"
    private val rain = "気象警報・注意報（Ｒ０６）（大雨）"
    private val bot = "did:plc:vwi7zhfyamyvrg5v6ycjgzxi"

    private fun feed(name: String, vararg types: String) = mapOf("feed" to name, "types" to types.toList())
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
    private val caBlock = importer(sources = listOf(source(bot, "earthquake")), feeds = listOf(feed("jma-xml", *both)))
    private fun chain(leafBlock: Map<String, Any?>? = importer(feeds = listOf(feed("jma-xml", quake))), parentBlock: Map<String, Any?>? = caBlock) =
        listOf(attest(root, ca.pub, listOf("attest", "import"), depth = 2, importer = parentBlock), attest(ca, leafKey, listOf("import"), importer = leafBlock))

    private fun message(title: String) = mapOf(
        "control" to mapOf("title" to title, "dateTime" to "2026-11-11T08:59:00+09:00", "status" to "通常", "editorialOffice" to "気象庁本庁", "publishingOffice" to "気象庁"),
        "head" to mapOf("title" to title, "reportDateTime" to "2026-11-11T08:59:00+09:00", "eventID" to "20261111085500", "infoType" to "発表",
            "serial" to "1", "infoKind" to "地震情報", "infoKindVersion" to "1.0_1"))
    private fun earthquake(title: String = quake) = mapOf("\$type" to PublicIngress.JMA_EARTHQUAKE, "message" to message(title), "selection" to mapOf("kind" to "whole"), "body" to mapOf<String, Any?>())
    private fun weather(title: String = rain) = mapOf("\$type" to PublicIngress.JMA_WEATHER, "message" to message(title), "selection" to mapOf("kind" to "whole"),
        "headlineItems" to listOf<Any>(), "warnings" to listOf<Any>(), "meteorologicalItems" to listOf<Any>())
    private fun post(c: List<Map<String, Any?>> = chain(), ext: List<Map<String, Any?>> = listOf(earthquake())) =
        author.createPost("気象庁の地震情報", now.toEpochMilli(), extensions = listOf(mapOf("\$type" to Attest.CHAIN, "attestations" to c)) + ext).event

    private val selected = setOf<PublicIngress.Selection>(PublicIngress.Selection.Feed("jma-xml", quake), PublicIngress.Selection.Feed("jma-xml", rain))
    private val config = PublicIngress.Config(roots, selected)
    private fun authorized(e: NostrEvent, sel: Set<PublicIngress.Selection> = selected) =
        PublicIngress.chainView(e, roots, PublicIngress.RevocationStore(), later, later, sel)!!.importAuthorized

    @Test fun feedsParseAndValidate() {
        val leaf = Attest.attestation(chain()[1])
        assertEquals(mapOf("jma-xml" to setOf(quake)), leaf.importerFeeds)
        assertEquals(emptyMap<String, Set<String>>(), leaf.importerSources)
        val parent = Attest.attestation(chain()[0])
        assertEquals(setOf(bot), parent.importerSources!!.keys)
        assertNull(Attest.attestation(attest(ca, leafKey, listOf("import"))).importerFeeds)
        fun rejected(block: Map<String, Any?>) = assertThrows(Exception::class.java) { Attest.attestation(attest(ca, leafKey, listOf("import"), importer = block)) }
        rejected(importer())                                                                    // neither sources nor feeds
        rejected(importer(feeds = emptyList()))                                                 // present but empty
        rejected(importer(feeds = listOf(feed("jma-xml"))))                                     // no types
        rejected(importer(feeds = listOf(feed("jma-xml", quake, quake))))                       // duplicate type
        rejected(importer(feeds = listOf(feed("jma-xml", *both.reversedArray()))))             // unsorted types
        rejected(importer(feeds = listOf(feed("b", "x"), feed("a", "x"))))                      // unsorted feeds
        rejected(importer(feeds = listOf(feed("a", "x"), feed("a", "y"))))                      // duplicate feed
        rejected(importer(feeds = (0 until 9).map { feed("f$it", "x") }))                       // more than 8 feeds
        rejected(importer(feeds = listOf(feed("a", *Array(33) { "t%02d".format(it) }))))       // more than 32 types
        // sources + feeds together count against the 64-pair limit.
        val many = listOf(feed("a", *Array(32) { "t%02d".format(it) }), feed("b", *Array(32) { "t%02d".format(it) }))
        assertEquals(2, Attest.attestation(attest(ca, leafKey, listOf("import"), importer = importer(feeds = many))).importerFeeds!!.size)
        rejected(importer(sources = listOf(source(bot, "earthquake")), feeds = many))
    }

    @Test fun feedsMustLieWithinTheIssuers() {
        fun state(c: List<Map<String, Any?>>) = Attest.verifyChain(c.map(Attest::attestation), roots, now, now).state
        assertEquals(Attest.ChainState.VERIFIED, state(chain()))
        assertEquals(Attest.ChainState.INVALID, state(chain(leafBlock = importer(feeds = listOf(feed("jma-xml", "震度速報"))))))
        assertEquals(Attest.ChainState.INVALID, state(chain(leafBlock = importer(feeds = listOf(feed("other", quake))))))
        // The issuer's block has no feeds: the child may not have any (likewise sources).
        assertEquals(Attest.ChainState.INVALID, state(chain(parentBlock = importer(sources = listOf(source(bot, "earthquake"))))))
        assertEquals(Attest.ChainState.INVALID, state(chain(leafBlock = importer(sources = listOf(source(bot, "earthquake"))),
            parentBlock = importer(feeds = listOf(feed("jma-xml", quake))))))
        // An issuer without the block leaves the child unconstrained.
        assertEquals(Attest.ChainState.VERIFIED, state(chain(parentBlock = null)))
    }

    @Test fun postPairComesFromItsExtensions() {
        assertEquals(PublicIngress.Selection.Feed("jma-xml", quake), PublicIngress.importSelection(post()))
        assertEquals(PublicIngress.Selection.Feed("jma-xml", rain), PublicIngress.importSelection(post(ext = listOf(weather()))))
        // Both kinds at once, or none: not a jma-xml re-post.
        assertThrows(Exception::class.java) { PublicIngress.importSelection(post(ext = listOf(earthquake(), weather()))) }
        assertThrows(Exception::class.java) { PublicIngress.importSelection(post(ext = listOf())) }
        // With public.bsaf the post is a Bot re-post and must carry `original`.
        assertThrows(Exception::class.java) { PublicIngress.importSelection(post(ext = listOf(earthquake(),
            mapOf("\$type" to PublicIngress.BSAF, "tags" to listOf("bsaf:v1", "type:earthquake"), "langs" to listOf("ja"))))) }
    }

    @Test fun feedRePostIsAuthorizedOnlyWhenCoveredAndSelected() {
        val e = post()
        assertTrue(authorized(e))
        val receipt = PublicIngress.verify(e, config, PublicIngress.RevocationStore(), later).receipt!!
        assertEquals(PublicIngress.Selection.Feed("jma-xml", quake), receipt.source)
        // Not selected locally.
        assertFalse(authorized(e, setOf(PublicIngress.Selection.Feed("jma-xml", rain))))
        assertNull(PublicIngress.verify(e, PublicIngress.Config(roots, emptySet()), PublicIngress.RevocationStore(), later).receipt)
        // Selected, but the leaf's block does not list the title.
        val weatherPost = post(ext = listOf(weather()))
        assertFalse(authorized(weatherPost))
        assertNull(PublicIngress.verify(weatherPost, config, PublicIngress.RevocationStore(), later).receipt)
        // A Bot-only leaf does not cover a feed pair even with the same type name.
        assertFalse(authorized(post(chain(leafBlock = importer(sources = listOf(source(bot, "earthquake")))))))
        // Ambiguous posts are never authorized.
        assertFalse(authorized(post(ext = listOf(earthquake(), weather()))))
    }

    @Test fun configAcceptsBotAndFeedSelections() {
        val c = PublicIngress.config("""{"roots":[],"selected":[{"botDid":"$bot","type":"earthquake"},{"feed":"jma-xml","type":"$quake"}]}""")
        assertEquals(setOf(PublicIngress.Selection.Bot(bot, "earthquake"), PublicIngress.Selection.Feed("jma-xml", quake)), c.selected)
        assertThrows(Exception::class.java) { PublicIngress.config("""{"roots":[],"selected":[{"feed":"jma-xml","botDid":"$bot","type":"x"}]}""") }
        assertThrows(Exception::class.java) { PublicIngress.config("""{"roots":[],"selected":[{"feed":"","type":"x"}]}""") }
        assertThrows(Exception::class.java) { PublicIngress.config("""{"roots":[],"selected":[{"feed":"jma-xml"}]}""") }
    }

    @Test fun shippedTrustConfigParsesWithinLimits() {
        val file = java.io.File("../mesh-android/src/main/assets/mosimo-public-trust.json")
        val c = PublicIngress.config(file.readText())
        assertTrue(c.selected.size <= PublicIngress.MAX_SELECTED)
        assertTrue(PublicIngress.Selection.Feed("jma-xml", quake) in c.selected)
        assertTrue(PublicIngress.Selection.Feed("jma-xml", "気象警報・注意報（Ｒ０６）（その他注意報）") in c.selected)
    }
}
