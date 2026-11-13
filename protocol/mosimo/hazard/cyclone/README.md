# 熱帯低気圧

識別子: `dev.jisaku.mosimo.hazard.cyclone`

## 目的

本プロトコルは、台風・ハリケーン・サイクロン等の熱帯低気圧に関する災害情報の種類別拡張を定義する。地域によって呼び名と強さの階級が異なる熱帯低気圧を一つの型で表し、中心位置・気圧・風速を共通の単位で運ぶ。

## 前提

| 識別子 | 文書 | 依存する理由 |
|---|---|---|
| `dev.jisaku.mosimo.hazard` | [災害情報](../README.md) | 共通拡張と組で用いる |
| `dev.jisaku.mosimo` | [コア](../../README.md) | `Microdegrees`・`Millimeters` |

## 1. 型

```ts
type CycloneExtension = {
  $type: "dev.jisaku.mosimo.hazard.cyclone";
  name?: LocalizedText;            // 固有名（例: ハギビス、Hagibis）
  designation?: string;            // 発表機関の番号（例: 台風第19号は "JMA 2019-19"）
  center?: { lat: Microdegrees; lon: Microdegrees };
  pressure?: integer;              // 中心気圧（Pa）
  maxWind?: integer;               // 最大風速（mm/s）。平均時間は原情報に従う
  stormRadius?: Millimeters;       // 暴風域の半径
};
```

正規の定義は [`lexicon.json`](lexicon.json) である。

- 最大風速の平均時間は機関によって異なる（気象庁は 10 分間平均、米国は 1 分間平均）。異なる機関の値を比較してはならない。
- 中心位置が「日本の南」等の地名でしか分からない場合は `center` を省略し、本文に残す。
- 暴風域の円は台風の現在の範囲を表すが、警報の対象範囲ではない。`geo#area` に置くのは、原情報がその範囲を情報の対象として示す場合に限る。

## 2. 地域スケール

| 登録名 | 用途 |
|---|---|
| `jp.jma.typhoon-intensity` | 気象庁の台風の強さの階級 |
| `intl.saffir-simpson` | サファ・シンプソン・ハリケーン・スケール |

## 3. 国際的な定義との対応

| 体系 | 対応 |
|---|---|
| CAP `category` | `Met` |
| GLIDE | `TC` |
| UNDRR/ISC HIP（2025 年版） | MH0309 Tropical Cyclone |

## 未確定事項

- 予報円・進路予報の表現。
- `designation` の表記規則。

## 参照

- [災害情報](../README.md)
