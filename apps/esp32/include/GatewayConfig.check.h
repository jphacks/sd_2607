#pragma once
// COMPILE/LINK TEST ONLY, never deploy. No usable credentials, CA or destination.
// Nonempty values keep the compiler from eliminating all networking as unreachable.
#include "GatewayConfig.example.h"
#undef MESH_GATEWAY_WIFI_SSID
#undef MESH_GATEWAY_RELAY_HOST
#undef MESH_GATEWAY_RELAY_CA
#undef MESH_GATEWAY_PLC_URL
#undef MESH_GATEWAY_PLC_CA
#define MESH_GATEWAY_WIFI_SSID "BUILD-CHECK-ONLY"
#define MESH_GATEWAY_RELAY_HOST "relay.invalid"
#define MESH_GATEWAY_RELAY_CA "COMPILE CHECK: NOT A CERTIFICATE"
#define MESH_GATEWAY_PLC_URL "https://plc.invalid"
#define MESH_GATEWAY_PLC_CA "COMPILE CHECK: NOT A CERTIFICATE"
