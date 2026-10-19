# Lexiconの登録・開発・検証

## HappyViewへの登録

1. **Lexicons → Add Lexicon → Local** から `getFeed.json` を登録します。
2. **Target Collection** を `dev.oriom.mosimo.post` に設定します。これはHappyView側の設定であり、Lexicon JSONには含めません。
3. Mosimoのみの構成ではLuaスクリプトを付けず、標準query処理を使います。BSAFを併置する場合は [BSAF収集の登録手順](../appview/bsaf/DEVELOPMENT.md)に従います。

クライアントキーを使う構成では、**Settings → API Clients** で発行します。APIの詳細は [HappyView Quickstart](https://happyview.dev/getting-started/quickstart) と [XRPC API](https://happyview.dev/api-reference/xrpc-api) を参照してください。

## レコードの扱い

投稿は `dev.oriom.mosimo.post` collectionにTIDキーで格納します。commit・署名・Nostrタグ・BPv7の配送状態をレコードのフィールドに追加しません。

Lexiconの検査とは別に、次のプロトコル制約を適用してください。

- 時刻はUTC・大文字 `Z` 終端とし、署名対象の元文字列を保持します。検証ライブラリの受理だけでプロトコルへの適合を判断しません。
- 基本版1のトップレベルは `$type`・`text`・`createdAt`・`deliveryExpiresAt`・`extensions` のみです。未知フィールドを削って元レコードとして扱わないでください。
- 未知拡張もatprotoデータモデルへの適合と資源制限の検査が必要です。open unionによる受理は拡張の意味の検証ではありません。
- メッシュのサイズ制限は署名などを含む配送用payload全体に適用します。本文長だけの制限で代用しません。
- レコード本体の検査とは別に、保存先のキーがTIDであることを確認します。

詳しい配置と署名の規則は [データ表現仕様](../protocol/0012-bindings.md)を参照してください。

## テスト

Node.js 22以降で、リポジトリ直下から実行します。

```sh
npm --prefix lexicons ci
npm --prefix lexicons test
```

スキーマの読み込み、レコードの受理・拒否、未知拡張・文字列表記・配列順の保持を確認します。
