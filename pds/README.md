# Mosimo PDS

端末署名済みリポジトリを収集・復元する物理PDS。Node.js 24＋PostgreSQLを使用する。
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

同じ論理PDSの複数物理ノードは共通DBを使い、採番を直列化する。公開headとstreamは
`group_id`で分離する。異なるDBのpeer間で同じstreamの合意形成を行う実装ではない。

## 外部インターフェース

- `/`: NIP-01の`EVENT` / `REQ` / `CLOSE`、履歴・ライブ購読。
- `/`へ`Accept: application/nostr+json`: NIP-11。kind 1/5を受け入れる。
  古い`created_at`・独自タグだけを理由に拒否しない。
- NIP-09: 著者一致のevent ID削除要求を履歴・ライブ・peer取得で抑止し、再投入も拒否。
  kind 5自体は削除しない。atprotoの削除commitをサーバー側で捏造しない。
- Hostで選択する論理グループの`com.atproto.sync.subscribeRepos`:
  CBORのidentity/account/commitイベント、永続seq、cursor再送、遅い購読者の切断。
- `com.atproto.sync`: `listRepos`, `getRepo`, `getCheckout`, `getHead`, `getLatestCommit`,
  `getRepoStatus`, `getBlocks`, `getRecord`, `listBlobs`, `getBlob`。
- `com.atproto.repo`: `describeRepo`, `getRecord`, `listRecords`。
- `/internal/notify`と`/internal/events/<event-id>`: Bearer認証必須の実装固有peer API。
- `/health`: プロセスの生存確認。

CARは公式ライブラリで生成し、元commit/CID/rev/署名を維持する。同期用CARにはMSTと
過去レコードも含める。大きな更新は標準`tooBig`イベントとgetRepoによる再同期へ誘導する。
stream履歴は現状全保持であり、独自の履歴切り詰め・欠損の隠蔽を行わない。

## 起動とテスト

```sh
cd pds
npm ci
npm test
```

`CONFIG_FILE`に設定JSONへのパス、`DATABASE_URL`にPostgreSQL接続URLを指定して
`npm start`。設定・Nixモジュール・Caddy・peer認証・バックアップ手順は
[deployment.md](deployment.md)を参照。
初回起動でDBスキーマを作る。標準のPDS管理者パスワードや著者署名鍵の設定はない。

実装の責務:

| ファイル | 責務 |
| --- | --- |
| `repository.mjs` | 基本版1の検証・復元、commit署名、MST/CAR |
| `identity.mjs` | 固定PLC DirectoryからのDID解決。任意URLへアクセスしない |
| `store.mjs` | PostgreSQL接続、スキーマ、トランザクション |
| `ingest.mjs` | 受理、削除要求、復元ジョブ、公開head・firehose |
| `server.mjs` | Nostrリレー、標準XRPC、firehose、peer受信 |
| `network.mjs` | 外部リレー収集、peer通知・取得・再試行 |
| `config.mjs` / `main.mjs` | 設定検証、起動・終了 |

## 現時点の境界

これはドラフトで合意された**公開テキスト・create専用プロファイル**の実装であり、
一般の書き込み可能なBluesky PDS一式ではない。外部同期は標準API/データ形式を使うが、
**実atproto Relayでの取り込み適合試験は未実施**。完全な互換性を確認済みとは主張しない。

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

プロトコル根拠: [リポジトリモデル](../protocol/0001-repository-model.md)、
[論理PDS](../protocol/0006-logical-pds.md)、[Nostrリレー](../protocol/0007-nostr-relay-profile.md)、
[データ表現](../protocol/0012-bindings.md)。
