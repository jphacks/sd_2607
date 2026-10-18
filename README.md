# Mosimo Protocol

[![いつものアプリがもしもの通信網になる](./assets/mosimo_protocol.png)](https://www.youtube.com/watch?v=piaNsc6ilBI)

## 製品概要
### 背景(製品開発のきっかけ、課題等）

災害によって通信設備が被害を受けると、普段利用しているインターネット経由の連絡や情報共有が難しくなります。Bluetoothなどを使って端末同士で通信する技術は既にありますが、専用アプリを事前にインストールしていなければ利用できないという課題があります。

そこで私たちは、普段使っているアプリに災害時の通信機能を組み込み、日常の利用がそのまま「もしも」の備えになる仕組みを考えました。

### 製品説明（具体的な製品の説明）

Mosimo Protocolは、「いつものアプリが、もしもの通信網になる。」をコンセプトにした、アプリに組み込める共通の通信プロトコルです。

インターネットに接続できない状況でも、Bluetoothなどで近くの端末と情報を交換し、端末から端末へ情報を中継します。同じプロトコルに対応した異なるアプリ同士でも情報を受け渡せるため、普段使っているアプリを通じて通信網に参加できます。

さらに、情報がインターネットに接続できる端末まで届けば、AT Protocolを使ってクライアントで閲覧できるようにするなど、圏外の端末とオンラインのネットワークをつなぐことができます。

![構成](./assets/diagram.png)

### 特長

#### 1. 通信圏外の壁を越える！

> [!IMPORTANT]
> インターネットがなくても、Bluetoothなどで端末同士が情報を受け渡し、人の移動とともに運びます。

大きな災害が発生した場合、通信インフラが破壊され、スマートフォン同士の通信もできなくなることが過去に何度も発生しています。発電所のトラブルでブラックアウトが置きた事例(北海道胆振東部地震)もあります。

このような状況下では、情報共有が最大の課題になります。能登地震の際も避難所の情報が届かず、混乱が発生しました。Mosimo Protocolは、スマートフォン同士が直接通信することで、通信圏外でも情報を受け渡すことができます。

Mosimo Protocolは、スマートフォン同士が直接通信することで、通信圏外でも情報を受け渡すことができます。これにより、災害時でも情報を共有することができ、混乱を最小限に抑えることができます。


#### 2. アプリの壁を越える！

> [!IMPORTANT]
> 共通のプロトコルを使うことで、異なるアプリ同士が直接情報を交換できます。

メッシュネットワークで情報を共有するというこれまでも広く試みられてきましたが、どれも専用アプリが必要でした。

Mosimo Protocolは、既存のアプリに組み込むだけで、異なるアプリ同士でも情報を交換できるようになります。
プロトコルもオープンなので、誰でも自分のアプリに組み込むことができます。

これにより過去に類を見ないユーザー密度を実現し、実用的なメッシュネットワークを構築することができます。


#### 3. ネットワークの壁を越える！

> [!IMPORTANT]
> スマートフォン同士の通信だけでなく、既存のメッシュネットワークやインターネットとも接続します。

たとえインターネットに繋がっても、サーバーがダウンしてしまえば、情報を受け取ることはできません。Mosimo Protocolは、既存のメッシュネットワークやインターネットとも接続することで、情報を受け取ることができます。

具体的には、
* At Protocolを用いて各ユーザーの投稿を整理して、複数のサーバーに分散して蓄積できる
* nostrを用いて、専用サーバーに直接アクセスできなくてもリレーを介して情報を送受信できる
* AT ProtocolのAppViewが情報を収集・整理することで、遅い接続でも効率的にデータを受け取れる


### 解決出来ること

災害でインターネットが途絶えた際にも、近くの端末同士で情報を受け渡すことで、連絡や地域の状況共有を支えます。

この仕組みを日常的に利用されるアプリに組み込むことで、災害用の専用アプリをあらかじめ入れてもらうという導入の壁を下げます。対応するアプリが増えるほど、アプリの違いを越えて情報を中継できる端末が増え、災害時に利用できる通信経路を広げられます。

### 今後の展望

まずは、私たちが開発する東北大生向けアプリ「とんペディア」への実装から始め、日常的に使われるアプリを災害時の通信手段として活用することを目指します。

さらに、自治体のアプリなどにも導入を広げ、地域で利用されるさまざまなアプリが互いに情報を中継できる仕組みへと育てていきます。将来的には、世界中で使われるOSへの組み込みも視野に入れ、災害時にも情報を届けられる強靭なネットワークの実現を目指します。

### 注力したこと（こだわり等）

- **柔軟性と拡張性のあるプロトコル設計**

  さまざまなアプリへの組み込みや、今後の用途・通信手段の広がりを見据え、特定のアプリだけに依存しない、柔軟で拡張性のある仕様づくりに注力しました。

- **プロトコルの特徴が伝わる実装デモ**

  仕様を文章で示すだけでなく、その仕組みや特徴を実際の動作で伝えられるようにデモを作成しました。インターネットに接続していない端末間でBluetoothによって情報を受け渡し、オンラインの端末を通じてクライアントに表示する流れを体験できる形にしました。

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
* Fetch API / DOM API：AppViewから1秒間隔で投稿を取得・表示し、PLC Directoryから表示用ハンドルを解決。

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

#### Mosimo Protocol：用途に合わせて拡張できる、共通の通信網

**さまざまなアプリや用途に合わせて拡張できる、柔軟な共通プロトコルを設計しました。**

共通の本文を土台に、位置情報や出典など、各アプリが必要とする情報を追加できる構造にしました。追加情報に未対応のアプリも、本文を読み、元の情報を壊さず中継できます。アプリごとの機能の違いや、今後の用途の広がりを受け止めながら、異なる対応アプリが同じ通信網に参加できることを重視しています。この共通の土台に、投稿者の署名を保ち、通信圏外からオンラインの情報網へつなぐ仕組みを組み合わせています。

```mermaid
flowchart TD
  A[発信アプリ：投稿を作成・署名] -->|オンライン| N[Nostrリレー：署名付き投稿を収集]
  A -->|圏外ではBLE・LAN| M[別の対応アプリ・エッジデバイス]
  M -->|保存して持ち運び・次の端末へ中継| M
  M --> G[オンラインにつながる端末]
  G -->|元の署名を保って送信| N
  N --> P[PDS：端末署名済みデータを復元・検証・公開]
  P -->|atproto Relay・Jetstream経由| V[AppView：投稿を索引化]
  V --> W[Webなどの閲覧クライアント]
```

##### 1. 知らない情報も、壊さず運べる

**アプリが情報を「理解すること」と「中継すること」を分け、用途の違うアプリも通信網に参加できるようにします。**

あるアプリが避難所の位置情報を付けても、別のアプリがその形式を理解できるとは限りません。そこで、基本投稿には本文・作成時刻・配送期限を持たせ、位置や出典などの追加情報は `extensions` にまとめます。その拡張に未対応のアプリでも本文を読み、追加情報をそのまま次の対応アプリへ渡せる設計です。重要な指示は本文だけでも読めるようにします。

各拡張は `$type` で識別し、アプリ開発者が自分の名前空間で定義できます。受信側は未知の型やフィールドも削除・書換えせず、配列順も保持します。全アプリの同時更新や、受信時のスキーマ取得を求めずに用途を広げられます。

この拡張は著者の署名対象です。Nostrではタグに載せ、PDSは未知の拡張も専用コードなしに元のレコードへ復元します。基本プロトコル・拡張型・メッシュ符号化は独立して版管理し、拡張の非互換変更は新しい型として区別します。

詳細：[拡張仕様](./protocol/0011-extensions.md)・[形式ごとの対応](./protocol/0012-bindings.md)。

##### 2. 中継者が変わっても、投稿者の署名は変わらない

**投稿の正本と秘密鍵は端末が持ち、中継者やサーバーは著者として署名し直しません。**

**圏外でも投稿者の署名を確かめながら周囲の人と情報を共有し、誰かがオンラインになれば、集まった情報を公開して、離れた家族も状況を確認できるようにします。**

- **atprotoの署名**：端末が投稿レコードを追加し、リポジトリの新しい状態を表すcommitにECDSA署名を付けます。
- **Nostrの署名**：本文・拡張・署名済みcommitの復元情報を一つのイベントにまとめ、全体にBIP-340 Schnorr署名を付けます。過去の投稿がなくても、そのイベント内の公開鍵で署名を検証できます。

**1投稿につき、内容を表す「レコード」、投稿一覧の更新後の状態を示す「署名済みcommit」、署名付きで配送するための「Nostrイベント」を一つずつ作ります。** 署名用途ごとに秘密鍵を分離し、端末内に保持します。PDSは受け取った情報からリポジトリの木構造（MST）を再構築し、端末が署名した状態と一致するか検証します。必要な過去の状態が欠けていれば、書き換えてつじつまを合わせず保留します。

**木を丸ごと運ばず、端末が署名した状態を再現します。**

```mermaid
flowchart TD
  subgraph Device["発信端末"]
    A["投稿レコードを追加"] --> B["MSTを更新"]
    B --> C["新しいrootを指すcommitに署名"]
  end

  C -->|MSTノードは配送しない| D["本文・拡張・path・適用元CID<br/>署名済みcommitをNostrイベントで運ぶ"]
  D --> E["受信端末<br/>過去の投稿がなくても<br/>Nostr署名を検証できる"]
  D --> F["PDS<br/>適用元の状態に投稿を追加<br/>MSTのrootを再計算"]

  F --> G{"端末が署名した<br/>rootと一致？"}
  G -->|一致し、commit署名も有効| H["検証済み状態として公開"]
  F -.->|適用元の履歴が不足| I["公開を保留"]
```

Nostr署名の検証は、DIDの認証や人物・機関の身元確認とは区別します。署名が正しいことだけで、内容が真実だとも判断しません。

詳細：[正本と署名のモデル](./protocol/0001-repository-model.md)・[データ表現](./protocol/0012-bindings.md)。

##### 3. つながったときに、足りない投稿を交換する

**常時接続を前提にせず、保存した投稿を持ち運び、接触した相手へ中継します。**

配送には、通信が途切れる環境向けのBundle Protocol v7（BPv7）を採用しました。署名付きイベントをコンパクトなCBOR形式にし、配送用のBundleに包みます。基本の通信経路はBLE GATT、追加経路は同一Wi-Fi LANです。接続時に所持するイベントIDを交換し、互いに不足する投稿だけを要求・転送します。

ここで、**著者が確定する情報と、中継のたびに変わる配送状態を分離**します。本文・拡張・配送期限・署名済みcommitは変更せず、経由回数や経過時間はBPv7側で扱います。同じ投稿を別のBundleで受け取ってもイベントIDで重複を判定し、再中継によって著者の配送期限を延長しません。

受信側はBundle全体の検査・署名検証・保存を終えてから受理を応答します。切断で転送が途切れた場合は、次の接触で先頭から再送します。小型機器も片方の接続役だけで参加でき、常時接続のサーバーがなくても保存・中継を担える設計です。

詳細：[メッシュ配送仕様（MNDT）](./protocol/mndt.md)。

##### 4. 圏外で生まれた投稿を、オンラインへつなぐ

**投稿の到着経路を一つに固定せず、受け取ったサーバーが元の署名を検証して公開につなげます。**

インターネットにつながる端末は、保持したイベントを元のNostr JSONへ戻し、リレーへ送信します。Nostrリレー兼PDSは投稿を収集し、端末署名済みのatprotoデータを復元・検証します。PDSが公開する更新をatproto Relayが集約し、Jetstream経由で対応AppViewが索引化することで、Webなどから閲覧できます。

複数のサーバーで収集・複製しながら、投稿者ごとの正規の公開窓口を**論理PDS**として提供します。例えば、投稿者の公開先がAなのに、先にBが投稿を受け取った場合、BはAへその投稿の到着を知らせます。Aは必要なデータをBから取得し、自ら検証して公開する設計です。

**各物理PDSがNostrから端末署名済みのリポジトリを復元・検証し、元の署名を保ったまま、一つの論理PDSとして公開します。**

```mermaid
flowchart TD
  N["Nostrイベント<br/>本文・拡張・端末署名済みcommitの復元情報"]

  subgraph Logical["論理PDS：複数の物理PDSで一つの公開窓口を提供"]
    P1["物理PDS A<br/>① Nostrイベントを受信<br/>② 元のレコード・commitを復元<br/>③ MSTを再構築し、署名・rootを検証"]
    P2["物理PDS B<br/>① Nostrイベントを受信<br/>② 元のレコード・commitを復元<br/>③ MSTを再構築し、署名・rootを検証"]

    P1 -->|検証済みの更新| S
    P2 -->|検証済みの更新| S
    S["公開状態を整合<br/>head・seq・再送履歴"]
    S --> E["正規公開endpoint<br/>atproto API・firehose"]
  end

  N --> P1
  N --> P2
  D["著者のDID"] -.->|正規PDSとして指定| E
  E --> R["atproto Relay"]
  R --> V["AppView"]

  style P1 fill:#e8f0fe,stroke:#557ab5
  style P2 fill:#e8f0fe,stroke:#557ab5
  style S fill:#fff3d6,stroke:#b8860b
```

図は複数物理ノードで一つの論理PDSを提供する構成例です。現実装では共通PostgreSQLと採番の直列化で公開状態を整合させます。論理PDSは運用グループごとに存在でき、DIDごとに正規公開先を指定します。

詳細：[論理PDSと分散公開](./protocol/0006-logical-pds.md)・[Nostrリレーの役割](./protocol/0007-nostr-relay-profile.md)。

##### 5. 災害情報の「いつ・どこ・誰から」を保つ

**遅れて届く情報を扱うため、配送の都合と、情報そのものの意味を分けて設計しています。**

- **いつ**：中継を続ける「配送期限」と、情報が有効な「有効期間」を区別します。配送期限を過ぎても撤回を意味せず、有効期間が不明な情報を無期限に有効とも扱いません。
- **どこ**：投稿者の「発信地点」と、情報が関係する「影響範囲」を区別します。影響範囲は配送順位のヒントに使い、範囲外のゲートウェイを経由する中継も許します。
- **誰から**：著者の署名と、自治体などの身元の証明を区別します。公式発信者の確認には、利用者やアプリが信頼する起点からの署名チェーンを使う設計です。

訂正・補足は元投稿を上書きせず、対象を参照する新しい署名付き投稿として残します。公的情報の取り込みでは、単独で読める本文に加え、原情報の出典・変換者・構造化情報を保持します。更新・取消には権限の確認を求め、正当に置き換えられた旧版の再拡散を抑える方針です。

**CAP・EDXL-DEなどの国際標準から、気象庁XMLのような国・機関ごとの形式まで、拡張によって共通の通信網で扱える設計です。**

形式ごとの構造化情報を人が読める本文とともに運び、出典や対象地域、更新・取消の意味を保持します。

詳細：[公的情報との対応](./protocol/0013-public-information.md)・[身元証明](./protocol/0009-identity-attestations.md)。

#### デモ実装：プロトコルをAndroidアプリ・ESP32・Webで具体化

**投稿を作る端末、中継する小型機器、公開・閲覧するサーバーを実装し、プロトコルの流れを具体化しました。**

| 実装 | 担当する部分 | 実現方法・工夫 |
|---|---|---|
| Android | 投稿作成・二重署名・中継・オンライン搬出 | 端末内でレコード・MST・署名済みcommitを生成。BLEとmDNS/TCPによるLAN交換を共通の配送処理へ接続し、Keystoreを使った暗号化保存と回線復帰時の再送を実装。 |
| ESP32（M5Stack Basic） | 小型機器での検証・保存・中継 | 署名対象データを順にハッシュへ流してメモリ使用を抑え、署名検証とLittleFSへの保存後に受理応答。画面表示と音声読み上げも実装。 |
| Nostrリレー兼PDS | 収集・復元・検証・公開 | 著者の鍵を持たず、PLCの公開鍵・commit署名・再構築したMSTを照合。履歴不足は保留し、公開状態と更新配信履歴を同一トランザクションで進める。 |
| AppView・Web | 投稿の索引化・閲覧 | HappyViewとLuaクエリでフィードを提供。許可した発信元のBSAF投稿は別入力として収集し、原投稿と出典情報を保って同じタイムラインに表示。 |

BPv7ライブラリはフォークし、未知ブロックの保持・受信バイト列でのCRC検証・期限計算などを修正しました。

#### 今後の展望

今回のデモで具体化した、署名付き投稿の作成・中継・オンライン公開を土台に、対応アプリ・通信手段・扱える情報を広げていきます。

- **異なるアプリ同士で使える、共通の通信基盤へ**

  拡張データの定義や端末間の交換仕様を整え、異なる実装同士での相互運用を検証します。各アプリが独自の機能を加えながら、同じ通信網に参加できる仕組みを確立していきます。

- **限られた接続時間を、より有効に使う**

  CBOR形式での交換と、受信可能な量に応じた流量制御への対応を進めます。Nearby Connectionsなどの追加経路も検討し、利用できる通信手段を広げます。

- **国内外の防災情報を、圏外にも届ける**

  現在のBSAF投稿の収集・表示から、CAP・EDXL-DE・気象庁XMLなどの直接取り込みとメッシュ配送へ実装を広げます。形式ごとの構造化情報や出典、更新・取消の関係を保ちながら運べるようにします。

- **情報の出どころを、通信が途絶えても確かめられるように**

  鍵の対応を示す宣言や、信頼する起点から発信者へつながる署名チェーンを具体化します。署名の検証に加えて、自治体などの発信元や更新・取消の権限を確認できる仕組みを目指します。
