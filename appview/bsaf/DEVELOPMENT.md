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

## 受理と保持

- Jetstreamイベントの**envelope DID**が許可されていることを確認する。
  本文中の`original`・`source:`・表示名・ハンドルで許可を引き継がない。
- 原collection/`$type`が`app.bsky.feed.post`、原`tags`に完全一致の`bsaf:v1`が必要。
  通常のBluesky投稿は許可済みBotでも保存しない。
- Lex JSONのlink/bytesをCBORへ変換して計算したCIDと、受信CIDを照合する。
  変換用コピーは保存しない。DBには原レコード・原CID・原URIを保持する。
- 5つのコアタグの欠落・未知の値・重複を修復しない。
  `bsaf:v1`を含む不完全な原投稿も未解釈のまま保持し、BSAF変換成功とは表示しない。
  タグ順、言語、未知フィールド、facets、添付参照を残す。blob本体は取得しない。
- 取得先・取得時刻・イベント時刻は`mosimo_bsaf_events`へ別保存する。
  `happyview_records.created_at`は原record.createdAtではなく初回ローカル保存時刻。
- 更新でタグが消えれば索引から削除し、deleteイベントにも従う。
  URI単位の時刻・tombstoneで古い再送による復活を防ぐ。
- 許可DIDを削除した場合、管理対象の当該BSAF索引も同一transactionで削除する。
  元PDS・Mosimo投稿・署名付き転載を削除する操作ではない。

購読は設定したJetstreamを信頼するモデル。CID照合は内容の整合性確認であり、
原Botのrepo署名/MST包含や機関の認証を独立検証したという表示はしない。


### 2026-10-18の実施結果

- 収集・永続化・再接続の15テスト、TypeScript型チェック、Nixモジュール検証が成功。
- VPS上の実HappyView 2.16イメージを使った一時DBの結合試験が成功。
  最終版のqueryはMosimoとBSAFを順序付きで統合し、許可取消・削除後の除外も確認した。
- query planは両collectionの索引を`MERGE (UNION ALL)`で結合し、全履歴のsortを避ける。
  50,000件の合成DBで、21件取得の中央値約0.19 msを観測した（メモリ内SQLite、VPS負荷試験ではない）。
- 実装commit `61e1247`を本番へ反映。
  system closure: `/nix/store/h4gcjcavlzfa2i7xv38ic4zwqrwmhf71-nixos-system-kagoya-vps-26.05.20260409.4c1018d`。
- 反映前の整合性あるSQLiteバックアップをVPS内のroot専用ディレクトリへ保存。
- 本番で`mosimo-bsaf-prepare`がsuccess、`approvedAuthors:0`を確認。
  準備処理のMemoryPeakは30,146,560 bytes。空リストのためcollector unitは作成されていない。
- 公開getFeedで既存テスト著者の9件・原投稿URI・CID provenance・ページングを再確認した。
  HappyView/PDSの両サービスはactive。
- **実Botの許可DIDは未指定。実BotのライブBSAF受信や過去投稿の取得は未検証・未有効化。**
