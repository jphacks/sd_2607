# BSAF → Mosimo publisher (dev.jisaku.mosimo.attest.importer)

`publisher.mts` is a separate Node 24 signing worker. It reads **the existing local HappyView/collector SQLite**, converts explicitly selected BSAF originals into `dev.jisaku.mosimo.post` records with the [`public.bsaf`](../../protocol/mosimo/public/bsaf/README.md) extension (`original` required), embeds the importer's [signing chain](../../protocol/mosimo/attest/chain/README.md) and an optional [`priority`](../../protocol/mosimo/priority/README.md) hint, signs the atproto commit and the Nostr event with the importer's own keys, and pushes `EVENT` messages to the configured PDS relay. Imports are **Nostr-only**: the PDS stores and relays them but never creates atproto jobs, repository records or firehose entries ([importer §2](../../protocol/mosimo/attest/importer/README.md#2-投稿)).

## Trust model: no periodic refresh

The importer is authorized by a key-attestation chain, not by a short-lived snapshot:

```
root key (offline, stratum 0)
  └─ KeyAttestation, no expiry ─▶ CA key (stratum 1), issuerDomain = pds.oriom.dev, delegation maxDepth 1
        └─ KeyAttestation, keyValidity.until = issue + 730 days ─▶ importer Nostr key (leaf)
              roles [import], organization, importer block (allowed Bot/type pairs), trustLevel
```

- The chain travels inside every import post (`mosimo:ext:dev.jisaku.mosimo.attest.chain`). Receivers verify it offline against their locally configured root (`publicInformation.roots`: public key, allowed roles, allowed depth) and their own `(botDid, type)` selection ([importer §3](../../protocol/mosimo/attest/importer/README.md#3-受信者の認可)).
- Nothing expires for two years. Renewal is `identity-cli issue` + `assemble` + redeploying `credentials.json`.
- Revocation is a signed fact ([attest.revocation](../../protocol/mosimo/attest/revocation/README.md)). The CA publishes a signed list at `https://<issuerDomain>/.well-known/mosimo/attest/revocations` (`application/cbor`); it only changes when a key is revoked. Receivers that are online fetch it periodically and persist notices; fetch failure keeps the last state and never blocks relaying. Revocations can also travel as kind `8173` control events.
- Receivers (this publisher included) check the chain at the post's `created_at` **and** at the current time, require the `import` role plus `importer` and `organization` blocks, require the original `(botDid, type)` to be inside both the importer block and the local selection, and refuse `revoked`/`revoked-later` chains.

## Files produced by `identity-cli.mjs`

All output goes to the private directory (`./private/` or `MOSIMO_PRIVATE_DIR`, mode 0700, Git-ignored). Signed objects are stored as unpadded Base64 of their canonical CBOR.

| Command | Writes | Deploy to |
|---|---|---|
| `root-create [--name=]` | `root-secret.json`, `roots.json` (public root settings) | `roots.json` → publisher `trustFile`; PDS `publicInformation.roots` via `receiver-export` |
| `ca-create DOMAIN [--jma] [--kikikuru]` | `ca-secret.json`, `ca-attestation.json`, `ca-revocation-notice.json` (pre-issued) | nothing (CA secret stays offline) |
| `importer-create HTTPS_PDS` | `importer-secret.json` (incl. PLC rotation key), `importer-request.json` | nothing |
| `issue [--jma] [--kikikuru] [--days=730] [--org-id=] [--org-name=]` | `leaf-attestation.json`, `leaf-revocation-notice.json` | nothing |
| `assemble` | `credentials.json` = `{importer:{did,atprotoSecret,nostrSecret}, chain}` | publisher `credentialsFile` only |
| `revocation-list` | `revocations.cbor` (signed by the CA; empty until `revoke`) | `https://DOMAIN/.well-known/mosimo/attest/revocations` |
| `revoke PUBKEY_HEX [--reason=code]` | appends to `revocations.json`; rerun `revocation-list` | republish `revocations.cbor` |
| `receiver-export [--jma] [--kikikuru]` | `pds-public-information.json`, `mosimo-public-trust.json` | PDS settings / client assets |
| `verify` | prints the chain status of `credentials.json` against `roots.json` | — |

The worker receives **only** `credentials.json` (through `LoadCredential`) and `roots.json`. Root, CA and PLC rotation secrets never leave the private directory.

## Explicit opt-in and fixed defaults

Collection never enables publication. Both publisher and receivers need explicit `(botDid,type)` selections, and the CA/leaf `importer` blocks must contain them too. Default: disabled, empty selection, no fixed priority override, maximum original age 3,600 seconds.

- `selectJmaDefaults`: `did:plc:vwi7zhfyamyvrg5v6ycjgzxi` with `ashfall`, `earthquake`, `eruption`, `nankai-trough`, `tsunami`.
- `selectKikikuruDefaults`: `@bsaf-kikikuru-bot.bsky.social`, `did:plc:kxwz5cz7o6g4jlmxh6doyfsm`, exactly the 23 types pinned from the [public bot definition](https://raw.githubusercontent.com/osprey74/bsaf-kikikuru-bot/main/bot-definition.json) (`updated_at: 2026-08-01T00:00:00Z`): `avalanche-warning`, `dense-fog-warning`, `dry-air-warning`, `flood-warning`, `frost-warning`, `heavy-rain`, `heavy-rain-warning`, `icing-warning`, `landslide-warning`, `linear-rainband-warning`, `low-temperature-warning`, `melting-snow-warning`, `other-warning`, `significant-snowfall-warning`, `snow-accretion-warning`, `snow-warning`, `storm-surge-warning`, `thunderstorm-warning`, `tornado-warning`, `typhoon`, `wave-warning`, `weather-info`, `wind-warning`.

Both presets may be combined (28 pairs). A custom `selection` replaces both presets. Neither preset follows newly added upstream types. This is the operator's own importer, **not** a JMA certificate: `source:jma`, handles and self-declared `original` URIs grant nothing.

```nix
services.mosimo-bsaf = {
  enable = true;
  authors = [ "did:plc:vwi7zhfyamyvrg5v6ycjgzxi" "did:plc:kxwz5cz7o6g4jlmxh6doyfsm" ];
  publisher = {
    enable = true;
    selectJmaDefaults = true;
    selectKikikuruDefaults = true;
    credentialsFile = "/var/lib/mosimo-importer/credentials.json"; # string, NOT a Nix path literal
    trustFile = "/var/lib/mosimo-importer/roots.json";
    relayUrl = "wss://pds.oriom.dev/"; # PDS Nostr endpoint is the root WebSocket path
    maxOriginalAgeSeconds = 3600;
  };
};
```

Direct execution: `PUBLISHER_CONFIG_FILE=/absolute/publisher.json node publisher.mts`. JSON fields: `enabled`, `databasePath`, `credentialsPath`, `trustPath`, `relayUrl`, `selection`, `maxOriginalAgeSeconds` (1..86400), optional `jmaPriorityPolicy` (default true), optional `priority` (0..3 override).

## Runtime behaviour

- Every second: stage the next selected original (oldest first, JMA priority policy first), self-authorize the signed event with the same rules a relay applies, then send it and wait for a positive `OK`. Failures retry the **same** signed event; the delivery deadline (`exp` = original `createdAt` + `maxOriginalAgeSeconds`) is never renewed.
- Every 10 minutes: fetch the revocation list of each `issuerDomain` in the chain into `mosimo_bsaf_revocations`. A fetched revocation of the leaf or the CA stops staging and sending (fail closed) and is logged once as `publisher-held` with the reason.
- Originals with attachments, replies, labels, non-literal facets, CID mismatches or outside the age window are recorded as `held` and never signed. Text is copied verbatim; nothing is appended.

### Pinned local priority policy

With `priority` null and `jmaPriorityPolicy` true, only the JMA Bot **and an explicitly selected type** use this table; everything else is hint 1. `type:`/`value:` are exact raw strings.

| Type | Exact `value:` | Signed hint |
|---|---|---|
| tsunami | `severe-warning`, `special-warning` | 3 |
| tsunami | `warning`, `advisory` | 2 |
| nankai-trough | `warning` | 3 |
| nankai-trough | `advisory` | 2 |
| eruption | `warning` | 3 |
| eruption | `advisory` | 2 |
| earthquake | `5-`, `5+` | 2 |
| earthquake | `6-`, `6+`, `7` | 3 |

Receivers still cap unauthorized hints at 1 and evaluate `effectivePriority` with their own roots.

## Local verification

```sh
cd appview/bsaf && npm ci && npm ci --prefix ../../pds
npm test && npm run typecheck
MOSIMO_PRIVATE_DIR=$(mktemp -d) node identity-cli.mjs root-create && node identity-cli.mjs ca-create pds.example.org --jma --kikikuru \
  && node identity-cli.mjs importer-create https://pds.example.org && node identity-cli.mjs issue --jma --kikikuru \
  && node identity-cli.mjs assemble && node identity-cli.mjs revocation-list && node identity-cli.mjs verify
```
