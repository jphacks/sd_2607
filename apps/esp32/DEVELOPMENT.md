# ESP32 開発・実機試験ガイド

通常の開発では、ソースを変更して対象環境を指定し、ファームウェアを書き込んでからシリアルモニターで確認する。書き込みやモニター接続で再起動する場合があるため、受信・転送試験中は実行しない。保存済み投稿が必要な場合は、パーティション移行や初期化の前にバックアップする。

機能概要・対応範囲は [README.md](README.md)、実機検証の条件・記録は [VERIFICATION.md](history/VERIFICATION.md) を参照。

## セットアップ・ビルド・ホストテスト

Pythonは `.python-version`、Python依存は `uv.lock`、SDKとC++ライブラリーは `platformio.ini` で管理する。以下は `apps/esp32` で実行する。

[mise](https://mise.jdx.dev/getting-started.html) を利用する場合は、同ディレクトリの `.config/mise/config.toml` で Python 3.12 と uv を用意し、既存のロックファイルから依存をインストールできる。

```sh
mise trust
mise install
mise run sync
mise run devices
# 音声あり版は先に SDK ZIP を private/aquestalk-esp32_s_0244.zip に置く
uv run --locked python setup_tts.py private/aquestalk-esp32_s_0244.zip
mise run build m5stack-tts
# ソースを変更した後、同じ環境を指定して書き込む
# フラッシュ容量とパーティションを確認し、必要なデータをバックアップしておく
mise run upload m5stack-tts
# COM3 は実際のポートに置き換える
mise run monitor -- --port COM3
```

ビルド時に環境を選択する。BLE はすべての環境で有効。

| PlatformIO 環境 (`-e`) | Wi-Fi gateway | 読み上げ |
| --- | --- | --- |
| `m5stack` | 無効 | 無効 |
| `m5stack-tts`（既定） | 無効 | 有効 |
| `m5stack-wifi` | 有効 | 無効 |
| `m5stack-wifi-tts` | 有効 | 有効 |

音声を有効にする環境だけ、下記の AquesTalk SDK セットアップと辞書の実機書込みが必要。gateway は別途ローカル設定が必要で、併用版は TLS・音声処理中に BLE を休止する。gateway 版と通常版はパーティション表が異なるため、切り替えて書き込む前にバックアップと実機のレイアウトを確認する。

`mise run build --help` と `mise run upload --help` で選択肢を確認できる。どちらも引数を省略すると `m5stack-tts` を選択する。`upload` は必要に応じてビルドした後に本体へ書き込み、接続ポートは PlatformIO が自動検出する。ポートを明示する場合は `uv run pio run -e m5stack-wifi --target upload --upload-port COM3` を使う。検証用の `m5stack-wifi-check` などは `uv run pio run -e m5stack-wifi-check` で指定する。SDK の自動ダウンロードは提供元のアクセス制限により利用できないため、[公式ページ](https://www.a-quest.com/download.html)から ZIP を取得する。

`mise tasks` でタスクを一覧できる。`COM3` は実際のポートに置き換える。モニターの接続で実機がリセットされる場合があるため、受信・転送試験中は注意する。ホストテストには別途 C++ コンパイラーや mbedTLS 等が必要で、下記の手順を参照する。mise を使わず、既存の uv コマンドを直接実行することもできる。

```powershell
uv sync --locked
uv run python setup_tts.py private/aquestalk-esp32_s_0244.zip
uv run pio device list
uv run pio run
```

[公式ページ](https://www.a-quest.com/download.html)から **AquesTalk ESP32 Small 辞書版 2.4.4** の ZIP を取得し、必要に応じて `private/` ディレクトリを作成して `apps/esp32/private/aquestalk-esp32_s_0244.zip` に配置する。この場所は Git 管理対象外。`setup_tts.py` は ZIP をダウンロードせず、必要なファイルと辞書 SHA-256 設定を `apps/esp32/private/aquestalk/` に配置する。展開される主なファイルは次のとおり。上の3つはビルドに必要で、辞書は実機への書き込みに使う。

- `apps/esp32/private/aquestalk/src/aquestalk.h`
- `apps/esp32/private/aquestalk/src/esp32/libaquestalk_s.a`
- `apps/esp32/private/aquestalk/generated/LocalAquesTalkConfig.h`（スクリプトが生成）
- `apps/esp32/private/aquestalk/aq_dic/aqdic_m.bin`（実機への辞書書込みに使用）

`private/` は Git 管理対象外。SDK やリンク済みファームウェアは公開しない。PlatformIO の SDK・コンパイラー等は初回ダウンロードにネット接続が必要。

現在の `platformio.ini` と `partitions.csv` は16 MiBフラッシュ用。別の実機へ書き込む前に容量と既存パーティション表を確認する。ホストテストの依存・実行手順は [test_bundle/README.md](test_bundle/README.md) を参照。

## 保存領域の初期化・バックアップ

**`uploadfs` は保存済み投稿を消す。通常のファームウェア更新・辞書書込みでは実行しない。** マウント失敗時も自動フォーマットしない。`LittleFS unavailable; not formatted` の場合は、既存領域をバックアップしてから初期化する。

以下のアドレスとサイズは現在の `partitions.csv` と `partitions-gateway.csv` に共通する LittleFS 領域。ビルドした `partitions.bin` と実機の既存パーティションを照合してから使う。`uploadfs` は `data/` から作成したイメージで領域全体を上書きするため、投稿だけでなく受信履歴や ingress 状態も失われる。`COM3` は `pio device list` の結果に置き換える。

```powershell
New-Item -ItemType Directory -Force .pio/backups
uv run pio pkg exec --package tool-esptoolpy -- esptool.py --chip esp32 --port COM3 --baud 460800 read_flash 0x290000 0x160000 .pio/backups/pre-littlefs.bin
```

初期化が必要な場合に限り、バックアップの成功を確認してから次を実行する。保存領域は `data/` の内容で上書きされる。

```powershell
uv run pio run --target uploadfs --upload-port COM3
```

同じ名前で元のバックアップを上書きしない。`.pio` はGit対象外なので、必要なバックアップは別の安全な場所にも保存する。

## 書き込み・起動確認

ソース変更後は、対象環境のファームウェアをビルド・書込みし、起動ログを確認する。`upload` はプログラムとパーティションテーブルを置き換えるが、通常は LittleFS イメージを書き込まない。保存データの保持は保証されないため、既存データが必要なら事前にバックアップする。環境を切り替える場合は「ビルド・書込みとパーティション移行」も確認する。

```powershell
uv run --locked pio run -e m5stack-tts --target upload --upload-port COM3
uv run --locked pio device monitor --port COM3
# 必要に応じてモニターを終了し、リセットを伴うスモークテストを実行する
uv run --locked python smoke_test.py COM3 --mesh --speech
```

スモークテストは**実機をリセット**し、起動・稼働ログ・BLEと評価TTSの初期化を確認する。辞書は下記の初回書込みが必要。モニターは `Ctrl+C` で終了し、同じポートを複数プロセスで開かない。

通常版のAは評価テスト文の読み上げ、gateway版のAは明示設定したwire-v1 ingressの取得要求。BはBLE送受信だけの切替で、gatewayのInternet搬出・取得は止めない。音声対応版のCは音声だけのミュート・解除。通信・音声の設定は揮発性で、再起動時はともに有効へ戻る。音声なし `m5stack-wifi` ではA/Cで音声は再生しない。combined版はTLS・音声処理中にBLEを休止する（下記）。

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

通常の `uv run pio run` は `m5stack-tts` のみをビルドし、peer制限・固定node・制御バイト列ログは無効。

`uv run pio run -e m5stack-tts-diagnostic` は隔離試験専用。固定nodeは `platformio.ini` の `MESH_TEST_NODE_ID` を参照する。制御フレームのhexログを出すため一般配布・通常運用には使わない。

診断版ではUART（115200 baud、末尾改行）へ `peers` に続けて現在のnodeを空白区切りで送ると、HELLO後の許可対象を制限できる。最大8件の小文字16桁hexで、除外peerは切断する。`peers *` で無制限へ戻す。設定は揮発性で、認証ではない。Androidのnodeはプロセス再起動で変わるため過去のIDを再利用しない。

wire形式の変更時は [MNDT](../../protocol/mndt.md) とAndroidの `Wire.kt`・`GattLink.kt`・`Exchange.kt` を照合する。現行§9形式と新§3形式を同じ接続で混在させず、移行は協調して行う。仕様との差分・統合条件は [監査資料](history/AUDIT-2026-10-18.md) を参照。

## 任意の Wi-Fi インターネットゲートウェイ

追加実装の主な変更箇所:

| ファイル | 責務 |
| --- | --- |
| `src/MeshGateway.cpp` | 任意の Wi-Fi・TLS による Nostr 搬出・PLC 代理登録・要求時の検証付き ingress、単一のネットワーク・音声 worker（併用版） |
| `src/MeshIngress.cpp`, `include/IngressCodec.h` | wire-v1 root→leaf・ECDSA/binding/commit・fresh snapshot/high-water・strict JSON/CBOR |
| `include/GatewayProtocol.h`, `include/GatewayWire.h` | 有界HTTP/WebSocket・OK判定・PLC DAG-CBOR/DID導出 |

BLEコールバックは有界キューへのコピーを行い、通常のBLE受信の暗号検証・ファイル操作・表示はメインループで処理する。gatewayの取得・認可検証はworker、Relay/LittleFS/UIと最終保存はメインループが所有する。

ローカル設定があっても、**既定の `m5stack-tts` と診断用環境はオフラインのまま**。ゲートウェイは公開データを保存・転送するもので、スマートフォンのテザリング、新しい投稿者、PDS、PLC 認証局ではない。鍵の生成や投稿の再署名・編集は行わない。プロトコル上の境界は [0001 §1.1.1](../../protocol/0001-repository-model.md)、[0007](../../protocol/0007-nostr-relay-profile.md)、[0012](../../protocol/0012-bindings.md)、暫定 Android 制御 kind 8173 は [mndt §9.5](../../protocol/mndt.md) を参照。

### ローカル設定と公開アップロードへの同意

`apps/esp32` で Git 管理対象外のヘッダーを作成する。**認証情報を `platformio.ini`、コマンドライン引数、追跡対象のソース、スクリーンショット、ログに入れない。**

```powershell
New-Item -ItemType Directory -Force private/gateway
Copy-Item include/GatewayConfig.example.h private/gateway/config.h
```

`private/gateway/config.h` をローカルで編集する。

- `MESH_GATEWAY_WIFI_SSID`, `MESH_GATEWAY_WIFI_PASSWORD`: 2.4 GHz の接続先。SSID は32バイト以下、WPA パスフレーズは63バイト以下。空欄は意図的にオープンネットワークを使う場合のみ。キャプティブポータルや企業向け認証の設定には対応しない。
- `MESH_GATEWAY_RELAY_HOST`, `..._PORT`, `..._PATH`: 明示的に信頼する一つの DNS ホスト、TLS ポート、WebSocket のリクエストパス。atproto の再構築には実際の物理 PDS の **Nostr WebSocket** エンドポイントを指定する。論理 PDS の HTTPS URL だけではリレー先にならない。リレー探索、リダイレクト、平文へのフォールバックはない。
- `MESH_GATEWAY_RELAY_CA`: 別経路で取得・検証したリレー用 PEM ルート CA。ESP32 の `WiFiClientSecure` が証明書チェーン、有効期間、DNS ホスト名を検証する。CA が空または無効でも安全でない TLS には切り替わらない。
- `MESH_GATEWAY_PLC_URL`: 任意の Directory の HTTPS ベース URL。末尾のスラッシュは付けない（例: `https://plc.directory`）。DNS ホストとポート **443** のみ対応し、ベースパスは指定できる。`MESH_GATEWAY_PLC_CA` はその PEM ルート CA。両方を空にすると PLC 代理登録は無効になるが、**genesis イベントは未完了のまま保持される**。オフラインで新規作成したスマートフォンの ID は、この設定を行うか、別のオンラインノードで元の genesis を登録する。リレーへのアップロードだけではリポジトリを登録できない。
- `MESH_GATEWAY_NTP_SERVER`: 運用者が選ぶ SNTP サーバー。既定値は `pool.ntp.org`。TLS は実時計が 2026-10-18 以降になるまで待つ。時計同期は非同期。**SNTP は認証されない**ため、悪意あるネットワークは時計や可用性を妨害でき、ハードウェアに裏付けられた信頼時刻ではない。TLS が証明書検証を省略することはない。

`private/` は Git 管理対象外。認証情報はファームウェアに組み込まれ、**保存時に暗号化されない**。ヘッダー、ビルド成果物、フラッシュのバックアップ、実機を保護する。BLE/HTTP による設定エンドポイントはなく、イベントからアップロード先や CA を選ぶこともできない。この環境を有効にすると、前回起動以前の cold を含む **保持中の対応する公開イベントすべて**のアップロードに同意したことになる。非公開情報には使わない。リレーには公開署名済みコンテンツ・メタデータとゲートウェイのネットワークアドレスが、Directory には公開署名済み genesis が渡る。

### ビルド・書込みとパーティション移行

```powershell
uv run pio run -e m5stack-wifi
# 設定の確認と既存フラッシュ・データのバックアップ後に実行する
uv run pio run -e m5stack-wifi --target upload --upload-port COM3
```

**ネットワークを有効にするために `uploadfs` やフラッシュ消去を実行しない。** 先に上記の手順でバックアップする。`partitions-gateway.csv` は通常版の app0/app1 を結合し、`0x10000` から `0x280000` バイトのアプリ領域にする。NVS、LittleFS（`0x290000`）、coredump、辞書（`0x400000`）の位置とサイズは変わらない。予備 OTA スロットはなくなり、OTA アップデーターも提供されない。PlatformIO で対応するファームウェアと**パーティション表を一緒に**書き込み、通常版の小さいアプリ領域にゲートウェイのイメージを書き込まない。通常版に戻す場合も対応する表とファームウェアが必要。以前のアプリや起動選択を復旧する必要がある場合は、パーティション表とフラッシュ全体もバックアップする。承認済み併用版への移行、全領域の二重読み取りと書込み・ハッシュ検証は [GATEWAY-HANDOFF.md](history/GATEWAY-HANDOFF.md) に記録されている。移行中の電源断とダウングレード・ロールバックは未検証。

`m5stack-wifi` は Wi-Fi/TLS のメモリーとタスクスタックを確保するため、**TTS を初期化しない**。別途選択する `m5stack-wifi-tts` は資源の使用を順番に切り替え、評価用音声を有効にする。BLE、ボタン、受信検証、保管、画面プレビュー、heartbeat は残る。`m5stack-wifi` では A/C で音声を再生せず、**A は下記の任意の検証付きダウンロードを要求する**。**B が停止するのは BLE のみ**で、インターネットの搬出・取得は止まらない。公開を停止するには、ローカル設定を取り除いてゲートウェイ版を再ビルド・書込みするか、電源を切る。前者は未完了データを保持したままネットワークを開始しない。通常のオフライン版を書き込むと、上限付きで置換可能な従来のキャッシュ動作に戻る。ヘッダーの欠落、無効な設定、worker の失敗によって、ゲートウェイの保持対象が黙って置換可能になることはない。

認証情報なしのコンパイル確認には `uv run pio run -e m5stack-wifi-check` を使う。`GatewayConfig.check.h` の到達不能な `.invalid` 宛先と意図的に無効な CA を指定し、空でないテスト値により TLS・アップロード・PLC・ingress の経路全体がコンパイラーやリンカーに除去されないようにする。**この環境を実機運用しない。** 運用用ゲートウェイにはローカルヘッダーが必要で、サンプルの空設定では安全側に停止する。

### Basic でのゲートウェイ・評価用音声の併用

2026-10-18 UTC に承認された COM3 への書込み結果と当時のコマンドは [`GATEWAY-HANDOFF.md`](history/GATEWAY-HANDOFF.md) を参照。稼働中の実機は電源を維持する。再起動するとキャッシュは cold として復元される。

```powershell
.venv/Scripts/pio.exe run -e m5stack-wifi-tts
# 全領域のバックアップとローカル Wi-Fi・ingress の明示設定後に実行する
.venv/Scripts/pio.exe run -e m5stack-wifi-tts --target upload --upload-port COM3
```

この環境は **ゲートウェイと同じパーティション表**を使う。`uploadfs`、フォーマット、消去を実行しない。`m5stack-wifi-tts-check` は架空の `.invalid` 設定で全経路をコンパイルするための環境で、実機運用しない。既定のオフライン版と診断版の音声動作は変わらない。

この実機構成の Basic では使用可能な PSRAM がない。**BLE 通信・TLS・音声の同時実行には対応しない。** 当時の失敗と最終的な実機結果は `VERIFICATION.md` に記録されている。

- 同時に動く2つの worker スタックの代わりに、16 KiB の音声・ネットワーク共通 worker を1つ使う。ループのスタックは 12 KiB。SDK の作業バッファーは発話間で保持しない。辞書のマッピングと起動時の SHA-256 検査は変わらない。
- ネットワーク処理や音声の前に、メインループは広告を停止し、物理切断を要求して最後のコールバックを待つ。その後 NimBLE を終了し、32件のイベントキューを解放する。保管中の未完了データは消去しない。再初期化では**同じ起動時 node ID** と B ボタンで指定した有効状態を維持し、処理後に5秒間の BLE 取得時間を設ける。スマートフォンは再接続し、ACK を受けていない転送を再試行する必要がある。C は引き続き協調的に音声をキャンセル・ミュートする。
- BLE が停止し、メインループが許可するまで、音声処理は SDK 資源を確保できない。許可前に保存・読戻しと信頼情報のログ出力を完了する。worker は BLE 再開前に SDK バッファーを解放する。音声処理がキューにある間も新しい TLS 処理の開始を止める。このため、時間制限のある発話中は新たな BLE 受信を意図的に遅らせる。オフライン版の、並行動作で最新の投稿を優先する動作は変わらない。
- 併用版でネットワーク処理を開始するには、**BLE 停止後**に空きが80,000バイト以上、連続領域が18,000バイト以上必要。TLS は別々の 16 KiB レコードバッファーと追加の領域を使い、32 KiB を一度に確保するわけではない。処理ごとの JSON 最悪ケースと空きヒープの既存検査も残る。開始を拒否した処理はデータを捨てずに保持し、待機後に再試行する。
- ingress では TLS の前に、上限付き受信フレームと選択された未加工フレーム1件分を予約する。新しいフレームごとに、厳密な解析・暗号処理の前に TLS を閉じる。同じ有限クエリを再発行し、SHA-256 でバイト単位に一致するフレームを飛ばす。上限は **9接続（一意な8イベントと対応する EOSE）、1接続24フレーム、1回120秒**で、これに上限付きの SDK・接続完了待ちが加わる。対象が変化する、または悪意ある応答が8イベントを超える場合は安全側に失敗し、部分結果を受け入れない。接続間では事前確保した未加工の選択結果とスカラーの並び順だけを保持し、EOSE 後に蓄積した信頼状態で元の選択結果を再検証する。優先取得に失敗した場合は別の通常 REQ を実行する。解析・検証時の既存の空きヒープ下限 70/65 KiB は維持する。証明書、署名、時計、期限、優先度、CRL の検査は省略しない。
- 16スロットすべてがアップロード待ちの場合、明示的なダウンロード要求は、少なくとも1件のアップロード成功確認によって通常の置換が可能になるまでキューに残る。BSAF を受け入れるために未完了の元データを追い出さない。

通常のゲートウェイ版では診断用コンパイラーフラグなしで、**処理量に上限のあるローカル UART** を使える。コマンドは `status`、`download`、`audio on`、`audio off`、`peers *`、`peers <up to 8 node IDs>` で、それぞれ末尾に改行が必要。`download` は A ボタンと同じ、要求をまとめる経路を使う。`audio on/off` はトグルではなく冪等な設定。peer フィルターはローカル試験の隔離用で、**認証ではない**。解析は1ループ最大64バイト、1行最大160バイト。上限を超えると改行まで行全体を拒否する。任意テキストの合成、認証情報の読戻し、ファイルシステム消去、信頼状態のリセットを行うコマンドはない。

COM3 を使用中の他のプロセスを終了してから、補助ツールを使う。このツールはリセットを要求しない。

```powershell
.venv/Scripts/python.exe scripts/gateway_serial.py COM3 --command status --seconds 15 --log .pio/gateway-status.log
.venv/Scripts/python.exe scripts/gateway_serial.py COM3 --audio-on --command download --seconds 300 --log .pio/gateway-download.log --require-ingress-speech
# peer の隔離が必要な場合のみ実行し、試験後は制限を解除する
.venv/Scripts/python.exe scripts/gateway_serial.py COM3 --peers --command status --seconds 10 --log .pio/gateway-peers-restored.log
```

ログの取得時間は1〜300秒、サイズは最大 8 MiB。`--require-ingress-speech` はクラッシュ・再起動を観測した場合、または同一イベントについて **保存済みの完全なイベント ID → TTS 開始 → ゼロでない PCM の生成完了**を確認できない場合に失敗する。対象となる*新規*イベントが必要で、重複イベントは読み上げないのが正常な動作。 `TTS done` は合成とスピーカー API の完了を示すが、聴取品質や SDK の商用ライセンスを保証しない。

`scripts/backup_flash.py` はブートローダーに留まったまま 16 MiB 全体を2回読み、SHA-256 を照合して、実際のパーティション表と領域ごとのハッシュを Git 管理対象外の `private/backups/<UTC>/` に記録する。消去・書込みは行わない。中断した検証は `--resume <既存ディレクトリ>` で再開できる。不一致の場合は両方の読取り結果を残し、成功扱いにしない。ブートローダーへの移行・リセットは動作中のアプリを中断するため、進行中のスマートフォン実機試験ではバックアップ・書込みツールを使わない。

承認済みの実験用設定ツール `scripts/provision_gateway.py` は Windows プロファイルの鍵を表示せずに取得し、ホストの Wi-Fi を切り替えない。公開 PEM ルートはシステムで検証済みの TLS チェーンから取得する。既存の非公開ゲートウェイ設定は上書きしない。運用者が設定した公開ルートは Git 管理対象外の `include/IngressTrust.generated.h` にローカル保存される。署名用秘密鍵を含まず、それだけで ingress が有効になることもない。非公開設定、リンク済みファームウェア、フラッシュ全体のバックアップは機密情報として扱う。

### 搬出・上限・障害時の動作

- Relay・LittleFS・UI はメインループだけが扱う。1件のポインターキューを通じ、不変の24,000バイト以下のコピーを、スタック 16 KiB のネットワークタスク1つに渡す。結果の処理と ID の保持解除もメインループだけが行う。DNS、TLS、HTTP、WebSocket の待機は BLE コールバックやメインループのアップロード監視では行わない。既存の BLE 暗号処理・フラッシュ操作は従来どおり同期処理であり、実測に基づくリアルタイム遅延の保証ではない。
- kind **1/5** の元のフィールドを署名済み CBOR から可逆的に再構築し（型付き version・Base64 タグを含む）、ID と BIP340 署名を再検証して、元のイベントを NIP-01 の `["EVENT", event]` として送信する。期限・hop の書換え、投稿者の置換、タグ追加、再署名は行わない。メッシュで期限切れ、hop を使い切った、または cold のコピーもオンラインへ搬出されることがあるが、メッシュ転送の資格は復活しない。未知の kind は搬出しない。
- その TLS 接続からの、型が正しい `["OK", exactFullId, true, message]` のみをリレーの受理とみなす。ソケット書込みの成功、無関係な OK、false の OK（`duplicate:` を含む）、NOTICE、AUTH チャレンジ、タイムアウト、切断、不正・サイズ超過・分割されたフレーム、結果の欠落は受理とみなさない。未完了の元データは再試行のため永続的に保持する。対応するリレーは1つで、NIP-42 認証と分割メッセージには対応しない。ライブ購読は使わない。任意の有限・検証付き ingress は後述する。
- 暫定 **8173 は Nostr に投稿しない**。署名済みイベントの元の content を変更せず PLC 操作として送信する。ゲートウェイは正規の署名済み DAG-CBOR の SHA-256 から `did:plc` を導出し、`/did/log` を GET する。TLS で認証された **404** の後にだけ POST し、再度 GET する。成功したログ応答の先頭の署名済み genesis が一致した場合だけ、過去の登録を確認したとみなす。既存・不正・不一致のログは上書きしない。設定済み Directory が PLC の ECDSA・署名・操作ルールを検証し、ESP32 は PLC 署名や更新履歴全体をローカルで監査しない。この確認は現在の鍵、PDS エンドポイント、tombstone の状態を保証しない。
- **リレーの受理、PLC 登録、PDS の再構築・公開、AppView の表示はそれぞれ別の状態。** PLC と設定済み PDS・AppView を個別に確認する。投稿は genesis より先にリレーへ届くことがあり、サーバーは保留された再構築を再試行する必要がある（現行 Android のオンライン経路もこれを前提とする）。公開完了の監視機能は実装されていない。
- ゲートウェイは未完了の **1/5/8173** を、メッシュでの期限切れや再起動後も置換から保護する。未確認が16件になると、ノードは削除せず WANT による取得を停止する。ここで ACK されていない投稿の責任は送信元スマートフォンに残る。配送成功は通常のキャッシュ置換を許可するだけで、直ちに削除しない。未知の kind は従来のキャッシュ方針を維持する。`gateway: <id-prefix> accepted (not PDS publication)` はローカルでの観測で、スマートフォンに返す受領通知ではない。30秒ごとのシリアル状態表示には Wi-Fi 接続、時計の準備状態、ヒープ、未完了数、worker の処理中状態を出し、認証情報やイベント本文は出さない。
- 配送チェックポイントは意図的に **RAM のみ**に保持する。再起動後は、以前に受理されたものを含む保持中の対応イベントすべてを再検証・再試行する。これは少なくとも1回の配送であり、厳密に1回ではない。OK やチェックポイントを失うと重複することはあるが、誤って完了扱いにはならない。受理済みイベントが後に置換された場合、その後のリレー側での保持は保証されない。物理的なフラッシュ破損・消去や容量不足による拒否があるため、最終的な配送は保証できず、永久保存用ストレージでもない。
- ラウンドロビン方式により、不正な投稿者やリレーの応答が他の未完了 ID を永久に処理できなくすることを防ぐ。各 ID の再試行間隔は5/10/20/40/80/160/300秒、最大5分で、自動的に諦めない。Wi-Fi 再接続は30秒間隔。処理・接続は同時に1つ。キューには上限があり、再試行記録は16件以下。無制限の送信待ち領域やネットワークから指定された URL は使わない。
- ローカル上限: HTTP ヘッダーは 4 KiB 以下・64行以下・1行512バイト以下。PLC 操作は 8 KiB 以下、ログ本文は 16 KiB 以下・128操作以下・深さ8・共有 JSON アロケーター 48 KiB 以下。HTTP の Content-Length、chunked、接続終了で区切る本文はいずれも上限付きで、不正な長さや競合するフレーミングは安全側に拒否する。アップロード ACK 用 WebSocket の受信は1フレーム 1 KiB 以下・32フレーム以下・分割なし（ingress の上限は別途後述）。アップグレード時の accept チャレンジは必須で、送信フレームのマスクと ping/pong を使う。TLS・接続タイムアウトは5秒、応答の制限時間は PLC リクエストごとに15秒、リレー通信ごとに20秒。基礎となる DNS・SDK のスケジューリングによる追加遅延は worker に生じ、BLE のポーリングには生じない。
- 搬出する JSON の取得は固定フィールドに加えて 64 KiB まで。それ以外は有効な bundle でも、エスケープ後の JSON が上限を超える場合は変更せず未完了のまま保持し、**切り詰めない**。空きヒープ110,000バイトの開始条件と、処理ごとの保守的なエスケープ最悪ケース・連続領域確保の検査により、メモリー不足時は処理を延期する。この110,000バイトの条件は通常のゲートウェイ版のもので、併用版は上記の別の開始条件を使う。併用版で時間を分けて実行した TLS・ヒープ・スタックの実測結果は [GATEWAY-HANDOFF.md](history/GATEWAY-HANDOFF.md) にある。悪意ある入力によるピークと BLE/TLS の同時動作は未検証。大きなイベントや PLC ログ、恒久的に拒否されるデータ、無効な設定、CA の欠落、時計未同期、メモリー不足により、上限付きキャッシュが無期限に保護されたままになることがある。手動で整理する前にエンドポイントを設定・修正するか元データを復旧する。データを自動的に捨てて解除する仕組みはない。

### テストと未検証の実機動作

`test_bundle/test_gateway.py` は実際の codec・プロトコル・転送の補助コードを、模擬ソケット、ASan/UBSan、独立した Python の CBOR・ハッシュ・署名の検証手段とともにビルドする。Android の実際の fixture 8件すべての正確な搬出フィールド、署名済み kind 1/5/8173、改ざん・JSON 展開失敗、PLC DID 導出、正しい・誤った・否定の OK、フレーミング・本文・期限・サイズの上限、マスクを検査する。`test_relay.py` は未完了キャッシュのバックプレッシャー、cold・期限切れデータの保護、読取り失敗、削除せずに保持を解除できること、再起動後の再試行、genesis の保管状態が変わらないことも検査する。これらのテストはイベントを公開せず、実際の PLC 操作も送信しない。

既存の mbedTLS 2.x と ArduinoJson ヘッダーを使い、リポジトリのルートから実行する例。使用した Windows/WSL の依存パスは [`test_bundle/README.md`](test_bundle/README.md) を参照。

```sh
python3 apps/esp32/test_bundle/test_gateway.py
python3 apps/esp32/test_bundle/test_relay.py
python3 apps/esp32/test_bundle/test_main_controls.py --sanitize --diagnostic
```

当初のホストのみのゲートウェイ試験では実機を検証していない。その後に承認された併用版の Wi-Fi・リレー・PLC・ingress・音声の観測結果は `VERIFICATION.md` を参照。同時無線データ通信、実機での CA 拒否、フラッシュ書込み中の電源断、長時間耐久、新規のスマートフォン2台によるオフライン → ESP32 → PDS/AppView の試験は別途必要。運用前に使い捨ての**公開**テスト ID と運用者が承認した宛先で試験する。その際はスマートフォンのネットワークを切り、リレーから同一の完全なイベント ID・署名を独立に取得・検証する。PLC genesis と現在の状態、PDS のレコード・head、AppView もそれぞれ確認する。

### 任意の wire-v1 Nostr → メッシュ取り込み（A ボタン）

以前見送られた署名者リスト案ではなく、[`protocol/0020-wire-v1.md`](../../protocol/0020-wire-v1.md) に基づく実装。**既定では無効で、運用用ルートや実際の JMA Bot DID は同梱しない。** リレー URL、NIP-11 の機能情報、署名者の許可リスト、`source:jma`、組織の表示名、優先度ヒントは信頼の基点にならない。既存の BLE 受信・転送と搬出のルールは変わらず、この厳格な検証は通常のメッシュ投稿全般ではなく、**インターネットからメッシュへの新規取り込み**に適用する。

#### 公開ルートの設定条件（親・サーバー担当者向け）

親・ルートの運用者は `include/IngressTrust.example.h` に従い、**`include/IngressTrust.generated.h`** を生成できる。これは公開の信頼・設定情報で、Wi-Fi 認証情報とは別にレビュー・バージョン管理できる。**ルートや importer の秘密鍵を含めない。** `include/IngressTrust.h` はファイルがあれば読み込み、なければ空・既定で無効の値を使う。

| マクロ | 公開値・設定条件 |
|---|---|
| `MESH_INGRESS_ROOT_PUBKEY` | SEC1 圧縮 secp256k1 ルート1個。小文字16進数66文字。ルート ID はデコードした33バイトの SHA-256。 |
| `MESH_INGRESS_SCOPE_CBOR_HEX` | wire-v1 Scope と一致する正規 `C({orgIds:[...],bsaf:[{botDid,types:[...]}]})` の小文字16進数。 |
| `MESH_INGRESS_SNAPSHOT_URL` | DNS ホストを使う絶対 HTTPS URL。ポート443、認証情報・クエリ・フラグメント・リダイレクトなし。エンドポイントは `application/cbor` と正規 `C({body,sig})` を返す必要がある。 |
| `MESH_INGRESS_SNAPSHOT_CA` | HTTPS 用の公開 PEM X.509 CA。**secp256k1 署名ルートとは用途が異なる。** TLS の識別はスナップショット署名の代わりにならない。 |
| `MESH_INGRESS_MIN_SEQUENCE` | 1〜9007199254740991 の整数。設定するスナップショットのシーケンス下限。エラーやルート変更時に自動で下げない。 |
| `MESH_INGRESS_SELECTED_CBOR_HEX` | ローカル選択範囲: 正規 `C([{botDid,types:[...]}])` の16進数。**承認された実際の JMA Bot DID と元の正確な `type:` 値**を使う。source 名・ハンドルからの推定やワイルドカード展開はしない。非公開のローカルヘッダーに定義することもできるが、二重に定義しない。 |

すべてのリストは wire-v1 が要求する順序と一意性を満たす必要がある。選択範囲は設定したルート Scope の部分集合にする。ルート・選択フィールドは正規 CBOR の16進数であり、**JSON や Base64 ではない**。テスト fixture のルートと Bot は公開の架空テスト ID で、JMA や運用用の認証局ではない。

ローカルの運用者は、Git 管理対象外の `private/gateway/config.h` に次の両方を追加して明示的に有効化する。

```cpp
#define MESH_INGRESS_ENABLED 1
#define MESH_INGRESS_TRUST_CLOCK 1
```

公開ルートファイルには、このローカルの判断を設定しない。公開フィールドの欠落・不正、ローカル選択の欠落、いずれかの有効化設定の欠落、時刻不明、チェックポイントの読取り失敗時には取り込みを実行しない。ルート設定だけでは有効にならない。全経路をコンパイルする `m5stack-wifi-check` は架空の公開 fixture ルート、`.invalid` エンドポイント、無効な TLS CA のみを使い、運用には使用できない。ingress のための PlatformIO ライブラリー追加やパーティション変更はない。**通常のゲートウェイ版は TTS 無効のまま**で、上記の併用版は資源の使用を時間で分ける。

#### 時計と認可の境界

`MESH_INGRESS_TRUST_CLOCK=1` は、実機の SNTP 由来の実時計を**ローカル運用上の前提**として信頼する明示的な設定であり、SNTP を認証するものではない。ingress は今回の起動後に SNTP 同期成功のコールバックを待ち、その時点から単調時計で1時間以内であることも要求する。SNTP のなりすまし、時刻の飛び、セキュアでロールバック耐性のある RTC がない点は制約として残る。TLS と署名付き時刻も時計を独立に証明しない。この前提を置けない場合は既定の false が安全。HTTP Date やイベント時刻を信頼時計として扱わない。通常の搬出ゲートウェイの既存 TLS 時刻チェックは変わらない。

worker は各候補について、次を個別に検証する。

1. 厳密な7フィールドの kind-1 Nostr イベント、ID・BIP340 署名、必須の 0012 タグ・base version・path・created-at・deadline、正規の拡張バイト列。元の文字列、タグ・拡張の順序、署名、型付き Base64 の復元結果を保持する。
2. identity と BSAF original-reference 拡張がそれぞれちょうど1つであること。元の URI は正確な `did:plc` Bot と `app.bsky.feed.post` のレコードキーを示し、CID は正規の CIDv1 dag-cbor/SHA-256 である必要がある。BSAF の6つの基本タグはすべて一意かつ空でなく、type は一意な `type:` タグから取得する。Bot と type は明示的なローカル選択に一致する必要がある。
3. **ルート → importer の直接 leaf 証明書1つのみ。** 中間証明書やより長いチェーンには対応せず、ルートに昇格させない。正確な wire フィールド、ルート ID・鍵、有効な圧縮点、コンパクトな low-S ECDSA 署名、importer ロール、委任なし、正確な単一組織、ルート Scope 内の範囲、366日以下の期間、受信時とイベント時の有効性が必要。未知・自己署名のルートや Scope の拡大は安全側に拒否する。
4. 従来の Android binding の正確な宣言・同意、パディング付き標準 Base64 の ECDSA 署名、**および** BIP340 binding 署名。証明書の DID・atproto 鍵、binding の DID・鍵、イベントの Nostr 鍵、commit の DID はすべて一致する必要がある。正規の v3 commit、CID リンク、コンパクトな low-S ECDSA commit 署名を検査する。これは**現在の PLC 認証、元 Bot のリポジトリ署名検証、MST 包含証明ではない**。
5. イベント作成時ではなく**受信時**に有効な、ルート署名付き完全 CRL スナップショット。worker 完了時、保存前、**永続化後の広告前**に証明書・期限・スナップショットの条件を再確認する。書込み中に期限切れになった場合、完全な bundle は cold のまま残り、広告や accepted コールバックは行わない。

証明書の identity が示すのは **importer** であり、JMA や元 Bot の署名ではない。UI は `Importer checked at ingress` と表示する。シリアルにはルート・leaf の ID、Bot・type、組織、スナップショットの sequence・issuedAt・validUntil・checkedAt と、個別の `authorSignature`、`binding`、`chainScope`、`certificateTime`、`revocation=fresh-as-of` の状態を出力する。失敗した検査や未実施の検査を成功として扱わない。通常のメッシュ受信や再起動では、この観測結果を現在の公式認証バッジとして復元しない。

#### スナップショットの最大受理値と永続化

`/ingress-state` は最新の検証済み署名付きスナップショットと、上限付きの発行者・シリアル番号の記録を保持する。設定済みルート ID に結び付け、正規エンコードし、破損検出用の SHA-256 チェックサムを付ける。**新たに検証したイベントの保存・広告より前に** `/ingress-tmp` に書込み、flush・close、アトミックなリネーム、読戻しを行う。ファイルシステム操作はメインループだけが行い、ネットワーク worker は LittleFS を開かない。チェックポイントの書込みに失敗すると、今回の起動中は新規取り込みを停止する。不正・途中切れ・ルート不一致のチェックポイントを破棄したり、初回インストールとして扱ったりしない。

- sequence が `max(configuredMin, savedSequence)` 未満なら拒否する。同じ sequence では、再署名されていても body のハッシュが一致しなければならない。より大きい sequence では issuedAt が減少せず、両方の失効リストがそれぞれ以前のリストの上位集合である必要がある。期限切れのスナップショットは最大受理値や失効情報を消去しない。有効なルート失効スナップショットはイベントを受け入れずに永続化し、そのルートは自動的に復活できない。
- 同じルートの issuer+serial に異なる証明書 body が現れると、永続的な競合状態になる。シリアルの記録は再起動後も残り、黙って削除されない。有効なルート署名付き証明書の観測は、その後の binding・Scope・時刻・受入れの検査に失敗しても、レジストリーの容量を消費することがある。
- HTTP 応答が成功しても、不正・ロールバック・同一 sequence で内容が矛盾するスナップショットを含む場合は、その取得を拒否する。転送・HTTP 失敗時には、最後の**有効期間内で署名検証済み**のキャッシュを元の validUntil までに限り使用できる。期限切れキャッシュの猶予期間、途中で切れた CRL、署名のない応答、タイムスタンプの修復は認めない。
- チェックサムは破損の検出用であり、フラッシュにアクセスできる攻撃者からの保護ではない。古いフラッシュバックアップの復元は、古い最大受理状態も復元しうる。初期状態を失った場合、プロトコルの minSequence と最大1時間のリプレイ窓の制約が残る。失効、シリアル上限、移行エラーを回避するために状態を削除・リセットしない。ルート交換には別経路で明示的な移行・復旧計画が必要で、イベントに現れた新しいルートを自動的には信頼しない。

#### 要求時のダウンロード・優先度・メッシュでの保管

設定済みのゲートウェイ版では、**A を押す**かメインループで `gatewayRequestIngress()` を呼ぶと、ダウンロード要求を一つにまとめる。実行中のネットワーク処理、Wi-Fi、時計、RAM の準備を待つ。**定期的なバックグラウンド取り込みやライブ購読はない。**

worker は `order:"priority-v1"`、kind `[1]`、`#t:["mosimo.oriom.dev"]`、**limit 8** の `MOSIMO-REQ` を要求する。NIP-11 を信頼せず拡張を試し、非対応・エラー・CLOSED・EOSE 不着・タイムアウト時は新しい TLS 接続で通常の `REQ` を送る。購読 ID は MOSIMO の `esp-priority` と異なる `esp-fallback` を使う。標準 REQ に独自の並び順フィールドはない。部分的・未完了の結果を空の成功として扱わない。いずれも対応する有限の EVENT/EOSE 応答を要求し、購読と接続を閉じる。

返された最大8件の**新規で独立に認可された**イベントから、ローカルで有効な優先度が最高のものを選ぶ。同順位では created_at が最新のもの、さらに同じなら完全 ID が辞書順で最小のものを選ぶ。有効な単一の優先度ヒント 0〜3 は認可後にのみ採用する。ヒントが欠落・重複・スキーマ不正の場合はレベル1を使い、認可に失敗したものにはレベル1を超える優先度もメッシュへの受入れも与えない。リレーが提示する順序、BSAF 値、組織名は権限を高めない。これは**上限付きの返却範囲内で最高**という意味であり、特にフォールバック時やリレーの信頼方針が異なる場合、リレー全体や世界全体で最高のイベントを保証しない。

保持中の完全 ID は飛ばす。既存の最大1,024件・各8バイトの受信済みプレフィックス索引を、追加の保守的な取得フィルターとしてコピーする（最大 8 KiB）。まれにプレフィックスが衝突すると新しい候補が除外されることはあるが、認可や ACK が行われることはない。保管時の最終的な重複排除では引き続き保存済み・ジャーナルの完全なイベント ID を確認する。これにより、無制限の完全 ID 履歴を TLS 用 RAM にコピーせず、既に置換された上位結果が毎回の要求を占め続けることを防ぐ。

検証済みの元イベントは、既存の正規7要素 CBOR ペイロードと通常の CRC32C BPv7 bundle になる（匿名 source、hop 0、age 0、lifetime は24時間以下かつ署名済み配送期限の残り時間以内）。インベントリの更新通知・表示より前に、**通常の検証付き保存・読戻し処理**を行う。緊急の取り込みを受け入れるために、アップロード待ちで満杯のキャッシュを置換しない。未処理の BLE WANT 予約は新しいダウンロードより優先して容量を確保する。スナップショット・シリアルのチェックポイント更新は bundle の保管を意味しない。重複、不正、期限切れ、永続化失敗、容量制限で拒否されたイベントを受信済みとして広告しない。受け入れた公開イベントには既存の通常のアップロード・保持方針が適用される。取得後や再起動後に重複してアップロードされることがあり、搬出完了には引き続き肯定の OK が必要。

#### ESP32 の制限と残る検証

- ルートは1つ、直接 leaf のみ。AIA・OCSP・差分 CRL・ルート探索には対応しない。ルート Scope・選択の上限は8組織（Scope のみ）、8 Bot、Bot ごとに明示された32 type、合計64組。明示的に設定すれば JMA の既存5 type とキキクルの23 type（`did:plc:kxwz5cz7o6g4jlmxh6doyfsm`）に対応する。ワイルドカード、Bot 定義の自動展開、ルート範囲の拡大は行わない。同梱される空・無効の既定値は変わらない。提示するチェーンは1つ、identity/bsafV1 も1つ。任意の BSAF botDefinition メタデータは現在未対応。任意の元レコードやネットワーク上のデータの取得は行わない。
- Nostr JSON フレームは6,144バイト以下、content は2,048バイト以下、タグは64個以下、タグごとのセルは8個以下、拡張は16個以下。さらに、**厳密な JSON/CBOR ツリーごとに128ノード・深さ12・デコード後文字列 12 KiB** の上限がある。スナップショットは CBOR 4,096バイト以下・失効の合計32件以下、identity は CBOR 4,096バイト以下、正規 document・payload は8,192バイト以下。すべての最大値を同時に満たせるとは限らない。非対応や資源上限は拒否となり、切詰めや検証の緩和はしない。
- BSAF の元タグは最大8個×640バイト、langs は3件以下。言語タグは BCP-47 の保守的な部分集合 `language[-script][-region]` に対応する（primary は2〜8英字、任意の script は4英字、任意の region は2英字または3桁）。その他の有効な BCP-47 形式は未対応で、書き換えない。既存の record・source・deadline の時刻は UTC の Z 表記で小数部9桁まで受け付ける。証明書・スナップショットの時刻は正確に秒精度の UTC。うるう秒と 24:00 は ingress では安全側の判断で対応しない。
- 永続的な issuer-serial レジストリーは16件以下。チェックポイントは 8 KiB 以下で、これに 8 KiB 以下の一時ファイル1つが加わる。レジストリーや CRL を自動的に忘却しない。容量を使い切った場合は運用者による計画的な移行が必要で、失効済み・再使用された identity を黙って再受入れしない。
- 処理は1件、既存の worker スタックは 16 KiB。通常の TTS 無効ゲートウェイ版では空きヒープ130,000バイト以上の場合のみ ingress を開始する。併用版は代わりに上記の BLE 停止・開始条件・再接続の規則を使う。両環境ともフレームの解析・検証に追加の空き・連続ヒープ条件（70/65 KiB）がある。中間ツリーは段階的に解放する。通常の TTS 無効版では1回の受信が24フレーム以下・8イベント以下で、分割やバイナリーデータには対応しない。優先取得段階の制限時間は8秒、フォールバックは15秒で、これに上限付きの TLS・HTTP・SDK の待機が**worker 内で**加わる。BLE コールバックやメインループのアップロード監視では待機しない。併用版では上記のとおり最大9接続、1接続24フレーム以下、1回120秒を使う。検証、通常の最終 bundle 検証、フラッシュ、UI 処理には上限があるが、BLE 遅延のハードリアルタイム保証ではない。併用版で時間を分けた TLS・ヒープ・スタックの測定結果は [GATEWAY-HANDOFF.md](history/GATEWAY-HANDOFF.md) に記録されている。BLE と Wi-Fi のデータ通信の同時併用には対応しない。

`test_bundle/wire-v1-combined-server-fixture.json` には、実際のサーバーが発行した28 type 併用証明書、選択した各 type について連続する署名済みイベント各1件、未選択の追加 type を使う否定例も含む。`node test_bundle/generate_server_fixture.mjs --write --combined` / `--check --combined` で再現できる。この隔離されたテスト専用ジェネレーターは一次資料の [キキクル定義](https://raw.githubusercontent.com/osprey74/bsaf-kikikuru-bot/main/bot-definition.json) の23 type 選択肢を固定し、運用用秘密鍵を使わず、同梱ルートを変更しない。Android の実際の検証器も同じ JSON をテスト専用リソースとして使う。両方の実際の受信実装は28件すべてを受け入れ、未選択・拡大された権限を拒否する。当時の測定値は正規 Scope **579 B**、選択範囲 **544 B**、証明書 **1,285 B**、identity **1,852 B**、最大イベント JSON **4,366 B**、メッシュ payload **3,307 B**。既存の Scope・選択範囲の CBOR はそれぞれ 8,192 B 以下、証明書・identity は 4,096 B 以下、JSON は 6,144 B 以下、ツリーは128ノードという上限は変わらない。Scope・選択範囲のテストは Bot ごと32件・合計64件を受け入れ、Bot ごと33件・合計65件を拒否する。架空の64 type を含む完全な identity は別の128ノードのツリー上限を超えるため、引き続き拒否される。提示された最大値を同時に満たせるとは限らない。これは実機・TLS のピークメモリーを測定したことを意味しない。

`test_bundle/test_ingress.py` は公開された**テスト専用**秘密値（root=7、atproto=5、Nostr=3）と、独立した Python の正規 CBOR・low-S ECDSA・BIP340 署名処理を使い、ASan/UBSan 付きで実際の C++ 検証器を実行する。[`test_bundle/wire-v1-fixture.json`](test_bundle/wire-v1-fixture.json) には、親側の Node/Kotlin 実装が使用する正規の証明書・binding・スナップショットのバイト列とハッシュ、証明書・ルートの ID、署名済みイベント、正確な期待メッシュ payload を公開している。Python と ESP32 の一致をテストしている。

2つ目の固定された公開 fixture `test_bundle/wire-v1-node-fixture.json` は、Android 担当者の `bundle-core/src/test/resources/wire-v1.json` から変更せずコピーしたもの（生成元は `@ipld/dag-cbor` / `@noble/curves` を使う `apps/android/wire-v1-fixture.mjs`）。実際の Node 生成コードによる読取り専用の検証と、この C++ 検証器の両方が独立したこの fixture に合格し、正規の証明書・スナップショット・レコード・commit のバイト列、ID・ハッシュ、署名、元イベント、正確なメッシュ payload が一致する。どちらの基本 fixture も運用用の信頼情報ではない。ここでは Android/Kotlin のテスト一式やサーバーランタイム全体を実行したとは主張しない。

**実際のサーバーとの相互運用は `test_bundle/wire-v1-server-fixture.json` でも検証している。** この fixture は実際の `pds/public-information.mjs:createImporterEvent` と、実際の importer・genesis・binding・証明書・スナップショットのエクスポートから再現可能に生成する。連続する2イベントを含み、空でない2件目の `base` は最初の commit を参照する。実際の PDS の `reconstruct` と独立した Python のブロックハッシュ・MST 走査の両方が、1レコード、次いで2レコードのルートを検証する。両イベントは実際の C++ チェッカーで個別にも、永続化された信頼状態を引き継いだ連続実行でも合格する。テスト専用ジェネレーター、ソースの由来、正確な Windows/WSL コマンド、安全上の制限は `test_bundle/README.md` を参照。fixture の MST 監査は実機上の包含証明を意味しない。

否定ケースには binding・鍵・DID・署名の不一致、high-S・DER、未知のルート、非対応チェーン、Scope の拡大、時刻・時計の境界、古い・未来のスナップショット、ロールバック・同一 sequence の矛盾・失効情報の削除、永続的なシリアル競合、未選択の Bot・type、認可なしの優先度を含む。Relay テストではさらにチェックポイント・書込み・読取り・リネームの失敗、永続化中の期限切れ、重複・満杯キャッシュ、広告前の永続化の境界を注入して検証する。いずれのテストも公開イベントの取得、操作の送信、実機への書込みは行わない。

リポジトリのルートから、`test_bundle/README.md` に記載した WSL 依存ファイルを使う当時の実行例。Git Bash では `MSYS_NO_PATHCONV=1` が必要。パスは環境に合わせて変更する。

```sh
MSYS_NO_PATHCONV=1 wsl.exe -d Ubuntu -- timeout 180s env PYTHONPATH=/home/mchiba/.cache/mesh-bundle-host/deps/usr/lib/python3/dist-packages python3 apps/esp32/test_bundle/test_ingress.py --mbedtls-include /home/mchiba/.cache/mesh-bundle-host/deps/usr/include --mbedtls-library /home/mchiba/.cache/mesh-bundle-host/deps/usr/lib/x86_64-linux-gnu/libmbedcrypto.a
```

以前の `test_gateway.py`、`test_relay.py`、`test_bundle.py --host ...`、Base64、main-controls のコマンドも回帰検証に使用できる。ファームウェアは `apps/esp32` から `.venv/Scripts/pio.exe run -e m5stack-wifi-check` を実行すると、実際の認証情報なしで ingress の全経路をコンパイル・リンクする。通常版・ゲートウェイ版・診断版もビルドする必要がある。ホスト・fixture の検査だけでは実際のネットワークやハードウェアを検証できない。

その後に承認された併用版の書込み、実環境での優先ダウンロード、HTTPS 署名付きスナップショットの検証、検証済み保存・音声・BLE ACK、リレーからの独立した読戻しは [GATEWAY-HANDOFF.md](history/GATEWAY-HANDOFF.md) と [VERIFICATION.md](history/VERIFICATION.md) に記録されている。これらは実環境での障害時の通常 REQ フォールバック、SNTP 攻撃への耐性、電源断・ロールバックの安全性、元 Bot の MST 包含、スマートフォン側の受入れ、新規のスマートフォン2台によるオフライン → PDS/AppView 経路を実証するものではない。新たに実機へ導入する前に公開ルート Scope と正確な Bot・type の選択を承認し、共有 fixture をサーバー実装で実行してから、承認された公開テストデータで目的の実経路を検証する。
