# 火山

識別子: `dev.jisaku.mosimo.hazard.volcano`

## 目的

本プロトコルは、火山に関する災害情報の種類別拡張を定義する。噴火・降灰・火山活動の高まり等の現象と、対象の火山を国際的な識別子で運ぶ。

保証するのは、署名された現象と火山の識別が保持・復元されることである。噴火警戒レベル等の国ごとの階級は共通拡張の地域スケールで運ぶ。

## 前提

| 識別子 | 文書 | 依存する理由 |
|---|---|---|
| `dev.jisaku.mosimo.hazard` | [災害情報](../README.md) | 共通拡張と組で用いる |
| `dev.jisaku.mosimo` | [コア](../../README.md) | `Microdegrees` |

## 1. 型

```ts
type VolcanoExtension = {
  $type: "dev.jisaku.mosimo.hazard.volcano";
  phenomenon: "eruption" | "ashfall" | "activity" | "pyroclastic-flow" | "lahar" | "gas";
  volcano?: {
    name?: LocalizedText;   // 火山名
    gvp?: string;           // Smithsonian Global Volcanism Program の火山番号（6 桁）
    lat?: Microdegrees;
    lon?: Microdegrees;
  };
};
```

正規の定義は [`lexicon.json`](lexicon.json) である。

| `phenomenon` | 意味 |
|---|---|
| `eruption` | 噴火の発生・噴火警報 |
| `ashfall` | 降灰・小さな噴石の予報 |
| `activity` | 火山活動の高まり・火山の状況に関する解説 |
| `pyroclastic-flow` | 火砕流 |
| `lahar` | 火山泥流・土石流 |
| `gas` | 火山ガス |

- `gvp` は火山を国際的に一意に識別する番号である。火山名は言語・国によって表記が異なるため、分かる場合は `gvp` を併記する。名称から番号を推測する場合は、変換者が管理する対応表に基づく。
- 火山の座標は影響範囲ではない。火口を中心とする円を `geo#area` として生成するのは、原情報がその円を警戒範囲として示す場合に限る。

## 2. 地域スケール

| 登録名 | 用途 |
|---|---|
| `jp.jma.volcanic-alert-level` | 気象庁の噴火警戒レベル |

## 3. 国際的な定義との対応

| 体系 | 対応 |
|---|---|
| CAP `category` | `Geo` |
| GLIDE | `VO` |
| UNDRR/ISC HIP（2025 年版） | 火山の総称に当たる項目はない。火山の群 GH0201〜GH0205 のうち、`ashfall` は GH0202 Ash/Tephra Fall |

## 未確定事項

- 降灰の量の階級と予想範囲の表現。

## 参照

- [災害情報](../README.md)
- [Smithsonian Institution Global Volcanism Program](https://volcano.si.edu/)
