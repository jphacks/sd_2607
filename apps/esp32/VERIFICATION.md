# ESP32 / Android BLE 実機検証

## 2026-10-18 UTC: combined gateway + TTS deployed on COM3

Latest deployment, full-flash backup SHA-256, exact BSAF/ordinary event IDs, PCM counts, measured heap/stack, limitations and parent commands are recorded in [`GATEWAY-HANDOFF.md`](GATEWAY-HANDOFF.md). The final bounded run verified real BSAF download/save/speech/BLE ACK, an additional BLE receipt/speech/relay OK, and subsequent `wifi=1 ingress_clock=1 pending=0 busy=0` without resetting. Both final event IDs were independently retrieved from the authenticated public relay and BIP340-verified; BSAF text was additionally compared byte-for-byte with its exact original source URI/CID. The parent's fresh two-phone offline → PDS/AppView test is still separate. Older unverified-hardware notes below describe earlier revisions, not this deployment.

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

## 日本語単体読み上げの評価追加

ユーザーが提供したAquesTalk ESP32 Small 2.4.4 SDKをローカルのみに配置し、`CAqTkPicoF_Init(..., nullptr)`で評価モードを使用。SDK・辞書・リンク済みファームウェアは公開していない。

- 辞書: 1,969,776バイト、`0x400000`の2 MiBパーティションへ配置。書込み検証と起動時SHA-256検査に成功。
- 既存の全領域の位置・サイズを保持。書込み前にパーティション表、メッセージ領域、追加先領域を`private/backups/20261018T015416492696Z/`へ退避した。LittleFSの移動・フォーマットなし。起動時に既存16件を復元できた。
- `uv run python smoke_test.py COM3 --mesh --speech --received-speech --log .pio/tts-evaluation-smoke.log`成功。録音ファイルやPC合成は使わず、受信・検証・保存したイベントIDと、ESP32の合成・再生処理完了IDを照合した。

ログ抜粋（本文は記録しない）:

```text
TTS ready evaluation=1 dictionary_bytes=1969776
mesh: saved e24e017d hop 1 727B
Message preview updated id=e24e017d
TTS start id=e24e017db2d12cb628b83b57e0003782d43d81e89e168733fab62c1b01a09c30
TTS done id=e24e017db2d12cb628b83b57e0003782d43d81e89e168733fab62c1b01a09c30 samples=10816 nonzero=3563 chunks=34
```

別の受信イベントでも53,156サンプル・非ゼロ26,333サンプルなどの生成とスピーカーAPIへの投入を確認。BLE受信・保存・画面更新・合成中にも稼働ログが続き、空きヒープは100,072バイト、メインループの空きスタックは28,120バイトだった。
実ビルドは1,218,257バイト／1,310,720バイト（92.9%）、静的RAM52,480バイト。日本語本文プレビューを削らずに収まった。

追加テスト:

- 実際の`MeshSpeech.cpp`をSDK・M5・タスク境界だけモックしてASan/UBSanで実行。UTF-8分割、待ち行列、重複抑止、ミュート、辞書境界、バッファ参照中の中断を検証。
- SDKインストールとパーティション保護を標準ライブラリーの13テストで検証。テスト素材は実行時生成で、ベンダー素材を含まない。

**聴覚による音質確認、A/B/Cの物理操作を伴う同時再生・中断、長時間耐久は未確認。** `TTS done`は音声生成・再生APIの処理完了を示し、音が実際に聞こえたことやDMAから完全に出力済みであることの証明ではない。
評価版はナ行・マ行が「ヌ」になる。正確な通知・本番運用には使わない。60秒制限とミュートはSDK呼出し間で確認する協調型で、SDKが戻らない場合の強制停止は未実装。

## 新規投稿の受信停止・操作の修正

- 16件を受信した同一接続で17件目の有効な投稿を要求しない不具合を、実際のRelay＋署名付きfixtureで再現（`type()=="want"`の失敗）。保存枠16件と接続内完了履歴を分離し、修正後は17件目・最大128件の接続内履歴を検証。3,596アサーション成功。
- 受信中に次のイベントが追加され続けると`Ble::poll()`が戻らない不具合も再現。1回4イベントに制限し、316アサーション成功。修正版の実機起動・BLE・TTSスモークテストは成功。連続受信中も`alive`ログと保存・表示更新が進んだ。
- 実際の`main.cpp`の73アサーションでCの音声ミュート中も受信・表示が続くこと、Bの通信切替と音声状態が独立することを検証。最終版を実機へ書込み済み。
- ミュート表示は`Audio muted`／`Audio enabled`に限定。音声の聴取成功はユーザーから確認済み。新しいB/C割当の物理操作による確認は別途必要。
- 同一接続の保存件数を数える追加UART採取はユーザー操作で中断され、終了時の件数アサーション結果は未取得。17件目の回帰成功は上記ホストテストの結果として区別する。

## 再接続の再取得と新着音声割込みの修正（2026-10-18 夕方）

発表後の実機報告「別端末がつながると古い投稿を再取得・読み上げ、新着が読まれない」を受けて修正した。

- 原因：16件キャッシュの置換で受信済みIDも失われ、接続内の完了履歴は切断時に消えるため、別peerから同じバックログを繰り返し要求していた。17件受信後の再接続の回帰を修正前に失敗させ、修正後は別peer・再起動でも退避IDを要求しないことを確認。
- キャッシュ置換前に完全なイベントIDとCRCを`/mesh-seen`へ保存・読戻し確認する。履歴保存や置換失敗時は既存Bundleを保護し、新規受理ACKを出さない。履歴だけを根拠に保存済みとして広告・ACKしない。現在のAndroidのinventory末尾から未受信候補を優先する。
- 音声は最新1件の待機枠と世代番号による協調的キャンセルへ変更。新規の有効な投稿が現在の発話を中断する。重複・不正・ミュート中の投入では現在の発話を変えない。単一ワーカーのまま、PCMバッファ返却を確認してから次の発話へ進む。
- 統合後のUbuntu WSL ASan/UBSan：Relay **4,326アサーション成功**、実`MeshSpeech.cpp`の変換中・合成中・PCM参照中・文分割間・最終出力待ちの割込み／重複／ミュート回帰成功、実`main.cpp` **85アサーション成功**。
- 通常版`uv run pio run -e m5stack-basic`成功：アプリ **1,218,205 / 1,310,720 B（92.9%）**、静的RAM **48,392 B**。永続履歴の索引は別途最大約8 KiBのヒープを使う。
- **この修正版の実機書込み・無線での再接続・実音声の割込みは未確認。** PCのUSBシリアル機器一覧が空のため実行できなかった。以前の実機成功記録をこの修正版の結果として流用しない。
- 上限：退避履歴1,024 IDと16件キャッシュが満杯になると新規取得を停止する。履歴を自動消去しない。更新以前に既に失われたIDは復元できず初回のみ再取得される可能性がある。詳しくは[`RELAY-SEEN-HISTORY.md`](test_bundle/RELAY-SEEN-HISTORY.md)。

## 限界・未検証

- Android1台上の2アプリによる論理的な3ノード検証。3台の独立した無線機器、移動・到達距離・電波混雑・長時間耐久は未検証。
- ESP32はperipheral専用で、ESP32同士の直接配送は未実装。
- 再起動前の保存データはcoldとして復元し転送しない。電源断をまたぐ配送再開、信頼できる時計、実電源断に対する保存耐性は未検証・未対応。
- 16件キャッシュは永久保持ではない。従来の置換・再投入の往復に対し、後述の修正でキャッシュ外の永続受信済み履歴を追加した。無制限の履歴・取得は保証せず、退避ID 1,024件の上限時は新規取得を停止する。
- Android側のACKはアプリ内受理を示す。ESP32側の保存ACKと異なり、Androidの暗号化ファイルへの書込み完了より先に送られる場合がある。
- 後から再取得した経路ではhopが増えることがある。最初のhop 2受理の証拠と、その後のキャッシュ状態を混同しない。

詳細なUART・logcat・UI XML・画面画像は検証PCの`apps/esp32/.pio/*e2e*`に保持し、既存投稿を含むためGitへ追加していない。
