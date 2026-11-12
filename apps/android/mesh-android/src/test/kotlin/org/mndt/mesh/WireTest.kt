package org.mndt.mesh

import org.junit.Assert.*
import org.junit.Test

class WireTest {
    @Test fun relayFramesAllowJsonEscapeExpansionBeyondNearbyCap() {
        val eventJson = json.writeValueAsString(mapOf("content" to "\u0001".repeat(16_000)))
        assertTrue(eventJson.toByteArray(Charsets.UTF_8).size > 32_768)
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

    @Test fun labNodeOverrideIsExplicitValidatedAndDoesNotChangeNormalRandomSelection() {
        var calls = 0
        val randomNode = { calls++; "0123456789abcdef" }
        assertEquals("c047ef246330f71e", meshNode("c047ef246330f71e", randomNode))
        assertEquals(0, calls)
        assertEquals("0123456789abcdef", meshNode(null, randomNode))
        assertEquals("0123456789abcdef", meshNode("", randomNode))
        assertEquals(2, calls)
        for (invalid in listOf("ABCDEF0123456789", "short", "g".repeat(16))) {
            assertTrue(runCatching { meshNode(invalid, randomNode) }.isFailure)
        }
    }

    @Test fun boundedReadsWorkWithoutNewAndroidJavaApis() {
        assertArrayEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3).inputStream().readBounded(3))
        assertArrayEquals(byteArrayOf(), byteArrayOf().inputStream().readBounded(0))
        assertTrue(runCatching { byteArrayOf(1, 2, 3, 4).inputStream().readBounded(3) }.isFailure)
    }
}
