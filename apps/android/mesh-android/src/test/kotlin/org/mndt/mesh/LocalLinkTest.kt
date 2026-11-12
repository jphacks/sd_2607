package org.mndt.mesh

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class LocalLinkTest {
    private class FakeRadio : Link {
        override val name = "radio"
        @Volatile var on = false
        override fun start(node: String, listener: LinkListener) { on = true }
        override fun stop() { on = false }
        override fun send(peer: String, frame: ByteArray) = false
        override fun disconnect(peer: String) {}
    }

    /** Stands in for Exchange: says its node on connect and reports the peer's as HELLO. */
    private class Node(private val id: String) : LinkListener {
        val hellos = LinkedBlockingQueue<String>()
        override fun onConnected(link: Link, peer: String) { link.send(peer, id.toByteArray()) }
        override fun onFrame(link: Link, peer: String, frame: ByteArray) { hellos.add(String(frame)); link.identified(peer, String(frame)) }
        override fun onDisconnected(link: Link, peer: String) {}
    }

    private fun freePort() = ServerSocket(0, 50, LocalLink.LOOPBACK).use { it.localPort }

    private fun waitFor(ms: Long, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!ok() && System.currentTimeMillis() < until) Thread.sleep(50)
        assertTrue(ok())
    }

    @Test fun onePhoneRunsOneSetOfRadiosAndAFollowerTakesOver() {
        val ports = listOf(freePort())
        val bleA = FakeRadio(); val lanA = FakeRadio(); val bleB = FakeRadio(); val lanB = FakeRadio()
        val a = LocalLink(listOf(bleA, lanA), ports); val b = LocalLink(listOf(bleB, lanB), ports)
        a.start("00000000000000aa", Node("00000000000000aa"))
        waitFor(5_000) { bleA.on && lanA.on }
        val nodeB = Node("00000000000000bb")
        b.start("00000000000000bb", nodeB)
        assertEquals("00000000000000aa", nodeB.hellos.poll(5, TimeUnit.SECONDS))
        Thread.sleep(500)
        assertFalse(bleB.on || lanB.on)

        a.stop()
        assertFalse(bleA.on || lanA.on)
        waitFor(10_000) { bleB.on && lanB.on } // b binds the freed port
        b.stop()
        assertFalse(bleB.on || lanB.on)
    }

    @Test fun aPortUsedByAnotherAppFallsBackToTheNext() {
        val foreign = ServerSocket(0, 50, LocalLink.LOOPBACK)
        thread(isDaemon = true) { while (!foreign.isClosed) runCatching { foreign.accept().close() } }
        val ports = listOf(foreign.localPort, freePort())
        val radioA = FakeRadio(); val radioB = FakeRadio(); val radioC = FakeRadio()
        val a = LocalLink(listOf(radioA), ports)
        a.start("00000000000000aa", Node("00000000000000aa"))
        waitFor(5_000) { radioA.on } // skipped the foreign port, holds the fallback

        val nodeB = Node("00000000000000bb")
        val b = LocalLink(listOf(radioB), ports)
        b.start("00000000000000bb", nodeB)
        assertEquals("00000000000000aa", nodeB.hellos.poll(5, TimeUnit.SECONDS))
        Thread.sleep(500)
        assertFalse(radioB.on) // found a on the fallback

        val c = LocalLink(listOf(radioC), listOf(foreign.localPort))
        c.start("00000000000000cc", Node("00000000000000cc"))
        waitFor(5_000) { radioC.on } // every port foreign: never leave the phone without radios
        c.stop()

        // The other app frees the first port before a's recheck: a newcomer joins a instead of holding it beside a.
        foreign.close()
        val radioD = FakeRadio(); val nodeD = Node("00000000000000dd")
        val d = LocalLink(listOf(radioD), ports)
        d.start("00000000000000dd", nodeD)
        assertEquals("00000000000000aa", nodeD.hellos.poll(5, TimeUnit.SECONDS))
        Thread.sleep(500)
        assertFalse(radioD.on)
        a.stop(); b.stop(); d.stop()
    }
}
