package org.mndt.core

/**
 * Node-side bookkeeping for deletion requests (`dev.jisaku.mosimo.delete`) and reports (`dev.jisaku.mosimo.report`).
 * A target stops being shown and forwarded once its author asked for deletion, or once [threshold] distinct
 * reporters reported it. The requests themselves are ordinary events and keep travelling. Not thread-safe.
 */
class Moderation(val threshold: Int = 2) {
    /** Target event ID → pubkeys that asked for its deletion (applied only when the target's author asked). */
    private val deletions = LinkedHashMap<String, MutableSet<String>>()
    /** Target event ID → report event ID → reporter pubkey. */
    private val reports = LinkedHashMap<String, MutableMap<String, String>>()
    /** Reports withdrawn by their own author (delete §5), as report ID + reporter. */
    private val withdrawn = hashSetOf<String>()

    /** Records a deletion request or report; returns the target it concerns, null for other events. */
    fun absorb(event: NostrEvent): String? {
        AtRepository.deletion(event)?.let { d ->
            deletions.getOrPut(d.target) { mutableSetOf() } += event.pubkey
            if (d.kind == 1984) withdrawn += d.target + event.pubkey
            trim()
            return d.target
        }
        val r = AtRepository.report(event)?.takeIf { it.author != event.pubkey } ?: return null
        reports.getOrPut(r.target) { mutableMapOf() }[event.id] = event.pubkey
        trim()
        return r.target
    }

    /** The target's author asked for its deletion. */
    fun deleted(event: NostrEvent): Boolean = deletions[event.id]?.contains(event.pubkey) == true

    /** Distinct reporters of [id] (the target's author never counts; withdrawn reports do not count). */
    fun reporters(id: String, author: String? = null): Int =
        reports[id].orEmpty().filter { (report, reporter) -> report + reporter !in withdrawn }.values.toSet().minus(setOfNotNull(author)).size

    /** Shown and forwarded no more: deleted by its author or reported by at least [threshold] reporters. */
    fun stopped(event: NostrEvent): Boolean = deleted(event) || reporters(event.id, event.pubkey) >= threshold

    /** Not worth requesting from peers. Pending deletions are not trusted before the target's author is known. */
    fun skip(id: String): Boolean = reporters(id) >= threshold

    private fun trim() {
        while (deletions.size > MAX_TARGETS) deletions.remove(deletions.keys.first())
        while (reports.size > MAX_TARGETS) reports.remove(reports.keys.first())
    }

    companion object {
        val REPORT_TYPES = setOf("nudity", "malware", "profanity", "illegal", "spam", "impersonation", "other")
        const val MAX_TARGETS = 4096
    }
}
