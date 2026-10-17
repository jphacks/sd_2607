# 0016: マイコン参加を前提としたメッシュ下位リンク調査

状態: 調査記録・採用案。BLE GATT 等の採用・wire 仕様を確定するものではない。

確認日: 2026-10-17。ユーザーの追加要件は、スマートフォン以外の無線機器もメッシュへ参加できることである。M5Stack は候補機材であり、手元の具体的な型番・搭載 SoC は未確認。ビルド、実機相互接続、性能・メモリ・消費電力の測定は未実施。

## 1. 結論と前回提案の見直し

[0015](0015-nearby-connections-research.md) では Nearby Connections を初期の共通リンク候補として推奨したが、マイコンの直接参加が必須という追加要件の下では、**BLE GATT 上の公開転送プロファイルを共通リンク候補とし、Nearby は任意の追加リンクへ下げる案**を推奨する。まだユーザーが採用した決定ではない。

Nearby 互換を ESP32 に作ることは公開ソースを基にした開発として検討可能だが、公式 SDK の単純な組み込みではない。利用する無線経路を一つに絞っても、その経路の探索・接続交渉・暗号・payload 転送の互換実装を要する。[S1][S2][S3]

「同じメッシュに参加する」と「全機器が Nearby を話す」は分けられる。機器間で共通に対応するリンクがあれば、同じ署名済みイベント・BPv7 Bundle を上位の配送規則で中継できる。異なるリンクだけに対応する2台が直接通信できるという意味ではない。

## 2. Nearby Connections の ESP32 移植

Connections の公開ソースは commit `1c1bcbe8e7c5d40597014789414a6a48c40294a2` を参照した。

| 必要部分 | 移植・互換実装の内容 |
|---|---|
| コア | C++、Abseil、Protocol Buffers、UKEY2 等の依存と実行環境の適合。[S1] |
| RTOS／プラットフォーム | atomic、mutex、condition variable、executor、timer、I/O 等の実装・不要機能の整理。[S2] |
| 無線経路 | Nearby 固有の広告・探索・endpoint 情報・接続経路との整合。BLE の service ID を合わせるだけでは足りない。[S3] |
| 暗号接続 | UKEY2 の接続処理・認証トークン・暗号通信の状態。独自 AES や BLE ペアリングを付けても代替できない。[S4] |
| 転送 | OfflineFrame、payload の識別・分割・制御、切断等の互換処理。[S5] |

確認した公式リポジトリの当該 commit のツリー・platform 選択と公式 embedded ブランチには、ESP32／ESP-IDF／FreeRTOS 向け Connections 実装は見つからなかった。非公開実装や全ての第三者実装の不存在を証明するものではない。[S1][S2][S6]

公開ソースは Apache-2.0 で利用・改変できるが、依存物の条件も別途扱う。RAM／flash の所要量や出荷済み Android GMS・iOS SDK との互換性は未確認であり、ESP32 では絶対不可能とも、短期間で実用可能とも断定しない。災害情報プロトコルの初期実装としては、別の無線 SDK の移植が主作業になり得るため推奨しない。

### 2.1 Embedded SDK は Fast Pair

Google の `Nearby for Embedded Systems` は **Fast Pair** の実装であり、Nearby Connections のマイコン版ではない。公式 embedded ブランチの commit `d207756bd002e4530a2fe40b40bdb8d88b770454` の README は、Fast Pair の Model ID・HAL・`nearby_fp_client_Init()` 等を説明する。これを移植しても Connections の任意データ交換に参加できるわけではない。[S6]

## 3. 代替リンクの比較

| 候補 | スマホ・マイコン間 | 今回の位置付け |
|---|---|---|
| **BLE GATT 上の公開転送プロファイル** | iOS Core Bluetooth、Android Bluetooth API、ESP-IDF の BLE で実装する候補。[S7][S8][S9] | 共通近接リンクの推奨候補。Google SDK 不要。ただしアプリ用のサービス・characteristic・転送規則をこちらで公開する必要がある。 |
| **到達可能な Wi-Fi LAN 上の TCP/IP** | スマホと ESP32 が同じ LAN 等に接続できれば候補。AP のある会場・固定ノード等に向く。[S10] | 高速な追加リンク候補。BPv7 の既存公開標準には TCPCLv4 がある。相手発見や LAN 参加まで解決するものではない。[S11] |
| **Nearby Connections** | スマホ側は SDK があるが、ESP32 の公式 Connections 実装は今回見つからず。[S1][S6] | スマホ同士の任意追加リンク。BLE GATT の当該プロファイルと直接互換ではない。 |
| **ESP-NOW** | Espressif 固有の Wi-Fi action frame を使う。通常の iOS／Android アプリの BLE／TCP API と互換ではない。[S12] | ESP32 区間の任意追加リンク候補。スマホとの接続には別リンクと両方に対応する中継器が必要。 |
| **Bluetooth SIG Mesh／ESP-BLE-MESH** | ESP32 実装あり。スマホは OS API が advertising bearer の要件を満たさなければ GATT Proxy 経由で参加する。[S13][S14] | ネットワーク加入時の provisioning・鍵配布・model 等があり、自由参加の署名付き DTN とそのまま一致しない。共通基盤の第一候補にはしない。 |

LAN 側で TCPCLv4 を選ぶ場合、TLS の実装は RFC 上必須であり、TLS の利用方針・証明書等の扱いも含めて検討する。単に TCP へ長さ付きバイト列を送るだけで TCPCLv4 適合とはしない。TCPCLv4 は今回未採用である。[S11]

BLE GATT は無線・属性アクセスの標準を再利用する案であり、「災害 DTN の転送まで既存の Bluetooth 標準だけで定義済み」という意味ではない。Nearby の内部互換を再実装する代わりに、こちらが必要とする小さな公開プロファイルを定義する。

Bluetooth SIG Mesh の Relay や Friend 機能が、今回の「接触時に保存し、切断後に持ち運んで再転送する」永続配送の代わりになるとは扱わない。ESP-NOW 等も同様に、無線経路と永続配送の責務を分離する。

## 4. 推奨する実装構成案

```text
共通の署名済みイベント・CBOR・BPv7 Bundle
                │
共通の能力交換・所持一覧・取得要求・保存転送
                │
      ┌─────────┴─────────┐
 BLE GATT の共通リンク      LAN等の追加リンク
      │                      │
 iOS / Android / ESP32   両端が対応する場合のみ利用
```

- スマホ↔スマホ、スマホ↔マイコン、マイコン↔マイコンを同じ上位データ形式で扱う。
- BLE 接続ごとに central／peripheral の役割は必要だが、上位の配送に固定の親・子は置かない。接続後は両方向に交換する。両端が peripheral 専用、または central 専用なら直接接続できないため、機器ごとの能力と役割選択を定める。[S8]
- 初期実装の入口は、マイコンを peripheral／GATT server、スマホを central／client とする双方向通信にできる。ただし、これだけではスマホ同士・マイコン同士の相互接続まで完成しない。共通仕様では他の組み合わせの役割選択も必要である。
- 公開する下位仕様はサービス UUID、送受信 characteristic、接続後の版・能力、MTU に応じた分割、流量制御、中断・再送、通信路保護等。具体値・形式は未決定であり、この調査では新設しない。
- マルチホップと永続保持は無線 SDK へ委ねず、上位の MNDT が担う。マイコンが後から別のスマホへ配送するため、接続終了後も必要なデータと重複排除状態を保持する。
- イベントの公開鍵・署名・本文・タグを維持し、リンクをまたぐ際に中継器が著者として再署名しない。BPv7 の可変ブロックは BPv7 の規則に従う。
- 中継専用マイコンへ、投稿者用の DID 発行や MST の生成を必須にする必要はない。これは本人が新しい投稿を作成する機器とは役割が異なる。Nostr 署名・サイズ等の既存の中継要件を省略する提案ではない。

32 KiB のイベントは BLE の一回の attribute 転送に詰めず分割する。BPv7 のヘッダーを加えた Bundle 全体の上限も今後必要である。MTU を固定値と仮定せず、相手・OS の転送能力に合わせる。下位リンク上の分割を、BPv7 の Bundle fragmentation や投稿の複数イベント化と混同しない。[S7][S8]

BLE を直接使っても、iOS の背景・画面消灯・強制終了時の制約は消えない。現行 iOS 26 の Live Activity による追加の BLE 権限も含め、前景での基本動作と背景対応は分ける。詳細と現時点の未確認範囲は [0015 §6](0015-nearby-connections-research.md#6-os-の動作制約) に従う。

## 5. M5Stack の機種と資源

ESP-IDF の対応表では ESP32・ESP32-S3 等に BLE と ESP-NimBLE がある。NimBLE は Bluedroid より heap・flash が小さいと説明されており、初期候補になる。[S9]

M5Stack Basic の公式仕様は ESP32・SRAM 520 KB・flash 16 MB、Stamp-S3 は ESP32-S3・flash 8 MB を記す。これは代表例であり、手元の型番を特定したものではない。[S15][S16]

無線スタック、署名処理、CBOR、保存索引、画面等もメモリを消費するため、SRAM の全量をイベント保持へ使えるわけではない。分割転送・同時受信数制限・flash／SD 等への保存を組み合わせる案が現実的だが、性能・寿命・保存可能件数は未測定。外部無線モジュールを含めた採用時には、対象機器・アンテナ・ファームウェアの国内利用条件も確認する。

## 6. 一次資料

- [S1: Nearby Connections のビルド定義](https://github.com/google/nearby/blob/1c1bcbe8e7c5d40597014789414a6a48c40294a2/connections/BUILD)
- [S2: Nearby platform移植API](https://github.com/google/nearby/blob/1c1bcbe8e7c5d40597014789414a6a48c40294a2/internal/platform/implementation/platform.h)
- [S3: Nearby BLE API](https://github.com/google/nearby/blob/1c1bcbe8e7c5d40597014789414a6a48c40294a2/internal/platform/implementation/ble.h)
- [S4: Nearby暗号接続処理](https://github.com/google/nearby/blob/1c1bcbe8e7c5d40597014789414a6a48c40294a2/connections/implementation/encryption_runner.cc)
- [S5: Nearby wire形式](https://github.com/google/nearby/blob/1c1bcbe8e7c5d40597014789414a6a48c40294a2/connections/implementation/proto/offline_wire_formats.proto)
- [S6: 公式embeddedブランチ・Fast Pair README](https://github.com/google/nearby/blob/d207756bd002e4530a2fe40b40bdb8d88b770454/embedded/README.md)
- [S7: Apple BLEデータ転送・両役割・流量制御](https://developer.apple.com/documentation/corebluetooth/transferring-data-between-bluetooth-low-energy-devices)
- [S8: Android BLE・GATT・役割](https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview)
- [S9: ESP-IDF BLE・チップ対応表・NimBLE](https://docs.espressif.com/projects/esp-idf/en/stable/esp32/api-guides/ble/overview.html)
- [S10: ESP-IDF Wi-Fi API Guide](https://docs.espressif.com/projects/esp-idf/en/v5.5/esp32/api-guides/wifi.html)
- [S11: RFC 9174 — TCPCLv4](https://www.rfc-editor.org/rfc/rfc9174.html)
- [S12: ESP-NOW公式仕様](https://docs.espressif.com/projects/esp-idf/en/stable/esp32/api-reference/network/esp_now.html)
- [S13: ESP-BLE-MESH](https://docs.espressif.com/projects/esp-idf/en/stable/esp32/api-guides/esp-ble-mesh/ble-mesh-index.html)
- [S14: Bluetooth SIG Mesh FAQ・smartphone bearer・provisioning](https://www.bluetooth.com/learn-about-bluetooth/feature-enhancements/mesh/mesh-faq/)
- [S15: M5Stack Basic](https://docs.m5stack.com/en/core/basic)
- [S16: M5Stack Stamp-S3](https://docs.m5stack.com/en/core/StampS3)
