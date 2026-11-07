# iOS verification (0.1.0)

Devices: iPhone 13 mini (iPhone14,4) and iPad Air 11-inch M3 (iPad15,3), Xcode 27.2, DEBUG builds,
free Personal Team signing. Lab isolation via DEBUG launch arguments (see README): iPad node `1111…`,
iPhone node `2222…`, each allowing only the other; relays `wss://pds.oriom.dev` only.

## Host tests (`mise run test`)

17 XCTests pass on the Mac:
- server publisher fixture (`apps/android/.../server-publisher-wire-v1.json`): record/commit bytes, CIDs, MST roots,
  mesh payload byte-identical; wire-v1 ingress authorizes both posts (priority 3, sequence 7); mesh relay preserves the
  original NIP-01 JSON.
- combined ESP32/Android fixture: all 28 JMA + Kikikuru Bot/types authorize; deselection, unselected event reject.
- ESP32 `fixtures.json` bundle parses and re-marshals byte-identically; tampered signature rejected.
- Android Base64 typed-tag cases, BIP-340 vector 0, NIP-01 escaping vector, atproto MST reference roots,
  canonical CBOR rejections, encrypted state round trip, shipped trust asset parses (1 root, 28 selections).

## Device results (2026-11-06)

| Check | Result |
|---|---|
| BLE only (`-mosimo.test.links ble`), iPhone posts | ✅ iPad dials, HELLO both ways, 5 bundles (≈900 B, 512 B chunks) verified and stored, hop 1 |
| LAN only (`-mosimo.test.links lan`), iPad posts | ✅ Bonjour `_mosimo._tcp` discovery, TCP connect, bundle accepted on iPhone |
| BLE delivery to a **backgrounded** iPhone (Settings app in front) | ✅ iPad (foreground) dials the node-less background advertisement; post accepted in the background |
| Online export | ✅ `OK` from `wss://pds.oriom.dev` for every test post; both devices also act as gateway for received posts |
| Display subscription | ✅ posts from `pds.oriom.dev` shown on the map/timeline as オンライン経由 |
| Public information (`MOSIMO-REQ` priority-v1) | ✅ 32 events fetched; ordinary posts rejected for lack of identity. ⚠️ Live CRL snapshot `snapshot.cbor` is stale (validUntil 2026-10-18T23:19:38Z), so ingress fails closed as specified — server-side issue |
| PLC | ✅ genesis registered/confirmed on plc.directory (one DID per device) |
| Android interop (incidental) | ✅ a nearby Android node (Service Data advert) was dialed over BLE and connected over LAN (HELLO/inventory). Full interop test is the next phase |
| Map rendering | ✅ GSI pale tiles inverted dark, pins, cluster badges, area circles, connectivity pills |

### Issues found and fixed on device

- Core Bluetooth state restoration: restored scan/advertisement carried the previous process's node name, and
  delegate calls made during the restoring `init` were dropped → setup re-run after init, advertisement replaced once
  per start, restored GATT service reused (re-adding it left centrals subscribed to stale handles; notifications lost).
- stdout block-buffered on a piped console → line-buffered in DEBUG.
- Lab isolation now persists across background relaunches (which receive no launch arguments).

### Known limits

- Android never dials iOS (iOS cannot advertise Service Data); iOS always dials Android/ESP32.
- Background: works while the system keeps the app alive (bluetooth-central/peripheral modes, state restoration).
  Not guaranteed after force-quit or long suspension; LAN stops in the background. Not measured: screen locked for
  hours, Low Power Mode.
- Early in testing, before isolation persisted, five 「［安全］BLEテスト」 test posts reached `relay.damus.io`
  (most likely through the nearby Android gateway via an unisolated background relaunch). Isolated posts afterwards
  stayed on `pds.oriom.dev` only (checked by event id).
