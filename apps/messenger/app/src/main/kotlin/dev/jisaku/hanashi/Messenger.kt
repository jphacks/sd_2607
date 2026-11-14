package dev.jisaku.hanashi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.mndt.core.NostrEvent
import org.mndt.dm.Delivery
import org.mndt.dm.DmCodec
import org.mndt.dm.Draft
import org.mndt.dm.Inbound
import org.mndt.mesh.Handoff
import org.mndt.mesh.MeshCoordinator
import org.mndt.mesh.MeshState
import org.mndt.mesh.Transit
import java.util.concurrent.ConcurrentHashMap

@Serializable data class Msg(val id: String, val peer: String, val mine: Boolean, val text: String, val createdAt: Long,
    val relayed: Boolean = false, val delivered: Boolean = false, val read: Boolean = true) {
    fun delivery(nowSeconds: Long) = DmCodec.delivery(createdAt, nowSeconds, relayed, delivered)
}
/** [seenAt]: created_at of the newest profile we hold for them. */
@Serializable data class Contact(val pubkey: String, val name: String, val seenAt: Long)
/** A saved [org.mndt.mesh.Handoff]: wall time (ms), link ("ble", "lan", "local") and the peer's session label. */
@Serializable data class Step(val at: Long, val link: String, val peer: String)
/** A saved [Transit]: the SDK keeps one per run, we keep it across restarts. */
@Serializable data class Trail(val hop: Int, val received: Step? = null, val forwarded: List<Step> = emptyList(),
    val exported: List<Step> = emptyList())
/** [trails]: by event ID, for every bundle the mesh holds. [gateway]: also export to online relays when the phone is online. */
@Serializable data class Chats(val name: String? = null, val contacts: Map<String, Contact> = emptyMap(),
    val messages: List<Msg> = emptyList(), val receipted: Set<String> = emptySet(), val trails: Map<String, Trail> = emptyMap(),
    val gateway: Boolean = false) {
    fun nameOf(pubkey: String) = contacts[pubkey]?.name ?: "名無し ${pubkey.take(6)}"
}

/** This run's transits laid over the saved trails: the first arrival and the first handoff per peer stay; gone bundles drop out. */
fun merge(saved: Map<String, Trail>, live: Map<String, Transit>): Map<String, Trail> = live.mapValues { (id, t) ->
    val before = saved[id]
    Trail(t.hop, before?.received ?: t.received?.step(), steps(before?.forwarded, t.forwarded), steps(before?.exported, t.exported))
}

private fun Handoff.step() = Step(atMillis, link, peer)
private fun steps(saved: List<Step>?, now: List<Handoff>) = (saved.orEmpty() + now.map { it.step() }).distinctBy { it.peer }

/** A bundle this phone holds for someone else, and how it passed through. */
data class Carried(val event: NostrEvent, val trail: Trail)

/** Everyone else's bundles, newest arrival first; one received before trails were kept sorts by its creation time. */
fun carried(events: List<NostrEvent>, me: String, trails: Map<String, Trail>): List<Carried> = events.filter { it.pubkey != me }
    .mapNotNull { e -> trails[e.id]?.let { Carried(e, it) } }
    .sortedByDescending { it.time() }

/** When the bundle reached this phone (ms), else when its author made it. */
fun Carried.time() = trail.received?.at ?: (event.createdAt * 1000)

/** Glue between the Mosimo mesh SDK and the chat history. Everything goes over the mesh; there is no server. */
class Messenger(private val context: Context) {
    val mesh = MeshCoordinator.get(context)
    private val store = ChatStore(context)
    private val mutable = MutableStateFlow(store.load() ?: Chats())
    val chats: StateFlow<Chats> = mutable.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val secrets = ConcurrentHashMap<String, ByteArray>()
    private val seen = HashSet<String>()
    private var announced = false
    /** The chat on screen: its messages arrive already read and raise no notification. */
    @Volatile var openPeer: String? = null

    init {
        scope.launch { mesh.state.collect { if (it.ready && it.publicKey.isNotEmpty()) lock.withLock { sync(it) } } }
    }

    private fun codec(me: String) = DmCodec(me) { peer -> secrets.getOrPut(peer) { mesh.sharedSecret(peer) } }
    private suspend fun publish(d: Draft) = mesh.publishEvent(d.kind, d.tags, d.content)
    private fun commit(next: Chats) { if (next != mutable.value) { store.save(next); mutable.value = next } }

    private suspend fun sync(state: MeshState) {
        val codec = codec(state.publicKey)
        var next = mutable.value
        val fresh = mutableListOf<Msg>()
        for (event in state.events) {
            if (!seen.add(event.id)) continue
            when (val m = codec.read(event)) {
                is Inbound.Profile -> if ((next.contacts[m.pubkey]?.seenAt ?: -1) < m.createdAt)
                    next = next.copy(contacts = next.contacts + (m.pubkey to Contact(m.pubkey, m.name, m.createdAt)))
                is Inbound.Message -> if (next.messages.none { it.id == m.id }) {
                    val msg = Msg(m.id, m.from, false, m.text, m.createdAt, read = m.from == openPeer)
                    next = next.copy(messages = next.messages + msg)
                    fresh += msg
                }
                is Inbound.Receipt -> next = next.copy(messages = next.messages.map {
                    if (it.mine && it.id == m.of && it.peer == m.from) it.copy(delivered = true) else it })
                null -> Unit
            }
        }
        next = next.copy(messages = next.messages.map {
            if (it.mine && !it.relayed && (state.forwardedTo[it.id] ?: 0) > 0) it.copy(relayed = true) else it },
            trails = merge(next.trails, state.transits))
        commit(next)
        fresh.filterNot { it.read }.forEach(::notify)
        // The mesh rate limit (12 events/min) may refuse a receipt; the rest are retried on the next sync.
        for (m in next.messages.filter { !it.mine && it.id !in next.receipted }) {
            publish(codec.receipt(m.peer, m.id)) ?: break
            commit(mutable.value.let { it.copy(receipted = it.receipted + m.id) })
        }
        if (!announced) next.name?.let { announced = publish(codec.profile(it)) != null }
    }

    /** False when the mesh refused it (rate, size or capacity limit); nothing was sent. */
    suspend fun send(peer: String, text: String): Boolean = lock.withLock {
        val me = mesh.state.value.publicKey.takeIf { it.isNotEmpty() } ?: return false
        val event = publish(codec(me).message(peer, text.trim())) ?: return false
        commit(mutable.value.let { it.copy(messages = it.messages + Msg(event.id, peer, true, text.trim(), event.createdAt)) })
        true
    }

    /** Also re-announces the profile; a refused announcement is retried on the next sync. */
    suspend fun setName(name: String) = lock.withLock {
        commit(mutable.value.copy(name = name.trim().take(DmCodec.MAX_NAME)))
        announced = false
    }

    /** Demo switch: with it on, the mesh also exports to online relays whenever the phone has a network. */
    suspend fun setGateway(on: Boolean) = lock.withLock {
        commit(mutable.value.copy(gateway = on))
        mesh.setOnlineEnabled(on)
    }

    fun markRead(peer: String) = scope.launch {
        lock.withLock { commit(mutable.value.let { c -> c.copy(messages = c.messages.map { if (!it.mine && it.peer == peer) it.copy(read = true) else it }) }) }
    }

    private fun notify(m: Msg) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "メッセージ", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, m.peer.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(EXTRA_PEER, m.peer).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(m.peer.hashCode(), Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_mesh)
            .setContentTitle(mutable.value.nameOf(m.peer)).setContentText(m.text).setAutoCancel(true).setContentIntent(open).build())
    }

    companion object {
        const val EXTRA_PEER = "peer"
        private const val CHANNEL = "dm"
    }
}

fun Delivery.label() = when (this) {
    Delivery.SENDING -> "送信中"
    Delivery.RELAYED -> "✓ 中継"
    Delivery.DELIVERED -> "✓✓ 配達"
    Delivery.EXPIRED -> "期限切れ"
}
