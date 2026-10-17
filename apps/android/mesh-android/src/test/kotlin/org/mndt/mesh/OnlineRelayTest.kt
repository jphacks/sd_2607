package org.mndt.mesh

import org.junit.Assert.*
import org.junit.Test

class OnlineRelayTest {
    @Test fun runningServiceAllowsHiddenActivityWithoutNearby() {
        assertTrue(onlineRelayAllowed(true, false, false, false, "ws://shelter.local", serviceRunning = true))
        assertFalse(onlineRelayAllowed(true, false, false, false, "ws://shelter.local", serviceRunning = false))
        assertFalse(onlineRelayAllowed(false, false, false, false, "ws://shelter.local", serviceRunning = true))
        assertFalse(onlineRelayAllowed(true, false, true, false, "ws://shelter.local", serviceRunning = true))
        assertFalse(onlineRelayAllowed(true, false, false, true, "ws://shelter.local", serviceRunning = true))
        assertFalse(onlineRelayAllowed(true, false, false, false, "", serviceRunning = true))
    }

    @Test fun foregroundOptInDoesNotDependOnNearbyOrInternetValidation() {
        assertTrue(onlineRelayAllowed(true, true, false, false, "ws://shelter.local"))
        assertFalse(onlineRelayAllowed(false, true, false, false, "ws://shelter.local"))
        assertFalse(onlineRelayAllowed(true, false, false, false, "ws://shelter.local"))
        assertFalse(onlineRelayAllowed(true, true, false, false, ""))
    }

    @Test fun fatalPersistenceAndActorOverflowCannotSendEvenWithForegroundOptIn() {
        assertFalse(onlineRelayAllowed(true, true, true, false, "wss://explicit.example"))
        assertFalse(onlineRelayAllowed(true, true, false, true, "wss://explicit.example"))
        val pending = listOf("a", "b")
        assertEquals("b", nextRelayId(pending, "a", mapOf("a" to 30_000), 20_000))
        assertEquals(listOf("a", "b"), pending)
    }
}
