# ESP32 host validation

Host tests compile the real firmware sources (C++11, `-Wall -Wextra -Werror`,
ASan/UBSan) against mocks of LittleFS, the clock/RNG, NimBLE and FreeRTOS. They
never flash a device, publish events or submit PLC operations.

| Runner | Production code | Covers |
|---|---|---|
| `test_bundle.py --host <bin>` | `MeshBundle.cpp` (via `host.cpp`) | Official BIP340 vectors; mesh CBOR v1 payload ([mesh §2.1](../../../protocol/mosimo/mesh/README.md)): typed `mosimo:commit`/`mosimo:ext:*` (unpadded standard Base64), `mosimo:base` (CID base32 `b…`), `mosimo:rkey` (TID ↔ uint), `mosimo:exp` (delta seconds), omitted fixed tags for kind 1/8173, exact-form deadline; BPv7 envelope, CRC, hop/age/residence, forwarding, truncations and mutations. Then runs `test_base64.py`. |
| `test_relay.py` | `MeshRelay.cpp`, `MeshMessages.cpp`, `Negentropy.cpp`, `MeshBundle.cpp` | Common exchange messages ([mesh §5](../../../protocol/mosimo/mesh/README.md)) against a simulated peer: HELLO/side rules, Negentropy as initiator and responder plus self-consistency down to the 1000-byte minimum frame, INVENTORY/WANT, BEGIN/DATA/CREDIT flow control in both directions, RESULT codes, FETCH "unavailable", idle release, cold entries after reboot, save-stage and eviction-journal faults, `/mesh-seen` dedup, gateway pinning (kind 1/5, PLC and revocation 8173 only), adv summary. |
| `test_ble.py [--combined]` | `MeshBle.cpp` | CBOR-sequence framing over GATT (split/coalesced/oversized/malformed items), notification pacing, queue/overflow recovery, radio toggle, network pause, scan-response Service Data (node, flags, summary). |
| `test_gateway.py` | `MeshBundle.cpp`, gateway headers | NIP-01 export of kind 1/5/8173 from the mesh payload, PLC DID derivation, relay OK parsing, HTTP/WebSocket bounds. |
| `test_main_controls.py [--diagnostic\|--combined]` | `main.cpp` | Buttons, status line, preview/speech routing, UART commands. |
| `test_speech.py [--combined]` | `MeshSpeech.cpp` | Speech worker boundaries. |
| `test_repository_integration.py` | `host.cpp` + real Kotlin + PDS | Real Kotlin → ESP32 C++ → Kotlin → PDS. Needs JDK 21, the Gradle cache and Node 24; **not re-run after the protocol overhaul**. |

## Dependencies

- A host C++ compiler and mbedTLS **2.x** (the codec uses 2.x API names, as the
  espressif32 6.12.0 framework does). mbedTLS 3.x is not supported.
- Python 3.10+ with `cbor2`.

On NixOS (nixpkgs no longer ships mbedTLS 2, so take it from 24.05):

```sh
M=$(nix build --no-link --print-out-paths github:NixOS/nixpkgs/nixos-24.05#mbedtls_2)
PY=$(nix build --no-link --print-out-paths --impure --expr 'with import <nixpkgs> {}; python3.withPackages (p: [ p.cbor2 ])')/bin/python3
cd apps/esp32/test_bundle
nix shell nixpkgs#gcc -c g++ -std=c++11 -Wall -Wextra -Werror -g -fsanitize=address,undefined \
  -I../include -I$M/include host.cpp $M/lib/libmbedcrypto.a -o /tmp/mesh-bundle-host
$PY test_bundle.py --host /tmp/mesh-bundle-host
nix shell nixpkgs#gcc -c $PY test_relay.py --mbedtls-include $M/include --mbedtls-library $M/lib/libmbedcrypto.a
nix shell nixpkgs#gcc -c $PY test_gateway.py --mbedtls-include $M/include --mbedtls-library $M/lib/libmbedcrypto.a
nix shell nixpkgs#gcc -c $PY test_ble.py --sanitize
nix shell nixpkgs#gcc -c $PY test_main_controls.py --sanitize --diagnostic
nix shell nixpkgs#gcc -c $PY test_speech.py --sanitize
```

`test_gateway.py` defaults `--arduinojson` to `../.pio/libdeps/m5stack-basic/ArduinoJson/src`,
which exists after one `pio run`. `host.cpp` includes `MeshBundle.cpp` directly so
the private verifier can be exercised; do not link `MeshBundle.cpp` separately.

## Fixtures

`fixtures.json` is deterministic (public test secret key **3**, deterministic
BIP340 nonce, zero auxiliary randomness) and holds the signing JSON, payload,
event ID, a valid BPv7 envelope and a signature-tampered envelope with correct
CRCs. Regenerate after changing the generator:

```sh
$PY test_bundle.py --generate fixtures.json
```

`bip340-vectors.csv` is the unmodified official fixture from
<https://github.com/bitcoin/bips/blob/master/bip-0340/test-vectors.csv>; only the
15 vectors with 32-byte messages (Nostr digests) apply.

`GenerateAndroidFixture.kt` / `generate_android_fixture.py` compile the real
Android `bundle-core` sources to produce bundles (used by
`test_repository_integration.py`). Real-Android interoperability of the current
protocol was checked on hardware instead; see [VERIFICATION.md](../VERIFICATION.md).

## Deliberate limits

- **No trusted clock:** a valid signed `mosimo:exp` is mandatory, but its expiry
  is UNKNOWN (Android `ClockConfidence.UNKNOWN`). Bundle age/lifetime, hop count
  and monotonic residence still apply.
- The outer payload array is decoded leniently (shape only, as on Android); tag
  values must be exact. Signed inner bytes (commit, extensions) are opaque.
- Key-attestation references (mesh §2.1.3) are not restored; such Bundles fail
  signature verification and are refused, and HELLO advertises no support.
