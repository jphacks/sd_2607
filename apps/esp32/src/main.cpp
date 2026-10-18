#include <Arduino.h>
#include <M5Unified.h>
#include <esp_system.h>

#include "MeshBle.h"
#include "MeshRelay.h"

namespace {
mesh::Relay relay;
mesh::Ble ble;
bool meshReady = false;

void note(const std::string& text) {
    Serial.printf("mesh: %s\n", text.c_str());
    M5.Display.println(text.c_str());
}
}

void setup() {
    Serial.begin(115200);
    auto config = M5.config();
    M5.begin(config);
    M5.Speaker.setVolume(64);
    Serial.printf("Speaker ready=%d\n", M5.Speaker.begin());

    M5.Display.setTextSize(2);
    M5.Display.println("ESP32 ready");
    Serial.printf("ESP32 ready chip=%s flash=%u heap=%u\n",
                  ESP.getChipModel(), ESP.getFlashChipSize(), ESP.getFreeHeap());

    uint8_t random[8];
    esp_fill_random(random, sizeof(random));
    char node[17];
    for (size_t i = 0; i < sizeof(random); ++i) snprintf(node + i * 2, 3, "%02x", random[i]);
    Serial.printf("mesh node=%s profile=android-experimental-v1 peripheral-only\n", node);
    if (relay.begin(node, [](const uint8_t* bytes, size_t size) { return ble.send(bytes, size); }, note)) {
        meshReady = ble.begin(node);
    }
    note(meshReady ? "BLE relay ready" : "Relay unavailable; see README");
}

void loop() {
    M5.update();

    if (M5.BtnA.wasPressed()) {
        M5.Display.println("Button A");
        Serial.println("Button A");
    }
    if (M5.BtnB.wasPressed()) {
        const bool playing = M5.Speaker.tone(880, 250);
        M5.Display.println(playing ? "Button B: beep" : "Speaker error");
        Serial.printf("Button B: tone started=%d\n", playing);
    }

    if (meshReady) ble.poll(relay);

    static uint32_t lastHeartbeat = 0;
    const uint32_t now = millis();
    if (now - lastHeartbeat >= 5000) {
        lastHeartbeat = now;
        Serial.printf("alive uptime_ms=%lu heap=%u stored=%u stack_free=%u\n",
                      static_cast<unsigned long>(now), ESP.getFreeHeap(),
                      static_cast<unsigned>(relay.storedCount()), uxTaskGetStackHighWaterMark(nullptr));
    }
    delay(10);
}
