package org.mndt.core

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.time.Instant
import java.util.Base64

class PublicIngressTest {
    private val fixture = jsonMapper.readTree(checkNotNull(javaClass.getResourceAsStream("/wire-v1.json")))
    private val config = PublicIngress.config(fixture["config"].toString())
    private val root = config.roots.single()
    private val event = NostrEvent.fromJson(fixture["event"].toString())
    private val now = Instant.parse("2026-10-19T00:00:10Z")
    private fun bytes(name: String) = fixture[name].textValue().let { unhex(it, it.length / 2) }
    private val snapshot = bytes("snapshotHex")
    private fun memory() = PublicIngress.acceptSnapshot(root, snapshot, PublicIngress.Memory(), now)
    private fun key(n: Int) = ByteArray(32).also { it[31] = n.toByte() }
    private fun mutable(v: Any?): MutableMap<String, Any?> = (v as Map<*, *>).entries.associate { it.key as String to it.value }.toMutableMap()
    private fun signed(body: Map<String, Any?>, n: Int = 1) = mapOf("body" to body, "sig" to hex(secp.sign(sha256(CanonicalCbor.encode(body)), key(n))))
    private fun resign(e: NostrEvent): NostrEvent {
        val h = sha256(e.signingBytes())
        return e.copy(id = hex(h), sig = hex(secp.signSchnorr(h, key(3), ByteArray(32))))
    }
    private fun extension(type: String, e: NostrEvent = event) = mutable(CanonicalCbor.decode(B64.decode(e.tags.single { it[0] == AtRepository.EXT_PREFIX + type }[1])!!))
    private fun replace(type: String, fields: Any?, e: NostrEvent = event) = resign(e.copy(tags = e.tags.map {
        if (it[0] == AtRepository.EXT_PREFIX + type) listOf(it[0], B64.encode(CanonicalCbor.encode(fields))) else it
    }))
    private fun identity(edit: (MutableMap<String, Any?>) -> Unit): NostrEvent = replace(PublicIngress.IDENTITY, extension(PublicIngress.IDENTITY).also(edit))
    private fun certificate(edit: (MutableMap<String, Any?>) -> Unit): NostrEvent = identity { id ->
        val cert = mutable((id["chain"] as List<*>).single()); val body = mutable(cert["body"]).also(edit)
        id["chain"] = listOf(signed(body))
    }
    private fun snap(edit: (MutableMap<String, Any?>) -> Unit): ByteArray {
        val body = mutable((CanonicalCbor.decode(snapshot) as Map<*, *>)["body"]).also(edit)
        return CanonicalCbor.encode(signed(body))
    }
    private fun deny(e: NostrEvent = event, c: PublicIngress.Config = config, m: PublicIngress.Memory = memory(), at: Instant? = now): PublicIngress.Decision =
        PublicIngress.verify(e, c, m, at).also { assertNull("Unexpected authorization: $it", it.receipt) }
    private fun rejectSnapshot(b: ByteArray, m: PublicIngress.Memory = memory(), at: Instant? = now) {
        assertThrows(Exception::class.java) { PublicIngress.acceptSnapshot(root, b, m, at) }
    }

    @Test fun sharedNodeFixtureBytesHashesSignaturesAndOriginalBundleRoundTrip() {
        val cert = mutable(jsonMapper.convertValue(fixture["certificate"], Map::class.java))
        assertArrayEquals(bytes("certificateBodyHex"), CanonicalCbor.encode(cert["body"]))
        assertEquals(fixture["certId"].textValue(), hex(sha256(CanonicalCbor.encode(cert["body"]))))
        val body = (CanonicalCbor.decode(snapshot) as Map<*, *>)["body"]
        assertArrayEquals(bytes("snapshotBodyHex"), CanonicalCbor.encode(body))
        assertEquals(fixture["snapshotBodyHash"].textValue(), hex(sha256(CanonicalCbor.encode(body))))
        val change = AtRepository.restore(event, unhex((cert["body"] as Map<*, *>)["subjectPubkey"] as String, 33))
        assertArrayEquals(bytes("recordHex"), change.recordBytes)
        assertArrayEquals(bytes("commitHex"), change.commitBytes)
        val decision = PublicIngress.verify(event, config, memory(), now)
        assertNotNull(decision.reason, decision.receipt)
        val receipt = checkNotNull(decision.receipt)
        assertEquals(3, receipt.priority)
        assertEquals(7L, receipt.sequence)
        assertTrue(PublicIngress.readyToInsert(receipt, event, decision.memory, now))
        assertFalse(PublicIngress.readyToInsert(receipt, event, decision.memory, null))
        assertFalse(PublicIngress.readyToInsert(receipt, event, decision.memory, Instant.parse(receipt.validUntil)))
        assertFalse(PublicIngress.readyToInsert(receipt, event, decision.memory, Instant.parse(receipt.certificateNotAfter)))
        val mesh = MeshEngine(ClockConfidence.TRUSTED)
        val stored = mesh.originate(event, now.toEpochMilli(), 0)
        assertEquals(event, stored.event)
        assertEquals(event, MeshEngine(ClockConfidence.TRUSTED).accept(mesh.forward(event.id, now.toEpochMilli(), 1)!!, now.toEpochMilli(), 1)!!.event)
        assertThrows(IllegalArgumentException::class.java) { mesh.originate(event, now.toEpochMilli(), 2) }
    }

    @Test fun unknownDisabledRootAndEmptyOrUnselectedScopeAreNeverTrustDiscovery() {
        deny(c = config.copy(roots = emptyList()))
        deny(c = config.copy(roots = listOf(root.copy(enabled = false))))
        deny(c = config.copy(selected = emptySet()))
        deny(c = config.copy(selected = setOf(PublicIngress.Selection(config.selected.single().botDid, "new-type"))))
        deny(c = config.copy(roots = listOf(root.copy(scope = root.scope.copy(bsaf = emptySet())))))
        deny(certificate { it["scope"] = mapOf("orgIds" to listOf("different-org"), "bsaf" to listOf(mapOf("botDid" to config.selected.single().botDid, "types" to listOf("earthquake")))) })
    }

    @Test fun rejectDidKeyAndEveryBindingSignatureMismatch() {
        deny(certificate { it["subjectDid"] = "did:plc:" + "c".repeat(24) })
        deny(certificate { it["subjectPubkey"] = hex(secp.pubKeyCompress(secp.pubkeyCreate(key(5)))) })
        for (field in listOf("atprotoSig", "nostrSig")) {
            deny(identity { id -> id["binding"] = mutable(id["binding"]).also { it[field] = if (field == "nostrSig") "00".repeat(64) else Base64.getEncoder().encodeToString(ByteArray(64)) } })
        }
        deny(identity { id -> id["binding"] = mutable(id["binding"]).also { b -> b["declaration"] = mutable(b["declaration"]).also { it["nostrPubkey"] = "00".repeat(32) } } })
        deny(identity { id -> id["binding"] = mutable(id["binding"]).also { b -> b["atprotoSig"] = (b["atprotoSig"] as String).trimEnd('=') } })
        val commit = mutable(CanonicalCbor.decode(bytes("commitHex")))
        commit["did"] = "did:plc:" + "c".repeat(24)
        commit["sig"] = secp.sign(sha256(CanonicalCbor.encode(commit - "sig")), key(4))
        deny(resign(event.copy(tags = event.tags.map { if (it[0] == AtRepository.TAG_PREFIX + "commit") listOf(it[0], B64.encode(CanonicalCbor.encode(commit))) else it })))
        deny(event.copy(sig = "00".repeat(64)))
    }

    @Test fun rejectHighSDerInvalidPointsAndLeafDelegation() {
        val n = BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)
        deny(identity { id ->
            val cert = mutable((id["chain"] as List<*>).single()); val sig = unhex(cert["sig"] as String, 64)
            val high = n.subtract(BigInteger(1, sig.copyOfRange(32, 64))).toString(16).padStart(64, '0')
            cert["sig"] = hex(sig.copyOfRange(0, 32)) + high; id["chain"] = listOf(cert)
        })
        deny(identity { id -> id["chain"] = listOf(mutable((id["chain"] as List<*>).single()).also { it["sig"] = "30".repeat(70) }) })
        deny(certificate { it["subjectPubkey"] = "02" + "ff".repeat(32) })
        deny(certificate { it["canDelegate"] = true })
        deny(certificate { it["role"] = "issuer"; it["subjectDid"] = "" })
        deny(certificate { it["subjectPubkey"] = root.pubkey })
        for (length in listOf(0, 2, 4, 5)) deny(identity { it["chain"] = List(length) { _ -> (it["chain"] as List<*>).single() } })
    }

    @Test fun certificateAndDeliveryTimeBoundariesAndUnknownClock() {
        assertEquals("Clock unknown", deny(at = null).reason)
        deny(at = Instant.parse("2026-10-20T00:00:00Z"))
        deny(at = Instant.parse("2026-10-18T23:59:59Z")) // future event
        deny(certificate { it["notBefore"] = "2026-10-19T00:00:01Z" }) // event predates certificate
        deny(certificate { it["notAfter"] = "2026-10-19T00:00:10Z" })
        deny(certificate { it["notBefore"] = "2025-01-01T00:00:00Z" }) // >366 days
        deny(certificate { it["notAfter"] = "2026-02-30T00:00:00Z" })
        deny(at = Instant.parse("2026-10-19T01:00:00Z")) // exact deadline
        val beforeBoundary = certificate { it["notBefore"] = "2026-10-19T00:00:00Z" }
        assertNotNull(PublicIngress.verify(beforeBoundary, config, memory(), now).receipt)
    }

    @Test fun snapshotsRequireFreshClockCanonicalShapeAndSignature() {
        rejectSnapshot(snapshot, at = null)
        rejectSnapshot(snapshot, at = Instant.parse("2026-10-19T01:00:00Z"))
        rejectSnapshot(snapshot, at = Instant.parse("2026-10-18T23:59:59Z"))
        rejectSnapshot(snap { it["validUntil"] = "2026-10-19T01:00:01Z" })
        rejectSnapshot(snap { it["sequence"] = 9007199254740992L })
        rejectSnapshot(snap { it["unknown"] = true })
        rejectSnapshot(snapshot + byteArrayOf(0))
        rejectSnapshot(ByteArray(32769))
        val wrong = mutable(CanonicalCbor.decode(snapshot)); wrong["sig"] = "00".repeat(64)
        rejectSnapshot(CanonicalCbor.encode(wrong))
        deny(m = PublicIngress.Memory())
        deny(m = memory(), at = Instant.parse("2026-10-19T01:00:00Z"))
    }

    @Test fun monotonicSnapshotsSurviveReloadExpiryAndRootDisableAndCannotUnrevokeRoot() {
        val old = memory()
        rejectSnapshot(snap { it["sequence"] = 6L }, old)
        rejectSnapshot(snap { it["validUntil"] = "2026-10-19T00:59:59Z" }, old) // same seq/body differs
        assertArrayEquals(snapshot, PublicIngress.acceptSnapshot(root, snapshot, old, now).snapshots[root.rootId])
        rejectSnapshot(snap { it["sequence"] = 8L; it["issuedAt"] = "2026-10-18T23:59:59Z"; it["validUntil"] = "2026-10-19T00:59:59Z" }, old)
        for ((field, value) in listOf("revokedCertIds" to fixture["certId"].textValue(), "revokedPubkeys" to root.pubkey,
            "revokedPubkeys" to (fixture["certificate"]["body"]["subjectPubkey"].textValue()))) {
            val b = snap { it["sequence"] = 8L; it[field] = listOf(value) }
            val saved = PublicIngress.acceptSnapshot(root, b, old, now)
            assertEquals("revoked", deny(m = saved).status.revocation)
            // Simulated restart: only persisted snapshot bytes, not an ephemeral verifier cache.
            val reloaded = PublicIngress.Memory(saved.snapshots.mapValues { it.value.copyOf() })
            rejectSnapshot(snapshot, reloaded)
            rejectSnapshot(snap { it["sequence"] = 9L }, reloaded)
            deny(m = reloaded, c = config.copy(roots = listOf(root.copy(enabled = false))))
            val later = Instant.parse("2026-10-19T01:01:00Z")
            rejectSnapshot(snap { it["sequence"] = 9L; it["issuedAt"] = "2026-10-19T01:00:00Z"; it["validUntil"] = "2026-10-19T02:00:00Z" }, reloaded, later)
        }
        rejectSnapshot(snap { it["revokedCertIds"] = listOf(fixture["certId"].textValue(), fixture["certId"].textValue()) })
    }

    @Test fun serialReuseAndBoundedHistoryFailClosed() {
        val first = PublicIngress.verify(event, config, memory(), now)
        val conflict = deny(certificate { it["orgName"] = "Different signed body, same serial" }, m = first.memory)
        deny(m = conflict.memory)
        assertFalse(PublicIngress.readyToInsert(checkNotNull(first.receipt), event, conflict.memory, now))
        val full = memory().copy(serials = (0 until PublicIngress.MAX_SERIALS).associate { it.toString() to "00".repeat(32) })
        deny(m = full)
    }

    @Test fun identityBsafAndPriorityMultiplicityAndCanonicalCbor() {
        val identityTag = event.tags.single { it[0] == AtRepository.EXT_PREFIX + PublicIngress.IDENTITY }
        deny(resign(event.copy(tags = event.tags + listOf(identityTag))))
        deny(identity { it["unknown"] = 1L })
        deny(replace(PublicIngress.BSAF, extension(PublicIngress.BSAF).also { it.remove("original") }))
        deny(replace(PublicIngress.BSAF, extension(PublicIngress.BSAF).also { it["tags"] = (it["tags"] as List<*>) + "type:other" }))
        deny(replace(PublicIngress.BSAF, extension(PublicIngress.BSAF).also { b -> b["original"] = mutable(b["original"]).also { it["uri"] = (it["uri"] as String) + "?x=1" } }))
        for (bad in listOf(byteArrayOf(0xa1.toByte(), 0x61, 0x78, 0x18, 0x01), byteArrayOf(0xa2.toByte(), 0x61, 0x78, 1, 0x61, 0x78, 1))) {
            assertThrows(Exception::class.java) { CanonicalCbor.decode(bad) }
        }
        val p = event.tags.single { it[0] == AtRepository.EXT_PREFIX + PublicIngress.PRIORITY }
        assertEquals(1, PublicIngress.priority(resign(event.copy(tags = event.tags + listOf(p)))))
        for (bad in listOf(-1L, 4L, "3", null)) assertEquals(1, PublicIngress.priority(replace(PublicIngress.PRIORITY, mapOf("version" to 1L, "level" to bad))))
        assertEquals(3, PublicIngress.priority(event))
        assertNull(deny(c = PublicIngress.Config()).receipt) // author's level3 grants nothing
    }

    @Test fun wireResourceLimitsUnknownFieldsAndScopeOrdering() {
        deny(resign(event.copy(tags = event.tags + List(65) { listOf("unknown", "tag") })))
        val extra = (1..17).map { listOf(AtRepository.EXT_PREFIX + "org.example.defs#x$it", B64.encode(CanonicalCbor.encode(emptyMap<String, Any?>()))) }
        deny(resign(event.copy(tags = event.tags + extra)))
        deny(certificate { it["orgName"] = "x".repeat(257) })
        deny(certificate { it["serial"] = "AB".repeat(16) })
        deny(certificate { it["scope"] = mapOf("orgIds" to listOf("test-org", "test-org"), "bsaf" to emptyList<Any>()) })
        deny(certificate { it["scope"] = mapOf("orgIds" to listOf("test-org"), "bsaf" to listOf(mapOf("botDid" to config.selected.single().botDid, "types" to listOf("z", "a")))) })
        deny(replace(PublicIngress.BSAF, extension(PublicIngress.BSAF).also { b -> b["original"] = mutable(b["original"]).also { it["cid"] = "bINVALID" } }))
        deny(replace(PublicIngress.BSAF, extension(PublicIngress.BSAF).also { b -> b["langs"] = listOf("ja_日本語") }))
        rejectSnapshot(snap { it["revokedCertIds"] = (0..256).map { n -> n.toString(16).padStart(64, '0') } })
        val lowMinRoot = root.copy(minSequence = 8)
        assertThrows(Exception::class.java) { PublicIngress.acceptSnapshot(lowMinRoot, snapshot, PublicIngress.Memory(), now) }
    }

    @Test fun sourceTimestampAndBotDefinitionValidationMatchServerWithoutNormalizingOriginals() {
        val invalidTimes = listOf("", "not-a-timestamp", "0000-01-01T00:00:00Z", "2026-02-30T00:00:00Z",
            "2026-10-19T24:00:00Z", "2026-10-19T00:00:60Z", "2026-10-19T00:00:00+24:00", "2026-10-19T00:00:00+09:60")
        for (time in invalidTimes) {
            deny(replace(PublicIngress.BSAF, extension(PublicIngress.BSAF).also { bsaf ->
                bsaf["original"] = mutable(bsaf["original"]).also { it["createdAt"] = time }
            }))
            deny(replace(PublicIngress.BSAF, extension(PublicIngress.BSAF).also {
                it["botDefinition"] = mapOf("url" to "https://bot.example.invalid/definition.json", "retrievedAt" to time)
            }))
        }
        for (url in listOf("file:///tmp/bot.json", "javascript:alert(1)", "data:application/json,{}", "relative.json", "https:?x", "https:/")) {
            deny(replace(PublicIngress.BSAF, extension(PublicIngress.BSAF).also { it["botDefinition"] = mapOf("url" to url) }))
        }
        for (original in listOf("2026-10-18T12:34:56.123456789123+09:00", "2024-02-29T12:34:56Z")) {
            val updated = replace(PublicIngress.BSAF, extension(PublicIngress.BSAF).also { bsaf ->
                bsaf["original"] = mutable(bsaf["original"]).also { it["createdAt"] = original }
                bsaf["botDefinition"] = mapOf("url" to "https://bot.example.invalid/definition.json", "retrievedAt" to original)
            })
            val result = PublicIngress.verify(updated, config, memory(), now)
            assertNotNull(result.reason, result.receipt)
            val record = CanonicalCbor.decode(AtRepository.restore(updated).recordBytes) as Map<*, *>
            val bsaf = (record["extensions"] as List<*>).map { it as Map<*, *> }.single { it["\$type"] == PublicIngress.BSAF }
            assertEquals(original, (bsaf["original"] as Map<*, *>)["createdAt"])
            assertEquals(original, (bsaf["botDefinition"] as Map<*, *>)["retrievedAt"])
        }
    }

    @Test fun strictConfigRejectsDuplicateRootIdWrongHashAndUnsortedScope() {
        assertEquals(PublicIngress.Config(), PublicIngress.config("{\"roots\":[],\"selected\":[]}"))
        val text = fixture["config"].toString()
        for (bad in listOf(text.replace(root.rootId, "00".repeat(32)), text.replace("https://", "http://"),
            text.replace("\"minSequence\":7", "\"minSequence\":0"), "{\"roots\":[],\"roots\":[],\"selected\":[]}"))
            assertThrows(Exception::class.java) { PublicIngress.config(bad) }
    }
}
