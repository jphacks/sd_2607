package org.mndt.core

import io.nodle.dtn.bpv7.cborUnmarshalBundle
import io.nodle.dtn.bpv7.cborMarshal
import io.nodle.dtn.bpv7.dtnTimeFromUnixMsec
import io.nodle.dtn.bpv7.BlobBlockData
import io.nodle.dtn.bpv7.CanonicalBlock
import io.nodle.dtn.bpv7.CRCType
import io.nodle.dtn.bpv7.addBlock
import io.nodle.dtn.bpv7.extensions.getAgeBlockData
import org.junit.Assert.*
import org.junit.Test

class ClockPolicyTest {
    private val now = 1_800_000_000_000L
    private val author by lazy { Author(SecretKeys.generate(), "https://pds.invalid") }
    private fun source() = MeshEngine().originate(author.createPost("本文", now).event, now, 0)

    @Test fun unknownTimeDoesNotRejectFreshZeroAgeBundleWithAheadWallClock() {
        val original = source()
        val relay = MeshEngine()
        assertNotNull(relay.accept(original.bytes, now + 2 * 86_400_000L, 0))
        assertNotNull(relay.forward(original.event.id, now + 2 * 86_400_000L, 1000))
    }

    @Test fun unknownRestartWithoutElapsedEvidenceCannotRejuvenateBundle() {
        val original = source()
        val relay = MeshEngine()
        assertNotNull(relay.restore(original.bytes, now, now - 86_400_000L, 0))
        assertNull(relay.forward(original.event.id, now - 86_400_000L, 0))
        assertEquals(original.event, relay.entries().single().event)
    }

    @Test fun trustedClockEnforcesSignedAbsoluteDeadlineButKeepsBacklog() {
        val original = source()
        val relay = MeshEngine(ClockConfidence.TRUSTED)
        assertEquals(DeliveryDeadline.WITHIN_DEADLINE, relay.deadline(original.event, now))
        assertNotNull(relay.accept(original.bytes, now, 0))
        assertEquals(DeliveryDeadline.EXPIRED, relay.deadline(original.event, now + 86_400_000))
        assertNull(relay.forward(original.event.id, now + 86_400_000, 1))
        assertEquals(1, relay.entries().size)
        assertThrows(IllegalArgumentException::class.java) {
            MeshEngine(ClockConfidence.TRUSTED).accept(original.bytes, now + 86_400_000, 0)
        }
    }

    @Test fun unknownAheadAndBackwardWallsCannotOverrideMonotonicLifetime() {
        val original = source()
        for (wall in listOf(now + 172_800_000, now - 172_800_000)) {
            val relay = MeshEngine()
            relay.accept(original.bytes, wall, 100)
            assertEquals(DeliveryDeadline.UNKNOWN, relay.deadline(original.event, wall))
            assertEquals(2000, cborUnmarshalBundle(relay.forward(original.event.id, wall, 2100)!!).getAgeBlockData()!!.age)
            assertNull(relay.forward(original.event.id, wall, 86_400_100))
            assertEquals(original.event, relay.entries().single().event)
        }
    }

    @Test fun checkpointsAndSameBootDowntimeAccumulateAcrossRestartsWithoutChangingOriginal() {
        val original = source()
        val first = MeshEngine()
        val entry = first.accept(original.bytes, now, 100)!!
        val saved = first.checkpoint(entry, now - 100_000, 2100)
        assertArrayEquals(original.bytes, entry.bytes)
        assertEquals(2000, cborUnmarshalBundle(saved.bytes).getAgeBlockData()!!.age)
        val second = MeshEngine()
        val loaded = second.restore(saved.bytes, saved.receivedAtMillis, now + 172_800_000, 5100, downtimeMillis = 3000)!!
        val forwarded = cborUnmarshalBundle(second.forward(original.event.id, now - 172_800_000, 6100)!!)
        assertEquals(6000, forwarded.getAgeBlockData()!!.age)
        val third = MeshEngine()
        val again = second.checkpoint(loaded, now, 7100)
        third.restore(again.bytes, now, now, 8100, downtimeMillis = 1000)
        assertEquals(8000, cborUnmarshalBundle(third.forward(original.event.id, now, 8100)!!).getAgeBlockData()!!.age)
        assertNull(third.forward(original.event.id, now, 86_401_000))
        assertEquals(original.event, third.entries().single().event)
    }

    @Test fun missingDowntimeAndRegressedElapsedFailBoundedRatherThanResettingAge() {
        val original = source()
        val unknown = MeshEngine()
        unknown.restore(original.bytes, now, now + 1000, 0)
        assertTrue(unknown.inventory(now, 0).isEmpty())
        val trusted = MeshEngine(ClockConfidence.TRUSTED)
        trusted.restore(original.bytes, now, now - 1000, 0)
        assertNull(trusted.forward(original.event.id, now - 1000, 0))
        val live = MeshEngine()
        live.accept(original.bytes, now, 1000)
        assertNull(live.forward(original.event.id, now, 0))
        val exhausted = MeshEngine()
        exhausted.restore(original.bytes, now, now, 0, Long.MAX_VALUE)
        assertNull(exhausted.forward(original.event.id, now, 0))
    }

    @Test fun fullSizeAcceptedBundleCanCheckpointAndRestoreWithoutLosingBacklog() {
        val original = source()
        fun padded(count: Int) = cborUnmarshalBundle(original.bytes).addBlock(CanonicalBlock(
            blockType = 200, crcType = CRCType.CRC32, data = BlobBlockData(ByteArray(count)))).cborMarshal()
        val slack = MeshPolicy.MAX_BUNDLE_BYTES - padded(0).size
        val wire = (slack - 8..slack).asSequence().map(::padded).first { it.size == MeshPolicy.MAX_BUNDLE_BYTES }
        val relay = MeshEngine()
        val accepted = relay.accept(wire, now, 0)!!
        val saved = relay.checkpoint(accepted, now, 60_000)
        assertTrue(saved.bytes.size > MeshPolicy.MAX_BUNDLE_BYTES)
        val restored = MeshEngine()
        assertNotNull(restored.restore(saved.bytes, now, now, 0, downtimeMillis = 0))
        assertEquals(original.event, restored.entries().single().event)
        assertNull(restored.forward(original.event.id, now, 0))
        assertThrows(IllegalArgumentException::class.java) { MeshEngine().accept(saved.bytes, now, 0) }
    }

    @Test fun unknownTimeStillEnforcesIncomingAgeAndUsesExplicitClockForBpCreationTime() {
        val original = source()
        val wire = cborUnmarshalBundle(original.bytes)
        wire.primaryBlock.creationTimestamp = dtnTimeFromUnixMsec(now - 2 * 86_400_000)
        val unknown = MeshEngine()
        assertNotNull(unknown.accept(wire.cborMarshal(), now, 0))
        assertThrows(IllegalArgumentException::class.java) { MeshEngine(ClockConfidence.TRUSTED).accept(wire.cborMarshal(), now, 0) }
        wire.getAgeBlockData()!!.age = wire.primaryBlock.lifetime
        assertThrows(IllegalArgumentException::class.java) { MeshEngine().accept(wire.cborMarshal(), now, 0) }
    }
}
