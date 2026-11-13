package org.mndt.mesh

import org.mndt.core.CanonicalCbor
import java.io.ByteArrayOutputStream

/** Link callbacks may arrive on any thread; the coordinator re-serializes them onto its actor. */
internal interface LinkListener {
    /** [node] is the peer's advertised node ID when this side discovered it (central / dialer); null otherwise. */
    fun onConnected(link: Link, peer: String, node: String?)
    /** One complete common exchange message (mesh §5), cut from the link's CBOR sequence. */
    fun onMessage(link: Link, peer: String, message: ByteArray)
    /** The link's outbound queue for [peer] drained; the exchange may push the next fragments. */
    fun onDrained(link: Link, peer: String)
    fun onDisconnected(link: Link, peer: String)
}

/**
 * One transport (BLE GATT, LAN TCP). It discovers and connects on its own and moves whole CBOR messages as an
 * ordered byte stream (mesh §7); store-carry-forward stays in MeshCoordinator/MeshEngine. Peer IDs are link-local
 * connection handles.
 */
internal interface Link {
    val name: String
    /** [node] is this process's 16-hex node ID, used only to break ties so two nodes open one connection. */
    fun start(node: String, listener: LinkListener)
    fun stop()
    /** Queues one whole message of at most [Messages.MAX_MESSAGE_BYTES] bytes; false when the peer is gone or backlogged. */
    fun send(peer: String, message: ByteArray): Boolean
    /** Number of queued messages not yet handed to the OS for [peer]; the exchange keeps this small so control messages get through. */
    fun queued(peer: String): Int = 0
    fun disconnect(peer: String)
    /** Reconciliation with [peer] completed; a link may remember the peer's advertised summary to skip idle reconnects (mesh.adv §4). */
    fun synced(peer: String) {}
    /** [peer] sent a valid HELLO; LocalLink uses it to tell a Mosimo holder from another app on its port. */
    fun hello(peer: String) {}
    /** [peer] broke the exchange framing (mesh §7, e.g. a pre-v1 build); a link may stop redialing its node for a while. */
    fun rejected(peer: String) {}
    /** Node IDs this link saw nearby within [withinMillis] (adverts, service discovery), connected or not. */
    fun nearby(withinMillis: Long): Set<String> = emptySet()
}

/**
 * Cuts a CBOR sequence (RFC 8742) arriving in arbitrary chunks into complete items. Throws when an item is malformed
 * or larger than [limit]; the stream is then out of sync and the link must close (mesh §7).
 */
internal class CborSequenceReader(private val limit: Int) {
    private val pending = ByteArrayOutputStream()
    fun feed(chunk: ByteArray): List<ByteArray> {
        pending.write(chunk)
        val bytes = pending.toByteArray()
        val items = mutableListOf<ByteArray>()
        var at = 0
        while (at < bytes.size) {
            val length = CanonicalCbor.itemLength(bytes, at, bytes.size, limit) ?: break
            items += bytes.copyOfRange(at, at + length)
            at += length
        }
        require(bytes.size - at <= limit) { "Incomplete message exceeds limit" }
        pending.reset(); pending.write(bytes, at, bytes.size - at)
        return items
    }
    /** Bytes of an item still being assembled. */
    val incomplete: Int get() = pending.size()
}
