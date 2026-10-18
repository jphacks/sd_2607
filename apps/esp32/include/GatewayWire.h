#pragma once
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <string>

namespace mesh { namespace gateway {
// Socket supplies now()/pause(), plus Arduino Client read/write/available/connected.
// All lengths come from local bounds, never a remote allocation request.
template<class Socket> class Wire {
    Socket& socket;
    uint64_t deadline;
public:
    struct Headers {
        int status = 0;
        int length = -1;
        bool chunked = false;
        std::string upgrade, connection, accept, contentType;
    };
    Wire(Socket& s, uint64_t end) : socket(s), deadline(end) {}
    void resetDeadline(uint64_t end) { deadline = end; }
    bool read(uint8_t* bytes, size_t n) {
        while (n && socket.now() < deadline) {
            int available = socket.available();
            if (available > 0) {
                int got = socket.read(bytes, std::min(n, static_cast<size_t>(available)));
                if (got <= 0) return false;
                bytes += got; n -= got;
            } else if (!socket.connected()) return false;
            else socket.pause();
        }
        return n == 0;
    }
    bool write(const uint8_t* bytes, size_t n) {
        while (n && socket.now() < deadline) {
            size_t sent = socket.write(bytes, std::min(n, size_t(512)));
            if (!sent) return false;
            bytes += sent; n -= sent;
        }
        return n == 0;
    }
    bool write(const std::string& s) { return write(reinterpret_cast<const uint8_t*>(s.data()), s.size()); }
    bool line(std::string& s, size_t& budget) {
        s.clear();
        while (budget && s.size() <= 512) {
            uint8_t b;
            if (!read(&b, 1)) return false;
            --budget;
            if (b == '\n') {
                if (s.empty() || s.back() != '\r') return false;
                s.pop_back(); return true;
            }
            if (b == 0) return false;
            s += static_cast<char>(b);
        }
        return false;
    }
    static std::string lower(std::string s) {
        for (char& c : s) if (c >= 'A' && c <= 'Z') c += 'a' - 'A';
        return s;
    }
    static std::string trim(std::string s) {
        const size_t a = s.find_first_not_of(" \t"), z = s.find_last_not_of(" \t");
        return a == std::string::npos ? "" : s.substr(a, z - a + 1);
    }
    static bool token(const std::string& list, const std::string& expected) {
        for (size_t at = 0; at < list.size();) {
            size_t end = list.find(',', at);
            if (trim(list.substr(at, end == std::string::npos ? end : end - at)) == expected) return true;
            if (end == std::string::npos) break;
            at = end + 1;
        }
        return false;
    }
    static bool number(const std::string& s, unsigned base, size_t& n) {
        if (s.empty() || s.size() > 8) return false;
        n = 0;
        for (char c : lower(s)) {
            unsigned d = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : 99;
            if (d >= base || n > (16384 - d) / base) return false;
            n = n * base + d;
        }
        return true;
    }
    bool headers(Headers& h) {
        h = {}; size_t budget = 4096; std::string s;
        if (!line(s, budget) || s.size() < 12 ||
            (s.compare(0, 9, "HTTP/1.1 ") && s.compare(0, 9, "HTTP/1.0 ")) ||
            s[9] < '1' || s[9] > '5' || s[10] < '0' || s[10] > '9' || s[11] < '0' || s[11] > '9' ||
            (s.size() > 12 && s[12] != ' ')) return false;
        h.status = (s[9] - '0') * 100 + (s[10] - '0') * 10 + s[11] - '0';
        for (unsigned count = 0; count < 64; ++count) {
            if (!line(s, budget)) return false;
            if (s.empty()) return !(h.chunked && h.length >= 0);
            size_t colon = s.find(':');
            if (colon == std::string::npos || !colon) return false;
            std::string key = lower(s.substr(0, colon)), value = trim(s.substr(colon + 1));
            if (key == "content-length") {
                size_t n;
                if (h.length >= 0 || !number(value, 10, n)) return false;
                h.length = static_cast<int>(n);
            } else if (key == "transfer-encoding") {
                if (h.chunked || lower(value) != "chunked") return false;
                h.chunked = true;
            } else if (key == "content-type") h.contentType = lower(value);
            else if (key == "upgrade") h.upgrade = lower(value);
            else if (key == "connection") {
                if (!h.connection.empty()) h.connection += ",";
                h.connection += lower(value);
            }
            else if (key == "sec-websocket-accept") {
                if (!h.accept.empty()) return false;
                h.accept = value;
            }
        }
        return false;
    }
    bool body(const Headers& h, std::string& out, size_t limit = 16384) {
        out.clear();
        if (limit > 16384 || (h.length >= 0 && size_t(h.length) > limit)) return false;
        if (h.chunked) {
            std::string s; size_t budget = 4096;
            for (unsigned chunks = 0; chunks < 256; ++chunks) {
                if (!line(s, budget)) return false;
                size_t n;
                if (!number(s.substr(0, s.find(';')), 16, n) || n > limit - out.size()) return false;
                if (!n) {
                    for (unsigned trailers = 0; trailers < 32; ++trailers) {
                        if (!line(s, budget)) return false;
                        if (s.empty()) return true;
                    }
                    return false;
                }
                size_t at = out.size(); out.resize(at + n);
                uint8_t crlf[2];
                if (!read(reinterpret_cast<uint8_t*>(&out[at]), n) || !read(crlf, 2) || crlf[0] != '\r' || crlf[1] != '\n') return false;
            }
            return false;
        }
        if (h.length >= 0) {
            out.resize(h.length);
            return out.empty() || read(reinterpret_cast<uint8_t*>(&out[0]), out.size());
        }
        // Connection: close delimitation, bounded even if the server never closes.
        while (socket.now() < deadline) {
            if (socket.available()) {
                uint8_t b;
                if (out.size() == limit || !read(&b, 1)) return false;
                out += static_cast<char>(b);
            } else if (!socket.connected()) return true;
            else socket.pause();
        }
        return false;
    }
    bool masked(unsigned opcode, const uint8_t* bytes, size_t n, uint32_t mask) {
        if (n > 66000) return false;
        uint8_t header[14]; size_t count = 0;
        header[count++] = 0x80 | opcode;
        if (n < 126) header[count++] = 0x80 | n;
        else if (n <= 65535) {
            header[count++] = 0xfe; header[count++] = n >> 8; header[count++] = n;
        } else {
            header[count++] = 0xff;
            for (unsigned i = 8; i; --i) header[count++] = uint64_t(n) >> ((i - 1) * 8);
        }
        uint8_t key[4]; std::memcpy(key, &mask, 4);
        std::memcpy(header + count, key, 4); count += 4;
        if (!write(header, count)) return false;
        uint8_t chunk[512];
        for (size_t at = 0; at < n;) {
            size_t size = std::min(sizeof(chunk), n - at);
            for (size_t i = 0; i < size; ++i) chunk[i] = bytes[at + i] ^ key[(at + i) % 4];
            if (!write(chunk, size)) return false;
            at += size;
        }
        return true;
    }
    bool frame(unsigned& opcode, std::string& payload, size_t limit = 1024) {
        uint8_t h[2];
        if (!read(h, 2) || (h[0] & 0xf0) != 0x80 || (h[1] & 0x80)) return false;
        opcode = h[0] & 15;
        size_t n = h[1] & 127;
        if (n == 127) return false; // Local inbound cap <=10 KiB; no fragmentation/extensions.
        if (n == 126) {
            if (!read(h, 2)) return false;
            n = (size_t(h[0]) << 8) | h[1];
            if (n < 126) return false;
        }
        if (limit > 10000 || n > limit || (opcode >= 8 && n > 125) ||
            (opcode != 1 && opcode != 8 && opcode != 9 && opcode != 10)) return false;
        payload.resize(n);
        return !n || read(reinterpret_cast<uint8_t*>(&payload[0]), n);
    }
};
}}
