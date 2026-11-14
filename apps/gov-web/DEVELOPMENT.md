# 投稿確認ダッシュボードの開発

React、Vite、TypeScriptで実装しています。UIには[デジタル庁デザインシステム](https://design.digital.go.jp/)のTailwindテーマを、地図にはLeafletと国土地理院タイルを使います。

## 接続先

ビルド時の環境変数で指定します。`.env.example`を`.env.local`にコピーして値を設定してください。

| 変数 | 用途 |
|---|---|
| `VITE_APPVIEW_URL` | 接続するAppViewのオリジン（省略時は`https://appview.oriom.dev`） |
| `VITE_CLIENT_KEY` | HappyViewの公開クライアントキー（省略時はキーを付けずに問い合わせます） |
| `VITE_RELAY_URL` | 注釈の対象をNostrのevent IDから投稿へ対応付けるために問い合わせるMosimoのNostrリレー（省略時は`wss://pds.oriom.dev`） |

クライアントキーは、HappyViewの管理画面の **Settings → API Clients** で、Client typeを`public`として発行します。配信先のドメインと開発用の`http://localhost:5173`を、Allowed originsに登録してください。オリジンが一致しない場合に拒否するか警告だけにするかは、HappyViewのバージョンによって異なります（[API Clients](https://happyview.dev/guides/api-clients)参照）。

## 開発

このディレクトリで実行します。

```sh
npm install
npm run dev
```

## テスト

拡張の解析、geohashのデコード、有効状態の判定、訂正・補足・追認のまとめ方をテストします。

```sh
npm test
npm run check
```

## 配信

`npm run build`で`dist/`に静的ファイルを出力します。Cloudflare Workersへは`npx wrangler deploy`で配信できます（`wrangler.jsonc`参照）。

NixOSでは`default.nix`のモジュール（`services.mosimo-gov-web`）が、Nixのビルド時に`dist/`を作り、Caddyで配信します。接続先は`appviewUrl`・`clientKey`・`relayUrl`の設定値をビルド時に埋め込むため、`.env.local`は使いません。依存パッケージは`package-lock.json`の`integrity`から取得するので、ロックファイルを更新してもハッシュの更新は不要です。
