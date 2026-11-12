#pragma once

#include <M5Unified.h>
#include <deque>
#include <string>
#include <vector>

#include "MeshBundle.h"

namespace ui {

// Snapshot taken by the main loop; the UI never calls into Relay/Ble itself.
struct Stats {
    bool meshReady = false, bleOn = false, link = false;
    size_t stored = 0, live = 0;
    uint32_t heap = 0;
};

enum class Action { None, ToggleBle };

// Single-task touch UI for the 320x240 CoreS3 panel. Everything (relay callbacks,
// touch, rendering) runs on the Arduino loop task, so no locking is needed.
// Frames are composed in a PSRAM sprite and pushed only when something changed.
class Ui {
public:
    void begin(const std::string& node);
    // Kind 1 posts newly validated and stored by the relay (never restored history).
    void addPost(const mesh::BundleInfo& info);
    // Kind 1 posts kept in storage from before this boot. No notice; listed after the
    // posts received in this boot, newest claimed creation time first.
    void addStored(const mesh::BundleInfo& info);
    // Removes displayed posts whose ID starts with idPrefix. A non-empty author must
    // also match, so a deletion request only hides its own author's posts.
    void erasePost(const std::string& idPrefix, const std::string& author = {});
    void addLog(const std::string& text);
    void setStats(const Stats& stats);
    // Reads touch and redraws when needed; call every loop after M5.update().
    Action update();
#ifdef UI_DEBUG_COMMANDS
    // Bench-only: dump the last frame (raw RGB565 big-endian) and inject taps over UART.
    void screenshot(Print& out);
    Action inject(int x, int y);
#endif

private:
    enum class Tab : uint8_t { Feed, Status, Log, Settings };
    struct Post {
        std::string id, pubkey, text;
        uint32_t hop = 0, hopLimit = 0;
        uint64_t createdAt = 0, expiresAt = 0;
        uint32_t receivedMs = 0;
        uint16_t color = 0;
        bool restored = false;  // received before this boot; no local receive time
        std::vector<std::string> preview;
    };
    struct LogLine { uint32_t ms; std::string text; uint16_t color; };

    M5Canvas canvas_{&M5.Display};
    std::string node_;
    Stats stats_;
    std::deque<Post> posts_;
    std::deque<LogLine> logs_;
    size_t received_ = 0, unread_ = 0;
    Tab tab_ = Tab::Feed;
    std::string detailId_;  // post shown in the detail view; empty for the list
    std::vector<std::string> detailLines_;

    // Scrolling (shared by feed, detail and log; reset on view change).
    float scroll_ = 0, velocity_ = 0;
    bool dragging_ = false, followLog_ = true, sliding_ = false, swallow_ = false;

    // Toast for posts arriving while the feed list is not visible.
    std::string toastId_, toastLine_;
    uint32_t toastMs_ = 0;
    uint32_t highlightMs_ = 0;

    bool chime_ = true, autoDim_ = true, dimmed_ = false;
    uint8_t brightness_ = 160;
    uint32_t touchedMs_ = 0, frameMs_ = 0, batteryMs_ = 0, secondMs_ = 0;
    int battery_ = -1;
    bool charging_ = false, dirty_ = true;

    Action touch();
    void tap(int x, int y, Action& action);
    const Post* find(const std::string& id) const;
    void openDetail(const std::string& id);
    void showTab(Tab tab);
    Post makePost(const mesh::BundleInfo& info);
    void wake();
    float maxScroll() const;
    bool animating(uint32_t now) const;

    void render(uint32_t now);
    void drawHeader();
    void drawTabs();
    void drawFeed(uint32_t now);
    void drawDetail(uint32_t now);
    void drawStatus(uint32_t now);
    void drawLog();
    void drawSettings();
    void drawToast(uint32_t now);
    void drawSwitch(int x, int y, bool on);
    void drawBluetooth(int cx, int cy, int size, uint16_t color);
    void drawTabIcon(Tab tab, int cx, int cy, uint16_t color);

    std::vector<std::string> wrap(const std::string& text, int width, size_t maxLines);
    void chime(bool post);
};

} // namespace ui
