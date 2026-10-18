# BSAF収集・再配信の登録・運用・検証

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

## 署名付き再配信の個別有効化

collectorは既存BSAF原投稿の収集・表示を担当し、投稿の変換・再配信は行いません。別プロセスのpublisherが、同じHappyViewのSQLite DBから選択した原投稿を読み、[wire-v1共有実装](../../pds/public-information.mjs)に基づく署名付きNostr `EVENT` を設定先PDSへ送ります。BSAF転載イベントはNostr経路のみで扱い、atprotoのrepo・firehoseへは公開しません。

収集元の設定例は次のとおりです。この例はデプロイ結果を示すものではありません。

```nix
services.mosimo-bsaf.authors = [
  "did:plc:vwi7zhfyamyvrg5v6ycjgzxi" # @jma-alert-bot.bsky.social
  "did:plc:kxwz5cz7o6g4jlmxh6doyfsm" # @bsaf-kikikuru-bot.bsky.social
];
```

`authors` は収集元のローカル許可リストです。公的機関の認証や、Botの全情報種別の再配信許可を意味しません。publisherは既定で無効であり、再配信には以下を別途設定します。

- `publisher.enable = true` と、再配信先の `relayUrl`、importerの `credentialsFile`、公開root設定の `trustFile` を指定します。配信先や信頼の起点は自動選択されません。
- 再配信対象はBot DIDと情報種別の組で選びます。`publisher.selectJmaDefaults` は地震・津波・火山の固定5種、`publisher.selectKikikuruDefaults` は気象の固定23種を選択します。両方を明示選択すると28組になり、いずれも新種別を自動追加しません。
- 独自の `publisher.selection` は固定プリセットと排他です。再配信対象のBot DIDは `authors` にも登録します。受信側でも信頼するrootとBot・情報種別を個別に選択する必要があります。
- 気象の優先度ヒントは既定1です。JMA XMLの復元、地域・警戒レベルの推測は行いません。優先度の上昇には受信側での独立した認可が必要です。

collectorには独立したDB・HTTPサーバー・著者秘密鍵・管理APIキーは不要です。publisherは独立したimporter署名鍵を使い、`LoadCredential` でimporter用の資格情報だけを読み込みます。root秘密鍵とPLC管理用の秘密鍵はオンラインサービスへ置きません。

固定種別の一覧、完全な設定例、鍵・証明書の発行、送信失敗時の扱いは [PUBLISHER.md](PUBLISHER.md) を参照してください。ローカル生成物と未実施のデプロイ手順は [DEPLOYMENT-CHECKLIST.md](DEPLOYMENT-CHECKLIST.md) に記録しています。

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
npm ci --prefix ../../pds
npm test
npm run typecheck
```

`npm test` はcollectorとpublisherのテストを実行します。publisherが共有する暗号・リポジトリ処理の依存解決には、隣接する `pds` のlockfileに基づく依存ツリーも必要です。publisher固有の試験条件は [ローカル検証手順](PUBLISHER.md#local-verification) を参照してください。

HappyView 2.16のイメージがあるLinuxのローカルPodmanでは、`HAPPYVIEW_IMAGE` に使用するイメージ名を指定して `node --test integration.test.mts` を実行します。

結合試験は一時DBと隔離コンテナーで行います。本番DBや実Botの秘密鍵・投稿権限は使用しないでください。
