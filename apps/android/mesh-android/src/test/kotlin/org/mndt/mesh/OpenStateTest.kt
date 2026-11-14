package org.mndt.mesh

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class OpenStateTest {
    private fun seal(key: SecretKey, aad: String, plain: ByteArray): ByteArray {
        val nonce = ByteArray(12) { it.toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad.toByteArray())
        return byteArrayOf(1) + nonce + cipher.doFinal(plain)
    }

    /** Builds before the dev.jisaku.mosimo rename sealed the state under the old AAD; updating must not lose it. */
    @Test fun stateSealedWithLegacyAadStillOpens() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val plain = "{\"v\":1}".toByteArray()
        assertArrayEquals(plain, openState(key, seal(key, "dev.jisaku.mosimo.state.v1", plain)))
        assertArrayEquals(plain, openState(key, seal(key, "dev.oriom.mosimo.state.v1", plain)))
        assertThrows(AEADBadTagException::class.java) { openState(key, seal(key, "other", plain)) }
    }
}
