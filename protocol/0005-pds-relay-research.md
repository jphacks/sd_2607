# 0005: PDS 複製と既存 Relay 接続の設計調査

状態: 調査メモ（設計提案を含む。採用済み仕様ではない）

調査日: 2026-10-17

設計判断の追記: 調査後の対話で、案 A（既存 Relay 向けは一つの論理 PDS、背後の収集・復元サーバーは分散）を採用した。本文の提案・未合意という記述は調査時点のものとし、現在の合意は [0001 §1.14](0001-repository-model.md#114-分散復元と論理-pds) を参照する。公開名・stream の障害耐性等の具体方式は引き続き未決定であり、以下の静的調査を runtime 検証済みの仕様として扱わない。

## 1. 結論

端末だけが atproto リポジトリを更新・署名し、複数サーバーが**同じ署名済み commit とその状態を復元・複製する**方針は、署名のモデルとは矛盾しない。ただし、署名が正しいことと、既存 Relay がその公開ホストを当該 DID の正規の PDS と認めることは別である。

| 構成 | 既存 Relay との成立条件・制約 |
|---|---|
| **A: 同一 HTTPS ホストの裏に複数 PDS** | **条件付きで成立する構成。** DID が示す一つの公開ホストを複数バックエンドで提供する。Relay からは一つの論理 PDS に見える必要があり、repo だけでなく firehose の順序・`seq`・再送履歴・account 状態等も整合させる。署名済み commit の複製だけではフェイルオーバーは完成しない。 |
| **B: 異なるホストの PDS が同一 DID を同時公開** | データの保管・ミラー提供は可能。しかし、**両ホストを同時に authoritative PDS として標準 Relay に取り込ませることは、現行仕様の通常経路では成立しない。** DID から選択される PDS は一つであり、非対応ホストの直接 stream は拒否対象。同じ署名・CID でもこの制約を回避できない。 |

**Relay は全世界で一つではない。** 複数運用者・複数インスタンスが存在可能であり、購読対象・制限・取り込み状態・出力カーソルも独立する。ある Relay への crawl 要求の成功が、全 Relay や全 AppView への配送を意味するわけではない。[S1][S3][I8][I12]

本 repo への提案は、複数収集・復元サーバーを維持しつつ、既存 Relay 向けにはまず **A の一つの論理 PDS** を公開すること。B の独立ミラーは内部の複製・復旧経路として分離する。異なる公開ホストへの切替は、同時 multi-home ではなく DID の PDS endpoint 更新を伴う移行として扱う。これは今回の提案であり、既存の合意事項を変更するものではない。

## 2. 前提と調査範囲

### 2.1 既存文書との関係

[README](README.md)、[全体概要](0000-overview.md)、[リポジトリ正本モデル](0001-repository-model.md)、[未決事項](0003-open-questions.md) を確認した。番号付き日本語 Markdown、状態の明示、合意事項・構想・未決定の区別、相対リンクと一次資料の参照という既存の書式に合わせる。作業開始時の `git status --short` は出力なし（変更なし）だった。

本調査では、端末が単一 writer、PDS 相当の収集サーバーは著者の秘密鍵を持たず、著者に代わる commit を作らないという前提を固定する。複数 writer の競合解決を課題と取り違えない。ただし端末自身の巻き戻り・fork・`rev` 単調増加と、サーバー間の到着順の違いは依然として課題となる。

### 2.2 一次ソースと検証の粒度

- **仕様**: atproto 公式の Sync、DID、Event Stream を調査日に取得して確認した。以下の [S1]〜[S3] は可変の仕様 URL であり、固定リビジョンではない。
- **実装**: `bluesky-social/indigo` の調査時点の `main` HEAD を GitHub API で解決し、commit **`3beb6e491b793834f6c66aed9963a255f56f1b6b`**（commit 日時: 2026-10-13）に固定した。現行の `cmd/relay` を中心に、関連する identity、stream 永続化、rainbow のソースを確認した。[I0]
- ソースは固定 commit の raw URL から取得し、参照は同 commit の `blob` URL とする。clone はしていない。
- **公開一次資料の取得と静的な制御フローの追跡のみ**を実施した。稼働中 PDS／Relay への XRPC・WebSocket 接続、crawl 登録、runtime 実験、障害注入は実施していない。
- この commit が既存の公開 Relay にデプロイされているとは確認していない。他の Relay 実装や全世界の運用設定を一般化しない。

## 3. 仕様から確定できる制約

### 3.1 DID は一つの PDS endpoint を選択する

DID 仕様は `service` 配列のうち、`id` が `#atproto_pds` で終わり、`type` が `AtprotoPersonalDataServer` の**最初の一致を使用し、他を無視する**と規定する。`serviceEndpoint` は HTTPS のホスト URL で、path prefix 等を含めない。[S2]

したがって、同じ DID Document に同種の service を複数追加しても、標準的な複数 PDS 発見・優先順位付きフェイルオーバーリストにはならない。別の service ID の追加も、標準の PDS 解決規則を自動的に拡張しない。DNS／ロードバランサーの裏の複数マシンは、この DID レベルの endpoint 複数化とは別の問題である。

### 3.2 正規ホスト照合は署名検証とは独立する

Sync の Message Validation Checklist は、PDS を直接購読するサービスに対し、WebSocket 接続先のホストを記憶し、DID の現在の authoritative PDS に対応しない stream からの `#commit`／`#sync` を拒否するよう求める。署名検証、DID の機能性、account の active 状態、CAR とイベントの整合性等も別々に検証する。[S1]

これは、非正規ホストから届いた署名が偽物だから拒否するという意味ではない。**正しい署名済み状態であっても、現在の公開元として受理しない場合がある。** Relay の集約 stream を購読する下流に対する規則と、直接 PDS を購読する際のホスト照合も区別する。

### 3.3 重複 commit とカーソルは別の名前空間

- `rev` は DID ごとの repository 論理時計。移行や長期停止を挟んでも増加させる。受信側は、その DID で最後に正常処理した `rev` **以下**の `#commit`／`#sync` を無視するよう求められる。[S1]
- `seq` は stream の再開用番号であり、repository のバージョンではない。スコープは **サービスの hostname と endpoint NSID の組**。異なる PDS／Relay の `seq` は比較・流用できない。[S3]
- Relay の出力も独自の順序と offset を持つ。同じ commit が別 Relay で異なる `seq` を持つことは正常である。[S1]
- 同じ `rev` を別内容に再使用しても fork 解決にはならない。既処理 `rev` と同じなら無視され得る。サーバー側で番号を付け直して救済すると著者の署名済み commit を変えてしまう。

Event Stream の再送 window は有限である。古すぎる cursor は info と利用可能な最古位置からの再送、未来の cursor はエラーと切断となる。履歴欠落を repo の再同期で回復する処理と、stream の継続購読を別々に設計する必要がある。[S1][S3]

## 4. Indigo の現行ソースで追跡できたこと

以下は指定 commit の**実装観察**であり、仕様の要求や公開環境の実測値ではない。

### 4.1 ホスト識別と購読の単位

`ParseHostname` は URL または裸のホスト名を受け、公開 DNS 名を正規化して返す。公開ホストの非標準ポートは拒否し、例外として開発用 `localhost:port` を扱う。仕様の DID URL 表現全体と、実装が受け入れる購読ホスト表現は完全には同一ではない。[I1]

`Host.Hostname` は DB の unique key、Slurper の `subs` も hostname をキーにした map である。`SubscribeToHost` は購読済みなら終了する。接続 URL は `wss://{hostname}/xrpc/com.atproto.sync.subscribeRepos`。バックエンドの実 IP やプロセス単位で複数購読する構造ではない。[I2][I3][I4]

**A への含意**: 公開 DNS／TLS ホストが同一なら、背後の物理サーバーの数はこの照合からは見えない。一方、一つの WebSocket は一つの接続であり、HTTP の負荷分散だけで全バックエンドのイベントが合流するわけではない。全更新を収めた論理 stream を提供する仕組みが別途必要となる。

### 4.2 DID とホストの照合、移行

`processRepoEvent` → `processCommitEvent`／`processSyncEvent` → `preProcessEvent` の順で、検証より先に account と host の対応を処理する。[I5]

1. 未知 DID は `CreateAccountHost` が DID を解決し、`ident.PDSEndpoint()` → `ParseHostname` の結果と受信 stream の hostname を比較する。不一致なら通常はエラー。
2. 既知 DID は `EnsureAccountHost` で検査する。保存済み `Account.HostID` と受信元が同じなら fast path で成功する。**毎イベントで必ず新しい DID Document を解決し直すわけではない。**
3. 別 host から届いた場合は DID の endpoint を確認し、不一致なら identity cache を purge して再解決する。それでも違えば拒否する。一致すれば DB の単一 `HostID` を新ホストへ更新する。[I6]

`Account.DID` は unique、`Account` は単一 `HostID` を持つ。これは複数の authoritative PDS の集合を管理するモデルではない。[I2]

identity の `ParseIdentity` は service の fragment をキーにして最初の同名 entry を保持し、`PDSEndpoint()` は `atproto_pds` 一つを返す。ただし type 等の検査には TODO があり、仕様に記載された条件を全て厳密に実装しているとまでは言えない。[I7]

`SkipAccountHostCheck` は通常 false で、ソース上はテスト用途、将来の中間 Relay 用途の可能性を記した設定である。確認した CLI の設定組み立てでは有効化していない。これを使う独自運用は通常経路とは別であり、既存 Relay 全体が B を受理する根拠にならない。[I6][I8][I9]

**B への含意**: 初見の非正規ホストからの commit は、署名や重複判定に到達する前に落ち得る。移行後も旧 host の fast path や DID cache により一時的な状態差があり得るが、これは同時 multi-home のサポートではなく、即時切替を保証できない実装上の境界として扱う。

### 4.3 `rev` 重複排除と仕様との差

`#commit` の `processCommitEvent` は DID に対応する `AccountRepo` を読み、`evt.Rev <= prevRepo.Rev` なら保存・再配信せず終了する。厳密検証側にも `rev` の比較がある。正常処理後は `rev`・commit CID・MST root CID を upsert し、出力イベントへ渡す。重複判定は全ネットワーク共有の CID 集合ではなく、**当該 Relay が持つ account の直近 `rev`** に基づく。[I2][I5][I6][I10]

重要な相違として、同 commit の **`processSyncEvent` は既存 `rev` を読み比べず**、`VerifyRepoSync` の後に repo 状態を upsert して再配信する。rollback を防ぐかという TODO も残る。`VerifyRepoSync` 自体にも直近 `rev` との比較はない。したがって「Indigo は `#commit` も `#sync` も古い `rev` を必ず無視する」とは結論しない。仕様は両方を対象とするが、この実装で確認できた防止は同じではない。[S1][I5][I10]

検証の強さにも留保がある。`VerifyCommitObject` は identity を取得できた場合に署名を検証するが、nil なら警告して署名検証を省略する。`VerifyCommitMessageStrict` でも `since`／`prevData` の不一致や MST inversion の失敗には警告だけの箇所がある。さらに `LenientSyncValidation` は一部の厳密検証エラーを許容する。**実装の受理結果だけを本設計の検証済み状態の定義にしてはいけない。**[I5][I9][I10]

また、同じ account の順序保証は単一 host 内の scheduler の範囲であり、異なる host 間では同時・順不同となり得ると `ingest.go` に明記される。repo upsert は条件付きの単調更新 SQL ではない。移行時の race に対する厳密な exactly-once や rollback 不可能性は、この追跡からは保証しない。[I5][I6][I14]

### 4.4 crawl の発見・設定・参加条件

確認した明示的な参加経路は `com.atproto.sync.requestCrawl` に**ホスト名**を渡す処理である。`handleComAtprotoSyncRequestCrawl` は次の順に扱う。[I11]

1. 公開 requestCrawl が無効化されていないかを確認（管理者要求は一部 bypass）。
2. hostname の解析、TLS 要件、localhost 制限、domain ban を確認。
3. `HostChecker.CheckHost` が対象の `com.atproto.server.describeServer` の呼び出し成功を確認。
4. 設定があれば sibling Relay へ要求を転送し、`SubscribeToHost` で DB 登録・購読を開始。

新規 host 数の一日上限、host ごとの account 数上限、stream rate limit、ban 等も存在する。起動時は DB 内の active host に `ResubscribeAllHosts` で再接続する。管理者 crawl 入口もある。[I3][I4][I8][I9][I15]

`CreateAccountHost` は「既知 host の stream で account を発見する」「DID が別 host を示していてもそこへ spidering しない」と明記する。**DID を PLC に登録しただけで、この Relay が未知の PDS を自動発見し購読するとは扱えない。** また、ここでいう crawl は repo 全件の自動 backfill 完了と同義ではない。確認した経路は購読開始であり、新規購読で cursor がなければ仕様上は現在位置からである。過去のみ存在する account／record の初期列挙・再同期は別の検討が必要となる。[I6][S3]

rainbow は requestCrawl を一つの upstream に転送し、成功後に設定された他 crawler へ best-effort で転送する。Relay 本体にも `sibling-relays` による転送があるが、いずれも設定された相手への要求転送であって、世界共通の登録簿・配送合意・出力 `seq` の共有ではない。[I9][I12][I16]

本プロジェクトの read-only 公開サーバーも、commit を stream に載せるだけではこの参加経路を満たさない。少なくとも上記 host check と同期 API を提供し、選んだ Relay の受付設定・制限に適合する必要がある。標準 PDS 実装を無改造で外部署名専用モードにできるか、必要な API の完全な一覧・認証／import 手順までは今回調査していない。

### 4.5 カーソル保存・再接続・フェイルオーバー

Slurper は host ごとの `LastSeq` を DB から読み、正値なら `?cursor=...` を付けて再接続する。処理 scheduler の進捗を取り出し、定期的・再接続時・shutdown 時に callback 経由で host cursor を保存する。接続失敗時は同じ hostname へ再試行し、一定回数の dial 失敗で offline にする。[I1][I3][I4][I14]

**`FutureCursor` を受けた場合は host を idle にし、その購読を cancel する。** cursor を自動的にゼロへ戻す、別 host のミラーを探す、DID を書き換えるといった復旧処理ではない。`#info` はこの処理ではログ出力のみであり、保持 window 外の欠落をここで自動修復するとは確認できない。[I4]

処理 callback の失敗をログにしても scheduler は処理済みとして進める経路がある。従って host cursor は「その位置まで全 commit が正常に署名検証され、下流まで配送された」という証明ではない。仕様の理想的な成功処理 cursor と、実装で保持される進捗の意味を混同しない。[I4][I14]

Relay 出力側の `DiskPersistence.doPersist` は、自分の `curSeq` を増加させ、各イベントの `seq` を上書きする。upstream PDS の `seq` は下流へ共通カーソルとして保存されるのではない。下流が Relay を切り替える場合にも、Relay ごとの cursor 管理と DID／rev に基づく再同期が必要となる。[I13][S1][S3]

## 5. A を成立させるための設計条件

以下は仕様・実装観察から導く**提案条件**であり、この repo に実装済みの機能ではない。

1. **公開 identity を一つにする。** DID の PDS endpoint を安定した一つの HTTPS ホストとし、そのホストの TLS・DNS・ルーティングを冗長化する。内部サーバー間の同期先と Relay が購読する公開名を分離する。
2. **署名済み状態を正確に保存する。** commit の元バイト列・CID・`rev` を保ち、必要な records／path／MST を取得・検証する。PDS が再署名・新規 commit 作成・`rev` 付け直しをしてはならない。blob を用いる場合はその可用性も別途確保する。
3. **一つの論理 firehose を作る。** 全バックエンドの受信を統合したイベントログ、またはそれと同等の継続性を保証する stream 層を設ける。同じ hostname／NSID では、切替後も `seq` の意味・イベント順序・再送 window を維持する。repo が同じでも独立採番の stream を無調整で負荷分散してはいけない。
4. **遅れた replica をそのまま昇格しない。** 例として、切替前の cursor が `10000`、切替後の独立 stream が `500` なら `FutureCursor` になり得る。単に初期 seq を大きくしてエラーを避けても、欠落が埋まるわけではない。健全性判定、fencing、catch-up、必要時の再同期を定義する。sticky session だけでは障害後の問題は解決しない。
5. **同期 API と lifecycle を揃える。** stream と `getRepo`／状態照会等で異なる時点や削除状態を矛盾して返さないようにする。削除・deactivation・takedown・DID 更新の反映も複製対象にする。`#sync` は全文 CAR の代用品ではなく、仕様上は commit の提示と必要な再取得の契機である。[S1]
6. **Relay 参加を運用として設計する。** 対象 Relay の crawl 登録・制限・停止後の復旧を監視する。複数 Relay を使う場合は受付・取り込み状況を別々に扱う。復元サーバーへの到着が、そのまま Relay／AppView での可視化完了を意味しない。

A は著者署名鍵をサーバーへ渡さずに設計できる。一方、共通公開ドメイン・その管理権限・名前解決・stream 制御は依存点として残る。複数運用者のストレージ冗長化と、公開入口の管理まで完全に独立した multi-home は同じ目標ではない。

## 6. B の可能な使い方と限界

- **非正規ミラー**: 異なるホストで同じ署名済みデータを保管・提供する。Sync 仕様も他サーバーによる repo CAR の cache／mirror 提供を認める。ただし更新・削除・account 状態へ迅速に追従する必要がある。標準 Relay がそのミラー stream を authoritative として取り込むことまでは意味しない。[S1]
- **移行・復旧**: 新 host に正確な状態を複製し、端末の PLC 管理鍵で PDS endpoint の変更操作を署名し、DID を更新する。本設計では PLC 管理鍵も端末だけが持つため、端末が不在でも任意の独立 host を自動昇格できるとは限らない。事前に署名した操作を用いる案も、状態の順序・失効・再利用防止を別途検討する必要があり、ここでは採用しない。
- **独自集約入口／独自 Relay**: 複数 host を信頼されたミラーとして統合する実装を追加することは設計上の選択肢。ただし既存 Relay の無改造利用とは別であり、その出力を既存 Relay がさらに購読することも自動ではない。調査した Slurper は接続先の `Server` header に `atproto-relay` が含まれる場合、Relay への購読とみなして ban する。[I4]

B を実現するためだけに著者 DID を PDS ごとに分けることは、今回の同一 DID・単一正本という問いへの解決ではない。署名や `rev` 重複排除を理由にホスト照合を省いてよいという結論にもならない。

## 7. 次に決めること・未検証事項

既存 [0003 §6](0003-open-questions.md#6-オンライン公開と冗長化) の公開・冗長化課題に対し、まず「A の一つの論理 PDS を初期構成にするか」を判断する。その後、次を具体化する。

- 復元入力形式と、署名済み commit を変更せずに受け入れる read-only 公開機構。CAR／MST／差分イベントの生成・検証要件。
- 全体 stream の所有者・永続ログ・採番・レプリケーション・障害時の fencing と、欠落時の repo 再同期手順。
- 初回 DID／PDS endpoint の設定、DID cache 更新、端末不在時の復旧可能範囲。
- Relay ごとの crawl 受付、過去データの初期取り込み、制限超過・offline／idle からの復旧方法。
- 将来の相互運用試験項目: 同一 commit の重複、順序逆転、遅れた replica への切替、保持 window 超過、`FutureCursor`、`#sync` の古い `rev`、削除後の再出現、DID 移行中の両 stream。

これらの runtime 挙動・対象 Relay の実デプロイ版と設定・他実装の互換性は未検証。今回確認した実装上の緩い検証や `#sync` の差を、将来も使える仕様上の許可として依存してはならない。

## 8. 一次資料

仕様 URL は調査日取得の可変ページ。実装 URL は全て同じ immutable commit に固定しており、リンク先のファイル内では本文に記した関数名を参照する。

- [S1: AT Protocol Sync][S1] — Message Validation Checklist、Repository Revisions、Repository Exports、Usage and Implementation Guidelines。
- [S2: AT Protocol DID][S2] — DID Documents の PDS service 選択。
- [S3: AT Protocol Event Stream][S3] — Sequence Numbers、Connection、再送 window と cursor。
- [I0: 調査対象 commit][I0] — 調査時の `main` HEAD。
- [I1: relay/host.go][I1] — `ParseHostname`、`PersistHostCursors`。
- [I2: relay/models/models.go][I2] — `Host`、`Account`、`AccountRepo`。
- [I3: relay/crawl.go][I3] — `SubscribeToHost`、`ResubscribeAllHosts`。
- [I4: relay/slurper.go][I4] — 購読 map、再接続、cursor、FutureCursor、Relay 接続排除。
- [I5: relay/ingest.go][I5] — イベント処理順、commit／sync の差。
- [I6: relay/account.go][I6] — `CreateAccountHost`、`EnsureAccountHost`、repo upsert。
- [I7: identity/identity.go][I7] — `ParseIdentity`、`PDSEndpoint`。
- [I8: relay/relay.go][I8] — 設定・既定値・`SkipAccountHostCheck`。
- [I9: cmd/relay/main.go][I9] — CLI 設定、起動時の再購読。
- [I10: relay/verify.go][I10] — commit／sync の検証、厳密・寛容モードの境界。
- [I11: cmd/relay/handlers.go][I11] — requestCrawl の処理。
- [I12: splitter/handlers.go][I12] — rainbow の crawl 転送。
- [I13: stream/persist/diskpersist/diskpersist.go][I13] — `doPersist` による出力 seq 採番。
- [I14: stream/schedulers/parallel/parallel.go][I14] — DID 単位の処理と進捗。
- [I15: relay/host_checker.go][I15] — describeServer／getRepoStatus による確認。
- [I16: cmd/relay/forward.go][I16] — sibling 転送。
- [I17: relay/models/methods.go][I17] — 公開 HTTP／WebSocket URL の構築。
- [I18: cmd/relay/handlers_admin.go][I18] — 管理者 crawl 入口。

[S1]: https://atproto.com/specs/sync
[S2]: https://atproto.com/specs/did
[S3]: https://atproto.com/specs/event-stream
[I0]: https://github.com/bluesky-social/indigo/commit/3beb6e491b793834f6c66aed9963a255f56f1b6b
[I1]: https://github.com/bluesky-social/indigo/blob/3beb6e491b793834f6c66aed9963a255f56f1b6b/cmd/relay/relay/host.go
[I2]: https://github.com/bluesky-social/indigo/blob/3beb6e491b793834f6c66aed9963a255f56f1b6b/cmd/relay/relay/models/models.go
[I3]: https://github.com/bluesky-social/indigo/blob/3beb6e491b793834f6c66aed9963a255f56f1b6b/cmd/relay/relay/crawl.go
[I4]: https://github.com/bluesky-social/indigo/blob/3beb6e491b793834f6c66aed9963a255f56f1b6b/cmd/relay/relay/slurper.go
[I5]: https://github.com/bluesky-social/indigo/blob/3beb6e491b793834f6c66aed9963a255f56f1b6b/cmd/relay/relay/ingest.go
[I6]: https://github.com/bluesky-social/indigo/blob/3beb6e491b793834f6c66aed9963a255f56f1b6b/cmd/relay/relay/account.go
[I7]: https://github.com/bluesky-social/indigo/blob/3beb6e491b793834f6c66aed9963a255f56f1b6b/atproto/identity/identity.go
[I8]: https://github.com/bluesky-social/indigo/blob/3beb6e491b793834f6c66aed9963a255f56f1b6b/cmd/relay/relay/relay.go
[I9]: https://github.com/bluesky-social/indigo/blob/3beb6e491b793834f6c66aed9963a255f56f1b6b/cmd/relay/main.go
[I10]: https://github.com/bluesky-social/indigo/blob/3beb6e491b793834f6c66aed9963a255f56f1b6b/cmd/relay/relay/verify.go
[I11]: https://github.com/bluesky-social/indigo/blob/3beb6e491b793834f6c66aed9963a255f56f1b6b/cmd/relay/handlers.go
[I12]: https://github.com/bluesky-social/indigo/blob/3beb6e491b793834f6c66aed9963a255f56f1b6b/splitter/handlers.go
[I13]: https://github.com/bluesky-social/indigo/blob/3beb6e491b793834f6c66aed9963a255f56f1b6b/cmd/relay/stream/persist/diskpersist/diskpersist.go
[I14]: https://github.com/bluesky-social/indigo/blob/3beb6e491b793834f6c66aed9963a255f56f1b6b/cmd/relay/stream/schedulers/parallel/parallel.go
[I15]: https://github.com/bluesky-social/indigo/blob/3beb6e491b793834f6c66aed9963a255f56f1b6b/cmd/relay/relay/host_checker.go
[I16]: https://github.com/bluesky-social/indigo/blob/3beb6e491b793834f6c66aed9963a255f56f1b6b/cmd/relay/forward.go
[I17]: https://github.com/bluesky-social/indigo/blob/3beb6e491b793834f6c66aed9963a255f56f1b6b/cmd/relay/relay/models/methods.go
[I18]: https://github.com/bluesky-social/indigo/blob/3beb6e491b793834f6c66aed9963a255f56f1b6b/cmd/relay/handlers_admin.go
