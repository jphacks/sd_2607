#include <Arduino.h>
#include <M5Unified.h>
#include <esp_system.h>

#include "MeshBle.h"
#include "MeshRelay.h"
#include "MeshGateway.h"
#include "MeshSpeech.h"
#include "MessagePreview.h"

namespace {
mesh::Relay relay;
mesh::Ble ble;
mesh::Speech speech;
bool meshReady = false;
bool speechReady = false;
constexpr int PREVIEW_HEIGHT = 56;
constexpr int STATUS_HEIGHT = 14;

void note(const std::string& text);
void showControls() {
    const int cursorX = M5.Display.getCursorX();
    const int cursorY = M5.Display.getCursorY();
    M5.Display.setTextScroll(false);
    M5.Display.setScrollRect(0, 0, M5.Display.width(), PREVIEW_HEIGHT);
    M5.Display.setClipRect(0, 0, M5.Display.width(), STATUS_HEIGHT);
    M5.Display.fillRect(0, 0, M5.Display.width(), STATUS_HEIGHT, TFT_BLACK);
    M5.Display.setFont(nullptr);
    M5.Display.setTextSize(1);
    M5.Display.setCursor(0, 0);
    M5.Display.printf("RX/TX:%s  AUDIO:%s", meshReady ? (ble.enabled() ? "ON" : "OFF") : "ERR",
                      speechReady ? (speech.muted() ? "MUTE" : "ON") : "ERR");
    M5.Display.clearClipRect();
    M5.Display.setTextSize(2);
    M5.Display.setScrollRect(0, PREVIEW_HEIGHT, M5.Display.width(), M5.Display.height() - PREVIEW_HEIGHT);
    M5.Display.setTextScroll(true);
    M5.Display.setCursor(cursorX, cursorY);
}

void showMessage(const mesh::BundleInfo& message) {
    // Control events (e.g. PLC genesis JSON) still relay, but are not notices.
    if (message.kind != 1) return;
    const int cursorX = M5.Display.getCursorX();
    const int cursorY = M5.Display.getCursorY();
    M5.Display.setTextScroll(false);
    M5.Display.setScrollRect(0, 0, M5.Display.width(), PREVIEW_HEIGHT);
    M5.Display.setClipRect(0, 0, M5.Display.width(), PREVIEW_HEIGHT);
    M5.Display.fillRect(0, 0, M5.Display.width(), PREVIEW_HEIGHT, TFT_BLACK);
    M5.Display.setFont(&fonts::lgfxJapanGothic_12);
    M5.Display.setTextSize(1);
    M5.Display.setCursor(0, STATUS_HEIGHT);
    M5.Display.printf("RX %.8s\n", message.id.c_str());
    M5.Display.println(mesh::messagePreview(message.text).c_str());
    M5.Display.clearClipRect();
    M5.Display.drawFastHLine(0, PREVIEW_HEIGHT - 1, M5.Display.width(), TFT_DARKGREY);
    M5.Display.setFont(nullptr);
    M5.Display.setTextSize(2);
    M5.Display.setScrollRect(0, PREVIEW_HEIGHT, M5.Display.width(), M5.Display.height() - PREVIEW_HEIGHT);
    M5.Display.setTextScroll(true);
    M5.Display.setCursor(cursorX, cursorY);
    showControls();
    Serial.printf("Message preview updated id=%.8s\n", message.id.c_str());
    if (!message.certifiedImporter.empty())
        note("Importer checked at ingress: " + message.certifiedImporter + " priority=" + std::to_string(message.effectivePriority));
    if (speechReady && !speech.muted()) speech.enqueue(message.id, message.text);
}

void note(const std::string& text) {
    Serial.printf("mesh: %s\n", text.c_str());
    M5.Display.println(text.c_str());
}
#ifdef MESH_TEST_DIAGNOSTICS
void testCommands() {
    static std::string line;
    static bool overflow = false;
    for (unsigned count = 0; count < 64 && Serial.available(); ++count) {
        const char c = static_cast<char>(Serial.read());
        if (c == '\r') continue;
        if (c != '\n') {
            if (line.size() < 160) line += c;
            else overflow = true;
            continue;
        }
        bool ok = false;
        if (!overflow && line == "peers *") ok = relay.setAllowedPeers({});
        else if (!overflow && line.compare(0, 6, "peers ") == 0) {
            std::vector<std::string> nodes;
            for (size_t at = 6; at < line.size();) {
                const size_t end = line.find(' ', at);
                nodes.push_back(line.substr(at, end == std::string::npos ? end : end - at));
                if (end == std::string::npos) break;
                at = end + 1;
            }
            if (!nodes.empty()) ok = relay.setAllowedPeers(nodes);
        }
        note(ok ? "TEST command accepted" : "TEST command rejected");
        line.clear(); overflow = false;
    }
}
#endif
}

void setup() {
    Serial.begin(115200);
    auto config = M5.config();
    M5.begin(config);
    M5.Speaker.setVolume(96);
    Serial.printf("Speaker ready=%d\n", M5.Speaker.begin());

    M5.Display.setTextSize(1);
    M5.Display.setCursor(0, STATUS_HEIGHT);
    M5.Display.println("Waiting for message");
    M5.Display.setTextSize(2);
    M5.Display.drawFastHLine(0, PREVIEW_HEIGHT - 1, M5.Display.width(), TFT_DARKGREY);
    M5.Display.setScrollRect(0, PREVIEW_HEIGHT, M5.Display.width(), M5.Display.height() - PREVIEW_HEIGHT);
    M5.Display.setTextScroll(true);
    M5.Display.setCursor(0, PREVIEW_HEIGHT);
    M5.Display.println("ESP32 ready");
    Serial.printf("ESP32 ready chip=%s flash=%u heap=%u\n",
                  ESP.getChipModel(), ESP.getFlashChipSize(), ESP.getFreeHeap());

#ifndef MESH_GATEWAY_BUILD
    speechReady = speech.begin();
    note(speechReady ? "TTS eval: A test / C mute" : "TTS unavailable; see README");
#else
    note("Gateway profile: TTS off to reserve TLS RAM");
#endif

    uint8_t random[8];
    esp_fill_random(random, sizeof(random));
#ifdef MESH_TEST_NODE_ID
    // Test-only stable node avoids changing the paired Android allowlist on reset.
    for (size_t i = 0; i < sizeof(random); ++i) random[i] = (MESH_TEST_NODE_ID >> (8 * (7 - i))) & 255;
#endif
    char node[17];
    for (size_t i = 0; i < sizeof(random); ++i) snprintf(node + i * 2, 3, "%02x", random[i]);
    Serial.printf("mesh node=%s profile=android-experimental-v1 peripheral-only\n", node);
    if (relay.begin(node, [](const uint8_t* bytes, size_t size) { return ble.send(bytes, size); }, note, showMessage)) {
        mesh::gatewayBegin(relay);
        meshReady = ble.begin(node);
    }
    note(meshReady ? "BLE relay ready" : "Relay unavailable; see README");
    showControls();
}

void loop() {
    M5.update();
#ifdef MESH_TEST_DIAGNOSTICS
    testCommands();
#endif

    if (M5.BtnA.wasPressed()) {
#ifdef MESH_GATEWAY_BUILD
        note(mesh::gatewayRequestIngress() ? "Priority download requested" : "Ingress disabled; see root/clock config");
#else
        const bool queued = speechReady && speech.test();
        note(queued ? "Speech test queued" : "Speech test unavailable");
#endif
    }
    if (M5.BtnB.wasPressed()) {
        if (meshReady) {
            ble.setEnabled(!ble.enabled(), relay);
            note(ble.enabled() ? "RX/TX enabled" : "RX/TX disabled");
        } else note("RX/TX unavailable");
        showControls();
    }

    if (M5.BtnC.wasPressed()) {
        const bool muted = speech.toggleMuted();
        note(muted ? "Audio muted" : "Audio enabled");
        showControls();
    }

    if (meshReady) ble.poll(relay);
    mesh::gatewayPoll(relay);

    static uint32_t lastHeartbeat = 0;
    const uint32_t now = millis();
    if (now - lastHeartbeat >= 5000) {
        lastHeartbeat = now;
        Serial.printf("alive uptime_ms=%lu heap=%u stored=%u stack_free=%u radio=%u muted=%u\n",
                      static_cast<unsigned long>(now), ESP.getFreeHeap(),
                      static_cast<unsigned>(relay.storedCount()), uxTaskGetStackHighWaterMark(nullptr),
                                            unsigned(meshReady && ble.enabled()), unsigned(speech.muted()));
    }
    delay(10);
}
