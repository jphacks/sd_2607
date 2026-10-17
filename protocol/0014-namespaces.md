# 0014: 公式名前空間と識別子の割り当て

状態: draft-01 — 管理ドメイン `mosimo.oriom.dev` の使用承認と割り当ての委任に基づく確定事項。名前の割り当てであり、個別スキーマ・公開サービスの完成を意味しない。

## 1. 管理範囲

本プロトコルの公式名前空間は `mosimo.oriom.dev` を基点とする。必要な公式のサブドメイン・ドメイン由来の識別子もこの管理範囲で割り当てる。

この文書で DNS レコード・Web サーバー・PDS・Nostr リレーを作成したわけではない。個人情報・秘密鍵等を含む設定値は記載しない。プロトコルの正式な表示名称と、ドメインに基づく機械識別子は区別する。

## 2. Lexicon 名前空間

公式 Lexicon の NSID 接頭辞は `dev.oriom.mosimo` とする。投稿と共通定義を同じ authority にまとめ、用途ごとの authority サブドメインを現時点で増やさない。

| 識別子 | 役割 | 確定範囲 |
|---|---|---|
| `dev.oriom.mosimo.post` | 通常投稿・訂正・補足・追認のレコード型と collection | NSID を割り当て。スキーマの未決項目は残る |
| `dev.oriom.mosimo.defs` | 公式拡張の共有定義を収める Lexicon | NSID を割り当て。各拡張は下記の定義を参照 |

### 2.1 公式拡張の型参照

| 拡張 | `$type` |
|---|---|
| 発信地点 | `dev.oriom.mosimo.defs#origin` |
| 影響範囲 | `dev.oriom.mosimo.defs#area` |
| 情報の有効期間 | `dev.oriom.mosimo.defs#validity` |
| 訂正・補足・追認の注釈 | `dev.oriom.mosimo.defs#annotation` |
| 原情報・取得・変換の出典 | `dev.oriom.mosimo.defs#source` |
| 公的情報の形式別の意味 | `dev.oriom.mosimo.defs#publicInformation` |
| 身元証明 | `dev.oriom.mosimo.defs#identity` |

型名を割り当てたことと、型の必須フィールド・上限・証明書形式等を確定したことは異なる。未決定のスキーマを公開済み Lexicon として扱わない。既存仕様の `public-information` は説明用の名称であり、型参照の定義名は `publicInformation` とする。

投稿の repository path は `dev.oriom.mosimo.post/<record-key>`。record key の生成方式は別途定める。atURI の collection も同じ NSID を使う。

### 2.2 公開・解決

この NSID 群の authority は `mosimo.oriom.dev` であり、Lexicon の標準解決では `_lexicon.mosimo.oriom.dev` の DNS TXT を用いる構成となる。公開時はスキーマを提供する DID を指定し、そのリポジトリに `com.atproto.lexicon.schema` のレコードを公開する。サービス DID の選定・実際の公開は未実施。

オフラインの受信・中継・未知拡張の再構成に、その都度の DNS 照会や Lexicon 取得を要求しない。名前の authority と、災害時に接続が必要なサービスを同一視しない。

## 3. Nostr の識別子

### 3.1 プロトコル識別

合意済みの `t` タグに使う値を `mosimo.oriom.dev` とする。

```json
["t", "mosimo.oriom.dev"]
```

この値はプロトコルの発見用であり、公式著者・適合性・信頼の証明ではない。版の識別は `mosimo.oriom.dev:version` タグで別に行い、発見用の `t` 値は版番号として使わない。通常投稿・訂正・補足・追認はこの識別タグを含め、基本版は文字列 `"1"` とする。制御イベントへの適用は別途定める。

### 3.2 自己完結した拡張タグ

共通接頭辞を `mosimo.oriom.dev:ext:` とする。タグ名は、その接頭辞へ元オブジェクトの完全修飾 `$type` をそのまま連結した文字列とする。

```text
タグ名 = "mosimo.oriom.dev:ext:" + 元の$type
タグ値 = $type以外の全フィールドのatproto CBORを、通常のBase64・paddingなしで表した文字列
```

例えば影響範囲のタグ名は、次のとおり。

```text
mosimo.oriom.dev:ext:dev.oriom.mosimo.defs#area
```

`$type` を URL として変換・percent-encode・大文字小文字変換しない。接頭辞を除いた残り全体が型参照であり、`#` はそのまま定義名を示す文字として保持する。タグ一覧内の出現順を元の拡張配列順とし、同じ型名の繰り返しも保持する。

基本プロトコル・各拡張型・メッシュ符号化形式は独立して版管理する（[0010 §3.3](0010-core.md#33-三層の独立した版管理合意済み)）。現在のタグ構文を互換性なく変更して同じ基本版として再利用しない。拡張型の非互換な変更には新しい `$type` を使い、未知の拡張追加だけでは基本版・メッシュ符号化版を上げない。初期基本版は `1` とする。メッシュ符号化版の初期値は整数 `1` とし、[0012 §4](0012-bindings.md#4-メッシュ用-cbor合意済み) の固定7要素配列の先頭に置く。制御イベントの表現・新しい型名の命名規則は未決定である。

### 3.3 基本項目のタグ名

基本仕様の独自タグにも `mosimo.oriom.dev:` を接頭辞として使う。通常投稿の値・個数・初回状態の構文は [0012 §3.2.4](0012-bindings.md#324-通常投稿の基本タグ合意済み) に従う。制御イベントへの利用は別途定める。

| タグ名 | 用途 |
|---|---|
| `mosimo.oriom.dev:commit` | 端末署名済み atproto commit |
| `mosimo.oriom.dev:base` | 適用元 commit CID。適用元なしの初回は空文字列。タグ自体は含める |
| `mosimo.oriom.dev:path` | repository path |
| `mosimo.oriom.dev:action` | 操作用に名前を割り当て。通常投稿は新規追加なので不要。制御形式での利用は未決定 |
| `mosimo.oriom.dev:record-type` | 型用に名前を割り当て。通常投稿は path の collection から得るため不要 |
| `mosimo.oriom.dev:created-at` | 元レコードの正確な作成時刻 |
| `mosimo.oriom.dev:delivery-expires-at` | メッシュの配送期限 |
| `mosimo.oriom.dev:version` | 通常投稿では初期基本版の文字列 `"1"` を1個含める。拡張型・メッシュ符号化版とは独立 |

Nostr 標準フィールドや、意味が正確に一致する既存標準タグを、これらの名前へ一律に置換するものではない。検索のためだけの重複タグは増やさない。追加の基本項目が必要になった場合は同じ管理範囲で名前を割り当てる。

## 4. 第三者の自由な拡張

公式の型を `dev.oriom.mosimo` に置くことは、第三者の拡張をその配下へ登録させるという意味ではない。第三者は、自身が管理する衝突しない Lexicon 名前空間で定義できる。

自己完結タグの共通接頭辞は、第三者の `$type` にも同じように使える。PDS・中継者は共通の運び方として識別し、内側の型が公式の接頭辞に一致することを受理・再構成の条件にしない。公式型の一覧への追加や、公式運営者の承認も必要としない。

未知の型の意味の解釈・表示と、その型のデータの保持・再構成は別である。資源上限・不正形式等への既存の制約は引き続き適用する。

## 5. サービス用ドメインの扱い

論理 PDS、物理 PDS、Nostr リレー、AppView、文書サイト等にドメインが必要になった場合は、`mosimo.oriom.dev` 以下で役割に応じて割り当てられる。ただし、この時点で未設計の endpoint やサーバー構成を作るための仮ホスト名は増やさない。

公式識別子に共通ドメインを使っても、全発信者の正規 PDS を一つに固定したり、第三者運用者のサービスへ同じドメインを要求したりするものではない。

## 6. 参照

- [atproto Lexicon — Authority and Control・Publication and Resolution](https://atproto.com/specs/lexicon)
- [atproto NSID](https://atproto.com/specs/nsid)
- [0011: 著者署名付き拡張ブロック](0011-extensions.md)
- [0012: atproto・Nostr・CBOR のデータ表現](0012-bindings.md)
