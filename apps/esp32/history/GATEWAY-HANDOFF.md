# COM3 gateway + TTS handoff — 2026-10-18 UTC

## Ready state

M5Stack Basic ESP32-D0WDQ6-V3, physical flash **16,777,216 B**, CH9102 **COM3**. Deployed `m5stack-basic-gateway-tts` via PlatformIO; every written region passed esptool hash verification. No `uploadfs`, flash erase, LittleFS format, Android/ADB operation, host Wi-Fi switch, Git commit or push was performed. COM3 is closed/released. **Do not reset/reflash before the parent's phone test:** restored bundles are cold and do not regain live forwarding eligibility.

Last bounded handoff capture (`.pio/gateway-tts-handoff-status.log`):

```text
status heap=44476 stored=16 radio=1 speech_ready=1 muted=0
gateway: wifi=1 clock=1 ingress_clock=1 heap=44656 pending=0 busy=0 utc_epoch=1792361419 max_alloc=14324 min_heap=25636
alive uptime_ms=379625 heap=44656 stored=16 stack_free=7080 radio=1 muted=0
```

SSID is the explicitly approved `TECHNOPOLIS 2.4`. Its exact Windows saved profile was absent; the captured saved TECHNOPOLIS key was tried as authorized and **actual association succeeded**. No password was printed. Private Wi-Fi config, original evaluation SDK/config/dictionary, build products and backups remain local/ignored. The local **public-only** root export has floor **4**, 23 Kikikuru + 5 JMA types and root fingerprint `9d60fc70a23705b9c350caba47acf117374046442950da94979bbe500855676a`. Public TLS PEMs were taken from chains actually verified using the host system trust store for `pds.oriom.dev` / `plc.directory`; no insecure TLS fallback exists.

The already-existing Windows task **`MosimoTrustSnapshot`** was inspected, not modified: repetition **PT30M**, last result **0**, last run epoch **1792360189**, next run observed **1792361959**. The device independently verified fresh root-signed snapshot **10**, issued **1792360187**, valid until **1792363787**, with SNTP freshness checked in this boot. This is an explicitly accepted unauthenticated-SNTP deployment assumption, not secure time.

## Actual BSAF download → verified save → speech → BLE ACK

Full event ID:

```text
fdf91c4fad6595ceed0eaacc8d59f8089ccbba767c89aa38130be4a48da36ad4
```

Final firmware log: **`.pio/gateway-tts-final-e2e.log`**. Exact evidence:

```text
ingress: window complete path=priority-v1 events=8 selected=fdf91c4fad6595ceed0eaacc8d59f8089ccbba767c89aa38130be4a48da36ad4 priority=1 heap=85276 min_heap=31232 stack_free=6804
ingress: id=fdf91c4fad6595ceed0eaacc8d59f8089ccbba767c89aa38130be4a48da36ad4 persisted before advertise; verified candidate heap=100752 max_alloc=34804 min_heap=31232
TTS start id=fdf91c4fad6595ceed0eaacc8d59f8089ccbba767c89aa38130be4a48da36ad4
TTS done id=fdf91c4fad6595ceed0eaacc8d59f8089ccbba767c89aa38130be4a48da36ad4 samples=169080 nonzero=97061 chunks=530
mesh: sent fdf91c4f hop 1 3375B
mesh: peer accepted fdf91c4f
gateway: fdf91c4fad6595ceed0eaacc8d59f8089ccbba767c89aa38130be4a48da36ad4 accepted (not PDS publication)
```

- Bot: `did:plc:kxwz5cz7o6g4jlmxh6doyfsm`; type **heavy-rain-warning**; local effective priority **1**, highest among eligible candidates in the bounded returned eight-event window. This is not a global-relay priority guarantee.
- Leaf certificate: `b3f52501f4465ffc0590b514d8c1353b7393616ba04dc8a5b85ef30eeded1984`; importer label **Mosimo BSAF importer (not JMA)**. The logged author signature, binding, chain/scope, certificate time and fresh revocation checks all passed before admission.
- Original: `at://did:plc:kxwz5cz7o6g4jlmxh6doyfsm/app.bsky.feed.post/3my6liqsq3q2j`.
- Original CID: `bafyreictm47742bessobpzgt5cnpvmj3m7dhqiyijqwlnd2sueysern6qq`.
- Independent authenticated relay readback recomputed the Nostr ID and verified BIP340. A separate HTTPS public-source lookup matched that exact original URI/CID and confirmed **Nostr content equals original record text exactly**, **301 UTF-8 bytes** (unchanged content cap 2,048 B), SHA-256 `111d6996162fff46a4de2f5d2c2ac35901708daa04370ea7d910c484886abcd2`. No added attribution/truncation/re-signing was used on ESP32.
- Measured source bounds: event JSON **4,337 B**, priority WebSocket frame **4,362 / 6,144 B**, identity **1,848 / 4,096 B**, certificate **1,281 / 4,096 B**; the actual certificate contains **23 + 5 types**. The emitted BPv7 bundle was **3,375 / 24,000 B**. No receiver size limit was expanded.
- Readback artifacts: `.pio/relay-fdf91c4fad6595ceed0eaacc8d59f8089ccbba767c89aa38130be4a48da36ad4.json` and matching `-proof.json`. This is not a Bot-repository signature/MST inclusion-proof claim. Nostr-only BSAF does not imply PDS reconstruction.

`TTS done` proves nonzero PCM generation and speaker API completion, **not independent audible-quality/DMA-output measurement**. Evaluation SDK **na/ma → nu** substitution remains; no commercial-SDK fix or license claim was made.

## Additional live BLE → speech → uplink observation

Without touching phones, a new ordinary BLE event was observed during the final capture:

```text
89bf6db086ac61dc892c7fe0793e38f5fefe7e7d530ee9325b08493cc94b488b
```

ESP32 logged `mesh: saved 89bf6db0 hop 1 785B`, then matching full-ID speech completion **10,756 samples / 2,127 nonzero / 34 chunks**, then **exact full-ID positive relay OK**. Independent relay readback verified its ID and BIP340 signature. Its archive/proof has the corresponding `.pio/relay-<id>*.json` name. No claim is made about which physical phone authored it, whether that phone was offline, or PDS/AppView publication. The parent's fresh **two-phone offline publication** test remains theirs to perform and correlate by the two supplied full IDs.

## Resource findings and limits

Initial concurrent builds were **not successful**: ~18 KiB idle heap, TLS admission starvation, later allocator aborts during TLS/crypto or BLE resend while TTS was active. Intermediate failures are retained in ignored `.pio/gateway-tts-*v*.log` / `.pio/gateway-tts-final-proof.log`; they are not reused as successful validation.

Final design: one 16 KiB network/speech worker, 12 KiB loop stack, lazy SDK buffers, main-loop permission before speech, BLE stack/queue deinitialized during TLS **and** speech, same-ID BLE reinitialization after work. Ingress closes TLS before each strict verification and retains a preallocated raw winner; eight events + EOSE require at most nine bounded reconnects/pass. JSON/content/tree/crypto/CRL bounds remain unchanged. The too-conservative single 32 KiB admission hole was replaced only in the combined profile with an 18 KiB per-record-buffer floor plus >=80 KiB total and existing per-job JSON checks.

Measured final run: ~107–110 KiB at network dispatch, ~49 KiB with ingress TLS active, ~76 KiB after candidate authorization, ~90 KiB during speech, ~44–45 KiB with an active BLE session at handoff. Final minimum free heap **25,636 B**, main stack watermark **7,080 B**, worker watermark **6,804 B**. The final capture had **zero** allocator aborts/reboots, memory-admission deferrals, event-queue overflows or unconfirmed upload results. `pending=0` was subsequently captured without resetting.

This is **time-sliced radio operation**, not simultaneous BLE/Wi-Fi data-plane coexistence. New BLE receipts wait during utterances/network jobs; phones must reconnect/retry. SDK timeout/mute is cooperative between SDK calls. Long endurance, power-cut recovery, adversarial peak combinations and the parent's fresh two-phone offline → PDS/AppView test are not proven by this bounded run.

## Backup, partitions and footprint

**Before any partition change**, all **16 MiB** were read twice, remaining in the bootloader between reads. SHA-256 equality was confirmed and the retained file was rehashed again after testing:

- Directory: `private/backups/20261018T203229Z/`.
- File: `full-16MiB.bin`.
- Both full-read SHA-256 values: **`de8e71987298ab1865dc5ba1b4cabec5f924eca8a7b8c51bbbfab3f2772243fe`**.
- Original 4 KiB partition-table SHA-256: `f9eb7ed41dc92bd7ee5d6ef9fdb87c145036b1105abf819180d3d7b262932017`.
- `manifest.json` contains each original region's exact offsets/sizes/hash; `partitions.bin` retains the actual original table.

| Region | Original offset / size | Deployed offset / size |
|---|---|---|
| nvs | `0x9000 / 0x5000` | unchanged |
| otadata | `0xe000 / 0x2000` | same region; standard upload boot selection |
| app0 | `0x10000 / 0x140000` | `0x10000 / 0x280000` |
| app1 | `0x150000 / 0x140000` | merged into app0; no spare OTA slot |
| spiffs (LittleFS) | `0x290000 / 0x160000` | unchanged |
| coredump | `0x3f0000 / 0x10000` | unchanged |
| aqdic | `0x400000 / 0x200000` | unchanged |

The matching deployed partition image passed esptool's write/hash verification. The existing **1,969,776 B** dictionary passed the firmware boot SHA-256 check; existing **16** retained bundles restored before normal operation. Subsequent cache replacements were normal verified custody operations **after positive upload confirmation**, not migration erasure; evicted IDs enter the persistent seen journal and originals remain in the full backup/public relay.

Final firmware: **1,826,857 / 2,621,440 B (69.7%)**, static RAM **68,160 B**. Linked binary file **1,833,440 B**, SHA-256 `8c7cf986fc3a274da64addfd137c1be54c3a128132ef288ed775e960f84e903b`. Firmware/SDK/dictionary/private config/backup are **not Git artifacts**.

## Validation and parent commands

Passed: ordinary, diagnostic, gateway-check, combined-check and real combined firmware builds; WSL ASan/UBSan speech (ordinary + combined), real main controls (**90** ordinary diagnostic and **90** combined assertions), BLE (**344** ordinary / **346** combined), Relay (**4,634** assertions), gateway transport/protocol (**66,051** assertions + **21** signed-field/PLC oracle cases), ingress (**124** signed/state cases + **30** codec/order assertions, all 28 combined server fixture types and negatives). The successful real UART command used `--require-ingress-speech`, which had also correctly failed earlier crashing runs.

A hardware-only stale-`errno` issue was fixed at checkpoint creation: successful Arduino create-on-missing can leave `ENOENT`; clear it **after successful open and before write**, without ignoring write/flush/readback errors. The host FS mock now reproduces that exact case.

From `apps/esp32`, **after this worker has released COM3**:

```powershell
.venv/Scripts/python.exe scripts/gateway_serial.py COM3 --command status --seconds 15 --log .pio/parent-status.log
# For a NEW eligible BSAF event; existing full IDs correctly do not speak again:
.venv/Scripts/python.exe scripts/gateway_serial.py COM3 --audio-on --command download --seconds 300 --log .pio/parent-download.log --require-ingress-speech
# Capture the parent's two newly created OFFLINE phone IDs flowing through ESP32:
.venv/Scripts/python.exe scripts/gateway_serial.py COM3 --seconds 180 --log .pio/parent-offline-uplink.log
# Independent public relay ID/signature readback (no publishing):
node scripts/check_relay_event.mjs <full-event-id>
# For BSAF only, additionally compare exact referenced source URI/CID/text:
node scripts/check_relay_event.mjs <full-bsaf-event-id> --original
```

Commands are bounded and do not request reset. B controls BLE only, not Internet privacy. Use one serial owner; do not run PlatformIO monitor, another serial helper, flash or backup concurrently. No peer restriction remains; default acceptance is unrestricted. Keep device powered/running for the parent's offline test.
