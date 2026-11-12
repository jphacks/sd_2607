# Mosimo CoreS3 — タッチ操作できる中継ノード

M5Stack CoreS3（ESP32-S3）向けの中継ノードです。中継の中身（BLE・Bundle検証・Negentropy差分照合・保存）は [ESP32版](../esp32/README.md) のソースをそのまま共有し、このディレクトリには CoreS3 用の画面と配線だけを置いています。通信の振る舞い・制限はESP32版と同じです。

## 画面

画面下のタブで4つの画面を切り替えます。

| タブ | 内容 |
|---|---|
| 受信 | 受け取った投稿のカード一覧（この起動後の受信分が先、保存済みの再起動前の分が後）。発信者ごとに色分けし、中継回数と受信からの経過時間を表示します。上下にドラッグ・フリックでスクロールし、タップで詳細（全文、公開鍵、投稿ID、中継回数、投稿者が申告した作成時刻・配送期限）を開きます |
| 状態 | Bluetooth中継の状態（待機・接続中・停止中・エラー）をアニメーションで表示し、保存数（16枠）、配信候補数、受信数、稼働時間、空きメモリを表示します |
| ログ | 中継の動作ログ。保存・接続・拒否などを色分けします。遡って読んでいる間は自動追従を止め、「最新へ」で戻ります |
| 設定 | Bluetooth中継の開始・停止、通知音、明るさ、自動減光（60秒） |

- 他の画面を開いているときに新着投稿が届くと、上部に通知を出し、受信タブに未読数を出します。通知はタップすると投稿を開けます。
- 削除要求（NIP-09）を受け取ると、その著者の対象投稿を画面からも消します。通報で中継ノードが消去した投稿も画面から消します。
- 読み上げはありません。評価用音声SDKはESP32（LX6）向けライブラリしかなく、ESP32-S3では使えないためです。代わりに通知音を鳴らします。
- 設定は揮発性です。再起動するとBluetooth中継・通知音・自動減光はオン、明るさは既定値に戻ります。
- 再起動前に受信して端末に保存されている投稿も、起動時に読み直して一覧に出します（「再起動前に受信」と表示）。周囲の情報を確認する用途のための表示で、中継の扱いはESP32版と同じです。再起動後はcold扱いになり、他の端末へは渡しません。表示できるのは保存領域に残っている分（16枠。削除要求・通報と共有）だけで、置き換えで消えた投稿は再起動後には出ません。

## ビルド・書き込み

Python環境は `apps/esp32` のものを使います。`apps/cores3` で実行します（macOS）。

```sh
(cd ../esp32 && uv sync --locked)
uv run --project ../esp32 pio device list      # CoreS3 は VID:PID 303A:1001
uv run --project ../esp32 pio run -t upload --upload-port /dev/cu.usbmodemXXXX
```

**初回だけ**、保存領域（LittleFS）を作ります。中継は保存領域を自動フォーマットしないため、これをしないと「Relay unavailable」になります。**`uploadfs` は保存済みの投稿を消すので、2回目以降は実行しません。**

```sh
uv run --project ../esp32 pio run -t uploadfs --upload-port /dev/cu.usbmodemXXXX
```

パーティションは `partitions.csv`（16 MiB：アプリ6 MiB、LittleFS 2 MiB、OTAなし）です。CoreS3の出荷時ファームウェアを上書きするため、必要なら先にフラッシュ全体をバックアップします。

```sh
mkdir -p .pio/backups
uv run --project ../esp32 pio pkg exec --package tool-esptoolpy -- esptool.py --chip esp32s3 \
  --port /dev/cu.usbmodemXXXX --baud 921600 read_flash 0 0x1000000 .pio/backups/cores3-full.bin
```

CoreS3のUSBシリアル（USB Serial/JTAG）は、ポートを開くと再起動することがあります。受信→転送の試験中はポートを開き直さないでください。

## 画面の確認（開発用）

`m5stack-cores3-debug` ビルドでは、UART経由で画面のスクリーンショット取得とタップの注入ができます。`demo` は画面だけにサンプル投稿を足すコマンドで、中継・保存・転送には一切入りません。

```sh
uv run --project ../esp32 pio run -e m5stack-cores3-debug -t upload --upload-port /dev/cu.usbmodemXXXX
../esp32/.venv/bin/python scripts/screenshot.py /dev/cu.usbmodemXXXX \
  cmd:demo shot:feed.png tap:160,80 shot:detail.png tap:120,220 shot:status.png
```

タブの中心座標は x=40, 120, 200, 280（y=220）です。

## 構成

- `src/main.cpp` — 中継（`mesh::Relay`・`mesh::Ble`）と画面の配線。すべてArduinoのloopタスク上で動きます
- `src/Ui.h`, `src/Ui.cpp` — タッチUI。PSRAM上の320×240フレームに描いて、変化があるときだけ転送します。接続中は中継処理の時間を確保するためフレーム間隔を広げます
- `platformio.ini` — `../esp32/src` から `main.cpp` と `MeshSpeech.cpp` を除いてビルドします
