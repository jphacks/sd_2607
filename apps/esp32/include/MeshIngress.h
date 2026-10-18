#pragma once
#include "IngressCodec.h"

namespace mesh { namespace ingress {
struct Scope {
    std::vector<std::string> orgs;
    std::vector<std::pair<std::string, std::string>> pairs;
};
struct Policy {
    bool enabled = false, trustedClock = false;
    std::string rootPubkey, rootId, snapshotUrl, snapshotCa;
    uint64_t minSequence = 1;
    Scope scope;
    std::vector<std::pair<std::string, std::string>> selected;
};
struct Snapshot {
    uint64_t sequence = 0, issuedAt = 0, validUntil = 0;
    std::string hash;
    std::vector<std::string> certs, keys;
};
struct Serial { std::string serial, certId; bool conflict = false; };
struct State { std::vector<uint8_t> snapshot; std::vector<Serial> serials; };
struct Authorization {
    bool authorSignature = false, binding = false, chainScope = false, certificateTime = false;
    enum Revocation { Unknown, Fresh, Revoked, Stale } revocation = Unknown;
    std::string id, rootId, leafId, botDid, type, orgId, orgName;
    uint64_t created = 0, notBefore = 0, notAfter = 0, deadline = 0;
    uint64_t sequence = 0, issuedAt = 0, validUntil = 0, checkedAt = 0;
    unsigned priority = 1;
};
// One configured root, direct root->importer only. Scope/selection are canonical
// CBOR hex: Scope object / array of {botDid,types}; no implicit JMA/source trust.
bool configure(Policy& policy, const std::string& root, const std::string& scopeHex,
               const std::string& selectionHex, uint64_t minSequence);
bool decodeState(const std::vector<uint8_t>& bytes, const Policy&, State&);
bool encodeState(const State&, const Policy&, std::vector<uint8_t>& bytes);
bool observeSnapshot(const std::vector<uint8_t>& bytes, const Policy&, uint64_t now,
                     State& state, Snapshot& snapshot);
bool currentSnapshot(const Policy&, uint64_t now, const State&, Snapshot&, bool fresh = true);
// Strict event JSON object or matched EVENT frame. State serial observations can
// advance on a valid root-signed certificate even if later binding checks fail.
bool authorize(const Document&, size_t event, const Policy&, uint64_t now, State&,
               Authorization&, std::vector<uint8_t>& canonicalPayload, std::string& reason);
bool eligibleNow(const Policy&, uint64_t now, const Authorization&);
bool higher(const Authorization& a, const Authorization& b);
std::string fetchSubscription(bool priority);
std::string fetchRequest(bool priority);
std::vector<uint8_t> unhex(const std::string&);
std::string hex(const uint8_t*, size_t);
}}
