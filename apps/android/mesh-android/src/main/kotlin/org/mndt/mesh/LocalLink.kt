package org.mndt.mesh

import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * One radio node per phone. Every Mosimo app on a phone (Bosai, Talk, a cloned Bosai) shares one Bluetooth controller
 * and one GATT database: two GATT servers put two identical services in it and remote centrals only ever use the
 * first, each add/remove sends Service Changed to every connected iPhone, and two of our centrals on one peer
 * interleave their writes on its single ATT bearer. LAN works per app, but would put one node per app on the network.
 *
 * So the app holding the first free of [ports] on 127.0.0.1 runs [radios] (BLE, LAN) for the whole phone; the others
 * connect to it there, exchange like any peer, and keep their radios off. A port whose listener does not answer with
 * HELLO belongs to some other app and is skipped. A holder on a fallback port keeps checking the earlier ones and moves
 * there or follows a holder found there, so every app ends up behind one holder. When it stops, a follower takes over.
 */
// ponytail: no peer auth on loopback (as LanLink); a local app speaking HELLO on the port keeps our radios off.
internal class LocalLink(private val radios: List<Link>, private val ports: List<Int> = PORTS) : Link {
    companion object {
        /** In order; IANA-unassigned and below the 32768–60999 ephemeral range. Provisional, not in protocol/. */
        val PORTS = listOf(29584, 26741, 23771)
        /** IPv4 on purpose: Android's getLoopbackAddress() is ::1, which never meets an app listening on 127.0.0.1. */
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
        private const val TAG = "LocalLink"
        private const val MAX_CONNS = 8
        private const val HELLO_MS = 10_000L
        private const val RETRY_MS = 5_000L
        private const val RECHECK_MS = 60_000L
    }

    override val name = "local"
    private val lock = Any()
    @Volatile private var gen = 0 // written under lock; callbacks from older starts compare and bail
    private var node = ""
    private var listener: LinkListener? = null
    private var exec: ScheduledExecutorService? = null
    private var server: ServerSocket? = null
    private var held = -1 // index in [ports] of [server]
    private var holder: String? = null // our connection to the holder, while we follow it or check its port
    private var holderOk = false // the holder said HELLO
    private var hello: CountDownLatch? = null // released when the checked port says HELLO or hangs up
    private var radiosOn = false
    private val conns = HashMap<String, TcpConn>()

    override fun start(node: String, listener: LinkListener) {
        stop()
        val exec = Executors.newSingleThreadScheduledExecutor()
        val g = synchronized(lock) { this.node = node; this.listener = listener; this.exec = exec; gen }
        exec.execute { elect(g) }
    }

    private enum class Answer { HOLDER, OTHER_APP, NOBODY }

    /**
     * Runs on [exec]. Not holding: follow a holder on any port, and take the first free port only if there is none, so
     * a port another app frees cannot start a second holder. Holding: move to a free earlier port or follow its holder.
     */
    private fun elect(g: Int) {
        val current = synchronized(lock) { if (g != gen || holder != null) return; held }
        val otherApps = HashSet<Int>()
        if (current < 0) for (i in ports.indices) when (ask(g, i) ?: return) {
            Answer.HOLDER -> return
            Answer.OTHER_APP -> otherApps += i
            Answer.NOBODY -> {}
        }
        for (i in 0 until if (current >= 0) current else ports.size) {
            if (i in otherApps) continue
            val s = runCatching { ServerSocket().apply { bind(loopback(i)) } }.getOrNull()
            if (s != null) { hold(g, i, s); return }
            if ((ask(g, i) ?: return) == Answer.HOLDER) return // taken since the scan above
        }
        if (current == 0) return
        if (current < 0) {
            log("every port is used by another app; running the radios here")
            synchronized(lock) { if (g == gen) setRadios(true) }
        }
        later(g, RECHECK_MS) { elect(g) }
    }

    private fun hold(g: Int, i: Int, s: ServerSocket) {
        val old = synchronized(lock) {
            if (g != gen) { runCatching { s.close() }; return }
            server.also { server = s; held = i; setRadios(true) }
        }
        runCatching { old?.close() } // its followers stay connected; newcomers find us on the earlier port
        log("holding the radios for this phone on port ${ports[i]}")
        thread(isDaemon = true, name = "local-accept") {
            while (true) adopt(g, try { s.accept() } catch (_: IOException) { return@thread }, follow = false)
        }
        if (i > 0) later(g, RECHECK_MS) { elect(g) }
    }

    /** HOLDER: a Mosimo holder said HELLO on ports[i] and we now follow it. Null: stopped. */
    private fun ask(g: Int, i: Int): Answer? {
        val c = Socket()
        try { c.connect(loopback(i), 1000) } catch (_: IOException) { runCatching { c.close() }; return Answer.NOBODY }
        val latch = CountDownLatch(1)
        synchronized(lock) { hello = latch }
        val h = adopt(g, c, follow = true) ?: return null
        runCatching { latch.await(HELLO_MS, TimeUnit.MILLISECONDS) }
        val (ok, drop) = synchronized(lock) {
            if (g != gen) return null
            if (holder == h && holderOk) {
                runCatching { server?.close() }; server = null; held = -1; setRadios(false)
                true to conns.filterKeys { it != h }.values.toList() // our followers: let them find the holder
            } else {
                if (holder == h) holder = null // its hang-up below is not losing a holder
                false to listOfNotNull(conns[h])
            }
        }
        drop.forEach { it.close() }
        log(if (ok) "following the holder on port ${ports[i]}" else "port ${ports[i]} is used by another app")
        return if (ok) Answer.HOLDER else Answer.OTHER_APP
    }

    private fun adopt(g: Int, s: Socket, follow: Boolean): String? {
        val peer = "local:${s.localPort}:${s.port}"
        runCatching { s.tcpNoDelay = true }
        lateinit var conn: TcpConn
        val l = synchronized(lock) {
            val l = listener
            if (g != gen || conns.size >= MAX_CONNS || l == null) return@synchronized null
            conn = TcpConn(s, { l.onFrame(this, peer, it) }) {
                // Losing the holder we followed: it freed its port, so elect again. A checked port's hang-up is ask()'s.
                val lost = synchronized(lock) {
                    conns.remove(peer, conn)
                    if (g != gen || holder != peer) return@synchronized false
                    holder = null; hello?.countDown()
                    holderOk.also { holderOk = false }
                }
                if (lost) later(g, RETRY_MS) { elect(g) }
                l.onDisconnected(this, peer)
            }
            conns[peer] = conn
            if (follow) { holder = peer; holderOk = false }
            l
        } ?: run { runCatching { s.close() }; return null }
        l.onConnected(this, peer)
        conn.start()
        return peer
    }

    override fun identified(peer: String, node: String) {
        synchronized(lock) { if (peer == holder) { holderOk = true; hello?.countDown() } }
    }

    /** Under [lock]. */
    private fun setRadios(on: Boolean) {
        if (radiosOn == on) return
        radiosOn = on
        log(if (on) "radios on" else "radios off: the holder relays for this phone")
        val l = listener
        for (r in radios) runCatching { if (!on) r.stop() else if (l != null) r.start(node, l) }.onFailure { log("${r.name}: $it") }
    }

    private fun later(g: Int, ms: Long, block: () -> Unit) {
        val e = synchronized(lock) { exec.takeIf { g == gen } } ?: return
        runCatching { e.schedule({ if (g == gen) block() }, ms, TimeUnit.MILLISECONDS) } // rejected once stopped
    }

    private fun loopback(i: Int) = InetSocketAddress(LOOPBACK, ports[i])

    override fun send(peer: String, frame: ByteArray) = synchronized(lock) { conns[peer] }?.offer(frame) ?: false

    override fun disconnect(peer: String) { synchronized(lock) { conns[peer] }?.close() }

    override fun stop() {
        val old = synchronized(lock) {
            gen++
            exec?.shutdownNow()
            runCatching { server?.close() } // first, so a follower can bind the moment its connection drops
            setRadios(false)
            hello?.countDown()
            exec = null; server = null; held = -1; holder = null; holderOk = false; hello = null; listener = null
            conns.values.toList().also { conns.clear() }
        }
        old.forEach { it.close() } // outside the lock: fires onDisconnected
    }

    // android.util.Log throws "not mocked" in JVM unit tests.
    private fun log(text: String) { runCatching { Log.i(TAG, text) } }
}
