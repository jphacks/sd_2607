package org.mndt.core

import java.io.File
import java.security.MessageDigest

/** Compile alongside the actual bundle-core sources, not a copy of its codec.
 * Public test secret 3 and zero Schnorr auxiliary randomness; never production keys.
 * args: output JSON file, repository root.
 */
fun main(args: Array<String>) {
    require(args.size == 2)
    val root = File(args[1])
    val sources = listOf("SignedPost.kt", "AtRepository.kt", "MeshEngine.kt", "CanonicalCbor.kt")
        .associate { name ->
            val path = "apps/android/bundle-core/src/main/kotlin/org/mndt/core/$name"
            path to hex(MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes()))
        }
    val secret = ByteArray(32).also { it[31] = 3 }
    val pubkey = hex(secp.pubkeyCreate(secret).copyOfRange(1, 33))
    val rawCases = listOf("fb" to "+w", "ffff" to "//8", "0001fe" to "AAH+", "0001feff" to "AAH+/w")
    val cases = listOf(AtRepository.TAG_PREFIX + "commit", AtRepository.EXT_PREFIX + "regression").flatMap { name ->
        rawCases.map { (rawHex, expected) ->
            val raw = unhex(rawHex, rawHex.length / 2)
            val encoded = B64.encode(raw)
            check(encoded == expected && '=' !in encoded)
            check(B64.decode(encoded)!!.contentEquals(raw))
            check(B64.decode(encoded + "=") == null)
            val created = 1792326896L
            val content = "Android Kotlin typed-byte $rawHex"
            val tags = frozenTags(listOf(
                listOf(AtRepository.TAG_PREFIX + "delivery-expires-at", "2030-01-01T00:00:00Z"),
                listOf(name, encoded),
            ))
            val signing = nostrSigningBytes(pubkey, created, 1, tags, content)
            val digest = sha256(signing)
            val event = NostrEvent(hex(digest), pubkey, created, 1, tags, content,
                hex(secp.signSchnorr(digest, secret, ByteArray(32))))
            check(event.verify())
            val payload = EventCodec.encode(event)
            check(EventCodec.decode(payload) == event)
            val typed = CanonicalCbor.decode(payload) as List<*>
            val typedTags = typed[4] as List<*>
            check(((typedTags[1] as List<*>)[1] as ByteArray).contentEquals(raw))
            val bundle = MeshEngine().originate(event, created * 1000, 0).bytes
            mapOf("tag_name" to name, "bytes_hex" to rawHex, "tag_value" to encoded,
                "event_id" to event.id, "event_json" to event.toJson(),
                "signing_json_hex" to hex(signing), "payload_hex" to hex(payload), "bundle_hex" to hex(bundle))
        }
    }
    val output = mapOf("producer" to "Actual Android B64 + EventCodec + MeshEngine; public test key 3; aux=0",
        "source_sha256" to sources, "cases" to cases)
    jsonMapper.writerWithDefaultPrettyPrinter().writeValue(File(args[0]), output)
    println("Generated ${cases.size} actual Android Kotlin typed-byte fixtures: ${args[0]}")
}
