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
- オンライン搬出が有効で、画面表示中または中継サービス稼働中は、保持中の全イベントを元の署名のまま、既定リレー（`wss://pds.oriom.dev`、`wss://relay.damus.io`、`wss://nos.lol`）と追加したリレーへ巡回して送る。拒否・接続失敗の再試行待機はリレーごとに独立する。一般リレー購読で取得したイベントは表示専用で、メッシュには投入しない。下記の手動公的情報取得だけが0020の独立認可を経て投入できる。
  - 保存済み一覧が旧既定の2件と完全一致するときだけ、PDS を一度追加する。独自一覧・一部削除済み一覧は変更しないため、その場合は PDS を手動追加する。
  - 自作投稿は PDS リレーの `OK` まで容量退避しない。未配送の自作投稿で128件が埋まると新規保存・投稿を拒否し、既存投稿を保持する。PDS を削除した構成でもこの保護は続く。一般リレーへの `OK` では保護を解除しない。
  - `OK` は Nostr イベントの受理であり、PLC 登録・atproto 公開・AppView 表示の完了ではない。PDS の受理後も、一般リレーへの配送が終わる前に容量退避される可能性はある。
- 画像などのメディアは送らない（0003 §9.4 で延期）。1イベントの CBOR payload の上限は 32 KiB（Bundle はローカル上限 24,000 bytes）。
- 旧形式（`org.mndt.experimental.v2` タグ・`mesh-state.aesgcm`）は読まない。初回起動時に旧状態を削除し、鍵を新しく生成する。

## SDK として組み込む場合

初期化時に `MeshCoordinator.get(context)` を呼ぶ。中継は `MeshService.start/stop(context)` で開始・停止する（権限は `MeshPermissions`）。投稿は `publish`、リレーは `addRelay` / `removeRelay` / `setOnlineEnabled` で操作する。Activity の `onStart` / `onStop` から `setOnlineForeground(true/false)` を通知する。UI は `state` を購読する。

## 公的情報: Nostr → 認可済み mesh ingress（0020 wire-v1）

AppView のダウンロード・Bluesky record のクライアント変換は行わない。server が署名した元の Mosimo kind-1 event を **PDS relay `wss://pds.oriom.dev`** から取得し、タグ順・署名・本文を変えず `MeshEngine.originate` で通常の BPv7 Bundle に包む。一般の relay 購読は従来どおり揮発表示専用。認可失敗した転載を通常投稿へ降格して投入する経路はない。

### 公開設定 asset と API

既定は `mesh-android/src/main/assets/mosimo-public-trust.json`:

```json
{"roots":[],"selected":[]}
```

**空設定は fail closed**。本番 root は別途帯域外で配布する。イベント、NIP-11、snapshot、URLから root を発見・追加しない。root秘密鍵はアプリにも importer にも配布しない。

配布 root が用意できたらアプリ側の同名 asset で上書きするか、別名の asset を同梱し Manifest application metadata `mosimo.public.trust.asset` に指定する。`MeshCoordinator.reloadPublicConfig()` は既定/metadata の asset、`reloadPublicConfig("deployment-trust.json")` はそのローカル asset を読み直す。名前は英数字・`_`・`-`・`.` の1..128文字のみ（パス指定不可）。APK asset なので更新には通常アプリ更新が必要。ネットワーク設定取得 API ではない。再読込は明示的なローカル信頼操作として扱い、進行中の取得を無効化する。不正設定では全 ingress を停止するが、既存鍵・high-water・失効履歴を消さない。

設定の exact fields（下記は型の説明であり、そのまま配布する設定ではない）:

```text
{
  roots: [{
    rootId: SHA256(unhex(rootPubkey)) の小文字 hex64,
    rootPubkey: SEC1 compressed secp256k1 の小文字 hex66,
    enabled: boolean,
    scope: {
      orgIds: [組織ID],
      bsaf: [{botDid: did:plc:… , types: [type: prefix を除いた機械値]}]
    },
    snapshotUrl: 絶対 HTTPS URL,
    minSequence: 1..9007199254740991 の整数
  }],
  selected: [{botDid: did:plc:… , type: 明示的に選択した機械値}]
}
```

`scope` は0020の上限・UTF-8辞書順・distinct・exact-matchに従う。root scope の許可は `selected` を自動で増やさない。新 Bot/type も自動追加しない。roots は8件、selected は64組まで。テスト用の完全な公開設定例は `bundle-core/src/test/resources/wire-v1.json` の `config` にあるが、**公開済みテスト秘密鍵に対応するため実運用には絶対に使わない**。fixture はテスト resources にのみ置き、APK に同梱しない。

### 手動取得と状態表示

1. 公開設定で root と受信対象 Bot DID/type を明示的に選ぶ。
2. オンライン搬出を有効にし、画面表示または中継サービスを開始する。
3. 実験 UI の「端末時計を信頼」を有効にする。これは利用者の明示的判断であり、同期の証明ではない。既定・再起動時は `UNKNOWN`。HTTP Date・イベント時刻を信頼時計へ昇格しない。
4. 「公的情報を取得」（SDK: `requestPublicInformation()`）を押す。自動ポーリングは行わない。validated Internet が必要で、30秒につき1回、同時に1 request worker のみ。

独自 verb `MOSIMO-REQ`、`order: priority-v1`、kind `[1]`、`#t: [mosimo.oriom.dev]`、limit32 の有限取得。`CLOSED` または15秒の無応答時は `CLOSE` 後、**異なる subscription ID と新しい接続**の通常 `REQ` に1回だけ fallback（未知verbで接続を切るrelayも対象）。通常 `REQ` も15秒以内に EOSE が来なければ取得失敗（空成功ではない）。接続待ちは15秒。NIP-11自己申告を認可に使わず、通常 REQ の意味・順序は変更しない。

root→leaf **1証明のみ**を対応範囲とする。長い経路は unsupported として拒否し、中間を root 扱いしない。証明・root scope、ローカル選択、leaf DID/key、両 binding 署名、commit DID/low-S署名、Nostr ID/Schnorr署名、受信/作成時刻の証明期間、未来イベント、配送期限、fresh snapshot/失効を独立検証する。取得と暗号検証は mesh actor 外の worker で実行し、設定再読込・時計変更・オンライン停止で古い worker の結果を無効化する。投入直前も時計・証明・snapshot・配送期限と serial 履歴を確認する。

UI は `authorSignature` / `binding` / `chainScope` / `certificateTime` / `revocation` と理由を表示する。revocation は unknown / stale / revoked / fresh-as-of(issuedAt, validUntil, sequence) を区別する。保存時の認可記録（rootId、certId、Bot/type、sequence・issuedAt・validUntil、確認時刻・priority）は暗号化保存し、**当時の転載者認可**と表示する。原Botの署名・MST包含・現在のPLC認証・内容の真実性・現在の公式認証ではない。新たな snapshot で失効しても過去の本文/署名を削除しない。

priority は正しい単一の署名拡張だけを採用（無指定/不正/複数は1）。認可成功分だけ0..3、それ以外は最大1でローカル取得結果を処理する。priority は通常 mesh の保存容量・著者頻度・期限・hop・lifetime を免除せず、署名 event に書き戻さない。mesh scheduler 自体の新しい優先配送規則は追加しない。

### snapshot と既存状態の保全

事前設定 HTTPS URL に `Accept: application/cbor`。system TLS 検証を使用し、redirect は許可しない。connect10秒/read20秒/call25秒、CBOR32768 bytes 以下、root 署名・exact fields・canonical bytes・最大1時間の期間・時計を検証する。取得失敗時は **まだ fresh な検証済み cache だけ**を利用できる。

検証済み snapshot の元 CBOR を rootId ごとに、serial→certId/衝突の履歴とともに既存 `DiskState` の同じ AES-GCM ciphertext / `AtomicFile` に追加保存する。CBOR 内の sequence・body hash・issuedAt・完全CRL により rollback、同sequenceの異内容、issuedAt後退、失効集合の縮小を拒否する。root失効も sticky。期限切れ・root無効化/設定削除・再起動で履歴を捨てない。同issuer/serialの異なる署名bodyを見た場合は衝突を永久保持し、そのserialを再認可しない。

履歴保存を **Bundle 新規投入より先**に原子的に完了させ、Bundle 保存成功後だけ広告/搬出する。保存失敗は既存どおり fatal stop。旧 v1 状態に新フィールドがなければ空履歴で読み、既存の Nostr/atproto/PLC 鍵、repository head、relay設定、Bundle はそのまま保持する。壊れた履歴を無視して鍵を再生成しない。バックアップ復元・データ消失による完全な rollback 防止は保証しない（0020参照）。

追加のローカル上限: 保存履歴8 roots・256 issuer/serial（自動削除なし、満杯は fail closed）、CRL合計256、event64 tags/16 extensions、CBOR depth16/items8192、HTTP/WebSocket JSON262144 bytes/frame、取得32 distinct events/2MiB（fallbackとの合計）、256 frames/request。受信待機キューも34 frames/2MiBまで。公的情報の揮発表示 cache は直近32件、一般 relay cache は既存64件。mesh は既存128 entries、24,000-byte Bundle（payloadプロファイル32KiBより小さい）、BPv7 envelope preflight4096 items、著者12件/分、hop8・最大24時間を維持し、超過は切り詰めず拒否する。

### 再現可能なチェック

`apps/android` で（Windows Git Bash の例）:

```sh
env JAVA_HOME='C:/Program Files/Microsoft/jdk-21.0.6.7-hotspot' \
  ANDROID_HOME='C:/Users/chiba/AppData/Local/Android/Sdk' \
  ./gradlew :bundle-core:test :mesh-android:testDebugUnitTest :app:assembleDemoDebug
node wire-v1-fixture.mjs
node bundle-core/src/test/fixtures/server-publisher-fixture.mjs
```

Node チェックは既存 `pds/node_modules` の DAG-CBOR/noble/multiformats を使う（Android に新規依存は追加しない）。`--write` はテスト専用固定鍵の共有fixtureを再生成する。Node/Kotlin が同じ certificate/identity/binding/post/commit/snapshot の canonical bytes・hash・certId・署名を検査する。Kotlinには unknown/disabled root、DID/key/binding/commit不一致、片署名、high-S/DER、非canonical/重複key、scope拡大/未選択、leaf再委任/長い経路、時刻境界/unknown、snapshot鮮度/rollback/equivocation/失効集合縮小、root/leaf失効、serial衝突、資源上限、priority自己申告の negative checks がある。MockWebServerで priority EOSE、CLOSED/timeout/切断 fallback、無応答失敗とフレーム上限を検証する。

実 publisher の共有fixtureは **`bundle-core/src/test/resources/server-publisher-wire-v1.json`**。`bundle-core/src/test/fixtures/server-publisher-fixture.mjs --write` が `pds/public-information.mjs` の `createRoot/createImporter/issueCertificate/createSnapshot/createImporterEvent` を直接呼び、固定時刻 `1792368000`（認可確認は `1792368010`）で2件の連続する正規投稿を生成する。生成時の鍵は新規の使い捨てテスト専用で、秘密鍵はfixtureにもAPKにも保存しない。通常実行は再生成せず既存fixtureを検査する。Nodeは `decodePost`・`verifyCommit`・`reconstruct` と通常の `verifyPublicInformation` により署名・完全な連続MST・認可を検証する。Kotlinの `ServerPublisherIntegrationTest` は同じeventを認可し、既知の完全repository状態からのMST一致、record/commit/mesh bytes・CID、元eventを変えない通常Bundle配送を検査する。

ESP32/他workerも同じ公開fixtureを使える。最初のeventは旧fixture同様のtop-level `event/config/certificate/binding/snapshotHex/recordHex/commitHex` 等にあり、`posts` に2件とそれぞれの `meshPayloadHex`、`repositoryEntries`、`repositoryBlocks` を含む。旧 `wire-v1.json` は変異テスト用の署名/preimage fixtureとして維持するが、**正規MSTを持つpublisher統合fixtureではない**。新fixtureのMST検証は完全状態を持つテストの証拠であり、通常ingressの不完全な受信履歴をMST包含検証済みと表示する変更ではない。原Bot recordへの署名・PLC現在状態も証明しない。

BSAFの `original.createdAt` と任意 `botDefinition.retrievedAt` は実在する日時/offsetを検査し、元のfraction/offset表記を保持する。Bot定義URLはHTTP(S)の絶対URLに限定し、`file:`/`data:`等を認可済みmetadataとして受け入れない（URL先の取得は行わない）。

実機の Keystore/電断 AtomicFile 試験・配布 root と本番 importer/relay の end-to-end は別途必要。ここで APK ビルドや JVM テストを通しても、その実機検証が完了したとはしない。

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
- 地理の拡張 UI、一般の公的発信/update/cancel 認可、多段証明、rootの本番配布/移行 UI。
- 独立した鍵対応宣言の配送（公的転載identity内の既存format binding検証は対応）。
- Nearby への経路切替。
- mDNS が届かない網向けの手動アドレス指定。
- BPSec。
- メディア。
- iOS。
