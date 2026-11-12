package org.mndt.mesh

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
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
import org.mndt.core.AtRepository
import org.mndt.core.Attest
import org.mndt.core.Author
import org.mndt.core.BlockedAuthor
import org.mndt.core.ClockConfidence
import org.mndt.core.DeliveryDeadline
import org.mndt.core.MeshEngine
import org.mndt.core.Moderation
import org.mndt.core.SecretKeys
import org.mndt.core.PublicIngress
import org.mndt.core.NostrEvent
import java.time.Instant
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** UI snapshots contain public information only, never the private author or key set. */
data class Neighbor(val endpoint: String, val name: String, val status: String)
data class PostView(val id: String, val pubkey: String, val content: String, val hop: Int?, val relayAccepted: Boolean,
    val sourceRelay: String? = null, val volatile: Boolean = false,
    val deliveryDeadline: DeliveryDeadline = DeliveryDeadline.UNKNOWN, val acceptedBy: List<String> = emptyList(),
    val publicAuthorization: String? = null, val did: String? = null,
    /** Nostr created_at (seconds) and decoded known extensions. */
    val createdAt: Long = 0, val ext: PostExtensions = PostExtensions(),
    /** attest.chain verification of the sender, null when the post carries no chain. */
    val chain: PublicIngress.ChainView? = null)
/** mesh.time §2: an unverified peer clock difference, for diagnostics only. */
data class ClockSkew(val peer: String, val deltaSeconds: Long, val confidence: Int)
data class MeshState(
    val ready: Boolean = false, val running: Boolean = false, val did: String = "", val publicKey: String = "",
    val relays: List<String> = emptyList(), val registration: String = "PLC 未登録", val neighbors: List<Neighbor> = emptyList(),
    val posts: List<PostView> = emptyList(), val diagnostics: List<String> = emptyList(),
    val error: String? = null, val lastPublishedId: String? = null, val publishAttempt: Long = 0,
    val clockConfidence: ClockConfidence = ClockConfidence.UNKNOWN,
    val onlineEnabled: Boolean = false, val onlineActive: Boolean = false,
    /** Send status: OS default network present, relay socket in use, unsent count per relay, seconds until retry. */
    val network: Boolean = false, val sendingRelay: String? = null, val queued: Map<String, Int> = emptyMap(),
    val retryInSeconds: Long = 0, val pdsConfigured: Boolean = true,
    val publicBusy: Boolean = false, val publicStatus: String = "公的情報: trust config 未読込", val publicResults: List<String> = emptyList(),
    /** Author Nostr public keys hidden from this device (mesh §10). */
    val blockedPubkeys: List<String> = emptyList(),
    /** Display-only filter for posts past their signed delivery deadline; on by default. */
    val hideExpired: Boolean = true,
    /** Last time any post arrived (wall ms) and whether it came online (relay/PDS) rather than over the mesh. */
    val lastReceivedAtMillis: Long? = null, val lastReceivedOnline: Boolean = false,
    /** Every stored mesh event of any kind (for apps built on other kinds), and how many peers took each one from us this run. */
    val events: List<NostrEvent> = emptyList(), val forwardedTo: Map<String, Int> = emptyMap(),
    /** Trusted roots from the local config, held revocations, and the last revocation-list check per issuer domain. */
    val roots: List<Attest.Root> = emptyList(), val revocationCount: Int = 0, val revocationChecked: Map<String, Long> = emptyMap(),
    /** Unverified peer clock hints, newest first (mesh.time). */
    val clockSkews: List<ClockSkew> = emptyList(),
    /** Posts reported from this device in this run. */
    val reported: Set<String> = emptySet(),
)

/** SDK entry point. Call get() at application initialization, not at mesh start. */
@SuppressLint("MissingPermission")
class MeshCoordinator private constructor(private val context: Context) {
    // Mesh state and disk commits are serialized; public download/crypto run on a separate bounded worker.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inbox = Channel<() -> Unit>(256)
    private val mutableState = MutableStateFlow(MeshState())
    val state: StateFlow<MeshState> = mutableState.asStateFlow()
    private val store = EncryptedStore(context)
    private var disk: DiskState? = null
    private var dirty = false
    private var publicConfig = PublicIngress.Config()
    private var publicGeneration = 0L
    private var publicBusy = false
    private var publicNext = 0L
    private var publicJob: Job? = null
    private var publicEvents: List<NostrEvent> = emptyList()
    private val engine = MeshEngine()
    private var revocations = PublicIngress.RevocationStore()
    /** Event ID → when this device first observed it; `seenAt` in the revocation rules. */
    private val seenAt = mutableMapOf<String, Instant>()
    private val chainCache = object : LinkedHashMap<String, PublicIngress.ChainView?>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PublicIngress.ChainView?>) = size > 1024
    }
    private var chainGeneration = 0L
    private var revocationBusy = false
    private var revocationNext = 0L
    private val clockSkews = linkedMapOf<String, ClockSkew>()
    /** Deletion requests (delete §3) and reports (report §2) seen so far; rebuilt from the store at launch. */
    private val moderation = Moderation()
    /** Posts reported from this device in this run (the report events themselves are in the store). */
    private val reported = mutableSetOf<String>()
    private var lastChainFlush = 0L
    private val bootCount = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }
        .getOrNull()?.takeIf { it >= 0 }
    @Volatile private var author: Author? = null
    /** Only unrecoverable storage failures; a full queue drops link traffic instead (peers re-offer it). */
    private var fatal = false
    private var running = false
    private var activityVisible = false
    private var lastSync = 0L
    private val random = SecureRandom()
    private val metadata = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
    private val node = meshNode(metadata?.getString("mosimo.test.node")) {
        ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    // Optional test isolation only: node IDs are ephemeral and are NOT peer authentication.
    private val allowedPeerNode = metadata?.getString("mosimo.test.peer.node")?.takeIf { it.isNotEmpty() }?.also {
        require(it.matches(Regex("[0-9a-f]{16}"))) { "Invalid test peer node" }
    }
    private val allowedAuthor = metadata?.getString("mosimo.test.author.pubkey")?.takeIf { it.isNotEmpty() }?.also {
        require(it.matches(Regex("[0-9a-f]{64}"))) { "Invalid test author" }
    }
    private val gatt = GattLink(context, allowedPeerNode)
    // BLE and LAN run only in the app that holds this phone's LocalLink port; other Mosimo apps on the phone relay through it.
    private val links: List<Link> = listOf(LocalLink(listOf(gatt, LanLink(context))))
    private val blocked get() = disk?.blockedPubkeys.orEmpty()
    private var hideExpired = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(HIDE_EXPIRED, true)
    // ponytail: in-memory only; apps that need it across restarts persist their own delivery status.
    private val forwarded = mutableMapOf<String, MutableSet<String>>()
    private val exchange = Exchange(engine, ::now, ::elapsed, ::note, allowedPeerNode, allowedAuthor,
        forwarded = { id, peer -> forwarded.getOrPut(id) { mutableSetOf() } += peer },
        accepted = { absorbControl(it.event); persist(); drainRelay(); if (it.event.kind == 1) received(online = false) },
        timeHint = ::timeHint, onTimeHint = { peer, delta, confidence -> noteSkew(peer, delta, confidence) },
        skip = { moderation.skip(it) })
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
    private val relayRetryAt = mutableMapOf<String, Long>()
    private val relayFailures = mutableMapOf<String, Int>()
    private val relayDeferred = mutableMapOf<String, MutableMap<String, Long>>()
    private val relayLastAttempt = mutableMapOf<String, String>()
    private var registrationBusy = false
    // Proxy registration (PDS §6): did → (failures, next attempt); Long.MAX_VALUE once the genesis is confirmed.
    private val proxyNext = mutableMapOf<String, Pair<Int, Long>>()
    private var proxyBusy = false
    private var registrationRetryAt = 0L
    private var registrationFailures = 0
    // Historical genesis confirmation is persisted; current authority is an online observation only.
    private var plcObservation = PlcObservation()
    // TODO(undecided): no production PDS/PLC policy is selected. Configure metadata before first install.
    private val pds = metadata?.getString("mosimo.pds.endpoint") ?: metadata?.getString("mndt.pds.endpoint") ?: "https://pds.invalid"
    private val plcDirectory = metadata?.getString("mosimo.plc.directory") ?: metadata?.getString("mndt.plc.directory") ?: ""
    private val allowRegistration = metadata?.getBoolean("mosimo.plc.registration.enabled", false)
        ?: metadata?.getBoolean("mndt.plc.registration.enabled", false) ?: false

    private val listener = object : LinkListener {
        override fun onConnected(link: Link, peer: String, node: String?) = enqueue(critical = true) {
            if (running) exchange.connected(link, peer, node) else link.disconnect(peer)
        }
        override fun onMessage(link: Link, peer: String, message: ByteArray) = enqueue { if (running) exchange.message(link, peer, message) }
        override fun onDrained(link: Link, peer: String) = enqueue { if (running) exchange.drained(link, peer) }
        override fun onDisconnected(link: Link, peer: String) = enqueue(critical = true) { exchange.disconnected(link, peer) }
    }

    init {
        engine.clockConfidence = if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CLOCK_TRUSTED, true)) ClockConfidence.TRUSTED else ClockConfidence.UNKNOWN
        engine.relayed = { id -> disk?.relayAccepted?.values?.any { id in it } == true }
        // Author block (mesh §10, by Nostr key): such events never enter the store, so they are never advertised or relayed either.
        // Stopped targets (deleted by their author, or reported enough) are refused too (delete §3, report §2).
        engine.admit = { it.pubkey !in blocked && !moderation.stopped(it) }
        engine.suppressed = { moderation.stopped(it) || chainOf(it)?.revocation == Attest.RevocationState.REVOKED }
        // PDS OK confirms event storage, not atproto publication; keep our base chain until then.
        // Kinds the PDS never accepts would otherwise be pinned forever and fill the store.
        engine.canEvict = { entry ->
            entry.event.pubkey != author?.nostrPubkey ||
                !relayWants(DiskState.PDS_RELAY, entry.event) ||
                disk?.relayAccepted?.get(DiskState.PDS_RELAY)?.contains(entry.event.id) == true
        }
        scope.launch {
            for (action in inbox) {
                try { action() }
                catch (e: CancellationException) { throw e }
                catch (_: SecurityException) { stopInternal(stopService = true); issue("権限が無効になったため停止しました。設定を確認してください") }
                catch (e: Throwable) {
                    // The cause is otherwise lost: the UI shows only the generic message below.
                    runCatching { android.util.Log.w("MeshCoordinator", "action failed", e) }
                    if (!fatal) issue("処理に失敗しました（設定・権限・サイズを確認）")
                }
                refresh()
            }
        }
        enqueue(critical = true) {
            try {
                val saved = store.load() ?: DiskState(SecretKeys.generate(), pdsEndpoint = pds).also { store.save(it, emptyList()) }
                disk = saved
                author = Author(saved.keys, saved.pdsEndpoint)
                revocations = PublicIngress.RevocationStore(saved.revocations)
                val wall = now()
                val monotonic = elapsed()
                // One unreadable bundle (e.g. after a policy change) must not take the rest of the store down.
                // A blocked author's bundle is dropped here too, but that is policy, not corruption.
                val lost = saved.bundles.count { runCatching { engine.restore(it.bytes, it.receivedAtMillis, wall, monotonic, it.downtime(bootCount, monotonic)) }
                    .exceptionOrNull().let { e -> e != null && e !is BlockedAuthor } }
                if (lost > 0) note("保存済み Bundle ${lost} 件を読めず破棄")
                engine.entries().forEach { seenAt.putIfAbsent(it.event.id, Instant.ofEpochMilli(it.receivedAtMillis)) }
                engine.entries().forEach { absorbControl(it.event, quiet = true) }
                persist(saved)
                note("インストール固有の3鍵・DIDを準備済み")
                reloadPublicConfigInternal()
            } catch (_: Exception) {
                fatal = true
                issue("暗号化状態を開けません。鍵は再生成せず停止しました")
            }
        }
        scope.launch { while (isActive) { delay(1_000); enqueue { tick() } } }
        // Offline attempts grow the relay backoff to 60 s; a new network must not wait that out.
        context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            // Capabilities change on every bandwidth update; only the first validation per network may skip backoff.
            private var validated: Network? = null
            override fun onAvailable(network: Network) = enqueue(critical = true) { networkChanged(fresh = true) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (network == validated || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                validated = network
                enqueue(critical = true) { networkChanged(fresh = false); requestPublic(manual = false) }
            }
            override fun onLost(network: Network) = enqueue(critical = true) { refresh() }
        })
    }
    /** [fresh]: a new default network, so a socket bound to the old one is dropped without counting a failure. */
    private fun networkChanged(fresh: Boolean) {
        if (fatal || disk == null || (!fresh && relayFailures.values.none { it > 0 })) return
        if (fresh && socket != null) closeRelay(graceful = false)
        relayFailures.clear(); relayRetryAt.clear(); relayDeferred.clear(); relayIndex = 0; registrationRetryAt = 0
        drainRelay()
    }
    /**
     * Link messages and ticks may be dropped under load; [critical] actions (UI, connection state) wait for space.
     * Both take the direct path first so a link's connected → message → disconnected order is kept: a HELLO
     * overtaking its onConnected was silently dropped and the peer cut 10 s later.
     */
    private fun enqueue(critical: Boolean = false, action: () -> Unit) {
        if (inbox.trySend(action).isFailure && critical) scope.launch { inbox.send(action) }
    }
    private fun now() = System.currentTimeMillis()
    private fun elapsed() = SystemClock.elapsedRealtime()
    private fun received(online: Boolean) = mutableState.update { it.copy(lastReceivedAtMillis = now(), lastReceivedOnline = online) }
    private fun note(text: String) {
        runCatching { android.util.Log.i("MeshCoordinator", text) }
        mutableState.update { it.copy(diagnostics = (it.diagnostics + text).takeLast(16)) }
    }
    private fun issue(text: String) { mutableState.update { it.copy(error = text) }; note(text) }
    fun clearError() = enqueue(critical = true) { mutableState.update { it.copy(error = null) } }
    fun reportError(text: String) = enqueue(critical = true) { issue(text.take(160)) }
    /** mesh.time §1: our hint, with the confidence the operator's clock setting justifies. Never used as a clock. */
    private fun timeHint(): Map<String, Any?> = mapOf("\$type" to Messages.TIME_HINT, "now" to now() / 1000,
        "confidence" to when {
            engine.clockConfidence != ClockConfidence.TRUSTED -> 0L
            // Network-synced OS time is the closest Android exposes to "recently synchronized".
            runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME) == 1 }.getOrDefault(false) -> 2L
            else -> 1L
        })
    private fun noteSkew(peer: String, delta: Long, confidence: Int) {
        clockSkews[peer] = ClockSkew(peer, delta, confidence)
        while (clockSkews.size > 8) clockSkews.remove(clockSkews.keys.first())
        // mesh.time §2: only a warning, never a clock. Two or more confident peers far off hint at our own clock.
        val suspicious = clockSkews.values.filter { it.confidence >= 2 && Math.abs(it.deltaSeconds) > 300 }
        if (suspicious.size >= 2) note("近隣端末 ${suspicious.size} 台と時刻が${suspicious.maxOf { Math.abs(it.deltaSeconds) } / 60}分以上ずれています（時計の確認を推奨）")
    }
    /** Local operator policy, not clock synchronization or network consensus. Trusted from install until the user says otherwise; persisted. */
    fun setClockConfidence(value: ClockConfidence) = enqueue(critical = true) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(CLOCK_TRUSTED, value == ClockConfidence.TRUSTED).apply()
        // A display-only download under the old clock must not throttle the authorized one.
        if (engine.clockConfidence != value) { cancelPublicDownload(); publicNext = 0; chainCache.clear(); chainGeneration++ }
        engine.clockConfidence = value
        exchange.broadcastInventory()
        if (value == ClockConfidence.TRUSTED) requestPublic(manual = false)
    }
    /** Hides expired posts from the UI only; they stay stored and exported. Persisted. */
    fun setHideExpired(value: Boolean) = enqueue(critical = true) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(HIDE_EXPIRED, value).apply()
        hideExpired = value
    }
    private fun acceptedAnywhere(id: String) = disk?.relayAccepted?.values?.any { id in it } == true
    /** attest.chain verification, memoized per event; the cache is dropped when roots, revocations or the clock change. */
    private fun chainOf(event: NostrEvent): PublicIngress.ChainView? = synchronized(chainCache) {
        chainCache.getOrPut(event.id) {
            val clock = PublicDownload.ingressClock(engine.clockConfidence, now())
            PublicIngress.chainView(event, publicConfig.roots, revocations, clock, seenAt[event.id] ?: Instant.ofEpochMilli(now()))
        }
    }
    /**
     * Applies control data held in the store: deletion requests and reports (delete §3, report §2, recorded even
     * before their target arrives) and revocation control events (attest.revocation §5, verified by their inner
     * signatures only).
     */
    private fun absorbControl(event: NostrEvent, quiet: Boolean = false) {
        moderation.absorb(event)?.let { target ->
            if (pruneStopped() && !quiet) note("${if (event.kind == 5) "削除要求" else "報告"} により ${target.take(8)} の表示・中継を停止")
            return
        }
        val revocation = runCatching { Attest.revocationOf(event) }.getOrNull() ?: return
        if (runCatching { revocations.add(revocation) }.getOrDefault(false)) {
            synchronized(chainCache) { chainCache.clear() }
            chainGeneration++
            dirty = true
            if (!quiet) note("失効通知を受信・永続保存（配送候補を再評価）")
        }
    }
    /**
     * Erases stopped posts (delete §3, report §2). Our own stays held, hidden and off the mesh, only until the PDS has
     * it: the PDS needs it to verify the removal commit and every later post.
     */
    private fun pruneStopped(): Boolean {
        val me = author?.nostrPubkey
        val atPds = disk?.relayAccepted?.get(DiskState.PDS_RELAY).orEmpty()
        return engine.remove { moderation.stopped(it.event) && (it.event.pubkey != me || it.event.id in atPds) }.also { if (it) dirty = true }
    }
    /** delete §1-2: asks for deletion of our own post with the commit of the repository without it. A request, not erasure. */
    fun delete(eventId: String) = enqueue(critical = true) {
        check(!fatal)
        val me = checkNotNull(author)
        val current = checkNotNull(disk)
        val target = engine.entries().firstOrNull { it.event.id == eventId }?.event
        if (target == null || target.pubkey != me.nostrPubkey) { issue("自分の投稿だけ削除を依頼できます"); return@enqueue }
        val request = try {
            me.createDeletion(target, now(), current.repository).also { engine.originate(it.event, now(), elapsed()) }
        } catch (_: IllegalArgumentException) {
            issue("削除要求を保存できませんでした（容量・発信頻度の制限）"); return@enqueue
        }
        seenAt[request.event.id] = Instant.ofEpochMilli(now())
        // The removal commit is the next base: save it before anything leaves the device.
        persist(current.copy(repository = request.repository))
        absorbControl(request.event)
        persist()
        note("削除要求を署名・配送 ${eventId.take(8)}（受信済みの端末から消える保証はありません）")
        exchange.broadcastInventory()
        drainRelay()
    }
    /** report §1: publishes a NIP-56 report of another author's post; [block] also blocks its key on this device. */
    fun report(eventId: String, type: String, comment: String, block: Boolean) = enqueue(critical = true) {
        check(!fatal)
        val me = checkNotNull(author)
        val target = engine.entries().firstOrNull { it.event.id == eventId }?.event
            ?: (relaySubscription.events() + publicEvents).firstOrNull { it.id == eventId }
        if (target == null || target.pubkey == me.nostrPubkey) { issue("この投稿は報告できません"); return@enqueue }
        var text = comment.trim()
        while (text.toByteArray(Charsets.UTF_8).size > 280) text = text.dropLast(1)
        val event = try {
            me.createReport(target, type, text, now()).also { engine.originate(it, now(), elapsed()) }
        } catch (_: IllegalArgumentException) {
            issue("報告を保存できませんでした（容量・発信頻度の制限）"); return@enqueue
        }
        seenAt[event.id] = Instant.ofEpochMilli(now())
        absorbControl(event)
        reported += eventId
        persist()
        note("報告を署名・配送 ${eventId.take(8)}")
        exchange.broadcastInventory()
        drainRelay()
        if (block && target.pubkey !in blocked) {
            val current = checkNotNull(disk)
            engine.remove { it.event.pubkey == target.pubkey }
            persist(current.copy(blockedPubkeys = current.blockedPubkeys + target.pubkey))
            note("ブロック ${target.pubkey.take(8)}")
        }
    }
    private fun refresh() {
        val data = disk
        val stopped = (engine.entries().map { it.event } + relaySubscription.events() + publicEvents).filter { moderation.stopped(it) }.mapTo(hashSetOf()) { it.id }
        mutableState.update { state -> state.copy(
            ready = author != null && !fatal, running = running, clockConfidence = engine.clockConfidence,
            onlineEnabled = data?.onlineEnabled == true, onlineActive = onlineAllowed(),
            network = context.getSystemService(ConnectivityManager::class.java).activeNetwork != null,
            sendingRelay = activeRelay.takeIf { socketOpen }, queued = data?.relays.orEmpty().associateWith { pendingFor(it).size },
            retryInSeconds = (((data?.relays.orEmpty().filter { pendingFor(it).isNotEmpty() }
                            .minOfOrNull { relayRetryAt[it] ?: 0L } ?: 0L) - elapsed()) / 1000).coerceAtLeast(0),
            pdsConfigured = data == null || configuredHttps(data.pdsEndpoint) != null,
            did = author?.did ?: "", publicKey = author?.nostrPubkey ?: "", relays = data?.relays.orEmpty(),
            registration = (if (data?.plcRegistered == true) "PLC 初期操作登録確認済み" else "PLC 初期操作登録未確認") +
                " / " + when (plcObservation.current) {
                    PlcCurrentState.CHECKED -> "Directory 現在鍵・PDS設定一致（確認時刻 ${plcObservation.checkedAtMillis}）"
                    PlcCurrentState.CHANGED -> "Directory 鍵・PDS設定変更あり（確認時刻 ${plcObservation.checkedAtMillis}）"
                    PlcCurrentState.TOMBSTONED -> "Directory 無効化操作あり（確認時刻 ${plcObservation.checkedAtMillis}）"
                    PlcCurrentState.UNKNOWN -> if (registrationBusy) "現在状態確認中" else "現在状態不明（オフライン・未設定・未確認）"
                } + " / PDS 受入・公開未確認",
            // HELLO-completed peers only, one entry per peer: the same phone may be reachable over BLE and LAN at once.
            neighbors = exchange.sessions.values.filter { it.peer != null }.map { it.label to it.link.name }
                .groupBy({ it.first }, { it.second }).map { (label, links) -> Neighbor(label, label, "同期中（${links.joinToString("・")}）") },
            // Other apps' kinds (DMs, profiles) are relayed but are not posts.
            posts = if (fatal) emptyList() else mergePostViews(engine.entries().asReversed().filter { it.event.kind == 1 }.map {
                PostView(it.event.id, it.event.pubkey, it.event.content, it.hop, acceptedAnywhere(it.event.id),
                    deliveryDeadline = engine.deadline(it.event, now()),
                    acceptedBy = data?.relayAccepted.orEmpty().filterValues { ids -> it.event.id in ids }.keys.toList(),
                    publicAuthorization = data?.publicAudit?.get(it.event.id), did = it.did,
                    createdAt = it.event.createdAt, ext = Extensions.of(it.event), chain = chainOf(it.event))
            }, (relaySubscription.events() + publicEvents).distinctBy { it.id }, activeRelay, { engine.deadline(it, now()) }, ::chainOf)
                .filterNot { it.pubkey in blocked || it.id in stopped ||
                    hideExpired && it.deliveryDeadline == DeliveryDeadline.EXPIRED }.let { posts ->
                val publicIds = publicEvents.mapTo(hashSetOf()) { it.id }
                posts.map { post -> if (post.volatile && post.id in publicIds) post.copy(sourceRelay = DiskState.PDS_RELAY) else post }
            },
            publicBusy = publicBusy, blockedPubkeys = blocked, hideExpired = hideExpired,
            events = if (fatal) emptyList() else engine.entries().map { it.event },
            forwardedTo = forwarded.apply { keys.retainAll(engine.entries().mapTo(hashSetOf()) { it.event.id }) }.mapValues { it.value.size },
            roots = publicConfig.roots, revocationCount = revocations.size, revocationChecked = data?.revocationChecked.orEmpty(),
            clockSkews = clockSkews.values.reversed(),
            reported = reported.toSet(),
        ) }
    }
    private fun persist(next: DiskState = checkNotNull(disk)) {
        check(!fatal) { "Persistence is disabled after a fatal error" }
        val entries = engine.entries()
        val ids = entries.map { it.event.id }.toSet()
        val clean = next.copy(publicAudit = next.publicAudit.filterKeys { it in ids },
            relayAccepted = next.relayAccepted.filterKeys { it in next.relays }.mapValues { (_, accepted) -> accepted.intersect(ids) },
            revocations = revocations.all())
        val wall = now()
        val monotonic = elapsed()
        try { store.save(clean, entries.map { engine.checkpoint(it, wall, monotonic) }, bootCount); disk = clean; dirty = false }
        catch (e: Exception) {
            fatal = true
            stopInternal(stopService = true)
            issue("永続化に失敗。未保存データを送信せず停止しました（再起動が必要）")
            throw e
        }
        seenAt.keys.retainAll(ids)
        advertiseSummary()
    }
    /** mesh.adv: keep the advertised candidate summary in step with the store. */
    private fun advertiseSummary() {
        if (!running) return
        val candidates = engine.inventory(now(), elapsed()).toHashSet()
        val urgent = engine.entries().filter { it.event.id in candidates }.any { entry ->
            val view = chainOf(entry.event)
            Extensions.of(entry.event).priority == 3 && view?.confirmedNow == true
        }
        gatt.setSummary(engine.summary(now(), elapsed()), if (urgent) 1 else 0)
    }
    /** [extensions]: official extension maps (see [Extensions]); [deliveryExpiresAtSeconds] defaults to 24 h. */
    fun publish(text: String, extensions: List<Map<String, Any?>> = emptyList(), deliveryExpiresAtSeconds: Long? = null) = enqueue(critical = true) {
        try {
            check(!fatal && author != null)
            require(text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size <= 2_000)
            val current = checkNotNull(disk)
            val post = checkNotNull(author).createPost(text, now(), current.repository,
                deliveryExpiresAtSeconds ?: (now() / 1000 + 86400), extensions)
            try { engine.originate(post.event, now(), elapsed()) }
            catch (_: IllegalArgumentException) {
                issue("投稿の保存容量・配送条件・サイズ・発信頻度の制限に達しました。保存・発信はしていません")
                return@enqueue
            }
            seenAt[post.event.id] = Instant.ofEpochMilli(now())
            // The repository head is saved before anything leaves the device, so a crash cannot reuse a TID/commit.
            persist(current.copy(repository = post.repository))
            mutableState.update { it.copy(lastPublishedId = post.event.id, error = null) }
            note("投稿を署名・暗号化保存済み ${post.event.id.take(8)}")
            exchange.broadcastInventory()
            drainRelay()
        } finally { mutableState.update { it.copy(publishAttempt = it.publishAttempt + 1) } }
    }
    /** Signs and originates any non-post kind; null when a mesh limit (size, rate, capacity) refused it. */
    suspend fun publishEvent(kind: Int, tags: List<List<String>>, content: String): NostrEvent? {
        val result = CompletableDeferred<NostrEvent?>()
        enqueue(critical = true) {
            try {
                check(!fatal)
                val event = checkNotNull(author).signEvent(kind, tags, content, now())
                try { engine.originate(event, now(), elapsed()) } catch (_: IllegalArgumentException) { return@enqueue }
                seenAt[event.id] = Instant.ofEpochMilli(now())
                absorbControl(event)
                persist()
                exchange.broadcastInventory()
                drainRelay()
                result.complete(event)
            } finally { result.complete(null) }
        }
        return result.await()
    }
    /** NIP-04/44 ECDH with our Nostr key; the key itself never leaves the coordinator. */
    fun sharedSecret(peerPubkey: String): ByteArray = checkNotNull(author) { "Author not ready" }.sharedSecret(peerPubkey)
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
    /** Hides one author's posts everywhere (mesh §10, by Nostr key); their device still relays other authors' posts to us. */
    fun block(pubkey: String) = enqueue(critical = true) {
        check(!fatal)
        require(pubkey.matches(Regex("[0-9a-f]{64}")) && pubkey != author?.nostrPubkey)
        val current = checkNotNull(disk)
        if (pubkey in current.blockedPubkeys) return@enqueue
        require(current.blockedPubkeys.size < DiskState.MAX_BLOCKED)
        engine.remove { it.event.pubkey == pubkey }
        persist(current.copy(blockedPubkeys = current.blockedPubkeys + pubkey))
        note("ブロック ${pubkey.take(8)}")
        exchange.broadcastInventory()
    }
    fun unblock(pubkey: String) = enqueue(critical = true) {
        check(!fatal)
        val current = checkNotNull(disk)
        persist(current.copy(blockedPubkeys = current.blockedPubkeys - pubkey))
        exchange.forgetRefused()
        note("ブロック解除 ${pubkey.take(8)}")
    }
    /** Reload is an explicit local trust operation. A bad asset disables ingress, not the identity. */
    fun reloadPublicConfig() = enqueue(critical = true) { reloadPublicConfigInternal() }
    fun reloadPublicConfig(assetName: String) = enqueue(critical = true) { reloadPublicConfigInternal(assetName) }
    private fun reloadPublicConfigInternal(assetName: String? = null) {
        cancelPublicDownload()
        publicGeneration++
        publicConfig = PublicIngress.Config()
        synchronized(chainCache) { chainCache.clear() }
        chainGeneration++
        val loaded = runCatching {
            val asset = assetName ?: metadata?.getString("mosimo.public.trust.asset") ?: "mosimo-public-trust.json"
            require(asset.matches(Regex("[A-Za-z0-9_.-]{1,128}")))
            val bytes = context.assets.open(asset).use { it.readBounded(65536) }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            PublicIngress.config(text)
        }
        loaded.onSuccess { publicConfig = it }
        mutableState.update { it.copy(publicStatus = loaded.fold(
            { c -> "公的情報: 有効 root ${c.roots.count { r -> r.enabled }} / 選択 Bot/type ${c.selected.size}（未設定は投入不可）" },
            { "公的情報: config 読込失敗 / fail closed（既存鍵・失効履歴は保持）" })) }
    }
    private fun cancelPublicDownload() {
        publicGeneration++
        publicJob?.cancel(); publicJob = null; publicBusy = false
    }
    /** Pull-to-refresh; also auto-run on foreground, network validation and clock trust. No AppView access and no client-side conversion/signing. */
    fun requestPublicInformation() = enqueue(critical = true) { requestPublic(manual = true) }
    /** Auto triggers skip guard failures silently; only a manual request explains them. An untrusted clock only blocks ingress. */
    private fun requestPublic(manual: Boolean) {
        fun skip(status: String) { if (manual) mutableState.update { it.copy(publicStatus = status) } }
        if (fatal || disk == null || publicBusy) return
        if (!onlineAllowed() || !validatedInternet()) return skip("公的情報: オンライン接続が必要 / 保留")
        if (elapsed() < publicNext) return skip("公的情報: 取得頻度制限（30秒）")
        publicNext = elapsed() + 30000
        publicBusy = true
        val generation = ++publicGeneration
        val config = publicConfig
        val confidence = engine.clockConfidence
        val held = revocations
        mutableState.update { it.copy(publicStatus = "PDS relay から priority-v1 取得中…", publicResults = emptyList()) }
        publicJob = scope.launch {
            // Network IO is never run inside inbox's serialized actor. No redirects or unbounded body reads.
            val batch = runCatching { PublicDownload.fetch(http, generation) }
            ensureActive()
            val clock = PublicDownload.ingressClock(confidence, now())
            // Crypto is bounded worker work too; decisions are made against the revocations held at request time.
            val decisions = batch.getOrNull()?.events.orEmpty().map { event ->
                ensureActive()
                event to PublicIngress.verify(event, config, held, clock)
            }.sortedByDescending { it.second.receipt?.priority ?: minOf(PublicIngress.priority(it.first), 1) }
            var imported = 0
            fun summary(fallback: Boolean) = "PDS ${if (fallback) "通常REQ fallback" else "priority-v1"}: ${publicEvents.size} 件取得 / $imported 件投入（原Bot署名・現在PLC認証ではない）" +
                if (clock == null) " / 時計 unknown・認可保留" else ""
            enqueue(critical = true) complete@{
                if (generation != publicGeneration || fatal || !onlineAllowed()) return@complete
                // A successful first batch stays busy while display-only older pages load.
                if (batch.isFailure) { publicBusy = false; publicJob = null }
                batch.onSuccess { result ->
                    val known = publicEvents.mapTo(hashSetOf()) { it.id } + engine.entries().map { it.event.id }
                    if (result.events.any { it.id !in known }) received(online = true)
                    publicEvents = result.events
                    val reports = mutableListOf<String>()
                    for ((event, decision) in decisions) {
                        if (fatal) break
                        val receipt = decision.receipt
                        val insertionTime = PublicDownload.ingressClock(engine.clockConfidence, now())
                        val outcome = if (receipt == null) "投入拒否/保留: ${decision.reason}"
                        // importer §3: re-check the whole authorization (incl. revocations learned meanwhile) at insertion.
                        else if (!PublicIngress.readyToInsert(receipt, event, insertionTime) ||
                            PublicIngress.verify(event, publicConfig, revocations, insertionTime).receipt == null) "投入保留: 待機中に証明・失効・期限・時計が無効化"
                        else if (engine.entries().any { it.event.id == event.id }) "既存 event ID / 重複投入なし" else {
                            val saved = runCatching { engine.originate(event, now(), elapsed()) }
                            if (saved.isSuccess) {
                                seenAt[event.id] = Instant.ofEpochMilli(now())
                                val audit = "取込時の転載者認可（現在の公式認証ではない）: $receipt"
                                persist(checkNotNull(disk).copy(publicAudit = checkNotNull(disk).publicAudit + (event.id to audit)))
                                imported++; "認可・暗号化保存済み / priority ${receipt.priority}"
                            } else "通常 mesh 資源制限: ${saved.exceptionOrNull()?.message}"
                        }
                        reports += "${event.id.take(8)} $outcome\n${decision.chain?.reason ?: decision.reason}"
                    }
                    mutableState.update { it.copy(publicStatus = summary(result.fallback) + if (result.events.size >= PublicDownload.LIMIT) " / 続きを取得中…" else "",
                        publicResults = reports.take(40)) }
                    if (imported > 0) { exchange.broadcastInventory(); drainRelay() }
                }.onFailure { failure -> mutableState.update { it.copy(publicStatus = "公的情報取得失敗: ${failure.message}") } }
            }
            val first = batch.getOrNull() ?: return@launch
            val pages = PublicDownload.more(http, generation, first)
            ensureActive()
            enqueue(critical = true) pages@{
                if (generation != publicGeneration || fatal || !onlineAllowed()) return@pages
                publicBusy = false; publicJob = null
                val known = publicEvents.mapTo(hashSetOf()) { it.id } + engine.entries().map { it.event.id }
                if (pages.events.any { it.id !in known }) received(online = true)
                publicEvents = (first.events + pages.events).distinctBy { it.id }.take(PublicDownload.MAX_EVENTS)
                mutableState.update { it.copy(publicStatus = summary(first.fallback) + (pages.failure?.let { f -> " / 続き取得中断: $f" } ?: "")) }
            }
        }
    }

    /**
     * attest.revocation.list §5: with Internet access, fetch the revocation lists of the issuer domains seen in held
     * chains, verify each notice and keep it permanently. A failed fetch keeps the previous state.
     */
    private fun fetchRevocations() {
        if (fatal || disk == null || revocationBusy || !onlineAllowed() || !validatedInternet()) return
        if (elapsed() < revocationNext) return
        val domains = engine.entries().asSequence().mapNotNull { runCatching { Attest.chains(it.event) }.getOrNull() }
            .flatten().flatMap { it.attestations }.mapNotNull { it.issuerDomain }.distinct().take(8).toList()
        if (domains.isEmpty()) return
        revocationNext = elapsed() + REVOCATION_INTERVAL_MS
        revocationBusy = true
        scope.launch {
            val fetched = domains.map { domain ->
                domain to runCatching { Attest.revocationList(PublicDownload.revocationList(http, domain)) }
            }
            enqueue(critical = true) {
                revocationBusy = false
                if (fatal || disk == null) return@enqueue
                var added = 0
                var checked = checkNotNull(disk).revocationChecked
                for ((domain, result) in fetched) result.onSuccess { list ->
                    checked = checked + (domain to now() / 1000)
                    list.forEach { if (runCatching { revocations.add(it) }.getOrDefault(false)) added++ }
                }.onFailure { note("失効リスト取得失敗 $domain: ${it.message}") }
                if (added > 0 || checked != checkNotNull(disk).revocationChecked) {
                    synchronized(chainCache) { chainCache.clear() }
                    chainGeneration++
                    persist(checkNotNull(disk).copy(revocationChecked = checked))
                    if (added > 0) note("失効通知 ${added} 件を取得・永続保存（配送候補を再評価）")
                }
            }
        }
    }

    /** Activity visibility permits online IO even while the mesh service is stopped. */
    fun setOnlineForeground(visible: Boolean) = enqueue(critical = true) {
        activityVisible = visible
        if (!onlineAllowed()) { cancelPublicDownload(); closeRelay(); relaySubscription.clear() } else { drainRelay(); requestPublic(manual = false) }
    }
    fun setOnlineEnabled(enabled: Boolean) = enqueue(critical = true) {
        check(!fatal)
        persist(checkNotNull(disk).copy(onlineEnabled = enabled))
        if (!onlineAllowed()) { cancelPublicDownload(); closeRelay(); relaySubscription.clear() } else drainRelay()
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
        advertiseSummary()
        note("BLE・LAN の探索と中継を開始（node $node）")
        drainRelay()
    }
    fun stop() = enqueue(critical = true) { stopInternal() }
    private fun stopInternal(stopService: Boolean = false) {
        if (dirty && !fatal) runCatching { persist() }
        running = false
        links.forEach { runCatching { it.stop() } }
        exchange.clear()
        clockSkews.clear()
        if (!onlineAllowed()) { cancelPublicDownload(); closeRelay(); relaySubscription.clear() }
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
        val failures = (relayFailures[activeRelay] ?: 0) + 1
        relayFailures[activeRelay] = failures
        val retry = elapsed() + backoff(failures)
        relayPendingId?.let { relayDeferred.getOrPut(activeRelay) { mutableMapOf() }[it] = retry }
        relayRetryAt[activeRelay] = retry
        closeRelay(graceful = false)
        relayIndex++
        note("リレー未受理。キューを保持し、次のリレーで再試行")
    }
    // A stopped post (our own, kept for the PDS) goes to the PDS relay only (delete §3).
    private fun pendingFor(relay: String) = disk?.let { d -> engine.entries().filter { relayWants(relay, it.event) && (allowedAuthor == null || it.event.pubkey == allowedAuthor) &&
        it.event.id !in d.relayAccepted[relay].orEmpty() && (relay == DiskState.PDS_RELAY || !moderation.stopped(it.event)) } }.orEmpty()
    /** Rotates through the configured relays so every one eventually receives every held event (mesh §12). */
    private fun drainRelay() {
        val current = disk ?: return
        if (!onlineAllowed() || current.relays.isEmpty()) return
        if (socket == null) {
            val relays = current.relays
            relayIndex = nextRelayIndex(relays, relayIndex, { pendingFor(it).map { entry -> entry.event.id } },
                relayDeferred, relayRetryAt, elapsed())
                ?: relays.indices.map { (relayIndex + it) % relays.size }
                    .firstOrNull { elapsed() >= (relayRetryAt[relays[it]] ?: 0L) } ?: return
            activeRelay = relays[relayIndex]
            // Rotation keeps the display cache; only relay removal or going offline clears it.
            relaySubscription.disconnect()
            val gen = ++socketGeneration
            relayDeadline = elapsed() + 20_000
            // Endpoint reachability, not public-Internet validation, decides local shelter relay access.
            socket = http.newWebSocket(Request.Builder().url(activeRelay).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = enqueue(critical = true) {
                    if (gen == socketGeneration && onlineAllowed()) {
                        socketOpen = true; relayDeadline = 0; relayFailures.remove(activeRelay)
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
        if (!socketOpen || relayPendingId != null) return
        val pending = pendingFor(activeRelay)
        val deferred = relayDeferred.getOrPut(activeRelay) { mutableMapOf() }
        val nextId = nextRelayId(pending.map { it.event.id }, relayLastAttempt[activeRelay], deferred, elapsed())
        if (nextId == null) {
            // Stay subscribed only while no other relay has a sendable event.
            if (nextRelayIndex(current.relays, relayIndex + 1, { pendingFor(it).map { entry -> entry.event.id } },
                    relayDeferred, relayRetryAt, elapsed()) != null) { closeRelay(); relayIndex++ }
            return
        }
        val next = pending.first { it.event.id == nextId }
        if (socketOpen && relayPendingId == null) {
            // Forward the signed original; never re-author or add mutable route tags.
            relayLastAttempt[activeRelay] = next.event.id
            val frame = try { relayEventFrame(next.event.toJson()) } catch (_: IllegalArgumentException) {
                deferred[next.event.id] = elapsed() + 60_000
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
            // General subscription remains display-only; authorized ingress uses the explicit public request.
            is RelayReply.Event -> { received(online = true); if (relaySubscription.atCapacity) closeRelayRead() }
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
            // OK false is relay policy, not transport: onOpen resets failures, so a backoff here looped every ~2 s.
            relayDeferred.getOrPut(activeRelay) { mutableMapOf() }[id] = elapsed() + RELAY_REJECT_DEFER_MILLIS
            note("リレー拒否 ${id.take(8)}（$activeRelay）: ${reply.message.ifEmpty { "理由なし" }}。投稿は保持")
            closeRelay(); relayIndex++
            drainRelay()
            return
        }
        val current = checkNotNull(disk)
        current.relayAccepted[activeRelay].orEmpty().let { disk = current.copy(relayAccepted = current.relayAccepted + (activeRelay to it + id)) }
        dirty = true
        pruneStopped()
        relayFailures.remove(activeRelay)
        relayRetryAt.remove(activeRelay)
        relayDeferred[activeRelay]?.remove(id)
        note("リレー OK ${id.take(8)}（$activeRelay / PLC・PDS・AppView 完了ではない）")
        closeRelay(); relayIndex++
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
    /** Unregistered with posts held: carry the signed genesis operation as its own control event (PDS §6). */
    private fun originateGenesisIfUnregistered() {
        val current = disk ?: return
        val me = author ?: return
        if (current.plcRegistered || configuredHttps(current.pdsEndpoint) == null) return
        val own = engine.entries().filter { it.event.pubkey == me.nostrPubkey }
        if (own.none { it.event.kind == 1 } || own.any { it.event.isPlcGenesis() }) return
        // A rate-limited or rejected attempt is retried on a later tick.
        runCatching { engine.originate(me.createPlcGenesisEvent(now()), now(), elapsed()) }.onSuccess {
            seenAt[it.event.id] = Instant.ofEpochMilli(now())
            persist()
            note("PLC 未登録のため署名済み初期操作をメッシュで配送（代理登録用）")
            exchange.broadcastInventory()
        }
    }
    /** Online nodes submit received genesis operations unchanged; PlcDirectory.check never POSTs over an existing log. */
    private fun proxyRegister() {
        val directory = configuredHttps(plcDirectory) ?: return
        if (fatal || !allowRegistration || proxyBusy || !validatedInternet()) return
        val clock = elapsed()
        val (did, operation) = engine.entries().asSequence().filter { it.event.isPlcGenesis() && it.event.pubkey != author?.nostrPubkey && (allowedAuthor == null || it.event.pubkey == allowedAuthor) }
            .mapNotNull { e -> runCatching { AtRepository.plcDid(e.event.content) to e.event.content }.getOrNull() }
            .firstOrNull { (did, _) -> clock >= (proxyNext[did]?.second ?: 0) } ?: return
        proxyBusy = true
        scope.launch {
            val confirmed = runCatching { PlcDirectory(http).check(directory, did, operation, false, allowRegistration = true) }
                .getOrNull()?.genesisConfirmed == true
            enqueue(critical = true) {
                proxyBusy = false
                // ponytail: per-DID retry forever at most once a minute; add a give-up rule once load limits are defined.
                val failures = if (confirmed) 0 else (proxyNext[did]?.first ?: 0) + 1
                proxyNext[did] = failures to if (confirmed) Long.MAX_VALUE else elapsed() + backoff(failures)
                note(if (confirmed) "PLC 代理登録: $did の初期操作をディレクトリで確認" else "PLC 代理登録: $did 未確認。再試行")
            }
        }
    }
    private fun tick() {
        if (fatal || disk == null) return
        if (dirty) persist()
        registerIfConfigured()
        originateGenesisIfUnregistered()
        proxyRegister()
        fetchRevocations()
        val clock = elapsed()
        if (clock - lastChainFlush >= 60_000) { lastChainFlush = clock; synchronized(chainCache) { chainCache.clear() }; chainGeneration++ }
        if (relayDeadline > 0 && clock >= relayDeadline) relayFailure()
        if (relayReadDeadline > 0 && clock >= relayReadDeadline) closeRelayRead()
        drainRelay()
        if (!running) return
        exchange.dropStale()
        if (clock - lastSync >= 10_000) { lastSync = clock; exchange.broadcastInventory(); advertiseSummary() }
    }
    companion object {
        private const val PREFS = "mesh-coordinator"
        private const val CLOCK_TRUSTED = "clockTrusted"
        private const val HIDE_EXPIRED = "hideExpired"
        private const val REVOCATION_INTERVAL_MS = 15 * 60_000L
        @Volatile private var instance: MeshCoordinator? = null
        fun get(context: Context): MeshCoordinator = instance ?: synchronized(this) {
            instance ?: MeshCoordinator(context.applicationContext).also { instance = it }
        }
    }
}

private fun org.mndt.core.NostrEvent.isPlcGenesis() = AtRepository.controlTopic(this) == AtRepository.PLC_TOPIC
