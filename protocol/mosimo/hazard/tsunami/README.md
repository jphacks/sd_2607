# 津波

識別子: `dev.jisaku.mosimo.hazard.tsunami`

## 目的

本プロトコルは、津波に関する災害情報の種類別拡張を定義する。予想・観測された津波の高さと到達時刻を、国に依存しない単位で運ぶ。

保証するのは、署名された高さ・時刻が共通の単位で保持・復元されることである。津波警報の種類は国ごとに異なるため、共通拡張の深刻度と地域スケールで運ぶ。

## 前提

| 識別子 | 文書 | 依存する理由 |
|---|---|---|
| `dev.jisaku.mosimo.hazard` | [災害情報](../README.md) | 共通拡張と組で用いる |
| `dev.jisaku.mosimo` | [コア](../../README.md) | `Millimeters`・`Timestamp` |

## 1. 型

```ts
type TsunamiExtension = {
  $type: "dev.jisaku.mosimo.hazard.tsunami";
  maxHeight?: {
    value?: Millimeters;              // 数値で示された高さ
    text?: LocalizedText;             // 原表記（例: 巨大、高い、10m超）
    basis?: "observed" | "forecast";
  };
  arrival?: Timestamp;                // 対象地域への最も早い到達（予想）時刻
};
```

正規の定義は [`lexicon.json`](lexicon.json) である。

- `maxHeight` は投稿の対象地域で示された最大の高さである。「巨大」「高い」のような定性表現や「10m超」のような範囲表現は、数値に置き換えず `text` に原表記で置く。範囲の境界値を `value` に入れてはならない。「10m超」を 10m として表示すると過小評価になるためである。
- `arrival` が「既に到達と推測」等の表現しか持たない場合は省略し、本文に残す。
- 地震の震源は本拡張では扱わない。地震と津波を一つの情報で伝える場合は[災害情報 §7](../README.md#7-種類別拡張)に従い主たる種類を一つ選ぶ。津波の警報を含む場合は避難行動に直結するため、本拡張を選ぶ。

## 2. 地域スケール

| 登録名 | 用途 |
|---|---|
| `jp.jma.tsunami` | 気象庁の大津波警報・津波警報・津波注意報・津波予報 |

## 3. 国際的な定義との対応

| 体系 | 対応 |
|---|---|
| CAP `category` | `Geo` |
| GLIDE | `TS` |
| UNDRR/ISC HIP（2025 年版） | MH0705 Tsunami |

## 未確定事項

- 地点ごとの観測値・予想値の一覧を運ぶ必要があるか。

## 参照

- [災害情報](../README.md)
