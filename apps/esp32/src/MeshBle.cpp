#include "MeshBle.h"
#include "MeshRelay.h"
#include "MeshCbor.h"

#include <Arduino.h>
#include <algorithm>
#include <cstring>
#ifdef MESH_GATT_TRACE
#include <esp_log.h>
#include "nimble/porting/nimble/include/os/os_mbuf.h"
#endif

namespace mesh {
namespace {
const char* SERVICE = "b5e89a01-f9da-45c2-9111-2de7a7441d24";
const char* RX_UUID = "483c3867-c832-4c60-9a96-7018059eaf74";
const char* TX_UUID = "49d60590-c23f-4a0a-a58e-f2f2f606f94d";
constexpr size_t MAX_QUEUE = 16, MAX_QUEUED_BYTES = 16384;
constexpr uint32_t ADVERTISEMENT_REFRESH_MS = 5000;
}

bool Ble::begin(const std::string& node) {
    node_ = node;
#ifdef MESH_GATT_TRACE
    esp_log_level_set("NimBLE", ESP_LOG_DEBUG);
#endif
    events_ = xQueueCreate(32, sizeof(Event));
    if (!events_) return false;
    NimBLEDevice::init("mosimo M5Stack");
    NimBLEDevice::setMTU(517);
    server_ = NimBLEDevice::createServer();
    server_->setCallbacks(this, false);
    server_->advertiseOnDisconnect(false);
    auto service = server_->createService(SERVICE);
    auto rx = service->createCharacteristic(RX_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
    tx_ = service->createCharacteristic(TX_UUID, NIMBLE_PROPERTY::NOTIFY);
    rx->setCallbacks(this);
    tx_->setCallbacks(this);
    service->start();

    NimBLEAdvertisementData advertisement;
    advertisement.setFlags(0x06);
    advertisement.addServiceUUID(SERVICE);
    auto advertising = NimBLEDevice::getAdvertising();
    if (!advertising->setAdvertisementData(advertisement) || !applyScanResponse()) return false;
    advertising->enableScanResponse(true);
    return !enabled_ || networkPaused_ || advertising->start();
}
// Mesh §3.1 + mesh.adv §1: Service UUID 16 | node ID 8 | role flags 1 | summary 3 | ext flags 1.
bool Ble::applyScanResponse() {
    uint8_t data[13];
    for (size_t i = 0; i < 8; ++i) data[i] = std::stoul(node_.substr(i * 2, 2), nullptr, 16);
    data[8] = 1; // Peripheral-only (role flags bit0): phones initiate, irrespective of node-ID order.
    std::memcpy(data + 9, advertised_, sizeof advertised_);
    NimBLEAdvertisementData response;
    return response.setServiceData(NimBLEUUID(SERVICE), data, sizeof(data)) &&
           NimBLEDevice::getAdvertising()->setScanResponseData(response);
}
void Ble::refreshAdvertisement(Relay& relay) {
    const uint32_t now = millis();
    if (handle_ != BLE_HS_CONN_HANDLE_NONE || !server_ ||
        (advertisementMs_ && now - advertisementMs_ < ADVERTISEMENT_REFRESH_MS)) return;
    advertisementMs_ = now ? now : 1;
    uint8_t current[4];
    relay.advertisement(current);
    if (!std::memcmp(current, advertised_, sizeof advertised_)) return;
    std::memcpy(advertised_, current, sizeof advertised_);
    if (!applyScanResponse()) Serial.printf("BLE scan response update failed\n");
}

void Ble::enqueue(Kind kind, uint16_t handle, uint16_t size, int status, const uint8_t* bytes) {
    if (!events_ || overflow_.load()) return;
    Event event{};
    event.kind = kind; event.handle = handle; event.size = size; event.status = status;
    if (bytes) {
        if (size > sizeof(event.bytes)) { overflow_.store(true); return; }
        std::memcpy(event.bytes, bytes, size);
    }
    if (xQueueSend(events_, &event, 0) != pdTRUE) overflow_.store(true);
}
void Ble::onConnect(NimBLEServer*, NimBLEConnInfo& info) {
    callbackHandle_.store(info.getConnHandle());
    callbackAlive_.store(true);
    enqueue(Up, info.getConnHandle(), info.getMTU());
}
void Ble::onDisconnect(NimBLEServer*, NimBLEConnInfo& info, int reason) {
    enqueue(Down, info.getConnHandle(), 0, reason);
    // Publish quiescence only after the final lifecycle event has been enqueued.
    callbackAlive_.store(false);
}
void Ble::onMTUChange(uint16_t mtu, NimBLEConnInfo& info) { enqueue(Mtu, info.getConnHandle(), mtu); }
void Ble::onWrite(NimBLECharacteristic* characteristic, NimBLEConnInfo& info) {
    auto value = characteristic->getValue();
    if (value.size() > 512) { overflow_.store(true); return; }
    enqueue(Rx, info.getConnHandle(), value.size(), 0, value.data());
}
void Ble::onSubscribe(NimBLECharacteristic*, NimBLEConnInfo& info, uint16_t value) {
    enqueue(Subscribe, info.getConnHandle(), value);
}

void Ble::clear() {
    subscribed_ = ready_ = false;
    out_.clear(); queuedBytes_ = offset_ = 0;
    txAttempted_ = txBlocked_ = false;
    incoming_.clear();
    mtu_ = 23;
}
void Ble::drop(Relay& relay, const char* reason) {
    Serial.printf("BLE drop: %s\n", reason);
    relay.disconnected();
    recovering_ = true;
    NimBLEDevice::getAdvertising()->stop();
    handle_ = BLE_HS_CONN_HANDLE_NONE;
    clear();
}
bool Ble::recover(Relay& relay) {
    if (!recovering_ && !overflow_.load()) return false;
    if (!recovering_) drop(relay, "event queue overflow");
    if (callbackAlive_.load()) {
        // Up/Down may themselves have been lost. Use the physical callback handle.
        server_->disconnect(callbackHandle_.load());
    }
    if (!callbackAlive_.load()) {
        // Auto-advertising is disabled: no fresh Up can race this reset. Down
        // publishes callbackAlive_=false after enqueueing its final event.
        if (events_) xQueueReset(events_);
        overflow_.store(false);
        recovering_ = false;
        if (enabled_ && !networkPaused_) NimBLEDevice::getAdvertising()->start();
    }
    return true;
}
void Ble::setEnabled(bool enabled, Relay& relay) {
    if (enabled == enabled_) return;
    enabled_ = enabled;
    if (!server_) return;
    if (!enabled_) {
        drop(relay, "RX/TX disabled by user");
        recover(relay);
    } else if (!recovering_ && !networkPaused_) {
        NimBLEDevice::getAdvertising()->start();
    }
}
bool Ble::pauseForNetwork(bool pause, Relay& relay) {
    if (pause != networkPaused_) {
#ifdef MESH_GATEWAY_TTS
        if (!pause && !events_) {
            if (!server_) {
                if (!begin(node_)) { Serial.printf("BLE resume failed: stack initialization\n"); return false; }
            } else events_ = xQueueCreate(32, sizeof(Event));
            if (!events_) { Serial.printf("BLE resume failed: event queue allocation\n"); return false; }
        }
#endif
        networkPaused_ = pause;
        if (pause) {
            drop(relay, "TLS/TTS memory preflight; BLE resumes after work");
            std::vector<uint8_t>().swap(incoming_);
            std::deque<std::vector<uint8_t>>().swap(out_);
        } else if (enabled_ && !recovering_) NimBLEDevice::getAdvertising()->start();
    }
    if (pause && events_) recover(relay);
    const bool quiescent = !callbackAlive_.load() && !recovering_;
#ifdef MESH_GATEWAY_TTS
    // Delete only after the final Down callback; no new Up while advertising is off.
    if (pause && quiescent && events_) {
        if (!NimBLEDevice::deinit(true)) { Serial.printf("BLE stop failed; no TLS admission\n"); return false; }
        server_ = nullptr; tx_ = nullptr;
        vQueueDelete(events_); events_ = nullptr;
        Serial.printf("BLE stack stopped for TLS/TTS; identity preserved\n");
    }
#endif
    return !pause || quiescent;
}
// Mesh §7: messages are written back to back as a CBOR sequence; the GATT
// write boundaries need not match message boundaries.
bool Ble::send(const uint8_t* bytes, size_t size) {
    if (!enabled_ || networkPaused_ || !ready_ || recovering_ || overflow_.load() || !callbackAlive_.load() ||
        !bytes || !size || size > Relay::MESSAGE_LIMIT || out_.size() >= MAX_QUEUE ||
        size > MAX_QUEUED_BYTES - queuedBytes_) return false;
    queuedBytes_ += size;
    out_.emplace_back(bytes, bytes + size);
    return true;
}
bool Ble::feed(Relay& relay, const uint8_t* bytes, size_t size) {
    rxMs_ = millis();
    incoming_.insert(incoming_.end(), bytes, bytes + size);
    while (!incoming_.empty()) {
        if (overflow_.load()) return false;
        size_t length = 0;
        const int state = cbor::measure(incoming_.data(), incoming_.size(), length, Relay::MESSAGE_LIMIT);
        // Mesh §7: an item longer than our advertised limit, or malformed CBOR,
        // means the stream lost synchronization; the link must be closed.
        if (state < 0 || (state > 0 && length > Relay::MESSAGE_LIMIT)) return false;
        if (state == 0) return incoming_.size() <= Relay::MESSAGE_LIMIT;
        relay.receive(incoming_.data(), length);
        incoming_.erase(incoming_.begin(), incoming_.begin() + length);
        if (overflow_.load() || relay.disconnectRequested()) return false;
    }
    return true;
}
void Ble::pump(Relay& relay) {
    if (!enabled_ || !ready_ || recovering_ || overflow_.load() || !callbackAlive_.load() || out_.empty()) return;
    const uint32_t now = millis();
    if (txBlocked_ && now - blockedMs_ >= 10000) { drop(relay, "notify queue timeout"); return; }
    if (txAttempted_ && now - txMs_ < 10) return;
    const auto& frame = out_.front();
    const size_t capacity = std::min<size_t>(mtu_ - 3, 512);
    const size_t count = std::min(capacity, frame.size() - offset_);
    txMs_ = now; txAttempted_ = true;
    // NimBLE 2.5.1 reports notification status synchronously inside notify(),
    // with unpopulated ConnInfo. Its return is queue acceptance, NOT an app ACK.
    if (!tx_->notify(frame.data() + offset_, count, handle_)) {
        if (!txBlocked_) { txBlocked_ = true; blockedMs_ = now; }
        return;
    }
    txBlocked_ = false;
    offset_ += count;
    if (offset_ == frame.size()) {
        queuedBytes_ -= frame.size(); out_.pop_front(); offset_ = 0;
    }
}
void Ble::poll(Relay& relay) {
    if (recover(relay)) return;
    if (!enabled_ || networkPaused_) return;
    Event event;
    // A busy peer can replenish the queue while verification/storage runs.
    // Return to the main loop regularly so mute/radio buttons remain usable.
    for (unsigned handled = 0; handled < 4 && xQueueReceive(events_, &event, 0) == pdTRUE; ++handled) {
        if (recover(relay)) return;
        if (event.kind == Down && event.handle == callbackHandle_.load()) {
            Serial.printf("BLE disconnected reason=%d\n", event.status);
            drop(relay, "physical disconnect");
            recover(relay);
            return;
        }
        if (event.kind == Up) {
            clear(); relay.disconnected();
            handle_ = event.handle; mtu_ = std::max<uint16_t>(event.size, 23);
            connectedMs_ = millis();
            Serial.printf("BLE connected handle=%u mtu=%u\n", handle_, mtu_);
#ifdef MESH_GATT_TRACE
            Serial.printf("[GATT-PROBE] peer=%s at=%lu\n",
                          server_->getPeerInfoByHandle(handle_).getAddress().toString().c_str(),
                          static_cast<unsigned long>(millis()));
#endif
            continue;
        }
        if (event.handle != handle_) continue;
        switch (event.kind) {
            case Mtu:
                mtu_ = std::max<uint16_t>(event.size, 23);
#ifdef MESH_GATT_TRACE
                Serial.printf("[GATT-PROBE] mtu handle=%u value=%u at=%lu\n", handle_, mtu_, static_cast<unsigned long>(millis()));
#endif
                break;
            case Subscribe:
#ifdef MESH_GATT_TRACE
                Serial.printf("[GATT-PROBE] subscribe handle=%u bits=%u at=%lu\n", handle_, event.size, static_cast<unsigned long>(millis()));
#endif
                if (!(event.size & 1)) { drop(relay, "notification subscription removed"); break; }
                subscribed_ = true;
                if (!ready_) { ready_ = true; relay.connected(); }
                break;
            case Rx: {
                const bool valid = ready_ && feed(relay, event.bytes, event.size);
                if (recover(relay)) return;
                if (!valid) drop(relay, "invalid stream or relay rejection");
                break;
            }
            default: break;
        }
        if (recover(relay)) return;
    }
    if (recover(relay)) return;
    const uint32_t now = millis();
#ifdef MESH_GATT_TRACE
    static uint32_t lastTrace = 0;
    if (now - lastTrace >= 5000) {
        lastTrace = now;
        Serial.printf("[GATT-PROBE] at=%lu alive=%u handle=%u mtu=%u subscribed=%u ready=%u msys_free=%d outgoing=%u\n",
                      static_cast<unsigned long>(now), unsigned(callbackAlive_.load()), handle_, mtu_,
                      unsigned(subscribed_), unsigned(ready_), os_msys_num_free(), unsigned(out_.size()));
    }
#endif
    if (handle_ != BLE_HS_CONN_HANDLE_NONE && !subscribed_ && now - connectedMs_ > 30000) drop(relay, "subscription timeout");
    if (!incoming_.empty() && now - rxMs_ > 15000) drop(relay, "incomplete message timeout");
    refreshAdvertisement(relay);
    if (relay.disconnectRequested()) drop(relay, "exchange requested disconnect");
    if (recover(relay)) return;
    relay.tick();
    if (recover(relay)) return;
    if (relay.disconnectRequested()) { drop(relay, "exchange requested disconnect"); recover(relay); return; }
    pump(relay);
    recover(relay);
}
} // namespace mesh
