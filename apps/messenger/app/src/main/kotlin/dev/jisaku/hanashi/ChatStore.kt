package dev.jisaku.hanashi

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.util.AtomicFile
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Chat history at rest: one AES-GCM blob keyed in the Android Keystore (the pattern of mesh-android's EncryptedStore). */
class ChatStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "hanashi-chats.aesgcm"))
    private val json = Json { ignoreUnknownKeys = true }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        // A missing key with existing history is corruption, never a silent reset.
        check(!file.baseFile.exists()) { "Encryption key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    /** Throws on an unreadable file rather than starting empty and overwriting the history. */
    fun load(): Chats? {
        if (!file.baseFile.exists()) return null
        val bytes = file.readFully()
        require(bytes.size > 13 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        return json.decodeFromString(Chats.serializer(), cipher.doFinal(bytes, 13, bytes.size - 13).decodeToString())
    }

    fun save(chats: Chats) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(json.encodeToString(Chats.serializer(), chats).encodeToByteArray())
        val output = file.startWrite()
        try { output.write(encrypted); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }

    private companion object { const val ALIAS = "hanashi-chats-v1" }
}
