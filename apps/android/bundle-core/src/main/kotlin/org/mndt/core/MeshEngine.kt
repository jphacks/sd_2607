package org.mndt.core

import io.nodle.dtn.bpv7.*
import io.nodle.dtn.bpv7.extensions.*
import java.net.URI

// TODO(undecided): local resource policy; DESTINATION is the provisional EID proposed in protocol/mndt.md §9.
object MeshPolicy {
    const val DESTINATION = "dtn://mosimo.oriom.dev/~post"
    const val MAX_BUNDLE_BYTES = 24_000
    // Internal snapshots may grow by at most eight bytes as the CBOR Bundle Age integer grows.
    const val MAX_SAVED_BUNDLE_BYTES = MAX_BUNDLE_BYTES + 8
    const val MAX_ENTRIES = 128
    const val MAX_HOPS = 8
    const val MAX_RESIDENCE_MILLIS = 86_400_000L
    const val PER_AUTHOR_PER_MINUTE = 12
}

data class StoredBundle(
    val event: NostrEvent,
    val bytes: ByteArray,
    val hop: Int,
    val receivedAtMillis: Long,
    val receivedElapsedMillis: Long,
)

enum class ClockConfidence { UNKNOWN, TRUSTED }
enum class DeliveryDeadline { UNKNOWN, WITHIN_DEADLINE, EXPIRED }

/** Call from one serialized coordinator. Links only move bytes; this is the store-carry-forward router. */
class MeshEngine(var clockConfidence: ClockConfidence = ClockConfidence.UNKNOWN) {
    private val stored = linkedMapOf<String, StoredBundle>()
    /** Event IDs already accepted by an online relay; such entries are evicted first once they stop forwarding. */
    var relayed: (String) -> Boolean = { false }

    private val rates = mutableMapOf<String, MutableList<Long>>()
    private val sendRates = mutableMapOf<String, MutableList<Long>>()

    fun originate(event: NostrEvent, nowMillis: Long, elapsedMillis: Long): StoredBundle {
        require(event.verify()) { "invalid author signature" }
        val expiry = expiry(event)
        require(deadline(event, nowMillis) != DeliveryDeadline.EXPIRED) { "delivery deadline expired" }
        val lifetime = if (clockConfidence == ClockConfidence.TRUSTED)
            (expiry.toEpochMilli() - nowMillis).coerceIn(1, MeshPolicy.MAX_RESIDENCE_MILLIS)
        else MeshPolicy.MAX_RESIDENCE_MILLIS
        val bundle = PrimaryBlock(
            destination = URI(MeshPolicy.DESTINATION),
            // TODO(undecided): originating node EID; the author key prefix stands in for a node ID.
            source = URI("dtn://n${event.pubkey.take(32)}/post"),
            creationTimestamp = 0,
            // ponytail: 60-bit event-derived sequence; a persistent allocator if bundle-ID collisions matter.
            sequenceNumber = event.id.take(15).toLong(16),
            lifetime = lifetime,
        ).setProcV7Flags(BundleV7Flags.MustNotFragment).makeBundle()
            .addBlock(hopCountBlockData(MeshPolicy.MAX_HOPS, 0).crcType(CRCType.CRC32))
            .addBlock(ageBlock(0).crcType(CRCType.CRC32))
            .addBlock(payloadBlock(EventCodec.encode(event)).crcType(CRCType.CRC32))
        return accept(bundle.cborMarshal(), nowMillis, elapsedMillis)
            ?: throw IllegalArgumentException("already stored")
    }

    fun accept(bytes: ByteArray, nowMillis: Long, elapsedMillis: Long): StoredBundle? =
        insert(bytes, nowMillis, elapsedMillis, applyRateLimit = true)

    /** Without a monotonic downtime (reboot) the wall-clock gap counts as residence; a clock going backwards counts as zero. */
    fun restore(bytes: ByteArray, receivedAtMillis: Long, nowMillis: Long, elapsedMillis: Long,
                downtimeMillis: Long? = null): StoredBundle? {
        val bundle = parse(bytes, saved = true)
        val age = bundle.getAgeBlockData() ?: error("missing bundle age")
        require(downtimeMillis == null || downtimeMillis >= 0)
        // ponytail: an untrusted wall clock may under-count reboot downtime; MAX_RESIDENCE still bounds uptime.
        val downtime = downtimeMillis ?: positiveDifference(nowMillis, receivedAtMillis)
        age.age = saturatedAdd(age.age, downtime)
        // Expired entries remain available to the relay backlog, but never to mesh forwarding.
        return insert(bundle.cborMarshal(), nowMillis, elapsedMillis, applyRateLimit = false, restoring = true)
    }

    fun entries(): List<StoredBundle> = stored.values.toList()

    fun deadline(event: NostrEvent, nowMillis: Long): DeliveryDeadline =
        if (clockConfidence != ClockConfidence.TRUSTED) DeliveryDeadline.UNKNOWN
        else if (expiry(event).toEpochMilli() > nowMillis) DeliveryDeadline.WITHIN_DEADLINE else DeliveryDeadline.EXPIRED

    /** Snapshot accumulated monotonic age without modifying the stored original or signed event. */
    fun checkpoint(entry: StoredBundle, nowMillis: Long, elapsedMillis: Long): StoredBundle {
        val bundle = parse(entry.bytes, saved = true)
        bundle.getAgeBlockData()!!.age = currentAge(entry, elapsedMillis)
        val bytes = bundle.cborMarshal().also { require(it.size <= MeshPolicy.MAX_SAVED_BUNDLE_BYTES) }
        return entry.copy(bytes = bytes, receivedAtMillis = nowMillis, receivedElapsedMillis = elapsedMillis)
    }

    fun inventory(nowMillis: Long, elapsedMillis: Long): List<String> =
        stored.values.filter { eligible(it, nowMillis, elapsedMillis) }.map { it.event.id }

    /** Each retry derives a fresh send view; it never increments the stored hop count. */
    fun forward(id: String, nowMillis: Long, elapsedMillis: Long): ByteArray? {
        val entry = stored[id] ?: return null
        if (!eligible(entry, nowMillis, elapsedMillis)) return null
        val sent = sendRates.getOrPut(entry.event.pubkey) { mutableListOf() }
        sent.removeAll { elapsedMillis - it >= 60_000 || elapsedMillis < it }
        if (sent.size >= 36) return null
        sent.add(elapsedMillis)
        val bundle = parse(entry.bytes, saved = true)
        bundle.canonicalBlocks.removeAll { it.blockType == BlockType.PreviousNodeBlock.code }
        bundle.getHopCountBlockData()!!.count++
        bundle.getAgeBlockData()!!.age = currentAge(entry, elapsedMillis)
        return bundle.cborMarshal().takeIf { it.size <= MeshPolicy.MAX_BUNDLE_BYTES }
    }

    private fun insert(
        bytes: ByteArray,
        nowMillis: Long,
        elapsedMillis: Long,
        applyRateLimit: Boolean,
        restoring: Boolean = false,
    ): StoredBundle? {
        val bundle = parse(bytes, saved = restoring)
        // Library checkValid() uses wall clock and rejects expired bundles. Offline restore
        // needs those for the relay queue, so validate structure separately here.
        bundle.checkValidPrimaryBlock()
        bundle.canonicalBlocks.forEach { bundle.checkValidCanonicalBlock(it) }
        require(bundle.primaryBlock.hasCRC()) { "primary CRC required" }
        require(!bundle.primaryBlock.isFragment() && !bundle.isAdminRecord()) { "unsupported bundle category" }
        require(bundle.canonicalBlocks.count { it.blockType == 1 } == 1) { "one payload required" }
        require(bundle.canonicalBlocks.last().blockType == 1) { "payload must be last" }
        require(bundle.canonicalBlocks.map { it.blockNumber }.distinct().size == bundle.canonicalBlocks.size) { "duplicate block numbers" }
        for (type in listOf(6, 7, 10)) {
            require(bundle.canonicalBlocks.count { it.blockType == type } <= 1) { "duplicate extension" }
        }
        require(bundle.canonicalBlocks.none { it.blockType == 11 || it.blockType == 12 }) { "BPSec not supported by this profile" }
        val hop = bundle.getHopCountBlockData() ?: error("missing hop count")
        val age = bundle.getAgeBlockData() ?: error("missing age")
        val event = EventCodec.decode(bundle.getPayloadBlockData().buffer)
        require(event.verify()) { "invalid author signature" }
        expiry(event)
        require(bundle.primaryBlock.destination.toString() == MeshPolicy.DESTINATION) { "unknown mesh destination" }
        require(hop.limit in 1..255 && hop.count in 0..minOf(hop.limit, MeshPolicy.MAX_HOPS)) { "hop limit exceeded" }
        require(bundle.primaryBlock.lifetime in 1..MeshPolicy.MAX_RESIDENCE_MILLIS) { "lifetime exceeds local policy" }
        if (!restoring) {
            require(age.age < bundle.primaryBlock.lifetime) { "bundle lifetime expired" }
            require(creationTimeEligible(bundle, nowMillis)) { "bundle expired" }
        }
        // A past signed deadline only stops forwarding (eligible); the event is still kept for gateway export.
        val knownTypes = setOf(1, 6, 7, 10)
        val unknown = bundle.canonicalBlocks.filter { it.blockType !in knownTypes }
        require(unknown.none { it.procV7flags and 0x04 != 0L }) { "unknown block requires bundle deletion" }
        bundle.canonicalBlocks.removeAll { it.blockType !in knownTypes && it.procV7flags and 0x10 != 0L }
        if (stored.containsKey(event.id)) return null
        if (applyRateLimit) {
            rates.values.forEach { samples -> samples.removeAll { elapsedMillis - it >= 60_000 || elapsedMillis < it } }
            rates.entries.removeAll { it.value.isEmpty() }
            val samples = rates.getOrPut(event.pubkey) { mutableListOf() }
            require(samples.size < MeshPolicy.PER_AUTHOR_PER_MINUTE) { "author rate limit" }
            samples.add(elapsedMillis)
        }
        val entry = StoredBundle(event, bundle.cborMarshal(), hop.count, nowMillis, elapsedMillis)
        if (stored.size >= MeshPolicy.MAX_ENTRIES) stored.remove(victim(nowMillis, elapsedMillis))
        stored[event.id] = entry

        return entry
    }

    // ponytail: no per-author fairness; the per-author accept rate is the only flood brake.
    private fun victim(nowMillis: Long, elapsedMillis: Long): String {
        val dead = stored.values.filterNot { eligible(it, nowMillis, elapsedMillis) }
        return (dead.firstOrNull { relayed(it.event.id) } ?: dead.firstOrNull() ?: stored.values.first()).event.id
    }

    private fun eligible(entry: StoredBundle, nowMillis: Long, elapsedMillis: Long): Boolean {
        val bundle = parse(entry.bytes, saved = true)
        val hop = bundle.getHopCountBlockData()!!
        return deadline(entry.event, nowMillis) != DeliveryDeadline.EXPIRED &&
            hop.count < minOf(hop.limit, MeshPolicy.MAX_HOPS) &&
            currentAge(entry, elapsedMillis) < bundle.primaryBlock.lifetime &&
            creationTimeEligible(bundle, nowMillis)
    }

    private fun creationTimeEligible(bundle: Bundle, nowMillis: Long): Boolean =
        clockConfidence != ClockConfidence.TRUSTED || bundle.primaryBlock.creationTimestamp == 0L ||
            !bundle.isExpired(dtnTimeFromUnixMsec(nowMillis))

    private fun currentAge(entry: StoredBundle, elapsedMillis: Long): Long =
        if (elapsedMillis < entry.receivedElapsedMillis) Long.MAX_VALUE else
            saturatedAdd(parse(entry.bytes, saved = true).getAgeBlockData()!!.age, positiveDifference(elapsedMillis, entry.receivedElapsedMillis))

    private fun expiry(event: NostrEvent) = AtRepository.deliveryExpiresAt(event) ?: error("signed delivery deadline required")

    private fun parse(bytes: ByteArray, saved: Boolean = false): Bundle {
        require(bytes.size in 1..if (saved) MeshPolicy.MAX_SAVED_BUNDLE_BYTES else MeshPolicy.MAX_BUNDLE_BYTES) { "bundle size limit" }
        BoundedCbor.check(bytes)
        return cborUnmarshalBundle(bytes)
    }
}

private fun positiveDifference(now: Long, then: Long): Long =
    if (now <= then) 0 else if (then < 0 && now > Long.MAX_VALUE + then) Long.MAX_VALUE else now - then

private fun saturatedAdd(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

/** Shape preflight before a third-party parser can allocate a claimed byte-string length. */
internal object BoundedCbor {
    fun check(bytes: ByteArray) {
        var at = 0
        var items = 0
        fun argument(ai: Int): Long {
            if (ai < 24) return ai.toLong()
            val width = when (ai) { 24 -> 1; 25 -> 2; 26 -> 4; 27 -> 8; else -> error("invalid CBOR argument") }
            require(at + width <= bytes.size) { "truncated CBOR" }
            var value = 0L
            repeat(width) {
                val next = bytes[at++].toLong() and 255
                require(value <= (Long.MAX_VALUE - next) / 256) { "CBOR integer overflow" }
                value = value * 256 + next
            }
            return value
        }
        fun read(depth: Int) {
            require(depth <= 16 && ++items <= 4096 && at < bytes.size) { "CBOR resource limit" }
            val head = bytes[at++].toInt() and 255
            val major = head ushr 5
            val ai = head and 31
            if (ai == 31) {
                require(major == 4 || major == 5) { "unsupported indefinite CBOR" }
                var count = 0
                while (at < bytes.size && (bytes[at].toInt() and 255) != 255) { read(depth + 1); count++ }
                require(at < bytes.size && (major != 5 || count % 2 == 0)) { "truncated CBOR container" }
                at++
                return
            }
            val count = argument(ai)
            when (major) {
                0, 1 -> Unit
                2, 3 -> {
                    require(count <= bytes.size - at) { "truncated CBOR string" }
                    at += count.toInt()
                }
                4, 5 -> {
                    require(count <= 4096) { "CBOR container limit" }
                    repeat(count.toInt() * if (major == 5) 2 else 1) { read(depth + 1) }
                }
                6 -> error("CBOR tags are not part of the BPv7 envelope")
                7 -> require(ai in 20..22) { "unsupported CBOR simple value" }
            }
        }
        read(0)
        require(at == bytes.size) { "trailing CBOR data" }
    }
}
