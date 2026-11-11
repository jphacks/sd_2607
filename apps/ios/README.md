# もしもの防災 (Mosimo Bosai) iOS

SwiftUI port of the Android app (`apps/android`, 0.2.2 feature set) for iOS 17+, iPhone and iPad.

- `MosimoCore/` — Swift package, the protocol core ported from `bundle-core` + the pure parts of `mesh-android`:
  canonical CBOR, secp256k1 (libsecp256k1 via `swift-secp256k1`), NIP-01 events, atproto record/MST/commit,
  PLC DID, mesh payload v1 (0012 §4), BPv7 bundles byte-compatible with the vendored dtn7-kotlin,
  `MeshEngine`, wire-v1 `PublicIngress` (0020), provisional mndt §9 wire (JSON control + BPv7 frames),
  relay subscription, public-information download, PLC directory, encrypted state file.
- `Mosimo/` — the app: SwiftUI screens, `MeshCoordinator` (single main-actor owner of mesh/relay/disk state),
  `GattLink` (Core Bluetooth), `LanLink` (Network.framework Bonjour `_mosimo._tcp` + TCP), location.

## Build and test

```sh
mise run test      # MosimoCore unit tests on the Mac (Android/ESP32 fixtures read in place)
mise run generate  # Mosimo.xcodeproj from project.yml (XcodeGen; the project is not committed)
mise run build     # device build (automatic signing, team 356F93V4HL, bundle dev.jisaku.mosimo.dev)
mise run install   # build + install on every paired iPhone/iPad
```

The trust roots ship from `apps/android/mesh-android/src/main/assets/mosimo-public-trust.json` (referenced, not copied).

### Device test loop (DEBUG builds only)

```sh
xcrun devicectl device process launch --console --terminate-existing --device <udid> dev.jisaku.mosimo.dev -- \
  -mosimo.test.relays wss://pds.oriom.dev -mosimo.test.post "テスト"
```

- `--console` streams `[mosimo]`/`[exchange]`/`[ble]`/`[lan]` diagnostics.
- Lab settings (`-mosimo.test.node`/`peer.node`/`links`/`relays`) persist across launches, so a later Android interop test
  sees no peers; the start note shows `ラボ隔離` while they apply. `-- -mosimo.test.reset 1` clears them.
- `-mosimo.test.relays` seeds the relay list of a *fresh* install (keeps test posts off public relays).
- `-mosimo.test.post` publishes one located post at launch.

## iOS-specific behaviour (vs Android)

- **BLE discovery.** iOS cannot advertise 128-bit Service Data, which Android/ESP32 require before dialing.
  iOS therefore always dials Android/ESP32 (as central) and advertises its node id as the local name;
  two iOS devices use the normal tie-break on that name. Android → iOS dialing would need Android to read the
  local name (not changed here).
- **Background.** No foreground service: `bluetooth-central`/`bluetooth-peripheral` background modes and
  Core Bluetooth state restoration give best-effort relaying; LAN runs only in the foreground.
  Measured behaviour is in `VERIFICATION.md`.
- **Map.** MapKit with GSI pale tiles (inverted to dark with Core Image, 200 MB URL cache) instead of MapLibre.
- **Storage.** Same AES-256-GCM state file format as Android; the key lives in the Keychain
  (this device only, after first unlock); the file is excluded from backups.
