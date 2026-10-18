#pragma once

#include <NimBLEDevice.h>
#include <atomic>
#include <deque>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include <vector>

namespace mesh {
class Relay;

// Peripheral-only compatibility transport for Android's experimental mndt §9.
// BLE callbacks only enqueue bounded events; filesystem and crypto run in poll().
class Ble : private NimBLEServerCallbacks, private NimBLECharacteristicCallbacks {
public:
    bool begin(const std::string& node);
    bool send(const uint8_t* bytes, size_t size);
    void poll(Relay& relay);

private:
    enum Kind : uint8_t { Up, Down, Subscribe, Rx, Mtu };
    struct Event {
        Kind kind;
        uint16_t handle;
        uint16_t size;
        int status;
        uint8_t bytes[512];
    };
    QueueHandle_t events_ = nullptr;
    std::atomic<bool> overflow_{false};
        std::atomic<bool> callbackAlive_{false};
    std::atomic<uint16_t> callbackHandle_{BLE_HS_CONN_HANDLE_NONE};
    NimBLEServer* server_ = nullptr;
    NimBLECharacteristic* tx_ = nullptr;
    uint16_t handle_ = BLE_HS_CONN_HANDLE_NONE, mtu_ = 23;
    bool subscribed_ = false, ready_ = false, recovering_ = false;
        bool txAttempted_ = false, txBlocked_ = false;
    std::deque<std::vector<uint8_t>> out_;
    size_t queuedBytes_ = 0, offset_ = 0;
    uint32_t connectedMs_ = 0, txMs_ = 0, blockedMs_ = 0, rxMs_ = 0;
    uint8_t prefix_[4] = {};
    size_t prefixBytes_ = 0, expected_ = 0;
    std::vector<uint8_t> incoming_;

    void enqueue(Kind kind, uint16_t handle, uint16_t size = 0, int status = 0,
                 const uint8_t* bytes = nullptr);
    void clear();
    void drop(Relay& relay, const char* reason);
        bool recover(Relay& relay);
    bool feed(Relay& relay, const uint8_t* bytes, size_t size);
    void pump(Relay& relay);
    void onConnect(NimBLEServer*, NimBLEConnInfo& info) override;
    void onDisconnect(NimBLEServer*, NimBLEConnInfo& info, int reason) override;
    void onMTUChange(uint16_t mtu, NimBLEConnInfo& info) override;
    void onWrite(NimBLECharacteristic*, NimBLEConnInfo& info) override;
    void onSubscribe(NimBLECharacteristic*, NimBLEConnInfo& info, uint16_t value) override;

};
} // namespace mesh
