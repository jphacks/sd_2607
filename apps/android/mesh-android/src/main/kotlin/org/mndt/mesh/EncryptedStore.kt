package org.mndt.mesh

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.mndt.core.RepositoryState
import org.mndt.core.MeshPolicy
import org.mndt.core.SecretKeys
import org.mndt.core.StoredBundle
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class SavedBundle(val bytes: ByteArray, val receivedAtMillis: Long,
    val savedElapsedMillis: Long? = null, val bootCount: Int? = null) {
    fun downtime(currentBoot: Int?, elapsed: Long): Long? =
        if (bootCount != null && bootCount == currentBoot && savedElapsedMillis != null && elapsed >= savedElapsedMillis)
            elapsed - savedElapsedMillis else null
}
internal data class DiskState(
    val keys: SecretKeys,
    val repository: RepositoryState = RepositoryState(),
    val bundles: List<SavedBundle> = emptyList(),
    val relay: String = "",
    val relayAccepted: Map<String, Set<String>> = emptyMap(),
    // Historical matching-genesis observation only; never restores current authority/PDS acceptance.
    val plcRegistered: Boolean = false,
    val pdsEndpoint: String = "https://pds.invalid",
    val onlineEnabled: Boolean = false
)

/** One authenticated ciphertext; keys, repository head and outbox commit together. */
internal class EncryptedStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "mesh-state.aesgcm"))
    private val aad = "org.mndt.mesh.state.v1".toByteArray()
    private val base64 = Base64.getEncoder()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("mndt-state-v1", null) as? SecretKey)?.let { return it }
        // A missing key with existing state is corruption, never a silent identity reset.
        check(!file.baseFile.exists()) { "Encryption key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("mndt-state-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    fun load(): DiskState? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val encrypted = file.openRead().use { input ->
            input.readBounded(MAX_DISK_BYTES).also { require(it.size in 29..MAX_DISK_BYTES) }
        }
        require(encrypted[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(1, 13)))
        cipher.updateAAD(aad)
        val plain = cipher.doFinal(encrypted, 13, encrypted.size - 13)
        try {
            val node = json.readTree(plain)
            require(node.isObject && node.path("v").asInt() == 1)
            fun secret(name: String) = Base64.getDecoder().decode(node.path(name).textValue()).also { require(it.size == 32) }
            val keys = SecretKeys(secret("nostr"), secret("atproto"), secret("plc"))
            val recordsNode = node.path("records")
            require(recordsNode.isObject && recordsNode.size() <= 10_000)
            val records = recordsNode.properties().associate { require(it.value.isTextual); it.key to it.value.textValue() }
            val bundles = node.path("bundles")
            require(bundles.isArray && bundles.size() <= 128)
            val saved = bundles.map {
                val bytes = Base64.getDecoder().decode(it.path("bytes").textValue())
                require(bytes.size in 1..MeshPolicy.MAX_SAVED_BUNDLE_BYTES && it.path("received").isIntegralNumber)
                val elapsed = it["elapsed"]?.takeUnless { n -> n.isNull }?.also { n -> require(n.isIntegralNumber && n.canConvertToLong() && n.longValue() >= 0) }?.longValue()
                val boot = it["boot"]?.takeUnless { n -> n.isNull }?.also { n -> require(n.isIntegralNumber && n.canConvertToInt() && n.intValue() >= 0) }?.intValue()
                SavedBundle(bytes, it["received"].longValue(), elapsed, boot)
            }
            val accepted = node.path("accepted")
            require(accepted.isObject && accepted.size() <= 8)
            val acks = accepted.properties().associate { entry ->
                require(entry.value.isArray && entry.value.size() <= 128)
                entry.key to entry.value.map { require(it.isTextual && it.textValue().matches(Regex("[0-9a-f]{64}"))); it.textValue() }.toSet()
            }
            return DiskState(keys, RepositoryState(records, node["previous"]?.takeUnless { it.isNull }?.textValue(),
                node.path("lastRev").longValue()), saved, node.path("relay").asText(""), acks, node.path("plcRegistered").asBoolean(false),
                node.path("pdsEndpoint").asText("https://pds.invalid"), node.path("onlineEnabled").asBoolean(node.path("relay").asText("").isNotEmpty()))
        } finally { plain.fill(0) }
    }
    fun save(state: DiskState, entries: List<StoredBundle>, bootCount: Int? = null) {
        val node = json.createObjectNode().put("v", 1)
            .put("nostr", base64.encodeToString(state.keys.nostr))
            .put("atproto", base64.encodeToString(state.keys.atproto))
            .put("plc", base64.encodeToString(state.keys.plc))
            .put("relay", state.relay).put("onlineEnabled", state.onlineEnabled).put("plcRegistered", state.plcRegistered).put("pdsEndpoint", state.pdsEndpoint)
            .put("previous", state.repository.previousCommit).put("lastRev", state.repository.lastRevMicros)
        node.putObject("records").also { records -> state.repository.records.forEach { (k, v) -> records.put(k, v) } }
        node.putArray("bundles").also { a -> entries.forEach {
            a.addObject().put("bytes", base64.encodeToString(it.bytes)).put("received", it.receivedAtMillis)
                .put("elapsed", it.receivedElapsedMillis).put("boot", bootCount)
        } }
        node.putObject("accepted").also { a -> state.relayAccepted.forEach { (url, ids) -> a.putArray(url).also { list -> ids.forEach(list::add) } } }
        val plain = json.writeValueAsBytes(node)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        val encrypted = try { byteArrayOf(1) + cipher.iv + cipher.doFinal(plain) } finally { plain.fill(0) }
        require(encrypted.size <= MAX_DISK_BYTES)
        val output = file.startWrite()
        try { output.write(encrypted); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
    companion object { private const val MAX_DISK_BYTES = 16_000_000 }
}
