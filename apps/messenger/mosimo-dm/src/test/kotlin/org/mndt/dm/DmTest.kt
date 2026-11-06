package org.mndt.dm

import org.junit.Assert.*
import org.junit.Test
import org.mndt.core.Author
import org.mndt.core.SecretKeys

class DmTest {
    private fun key(n: Int) = ByteArray(32).also { it[31] = n.toByte() }
    private val alice = Author(SecretKeys(key(3), key(4), key(5)), "https://pds.invalid")
    private val bob = Author(SecretKeys(key(6), key(7), key(8)), "https://pds.invalid")
    private val now = 1792195200000L
    private fun Author.codec() = DmCodec(nostrPubkey, ::sharedSecret)
    private fun Author.sign(d: Draft) = signEvent(d.kind, d.tags, d.content, now)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** Vector from Node's OpenSSL (ECDH secp256k1 + aes-256-cbc), independent of this code. */
    @Test fun nip04MatchesAnIndependentImplementation() {
        val shared = alice.sharedSecret(bob.nostrPubkey)
        assertEquals("5601570cb47f238d2b0286db4a990fa0f3ba28d1a319f5e7cf55c2a2444da7cc", hex(shared))
        val content = "Otce5/hmIXRvVwMDbCh/jgUwn07zWutL08mv3x7w/6k=?iv=EREREREREREREREREREREQ=="
        assertEquals(content, Nip04.encrypt(shared, "こんにちは、Hanashi", ByteArray(16) { 0x11 }))
        assertEquals("こんにちは、Hanashi", Nip04.decrypt(bob.sharedSecret(alice.nostrPubkey), content))
    }

    @Test fun messageReceiptAndProfileRoundTrip() {
        val dm = alice.sign(alice.codec().message(bob.nostrPubkey, "やあ"))
        assertEquals(Inbound.Message(dm.id, alice.nostrPubkey, "やあ", dm.createdAt), bob.codec().read(dm))
        assertNull("own events are not inbound", alice.codec().read(dm))
        val receipt = bob.sign(bob.codec().receipt(alice.nostrPubkey, dm.id))
        assertEquals(Inbound.Receipt(dm.id, bob.nostrPubkey), alice.codec().read(receipt))
        val profile = alice.sign(alice.codec().profile("  ありす "))
        assertEquals(Inbound.Profile(alice.nostrPubkey, "ありす", profile.createdAt), bob.codec().read(profile))
    }

    @Test fun othersDmsAreIgnored() {
        val carol = Author(SecretKeys(key(9), key(10), key(11)), "https://pds.invalid")
        val dm = alice.sign(alice.codec().message(carol.nostrPubkey, "秘密"))
        assertNull(bob.codec().read(dm))
    }

    @Test fun deliveryStatus() {
        val t = 1000L
        assertEquals(Delivery.SENDING, DmCodec.delivery(t, t + 1, relayed = false, delivered = false))
        assertEquals(Delivery.RELAYED, DmCodec.delivery(t, t + 1, relayed = true, delivered = false))
        assertEquals(Delivery.DELIVERED, DmCodec.delivery(t, t + 90000, relayed = true, delivered = true))
        assertEquals(Delivery.EXPIRED, DmCodec.delivery(t, t + 86400, relayed = true, delivered = false))
    }
}
