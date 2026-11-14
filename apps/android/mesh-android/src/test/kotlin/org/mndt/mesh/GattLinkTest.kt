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

    /** Measured from backgrounded iPhone/iPad adverts: the overflow record alone, or after Nearby Info. */
    @Test fun backgroundedIosOverflowRecordIsRecognized() {
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertTrue(overflowAdvert(hex("0100000000000000000000040000000000")))
        assertTrue(overflowAdvert(hex("1005041c3233650100000000000000000000040000000000")))
        assertFalse(overflowAdvert(hex("0100000000000000000000020000000000")))
        assertFalse(overflowAdvert(hex("1005401c38fe4e")))
        assertFalse(overflowAdvert(hex("01000000")))
        assertFalse(overflowAdvert(null))
    }
}
