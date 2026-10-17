package org.mndt.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

internal data class CidLink(val value: String)

/** Bounded deterministic CBOR subset: no floats, indefinite lengths, or arbitrary tags. */
object CanonicalCbor {
    // TODO(undecided): experimental resource limits, not protocol-wide constants.
    const val MAX_BYTES = 262144
    private const val MAX_ITEMS = 8192
    private const val MAX_DEPTH = 16

    fun encode(value: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        var items = 0
        fun head(major: Int, n: Long) {
            require(n >= 0)
            when {
                n < 24 -> out.write((major shl 5) or n.toInt())
                n <= 255 -> { out.write((major shl 5) or 24); out.write(n.toInt()) }
                n <= 65535 -> { out.write((major shl 5) or 25); for (i in 1 downTo 0) out.write((n ushr (i * 8)).toInt()) }
                n <= 0xffffffffL -> { out.write((major shl 5) or 26); for (i in 3 downTo 0) out.write((n ushr (i * 8)).toInt()) }
                else -> { out.write((major shl 5) or 27); for (i in 7 downTo 0) out.write((n ushr (i * 8)).toInt()) }
            }
        }
        fun write(v: Any?, depth: Int) {
            require(depth <= MAX_DEPTH && ++items <= MAX_ITEMS) { "CBOR resource limit" }
            when (v) {
                null -> out.write(0xf6)
                is Boolean -> out.write(if (v) 0xf5 else 0xf4)
                is Int -> { val n = v.toLong(); head(if (n >= 0) 0 else 1, if (n >= 0) n else -1 - n) }
                is Long -> head(if (v >= 0) 0 else 1, if (v >= 0) v else -1 - v)
                is ByteArray -> { head(2, v.size.toLong()); out.write(v) }
                is String -> { val b = utf8(v); head(3, b.size.toLong()); out.write(b) }
                is CidLink -> { head(6, 42); write(byteArrayOf(0) + cidBytes(v.value), depth + 1) }
                is List<*> -> { head(4, v.size.toLong()); v.forEach { write(it, depth + 1) } }
                is Map<*, *> -> {
                    require(v.keys.all { it is String }) { "CBOR map keys must be strings" }
                    val entries = v.entries.sortedWith { a, b -> compareBytes(utf8(a.key as String), utf8(b.key as String)) }
                    head(5, entries.size.toLong())
                    entries.forEach { write(it.key, depth + 1); write(it.value, depth + 1) }
                }
                else -> error("Unsupported CBOR type")
            }
            require(out.size() <= MAX_BYTES) { "CBOR size limit" }
        }
        write(value, 0)
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): Any? {
        require(bytes.size <= MAX_BYTES) { "CBOR size limit" }
        var pos = 0
        var items = 0
        fun byte(): Int { require(pos < bytes.size) { "Truncated CBOR" }; return bytes[pos++].toInt() and 255 }
        fun read(depth: Int): Any? {
            require(depth <= MAX_DEPTH && ++items <= MAX_ITEMS) { "CBOR resource limit" }
            val h = byte()
            val major = h ushr 5
            val ai = h and 31
            if (major == 7) return when (ai) { 20 -> false; 21 -> true; 22 -> null; else -> error("Unsupported CBOR simple/float") }
            val n = when (ai) {
                in 0..23 -> ai.toLong()
                in 24..27 -> {
                    var x = 0L
                    repeat(1 shl (ai - 24)) {
                        val b = byte()
                        require(x <= (Long.MAX_VALUE - b) / 256) { "CBOR integer overflow" }
                        x = x * 256 + b
                    }
                    x
                }
                else -> error("Indefinite/reserved CBOR")
            }
            fun size(): Int { require(n <= MAX_BYTES && n <= bytes.size - pos); return n.toInt() }
            return when (major) {
                0 -> n
                1 -> -1 - n
                2 -> { val end = pos + size(); bytes.copyOfRange(pos, end).also { pos = end } }
                3 -> { val end = pos + size(); val s = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, pos, end - pos)).toString(); pos = end; s }
                4 -> { require(n <= MAX_ITEMS); List(n.toInt()) { read(depth + 1) } }
                5 -> {
                    require(n <= MAX_ITEMS)
                    val map = linkedMapOf<String, Any?>()
                    repeat(n.toInt()) { val key = read(depth + 1) as? String ?: error("Non-string CBOR key"); require(!map.containsKey(key)) { "Duplicate CBOR key" }; map[key] = read(depth + 1) }
                    map
                }
                6 -> {
                    require(n == 42L) { "Unknown CBOR tag" }
                    val b = read(depth + 1) as? ByteArray ?: error("Invalid CID link")
                    require(b.size == 37 && b[0] == 0.toByte())
                    val cid = "b" + base32(b.copyOfRange(1, b.size))
                    cidBytes(cid)
                    CidLink(cid)
                }
                else -> error("Unknown CBOR type")
            }
        }
        val value = read(0)
        require(pos == bytes.size && encode(value).contentEquals(bytes)) { "Non-canonical/trailing CBOR" }
        return value
    }
}

internal fun utf8(s: String): ByteArray {
    val b = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(s))
    return ByteArray(b.remaining()).also { b.get(it) }
}
internal fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
internal fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun unhex(s: String, size: Int): ByteArray {
    require(s.length == size * 2 && s.all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid lowercase hex" }
    return ByteArray(size) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
internal fun compareBytes(a: ByteArray, b: ByteArray): Int {
    if (a.size != b.size) return a.size.compareTo(b.size)
    for (i in a.indices) { val c = (a[i].toInt() and 255).compareTo(b[i].toInt() and 255); if (c != 0) return c }
    return 0
}
private const val B32 = "abcdefghijklmnopqrstuvwxyz234567"
internal fun base32(bytes: ByteArray): String = buildString {
    var acc = 0; var bits = 0
    for (b in bytes) { acc = (acc shl 8) or (b.toInt() and 255); bits += 8; while (bits >= 5) { bits -= 5; append(B32[(acc ushr bits) and 31]) } }
    if (bits > 0) append(B32[(acc shl (5 - bits)) and 31])
}
internal fun cid(bytes: ByteArray): String = "b" + base32(byteArrayOf(1, 0x71, 0x12, 0x20) + sha256(bytes))
internal fun cidBytes(s: String): ByteArray {
    require(s.length == 59 && s[0] == 'b') { "Expected CIDv1 dag-cbor sha256" }
    val out = ByteArrayOutputStream(); var acc = 0; var bits = 0
    for (c in s.drop(1)) { val n = B32.indexOf(c); require(n >= 0); acc = (acc shl 5) or n; bits += 5; if (bits >= 8) { bits -= 8; out.write(acc ushr bits) } }
    val b = out.toByteArray()
    require(b.size == 36 && b.copyOfRange(0, 4).contentEquals(byteArrayOf(1, 0x71, 0x12, 0x20)) && "b" + base32(b) == s) { "Invalid CID" }
    return b
}
