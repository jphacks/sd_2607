package org.mndt.mesh

import org.junit.Assert.*
import org.junit.Test
import org.mndt.core.Author
import org.mndt.core.SecretKeys

class PostExtensionsTest {
    private val author by lazy { Author(SecretKeys.generate(), "https://pds.invalid") }

    @Test fun knownExtensionsRoundTripThroughSignedTags() {
        val origin = Origin(38_260_000, 140_870_000, 12_000)
        val circle = Region.Circle(38_260_000, 140_870_000, 200_000)
        val event = author.createPost("［危険］遊歩道が冠水", 1_792_195_200_000L, extensions = listOf(
            Extensions.origin(origin), Extensions.circle(circle), Extensions.validity(until = "2026-11-02T06:00:00.000Z"),
            Extensions.annotation(Relation.SUPPLEMENT, "a".repeat(64)),
            mapOf("\$type" to "example.unknown.extension", "opaque" to "kept"))).event
        val ext = Extensions.decode(event)
        assertEquals(origin, ext.origin)
        assertEquals(listOf(circle), ext.regions)
        assertEquals("2026-11-02T06:00:00.000Z", ext.validity?.until)
        assertEquals(Annotation(Relation.SUPPLEMENT, "a".repeat(64)), ext.annotation)
        assertEquals(1, ext.priority)
        assertNull(ext.bsaf)
    }

    @Test fun plainPostHasNoExtensions() {
        assertEquals(PostExtensions(), Extensions.decode(author.createPost("本文のみ", 1_792_195_200_000L).event))
    }
}
