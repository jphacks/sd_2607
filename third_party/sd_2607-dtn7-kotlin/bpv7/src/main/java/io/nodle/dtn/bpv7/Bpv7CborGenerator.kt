package io.nodle.dtn.bpv7

import com.fasterxml.jackson.core.io.ContentReference
import com.fasterxml.jackson.dataformat.cbor.CBORFactory
import com.fasterxml.jackson.dataformat.cbor.CBORGenerator
import java.io.OutputStream
import java.math.BigInteger

/**
 * Jackson 2.14 writes BigInteger as CBOR tag 2, even for uint64. BP fields need
 * major type 0 instead. Keep this version-specific adaptation in one place.
 */
fun createBpv7Generator(out: OutputStream): CBORGenerator = Bpv7CborFactory().createGenerator(out)

private class Bpv7CborFactory : CBORFactory() {
    override fun createGenerator(out: OutputStream): CBORGenerator =
        object : CBORGenerator(
            _createContext(ContentReference.rawReference(out), false),
            _generatorFeatures, _formatGeneratorFeatures, _objectCodec, out
        ) {
            override fun writeNumber(value: BigInteger?) {
                if (value == null || value.signum() < 0 || value.bitLength() > 64) {
                    super.writeNumber(value)
                } else if (value.bitLength() <= 63) {
                    writeNumber(value.toLong())
                } else {
                    _verifyValueWrite("write unsigned integer")
                    writeRaw(0x1b.toByte())
                    val bits = value.toLong()
                    for (shift in 56 downTo 0 step 8) writeRaw((bits ushr shift).toByte())
                }
            }
        }
}
