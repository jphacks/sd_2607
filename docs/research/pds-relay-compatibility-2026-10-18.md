# PDS の実 atproto Relay 互換性調査 — 2026-10-18

状態: 修正前の一次ソース調査＋本番の読み取り検証＋公式Go検証器による実データ検証の記録。
本書の調査時点ではランタイム・本番設定を変更していない。
その後、指摘事項を修正し、実Relayの登録・取り込み・新規中継を確認した。
最新結果は[修正・本番検証報告](pds-relay-fixes-2026-10-18.md)を参照。

## 結論

**現在の登録拒否に対応する確定した不足は `GET /xrpc/com.atproto.server.describeServer`。同期 API/CAR/firehose が動くことだけでは、Indigo の PDS 判定を通らない。** ただし、この API を追加するだけで取り込み完了とはならない。後続には購読接続、著者 DID の正規 PDS 照合、アカウント状態・上限、commit 検証がある。[I1–I8]

特に **初回購読以前の既存 repo の発見・初期同期が別課題**。確認した `requestCrawl` 経路は PDS の `listRepos` を走査せず、保存 cursor が 0 なら cursor なしで購読する。現 PDS は cursor なしを最新 seq と解釈するので、過去投稿が自動回収されるとは言えない。[I3][I4][L1]

もう一つの確定した非互換は **大容量時の空CAR付き`tooBig`イベント**。現PDSは完全CARが900,000 bytesを超えると空blocksを送るが、指定Relayはstrict/lenient判定前のCAR読込で`EOF`になる。[I8][I17][L3]

一方、**本番の既存8commitは公式Go検証器で構造・実PLC公開鍵による署名・MST逆演算に合格した**。`prev:null`や完全スナップショットCARを、それだけで非互換と判定する根拠はない。詳細は後述の動的検証。

**OAuth、セッション、著者秘密鍵のサーバー保管は、この crawl 判定の要件ではない。** サーバー DID とその鍵、著者 DID と repo 署名鍵を混同しない。公開同期用 PDS を目指す課題と、一般クライアントがログイン・書き込みできる PDS 一式を作る課題は分離する。

## 範囲・証拠の扱い

- 既存の日本語調査メモにならい、確認事実・推論・未検証を区別する。親 `docs/research/` の存在を確認した。開始時に既存 `protocol/` 文書の変更を確認し、ユーザー作業として変更していない。本調査の変更は本書だけ。
- GitHub `commits/main` API は調査時に **`e076b63ff34a010d47ed1bfe82c767b23c3a7eb6`** を返した（commit 日時 `2026-10-17T18:05:21Z`）。tree API を root → `cmd/relay` → `relay` と限定して確認し、以下の実装リンクはすべてこの commit に固定した。[I0]
- 公開 `bsky.network` にその commit がデプロイ済みかは未確認。実測応答と当該実装の因果経路が一致することと、本番バイナリの同定は別。
- 2026-10-18T00:25:12Z、`bsky.network`への`requestCrawl`を1回再実行し、HTTP 400 / 同じ拒否を確認した。`getHostStatus?hostname=pds.oriom.dev`も`HostNotFound`だった。新しい著者DIDの作成・投稿・本番設定の変更は行っていない。[既存の本番報告](../pds-production-verification-2026-10-18.md)の115テスト成功を今回の実行結果とは扱わない。
- 本番のsystem closureは `/nix/store/swi584rsd245s251wjvcj7bxj6dcvdk6-nixos-system-kagoya-vps-26.05.20260409.4c1018d`。実行中サービスはactive、運用側で固定したPDSランタイムは`84fba1f2434b843eb27ed2ff5ad67263330ad7f9`。この調査で再デプロイしていない。

## 1. 拒否メッセージの正確な発生経路

```text
requestCrawl(hostname)
  → handleComAtprotoSyncRequestCrawl
  → HostChecker.CheckHost("https://" + hostname)
  → com.atproto.ServerDescribeServer
  → GET /xrpc/com.atproto.server.describeServer
  → PDS: HTTP 404 / XRPCNotSupported
  → ErrHostNotPDS: "server is not a PDS"
  → HTTP 400 / HostNotFound:
    "host server unreachable: server is not a PDS:
     API request failed (HTTP 404): XRPCNotSupported: XRPCNotSupported"
```

- handler は `CheckHost` のエラーを **すべて** `HostNotFound` / `host server unreachable` と包む。この文言だけから DNS/TLS 障害と判断してはいけない。今回の内部エラーは HTTP 404 の XRPC 応答。[I1][I2][I9]
- `CheckHost` が呼ぶ API は **`ServerDescribeServer` の一つだけ**。5 秒 timeout、public-only HTTP transport を使う。生成クライアントと request builder から GET の NSID/path まで確認できる。[I2][I10][I11]
- 現 `pds/server.mjs:195–204` は sync/repo の限定 API だけを許可し、`describeServer` は `XRPCNotSupported` になる。[L1] 既存報告の個別 404 とも一致する。したがって、指定 Indigo 実装に対する入口の欠落は確定。ただし修正後の実 Relay 動作は未検証。

## 2. `describeServer` とサーバー DID の必要範囲

| 項目 | 確認結果 |
| --- | --- |
| Lexicon 必須出力 | `did`: DID 形式の文字列、`availableUserDomains`: 文字列配列。`application/json` の object。[I12] |
| 任意出力 | `inviteCodeRequired`, `phoneVerificationRequired`, `blobUploadLimit`, `links`, `contact`。アカウント作成・blob upload を提供しないのに提供可能と偽って返さない。[I12] |
| 空ドメイン配列 | schema に最小要素数はなく、`[]` は許される。必須フィールドの省略や `null` と区別する。[I12] |
| crawl の実装チェック | HTTP 成功と Go struct への JSON decode。返された `did`/ドメインの値は `CheckHost` で参照しない。Lexicon 必須項目の存在・DID 形式をここで厳密検証してはいない。[I2][I10][I13] |
| サーバー DID 解決 | この crawl 経路に、返されたサーバー DID を解決したり、その DID Document の鍵を検証したりする処理はない。`/.well-known/did.json` を取る処理もない。[I1–I4] |

**不完全な `{}` が実装上 decode できることは、標準に適合した解決ではない。** 実装時は実体のあるサービス DID と、実際の能力に合う必須出力を定義すべきであり、著者の DID を便宜的にサーバー DID に流用しない。

`did:web:pds.oriom.dev` をサービス DID に採用するなら、DID Web の解決先は `https://pds.oriom.dev/.well-known/did.json`、document の `id` は当該 DID と一致させる必要がある。[S1] これは **did:web の解決可能性の要件**であって、上記 crawl が実際に要求した追加 HTTP check ではない。`describeServer` schema 自体も `did:web` を指定していない。現アプリには当該well-known routeがなく、本番の同URLもHTTP 404 / NotFoundだった。[L1]

サーバー用署名鍵・service-auth/OAuth 向け鍵の導入がこの拒否解消に必須、とは確認できない。一方、**著者 DID** の現在の `#atproto` 公開鍵と `#atproto_pds` endpoint は repo 検証・正規公開元判定の別要件である。サーバー DID の document に著者の秘密鍵を移す話ではない。[I5][I8][S2]

## 3. 入口通過後のゲート

| 段階 | 指定 Indigo で確認した処理 | sd_2607 での判定 |
| --- | --- | --- |
| 要求受付 | public crawl の無効化、hostname/SSL 制約、domain ban を先に検査。[I1] | 今回の応答は後段の `CheckHost` エラーと一致。将来の運用ポリシー変更は別要因。 |
| ホスト追加 | 新規 host/day 制限、trusted 判定と repo 上限設定、既存 banned host 拒否。非同期で Slurper を起動。[I3] | API 成功は WebSocket 接続成功を待たず、取り込み完了通知ではない。 |
| firehose 接続 | `wss://host/xrpc/com.atproto.sync.subscribeRepos`。接続失敗を再試行し、一定回数で offline。応答 `Server` に `atproto-relay` があれば banned。[I4] | TLS/public 到達性、proxy の Upgrade/timeout/header を修正後に確認する。現在の実 Relay 接続は未達。 |
| 著者発見・公開元 | 未知 DID を解決、DID の PDS endpoint hostname と stream host を比較。不一致なら通常拒否。[I5] | 各著者 DID が正規の論理 PDS ホストを指す必要。ローカルの group 設定だけでは足りない。 |
| 状態・アカウント数 | account limit 超過は `host-throttled`。inactive は commit を落とす。active fast path があり、初回の `getRepoStatus` 呼び出しは必須ではない。[I5][I6][I7] | 現 PDS は公開 repo に `active:true` を返すが、移行・停止等の追従は未対応。[L1][L2] |
| commit 受理・再配信 | CAR、署名、DID/rev 整合、未来 rev（許容 5 分）、2,000,000 bytes/200 ops、古い rev 等を検査。strict/lenient 差あり。[I7][I8] | 自前検証成功だけで当該 validator の合格とはしない。wire/commit 詳細は別途の検証結果で補う。 |

既知 account の host が一致する場合は照合 fast path、identity が取れない場合の署名検証スキップ、MST inversion 失敗を警告にとどめる箇所もある。仕様上の SHOULD と現実装の強制拒否を同一視しない。[I5][I7][I8][S3]

## 4. 初期同期・cursor・上限・健全性

### 既存 repo は登録だけで列挙されない

`SubscribeToHost` → `Slurper.Subscribe` → WebSocket 接続までに `listRepos/getRepo` による一括 bootstrap はない。未知 account は stream 上のイベントから作られる。[I3–I5] 現 PDS にはページング付き `listRepos` と `getRepo` が既にあるので、**不足は API 名ではなく初期回収の成立確認**である。[L1]

初回 `LastSeq=0` の Indigo は cursor パラメータを付けない。現 PDS の `server.mjs:409–417` は cursor なしで `latest` を選ぶ。したがって、静止した既存 repo はこの経路だけでは発見されない。後から新規 commit が届いても過去全レコードの回収を証明したことにはならない。過去 seq からの再送、適切な `#sync`、別途の列挙・CAR bootstrap のどれを誰が行うかを検証環境で明確にする。**本番 stream を巻き戻したり、同じ commit を新しい著者署名として捏造したりして解決しない。** [I4][I5][L1][S3]

### 再開と上限

- Indigo は保存済み正の `LastSeq` で再開。PDS の DB/seq が巻き戻って `FutureCursor` を返すと host を `idle` にして購読を止める。hostname 単位の永続 seq とバックアップ復元・切替の整合が必要。[I4][I14]
- repo 上限は `DefaultRelayConfig` では通常 100、trusted は 10,000,000、新規 host/day は 50。ただしソース自身が CLI による上書きを明記している。**公開 Relay の現在の上限値ではない。** [I15]
- event 制限は通常 host で毎秒 `baseline + accountLimit/1000`、毎時 `baseline + accountLimit`、毎日 `baseline + accountLimit*10`。trusted は別枠。baseline のソース既定値は 50/s、2,500/h、20,000/day。実運用値・受信可能量は未確認。[I4]

### 成功判定を混同しない

1. PDS `/health` は `{ok:true}` の生存確認であり、公開 repo/Relay 取り込みの保証ではない。[L1]
2. Relay `getHostStatus` の `status/seq/accountCount` で登録・進捗を観測する。`listHosts` は `last_seq>0` の host のみ返すので、一覧にないことだけで未登録と断定しない。[I14][I16]
3. seq 前進だけでも commit 受理の保証にはならない。Slurper は処理エラーをログに出した後も callback を成功で返す箇所がある。[I4]
4. 合格条件は対象 DID の `getRepoStatus/getLatestCommit`、Relay 出力 firehose の対象 commit、元 CID/rev/署名との一致、既存 CAR からの初期回収、切断後の再開を合わせる。AppView に独自 collection が表示されることはさらに別のアプリ側要件。[I7][S3]

## 5. 本番データを使った動的検証

### 5.1 初回cursorなし購読と既存履歴

本番に対し、それぞれ3秒間WebSocketを開き、新しい投稿は行わず観測した。

```json
{"cursor":null,"opened":true,"observationMs":3000,"frames":0}
{"cursor":0,"opened":true,"observationMs":3000,"frames":17,"firstSeq":1,"lastSeq":17}
```

3秒間の無通信だけで永続的な不達を証明したわけではない。しかし、cursorなしは最新位置から開始するローカルコードと、初回cursorを送らず列挙もしない指定Relayコードの組み合わせを実応答で確認した。cursorなしのライブ開始自体を仕様違反と呼ぶのではなく、**登録時点で既に存在するrepoをどう発見させるかが未設計・未検証**と区別する。同期APIを追加列挙するだけでは、このRelayが自動で呼ぶようにはならない。

### 5.2 公式Indigo Go検証器で8commitを確認

指定commitのIndigoをWSLの一時ディレクトリへ展開し、最小Goハーネスで本番`subscribeRepos?cursor=0`を読み取った。2026-10-18T00:32:23Zに開始、30秒の読取期限・256フレーム上限・2.5MB/フレーム上限を設け、全17フレームから専用DID `did:plc:i2tq3bvlzchjxqa3qgckybmx` の8commitを抽出した。公式の`repo.LoadCommitFromCAR`、`Commit.VerifyStructure`、実PLC Documentから得た公開鍵による`Commit.VerifySignature`、`repo.VerifyCommitMessage`を実行した。[I17][I18]

| seq | rev | CAR bytes | 構造・署名 | 公式MST逆演算 |
| ---: | --- | ---: | --- | --- |
| 3 | `3my4bhfhq6s2a` | 718 | 合格 | 合格（初回prevDataはnull、前root比較は省略） |
| 4 | `3my4bhfja222a` | 1,191 | 合格 | 合格 |
| 5 | `3my4bhfjitc2a` | 1,550 | 合格 | 合格 |
| 6 | `3my4c5qyw5k2z` | 2,023 | 合格 | 合格 |
| 7 | `3my4c5qzxe22z` | 2,382 | 合格 | 合格 |
| 8 | `3my4c5r2hxc2z` | 2,742 | 合格 | 合格 |
| 9 | `3my4c65jx5s2j` | 3,103 | 合格 | 合格 |
| 16 | `3my4cynwyos2m` | 3,464 | 合格 | 合格 |

各イベントのcommit CIDはCAR rootと一致し、初回以外の`prevData`と`since`も、直前の対象commitのMST root・revと一致した。各CARに以前のrecordがすべて残っていることも確認した。したがって、**今回のcreate専用履歴では、完全CARでも公式逆演算に通る**。差分CAR化は容量効率・大容量対応の課題であり、小さい現在のcommitが落ちる原因ではない。

負の対照として、seq16の**メモリ上のコピーだけ**の`prevData`を別の有効CIDへ変えたところ、公式検証器が以下を返した。元の本番イベントは変更していない。

```text
seq=16 negative_control_wrong_prevData=FAIL: inverted tree root didn't match prevData
```

本番`getRepo`のCARもロード・構造・署名に合格し、seq16のCARとバイト一致した。

```text
commit: bafyreigc5ofkwhxokkm3ay4vqvqoshvwcykgazcvpwfh3othwfdkgobiwu
data:   bafyreiatpo22jjwvemexmbkseotwhjgh3gtkptzxlrzj2pjofsvyokqjxy
CAR SHA256: 7b126a09e9dfdb131b77db028d0543d30dbeb0e26432752bae53293e0c7b73fc
```

今回コンパイルしたのは公式ライブラリを呼ぶ最小ハーネスであり、Relay全サービスを動かした試験ではない。公開`bsky.network`が入口を通過したという意味でもない。なお指定Relayのstrict実装自身はMST逆演算エラーを警告にとどめる箇所があるが、上記8件はその緩和に頼らず逆演算そのものに合格した。[I8]

### 5.3 大容量分岐の不合格をオフライン再現

現PDSの`ingest.mjs:142–145`は完全CARが900,000 bytesを超えると、`tooBig=true, blocks=[], ops=[]`を配信する。[L3] seq16のメモリ上コピーをこの形にして、公式`LoadCommitFromCAR`を実行した。

```text
seq=16 negative_control_tooBig=true blocks=0 ops=0 LoadCommitFromCAR_error="EOF"
```

指定Relayの`VerifyRepoCommit`はCAR読込をstrict/lenient分岐より先に行い、エラーなら即returnする。したがって、この空CARは **LenientSyncValidationでも救済されない**。[I8][I17] これは巨大投稿を本番で発生させた結果ではなく、実装分岐と同じwire形状のオフライン対照試験である。

現行Lexiconの`#commit`はblocks上限2,000,000 bytes・ops上限200、`tooBig`はdeprecatedで、復旧にはcommitを含む小さなCAR付きの`#sync`を定義する。[I19] 現PDSは`#sync`を発行していない。完全CAR全体を毎回送る方式を継続するならrepo全体の増加がこの分岐を引き起こすため、**差分/証明CARと正しい#sync・再同期経路**の設計・試験が必要。

### 5.4 残る境界条件

指定RelayはrevのTID時刻が現在より5分超未来なら拒否する。[I8] 現PDSはTID構文・署名とbaseに対するrev増加を確認するが、未来時刻の上限は確認していない。[L3][L4] そのため「PDS側validatedだがRelay拒否」になり得る。**今回の8件がこれに該当するとは確認しておらず**、端末時刻ずれへの方針と隔離試験を追加する課題である。既存署名を書き換えて時刻を補正する方法は使えない。

### 5.5 実行環境・再現資料

一次ソースを展開した一時ディレクトリ（WSL内、Git管理外）:

```text
/tmp/indigo-verify-wwyera5b/
  indigo-e076b63ff34a010d47ed1bfe82c767b23c3a7eb6/verify-production.go
  indigo-e076b63ff34a010d47ed1bfe82c767b23c3a7eb6/check-offline.go
  result.jsonl
  verifier.stderr
  offline-check.txt
  offline-tooBig-check.txt
  seq-16.frame
  getRepo.car
  plc.json
```

Nixが用意したGo 1.25.4から、固定moduleが指定するGo 1.26.1を自動取得した。`go build -mod=readonly`に成功し、元の`go.mod/go.sum`が不変であることも確認した。

```sh
nix shell path:/nix/store/24b29jfv23y9mh5ib7a5yljcgm0n65kw-source#go --command sh -c 'cd /tmp/indigo-verify-wwyera5b/indigo-e076b63ff34a010d47ed1bfe82c767b23c3a7eb6 && go build -mod=readonly -o /tmp/indigo-verify-wwyera5b/verify-production verify-production.go'
/tmp/indigo-verify-wwyera5b/verify-production
nix shell path:/nix/store/24b29jfv23y9mh5ib7a5yljcgm0n65kw-source#go --command sh -c 'cd /tmp/indigo-verify-wwyera5b/indigo-e076b63ff34a010d47ed1bfe82c767b23c3a7eb6 && go run -mod=readonly verify-production.go check-offline.go'
```

一時ファイルは将来消えるため、恒久的な自動回帰テストを追加したという主張はしない。採用時は固定Indigoを使う隔離環境の相互運用テストへ移す。

## 6. 推奨する作業順（今回は実装しない）

1. **確定 blocker**: 論理 PDS ごとの実サービス DID を決め、Lexicon 準拠の `describeServer` を追加。任意の成功 JSON で偽装しない。
2. **確定した経路上の穴**: 空の Relay DB + 既存投稿だけの PDS を使い、初回購読の bootstrap 方針を検証する。`listRepos` があるだけで既存投稿が見えると主張しない。
3. **確定した大容量非互換**: 空CAR付き`tooBig`を廃止し、差分/証明CARとcommit CAR付き`#sync`・再同期の経路を実装・隔離検証する。大容量で空blocksのままではlenient設定でも失敗する。[I8][I17][I19][L3]
4. **次の適合性確認**: 指定commitのRelay全体を隔離環境で動かし、DID正規endpoint、active/limit、未来rev、初期回収、切断再開・FutureCursorを順に検証する。既存8件の公式ライブラリ検証合格を、登録から配信までの合格へ拡張する。
5. **本番合格は別途**: 修正・隔離試験後、運用者の方針と上限を確認して最小の参加試験を計画する。今回の報告だけで本番互換性を合格へ変更しない。
6. **別スコープ**: `createRecord/applyWrites`、session/OAuth、blob upload、アカウント作成を伴う一般 PDS 化は、端末単一 writer・秘密鍵非保管という設計との整合を別途判断する。今回の拒否の直接修正として一括追加しない。[L2]

## 一次資料

Indigo 実装の参照先はすべて `e076b63ff34a010d47ed1bfe82c767b23c3a7eb6`。行番号はその raw source に基づく。公式仕様ページは 2026-10-18 取得の可変 URL。

- [I0] [固定 commit](https://github.com/bluesky-social/indigo/commit/e076b63ff34a010d47ed1bfe82c767b23c3a7eb6)、[root tree API](https://api.github.com/repos/bluesky-social/indigo/git/trees/e076b63ff34a010d47ed1bfe82c767b23c3a7eb6)、[relay tree API](https://api.github.com/repos/bluesky-social/indigo/git/trees/66cdd0eba5a52355ee37ec48b55648f00ddac0ab)。`main` 解決 API は `https://api.github.com/repos/bluesky-social/indigo/commits/main`（可変）。
- [I1] [handlers.go L19–69: requestCrawl](https://github.com/bluesky-social/indigo/blob/e076b63ff34a010d47ed1bfe82c767b23c3a7eb6/cmd/relay/handlers.go#L19-L69)
- [I2] [host_checker.go L32–65: HTTP client / CheckHost](https://github.com/bluesky-social/indigo/blob/e076b63ff34a010d47ed1bfe82c767b23c3a7eb6/cmd/relay/relay/host_checker.go#L32-L65)
- [I3] [crawl.go L11–56: SubscribeToHost](https://github.com/bluesky-social/indigo/blob/e076b63ff34a010d47ed1bfe82c767b23c3a7eb6/cmd/relay/relay/crawl.go#L11-L56)
- [I4] [slurper.go L66–87: baseline](https://github.com/bluesky-social/indigo/blob/e076b63ff34a010d47ed1bfe82c767b23c3a7eb6/cmd/relay/relay/slurper.go#L66-L87)、[L178–190: limiter 計算](https://github.com/bluesky-social/indigo/blob/e076b63ff34a010d47ed1bfe82c767b23c3a7eb6/cmd/relay/relay/slurper.go#L178-L190)、[L245–475: 非同期購読・cursor・callback](https://github.com/bluesky-social/indigo/blob/e076b63ff34a010d47ed1bfe82c767b23c3a7eb6/cmd/relay/relay/slurper.go#L245-L475)
- [I5] [account.go L59–194: 著者発見・host 照合・上限](https://github.com/bluesky-social/indigo/blob/e076b63ff34a010d47ed1bfe82c767b23c3a7eb6/cmd/relay/relay/account.go#L59-L194)
- [I6] [account.go L196–240: active 判定](https://github.com/bluesky-social/indigo/blob/e076b63ff34a010d47ed1bfe82c767b23c3a7eb6/cmd/relay/relay/account.go#L196-L240)、[host_checker.go L67–93: getRepoStatus](https://github.com/bluesky-social/indigo/blob/e076b63ff34a010d47ed1bfe82c767b23c3a7eb6/cmd/relay/relay/host_checker.go#L67-L93)
- [I7] [ingest.go L54–146: preProcessEvent / processCommitEvent](https://github.com/bluesky-social/indigo/blob/e076b63ff34a010d47ed1bfe82c767b23c3a7eb6/cmd/relay/relay/ingest.go#L54-L146)
- [I8] [verify.go L22–165: サイズ・署名・strict validation](https://github.com/bluesky-social/indigo/blob/e076b63ff34a010d47ed1bfe82c767b23c3a7eb6/cmd/relay/relay/verify.go#L22-L165)
- [I9] [errors.go L7–13: ErrHostNotPDS](https://github.com/bluesky-social/indigo/blob/e076b63ff34a010d47ed1bfe82c767b23c3a7eb6/cmd/relay/relay/errors.go#L7-L13)
- [I10] [serverdescribeServer.go L24–49: 生成出力型・呼び出し](https://github.com/bluesky-social/indigo/blob/e076b63ff34a010d47ed1bfe82c767b23c3a7eb6/api/atproto/serverdescribeServer.go#L24-L49)
- [I11] [apirequest.go L16–20 / L78–101: HTTP method と path](https://github.com/bluesky-social/indigo/blob/e076b63ff34a010d47ed1bfe82c767b23c3a7eb6/atproto/atclient/apirequest.go#L16-L101)
- [I12] [describeServer.json L5–46: Lexicon 出力要件](https://github.com/bluesky-social/indigo/blob/e076b63ff34a010d47ed1bfe82c767b23c3a7eb6/lexicons/com/atproto/server/describeServer.json#L5-L46)
- [I13] [lexclient.go L53–91: HTTP error / JSON decode](https://github.com/bluesky-social/indigo/blob/e076b63ff34a010d47ed1bfe82c767b23c3a7eb6/atproto/atclient/lexclient.go#L53-L91)
- [I14] [host.go L56–72: everActive filter](https://github.com/bluesky-social/indigo/blob/e076b63ff34a010d47ed1bfe82c767b23c3a7eb6/cmd/relay/relay/host.go#L56-L72)、[L127–137: cursor 永続化](https://github.com/bluesky-social/indigo/blob/e076b63ff34a010d47ed1bfe82c767b23c3a7eb6/cmd/relay/relay/host.go#L127-L137)
- [I15] [relay.go L40–61: config 既定値・host check bypass の位置付け](https://github.com/bluesky-social/indigo/blob/e076b63ff34a010d47ed1bfe82c767b23c3a7eb6/cmd/relay/relay/relay.go#L40-L61)
- [I16] [handlers.go L71–128: listHosts / getHostStatus](https://github.com/bluesky-social/indigo/blob/e076b63ff34a010d47ed1bfe82c767b23c3a7eb6/cmd/relay/handlers.go#L71-L128)
- [S1] [DID Web: Read (Resolve)](https://w3c-ccg.github.io/did-method-web/#read-resolve)
- [S2] [atproto DID: DID Documents](https://atproto.com/specs/did#did-documents)
- [S3] [atproto Sync: Message Validation Checklist / Reliable Repository Synchronization](https://atproto.com/specs/sync)
- [I17] [atproto/repo/car.go L82–85: LoadCommitFromCAR](https://github.com/bluesky-social/indigo/blob/e076b63ff34a010d47ed1bfe82c767b23c3a7eb6/atproto/repo/car.go#L82-L85)
- [I18] [atproto/repo/sync.go: VerifyCommitMessage・MST逆演算](https://github.com/bluesky-social/indigo/blob/e076b63ff34a010d47ed1bfe82c767b23c3a7eb6/atproto/repo/sync.go)、[commit.go: VerifyStructure / VerifySignature](https://github.com/bluesky-social/indigo/blob/e076b63ff34a010d47ed1bfe82c767b23c3a7eb6/atproto/repo/commit.go)
- [I19] [固定Lexicon: subscribeReposのcommit / sync](https://github.com/bluesky-social/indigo/blob/e076b63ff34a010d47ed1bfe82c767b23c3a7eb6/lexicons/com/atproto/sync/subscribeRepos.json)
- [L3] [本番固定版ingest.mjs L131–146: stream発行・tooBig](https://github.com/jphacks/sd_2607/blob/84fba1f2434b843eb27ed2ff5ad67263330ad7f9/pds/ingest.mjs#L131-L146)
- [L4] [本番固定版repository.mjs L121–138: commit構造・署名](https://github.com/jphacks/sd_2607/blob/84fba1f2434b843eb27ed2ff5ad67263330ad7f9/pds/repository.mjs#L121-L138)
- [L1] [現 PDS server.mjs](../../pds/server.mjs): L158–212（health、API 分岐）、L387–417（poll と初回 cursor）。ローカル実装であり、本番への反映差分は本書では同定しない。
- [L2] [現 PDS README: 外部インターフェース・現時点の境界](../../pds/README.md#現時点の境界)。既存の本番報告・protocol 文書は変更していない。
