# BSAF → Mosimo server publisher (experimental wire-v1)

`publisher.mts` is a separate Node 24 signing worker. It reads **the existing local HappyView/collector SQLite** and pushes signed kind-1 `EVENT` messages to the configured PDS **Nostr-only** relay path. It constructs `dev.oriom.mosimo.post`/atproto v3 commit and MST metadata solely as signed verification evidence for current receivers: these BSAF imports must never create atproto jobs, repository records, or firehose entries. Constructing that evidence is not atproto publication. No generic HappyView record hook, new source abstraction, new HTTP server, automatic BSAF repost, or automatic PLC registration is used.

## Explicit opt-in and fixed defaults

Collection never enables publication. Both publisher and receiver need explicit `(botDid,type)` selections. Default: disabled, empty selection, no fixed priority override, maximum original age 3,600 seconds. Once the pinned Bot/type pairs are explicitly selected, the local JMA priority policy below applies by default; otherwise the hint is 1. The optional **fixed** JMA-source Bot preset is:

- Bot DID: `did:plc:vwi7zhfyamyvrg5v6ycjgzxi`
- Types: `ashfall`, `earthquake`, `eruption`, `nankai-trough`, `tsunami`

A separate `selectKikikuruDefaults` opt-in selects `@bsaf-kikikuru-bot.bsky.social`, DID `did:plc:kxwz5cz7o6g4jlmxh6doyfsm`, and exactly these 23 types:

`avalanche-warning`, `dense-fog-warning`, `dry-air-warning`, `flood-warning`, `frost-warning`, `heavy-rain`, `heavy-rain-warning`, `icing-warning`, `landslide-warning`, `linear-rainband-warning`, `low-temperature-warning`, `melting-snow-warning`, `other-warning`, `significant-snowfall-warning`, `snow-accretion-warning`, `snow-warning`, `storm-surge-warning`, `thunderstorm-warning`, `tornado-warning`, `typhoon`, `wave-warning`, `weather-info`, `wind-warning`.

The DID and complete 23-option `type` list were checked against the [public primary bot-definition](https://raw.githubusercontent.com/osprey74/bsaf-kikikuru-bot/main/bot-definition.json) (`updated_at: 2026-08-01T00:00:00Z`). This pins configuration, not current PLC ownership or original-post signatures. Runtime never fetches definitions or follows new types. `selectJmaDefaults` still selects **only the previous five**; the two fixed presets may combine (28 pairs). Custom `selection` is an alternative to **both** presets and cannot be mixed with either. Weather hints stay **normal 1** with null priority, even for `level5`; no reviewed weather severity mapping is introduced. A deliberate fixed `priority` override remains available.

Neither preset ever follows newly added types. This is a third-party Bot/importer selection, **not** a JMA organization certificate or a claim that JMA signed the post. `source:jma`, handles and original-URI claims from other accounts do not grant authorization. Redistribution permission must be reviewed independently by the operator.

```nix
services.mosimo-bsaf = {
  enable = true;
  authors = [
      "did:plc:vwi7zhfyamyvrg5v6ycjgzxi" # @jma-alert-bot.bsky.social
      "did:plc:kxwz5cz7o6g4jlmxh6doyfsm" # @bsaf-kikikuru-bot.bsky.social
    ];
  publisher = {
    enable = true;
    selectJmaDefaults = true; # unchanged five-type opt-in
        selectKikikuruDefaults = true; # separate 23-type opt-in; omit/false to keep JMA-only
    credentialsFile = "/var/lib/mosimo-importer/credentials.json"; # string, NOT a Nix path literal
    trustFile = "/var/lib/mosimo-importer/roots.json";
    relayUrl = "wss://YOUR-PDS/"; # PDS Nostr endpoint is the root WebSocket path
    maxOriginalAgeSeconds = 3600;
    jmaPriorityPolicy = true; # exact pinned local policy below; not source:jma authority
    priority = null; # optional explicit 0..3 override wins; set 1 to force normal
  };
};
```

This is an example, not an activated deployment. `LoadCredential` passes only importer credentials to `mosimo-bsaf-publisher.service`. Root/PLC rotation secrets are never loaded. Keep files outside `/home` because `ProtectHome` is enabled. Node old-space is 64 MiB, service MemoryHigh/Max 96/128 MiB. The package reuses the existing **locked PDS runtime dependencies**, preserving relative imports of the single shared implementation; no extra npm dependency/lockfile update is needed.

Direct execution: `PUBLISHER_CONFIG_FILE=/absolute/publisher.json node publisher.mts`. JSON fields are `enabled`, `databasePath`, `credentialsPath`, `trustPath` (public root array), `relayUrl`, `selection` (`[{botDid,types}]`), `maxOriginalAgeSeconds` (1..86400), optional `jmaPriorityPolicy` (default true), optional `priority` (null/omitted, or explicit 0..3 override). There is no implicit relay or selection. Do not run this against a real destination until issuance/registration and redistribution review are complete.

Source checkout startup needs **both locked dependency trees**: run `npm ci` in `appview/bsaf` and `npm ci --prefix ../../pds` from that directory before starting the worker. The relative `../../pds/public-information.mjs` import resolves crypto/repository dependencies through `pds/node_modules`, not through the collector's node_modules. The private `@mosimo/pds` package does not need to be downloaded from a registry; it is repository-local source. Nix builds the sibling PDS runtime from its own existing lockfile/npmDepsHash and links it at `lib/pds`, preserving the same relative module/dependency resolution. No root/importer secrets are build inputs.

### Pinned local priority policy

With `priority` null/omitted and `jmaPriorityPolicy` true, only the exact approved Bot DID above **and an explicitly selected type** use this table. `type:` and `value:` are exact raw machine strings; no trimming, case changes, numeric coercion, aliases, inferred severity, or inferred region is applied.

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
| ashfall, unknown values, all other pairs | any unmapped value | 1 |

This is an explicit **local delivery-order policy**, not a claim that these strings encode an official agency severity, predicted/observed intensity, alert relevance, or geography. The original tags remain unchanged. Unknown/new/case-varied values stay at 1. Another Bot saying `source:jma` never inherits this mapping. Setting `jmaPriorityPolicy=false` disables it; an explicit `priority` 0..3 overrides it, including explicit 0 or 1.

Uncommitted eligible DB candidates are ranked by this hint **before** the eight-record candidate limit, then by collector time and URI. Existing signed/pending commits cannot be reordered because repository bases must be acknowledged in order. Every staged/send event still passes root/binding/snapshot/scope/selection checks. Effective priority above 1 is granted only after full receiver authorization; the hint does not bypass freshness, revocation, quota, geographic/relevance decisions or mesh limits. This does not globally sort arbitrary public posts highest-first or change standard NIP-01 `REQ` semantics.

## Offline root and importer CLI

Run `node identity-cli.mjs COMMAND ...` from this directory. Every output is under ignored `private/`; exclusive creation refuses to overwrite keys. POSIX directory/file modes are 0700/0600 and files are fsynced. On Windows verify the directory ACL yourself; POSIX mode bits are not an ACL guarantee. Commands only report a fixed status, never key material.

1. **Root environment, offline:** `root-create https://YOUR-TRUST-HOST/snapshot.cbor` creates `root-secret.json` and public `roots.json`. The root configuration contains only `rootPubkey`, `enabled`, pinned scope, `snapshotUrl`, `minSequence`. Root authority is limited to the importer organization and selected fixed Bot/type presets. Optional `--jma` and/or `--kikikuru` flags choose the root scope; no flags retain the historical five JMA types. `--kikikuru` alone selects only weather.
2. **Importer environment:** `importer-create https://YOUR-PDS` creates independent Nostr, atproto and PLC rotation keys, signed PLC genesis, reciprocal Android-compatible binding, and public `importer-request.json`. This does **not** register the DID or publish anything.
3. Transfer **only the public request** to the offline root environment. Review organization/redistribution permission out of band. The currently implemented CLI issuance path is deliberately experimental **unregistered/genesis-only**, not a current PLC Directory identity assertion. `issue-genesis I-reviewed-organization-and-genesis` verifies PLC genesis-derived DID, genesis signature, atproto key and both binding signatures; then writes a 30-day leaf certificate and an issuance record explicitly marked `unregistered-genesis-only`. For a registered DID, independently verify its current PLC key before issuance; this CLI does not implement Directory-backed issuance and must not be represented as doing so.
   Issuance accepts the same optional `--jma`/`--kikikuru` flags and validates **every** requested pair against the actual configured root. Selecting weather requires a separately approved root-scope extension and a **new serial certificate**, not editing/reinterpreting an existing signed certificate. Historical no-flag issuance remains JMA-only.
4. Return public `certificate.json`/`roots.json` to the importer environment; `assemble` checks leaf/DID/key/binding and produces **importer-only** `credentials.json`, excluding root and PLC rotation secrets. Do not transfer `root-secret.json` or `importer-secret.json` to the worker.
5. **Root environment:** `snapshot [complete-crl.json]` produces canonical `snapshot.cbor` and durable `snapshot-state.json`. Sequence increases, CRLs are complete/cumulative, and entries cannot be removed. CRL JSON is `{ "revokedCertIds": [], "revokedPubkeys": [] }`, sorted/distinct, maximum 256 combined entries. A lock excludes concurrent issuance; a stale lock after a crash needs operator inspection, never automatic sequence reset. Keep/back up snapshot state permanently.
6. Publish **only** `snapshot.cbor` via the configured HTTPS URL with `Content-Type: application/cbor`. Transfer using an operator-approved mechanism; the CLI does not upload. Schedule snapshot signing/transfer at least every 30 minutes on a **separate secured issuing environment**, not the application server. Every snapshot lasts at most one hour. If the offline issuer cannot refresh, new ingress stops after expiry; do not work around this by extending snapshots or copying the root secret online. A continuously air-gapped root and uninterrupted fresh snapshots require a deliberate operational transfer process.

7. `receiver-export [--jma] [--kikikuru]` validates the local snapshot and writes **only in ignored `private/`**: Android `mosimo-public-trust.json` (`roots` plus flattened `selected`), PDS `pds-public-information.json` (`publicInformation.roots/selection`), and ESP32 `IngressTrust.generated.h` (public key/scope/selection/snapshot URL, current snapshot sequence as minimum, **empty CA, no ingress-enable/trusted-clock macros**). It validates the explicit selection against the actual root scope, not a hard-coded JMA assumption. No flags still export only the historical five; both flags explicitly export all 28. It refuses to overwrite existing exports; archive old exports explicitly before regenerating.

**Current local material:** with explicit user approval, root local/public scope was extended to both fixed presets (5+23), keeping the **same root key and importer DID/all keys**. The prior active 12 files were copied byte-for-byte to ignored `private/before-kikikuru-sequence-2/`; their JMA-only scope is historical and unchanged. A **new serial** 30-day `unregistered-genesis-only` leaf and worker credentials were issued, snapshot advanced **2→3** without CRL loss, and all three receiver exports explicitly select 28 pairs with minimum sequence 3. The old leaf/key was not revoked. Earlier `bootstrap-invalid/` backups remain untouched. Root URL remains `https://pds.oriom.dev/.well-known/mosimo/trust/snapshot.cbor`, not a hosting claim. No PLC registration, upload, posting, provisioning, or deployment occurred. Snapshot 3 expires at **2026-10-18T19:13:51Z** (**2026-10-19 04:13:51 JST**); refresh with a higher sequence at deployment. UTC/JST dates are consistent; local validation is not independent clock synchronization. See **[DEPLOYMENT-CHECKLIST.md](DEPLOYMENT-CHECKLIST.md)** for exact files, signed expiry times, ACL audit and deployment gates. Keep the root/PLC secrets on the secured issuing machine, not beside the eventual service.

## Source semantics, durable outbox and failure policy

- Source is a direct join of `happyview_records`, `mosimo_bsaf_events` (matching URI **and CID**), and `mosimo_bsaf_sources`. The record URI must match the collector envelope DID/rkey. The worker recalculates original DAG-CBOR CID using Lex JSON links/bytes. It preserves the complete original record JSON plus the collector's URI/DID/CID/rkey, source URL, fetch time and Jetstream time in `mosimo_bsaf_publications`. The old collector did not retain raw WebSocket frame bytes; this archive preserves its full record and attribution receipt, not an invented original signed envelope.
- Nostr `content` is **exactly** the source `record.text`, including Unicode, whitespace and any DIDs already present in the original. No importer/author DID, CID, source label, attribution or redistribution footer is appended or stripped. Identity, source URI/CID and authorization evidence remain in signed tags/extensions and the local provenance archive, never added to visible text. Tags, languages and original `createdAt` retain their strings/order. No invented region, CAP/JMA XML, predicted intensity, institution authority, or cancellation semantics.
- Plain records and already-visible literal URL facets are supported. Attachments, replies, labels, nonliteral/mention/tag facets and unknown source fields are **held**, not silently stripped. Collector viewing remains unaffected. There is no blob download or truncated-content fallback.
- `deliveryExpiresAt = floor(original.createdAt seconds) + maxOriginalAgeSeconds`, not fetch/retry time. Future, expired, malformed, ambiguous or unsupported sources are not signed. `(uri,cid)` is unique; a different CID is separate, never an implicit replacement/cancellation.
- One `BEGIN IMMEDIATE` transaction archives original/provenance, fixes the exact event/deadline, and advances `mosimo_bsaf_repository` (identity, full MST entries, previous commit CID, monotonic rev micros). Failure/crash rolls all of these back. Snapshot high-water and issuer/serial records are SQLite sidecars, never memory-only authorization state. A detected issuer+serial equivocation is queued as security evidence and durably inserted into `mosimo_bsaf_certificate_equivocations` **after** the enclosing transaction commits/rolls back and before its promise settles. Both the original and conflicting body then remain rejected, including after restart. Catching the conflict inside the callback and throwing a different publication error does not discard the marker; a marker-write failure rejects the operation rather than authorizing.
- New candidates use the pinned priority ordering above; once committed, only the oldest pending event is sent, after fresh authorization checks. Exact positive `['OK', event.id, true, string]` marks accepted, including positive duplicate acknowledgements. Negative/malformed/unrelated responses, disconnect, abort, timeout, or a crash after relay acceptance retry the **same signed bytes/event ID**. Backoff 2..256 seconds, 10-second attempt timeout, no unbounded promise queue. Later commits cannot overtake unacknowledged bases.
- Expired/revoked/unselected/stale pending events remain held and are **not renewed or skipped**. Legacy signed pending events whose content differs from archived source text also remain pending with an explicit `hold_reason`; they are never sent, rewritten, re-signed or overtaken. This preserves the signed evidence chain, even though it is no longer published as an atproto repository. Operator recovery needs explicit repository/history reconciliation or a reviewed new importer identity/database migration. Do not delete the outbox/dedup/high-water tables to get around a failure.
- Limits: 10,000 publication/dedup records and 10,000 repo entries, eight DB candidates per staging pass, source JSON at most 262,144 bytes, 64 roots, 16 selected Bots / 32 types per Bot / 64 total selection pairs, 64 event tags/16 extensions, CBOR depth 16/items 8192, 32 KiB mesh payload, 20 KiB identity, 4 KiB leaf, 32 KiB snapshot. At capacity the worker stops signing rather than deleting dedup/history or truncating content. Original source rows over the JSON cap stay in the collector and are not staged. MST rebuild is deliberately bounded O(n), matching existing PDS reconstruction; no performance/load guarantee is implied.
- Snapshot fetch: configured HTTPS only, no redirects, 10-second timeout, strict `application/cbor`, 32 KiB cap, re-canonicalization, root signature and atomic monotonic/cumulative validation. Refresh every 60 seconds, with independent failure handling per root; a failed earlier root cannot prevent later roots' fresh snapshots/revocations from being accepted. `refreshSnapshots` returns `{refreshed,failed}` counts without leaking upstream details, and the scheduler records every completed pass so partial failures do not trigger one-second fetch storms. Failure does not erase an existing fresh snapshot or its permanent high-water state. A trusted local clock is required. The Node service uses the OS clock; operators must maintain trustworthy time synchronization.

## Shared API / PDS worker handoff

Authoritative implementation: `../../pds/public-information.mjs`. Only that file and its sibling test were added under PDS.

```js
const result = await verifyPublicInformation(event, {
  roots,        // explicitly provisioned public root settings array, <=64
  snapshots,    // durable adapter below; never fetched from an event-chosen URL
  nowSeconds,   // trusted current receive/send time, safe nonnegative integer
  selection,    // explicit [{botDid, types}], defaults []
});
```

Success returns `{ authorized: true, priority, effectivePriority, rootId, leafCertId, botDid, type, snapshot: { sequence, issuedAt, validUntil }, checkedAt, checks: { authorSignature: true, binding: true, chainScope: true, certificateTime: true, revocation: 'fresh-as-of' } }`. Every authorization failure **throws**, with no ordinary-post ingress fallback. `priorityHint(event)` returns a valid unique 0..3 hint or 1; `effectivePriority(event, authorized=false)` caps unauthenticated hints at 1. For priority ordering, catch verification errors and use `effectivePriority(event, false)`; never use that catch to auto-ingress the event. A failed ingress check does not itself delete/invalidate the underlying Nostr signature.

The durable `snapshots` adapter MUST implement:

- `get(rootId)` → complete signed snapshot (or throw if unavailable).
- `accept(rootId, snapshot)` → atomically read previous persisted snapshot, run `await verifySnapshot(snapshot, {root, nowSeconds, previous})`, and durably store it. Reject rollback below configured minSequence/saved sequence, same-sequence differing **body hash**, earlier issuedAt, CRL removal, and write failure. Preserve state after expiry/restart; a root-key revocation remains in the cumulative set forever. Same-body re-signing is allowed. Keep at most the bounded provisioned roots. The shared verifier deliberately requires this adapter rather than secretly maintaining process-local state.
- `recordCertificate(issuerPubkey, serial, certId)` → atomically insert or compare. If an issuer+serial has another body ID, persist a **sticky equivocation marker independently of the rejected event/publication transaction** and reject **both** bodies permanently. Re-presenting certificate A after A→B conflict must not authorize it again. Persist security state before returning authorization/rejection; marker-write failures fail closed. Bound storage under local policy; never evict security state to authorize more events.

For AppView, `snapshotStore`, `cacheSnapshot` and the sidecar SQLite transactions implement this contract. Cache ingestion (`cacheSnapshot`) commits separately **before** checking a particular event, so even a root-revocation snapshot survives that event's authorization rejection. The PDS worker must do the same: do not wrap snapshot ingestion in a transaction that rolls back solely because the event is revoked. `verifySnapshot` validates signed cumulative state, including snapshots revoking the root itself; `verifyPublicInformation` denies ingress after persistence. No network fetch is performed by `verifyPublicInformation`.

Other exports:

- `canonical(value, options?)`, `decodeCanonical(bytes, options?)`, `bodyHash(body)`, `rootId(compressedKey)`, `certId(certificate)`, `keyPublic(secretHex)`, `timestamp(seconds)`.
- `createRoot({scope,snapshotUrl,minSequence?})` → `{secretKey,root}`; private caller only.
- `issueCertificate(rootSecretHex, fields)` → signed leaf, with generated serial and fixed v1 defaults.
- `createSnapshot(rootSecretHex, {sequence,issuedAt,validUntil,revokedCertIds?,revokedPubkeys?})` and `verifySnapshot(snapshot,{root,nowSeconds,previous?})`.
- `createImporter({endpoint?})` → independent private keys, genesis-derived DID, genesis and reciprocal binding. `verifyImporterGenesis(publicRequest)` checks genesis-only self-consistency; **not** current Directory identity.
- `createBinding({did,atprotoSecret,nostrSecret})`, `verifyBinding(binding,{did,atprotoPubkey,nostrPubkey})`.
- `verifyChain(chain,{roots,nowSeconds,eventSeconds?,selection,botDid,type})` accepts **root→leaf only**; >1 certificate is unsupported/fail-closed, never an intermediate promoted to root.
- `verifyPublicCommit(decodedPost, compressedAtprotoKey)`, `verifyBsaf(extension)`, `validateScope(scope)`.
- `createImporterEvent({importer,chain,text,extensions,nowSeconds,deliveryExpiresAt,state?,priority?})` → `{event,record,state}`. State is `{entries:[{key,cid}],base,lastMicros}`. Call only inside the publisher's atomic repo/outbox transaction, then verify before commit. It does not fetch data or publish.
- `NS`, `IDENTITY`, `BSAF`, `PRIORITY`, `JMA_SELECTION` are shared wire constants.

The verifier authenticates the importer's signed attribution claim and commit signature, **not original Bot repo inclusion or current PLC Directory state**. Ordinary atproto user posts still require independent PDS PLC/key checks. BSAF-import routing instead depends only on the canonical signed extension's `original` claim, not issuer DID allowlists, certificate validity or snapshot freshness; malformed claimed import extensions fail closed from atproto. Existing queued jobs are guarded too. Historical records and AppView display/query behavior are unchanged; no feed filtering or database purge is applied. Input is an already-parsed event; network JSON parsing must reject duplicate keys/invalid UTF-8 **before** information is lost during parsing. CBOR parsing here rejects duplicate keys, noncanonical encoding, unsafe integers, high-S/DER ECDSA, tags in new signed objects, depth/items/size excess and trailing data. Unknown canonical extensions are preserved; malformed/multiple priority hints never elevate priority. Standard NIP-01 `REQ` behavior must remain unchanged by any PDS priority-order extension.

## Local verification

```sh
# from appview/bsaf; both local lockfiles are required for shared-module imports
npm ci
npm ci --prefix ../../pds
npm test
npm run typecheck
# from pds; npm ci if needed
node --test public-information.test.mjs
npm test
npm run typecheck
# Linux/Nix host only; no deploy
nix-instantiate --eval --strict tests.nix --arg nixpkgs /path/to/nixpkgs
```

Tests use synthetic records, ephemeral credentials/databases, and a loopback WebSocket relay. They do not use the generated operational private files, contact real Bot/PDS/PLC services, post, or deploy. Node additionally consumes the Android `wire-v1.json` shared fixture and checks exact certificate/snapshot/record/commit CBOR, body hashes, certId, binding and ingress signatures. That receiver fixture's signed MST root does not match its record's reconstructed MST, so it is not a successful normal-PDS-repository fixture; the actual publisher's successive MSTs/CAR reconstruction are independently tested. Kotlin/ESP32 execution and Nix build/module evaluation still need their environments; Node success is not a claim those runs happened.
