package org.mndt.mesh

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString
import org.mndt.core.Author
import org.mndt.core.ClockConfidence
import org.mndt.core.DeliveryDeadline
import org.mndt.core.MeshEngine
import org.mndt.core.SecretKeys
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** UI snapshots contain public information only, never the private author or key set. */
data class Neighbor(val endpoint: String, val name: String, val status: String)
data class PostView(val id: String, val pubkey: String, val content: String, val hop: Int?, val relayAccepted: Boolean,
    val sourceRelay: String? = null, val volatile: Boolean = false,
    val deliveryDeadline: DeliveryDeadline = DeliveryDeadline.UNKNOWN)
data class MeshState(
    val ready: Boolean = false, val running: Boolean = false, val did: String = "", val publicKey: String = "",
    val relays: List<String> = emptyList(), val registration: String = "PLC 未登録", val neighbors: List<Neighbor> = emptyList(),
    val posts: List<PostView> = emptyList(), val diagnostics: List<String> = emptyList(),
    val error: String? = null, val lastPublishedId: String? = null, val publishAttempt: Long = 0,
    val clockConfidence: ClockConfidence = ClockConfidence.UNKNOWN,
    val onlineEnabled: Boolean = false, val onlineActive: Boolean = false
)

/** SDK entry point. Call get() at application initialization, not at mesh start. */
@SuppressLint("MissingPermission")
class MeshCoordinator private constructor(private val context: Context) {
    // ponytail: one bounded actor serializes all IO/state; split only if it measurably lags.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inbox = Channel<() -> Unit>(256)
    private val mutableState = MutableStateFlow(MeshState())
    val state: StateFlow<MeshState> = mutableState.asStateFlow()
    private val store = EncryptedStore(context)
    private var disk: DiskState? = null
    private var dirty = false
    private val engine = MeshEngine()
    private val bootCount = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }
        .getOrNull()?.takeIf { it >= 0 }
    private var author: Author? = null
    /** Only unrecoverable storage failures; a full queue drops link traffic instead (peers re-offer it). */
    private var fatal = false
    private var running = false
    private var activityVisible = false
    private var lastSync = 0L
    private val random = SecureRandom()
    private val node = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val links: List<Link> = listOf(GattLink(context), LanLink(context))
    private val exchange = Exchange(engine, node, ::now, ::elapsed, ::note) { dirty = true; drainRelay() }
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private var socket: WebSocket? = null
    private var socketGeneration = 0L
    private var socketOpen = false
    private var activeRelay = ""
    private var relayIndex = 0
    private val relaySubscription = RelaySubscription()
    private var relayReadDeadline = 0L
    private var relayPendingId: String? = null
    private var relayDeadline = 0L
    private var relayRetryAt = 0L
    private var relayFailures = 0
    private val relayDeferred = mutableMapOf<String, Long>()
    private var relayLastAttempt: String? = null
    private var registrationBusy = false
    private var registrationRetryAt = 0L
    private var registrationFailures = 0
    // Historical genesis confirmation is persisted; current authority is an online observation only.
    private var plcObservation = PlcObservation()
    private val metadata = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
    // TODO(undecided): no production PDS/PLC policy is selected. Configure metadata before first install.
    private val pds = metadata?.getString("mndt.pds.endpoint") ?: "https://pds.invalid"
    private val plcDirectory = metadata?.getString("mndt.plc.directory") ?: ""
    private val allowRegistration = metadata?.getBoolean("mndt.plc.registration.enabled", false) ?: false

    private val listener = object : LinkListener {
        override fun onConnected(link: Link, peer: String) = enqueue(critical = true) {
            if (running) exchange.connected(link, peer) else link.disconnect(peer)
        }
        override fun onFrame(link: Link, peer: String, frame: ByteArray) = enqueue { if (running) exchange.frame(link, peer, frame) }
        override fun onDisconnected(link: Link, peer: String) = enqueue(critical = true) { exchange.disconnected(link, peer) }
    }

    init {
        engine.relayed = { id -> disk?.relayAccepted?.values?.any { id in it } == true }
        scope.launch {
            for (action in inbox) {
                try { action() }
                catch (e: CancellationException) { throw e }
                catch (_: SecurityException) { stopInternal(stopService = true); issue("権限が無効になったため停止しました。設定を確認してください") }
                catch (_: Throwable) { if (!fatal) issue("処理に失敗しました（設定・権限・サイズを確認）") }
                refresh()
            }
        }
        enqueue(critical = true) {
            try {
                val saved = store.load() ?: DiskState(SecretKeys.generate(), pdsEndpoint = pds).also { store.save(it, emptyList()) }
                disk = saved
                author = Author(saved.keys, saved.pdsEndpoint)
                val wall = now()
                val monotonic = elapsed()
                // One unreadable bundle (e.g. after a policy change) must not take the rest of the store down.
                val lost = saved.bundles.count { runCatching { engine.restore(it.bytes, it.receivedAtMillis, wall, monotonic, it.downtime(bootCount, monotonic)) }.isFailure }
                if (lost > 0) note("保存済み Bundle ${lost} 件を読めず破棄")
                persist(saved)
                note("インストール固有の3鍵・DIDを準備済み")
            } catch (_: Exception) {
                fatal = true
                issue("暗号化状態を開けません。鍵は再生成せず停止しました")
            }
        }
        scope.launch { while (isActive) { delay(1_000); enqueue { tick() } } }
    }
    /**
     * Link frames and ticks may be dropped under load; [critical] actions (UI, connection state) wait for space.
     * Both take the direct path first so a link's connected → frame → disconnected order is kept: a HELLO
     * overtaking its onConnected was silently dropped and the peer cut 10 s later.
     */
    private fun enqueue(critical: Boolean = false, action: () -> Unit) {
        if (inbox.trySend(action).isFailure && critical) scope.launch { inbox.send(action) }
    }
    private fun now() = System.currentTimeMillis()
    private fun elapsed() = SystemClock.elapsedRealtime()
    private fun note(text: String) = mutableState.update { it.copy(diagnostics = (it.diagnostics + text).takeLast(16)) }
    private fun issue(text: String) { mutableState.update { it.copy(error = text) }; note(text) }
    fun clearError() = enqueue(critical = true) { mutableState.update { it.copy(error = null) } }
    fun reportError(text: String) = enqueue(critical = true) { issue(text.take(160)) }
    /** Local operator policy, not clock synchronization or network consensus. Defaults to UNKNOWN on restart. */
    fun setClockConfidence(value: ClockConfidence) = enqueue(critical = true) {
        engine.clockConfidence = value
        exchange.broadcastInventory()
    }
    private fun acceptedAnywhere(id: String) = disk?.relayAccepted?.values?.any { id in it } == true
    private fun refresh() {
        val data = disk
        mutableState.update { state -> state.copy(
            ready = author != null && !fatal, running = running, clockConfidence = engine.clockConfidence,
            onlineEnabled = data?.onlineEnabled == true, onlineActive = onlineAllowed(),
            did = author?.did ?: "", publicKey = author?.nostrPubkey ?: "", relays = data?.relays.orEmpty(),
            registration = (if (data?.plcRegistered == true) "PLC 初期操作登録確認済み" else "PLC 初期操作登録未確認") +
                " / " + when (plcObservation.current) {
                    PlcCurrentState.CHECKED -> "Directory 現在鍵・PDS設定一致（確認時刻 ${plcObservation.checkedAtMillis}）"
                    PlcCurrentState.CHANGED -> "Directory 鍵・PDS設定変更あり（確認時刻 ${plcObservation.checkedAtMillis}）"
                    PlcCurrentState.TOMBSTONED -> "Directory 無効化操作あり（確認時刻 ${plcObservation.checkedAtMillis}）"
                    PlcCurrentState.UNKNOWN -> if (registrationBusy) "現在状態確認中" else "現在状態不明（オフライン・未設定・未確認）"
                } + " / PDS 受入・公開未確認",
            // One entry per node: the same phone may be reachable over BLE and LAN (or two BLE links) at once.
            neighbors = exchange.sessions.values.groupBy { it.peerNode ?: "${it.link.name}:${it.handle}" }.map { (key, list) ->
                val named = list.firstOrNull { it.peerNode != null }
                Neighbor(key, named?.peerNode ?: "?", if (named == null) "HELLO 待ち"
                    else "同期中（${list.filter { it.peerNode != null }.joinToString("・") { it.link.name }}）")
            },
            posts = if (fatal) emptyList() else mergePostViews(engine.entries().asReversed().map {
                PostView(it.event.id, it.event.pubkey, it.event.content, it.hop, acceptedAnywhere(it.event.id),
                    deliveryDeadline = engine.deadline(it.event, now()))
            }, relaySubscription.events(), activeRelay)
        ) }
    }
    private fun persist(next: DiskState = checkNotNull(disk)) {
        check(!fatal) { "Persistence is disabled after a fatal error" }
        val entries = engine.entries()
        val ids = entries.map { it.event.id }.toSet()
        val clean = next.copy(relayAccepted = next.relayAccepted.filterKeys { it in next.relays }.mapValues { (_, accepted) -> accepted.intersect(ids) })
        val wall = now()
        val monotonic = elapsed()
        try { store.save(clean, entries.map { engine.checkpoint(it, wall, monotonic) }, bootCount); disk = clean; dirty = false }
        catch (e: Exception) {
            fatal = true
            stopInternal(stopService = true)
            issue("永続化に失敗。未保存データを送信せず停止しました（再起動が必要）")
            throw e
        }
    }
    fun publish(text: String) = enqueue(critical = true) {
        try {
            check(!fatal && author != null)
            require(text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size <= 2_000)
            val current = checkNotNull(disk)
            val post = checkNotNull(author).createPost(text, now(), current.repository)
            try { engine.originate(post.event, now(), elapsed()) }
            catch (_: IllegalArgumentException) {
                issue("投稿の配送条件・サイズ・発信頻度の制限に達しました。保存・発信はしていません")
                return@enqueue
            }
            // The repository head is saved before anything leaves the device, so a crash cannot reuse a TID/commit.
            persist(current.copy(repository = post.repository))
            mutableState.update { it.copy(lastPublishedId = post.event.id, error = null) }
            note("投稿を署名・暗号化保存済み ${post.event.id.take(8)}")
            exchange.broadcastInventory()
            drainRelay()
        } finally { mutableState.update { it.copy(publishAttempt = it.publishAttempt + 1) } }
    }
    fun addRelay(endpoint: String) = enqueue(critical = true) {
        check(!fatal)
        val value = endpoint.trim()
        val parsed = value.replaceFirst(Regex("^wss:"), "https:").replaceFirst(Regex("^ws:"), "http:").toHttpUrlOrNull()
        require(value.startsWith("wss://") || value.startsWith("ws://"))
        require(value.length <= 2048 && parsed != null && parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.fragment == null)
        val current = checkNotNull(disk)
        if (value in current.relays) return@enqueue
        require(current.relays.size < DiskState.MAX_RELAYS)
        persist(current.copy(relays = current.relays + value))
        note("リレー追加 $value")
        drainRelay()
    }
    fun removeRelay(endpoint: String) = enqueue(critical = true) {
        check(!fatal)
        val current = checkNotNull(disk)
        persist(current.copy(relays = current.relays - endpoint))
        if (endpoint == activeRelay) { closeRelay(); relaySubscription.clear() }
        note("リレー削除 $endpoint")
    }
    /** Activity visibility permits online IO even while the mesh service is stopped. */
    fun setOnlineForeground(visible: Boolean) = enqueue(critical = true) {
        activityVisible = visible
        if (!onlineAllowed()) { closeRelay(); relaySubscription.clear() } else drainRelay()
    }
    fun setOnlineEnabled(enabled: Boolean) = enqueue(critical = true) {
        check(!fatal)
        persist(checkNotNull(disk).copy(onlineEnabled = enabled))
        if (!onlineAllowed()) { closeRelay(); relaySubscription.clear() } else drainRelay()
        note(if (enabled) "オンライン搬出を有効化" else "オンライン搬出を停止。搬出待ちは保持")
    }
    private fun onlineAllowed(): Boolean = disk?.let {
        onlineRelayAllowed(it.onlineEnabled, activityVisible, fatal, it.relays.isNotEmpty(), running)
    } == true
    fun start() = enqueue(critical = true) {
        if (fatal || author == null) {
            issue("発信者の初期化が完了していないため開始できません")
            stopInternal(stopService = true)
            return@enqueue
        }
        if (running) return@enqueue
        running = true
        links.forEach { link -> runCatching { link.start(node, listener) }.onFailure { note("${link.name} を開始できません") } }
        note("BLE・LAN の探索と中継を開始（node $node）")
        drainRelay()
    }
    fun stop() = enqueue(critical = true) { stopInternal() }
    private fun stopInternal(stopService: Boolean = false) {
        if (dirty && !fatal) runCatching { persist() }
        running = false
        links.forEach { runCatching { it.stop() } }
        exchange.clear()
        if (!onlineAllowed()) { closeRelay(); relaySubscription.clear() }
        if (stopService) context.stopService(Intent(context, MeshService::class.java))
        note("中継を停止。保存済み投稿とリレー設定は保持")
    }
    private fun backoff(attempts: Int): Long = (1_000L shl attempts.coerceIn(0, 6)).coerceAtMost(60_000L) + random.nextInt(1000)
    private fun validatedInternet(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    private fun closeRelayRead() {
        relaySubscription.close()?.let { if (socketOpen && socket?.send(it) != true) relayFailure() }
        relayReadDeadline = 0
    }
    private fun closeRelay(graceful: Boolean = true) {
        if (graceful) closeRelayRead()
        relaySubscription.disconnect()
        relayReadDeadline = 0
        socketGeneration++
        val old = socket
        if (!graceful || !socketOpen || old?.close(1000, null) != true) old?.cancel()
        socket = null; socketOpen = false; relayPendingId = null; relayDeadline = 0
    }
    private fun relayFailure() {
        relayPendingId?.let { relayDeferred[it] = elapsed() + backoff(relayFailures + 1) }
        closeRelay(graceful = false)
        relayIndex++
        relayRetryAt = elapsed() + backoff(++relayFailures)
        note("リレー未受理。キューを保持し、次のリレーで再試行")
    }
    private fun pendingFor(relay: String) = disk?.let { d -> engine.entries().filter { it.event.id !in d.relayAccepted[relay].orEmpty() } }.orEmpty()
    /** Rotates through the configured relays so every one eventually receives every held event (mndt §6). */
    private fun drainRelay() {
        val current = disk ?: return
        if (!onlineAllowed() || elapsed() < relayRetryAt || current.relays.isEmpty()) return
        if (socket == null) {
            val relays = current.relays
            relayIndex = (0 until relays.size).map { (relayIndex + it) % relays.size }
                .firstOrNull { pendingFor(relays[it]).isNotEmpty() } ?: (relayIndex % relays.size)
            activeRelay = relays[relayIndex]
            relaySubscription.clear()
            val gen = ++socketGeneration
            relayDeadline = elapsed() + 20_000
            // Endpoint reachability, not public-Internet validation, decides local shelter relay access.
            socket = http.newWebSocket(Request.Builder().url(activeRelay).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = enqueue(critical = true) {
                    if (gen == socketGeneration && onlineAllowed()) {
                        socketOpen = true; relayDeadline = 0
                        val req = relaySubscription.open(gen)
                        if (req != null) {
                            relayReadDeadline = elapsed() + 20_000
                            if (!webSocket.send(req)) { relayFailure(); return@enqueue }
                        }
                        drainRelay()
                    } else webSocket.cancel()
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.length > RelaySubscription.MAX_FRAME_BYTES || text.toByteArray(Charsets.UTF_8).size > RelaySubscription.MAX_FRAME_BYTES) {
                        enqueue(critical = true) { if (gen == socketGeneration) relayFailure() }; return
                    }
                    enqueue(critical = true) { if (gen == socketGeneration) relayMessage(text) }
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = enqueue(critical = true) { if (gen == socketGeneration) relayFailure() }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = enqueue(critical = true) { if (gen == socketGeneration) relayFailure() }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = enqueue(critical = true) { if (gen == socketGeneration) relayFailure() }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = enqueue(critical = true) {
                    if (gen == socketGeneration) { webSocket.close(code, null); relayFailure() } else webSocket.cancel()
                }
            })
            return
        }
        val pending = pendingFor(activeRelay)
        if (pending.isEmpty()) {
            // Done here: move on if another relay still lacks something, otherwise stay subscribed.
            if (current.relays.any { it != activeRelay && pendingFor(it).isNotEmpty() }) { closeRelay(); relayIndex++ }
            return
        }
        val nextId = nextRelayId(pending.map { it.event.id }, relayLastAttempt, relayDeferred, elapsed()) ?: return
        val next = pending.first { it.event.id == nextId }
        if (socketOpen && relayPendingId == null) {
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
        }
    }
    private fun relayMessage(text: String) {
        val reply = try {
            relaySubscription.receive(socketGeneration, text, elapsed(), engine.entries().map { it.event.id }.toSet())
        } catch (_: Exception) { relayFailure(); return }
        when (reply) {
            // Importer trust/equivalence rules are undecided (mndt §6): relay events are display-only, never mesh ingress.
            is RelayReply.Event -> if (relaySubscription.atCapacity) closeRelayRead()
            RelayReply.Full -> closeRelayRead()
            RelayReply.Eose, RelayReply.Closed -> relayReadDeadline = 0
            is RelayReply.Ok -> relayOk(reply)
            null -> Unit
        }
    }
    private fun relayOk(reply: RelayReply.Ok) {
        val id = relayPendingId ?: return
        if (reply.id != id) return
        relayPendingId = null; relayDeadline = 0
        if (!reply.accepted) {
            relayDeferred[id] = elapsed() + backoff(++relayFailures)
            note("リレー拒否 ${id.take(8)}（$activeRelay）。投稿は保持")
            drainRelay()
            return
        }
        val current = checkNotNull(disk)
        current.relayAccepted[activeRelay].orEmpty().let { disk = current.copy(relayAccepted = current.relayAccepted + (activeRelay to it + id)) }
        dirty = true
        relayFailures = 0
        relayDeferred.remove(id)
        note("リレー OK ${id.take(8)}（$activeRelay / PLC・PDS・AppView 完了ではない）")
        drainRelay()
    }
    private fun configuredHttps(value: String) = value.toHttpUrlOrNull()?.takeIf {
        it.isHttps && !it.host.endsWith(".invalid") && it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
    }
    private fun registerIfConfigured() {
        val current = disk ?: return
        val directory = configuredHttps(plcDirectory)
        if (fatal || configuredHttps(current.pdsEndpoint) == null || directory == null || !validatedInternet()) {
            plcObservation = PlcObservation(current.plcRegistered)
            return
        }
        if (registrationBusy || elapsed() < registrationRetryAt) return
        val identity = author ?: return
        registrationBusy = true
        plcObservation = PlcObservation(current.plcRegistered)
        scope.launch {
            val result = runCatching { PlcDirectory(http).check(directory, identity.did, identity.initialOperationJson, current.plcRegistered, allowRegistration) }
                .getOrDefault(PlcObservation(current.plcRegistered))
            enqueue(critical = true) {
                registrationBusy = false
                if (fatal) return@enqueue
                val latest = checkNotNull(disk)
                if (result.genesisConfirmed && !latest.plcRegistered) persist(latest.copy(plcRegistered = true))
                plcObservation = if (validatedInternet()) result else PlcObservation(result.genesisConfirmed)
                if (plcObservation.current == PlcCurrentState.UNKNOWN) registrationFailed() else {
                    registrationFailures = 0
                    // ponytail: local observation throttle, not a common protocol retry policy.
                    registrationRetryAt = elapsed() + 60_000
                    note("PLC Directory 状態 ${plcObservation.current}。保存済み鍵・DIDは変更せず、PDS受入は未確認")
                }
            }
        }
    }
    private fun registrationFailed() {
        registrationBusy = false
        registrationRetryAt = elapsed() + backoff(++registrationFailures)
        note("PLC 現在状態不明。保存済み鍵・DID・署名済み初期操作を保持")
    }
    private fun tick() {
        if (fatal || disk == null) return
        if (dirty) persist()
        registerIfConfigured()
        val clock = elapsed()
        if (relayDeadline > 0 && clock >= relayDeadline) relayFailure()
        if (relayReadDeadline > 0 && clock >= relayReadDeadline) closeRelayRead()
        drainRelay()
        if (!running) return
        exchange.dropStale()
        if (clock - lastSync >= 10_000) { lastSync = clock; exchange.broadcastInventory() }
    }
    companion object {
        @Volatile private var instance: MeshCoordinator? = null
        fun get(context: Context): MeshCoordinator = instance ?: synchronized(this) {
            instance ?: MeshCoordinator(context.applicationContext).also { instance = it }
        }
    }
}
