# 0012: atproto・Nostr・CBOR のデータ表現

状態: draft-01 — 投稿単位・署名の責務・変換方向は合意済み。以下の配置案は未確定。正式なタグ名・NSID・CBOR 配列位置の登録ではない。

## 要旨

[基本投稿](0010-core.md) と [拡張](0011-extensions.md) が、atproto レコード・署名済み commit・Nostr イベント・メッシュ用 CBOR のどこに入るかを説明する。説明用の中間構造を、新たな署名形式や別配送イベントとして追加するものではない。

## 1. 全体の対応（要件）

| 情報 | atproto | Nostr | メッシュ区間 |
|---|---|---|---|
| 人が読む本文 | 独自 Lexicon の本文フィールド | `content` | CBOR payload の本文 |
| 投稿作成時刻 | レコードの日時 | `created_at` は Unix 秒。精度差の扱いは配置案に記載 | 元の Nostr 値を維持 |
| 配送期限・著者拡張 | レコードの項目 | 同じイベントの署名対象タグ | タグを含めて元のイベントを保持 |
| repository path | MST のキー | 同じイベントの署名対象タグ | 同左 |
| 署名済み commit | repository v3 の commit | 同じイベントの署名対象タグ | 同左 |
| 適用元 commit CID | 本プロトコルの変更適用元。標準 `prev` とは別 | 同じイベントの署名対象タグ | 同左 |
| 著者の識別 | commit の DID、DID 情報の署名鍵 | `pubkey`。DID との鍵対応は独立宣言 | 元の Nostr 公開鍵 |
| 著者署名 | atproto commit の ECDSA | イベント全体の BIP-340 | 元の両署名を維持 |
| hop・経過時間 | レコードへ入れない | 元イベントへ追記しない | BPv7 の可変な配送状態 |

一投稿の本文・レコード・path・commit・適用元 CID は同じ Nostr イベントにまとめる。初回適用元なしを明示し、後続は端末が直前に確定した commit の CID を指定する。標準 atproto commit の `prev` の意味を変更しない。

## 2. atproto 表現（配置案）

### 2.1 独自レコード

```ts
type PostRecord = {
  $type: string; // 正式な独自Lexicon NSIDは未決定
  text: Text;
  createdAt: Timestamp;
  deliveryExpiresAt: Timestamp;
  extensions: Extension[];
};
```

これは論理構造である。正式 Lexicon では既知の拡張に型を割り当て、未知拡張の保持方法を含めて定義する。任意のオブジェクトをそのまま Lexicon の固定スキーマへ記述できると仮定しない。拡張一覧を open union にするか、拡張本体を bytes にして別スキーマとするかは未確定。

レコードを標準の atproto CBOR で符号化して record CID を得る。repository path は `<独自collection>/<record-key>`。本文投稿ごとに新しい path へ追加し、既存投稿の更新には使わない。NSID・record key の生成方式・上限は未決定である。

現行 Data Model は DRISL（DAG-CBOR の後継として説明される正規化 CBOR）を規定する。任意の CBOR エンコーダーの出力や JSON のキー整列を、そのまま同じ CID を作る手段とはしない。一般の deterministic CBOR と atproto の符号化制約を区別する。

### 2.2 commit と操作

標準 repository v3 commit の構造は次のとおり。これは本プロトコルが新設する型ではない。

```ts
type AtprotoCommitV3 = {
  did: Did;
  version: 3;
  data: CidLink; // MST root
  rev: string;   // TID。単調増加するrepository revision
  prev: CidLink | null;
  sig: Bytes;
};
```

`CidLink` は標準 CID link。`sig` を含めた commit の符号化バイト列を保持する。`rev` は投稿 ID でもメッシュ hop 数でもなく、`data` は投稿レコード CID ではない。

配送する変更情報の論理構造は次のように表せる。

```ts
type CreateRecordChange = {
  baseCommit: Cid | null;
  commit: Bytes;         // 元の署名済みcommitのatproto CBOR
  action: "create";
  path: RepositoryPath;
  record: PostRecord;
};
```

`record` と `commit` の CID は元のバイト列から得られるため、重複配送が必須な追加項目とはしない。DID も commit に含まれる。MST ノード・包含証明はこの構造へ加えない。削除操作は `action: "delete"` と対象 path を使う方向で別途具体化し、上記の追加レコード型と混同しない。

PDS はこれらの操作から MST を再構築する。入力側のこの独自表現と、標準同期に用いる MST ノードを含む CAR 出力は別である。

## 3. Nostr 表現

### 3.1 標準フィールド（要件）

| フィールド | 内容 |
|---|---|
| `kind` | 通常投稿・訂正・補足・追認は `1`。削除要求は NIP-09 の `5` |
| `content` | `Post.text` そのもの。XML・CBOR・CAR の Base64 を本文の代わりにしない |
| `tags` | 検索用 `t` タグ、投稿の構造化情報と commit 等。並び順・文字列を署名後に変えない |
| `created_at` | Nostr の Unix 秒整数 |
| `pubkey`・`id`・`sig` | 標準 Nostr の値・符号化 |

Nostr のタグは文字列配列の配列であり、JSON object や生バイト列を直接入れられない。通常リレーへ送る JSON では追加データの文字列表現が必要となる。署名対象外のトップレベル独自フィールドへ commit を置く案は採用しない。

### 3.2 本文を二重格納しない配置案

一案として、`text` 以外のレコードの全フィールドと、commit・適用元・path を CBOR にまとめ、タグの一つの文字列へ符号化する。

```text
Nostr content = 元レコードのtext
タグ内の説明用構造 = {
  version,
  baseCommit,
  commit,
  action: "create",
  path,
  recordFields: 元レコードからtextだけを除いた全フィールド
}
```

`recordFields` から既知フィールドだけを抜き出すのではなく、未知項目を含めて残す。PDS は `recordFields` に `text = content` を戻して元のレコードを構成する。この案では、拡張・正確なレコード作成時刻・配送期限は `recordFields` に一度だけ格納する。レコード内へ Nostr 署名や自分自身の event ID を埋め込まないため、署名生成順序の循環を作らない。

| 項目 | この配置案での扱い |
|---|---|
| 本文 | `content` に一度だけ格納 |
| レコードの残り | `recordFields` を atproto データ型のまま符号化 |
| commit | 元の署名済み CBOR の bytes。再署名しない |
| タグへの文字列表現 | Base64 等が候補。アルファベット・padding・正式タグ名は未決定 |
| `createdAt` の秒未満 | `recordFields` に原値を保持。`created_at` への丸め方は未決定 |
| `t` タグ | プロトコル検索用。識別値は未決定 |
| 地域等の検索用タグ | 必要なら別途署名時に追加。原拡張との対応は未決定 |

この配置案はサイズ重複を減らすための具体案であり、採用済みの wire format ではない。最終的な型を固定するまでは、説明用の名前を独自タグとして実ネットワークへ配布しない。

### 3.3 制御データ

| データ | 配送単位 | 未確定部分 |
|---|---|---|
| PLC 初期操作 | 通常投稿とは独立した制御イベント。未登録時の例外経路 | kind・符号化・期限 |
| DID を含む両鍵署名の鍵対応宣言 | 独立した制御イベント。毎投稿の同梱は不要 | kind・参照・取得・更新 |
| NIP-09 削除要求と削除 commit | 著者が双方を発行する制御処理 | 一つの kind 5 への具体配置、複数対象の単位 |

これらが未取得でも、通常の Nostr 署名付き本文を扱える。一方、申告 DID や公式機関への帰属まで確認済みとはしない。

## 4. メッシュ用 CBOR（配置案）

まず元イベントを変更しない単純な表現を基準として説明する。

```cddl
compact-event = [
  version: uint,
  pubkey: bstr .size 32,
  created-at: uint,
  kind: 0..65535,
  tags: [* [tstr, * tstr]],
  content: tstr,
  sig: bstr .size 64
]
```

この CDDL は配列の候補であり、版の値・採用を確定するものではない。Nostr event ID は標準の署名対象から再計算できるため、配送時には省略できる。公開鍵・署名を hex のまま運ばず bytes にする。タグの文字列・順序・本文はそのまま保持する。

タグ内部の Base64 CBOR がそのまま残る分、この単純案には文字列符号化のオーバーヘッドがある。最初から全ての独自タグを自動変換してよいわけではない。将来、固定されたプロファイルタグに限り、その文字列表現を元どおり再生成できる規則を定めれば、該当データだけを bytes にする最適化が可能である。未知のタグはそのまま保持する。

CBOR 符号化だけで汎用圧縮をしたとは表現しない。DEFLATE 等の追加圧縮、辞書、サイズ削減率は未採用・未測定。圧縮方式を追加しても著者署名や CID の元の対象バイト列は変わらない。

## 5. 変換方向と保持するもの

```mermaid
flowchart TD
  A[本文と任意の拡張] --> B[独自レコード・path]
  B --> C[MST更新・端末署名済みcommit]
  C --> D[本文とレコード・path・commitを持つ単一Nostrイベント]
  D <--> E[同じイベントのCBOR表現]
  E <--> F[BPv7 payload]
  D --> G[PDSによる元レコードとcommitの取得]
  G --> H[MST再構築・標準atproto公開]
```

- Nostr とメッシュ用 CBOR の間では元の event ID・署名・署名対象フィールドを維持する。
- Nostr から atproto へは元の record CID・commit CID・署名を維持する。
- atproto データだけから元の Nostr イベントを逆生成する機能は延期。レコードへの Nostr 署名保存は要求しない。
- BPv7 全体は可逆変換対象ではない。ゲートウェイが持つ経路ごとの hop・経過時間まで Nostr へ移さない。

## 6. BSAF との表現上の境界

本プロトコルは独自 Lexicon、BSAF は既存 `app.bsky.feed.post.tags` を使う。ここでの「タグ」は Nostr の `tags: string[][]` とは型が異なる。BSAF の文字列タグをそのまま Nostr タグと呼ばない。

独自レコードを BSAF 投稿に偽装せず、BSAF 投稿へ本書の commit・配送期限の同梱を要求しない。対応 AppView での収集と表示の対応は [0013 §6.4](0013-public-information.md#64-bsaf-appviewの別入力) に記す。

## 7. 参照

- [atproto Data Model](https://atproto.com/specs/data-model)（2026-10-17 確認）
- [atproto Repository](https://atproto.com/specs/repository)（2026-10-17 確認）
- [NIP-01](https://github.com/nostr-protocol/nips/blob/master/01.md)（2026-10-17 確認）
- [RFC 8949 — CBOR](https://www.rfc-editor.org/rfc/rfc8949.html)
- [RFC 4648 — Base Encodings](https://www.rfc-editor.org/rfc/rfc4648.html)
