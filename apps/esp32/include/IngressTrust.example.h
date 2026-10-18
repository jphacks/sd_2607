#pragma once
// PUBLIC integration contract. Parent/root operator may generate
// include/IngressTrust.generated.h with these fields. Do not insert root secrets.
#define MESH_INGRESS_ROOT_PUBKEY "" // lowercase SEC1 compressed secp256k1 Hex33
#define MESH_INGRESS_SCOPE_CBOR_HEX "" // C({orgIds:[...],bsaf:[{botDid,types:[...]}]}) hex
#define MESH_INGRESS_SNAPSHOT_URL "" // absolute HTTPS DNS URL, port 443, no redirects
#define MESH_INGRESS_SNAPSHOT_CA R"PEM()PEM" // public PEM TLS root CA(s)
#define MESH_INGRESS_MIN_SEQUENCE 1ULL // provisioned anti-replay lower bound
// LOCAL independent selection, e.g. the exact JMA Bot DID/type pairs approved
// for this receiver. C([{botDid:"did:plc:...",types:["exact-type",...]}]) hex.
// Sort botDids/types and orgIds by UTF-8 lexicographic order; no wildcard matching.
// This can instead be defined in private/gateway/config.h.
#define MESH_INGRESS_SELECTED_CBOR_HEX ""
// Never generate these as root authority: local opt-ins only, default false:
// #define MESH_INGRESS_ENABLED 1
// #define MESH_INGRESS_TRUST_CLOCK 1
