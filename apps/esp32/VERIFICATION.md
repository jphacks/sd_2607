# ESP32 実機検証

実機で確認した事項と、確認していない事項を記録する。手順は [DEVELOPMENT.md](DEVELOPMENT.md#androidとの実機確認) を参照。刷新前のプロトコルでの記録はGit履歴にある。

## 2026-11-12 UTC: プロトコル刷新後（dev.jisaku.mosimo.mesh）

M5Stack Basic（COM3、`m5stack-basic` 通常版）と ASUSAI2501H 上の `dev.jisaku.mosimo.debug`・`.debug2` で確認。書込み前に LittleFS 領域（0x290000, 0x160000）と旧パーティション表を退避し、旧形式の16件は新形式で読めないため LittleFS を初期化した。

- `.debug` → ESP32: HELLO 交換（ESP32 側0/側1 の両方）、Negentropy 照合（`reconciled: peer lacks 0, we lack 2`）、`want` → `BEGIN` → 保存・読戻し → `RESULT` 保存受理。新規投稿 `4a9c41ef…` は `saved 4a9c41ef kind 1 hop 1 731B`、画面表示、`TTS done … samples=25588 nonzero=13232`。
- ESP32 再起動後、保存済み2件を新しい codec で検証・復元（cold）。
- ESP32 → `.debug2`: `.debug2` 側が側0で照合（`have 2 need 1`）→ `want 1` → ESP32 `sent 4a9c41ef hop 2 732B` → `peer accepted 4a9c41ef`（`RESULT` 保存受理）。
- 同一端末の複数アプリが同時に動くと 1 本の BLE リンクを共有し、ESP32 側で CBOR sequence が混ざって切断される。試験では使わないアプリを `am force-stop` で止めた。

## 未検証

- gateway版・gateway＋音声版の実機での搬出・PLC代理登録・資源の時間分割（ビルドとホストテストのみ）。
- ESP32が保持する投稿が多い状態での照合、16件満杯時の置換と受信済み履歴の実機動作。
- 鍵署名チェーン付き投稿、制御イベント（PLC初期操作・失効通知）の実機での中継。
- 独立した複数台の端末での到達距離・移動、長時間動作、電源断を挟む耐障害性。
- `test_repository_integration.py`（実際のKotlin → ESP32 → PDS）の刷新後の再実行。
