#pragma once
namespace mesh {
class Relay;
class Ble;
// Main-loop-only filesystem/cache access. Network task owns no Relay/FS/UI state.
void gatewayBegin(Relay& relay);
void gatewayPoll(Relay& relay, Ble* ble = nullptr);
// Combined profile only: called by the speech worker, never by main/BLE callbacks.
void gatewayWorkPoll();
bool gatewayNetworkBusy();
// Coalesced on-demand request, no network work here. False when ingress disabled.
bool gatewayRequestIngress();
}
