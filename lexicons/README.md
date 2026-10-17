# Mosimo Lexicons

通信層に依存しない atproto の Lexicon JSON 定義。仕様は [基本プロトコル](../protocol/0010-core.md)・[データ表現](../protocol/0012-bindings.md) に従う。

## 実装済み

| NSID | ファイル | 内容 |
|---|---|---|
| `dev.oriom.mosimo.post` | [post.json](dev/oriom/mosimo/post.json) | TID キーの投稿レコード。本文・作成時刻・配送期限・拡張一覧が必須 |

レコードには標準どおり `$type: "dev.oriom.mosimo.post"` を付ける。`$type` はレコードの型識別子であり、Lexicon の `properties` に独自フィールドとして重複定義しない。

`extensions` は `$type` 必須の open union。現在は `refs: []`・`closed: false` とし、外部の拡張スキーマを取得せずに未知の拡張を保持できる。これは [Lexicon 標準で許容される形式](https://atproto.com/specs/lexicon#union) であり、任意の JSON 値を許す配列ではない。

`dev.oriom.mosimo.defs` 以下の個別拡張は、フィールド・必須条件等がまだ型案のため実装していない。空の定義ファイルや、仮のスキーマで型を固定することはしない。名前の割り当ては [0014](../protocol/0014-namespaces.md) を参照する。

## 使用例

```json
{
  "$type": "dev.oriom.mosimo.post",
  "text": "避難所Aは開設済みです。",
  "createdAt": "2026-10-17T12:34:56.120000Z",
  "deliveryExpiresAt": "2026-10-18T12:34:56Z",
  "extensions": []
}
```

このオブジェクトを TID キーで `dev.oriom.mosimo.post` collection に格納する。commit、署名、Nostr タグ、BPv7 はこのレコードのフィールドに追加しない。レコードの直接保存だけで、本プロトコルの端末署名済み commit・Nostr イベントの生成まで実装されるわけではない。

## Lexicon だけでは表せない制約

- Lexicon の `datetime` は時差オフセット付き文字列も認める。本プロトコルの UTC・大文字 `Z` 終端の制約は、発信・変換処理で別途適用する。日時を `Date` 等へ変換して再出力せず、元の文字列を保持する。なお、固定した `@atproto/lexicon@0.7.17` は、標準では無効なタイムゾーンなしの `2026-10-17T12:34:56` も受理することを確認した。このライブラリの受理だけで日時の標準適合・本プロトコル適合を保証しない。
- Lexicon は未知フィールドを許容する。本プロトコル基本版1の「トップレベルは `$type` と上記4項目のみ」という制約は別途適用する。未知フィールドを削って同じ元レコードとして扱わない。
- open union の受理は、拡張の意味・個別スキーマ・内部の atproto データモデルへの適合をすべて確認済みという意味ではない。未知データを含めたデータモデル適合と資源制限は別途必要。
- メッシュの32 KiB上限は、commit・Nostr 署名等を含む CBOR payload 全体の上限。本文の `maxLength` やレコードの上限に置き換えない。未確定の本文長・拡張数等をスキーマに勝手に固定しない。
- レコードキーは `key: "tid"` で宣言するが、レコード本体の検査だけでは path 上のキーの確認や単調増加生成まで行われない。

## 開発時の確認

Node.js 22以降を使い、リポジトリのルートで実行する。

```sh
npm --prefix lexicons ci
npm --prefix lexicons test
```

公式 `@atproto/lexicon` によるスキーマ読み込みとレコードの受理・拒否、未知拡張・文字列表記・配列順の保持を確認する。依存は開発用のみで、Lexicon JSON 自体は JavaScript や Nearby SDK に依存しない。生成 SDK や独自の実行時バリデーターはまだ追加していない。

DNS 設定、Lexicon の PDS への公開、npm パッケージの公開は行っていない。
