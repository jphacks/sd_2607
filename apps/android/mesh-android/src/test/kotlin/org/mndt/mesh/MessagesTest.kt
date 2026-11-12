package org.mndt.mesh

import org.junit.Assert.*
import org.junit.Test
import org.mndt.core.CanonicalCbor

class MessagesTest {
    private val id = ByteArray(32) { it.toByte() }

    @Test fun everyMessageRoundTripsWithSpecLayout() {
        val messages = listOf(
            Msg.Inventory(listOf(id)), Msg.Want(3, listOf(id)), Msg.Begin(1, 2, id, 10, byteArrayOf(1)), Msg.Begin(1, 2, id, 10, null),
            Msg.Data(1, byteArrayOf(9)), Msg.Credit(1, 10), Msg.Reconcile(0, byteArrayOf(0x61)), Msg.Fetch(4, listOf(id)),
            Msg.Result(Msg.Target.Transfer(1, 5), 0, 0), Msg.Result(Msg.Target.Event(0, 2, id), 3, 6), Msg.Result(Msg.Target.Object(0, 4, id), 3, 6),
        )
        for (m in messages) {
            val bytes = Messages.encode(m)
            assertEquals(Messages.encode(Messages.decode(bytes)).toList(), bytes.toList())
        }
        // §5.7: BEGIN = [3, transfer, request, id, length, ?fragment]
        assertEquals(listOf(3L, 1L, 2L), (CanonicalCbor.decode(Messages.encode(Msg.Begin(1, 2, id, 10, null))) as List<*>).take(3))
        // §5.10: RESULT target for a transfer = [1, side, transfer]
        assertEquals(listOf(6L, listOf(1L, 1L, 5L), 0L, 0L), CanonicalCbor.decode(Messages.encode(Msg.Result(Msg.Target.Transfer(1, 5), 0, 0))))
    }

    @Test fun helloFitsTheFloorAndIgnoresUnknownExtensions() {
        val hello = Msg.Hello(ByteArray(8), 65_536, 3, 4096, 16_384, listOf(mapOf("\$type" to "x.future", "v" to 1L)))
        val bytes = Messages.encode(hello)
        assertTrue(bytes.size <= Messages.HELLO_MAX_BYTES)
        val back = Messages.decode(bytes) as Msg.Hello
        assertEquals(16_384, back.maxMessageBytes)
        assertEquals("x.future", back.extensions.single()["\$type"])
        // Version 2 is refused; a duplicate extension $type is invalid.
        assertThrows(Exception::class.java) { Messages.decode(CanonicalCbor.encode(listOf(0L, 2L, ByteArray(8), 1L, 1L, 0L, 1024L, listOf<Any>()))) }
        val dup = listOf(mapOf("\$type" to "a"), mapOf("\$type" to "a"))
        assertThrows(Exception::class.java) { Messages.decode(CanonicalCbor.encode(listOf(0L, 1L, ByteArray(8), 1L, 1L, 0L, 1024L, dup))) }
        // Message limit below the 1,024 floor is invalid.
        assertThrows(Exception::class.java) { Messages.decode(CanonicalCbor.encode(listOf(0L, 1L, ByteArray(8), 1L, 1L, 0L, 1000L, listOf<Any>()))) }
    }

    @Test fun unknownTypeDecodesAsUnknown() {
        assertEquals(Msg.Unknown(42), Messages.decode(CanonicalCbor.encode(listOf(42L, "x"))))
    }

    @Test fun idListsFitTheSmallestLimit() {
        val n = Messages.idsPerMessage(1024)
        assertTrue(n >= 29)
        assertTrue(Messages.encode(Msg.Want(65_535, List(n) { i -> ByteArray(32) { i.toByte() } })).size <= 1024)
        assertTrue(Messages.encode(Msg.Inventory(List(n) { i -> ByteArray(32) { i.toByte() } })).size <= 1024)
    }

    @Test fun cborSequenceSplitsAtItemBoundariesAcrossAnyChunking() {
        val items = listOf(Msg.Data(1, ByteArray(700) { 1 }), Msg.Credit(1, 9), Msg.Inventory(listOf(id))).map(Messages::encode)
        val stream = items.reduce(ByteArray::plus)
        for (chunk in listOf(1, 3, 20, 244, stream.size)) {
            val reader = CborSequenceReader(1024)
            val out = stream.toList().chunked(chunk).flatMap { reader.feed(it.toByteArray()) }
            assertEquals(items.map { it.toList() }, out.map { it.toList() })
            assertEquals(0, reader.incomplete)
        }
        // A header announcing an item past the limit means the stream lost sync (§7).
        assertThrows(Exception::class.java) { CborSequenceReader(1024).feed(byteArrayOf(0x83.toByte(), 4, 1, 0x59, 0x10, 0)) }
    }
}
