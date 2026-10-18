# Android 実機→本番 Nostr／atproto 公開検証（2026-10-18）

## 結果

**成功。** Android 実機上の実 `MeshCoordinator` でオフライン生成した初回・継続投稿2件が、オンライン復帰後に PDS・Damus・nos.lol の全3リレーへ配送された。外部の読み取り専用クライアントから両件の署名付きイベントを取得し、PDS のレコード・最新 commit・CAR／MST と照合した。実 atproto Relay `bsky.network` でも同一 head を確認した。

試験投稿は `TEST ONLY - NOT DISASTER INFORMATION` と明記した2件のみ。新規 PLC DID はテスト用インストールの1件。秘密鍵・暗号化状態は端末外へ取り出していない。

## 対象と分離

- 端末：ASUSAI2501H、ADB 接続済み実機。
- 試験アプリ：`org.mndt.demo.verification`（表示名 `mosimo Public Network Test`）。既存の Demo／Peer と異なるアプリ ID・Keystore・保存領域。
- 試験 run：`android20261018T0301`。
- DID：`did:plc:22enadmulnb2e7urhi4hcvny`。
- Nostr 公開鍵：`48621e36908a8ecbaf377c9a786a93c112194b99a219b34a2494d658192035d2`。
- 正規 PDS：`https://pds.oriom.dev`。
- 本番配送試験中は既存 Demo／Peer のデータを保持。ESP32 書込み、PDS 設定変更、Relay crawl の再要求は行っていない。
- 試験後、通常 Demo の上書きは `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（既存 APK と署名鍵が不一致）で拒否された。その後の利用者の明示承認により、`org.mndt.demo` のみアンインストールして修正版 Demo APK を再インストールした。旧 Demo のローカル鍵・DID・投稿は引き継がず、次回起動は新規発信者になる。Peer と専用試験アプリのデータは保持し、通常 Demo のインストール後の起動・追加公開は行っていない。

端末は開始時に機内モード ON・Wi-Fi OFF・mobile_data 設定1、物理ネットワークなし。VPN の仮想インターフェースだけが残っていたため、試験ランナーは仮想インターフェースの残存と物理ネットワークの接続を区別する。Wi-Fi のみ一時的に有効化し、各試験の `finally` で試験アプリを停止・Wi-Fi OFF に復元した。機内モード・モバイルデータ・VPN の設定は変更していない。

## 手順と証拠

1. `verification` flavor の APK をビルド・別アプリ ID でインストール。
2. ネイティブ Instrumentation の `phase=queue` から、実 `MeshCoordinator.publish` を2回実行。BLE／LAN サービスは開始しない。
3. オフライン保存後にプロセスが終了。公開情報だけを `files/verification-public.json` から取得。
4. PC から実 PLC が404を返すこと、PDS・nos.lol の `REQ` に対象イベントが0件であることを確認。
5. Wi-Fi ON → `phase=observe` で同じ DID・event ID・本文のディスク復元を確認し、`setOnlineForeground(true)` で実際のオンライン搬出を開始。
6. Android が自動で PLC 初期操作を登録し、現在の鍵・PDS 設定の一致を確認。再署名・再投稿生成はせず、保持中の2件を送信。
7. 最初の観測は PDS と Damus の両件受理で終了。nos.lol の2件目は未配送だったため、試験ランナーの完了条件を PDS と nos.lol の両件受理へ変更し、同じ run を再開。追加投稿を生成せず残り1件を配送した。
8. PC の別クライアントから全3リレーへ `REQ` を送り、署名・著者・本文・ID を検証。PDS と nos.lol のイベント全体が一致。
9. 実 PLC の現在鍵と正規 PDS endpoint を読み取り、commit 署名、初回→継続の base chain、再構築 MST を検証。
10. PDS の `getRecord`／`getLatestCommit`／`getRepo` から、2レコード・最新 commit と公式ライブラリで読んだ CAR／MST を照合。
11. `bsky.network` の `getRepoStatus`／`getLatestCommit` が PDS と同じ rev・CID を返すことを確認。

| 確認項目 | 結果 |
| --- | --- |
| オフライン時 PLC | DID 未登録、HTTP 404 |
| オフライン時 PDS／nos.lol | 対象2件とも不在 |
| プロセスをまたぐ保存 | 同じ DID・署名済み event ID を復元 |
| PDS／Damus／nos.lol | 全3リレーで2件とも `OK=true`、外部から取得・署名検証成功 |
| 実 PLC | 初期操作の登録、現在鍵・正規 PDS 設定の一致 |
| PDS `getRecord` | 2件の本文・record CID・URI 一致 |
| PDS `getLatestCommit` | 2件目の CID・rev 一致 |
| PDS `getRepo` | 5ブロック、2レコード。全ブロック CID・元 bytes・署名・公式 MST 一致 |
| `bsky.network` | `active:true`、同じ最新 CID・rev |

### 投稿識別子

| 項目 | 初回 | 継続 |
| --- | --- | --- |
| event ID | `19ce3df4c83a33da82a0f49978b5af2d4bf631a4c152206f290dfce1f1453e2c` | `05e11efda16b130267afd539ee510778ace980f9052a318a9235225269561c30` |
| rkey | `3my4mwtyipcmx` | `3my4mwu34osmx` |
| record CID | `bafyreidk3r3bzn6exn2lnn3ajnmnozfztp22dxxkdl4bgrmyupuuu4gdpa` | `bafyreiarj36hmthi5x22c47ors5yzd2xa5yn43rvnvmf5s2ol6gmbs57ay` |

最終 commit CID：`bafyreichkigr44ysa6ire6hklbgjcvbygovbyz45aaatb43e5isky2l5ki`。
最終 rev：`3my4mwu34osmx`。

## 再実行・保存した公開証拠

- [オフライン時の公開情報](android-production-2026-10-18-offline.json)
- [最終 Android 公開情報・6件の受理観測](android-production-2026-10-18-public.json)
- [全3リレー・PLC・PDS の独立照合結果](android-production-2026-10-18-complete-evidence.json)
- [外部 atproto Relay の同一 head](android-production-2026-10-18-bsky-relay.json)
- [最初の搬出観測ログ](android-production-2026-10-18-observe.txt)
- [再開後の残件配送ログ](android-production-2026-10-18-observe-complete.txt)
- [早期停止時点の独立照合](android-production-2026-10-18-evidence.json)：atproto・PDS・Damus は成功、nos.lol は1件のみ。最終結果と混同しないこと。

リポジトリルートから、保存済み公開情報だけで再照合できる（投稿・PLC 登録は発生しない）：

```sh
node pds/verify-android-production.mjs --selftest
node pds/verify-android-production.mjs docs/android-production-2026-10-18-public.json --damus
```

`--output <新規ファイル>` で証拠を保存可能。既存ファイルは上書きしない。保持・接続状況が変化する可能性があるため、保存済み証拠と後日の結果は区別する。

最終確認では `:bundle-core:test :mesh-android:testDebugUnitTest :dtn7-bpv7:test` と Demo／Peer／Verification の全3 APK ビルドが成功。読み取り検証ツールの構文チェック・オフライン self-test と `git diff --check` も成功した。

実機ランナーは `:app:assembleVerificationDebug` でビルドする。`phase=queue` は物理ネットワークを切った状態が必須。`phase=observe` は投稿を生成せず、同じ run の保存済み2件だけを搬出する。別の run で既存著者履歴を不用意に延長しないよう、新規 run は既存自作投稿があれば拒否する。

## 検証の境界

- Android 実機の Keystore・暗号化保存・PLC 登録・TLS／WebSocket 搬出を使用したが、Compose のボタン操作や画面復帰そのものは自動操作していない。Instrumentation から UI と同じ公開 API を呼んだ。
- BLE／LAN の実機 DTN 経由の投稿は今回の本番試験には含まない。ESP32 を通したデータ互換性は別のホスト統合試験で確認済み。
- 今回は foreground 許可の経路。画面消灯中の前景サービス、OS 再起動、Doze・強制停止からの復帰は未検証。
- 外部 Relay は HTTP の head 一致で取り込みを確認した。今回の2 commit を実 Relay のライブ firehose で捕捉する試験は行っていない。
- アプリ内には依然として atproto 公開完了の監視機能はない。Nostr の `OK` と PDS 公開完了を同一視せず、今回の公開成功は外部検証ツールで独立確認した。
- `dev.oriom.mosimo.post` の公開であり、Bluesky の一般投稿や AppView 上の表示を確認した意味ではない。
- 試験用 DID・投稿は公開済みで完全消去を保証しない。テストアプリの鍵は端末の暗号化領域に保持している。
