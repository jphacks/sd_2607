# Android メッシュデモ（実験実装）

`protocol/` の合意事項を正とする。古い `AGENTS.md` / `CLAUDE.md` の「端末は atproto commit を作らない」という案 C とは異なり、端末自身が atproto v3 commit を署名する。これは完成した共通 wire specification の実装ではない。未確定の配送・復元形式を暫定実装している。

## 構成

- `bundle-core`: Android 非依存の署名、atproto レコード・MST・commit、MNDT Payload、BPv7 検証・保存・転送判断。
- `mesh-android`: Nearby P2P_CLUSTER、HELLO / INVENTORY / WANT / ACK、暗号化保存、Nearby とオンライン通信それぞれの前景サービス、Nostr 搬出・取得、設定先 PLC Directory の状態照合。
- `app`: Compose の入力・送信、接続承認、観測 hop、リレー受理と診断の表示。
- `../../third_party/sd_2607-dtn7-kotlin`: 指定フォークの BPv7 コーデックの固定スナップショット。出典と変更点は `UPSTREAM.md`。

Nearby は直接接続のみを提供する。アプリが受信・検証・保存したバンドルを次の端末へ送り、store-carry-forward を実現する。接触中に全経路がつながっている必要はない。

## ビルド

JDK 21 と Android SDK 36 が必要。ライブラリの出力 bytecode は Java 17。デスクトップ署名テストで使う ACINQ JNI 0.24.0 は JDK 21 で実行する。

リポジトリ直下から:

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
apps/android/gradlew -p apps/android \
  :dtn7-bpv7:test :bundle-core:test :mesh-android:testDebugUnitTest \
  :app:assembleDemoDebug :app:assemblePeerDebug
```

Kotlin daemon の incremental cache エラーが発生する環境では、同じコマンドに `-Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process` を付ける。

成果物:

- `app/build/outputs/apk/demo/debug/app-demo-debug.apk`: `org.mndt.demo`
- `app/build/outputs/apk/peer/debug/app-peer-debug.apk`: `org.mndt.demo.peer`

初回ビルドには依存ライブラリの取得が必要。オフライン動作とは、インストール済みアプリ同士の通信がインターネット接続を必要としないという意味。Google Play 開発者サービス、Bluetooth / Wi-Fi と実行時権限は必要。

## デモ

1. 実機 A / B / C にインストールし、各アプリを一度開く。初期化時にインストール固有の鍵と DID を準備する。バックアップ・他アプリとの秘密鍵共有はしない。
2. 権限を許可しメッシュを開始する。通知の許可は Nearby の通信権限とは別。接続時には両端末の照合コードを実際に比較してから双方で承認する。表示名は未認証。
3. A と B だけを接続して A から送信する。B で受信・署名検証と観測 hop を確認する。
4. A を停止または圏外へ移し、B と C を接続する。C に同じ event ID が届き、hop が増えることを確認する。3台が常に互いを直接発見できる配置では、多段転送の証明にならない。
5. 運用者のリレー URL を明示設定してオンライン通信を有効にする。通知付きの独立した前景サービスで、画面を閉じても搬出・取得を続ける。Nearby の開始・権限は不要で、Nearby 停止とオンライン停止も別の操作。オンライン停止は画面またはオンライン通知の「停止」から行う。外部ネットのない避難所 LAN にも接続を試みる。既定 URL は空で、公開リレーへ勝手に投稿しない。`wss://` を推奨。署名済みの元イベントを変更せず送る。
6. `Nearby transport SUCCESS`、相手の保存 ACK、リレーの `OK true` は別の状態。リレー受理は PDS 公開・AppView 表示・永続保存の保証ではない。
7. アプリを終了・再起動して、鍵・リポジトリ head・保存バンドル・リレー未受理キューが戻ることも確認する。age・hop はリセットしない。同じ OS 起動内では単調時計で停止時間を加算し、再起動等で停止時間を測れなければメッシュ寿命を使い切った扱いにする。本文・オンライン搬出待ちは残す。

通信圏外の作成、端末間無線、OS の前景サービス、Keystore、別署名 APK 間の接続は実機確認が必要。JVM の仮想 A→B→C テストだけで検証済みとは扱わない。

## SDK 化と異なるアプリ間の接続

全アプリで `Wire.SERVICE_ID = org.mndt.mesh.experimental.v1` と P2P_CLUSTER、HELLO の版・グループ・wire format を一致させる。`applicationId` を service ID に使わない。SDK に組み込むアプリ名とメッシュ参加用 ID は別物である。

`demo` / `peer` は異なる application ID を持ち、同じ通信実装を使う。発信者 DID と3用途の秘密鍵は各インストールで独立している。Google の API 文書には同一 package / 署名証明書を要求する制約は記載されていないが、異なる package / 署名と実機の組み合わせでの接続確認は別途必要。同一物理端末上で二つのアプリを同時実行することの保証にはしない。

将来の公開 SDK は `bundle-core` と `mesh-android` を配布対象にすればよい。現時点では公開 Maven artifact、汎用 transport interface、DI、独立したルーティング framework は作らない。組み込み先は初期化時に `MeshCoordinator.get(context)` を呼ぶ。オンライン通信には Activity の `onStart` / `onStop` から `setOnlineForeground(true/false)` を通知し、明示的な `configureRelay` / `setOnlineEnabled` を使う。`state.ready && state.onlineEnabled` のとき、可視 Activity から `OnlineService.start(context)` を呼ぶ。Activity が隠れる際にサービスを止めない。デモ UI は `LifecycleStartEffect` で設定変更・画面への復帰時に開始する。Nearby には別途、可視 Activity で権限を確認して `MeshService.start(context)` を呼ぶ。UI は `state` を購読し、接続承認は `confirm`、投稿は `publish`。サービスは manifest merge される。

## 署名と復元境界

- sig1: NIP-01 の規定シリアライズの SHA-256 に対する BIP-340 Schnorr。Nostr の `content` は人が読める本文。
- sig2: atproto v3 の署名なし commit を canonical DAG-CBOR にして SHA-256、別の secp256k1 秘密鍵で low-S compact ECDSA 署名。単なる独自 JSON レコード署名ではない。
- PLC 管理鍵も別に生成する。秘密鍵は Android Keystore の AES-GCM 鍵で暗号化し、鍵・リポジトリ head・outbox を一つの AtomicFile に保存する。復号不能時に鍵を勝手に再生成しない。
- 1投稿につき1レコード・1 commit・1 Nostr event。小規模な端末リポジトリ全体の MST root を生成する。commit の標準 `prev` は null、適用元 commit CID は独立した Nostr 署名対象タグに保持する。
- 新規投稿は実験用 v2 の項目別タグを使う。本文は `content` のみ、署名済み commit は Base64、path・適用元 CID・レコード型・正確な作成時刻等は個別タグとする。全項目を一つの envelope に包まない。MST ノード・包含証明は配送しない。
- 配送期限はレコードの `deliveryExpiresAt` と署名済み `mndt-exp` の両方へ同じ値を格納する。SDK の `Author.createPost` で秒単位の絶対期限を指定でき、省略時は24時間後。共通仕様の既定値ではない。
- `extensions` は `$type` 付きオブジェクトの配列。SDK へ `List<Map<String, Any?>>` として渡し、未知の型・未知フィールド・null と省略・配列順を残す。値は atproto データモデルの型だけを許容し、bytes は `ByteArray`、CID link は `CidLink` を使う。表示用の既知項目だけで原データを作り直さない。現在の画面は本文のみを入力・表示する。
- `AtRepository.restore` は元レコードの CBOR を再構成し、署名対象の record CID と照合して、元の commit bytes と合わせて返す。`verifyEnvelope` はこの検査と提供された公開鍵に対する commit 署名検証の真偽値を返す互換 API。PLC に基づく DID 認証でも、過去状態を使ったレコードの MST 包含検証でもない。後者は PDS の別段階である。
- 旧 v1 の `atproto` envelope は読み取り互換のみ残す。保存済みイベントの本文・タグ・署名・CID を変更せず、旧 head からの次の投稿だけを v2 で作る。旧レコードへ欠けている配送期限や拡張配列を後付けしない。
- 生成した DID はオフライン導出した識別子。既定の `https://pds.invalid` は実在する公開先ではなく、PLC 登録済みと表示しない。本番の PDS と登録方針を決めるまで実験用とする。

## 時計・オンライン通信・PLC の境界

- 時計は既定で `ClockConfidence.UNKNOWN`。`MeshEngine` のローカル方針、SDK の `setClockConfidence` または画面の手動スイッチでのみ `TRUSTED` にする。自動同期・信頼判定・時計合意プロトコルは実装していない。プロセス再起動では信頼指定を復元しない。
- 信頼すると指定した時計では署名付き絶対期限を強制し、信頼しない場合は配送期限を「判定不能」と表示する。判定不能だけでは受信・中継を拒否せず、Bundle Age・単調時計・Lifetime・hop・保存量・送信量・サイズ上限は維持する。「期限内」は内容が有効・最新という意味ではない。
- 暗号化保存には累積 age のスナップショット、単調時刻と Android の boot count を含める。同じ boot の停止時間だけを単調時計から加算する。旧保存形式・boot 不明・OS 再起動・単調時計の後退等で経過時間を測れない場合は、寿命を延長せずメッシュ再転送を打ち切る。既存署名・hop・本文・搬出待ちは保持する。状態の巻き戻り検出・回復を実装したわけではない。
- オンライン通信の有効設定と搬出待ちは暗号化保存する。以前に明示設定されたリレーも opt-in として引き継ぐ。`OnlineService`（`dataSync`）は Nearby の `connectedDevice` サービスと独立し、画面非表示でも接続・購読・再送を続ける。通知許可の拒否は通信を止めず、画面に通知無効を表示する。オンライン停止・URL の解除・致命的な保存エラーではサービスを終了する。停止時は搬出待ちを保持し、Nearby は独立して継続できる。
- Android 15 以降では `dataSync` に原則24時間中6時間の実行上限がある。`onTimeout` で直ちにサービスを停止し、設定・搬出待ちは保持する。オンライン設定が有効なら、次に画面を開いた際にサービスを再開する。WorkManager、boot receiver、自動再起動、電池最適化の除外要求、wake lock は追加しない。画面非表示とプロセス不在は別であり、OS の強制停止・省電力・通信制限下の即時配送や無期限の常駐は保証しない。
- 標準 Nostr の `REQ` / `CLOSE`、`EVENT` / `EOSE` / `CLOSED` を使う。デモの取得対象は `kind:1` と既存 `t=mndt-experimental-v2` のみ、初期32件。EOSE 後は新着を購読し、初期履歴は20秒で打ち切る。接続世代・subscription ID・event ID と BIP-340 署名を検証する。
- 取得は64件・元イベント JSON 換算2 MiB、全 frame 256件/分・著者12件/分までのメモリ内表示専用。未知タグを保持し、メッシュ保存済みの検証済み event ID と重複表示しない。取得元・揮発表示を明示し、ディスク保存・再署名・再バンドル・メッシュ投入はしない。信頼する importer と配送同値性が未決のため、自動 mesh ingress は無効のまま。
- PLC の過去の初期操作登録確認と、現在状態の `UNKNOWN / CHECKED / CHANGED / TOMBSTONED` は別。設定先 Directory の `/log` と `/data` を保存済みインストール由来の公開鍵・PDS 設定と照合し、観測時刻を表示する。復元・通信不能・不正入力では現在状態を不明に戻し、登録済み bool だけで後続確認を省略しない。
- PLC は HTTPS Directory の時点付き観測であり、全監査履歴・ECDSA・CID・recovery ordering の独立検証ではない。ログは最大128操作、各 HTTP body 最大262,144 bytes。PDS 受入・公開確認や通常投稿の DID 認証には使わず、本文表示・メッシュ配送も待たせない。既存登録の読み取り確認と登録 POST の許可を分離し、登録 disabled では POST しない。既定の `.invalid` PDS・空 Directory・登録 disabled は変更していない。

## 未確定形式と変更箇所

各箇所に `TODO(undecided)` を付けてある。暫定値をそのまま新しい共通仕様として扱わない。

| 対象 | 現在の実験用形式 | 確定後の変更箇所 |
|---|---|---|
| 復元情報 | v2 項目別タグ、commit のみ CBOR/Base64、レコードは本文・タグから再構成 | `bundle-core/.../AtRepository.kt`: `create` / `restore` |
| Lexicon / path / rkey | `xxx.disaster.post`、本文・作成日時・配送期限・open union 拡張、単一 writer の TID | `AtRepository.kt`: `COLLECTION` / `create` / `tid` |
| 鍵対応宣言 | DID・両公開鍵・用途・同意を含む独立の相互署名付きデータ | `AtRepository.kt`: `binding`。公開・配送形式は未実装 |
| Nostr tag 対応 | `org.mndt.experimental.v2.*` / `mndt` / 必須 `mndt-exp` / 検索 `t` | `AtRepository.kt`: `TAG_PREFIX` / `create` / `restore`、`SignedPost.kt`: `Author.createPost`、`MeshEngine.kt`: `policy` |
| MNDT Payload | `[1, pubkey bytes, created_at, kind, tags, content, sig bytes]` | `SignedPost.kt`: `EventCodec` |
| Nearby 識別・同期 | 共通 service ID、版1、JSON の HELLO / INVENTORY / WANT / ACK、ID 全件列挙 | `mesh-android/.../Wire.kt` / `MeshCoordinator.kt` |
| 配送 policy | `dtn://mndt.local/~demo`、最大8 hop、24時間、priority 1 | `MeshEngine.kt`: `MeshPolicy`、`SignedPost.kt`: 投稿時の署名 tag |
| PDS / PLC 方針 | 本番未設定。既定 PDS は `.invalid` | `MeshCoordinator.kt`: `mndt.pds.endpoint` / `mndt.plc.directory` / 登録有効化の manifest metadata |

`mndt-exp` はメッシュ配送期限であり情報の有効期間ではない。有効期間は現デモでは未指定。期限だけで本文が虚偽・撤回済みとは判定しない。

### 実験用 v2 のタグ配置

以下はこのデモ内の暫定 bindings であり、`protocol/` の正式 wire 仕様を決めるものではない。名前空間・版・フィールド名・時刻・各拡張の具体的なタグ対応は引き続き `TODO(undecided)`。`t=mndt-experimental-v2` は投稿 bindings の識別であり、Nearby の制御 wire / service ID は従来の版1のまま。

| タグ名 | 値 |
|---|---|
| `t` | `mndt-experimental-v2`。他の `t` タグも保持できる |
| `org.mndt.experimental.v2.commit` | 元の署名済み commit CBOR の Base64 |
| `org.mndt.experimental.v2.recordCid` | 再構成した record bytes の一致確認用 CID。検索用の重複索引ではなく、MST 包含の証明でもない |
| `org.mndt.experimental.v2.base` | 適用元 commit CID。初回は空文字列で「なし」を明示 |
| `org.mndt.experimental.v2.path` / `.action` / `.key` | repository path / `create` / atproto 検証用公開鍵の hex |
| `org.mndt.experimental.v2.type` / `.createdAt` | レコード型 / レコードに使った正確な UTC 時刻文字列 |
| `mndt-exp` | Unix 秒整数。レコードでは UTC 時刻文字列へ対応付ける |
| `org.mndt.experimental.v2.extension:<$type>` | 配列位置の十進文字列と、その拡張オブジェクトだけの atproto JSON。型名・元の未知フィールドを含める |
| `org.mndt.experimental.v2.field:<name>` | 未知のレコード直下フィールドを受信した場合の atproto JSON。既知項目の上書き・重複は拒否 |

拡張は共通の `type/version/value` や一律の CBOR bytes へ包まない。atproto JSON の bytes は `{"$bytes":"..."}`、CID link は `{"$link":"..."}`。64-bit 整数を浮動小数点へ丸めない。拡張の配列位置を使ってレコードを復元するが、配送中の元タグの順序・文字列は変えない。`recordCid` と一致しない、不正・曖昧・上限超過のデータは復元検証を拒否する。メッシュでの本文表示・中継は引き続き Nostr 署名検証を基本とし、未対応拡張の解釈や DID 認証を前提にしない。

## 資源・実装上の上限

- バンドルの受信・送信最大24,000 bytes、Nearby アプリ frame 最大32,768 bytes。age の CBOR 整数が成長するため内部保存スナップショットのみ最大24,008 bytes。wire 上限を超えたコピーは転送せず、本文と搬出待ちを保持する。リレーの送受信 JSON は最大262,144 bytesとし、Nearby の上限を流用しない。
- メッシュ保存128件・priority ごと32件。通常投稿は priority 1 なので現在は同じ端末に32件まで。満杯時は拒否し、未送信投稿を暗黙に削除しない。現在は容量回収 UI を実装していない。
- 著者ごとの受信12件/分・転送36回/分。期限・Bundle Age・hop とローカル上限を適用する。時刻の正しさや配送完了は保証しない。
- リポジトリは128レコードまで。MST を全再構築するデモ実装。大規模化が必要になった時点で永続 MST に置き換える。
- BPv7 source EID は公開鍵から、creation time は0、sequence は event ID 由来の60bit。重複排除は必ず検証済み event ID で行う。60bit 衝突が問題になる規模では永続 sequence allocator を導入する。
- 未知の著者 tag は不変のまま保持。未知の BPv7 block は処理フラグに従い保持・discard・bundle 拒否。実験的 BPSec、断片化、大容量メディアは未対応。
- hop は受信した配送状態の観測であり、暗号学的な経路証明ではない。中継者が hop / age を正しく扱うことを完全には強制できない。
- OkHttp の受信サイズ検査は WebSocket message の組み立て後になる。標準 API に受信 frame の hard cap はなく、設定したリレー自体が悪意ある巨大 frame を送る DoS は完全には防がない。
- sidecar、PDS/AppView の実装、オフライン初回起動時の PLC 制御データの代理配送、鍵対応宣言の制御イベント、削除・訂正・機関証明は今回のデモ範囲外。
- リレー候補の同梱・自動探索・NIP-11 による優先選択・複数候補のフェイルオーバーは未実装。運用先が未提示のため、利用者による URL 設定を必須にしている。PLC 代理登録と併せて、`protocol/` 全体への適合を主張しない理由である。
- 時計の自動信頼判定・同期、信頼 importer の選択とオンライン取得からのメッシュ投入、PLC の独立全履歴監査・登録先と PDS 運用は未実装。今回のローカル実装・Directory 照合・揮発取得で `protocol/` 全体への完全適合を主張しない。

## 一次資料

- Nearby Android: https://developers.google.com/nearby/connections/android/get-started
- 発見・service ID: https://developers.google.com/nearby/connections/android/discover-devices
- 認証コード: https://developers.google.com/nearby/connections/android/manage-connections
- Strategy: https://developers.google.com/nearby/connections/strategies
- Connected-device FGS: https://developer.android.com/develop/background-work/services/fgs/service-types
- FGS timeout: https://developer.android.com/develop/background-work/services/fgs/timeout
- atproto Repository: https://atproto.com/specs/repository
- atproto Data Model: https://atproto.com/specs/data-model
- atproto Lexicon / NSID: https://atproto.com/specs/lexicon / https://atproto.com/specs/nsid
- atproto Cryptography: https://atproto.com/specs/cryptography
- PLC: https://github.com/did-method-plc/did-method-plc
- BPv7: https://www.rfc-editor.org/rfc/rfc9171.html
- ACINQ: https://github.com/ACINQ/secp256k1-kmp
