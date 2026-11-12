package org.mndt.mesh

import org.junit.Assert.*
import org.junit.Test
import org.mndt.core.CanonicalCbor
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LanLinkTest {
    @Test fun onlyTheSmallerNodeDials() {
        assertTrue(shouldDial("00000000000000aa", "00000000000000ab"))
        assertFalse(shouldDial("00000000000000ab", "00000000000000aa"))
        assertFalse(shouldDial("00000000000000aa", "00000000000000aa"))
    }

    @Test fun tcpConnMovesFramesBothWaysAndClosesOnce() {
        val server = ServerSocket(0)
        val client = Socket("127.0.0.1", server.localPort)
        val accepted = server.accept()
        val gotA = LinkedBlockingQueue<ByteArray>(); val gotB = LinkedBlockingQueue<ByteArray>()
        val closesA = AtomicInteger(); val closesB = AtomicInteger(); val bClosed = CountDownLatch(1)
        val a = TcpConn(client, { gotA.add(it) }, onClose = { closesA.incrementAndGet() })
        val b = TcpConn(accepted, { gotB.add(it) }, onClose = { closesB.incrementAndGet(); bClosed.countDown() })
        a.start(); b.start()

        val small = CanonicalCbor.encode(listOf(1L, listOf<Any>()))
        val big = CanonicalCbor.encode(listOf(4L, 0L, ByteArray(Exchange.MESSAGE_LIMIT - 16) { it.toByte() }))
        assertTrue(a.offer(small)); assertTrue(a.offer(big))
        assertTrue(b.offer(small))
        assertArrayEquals(small, gotB.poll(5, TimeUnit.SECONDS))
        assertArrayEquals(big, gotB.poll(5, TimeUnit.SECONDS))
        assertArrayEquals(small, gotA.poll(5, TimeUnit.SECONDS))
        assertFalse(a.offer(ByteArray(0))); assertFalse(a.offer(ByteArray(Messages.MAX_MESSAGE_BYTES + 1)))

        a.close(); a.close()
        assertTrue(bClosed.await(5, TimeUnit.SECONDS)) // peer sees EOF and closes itself
        Thread.sleep(200)
        assertEquals(1, closesA.get()); assertEquals(1, closesB.get())
        assertFalse(a.offer(byteArrayOf(1)))
        server.close()
    }

    @Test fun oversizedCborItemClosesTheConnection() {
        val server = ServerSocket(0)
        val client = Socket("127.0.0.1", server.localPort)
        val closed = CountDownLatch(1)
        TcpConn(server.accept(), { fail("no frame expected") }, onClose = { closed.countDown() }).start()
        client.getOutputStream().apply { write(byteArrayOf(0x5a, 0x7f, 0, 0, 0)); flush() }
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        client.close(); server.close()
    }
}
