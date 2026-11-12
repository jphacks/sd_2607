#include "Ui.h"

#include <algorithm>
#include <cmath>
#include <ctime>

namespace ui {
namespace {
constexpr int W = 320, H = 240;
constexpr int HEADER = 30, TABS = 42;
constexpr int TOP = HEADER, BOTTOM = H - TABS, VIEW = BOTTOM - TOP;
constexpr int CARD_H = 66, CARD_GAP = 6, MARGIN = 8;
constexpr int DETAIL_BAR = 34, DETAIL_META = 102, DETAIL_LINE = 20;
constexpr int LOG_LINE = 16;
constexpr int SETTING_ROW = 42;
constexpr size_t POST_LIMIT = 30, LOG_LIMIT = 80;
// Matches STORE_FILES in MeshRelay.cpp (local storage profile, not a protocol value).
constexpr size_t STORE_SLOTS = 16;
constexpr uint32_t TOAST_MS = 4000, HIGHLIGHT_MS = 2500, DIM_AFTER_MS = 60000;
constexpr uint8_t DIM_LEVEL = 12;

constexpr uint16_t rgb(uint8_t r, uint8_t g, uint8_t b) {
    return uint16_t(((r & 0xf8) << 8) | ((g & 0xfc) << 3) | (b >> 3));
}
constexpr uint16_t BG = rgb(11, 15, 20);
constexpr uint16_t SURFACE = rgb(22, 28, 36);
constexpr uint16_t SURFACE2 = rgb(34, 42, 54);
constexpr uint16_t LINE = rgb(48, 58, 72);
constexpr uint16_t TEXT = rgb(232, 237, 243);
constexpr uint16_t MUTED = rgb(140, 152, 168);
constexpr uint16_t ACCENT = rgb(46, 196, 182);
constexpr uint16_t ACCENT_DIM = rgb(18, 78, 74);
constexpr uint16_t WARN = rgb(255, 176, 59);
constexpr uint16_t DANGER = rgb(239, 83, 80);
constexpr uint16_t GOOD = rgb(102, 187, 106);
constexpr uint16_t INFO = rgb(100, 170, 245);

const lgfx::IFont* const SMALL = &fonts::lgfxJapanGothicP_12;
const lgfx::IFont* const BODY = &fonts::lgfxJapanGothicP_16;
const lgfx::IFont* const TITLE = &fonts::lgfxJapanGothicP_20;
const lgfx::IFont* const NUMBER = &fonts::FreeSansBold12pt7b;

uint16_t blend(uint16_t a, uint16_t b, float t) {
    t = std::min(1.0f, std::max(0.0f, t));
    const int ar = (a >> 11) & 31, ag = (a >> 5) & 63, ab = a & 31;
    const int br = (b >> 11) & 31, bg = (b >> 5) & 63, bb = b & 31;
    return uint16_t((int(ar + (br - ar) * t) << 11) | (int(ag + (bg - ag) * t) << 5) | int(ab + (bb - ab) * t));
}

// Stable per-author hue from the public key; a recognition aid, not an identity claim.
uint16_t authorColor(const std::string& pubkey) {
    uint32_t h = 2166136261u;
    for (char c : pubkey) h = (h ^ uint8_t(c)) * 16777619u;
    const float hue = (h % 360) / 60.0f, s = 0.55f, v = 0.92f;
    const float c = v * s, x = c * (1 - std::fabs(std::fmod(hue, 2.0f) - 1)), m = v - c;
    float r = 0, g = 0, b = 0;
    switch (int(hue)) {
        case 0: r = c; g = x; break;
        case 1: r = x; g = c; break;
        case 2: g = c; b = x; break;
        case 3: g = x; b = c; break;
        case 4: r = x; b = c; break;
        default: r = c; b = x; break;
    }
    return rgb(uint8_t((r + m) * 255), uint8_t((g + m) * 255), uint8_t((b + m) * 255));
}

size_t utf8Width(unsigned char first) {
    return first < 0x80 ? 1 : first < 0xe0 ? 2 : first < 0xf0 ? 3 : 4;
}

// Closing punctuation may hang past the right edge instead of starting a line.
bool hangs(const std::string& glyph) {
    static const char* const marks[] = {"、", "。", "，", "．", "）", "」", "』", "！", "？", "ー", ")", ",", "."};
    for (const char* mark : marks) if (glyph == mark) return true;
    return false;
}

std::string ago(uint32_t now, uint32_t then) {
    const uint32_t s = (now - then) / 1000;
    if (s < 10) return "たった今";
    if (s < 60) return std::to_string(s) + "秒前";
    if (s < 3600) return std::to_string(s / 60) + "分前";
    return std::to_string(s / 3600) + "時間前";
}

std::string uptime(uint32_t ms) {
    const uint32_t s = ms / 1000;
    char out[16];
    snprintf(out, sizeof(out), "%lu:%02lu:%02lu", (unsigned long)(s / 3600),
             (unsigned long)(s / 60 % 60), (unsigned long)(s % 60));
    return out;
}

// Signed author claim; this device has no trusted clock, so it is labelled as such.
std::string claimedTime(uint64_t unix) {
    if (!unix) return "なし";
    const time_t t = time_t(unix + 9 * 3600);
    struct tm tm;
    gmtime_r(&t, &tm);
    char out[24];
    strftime(out, sizeof(out), "%Y-%m-%d %H:%M", &tm);
    return out;
}

std::string shortHex(const std::string& hex, size_t n) {
    return hex.size() > n ? hex.substr(0, n) + "…" : hex;
}

uint16_t logColor(const std::string& text) {
    auto has = [&](const char* word) { return text.find(word) != std::string::npos; };
    if (has("rejected") || has("fail") || has("unavailable") || has("refused") || has("timed out")) return DANGER;
    if (has("saved") || has("accepted") || has("ready") || has("enabled")) return GOOD;
    if (has("stopped") || has("evicted") || has("disabled") || has("full")) return WARN;
    if (has("connected") || has("HELLO") || has("sent") || has("reconciled") || has("want")) return INFO;
    return MUTED;
}
} // namespace

void Ui::begin(const std::string& node) {
    node_ = node;
    M5.Display.setBrightness(brightness_);
    canvas_.setPsram(true);
    canvas_.setColorDepth(16);
    if (!canvas_.createSprite(W, H)) Serial.println("ui: frame buffer allocation failed");
    touchedMs_ = millis();
    dirty_ = true;
}

Ui::Post Ui::makePost(const mesh::BundleInfo& info) {
    Post post;
    post.id = info.id;
    post.pubkey = info.pubkey;
    post.text = info.text;
    post.hop = info.hop;
    post.hopLimit = info.hopLimit;
    post.createdAt = info.createdAt;
    post.expiresAt = info.expiresAt;
    post.receivedMs = millis();
    post.color = authorColor(info.pubkey);
    canvas_.setFont(BODY);
    post.preview = wrap(info.text, W - 2 * MARGIN - 24, 2);
    return post;
}

void Ui::addStored(const mesh::BundleInfo& info) {
    if (find(info.id) || posts_.size() >= POST_LIMIT) return;
    Post post = makePost(info);
    post.restored = true;
    auto at = std::find_if(posts_.begin(), posts_.end(), [&](const Post& p) {
        return p.restored && p.createdAt < post.createdAt;
    });
    posts_.insert(at, std::move(post));
    dirty_ = true;
}

void Ui::addPost(const mesh::BundleInfo& info) {
    Post post = makePost(info);
    posts_.push_front(std::move(post));
    if (posts_.size() > POST_LIMIT) {
        if (posts_.back().id == detailId_) detailId_.clear();
        posts_.pop_back();
    }
    ++received_;
    const bool listVisible = tab_ == Tab::Feed && detailId_.empty();
    if (listVisible) {
        // Keep the reader's position unless they were already at the top.
        if (scroll_ > 1) scroll_ += CARD_H + CARD_GAP;
        highlightMs_ = millis();
    } else {
        ++unread_;
        toastId_ = posts_.front().id;
        toastMs_ = millis();
        canvas_.setFont(BODY);
        const auto line = wrap(info.text, W - 2 * MARGIN - 46, 1);
        toastLine_ = line.empty() ? std::string() : line.front();
    }
    if (listVisible && scroll_ <= 1) unread_ = 0;
    wake();
    chime(true);
    dirty_ = true;
}

void Ui::erasePost(const std::string& idPrefix, const std::string& author) {
    if (idPrefix.empty()) return;
    const size_t before = posts_.size();
    posts_.erase(std::remove_if(posts_.begin(), posts_.end(), [&](const Post& p) {
        return p.id.compare(0, idPrefix.size(), idPrefix) == 0 && (author.empty() || p.pubkey == author);
    }), posts_.end());
    if (posts_.size() == before) return;
    if (!find(detailId_)) { detailId_.clear(); detailLines_.clear(); }
    if (!find(toastId_)) toastId_.clear();
    unread_ = std::min(unread_, posts_.size());
    scroll_ = std::min(scroll_, maxScroll());
    dirty_ = true;
}

void Ui::addLog(const std::string& text) {
    canvas_.setFont(SMALL);
    const auto lines = wrap(text, W - 2 * MARGIN - 48, 1);
    logs_.push_back({millis(), lines.empty() ? std::string() : lines.front(), logColor(text)});
    if (logs_.size() > LOG_LIMIT) {
        logs_.pop_front();
        if (tab_ == Tab::Log && !followLog_) scroll_ = std::max(0.0f, scroll_ - LOG_LINE);
    }
    if (tab_ == Tab::Log) {
        if (followLog_) scroll_ = maxScroll();
        dirty_ = true;
    }
}

void Ui::setStats(const Stats& stats) {
    if (stats.meshReady != stats_.meshReady || stats.bleOn != stats_.bleOn || stats.link != stats_.link ||
        stats.stored != stats_.stored || stats.live != stats_.live) dirty_ = true;
    stats_ = stats;
}

const Ui::Post* Ui::find(const std::string& id) const {
    if (id.empty()) return nullptr;
    for (const auto& post : posts_) if (post.id == id) return &post;
    return nullptr;
}

void Ui::openDetail(const std::string& id) {
    const Post* post = find(id);
    if (!post) return;
    tab_ = Tab::Feed;
    detailId_ = id;
    canvas_.setFont(BODY);
    detailLines_ = wrap(post->text, W - 2 * MARGIN - 8, 0);
    scroll_ = 0; velocity_ = 0;
    if (toastId_ == id) toastId_.clear();
    unread_ = 0;
    dirty_ = true;
}

void Ui::showTab(Tab tab) {
    if (tab == Tab::Feed && tab_ == Tab::Feed && detailId_.empty()) {
        scroll_ = 0;  // second tap on the active feed tab returns to the newest post
    } else {
        tab_ = tab;
        detailId_.clear();
        detailLines_.clear();
        scroll_ = 0;
    }
    velocity_ = 0;
    if (tab_ == Tab::Feed) { unread_ = 0; toastId_.clear(); }
    if (tab_ == Tab::Log) { followLog_ = true; scroll_ = maxScroll(); }
    dirty_ = true;
}

void Ui::wake() {
    touchedMs_ = millis();
    if (dimmed_) {
        dimmed_ = false;
        M5.Display.setBrightness(brightness_);
    }
}

float Ui::maxScroll() const {
    int content = 0, view = VIEW;
    if (tab_ == Tab::Feed && !detailId_.empty()) {
        content = 4 + int(detailLines_.size()) * DETAIL_LINE + 8 + DETAIL_META + MARGIN;
        view = VIEW - DETAIL_BAR;
    } else if (tab_ == Tab::Feed) {
        content = int(posts_.size()) * (CARD_H + CARD_GAP) + CARD_GAP;
    } else if (tab_ == Tab::Log) {
        content = int(logs_.size()) * LOG_LINE + 2 * MARGIN;
    }
    return float(std::max(0, content - view));
}

bool Ui::animating(uint32_t now) const {
    if (std::fabs(velocity_) > 0.3f) return true;
    if (!toastId_.empty() && now - toastMs_ < TOAST_MS) return true;
    if (tab_ == Tab::Feed && detailId_.empty() && now - highlightMs_ < HIGHLIGHT_MS) return true;
    return tab_ == Tab::Status && stats_.meshReady && stats_.bleOn;
}

Action Ui::update() {
    const uint32_t now = millis();
    Action action = touch();

    if (!dragging_ && std::fabs(velocity_) > 0.3f) {
        scroll_ += velocity_;
        velocity_ *= 0.93f;
        const float limit = maxScroll();
        if (scroll_ < 0 || scroll_ > limit) { scroll_ = std::min(std::max(scroll_, 0.0f), limit); velocity_ = 0; }
        if (tab_ == Tab::Log) followLog_ = scroll_ >= limit - 1;
        dirty_ = true;
    }
    if (!toastId_.empty() && now - toastMs_ >= TOAST_MS) { toastId_.clear(); dirty_ = true; }

    if (now - batteryMs_ >= 5000 || !batteryMs_) {
        batteryMs_ = now;
        // Without a pack the PMIC reads ~0 V; hide the gauge instead of showing 0%.
        const int level = M5.Power.getBatteryVoltage() >= 3000 ? M5.Power.getBatteryLevel() : -1;
        const bool charging = M5.Power.isCharging() == m5::Power_Class::is_charging;
        if (level != battery_ || charging != charging_) { battery_ = level; charging_ = charging; dirty_ = true; }
    }
    // Relative times and uptime.
    if (now - secondMs_ >= 1000) { secondMs_ = now; dirty_ = true; }

    if (autoDim_ && !dimmed_ && now - touchedMs_ >= DIM_AFTER_MS) {
        dimmed_ = true;
        M5.Display.setBrightness(DIM_LEVEL);
    }

    const bool busyLink = stats_.link && !M5.Touch.getDetail().isPressed() && std::fabs(velocity_) <= 0.3f;
    // Pushing a frame blocks the loop for ~30 ms; leave the relay more time during a session.
    const uint32_t frameGap = busyLink ? 90 : 33;
    if ((dirty_ || animating(now)) && now - frameMs_ >= frameGap) {
        frameMs_ = now;
        dirty_ = false;
        render(now);
    }
    return action;
}

Action Ui::touch() {
    Action action = Action::None;
    if (!M5.Touch.getCount() && !M5.Touch.getDetail().wasReleased()) return action;
    const auto& t = M5.Touch.getDetail();

    if (t.wasPressed()) {
        swallow_ = dimmed_;  // the first touch on a dimmed screen only wakes it
        wake();
        velocity_ = 0;
        dragging_ = false;
        sliding_ = !swallow_ && tab_ == Tab::Settings && t.y >= TOP + 2 * SETTING_ROW &&
                   t.y < TOP + 3 * SETTING_ROW && t.x >= 96;
    }
    if (swallow_) {
        if (t.wasReleased()) swallow_ = false;
        return action;
    }
    touchedMs_ = millis();

    if (sliding_ && t.isPressed()) {
        const int x = std::min(std::max<int>(t.x, 120), 296);
        const uint8_t level = uint8_t(24 + (x - 120) * (255 - 24) / (296 - 120));
        if (level != brightness_) {
            brightness_ = level;
            M5.Display.setBrightness(brightness_);
            dirty_ = true;
        }
    } else if (t.isPressed() && (t.state & m5::touch_state_t::mask_moving) && t.base_y >= TOP && t.base_y < BOTTOM) {
        const float dy = float(t.prev_y - t.y);
        const float limit = maxScroll();
        scroll_ = std::min(std::max(scroll_ + dy, 0.0f), limit);
        velocity_ = dy;
        dragging_ = true;
        if (tab_ == Tab::Log) followLog_ = scroll_ >= limit - 1;
        if (tab_ == Tab::Feed && detailId_.empty() && scroll_ <= 1) unread_ = 0;
        dirty_ = true;
    }
    if (t.wasReleased()) {
        if (!dragging_) velocity_ = 0;
        dragging_ = false;
        sliding_ = false;
    }
    if (t.wasClicked() || t.state == m5::touch_state_t::hold_end) tap(t.x, t.y, action);
    return action;
}

void Ui::tap(int x, int y, Action& action) {
    if (y >= BOTTOM) {
        showTab(static_cast<Tab>(std::min(3, x / (W / 4))));
        chime(false);
        return;
    }
    if (!toastId_.empty() && y >= TOP + 4 && y < TOP + 52) {
        openDetail(toastId_);
        chime(false);
        return;
    }
    if (y < TOP) return;
    switch (tab_) {
        case Tab::Feed:
            if (!detailId_.empty()) {
                if (y < TOP + DETAIL_BAR && x < 110) { detailId_.clear(); detailLines_.clear(); scroll_ = 0; dirty_ = true; chime(false); }
                return;
            } else {
                const int index = int((y - TOP + scroll_ - CARD_GAP) / (CARD_H + CARD_GAP));
                if (index >= 0 && index < int(posts_.size())) { openDetail(posts_[index].id); chime(false); }
            }
            return;
        case Tab::Log:
            if (!followLog_ && x >= W - 92 && y >= BOTTOM - 28) {
                followLog_ = true; scroll_ = maxScroll(); velocity_ = 0; dirty_ = true; chime(false);
            }
            return;
        case Tab::Settings: {
            const int row = (y - TOP) / SETTING_ROW;
            if (row == 0) action = Action::ToggleBle;
            else if (row == 1) chime_ = !chime_;
            else if (row == 3) autoDim_ = !autoDim_;
            else return;
            chime(false);
            dirty_ = true;
            return;
        }
        default:
            return;
    }
}

#ifdef UI_DEBUG_COMMANDS
void Ui::screenshot(Print& out) {
    const auto* frame = static_cast<const uint8_t*>(canvas_.getBuffer());
    if (!frame) { out.println("SHOT none"); return; }
    out.printf("SHOT %d %d\n", W, H);
    out.write(frame, size_t(W) * H * 2);
    out.println("\nEND");
}

Action Ui::inject(int x, int y) {
    Action action = Action::None;
    wake();
    tap(x, y, action);
    return action;
}
#endif

std::vector<std::string> Ui::wrap(const std::string& text, int width, size_t maxLines) {
    std::vector<std::string> lines;
    std::string line;
    int used = 0;
    for (size_t at = 0; at < text.size();) {
        const size_t n = utf8Width(static_cast<unsigned char>(text[at]));
        if (n > text.size() - at) break;
        std::string glyph = text.substr(at, n);
        at += n;
        if (glyph == "\n") { lines.push_back(line); line.clear(); used = 0; continue; }
        if (static_cast<unsigned char>(glyph[0]) < 0x20 || glyph[0] == 0x7f) glyph = " ";
        const int w = canvas_.textWidth(glyph.c_str());
        if (used + w > width && !line.empty() && !hangs(glyph)) {
            lines.push_back(line);
            line.clear(); used = 0;
            if (glyph == " ") continue;
        }
        line += glyph; used += w;
    }
    if (!line.empty()) lines.push_back(line);
    if (maxLines && lines.size() > maxLines) {
        lines.resize(maxLines);
        std::string& last = lines.back();
        while (!last.empty() && canvas_.textWidth((last + "…").c_str()) > width) {
            size_t cut = last.size() - 1;
            while (cut && (static_cast<unsigned char>(last[cut]) & 0xc0) == 0x80) --cut;
            last.erase(cut);
        }
        last += "…";
    }
    return lines;
}

void Ui::chime(bool post) {
    if (!chime_) return;
    if (post) {
        M5.Speaker.tone(1568, 70, 0, true);
        M5.Speaker.tone(2093, 120, 0, false);
    } else {
        M5.Speaker.tone(2800, 6, 0, true);
    }
}

// ---------------------------------------------------------------- drawing

void Ui::render(uint32_t now) {
    canvas_.fillScreen(BG);
    canvas_.setTextDatum(textdatum_t::top_left);
    canvas_.setClipRect(0, TOP, W, VIEW);
    switch (tab_) {
        case Tab::Feed: detailId_.empty() ? drawFeed(now) : drawDetail(now); break;
        case Tab::Status: drawStatus(now); break;
        case Tab::Log: drawLog(); break;
        case Tab::Settings: drawSettings(); break;
    }
    canvas_.clearClipRect();
    drawToast(now);
    drawHeader();
    drawTabs();
    canvas_.pushSprite(0, 0);
}

void Ui::drawHeader() {
    canvas_.fillRect(0, 0, W, HEADER, SURFACE);
    canvas_.drawFastHLine(0, HEADER - 1, W, LINE);
    // Logo: three linked nodes.
    canvas_.drawLine(10, 20, 17, 9, ACCENT);
    canvas_.drawLine(17, 9, 24, 20, ACCENT);
    canvas_.drawLine(10, 20, 24, 20, ACCENT_DIM);
    canvas_.fillSmoothCircle(10, 20, 3, ACCENT);
    canvas_.fillSmoothCircle(17, 9, 3, TEXT);
    canvas_.fillSmoothCircle(24, 20, 3, ACCENT);

    static const char* const titles[] = {"受信した投稿", "中継の状態", "ログ", "設定"};
    canvas_.setFont(BODY);
    canvas_.setTextColor(TEXT);
    canvas_.setTextDatum(textdatum_t::middle_left);
    canvas_.drawString(!detailId_.empty() ? "投稿の詳細" : titles[int(tab_)], 34, HEADER / 2);

    // Battery (hidden when no pack is fitted).
    int x = W - 8;
    if (battery_ >= 0) {
        x -= 24;
        const int y = 9;
        const uint16_t frame = battery_ <= 15 && !charging_ ? DANGER : MUTED;
        canvas_.drawRoundRect(x, y, 22, 12, 2, frame);
        canvas_.fillRect(x + 22, y + 3, 2, 6, frame);
        const int fill = 18 * battery_ / 100;
        canvas_.fillRect(x + 2, y + 2, fill, 8, charging_ ? GOOD : battery_ <= 15 ? DANGER : TEXT);
        canvas_.setFont(SMALL);
        canvas_.setTextColor(MUTED);
        canvas_.setTextDatum(textdatum_t::middle_right);
        canvas_.drawString((std::to_string(battery_) + "%").c_str(), x - 4, HEADER / 2);
        x -= 4 + canvas_.textWidth("100%");
    }
    x += 8;

    // Relay pill.
    const char* label = !stats_.meshReady ? "エラー" : !stats_.bleOn ? "停止" : stats_.link ? "接続中" : "待機";
    const uint16_t color = !stats_.meshReady ? DANGER : !stats_.bleOn ? MUTED : stats_.link ? ACCENT : INFO;
    canvas_.setFont(SMALL);
    const int pillW = canvas_.textWidth(label) + 26;
    const int pillX = x - 8 - pillW;
    canvas_.fillSmoothRoundRect(pillX, 6, pillW, 18, 9, blend(BG, color, 0.25f));
    drawBluetooth(pillX + 9, 15, 10, color);
    canvas_.setTextColor(color);
    canvas_.setTextDatum(textdatum_t::middle_left);
    canvas_.drawString(label, pillX + 18, 15);
    canvas_.setTextDatum(textdatum_t::top_left);
}

void Ui::drawTabs() {
    canvas_.fillRect(0, BOTTOM, W, TABS, SURFACE);
    canvas_.drawFastHLine(0, BOTTOM, W, LINE);
    static const char* const labels[] = {"受信", "状態", "ログ", "設定"};
    canvas_.setFont(SMALL);
    canvas_.setTextDatum(textdatum_t::top_center);
    for (int i = 0; i < 4; ++i) {
        const int cx = i * (W / 4) + W / 8;
        const bool active = int(tab_) == i;
        const uint16_t color = active ? ACCENT : MUTED;
        if (active) canvas_.fillSmoothRoundRect(cx - 20, BOTTOM + 1, 40, 3, 1, ACCENT);
        drawTabIcon(static_cast<Tab>(i), cx, BOTTOM + 15, color);
        canvas_.setTextColor(color);
        canvas_.drawString(labels[i], cx, BOTTOM + 26);
        if (i == 0 && unread_) {
            const std::string count = unread_ > 9 ? "9+" : std::to_string(unread_);
            canvas_.fillSmoothCircle(cx + 14, BOTTOM + 9, 8, DANGER);
            canvas_.setTextColor(TEXT);
            canvas_.setTextDatum(textdatum_t::middle_center);
            canvas_.drawString(count.c_str(), cx + 14, BOTTOM + 9);
            canvas_.setTextDatum(textdatum_t::top_center);
        }
    }
    canvas_.setTextDatum(textdatum_t::top_left);
}

void Ui::drawTabIcon(Tab tab, int cx, int cy, uint16_t color) {
    switch (tab) {
        case Tab::Feed:  // inbox tray
            canvas_.drawRoundRect(cx - 9, cy - 7, 18, 14, 2, color);
            canvas_.drawLine(cx - 9, cy, cx - 4, cy, color);
            canvas_.drawLine(cx - 4, cy, cx - 2, cy + 3, color);
            canvas_.drawLine(cx - 2, cy + 3, cx + 2, cy + 3, color);
            canvas_.drawLine(cx + 2, cy + 3, cx + 4, cy, color);
            canvas_.drawLine(cx + 4, cy, cx + 8, cy, color);
            break;
        case Tab::Status:  // pulse
            canvas_.drawLine(cx - 10, cy, cx - 5, cy, color);
            canvas_.drawLine(cx - 5, cy, cx - 2, cy - 7, color);
            canvas_.drawLine(cx - 2, cy - 7, cx + 2, cy + 7, color);
            canvas_.drawLine(cx + 2, cy + 7, cx + 5, cy, color);
            canvas_.drawLine(cx + 5, cy, cx + 10, cy, color);
            break;
        case Tab::Log:  // lines
            for (int i = -1; i <= 1; ++i) {
                canvas_.fillRect(cx - 9, cy + i * 5 - 1, 3, 2, color);
                canvas_.fillRect(cx - 4, cy + i * 5 - 1, 13, 2, color);
            }
            break;
        case Tab::Settings:  // sliders
            canvas_.drawFastHLine(cx - 9, cy - 4, 18, color);
            canvas_.drawFastHLine(cx - 9, cy + 4, 18, color);
            canvas_.fillSmoothCircle(cx - 3, cy - 4, 3, color);
            canvas_.fillSmoothCircle(cx + 4, cy + 4, 3, color);
            break;
    }
}

void Ui::drawBluetooth(int cx, int cy, int size, uint16_t color) {
    const int h = size / 2, q = size / 4;
    const float r = std::max(0.5f, size / 22.0f);
    canvas_.drawWideLine(cx, cy - h, cx, cy + h, r, color);
    canvas_.drawWideLine(cx, cy - h, cx + q, cy - q, r, color);
    canvas_.drawWideLine(cx + q, cy - q, cx - q, cy + q, r, color);
    canvas_.drawWideLine(cx, cy + h, cx + q, cy + q, r, color);
    canvas_.drawWideLine(cx + q, cy + q, cx - q, cy - q, r, color);
}

void Ui::drawFeed(uint32_t now) {
    if (posts_.empty()) {
        const int cy = TOP + 58;
        canvas_.drawRoundRect(W / 2 - 22, cy - 16, 44, 32, 4, LINE);
        canvas_.drawLine(W / 2 - 22, cy + 2, W / 2 - 8, cy + 2, LINE);
        canvas_.drawLine(W / 2 - 8, cy + 2, W / 2 - 4, cy + 8, LINE);
        canvas_.drawLine(W / 2 - 4, cy + 8, W / 2 + 4, cy + 8, LINE);
        canvas_.drawLine(W / 2 + 4, cy + 8, W / 2 + 8, cy + 2, LINE);
        canvas_.drawLine(W / 2 + 8, cy + 2, W / 2 + 21, cy + 2, LINE);
        canvas_.setFont(BODY);
        canvas_.setTextColor(TEXT);
        canvas_.setTextDatum(textdatum_t::top_center);
        canvas_.drawString("まだ投稿を受信していません", W / 2, cy + 32);
        canvas_.setFont(SMALL);
        canvas_.setTextColor(MUTED);
        canvas_.drawString("近くのアプリから届いた投稿がここに並びます", W / 2, cy + 56);
        canvas_.drawString("（端末に保存中の投稿も表示します）", W / 2, cy + 72);
        canvas_.setTextDatum(textdatum_t::top_left);
        return;
    }
    const int x = MARGIN, w = W - 2 * MARGIN;
    for (size_t i = 0; i < posts_.size(); ++i) {
        const int y = TOP + CARD_GAP + int(i) * (CARD_H + CARD_GAP) - int(scroll_);
        if (y + CARD_H < TOP) continue;
        if (y >= BOTTOM) break;
        const Post& p = posts_[i];
        uint16_t card = SURFACE;
        if (i == 0 && now - highlightMs_ < HIGHLIGHT_MS) card = blend(ACCENT_DIM, SURFACE, float(now - highlightMs_) / HIGHLIGHT_MS);
        canvas_.fillSmoothRoundRect(x, y, w, CARD_H, 8, card);
        canvas_.fillRect(x, y + 8, 3, CARD_H - 16, p.color);

        canvas_.fillSmoothCircle(x + 18, y + 15, 8, p.color);
        canvas_.setFont(SMALL);
        canvas_.setTextColor(BG);
        canvas_.setTextDatum(textdatum_t::middle_center);
        canvas_.drawString(p.pubkey.substr(0, 1).c_str(), x + 18, y + 15);
        canvas_.setTextDatum(textdatum_t::middle_left);
        canvas_.setTextColor(p.color);
        canvas_.drawString(shortHex(p.pubkey, 8).c_str(), x + 32, y + 15);
        canvas_.setTextColor(MUTED);
        canvas_.setTextDatum(textdatum_t::middle_right);
        canvas_.drawString(("hop " + std::to_string(p.hop) + " ・ " + (p.restored ? "再起動前に受信" : ago(now, p.receivedMs))).c_str(), x + w - 10, y + 15);

        canvas_.setFont(BODY);
        canvas_.setTextColor(TEXT);
        canvas_.setTextDatum(textdatum_t::top_left);
        for (size_t l = 0; l < p.preview.size(); ++l) canvas_.drawString(p.preview[l].c_str(), x + 14, y + 26 + int(l) * 18);
    }
    // Scroll indicator.
    const float limit = maxScroll();
    if (limit > 0) {
        const int track = VIEW - 8, thumb = std::max(18, int(track * VIEW / (VIEW + limit)));
        canvas_.fillSmoothRoundRect(W - 4, TOP + 4 + int((track - thumb) * scroll_ / limit), 3, thumb, 1, LINE);
    }
    canvas_.setTextDatum(textdatum_t::top_left);
}

void Ui::drawDetail(uint32_t now) {
    const Post* p = find(detailId_);
    if (!p) return;
    // Fixed bar.
    canvas_.fillRect(0, TOP, W, DETAIL_BAR, BG);
    canvas_.fillSmoothRoundRect(MARGIN, TOP + 5, 86, 24, 12, SURFACE2);
    canvas_.drawLine(MARGIN + 16, TOP + 11, MARGIN + 10, TOP + 17, TEXT);
    canvas_.drawLine(MARGIN + 10, TOP + 17, MARGIN + 16, TOP + 23, TEXT);
    canvas_.setFont(SMALL);
    canvas_.setTextColor(TEXT);
    canvas_.setTextDatum(textdatum_t::middle_left);
    canvas_.drawString("一覧へ", MARGIN + 24, TOP + 17);
    canvas_.setTextColor(MUTED);
    canvas_.setTextDatum(textdatum_t::middle_right);
    canvas_.drawString(("受信 " + (p->restored ? std::string("再起動前") : ago(now, p->receivedMs))).c_str(), W - MARGIN, TOP + 17);

    const int top = TOP + DETAIL_BAR;
    canvas_.setClipRect(0, top, W, BOTTOM - top);
    const int x = MARGIN;
    int y = top + 4 - int(scroll_);
    canvas_.setFont(BODY);
    canvas_.setTextColor(TEXT);
    canvas_.setTextDatum(textdatum_t::top_left);
    for (const auto& line : detailLines_) {
        if (y + DETAIL_LINE >= top && y < BOTTOM) canvas_.drawString(line.c_str(), x + 4, y);
        y += DETAIL_LINE;
    }
    y += 8;

    // Metadata card: what the bundle claims, not verified facts about the world.
    canvas_.fillSmoothRoundRect(x, y, W - 2 * MARGIN, DETAIL_META, 8, SURFACE);
    canvas_.setFont(SMALL);
    canvas_.fillSmoothCircle(x + 18, y + 18, 10, p->color);
    canvas_.setTextColor(BG);
    canvas_.setTextDatum(textdatum_t::middle_center);
    canvas_.drawString(p->pubkey.substr(0, 1).c_str(), x + 18, y + 18);
    canvas_.setTextDatum(textdatum_t::top_left);
    canvas_.setTextColor(MUTED);
    canvas_.drawString("発信者（公開鍵）", x + 36, y + 4);
    canvas_.setTextColor(p->color);
    canvas_.drawString(shortHex(p->pubkey, 24).c_str(), x + 36, y + 18);

    const struct { const char* label; std::string value; } rows[] = {
        {"投稿ID", shortHex(p->id, 24)},
        {"中継回数", std::to_string(p->hop) + " / 上限 " + std::to_string(p->hopLimit)},
        {"作成時刻", claimedTime(p->createdAt) + "（投稿者の申告・JST）"},
        {"配送期限", claimedTime(p->expiresAt) + "（申告）"},
    };
    int ry = y + 38;
    for (const auto& row : rows) {
        canvas_.setTextColor(MUTED);
        canvas_.drawString(row.label, x + 10, ry);
        canvas_.setTextColor(TEXT);
        canvas_.drawString(row.value.c_str(), x + 66, ry);
        ry += 15;
    }
    canvas_.clearClipRect();
}

void Ui::drawStatus(uint32_t now) {
    const int cx = 72, cy = TOP + 62, r = 36;
    uint16_t color;
    const char* state;
    const char* detail;
    if (!stats_.meshReady) {
        color = DANGER; state = "エラー"; detail = "ログを確認";
        canvas_.drawCircle(cx, cy, r, DANGER);
        canvas_.drawCircle(cx, cy, r - 1, DANGER);
    } else if (!stats_.bleOn) {
        color = MUTED; state = "停止中"; detail = "設定で再開できます";
        canvas_.drawCircle(cx, cy, r, LINE);
        canvas_.drawCircle(cx, cy, r - 1, LINE);
        canvas_.drawWideLine(cx - 24, cy + 24, cx + 24, cy - 24, 1.5f, MUTED);
    } else if (stats_.link) {
        color = ACCENT; state = "接続中"; detail = "投稿をやり取り中";
        canvas_.fillSmoothCircle(cx, cy, r, ACCENT_DIM);
        const float a = float(now % 1200) * 360.0f / 1200.0f;
        canvas_.fillArc(cx, cy, r + 4, r + 8, a, a + 100, ACCENT);
        canvas_.fillArc(cx, cy, r + 4, r + 8, a + 180, a + 280, ACCENT);
    } else {
        color = INFO; state = "待機中"; detail = "近くの端末を待機";
        for (int k = 0; k < 2; ++k) {
            const float phase = std::fmod(float(now % 2400) / 2400.0f + k * 0.5f, 1.0f);
            const int rr = r + int(phase * 14);
            const uint16_t c = blend(INFO, BG, phase);
            canvas_.drawCircle(cx, cy, rr, c);
            canvas_.drawCircle(cx, cy, rr + 1, c);
        }
        canvas_.fillSmoothCircle(cx, cy, r, blend(BG, INFO, 0.18f));
    }
    drawBluetooth(cx, cy, 34, stats_.meshReady && stats_.bleOn ? TEXT : MUTED);

    canvas_.setTextDatum(textdatum_t::top_center);
    canvas_.setFont(TITLE);
    canvas_.setTextColor(color);
    canvas_.drawString(state, cx, TOP + 110);
    canvas_.setFont(SMALL);
    canvas_.setTextColor(MUTED);
    canvas_.drawString(detail, cx, TOP + 134);
    canvas_.drawString(("ノード " + shortHex(node_, 8)).c_str(), cx, TOP + 150);

    // Tiles.
    const int tx = 146, tw = 82, th = 74, gap = 6;
    struct Tile { const char* label; std::string value; std::string sub; float bar; uint16_t color; };
    const float fill = float(stats_.stored) / STORE_SLOTS;
    const Tile tiles[] = {
        {"保存", std::to_string(stats_.stored) + "/" + std::to_string(STORE_SLOTS), "", fill, fill >= 1 ? WARN : ACCENT},
        {"配信候補", std::to_string(stats_.live), "相手へ渡せる", -1, INFO},
        {"受信", std::to_string(received_), "この起動後", -1, GOOD},
        {"稼働", uptime(now), "空き " + std::to_string(stats_.heap / 1024) + "KB", -1, TEXT},
    };
    for (int i = 0; i < 4; ++i) {
        const int x = tx + (i % 2) * (tw + gap), y = TOP + 8 + (i / 2) * (th + gap);
        const Tile& tile = tiles[i];
        canvas_.fillSmoothRoundRect(x, y, tw, th, 8, SURFACE);
        canvas_.setTextDatum(textdatum_t::top_left);
        canvas_.setFont(SMALL);
        canvas_.setTextColor(MUTED);
        canvas_.drawString(tile.label, x + 8, y + 6);
        canvas_.setFont(i == 3 ? BODY : NUMBER);
        canvas_.setTextColor(tile.color);
        canvas_.drawString(tile.value.c_str(), x + 8, y + (i == 3 ? 28 : 24));
        if (tile.bar >= 0) {
            canvas_.fillSmoothRoundRect(x + 8, y + th - 14, tw - 16, 6, 3, SURFACE2);
            const int bw = int((tw - 16) * std::min(1.0f, tile.bar));
            if (bw > 0) canvas_.fillSmoothRoundRect(x + 8, y + th - 14, std::max(6, bw), 6, 3, tile.color);
        } else if (!tile.sub.empty()) {
            canvas_.setFont(SMALL);
            canvas_.setTextColor(MUTED);
            canvas_.drawString(tile.sub.c_str(), x + 8, y + th - 18);
        }
    }
}

void Ui::drawLog() {
    if (logs_.empty()) return;
    canvas_.setFont(SMALL);
    canvas_.setTextDatum(textdatum_t::top_left);
    int y = TOP + MARGIN - int(scroll_);
    for (const auto& line : logs_) {
        if (y + LOG_LINE >= TOP && y < BOTTOM) {
            const uint32_t s = line.ms / 1000;
            char stamp[12];
            snprintf(stamp, sizeof(stamp), "%02lu:%02lu", (unsigned long)(s / 60 % 100), (unsigned long)(s % 60));
            canvas_.setTextColor(LINE);
            canvas_.drawString(stamp, MARGIN, y);
            canvas_.fillSmoothCircle(MARGIN + 42, y + 7, 2, line.color);
            canvas_.setTextColor(line.color == MUTED ? TEXT : line.color);
            canvas_.drawString(line.text.c_str(), MARGIN + 50, y);
        }
        y += LOG_LINE;
    }
    if (!followLog_) {
        // "Jump to latest" hint while reading history.
        canvas_.fillSmoothRoundRect(W - 92, BOTTOM - 28, 84, 22, 11, SURFACE2);
        canvas_.setTextColor(ACCENT);
        canvas_.setTextDatum(textdatum_t::middle_center);
        canvas_.drawString("↓ 最新へ", W - 50, BOTTOM - 17);
        canvas_.setTextDatum(textdatum_t::top_left);
    }
}

void Ui::drawSwitch(int x, int y, bool on) {
    canvas_.fillSmoothRoundRect(x, y, 44, 24, 12, on ? ACCENT : SURFACE2);
    canvas_.fillSmoothCircle(on ? x + 32 : x + 12, y + 12, 9, on ? TEXT : MUTED);
}

void Ui::drawSettings() {
    struct Row { const char* title; const char* sub; };
    const Row rows[] = {
        {"Bluetooth中継", !stats_.meshReady ? "初期化に失敗しました" : stats_.bleOn ? "投稿を受け取り、次の端末へ渡します" : "通信を止めています（保存は保持）"},
        {"通知音", "新着投稿と操作の音"},
        {"明るさ", ""},
        {"自動減光", "60秒操作がないと暗くします"},
    };
    for (int i = 0; i < 4; ++i) {
        const int y = TOP + i * SETTING_ROW;
        if (i) canvas_.drawFastHLine(MARGIN, y, W - 2 * MARGIN, SURFACE2);
        canvas_.setFont(BODY);
        canvas_.setTextColor(TEXT);
        canvas_.setTextDatum(i == 2 ? textdatum_t::middle_left : textdatum_t::top_left);
        canvas_.drawString(rows[i].title, MARGIN + 4, i == 2 ? y + SETTING_ROW / 2 : y + 5);
        if (*rows[i].sub) {
            canvas_.setFont(SMALL);
            canvas_.setTextColor(i == 0 && !stats_.meshReady ? DANGER : MUTED);
            canvas_.drawString(rows[i].sub, MARGIN + 4, y + 24);
        }
    }
    canvas_.setTextDatum(textdatum_t::top_left);
    if (stats_.meshReady) drawSwitch(W - MARGIN - 48, TOP + 9, stats_.bleOn);
    drawSwitch(W - MARGIN - 48, TOP + SETTING_ROW + 9, chime_);
    drawSwitch(W - MARGIN - 48, TOP + 3 * SETTING_ROW + 9, autoDim_);

    const int sy = TOP + 2 * SETTING_ROW + SETTING_ROW / 2;
    const int kx = 120 + (brightness_ - 24) * (296 - 120) / (255 - 24);
    canvas_.fillSmoothRoundRect(120, sy - 3, 176, 6, 3, SURFACE2);
    canvas_.fillSmoothRoundRect(120, sy - 3, kx - 120 + 3, 6, 3, ACCENT);
    canvas_.fillSmoothCircle(kx, sy, 9, TEXT);
}

void Ui::drawToast(uint32_t now) {
    const Post* p = find(toastId_);
    if (!p || now - toastMs_ >= TOAST_MS) return;
    const uint32_t age = now - toastMs_;
    // Slide in, hold, slide out.
    float t = age < 220 ? age / 220.0f : age > TOAST_MS - 220 ? (TOAST_MS - age) / 220.0f : 1.0f;
    t = 1 - (1 - t) * (1 - t);
    const int y = TOP + 4 - int((1 - t) * 56);
    canvas_.fillSmoothRoundRect(MARGIN - 1, y - 1, W - 2 * MARGIN + 2, 50, 10, LINE);
    canvas_.fillSmoothRoundRect(MARGIN, y, W - 2 * MARGIN, 48, 10, SURFACE2);
    canvas_.fillSmoothCircle(MARGIN + 20, y + 24, 10, p->color);
    canvas_.setFont(SMALL);
    canvas_.setTextDatum(textdatum_t::top_left);
    canvas_.setTextColor(ACCENT);
    canvas_.drawString("新着投稿", MARGIN + 38, y + 6);
    canvas_.setTextColor(MUTED);
    canvas_.drawString(("・ " + shortHex(p->pubkey, 8) + " ・ タップで開く").c_str(), MARGIN + 38 + canvas_.textWidth("新着投稿") + 4, y + 6);
    canvas_.setFont(BODY);
    canvas_.setTextColor(TEXT);
    canvas_.drawString(toastLine_.c_str(), MARGIN + 38, y + 24);
}

} // namespace ui
