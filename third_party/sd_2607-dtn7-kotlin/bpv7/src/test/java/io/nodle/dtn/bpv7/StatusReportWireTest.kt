package io.nodle.dtn.bpv7

import io.nodle.dtn.bpv7.administrative.*
import io.nodle.dtn.utils.hexToBa
import org.junit.Assert.*
import org.junit.Test

class StatusReportWireTest {
    @Test
    fun assertedStatusWithoutTimeCanBeDecodedAndReencoded() {
        val encoded = "8201848481f581f481f481f400820100820001".hexToBa()
        val record = cborUnmarshalAdmnistrativeRecord(encoded)
        assertArrayEquals(encoded, record.cborMarshalData())
    }

    @Test
    fun generatedReportHonorsTheSubjectsTimeRequest() {
        val subject = PrimaryBlock(creationTimestamp = 0, sequenceNumber = 1).makeBundle()
            .addBlock(payloadBlock(byteArrayOf(1)))
        val record = statusRecord(subject, StatusAssertion.ReceivedBundle, StatusReportReason.NoInformation, 1234)
        assertArrayEquals("8201848481f581f481f481f400820100820001".hexToBa(), record.cborMarshalData())
        subject.primaryBlock.setProcV7Flags(BundleV7Flags.RequestStatusTime)
        val timed = statusRecord(subject, StatusAssertion.ReceivedBundle, StatusReportReason.NoInformation, 0)
        assertArrayEquals("8201848482f50081f481f481f400820100820001".hexToBa(), timed.cborMarshalData())
        assertArrayEquals(timed.cborMarshalData(), cborUnmarshalAdmnistrativeRecord(timed.cborMarshalData()).cborMarshalData())
    }
}
