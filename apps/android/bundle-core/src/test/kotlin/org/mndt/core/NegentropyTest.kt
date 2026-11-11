package org.mndt.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class NegentropyTest {
    private fun item(r: Random, t: Long) = Negentropy.Item(t, r.nextBytes(32))
    private fun key(b: ByteArray) = b.toList()

    private fun run(a: List<Negentropy.Item>, b: List<Negentropy.Item>, frame: Int): Pair<Set<List<Byte>>, Set<List<Byte>>> {
        val init = Negentropy(a, frame)
        var msg: ByteArray? = init.initiate()
        var rounds = 0
        while (msg != null) {
            assertTrue("frame ${msg.size} > $frame", msg.size <= frame)
            val reply = Negentropy(b, frame).reconcile(msg)!!
            assertTrue("reply ${reply.size} > $frame", reply.size <= frame)
            msg = init.reconcile(reply)
            require(++rounds < 500) { "no convergence" }
        }
        return init.haveIds.map(::key).toSet() to init.needIds.map(::key).toSet()
    }

    @Test fun findsExactSymmetricDifferenceAtEveryFrameSize() {
        val r = Random(7)
        for ((shared, onlyA, onlyB) in listOf(Triple(0, 0, 0), Triple(0, 3, 0), Triple(10, 0, 4), Triple(300, 40, 70), Triple(2000, 1, 1))) {
            val common = List(shared) { item(r, 1_700_000_000L + r.nextLong(0, 5000)) }
            val a = List(onlyA) { item(r, 1_700_000_000L + r.nextLong(0, 5000)) }
            val b = List(onlyB) { item(r, 1_700_000_000L + r.nextLong(0, 5000)) }
            for (frame in listOf(1000, 4096, 16_360)) {
                val (have, need) = run(common + a, common + b, frame)
                assertEquals("have $shared/$onlyA/$onlyB@$frame", a.map { key(it.id) }.toSet(), have)
                assertEquals("need $shared/$onlyA/$onlyB@$frame", b.map { key(it.id) }.toSet(), need)
            }
        }
    }

    @Test fun sameTimestampsAreSplitByIdPrefix() {
        val r = Random(3)
        val common = List(500) { item(r, 42) }
        val extra = List(5) { item(r, 42) }
        val (have, need) = run(common + extra, common, 1000)
        assertEquals(extra.map { key(it.id) }.toSet(), have)
        assertTrue(need.isEmpty())
    }

    @Test fun firstMessageMatchesReferenceVectorForEmptySet() {
        // Reference implementation: version byte 0x61, infinity bound (0, 0-length prefix), IdList mode with 0 IDs.
        assertArrayEquals(byteArrayOf(0x61, 0, 0, 2, 0), Negentropy(emptyList(), 0).initiate())
    }
}
