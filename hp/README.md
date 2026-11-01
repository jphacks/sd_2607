# Mosimo Protocol ウェブサイト

Astro と Cloudflare Workers で構築しています。

```sh
npm ci
npm run dev
```

`npm run check` で型チェック、`npm run build` で本番用ビルドを行います。デプロイには Cloudflare アカウントへの認証後、次を実行します。

```sh
npm run build
npx wrangler deploy
```

公開先の `mosimo.jisaku.dev` は Cloudflare Workers のカスタムドメインとして設定してください。
