package org.mndt.mesh

import org.junit.Assert.*
import org.junit.Test

class OnlineRelayTest {
    @Test fun runningServiceOrVisibleScreenAllowsRelayIo() {
        // (enabled, foreground, fatal, hasRelay, serviceRunning)
        assertTrue(onlineRelayAllowed(true, false, false, true, serviceRunning = true))
        assertTrue(onlineRelayAllowed(true, true, false, true))
        assertFalse(onlineRelayAllowed(true, false, false, true))
        assertFalse(onlineRelayAllowed(false, true, false, true, serviceRunning = true))
        assertFalse(onlineRelayAllowed(true, true, true, true, serviceRunning = true))
        assertFalse(onlineRelayAllowed(true, true, false, false, serviceRunning = true))
    }

    @Test fun failedFirstEventDoesNotStarveLaterOnes() {
        val pending = listOf("a", "b")
        assertEquals("b", nextRelayId(pending, "a", mapOf("a" to 30_000), 20_000))
        assertEquals(listOf("a", "b"), pending)
    }
}
