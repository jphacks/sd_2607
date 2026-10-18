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

変更時の仕様根拠は [リポジトリモデル](../protocol/0001-repository-model.md)、[論理PDS](../protocol/0006-logical-pds.md)、[Nostrリレー](../protocol/0007-nostr-relay-profile.md)、[データ表現](../protocol/0012-bindings.md) を参照。peer APIやNIP-11の独自情報を標準仕様として扱わない。

## 運用・試験時の注意

- Nostrの `OK`、DBへのイベント保存、復元ジョブの検証完了、正規公開先への掲載は別の状態。公開されない場合の `jobs` 監視と再試行の確認は [起動と運用](deployment.md#起動と運用) を参照。`/health` はプロセスの生存確認であり、後段への配信完了を示さない。
- SQLiteは単一物理ノードの永続ローカルディスク専用。複数物理ノードで同じ論理PDSを提供する場合は共通PostgreSQLを使う。peer同期をDBレプリケーション・HAの代替にしない。構成条件は [デプロイ構成](deployment.md#構成) を参照。
- PLCは現在の状態のみ参照する。鍵更新・DID移行時は手動で公開を停止して管理する。欠落baseの完全回収は保証されないため、保留ジョブを監視する。
- MSTは更新ごとに全件から再構築し、commitごとに完全CARを保存する。保存・stream履歴の自動削除はないため、容量監視とDB側の資源制限が必要。匿名投稿の濫用対策はCaddy・ネットワーク側にも設ける。
- バックアップは [バックアップと復元](deployment.md#バックアップと復元) に従う。稼働中のSQLite DB本体だけをコピーしない。古いDBへ戻したまま同じ公開名で再開するとfirehoseのseqを再利用し得るため、復旧・再同期方針を先に決める。
- 公開ネットワークを使う試験は投稿が永続的に残り得る。再実行の条件・証拠は [本番動作検証報告](../docs/pds-production-verification-2026-10-18.md) と [Relay互換検証報告](../docs/research/pds-relay-fixes-2026-10-18.md) を参照。単体テストの成功を実Relayや全経路の互換性保証とは扱わない。
