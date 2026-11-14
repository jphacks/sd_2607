# BSAF importer deployment checklist

Keys are issued offline with the CA tool `mosimo-ca` ([ca/README.md](../../ca/README.md)); the importer-specific steps are in [PUBLISHER.md](PUBLISHER.md). The key directory `ca/keys/` is Git-ignored and mode 0700; never attach it to an issue/PR or copy it wholesale to a server.

## What goes where

| Material | Destination | Notes |
|---|---|---|
| root, CA and operator keys (`ca/keys/**/secret.pem[.age]`) | nowhere | Offline only |
| PLC rotation key (`_importer/plc-rotation.pem[.age]`) | nowhere | Offline only |
| `mosimo-ca credentials NODE --out DIR`: `identity.json`, `chain.cbor`, `nostr.pem`, `atproto.pem` | `/var/lib/mosimo-importer/` (root 0700, files root 0600) | The only private keys on a server ([PUBLISHER.md, Key placement](PUBLISHER.md#key-placement)) |
| same directory: `roots.json` | `/var/lib/mosimo-importer/roots.json` (root, 0600) | Public root settings used for the publisher's self-check |
| `<CA>/_revocation-list/revocations.cbor` | `/var/lib/mosimo-trust/revocations.cbor` (root:caddy, 0640) | Served at `https://<issuerDomain>/.well-known/mosimo/attest/revocations` as `application/cbor` |
| `identity-cli receiver-export`: `pds-public-information.json` | PDS `services.mosimo-pds.settings.publicInformation` | Public: root key, roles, depth, selection, revocation domains |
| `identity-cli receiver-export`: `mosimo-public-trust.json` | client assets (Android/iOS) | Public |

The operations repository ships `bin/mosimo-trust-install.sh` to copy the runtime files and restart the publisher.

## Gates

- [ ] `mosimo-ca verify` reports `OK` for the importer's key, and `mosimo-ca credentials` succeeds (it refuses revoked or expired chains).
- [ ] Redistribution of the selected Bots is reviewed independently; the chain is the operator's own importer authorization, not JMA's.
- [ ] `publicInformation` of the PDS and `selection` of the publisher list the same `(botDid, type)` pairs, and both are inside every `importer` block of the chain.
- [ ] `revocations.cbor` is published and reachable as `application/cbor` without redirects (`mosimo-ca check-published <CA>`).
- [ ] Leaf `keyValidity.until` is noted; issue a new leaf and redeploy before it passes (default 730 days).
- [ ] Backups cover `/var/lib/mosimo-importer`, `/var/lib/mosimo-trust` and the HappyView/PDS databases, which hold the durable revocation state.

## Revoking

```sh
mosimo-ca revoke <key> --reason compromise   # signed by the parent (or --by an ancestor)
mosimo-ca publish <key>                      # checks, then rewrites the CA's revocations.cbor
mosimo-ca check-published <CA>               # after deploying revocations.cbor
```

Relays and the publisher pick the list up within their refresh interval and refuse the subject from `effectiveAt`. Issue a new leaf afterwards.
