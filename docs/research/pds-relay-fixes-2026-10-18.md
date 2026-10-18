# atproto Relay互換修正と本番検証（2026-10-18）

[初回互換性調査](pds-relay-compatibility-2026-10-18.md)で挙げた4点を修正した。
対象は公開同期PDSであり、一般のアカウント・OAuth・書込APIの追加ではない。

## 実装とデプロイ

- `4e24d37`: `describeServer`、論理PDSごとの`did:web`サービスDIDとDID Document。
  cursor省略時は保存済みstreamを先頭からページング再送する。明示cursorは排他的再開。
- `5528165`: 公式JS MSTの`getCoveringProof`で差分CARを生成する。
  CAR 2,000,000 bytes・200 ops・送信フレーム上限を超えた場合は、元の署名済みcommitだけの
  非空CARを持つ`#sync`へ切り替える。sync CARは10,000 bytes以下。
  上限内に収まらなければ公開トランザクションをrollbackする。
  5分超未来のrevはhead/stream変更前に保留し、既存の上限付き指数バックオフで再試行する。
- `e37efbb`: 独立した本番検証ツールを差分proofとsyncへ対応。
  `compatibility-live`は既存テスト著者で1件だけ新規投稿し、署名済みfixtureを送信前にGit外へ保存。
- 本番のNix入力: `e37efbbdbc66160ffc19e460d00ab44bcd62466d`。
- 適用したsystem closure:
  `/nix/store/lz70dvkpsgr9vkhr65rhyr3cg6a69ga4-nixos-system-kagoya-vps-26.05.20260409.4c1018d`。
- SQLite、Node 24、著者秘密鍵をサーバーへ置かない方式は維持。依存追加・DB移行なし。

## ローカル回帰試験

`pds`で実行:

```sh
npm test
npm run typecheck
node verify-production.mts selftest
```

133/133テスト、型チェック、ネットワーク要求なしのselftestが成功した。
未来revの5分境界、保留後の再試行、空blocksを出さないこと、フレーム上限、
2 MB超proof、200/201 ops境界、cursorなし初期再送と明示cursor再開を含む。

## 固定公式Indigoとの相互運用検証

Indigo `e076b63ff34a010d47ed1bfe82c767b23c3a7eb6`の
`repo.VerifyCommitMessage` / `VerifySyncMessage`と、実Relayパッケージの
`Relay.VerifyRepoCommit` / `VerifyRepoSync`を実行した。
Relay設定は`LenientSyncValidation=false`、合成著者鍵は一時生成した。

| ケース | 結果 |
| --- | --- |
| 200 createの初期commit、CAR 36,162 bytes | 署名・MST逆演算・Relay検証成功 |
| 201 create | commit-only sync CAR 285 bytes、公式検証成功 |
| 180 create、完全CAR 2,190,655 bytes | sync、公式検証成功 |
| フレーム上限1,800 bytesを超えるproof | sync、公式検証成功 |
| 64件・完全CAR 2,571,532 bytesの基底repo | sync、公式検証成功 |
| 上記基底への40回連続create、proof CAR 986–3,211 bytes | 全件署名・MST逆演算・Relay検証成功 |

計commit 41件・sync 4件が成功。syncは全て元署名を維持した1ブロックCAR。
誤ったsync revは公式ライブラリ・Relayの両方で拒否された。
誤ったprevDataは公式MST逆演算で拒否されたが、Relay wrapper自体は警告だけで成功した。
正常系は直接ライブラリの逆演算にも合格しており、この緩い挙動に依存しない。

WSLの一時成果物:
`/tmp/indigo-verify-wwyera5b/sync-fixtures/`。
Go harnessは同ディレクトリ内の固定Indigo sourceにある`fixture-verify.go`、
実行ファイルは`/tmp/indigo-verify-wwyera5b/fixture-verify`。
このGo検証はサーバー・DB・crawler全体を起動した隔離Relay試験ではなく、
一時成果物も恒久CIではない。JS側の回帰テストはリポジトリへ保存した。

## 本番の実Relayとの検証

### Discoveryと既存repoの取り込み

`https://pds.oriom.dev/xrpc/com.atproto.server.describeServer`:

```json
{"did":"did:web:pds.oriom.dev","availableUserDomains":[]}
```

`/.well-known/did.json`は同じDIDと`https://pds.oriom.dev`のPDS serviceを返した。
`bsky.network`の`com.atproto.sync.requestCrawl`へhostnameを送信して成功。
新規投稿前に、Relayの`getLatestCommit`と`getRepoStatus`で既存テスト著者のhead
`bafyreigc5ofkwhxokkm3ay4vqvqoshvwcykgazcvpwfh3othwfdkgobiwu`、rev `3my4cynwyos2m`、
`active:true`を確認した。初回調査時のHostNotFoundは解消した。

### 新規commitの中継

2026-10-18 01:11:32 UTC頃、`node verify-production.mts compatibility-live`を実行。
災害情報ではない旨を明記したテスト投稿1件を既存の専用著者で作成した。
同時に`wss://bsky.network/xrpc/com.atproto.sync.subscribeRepos`を購読し、
CBORをdecodeして対象DIDだけを抽出した。

- 著者: `did:plc:i2tq3bvlzchjxqa3qgckybmx`
- Nostr event: `4b7daaea853cf000f23149c23748e9e098306fa3df6ecd09497b058d93843136`
- commit: `bafyreih5zphzbyv47merxkddfwd7g7gtrrdp6ntmrvh4s6b2bhkmpana2e`
- rev: `3my4gdyq3gs2b`
- PDS seq: `18`
- **実Relayで受信した#commit seq: `34591481120`**

検証ツールは`ok:true`、`freshLive:true`で完了した。
新しいfirehose CARは4ブロック・変更レコード1件で、過去の未変更レコードは省略された。
署名・CID・元bytes・MST covering proofを検証し、完全getRepoは13ブロック・9件を検証した。
cursor省略の既存履歴取得と、cursor `16`からの再接続後の新規commit取得も成功。

その後、公開RelayのHTTP APIで以下を確認した:

```json
{"cid":"bafyreih5zphzbyv47merxkddfwd7g7gtrrdp6ntmrvh4s6b2bhkmpana2e","rev":"3my4gdyq3gs2b"}
{"active":true,"did":"did:plc:i2tq3bvlzchjxqa3qgckybmx","rev":"3my4gdyq3gs2b"}
{"accountCount":3,"hostname":"pds.oriom.dev","seq":18,"status":"active"}
```

API成功だけでなく、実Relayのfirehose中継まで確認した点が初回調査との違い。

## AppViewまでの取り込み検証（追加確認）

2026-10-18、同じVPSで稼働するHappyView 2.16.0について、上記の新規テスト投稿を追跡した。
追加投稿・設定変更・DB書込・再起動はせず、公開HTTP、journal、SQLite read-onlyで確認した。

経路は **PDS → 公開atproto Relay → Jetstream → HappyView → getFeed**。
HappyViewはPDSのCBOR firehoseを直接購読するのではなく、
`wss://jetstream1.us-east.bsky.network/subscribe`のJSONストリームを購読する。
接続ログでは`wantedCollections=dev.oriom.mosimo.post`と
`wantedCollections=com.atproto.lexicon.schema`を確認した。

対象URI:

```text
at://did:plc:i2tq3bvlzchjxqa3qgckybmx/dev.oriom.mosimo.post/3my4gdyq3gs2b
```

### 証拠

1. 前節で同じrkey/revのcommitを実Relay firehoseで受信済み。
2. `podman-happyview.service`のjournalに以下の受信ログがあった:

   ```text
   2026-10-18T01:11:32.821338Z DEBUG happyview::jetstream: received commit event from jetstream operation=create collection=dev.oriom.mosimo.post did=did:plc:i2tq3bvlzchjxqa3qgckybmx rkey=3my4gdyq3gs2b
   ```

3. 永続DB `/var/lib/containers/storage/volumes/happyview-data/_data/happyview.db`を
   `sqlite3 -readonly`で照会し、`happyview_records`の対象URIの行を確認:

   ```json
   {"cid":"bafyreihz5nh4753ej3kvqqc3fy2jevfml2asadc36hjkof6zfyqhk3h5ti","indexed_at":"2026-10-18T01:11:32.822311731+00:00","created_at":"2026-10-18T01:11:32.822311731+00:00"}
   ```

4. `https://appview.oriom.dev/xrpc/dev.oriom.mosimo.getFeed?uri=...`が200で対象recordを返した。
   AppViewが付与する`uri`を除き、PDSの`com.atproto.repo.getRecord`の`value`と
   Nodeの`assert.deepEqual`で全フィールド一致を確認した。DBのrecord CIDもPDSのCIDと一致した。
5. 同じgetFeedの`did=...&limit=20`でも対象URIを含む著者の9件を取得した。
   この9件全てについての個別CID・取り込み経路照合を追加実施したわけではない。

DBには`dev.oriom.mosimo.post`がrecord lexicon revision 1として登録され、
`dev.oriom.mosimo.getFeed` revision 2のtarget collectionが同コレクションであることも確認した。
API表示だけでなく、Jetstream受信ログ・永続DB・PDSとの内容照合が揃っており、
今回の新規投稿が中継経路を通ってAppViewに取り込まれ、APIから取得可能であることを確認できた。
Jetstream内部のどのRelay接続がイベントを供給したかは、このAppView側の証拠からは特定していない。

## 運用状態と残る範囲

反映・投稿後のsystemdはactive、PDS cgroup MemoryCurrent 82,620,416 bytes、
MemoryPeak 88,915,968 bytes、MemoryMax 536,870,912 bytes。
VPSのメモリは計2,903 MiB、available 2,214 MiBだった。
これは少量テスト時の観測値であり、負荷試験・大規模repoの動作保証ではない。

- 公開Relayに大容量投稿を流してのsync後の再取得end-to-end試験は未実施。
  大容量の公式validator検証と、getRepo完全CARのローカル回帰試験は実施済み。
- 過去に保存されたstream rowsは改変しない。本番の今回の既存fixtureには旧形式の
  空blocks tooBigはない。別環境にその履歴がある場合、今回の修正だけでは修復されない。
- 各更新の完全MST再構築と完全CAR保存による時間・容量の制限は残る。
- OAuth・アカウント作成・一般書込API、blob、鍵更新・DID移行の自動追従は未対応のまま。
- 本文を持つkind 1のcreate専用プロファイルの参加確認であり、全Nostr kindや
  全atproto機能の互換性を主張しない。
