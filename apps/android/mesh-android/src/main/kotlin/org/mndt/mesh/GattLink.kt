package org.mndt.mesh

import android.annotation.SuppressLint
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
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.util.UUID
import java.util.concurrent.Executors

/** What a node puts in its advertisement: who it is and whether it can only be a peripheral. */
internal data class GattAdvert(val node: String, val peripheralOnly: Boolean)

/** Service data under [GattLink.SERVICE_UUID]: 8-byte node id, then flags (bit0 = peripheral-only). Longer is fine. */
internal fun parseGattAdvert(data: ByteArray?): GattAdvert? {
    if (data == null || data.size < 9) return null
    return GattAdvert(data.copyOf(8).joinToString("") { "%02x".format(it) }, data[8].toInt() and 1 != 0)
}

internal fun gattServiceData(node: String, flags: Int = 0): ByteArray =
    ByteArray(9) { if (it < 8) node.substring(it * 2, it * 2 + 2).toInt(16).toByte() else flags.toByte() }

/** Central iff the peer cannot be one, or we win the tie-break, so two phones open exactly one link. */
internal fun shouldConnect(myNode: String, peer: GattAdvert) =
    peer.node != myNode && (peer.peripheralOnly || myNode < peer.node)

/**
 * BLE GATT link: every phone is GATT server + advertiser and scanner + central at once.
 *
 * Advertisement (legacy): Flags (3) + 128-bit Service UUID list (18) = 21 bytes; scanners filter on the UUID,
 * which controllers offload reliably. The scan response carries the 128-bit Service Data
 * (1 len + 1 type 0x21 + 16 UUID + 8 node id + 1 flags = 27 bytes). A filter on 128-bit Service Data alone
 * matched nothing on a real phone (OPPO CPH2797, Android 16), so it is not used.
 *
 * Bytes on either direction are [Frames.encode] streams cut into min(MTU - 3, 512) chunks: central writes RX with
 * response, server notifies TX; one chunk in flight per peer. Peer handles are "c:ADDR" (we are central)
 * and "p:ADDR" (we are server). Listener calls go through one executor, never under [lock].
 */
@SuppressLint("MissingPermission")
internal class GattLink(private val context: Context) : Link {
    override val name = "ble"

    private class Peer(val handle: String, val device: BluetoothDevice, val gatt: BluetoothGatt?, var node: String?) {
        var connected = false
        var mtu = 23
        val reader = FrameReader()
        val out = ArrayDeque<ByteArray>() // encoded frames; [offset] bytes of the first are already sent
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
    /** First time each node was seen; lets the tie-break loser dial when the winner cannot see us. */
    private val firstSeen = HashMap<String, Long>()

    override fun start(node: String, listener: LinkListener) {
        require(node.matches(Regex("[0-9a-fA-F]{16}"))) { "node must be 16 hex" }
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

    override fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false; gen++
            runCatching { scanCb?.let { scanner?.stopScan(it) } }
            runCatching { advertiseCb?.let { advertiser?.stopAdvertising(it) } }
            for (p in peers.values.toList()) drop(p)
            runCatching { server?.close() }
            server = null; scanCb = null; advertiseCb = null; listener = null; serverMtu.clear(); firstSeen.clear()
        }
    }

    override fun send(peer: String, frame: ByteArray): Boolean {
        val encoded = Frames.encode(frame)
        return safe(false) {
            synchronized(lock) {
                val p = peers[peer]?.takeIf { it.connected } ?: return false
                if (p.out.size >= MAX_QUEUE) return false
                p.out.addLast(encoded); pump(p)
                peers[peer] === p
            }
        }
    }

    // Inbound (p:) links only learn their node here; without it we would also dial that node after the wait.
    override fun identified(peer: String, node: String) {
        synchronized(lock) { peers[peer]?.node = node }
    }

    override fun disconnect(peer: String) {
        safe(Unit) { synchronized(lock) { peers[peer]?.let { drop(it) } } }
    }

    // --- everything below runs under [lock] ---

    private fun emit(f: (LinkListener) -> Unit) {
        val l = listener ?: return
        events.execute { f(l) }
    }

    private fun connect(g: Int, device: BluetoothDevice, ad: GattAdvert) {
        if (ad.node == node || peers.size >= MAX_LINKS) return
        val now = SystemClock.elapsedRealtime()
        val waited = now - firstSeen.getOrPut(ad.node) { now } >= ASYMMETRIC_FALLBACK_MS
        // Visibility can be one-way (one phone's scanner misses the other's advert), so the loser dials too after a wait.
        if (!shouldConnect(node, ad) && !waited) return
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
        backoff.remove(p.device.address)
        emit { it.onConnected(this, p.handle) }
    }

    private fun received(p: Peer?, value: ByteArray?) {
        if (p == null || value == null) return
        connected(p) // a notification can race ahead of our CCCD write callback
        val frames = try { p.reader.feed(value) } catch (e: Exception) {
            Log.w(TAG, "bad framing from ${p.handle}", e); drop(p); return
        }
        for (f in frames) emit { it.onFrame(this, p.handle, f) }
    }

    private fun sent(p: Peer) {
        if (p.inFlight == 0) return
        p.offset += p.inFlight; p.inFlight = 0
        if (p.offset >= p.out.first().size) { p.out.removeFirst(); p.offset = 0 }
        pump(p)
    }

    private fun pump(p: Peer) {
        if (p.inFlight != 0 || !p.connected) return
        val frame = p.out.firstOrNull() ?: return
        // An attribute value is at most 512 bytes even when the MTU is 517 (MTU - 3 = 514 throws).
        val chunk = frame.copyOfRange(p.offset, minOf(frame.size, p.offset + minOf(p.mtu - 3, MAX_ATTRIBUTE)))
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
        else if (p.gatt != null) {
            val n = (backoff[p.device.address]?.first ?: 0) + 1
            backoff[p.device.address] = n to SystemClock.elapsedRealtime() + BACKOFF_MS[minOf(n, BACKOFF_MS.size) - 1]
        }
    }

    @Suppress("DEPRECATION")
    private fun write(p: Peer, chunk: ByteArray): Boolean {
        val gatt = p.gatt
        if (gatt != null) {
            val ch = gatt.getService(SERVICE_UUID)?.getCharacteristic(RX_UUID) ?: return false
            val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
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
                safe(Unit) { synchronized(lock) { if (g != gen) adv.stopAdvertising(this) } }
            }
            override fun onStartFailure(errorCode: Int) { Log.w(TAG, "advertise failed: $errorCode") }
        }
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).setConnectable(true).setTimeout(0).build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val response = AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
            .addServiceData(ParcelUuid(SERVICE_UUID), gattServiceData(node)).build()
        advertiseCb = cb
        adv.startAdvertising(settings, data, response, cb)
    }

    private fun scan(g: Int) {
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                locked(g) {
                    // Results without the scan response lack the node id; the merged one follows.
                    val ad = parseGattAdvert(result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID))) ?: return
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
        addCharacteristic(BluetoothGattCharacteristic(RX_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE,
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
        // Provisional values; to be proposed in protocol/mndt.md.
        val SERVICE_UUID: UUID = UUID.fromString("b5e89a01-f9da-45c2-9111-2de7a7441d24")
        /** central -> peripheral, write with response. */
        val RX_UUID: UUID = UUID.fromString("483c3867-c832-4c60-9a96-7018059eaf74")
        /** peripheral -> central, notify. */
        val TX_UUID: UUID = UUID.fromString("49d60590-c23f-4a0a-a58e-f2f2f606f94d")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val FLAG_PERIPHERAL_ONLY = 1
        const val MAX_LINKS = 7
        const val MAX_QUEUE = 64
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val ASYMMETRIC_FALLBACK_MS = 10_000L
        const val MAX_ATTRIBUTE = 512
        private val BACKOFF_MS = longArrayOf(5_000, 30_000, 120_000)
        private const val TAG = "GattLink"
    }
}
