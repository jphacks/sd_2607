#include "MeshRelay.h"

#include <LittleFS.h>
#include <esp_timer.h>
#include <esp_random.h>
#include <mbedtls/sha256.h>
#include <algorithm>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <utility>

namespace mesh {
namespace {
constexpr size_t STORE_FILES = 16, STORE_BYTES = 384000;
constexpr size_t STORE_WITH_TEMP_BYTES = STORE_BYTES + MAX_BUNDLE_BYTES;
// Session bounds (local policy, not wire claims).
constexpr size_t MAX_OPEN_REQUEST_IDS = 16, MAX_QUEUE = 32, MAX_AWAITING = 4;
constexpr size_t MAX_SESSION_IDS = 512, MAX_CLOSED = 16;
constexpr size_t OUTBOX_MESSAGES = 64, OUTBOX_BYTES = 20000;
constexpr unsigned MINUTE_MESSAGES = 2048;
constexpr uint64_t HELLO_MS = 60000, TRANSFER_MS = 90000, RECONCILE_MS = 60000, REQUEST_MS = 90000;
constexpr uint64_t IDLE_MS = 45000, BACKPRESSURE_MS = 10000;
const char* DIRECTORY = "/mesh";
const char* TEMP = "/mesh/.incoming";
const char* SEEN = "/mesh-seen"; // Outside /mesh's bundle slot/file budget.
constexpr size_t SEEN_LIMIT = 1024, SEEN_RECORD = 36;
constexpr size_t MAX_DELETIONS = 64, MAX_REPORTS = 128;
const uint8_t SEEN_HEADER[] = {'M','S','E','E','N','1','\r','\n'};

std::array<uint8_t, 32> idBytes(const std::string& id) {
    std::array<uint8_t, 32> bytes{};
    auto digit = [](char c) { return c <= '9' ? c - '0' : c - 'a' + 10; };
    for (size_t i = 0; i < bytes.size(); ++i) bytes[i] = (digit(id[2 * i]) << 4) | digit(id[2 * i + 1]);
    return bytes;
}
std::string idHex(const msg::Id& id) { return hex(id.data(), id.size()); }
uint32_t seenCrc(const uint8_t* bytes) {
    uint32_t crc = 0xffffffff;
    for (size_t i = 0; i < 32; ++i) {
        crc ^= bytes[i];
        for (unsigned bit = 0; bit < 8; ++bit) crc = (crc >> 1) ^ ((crc & 1) ? 0x82f63b78 : 0);
    }
    return crc ^ 0xffffffff;
}
bool seenRecordValid(const uint8_t* record) {
    const uint32_t expected = (uint32_t(record[32]) << 24) | (uint32_t(record[33]) << 16) |
                              (uint32_t(record[34]) << 8) | record[35];
    return seenCrc(record) == expected;
}

uint64_t clockMs() { return static_cast<uint64_t>(esp_timer_get_time()) / 1000; }
bool contains(const std::vector<std::string>& ids, const std::string& id) {
    return std::find(ids.begin(), ids.end(), id) != ids.end();
}
// Bounded per-session ID memory: drop the oldest half when full.
void remember(std::vector<std::string>& ids, const std::string& id) {
    if (contains(ids, id)) return;
    if (ids.size() >= MAX_SESSION_IDS) ids.erase(ids.begin(), ids.begin() + MAX_SESSION_IDS / 2);
    ids.push_back(id);
}
// Storage location is not identity. Two-digit slots fit the provisioned
// LittleFS name_max=32; full event IDs come only from verified bundle contents.
std::string slotPath(size_t slot) {
    std::string name(2, '0');
    name[0] = static_cast<char>('0' + slot / 10);
    name[1] = static_cast<char>('0' + slot % 10);
    return std::string(DIRECTORY) + "/" + name;
}
bool slotName(const std::string& name) {
    return name.size() == 2 && name[0] >= '0' && name[0] <= '1' &&
        name[1] >= '0' && name[1] <= '9' &&
        static_cast<size_t>((name[0] - '0') * 10 + name[1] - '0') < STORE_FILES;
}
} // namespace

constexpr size_t Relay::MESSAGE_LIMIT;
constexpr size_t Relay::INITIAL_CREDIT;

void Relay::note(const std::string& text) { if (note_) note_(text); }
void Relay::fail(const std::string& reason) {
    active_ = false;
    disconnect_ = true;
    note(reason);
}
void Relay::resetSession() {
    peerHello_ = blocked_ = false;
    side_ = 0;
    peerMaxBundle_ = peerCredit_ = 0; peerMaxMessage_ = msg::MIN_MESSAGE_BYTES;
    nextRequest_ = nextTransfer_ = nextReconcile_ = 0;
    reconcile_.reset(); reconcileId_ = reconcileMs_ = 0;
    beganMs_ = activityMs_ = windowMs_ = blockedMs_ = 0;
    rxWindow_ = 0;
    sending_.reset(); incoming_.reset();
    // Release capacity, not just contents: RAM is shared with TLS/TTS on Basic.
    std::vector<std::string>().swap(reconcileCandidates_);
    std::vector<Request>().swap(requests_);
    std::vector<std::string>().swap(peerHas_);
    std::vector<std::string>().swap(offered_);
    std::vector<std::string>().swap(refused_);
    std::deque<Pending>().swap(queue_);
    std::vector<Awaiting>().swap(awaiting_);
    std::vector<uint64_t>().swap(closedIncoming_);
    std::deque<std::vector<uint8_t>>().swap(outbox_);
    outboxBytes_ = 0;
}
bool Relay::begin(std::function<bool(const uint8_t*, size_t)> send,
                  std::function<void(const std::string&)> callback,
                  std::function<void(const BundleInfo&)> accepted) {
    ready_ = active_ = disconnect_ = false;
    resetSession();
    entries_.clear(); fileCount_ = diskBytes_ = 0;
    seen_.clear(); seenHealthy_ = true;
    deletions_.clear(); reports_.clear();
    send_ = std::move(send); note_ = std::move(callback);
    accepted_ = std::move(accepted);
    if (!send_) { note("relay configuration invalid"); return false; }
    if (!LittleFS.begin(false)) { note("LittleFS unavailable; not formatted"); return false; }
    if (!LittleFS.exists(DIRECTORY) && !LittleFS.mkdir(DIRECTORY)) { note("store directory failed"); return false; }
    if (!restoreSeen() || !restore()) return false;
    ready_ = true;
    note("store " + std::to_string(entries_.size()) + " cold; not forwarding");
    return true;
}

bool Relay::seenFailure(const char* stage) {
    seenHealthy_ = false;
    const std::string message = "seen history " + std::string(stage) + "; retained; no new requests/ACK";
    fail(message.c_str());
    return false;
}
bool Relay::restoreSeen() {
    errno = 0;
    if (!LittleFS.exists(SEEN)) {
        if (errno && errno != ENOENT) return seenFailure("probe failed");
        return true;
    }
    File file = LittleFS.open(SEEN, "r");
    if (!file || file.isDirectory()) { file.close(); return seenFailure("open failed"); }
    const size_t size = file.size();
    if (size < sizeof SEEN_HEADER || size > sizeof SEEN_HEADER + SEEN_LIMIT * SEEN_RECORD ||
        (size - sizeof SEEN_HEADER) % SEEN_RECORD) { file.close(); return seenFailure("size invalid"); }
    uint8_t header[sizeof SEEN_HEADER], record[SEEN_RECORD];
    if (file.read(header, sizeof header) != sizeof header || std::memcmp(header, SEEN_HEADER, sizeof header)) {
        file.close(); return seenFailure("header invalid");
    }
    // ponytail: bounded linear prefix index; confirm full IDs on disk, no Bloom false positives.
    seen_.reserve(SEEN_LIMIT);
    for (size_t at = sizeof header; at < size; at += sizeof record) {
        if (file.read(record, sizeof record) != sizeof record || !seenRecordValid(record)) {
            file.close(); return seenFailure("record invalid");
        }
        std::array<uint8_t, 8> prefix;
        std::copy(record, record + prefix.size(), prefix.begin());
        seen_.push_back(prefix);
    }
    errno = 0; file.close();
    if (errno) return seenFailure("close failed");
    if (seen_.size() == SEEN_LIMIT) note("seen history full; no new requests when cache full");
    return true;
}
bool Relay::wasSeen(const std::string& id) {
    const auto bytes = idBytes(id);
    for (size_t i = 0; i < seen_.size(); ++i) {
        if (!std::equal(seen_[i].begin(), seen_[i].end(), bytes.begin())) continue;
        File file = LittleFS.open(SEEN, "r");
        uint8_t record[SEEN_RECORD];
        const bool ok = file && !file.isDirectory() &&
            file.size() == sizeof SEEN_HEADER + seen_.size() * SEEN_RECORD &&
            file.seek(sizeof SEEN_HEADER + i * SEEN_RECORD) &&
            file.read(record, sizeof record) == sizeof record && seenRecordValid(record);
        errno = 0; file.close();
        if (!ok || errno) { seenFailure("lookup failed"); return false; }
        if (!std::memcmp(record, bytes.data(), bytes.size())) return true;
    }
    return false;
}
bool Relay::rememberEvicted(const std::string& id) {
    if (!seenHealthy_) return false;
    if (wasSeen(id)) return true;
    if (!seenHealthy_) return false;
    if (seen_.size() >= SEEN_LIMIT) return seenFailure("full");
    const auto bytes = idBytes(id);
    uint8_t record[SEEN_RECORD];
    std::copy(bytes.begin(), bytes.end(), record);
    const uint32_t crc = seenCrc(record);
    for (size_t i = 0; i < 4; ++i) record[32 + i] = crc >> (24 - i * 8);
    const size_t before = seen_.empty() ? 0 : sizeof SEEN_HEADER + seen_.size() * SEEN_RECORD;
    File file = LittleFS.open(SEEN, "a");
    if (!file || file.isDirectory() || (file.size() != before && !(seen_.empty() && file.size() == sizeof SEEN_HEADER))) {
        file.close(); return seenFailure("append size invalid");
    }
    bool ok = true;
    errno = 0;
    if (!file.size()) ok = file.write(SEEN_HEADER, sizeof SEEN_HEADER) == sizeof SEEN_HEADER;
    ok = ok && file.write(record, sizeof record) == sizeof record;
    if (ok) file.flush();
    ok = ok && !errno && file.size() == sizeof SEEN_HEADER + (seen_.size() + 1) * SEEN_RECORD;
    errno = 0; file.close();
    if (!ok || errno) return seenFailure("append failed");
    file = LittleFS.open(SEEN, "r");
    uint8_t check[SEEN_RECORD];
    ok = file && !file.isDirectory() && file.size() == sizeof SEEN_HEADER + (seen_.size() + 1) * SEEN_RECORD &&
         file.seek(sizeof SEEN_HEADER + seen_.size() * SEEN_RECORD) &&
         file.read(check, sizeof check) == sizeof check && !std::memcmp(check, record, sizeof check);
    errno = 0; file.close();
    if (!ok || errno) return seenFailure("readback failed");
    if (seen_.capacity() < SEEN_LIMIT) seen_.reserve(SEEN_LIMIT);
    std::array<uint8_t, 8> prefix;
    std::copy(bytes.begin(), bytes.begin() + prefix.size(), prefix.begin());
    seen_.push_back(prefix);
    if (seen_.size() == SEEN_LIMIT) note("seen history full; no new requests when cache full");
    return true;
}
bool Relay::restore() {
    // A temp file is not a commit and was never acknowledged. Never promote it.
    if (LittleFS.exists(TEMP) && !LittleFS.remove(TEMP)) { note("store temp cleanup failed"); return false; }
    File root = LittleFS.open(DIRECTORY, "r");
    if (!root || !root.isDirectory()) { note("store directory unreadable"); return false; }
    std::vector<BundleInfo> requests; // deletion requests and reports, applied once the scan is complete
    File f = root.openNextFile();
    while (f) {
        size_t size = f.size();
        if (f.isDirectory() || ++fileCount_ > STORE_FILES || size > MAX_BUNDLE_BYTES || size > STORE_BYTES - diskBytes_) {
            f.close(); root.close(); note("store exceeds limits; retained"); return false;
        }
        diskBytes_ += size;
        std::string name = f.name();
        size_t slash = name.find_last_of('/');
        if (slash != std::string::npos) name.erase(0, slash + 1);
        bool named = slotName(name);
        std::vector<uint8_t> bytes(size);
        bool loaded = size && f.read(bytes.data(), size) == size;
        f.close();
        BundleInfo info;
        std::string error;
        if (named && loaded && validateBundle(bytes.data(), bytes.size(), info, error)) {
            if (find(info.id)) {
                note("duplicate store ID retained");
                f = root.openNextFile();
                continue; // Count its file/bytes, but index each verified ID once.
            }
            Entry e;
            e.path = std::string(DIRECTORY) + "/" + name;
            e.id = info.id; e.age = info.age; e.lifetime = info.lifetime;
            e.hop = info.hop; e.hopLimit = info.hopLimit; e.size = size; e.kind = info.kind;
            e.createdAt = info.createdAt; e.topic = info.topic; e.pubkey = info.pubkey;
            entries_.push_back(std::move(e)); // cold=true; no fabricated received timestamp.
            if (!info.target.empty()) requests.push_back(std::move(info));
        } else note("invalid store file retained");
        f = root.openNextFile();
    }
    root.close();
    for (const BundleInfo& info : requests) moderate(info);
    return true;
}
// delete §3 / report §2: record the request, then erase its target if it is now stopped.
void Relay::moderate(const BundleInfo& info) {
    if (info.target.empty()) return;
    if (info.kind == 5) {
        bool known = false;
        for (const Deletion& d : deletions_) known = known || (d.target == info.target && d.author == info.pubkey);
        if (!known) {
            if (deletions_.size() >= MAX_DELETIONS) deletions_.erase(deletions_.begin());
            deletions_.push_back({info.target, info.pubkey});
        }
    } else if (info.kind == 1984 && info.reported != info.pubkey) {
        bool known = false;
        for (const Report& r : reports_) known = known || (r.target == info.target && r.reporter == info.pubkey);
        if (!known) {
            if (reports_.size() >= MAX_REPORTS) reports_.erase(reports_.begin());
            reports_.push_back({info.target, info.pubkey});
        }
    } else return;
    for (size_t i = 0; i < entries_.size(); ++i) {
        if (entries_[i].id != info.target || !stopped(entries_[i].id, entries_[i].pubkey)) continue;
        dropEntry(i);
        return;
    }
    // Reported enough before arriving: journal it so it is never requested (deletions wait for the author check).
    if (reporters(info.target, "") >= REPORT_THRESHOLD && !wasSeen(info.target) && seenHealthy_) rememberEvicted(info.target);
}
size_t Relay::reporters(const std::string& id, const std::string& author) const {
    std::vector<const std::string*> distinct;
    for (const Report& r : reports_) {
        if (r.target != id || r.reporter == author) continue;
        bool seen = false;
        for (const std::string* p : distinct) seen = seen || *p == r.reporter;
        if (!seen) distinct.push_back(&r.reporter);
    }
    return distinct.size();
}
bool Relay::stopped(const std::string& id, const std::string& author) const {
    for (const Deletion& d : deletions_) if (d.target == id && d.author == author) return true;
    return reporters(id, author) >= REPORT_THRESHOLD;
}
bool Relay::dropEntry(size_t index) {
    const Entry e = entries_[index];
    // Journal first, as for eviction: a failed journal keeps the file rather than forgetting the ID.
    if (!wasSeen(e.id) && !rememberEvicted(e.id)) return false;
    if (!LittleFS.remove(e.path.c_str())) { note("stopped " + e.id.substr(0, 8) + " but remove failed"); return false; }
    --fileCount_;
    diskBytes_ -= e.size;
    entries_.erase(entries_.begin() + index);
    note("stopped " + e.id.substr(0, 8) + " (deletion request or reports)");
    return true;
}
size_t Relay::victim(uint64_t now) const {
    auto dead = [now](const Entry& e) {
        return e.cold || now < e.receivedMs || e.age >= e.lifetime ||
            now - e.receivedMs >= std::min(MAX_RESIDENCE_MS, e.lifetime - e.age);
    };
    size_t best = entries_.size();
    for (size_t i = 0; i < entries_.size(); ++i) {
        if (gatewayProtected(entries_[i])) continue;
        if (best == entries_.size() || (dead(entries_[i]) && !dead(entries_[best])) ||
            (dead(entries_[i]) == dead(entries_[best]) && entries_[i].receivedMs < entries_[best].receivedMs)) best = i;
    }
    return best;
}
bool Relay::read(const Entry& e, std::vector<uint8_t>& bytes) {
    File file = LittleFS.open(e.path.c_str(), "r");
    if (!file || file.isDirectory() || file.size() != e.size || e.size > MAX_BUNDLE_BYTES) {
        file.close(); return false;
    }
    bytes.resize(e.size);
    bool ok = file.read(bytes.data(), bytes.size()) == bytes.size();
    file.close();
    return ok;
}
bool Relay::save(const uint8_t* data, size_t size, const BundleInfo& info, uint64_t receivedMs) {
    // Capture errno before close/remove/callbacks can replace it. Some Arduino
    // void wrappers expose no errno either; report 0 honestly, not a stale ENOENT.
    auto failed = [&](const char* stage, int code) {
        std::string message = "store " + std::string(stage) + " errno=" + std::to_string(code) +
            " (" + (code ? std::string(std::strerror(code)) : std::string("not reported")) +
            ") id=" + info.id.substr(0, 8) + " " + std::to_string(size) + "B; no ACK";
        fail(message.c_str());
        return false;
    };
    if (size > MAX_BUNDLE_BYTES) return failed("size-limit", EFBIG);
    // One serialized writer: reserve 384000 committed bytes + one 24000-byte
    // staging file, even when all sixteen committed slots are occupied.
    if (fileCount_ > STORE_FILES || diskBytes_ > STORE_BYTES ||
        size > STORE_WITH_TEMP_BYTES - diskBytes_) return failed("capacity", ENOSPC);
    if (LittleFS.exists(TEMP)) return failed("temp-exists", EBUSY);
    errno = 0;
    File file = LittleFS.open(TEMP, "w");
    if (!file) return failed("open-temp", errno);
    errno = 0;
    size_t written = file.write(data, size);
    int code = errno;
    if (written != size) {
        file.close(); LittleFS.remove(TEMP);
        return failed("write", code);
    }
    errno = 0;
    file.flush();
    code = errno;
    if (code) {
        file.close(); LittleFS.remove(TEMP);
        return failed("flush", code);
    }
    // File::size() uses stat(), which may lag buffered fwrite until flush.
    errno = 0;
    size_t actualSize = file.size();
    code = errno;
    if (actualSize != size) {
        file.close(); LittleFS.remove(TEMP);
        return failed("size-after-flush", code);
    }
    errno = 0;
    file.close();
    code = errno;
    if (code) { LittleFS.remove(TEMP); return failed("close-temp", code); }
    // Incoming signature/request checks already passed. Only now, after the
    // complete temp file is flushed and closed, may a verified entry be evicted.
    std::string path;
    size_t selected = entries_.size();
    for (size_t slot = 0; slot < STORE_FILES; ++slot) {
        std::string candidate = slotPath(slot);
        errno = 0;
        if (!LittleFS.exists(candidate.c_str())) {
            code = errno;
            if (code && code != ENOENT) { LittleFS.remove(TEMP); return failed("slot-probe", code); }
            path = std::move(candidate);
            break;
        }
    }
    if (path.empty() || fileCount_ == STORE_FILES || size > STORE_BYTES - diskBytes_) {
        selected = victim(clockMs());
        if (selected == entries_.size()) { LittleFS.remove(TEMP); return failed("no-verified-victim", ENOSPC); }
        // The only copy may be evicted only after its verified ID is durably
        // remembered. A journal failure preserves the old cache and sends no ACK.
        if (!rememberEvicted(entries_[selected].id)) { LittleFS.remove(TEMP); return false; }
        path = entries_[selected].path;
    }
    // Unknown/invalid diagnostic files are never candidates or overwritten.
    if (selected == entries_.size() && LittleFS.exists(path.c_str())) {
        LittleFS.remove(TEMP);
        return failed("destination-exists", EEXIST);
    }
    errno = 0;
    // LittleFS rename replaces an existing file atomically. Never remove the
    // victim first: a failed replacement must leave its complete Bundle intact.
    if (!LittleFS.rename(TEMP, path.c_str())) {
        code = errno;
        LittleFS.remove(TEMP);
        return failed("rename", code);
    }
    if (selected != entries_.size()) {
        --fileCount_;
        diskBytes_ -= entries_[selected].size;
        note("evicted " + entries_[selected].id.substr(0, 8));
        entries_.erase(entries_.begin() + selected);
    }
    // Arduino flush/close have no status return. Check errno where available,
    // then close + rename + exact readback; never ACK earlier. A failed final
    // readback remains on disk for diagnosis and still counts toward capacity.
    ++fileCount_; diskBytes_ += size;
    errno = 0;
    file = LittleFS.open(path.c_str(), "r");
    if (!file) return failed("open-final", errno);
    if (file.isDirectory()) { file.close(); return failed("readback-type", EISDIR); }
    errno = 0;
    actualSize = file.size();
    code = errno;
    if (actualSize != size) {
        diskBytes_ = diskBytes_ - size + actualSize;
        file.close(); return failed("readback-size", code);
    }
    uint8_t check[256];
    for (size_t at = 0; at < size;) {
        size_t count = std::min(sizeof check, size - at);
        errno = 0;
        size_t got = file.read(check, count);
        code = errno;
        if (got != count) { file.close(); return failed("readback", code); }
        if (std::memcmp(check, data + at, count)) { file.close(); return failed("readback-compare", 0); }
        at += count;
    }
    errno = 0;
    file.close();
    code = errno;
    if (code) return failed("close-final", code);
    Entry e;
    e.path = path;
    e.id = info.id; e.age = info.age; e.lifetime = info.lifetime;
    e.hop = info.hop; e.hopLimit = info.hopLimit; e.size = size; e.kind = info.kind;
    e.createdAt = info.createdAt; e.topic = info.topic; e.pubkey = info.pubkey;
    e.receivedMs = receivedMs; e.cold = false;
    entries_.push_back(std::move(e));
    return true;
}
const Relay::Entry* Relay::find(const std::string& id) const {
    for (const Entry& e : entries_) if (e.id == id) return &e;
    return nullptr;
}
bool Relay::eligible(const Entry& e, uint64_t now) const {
    return !e.cold && now >= e.receivedMs && e.age < e.lifetime &&
        now - e.receivedMs < MAX_RESIDENCE_MS && now - e.receivedMs < e.lifetime - e.age &&
        e.hop < std::min(e.hopLimit, MAX_HOPS);
}
// PDS relay profile (pds §4.2, §6): posts, deletion requests, reports and revocation notices go
// to the relay; PLC genesis control events are registered with the directory.
bool Relay::gatewayProtected(const Entry& e) const {
    return gateway_ && !e.delivered &&
        (e.kind == 1 || e.kind == 5 || e.kind == 1984 ||
         (e.kind == 8173 && (e.topic == "mosimo.jisaku.dev/plc" || e.topic == "mosimo.jisaku.dev/revocation")));
}
std::vector<std::string> Relay::gatewayPending() const {
    std::vector<std::string> ids;
    if (ready_) for (const Entry& e : entries_) if (gatewayProtected(e)) ids.push_back(e.id);
    return ids;
}
bool Relay::gatewayRead(const std::string& id, std::vector<uint8_t>& bytes) {
    const Entry* e = find(id);
    return e && gatewayProtected(*e) && read(*e, bytes);
}
void Relay::gatewayDelivered(const std::string& id) {
    for (Entry& e : entries_) if (e.id == id) e.delivered = true;
}
std::vector<const Relay::Entry*> Relay::candidates(uint64_t now) const {
    std::vector<const Entry*> list;
    for (const Entry& e : entries_) if (eligible(e, now)) list.push_back(&e);
    return list;
}
size_t Relay::candidateCount() const { return candidates(clockMs()).size(); }
void Relay::forEachStored(const std::function<void(const BundleInfo&, bool cold)>& visit) {
    std::vector<uint8_t> bytes;
    for (const Entry& e : entries_) {
        BundleInfo info;
        std::string error;
        if (read(e, bytes) && validateBundle(bytes.data(), bytes.size(), info, error)) visit(info, e.cold);
    }
}
void Relay::advertisement(uint8_t out[4]) const {
    // SHA-256 over the candidate IDs sorted as unsigned bytes (= lowercase hex order).
    std::vector<std::string> ids;
    for (const Entry* e : candidates(clockMs())) ids.push_back(e->id);
    std::sort(ids.begin(), ids.end());
    mbedtls_sha256_context ctx;
    mbedtls_sha256_init(&ctx);
    mbedtls_sha256_starts_ret(&ctx, 0);
    for (const std::string& id : ids) { const auto b = idBytes(id); mbedtls_sha256_update_ret(&ctx, b.data(), b.size()); }
    uint8_t digest[32];
    mbedtls_sha256_finish_ret(&ctx, digest);
    mbedtls_sha256_free(&ctx);
    std::memcpy(out, digest, 3);
    out[3] = 0; // No top-priority hint, no attestation references, no L2CAP.
}
// Slots that a newly requested event could occupy: empty slots plus evictable
// verified entries, bounded by the eviction journal's remaining capacity.
size_t Relay::acquisitionCapacity() const {
    if (!seenHealthy_) return 0;
    size_t capacity = STORE_FILES - std::min(fileCount_, STORE_FILES);
    size_t evictable = 0;
    for (const Entry& e : entries_) if (!gatewayProtected(e)) ++evictable;
    capacity += std::min(evictable, SEEN_LIMIT - seen_.size());
    return capacity;
}

void Relay::connected() {
    resetSession();
    disconnect_ = false;
    if (!ready_) { fail("relay not ready"); return; }
    active_ = true;
    beganMs_ = activityMs_ = windowMs_ = clockMs();
    esp_fill_random(nonce_, sizeof nonce_);
    push(msg::hello(nonce_, MAX_BUNDLE_BYTES, msg::ROUTE_GATT, INITIAL_CREDIT, MESSAGE_LIMIT));
    note("mesh connected");
}
void Relay::disconnected() {
    active_ = disconnect_ = false;
    resetSession();
}
size_t Relay::frameLimit() const { return std::min(MESSAGE_LIMIT, peerMaxMessage_); }
void Relay::push(std::vector<uint8_t> message) {
    if (!active_) return;
    const size_t limit = peerHello_ ? peerMaxMessage_ : msg::HELLO_MAX_BYTES;
    if (message.size() > limit) { fail("outgoing message exceeds peer limit"); return; }
    if (outbox_.size() >= OUTBOX_MESSAGES || message.size() > OUTBOX_BYTES - outboxBytes_) { fail("mesh outbox overflow"); return; }
    outboxBytes_ += message.size();
    outbox_.push_back(std::move(message));
}

void Relay::receive(const uint8_t* data, size_t size) {
    if (!active_) return;
    const uint64_t now = clockMs();
    if (now - windowMs_ >= 60000) { windowMs_ = now; rxWindow_ = 0; }
    if (++rxWindow_ > MINUTE_MESSAGES) { fail("mesh receive rate limit"); return; }
    activityMs_ = now;
    msg::Message m;
    std::string error;
    if (!msg::decode(data, size, m, error)) {
        // Public protocol bytes only (no secrets); the head identifies interop mismatches.
        fail("mesh message rejected: " + error + " size=" + std::to_string(size) + " head=" + hex(data, std::min<size_t>(size, 24)));
        return;
    }
    if (m.type == msg::HELLO) { hello(m); return; }
    if (!peerHello_) { fail("mesh HELLO required first"); return; }
    switch (m.type) {
        case msg::RECONCILE: reconcile(m); break;
        case msg::INVENTORY: {
            std::vector<std::string> ids;
            for (const auto& id : m.ids) { ids.push_back(idHex(id)); remember(peerHas_, ids.back()); }
            want(ids);
            break;
        }
        case msg::WANT: served(m); break;
        case msg::BEGIN: begin(m); break;
        case msg::DATA:
            if (!incoming_ || incoming_->id != m.first) {
                if (std::find(closedIncoming_.begin(), closedIncoming_.end(), m.first) == closedIncoming_.end())
                    fail("DATA for unknown transfer");
                return;
            }
            append(m.bytes.p, m.bytes.n);
            break;
        case msg::CREDIT:
            if (sending_ && sending_->id == m.first && m.length > sending_->credit)
                sending_->credit = std::min<size_t>(m.length, sending_->bytes.size());
            break;
        case msg::RESULT: result(m); break;
        case msg::FETCH:
            // Key-attestation references are never sent by us, and we keep no objects.
            for (const auto& hash : m.ids)
                push(msg::result(msg::TARGET_OBJECT, 1 - side_, m.first, &hash, msg::UNAVAILABLE, msg::MISSING));
            break;
        default: break; // Unknown types are ignored (mesh §5.2).
    }
}

void Relay::hello(const msg::Message& m) {
    if (peerHello_) { fail("duplicate HELLO"); return; }
    const int order = std::memcmp(nonce_, m.nonce, sizeof nonce_);
    if (!order) { fail("HELLO nonce collision; reconnect"); return; }
    peerHello_ = true;
    side_ = order < 0 ? 0 : 1;
    peerMaxBundle_ = static_cast<size_t>(m.maxBundleBytes);
    peerCredit_ = static_cast<size_t>(m.initialCredit);
    peerMaxMessage_ = static_cast<size_t>(m.maxMessageBytes);
    note("mesh HELLO side=" + std::to_string(side_) + " max=" + std::to_string(peerMaxBundle_) +
         " msg=" + std::to_string(peerMaxMessage_));
    if (side_ == 0) startReconcile();
}

void Relay::startReconcile() {
    std::vector<Negentropy::Item> items;
    std::vector<std::string>().swap(reconcileCandidates_);
    for (const Entry* e : candidates(clockMs())) {
        Negentropy::Item item{e->createdAt, idBytes(e->id)};
        items.push_back(item);
        reconcileCandidates_.push_back(e->id);
    }
    reconcile_.reset(new Negentropy(std::move(items), frameLimit() - msg::RECONCILE_OVERHEAD));
    reconcileId_ = nextReconcile_++;
    reconcileMs_ = clockMs();
    push(msg::reconcile(reconcileId_, reconcile_->initiate()));
}

void Relay::reconcile(const msg::Message& m) {
    std::vector<uint8_t> out;
    bool done = false;
    std::string error;
    if (reconcile_ && m.first == reconcileId_) {
        if (!reconcile_->reconcile(m.bytes.p, m.bytes.n, out, done, error)) { fail("reconcile: " + error); return; }
        if (!done) { push(msg::reconcile(m.first, out)); return; }
        std::vector<std::string> have, need;
        for (const auto& id : reconcile_->have()) have.push_back(idHex(id));
        for (const auto& id : reconcile_->need()) need.push_back(idHex(id));
        for (const std::string& id : reconcileCandidates_) if (!contains(have, id)) remember(peerHas_, id);
        reconcile_.reset();
        note("reconciled: peer lacks " + std::to_string(have.size()) + ", we lack " + std::to_string(need.size()));
        want(need);
        offer(have);
        return;
    }
    // Peer-initiated: answer from our current candidate set.
    std::vector<Negentropy::Item> items;
    for (const Entry* e : candidates(clockMs())) items.push_back({e->createdAt, idBytes(e->id)});
    Negentropy responder(std::move(items), frameLimit() - msg::RECONCILE_OVERHEAD);
    if (!responder.reconcile(m.bytes.p, m.bytes.n, out, done, error)) { fail("reconcile: " + error); return; }
    push(msg::reconcile(m.first, out));
}

void Relay::want(const std::vector<std::string>& ids) {
    size_t open = 0;
    for (const Request& r : requests_) open += r.open.size();
    const size_t capacity = std::min(acquisitionCapacity(), MAX_OPEN_REQUEST_IDS);
    std::vector<std::string> wanted;
    for (const std::string& id : ids) {
        if (open + wanted.size() >= capacity) break;
        if (find(id) || contains(refused_, id) || contains(wanted, id)) continue;
        bool requested = false;
        for (const Request& r : requests_) if (contains(r.open, id)) requested = true;
        if (requested || wasSeen(id)) continue;
        if (!seenHealthy_) return;
        wanted.push_back(id);
    }
    const size_t per = msg::idsPerMessage(frameLimit());
    for (size_t at = 0; at < wanted.size(); at += per) {
        Request r{nextRequest_++, {}, clockMs()};
        std::vector<msg::Id> list;
        for (size_t i = at; i < std::min(wanted.size(), at + per); ++i) { r.open.push_back(wanted[i]); list.push_back(idBytes(wanted[i])); }
        push(msg::ids(msg::WANT, &r.id, list));
        requests_.push_back(std::move(r));
    }
    if (!wanted.empty()) note("want " + std::to_string(wanted.size()));
}

void Relay::offer(const std::vector<std::string>& ids) {
    const uint64_t now = clockMs();
    std::vector<std::string> fresh;
    for (const std::string& id : ids) {
        const Entry* e = find(id);
        if (e && eligible(*e, now) && !contains(peerHas_, id) && !contains(offered_, id)) fresh.push_back(id);
    }
    const size_t per = msg::idsPerMessage(frameLimit());
    for (size_t at = 0; at < fresh.size(); at += per) {
        std::vector<msg::Id> list;
        for (size_t i = at; i < std::min(fresh.size(), at + per); ++i) { list.push_back(idBytes(fresh[i])); remember(offered_, fresh[i]); }
        push(msg::ids(msg::INVENTORY, nullptr, list));
    }
}

// Peer WANT: queue what we can serve, answer the rest "unavailable".
void Relay::served(const msg::Message& m) {
    const uint64_t now = clockMs();
    for (const auto& id : m.ids) {
        const std::string h = idHex(id);
        const Entry* e = find(h);
        if (!e || !eligible(*e, now)) {
            push(msg::result(msg::TARGET_EVENT, 1 - side_, m.first, &id, msg::UNAVAILABLE, msg::MISSING));
        } else if (queue_.size() >= MAX_QUEUE) {
            push(msg::result(msg::TARGET_EVENT, 1 - side_, m.first, &id, msg::UNAVAILABLE, msg::RESOURCES));
        } else queue_.push_back({h, m.first});
    }
}

void Relay::closeRequestItem(uint64_t request, const std::string& id) {
    for (auto it = requests_.begin(); it != requests_.end(); ++it) {
        if (it->id != request) continue;
        it->open.erase(std::remove(it->open.begin(), it->open.end(), id), it->open.end());
        if (it->open.empty()) requests_.erase(it);
        return;
    }
}

void Relay::begin(const msg::Message& m) {
    const std::string id = idHex(m.target);
    auto reply = [&](uint64_t code, uint64_t reason) {
        if (closedIncoming_.size() >= MAX_CLOSED) closedIncoming_.erase(closedIncoming_.begin());
        closedIncoming_.push_back(m.first);
        push(msg::result(msg::TARGET_TRANSFER, 1 - side_, m.first, nullptr, code, reason));
    };
    bool requested = false;
    for (const Request& r : requests_) if (r.id == m.second && contains(r.open, id)) requested = true;
    if (!requested) { reply(msg::REJECTED, msg::POLICY); return; }
    if (find(id)) { closeRequestItem(m.second, id); remember(peerHas_, id); reply(msg::ALREADY_HELD, msg::NONE); return; }
    if (m.length > MAX_BUNDLE_BYTES) { closeRequestItem(m.second, id); reply(msg::REJECTED, msg::SIZE); return; }
    if (incoming_) { reply(msg::REJECTED, msg::RESOURCES); return; }
    std::unique_ptr<Incoming> t(new Incoming);
    t->id = m.first; t->request = m.second; t->eventId = id;
    t->length = static_cast<size_t>(m.length);
    t->granted = std::min(INITIAL_CREDIT, t->length);
    t->lastMs = clockMs();
    t->buffer.reserve(t->length);
    incoming_ = std::move(t);
    if (m.hasBytes) append(m.bytes.p, m.bytes.n);
    else if (incoming_->granted < incoming_->length) {
        // Whole-Bundle grant: the assembly buffer above is the only resource.
        incoming_->granted = incoming_->length;
        push(msg::credit(incoming_->id, incoming_->length));
    }
}

void Relay::closeIncoming(uint64_t code, uint64_t reason) {
    if (!incoming_) return;
    if (closedIncoming_.size() >= MAX_CLOSED) closedIncoming_.erase(closedIncoming_.begin());
    closedIncoming_.push_back(incoming_->id);
    closeRequestItem(incoming_->request, incoming_->eventId);
    push(msg::result(msg::TARGET_TRANSFER, 1 - side_, incoming_->id, nullptr, code, reason));
    incoming_.reset();
}

void Relay::append(const uint8_t* fragment, size_t size) {
    Incoming& t = *incoming_;
    t.lastMs = clockMs();
    if (size > t.granted - t.buffer.size() || size > t.length - t.buffer.size()) {
        closeIncoming(msg::REJECTED, msg::INVALID);
        return;
    }
    t.buffer.insert(t.buffer.end(), fragment, fragment + size);
    if (t.buffer.size() == t.length) { finish(); return; }
    if (t.granted < t.length) {
        t.granted = t.length;
        push(msg::credit(t.id, t.length));
    }
}

void Relay::finish() {
    std::unique_ptr<Incoming> t = std::move(incoming_);
    if (closedIncoming_.size() >= MAX_CLOSED) closedIncoming_.erase(closedIncoming_.begin());
    closedIncoming_.push_back(t->id);
    closeRequestItem(t->request, t->eventId);
    auto reply = [&](uint64_t code, uint64_t reason) {
        push(msg::result(msg::TARGET_TRANSFER, 1 - side_, t->id, nullptr, code, reason));
    };
    BundleInfo info;
    std::string error;
    if (!validateBundle(t->buffer.data(), t->buffer.size(), info, error) || info.id != t->eventId) {
        if (error.empty()) error = "different event than announced";
        note("bundle rejected " + t->eventId.substr(0, 8) + ": " + error);
        remember(refused_, t->eventId);
        reply(msg::REJECTED, error.find("expired") != std::string::npos ? msg::EXPIRED :
                             error.find("size") != std::string::npos ? msg::SIZE : msg::INVALID);
        return;
    }
    if (find(info.id)) { remember(peerHas_, info.id); reply(msg::ALREADY_HELD, msg::NONE); return; }
    // delete §3 / report §2: a stopped target is refused by local policy and never requested again.
    if (stopped(info.id, info.pubkey)) {
        if (seenHealthy_ && !wasSeen(info.id)) rememberEvicted(info.id);
        remember(refused_, info.id);
        note("refused stopped " + info.id.substr(0, 8));
        reply(msg::REJECTED, msg::POLICY);
        return;
    }
    if (!seenHealthy_ || (fileCount_ >= STORE_FILES && victim(clockMs()) == entries_.size())) {
        reply(msg::REJECTED, msg::RESOURCES);
        return;
    }
    if (!save(t->buffer.data(), t->buffer.size(), info, clockMs())) return; // save() reports the stage and disconnects.
    remember(peerHas_, info.id);
    reply(msg::STORED, msg::NONE);
    note("saved " + info.id.substr(0, 8) + " kind " + std::to_string(info.kind) + " hop " + std::to_string(info.hop) + " " + std::to_string(t->buffer.size()) + "B");
    if (accepted_) accepted_(info);
    moderate(info);
}

void Relay::result(const msg::Message& m) {
    if (m.side != side_) return; // Targets something the peer issued.
    if (m.targetKind == msg::TARGET_TRANSFER) {
        std::string eventId;
        if (sending_ && sending_->id == m.first) { eventId = sending_->eventId; sending_.reset(); }
        for (auto it = awaiting_.begin(); it != awaiting_.end(); ++it)
            if (it->id == m.first) { eventId = it->eventId; awaiting_.erase(it); break; }
        if (eventId.empty()) return;
        if (m.code == msg::STORED || m.code == msg::ALREADY_HELD) {
            remember(peerHas_, eventId);
            note("peer accepted " + eventId.substr(0, 8));
        } else note("peer refused " + eventId.substr(0, 8) + " code " + std::to_string(m.code) + " reason " + std::to_string(m.reason));
    } else if (m.targetKind == msg::TARGET_EVENT) {
        closeRequestItem(m.first, idHex(m.target));
    }
}

void Relay::startTransfer(uint64_t now) {
    const Pending next = queue_.front();
    queue_.pop_front();
    const msg::Id target = idBytes(next.id);
    auto unavailable = [&](uint64_t reason) {
        push(msg::result(msg::TARGET_EVENT, 1 - side_, next.request, &target, msg::UNAVAILABLE, reason));
    };
    const Entry* e = find(next.id);
    if (!e || !eligible(*e, now)) { unavailable(msg::MISSING); return; }
    std::vector<uint8_t> original;
    std::unique_ptr<Outgoing> t(new Outgoing);
    std::string error;
    if (!read(*e, original) || !forwardBundle(original.data(), original.size(), now - e->receivedMs, t->bytes, error)) {
        unavailable(msg::MISSING);
        return;
    }
    if (t->bytes.size() > peerMaxBundle_) { unavailable(msg::SIZE); return; }
    t->id = nextTransfer_++; t->request = next.request; t->eventId = next.id; t->startedMs = now;
    t->hop = e->hop + 1;
    t->credit = std::min(peerCredit_, t->bytes.size());
    const size_t first = std::min(t->credit, frameLimit() - msg::BEGIN_OVERHEAD);
    push(msg::begin(t->id, t->request, target, t->bytes.size(), t->bytes.data(), first));
    t->sent = first;
    const size_t size = t->bytes.size();
    if (t->sent >= t->bytes.size()) { awaiting_.push_back({t->id, t->eventId, now}); t.reset(); }
    note("sent " + next.id.substr(0, 8) + " hop " + std::to_string(e->hop + 1) + " " + std::to_string(size) + "B");
    sending_ = std::move(t);
}

void Relay::tick() {
    if (!active_) return;
    const uint64_t now = clockMs();
    if (!peerHello_ && now - beganMs_ >= HELLO_MS) { fail("mesh HELLO timeout"); return; }
    if (incoming_ && now - incoming_->lastMs >= TRANSFER_MS) closeIncoming(msg::REJECTED, msg::RESOURCES);
    awaiting_.erase(std::remove_if(awaiting_.begin(), awaiting_.end(), [&](const Awaiting& a) {
        return now - a.startedMs >= TRANSFER_MS;
    }), awaiting_.end());
    if (sending_ && now - sending_->startedMs >= TRANSFER_MS) { note("transfer timed out"); sending_.reset(); }
    if (reconcile_ && now - reconcileMs_ >= RECONCILE_MS) { note("reconciliation timed out"); reconcile_.reset(); }
    requests_.erase(std::remove_if(requests_.begin(), requests_.end(), [&](const Request& r) {
        return now - r.sentMs >= REQUEST_MS;
    }), requests_.end());
    if (!active_) return;
    // Control messages first (mesh §4.5), then DATA within credit, then the next transfer.
    bool refused = false;
    for (unsigned step = 0; step < 8 && active_; ++step) {
        if (!outbox_.empty()) {
            if (!send_(outbox_.front().data(), outbox_.front().size())) { refused = true; break; }
            outboxBytes_ -= outbox_.front().size();
            outbox_.pop_front();
            activityMs_ = now;
        } else if (sending_ && sending_->sent < sending_->credit) {
            Outgoing& t = *sending_;
            const size_t count = std::min(t.credit - t.sent, frameLimit() - msg::DATA_OVERHEAD);
            const auto frame = msg::data(t.id, t.bytes.data() + t.sent, count);
            if (!send_(frame.data(), frame.size())) { refused = true; break; }
            t.sent += count;
            activityMs_ = now;
            if (t.sent >= t.bytes.size()) { awaiting_.push_back({t.id, t.eventId, t.startedMs}); sending_.reset(); }
        } else if (!sending_ && !queue_.empty() && awaiting_.size() < MAX_AWAITING) {
            startTransfer(now);
        } else break;
    }
    if (!active_) return;
    if (refused) {
        if (!blocked_) { blocked_ = true; blockedMs_ = now; }
        else if (now - blockedMs_ >= BACKPRESSURE_MS) { fail("mesh send queue stalled"); return; }
    } else blocked_ = false;
    // A single-connection peripheral must not be held by an idle phone.
    const bool busy = !outbox_.empty() || sending_ || incoming_ || !awaiting_.empty() || !queue_.empty() || reconcile_ || !requests_.empty();
    if (!busy && now - activityMs_ >= IDLE_MS) fail("mesh idle; releasing connection");
}
} // namespace mesh
