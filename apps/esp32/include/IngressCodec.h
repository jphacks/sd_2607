#pragma once
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace mesh { namespace ingress {
constexpr size_t JSON_LIMIT = 6144, CBOR_LIMIT = 8192, SNAPSHOT_LIMIT = 4096;
constexpr uint64_t SAFE = 9007199254740991ULL;
// Small tree for strict JSON and canonical CBOR. No DOM supplied by a relay can
// choose allocation counts: 128 nodes, 12 levels, 12 KiB decoded string data.
struct Value {
    enum Type { Null, Uint, Text, Bytes, Array, Map, Boolean, Link } type = Null;
    uint64_t n = 0;
    std::string s;
    std::vector<size_t> children;
};
struct Document {
    std::vector<Value> values;
    size_t root = 0, textBytes = 0;
    bool ok = true;
    size_t add(Value::Type type) {
        if (values.size() >= 128) { ok = false; return 0; }
        values.emplace_back(); values.back().type = type; return values.size() - 1;
    }
    size_t text(const std::string& s, Value::Type type = Value::Text) {
        if (s.size() > 12288 - textBytes) { ok = false; return 0; }
        textBytes += s.size(); size_t i = add(type); if (ok) values[i].s = s; return i;
    }
    size_t number(uint64_t n) { size_t i = add(Value::Uint); if (ok) values[i].n = n; return i; }
    const Value& at(size_t i) const { return values[i]; }
    size_t get(size_t i, const std::string& key) const {
        if (i >= values.size() || at(i).type != Value::Map) return values.size();
        const auto& c = at(i).children;
        for (size_t j = 0; j < c.size(); j += 2) if (at(c[j]).s == key) return c[j + 1];
        return values.size();
    }
    bool has(size_t i, const std::string& key) const { return get(i, key) < values.size(); }
    std::string str(size_t i, const std::string& key) const {
        size_t j = get(i, key); return j < values.size() && at(j).type == Value::Text ? at(j).s : "";
    }
    bool fields(size_t i, std::initializer_list<const char*> names) const {
        if (i >= values.size() || at(i).type != Value::Map || at(i).children.size() != names.size() * 2) return false;
        for (auto name : names) if (!has(i, name)) return false;
        return true;
    }
};
inline bool utf8(const std::string& s) {
    for (size_t i = 0; i < s.size();) {
        uint32_t c = static_cast<uint8_t>(s[i++]); if (c < 128) continue;
        unsigned more; uint32_t min;
        if (c >= 0xc2 && c <= 0xdf) { more = 1; min = 0x80; c &= 31; }
        else if (c >= 0xe0 && c <= 0xef) { more = 2; min = 0x800; c &= 15; }
        else if (c >= 0xf0 && c <= 0xf4) { more = 3; min = 0x10000; c &= 7; }
        else return false;
        if (more > s.size() - i) return false;
        while (more--) { unsigned b = static_cast<uint8_t>(s[i++]); if ((b & 0xc0) != 0x80) return false; c = (c << 6) | (b & 63); }
        if (c < min || c > 0x10ffff || (c >= 0xd800 && c <= 0xdfff)) return false;
    }
    return true;
}
inline void head(std::vector<uint8_t>& b, unsigned major, uint64_t n) {
    if (n < 24) { b.push_back((major << 5) | n); return; }
    unsigned width = n <= 255 ? 1 : n <= 65535 ? 2 : n <= 0xffffffffULL ? 4 : 8;
    b.push_back((major << 5) | (width == 1 ? 24 : width == 2 ? 25 : width == 4 ? 26 : 27));
    for (unsigned i = width; i; --i) b.push_back(n >> ((i - 1) * 8));
}
inline bool encode(const Document& d, size_t i, std::vector<uint8_t>& b, const char* omit = nullptr, unsigned depth = 0) {
    if (!d.ok || i >= d.values.size() || depth > 12 || b.size() > CBOR_LIMIT) return false;
    const auto& v = d.at(i);
    switch (v.type) {
    case Value::Null: b.push_back(0xf6); break;
    case Value::Boolean: b.push_back(v.n ? 0xf5 : 0xf4); break;
    case Value::Uint: if (v.n > SAFE) return false; head(b, 0, v.n); break;
    case Value::Text: case Value::Bytes:
        head(b, v.type == Value::Text ? 3 : 2, v.s.size()); b.insert(b.end(), v.s.begin(), v.s.end()); break;
    case Value::Link:
        head(b, 6, 42); head(b, 2, v.s.size()); b.insert(b.end(), v.s.begin(), v.s.end()); break;
    case Value::Array:
        head(b, 4, v.children.size());
        for (auto child : v.children) if (!encode(d, child, b, nullptr, depth + 1)) return false;
        break;
    case Value::Map: {
        std::vector<size_t> pairs;
        for (size_t j = 0; j < v.children.size(); j += 2) if (!omit || d.at(v.children[j]).s != omit) pairs.push_back(j);
        std::sort(pairs.begin(), pairs.end(), [&](size_t a, size_t z) {
            const auto& x = d.at(v.children[a]).s; const auto& y = d.at(v.children[z]).s;
            return x.size() == y.size() ? x < y : x.size() < y.size();
        });
        head(b, 5, pairs.size());
        for (auto p : pairs) if (!encode(d, v.children[p], b, nullptr, depth + 1) || !encode(d, v.children[p + 1], b, nullptr, depth + 1)) return false;
        break;
    }
    }
    return b.size() <= CBOR_LIMIT;
}
struct Decoder {
    Document& d; const uint8_t* p; size_t n, at = 0; bool links;
    Decoder(Document& doc, const uint8_t* bytes, size_t size, bool allowLinks) : d(doc), p(bytes), n(size), links(allowLinks) {}
    bool length(unsigned ai, uint64_t& v) {
        if (ai < 24) { v = ai; return true; }
        if (ai > 27) return false;
        unsigned width = 1u << (ai - 24); if (width > n - at) return false;
        v = 0; for (unsigned i = 0; i < width; ++i) v = (v << 8) | p[at++];
        return v <= SAFE && v >= 24 && !(width > 1 && v <= 255) && !(width > 2 && v <= 65535) && !(width > 4 && v <= 0xffffffffULL);
    }
    bool value(size_t& out, unsigned depth = 0) {
        if (depth > 12 || at >= n || !d.ok) return false;
        unsigned b = p[at++], major = b >> 5; uint64_t v;
        if (major == 7) {
            if (b != 0xf4 && b != 0xf5 && b != 0xf6) return false;
            out = d.add(b == 0xf6 ? Value::Null : Value::Boolean); d.values[out].n = b == 0xf5; return d.ok;
        }
        if (!length(b & 31, v)) return false;
        if (major == 0) { out = d.number(v); return d.ok; }
        if (major == 2 || major == 3) {
            if (v > n - at) return false;
            std::string s(reinterpret_cast<const char*>(p + at), v); at += v;
            if (major == 3 && !utf8(s)) return false;
            out = d.text(s, major == 2 ? Value::Bytes : Value::Text); return d.ok;
        }
        if (major == 6 && links && v == 42) {
            size_t child; if (!value(child, depth + 1) || d.at(child).type != Value::Bytes) return false;
            out = child; d.values[out].type = Value::Link; return true;
        }
        if ((major != 4 && major != 5) || v > 128) return false;
        out = d.add(major == 4 ? Value::Array : Value::Map); if (!d.ok) return false;
        for (uint64_t j = 0; j < v; ++j) {
            size_t key, child;
            if (major == 5) {
                if (!value(key, depth + 1) || d.at(key).type != Value::Text) return false;
                if (d.has(out, d.at(key).s)) return false;
                // Do not append a key without its value: get() traverses pairs.
                if (!value(child, depth + 1)) return false;
                d.values[out].children.push_back(key); d.values[out].children.push_back(child);
            } else { if (!value(child, depth + 1)) return false; d.values[out].children.push_back(child); }
        }
        return d.ok;
    }
};
inline bool decode(const std::vector<uint8_t>& bytes, Document& d, bool links = false) {
    d = {}; if (bytes.empty() || bytes.size() > CBOR_LIMIT) return false;
    Decoder r(d, bytes.data(), bytes.size(), links);
    if (!r.value(d.root) || r.at != bytes.size()) return false;
    std::vector<uint8_t> canonical;
    return encode(d, d.root, canonical) && canonical == bytes;
}
struct JsonDecoder {
    Document& d; const std::string& s; size_t at = 0;
    JsonDecoder(Document& doc, const std::string& json) : d(doc), s(json) {}
    void ws() { while (at < s.size() && (s[at] == ' ' || s[at] == '\n' || s[at] == '\r' || s[at] == '\t')) ++at; }
    bool take(char c) { ws(); if (at == s.size() || s[at] != c) return false; ++at; return true; }
    bool hex4(uint32_t& v) {
        v = 0; for (unsigned j = 0; j < 4; ++j) {
            if (at == s.size()) return false;
            char c = s[at++]; unsigned x = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : 99;
            if (x > 15) return false;
            v = (v << 4) | x;
        } return true;
    }
    static void codepoint(std::string& b, uint32_t c) {
        if (c < 128) b += char(c);
        else if (c < 2048) { b += char(0xc0 | (c >> 6)); b += char(0x80 | (c & 63)); }
        else if (c < 65536) { b += char(0xe0 | (c >> 12)); b += char(0x80 | ((c >> 6) & 63)); b += char(0x80 | (c & 63)); }
        else { b += char(0xf0 | (c >> 18)); b += char(0x80 | ((c >> 12) & 63)); b += char(0x80 | ((c >> 6) & 63)); b += char(0x80 | (c & 63)); }
    }
    bool text(size_t& out) {
        if (!take('"')) return false;
        std::string b;
        while (at < s.size()) {
            unsigned c = static_cast<uint8_t>(s[at++]);
            if (c == '"') { if (!utf8(b)) return false; out = d.text(b); return d.ok; }
            if (c < 32) return false;
            if (c != '\\') b += char(c);
            else {
                if (at == s.size()) return false;
                char e = s[at++];
                if (e == '"' || e == '\\' || e == '/') b += e;
                else if (e == 'b') b += '\b'; else if (e == 'f') b += '\f'; else if (e == 'n') b += '\n'; else if (e == 'r') b += '\r'; else if (e == 't') b += '\t';
                else if (e == 'u') {
                    uint32_t cp; if (!hex4(cp)) return false;
                    if (cp >= 0xd800 && cp <= 0xdbff) {
                        if (s.size() - at < 6 || s[at++] != '\\' || s[at++] != 'u') return false;
                        uint32_t low; if (!hex4(low) || low < 0xdc00 || low > 0xdfff) return false;
                        cp = 0x10000 + ((cp - 0xd800) << 10) + low - 0xdc00;
                    } else if (cp >= 0xdc00 && cp <= 0xdfff) return false;
                    codepoint(b, cp);
                } else return false;
            }
        } return false;
    }
    bool value(size_t& out, unsigned depth = 0) {
        ws(); if (depth > 12 || at == s.size() || !d.ok) return false;
        char c = s[at];
        if (c == '"') return text(out);
        if (c == '[' || c == '{') {
            ++at; out = d.add(c == '[' ? Value::Array : Value::Map); if (!d.ok) return false;
            const char end = c == '[' ? ']' : '}'; ws(); if (at < s.size() && s[at] == end) { ++at; return true; }
            for (unsigned count = 0; count < 128; ++count) {
                size_t key, child;
                if (c == '{') {
                    if (!text(key) || d.has(out, d.at(key).s) || !take(':') || !value(child, depth + 1)) return false;
                    d.values[out].children.push_back(key); d.values[out].children.push_back(child);
                } else { if (!value(child, depth + 1)) return false; d.values[out].children.push_back(child); }
                ws(); if (at < s.size() && s[at] == end) { ++at; return true; }
                if (!take(',')) return false;
            } return false;
        }
        if (c >= '0' && c <= '9') {
            size_t start = at; uint64_t v = 0;
            while (at < s.size() && s[at] >= '0' && s[at] <= '9') {
                unsigned x = s[at++] - '0'; if (v > (SAFE - x) / 10) return false; v = v * 10 + x;
            }
            if (at - start > 1 && c == '0') return false;
            out = d.number(v); return d.ok;
        }
        for (auto literal : {"true", "false", "null"}) {
            size_t n = std::strlen(literal);
            if (s.compare(at, n, literal) == 0) {
                at += n; out = d.add(literal[0] == 'n' ? Value::Null : Value::Boolean); d.values[out].n = literal[0] == 't'; return d.ok;
            }
        } return false;
    }
};
inline bool json(const std::string& s, Document& d) {
    d = {}; if (s.empty() || s.size() > JSON_LIMIT) return false;
    JsonDecoder r(d, s); if (!r.value(d.root)) return false; r.ws(); return d.ok && r.at == s.size();
}
}}
