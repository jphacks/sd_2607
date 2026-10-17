# 0015: Nearby Connections のメッシュ下位リンク採用調査

状態: 調査記録・採用案。Nearby Connections の採用を確定する仕様ではない。

確認日: 2026-10-17。公式資料・公開ソースに基づく。SDK のビルド、端末間通信、性能・消費電力の測定は未実施。ソースは `google/nearby` の commit `1c1bcbe8e7c5d40597014789414a6a48c40294a2` を参照し、出荷済み Google Play services の内部実装と同一とは扱わない。

## 1. 結論

Nearby Connections は、Android・iOS の近接通信を共通 API で扱う初期実装候補として有力である。一方、公開ソースの存在と、独立実装の長期的な wire 互換性を保証する公開標準であることは異なる。

提案は、[MNDT](mndt.md) の接触時交換と BPv7 の下に置く交換可能なリンクとして利用し、Nearby の SDK・service ID・endpoint ID を基本投稿の識別や署名へ組み込まないことである。初期実装の共通リンクに指定するか、Google 非依存のリンクを先に定義するかは未決定。

## 2. オープン性と依存関係

| 観点 | 確認できたこと |
|---|---|
| 公開 API | Android と Swift の公式資料がある。[S1][S2][S3] |
| 公開実装 | `google/nearby` は Apache-2.0。接続コア・Apple 用実装・Swift Package が公開されている。[S4][S5][S6] |
| Android の通常の導入経路 | Google Play services SDK を用いる。GMS 非搭載端末でも同じ手順で動くとは扱えない。[S2] |
| 独立した公開標準 | 今回の確認範囲では、BPv7 の RFC のような、SDK と独立した完全な相互運用仕様・互換性保証は確認できない。非公開実装しかないという意味でもない。 |
| 公開ソースの保守保証 | ルート README は「not an officially supported Google product」と明記する。Google の API 製品と、この OSS 配布物の保守保証を混同しない。[S4] |
| 通信時のインターネット | 近接の広告・探索・接続・転送自体は完全オフラインを想定する。SDK の事前導入・GMS の提供条件とは別問題。[S1][S2] |
| 診断データ | Google Play services Nearby SDK は性能・端末・アプリ package 名等の診断情報を収集し、利用者設定で制御できる。オフライン中継に必要なデータと同一視しない。[S1] |

公開実装を参照・改変できる点は利点だが、OSS をそのまま任意 OS にビルドすれば実用的な無線経路が揃うとは限らない。例えば同じ README は Linux の medium 未実装を記す。[S5]

## 3. 異なるアプリ間の通信

Android API の `serviceId` はサービスを識別する任意文字列であり、アプリの package 名は推奨既定値であって必須の一致条件ではない。広告側と探索側で共通の service ID と strategy を用いる設計が可能である。公開契約と確認した OSS の探索経路には、相手と package 名・bundle ID・アプリ署名を一致させる条件は見当たらない。[S3][S7][S8]

したがって、市の防災アプリ等が異なる package／署名のまま共通サービスを発見する構成は候補になる。ただし、別署名・別アプリの iOS／Android 実機相互接続や、GMS の全版での挙動を保証した調査結果ではない。同一端末で複数の参加アプリが同時動作する場合の競合も未確認である。

採用時の共通 service ID 候補は `dev.oriom.mosimo.mesh` とする。アプリごとの package 名は使わず、接続後の交換メッセージで上位プロトコルの能力・版を識別する案である。この文字列は未採用で、DNS や SDK の登録を実施したものではない。

service ID と Nearby endpoint ID は著者認証には使わない。接続は双方の受け入れを要し、公式は検証コードによる相手確認を推奨する。自動接続のためにこれを省略した場合、近接相手との通信路を認証済みと扱ってはいけない。投稿署名が保護する本文等と、通信相手・所持一覧・経路メタデータの保護は別問題である。自動受け入れの方針は未決定。[S9]

## 4. どの無線方式を使うか

Nearby は単一の無線方式ではなく、広告・探索・初期接続・帯域アップグレードを抽象化する。双方の利用可能な medium をもとに高帯域経路への変更を試みるため、名前だけから「常に Wi-Fi Direct」「常に BLE」等とは決められない。利用可能な経路は、OS・SDK 版・権限・strategy・機器・ネットワーク状態に依存する。[S1][S5][S10]

| 経路 | 調査で確認した範囲 | 災害時の含意 |
|---|---|---|
| BLE | Apple 側に探索・広告・接続の実装あり。Swift API から medium 指定が Core へ渡る。実機相互接続は未確認。[S6][S10] | AP のない環境の候補。速度・同時接続数・背景動作は保証しない。 |
| Wi-Fi LAN | Apple 側に実装あり。[S6] | インターネット不要でも、双方が到達できる LAN は必要。AP のない直接通信とは区別する。 |
| Wi-Fi Hotspot | Apple 側に他端の hotspot へ参加する実装と帯域アップグレード経路あり。公式 iOS 資料も権限・entitlement を説明する。[S11][S12][S13] | iOS が AP を作成できるという意味ではない。参加時の OS 確認等を含め、無操作の高速化は保証しない。 |
| Bluetooth Classic・Wi-Fi Direct | 参照した Apple platform factory は `nullptr`。[S6] | Android 側の対応だけを根拠に iOS との共通経路に数えない。 |
| WebRTC | 公開 Swift Package は `NO_WEBRTC` を指定する。[S14] | iOS 公開パッケージの共通オフライン経路として数えない。 |
| Wi-Fi Aware・AWDL | Aware の Apple factory は `!GITHUB_BUILD` 条件付きで、公開 Swift Package は `GITHUB_BUILD` を指定。AWDL の存在も Android 互換性を証明しない。[S6][S14] | OS の API 名や内部実装だけを根拠に利用可能としない。 |

**資料の不一致:** 同じ commit の `connections/README.md` は Apple 対応を Wi-Fi LAN のみと記すが、公開ソースには BLE・Hotspot の実装がある。README の表だけで BLE 非対応と断定せず、ソースの存在だけで出荷 SDK の動作保証ともしない。採用時には実装対象の版を固定する必要がある。[S5][S6][S10][S11][S14]

## 5. strategy とマルチホップ

- Cluster: M対N の直接接続。今回の初期候補。
- Star: 1対N。端末はその時点で hub または spoke の一方。
- Point-to-Point: 同時に1対1。高帯域志向だが接触の並列性が小さい。

これらは直接接続の形を定めるものであり、Cluster でも A→B→C の自動中継や、切断後の永続保持を提供するわけではない。[S15]

保存・持ち運び・転送、所持一覧、要求、重複排除、配送期限、BPv7 hop 等は本プロトコル側で扱う。Nearby の送信 API 成功も相手の保存完了を意味しない。Android API は送信受け付けと実際の配送を区別する。[S3]

## 6. OS の動作制約

初期実装は、利用者が災害時通信を起動し、アプリが実行可能な間の接触で交換するモデルが安全である。Nearby を使っても iOS の実行制約を回避できるとはしない。[S16][S17]

Apple の従来の背景 BLE 規則では、探索間隔・重複通知・広告の local name・service UUID の扱いが変わる。Nearby の広告形式や発見経路によって、Android からの発見にも影響し得る。[S17]

一方、現行 Core Bluetooth 資料は **iOS 26以降、CBManager を用意して前景中に Live Activity を開始すると、背景でも前景同様の権限を使える場合がある**と説明する。したがって背景 BLE を一律に不可能とも断定しない。ただし Nearby SDK における適用、画面消灯・ロック・強制終了・Live Activity 終了時の挙動や継続時間は今回未確認であり、常時中継の保証には使わない。[S18]

iOS の Bluetooth・ローカルネットワーク・必要時の位置情報や Hotspot 権限、Android の OS 版に応じた Bluetooth・Wi-Fi 等の権限が必要となる。[S2][S13]

## 7. 32 KiB payload と Nearby の転送単位

本プロトコルの32 KiB上限はイベントの CBOR payload 全体であり、BPv7 Bundle はヘッダー等の分だけそれより大きい。[0012 §4.4](0012-bindings.md#44-payload-のサイズ上限合意済み) を変更する必要はない。

Nearby の overview は Bytes payload を「32k」と記す一方、確認日の Android `ConnectionsClient.MAX_BYTES_DATA_SIZE` は **1,047,552バイト**である。公式資料間にも差があり、全 OS・全 SDK に共通する32 KiBまたは約1 MiBの保証として扱えない。[S1][S3]

そのため「1イベント／1 Bundle＝1 Nearby Bytes payload」を仕様に直結させない。採用時には、対応 SDK の上限内に収まる転送フレームへ Bundle を分割する案、または File／Stream payload を使う案を比較し、受信総量・中断時の保持・再組み立てを定める。これは下位リンク上の分割であり、BPv7 の Bundle fragmentation や1投稿の複数イベント化とは別である。Swift API は Bytes・File・Stream を提供するが、異なる payload 種別間の到着順は保証しない。[S19]

## 8. このリポジトリへの提案

### 案A: 上位仕様を独立させ、初期の共通リンクは Nearby Connections（推奨候補）

- 基本投稿・CBOR・BPv7・接触時交換は SDK と独立させる。
- Android は GMS API、iOS は公開 Swift Package を使う初期実装を想定し、対象の版を固定する。
- 共通 service ID と Cluster を使う。具体値・転送フレーム・自動受け入れは採用後の仕様化対象。
- 初版の動作保証は前景での接触を中心にし、背景動作は OS ごとの追加対応とする。
- 別の BLE／LAN 等のリンク実装を後から同じ接触時交換へ接続できるようにする。

利点は初期実装の無線制御負担を下げられること。代償は、Nearby だけを実装したアプリ同士の共通接続には、現実に Google SDK／GMS への依存が残ることである。抽象化だけでこの依存が解消するわけではない。

### 案B: 最初から Google 非依存の共通リンクを定義

例えば BLE GATT 等を基盤にサービス・characteristic・転送フレームを公開する。独立実装と GMS 非搭載機器への対応を優先できる一方、探索・接続・分割・流量制御・通信路保護等の設計と OS ごとの実装負担が増える。BLE を選んでも背景制約は解消しない。これは比較案であり、BLE の具体的な wire 仕様は未調査・未採用。

まず A／B の依存関係に関する方針を決め、その後に接触時交換の一式を確定する。Nearby の内部無線プロトコルを独自再実装することや、全リンクを初版で同時実装することは提案しない。

## 9. 一次資料

- [S1: Nearby Connections overview](https://developers.google.com/nearby/connections/overview)
- [S2: Android get started](https://developers.google.com/nearby/connections/android/get-started)
- [S3: Android ConnectionsClient](https://developers.google.com/android/reference/com/google/android/gms/nearby/connection/ConnectionsClient)
- [S4: google/nearby README・ライセンス](https://github.com/google/nearby/blob/1c1bcbe8e7c5d40597014789414a6a48c40294a2/README.md)
- [S5: Connections README・medium対応表](https://github.com/google/nearby/blob/1c1bcbe8e7c5d40597014789414a6a48c40294a2/connections/README.md)
- [S6: Apple platform factory](https://github.com/google/nearby/blob/1c1bcbe8e7c5d40597014789414a6a48c40294a2/internal/platform/implementation/apple/platform.mm)
- [S7: 探索時のservice ID・PCP照合](https://github.com/google/nearby/blob/1c1bcbe8e7c5d40597014789414a6a48c40294a2/connections/implementation/p2p_cluster_pcp_handler.cc#L579-L617)
- [S8: LAN探索時の照合](https://github.com/google/nearby/blob/1c1bcbe8e7c5d40597014789414a6a48c40294a2/connections/implementation/p2p_cluster_pcp_handler.cc#L960-L989)
- [S9: Swift manage connections](https://developers.google.com/nearby/connections/swift/manage-connections)
- [S10: Swift Advertiser・medium指定](https://github.com/google/nearby/blob/1c1bcbe8e7c5d40597014789414a6a48c40294a2/connections/swift/NearbyConnections/Sources/Advertiser.swift)
- [S11: Apple Hotspot実装](https://github.com/google/nearby/blob/1c1bcbe8e7c5d40597014789414a6a48c40294a2/internal/platform/implementation/apple/wifi_hotspot.mm)
- [S12: 帯域アップグレード経路](https://github.com/google/nearby/blob/1c1bcbe8e7c5d40597014789414a6a48c40294a2/connections/implementation/bwu_manager.cc#L96-L158)
- [S13: Swift get started・権限](https://developers.google.com/nearby/connections/swift/get-started)
- [S14: 公開Swift Packageとビルド条件](https://github.com/google/nearby/blob/1c1bcbe8e7c5d40597014789414a6a48c40294a2/Package.swift)
- [S15: strategies](https://developers.google.com/nearby/connections/strategies)
- [S16: UIKit background execution（現行資料）](https://developer.apple.com/documentation/uikit/extending-your-app-s-background-execution-time)
- [S17: Core Bluetooth background（アーカイブ資料）](https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html)
- [S18: Core Bluetooth（現行資料・iOS 26 Live Activity）](https://developer.apple.com/documentation/corebluetooth)
- [S19: Swift exchange data](https://developers.google.com/nearby/connections/swift/exchange-data)
