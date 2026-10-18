# M5Stack Basic BLEメッシュ中継ノード（実験実装）

Androidの公開・署名済み投稿をBLEで受信し、検証・保存して、後で接続するAndroidへ転送する。
uvでPlatformIO CLIを管理する。SDK・コンパイラーは既定のユーザーディレクトリ（Windowsでは`%USERPROFILE%\.platformio`）を使用する。`PLATFORMIO_CORE_DIR`の設定は不要。

## 対応範囲

**現在のAndroidアプリと相互接続するための暫定互換実装**。正本は[`protocol/mndt.md`](../../protocol/mndt.md)。

- 対応するwire形式は同文書**§9のAndroid実験形式**。JSON `hello / inventory / want / ack`、4バイトbig-endian長＋BPv7 Bundle。
- §3の新しいCBOR交換、連番付きGATTフレーム、`BEGIN / DATA / CREDIT / RESULT`には**未対応**。両形式を同じ接続で混在させない。§9のUUIDも採用済み標準値とは扱わない。
- ESP32は**peripheral / GATT server専用**の部分実装。広告で専用フラグを示し、Androidがcentralとして接続する。接続後の転送は双方向。同時接続は1台。
- 2台のこのESP32ファームウェア同士では接続できない。central側、LAN、Nearby、オンライン搬出、ESP32での投稿作成は未実装。

## 構成

| ファイル | 責務 |
|---|---|
| `src/MeshBle.cpp` | 広告・GATT・有界キュー・フレーム再組み立て・切断時の破棄 |
| `src/MeshRelay.cpp` | 所持情報・取得要求・保存受理応答・LittleFSキャッシュ |
| `src/MeshBundle.cpp` | BPv7/CRC、CBORイベント、Nostr IDとBIP340署名検証、hop/age更新 |
| `src/main.cpp` | M5Stack表示・ボタン・メインループ |
| `test_bundle/` | 暗号・異常系・保存・BLE境界のホストテストとKotlin実装由来fixture |

BLEコールバックではデータを有界キューへコピーするだけにし、暗号検証・ファイル操作・表示はメインループで実行する。

## セットアップ・ビルド

Pythonは`.python-version`、Python依存は`uv.lock`、SDKとC++ライブラリーは`platformio.ini`で管理する。以下は`apps/esp32`で実行する。

```powershell
uv sync --locked
uv run pio device list
uv run pio run
```

初回はSDK・コンパイラー等のダウンロードにネット接続が必要。
`main.py`はuv生成時のPythonサンプルで、ファームウェアには使用しない。
実機はESP32-D0WDQ6-V3 revision v3.1、フラッシュ16 MiB。ただしボード定義既定の4 MiBパーティション構成を使用する。

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
uv run python smoke_test.py COM3 --mesh
```

スモークテストは**実機をリセット**し、30秒間、起動1回・稼働ログ2回以上・BLE初期化を確認する。端末間の配送テストではない。
再起動は下記のcold保存方針に影響するため、中継中のデータをそのまま別端末へ渡すテストの途中では実行しない。

```powershell
uv run pio device monitor --port COM3
```

ログ監視の終了は`Ctrl+C`。ポートを使うほかのモニターは閉じる。Aボタンは表示・ログ確認、Bボタンは880 Hzを250 ms再生（音量64/255）。
M5Stackの画面ログは下端で自動スクロールし、最新行を表示する。画面から流れた過去のログはシリアルモニターで確認する。

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

- Bundleは最大24,000バイト、最大16件・384,000バイト＋一時ファイル1件。ネスト・タグ・制御メッセージ・キュー・セッションの通信量にも上限を設ける。
- 検証済みイベントIDで重複排除し、CRC・Nostr ID・BIP340署名・宛先・hop・Bundle Age・Lifetime・署名付き配送期限の構文を検査する。
- 完全受信→検証→一時ファイル書込み→flush/close→rename→読戻し照合の後だけACKを送る。ACKは永久保持・最終配送・内容の真実性・DID本人認証の保証ではない。
- 満杯時は、**新規の要求済みBundleが署名検証と一時ファイル保存を完了した場合だけ**、cold/期限切れ→最古の有効キャッシュの順で1件を置換する。inventoryだけでは削除しない。不正な診断用ファイルは勝手に削除しない。1セッションの新規保存は最大16件。
- 再送は常に保存した原本から生成し、hopと滞在時間を加算してCRCを更新する。署名済みpayloadは変更しない。同じイベントの重複受信では滞在開始時刻を更新しない。
- **信頼できる時計・電源断中の経過時間がない。** 署名付き配送期限の時刻判定はUNKNOWNとし、起動中は単調時計とBundle Ageで制限する。
- **再起動前の保存データは復元・検証するがcold扱いで、広告・転送しない。** 再起動で期限をリセットして無制限に配送しないための保守的な制約。同じIDの再受信でも復活させない。異なる新規イベントは通常どおり受信し、必要ならcoldキャッシュと置換する。
- ファイル保存はArduino FSが公開する範囲で確認する。実電源断を挟む耐障害性試験は未実施。
- 通信路自体は暗号化・相互認証しない。公開投稿のみを扱う。標準の確定値や本番運用向けの完成品とは扱わない。

## 他ブランチへの追従

このブランチで`origin/main`と`origin/feature/client`を取り込み、その都度`mndt.md`とAndroidの`Wire.kt`・`GattLink.kt`・`Exchange.kt`を照合する。
新CBOR方式への移行はAndroidとの協調変更が必要。継続的な自動マージ処理は設けていない。
