#pragma once

#include "MeshBundle.h"
#include "MeshMessages.h"
#include "Negentropy.h"

#include <array>
#include <deque>
#include <functional>
#include <memory>
#include <string>
#include <vector>

namespace mesh {

// Mesh §4-5 contact exchange with one peer at a time: HELLO -> RECONCILE
// (Negentropy) -> INVENTORY/WANT -> BEGIN/DATA with CREDIT -> RESULT.
// All methods, filesystem work, crypto and callbacks run on one serialized
// main loop, never from BLE callbacks.
//
// Local profile (advertised in HELLO, not a protocol claim): Bundle <= 24000 B,
// message <= 4096 B, initial credit 4096 B, GATT only, no HELLO extensions,
// no key-attestation reference restoration (FETCH answers "unavailable").
class Relay {
public:
    static constexpr size_t MESSAGE_LIMIT = 4096;
    static constexpr size_t INITIAL_CREDIT = 4096;

    // send must synchronously copy one complete message into the transport
    // queue and return false (backpressure) if it cannot. Callbacks must not
    // reenter Relay. Mounts LittleFS with formatOnFail=false; never formats.
    bool begin(std::function<bool(const uint8_t*, size_t)> send,
               std::function<void(const std::string&)> note,
               std::function<void(const BundleInfo&)> accepted = {});
    void connected();
    // Transport must also discard queued TX/RX bytes on disconnect.
    void disconnected();
    // One complete common exchange message (the transport splits the CBOR sequence).
    void receive(const uint8_t* data, size_t size);
    void tick();
    // Parent must physically close the current link when true, then call disconnected().
    bool disconnectRequested() const { return disconnect_; }
    bool sessionActive() const { return active_ && peerHello_; }

    // Validated live + cold entries.
    size_t storedCount() const { return entries_.size(); }
    // Entries currently offered to peers (mesh §5.5 candidate set).
    size_t candidateCount() const;
    // Local display only: revalidates each stored original (live and cold) and passes it
    // to visit with its cold flag. Unreadable or invalid files are skipped. visit must not
    // reenter Relay. Not a forwarding decision; cold entries stay cold.
    void forEachStored(const std::function<void(const BundleInfo&, bool cold)>& visit);
    // mesh.adv §1: 3-byte candidate-set summary + extension flags byte.
    void advertisement(uint8_t out[4]) const;

    // Enable before BLE starts. Pending exportable entries cannot be evicted,
    // even cold/expired. Completion is RAM-only: reboot safely resends originals.
    void enableGateway() { gateway_ = true; }
    std::vector<std::string> gatewayPending() const;
    bool gatewayRead(const std::string& id, std::vector<uint8_t>& bytes);
    void gatewayDelivered(const std::string& id);

    // Storage: /mesh/00../mesh/15 hold original wire bundles. Restore verifies
    // contents and indexes the FULL event ID from the bundle. Entries restored on
    // begin() are cold: residence during power-off is unknown, so (mesh §11, unknown
    // clock) they are relayed only within a local window counted from boot
    // (RESTORED_RESIDENCE_MS), still bounded by their own lifetime and hop limit.
    // Verified entries may be replaced (cold/expired first, then the lowest effective
    // priority, oldest received among equals; mesh §11). A full store of forwardable
    // entries refuses an event below all of them (reason 1) instead of evicting.
    // Effective priority is min(hint, 1): without a trusted clock no sender can be
    // authorized here (importer §3, priority §2), so only bulk (0) vs normal (1) differ.
    // Offers and served WANTs go higher priority, then newer, first (mesh §9);
    // evicted IDs are journaled in /mesh-seen BEFORE eviction so they are not
    // requested again; a full journal drops its older half (a forgotten ID may be
    // fetched once more, which is harmless). RESULT "stored" is sent only after save + readback.
    //
    // delete §3 / report §2 (local policy): a stored target is erased and journaled as
    // soon as its author's kind 5 deletion request arrives, or once REPORT_THRESHOLD
    // distinct reporters sent kind 1984 reports; a report its reporter withdrew with
    // a kind 5 (report §2, delete §5) is not counted. Later copies are refused (reason 5).
    // The deletion requests and reports themselves keep being stored and forwarded.
    static constexpr size_t REPORT_THRESHOLD = 2;

private:
    struct Entry {
        std::string id, path;
        uint64_t age = 0, lifetime = 0, receivedMs = 0, createdAt = 0;
        uint32_t hop = 0, hopLimit = 0;
        size_t size = 0;
        bool cold = true;
        uint8_t priority = 1; // effective priority (priority §2)
        uint16_t kind = 0;
        std::string topic, pubkey;
        bool delivered = false;
    };
    // RAM-only moderation records, rebuilt from the stored requests on begin().
    struct Deletion { std::string target, author; };
    struct Report { std::string id, target, reporter; };
    std::vector<Deletion> deletions_;
    std::vector<Report> reports_;
    struct Request { uint64_t id; std::vector<std::string> open; uint64_t sentMs; };
    struct Pending { std::string id; uint64_t request; };
    struct Outgoing {
        uint64_t id = 0, request = 0, startedMs = 0;
        std::string eventId;
        std::vector<uint8_t> bytes;
        size_t credit = 0, sent = 0;
        uint32_t hop = 0;
    };
    struct Awaiting { uint64_t id; std::string eventId; uint64_t startedMs; };
    struct Incoming {
        uint64_t id = 0, request = 0, lastMs = 0;
        std::string eventId;
        size_t length = 0, granted = 0;
        std::vector<uint8_t> buffer;
    };

    std::function<bool(const uint8_t*, size_t)> send_;
    std::function<void(const std::string&)> note_;
    std::function<void(const BundleInfo&)> accepted_;
    std::vector<Entry> entries_;
    // Full IDs + CRC live in /mesh-seen; 8-byte prefixes are only an index.
    std::vector<std::array<uint8_t, 8>> seen_;
    bool seenHealthy_ = true;
    size_t fileCount_ = 0, diskBytes_ = 0;
    bool ready_ = false, active_ = false, disconnect_ = false, gateway_ = false;

    // Session state (mesh §5.3-5.4), reset on every connection.
    bool peerHello_ = false;
    uint8_t nonce_[8] = {};
    unsigned side_ = 0;
    size_t peerMaxBundle_ = 0, peerCredit_ = 0, peerMaxMessage_ = msg::MIN_MESSAGE_BYTES;
    uint64_t nextRequest_ = 0, nextTransfer_ = 0, nextReconcile_ = 0;
    std::unique_ptr<Negentropy> reconcile_;
    uint64_t reconcileId_ = 0, reconcileMs_ = 0;
    std::vector<std::string> reconcileCandidates_;
    std::vector<Request> requests_;
    std::vector<std::string> peerHas_, offered_, refused_;
    std::deque<Pending> queue_;
    std::unique_ptr<Outgoing> sending_;
    std::vector<Awaiting> awaiting_;
    std::unique_ptr<Incoming> incoming_;
    std::vector<uint64_t> closedIncoming_;
    std::deque<std::vector<uint8_t>> outbox_;
    size_t outboxBytes_ = 0;
    uint64_t beganMs_ = 0, activityMs_ = 0, windowMs_ = 0, blockedMs_ = 0;
    bool blocked_ = false;
    bool reconciled_ = false;  // a reconciliation round finished or was answered this session
    unsigned rxWindow_ = 0;

    void note(const std::string& text);
    void fail(const std::string& reason);
    void resetSession();
    const Entry* find(const std::string& id) const;
    bool eligible(const Entry& entry, uint64_t now) const;
    bool gatewayProtected(const Entry& entry) const;
    // Index of the entry to replace for an incoming event of [incoming] priority, or size() if none.
    size_t victim(uint64_t now, uint8_t incoming) const;
    // mesh §9 send order: higher effective priority, then newer.
    bool sendsBefore(const Entry& a, const Entry& b) const;
    size_t acquisitionCapacity() const;
    std::vector<const Entry*> candidates(uint64_t now) const;
    bool restore();
    bool restoreSeen();
    bool wasSeen(const std::string& id);
    bool rememberEvicted(const std::string& id);
    bool seenFailure(const char* stage);
    bool compactSeen();
    bool withdrawn(const std::string& reportId, const std::string& reporter) const;
    bool save(const uint8_t* data, size_t size, const BundleInfo& info, uint64_t receivedMs);
    bool read(const Entry& entry, std::vector<uint8_t>& bytes);
    void moderate(const BundleInfo& info);
    size_t reporters(const std::string& id, const std::string& author) const;
    bool stopped(const std::string& id, const std::string& author) const;
    bool dropEntry(size_t index);

    size_t frameLimit() const;
    void push(std::vector<uint8_t> message);
    void hello(const msg::Message& m);
    void startReconcile();
    void reconcile(const msg::Message& m);
    void want(const std::vector<std::string>& ids);
    void offer(const std::vector<std::string>& ids);
    void served(const msg::Message& m);
    void begin(const msg::Message& m);
    void append(const uint8_t* fragment, size_t size);
    void finish();
    void result(const msg::Message& m);
    void closeRequestItem(uint64_t request, const std::string& id);
    void closeIncoming(uint64_t code, uint64_t reason);
    void startTransfer(uint64_t now);
};
} // namespace mesh
