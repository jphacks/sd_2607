# PDS本番動作検証 — 2026-10-18

## 結論

`https://pds.oriom.dev` の実VPS（Node.js 24 / SQLite）を使い、Nostr受理から署名済みcommitの復元、MST/CAR、XRPC・firehose配信まで確認した。実PLC Directoryと外部Nostrリレーも使用した。検証中に見つかった2件の不具合は修正して本番へ反映した。

ただし、**任意kindを受け入れる汎用Nostrリレーではなく、実Bluesky Relayへの取り込みも失敗した**。全要件に合格したという報告ではない。

検証は2026-10-17夜〜18日（UTC）。本番の最終ランタイム入力は `84fba1f2434b843eb27ed2ff5ad67263330ad7f9`。テスト専用DIDと少量の明示的なテスト投稿を使った。既存データの削除、偽DID resolver、署名検証の迂回、jobsの手動成功化は行っていない。

## 結果一覧

| 項目 | 実施環境・結果 |
| --- | --- |
| 通常のkind 1投稿 | 本番：署名付き投稿をACK、別購読者へのライブ配信、履歴取得を確認 |
| 今回のプロトコルのkind 1投稿 | 本番：生イベントの保存と、後段のcommit検証・公開を確認 |
| kind 5 | 本番：受理・履歴取得を確認。著者別削除抑止・削除後再投入拒否はローカルSQL統合テストで確認 |
| 任意kind | **非対応**。本番で署名済みkind 0 / 2 / 7 / 30023 / 10002を拒否。履歴にも保存されない |
| 不正Nostr署名 | 本番：拒否・履歴に不在を確認 |
| 設定済み外部リレーの履歴収集 | 本番：Damusにだけ投稿したイベントをPDSが収集・保存。直接PDSに投稿していない |
| 外部リレーのライブ収集 | 本番：未保存をEOSEで確認してからDamusにだけ新規投稿。同じPDS購読でEOSE後に受信 |
| Nostr → atproto | 本番：実PLC Directoryの公開鍵で検証し、8件の元commit・recordを復元。PDSが新しく著者署名を作るわけではない |
| MST・CAR | 本番：全CARブロックのCIDハッシュ、元commit/recordのバイト一致、署名、公式`MST.load().list()`による全8レコードの走査を確認 |
| firehose | 本番：CBORヘッダ・本文、identity/account、commit順序、rev/since/prevData/ops、各CARを検証 |
| 順序逆転 | 本番：孫→子を先行投入、親を3.555秒保留。head不変・先行firehoseなし。親投入後に正しい順序で3件公開 |
| 再起動・永続化 | 本番：`systemctl restart mosimo-pds`後も8件のMST、元署名、CAR、firehose履歴を再検証。SQLite `quick_check=ok` |
| 実Bluesky Relayへの参加 | **失敗**。`bsky.network`へのrequestCrawlがHTTP 400 / HostNotFound。取り込み・Relay経由配信は未達 |
| ローカル回帰テスト | `npm test`：115成功、失敗・skipなし。`npm run typecheck`成功 |

Nostrリレーの「中継」は本PDSの購読者への配信を検証した。設定先の全公開リレーへ投稿を自動送信する機能や、全NIP対応を意味しない。

## 修正した不具合

### 1. 実PLC Directoryの公開鍵項目と不一致

修正前は`identity.mjs`が`/data`の`signingKeys.atproto`を参照していた。実応答は`verificationMethods.atproto`であり、テストfixtureも誤った項目名だった。

本番では元イベント3件は受理されたが、全件が `retry / PLC resolution: Invalid PLC identity`、commitsは0件だった。fixtureを実応答形式に変更してテスト失敗を確認し、参照先を修正した。再デプロイ後、保留ジョブの通常の再試行で公開された。DBの状態・検証結果を手動改変していない。

修正コミット：`0f577a9`。

### 2. 外部履歴のキュー超過による先頭再生ループ

修正前は未処理フレームが100件を超えると切断していた。Damusの実購読では初回に約526フレームが届き、SQLite処理中に切断→履歴の先頭から再接続を繰り返し、後続の対象イベントに到達しなかった。

実WebSocketで526件を一括送信し、最初のingestを保留する回帰テストを先に追加して失敗を確認した。`createWebSocketStream`のreadable object modeと逐次取り込みでバックプレッシャーをかける方式に変更し、同じ接続で履歴全件と続くライブ投稿を受け取ることを検証した。テキスト/バイナリの区別、個別payload上限、heartbeat、再接続、処理中shutdownも確認した。

`highWaterMark=1`は厳密な1メッセージ上限ではない。既に受信・解析中のチャンクやTLS/socketバッファは残るが、無制限にPromiseを積まず継続的な読み込みを停止する。

修正コミット：`84fba1f`。本番設定に `wss://relay.damus.io` を追加して反映した。

## 本番の公開証拠

専用DID：`did:plc:i2tq3bvlzchjxqa3qgckybmx`

- [PLC公開データ](https://plc.directory/did:plc:i2tq3bvlzchjxqa3qgckybmx/data)
- [PDSの最新commit](https://pds.oriom.dev/xrpc/com.atproto.sync.getLatestCommit?did=did:plc:i2tq3bvlzchjxqa3qgckybmx)
- [PDSのCAR](https://pds.oriom.dev/xrpc/com.atproto.sync.getRepo?did=did:plc:i2tq3bvlzchjxqa3qgckybmx)

| 用途 | Nostr event ID |
| --- | --- |
| 初期base | `828664caedcce35bd184f745f389eaa0aea7d72c409cc6bdf8d1a82ed0404345` |
| 初期child | `108204c879a71f9e9ea92d3710c32c0df78d8e888337ebd520f8b70e61c7011a` |
| 初期grandchild | `962cfe9a2172b70d3821944ed04c865a168cddd7362794f7e81891b52e12028d` |
| 順序逆転テストparent | `0514ea99aa46c67a7a8bc847d9a8286032148a8a2a35d97bffdc7dd269624365` |
| 順序逆転テストchild | `3f346b519c2cfc0c27a8f8257fe6f747b8629835b6c743866788ba493e381709` |
| 順序逆転テストgrandchild | `ba6ae5131144591d6791ae379878a5fd9ba28d00240022e149810894a9cbe7c1` |
| Damusのみへ投稿・履歴回収 | `246c4ea4dbfb45192d069e16475db4a520f0612deda7f0415e9820cee3572ae7` |
| Damusのみへ投稿・新規ライブ回収 | `a36f8ef99602a81a19dd209aae316bd2048a26120b3c767003a135231d61e5a9` |

最終head：`bafyreigc5ofkwhxokkm3ay4vqvqoshvwcykgazcvpwfh3othwfdkgobiwu`。CARは12ブロック・8レコード。対象DIDのcommit seqは `3,4,5,6,7,8,9,16`。seqはグループ全体で共有し、10〜15は他のリポジトリのイベントなので、このDIDだけの飛びを履歴欠損と扱わない。

新規ライブ収集の時刻（UTC）:

- `00:11:30.688`：PDS購読のEOSE、イベント未保存を確認
- `00:11:30.697`：Damusだけに投稿
- `00:11:31.075`：同じPDS購読で元イベントと完全一致するEVENTを受信

順序逆転テストでは親を保留する間にheadを8回観測し、すべて不変。子孫の元イベントは履歴に存在し、親は不在で、接続済みfirehoseにも先行公開なし。親解放後の3commitはseq 6→7→8だった。

## 外部atproto Relayの不合格

以下を1回実行した（公開Relayへの参加要求になる）:

```sh
curl --json '{"hostname":"pds.oriom.dev"}' \
  https://bsky.network/xrpc/com.atproto.sync.requestCrawl
```

応答：HTTP 400。

```json
{"error":"HostNotFound","message":"host server unreachable: server is not a PDS: API request failed (HTTP 404): XRPCNotSupported: XRPCNotSupported"}
```

PDSの `com.atproto.server.describeServer` も個別にHTTP 404 / XRPCNotSupportedを確認した。ただしRelay側のエラーは失敗した具体的なメソッド名を示していないため、それだけを原因と断定していない。

同期APIを読める独立クライアントによるMST/CAR/firehose検証と、実Relayの発見・登録・取り込み適合性は別。後者には追加の互換性実装・検証が必要であり、適当な成功応答で通過させていない。Bluesky AppViewへの表示も未検証。

## 再実行

`pds/verify-production.mts`はNode.js 24のネイティブTypeScript型除去で実行する。各ステージは約60秒以内で終了し、公開証拠のJSONを出力する。`npm run typecheck`はこのツールも検査する。

**副作用あり**：初回`prepare`は実PLC Directoryに新しい専用DIDを登録し、他の投稿ステージは明示的なテストデータを本番・Damusに公開する。DID・署名済みイベント・回復用秘密鍵は `~/.mosimo-pds-verification/state.json` に保存し、Gitへ入れない。このディレクトリを安全に保管する。既存stateを消すと別のDIDが作られるため、再実行のために削除しない。

```sh
cd pds
npm ci
npm test
npm run typecheck
node verify-production.mts prepare
node verify-production.mts selftest
node verify-production.mts relay
node verify-production.mts pipeline
node verify-production.mts reorder
node verify-production.mts upstream wss://relay.damus.io
node verify-production.mts upstream-live wss://relay.damus.io
node verify-production.mts pipeline
```

`selftest`はネットワークアクセスなし。`pipeline`は既存stateに対応する公開データを読む。投稿ステージは保存済みイベントを再利用し、順序逆転・ライブテストの再実行時は保存済みの観測証拠を再利用したことを出力する。再利用を新規の時系列実験として数えない。

## 残る制限・運用上の注意

- 汎用kind対応、replaceable/addressable/ephemeralイベントのNIPごとの意味論は未実装。kind制限を外すだけでは対応済みにならない。
- 取得先リレーの履歴保持・結果上限による完全履歴回収の保証はない。再接続時に全履歴を取り直す設計上の上限は残る。
- 公開リレーからは他者のイベントも入る。最終確認では未登録DIDによるPLC HTTP 404のretryが19件残っていた。検証済みとは扱わず保留され、今回の正当な8commit処理は阻害しなかった。
- PDS全体のvalidated件数は10件、group seqは16。今回の専用DIDの8件以外も存在するため、他者データを削除・リセットしていない。
- 再起動直後のsystemdメモリ値は約51MiB、直前の短時間観測ピークは約77MiB。負荷・長時間安定性の保証や、RSSの厳密な計測ではない。
- ローカルのPostgreSQL系統合テストはPGliteを使い、advisory lockを置換している。実PostgreSQLの複数プロセス・障害切替は今回検証していない。
- peer同期、blob、OAuth、書き込みAPI、鍵ローテーション履歴、分岐解消、実機クラッシュ中の障害復旧・バックアップ復元、負荷試験は今回の本番試験の対象外。
