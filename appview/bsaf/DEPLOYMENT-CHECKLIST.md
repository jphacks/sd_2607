# BSAF importer deployment checklist

Material is produced offline with `identity-cli.mjs` ([PUBLISHER.md](PUBLISHER.md)). The private directory is Git-ignored and mode 0700; never attach it to an issue/PR or copy it wholesale to a server.

## What goes where

| File | Destination | Notes |
|---|---|---|
| `root-secret.json`, `ca-secret.json`, `importer-secret.json` | nowhere | Offline only. `importer-secret.json` holds the PLC rotation key as well |
| `credentials.json` | `/var/lib/mosimo-importer/credentials.json` (root, 0600) | Worker-only: importer DID, atproto/Nostr signing secrets, signing chain |
| `roots.json` | `/var/lib/mosimo-importer/roots.json` (root, 0600) | Public root settings used for the publisher's self-check |
| `revocations.cbor` | `/var/lib/mosimo-trust/revocations.cbor` (root:caddy, 0640) | Served at `https://pds.oriom.dev/.well-known/mosimo/attest/revocations` as `application/cbor` |
| `pds-public-information.json` | PDS `services.mosimo-pds.settings.publicInformation` | Public: root key, roles, depth, selection, revocation domains |
| `mosimo-public-trust.json` | client assets (Android/ESP32) | Public; shipped trust stays empty until provisioned deliberately |

The operations repository ships `bin/mosimo-trust-install.sh` to copy the three runtime files and restart the publisher.

## Gates

- [ ] `identity-cli verify` reports `verified` / `valid` for the assembled chain.
- [ ] Redistribution of the selected Bots is reviewed independently; the chain is the operator's own importer authorization, not JMA's.
- [ ] `publicInformation` of the PDS and `selection` of the publisher list the same `(botDid, type)` pairs, and both are inside the CA/leaf `importer` blocks.
- [ ] `revocations.cbor` is published and reachable as `application/cbor` without redirects.
- [ ] Leaf `keyValidity.until` is noted; re-run `issue` + `assemble` before it passes (default 730 days).
- [ ] Backups cover `/var/lib/mosimo-importer`, `/var/lib/mosimo-trust` and the HappyView/PDS databases, which hold the durable revocation state.

## Revoking

```sh
node identity-cli.mjs revoke <subject pubkey hex> --reason=compromise   # leaf: signed by the CA; CA: signed by the root
node identity-cli.mjs revocation-list                                   # rewrites revocations.cbor
```

Publish the new `revocations.cbor`; relays and the publisher pick it up within their refresh interval and refuse the subject from `effectiveAt`. Issue a new leaf with `issue` + `assemble` afterwards.
