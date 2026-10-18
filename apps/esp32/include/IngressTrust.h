#pragma once
// Optional PUBLIC generated configuration, supplied later by the root operator.
// No secrets, no event-driven root discovery. Local enable/clock decisions belong
// in private/gateway/config.h, not in a generated root file.
#if !defined(MESH_INGRESS_COMPILE_CHECK) && __has_include("IngressTrust.generated.h")
#include "IngressTrust.generated.h"
#endif
#ifndef MESH_INGRESS_ENABLED
#define MESH_INGRESS_ENABLED 0
#endif
#ifndef MESH_INGRESS_TRUST_CLOCK
#define MESH_INGRESS_TRUST_CLOCK 0
#endif
#ifndef MESH_INGRESS_ROOT_PUBKEY
#define MESH_INGRESS_ROOT_PUBKEY ""
#endif
#ifndef MESH_INGRESS_SCOPE_CBOR_HEX
#define MESH_INGRESS_SCOPE_CBOR_HEX ""
#endif
#ifndef MESH_INGRESS_SELECTED_CBOR_HEX
#define MESH_INGRESS_SELECTED_CBOR_HEX ""
#endif
#ifndef MESH_INGRESS_SNAPSHOT_URL
#define MESH_INGRESS_SNAPSHOT_URL ""
#endif
#ifndef MESH_INGRESS_SNAPSHOT_CA
#define MESH_INGRESS_SNAPSHOT_CA ""
#endif
#ifndef MESH_INGRESS_MIN_SEQUENCE
#define MESH_INGRESS_MIN_SEQUENCE 1ULL
#endif
