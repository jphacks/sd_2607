package org.mndt.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AttestTest {
    private class Key { val sk = SecretKeys.generate().nostr; val pub: ByteArray = secp.pubkeyCreate(sk).copyOfRange(1, 33) }
    private fun sign(body: Map<String, Any?>, k: Key): ByteArray = secp.signSchnorr(sha256(CanonicalCbor.encode(body)), k.sk, ByteArray(32))
    private val now = Instant.parse("2026-11-11T00:00:00Z")
    private val createdMillis = now.toEpochMilli()

    private fun attest(issuer: Key, subject: ByteArray, roles: List<String>, depth: Long = 0, until: String? = "2027-01-01T00:00:00Z",
        org: Boolean = true, subkey: ByteArray? = null): Map<String, Any?> {
        val blocks = listOfNotNull(
            mapOf("\$type" to Attest.KEY_VALIDITY, "from" to "2026-01-01T00:00:00Z") + (until?.let { mapOf("until" to it) } ?: emptyMap()),
            if (depth > 0) mapOf("\$type" to Attest.DELEGATION, "maxDepth" to depth) else null,
            if (org) mapOf("\$type" to Attest.ORGANIZATION, "orgId" to "lg:042021", "orgName" to "仙台市") else null,
            subkey?.let { mapOf("\$type" to Attest.SUBKEY, "key" to it) },
        )
        val body = mapOf("\$type" to Attest.KEY_ATTESTATION, "issuer" to issuer.pub, "subject" to subject,
            "owner" to mapOf("id" to "o", "name" to "担当"), "roles" to roles.sorted(), "blocks" to blocks)
        return mapOf("body" to body, "sig" to sign(body, issuer))
    }

    private val root = Key(); private val ca = Key()
    private val roots = listOf(Attest.Root(root.pub, "test root", setOf("attest", "identity", "public-information", "import"), 3))
    private val author = Author(SecretKeys.generate(), "https://pds.invalid")
    private val leafKey = unhex(author.nostrPubkey, 32)
    private val caSub = Key()
    private val chain = listOf(attest(root, ca.pub, listOf("attest", "identity"), depth = 2, subkey = caSub.pub), attest(ca, leafKey, listOf("identity")))
    private fun post(c: List<Map<String, Any?>> = chain) = author.createPost("仙台市からのお知らせ", createdMillis,
        extensions = listOf(mapOf("\$type" to Attest.CHAIN, "attestations" to c))).event

    private fun view(store: PublicIngress.RevocationStore = PublicIngress.RevocationStore(), seen: Instant = now) =
        PublicIngress.chainView(post(), roots, store, now, seen)!!

    @Test fun verifiedChainNamesTheOrganizationOnlyWithIdentity() {
        val v = view()
        assertEquals(Attest.ChainState.VERIFIED, v.state)
        assertTrue(v.confirmedNow && v.identity)
        assertEquals("仙台市", v.name)
        val noIdentity = PublicIngress.chainView(post(listOf(chain[0], attest(ca, leafKey, listOf()))), roots, PublicIngress.RevocationStore(), now, now)!!
        assertEquals(Attest.ChainState.VERIFIED, noIdentity.state)
        assertFalse(noIdentity.identity)
    }

    @Test fun unknownRootAndForeignLeafAreNotVerified() {
        assertEquals(Attest.ChainState.UNVERIFIABLE, PublicIngress.chainView(post(), emptyList(), PublicIngress.RevocationStore(), now, now)!!.state)
        val other = Key()
        val foreign = post(listOf(chain[0], attest(ca, other.pub, listOf("identity"))))
        assertEquals(Attest.ChainState.INVALID, PublicIngress.chainView(foreign, roots, PublicIngress.RevocationStore(), now, now)!!.state)
        assertEquals(Attest.ChainState.UNKNOWN_TIME, PublicIngress.chainView(post(), roots, PublicIngress.RevocationStore(), null, now)!!.state)
    }

    private fun notice(issuer: Key, subject: ByteArray, effectiveAt: String?): Map<String, Any?> {
        val body = mapOf("\$type" to Attest.NOTICE, "issuer" to issuer.pub, "subject" to subject, "issuedAt" to "2026-06-01T00:00:00Z",
            "blocks" to emptyList<Any>()) + (effectiveAt?.let { mapOf("effectiveAt" to it) } ?: emptyMap())
        return mapOf("body" to body, "sig" to sign(body, issuer))
    }
    private fun activation(n: Map<String, Any?>, signer: Key, at: String): Map<String, Any?> {
        val body = mapOf("\$type" to Attest.ACTIVATION, "noticeId" to sha256(CanonicalCbor.encode(n["body"])), "effectiveAt" to at)
        return mapOf("body" to body, "sig" to sign(body, signer))
    }
    private fun controlEvent(revocation: Map<String, Any?>): NostrEvent {
        val carrier = Author(SecretKeys.generate(), "https://pds.invalid")
        return carrier.signEvent(AtRepository.CONTROL_KIND, listOf(listOf("t", "mosimo.jisaku.dev"), listOf("t", AtRepository.REVOCATION_TOPIC),
            listOf(AtRepository.EXT_PREFIX + Attest.REVOCATION, B64.encode(CanonicalCbor.encode(revocation)))), "", createdMillis)
    }

    @Test fun revocationControlEventRevokesForAnyCarrier() {
        val event = controlEvent(mapOf("notice" to notice(root, ca.pub, "2026-10-01T00:00:00Z")))
        assertEquals(event, EventCodec.decode(EventCodec.encode(event)))
        val store = PublicIngress.RevocationStore()
        assertTrue(store.add(Attest.revocationOf(event)!!))
        assertFalse("re-send is dropped", store.add(Attest.revocationOf(event)!!))
        assertEquals(Attest.RevocationState.REVOKED, view(store).revocation)
        assertFalse(view(store).confirmedNow)
        // A notice whose issuer is not an ancestor has no effect.
        val stranger = PublicIngress.RevocationStore(listOf(Attest.revocationOf(controlEvent(mapOf("notice" to notice(Key(), ca.pub, "2026-10-01T00:00:00Z"))))!!))
        assertEquals(Attest.RevocationState.NOT_REVOKED_AS_KNOWN, view(stranger).revocation)
        // Effective after both creation and first sight: revoked later.
        val later = PublicIngress.RevocationStore(listOf(Attest.revocationOf(controlEvent(mapOf("notice" to notice(root, ca.pub, "2026-12-01T00:00:00Z"))))!!))
        assertEquals(Attest.RevocationState.REVOKED_LATER, view(later).revocation)
    }

    @Test fun subkeyActivationCountsOnlyWithTheSubkeyAttestedInTheEvaluatedChain() {
        val preIssued = notice(root, ca.pub, null)
        val r = Attest.revocation(mapOf("notice" to preIssued, "activation" to activation(preIssued, caSub, "2026-10-01T00:00:00Z")))!!
        assertTrue(r.needsSubkey)
        assertEquals(Attest.RevocationState.REVOKED, view(PublicIngress.RevocationStore(listOf(r))).revocation)
        // An attacker's own sub-key, attested nowhere in this chain, cannot activate it.
        val forged = Attest.revocation(mapOf("notice" to preIssued, "activation" to activation(preIssued, Key(), "2026-10-01T00:00:00Z")))!!
        assertEquals(Attest.RevocationState.NOT_REVOKED_AS_KNOWN, view(PublicIngress.RevocationStore(listOf(forged))).revocation)
        assertNull("inactive pre-issued notice", Attest.revocation(mapOf("notice" to preIssued)))
    }
}
