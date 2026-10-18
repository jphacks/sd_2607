#pragma once
#include <atomic>
#include <cstddef>
#include <cstdint>

#include <cstring>
#include <string>

namespace mesh { namespace speech {
constexpr size_t MaxText = 2000;
constexpr size_t ChunkPoints = 100;
constexpr size_t Pending = 3;

// Strict UTF-8: reject truncation, overlong forms, surrogates and > U+10FFFF.
inline size_t decode(const char* s, size_t remaining, uint32_t& cp) {
    if (!remaining) return 0;
    const auto c = static_cast<uint8_t>(s[0]);
    size_t n;
    if (c < 0x80) { cp = c; return 1; }
    if (c >= 0xc2 && c <= 0xdf) { n = 2; cp = c & 31; }
    else if (c >= 0xe0 && c <= 0xef) { n = 3; cp = c & 15; }
    else if (c >= 0xf0 && c <= 0xf4) { n = 4; cp = c & 7; }
    else return 0;
    if (remaining < n) return 0;
    for (size_t i = 1; i < n; ++i) {
        const auto b = static_cast<uint8_t>(s[i]);
        if ((b & 0xc0) != 0x80) return 0;
        cp = (cp << 6) | (b & 63);
    }
    if ((n == 2 && cp < 0x80) || (n == 3 && cp < 0x800) ||
        (n == 4 && cp < 0x10000) || (cp >= 0xd800 && cp <= 0xdfff) || cp > 0x10ffff) return 0;
    return n;
}

inline bool sanitize(const std::string& text, char (&out)[MaxText + 1]) {
    if (text.empty() || text.size() > MaxText) return false;
    size_t at = 0, used = 0;
    bool audible = false;
    while (at < text.size()) {
        uint32_t cp;
        const size_t n = decode(text.data() + at, text.size() - at, cp);
        if (!n) return false;
        const bool control = cp < 32 || (cp >= 0x7f && cp <= 0x9f) ||
            (cp >= 0x200b && cp <= 0x200f) || (cp >= 0x2028 && cp <= 0x202e) ||
            (cp >= 0x2060 && cp <= 0x206f) || cp == 0xfeff;
        if (control) out[used++] = ' ';
        else {
            std::memcpy(out + used, text.data() + at, n); used += n;
            if (cp != 32 && cp != 0x3000) audible = true;
        }
        at += n;
    }
    out[used] = 0;
    return audible;
}

// Input is sanitized UTF-8. Preserve every byte, splitting at a sentence end
// when available, otherwise at 100 codepoints. Output always fits 401 bytes.
inline bool nextChunk(const char* text, size_t size, size_t& at, char (&out)[401]) {
    if (at >= size) return false;
    size_t end = at, sentence = at, points = 0;
    while (end < size && points < ChunkPoints) {
        uint32_t cp;
        const size_t n = decode(text + end, size - end, cp);
        if (!n) return false;
        end += n; ++points;
        if (cp == 0x3002 || cp == 0xff01 || cp == 0xff1f || cp == '.' || cp == '!' || cp == '?') sentence = end;
    }
    if (sentence > at) end = sentence;
    const size_t count = end - at;
    std::memcpy(out, text + at, count); out[count] = 0; at = end;
    return true;
}

struct Job {
    char id[65]{};
    char text[MaxText + 1]{};
    uint32_t generation = 0;
};

// Single main-task producer; caller serializes push/pop/toggle. Only the
// generation and mute flag may be inspected by the worker without that lock.
class Inbox {
public:
    enum Result { Queued, Invalid, Duplicate, Muted };
    Result push(const std::string& id, const std::string& text, bool local = false) {
        if (isMuted.load()) return Muted;
        if (id.empty() || id.size() > 64) return Invalid;
        if (!local) {
            if (id.size() != 64) return Invalid;
            for (char c : id) if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return Invalid;
            for (const auto& old : recent_) if (id == old) return Duplicate;
        }
        Job job;
        if (!sanitize(text, job.text)) return Invalid;
        std::memcpy(job.id, id.c_str(), id.size() + 1);
        job.generation = generation.load();
        if (count_ == Pending) { head_ = (head_ + 1) % Pending; --count_; }
        jobs_[(head_ + count_) % Pending] = job; ++count_;
        if (!local) {
            std::memcpy(recent_[recentAt_], job.id, sizeof(job.id));
            recentAt_ = (recentAt_ + 1) % 64;
        }
        return Queued;
    }
    bool pop(Job& job) {
        if (!count_) return false;
        job = jobs_[head_]; head_ = (head_ + 1) % Pending; --count_; return true;
    }
    bool toggle() {
        const bool value = !isMuted.load();
        isMuted.store(value); generation.fetch_add(1); head_ = count_ = 0;
        return value;
    }
    bool cancelled(const Job& job) const {
        return isMuted.load() || generation.load() != job.generation;
    }
    std::atomic<bool> isMuted{false};
    std::atomic<uint32_t> generation{0};
private:
    Job jobs_[Pending];
    char recent_[64][65]{};
    size_t head_ = 0, count_ = 0, recentAt_ = 0;
};
}} // namespace mesh::speech
