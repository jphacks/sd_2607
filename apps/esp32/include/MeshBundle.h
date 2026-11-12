#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace mesh {
// Local receive limit for a whole encoded Bundle (mesh §6 allows up to 64 KiB;
// HELLO advertises this smaller value). Payload limit is the protocol's 32 KiB.
constexpr size_t MAX_BUNDLE_BYTES = 24000;
constexpr size_t MAX_PAYLOAD_BYTES = 32768;
constexpr uint64_t MAX_RESIDENCE_MS = 86400000;
constexpr uint32_t MAX_HOPS = 8;

struct BundleInfo {
    std::string id, text, pubkey;
    // Second `t` tag of a kind 8173 control event, e.g. "mosimo.jisaku.dev/plc".
    std::string topic;
    // delete §1 / report §1: the `e` target (lowercase hex event ID) of a kind 5 deletion
    // request or kind 1984 report, and the reported author (`p`) of a report. Empty otherwise.
    std::string target, reported;
    uint64_t createdAt = 0;
    // Signed delivery deadline (mosimo:exp) in Unix seconds. Untrusted clock:
    // informational only on this device.
    uint64_t expiresAt = 0;
    uint64_t age = 0, lifetime = 0;
    uint32_t hop = 0, hopLimit = 0;
    uint16_t kind = 0;
};

// Mesh §2: BPv7 Bundle with destination dtn://mosimo.jisaku.dev/~post whose
// payload is the 7-element mesh CBOR v1 event. The Nostr signature is checked
// over the restored original tags. A signed mosimo:exp deadline is required,
// but its expiry is UNKNOWN without a trusted clock. Key-attestation references
// (§2.1.3) are not restorable here and therefore fail signature verification.
// On failure info is cleared and error set; error is cleared on success.
bool validateBundle(const uint8_t* data, size_t size, BundleInfo& info, std::string& error);

// Reconstruct the original NIP-01 JSON (typed tag values and omitted fixed tags
// restored), reverify the signature. No re-signing or tag changes.
bool exportEvent(const uint8_t* data, size_t size, BundleInfo& info,
                 std::string& json, std::string& error);

// residenceMs is total monotonic residence since accepting THESE input bytes.
// Derive each send view from the stored original: hop+1, age+residence, the
// previous-node block dropped. Exhausted/expired bundles are refused.
bool forwardBundle(const uint8_t* data, size_t size, uint64_t residenceMs,
                   std::vector<uint8_t>& out, std::string& error);

std::string hex(const uint8_t* p, size_t n);
bool verifySchnorr(const uint8_t pub[32], const uint8_t sig[64], const uint8_t message[32]);
} // namespace mesh
