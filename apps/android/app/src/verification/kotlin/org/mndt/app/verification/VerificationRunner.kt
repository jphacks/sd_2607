package org.mndt.app.verification

import android.app.Activity
import android.app.Instrumentation
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.mndt.mesh.MeshCoordinator
import org.mndt.mesh.MeshState
import java.io.File

/** Same-APK native instrumentation; only MeshCoordinator's public API is used. */
class VerificationRunner : Instrumentation() {
    private var arguments = Bundle()
    private var collector: Job? = null
    @Volatile private var captureFailed = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        var mesh: MeshCoordinator? = null
        var evidence: PublicEvidence? = null
        var failure: String? = null
        val phase = arguments.getString("phase").orEmpty()
        try {
            ensure(targetContext.packageName == PACKAGE, "Wrong target package")
            ensure(phase == "queue" || phase == "observe", "phase must be queue or observe")
            val run = arguments.getString("run").orEmpty()
            ensure(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}").matches(run), "run must be 1-80 ASCII letters, digits, _ or -")
            val coordinator = MeshCoordinator.get(targetContext)
            mesh = coordinator
            await(coordinator, "Coordinator ready", 30_000) { it.ready }
            ensure(!coordinator.state.value.running, "Mesh service must not be running")
            coordinator.setOnlineForeground(false)
            await(coordinator, "Online foreground off") { !it.onlineActive }
            if (phase == "queue") requireOffline()
            val record = PublicEvidence(run, coordinator.state.value)
            evidence = record
            record.restore(coordinator.state.value, requireTwo = phase == "observe")
            record.phase = phase
            record.result = "running"
            record.save()
            // Unconfined collection runs at state emission, not once per second: the coordinator
            // retains only 16 diagnostic lines. Never copy the whole state or private disk store.
            collector = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.state.collect { state ->
                    try { record.capture(state) } catch (_: Exception) { captureFailed = true }
                }
            }
            coordinator.setOnlineEnabled(true)
            await(coordinator, "Online policy enabled", guard = { if (phase == "queue") requireOffline() }) { it.onlineEnabled }
            if (phase == "queue") queue(coordinator, record) else observe(coordinator, record)
        } catch (e: Exception) {
            failure = if (e is VerificationFailure) e.message else "Runner failed (${e.javaClass.simpleName})"
        } finally {
            try {
                mesh?.let { coordinator ->
                    coordinator.setOnlineForeground(false)
                    await(coordinator, "Final online foreground off") { !it.onlineActive }
                    // tick() persists dirty relay receipts every second. No private-store readback.
                    SystemClock.sleep(2_200)
                    checkHealthy(coordinator.state.value)
                    evidence?.capture(coordinator.state.value)
                }
                ensure(!captureFailed, "Public evidence capture failed")
            } catch (_: Exception) {
                failure = failure ?: "Could not confirm final foreground-off / persistence tick"
            }
            collector?.cancel()
            try {
                evidence?.let {
                    it.result = if (failure == null) "success" else "failed"
                    it.failure = failure
                    it.save()
                }
            } catch (_: Exception) { failure = failure ?: "Could not save public evidence" }
        }
        val summary = JSONObject().put("phase", phase).put("result", if (failure == null) "success" else "failed")
            .put("publicFile", "files/verification-public.json")
        failure?.let { summary.put("failure", it) }
        stream(summary.toString())
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", summary.toString() + "\n") })
    }

    private fun queue(mesh: MeshCoordinator, evidence: PublicEvidence) {
        for (content in evidence.contents) {
            requireOffline()
            evidence.restore(mesh.state.value, requireTwo = false)
            if (mesh.state.value.posts.any { it.content == content && it.pubkey == mesh.state.value.publicKey && !it.volatile && it.sourceRelay == null }) continue
            val before = mesh.state.value.publishAttempt
            mesh.publish(content)
            await(mesh, "Publish completed", 30_000, ::requireOffline) { it.publishAttempt > before }
            ensure(mesh.state.value.posts.any { it.content == content && it.id == mesh.state.value.lastPublishedId }, "Publish did not retain the expected event")
            evidence.restore(mesh.state.value, requireTwo = false)
            evidence.save()
        }
        requireOffline()
        evidence.restore(mesh.state.value, requireTwo = true)
        ensure(mesh.state.value.onlineEnabled && !mesh.state.value.onlineActive, "Queue must keep online policy enabled but IO inactive")
        stream("queue: two signed events retained; no mesh service or radios started")
    }

    private fun observe(mesh: MeshCoordinator, evidence: PublicEvidence) {
        // IDs, content and author must match the queue ledger AND restored local posts.
        evidence.restore(mesh.state.value, requireTwo = true)
        ensure(PDS in mesh.state.value.relays && mesh.state.value.relays.any { it != PDS }, "PDS and another relay must be configured")
        mesh.setOnlineForeground(true)
        val deadline = SystemClock.elapsedRealtime() + 180_000
        var nextProgress = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = mesh.state.value
            checkHealthy(state)
            ensure(!captureFailed, "Public evidence capture failed")
            evidence.capture(state)
            if (evidence.delivered()) {
                stream("observe: both events OK at PDS and nos.lol")
                return
            }
            if (SystemClock.elapsedRealtime() >= nextProgress) {
                evidence.save()
                stream(evidence.snapshot().toString())
                nextProgress = SystemClock.elapsedRealtime() + 5_000
            }
            SystemClock.sleep(100)
        }
        throw VerificationFailure("180s elapsed without both-event PDS and nos.lol OK evidence")
    }

    @Suppress("DEPRECATION")
    private fun requireOffline() {
        val manager = targetContext.getSystemService(ConnectivityManager::class.java)
        // A VPN tunnel can remain listed in airplane mode without an underlying network.
        // Still reject any default route or physical/local network, even if unvalidated.
        val physicalNetworks = manager.allNetworks.filter {
            manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true
        }
        val active = manager.activeNetwork
        val onlyVpnDefault = active != null && manager.getNetworkCapabilities(active)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        ensure((active == null || onlyVpnDefault) && physicalNetworks.isEmpty(),
            "queue requires no physical/local networks (physical=${physicalNetworks.size}, vpnDefault=$onlyVpnDefault)")
    }

    private fun await(mesh: MeshCoordinator, description: String, timeout: Long = 10_000,
        guard: () -> Unit = {}, predicate: (MeshState) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        do {
            guard()
            val state = mesh.state.value
            checkHealthy(state)
            ensure(!captureFailed, "Public evidence capture failed")
            if (predicate(state)) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw VerificationFailure("Timed out: $description")
    }

    private fun checkHealthy(state: MeshState) {
        ensure(state.error == null, "Coordinator reported an error; no additional posts attempted")
        ensure(!state.running, "Mesh service unexpectedly running")
    }

    private fun stream(text: String) = sendStatus(0, Bundle().apply { putString("stream", text + "\n") })

    private inner class PublicEvidence(val run: String, initial: MeshState) {
        val contents = listOf(
            "TEST ONLY - NOT DISASTER INFORMATION. Android MeshCoordinator verification run=$run step=1 initial.",
            "TEST ONLY - NOT DISASTER INFORMATION. Android MeshCoordinator verification run=$run step=2 continuation."
        )
        private val did = initial.did
        private val pubkey = initial.publicKey
        private val ids = arrayOfNulls<String>(2)
        private val receipts = linkedMapOf<String, MutableSet<String>>()
        private val receiptTimes = mutableMapOf<Pair<String, String>, Long>()
        private var plc = initial.registration
        private val ledger = File(targetContext.filesDir, "verification/$run.json")
        var phase = ""
        var result = "running"
        var failure: String? = null

        init {
            if (ledger.exists()) {
                ensure(ledger.length() <= 65_536, "Public ledger too large")
                val saved = JSONObject(AtomicFile(ledger).openRead().bufferedReader().use { it.readText() })
                ensure(saved.getString("run") == run && saved.getString("did") == did && saved.getString("pubkey") == pubkey, "Public ledger identity mismatch")
                val events = saved.getJSONArray("events")
                ensure(events.length() == 2, "Public ledger must describe two events")
                for (i in 0..1) {
                    val event = events.getJSONObject(i)
                    ensure(event.getString("content") == contents[i], "Public ledger content mismatch")
                    if (!event.isNull("id")) ids[i] = event.getString("id").also {
                        ensure(Regex("[0-9a-f]{64}").matches(it), "Invalid public event ID")
                    }
                }
                val accepted = saved.getJSONArray("relayOk")
                for (i in 0 until accepted.length()) {
                    val receipt = accepted.getJSONObject(i)
                    val relay = receipt.getString("relay")
                    val id = receipt.getString("eventId")
                    ensure(relay in initial.relays && id in ids, "Public receipt does not match restored configuration")
                    receipts.getOrPut(relay) { linkedSetOf() }.add(id)
                    receiptTimes[relay to id] = receipt.getLong("observedAtMillis")
                }
            } else {
                ensure(arguments.getString("phase") == "queue", "Missing queue public ledger; observe will not create posts")
                ensure(initial.posts.none { it.pubkey == pubkey && !it.volatile },
                    "A new run needs a fresh test identity; existing author history must not be extended")
            }
        }

        @Synchronized fun restore(state: MeshState, requireTwo: Boolean) {
            ensure(state.did == did && state.publicKey == pubkey, "Public identity changed")
            val matching = state.posts.filter { "verification run=$run " in it.content }
            ensure(matching.all { it.content in contents && it.pubkey == pubkey && !it.volatile && it.sourceRelay == null }, "Unexpected event with this run marker")
            for (i in 0..1) {
                val posts = matching.filter { it.content == contents[i] }
                ensure(posts.size <= 1, "Duplicate retained event for run/step; refusing to post")
                val post = posts.singleOrNull()
                if (ids[i] != null) ensure(post?.id == ids[i], "Previously recorded event missing or changed; refusing to republish")
                if (post != null) ids[i] = post.id
                if (requireTwo) ensure(post != null, "Both queued events must be restored from local state")
            }
            val present = ids.filterNotNull()
            ensure(present.map { it.take(8) }.distinct().size == present.size, "Diagnostic event prefixes collide")
            ensure(state.posts.none { it.id !in present && it.id.take(8) in present.map { id -> id.take(8) } }, "Diagnostic prefix collides with another event")
        }

        @Synchronized fun capture(state: MeshState) {
            var changed = false
            if (plc != state.registration) { plc = state.registration; changed = true }
            for (line in state.diagnostics) {
                val match = OK.matchEntire(line) ?: continue
                val id = ids.filterNotNull().singleOrNull { it.startsWith(match.groupValues[1]) } ?: continue
                val relay = match.groupValues[2]
                if (relay !in state.relays) continue
                if (receipts.getOrPut(relay) { linkedSetOf() }.add(id)) {
                    receiptTimes[relay to id] = System.currentTimeMillis()
                    changed = true
                    stream(JSONObject().put("run", run).put("eventId", id).put("relay", relay).put("accepted", true).toString())
                }
            }
            if (changed) {
                save()
                stream(JSONObject().put("run", run).put("plc", plc).toString())
            }
        }

        @Synchronized fun delivered(): Boolean = ids.all { it != null } &&
            receipts[PDS].orEmpty().containsAll(ids.filterNotNull()) &&
            receipts["wss://nos.lol"].orEmpty().containsAll(ids.filterNotNull())

        @Synchronized fun snapshot(): JSONObject = JSONObject().apply {
            put("run", run); put("phase", phase); put("result", result)
            put("did", did); put("pubkey", pubkey); put("plc", plc)
            put("events", JSONArray().also { events ->
                for (i in 0..1) events.put(JSONObject().put("id", ids[i] ?: JSONObject.NULL).put("content", contents[i]))
            })
            put("relayOk", JSONArray().also { accepted ->
                receipts.forEach { (relay, events) -> events.forEach { id ->
                    accepted.put(JSONObject().put("relay", relay).put("eventId", id)
                        .put("observedAtMillis", receiptTimes[relay to id]).put("source", "MeshState.diagnostics: unique 8-char event prefix"))
                } }
            })
            put("deliverySuccess", delivered())
            put("persistence", "foreground off and >=2 ticks awaited before final result; restored IDs checked by observe")
            failure?.let { put("failure", it) }
        }

        @Synchronized fun save() {
            val bytes = snapshot().toString(2).toByteArray(Charsets.UTF_8)
            ensure(ledger.parentFile!!.isDirectory || ledger.parentFile!!.mkdirs(), "Cannot create public evidence directory")
            writePublic(ledger, bytes)
            writePublic(File(targetContext.filesDir, "verification-public.json"), bytes)
        }
    }

    private fun writePublic(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }

    private class VerificationFailure(message: String) : RuntimeException(message)
    private fun ensure(condition: Boolean, message: String) { if (!condition) throw VerificationFailure(message) }

    companion object {
        private const val PACKAGE = "org.mndt.demo.verification"
        private const val PDS = "wss://pds.oriom.dev"
        private val OK = Regex("リレー OK ([0-9a-f]{8})（(.+) / PLC・PDS・AppView 完了ではない）")
    }
}
