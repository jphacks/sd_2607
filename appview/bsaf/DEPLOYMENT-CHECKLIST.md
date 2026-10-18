# Local BSAF importer material — deployment checklist

This records the **user-approved local issuance** for the operator's own **Mosimo BSAF importer (not JMA)**. It is documentation for a later explicit deployment, **not** a deployment result. Material generation was local-only: it made no PLC or external network requests and performed no upload, post, device provisioning, or service activation.

## Material generated locally

All paths below are relative to `appview/bsaf/private/`. This directory, including the public exports, is Git-ignored. Do not attach the directory to an issue/PR or copy it wholesale to a server/device.

| File | Purpose / destination boundary |
|---|---|
| `root-secret.json` | Existing root private key **preserved**, with local snapshot URL metadata updated. Keep in the secured offline issuing environment; never send to importer/PDS/receivers. |
| `roots.json` | Public root settings for local certificate verification/worker provisioning. Snapshot URL is metadata, not proof that hosting exists. |
| `importer-secret.json` | **New** independent atproto/Nostr/PLC keys, signed genesis and binding for `https://pds.oriom.dev`. Keep the PLC rotation secret offline. |
| `importer-request.json` | Public genesis/binding/key-possession issuance request. |
| `certificate.json` | Root→leaf, `bsaf-import`, `mosimo-bsaf-importer`, display name `Mosimo BSAF importer (not JMA)`, 30-day certificate. |
| `issuance.json` | Issuance audit, explicitly `unregistered-genesis-only`; organization approval is the user's own-Mosimo approval, not an external/JMA identity review. |
| `credentials.json` | Worker-only importer DID, atproto/Nostr signing secrets, binding and chain. Contains **neither root secret nor PLC rotation secret**. Copy only this secret to the eventual worker credential location. |
| `snapshot-state.json` | Root-signed complete CRL/high-water state, advanced from sequence **1 to 2**. Retain permanently; never reset sequence or remove prior revocations. |
| `snapshot.cbor` | Canonical signed snapshot bytes for eventual HTTPS publication with `Content-Type: application/cbor`. **Not uploaded/hosted by this task.** |
| `mosimo-public-trust.json` | Public Android `{roots,selected}` export, derived `rootId`, exact flattened selected Bot/type pairs, minimum sequence **2**. Not copied into Android assets. |
| `pds-public-information.json` | Public PDS configuration fragment `{publicInformation:{roots,selection}}`, minimum sequence **2**. Merge only after explicit deployment approval; do not treat as a complete PDS config. |
| `IngressTrust.generated.h` | Public ESP32 root key, canonical-CBOR scope/selection hex, snapshot URL and minimum sequence **2**. TLS snapshot CA is **empty/fail-closed**. Defines neither `MESH_INGRESS_ENABLED` nor `MESH_INGRESS_TRUST_CLOCK`. Not installed into firmware sources. |

Original placeholder material was preserved in **`private/bootstrap-invalid/`**, with exclusive creation and byte-for-byte copy verification:

- Original `root-secret.json` and `roots.json` before the metadata update. The root private key is identical to the active one; this is a secret backup, not a second online root.
- Original `.invalid` `importer-secret.json` and `importer-request.json`. They were archived before creating the new importer; **do not deploy these placeholder identities**.
- Original sequence-1 `snapshot-state.json` and `snapshot.cbor` for provenance. They are not current snapshots and must not replace sequence 2 or later.

Existing backups were not overwritten. Local ignored `prepare-production.mjs`, `verify-production.mjs` and `verify-private-acl.ps1` are migration/audit aids, not deployment inputs. The migration refuses to rerun against the new active material.

## Endpoint and authorization boundaries

- New importer genesis PDS endpoint: **`https://pds.oriom.dev`**.
- Intended PDS Nostr WebSocket: **`wss://pds.oriom.dev/`** (root path, not `/nostr`). No connection was opened to it.
- Root local/public snapshot URL metadata: **`https://pds.oriom.dev/.well-known/mosimo/trust/snapshot.cbor`**. This URL is not in the signed certificate/snapshot body and does not change the preserved root key. **There is no claim this route is configured or serving a snapshot.**
- The leaf certifies the importer, not the original Bot or JMA. Its scope and generated selections are limited to Bot **`did:plc:vwi7zhfyamyvrg5v6ycjgzxi`**, with exactly **ashfall, earthquake, eruption, nankai-trough, tsunami**.
- The DID was re-derived from the signed genesis, and genesis/binding/leaf/snapshot signatures were checked **locally**. This establishes **unregistered genesis-only consistency and root testimony**, not current PLC Directory key ownership, registration, institutional authorization, or PDS acceptance.
- Root-scope authorization does not enable the publisher, receiver auto-ingress, or a trusted clock. Shipped Android trust remains `roots:[]`, `selected:[]`; no ESP generated trust header was installed. The generated files remain only in `private/` until a separate explicit provisioning/deployment action.

## UTC expiry and deployment refresh

The UTC/local calendar dates are consistent: **2026-10-18T17:59:06Z** is **2026-10-19 02:59:06 JST** in **Asia/Tokyo (UTC+09:00)**. This timezone difference is **not evidence of an incorrect clock or a deployment blocker**. No clock trust flag was enabled, no clock was changed and no network time source was contacted; local validation does not independently establish clock synchronization.

The generated certificate's absolute `notAfter` is **2026-11-17T17:52:18Z**. Snapshot sequence 2 has absolute `validUntil` **2026-10-18T18:52:20Z**, equivalent to **2026-10-19 03:52:20 JST**. Freshness depends on the receiver's trusted instant being before that expiry, not on whether its local calendar date is October 19. These are signed expiry fields, not a claim of present freshness.

As part of deployment, run `node identity-cli.mjs snapshot` in the secured root environment to generate a fresh snapshot for publication. This must advance the existing state to sequence **3 or greater**, retaining the complete CRL. If initial receiver configuration has not yet been deployed, archive the old public export files without overwriting them and run `node identity-cli.mjs receiver-export` to pin the newly signed sequence. Never reset `snapshot-state.json`, roll back to `bootstrap-invalid`, extend a previously signed expiry, or enable receiver clock trust to bypass stale data.

## Windows private-directory ACL

Native Windows `whoami /user` identified **`MACBOOK-AIR\chiba`**. `icacls` initially showed inherited broader access, including the sandbox group. Inheritance was disabled at **`private/`**, existing files were restricted to the verified user, and new files/backups inherit only that user's grants.

A recursive audit verified **23 paths** with only the verified current user's allow entries and no broader group grant. The root directory's inheritance is disabled; child inheritance from this user-only directory is intentional. The audit used Windows/.NET ACLs, not POSIX mode bits. This is not a guarantee against an administrator taking ownership, prior access before restriction, malware under the same account, insecure backups, or later ACL changes.

Before transfer, repeat `icacls appview/bsaf/private /T` and inspect the destination ACL/permissions independently. POSIX destinations should use directory 0700 and secret files 0600. Never place root/PLC secrets in a Nix path literal, Nix store, Android asset, ESP header, Git, logs or public web directory.

## Explicit future deployment gates

- [ ] Generate a **fresh**, higher-sequence snapshot at deployment, check its absolute UTC validity window against the trusted receive time, and update initial receiver minimum sequence if appropriate. Local validation alone is not an independent clock-synchronization assertion.
- [ ] Retain the root key and complete snapshot/issuance history on the secured issuing machine. Transfer only the appropriate public request/certificate/configs and worker-only credentials across boundaries.
- [ ] Independently review redistribution conditions. The user approved their own importer organization; the certificate is **not** JMA authorization or a content-truth/relevance guarantee.
- [ ] With a separate explicit authorization, register/check the new DID's PLC genesis/current key state and canonical PDS endpoint. **No registration/current-state check has happened yet.** Do not assume the PDS will accept an unregistered DID.
- [ ] Configure and verify the snapshot HTTPS route, `application/cbor`, no redirects, size/timeout bounds and persistent sequence/CRL history. Do not expose `snapshot-state.json`, root secrets, importer secrets or credentials through that route.
- [ ] Establish a secured issuing/transfer schedule at least every 30 minutes for snapshots of at most one hour. Do **not** copy the root secret onto the online application server for convenience.
- [ ] Verify the snapshot host's appropriate public TLS CA and fill **only the public CA** in the ESP exported header. The current empty CA intentionally prevents successful snapshot fetching. Do not disable TLS verification.
- [ ] Review and explicitly provision Android's public asset, PDS `publicInformation` fragment, and ESP public generated header. Keep ingress enable and trusted-clock decisions separate, local and disabled until their prerequisites hold.
- [ ] Configure AppView's publisher separately: canonical root WebSocket URL, worker-only `LoadCredential`, public roots, exact Bot/type selection, fixed original-age deadline and reviewed local priority policy. Installing a certificate or collecting a Bot does not enable publication automatically.
- [ ] Exercise per-receiver unknown-root/stale snapshot/revocation/serial-equivocation behavior and PDS priority ordering, including the implemented sticky rejection of **both** certificate bodies after an issuer+serial conflict.
- [ ] Take consistent SQLite/trust-state backups, then perform the separately approved deployment. Confirm ACK/retry behavior without deadline renewal and never skip missing repository bases.

## Reproduction and local validation

Both repository-local lockfiles are required; no private package registry is needed:

```sh
# appview/bsaf
npm ci
npm ci --prefix ../../pds
npm test
npm run typecheck
```

`identity-cli.mjs receiver-export` validates the locally signed snapshot and emits the three public platform exports only in `private/`, refusing to overwrite existing exports. Its pure exporter has tests for exact Android/PDS shapes, canonical scope/selection hex, minimum sequence, no secret leakage, empty ESP CA and absent ESP enable/clock macros.

The local audit checked root-key preservation against the archived copy, new canonical endpoint/genesis, binding and leaf signatures/scopes, worker credential key separation, sequence increment/cumulative snapshot signature/CBOR, exact generated exports, and unchanged empty shipped Android trust. It did not use live PLC/PDS services or test actual hosting/device provisioning. Nix/device build or deployment validation remains a separate gate.
