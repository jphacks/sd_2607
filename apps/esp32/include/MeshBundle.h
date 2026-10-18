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
    uint16_t kind = 0;
    // Local fresh ingress observation only; never serialized into the signed
    // event or restored as current authentication after reboot/mesh forwarding.
    std::string certifiedImporter;
    unsigned effectivePriority = 1;
};

// No wall-clock assumptions: a syntactically valid signed deadline is required,
// but its expiry is UNKNOWN. Call only from a worker, not the BLE callback/task.
// On failure info/out are cleared; error is cleared on success.
bool validateBundle(const uint8_t* data, size_t size, BundleInfo& info, std::string& error);

// Reconstruct the original signed Nostr fields (including typed tag reversal),
// reverify signature, and bound JSON to 64 KiB canonical + fixed object overhead.
// Unlike mesh forwarding, online export may use cold/hop-exhausted stored bytes.
// Oversize/invalid input clears outputs; no re-signing or tag changes.
bool exportEvent(const uint8_t* data, size_t size, BundleInfo& info,
                 std::string& json, std::string& error);

// Public verification-only secp256k1 primitives shared with wire-v1 ingress.
bool validSecpKey(const std::vector<uint8_t>& compressed);
bool verifyCompactEcdsa(const std::vector<uint8_t>& compressed,
                        const std::vector<uint8_t>& signature, const uint8_t hash[32]);
bool verifyCompactSchnorr(const std::vector<uint8_t>& pubkey,
                          const std::vector<uint8_t>& signature, const uint8_t hash[32]);
bool validateEventPayload(const std::vector<uint8_t>& payload, BundleInfo& info, std::string& error);
// Wrap an original canonical 7-element mesh event, never re-sign it. Anonymous
// BPv7 source, CRC32C, age=0/hop=0, max 24h lifetime. Outputs cleared on failure.
bool wrapEventPayload(const std::vector<uint8_t>& payload, uint64_t lifetimeMs,
                      std::vector<uint8_t>& bundle, BundleInfo& info, std::string& error);

// residenceMs is total monotonic residence since accepting THESE input bytes.
// Derive each retry from the stored original, never from a previous send view.
// Exhausted-hop bundles may be accepted but cannot be forwarded. Input must not
// alias out. Caller must discard expired stored entries, including after reboot
// if monotonic residence cannot be recovered.
bool forwardBundle(const uint8_t* data, size_t size, uint64_t residenceMs,
                   std::vector<uint8_t>& out, std::string& error);
} // namespace mesh
