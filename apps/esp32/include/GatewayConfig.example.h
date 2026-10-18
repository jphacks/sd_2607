#pragma once
// Copy to private/gateway/config.h (gitignored). Empty values are fail-closed.
// Never commit the filled header or share firmware containing Wi-Fi credentials.
#define MESH_GATEWAY_WIFI_SSID ""
#define MESH_GATEWAY_WIFI_PASSWORD ""
// DNS hostname only, not a URL. Choose a protocol-capable PDS relay for reconstruction.
#define MESH_GATEWAY_RELAY_HOST ""
#define MESH_GATEWAY_RELAY_PORT 443
#define MESH_GATEWAY_RELAY_PATH "/"
// PEM root CA(s) obtained/verified out of band for this relay. No insecure fallback.
#define MESH_GATEWAY_RELAY_CA R"PEM()PEM"
// Optional: exact HTTPS Directory base URL, without trailing slash (e.g. https://plc.directory).
// Disabled PLC leaves genesis controls pinned/retrying; it never publishes kind 8173 to Nostr.
#define MESH_GATEWAY_PLC_URL ""
#define MESH_GATEWAY_PLC_CA R"PEM()PEM"
#define MESH_GATEWAY_NTP_SERVER "pool.ntp.org"
// Optional wire-v1 ingress is OFF without explicit local opt-ins and public root
// configuration; see include/IngressTrust.example.h. A requests a bounded fetch.
// #define MESH_INGRESS_ENABLED 1
// #define MESH_INGRESS_TRUST_CLOCK 1 // acknowledges that SNTP itself is unauthenticated
// #define MESH_INGRESS_SELECTED_CBOR_HEX "..." // exact selected JMA Bot DID/types
