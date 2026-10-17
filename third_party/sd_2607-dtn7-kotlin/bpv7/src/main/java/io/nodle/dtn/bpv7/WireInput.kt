package io.nodle.dtn.bpv7

import com.fasterxml.jackson.dataformat.cbor.CBORFactory
import com.fasterxml.jackson.dataformat.cbor.CBORParser
import io.nodle.dtn.crypto.CRC16X25
import io.nodle.dtn.crypto.CRC32C
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Parser for one or more concatenated bundles. Retains only the current block and Jackson's
 * read-ahead, so CRCs can be checked against received bytes rather than reserialized objects.
 * The limit is a local resource policy, not a BPv7 wire-format restriction.
 */
fun createBpv7Parser(input: InputStream, maxBlockBytes: Int = 64 * 1024 * 1024): CBORParser {
    require(maxBlockBytes > 0 && maxBlockBytes <= Int.MAX_VALUE - 8192)
    return CBORFactory().createParser(WireInput(input, maxBlockBytes))
}

private class WireInput(private val input: InputStream, private val limit: Int) : InputStream() {
    private val captured = ByteArrayOutputStream()
    private var base = 0L

    override fun read(): Int {
        val b = input.read()
        if (b >= 0) {
            checkSize(1)
            captured.write(b)
        }
        return b
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        val count = input.read(bytes, offset, length)
        if (count > 0) {
            checkSize(count)
            captured.write(bytes, offset, count)
        }
        return count
    }

    private fun checkSize(extra: Int) {
        if (captured.size().toLong() + extra > limit.toLong() + 8192) {
            throw CborParsingException("block exceeds local size limit")
        }
    }

    fun bytes(start: Long, end: Long): ByteArray {
        if (start < base || end < start || end - base > captured.size() || end - start > limit) {
            throw CborParsingException("invalid or oversized encoded block")
        }
        return captured.toByteArray().copyOfRange((start - base).toInt(), (end - base).toInt())
    }

    fun discardBefore(end: Long) {
        val remaining = captured.toByteArray().copyOfRange((end - base).toInt(), captured.size())
        captured.reset()
        captured.write(remaining)
        base = end
    }

    override fun close() = input.close()
}

@Suppress("DEPRECATION")
internal fun CBORParser.wireBytes(start: Long, end: Long): ByteArray {
    val input = inputSource
    if (input is WireInput) return input.bytes(start, end)
    val bytes = tokenLocation.sourceRef as? ByteArray
        ?: throw CborParsingException("use createBpv7Parser for streaming BPv7 decoding")
    if (start < 0 || end < start || end > bytes.size) {
        throw CborParsingException("invalid block byte range")
    }
    return bytes.copyOfRange(start.toInt(), end.toInt())
}

internal fun CBORParser.releaseWireBytes() {
    (inputSource as? WireInput)?.discardBefore(currentLocation.byteOffset)
}

internal fun verifyWireCRC(type: CRCType, wire: ByteArray, valueOffset: Int, received: ByteArray) {
    if (type == CRCType.NoCRC) return
    val expectedSize = if (type == CRCType.CRC16) 2 else 4
    if (received.size != expectedSize || valueOffset < 1 || valueOffset + expectedSize > wire.size ||
        wire[valueOffset - 1].toInt() != 0x40 + expectedSize
    ) {
        throw CborParsingException("invalid CRC field")
    }
    val crc = if (type == CRCType.CRC16) CRC16X25() else CRC32C()
    crc.write(wire, 0, valueOffset)
    repeat(expectedSize) { crc.write(0) }
    crc.write(wire, valueOffset + expectedSize, wire.size - valueOffset - expectedSize)
    if (!crc.done().contentEquals(received)) throw CborParsingException("wrong crc")
}
