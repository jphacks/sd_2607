# ESP32 開発・実機試験ガイド

機能概要・対応範囲は [README.md](README.md)、実機検証の条件・記録は [VERIFICATION.md](VERIFICATION.md) を参照。

## セットアップ・ビルド・ホストテスト

Pythonは `.python-version`、Python依存は `uv.lock`、SDKとC++ライブラリーは `platformio.ini` で管理する。以下は `apps/esp32` で実行する。

```powershell
uv sync --locked
uv run python setup_tts.py C:\SDK\aquestalk-esp32_s_0244.zip
uv run pio device list
uv run pio run
```

ZIPパスは正規に入手した評価用SDKの保存場所に置き換える。`setup_tts.py` はSDKをダウンロードせず、ローカルZIPから必要なファイルと辞書SHA-256設定をGit対象外の `private/aquestalk/` に配置する。PlatformIOのSDK・コンパイラー等は初回ダウンロードにネット接続が必要。

現在の `platformio.ini` と `partitions.csv` は16 MiBフラッシュ用。別の実機へ書き込む前に容量と既存パーティション表を確認する。ホストテストの依存・実行手順は [test_bundle/README.md](test_bundle/README.md) を参照。

## 保存領域の初期化・バックアップ

**`uploadfs` は保存済み投稿を消す。通常のファームウェア更新・辞書書込みでは実行しない。** マウント失敗時も自動フォーマットしない。`LittleFS unavailable; not formatted` の場合は、既存領域をバックアップしてから初期化する。

以下のアドレスとサイズは現在の `partitions.csv` のLittleFS領域。ビルドした `partitions.bin` と実機の既存パーティションを照合してから使う。`COM3` は `pio device list` の結果に置き換える。

```powershell
New-Item -ItemType Directory -Force .pio/backups
uv run pio pkg exec --package tool-esptoolpy -- esptool.py --chip esp32 --port COM3 --baud 460800 read_flash 0x290000 0x160000 .pio/backups/pre-littlefs.bin
uv run pio run --target uploadfs --upload-port COM3
```

バックアップの成功を確認してから `uploadfs` を実行する。同じ名前で元のバックアップを上書きしない。`.pio` はGit対象外なので、必要なバックアップは別の安全な場所にも保存する。

## 書き込み・起動確認

ファームウェア書込みはプログラムとパーティションテーブルを置き換える。既存データが必要なら事前にバックアップする。

```powershell
uv run pio run --target upload --upload-port COM3
uv run python smoke_test.py COM3 --mesh --speech
uv run pio device monitor --port COM3
```

スモークテストは**実機をリセット**し、起動・稼働ログ・BLEと評価TTSの初期化を確認する。辞書は下記の初回書込みが必要。モニターは `Ctrl+C` で終了し、同じポートを複数プロセスで開かない。

通常版のAは評価テスト文の読み上げ、gateway版のAは明示設定したwire-v1 ingressの取得要求。BはBLE送受信だけの切替で、gatewayのInternet搬出・取得は止めない。音声対応版のCは音声だけのミュート・解除。通信・音声の設定は揮発性で、再起動時はともに有効へ戻る。音声なし `m5stack-basic-gateway` ではA/Cで音声は再生しない。combined版はTLS・音声処理中にBLEを休止する（下記）。

**受信→転送の試験中はリセットしない。** UARTを採取する際もDTR/RTSをfalseにする。再起動前の保存データはcold扱いとなり、検証・復元されても広告・転送しない。同じIDの再受信でも復活しない。

## Androidとの実機確認

Androidは [開発ガイド](../android/DEVELOPMENT.md) に従ってビルドする。`demo` と `peer` は別の鍵・保存領域を持つため、Android 1台でも別の送信元・受信先として使える。

1. オフライン中継だけを確認する場合、ESP32は通常版・診断版を使い、gateway版のWi-Fi搬出が動いていないことを確認する（Bでは止まらない）。両アプリの**オンライン搬出を停止**し、受信側の中継も停止する。投稿前に保存済み投稿と公開設定を確認する。AndroidのPLC登録は搬出OFFでも動作し得る。
2. 送信側で一意なテスト本文を投稿し、「中継開始」。Bluetooth・付近のデバイス権限を許可する。
3. ESP32ログの `mesh HELLO accepted node=…` と `saved <ID> hop …` を確認する。
4. 送信側を「中継停止」。ESP32の電源は切らずに受信側を「中継開始」。
5. ESP32の `sent <同じID> hop …`、受信側の `Exchange` ログの `accepted <同じID>`、画面の本文・hopを照合する。
6. Android受信ログの送信元nodeがESP32の起動時nodeと一致することを確認し、他の近隣ノードからの配送と区別する。本文一致だけでは同一イベントの証明にならない。

```powershell
adb logcat -d -v brief -s Exchange:I GattLink:I '*:S'
```

同一Android上の2アプリによる試験を、独立した3台の無線機器による到達距離・移動の試験とは扱わない。

## 保存・受信停止時の確認

- キャッシュは最大16件。退避したIDは `/mesh-seen` に保存し、再要求を抑止する。退避履歴1,024 IDとキャッシュが両方満杯になると新規取得を停止するが、保持中の投稿の転送は続ける。履歴形式・失敗時の動作は [受信済み履歴](test_bundle/RELAY-SEEN-HISTORY.md) を参照。
- 保存・読戻し照合後にだけACKを送る。ACKは永久保持・最終配送・内容の真実性やDID本人認証の保証ではない。
- 通常版には信頼できる時計と電源断中の経過時間がないため、起動中は単調時計とBundle Ageで期限を制限する。gatewayのSNTPも認証済み時計ではなく、ingressには明示的な時計信頼と同期鮮度の確認が別途必要。再起動後のcold制約を解除してメッシュ配送期限を延ばさない。gatewayのオンライン搬出はcold投稿も対象とする。
- マウント・保存失敗時に、診断用ファイルや履歴を安易に削除して復旧しない。まずバックアップとログを確保する。

## 日本語読み上げSDKの組み込み

AquesTalk ESP32 Small辞書版2.4.4をキーなし評価モードで使う。**ナ行・マ行が「ヌ」になるため、正確な防災放送や製品運用には使わない。** 継続利用・製品開発・配布はSDK同梱条件と [公式条件](https://www.a-quest.com/download.html) を確認する。

SDK・辞書・生成設定・バックアップは `private/`、リンク済みファームウェアは `.pio/` に置く。**SDKの全部・一部や、それを含む評価ファームウェアをGit・Release等で公開しない。**

`setup_tts.py` とビルドが成功した後、対応する16 MiB実機で初回・辞書変更時のみ実行する。

```powershell
uv run python provision_tts.py COM3
uv run pio run --target upload --upload-port COM3
uv run python smoke_test.py COM3 --mesh --speech
```

`provision_tts.py` はフラッシュ容量と既存パーティション表を照合し、表・メッセージ領域・追加先領域を `private/backups/<UTC時刻>/` に退避してから、`0x400000` の辞書領域へ書き込み・読戻し検証する。想定外の表では停止するので、強制移行しない。**`uploadfs` は不要。** 起動時の辞書SHA-256検査に失敗すると音声だけを無効化する。

新規投稿を送信する相手がいる場合、受信IDと音声生成・再生処理の完了IDを照合できる。

```powershell
uv run python smoke_test.py COM3 --mesh --speech --received-speech --log .pio/tts-evaluation-smoke.log
```

`TTS done` は実際の聴取成功を保証しない。読み上げは新着優先・待機は最新1件で、全投稿の読み上げを保証しない。60秒制限とミュートはSDK呼出し間の協調的制御であり、SDKが戻らない場合の強制停止や即時無音化ではない。機能追加時はビルド出力でアプリ領域の残量も確認する。

## 診断・互換性変更

通常の `uv run pio run` は `m5stack-basic` のみをビルドし、peer制限・固定node・制御バイト列ログは無効。

`uv run pio run -e m5stack-basic-diagnostic` は隔離試験専用。固定nodeは `platformio.ini` の `MESH_TEST_NODE_ID` を参照する。制御フレームのhexログを出すため一般配布・通常運用には使わない。

診断版ではUART（115200 baud、末尾改行）へ `peers` に続けて現在のnodeを空白区切りで送ると、HELLO後の許可対象を制限できる。最大8件の小文字16桁hexで、除外peerは切断する。`peers *` で無制限へ戻す。設定は揮発性で、認証ではない。Androidのnodeはプロセス再起動で変わるため過去のIDを再利用しない。

wire形式の変更時は [MNDT](../../protocol/mndt.md) とAndroidの `Wire.kt`・`GattLink.kt`・`Exchange.kt` を照合する。現行§9形式と新§3形式を同じ接続で混在させず、移行は協調して行う。仕様との差分・統合条件は [監査資料](AUDIT-2026-10-18.md) を参照。

## Optional Wi-Fi Internet gateway

追加実装の主な変更箇所:

| ファイル | 責務 |
| --- | --- |
| `src/MeshGateway.cpp` | 任意Wi-Fi・TLS Nostr搬出・PLC代理登録・on-demand verified ingress、単一ネットワーク/音声worker（combined版） |
| `src/MeshIngress.cpp`, `include/IngressCodec.h` | wire-v1 root→leaf・ECDSA/binding/commit・fresh snapshot/high-water・strict JSON/CBOR |
| `include/GatewayProtocol.h`, `include/GatewayWire.h` | 有界HTTP/WebSocket・OK判定・PLC DAG-CBOR/DID導出 |

BLEコールバックは有界キューへのコピーを行い、通常のBLE受信の暗号検証・ファイル操作・表示はメインループで処理する。gatewayの取得・認可検証はworker、Relay/LittleFS/UIと最終保存はメインループが所有する。

The **default `m5stack-basic` and diagnostic profiles remain offline**, even if a local gateway config exists. The gateway is public store-and-forward, not a phone tether, a new author, a PDS, or a PLC authority. It never generates keys or re-signs/edits posts. Protocol boundaries: [0001 §1.1.1](../../protocol/0001-repository-model.md), [0007](../../protocol/0007-nostr-relay-profile.md), [0012](../../protocol/0012-bindings.md), and provisional Android control kind 8173 in [mndt §9.5](../../protocol/mndt.md).

### Local configuration (explicit opt-in to public upload)

From `apps/esp32`, create an ignored header; **never put credentials in `platformio.ini`, command-line flags, tracked source, screenshots or logs**:

```powershell
New-Item -ItemType Directory -Force private/gateway
Copy-Item include/GatewayConfig.example.h private/gateway/config.h
```

Edit `private/gateway/config.h` locally:

- `MESH_GATEWAY_WIFI_SSID`, `MESH_GATEWAY_WIFI_PASSWORD`: a 2.4 GHz station network; SSID <=32 bytes, WPA passphrase <=63 bytes (empty only for an intentionally open network). No captive-portal or enterprise provisioning.
- `MESH_GATEWAY_RELAY_HOST`, `..._PORT`, `..._PATH`: one explicitly trusted DNS host, TLS port and WebSocket request path. Configure the actual physical PDS's **Nostr WebSocket** endpoint if atproto reconstruction is wanted; the logical PDS HTTPS URL alone is not a relay endpoint. No relay discovery, redirects, or fallback to plaintext.
- `MESH_GATEWAY_RELAY_CA`: PEM root CA(s) for that relay, obtained and verified out of band. Certificate-chain, validity and DNS-hostname verification use ESP32 `WiFiClientSecure`. A blank/invalid CA never enables insecure TLS.
- `MESH_GATEWAY_PLC_URL`: optional Directory HTTPS base URL, without trailing slash, for example `https://plc.directory`. DNS host and port **443** only; a base path is allowed. `MESH_GATEWAY_PLC_CA` is its PEM root CA(s). Leave both empty to disable PLC proxy registration, **but genesis events then remain pending/pinned**. For newly created offline phone identities, configure this or have another online node register their original genesis; relay upload alone cannot register a repository.
- `MESH_GATEWAY_NTP_SERVER`: operator-chosen SNTP server; default `pool.ntp.org`. TLS waits for wall time >=2026-10-18. Clock synchronization is asynchronous. **SNTP is not authenticated**; a hostile network can disrupt the clock/availability, and there is no hardware-backed trusted-time claim. TLS never falls back to skipping certificate verification.

`private/` is already ignored by the repository. Credentials are compiled into firmware, **not encrypted at rest**: protect the header, build artifacts, flash backups and physical device. There is no BLE/HTTP provisioning endpoint. No event can choose the upload destination or CA. Enabling this profile consents to uploading **all retained supported public events**, including cold events from before the current boot. Do not use it for private information. The relay receives public signed content/metadata and the gateway's network address; the Directory receives the public signed genesis.

### Build/flash profile and migration

```powershell
uv run pio run -e m5stack-basic-gateway
# After inspecting configuration and backing up existing flash/data:
uv run pio run -e m5stack-basic-gateway --target upload --upload-port COM3
```

**Do not run `uploadfs` or erase flash to enable networking.** Follow the backup procedure above first. `partitions-gateway.csv` merges the old app0/app1 area into one 0x280000-byte app at 0x10000. NVS, LittleFS (`0x290000`), coredump and dictionary (`0x400000`) remain at their old offsets/sizes. This removes the spare OTA slot; no OTA updater is provided. Upload the matching firmware **and partition table** together using PlatformIO; do not flash a gateway image into the smaller default app slot. Downgrading likewise requires the normal profile's matching partition table and firmware. Back up the partition table/whole flash too if recovery of a previous app/boot selection matters. The later approved combined-profile migration, double-read full-flash backup and upload/hash verification are recorded in [GATEWAY-HANDOFF.md](GATEWAY-HANDOFF.md). Power-loss during migration and downgrade/rollback remain untested.

The original `m5stack-basic-gateway` profile deliberately **does not initialize TTS**, reserving its working memory/task stack for Wi-Fi/TLS. The separate opt-in `m5stack-basic-gateway-tts` profile below enables evaluated speech with serialized resource ownership. BLE, buttons, receipt verification, custody, screen preview and heartbeat remain. A/C cannot play audio in this profile; **A now requests the optional verified download described below**. **B disables BLE only, not Internet upload/download**; it is not a privacy/offline switch. To stop Internet export, remove the local config and rebuild/flash the gateway profile (which keeps pending entries pinned but starts no networking), or power off. Flashing the normal offline profile restores the original bounded, evictable-cache behavior. A missing header, invalid config or worker failure never silently unpins gateway data.

For compilation without credentials, `uv run pio run -e m5stack-basic-gateway-check` explicitly uses `GatewayConfig.check.h`: non-routable `.invalid` destinations and an intentionally invalid CA, not deployable credentials. Nonempty test values ensure the full TLS/upload/PLC/ingress path survives compiler/linker dead-code elimination. **Never deploy this profile.** The production gateway profile needs the local header; the default example's empty settings fail closed.

### Optional combined gateway + evaluated TTS on Basic

Recorded approved COM3 deployment results (2026-10-18 UTC) and parent commands: [`GATEWAY-HANDOFF.md`](GATEWAY-HANDOFF.md). Keep the running device powered; reboot restores cached events as cold.

```powershell
.venv/Scripts/pio.exe run -e m5stack-basic-gateway-tts
# Only after full backup and explicit local Wi-Fi / ingress provisioning:
.venv/Scripts/pio.exe run -e m5stack-basic-gateway-tts --target upload --upload-port COM3
```

This uses **the same gateway partition map**; never run `uploadfs`, format, or erase. `m5stack-basic-gateway-tts-check` compiles the full path with synthetic `.invalid` configuration, and must not be deployed. Default/offline and diagnostic profiles retain their existing speech behavior.

The Basic has no usable PSRAM in this deployment. **Concurrent BLE exchange + TLS + speech is not supported.** Measured failures and the final device results are recorded in `VERIFICATION.md`:

- One 16 KiB speech/network worker replaces two concurrent worker stacks. The loop stack is 12 KiB; no SDK work buffer is retained between utterances. Dictionary mapping and its boot SHA-256 check remain unchanged.
- Before network work or speech, the main loop stops advertising, requests a physical disconnect, waits for the final callback, then deinitializes NimBLE and frees its 32-entry event queue. Pending custody is never erased. Reinitialization uses the **same boot node ID**, preserves B's desired enable state, and leaves a five-second BLE acquisition window after work. Phones must reconnect/retry unacknowledged transfers. C still cancels/mutes speech cooperatively.
- Speech cannot allocate SDK resources until the main loop grants permission after BLE is quiescent. Save/readback and trust logging complete before this grant. The worker releases SDK buffers before BLE resumes. A queued speech job also blocks new TLS dispatch. This intentionally delays new BLE receipts during the bounded utterance; the offline profile's concurrent latest-wins behavior is unchanged.
- Combined network admission requires >=80,000 free bytes and an >=18,000-byte contiguous block **after BLE shutdown**. TLS has separate 16 KiB record buffers plus overhead, not one 32 KiB allocation. Existing per-job worst-case JSON/free-heap checks remain. A refused job retains data and backs off rather than dropping it.
- On ingress, reserve the bounded incoming frame and one raw winning frame before TLS. For each new frame, close TLS before strict parsing/crypto. Reissue the same finite query and skip byte-identical frames by SHA-256, up to **nine connections (eight unique events plus matching EOSE), 24 frames/connection, 120 seconds/pass** plus bounded SDK/connect completion. A changing/hostile window that exceeds eight events fails closed; partial results are not admitted. Keep only preallocated raw winner/scalar ordering between connections; after EOSE reverify that original winner against the accumulated trust state. Priority failure still uses the separate ordinary REQ pass. Parsing/verification keep the existing 70/65 KiB free-heap guards. No certificate, signature, clock, expiry, priority, or CRL check is skipped.
- If all 16 slots are pending upload, an explicit download stays queued until at least one positive upload confirmation permits ordinary replacement. No pending original is evicted to admit BSAF.

**Bounded local UART** is available in normal gateway builds, without diagnostic compiler flags: `status`, `download`, `audio on`, `audio off`, `peers *`, or `peers <up to 8 node IDs>`, each newline-terminated. `download` shares A's coalesced request path; `audio on/off` is idempotent, not a toggle. Peer filtering is local test isolation, **not authentication**. Parsing consumes at most 64 bytes/loop and 160 bytes/line; overflow rejects the entire line until newline. No arbitrary text synthesis, credential readback, filesystem erase, or trust-reset command exists.

After releasing any other COM3 owner, use the helper (does not request reset):

```powershell
.venv/Scripts/python.exe scripts/gateway_serial.py COM3 --command status --seconds 15 --log .pio/gateway-status.log
.venv/Scripts/python.exe scripts/gateway_serial.py COM3 --audio-on --command download --seconds 300 --log .pio/gateway-download.log --require-ingress-speech
# Only if explicit peer isolation is needed; restore unrestricted operation afterward:
.venv/Scripts/python.exe scripts/gateway_serial.py COM3 --peers --command status --seconds 10 --log .pio/gateway-peers-restored.log
```

Capture is bounded to 1..300 seconds / 8 MiB. `--require-ingress-speech` fails on observed crash/reboot or absent matching **full persisted event ID -> TTS start -> nonzero PCM finish**. It expects a *new* eligible event; duplicates correctly do not speak. `TTS done` proves synthesis/speaker-API completion, not audible quality or commercial SDK licensing.

`scripts/backup_flash.py` reads all 16 MiB twice while remaining in the bootloader between reads, compares SHA-256, and records the actual partition table/region hashes under ignored `private/backups/<UTC>/`. It performs no erase/write operation. Interrupted verification can resume with `--resume <existing directory>`; a mismatch retains both reads and blocks success. Bootloader entry/reset interrupts the running app: do not use backup/flash tools during the parent's live phone test.

The approved lab provisioning helper `scripts/provision_gateway.py` only captures Windows profile keys without printing them, never switches host Wi-Fi, and derives public PEM roots from system-verified TLS chains. It refuses to overwrite an existing private gateway config. The operator-provisioned public root export remains local in ignored `include/IngressTrust.generated.h`; it contains no signing secrets and does not itself enable ingress. Treat private config, linked firmware and full flash backups as sensitive.

### Delivery, bounds and failure behavior

- Main loop alone owns Relay/LittleFS/UI. A one-item pointer queue transfers an immutable <=24,000-byte copy to one 16 KiB-stack network task. Only the main loop processes results/unpins IDs. DNS, TLS, HTTP and WebSocket waits never run in BLE callbacks or the main-loop upload poll. Existing BLE crypto/flash work is still synchronous as before; this is not a measured real-time latency guarantee.
- Original kind **1/5** fields are reconstructed reversibly from signed CBOR (typed version/Base64 tags included), ID and BIP340 signature are verified again, then the original event is sent as NIP-01 `["EVENT", event]`. No expiry/hop rewriting, author substitution, new tags, or re-signing. Mesh-expired/hop-exhausted/cold copies may still be exported online, without reviving mesh eligibility. Unknown kinds are not exported.
- Only a well-typed `["OK", exactFullId, true, message]` from that TLS connection counts as relay acceptance. A successful socket write, unrelated OK, false OK (even `duplicate:`), NOTICE, AUTH challenge, timeout, disconnect, malformed/oversized/fragmented frame or lost result does not. Pending originals stay durable for retry. One relay is supported; no NIP-42 authentication or fragmented messages. Live subscriptions are not used; optional finite, verified ingress is described below.
- Provisional **8173 is never posted to Nostr**. The signed event's original content is submitted unchanged as a PLC operation. The gateway derives `did:plc` from SHA-256 of canonical signed DAG-CBOR, GETs `/did/log`, POSTs only after a TLS-authenticated **404**, then GETs again. Only a matching first signed genesis in a successful log response is historical registration confirmation. Existing/malformed/mismatched logs are never overwritten. The configured Directory validates PLC ECDSA/signature/operation rules; ESP32 does not locally audit PLC signatures or the full update history. No current-key, PDS-endpoint or tombstone status is blessed by this confirmation.
- **Relay acceptance != PLC registration != PDS reconstruction/publication != AppView display.** Inspect PLC and the configured PDS/AppView separately. Posts may reach the relay before genesis; the server must retry deferred reconstruction (as the current Android online path expects). No publication-completion monitor is implemented.
- A gateway protects pending **1/5/8173** entries from eviction, even after mesh expiry/reboot. At 16 unconfirmed entries the node stops WANT acquisition rather than deleting them; source phones retain responsibility for posts not ACKed here. Positive delivery only unpins an entry for the normal cache replacement policy, never immediately deletes it. Unknown kinds keep the previous cache policy. `gateway: <id-prefix> accepted (not PDS publication)` is a local observation, not a receipt returned to phones. A 30-second serial status reports Wi-Fi association, clock readiness, heap, pending count and worker busy state without credentials or event bodies.
- Delivery checkpoints are deliberately **RAM-only**: reboot re-verifies and retries every retained supported event, including previously accepted ones. This is at-least-once, not exactly-once. Losing an OK/checkpoint can cause duplicates, never a false completion. Once a positively accepted event is later evicted, relay-side future retention is not guaranteed. Physical flash corruption/erasure and capacity refusal cannot guarantee eventual delivery; this is not permanent archival storage.
- Round-robin scheduling prevents a bad author/relay response from permanently starving other pending IDs. Each ID backs off 5/10/20/40/80/160/300 seconds, capped at 5 minutes, without automatic give-up; Wi-Fi reconnect attempts are spaced 30 seconds apart. One job/connection at a time. Bounded queues, <=16 retry records, no unbounded outbox or network-supplied URL.
- Local limits: HTTP headers <=4 KiB / <=64 lines / <=512-byte lines; PLC operation <=8 KiB, log body <=16 KiB / <=128 operations / depth 8 / <=48 KiB shared JSON allocator. HTTP Content-Length, chunked and close-delimited bodies are bounded; malformed lengths/conflicting framing fail closed. Upload-ACK WebSocket inbound frames <=1 KiB / <=32 frames / no fragments (the separate ingress limits are below), mandatory upgrade accept challenge, masked outbound frames, ping/pong. TLS/connect timeout 5 seconds, response budget 15 seconds per PLC request or 20 seconds per relay exchange; underlying DNS/SDK scheduling can add latency in the worker, not BLE poll.
- Export JSON capture is capped at 64 KiB plus fixed fields. An otherwise valid bundle whose escaped JSON exceeds this stays pending, unchanged; it is **not** truncated. A 110,000-byte free-heap admission guard plus conservative per-job worst-case escaping/contiguous-allocation checks defer work under memory pressure. This 110,000-byte guard describes the original gateway profile; the combined profile uses the separate admission rules above. Its measured, time-sliced TLS/heap/stack results are in [GATEWAY-HANDOFF.md](GATEWAY-HANDOFF.md); adversarial peaks and simultaneous BLE/TLS operation are not validated. Large events, large PLC logs, permanently rejected data, invalid config, missing CA, no clock sync or unavailable memory can leave the bounded cache pinned indefinitely. Provision/correct the endpoint or recover originals before any manual cleanup; no automatic data-dropping escape hatch is supplied.

### Tests and unverified hardware behavior

`test_bundle/test_gateway.py` builds the real codec/protocol/transport helpers with synthetic sockets, ASan/UBSan and independent Python CBOR/hash/signing oracles. It checks exact exported fields for all eight actual Android fixtures, signed kind 1/5/8173, tampering/JSON expansion failure, PLC DID derivation, correct/wrong/negative OKs, framing/body/deadline/size limits and masking. `test_relay.py` also checks pending cache backpressure, cold/expired protection, failed reads, successful unpinning without deletion, reboot retries and unchanged genesis custody. These tests never publish events or submit a real PLC operation.

Example host invocation from the repository root with existing mbedTLS 2.x and ArduinoJson headers (the retained Windows/WSL dependency paths are documented in [`test_bundle/README.md`](test_bundle/README.md)):

```sh
python3 apps/esp32/test_bundle/test_gateway.py
python3 apps/esp32/test_bundle/test_relay.py
python3 apps/esp32/test_bundle/test_main_controls.py --sanitize --diagnostic
```

The original host-only gateway work did not validate hardware. See `VERIFICATION.md` for the later approved combined-profile Wi-Fi/relay/PLC/ingress/speech observations. Simultaneous radio-data coexistence, hardware CA-rejection tests, flash power-cut, long endurance and the parent's fresh two-phone offline → ESP32 → PDS/AppView test remain separate validation tasks. Test with disposable **public** test identities and operator-approved destinations before deployment. Keep phone networking off during that test, independently retrieve and verify the identical full event ID/signature from the relay, check PLC genesis/current state separately, then check the PDS record/head and AppView.

### Optional wire-v1 Nostr → mesh ingress (A button)

Implemented against [`protocol/0020-wire-v1.md`](../../protocol/0020-wire-v1.md), not the earlier deferred signer-list proposal. **Disabled by default; no production root or real JMA Bot DID is shipped.** No relay URL, NIP-11 capability, signer allowlist, `source:jma`, organization display name or priority hint is a trust anchor. Existing BLE receipt/forwarding and outgoing upload rules remain unchanged; these stricter checks apply to **new Internet→mesh admission**, not every ordinary mesh post.

#### Public root integration contract (parent/server worker)

The parent/root operator can later generate **`include/IngressTrust.generated.h`**, following `include/IngressTrust.example.h`. This is public trust/configuration material, safe to review/version separately from Wi-Fi credentials; **never include root/importer private keys**. `include/IngressTrust.h` loads it if present, otherwise supplies empty/default-disabled values.

| Macro | Public value / rule |
|---|---|
| `MESH_INGRESS_ROOT_PUBKEY` | One SEC1 compressed secp256k1 root, 66 lowercase hex characters. Root ID is SHA-256 of its decoded 33 bytes. |
| `MESH_INGRESS_SCOPE_CBOR_HEX` | Lowercase hex of canonical `C({orgIds:[...],bsaf:[{botDid,types:[...]}]})`, exactly wire-v1 Scope. |
| `MESH_INGRESS_SNAPSHOT_URL` | Absolute HTTPS DNS URL, port 443, no credentials/query/fragment/redirects. The endpoint must return `application/cbor` and canonical `C({body,sig})`. |
| `MESH_INGRESS_SNAPSHOT_CA` | Public PEM X.509 CA(s) for HTTPS. **Different purpose from the secp256k1 signing root.** TLS identity does not replace the snapshot signature. |
| `MESH_INGRESS_MIN_SEQUENCE` | Integer 1..9007199254740991; provisioned snapshot sequence floor. Never automatically lowered on errors/root changes. |
| `MESH_INGRESS_SELECTED_CBOR_HEX` | Local selection: hex of canonical `C([{botDid,types:[...]}])`. Use the **actual approved JMA Bot DID and exact original `type:` values**; no source-name/handle inference or wildcard expansion. This field can instead be defined in the private local header; do not define it twice. |

All lists must have the ordering/distinctness wire-v1 requires. Selection must be a subset of the configured root Scope. Root/selection fields are canonical CBOR hex, **not JSON or Base64**. The sample root/Bot in the test fixture are public synthetic test identities, not JMA or production authority.

Local operators must **also** add both explicit opt-ins to ignored `private/gateway/config.h`:

```cpp
#define MESH_INGRESS_ENABLED 1
#define MESH_INGRESS_TRUST_CLOCK 1
```

The public generated root file should not set these local decisions. With missing/invalid public fields, no local selection, either opt-in absent, unknown clock, or unreadable checkpoint, ingress does not run. Root configuration alone does not enable it. The full-path `m5stack-basic-gateway-check` build uses only the synthetic public fixture root, `.invalid` endpoints and invalid TLS CA; it is never deployable. No additional PlatformIO library or partition change was added for ingress. **The original gateway profile remains TTS-disabled; the optional combined profile above time-slices resources instead.**

#### Clock and authorization boundary

`MESH_INGRESS_TRUST_CLOCK=1` explicitly accepts the device's SNTP-derived wall clock as a **local deployment assumption**. It does not authenticate SNTP. Ingress additionally waits for a successful SNTP sync callback in this boot and requires that callback to be at most one monotonic hour old. SNTP spoofing, time jumps and lack of a secure/rollback-resistant RTC remain limitations; TLS and signed timestamps do not independently prove the clock. Default false is the safe setting when that assumption cannot be made. No HTTP Date or event timestamp becomes a trusted clock. The ordinary outgoing gateway's pre-existing TLS time guard is unchanged.

For each candidate, the worker independently verifies:

1. Strict seven-field kind-1 Nostr event, ID/BIP340 signature, required 0012 tags/base version/path/created-at/deadline, and canonical extension bytes. Original strings, tag/extension order, signatures and typed Base64 reversals are preserved.
2. Exactly one identity and one BSAF original-reference extension. Original URI must identify an exact `did:plc` Bot and `app.bsky.feed.post` record key; CID must be canonical CIDv1 dag-cbor/SHA-256. All six BSAF core tags are unique/nonempty, type comes from its unique `type:` tag, and Bot/type must match the explicit local selection.
3. **One direct root→importer leaf certificate only.** Intermediates/longer paths are unsupported, never promoted into roots. Exact wire fields, root ID/key, valid compressed points, compact low-S ECDSA signature, importer role, no delegation, exact single organization, root Scope containment, period <=366 days, receive-time and event-time validity are required. Unknown/self-signed roots and scope escalation fail closed.
4. Exact legacy Android binding declaration/consent, padded standard Base64 ECDSA signature **and** BIP340 binding signature. Certificate DID/atproto key, binding DID/keys, event Nostr key and commit DID must all match. The canonical v3 commit, CID link and compact low-S ECDSA commit signature are checked. This is **not current PLC authentication, original Bot repository signature verification, or MST inclusion proof**.
5. A fresh root-signed complete-CRL snapshot at **receive time**, not event creation time. Certificate/deadline/snapshot eligibility is checked again when the worker completes, before save, and **after persistence before advertisement**. Expiry during the write leaves the complete bundle cold, with no advertisement/accepted callback.

Certificate identity describes the **importer**, not a signature by JMA/the original Bot. UI text says `Importer checked at ingress`; serial observations include root/leaf IDs, Bot/type, organization, snapshot sequence/issuedAt/validUntil/checkedAt and independent `authorSignature`, `binding`, `chainScope`, `certificateTime`, `revocation=fresh-as-of` statuses. Failed/unperformed checks are not treated as success. Ordinary mesh receipt or reboot does not restore this observation as a current official-authentication badge.

#### Snapshot high-water / persistence

`/ingress-state` contains the latest verified signed snapshot and bounded issuer-serial observations, tied to the configured root ID, canonical encoded and SHA-256 checksummed for corruption detection. It is written to `/ingress-tmp`, flushed/closed, atomically renamed and read back **before any newly verified event is saved/advertised**. Filesystem work stays on the main loop; the network worker never opens LittleFS. A failed checkpoint write latches new ingress off for this boot. A malformed/truncated/wrong-root checkpoint is not discarded or treated as a first install.

- Reject sequence below `max(configuredMin, savedSequence)`; same sequence requires identical body hash, even when re-signed. Higher sequences require nondecreasing issuedAt and a superset of both revocation lists. Expired snapshots do not erase the high-water/revocations. A valid root-revoking snapshot is persisted without admitting events; that root cannot automatically revive itself.
- Same root issuer+serial with a different certificate body becomes a sticky conflict. Serial observations survive reboot and are not silently evicted. Valid root-signed certificate observations may consume registry capacity even if later binding/scope/time/admission checks fail.
- A successful HTTP response containing an invalid/rollback/equivocating snapshot rejects that fetch. On transport/HTTP failure, the last **still-fresh, signature-verified** cached snapshot may be used only until its original validUntil. No stale-cache grace period, truncated CRL, unsigned response, or timestamp repair.
- Checksum is corruption detection, not protection against an attacker with flash access. Restoring an old flash backup can restore old high-water state; initial state loss retains the protocol's minSequence/at-most-one-hour replay-window limitation. Do not delete/reset state to bypass revocation, serial limits or migration errors. Root replacement requires an explicit out-of-band migration/recovery plan; a new root is not automatically trusted because it appears in an event.

#### On-request download, priority and mesh custody

In a configured gateway build, **press A** (or call main-loop `gatewayRequestIngress()`) to coalesce one download request. It waits for the current network job, Wi-Fi, clock readiness and RAM. There is **no periodic background import or live subscription**.

The worker requests `MOSIMO-REQ` with `order:"priority-v1"`, kind `[1]`, `#t:["mosimo.oriom.dev"]`, and **limit 8**. It optimistically tries the extension without trusting NIP-11. Unsupported/error/CLOSED/no EOSE/timeout causes a new TLS connection and ordinary `REQ` using the **distinct** subscription ID `esp-fallback` (`esp-priority` for MOSIMO). Standard REQ has no custom ordering field. Partial/incomplete results are not reported as an empty success. Both paths require matched finite EVENT/EOSE responses and close the subscription/connection.

Among at most eight returned **new, independently authorized** events, choose highest local effective priority, then newest created_at, then lexicographically lowest full ID. Valid single priority hint 0..3 is honored only after authorization; missing/duplicate/schema-invalid hints use level 1, and failed authorization never grants a level above 1 or any mesh admission. Relay-provided order, BSAF value and organization name cannot raise privilege. This is **highest within the bounded returned window, not a guarantee of the relay's/global highest event**, especially with fallback or different relay trust policy.

Retained full IDs are skipped. The existing <=1024-entry 8-byte seen-prefix index is copied as an additional conservative acquisition filter (<=8 KiB); a rare prefix collision can suppress a new candidate, but can never authorize/ACK one. Final custody dedup still checks full stored/journal event IDs. This prevents repeated already-evicted top results from dominating every request without copying an unbounded/full-ID history into TLS RAM.

The verified original event becomes the existing canonical 7-element CBOR payload and ordinary CRC32C BPv7 bundle (anonymous source, hop 0, age 0, lifetime <=24h and the remaining signed delivery deadline). The **normal verified save/readback path** runs before inventory dirtying/display. Full caches of pending uploads are not evicted to admit an urgent import, and outstanding BLE WANT reservations keep their capacity ahead of a new download. Snapshot/serial checkpoint advancement does not claim bundle custody. Duplicate, invalid, expired, persistence-failed or quota-refused events are never advertised as received. An admitted public event participates in the existing normal upload/retention policy; it may be redundantly uploaded after fetch/reboot and still requires positive OK for outgoing completion.

#### Smaller ESP32 profile limits / remaining validation

- One root; direct leaf only; no AIA/OCSP/delta CRL/root discovery. Root Scope/selection <=8 orgs (Scope only), <=8 Bots, <=32 explicit types/Bot, <=64 total pairs. This supports JMA's existing 5 types plus Kikikuru's 23 (`did:plc:kxwz5cz7o6g4jlmxh6doyfsm`) when explicitly provisioned; no wildcard, automatic Bot-definition expansion or root widening. Shipped empty/disabled defaults are unchanged. One presented chain, one identity/bsafV1; optional BSAF botDefinition metadata is currently unsupported. No arbitrary original-record/network fetch.
- Nostr JSON frame <=6,144 bytes; content <=2,048; <=64 tags, <=8 cells/tag and <=16 extensions, additionally limited by a **128-node / depth-12 / 12-KiB decoded-string budget per strict JSON/CBOR tree**. Snapshot <=4,096 CBOR bytes and <=32 total revocations; identity <=4,096 CBOR bytes; canonical document/payload <=8,192 bytes. All maxima need not fit simultaneously; unsupported/resource-limit is rejection, never truncation or weakened verification.
- BSAF original tags <=8 ×640 bytes, langs <=3. Language tags support the conservative BCP-47 subset `language[-script][-region]` (primary 2..8 letters; optional 4-letter script; optional 2-letter/3-digit region). Other valid BCP-47 forms are unsupported, not rewritten. Existing record/source/deadline timestamps accept UTC Z with up to 9 fractional digits; certificate/snapshot timestamps are exact second-precision UTC. Leap seconds/24:00 are conservatively unsupported for ingress.
- Persistent issuer-serial registry <=16; checkpoint <=8 KiB plus one <=8 KiB temp. No automatic registry/CRL forgetting. Capacity exhaustion requires deliberate operator migration, not silently reopening revoked/reused identities.
- One job, existing 16 KiB worker stack. In the original non-TTS gateway profile, ingress starts only with >=130,000 free heap bytes; the combined profile instead uses the BLE-shutdown/admission/reconnect rules above. In both profiles, frame parsing and verification have additional free/contiguous-heap guards (70/65 KiB). Intermediate trees are released in phases. Original non-TTS profile: <=24 inbound frames/pass, <=8 events/pass, no fragmentation/binary data; priority phase budget 8 seconds, fallback 15 seconds, plus bounded TLS/HTTP/SDK waits in the **worker**, not BLE callbacks/main-loop upload poll. The combined profile instead uses up to nine connections, <=24 frames/connection and 120 seconds/pass as described above. Verification, normal final bundle validation, flash and UI work are bounded, not a hard real-time BLE latency guarantee. Combined-profile time-sliced TLS/heap/stack measurements are recorded in [GATEWAY-HANDOFF.md](GATEWAY-HANDOFF.md); simultaneous BLE/Wi-Fi data-plane coexistence is not supported.

`test_bundle/wire-v1-combined-server-fixture.json` additionally contains a real server-issued combined 28-type certificate and one successive signed event per selected type, plus an unselected-extra negative. Reproduce with `node test_bundle/generate_server_fixture.mjs --write --combined` / `--check --combined`; this isolated test-only generator pins the 23 type options from the primary [Kikikuru definition](https://raw.githubusercontent.com/osprey74/bsaf-kikikuru-bot/main/bot-definition.json), uses no operational private keys and never changes shipped roots. Android's real verifier consumes this same JSON as a test-only resource. Both real receivers accept all 28 and reject unselected/expanded authority. Measured canonical Scope **579 B**, selection **544 B**, certificate **1,285 B**, identity **1,852 B**; maximum event JSON **4,366 B**, mesh payload **3,307 B**. Existing Scope/selection CBOR <=8,192 B each, certificate/identity <=4,096 B, JSON <=6,144 B and 128-node trees remain unchanged. Scope/selection tests accept 32/Bot and 64 total, reject 33/Bot and 65 total. A synthetic 64-type full identity exceeds the separate 128-node tree budget and remains rejected: advertised maxima need not fit simultaneously. No hardware/TLS peak-memory measurement is implied.

`test_bundle/test_ingress.py` uses independent Python canonical CBOR, low-S ECDSA and BIP340 signers with public TEST ONLY secrets (root=7, atproto=5, Nostr=3), then executes the real C++ verifier with ASan/UBSan. [`test_bundle/wire-v1-fixture.json`](test_bundle/wire-v1-fixture.json) publishes canonical certificate/binding/snapshot bytes/hashes, cert/root IDs, signed event and exact expected mesh payload for the parent Node/Kotlin implementations to consume. Python/ESP32 agreement is tested. A second frozen public fixture, `test_bundle/wire-v1-node-fixture.json`, was copied unchanged from the Android worker's `bundle-core/src/test/resources/wire-v1.json` (producer `apps/android/wire-v1-fixture.mjs`, using `@ipld/dag-cbor` / `@noble/curves`). The real Node producer's read-only validation and this C++ verifier both pass that independent fixture: canonical certificate/snapshot/record/commit bytes, IDs/hashes, signatures, original event and exact mesh payload agree. Neither primitive fixture is production trust; no claim is made here about running the Android/Kotlin suite or the complete server runtime. **Actual server interop is additionally covered by `test_bundle/wire-v1-server-fixture.json`**, reproducibly created by the real `pds/public-information.mjs:createImporterEvent` with real importer/genesis/binding/certificate/snapshot exports. It contains two successive events whose nonempty second `base` references the first commit; actual PDS `reconstruct` and an independent Python block-hash/MST traversal both verify one-record then two-record roots. Both events pass the real C++ checker separately and sequentially across persisted trust state. See `test_bundle/README.md` for the test-only generator, source provenance, exact Windows/WSL commands and safety limits. This does not turn fixture MST auditing into an on-device inclusion-proof claim. Negative cases include binding/key/DID/signature mismatches, high-S/DER, unknown roots, unsupported chains, scope expansion, time/clock boundaries, stale/future snapshots, rollback/equivocation/revocation removal, sticky serial conflict, unselected Bot/type, and priority without authorization. Relay tests additionally inject checkpoint/write/read/rename failures, expiry during persistence, duplicate/full-cache and persist-before-advertise boundaries. No tests fetch public events, submit operations or flash a device.

From repository root, reusing the exact retained WSL dependencies described in `test_bundle/README.md` (Git Bash requires `MSYS_NO_PATHCONV=1`):

```sh
MSYS_NO_PATHCONV=1 wsl.exe -d Ubuntu -- timeout 180s env PYTHONPATH=/home/mchiba/.cache/mesh-bundle-host/deps/usr/lib/python3/dist-packages python3 apps/esp32/test_bundle/test_ingress.py --mbedtls-include /home/mchiba/.cache/mesh-bundle-host/deps/usr/include --mbedtls-library /home/mchiba/.cache/mesh-bundle-host/deps/usr/lib/x86_64-linux-gnu/libmbedcrypto.a
```

Prior `test_gateway.py`, `test_relay.py`, `test_bundle.py --host ...`, Base64 and main-controls commands remain valid for regression validation. Firmware: `.venv/Scripts/pio.exe run -e m5stack-basic-gateway-check` from `apps/esp32` compiles/links the complete ingress path without real credentials. Normal/gateway/diagnostic profiles must also build. Host/fixture checks alone do not validate live networking or hardware. Later approved combined-profile deployment, live priority download, HTTPS signed snapshot verification, verified save/speech/BLE ACK and independent relay readback are documented in [GATEWAY-HANDOFF.md](GATEWAY-HANDOFF.md) and [VERIFICATION.md](VERIFICATION.md). They do not establish ordinary REQ fallback under live failure, SNTP attack resistance, power-cut/rollback safety, original Bot MST inclusion, phone-side acceptance, or the fresh two-phone offline → PDS/AppView path. Before any new deployment, approve the public root Scope and exact Bot/type selection, run the shared fixture against the server implementation, then validate the intended real path with approved public test data.
