package org.mndt.dm

import com.fasterxml.jackson.databind.ObjectMapper
import org.mndt.core.NostrEvent
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** An unsigned event, ready for MeshCoordinator.publishEvent(kind, tags, content). */
data class Draft(val kind: Int, val tags: List<List<String>>, val content: String)

/** What a mesh event means to us. */
sealed interface Inbound {
    data class Message(val id: String, val from: String, val text: String, val createdAt: Long) : Inbound
    data class Receipt(val of: String, val from: String) : Inbound
    data class Profile(val pubkey: String, val name: String, val createdAt: Long) : Inbound
}

enum class Delivery { SENDING, RELAYED, DELIVERED, EXPIRED }

/**
 * Mosimo DM profile (protocol/0022-direct-messages.md): NIP-04 kind 4 messages, kind 4 delivery receipts carrying an `e` tag,
 * kind 0 display names. [secret] is the ECDH x coordinate with a peer (MeshCoordinator.sharedSecret).
 */
class DmCodec(private val me: String, private val secret: (String) -> ByteArray) {
    fun message(to: String, text: String): Draft {
        require(text.isNotBlank() && text.toByteArray().size <= MAX_TEXT_BYTES)
        return Draft(KIND_DM, listOf(listOf("p", to), TOPIC), Nip04.encrypt(secret(to), text))
    }
    fun receipt(to: String, of: String) = Draft(KIND_DM, listOf(listOf("p", to), listOf("e", of), TOPIC), Nip04.encrypt(secret(to), ""))
    fun profile(name: String) = Draft(KIND_PROFILE, listOf(TOPIC), json.writeValueAsString(mapOf("name" to name.trim().take(MAX_NAME))))

    /** Null for anything not addressed to us, our own events, and anything malformed. */
    fun read(event: NostrEvent): Inbound? = runCatching {
        if (event.pubkey == me) return null
        when (event.kind) {
            KIND_PROFILE -> json.readTree(event.content)["name"]?.textValue()?.trim()?.take(MAX_NAME)?.takeIf { it.isNotEmpty() }
                ?.let { Inbound.Profile(event.pubkey, it, event.createdAt) }
            KIND_DM -> {
                if (event.tags.filter { it.firstOrNull() == "p" }.singleOrNull()?.getOrNull(1) != me) return null
                val text = Nip04.decrypt(secret(event.pubkey), event.content)
                event.tags.firstOrNull { it.firstOrNull() == "e" }?.getOrNull(1)?.let { Inbound.Receipt(it, event.pubkey) }
                    ?: Inbound.Message(event.id, event.pubkey, text, event.createdAt)
            }
            else -> null
        }
    }.getOrNull()

    companion object {
        const val KIND_PROFILE = 0
        const val KIND_DM = 4
        /** Matches the mesh's default delivery deadline (Author.signEvent). */
        const val LIFETIME_SECONDS = 86400L
        const val MAX_TEXT_BYTES = 2000
        const val MAX_NAME = 40
        private val TOPIC = listOf("t", "mosimo.jisaku.dev")
        private val json = ObjectMapper()

        fun delivery(createdAt: Long, nowSeconds: Long, relayed: Boolean, delivered: Boolean) = when {
            delivered -> Delivery.DELIVERED
            nowSeconds >= createdAt + LIFETIME_SECONDS -> Delivery.EXPIRED
            relayed -> Delivery.RELAYED
            else -> Delivery.SENDING
        }
    }
}

/**
 * NIP-04: AES-256-CBC keyed by the ECDH x coordinate, "base64(ciphertext)?iv=base64(iv)".
 * ponytail: NIP-04 leaks who talks to whom (`p` tag) and is deprecated; move to NIP-17 gift wraps if metadata matters.
 */
object Nip04 {
    private val random = SecureRandom()
    fun encrypt(key: ByteArray, text: String, iv: ByteArray = ByteArray(16).also(random::nextBytes)): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
        val b64 = Base64.getEncoder()
        return b64.encodeToString(cipher.doFinal(text.toByteArray())) + "?iv=" + b64.encodeToString(iv)
    }
    fun decrypt(key: ByteArray, content: String): String {
        val parts = content.split("?iv=")
        require(parts.size == 2)
        val b64 = Base64.getDecoder()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(b64.decode(parts[1]))) }
        return String(cipher.doFinal(b64.decode(parts[0])))
    }
}
