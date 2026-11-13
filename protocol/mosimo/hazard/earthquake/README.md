# 地震

識別子: `dev.jisaku.mosimo.hazard.earthquake`

## 目的

本プロトコルは、地震に関する災害情報の種類別拡張を定義する。地震の発生後の情報、揺れが来る前の緊急地震速報、地震活動の評価（大地震の可能性の高まり等）を区別し、震源と規模を国に依存しない単位で運ぶ。

保証するのは、署名された震源・規模が共通の単位で保持・復元されることである。震度等の揺れの階級は国ごとに異なるため本拡張では扱わず、共通拡張の地域スケール（[災害情報 §5](../README.md#5-地域スケール)）で運ぶ。

## 前提

| 識別子 | 文書 | 依存する理由 |
|---|---|---|
| `dev.jisaku.mosimo.hazard` | [災害情報](../README.md) | 共通拡張と組で用いる。発震時刻は共通拡張の `onset` |
| `dev.jisaku.mosimo` | [コア](../../README.md) | `Microdegrees`・`Millimeters` |

## 1. 型

```ts
type EarthquakeExtension = {
  $type: "dev.jisaku.mosimo.hazard.earthquake";
  kind: "event" | "early-warning" | "assessment";
  hypocenter?: {
    lat?: Microdegrees;
    lon?: Microdegrees;
    depth?: Millimeters;        // 地表からの深さ。0 以上
    name?: LocalizedText;       // 震央地名（例: 石川県能登地方）
  };
  magnitude?: {
    value: integer;             // マグニチュードの 10 倍（M6.5 → 65）
    type?: string;              // Mj、Mw、ML、mb、Ms 等。不明なら省略
  };
};
```

正規の定義は [`lexicon.json`](lexicon.json) である。`LocalizedText` は[災害情報 §2.1](../README.md#21-型)の型である。

| `kind` | 意味 | 共通拡張の典型 |
|---|---|---|
| `event` | 発生した地震の震源・規模・揺れの情報 | `certainty: Observed`、`urgency: Past` |
| `early-warning` | 揺れが到達する前の予測（緊急地震速報等）。震源・規模は推定値 | `certainty: Likely`、`urgency: Immediate`、`responseTypes: ["Shelter"]` |
| `assessment` | 大地震発生の可能性の高まりの評価（南海トラフ地震臨時情報、余震の見通し等）。特定の地震の震源を表さない | `certainty: Possible`、`urgency: Future`、`responseTypes: ["Prepare"]` |

`kind` を必須とするのは、予測と観測、評価と発生を取り違えると取るべき行動が変わるためである。

## 2. 値の扱い

- マグニチュードは atproto データモデルが浮動小数点数を許さないため 10 倍の整数で表す。原情報が小数第 2 位以下を持つ場合は四捨五入する。原情報がマグニチュード不明を示す場合（気象庁 XML の `NaN` や、値 0 で不明を表す形式等）は `magnitude` を省略し、0 を入れてはならない。M0 の地震として表示されるためである。
- 深さが「ごく浅い」等の定性表現しかない場合は `depth` を省略し、本文に残す。深さ不明を 0 にしてはならない。
- 震央の座標が分からず地名だけ分かる場合は `name` だけを置く。地名から座標を推測してはならない。推測した点が震央として地図に表示されるためである。
- 震央の座標は影響範囲ではない。震央を中心とする円を `geo#area` として生成してはならない（[地理情報 §4.3](../../geo/README.md#43-近似)）。

## 3. 地域スケール

揺れの階級は共通拡張の `scales` に置く。投稿の対象地域で観測・予測された最大値を置き、`basis` で観測か予測かを必ず示す。

| 登録名 | 用途 |
|---|---|
| `jp.jma.shindo` | 気象庁震度階級（震度速報・震源震度情報は `observed`、緊急地震速報は `forecast`） |
| `jp.jma.nankai-trough` | 南海トラフ地震臨時情報（`kind: assessment`） |
| `intl.mmi` | 改正メルカリ震度階級 |

## 4. 国際的な定義との対応

| 体系 | 対応 |
|---|---|
| CAP `category` | `Geo` |
| GLIDE | `EQ` |
| UNDRR/ISC HIP（2025 年版） | GH0101 Earthquake |

## 未確定事項

- 複数の震源（同時発生・誘発地震）を一つの投稿で表す必要があるか。
- 震源の推定誤差・精度の表現。

## 参照

- [災害情報](../README.md)
- [気象庁 緊急地震速報 拡張](../../public/jma/eew/README.md)、[気象庁 地震観測情報 拡張](../../public/jma/earthquake/README.md) — 原形式の全項目
