#pragma once
// Only the platform/relay boundaries are mocked; MeshBle.cpp is compiled unchanged.
#include "MeshBundle.h"
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <deque>
#include <functional>
#include <memory>
#include <string>
#include <vector>

extern uint32_t fakeMs;
inline uint32_t millis() { return fakeMs; }
struct MockSerial {
    template<class... Args> void printf(const char*, Args...) {}
};
extern MockSerial Serial;

constexpr int pdTRUE = 1;
struct MockQueue {
    size_t capacity, itemSize;
    std::deque<std::vector<uint8_t>> items;
};
using QueueHandle_t = MockQueue*;
extern std::vector<std::unique_ptr<MockQueue>> mockQueues;
extern unsigned queueResets;
inline QueueHandle_t xQueueCreate(size_t count, size_t size) {
    mockQueues.emplace_back(new MockQueue{count, size, {}});
    return mockQueues.back().get();
}
inline int xQueueSend(QueueHandle_t q, const void* value, int) {
    if (q->items.size() == q->capacity) return 0;
    const auto* p = static_cast<const uint8_t*>(value);
    q->items.emplace_back(p, p + q->itemSize);
    return pdTRUE;
}
inline int xQueueReceive(QueueHandle_t q, void* value, int) {
    if (q->items.empty()) return 0;
    std::memcpy(value, q->items.front().data(), q->itemSize);
    q->items.pop_front();
    return pdTRUE;
}
inline void xQueueReset(QueueHandle_t q) { ++queueResets; q->items.clear(); }

constexpr uint16_t BLE_HS_CONN_HANDLE_NONE = 0xffff;
namespace NIMBLE_PROPERTY { constexpr int WRITE = 1, WRITE_NR = 2, NOTIFY = 4; }
class NimBLEServer;
class NimBLECharacteristic;
struct NimBLEConnInfo {
    uint16_t handle = 0, mtu = 23;
    uint16_t getConnHandle() const { return handle; }
    uint16_t getMTU() const { return mtu; }
};
struct NimBLEServerCallbacks {
    virtual ~NimBLEServerCallbacks() {}
    virtual void onConnect(NimBLEServer*, NimBLEConnInfo&) {}
    virtual void onDisconnect(NimBLEServer*, NimBLEConnInfo&, int) {}
    virtual void onMTUChange(uint16_t, NimBLEConnInfo&) {}
};
struct NimBLECharacteristicCallbacks {
    virtual ~NimBLECharacteristicCallbacks() {}
    virtual void onWrite(NimBLECharacteristic*, NimBLEConnInfo&) {}
    virtual void onSubscribe(NimBLECharacteristic*, NimBLEConnInfo&, uint16_t) {}
    virtual void onStatus(NimBLECharacteristic*, NimBLEConnInfo&, int) {}
};
struct Notification {
    uint16_t handle;
    uint32_t at;
    bool accepted;
    std::vector<uint8_t> bytes;
};
struct NimBLECharacteristic {
    NimBLECharacteristicCallbacks* callbacks = nullptr;
    std::vector<uint8_t> value;
    std::vector<Notification> attempts;
    bool accept = true;
    std::function<void()> duringNotify;
    void setCallbacks(NimBLECharacteristicCallbacks* cb) { callbacks = cb; }
    std::vector<uint8_t> getValue() { return value; }
    bool notify(const uint8_t* p, size_t n, uint16_t handle) {
        attempts.push_back({handle, fakeMs, accept, {p, p + n}});
        const bool result = accept;
        // Match 2.5.1: status happens inside notify, with default ConnInfo (0).
        NimBLEConnInfo bogus;
        if (callbacks) callbacks->onStatus(this, bogus, result ? 0 : 12);
        if (duringNotify) duringNotify();
        return result;
    }
};
struct NimBLEService {
    NimBLECharacteristic rx, tx;
    NimBLECharacteristic* createCharacteristic(const char*, int properties) {
        return properties == NIMBLE_PROPERTY::NOTIFY ? &tx : &rx;
    }
    void start() {}
};
struct NimBLEUUID { explicit NimBLEUUID(const char*) {} };
struct NimBLEAdvertisementData {
    void setFlags(int) {}
    void addServiceUUID(const char*) {}
    void setServiceData(NimBLEUUID, const uint8_t*, size_t) {}
};
struct NimBLEAdvertising {
    bool active = false;
    unsigned starts = 0, stops = 0;
    std::function<void()> onStart;
    bool setAdvertisementData(const NimBLEAdvertisementData&) { return true; }
    bool setScanResponseData(const NimBLEAdvertisementData&) { return true; }
    void enableScanResponse(bool) {}
    bool start() {
        active = true; ++starts;
        if (onStart) onStart();
        return true;
    }
    bool stop() { active = false; ++stops; return true; }
};
extern NimBLEAdvertising mockAdvertising;
struct NimBLEServer {
    NimBLEServerCallbacks* callbacks = nullptr;
    NimBLEService service;
    bool automaticAdvertising = true;
    NimBLEConnInfo physical;
    bool alive = false, disconnectPending = false, immediateDisconnect = false;
    std::vector<uint16_t> disconnects;
    void setCallbacks(NimBLEServerCallbacks* cb, bool) { callbacks = cb; }
    void advertiseOnDisconnect(bool enabled) { automaticAdvertising = enabled; }
    NimBLEService* createService(const char*) { return &service; }
    bool disconnect(uint16_t handle) {
        disconnects.push_back(handle);
        if (!alive || handle != physical.handle) return false;
        disconnectPending = true;
        if (immediateDisconnect) down();
        return true;
    }
    void up(uint16_t handle, uint16_t mtu = 23) {
        physical.handle = handle; physical.mtu = mtu;
        alive = true; disconnectPending = false; mockAdvertising.active = false;
        callbacks->onConnect(this, physical);
    }
    void down() {
        alive = false; disconnectPending = false;
        callbacks->onDisconnect(this, physical, 19);
        if (automaticAdvertising) mockAdvertising.start();
    }
    void subscribe(uint16_t bits = 1) {
        service.tx.callbacks->onSubscribe(&service.tx, physical, bits);
    }
    void write(const std::vector<uint8_t>& bytes) {
        service.rx.value = bytes;
        service.rx.callbacks->onWrite(&service.rx, physical);
    }
    void mtu(uint16_t value) { physical.mtu = value; callbacks->onMTUChange(value, physical); }
};
extern NimBLEServer mockServer;
struct NimBLEDevice {
    static void init(const char*) {}
    static void setMTU(uint16_t) {}
    static NimBLEServer* createServer() { return &mockServer; }
    static NimBLEAdvertising* getAdvertising() { return &mockAdvertising; }
};

namespace mesh {
struct Relay {
    unsigned connections = 0, disconnections = 0, ticks = 0;
    bool reject = false;
    std::vector<std::vector<uint8_t>> frames;
    std::function<void()> duringReceive, duringTick;
    void connected() { ++connections; reject = false; }
    void disconnected() { ++disconnections; reject = false; }
    void receive(const uint8_t* p, size_t n) {
        frames.emplace_back(p, p + n);
        if (duringReceive) duringReceive();
    }
    bool disconnectRequested() const { return reject; }
    void tick() { ++ticks; if (duringTick) duringTick(); }
};
} // namespace mesh
