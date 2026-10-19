#include "MeshRelay.h"

#include <ArduinoJson.h>
#include <LittleFS.h>
#include <esp_timer.h>
#include <algorithm>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <utility>

namespace mesh {
namespace {
constexpr size_t STORE_FILES = 16, STORE_BYTES = 384000;
constexpr size_t STORE_WITH_TEMP_BYTES = STORE_BYTES + MAX_BUNDLE_BYTES;
constexpr size_t CONTROL_BYTES = 10 * 1024, CONTROL_IDS = 128;
constexpr size_t MAX_REQUESTS = 16, SESSION_COMPLETED_IDS = CONTROL_IDS;
constexpr size_t SESSION_BYTES = 4 * 1024 * 1024;
constexpr unsigned SESSION_MESSAGES = 4096, MINUTE_MESSAGES = 512;
constexpr uint64_t HELLO_MS = 60000, INVENTORY_MS = 10000;
constexpr uint64_t REQUEST_MS = 30000, BACKPRESSURE_MS = 10000, RETRY_MS = 250;
const char* DIRECTORY = "/mesh";
const char* TEMP = "/mesh/.incoming";
const char* SEEN = "/mesh-seen"; // Outside /mesh's bundle slot/file budget.
constexpr size_t SEEN_LIMIT = 1024, SEEN_RECORD = 36;
const uint8_t SEEN_HEADER[] = {'M','S','E','E','N','1','\r','\n'};

std::array<uint8_t, 32> idBytes(const std::string& id) {
    std::array<uint8_t, 32> bytes{};
    auto digit = [](char c) { return c <= '9' ? c - '0' : c - 'a' + 10; };
    for (size_t i = 0; i < bytes.size(); ++i) bytes[i] = (digit(id[2 * i]) << 4) | digit(id[2 * i + 1]);
    return bytes;
}
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
bool hexId(const std::string& id, size_t length = 64) {
    return id.size() == length && std::all_of(id.begin(), id.end(), [](char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    });
}
bool contains(const std::vector<std::string>& ids, const std::string& id) {
    return std::find(ids.begin(), ids.end(), id) != ids.end();
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

// ArduinoJson 7 otherwise grows its document as needed. This bounds all its
// pools/strings together, including ignored future HELLO capability fields.
class JsonBudget : public ArduinoJson::Allocator {
    union Header { size_t size; std::max_align_t alignment; };
    size_t used_ = 0;
    static constexpr size_t LIMIT = 32768;
public:
    void* allocate(size_t size) override {
        if (size > LIMIT - used_) return nullptr;
        Header* h = static_cast<Header*>(std::malloc(sizeof(Header) + size));
        if (!h) return nullptr;
        h->size = size; used_ += size;
        return h + 1;
    }
    void deallocate(void* ptr) override {
        if (!ptr) return;
        Header* h = static_cast<Header*>(ptr) - 1;
        used_ -= h->size;
        std::free(h);
    }
    void* reallocate(void* ptr, size_t size) override {
        if (!ptr) return allocate(size);
        if (!size) { deallocate(ptr); return nullptr; }
        Header* old = static_cast<Header*>(ptr) - 1;
        size_t before = old->size;
        if (size > LIMIT - (used_ - before)) return nullptr;
        Header* h = static_cast<Header*>(std::realloc(old, sizeof(Header) + size));
        if (!h) return nullptr;
        h->size = size; used_ = used_ - before + size;
        return h + 1;
    }
};

// Strict lexical preflight: ArduinoJson alone accepts trailing data and some
// relaxed JSON forms. Only the root object carries protocol fields; its member
// count is compared with the DOM to detect even escaped duplicate field names.
struct JsonShape {
    const uint8_t* p;
    size_t n, at = 0, items = 0, members = 0;
    JsonShape(const uint8_t* data, size_t size) : p(data), n(size) {}
    void ws() { while (at < n && (p[at] == ' ' || p[at] == '\t' || p[at] == '\r' || p[at] == '\n')) ++at; }
    bool take(char c) { ws(); if (at == n || p[at] != c) return false; ++at; return true; }
    int digit(unsigned c) {
        if (c >= '0' && c <= '9') return static_cast<int>(c - '0');
        if (c >= 'a' && c <= 'f') return static_cast<int>(c - 'a' + 10);
        if (c >= 'A' && c <= 'F') return static_cast<int>(c - 'A' + 10);
        return -1;
    }
    bool unicode(unsigned& value) {
        if (n - at < 4) return false;
        value = 0;
        for (unsigned i = 0; i < 4; ++i) {
            int d = digit(p[at++]);
            if (d < 0) return false;
            value = value * 16 + static_cast<unsigned>(d);
        }
        return true;
    }
    bool string() {
        if (!take('"')) return false;
        while (at < n) {
            unsigned c = p[at++];
            if (c == '"') return true;
            if (c < 32) return false;
            if (c == '\\') {
                if (at == n) return false;
                c = p[at++];
                if (c == 'u') {
                    unsigned u;
                    if (!unicode(u) || (u >= 0xdc00 && u <= 0xdfff)) return false;
                    if (u >= 0xd800 && u <= 0xdbff) {
                        if (n - at < 6 || p[at++] != '\\' || p[at++] != 'u' ||
                            !unicode(u) || u < 0xdc00 || u > 0xdfff) return false;
                    }
                } else if (!c || !std::strchr("\"\\/bfnrt", c)) return false;
            } else if (c >= 128) {
                unsigned more, minimum;
                if (c >= 0xc2 && c <= 0xdf) { more = 1; minimum = 0x80; c &= 31; }
                else if (c >= 0xe0 && c <= 0xef) { more = 2; minimum = 0x800; c &= 15; }
                else if (c >= 0xf0 && c <= 0xf4) { more = 3; minimum = 0x10000; c &= 7; }
                else return false;
                if (more > n - at) return false;
                while (more--) {
                    unsigned b = p[at++];
                    if ((b & 0xc0) != 0x80) return false;
                    c = (c << 6) | (b & 63);
                }
                if (c < minimum || c > 0x10ffff || (c >= 0xd800 && c <= 0xdfff)) return false;
            }
        }
        return false;
    }
    bool number() {
        size_t start = at;
        if (at < n && p[at] == '-') ++at;
        if (at == n) return false;
        if (p[at] == '0') ++at;
        else {
            if (p[at] < '1' || p[at] > '9') return false;
            while (at < n && p[at] >= '0' && p[at] <= '9') ++at;
        }
        if (at < n && p[at] == '.') {
            ++at;
            size_t digits = at;
            while (at < n && p[at] >= '0' && p[at] <= '9') ++at;
            if (at == digits) return false;
        }
        if (at < n && (p[at] == 'e' || p[at] == 'E')) {
            ++at;
            if (at < n && (p[at] == '-' || p[at] == '+')) ++at;
            size_t digits = at;
            while (at < n && p[at] >= '0' && p[at] <= '9') ++at;
            if (at == digits) return false;
        }
        return at - start <= 32;
    }
    bool value(unsigned depth) {
        if (depth > 8 || ++items > 1024) return false;
        ws();
        if (at == n) return false;
        char c = static_cast<char>(p[at]);
        if (c == '"') return string();
        if (c == '{' || c == '[') {
            bool object = c == '{';
            ++at; ws();
            char end = object ? '}' : ']';
            if (at < n && p[at] == end) { ++at; return true; }
            while (true) {
                if (object) {
                    if (!string() || !take(':')) return false;
                    if (depth == 0) ++members;
                }
                if (!value(depth + 1)) return false;
                ws();
                if (at == n) return false;
                if (p[at] == end) { ++at; return true; }
                if (p[at++] != ',') return false;
            }
        }
        const char* literal = c == 't' ? "true" : c == 'f' ? "false" : c == 'n' ? "null" : nullptr;
        if (literal) {
            size_t length = std::strlen(literal);
            if (length > n - at || std::memcmp(p + at, literal, length)) return false;
            at += length; return true;
        }
        return number();
    }
    bool check() { return n && p[0] == '{' && value(0) && (ws(), at == n); }
};
bool jsonString(JsonVariantConst value, std::string& out) {
    if (!value.is<const char*>()) return false;
    JsonString s = value.as<JsonString>();
    out.assign(s.c_str(), s.size());
    return true;
}
} // namespace

void Relay::note(const std::string& text) { if (note_) note_(text); }
void Relay::fail(const char* reason) {
    active_ = false;
    disconnect_ = true;
    note(reason);
}
bool Relay::setAllowedPeers(const std::vector<std::string>& nodes) {
    if (nodes.size() > 8) return false;
    for (size_t i = 0; i < nodes.size(); ++i) {
        if (!hexId(nodes[i], 16) || nodes[i] == node_) return false;
        for (size_t j = 0; j < i; ++j) if (nodes[i] == nodes[j]) return false;
    }
    allowedPeers_ = nodes;
    note("TEST peer filter count=" + std::to_string(nodes.size()));
    if (active_ && peerHello_ && !nodes.empty() && !contains(nodes, peerNode_))
        fail("TEST current peer excluded");
    return true;
}
void Relay::resetSession() {
    helloPending_ = peerHello_ = inventoryDirty_ = blocked_ = false;
    peerMax_ = 0;
    peerNode_.clear();
    beganMs_ = inventoryMs_ = windowMs_ = blockedMs_ = retryMs_ = 0;
    rxBytes_ = txBytes_ = 0;
    rxWindow_ = txWindow_ = rxMessages_ = txMessages_ = 0;
    remoteIds_.clear(); advertised_.clear(); completed_.clear(); acks_.clear();
    requests_.clear(); offers_.clear();
#ifdef MESH_GATEWAY_TTS
    remoteIds_.shrink_to_fit(); advertised_.shrink_to_fit(); completed_.shrink_to_fit(); acks_.shrink_to_fit();
    requests_.shrink_to_fit(); offers_.shrink_to_fit();
#endif
}
bool Relay::begin(const std::string& node,
                  std::function<bool(const uint8_t*, size_t)> send,
                  std::function<void(const std::string&)> callback,
                  std::function<void(const BundleInfo&)> accepted) {
    ready_ = active_ = disconnect_ = false;
    resetSession();
    entries_.clear(); fileCount_ = diskBytes_ = 0;
    seen_.clear(); seenHealthy_ = true;
    node_ = node; send_ = std::move(send); note_ = std::move(callback);
    accepted_ = std::move(accepted);
    if (!hexId(node_, 16) || !send_) { note("relay configuration invalid"); return false; }
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
            entries_.push_back(std::move(e)); // cold=true; no fabricated received timestamp.
        } else note("invalid store file retained");
        f = root.openNextFile();
    }
    root.close();
    return true;
}
void Relay::connected() {
    resetSession();
    disconnect_ = false;
    if (!ready_) { fail("relay not ready"); return; }
    active_ = true;
    beganMs_ = windowMs_ = clockMs();
    helloPending_ = true;
    note("mesh connected");
}
void Relay::disconnected() {
    active_ = disconnect_ = false;
    resetSession();
}
void Relay::window(uint64_t now) {
    if (now - windowMs_ >= 60000) { windowMs_ = now; rxWindow_ = txWindow_ = 0; }
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
bool Relay::gatewayProtected(const Entry& e) const {
    return gateway_ && !e.delivered && (e.kind == 1 || e.kind == 5 || e.kind == 8173);
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
std::vector<std::string> Relay::retainedIds() const {
    std::vector<std::string> ids; for (const Entry& e : entries_) ids.push_back(e.id); return ids;
}
bool Relay::ingressReadState(std::vector<uint8_t>& bytes, bool& exists) {
    bytes.clear(); exists = false;
    if (!ready_) return false;
    errno = 0; exists = LittleFS.exists("/ingress-state");
    if (!exists) return errno == 0 || errno == ENOENT;
    File file = LittleFS.open("/ingress-state", "r");
    if (!file || file.isDirectory() || !file.size() || file.size() > 8192) { file.close(); return false; }
    bytes.resize(file.size()); errno = 0;
    bool ok = file.read(bytes.data(),bytes.size()) == bytes.size() && errno == 0;
    file.close(); ok = ok && errno == 0;
    if (!ok) bytes.clear();
    return ok;
}
bool Relay::ingressWriteState(const std::vector<uint8_t>& bytes) {
    if (!ready_ || bytes.empty() || bytes.size() > 8192) return false;
    const char* temp = "/ingress-tmp";
    // An interrupted temp is never promoted. Only a newly verified worker result
    // can replace the atomic high-water checkpoint, before any ingress advertise.
    if (LittleFS.exists(temp) && !LittleFS.remove(temp)) return false;
    errno = 0; File file = LittleFS.open(temp,"w");
    if (!file) return false;
    // Arduino's create-on-missing open can succeed while leaving ENOENT behind.
    errno = 0;
    bool ok = file.write(bytes.data(),bytes.size()) == bytes.size();
    file.flush(); ok = ok && errno == 0 && file.size() == bytes.size();
    file.close(); ok = ok && errno == 0;
    if (!ok) { LittleFS.remove(temp); return false; }
    errno = 0;
    if (!LittleFS.rename(temp,"/ingress-state")) { LittleFS.remove(temp); return false; }
    file = LittleFS.open("/ingress-state","r");
    if (!file || file.isDirectory() || file.size() != bytes.size()) { file.close(); return false; }
    uint8_t chunk[256];
    for (size_t at = 0; at < bytes.size();) {
        size_t n = std::min(sizeof(chunk),bytes.size() - at);
        errno = 0;
        if (file.read(chunk,n) != n || errno || std::memcmp(chunk,bytes.data() + at,n)) { file.close(); return false; }
        at += n;
    }
    errno = 0; file.close(); return errno == 0;
}
bool Relay::ingressSave(const std::vector<uint8_t>& bytes, const std::string& id,
                        const std::string& org, unsigned priority, std::function<bool()> stillAuthorized) {
    if (!ready_ || !seenHealthy_ || !hexId(id) || !stillAuthorized || !stillAuthorized() || find(id) || wasSeen(id) || !seenHealthy_) return false;
    size_t capacity = STORE_FILES - fileCount_;
    for (const Entry& entry : entries_) if (!gatewayProtected(entry)) ++capacity;
    const size_t historyCapacity = STORE_FILES - fileCount_ + SEEN_LIMIT - seen_.size();
    // A download must not steal capacity already reserved by BLE WANTs.
    if (capacity <= requests_.size() || historyCapacity <= requests_.size()) return false;
    BundleInfo info; std::string error;
    if (!validateBundle(bytes.data(),bytes.size(),info,error) || info.id != id || info.kind != 1 || !save(bytes.data(),bytes.size(),info,clockMs())) return false;
    if (!stillAuthorized()) {
        for (Entry& e : entries_) if (e.id == id) e.cold = true;
        note("ingress authorization expired during persistence; retained cold, not advertised");
        return false;
    }
    info.certifiedImporter = org; info.effectivePriority = std::min(priority,3u);
    inventoryDirty_ = true;
    if (accepted_) accepted_(info);
    note("ingress saved " + id.substr(0,8) + " importer-certified priority=" + std::to_string(info.effectivePriority));
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
        inventoryDirty_ = true;
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
    e.receivedMs = receivedMs; e.cold = false;
    entries_.push_back(std::move(e));
    return true;
}
void Relay::fillRequests() {
    // Plan only; inventory must never remove a file. Empty slots and verified
    // victims supply capacity; invalid diagnostics cannot be reclaimed. Bound
    // outstanding requests separately from the session's replay history. Reserve
    // a history entry for each request so no issued WANT exceeds the 128-ID cap.
    if (!seenHealthy_) return;
    size_t capacity = STORE_FILES - fileCount_;
    for (const Entry& e : entries_) if (!gatewayProtected(e)) ++capacity;
    const size_t historyCapacity = STORE_FILES - fileCount_ + SEEN_LIMIT - seen_.size();
    for (auto it = remoteIds_.rbegin(); it != remoteIds_.rend(); ++it) {
        const std::string& id = *it;
        if (requests_.size() >= MAX_REQUESTS || requests_.size() >= capacity ||
            completed_.size() + requests_.size() >= SESSION_COMPLETED_IDS || requests_.size() >= historyCapacity) break;
        if (find(id) || contains(completed_, id) || wasSeen(id)) continue;
        if (!seenHealthy_) return;
        bool exists = false;
        for (const Request& r : requests_) if (r.id == id) { exists = true; break; }
        if (!exists) { Request r; r.id = id; requests_.push_back(std::move(r)); }
    }
}
void Relay::receive(const uint8_t* data, size_t size) {
    if (!active_) return;
    uint64_t now = clockMs();
    window(now);
    if (++rxWindow_ > MINUTE_MESSAGES || ++rxMessages_ > SESSION_MESSAGES ||
        size > SESSION_BYTES - rxBytes_) { fail("mesh receive limit"); return; }
    rxBytes_ += size;
    if (!data || !size || size > MAX_BUNDLE_BYTES) { fail("mesh frame size invalid"); return; }
    if (now - beganMs_ >= HELLO_MS && !peerHello_) { fail("mesh HELLO timeout"); return; }
    if (data[0] == '{') {
        if (size > CONTROL_BYTES) { fail("mesh control size limit"); return; }
        control(data, size, now);
    } else if (data[0] == 0x9f && peerHello_) bundle(data, size, now);
    else fail("mesh HELLO/frame invalid");
}
void Relay::control(const uint8_t* data, size_t size, uint64_t now) {
    JsonShape shape(data, size);
    const bool validShape = shape.check();
#ifdef MESH_TEST_DIAGNOSTICS
    if (!peerHello_ && size <= 256) {
        static const char* hex = "0123456789abcdef";
        std::string encoded;
        for (size_t i = 0; i < size; ++i) { encoded += hex[data[i] >> 4]; encoded += hex[data[i] & 15]; }
        note("TEST initial control bytes=" + std::to_string(size) + " valid=" +
             std::to_string(validShape) + " at=" + std::to_string(shape.at) + " hex=" + encoded);
    }
#endif
    if (!validShape) { fail("mesh JSON invalid"); return; }
    JsonBudget budget;
    JsonDocument doc(&budget);
    if (deserializeJson(doc, reinterpret_cast<const char*>(data), size,
                        DeserializationOption::NestingLimit(8)) || !doc.is<JsonObject>() ||
        doc.size() != shape.members || !doc["v"].is<unsigned>() || doc["v"].as<unsigned>() != 1) {
        fail("mesh control invalid"); return;
    }
    std::string type;
    if (!jsonString(doc["type"], type)) { fail("mesh type invalid"); return; }
    if (type == "hello") {
        std::string peer;
        if (peerHello_ || !jsonString(doc["node"], peer) || !hexId(peer, 16) || peer == node_ ||
            !doc["max"].is<unsigned>() || doc["max"].as<unsigned>() < 1 || doc["max"].as<unsigned>() > 32768) {
            fail("mesh HELLO invalid"); return;
        }
        if (!allowedPeers_.empty() && !contains(allowedPeers_, peer)) {
            note("TEST peer rejected node=" + peer);
            fail("TEST peer not allowed"); return;
        }
        peerNode_ = peer;
        peerHello_ = true;
        peerMax_ = doc["max"].as<unsigned>();
        inventoryDirty_ = true;
        note("mesh HELLO accepted node=" + peer);
        return;
    }
    if (!peerHello_ || doc.size() != 3 || !doc["ids"].is<JsonArrayConst>() ||
        (type != "inventory" && type != "want" && type != "ack")) { fail("mesh control state invalid"); return; }
    JsonArrayConst array = doc["ids"].as<JsonArrayConst>();
    if (array.size() > CONTROL_IDS || (type == "ack" && array.size() != 1)) { fail("mesh ID count invalid"); return; }
    std::vector<std::string> ids;
    ids.reserve(array.size());
    for (JsonVariantConst v : array) {
        std::string id;
        if (!jsonString(v, id) || !hexId(id) || contains(ids, id)) { fail("mesh ID invalid/duplicate"); return; }
        ids.push_back(std::move(id));
    }
    if (type == "inventory") {
        remoteIds_ = std::move(ids);
        offers_.erase(std::remove_if(offers_.begin(), offers_.end(), [&](const Offer& o) {
            return contains(remoteIds_, o.id);
        }), offers_.end());
        requests_.erase(std::remove_if(requests_.begin(), requests_.end(), [&](const Request& r) {
            return !r.issued; // Re-plan unsent requests from the newest inventory tail.
        }), requests_.end());
        fillRequests();
    } else if (type == "want") {
        for (const std::string& id : ids) {
            const Entry* e = find(id);
            if (!e || !eligible(*e, now) || !contains(advertised_, id)) continue;
            bool queued = false;
            for (const Offer& o : offers_) if (o.id == id) { queued = true; break; }
            if (!queued && offers_.size() < STORE_FILES) { Offer o; o.id = id; offers_.push_back(std::move(o)); }
        }
    } else {
        // ACKs are only meaningful for a known ID actually enqueued this session.
        for (const Offer& offer : offers_) {
            if (offer.attempts && offer.id == ids[0]) note("peer accepted " + offer.id.substr(0, 8));
        }
        offers_.erase(std::remove_if(offers_.begin(), offers_.end(), [&](const Offer& o) {
            return o.attempts && o.id == ids[0];
        }), offers_.end());
    }
}
void Relay::bundle(const uint8_t* data, size_t size, uint64_t now) {
    bool expecting = !completed_.empty();
    for (const Request& r : requests_) if (r.issued) expecting = true;
    if (!expecting) { fail("unsolicited bundle"); return; }
    BundleInfo info;
    std::string error;
    if (!validateBundle(data, size, info, error)) {
        note("bundle rejected: " + error);
        fail("bundle verification failed"); return;
    }
    auto request = std::find_if(requests_.begin(), requests_.end(), [&](const Request& r) {
        return r.issued && r.id == info.id;
    });
    bool duplicate = contains(completed_, info.id);
    if (request == requests_.end() && !duplicate) { fail("unrequested event ID"); return; }
    if (request != requests_.end() && now - request->sentMs >= REQUEST_MS) { fail("bundle request timeout"); return; }
    const Entry* existing = find(info.id);
    if (!existing) {
        if (duplicate) { fail("duplicate entry missing; no ACK"); return; }
        if (completed_.size() >= SESSION_COMPLETED_IDS) { fail("session history limit; no ACK"); return; }
        if (!save(data, size, info, now)) return; // save reports the precise stage/errno.
        completed_.push_back(info.id); // Retained even if this cache entry is later evicted.
        note("saved " + info.id.substr(0, 8) + " hop " + std::to_string(info.hop) + " " + std::to_string(size) + "B");
        if (accepted_) accepted_(info);
    } else {
        // A validated retransmission never overwrites or refreshes residence.
        std::vector<uint8_t> bytes;
        BundleInfo persisted;
        if (!read(*existing, bytes) || !validateBundle(bytes.data(), bytes.size(), persisted, error) || persisted.id != info.id) {
            fail("duplicate store invalid; no ACK"); return;
        }
        note("duplicate " + info.id.substr(0, 8));
    }
    if (request != requests_.end()) requests_.erase(request);
    if (!contains(acks_, info.id)) {
        if (acks_.size() >= STORE_FILES) { fail("ACK backlog limit"); return; }
        acks_.push_back(info.id);
    }
    inventoryDirty_ = true;
    fillRequests();
}
bool Relay::transmit(const uint8_t* data, size_t size, uint64_t now) {
    window(now);
    if (size > MAX_BUNDLE_BYTES || ++txWindow_ > MINUTE_MESSAGES || txMessages_ >= SESSION_MESSAGES ||
        size > SESSION_BYTES - txBytes_) { fail("mesh send limit"); return false; }
    if (!send_(data, size)) {
        --txWindow_; // A queue refusal is not a transmitted message.
        if (!blocked_) { blocked_ = true; blockedMs_ = now; }
        retryMs_ = now + RETRY_MS;
        if (now - blockedMs_ >= BACKPRESSURE_MS) fail("mesh send queue stalled");
        return false;
    }
    blocked_ = false;
    ++txMessages_; txBytes_ += size;
    return true;
}
bool Relay::sendIds(const char* type, const std::vector<std::string>& ids, uint64_t now) {
    // All IDs and type names are internal validated ASCII, so no escaping needed.
    std::string wire = "{\"v\":1,\"type\":\"" + std::string(type) + "\",\"ids\":[";
    for (size_t i = 0; i < ids.size(); ++i) {
        if (i) wire += ',';
        wire += '"'; wire += ids[i]; wire += '"';
    }
    wire += "]}";
    return transmit(reinterpret_cast<const uint8_t*>(wire.data()), wire.size(), now);
}
void Relay::tick() {
    if (!active_) return;
    uint64_t now = clockMs();
    if (!peerHello_ && now - beganMs_ >= HELLO_MS) { fail("mesh HELLO timeout"); return; }
    if (blocked_ && now - blockedMs_ >= BACKPRESSURE_MS) { fail("mesh send queue stalled"); return; }
    for (const Request& r : requests_) {
        if (r.issued && now - r.sentMs >= REQUEST_MS) { fail("mesh WANT timeout"); return; }
    }
    if (blocked_ && now < retryMs_) return;
    // One complete message per tick; transport queue credit remains authoritative.
    if (helloPending_) {
        std::string hello = "{\"v\":1,\"type\":\"hello\",\"node\":\"" + node_ + "\",\"max\":24000}";
        if (transmit(reinterpret_cast<const uint8_t*>(hello.data()), hello.size(), now)) helloPending_ = false;
        return;
    }
    if (!peerHello_) return;
    if (!acks_.empty()) {
        if (sendIds("ack", {acks_.front()}, now)) acks_.erase(acks_.begin());
        return;
    }
    // Drain durable-acceptance ACKs before asking the parent to reconnect.
    // Never age-reset or discard completed IDs within a live session: evicted
    // entries must not be requested again with a refreshed residence timestamp.
    if (completed_.size() >= SESSION_COMPLETED_IDS && requests_.empty()) {
        fail("mesh session completed ID limit"); return;
    }
    if (inventoryDirty_ || now - inventoryMs_ >= INVENTORY_MS) {
        std::vector<std::string> ids;
        for (const Entry& e : entries_) if (eligible(e, now)) ids.push_back(e.id);
        if (sendIds("inventory", ids, now)) {
            advertised_ = std::move(ids); inventoryDirty_ = false; inventoryMs_ = now;
        }
        return;
    }
    std::vector<std::string> wanted;
    for (const Request& r : requests_) if (!r.issued) wanted.push_back(r.id);
    if (!wanted.empty()) {
        if (sendIds("want", wanted, now)) {
            for (Request& r : requests_) if (!r.issued) { r.issued = true; r.sentMs = now; }
        }
        return;
    }
    for (size_t i = 0; i < offers_.size();) {
        Offer& offer = offers_[i];
        const Entry* e = find(offer.id);
        if (!e || !eligible(*e, now)) { offers_.erase(offers_.begin() + i); continue; }
        if (offer.attempts && now - offer.sentMs < INVENTORY_MS) { ++i; continue; }
        if (offer.attempts >= 3) { fail("mesh ACK timeout"); return; }
        std::vector<uint8_t> original, forwarded;
        if (!read(*e, original)) { fail("stored bundle unreadable"); return; }
        std::string error;
        if (!forwardBundle(original.data(), original.size(), now - e->receivedMs, forwarded, error)) {
            // Expiry or wire-size growth can make a previously advertised ID
            // unavailable. Correct the inventory rather than sending stale data.
            offers_.erase(offers_.begin() + i); inventoryDirty_ = true; return;
        }
        if (forwarded.size() > peerMax_) { offers_.erase(offers_.begin() + i); continue; }
        if (transmit(forwarded.data(), forwarded.size(), now)) {
            ++offer.attempts; offer.sentMs = now;
            note("sent " + e->id.substr(0, 8) + " hop " + std::to_string(e->hop + 1) + " " + std::to_string(forwarded.size()) + "B");
        }
        return;
    }
}
} // namespace mesh
