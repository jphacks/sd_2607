# M5Stack Basic 開発環境

uvでPlatformIO CLIを管理する。SDK・コンパイラーはPlatformIO既定のユーザーディレクトリ（Windowsでは`%USERPROFILE%\.platformio`）に保存する。`PLATFORMIO_CORE_DIR`の設定は不要。

## セットアップ

Pythonは`.python-version`、Python依存は`uv.lock`、ESP32プラットフォームと表示ライブラリーは`platformio.ini`でバージョンを管理する。以下は`apps/esp32`で実行する。

```powershell
uv sync --locked
uv run pio device list
uv run pio run
```

初回ビルドはSDK・コンパイラー・ライブラリーのダウンロードにネット接続が必要。
`main.py`はuv作成時のPythonサンプルで、ファームウェアには使用しない。ファームウェアの入口は`src/main.cpp`。

## 書き込みと確認

以下の`COM3`は`pio device list`で確認したポートへ置き換える。ポートはPCごとに異なるため設定ファイルには固定しない。
書き込みは現在のプログラムとパーティションテーブルを置き換える。必要な既存プログラム・データは事前にバックアップする。

```powershell
uv run pio run --target upload --upload-port COM3
uv run python smoke_test.py COM3
```

スモークテストは実機をリセットし、16秒間ログを収集する。起動ログが1回、稼働ログが2回以上あり、panic・brownoutの代表的ログがないことを確認する。成功時は`Serial smoke test passed`と表示する。BLE通信の検証は含まない。

継続してログを見る場合は次を実行する。終了は`Ctrl+C`。同じポートを使う別のモニターは閉じておく。

```powershell
uv run pio device monitor --port COM3
```

実機の画面に`ESP32 ready`が表示され、Aボタンを押すと画面とログに`Button A`が表示されることを手動で確認する。

## 初期動作確認結果

- USBシリアル: CH9102 / COM3
- チップ: ESP32-D0WDQ6-V3、revision v3.1
- 実フラッシュ容量: 16 MiB（起動時の`ESP.getFlashChipSize()`で確認）
- ビルド、USB書き込み、起動ログと5秒間隔の稼働ログの取得に成功
- スモークテスト中の空きヒープ: 332,256バイトで安定
- 画面の目視確認・物理ボタン操作は未確認

現時点では`m5stack-core-esp32`の既定4 MiB構成を使用し、実フラッシュ全容量を使うパーティションにはしていない。
BLE・メッシュ通信・投稿の永続保存は未実装。まず基礎動作を確認するためのファームウェア。
