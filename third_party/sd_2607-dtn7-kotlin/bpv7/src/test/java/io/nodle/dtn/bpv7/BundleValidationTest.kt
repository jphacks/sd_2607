package io.nodle.dtn.bpv7

import io.nodle.dtn.bpv7.extensions.*
import io.nodle.dtn.utils.hexToBa
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class BundleValidationTest {
    private fun bundle() = PrimaryBlock(source = URI.create("dtn://source/"))
        .makeBundle().addBlock(payloadBlock(byteArrayOf(1)))

    @Test
    fun rejectsInvalidStructureWithoutSilentlyDroppingReceivedBlocks() {
        val invalid: List<(Bundle) -> Unit> = listOf(
            { it.primaryBlock.version = 6 },
            { it.canonicalBlocks.add(0, payloadBlock(byteArrayOf(2)).apply { blockNumber = 2 }) },
            { it.canonicalBlocks.clear() },
            { it.getPayloadBlock().blockNumber = 2 },
            { it.addBlock(ageBlock(1)).addBlock(ageBlock(2)) },
            { it.addBlock(hopCountBlockData(0, 0)) },
            { it.addBlock(hopCountBlockData(256, 0)) },
            { it.addBlock(hopCountBlockData(10, -1)) },
            { it.primaryBlock.procV7Flags = 0x4002 }, // administrative record requesting reception reports
            { it.getPayloadBlock().blockNumber = -1 }
        )
        for (change in invalid) {
            val b = bundle().also(change)
            assertFalse(b.isValid())
        }
        val duplicate = bundle().apply(invalid[1])
        assertEquals(2, cborUnmarshalBundle(duplicate.cborMarshal()).canonicalBlocks.size)
    }

    @Test
    fun zeroCreationTimeRequiresExactlyOneAgeBlock() {
        val b = bundle().apply { primaryBlock.creationTimestamp = 0 }
        assertFalse(b.isValid())
        b.addBlock(ageBlock(0))
        assertTrue(b.isValid())
        b.addBlock(ageBlock(0))
        assertFalse(b.isValid())
    }

    @Test
    fun rejectsFloatingPointAndNegativeUnsignedHeaders() {
        for (hex in listOf("85f9400002000043010203", "8518c820000043010203")) {
            assertThrows(CborParsingException::class.java) { cborUnmarshalCanonicalBlock(hex.hexToBa()) }
        }
    }

    @Test
    fun ipnAndHighControlFlagsRoundTrip() {
        val highFlagsBlock = "8518c8021b80000000000000000043010203".hexToBa()
        val parsed = cborUnmarshalCanonicalBlock(highFlagsBlock)
        assertEquals(Long.MIN_VALUE, parsed.procV7flags)
        assertArrayEquals(highFlagsBlock, parsed.cborMarshal())
        val b = bundle()
        b.primaryBlock.destination = URI.create("ipn:4294967295.18446744073709551615")
        b.primaryBlock.procV7Flags = Long.MIN_VALUE
        b.getPayloadBlock().procV7flags = Long.MIN_VALUE
        assertEquals(b, cborUnmarshalBundle(b.cborMarshal()))
        b.primaryBlock.destination = URI.create("dtn://destination/a%2Fb")
        assertEquals(b, cborUnmarshalBundle(b.cborMarshal()))
    }

    @Test
    fun insertionUsesAHoleWhenTheHighestSupportedNumberIsAlreadyUsed() {
        val b = bundle()
        b.addBlock(CanonicalBlock(200, Int.MAX_VALUE, data = BlobBlockData()), pickNumber = false)
        b.addBlock(ageBlock(0))
        b.checkValid()
        assertEquals(listOf(Int.MAX_VALUE, 2, 1), b.canonicalBlocks.map { it.blockNumber })
    }
}
