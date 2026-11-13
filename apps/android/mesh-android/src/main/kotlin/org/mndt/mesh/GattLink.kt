package org.mndt.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.util.UUID
import java.util.concurrent.Executors

/**
 * What a node puts in its scan response (mesh §3.1 + mesh.adv §1): node ID, role flags, and optionally the
 * 3-byte candidate-set summary with extension flags.
 */
/** [nameOnly]: an iOS peer, which can only put its node in the local name (mesh §3.1), not Service Data. */
internal data class GattAdvert(val node: String, val peripheralOnly: Boolean, val summary: ByteArray? = null, val extFlags: Int = 0,
    val nameOnly: Boolean = false) {
    val urgent: Boolean get() = extFlags and 1 != 0
}

/** Service data under [GattLink.SERVICE_UUID]: 8-byte node id, flags (bit0 = peripheral-only), then the optional 4-byte adv extension. */
internal fun parseGattAdvert(data: ByteArray?): GattAdvert? {
    if (data == null || data.size < 9) return null
    val node = data.copyOf(8).joinToString("") { "%02x".format(it.toInt() and 255) }
    val extension = data.size >= 13
    return GattAdvert(node, data[8].toInt() and 1 != 0, if (extension) data.copyOfRange(9, 12) else null, if (extension) data[12].toInt() and 255 else 0)
}

/** iOS advertises the service UUID with its 16-hex node as the local name, and no Service Data. */
internal fun nameAdvert(name: String?): GattAdvert? =
    name?.takeIf { it.matches(Regex("[0-9a-f]{16}")) }?.let { GattAdvert(it, peripheralOnly = false, nameOnly = true) }

internal fun gattServiceData(node: String, flags: Int = 0, summary: ByteArray? = null, extFlags: Int = 0): ByteArray {
    val head = ByteArray(9) { if (it < 8) node.substring(it * 2, it * 2 + 2).toInt(16).toByte() else flags.toByte() }
    return if (summary == null) head else head + summary.copyOf(3) + byteArrayOf(extFlags.toByte())
}

/** Central iff the peer cannot be one, or we win the tie-break, so two phones open exactly one link. */
internal fun shouldConnect(myNode: String, peer: GattAdvert) =
    peer.node != myNode && (peer.peripheralOnly || myNode < peer.node)

/**
 * mesh.adv §4: skip connecting when the peer's advertised summary and our own summary are both unchanged since the
 * last reconciliation with that node; an urgent flag (bit0) always permits a connection.
 */
internal fun worthConnecting(peer: GattAdvert, lastSynced: Pair<ByteArray, ByteArray>?, ownSummary: ByteArray): Boolean {
    if (peer.summary == null || lastSynced == null || peer.urgent) return true
    return !(lastSynced.first.contentEquals(peer.summary) && lastSynced.second.contentEquals(ownSummary))
}

/**
 * BLE GATT link: every phone is GATT server + advertiser and scanner + central at once.
 *
 * Advertisement (legacy): Flags (3) + 128-bit Service UUID list (18) = 21 bytes; scanners filter on the UUID,
 * which controllers offload reliably. The scan response carries the 128-bit Service Data
 * (1 len + 1 type 0x21 + 16 UUID + 8 node id + 1 flags + 3 summary + 1 ext flags = 31 bytes).
 *
 * Bytes on either direction are the CBOR sequence of exchange messages cut into min(MTU - 3, 512) chunks: the
 * central writes RX without response, the server notifies TX; one chunk in flight per peer. Peer handles are
 * "c:ADDR" (we are central) and "p:ADDR" (we are server). Listener calls go through one executor, never under [lock].
 */
@SuppressLint("MissingPermission")
internal class GattLink(private val context: Context, private val allowedPeerNode: String? = null) : Link {
    override val name = "ble"

    private class Peer(val handle: String, val device: BluetoothDevice, val gatt: BluetoothGatt?, val node: String?) {
        var connected = false
        /** The exchange reconciled over this link; a drop before that counts as a failed attempt. */
        var synced = false
        var mtu = 23
        val reader = CborSequenceReader(Exchange.MESSAGE_LIMIT)
        val out = ArrayDeque<ByteArray>() // whole messages; [offset] bytes of the first are already sent
        var offset = 0
        var inFlight = 0 // size of the unacknowledged chunk, 0 when idle
    }

    private val lock = Any()
    private val events = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var gen = 0
    private var running = false
    private var node = ""
    private var listener: LinkListener? = null
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var advertiseCb: AdvertiseCallback? = null
    private var scanCb: ScanCallback? = null
    private val peers = HashMap<String, Peer>()
    private val serverMtu = HashMap<String, Int>()
    // ponytail: backoff keyed by device address; phones rotate private addresses (~15 min), which resets it.
    private val backoff = HashMap<String, Pair<Int, Long>>()
    // Nodes whose stream we rejected: redialing an incompatible build only repeats the failure and holds its radio.
    private val incompatible = HashMap<String, Long>()
    /** First time each node was seen; lets the tie-break loser dial when the winner cannot see us. */
    private val firstSeen = HashMap<String, Long>()
    /** Last time each node's advert was seen, for the nearby count. */
    private val lastSeen = HashMap<String, Long>()
    /** Last reconciliation per node; a peer without an advertised summary is not redialed sooner than [RESYNC_MS]. */
    private val lastSyncedAt = HashMap<String, Long>()
    /** mesh.adv: our candidate summary and flags, the last advertised summary per node, and the pair recorded at the last sync. */
    private var summary = ByteArray(3)
    private var extFlags = 0
    private val lastAdvertSummary = HashMap<String, ByteArray>()
    private val lastSynced = HashMap<String, Pair<ByteArray, ByteArray>>()
    private var lastAdvertiseRestart = 0L
    /** The last start request: Bluetooth turned on later (or OFF→ON, which kills scanner/advertiser/server) restarts the link. */
    @Volatile private var wanted: Pair<String, LinkListener>? = null

    init {
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val (n, l) = wanted ?: return
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> halt()
                    BluetoothAdapter.STATE_ON -> start(n, l)
                }
            }
        }, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    override fun start(node: String, listener: LinkListener) {
        require(node.matches(Regex("[0-9a-fA-F]{16}"))) { "node must be 16 hex" }
        wanted = node to listener
        safe(Unit) {
            synchronized(lock) {
                if (running) return
                val manager = context.getSystemService(BluetoothManager::class.java)
                val adapter = manager?.adapter
                if (adapter == null || !adapter.isEnabled) { Log.w(TAG, "Bluetooth unavailable or off"); return }
                val g = ++gen
                running = true; this.node = node.lowercase(); this.listener = listener
                advertiser = adapter.bluetoothLeAdvertiser // null when the chip cannot advertise: central-only then
                scanner = adapter.bluetoothLeScanner
                val s = manager.openGattServer(context, serverCallback(g))
                if (s == null || scanner == null) { Log.w(TAG, "BLE unavailable"); stop(); return }
                server = s
                s.addService(service()) // advertising starts in onServiceAdded, once peers can find the service
                scan(g)
            }
        }
    }

    override fun stop() { wanted = null; halt() }

    private fun halt() {
        synchronized(lock) {
            if (!running) return
            running = false; gen++
            runCatching { scanCb?.let { scanner?.stopScan(it) } }
            runCatching { advertiseCb?.let { advertiser?.stopAdvertising(it) } }
            for (p in peers.values.toList()) drop(p)
            runCatching { server?.close() }
            server = null; scanCb = null; advertiseCb = null; listener = null; serverMtu.clear(); firstSeen.clear(); lastSynced.clear(); lastAdvertSummary.clear()
        }
    }

    /** mesh.adv §2-3: re-advertise when the candidate summary or flags change (rate limited to spare the controller). */
    fun setSummary(summary: ByteArray, extFlags: Int) {
        safe(Unit) {
            synchronized(lock) {
                if (this.summary.contentEquals(summary) && this.extFlags == extFlags) return
                this.summary = summary.copyOf(3); this.extFlags = extFlags
                if (!running || advertiseCb == null) return
                val now = SystemClock.elapsedRealtime()
                if (now - lastAdvertiseRestart < ADVERTISE_RESTART_MIN_MS) { main.postDelayed({ locked(gen) { readvertise(gen) } }, ADVERTISE_RESTART_MIN_MS); return }
                readvertise(gen)
            }
        }
    }

    private fun readvertise(g: Int) {
        runCatching { advertiseCb?.let { advertiser?.stopAdvertising(it) } }
        advertiseCb = null
        lastAdvertiseRestart = SystemClock.elapsedRealtime()
        advertise(g)
    }

    override fun send(peer: String, message: ByteArray): Boolean {
        require(message.isNotEmpty() && message.size <= Messages.MAX_MESSAGE_BYTES) { "Message size" }
        return safe(false) {
            synchronized(lock) {
                val p = peers[peer]?.takeIf { it.connected } ?: return false
                if (p.out.size >= MAX_QUEUE) return false
                p.out.addLast(message); pump(p)
                peers[peer] === p
            }
        }
    }

    override fun queued(peer: String): Int = synchronized(lock) { peers[peer]?.out?.size ?: 0 }

    override fun synced(peer: String) {
        synchronized(lock) {
            val p = peers[peer] ?: return
            p.synced = true
            backoff.remove(p.device.address)
            val n = p.node ?: return
            lastSyncedAt[n] = SystemClock.elapsedRealtime()
            lastAdvertSummary[n]?.let { lastSynced[n] = it to summary.copyOf() }
        }
    }

    override fun rejected(peer: String) {
        synchronized(lock) {
            val n = peers[peer]?.node ?: return
            incompatible[n] = SystemClock.elapsedRealtime() + INCOMPATIBLE_MS
            Log.i(TAG, "not redialing node $n for ${INCOMPATIBLE_MS / 60_000} min: invalid exchange stream")
        }
    }

    override fun disconnect(peer: String) {
        safe(Unit) { synchronized(lock) { peers[peer]?.let { drop(it) } } }
    }

    // --- everything below runs under [lock] ---

    private fun emit(f: (LinkListener) -> Unit) {
        val l = listener ?: return
        events.execute { f(l) }
    }

    override fun nearby(withinMillis: Long): Set<String> = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        lastSeen.entries.removeAll { now - it.value > maxOf(withinMillis, RESYNC_MS) }
        lastSeen.filterValues { now - it <= withinMillis }.keys.toSet()
    }

    private fun connect(g: Int, device: BluetoothDevice, ad: GattAdvert) {
        if (ad.node != node) lastSeen[ad.node] = SystemClock.elapsedRealtime()
        if (ad.node == node || peers.size >= MAX_LINKS || allowedPeerNode != null && ad.node != allowedPeerNode) return
        val now = SystemClock.elapsedRealtime()
        // Without a summary we cannot tell whether anything changed (mesh.adv §4), so a synced peer rests a while.
        if (ad.summary == null && now - (lastSyncedAt[ad.node] ?: Long.MIN_VALUE / 2) < RESYNC_MS) return
        ad.summary?.let { lastAdvertSummary[ad.node] = it }
        if ((incompatible[ad.node] ?: 0) > now) return
        if (!worthConnecting(ad, lastSynced[ad.node], summary)) return
        val waited = now - firstSeen.getOrPut(ad.node) { now } >= ASYMMETRIC_FALLBACK_MS
        // Visibility can be one-way (one phone's scanner misses the other's advert), so the loser dials too after a wait.
        // An iOS peer always dials us (it reads our Service Data); we dial it only when it has not within the wait.
        if ((ad.nameOnly || !shouldConnect(node, ad)) && !waited) return
        val addr = device.address
        if (peers.values.any { it.device.address == addr || it.node == ad.node }) return
        if ((backoff[addr]?.second ?: 0) > SystemClock.elapsedRealtime()) return
        val h = "c:$addr"
        Log.i(TAG, "connecting to node ${ad.node} ($addr)")
        val gatt = device.connectGatt(context, false, clientCallback(g, h), BluetoothDevice.TRANSPORT_LE) ?: return
        val p = Peer(h, device, gatt, ad.node)
        peers[h] = p
        main.postDelayed({ locked(g) { if (!p.connected) drop(p) } }, CONNECT_TIMEOUT_MS)
    }

    private fun connected(p: Peer) {
        if (p.connected) return
        p.connected = true
        Log.i(TAG, "connected ${p.handle}")
        emit { it.onConnected(this, p.handle, p.node) }
    }

    private fun received(p: Peer?, value: ByteArray?) {
        if (p == null || value == null) return
        connected(p) // a notification can race ahead of our CCCD write callback
        val messages = try { p.reader.feed(value) } catch (e: Exception) {
            Log.w(TAG, "bad CBOR sequence from ${p.handle}", e); drop(p); return
        }
        for (m in messages) emit { it.onMessage(this, p.handle, m) }
    }

    private fun sent(p: Peer) {
        if (p.inFlight == 0) return
        p.offset += p.inFlight; p.inFlight = 0
        if (p.offset >= p.out.first().size) { p.out.removeFirst(); p.offset = 0 }
        if (p.out.isEmpty()) emit { it.onDrained(this, p.handle) }
        pump(p)
    }

    private fun pump(p: Peer) {
        if (p.inFlight != 0 || !p.connected) return
        val message = p.out.firstOrNull() ?: return
        // An attribute value is at most 512 bytes even when the MTU is 517 (MTU - 3 = 514 throws).
        val chunk = message.copyOfRange(p.offset, minOf(message.size, p.offset + minOf(p.mtu - 3, MAX_ATTRIBUTE)))
        p.inFlight = chunk.size
        if (!(try { write(p, chunk) } catch (_: IllegalArgumentException) { false })) { Log.w(TAG, "write failed on ${p.handle}"); drop(p) }
    }

    /** Forgets [p] and closes its connection; onDisconnected only if onConnected was sent. */
    private fun drop(p: Peer) {
        if (peers[p.handle] !== p) return
        peers.remove(p.handle)
        Log.i(TAG, "dropped ${p.handle} (connected=${p.connected})")
        runCatching { if (p.gatt != null) { p.gatt.disconnect(); p.gatt.close() } else server?.cancelConnection(p.device) }
        p.node?.let { firstSeen.remove(it) }
        if (p.connected) emit { it.onDisconnected(this, p.handle) }
        // A link dropped before reconciling (refused HELLO, incompatible peer, full peer) backs off like a failed dial.
        if (!p.synced && p.gatt != null) {
            val n = (backoff[p.device.address]?.first ?: 0) + 1
            backoff[p.device.address] = n to SystemClock.elapsedRealtime() + BACKOFF_MS[minOf(n, BACKOFF_MS.size) - 1]
        }
    }

    @Suppress("DEPRECATION")
    private fun write(p: Peer, chunk: ByteArray): Boolean {
        val gatt = p.gatt
        if (gatt != null) {
            val ch = gatt.getService(SERVICE_UUID)?.getCharacteristic(RX_UUID) ?: return false
            // Mesh §3.3: Write Without Response; the stack still reports completion so one chunk stays in flight.
            val type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            return if (Build.VERSION.SDK_INT >= 33) gatt.writeCharacteristic(ch, chunk, type) == BluetoothStatusCodes.SUCCESS
            else { ch.writeType = type; ch.value = chunk; gatt.writeCharacteristic(ch) }
        }
        val s = server ?: return false
        val ch = s.getService(SERVICE_UUID)?.getCharacteristic(TX_UUID) ?: return false
        return if (Build.VERSION.SDK_INT >= 33) s.notifyCharacteristicChanged(p.device, ch, false, chunk) == BluetoothStatusCodes.SUCCESS
        else { ch.value = chunk; s.notifyCharacteristicChanged(p.device, ch, false) }
    }

    @Suppress("DEPRECATION")
    private fun subscribe(gatt: BluetoothGatt, d: BluetoothGattDescriptor): Boolean {
        val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) gatt.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
        else { d.value = v; gatt.writeDescriptor(d) }
    }

    private fun advertise(g: Int) {
        val adv = advertiser ?: return
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.i(TAG, "advertising node $node")
                safe(Unit) { synchronized(lock) { if (g != gen || advertiseCb !== this) adv.stopAdvertising(this) } }
            }
            override fun onStartFailure(errorCode: Int) { Log.w(TAG, "advertise failed: $errorCode") }
        }
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).setConnectable(true).setTimeout(0).build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val response = AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
            .addServiceData(ParcelUuid(SERVICE_UUID), gattServiceData(node, 0, summary, extFlags)).build()
        advertiseCb = cb
        adv.startAdvertising(settings, data, response, cb)
    }

    private fun scan(g: Int) {
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                locked(g) {
                    // Results without the scan response lack the node id; the merged one follows.
                    val ad = parseGattAdvert(result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)))
                        ?: nameAdvert(result.scanRecord?.deviceName) ?: return
                    connect(g, result.device, ad)
                }
            }
            override fun onScanFailed(errorCode: Int) { Log.w(TAG, "scan failed: $errorCode") }
        }
        // A filter is required for scanning to continue with the screen off.
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        scanCb = cb
        scanner?.startScan(listOf(filter), settings, cb)
    }

    private fun service() = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        addCharacteristic(BluetoothGattCharacteristic(RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE))
        addCharacteristic(BluetoothGattCharacteristic(TX_UUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0).apply {
            addDescriptor(BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_WRITE))
        })
    }

    private fun clientCallback(g: Int, h: String) = object : BluetoothGattCallback() {
        fun peer(gatt: BluetoothGatt) = peers[h]?.takeIf { it.gatt === gatt }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            locked(g) {
                val p = peer(gatt) ?: return
                val up = status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED
                if (!up || !gatt.requestMtu(517)) drop(p)
            }
        }
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            locked(g) {
                val p = peer(gatt) ?: return
                if (status == BluetoothGatt.GATT_SUCCESS) p.mtu = mtu
                if (!p.connected && !gatt.discoverServices()) drop(p)
            }
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            locked(g) {
                val p = peer(gatt) ?: return
                val tx = gatt.getService(SERVICE_UUID)?.getCharacteristic(TX_UUID)
                val cccd = tx?.getDescriptor(CCCD_UUID)
                if (status != BluetoothGatt.GATT_SUCCESS || cccd == null ||
                    !gatt.setCharacteristicNotification(tx, true) || !subscribe(gatt, cccd)) drop(p)
            }
        }
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            locked(g) {
                val p = peer(gatt) ?: return
                if (status == BluetoothGatt.GATT_SUCCESS) connected(p) else drop(p)
            }
        }
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            locked(g) {
                val p = peer(gatt) ?: return
                if (status == BluetoothGatt.GATT_SUCCESS) sent(p) else drop(p)
            }
        }
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // API 33+ also calls the overload below; take the bytes from exactly one of them.
            if (Build.VERSION.SDK_INT < 33) locked(g) { received(peer(gatt), characteristic.value) }
        }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            locked(g) { received(peer(gatt), value) }
        }
    }

    private fun serverCallback(g: Int) = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            locked(g) { if (status == BluetoothGatt.GATT_SUCCESS) advertise(g) else Log.w(TAG, "addService failed: $status") }
        }
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            locked(g) {
                if (newState != BluetoothProfile.STATE_DISCONNECTED) return
                serverMtu.remove(device.address)
                peers["p:${device.address}"]?.let { drop(it) }
            }
        }
        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            locked(g) { serverMtu[device.address] = mtu; peers["p:${device.address}"]?.mtu = mtu }
        }
        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
                                              preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            locked(g) {
                val ok = descriptor.uuid == CCCD_UUID && !preparedWrite && offset == 0
                if (responseNeeded) server?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
                if (!ok) return
                val h = "p:${device.address}"
                if (!value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) { peers[h]?.let { drop(it) }; return }
                if (h in peers) return
                if (peers.size >= MAX_LINKS) { server?.cancelConnection(device); return }
                val p = Peer(h, device, null, null)
                p.mtu = serverMtu[device.address] ?: 23
                peers[h] = p
                connected(p)
            }
        }
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                                                  preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            locked(g) {
                val p = peers["p:${device.address}"]
                // ponytail: no prepared (long) writes; our centrals never write more than MTU - 3 bytes at once.
                val ok = p != null && characteristic.uuid == RX_UUID && !preparedWrite && offset == 0
                if (responseNeeded) server?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
                if (ok) received(p, value)
            }
        }
        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            locked(g) {
                val p = peers["p:${device.address}"] ?: return
                if (status == BluetoothGatt.GATT_SUCCESS) sent(p) else drop(p)
            }
        }
    }

    /** Runs [block] under the lock only if it belongs to the current start(); a lost permission stops the link. */
    private inline fun locked(g: Int, block: () -> Unit) = safe(Unit) { synchronized(lock) { if (g == gen) block() } }

    private inline fun <T> safe(fallback: T, block: () -> T): T = try { block() } catch (e: SecurityException) {
        Log.w(TAG, "Bluetooth permission missing; stopping", e); stop(); fallback
    }

    companion object {
        // Mesh §3.1.
        val SERVICE_UUID: UUID = UUID.fromString("b5e89a01-f9da-45c2-9111-2de7a7441d24")
        /** central -> peripheral, write without response. */
        val RX_UUID: UUID = UUID.fromString("483c3867-c832-4c60-9a96-7018059eaf74")
        /** peripheral -> central, notify. */
        val TX_UUID: UUID = UUID.fromString("49d60590-c23f-4a0a-a58e-f2f2f606f94d")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val FLAG_PERIPHERAL_ONLY = 1
        const val MAX_LINKS = 7
        /** A peer that advertises no summary (iOS) is not redialed within this long after a reconciliation. */
        const val RESYNC_MS = 60_000L
        const val MAX_QUEUE = 64
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val ASYMMETRIC_FALLBACK_MS = 10_000L
        const val ADVERTISE_RESTART_MIN_MS = 5_000L
        const val MAX_ATTRIBUTE = 512
        private val BACKOFF_MS = longArrayOf(5_000, 30_000, 120_000)
        private const val INCOMPATIBLE_MS = 600_000L
        private const val TAG = "GattLink"
    }
}
