package org.mndt.mesh

import org.junit.Assert.*
import org.junit.Test

class FramesTest {
    @Test fun reassemblesAcrossArbitraryChunksAndRejectsBadLengths() {
        val frames = listOf(byteArrayOf(1), ByteArray(Frames.MAX_FRAME) { it.toByte() }, byteArrayOf(7, 8, 9))
        val stream = frames.map(Frames::encode).reduce(ByteArray::plus)
        for (chunk in listOf(1, 3, 20, 512, stream.size)) {
            val reader = FrameReader()
            val out = stream.toList().chunked(chunk).flatMap { reader.feed(it.toByteArray()) }
            assertEquals(frames.map { it.toList() }, out.map { it.toList() })
        }
        assertThrows(Exception::class.java) { FrameReader().feed(byteArrayOf(0, 0, 0, 0)) }
        assertThrows(Exception::class.java) { FrameReader().feed(byteArrayOf(0, 0x00, 0x80.toByte(), 1)) }
        assertThrows(Exception::class.java) { Frames.encode(ByteArray(Frames.MAX_FRAME + 1)) }
    }
}
