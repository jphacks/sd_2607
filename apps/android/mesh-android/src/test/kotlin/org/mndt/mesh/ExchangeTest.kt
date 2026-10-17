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
