package org.mndt.mesh

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import org.mndt.core.Author
import org.mndt.core.MeshEngine
import org.mndt.core.SecretKeys
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** UI snapshots contain public information only, never the private author or key set. */
data class Neighbor(val endpoint: String, val name: String, val status: String, val code: String? = null)
data class PostView(val id: String, val pubkey: String, val content: String, val hop: Int, val relayAccepted: Boolean)
data class MeshState(
    val ready: Boolean = false, val running: Boolean = false, val did: String = "", val publicKey: String = "",
    val relay: String = "", val registration: String = "PLC 未登録", val neighbors: List<Neighbor> = emptyList(),
    val posts: List<PostView> = emptyList(), val diagnostics: List<String> = emptyList(),
    val error: String? = null, val lastPublishedId: String? = null, val publishAttempt: Long = 0
)

/** SDK entry point. Call get() at application initialization, not at mesh start. */
@SuppressLint("MissingPermission")
class MeshCoordinator private constructor(private val context: Context) {
    // ponytail: one bounded actor serializes all IO/state; split only if 128 bundles become insufficient.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inbox = Channel<() -> Unit>(128)
    private val overloaded = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(MeshState())
    val state: StateFlow<MeshState> = mutableState.asStateFlow()
    private val store = EncryptedStore(context)
    private var disk: DiskState? = null
    private var engine = MeshEngine()
    private var author: Author? = null
    private var fatal = false
    private var running = false
    private var generation = 0L
    private var advertiseActive = false
    private var discoveryActive = false
    private var advertiseBusy = false
    private var discoveryBusy = false
    private var advertiseRetry = 0L
    private var discoveryRetry = 0L
    private var advertiseFailures = 0
    private var discoveryFailures = 0
    private var lastSync = 0L
    private val nearby = Nearby.getConnectionsClient(context)
    private val random = SecureRandom()
    private val peers = linkedMapOf<String, Peer>()
    private val candidates = linkedMapOf<String, Candidate>()
    private val transport = linkedMapOf<Long, Pair<String, String?>>()
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private var socket: WebSocket? = null
    private var socketGeneration = 0L
    private var socketOpen = false
    private var relayPendingId: String? = null
    private var relayDeadline = 0L
    private var relayRetryAt = 0L
    private var relayFailures = 0
    private val relayDeferred = mutableMapOf<String, Long>()
    private var relayLastAttempt: String? = null
    private var registrationBusy = false
    private var registrationRetryAt = 0L
    private var registrationFailures = 0
    private val metadata = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
    // TODO(undecided): no production PDS/PLC policy is selected. Configure metadata before first install.
    private val pds = metadata?.getString("mndt.pds.endpoint") ?: "https://pds.invalid"
    private val plcDirectory = metadata?.getString("mndt.plc.directory") ?: ""
    private val allowRegistration = metadata?.getBoolean("mndt.plc.registration.enabled", false) ?: false

    private class Candidate(val name: String, var attempts: Int = 0, var retryAt: Long = 0)
    private class Peer(val name: String, val code: String, val began: Long) {
        var approved = false
        var connected = false
        val handshake = Handshake()
        val sent = linkedMapOf<String, Long>()
        var window = 0L
        var messages = 0
    }
    init {
        scope.launch {
            for (action in inbox) {
                if (overloaded.getAndSet(false)) {
                    fatal = true
                    stopInternal(stopService = true)
                    registrationBusy = false
                    issue("処理キューが満杯のため安全停止。保存済み投稿は保持しました。再起動してください")
                }
                try { action() }
                catch (_: SecurityException) { stopInternal(stopService = true); issue("権限が無効になったため停止しました。設定を確認してください") }
                catch (_: Exception) { if (!fatal) issue("処理に失敗しました（設定・権限・サイズを確認）") }
                refresh()
            }
        }
        enqueue {
            try {
                val saved = store.load() ?: DiskState(SecretKeys.generate(), pdsEndpoint = pds).also { store.save(it, emptyList()) }
                disk = saved
                author = Author(saved.keys, saved.pdsEndpoint)
                saved.bundles.forEach { engine.restore(it.bytes, it.receivedAtMillis, now(), elapsed()) }
                // Re-save restored bundle ages and keep ACKs scoped to retained entries.
                persist(saved)
                note("インストール固有の3鍵・DIDを準備済み")
            } catch (_: Exception) {
                fatal = true
                issue("暗号化状態を開けません。鍵は再生成せず停止しました")
            }
        }
        scope.launch { while (isActive) { delay(1_000); enqueue { tick() } } }
    }
    private fun enqueue(action: () -> Unit) {
        if (inbox.trySend(action).isFailure) {
            overloaded.set(true)
            mutableState.update { it.copy(error = "処理キューが満杯です。送信完了とは扱わず安全停止します", ready = false) }
        }
    }
    private fun now() = System.currentTimeMillis()
    private fun elapsed() = SystemClock.elapsedRealtime()
    private fun note(text: String) { mutableState.value = mutableState.value.copy(diagnostics = (mutableState.value.diagnostics + text).takeLast(16)) }
    private fun issue(text: String) { mutableState.value = mutableState.value.copy(error = text); note(text) }
    fun clearError() = enqueue { mutableState.value = mutableState.value.copy(error = null) }
    fun reportError(text: String) = enqueue { issue(text.take(160)) }
    private fun refresh() {
        val data = disk
        mutableState.value = mutableState.value.copy(
            ready = author != null && !fatal, running = running,
            did = author?.did ?: "", publicKey = author?.nostrPubkey ?: "", relay = data?.relay ?: "",
            registration = when {
                data?.plcRegistered == true -> "PLC 初期操作登録確認済み / PDS 公開は未確認"
                registrationBusy -> "PLC 登録確認中 / PDS 公開は未確認"
                else -> "PLC 未登録 / PDS 公開未確認（本番設定・登録方針が必要）"
            },
            neighbors = peers.map { (id, peer) -> Neighbor(id, peer.name, when {
                peer.code.isBlank() -> "接続要求中"
                !peer.approved -> "確認待ち"
                !peer.connected -> "相手の承認待ち"
                !peer.handshake.ready -> "HELLO 待ち"
                else -> "同期中"
            }, peer.code.takeUnless { peer.approved || it.isBlank() }) },
            posts = (if (fatal) emptyList() else engine.entries()).asReversed().map { PostView(it.event.id, it.event.pubkey, it.event.content, it.hop,
                data?.relayAccepted?.get(data.relay)?.contains(it.event.id) == true) }
        )
    }
    private fun persist(next: DiskState) {
        check(!fatal) { "Persistence is disabled after a fatal error" }
        val entries = engine.entries()
        val ids = entries.map { it.event.id }.toSet()
        val clean = next.copy(relayAccepted = next.relayAccepted.mapValues { (_, accepted) -> accepted.intersect(ids) })
        try { store.save(clean, entries); disk = clean }
        catch (e: Exception) {
            fatal = true
            stopInternal(stopService = true)
            issue("永続化に失敗。未保存データを送信せず停止しました（再起動が必要）")
            throw e
        }
    }
    fun publish(text: String) = enqueue {
        try {
        check(!fatal && author != null)
        require(text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size <= 2_000)
        val current = checkNotNull(disk)
        val post = checkNotNull(author).createPost(text, now(), current.repository)
        require(post.event.verify())
        try { engine.originate(post.event, now(), elapsed()) }
        catch (e: IllegalArgumentException) {
            issue(if (e.message?.contains("storage full") == true) "保存枠が満杯です（各優先度32件 / 全体128件）。投稿・履歴を削除せず拒否しました"
                else "投稿の配送条件・サイズ・発信頻度の制限に達しました。保存・発信はしていません")
            return@enqueue
        }
        persist(current.copy(repository = post.repository))
        mutableState.value = mutableState.value.copy(lastPublishedId = post.event.id, error = null)
        note("投稿を署名・暗号化保存済み ${post.event.id.take(8)}")
        broadcastInventory()
        drainRelay()
        } finally { mutableState.value = mutableState.value.copy(publishAttempt = mutableState.value.publishAttempt + 1) }
    }
    fun configureRelay(endpoint: String) = enqueue {
        check(!fatal)
        val value = endpoint.trim()
        if (value.isNotEmpty()) {
            val parsed = value.replaceFirst(Regex("^wss:"), "https:").replaceFirst(Regex("^ws:"), "http:").toHttpUrlOrNull()
            require(value.startsWith("wss://") || value.startsWith("ws://"))
            require(value.length <= 2048 && parsed != null && parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.fragment == null)
        }
        val current = checkNotNull(disk)
        require(value.isEmpty() || value in current.relayAccepted || current.relayAccepted.size < 8)
        persist(current.copy(relay = value, relayAccepted = if (value.isEmpty()) current.relayAccepted else current.relayAccepted + (value to (current.relayAccepted[value] ?: emptySet()))))
        closeRelay()
        relayRetryAt = 0
        relayFailures = 0
        relayDeferred.clear()
        relayLastAttempt = null
        note(if (value.isEmpty()) "リレー送信を無効化" else "明示設定したリレーへの送信を有効化")
        drainRelay()
    }
    fun start() = enqueue {
        if (fatal || author == null) {
            issue("発信者の初期化が完了していないため開始できません")
            stopInternal(stopService = true)
            return@enqueue
        }
        if (!running) {
            running = true; generation++
            advertiseFailures = 0; discoveryFailures = 0; advertiseRetry = 0; discoveryRetry = 0
            note("Nearby P2P_CLUSTER 開始"); startRadios()
        }
    }
    fun stop() = enqueue { stopInternal() }
    private fun stopInternal(stopService: Boolean = false) {
        running = false
        generation++
        runCatching { nearby.stopAdvertising() }
        runCatching { nearby.stopDiscovery() }
        runCatching { nearby.stopAllEndpoints() }
        advertiseActive = false; discoveryActive = false; advertiseBusy = false; discoveryBusy = false
        peers.clear(); candidates.clear(); transport.clear()
        closeRelay()
        if (stopService) context.stopService(Intent(context, MeshService::class.java))
        note("メッシュ・ゲートウェイ停止。保存済み投稿は保持")
    }
    fun confirm(endpoint: String, accepted: Boolean) = enqueue {
        val peer = peers[endpoint] ?: return@enqueue
        if (!running || peer.approved || peer.code.isBlank()) return@enqueue
        if (!accepted) {
            candidates[endpoint]?.attempts = 6
            nearby.rejectConnection(endpoint); dropPeer(endpoint); return@enqueue
        }
        peer.approved = true
        nearby.acceptConnection(endpoint, payloads).addOnFailureListener { enqueue { dropPeer(endpoint); issue("Nearby 承認に失敗") } }
    }
    private fun dropPeer(id: String) {
        peers.remove(id)
        transport.entries.removeAll { it.value.first == id }
        candidates[id]?.let { it.retryAt = elapsed() + backoff(it.attempts) }
    }
    private fun backoff(attempts: Int): Long = (1_000L shl attempts.coerceIn(0, 6)).coerceAtMost(60_000L) + random.nextInt(1000)
    private fun localName() = "MNDT-${author?.nostrPubkey?.take(12)}"
    private fun startRadios() {
        if (!running || fatal) return
        val gen = generation
        if (!advertiseActive && !advertiseBusy && elapsed() >= advertiseRetry && advertiseFailures < 8) {
            advertiseBusy = true
            nearby.startAdvertising(localName(), Wire.SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                .addOnSuccessListener { enqueue { if (running && gen == generation) { advertiseActive = true; advertiseBusy = false; advertiseFailures = 0 } } }
                .addOnFailureListener { enqueue { if (gen == generation) { advertiseBusy = false; advertiseRetry = elapsed() + backoff(++advertiseFailures); issue("Nearby 広告失敗。権限・Bluetooth・位置設定を確認") } } }
        }
        if (!discoveryActive && !discoveryBusy && elapsed() >= discoveryRetry && discoveryFailures < 8) {
            discoveryBusy = true
            nearby.startDiscovery(Wire.SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                .addOnSuccessListener { enqueue { if (running && gen == generation) { discoveryActive = true; discoveryBusy = false; discoveryFailures = 0 } } }
                .addOnFailureListener { enqueue { if (gen == generation) { discoveryBusy = false; discoveryRetry = elapsed() + backoff(++discoveryFailures); issue("Nearby 探索失敗。停止・再開で再試行") } } }
        }
    }
    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) = enqueue {
            if (running && info.serviceId == Wire.SERVICE_ID && candidates.size < 16 && id !in candidates)
                candidates[id] = Candidate(info.endpointName.take(80), retryAt = elapsed() + random.nextInt(2000))
        }
        override fun onEndpointLost(id: String) = enqueue { candidates.remove(id) }
    }
    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) = enqueue {
            if (!running || peers.size >= 8 || peers[id]?.code?.isNotEmpty() == true) {
                nearby.rejectConnection(id)
            } else {
                peers[id] = Peer(info.endpointName.take(80), info.authenticationDigits, elapsed())
                note("照合コードを両端末で確認してください（名前は未認証）")
            }
        }
        override fun onConnectionResult(id: String, result: ConnectionResolution) = enqueue {
            val peer = peers[id]
            if (!running || !result.status.isSuccess || peer == null || !peer.approved) {
                nearby.disconnectFromEndpoint(id); dropPeer(id)
            } else {
                peer.connected = true
                sendControl(id, Control("hello"))
                // Inventory is sent only after the peer's compatible HELLO.
            }
        }
        override fun onDisconnected(id: String) = enqueue { dropPeer(id) }
    }
    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) {
            val bytes = payload.asBytes()
            if (payload.type != Payload.Type.BYTES || bytes == null || bytes.size > Wire.MAX_BYTES) {
                enqueue { nearby.disconnectFromEndpoint(id); dropPeer(id) }; return
            }
            enqueue { receive(id, bytes) }
        }
        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) = enqueue {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS || update.status == PayloadTransferUpdate.Status.FAILURE || update.status == PayloadTransferUpdate.Status.CANCELED) {
                val sent = transport.remove(update.payloadId)
                if (sent?.second != null) note("Nearby ${if (update.status == PayloadTransferUpdate.Status.SUCCESS) "transport SUCCESS（受理未確認）" else "transport FAILED"} ${sent.second!!.take(8)}")
            }
        }
    }
    private fun sendBytes(id: String, bytes: ByteArray, eventId: String? = null) {
        if (!running || fatal || overloaded.get() || bytes.size > Wire.MAX_BYTES || transport.size >= 256) return
        val payload = Payload.fromBytes(bytes)
        transport[payload.id] = id to eventId
        nearby.sendPayload(id, payload).addOnFailureListener { enqueue { transport.remove(payload.id); note("Nearby 送信要求失敗（再同期で再試行）") } }
    }
    private fun sendControl(id: String, control: Control) = sendBytes(id, Wire.encode(control))
    private fun inventory(id: String) = sendControl(id, Control("inventory", engine.inventory(now(), elapsed())))
    private fun broadcastInventory() { peers.filterValues { it.connected && it.handshake.ready }.keys.forEach(::inventory) }
    private fun receive(id: String, bytes: ByteArray) {
        val peer = peers[id] ?: return
        if (!running || fatal || !peer.connected || !peer.approved) return
        if (elapsed() - peer.window >= 60_000) { peer.window = elapsed(); peer.messages = 0 }
        if (++peer.messages > 256) { nearby.disconnectFromEndpoint(id); dropPeer(id); return }
        try {
            require(bytes.isNotEmpty())
            when (bytes[0].toInt() and 255) {
                '{'.code -> {
                    val control = Wire.decode(bytes)
                    peer.handshake.receive(control)
                    when (control.type) {
                        "hello" -> inventory(id)
                        "inventory" -> {
                            val owned = engine.entries().map { it.event.id }.toSet()
                            val missing = control.ids.filterNot(owned::contains)
                            if (missing.isNotEmpty()) sendControl(id, Control("want", missing))
                            peer.sent.keys.removeAll(control.ids.toSet())
                        }
                        "want" -> {
                            val wants = control.ids.toSet()
                            val eligible = engine.inventory(now(), elapsed()).toSet()
                            val ordered = engine.entries().filter { it.event.id in eligible && it.event.id in wants }
                                .sortedWith(compareByDescending<org.mndt.core.StoredBundle> {
                                    it.event.tags.single { tag -> tag.firstOrNull() == "mndt" }[4].toInt()
                                }.thenBy { it.event.tags.single { tag -> tag.firstOrNull() == "mndt-exp" }[1].toLong() }
                                    .thenBy { it.bytes.size })
                            ordered.forEach { entry ->
                                val eventId = entry.event.id
                                if (elapsed() - (peer.sent[eventId] ?: -30_000L) >= 10_000) {
                                    engine.forward(eventId, now(), elapsed())?.let { bundle ->
                                        peer.sent[eventId] = elapsed(); sendBytes(id, bundle, eventId)
                                    }
                                }
                            }
                        }
                        "ack" -> if (peer.sent.remove(control.ids.single()) != null) note("相手が署名検証・保存済みと応答 ${control.ids.single().take(8)}（経路証明ではない）")
                    }
                }
                0x9f -> {
                    require(peer.handshake.ready)
                    val accepted = engine.accept(bytes, now(), elapsed())
                    if (accepted != null) {
                        persist(checkNotNull(disk))
                        sendControl(id, Control("ack", listOf(accepted.event.id)))
                        note("署名検証・暗号化保存済み ${accepted.event.id.take(8)} / 観測 hop ${accepted.hop}")
                        broadcastInventory()
                        drainRelay()
                    } else inventory(id)
                }
                else -> error("Unknown frame")
            }
        } catch (_: Exception) {
            note("不正・制限超過フレームを拒否")
            if (!peer.handshake.ready) { nearby.disconnectFromEndpoint(id); dropPeer(id) }
        }
    }
    private fun validatedInternet(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    private fun closeRelay() {
        socketGeneration++
        socket?.cancel(); socket = null; socketOpen = false; relayPendingId = null; relayDeadline = 0
    }
    private fun relayFailure() {
        relayPendingId?.let { relayDeferred[it] = elapsed() + backoff(relayFailures + 1) }
        closeRelay()
        relayRetryAt = elapsed() + backoff(++relayFailures)
        note("リレー未受理。暗号化キューを保持し再試行")
    }
    private fun drainRelay() {
        val current = disk ?: return
        if (!running || fatal || overloaded.get() || current.relay.isEmpty() || elapsed() < relayRetryAt) return
        // Endpoint reachability, not public-Internet validation, decides local shelter relay access.
        val pending = engine.entries().filter { it.event.id !in current.relayAccepted[current.relay].orEmpty() }
        val nextId = nextRelayId(pending.map { it.event.id }, relayLastAttempt, relayDeferred, elapsed()) ?: return
        val next = pending.first { it.event.id == nextId }
        if (socket == null) {
            val gen = ++socketGeneration
            relayDeadline = elapsed() + 20_000
            val request = Request.Builder().url(current.relay).build()
            socket = http.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = enqueue {
                    if (gen == socketGeneration && running) { socketOpen = true; relayDeadline = 0; drainRelay() } else webSocket.cancel()
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.length > Wire.MAX_BYTES || text.toByteArray(Charsets.UTF_8).size > Wire.MAX_BYTES) {
                        enqueue { if (gen == socketGeneration) relayFailure() }; return
                    }
                    enqueue { if (gen == socketGeneration) relayMessage(text) }
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = enqueue { if (gen == socketGeneration) relayFailure() }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = enqueue { if (gen == socketGeneration) relayFailure() }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = enqueue { if (gen == socketGeneration) relayFailure() }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            })
            return
        }
        if (socketOpen && relayPendingId == null) {
            require(next.event.verify())
            // Forward the signed original; never re-author or add mutable route tags.
            relayLastAttempt = next.event.id
            val frame = try { relayEventFrame(next.event.toJson()) } catch (_: IllegalArgumentException) {
                relayDeferred[next.event.id] = elapsed() + 60_000
                note("リレー形式のサイズ上限を超過。投稿を保持し、後続を優先")
                return
            }
            relayPendingId = next.event.id
            relayDeadline = elapsed() + 20_000
            if (socket?.send(frame) != true) relayFailure()
            else note("リレー EVENT 送信 ${next.event.id.take(8)}（OK 待ち）")
        }
    }
    private fun relayMessage(text: String) {
        val frame = runCatching { json.readTree(text) }.getOrNull() ?: return
        if (!frame.isArray || frame.size() != 4 || frame[0].textValue() != "OK" || !frame[1].isTextual || !frame[2].isBoolean || !frame[3].isTextual) return
        val id = relayPendingId ?: return
        if (frame[1].textValue() != id) return
        if (!frame[2].booleanValue()) {
            relayDeferred[id] = elapsed() + backoff(++relayFailures)
            relayPendingId = null; relayDeadline = 0
            note("リレー拒否 ${id.take(8)}。投稿は保持し、他の保留投稿を先に試行")
            drainRelay()
            return
        }
        val current = checkNotNull(disk)
        val accepted = current.relayAccepted[current.relay].orEmpty() + id
        persist(current.copy(relayAccepted = current.relayAccepted + (current.relay to accepted)))
        relayPendingId = null; relayDeadline = 0; relayFailures = 0
        relayDeferred.remove(id)
        note("リレー OK true ${id.take(8)}（PLC/PDS/AppView 完了ではない）")
        drainRelay()
    }
    private fun configuredHttps(value: String) = value.toHttpUrlOrNull()?.takeIf {
        it.isHttps && !it.host.endsWith(".invalid") && it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
    }
    private fun registerIfConfigured() {
        val current = disk ?: return
        if (fatal || current.plcRegistered || registrationBusy || !allowRegistration || configuredHttps(current.pdsEndpoint) == null || elapsed() < registrationRetryAt || !validatedInternet()) return
        val directory = configuredHttps(plcDirectory) ?: return
        val identity = author ?: return
        registrationBusy = true
        val request = Request.Builder().url(directory.newBuilder().addPathSegment(identity.did).build())
            .post(identity.initialOperationJson.toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = enqueue { registrationFailed() }
            override fun onResponse(call: Call, response: Response) {
                response.close()
                // Confirm the initial operation at the exact DID, not just an HTTP submission response.
                val verify = Request.Builder().url(directory.newBuilder().addPathSegment(identity.did).addPathSegment("log").build()).build()
                http.newCall(verify).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) = enqueue { registrationFailed() }
                    override fun onResponse(call: Call, response: Response) {
                        val body = response.use { r ->
                            if (!r.isSuccessful) null else runCatching { r.body?.byteStream()?.readBounded(262144) }.getOrNull()
                        }
                        enqueue {
                            val log = body?.let { runCatching { json.readTree(it) }.getOrNull() }
                            val expected = json.readTree(identity.initialOperationJson)
                            if (log?.isArray == true && log.size() > 0 && log[0] == expected) {
                                persist(checkNotNull(disk).copy(plcRegistered = true)); registrationBusy = false
                                note("PLC 初期操作の公開登録を確認。PDS 受入・現在鍵状態は未確認")
                            } else registrationFailed()
                        }
                    }
                })
            }
        })
    }
    private fun registrationFailed() {
        registrationBusy = false
        registrationRetryAt = elapsed() + backoff(++registrationFailures)
        note("PLC 登録未確認。署名済み初期操作を保持")
    }
    private fun tick() {
        if (fatal || disk == null) return
        registerIfConfigured()
        if (!running) return
        startRadios()
        val clock = elapsed()
        peers.filterValues { (!it.connected || !it.handshake.ready) && clock - it.began > 60_000 }.keys.toList().forEach { nearby.disconnectFromEndpoint(it); dropPeer(it) }
        candidates.forEach { (id, candidate) ->
            if (id !in peers && peers.size < 8 && candidate.attempts < 6 && clock >= candidate.retryAt) {
                candidate.attempts++
                // Reserve before async request: never two simultaneous requests for an endpoint.
                peers[id] = Peer(candidate.name, "", clock)
                nearby.requestConnection(localName(), id, lifecycle).addOnFailureListener { enqueue { dropPeer(id) } }
            }
        }
        if (clock - lastSync >= 10_000) { lastSync = clock; broadcastInventory() }
        if (relayDeadline > 0 && clock >= relayDeadline) relayFailure()
        drainRelay()
    }
    companion object {
        @Volatile private var instance: MeshCoordinator? = null
        fun get(context: Context): MeshCoordinator = instance ?: synchronized(this) {
            instance ?: MeshCoordinator(context.applicationContext).also { instance = it }
        }
    }
}
