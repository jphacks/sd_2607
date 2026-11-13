# 気象庁 XML から災害情報への対応

識別子: `dev.jisaku.mosimo.public.jma.hazard`

## 目的

本プロトコルは、気象庁防災情報 XML から[災害情報](../../../hazard/README.md)の共通拡張と種類別拡張を生成する変換規則を定義する。気象庁 XML は震源の座標・マグニチュードの種類・地域コードを持つため、BSAF 経由より多くの項目を構造化できる。識別子は `public#source` の `conversion.rule` に記録する。本書の版は `1` である。

本書は仕様のみであり、現時点で実装した取り込み経路はない。投稿単位・本文・更新取消は[気象庁 XML 共通](../README.md)と電文別の拡張に従う。

## 前提

| 識別子 | 文書 | 依存する理由 |
|---|---|---|
| `dev.jisaku.mosimo.public.jma` 以下 | [気象庁 XML 共通](../README.md)、[緊急地震速報](../eew/README.md)、[地震観測情報](../earthquake/README.md)、[気象防災情報](../weather/README.md) | 投稿単位、原形式の全項目 |
| `dev.jisaku.mosimo.hazard` 以下 | [災害情報](../../../hazard/README.md) | 生成する拡張 |

## 1. 共通の規則

| 気象庁 XML | 共通拡張 |
|---|---|
| `Control/Status` | `通常` → `Actual`、`訓練` → `Exercise`、`試験` → `Test` |
| `Head/InfoType` が `取消` | 共通拡張を付けない（取消は解除ではない） |
| `Head/Title` | `event`（`lang: "ja"`） |
| 震源要素の `OriginTime` | `onset` |

## 2. 地震・津波・火山

| 電文 | 種類別拡張 | 共通拡張 | 地域スケール |
|---|---|---|---|
| 緊急地震速報（警報・予報） | `hazard.earthquake`（`kind: "early-warning"`） | 警報: `Severe` / `Immediate` / `Likely` / `Shelter`。予報: `Moderate` / `Immediate` / `Likely` / — | `jp.jma.shindo`（予測最大震度、`forecast`） |
| 震度速報、震源・震度に関する情報 | `hazard.earthquake`（`kind: "event"`） | 最大震度により [BSAF 対応 §3.1](../../bsaf/hazard/README.md#31-地震typeearthquake) と同じ | `jp.jma.shindo`（`observed`） |
| 震源に関する情報 | `hazard.earthquake`（`kind: "event"`） | `Unknown` / `Past` / `Observed` / — | — |
| 南海トラフ地震臨時情報 | `hazard.earthquake`（`kind: "assessment"`） | [BSAF 対応 §3.5](../../bsaf/hazard/README.md#35-南海トラフ地震臨時情報typenankai-trough) と同じ | `jp.jma.nankai-trough` |
| 津波警報・注意報・予報 | `hazard.tsunami` | 大津波警報・津波警報・津波注意報・津波予報ごとに BSAF 対応 §3.2 と同じ | `jp.jma.tsunami` |
| 噴火警報・予報、噴火速報 | `hazard.volcano`（`eruption`） | 噴火警戒レベル 4・5 → `Extreme` / … / `Evacuate`、3 → `Severe` / … / `Avoid`、2 → `Moderate` / … / `Avoid`、噴火速報 → `Observed` / `Immediate` | `jp.jma.volcanic-alert-level` |
| 降灰予報 | `hazard.volcano`（`ashfall`） | 速報・詳細 → `Moderate` / `Expected` / `Likely` / `Avoid`、定時 → `Minor` / `Future` / `Possible` / — | — |

震源は `jmx_eb:Coordinate`（ISO 6709 表記）から `hypocenter.lat`・`lon`・`depth` を、`jmx_eb:Magnitude@type` から `magnitude.type`（`Mj` 等）を得る。`Magnitude` が `NaN` の場合、深さが「ごく浅い」等の場合は[地震 §2](../../../hazard/earthquake/README.md#2-値の扱い)に従い省略する。火山は `VolcanoInfo/Item/Areas/Area/Code` から対応表で `gvp` を得られる場合に置く。

## 3. 気象

| 電文 | 種類別拡張 | 共通拡張 | 地域スケール |
|---|---|---|---|
| 気象警報・注意報（警戒レベル付きの種別） | `hazard.weather`（警報の種別から §4） | 警戒レベル 2〜5 ごとに [BSAF 対応 §4.2](../../bsaf/hazard/README.md#42-値) と同じ | `jp.jma.warning-level` |
| 気象警報・注意報（その他の種別） | 同上 | 注意報 → `Moderate`、警報 → `Severe`、特別警報 → `Extreme`。いずれも `Expected` / `Likely` | `jp.jma.warning-class` |
| 警報・注意報の解除 | 同上 | `Minor` / `Past` / `Observed` / `AllClear` | — |
| 竜巻注意情報 | `hazard.weather`（`["tornado"]`） | `Severe` / `Immediate` / `Likely` / `Shelter` | `jp.jma.warning-class` = `warning` |
| 記録的短時間大雨情報 | `hazard.weather`（`["rain"]`） | `Severe` / `Immediate` / `Observed` / — | — |
| 土砂災害警戒情報 | `hazard.weather`（`["landslide"]`） | `Extreme` / `Expected` / `Likely` / `Evacuate` | `jp.jma.warning-level` = `4` |
| 指定河川洪水予報 | `hazard.weather`（`["flood"]`） | 警戒レベルにより §3 第 1 行と同じ | `jp.jma.warning-level` |
| 台風解析・予報情報 | `hazard.cyclone` | 強さの階級により [BSAF 対応 §4.2](../../bsaf/hazard/README.md#42-値) と同じ | `jp.jma.typhoon-intensity` |

台風は `MeteorologicalInfo` の中心位置（`jmx_eb:Coordinate`）・中心気圧・最大風速・暴風域から `center`・`pressure`・`maxWind`・`stormRadius` を得る。

## 4. 警報の種別と現象

気象警報・注意報の種類コードの現象を、[気象・水文 §2](../../../hazard/weather/README.md#2-語彙) の語彙へ対応させる。大雨 → `rain`、洪水 → `flood`、土砂災害 → `landslide`、暴風・強風 → `wind`、暴風雪・風雪 → `wind` と `snow`、大雪 → `snow`、波浪 → `wave`、高潮 → `storm-surge`、雷 → `thunderstorm`、融雪 → `snowmelt`、濃霧 → `fog`、乾燥 → `dry-air`、なだれ → `avalanche`、低温 → `cold`、霜 → `frost`、着氷 → `icing`、着雪 → `snow-accretion` とする。

## 5. 影響範囲

警報・注意報の対象は二次細分区域等の地域コードで示される。気象庁の予報区等 GIS データ（版を `source.conversion` の版とともに固定する）で地域コードを解決できる場合に限り、[公的情報 §10](../../README.md#10-共通領域への近似と変換不能時の扱い)に従い全体を覆う Geohash セルへ近似する。津波予報区も同様とする。

## 未確定事項

- 予報区等 GIS データの版と、Geohash 近似の精度・セル数。
- 緊急地震速報の警報・予報で、地域ごとに異なる予測震度をどの投稿単位で扱うか（[緊急地震速報](../eew/README.md)と共通）。
- 火山コードと GVP 番号の対応表。

## 参照

- [気象庁防災情報 XML](https://xml.kishou.go.jp/)
- [気象庁 予報区等 GIS データ](https://www.data.jma.go.jp/developer/gis.html)
