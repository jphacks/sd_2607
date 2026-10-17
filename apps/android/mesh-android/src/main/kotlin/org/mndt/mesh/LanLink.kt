package org.mndt.mesh

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Only the lexicographically smaller node dials, so two nodes open one connection. */
internal fun shouldDial(myNode: String, peerNode: String) = myNode < peerNode

/** One TCP peer: a reader thread delivers frames, a writer thread drains a bounded queue. Android-free for JVM tests. */
internal class TcpConn(
    private val socket: Socket,
    private val onFrame: (ByteArray) -> Unit,
    private val onClose: () -> Unit,
) {
    private val queue = ArrayBlockingQueue<ByteArray>(64)
    private val closed = AtomicBoolean(false)

    fun start() {
        thread(isDaemon = true, name = "lan-read") {
            try {
                val input = socket.getInputStream()
                val reader = FrameReader()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    reader.feed(buf.copyOf(n)).forEach(onFrame)
                }
            } catch (_: Exception) {
            }
            close()
        }
        thread(isDaemon = true, name = "lan-write") {
            try {
                val out = socket.getOutputStream()
                while (!closed.get()) {
                    val frame = queue.poll(1, TimeUnit.SECONDS) ?: continue
                    out.write(Frames.encode(frame)); out.flush()
                }
            } catch (_: Exception) {
            }
            close()
        }
    }

    fun offer(frame: ByteArray) = !closed.get() && frame.size in 1..Frames.MAX_FRAME && queue.offer(frame)

    /** Idempotent; [onClose] runs exactly once, on the calling thread. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        onClose()
    }
}

// ponytail: no TLS or peer auth; payloads are public signed events. Peers that block mDNS (client isolation,
// multicast-filtering networks) are unreachable: manual host:port peers are not implemented.
internal class LanLink(private val context: Context) : Link {
    companion object {
        const val SERVICE_TYPE = "_mosimo._tcp" // provisional (protocol/mndt.md §3.1)
        private const val TAG = "LanLink"
        private const val MAX_CONNS = 8
        private const val RETRY_S = 10L
    }

    override val name = "lan"
    private val nsd by lazy { context.getSystemService(NsdManager::class.java) }
    private val lock = Any()
    @Volatile private var gen = 0 // written under lock; NSD/socket callbacks from older starts compare and bail
    private var node = ""
    private var ownName: String? = null
    private var listener: LinkListener? = null
    private var server: ServerSocket? = null
    private var exec: ScheduledExecutorService? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val conns = HashMap<String, TcpConn>()
    private val outbound = HashMap<String, String>() // peer handle -> node we dialed
    private val dialing = HashSet<String>()
    private val found = HashMap<String, NsdServiceInfo>() // node -> service seen on the LAN

    override fun start(node: String, listener: LinkListener) {
        stop()
        val server = runCatching { ServerSocket(0) }.getOrElse { Log.w(TAG, "listen failed", it); return }
        val exec = Executors.newSingleThreadScheduledExecutor()
        val g = gen // stop() just bumped it; only start/stop write it, and the caller serializes those
        val mc = context.applicationContext.getSystemService(WifiManager::class.java)
            ?.createMulticastLock("mosimo-lan")?.apply { setReferenceCounted(false); acquire() }
        val reg = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { synchronized(lock) { if (g == gen) ownName = info.serviceName } }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { Log.w(TAG, "register failed $code") }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        synchronized(lock) {
            this.node = node; this.listener = listener; this.server = server; this.exec = exec
            multicast = mc; registration = reg; ownName = null
        }
        thread(isDaemon = true, name = "lan-accept") {
            while (true) {
                val s = try { server.accept() } catch (e: IOException) {
                    if (g == gen) Log.w(TAG, "accept failed", e) // ponytail: no server restart; stop()/start() recovers
                    return@thread
                }
                adopt(g, s, null)
            }
        }
        val info = NsdServiceInfo().apply { serviceName = node; serviceType = SERVICE_TYPE; port = server.localPort }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg) }.onFailure { Log.w(TAG, "register", it) }
        exec.scheduleWithFixedDelay({ tick(g) }, 0, RETRY_S, TimeUnit.SECONDS)
    }

    /** Runs on [exec]: (re)starts discovery, then resolves and dials found peers one at a time. */
    private fun tick(g: Int) {
        if (g != gen) return
        val disc = synchronized(lock) { if (discovery != null) null else discoveryListener(g).also { discovery = it } }
        disc?.let { d -> runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, d) }.onFailure { failDiscovery(g, d, it.toString()) } }
        val todo = synchronized(lock) {
            found.filterKeys { shouldDial(node, it) && it !in dialing && it !in outbound.values }.values.toList()
        }
        for (svc in todo) {
            val peerNode = svc.serviceName
            synchronized(lock) {
                if (g != gen || conns.size + dialing.size >= MAX_CONNS || peerNode in dialing || peerNode in outbound.values) return
                dialing += peerNode
            }
            val resolved = resolve(svc)
            @Suppress("DEPRECATION") val addr = resolved?.host?.let { InetSocketAddress(it, resolved.port) }
            if (addr == null) { synchronized(lock) { dialing -= peerNode }; continue }
            thread(isDaemon = true, name = "lan-dial") {
                val s = Socket()
                try {
                    s.connect(addr, 5000)
                } catch (e: IOException) {
                    Log.d(TAG, "dial $peerNode failed: $e"); runCatching { s.close() }
                    synchronized(lock) { dialing -= peerNode }; return@thread
                }
                adopt(g, s, peerNode)
            }
        }
    }

    // ponytail: legacy resolveService is single-flight, so resolves run serially on exec; API 34
    // registerServiceInfoCallback would allow parallel resolves.
    @Suppress("DEPRECATION")
    private fun resolve(svc: NsdServiceInfo): NsdServiceInfo? {
        val done = CountDownLatch(1)
        var out: NsdServiceInfo? = null
        val l = object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) { out = info; done.countDown() }
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) { Log.d(TAG, "resolve failed $code"); done.countDown() }
        }
        if (runCatching { nsd.resolveService(svc, l) }.isFailure) return null
        return if (runCatching { done.await(RETRY_S, TimeUnit.SECONDS) }.getOrDefault(false)) out else null
    }

    private fun discoveryListener(g: Int) = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) {
            val name = info.serviceName ?: return
            val e = synchronized(lock) {
                if (g != gen || name == node || name == ownName) return
                found[name] = info; exec
            }
            runCatching { e?.execute { tick(g) } }
        }
        override fun onServiceLost(info: NsdServiceInfo) { synchronized(lock) { if (g == gen) found.remove(info.serviceName) } }
        override fun onStartDiscoveryFailed(type: String, code: Int) = failDiscovery(g, this, "code $code")
        override fun onDiscoveryStarted(type: String) {}
        override fun onDiscoveryStopped(type: String) {}
        override fun onStopDiscoveryFailed(type: String, code: Int) {}
    }

    /** Forget the failed listener so the next tick (every [RETRY_S]s) starts discovery again. */
    private fun failDiscovery(g: Int, d: NsdManager.DiscoveryListener, why: String) {
        Log.w(TAG, "discovery failed: $why")
        synchronized(lock) { if (g == gen && discovery === d) discovery = null }
    }

    private fun adopt(g: Int, s: Socket, peerNode: String?) {
        val peer = "lan:${s.inetAddress.hostAddress}:${s.port}"
        runCatching { s.keepAlive = true; s.tcpNoDelay = true }
        lateinit var conn: TcpConn
        val l = synchronized(lock) {
            peerNode?.let { dialing -= it }
            val l = listener
            if (g != gen || conns.size >= MAX_CONNS || l == null) return@synchronized null
            conn = TcpConn(s, { l.onFrame(this, peer, it) }) {
                synchronized(lock) { if (conns.remove(peer, conn)) outbound.remove(peer) }
                l.onDisconnected(this, peer)
            }
            conns[peer] = conn; peerNode?.let { outbound[peer] = it }
            l
        } ?: run { runCatching { s.close() }; return }
        l.onConnected(this, peer)
        conn.start()
    }

    override fun send(peer: String, frame: ByteArray) = synchronized(lock) { conns[peer] }?.offer(frame) ?: false

    override fun disconnect(peer: String) { synchronized(lock) { conns[peer] }?.close() }

    override fun stop() {
        val old = synchronized(lock) {
            gen++
            exec?.shutdownNow()
            registration?.let { runCatching { nsd.unregisterService(it) } }
            discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
            runCatching { server?.close() }
            runCatching { multicast?.release() }
            server = null; exec = null; multicast = null; registration = null; discovery = null; listener = null
            outbound.clear(); dialing.clear(); found.clear()
            conns.values.toList().also { conns.clear() }
        }
        old.forEach { it.close() } // outside the lock: fires onDisconnected
    }
}
