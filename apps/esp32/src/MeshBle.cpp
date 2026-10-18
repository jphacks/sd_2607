#include "MeshBle.h"
#include "MeshRelay.h"

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
constexpr size_t MAX_QUEUE = 8, MAX_QUEUED_BYTES = 48000;
}

bool Ble::begin(const std::string& node) {
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

    uint8_t data[9];
    for (size_t i = 0; i < 8; ++i) data[i] = std::stoul(node.substr(i * 2, 2), nullptr, 16);
    data[8] = 1; // Peripheral-only: Android must initiate, irrespective of node-ID order.
    NimBLEAdvertisementData advertisement, response;
    advertisement.setFlags(0x06);
    advertisement.addServiceUUID(SERVICE);
    response.setServiceData(NimBLEUUID(SERVICE), data, sizeof(data));
    auto advertising = NimBLEDevice::getAdvertising();
    if (!advertising->setAdvertisementData(advertisement) || !advertising->setScanResponseData(response)) return false;
    advertising->enableScanResponse(true);
    return advertising->start();
}

void Ble::enqueue(Kind kind, uint16_t handle, uint16_t size, int status, const uint8_t* bytes) {
    if (overflow_.load()) return;
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
    prefixBytes_ = expected_ = 0;
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
        xQueueReset(events_);
        overflow_.store(false);
        recovering_ = false;
        if (enabled_) NimBLEDevice::getAdvertising()->start();
    }
    return true;
}
void Ble::setEnabled(bool enabled, Relay& relay) {
    if (!server_ || enabled == enabled_) return;
    enabled_ = enabled;
    if (!enabled_) {
        drop(relay, "RX/TX disabled by user");
        recover(relay);
    } else if (!recovering_) {
        NimBLEDevice::getAdvertising()->start();
    }
}
bool Ble::send(const uint8_t* bytes, size_t size) {
    if (!enabled_ || !ready_ || recovering_ || overflow_.load() || !callbackAlive_.load() ||
        !bytes || !size || size > MAX_BUNDLE_BYTES || out_.size() >= MAX_QUEUE ||
        size + 4 > MAX_QUEUED_BYTES - queuedBytes_) return false;
    std::vector<uint8_t> frame(size + 4);
    frame[0] = size >> 24; frame[1] = size >> 16; frame[2] = size >> 8; frame[3] = size;
    std::memcpy(frame.data() + 4, bytes, size);
    queuedBytes_ += frame.size();
    out_.push_back(std::move(frame));
    return true;
}
bool Ble::feed(Relay& relay, const uint8_t* bytes, size_t size) {
    rxMs_ = millis();
    for (size_t at = 0; at < size;) {
        if (overflow_.load()) return false;
        if (prefixBytes_ < 4) {
            prefix_[prefixBytes_++] = bytes[at++];
            if (prefixBytes_ != 4) continue;
            expected_ = (uint32_t(prefix_[0]) << 24) | (uint32_t(prefix_[1]) << 16) |
                        (uint32_t(prefix_[2]) << 8) | prefix_[3];
            if (!expected_ || expected_ > MAX_BUNDLE_BYTES) return false;
            incoming_.clear();
            incoming_.reserve(expected_);
        }
        const size_t count = std::min(expected_ - incoming_.size(), size - at);
        incoming_.insert(incoming_.end(), bytes + at, bytes + at + count);
        at += count;
        if (incoming_.size() == expected_) {
            if (overflow_.load()) return false;
            relay.receive(incoming_.data(), incoming_.size());
            incoming_.clear(); prefixBytes_ = expected_ = 0;
            if (overflow_.load() || relay.disconnectRequested()) return false;
        }
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
    if (!enabled_) return;
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
    if (prefixBytes_ && now - rxMs_ > 15000) drop(relay, "incomplete frame timeout");
    if (relay.disconnectRequested()) drop(relay, "exchange requested disconnect");
    if (recover(relay)) return;
    relay.tick();
    if (recover(relay)) return;
    if (relay.disconnectRequested()) { drop(relay, "exchange requested disconnect"); recover(relay); return; }
    pump(relay);
    recover(relay);
}
} // namespace mesh
