package org.mndt.core

import org.junit.Assert.*
import org.junit.Test

/** delete §1-3 and report §1-2. */
class ModerationTest {
    private fun key(n: Int) = ByteArray(32).also { it[31] = n.toByte() }
    private fun author(n: Int) = Author(SecretKeys(key(n), key(n + 1), key(n + 2)), "https://pds.invalid")
    private val now = 1792195200000L

    @Test fun deletionCarriesTheRemovalCommitAndTheNextPostBuildsOnIt() {
        val a = author(3)
        val first = a.createPost("残す", now)
        val target = a.createPost("消す", now + 1, first.repository)
        val del = a.createDeletion(target.event, now + 2000, target.repository)
        assertEquals(listOf("t", "e", "k", "mosimo:commit", "mosimo:base", "mosimo:rkey", "mosimo:exp"), del.event.tags.map { it[0] })
        val parsed = AtRepository.deletion(del.event)!!
        assertEquals(target.event.id, parsed.target)
        assertEquals(target.repository.previousCommit, parsed.change!!.applyFromCommit)
        assertFalse(AtRepository.restore(target.event).path in del.repository.records)
        val commit = CanonicalCbor.decode(parsed.change!!.commitBytes) as Map<*, *>
        assertEquals(CidLink(AtRepository.mstRoot(del.repository.records)), commit["data"])
        val next = a.createPost("次", now + 3000, del.repository)
        assertEquals(cid(parsed.change!!.commitBytes), next.event.tags[3][1])
        assertEquals(del.event, EventCodec.decode(EventCodec.encode(del.event)))
        assertThrows(IllegalArgumentException::class.java) { author(10).createDeletion(target.event, now, RepositoryState()) }
    }

    @Test fun deletionStopsAtOnceAndReportsAtTwoDistinctReporters() {
        val a = author(3); val b = author(10); val c = author(20)
        val post = a.createPost("対象", now)
        val m = Moderation()
        m.absorb(b.signEvent(5, listOf(AtRepository.TOPIC_TAG, listOf("e", post.event.id), listOf("k", "1")), "", now))
        assertFalse("a stranger cannot delete", m.stopped(post.event))
        m.absorb(b.createReport(post.event, "spam", nowMillis = now))
        m.absorb(b.createReport(post.event, "other", nowMillis = now + 1000))
        assertFalse("one reporter counts once", m.stopped(post.event))
        val r3 = c.createReport(post.event, "illegal", "理由", now)
        assertEquals("illegal", AtRepository.report(r3)!!.type)
        m.absorb(r3)
        assertTrue(m.stopped(post.event) && m.skip(post.event.id))
        m.absorb(c.createDeletion(r3, now + 2000, RepositoryState()).event)
        assertFalse("a withdrawn report no longer counts", m.stopped(post.event))
        assertThrows(IllegalArgumentException::class.java) { a.createReport(post.event, "spam", nowMillis = now) }
        assertThrows(IllegalArgumentException::class.java) { b.createReport(post.event, "bogus", nowMillis = now) }
        m.absorb(a.createDeletion(post.event, now + 1000, post.repository).event)
        assertTrue(m.deleted(post.event))
        assertEquals(r3, EventCodec.decode(EventCodec.encode(r3)))
    }
}
