# BSAF から災害情報への対応

識別子: `dev.jisaku.mosimo.public.bsaf.hazard`

## 目的

本プロトコルは、BSAF v1 投稿を転載するときに、Bot 定義が意味を公開しているタグ値から[災害情報](../../../hazard/README.md)の共通拡張・種類別拡張と[影響範囲](../../../geo/README.md)を生成する変換規則を定義する。目的は、BSAF の語彙を知らないアプリでも、転載された投稿の種類・深刻度・範囲が分かるようにすることである。

本書は変換規則であり、新しい拡張型を定義しない。識別子は変換規則名として [`public#source`](../../README.md#5-出典拡張-source) の `conversion.rule` に記録する。本書の版は `1` である。

保証するのは、同じ原投稿に本書の同じ版を適用した変換者が同じ拡張を生成することである。保証しないのは、Bot 自身が原 XML から付けたタグ値の正しさ、共通値が気象庁の公式な対応であること、本文から抽出した値が原 XML の値と一致することである。対応表は本プロトコルの運用者が Bot 定義と Bot の公開ソースから定めたものである。

## 前提

| 識別子 | 文書 | 依存する理由 |
|---|---|---|
| `dev.jisaku.mosimo.public.bsaf` | [BSAF v1 互換拡張](../README.md) | 原タグ・原本文の保持、転載の経路、対象 Bot |
| `dev.jisaku.mosimo.hazard` 以下 | [災害情報](../../../hazard/README.md) | 生成する共通拡張・種類別拡張 |
| `dev.jisaku.mosimo.geo` | [地理情報](../../../geo/README.md) | `target:` から生成する近似の影響範囲 |
| `dev.jisaku.mosimo.public` | [公的情報の取り込み](../../README.md) | 出典拡張 `source` と変換規則の記録 |

## 1. 適用の条件

本書の対応表は、次の Bot と固定リビジョンの Bot 定義・投稿生成コードを対象とする。

| Bot | DID | Bot 定義と投稿生成コードの固定リビジョン |
|---|---|---|
| JMA Bot（`@jma-alert-bot.bsky.social`、非公式） | `did:plc:vwi7zhfyamyvrg5v6ycjgzxi` | [osprey74/bsaf-jma-bot@98071e7](https://github.com/osprey74/bsaf-jma-bot/tree/98071e7d579c8c67dea69dc9a325cdfcafa338b2) |
| Kikikuru Bot（`@bsaf-kikikuru-bot.bsky.social`、非公式） | `did:plc:kxwz5cz7o6g4jlmxh6doyfsm` | [osprey74/bsaf-kikikuru-bot@76ec562](https://github.com/osprey74/bsaf-kikikuru-bot/tree/76ec562305cddd10da3b91828033dd6066c99e74) |

- 原投稿の著者 DID が上の表にあり、`type:` と `value:` の組が §3・§4 の表にある場合に限り、災害情報の拡張を生成する。それ以外の投稿は、従来どおり BSAF 拡張と原本文だけで転載する。対応表にない値を、似た値の行や英語名から推測して変換してはならない。Bot ごとに同じ文字列が別の意味を持ち得るためである（[BSAF 拡張 §5.1](../README.md#51-既存-bsaf-投稿の-appview-収集)）。
- Bot が定義・生成コードを更新し、タグ値の意味や本文の書式が変わった場合は、本書の新しい版で対応表を改める。版を改めずに対応表を変えてはならない。同じ規則名・版の変換結果が変換者によって異なり、[配送同値性](../../README.md#7-配送同値性)の判断を誤らせるためである。
- 原本文・原タグは [BSAF 拡張 §5.2.2](../README.md#522-投稿への変換) に従いそのまま保持する。本書の拡張は追加であり、本文に追記しない。

## 2. 共通の規則

### 2.1 生成する拡張

| 拡張 | 生成の条件 |
|---|---|
| `dev.jisaku.mosimo.hazard` | §1 の条件を満たすとき常に |
| 種類別拡張 | 対応表の「種類別拡張」列が空でないとき |
| `dev.jisaku.mosimo.geo#area` | §2.3 で範囲を解決できたとき |
| `dev.jisaku.mosimo.public#source` | 災害情報の拡張を生成したとき常に（§2.5） |

`validity` は生成しない。BSAF の `time:` は原イベントの時刻であり、有効期間ではないためである（[BSAF 拡張 §7](../README.md#7-地域時刻信頼)）。

### 2.2 共通拡張の固定値

| 項目 | 値 |
|---|---|
| `status` | `Actual`。BSAF は運用種別を運ばないため、Bot が実情報として投稿したものとして扱う。Kikikuru Bot の固定リビジョンは `Control/Status` が「通常」以外の電文を投稿しない。JMA Bot の固定リビジョンは運用種別を確認していない（未確定事項） |
| `event` | 原投稿の `type:` に対する Bot 定義の表示ラベル（`filters[tag=type].options[].label`）と `lang: "ja"` |
| `onset` | §3 の表で指定した種類だけ、`time:` の値をそのまま用いる。`time:` が[コア §3.1](../../../README.md#31-共通時刻型-timestamp) の `Timestamp` として解釈できない場合は省略する |
| `glide` | 生成しない |

### 2.3 影響範囲

`target:` が Bot 定義上の都道府県であり、Bot がその都道府県ごとに投稿を分けている場合だけ、[`prefectures.json`](prefectures.json) の Geohash セルの和集合を `approximation: true` の `geo#area` として生成する。

| Bot | `target:` の意味 | 影響範囲 |
|---|---|---|
| Kikikuru Bot | `jp-<都道府県>`：その都道府県内の発表を集約した投稿 | `prefectures.json` の該当セル |
| Kikikuru Bot | `jp`：台風等の全国的な情報 | 生成しない。全国は台風の影響範囲ではないため |
| JMA Bot | 地方名（`jp-kanto` 等）：対象地域のうち最初の地方だけ | 生成しない。他の地方を含む情報で最初の地方だけを範囲とすると、範囲外とされた対象地域で配送順位が下がるため（[地理情報 §4.3](../../../geo/README.md#43-近似)） |

`prefectures.json` は、Natural Earth 1:10m Admin 1（バージョン 5.1.1、パブリックドメイン）の都道府県界を約 5 km 外側へ広げた領域を、Geohash 4 文字（都道府県あたり 64 セルを超える場合は 3 文字）のセルで覆ったものである。Natural Earth で沖縄県に含まれている奄美群島は鹿児島県へ移し、1:10m に含まれない沖ノ鳥島・南鳥島を東京都に加えている。生成手順は [`appview/bsaf/tools/prefecture_cover.py`](../../../../../appview/bsaf/tools/prefecture_cover.py) である。セルの和集合は都道府県より広く、近似に含まれる地点が警報の対象とは限らない。

### 2.4 本文からの抽出

一部の物理量（震源地名・マグニチュード・火山名・台風の気圧等）はタグになく、本文だけにある。§1 の固定リビジョンの投稿生成コードが決まった書式で出力する行に限り、§5 の正規表現に**行全体が一致する**場合だけ値を抽出する。一致しない場合はその項目を省略し、投稿の転載は続ける。部分一致や書式の推測で抽出してはならない。書式が変わった本文から誤った値を抽出し、本文と矛盾する構造化値を署名するためである。抽出元の本文は同じ投稿に署名付きで残るため、受信者は照合できる。

本文の行は `\n` で区切る。全角数字は Kikikuru Bot が半角に正規化しているため、正規表現は半角数字だけを受け付ける。

### 2.5 出典

```ts
{
  $type: "dev.jisaku.mosimo.public#source",
  document: { format: "bsaf-v1", senderId: <Bot の DID>, messageId: <原投稿の at-URI>, sentAt: <原 createdAt> },
  retrievedFrom: <収集に使った Jetstream の URL>,
  retrievedAt: <収集時刻>,
  converter: <転載サーバーの DID>,
  conversion: { rule: "dev.jisaku.mosimo.public.bsaf.hazard", revision: "1" },
}
```

`sentAt` は原 `createdAt` が `Timestamp`（UTC・末尾 `Z`）として解釈できる場合だけ、文字列を変えずに置く。

## 3. JMA Bot の対応表

共通拡張の列は `severity` / `urgency` / `certainty` / `responseTypes` の順である。「—」は省略を表す。

### 3.1 地震（`type:earthquake`）

種類別拡張は `hazard.earthquake`（`kind: "event"`）、大分類は `Geo`、`onset` は `time:`（発震時刻）とする。

| `value:` | 共通拡張 | 地域スケール |
|---|---|---|
| `1`、`2` | `Minor` / `Past` / `Observed` / — | `jp.jma.shindo` = 原値、`basis: "observed"` |
| `3`、`4` | `Moderate` / `Past` / `Observed` / — | 同上 |
| `5-`、`5+` | `Severe` / `Past` / `Observed` / — | 同上 |
| `6-`、`6+`、`7` | `Extreme` / `Past` / `Observed` / — | 同上 |

`value:` は投稿内の最大観測震度である。Bot は震度速報と震源・震度に関する情報を投稿し、いずれも観測値である。

### 3.2 津波（`type:tsunami`）

種類別拡張は `hazard.tsunami`、大分類は `Geo` とする。`onset` は生成しない（`time:` は発表時刻であるため）。

| `value:` | 共通拡張 | 地域スケール |
|---|---|---|
| `special-warning` | `Extreme` / `Immediate` / `Likely` / `Evacuate` | `jp.jma.tsunami` = `major-warning` |
| `warning` | `Severe` / `Immediate` / `Likely` / `Evacuate` | `jp.jma.tsunami` = `warning` |
| `advisory` | `Moderate` / `Expected` / `Likely` / `Avoid` | `jp.jma.tsunami` = `advisory` |
| `info` | `Minor` / `Unknown` / `Unknown` / — | — |

`info` には津波予報のほか、Bot が種類コードを判別できなかった電文も含まれるため、地域スケールを生成しない。

### 3.3 噴火（`type:eruption`）

種類別拡張は `hazard.volcano`（`phenomenon: "eruption"`）、大分類は `Geo` とする。

| `value:` | 共通拡張 |
|---|---|
| `special-warning` | `Extreme` / `Immediate` / `Likely` / `Evacuate` |
| `warning` | `Severe` / `Expected` / `Likely` / `Avoid` |
| `advisory` | `Moderate` / `Future` / `Possible` / `Avoid` |

本文の 1 行目が噴火速報（§5.1）である場合は、`certainty` を `Observed`、`urgency` を `Immediate` とする。噴火速報は噴火の発生を伝えるためである。噴火警戒レベルは本文から抽出できた場合だけ `jp.jma.volcanic-alert-level` に置く。`value:` から噴火警戒レベルを逆算してはならない。Bot はレベル 4 と 5 を同じ `special-warning` にしているためである。

### 3.4 降灰（`type:ashfall`）

種類別拡張は `hazard.volcano`（`phenomenon: "ashfall"`）、大分類は `Geo` とする。

| `value:` | 共通拡張 |
|---|---|
| `advisory` | `Moderate` / `Expected` / `Likely` / `Avoid` |
| `info` | `Minor` / `Future` / `Possible` / — |

### 3.5 南海トラフ地震臨時情報（`type:nankai-trough`）

種類別拡張は `hazard.earthquake`（`kind: "assessment"`）、大分類は `Geo` とする。

| `value:` | 共通拡張 | 地域スケール |
|---|---|---|
| `special-warning` | `Extreme` / `Future` / `Possible` / `Prepare` | `jp.jma.nankai-trough` = `alert` |
| `warning` | `Severe` / `Future` / `Possible` / `Prepare` | `jp.jma.nankai-trough` = `caution` |
| `advisory` | `Moderate` / `Future` / `Possible` / `Monitor` | — |

`advisory` は調査中と、Bot がキーワードを判別できなかった電文（調査終了を含む）の両方に使われるため、地域スケールを生成しない。

## 4. Kikikuru Bot の対応表

### 4.1 情報種別

| `type:` | 大分類 | 種類別拡張 |
|---|---|---|
| `heavy-rain-warning`、`heavy-rain`、`linear-rainband-warning` | `Met` | `hazard.weather`、`phenomena: ["rain"]` |
| `flood-warning` | `Met` | `hazard.weather`、`["flood"]` |
| `landslide-warning` | `Geo` | `hazard.weather`、`["landslide"]` |
| `storm-surge-warning` | `Met` | `hazard.weather`、`["storm-surge"]` |
| `wind-warning` | `Met` | `hazard.weather`、`["wind"]` |
| `wave-warning` | `Met` | `hazard.weather`、`["wave"]` |
| `snow-warning`、`significant-snowfall-warning` | `Met` | `hazard.weather`、`["snow"]` |
| `tornado-warning` | `Met` | `hazard.weather`、`["tornado"]` |
| `thunderstorm-warning` | `Met` | `hazard.weather`、`["thunderstorm"]` |
| `melting-snow-warning` | `Met` | `hazard.weather`、`["snowmelt"]` |
| `dense-fog-warning` | `Met` | `hazard.weather`、`["fog"]` |
| `dry-air-warning` | `Met` | `hazard.weather`、`["dry-air"]` |
| `avalanche-warning` | `Geo` | `hazard.weather`、`["avalanche"]` |
| `low-temperature-warning` | `Met` | `hazard.weather`、`["cold"]` |
| `frost-warning` | `Met` | `hazard.weather`、`["frost"]` |
| `icing-warning` | `Met` | `hazard.weather`、`["icing"]` |
| `snow-accretion-warning` | `Met` | `hazard.weather`、`["snow-accretion"]` |
| `weather-info` | `Met` | `hazard.weather`、`[]` |
| `typhoon` | `Met` | `hazard.cyclone` |
| `other-warning` | `Met` | なし |

`wind-warning` は暴風・暴風雪・強風をまとめた種別である。暴風雪かどうかはタグから分からないため `snow` を加えない。

### 4.2 値

| `value:` | 共通拡張 | 地域スケール |
|---|---|---|
| `level2` | `Moderate` / `Expected` / `Likely` / `Prepare` | `jp.jma.warning-level` = `2` |
| `level3` | `Severe` / `Expected` / `Likely` / `Prepare` | `jp.jma.warning-level` = `3` |
| `level4` | `Extreme` / `Expected` / `Likely` / `Evacuate` | `jp.jma.warning-level` = `4` |
| `level5` | `Extreme` / `Immediate` / `Likely` / `Shelter` | `jp.jma.warning-level` = `5` |
| `advisory` | `Moderate` / `Expected` / `Likely` / — | `jp.jma.warning-class` = `advisory` |
| `warning` | `Severe` / `Expected` / `Likely` / — | `jp.jma.warning-class` = `warning` |
| `special-warning` | `Extreme` / `Expected` / `Likely` / — | `jp.jma.warning-class` = `special-warning` |
| `cancelled` | `Minor` / `Past` / `Observed` / `AllClear` | — |
| `violent` | `Extreme` / `Expected` / `Observed` / — | `jp.jma.typhoon-intensity` = `violent` |
| `very-strong` | `Extreme` / `Expected` / `Observed` / — | `jp.jma.typhoon-intensity` = `very-strong` |
| `strong` | `Severe` / `Expected` / `Observed` / — | `jp.jma.typhoon-intensity` = `strong` |
| `tropical-storm` | `Moderate` / `Expected` / `Observed` / — | `jp.jma.typhoon-intensity` = `none` |
| `info` | `Minor` / `Unknown` / `Unknown` / — | — |

`cancelled` は警報・注意報の解除であり、気象庁 XML の取消ではない（[BSAF 拡張 §5.2.3](../README.md#523-jma-bot-と-kikikuru-bot)）。解除の投稿は旧投稿の拡散停止の根拠にならない。

### 4.3 種別による上書き

次の組では §4.2 の値の一部を上書きする。

| `type:` と `value:` | 上書き | 理由 |
|---|---|---|
| `tornado-warning` と `warning` | `urgency: Immediate`、`responseTypes: ["Shelter"]` | 竜巻注意情報は発表から約 1 時間を対象とし、屋内の安全確保を求める |
| `heavy-rain` と `warning` | `urgency: Immediate`、`certainty: Observed` | 記録的短時間大雨情報は観測・解析された大雨を伝える |
| `linear-rainband-warning` と `level5` | `certainty: Observed` | 線状降水帯の発生（直前予測は `level4`）を伝える |

## 5. 本文から抽出する項目

### 5.1 JMA Bot

| 種別 | 行の正規表現 | 生成する項目 |
|---|---|---|
| `earthquake` | `^\d{1,2}日\d{1,2}時\d{2}分ころ、(.{1,64})で地震$` | `hypocenter.name` = 1 番目の捕獲（`lang: "ja"`） |
| `earthquake` | `^マグニチュード：(\d{1,2}(?:\.\d)?)（(?:深さ約(\d{1,3})km\|深さ不明)）$` | 1 番目の捕獲が 0 より大きければ `magnitude.value` = 10 倍の整数。2 番目の捕獲が 0 より大きければ `hypocenter.depth` = km × 1,000,000 |
| `tsunami` | `^予想される最大高さ：(.{1,32})$` | `maxHeight.text`（`lang: "ja"`）、`basis: "forecast"`。捕獲が `^(\d{1,2}(?:\.\d)?)m$` に一致すれば `maxHeight.value` = m × 1,000 |
| `eruption`（2 行目） | `^(.{1,32})で噴火が発生$` | `volcano.name`。1 行目が `^\S+ 噴火速報$` に一致する場合に限る（噴火速報） |
| `eruption`（2 行目） | `^(.{1,32})（噴火警戒レベル([1-5])）$` | `volcano.name`、`jp.jma.volcanic-alert-level` = 2 番目の捕獲 |

マグニチュード 0 は Bot が不明を表す値である（固定リビジョンの解析処理が数値化できない値を 0 にする）。深さ 0 も Bot は「深さ不明」と出力するため、いずれも省略する。マグニチュードの種類（`Mj` 等）は本文にないため置かない。

### 5.2 Kikikuru Bot

| 種別 | 行の正規表現 | 生成する項目 |
|---|---|---|
| `typhoon`（1 行目） | `^🌀 台風第\d{1,2}号（(.{1,32})）$` | `name`（`lang: "ja-Kana"`） |
| `typhoon` | `^中心気圧：(\d{3,4})hPa$` | `pressure` = hPa × 100 |
| `typhoon` | `^最大風速：(\d{1,3})m/s(?:（最大瞬間\d{1,3}m/s）)?$` | `maxWind` = m/s × 1,000 |

台風番号は本文に年がなく一意にならないため、`designation` は生成しない。

## 6. 例

Kikikuru Bot の竜巻注意情報（`type:tornado-warning`、`value:warning`、`target:jp-chiba`）から生成する拡張である。原本文、BSAF 拡張、出典は省略し、`regions` は `prefectures.json` の `jp-chiba` の 24 セルのうち先頭 3 セルだけを示す。

```json
[
  {
    "$type": "dev.jisaku.mosimo.hazard",
    "status": "Actual",
    "category": ["Met"],
    "severity": "Severe",
    "urgency": "Immediate",
    "certainty": "Likely",
    "responseTypes": ["Shelter"],
    "event": { "text": "竜巻注意情報", "lang": "ja" },
    "scales": [{ "scheme": "jp.jma.warning-class", "value": "warning" }]
  },
  { "$type": "dev.jisaku.mosimo.hazard.weather", "phenomena": ["tornado"] },
  {
    "$type": "dev.jisaku.mosimo.geo#area",
    "regions": [
      { "shape": "geohash", "hash": "xn5q" },
      { "shape": "geohash", "hash": "xn5r" },
      { "shape": "geohash", "hash": "xn5w" }
    ],
    "approximation": true
  }
]
```

## 未確定事項

- Bot 定義・生成コードの更新を検出し、対応表の新しい版を作る運用。
- JMA Bot の地方単位の `target:` から範囲を作るために、全対象地方を示すタグを Bot 側へ提案するか。
- 対応表の機械可読な配布形式。
- JMA Bot が訓練・試験電文を投稿した場合に、`status: Actual` と区別する手段。

## 参照

- [BSAF v1 互換拡張](../README.md)
- [災害情報](../../../hazard/README.md)
- [osprey74/bsaf-jma-bot](https://github.com/osprey74/bsaf-jma-bot/tree/98071e7d579c8c67dea69dc9a325cdfcafa338b2) — `bot-definition.json`、`src/poster/formatter.ts`
- [osprey74/bsaf-kikikuru-bot](https://github.com/osprey74/bsaf-kikikuru-bot/tree/76ec562305cddd10da3b91828033dd6066c99e74) — `bot-definition.json`、`src/bsaf/*Mapper.ts`
- [Natural Earth 1:10m Cultural Vectors, Admin 1 – States, Provinces](https://www.naturalearthdata.com/downloads/10m-cultural-vectors/10m-admin-1-states-provinces/) — バージョン 5.1.1、パブリックドメイン
