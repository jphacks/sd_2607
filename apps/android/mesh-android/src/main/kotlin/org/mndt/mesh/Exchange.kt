package org.mndt.mesh

import android.util.Log
import org.mndt.core.BlockedAuthor
import org.mndt.core.MeshEngine
import org.mndt.core.MeshPolicy
import org.mndt.core.Negentropy
import org.mndt.core.StoredBundle
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 255) }
private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/** One link connection's contact state (mesh §4-5). */
internal class Session(val link: Link, val handle: String, val began: Long, val node: String?) {
    val nonce: ByteArray = ByteArray(8).also(SecureRandom()::nextBytes)
    var peer: Msg.Hello? = null
    /** Mesh §5.4 side number after HELLO (0 = smaller nonce); -1 before. */
    var side = -1
    val peerSide: Int get() = 1 - side
    var lastActivity = began
    var nextRequest = 0L
    var nextTransfer = 0L
    var nextReconcile = 0L
    var reconcile: Negentropy? = null
    var reconcileId = -1L
    var reconcileStarted = 0L
    var reconcileCandidates: Set<String> = emptySet()
    var reconciledSummary: ByteArray? = null
    var sawReconcile = false
    /** Requests we issued: request ID → event IDs (hex) still open. */
    val requests = linkedMapOf<Long, MutableSet<String>>()
    /** Event IDs (hex) the peer is known to hold (reconciliation, their INVENTORY/WANT outcome, RESULT). */
    val peerHas = hashSetOf<String>()
    /** IDs we already offered in an INVENTORY. */
    val offered = hashSetOf<String>()
    /** Peer WANTs not yet started: (event ID hex, peer's request ID). */
    val queue = ArrayDeque<Pair<String, Long>>()
    /** Our transfers that have begun and lack a RESULT, by transfer ID. */
    val outgoing = linkedMapOf<Long, OutTransfer>()
    var sending: OutTransfer? = null
    /** Peer transfers being assembled, by the peer's transfer ID. */
    val incoming = linkedMapOf<Long, InTransfer>()
    val closedIncoming = LinkedHashSet<Long>()
    /** Display label: the advertised node when known, else the session nonce pair. */
    val label: String get() = node ?: peer?.let { hex(if (side == 0) nonce + it.nonce else it.nonce + nonce) } ?: handle

    class OutTransfer(val id: Long, val eventId: String, val bytes: ByteArray, val requestId: Long, var credit: Int, val startedAt: Long) {
        var sent = 0
        val complete: Boolean get() = sent >= bytes.size
    }
    class InTransfer(val id: Long, val eventId: String, val requestId: Long, val length: Int, val startedAt: Long) {
        val buffer = ByteArrayOutputStream()
        var granted = 0
        var lastActivity = startedAt
    }
}

/**
 * Contact exchange over any [Link] (mesh §4-5): HELLO → RECONCILE (Negentropy) → WANT/INVENTORY → BEGIN/DATA with
 * CREDIT flow control → RESULT. Peers are auto-accepted: trust comes from the Nostr signature check in [MeshEngine]
 * plus resource caps. Not thread-safe; call only from the coordinator's actor.
 */
internal class Exchange(
    private val engine: MeshEngine,
    private val now: () -> Long,
    private val elapsed: () -> Long,
    private val note: (String) -> Unit = {},
    private val allowedPeerNode: String? = null,
    private val allowedAuthor: String? = null,
    // (event id, peer label): a peer stored or already holds a bundle we sent it.
    private val forwarded: (String, String) -> Unit = { _, _ -> },
    // Must durably save before returning; failures must stop the coordinator's links.
    private val accepted: (StoredBundle) -> Unit = {},
    /** mesh.time: our HELLO hint (null = none) and the peer's observed hint (label, delta seconds, confidence). */
    private val timeHint: () -> Map<String, Any?>? = { null },
    private val onTimeHint: (String, Long, Int) -> Unit = { _, _, _ -> },
    /** Our HELLO receive limits (mesh §5.4); tests lower them to emulate small devices. */
    private val messageLimit: Int = MESSAGE_LIMIT,
    private val initialCredit: Int = INITIAL_CREDIT,
    /** Event IDs not worth requesting (targets stopped by reports); refused again by MeshEngine.admit if they arrive. */
    private val skip: (String) -> Boolean = { false },
) {
    val sessions = linkedMapOf<Pair<Link, String>, Session>()
    // ponytail: in-memory FIFO; a restarted app re-wants a refused bundle once.
    private val refused = linkedSetOf<String>()
    private val messages = hashMapOf<Session, Pair<Long, Int>>()

    fun connected(link: Link, handle: String, node: String?) {
        if (sessions.size >= MAX_SESSIONS || node != null && allowedPeerNode != null && node != allowedPeerNode) { link.disconnect(handle); return }
        val s = Session(link, handle, elapsed(), node)
        sessions[link to handle] = s
        val hello = Msg.Hello(s.nonce, MeshPolicy.MAX_BUNDLE_BYTES, Messages.ROUTE_GATT or Messages.ROUTE_LAN, initialCredit, messageLimit, listOfNotNull(timeHint()))
        if (!send(s, Messages.encode(hello))) drop(s)
    }

    fun disconnected(link: Link, handle: String) {
        val s = sessions.remove(link to handle) ?: return
        if (s.sawReconcile) s.link.synced(s.handle)
        messages.remove(s)
    }

    fun drained(link: Link, handle: String) { sessions[link to handle]?.let { if (it.peer != null) pump(it) } }

    fun message(link: Link, handle: String, bytes: ByteArray) {
        val s = sessions[link to handle] ?: return
        val (windowStart, count) = messages[s] ?: (elapsed() to 0)
        val window = if (elapsed() - windowStart >= 60_000) elapsed() to 1 else windowStart to count + 1
        messages[s] = window
        if (window.second > MESSAGES_PER_MINUTE) { drop(s); return }
        s.lastActivity = elapsed()
        try {
            val m = Messages.decode(bytes)
            if (m is Msg.Hello) { hello(s, m); return }
            require(s.peer != null) { "HELLO required" }
            when (m) {
                is Msg.Reconcile -> reconcile(s, m)
                is Msg.Inventory -> { s.sawReconcile.let { if (it) s.link.synced(s.handle) }; m.ids.forEach { s.peerHas += hex(it) }; want(s, m.ids.map(::hex)) }
                is Msg.Want -> { if (s.sawReconcile) s.link.synced(s.handle); want(s, m) }
                is Msg.Begin -> begin(s, m)
                is Msg.Data -> data(s, m)
                is Msg.Credit -> credit(s, m)
                is Msg.Result -> result(s, m)
                is Msg.Fetch -> m.hashes.forEach { send(s, Messages.encode(Msg.Result(Msg.Target.Object(s.peerSide, m.requestId, it), Messages.RESULT_UNAVAILABLE, Messages.REASON_MISSING))) }
                is Msg.Unknown -> log("ignoring unknown message type ${m.type} from ${s.label}")
                is Msg.Hello -> Unit
            }
        } catch (e: Exception) {
            log("reject message from ${s.label}: ${e.message}")
            note("不正・制限超過メッセージを拒否")
            s.link.rejected(s.handle)
            drop(s)
        }
    }

    /** Offers newly held candidates to every peer; side 0 also re-reconciles when its set changed since the last pass. */
    fun broadcastInventory() = sessions.values.filter { it.peer != null }.forEach { s ->
        inventory(s)
        if (s.side == 0 && s.reconcile == null && s.reconciledSummary?.contentEquals(engine.summary(now(), elapsed())) != true) startReconcile(s)
    }

    /** Drops connections that never completed HELLO and times out stalled transfers, so a silent peer cannot hold resources. */
    fun dropStale() {
        val t = elapsed()
        sessions.values.filter { it.peer == null && t - it.began > HELLO_TIMEOUT_MS }.forEach(::drop)
        for (s in sessions.values.toList()) {
            s.incoming.values.filter { t - it.lastActivity > TRANSFER_TIMEOUT_MS }.forEach {
                s.incoming.remove(it.id); s.closedIncoming += it.id
                send(s, Messages.encode(Msg.Result(Msg.Target.Transfer(s.peerSide, it.id), Messages.RESULT_REJECTED, Messages.REASON_RESOURCES)))
                s.requests[it.requestId]?.remove(it.eventId)
            }
            s.outgoing.values.filter { it.complete && t - it.startedAt > TRANSFER_TIMEOUT_MS }.forEach { s.outgoing.remove(it.id) }
            if (s.reconcile != null && t - s.reconcileStarted > RECONCILE_TIMEOUT_MS) { s.reconcile = null; log("reconciliation timed out with ${s.label}") }
            s.requests.entries.removeAll { it.value.isEmpty() }
            if (s.peer != null) pump(s)
        }
    }

    fun clear() { sessions.clear(); messages.clear() }

    /** After an unblock; still-blocked authors are simply refused once more. */
    fun forgetRefused() = refused.clear()

    private fun hello(s: Session, m: Msg.Hello) {
        require(s.peer == null) { "Duplicate HELLO" }
        if (m.nonce.contentEquals(s.nonce)) { drop(s); return }
        s.peer = m
        s.link.hello(s.handle)
        s.side = if (compareUnsigned(s.nonce, m.nonce) < 0) 0 else 1
        m.extensions.firstOrNull { it["\$type"] == Messages.TIME_HINT }?.let { hint ->
            val peerNow = hint["now"] as? Long; val confidence = (hint["confidence"] as? Long)?.toInt()
            if (peerNow != null && confidence != null && confidence in 0..3) onTimeHint(s.label, peerNow - now() / 1000, confidence)
        }
        log("hello from ${s.label} on ${s.link.name}:${s.handle} side=${s.side} max=${m.maxBundleBytes} msg=${m.maxMessageBytes} credit=${m.initialCredit}")
        if (s.side == 0) startReconcile(s)
    }

    private fun candidates(): List<Negentropy.Item> = engine.entries()
        .filter { allowedAuthor == null || it.event.pubkey == allowedAuthor }.map { it.event.id }.toSet()
        .let { allowed -> engine.candidates(now(), elapsed()).filter { hex(it.id) in allowed } }

    private fun frameLimit(s: Session) = minOf(messageLimit, s.peer!!.maxMessageBytes)

    private fun startReconcile(s: Session) {
        val items = candidates()
        val neg = Negentropy(items, frameLimit(s) - Messages.RECONCILE_OVERHEAD)
        s.reconcile = neg; s.reconcileId = s.nextReconcile++; s.reconcileStarted = elapsed()
        s.reconcileCandidates = items.mapTo(hashSetOf()) { hex(it.id) }
        s.sawReconcile = true
        send(s, Messages.encode(Msg.Reconcile(s.reconcileId, neg.initiate())))
    }

    private fun reconcile(s: Session, m: Msg.Reconcile) {
        s.sawReconcile = true
        val ours = s.reconcile
        if (ours != null && m.id == s.reconcileId) {
            val next = ours.reconcile(m.message)
            if (next != null) { send(s, Messages.encode(Msg.Reconcile(m.id, next))); return }
            val have = ours.haveIds.mapTo(hashSetOf()) { hex(it) }
            s.peerHas += s.reconcileCandidates - have
            s.reconcile = null
            s.reconciledSummary = engine.summary(now(), elapsed())
            log("reconciled with ${s.label}: have ${have.size} need ${ours.needIds.size}")
            want(s, ours.needIds.map(::hex))
            inventory(s, have)
            s.link.synced(s.handle)
            return
        }
        // Peer-initiated: answer from our current candidate set.
        val responder = Negentropy(candidates(), frameLimit(s) - Messages.RECONCILE_OVERHEAD)
        send(s, Messages.encode(Msg.Reconcile(m.id, responder.reconcile(m.message) ?: return)))
    }

    private fun want(s: Session, ids: List<String>) {
        val owned = engine.entries().mapTo(hashSetOf()) { it.event.id }
        val open = s.requests.values.flatten().toSet()
        val missing = ids.filter { it !in owned && it !in refused && it !in open && !skip(it) }.distinct()
        if (missing.isEmpty()) return
        for (chunk in missing.chunked(Messages.idsPerMessage(frameLimit(s)))) {
            val id = s.nextRequest++
            s.requests[id] = chunk.toMutableSet()
            send(s, Messages.encode(Msg.Want(id, chunk.map(::unhex))))
        }
        log("want ${missing.size} from ${s.label}")
    }

    private fun inventory(s: Session, candidates: Set<String> = engine.inventory(now(), elapsed()).toSet()) {
        val allowed = engine.entries().filter { allowedAuthor == null || it.event.pubkey == allowedAuthor }.mapTo(hashSetOf()) { it.event.id }
        val fresh = candidates.filter { it in allowed && it !in s.peerHas && it !in s.offered }
        if (fresh.isEmpty()) return
        for (chunk in fresh.chunked(Messages.idsPerMessage(frameLimit(s)))) send(s, Messages.encode(Msg.Inventory(chunk.map(::unhex))))
        s.offered += fresh
    }

    private fun want(s: Session, m: Msg.Want) {
        val eligible = engine.inventory(now(), elapsed()).toSet()
        val allowed = engine.entries().filter { allowedAuthor == null || it.event.pubkey == allowedAuthor }.mapTo(hashSetOf()) { it.event.id }
        for (id in m.ids) {
            val h = hex(id)
            if (h in eligible && h in allowed) s.queue += h to m.requestId
            else send(s, Messages.encode(Msg.Result(Msg.Target.Event(s.peerSide, m.requestId, id), Messages.RESULT_UNAVAILABLE, Messages.REASON_MISSING)))
        }
        log("want ${m.ids.size} from ${s.label}, queued ${s.queue.size}")
        pump(s)
    }

    /** Starts queued transfers (≤ 8 awaiting RESULT) and pushes DATA within credit, keeping the link queue short. */
    private fun pump(s: Session) {
        val peer = s.peer ?: return
        val fragmentMax = frameLimit(s) - Messages.DATA_OVERHEAD
        while (true) {
            val t = s.sending
            if (t == null) {
                if (s.outgoing.size >= MAX_OUTSTANDING || s.queue.isEmpty()) return
                val (id, requestId) = s.queue.removeFirst()
                val bytes = engine.forward(id, now(), elapsed())
                if (bytes == null) { send(s, Messages.encode(Msg.Result(Msg.Target.Event(s.peerSide, requestId, unhex(id)), Messages.RESULT_UNAVAILABLE, Messages.REASON_MISSING))); continue }
                if (bytes.size > peer.maxBundleBytes) { send(s, Messages.encode(Msg.Result(Msg.Target.Event(s.peerSide, requestId, unhex(id)), Messages.RESULT_UNAVAILABLE, Messages.REASON_SIZE))); continue }
                val transfer = Session.OutTransfer(s.nextTransfer++, id, bytes, requestId, minOf(peer.initialCredit, bytes.size), elapsed())
                val first = bytes.copyOf(minOf(transfer.credit, frameLimit(s) - Messages.BEGIN_OVERHEAD))
                if (!send(s, Messages.encode(Msg.Begin(transfer.id, requestId, unhex(id), bytes.size, first.takeIf { it.isNotEmpty() })))) {
                    s.queue.addFirst(id to requestId) // link backlogged: retry on the next drain
                    return
                }
                s.outgoing[transfer.id] = transfer
                s.sending = transfer
                transfer.sent = first.size
                log("bundle ${id.take(8)} ${bytes.size}B to ${s.label}: begin")
                continue
            }
            if (t.complete) { s.sending = null; continue }
            if (t.sent >= t.credit) return // waiting for CREDIT
            if (s.link.queued(s.handle) >= MAX_QUEUED_FRAGMENTS) return
            val end = minOf(t.credit, t.sent + fragmentMax)
            if (!send(s, Messages.encode(Msg.Data(t.id, t.bytes.copyOfRange(t.sent, end))))) return
            t.sent = end
        }
    }

    private fun credit(s: Session, m: Msg.Credit) {
        val t = s.outgoing[m.transferId] ?: return
        if (m.limit > t.credit) t.credit = minOf(m.limit, t.bytes.size)
        pump(s)
    }

    private fun result(s: Session, m: Msg.Result) {
        when (val target = m.target) {
            is Msg.Target.Transfer -> {
                if (target.side != s.side) return
                val t = s.outgoing.remove(target.transferId) ?: return
                if (s.sending === t) s.sending = null
                when (m.code) {
                    Messages.RESULT_STORED, Messages.RESULT_ALREADY_HELD -> { s.peerHas += t.eventId; forwarded(t.eventId, s.label) }
                    else -> log("peer ${s.label} refused ${t.eventId.take(8)}: code ${m.code} reason ${m.reason}")
                }
                pump(s)
            }
            is Msg.Target.Event -> {
                if (target.side != s.side) return
                s.requests[target.requestId]?.remove(hex(target.id))
            }
            is Msg.Target.Object -> Unit
        }
    }

    private fun begin(s: Session, m: Msg.Begin) {
        val id = hex(m.target)
        fun reply(code: Int, reason: Int) { s.closedIncoming += m.transferId; trimClosed(s); send(s, Messages.encode(Msg.Result(Msg.Target.Transfer(s.peerSide, m.transferId), code, reason))) }
        val request = s.requests[m.requestId]
        if (request == null || id !in request) { reply(Messages.RESULT_REJECTED, Messages.REASON_POLICY); return }
        if (engine.entries().any { it.event.id == id }) { request.remove(id); s.peerHas += id; reply(Messages.RESULT_ALREADY_HELD, Messages.REASON_NONE); return }
        if (m.length > MeshPolicy.MAX_BUNDLE_BYTES) { request.remove(id); reply(Messages.RESULT_REJECTED, Messages.REASON_SIZE); return }
        if (s.incoming.size >= MAX_OUTSTANDING || m.transferId in s.incoming) { reply(Messages.RESULT_REJECTED, Messages.REASON_RESOURCES); return }
        val t = Session.InTransfer(m.transferId, id, m.requestId, m.length, elapsed())
        t.granted = minOf(initialCredit, m.length)
        s.incoming[m.transferId] = t
        if (m.fragment != null) append(s, t, m.fragment) else grant(s, t)
    }

    private fun data(s: Session, m: Msg.Data) {
        val t = s.incoming[m.transferId] ?: run { require(m.transferId in s.closedIncoming) { "DATA for unknown transfer" }; return }
        append(s, t, m.fragment)
    }

    private fun append(s: Session, t: Session.InTransfer, fragment: ByteArray) {
        t.lastActivity = elapsed()
        if (t.buffer.size() + fragment.size > t.granted || t.buffer.size() + fragment.size > t.length) {
            s.incoming.remove(t.id); s.closedIncoming += t.id; trimClosed(s)
            s.requests[t.requestId]?.remove(t.eventId)
            send(s, Messages.encode(Msg.Result(Msg.Target.Transfer(s.peerSide, t.id), Messages.RESULT_REJECTED, Messages.REASON_INVALID)))
            return
        }
        t.buffer.write(fragment)
        if (t.buffer.size() == t.length) finish(s, t) else grant(s, t)
    }

    /** Mesh §5.9: a whole-Bundle grant; the engine bounds the size and the assembly buffer is the only resource. */
    private fun grant(s: Session, t: Session.InTransfer) {
        if (t.granted >= t.length) return
        t.granted = t.length
        send(s, Messages.encode(Msg.Credit(t.id, t.length)))
    }

    private fun finish(s: Session, t: Session.InTransfer) {
        s.incoming.remove(t.id); s.closedIncoming += t.id; trimClosed(s)
        s.requests[t.requestId]?.remove(t.eventId)
        val bytes = t.buffer.toByteArray()
        val target = Msg.Target.Transfer(s.peerSide, t.id)
        val entry = try { engine.accept(bytes, now(), elapsed()) } catch (e: BlockedAuthor) {
            refused.add(e.id)
            if (refused.size > MAX_REFUSED) refused.remove(refused.first())
            log("refused blocked ${e.id.take(8)}")
            send(s, Messages.encode(Msg.Result(target, Messages.RESULT_REJECTED, Messages.REASON_POLICY)))
            return
        } catch (e: IllegalArgumentException) {
            val why = e.message.orEmpty()
            val reason = when {
                "size" in why || "limit" in why -> Messages.REASON_SIZE
                "expired" in why || "deadline" in why -> Messages.REASON_EXPIRED
                "capacity" in why || "rate" in why -> Messages.REASON_RESOURCES
                else -> Messages.REASON_INVALID
            }
            log("rejected bundle from ${s.label}: $why")
            send(s, Messages.encode(Msg.Result(target, Messages.RESULT_REJECTED, reason)))
            return
        }
        if (entry == null) { s.peerHas += t.eventId; send(s, Messages.encode(Msg.Result(target, Messages.RESULT_ALREADY_HELD, Messages.REASON_NONE))); return }
        require(entry.event.id == t.eventId) { "Bundle carries a different event than announced" }
        accepted(entry)
        s.peerHas += t.eventId
        send(s, Messages.encode(Msg.Result(target, Messages.RESULT_STORED, Messages.REASON_NONE)))
        note("署名検証・保存 ${entry.event.id.take(8)} / 観測 hop ${entry.hop}")
        broadcastInventory()
    }

    private fun trimClosed(s: Session) { while (s.closedIncoming.size > 64) s.closedIncoming.remove(s.closedIncoming.first()) }

    // android.util.Log throws "not mocked" in JVM unit tests; runCatching keeps Exchange testable there.
    private fun log(text: String) { runCatching { Log.i("Exchange", text) } }
    private fun send(s: Session, message: ByteArray): Boolean {
        val limit = s.peer?.maxMessageBytes ?: Messages.HELLO_MAX_BYTES
        if (message.size > limit) { log("message of ${message.size}B exceeds peer limit $limit"); return false }
        return s.link.send(s.handle, message)
    }
    private fun drop(s: Session) { sessions.remove(s.link to s.handle); messages.remove(s); s.link.disconnect(s.handle) }
    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) { val c = (a[i].toInt() and 255).compareTo(b[i].toInt() and 255); if (c != 0) return c }
        return a.size.compareTo(b.size)
    }

    companion object {
        const val MAX_SESSIONS = 16
        const val MAX_REFUSED = 1024
        /** Our receive limit per common message (mesh §6); links cut their byte streams against it. */
        const val MESSAGE_LIMIT = 16_384
        /** Mesh §5.4 initial credit: bytes a peer may send after BEGIN before our CREDIT. */
        const val INITIAL_CREDIT = 4096
        /** Mesh §4.5: transfers per direction awaiting RESULT. */
        const val MAX_OUTSTANDING = 8
        const val MAX_QUEUED_FRAGMENTS = 2
        const val MESSAGES_PER_MINUTE = 4096
        const val HELLO_TIMEOUT_MS = 60_000L
        const val TRANSFER_TIMEOUT_MS = 90_000L
        const val RECONCILE_TIMEOUT_MS = 60_000L
    }
}
