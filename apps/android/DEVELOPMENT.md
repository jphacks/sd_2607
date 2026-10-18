# Android 開発・試験ガイド

機能概要・対応範囲は [README.md](README.md)、実機試験の手順・条件・記録は [VERIFICATION.md](VERIFICATION.md) を参照。

## ビルド・テスト

JDK 21、Android SDK 36を用意し、リポジトリ直下で実行する。

```sh
apps/android/gradlew -p apps/android \
  :dtn7-bpv7:test :bundle-core:test :mesh-android:testDebugUnitTest \
  :app:assembleDemoDebug :app:assemblePeerDebug
```

APKは `apps/android/app/build/outputs/apk/{demo,peer}/debug/` に生成される。`demo`（`org.mndt.demo`）と `peer`（`org.mndt.demo.peer`）は別の鍵・保存領域を持つ。

公開ネットワーク試験用のビルドは `:app:assembleVerificationDebug`（`org.mndt.demo.verification`）。実PLC登録・公開投稿を伴う試験に使うため、通常のローカルテストとして実行しない。実行条件は [公開ネットワーク検証報告](../../docs/android-production-verification-2026-10-18.md) を参照。

### 任意の試験用フィルタ

Gradleに次のプロパティを渡すと隔離試験に使える。両方とも既定は空で、既存保存データは削除しない。

- `-PmndtTestPeerNode=<16桁小文字hex>`：接続対象を指定nodeに制限する。
- `-PmndtTestAuthor=<64桁Nostr公開鍵>`：広告・Bundle提供・オンライン搬出・代理登録を指定著者に制限する。

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
- 既定リレーは `wss://pds.oriom.dev`、`wss://relay.damus.io`、`wss://nos.lol`。独自の保存済み一覧は自動更新されないため、PDSが必要なら一覧を確認して追加する。
- 自作投稿はPDSリレーの `OK` まで容量退避しない。未配送の自作投稿で128件が埋まると新規保存・投稿を拒否する。PDSを一覧から削除してもこの保護は続き、一般リレーの `OK` では解除しない。`OK` はatproto公開・Web表示の完了ではない。
- Manifestの `mndt.pds.endpoint` は `https://pds.oriom.dev`、`mndt.plc.directory` は `https://plc.directory`。`mndt.plc.registration.enabled` によるPLC登録・確認は搬出のON/OFFと独立し、搬出OFFでも実DID登録が発生し得る。
- 保存済みのPDS endpoint・鍵・DIDはManifest更新だけでは変わらない。SDKの未設定値 `.invalid` を保存済みなら、本人の鍵によるPLC更新などの移行判断が必要。アプリデータ削除で安易に別発信者へ置き換えない。
- オフライン発信者のPLC初期操作と投稿のbaseチェーンも配送対象として保持する。欠落すると投稿がリレーに受理されてもPDS復元が保留され得る。詳細な統合条件は [監査資料](AUDIT-2026-10-18.md) を参照。

ESP32との実機確認は [ESP32開発ガイド](../esp32/DEVELOPMENT.md#androidとの実機確認) を参照。JVMテスト・直接オンライン搬出の成功を、BLEを含む全経路の実証とは扱わない。
