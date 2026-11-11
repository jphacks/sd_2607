# Mosimo Protocol ウェブサイト

Astro で構築し、GitHub Pages または Cloudflare Workers にデプロイできます。Node.js 24 を使用してください。

```sh
npm ci
npm run dev
npm run check
```

## プロトコル仕様ページ

`/protocol/` 以下は `../protocol/mosimo/**/README.md` をビルド時に `import.meta.glob` で直接読み込んで生成します（`src/lib/specs.ts`）。仕様の本文はリポジトリの README が唯一のソースであり、`hp/` 側には複製を置きません。

- `src/lib/remark-protocol.mjs` が、先頭の見出しと「識別子:」行、「未確定事項」の節を除き、mermaid を SVG に変換し（`beautiful-mermaid`）、README 間の相対リンクをサイト内 URL に書き換えます。
- 各ページの要約・要点・前提関係・分類は `src/data/protocols.ts` に置きます。仕様を追加したらここにも項目を加えてください。
- Lexicon JSON（`lexicon.json`、`post.json`、`getFeed.json`）は `src/pages/protocol/[...path].json.ts` が同じパスでそのまま配信します。
- 用語集 `/protocol/glossary/` はリポジトリ直下の `CONTEXT.md` から生成します。

Cloudflare Workers 向けの `npm run build` は、仕様ページを事前生成するため workerd を起動します。NixOS では npm が配る workerd がそのままでは動かないため、`patchelf` で interpreter を書き換えた複製を用意し、`MINIFLARE_WORKERD_PATH` で指定してください。GitHub Pages 向けの `npm run build:pages` と `npm run check` には不要です。

## GitHub Pages

`npm run build:pages` で `dist/` に静的サイトを生成します。`.github/workflows/hp.yml` は `main` へのプッシュ時に `hp/` またはワークフローに変更があれば、サイトをビルドして GitHub Pages にデプロイします。手動実行もできます。

初回公開にはリポジトリ管理者による設定が必要です。

1. リポジトリの **Settings → Pages → Build and deployment → Source** で **GitHub Actions** を選択します。
2. Pages のカスタムドメインに `mosimo.jisaku.dev` を設定します。DNS 管理画面で `mosimo.jisaku.dev` の CNAME を `jphacks.github.io` に向け、必要に応じて GitHub のドメイン所有権確認を行います。既存の DNS レコードを変更する前に、現在の利用状況を確認してください。
3. デプロイと DNS の反映後、Pages 設定で HTTPS を有効にし、公開 URL を確認します。

このビルドはカスタムドメインのルートでの公開を前提としています。`jphacks.github.io/sd_2607/` のようなサブパスでは、リンクや画像のパスを変更しないと正しく動作しません。

GitHub Pages では `/reference/mobile/android/policy` は HTTP 301 ではなく、HTML の meta refresh によって `/reference/mobile/policy` に転送されます。

## Cloudflare Workers

Cloudflare に切り替える場合は、アカウントへの認証後に次を実行します。

```sh
npm run build
npx wrangler deploy
```

Workers Builds で Git リポジトリを連携する場合は、ルートディレクトリを `hp`、ビルドコマンドを `npm run build`、デプロイコマンドを `npx wrangler deploy` に設定します。Workers Builds は Wrangler のカスタムビルド設定を使用しません。

Cloudflare Workers のカスタムドメインとして `mosimo.jisaku.dev` を設定し、DNS を切り替えてください。切り替え後は GitHub Pages の自動デプロイを無効化し、Pages のカスタムドメイン設定と不要になった DNS レコードを整理してください。Workers ではポリシーの旧 URL から HTTP 301 を返します。
