package org.mndt.mesh

import org.junit.Assert.*
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class OnlineRelayTest {
    @Test fun runningServiceOrVisibleScreenAllowsRelayIo() {
        // (enabled, foreground, fatal, hasRelay, serviceRunning)
        assertTrue(onlineRelayAllowed(true, false, false, true, serviceRunning = true))
        assertTrue(onlineRelayAllowed(true, true, false, true))
        assertFalse(onlineRelayAllowed(true, false, false, true))
        assertFalse(onlineRelayAllowed(false, true, false, true, serviceRunning = true))
        assertFalse(onlineRelayAllowed(true, true, true, true, serviceRunning = true))
        assertFalse(onlineRelayAllowed(true, true, false, false, serviceRunning = true))
    }

    @Test fun rejectingRelayDoesNotStarvePdsOrAnotherRelay() {
        val relays = listOf("wss://general.invalid", "wss://pds.oriom.dev", "wss://other.invalid")
        val client = OkHttpClient()
        val servers = relays.mapIndexed { index, _ -> MockWebServer().apply {
            enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    assertEquals("[\"EVENT\",{\"id\":\"event\"}]", text)
                    webSocket.send("[\"OK\",\"event\",${index != 0},\"test\"]")
                }
            }))
            start()
        } }
        val accepted = mutableSetOf<String>()
        val deferred = mutableMapOf<String, Map<String, Long>>()
        val attempted = mutableListOf<String>()
        try {
            repeat(3) {
                // A rejected event remains pending; only this relay's retry is deferred.
                val index = nextRelayIndex(relays, 0, { if (it in accepted) emptyList() else listOf("event") },
                    deferred, emptyMap(), 20_000) ?: error("No relay selected")
                val relay = relays[index]
                attempted += relay
                val replies = LinkedBlockingQueue<String>()
                val socket = client.newWebSocket(Request.Builder().url(servers[index].url("/")).build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                        webSocket.send(relayEventFrame("{\"id\":\"event\"}"))
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) { replies.offer(text) }
                })
                try {
                    val reply = json.readTree(checkNotNull(replies.poll(5, TimeUnit.SECONDS)) { "Missing relay ACK for $relay" })
                    if (reply[2].booleanValue()) accepted += relay
                    else deferred[relay] = mapOf("event" to 30_000)
                } finally { socket.cancel() }
                if (it == 0) assertEquals(1, nextRelayIndex(relays, 0, { listOf("event") }, deferred, emptyMap(), 20_000))
            }
            assertEquals(relays, attempted)
            assertEquals(setOf(relays[1], relays[2]), accepted)
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            servers.forEach { it.shutdown() }
        }
    }

    @Test fun oldDefaultsGainPdsFirstOnlyOnce() {
        val old = listOf("wss://relay.damus.io", "wss://nos.lol")
        val expected = listOf("wss://pds.oriom.dev", "wss://relay.damus.io", "wss://nos.lol")
        assertEquals(expected, migrateDefaultRelays(old, migrated = false))
        assertEquals(expected, DiskState.DEFAULT_RELAYS)
        assertEquals(expected, migrateDefaultRelays(expected, migrated = true))
        // Removing PDS after migration must survive the next load.
        assertEquals(old, migrateDefaultRelays(old, migrated = true))
    }

    @Test fun relayMigrationPreservesCustomRemovedAndFullLists() {
        val old = listOf("wss://relay.damus.io", "wss://nos.lol")
        for (relays in listOf(emptyList(), old.take(1), old.reversed(), old + "wss://custom.invalid",
                listOf("wss://pds.oriom.dev") + old, List(8) { "wss://custom$it.invalid" })) {
            assertSame(relays, migrateDefaultRelays(relays, migrated = false))
        }
        assertSame(old, migrateDefaultRelays(old, migrated = false, maxRelays = 2))
    }

    @Test fun connectionCooldownAndEventDeferralAreRelayLocal() {
        val relays = listOf("general", "pds", "other")
        val pending: (String) -> List<String> = { listOf("a", "b") }
        val deferred = mapOf("general" to mapOf("a" to 30_000L, "b" to 30_000L))
        val retryAt = mapOf("pds" to 30_000L)
        assertEquals(2, nextRelayIndex(relays, 0, pending, deferred, retryAt, 20_000))
        assertEquals(1, nextRelayIndex(relays, 1, pending, deferred, retryAt, 30_000))
        assertEquals(0, nextRelayIndex(relays, 2, { if (it == "other") emptyList() else pending(it) },
            emptyMap(), emptyMap(), 20_000))
        assertNull(nextRelayIndex(relays, 0, pending, deferred,
            mapOf("pds" to 30_000L, "other" to 30_000L), 20_000))
        assertNull(nextRelayIndex(emptyList(), 0, pending, emptyMap(), emptyMap(), 20_000))
        // Last-attempt rotation is passed independently for each relay by the coordinator.
        assertEquals("b", nextRelayId(pending("general"), "a", deferred["general"].orEmpty(), 30_000))
        assertEquals("a", nextRelayId(pending("pds"), null, deferred["pds"].orEmpty(), 20_000))
    }

    @Test fun failedFirstEventDoesNotStarveLaterOnes() {
        val pending = listOf("a", "b")
        assertEquals("b", nextRelayId(pending, "a", mapOf("a" to 30_000), 20_000))
        assertEquals(listOf("a", "b"), pending)
    }
}
