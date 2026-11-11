# PDS 開発・運用ガイド

機能概要・対応範囲は [README.md](README.md) を参照。デプロイ・設定項目・認証・バックアップは [deployment.md](deployment.md) にまとめている。

## 起動・テスト

Node.js 24以降を用意し、`pds` で実行する。

```sh
npm ci
npm test
npm run typecheck
```

手動起動時は次の環境変数を設定して `npm start` を実行する。

| 変数 | 用途 |
| --- | --- |
| `CONFIG_FILE` | 設定JSONへのパス。内容は [設定と秘密](deployment.md#設定と秘密) を参照 |
| `DATABASE_PATH` | SQLite DBの絶対パス。親ディレクトリを事前作成する |
| `DATABASE_URL` | PostgreSQL接続URL。`DATABASE_PATH` と同時に設定しない（起動時に拒否） |
| `HOST` / `PORT` | 待受アドレスとポート。既定は `0.0.0.0:2583`。ローカル開発では `HOST=127.0.0.1` を指定する |

DB指定はどちらか一方が必須。初回起動でスキーマを作成するが、バックエンド切替による自動データ移行は行わない。開発では本番と別のDB・設定を使い、意図しない外部リレー収集やpeer通知を避ける。著者署名鍵や標準PDSの管理者パスワードをサーバーへ設定する手順はない。

Android・ESP32を含むローカルデータ相互運用試験の手順は [ESP32ホストテスト](../apps/esp32/test_bundle/README.md) を参照。

## 実装の入口

| ファイル | 責務 |
| --- | --- |
| `repository.mjs` | 基本版1の検証・復元、commit署名、MST/CAR |
| `identity.mjs` | 設定したPLC DirectoryからのDID解決 |
| `store.mts` / `store.mjs` | SQLite/PostgreSQL接続、スキーマ、トランザクション。Node.jsのネイティブ型除去で実行 |
| `ingest.mjs` | 受理、削除要求、復元ジョブ、公開head・firehose |
| `sync.mts` | covering proof、commit/syncフレーム、未来rev判定 |
| `server.mjs` | Nostrリレー、XRPC、firehose、peer受信 |
| `network.mjs` | 外部リレー収集、peer通知・取得・再試行 |
| `config.mjs` / `main.mjs` | 設定検証、起動・終了 |

変更時の仕様根拠は [コアプロトコル](../protocol/mosimo/README.md)、[論理PDSとNostrリレー](../protocol/mosimo/pds/README.md) を参照。peer APIやNIP-11の独自情報を標準仕様として扱わない。

## BSAF転載イベントの経路

`dev.jisaku.mosimo.public.bsaf` 拡張に `original` を持つBSAF転載イベントはNostr専用として扱う。元の署名付き `EVENT` を保存し、`REQ` での配信・peer転送を行うが、atprotoの検証ジョブ作成・commit保存・repo更新・firehose配信には進めない。

この分類は信頼するrootの鮮度やimporter DIDの許可リストに依存しない。不正なBSAF拡張もatproto経路へ進めず、更新前から残るpending/retryジョブにも同じ分類を適用する。`original` のない通常のBSAFユーザー投稿は従来のatproto経路を維持する。既存のcommit・streamを削除・巻き戻しせず、AppViewの表示・queryや過去レコードも維持する。

## 優先度付き履歴取得（MOSIMO-REQ）

NIP-11で `mosimo.protocol: "dev.jisaku.mosimo"`、`mosimo.v: "1"`、`mosimo.priority_order: ["priority-v1"]`、`mosimo.kinds: [1, 5, 8173]` を通知する。独自の `MOSIMO-REQ` を明示した場合だけ優先度順で取得する。通常の `REQ` は `created_at` の降順、同時刻ではevent IDの昇順で、フィルター・件数制限・ライブ購読を維持する。

```json
["MOSIMO-REQ", "priority-history", {"order":"priority-v1","filter":{"kinds":[1],"#t":["mosimo.jisaku.dev"],"limit":100}}]
```

- フィルターは1つだけで、`kinds: [1]` と `#t` に `mosimo.jisaku.dev` を指定する。`limit` は1以上、`min(100, maxResults)` 以下の整数とする。
- 受理時点で評価時刻と失効状態を固定し、`effectivePriority DESC, created_at DESC, id ASC` で並べてから `limit` を適用する。候補は最大1,000件・JSON合計8 MiB、処理は5秒以内。超過・不正・ID競合は理由付きの `CLOSED` を返し、切り詰めた結果で代用しない。
- 並べ替え後に全候補を再評価し、評価中に失効通知の保存や期限経過で優先度が変わった場合はイベント送信前に `CLOSED` で終了する。

### 認可と失効

`publicInformation` の既定値は `{ roots: [], selection: [], revocationDomains: [] }`。`attest.mjs` の `authorizeImport` が [転載サーバー §3](../protocol/mosimo/attest/importer/README.md) の手順（Nostr署名・基本タグ・`public.bsaf` の `original`・ルートから `pubkey` へ至る鍵署名チェーン・作成時刻と現在時刻の有効性・`import` 役割と `importer`/`organization` ブロック・ローカル選択・配送期限・失効状態）を検証し、通った投稿だけが1を超えるヒントを実効優先度にできる。要求処理中にネットワーク取得は行わない。

失効通知は永続的な事実として `revocations` 表に保存し、削除しない。入口は二つ: kind `8173`（`t` = `mosimo.jisaku.dev/revocation`）の制御イベントのingestと、`issuerDomain` の失効リスト（`/.well-known/mosimo/attest/revocations`）の定期取得（起動時と10分ごと。失敗は最後の状態を保つ）。評価は通知の `issuer` がチェーン上の祖先か `subject` 自身である場合にだけ適用する（[失効通知 §2.1, §6](../protocol/mosimo/attest/revocation/README.md)）。

## 運用・試験時の注意

- Nostrの `OK`、DBへのイベント保存、復元ジョブの検証完了、正規公開先への掲載は別の状態。公開されない場合の `jobs` 監視と再試行の確認は [起動と運用](deployment.md#起動と運用) を参照。`/health` はプロセスの生存確認であり、後段への配信完了を示さない。
- SQLiteは単一物理ノードの永続ローカルディスク専用。ネットワーク・共有ファイルシステムに置かず、同じ論理PDSを別々のSQLite DBで負荷分散しない。複数物理ノードで同じ論理PDSを提供する場合は共通PostgreSQLを使い、採番を直列化する。公開headとstreamは `group_id` で分離する。異なるDBのpeer間で同じstreamの合意形成を行う実装ではなく、peer同期をDBレプリケーション・HAの代替にしない。構成条件は [デプロイ構成](deployment.md#構成) を参照。
- PLCは現在の状態のみ参照する。鍵更新・DID移行時は手動で公開を停止して管理する。欠落baseの完全回収は保証されないため、保留ジョブを監視する。
- MSTは更新ごとに全件から再構築し、commitごとに完全CARを保存する。保存・stream履歴の自動削除はないため、容量監視とDB側の資源制限が必要。匿名投稿の濫用対策はCaddy・ネットワーク側にも設ける。
- バックアップは [バックアップと復元](deployment.md#バックアップと復元) に従う。稼働中のSQLite DB本体だけをコピーしない。古いDBへ戻したまま同じ公開名で再開するとfirehoseのseqを再利用し得るため、復旧・再同期方針を先に決める。
- 公開ネットワークを使う試験は投稿が永続的に残り得る。再実行の条件・証拠は [本番動作検証報告](../docs/pds-production-verification-2026-10-18.md) と [Relay互換検証報告](../docs/research/pds-relay-fixes-2026-10-18.md) を参照。単体テストの成功を実Relayや全経路の互換性保証とは扱わない。
