import CoreBluetooth
import Foundation
import MosimoCore
import os

/// What a node advertises (mesh §3.1 + mesh.adv §1): Android/ESP32 put an 8-byte node id, flags and optionally the
/// 3-byte candidate summary with extension flags in Service Data; iOS cannot, so it uses the local name.
struct GattAdvert: Equatable {
    let node: String?
    let peripheralOnly: Bool
    /// Android/ESP32 only dial peers whose Service Data they can read, so they never dial iOS: we must.
    let mustDial: Bool
    var summary: [UInt8]? = nil
    var extFlags = 0
    var urgent: Bool { extFlags & 1 != 0 }
}

func parseGattAdvert(serviceData: Data?, localName: String?) -> GattAdvert? {
    if let data = serviceData, data.count >= 9 {
        let d = Array(data)
        let ext = d.count >= 13
        return GattAdvert(node: d.prefix(8).map { String(format: "%02x", $0) }.joined(), peripheralOnly: d[8] & 1 != 0, mustDial: true,
                          summary: ext ? Array(d[9..<12]) : nil, extFlags: ext ? Int(d[12]) : 0)
    }
    if let name = localName, name.count == 16, name.allSatisfy({ $0.isHexDigit && !$0.isUppercase }) {
        return GattAdvert(node: name, peripheralOnly: false, mustDial: false)
    }
    // A backgrounded iOS peer advertises the service UUID only (overflow area): node unknown until HELLO.
    return GattAdvert(node: nil, peripheralOnly: false, mustDial: false)
}

/// mesh.adv §4: skip connecting when the peer's advertised summary and our own summary are both unchanged since the
/// last reconciliation with that node; an urgent flag (bit0) always permits a connection.
func worthConnecting(_ peer: GattAdvert, lastSynced: (peer: [UInt8], own: [UInt8])?, ownSummary: [UInt8]) -> Bool {
    guard let summary = peer.summary, let lastSynced, !peer.urgent else { return true }
    return !(lastSynced.peer == summary && lastSynced.own == ownSummary)
}

/// BLE GATT link (mesh §3): GATT server + advertiser and scanner + central at once.
/// Each direction is the CBOR sequence of exchange messages (mesh §7) cut into min(ATT MTU - 3, 512) chunks: the central
/// writes RX without response (flow-controlled by canSendWriteWithoutResponse), the server notifies TX. Peer handles are "c:UUID" (we are central) and "p:UUID" (we are server).
/// All state lives on [queue]; listener calls hop to the main queue.
final class GattLink: NSObject, Link {
    let name = "ble"
    static let service = CBUUID(string: "b5e89a01-f9da-45c2-9111-2de7a7441d24")
    /// central -> peripheral, write without response.
    static let rx = CBUUID(string: "483c3867-c832-4c60-9a96-7018059eaf74")
    /// peripheral -> central, notify.
    static let tx = CBUUID(string: "49d60590-c23f-4a0a-a58e-f2f2f606f94d")
    static let maxLinks = 7, maxQueue = 64, maxAttribute = 512
    static let connectTimeout: TimeInterval = 15, asymmetricFallback: TimeInterval = 10
    private static let backoffSeconds: [TimeInterval] = [5, 30, 120]
    private static let incompatibleSeconds: TimeInterval = 600

    private final class Peer {
        let handle: String
        let peripheral: CBPeripheral?
        let central: CBCentral?
        var node: String?
        var connected = false
        var chunk = 20
        var reader = CborSequenceReader(limit: Exchange.messageLimit)
        var out = [[UInt8]]()
        var offset = 0
        var inFlight = 0
        var rx: CBCharacteristic?
        var tx: CBMutableCharacteristic?
        init(handle: String, peripheral: CBPeripheral?, central: CBCentral?, node: String?) {
            self.handle = handle; self.peripheral = peripheral; self.central = central; self.node = node
        }
    }

    private let queue = DispatchQueue(label: "mosimo.gatt")
    private let log = Logger(subsystem: "dev.jisaku.mosimo", category: "GattLink")
    private func trace(_ s: String) {
        log.info("\(s, privacy: .public)")
        #if DEBUG
        print("[ble] \(s)")
        #endif
    }
    private var central: CBCentralManager?
    private var peripheralManager: CBPeripheralManager?
    private var txCharacteristic: CBMutableCharacteristic?
    private var serviceAdded = false
    private var advertisedNode: String?
    private var running = false
    private var node = ""
    private weak var listener: LinkListener?
    private var peers = [String: Peer]()
    // ponytail: backoff keyed by CoreBluetooth peripheral UUID, which is stable per pairing of devices.
    private var backoff = [UUID: (Int, Date)]()
    // Nodes whose stream we rejected: redialing an incompatible build only repeats the failure and holds its radio.
    private var incompatible = [String: Date]()
    /// First time each peer was seen; lets the tie-break loser (or a node-less background peer) dial after a wait.
    private var firstSeen = [String: Date]()
    /// mesh.adv: our candidate summary, the last advertised summary per node, and the pair recorded at the last sync.
    private var summary = [UInt8](repeating: 0, count: 3)
    private var lastAdvertSummary = [String: [UInt8]]()
    private var lastSynced = [String: (peer: [UInt8], own: [UInt8])]()
    /// Last advert with Service Data per peripheral: a discovery before the scan response arrives lacks it.
    private var lastAdvert = [UUID: GattAdvert]()
    private let allowedPeerNode: String?
    init(allowedPeerNode: String? = nil) { self.allowedPeerNode = allowedPeerNode }
    /// Called on the main queue whenever radio availability changes.
    var onState: ((Bool) -> Void)?

    static var authorized: Bool { CBManager.authorization == .allowedAlways }
    static var denied: Bool { CBManager.authorization == .denied || CBManager.authorization == .restricted }

    func start(node: String, listener: LinkListener) {
        queue.async { [self] in
            guard !running else { return }
            running = true
            self.node = node; self.listener = listener
            if central == nil {
                central = CBCentralManager(delegate: self, queue: queue, options: [CBCentralManagerOptionRestoreIdentifierKey: "mosimo.central"])
                peripheralManager = CBPeripheralManager(delegate: self, queue: queue, options: [CBPeripheralManagerOptionRestoreIdentifierKey: "mosimo.peripheral"])
                // Calls made inside the managers' (restoring) init can be dropped; set up again once both exist.
                queue.async { [self] in
                    if let c = central { scan(c) }
                    if let pm = peripheralManager { advertise(pm) }
                }
            } else {
                centralManagerDidUpdateState(central!)
                peripheralManagerDidUpdateState(peripheralManager!)
            }
        }
    }

    func stop() {
        queue.async { [self] in
            guard running else { return }
            running = false
            central?.stopScan()
            peripheralManager?.stopAdvertising()
            // The GATT database is shared by every app on the device: a stopped (e.g. LocalLink follower) service must not stay in it.
            peripheralManager?.removeAllServices()
            serviceAdded = false
            advertisedNode = nil
            for p in Array(peers.values) { drop(p) }
            firstSeen.removeAll(); lastSynced.removeAll(); lastAdvertSummary.removeAll()
            listener = nil
        }
    }

    func send(_ peer: String, _ message: [UInt8]) -> Bool {
        guard !message.isEmpty, message.count <= Messages.maxMessageBytes else { return false }
        return queue.sync {
            guard let p = peers[peer], p.connected, p.out.count < Self.maxQueue else { return false }
            p.out.append(message)
            pump(p)
            return peers[peer] === p
        }
    }

    func queued(_ peer: String) -> Int { queue.sync { peers[peer]?.out.count ?? 0 } }

    func rejected(_ peer: String) {
        queue.async { [self] in
            guard let n = peers[peer]?.node else { return }
            incompatible[n] = Date().addingTimeInterval(Self.incompatibleSeconds)
            trace("not redialing node \(n) for \(Int(Self.incompatibleSeconds / 60)) min: invalid exchange stream")
        }
    }

    func synced(_ peer: String) {
        queue.async { [self] in
            guard let n = peers[peer]?.node, let s = lastAdvertSummary[n] else { return }
            lastSynced[n] = (s, summary)
        }
    }

    /// mesh.adv: iOS cannot advertise Service Data, so the summary only decides whether we dial (§4).
    func setSummary(_ value: [UInt8]) { queue.async { [self] in summary = Array(value.prefix(3)) } }

    func disconnect(_ peer: String) { queue.async { [self] in if let p = peers[peer] { drop(p) } } }

    // MARK: everything below runs on [queue]

    private func emit(_ f: @escaping (LinkListener) -> Void) {
        guard let l = listener else { return }
        DispatchQueue.main.async { f(l) }
    }

    private func publishState() {
        let ok = central?.state == .poweredOn
        DispatchQueue.main.async { [onState] in onState?(ok) }
    }

    // Managers come from the callback: with state restoration, delegate calls can arrive before init assigned them.
    private func scan(_ c: CBCentralManager) {
        guard running, c.state == .poweredOn else { return }
        c.scanForPeripherals(withServices: [Self.service], options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
        trace("scanning")
    }

    private func advertise(_ pm: CBPeripheralManager) {
        guard running, pm.state == .poweredOn else { return }
        trace("advertise (service added: \(serviceAdded), advertising: \(pm.isAdvertising))")
        if !serviceAdded {
            let rx = CBMutableCharacteristic(type: Self.rx, properties: [.write, .writeWithoutResponse], value: nil, permissions: [.writeable])
            let tx = CBMutableCharacteristic(type: Self.tx, properties: [.notify], value: nil, permissions: [])
            let s = CBMutableService(type: Self.service, primary: true)
            s.characteristics = [rx, tx]
            txCharacteristic = tx
            serviceAdded = true
            pm.add(s) // advertising starts in didAdd, once peers can find the service
            return
        }
        // A restored or earlier advertisement may name another process's node: replace it once per start.
        if advertisedNode != node {
            pm.stopAdvertising()
            pm.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [Self.service], CBAdvertisementDataLocalNameKey: node])
            advertisedNode = node
        }
    }

    private func connect(_ peripheral: CBPeripheral, _ ad: GattAdvert) {
        guard running, ad.node != node, peers.count < Self.maxLinks else { return }
        // Node-less (backgrounded iOS) peers are dialed too; Exchange enforces the lab peer at HELLO.
        if let allowed = allowedPeerNode, let n = ad.node, n != allowed { return }
        if let n = ad.node, let s = ad.summary { lastAdvertSummary[n] = s }
        if let n = ad.node, let until = incompatible[n], until > Date() { return }
        if let n = ad.node, !worthConnecting(ad, lastSynced: lastSynced[n], ownSummary: summary) { return }
        let key = ad.node ?? peripheral.identifier.uuidString
        let now = Date()
        let waited = now.timeIntervalSince(firstSeen[key] ?? { firstSeen[key] = now; return now }()) >= Self.asymmetricFallback
        // iOS↔iOS: the smaller node dials; visibility can be one-way, so the other dials too after a wait.
        let wins = ad.node.map { node < $0 } ?? false
        if !(ad.mustDial || ad.peripheralOnly || wins || waited) { return }
        if peers.values.contains(where: { $0.peripheral?.identifier == peripheral.identifier || (ad.node != nil && $0.node == ad.node) }) { return }
        if let b = backoff[peripheral.identifier], b.1 > now { return }
        let h = "c:\(peripheral.identifier.uuidString)"
        trace("connecting to node \(ad.node ?? "?") (\(h))")
        let p = Peer(handle: h, peripheral: peripheral, central: nil, node: ad.node)
        peers[h] = p
        peripheral.delegate = self
        central?.connect(peripheral)
        queue.asyncAfter(deadline: .now() + Self.connectTimeout) { [weak self, weak p] in
            guard let self, let p, self.peers[p.handle] === p, !p.connected else { return }
            self.drop(p)
        }
    }

    private func connected(_ p: Peer) {
        if p.connected { return }
        p.connected = true
        trace("connected \(p.handle) chunk \(p.chunk)")
        if let id = p.peripheral?.identifier { backoff[id] = nil }
        let node = p.node
        emit { [weak self] in if let self { $0.onConnected(self, p.handle, node: node) } }
    }

    private func received(_ p: Peer?, _ value: Data?) {
        guard let p, let value else { return }
        connected(p) // a notification can race ahead of our subscription callback
        do {
            for m in try p.reader.feed(Array(value)) { emit { [weak self] in if let self { $0.onMessage(self, p.handle, m) } } }
        } catch {
            trace("bad CBOR sequence from \(p.handle)"); drop(p)
        }
    }

    private func sent(_ p: Peer) {
        guard p.inFlight != 0 else { return }
        p.offset += p.inFlight; p.inFlight = 0
        if p.offset >= p.out[0].count { p.out.removeFirst(); p.offset = 0 }
        if p.out.isEmpty { emit { [weak self] in if let self { $0.onDrained(self, p.handle) } } }
        pump(p)
    }

    private func pump(_ p: Peer) {
        guard p.inFlight == 0, p.connected, let frame = p.out.first else { return }
        let size = max(1, min(p.chunk, Self.maxAttribute))
        let chunk = Data(frame[p.offset..<min(frame.count, p.offset + size)])
        if let peripheral = p.peripheral {
            guard let rx = p.rx else { drop(p); return }
            // Mesh §3.3: Write Without Response. Such writes get no callback; flow control is canSendWriteWithoutResponse,
            // and peripheralIsReady resumes the pump.
            if rx.properties.contains(.writeWithoutResponse) {
                guard peripheral.canSendWriteWithoutResponse else { return }
                p.inFlight = chunk.count
                peripheral.writeValue(chunk, for: rx, type: .withoutResponse)
                sent(p)
            } else {
                p.inFlight = chunk.count
                peripheral.writeValue(chunk, for: rx, type: .withResponse)
            }
        } else if let central = p.central, let pm = peripheralManager, let tx = p.tx ?? txCharacteristic {
            // Notifications have no per-chunk callback: false means the queue is full, retried in isReady.
            if pm.updateValue(chunk, for: tx, onSubscribedCentrals: [central]) { p.inFlight = chunk.count; sent(p) }
        }
    }

    /// Forgets [p] and closes its connection; onDisconnected only if onConnected was sent.
    private func drop(_ p: Peer) {
        guard peers[p.handle] === p else { return }
        peers[p.handle] = nil
        trace("dropped \(p.handle) (connected=\(p.connected))")
        if let peripheral = p.peripheral { central?.cancelPeripheralConnection(peripheral) }
        // ponytail: CoreBluetooth cannot cut an inbound central; it is only forgotten (writes are then refused).
        if let n = p.node { firstSeen[n] = nil }
        if p.connected { emit { [weak self] in if let self { $0.onDisconnected(self, p.handle) } } }
        else if let id = p.peripheral?.identifier {
            let n = (backoff[id]?.0 ?? 0) + 1
            backoff[id] = (n, Date().addingTimeInterval(Self.backoffSeconds[min(n, Self.backoffSeconds.count) - 1]))
        }
    }

    private func peer(_ peripheral: CBPeripheral) -> Peer? { peers["c:\(peripheral.identifier.uuidString)"] }
}

extension GattLink: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ c: CBCentralManager) {
        trace("central state \(c.state.rawValue)")
        publishState()
        if c.state == .poweredOn { scan(c) } else { for p in peers.values where p.peripheral != nil { drop(p) } }
    }

    func centralManager(_ c: CBCentralManager, willRestoreState dict: [String: Any]) {
        // Restored links lost their sessions; rediscover them instead of resuming half a frame stream.
        for p in dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? [] { c.cancelPeripheralConnection(p) }
        // A restored scan keeps running without us; restart it so discoveries follow this process's settings.
        c.stopScan()
    }

    func centralManager(_ c: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi: NSNumber) {
        let serviceData = (advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data])?[Self.service]
        let name = advertisementData[CBAdvertisementDataLocalNameKey] as? String
        guard var ad = parseGattAdvert(serviceData: serviceData, localName: name) else { return }
        if ad.mustDial { lastAdvert[peripheral.identifier] = ad } else if ad.node == nil, let known = lastAdvert[peripheral.identifier] { ad = known }
        if lastAdvert.count > 256 { lastAdvert.removeAll() }
        connect(peripheral, ad)
    }

    func centralManager(_ c: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard let p = peer(peripheral) else { c.cancelPeripheralConnection(peripheral); return }
        p.chunk = peripheral.maximumWriteValueLength(for: .withoutResponse)
        peripheral.discoverServices([Self.service])
    }

    func centralManager(_ c: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        if let p = peer(peripheral) { drop(p) }
    }

    func centralManager(_ c: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        if let p = peer(peripheral) { drop(p) }
    }
}

extension GattLink: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let p = peer(peripheral) else { return }
        guard error == nil, let s = peripheral.services?.first(where: { $0.uuid == Self.service }) else { drop(p); return }
        peripheral.discoverCharacteristics([Self.rx, Self.tx], for: s)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let p = peer(peripheral) else { return }
        let rx = service.characteristics?.first { $0.uuid == Self.rx }
        guard error == nil, let rx, let tx = service.characteristics?.first(where: { $0.uuid == Self.tx }) else { drop(p); return }
        p.rx = rx
        peripheral.setNotifyValue(true, for: tx)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard let p = peer(peripheral) else { return }
        if error == nil && characteristic.isNotifying {
            // ATT MTU - 3 (at most 512): larger writes would become prepared writes, which peers refuse.
            p.chunk = peripheral.maximumWriteValueLength(for: .withoutResponse)
            connected(p)
        } else { drop(p) }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == Self.tx, error == nil else { return }
        received(peer(peripheral), characteristic.value)
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let p = peer(peripheral) else { return }
        if error == nil { sent(p) } else { trace("write failed on \(p.handle)"); drop(p) }
    }

    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) { if let p = peer(peripheral) { pump(p) } }

    // A killed Android app loses its GATT service but the OS keeps our link up, and the restarted app
    // (new address) refuses a second link from us: drop the dead one so the new advertisement can be dialed.
    func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        guard let p = peer(peripheral), invalidatedServices.contains(where: { $0.uuid == Self.service }) else { return }
        trace("service invalidated on \(p.handle)")
        drop(p)
    }
}

extension GattLink: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ pm: CBPeripheralManager) {
        trace("peripheral state \(pm.state.rawValue)")
        if pm.state == .poweredOn { advertise(pm) } else {
            serviceAdded = false
            advertisedNode = nil
            for p in peers.values where p.central != nil { drop(p) }
        }
    }

    func peripheralManager(_ pm: CBPeripheralManager, willRestoreState dict: [String: Any]) {
        trace("peripheral restore \(dict.keys.sorted())")
        // Keep the restored service: replacing it changes the GATT database under centrals that cached its handles.
        let services = dict[CBPeripheralManagerRestoredStateServicesKey] as? [CBMutableService] ?? []
        if let tx = services.first(where: { $0.uuid == Self.service })?.characteristics?.first(where: { $0.uuid == Self.tx }) as? CBMutableCharacteristic {
            txCharacteristic = tx
            serviceAdded = true
        } else {
            pm.removeAllServices()
            serviceAdded = false
        }
        // The restored advertisement still names the previous process's node.
        pm.stopAdvertising()
        advertisedNode = nil
    }

    func peripheralManager(_ pm: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error { trace("addService failed: \(error.localizedDescription)"); serviceAdded = false; return }
        advertise(pm)
    }

    func peripheralManagerDidStartAdvertising(_ pm: CBPeripheralManager, error: Error?) {
        if let error { trace("advertise failed: \(error.localizedDescription)") } else { trace("advertising node \(self.node)") }
    }

    func peripheralManager(_ pm: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        let h = "p:\(central.identifier.uuidString)"
        guard running, characteristic.uuid == Self.tx, peers[h] == nil else { return }
        guard peers.count < Self.maxLinks else { return }
        let p = Peer(handle: h, peripheral: nil, central: central, node: nil)
        p.chunk = central.maximumUpdateValueLength
        p.tx = characteristic as? CBMutableCharacteristic
        peers[h] = p
        connected(p)
    }

    func peripheralManager(_ pm: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        if let p = peers["p:\(central.identifier.uuidString)"] { drop(p) }
    }

    func peripheralManager(_ pm: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        // ponytail: no prepared (long) writes; centrals never write more than ATT MTU - 3 bytes at once.
        var ok = true
        for r in requests {
            let p = peers["p:\(r.central.identifier.uuidString)"]
            if p == nil || r.characteristic.uuid != Self.rx || r.offset != 0 { ok = false; continue }
            received(p, r.value)
        }
        if let first = requests.first { pm.respond(to: first, withResult: ok ? .success : .writeNotPermitted) }
    }

    func peripheralManagerIsReady(toUpdateSubscribers pm: CBPeripheralManager) {
        for p in peers.values where p.central != nil { pump(p) }
    }
}
