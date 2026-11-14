package dev.jisaku.hanashi

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mndt.core.NostrEvent
import org.mndt.mesh.Handoff
import org.mndt.mesh.MeshState
import org.mndt.mesh.Transit

class CarriedTest {
    private fun event(id: String, pubkey: String) = NostrEvent(id, pubkey, 0, 1, emptyList(), "", "")
    private fun arrived(at: Long?) = Transit(1, at?.let { Handoff(it, "ble", "peer") }, emptyList())

    @Test fun othersOnlyNewestArrivalFirstRestoredLast() {
        val state = MeshState(publicKey = "me",
            events = listOf(event("restored", "a"), event("old", "b"), event("mine", "me"), event("new", "c")),
            transits = mapOf("restored" to arrived(null), "old" to arrived(1_000), "mine" to arrived(null), "new" to arrived(2_000)))
        assertEquals(listOf("new", "old", "restored"), carried(state).map { it.event.id })
    }
}
