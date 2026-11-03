# Android 開発・試験ガイド

機能概要・対応範囲は [README.md](README.md)、実機試験の手順・条件・記録は [VERIFICATION.md](VERIFICATION.md) を参照。

## ビルド・テスト

JDK 21、Android SDK 36を用意し、リポジトリ直下で実行する。

```sh
apps/android/gradlew -p apps/android \
  :dtn7-bpv7:test :bundle-core:test :mesh-android:testDebugUnitTest \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APKは `apps/android/app/build/outputs/apk/debug/app-debug.apk` に生成される。アプリのIDは`dev.jisaku.mosimo`。旧アプリとは別インストールになり、鍵・DID・投稿は自動移行しない。


## mise タスク

`apps/android`で実行する。事前に`mise install`で`mise.toml`のツールを用意する。各タスクは必要に応じてAndroid SDK 36とBuild Toolsを`android sdk install`で用意し、fastlaneを使うタスクは不足するgemを`bundle install`でインストールする。初回はネットワーク接続やAndroid SDKライセンスの承認が必要。

miseの`android-sdk`は独自のSDKディレクトリを`ANDROID_HOME`に設定するため、別のSDKにインストール済みでも、タスク実行時にmise側のSDKへパッケージがインストールされる。

Windowsの古いAndroid CLIには、SDK操作後にネイティブダウンローダーの終了処理で`0xC0000409`（miseでは終了コード9）になる[既知の不具合](https://github.com/aws/aws-lc/issues/3540)がある。この環境ではCLI本体を`1.0.15857036`から`1.1.16717390`へ更新して解消した。`mise exec -- android --version`で本体の版を確認し、古い場合は`mise exec -- android update`を実行する。旧版からの更新では、更新完了の表示後に同じ終了エラーになる場合があるため、次の起動で版を再確認し、`mise run sdk-setup`が成功することを確かめる。miseでランチャーを入れ直すだけでは、ユーザーディレクトリにキャッシュされた古い本体が残ることがある。

Command-line Toolsはmiseで管理し、`sdk-setup`のインストール対象へ`cmdline-tools/23.0`を加えない。Windowsでは実行中のランチャーを自己上書きしようとしてアクセス拒否になる可能性がある。

```sh
mise tasks                  # タスク一覧
mise run test               # 単体テスト
mise run lint               # debugのlint
mise run apk                # debug APK

mise run fastlane-test      # Fastfileのテスト
mise run ci                 # fastlaneで単体テスト・lint・debug APKビルド
mise run aab
mise run deploy             # Google Playのalphaトラック（クローズドテスト）へ配布
```

`aab`は署名付きrelease AABを生成する。バージョンは`app/build.gradle.kts`の`versionCode`・`versionName`を使い、署名用環境変数が必要。Google Playへのアップロードは行わない。ローカルからPlayに配布するときは、`-PmosimoVersionCode=<値>`（または`ORG_GRADLE_PROJECT_mosimoVersionCode`）でアップロード済みの全バージョンより大きいversionCodeを指定する。環境変数と出力先は次節を参照。APKの出力先は上記「ビルド・テスト」を参照。

`deploy`は`secrets/release.sops.yaml`（署名パスワード・alias）と`secrets/deploy.sops.yaml`（`SUPPLY_JSON_KEY_DATA`）を復号して読み込み、テスト・lint・AAB生成後にGoogle Playの`alpha`トラック（クローズドテスト）へ配布する。両ファイルを復号できる鍵が必要。秘密値はmiseのログでマスクする。APK・AABのビルドだけでは`deploy.sops.yaml`は読み込まない。

GitHub Actionsなどでは、復号鍵ファイルのパスを`SOPS_AGE_KEY_FILE`、別途用意したアップロードkeystoreの絶対パスを`ANDROID_KEYSTORE_PATH`で指定して`mise run deploy`を実行する。未指定時はそれぞれ`.config/mise/age.txt`と`release_key.jks`を使う。復号鍵とkeystoreはGitに含めない。Actions workflowもこの方法で`mise run fastlane-test`・`mise run ci`・`mise run deploy`を実行する。

## Fastlane CI/CD・Google Playクローズドテスト

Rubyは`.ruby-version`（4.0.7）をmiseが読み込む。CIも`jdx/mise-action`で同じmise設定を使い、上記のmiseタスクを実行する。

CIはPRと`main`へのpushで単体テスト・lint・debug APKビルドを実行し、APKとレポートをActions Artifactsへ保存する。実機試験・PLC登録・公開投稿は実行しない。

### 署名と配布

Play Consoleで`dev.jisaku.mosimo`を登録し、初回AABの登録、Android Publisher APIの有効化、サービスアカウントへの対象アプリのテストトラックへのリリース権限の付与を確認する。署名には、対象アプリに登録した**アップロード鍵**を使う。

GitHubのSettings → Environmentsで`play-mosimo`を作成し、以下のEnvironment Secretsを登録する。署名パスワード・alias・JSON鍵は`secrets/*.sops.yaml`から復号する。

| Secret | 値 |
| --- | --- |
| `MISE_SOPS_AGE_KEY` | `.config/.sops.yaml`の`github_actions_release`に対応するage秘密鍵 |
| `ANDROID_KEYSTORE_BASE64` | アップロードkeystoreのBase64文字列 |

同じEnvironmentの**Deployment branches and tags**を`Selected branches and tags`にし、`main`だけを許可する。これで`main`以外のrefから実行した配布ジョブはSecretを読む前に拒否される。

認証ファイルをローカルに置く場合は、Git管理対象外の`fastlane/credentials/`を使う。秘密情報をコマンド引数や追跡対象ファイルに書かない。keystoreファイルもGit管理対象外とする。

GitHub Actionsの**Android → Run workflow**で、ref`main`・`app=mosimo`を指定する。`versionCode`はこのworkflowの実行番号（`github.run_number`）で自動採番し、`versionName`は`app/build.gradle.kts`の値を使う。同じ実行のRe-runは同じコードになりPlayに拒否されるため、再配布はRun workflowから新しく実行する。ローカル配布でversionCodeを指定する場合は、CIの実行番号と衝突しない値にする。

手動配布はテスト成功後に署名済みAABを生成し、`alpha`トラックへ`completed`として送信する。配布前にAPKをビルドしてからエミュレータを起動し（同時に動かすとエミュレータが重くなりランチャーのANRダイアログが写り込む）、エラーダイアログを非表示にしてスクリーンショットを撮影し、ストア掲載のスクリーンショットを置き換える（掲載文のテキストファイルは置かないため変更されない）。撮影結果もArtifactsに保存する。不足するSecret、ビルド失敗、バージョンコードの衝突、Play APIエラーは失敗として報告する。同一アプリの配布は直列化し、keystoreの一時ファイルは終了時に削除する。AABと検証レポートはArtifactsに保存する。

### Play Store用スクリーンショット

手動配布ジョブがエミュレータ（`pixel_2`、1080×1920。Playの比率制限「長辺は短辺の2倍まで」を満たす）を起動し、screengrabで`fastlane/metadata/android/ja-JP/images/phoneScreenshots/`へ撮影してから配布する。撮影結果は生成物としてGit管理しない。撮影内容は`app/src/androidTest/kotlin/org/mndt/app/ScreenshotTest.kt`、設定は`fastlane/Screengrabfile`。`-PmosimoPlcRegistration=false`で本番PLCへの登録を止める。ローカルで確認する場合は同等のエミュレータを起動し、`apps/android`で実行する。

```sh
mise exec -- ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PmosimoPlcRegistration=false
mise exec -- bundle exec fastlane screengrab
```

ローカルでAABだけを生成する場合は、次の環境変数を秘密管理ツールなどから設定し、`build`を実行する。Gradleの`signingConfigs.release`が署名用の環境変数を直接読み取るため、秘密情報をGradleのコマンドライン引数には渡さない。JSON鍵は`internal`のときだけ必要。

```text
ANDROID_KEYSTORE_PATH      アップロードkeystoreの絶対パス
ANDROID_KEYSTORE_PASSWORD  keystoreのパスワード
ANDROID_KEY_ALIAS          鍵alias
ANDROID_KEY_PASSWORD       鍵のパスワード
SUPPLY_JSON_KEY_DATA       サービスアカウントJSON鍵（配布時のみ）
```

```sh
bundle exec fastlane android build app:mosimo
# テストからPlayのalpha配布まで実行する場合（lane名はinternalのまま）
bundle exec fastlane android internal app:mosimo
```

生成先は`app/build/outputs/bundle/release/app-release.aab`。初回導入時はAABのpackage ID・バージョン・署名証明書を登録済みの値と照合し、Play Consoleのクローズドテスト（Alpha）への反映を確認する。CI成功は実機のBLE通信・アプリ間相互運用の確認を意味しない。

### 別アプリを追加する場合

`apps/android`は複数製品で共有するGradleルート。現在の`:app`は「もしもの防災」用とし、別製品は`:messenger`などのapplicationモジュールとして追加する。UI・applicationId・versionは製品ごとに分け、`:mesh-android`・`:bundle-core`を共有する。アプリは標準の`debug`・`release`のみを使い、別製品をflavorとして追加しない。

1. 新モジュールを`settings.gradle.kts`へ登録し、既存共通ライブラリへの依存を設定する。
2. `fastlane/Fastfile`の`ANDROID_APPS`へ識別名をキーとして`module`・Playの`package`のみを追加する。flavorはサポートしない。AABはAGP標準の出力名を使う。スクリーンショットの`metadata_path`・`Screengrabfile`はアプリ別に分ける。
3. `.github/workflows/android.yml`の`inputs.app.options`へ識別名を追加し、CIの`app:`指定もアプリ別matrixにする。
4. `play-<識別名>`のEnvironmentに対象アプリのSecretsを登録する。Play掲載情報とバージョンコードはアプリごとに管理する。

参考：[Fastlane Gradle action](https://docs.fastlane.tools/actions/gradle/) / [Play配布](https://docs.fastlane.tools/actions/upload_to_play_store/) / [screengrab](https://docs.fastlane.tools/actions/screengrab/)

### 任意の試験用フィルタ

Gradleに次のプロパティを渡すと隔離試験に使える。いずれも既定は空で、既存保存データは削除しない。

- `-PmosimoTestPeerNode=<16桁小文字hex>`：接続対象を指定nodeに制限する。
- `-PmosimoTestAuthor=<64桁Nostr公開鍵>`：広告・Bundle提供・オンライン搬出・代理登録を指定著者に制限する。受信・保存自体は制限しない。
- `-PmosimoTestNode=<16桁小文字hex>`：自分のnode IDを固定する。未指定ならランダムに生成する。

認証・本番アクセス制御の代替ではない。nodeはプロセスごとの一時値なので、現在のログで確認し、過去のallowlistを再利用しない。

## 組み込み・変更箇所

| モジュール | 責務 |
| --- | --- |
| `bundle-core` | Android非依存の署名、atprotoレコード・MST・commit、メッシュCBOR、BPv7の検証・保存・転送 |
| `mesh-android` | `GattLink` / `LanLink`、`Exchange`、暗号化保存・搬出の `MeshCoordinator`、`MeshService` / `BootReceiver` |
| `app` | Compose UIとアプリ設定 |
| `../../third_party/sd_2607-dtn7-kotlin` | BPv7コーデックの固定スナップショット |

SDKとして使う場合は `MeshCoordinator.get(context)` で初期化する。

- `MeshPermissions` で権限を確認し、`MeshService.start/stop(context)` で中継を開始・停止する。
- `publish` で投稿し、`addRelay` / `removeRelay` / `setOnlineEnabled` で搬出先と有効状態を操作する。
- Activityの `onStart` / `onStop` から `setOnlineForeground(true/false)` を通知し、UIは `state` を購読する。

wire形式を変更する際は [MNDT §9](../../protocol/mndt.md#9-android-実装の暫定値提案未採用) とESP32実装を照合する。最新§3のCBOR交換とは接続内で混在させず、移行は両実装で協調して行う。タグ・データ表現は [0012](../../protocol/0012-bindings.md) を正とする。

`AtRepository.restore(event, atprotoPubkey?)` はタグからレコードとcommitを復元する。公開鍵を渡した場合だけcommit署名も検証するため、認証用途では信頼できるDID documentから取得した鍵を使う。DID認証・MST包含検証を行うAPIではない。受信側はrecord keyと `rev` の一致を前提にしない。

## 試験・公開時の注意

- 投稿前に中継・オンライン搬出の状態と保存済み投稿を確認する。搬出を有効にすると、自作投稿だけでなく預かった投稿も送信対象になる。未承認のテスト投稿や他者イベントを誤公開しない。
- 既定リレーは `wss://pds.oriom.dev`、`wss://relay.damus.io`、`wss://nos.lol`。独自の保存済み一覧は自動更新されないため、PDSが必要なら一覧を確認して追加する。オンライン搬出は画面表示中または中継サービス稼働中に行い、拒否・接続失敗の再試行待機はリレーごとに独立する。一般リレー購読で取得したイベントは揮発表示専用でメッシュには投入せず、下記の手動公的情報取得だけが0020の独立認可を経て投入できる。
- 自作投稿はPDSリレーの `OK` まで容量退避しない。未配送の自作投稿で128件が埋まると新規保存・投稿を拒否する。PDSを一覧から削除してもこの保護は続き、一般リレーの `OK` では解除しない。`OK` はatproto公開・Web表示の完了ではない。
- Manifestの `mndt.pds.endpoint` は `https://pds.oriom.dev`、`mndt.plc.directory` は `https://plc.directory`。`mndt.plc.registration.enabled` によるPLC登録・確認は搬出のON/OFFと独立し、搬出OFFでも実DID登録が発生し得る。
- 保存済みのPDS endpoint・鍵・DIDはManifest更新だけでは変わらない。SDKの未設定値 `.invalid` を保存済みなら、本人の鍵によるPLC更新などの移行判断が必要。アプリデータ削除で安易に別発信者へ置き換えない。
- オフライン発信者のPLC初期操作と投稿のbaseチェーンも配送対象として保持する。欠落すると投稿がリレーに受理されてもPDS復元が保留され得る。詳細な統合条件は [監査資料](AUDIT-2026-10-18.md) を参照。

ESP32との実機確認は [ESP32開発ガイド](../esp32/DEVELOPMENT.md#androidとの実機確認) を参照。JVMテスト・直接オンライン搬出の成功を、BLEを含む全経路の実証とは扱わない。

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

Scope は各 Bot 最大32個の明示的 type、合計64 Bot/type（最大16 Bots・16 orgs）に対応する。既存 JMA 5種と Kikikuru (`did:plc:kxwz5cz7o6g4jlmxh6doyfsm`) の23種を同時に選択できるが、root Scope と leaf Scope の包含・個別のローカル選択が必須で、Bot定義や `source:jma` から自動拡大しない。配布時の空 roots/selected は変更せず、承認した公開設定を provision するまで無効のまま。

### 再現可能なチェック

`apps/android` で（Windows Git Bash の例）:

```sh
env JAVA_HOME='C:/Program Files/Microsoft/jdk-21.0.6.7-hotspot' \
  ANDROID_HOME='C:/Users/chiba/AppData/Local/Android/Sdk' \
  ./gradlew :bundle-core:test :mesh-android:testDebugUnitTest :app:assembleDebug
node ../esp32/test_bundle/generate_server_fixture.mjs --check --combined
node wire-v1-fixture.mjs
node bundle-core/src/test/fixtures/server-publisher-fixture.mjs
```

Node チェックは既存 `pds/node_modules` の DAG-CBOR/noble/multiformats を使う（Android に新規依存は追加しない）。`--write` はテスト専用固定鍵の共有fixtureを再生成する。Node/Kotlin が同じ certificate/identity/binding/post/commit/snapshot の canonical bytes・hash・certId・署名を検査する。Kotlinには unknown/disabled root、DID/key/binding/commit不一致、片署名、high-S/DER、非canonical/重複key、scope拡大/未選択、leaf再委任/長い経路、時刻境界/unknown、snapshot鮮度/rollback/equivocation/失効集合縮小、root/leaf失効、serial衝突、資源上限、priority自己申告の negative checks がある。MockWebServerで priority EOSE、CLOSED/timeout/切断 fallback、無応答失敗とフレーム上限を検証する。

実 publisher の共有fixtureは **`bundle-core/src/test/resources/server-publisher-wire-v1.json`**。`bundle-core/src/test/fixtures/server-publisher-fixture.mjs --write` が `pds/public-information.mjs` の `createRoot/createImporter/issueCertificate/createSnapshot/createImporterEvent` を直接呼び、固定時刻 `1792368000`（認可確認は `1792368010`）で2件の連続する正規投稿を生成する。生成時の鍵は新規の使い捨てテスト専用で、秘密鍵はfixtureにもAPKにも保存しない。通常実行は再生成せず既存fixtureを検査する。Nodeは `decodePost`・`verifyCommit`・`reconstruct` と通常の `verifyPublicInformation` により署名・完全な連続MST・認可を検証する。Kotlinの `ServerPublisherIntegrationTest` は同じeventを認可し、既知の完全repository状態からのMST一致、record/commit/mesh bytes・CID、元eventを変えない通常Bundle配送を検査する。

ESP32/他workerも同じ公開fixtureを使える。最初のeventは旧fixture同様のtop-level `event/config/certificate/binding/snapshotHex/recordHex/commitHex` 等にあり、`posts` に2件とそれぞれの `meshPayloadHex`、`repositoryEntries`、`repositoryBlocks` を含む。旧 `wire-v1.json` は変異テスト用の署名/preimage fixtureとして維持するが、**正規MSTを持つpublisher統合fixtureではない**。新fixtureのMST検証は完全状態を持つテストの証拠であり、通常ingressの不完全な受信履歴をMST包含検証済みと表示する変更ではない。原Bot recordへの署名・PLC現在状態も証明しない。

BSAFの `original.createdAt` と任意 `botDefinition.retrievedAt` は実在する日時/offsetを検査し、元のfraction/offset表記を保持する。Bot定義URLはHTTP(S)の絶対URLに限定し、`file:`/`data:`等を認可済みmetadataとして受け入れない（URL先の取得は行わない）。

追加の共有公開fixture `../esp32/test_bundle/wire-v1-combined-server-fixture.json` は実サーバーAPIが発行した JMA 5種 + Kikikuru 23種の同一 certificate・連続28投稿を含む。上記 generator の `--write --combined` で再生成し、`--check --combined` で byte/provenance drift を検出する（テスト専用の決定的乱数、秘密鍵の読込・出力なし）。Gradle はこのJSONだけを test resource に追加し、APK/default trust に含めない。`PublicIngressTest` は全28種の認可・mesh payload一致、未選択extra・unknown/disabled root・root Scope拡大の拒否、32/33 types/Bot・64/65総数境界を検査する。Kikikuruの23種は一次 bot-definition の `type` options に固定し、元定義の更新で自動追加しない。

実機の Keystore/電断 AtomicFile 試験・配布 root と本番 importer/relay の Android end-to-end は別途必要。ここで APK ビルドや JVM テストを通しても、その実機検証が完了したとはしない。ESP32 combined版の後続実機結果は [GATEWAY-HANDOFF.md](../esp32/GATEWAY-HANDOFF.md) にあり、Androidでの全経路検証とは区別する。

一般の公的発信・update/cancel認可、多段証明、rootの本番配布・移行UI、独立した鍵対応宣言の配送は範囲外。公的転載identity内の既存format binding検証は対応する。
