#include "MeshMessages.h"

#include <algorithm>

namespace mesh {
namespace msg {
using cbor::Reader;
using cbor::Slice;

bool decode(const uint8_t* data, size_t size, Message& m, std::string& error) {
    m = {};
    if (!data || !size || size > MAX_MESSAGE_BYTES) { error = "message size"; return false; }
    Reader r(Slice(data, size));
    uint64_t count;
    if (!r.array(count) || count == UINT64_MAX || count < 1 || !r.uint(m.type)) { error = "expected definite message array"; return false; }
    auto idList = [&](std::vector<Id>& out) {
        uint64_t n;
        if (!r.array(n) || n == UINT64_MAX || n > 2048) return false;
        out.reserve(static_cast<size_t>(n));
        for (uint64_t i = 0; i < n; ++i) {
            Slice s;
            if (!r.bytes(2, s) || s.n != 32) return false;
            Id id;
            std::copy(s.p, s.p + 32, id.begin());
            if (std::find(out.begin(), out.end(), id) != out.end()) return false; // duplicate ID
            out.push_back(id);
        }
        return true;
    };
    auto id32 = [&](Id& out) {
        Slice s;
        if (!r.bytes(2, s) || s.n != 32) return false;
        std::copy(s.p, s.p + 32, out.begin());
        return true;
    };
    bool ok = true;
    switch (m.type) {
        case HELLO: {
            Slice nonce;
            uint64_t version, extensions;
            ok = count == 8 && size <= HELLO_MAX_BYTES && r.uint(version) && version == PROTOCOL_VERSION &&
                 r.bytes(2, nonce) && nonce.n == 8 && r.uint(m.maxBundleBytes) && m.maxBundleBytes >= 1 &&
                 m.maxBundleBytes <= 65536 && r.uint(m.routes) && r.uint(m.initialCredit) && m.initialCredit <= 65536 &&
                 r.uint(m.maxMessageBytes) && m.maxMessageBytes >= MIN_MESSAGE_BYTES && m.maxMessageBytes <= MAX_MESSAGE_BYTES &&
                 r.array(extensions) && extensions != UINT64_MAX && extensions <= 32;
            if (!ok) { error = "HELLO shape/version"; return false; }
            std::copy(nonce.p, nonce.p + 8, m.nonce);
            for (uint64_t i = 0; i < extensions; ++i) {
                // Each extension is a map with a "$type" text key; other keys are skipped.
                uint64_t pairs;
                std::string type;
                if (!r.head(5, pairs) || pairs > 64) { error = "HELLO extension"; return false; }
                for (uint64_t k = 0; k < pairs; ++k) {
                    Slice key, value;
                    if (r.major() != 3 || !r.bytes(3, key)) { error = "HELLO extension key"; return false; }
                    if (key.n == 5 && !std::memcmp(key.p, "$type", 5)) {
                        if (!r.bytes(3, value) || value.n == 0 || !type.empty()) { error = "HELLO extension $type"; return false; }
                        type.assign(reinterpret_cast<const char*>(value.p), value.n);
                    } else if (!r.skip()) { error = "HELLO extension value"; return false; }
                }
                if (type.empty() || std::find(m.extensionTypes.begin(), m.extensionTypes.end(), type) != m.extensionTypes.end()) {
                    error = "HELLO extension without/duplicate $type"; return false;
                }
                m.extensionTypes.push_back(type);
            }
            break;
        }
        case INVENTORY: ok = count == 2 && idList(m.ids); break;
        case WANT: case FETCH: ok = count == 3 && r.uint(m.first) && idList(m.ids); break;
        case BEGIN:
            ok = (count == 5 || count == 6) && r.uint(m.first) && r.uint(m.second) && id32(m.target) &&
                 r.uint(m.length) && m.length >= 1 && m.length <= 65536;
            if (ok && count == 6) { ok = r.bytes(2, m.bytes); m.hasBytes = true; }
            break;
        case DATA: ok = count == 3 && r.uint(m.first) && r.bytes(2, m.bytes); m.hasBytes = true; break;
        case CREDIT: ok = count == 3 && r.uint(m.first) && r.uint(m.length) && m.length <= 65536; break;
        case RESULT: {
            uint64_t n;
            ok = count == 4 && r.array(n) && n != UINT64_MAX && r.uint(m.targetKind) && r.uint(m.side) && m.side <= 1 &&
                 r.uint(m.first);
            if (ok) {
                if (m.targetKind == TARGET_TRANSFER) ok = n == 3;
                else if (m.targetKind == TARGET_EVENT || m.targetKind == TARGET_OBJECT) ok = n == 4 && id32(m.target);
                else ok = false;
            }
            ok = ok && r.uint(m.code) && m.code <= 0xffff && r.uint(m.reason) && m.reason <= 0xffff;
            break;
        }
        case RECONCILE: ok = count == 3 && r.uint(m.first) && r.bytes(2, m.bytes); m.hasBytes = true; break;
        default: return true; // Unknown type: ignored by the caller.
    }
    if (!ok || !r.done()) { error = "malformed message type " + std::to_string(m.type); return false; }
    return true;
}

namespace {
void id(std::vector<uint8_t>& out, const Id& value) { cbor::bytes(out, value.data(), value.size()); }
}

std::vector<uint8_t> hello(const uint8_t nonce[8], uint64_t maxBundleBytes, uint64_t routes,
                           uint64_t initialCredit, uint64_t maxMessageBytes) {
    std::vector<uint8_t> out;
    cbor::head(out, 4, 8); cbor::head(out, 0, HELLO); cbor::head(out, 0, PROTOCOL_VERSION);
    cbor::bytes(out, nonce, 8);
    cbor::head(out, 0, maxBundleBytes); cbor::head(out, 0, routes);
    cbor::head(out, 0, initialCredit); cbor::head(out, 0, maxMessageBytes);
    cbor::head(out, 4, 0);
    return out;
}
std::vector<uint8_t> ids(uint64_t type, const uint64_t* requestId, const std::vector<Id>& list) {
    std::vector<uint8_t> out;
    out.reserve(8 + list.size() * 34);
    cbor::head(out, 4, requestId ? 3 : 2); cbor::head(out, 0, type);
    if (requestId) cbor::head(out, 0, *requestId);
    cbor::head(out, 4, list.size());
    for (const Id& value : list) id(out, value);
    return out;
}
std::vector<uint8_t> begin(uint64_t transfer, uint64_t request, const Id& target, uint64_t length,
                           const uint8_t* fragment, size_t fragmentSize) {
    std::vector<uint8_t> out;
    out.reserve(BEGIN_OVERHEAD + fragmentSize);
    cbor::head(out, 4, fragmentSize ? 6 : 5); cbor::head(out, 0, BEGIN);
    cbor::head(out, 0, transfer); cbor::head(out, 0, request); id(out, target); cbor::head(out, 0, length);
    if (fragmentSize) cbor::bytes(out, fragment, fragmentSize);
    return out;
}
std::vector<uint8_t> data(uint64_t transfer, const uint8_t* fragment, size_t size) {
    std::vector<uint8_t> out;
    out.reserve(DATA_OVERHEAD + size);
    cbor::head(out, 4, 3); cbor::head(out, 0, DATA); cbor::head(out, 0, transfer);
    cbor::bytes(out, fragment, size);
    return out;
}
std::vector<uint8_t> credit(uint64_t transfer, uint64_t limit) {
    std::vector<uint8_t> out;
    cbor::head(out, 4, 3); cbor::head(out, 0, CREDIT); cbor::head(out, 0, transfer); cbor::head(out, 0, limit);
    return out;
}
std::vector<uint8_t> result(uint64_t targetKind, uint64_t side, uint64_t value, const Id* item,
                            uint64_t code, uint64_t reason) {
    std::vector<uint8_t> out;
    cbor::head(out, 4, 4); cbor::head(out, 0, RESULT);
    cbor::head(out, 4, item ? 4 : 3); cbor::head(out, 0, targetKind); cbor::head(out, 0, side); cbor::head(out, 0, value);
    if (item) id(out, *item);
    cbor::head(out, 0, code); cbor::head(out, 0, reason);
    return out;
}
std::vector<uint8_t> reconcile(uint64_t value, const std::vector<uint8_t>& payload) {
    std::vector<uint8_t> out;
    out.reserve(RECONCILE_OVERHEAD + payload.size());
    cbor::head(out, 4, 3); cbor::head(out, 0, RECONCILE); cbor::head(out, 0, value);
    cbor::bytes(out, payload.data(), payload.size());
    return out;
}
} // namespace msg
} // namespace mesh
