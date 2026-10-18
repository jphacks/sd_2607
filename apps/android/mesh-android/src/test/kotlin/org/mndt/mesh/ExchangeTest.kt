package org.mndt.mesh

import org.junit.Assert.*
import org.junit.Test
import org.mndt.core.Author
import org.mndt.core.MeshEngine
import org.mndt.core.SecretKeys

private val wire = ArrayDeque<() -> Unit>()
private fun pump() { while (wire.isNotEmpty()) wire.removeFirst()() }

/** In-memory link joining two exchanges; frames queue until [pump], like a real asynchronous link. */
private class Wired : Link {
    override val name = "test"
    val ends = mutableMapOf<String, Pair<Exchange, Wired>>()
    override fun start(node: String, listener: LinkListener) = Unit
    override fun stop() = Unit
    override fun send(peer: String, frame: ByteArray): Boolean {
        val (remote, link) = ends[peer] ?: return false
        wire.addLast { remote.frame(link, peer.reversed(), frame) }; return true
    }
    override fun disconnect(peer: String) { ends.remove(peer) }
}

class ExchangeTest {
    private val now = 1_800_000_000_000L
    private var clock = 1_000L
    private fun node(id: Char) = MeshEngine().let { it to Exchange(it, id.toString().repeat(16), { now }, { clock }) }
    private fun connect(a: Exchange, b: Exchange) {
        val la = Wired(); val lb = Wired()
        la.ends["ab"] = b to lb; lb.ends["ba"] = a to la
        a.connected(la, "ab"); b.connected(lb, "ba")
        pump()
    }

    @Test fun storeCarryForwardReachesANodeThatNeverMetTheAuthor() {
        val (ea, a) = node('a'); val (eb, b) = node('b'); val (ec, c) = node('c')
        val event = Author(SecretKeys.generate(), "https://pds.invalid").createPost("避難所A 開設", now).event
        ea.originate(event, now, clock)
        connect(a, b)
        assertEquals(1, eb.entries().single().hop)
        a.clear(); b.clear()           // A walks away
        clock += 60_000
        connect(b, c)                  // B later meets C
        val carried = ec.entries().single()
        assertEquals(event, carried.event)
        assertEquals(2, carried.hop)
        assertTrue(c.sessions.values.all { it.peerNode == "b".repeat(16) })
    }

    @Test fun durableSaveFinishesBeforeAckOrInventoryLeavesReceiver() {
        val engine = MeshEngine()
        val link = RecordingLink()
        var saved = false
        val receiver = Exchange(engine, "b".repeat(16), { now }, { clock }, accepted = { entry ->
            assertTrue("no ACK or inventory before durable save", link.frames.isEmpty())
            assertEquals(entry.event.id, engine.entries().single().event.id)
            saved = true
        })
        receiver.connected(link, "peer")
        receiver.frame(link, "peer", Wire.encode(Control("hello", node = "a".repeat(16), max = 24_000)))
        link.frames.clear()
        val source = MeshEngine()
        val event = Author(SecretKeys.generate(), "https://pds.invalid").createPost("保存してから応答", now).event
        source.originate(event, now, clock)
        receiver.frame(link, "peer", source.forward(event.id, now, clock)!!)
        assertTrue(saved)
        assertEquals(listOf("ack", "inventory"), link.frames.map { Wire.decode(it).type })
        assertEquals(listOf(event.id), Wire.decode(link.frames.first()).ids)
    }

    @Test fun failedDurableSaveDoesNotAckOrAdvertiseReceivedBundle() {
        val link = RecordingLink()
        val receiver = Exchange(MeshEngine(), "b".repeat(16), { now }, { clock }, accepted = {
            throw java.io.IOException("disk full")
        })
        receiver.connected(link, "peer")
        receiver.frame(link, "peer", Wire.encode(Control("hello", node = "a".repeat(16), max = 24_000)))
        link.frames.clear()
        val source = MeshEngine()
        val event = Author(SecretKeys.generate(), "https://pds.invalid").createPost("未保存", now).event
        source.originate(event, now, clock)
        receiver.frame(link, "peer", source.forward(event.id, now, clock)!!)
        assertTrue("a failed save must not claim custody", link.frames.isEmpty())
    }

    @Test fun optionalTestPeerIsolationRejectsOtherNodesBeforeInventory() {
        val expected = "a7fb2785f1068ab5"
        for (peer in listOf(expected, "d".repeat(16))) {
            val link = RecordingLink()
            val receiver = Exchange(MeshEngine(), "b".repeat(16), { now }, { clock }, allowedPeerNode = expected)
            receiver.connected(link, "peer")
            link.frames.clear()
            receiver.frame(link, "peer", Wire.encode(Control("hello", node = peer, max = 24_000)))
            if (peer == expected) {
                assertEquals("inventory", Wire.decode(link.frames.single()).type)
                assertFalse(link.disconnected)
            } else {
                assertTrue(link.frames.isEmpty())
                assertTrue(link.disconnected)
                assertTrue(receiver.sessions.isEmpty())
            }
        }
    }

    @Test fun optionalTestAuthorIsolationDoesNotAdvertiseOrSendUnrelatedStoredPosts() {
        val engine = MeshEngine()
        val wanted = Author(SecretKeys.generate(), "https://pds.invalid").createPost("TEST ONLY - NOT DISASTER INFORMATION", now).event
        val unrelated = Author(SecretKeys.generate(), "https://pds.invalid").createPost("other stored data", now).event
        engine.originate(wanted, now, clock)
        engine.originate(unrelated, now, clock)
        val link = RecordingLink()
        val receiver = Exchange(engine, "b".repeat(16), { now }, { clock }, allowedAuthor = wanted.pubkey)
        receiver.connected(link, "peer")
        link.frames.clear()
        receiver.frame(link, "peer", Wire.encode(Control("hello", node = "a".repeat(16), max = 24_000)))
        assertEquals(listOf(wanted.id), Wire.decode(link.frames.single()).ids)
        link.frames.clear()
        receiver.frame(link, "peer", Wire.encode(Control("want", listOf(unrelated.id))))
        assertTrue(link.frames.isEmpty())
        assertEquals("existing data must remain stored", 2, engine.entries().size)
    }

    private class RecordingLink : Link {
        override val name = "recording"
        val frames = mutableListOf<ByteArray>()
        override fun start(node: String, listener: LinkListener) = Unit
        override fun stop() = Unit
        var disconnected = false
        override fun disconnect(peer: String) { disconnected = true }
        override fun send(peer: String, frame: ByteArray): Boolean { frames.add(frame); return true }
    }

    @Test fun silentPeerIsDroppedAndOwnNodeIsRefused() {
        val (_, a) = node('a')
        val silent = Wired()
        a.connected(silent, "x")
        pump()
        clock += 61_000
        a.dropStale()
        assertTrue(a.sessions.isEmpty())
        val (_, twin) = node('a')
        connect(a, twin)
        assertTrue("a node must not sync with itself", a.sessions.isEmpty() && twin.sessions.isEmpty())
    }
}
