package org.mndt.mesh

import org.junit.Assert.*
import org.junit.Test

class RestoreClockTest {
    @Test fun onlySameBootMonotonicEvidenceCanMeasureUnknownClockDowntime() {
        val saved = SavedBundle(byteArrayOf(), 123L, 1000L, 7)
        assertEquals(3000L, saved.downtime(7, 4000))
        assertNull(saved.downtime(8, 4000))
        assertNull(saved.downtime(null, 4000))
        assertNull(saved.downtime(7, 999))
        assertNull(SavedBundle(byteArrayOf(), 123).downtime(7, 4000))
        assertNull(saved.copy(bootCount = null).downtime(7, 4000))
    }

    @Test fun blockedDidsAreOptionalAndValidatedOnLoad() {
        assertEquals("state files from before blocking load as empty", emptyList<String>(), readBlockedDids(null))
        val did = "did:plc:" + "a2".repeat(12)
        assertEquals(listOf(did), readBlockedDids(json.readTree("[\"$did\"]")))
        for (bad in listOf("{}", "[\"did:plc:short\"]", "[1]", "[\"did:web:example.com\"]")) {
            assertTrue(bad, runCatching { readBlockedDids(json.readTree(bad)) }.isFailure)
        }
    }
}
