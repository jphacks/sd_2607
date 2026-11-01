package org.mndt.app.ui.post

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mndt.mesh.Annotation
import org.mndt.mesh.PostExtensions
import org.mndt.mesh.PostView
import org.mndt.mesh.Relation

class AnnotationsTest {
    private fun post(id: String, did: String? = null, pubkey: String = id, on: String? = null, relation: Relation = Relation.ENDORSEMENT) =
        PostView(id, pubkey, "x", hop = 0, relayAccepted = false, did = did,
            ext = PostExtensions(annotation = on?.let { Annotation(relation, it) }))

    @Test fun groupsAnnotationsAndCountsDistinctEndorsers() {
        val posts = listOf(
            post("t"),
            post("a", did = "did:plc:alice", on = "t"),
            post("b", did = "did:plc:alice", on = "t"),
            post("c", pubkey = "k", on = "t"),
            post("d", did = "did:plc:bob", on = "t", relation = Relation.CORRECTION),
            post("orphan", on = "gone"),
        )
        assertEquals(listOf("t", "orphan"), standalone(posts).map { it.id })
        val onTarget = annotationsByTarget(posts).getValue("t")
        assertEquals(4, onTarget.size)
        assertEquals(setOf("did:plc:alice", "k"), endorsers(onTarget))
    }
}
