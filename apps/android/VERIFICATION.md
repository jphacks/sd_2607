# Android 検証メモ

## 期限前監査・保存応答順序の修正（2026-10-18）

- 受信 Bundle の暗号化保存を ACK・所持広告・オンライン搬出より先に完了するよう修正。未登録発信者の PLC 初期操作も広告前に保存する。
- 保存前の送信禁止と保存失敗時の ACK／広告抑止の回帰2件は、修正前に失敗・修正後に成功。`--rerun-tasks` で `bundle-core` 32件・`mesh-android` 43件を再実行し、失敗・skip なし。Demo／Peer／Verification の全 APK ビルドも成功。
- 今回は端末のインストール・設定・公開投稿を変更せず、ADB 一覧で ASUSAI2501H 1台だけを確認。過去の実機試験と今回の JVM 検証を区別する。
- [監査結果・合意済み仕様との差分・正確な統合条件](AUDIT-2026-10-18.md)を参照。最新 MNDT §3 の CBOR 交換とは未互換であり、ESP32 との現行接続は §9 の実験形式。

## 実機・本番検証（2026-10-18 03:09–03:12 UTC）

**成功。** 別アプリ ID `org.mndt.demo.verification` の実 `MeshCoordinator` で、オフライン生成した初回・継続投稿2件をプロセス終了後に復元。Wi-Fi 復帰後、PDS・Damus・nos.lol の全3リレーへ配送し、別クライアントから同じ署名付きイベントを取得できた。

実 PLC の初期操作登録・現在鍵・PDS 設定、PDS の2レコード・最新 commit・CAR／MST を照合し、`bsky.network` の最新 CID／rev も一致した。試験中は既存 Demo／Peer の保存データを保持し、Wi-Fi・機内モード設定も試験後に復元した。

試験完了後、通常 Demo は署名鍵の不一致で上書きできなかったため、利用者の明示承認により `org.mndt.demo` のみ削除・修正版を再インストールした。旧 Demo のローカル鍵・DID・投稿は引き継がない。Peer と専用試験アプリは保持した。

[本番検証報告と公開証拠](../../docs/android-production-verification-2026-10-18.md)を参照。今回の実機試験は Instrumentation から UI と同じ公開 API を使った foreground 搬出であり、Compose 操作・BLE 実機中継・画面消灯中の搬出を実証するものではない。

## ローカル・読み取り検証（2026-10-18）

- Android の `bundle-core` 31件、`mesh-android` 39件、`dtn7-bpv7` 33件が成功。Demo／Peer の APK ビルドも成功。
- 一般リレーが `OK=false` を返しても PDS と別リレーへ巡回する選択を、MockWebServer と実 WebSocket で回帰検証。実 `MeshCoordinator` を Android OS 上で駆動する試験ではない。
- 旧既定リレー一覧への PDS の一度限りの追加、独自一覧の保持を検証。
- 自作投稿の PDS 受理前の退避防止、全128件保護時の新規保存拒否と既存データ維持を検証。
- 実 Kotlin `Author.createPost` の初回・継続投稿を、ESP32 の本番 C++ `forwardBundle` → Kotlin 再受信 → ローカル PDS の実 WebSocket → SQLite／署名検証／MST 復元 → HTTP `getRecord`／`getLatestCommit` まで照合した。署名済み payload・event ID・record CID・commit CID が一致。未知・同型重複拡張、bytes・CID・null・日本語を含む。C++ は ASan／UBSan 有効。
  - PLC はテスト鍵のローカル解決を注入し、実 PLC 登録を行わない。無線リンク・Android 実機のオンライン復帰・公開 PDS への投稿の実証とは区別する。
- 本番の読み取り確認：`https://pds.oriom.dev/health` と NIP-11 は HTTP 200。PDS と nos.lol の WebSocket `REQ` に `EOSE` 応答。Damus はこの環境からの接続時に HTTP 503 を返した。投稿受理の試験ではない。
- PDS 全体は133件成功、新規統合テスト1件は fixture 未指定の通常 `npm test` では skip。統合ランナーではその1件も成功。`npm run typecheck` 成功。

再実行（この Windows／WSL 環境、リポジトリルートの Git Bash）：

```sh
JAVA_HOME='C:/Program Files/Microsoft/jdk-21.0.6.7-hotspot' \
ANDROID_HOME='C:/Users/chiba/AppData/Local/Android/Sdk' \
apps/android/gradlew -p apps/android --offline --no-daemon --console=plain \
  :dtn7-bpv7:test :bundle-core:test :mesh-android:testDebugUnitTest \
  :app:assembleDemoDebug :app:assemblePeerDebug

MSYS_NO_PATHCONV=1 python apps/esp32/test_bundle/test_repository_integration.py \
  --java 'C:/Program Files/Microsoft/jdk-21.0.6.7-hotspot/bin/java.exe' \
  --wsl Ubuntu \
  --mbedtls-include /home/mchiba/.cache/mesh-bundle-host/deps/usr/include \
  --mbedtls-library /home/mchiba/.cache/mesh-bundle-host/deps/usr/lib/x86_64-linux-gnu/libmbedcrypto.a
```

統合ランナーにはキャッシュ済み Kotlin 依存、ビルド済み BPv7 jar、PDS の npm 依存が必要。詳細は `apps/esp32/test_bundle/test_repository_integration.py --help` を参照。

### 残る制約

- アプリは atproto 公開完了を監視しない。Nostr の `OK` 後に、PLC・base 不足等で PDS の復元が保留されることはある。
- 他者から預かった投稿は既存の容量退避方針のまま。全投稿・全リレーへの永続配送は保証しない。更新前に失った base イベントの自動回復もない。
- 旧 `.invalid` endpoint を保存済みの場合は、リレー追加だけでは正規 PDS の設定は変わらない。
- Android／ESP32 は秒未満9桁を超える日時を受理できない。0010 の Timestamp との一般的な相互運用上の制限であり、現行 Android が生成するミリ秒精度の投稿は今回の経路で検証済み。
- 公開先への試験投稿は永続的に残り得る。初回の照合段階では本番投稿を行わず、その後の利用者承認により上記実機試験で専用 DID と2投稿のみを公開した。ESP32 書込みは行っていない。

## 以前の検証（2026-10-17、JVM・ビルドのみ）

- `bundle-core` 29件、`mesh-android` 35件の単体テストに成功。
  - 0012 §3.2.4 の基本タグ表との一致、拡張タグの自己完結性と配列順、メッシュ CBOR v1 の3種の型変換と非正規値の保持。
  - 不正タグの拒否、rkey と rev の独立、commit 署名・high-S の拒否。
  - 満杯時の退避順、再起動時の経過時間、期限切れ Bundle の搬出用保持。
  - `Exchange` を介した A→B→C の store-carry-forward（A と離れた後に B が C へ運ぶ）、HELLO のない接続の切断、自ノード接続の拒否。
  - フレームの分割と再組み立て、GATT 広告の解析と接続側の決定、LAN の loopback 送受信。
- `assembleDemoDebug` / `assemblePeerDebug` に成功。
- `lexicons/` の `npm test` 24件に成功。

形式変更前の Node 別実装による署名・CID・MST の独立照合と AVD 試験は旧形式・旧サービス構成に対するもの。現行形式のデータ相互運用検証は上記2026-10-18の追加検証を参照。AVD 試験は現行形式では未実施。

## 未検証（実機で要確認）

Keystore・暗号化保存・オンライン搬出は上記実機試験で確認済み。BLE・mDNS・画面消灯中の前景サービス・端末再起動は別途実機検証が必要であり、JVM テストの成功を無線の動作確認とは扱わない。

## 実機手順（2〜3台）

各端末に demo または peer の APK を入れ、一度開いて「中継開始」を押し、権限と通知を許可する。

1. **BLE のみ**: 機内モードにして Bluetooth だけ ON。A で投稿すると、操作なしで B に同じ投稿が届き、診断に `同期中（ble）` が出る。
2. **バケツリレー**: A と B を近づけて同期したら、A を圏外（または中継停止）にする。その後 B と C を近づけると、C に A の投稿が `観測 hop 2` で届く。3台が同時に直接見える配置では多段転送の確認にならない。
3. **LAN（NerveNet 相当）**: インターネットのない同じ Wi-Fi AP に接続し、Bluetooth を OFF にする。投稿が届き、診断に `同期中（lan）` が出る。
4. **バックグラウンド**: 全端末でホームに戻って画面を消灯しても 1〜3 が成立する。B を再起動すると、アプリを開かなくても中継が再開し、保持していた投稿を C へ渡せる。
5. **ゲートウェイ**: C をインターネットに接続すると、投稿が「リレー OK」になる。別の Nostr クライアントで `{"kinds":[1],"#t":["mosimo.oriom.dev"]}` を購読すると、同じ event ID と署名が見える。

確認すべき既知のリスク:

- 画面消灯中の Service Data フィルタによる scan（機種差）。
- API 26〜27 での 128-bit Service Data の広告。
- `BLUETOOTH_SCAN` の `neverForLocation` による結果の除外。
- NerveNet の基地局をまたぐ mDNS の到達性。
- Android 16 以降のローカルネットワーク権限。
