package org.mndt.mesh

import org.junit.Assert.*
import org.junit.Test
import org.mndt.core.Author
import org.mndt.core.BlockedAuthor
import org.mndt.core.MeshEngine
import org.mndt.core.SecretKeys

private val wire = ArrayDeque<() -> Unit>()
private fun pump() { var n = 0; while (wire.isNotEmpty()) { require(++n < 1_000_000) { "livelock" }; wire.removeFirst()() } }

/**
 * In-memory link joining two exchanges. Messages queue until [pump], like a real asynchronous link, and pass through
 * a [CborSequenceReader] fed in [chunk]-byte pieces, as a GATT link would deliver them.
 */
private class Wired(val chunk: Int = 20) : Link {
    override val name = "test"
    val ends = mutableMapOf<String, Pair<Exchange, Wired>>()
    val sent = mutableListOf<Msg>()
    private val readers = mutableMapOf<String, CborSequenceReader>()
    override fun start(node: String, listener: LinkListener) = Unit
    override fun stop() = Unit
    override fun send(peer: String, message: ByteArray): Boolean {
        val (remote, link) = ends[peer] ?: return false
        sent.add(Messages.decode(message))
        val back = peer.reversed()
        val reader = link.readers.getOrPut(back) { CborSequenceReader(Messages.MAX_MESSAGE_BYTES) }
        message.toList().chunked(chunk).forEach { piece ->
            wire.addLast { reader.feed(piece.toByteArray()).forEach { remote.message(link, back, it) } }
        }
        wire.addLast { remote.drained(link, back) }
        return true
    }
    override fun disconnect(peer: String) { ends.remove(peer) }
    val rejected = mutableListOf<String>()
    override fun rejected(peer: String) { rejected += peer }
}

class ExchangeTest {
    private val now = 1_800_000_000_000L
    private var clock = 1_000L
    private fun author() = Author(SecretKeys.generate(), "https://pds.invalid")

    private class Node(val engine: MeshEngine, val exchange: Exchange)
    private fun node(messageLimit: Int = Exchange.MESSAGE_LIMIT, credit: Int = Exchange.INITIAL_CREDIT,
        accepted: (org.mndt.core.StoredBundle) -> Unit = {}, forwarded: (String, String) -> Unit = { _, _ -> }): Node {
        val engine = MeshEngine()
        return Node(engine, Exchange(engine, { now }, { clock }, accepted = accepted, forwarded = forwarded,
            messageLimit = messageLimit, initialCredit = credit))
    }
    private fun connect(a: Node, b: Node, chunk: Int = 20): Pair<Wired, Wired> {
        val la = Wired(chunk); val lb = Wired(chunk)
        la.ends["ab"] = b.exchange to lb; lb.ends["ba"] = a.exchange to la
        a.exchange.connected(la, "ab", null); b.exchange.connected(lb, "ba", null)
        pump()
        return la to lb
    }
    private fun Node.ids() = engine.entries().map { it.event.id }.toSet()

    @Test fun aPreV1StreamIsRejectedAndReportedToTheLink() {
        val a = node(); val b = node()
        val (la, _) = connect(a, b)
        // A pre-v1 build opens with a 4-byte length prefix: the first CBOR item is the integer 0, not a message array.
        a.exchange.message(la, "ab", byteArrayOf(0x00))
        pump()
        assertEquals(listOf("ab"), la.rejected)
        assertFalse("ab" in la.ends)
    }

    @Test fun storeCarryForwardReachesANodeThatNeverMetTheAuthor() {
        val a = node(); val b = node(); val c = node()
        val event = author().createPost("避難所A 開設", now).event
        a.engine.originate(event, now, clock)
        connect(a, b)
        assertEquals(1, b.engine.entries().single().hop)
        a.exchange.clear(); b.exchange.clear()           // A walks away
        clock += 60_000
        connect(b, c)                                    // B later meets C
        val carried = c.engine.entries().single()
        assertEquals(event, carried.event)
        assertEquals(2, carried.hop)
    }

    @Test fun reconciliationExchangesBothDifferencesOnly() {
        val a = node(); val b = node()
        val shared = (0 until 5).map { author().createPost("shared $it", now - it * 1000).event }
        val onlyA = (0 until 7).map { author().createPost("A $it", now - it * 1000).event }
        val onlyB = (0 until 9).map { author().createPost("B $it", now - it * 1000).event }
        (shared + onlyA).forEach { a.engine.originate(it, now, clock) }
        (shared + onlyB).forEach { b.engine.originate(it, now, clock) }
        val (la, lb) = connect(a, b)
        val all = (shared + onlyA + onlyB).map { it.id }.toSet()
        assertEquals(all, a.ids()); assertEquals(all, b.ids())
        // Nothing both sides already held was transferred.
        val begun = (la.sent + lb.sent).filterIsInstance<Msg.Begin>().map { hexOf(it.target) }.toSet()
        assertEquals((onlyA + onlyB).map { it.id }.toSet(), begun)
        assertTrue("no full listing", (la.sent + lb.sent).filterIsInstance<Msg.Inventory>().sumOf { it.ids.size } <= onlyA.size + onlyB.size)
    }

    @Test fun smallDeviceLimitsAreRespectedAndManyBundlesStillArrive() {
        // A 1,024-byte message limit and zero initial credit, as the smallest conforming peer may announce.
        val a = node(); val small = node(messageLimit = 1024, credit = 0)
        val events = (0 until 60).map { author().createPost("長い本文 $it " + "あ".repeat(600), now - it * 1000L).event }
        events.forEach { a.engine.originate(it, now, clock) }
        val (la, _) = connect(a, small, chunk = 7)
        assertEquals(events.map { it.id }.toSet(), small.ids())
        assertTrue(la.sent.all { Messages.encode(it).size <= 1024 })
        // Zero initial credit: every first fragment waited for CREDIT, so no BEGIN carried data.
        assertTrue(la.sent.filterIsInstance<Msg.Begin>().all { it.fragment == null })
    }

    @Test fun smallBundleTravelsInsideBeginAlone() {
        val a = node(); val b = node()
        a.engine.originate(author().createPost("短い", now).event, now, clock)
        val (la, _) = connect(a, b)
        val begin = la.sent.filterIsInstance<Msg.Begin>().single()
        assertEquals(begin.length, begin.fragment!!.size)
        assertTrue(la.sent.none { it is Msg.Data })
    }

    @Test fun blockedAuthorIsRefusedWithPolicyReasonAndNotRewanted() {
        val blocked = author(); val other = author()
        val a = node(); val b = node()
        b.engine.admit = { it.pubkey != blocked.nostrPubkey }
        val bad = blocked.createPost("blocked", now).event
        val good = other.createPost("good", now).event
        a.engine.originate(bad, now, clock); a.engine.originate(good, now, clock)
        val (_, lb) = connect(a, b)
        assertEquals(setOf(good.id), b.ids())
        val refusal = lb.sent.filterIsInstance<Msg.Result>().single { it.code == Messages.RESULT_REJECTED }
        assertEquals(Messages.REASON_POLICY, refusal.reason)
        a.exchange.clear(); b.exchange.clear()
        val (_, again) = connect(a, b)
        assertTrue("refused ID is not wanted again", again.sent.filterIsInstance<Msg.Want>().none { w -> w.ids.any { hexOf(it) == bad.id } })
        assertThrows(BlockedAuthor::class.java) { b.engine.originate(bad, now, clock) }
    }

    @Test fun storedResultOnlyAfterDurableSave() {
        val order = mutableListOf<String>()
        val a = node(forwarded = { id, _ -> order += "forwarded:$id" })
        val b = node(accepted = { order += "saved:${it.event.id}" })
        val event = author().createPost("x", now).event
        a.engine.originate(event, now, clock)
        connect(a, b)
        assertEquals(listOf("saved:${event.id}", "forwarded:${event.id}"), order)
    }

    @Test fun failedDurableSaveSendsNoStoredResult() {
        val a = node(); val b = node(accepted = { error("disk full") })
        a.engine.originate(author().createPost("x", now).event, now, clock)
        val (_, lb) = connect(a, b)
        assertTrue(lb.sent.filterIsInstance<Msg.Result>().none { it.code == Messages.RESULT_STORED })
    }

    @Test fun silentPeerIsDroppedAfterHelloTimeout() {
        val a = node()
        val link = Wired()
        link.ends["x"] = node().exchange to Wired() // the peer never answers: messages are queued but never pumped
        a.exchange.connected(link, "x", null)
        wire.clear()
        assertEquals(1, a.exchange.sessions.size)
        clock += Exchange.HELLO_TIMEOUT_MS + 1
        a.exchange.dropStale()
        assertEquals(0, a.exchange.sessions.size)
    }

    @Test fun unknownMessageTypesAreIgnored() {
        val a = node(); val b = node()
        val (la, _) = connect(a, b)
        b.exchange.message(Wired(), "ba", org.mndt.core.CanonicalCbor.encode(listOf(99L, "future")))
        b.exchange.message(la.ends["ab"]!!.second, "ba", org.mndt.core.CanonicalCbor.encode(listOf(99L, "future")))
        pump()
        assertEquals(1, b.exchange.sessions.size)
    }

    private fun hexOf(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 255) }
}
