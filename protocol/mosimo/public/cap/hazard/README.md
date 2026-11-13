# CAP から災害情報への対応

識別子: `dev.jisaku.mosimo.public.cap.hazard`

## 目的

本プロトコルは、CAP 1.2 の `info` から[災害情報](../../../hazard/README.md)の共通拡張と種類別拡張を生成する変換規則を定義する。共通拡張の語彙は CAP 1.2 の列挙値そのものであるため、大半の項目は値を書き換えずに写す。識別子は `public#source` の `conversion.rule` に記録する。本書の版は `1` である。

本書は仕様のみであり、現時点で実装した取り込み経路はない。

## 前提

| 識別子 | 文書 | 依存する理由 |
|---|---|---|
| `dev.jisaku.mosimo.public.cap` | [CAP 1.2 拡張](../README.md) | 投稿単位（`info` ごと）、原形式の全項目 |
| `dev.jisaku.mosimo.hazard` 以下 | [災害情報](../../../hazard/README.md) | 生成する拡張 |
| `dev.jisaku.mosimo.public` | [公的情報の取り込み](../../README.md) | 領域の近似（§10）、更新・取消（§9） |

## 1. 共通拡張

一つの `info` から一つの共通拡張を生成する（[公的情報 §4.1](../../README.md#41-capedxl-de-の投稿単位)）。

| CAP | 共通拡張 | 規則 |
|---|---|---|
| `alert.status` | `status` | `Actual`・`Exercise`・`Test` はそのまま。`System`・`Draft` は投稿を生成しない |
| `info.category` | `category` | そのまま（最大 4 件。超える場合は原順で先頭 4 件とし、全件は CAP 拡張に残す） |
| `info.severity`・`urgency`・`certainty` | 同名 | そのまま。CAP 1.1 以前の `Very Likely` は `Likely` |
| `info.responseType` | `responseTypes` | そのまま（最大 4 件、同上） |
| `info.onset` | `onset` | UTC へ変換する。`onset` がない場合は省略し、`effective`・`sent` で補ってはならない |
| `info.event`・`info.language` | `event` | `text` = `event`、`lang` = `language`（省略時 `en-US`） |
| `info.parameter` | `scales` | §3 の対応表にある `valueName` だけ |
| `info.area` | `geo#area` | [公的情報 §10](../../README.md#10-共通領域への近似と変換不能時の扱い)。`circle` は円へ、`polygon` は全体を覆うセルへ近似する。`geocode` だけで境界資料がない場合は省略する |

`alert.msgType` が `Cancel` の電文は取消であり、共通拡張を付けない。取消は危険の解除ではないためである（[災害情報 §4](../../../hazard/README.md#4-深刻度切迫度確実性行動)）。

## 2. 種類別拡張

CAP には世界共通の種類の語彙がないため、種類別拡張は `info.eventCode` の既知の体系からだけ生成する。体系と値が次の表にない場合は種類別拡張を生成せず、共通拡張だけで投稿する。`event` の自由記述から種類を推測してはならない。言語・国によって表記が異なり、誤った種類を署名するためである。

| `eventCode.valueName` | 値 | 種類別拡張 |
|---|---|---|
| `SAME`（米国） | `EQW` | `hazard.earthquake`（`kind: "event"`） |
| | `TSW`、`TSA` | `hazard.tsunami` |
| | `VOW` | `hazard.volcano`（`phenomenon: "eruption"`） |
| | `TOR`、`TOA` | `hazard.weather`（`["tornado"]`） |
| | `FFW`、`FFA`、`FLW`、`FLA` | `hazard.weather`（`["flood"]`） |
| | `HUW`、`HUA`、`TRW`、`TRA` | `hazard.cyclone` |
| | `SVR`、`SVA` | `hazard.weather`（`["thunderstorm"]`） |
| | `WSW`、`WSA`、`BZW` | `hazard.weather`（`["snow"]`） |
| | `SSW`、`SSA` | `hazard.weather`（`["storm-surge"]`） |
| | `HWW`、`HWA` | `hazard.weather`（`["wind"]`） |
| `awareness_type`（MeteoAlarm） | `1; Wind` | `hazard.weather`（`["wind"]`） |
| | `2; snow-ice` | `hazard.weather`（`["snow", "icing"]`） |
| | `3; Thunderstorm` | `hazard.weather`（`["thunderstorm"]`） |
| | `4; Fog` | `hazard.weather`（`["fog"]`） |
| | `5; high-temperature` | `hazard.weather`（`["heat"]`） |
| | `6; low-temperature` | `hazard.weather`（`["cold"]`） |
| | `7; coastalevent` | `hazard.weather`（`["storm-surge", "wave"]`） |
| | `9; avalanches` | `hazard.weather`（`["avalanche"]`） |
| | `10; Rain` | `hazard.weather`（`["rain"]`） |
| | `12; flooding` | `hazard.weather`（`["flood"]`） |
| | `13; rain-flood` | `hazard.weather`（`["rain", "flood"]`） |

## 3. 地域スケール

| `parameter.valueName` | 地域スケール |
|---|---|
| `awareness_level`（MeteoAlarm、例 `3; orange; Severe`） | `intl.meteoalarm.awareness-level` = 色名 |

## 未確定事項

- 各国の CAP プロファイル（CAP-CP、CAP-AU 等）の `eventCode` 対応表。
- `SAME` の値の網羅と、警報（Warning）・注意報（Watch）の区別を深刻度へ反映する規則。
- `polygon` を Geohash へ近似する具体的なアルゴリズム（[公的情報 未確定事項](../../README.md#未確定事項)と共通）。

## 参照

- [OASIS CAP 1.2](https://docs.oasis-open.org/emergency/cap/v1.2/CAP-v1.2-os.html)
- [47 CFR § 11.31 EAS protocol](https://www.ecfr.gov/current/title-47/chapter-I/subchapter-A/part-11/subpart-B/section-11.31) — SAME のイベントコード
- [MeteoAlarm CAP profile](https://meteoalarm.org/) — `awareness_type`・`awareness_level`
