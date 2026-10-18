// Compile the actual setup/loop/showMessage in this TU; only external boundaries are fake.
#include "MeshBundle.h"
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <functional>
#include <iostream>
#include <string>
#include <utility>
#include <vector>

static unsigned checks = 0;
#define CHECK(x) do { ++checks; if (!(x)) { std::cerr << "FAIL " << __LINE__ << ": " << #x << '\n'; std::abort(); } } while (0)
static uint32_t fakeMs = 0;
uint32_t millis() { return fakeMs; }
void delay(unsigned ms) { fakeMs += ms; }
unsigned uxTaskGetStackHighWaterMark(void*) { return 1024; }
void esp_fill_random(void* data, size_t size) {
    auto* bytes = static_cast<uint8_t*>(data);
    for (size_t i = 0; i < size; ++i) bytes[i] = static_cast<uint8_t>(i);
}
struct MockSerial {
    void begin(unsigned) {}
    template<class... Args> void printf(const char*, Args...) {}
} Serial;
struct MockESP {
    const char* getChipModel() { return "host"; }
    unsigned getFlashChipSize() { return 0; }
    unsigned getFreeHeap() { return 0; }
} ESP;
constexpr int TFT_BLACK = 0, TFT_DARKGREY = 1;
namespace fonts { const int lgfxJapanGothic_12 = 12; }
struct MockDisplay {
    int x = 0, y = 0, clipHeight = 0;
    std::vector<std::string> previews, headings, statuses;
    int width() const { return 320; }
    int height() const { return 240; }
    int getCursorX() const { return x; }
    int getCursorY() const { return y; }
    void setCursor(int newX, int newY) { x = newX; y = newY; }
    void setTextScroll(bool) {}
    void setScrollRect(int, int, int, int) {}
    void setClipRect(int, int, int, int height) { clipHeight = height; }
    void clearClipRect() { clipHeight = 0; }
    void fillRect(int, int, int, int, int) {}
    void setFont(const int*) {}
    void setTextSize(int) {}
    void drawFastHLine(int, int, int, int) {}
    void printf(const char* format, ...) {
        char text[256];
        va_list args;
        va_start(args, format);
        std::vsnprintf(text, sizeof(text), format, args);
        va_end(args);
        if (clipHeight == 14 && y == 0) statuses.emplace_back(text);
        if (clipHeight == 56 && y == 14) headings.emplace_back(text);
    }
    void println(const char* text) {
        if (clipHeight == 56 && y == 14) previews.emplace_back(text);
    }
};
struct MockButton {
    bool pressed = false;
    bool wasPressed() { const bool result = pressed; pressed = false; return result; }
};
struct MockM5 {
    MockDisplay Display;
    MockButton BtnA, BtnB, BtnC;
    struct MockSpeaker {
        void setVolume(unsigned) {}
        bool begin() { return true; }
    } Speaker;
    int config() { return 0; }
    void begin(int) {}
    void update() {}
} M5;

namespace mesh {
struct Speech {
    bool isMuted = false;
    unsigned begins = 0, tests = 0, toggles = 0;
    std::vector<std::pair<std::string, std::string>> enqueued;
    bool begin() { ++begins; return true; }
    bool test() { ++tests; return true; }
    // Deliberately record even when muted: main must skip enqueue itself.
    bool enqueue(const std::string& id, const std::string& text) {
        enqueued.emplace_back(id, text); return true;
    }
    bool toggleMuted() { ++toggles; isMuted = !isMuted; return isMuted; }
    bool muted() const { return isMuted; }
};
struct Relay {
    unsigned begins = 0, acceptedCalls = 0;
    std::string node;
    std::function<bool(const uint8_t*, size_t)> send;
    std::function<void(const BundleInfo&)> accepted;
    bool begin(const std::string& id, std::function<bool(const uint8_t*, size_t)> tx,
               std::function<void(const std::string&)>, std::function<void(const BundleInfo&)> callback) {
        ++begins; node = id; send = tx; accepted = callback; return true;
    }
    size_t storedCount() const { return acceptedCalls; }
};
struct Ble {
    bool isEnabled = true, pending = false;
    unsigned begins = 0, polls = 0, sends = 0;
    std::string node;
    std::vector<bool> enableCalls;
    BundleInfo nextAccepted;
    bool begin(const std::string& id) { ++begins; node = id; return true; }
    bool send(const uint8_t*, size_t) { ++sends; return isEnabled; }
    void setEnabled(bool value, Relay&) { enableCalls.push_back(value); isEnabled = value; }
    bool enabled() const { return isEnabled; }
    void poll(Relay& relay) {
        ++polls;
        if (isEnabled && pending) {
            pending = false;
            ++relay.acceptedCalls;
            relay.accepted(nextAccepted);
        }
    }
};
} // namespace mesh

// The runner supplies empty SDK/class headers so these fakes replace dependencies,
// not production logic. MessagePreview.h and BundleInfo are the real headers.
#include "../src/main.cpp"

static void press(MockButton& button) {
    const unsigned polls = ble.polls;
    button.pressed = true;
    loop();
    CHECK(!button.pressed);
    CHECK(ble.polls == polls + 1);
}
static void acceptFresh(const std::string& id, const std::string& text) {
    ble.nextAccepted = mesh::BundleInfo();
    ble.nextAccepted.id = id;
    ble.nextAccepted.text = text;
    ble.pending = true;
    const unsigned accepted = relay.acceptedCalls, polls = ble.polls;
    const size_t previews = M5.Display.previews.size();
    loop();
    CHECK(ble.polls == polls + 1 && !ble.pending);
    CHECK(relay.acceptedCalls == accepted + 1);
    CHECK(M5.Display.previews.size() == previews + 1);
    CHECK(M5.Display.previews.back() == mesh::messagePreview(text));
    CHECK(M5.Display.headings.back() == "RX " + id.substr(0, 8) + "\n");
}

int main() {
    setup();
    CHECK(meshReady && speechReady);
    CHECK(ble.begins == 1 && speech.begins == 1 && relay.begins == 1);
    CHECK(ble.node == relay.node && ble.node == "0001020304050607");
    CHECK(bool(relay.accepted) && bool(relay.send));
    CHECK(ble.enabled() && !speech.muted());
    CHECK(M5.Display.statuses.back() == "RX/TX:ON  AUDIO:ON");
    const uint8_t byte = 1;
    CHECK(relay.send(&byte, 1) && ble.sends == 1);
    // Positive control: a fresh accepted bundle normally queues speech.
    acceptFresh("11111111-first", "Before mute");
    CHECK(speech.enqueued.size() == 1);
    CHECK(speech.enqueued.back() == std::make_pair(std::string("11111111-first"), std::string("Before mute")));

    press(M5.BtnC); // Exercise actual wasPressed -> toggleMuted path.
    CHECK(speech.muted() && speech.toggles == 1);
    CHECK(ble.enabled() && ble.enableCalls.empty());
    CHECK(M5.Display.statuses.back() == "RX/TX:ON  AUDIO:MUTE");
    const size_t queued = speech.enqueued.size();
    acceptFresh("22222222-muted", "Fresh muted message\nwith preview");
    CHECK(ble.enabled() && speech.muted() && ble.enableCalls.empty());
    CHECK(speech.enqueued.size() == queued); // Not merely suppressed inside Speech.

    press(M5.BtnB);
    CHECK(!ble.enabled() && speech.muted() && speech.toggles == 1);
    CHECK(ble.enableCalls == std::vector<bool>({false}));
    CHECK(M5.Display.statuses.back() == "RX/TX:OFF  AUDIO:MUTE");
    CHECK(!relay.send(&byte, 1));
    const unsigned polls = ble.polls;
    loop(); CHECK(ble.polls == polls + 1); // Poll continues even with radio/audio off.
    CHECK(speech.enqueued.size() == queued);
    press(M5.BtnB);
    CHECK(ble.enabled() && speech.muted() && speech.toggles == 1);
    CHECK(ble.enableCalls == std::vector<bool>({false, true}));
    acceptFresh("33333333-still-muted", "Radio back on, still quiet");
    CHECK(speech.enqueued.size() == queued);

    press(M5.BtnC);
    CHECK(!speech.muted() && speech.toggles == 2 && ble.enabled());
    CHECK(ble.enableCalls == std::vector<bool>({false, true}));
    CHECK(M5.Display.statuses.back() == "RX/TX:ON  AUDIO:ON");
    acceptFresh("44444444-audible", "Speech is back");
    CHECK(speech.enqueued.size() == queued + 1);
    CHECK(speech.enqueued.back() == std::make_pair(std::string("44444444-audible"), std::string("Speech is back")));
    press(M5.BtnA);
    CHECK(speech.tests == 1 && !speech.muted() && ble.enabled());
    CHECK(ble.enableCalls == std::vector<bool>({false, true}));
    CHECK(speech.enqueued.size() == queued + 1 && speech.toggles == 2);

    // C and A must not accidentally re-enable a disabled radio either.
    press(M5.BtnB);
    press(M5.BtnC);
    CHECK(!ble.enabled() && speech.muted());
    press(M5.BtnA);
    CHECK(speech.tests == 2 && !ble.enabled() && speech.muted());
    press(M5.BtnC);
    CHECK(!ble.enabled() && !speech.muted());
    CHECK(ble.enableCalls == std::vector<bool>({false, true, false}));
    fakeMs = 5000; loop(); // Exercise heartbeat dependencies as well.
    std::cout << "PASS main controls: " << checks << " assertions (actual main.cpp, host boundaries)\n";
}
