# 気象・水文

識別子: `dev.jisaku.mosimo.hazard.weather`

## 目的

本プロトコルは、大雨・洪水・土砂災害・暴風・竜巻・大雪等の気象・水文に起因する災害情報の種類別拡張を定義する。国によって細分の仕方が異なる気象警報類を、現象の語彙で表す。

熱帯低気圧そのものの情報は[熱帯低気圧](../cyclone/README.md)、津波は[津波](../tsunami/README.md)で扱う。台風に伴う大雨・暴風の警報は本拡張で扱う。

## 前提

| 識別子 | 文書 | 依存する理由 |
|---|---|---|
| `dev.jisaku.mosimo.hazard` | [災害情報](../README.md) | 共通拡張と組で用いる |

## 1. 型

```ts
type WeatherExtension = {
  $type: "dev.jisaku.mosimo.hazard.weather";
  phenomena: string[];  // 0〜8 件。§2 の語彙
};
```

正規の定義は [`lexicon.json`](lexicon.json) である。

`phenomena` は情報の対象となる現象の集合である。一つの警報が複数の現象を対象とする場合（暴風雪 = `wind` と `snow` 等）は全てを並べる。気象情報の解説のように特定の現象を示さない情報は空配列とする。受信側は未知の値を無視し、既知の値だけで表示する。未知の値しかない場合は共通拡張の大分類で表示する。

## 2. 語彙

| 値 | 現象 | 既定の大分類 | GLIDE | UNDRR/ISC HIP（2025 年版） |
|---|---|---|---|---|
| `rain` | 大雨・記録的短時間大雨・線状降水帯 | `Met` | なし | MH0402 Rain |
| `flood` | 河川の洪水・浸水 | `Met` | `FL` | MH0600 Flooding（河川は MH0604、急な出水は MH0603） |
| `landslide` | 土砂災害（がけ崩れ・土石流・地すべり） | `Geo` | `LS` | GH0300 Gravitational Mass Movement (Landslide) |
| `storm-surge` | 高潮 | `Met` | `SS` | MH0703 Storm Surge |
| `wave` | 高波・波浪 | `Met` | なし | 該当なし（近いものは MH0701 Rogue Wave） |
| `wind` | 暴風・強風 | `Met` | `VW` | MH0301 Wind、MH0303 Gale |
| `tornado` | 竜巻等の激しい突風 | `Met` | `TO` | MH0305 Tornado |
| `thunderstorm` | 雷・落雷 | `Met` | `ST` | MH0103 Thunderstorm、MH0102 Lightning |
| `snow` | 大雪・暴風雪 | `Met` | なし | MH0405 Snow、MH0406 Snow Storm |
| `snowmelt` | 融雪 | `Met` | `FL` | MH0610 Snowmelt Flooding |
| `avalanche` | なだれ | `Geo` | `AV` | MH0801 Avalanche |
| `fog` | 濃霧 | `Met` | なし | MH0202 Fog |
| `dry-air` | 乾燥 | `Met` | なし | 該当なし |
| `cold` | 低温・寒波 | `Met` | `CW` | MH0502 Cold Wave |
| `heat` | 高温・熱波 | `Met` | `HT` | MH0501 Heatwave |
| `frost` | 霜 | `Met` | なし | MH0505 Frost |
| `icing` | 着氷 | `Met` | なし | MH0509 Icing |
| `snow-accretion` | 着雪 | `Met` | なし | 該当なし（近いものは MH0509 Icing） |

語彙は現象の種類であり、注意報・警報等の区分は表さない。区分は共通拡張の深刻度と地域スケール（`jp.jma.warning-level`、`jp.jma.warning-class` 等）で表す。値の追加は既存の値の意味を変えない。既存の値の意味を変えてはならない。過去に署名された投稿の解釈が変わるためである。

## 未確定事項

- 現象ごとの量的な予想値（雨量・風速・積雪深等）の表現。
- 語彙の追加手続き。

## 参照

- [災害情報](../README.md)
- [気象庁 気象防災情報 拡張](../../public/jma/weather/README.md) — 原形式の全項目
- UNDRR-ISC, *Hazard Information Profiles: The 2025 update of the UNDRR-ISC HIPs*（2025）
