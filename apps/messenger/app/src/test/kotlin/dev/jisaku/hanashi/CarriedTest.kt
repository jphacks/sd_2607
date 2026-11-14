package dev.jisaku.hanashi

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mndt.core.NostrEvent
import org.mndt.mesh.Handoff
import org.mndt.mesh.Transit

class CarriedTest {
    private fun event(id: String, pubkey: String, createdAt: Long = 0) = NostrEvent(id, pubkey, createdAt, 1, emptyList(), "", "")
    private fun arrived(at: Long?) = Trail(1, at?.let { Step(it, "ble", "peer") })

    @Test fun othersOnlyNewestArrivalFirstUnknownArrivalByCreationTime() {
        val events = listOf(event("unknown", "a", createdAt = 2), event("old", "b"), event("mine", "me"), event("new", "c"))
        val trails = mapOf("unknown" to arrived(null), "old" to arrived(1_000), "mine" to arrived(null), "new" to arrived(3_000))
        assertEquals(listOf("new", "unknown", "old"), carried(events, "me", trails).map { it.event.id })
    }

    @Test fun aRestartKeepsTheSavedArrivalAndAddsNewForwards() {
        val saved = mapOf("kept" to Trail(2, Step(1, "ble", "x"), listOf(Step(2, "lan", "y"))), "gone" to arrived(5))
        val live = mapOf("kept" to Transit(2, null, listOf(Handoff(9, "ble", "z"))))
        assertEquals(mapOf("kept" to Trail(2, Step(1, "ble", "x"), listOf(Step(2, "lan", "y"), Step(9, "ble", "z")))), merge(saved, live))
    }
}
