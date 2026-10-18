#pragma once

#include "MeshBundle.h"
#include <functional>
#include <string>
#include <vector>

namespace mesh {

// Single-peer provisional mndt §9 JSON/raw-BPv7 exchange, NOT the standardized
// CBOR exchange. All methods, filesystem work, crypto and callbacks run on one
// serialized worker/main loop, never from BLE callbacks. No plaintext originate.
class Relay {
public:
    // node: random lowercase 16-hex per boot. send must synchronously copy the
    // complete frame into the transport queue and return false if not enqueued.
    // The pointer is valid only during the call. Callbacks must not reenter Relay.
    // Mounts LittleFS with formatOnFail=false; never formats. Bounded cache,
    // not permanent storage: ACK means durable acceptance, not indefinite retention.
    bool begin(const std::string& node,
               std::function<bool(const uint8_t*, size_t)> send,
               std::function<void(const std::string&)> note,
               std::function<void(const BundleInfo&)> accepted = {});
    void connected();
    // Transport must also discard queued TX/RX frames on disconnect, so an old
    // session's control/bundle bytes cannot leak into the next connected().
    void disconnected();
    void receive(const uint8_t* data, size_t size);
    void tick();
    // Validated live + cold entries; invalid diagnostic files still consume
    // disk capacity but are not included in this count.
    size_t storedCount() const { return entries_.size(); }

    // Parent must physically close the current link when true, then call
    // disconnected(). There is no BLE dependency or disconnect callback here.
    bool disconnectRequested() const { return disconnect_; }

    // /mesh/00 through /mesh/15 hold original wire bundles in bounded slots.
    // Entry retains its slot path; filenames never encode or truncate identity.
    // Restore verifies contents and indexes the FULL event ID from the bundle.
    // Verified cache entries may be replaced: cold/expired first, then oldest
    // live. Only a DIFFERENT requested, fully verified event, staged and synced
    // to .incoming, can trigger eviction. Invalid diagnostics are never deleted.
    // Every retained file counts toward capacity; .incoming is removed on boot.
    // Failed saves report stage + captured errno (0 if unavailable), ID prefix
    // and byte length through note; never body text and never an ACK.
    // Entries validated on begin() are retained for diagnosis but NEVER
    // advertised/forwarded in this boot: no trusted downtime/monotonic-age
    // recovery is available.
    // Only newly received entries get a live monotonic receivedMs. Duplicates
    // cannot replace files or refresh this timestamp. All files (even invalid
    // diagnostic files) count toward 16 committed files/384000 bytes, plus one
    // <=24000-byte staging file. Full caches can accept replacements; a cache
    // filled only with invalid diagnostics needs operator cleanup while off.
    // No persisted checkpoint or trusted wall-clock policy is claimed.
    // Local limits (not standardized wire claims): JSON <=10 KiB, <=128 distinct
    // lowercase-hex IDs; <=16 outstanding WANTs, <=128 completed IDs per session
    // (retained even after cache eviction, never reset by minute windows). WANTs
    // reserve history capacity; at 128 completions drain ACKs then request link
    // closure/reconnect, rather than forget IDs and replay writes. Cache slots
    // do not impose a sixteen-write session quota. 512 RX/TX messages per minute
    // and 4096 messages/4 MiB per direction per session. HELLO timeout 60 s;
    // WANT timeout 30 s; inventory every 10 s or after acceptance; queue refusal
    // retries every 250 ms, disconnect after 10 s stalled. Bundle retries after
    // 10 s without ACK, at most three enqueues. tick() sends at most one frame.

private:
    struct Entry {
        std::string id, path;
        uint64_t age = 0, lifetime = 0, receivedMs = 0;
        uint32_t hop = 0, hopLimit = 0;
        size_t size = 0;
        bool cold = true;
    };
    struct Request {
        std::string id;
        uint64_t sentMs = 0;
        bool issued = false;
    };
    struct Offer {
        std::string id;
        uint64_t sentMs = 0;
        unsigned attempts = 0;
    };

    std::string node_;
    std::function<bool(const uint8_t*, size_t)> send_;
    std::function<void(const std::string&)> note_;
    // Saved, verified NEW events only. Called on the main loop; must not reenter.
    std::function<void(const BundleInfo&)> accepted_;
    std::vector<Entry> entries_;
    size_t fileCount_ = 0, diskBytes_ = 0;
    bool ready_ = false, active_ = false, disconnect_ = false;
    bool helloPending_ = false, peerHello_ = false, inventoryDirty_ = false;
    size_t peerMax_ = 0;
    uint64_t beganMs_ = 0, inventoryMs_ = 0, windowMs_ = 0;
    uint64_t blockedMs_ = 0, retryMs_ = 0;
    bool blocked_ = false;
    size_t rxBytes_ = 0, txBytes_ = 0;
    unsigned rxWindow_ = 0, txWindow_ = 0, rxMessages_ = 0, txMessages_ = 0;
    std::vector<std::string> remoteIds_, advertised_, completed_, acks_;
    std::vector<Request> requests_;
    std::vector<Offer> offers_;

    void note(const std::string& text);
    void fail(const char* reason);
    void resetSession();
    void window(uint64_t now);
    const Entry* find(const std::string& id) const;
    bool eligible(const Entry& entry, uint64_t now) const;
    size_t victim(uint64_t now) const;
    bool restore();
    bool save(const uint8_t* data, size_t size, const BundleInfo& info, uint64_t receivedMs);
    bool read(const Entry& entry, std::vector<uint8_t>& bytes);
    void fillRequests();
    void control(const uint8_t* data, size_t size, uint64_t now);
    void bundle(const uint8_t* data, size_t size, uint64_t now);
    bool transmit(const uint8_t* data, size_t size, uint64_t now);
    bool sendIds(const char* type, const std::vector<std::string>& ids, uint64_t now);
};
} // namespace mesh
