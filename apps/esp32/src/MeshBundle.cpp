#include "MeshBundle.h"

#include <algorithm>
#include <cstring>

#include <mbedtls/bignum.h>
#include <mbedtls/ecp.h>
#include <mbedtls/sha256.h>

namespace mesh {
namespace {
constexpr uint64_t SIGNED_MAX = 0x7fffffffffffffffULL;
constexpr size_t MAX_BLOCKS = 64;

struct Slice {
    const uint8_t* p;
    size_t n;
    Slice(const uint8_t* data = nullptr, size_t size = 0) : p(data), n(size) {}
};

bool utf8(Slice s) {
    for (size_t i = 0; i < s.n;) {
        uint32_t c = s.p[i++];
        if (c < 128) continue;
        unsigned more;
        uint32_t minimum;
        if (c >= 0xc2 && c <= 0xdf) { more = 1; minimum = 0x80; c &= 31; }
        else if (c >= 0xe0 && c <= 0xef) { more = 2; minimum = 0x800; c &= 15; }
        else if (c >= 0xf0 && c <= 0xf4) { more = 3; minimum = 0x10000; c &= 7; }
        else return false;
        if (more > s.n - i) return false;
        while (more--) {
            uint8_t b = s.p[i++];
            if ((b & 0xc0) != 0x80) return false;
            c = (c << 6) | (b & 63);
        }
        if (c < minimum || c > 0x10ffff || (c >= 0xd800 && c <= 0xdfff)) return false;
    }
    return true;
}

bool equal(Slice s, const char* text) {
    return s.n == std::strlen(text) && std::memcmp(s.p, text, s.n) == 0;
}
std::string string(Slice s) { return std::string(reinterpret_cast<const char*>(s.p), s.n); }

// No recursion or allocation from attacker-supplied lengths. Every container in
// this profile has a fixed shape or an explicit local bound.
struct Reader {
    const uint8_t* p;
    size_t n, at = 0, items = 0;
    bool canonical;
    Reader(Slice s, bool c = false) : p(s.p), n(s.n), canonical(c) {}
    bool head(unsigned major, uint64_t& value, bool allowIndef = false) {
        if (at >= n || ++items > 8192) return false;
        unsigned b = p[at++], ai = b & 31;
        if ((b >> 5) != major) return false;
        if (ai < 24) { value = ai; return true; }
        if (ai == 31 && allowIndef && !canonical) { value = UINT64_MAX; return true; }
        if (ai < 24 || ai > 27) return false;
        unsigned width = 1u << (ai - 24);
        if (width > n - at) return false;
        value = 0;
        for (unsigned i = 0; i < width; ++i) value = (value << 8) | p[at++];
        if (value > SIGNED_MAX) return false;
        if (canonical && (value < 24 || (width > 1 && value <= 0xff) ||
                          (width > 2 && value <= 0xffff) || (width > 4 && value <= 0xffffffffULL))) return false;
        return true;
    }
    bool uint(uint64_t& v) { return head(0, v); }
    bool array(uint64_t& count) { return head(4, count, true); }
    bool array(size_t expected, uint64_t& count) { return array(count) && (count == expected || count == UINT64_MAX); }
    bool end(uint64_t count) {
        if (count != UINT64_MAX) return true;
        return at < n && p[at++] == 0xff;
    }
    bool done() const { return at == n; }
    bool bytes(unsigned major, Slice& s) {
        uint64_t length;
        if (!head(major, length) || length > n - at) return false;
        s = {p + at, static_cast<size_t>(length)};
        at += s.n;
        return major != 3 || utf8(s);
    }
};

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
    size_t bytes = 0;
    Hash() { mbedtls_sha256_init(&ctx); ok = mbedtls_sha256_starts_ret(&ctx, 0) == 0; }
    ~Hash() { mbedtls_sha256_free(&ctx); }
    void add(const void* p, size_t n) {
        bytes += n;
        if (bytes > 262144 || mbedtls_sha256_update_ret(&ctx, static_cast<const uint8_t*>(p), n)) ok = false;
    }
    void add(const std::string& s) { add(s.data(), s.size()); }
    void literal(const char* s) { add(s, std::strlen(s)); }
    bool finish(uint8_t out[32]) { return ok && mbedtls_sha256_finish_ret(&ctx, out) == 0; }
    void quote(Slice s) {
        literal("\"");
        size_t start = 0;
        for (size_t i = 0; i < s.n; ++i) {
            unsigned c = s.p[i];
            if (c >= 32 && c != '"' && c != '\\') continue;
            add(s.p + start, i - start);
            switch (c) {
                case '"': literal("\\\""); break;
                case '\\': literal("\\\\"); break;
                case 8: literal("\\b"); break;
                case 9: literal("\\t"); break;
                case 10: literal("\\n"); break;
                case 12: literal("\\f"); break;
                case 13: literal("\\r"); break;
                default: {
                    const char* hex = "0123456789abcdef";
                    char escape[] = {'\\', 'u', '0', '0', hex[c >> 4], hex[c & 15]};
                    add(escape, sizeof escape);
                }
            }
            start = i + 1;
        }
        add(s.p + start, s.n - start);
        literal("\"");
    }
};
std::string hex(Slice s) {
    static const char* digits = "0123456789abcdef";
    std::string out(s.n * 2, '0');
    for (size_t i = 0; i < s.n; ++i) { out[i * 2] = digits[s.p[i] >> 4]; out[i * 2 + 1] = digits[s.p[i] & 15]; }
    return out;
}
std::string base64(Slice s) {
    static const char* alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string out;
    out.reserve((s.n + 2) / 3 * 4);
    for (size_t i = 0; i < s.n; i += 3) {
        uint32_t x = uint32_t(s.p[i]) << 16;
        if (i + 1 < s.n) x |= uint32_t(s.p[i + 1]) << 8;
        if (i + 2 < s.n) x |= s.p[i + 2];
        out += alphabet[x >> 18]; out += alphabet[(x >> 12) & 63];
        // Android B64.encode uses the standard alphabet WITHOUT padding.
        if (i + 1 < s.n) out += alphabet[(x >> 6) & 63];
        if (i + 2 < s.n) out += alphabet[x & 63];
    }
    return out;
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

// BIP340 verification only: no secrets, signing, RNG, or new crypto dependency.
// Explicit lift_x is needed because mbedTLS 2.x cannot import compressed points.
bool verifySchnorr(Slice pub, Slice sig, const uint8_t message[32]) {
    if (pub.n != 32 || sig.n != 64) return false;
    Curve c;
    Mpi r, s, e, y2, exponent;
    if (mbedtls_ecp_group_load(&c.g, MBEDTLS_ECP_DP_SECP256K1) ||
        mbedtls_mpi_read_binary(&c.p.X, pub.p, 32) ||
        mbedtls_mpi_read_binary(&r.v, sig.p, 32) ||
        mbedtls_mpi_read_binary(&s.v, sig.p + 32, 32) ||
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
    h.add(tag, 32); h.add(tag, 32); h.add(sig.p, 32); h.add(pub.p, 32); h.add(message, 32);
    if (!h.finish(challenge) || mbedtls_mpi_read_binary(&e.v, challenge, 32) ||
        mbedtls_mpi_mod_mpi(&e.v, &e.v, &c.g.N)) return false;
    if (mbedtls_mpi_cmp_int(&e.v, 0) && mbedtls_mpi_sub_mpi(&e.v, &c.g.N, &e.v)) return false;
    if (mbedtls_ecp_muladd(&c.g, &c.r, &s.v, &c.g.G, &e.v, &c.p)) return false;
    return !mbedtls_ecp_is_zero(&c.r) && !mbedtls_mpi_get_bit(&c.r.Y, 0) &&
           mbedtls_mpi_cmp_mpi(&c.r.X, &r.v) == 0;
}

bool deadline(Slice s) {
    // Android requires four-digit UTC ISO-8601 and Instant.parse validity.
    if (s.n < 20 || s.n > 30) return false;
    for (size_t i = 0; i < s.n; ++i) {
        if (i == 4 || i == 7) { if (s.p[i] != '-') return false; }
        else if (i == 10) { if (s.p[i] != 'T') return false; }
        else if (i == 13 || i == 16) { if (s.p[i] != ':') return false; }
        else if (i == s.n - 1) { if (s.p[i] != 'Z') return false; }
        else if (i == 19) { if (s.p[i] != '.' || s.n < 22) return false; }
        else if (s.p[i] < '0' || s.p[i] > '9') return false;
    }
    auto two = [&](size_t i) { return (s.p[i] - '0') * 10 + s.p[i + 1] - '0'; };
    int year = two(0) * 100 + two(2), month = two(5), day = two(8);
    const int days[] = {31,28,31,30,31,30,31,31,30,31,30,31};
    if (month < 1 || month > 12) return false;
    int maximum = days[month - 1] + (month == 2 && year % 4 == 0 && (year % 100 != 0 || year % 400 == 0));
    int hour = two(11), minute = two(14), second = two(17);
    if (day < 1 || day > maximum || minute > 59) return false;
    if (hour == 24) {
        if (minute || second) return false;
        for (size_t i = 20; i + 1 < s.n; ++i) if (s.p[i] != '0') return false;
        return true;
    }
    return hour <= 23 && (second <= 59 || (hour == 23 && minute == 59 && second == 60));
}

bool event(Slice payload, BundleInfo& info, std::string& error) {
    Reader p(payload, true);
    uint64_t count, version, created, kind, tags;
    Slice pub, sig, content;
    if (!p.array(7, count) || !p.uint(version) || version != 1 || !p.bytes(2, pub) || pub.n != 32 ||
        !p.uint(created) || created > 9007199254740991ULL || !p.uint(kind) || kind > 65535 ||
        !p.array(tags) || tags > 256) { error = "invalid event header"; return false; }
    Hash h;
    h.literal("[0,\""); h.add(hex(pub)); h.literal("\","); h.add(std::to_string(created));
    h.literal(","); h.add(std::to_string(kind)); h.literal(",[");
    bool hasDeadline = false;
    for (uint64_t i = 0; i < tags; ++i) {
        uint64_t length;
        Slice name;
        if (!p.array(length) || length < 1 || length > 32 || !p.bytes(3, name)) { error = "invalid event tag"; return false; }
        bool isDeadline = equal(name, "mosimo.oriom.dev:delivery-expires-at");
        if (isDeadline && (hasDeadline || length != 2)) { error = "duplicate/invalid signed deadline"; return false; }
        if (i) h.literal(",");
        h.literal("["); h.quote(name);
        for (uint64_t j = 1; j < length; ++j) {
            h.literal(",");
            Slice value;
            if (p.at < p.n && (p.p[p.at] >> 5) == 3) {
                if (!p.bytes(3, value)) { error = "invalid UTF-8 tag"; return false; }
                h.quote(value);
                if (isDeadline) {
                    if (!deadline(value)) { error = "invalid signed deadline"; return false; }
                    hasDeadline = true;
                }
            } else if (j == 1 && length == 2 && !isDeadline &&
                       (equal(name, "mosimo.oriom.dev:commit") ||
                        (name.n > 21 && std::memcmp(name.p, "mosimo.oriom.dev:ext:", 21) == 0))) {
                if (!p.bytes(2, value)) { error = "invalid typed byte tag"; return false; }
                std::string encoded = base64(value);
                h.quote({reinterpret_cast<const uint8_t*>(encoded.data()), encoded.size()});
            } else if (j == 1 && length == 2 && equal(name, "mosimo.oriom.dev:version")) {
                uint64_t v;
                if (!p.uint(v) || v != 1) { error = "invalid typed version tag"; return false; }
                h.literal("\"1\"");
            } else { error = "unsupported typed tag"; return false; }
        }
        h.literal("]");
    }
    if (!hasDeadline || !p.bytes(3, content) || content.n > 16384 || !p.bytes(2, sig) || sig.n != 64 || !p.done()) {
        error = "invalid event content/signature/deadline"; return false;
    }
    h.literal("],"); h.quote(content); h.literal("]");
    uint8_t digest[32];
    if (!h.finish(digest) || !verifySchnorr(pub, sig, digest)) { error = "invalid event signature"; return false; }
    info.id = hex({digest, 32}); info.text = string(content);
    info.kind = static_cast<uint16_t>(kind);
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
        if (r.at < r.n && (r.p[r.at] >> 5) == 3) {
            Slice s;
            if (!r.bytes(3, s) || s.n < 3 || s.p[0] != '/' || s.p[1] != '/' ||
                (destination && !equal(s, "//mosimo.oriom.dev/~post"))) return false;
            size_t path = 2;
            while (path < s.n && s.p[path] != '/' && s.p[path] != '?' && s.p[path] != '#') ++path;
            if (path == 2 || path == s.n || s.p[path] != '/') return false;
            // RFC 3986 URI syntax: reject spaces/control, non-ASCII and malformed escapes.
            for (size_t i = 0; i < s.n; ++i) {
                unsigned c = s.p[i];
                const char* allowed = "-._~:/?#[]@!$&'()*+,;=";
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || std::strchr(allowed, c)) {
                    if (!c) return false;
                    continue;
                }
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

bool parse(Slice bytes, Parsed& result, std::string& error) {
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
    while (r.at < r.n && (outer == UINT64_MAX ? r.p[r.at] != 0xff : result.blocks.size() < outer - 1)) {
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
    return event(result.blocks.back().data, result.info, error);
}

void head(std::vector<uint8_t>& out, unsigned major, uint64_t value) {
    if (value < 24) { out.push_back(static_cast<uint8_t>((major << 5) | value)); return; }
    unsigned width = value <= 0xff ? 1 : value <= 0xffff ? 2 : value <= 0xffffffffULL ? 4 : 8;
    out.push_back(static_cast<uint8_t>((major << 5) | (width == 1 ? 24 : width == 2 ? 25 : width == 4 ? 26 : 27)));
    for (unsigned i = width; i; --i) out.push_back(static_cast<uint8_t>(value >> ((i - 1) * 8)));
}
void append(std::vector<uint8_t>& out, Slice s) { out.insert(out.end(), s.p, s.p + s.n); }
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
