# Android メッシュデモ（実験実装）

`protocol/` を正とする。投稿のタグ・メッシュ用 CBOR は 0012 §3.2.4・§4、名前は 0014 に従う。リンク・制御メッセージは [mndt.md §9](../../protocol/mndt.md#9-android-実装の暫定値提案未採用) の実験形式を使い、最新 §3 の合意済み CBOR 交換・CREDIT・連番付き GATT フレームには未対応。§9 の値は採用済み標準ではなく、新形式と接続内で混在できない。[2026-10-18 Android 監査・統合条件](AUDIT-2026-10-18.md)を参照。

## 構成

- `bundle-core`: Android 非依存。署名、atproto レコード・MST・commit、0012 のタグ、メッシュ CBOR v1、BPv7 の検証・保存・転送・退避。
- `mesh-android`:
  - `GattLink`（BLE GATT）と `LanLink`（`_mosimo._tcp` の mDNS + TCP）。どちらも `Link` を実装する。
  - `Exchange`: hello / inventory / want / bundle / ack の接触時交換。
  - `MeshCoordinator`: 暗号化保存とリレー搬出。
  - `MeshService`: 単一の `connectedDevice` 前景サービス。
  - `BootReceiver`: 再起動後の再開。
- `app`: Compose の投稿・中継開始・リレー一覧・診断表示。
- `../../third_party/sd_2607-dtn7-kotlin`: BPv7 コーデックの固定スナップショット。

store-carry-forward（バケツリレー）: 受信した Bundle は署名検証後に暗号化保存され、保存成功後だけ ACK・所持広告・オンライン搬出を行う。後で接続した端末へ hop を1増やして渡され、接触中に全経路がつながっている必要はない。

## ビルド

JDK 21、Android SDK 36。リポジトリ直下から:

```sh
apps/android/gradlew -p apps/android \
  :dtn7-bpv7:test :bundle-core:test :mesh-android:testDebugUnitTest \
  :app:assembleDemoDebug :app:assemblePeerDebug
```

成果物は `app/build/outputs/apk/{demo,peer}/debug/`（`org.mndt.demo` / `org.mndt.demo.peer`）。Google Play 開発者サービスは不要。

本番公開を検証する専用 flavor は `:app:assembleVerificationDebug`（`org.mndt.demo.verification`、表示名 `mosimo Public Network Test`）。既存アプリとは別の鍵・保存領域を使い、ネイティブ Instrumentation から実 Coordinator を駆動する。実 PLC 登録と公開投稿を伴うため、通常のローカルテストとして実行しない。[実機検証報告](../../docs/android-production-verification-2026-10-18.md)と[検証メモ](VERIFICATION.md)を参照。

### 隔離実機試験用の任意ビルド設定

`-PmndtTestPeerNode=<16桁小文字hex>` で対象の一時 node のみに制限する。`-PmndtTestAuthor=<64桁Nostr公開鍵>` で広告・Bundle 提供・オンライン搬出・代理登録をその著者に限定する。既存保存データは削除しない。両設定とも既定は空（通常動作）であり、認証・本番アクセス制御ではない。BLE の役割・MTU 交渉は通常経路のまま。未解決だった central-only／MTU フォールバック実験は最終コードから除去した。

2026-10-18 14:19 の最終端末 APK は制限なしの通常ビルドへ復元済みで、Demo/Peer とも中継・オンライン搬出 OFF。保持中の未承認テスト/他者イベントがあるため、搬出を有効にする前に[監査の最終状態](AUDIT-2026-10-18.md)を確認する。node はプロセスごとの一時値で、過去の試験用 allowlist はそのまま再利用できない。

## 動作

- 「中継開始」で Bluetooth 権限を求め、前景サービスを開始する。
  - 各端末は GATT server・広告・scan を兼ね、node ID の小さい側が接続する。同じ LAN 上の端末とは mDNS で見つけて TCP 接続する。
  - 接続は自動で受け入れる。照合コードの確認はない。
  - 信頼は Nostr 署名の検証と資源上限による。
- 画面を閉じても、画面消灯中も中継を続ける。
  - `START_STICKY` なので、OS がプロセスを回収した場合は再起動される。
  - 中継を有効にしたまま端末を再起動すると、自動で再開する。
  - 強制停止後や省電力制限下の動作は保証しない（mndt §3.5）。
- オンライン搬出が有効で、画面表示中または中継サービス稼働中は、保持中の全イベントを元の署名のまま、既定リレー（`wss://pds.oriom.dev`、`wss://relay.damus.io`、`wss://nos.lol`）と追加したリレーへ巡回して送る。拒否・接続失敗の再試行待機はリレーごとに独立する。リレーから取得したイベントは表示専用で、メッシュには投入しない（mndt §6）。
  - 保存済み一覧が旧既定の2件と完全一致するときだけ、PDS を一度追加する。独自一覧・一部削除済み一覧は変更しないため、その場合は PDS を手動追加する。
  - 自作投稿は PDS リレーの `OK` まで容量退避しない。未配送の自作投稿で128件が埋まると新規保存・投稿を拒否し、既存投稿を保持する。PDS を削除した構成でもこの保護は続く。一般リレーへの `OK` では保護を解除しない。
  - `OK` は Nostr イベントの受理であり、PLC 登録・atproto 公開・AppView 表示の完了ではない。PDS の受理後も、一般リレーへの配送が終わる前に容量退避される可能性はある。
- 画像などのメディアは送らない（0003 §9.4 で延期）。1イベントの CBOR payload の上限は 32 KiB（Bundle はローカル上限 24,000 bytes）。
- 旧形式（`org.mndt.experimental.v2` タグ・`mesh-state.aesgcm`）は読まない。初回起動時に旧状態を削除し、鍵を新しく生成する。

## SDK として組み込む場合

初期化時に `MeshCoordinator.get(context)` を呼ぶ。中継は `MeshService.start/stop(context)` で開始・停止する（権限は `MeshPermissions`）。投稿は `publish`、リレーは `addRelay` / `removeRelay` / `setOnlineEnabled` で操作する。Activity の `onStart` / `onStop` から `setOnlineForeground(true/false)` を通知する。UI は `state` を購読する。

## 署名と復元の境界

- Nostr: NIP-01 + BIP-340。`content` は本文そのもの。
- atproto: v3 commit を別の secp256k1 鍵で low-S ECDSA 署名し、`mosimo.oriom.dev:commit` に padding なし Base64 で載せる。PLC 管理鍵も別に生成する。
- `AtRepository.restore(event, atprotoPubkey?)` はタグからレコードと commit を再構成する。
  - 公開鍵を渡した場合は commit 署名も検証する。この公開鍵は本来 DID document から得る。
  - DID 認証と MST 包含検証は行わない（PDS 側の段階）。
- record key と `rev` は同じ TID を使う（0012 §2.1.1 は一致を要求しないので、受信側は一致を前提にしない）。TID の clock ID は repository ごとの乱数で、永続化する。

- DID はオフラインで導出する。現行アプリの Manifest は PDS に `https://pds.oriom.dev`、PLC Directory に `https://plc.directory` を指定し、オンライン時の初期操作登録・現在鍵と PDS 設定の確認を有効にしている。SDK の未設定時の PDS は `.invalid`。
  - 保存済みの PDS endpoint・鍵・DID は Manifest 更新だけでは変更しない。旧 `.invalid` の状態はそのままでは本番 atproto 公開できず、本人の鍵による PLC 更新等の移行判断が必要。アプリデータ削除で安易に別発信者へ置き換えない。
  - PLC 登録・確認はリレー搬出の ON/OFF とは独立して動く。公開ネットワークへ接続した検証時は、実 DID 登録が発生し得る点に注意する。
  - 未登録のまま投稿した場合は、署名済み初期操作を制御イベント（[mndt §9.5](../../protocol/mndt.md#95-plc-初期操作の制御イベントと代理登録)）としてメッシュで運び、受け取ったオンライン端末が代理登録する。

## 未実装・範囲外

- 削除（NIP-09）、訂正などの制御形式。
- 地理・身元証明の拡張 UI。
- 鍵対応宣言の配送。
- Nearby への経路切替。
- mDNS が届かない網向けの手動アドレス指定。
- BPSec。
- メディア。
- iOS。
