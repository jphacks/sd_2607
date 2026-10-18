package org.mndt.mesh

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.mndt.core.Author
import org.mndt.core.PublicIngress
import org.mndt.core.SecretKeys
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PublicDownloadTest {
    private val event by lazy { Author(SecretKeys.generate(), "https://pds.invalid").createPost("Relay test", 1792368000000).event }

    @Test fun priorityVerbExactObjectAndDistinctBoundedOrdinaryFallback() {
        val p = json.readTree(PublicDownload.request("p", true))
        assertEquals("MOSIMO-REQ", p[0].textValue())
        assertEquals(setOf("order", "filter"), p[2].fieldNames().asSequence().toSet())
        assertEquals("priority-v1", p[2]["order"].textValue())
        val f = json.readTree(PublicDownload.request("r", false))
        assertEquals("REQ", f[0].textValue())
        assertEquals(p[2]["filter"], f[2])
        assertEquals(32, f[2]["limit"].intValue())
        assertEquals("mosimo.oriom.dev", f[2]["#t"][0].textValue())
    }

    private fun fetch(mode: String): Pair<PublicDownload.Batch, List<String>> = runBlocking {
        val seen = LinkedBlockingQueue<String>()
        val server = MockWebServer()
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = json.readTree(text); val verb = frame[0].textValue(); val id = frame[1].textValue()
                seen.add(text)
                if (verb == "CLOSE") return
                if (verb == "MOSIMO-REQ" && mode == "closed") { webSocket.send("[\"CLOSED\",\"$id\",\"unsupported\"]"); return }
                if (verb == "MOSIMO-REQ" && mode == "timeout") return
                if (verb == "MOSIMO-REQ" && mode == "disconnect") { webSocket.close(1000, "unsupported"); return }
                webSocket.send("[\"EVENT\",\"$id\",${event.toJson()}]")
                webSocket.send("[\"EOSE\",\"$id\"]")
            }
        }
        repeat(2) { server.enqueue(MockResponse().withWebSocketUpgrade(listener)) }
        val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        try {
            val batch = PublicDownload.fetch(http, 7, server.url("/").toString(), 500)
            val requests = mutableListOf<String>()
            while (requests.size < if (mode == "priority") 1 else 2) {
                val frame = checkNotNull(seen.poll(2, TimeUnit.SECONDS))
                if (json.readTree(frame)[0].textValue() != "CLOSE") requests += frame
            }
            batch to requests
        } finally { server.close(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    }

    @Test fun eoseFinitePriorityRequestPreservesSignedOriginal() {
        val (batch, requests) = fetch("priority")
        assertFalse(batch.fallback)
        assertEquals(listOf(event), batch.events)
        assertEquals("MOSIMO-REQ", json.readTree(requests.first())[0].textValue())
    }

    @Test fun closedAndSilentNonSupportFallBackToStandardReqWithDifferentId() {
        for (mode in listOf("closed", "timeout", "disconnect")) {
            val (batch, requests) = fetch(mode)
            assertTrue(batch.fallback)
            assertEquals(listOf(event), batch.events)
            val frames = requests.map(json::readTree)
            assertEquals(listOf("MOSIMO-REQ", "REQ"), frames.map { it[0].textValue() })
            assertNotEquals(frames[0][1].textValue(), frames[1][1].textValue())
        }
    }

    @Test fun bothTimeoutsAreFailureNotEmptySuccess() = runBlocking {
        val server = MockWebServer()
        repeat(2) { server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {})) }
        val http = OkHttpClient()
        try {
            assertTrue(runCatching { PublicDownload.fetch(http, 9, server.url("/").toString(), 100) }.isFailure)
        } finally { server.close(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    }

    @Test fun finiteRequestDoesNotHaveUnboundedEventOrFrameCapacity() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = json.readTree(text)
                if (frame[0].textValue() != "MOSIMO-REQ") return
                webSocket.send("x".repeat(RelaySubscription.MAX_FRAME_BYTES + 1))
            }
        }))
        val http = OkHttpClient()
        try {
            assertTrue(runCatching { PublicDownload.fetch(http, 9, server.url("/").toString(), 500) }.isFailure)
        } finally { server.close(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    }

    @Test fun snapshotTransportRequiresCborSuccessHttpsAndBoundedBody() {
        val root = PublicIngress.Root("00".repeat(32), "", true, PublicIngress.Scope(emptySet(), emptySet()), "https://trust.example.invalid/snapshot.cbor", 1)
        fun client(status: Int, contentType: String, bytes: ByteArray) = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test")
                .body(bytes.toResponseBody(contentType.toMediaType())).build() }.build()
        assertArrayEquals(byteArrayOf(1, 2), PublicDownload.snapshot(client(200, "application/cbor", byteArrayOf(1, 2)), root))
        for ((status, type, bytes) in listOf(Triple(302, "application/cbor", byteArrayOf(1)), Triple(200, "application/json", byteArrayOf(1)),
            Triple(200, "application/cbor", ByteArray(32769))))
            assertThrows(Exception::class.java) { PublicDownload.snapshot(client(status, type, bytes), root) }
        assertThrows(Exception::class.java) { PublicDownload.snapshot(client(200, "application/cbor", byteArrayOf(1)), root.copy(snapshotUrl = "http://trust.example.invalid/snapshot.cbor")) }
    }

    @Test fun encryptedStateHistoryIsOptionalButMalformedHistoryNeverResetsIt() {
        assertEquals(PublicIngress.Memory(), readPublicTrust(null))
        val n = json.createObjectNode()
        val id = "ab".repeat(32)
        n.putObject("snapshots").put(id, Base64.getEncoder().encodeToString(byteArrayOf(1, 2)))
        val serial = "02" + "ab".repeat(32) + ":" + "cd".repeat(16)
        n.putObject("serials").put(serial, "ef".repeat(32))
        val old = readPublicTrust(n)
        val roundTrip = readPublicTrust(json.readTree(json.writeValueAsBytes(n)))
        assertArrayEquals(old.snapshots[id], roundTrip.snapshots[id])
        assertEquals(old.serials, roundTrip.serials)
        n.withObject("serials").put(serial, "equivocation")
        assertEquals("equivocation", readPublicTrust(n).serials[serial])
        n.withObject("serials").put(serial, "bad")
        assertThrows(Exception::class.java) { readPublicTrust(n) }
    }
}
