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
- **Aボタン**：固定の日本語評価テスト文を合成・再生する（録音の再生ではない）。
- **Bボタン**：BLE送受信のオン／オフ。オフ時は広告と接続を停止し、オンで探索可能な状態へ戻す。
- **Cボタン**：音声だけをミュート／解除。現在の発話を中断し、待ち行列を破棄する。受信・保存・転送・本文表示は継続する。表示は`Audio muted`／`Audio enabled`。
音量は96/255。通信・音声の状態は画面最上部の`RX/TX`と`AUDIO`で個別に表示する。設定は起動中のみ有効で、再起動時は通信・読み上げともに有効へ戻る。
画面上部56pxには最新の新規受信メッセージのID接頭辞と本文の先頭40文字を表示する。日本語フォントを使い、UTF-8の文字境界で切り、改行・制御文字は空白へ置換する。長い本文には`...`を付ける。絵文字などフォントにない字形の表示は保証しない。
表示するのは署名検証と保存・読戻し照合に成功した新規イベントだけ。同じ保持中イベントの重複や起動時の復元では更新しない。本文をシリアルログへは出力しない。
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
- 完全受信→検証→一時ファイル書込み→flush/close→rename→読戻し照合の後だけACKを送る。ACKは永久保持・最終配送・内容の真実性・DID本人認証の保証ではない。
- 満杯時は、**新規の要求済みBundleが署名検証と一時ファイル保存を完了した場合だけ**、cold/期限切れ→最古の有効キャッシュの順で1件を置換する。inventoryだけでは削除しない。不正な診断用ファイルは勝手に削除しない。保存16件と接続内の受信総数は分離し、同じ接続の17件目以降も受信する。未完了要求は最大16件、接続内の完了ID履歴は最大128件。履歴上限で接続を更新し、履歴を無制限に増やさない。
- 再送は常に保存した原本から生成し、hopと滞在時間を加算してCRCを更新する。署名済みpayloadは変更しない。同じイベントの重複受信では滞在開始時刻を更新しない。
- **信頼できる時計・電源断中の経過時間がない。** 署名付き配送期限の時刻判定はUNKNOWNとし、起動中は単調時計とBundle Ageで制限する。
- **再起動前の保存データは復元・検証するがcold扱いで、広告・転送しない。** 再起動で期限をリセットして無制限に配送しないための保守的な制約。同じIDの再受信でも復活させない。異なる新規イベントは通常どおり受信し、必要ならcoldキャッシュと置換する。
- ファイル保存はArduino FSが公開する範囲で確認する。実電源断を挟む耐障害性試験は未実施。
- 通信路自体は暗号化・相互認証しない。公開投稿のみを扱う。標準の確定値や本番運用向けの完成品とは扱わない。

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
- 待ち行列3件。満杯時は古い未再生分を落とし、新しいものを優先する。直近64件のIDで再発話を抑止する。無制限の履歴や全投稿の必達読み上げは保証しない。
- 発話は通常最大60秒、1秒の再生待ちタイムアウト。SDK呼出しの間で確認する**協調的な制限**であり、SDK自身の処理が戻らなくなった場合の強制中断や、Cボタン直後の無音は保証しない。既にDMAへ送った音の短い末尾が残る場合がある。
- Cで止めた発話は解除しても途中から再開しない。SDKや再生エラーはID・段階・コードだけをログに出し、本文・音声記号列は出力しない。

メッシュ相手が新規投稿を送る状態なら、受信IDと発話完了の非ゼロ音声サンプルを自動照合できる:

```powershell
uv run python smoke_test.py COM3 --mesh --speech --received-speech --log .pio/tts-evaluation-smoke.log
```

### 実測資源

表示＋BLE＋評価TTSの実ビルド: **1,218,257バイト／1,310,720バイト（92.9%）**、静的RAM52,480バイト。
別領域の辞書1,969,776バイトを含めても16 MiB内に収まる。受信・合成・再生中の空きヒープ約100 KBを確認し、本文プレビューも維持できた。
長時間耐久・音質・騒音下の明瞭度は別途確認が必要。SDKを組み込んだビルドのため、今後の機能追加ではアプリ領域の残量にも注意する。

## 他ブランチへの追従

このブランチで`origin/main`と`origin/feature/client`を取り込み、その都度`mndt.md`とAndroidの`Wire.kt`・`GattLink.kt`・`Exchange.kt`を照合する。
新CBOR方式への移行はAndroidとの協調変更が必要。継続的な自動マージ処理は設けていない。
