package org.mndt.mesh

import org.junit.Assert.*
import org.junit.Test

class WireTest {
    @Test fun relayFramesAllowJsonEscapeExpansionBeyondNearbyCap() {
        val eventJson = json.writeValueAsString(mapOf("content" to "\u0001".repeat(16_000)))
        assertTrue(eventJson.toByteArray(Charsets.UTF_8).size > Wire.MAX_BYTES)
        val frame = json.readTree(relayEventFrame(eventJson))
        assertEquals("EVENT", frame[0].textValue())
        assertEquals("\u0001".repeat(16_000), frame[1]["content"].textValue())
        assertTrue(runCatching { relayEventFrame("x".repeat(262145)) }.isFailure)
    }

    @Test fun failedFirstRelayEventDoesNotStarveLaterPosts() {
        val ids = listOf("a", "b", "c")
        assertEquals("a", nextRelayId(ids, null, emptyMap(), 0))
        assertEquals("b", nextRelayId(ids, "a", emptyMap(), 20_000))
        assertEquals("c", nextRelayId(ids, "b", mapOf("a" to 30_000), 20_000))
        assertEquals("a", nextRelayId(ids, "c", emptyMap(), 30_000))
        assertNull(nextRelayId(ids, "a", ids.associateWith { 30_000L }, 20_000))
        assertNull(nextRelayId(emptyList(), "a", emptyMap(), 0))
        assertEquals("b", nextRelayId(listOf("b", "c"), "a", emptyMap(), 0))
    }

    @Test fun handshakeAndUntrustedInventoryAreBounded() {
        assertEquals(Control("hello"), Wire.decode(Wire.encode(Control("hello"))))
        val id = "a".repeat(64)
        assertEquals(listOf(id), Wire.decode(Wire.encode(Control("inventory", listOf(id)))).ids)
        listOf(
            "{\"v\":2,\"type\":\"hello\"}",
            "{\"v\":4294967297,\"type\":\"hello\",\"group\":\"${Wire.GROUP}\"}",
            "{\"v\":1,\"v\":1,\"type\":\"hello\"}",
            "{\"v\":1,\"type\":\"want\",\"ids\":[\"bad\"]}",
            "{\"v\":1,\"type\":\"ack\",\"ids\":[]}",
            "{\"v\":1,\"type\":\"hello\"}{}"
        ).forEach { bad ->
            assertTrue(bad, runCatching { Wire.decode(bad.toByteArray()) }.isFailure)
        }
        assertTrue(runCatching { Wire.decode(ByteArray(32769)) }.isFailure)
        assertTrue(runCatching { Wire.encode(Control("inventory", List(129) { id })) }.isFailure)
        val handshake = Handshake()
        assertFalse(handshake.ready)
        assertTrue(runCatching { handshake.receive(Control("want", listOf(id))) }.isFailure)
        handshake.receive(Control("hello"))
        assertTrue(handshake.ready)
        handshake.receive(Control("inventory", listOf(id)))
    }

    @Test fun boundedReadsWorkWithoutNewAndroidJavaApis() {
        assertArrayEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3).inputStream().readBounded(3))
        assertArrayEquals(byteArrayOf(), byteArrayOf().inputStream().readBounded(0))
        assertTrue(runCatching { byteArrayOf(1, 2, 3, 4).inputStream().readBounded(3) }.isFailure)
    }
}
