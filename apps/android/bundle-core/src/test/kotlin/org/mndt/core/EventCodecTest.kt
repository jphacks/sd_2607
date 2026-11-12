package org.mndt.core

import org.junit.Assert.*
import org.junit.Test

class EventCodecTest {
    private val author = Author(SecretKeys.generate(), "https://pds.invalid")
    private val now = 1_800_000_000_123L

    @Test fun postRoundTripsAndOmitsFixedTags() {
        val event = author.createPost("本文", now, extensions = listOf(mapOf("\$type" to "x.third.party", "n" to 1L))).event
        assertEquals(listOf("t", "mosimo.jisaku.dev"), event.tags[0])
        assertEquals(listOf("mosimo:v", "1"), event.tags[1])
        assertEquals(listOf("mosimo:commit", "mosimo:base", "mosimo:rkey", "mosimo:exp"), event.tags.drop(2).take(4).map { it[0] })
        assertEquals("mosimo:ext:x.third.party", event.tags[6][0])
        val payload = EventCodec.encode(event)
        val a = CanonicalCbor.decode(payload) as List<*>
        assertEquals(7, a.size); assertEquals(1L, a[0])
        val tags = a[4] as List<*>
        assertEquals("mosimo:commit", (tags[0] as List<*>)[0])
        assertTrue((tags[0] as List<*>)[1] is ByteArray)
        assertArrayEquals(ByteArray(0), (tags[1] as List<*>)[1] as ByteArray) // first post: base ""
        assertTrue((tags[2] as List<*>)[1] is Long)  // rkey TID as uint
        assertEquals(86_400L, (tags[3] as List<*>)[1]) // exp as seconds after created_at
        assertTrue((tags[4] as List<*>)[1] is ByteArray)
        assertEquals(event, EventCodec.decode(payload))
    }

    @Test fun nonCanonicalValuesStayStrings() {
        // A Base64 value with padding cannot be typed and must survive as text.
        val tags = listOf(listOf("t", "mosimo.jisaku.dev"), listOf("mosimo:exp", "2027-01-01T00:00:00.000Z"), listOf("mosimo:ext:x.y", "AQI="), listOf("z", "001"))
        val event = author.signEvent(AtRepository.CONTROL_KIND, tags.drop(0).filterNot { it[0] == "mosimo:exp" }, "", now)
        val back = EventCodec.decode(EventCodec.encode(event))
        assertEquals(event, back)
        val a = CanonicalCbor.decode(EventCodec.encode(event)) as List<*>
        assertTrue((a[4] as List<*>).all { tag -> (tag as List<*>).all { it is String || it is Long } })
    }

    @Test fun eventsNotFollowingTheFixedOrderAreRefused() {
        val event = author.signEvent(AtRepository.CONTROL_KIND, listOf(listOf("x", "y"), listOf("t", "mosimo.jisaku.dev")), "", now)
        assertThrows(Exception::class.java) { EventCodec.encode(event) }
    }
}
