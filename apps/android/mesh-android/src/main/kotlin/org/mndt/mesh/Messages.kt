package org.mndt.mesh

import org.mndt.core.CanonicalCbor

/**
 * Common exchange messages (mesh §5): CBOR arrays whose first element is the numeric type. Encoded with the
 * deterministic encoder; decoded leniently so other implementations' definite-length encodings are accepted.
 */
internal sealed interface Msg {
    data class Hello(val nonce: ByteArray, val maxBundleBytes: Int, val routes: Int, val initialCredit: Int, val maxMessageBytes: Int,
        val extensions: List<Map<String, Any?>>) : Msg
    data class Inventory(val ids: List<ByteArray>) : Msg
    data class Want(val requestId: Long, val ids: List<ByteArray>) : Msg
    /** [target] is an event ID (WANT) or an object hash (FETCH); [fragment] is the optional first fragment. */
    data class Begin(val transferId: Long, val requestId: Long, val target: ByteArray, val length: Int, val fragment: ByteArray?) : Msg
    data class Data(val transferId: Long, val fragment: ByteArray) : Msg
    data class Credit(val transferId: Long, val limit: Int) : Msg
    data class Result(val target: Target, val code: Int, val reason: Int) : Msg
    data class Reconcile(val id: Long, val message: ByteArray) : Msg
    data class Fetch(val requestId: Long, val hashes: List<ByteArray>) : Msg
    /** Mesh §5.2: unknown types are ignored. */
    data class Unknown(val type: Long) : Msg

    sealed interface Target {
        data class Event(val side: Int, val requestId: Long, val id: ByteArray) : Target
        data class Transfer(val side: Int, val transferId: Long) : Target
        data class Object(val side: Int, val requestId: Long, val hash: ByteArray) : Target
    }
}

internal object Messages {
    const val PROTOCOL_VERSION = 1L
    /** Mesh §6: the floor every implementation receives, and the ceiling. */
    const val MIN_MESSAGE_BYTES = 1024
    const val MAX_MESSAGE_BYTES = 65_536
    const val HELLO_MAX_BYTES = 1024
    const val ROUTE_GATT = 1
    const val ROUTE_LAN = 2
    const val ROUTE_NEARBY = 4
    const val RESULT_STORED = 0
    const val RESULT_ALREADY_HELD = 1
    const val RESULT_REJECTED = 2
    const val RESULT_UNAVAILABLE = 3
    const val RESULT_CANCELLED = 4
    const val REASON_NONE = 0
    const val REASON_RESOURCES = 1
    const val REASON_SIZE = 2
    const val REASON_INVALID = 3
    const val REASON_EXPIRED = 4
    const val REASON_POLICY = 5
    const val REASON_MISSING = 6
    const val TIME_HINT = "dev.jisaku.mosimo.mesh.time"
    /** Bytes of the `RECONCILE` wrapping subtracted from the message limit (mesh §5.5). */
    const val RECONCILE_OVERHEAD = 24
    /** Upper bound of a `BEGIN` wrapping around its optional first fragment (IDs ≤ 2^64, 32-byte target, length ≤ 2^32). */
    const val BEGIN_OVERHEAD = 64
    /** Bytes of a `DATA` wrapping around one fragment. */
    const val DATA_OVERHEAD = 24
    /** Per 32-byte ID in an `INVENTORY`/`WANT` list (2-byte bstr header + payload). */
    const val ID_ITEM_BYTES = 34

    fun encode(m: Msg): ByteArray = CanonicalCbor.encode(when (m) {
        is Msg.Hello -> listOf(0L, PROTOCOL_VERSION, m.nonce, m.maxBundleBytes.toLong(), m.routes.toLong(), m.initialCredit.toLong(), m.maxMessageBytes.toLong(), m.extensions)
        is Msg.Inventory -> listOf(1L, m.ids)
        is Msg.Want -> listOf(2L, m.requestId, m.ids)
        is Msg.Begin -> listOfNotNull(3L, m.transferId, m.requestId, m.target, m.length.toLong(), m.fragment)
        is Msg.Data -> listOf(4L, m.transferId, m.fragment)
        is Msg.Credit -> listOf(5L, m.transferId, m.limit.toLong())
        is Msg.Result -> listOf(6L, when (val t = m.target) {
            is Msg.Target.Event -> listOf(0L, t.side.toLong(), t.requestId, t.id)
            is Msg.Target.Transfer -> listOf(1L, t.side.toLong(), t.transferId)
            is Msg.Target.Object -> listOf(2L, t.side.toLong(), t.requestId, t.hash)
        }, m.code.toLong(), m.reason.toLong())
        is Msg.Reconcile -> listOf(7L, m.id, m.message)
        is Msg.Fetch -> listOf(8L, m.requestId, m.hashes)
        is Msg.Unknown -> error("Cannot encode an unknown message")
    })

    fun decode(bytes: ByteArray): Msg {
        require(bytes.size in 1..MAX_MESSAGE_BYTES) { "Message size" }
        val a = CanonicalCbor.decode(bytes, canonical = false) as? List<*> ?: error("Expected message array")
        val type = a.getOrNull(0) as? Long ?: error("Expected message type")
        fun uint(i: Int): Long = (a.getOrNull(i) as? Long)?.also { require(it >= 0) } ?: error("Expected unsigned integer at $i")
        fun int(i: Int, range: IntRange): Int = uint(i).also { require(it in range.first.toLong()..range.last.toLong()) { "Value at $i out of range" } }.toInt()
        fun bstr(i: Int, size: Int? = null): ByteArray = (a.getOrNull(i) as? ByteArray)?.also { require(size == null || it.size == size) } ?: error("Expected bytes at $i")
        fun ids(i: Int): List<ByteArray> = (a.getOrNull(i) as? List<*> ?: error("Expected list at $i")).map { (it as? ByteArray)?.also { b -> require(b.size == 32) } ?: error("Expected 32-byte ID") }
            .also { require(it.size <= 2048 && it.distinctBy { b -> b.toList() }.size == it.size) { "Duplicate or oversized ID list" } }
        return when (type) {
            0L -> {
                require(a.size == 8 && bytes.size <= HELLO_MAX_BYTES) { "HELLO shape" }
                require(uint(1) == PROTOCOL_VERSION) { "Unsupported exchange protocol version" }
                val extensions = (a[7] as? List<*> ?: error("Expected extensions")).map { e ->
                    (e as? Map<*, *> ?: error("Expected extension map")).entries.associate { (k, v) -> (k as? String ?: error("Extension key")) to v }
                        .also { require(it["\$type"] is String) { "Extension requires \$type" } }
                }
                require(extensions.map { it["\$type"] }.distinct().size == extensions.size) { "Duplicate HELLO extension" }
                Msg.Hello(bstr(2, 8), int(3, 1..65_536), int(4, 0..0xffff), int(5, 0..65_536), int(6, MIN_MESSAGE_BYTES..MAX_MESSAGE_BYTES), extensions)
            }
            1L -> { require(a.size == 2); Msg.Inventory(ids(1)) }
            2L -> { require(a.size == 3); Msg.Want(uint(1), ids(2)) }
            3L -> { require(a.size == 5 || a.size == 6); Msg.Begin(uint(1), uint(2), bstr(3, 32), int(4, 1..MAX_MESSAGE_BYTES), if (a.size == 6) bstr(5) else null) }
            4L -> { require(a.size == 3); Msg.Data(uint(1), bstr(2)) }
            5L -> { require(a.size == 3); Msg.Credit(uint(1), int(2, 0..65_536)) }
            6L -> {
                require(a.size == 4)
                val t = a[1] as? List<*> ?: error("Expected result target")
                fun tu(i: Int): Long = (t.getOrNull(i) as? Long)?.also { require(it >= 0) } ?: error("Expected unsigned integer in target")
                val side = tu(1).also { require(it in 0..1) }.toInt()
                val target: Msg.Target = when (tu(0)) {
                    0L -> { require(t.size == 4); Msg.Target.Event(side, tu(2), (t[3] as? ByteArray)?.also { require(it.size == 32) } ?: error("Expected ID")) }
                    1L -> { require(t.size == 3); Msg.Target.Transfer(side, tu(2)) }
                    2L -> { require(t.size == 4); Msg.Target.Object(side, tu(2), (t[3] as? ByteArray)?.also { require(it.size == 32) } ?: error("Expected hash")) }
                    else -> error("Unknown result target")
                }
                Msg.Result(target, int(2, 0..0xffff), int(3, 0..0xffff))
            }
            7L -> { require(a.size == 3); Msg.Reconcile(uint(1), bstr(2)) }
            8L -> { require(a.size == 3); Msg.Fetch(uint(1), ids(2)) }
            else -> Msg.Unknown(type)
        }
    }

    /** How many 32-byte IDs fit one list message under [limit] bytes, leaving room for the array/ID wrapping. */
    fun idsPerMessage(limit: Int): Int = ((limit - 16) / ID_ITEM_BYTES).coerceIn(1, 2048)
}
