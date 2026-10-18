# BSAF収集の登録・運用・検証

## 登録と有効化

HappyViewと[フィードAPI](../../lexicons/DEVELOPMENT.md#happyviewへの登録)を準備し、NixOS構成にこのディレクトリをimportします。

```nix
imports = [ (inputs.mosimo + "/appview/bsaf") ];
services.mosimo-bsaf = {
  enable = true;
  authors = [ ];
};
```

`authors` に収集を許可するBotのDIDを設定してrebuildします。空のリストでは準備処理のみを行い、収集プロセスは起動しません。表示名・ハンドル・投稿本文中の出典は許可の根拠にしません。

必要に応じて `services.mosimo-bsaf.databasePath` で既存のHappyView DBを、`jetstreamUrl` で信頼する購読先を指定します。既定値は [`default.nix`](default.nix) を参照してください。

## 導入・更新時の注意

- ローカルSQLite専用です。導入・更新前に整合性のあるバックアップを取得してください。
- HappyView側へ `app.bsky.feed.post` をrecord lexiconとして別途登録しないでください。全投稿取り込みとの競合を検出すると起動を中止します。
- 既存フィードに独自のquery scriptがある場合、準備処理は上書きせず失敗します。導入後の管理対象scriptを手動編集した場合も同様です。
- HappyView更新時には、DBとの互換性を隔離環境で試験してください。この収集経路ではHappyViewの汎用record hooks・labeler処理を実行しません。

## フィードAPIとの接続

`dev.oriom.mosimo.getFeed` の `limit`・`cursor`・`did`・`uri` を使います。一覧は `records`、単件は `record` を返し、原レコードに `uri` を付けた形式です。

- BSAFは `$type: "app.bsky.feed.post"` のまま返します。クライアントはMosimo投稿と型を分け、BSAFに `deliveryExpiresAt` や `extensions` を要求しないでください。
- `provenance` は投稿URIをキーとする別のメタデータです。CID・著者・索引時刻、BSAFの取得元・取得時刻を含みます。
- 単件指定で対象がない場合、現状は404ではなくquery scriptのエラー応答になります。

## テスト

このディレクトリで実行します。

```sh
npm ci
npm test
npm run typecheck
```

HappyView 2.16のイメージがあるLinuxのローカルPodmanでは、`HAPPYVIEW_IMAGE` に使用するイメージ名を指定して `node --test integration.test.mts` を実行します。

結合試験は一時DBと隔離コンテナーで行います。本番DBや実Botの秘密鍵・投稿権限は使用しないでください。
