# 0011: 著者署名付き拡張ブロック

状態: draft-01 — 拡張を Lexicon の open union の要素として表す方式は合意済み。公式の型識別子と共通タグ接頭辞は [0014](0014-namespaces.md) に割り当て済み。拡張型は基本プロトコル・メッシュ符号化と独立して版管理し、非互換な変更は新しい `$type` で識別する。個別フィールド等には未決定事項が残る。

## 要旨

本文だけを扱うアプリと、地理情報・公的情報・注釈等を扱うアプリを相互運用させるための、任意の拡張ブロックを整理する。「ブロック」は著者が付ける情報の論理的な単位であり、BPv7 extension block とは別である。

## 1. 基本プロトコルとの境界

基本投稿は本文・投稿時刻・配送期限を持つ。基本版1のレコードのトップレベルは `$type`・`text`・`createdAt`・`deliveryExpiresAt`・`extensions` の5項目に固定し、位置・有効期間・出典・言語・分類・アプリ固有情報等の任意の追加情報は `extensions` の要素として定義する。拡張に未対応でも本文を読めるようにし、主要な避難指示等を拡張の中だけに隠さない。

| 著者の拡張 | 中継者の配送情報 |
|---|---|
| 発信地点・影響範囲・出典・注釈等 | hop count・Bundle Age・直前ノード等 |
| 著者の署名対象で不変 | 各配送層の規則に従って変わる |
| Nostr タグ／atproto レコードで保持 | BPv7 の標準ブロック等で保持 |
| 未知でも削除・書換えしない | BPv7 の処理フラグ等に従う |

## 2. 共通構造（合意済み）

投稿の `extensions` は、Lexicon の open union を要素とする配列とする。各要素は `$type` で型を識別するオブジェクトであり、拡張固有のフィールドをそのオブジェクトへ直接格納する。atproto レコード内で拡張本体を一律に CBOR bytes へ包む方式や、`{ type, version, value }` の共通の包みは採用しない。Nostr での運搬時の符号化は、このレコード内の型とは別である。

```ts
// 説明用の型。未知の型も許容するため、既知型だけの閉じた集合ではない。
type Extension = KnownExtension | UnknownExtension;
type KnownExtension = OriginExtension | AreaExtension | ValidityExtension
  | AnnotationExtension | SourceExtension | PublicInformationExtension;
type UnknownExtension = { $type: string; [field: string]: DataValue };
```

`DataValue` は atproto データモデルの値であり、float や未定義値を含めない。未知拡張もそのモデルに適合するオブジェクトとして保持する。`Text`・`Timestamp`・`Microdegrees`・`Millimeters`・`Bytes`・`Cid` 等は [0010](0010-core.md) の型を使う。身元証明の型は内容が定まり次第、既知型へ追加できる。

- `$type` は完全修飾の Lexicon 型参照（NSID、必要なら `#定義名`）を持つ。以下の短い `area` 等は説明用名称である。公式型の名前は [0014](0014-namespaces.md) に従い、影響範囲は `dev.oriom.mosimo.defs#area` 等を使う。
- 個別型の `$type` は割り当て済みの型参照を使う。個別のフィールド・上限が未確定のため、型名の確定だけでスキーマが公開可能になったとは扱わない。
- 旧案の共通 `version` フィールドは置かない。拡張の互換性を壊す変更は新しい `$type` で識別し、基本プロトコル・メッシュ符号化・原情報の版とは独立に管理する。互換な変更は Lexicon の進化規則に従う。
- 拡張は投稿内の入れ子のオブジェクトであり、別レコード・別 Nostr イベントにはしない。
- 拡張一覧は配列として保持し、中継時に並べ替えない。同じ型の複数要素も独立して保持し、運搬時に統合・上書き・重複削除しない。個別の既知型が意味上許す多重性は別途定める。

未知の `$type` や既知型の未知フィールドも元の署名対象表現を保持する。保持・中継用の元オブジェクトと、表示用に読み取った既知フィールドを区別し、既知項目だけで元データを作り直さない。これは未知の基本プロトコル版まで対応済みとする意味ではない。

## 3. 拡張の一覧

| 拡張の説明用名称 | 用途 | 未対応・省略時 | 詳細 |
|---|---|---|---|
| `origin` | 発信端末が任意公開する発信地点 | 位置不明。本文は利用可能 | §4 |
| `area` | 内容の影響範囲 | 地域不明。全世界対象にはしない | §5、[0004](0004-geography.md) |
| `validity` | 内容の有効期間 | 期間不明。無期限に有効とはしない | §6 |
| `annotation` | 訂正・補足・追認の対象と関係 | 本文を読める。対象との関連は不明 | §7、[0008](0008-posts-and-labels.md) |
| `source` | 原情報・配布容器・取得・変換の出典 | 公式情報への帰属を推定しない | [0013 §4.2](0013-public-information.md#42-source-出典と変換経路提案) |
| `public-information` | CAP 等の原語彙・訂正取消・言語・強度等 | 本文と出典を利用 | [0013 §4.3](0013-public-information.md#43-public-information-原情報の意味提案) |
| `identity` | 自治体等の署名付き身元証明チェーン | 公式の身元は未確認 | §8、[0009](0009-identity-attestations.md) |

これらは独立した関心である。公的な投稿に必ず `origin` が必要なわけではなく、`area` があっても発信者の現在地は分からない。

## 4. 発信地点 `origin`（型案）

```ts
type OriginExtension = {
  $type: "dev.oriom.mosimo.defs#origin";
  latitudeMicrodegrees: Microdegrees;
  longitudeMicrodegrees: Microdegrees;
  accuracyMillimeters?: Millimeters;
  measuredAt?: Timestamp;
};
```

緯度・経度は WGS 84 の100万分の1度、精度の距離はミリメートル単位の整数で保存する。表示時は度・メートルへ換算できる。精度の統計的な定義・数値範囲・原値から整数への丸め規則は未決定である。公開は任意であり、利用者は精度を落とせる。測位時刻が不明なら現在時刻で補わない。高度を基本の領域判定には使わない。

公的電文の震源・観測地点や、変換サーバーの所在地を被災現場の発信地点として扱わない。署名された座標は発信者の申告であって GPS の真正性や現地滞在の証明ではない。

## 5. 影響範囲 `area`（型案）

```ts
type Circle = {
  shape: "circle";
  latitudeMicrodegrees: Microdegrees;
  longitudeMicrodegrees: Microdegrees;
  radiusMillimeters: Millimeters;
};
type GeohashCell = { shape: "geohash"; hash: string };
type AreaExtension = { $type: "dev.oriom.mosimo.defs#area"; regions: (Circle | GeohashCell)[] };
```

`regions` の意味は和集合である。円とセルを混在できる。中心座標は WGS 84 の100万分の1度、半径はミリメートル単位の整数として保存する。行政区域コード・多角形・除外領域は初期の共通範囲型に含めない。

```json
{
  "$type": "dev.oriom.mosimo.defs#area",
  "regions": [
    { "shape": "circle", "latitudeMicrodegrees": 35000000, "longitudeMicrodegrees": 139000000, "radiusMillimeters": 2500000 },
    { "shape": "geohash", "hash": "xn76" }
  ]
}
```

この例の二つの領域は同一形状であることを意味しない。Geohash の許容長・要素数・半径上限・原値の丸めと領域誤差・地表距離の計算法・空配列の扱いは未決定である。座標・距離の固定単位は [0010 §3.2](0010-core.md#32-座標距離の整数表現合意済み) に従う。

対応できる要素のいずれかに含まれれば範囲内。それ以外で未知の要素があれば判定不明。全要素を扱えた場合に限り範囲外とできる。元電文の領域の一部しか変換できなかった場合、既知部分だけを完全な範囲として発行しない。元領域全体を覆う Geohash 近似を生成できる場合は、その全体の近似を使用できる。全体を扱えない場合は共通 `area` を省略し、原形式側に情報を保持する。近似の区別は `area` 自体に明示する方針が合意済みだが、上の型案にはまだそのフィールドを反映していない。具体的な区分の型・配置と未解決部分の表現は未決定である。詳細は [0004 §5.1](0004-geography.md#51-原情報全体を覆う近似合意済み) に従う。

近似領域に含まれる地点が、原情報の厳密な警報対象であるとは限らない。影響範囲は表示・配送順位のヒントであり、閲覧制限や範囲外中継の禁止ではない。地域外のゲートウェイへの搬出も許容する。

## 6. 情報の有効期間 `validity`（型案）

```ts
type ValidityExtension = {
  $type: "dev.oriom.mosimo.defs#validity";
  startsAt?: Timestamp;
  endsAt?: Timestamp;
};
```

有効期間自体は任意。不明な端点は省略し、配送期限や受信時刻で補わない。時刻の表記と精度は [0010 §3.1](0010-core.md#31-共通時刻型合意済み) に従う。両端省略のブロックを許容するか、期間境界を含むかは未決定。終了は削除を意味しない。`startsAt`・`endsAt` という項目名と片端省略の構造は提案である。

原情報が持つ現象発生時刻と、有効開始時刻は異なる。CAP の `onset` や BSAF の `time:` を無条件で `startsAt` に置き換えない。

## 7. 注釈 `annotation`（型案）

```ts
type PostReference =
  | { network: "atproto"; uri: AtUri; cid?: Cid }
  | { network: "nostr"; eventId: NostrEventId };

type AnnotationExtension = {
  $type: "dev.oriom.mosimo.defs#annotation";
  relation: "correction" | "supplement" | "endorsement";
  target: PostReference;
};
```

これは未決だった参照形式を説明する案である。一つの参照が場所を指すのか特定版を指すのかを区別し、具体的な Nostr タグ・atproto strong reference との対応は今後固定する。二経路の参照を同じ投稿へ対応付ける索引は AppView 等が担い、URI と event ID を単なる文字列一致では比較しない。

注釈の著者はその注釈を発信した主体であり、対象投稿の著者ではない。元著者による訂正と他者の訂正の主張を区別する。関係の存在を示す Label はサービスが別途生成する。取消など原情報側の関係は `public-information.references` に保持し、本プロトコルの投稿を参照できる場合に注釈と関連付ける。

## 8. 身元証明 `identity`

論理上は信頼するルートから末端の DID・atproto 公開鍵へ至る署名付き証明の列を持つ。証明書の署名形式・スキーマが未決定のため、本書では架空の証明書フィールドを固定しない。

末端の Nostr 鍵との対応は独立した鍵対応宣言を用いる。通常投稿への証明の毎回同梱や、本文表示前の DID 認証は要求しない。公式発信者として表示する場合にのみ必要な証拠・信頼の起点を用いる。詳細は [0009](0009-identity-attestations.md) に従う。

## 9. 拡張の追加と配置

Nostr の既定の運び方は、1拡張オブジェクト＝1自己完結タグとする。タグ名は共通接頭辞 `mosimo.oriom.dev:ext:` と完全修飾の `$type`、値は `$type` 以外の全フィールドの atproto CBOR を Base64（paddingなし）にしたものとする。接頭辞へ元の `$type` をそのまま連結する。割り当ては [0014](0014-namespaces.md) に従う。拡張タグの出現順から `extensions` 配列を構成し、位置番号は要求しない。詳細は [0012 §3.2](0012-bindings.md#32-項目拡張ごとのタグ配置方式合意済み) に従う。

拡張は任意であり、誰でも自身の衝突しない Lexicon 名前空間で定義できる。共通管理者の承認や、全クライアント・PDS への型一覧・専用コードの追加は要求しない。未知の拡張も共通の運び方だけで保持・再構成でき、意味を利用するアプリだけがその型に対応する。受信時のオンラインスキーマ取得も必須にしない。

標準タグは対象文脈と意味が正確に一致し、型・全フィールド・配列位置を欠落なく扱う共通規則がある場合だけ直接使う。それ以外は自己完結した独自タグとし、検索目的の別名タグ・重複索引は追加しない。未知フィールドを捨てて標準タグへ合わせない。

メッシュ用 CBOR では共通拡張タグの値を生バイト列にできる。内側の型が未知でもこの処理は可能であり、内部のフィールドを拡張固有コードで再整形しない。共通形式すら未知のタグ・変換条件に合わない値は元の文字列で保持する。内部の日時も原表記を維持する。

新しい拡張は目的・型・互換性・省略時の意味・単位・本文へのフォールバックを文書化する。互換な任意フィールド追加等は同じ `$type` で行えるが、非互換な構造・意味の変更は新しい型参照にする。拡張の追加だけで基本版やメッシュ符号化版を上げず、共通の拡張版フィールドも追加しない。詳細は [0010 §3.3](0010-core.md#33-三層の独立した版管理合意済み) に従う。新しい型名の命名規則・任意の発見カタログの方式は未決定だが、中央登録による許可を定義の条件にしない。

著者拡張の正本は署名付き投稿の一部である。BPv7 独自ブロックにだけ格納すると Nostr 搬出時に失われるため、そのような配置を著者情報の唯一の保存先にはしない。標準・独自のいずれのタグも署名時点で含め、中継者が付け足すものではない。

## 10. 参照

- [atproto Lexicon — union・When to use `$type`・Lexicon Evolution](https://atproto.com/specs/lexicon)（2026-10-17 確認）
