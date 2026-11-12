#pragma once

// Minimal bounded CBOR helpers shared by the event codec, the exchange messages
// and the GATT byte-stream framing. No recursion on attacker-chosen depth beyond
// MAX_DEPTH, no allocation from claimed lengths.

#include <cstddef>
#include <cstdint>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace mesh {
namespace cbor {
constexpr uint64_t SIGNED_MAX = 0x7fffffffffffffffULL;
constexpr unsigned MAX_DEPTH = 16;

struct Slice {
    const uint8_t* p;
    size_t n;
    Slice(const uint8_t* data = nullptr, size_t size = 0) : p(data), n(size) {}
};

inline bool utf8(Slice s) {
    for (size_t i = 0; i < s.n;) {
        uint32_t c = s.p[i++];
        if (c < 128) continue;
        unsigned more;
        uint32_t minimum;
        if (c >= 0xc2 && c <= 0xdf) { more = 1; minimum = 0x80; c &= 31; }
        else if (c >= 0xe0 && c <= 0xef) { more = 2; minimum = 0x800; c &= 15; }
        else if (c >= 0xf0 && c <= 0xf4) { more = 3; minimum = 0x10000; c &= 7; }
        else return false;
        if (more > s.n - i) return false;
        while (more--) {
            uint8_t b = s.p[i++];
            if ((b & 0xc0) != 0x80) return false;
            c = (c << 6) | (b & 63);
        }
        if (c < minimum || c > 0x10ffff || (c >= 0xd800 && c <= 0xdfff)) return false;
    }
    return true;
}

// Cursor over one buffer. Integers above 2^63-1 are rejected (Android uses Long).
// canonical=true additionally rejects non-shortest heads and indefinite lengths.
struct Reader {
    const uint8_t* p;
    size_t n, at = 0, items = 0;
    bool canonical;
    Reader(Slice s, bool c = false) : p(s.p), n(s.n), canonical(c) {}
    bool more() const { return at < n; }
    int major() const { return at < n ? p[at] >> 5 : -1; }
    bool head(unsigned major, uint64_t& value, bool allowIndef = false) {
        if (at >= n || ++items > 8192) return false;
        unsigned b = p[at++], ai = b & 31;
        if ((b >> 5) != major) return false;
        if (ai < 24) { value = ai; return true; }
        if (ai == 31 && allowIndef && !canonical) { value = UINT64_MAX; return true; }
        if (ai > 27) return false;
        unsigned width = 1u << (ai - 24);
        if (width > n - at) return false;
        value = 0;
        for (unsigned i = 0; i < width; ++i) value = (value << 8) | p[at++];
        if (value > SIGNED_MAX) return false;
        if (canonical && (value < 24 || (width > 1 && value <= 0xff) ||
                          (width > 2 && value <= 0xffff) || (width > 4 && value <= 0xffffffffULL))) return false;
        return true;
    }
    bool uint(uint64_t& v) { return head(0, v); }
    bool array(uint64_t& count) { return head(4, count, true); }
    bool array(size_t expected, uint64_t& count) { return array(count) && (count == expected || count == UINT64_MAX); }
    // Indefinite containers end with 0xff; definite ones need no terminator.
    bool end(uint64_t count) {
        if (count != UINT64_MAX) return true;
        return at < n && p[at++] == 0xff;
    }
    bool atBreak() const { return at < n && p[at] == 0xff; }
    bool done() const { return at == n; }
    bool bytes(unsigned major, Slice& s) {
        uint64_t length;
        if (!head(major, length) || length > n - at) return false;
        s = {p + at, static_cast<size_t>(length)};
        at += s.n;
        return major != 3 || utf8(s);
    }
    // Skips one well-formed item of any type (definite or indefinite).
    bool skip(unsigned depth = 0) {
        if (depth > MAX_DEPTH || at >= n) return false;
        const unsigned m = p[at] >> 5, ai = p[at] & 31;
        uint64_t v;
        switch (m) {
            case 0: case 1: return head(m, v);
            case 2: case 3: {
                if (ai == 31) return false; // Indefinite strings are unused here.
                Slice s;
                return bytes(m, s);
            }
            case 4: case 5: {
                if (!head(m, v, true)) return false;
                if (v == UINT64_MAX) {
                    while (!atBreak()) if (!skip(depth + 1) || (m == 5 && !skip(depth + 1))) return false;
                    ++at;
                    return true;
                }
                if (v > 4096) return false;
                for (uint64_t i = 0; i < v * (m == 5 ? 2 : 1); ++i) if (!skip(depth + 1)) return false;
                return true;
            }
            case 6: return head(6, v) && skip(depth + 1);
            default: {
                ++at; ++items;
                if (ai < 24) return true;
                const size_t width = ai == 24 ? 1 : ai == 25 ? 2 : ai == 26 ? 4 : ai == 27 ? 8 : 0;
                if (!width || width > n - at) return false;
                at += width;
                return true;
            }
        }
    }
};

// Length of the first complete item of a byte stream (RFC 8742 sequence).
// Returns 1 with length set, 0 if more bytes are needed, -1 if malformed or if
// a declared string length already exceeds limit (stream sync is lost, mesh §7).
inline int measure(const uint8_t* data, size_t size, size_t& length, size_t limit = SIZE_MAX) {
    struct Walk {
        const uint8_t* p; size_t n, at = 0, limit; unsigned items = 0;
        Walk(const uint8_t* data, size_t size, size_t max) : p(data), n(size), limit(max) {}
        int item(unsigned depth) {
            if (depth > MAX_DEPTH || ++items > 8192) return -1;
            if (at >= n) return 0;
            const unsigned b = p[at++], m = b >> 5, ai = b & 31;
            uint64_t v = ai;
            if (ai == 31) {
                if (m != 4 && m != 5) return -1;
                for (;;) {
                    if (at >= n) return 0;
                    if (p[at] == 0xff) { ++at; return 1; }
                    for (unsigned k = 0; k < (m == 5 ? 2u : 1u); ++k) { int r = item(depth + 1); if (r <= 0) return r; }
                }
            }
            if (ai > 27) return -1;
            if (ai >= 24) {
                const size_t width = size_t(1) << (ai - 24);
                if (width > n - at) return 0;
                v = 0;
                for (size_t i = 0; i < width; ++i) v = (v << 8) | p[at++];
            }
            if (m == 7) return 1;
            if (m == 2 || m == 3) {
                if (v > limit) return -1;
                if (v > n - at) return 0;
                at += static_cast<size_t>(v);
                return 1;
            }
            if (m == 4 || m == 5) {
                if (v > 4096) return -1;
                for (uint64_t i = 0; i < v * (m == 5 ? 2 : 1); ++i) { int r = item(depth + 1); if (r <= 0) return r; }
                return 1;
            }
            if (m == 6) return item(depth + 1);
            return 1;
        }
    } walk(data, size, limit);
    const int result = walk.item(0);
    if (result == 1) length = walk.at;
    return result;
}

inline void head(std::vector<uint8_t>& out, unsigned major, uint64_t value) {
    if (value < 24) { out.push_back(static_cast<uint8_t>((major << 5) | value)); return; }
    unsigned width = value <= 0xff ? 1 : value <= 0xffff ? 2 : value <= 0xffffffffULL ? 4 : 8;
    out.push_back(static_cast<uint8_t>((major << 5) | (width == 1 ? 24 : width == 2 ? 25 : width == 4 ? 26 : 27)));
    for (unsigned i = width; i; --i) out.push_back(static_cast<uint8_t>(value >> ((i - 1) * 8)));
}
inline void append(std::vector<uint8_t>& out, Slice s) { if (s.n) out.insert(out.end(), s.p, s.p + s.n); }
inline void bytes(std::vector<uint8_t>& out, const uint8_t* p, size_t n) { head(out, 2, n); append(out, {p, n}); }
inline void text(std::vector<uint8_t>& out, const char* s) {
    const size_t n = std::strlen(s);
    head(out, 3, n); append(out, {reinterpret_cast<const uint8_t*>(s), n});
}
} // namespace cbor
} // namespace mesh
