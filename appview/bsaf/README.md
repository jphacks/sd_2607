# 登録済み発信元のBSAF投稿をHappyViewへ収集

[0019 §5.1](../../protocol/0019-bsaf.md)の既存BSAF収集に対応する。
収集と署名付き再配信は別プロセス・別opt-in。collectorだけではMosimoへの変換・投稿はしない。
任意のpublisherは[0020 wire-v1](../../protocol/0020-wire-v1.md)に従い、既存collector DBからPDSへNostr `EVENT`をpushする。

HappyView 2.16.0の既存SQLite DBを使うNode.js 24の型付きサイドカー。
HappyView本体をforkせず、投稿レコードとquery scriptの既存保存形式へ接続する。
collectorには独立したDB・HTTPサーバー・著者秘密鍵・管理APIキーは不要。
publisherだけが独立したimporter鍵を必要とする。root秘密鍵をオンラインサービスへ置かない。

## 登録と有効化

既存のHappyViewモジュールに加えて、このディレクトリをNixOSでimportする。

```nix
imports = [ (inputs.mosimo + "/appview/bsaf") ];
services.mosimo-bsaf = {
  enable = true;
  # 承認した原BotのDIDを明示する。ハンドル・sourceタグでは代用しない。
  authors = [ ];
};
```

`authors = []`が初期値。BSAFの接続・収集プロセスは起動しない。
準備処理だけを実行し、空の許可リストと統合feedを配置する。
運用者がDIDを追加してrebuildすると、そのDIDを指定したJetstream購読を開始する。
登録変更時には収集プロセスも再起動する。HappyViewのrestartにも追従する。

- DB既定値: `/var/lib/containers/storage/volumes/happyview-data/_data/happyview.db`。
- 購読先: `wss://jetstream1.us-east.bsky.network/subscribe`。`jetstreamUrl`で変更可能。
- `wantedCollections=app.bsky.feed.post`と`wantedDids`を両方指定する。
  全Bluesky投稿をHappyView本体へ購読させず、既存Mosimo収集のDIDも制限しない。
- **HappyView側へ`app.bsky.feed.post`をrecord lexiconとして別途登録しない。**
  ネイティブの全投稿取り込みとの競合を検出すると起動を中止する。
- 既存の`dev.oriom.mosimo.getFeed`（target `dev.oriom.mosimo.post`）が必要。
  独自query scriptがある場合は勝手に上書きせず準備処理を失敗させる。
  導入後の管理対象scriptを手動編集した場合も同様。

これは収集元のローカル許可リストであり、公的機関認証やネットワーク全体のBot登録ではない。
収集の許可は、0019 §5.2の「Bot DID＋情報種別」の自動再配信許可を意味しない。

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

## feed API

従来の`dev.oriom.mosimo.getFeed`でMosimoと収集済みBSAFを取得する。
`limit`（1–100）、offset方式の`cursor`、`did`、`uri`を維持する。
一覧の`records`と単件の`record`は従来の平坦な原レコード＋`uri`形式。
BSAFは`$type: app.bsky.feed.post`のままで、Mosimoへ偽装しない。
クライアントは`$type`で分岐し、BSAFに`deliveryExpiresAt`や`extensions`を要求しない。

追加の`provenance`はURIをキーにDID・CID・collection・索引時刻を返す。
BSAFには取得先と取得時刻も含む。原レコードのフィールドへ出典メタデータを混ぜない。
Bot定義の取得、構造化フィルタ、地域の推測、Bot間の重複折りたたみは実装しない。

## 運用境界

- **新規登録時の過去投稿backfillは行わない。** 初回はライブから、再接続は独自cursorから再開する。
  新しいBotを登録しても過去の全投稿が自動取得されるわけではない。
  Jetstreamの履歴保持期間を超える停止も完全回収を保証しない。
- cursor更新と索引更新は同一SQLite transaction。HappyView自身のcursorには触れない。
  1件ずつ処理し、WebSocketのread backpressure、1 MiB/message上限を設ける。
- Node old-space 64 MiB、systemd MemoryHigh 96 MiB/MemoryMax 128 MiB。
  空リストなら常駐プロセスなし。これは資源保護であり、負荷試験の実測保証ではない。
- rootful HappyView volumeに合わせてrootで動くが、書込範囲はそのvolumeのみ、capabilityは空。
  DBはローカルSQLite専用。先に整合性のあるバックアップを取り、HappyView更新時は隔離試験を実行する。
- HappyViewの汎用record hooks/labeler/ref索引処理はこの収集経路では実行しない。
  collector自体は取り込み後に投稿しない。Luaのfail-open record hookを認可境界に使わない。
- 既存query scriptの単件不在はLuaエラーになるため、標準処理の404ではなく非200のscriptエラーを返す。

## 検証

```sh
npm ci
npm test
npm run typecheck
```

実HappyView 2.16イメージを持つLinuxのローカルPodmanで:

```sh
HAPPYVIEW_IMAGE=localhost/your-existing-2.16-image node --test integration.test.mts
```

実行先は新規の一時DB・外部へ経路のない隔離コンテナのみ。
許可/拒否、CID、タグ・言語・添付参照、更新/削除、許可取消、統合一覧・ページング、
原レコード保持、独自scriptの上書き拒否を検証し、終了時に削除する。
本番DB・実Botの秘密鍵や投稿権限は使用しない。

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
