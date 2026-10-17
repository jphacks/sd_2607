package org.mndt.core

import io.nodle.dtn.bpv7.*
import io.nodle.dtn.bpv7.extensions.*
import org.junit.Assert.*
import org.junit.Test

class MeshEngineTest {
    private val now = 1_800_000_000_000L
    private val author by lazy { Author(SecretKeys.generate(), "https://pds.invalid") }
    private fun post(text: String = "避難所A 開設済み") = author.createPost(text, now).event

    @Test fun forwardingRemovesReceivedPreviousNodeWithoutChangingStoredBundle() {
        val source = MeshEngine().originate(post(), now, 0)
        val wire = cborUnmarshalBundle(source.bytes)
            .addBlock(previousNodeBlock(java.net.URI("dtn://old-peer/")).crcType(CRCType.CRC32)).cborMarshal()
        val relay = MeshEngine()
        val received = relay.accept(wire, now, 0)!!
        assertNotNull(cborUnmarshalBundle(received.bytes).getPreviousNodeBlockData())
        assertNull(cborUnmarshalBundle(relay.forward(source.event.id, now, 0)!!).getPreviousNodeBlockData())
        assertNotNull(cborUnmarshalBundle(relay.entries().single().bytes).getPreviousNodeBlockData())
    }

    @Test fun acceptedCompactEventCanExceedNearbyLimitInJson() {
        val seed = post().copy(content = "\u0001".repeat(16_000), tags = post().tags)
        val digest = sha256(seed.signingBytes())
        val event = seed.copy(id = hex(digest), sig = hex(secp.signSchnorr(digest, author.keys.nostr, ByteArray(32))))
        val stored = MeshEngine().originate(event, now, 0)
        assertTrue(stored.bytes.size < MeshPolicy.MAX_BUNDLE_BYTES)
        assertTrue(event.toJson().toByteArray(Charsets.UTF_8).size > 32768)
    }

    @Test fun storeCarryForwardAcrossThreeNodesPreservesAuthorAndLimitsHops() {
        val a = MeshEngine()
        val b = MeshEngine()
        val c = MeshEngine()
        val event = post()
        a.originate(event, now, 100)
        val ab = a.forward(event.id, now + 1000, 1100)!!
        assertArrayEquals(ab, a.forward(event.id, now + 1000, 1100))
        assertEquals(1, b.accept(ab, now + 1000, 1100)!!.hop)
        assertNull(b.accept(ab, now + 1000, 1100))
        val received = c.accept(b.forward(event.id, now + 3000, 3100)!!, now + 3000, 3100)!!
        assertEquals(2, received.hop)
        assertEquals(event.toJson(), received.event.toJson())
        assertTrue(received.event.verify())
        var engine = c
        for (hop in 3..8) {
            val next = MeshEngine()
            assertEquals(hop, next.accept(engine.forward(event.id, now + 3000, 3100)!!, now + 3000, 3100)!!.hop)
            engine = next
        }
        assertNull(engine.forward(event.id, now + 3000, 3100))
        assertTrue(engine.inventory(now + 3000, 3100).isEmpty())
    }

    @Test fun restartConsumesResidenceTimeAndExpiredBacklogNeverReentersMesh() {
        val a = MeshEngine()
        val entry = a.originate(post(), now, 100)
        val b = MeshEngine(ClockConfidence.TRUSTED)
        b.restore(entry.bytes, now, now + 30_000, 10)
        assertEquals(30_000, cborUnmarshalBundle(b.forward(entry.event.id, now + 30_000, 10)!!).getAgeBlockData()!!.age)
        val expired = MeshEngine()
        assertNotNull(expired.restore(entry.bytes, now, now + 86_400_001, 10))
        assertNull(expired.forward(entry.event.id, now + 86_400_001, 10))
        assertEquals(1, expired.entries().size)
        assertNull(a.forward(entry.event.id, now - 1000, 86_400_101))
    }

    @Test fun rejectsTamperingOversizeTrailingDataAndUnsignedDeliveryChanges() {
        val engine = MeshEngine()
        val entry = engine.originate(post(), now, 0)
        assertThrows(Exception::class.java) { MeshEngine().originate(entry.event.copy(content = "tampered"), now, 0) }
        assertThrows(Exception::class.java) { MeshEngine().accept(ByteArray(24_001), now, 0) }
        assertThrows(Exception::class.java) { MeshEngine().accept(entry.bytes + byteArrayOf(0), now, 0) }
        assertThrows(Exception::class.java) { MeshEngine().accept(byteArrayOf(0x5b, 0x7f, -1, -1, -1, -1, -1, -1, -1), now, 0) }
        val changed = cborUnmarshalBundle(entry.bytes)
        changed.primaryBlock.destination = java.net.URI("dtn://mosimo.oriom.dev/~other")
        assertThrows(Exception::class.java) { MeshEngine().accept(changed.cborMarshal(), now, 0) }
        val crcTamper = entry.bytes.copyOf().also { it[it.lastIndex - 2] = (it[it.lastIndex - 2].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { MeshEngine().accept(crcTamper, now, 0) }
    }

    @Test fun opaqueBlocksFollowRfcFlags() {
        val a = MeshEngine()
        val entry = a.originate(post(), now, 0)
        fun extended(flags: Long) = cborUnmarshalBundle(entry.bytes)
            .addBlock(CanonicalBlock(blockType = 200, procV7flags = flags, crcType = CRCType.CRC32, data = BlobBlockData(byteArrayOf(-1, 0, 1))))
            .cborMarshal()
        val b = MeshEngine()
        b.accept(extended(0), now, 0)
        val forwarded = cborUnmarshalBundle(b.forward(entry.event.id, now, 0)!!)
        assertArrayEquals(byteArrayOf(-1, 0, 1), (forwarded.getBlockType(200)!!.data as BlobBlockData).buffer)
        val discard = MeshEngine().accept(extended(0x10), now, 0)!!
        assertNull(cborUnmarshalBundle(discard.bytes).getBlockType(200))
        assertThrows(Exception::class.java) { MeshEngine().accept(extended(0x14), now, 0) }
    }

    @Test fun enforcesPerAuthorRate() {
        val a = MeshEngine()
        repeat(12) { index -> a.originate(post("message $index"), now, 0) }
        assertThrows(Exception::class.java) { a.originate(post("rate limited"), now, 0) }
    }
}
