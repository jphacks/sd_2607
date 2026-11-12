#include <Arduino.h>
#include <M5Unified.h>
#include <esp_system.h>

#include "MeshBle.h"
#include "MeshGateway.h"
#include "MeshRelay.h"
#include "Ui.h"

namespace {
mesh::Relay relay;
mesh::Ble ble;
ui::Ui screen;
bool meshReady = false;

void note(const std::string& text) {
    Serial.printf("mesh: %s\n", text.c_str());
    screen.addLog(text);
    // Report threshold reached (or a deletion of a stored post): the relay erased it,
    // so the screen must stop showing it too.
    if (text.rfind("stopped ", 0) == 0) screen.erasePost(text.substr(8, 8));
}

void accepted(const mesh::BundleInfo& message) {
    // delete §3: hide the author's own targets even if the relay no longer stores them.
    if (message.kind == 5) screen.erasePost(message.target, message.pubkey);
    // Control events (e.g. PLC genesis JSON) and moderation records still relay, but are not notices.
    if (message.kind != 1) return;
    screen.addPost(message);
    Serial.printf("Message preview updated id=%.8s\n", message.id.c_str());
}

#ifdef UI_DEBUG_COMMANDS
// Screen-only sample posts: never stored, relayed or counted by the relay.
void demo() {
    static const char* const texts[] = {
        "【訓練】避難所の体育館は開いています。毛布と飲料水があります。",
        "駅前の交差点で信号が止まっています。通行に注意してください。",
        "給水車が13時に市役所の駐車場へ来ます。容器を持参してください。長い文章が画面の幅を超えたときに、折り返しと省略記号が正しく表示されるかを確かめるための文です。",
    };
    static unsigned n = 0;
    mesh::BundleInfo info;
    char id[65];
    for (int i = 0; i < 64; ++i) id[i] = "0123456789abcdef"[(n * 7 + i * 13) & 15];
    id[64] = 0;
    info.id = id;
    info.pubkey = std::string(n % 2 ? "b7" : "3f") + std::string(id + 2);
    info.text = texts[n % 3];
    info.kind = 1;
    info.hop = n % 4;
    info.hopLimit = 8;
    info.createdAt = 1794550000 + n * 60;
    info.expiresAt = info.createdAt + 86400;
    ++n;
    screen.addPost(info);
}

// "screenshot", "tap X Y" or "demo", one per line.
ui::Action commands() {
    static std::string line;
    ui::Action action = ui::Action::None;
    while (Serial.available()) {
        const char c = static_cast<char>(Serial.read());
        if (c == '\r') continue;
        if (c != '\n') { if (line.size() < 32) line += c; continue; }
        int x = 0, y = 0;
        if (line == "screenshot") screen.screenshot(Serial);
        else if (sscanf(line.c_str(), "tap %d %d", &x, &y) == 2) action = screen.inject(x, y);
        else if (line == "demo") demo();
        line.clear();
    }
    return action;
}
#endif

ui::Stats stats() {
    ui::Stats s;
    s.meshReady = meshReady;
    s.bleOn = meshReady && ble.enabled();
    s.link = relay.sessionActive();
    s.stored = relay.storedCount();
    s.live = relay.candidateCount();
    s.heap = ESP.getFreeHeap();
    return s;
}
}

void setup() {
    Serial.begin(115200);
    auto config = M5.config();
    M5.begin(config);
    M5.Speaker.setVolume(64);
    Serial.printf("Speaker ready=%d\n", M5.Speaker.begin());

    uint8_t random[8];
    esp_fill_random(random, sizeof(random));
    char node[17];
    for (size_t i = 0; i < sizeof(random); ++i) snprintf(node + i * 2, 3, "%02x", random[i]);
    screen.begin(node);
    screen.update();
    Serial.printf("CoreS3 ready chip=%s flash=%u heap=%u psram=%u\n",
                  ESP.getChipModel(), ESP.getFlashChipSize(), ESP.getFreeHeap(), ESP.getFreePsram());
    note("CoreS3 touch UI ready (no TTS)");

    Serial.printf("mesh node=%s profile=mosimo-mesh-v1 peripheral-only\n", node);
    if (relay.begin([](const uint8_t* bytes, size_t size) { return ble.send(bytes, size); }, note, accepted)) {
        // Posts kept from before this reboot are shown as local information. They stay cold:
        // the relay still neither offers nor forwards them in this boot.
        size_t shown = 0;
        relay.forEachStored([&](const mesh::BundleInfo& info, bool) {
            if (info.kind == 1) { screen.addStored(info); ++shown; }
        });
        note("showing " + std::to_string(shown) + " stored posts from before reboot");
        mesh::gatewayBegin(relay);
        meshReady = ble.begin(node);
        Serial.printf("memory after_ble=%u max_alloc=%u min_heap=%u\n", ESP.getFreeHeap(), ESP.getMaxAllocHeap(), ESP.getMinFreeHeap());
    }
    note(meshReady ? "BLE relay ready" : "Relay unavailable; see README");
    screen.setStats(stats());
}

void loop() {
    M5.update();
    ui::Action action = screen.update();
#ifdef UI_DEBUG_COMMANDS
    if (action == ui::Action::None) action = commands();
#endif
    if (action == ui::Action::ToggleBle && meshReady) {
        ble.setEnabled(!ble.enabled(), relay);
        note(ble.enabled() ? "RX/TX enabled" : "RX/TX disabled");
    }

    mesh::gatewayPoll(relay, &ble);
    if (meshReady) ble.poll(relay);
    screen.setStats(stats());

    static uint32_t lastHeartbeat = 0;
    const uint32_t now = millis();
    if (now - lastHeartbeat >= 5000) {
        lastHeartbeat = now;
        Serial.printf("alive uptime_ms=%lu heap=%u psram=%u stored=%u live=%u stack_free=%u radio=%u batt_mv=%d\n",
                      static_cast<unsigned long>(now), ESP.getFreeHeap(), ESP.getFreePsram(),
                      static_cast<unsigned>(relay.storedCount()), static_cast<unsigned>(relay.candidateCount()),
                      uxTaskGetStackHighWaterMark(nullptr), unsigned(meshReady && ble.enabled()),
                      int(M5.Power.getBatteryVoltage()));
    }
    delay(5);
}
