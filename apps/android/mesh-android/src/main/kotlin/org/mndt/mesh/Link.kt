package org.mndt.mesh

import java.io.ByteArrayOutputStream

/** Link callbacks may arrive on any thread; the coordinator re-serializes them onto its actor. */
internal interface LinkListener {
    fun onConnected(link: Link, peer: String)
    fun onFrame(link: Link, peer: String, frame: ByteArray)
    fun onDisconnected(link: Link, peer: String)
}

/**
 * One transport (BLE GATT, LAN TCP). It discovers and connects on its own and moves whole frames;
 * store-carry-forward stays in MeshCoordinator/MeshEngine. Peer IDs are link-local connection handles.
 */
internal interface Link {
    val name: String
    /** [node] is this process's 16-hex node ID, used only to break ties so two nodes open one connection. */
    fun start(node: String, listener: LinkListener)
    fun stop()
    /** Queues one whole frame of at most [Frames.MAX_FRAME] bytes; false when the peer is gone or backlogged. */
    fun send(peer: String, frame: ByteArray): Boolean
    fun disconnect(peer: String)
    /** The peer's HELLO named [node]; a link may use it to avoid opening a second connection to the same node. */
    fun identified(peer: String, node: String) {}
}

/** Provisional framing (protocol/mndt.md §9): 4-byte big-endian length, then the frame, on any byte stream. */
internal object Frames {
    const val MAX_FRAME = Wire.MAX_BYTES
    fun encode(frame: ByteArray): ByteArray {
        require(frame.isNotEmpty() && frame.size <= MAX_FRAME) { "Frame size" }
        val n = frame.size
        return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + frame
    }
}

/** Reassembles frames from arbitrary chunk boundaries (GATT writes, TCP reads). Throws on a bad length. */
internal class FrameReader {
    private val pending = ByteArrayOutputStream()
    fun feed(chunk: ByteArray): List<ByteArray> {
        pending.write(chunk)
        val bytes = pending.toByteArray()
        val frames = mutableListOf<ByteArray>()
        var at = 0
        while (bytes.size - at >= 4) {
            val n = ((bytes[at].toInt() and 255) shl 24) or ((bytes[at + 1].toInt() and 255) shl 16) or
                ((bytes[at + 2].toInt() and 255) shl 8) or (bytes[at + 3].toInt() and 255)
            require(n in 1..Frames.MAX_FRAME) { "Frame size" }
            if (bytes.size - at - 4 < n) break
            frames += bytes.copyOfRange(at + 4, at + 4 + n)
            at += 4 + n
        }
        pending.reset(); pending.write(bytes, at, bytes.size - at)
        return frames
    }
}
