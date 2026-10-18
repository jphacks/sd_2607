package org.mndt.core

import java.io.File
import io.nodle.dtn.bpv7.*

/** Public test keys 3/4/5 only. No PLC registration or network access. */
fun main(args: Array<String>) {
    require(args.size == 3) { "generate|receive fixture.json bundle-directory" }
    val fixtureFile = File(args[1])
    val directory = File(args[2])
    if (args[0] == "receive") {
        val fixture = jsonMapper.readTree(fixtureFile)
        val receiver = MeshEngine()
        fixture["posts"].forEachIndexed { index, post ->
            val bytes = File(directory, "forwarded-$index.bin").readBytes()
            val received = checkNotNull(receiver.accept(bytes, fixture["now_millis"].longValue() + 2000, 2000))
            val original = NostrEvent.fromJson(post["event_json"].textValue())
            check(received.hop == 2)
            check(received.event == original && received.event.verify())
            check(hex(cborUnmarshalBundle(bytes).getPayloadBlockData().buffer) == post["payload_hex"].textValue())
            check(hex(EventCodec.encode(received.event)) == post["payload_hex"].textValue())
            val restored = AtRepository.restore(received.event, unhex(fixture["atproto_pubkey_hex"].textValue(), 33))
            check(restored.recordCid == post["record_cid"].textValue())
            check(restored.commitCid == post["commit_cid"].textValue())
            (post as com.fasterxml.jackson.databind.node.ObjectNode).put("received_event_json", received.event.toJson())
        }
        check(receiver.entries().size == 2)
        jsonMapper.writerWithDefaultPrettyPrinter().writeValue(fixtureFile, fixture)
        println("PASS Kotlin receive: 2 ESP32-forwarded events, hop 2, exact signatures/payloads/record and commit CIDs")
        return
    }
    require(args[0] == "generate")
    fun key(n: Int) = ByteArray(32).also { it[31] = n.toByte() }
    val author = Author(SecretKeys(key(3), key(4), key(5)), "https://integration.invalid")
    val now = 1792240496120L
    var state = RepositoryState(clockId = 7)
    val posts = (0..1).map { index ->
        val extensions = if (index == 0) emptyList() else listOf(
            mapOf("\$type" to "org.example.integration.defs#opaque", "bytes" to byteArrayOf(-5, -1),
                "link" to CidLink(state.records.values.single()), "nested" to mapOf("nullValue" to null,
                    "values" to listOf(true, 42L, "か\u3099\r\n🏠"))),
            mapOf("\$type" to "org.example.integration.defs#other", "empty" to emptyList<Any>()),
            mapOf("\$type" to "org.example.integration.defs#opaque", "future" to false)
        )
        val created = author.createPost("統合チェック $index: か\u3099・が 🏠\r\n\"quote\" \\ /\t\u0001", now + index,
            previous = state, extensions = extensions)
        val change = AtRepository.restore(created.event, author.atprotoPubkey)
        check(change.applyFromCommit == state.previousCommit)
        check(created.repository.records[change.path] == change.recordCid)
        check(created.repository.previousCommit == change.commitCid)
        state = created.repository
        val engine = MeshEngine()
        engine.originate(created.event, now + index, 0)
        // Model the source Android's first transmission: ESP32 receives hop 1.
        val bundle = checkNotNull(engine.forward(created.event.id, now + index, 17))
        val commit = CanonicalCbor.decode(change.commitBytes) as Map<*, *>
        mapOf("event_json" to created.event.toJson(), "payload_hex" to hex(EventCodec.encode(created.event)),
            "bundle_hex" to hex(bundle), "record_hex" to hex(change.recordBytes), "commit_hex" to hex(change.commitBytes),
            "record_cid" to change.recordCid, "commit_cid" to change.commitCid,
            "mst_root" to (commit["data"] as CidLink).value, "path" to change.path,
            "base" to change.applyFromCommit, "entries" to state.records.map { mapOf("key" to it.key, "value" to it.value) })
    }
    val signingKeyDid = jsonMapper.readTree(author.initialOperationJson)["verificationMethods"]["atproto"].textValue()
    jsonMapper.writerWithDefaultPrettyPrinter().writeValue(fixtureFile, mapOf("did" to author.did,
        "atproto_pubkey_hex" to hex(author.atprotoPubkey), "signing_key_did" to signingKeyDid,
        "pds_endpoint" to "https://integration.invalid", "now_millis" to now, "posts" to posts))
    println("Generated 2 real Author.createPost fixtures: initial + chained commit, ordered unknown extensions")
}
