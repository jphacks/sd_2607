# 0028: 外部アカウント証明プロファイル

状態: draft-01 — [0022](0022-key-attestation.md) の拡張。NIP-39 の外部アカウント主張を検証した認証局が、個人の鍵に外部アカウントの所持を証明する鍵署名を発行するプロファイル。要求の経路に [0027](0027-signing-request.md) を使えるが、必須ではない。

## 要旨

Nostr の NIP-39 は、鍵の所有者が GitHub 等の外部アカウントを所持していることを、プロフィールの `i` タグと外部サービス上の証明で主張する方式である。本書はその検証を認証局が代行し、結果を鍵署名として発行する。受信者は外部サービスへ接続せずに「この鍵は、この認証局が検証した時点で、このアカウントを所持していた」と確認できる。実名・本人性・内容の真実性は証明しない。

## 1. 構造

```ts
type ExternalAccount = {
  platform: Text;        // NIP-39 の platform: "github" | "twitter" | "mastodon" | "telegram" 等
  identity: Text;        // NIP-39 の identity
  proof: Text;           // NIP-39 の proof
  verifiedAt: Timestamp;
};
type ExternalAccountsBlock = {
  $type: "dev.oriom.mosimo.attest#externalAccounts";
  accounts: ExternalAccount[];   // 1..8、(platform, identity) で重複なし
};
```

同じ `$type` のブロックは一つに限るため（[0022 §3](0022-key-attestation.md#3-鍵署名の構造)）、複数のアカウントは一つのブロックの配列で表す。

## 2. 発行

| 項目 | 内容 |
|---|---|
| 要求 | `subject` は 32 バイト鍵。0027 を使う場合、`blocks` に検証を求める `externalAccounts` を含める |
| 検証 | 認証局は `subject` の NIP-01 `kind 0` の `i` タグ、または要求内の主張について、NIP-39 が platform ごとに定める証明を取得し、証明が `subject` の npub を含むことを確認する |
| 発行 | `owner.id = "nip39:" + platform + ":" + identity`（複数アカウントでは先頭）、`owner.name` は要求値または `identity`、`roles = ["identity"]`、`delegation` なし、`organization` なし、`externalAccounts`、`trustLevel { level: 1, method: "nip39" }`、`keyValidity.notAfter` は認証局の方針 |

検証できたアカウントだけを `accounts` に含める。一つも検証できなければ発行しない。

## 3. 受信者の表示

`identity` の用途で表示する名称は `owner.name` であるが、受信者は `externalAccounts` の platform と identity を併せて示し、「GitHub の `identity` を所持する発信者」のように外部アカウントの所持として表現する。実名や組織の公式発信として表示しない。

## 4. 限界

- 外部アカウントの乗っ取り・譲渡・改名は認証局の検証後に起こり得る。認証局は期限を短く設定し、更新時に再検証する。
- 証明の取得先となる外部サービスの可用性・API 変更に依存する。取得できない場合は発行を保留する。
- `public-information` の権限や再委任を与えない。

## 5. 参照

- [0022: 鍵署名](0022-key-attestation.md)
- [0027: 署名要求と自動発行](0027-signing-request.md)
- [NIP-39: External Identities in Profiles](https://github.com/nostr-protocol/nips/blob/master/39.md)
