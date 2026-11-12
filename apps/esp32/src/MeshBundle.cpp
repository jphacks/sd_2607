#include "MeshBundle.h"
#include "MeshCbor.h"

#include <algorithm>
#include <cstdio>
#include <cstring>

#include <mbedtls/bignum.h>
#include <mbedtls/ecp.h>
#include <mbedtls/sha256.h>

namespace mesh {
namespace {
using cbor::Slice;
using cbor::Reader;
using cbor::head;
using cbor::append;
constexpr size_t MAX_BLOCKS = 64;
constexpr size_t MAX_JSON_BYTES = 65536;
const char* DESTINATION = "//mosimo.jisaku.dev/~post";

bool equal(Slice s, const char* text) {
    return s.n == std::strlen(text) && std::memcmp(s.p, text, s.n) == 0;
}
bool startsWith(Slice s, const char* prefix) {
    const size_t n = std::strlen(prefix);
    return s.n >= n && std::memcmp(s.p, prefix, n) == 0;
}
std::string string(Slice s) { return std::string(reinterpret_cast<const char*>(s.p), s.n); }

// BPv7 transmits the complemented reflected CRC value in network byte order.
uint32_t crc(Slice block, Slice zeros, unsigned type) {
    uint32_t sum = type == 1 ? 0xffff : 0xffffffff;
    const uint32_t poly = type == 1 ? 0x8408 : 0x82f63b78;
    for (size_t i = 0; i < block.n; ++i) {
        const uint8_t* b = block.p + i;
        sum ^= b >= zeros.p && b < zeros.p + zeros.n ? 0 : *b;
        for (unsigned j = 0; j < 8; ++j) sum = (sum >> 1) ^ ((sum & 1) ? poly : 0);
    }
    return (~sum) & (type == 1 ? 0xffff : 0xffffffff);
}
bool checkCrc(Slice raw, Slice field, uint64_t type) {
    if (!type) return field.n == 0;
    if (type > 2 || field.n != (type == 1 ? 2u : 4u)) return false;
    uint32_t expected = 0;
    for (size_t i = 0; i < field.n; ++i) expected = (expected << 8) | field.p[i];
    return crc(raw, field, static_cast<unsigned>(type)) == expected;
}

struct Hash {
    mbedtls_sha256_context ctx;
    bool ok;
    Hash() { mbedtls_sha256_init(&ctx); ok = mbedtls_sha256_starts_ret(&ctx, 0) == 0; }
    ~Hash() { mbedtls_sha256_free(&ctx); }
    void add(const void* p, size_t n) { if (n && mbedtls_sha256_update_ret(&ctx, static_cast<const uint8_t*>(p), n)) ok = false; }
    void add(const std::string& s) { add(s.data(), s.size()); }
    void literal(const char* s) { add(s, std::strlen(s)); }
    bool finish(uint8_t out[32]) { return ok && mbedtls_sha256_finish_ret(&ctx, out) == 0; }
};

// NIP-01 serialization escaping (the same rules Android's signer uses).
void quote(std::string& out, Slice s) {
    out += '"';
    for (size_t i = 0; i < s.n; ++i) {
        const unsigned c = s.p[i];
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case 8: out += "\\b"; break;
            case 9: out += "\\t"; break;
            case 10: out += "\\n"; break;
            case 12: out += "\\f"; break;
            case 13: out += "\\r"; break;
            default:
                if (c < 32) {
                    const char* digits = "0123456789abcdef";
                    const char escape[] = {'\\', 'u', '0', '0', digits[c >> 4], digits[c & 15]};
                    out.append(escape, sizeof escape);
                } else out += static_cast<char>(c);
        }
    }
    out += '"';
}
void quote(std::string& out, const std::string& s) { quote(out, {reinterpret_cast<const uint8_t*>(s.data()), s.size()}); }

std::string base64(Slice s) {
    static const char* alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string out;
    out.reserve((s.n + 2) / 3 * 4);
    for (size_t i = 0; i < s.n; i += 3) {
        uint32_t x = uint32_t(s.p[i]) << 16;
        if (i + 1 < s.n) x |= uint32_t(s.p[i + 1]) << 8;
        if (i + 2 < s.n) x |= s.p[i + 2];
        out += alphabet[x >> 18]; out += alphabet[(x >> 12) & 63];
        // Core byte representation: standard alphabet, no padding.
        if (i + 1 < s.n) out += alphabet[(x >> 6) & 63];
        if (i + 2 < s.n) out += alphabet[x & 63];
    }
    return out;
}
// Multibase "b": lowercase RFC 4648 base32 without padding (CID strings).
std::string base32(Slice s) {
    static const char* alphabet = "abcdefghijklmnopqrstuvwxyz234567";
    std::string out = "b";
    uint32_t acc = 0; unsigned bits = 0;
    for (size_t i = 0; i < s.n; ++i) {
        acc = (acc << 8) | s.p[i]; bits += 8;
        while (bits >= 5) { bits -= 5; out += alphabet[(acc >> bits) & 31]; }
    }
    if (bits) out += alphabet[(acc << (5 - bits)) & 31];
    return out;
}
// atproto TID: 13 base32-sortable characters, most significant first.
std::string tid(uint64_t value) {
    static const char* alphabet = "234567abcdefghijklmnopqrstuvwxyz";
    std::string out(13, '2');
    for (int i = 12; i >= 0; --i) { out[i] = alphabet[value & 31]; value >>= 5; }
    return out;
}

// Days from 1970-01-01 (Howard Hinnant's civil calendar algorithms).
int64_t daysFromCivil(int64_t y, unsigned m, unsigned d) {
    y -= m <= 2;
    const int64_t era = (y >= 0 ? y : y - 399) / 400;
    const unsigned yoe = static_cast<unsigned>(y - era * 400);
    const unsigned doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
    const unsigned doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    return era * 146097 + static_cast<int64_t>(doe) - 719468;
}
// "YYYY-MM-DDTHH:MM:SSZ" (UTC, seconds precision) only; years 0000..9999.
bool formatSeconds(uint64_t seconds, std::string& out) {
    if (seconds > 253402300799ULL) return false; // 9999-12-31T23:59:59Z
    const int64_t z = static_cast<int64_t>(seconds / 86400) + 719468;
    const uint64_t rest = seconds % 86400;
    const int64_t era = z / 146097;
    const unsigned doe = static_cast<unsigned>(z - era * 146097);
    const unsigned yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    const unsigned doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    const unsigned mp = (5 * doy + 2) / 153;
    const unsigned d = doy - (153 * mp + 2) / 5 + 1, m = mp < 10 ? mp + 3 : mp - 9;
    const int64_t y = static_cast<int64_t>(yoe) + era * 400 + (m <= 2);
    char buffer[48];
    std::snprintf(buffer, sizeof buffer, "%04d-%02u-%02uT%02u:%02u:%02uZ", static_cast<int>(y), m, d,
                  unsigned(rest / 3600), unsigned(rest / 60 % 60), unsigned(rest % 60));
    out = buffer;
    return true;
}
bool parseSeconds(Slice s, uint64_t& seconds) {
    if (s.n != 20) return false;
    for (size_t i = 0; i < 20; ++i) {
        const char c = static_cast<char>(s.p[i]);
        const char expected = i == 4 || i == 7 ? '-' : i == 10 ? 'T' : i == 13 || i == 16 ? ':' : i == 19 ? 'Z' : 0;
        if (expected ? c != expected : (c < '0' || c > '9')) return false;
    }
    auto num = [&](size_t at, size_t width) { unsigned v = 0; for (size_t i = 0; i < width; ++i) v = v * 10 + (s.p[at + i] - '0'); return v; };
    const unsigned year = num(0, 4), month = num(5, 2), day = num(8, 2), hour = num(11, 2), minute = num(14, 2), second = num(17, 2);
    static const unsigned days[] = {31,28,31,30,31,30,31,31,30,31,30,31};
    if (month < 1 || month > 12 || hour > 23 || minute > 59 || second > 59) return false;
    const unsigned maximum = days[month - 1] + (month == 2 && year % 4 == 0 && (year % 100 != 0 || year % 400 == 0));
    if (day < 1 || day > maximum) return false;
    const int64_t total = daysFromCivil(year, month, day) * 86400 + hour * 3600 + minute * 60 + second;
    if (total < 0) return false;
    seconds = static_cast<uint64_t>(total);
    return true;
}

struct Mpi {
    mbedtls_mpi v;
    Mpi() { mbedtls_mpi_init(&v); }
    ~Mpi() { mbedtls_mpi_free(&v); }
};
struct Curve {
    mbedtls_ecp_group g;
    mbedtls_ecp_point p, r;
    Curve() { mbedtls_ecp_group_init(&g); mbedtls_ecp_point_init(&p); mbedtls_ecp_point_init(&r); }
    ~Curve() { mbedtls_ecp_point_free(&r); mbedtls_ecp_point_free(&p); mbedtls_ecp_group_free(&g); }
};
} // namespace

std::string hex(const uint8_t* p, size_t n) {
    static const char* digits = "0123456789abcdef";
    std::string out(n * 2, '0');
    for (size_t i = 0; i < n; ++i) { out[i * 2] = digits[p[i] >> 4]; out[i * 2 + 1] = digits[p[i] & 15]; }
    return out;
}

// BIP340 verification only: no secrets, signing, RNG, or new crypto dependency.
// Explicit lift_x is needed because mbedTLS 2.x cannot import compressed points.
bool verifySchnorr(const uint8_t pub[32], const uint8_t sig[64], const uint8_t message[32]) {
    Curve c;
    Mpi r, s, e, y2, exponent;
    if (mbedtls_ecp_group_load(&c.g, MBEDTLS_ECP_DP_SECP256K1) ||
        mbedtls_mpi_read_binary(&c.p.X, pub, 32) ||
        mbedtls_mpi_read_binary(&r.v, sig, 32) ||
        mbedtls_mpi_read_binary(&s.v, sig + 32, 32) ||
        mbedtls_mpi_cmp_mpi(&c.p.X, &c.g.P) >= 0 ||
        mbedtls_mpi_cmp_mpi(&r.v, &c.g.P) >= 0 || mbedtls_mpi_cmp_mpi(&s.v, &c.g.N) >= 0) return false;
    // y = (x^3 + 7)^((p+1)/4) mod p; then choose the even square root.
    if (mbedtls_mpi_mul_mpi(&y2.v, &c.p.X, &c.p.X) ||
        mbedtls_mpi_mod_mpi(&y2.v, &y2.v, &c.g.P) ||
        mbedtls_mpi_mul_mpi(&y2.v, &y2.v, &c.p.X) ||
        mbedtls_mpi_add_int(&y2.v, &y2.v, 7) ||
        mbedtls_mpi_mod_mpi(&y2.v, &y2.v, &c.g.P) ||
        mbedtls_mpi_add_int(&exponent.v, &c.g.P, 1) || mbedtls_mpi_shift_r(&exponent.v, 2) ||
        mbedtls_mpi_exp_mod(&c.p.Y, &y2.v, &exponent.v, &c.g.P, nullptr)) return false;
    if (mbedtls_mpi_get_bit(&c.p.Y, 0) && mbedtls_mpi_sub_mpi(&c.p.Y, &c.g.P, &c.p.Y)) return false;
    if (mbedtls_mpi_lset(&c.p.Z, 1) || mbedtls_ecp_check_pubkey(&c.g, &c.p)) return false;
    uint8_t tag[32], challenge[32];
    Hash tagHash;
    tagHash.literal("BIP0340/challenge");
    if (!tagHash.finish(tag)) return false;
    Hash h;
    h.add(tag, 32); h.add(tag, 32); h.add(sig, 32); h.add(pub, 32); h.add(message, 32);
    if (!h.finish(challenge) || mbedtls_mpi_read_binary(&e.v, challenge, 32) ||
        mbedtls_mpi_mod_mpi(&e.v, &e.v, &c.g.N)) return false;
    if (mbedtls_mpi_cmp_int(&e.v, 0) && mbedtls_mpi_sub_mpi(&e.v, &c.g.N, &e.v)) return false;
    if (mbedtls_ecp_muladd(&c.g, &c.r, &s.v, &c.g.G, &e.v, &c.p)) return false;
    return !mbedtls_ecp_is_zero(&c.r) && !mbedtls_mpi_get_bit(&c.r.Y, 0) &&
           mbedtls_mpi_cmp_mpi(&c.r.X, &r.v) == 0;
}

namespace {
// Mesh §2.1: the 7-element payload, typed tag values (§2.1.1) and omitted
// fixed tags (§2.1.2) are reversed into the original Nostr tags. Only exact
// round-trip forms decode; anything else is rejected, never guessed.
bool event(Slice payload, BundleInfo& info, std::string& error, std::string* json = nullptr) {
    if (payload.n > MAX_PAYLOAD_BYTES) { error = "payload size limit"; return false; }
    Reader p(payload);
    uint64_t count, version, created, kind, tagCount;
    Slice pub, sig, content;
    if (!p.array(7, count) || !p.uint(version) || version != 1 || !p.bytes(2, pub) || pub.n != 32 ||
        !p.uint(created) || !p.uint(kind) || kind > 65535 || !p.array(tagCount) ||
        (tagCount != UINT64_MAX && tagCount > 256)) { error = "invalid event header"; return false; }
    std::string tags = "[";
    if (kind == 1) tags += "[\"t\",\"mosimo.jisaku.dev\"],[\"mosimo:v\",\"1\"]";
    else if (kind == 8173) tags += "[\"t\",\"mosimo.jisaku.dev\"]";
    const bool omitted = kind == 1 || kind == 8173;
    bool hasDeadline = false;
    uint64_t deadline = 0;
    for (uint64_t i = 0; tagCount == UINT64_MAX ? !p.atBreak() : i < tagCount; ++i) {
        if (i >= 256) { error = "too many tags"; return false; }
        uint64_t length;
        Slice name;
        // Tags are definite arrays (CDDL compact-tag); typed values need the exact element count.
        if (!p.array(length) || length == 0 || length == UINT64_MAX || length > 32 || !p.bytes(3, name)) {
            error = "invalid event tag"; return false;
        }
        const bool isDeadline = equal(name, "mosimo:exp");
        if (isDeadline && hasDeadline) { error = "duplicate signed deadline"; return false; }
        if (i || omitted) tags += ',';
        tags += '[';
        quote(tags, name);
        std::string first;
        uint64_t j = 1;
        for (; j < length; ++j) {
            tags += ',';
            const int major = p.major();
            const size_t valueStart = tags.size();
            Slice value;
            if (major == 3) {
                if (!p.bytes(3, value)) { error = "invalid UTF-8 tag"; return false; }
                quote(tags, value);
            } else if (j != 1 || length != 2) {
                error = "typed value outside position 1 of a two-element tag"; return false;
            } else if (major == 2) {
                if (!p.bytes(2, value)) { error = "invalid typed byte tag"; return false; }
                if (equal(name, "mosimo:commit") || (startsWith(name, "mosimo:ext:") && name.n > 11)) quote(tags, base64(value));
                else if (equal(name, "mosimo:base")) quote(tags, value.n ? base32(value) : std::string());
                else { error = "unexpected typed byte tag"; return false; }
            } else if (major == 0) {
                uint64_t number;
                if (!p.uint(number)) { error = "invalid typed integer tag"; return false; }
                std::string text;
                if (equal(name, "mosimo:rkey")) text = tid(number);
                else if (equal(name, "mosimo:exp")) {
                    if (number > cbor::SIGNED_MAX - created || !formatSeconds(created + number, text)) {
                        error = "deadline outside the Timestamp range"; return false;
                    }
                } else { error = "unexpected typed integer tag"; return false; }
                quote(tags, text);
            } else { error = "unexpected tag value type"; return false; }
            if (j == 1) first = tags.substr(valueStart + 1, tags.size() - valueStart - 2);
        }
        tags += ']';
        if (isDeadline) {
            // Signed delivery deadline: exactly ["mosimo:exp", YYYY-MM-DDTHH:MM:SSZ].
            if (j != 2 || !parseSeconds({reinterpret_cast<const uint8_t*>(first.data()), first.size()}, deadline)) {
                error = "invalid signed deadline"; return false;
            }
            hasDeadline = true;
        }
        if (kind == 8173 && i == 0 && j == 2 && equal(name, "t") && first.compare(0, 18, "mosimo.jisaku.dev/") == 0)
            info.topic = first;
        // delete §1 / report §1: tags[1] is the `e` target, tags[2] a report's `p` author (both 64 lowercase hex).
        auto isId = [](const std::string& v) {
            return v.size() == 64 && std::all_of(v.begin(), v.end(), [](char c) { return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'); });
        };
        if ((kind == 5 || kind == 1984) && i == 1 && j >= 2 && equal(name, "e") && isId(first)) info.target = first;
        if (kind == 1984 && i == 2 && j >= 2 && equal(name, "p") && isId(first)) info.reported = first;
        if (tags.size() > MAX_JSON_BYTES) { error = "tag JSON size limit"; return false; }
    }
    if (!p.end(tagCount)) { error = "invalid tag list end"; return false; }
    tags += ']';
    if (!hasDeadline || !p.bytes(3, content) || !p.bytes(2, sig) || sig.n != 64 || !p.end(count) || !p.done()) {
        error = "invalid event content/signature/deadline"; return false;
    }
    const std::string pubHex = hex(pub.p, pub.n);
    Hash h;
    h.literal("[0,\""); h.add(pubHex); h.literal("\","); h.add(std::to_string(created));
    h.literal(","); h.add(std::to_string(kind)); h.literal(","); h.add(tags); h.literal(",");
    std::string quoted;
    quoted.reserve(content.n + 16);
    quote(quoted, content);
    h.add(quoted); h.literal("]");
    uint8_t digest[32];
    if (!h.finish(digest) || !verifySchnorr(pub.p, sig.p, digest)) { error = "invalid event signature"; return false; }
    info.id = hex(digest, 32); info.text = string(content); info.pubkey = pubHex;
    info.kind = static_cast<uint16_t>(kind); info.createdAt = created; info.expiresAt = deadline;
    if (json) {
        if (tags.size() + quoted.size() > MAX_JSON_BYTES) { error = "export JSON size limit"; return false; }
        *json = "{\"id\":\"" + info.id + "\",\"pubkey\":\"" + pubHex + "\",\"created_at\":" + std::to_string(created) +
            ",\"kind\":" + std::to_string(kind) + ",\"tags\":" + tags + ",\"content\":" + quoted +
            ",\"sig\":\"" + hex(sig.p, sig.n) + "\"}";
    }
    return true;
}

struct Block {
    uint64_t type = 0, number = 0, flags = 0, crcType = 0;
    Slice raw, data;
    bool drop = false;
};
struct Parsed {
    Slice primary;
    std::vector<Block> blocks;
    BundleInfo info;
};

bool eid(Reader& r, bool* null = nullptr, bool destination = false) {
    uint64_t count, scheme, value;
    if (!r.array(2, count) || !r.uint(scheme)) return false;
    bool isNull = false;
    if (scheme == 1) {
        if (r.major() == 3) {
            Slice s;
            if (!r.bytes(3, s) || s.n < 3 || s.p[0] != '/' || s.p[1] != '/' ||
                (destination && !equal(s, DESTINATION))) return false;
            size_t path = 2;
            while (path < s.n && s.p[path] != '/' && s.p[path] != '?' && s.p[path] != '#') ++path;
            if (path == 2 || path == s.n || s.p[path] != '/') return false;
            // RFC 3986 URI syntax: reject spaces/control, non-ASCII and malformed escapes.
            for (size_t i = 0; i < s.n; ++i) {
                unsigned c = s.p[i];
                const char* allowed = "-._~:/?#[]@!$&'()*+,;=";
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || (c && std::strchr(allowed, c)))
                    continue;
                if (c != '%' || i + 2 >= s.n) return false;
                for (int j = 0; j < 2; ++j) {
                    c = s.p[++i];
                    if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
                }
            }
        } else {
            if (destination || !r.uint(value) || value != 0) return false;
            isNull = true;
        }
    } else if (scheme == 2 && !destination) {
        uint64_t pair;
        if (!r.array(2, pair) || !r.uint(value) || !r.uint(value) || !r.end(pair)) return false;
    } else return false;
    if (null) *null = isNull;
    return r.end(count);
}

bool parse(Slice bytes, Parsed& result, std::string& error, std::string* json = nullptr) {
    if (!bytes.p || bytes.n == 0 || bytes.n > MAX_BUNDLE_BYTES) { error = "bundle size limit"; return false; }
    Reader r(bytes);
    uint64_t outer, primaryCount, version, flags, crcType, timestamp, sequence, timeCount;
    if (!r.array(outer) || (outer != UINT64_MAX && (outer < 4 || outer > MAX_BLOCKS + 1))) {
        error = "invalid bundle array"; return false;
    }
    size_t start = r.at;
    bool anonymous = false;
    Slice crcField;
    if (!r.array(9, primaryCount) || !r.uint(version) || version != 7 || !r.uint(flags) || (flags & 3) ||
        !r.uint(crcType) || crcType < 1 || crcType > 2 || !eid(r, nullptr, true) || !eid(r, &anonymous) || !eid(r) ||
        !r.array(2, timeCount) || !r.uint(timestamp) || !r.uint(sequence) || !r.end(timeCount) ||
        !r.uint(result.info.lifetime) || result.info.lifetime < 1 || result.info.lifetime > MAX_RESIDENCE_MS ||
        !r.bytes(2, crcField) || !r.end(primaryCount)) { error = "invalid primary/destination/lifetime"; return false; }
    if (anonymous && (!(flags & 4) || (flags & 0x74000))) { error = "invalid anonymous source flags"; return false; }
    result.primary = {r.p + start, r.at - start};
    if (!checkCrc(result.primary, crcField, crcType)) { error = "primary CRC mismatch"; return false; }
    bool age = false, hop = false, previous = false, payload = false;
    while (r.at < r.n && (outer == UINT64_MAX ? !r.atBreak() : result.blocks.size() < outer - 1)) {
        if (result.blocks.size() == MAX_BLOCKS || payload) { error = "block limit/payload must be last"; return false; }
        Block b;
        uint64_t count;
        start = r.at;
        if (!r.array(count) || (count != 5 && count != 6 && count != UINT64_MAX) ||
            !r.uint(b.type) || !b.type || !r.uint(b.number) || !b.number || !r.uint(b.flags) ||
            !r.uint(b.crcType) || b.crcType > 2 || (count != UINT64_MAX && count != (b.crcType ? 6u : 5u)) ||
            !r.bytes(2, b.data)) { error = "invalid canonical block"; return false; }
        crcField = {};
        if ((b.crcType && !r.bytes(2, crcField)) || !r.end(count)) { error = "invalid block CRC field"; return false; }
        b.raw = {r.p + start, r.at - start};
        if (!checkCrc(b.raw, crcField, b.crcType)) { error = "canonical CRC mismatch"; return false; }
        for (const Block& old : result.blocks) if (old.number == b.number) { error = "duplicate block number"; return false; }
        if (anonymous && (b.flags & 2)) { error = "anonymous block status report"; return false; }
        Reader data(b.data);
        uint64_t value, limit, pair;
        switch (b.type) {
            case 1:
                if (b.number != 1) { error = "payload number must be one"; return false; }
                payload = true;
                break;
            case 6:
                if (previous || !eid(data) || !data.done()) { error = "invalid/duplicate previous node"; return false; }
                previous = true; b.drop = true;
                break;
            case 7:
                if (age || !data.uint(value) || !data.done()) { error = "invalid/duplicate age"; return false; }
                age = true; result.info.age = value;
                break;
            case 10:
                if (hop || !data.array(2, pair) || !data.uint(limit) || !data.uint(value) ||
                    !data.end(pair) || !data.done() || limit < 1 || limit > 255 || value > std::min<uint64_t>(limit, MAX_HOPS)) {
                    error = "invalid/duplicate hop count"; return false;
                }
                hop = true; result.info.hop = static_cast<uint32_t>(value); result.info.hopLimit = static_cast<uint32_t>(limit);
                break;
            case 11: case 12: error = "BPSec unsupported"; return false;
            default:
                if (b.flags & 4) { error = "unknown block requires deletion"; return false; }
                b.drop = (b.flags & 16) != 0;
        }
        result.blocks.push_back(b);
    }
    if (!r.end(outer) || !r.done() || !age || !hop || !payload || result.info.age >= result.info.lifetime) {
        error = "missing blocks/trailing bytes/expired age"; return false;
    }
    return event(result.blocks.back().data, result.info, error, json);
}

void rewrite(std::vector<uint8_t>& out, const Block& b, uint64_t value, uint32_t hopLimit) {
    std::vector<uint8_t> data;
    data.reserve(12);
    if (b.type == 10) { head(data, 4, 2); head(data, 0, hopLimit); }
    head(data, 0, value);
    size_t start = out.size();
    head(out, 4, b.crcType ? 6 : 5);
    head(out, 0, b.type); head(out, 0, b.number); head(out, 0, b.flags); head(out, 0, b.crcType);
    head(out, 2, data.size()); append(out, {data.data(), data.size()});
    if (b.crcType) {
        size_t width = b.crcType == 1 ? 2 : 4;
        head(out, 2, width);
        size_t offset = out.size();
        out.insert(out.end(), width, 0);
        uint32_t sum = crc({out.data() + start, out.size() - start}, {out.data() + offset, width}, static_cast<unsigned>(b.crcType));
        for (size_t i = 0; i < width; ++i) out[offset + i] = static_cast<uint8_t>(sum >> ((width - i - 1) * 8));
    }
}
} // namespace

bool validateBundle(const uint8_t* data, size_t size, BundleInfo& info, std::string& error) {
    info = {}; error.clear();
    Parsed parsed;
    if (!parse({data, size}, parsed, error)) return false;
    info = std::move(parsed.info);
    return true;
}

bool exportEvent(const uint8_t* data, size_t size, BundleInfo& info,
                 std::string& json, std::string& error) {
    info = {}; json.clear(); error.clear();
    Parsed parsed;
    if (!parse({data, size}, parsed, error, &json)) { json.clear(); return false; }
    info = std::move(parsed.info);
    return true;
}

bool forwardBundle(const uint8_t* data, size_t size, uint64_t residenceMs,
                   std::vector<uint8_t>& out, std::string& error) {
    out.clear(); error.clear();
    Parsed parsed;
    if (!parse({data, size}, parsed, error)) return false;
    const BundleInfo& info = parsed.info;
    if (residenceMs >= MAX_RESIDENCE_MS || residenceMs >= info.lifetime - info.age) {
        error = "residence/lifetime expired"; return false;
    }
    if (info.hop >= std::min(info.hopLimit, MAX_HOPS)) { error = "hop limit exhausted"; return false; }
    // Rewrites can grow by at most 32 bytes (including normalization of incoming
    // indefinite arrays). Do not reserve from any claimed wire length.
    out.reserve(size + 32);
    out.push_back(0x9f);
    append(out, parsed.primary);
    for (const Block& b : parsed.blocks) {
        if (b.drop) continue;
        if (b.type == 7) rewrite(out, b, info.age + residenceMs, info.hopLimit);
        else if (b.type == 10) rewrite(out, b, info.hop + 1, info.hopLimit);
        else append(out, b.raw);
    }
    out.push_back(0xff);
    if (out.size() > MAX_BUNDLE_BYTES) { out.clear(); error = "forwarded bundle size limit"; return false; }
    return true;
}
} // namespace mesh
