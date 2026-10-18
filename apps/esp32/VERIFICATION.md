# ESP32 / Android BLE 実機検証

2026-10-18、Windows PCにUSB接続したM5Stack BasicとASUSAI2501H（Android API 36）で確認。
Androidの`org.mndt.demo`と`org.mndt.demo.peer`は別のアプリストレージとして使用した。
検証対象はMNDT §9の暫定形式であり、§3の新CBOR交換ではない。

## 最終版での中継成功

経路: **demo → ESP32 → peer**。双方のオンライン搬出は停止し、受信側を切り替えるときに送信側の中継を停止した。Androidの既存鍵・投稿は削除していない。

| 段階 | 観測結果 |
|---|---|
| demoで作成 | イベントID `a22b16e5b1ff…`、hop 0 |
| ESP32で検証・保存 | `mesh: saved a22b16e5 hop 1 813B` |
| peerとESP32のBLE接続 | `hello from 71cba67caf14677d on ble:c:5C:01:3B:13:93:32` |
| peerが未所持を要求 | `inventory 16 from 71cba67caf14677d, want 1` |
| ESP32で送信 | `mesh: sent a22b16e5 hop 2 815B` |
| peerで署名検証・受理 | `bundle 815B from 71cba67caf14677d: accepted a22b16e5` |
| ESP32でACK確認 | `mesh: peer accepted a22b16e5` |
| peer画面 | 同一ID接頭辞、観測hop 2。署名検証済み投稿22→23件 |

Androidでの受理時刻は10:17:37。送信元がESP32のnode IDであることをログで照合し、ほかの近隣ノードからの配送と区別した。
長さ813→815バイトは滞在時間を反映したBundle Age等の再符号化によるもので、署名済みイベントは同一。
最終ハンドオフ中のUARTは約56秒、リセットせずに取得した。

先行検証では逆の役割、**peer → ESP32 → demo**でも成功。イベント`506c252135cb…`をESP32でhop 1保存、hop 2送信し、demoが新規受理した。demoの投稿数19→20件と画面表示を確認。
同じテスト本文を持つ別イベント`5a9bb158…`も存在したため、本文一致ではなくIDで照合した。

## 自動検証

実行して成功したもの:

- `uv sync --locked`
- `uv run pio run --target upload --upload-port COM3`：ビルド・USB書込み・書込みハッシュ検証
- `uv run python smoke_test.py COM3 --mesh`：再起動、BLE初期化、稼働ログ
- BIP340公式32バイトメッセージベクトル15件
- Bundle/転送/異常入力228チェック
- paddingなしBase64回帰16件（うちAndroid Kotlin実装が生成したfixture 8件）
- Relay保存・交換・置換・失敗時ACK抑制：1,527アサーション
- BLE実装の非ゼロ接続ハンドル・分割・再接続・オーバーフロー・送信待ち：215アサーション
- ホストのC++テストはASan/UBSan有効。再実行方法は[`test_bundle/README.md`](test_bundle/README.md)参照
- Android: JDK 21で`:app:assembleDemoDebug :app:assemblePeerDebug :bundle-core:test :mesh-android:testDebugUnitTest`成功

ESP32のログでは空きヒープ約150 KiB、メインループの最小空きスタック約28 KiB。確認したテスト区間でpanic・brownout・再起動なし。
32 KiBのループスタックを設定し、暗号処理をBLEコールバックから分離している。

## 実機で検出し、回帰テストにした問題

1. CBORから復元するBase64のpadding差で署名不一致。Androidのpaddingなし標準Base64へ合わせ、実際のKotlin生成fixtureで検証。
2. SDK設定上は名前上限64でも、初期化ツールのLittleFSイメージは`name_max=32`。長いIDファイル名から短い番号スロットへ変更し、実際の上限を模擬したテストを追加。
3. NimBLE 2.5.1の通知statusに渡される接続情報を信頼すると非ゼロhandleで送信が止まる。`notify()`の成功でSDKキュー投入だけを管理し、Bundle受理ACKと分離。handle 1のテストを追加。
4. 検証中にBLEイベントキューがあふれた際は、不確かなバイト列を続行せず物理切断後にキューを破棄するテストを追加。

## 限界・未検証

- Android1台上の2アプリによる論理的な3ノード検証。3台の独立した無線機器、移動・到達距離・電波混雑・長時間耐久は未検証。
- ESP32はperipheral専用で、ESP32同士の直接配送は未実装。
- 再起動前の保存データはcoldとして復元し転送しない。電源断をまたぐ配送再開、信頼できる時計、実電源断に対する保存耐性は未検証・未対応。
- 16件キャッシュは永久保持ではない。実機でも置換・再投入の往復が観測された。重複抑止は保持中の完全なIDと接続内状態に基づき、無期限の既知ID台帳や公平性は実装していない。
- Android側のACKはアプリ内受理を示す。ESP32側の保存ACKと異なり、Androidの暗号化ファイルへの書込み完了より先に送られる場合がある。
- 後から再取得した経路ではhopが増えることがある。最初のhop 2受理の証拠と、その後のキャッシュ状態を混同しない。

詳細なUART・logcat・UI XML・画面画像は検証PCの`apps/esp32/.pio/*e2e*`に保持し、既存投稿を含むためGitへ追加していない。
