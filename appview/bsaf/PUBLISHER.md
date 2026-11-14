# BSAF → Mosimo publisher (dev.jisaku.mosimo.attest.importer)

`publisher.mts` is a separate Node 24 signing worker. It reads **the existing local HappyView/collector SQLite**, converts explicitly selected BSAF originals into `dev.jisaku.mosimo.post` records with the [`public.bsaf`](../../protocol/mosimo/public/bsaf/README.md) extension (`original` required), embeds the importer's [signing chain](../../protocol/mosimo/attest/chain/README.md) and an optional [`priority`](../../protocol/mosimo/priority/README.md) hint, signs the atproto commit and the Nostr event with the importer's own keys, and pushes `EVENT` messages to the configured PDS relay. Imports are **Nostr-only**: the PDS stores and relays them but never creates atproto jobs, repository records or firehose entries ([importer §2](../../protocol/mosimo/attest/importer/README.md#2-投稿)).

## Trust model: no periodic refresh

The importer is authorized by a key-attestation chain, not by a short-lived snapshot. Keys are issued and revoked with the CA tool in [`ca/`](../../ca/README.md) (`mosimo-ca`); its key directory mirrors the chain, each directory named by the `owner.id` its parent gave it. The chain currently in production:

```
oriom-root                         root key (offline, stratum 0), receivers' root setting maxDepth 16
  └─ oriom-mosimo-ca               KeyAttestation, no expiry, issuerDomain = pds.oriom.dev, delegation maxDepth 1
       └─ did:plc:vy6l6jpmhxupmelsphj7dpnx   importer Nostr key (leaf), keyValidity.until 2028-11-10
                                    roles [import], organization, importer block (allowed Bot/type pairs), trustLevel
```

App releases before the root setting was raised to `maxDepth 16` accept at most this two-step chain, so this CA and leaf stay in use until those releases are gone. New importers are planned as root → role-specific issuer → importer operator → node, which needs the newer root setting.

- The chain travels inside every import post (`mosimo:ext:dev.jisaku.mosimo.attest.chain`). Receivers verify it offline against their locally configured root (`publicInformation.roots`: public key, allowed roles, allowed depth) and their own `(botDid, type)` selection ([importer §3](../../protocol/mosimo/attest/importer/README.md#3-受信者の認可)).
- Nothing expires for two years. Renewal is `mosimo-ca issue` for a new leaf + `mosimo-ca credentials` + redeploying the key directory.
- Revocation is a signed fact ([attest.revocation](../../protocol/mosimo/attest/revocation/README.md)). The CA publishes a signed list at `https://<issuerDomain>/.well-known/mosimo/attest/revocations` (`application/cbor`); it only changes when a key is revoked. Receivers that are online fetch it periodically and persist notices; fetch failure keeps the last state and never blocks relaying. Revocations can also travel as kind `8173` control events.
- Receivers (this publisher included) check the chain at the post's `created_at` **and** at the current time, require the `import` role plus `importer` and `organization` blocks, require the original `(botDid, type)` to be inside both the importer block and the local selection, and refuse `revoked`/`revoked-later` chains.

## Issuing an importer

```sh
# 1. A new did:plc identity for the node (PLC rotation key, atproto key, Nostr key). The file is new and mode 0600.
node identity-cli.mjs importer-create https://pds.example.org --out=importer-secret.json
# 2. The parent signs the node's Nostr key; the DID, atproto key and PLC rotation key go to the node's _importer/ directory.
mosimo-ca issue PARENT NAME --leaf --owner-name 'BSAF importer' --org-id ... --org-name ... --preset jma --preset kikikuru \
  --importer-identity importer-secret.json --days 730
rm importer-secret.json   # holds the PLC rotation secret in plain text
# 3. The key directory for the server (below), then deploy it.
mosimo-ca credentials PARENT/NAME --out ./deploy
```

`identity-cli.mjs receiver-export --anchor=ca/keys/<root>/anchor.json --domain=<issuerDomain> [--jma] [--kikikuru] --out=DIR` writes the receivers' public configuration (`pds-public-information.json`, `mosimo-public-trust.json`).

## Key placement

The publisher is the only Mosimo server that holds private keys (the PDS, AppView and the revocation list endpoint hold none). It gets exactly the importer's two signing keys, never a root, CA, operator or PLC rotation key.

| File (written by `mosimo-ca credentials`) | Host path | Format |
|---|---|---|
| `identity.json` | `/var/lib/mosimo-importer/identity.json` | `{did, nostrPubkey, atprotoPubkey}` (public) |
| `chain.cbor` | `/var/lib/mosimo-importer/chain.cbor` | canonical CBOR array of KeyAttestation, root-issued first, importer leaf last |
| `nostr.pem` | `/var/lib/mosimo-importer/nostr.pem` | PKCS#8 PEM, secp256k1: the attested importer key (signs Nostr events) |
| `atproto.pem` | `/var/lib/mosimo-importer/atproto.pem` | PKCS#8 PEM, secp256k1: the DID's atproto signing key (signs commits) |
| `roots.json` | `/var/lib/mosimo-importer/roots.json` | public root settings for the publisher's self-check (`trustFile`) |

- `/var/lib/mosimo-importer` is `root:root 0700`, every file `root:root 0600`. The PEM files are unencrypted because the service starts unattended; the host directory, systemd credentials and backups are what protect them. Do not place them under `/nix/store` or in a Nix expression.
- `services.mosimo-bsaf.publisher.credentialsDirectory` points at the host directory. The module passes the four key files with systemd `LoadCredential` (`identity.json:<dir>/identity.json`, ...), so the worker reads them from `/run/credentials/mosimo-bsaf-publisher.service/` and has no access to the host directory itself.
- The worker refuses to start unless `nostr.pem` and `atproto.pem` are unencrypted secp256k1 PKCS#8 keys matching `identity.json`, the two keys differ, and the chain's leaf is the Nostr key.
- Replace the files atomically (write `*.next`, then rename) and restart the publisher. Delete the `--out` directory on the workstation after deploying.

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
    credentialsDirectory = "/var/lib/mosimo-importer"; # string, NOT a Nix path literal
    trustFile = "/var/lib/mosimo-importer/roots.json";
    relayUrl = "wss://pds.oriom.dev/"; # PDS Nostr endpoint is the root WebSocket path
    maxOriginalAgeSeconds = 3600;
  };
};
```

Direct execution: `PUBLISHER_CONFIG_FILE=/absolute/publisher.json node publisher.mts`. JSON fields: `enabled`, `databasePath`, `credentialsDirectory` (directory with the four key files), `trustPath`, `relayUrl`, `selection`, `maxOriginalAgeSeconds` (1..86400), optional `jmaPriorityPolicy` (default true), optional `priority` (0..3 override).

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
nix develop ../../ca -c cargo test --manifest-path ../../ca/Cargo.toml   # mosimo-ca: issuance, revocation, credentials directory
```
