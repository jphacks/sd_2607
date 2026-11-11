#pragma once

#include "MeshCbor.h"

#include <array>
#include <cstdint>
#include <string>
#include <vector>

namespace mesh {
namespace msg {
// Mesh §5: numeric-typed CBOR arrays shared by GATT/L2CAP/LAN/Nearby.
enum Type : uint64_t {
    HELLO = 0, INVENTORY = 1, WANT = 2, BEGIN = 3, DATA = 4, CREDIT = 5, RESULT = 6, RECONCILE = 7, FETCH = 8,
};
constexpr uint64_t PROTOCOL_VERSION = 1;
constexpr size_t HELLO_MAX_BYTES = 1024, MIN_MESSAGE_BYTES = 1024, MAX_MESSAGE_BYTES = 65536;
constexpr uint64_t ROUTE_GATT = 1;
constexpr size_t RECONCILE_OVERHEAD = 24, BEGIN_OVERHEAD = 64, DATA_OVERHEAD = 24;
enum Result : uint64_t { STORED = 0, ALREADY_HELD = 1, REJECTED = 2, UNAVAILABLE = 3, CANCELLED = 4 };
enum Reason : uint64_t { NONE = 0, RESOURCES = 1, SIZE = 2, INVALID = 3, EXPIRED = 4, POLICY = 5, MISSING = 6 };
enum TargetKind : uint64_t { TARGET_EVENT = 0, TARGET_TRANSFER = 1, TARGET_OBJECT = 2 };

using Id = std::array<uint8_t, 32>;

// Decoded view; byte fields point into the caller's message buffer.
struct Message {
    uint64_t type = 0;
    // HELLO
    uint8_t nonce[8] = {};
    uint64_t maxBundleBytes = 0, routes = 0, initialCredit = 0, maxMessageBytes = 0;
    std::vector<std::string> extensionTypes;
    // WANT/FETCH request ID, BEGIN/DATA/CREDIT transfer ID, RECONCILE ID.
    uint64_t first = 0;
    // BEGIN request ID.
    uint64_t second = 0;
    // INVENTORY/WANT IDs, FETCH hashes.
    std::vector<Id> ids;
    // BEGIN target (event ID or hash), RESULT target event ID / hash.
    Id target{};
    // BEGIN total length, CREDIT cumulative limit.
    uint64_t length = 0;
    // BEGIN optional first fragment, DATA fragment, RECONCILE payload.
    cbor::Slice bytes;
    bool hasBytes = false;
    // RESULT
    uint64_t targetKind = 0, side = 0, code = 0, reason = 0;
};

// False on a malformed known message (the link must then be closed). Unknown
// types decode successfully with only `type` set (mesh §5.2: ignored).
bool decode(const uint8_t* data, size_t size, Message& m, std::string& error);

std::vector<uint8_t> hello(const uint8_t nonce[8], uint64_t maxBundleBytes, uint64_t routes,
                           uint64_t initialCredit, uint64_t maxMessageBytes);
std::vector<uint8_t> ids(uint64_t type, const uint64_t* requestId, const std::vector<Id>& ids);
std::vector<uint8_t> begin(uint64_t transfer, uint64_t request, const Id& target, uint64_t length,
                           const uint8_t* fragment, size_t fragmentSize);
std::vector<uint8_t> data(uint64_t transfer, const uint8_t* fragment, size_t size);
std::vector<uint8_t> credit(uint64_t transfer, uint64_t limit);
std::vector<uint8_t> result(uint64_t targetKind, uint64_t side, uint64_t id, const Id* item,
                            uint64_t code, uint64_t reason);
std::vector<uint8_t> reconcile(uint64_t id, const std::vector<uint8_t>& payload);

// How many 32-byte IDs fit one list message under limit bytes.
inline size_t idsPerMessage(size_t limit) {
    const size_t n = (limit - 16) / 34;
    return n < 1 ? 1 : n;
}
} // namespace msg
} // namespace mesh
