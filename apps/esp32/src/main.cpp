#include <Arduino.h>
#include <M5Unified.h>
#include <esp_system.h>

#include "MeshBle.h"
#include "MeshRelay.h"
#include "MeshSpeech.h"
#include "MessagePreview.h"

namespace {
mesh::Relay relay;
mesh::Ble ble;
mesh::Speech speech;
bool meshReady = false;
bool speechReady = false;
constexpr int PREVIEW_HEIGHT = 56;

void showMessage(const mesh::BundleInfo& message) {
    const int cursorX = M5.Display.getCursorX();
    const int cursorY = M5.Display.getCursorY();
    M5.Display.setTextScroll(false);
    M5.Display.setScrollRect(0, 0, M5.Display.width(), PREVIEW_HEIGHT);
    M5.Display.setClipRect(0, 0, M5.Display.width(), PREVIEW_HEIGHT);
    M5.Display.fillRect(0, 0, M5.Display.width(), PREVIEW_HEIGHT, TFT_BLACK);
    M5.Display.setFont(&fonts::lgfxJapanGothic_12);
    M5.Display.setTextSize(1);
    M5.Display.setCursor(0, 0);
    M5.Display.printf("RX %.8s\n", message.id.c_str());
    M5.Display.println(mesh::messagePreview(message.text).c_str());
    M5.Display.clearClipRect();
    M5.Display.drawFastHLine(0, PREVIEW_HEIGHT - 1, M5.Display.width(), TFT_DARKGREY);
    M5.Display.setFont(nullptr);
    M5.Display.setTextSize(2);
    M5.Display.setScrollRect(0, PREVIEW_HEIGHT, M5.Display.width(), M5.Display.height() - PREVIEW_HEIGHT);
    M5.Display.setTextScroll(true);
    M5.Display.setCursor(cursorX, cursorY);
    Serial.printf("Message preview updated id=%.8s\n", message.id.c_str());
    if (speechReady) speech.enqueue(message.id, message.text);
}

void note(const std::string& text) {
    Serial.printf("mesh: %s\n", text.c_str());
    M5.Display.println(text.c_str());
}
}

void setup() {
    Serial.begin(115200);
    auto config = M5.config();
    M5.begin(config);
    M5.Speaker.setVolume(96);
    Serial.printf("Speaker ready=%d\n", M5.Speaker.begin());

    M5.Display.setTextSize(2);
    M5.Display.println("Waiting for message");
    M5.Display.drawFastHLine(0, PREVIEW_HEIGHT - 1, M5.Display.width(), TFT_DARKGREY);
    M5.Display.setScrollRect(0, PREVIEW_HEIGHT, M5.Display.width(), M5.Display.height() - PREVIEW_HEIGHT);
    M5.Display.setTextScroll(true);
    M5.Display.setCursor(0, PREVIEW_HEIGHT);
    M5.Display.println("ESP32 ready");
    Serial.printf("ESP32 ready chip=%s flash=%u heap=%u\n",
                  ESP.getChipModel(), ESP.getFlashChipSize(), ESP.getFreeHeap());

    speechReady = speech.begin();
    note(speechReady ? "TTS eval: A test / C mute" : "TTS unavailable; see README");

    uint8_t random[8];
    esp_fill_random(random, sizeof(random));
    char node[17];
    for (size_t i = 0; i < sizeof(random); ++i) snprintf(node + i * 2, 3, "%02x", random[i]);
    Serial.printf("mesh node=%s profile=android-experimental-v1 peripheral-only\n", node);
    if (relay.begin(node, [](const uint8_t* bytes, size_t size) { return ble.send(bytes, size); }, note, showMessage)) {
        meshReady = ble.begin(node);
    }
    note(meshReady ? "BLE relay ready" : "Relay unavailable; see README");
}

void loop() {
    M5.update();

    if (M5.BtnA.wasPressed()) {
        const bool queued = speechReady && speech.test();
        note(queued ? "Speech test queued" : "Speech test unavailable");
    }
    if (M5.BtnB.wasPressed()) {
        const bool playing = M5.Speaker.tone(880, 250, 0);
        M5.Display.println(playing ? "Button B: beep" : "Speaker error");
        Serial.printf("Button B: tone started=%d\n", playing);
    }

    if (M5.BtnC.wasPressed()) {
        const bool muted = speech.toggleMuted();
        note(muted ? "Speech muted / stopped" : "Speech enabled");
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
