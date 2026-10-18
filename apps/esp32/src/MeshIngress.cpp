#include "MeshIngress.h"
#include "MeshBundle.h"
#include <mbedtls/sha256.h>
#include <mbedtls/base64.h>
#include <set>

namespace mesh { namespace ingress {
namespace {
bool is(const Document& d, size_t i, Value::Type t) { return i < d.values.size() && d.at(i).type == t; }
uint64_t integer(const Document& d, size_t i, const char* key) {
    size_t j = d.get(i, key); return is(d, j, Value::Uint) ? d.at(j).n : SAFE + 1;
}
bool flag(const Document& d, size_t i, const char* key, bool expected) {
    size_t j = d.get(i, key); return is(d, j, Value::Boolean) && bool(d.at(j).n) == expected;
}
bool hexSize(const std::string& s, size_t bytes) {
    return s.size() == bytes * 2 && s.find_first_not_of("0123456789abcdef") == std::string::npos;
}
std::string hash(const std::vector<uint8_t>& b) {
    uint8_t h[32]; if (mbedtls_sha256_ret(b.data(), b.size(), h, 0)) return {};
    return hex(h, 32);
}
std::string bodyHash(const Document& d, size_t i, const char* omit = nullptr) {
    std::vector<uint8_t> b; return encode(d, i, b, omit) ? hash(b) : "";
}
bool ecdsa(const Document& d, size_t body, const std::string& key, const std::string& sig, const char* omit = nullptr) {
    std::string h = bodyHash(d, body, omit);
    return hexSize(key, 33) && hexSize(sig, 64) && !h.empty() && verifyCompactEcdsa(unhex(key), unhex(sig), unhex(h).data());
}
bool did(const std::string& s) {
    return s.size() == 32 && s.compare(0, 8, "did:plc:") == 0 && s.find_first_not_of("abcdefghijklmnopqrstuvwxyz234567", 8) == std::string::npos;
}
bool label(const std::string& s, size_t max) {
    if (s.empty() || s.size() > max || !utf8(s)) return false;
    for (unsigned char c : s) if (c < 32 || c == 127) return false;
    return true;
}
// Strict real UTC calendar dates. Fractional timestamp support is deliberately
// limited to 1..9 digits for existing record/deadline/source fields.
bool timestamp(const std::string& s, uint64_t& out, bool secondsOnly = true) {
    if (s.size() < 20 || s.size() > 30 || (secondsOnly && s.size() != 20) ||
        s[4] != '-' || s[7] != '-' || s[10] != 'T' || s[13] != ':' || s[16] != ':' || s.back() != 'Z') return false;
    for (size_t i = 0; i < 19; ++i) if (i != 4 && i != 7 && i != 10 && i != 13 && i != 16 && (s[i] < '0' || s[i] > '9')) return false;
    if (s.size() != 20) {
        if (s.size() < 22 || s[19] != '.') return false;
        for (size_t i = 20; i + 1 < s.size(); ++i) if (s[i] < '0' || s[i] > '9') return false;
    }
    auto two = [&](size_t i) { return (s[i] - '0') * 10 + s[i + 1] - '0'; };
    int y = two(0) * 100 + two(2), m = two(5), day = two(8), h = two(11), min = two(14), sec = two(17);
    const int days[] = {31,28,31,30,31,30,31,31,30,31,30,31};
    if (y < 1970 || m < 1 || m > 12 || day < 1 || day > days[m - 1] + (m == 2 && y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) || h > 23 || min > 59 || sec > 59) return false;
    int year = y - (m <= 2), era = year / 400, yoe = year - era * 400;
    int doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + day - 1;
    int64_t civilDays = int64_t(era) * 146097 + yoe * 365 + yoe / 4 - yoe / 100 + doy - 719468;
    out = uint64_t(civilDays) * 86400 + h * 3600 + min * 60 + sec; return true;
}
bool clock(const Policy& p, uint64_t now) { return p.enabled && p.trustedClock && now >= 1792281600 && now <= 253402300799ULL; }
bool strings(const Document& d, size_t i, std::vector<std::string>& out, size_t count, size_t max, unsigned hexBytes = 0) {
    out.clear(); if (!is(d, i, Value::Array) || d.at(i).children.size() > count) return false;
    for (auto j : d.at(i).children) {
        if (!is(d, j, Value::Text)) return false;
        const auto& s = d.at(j).s;
        if (hexBytes ? !hexSize(s, hexBytes) : !label(s, max)) return false;
        if (!out.empty() && out.back() >= s) return false;
        out.push_back(s);
    }
    return true;
}
bool pairs(const Document& d, size_t array, std::vector<std::pair<std::string,std::string>>& out) {
    out.clear(); if (!is(d, array, Value::Array) || d.at(array).children.empty() || d.at(array).children.size() > 8) return false;
    std::string previous;
    for (auto entry : d.at(array).children) {
        if (!d.fields(entry, {"botDid", "types"})) return false;
        std::string bot = d.str(entry, "botDid"); std::vector<std::string> types;
        if (!did(bot) || (!previous.empty() && previous >= bot) || !strings(d, d.get(entry, "types"), types, 32, 128) || types.empty()) return false;
        previous = bot;
        for (const auto& t : types) out.emplace_back(bot, t);
    }
    return out.size() <= 64;
}
bool scope(const Document& d, size_t i, Scope& s) {
    return d.fields(i, {"orgIds", "bsaf"}) && strings(d, d.get(i, "orgIds"), s.orgs, 8, 128) && !s.orgs.empty() && pairs(d, d.get(i, "bsaf"), s.pairs);
}
template<class T> bool subset(const std::vector<T>& a, const std::vector<T>& b) {
    return std::includes(b.begin(), b.end(), a.begin(), a.end());
}
bool snapshot(const std::vector<uint8_t>& bytes, const Policy& p, Snapshot& s) {
    s = {}; Document d;
    if (bytes.size() > SNAPSHOT_LIMIT || !decode(bytes, d) || !d.fields(d.root, {"body", "sig"})) return false;
    size_t b = d.get(d.root, "body");
    if (!d.fields(b, {"type","version","rootId","sequence","issuedAt","validUntil","revokedCertIds","revokedPubkeys"}) ||
        d.str(b, "type") != "mosimo.oriom.dev/trust-snapshot" || integer(d,b,"version") != 1 || d.str(b,"rootId") != p.rootId ||
        !ecdsa(d,b,p.rootPubkey,d.str(d.root,"sig")) || !timestamp(d.str(b,"issuedAt"),s.issuedAt) ||
        !timestamp(d.str(b,"validUntil"),s.validUntil) || s.validUntil <= s.issuedAt || s.validUntil - s.issuedAt > 3600) return false;
    s.sequence = integer(d,b,"sequence"); s.hash = bodyHash(d,b);
    if (s.sequence < 1 || s.sequence > SAFE || !strings(d,d.get(b,"revokedCertIds"),s.certs,32,64,32) ||
        !strings(d,d.get(b,"revokedPubkeys"),s.keys,32,66,33) || s.certs.size() + s.keys.size() > 32) return false;
    for (const auto& k : s.keys) if (!validSecpKey(unhex(k))) return false;
    return true;
}
bool b64(const std::string& s, std::vector<uint8_t>& bytes, bool padded = false) {
    bytes.clear(); if (s.empty() || s.size() > JSON_LIMIT || (padded && (s.size() != 88 || s.substr(86) != "=="))) return false;
    const size_t end = padded ? s.size() - 2 : s.size();
    for (size_t i = 0; i < end; ++i) if (!std::strchr("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/", s[i]) || !s[i]) return false;
    std::string input = s; if (!padded) input.append((4 - s.size() % 4) % 4, '=');
    bytes.resize(input.size() / 4 * 3); size_t n = 0;
    if (mbedtls_base64_decode(bytes.data(), bytes.size(), &n, reinterpret_cast<const uint8_t*>(input.data()), input.size())) { bytes.clear(); return false; }
    bytes.resize(n); std::vector<uint8_t> encoded((n + 2) / 3 * 4 + 1); size_t length = 0;
    if (mbedtls_base64_encode(encoded.data(),encoded.size(),&length,bytes.data(),bytes.size())) return false;
    std::string canonical(reinterpret_cast<char*>(encoded.data()), length);
    if (!padded) while (!canonical.empty() && canonical.back() == '=') canonical.pop_back();
    return canonical == s;
}
bool extensionType(const std::string& type) {
    auto alpha = [](char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'); };
    auto digit = [](char c) { return c >= '0' && c <= '9'; };
    size_t sharp = type.find('#'); const std::string nsid = type.substr(0,sharp);
    if (nsid.size() > 317 || nsid.find_last_of('.') == std::string::npos || nsid.find_last_of('.') > 253) return false;
    if (sharp != std::string::npos) {
        const std::string fragment = type.substr(sharp + 1);
        if (fragment.empty() || fragment == "main" || !alpha(fragment[0])) return false;
        for (char c : fragment) if (!alpha(c) && !digit(c) && c != '_') return false;
    }
    unsigned labels = 0;
    for (size_t at = 0; at < nsid.size();) {
        size_t end = nsid.find('.',at); if (end == std::string::npos) end = nsid.size();
        const std::string label = nsid.substr(at,end - at); const bool last = end == nsid.size();
        if (label.empty() || label.size() > 63 || ((!labels || last) && !alpha(label[0])) || label.front() == '-' || label.back() == '-') return false;
        for (char c : label) if (!alpha(c) && !digit(c) && (last || c != '-')) return false;
        ++labels; if (last) return labels >= 3;
        at = end + 1;
    }
    return false;
}
bool language(const std::string& s) {
    if (s.empty() || s.size() > 64) return false;
    std::vector<std::string> parts;
    for (size_t at = 0; at < s.size();) {
        size_t end = s.find('-',at); if (end == std::string::npos) end = s.size();
        parts.push_back(s.substr(at,end - at)); if (parts.size() > 3 || end + 1 == s.size()) return false;
        at = end + 1;
    }
    auto alpha = [](const std::string& v) { return !v.empty() && v.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ") == std::string::npos; };
    if (parts.empty() || parts[0].size() < 2 || parts[0].size() > 8 || !alpha(parts[0])) return false;
    size_t next = 1;
    if (next < parts.size() && parts[next].size() == 4 && alpha(parts[next])) ++next;
    if (next < parts.size()) {
        const auto& region = parts[next++];
        if (!((region.size() == 2 && alpha(region)) || (region.size() == 3 && region.find_first_not_of("0123456789") == std::string::npos))) return false;
    }
    return next == parts.size(); // Conservative BCP-47 subset: language[-script][-region].
}
bool tid(const std::string& s) {
    return s.size() == 13 && std::strchr("234567abcdefghij",s[0]) && s.find_first_not_of("234567abcdefghijklmnopqrstuvwxyz") == std::string::npos;
}
bool cid(const std::string& s) {
    if (s.size() != 59 || s[0] != 'b') return false;
    const std::string alphabet = "abcdefghijklmnopqrstuvwxyz234567"; std::vector<uint8_t> b;
    unsigned acc = 0, bits = 0;
    for (size_t i = 1; i < s.size(); ++i) {
        size_t n = alphabet.find(s[i]); if (n == std::string::npos) return false;
        acc = (acc << 5) | unsigned(n); bits += 5;
        if (bits >= 8) { bits -= 8; b.push_back(acc >> bits); acc &= (1u << bits) - 1; }
    }
    return acc == 0 && b.size() == 36 && b[0] == 1 && b[1] == 0x71 && b[2] == 0x12 && b[3] == 0x20;
}
void pair(Document& d, size_t map, const std::string& key, size_t val) {
    size_t k = d.text(key); d.values[map].children.push_back(k); d.values[map].children.push_back(val);
}
}
std::vector<uint8_t> unhex(const std::string& s) {
    std::vector<uint8_t> b;
    if (s.size() % 2 || s.find_first_not_of("0123456789abcdef") != std::string::npos) return b;
    for (size_t i = 0; i < s.size(); i += 2) {
        auto digit = [](char c) { return c <= '9' ? c - '0' : c - 'a' + 10; };
        b.push_back((digit(s[i]) << 4) | digit(s[i + 1]));
    } return b;
}
std::string hex(const uint8_t* p, size_t n) {
    const char* h = "0123456789abcdef"; std::string s;
    for (size_t i = 0; i < n; ++i) { s += h[p[i] >> 4]; s += h[p[i] & 15]; } return s;
}
bool configure(Policy& p, const std::string& root, const std::string& scopeHex, const std::string& selectedHex, uint64_t min) {
    Document scopeDoc, selected;
    if (!hexSize(root,33) || !validSecpKey(unhex(root)) || min < 1 || min > SAFE || scopeHex.size() > CBOR_LIMIT * 2 || selectedHex.size() > CBOR_LIMIT * 2 ||
        !decode(unhex(scopeHex),scopeDoc) || !scope(scopeDoc,scopeDoc.root,p.scope) ||
        !decode(unhex(selectedHex),selected) || !pairs(selected,selected.root,p.selected) || !subset(p.selected,p.scope.pairs)) return false;
    p.rootPubkey = root; p.rootId = hash(unhex(root)); p.minSequence = min; return !p.rootId.empty();
}
bool currentSnapshot(const Policy& p, uint64_t now, const State& state, Snapshot& s, bool fresh) {
    if (!snapshot(state.snapshot,p,s) || s.sequence < p.minSequence) return false;
    return !fresh || (clock(p,now) && s.issuedAt <= now && now < s.validUntil && !std::binary_search(s.keys.begin(),s.keys.end(),p.rootPubkey));
}
bool observeSnapshot(const std::vector<uint8_t>& bytes, const Policy& p, uint64_t now, State& state, Snapshot& next) {
    if (!clock(p,now) || !snapshot(bytes,p,next) || next.sequence < p.minSequence || next.issuedAt > now || now >= next.validUntil) return false;
    if (!state.snapshot.empty()) {
        Snapshot old;
        if (!snapshot(state.snapshot,p,old) || next.sequence < old.sequence ||
            (next.sequence == old.sequence && next.hash != old.hash) || next.issuedAt < old.issuedAt ||
            !subset(old.certs,next.certs) || !subset(old.keys,next.keys)) return false;
    }
    state.snapshot = bytes; return true; // Root revocation is persisted, never auto-cleared.
}
bool encodeState(const State& state, const Policy& p, std::vector<uint8_t>& out) {
    out.clear(); Snapshot s;
    if (!snapshot(state.snapshot,p,s) || state.serials.size() > 16) return false;
    Document d; size_t body = d.add(Value::Map);
    pair(d,body,"version",d.number(1)); pair(d,body,"rootId",d.text(p.rootId));
    pair(d,body,"snapshot",d.text(std::string(state.snapshot.begin(),state.snapshot.end()),Value::Bytes));
    size_t serials = d.add(Value::Array);
    for (const auto& serial : state.serials) {
        size_t a = d.add(Value::Array);
        size_t name = d.text(serial.serial), id = d.text(serial.certId);
        d.values[a].children.push_back(name); d.values[a].children.push_back(id);
        size_t b = d.add(Value::Boolean); d.values[b].n = serial.conflict; d.values[a].children.push_back(b); d.values[serials].children.push_back(a);
    }
    pair(d,body,"serials",serials);
    std::string digest = bodyHash(d,body); if (digest.empty()) return false;
    size_t wrapper = d.add(Value::Array); d.values[wrapper].children.push_back(body);
    auto rawHash = unhex(digest);
    size_t checksum = d.text(std::string(rawHash.begin(),rawHash.end()),Value::Bytes);
    d.values[wrapper].children.push_back(checksum);
    return encode(d,wrapper,out);
}
bool decodeState(const std::vector<uint8_t>& bytes, const Policy& p, State& state) {
    state = {}; Document d;
    if (!decode(bytes,d) || !is(d,d.root,Value::Array) || d.at(d.root).children.size() != 2) return false;
    size_t body = d.at(d.root).children[0], digest = d.at(d.root).children[1];
    if (!d.fields(body,{"version","rootId","snapshot","serials"}) || integer(d,body,"version") != 1 || d.str(body,"rootId") != p.rootId ||
        !is(d,digest,Value::Bytes) || hex(reinterpret_cast<const uint8_t*>(d.at(digest).s.data()),d.at(digest).s.size()) != bodyHash(d,body)) return false;
    size_t snap = d.get(body,"snapshot"), serials = d.get(body,"serials");
    if (!is(d,snap,Value::Bytes) || !is(d,serials,Value::Array) || d.at(serials).children.size() > 16) return false;
    state.snapshot.assign(d.at(snap).s.begin(),d.at(snap).s.end()); Snapshot observed;
    if (!snapshot(state.snapshot,p,observed)) return false;
    std::set<std::string> seen;
    for (auto entry : d.at(serials).children) {
        if (!is(d,entry,Value::Array) || d.at(entry).children.size() != 3) return false;
        const auto& a = d.at(entry).children;
        if (!is(d,a[0],Value::Text) || !is(d,a[1],Value::Text) || !is(d,a[2],Value::Boolean) ||
            !hexSize(d.at(a[0]).s,16) || !hexSize(d.at(a[1]).s,32) || !seen.insert(d.at(a[0]).s).second) return false;
        Serial serial; serial.serial = d.at(a[0]).s; serial.certId = d.at(a[1]).s; serial.conflict = d.at(a[2]).n; state.serials.push_back(serial);
    }
    return true;
}
bool eligibleNow(const Policy& p, uint64_t now, const Authorization& a) {
    return clock(p,now) && a.authorSignature && a.binding && a.chainScope && a.certificateTime && a.revocation == Authorization::Fresh &&
        a.rootId == p.rootId && a.sequence >= p.minSequence && a.notBefore <= now && now < a.notAfter &&
        a.created <= now && a.issuedAt <= now && now < a.validUntil && now < a.deadline;
}
bool higher(const Authorization& a, const Authorization& b) {
    return b.id.empty() || a.priority > b.priority || (a.priority == b.priority && (a.created > b.created || (a.created == b.created && a.id < b.id)));
}

std::string fetchSubscription(bool priority) { return priority ? "esp-priority" : "esp-fallback"; }
std::string fetchRequest(bool priority) {
    const std::string sub = fetchSubscription(priority);
    const std::string filter = "{\"kinds\":[1],\"#t\":[\"mosimo.oriom.dev\"],\"limit\":8}";
    return priority ? "[\"MOSIMO-REQ\",\"" + sub + "\",{\"order\":\"priority-v1\",\"filter\":" + filter + "}]" :
        "[\"REQ\",\"" + sub + "\"," + filter + "]";
}

bool authorize(const Document& event, size_t e, const Policy& p, uint64_t now, State& state,
               Authorization& a, std::vector<uint8_t>& payload, std::string& reason) {
    a = {}; payload.clear(); reason = "event/size/clock";
    auto reject = [&](const char* why) { reason = why; payload.clear(); a.priority = std::min(a.priority,1u); return false; };
    if (!clock(p,now) || !event.fields(e,{"id","pubkey","created_at","kind","tags","content","sig"}) || integer(event,e,"kind") != 1) return false;
    a.id = event.str(e,"id"); a.created = integer(event,e,"created_at");
    const std::string pubkey = event.str(e,"pubkey"), signature = event.str(e,"sig");
    size_t tags = event.get(e,"tags"), content = event.get(e,"content");
    if (!hexSize(a.id,32) || !hexSize(pubkey,32) || !hexSize(signature,64) || a.created > now ||
        !is(event,tags,Value::Array) || event.at(tags).children.size() > 64 || !is(event,content,Value::Text) || event.at(content).s.size() > 2048) return false;
    Document wire; wire.root = wire.add(Value::Array);
    auto push = [&](size_t v) { wire.values[wire.root].children.push_back(v); };
    push(wire.number(1)); auto keyBytes = unhex(pubkey); push(wire.text(std::string(keyBytes.begin(),keyBytes.end()),Value::Bytes));
    push(wire.number(a.created)); push(wire.number(1)); size_t typedTags = wire.add(Value::Array); push(typedTags);
    std::vector<std::pair<std::string,std::string>> allTags;
    std::vector<std::pair<std::string,std::vector<uint8_t>>> extensions;
    unsigned protocolTags = 0, identityCount = 0, bsafCount = 0, priorityCount = 0; bool priorityValid = true;
    std::vector<uint8_t> identityBytes, bsafBytes, commitBytes;
    std::set<std::string> baseNames;
    for (auto tag : event.at(tags).children) {
        if (!is(event,tag,Value::Array) || event.at(tag).children.empty() || event.at(tag).children.size() > 8) return reject("tag shape/resource");
        const auto& cells = event.at(tag).children;
        for (auto cell : cells) if (!is(event,cell,Value::Text)) return reject("non-string Nostr tag");
        const std::string name = event.at(cells[0]).s;
        size_t typed = wire.add(Value::Array); wire.values[typedTags].children.push_back(typed);
        for (size_t j = 0; j < cells.size(); ++j) {
            const auto& text = event.at(cells[j]).s; size_t v;
            bool special = j == 1 && cells.size() == 2 && (name == "mosimo.oriom.dev:commit" || name.compare(0,21,"mosimo.oriom.dev:ext:") == 0);
            std::vector<uint8_t> bytes;
            if (special && b64(text,bytes)) v = wire.text(std::string(bytes.begin(),bytes.end()),Value::Bytes);
            else if (j == 1 && cells.size() == 2 && name == "mosimo.oriom.dev:version" && text == "1") v = wire.number(1);
            else v = wire.text(text);
            wire.values[typed].children.push_back(v);
        }
        if (name == "t" && cells.size() == 2 && event.at(cells[1]).s == "mosimo.oriom.dev") ++protocolTags;
        if (name.compare(0,21,"mosimo.oriom.dev:ext:") == 0) {
            if (cells.size() != 2 || extensions.size() == 16) return reject("extension count/shape");
            std::vector<uint8_t> bytes;
            if (!b64(event.at(cells[1]).s,bytes)) return reject("noncanonical extension Base64");
            const std::string type = name.substr(21);
            extensions.emplace_back(type,bytes);
            if (type == "dev.oriom.mosimo.defs#identity") { ++identityCount; identityBytes = bytes; }
            if (type == "dev.oriom.mosimo.defs#bsafV1") { ++bsafCount; bsafBytes = bytes; }
            if (type == "dev.oriom.mosimo.defs#priority") {
                ++priorityCount; Document priority;
                if (!decode(bytes,priority) || !priority.fields(priority.root,{"version","level"}) || integer(priority,priority.root,"version") != 1 || integer(priority,priority.root,"level") > 3) priorityValid = false;
                else a.priority = unsigned(integer(priority,priority.root,"level"));
            }
        } else if (name.compare(0,17,"mosimo.oriom.dev:") == 0) {
            // All base fields are unique, two-element strings; unknown base tags
            // remain signed but are not used to invent record fields.
            if (cells.size() != 2 || !baseNames.insert(name).second) return reject("duplicate/base tag shape");
        }
        if (cells.size() == 2) allTags.emplace_back(name,event.at(cells[1]).s);
    }
    if (priorityCount != 1 || !priorityValid) a.priority = 1;
    auto field = [&](const std::string& name) {
        for (const auto& tag : allTags) if (tag.first == "mosimo.oriom.dev:" + name) return tag.second;
        return std::string();
    };
    if (protocolTags != 1 || field("version") != "1" || identityCount != 1 || bsafCount != 1 || identityBytes.size() > 4096 ||
        !baseNames.count("mosimo.oriom.dev:base") || !b64(field("commit"),commitBytes)) return reject("required base/identity/BSAF");
    push(wire.text(event.at(content).s)); auto sigBytes = unhex(signature); push(wire.text(std::string(sigBytes.begin(),sigBytes.end()),Value::Bytes));
    if (!encode(wire,wire.root,payload)) return reject("payload resource");
    BundleInfo info; std::string error;
    if (!validateEventPayload(payload,info,error) || info.id != a.id) return reject("Nostr ID/signature");
    a.authorSignature = true;
    // Phase boundaries release duplicated JSON/payload trees before allocating
    // certificate/commit trees; keep only the immutable canonical payload.
    wire = {};
    uint64_t recordTime;
    if (!timestamp(field("created-at"),recordTime,false) || recordTime != a.created ||
        !timestamp(field("delivery-expires-at"),a.deadline,false) || a.deadline <= now) return reject("record time/deadline");
    const std::string path = field("path"), base = field("base"), collection = "dev.oriom.mosimo.post/";
    if (path.compare(0,collection.size(),collection) || !tid(path.substr(collection.size())) || (!base.empty() && !cid(base))) return reject("path/base CID");
    // Decode every extension canonically, including unknown opaque extensions.
    for (const auto& extension : extensions) {
        Document decoded;
        if (!extensionType(extension.first) || !decode(extension.second,decoded) || !is(decoded,decoded.root,Value::Map) || decoded.has(decoded.root,"$type")) return reject("extension canonical map");
    }
    std::vector<std::pair<std::string,std::vector<uint8_t>>>().swap(extensions);
    std::vector<std::pair<std::string,std::string>>().swap(allTags);
    Document identity;
    if (!decode(identityBytes,identity) || !identity.fields(identity.root,{"version","chain","binding"}) || integer(identity,identity.root,"version") != 1) return reject("identity schema");
    size_t chain = identity.get(identity.root,"chain");
    if (!is(identity,chain,Value::Array) || identity.at(chain).children.size() != 1) return reject("unsupported certificate path (root->leaf only)");
    size_t cert = identity.at(chain).children[0], leaf = identity.get(cert,"body");
    std::vector<uint8_t> certEncoded;
    if (!identity.fields(cert,{"body","sig"}) || !encode(identity,cert,certEncoded) || certEncoded.size() > 4096 ||
        !identity.fields(leaf,{"type","version","serial","rootId","issuerPubkey","subjectPubkey","subjectDid","orgId","orgName","role","permissions","canDelegate","scope","notBefore","notAfter"}) ||
        identity.str(leaf,"type") != "mosimo.oriom.dev/identity-certificate" || integer(identity,leaf,"version") != 1 || identity.str(leaf,"rootId") != p.rootId ||
        identity.str(leaf,"issuerPubkey") != p.rootPubkey || identity.str(leaf,"role") != "importer" || !flag(identity,leaf,"canDelegate",false)) return reject("certificate/root/schema");
    std::string leafKey = identity.str(leaf,"subjectPubkey"), leafDid = identity.str(leaf,"subjectDid"), serial = identity.str(leaf,"serial");
    a.rootId = p.rootId; a.leafId = bodyHash(identity,leaf); a.orgId = identity.str(leaf,"orgId"); a.orgName = identity.str(leaf,"orgName");
    size_t permissions = identity.get(leaf,"permissions"); Scope leafScope;
    if (!hexSize(serial,16) || !did(leafDid) || leafKey == p.rootPubkey || !hexSize(leafKey,33) || !validSecpKey(unhex(leafKey)) ||
        !label(a.orgId,128) || !label(a.orgName,256) || !scope(identity,identity.get(leaf,"scope"),leafScope) ||
        leafScope.orgs != std::vector<std::string>{a.orgId} || !subset(leafScope.orgs,p.scope.orgs) || !subset(leafScope.pairs,p.scope.pairs) ||
        !is(identity,permissions,Value::Array) || identity.at(permissions).children.size() != 1 ||
        !is(identity,identity.at(permissions).children[0],Value::Text) || identity.at(identity.at(permissions).children[0]).s != "bsaf-import" ||
        !ecdsa(identity,leaf,p.rootPubkey,identity.str(cert,"sig"))) return reject("certificate signature/scope/key");
    auto observed = std::find_if(state.serials.begin(),state.serials.end(),[&](const Serial& old) { return old.serial == serial; });
    if (observed != state.serials.end()) {
        if (observed->certId != a.leafId) observed->conflict = true;
        if (observed->conflict) return reject("issuer serial equivocation");
    } else {
        if (state.serials.size() == 16) return reject("issuer serial history full");
        Serial record; record.serial = serial; record.certId = a.leafId; state.serials.push_back(record);
    }
    if (!timestamp(identity.str(leaf,"notBefore"),a.notBefore) || !timestamp(identity.str(leaf,"notAfter"),a.notAfter) ||
        a.notAfter <= a.notBefore || a.notAfter - a.notBefore > 31622400 || a.notBefore > now || now >= a.notAfter || a.created < a.notBefore || a.created >= a.notAfter) return reject("certificate time");
    a.certificateTime = true;
    size_t binding = identity.get(identity.root,"binding"), declaration = identity.get(binding,"declaration");
    if (!identity.fields(binding,{"declaration","atprotoSig","nostrSig"}) ||
        !identity.fields(declaration,{"type","version","did","atprotoPubkey","nostrPubkey","consent"}) ||
        identity.str(declaration,"type") != "mndt.keyBinding" || integer(identity,declaration,"version") != 1 ||
        identity.str(declaration,"did") != leafDid || identity.str(declaration,"atprotoPubkey") != leafKey || identity.str(declaration,"nostrPubkey") != pubkey || leafKey.substr(2) == pubkey ||
        identity.str(declaration,"consent") != "Use this key pair for this DID's MNDT publications") return reject("binding declaration");
    std::vector<uint8_t> atSig; std::string bindingHash = bodyHash(identity,declaration), nostrSig = identity.str(binding,"nostrSig");
    if (bindingHash.empty() || !b64(identity.str(binding,"atprotoSig"),atSig,true) || !hexSize(nostrSig,64) ||
        !verifyCompactEcdsa(unhex(leafKey),atSig,unhex(bindingHash).data()) ||
        !verifyCompactSchnorr(unhex(pubkey),unhex(nostrSig),unhex(bindingHash).data())) return reject("binding signatures");
    a.binding = true;
    Document commit;
    if (!decode(commitBytes,commit,true) || !commit.fields(commit.root,{"did","version","data","rev","prev","sig"}) ||
        commit.str(commit.root,"did") != leafDid || integer(commit,commit.root,"version") != 3 ||
        !tid(commit.str(commit.root,"rev")) || !is(commit,commit.get(commit.root,"prev"),Value::Null) ||
        !is(commit,commit.get(commit.root,"data"),Value::Link) || !is(commit,commit.get(commit.root,"sig"),Value::Bytes)) return reject("commit schema/DID");
    const auto& link = commit.at(commit.get(commit.root,"data")).s;
    if (link.size() != 37 || static_cast<uint8_t>(link[0]) != 0 || static_cast<uint8_t>(link[1]) != 1 || static_cast<uint8_t>(link[2]) != 0x71 ||
        static_cast<uint8_t>(link[3]) != 0x12 || static_cast<uint8_t>(link[4]) != 0x20) return reject("commit CID link");
    const auto& rawSig = commit.at(commit.get(commit.root,"sig")).s;
    if (!ecdsa(commit,commit.root,leafKey,hex(reinterpret_cast<const uint8_t*>(rawSig.data()),rawSig.size()),"sig")) return reject("commit signature");
    Document bsaf;
    if (!decode(bsafBytes,bsaf) || !bsaf.fields(bsaf.root,{"tags","langs","original"})) return reject("BSAF schema (botDefinition unsupported)");
    size_t original = bsaf.get(bsaf.root,"original"), bsafTags = bsaf.get(bsaf.root,"tags"), langs = bsaf.get(bsaf.root,"langs");
    if (!bsaf.fields(original,{"uri","cid","createdAt"}) || !cid(bsaf.str(original,"cid")) ||
        !timestamp(bsaf.str(original,"createdAt"),recordTime,false) || !is(bsaf,bsafTags,Value::Array) || bsaf.at(bsafTags).children.size() > 8 ||
        !is(bsaf,langs,Value::Array) || bsaf.at(langs).children.size() > 3) return reject("BSAF source/schema");
    const std::string uri = bsaf.str(original,"uri");
    if (uri.size() < 58 || uri.compare(0,5,"at://") || !did(uri.substr(5,32)) || uri.compare(37,20,"/app.bsky.feed.post/")) return reject("original URI");
    const std::string rkey = uri.substr(57);
    if (rkey.empty() || rkey.size() > 512 || rkey == "." || rkey == ".." || rkey.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_~.:-") != std::string::npos) return reject("original record key");
    a.botDid = uri.substr(5,32); std::set<std::string> core;
    for (auto j : bsaf.at(bsafTags).children) {
        if (!is(bsaf,j,Value::Text) || bsaf.at(j).s.size() > 640) return reject("BSAF tag limit");
        const auto& s = bsaf.at(j).s; size_t colon = s.find(':');
        std::string name = s.substr(0,colon), value = colon == std::string::npos ? "" : s.substr(colon + 1);
        if (name == "bsaf" || name == "type" || name == "value" || name == "time" || name == "target" || name == "source") {
            if (value.empty() || !core.insert(name).second || (name == "bsaf" && value != "v1")) return reject("BSAF core ambiguity");
            if (name == "type") { if (!label(value,128)) return reject("BSAF type"); a.type = value; }
        }
    }
    if (core.size() != 6) return reject("BSAF core missing");
    for (auto j : bsaf.at(langs).children) {
        if (!is(bsaf,j,Value::Text)) return reject("language type");
        if (!language(bsaf.at(j).s)) return reject("unsupported language tag");
    }
    const auto selection = std::make_pair(a.botDid,a.type);
    if (!std::binary_search(p.selected.begin(),p.selected.end(),selection) || !std::binary_search(leafScope.pairs.begin(),leafScope.pairs.end(),selection)) return reject("Bot/type not selected/scoped");
    a.chainScope = true; Snapshot fresh;
    if (!currentSnapshot(p,now,state,fresh)) { a.revocation = Authorization::Stale; return reject("snapshot stale/unknown/root revoked"); }
    if (std::binary_search(fresh.certs.begin(),fresh.certs.end(),a.leafId) || std::binary_search(fresh.keys.begin(),fresh.keys.end(),leafKey)) {
        a.revocation = Authorization::Revoked; return reject("revoked leaf");
    }
    a.revocation = Authorization::Fresh; a.sequence = fresh.sequence; a.issuedAt = fresh.issuedAt; a.validUntil = fresh.validUntil; a.checkedAt = now;
    if (!eligibleNow(p,now,a)) return reject("ingress no longer eligible");
    reason.clear(); return true;
}
}}
