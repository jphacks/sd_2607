package org.mndt.mesh

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

internal enum class PlcCurrentState { UNKNOWN, CHECKED, CHANGED, TOMBSTONED }
internal data class PlcObservation(val genesisConfirmed: Boolean = false,
    val current: PlcCurrentState = PlcCurrentState.UNKNOWN, val checkedAtMillis: Long? = null)

/** Observes the configured Directory, not an independently audited history or PDS acceptance.
 * /log excludes nullified operations; /data is current PLC state, not a DID Document.
 * A positive observation is time-scoped and must never be restored as current authority.
 */
internal class PlcDirectory(private val http: OkHttpClient) {
    private val parser = ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
            .maxStringLength(8192).maxNumberLength(20).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private val stateFields = listOf("rotationKeys", "verificationMethods", "alsoKnownAs", "services")

    fun check(directory: HttpUrl, did: String, genesisJson: String, previouslyConfirmed: Boolean,
              allowRegistration: Boolean = false): PlcObservation {
        var confirmed = previouslyConfirmed
        return try {
            require(did.matches(Regex("did:plc:[a-z2-7]{24}")))
            val expected = parse(genesisJson.toByteArray(Charsets.UTF_8))
            operation(expected, genesis = true)
            val target = directory.newBuilder().addPathSegment(did).build()
            val logUrl = target.newBuilder().addPathSegment("log").build()
            var response = get(logUrl)
            // Never POST over an existing/malformed registration or after historical confirmation.
            if (response.first == 404 && !previouslyConfirmed && allowRegistration) {
                // Lost/duplicate submission responses do not decide registration or authority.
                runCatching { http.newCall(Request.Builder().url(target)
                    .post(genesisJson.toRequestBody("application/json".toMediaType())).build()).execute().close() }
                response = get(logUrl)
            }
            require(response.first in 200..299)
            val log = checkNotNull(response.second)
            // ponytail: bounded whole-log observation; >128 operations stays unknown, not truncated.
            require(log.isArray && log.size() in 1..128 && log[0] == expected)
            confirmed = true
            log.forEachIndexed { index, op ->
                operation(op, genesis = index == 0)
                require(op["type"].textValue() != "plc_tombstone" || index == log.size() - 1)
            }
            val latest = log[log.size() - 1]
            val current = if (latest["type"].textValue() == "plc_tombstone") PlcCurrentState.TOMBSTONED else {
                val resolved = get(target.newBuilder().addPathSegment("data").build())
                require(resolved.first in 200..299)
                val data = checkNotNull(resolved.second)
                require(data.isObject && data["did"]?.textValue() == did)
                state(data)
                // Different reads may straddle an update. Do not bless a mixed snapshot.
                require(stateFields.all { data[it] == latest[it] })
                if (listOf("rotationKeys", "verificationMethods").all { data[it] == expected[it] } &&
                    data["services"]["atproto_pds"] == expected["services"]["atproto_pds"])
                    PlcCurrentState.CHECKED else PlcCurrentState.CHANGED
            }
            PlcObservation(confirmed, current, System.currentTimeMillis())
        } catch (_: Exception) { PlcObservation(confirmed) }
    }

    private fun get(url: HttpUrl): Pair<Int, JsonNode?> = http.newCall(Request.Builder().url(url)
        .header("Cache-Control", "no-cache").build()).execute().use { response ->
        response.code to if (response.isSuccessful)
            parse(checkNotNull(response.body).byteStream().readBounded(262144)) else null
    }

    private fun parse(bytes: ByteArray): JsonNode {
        require(bytes.size in 1..262144)
        parser.factory.createParser(bytes).use { tokens ->
            var count = 0
            while (tokens.nextToken() != null) require(++count <= 16384)
        }
        return checkNotNull(parser.readTree(bytes))
    }

    private fun operation(op: JsonNode, genesis: Boolean) {
        require(op.isObject && op["sig"]?.isTextual == true && op["sig"].textValue().isNotEmpty())
        require(if (genesis) op["prev"]?.isNull == true else op["prev"]?.isTextual == true && op["prev"].textValue().isNotEmpty())
        when (op["type"]?.textValue()) {
            "plc_operation" -> {
                require(op.fieldNames().asSequence().toSet() == (stateFields + listOf("type", "prev", "sig")).toSet())
                state(op)
            }
            "plc_tombstone" -> require(!genesis && op.fieldNames().asSequence().toSet() == setOf("type", "prev", "sig"))
            else -> error("Unsupported PLC operation")
        }
    }

    private fun state(node: JsonNode) {
        val keys = node.path("rotationKeys")
        require(keys.isArray && keys.size() in 1..5 && keys.all { it.isTextual && it.textValue().startsWith("did:key:") })
        require(keys.toList().distinct().size == keys.size())
        val methods = node.path("verificationMethods")
        require(methods.isObject && methods.size() <= 10 && methods.all { it.isTextual && it.textValue().startsWith("did:key:") })
        val aliases = node.path("alsoKnownAs")
        require(aliases.isArray && aliases.all(JsonNode::isTextual))
        val services = node.path("services")
        require(services.isObject && services.all { it.isObject && it["type"]?.isTextual == true && it["endpoint"]?.isTextual == true })
    }
}
