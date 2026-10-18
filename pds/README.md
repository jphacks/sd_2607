# Mosimo PDS

端末署名済みリポジトリを収集・復元する物理PDS。既定のNixOS構成はNode.js 24＋SQLiteを
ネイティブsystemdサービス`mosimo-pds`で実行する。OCIイメージやenv-fileは不要
（peer認証を設定する場合はenv-fileが必要）。PostgreSQLとOCI実行も明示指定で選択できる。
著者のNostr鍵・atproto署名鍵・PLC管理鍵を生成・保持せず、署名も代行しない。

## データの流れ

1. 外部Nostrリレーを購読、または内蔵Nostrリレーへ`EVENT`で投稿。
2. event ID・BIP-340署名を`nostr-tools`で検証し、元イベントを保存。
   `OK`はNostr受理であり、atproto公開完了ではない。
3. [基本版1のタグ](../protocol/0012-bindings.md#324-通常投稿の基本タグ合意済み)から、
   レコード、端末署名済みcommit、base CID、pathを復元。未知の拡張・配列順・bytes・CIDを保持。
4. 信頼するPLC DirectoryからDIDの現在の署名公開鍵・正規PDS endpointを取得し、
   `@atproto/crypto`でcommit署名を検証する。
5. 検証済みbaseの状態へcreateを適用し、`@atproto/repo`のMSTでrootを再計算。
   署名済みcommitの`data`と一致した場合のみ検証済みとして保存する。
   base不足は保留し、逆順で届いた更新も後から再試行する。
6. 正規公開先が自グループに一致し、現在のheadの直系更新であれば、headとfirehoseを
   同一トランザクションで更新。過去commitや分岐でheadを巻き戻さない。
7. 設定済みpeerへ小さな通知を送り、受信側が必要な元イベントだけを認証付きで取得・再検証。

BSAF再配信は **Nostrのみ**。`bsafV1`拡張の`original`を持つイベントは元の署名付き
EVENTを保存・REQ配信・peer転送するが、atproto検証job・commit保存・repo更新・firehoseへは
流さない。構造分類はroot鮮度やimporter DID許可リストに依存しない。不正なBSAF拡張も
atproto側へfail-closedし、更新前から残るpending/retry jobにも同じ分類を適用する。
`original`のない通常のBSAFユーザー投稿は従来のatproto経路を維持する。
既存のcommit/streamは削除・巻き戻ししない。AppViewの表示・queryは変更せず、過去レコードも保持する。

SQLiteは**単一物理ノード・永続ローカルディスク専用**。ネットワーク・共有ファイルシステムへ
置かず、同じ論理PDSを別々のSQLite DBで負荷分散しない。複数物理ノードで同じ論理PDSを
提供する場合は共通PostgreSQLを使い、採番を直列化する。公開headとstreamは`group_id`で
分離する。異なるDBのpeer間で同じstreamの合意形成を行う実装ではない。

## 外部インターフェース

- `/`: NIP-01の`EVENT` / `REQ` / `CLOSE`、履歴・ライブ購読。
- `/`へ`Accept: application/nostr+json`: NIP-11。kind 1/5を受け入れる。
  古い`created_at`・独自タグだけを理由に拒否しない。
- NIP-09: 著者一致のevent ID削除要求を履歴・ライブ・peer取得で抑止し、再投入も拒否。
  kind 5自体は削除しない。atprotoの削除commitをサーバー側で捏造しない。
- Hostで選択する論理グループの`com.atproto.sync.subscribeRepos`:
  CBORのidentity/account/commit/syncイベント、永続seq、cursor再送、遅い購読者の切断。
    cursor省略時は永続履歴の先頭から再送し、初回Relayの既存repo発見にも使う。
- `com.atproto.sync`: `listRepos`, `getRepo`, `getCheckout`, `getHead`, `getLatestCommit`,
  `getRepoStatus`, `getBlocks`, `getRecord`, `listBlobs`, `getBlob`。
- `com.atproto.repo`: `describeRepo`, `getRecord`, `listRecords`。
- `com.atproto.server.describeServer`と`/.well-known/did.json`: 論理PDSのサービスDIDとendpoint。
- `/internal/notify`と`/internal/events/<event-id>`: Bearer認証必須の実装固有peer API。
- `/health`: プロセスの生存確認。

CARは公式ライブラリで生成し、元commit/CID/rev/署名を維持する。getRepoは完全CAR、
通常のfirehoseは変更レコードとMST covering proofを含む差分CARを返す。
2,000,000 bytes・200 opsまたは送信フレーム上限を超える更新は、空blocksの`tooBig`ではなく
署名済みcommit CAR付き`#sync`で通知する。完全データはgetRepoから取得する。
現在時刻より5分超先のrevは署名を書き換えず保留・再試行し、head/streamへ公開しない。
stream履歴は現状全保持であり、独自の履歴切り詰め・欠損の隠蔽を行わない。

## Opt-in wire-v1 priority history

NIP-11 advertises `mosimo.wire: 1` and `mosimo.priority_order: ["priority-v1"]`.
Standard `REQ` is unchanged: newest `created_at` first, then ascending event ID,
with ordinary filters, limits and live subscriptions. Unknown filter fields never opt in.

```json
["MOSIMO-REQ", "priority-history", {"order":"priority-v1","filter":{"kinds":[1],"#t":["mosimo.oriom.dev"],"limit":100}}]
```

This custom verb returns a **finite stored snapshot**, sorted by freshly evaluated
`effectivePriority DESC, created_at DESC, id ASC` **before** applying limit.
Only one filter is allowed; limit must be 1..min(100, `maxResults`). IDs must be
1..64 printable ASCII bytes and cannot collide with an existing subscription.
Replies are standard `EVENT`, then `EOSE`; the ID is released, with no live subscription.
`CLOSE` cancels pending work. Invalid requests, ID conflicts, unavailable durable state
or resource exhaustion return bounded `CLOSED` reasons, never a truncated newest-only
priority result. Fall back with a **different ID** to standard `REQ` if unsupported/closed.
After sorting, the relay rechecks **every candidate** against current durable trust and
the current clock. Any priority change closes the request before sending events rather
than serving stale ordering. A final synchronous deadline check covers certificate,
snapshot and delivery expiry while the recheck itself was queued/running.

Budgets are one active priority request per process, at most 1,000 matching candidates,
8 MiB candidate JSON, and five seconds for scanning and both authorization passes
after initial snapshot retrieval.
Exceeding a budget closes the request before sending events. Snapshot retrieval is
bounded separately per root (10 seconds, 32,768 bytes, no redirects, CBOR only),
coalesced by the process-wide priority slot and attempted at most every 30 seconds
per root. A fresh persisted snapshot remains usable during a fetch failure; expiry
removes authority, not the stored Nostr event. Each configured root updates independently.
Due refreshes run before the candidate scan, even when its resource budget later rejects
the request; repeated requests do not postpone a root's next refresh.

`publicInformation` defaults to `{ "roots": [], "selection": [] }`, granting **no**
elevated priority. Only explicitly enabled public roots and selected Bot/type pairs
can authorize hints above one. The shared `verifyPublicInformation` helper validates
author signature, chain, binding, commit, scope, time and fresh revocation state.
Authorization failures cap hints at one (zero stays zero); self-declared priority or
BSAF source/severity alone never grants authority. Receivers must independently verify:
relay ordering is not an ingress authorization or a claim of current JMA authorship.

SQLite/PostgreSQL persist complete signed snapshots (sequence, body hash derivable
from canonical body, issuedAt and cumulative CRL) and issuer+serial → certificate ID.
Snapshot updates commit **before** event authorization, including root revocation;
rollback, equivocation, earlier issue time, CRL removal and serial reuse are rejected.
Issuer+serial conflicts commit a sticky `trust_serial_conflicts` marker in their own
transaction **before** rejecting authorization. Both the original and conflicting
certificate bodies remain unauthorized after restart; re-presenting A after A→B never
restores A. The marker is bounded to one row per recorded issuer/serial pair. A marker
write/commit failure closes the priority request, never degrades into authorization.
Expired/disabled-root state is retained across restarts. Security state is never evicted
to authorize new events: 64 historical roots and 100,000 issuer/serial pairs are hard
caps; capacity/write failures close priority requests. Back up the entire database;
restoring an old backup can roll back security state without external rollback protection.
See [deployment.md](deployment.md#public-wire-v1-trust-configuration) for provisioning.

## 起動とテスト

```sh
cd pds
npm ci
npm test
```

手動起動では`CONFIG_FILE`に設定JSONへのパス、`DATABASE_PATH`にSQLite DBの絶対パスを
指定して`npm start`（親ディレクトリは事前に作成）。PostgreSQLを使う場合は`DATABASE_PATH`を
設定せず、`DATABASE_URL`に接続URLを指定する。両方ある場合は`DATABASE_PATH`が優先される。
初回起動で選択したDBのスキーマを作るが、PostgreSQLからSQLiteへの自動移行は行わない。
標準のPDS管理者パスワードや著者署名鍵の設定はない。

NixOSでは`pds/default.nix`をimportして有効化すると、`package.nix`でNode.js 24のパッケージを
ビルドし、`StateDirectory=mosimo-pds`の`/var/lib/mosimo-pds/pds.sqlite`へ保存する。
SQLiteはWAL・`synchronous=FULL`・キャッシュ約2 MiB。サービスの`MemoryHigh=384M`、
`MemoryMax=512M`、Nodeのold-space上限256 MiBは保護用の設定値であり、VPS上での
消費メモリ・処理性能の実測結果や動作保証ではない。
設定・Caddy・peer認証・安全なバックアップ手順は[deployment.md](deployment.md)を参照。

実装の責務:

| ファイル | 責務 |
| --- | --- |
| `repository.mjs` | 基本版1の検証・復元、commit署名、MST/CAR |
| `identity.mjs` | 固定PLC DirectoryからのDID解決。任意URLへアクセスしない |
| `store.mts` / `store.mjs` | SQLite/PostgreSQL接続、スキーマ、トランザクション。Node.js 24のネイティブ型除去で実行 |
| `ingest.mjs` | 受理、削除要求、復元ジョブ、公開head・firehose |
| `sync.mts` | covering proof、上限内のcommit/syncフレーム生成、未来rev判定 |
| `server.mjs` | Nostrリレー、標準XRPC、firehose、peer受信 |
| `network.mjs` | 外部リレー収集、peer通知・取得・再試行 |
| `config.mjs` / `main.mjs` | 設定検証、起動・終了 |

## 現時点の境界

これはドラフトで合意された**公開テキスト・create専用プロファイル**の実装であり、
一般の書き込み可能なBluesky PDS一式ではない。2026-10-18の修正後、
**実Relay `bsky.network`への登録・既存repo取り込み・新規commit中継を確認した**。
全機能・全条件での完全な互換性を確認済みとは主張しない。
kind 1/5以外のNostrイベントは拒否するため、汎用Nostrリレーでもない。

- 本文を持つkind 1からの復元のみ。外部クライアントのcreateRecord/applyWrites、
  セッション/OAuth、メディア・blobの保存は提供しない。listBlobsは空、getBlobはBlobNotFound。
- PLC初期操作のオフライン代理登録、両鍵署名の鍵対応宣言、削除commitの制御イベントは
  [wire形式が未確定](../protocol/0012-bindings.md#34-制御データ)なので独自形式を発明していない。
  端末が通常経路でPLC登録済みであることが必要。Nostr鍵とDIDの対応は認証済みと表示せず、
  リポジトリの著者性はDID公開鍵によるcommit署名とrootの一致で検証する。
- PLCは現在の状態のみ参照する。鍵更新をまたぐ古い署名の履歴検証、DID移行・
  アカウント状態変更の自動追従・分岐解消は未対応。運用中の移行は手動で公開を停止して管理する。
  ハンドルのDNS/HTTPS逆引きは未実装で、`handleIsCorrect`はfalse。
- [延期された欠落commit探索](../protocol/0006-logical-pds.md#24-mst-の再構築)は実装しない。
  既知リレーと承認済みpeerから届く情報で補完する。Nostrの履歴制限・切断による欠落が
  自動的に完全回収できる保証はない。保留ジョブを監視する。
- peer API/NIP-11の独自情報は実装固有で、プロトコル標準化済みではない。
  通知は承認済みpeerにだけ送信し、受信イベントは再通知しない。公開URLの自動発見はしない。
- 現在のMSTは各更新時に全件から再構築し、commitごとに完全CARを保存するため、
  大規模リポジトリには時間・容量上の制限がある。容量監視とDB側の資源制限が必要。
- サイズ・購読・送信待機・接続数に上限はあるが、匿名投稿のレート制限やネットワークごとの
  濫用対策はCaddy/ネットワーク側にも設ける。削除は配信抑止であり、DBからの物理消去ではない。

2026-10-18、本番VPS・実PLC Directory・Damusを使い、8件の署名済みcommit復元、
MST/CAR/firehose、順序逆転、外部履歴・ライブ収集、サービス再起動後の保持を検証した。
初回検証時のRelay登録拒否は、その後の修正で解消。追加テスト投稿を含め9件のcommit、
cursor省略の初期再送、切断再開、差分CARと完全getRepoを確認し、新規commitを実Relayでも受信した。
ローカル133テスト・型チェックと、固定Indigoのcommit 41件/sync 4件の公式検証が成功。
負荷試験と公開Relayでの大容量syncのend-to-end試験は未実施。
初回の[本番動作検証報告](../docs/pds-production-verification-2026-10-18.md)と、
修正後の[Relay互換修正・検証報告](../docs/research/pds-relay-fixes-2026-10-18.md)を参照。

プロトコル根拠: [リポジトリモデル](../protocol/0001-repository-model.md)、
[論理PDS](../protocol/0006-logical-pds.md)、[Nostrリレー](../protocol/0007-nostr-relay-profile.md)、
[データ表現](../protocol/0012-bindings.md)。
