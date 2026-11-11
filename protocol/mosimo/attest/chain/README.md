# 署名チェーンの同梱

識別子: `dev.jisaku.mosimo.attest.chain`

## 目的

投稿の著者鍵が、受信者の信頼するルート鍵からどの鍵署名を経て証明されているかを、投稿自身の拡張として同梱する。受信者は投稿とチェーンだけで、インターネットに接続せずに発信者を確認できる。公的機関が数週間インターネットから切り離されたまま発信を続ける状況でも、作成時に有効だった鍵署名を受信者が確認し続けられることを優先した設計である。

本拡張がない投稿、未対応の受信者、検証に失敗した投稿は「発信者未確認」として扱い、本文の表示・著者署名の検証・中継はそのまま行う。発信者の確認は追加情報であり、通常投稿の処理の前提ではない。

## 前提

| 識別子 | 役割 |
|---|---|
| [`dev.jisaku.mosimo.attest`](../README.md) | 鍵署名の形式とチェーン検証 |
| [`dev.jisaku.mosimo`](../../README.md) | 拡張ブロックの構造、Nostr タグ・atproto レコード・メッシュ CBOR での運び方 |
| [`dev.jisaku.mosimo.attest.revocation`](../revocation/README.md) | 失効状態の評価 |

## 1. 構造

```ts
type ChainExtension = {
  $type: "dev.jisaku.mosimo.attest.chain";
  attestations: KeyAttestation[];   // ルート直下 → 末端
  binding?: Binding;                // 末端鍵が 33 バイト鍵のときに必須
};

type BindingDeclaration = {
  $type: "dev.jisaku.mosimo.attest.chain#bindingDeclaration";
  did: Did;
  atprotoPubkey: PublicKey;         // 33 バイト
  nostrPubkey: PublicKey;           // 32 バイト
  consent: "Use this key pair for this DID's Mosimo publications";
};
type Binding = {
  declaration: BindingDeclaration;
  atprotoSig: Signature;            // atproto 鍵による H(declaration) への署名
  nostrSig: Signature;              // Nostr 鍵による H(declaration) への署名
};
```

```mermaid
flowchart LR
  root[受信者が事前に持つ<br/>ルート公開鍵] -->|検証| a0[attestations 0<br/>issuer = ルート]
  a0 --> a1[attestations 1]
  a1 --> an[attestations n<br/>subject = 末端鍵]
  an -->|32 バイト鍵| ev[投稿の pubkey と署名]
  an -->|33 バイト鍵| b[binding] --> ev
```

- 末端の `subject` が 32 バイト鍵なら、投稿の Nostr `pubkey` と一致しなければならない。
- 末端の `subject` が 33 バイト鍵なら、それは著者の atproto リポジトリ署名鍵であり、`binding` を通じて Nostr `pubkey` へ接続する。`declaration.atprotoPubkey` と末端 `subject`、`declaration.nostrPubkey` と `pubkey`、`declaration.did` と commit の DID を一致させ、両署名を検証する。三用途の鍵（Nostr・atproto・PLC）は同じ曲線でも別々に生成する。一つの鍵の漏えいが他の用途に波及しないようにするためである。
- 一つの投稿に本拡張を複数置いてよい。異なるルートに至るチェーンを提示する場合に用い、各要素は独立に検証する。中継者は要素を統合・並べ替え・削除してはならない。拡張は著者の署名対象であり、変更すると著者署名が検証できなくなる。

## 2. 運び方

Nostr では自己完結拡張タグとして運ぶ。

```text
["mosimo:ext:dev.jisaku.mosimo.attest.chain", B64(C({attestations, binding}))]
```

atproto レコードでは `ext` の要素として同じオブジェクトを置く。メッシュ用 CBOR では共通接頭辞の規則によりタグ値が `bstr` になる。拡張全体の `C` は 16,384 バイト以下を推奨する。

メッシュでは、送信側が各鍵署名を `H(body)` の参照に置き換えて運び、受信側が手元の鍵署名で元のバイト列へ復元してから Nostr 署名を検証できる（[メッシュ配送](../../mesh/README.md)）。参照の置き換えは署名済みイベントの外側で行われるため、復元後のイベントが元と一致しなければ Nostr 署名の検証で検出される。受信側が復元に使ってよい鍵署名は、過去に復元後の署名検証に成功したイベントに含まれていたものに限る。

## 3. 検証

受信者は投稿ごとに次を行い、結果と最初に観測した時刻を投稿に紐付けて保持する。

| 手順 | 内容 | 失敗時の状態 |
|---|---|---|
| 1 | 投稿の Nostr 署名と基本タグを検証 | 投稿自体が不正 |
| 2 | 末端鍵と `pubkey` の対応（§1）。`binding` があれば両署名 | `invalid` |
| 3 | [鍵署名 §5](../README.md#5-チェーンの検証) のチェーン検証。評価時刻 `t` は投稿の `created_at` | `invalid` / `expired` / `unverifiable` |
| 4 | 受信者が信頼できる時計を持つ場合、現在時刻でも `keyValidity` を評価 | `expired-now` を併記 |
| 5 | [失効通知 §6](../revocation/README.md#6-受信者の状態) の失効状態を、チェーン上の全鍵について評価 | `revoked` / `unknown` |
| 6 | 末端の `roles` から、投稿に認められる扱いを決める | 該当する扱いだけを無効化 |

手順 3 は投稿の作成時刻で評価する。手順 4 は「現在も有効な発信者か」という別の問いであり、両者を同じ状態として表示してはならない。期限切れの鍵による過去の投稿と、現在有効な鍵による投稿を受信者が区別できなくなるためである。

## 4. 投稿の扱い

### 4.1 役割と投稿の扱い

| 末端の `roles` | 認められる扱い |
|---|---|
| `identity` | 発信者名として `organization.orgName`（なければ `owner.name`）を、`trustLevel` と確認状態を添えて表示する |
| `public-information` | 組織が自らの名で公的情報を発信する。訂正・取消の対象が、同じ `organization.orgId` を持つ検証済みチェーンの投稿であるとき、旧版の拡散停止を認可する。`organization` のない鍵には認められない。取り込んだ公的情報の更新・取消は [転載サーバー §3](../importer/README.md#3-受信者の認可) に従う |
| `import` | 転載サーバー §3 に従う |
| `attest` | 投稿には効果を持たない |

`identity` のない鍵の投稿を組織名や所有者名で表示してはならない。`public-information` のない鍵の訂正・取消は主張として保持できるが、他の投稿の拡散停止の根拠にしてはならない。拡散停止は他者の投稿に対する強い効果であり、明示的に付与された役割だけを根拠にする。

### 4.2 失効と拡散

`revoked` となった投稿は、その受信者で新たな広告・転送の対象から外す。既に保持した本文の表示は失効状態を添えて続けてよい。失効の判定には投稿の `created_at` と、受信者がその投稿を最初に観測した時刻の両方を用いる。

### 4.3 表示

表示は少なくとも次を区別する。

- 発信者確認済み（`verified`、失効なし、現在も有効）
- 作成時点で確認済み（`verified`、現在は期限切れまたは時計不明）
- 失効情報の確認時点（最後に失効情報を得た時刻。[失効リスト配信 §5](../revocation/list/README.md#5-クライアントと-mosimo-サーバーの確認)）
- 失効（`revoked`）
- 未確認（拡張なし、未知のルート、検証失敗）

## 参照

- [NIP-01: Basic protocol flow](https://github.com/nostr-protocol/nips/blob/master/01.md)
- [atproto Repository](https://atproto.com/specs/repository)
