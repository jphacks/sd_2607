package org.mndt.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.util.Base64

class SignatureTest {
    private fun key(n: Int) = ByteArray(32).also { it[31] = n.toByte() }
    private fun author() = Author(SecretKeys(key(3), key(4), key(5)), "https://pds.invalid")
    private val now = 1792195200000L
    private fun envelope(e: NostrEvent): com.fasterxml.jackson.databind.JsonNode {
        val change = AtRepository.restore(e)
        val commit = CanonicalCbor.decode(change.commitBytes) as Map<*, *>
        return jsonMapper.valueToTree(mapOf("version" to 1, "did" to commit["did"], "path" to change.path,
            "atprotoPubkey" to change.atprotoPubkey, "recordCid" to change.recordCid, "commitCid" to change.commitCid,
            "applyFromCommit" to change.applyFromCommit, "recordCbor" to Base64.getEncoder().encodeToString(change.recordBytes),
            "commitCbor" to Base64.getEncoder().encodeToString(change.commitBytes)))
    }
    private fun resigned(e: NostrEvent): NostrEvent {
        val hash = sha256(e.signingBytes())
        return e.copy(id = hex(hash), sig = hex(secp.signSchnorr(hash, key(3), ByteArray(32))))
    }
    private fun withEnvelope(e: NostrEvent, change: (MutableMap<String, Any?>) -> Unit): NostrEvent {
        val node = envelope(e)
        val map = jsonMapper.convertValue(node, Map::class.java).entries.associate { it.key as String to it.value }.toMutableMap()
        change(map)
        val payload = Base64.getEncoder().encodeToString(utf8(jsonMapper.writeValueAsString(map)))
        val tags = e.tags.filterNot { it[0].startsWith(AtRepository.TAG_PREFIX) || it[0] == "atproto" }
            .map { if (it[0] == "t") listOf("t", "mndt-experimental-v1") else it }
        return resigned(e.copy(tags = tags + listOf(listOf("atproto", "1", payload))))
    }

    private fun withRecord(e: NostrEvent, record: Map<String, Any?>, extraTags: List<List<String>>): NostrEvent {
        val original = AtRepository.restore(e)
        val recordBytes = CanonicalCbor.encode(record)
        val commit = CanonicalCbor.decode(original.commitBytes) as Map<*, *>
        val unsigned = commit.filterKeys { it != "sig" } + ("data" to CidLink(AtRepository.mstRoot(mapOf(original.path to cid(recordBytes)))))
        val signed = CanonicalCbor.encode(unsigned + ("sig" to secp.sign(sha256(CanonicalCbor.encode(unsigned)), key(4))))
        val tags = e.tags.map {
            when (it[0]) {
                AtRepository.TAG_PREFIX + "recordCid" -> listOf(it[0], cid(recordBytes))
                AtRepository.TAG_PREFIX + "commit" -> listOf(it[0], Base64.getEncoder().encodeToString(signed))
                else -> it
            }
        }
        return resigned(e.copy(tags = tags + extraTags))
    }

    @Test fun unknownOpenUnionExtensionRestoresWithoutLosingData() {
        val event = author().createPost("拡張未対応でも読める本文", now).event
        val type = "org.example.audit.defs#unknown"
        val link = cid(byteArrayOf(1, 2, 3))
        val extension = mapOf("\$type" to type, "extra" to listOf(null, false, emptyList<Any>(), 9007199254740993L),
            "bytes" to byteArrayOf(0, -1), "ref" to CidLink(link))
        val json = jsonMapper.writeValueAsString(extension + mapOf("bytes" to mapOf("\$bytes" to "AP8="), "ref" to mapOf("\$link" to link)))
        val record = (CanonicalCbor.decode(AtRepository.restore(event).recordBytes) as Map<*, *>).entries
            .associate { it.key as String to it.value } + ("extensions" to listOf(extension))
        val signed = withRecord(event, record, listOf(listOf(AtRepository.TAG_PREFIX + "extension:" + type, "0", json)))
        assertTrue(signed.verify())
        assertTrue("Unknown extension must not invalidate repository restoration", AtRepository.verifyEnvelope(signed))
        assertArrayEquals(CanonicalCbor.encode(record), AtRepository.restore(signed).recordBytes)
        val mesh = MeshEngine()
        mesh.originate(signed, now, 0)
        assertEquals(signed, MeshEngine().accept(mesh.forward(signed.id, now, 0)!!, now, 0)!!.event)
    }

    @Test fun authorCreatesIndependentExtensionTags() {
        val first = mapOf("\$type" to "org.example.audit.defs#origin", "latitude" to "35.0", "future" to null)
        val second = mapOf("\$type" to "org.example.audit.defs#area", "regions" to emptyList<Any>())
        val created = author().createPost("本文", now, extensions = listOf(first, second, first), deliveryExpiresAtSeconds = now / 1000 + 3600)
        val event = created.event
        val change = AtRepository.restore(event)
        val record = CanonicalCbor.decode(change.recordBytes) as Map<*, *>
        assertArrayEquals(CanonicalCbor.encode(listOf(first, second, first)), CanonicalCbor.encode(record["extensions"]))
        val tags = event.tags.filter { it[0].startsWith(AtRepository.TAG_PREFIX + "extension:") }
        assertEquals(listOf("0", "1", "2"), tags.map { it[1] })
        tags.forEach { assertTrue(it[2].startsWith("{")); assertTrue(strictJson(it[2]).has("\$type")) }
        assertEquals(listOf("mndt-exp", (now / 1000 + 3600).toString()), event.tags.single { it[0] == "mndt-exp" })
        val commit = CanonicalCbor.decode(change.commitBytes) as Map<*, *>
        assertEquals(CidLink(AtRepository.mstRoot(created.repository.records)), commit["data"])
    }

    @Test fun unknownRecordFieldsArePreserved() {
        val event = author().createPost("本文", now).event
        val record = (CanonicalCbor.decode(AtRepository.restore(event).recordBytes) as Map<*, *>).entries
            .associate { it.key as String to it.value } + ("futureField" to mapOf("nullable" to null, "nested" to listOf(1, 2)))
        val tag = listOf(AtRepository.TAG_PREFIX + "field:futureField", "{\"nullable\":null,\"nested\":[1,2]}")
        val signed = withRecord(event, record, listOf(tag))
        assertTrue("Unknown record fields must not be dropped", AtRepository.verifyEnvelope(signed))
        assertArrayEquals(CanonicalCbor.encode(record), AtRepository.restore(signed).recordBytes)
        assertFalse(AtRepository.verifyEnvelope(resigned(signed.copy(tags = signed.tags + listOf(tag)))))
    }

    @Test fun rawCidInUnknownExtensionIsPreserved() {
        val link = CidLink("b" + base32(byteArrayOf(1, 0x55, 0x12, 0x20) + sha256(byteArrayOf(7))))
        val extension = mapOf("\$type" to "org.example.audit.defs#unknown", "ref" to link)
        val created = runCatching { author().createPost("本文", now, extensions = listOf(extension)) }
        assertTrue("Standard raw CID link must be supported: ${created.exceptionOrNull()?.message}", created.isSuccess)
        val record = CanonicalCbor.decode(AtRepository.restore(created.getOrThrow().event).recordBytes) as Map<*, *>
        assertArrayEquals(CanonicalCbor.encode(listOf(extension)), CanonicalCbor.encode(record["extensions"]))
        assertThrows(Exception::class.java) { AtRepository.mstRoot(mapOf("xxx.disaster.post/key" to link.value)) }
    }

    @Test fun extraNostrTopicsDoNotBlockRepositoryRestoration() {
        val original = author().createPost("本文", now).event
        val event = resigned(original.copy(tags = original.tags + listOf(listOf("t", "another-topic"), listOf("unknown", "keep"))))
        assertTrue("Protocol identification must not reject other topic tags", AtRepository.verifyEnvelope(event))
        assertArrayEquals(AtRepository.restore(original).recordBytes, AtRepository.restore(event).recordBytes)
    }

    @Test fun legacySignedEventAndRepositoryHeadRemainUnchanged() {
        val fixture = javaClass.getResourceAsStream("/legacy-v1-post.json")!!.use { strictJson(String(it.readBytes(), Charsets.UTF_8)) }
        val event = NostrEvent.fromJson(jsonMapper.writeValueAsString(fixture["event"]))
        assertTrue(AtRepository.verifyEnvelope(event))
        val oldEnvelope = strictJson(String(Base64.getDecoder().decode(event.tags.single { it[0] == "atproto" }[2]), Charsets.UTF_8))
        assertArrayEquals(Base64.getDecoder().decode(oldEnvelope["recordCbor"].string()), AtRepository.restore(event).recordBytes)
        assertArrayEquals(Base64.getDecoder().decode(oldEnvelope["commitCbor"].string()), AtRepository.restore(event).commitBytes)
        val stored = MeshEngine().originate(event, now, 0)
        val restarted = MeshEngine()
        assertEquals(event, restarted.restore(stored.bytes, now, now + 1000, 0)!!.event)
        val repository = fixture["repository"]
        val previous = RepositoryState(repository["records"].properties().associate { it.key to it.value.string() },
            repository["previousCommit"].string(), repository["lastRevMicros"].integer())
        val next = author().createPost("新形式で追記", now + 1000, previous)
        assertEquals(previous.previousCommit, AtRepository.restore(next.event).applyFromCommit)
        assertTrue(next.repository.records.entries.containsAll(previous.records.entries))
        assertEquals(event, EventCodec.decode(EventCodec.encode(event)))
    }

    @Test fun invalidBindingDataIsRejectedWithoutDroppingUnknownFields() {
        val extension = mapOf("\$type" to "org.example.audit.defs#unknown", "value" to null)
        val event = author().createPost("本文", now, extensions = listOf(extension)).event
        val tag = event.tags.single { it[0].startsWith(AtRepository.TAG_PREFIX + "extension:") }
        val badTags = listOf(
            listOf(tag[0], "1", tag[2]), listOf(tag[0], "00", tag[2]),
            listOf(tag[0] + "Other", "0", tag[2]),
            listOf(tag[0], "0", "{\"\$type\":\"org.example.audit.defs#unknown\",\"value\":0.5}"),
            listOf(tag[0], "0", "{\"\$type\":\"org.example.audit.defs#unknown\",\"value\":1,\"value\":2}"),
            listOf(tag[0], "0", "{\"\$type\":\"org.example.audit.defs#unknown\",\"value\":9223372036854775808}"),
        )
        for (bad in badTags) assertFalse(AtRepository.verifyEnvelope(resigned(event.copy(tags = event.tags.map { if (it === tag) bad else it }))))
        assertFalse(AtRepository.verifyEnvelope(resigned(event.copy(tags = event.tags + listOf(tag)))))
        assertFalse(AtRepository.verifyEnvelope(resigned(event.copy(tags = event.tags.filterNot { it[0] == AtRepository.TAG_PREFIX + "base" }))))
        assertFalse(AtRepository.verifyEnvelope(resigned(event.copy(tags = event.tags + listOf(event.tags.single { it[0] == AtRepository.TAG_PREFIX + "commit" })))))
        assertFalse(AtRepository.verifyEnvelope(resigned(event.copy(tags = event.tags.map { if (it[0] == "mndt-exp") listOf("mndt-exp", (now / 1000 + 1).toString()) else it }))))
        assertThrows(Exception::class.java) { author().createPost("本文", now, deliveryExpiresAtSeconds = now / 1000) }
        assertThrows(Exception::class.java) { author().createPost("本文", now, extensions = listOf(mapOf("\$type" to "not-a-type"))) }
        for (bad in listOf("{\"\$bytes\":true}", "{\"\$bytes\":\"__8\"}", "{\"\$bytes\":\"AB==\"}", "{\"\$link\":\"not-a-cid\"}", "{\"\$bytes\":\"AQ==\",\"extra\":1}")) {
            assertThrows(Exception::class.java) { AtDataJson.decode(bad) }
        }
        assertArrayEquals(byteArrayOf(0, -1), AtDataJson.decode("{\"\$bytes\":\"AP8\"}") as ByteArray)
        val extremes = listOf(Long.MIN_VALUE, Long.MAX_VALUE)
        assertEquals(extremes, AtDataJson.decode(AtDataJson.encode(extremes)))
    }

    @Test fun bip340OfficialVectorZero() {
        // bitcoin/bips bip-0340/test-vectors.csv, index 0 (external expected values).
        val pub = "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"
        val sig = "e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca821525f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0"
        assertEquals(pub, hex(secp.pubkeyCreate(key(3)).copyOfRange(1, 33)))
        assertEquals(sig, hex(secp.signSchnorr(ByteArray(32), key(3), ByteArray(32))))
        assertTrue(secp.verifySchnorr(unhex(sig, 64), ByteArray(32), unhex(pub, 32)))
        assertFalse(secp.verifySchnorr(unhex(sig, 64), ByteArray(32).also { it[0] = 1 }, unhex(pub, 32)))
    }

    @Test fun nip01MatchesNostrToolsUnicodeAndEscapeVector() {
        // Generated and verified independently with nostr-tools/pure, not this encoder.
        val event = NostrEvent("8ccfd0d228af4d6011d9ee15d1829f9d5e8e8708934a078cf120e3f4df242e66",
            "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9", 1760000000, 1,
            listOf(listOf("x", "順序\\\"\n", "\b\t\u000c\r\u0001"), listOf("t", "mndt-experimental-v1")),
            "日本語😀 / \" \\ \n\r\t\b\u000c\u0000\u001f\u2028",
            "58a034351b44064679259d7bb9fc490f9ab0f23c59c66a40ee2da510d76409e85f237726d4c4b48e3502303a45c0998cacaa5b7c1695fcd9a86984f0937fbe99")
        assertTrue(event.verify())
        assertEquals(event, NostrEvent.fromJson(event.toJson()))
        assertEquals(event, EventCodec.decode(EventCodec.encode(event)))
        assertFalse(event.copy(tags = event.tags.reversed()).verify())
        assertFalse(event.copy(content = event.content + "!").verify())
    }

    @Test fun strictJsonRejectsAmbiguityAndUntrustedIds() {
        val json = author().createPost("本文", now).event.toJson()
        assertThrows(Exception::class.java) { NostrEvent.fromJson(json.replaceFirst("{", "{\"extra\":1,")) }
        assertThrows(Exception::class.java) { NostrEvent.fromJson(json.replaceFirst("{", "{\"kind\":1,")) }
        assertThrows(Exception::class.java) { NostrEvent.fromJson(json.replace("\"kind\":1", "\"kind\":1.0")) }
        assertThrows(Exception::class.java) { NostrEvent.fromJson(json + " {}") }
        assertThrows(Exception::class.java) { NostrEvent.fromJson(json.replace("本文", "改変")) }
        assertThrows(Exception::class.java) { NostrEvent.fromJson(json.replaceFirst(Regex("\"id\":\"[a-f0-9]+\""), "\"id\":\"${"0".repeat(64)}\"")) }
    }

    @Test fun cborRejectsNonCanonicalFloatsDuplicatesAndResourceAttacks() {
        for (bad in listOf("1801", "9fff", "f90000", "a2616101616102", "0100", "5b7fffffffffffffff", "61ff", "d82a4100")) {
            assertThrows(bad, Exception::class.java) { CanonicalCbor.decode(unhex(bad, bad.length / 2)) }
        }
        assertThrows(Exception::class.java) { CanonicalCbor.decode(ByteArray(18) { 0x81.toByte() } + byteArrayOf(0)) }
        assertThrows(Exception::class.java) { CanonicalCbor.encode(0.5) }
        assertThrows(Exception::class.java) { CanonicalCbor.encode("\ud800") }
        assertEquals("a261620262616101", hex(CanonicalCbor.encode(mapOf("aa" to 1, "b" to 2))))
    }

    @Test fun mstRootsMatchAtprotoReferenceIncludingStructuralLayers() {
        // @atproto/repo MST.create(MemoryBlockstore).add(path, CID), independently generated.
        val recordCid = "bafyreie4skwimcy6rvyz3e6xgrqpndeipmbhpcmg33pejewxwvtoraqjz4"
        val expected = mapOf(0 to "bafyreie5737gdxlw5i64vzichcalba3z2v5n6icifvx5xytvske7mr3hpm",
            1 to "bafyreidphvidzya4ecq6lsl34ioit4i5xqo6gnuwcqt5j7qtxznp7j62qq",
            2 to "bafyreieiwnukqp6tkj6slmcsxnq4z4ioxttcv23pwn4npf3ybxwjemcec4",
            10 to "bafyreihxl75zffvdhyvhvkylu4zgq7aa57x4uf4qbrsuqw2pznlzwt3m4u",
            64 to "bafyreiepbg6lzbqocylo45rrpedyqi424j6egpkejei2xgul2ukt5np3wu",
            128 to "bafyreihmizbmhzyvqqmmdr5yy3uzed76gyf67i7ftbmc3knxjqwtekfnlu")
        for ((count, root) in expected) {
            val records = (0 until count).associate { "xxx.disaster.post/key${it.toString().padStart(3, '0')}" to recordCid }
            assertEquals("$count records", root, AtRepository.mstRoot(records))
            assertEquals(root, AtRepository.mstRoot(records.entries.reversed().associate { it.toPair() }))
        }
    }

    @Test fun repositoryFieldsHaveIndependentTags() {
        val event = author().createPost("本文", now).event
        assertFalse("Opaque legacy envelope must not be generated", event.tags.any { it[0] == "atproto" })
        for (field in listOf("commit", "recordCid", "base", "path", "action", "key", "type", "createdAt")) {
            assertEquals(field, 1, event.tags.count { it[0] == "org.mndt.experimental.v2.$field" })
        }
        assertEquals("本文", event.content)
        assertEquals(1, event.tags.count { it[0] == "mndt-exp" })
    }

    @Test fun deliveryDeadlineIsPreservedInSignedRecord() {
        val event = author().createPost("本文", now).event
        val bytes = Base64.getDecoder().decode(envelope(event)["recordCbor"].string())
        val record = CanonicalCbor.decode(bytes) as Map<*, *>
        val expiry = event.tags.single { it[0] == "mndt-exp" }[1].toLong()
        // TODO(undecided): provisional field name and timestamp representation.
        assertEquals(java.time.Instant.ofEpochSecond(expiry).toString(), record["deliveryExpiresAt"])
        assertTrue(AtRepository.verifyEnvelope(event))
    }

    @Test fun authorPreservesPreviousStateAndSignedCommitBytes() {
        val a = author()
        val first = a.createPost("避難所Aは開設済みです。", now)
        val second = a.createPost("追記", now - 1000, first.repository)
        assertTrue(first.event.verify()); assertTrue(second.event.verify())
        assertTrue(AtRepository.verifyEnvelope(first.event)); assertTrue(AtRepository.verifyEnvelope(second.event))
        assertEquals(1, first.repository.records.size); assertEquals(2, second.repository.records.size)
        assertTrue(second.repository.records.entries.containsAll(first.repository.records.entries))
        assertTrue(second.repository.lastRevMicros > first.repository.lastRevMicros)
        assertTrue(envelope(first.event)["applyFromCommit"].isNull)
        assertEquals(first.repository.previousCommit, envelope(second.event)["applyFromCommit"].string())
        assertEquals(listOf("mndt", "1", "dtn://mndt.local/~demo", "8", "1"), first.event.tags[0])
        assertEquals(listOf("mndt-exp", (now / 1000 + 86400).toString()), first.event.tags[1])
        assertEquals(listOf("t", "mndt-experimental-v2"), first.event.tags[2])
        for (post in listOf(first, second)) {
            val env = envelope(post.event)
            val bytes = Base64.getDecoder().decode(env["commitCbor"].string())
            val commit = CanonicalCbor.decode(bytes) as Map<*, *>
            assertEquals(3L, commit["version"]); assertNull(commit["prev"])
            assertEquals(CidLink(AtRepository.mstRoot(post.repository.records)), commit["data"])
            assertEquals(post.repository.previousCommit, cid(bytes))
        }
        assertThrows(UnsupportedOperationException::class.java) { (first.event.tags as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (first.event.tags[0] as MutableList).clear() }
        assertEquals(first.event, EventCodec.decode(EventCodec.encode(first.event)))
        // Optional independent-interoperability export; never includes private keys.
        System.getenv("MNDT_FIXTURE_DIR")?.let { dir ->
            val third = a.createPost("未知拡張の独立照合", now + 1000, second.repository, extensions = listOf(
                mapOf("\$type" to "org.example.audit.defs#unknown", "bytes" to byteArrayOf(0, -1),
                    "ref" to CidLink("b" + base32(byteArrayOf(1, 0x55, 0x12, 0x20) + sha256(byteArrayOf(7)))),
                    "values" to listOf(null, false, emptyList<Any>(), 9007199254740993L, Long.MIN_VALUE, Long.MAX_VALUE)),
                mapOf("\$type" to "org.example.audit.defs#unknown", "nullable" to null)
            ))
            val fixture = mapOf("did" to a.did, "initialOperation" to strictJson(a.initialOperationJson), "binding" to strictJson(a.bindingJson),
                "posts" to listOf(first, second, third).map {
                    val change = AtRepository.restore(it.event)
                    mapOf("event" to strictJson(it.event.toJson()), "repository" to it.repository,
                        "recordCbor" to Base64.getEncoder().encodeToString(change.recordBytes),
                        "commitCbor" to Base64.getEncoder().encodeToString(change.commitBytes))
                })
            File(dir).mkdirs(); File(dir, "kotlin-fixture.json").writeText(jsonMapper.writeValueAsString(fixture))
        }
    }

    @Test fun alteredEnvelopeAndHighSCommitAreRejectedEvenWithValidNostrSignature() {
        val e = author().createPost("本文", now).event
        assertFalse(AtRepository.verifyEnvelope(resigned(e.copy(content = "別の本文"))))
        assertFalse(AtRepository.verifyEnvelope(withEnvelope(e) { it["recordCid"] = it["commitCid"] }))
        assertFalse(AtRepository.verifyEnvelope(withEnvelope(e) { it["applyFromCommit"] = "bad-cid" }))
        assertFalse(AtRepository.verifyEnvelope(withEnvelope(e) { it["version"] = 2 }))
        val highS = withEnvelope(e) { env ->
            val commit = (CanonicalCbor.decode(Base64.getDecoder().decode(env["commitCbor"] as String)) as Map<*, *>).entries.associate { it.key as String to it.value }.toMutableMap()
            val sig = (commit["sig"] as ByteArray).copyOf()
            val order = BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)
            val s = order.subtract(BigInteger(1, sig.copyOfRange(32, 64))).toString(16).padStart(64, '0')
            unhex(s, 32).copyInto(sig, 32)
            commit["sig"] = sig
            val bytes = CanonicalCbor.encode(commit)
            env["commitCbor"] = Base64.getEncoder().encodeToString(bytes); env["commitCid"] = cid(bytes)
        }
        assertTrue(highS.verify()); assertFalse(AtRepository.verifyEnvelope(highS))
    }

    @Test fun keysAreSeparatedIdentityAndBindingSignedWithoutRegistration() {
        val a = author()
        assertTrue(a.did.matches(Regex("did:plc:[a-z2-7]{24}")))
        assertEquals(a.did, author().did)
        val op = strictJson(a.initialOperationJson)
        assertEquals("plc_operation", op["type"].string()); assertTrue(op["prev"].isNull)
        assertEquals("https://pds.invalid", op["services"]["atproto_pds"]["endpoint"].string())
        val signed = jsonMapper.convertValue(op, Map::class.java).entries.associate { it.key as String to it.value }
        assertEquals(a.did, "did:plc:" + base32(sha256(CanonicalCbor.encode(signed))).take(24))
        assertTrue(secp.verify(Base64.getUrlDecoder().decode(op["sig"].string()), sha256(CanonicalCbor.encode(signed.filterKeys { it != "sig" })), secp.pubkeyCreate(key(5))))
        val binding = strictJson(a.bindingJson)
        val body = jsonMapper.convertValue(binding["declaration"], Map::class.java)
        val digest = sha256(CanonicalCbor.encode(body))
        assertTrue(secp.verify(Base64.getDecoder().decode(binding["atprotoSig"].string()), digest, secp.pubkeyCreate(key(4))))
        assertTrue(secp.verifySchnorr(unhex(binding["nostrSig"].string(), 64), digest, unhex(a.nostrPubkey, 32)))
        assertThrows(IllegalArgumentException::class.java) { SecretKeys(key(3), key(3), key(5)) }
        assertEquals("SecretKeys(nostr=<redacted>, atproto=<redacted>, plc=<redacted>)", SecretKeys.generate().toString())
        assertThrows(IllegalArgumentException::class.java) { Author(a.keys, "http://pds.invalid") }
    }

    @Test fun demoRecordCapRejectsWithoutDiscardingSavedPosts() {
        val a = author()
        var state = RepositoryState()
        repeat(128) { state = a.createPost("投稿$it", now, state).repository }
        assertEquals(128, state.records.size)
        assertThrows(IllegalArgumentException::class.java) { a.createPost("超過", now, state) }
        assertEquals(128, state.records.size)
    }
}
