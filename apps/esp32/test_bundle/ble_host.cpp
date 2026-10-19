#include "MeshBle.h"
#include "ble_host_mocks.h"
#include <cstdlib>
#include <iostream>
#include <limits>

uint32_t fakeMs = 0;
MockSerial Serial;
std::vector<std::unique_ptr<MockQueue>> mockQueues;
unsigned queueResets = 0;
NimBLEAdvertising mockAdvertising;
NimBLEServer mockServer;
static unsigned checks = 0;
#define CHECK(x) do { ++checks; if (!(x)) { std::cerr << "FAIL " << __LINE__ << ": " << #x << '\n'; std::abort(); } } while (0)

static std::vector<uint8_t> frame(const std::vector<uint8_t>& bytes) {
    const uint32_t n = static_cast<uint32_t>(bytes.size());
    std::vector<uint8_t> out{uint8_t(n >> 24), uint8_t(n >> 16), uint8_t(n >> 8), uint8_t(n)};
    out.insert(out.end(), bytes.begin(), bytes.end());
    return out;
}
struct Env {
    mesh::Ble ble;
    mesh::Relay relay;
    Env() {
        mockServer = NimBLEServer(); mockAdvertising = NimBLEAdvertising();
        mockQueues.clear(); queueResets = 0; fakeMs = 100;
        CHECK(ble.begin("0123456789abcdef"));
        CHECK(mockAdvertising.active && mockAdvertising.starts == 1);
        CHECK(!mockServer.automaticAdvertising);
    }
    void poll(uint32_t advance = 0) { fakeMs += advance; ble.poll(relay); }
    void connect(uint16_t handle = 7, uint16_t mtu = 23) {
        mockServer.up(handle, mtu); mockServer.subscribe(); poll();
        CHECK(relay.connections == 1);
    }
    NimBLECharacteristic& tx() { return mockServer.service.tx; }
    std::vector<uint8_t> sent() {
        std::vector<uint8_t> bytes;
        for (const auto& n : tx().attempts) if (n.accepted) bytes.insert(bytes.end(), n.bytes.begin(), n.bytes.end());
        return bytes;
    }
    void finishDisconnect() {
        CHECK(mockServer.disconnectPending);
        CHECK(!mockAdvertising.active);
        const unsigned starts = mockAdvertising.starts;
        poll(); CHECK(mockAdvertising.starts == starts); // Wait for physical Down.
        mockServer.down(); poll();
        CHECK(mockAdvertising.active && mockAdvertising.starts == starts + 1);
        CHECK(mockQueues.back()->items.empty());
    }
};

static void notifications() {
    Env e; e.connect(7, 23);
    std::vector<uint8_t> payload(53);
    for (size_t i = 0; i < payload.size(); ++i) payload[i] = uint8_t(i);
    CHECK(e.ble.send(payload.data(), payload.size()));
    e.poll(); CHECK(e.tx().attempts.size() == 1);
    // Fake status reports handle 0 synchronously; neither duplicate nor wrong-handle progress.
    for (unsigned i = 0; i < 5; ++i) e.poll();
    CHECK(e.tx().attempts.size() == 1);
    e.poll(9); CHECK(e.tx().attempts.size() == 1);
    e.poll(1); CHECK(e.tx().attempts.size() == 2);
    e.poll(10); CHECK(e.tx().attempts.size() == 3);
    e.poll(100); CHECK(e.tx().attempts.size() == 3);
    CHECK(e.sent() == frame(payload));
    CHECK(e.relay.frames.empty()); // notify acceptance never becomes an application ACK/frame.
    for (const auto& n : e.tx().attempts) { CHECK(n.handle == 7); CHECK(n.bytes.size() <= 20); }
}

static void retries() {
    Env e; e.connect();
    std::vector<uint8_t> payload(30, 42);
    CHECK(e.ble.send(payload.data(), payload.size()));
    e.tx().accept = false;
    e.poll(); e.poll(9); CHECK(e.tx().attempts.size() == 1);
    e.poll(1); CHECK(e.tx().attempts.size() == 2);
    CHECK(e.tx().attempts[0].bytes == e.tx().attempts[1].bytes);
    CHECK(mockServer.disconnects.empty());
    e.tx().accept = true; e.poll(10); e.poll(10);
    CHECK(e.sent() == frame(payload));
    CHECK(mockServer.disconnects.empty());
    // Failed queue attempts time out, including across millis() wraparound.
    fakeMs = std::numeric_limits<uint32_t>::max() - 100;
    CHECK(e.ble.send(payload.data(), payload.size())); e.tx().accept = false; e.poll();
    e.poll(9999); CHECK(mockServer.disconnects.empty());
    e.poll(1); CHECK(!mockServer.disconnects.empty());
    CHECK(mockServer.disconnects.back() == 7);
    e.finishDisconnect();
}

static void fragmentation() {
    Env e; e.connect(11, 517);
    std::vector<uint8_t> payload(mesh::MAX_BUNDLE_BYTES);
    for (size_t i = 0; i < payload.size(); ++i) payload[i] = uint8_t(i * 13);
    CHECK(e.ble.send(payload.data(), payload.size()));
    CHECK(!e.ble.send(payload.data(), payload.size())); // 48000-byte budget includes prefixes.
    for (unsigned i = 0; i < 60; ++i) e.poll(10);
    CHECK(e.sent() == frame(payload));
    CHECK(e.tx().attempts.size() == 47);
    for (const auto& n : e.tx().attempts) CHECK(n.bytes.size() <= 512);
    // RX prefix splits at every byte, full 24000-byte payload, then coalesced frames.
    auto wire = frame(payload);
    for (size_t at = 0; at < wire.size();) {
        const size_t count = at < 4 ? 1 : std::min<size_t>(512, wire.size() - at);
        mockServer.write({wire.begin() + at, wire.begin() + at + count}); e.poll(); at += count;
    }
    CHECK(e.relay.frames.size() == 1 && e.relay.frames[0] == payload);
    auto pair = frame({1}); auto second = frame({2, 3}); pair.insert(pair.end(), second.begin(), second.end());
    mockServer.write(pair); e.poll();
    CHECK(e.relay.frames.size() == 3);
    CHECK(e.relay.frames[1] == std::vector<uint8_t>({1}));
    CHECK(e.relay.frames[2] == std::vector<uint8_t>({2, 3}));
    CHECK(!e.ble.send(nullptr, 1));
    CHECK(!e.ble.send(payload.data(), 0));
    CHECK(!e.ble.send(payload.data(), mesh::MAX_BUNDLE_BYTES + 1));
    for (unsigned i = 0; i < 8; ++i) CHECK(e.ble.send(payload.data(), 1));
    CHECK(!e.ble.send(payload.data(), 1));
}

static void overflowDuringReceive() {
    Env e; e.connect();
    const uint8_t pending = 99;
    CHECK(e.ble.send(&pending, 1));
    // Two frames in the SAME feed call; the second must not reach Relay after poisoning.
    auto pair = frame({1}); auto second = frame({2}); pair.insert(pair.end(), second.begin(), second.end());
    e.relay.duringReceive = [&] {
        for (unsigned i = 0; i < 33; ++i) mockServer.write(frame({3}));
    };
    mockServer.write(pair); e.poll();
    CHECK(e.relay.frames.size() == 1);
    CHECK(e.tx().attempts.empty());
    CHECK(mockServer.disconnectPending && mockServer.disconnects.back() == 7);
    CHECK(queueResets == 0); // Still physically connected: do NOT clear poison/queue yet.
    CHECK(!e.ble.send(&pending, 1));
    e.poll(); CHECK(e.relay.frames.size() == 1 && queueResets == 0);
    // Overflow poisoned the queue; Down need not fit for recovery to complete.
    mockServer.down();
    // Connect immediately when advertising restarts, to catch reset-after-Up races.
    mockAdvertising.onStart = [&] { mockServer.up(13); mockServer.subscribe(); };
    e.poll(); CHECK(queueResets == 1);
    CHECK(mockServer.alive && !mockAdvertising.active);
    e.relay.duringReceive = nullptr;
    e.poll(); CHECK(e.relay.connections == 2);
    CHECK(e.tx().attempts.empty()); // Previous session's queued TX was discarded.
    mockServer.write(frame({4})); e.poll();
    CHECK(e.relay.frames.size() == 2 && e.relay.frames.back() == std::vector<uint8_t>({4}));
    CHECK(e.ble.send(&pending, 1)); e.poll(10);
    CHECK(e.tx().attempts.back().handle == 13);
    CHECK(e.sent() == frame({pending}));
}

static void lifecycleLoss() {
    Env e;
    // Lose Up itself: recovery must still disconnect the actual physical handle.
    for (unsigned i = 0; i < 32; ++i) mockServer.mtu(23);
    mockServer.up(21); mockServer.subscribe(); e.poll();
    CHECK(e.relay.connections == 0);
    CHECK(mockServer.disconnects.back() == 21 && queueResets == 0);
    mockServer.down(); e.poll(); CHECK(queueResets == 1 && mockAdvertising.active);
    mockServer.up(22); mockServer.subscribe(); e.poll(); CHECK(e.relay.connections == 1);
    // Physical Down while the receive callback is blocked, with a full/poisoned queue.
    e.relay.duringReceive = [&] {
        for (unsigned i = 0; i < 33; ++i) mockServer.write(frame({8}));
        mockServer.down();
    };
    mockServer.write(frame({7})); e.poll();
    CHECK(e.relay.frames.size() == 1);
    CHECK(queueResets == 2 && mockAdvertising.active);
}

static void invalidFramesAndDisconnects() {
    for (const auto& bad : std::vector<std::vector<uint8_t>>{{0, 0, 0, 0}, {0, 0, 0x5d, 0xc1}, std::vector<uint8_t>(513, 1)}) {
        Env e; e.connect(); mockServer.write(bad); e.poll();
        CHECK(e.relay.frames.empty()); CHECK(mockServer.disconnectPending);
        e.finishDisconnect();
    }
    {
        Env e; e.connect(); mockServer.write({0, 0}); e.poll();
        e.poll(15001); CHECK(mockServer.disconnectPending); e.finishDisconnect();
        mockServer.up(31); mockServer.subscribe(); e.poll();
        mockServer.write(frame({6})); e.poll();
        CHECK(e.relay.frames.size() == 1 && e.relay.frames[0] == std::vector<uint8_t>({6}));
    }
    {
        Env e; e.connect(); mockServer.subscribe(0); e.poll();
        // Logical handle was cleared by drop, but Down must still restart advertising.
        e.finishDisconnect();
    }
    {
        Env e; e.connect(); mockServer.down(); e.poll();
        CHECK(mockAdvertising.active && queueResets == 1);
    }
    {
        Env e; e.connect(); mockServer.immediateDisconnect = true;
        mockServer.write({0, 0, 0, 0}); e.poll();
        CHECK(!mockServer.alive && mockAdvertising.active && queueResets == 1);
    }
}

static void transitionPoison() {
    {
        Env e; e.connect();
        e.relay.duringTick = [&] { for (unsigned i = 0; i < 33; ++i) mockServer.write(frame({1})); };
        const uint8_t byte = 1; CHECK(e.ble.send(&byte, 1)); e.poll();
        CHECK(e.tx().attempts.empty() && mockServer.disconnectPending);
        CHECK(e.relay.frames.empty());
    }
    {
        Env e; e.connect();
        e.tx().duringNotify = [&] { for (unsigned i = 0; i < 33; ++i) mockServer.write(frame({1})); };
        std::vector<uint8_t> bytes(30, 1); CHECK(e.ble.send(bytes.data(), bytes.size())); e.poll();
        CHECK(e.tx().attempts.size() == 1 && mockServer.disconnectPending);
        CHECK(e.relay.frames.empty());
    }
}

static void idleDisable() {
    Env e;
    const uint8_t byte = 42;
    CHECK(e.ble.enabled());
    e.ble.setEnabled(false, e.relay);
    CHECK(!e.ble.enabled() && !mockAdvertising.active);
    CHECK(mockAdvertising.stops == 1 && mockAdvertising.starts == 1);
    CHECK(queueResets == 1 && mockQueues.back()->items.empty());
    CHECK(mockServer.disconnects.empty());
    const unsigned ticks = e.relay.ticks, drops = e.relay.disconnections;
    e.ble.setEnabled(false, e.relay);
    for (unsigned i = 0; i < 3; ++i) {
        CHECK(!e.ble.send(&byte, 1));
        e.poll(30001);
    }
    CHECK(e.relay.ticks == ticks && e.relay.disconnections == drops);
    CHECK(e.relay.frames.empty() && e.tx().attempts.empty());
    CHECK(!mockAdvertising.active && mockAdvertising.starts == 1);
    e.ble.setEnabled(true, e.relay);
    CHECK(e.ble.enabled() && mockAdvertising.active && mockAdvertising.starts == 2);
    e.ble.setEnabled(true, e.relay);
    CHECK(mockAdvertising.starts == 2);
    CHECK(!e.ble.send(&byte, 1)); // Enabled is not yet subscribed/connected.
}

static void disableDiscardsSession() {
    // Exercise both a split length prefix and a partially received body.
    for (size_t partial : {size_t(2), size_t(6)}) {
        Env e; e.connect();
        const std::vector<uint8_t> old(53, 0xa5);
        const auto wire = frame(old);
        CHECK(e.ble.send(old.data(), old.size()));
        CHECK(e.ble.send(old.data(), old.size())); // Also discard a whole queued TX frame.
        mockServer.write({wire.begin(), wire.begin() + partial}); e.poll();
        CHECK(e.tx().attempts.size() == 1 && e.sent().size() == 20);
        CHECK(e.relay.frames.empty());
        // These events have not reached the parser when the user disables radio.
        mockServer.write({wire.begin() + partial, wire.end()});
        mockServer.write(frame({0xde, 0xad}));
        const unsigned ticks = e.relay.ticks;
        e.ble.setEnabled(false, e.relay);
        CHECK(!e.ble.enabled() && !mockAdvertising.active);
        CHECK(mockServer.disconnectPending && mockServer.disconnects.back() == 7);
        CHECK(queueResets == 0); // Do not reset before the physical callback is quiescent.
        for (unsigned i = 0; i < 3; ++i) {
            CHECK(!e.ble.send(old.data(), old.size()));
            mockServer.write(frame({0xba, 0xd0}));
            e.poll(30001);
        }
        CHECK(e.relay.ticks == ticks && e.relay.frames.empty());
        CHECK(e.tx().attempts.size() == 1 && mockAdvertising.starts == 1);
        mockServer.down(); e.poll();
        CHECK(queueResets == 1 && mockQueues.back()->items.empty());
        e.poll(30001);
        CHECK(!e.ble.enabled() && !mockAdvertising.active && mockAdvertising.starts == 1);
        CHECK(e.relay.ticks == ticks && e.relay.frames.empty());
        CHECK(!e.ble.send(old.data(), old.size()));

        e.tx().attempts.clear();
        e.ble.setEnabled(true, e.relay);
        CHECK(mockAdvertising.active && mockAdvertising.starts == 2);
        mockServer.up(41); mockServer.subscribe(); e.poll();
        CHECK(e.relay.connections == 2 && e.tx().attempts.empty());
        const std::vector<uint8_t> fresh{9, 8, 7};
        const auto freshWire = frame(fresh);
        for (uint8_t byte : freshWire) { mockServer.write({byte}); e.poll(); }
        CHECK(e.relay.frames == std::vector<std::vector<uint8_t>>({fresh}));
        CHECK(e.ble.send(fresh.data(), fresh.size()));
        for (unsigned i = 0; i < 10; ++i) e.poll(10);
        CHECK(e.sent() == freshWire); // Fresh prefix, no old tail or queued frame.
        CHECK(e.tx().attempts.size() == 1 && e.tx().attempts[0].handle == 41);
    }
}

static void disableDuringDisconnectAndRapidToggle() {
    {
        Env e; e.connect();
        mockServer.down(); // Ordinary disconnect queued just before the button press.
        e.ble.setEnabled(false, e.relay); e.poll();
        CHECK(!e.ble.enabled() && !mockAdvertising.active);
        CHECK(mockAdvertising.starts == 1 && queueResets == 1);
        CHECK(mockQueues.back()->items.empty() && mockServer.disconnects.empty());
        const unsigned ticks = e.relay.ticks;
        e.poll(60001);
        CHECK(e.relay.ticks == ticks && !mockAdvertising.active);
        e.ble.setEnabled(true, e.relay);
        mockServer.up(42); mockServer.subscribe(); e.poll();
        CHECK(e.ble.enabled() && e.relay.connections == 2);
    }
    {
        Env e; e.connect();
        const uint8_t old = 0xee;
        CHECK(e.ble.send(&old, 1));
        mockServer.write({0, 0}); e.poll();
        e.tx().attempts.clear();
        mockServer.write(frame({old})); // Stale callback event must be discarded.
        e.ble.setEnabled(false, e.relay);
        e.ble.setEnabled(true, e.relay);
        CHECK(e.ble.enabled() && mockServer.disconnectPending);
        CHECK(!mockAdvertising.active && queueResets == 0);
        CHECK(!e.ble.send(&old, 1));
        const unsigned ticks = e.relay.ticks;
        e.poll(60001);
        CHECK(mockAdvertising.starts == 1 && queueResets == 0);
        CHECK(e.relay.ticks == ticks && e.relay.frames.empty() && e.tx().attempts.empty());
        // A new connection can arrive synchronously when advertising resumes.
        mockAdvertising.onStart = [&] { mockServer.up(43); mockServer.subscribe(); };
        mockServer.down(); e.poll();
        CHECK(queueResets == 1 && mockAdvertising.starts == 2);
        CHECK(mockServer.alive && !mockAdvertising.active);
        e.poll();
        CHECK(e.relay.connections == 2 && e.tx().attempts.empty());
        mockServer.write(frame({1, 2, 3})); e.poll();
        CHECK(e.relay.frames == std::vector<std::vector<uint8_t>>({{1, 2, 3}}));
        const uint8_t fresh = 4;
        CHECK(e.ble.send(&fresh, 1)); e.poll(10);
        CHECK(e.sent() == frame({fresh}) && e.tx().attempts.back().handle == 43);
    }
}

static void busyReceiveYieldsToControls() {
    Env e; e.connect();
    e.relay.duringReceive = [&] {
        if (e.relay.frames.size() < 40) mockServer.write(frame({1}));
    };
    mockServer.write(frame({1}));
    e.poll();
    CHECK(!e.relay.frames.empty() && e.relay.frames.size() <= 4);
    for (unsigned i = 0; i < 40 && e.relay.frames.size() < 40; ++i) e.poll();
    CHECK(e.relay.frames.size() == 40);
    CHECK(!mockServer.disconnectPending);
}

static void networkPause() {
    for (bool userOff : {false, true}) {
        Env e; e.connect();
        CHECK(!e.ble.pauseForNetwork(true, e.relay));
        CHECK(e.ble.enabled() && !mockAdvertising.active && mockServer.disconnectPending);
        CHECK(!mockQueues.empty()); // The final callback still owns this queue.
        mockServer.down();
        CHECK(e.ble.pauseForNetwork(true, e.relay));
#ifdef MESH_GATEWAY_TTS
        CHECK(mockQueues.empty());
#endif
        const uint8_t byte = 1;
        CHECK(!e.ble.send(&byte, 1));
        e.poll(); CHECK(!mockAdvertising.active);
        if (userOff) e.ble.setEnabled(false, e.relay);
        CHECK(e.ble.pauseForNetwork(false, e.relay));
        CHECK(!mockQueues.empty() && mockQueues.back()->capacity == 32);
        CHECK(e.ble.enabled() == !userOff && mockAdvertising.active == !userOff);
        if (userOff) e.ble.setEnabled(true, e.relay);
        CHECK(mockAdvertising.active);
    }
}
int main() {
    networkPause();
    busyReceiveYieldsToControls();
    notifications(); retries(); fragmentation(); overflowDuringReceive();
    lifecycleLoss(); invalidFramesAndDisconnects(); transitionPoison();
    idleDisable(); disableDiscardsSession(); disableDuringDisconnectAndRapidToggle();
    std::cout << "PASS MeshBle: " << checks << " assertions (production transport, mock NimBLE/FreeRTOS/Relay)\n";
}
