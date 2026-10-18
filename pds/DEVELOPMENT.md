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

## BSAF転載イベントの経路

`bsafV1` 拡張に `original` を持つBSAF転載イベントはNostr専用として扱う。元の署名付き `EVENT` を保存し、`REQ` での配信・peer転送を行うが、atprotoの検証ジョブ作成・commit保存・repo更新・firehose配信には進めない。

この分類は信頼するrootの鮮度やimporter DIDの許可リストに依存しない。不正なBSAF拡張もatproto経路へ進めず、更新前から残るpending/retryジョブにも同じ分類を適用する。`original` のない通常のBSAFユーザー投稿は従来のatproto経路を維持する。既存のcommit・streamを削除・巻き戻しせず、AppViewの表示・queryや過去レコードも維持する。

## wire-v1の優先度付き履歴取得

NIP-11で `mosimo.wire: 1` と `mosimo.priority_order: ["priority-v1"]` を通知する。独自の `MOSIMO-REQ` を明示した場合だけ優先度順で取得する。通常の `REQ` は `created_at` の降順、同時刻ではevent IDの昇順で、フィルター・件数制限・ライブ購読を維持する。未知のフィルターフィールドを指定しても優先度順へ切り替わらない。

```json
["MOSIMO-REQ", "priority-history", {"order":"priority-v1","filter":{"kinds":[1],"#t":["mosimo.oriom.dev"],"limit":100}}]
```

### 要求と応答

- 保存済みの有限な履歴を対象に、取得時に検証した `effectivePriority DESC, created_at DESC, id ASC` で並べてから `limit` を適用する。
- フィルターは1つだけで、`kinds: [1]` と `#t` に `mosimo.oriom.dev` を指定する。`limit` は1以上、`min(100, maxResults)` 以下の整数とする。
- 要求IDは印字可能ASCIIの1〜64バイトで、既存の購読IDと重複させない。
- 通常形式の `EVENT` に続いて `EOSE` を返し、IDを解放する。ライブ購読は開始しない。処理中の `CLOSE` で中止できる。
- 不正な要求、IDの競合、永続的な信頼状態の利用不能、資源制限超過では、長さを制限した理由付きの `CLOSED` を返す。新しい投稿だけに候補を切り詰めた結果で代用しない。未対応・拒否時に通常の `REQ` へ戻す場合は別のIDを使う。

並べ替え後、すべての候補を現在の永続的な信頼状態と時刻で再検証する。実効優先度が変わった場合はイベント送信前に要求を終了する。再検証の待機・実行中に到来する証明書・snapshot・配送期限も、最後に同期的に確認する。

### 処理上限とsnapshot更新

優先度付き要求はプロセス全体で同時に1件まで。候補は最大1,000件・JSON合計8 MiBで、初回snapshot取得後の走査と2回の認可処理は5秒以内とする。上限を超えた場合はイベント送信前に要求を終了する。

snapshot取得はrootごとに10秒・32,768バイト以内、リダイレクト不可、CBORのみとする。プロセス共通の優先度付き要求枠で同時取得を抑え、同じrootへの取得試行は30秒に1回までとする。取得に失敗しても、保存済みsnapshotが新鮮なら使用できる。期限切れは優先度を認可する効力を失わせるが、保存済みNostrイベントを削除しない。各rootは独立して更新する。

更新時期を迎えたrootは候補走査より先に更新を試みる。後段で候補数の上限を超えても更新処理は行い、要求が繰り返されても次回更新時刻を先送りしない。

### 優先度の認可と永続化

`publicInformation` の既定値は `{ "roots": [], "selection": [] }`。明示的に有効化した公開rootと選択したBot・情報種別の組だけが、1を超える優先度を認可できる。共有処理 `verifyPublicInformation` が著者署名、証明書チェーン、鍵対応、commit、scope、時刻、新鮮な失効情報を検証する。認可に失敗したヒントは最大1とし、0は0のまま扱う。自己申告の優先度やBSAFの出典・重大度だけでは権限を与えない。受信側も独立して検証する必要があり、リレーの並び順はメッシュ取り込みの認可や現在の気象庁による発信を示さない。

SQLite・PostgreSQLの両方に、署名付きsnapshot全体（sequence、canonical bodyから導出するbody hash、issuedAt、累積CRL）と、issuer・serialから証明書IDへの対応を永続化する。root失効を含むsnapshot更新はイベントの認可より先にcommitし、sequenceの巻き戻し・同一sequenceで異なる内容・issuedAtの後退・CRLの削除・serialの使い回しを拒否する。

issuer・serialが同じで証明書の内容が異なる場合は、認可を拒否する前に独立したトランザクションで `trust_serial_conflicts` をcommitする。競合した両方の証明書を再起動後も拒否し、A→Bの競合後にAを再提示しても復帰させない。記録はissuer・serialの組ごとに1行とし、書き込み・commitに失敗した場合は要求を終了する。

期限切れ・無効化されたrootの状態も再起動後に保持する。過去のrootは64件、issuer・serialの組は100,000件を上限とし、新しいイベントを認可するために古い信頼状態を追い出さない。容量上限・書き込み失敗時も優先度付き要求を終了する。バックアップはDB全体を対象とする。古いバックアップの復元は、外部で巻き戻しを防止しない限り信頼状態も巻き戻し得る。

設定・配備手順は [Public wire-v1 trust configuration](deployment.md#public-wire-v1-trust-configuration) を参照。

## 運用・試験時の注意

- Nostrの `OK`、DBへのイベント保存、復元ジョブの検証完了、正規公開先への掲載は別の状態。公開されない場合の `jobs` 監視と再試行の確認は [起動と運用](deployment.md#起動と運用) を参照。`/health` はプロセスの生存確認であり、後段への配信完了を示さない。
- SQLiteは単一物理ノードの永続ローカルディスク専用。複数物理ノードで同じ論理PDSを提供する場合は共通PostgreSQLを使う。peer同期をDBレプリケーション・HAの代替にしない。構成条件は [デプロイ構成](deployment.md#構成) を参照。
- PLCは現在の状態のみ参照する。鍵更新・DID移行時は手動で公開を停止して管理する。欠落baseの完全回収は保証されないため、保留ジョブを監視する。
- MSTは更新ごとに全件から再構築し、commitごとに完全CARを保存する。保存・stream履歴の自動削除はないため、容量監視とDB側の資源制限が必要。匿名投稿の濫用対策はCaddy・ネットワーク側にも設ける。
- バックアップは [バックアップと復元](deployment.md#バックアップと復元) に従う。稼働中のSQLite DB本体だけをコピーしない。古いDBへ戻したまま同じ公開名で再開するとfirehoseのseqを再利用し得るため、復旧・再同期方針を先に決める。
- 公開ネットワークを使う試験は投稿が永続的に残り得る。再実行の条件・証拠は [本番動作検証報告](../docs/pds-production-verification-2026-10-18.md) と [Relay互換検証報告](../docs/research/pds-relay-fixes-2026-10-18.md) を参照。単体テストの成功を実Relayや全経路の互換性保証とは扱わない。
