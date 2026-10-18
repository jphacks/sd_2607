package org.mndt.mesh

import org.junit.Assert.*
import org.junit.Test

class GattLinkTest {
    @Test fun advertRoundTripsAndTieBreakOpensOneLink() {
        val a = "00112233445566aa"; val b = "00112233445566ab"
        assertEquals(GattAdvert(b, false), parseGattAdvert(gattServiceData(b)))
        assertEquals(GattAdvert(b, true), parseGattAdvert(gattServiceData(b, GattLink.FLAG_PERIPHERAL_ONLY) + byteArrayOf(9)))
        assertNull(parseGattAdvert(ByteArray(8)))
        assertNull(parseGattAdvert(null))
        assertTrue(shouldConnect(a, GattAdvert(b, false)))
        assertFalse(shouldConnect(b, GattAdvert(a, false)))
        assertTrue(shouldConnect(b, GattAdvert(a, true)))
        assertFalse(shouldConnect(a, GattAdvert(a, true)))
    }
}
