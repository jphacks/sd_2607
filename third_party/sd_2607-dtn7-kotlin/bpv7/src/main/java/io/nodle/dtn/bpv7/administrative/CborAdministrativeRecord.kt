package io.nodle.dtn.bpv7.administrative

import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.dataformat.cbor.CBORFactory
import com.fasterxml.jackson.dataformat.cbor.CBORGenerator
import com.fasterxml.jackson.dataformat.cbor.CBORParser
import io.nodle.dtn.bpv7.CborEncodingException
import io.nodle.dtn.bpv7.CborParsingException
import io.nodle.dtn.bpv7.bpsec.cborMarshal
import io.nodle.dtn.bpv7.cborMarshal
import io.nodle.dtn.bpv7.eid.EID_IPN_IANA_VALUE
import io.nodle.dtn.bpv7.eid.getNodeNumberUnsafe
import io.nodle.dtn.bpv7.eid.getServiceNumberUnsafe
import io.nodle.dtn.bpv7.readEid
import io.nodle.dtn.bpv7.createBpv7Generator
import io.nodle.dtn.utils.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * @author Lucien Loiseau on 18/02/21.
 */
@Throws(CborEncodingException::class)
fun AdministrativeRecord.cborMarshalData(): ByteArray =
    ByteArrayOutputStream().use {
        cborMarshalData(it)
        it
    }.toByteArray()

@Throws(CborEncodingException::class)
fun AdministrativeRecord.cborMarshalData(out: OutputStream) {
    createBpv7Generator(CloseProtectOutputStream(out)).use {
        it.writeStartArray(null, 2)
        it.writeNumber(recordTypeCode)
        when (data) {
            is StatusReport -> it.cborMarshal(data as StatusReport)
        }
        it.writeEndArray()
    }
}

fun CBORGenerator.cborMarshal(sr: StatusReport) {
    writeStartArray(null, sr.cborGetItemCount())

    // prepare assertions
    val assertions = mutableListOf<StatusItem>()
    assertions.add(0, StatusItem(0, sr.received > 0 || StatusAssertion.ReceivedBundle in sr.assertedStatuses, sr.received, sr.reportStatusTime))
    assertions.add(1, StatusItem(1, sr.forwarded > 0 || StatusAssertion.ForwardedBundle in sr.assertedStatuses, sr.forwarded, sr.reportStatusTime))
    assertions.add(2, StatusItem(2, sr.delivered > 0 || StatusAssertion.DeliveredBundle in sr.assertedStatuses, sr.delivered, sr.reportStatusTime))
    assertions.add(3, StatusItem(3, sr.deleted > 0 || StatusAssertion.DeletedBundle in sr.assertedStatuses, sr.deleted, sr.reportStatusTime))
    assertions.addAll(sr.otherAssertions)

    writeStartArray(null, assertions.size)
    assertions.forEach { statusItem ->
        cborMarshal(statusItem)
    }
    writeEndArray()
    writeNumber(sr.bundleStatusReportReason)
    cborMarshal(sr.sourceNodeId)
    cborMarshal(sr.creationTimestamp, sr.sequenceNumber)
    if (sr.isFragment) {
        writeNumber(sr.fragmentOffset)
        writeNumber(sr.appDataLength)
    }
    writeEndArray()
}

fun CBORGenerator.cborMarshal(item: StatusItem) {
    writeStartArray(null, item.cborGetItemCount())
    writeBoolean(item.asserted)
    if (item.asserted && item.hasTimestamp) {
        writeNumber(item.timestamp)
    }
    writeEndArray()
}

fun StatusReport.cborGetItemCount(): Int {
    if (!isFragment) {
        return 4
    }
    return 6
}


fun StatusItem.cborGetItemCount(): Int {
    return if (asserted && hasTimestamp) {
        2
    } else {
        1
    }
}

@Throws(CborParsingException::class)
fun cborUnmarshalAdmnistrativeRecord(buffer: ByteArray) =
    cborUnmarshalAdmnistrativeRecord(ByteArrayInputStream(buffer))

@Throws(CborParsingException::class)
fun cborUnmarshalAdmnistrativeRecord(input: InputStream): AdministrativeRecord {
    return CBORFactory()
        .createParser(input)
        .readAdministrativeRecord()
}

@Throws(CborParsingException::class)
fun CBORParser.readAdministrativeRecord(): AdministrativeRecord {
    return readStruct {
        AdministrativeRecord(recordTypeCode = readUnsignedInt()).also { adm ->
            adm.data = when (adm.recordTypeCode) {
                RecordTypeCode.StatusRecordType.code -> readStatusReport()
                else -> throw CborParsingException("unsupported administrative record")
            }
        }
    }
}


@Throws(CborParsingException::class)
fun CBORParser.readStatusReport(): StatusReport {
    readStartArray()
    var assertCounter = 0
    return StatusReport(
        otherAssertions = readArray { readStatusItem(assertCounter++, true) },
        bundleStatusReportReason = readInt(),
        sourceNodeId = readEid()
    ).also {
        readStartArray()
        it.creationTimestamp = readLong()
        it.sequenceNumber = readLong()
        readCloseArray()
    }.also {
        if (it.otherAssertions.size < 4) {
            throw CborParsingException("missing some of status assertion")
        }
        it.received = it.otherAssertions[0].timestamp
        it.forwarded = it.otherAssertions[1].timestamp
        it.delivered = it.otherAssertions[2].timestamp
        it.deleted = it.otherAssertions[3].timestamp
        it.assertedStatuses = StatusAssertion.values().filter { status ->
            it.otherAssertions[status.code].asserted
        }.toSet()
        it.reportStatusTime = it.otherAssertions.any { item -> item.asserted && item.hasTimestamp }
        if (it.otherAssertions.size == 4) {
            it.otherAssertions = mutableListOf()
        } else {
            it.otherAssertions = it.otherAssertions.subList(4, it.otherAssertions.size)
        }

        // unfortunately with jackson we cannot read the cbor array length
        // so we cannot know if there are the fragment offset part or not
        if (nextToken() != JsonToken.END_ARRAY) {
            if (currentToken != JsonToken.VALUE_NUMBER_INT || longValue < 0) {
                throw CborParsingException("invalid report fragment offset")
            }
            it.isFragment = true
            it.fragmentOffset = longValue
            it.appDataLength = readUnsignedLong()
            readCloseArray()
        }
    }
}

fun CBORParser.readStatusItem(code: Int, prefetch: Boolean): StatusItem {
    if (prefetch) assertStartArray() else readStartArray()
    val asserted = readBoolean()
    if (nextToken() == JsonToken.END_ARRAY) {
        return StatusItem(code, asserted, 0, false)
    }
    if (!asserted || currentToken != JsonToken.VALUE_NUMBER_INT || longValue < 0) {
        throw CborParsingException("invalid status time")
    }
    val time = longValue
    readCloseArray()
    return StatusItem(code, true, time, true)
}
