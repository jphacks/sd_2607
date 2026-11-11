# Lexicon 検証ツール

Mosimo の Lexicon 定義は、各プロトコルの文書と同じディレクトリに置く（[プロトコル仕様](../protocol/README.md)）。このディレクトリは、それらの定義を AT Protocol の Lexicon 言語として検証するためのツールだけを持つ。

| 定義 | 場所 |
|---|---|
| 投稿レコード `dev.jisaku.mosimo.post` | [protocol/mosimo/post.json](../protocol/mosimo/post.json) |
| フィード取得 API `dev.jisaku.mosimo.getFeed` | [protocol/mosimo/getFeed.json](../protocol/mosimo/getFeed.json) |
| 各拡張の型 | 各プロトコルのディレクトリの `lexicon.json` |

## テスト

Node.js 22 以降で、リポジトリ直下から実行する。

```sh
npm --prefix lexicons ci
npm --prefix lexicons test
```

投稿レコードのスキーマの読み込み、レコードの受理・拒否、未知拡張・文字列表記・配列順の保持を確認する。登録・運用上の注意は [DEVELOPMENT.md](DEVELOPMENT.md) に従う。
