# Moshimo Protocol

[![いつものアプリがもしもの通信網になる](./assets/moshimo_protocol.png)](https://www.youtube.com/watch?v=piaNsc6ilBI)

## 製品概要
### 背景(製品開発のきっかけ、課題等）


### 製品説明（具体的な製品の説明）

![構成](./assets/diagram.png)

### 特長
#### 1. 特長1
#### 2. 特長2
#### 3. 特長3

### 解決出来ること
### 今後の展望
### 注力したこと（こだわり等）
* 
* 

## 開発技術
### 活用した技術
#### API・データ
* [Nostr](https://nostr.com/)：署名付き投稿をリレーへ送信・購読。独自PDSのリレーに加え、Damus・nos.lolを既定の送信先として利用。
* [AT Protocol](https://atproto.com/)：端末で署名したリポジトリをPDSで復元・検証し、`com.atproto.sync` / `com.atproto.repo` のXRPC APIとfirehoseで公開。
* [PLC Directory](https://plc.directory/)：`did:plc`の登録・解決、署名公開鍵とPDSエンドポイントの確認。
* [Bluesky Jetstream](https://github.com/bluesky-social/jetstream)：登録済み発信元の `app.bsky.feed.post` を購読し、`bsaf:v1` タグ付きのBSAF投稿を収集（発信元DIDの設定時のみ有効）。
* [BSAF JMA Bot](https://github.com/osprey74/bsaf-jma-bot)：気象庁の地震・津波・火山関連情報をBSAF形式でBlueskyへ投稿する非公式Bot。情報取得元として利用し、Jetstream経由で投稿を収集。
* [気象庁防災情報XML](https://xml.kishou.go.jp/)：防災情報の原データ。現在はBSAF JMA Botによる投稿を通じて間接的に取得。
* 独自AppView API `dev.oriom.mosimo.getFeed`：Mosimo投稿と収集済みBSAF投稿を取得し、Webクライアントのタイムラインに表示。

#### フレームワーク・ライブラリ・モジュール

##### Androidアプリ
* Kotlin（Android / JVM）、Jetpack Compose・Material 3：投稿・中継操作・診断画面。
* [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines)：非同期処理。
* [dtn7-kotlin](https://github.com/jphacks/sd_2607-dtn7-kotlin)（[フォーク元](https://github.com/NodleCode/dtn7-kotlin)）：BPv7のエンコード・デコード。リポジトリ内の固定スナップショットを使用。
* [Jackson Databind](https://github.com/FasterXML/jackson-databind) / [Jackson CBOR](https://github.com/FasterXML/jackson-dataformats-binary)：JSON・CBORの処理。
* [OkHttp](https://square.github.io/okhttp/)：HTTP / WebSocket通信。
* [secp256k1-kmp](https://github.com/ACINQ/secp256k1-kmp)：NostrのBIP-340署名とatprotoのECDSA署名。
* [Android Keystore](https://developer.android.com/privacy-and-security/keystore) / AES-GCM：鍵・投稿・中継状態の暗号化保存。

##### エッジデバイス
* C++ / [Arduino framework（ESP32）](https://github.com/espressif/arduino-esp32) / [PlatformIO](https://platformio.org/)：ESP32向けファームウェア。
* [M5Unified](https://github.com/m5stack/M5Unified) / [M5GFX](https://github.com/m5stack/M5GFX)：M5Stackの画面・ボタン・スピーカー制御。
* [NimBLE-Arduino](https://github.com/h2zero/NimBLE-Arduino)：BLE GATTによるAndroid端末とのBundle交換。
* [ArduinoJson](https://arduinojson.org/) / [LittleFS](https://github.com/littlefs-project/littlefs)：JSON処理と中継データの永続化。
* [mbedTLS](https://github.com/Mbed-TLS/mbedtls)：署名検証・SHA-256ハッシュ計算。
* [AquesTalk ESP32（Small辞書版）](https://www.a-quest.com/)：受信メッセージの音声読み上げ。

##### オンライン時のアップロードサーバー
* Node.js 24 / JavaScript・TypeScript：独自PDSと内蔵Nostrリレー（[`pds/`](./pds/)）。
* [`nostr-tools`](https://github.com/nbd-wtf/nostr-tools) / [`ws`](https://github.com/websockets/ws)：Nostrイベントの署名検証とWebSocket通信。
* [`@atproto/crypto`](https://github.com/bluesky-social/atproto/tree/main/packages/crypto) / [`@atproto/repo`](https://github.com/bluesky-social/atproto/tree/main/packages/repo)：端末署名済みcommitの検証、MSTの再構築、CARの生成。
* [`@ipld/dag-cbor`](https://github.com/ipld/js-dag-cbor) / [`multiformats`](https://github.com/multiformats/js-multiformats)：DAG-CBORとCIDの処理。
* SQLite（[`node:sqlite`](https://nodejs.org/api/sqlite.html)）/ PostgreSQL（[`pg`](https://node-postgres.com/)）：イベント・リポジトリ・配信履歴の保存。標準構成はSQLite、複数物理ノードでDBを共有する構成ではPostgreSQLを使用。

##### ダウンロードサーバー
* [HappyView](https://happyview.dev/) 2.16.0 / SQLite：AT ProtocolのAppViewとして投稿を索引化・配信（[`appview/happyview/`](./appview/happyview/)）。
* Node.js 24 / TypeScript / [`ws`](https://github.com/websockets/ws)：JetstreamからBSAF投稿を収集するサイドカー（[`appview/bsaf/`](./appview/bsaf/)）。
* [`@atproto/lex-cbor`](https://github.com/bluesky-social/atproto/tree/main/packages/lex/lex-cbor) / [`@atproto/lex-data`](https://github.com/bluesky-social/atproto/tree/main/packages/lex/lex-data)：収集したレコードのCID整合性確認。
* Lua：HappyViewのクエリスクリプトでMosimo・BSAFの統合フィードを提供。

##### サーバーの運用・管理 (もともと個人開発していたもの: [github.com/oriom-dev/podman-cluster-config](https://github.com/oriom-dev/podman-cluster-config))
* [NixOS](https://nixos.org/) / [Nix](https://nix.dev/)：OS設定・サービス・パッケージの構成を宣言的に定義。
* [systemd](https://systemd.io/) / [Caddy](https://caddyserver.com/) / [Podman](https://podman.io/)：サービス管理・HTTPS公開・HappyViewコンテナの実行。

##### web上のクライアント
* HTML / CSS / JavaScript：フレームワークを使わないタイムライン画面（[`apps/web/index.html`](./apps/web/index.html)）。
* Fetch API / DOM API：AppViewから5秒間隔で投稿を取得・表示し、PLC Directoryから表示用ハンドルを解決。

##### データ形式
* Nostrイベント（JSON）：本文・タグ・署名を保持し、atprotoレコードと端末署名済みcommitの復元情報を格納。
* [CBOR](https://www.rfc-editor.org/rfc/rfc8949) / [Bundle Protocol Version 7（BPv7）](https://www.rfc-editor.org/rfc/rfc9171)：署名付きイベントをコンパクトに符号化し、オフライン中継用のBundleとして転送。
* [AT Protocol Lexicon](https://atproto.com/specs/lexicon)：独自投稿レコード `dev.oriom.mosimo.post` とフィードAPIを定義。
* [DAG-CBOR](https://ipld.io/specs/codecs/dag-cbor/spec/) / [CID](https://github.com/multiformats/cid) / [MST](https://atproto.com/specs/repository) / [CAR](https://ipld.io/specs/transport/car/)：atprotoリポジトリの内容識別・整合性検証・同期。
* [BSAF（Bluesky Structured Alert Feed）](https://github.com/osprey74/bsaf-protocol)（`bsaf:v1`）：防災情報などを構造化タグ付きのBluesky投稿として扱う規約。原投稿のレコード・タグ・CID・URIを保持して収集。Mosimo形式への変換やメッシュへの再配信は行わない。
* [CAP（Common Alerting Protocol）1.2](https://docs.oasis-open.org/emergency/cap/v1.2/CAP-v1.2-os.html)：警報の内容・対象地域・有効期間・更新取消を表すXML規格。Mosimo投稿への変換と構造化情報の保持を[仕様草案](./protocol/0015-cap.md)で定義（直接取り込みは未実装）。
* [EDXL-DE（Emergency Data Exchange Language Distribution Element）1.0](https://docs.oasis-open.org/emergency/edxl-de/v1.0/EDXL-DE_Spec_v1.0.pdf)：CAPなどの緊急情報を包み、配布先・公開条件・更新取消を表すXML規格。内包情報の取り込みを[仕様草案](./protocol/0016-edxl-de.md)で定義（直接取り込みは未実装）。
* [気象庁防災情報XML](https://xml.kishou.go.jp/)：地震・津波・火山・気象などの防災情報を記述するXML形式。地震関連電文の構造化情報の保持を[緊急地震速報](./protocol/0017-jma-eew.md)・[地震情報](./protocol/0018-jma-earthquake.md)の仕様草案で定義（現在の取得経路はBSAF JMA Bot経由）。

##### ネットワーク
* BLE（Bluetooth Low Energy）GATT：Android端末間、およびAndroid・ESP32間の近距離通信。
* mDNS / DNS-SD（[Android NSD](https://developer.android.com/develop/connectivity/wifi/use-nsd)）＋TCP：同一LAN上のAndroid端末を発見してBundleを交換。
* [DTN](https://www.rfc-editor.org/rfc/rfc4838)（store-carry-forward）：Bundleを保存して持ち運び、後から接触した端末へ中継。
* HTTPS / WebSocket：PLC・XRPC APIへのアクセス、Nostrリレー・atproto firehose・Jetstreamとの通信。
* [NixOS](https://nixos.org/) / [Nix](https://nix.dev/)：サーバーOSにNixOSを採用し、OS設定・サービス・パッケージの構成をNixで宣言的に定義。
* [systemd](https://systemd.io/) / [Caddy](https://caddyserver.com/) / [Podman](https://podman.io/)：サービス管理・HTTPS公開・HappyViewコンテナの実行。

#### デバイス
* Androidスマートフォン（約4台）：投稿の作成・表示と、BLEや同一LAN経由の端末間中継に使用。オンライン時は保持した投稿をNostrリレーへ送信。
* ESP32（M5Stack Basic Development Kit）：BLEで受信したBundleを保存・中継するエッジデバイス。画面でのメッセージ表示と、AquesTalkによる音声読み上げに使用。
* KAGOYAのVPS：独自PDS・Nostrリレーと、投稿を索引化・配信するHappyView（AppView）の稼働環境。オンラインでの投稿の受け取りとWebクライアントへの配信を担当。

### 独自技術
#### mosimo protocol
災害でインターネットが途絶えても、異なるアプリの間で署名済みの公開情報を端末から端末へ運び、回線が戻った時点で Nostr と atproto にそのまま合流させるプロトコル群です。日本語の RFC 風文書として [`protocol/`](./protocol/) に定義しました（入口は [`protocol/README.md`](./protocol/README.md)、用語は [`CONTEXT.md`](./CONTEXT.md)）。

##### 参加者と責務（[0010](./protocol/0010-core.md)）
* 発信アプリ：鍵・DID・リポジトリを持ち、投稿を作って atproto と Nostr の両形式で署名する。
* 中継アプリ：署名済みイベントを保存・配送し、本文を表示する。著者の本文・拡張は編集しない。
* ゲートウェイ：メッシュから受け取ったイベントを署名を変えずに Nostr リレーへ投稿する。逆向きは信頼する取り込みサーバーの公的情報だけを条件付きで投入する。
* 物理 PDS：Nostr リレーとして受け取り、レコードと操作から MST を再構築して複製する。著者の commit を新たに署名しない。
* 論理 PDS：DID が指す唯一の公開 endpoint として atproto の同期を提供する。
* 対応 AppView：独自 Lexicon の投稿と既存 BSAF 投稿を集めて索引・一覧・地図を提供する。

##### リポジトリ正本モデル（[0001](./protocol/0001-repository-model.md)・[0012](./protocol/0012-bindings.md)）
* 正本は端末：端末が atproto リポジトリにレコードを追加し、MST root を計算して repository v3 commit に署名する。サーバーは検証・復元・複製だけを行い、著者の鍵を持たず、`rev` を振り直さない。
* 投稿単位：1投稿＝1レコード追加＝1署名済み commit＝1 Nostr `kind:1` イベント。バッチ commit は設けない。
* 二重署名：Nostr イベント全体に BIP-340 Schnorr、commit に ECDSA。両方の署名を保ったまま運ぶ。
* 運ぶもの：本文は `content`、それ以外はすべて同じイベントの署名対象タグに入れる。

  | タグ | 内容 |
  |---|---|
  | `["t", "mosimo.oriom.dev"]` | プロトコル識別。一般リレーでも標準の `#t` フィルタで発見できる |
  | `mosimo.oriom.dev:version` | 基本プロトコルの版 `"1"` |
  | `mosimo.oriom.dev:commit` | 署名済み commit の CBOR（padding なしの標準 Base64） |
  | `mosimo.oriom.dev:base` | 適用元 commit の CID。初回は空文字列を明示 |
  | `mosimo.oriom.dev:path` | `dev.oriom.mosimo.post/<TID>` |
  | `mosimo.oriom.dev:created-at` | 作成時刻の原表記（`created_at` は秒未満を切り捨てた Unix 秒） |
  | `mosimo.oriom.dev:delivery-expires-at` | 配送期限 |
  | `mosimo.oriom.dev:ext:<$type>` | 拡張1つにつき1タグ。出現順が `extensions` の配列順 |

* MST は運ばない：MST ノードや包含証明は同梱しない。PDS が検証済みの適用元状態に今回のレコードを足して MST を作り直し、root が commit の `data` と一致することで検証する。標準の同期に必要な MST・CAR は PDS が生成する。適用元が欠けていれば推測せず保留する。
* `base` と `prev`：commit 間の連鎖は独自の `base` で表し、atproto 標準の `prev` の意味は変えない。
* 変換の向き：Nostr → atproto の一方向。atproto レコードに Nostr 署名は持たせない。
* 単独での検証：過去の投稿が欠けていても、Nostr イベントの署名だけで本文を検証・表示できる。「Nostr 署名が正しい」「DID を認証できた」「署名済み atproto 状態に含まれる」は別々の状態として扱う。
* レコードキー：端末が生成する13文字の TID。オフラインでも生成し、直前の値を永続化して時計が戻っても単調に増やす。キー内の時刻は投稿時刻の根拠にしない。
* 識別子の区別：Nostr event ID・record CID・atURI・commit CID・Bundle の識別を別物として扱い、本文一致だけで同じ投稿とは断定しない。

##### メッシュ用 CBOR とサイズ（[0012 §4](./protocol/0012-bindings.md#4-メッシュ用-cbor合意済み)）
* 符号化形式 v1：Nostr イベントを固定7要素の配列 `[1, pubkey, created_at, kind, tags, content, sig]` で表す。
* 可逆な型変換だけ：元の文字列へ完全に戻せる場合に限り、Base64 を byte string に、版 `"1"` を整数にする。`"001"` → `1` のような戻せない変換や、運び方が未知のタグの推測変換はしない。日時文字列は原表記のまま。
* 上限：1イベントの payload は 32 KiB、Bundle 全体は 64 KiB。中継者は署名済みデータを削って収めず、運べなければ見送る。BPv7 の断片化でこの上限を回避しない。
* 三層の独立した版：基本プロトコル（`version` タグ）・拡張（各 `$type`）・メッシュ符号化（配列の先頭）を独立に管理し、拡張の追加で他の版は上げない。

##### MNDT：BPv7 メッシュ配送プロファイル（[mndt.md](./protocol/mndt.md)）
* 署名データと配送状態の分離：本文・期限・拡張・commit は著者署名の対象で中継者は変更できない。hop 数・経過時間・直前ノードは BPv7（RFC 9171）の標準拡張ブロックだけに置く。著者の `area` などを BPv7 拡張ブロックだけに置くこともしない。
* リンク：共通の基本経路は BLE GATT（1サービス・方向別 RX/TX の2 characteristic）。接続済み Wi-Fi LAN を追加の探索・転送経路にする。マイコンが参加できるよう、Nearby Connections は双方が対応するときだけの任意の追加経路とする。
* BLE の役割：スマホと汎用中継ノードは central/peripheral の両方に対応し、資源の限られた機器には片役だけの部分実装を認める。GATT の役割は配送上の親子を意味せず、接続後は双方向に交換する。
* Nearby への切替：BLE で接続・能力交換した後、双方が対応すれば Nearby の別接続を試み、同じ相手だと対応付けられたときだけ切り替える。失敗しても GATT で交換を完結させる。
* 接触時の交換メッセージ：数値の種別を先頭に持つ CBOR 配列。イベント ID や断片は byte string で運ぶ。

  | 種別 | 役割 |
  |---|---|
  | `HELLO=0` | 交換プロトコルの版・受信上限・対応経路・セッション識別 |
  | `INVENTORY=1` | 配送候補のイベント ID 一覧 |
  | `WANT=2` | 取得要求 |
  | `BEGIN=3` | 1件の Bundle 転送開始（イベント ID・全長） |
  | `DATA=4` | Bundle の断片 |
  | `CREDIT=5` | 受信側が許す、Bundle 先頭からの累積バイト数 |
  | `RESULT=6` | Bundle 単位の受理・拒否と理由 |
  | `CANCEL` | 転送の中止 |

* 流量制御と受理：GATT は Write Without Response と Notification を使い、断片ごとの応答は待たずに `CREDIT` で受信側の負荷を抑える。送信方向ごとに転送中の Bundle は1件、制御メッセージには小さな別枠を設ける。受理応答は Bundle 全体を検査・保存した後にだけ返し、GATT 操作の成功を受信完了とみなさない。切断された転送は次の接触で先頭から送り直す。
* 宛先 EID：`dtn://mosimo.oriom.dev/~post`。
* 転送候補の選択：相手が持っていないイベントを候補にする。影響範囲内を優先できるが、ゲートウェイへ運ぶため範囲外への中継も許す。地域不明の投稿を全世界・最高優先度にはしない。著者の優先度はヒントで、資源の上限を解除しない。認可済みの置換を確認した公的情報の旧版は候補から外す。
* 重複と再バンドル：同じ Nostr event ID は Bundle が違っても同じイベントとして扱う。再バンドルで著者の配送期限を延長せず、既知イベントの記録とローカル上限で無制限な再拡散を防ぐ。
* ゲートウェイ：元の公開鍵・時刻・kind・tags・content・署名を変えずに Nostr JSON へ戻して投稿する。BPv7 の経路情報をタグへ追記しない。

##### 時刻の扱い（[0010](./protocol/0010-core.md)）
* 配送期限 `deliveryExpiresAt`：必須。著者が署名する、メッシュで新たに配る期限。中継者は延長できず、満了は削除・撤回を意味しない。既定期間はアプリ・運用者が決め、作成時に絶対時刻で確定する。
* 情報の有効期間 `validity`：任意。省略は「不明」で、無期限に有効とはみなさない。
* BPv7 Lifetime・ローカル上限：ノードごとの資源管理。各ノードは配送期間を短縮できる。
* 時刻表記：UTC・末尾 `Z` の atproto `datetime`。秒未満の精度と原表記を保つ（書き換えると CID が変わる）。
* 時計への依存：期限の実施はベストエフォート。期限を判定できないノードも、期限内と扱わずにローカル上限内で中継できる。

##### ID と鍵（[0000](./protocol/0000-overview.md)・[0001](./protocol/0001-repository-model.md)）
* 発信者の単位：アプリのインストールごとに独立した鍵・DID・リポジトリを持つ。人・端末・アカウントとは同一視しない。
* 鍵：secp256k1 を3本（Nostr 署名・atproto リポジトリ署名・PLC rotation）。曲線は揃えるが秘密鍵は共用せず、すべて端末内に置く。
* DID：`did:plc`。平時は初回起動時に自動で登録し、既定の論理 PDS グループを指定する。
* メッシュ経由の代理登録：オフラインで初回起動した端末は、署名済みの PLC 初期操作を制御イベントとしてメッシュに流す。受け取ったオンラインのノードが、それを変えずに PLC Directory へ提出する。代理者は鍵を持たないため endpoint を書き換えられない。
* 鍵対応宣言：DID・atproto 公開鍵・Nostr 公開鍵の対応を両方の鍵で署名した独立の宣言。通常の受信・中継・表示には要求しない。

##### 拡張（[0011](./protocol/0011-extensions.md)・[0014](./protocol/0014-namespaces.md)）
* 構造：`extensions` は `$type` 付きオブジェクトの配列（Lexicon の open union）。拡張ごとの版フィールドは持たず、互換性を壊す変更は新しい `$type` にする。
* 未知の拡張：改変せずに保持・中継し、配列順・同じ型の複数要素・未知フィールドも保つ。本文は拡張を解釈できなくても表示できる。
* 自由な定義：誰でも自分の名前空間で拡張を定義できる。中央の承認・全実装への型の追加・受信時のスキーマ取得を要求しない。
* 公式の名前空間：`mosimo.oriom.dev` を起点に、Lexicon は `dev.oriom.mosimo.*`、Nostr タグは `mosimo.oriom.dev:*`。

  | 拡張（`dev.oriom.mosimo.defs#…`） | 内容 |
  |---|---|
  | `origin` | 発信端末が任意で公開する地点 |
  | `area` | 内容の影響範囲 |
  | `validity` | 内容の有効期間 |
  | `annotation` | 訂正・補足・追認の対象と関係 |
  | `source` | 原情報・配布容器・取得 URL・変換者・変換規則・原文ダイジェスト |
  | `identity` | 身元証明チェーン |
  | `cap12` / `edxlDe10` / `jmaEew` / `jmaEarthquake` / `bsafV1` | 形式別の公的情報 |

##### 地理情報（[0004](./protocol/0004-geography.md)）
* 発信地点と影響範囲は独立：発信地点は現地にいた証明ではなく、影響範囲は配送優先度のヒントで閲覧制限ではない。省略時は地域不明で、発信地点から補わない。
* 表現：`area` は円と Geohash セルの和集合。多角形・行政区域コード・除外領域は持たない。境界データの搭載や外部照会なしで判定できる。
* 整数表現：atproto は float を持たないため、座標は 10⁻⁶ 度、半径・精度はミリメートルの整数。
* 3値の包含判定：解釈できる要素のどれかに含まれれば「内」、未知の要素が残れば「不明」、全要素を評価できて含まれないときだけ「外」。
* 全被覆の近似：公的情報の多角形・地域コードは、元の領域全体を覆う Geohash セルの和集合へ署名前に変換する。一部を切り捨てる近似はせず、全体を覆えなければ `area` を省略する。近似であることは `area` 自体に明示する。

##### 訂正・削除と Label（[0008](./protocol/0008-posts-and-labels.md)）
* 上書きしない：訂正・補足・追認は、誰でも自分の新規投稿として `annotation`（`relation` と、対象の atproto URI/CID または Nostr event ID）を付けて出す。元著者の訂正と他者の主張は区別し、追認の件数で真偽を決めない。
* 削除：著者が NIP-09 の `kind:5` と、MST から対象レコードを除いた新しい commit の両方に署名する。削除するのは MST のエントリで、過去の commit ではない。全コピーの消去は保証しない。
* Label：本人署名の注釈レコードを正本とし、グループの Labeler が自身の DID を `src` として標準 Label を集約発行する。Bluesky AppView の `queryLabels` が空の `sources` を拒否し、hydration が `src` で絞り込むことをソースで確かめ、閲覧者が少数の Labeler を選べるこの方式を選んだ。Label の `src` を本人や自治体に偽装しない。

##### 身元証明チェーン（[0009](./protocol/0009-identity-attestations.md)）
* 信頼の起点：アプリ既定または利用者が選んだルート公開鍵。受信データ内のルートを自動で信頼しない。
* チェーン：ルート → 中間の証明者 → 組織名・DID・atproto 公開鍵。Nostr 鍵は鍵対応宣言で結び付ける。
* 権限：投稿できる鍵・他者を証明できる鍵・再委任できる鍵を区別し、委任のたびに範囲は狭まるだけ。
* 検証状態：署名と権限・有効期間・失効確認・情報不足による判定不能を分け、署名が正しいだけで公式の発信者と表示しない。身元の確認は内容の真実性を保証しない。

##### 公的情報の取り込み（[0002](./protocol/0002-ingestion-and-trust.md)・[0013](./protocol/0013-public-information.md)）
* 信頼：既定で複数の取り込みサーバーを信頼し、1者の有効な署名で受理する。中継者を変換者として信頼する必要はない。
* 配送同値性：同じ原情報を別のサーバーが取り込んだ場合、署名・event ID・リポジトリは別々に保ち、配送の重複だけを抑える。候補キーは正規化規則の版・情報源 ID・原情報 ID と版・正規化内容のハッシュ。
* 旧版の拡散停止：更新・取消による停止は、利用者が平時から持つルート鍵へのチェーンと対象への権限が確かめられたときだけ。旧版が未着でも置換関係を記録し、後から届いた旧版を再拡散しない。
* 原文：原 XML は取り込み側で保存し、メッシュには単独で読める本文と形式別の拡張を流す。更新は差分ではなく単独で読める新規投稿にする。
* 取り込み手順：取得 → 安全な XML 解析 → 公開可否の判定（限定・訓練・試験は実情報から外す）→ 情報単位への分割 → 本文と拡張の生成 → 変換者としての署名。

##### 形式別の拡張（[0015](./protocol/0015-cap.md)〜[0019](./protocol/0019-bsaf.md)）
* CAP 1.2：解釈した `info` 単位で投稿を作る。`info` のない取消は通知1件。`effective`/`expires` を有効期間に対応させ、`onset` は開始とみなさない。自動取り込みは `Actual`・`Public` の `Alert`/`Update`/`Cancel` に限る。置換は `sender`＋`identifier`＋`sent` の原電文単位で、署名チェーンによる認可を要する。
* EDXL-DE 1.0：容器と内容を分け、各 `contentObject` の公開可否を評価して形式別の規則へ渡す。`targetArea` は配布対象で影響範囲ではない。容器の取消はその容器由来の投稿だけを止め、内包警報の取消とは扱わない。
* 気象庁 緊急地震速報：府県予報区ごとに単独で読める投稿を作り、32 KiB を超える場合だけ細分区域まで分ける。原 XML 内の位置を `selection.path` に、全国の府県一覧を `nationalOverview` に持つ。省いた項目は `reduction.omitted` と本文の両方に明記する。独立した情報単位は `Title`＋`Status`＋`EventID`、最新の判定は `Control/DateTime`。遅れて届いた速報でカウントダウンしない。
* 気象庁 地震観測情報：震度速報・震源・震源震度・長周期地震動を扱う。予測震度と観測震度を区別し、未入電の地点を震度0とみなさない。サイズ超過時は決められた順で低優先の詳細から省く。
* BSAF v1：既存投稿の AppView 収集・mosimo への取り込み・BSAF としての別途公開を別経路に分ける。自動再配信は運用者が明示的に選んだ Bot DID と `type:` の組だけ。`type+value+time+target` の一致は表示上の折りたたみにだけ使い、重複排除のキーにしない。

##### オンライン連携（[0006](./protocol/0006-logical-pds.md)・[0007](./protocol/0007-nostr-relay-profile.md)）
* 物理 PDS は Nostr リレー：NIP-01/09/11 を提供し、独自タグや古い `created_at` だけで拒否しない。端末は一般リレーを経由せず直接投稿・取得できる。NIP-11 に本プロトコル向けの追加情報を載せる。
* リレーの発見：アプリ同梱・キャッシュ・既知 AppView のレコード・Nostr 経由の発見を組み合わせる。一般の著名リレーも併用する。候補一覧の発行者への信頼を、内容や論理 PDS への信頼と混同しない。
* 論理 PDS：DID は1つの論理 PDS（公開 HTTPS endpoint）を指し、その背後で複数の物理 PDS が収集・復元を分担する。物理と論理は多対多。同じ DID を複数ホストから同時に公開する方式は Relay の正規ホスト照合と合わないため採らない。
* 参加権限：収集・検証・ミラーは誰でもできる。正規公開系への参加はグループの承認を要する。
* グループ間共有：他グループが正規公開先の commit を集めたら、小さな通知を送り、相手が必要なデータだけ取りに来る。相手は独立に検証する。
* 外部動作の要件：障害切替後に `seq` を再利用しない、保持範囲はカーソルから欠落なく再送する、再送できない範囲を隠さない、整合しない複製先へ切り替えない。
* 完了状態の区別：Nostr の `OK`、PLC 登録、リポジトリ検証、Relay への取り込み、AppView での表示をそれぞれ別の段階として扱う。

##### 公開範囲
* 公開情報だけを扱う。個別通信・限定公開・暗号化・メディア本体の配送は対象外。全端末への配送、内容の真実性、GPS の真正性、全コピーの削除は保証しない。

#### アプリ
Android アプリ（[`apps/android/`](./apps/android/)）。投稿の作成と署名、メッシュでの中継、回線が戻ったときの送り出しを1つのアプリで行います。
* 端末上の atproto リポジトリ（[`AtRepository.kt`](./apps/android/bundle-core/src/main/kotlin/org/mndt/core/AtRepository.kt)）：TID の生成、DAG-CBOR へのレコード符号化、MST root の計算、v3 commit への low-S ECDSA 署名を端末内で行う。`did:plc` の genesis もここで作り、DID をオフラインで導出する。
* 正規化 CBOR（[`CanonicalCbor.kt`](./apps/android/bundle-core/src/main/kotlin/org/mndt/core/CanonicalCbor.kt)）：float・不定長・tag 42 以外の tag を拒否し、デコード後に再エンコードしてバイト一致を確かめる。
* 署名対象の JSON（[`SignedPost.kt`](./apps/android/bundle-core/src/main/kotlin/org/mndt/core/SignedPost.kt)）：NIP-01 の署名対象 JSON を専用のエンコーダで作り、汎用 JSON ライブラリの出力に依存しない。メッシュ用 CBOR v1 との可逆変換もここ。
* BPv7 Bundle（[`MeshEngine.kt`](./apps/android/bundle-core/src/main/kotlin/org/mndt/core/MeshEngine.kt)）：時計に頼らないよう `creationTimestamp = 0` と Bundle Age を組み合わせ、全ブロックに CRC32C を付ける。受信時は外部パーサーに渡す前に CBOR の形を検査し、BPSec を拒否し、未知ブロックは RFC のフラグに従う。転送は送信ごとのコピーで hop と age を更新し、保存している原本は変えない。
* BLE GATT（[`GattLink.kt`](./apps/android/mesh-android/src/main/kotlin/org/mndt/mesh/GattLink.kt)）：各端末が GATT サーバー・広告・スキャン・central を兼ねる。node ID と「peripheral 専用」フラグは scan response の Service Data に入れる。接続するのは相手が peripheral 専用のとき、または node ID の小さい側で、片方からしか見えない場合は10秒後に大きい側からも接続する。分割は `min(MTU−3, 512)` バイト。
* LAN（[`LanLink.kt`](./apps/android/mesh-android/src/main/kotlin/org/mndt/mesh/LanLink.kt)）：DNS-SD の `_mosimo._tcp` で相手を探して TCP で接続する。
* 接触時の交換（[`Exchange.kt`](./apps/android/mesh-android/src/main/kotlin/org/mndt/mesh/Exchange.kt)・[`MeshCoordinator.kt`](./apps/android/mesh-android/src/main/kotlin/org/mndt/mesh/MeshCoordinator.kt)）：hello → inventory → want → Bundle → ack。配送期限の近いものから送る。全体の状態は1つのアクターで直列に処理する。
* 保持と送信：128件まで保持し、作成者ごとに受信頻度を制限する。自分の投稿は `base` の連鎖を切らないよう、PDS が受理するまで退避しない。リレーごとに再試行とバックオフを持って順に回し、拒否を続けるリレーが他を止めないようにする。ネットワークが使えるようになった通知で即座に送り直す。
* PLC の代理登録（[`PlcDirectory.kt`](./apps/android/mesh-android/src/main/kotlin/org/mndt/mesh/PlcDirectory.kt)）：未登録の端末は genesis を kind `8173` の制御イベントで流す。オンラインの端末は操作ログを確かめ、未登録の場合だけ変えずに提出し、ログの先頭が一致することを確認する。
* 暗号化保存（[`EncryptedStore.kt`](./apps/android/mesh-android/src/main/kotlin/org/mndt/mesh/EncryptedStore.kt)）：鍵・リポジトリの head・送信待ちをまとめて Android Keystore の AES-256-GCM で暗号化する。Keystore の鍵が失われていたら破損として扱い、別の ID を黙って作り直さない。
* dtn7-kotlin の修正（[`third_party/sd_2607-dtn7-kotlin`](./third_party/sd_2607-dtn7-kotlin/)、[jphacks/sd_2607-dtn7-kotlin@b3c329a](https://github.com/jphacks/sd_2607-dtn7-kotlin/commit/b3c329a)）：上流を RFC 9171/9172/9173 と errata で監査し（[`BPV7_AUDIT.md`](./third_party/sd_2607-dtn7-kotlin/BPV7_AUDIT.md)）、次を修正した（[`BPV7_IMPLEMENTATION.md`](./third_party/sd_2607-dtn7-kotlin/BPV7_IMPLEMENTATION.md)）。
  * ブロック処理フラグのビット位置を RFC どおり report `0x02`・delete `0x04`・discard `0x10` に修正（上流は 0x04・0x08・0x02）。
  * 未知の種別のブロックで例外になっていたのを、中身を解釈せずに保持して書き戻すよう修正。
  * CRC を再エンコード結果ではなく受信したバイト列で検証（erratum 8043）。不定長配列の break も計算に含める。
  * `addBlock` の並べ替えと番号衝突を修正し、受信順を保って未使用の番号を割り当てる。
  * payload の数・番号・位置、番号と標準拡張の重複、hop limit の範囲を検証。
  * `ipn` EID を uint64 で符号化し、出力した `ipn:1.2` を読み戻せるよう修正。
  * Bundle Age の二重加算と、作成時刻0のときの期限の符号を修正。
  * Rust `bp7 0.10.7` が生成した Bundle をデコード・再エンコードしてバイト一致させるテストを追加（`WireFormatTest`）。
* 主なコミット：[3bc7efa](https://github.com/jphacks/sd_2607/commit/3bc7efa)・[7f4c419](https://github.com/jphacks/sd_2607/commit/7f4c419)・[4aa227a](https://github.com/jphacks/sd_2607/commit/4aa227a)・[35ba4f5](https://github.com/jphacks/sd_2607/commit/35ba4f5)・[0cf3fc2](https://github.com/jphacks/sd_2607/commit/0cf3fc2)・[fea2b54](https://github.com/jphacks/sd_2607/commit/fea2b54)・[0cefa3f](https://github.com/jphacks/sd_2607/commit/0cefa3f)

#### Lexicon
基本投稿 [`dev.oriom.mosimo.post`](./lexicons/dev/oriom/mosimo/post.json) とフィード取得 [`dev.oriom.mosimo.getFeed`](./lexicons/dev/oriom/mosimo/getFeed.json)。

| フィールド | 定義 | 理由 |
|---|---|---|
| record key | `tid` | 端末がオフラインで単調に生成する |
| `text` | string・必須 | 拡張に非対応でも読める本文。署名後に Unicode・改行を正規化しない |
| `createdAt` | datetime・必須 | UTC・末尾 `Z` で原表記を保つ。Lexicon の `datetime` は時差表記も許すため、この制約は別に検査する |
| `deliveryExpiresAt` | datetime・必須 | 著者が署名するメッシュの配送期限。有効期間・削除とは別 |
| `extensions` | `union`（`refs: []`、`closed: false`）の配列・必須 | 拡張の置き場所。拡張がなければ空配列 |

* トップレベルを5項目に固定：追加の情報はすべて `extensions` に入れる。トップレベルの項目が増えたレコードを、未知の項目を削って版1として扱わない。
* 空の refs の open union：各要素に `$type` を必須としつつ第三者の型を運べる。これで中央の承認なしに拡張を追加できる。
* Nostr 上の自己完結タグ：`mosimo.oriom.dev:ext:<$type>` の値は、`$type` 以外の全フィールドを atproto CBOR にして padding なし Base64 にしたもの。PDS は拡張ごとの専用コードなしに、タグの出現順から `extensions` をバイト単位で組み立て直せる。拡張を追加しても PDS や中継者の更新は要らない。
* 主なファイル：[`lexicons/README.md`](./lexicons/README.md)・[`protocol/0011-extensions.md`](./protocol/0011-extensions.md)・[`protocol/0014-namespaces.md`](./protocol/0014-namespaces.md)

#### nostr+PDS
[`pds/`](./pds/) は鍵を持たない検証・復元専用の PDS で、標準の Nostr リレーを兼ねます。著者の鍵を生成・保持せず、`createAccount` もありません。端末が署名した commit を検証し、atproto の同期プロトコルでそのまま公開します。
* 受け取り：NIP-01（`EVENT`/`REQ`/`CLOSE`）・NIP-09・NIP-11。外部リレーは `{"kinds":[1], "#t":["mosimo.oriom.dev"]}` と `{"kinds":[5]}` で購読し、オフライン中の古い投稿を取りこぼさないよう `since` を付けない。物理 PDS 同士は認証付きの通知を送り、受け手が必要なイベントだけ取りに行って再検証する。
* 取り込みの流れ（[`ingest.mjs`](./pds/ingest.mjs)・[`repository.mjs`](./pds/repository.mjs)）：
  1. event ID と BIP-340 署名を検証。
  2. 基本タグを厳密に復号する。各タグがちょうど1個、Base64 が正規表記、`created_at` が `createdAt` の切り捨てと一致、拡張の CBOR が再エンコードで元のバイト列に戻ることを確かめる。
  3. rev が未来の commit は、Bluesky Relay が許す5分の範囲に入った時点まで保留する（時計の進んだ端末向け。署名は書き換えない）。
  4. 設定した PLC Directory から署名鍵と PDS endpoint を取得。未登録の DID は代理登録を待って最大60秒間隔で再試行。
  5. commit の署名を検証し、`base` が同じ DID で検証済みであることを確かめる（未検証なら保留、rev が増えていなければ無効）。
  6. `@atproto/repo` の MST で root を再計算して `data` と照合し、CAR とブロックを保存。
  7. DID の endpoint が自分のグループで、現在の head の直接の続きなら、head と firehose を同じトランザクションで進める。head は巻き戻さない。
  8. 親が検証済みになったら待っていた子をすぐ再処理し、順不同の到着を解決する。
* firehose（[`sync.mts`](./pds/sync.mts)・[`server.mjs`](./pds/server.mjs)）：`#commit` に `since`・`prevData` と差分 CAR（レコード・MST の covering proof・commit）を載せる。200 op 超または 2 MB 超では commit だけの `#sync` を送る。初回公開時は同じトランザクションで `#identity` と `#account` を出す。cursor を省いた `subscribeRepos` は、Relay が既存リポジトリを発見できるよう seq 0 から送り直す。
* 論理 PDS グループ：`Host` ヘッダーでグループを選び、グループごとに `did:web` のサービス DID と独立した `seq` を持つ。複数の物理ノードで1つの論理 PDS を提供するときは、共有 PostgreSQL と advisory lock で採番を直列化する。
* AppView：[HappyView](./appview/happyview/) が Jetstream から `dev.oriom.mosimo.post` を索引化して `getFeed` を提供する。[BSAF サイドカー](./appview/bsaf/) は HappyView をフォークせずに DB とクエリスクリプトへつなぎ、運用者が許可した DID の `bsaf:v1` 投稿を CID を再計算して確かめたうえで、原レコードのまま `provenance` を付けて mosimo の投稿と並べる。[Web](./apps/web/index.html) は `getFeed` を表示する読み取り専用のタイムライン。
* 主なコミット：[4e24d37](https://github.com/jphacks/sd_2607/commit/4e24d37)・[5528165](https://github.com/jphacks/sd_2607/commit/5528165)・[e37efbb](https://github.com/jphacks/sd_2607/commit/e37efbb)・[c2f1ee7](https://github.com/jphacks/sd_2607/commit/c2f1ee7)・[9916c49](https://github.com/jphacks/sd_2607/commit/9916c49)・[61e1247](https://github.com/jphacks/sd_2607/commit/61e1247)

#### エッジデバイス
M5Stack Basic（ESP32）をスマートフォンなしで動く BLE 中継ノードにしました（[`apps/esp32/`](./apps/esp32/)）。Android と同じ GATT プロファイルで Bundle を受け取り、自分で検証・保存して別の端末へ渡します。
* CBOR 読み取り器（[`MeshBundle.cpp`](./apps/esp32/src/MeshBundle.cpp)）：再帰を使わず、申告された長さでメモリを確保しない。要素数・整数範囲・UTF-8 を厳密に検査し、payload は最短符号化だけを受け付ける。
* BPv7 の検証：CRC-16/X-25 と CRC-32C を受信したバイト列のまま検証する。宛先 EID、ブロック番号の重複、payload の位置、Hop Count と Bundle Age の必須を確かめ、BPSec は拒否、未知ブロックはフラグに従う。
* 署名の検証：NIP-01 の署名対象を JSON を組み立てずに SHA-256 へ順に流し込み、event ID を端末で計算し直す。BIP-340 は mbedTLS の上に実装し、mbedTLS が圧縮点を読めないため `lift_x`（y = (x³+7)^((p+1)/4) の偶数根）を自前で計算する。新しい暗号ライブラリは追加していない。
* 転送：保存した原本から作り直し、Bundle Age と Hop Count のブロックだけを書き換えて CRC を再計算する。他のブロックはバイト列のまま残す。
* BLE（[`MeshBle.cpp`](./apps/esp32/src/MeshBle.cpp)）：peripheral 専用で、そのフラグを広告する。NimBLE のコールバックはキューに積むだけにし、検証・ファイル I/O・表示はメインループで行う。キューが溢れたら切断して最初からやり直し、途中のバイト列で処理を続けない。
* 保存と ack（[`MeshRelay.cpp`](./apps/esp32/src/MeshRelay.cpp)）：LittleFS に16件まで。一時ファイル → flush → サイズ確認 → rename → 読み戻してバイト比較、が全部成功したときだけ `ack` を返す。BLE の `notify()` 成功を受理とみなさない。
* 時計を持たない前提：期限は単調時計だけで判定する。再起動後は電源断の長さが分からないので、保存済みの投稿は再検証しても送り出さない。同じ投稿を再受信しても保持期限は延ばさない。
* 日本語の読み上げ（[`MeshSpeech.cpp`](./apps/esp32/src/MeshSpeech.cpp)）：新しく検証・保存した投稿の冒頭を画面に出し、端末だけで読み上げる。AquesTalk ESP32 の評価版を使っており、ナ行・マ行がすべて「ヌ」になる制限がある。SDK はライセンス上リポジトリに含めず、手元の SDK を [`setup_tts.py`](./apps/esp32/setup_tts.py) で取り込む。約 2 MB の辞書はフラッシュの専用領域に [`provision_tts.py`](./apps/esp32/provision_tts.py) で書き込み、起動時に SHA-256 を確かめて RAM に載せずに参照する。
* ボタン：A は読み上げテスト、B は BLE 送受信の切り替え、C は音声だけのミュート（受信・保存・中継は続く）。
* 主なコミット：[a7dc529](https://github.com/jphacks/sd_2607/commit/a7dc529)・[a34b7cd](https://github.com/jphacks/sd_2607/commit/a34b7cd)・[c2579ff](https://github.com/jphacks/sd_2607/commit/c2579ff)・[ed61d60](https://github.com/jphacks/sd_2607/commit/ed61d60)・[298d47f](https://github.com/jphacks/sd_2607/commit/298d47f)・[a368723](https://github.com/jphacks/sd_2607/commit/a368723)・[5ebc5b9](https://github.com/jphacks/sd_2607/commit/5ebc5b9)
