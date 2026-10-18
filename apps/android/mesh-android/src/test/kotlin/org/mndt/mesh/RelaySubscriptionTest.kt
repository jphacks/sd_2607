package org.mndt.mesh

import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.mndt.core.Author
import org.mndt.core.MeshEngine
import org.mndt.core.NostrEvent
import org.mndt.core.SecretKeys
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RelaySubscriptionTest {
    private val author by lazy { Author(SecretKeys.generate(), "https://pds.invalid") }
    private fun event(index: Int = 0, large: Boolean = false): NostrEvent = author.createPost("本文 $index", 1_792_195_200_000L + index * 1000L,
        extensions = listOf(mapOf("\$type" to "example.unknown.extension", "opaque" to if (large) "x".repeat(150_000) else "unknown\n日本語"))).event
    private fun frame(session: RelaySubscription, event: NostrEvent) = "[\"EVENT\",\"${session.subscriptionId}\",${event.toJson()}]"
    private fun opened() = RelaySubscription().also { it.open(1) }

    @Test fun requestUsesStandardBoundedFilterAndCloseIsIdempotent() {
        val session = RelaySubscription()
        val req = json.readTree(session.open(7))
        assertEquals("REQ", req[0].textValue())
        assertEquals("mndt-7", req[1].textValue())
        assertEquals(1, req[2]["kinds"][0].intValue())
        assertEquals(RelaySubscription.TAG, req[2]["#t"][0].textValue())
        assertEquals(32, req[2]["limit"].intValue())
        assertEquals("[\"CLOSE\",\"mndt-7\"]", session.close())
        assertNull(session.close())
    }

    @Test fun verifiedEventsPreserveUnknownTagsAndNeverEnterMesh() {
        val session = opened()
        val original = event()
        val mesh = MeshEngine()
        assertEquals(RelayReply.Event(original), session.receive(1, frame(session, original), 0))
        assertEquals(listOf(original), session.events())
        assertEquals(original.toJson(), session.events().single().toJson())
        assertTrue(mesh.entries().isEmpty())
        // Historical delivery expiry is not relay retrieval expiry.
        assertTrue(original.tags.any { it.first().contains("example.unknown.extension") })
    }

    @Test fun invalidIdSignatureAndMalformedJsonAreNeverRetained() {
        val session = opened()
        val good = event()
        val bad = listOf(
            frame(session, good).replace(good.id, "0".repeat(64)),
            frame(session, good).replace(good.sig, "0".repeat(128)),
            frame(session, good).replace("\"created_at\":", "\"created_at\":0,\"created_at\":"),
            frame(session, good) + "[]",
            "[\"EVENT\",\"${session.subscriptionId}\",{}]",
            "[\"EOSE\",\"${session.subscriptionId}\",1]",
            "[\"CLOSED\",\"${session.subscriptionId}\",false]"
        )
        bad.forEach { assertNull(session.receive(1, it, 0)) }
        assertTrue(session.events().isEmpty())
        assertFalse(session.eose)
        assertNotNull(session.subscriptionId)
    }

    @Test fun wrongSubscriptionOrGenerationCannotDisplayOrEndLiveSession() {
        val session = opened()
        val first = frame(session, event())
        assertNull(session.receive(1, first.replace("mndt-1", "other"), 0))
        session.disconnect()
        session.open(2)
        assertNull(session.receive(1, first, 0))
        assertNull(session.receive(2, first, 0))
        assertNull(session.receive(1, "[\"CLOSED\",\"mndt-2\",\"old\"]", 0))
        assertEquals("mndt-2", session.subscriptionId)
        assertTrue(session.events().isEmpty())
    }

    @Test fun eoseKeepsLiveEventsAndClosedStopsOnlyMatchingSubscription() {
        val session = opened()
        assertEquals(RelayReply.Eose, session.receive(1, "[\"EOSE\",\"mndt-1\"]", 0))
        assertTrue(session.eose)
        assertNotNull(session.receive(1, frame(session, event()), 0))
        assertNull(session.receive(1, "[\"CLOSED\",\"other\",\"blocked\"]", 0))
        val old = frame(session, event(1))
        assertEquals(RelayReply.Closed, session.receive(1, "[\"CLOSED\",\"mndt-1\",\"blocked\"]", 0))
        assertNull(session.subscriptionId)
        assertNull(session.receive(1, old, 0))
        assertEquals(RelayReply.Ok("id", true), session.receive(1, "[\"OK\",\"id\",true,\"saved\"]", 0))
        assertEquals(1, session.events().size)
    }

    @Test fun displayDedupWorksForBothMeshArrivalOrders() {
        val session = opened()
        val a = event()
        assertNull(session.receive(1, frame(session, a), 0, setOf(a.id)))
        assertTrue(session.events().isEmpty())
        session.receive(1, frame(session, a), 0)
        assertNull(session.receive(1, frame(session, a), 0))
        val mesh = PostView(a.id, a.pubkey, a.content, 3, false)
        assertEquals(listOf(mesh), mergePostViews(listOf(mesh), session.events(), "ws://relay"))
        val online = mergePostViews(emptyList(), session.events(), "ws://relay").single()
        assertTrue(online.volatile)
        assertEquals("ws://relay", online.sourceRelay)
        assertNull(online.hop)
        assertFalse(online.relayAccepted)
    }

    @Test fun sizeLimitsIncludeUtf8AndEscapedEventCanExceedNearbyCap() {
        val session = opened()
        assertTrue(runCatching { session.receive(1, "x".repeat(RelaySubscription.MAX_FRAME_BYTES + 1), 0) }.isFailure)
        assertTrue(runCatching { session.receive(1, "日".repeat(90_000), 0) }.isFailure)
        val original = event(large = true)
        val text = frame(session, original)
        assertTrue(text.toByteArray().size > Wire.MAX_BYTES)
        assertNotNull(session.receive(1, text, 0))
    }

    @Test fun perAuthorLimitAndGlobalFrameRateSurviveReconnect() {
        val session = opened()
        repeat(12) { assertNotNull(session.receive(1, frame(session, event(it)), 0)) }
        assertNull(session.receive(1, frame(session, event(12)), 0))
        session.disconnect(); session.open(2)
        assertNull(session.receive(2, frame(session, event(12)), 0))
        assertNotNull(session.receive(2, frame(session, event(12)), 60_000))
        val flood = opened()
        repeat(RelaySubscription.MAX_FRAMES_PER_MINUTE) { flood.receive(1, "[\"NOTICE\",\"ignored\"]", 0) }
        flood.disconnect(); flood.open(2)
        assertTrue(runCatching { flood.receive(2, "[]", 0) }.isFailure)
        assertNull(flood.receive(2, "[]", 60_000))
    }

    @Test fun backlogOksDoNotSpendTheReceiveWindow() {
        val session = opened()
        val ok = "[\"OK\",\"${"a".repeat(64)}\",true,\"\"]"
        repeat(RelaySubscription.MAX_FRAMES_PER_MINUTE * 2) { assertEquals(RelayReply.Ok("a".repeat(64), true), session.receive(1, ok, 0)) }
        assertNull(session.receive(1, "[\"NOTICE\",\"still within window\"]", 0))
    }

    @Test fun onlyUntouchedOldDefaultRelaysMigrate() {
        assertEquals("wss://pds.oriom.dev", migrateDefaultRelays(listOf("wss://relay.damus.io", "wss://nos.lol"), migrated = false).first())
        assertEquals(listOf("wss://nos.lol"), migrateDefaultRelays(listOf("wss://nos.lol"), migrated = false))
        assertEquals(listOf("wss://nos.lol", "wss://relay.damus.io"), migrateDefaultRelays(listOf("wss://nos.lol", "wss://relay.damus.io"), migrated = false))
    }

    @Test fun eventCountCapClosesRetrievalWithoutEvictionOrMeshInsertion() {
        val session = opened()
        repeat(RelaySubscription.MAX_EVENTS) { index ->
            val original = event(index)
            assertEquals(RelayReply.Event(original), session.receive(1, frame(session, original), index * 60_000L))
        }
        assertEquals(64, session.events().size)
        assertEquals(RelayReply.Full, session.receive(1, frame(session, event(64)), 64 * 60_000L))
        assertNotNull(session.close())
        session.disconnect()
        assertNull(session.open(2))
        assertEquals(64, session.events().size)
        session.clear()
        assertTrue(session.events().isEmpty())
        assertNotNull(session.open(3))
    }

    @Test fun totalByteCapIsEnforcedBeforeCountCap() {
        val session = opened()
        var stopped = false
        for (index in 0 until 20) {
            if (session.receive(1, frame(session, event(index, large = true)), index * 60_000L) == RelayReply.Full) { stopped = true; break }
        }
        assertTrue(stopped)
        assertTrue(session.events().size < RelaySubscription.MAX_EVENTS)
        assertTrue(session.events().sumOf { it.toJson().toByteArray().size } <= RelaySubscription.MAX_BYTES)
    }

    @Test fun localWebSocketCarriesReqHistoricalEoseLiveEventAndClose() {
        val session = RelaySubscription()
        val original = event()
        val live = event(1)
        val replies = LinkedBlockingQueue<RelayReply>()
        val serverFrames = LinkedBlockingQueue<String>()
        val client = OkHttpClient()
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                serverFrames.add(text)
                val req = json.readTree(text)
                if (req[0].textValue() == "REQ") {
                    val id = req[1].textValue()
                    webSocket.send("[\"EVENT\",\"$id\",${original.toJson()}]")
                    webSocket.send("[\"EOSE\",\"$id\"]")
                    webSocket.send("[\"EVENT\",\"wrong\",${original.toJson()}]")
                    webSocket.send("[\"EVENT\",\"$id\",${live.toJson()}]")
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        }))
        server.start()
        try {
            val ws = client.newWebSocket(Request.Builder().url(server.url("/relay")).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(checkNotNull(session.open(42))) }
                override fun onMessage(webSocket: WebSocket, text: String) { session.receive(42, text, 0)?.let(replies::add) }
            })
            assertEquals("REQ", json.readTree(serverFrames.poll(5, TimeUnit.SECONDS))[0].textValue())
            assertEquals(RelayReply.Event(original), replies.poll(5, TimeUnit.SECONDS))
            assertEquals(RelayReply.Eose, replies.poll(5, TimeUnit.SECONDS))
            assertEquals(RelayReply.Event(live), replies.poll(5, TimeUnit.SECONDS))
            ws.send(checkNotNull(session.close()))
            assertEquals("[\"CLOSE\",\"mndt-42\"]", serverFrames.poll(5, TimeUnit.SECONDS))
            session.disconnect()
            ws.close(1000, null)
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            server.close()
        }
    }

    @Test fun localReconnectUsesNewSubscriptionAndClosedDoesNotSwallowPostingOk() {
        val session = RelaySubscription()
        val replies = LinkedBlockingQueue<RelayReply>()
        val serverFrames = LinkedBlockingQueue<String>()
        val client = OkHttpClient()
        val server = MockWebServer()
        repeat(2) {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    serverFrames.add(text)
                    val req = json.readTree(text)
                    if (req[0].textValue() == "REQ") {
                        val id = req[1].textValue()
                        webSocket.send("[\"CLOSED\",\"$id\",\"restricted: local test\"]")
                        webSocket.send("[\"OK\",\"posted-id\",false,\"blocked: local test\"]")
                    }
                }
            }))
        }
        server.start()
        try {
            for (generation in 1L..2L) {
                val ws = client.newWebSocket(Request.Builder().url(server.url("/relay")).build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(checkNotNull(session.open(generation))) }
                    override fun onMessage(webSocket: WebSocket, text: String) { session.receive(generation, text, 0)?.let(replies::add) }
                })
                assertEquals("mndt-$generation", json.readTree(serverFrames.poll(5, TimeUnit.SECONDS))[1].textValue())
                assertEquals(RelayReply.Closed, replies.poll(5, TimeUnit.SECONDS))
                assertEquals(RelayReply.Ok("posted-id", false), replies.poll(5, TimeUnit.SECONDS))
                assertNull(session.subscriptionId)
                assertNull(session.close())
                session.disconnect()
                assertNull(session.receive(generation, "[\"OK\",\"posted-id\",true,\"late\"]", 0))
                ws.cancel()
            }
            session.clear()
            assertTrue(session.events().isEmpty())
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            server.close()
        }
    }
}
