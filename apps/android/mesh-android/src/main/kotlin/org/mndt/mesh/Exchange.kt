package org.mndt.mesh

import android.util.Log
import org.mndt.core.AtRepository
import org.mndt.core.MeshEngine
import org.mndt.core.MeshPolicy
import org.mndt.core.StoredBundle

/** One link connection's contact state. */
internal class Session(val link: Link, val handle: String, val began: Long) {
    var peerNode: String? = null
    var peerMax = 0
    val sent = linkedMapOf<String, Long>()
    var window = 0L
    var messages = 0
}

/**
 * Contact exchange over any [Link] (mndt §3): hello (capabilities) → inventory → want → bundle → ack.
 * Peers are auto-accepted: trust comes from the Nostr signature check in [MeshEngine] plus resource caps.
 * Not thread-safe; call only from the coordinator's actor.
 */
internal class Exchange(
    private val engine: MeshEngine,
    private val node: String,
    private val now: () -> Long,
    private val elapsed: () -> Long,
    private val note: (String) -> Unit = {},
    private val allowedPeerNode: String? = null,
    private val allowedAuthor: String? = null,
    // Must durably save before returning; failures must stop the coordinator's links.
    private val accepted: (StoredBundle) -> Unit = {},
) {
    val sessions = linkedMapOf<Pair<Link, String>, Session>()

    fun connected(link: Link, handle: String) {
        if (sessions.size >= MAX_SESSIONS) { link.disconnect(handle); return }
        val session = Session(link, handle, elapsed())
        sessions[link to handle] = session
        send(session, Wire.encode(Control("hello", node = node, max = MeshPolicy.MAX_BUNDLE_BYTES)))
    }

    fun disconnected(link: Link, handle: String) { sessions.remove(link to handle) }

    fun frame(link: Link, handle: String, bytes: ByteArray) {
        val s = sessions[link to handle] ?: return
        if (elapsed() - s.window >= 60_000) { s.window = elapsed(); s.messages = 0 }
        if (++s.messages > 512) { drop(s); return }
        try {
            require(bytes.isNotEmpty())
            when (bytes[0].toInt() and 255) {
                '{'.code -> control(s, Wire.decode(bytes))
                0x9f -> bundle(s, bytes)
                else -> error("Unknown frame")
            }
        } catch (e: Exception) {
            log("reject frame from ${s.handle}: ${e.message}")
            note("不正・制限超過フレームを拒否")
            if (s.peerNode == null) drop(s)
        }
    }

    fun broadcastInventory() = sessions.values.filter { it.peerNode != null }.forEach(::inventory)

    /** Drops connections that never completed HELLO, so a silent peer cannot hold a slot. */
    fun dropStale() = sessions.values.filter { it.peerNode == null && elapsed() - it.began > 60_000 }.forEach(::drop)

    fun clear() = sessions.clear()

    private fun control(s: Session, c: Control) {
        if (c.type == "hello") {
            require(s.peerNode == null) { "Duplicate HELLO" }
            if (c.node == node || allowedPeerNode != null && c.node != allowedPeerNode) { drop(s); return }
            s.peerNode = c.node; s.peerMax = c.max
            s.link.identified(s.handle, c.node)
            log("hello from ${c.node} on ${s.link.name}:${s.handle}")
            inventory(s)
            return
        }
        require(s.peerNode != null) { "HELLO required" }
        when (c.type) {
            "inventory" -> {
                val owned = engine.entries().map { it.event.id }.toSet()
                val missing = c.ids.filterNot(owned::contains)
                log("inventory ${c.ids.size} from ${s.peerNode}, want ${missing.size}")
                if (missing.isNotEmpty()) send(s, Wire.encode(Control("want", missing)))
                s.sent.keys.removeAll(c.ids.toSet())
            }
            "want" -> {
                val wanted = c.ids.toSet()
                log("want ${c.ids.size} from ${s.peerNode}")
                val eligible = engine.inventory(now(), elapsed()).toSet()
                engine.entries().filter { it.event.id in eligible && it.event.id in wanted && (allowedAuthor == null || it.event.pubkey == allowedAuthor) }
                    .sortedWith(compareBy<StoredBundle> { AtRepository.deliveryExpiresAt(it.event) }.thenBy { it.bytes.size })
                    .forEach { entry ->
                        val id = entry.event.id
                        if (elapsed() - (s.sent[id] ?: -30_000L) < 10_000) return@forEach
                        val bundle = engine.forward(id, now(), elapsed()) ?: return@forEach
                        val ok = bundle.size <= s.peerMax && send(s, bundle)
                        log("bundle ${id.take(8)} ${bundle.size}B to ${s.peerNode}: ${if (ok) "queued" else "NOT sent"}")
                        if (ok) s.sent[id] = elapsed()
                    }
            }
            "ack" -> s.sent.remove(c.ids.single())
        }
    }

    private fun bundle(s: Session, bytes: ByteArray) {
        require(s.peerNode != null) { "HELLO required" }
        val entry = engine.accept(bytes, now(), elapsed())
        log("bundle ${bytes.size}B from ${s.peerNode}: ${if (entry == null) "duplicate" else "accepted ${entry.event.id}"}")
        if (entry == null) { inventory(s); return }
        accepted(entry)
        send(s, Wire.encode(Control("ack", listOf(entry.event.id))))
        note("署名検証・保存 ${entry.event.id.take(8)} / 観測 hop ${entry.hop}")
        broadcastInventory()
    }

    // android.util.Log throws "not mocked" in JVM unit tests; runCatching keeps Exchange testable there.
    private fun log(text: String) { runCatching { Log.i("Exchange", text) } }
    private fun inventory(s: Session) {
        val allowed = engine.entries().filter { allowedAuthor == null || it.event.pubkey == allowedAuthor }.map { it.event.id }.toSet()
        send(s, Wire.encode(Control("inventory", engine.inventory(now(), elapsed()).filter { it in allowed }.takeLast(128))))
    }
    private fun send(s: Session, frame: ByteArray): Boolean = s.link.send(s.handle, frame)
    private fun drop(s: Session) { sessions.remove(s.link to s.handle); s.link.disconnect(s.handle) }

    companion object { const val MAX_SESSIONS = 16 }
}
