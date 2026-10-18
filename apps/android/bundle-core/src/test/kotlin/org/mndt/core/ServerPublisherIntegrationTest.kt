package org.mndt.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Public fixtures emitted by the server's real publisher, not the hand-built signature/mutation vector. */
class ServerPublisherIntegrationTest {
    private val fixture = jsonMapper.readTree(checkNotNull(javaClass.getResourceAsStream("/server-publisher-wire-v1.json")))
    private val config = PublicIngress.config(fixture["config"].toString())
    private val now = Instant.ofEpochSecond(fixture["checkedAtSeconds"].longValue())
    private val atKey = unhex(fixture["certificate"]["body"]["subjectPubkey"].textValue(), 33)
    private fun bytes(text: String) = unhex(text, text.length / 2)
    private fun initialMemory() = PublicIngress.acceptSnapshot(config.roots.single(), bytes(fixture["snapshotHex"].textValue()), PublicIngress.Memory(), now)

    @Test fun actualServerPublisherPostsAuthorizeAndPreserveSignedOriginalThroughMesh() {
        assertTrue(fixture["testOnly"].booleanValue())
        assertEquals("pds/public-information.mjs:createImporterEvent", fixture["generatedBy"].textValue())
        assertEquals(2, fixture["posts"].size())
        val mesh = MeshEngine(ClockConfidence.TRUSTED)
        val recipient = MeshEngine(ClockConfidence.TRUSTED)
        var memory = initialMemory()
        val subjectDid = AtRepository.plcDid(fixture["plcGenesis"].toString())
        assertEquals(fixture["certificate"]["body"]["subjectDid"].textValue(), subjectDid)
        for ((index, vector) in fixture["posts"].withIndex()) {
            val originalJson = vector["event"].toString()
            val event = NostrEvent.fromJson(originalJson)
            val change = AtRepository.restore(event, atKey)
            assertArrayEquals(bytes(vector["recordHex"].textValue()), change.recordBytes)
            assertArrayEquals(bytes(vector["commitHex"].textValue()), change.commitBytes)
            assertArrayEquals(bytes(vector["meshPayloadHex"].textValue()), EventCodec.encode(event))
            val extensions = (CanonicalCbor.decode(change.recordBytes) as Map<*, *>)["extensions"] as List<*>
            assertEquals(listOf(PublicIngress.BSAF, PublicIngress.IDENTITY, PublicIngress.PRIORITY), extensions.map { (it as Map<*, *>)["\$type"] })
            assertEquals(subjectDid, (CanonicalCbor.decode(change.commitBytes) as Map<*, *>)["did"])
            val decision = PublicIngress.verify(event, config, memory, now)
            val receipt = checkNotNull(decision.receipt) { decision.toString() }
            assertEquals(fixture["certId"].textValue(), receipt.certId)
            assertEquals(3, receipt.priority)
            assertEquals(7L, receipt.sequence)
            assertEquals("valid", decision.status.authorSignature)
            assertTrue(decision.status.binding.startsWith("valid"))
            assertTrue(decision.status.chainScope.startsWith("valid"))
            assertTrue(decision.status.certificateTime.startsWith("valid"))
            assertTrue(decision.status.revocation.startsWith("fresh-as-of"))
            memory = decision.memory // The Coordinator persists this before originate; no fixture secrets are involved.
            assertTrue(PublicIngress.readyToInsert(receipt, event, memory, now))
            val saved = mesh.originate(event, now.toEpochMilli(), index.toLong())
            assertEquals(event, saved.event)
            val forwarded = checkNotNull(mesh.forward(event.id, now.toEpochMilli(), index + 10L))
            val received = checkNotNull(recipient.accept(forwarded, now.toEpochMilli(), index + 10L))
            assertEquals(event, received.event)
            assertEquals(jsonMapper.readTree(originalJson), jsonMapper.readTree(received.event.toJson()))
            assertNull(recipient.accept(forwarded, now.toEpochMilli(), index + 11L))
        }
        assertEquals(2, recipient.entries().size)
    }

    @Test fun completeKnownRepositoryStateMatchesBothRealServerMstRootsAndCids() {
        // This test has the full fixture state; runtime ingress still makes no MST-inclusion claim.
        val records = linkedMapOf<String, String>()
        var previousCommit: String? = null
        for (vector in fixture["posts"]) {
            val event = NostrEvent.fromJson(vector["event"].toString())
            val change = AtRepository.restore(event, atKey)
            assertEquals(previousCommit, change.applyFromCommit)
            assertEquals(vector["path"].textValue(), change.path)
            assertEquals(vector["recordCid"].textValue(), change.recordCid)
            assertEquals(vector["commitCid"].textValue(), change.commitCid)
            records[change.path] = change.recordCid
            val commit = CanonicalCbor.decode(change.commitBytes) as Map<*, *>
            val mst = (commit["data"] as CidLink).value
            assertEquals(vector["mstRoot"].textValue(), mst)
            assertEquals(mst, AtRepository.mstRoot(records))
            assertNotEquals(mst, AtRepository.mstRoot(records - change.path))
            assertEquals(records, vector["repositoryEntries"].associate { it["key"].textValue() to it["cid"].textValue() })
            for (block in vector["repositoryBlocks"]) {
                val raw = bytes(block["hex"].textValue())
                assertEquals(block["cid"].textValue(), cid(raw))
                assertArrayEquals(raw, CanonicalCbor.encode(CanonicalCbor.decode(raw)))
            }
            previousCommit = change.commitCid
        }
        val primary = fixture["posts"][0]
        for (name in listOf("event", "recordHex", "commitHex", "meshPayloadHex")) assertEquals(primary[name], fixture[name])
    }

    @Test fun actualPublisherEventStillRequiresLocalPolicyFreshSnapshotClockAndValidSignature() {
        val event = NostrEvent.fromJson(fixture["event"].toString())
        val memory = initialMemory()
        assertNull(PublicIngress.verify(event, config.copy(selected = emptySet()), memory, now).receipt)
        assertNull(PublicIngress.verify(event, config.copy(roots = emptyList()), memory, now).receipt)
        assertNull(PublicIngress.verify(event, config, PublicIngress.Memory(), now).receipt)
        assertNull(PublicIngress.verify(event, config, memory, null).receipt)
        assertNull(PublicIngress.verify(event.copy(content = event.content + "tampered"), config, memory, now).receipt)
        assertNull(PublicIngress.verify(event.copy(sig = "00".repeat(64)), config, memory, now).receipt)
        val until = (CanonicalCbor.decode(bytes(fixture["snapshotHex"].textValue())) as Map<*, *>)["body"] as Map<*, *>
        assertNull(PublicIngress.verify(event, config, memory, Instant.parse(until["validUntil"] as String)).receipt)
    }
}
