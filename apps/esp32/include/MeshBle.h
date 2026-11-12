#pragma once

#include <NimBLEDevice.h>
#include <atomic>
#include <deque>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include <vector>

namespace mesh {
class Relay;

// Mesh §3 GATT transport, peripheral-only (role flags bit0): advertises the
// common service with node ID + mesh.adv summary in the scan response and
// carries common exchange messages as a CBOR sequence over RX/TX.
// BLE callbacks only enqueue bounded events; filesystem and crypto run in poll().
class Ble : private NimBLEServerCallbacks, private NimBLECharacteristicCallbacks {
public:
    bool begin(const std::string& node);
    bool send(const uint8_t* bytes, size_t size);
    void poll(Relay& relay);
    void setEnabled(bool enabled, Relay& relay);
    bool enabled() const { return enabled_; }
    // Main-loop preflight: quiesce a session without changing the user's B state.
    bool pauseForNetwork(bool pause, Relay& relay);

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
    bool enabled_ = true, networkPaused_ = false;
    std::string node_;
        bool txAttempted_ = false, txBlocked_ = false;
    std::deque<std::vector<uint8_t>> out_;
    size_t queuedBytes_ = 0, offset_ = 0;
    uint32_t connectedMs_ = 0, txMs_ = 0, blockedMs_ = 0, rxMs_ = 0;
    std::vector<uint8_t> incoming_;
    uint8_t advertised_[4] = {};
    uint32_t advertisementMs_ = 0;

    void enqueue(Kind kind, uint16_t handle, uint16_t size = 0, int status = 0,
                 const uint8_t* bytes = nullptr);
    void clear();
    void drop(Relay& relay, const char* reason);
        bool recover(Relay& relay);
    bool feed(Relay& relay, const uint8_t* bytes, size_t size);
    bool applyScanResponse();
    void refreshAdvertisement(Relay& relay);
    void pump(Relay& relay);
    void onConnect(NimBLEServer*, NimBLEConnInfo& info) override;
    void onDisconnect(NimBLEServer*, NimBLEConnInfo& info, int reason) override;
    void onMTUChange(uint16_t mtu, NimBLEConnInfo& info) override;
    void onWrite(NimBLECharacteristic*, NimBLEConnInfo& info) override;
    void onSubscribe(NimBLECharacteristic*, NimBLEConnInfo& info, uint16_t value) override;

};
} // namespace mesh
