#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace mesh {
// Negentropy v1 (NIP-77) range-based set reconciliation over (created_at, event
// ID) items, the mesh §5.5 RECONCILE payload. Port of Android's Negentropy.kt so
// both sides produce identical fingerprints and bounds. One instance holds one
// side's candidate set for one reconciliation; the responder may use a fresh
// instance per message. frameLimit bounds each produced message (0 = none).
class Negentropy {
public:
    using Id = std::array<uint8_t, 32>;
    struct Item {
        uint64_t timestamp;
        Id id;
    };
    static constexpr uint8_t VERSION = 0x61;
    // Local bound on accumulated have/need IDs; extra IDs are ignored (they are
    // simply reconciled again at the next contact).
    static constexpr size_t MAX_RESULT_IDS = 256;

    Negentropy(std::vector<Item> items, size_t frameLimit);
    std::vector<uint8_t> initiate();
    // Processes one peer message. Returns false on a malformed message. done is
    // set when an initiator has finished (no message to send).
    bool reconcile(const uint8_t* data, size_t size, std::vector<uint8_t>& out, bool& done, std::string& error);
    const std::vector<Id>& have() const { return have_; }
    const std::vector<Id>& need() const { return need_; }

private:
    struct Bound {
        uint64_t timestamp;
        uint8_t prefix[32];
        size_t length;
    };
    std::vector<Item> items_;
    size_t frameLimit_;
    bool initiator_ = false;
    std::vector<Id> have_, need_;
    uint64_t lastIn_ = 0, lastOut_ = 0;

    bool exceeded(size_t size) const { return frameLimit_ && size + 200 > frameLimit_; }
    void splitRange(size_t lower, size_t upper, const Bound& upperBound, std::vector<uint8_t>& o);
    size_t findLowerBound(size_t from, size_t to, const Bound& bound) const;
    void fingerprint(size_t from, size_t to, uint8_t out[16]) const;
    void encodeBound(const Bound& bound, std::vector<uint8_t>& o);
    void encodeTimestamp(uint64_t timestamp, std::vector<uint8_t>& o);
};
} // namespace mesh
