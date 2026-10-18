package org.mndt.core

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.util.Base64

class SignatureTest {
    private fun key(n: Int) = ByteArray(32).also { it[31] = n.toByte() }
    private fun author() = Author(SecretKeys(key(3), key(4), key(5)), "https://pds.invalid")
    private val now = 1792195200000L
    private val p = AtRepository.TAG_PREFIX
    private fun resigned(e: NostrEvent): NostrEvent {
        val hash = sha256(e.signingBytes())
        return e.copy(id = hex(hash), sig = hex(secp.signSchnorr(hash, key(3), ByteArray(32))))
    }
    private fun replaceTag(e: NostrEvent, name: String, value: List<String>?): NostrEvent =
        resigned(e.copy(tags = e.tags.mapNotNull { if (it[0] == name) value else it }))
    /** Re-signs a modified commit with the author's atproto key so only the edited property differs. */
    private fun withCommit(e: NostrEvent, edit: (MutableMap<String, Any?>) -> Unit): NostrEvent {
        val commit = (CanonicalCbor.decode(AtRepository.restore(e).commitBytes) as Map<*, *>).entries
            .associate { it.key as String to it.value }.toMutableMap()
        commit.remove("sig"); edit(commit)
        if ("sig" !in commit) commit["sig"] = secp.sign(sha256(CanonicalCbor.encode(commit)), key(4))
        return replaceTag(e, p + "commit", listOf(p + "commit", B64.encode(CanonicalCbor.encode(commit))))
    }

    @Test fun postUsesExactly0012BaseTags() {
        val a = author()
        val created = a.createPost("本文", now)
        val e = created.event
        val change = AtRepository.restore(e, a.atprotoPubkey)
        assertEquals(listOf("t", "mosimo.oriom.dev"), e.tags[0])
        assertEquals(listOf(p + "version", "1"), e.tags[1])
        assertEquals(listOf(p + "base", ""), e.tags.single { it[0] == p + "base" })
        assertEquals(listOf(p + "created-at", "2026-10-17T00:00:00Z"), e.tags.single { it[0] == p + "created-at" })
        assertEquals(listOf(p + "delivery-expires-at", "2026-10-18T00:00:00Z"), e.tags.single { it[0] == p + "delivery-expires-at" })
        assertEquals(7, e.tags.size)
        assertTrue(e.tags.all { it.size == 2 })
        val commitTag = e.tags.single { it[0] == p + "commit" }[1]
        assertFalse("0012 §3.5: no padding", commitTag.endsWith("="))
        assertArrayEquals(Base64.getDecoder().decode(commitTag), change.commitBytes)
        assertTrue(change.path.matches(Regex("dev\\.oriom\\.mosimo\\.post/[2-7a-z]{13}")))
        assertNull(change.applyFromCommit)
        val record = CanonicalCbor.decode(change.recordBytes) as Map<*, *>
        assertEquals(setOf("\$type", "text", "createdAt", "deliveryExpiresAt", "extensions"), record.keys)
        assertEquals("dev.oriom.mosimo.post", record["\$type"])
        assertEquals(created.repository.previousCommit, change.commitCid)
        assertEquals(CidLink(AtRepository.mstRoot(created.repository.records)), (CanonicalCbor.decode(change.commitBytes) as Map<*, *>)["data"])
    }

    @Test fun extensionsAreSelfContainedTagsInArrayOrder() {
        val link = CidLink("b" + base32(byteArrayOf(1, 0x55, 0x12, 0x20) + sha256(byteArrayOf(7))))
        val unknown = mapOf("\$type" to "org.example.audit.defs#unknown", "bytes" to byteArrayOf(0, -1), "ref" to link,
            "values" to listOf(null, false, emptyList<Any>(), 9007199254740993L, Long.MIN_VALUE, Long.MAX_VALUE))
        val area = mapOf("\$type" to "dev.oriom.mosimo.defs#area", "regions" to emptyList<Any>())
        val e = author().createPost("本文", now, extensions = listOf(unknown, area, unknown)).event
        val tags = e.tags.filter { it[0].startsWith(AtRepository.EXT_PREFIX) }
        assertEquals(listOf("org.example.audit.defs#unknown", "dev.oriom.mosimo.defs#area", "org.example.audit.defs#unknown"),
            tags.map { it[0].removePrefix(AtRepository.EXT_PREFIX) })
        assertArrayEquals(CanonicalCbor.encode(area - "\$type"), B64.decode(tags[1][1]))
        val record = CanonicalCbor.decode(AtRepository.restore(e).recordBytes) as Map<*, *>
        assertArrayEquals(CanonicalCbor.encode(listOf(unknown, area, unknown)), CanonicalCbor.encode(record["extensions"]))
        val mesh = MeshEngine()
        mesh.originate(e, now, 0)
        assertEquals(e, MeshEngine().accept(mesh.forward(e.id, now, 0)!!, now, 0)!!.event)
    }

    @Test fun meshCborV1TypesOnlyTheThreeKnownTagValues() {
        val e = author().createPost("本文", now, extensions = listOf(mapOf("\$type" to "org.example.a.defs#x", "n" to 1))).event
        val tags = (CanonicalCbor.decode(EventCodec.encode(e)) as List<*>)[4] as List<*>
        fun value(name: String) = (tags.single { (it as List<*>)[0] == name } as List<*>)[1]
        assertTrue(value(p + "commit") is ByteArray)
        assertTrue(value(AtRepository.EXT_PREFIX + "org.example.a.defs#x") is ByteArray)
        assertEquals(1L, value(p + "version"))
        assertEquals("", value(p + "base"))
        assertEquals("mosimo.oriom.dev", value("t"))
        assertEquals(e, EventCodec.decode(EventCodec.encode(e)))
        // A non-canonical spelling is carried as the original string, never normalized.
        val padded = replaceTag(e, p + "commit", listOf(p + "commit", e.tags.single { it[0] == p + "commit" }[1] + "="))
        assertEquals(padded, EventCodec.decode(EventCodec.encode(padded)))
        val v2 = replaceTag(e, p + "version", listOf(p + "version", "01"))
        assertEquals(v2, EventCodec.decode(EventCodec.encode(v2)))
        // bytes on a tag outside the v1 table must be rejected, not guessed back into a string.
        val raw = (CanonicalCbor.decode(EventCodec.encode(e)) as List<*>).toMutableList()
        raw[4] = (raw[4] as List<*>).map { if ((it as List<*>)[0] == "t") listOf("t", byteArrayOf(1)) else it }
        assertThrows(Exception::class.java) { EventCodec.decode(CanonicalCbor.encode(raw)) }
    }

    @Test fun extraNostrTagsDoNotBlockRepositoryRestoration() {
        val original = author().createPost("本文", now).event
        val event = resigned(original.copy(tags = original.tags + listOf(listOf("t", "another-topic"), listOf("unknown", "keep"),
            listOf(p + "record-type", "x"))))
        assertTrue(AtRepository.verifyEnvelope(event))
        assertArrayEquals(AtRepository.restore(original).recordBytes, AtRepository.restore(event).recordBytes)
    }

    @Test fun invalidBindingDataIsRejected() {
        val e = author().createPost("本文", now, extensions = listOf(mapOf("\$type" to "org.example.audit.defs#unknown", "value" to null))).event
        val ext = e.tags.single { it[0].startsWith(AtRepository.EXT_PREFIX) }
        val commit = e.tags.single { it[0] == p + "commit" }
        val bad = listOf(
            replaceTag(e, ext[0], listOf(ext[0], ext[1], "extra")),
            replaceTag(e, ext[0], listOf(ext[0], B64.encode(CanonicalCbor.encode(mapOf("\$type" to "org.example.audit.defs#unknown"))))),
            replaceTag(e, ext[0], listOf(AtRepository.EXT_PREFIX + "not-a-type", ext[1])),
            replaceTag(e, p + "commit", listOf(p + "commit", commit[1] + "=")),
            replaceTag(e, p + "version", listOf(p + "version", "2")),
            replaceTag(e, p + "base", null),
            replaceTag(e, "t", null),
            replaceTag(e, p + "created-at", listOf(p + "created-at", "2026-10-17T00:00:00+00:00")),
            replaceTag(e, p + "created-at", listOf(p + "created-at", "2026-10-17T00:00:01Z")),
            replaceTag(e, p + "delivery-expires-at", listOf(p + "delivery-expires-at", "1792281600")),
            replaceTag(e, p + "path", listOf(p + "path", "xxx.disaster.post/3jzfcijpj2z2a")),
            resigned(e.copy(tags = e.tags + listOf(commit))),
        )
        bad.forEachIndexed { i, it -> assertFalse("case $i", AtRepository.verifyEnvelope(it)) }
        assertThrows(Exception::class.java) { author().createPost("本文", now, deliveryExpiresAtSeconds = now / 1000) }
        assertThrows(Exception::class.java) { author().createPost("本文", now, extensions = listOf(mapOf("\$type" to "not-a-type"))) }
    }

    @Test fun recordKeyNeedNotEqualRevAndCommitSignatureIsChecked() {
        val a = author()
        val e = a.createPost("本文", now).event
        val otherRev = withCommit(e) { it["rev"] = "3jzfcijpj2z2a" }
        assertTrue("0012 §2.1.1: rkey and rev are separate identifiers", AtRepository.verifyEnvelope(otherRev, a.atprotoPubkey))
        val wrongKey = Author(SecretKeys(key(6), key(7), key(8)), "https://pds.invalid").atprotoPubkey
        assertFalse(AtRepository.verifyEnvelope(e, wrongKey))
        val highS = withCommit(e) {
            val sig = secp.sign(sha256(CanonicalCbor.encode(it)), key(4))
            val order = BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)
            unhex(order.subtract(BigInteger(1, sig.copyOfRange(32, 64))).toString(16).padStart(64, '0'), 32).copyInto(sig, 32)
            it["sig"] = sig
        }
        assertTrue(highS.verify()); assertFalse(AtRepository.verifyEnvelope(highS))
    }

    @Test fun authorChainsCommitsAndKeepsTidsMonotonic() {
        val a = author()
        val first = a.createPost("避難所Aは開設済みです。", now)
        val second = a.createPost("追記", now - 1000, first.repository)
        assertEquals(first.repository.previousCommit, AtRepository.restore(second.event, a.atprotoPubkey).applyFromCommit)
        assertTrue(second.repository.records.entries.containsAll(first.repository.records.entries))
        assertTrue(second.repository.lastRevMicros > first.repository.lastRevMicros)
        assertEquals(first.repository.clockId, second.repository.clockId)
        assertTrue(AtRepository.restore(second.event).path > AtRepository.restore(first.event).path)
        assertThrows(UnsupportedOperationException::class.java) { (first.event.tags as MutableList).clear() }
        assertEquals(first.event, EventCodec.decode(EventCodec.encode(first.event)))
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

    @Test fun genesisControlEventCarriesTheUnchangedOperationThroughTheMesh() {
        val a = author()
        val now = 1_792_195_200_000L
        val event = a.createPlcGenesisEvent(now)
        assertEquals(AtRepository.PLC_GENESIS_KIND, event.kind)
        assertEquals(a.initialOperationJson, event.content)
        assertEquals(a.did, AtRepository.plcDid(event.content))
        assertEquals(event, EventCodec.decode(EventCodec.encode(event)))
        assertEquals(event, MeshEngine().originate(event, now, 0).event)
        assertTrue(runCatching { AtRepository.plcDid("{\"type\":\"plc_operation\"") }.isFailure)
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
}
