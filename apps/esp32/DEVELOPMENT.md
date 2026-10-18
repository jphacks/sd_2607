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

Aは評価テスト文の読み上げ、BはBLE送受信の切替、Cは音声だけのミュート・解除。通信・音声の設定は揮発性で、再起動時はともに有効へ戻る。

**受信→転送の試験中はリセットしない。** UARTを採取する際もDTR/RTSをfalseにする。再起動前の保存データはcold扱いとなり、検証・復元されても広告・転送しない。同じIDの再受信でも復活しない。

## Androidとの実機確認

Androidは [開発ガイド](../android/DEVELOPMENT.md) に従ってビルドする。`demo` と `peer` は別の鍵・保存領域を持つため、Android 1台でも別の送信元・受信先として使える。

1. 両アプリの**オンライン搬出を停止**し、受信側の中継も停止する。投稿前に保存済み投稿と公開設定を確認する。AndroidのPLC登録は搬出OFFでも動作し得る。
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
- 信頼できる時計と電源断中の経過時間がないため、起動中は単調時計とBundle Ageで期限を制限する。再起動後のcold制約を解除して配送期限を延ばさない。
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
