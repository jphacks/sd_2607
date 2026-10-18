#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace mesh {
constexpr size_t MAX_BUNDLE_BYTES = 24000;
constexpr uint64_t MAX_RESIDENCE_MS = 86400000;
constexpr uint32_t MAX_HOPS = 8;

struct BundleInfo {
    std::string id, text;
    uint64_t age = 0, lifetime = 0;
    uint32_t hop = 0, hopLimit = 0;
};

// No wall-clock assumptions: a syntactically valid signed deadline is required,
// but its expiry is UNKNOWN. Call only from a worker, not the BLE callback/task.
// On failure info/out are cleared; error is cleared on success.
bool validateBundle(const uint8_t* data, size_t size, BundleInfo& info, std::string& error);

// residenceMs is total monotonic residence since accepting THESE input bytes.
// Derive each retry from the stored original, never from a previous send view.
// Exhausted-hop bundles may be accepted but cannot be forwarded. Input must not
// alias out. Caller must discard expired stored entries, including after reboot
// if monotonic residence cannot be recovered.
bool forwardBundle(const uint8_t* data, size_t size, uint64_t residenceMs,
                   std::vector<uint8_t>& out, std::string& error);
} // namespace mesh
