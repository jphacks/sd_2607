package io.nodle.dtn.bpv7

import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.dataformat.cbor.CBORFactory
import com.fasterxml.jackson.dataformat.cbor.CBORParser
import io.nodle.dtn.bpv7.eid.EID_DTN_IANA_VALUE
import io.nodle.dtn.bpv7.eid.EID_IPN_IANA_VALUE
import io.nodle.dtn.bpv7.eid.createIpn
import io.nodle.dtn.bpv7.eid.nullDtnEid
import io.nodle.dtn.utils.*
import java.io.InputStream
import java.net.URI
import java.net.URISyntaxException

/**
 * @author Lucien Loiseau on 12/02/21.
 */
class CborParsingException(msg: String) : Exception(msg)

@Throws(CborParsingException::class)
fun cborUnmarshalBundle(buffer: ByteArray) =
    CBORFactory().createParser(buffer).use { it.readBundle() }

@Throws(CborParsingException::class)
fun cborUnmarshalBundle(input: InputStream) =
    createBpv7Parser(input).use { it.readBundle() }

@Throws(CborParsingException::class)
fun CBORParser.readBundle(): Bundle {
    readStartArray()
    return readBundleContents()
}

/** Returns null only at a clean stream boundary, not for a truncated bundle. */
fun CBORParser.readBundleOrNull(): Bundle? {
    val token = nextToken() ?: return null
    if (token != JsonToken.START_ARRAY) throw CborParsingException("expected bundle array")
    return readBundleContents()
}

private fun CBORParser.readBundleContents(): Bundle {
    val bundle = readPrimaryBlock().makeBundle()
    while (nextToken() != JsonToken.END_ARRAY) {
        bundle.canonicalBlocks.add(readCanonicalBlock(true))
    }
    return bundle
}

@Throws(CborParsingException::class)
fun cborUnmarshalPrimaryBlock(buffer: ByteArray) =
    CBORFactory().createParser(buffer).use { it.readPrimaryBlock() }

@Throws(CborParsingException::class)
fun CBORParser.readPrimaryBlock(): PrimaryBlock {
    readStartArray()
    val start = tokenLocation.byteOffset
    val primary = PrimaryBlock(
        version = readUnsignedInt(),
        procV7Flags = readControlFlags(),
        crcType = CRCType.fromInt(readUnsignedInt()),
        destination = readEid(),
        source = readEid(),
        reportTo = readEid(),
        creationTimestamp = let { readStartArray(); readUnsignedLong() },
        sequenceNumber = readUnsignedLong(),
        lifetime = let { readCloseArray(); readUnsignedLong() }
    )
    if (primary.isFragment()) {
        primary.fragmentOffset = readUnsignedLong()
        primary.appDataLength = readUnsignedLong()
    }
    val crc = if (primary.crcType == CRCType.NoCRC) byteArrayOf() else readByteArray()
    val crcOffset = currentLocation.byteOffset - start - crc.size
    readCloseArray()
    val wire = wireBytes(start, currentLocation.byteOffset)
    verifyWireCRC(primary.crcType, wire, crcOffset.toInt(), crc)
    primary.receivedEncoding = wire
    primary.receivedFields = primary.copy()
    releaseWireBytes()
    return primary
}

@Throws(CborParsingException::class)
fun cborUnmarshalCanonicalBlock(buffer: ByteArray) =
    CBORFactory().createParser(buffer).use { it.readCanonicalBlock() }

@Throws(CborParsingException::class)
fun CBORParser.readCanonicalBlock(prefetch: Boolean): CanonicalBlock {
    if (prefetch) assertStartArray() else readStartArray()
    val start = tokenLocation.byteOffset
    val block = CanonicalBlock(
        blockType = readUnsignedInt(),
        blockNumber = readUnsignedInt(),
        procV7flags = readControlFlags(),
        crcType = CRCType.fromInt(readUnsignedInt())
    )
    val rawData = readByteArray()
    val dataStart = tokenLocation.byteOffset
    val dataHeader = wireBytes(dataStart, dataStart + 1)[0].toInt() and 0xff
    if (dataHeader and 0xe0 != 0x40 || dataHeader and 0x1f == 31) {
        throw CborParsingException("block data must be a definite-length byte string")
    }
    val crc = if (block.crcType == CRCType.NoCRC) byteArrayOf() else readByteArray()
    val crcOffset = currentLocation.byteOffset - start - crc.size
    readCloseArray()
    verifyWireCRC(block.crcType, wireBytes(start, currentLocation.byteOffset), crcOffset.toInt(), crc)
    releaseWireBytes()

    // Verify the envelope before interpreting extension data.
    block.data = getBpv7BlockExtensionParser(block.blockType)?.let { unmarshal ->
        CBORFactory().createParser(rawData).use { parser ->
            val data = unmarshal(parser)
            if (parser.nextToken() != null) throw CborParsingException("trailing extension data")
            data
        }.also {
            block.receivedBlockType = block.blockType
            block.receivedData = rawData
            block.decodedDataEncoding = encodeBlockData(block.blockType, it)
        }
    } ?: BlobBlockData(rawData)
    return block
}

@Throws(CborParsingException::class)
fun CBORParser.readCanonicalBlock() = readCanonicalBlock(false)

@Throws(CborParsingException::class)
fun CBORParser.readEid(): URI {
    return readStruct(false) {
        try {
            when (readUnsignedInt()) {
                EID_IPN_IANA_VALUE -> readStruct {
                    createIpn(readUnsignedInteger(), readUnsignedInteger())
                }
                EID_DTN_IANA_VALUE -> {
                    val t = nextToken()
                    if (t == JsonToken.VALUE_NUMBER_INT) {
                        check(0 == intValue)
                        nullDtnEid()
                    } else if (t == JsonToken.VALUE_STRING) {
                        URI("dtn:$text")
                    } else {
                        throw CborParsingException("invalid dtn scheme-specific part")
                    }
                }
                else -> throw CborParsingException("eid unsupported")
            }
        } catch (e: URISyntaxException) {
            throw CborParsingException("invalid dtn eid: ${e.message}");
        } catch (e: IllegalStateException) {
            throw CborParsingException("invalid eid: ${e.message}");
        }
    }
}



