#pragma once
namespace mesh {
class Relay;
// Main-loop-only filesystem/cache access. Network task owns no Relay/FS/UI state.
void gatewayBegin(Relay& relay);
void gatewayPoll(Relay& relay);
// Coalesced on-demand request, no network work here. False when ingress disabled.
bool gatewayRequestIngress();
}
