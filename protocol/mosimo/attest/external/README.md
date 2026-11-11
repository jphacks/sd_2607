# 外部アカウント証明

識別子: `dev.jisaku.mosimo.attest.external`

## 目的

Nostr の NIP-39 は、鍵の所有者が GitHub 等の外部アカウントを所持していることを、プロフィールの `i` タグと外部サービス上の証明で主張する方式である。本拡張ではその検証を認証局が代行し、結果を鍵署名のブロックとして発行する。受信者は外部サービスへ接続せずに「この鍵は、この認証局が検証した時点で、このアカウントを所持していた」と確認できる。実名・本人性・内容の真実性は証明の対象ではない。

## 前提

| 識別子 | 役割 |
|---|---|
| [`dev.jisaku.mosimo.attest`](../README.md) | 鍵署名の構造と信頼度 |
| [`dev.jisaku.mosimo.attest.signing`](../signing/README.md) | 任意。要求の経路として用いる場合 |

## 1. 構造

```ts
type ExternalAccount = {
  platform: Text;        // NIP-39 の platform: "github" | "twitter" | "mastodon" | "telegram" 等
  identity: Text;        // NIP-39 の identity
  proof: Text;           // NIP-39 の proof
  verifiedAt: Timestamp;
};
type ExternalAccountsBlock = {
  $type: "dev.jisaku.mosimo.attest.external";
  accounts: ExternalAccount[];   // 1..8、(platform, identity) で重複なし
};
```

同じ `$type` のブロックは一つに限るため、複数のアカウントは一つのブロックの配列で表す。

## 2. 発行

| 項目 | 内容 |
|---|---|
| 要求 | `subject` は 32 バイト鍵。署名要求を使う場合、`blocks` に検証を求める本ブロックを含める |
| 検証 | 認証局は `subject` の `kind 0` の `i` タグ、または要求内の主張について、NIP-39 が platform ごとに定める証明を取得し、証明が `subject` の npub を含むことを確認する |
| 発行 | `owner.id = "nip39:" + platform + ":" + identity`（複数アカウントでは先頭）、`owner.name` は要求値または `identity`、`roles = ["identity"]`、`delegation` と `organization` なし、本ブロック、`trustLevel { level: 1, method: "nip39" }`、`keyValidity.until` は認証局の方針 |

検証できたアカウントだけを `accounts` に含める。一つも検証できない場合は発行してはならない。

## 3. 受信者の表示

`identity` 役割で表示する名称は `owner.name` であるが、受信者は `accounts` の platform と identity を併せて示し、「GitHub の `identity` を所持する発信者」のように外部アカウントの所持として表現する。組織の公式発信として表示してはならない。本拡張は `organization` を持たないためである。

## 4. 限界

- 外部アカウントの乗っ取り・譲渡・改名は認証局の検証後に起こり得る。認証局は期限を短く設定し、更新時に再検証する。
- 証明の取得先となる外部サービスの可用性・API 変更に依存する。取得できない場合は発行を保留する。

## 参照

- [NIP-39: External Identities in Profiles](https://github.com/nostr-protocol/nips/blob/master/39.md)
