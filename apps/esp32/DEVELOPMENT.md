# ESP32 開発・実機試験ガイド

機能概要・対応範囲は [README.md](README.md)、実機検証の条件・記録は [VERIFICATION.md](VERIFICATION.md) を参照。

## セットアップ・ビルド・ホストテスト

Pythonは `.python-version`、Python依存は `uv.lock`、SDKとC++ライブラリーは `platformio.ini` で管理する。以下は `apps/esp32` で実行する（Windows）。

```powershell
uv sync --locked
uv run python setup_tts.py C:\SDK\aquestalk-esp32_s_0244.zip
uv run pio device list
uv run pio run
```

ZIPパスは正規に入手した評価用SDKの保存場所に置き換える。`setup_tts.py` はSDKをダウンロードせず、ローカルZIPから必要なファイルと辞書SHA-256設定をGit対象外の `private/aquestalk/` に配置する。PlatformIOのSDK・コンパイラー等は初回ダウンロードにネット接続が必要。

NixOS（WSL）では、uvが取得する汎用Linux向けPythonは動かないため、PlatformIOはnixpkgsのものを使い、`setup_tts.py` はシステムのPythonで実行する。

```sh
python3 setup_tts.py /mnt/c/path/to/aquestalk-esp32_s_0244.zip
nix shell nixpkgs#platformio -c pio run -e m5stack-basic
```

WSLからはCOMポートが見えないため、書込み・UART採取はWindows側のPython環境（`esptool`・`pyserial`）で行うか、`usbipd` でデバイスをWSLへ接続する。esptoolで直接書き込む場合のアドレスは、bootloader `0x1000`、partitions `0x8000`、boot_app0 `0xe000`、firmware `0x10000`、LittleFS `0x290000`。

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

Aは評価テスト文の読み上げ（音声なしgateway版では不可）。BはBLE送受信だけの切替で、gatewayのInternet搬出は止めない。音声対応版のCは音声だけのミュート・解除。通信・音声の設定は揮発性で、再起動時はともに有効へ戻る。音声なし `m5stack-basic-gateway` ではA/Cで音声は再生しない。combined版はTLS・音声処理中にBLEを休止する（下記）。

**受信→転送の試験中はリセットしない。** UARTを採取する際もDTR/RTSをfalseにする。再起動前の保存データはcold扱いとなり、起動から2時間（`RESTORED_RESIDENCE_MS`）だけ広告・転送する。

## Androidとの実機確認

Androidは [開発ガイド](../android/DEVELOPMENT.md) に従ってビルドする。**同じスマートフォン上の複数アプリ（例: `.debug` と `.debug2`）は同じBLEリンクを共有するため、同時にESP32へ接続させない。** ESP32からは1本の接続に複数のGATT clientが書き込む形になり、CBOR sequenceが混ざって切断される。試験中は使わないアプリを止める（`adb shell am force-stop <package>`）。

1. オフライン中継だけを確認する場合、ESP32は通常版・診断版を使う。送信側アプリだけを起動する。
2. 送信側で一意なテスト本文を投稿する。
3. ESP32ログの `mesh HELLO side=…`、`reconciled: …`（ESP32が側0のとき）、`want 1`、`saved <ID> kind 1 hop …` を確認する。画面には本文・著者鍵の先頭・hopが表示され、読み上げられる。
4. 送信側を止め、ESP32の電源は切らずに受信側だけを起動する。
5. ESP32の `sent <同じID> hop …` と `peer accepted <同じID>`、受信側の `Exchange` ログの `want 1 from …` を照合する。

```powershell
adb logcat -d -v time -s Exchange:I GattLink:I '*:S'
```

ESP32は接続枠が1つのため、差分照合を終えて（自分が開始した照合の完了、または相手の照合への応答の後）5秒間やり取りがなければ `mesh exchange complete; releasing connection` で接続を解放する（[メッシュ配送 §3.4](../../protocol/mosimo/mesh/README.md#34-バッテリー)の「双方に送るものがなくなった時点で切断」）。照合が行われない相手でも、45秒間やり取りがなければ解放する。相手が知らせたIDを要求しなかった場合は、`want 0 of N (held …, refused …, seen …, deferred …, capacity …)` で理由（保存済み・拒否済み・受信履歴あり・容量不足）を記録する。受信側が既にイベントを持っていれば照合結果は差分なしになり、転送は起きない。同一Android上の2アプリによる試験を、独立した3台の無線機器による到達距離・移動の試験とは扱わない。

## 保存・受信停止時の動作

- 保存は `/mesh/00`〜`/mesh/15` の16枠・合計384,000 B、加えて受信中の一時ファイル `/mesh/.incoming`（24,000 B以下）1つ。ファイル名は保存場所であって識別子ではなく、起動時に中身を検証してイベントIDを索引する。検証できないファイルは診断用に残し、削除も上書きもしない（容量には数える）。
- 保存は一時ファイルへの書込み・flush・close → `rename` による置換 → 全バイトの読戻し照合の順で行い、成功した後にだけ `RESULT` 保存受理を返す。途中で失敗した段階と errno をログに出し、受理応答は返さず切断する。受理応答は直近の相手が保存したことを示すだけで、永久保持・最終配送・内容の真実性の保証ではない。
- 満杯時は、転送対象外（中継期間を過ぎたcold・期限切れ）の投稿 → 実効優先度の最も低い投稿（同順位では最も古く受信したもの）の順に1件を置き換える。転送対象だけで満杯のとき、どれよりも優先度の低い新着は受け取らない（理由コード1）。ESP32は時計を信頼できず署名チェーンも検証しないので、実効優先度は `min(hint, 1)`。`/mesh-seen` が1024件に達したら古い半分を捨てて書き直す。gateway版で搬出未確認の投稿は置き換えない。置き換える前に、その投稿のイベントIDを `/mesh-seen` に追記して読み戻し、記録できなければ置き換えも受理もしない。
- `/mesh-seen`（受信済み履歴）: 先頭8バイト `MSEEN1\r\n` に続き、最大1,024件の「イベントID 32 B + そのCRC-32C 4 B（ビッグエンディアン）」。最大36,872 B。RAMにはIDの先頭8バイトだけを索引し、一致したら全256ビットをファイルで照合する。保存中のIDと履歴のIDは、相手やセッションが変わっても、再起動しても再取得しない。履歴だけでは照合候補・転送・受理応答の根拠にしない。
- 履歴は自動で古いものを捨てない。1,024件と16枠がともに満杯になると新規取得を止め、保持中の投稿の転送は続ける。履歴の破損・切詰め・上限超過は起動失敗として扱い、自動で修復・初期化しない。実行中に履歴の読み書きに失敗した場合は、その起動中の新規取得を止める。
- 通常版には信頼できる時計と電源断中の経過時間がないため、起動中は単調時計とBundle Ageで期限を制限する。署名済み配送期限（`mosimo:exp`）は形式だけ検査し、満了は判定しない。gatewayのSNTPも認証済み時計ではない。再起動前の保存分はcoldとし、保持・gateway搬出に加え、起動から2時間に限って照合候補・転送にも出す。
- マウント・保存失敗時に、診断用ファイルや履歴を安易に削除して復旧しない。まずバックアップとログを確保する。バックアップ・復元は `/mesh` だけでなく `/mesh-seen` を含むLittleFS領域全体で行う。

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

通常の `uv run pio run` は `m5stack-basic` のみをビルドし、固定node・GATTトレースは無効。

`uv run pio run -e m5stack-basic-diagnostic` は隔離試験専用。固定nodeは `platformio.ini` の `MESH_TEST_NODE_ID` を参照し、GATTの接続・購読・MTUをトレースする。一般配布・通常運用には使わない。診断版とgateway版のUARTは `status` を受け付ける。

交換形式は [メッシュ配送](../../protocol/mosimo/mesh/README.md) §3・§5・§7 に従う。変更時はAndroidの `Messages.kt`・`Exchange.kt`・`GattLink.kt`・`Negentropy.kt`、および `bundle-core` の `EventCodec`（`SignedPost.kt`）と照合する。拒否したメッセージはログに先頭24バイトをhexで出す（公開プロトコルのバイト列のみ）。

### このノードのプロファイル

| 項目 | 値・対応 |
|---|---|
| BLE役割 | peripheral専用（scan responseの役割flags bit0=1）。接続は1本 |
| 広告 | 128ビットService UUID一覧 + scan responseのService Data（node ID・役割flags・[広告要約](../../protocol/mosimo/mesh/adv/README.md)の要約3バイト・拡張flags 0）。要約は未接続時に5秒ごとに再計算 |
| HELLO | 受信Bundle上限24,000 B、受信メッセージ上限4,096 B、初期許可4,096 B、対応経路GATTのみ、拡張なし |
| 差分照合 | Negentropy v1。側0なら開始し、側1なら応答する |
| 転送 | 受信は1件ずつ組み立て、`BEGIN` 後に全長分の `CREDIT` を一括許可。送信は相手の初期許可と `CREDIT` に従う。保存・読戻し照合後にだけ `RESULT` 保存受理 |
| 未対応 | 鍵署名の参照化（§2.1.3）の復元（`FETCH` は提供不能を返す）、L2CAP・LAN・Nearby、`CANCEL`、zstd、時刻ヒント、ブロック設定 |
| 保持 | 16件・384,000 B。退避IDは `/mesh-seen`（最大1024件、満杯で古い半分を破棄）に記録して再取得しない。再起動前の保存分はcold（起動から2時間だけ照合候補・転送に出す） |

## 任意のWi-Fi gateway

gateway版は、保持している公開イベントを元の署名のまま搬出する。電話のテザリング・新しい著者・PDS・PLC権限者ではなく、鍵の生成や再署名・編集はしない。通常版・診断版は、ローカルにgateway設定があってもオフラインのまま。プロトコル上の位置づけは [コア](../../protocol/mosimo/README.md)、[論理PDSとNostrリレー](../../protocol/mosimo/pds/README.md)、[メッシュ配送 §12](../../protocol/mosimo/mesh/README.md) を参照。

| ファイル | 責務 |
| --- | --- |
| `src/MeshGateway.cpp` | Wi-Fi・TLS・Nostr搬出・PLC代理登録、ネットワーク/音声worker（combined版） |
| `include/GatewayProtocol.h`, `include/GatewayWire.h` | 有界HTTP/WebSocket、リレーのOK判定、PLC DAG-CBOR・DID導出 |

### 搬出対象

| イベント | 搬出先 |
|---|---|
| kind 1（投稿）、kind 5（削除要求） | 設定したNostrリレー |
| kind 8173、`t` = `mosimo.jisaku.dev/revocation`（失効通知） | 設定したNostrリレー |
| kind 8173、`t` = `mosimo.jisaku.dev/plc`（PLC初期操作） | PLC Directoryへ代理登録。Nostrには出さない（[PDS §6](../../protocol/mosimo/pds/README.md#6-plc-初期操作の制御イベントと代理登録)） |
| その他 | 搬出しない。通常のキャッシュ置換の対象 |

搬出時はメッシュ用CBORから元のNostr JSONを復元し（省略タグ・型変換値を戻す）、IDとBIP340署名を再検証してから `["EVENT", event]` を送る。配送期限・hopの書換え、タグ追加、再署名はしない。メッシュ上で期限切れ・hop上限・中継期間を過ぎたcoldのものも搬出する（メッシュ配送の資格は戻らない）。インターネットからメッシュへの取り込み（[転載サーバーの認可](../../protocol/mosimo/attest/importer/README.md)、MOSIMO-REQ）は未実装。

### ローカル設定

Git対象外のヘッダーを作る。**認証情報を `platformio.ini`、コマンドライン、追跡対象のソース、スクリーンショット、ログに入れない。**

```powershell
New-Item -ItemType Directory -Force private/gateway
Copy-Item include/GatewayConfig.example.h private/gateway/config.h
```

`private/gateway/config.h` で次を設定する。空の値はfail closed。

- `MESH_GATEWAY_WIFI_SSID`, `MESH_GATEWAY_WIFI_PASSWORD`: 2.4 GHzのステーション接続。SSID 32 B以下、パスフレーズ63 B以下。キャプティブポータル・エンタープライズ認証は非対応。
- `MESH_GATEWAY_RELAY_HOST`, `..._PORT`, `..._PATH`: 信頼するリレー1つのDNS名・TLSポート・WebSocketパス。atprotoへの復元が必要なら、物理PDSの **Nostr WebSocket** を指定する。リレー探索・リダイレクト・平文へのフォールバックはない。
- `MESH_GATEWAY_RELAY_CA`: そのリレーのルートCA（PEM）。別経路で入手・確認する。空・不正なCAで検証を省略することはない。
- `MESH_GATEWAY_PLC_URL`, `MESH_GATEWAY_PLC_CA`: 任意。末尾スラッシュなしのHTTPS（ポート443）のDirectory URL（例 `https://plc.directory`）とそのCA。空にするとPLC代理登録は無効になり、PLC初期操作は未搬出のまま保護され続ける。
- `MESH_GATEWAY_NTP_SERVER`: 既定 `pool.ntp.org`。TLSは時刻が2026-10-18以降になるまで待つ。**SNTPは認証されない。**

認証情報はファームウェアに暗号化されずに埋め込まれるので、ヘッダー・ビルド成果物・フラッシュのバックアップ・実機を保護する。BLE/HTTPでの設定経路はなく、イベントが搬出先を選ぶこともない。このプロファイルを有効にすると、保持している対象の公開イベントすべて（再起動前のcold分を含む）の搬出に同意したことになる。`scripts/provision_gateway.py` は検証用PCのWindowsのWi-Fiプロファイルとシステムが検証したTLSチェーンからこのヘッダーを作る補助で、既存のヘッダーは上書きしない。

### ビルド・書込み

```powershell
uv run pio run -e m5stack-basic-gateway
# 設定を確認し、フラッシュとデータをバックアップしてから:
uv run pio run -e m5stack-basic-gateway --target upload --upload-port COM3
```

**ネットワークを有効にするために `uploadfs` や消去を行わない。** `partitions-gateway.csv` は通常版のapp0/app1を0x280000 Bの単一アプリ領域（0x10000）にまとめ、NVS・LittleFS（0x290000）・coredump・辞書（0x400000）は同じ位置・サイズのまま。OTAの予備領域はなくなる。ファームウェアとパーティション表は必ず組で書き込み、通常版へ戻すときも通常版の組を書く。`scripts/backup_flash.py` は16 MiB全体を2回読んでSHA-256を比較し、`private/backups/<UTC>/` に保存する（書込み・消去はしない）。移行中の電源断と巻き戻しは未検証。

`-check` 付きのプロファイル（`m5stack-basic-gateway-check`、`m5stack-basic-gateway-tts-check`）は `GatewayConfig.check.h` の `.invalid` 宛先と不正なCAで全経路をコンパイル・リンクする確認用で、**書き込まない**。

搬出を止めるにはローカル設定を消してgateway版を書き直すか電源を切る。Bボタンが止めるのはBLEだけで、Internet搬出は止まらない。設定がない・不正・workerの起動失敗のいずれでも、搬出未確認の投稿の保護は解除しない。

### gateway＋音声（combined）版

`m5stack-basic-gateway-tts` は同じgateway用パーティション表を使い、PSRAMのないBasicでBLE・TLS・音声の資源を時間分割する。**BLEの交換・TLS・音声合成は同時に動かない。**

- 音声とネットワークは16 KiBスタックのworker 1つで直列に処理する。ループのスタックは12 KiB。
- ネットワーク処理や音声の前に、メインループが広告を止めて切断を要求し、最後のコールバックを待ってからNimBLEを解放する。処理後は同じnode IDで再初期化し、次の処理まで5秒以上のBLE受付期間を空ける。相手は再接続し、受理応答のない転送を再試行する必要がある。
- ネットワーク処理はBLE停止後に空き80,000 B以上・連続18,000 B以上で開始する（音声なしgateway版は空き110,000 B以上・連続32,768 B以上）。足りない場合はデータを保持したまま後で再試行する。

### 配送・上限・失敗時の動作

- Relay・LittleFS・UIはメインループだけが所有する。workerへは24,000 B以下の複製を1件ずつ渡し、DNS・TLS・HTTP・WebSocketの待ちはBLEコールバックやメインループで行わない。
- リレー受理と数えるのは、同じTLS接続で受けた `["OK", 完全なイベントID, true, …]` だけ。`false`（`duplicate:` を含む）、NOTICE、AUTH、タイムアウト、切断、不正・過大・分割されたフレームは受理ではない。リレーは1つ、NIP-42認証なし、購読なし。
- PLC代理登録は、署名済みの初期操作を変えずに送る。正規化DAG-CBORのSHA-256から `did:plc` を導出し、`/log` を取得して404のときだけ提出し、再取得したログの先頭が一致すれば登録確認とする。既存・不正・不一致のログは上書きしない。PLC署名の検証はDirectoryに委ねる。
- **リレー受理、PLC登録、PDSでの復元・公開、AppViewでの表示は別の段階**であり、それぞれ別に確認する。
- 搬出未確認の対象イベントは、期限切れ・再起動後も置換しない。16件すべてが未確認になると新規取得（WANT）を止める。受理を確認したら保護を外すだけで、すぐには削除しない。確認状態はRAMだけに持つため、再起動後はすべて再送する（at-least-once）。
- 未確認のIDはラウンドロビンで扱い、IDごとに5/10/20/40/80/160/300秒（上限5分）の間隔で再試行する。Wi-Fi再接続は30秒間隔。同時に処理する接続は1つ。
- 上限: HTTPヘッダー4 KiB・64行・1行512 B、PLC操作8 KiB、ログ本体16 KiB・128操作、リレー応答フレーム1 KiB・32フレーム、TLS接続5秒、応答待ちはPLC 15秒・リレー20秒。搬出JSONは64 KiBまでで、超える場合は切り詰めずに未搬出のまま残す。
- 30秒ごとのシリアル出力 `gateway: wifi=… clock=… heap=… pending=… busy=…` に、認証情報やイベント本文は含まれない。

### UART

gateway版・診断版は改行終端の `status`、gateway版は `audio on` / `audio off` も受け付ける。1ループ64 B・1行160 Bまでで、超えた行は改行まで丸ごと捨てる。任意文の読み上げ、認証情報の読出し、ファイル消去のコマンドはない。他のプロセスがCOMポートを使っていない状態で、リセットを要求しない補助スクリプトを使う。

```powershell
.venv/Scripts/python.exe scripts/gateway_serial.py COM3 --command status --seconds 15 --log .pio/gateway-status.log
# スマートフォンからBLEで新しい投稿を受ける間:
.venv/Scripts/python.exe scripts/gateway_serial.py COM3 --audio-on --seconds 300 --log .pio/gateway-speech.log --require-speech
```

`--require-speech` は、クラッシュ・再起動を検出した場合か、保存したkind 1のイベント → 読み上げ開始 → 無音でないPCMの完了が揃わない場合に失敗する。`TTS done` は合成と再生APIの完了を示し、聞き取れる音質を示すものではない。

搬出の結果は、別途リレーから同じイベントIDを取得して確認する（`node scripts/check_relay_event.mjs <イベントID>`。`pds/` のテスト用依存を使い、IDとBIP340署名を再計算する）。PLCの登録状態、PDSのレコード、AppViewも別に確認する。

### 未検証の事項

刷新後のgateway版・gateway＋音声版は、ホストテストとビルドだけを確認しており、実機での搬出・PLC登録・時間分割は未検証。BLEとWi-Fiのデータ通信の同時動作、CA拒否の実機試験、電源断、長時間動作、オフラインの2台 → ESP32 → PDS/AppViewの一連の試験も未実施。実機試験は使い捨ての公開テスト用IDと、運用者が承認した宛先で行う。
