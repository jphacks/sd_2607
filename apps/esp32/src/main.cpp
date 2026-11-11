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
// Status line: radio/audio switches, link state and stored (live = offered to peers) counts.
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
    M5.Display.printf("RX/TX:%s AUDIO:%s %s %u/%u", meshReady ? (ble.enabled() ? "ON" : "OFF") : "ERR",
                      speechReady ? (speech.muted() ? "MUTE" : "ON") : "ERR",
                      relay.sessionActive() ? "LINK" : "    ",
                      unsigned(relay.candidateCount()), unsigned(relay.storedCount()));
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
    M5.Display.printf("RX %.8s  author %.8s  hop %u\n", message.id.c_str(), message.pubkey.c_str(), unsigned(message.hop));
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
    if (speechReady && !speech.muted()) speech.enqueue(message.id, message.text);
}

void note(const std::string& text) {
    Serial.printf("mesh: %s\n", text.c_str());
    M5.Display.println(text.c_str());
}
#if defined(MESH_TEST_DIAGNOSTICS) || defined(MESH_GATEWAY_BUILD)
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
        if (!overflow && line == "status") {
            Serial.printf("status heap=%u stored=%u live=%u link=%u radio=%u speech_ready=%u muted=%u\n", ESP.getFreeHeap(), unsigned(relay.storedCount()), unsigned(relay.candidateCount()), unsigned(relay.sessionActive()), unsigned(meshReady && ble.enabled()), unsigned(speechReady), unsigned(speech.muted()));
            ok = true;
        }
#ifdef MESH_GATEWAY_BUILD
        else if (!overflow && (line == "audio on" || line == "audio off")) {
            if (speech.muted() != (line == "audio off")) speech.toggleMuted();
            showControls(); ok = true;
        }
#endif
        note(ok ? "UART command accepted" : "UART command rejected");
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

#if !defined(MESH_GATEWAY_BUILD) || defined(MESH_GATEWAY_TTS)
    speechReady = speech.begin();
#ifdef MESH_GATEWAY_TTS
    note(speechReady ? "Gateway + TTS eval: A test / C mute" : "TTS unavailable; see README");
#else
    note(speechReady ? "TTS eval: A test / C mute" : "TTS unavailable; see README");
#endif
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
    Serial.printf("mesh node=%s profile=mosimo-mesh-v1 peripheral-only\n", node);
    if (relay.begin([](const uint8_t* bytes, size_t size) { return ble.send(bytes, size); }, note, showMessage)) {
        Serial.printf("memory before_gateway=%u\n", ESP.getFreeHeap());
        mesh::gatewayBegin(relay);
        Serial.printf("memory before_ble=%u\n", ESP.getFreeHeap());
        meshReady = ble.begin(node);
        Serial.printf("memory after_ble=%u max_alloc=%u min_heap=%u\n", ESP.getFreeHeap(), ESP.getMaxAllocHeap(), ESP.getMinFreeHeap());
    }
    note(meshReady ? "BLE relay ready" : "Relay unavailable; see README");
    showControls();
}

void loop() {
    M5.update();
#if defined(MESH_TEST_DIAGNOSTICS) || defined(MESH_GATEWAY_BUILD)
    testCommands();
#endif

    if (M5.BtnA.wasPressed()) {
        const bool queued = speechReady && speech.test();
        note(queued ? "Speech test queued" : "Speech test unavailable");
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

    mesh::gatewayPoll(relay, &ble);
#ifdef MESH_GATEWAY_TTS
    // The worker starts SDK work only after gatewayPoll has quiesced BLE.
    if (meshReady && !mesh::gatewayNetworkBusy() && !mesh::Speech::resourcesBusy()) ble.poll(relay);
#else
    if (meshReady) ble.poll(relay);
#endif

    static uint32_t lastHeartbeat = 0, lastStatus = 0;
    static bool shownLink = false;
    static size_t shownLive = 0, shownStored = 0;
    const uint32_t now = millis();
    if (meshReady && now - lastStatus >= 1000) {
        lastStatus = now;
        const bool link = relay.sessionActive();
        const size_t live = relay.candidateCount(), stored = relay.storedCount();
        if (link != shownLink || live != shownLive || stored != shownStored) {
            shownLink = link; shownLive = live; shownStored = stored;
            showControls();
        }
    }
    if (now - lastHeartbeat >= 5000) {
        lastHeartbeat = now;
        Serial.printf("alive uptime_ms=%lu heap=%u stored=%u live=%u stack_free=%u radio=%u muted=%u\n",
                      static_cast<unsigned long>(now), ESP.getFreeHeap(),
                      static_cast<unsigned>(relay.storedCount()), static_cast<unsigned>(relay.candidateCount()),
                      uxTaskGetStackHighWaterMark(nullptr),
                                            unsigned(meshReady && ble.enabled()), unsigned(speech.muted()));
    }
    delay(10);
}
