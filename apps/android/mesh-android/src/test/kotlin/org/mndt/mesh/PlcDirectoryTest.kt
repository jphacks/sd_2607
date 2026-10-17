package org.mndt.mesh

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class PlcDirectoryTest {
    // Public, synthetic HTTP fixtures: this suite does not sign or register real identities.
    private val did = "did:plc:" + "a".repeat(24)
    private val genesis = """{"type":"plc_operation","rotationKeys":["did:key:zLocalManagement"],"verificationMethods":{"atproto":"did:key:zLocalRepository"},"alsoKnownAs":[],"services":{"atproto_pds":{"type":"AtprotoPersonalDataServer","endpoint":"https://saved-pds.example"}},"prev":null,"sig":"fixture"}"""
    private val client = OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(1, TimeUnit.SECONDS).callTimeout(2, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    private fun data(operation: String = genesis, identity: String = did): String =
        json.readTree(operation).also { (it as com.fasterxml.jackson.databind.node.ObjectNode)
            .remove(listOf("type", "prev", "sig")); it.put("did", identity) }.toString()

    private fun withServer(log: String, current: String = data(), dataCode: Int = 200,
                           block: (MockWebServer) -> Unit) {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.method == "POST" -> MockResponse().setResponseCode(409)
                    request.path == "/$did/log" -> MockResponse().setBody(log)
                    request.path == "/$did/data" -> MockResponse().setResponseCode(dataCode).setBody(current)
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            block(server)
        }
    }

    @Test fun matchingGenesisChecksCurrentSavedKeysAndPdsWithoutSubmitting() {
        withServer("[$genesis]") { server ->
            val result = PlcDirectory(client).check(server.url("/"), did, genesis, false)
            assertTrue(result.genesisConfirmed)
            assertEquals(PlcCurrentState.CHECKED, result.current)
            assertNotNull(result.checkedAtMillis)
            assertEquals("GET", checkNotNull(server.takeRequest(3, TimeUnit.SECONDS)).method)
            assertEquals("/$did/data", checkNotNull(server.takeRequest(3, TimeUnit.SECONDS)).path)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun historicalRegistrationNeverSkipsLaterKeyPdsOrManagementChanges() {
        val changes = listOf(
            genesis.replace("zLocalRepository", "zChangedRepository"),
            genesis.replace("saved-pds.example", "changed-pds.example"),
            genesis.replace("zLocalManagement", "zChangedManagement")
        )
        changes.forEach { changed ->
            val update = changed.replace("\"prev\":null", "\"prev\":\"bPreviousOperation\"")
            withServer("[$genesis,$update]", data(update)) { server ->
                val checker = PlcDirectory(client)
                val result = checker.check(server.url("/"), did, genesis, true)
                assertTrue(result.genesisConfirmed)
                assertEquals(PlcCurrentState.CHANGED, result.current)
                assertEquals("GET", checkNotNull(server.takeRequest(3, TimeUnit.SECONDS)).method)
                assertEquals("/$did/data", checkNotNull(server.takeRequest(3, TimeUnit.SECONDS)).path)
            }
        }
    }

    @Test fun tombstoneMustBeExplicitNotAnyResolutionFailure() {
        val tombstone = """{"type":"plc_tombstone","prev":"bPreviousOperation","sig":"fixture"}"""
        withServer("[$genesis,$tombstone]", "not available", 404) { server ->
            val result = PlcDirectory(client).check(server.url("/"), did, genesis, true)
            assertTrue(result.genesisConfirmed)
            assertEquals(PlcCurrentState.TOMBSTONED, result.current)
        }
        withServer("[$genesis]", "not available", 404) { server ->
            val result = PlcDirectory(client).check(server.url("/"), did, genesis, true)
            assertTrue(result.genesisConfirmed)
            assertEquals(PlcCurrentState.UNKNOWN, result.current)
            assertNull(result.checkedAtMillis)
        }
    }

    @Test fun failedResolutionOrRacingLogNeverInheritsCheckedState() {
        val badData = listOf("{}", "null", data(identity = "did:plc:" + "b".repeat(24)),
            data().replace("saved-pds.example", "racing-update.example"), data() + "{}")
        badData.forEach { current ->
            withServer("[$genesis]", current) { server ->
                val result = PlcDirectory(client).check(server.url("/"), did, genesis, true)
                assertTrue(result.genesisConfirmed)
                assertEquals(PlcCurrentState.UNKNOWN, result.current)
            }
        }
        assertEquals(PlcCurrentState.UNKNOWN, PlcObservation(true).current)
    }

    @Test fun untrustedInputIsBoundedAndCannotConfirmCurrentAuthority() {
        val badLogs = listOf("[]", "[{}]", "[${genesis.replace("fixture", "wrong-genesis")}]",
            "[" + List(129) { genesis }.joinToString(",") + "]",
            " ".repeat(262145), "[".repeat(18) + "0" + "]".repeat(18),
            "[$genesis]{}", "[{\"type\":\"plc_operation\",\"type\":\"plc_tombstone\"}]")
        badLogs.forEach { log ->
            withServer(log) { server ->
                val result = PlcDirectory(client).check(server.url("/"), did, genesis, false)
                assertFalse(result.genesisConfirmed)
                assertEquals(PlcCurrentState.UNKNOWN, result.current)
                assertEquals(1, server.requestCount)
            }
        }
        withServer("[$genesis]", " ".repeat(262145)) { server ->
            val result = PlcDirectory(client).check(server.url("/"), did, genesis, false)
            assertTrue(result.genesisConfirmed)
            assertEquals(PlcCurrentState.UNKNOWN, result.current)
        }
    }

    @Test fun duplicateSubmissionResponseIsNotAuthorityAndSuccessAloneIsNotConfirmation() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(409))
            server.enqueue(MockResponse().setBody("[$genesis]"))
            server.enqueue(MockResponse().setBody(data()))
            server.start()
            val result = PlcDirectory(client).check(server.url("/"), did, genesis, false, allowRegistration = true)
            assertEquals(PlcCurrentState.CHECKED, result.current)
            assertTrue(result.genesisConfirmed)
            assertEquals("GET", checkNotNull(server.takeRequest(3, TimeUnit.SECONDS)).method)
            val submitted = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("POST", submitted.method)
            assertEquals(genesis, submitted.body.readUtf8())
            assertEquals("/$did/log", checkNotNull(server.takeRequest(3, TimeUnit.SECONDS)).path)
            assertEquals("/$did/data", checkNotNull(server.takeRequest(3, TimeUnit.SECONDS)).path)
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(MockResponse().setResponseCode(404))
            server.start()
            val result = PlcDirectory(client).check(server.url("/"), did, genesis, false, allowRegistration = true)
            assertFalse(result.genesisConfirmed)
            assertEquals(PlcCurrentState.UNKNOWN, result.current)
        }
    }

    @Test fun missingRegistrationIsReadOnlyUnlessExplicitlyEnabled() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.start()
            val result = PlcDirectory(client).check(server.url("/"), did, genesis, false)
            assertFalse(result.genesisConfirmed)
            assertEquals(PlcCurrentState.UNKNOWN, result.current)
            assertEquals("GET", checkNotNull(server.takeRequest(3, TimeUnit.SECONDS)).method)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun directoryFailureNeverReRegistersHistoricalIdentity() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(404))
            server.start()
            val checker = PlcDirectory(client)
            repeat(2) {
                val result = checker.check(server.url("/"), did, genesis, true)
                assertTrue(result.genesisConfirmed)
                assertEquals(PlcCurrentState.UNKNOWN, result.current)
                assertEquals("GET", checkNotNull(server.takeRequest(3, TimeUnit.SECONDS)).method)
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun repeatedObservationDropsOldCurrentStateOnHttpOrNetworkFailure() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("[$genesis]"))
        server.enqueue(MockResponse().setBody(data()))
        server.enqueue(MockResponse().setResponseCode(503))
        server.start()
        val directory = server.url("/")
        val checker = PlcDirectory(client)
        try {
            val first = checker.check(directory, did, genesis, false)
            assertEquals(PlcCurrentState.CHECKED, first.current)
            val unavailable = checker.check(directory, did, genesis, first.genesisConfirmed)
            assertTrue(unavailable.genesisConfirmed)
            assertEquals(PlcCurrentState.UNKNOWN, unavailable.current)
            assertNull(unavailable.checkedAtMillis)
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
        val offline = checker.check(directory, did, genesis, true)
        assertTrue(offline.genesisConfirmed)
        assertEquals(PlcCurrentState.UNKNOWN, offline.current)
        assertNull(offline.checkedAtMillis)
        // Restoring the old disk boolean similarly conveys history, never current authority.
        assertEquals(PlcCurrentState.UNKNOWN, PlcObservation(true).current)
    }
}
