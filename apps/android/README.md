# Android メッシュデモ（実験実装）

`protocol/` の合意事項を正とする。古い `AGENTS.md` / `CLAUDE.md` の「端末は atproto commit を作らない」という案 C とは異なり、端末自身が atproto v3 commit を署名する。これは完成した共通 wire specification の実装ではない。未確定の配送・復元形式を暫定実装している。

## 構成

- `bundle-core`: Android 非依存の署名、atproto レコード・MST・commit、MNDT Payload、BPv7 検証・保存・転送判断。
- `mesh-android`: Nearby P2P_CLUSTER、HELLO / INVENTORY / WANT / ACK、暗号化保存、前景サービス、Nostr WebSocket への搬出。
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
5. 接続端末に運用者のリレー URL を明示設定する。外部ネットのない避難所 LAN のリレーにも接続を試みる。既定値は空で、公開リレーへ勝手に投稿しない。`wss://` を推奨。署名済みの元イベントを変更せず送る。
6. `Nearby transport SUCCESS`、相手の保存 ACK、リレーの `OK true` は別の状態。リレー受理は PDS 公開・AppView 表示・永続保存の保証ではない。
7. アプリを終了・再起動して、鍵・リポジトリ head・保存バンドル・リレー未受理キューが戻ることも確認する。期限・hop はリセットしない。

通信圏外の作成、端末間無線、OS の前景サービス、Keystore、別署名 APK 間の接続は実機確認が必要。JVM の仮想 A→B→C テストだけで検証済みとは扱わない。

## SDK 化と異なるアプリ間の接続

全アプリで `Wire.SERVICE_ID = org.mndt.mesh.experimental.v1` と P2P_CLUSTER、HELLO の版・グループ・wire format を一致させる。`applicationId` を service ID に使わない。SDK に組み込むアプリ名とメッシュ参加用 ID は別物である。

`demo` / `peer` は異なる application ID を持ち、同じ通信実装を使う。発信者 DID と3用途の秘密鍵は各インストールで独立している。Google の API 文書には同一 package / 署名証明書を要求する制約は記載されていないが、異なる package / 署名と実機の組み合わせでの接続確認は別途必要。同一物理端末上で二つのアプリを同時実行することの保証にはしない。

将来の公開 SDK は `bundle-core` と `mesh-android` を配布対象にすればよい。現時点では公開 Maven artifact、汎用 transport interface、DI、独立したルーティング framework は作らない。組み込み先は初期化時に `MeshCoordinator.get(context)` を呼び、可視 Activity で権限を確認して `MeshService.start(context)`、UI は `state` を購読する。接続承認は `confirm`、投稿は `publish`。サービスは manifest merge される。

## 署名と復元境界

- sig1: NIP-01 の規定シリアライズの SHA-256 に対する BIP-340 Schnorr。Nostr の `content` は人が読める本文。
- sig2: atproto v3 の署名なし commit を canonical DAG-CBOR にして SHA-256、別の secp256k1 秘密鍵で low-S compact ECDSA 署名。単なる独自 JSON レコード署名ではない。
- PLC 管理鍵も別に生成する。秘密鍵は Android Keystore の AES-GCM 鍵で暗号化し、鍵・リポジトリ head・outbox を一つの AtomicFile に保存する。復号不能時に鍵を勝手に再生成しない。
- 1投稿につき1レコード・1 commit・1 Nostr event。小規模な端末リポジトリ全体の MST root を生成する。commit の標準 `prev` は null、適用元 commit CID は Nostr 署名対象の復元 envelope に別途保持する。
- `atproto` タグにはレコード / commit の正確な CBOR bytes、CID、repository path、適用元 CID、署名検証用公開鍵を含める。sidecar はそれを使って元の commit を検証・復元できる。MST ノード・包含証明は配送しない。
- `AtRepository.verifyEnvelope` は本文・record CID・commit CID・提供された公開鍵に対する署名を検証する。PLC に基づく DID 認証でも、過去状態を使ったレコードの MST 包含検証でもない。後者は sidecar の別段階。
- 生成した DID はオフライン導出した識別子。既定の `https://pds.invalid` は実在する公開先ではなく、PLC 登録済みと表示しない。本番の PDS と登録方針を決めるまで実験用とする。

## 未確定形式と変更箇所

各箇所に `TODO(undecided)` を付けてある。暫定値をそのまま新しい共通仕様として扱わない。

| 対象 | 現在の実験用形式 | 確定後の変更箇所 |
|---|---|---|
| 復元 envelope | JSON を Base64、正確な record/commit CBOR も Base64 | `bundle-core/.../AtRepository.kt`: `create` / `verifyEnvelope` |
| Lexicon / path / rkey | `xxx.disaster.post`、本文・作成日時、単一 writer の TID | `AtRepository.kt`: `COLLECTION` / `create` / `tid` |
| 鍵対応宣言 | DID・両公開鍵・用途・同意を含む独立の相互署名付きデータ | `AtRepository.kt`: `binding`。公開・配送形式は未実装 |
| Nostr tag 対応 | `atproto` / `mndt` / 必須 `mndt-exp` / 検索 `t` | `SignedPost.kt`: `Author.createPost`、`MeshEngine.kt`: `policy` |
| MNDT Payload | `[1, pubkey bytes, created_at, kind, tags, content, sig bytes]` | `SignedPost.kt`: `EventCodec` |
| Nearby 識別・同期 | 共通 service ID、版1、JSON の HELLO / INVENTORY / WANT / ACK、ID 全件列挙 | `mesh-android/.../Wire.kt` / `MeshCoordinator.kt` |
| 配送 policy | `dtn://mndt.local/~demo`、最大8 hop、24時間、priority 1 | `MeshEngine.kt`: `MeshPolicy`、`SignedPost.kt`: 投稿時の署名 tag |
| PDS / PLC 方針 | 本番未設定。既定 PDS は `.invalid` | `MeshCoordinator.kt`: `mndt.pds.endpoint` / `mndt.plc.directory` / 登録有効化の manifest metadata |

`mndt-exp` はメッシュ配送期限であり情報の有効期間ではない。有効期間は現デモでは未指定。期限だけで本文が虚偽・撤回済みとは判定しない。

## 資源・実装上の上限

- バンドル最大24,000 bytes、Nearby アプリ frame 最大32,768 bytes。リレーへの送信 JSON はエスケープによる増加を考慮して最大262,144 bytesとし、Nearby の上限を流用しない。
- メッシュ保存128件・priority ごと32件。通常投稿は priority 1 なので現在は同じ端末に32件まで。満杯時は拒否し、未送信投稿を暗黙に削除しない。現在は容量回収 UI を実装していない。
- 著者ごとの受信12件/分・転送36回/分。期限・Bundle Age・hop とローカル上限を適用する。時刻の正しさや配送完了は保証しない。
- リポジトリは128レコードまで。MST を全再構築するデモ実装。大規模化が必要になった時点で永続 MST に置き換える。
- BPv7 source EID は公開鍵から、creation time は0、sequence は event ID 由来の60bit。重複排除は必ず検証済み event ID で行う。60bit 衝突が問題になる規模では永続 sequence allocator を導入する。
- 未知の著者 tag は不変のまま保持。未知の BPv7 block は処理フラグに従い保持・discard・bundle 拒否。実験的 BPSec、断片化、大容量メディアは未対応。
- hop は受信した配送状態の観測であり、暗号学的な経路証明ではない。中継者が hop / age を正しく扱うことを完全には強制できない。
- OkHttp の受信サイズ検査は WebSocket message の組み立て後になる。標準 API に受信 frame の hard cap はなく、設定したリレー自体が悪意ある巨大 frame を送る DoS は完全には防がない。
- sidecar、PDS/AppView の実装、オフライン初回起動時の PLC 制御データの代理配送、鍵対応宣言の制御イベント、削除・訂正・機関証明は今回のデモ範囲外。
- リレー候補の同梱・自動探索・NIP-11 による優先選択・複数候補のフェイルオーバーは未実装。運用先が未提示のため、利用者による URL 設定を必須にしている。PLC 代理登録と併せて、`protocol/` 全体への適合を主張しない理由である。

## 一次資料

- Nearby Android: https://developers.google.com/nearby/connections/android/get-started
- 発見・service ID: https://developers.google.com/nearby/connections/android/discover-devices
- 認証コード: https://developers.google.com/nearby/connections/android/manage-connections
- Strategy: https://developers.google.com/nearby/connections/strategies
- Connected-device FGS: https://developer.android.com/develop/background-work/services/fgs/service-types
- atproto Repository: https://atproto.com/specs/repository
- atproto Cryptography: https://atproto.com/specs/cryptography
- PLC: https://github.com/did-method-plc/did-method-plc
- BPv7: https://www.rfc-editor.org/rfc/rfc9171.html
- ACINQ: https://github.com/ACINQ/secp256k1-kmp
