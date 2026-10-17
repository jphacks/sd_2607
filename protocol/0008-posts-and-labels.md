# 0008: 投稿と注釈・Label

状態: draft-00（投稿単位と誰でも注釈できる方針は合意済み。Label の発行・署名・配信方式は未決定）

## 1. 投稿単位

通常投稿、訂正、補足、追認は、1件につき発信者のリポジトリへ1レコードを追加し、その更新に1 commit と1 Nostr 投稿イベントを生成する。初期仕様では複数投稿のバッチ commit を設けない。本文投稿の Nostr 表現は `kind: 1` とする。

この単位は、削除・ID 宣言・鍵対応宣言等の制御処理や、標準 Label オブジェクトの署名そのものの個数を規定するものではない。

## 2. 誰でも発信できる注釈

訂正・補足・追認は、対象投稿の著者以外も発信できる。発信者自身の新規レコードとして本文・対象参照等を署名し、対象投稿の著者として書き換えない。

元著者による訂正と他者の主張を区別する。追認も、その発信者による賛同・確認の表明であり、情報の真実性の自動的な証明にはしない。注釈の署名や件数だけで信頼を確定しない。誰でも発信できることは、すべてのクライアントが無条件に表示・信用することを意味しない。

## 3. atproto Label との関係

訂正・補足・追認を、atproto 上では対象投稿への Label として提示する方針とする。本文は独立した注釈レコードとして保持する。

[標準 Label 仕様](https://atproto.com/specs/label) による制約:

- Label はリポジトリレコード・commit とは独立した、自己認証可能なオブジェクトである。
- 主に発行者 `src`（DID）、対象 `uri`、短い値 `val` を持ち、必要に応じて対象版の `cid` を指定する。
- `val` は最大128バイトの短いトークンであり、自由文や構造化情報を詰め込む用途は推奨されていない。注釈本文を Label 本体の代わりにはしない。
- Label の署名鍵は発行者 DID の `#atproto_label` で解決する。この鍵の値はリポジトリ署名鍵と同じでもよいが、その宣言なしに `#atproto` へフォールバックして検証するものではない。
- Labeler service の識別・配信はリポジトリ firehose と別である。Label をレコードとして保存しただけで標準配信が成立するとは扱わない。
- 同じ `src`・`uri`・`val` の Label のライフサイクルは時刻・否定・期限に従う。個々の注釈レコードを無条件に一対一の永続 Label と扱わない。

具体的な発行者、署名する主体、鍵構成、Label 値、注釈レコードとの対応、複数注釈の集約と撤回、サービス発見・配信は未決定である。既存 AppView・クライアントが自動的にすべての Label を収集・表示するとは保証しない。

### 3.1 集約配信と発行元選択の調査

2026-10-17 に、atproto リポジトリの commit `11ce369ebf05fa533a46080f1b42eded6fa5f71f` のスキーマと AppView ソースを確認した。接続・配送の集約と、発行元への信頼・表示対象の選択は別である。

- [`subscribeLabels` スキーマ](https://github.com/bluesky-social/atproto/blob/11ce369ebf05fa533a46080f1b42eded6fa5f71f/lexicons/com/atproto/label/subscribeLabels.json) は `seq` と Label 配列を持つイベントを配信し、Label の `src` が一つに限られるというスキーマ制約はない。したがって複数発行元を一つの stream で運ぶデータ構造は表現できる。ただし全ての既存サービスが集約 stream の取り込みに対応する保証ではない。
- [`queryLabels` スキーマ](https://github.com/bluesky-social/atproto/blob/11ce369ebf05fa533a46080f1b42eded6fa5f71f/lexicons/com/atproto/label/queryLabels.json) は対象 URI と、任意の発行元 DID リスト `sources` を指定できる。
- 一方、確認した [Bluesky AppView の queryLabels 実装](https://github.com/bluesky-social/atproto/blob/11ce369ebf05fa533a46080f1b42eded6fa5f71f/packages/bsky/src/api/com/atproto/label/queryLabels.ts) は空の `sources` を拒否する。スキーマで任意でも、その実装が全発行元の一括取得を提供するとは限らない。
- [Label hydration](https://github.com/bluesky-social/atproto/blob/11ce369ebf05fa533a46080f1b42eded6fa5f71f/packages/bsky/src/hydration/label.ts) は選択された Labeler DID を `issuers` に渡し、[取得側](https://github.com/bluesky-social/atproto/blob/11ce369ebf05fa533a46080f1b42eded6fa5f71f/packages/bsky/src/data-plane/server/routes/labels.ts) は `src` で絞り込む。一つのサーバーを選択すれば、その配信する任意の `src` が自動的に表示対象になるとは扱えない。

設計上の選択肢（未合意）:

1. **本人を Label の `src` とする。** 元の発行者・署名を維持し、対応 AppView が複数 source の発見・検証・選択を代行する。ユーザーごとの接続は不要にできるが、既存クライアントへの表示互換性や大量の発行者管理は別途必要となる。
2. **グループ等の Labeler を `src` とする。** 本人署名付きの注釈レコードを収集・検証し、サービスが自身の DID・鍵で標準 Label を発行する。閲覧者は少数の Labeler を選べるが、Label 自体はサービスの判断・証言となる。元の注釈著者はレコード等で維持し、本人発行の Label と偽らない。

今回の静的調査で、配信の集約だけでは発行元選択の負担が自動的に解消しないことを確認した。構成の採否と runtime 相互運用は未決定・未検証である。Label の生成元を少数に集約しても、誰でも注釈を投稿できるという方針とは両立し得る。

## 4. 削除との区別

MST から削除するのは対象レコードの path → CID エントリであり、commit オブジェクトではない。端末は削除後の MST root を参照する新しい commit に署名する。削除対象に応じた NIP-09 の `kind: 5` 削除要求も発行する。

したがって、一つのレコード削除では新規投稿レコードは増えないが、削除後の状態を表す commit は生成する。複数対象の削除をまとめるかは未決定。過去の署名済み commit や取得済みコピーが全て消える保証はない。

誰でも注釈できることは他人の投稿を著者として削除できることを意味しない。また NIP-09 の削除要求と標準 Label の否定（`neg`）は別の操作である。注釈レコードの削除と、その注釈に対応する Label の撤回・集約状態の関係は今後定義する。

## 5. 参照

- [AT Protocol Labels](https://atproto.com/specs/label)（2026-10-17 確認）
- [AT Protocol Repository](https://atproto.com/specs/repository)
- [NIP-09](https://github.com/nostr-protocol/nips/blob/master/09.md)

標準仕様の確認と設計方針の記録のみ。Label の実装・署名検証・既存 AppView との相互運用試験は未実施である。
