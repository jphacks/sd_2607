#include "Negentropy.h"

#include <algorithm>
#include <cstring>
#include <mbedtls/sha256.h>

namespace mesh {
namespace {
constexpr uint64_t INFINITE = UINT64_MAX;
constexpr uint64_t MODE_SKIP = 0, MODE_FINGERPRINT = 1, MODE_ID_LIST = 2;
constexpr size_t BUCKETS = 16, ID_SIZE = 32, FINGERPRINT_SIZE = 16;

// Timestamp first, then the ID (or prefix) lexicographically; a shorter equal prefix sorts first.
int compare(uint64_t t1, const uint8_t* a, size_t na, uint64_t t2, const uint8_t* b, size_t nb) {
    if (t1 != t2) return t1 < t2 ? -1 : 1;
    const int c = std::memcmp(a, b, std::min(na, nb));
    if (c) return c;
    return na < nb ? -1 : na > nb ? 1 : 0;
}
void varint(uint64_t value, std::vector<uint8_t>& o) {
    uint8_t groups[10];
    size_t n = 0;
    do { groups[n++] = value & 0x7f; value >>= 7; } while (value);
    while (n) { --n; o.push_back(static_cast<uint8_t>(groups[n] | (n ? 0x80 : 0))); }
}
struct Reader {
    const uint8_t* p; size_t n, at = 0;
    Reader(const uint8_t* data, size_t size) : p(data), n(size) {}
    bool empty() const { return at >= n; }
    bool byte(uint8_t& b) { if (at >= n) return false; b = p[at++]; return true; }
    bool bytes(uint8_t* out, size_t count) {
        if (count > n - at) return false;
        std::memcpy(out, p + at, count); at += count; return true;
    }
    bool varint(uint64_t& value) {
        value = 0;
        for (unsigned count = 0; count < 10; ++count) {
            uint8_t b;
            if (!byte(b) || value > (INT64_MAX >> 7)) return false;
            value = (value << 7) | (b & 0x7f);
            if (!(b & 0x80)) return true;
        }
        return false;
    }
};
} // namespace

constexpr uint8_t Negentropy::VERSION;
constexpr size_t Negentropy::MAX_RESULT_IDS;

Negentropy::Negentropy(std::vector<Item> items, size_t frameLimit) : items_(std::move(items)), frameLimit_(frameLimit) {
    std::sort(items_.begin(), items_.end(), [](const Item& a, const Item& b) {
        return compare(a.timestamp, a.id.data(), ID_SIZE, b.timestamp, b.id.data(), ID_SIZE) < 0;
    });
    items_.erase(std::unique(items_.begin(), items_.end(), [](const Item& a, const Item& b) {
        return a.timestamp == b.timestamp && a.id == b.id;
    }), items_.end());
    if (frameLimit_ && frameLimit_ < 512) frameLimit_ = 512;
}

std::vector<uint8_t> Negentropy::initiate() {
    initiator_ = true;
    lastOut_ = 0;
    std::vector<uint8_t> out{VERSION};
    Bound infinity{INFINITE, {}, 0};
    splitRange(0, items_.size(), infinity, out);
    return out;
}

bool Negentropy::reconcile(const uint8_t* data, size_t size, std::vector<uint8_t>& full, bool& done, std::string& error) {
    full.assign(1, VERSION);
    done = false;
    lastIn_ = lastOut_ = 0;
    Reader query(data, size);
    uint8_t version;
    if (!query.byte(version) || version < 0x60 || version > 0x6f) { error = "invalid negentropy version"; return false; }
    if (version != VERSION) {
        if (initiator_) { error = "unsupported negentropy version"; return false; }
        return true; // Responder answers with its own version only.
    }
    Bound prevBound{0, {}, 0};
    size_t prevIndex = 0, writtenIndex = 0;
    bool skip = false;
    std::vector<uint8_t> o;
    auto decodeBound = [&](Bound& bound) {
        uint64_t raw, length;
        if (!query.varint(raw)) return false;
        uint64_t timestamp = raw ? raw - 1 : INFINITE;
        if (lastIn_ == INFINITE || timestamp == INFINITE) timestamp = INFINITE;
        else {
            if (timestamp > INT64_MAX - lastIn_) return false;
            timestamp += lastIn_;
        }
        lastIn_ = timestamp;
        if (!query.varint(length) || length > ID_SIZE || !query.bytes(bound.prefix, static_cast<size_t>(length))) return false;
        bound.timestamp = timestamp;
        bound.length = static_cast<size_t>(length);
        return true;
    };
    while (!query.empty()) {
        o.clear();
        auto doSkip = [&]() {
            if (!skip) return;
            skip = false;
            encodeBound(prevBound, o);
            varint(MODE_SKIP, o);
        };
        Bound currBound;
        uint64_t mode;
        if (!decodeBound(currBound) || !query.varint(mode)) { error = "truncated negentropy message"; return false; }
        const size_t lower = prevIndex;
        size_t upper = findLowerBound(prevIndex, items_.size(), currBound);
        if (mode == MODE_SKIP) {
            skip = true;
        } else if (mode == MODE_FINGERPRINT) {
            uint8_t theirs[FINGERPRINT_SIZE], ours[FINGERPRINT_SIZE];
            if (!query.bytes(theirs, sizeof theirs)) { error = "truncated fingerprint"; return false; }
            fingerprint(lower, upper, ours);
            if (std::memcmp(theirs, ours, sizeof ours)) { doSkip(); splitRange(lower, upper, currBound, o); }
            else skip = true;
        } else if (mode == MODE_ID_LIST) {
            uint64_t count;
            if (!query.varint(count) || count > (query.n - query.at) / ID_SIZE) { error = "invalid ID list"; return false; }
            std::vector<Id> theirs(static_cast<size_t>(count));
            for (Id& id : theirs) query.bytes(id.data(), ID_SIZE);
            if (initiator_) {
                skip = true;
                std::vector<bool> matched(theirs.size(), false);
                for (size_t i = lower; i < upper; ++i) {
                    bool found = false;
                    for (size_t k = 0; k < theirs.size(); ++k)
                        if (!matched[k] && theirs[k] == items_[i].id) { matched[k] = found = true; break; }
                    if (!found && have_.size() < MAX_RESULT_IDS) have_.push_back(items_[i].id);
                }
                for (size_t k = 0; k < theirs.size(); ++k) {
                    if (matched[k] || need_.size() >= MAX_RESULT_IDS) continue;
                    if (std::find(need_.begin(), need_.end(), theirs[k]) == need_.end()) need_.push_back(theirs[k]);
                }
            } else {
                doSkip();
                std::vector<uint8_t> ids;
                uint64_t responded = 0;
                Bound endBound = currBound;
                for (size_t i = lower; i < upper; ++i) {
                    if (exceeded(full.size() + o.size() + ids.size())) {
                        endBound.timestamp = items_[i].timestamp;
                        std::memcpy(endBound.prefix, items_[i].id.data(), ID_SIZE);
                        endBound.length = ID_SIZE;
                        upper = i;
                        break;
                    }
                    ids.insert(ids.end(), items_[i].id.begin(), items_[i].id.end());
                    ++responded;
                }
                encodeBound(endBound, o); varint(MODE_ID_LIST, o); varint(responded, o);
                o.insert(o.end(), ids.begin(), ids.end());
                full.insert(full.end(), o.begin(), o.end()); o.clear();
                writtenIndex = upper;
            }
        } else { error = "unexpected negentropy mode"; return false; }
        if (exceeded(full.size() + o.size())) {
            // Stop and fingerprint everything the peer has not yet seen covered, up to infinity.
            Bound infinity{INFINITE, {}, 0};
            encodeBound(infinity, full); varint(MODE_FINGERPRINT, full);
            uint8_t fp[FINGERPRINT_SIZE];
            fingerprint(writtenIndex, items_.size(), fp);
            full.insert(full.end(), fp, fp + sizeof fp);
            break;
        }
        if (!o.empty()) { full.insert(full.end(), o.begin(), o.end()); writtenIndex = upper; }
        prevIndex = upper;
        prevBound = currBound;
    }
    done = initiator_ && full.size() == 1;
    return true;
}

void Negentropy::splitRange(size_t lower, size_t upper, const Bound& upperBound, std::vector<uint8_t>& o) {
    const size_t count = upper - lower;
    // An ID list must fit one frame; with small frames, split instead.
    if (count < BUCKETS * 2 && (!frameLimit_ || count * ID_SIZE + 64 + 200 <= frameLimit_)) {
        encodeBound(upperBound, o); varint(MODE_ID_LIST, o); varint(count, o);
        for (size_t i = lower; i < upper; ++i) o.insert(o.end(), items_[i].id.begin(), items_[i].id.end());
        return;
    }
    const size_t buckets = std::min(BUCKETS, count);
    const size_t perBucket = count / buckets, extra = count % buckets;
    size_t curr = lower;
    for (size_t i = 0; i < buckets; ++i) {
        const size_t size = perBucket + (i < extra ? 1 : 0);
        uint8_t fp[FINGERPRINT_SIZE];
        fingerprint(curr, curr + size, fp);
        curr += size;
        Bound next;
        if (curr == upper) next = upperBound;
        else {
            const Item& prev = items_[curr - 1];
            const Item& item = items_[curr];
            next.timestamp = item.timestamp;
            if (item.timestamp != prev.timestamp) next.length = 0;
            else {
                size_t shared = 0;
                while (shared < ID_SIZE && item.id[shared] == prev.id[shared]) ++shared;
                next.length = std::min(shared + 1, ID_SIZE);
                std::memcpy(next.prefix, item.id.data(), next.length);
            }
        }
        encodeBound(next, o); varint(MODE_FINGERPRINT, o);
        o.insert(o.end(), fp, fp + sizeof fp);
    }
}

size_t Negentropy::findLowerBound(size_t from, size_t to, const Bound& bound) const {
    size_t lo = from, hi = to;
    while (lo < hi) {
        const size_t mid = (lo + hi) / 2;
        if (compare(bound.timestamp, bound.prefix, bound.length, items_[mid].timestamp, items_[mid].id.data(), ID_SIZE) <= 0) hi = mid;
        else lo = mid + 1;
    }
    return lo;
}

// Sum of the IDs as 256-bit little-endian integers mod 2^256, then
// SHA-256(sum || varint(count)), first 16 bytes.
void Negentropy::fingerprint(size_t from, size_t to, uint8_t out[16]) const {
    uint8_t acc[ID_SIZE] = {};
    for (size_t i = from; i < to; ++i) {
        unsigned carry = 0;
        for (size_t b = 0; b < ID_SIZE; ++b) {
            const unsigned sum = acc[b] + items_[i].id[b] + carry;
            acc[b] = sum & 255; carry = sum >> 8;
        }
    }
    std::vector<uint8_t> input(acc, acc + ID_SIZE);
    varint(to - from, input);
    uint8_t digest[32];
    mbedtls_sha256_ret(input.data(), input.size(), digest, 0);
    std::memcpy(out, digest, FINGERPRINT_SIZE);
}

void Negentropy::encodeBound(const Bound& bound, std::vector<uint8_t>& o) {
    encodeTimestamp(bound.timestamp, o);
    varint(bound.length, o);
    o.insert(o.end(), bound.prefix, bound.prefix + bound.length);
}

void Negentropy::encodeTimestamp(uint64_t timestamp, std::vector<uint8_t>& o) {
    if (timestamp == INFINITE) { lastOut_ = INFINITE; varint(0, o); return; }
    const uint64_t delta = timestamp - lastOut_;
    lastOut_ = timestamp;
    varint(delta + 1, o);
}
} // namespace mesh
