#pragma once
#include <ArduinoJson.h>
#include <mbedtls/sha256.h>
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace mesh {
namespace gateway {
// ArduinoJson's input byte/depth limits alone do not bound DOM expansion.
class JsonLimit : public ArduinoJson::Allocator {
    union Header { size_t size; std::max_align_t align; };
    size_t used = 0;
public:
    void* allocate(size_t n) override {
        if (n > 49152 - used) return nullptr;
        auto* h = static_cast<Header*>(std::malloc(sizeof(Header) + n));
        if (!h) return nullptr;
        h->size = n; used += n; return h + 1;
    }
    void deallocate(void* p) override {
        if (!p) return;
        auto* h = static_cast<Header*>(p) - 1; used -= h->size; std::free(h);
    }
    void* reallocate(void* p, size_t n) override {
        if (!p) return allocate(n);
        auto* h = static_cast<Header*>(p) - 1;
        if (n > 49152 - used + h->size) return nullptr;
        size_t before = h->size;
        h = static_cast<Header*>(std::realloc(h, sizeof(Header) + n));
        if (!h) return nullptr;
        h->size = n; used = used - before + n; return h + 1;
    }
};
inline bool relayOk(const uint8_t* bytes, size_t n, const std::string& id) {
    if (!n || n > 1024 || id.size() != 64) return false;
    JsonLimit limit; JsonDocument doc(&limit);
    if (deserializeJson(doc, bytes, n, DeserializationOption::NestingLimit(3))) return false;
    JsonArrayConst a = doc.as<JsonArrayConst>();
    return a.size() == 4 && a[0].is<const char*>() && a[0].as<std::string>() == "OK" &&
        a[1].is<const char*>() && a[1].as<std::string>() == id &&
        a[2].is<bool>() && a[2].as<bool>() && a[3].is<const char*>();
}
inline void cborHead(std::vector<uint8_t>& b, unsigned major, size_t n) {
    if (n < 24) b.push_back((major << 5) | n);
    else if (n <= 255) { b.push_back((major << 5) | 24); b.push_back(n); }
    else { b.push_back((major << 5) | 25); b.push_back(n >> 8); b.push_back(n); }
}
inline bool cbor(JsonVariantConst v, std::vector<uint8_t>& b, unsigned depth = 0) {
    if (depth > 8 || b.size() > 16384) return false;
    if (v.isNull()) b.push_back(0xf6);
    else if (v.is<const char*>()) {
        JsonString s = v.as<JsonString>();
        if (s.size() > 8192) return false;
        cborHead(b, 3, s.size()); b.insert(b.end(), s.c_str(), s.c_str() + s.size());
    } else if (v.is<JsonArrayConst>()) {
        auto a = v.as<JsonArrayConst>();
        if (a.size() > 32) return false;
        cborHead(b, 4, a.size());
        for (auto item : a) if (!cbor(item, b, depth + 1)) return false;
    } else if (v.is<JsonObjectConst>()) {
        auto o = v.as<JsonObjectConst>();
        if (o.size() > 16) return false;
        std::vector<std::string> keys;
        for (auto item : o) {
            if (item.key().size() > 128 || std::strlen(item.key().c_str()) != item.key().size()) return false;
            keys.emplace_back(item.key().c_str());
        }
        std::sort(keys.begin(), keys.end(), [](const std::string& a, const std::string& z) {
            return a.size() == z.size() ? a < z : a.size() < z.size();
        });
        cborHead(b, 5, keys.size());
        for (const auto& key : keys) {
            cborHead(b, 3, key.size()); b.insert(b.end(), key.begin(), key.end());
            if (!cbor(o[key], b, depth + 1)) return false;
        }
    } else return false; // PLC genesis uses only string/null/array/map, no numbers.
    return b.size() <= 16384;
}
inline bool genesis(JsonVariantConst v, std::vector<uint8_t>& encoded) {
    encoded.clear();
    auto o = v.as<JsonObjectConst>();
    if (o.size() != 7 || !o["type"].is<const char*>() || o["type"].as<std::string>() != "plc_operation" ||
        o["prev"].isUnbound() || !o["prev"].isNull() || !o["sig"].is<const char*>() ||
        o["sig"].as<JsonString>().size() != 86 || !o["rotationKeys"].is<JsonArrayConst>() ||
        !o["verificationMethods"].is<JsonObjectConst>() || !o["alsoKnownAs"].is<JsonArrayConst>() ||
        !o["services"].is<JsonObjectConst>()) return false;
    auto keys = o["rotationKeys"].as<JsonArrayConst>();
    if (keys.size() < 1 || keys.size() > 5) return false;
    for (auto k : keys) if (!k.is<const char*>() || k.as<std::string>().compare(0, 8, "did:key:") != 0) return false;
    return cbor(v, encoded);
}
inline std::string plcDid(const std::vector<uint8_t>& encoded) {
    uint8_t hash[32];
    if (mbedtls_sha256_ret(encoded.data(), encoded.size(), hash, 0)) return {};
    const char* alphabet = "abcdefghijklmnopqrstuvwxyz234567";
    std::string did = "did:plc:";
    // DID PLC is the first 24 base32 characters of the signed DAG-CBOR hash.
    for (unsigned i = 0; i < 24; ++i) {
        unsigned value = 0;
        for (unsigned j = 0; j < 5; ++j) {
            unsigned bit = i * 5 + j; value = (value << 1) | ((hash[bit / 8] >> (7 - bit % 8)) & 1);
        }
        did += alphabet[value];
    }
    return did;
}
inline uint64_t retryDelay(unsigned failures) {
    return std::min<uint64_t>(300000, uint64_t(5000) << std::min(failures, 6u));
}
}
}
