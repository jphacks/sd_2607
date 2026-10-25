# Webクライアントの開発・配信

## 配信設定

NixOS構成にはリポジトリの `nixos/` をimportし、`services.mosimo-web` の `enable`、`domain`、`appviewUrl`、`clientKey` を設定します。Caddyで `index.html` と生成された公開設定を配信し、HTTPS証明書を管理します。

## 接続先の変更

URLのクエリパラメーターで接続先を変更できます。

| パラメーター | 用途 |
|---|---|
| `appview` | 接続するAppViewのオリジン |
| `client_key` | 接続先で利用する公開クライアントキー |
| `did` | 発信者DIDによる絞り込み |
| `uri` | 投稿のatURIによる絞り込み |

接続先には[フィードAPI](../../lexicons/DEVELOPMENT.md#happyviewへの登録)を登録し、ブラウザーからアクセスできるCORS設定を確認してください。クライアントキーは秘密情報や認証の代わりにはなりません。

## テスト

リポジトリ直下で実行します。

```sh
node --test apps/web/web.test.mjs
```
