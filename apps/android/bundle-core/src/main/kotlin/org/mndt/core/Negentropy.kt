package org.mndt.core

import java.io.ByteArrayOutputStream

/**
 * Negentropy v1 range-based set reconciliation (NIP-77) over `(created_at, event ID)` items, as the mesh §5.5
 * `RECONCILE` payload. One instance holds one side's candidate set for one reconciliation. The initiator calls
 * [initiate] and then [reconcile] on each reply until it returns null; the responder calls [reconcile] on each
 * query. [frameSizeLimit] bounds one message (0 = unlimited); each `RECONCILE` must still fit the peer's limit.
 */
class Negentropy(items: Collection<Item>, private val frameSizeLimit: Int = 0) {
    class Item(val timestamp: Long, val id: ByteArray) : Comparable<Item> {
        init { require(timestamp >= 0 && id.size == ID_SIZE) }
        override fun compareTo(other: Item): Int = compareItem(timestamp, id, other.timestamp, other.id)
    }
    /** A range boundary: a timestamp and an ID prefix; [INFINITY] is the open upper end. */
    private class Bound(val timestamp: Long, val prefix: ByteArray) {
        fun compareTo(item: Item): Int = compareItem(timestamp, prefix, item.timestamp, item.id)
    }

    private val items: List<Item> = items.sortedWith(Comparator.naturalOrder()).also { sorted ->
        for (i in 1 until sorted.size) require(sorted[i - 1].compareTo(sorted[i]) != 0) { "Duplicate item" }
    }
    private var isInitiator = false
    private val have = mutableListOf<ByteArray>()
    private val need = mutableListOf<ByteArray>()
    private var lastTimestampIn = 0L
    private var lastTimestampOut = 0L

    /** IDs only this side holds (after the initiator finishes). */
    val haveIds: List<ByteArray> get() = have.toList()
    /** IDs only the peer holds. */
    val needIds: List<ByteArray> get() = need.toList()

    init { require(frameSizeLimit == 0 || frameSizeLimit >= 512) { "Frame size limit too small" } }

    fun initiate(): ByteArray {
        isInitiator = true
        lastTimestampOut = 0
        val out = ByteArrayOutputStream()
        out.write(PROTOCOL_VERSION)
        splitRange(0, items.size, INFINITY, out)
        return out.toByteArray()
    }

    /** Processes one message from the peer; returns the next message, or null when an initiator has finished. */
    fun reconcile(query: ByteArray): ByteArray? {
        val out = reconcileAux(query)
        return if (out.size == 1 && isInitiator) null else out
    }

    private fun reconcileAux(queryBytes: ByteArray): ByteArray {
        lastTimestampIn = 0; lastTimestampOut = 0
        val query = Reader(queryBytes)
        val full = ByteArrayOutputStream()
        full.write(PROTOCOL_VERSION)
        val version = query.byte()
        require(version in 0x60..0x6f) { "Invalid negentropy protocol version" }
        if (version != PROTOCOL_VERSION) {
            require(!isInitiator) { "Unsupported negentropy protocol version" }
            return full.toByteArray()
        }
        var prevBound = Bound(0, ByteArray(0))
        var prevIndex = 0
        // Index up to which ranges have actually been written to [full]; a dropped range is re-fingerprinted from here.
        var writtenIndex = 0
        var skip = false
        while (!query.isEmpty()) {
            val o = ByteArrayOutputStream()
            fun doSkip() { if (skip) { skip = false; encodeBound(prevBound, o); encodeVarInt(MODE_SKIP, o) } }
            val currBound = decodeBound(query)
            val mode = decodeVarInt(query)
            val lower = prevIndex
            var upper = findLowerBound(prevIndex, items.size, currBound)
            when (mode) {
                MODE_SKIP -> skip = true
                MODE_FINGERPRINT -> {
                    val theirs = query.bytes(FINGERPRINT_SIZE)
                    if (!theirs.contentEquals(fingerprint(lower, upper))) { doSkip(); splitRange(lower, upper, currBound, o) } else skip = true
                }
                MODE_ID_LIST -> {
                    val count = decodeVarInt(query)
                    require(count <= 1_000_000) { "ID list too long" }
                    val theirs = LinkedHashMap<String, ByteArray>()
                    repeat(count.toInt()) { val id = query.bytes(ID_SIZE); theirs[hex(id)] = id }
                    if (isInitiator) {
                        skip = true
                        for (i in lower until upper) {
                            val key = hex(items[i].id)
                            if (theirs.remove(key) == null) have += items[i].id
                        }
                        need += theirs.values
                    } else {
                        doSkip()
                        val ids = ByteArrayOutputStream()
                        var responded = 0L
                        var endBound = currBound
                        for (i in lower until upper) {
                            if (exceeded(full.size() + o.size() + ids.size())) { endBound = Bound(items[i].timestamp, items[i].id); upper = i; break }
                            ids.write(items[i].id); responded++
                        }
                        encodeBound(endBound, o); encodeVarInt(MODE_ID_LIST, o); encodeVarInt(responded, o); o.write(ids.toByteArray())
                        full.write(o.toByteArray()); o.reset()
                        writtenIndex = upper
                    }
                }
                else -> error("Unexpected negentropy mode")
            }
            if (exceeded(full.size() + o.size())) {
                // Stop here and fingerprint everything the peer has not yet seen covered, up to infinity.
                encodeBound(INFINITY, full); encodeVarInt(MODE_FINGERPRINT, full); full.write(fingerprint(writtenIndex, items.size))
                break
            }
            if (o.size() > 0) { full.write(o.toByteArray()); writtenIndex = upper }
            prevIndex = upper
            prevBound = currBound
        }
        return full.toByteArray()
    }

    private fun splitRange(lower: Int, upper: Int, upperBound: Bound, o: ByteArrayOutputStream) {
        val numElems = upper - lower
        // An ID list must fit one frame; with small frames, split instead (still a valid message for any peer).
        if (numElems < BUCKETS * 2 && (frameSizeLimit == 0 || numElems * ID_SIZE + 64 <= frameSizeLimit - 200)) {
            encodeBound(upperBound, o); encodeVarInt(MODE_ID_LIST, o); encodeVarInt(numElems.toLong(), o)
            for (i in lower until upper) o.write(items[i].id)
            return
        }
        val buckets = minOf(BUCKETS, numElems)
        val perBucket = numElems / buckets
        val extra = numElems % buckets
        var curr = lower
        for (i in 0 until buckets) {
            val size = perBucket + if (i < extra) 1 else 0
            val fp = fingerprint(curr, curr + size)
            curr += size
            val next = if (curr == upper) upperBound else minimalBound(items[curr - 1], items[curr])
            encodeBound(next, o); encodeVarInt(MODE_FINGERPRINT, o); o.write(fp)
        }
    }

    private fun minimalBound(prev: Item, curr: Item): Bound {
        if (curr.timestamp != prev.timestamp) return Bound(curr.timestamp, ByteArray(0))
        var shared = 0
        while (shared < ID_SIZE && curr.id[shared] == prev.id[shared]) shared++
        return Bound(curr.timestamp, curr.id.copyOf(shared + 1))
    }

    private fun findLowerBound(from: Int, to: Int, bound: Bound): Int {
        var lo = from; var hi = to
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (bound.compareTo(items[mid]) <= 0) hi = mid else lo = mid + 1 }
        return lo
    }

    /** Sum of the IDs as 256-bit little-endian integers mod 2^256, then SHA-256 over sum || varint(count), first 16 bytes. */
    private fun fingerprint(from: Int, to: Int): ByteArray {
        val acc = IntArray(ID_SIZE)
        for (i in from until to) {
            var carry = 0
            for (b in 0 until ID_SIZE) {
                val sum = acc[b] + (items[i].id[b].toInt() and 255) + carry
                acc[b] = sum and 255; carry = sum ushr 8
            }
        }
        val input = ByteArrayOutputStream()
        for (b in acc) input.write(b)
        encodeVarInt((to - from).toLong(), input)
        return sha256(input.toByteArray()).copyOf(FINGERPRINT_SIZE)
    }

    private fun exceeded(size: Int) = frameSizeLimit != 0 && size > frameSizeLimit - 200

    private fun encodeBound(bound: Bound, o: ByteArrayOutputStream) {
        encodeTimestampOut(bound.timestamp, o)
        encodeVarInt(bound.prefix.size.toLong(), o)
        o.write(bound.prefix)
    }
    private fun decodeBound(r: Reader): Bound {
        val timestamp = decodeTimestampIn(r)
        val length = decodeVarInt(r)
        require(length <= ID_SIZE) { "ID prefix too long" }
        return Bound(timestamp, r.bytes(length.toInt()))
    }
    private fun encodeTimestampOut(timestamp: Long, o: ByteArrayOutputStream) {
        if (timestamp == Long.MAX_VALUE) { lastTimestampOut = Long.MAX_VALUE; encodeVarInt(0, o); return }
        val delta = timestamp - lastTimestampOut
        lastTimestampOut = timestamp
        encodeVarInt(delta + 1, o)
    }
    private fun decodeTimestampIn(r: Reader): Long {
        val raw = decodeVarInt(r)
        val timestamp = if (raw == 0L) Long.MAX_VALUE else raw - 1
        if (lastTimestampIn == Long.MAX_VALUE || timestamp == Long.MAX_VALUE) { lastTimestampIn = Long.MAX_VALUE; return Long.MAX_VALUE }
        val absolute = Math.addExact(timestamp, lastTimestampIn)
        lastTimestampIn = absolute
        return absolute
    }

    private class Reader(private val bytes: ByteArray) {
        private var at = 0
        fun isEmpty() = at >= bytes.size
        fun byte(): Int { require(at < bytes.size) { "Truncated negentropy message" }; return bytes[at++].toInt() and 255 }
        fun bytes(n: Int): ByteArray { require(at + n <= bytes.size) { "Truncated negentropy message" }; return bytes.copyOfRange(at, at + n).also { at += n } }
    }

    companion object {
        const val PROTOCOL_VERSION = 0x61
        const val ID_SIZE = 32
        const val FINGERPRINT_SIZE = 16
        private const val BUCKETS = 16
        private const val MODE_SKIP = 0L
        private const val MODE_FINGERPRINT = 1L
        private const val MODE_ID_LIST = 2L
        private val INFINITY = Bound(Long.MAX_VALUE, ByteArray(0))

        /** Timestamp first, then the ID (or prefix) lexicographically; a shorter equal prefix sorts first. */
        private fun compareItem(t1: Long, id1: ByteArray, t2: Long, id2: ByteArray): Int {
            if (t1 != t2) return t1.compareTo(t2)
            for (i in 0 until minOf(id1.size, id2.size)) { val c = (id1[i].toInt() and 255).compareTo(id2[i].toInt() and 255); if (c != 0) return c }
            return id1.size.compareTo(id2.size)
        }
        internal fun encodeVarInt(value: Long, o: ByteArrayOutputStream) {
            require(value >= 0)
            if (value == 0L) { o.write(0); return }
            val groups = ArrayDeque<Int>()
            var n = value
            while (n != 0L) { groups.addFirst((n and 0x7f).toInt()); n = n ushr 7 }
            for ((i, g) in groups.withIndex()) o.write(if (i < groups.size - 1) g or 0x80 else g)
        }
        private fun decodeVarInt(r: Reader): Long {
            var value = 0L
            var count = 0
            while (true) {
                val b = r.byte()
                require(++count <= 10 && value <= (Long.MAX_VALUE ushr 7)) { "Varint overflow" }
                value = (value shl 7) or (b and 0x7f).toLong()
                if (b and 0x80 == 0) return value
            }
        }
    }
}
