# M5Stack Basic BLEメッシュ中継ノード（実験実装）

Androidの公開・署名済み投稿をBLEで受信し、検証・保存して、後で接続するAndroidへ転送する。任意のgatewayビルドでは、周囲のAndroidがオフラインでもESP32自身のWi-FiでNostrリレー／PLC Directoryへ搬出できる。
uvでPlatformIO CLIを管理する。SDK・コンパイラーは既定のユーザーディレクトリ（Windowsでは`%USERPROFILE%\.platformio`）を使用する。`PLATFORMIO_CORE_DIR`の設定は不要。

## 対応範囲

**現在のAndroidアプリと相互接続するための暫定互換実装**。正本は[`protocol/mndt.md`](../../protocol/mndt.md)。

- 対応するwire形式は同文書**§9のAndroid実験形式**。JSON `hello / inventory / want / ack`、4バイトbig-endian長＋BPv7 Bundle。
- §3の新しいCBOR交換、連番付きGATTフレーム、`BEGIN / DATA / CREDIT / RESULT`には**未対応**。両形式を同じ接続で混在させない。§9のUUIDも採用済み標準値とは扱わない。
- ESP32は**peripheral / GATT server専用**の部分実装。広告で専用フラグを示し、Androidがcentralとして接続する。接続後の転送は双方向。同時接続は1台。
- 2台のこのESP32ファームウェア同士では接続できない。central側、LAN、Nearby、ESP32での投稿作成は未実装。オンライン搬出は下記の任意gatewayプロファイルで対応する。

## 構成

| ファイル | 責務 |
|---|---|
| `src/MeshBle.cpp` | 広告・GATT・有界キュー・フレーム再組み立て・切断時の破棄 |
| `src/MeshRelay.cpp` | 所持情報・取得要求・保存受理応答・LittleFSキャッシュ |
| `src/MeshBundle.cpp` | BPv7/CRC、CBORイベント、Nostr IDとBIP340署名検証、hop/age更新 |
| `src/MeshGateway.cpp` | 任意Wi-Fi・TLS Nostr搬出・PLC代理登録・on-demand verified ingress、単一ネットワークtask |
| `src/MeshIngress.cpp`, `include/IngressCodec.h` | wire-v1 root→leaf・ECDSA/binding/commit・fresh snapshot/high-water・strict JSON/CBOR |
| `include/GatewayProtocol.h`, `include/GatewayWire.h` | 有界HTTP/WebSocket・OK判定・PLC DAG-CBOR/DID導出 |
| `src/MeshSpeech.cpp` | ローカル評価SDK・辞書検証・日本語音声合成・非同期PCM再生 |
| `src/main.cpp` | M5Stack表示・ボタン・メインループ |
| `test_bundle/` | 暗号・異常系・保存・BLE境界のホストテストとKotlin実装由来fixture |

BLEコールバックではデータを有界キューへコピーするだけにし、暗号検証・ファイル操作・表示はメインループで実行する。

## セットアップ・ビルド

Pythonは`.python-version`、Python依存は`uv.lock`、SDKとC++ライブラリーは`platformio.ini`で管理する。以下は`apps/esp32`で実行する。

```powershell
uv sync --locked
uv run python setup_tts.py C:\Users\chiba\Downloads\tmp\aquestalk-esp32_s_0244.zip
uv run pio device list
uv run pio run
```

上のZIPパスは、正規にダウンロードした評価用SDKの保存場所へ置き換える。`setup_tts.py`はローカルZIPを読むだけで、SDKを自動ダウンロードしない。必要なファイルと辞書SHA-256設定をGit対象外の`private/aquestalk/`へ配置する。
初回はPlatformIOのSDK・コンパイラー等のダウンロードにネット接続が必要。
`main.py`はuv生成時のPythonサンプルで、ファームウェアには使用しない。
実機はESP32-D0WDQ6-V3 revision v3.1、フラッシュ16 MiB。`partitions.csv`は従来の先頭4 MiBの全領域を同じ位置・サイズで残し、`0x400000`に2 MiBの生辞書領域を追加する。

## 初回のみ：保存領域の初期化

**`uploadfs`は保存済み投稿を消す。通常のファームウェア更新では実行しない。**
マウント失敗時にも自動フォーマットしない。`LittleFS unavailable; not formatted`の場合は、既存領域をバックアップしてから初期化する。
以下のアドレスは現在の既定パーティション専用。変更時はビルドした`partitions.bin`を確認し、実機の既存パーティションとも照合する。

```powershell
New-Item -ItemType Directory -Force .pio/backups
uv run pio pkg exec --package tool-esptoolpy -- esptool.py --chip esp32 --port COM3 --baud 460800 read_flash 0x290000 0x160000 .pio/backups/pre-littlefs.bin
uv run pio run --target uploadfs --upload-port COM3
```

バックアップが成功したことを確認してから`uploadfs`を行う。同じバックアップ名を再利用して元のバックアップを上書きしない。
`.pio`はGit対象外のため、必要なバックアップは別途安全な場所へコピーする。
初期化ツールが作るLittleFSはファイル名上限32文字。保存先は短い`/mesh/00`〜`/mesh/15`で、完全なイベントIDは検証済みの内容から取得する。

## 書き込み・起動確認

`COM3`は`pio device list`の結果で置き換える。ポートは設定ファイルに固定していない。
書き込みはプログラムとパーティションテーブルを置き換える。既存データが必要なら事前にバックアップする。

```powershell
uv run pio run --target upload --upload-port COM3
uv run python smoke_test.py COM3 --mesh --speech
```

スモークテストは**実機をリセット**し、30秒間、起動1回・稼働ログ2回以上・BLEと評価TTSの初期化を確認する。辞書の初回書込みは下記の読み上げ手順が必要。
再起動は下記のcold保存方針に影響するため、中継中のデータをそのまま別端末へ渡すテストの途中では実行しない。

```powershell
uv run pio device monitor --port COM3
```

ログ監視の終了は`Ctrl+C`。ポートを使うほかのモニターは閉じる。
- **Aボタン**：通常ビルドでは固定の日本語評価テスト文を合成・再生する（録音の再生ではない）。gatewayビルドでは、明示設定したwire-v1 ingressの優先度付き取得要求（下記）。
- **Bボタン**：BLE送受信のオン／オフ。オフ時は広告と接続を停止し、オンで探索可能な状態へ戻す。
- **Cボタン**：音声だけをミュート／解除。現在の発話を中断し、待ち行列を破棄する。受信・保存・転送・本文表示は継続する。表示は`Audio muted`／`Audio enabled`。
音量は96/255。通信・音声の状態は画面最上部の`RX/TX`と`AUDIO`で個別に表示する。設定は起動中のみ有効で、再起動時は通信・読み上げともに有効へ戻る。
画面上部56pxには最新の新規受信メッセージのID接頭辞と本文の先頭40文字を表示する。日本語フォントを使い、UTF-8の文字境界で切り、改行・制御文字は空白へ置換する。長い本文には`...`を付ける。絵文字などフォントにない字形の表示は保証しない。
表示・読み上げの対象は署名検証と保存・読戻し照合に成功した新規の`kind=1`投稿だけ。PLC初期登録（`kind=8173`）・削除等の制御イベントは保存・中継するが、本文表示を置き換えず発話しない。保持中・受信済み履歴にある同一イベントの再接続時の再取得や、起動時の復元では更新しない。本文をシリアルログへは出力しない。
下部の画面ログは自動スクロールし、最新行を表示する。画面から流れた過去のログはシリアルモニターで確認する。

## Androidとの実機確認

Android側は[`apps/android/README.md`](../android/README.md)の手順でビルドする。`demo`と`peer`は別の鍵・保存領域を持つため、Androidが1台でも論理的に別の送信元・受信先として使える。

1. 両アプリの**オンライン搬出を停止**し、受信側の中継も停止する。テスト投稿を公開リレーへ誤送信しないよう、投稿前に状態を確認する。
2. 送信側で一意なテスト本文を投稿し、「中継開始」。Bluetooth・付近のデバイス権限を許可する。
3. ESP32ログの`mesh HELLO accepted node=…`、`saved <ID> hop …`を確認する。これは署名検証とファイル保存後のログ。
4. 送信側を「中継停止」。ESP32の電源は切らずに受信側を「中継開始」。
5. ESP32の`sent <同じID> hop …`、受信側の`Exchange`ログの`accepted <同じID>`と、投稿一覧の本文・hopを照合する。
6. ほかの近隣ノードからの配送と混同しないよう、Androidの受信ログが示す送信元nodeとESP32の起動時nodeが一致することを確認する。本文一致だけでは同一イベントの証明にならない。

```powershell
adb logcat -d -v brief -s Exchange:I GattLink:I '*:S'
```

直近N行だけの取得ではOSの大量ログに埋もれて対象が消えることがある。実機の成功記録は[`VERIFICATION.md`](VERIFICATION.md)を参照。
同一Android上の2アプリによる検証は、3台の無線機器による到達距離や移動のテストではない。

## 保存・期限・安全性

- BLEイベントは1回のメインループで最大4件処理し、連続受信中もボタン操作と定期ログへ戻る。
- Bundleは最大24,000バイト、最大16件・384,000バイト＋一時ファイル1件。ネスト・タグ・制御メッセージ・キュー・セッションの通信量にも上限を設ける。
- 検証済みイベントIDで重複排除し、CRC・Nostr ID・BIP340署名・宛先・hop・Bundle Age・Lifetime・署名付き配送期限の構文を検査する。
- キャッシュから置換する前に完全なイベントIDを別ファイル`/mesh-seen`へ永続化する。別端末への接続・再接続・再起動でも、履歴にあるIDを再要求しない。現在のAndroidが送る古い順inventoryでは末尾の未受信候補を優先する（日時の検証ではない）。更新前に既に失われたIDは復元できず、初回に一度取得する可能性がある。
- 受信済み退避履歴は最大1,024 ID・36,872バイト、RAM索引は最大8 KiB。履歴を自動で忘れないため、履歴と16件キャッシュが両方満杯になると新規取得を停止しログへ通知する。保持中の実データの転送は継続する。履歴だけを持つIDの広告・転送・ACKはしない。保存失敗時も既存データを維持する。詳しい形式・失敗時の動作は[`受信済み履歴`](test_bundle/RELAY-SEEN-HISTORY.md)を参照。
- 完全受信→検証→一時ファイル書込み→flush/close→rename→読戻し照合の後だけACKを送る。ACKは永久保持・最終配送・内容の真実性・DID本人認証の保証ではない。
- 満杯時は、**新規の要求済みBundleが署名検証と一時ファイル保存を完了した場合だけ**、cold/期限切れ→最古の有効キャッシュの順で1件を置換する。inventoryだけでは削除しない。不正な診断用ファイルは勝手に削除しない。保存16件と接続内の受信総数は分離し、同じ接続の17件目以降も受信する。未完了要求は最大16件、接続内の完了ID履歴は最大128件。履歴上限で接続を更新し、履歴を無制限に増やさない。
- 再送は常に保存した原本から生成し、hopと滞在時間を加算してCRCを更新する。署名済みpayloadは変更しない。同じイベントの重複受信では滞在開始時刻を更新しない。
- **信頼できる時計・電源断中の経過時間がない。** 署名付き配送期限の時刻判定はUNKNOWNとし、起動中は単調時計とBundle Ageで制限する。
- **再起動前の保存データは復元・検証するがcold扱いで、広告・転送しない。** 再起動で期限をリセットして無制限に配送しないための保守的な制約。同じIDの再受信でも復活させない。異なる新規イベントは通常どおり受信し、必要ならcoldキャッシュと置換する。
- ファイル保存はArduino FSが公開する範囲で確認する。実電源断を挟む耐障害性試験は未実施。
- 通信路自体は暗号化・相互認証しない。公開投稿のみを扱う。標準の確定値や本番運用向けの完成品とは扱わない。

## Optional Wi-Fi Internet gateway

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

**Do not run `uploadfs` or erase flash to enable networking.** Follow the backup procedure above first. `partitions-gateway.csv` merges the old app0/app1 area into one 0x280000-byte app at 0x10000. NVS, LittleFS (`0x290000`), coredump and dictionary (`0x400000`) remain at their old offsets/sizes. This removes the spare OTA slot; no OTA updater is provided. Upload the matching firmware **and partition table** together using PlatformIO; do not flash a gateway image into the smaller default app slot. Downgrading likewise requires the normal profile's matching partition table and firmware. Back up the partition table/whole flash too if recovery of a previous app/boot selection matters. Power-loss/upgrade/rollback on physical hardware has not been tested for this change.

The gateway profile deliberately **does not initialize TTS**, reserving its working memory/task stack for Wi-Fi/TLS. BLE, buttons, receipt verification, custody, screen preview and heartbeat remain. A/C cannot play audio in this profile; **A now requests the optional verified download described below**. **B disables BLE only, not Internet upload/download**; it is not a privacy/offline switch. To stop Internet export, remove the local config and rebuild/flash the gateway profile (which keeps pending entries pinned but starts no networking), or power off. Flashing the normal offline profile restores the original bounded, evictable-cache behavior. A missing header, invalid config or worker failure never silently unpins gateway data.

For compilation without credentials, `uv run pio run -e m5stack-basic-gateway-check` explicitly uses `GatewayConfig.check.h`: non-routable `.invalid` destinations and an intentionally invalid CA, not deployable credentials. Nonempty test values ensure the full TLS/upload/PLC/ingress path survives compiler/linker dead-code elimination. **Never deploy this profile.** The production gateway profile needs the local header; the default example's empty settings fail closed.

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
- Export JSON capture is capped at 64 KiB plus fixed fields. An otherwise valid bundle whose escaped JSON exceeds this stays pending, unchanged; it is **not** truncated. A 110,000-byte free-heap admission guard plus conservative per-job worst-case escaping/contiguous-allocation checks defer work under memory pressure. Concurrent BLE allocations and actual TLS heap/stack peaks still need device measurement. Large events, large PLC logs, permanently rejected data, invalid config, missing CA, no clock sync or unavailable memory can leave the bounded cache pinned indefinitely. Provision/correct the endpoint or recover originals before any manual cleanup; no automatic data-dropping escape hatch is supplied.

### Tests and unverified hardware behavior

`test_bundle/test_gateway.py` builds the real codec/protocol/transport helpers with synthetic sockets, ASan/UBSan and independent Python CBOR/hash/signing oracles. It checks exact exported fields for all eight actual Android fixtures, signed kind 1/5/8173, tampering/JSON expansion failure, PLC DID derivation, correct/wrong/negative OKs, framing/body/deadline/size limits and masking. `test_relay.py` also checks pending cache backpressure, cold/expired protection, failed reads, successful unpinning without deletion, reboot retries and unchanged genesis custody. These tests never publish events or submit a real PLC operation.

Example host invocation from the repository root with existing mbedTLS 2.x and ArduinoJson headers (the retained Windows/WSL dependency paths are documented in [`test_bundle/README.md`](test_bundle/README.md)):

```sh
python3 apps/esp32/test_bundle/test_gateway.py
python3 apps/esp32/test_bundle/test_relay.py
python3 apps/esp32/test_bundle/test_main_controls.py --sanitize --diagnostic
```

No live Wi-Fi/CA rejection/relay OK/PLC registration, ESP32 radio coexistence, flash power-cut, peak TLS heap/stack, or full phone → ESP32 → public PDS/AppView test is claimed for this change. Test with disposable **public** test identities and operator-approved destinations before deployment. Keep phone networking off during that test, independently retrieve and verify the identical full event ID/signature from the relay, check PLC genesis/current state separately, then check the PDS record/head and AppView.

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

The public generated root file should not set these local decisions. With missing/invalid public fields, no local selection, either opt-in absent, unknown clock, or unreadable checkpoint, ingress does not run. Root configuration alone does not enable it. The full-path `m5stack-basic-gateway-check` build uses only the synthetic public fixture root, `.invalid` endpoints and invalid TLS CA; it is never deployable. No additional PlatformIO library or partition change was added for ingress. **The existing gateway TTS-disabled tradeoff remains**.

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
- One job, existing 16 KiB worker stack. Ingress starts only with >=130,000 free heap bytes; frame parsing and verification have additional free/contiguous-heap guards (70/65 KiB). Intermediate trees are released in phases. <=24 inbound frames/pass, <=8 events/pass, no fragmentation/binary data; priority phase budget 8 seconds, fallback 15 seconds, plus bounded TLS/HTTP/SDK waits in the **worker**, not BLE callbacks/main-loop upload poll. Verification, normal final bundle validation, flash and UI work are bounded, not a hard real-time BLE latency guarantee. Actual TLS/heap/stack/radio coexistence peaks still need measurement on the physical M5Stack.

`test_bundle/wire-v1-combined-server-fixture.json` additionally contains a real server-issued combined 28-type certificate and one successive signed event per selected type, plus an unselected-extra negative. Reproduce with `node test_bundle/generate_server_fixture.mjs --write --combined` / `--check --combined`; this isolated test-only generator pins the 23 type options from the primary [Kikikuru definition](https://raw.githubusercontent.com/osprey74/bsaf-kikikuru-bot/main/bot-definition.json), uses no operational private keys and never changes shipped roots. Android's real verifier consumes this same JSON as a test-only resource. Both real receivers accept all 28 and reject unselected/expanded authority. Measured canonical Scope **579 B**, selection **544 B**, certificate **1,285 B**, identity **1,852 B**; maximum event JSON **4,366 B**, mesh payload **3,307 B**. Existing Scope/selection CBOR <=8,192 B each, certificate/identity <=4,096 B, JSON <=6,144 B and 128-node trees remain unchanged. Scope/selection tests accept 32/Bot and 64 total, reject 33/Bot and 65 total. A synthetic 64-type full identity exceeds the separate 128-node tree budget and remains rejected: advertised maxima need not fit simultaneously. No hardware/TLS peak-memory measurement is implied.

`test_bundle/test_ingress.py` uses independent Python canonical CBOR, low-S ECDSA and BIP340 signers with public TEST ONLY secrets (root=7, atproto=5, Nostr=3), then executes the real C++ verifier with ASan/UBSan. [`test_bundle/wire-v1-fixture.json`](test_bundle/wire-v1-fixture.json) publishes canonical certificate/binding/snapshot bytes/hashes, cert/root IDs, signed event and exact expected mesh payload for the parent Node/Kotlin implementations to consume. Python/ESP32 agreement is tested. A second frozen public fixture, `test_bundle/wire-v1-node-fixture.json`, was copied unchanged from the Android worker's `bundle-core/src/test/resources/wire-v1.json` (producer `apps/android/wire-v1-fixture.mjs`, using `@ipld/dag-cbor` / `@noble/curves`). The real Node producer's read-only validation and this C++ verifier both pass that independent fixture: canonical certificate/snapshot/record/commit bytes, IDs/hashes, signatures, original event and exact mesh payload agree. Neither primitive fixture is production trust; no claim is made here about running the Android/Kotlin suite or the complete server runtime. **Actual server interop is additionally covered by `test_bundle/wire-v1-server-fixture.json`**, reproducibly created by the real `pds/public-information.mjs:createImporterEvent` with real importer/genesis/binding/certificate/snapshot exports. It contains two successive events whose nonempty second `base` references the first commit; actual PDS `reconstruct` and an independent Python block-hash/MST traversal both verify one-record then two-record roots. Both events pass the real C++ checker separately and sequentially across persisted trust state. See `test_bundle/README.md` for the test-only generator, source provenance, exact Windows/WSL commands and safety limits. This does not turn fixture MST auditing into an on-device inclusion-proof claim. Negative cases include binding/key/DID/signature mismatches, high-S/DER, unknown roots, unsupported chains, scope expansion, time/clock boundaries, stale/future snapshots, rollback/equivocation/revocation removal, sticky serial conflict, unselected Bot/type, and priority without authorization. Relay tests additionally inject checkpoint/write/read/rename failures, expiry during persistence, duplicate/full-cache and persist-before-advertise boundaries. No tests fetch public events, submit operations or flash a device.

From repository root, reusing the exact retained WSL dependencies described in `test_bundle/README.md` (Git Bash requires `MSYS_NO_PATHCONV=1`):

```sh
MSYS_NO_PATHCONV=1 wsl.exe -d Ubuntu -- timeout 180s env PYTHONPATH=/home/mchiba/.cache/mesh-bundle-host/deps/usr/lib/python3/dist-packages python3 apps/esp32/test_bundle/test_ingress.py --mbedtls-include /home/mchiba/.cache/mesh-bundle-host/deps/usr/include --mbedtls-library /home/mchiba/.cache/mesh-bundle-host/deps/usr/lib/x86_64-linux-gnu/libmbedcrypto.a
```

Prior `test_gateway.py`, `test_relay.py`, `test_bundle.py --host ...`, Base64 and main-controls commands remain valid and are rerun for regression validation. Firmware: `.venv/Scripts/pio.exe run -e m5stack-basic-gateway-check` from `apps/esp32` compiles/links the complete ingress path without real credentials. Normal/gateway/diagnostic profiles must also build. No live MOSIMO-REQ/REQ negotiation, HTTPS root snapshot fetch, real SNTP/clock attack, hardware upgrade/power cut, or independent server→ESP32→phone admission is claimed. Before deployment, generate/approve the real public root Scope and exact JMA selection, run the shared fixture against the parent server implementation, then validate the real path with approved public test data.

## 日本語の単体読み上げ（評価専用）

AquesTalk ESP32 Small辞書版2.4.4を**キーなし評価モード**で実行する。漢字かな混じりUTF-8をESP32自身で解析・合成し、内蔵スピーカーで再生する。PC・Android・クラウドでの音声合成や、録音ファイルの転送は行わない。microSDも不要。

**評価版はナ行・マ行がすべて「ヌ」になる制限がある。正確な防災放送や製品運用には使わない。** SDKの同梱条件と[公式配布条件](https://www.a-quest.com/download.html)に従い、今回の組み込みは事前評価目的に限る。継続利用・製品開発にはライセンス、販売・配布には別途頒布条件の確認が必要。
SDK・辞書・生成設定・バックアップは`private/`、リンク済みファームウェアは`.pio/`に置く。**SDKの全部・一部や、SDKを含む評価ファームウェアをGit・Release等で公開しない。** リポジトリには独自の接続コードとテストだけを追加する。

### 辞書の初回書込み

`setup_tts.py`とビルドが成功してから、既存の本プロジェクトの16 MiB実機に対して実行する。

```powershell
uv run python provision_tts.py COM3
uv run pio run --target upload --upload-port COM3
uv run python smoke_test.py COM3 --mesh --speech
```

`provision_tts.py`は16 MiBフラッシュと既存パーティション表を照合し、表・既存メッセージ領域・追加先領域を`private/backups/<UTC時刻>/`へ退避した後だけ、`0x400000`へ1,969,776バイトの辞書を書き込み、読戻し検証する。初回書込みや辞書変更時だけ必要。想定と異なる表の場合は停止し、強制移行しない。
**`uploadfs`は不要。既存LittleFSを移動・フォーマットしない。** 辞書は起動時にもSHA-256を検査し、失敗時は読み上げを無効にするがメッシュを止めない。

### 動作・上限

- 保存・署名検証済みの新規メッセージだけを別タスクへ渡す。保持中の重複や起動時の復元では発話を要求しない。
- 最大2,000 UTF-8バイト。超過・不正UTF-8・空白だけの本文は読み上げないが、表示・保存・中継は続ける。
- 100文字以内の文単位へ分割し、8 kHz・16-bit・mono PCMを逐次生成。辞書はフラッシュを参照し、全文辞書や全音声をRAMへ読み込まない。
- **新着優先・未再生分は最新1件のみ。** 読み上げ可能な新規投稿を受けると、現在の発話を中断して新着を読む。短時間に複数件届いた場合は古い待機分を置換する。同一IDの重複・不正本文・ミュート中の投稿は現在の発話を中断しない。音声側の直近64 IDの抑止に加え、Relayの永続履歴で再接続時の再取得を防ぐ。全投稿の必達読み上げは保証しない。
- 発話は通常最大60秒、1秒の再生待ちタイムアウト。SDK呼出しの間で確認する**協調的な制限**であり、SDK自身の処理が戻らなくなった場合の強制中断や、新着受信・Cボタン直後の無音は保証しない。既にDMAへ送った音の短い末尾が残る場合がある。
- Cで止めた発話は解除しても途中から再開しない。SDKや再生エラーはID・段階・コードだけをログに出し、本文・音声記号列は出力しない。

メッシュ相手が新規投稿を送る状態なら、受信IDと発話完了の非ゼロ音声サンプルを自動照合できる:

```powershell
uv run python smoke_test.py COM3 --mesh --speech --received-speech --log .pio/tts-evaluation-smoke.log
```

### 実測資源

表示＋BLE＋評価TTSの実ビルド: **1,218,257バイト／1,310,720バイト（92.9%）**、静的RAM52,480バイト。
別領域の辞書1,969,776バイトを含めても16 MiB内に収まる。受信・合成・再生中の空きヒープ約100 KBを確認し、本文プレビューも維持できた。
長時間耐久・音質・騒音下の明瞭度は別途確認が必要。SDKを組み込んだビルドのため、今後の機能追加ではアプリ領域の残量にも注意する。

## 任意の実機診断・接続の隔離

通常の`uv run pio run`は`m5stack-basic`のみをビルドし、peer制限・固定node・制御バイト列ログは無効のまま。
明示的な`uv run pio run -e m5stack-basic-diagnostic`は今回の実験用で、nodeを`a7fb2785f1068ab5`へ固定し、初回制御フレームだけ最大256バイトのhexを出す。著者認証ではなく、一般配布・通常運用用ではない。

診断版だけはUARTで次を1行送れる（115200 baud、末尾改行）:

```text
peers *
```

`peers *`は無制限へ戻す。実験時のみ`peers`に続けて現在のnodeを空白区切りで指定すると、そのnodeのみHELLO後に許可し、既に接続している除外peerを切断する。最大8件の小文字16桁hex、不正・長過ぎる入力では設定を変えない。設定は揮発性で、再起動時は無制限。Androidのnodeもプロセス再起動で変わるため、過去の実験IDを再利用しない。

**2026-10-18 14:17 最終状態：`peers *`受理済み、peer制限なし、通信ON・音声ON、COM3は閉鎖。** hot cacheを維持するため診断バイナリ自体の入替え・リセットは行っていない。隔離したBLE全経路はservice discoveryで未完了であり、直接オンライン公開の成功とは区別する。
シリアルを複数プロセスで開かず、受信→転送中はDTR/RTSをfalseにして**リセットしない**。A/B/Cの割当は変わらない。今回の実バイト列・バックアップ・現在のブロッカーは監査文書を参照。

## 他ブランチへの追従

このブランチで`origin/main`と`origin/feature/client`を取り込み、その都度`mndt.md`とAndroidの`Wire.kt`・`GattLink.kt`・`Exchange.kt`を照合する。
新CBOR方式への移行はAndroidとの協調変更が必要。継続的な自動マージ処理は設けていない。

最新仕様との照合・今回の修正と検証・デモの正確な互換条件は[`AUDIT-2026-10-18.md`](AUDIT-2026-10-18.md)を参照。初回ソフトウェア監査後、明示承認を受けて既存全割当領域を退避し、COM3のapp0のみ更新・検証した。追加ハードウェア検証の証拠と残件も同文書に記録。
