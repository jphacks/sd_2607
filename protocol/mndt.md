# MNDT draft-00
## Nostr Mesh Delay-Tolerant Transport Protocol

**状態:** 初期提案（参考資料・未確定）

> 本文は初期の配送プロファイル案を保存したものです。現在の全体像は [仕様群の索引](README.md) と [全体概要](0000-overview.md) を参照してください。本文のタグ、CDDL、EID、hop 規則、ブロック番号等はまだ採用・標準適合性を確認していません。特に atproto 復元情報、配送同値による重複抑制、削除・訂正、双方向配送は今後の改訂対象です。合意済み事項と矛盾する場合は [リポジトリ正本モデル](0001-repository-model.md) を優先します。

**対象:** Bluetooth、孤立 Wi-Fi 等の低速・断続ネットワークにおいて、署名済み Nostr event を BPv7 で配送し、Gateway から通常の Nostr Relay 網へ投入する仕組み。

## 1. 目的

MNDT は、次を実現する。

- 断続的なメッシュでの Nostr event の保存・転送
- group endpoint 宛の best-effort なブロードキャスト
- event 著者の Nostr 署名の維持
- CBOR によるメッシュ区間の小型化
- hop・期限・重複排除によるフラッディング抑止
- インターネット到達後の標準 Nostr Relay 互換
- 経路情報・受領証など、著者署名外の任意拡張

MNDT は、Nostr の代替ではない。

```text
Nostr:
  誰が何を投稿したかを署名で保証する。

BPv7:
  断続ネットワーク上で Bundle を保存・転送する。

MNDT:
  Nostr event を BPv7 で運ぶための application profile。
```

## 2. 用語

| 用語 | 意味 |
|---|---|
| **Nostr core** | `pubkey`、`created_at`、`kind`、`tags`、`content`、`sig` からなる署名済み event 内容 |
| **MNDT Payload** | CBOR で符号化した Nostr core |
| **Mesh group** | 同じ non-singleton BPv7 endpoint を購読・転送するノード群 |
| **Gateway** | MNDT Bundle を検証し、標準 Nostr Relay に event を投稿するノード |
| **転送者** | Bundle を受信し、次の接触相手へ転送するノード |
| **receipt** | 転送者が event を観測・転送したことを表す任意の署名付き記録 |

本文中の「必須」「推奨」は、それぞれ **MUST**、**SHOULD** に相当する。

## 3. プロトコル構成

```mermaid
flowchart TB
  A["Nostr Client<br/>event 作成・BIP-340 署名"] --> B["MNDT Payload<br/>CBOR compact encoding"]
  B --> C["BPv7 Bundle"]
  C --> D["Bluetooth / Wi-Fi<br/>Convergence-Layer Adapter"]
  D <--> E["断続的 Mesh"]

  C --> F["BPv7 extension blocks<br/>hop / age / previous node / optional metadata"]
  E --> G["MNDT Gateway"]
  G --> H["NIP-01 JSON event を復元・検証"]
  H --> I["Nostr Relay<br/>[\"EVENT\", event]"]
```

| 層 | 役割 |
|---|---|
| 下位リンク | Bluetooth、Wi-Fi Direct、孤立 Wi-Fi 等 |
| BPv7 | Bundle の保存・転送、Lifetime、断片化、標準拡張 block |
| MNDT | group 宛の複製方針、Nostr compact payload、Mesh 固有規約 |
| Nostr | 著者署名、Relay への公開・購読 |

Bluetooth / Wi-Fi 上の Convergence-Layer Adapter は本仕様の対象外とする。

## 4. Group endpoint

MNDT は BPv7 の non-singleton endpoint を Mesh group に用いる。

```text
dtn://<mesh-id>/~<group>
```

例:

```text
dtn://mesh.example/~all
dtn://mesh.example/~community/tokyo-west
dtn://mesh.example/~topic/disaster
dtn://mesh.example/~gateway
```

`~all` は「世界中の全ノード」を意味しない。次の範囲に限定される。

```text
当該 mesh deployment に参加し、
当該 endpoint を処理対象として登録し、
Bundle lifetime 中に接触可能なノード群
```

この endpoint は宛先 group を表すだけであり、BPv7 自体が複製方法や配送完了を規定するものではない。

## 5. Nostr 独自拡張

MNDT で配送する Nostr event には、次の tag を一つ含める。

```json
[
  "mndt",
  "1",
  "dtn://mesh.example/~community/tokyo-west",
  "6",
  "2"
]
```

| 位置 | 値 | 意味 |
|---:|---|---|
| 0 | `"mndt"` | MNDT profile 識別子 |
| 1 | `"1"` | profile version |
| 2 | group EID | 著者が許可する配送先 group |
| 3 | 最大 hop 数 | `1`〜`255` の十進整数文字列 |
| 4 | priority | `0`〜`3` の十進整数文字列。`3` が最高 |

例:

```json
{
  "kind": 1,
  "tags": [
    ["mndt", "1", "dtn://mesh.example/~topic/disaster", "8", "3"],
    ["mndt-exp", "1760000000"]
  ],
  "content": "避難所Aは開設済みです。"
}
```

`mndt-exp` は任意であり、値は Unix 時刻秒の十進整数文字列とする。

### 5.1 解釈規則

MNDT ノードは次を確認する。

```text
- mndt tag が一つだけ存在する
- version がサポート対象である
- tag の group EID と BPv7 Destination EID が一致する
- max_hops が 1..255 である
- priority が 0..3 である
- Nostr event ID と BIP-340 署名が正しい
```

`mndt` tag は Nostr event の署名対象である。したがって中継者は、配送先 group・最大 hop・priority を改竄できない。

ただし、ノードは常に独自の上限を優先する。

```text
実効 hop limit
= min(著者が署名した max_hops, ローカル運用上の最大値)
```

署名済み priority は、著者の希望を示すヒントである。受信ノードは、未信頼著者からの高 priority 投稿を低 priority として扱ってよい。

## 6. MNDT Payload

BPv7 Payload Block には、Nostr core を CBOR で圧縮した **MNDT Payload** を格納する。

```cddl
mndt-payload = [
  1,                      ; MNDT Payload version
  pubkey: bstr .size 32,
  created-at: uint,
  kind: uint,
  tags: [* [* tstr]],
  content: tstr,
  sig: bstr .size 64
]
```

- CBOR は RFC 8949 の deterministic encoding を用いる。
- `pubkey` は 32-byte の生バイト列とする。
- `sig` は 64-byte の生バイト列とする。
- `id` は payload に格納しない。
- `id` は NIP-01 の規定 JSON serialization から再計算する。
- `kind` は Nostr で許容される範囲内でなければならない。
- tags 中の文字列は Nostr の tag と同じ順序・内容を維持する。

MNDT Payload は、Nostr JSON を単純に CBOR 化するものではない。

```text
Nostr JSON:
  pubkey / id / sig は hex text

MNDT Payload:
  pubkey / sig は生バイト列
  id は再計算して省略
```

これにより、メッシュ区間のサイズを削減する。

## 7. BPv7 Bundle の構成

MNDT Bundle は少なくとも次を含む。

```text
Primary Bundle Block
Payload Block: MNDT Payload
Hop Count Block
Bundle Age Block
```

推奨構成:

| Block | 用途 |
|---|---|
| Primary Bundle Block | source、destination、creation time、lifetime |
| Payload Block | MNDT Payload |
| Hop Count Block | loop 防止、最大 hop の実施 |
| Bundle Age Block | 時計ずれ環境での経過時間評価 |
| Previous Node Block | 直前の転送者の記録 |
| Mesh Metadata Block | 任意の独自配送情報・receipt |

### 7.1 Hop Count Block

MNDT 送信者は Hop Count Block を含めなければならない。

```text
hop limit:
  mndt tag の max_hops 以下

hop count:
  初期値 0
  Bundle 転送ごとに 1 増加
```

転送者は、次の場合に Bundle を転送してはならない。

```text
hop_count >= effective_hop_limit
```

### 7.2 Lifetime と expiry

転送者は、以下のいずれかを満たす Bundle を破棄する。

```text
- BPv7 Bundle Lifetime が満了した
- mndt-exp が存在し、ローカル時計で満了した
- ローカル保持上限により低優先度 Bundle を追い出す必要がある
```

時計が不正確なノードは Bundle Age Block と Bundle Lifetime を優先してよい。

## 8. Mesh Metadata Block

MNDT は、閉じた deployment 内で BPv7 の private / experimental block type を一つ利用してよい。

```text
Block name: Mesh Metadata Block
Default block type: 192
```

この block type は private 用である。異なる独立プロトコルとの衝突を避ける必要がある公開相互運用では、正式な block type 登録を要する。

block-type-specific data は deterministic CBOR とする。

```cddl
mesh-metadata = {
  0: 1,                         ; version
  ? 1: [* forwarding-receipt],  ; 任意の receipt
  ? 2: any                      ; 将来拡張
}
```

未知の Mesh Metadata Block を処理できないノードは、原則として **block だけを破棄し、Payload Block を破棄してはならない**。

ただし、Mesh Metadata Block 内の情報は、著者が署名した Nostr policy の代替ではない。

```text
Mesh Metadata Block:
  可変・任意・中継者が削除可能

Nostr mndt tag:
  著者署名済み・不変
```

## 9. 転送規則

MNDT の group 宛配送は、bounded anti-entropy gossip を用いる。

接触した二つのノードは、対象 group の event inventory を交換する。

```text
1. HELLO
2. INVENTORY
3. WANT
4. Bundle transfer
5. 任意の受領確認
```

inventory は Nostr event ID を 32-byte CBOR byte string として表現する。

転送候補は次を全て満たす event とする。

```text
- peer が event ID を未所持である
- peer が destination group を処理対象とする
- event が期限切れでない
- effective_hop_limit を超えていない
- ローカル保存量・送信量の上限内である
```

候補は原則として次の順に送る。

```text
1. priority が高い
2. 残り寿命が短い
3. サイズが小さい
4. Gateway 到達性が高い peer に適する
```

priority は配送アルゴリズムを切り替える値ではない。

```text
priority:
  同じ配送規則における送信順・保存順位・帯域配分
```

低 priority event は、保存枠不足時に先に破棄してよい。

### 9.1 PRoPHET の扱い

PRoPHET は group 全体への配送確率を表すためには用いない。

```text
P(node, group)
≠ group 全員へ届けられる確率
```

PRoPHET 的な遭遇履歴・Gateway 到達性は、同一 priority 内での peer 選択の補助情報として用いてよい。

```text
peer が同じ event を未所持であり、
Gateway または多様な接触先へ到達しやすい
→ 優先的な転送候補にする
```

PRoPHET の広告値は偽装され得るため、単独の peer にのみ Bundle を預ける根拠にしてはならない。

## 10. 重複排除と保存管理

MNDT の重複排除キーは、再計算・検証済みの Nostr event ID とする。

```text
deduplication key = Nostr event id
```

ノードは少なくとも次を保持する。

```text
event ID
受信時刻
expiry
priority
転送済み peer
Bundle の状態
```

BPv7 Bundle が異なっていても Nostr event ID が同一なら、同一コンテンツとして扱う。

## 11. 任意の転送受領証

通常の配送では receipt を送らない。receipt は監査・限定的な配送証跡が必要な場合だけ使用する。

```cddl
receipt-payload = [
  "mndt-forward-v1",
  event-id: bstr .size 32,
  previous-receipt-digest: bstr .size 32 / null,
  forwarding-key-id: bstr,
  sequence: uint
]
```

`receipt-payload` は deterministic CBOR とし、`COSE_Sign1` で署名する。

```text
Nostr core:
  NIP-01 / BIP-340 Schnorr 署名

BPv7 block 保護:
  必要に応じて BPSec BIB / BCB

転送 receipt:
  必要に応じて COSE_Sign1
```

独自署名形式は定義しない。

receipt は次を証明する。

```text
ある鍵の保持者が、
特定 event ID の転送・観測に関する記録へ署名した
```

receipt は、実際の物理経路、全転送者、配送完了を完全には証明しない。

## 12. Nostr Gateway 変換

Gateway は MNDT Bundle を受信したとき、次の順に処理する。

```text
1. BPv7 Bundle の妥当性を検査する
2. MNDT Payload を CBOR decode する
3. pubkey を lowercase hex に復元する
4. NIP-01 の規定 JSON serialization を再構成する
5. SHA-256 で Nostr event ID を計算する
6. BIP-340 Schnorr 署名を検証する
7. mndt tag と Bundle destination の整合性を検査する
8. 標準 Nostr JSON event を生成する
9. Relay へ ["EVENT", event] を送る
```

Gateway が Relay へ送る event は、通常の Nostr event である。

```json
["EVENT", {
  "id": "<recomputed id>",
  "pubkey": "<lowercase hex>",
  "created_at": 1760000000,
  "kind": 1,
  "tags": [
    ["mndt", "1", "dtn://mesh.example/~topic/disaster", "8", "3"]
  ],
  "content": "避難所Aは開設済みです。",
  "sig": "<lowercase hex>"
}]
```

Gateway は、元 event の以下を変更してはならない。

```text
pubkey
created_at
kind
tags
content
id
sig
```

そのため、`mndt` tag を含む独自 Nostr 拡張は、通常の Nostr event の一部として維持される。

## 13. 経路情報の Nostr への公開

BPv7 extension block 内の情報は、元 Nostr event に追記してはならない。

```text
経路情報を tags に追記する
→ event ID と著者署名が壊れる
```

Mesh Metadata Block や receipt をインターネット側へ公開したい場合は、元 event を変更せず、元 event ID を参照する**別 Nostr event**として公開する。

この sidecar event の kind 定義は MNDT draft-00 の対象外とする。通常運用では経路情報を Nostr Relay へ公開しない。

## 14. セキュリティ上の注意

MNDT は以下を防がない。

```text
- Sybil 攻撃
- 大量投稿による保存領域枯渇
- 偽の PRoPHET 指標広告
- 悪意ある中継者による Bundle 廃棄
- 経路情報からの位置・接触関係の推定
- 物理層での妨害
```

各ノードは少なくとも次を実装する。

```text
- Nostr 署名検証
- message size 上限
- CBOR の最大ネスト深さ・要素数上限
- priority 別の保存枠
- pubkey ごとの受信・転送 rate limit
- event ID の重複排除
- Bundle Lifetime と hop limit の強制
- 未知・不正な CBOR の拒否
```

公開 Nostr 投稿では、Nostr core は本質的に公開情報である。Mesh 区間における秘匿性が必要な場合だけ、NIP-44 / NIP-17 / NIP-59 および必要に応じて BPSec BCB を用いる。

## 15. 非目標

MNDT draft-00 は次を提供しない。

```text
- 全 group 構成員への配送保証
- 正確に一回だけの配送
- 完全な経路証明
- Nostr Relay の WebSocket session を DTN 上で透過すること
- 大容量メディアの配送
- 複雑な membership 管理
- PRoPHET による全ノード配送確率の推定
```

## 16. 参照仕様

- [RFC 9171 — Bundle Protocol Version 7](https://www.rfc-editor.org/rfc/rfc9171.html)
- [RFC 9172 — Bundle Protocol Security](https://www.rfc-editor.org/rfc/rfc9172.html)
- [RFC 6693 — PRoPHET](https://www.rfc-editor.org/rfc/rfc6693.html)
- [RFC 8949 — CBOR](https://www.rfc-editor.org/rfc/rfc8949.html)
- [RFC 9052 — COSE](https://www.rfc-editor.org/rfc/rfc9052.html)
- [NIP-01 — Basic protocol flow](https://github.com/nostr-protocol/nips/blob/master/01.md)
