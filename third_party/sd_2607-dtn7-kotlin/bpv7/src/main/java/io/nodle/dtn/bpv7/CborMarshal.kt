package io.nodle.dtn.bpv7

import com.fasterxml.jackson.dataformat.cbor.CBORGenerator
import io.nodle.dtn.bpv7.administrative.cborMarshal
import io.nodle.dtn.bpv7.eid.*
import io.nodle.dtn.crypto.CRC
import io.nodle.dtn.crypto.CRC16X25
import io.nodle.dtn.crypto.CRC32C
import io.nodle.dtn.crypto.NullCRC
import io.nodle.dtn.utils.CloseProtectOutputStream
import io.nodle.dtn.utils.DualOutputStream
import io.nodle.dtn.utils.isFlagSet
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.URI
import java.math.BigInteger

/**
 * @author Lucien Loiseau on 12/02/21.
 */

class CborEncodingException(msg: String) : Exception(msg)

@Throws(CborEncodingException::class)
fun Bundle.cborMarshal() : ByteArray {
    return ByteArrayOutputStream().use {
        this.cborMarshal(it)
        it.toByteArray()
    }
}

@Throws(CborEncodingException::class)
fun Bundle.cborMarshal(out: OutputStream) {
    createBpv7Generator(CloseProtectOutputStream(out)).use {
        it.writeStartArray()
        it.flush()
        primaryBlock.cborMarshal(out)
        for (block in canonicalBlocks) {
            block.cborMarshal(out)
        }
        it.writeEndArray()
    }
}

@Throws(CborEncodingException::class)
fun PrimaryBlock.cborMarshal(): ByteArray {
    return ByteArrayOutputStream().use {
        this.cborMarshal(it)
        it.toByteArray()
    }
}

@Throws(CborEncodingException::class)
fun PrimaryBlock.cborMarshal(out: OutputStream) {
    if (receivedEncoding != null && this == receivedFields) {
        out.write(receivedEncoding!!)
        return
    }
    val crc = when (crcType) {
        CRCType.NoCRC -> NullCRC()
        CRCType.CRC16 -> CRC16X25()
        CRCType.CRC32 -> CRC32C()
    }

    // multiplexing the output stream to compute crc at same time
    val dualOut = DualOutputStream(crc, out)
    createBpv7Generator(dualOut).use {
        it.writeStartArray(null, cborGetItemCount())
        it.writeNumber(version)
        it.writeControlFlags(procV7Flags)
        it.writeNumber(crcType.ordinal)
        it.cborMarshal(destination)
        it.cborMarshal(source)
        it.cborMarshal(reportTo)
        it.cborMarshal(creationTimestamp, sequenceNumber)
        it.writeNumber(lifetime)
        if (procV7Flags.isFlagSet(BundleV7Flags.IsFragment.offset)) {
            it.writeNumber(fragmentOffset)
            it.writeNumber(appDataLength)
        }
        if (crcType != CRCType.NoCRC) {
            it.flush() // force into the crc
            it.writeBinary(endCRC(crc).done())
        }
        it.writeEndArray()
    }
}

fun PrimaryBlock.endCRC(crc: CRC): CRC {
    // The CRC SHALL be computed over the concatenation of
    // all bytes (including CBOR "break" characters) of the primary block
    // including the CRC field itself, which for this purpose SHALL be
    // temporarily populated with all bytes set to zero.
    createBpv7Generator(crc).use {
        when (crcType) {
            CRCType.CRC16 -> it.writeBinary(byteArrayOf(0, 0))
            CRCType.CRC32 -> it.writeBinary(byteArrayOf(0, 0, 0, 0))
            CRCType.NoCRC -> {
            }
        }
    }
    return crc
}

fun PrimaryBlock.cborGetItemCount(): Int {
    var length = 8
    if (this.crcType != CRCType.NoCRC) {
        length++
    }
    if (this.procV7Flags.isFlagSet(BundleV7Flags.IsFragment.offset)) {
        length += 2
    }
    return length
}

@Throws(CborEncodingException::class)
fun CanonicalBlock.cborMarshal(): ByteArray {
    return ByteArrayOutputStream().use {
        this.cborMarshal(it)
        it.toByteArray()
    }
}

@Throws(CborEncodingException::class)
fun CanonicalBlock.cborMarshal(out: OutputStream) {
    val crc = when (crcType) {
        CRCType.NoCRC -> NullCRC()
        CRCType.CRC16 -> CRC16X25()
        CRCType.CRC32 -> CRC32C()
    }

    // multiplexing the output stream to compute crc at same time
    val dualOut = DualOutputStream(crc, out)
    createBpv7Generator(dualOut).use {
        it.writeStartArray(null, cborGetItemCount())
        it.writeNumber(blockType)
        it.writeNumber(blockNumber)
        it.writeControlFlags(procV7flags)
        it.writeNumber(crcType.ordinal)
        val encodedData = encodeBlockData(blockType, data)
        val unchanged = blockType == receivedBlockType && encodedData.contentEquals(decodedDataEncoding)
        it.writeBinary(if (unchanged) receivedData!! else encodedData)

        if (crcType != CRCType.NoCRC) {
            it.flush() // force into the crc
            it.writeBinary(crc.endCRC(crcType).done())
        }
        it.writeEndArray()
    }
}


@Throws(CborEncodingException::class)
fun CBORGenerator.writeBlockData(blockType: Int, data: ExtensionBlockData) {
    writeBinary(encodeBlockData(blockType, data))
}

internal fun encodeBlockData(blockType: Int, data: ExtensionBlockData): ByteArray =
    getBpv7BlockExtensionEncoder(blockType)?.let { marshal ->
        val buf = ByteArrayOutputStream()
        marshal(data, buf)
        buf.toByteArray()
    } ?: run {
        if (data !is BlobBlockData) {
            throw CborEncodingException("block data must be raw bytes when no encoder is registered")
        }
        data.buffer
    }


fun CRC.endCRC(crcType: CRCType): CRC {
    // The CRC SHALL be computed over the concatenation of
    // all bytes (including CBOR "break" characters) of the primary block
    // including the CRC field itself, which for this purpose SHALL be
    // temporarily populated with all bytes set to zero.
    createBpv7Generator(this).use {
        when (crcType) {
            CRCType.CRC16 -> it.writeBinary(byteArrayOf(0, 0))
            CRCType.CRC32 -> it.writeBinary(byteArrayOf(0, 0, 0, 0))
            CRCType.NoCRC -> {
            }
        }
    }
    return this
}

fun CanonicalBlock.cborGetItemCount(): Int {
    if (crcType == CRCType.NoCRC) {
        return 5
    }
    return 6
}

@Throws(CborEncodingException::class)
fun CBORGenerator.cborMarshal(uri: URI) {
    if (uri.isIpnEid()) {
        writeStartArray(null, 2)
        writeNumber(EID_IPN_IANA_VALUE)
        val parts = uri.schemeSpecificPart.split('.').map { BigInteger(it) }
        if (parts.any { it.signum() < 0 || it.bitLength() > 64 }) {
            throw CborEncodingException("ipn number outside uint64 range")
        }
        writeStartArray(null, 2)
        writeNumber(parts[0])
        writeNumber(parts[1])
        writeEndArray()
        writeEndArray()
        return
    }
    if (uri.isNullEid()) {
        writeStartArray(null, 2)
        writeNumber(EID_DTN_IANA_VALUE)
        writeNumber(0)
        writeEndArray()
        return
    }
    if (uri.isDtnEid()) {
        writeStartArray(null, 2)
        writeNumber(EID_DTN_IANA_VALUE)
        writeString(uri.rawSchemeSpecificPart)
        writeEndArray()
        return
    }
    throw CborEncodingException("eid not supported: ${uri.toASCIIString()}")
}

@Throws(CborEncodingException::class)
fun CBORGenerator.cborMarshal(creationTimestamp: DtnTime, sequenceNumber: Long) {
    writeStartArray(null, 2)
    writeNumber(creationTimestamp)
    writeNumber(sequenceNumber)
    writeEndArray()
}

private fun CBORGenerator.writeControlFlags(flags: Long) {
    writeNumber(BigInteger(java.lang.Long.toUnsignedString(flags)))
}
