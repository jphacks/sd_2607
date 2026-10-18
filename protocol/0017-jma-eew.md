# 0017: 気象庁緊急地震速報の著者拡張と投稿への変換

状態: draft-01 — 緊急地震速報の警報・予報への対応範囲と、地域別の単独可読な投稿化は合意済み。型とマッピングを具体化した仕様草案であり、地域粒度の具体化・更新の配送への適用・即時通知条件には未決事項を含む。正式Lexicon JSONや取得サービスの実装を意味しない。

## 1. 適用範囲

気象庁防災情報XMLの `緊急地震速報（警報）`（VXSE43）と `緊急地震速報（予報）`（VXSE44）を対象とする。原電文の管理部・ヘッダ部・震源・予測・付加文を、本プロトコルの任意の著者拡張へ対応付ける。

初版では `緊急地震速報（地震動予報）`（VXSE45）、リアルタイム震度電文、配信テスト専用電文、津波警報等は対象外とする。通常電文の訓練・試験という運用種別は保持し、実災害向けと混同しない。観測震度・観測長周期地震動は [0018](0018-jma-earthquake.md) に分離する。

1投稿は1レコード・1 commit・1 Nostrイベントとする。原XMLは取り込み側で保存し、中継者にXML解析や原文取得を要求しない。本書はデータと処理上の意味を定め、検証アルゴリズム・適合試験・テストベクトルは定めない。

## 2. 識別と独立性

`$type` を `dev.oriom.mosimo.defs#jmaEew` とする。各投稿は管理部・ヘッダ部とその投稿が伝える予測等を自分の拡張内に持ち、他の投稿や別の共通ヘッダ拡張の取得を解釈の前提にしない。

- `jmaEew`: 原電文の種別・震源・予測値・状態を担当する。
- `source`: 原文参照、取得URL・取得時刻・変換者・変換規則等の追跡を担当する。
- `area`: 共通の配送判断用の地域表現を担当する。
- `validity`: 意味を確定できる内容有効期間を担当する。予測到達時刻や配送期限の別名ではない。

`source`・`area`・`validity`・`identity` の存在を本拡張の基本表示の条件にしない。拡張追加のためにNostrのkindやBPv7の必須ブロックを増やさない。

## 3. データ型（提案）

TypeScript風の説明用表記である。本節の共通型は0018でも再利用するが、型の共有は投稿間・拡張間の実行時依存を意味しない。XML日時・数量・コード・階級は原文字列を保持し、floatを使わない。共通のUTC `Timestamp` や整数座標とは別である。

### 3.1 共通の原値と管理情報

```ts
type JmaValue = {
  value: string;
  type?: string;
  unit?: string;
  description?: string;
  condition?: string;
  nil?: boolean;
};
type JmaCode = { value: string; type?: string };
type JmaNamedCode = { name: string; code: JmaCode };

// integerはatprotoの整数。位置は原電文内のゼロ始まり位置
type JmaSelection =
  | { kind: "whole" }
  | { kind: "region"; path: [integer, integer] };

type JmaMessage = {
  control: {
    title: string;
    dateTime: string;
    status: string;
    editorialOffice: string;
    publishingOffice: string;
  };
  head: {
    title: string;
    reportDateTime: string;
    targetDateTime?: JmaValue;
    validDateTime?: JmaValue;
    eventID: string;
    infoType: string;
    serial: string;
    infoKind: string;
    infoKindVersion: string;
    headline?: string;
  };
};
```

`Control`・`Head` の同名要素を対応するフィールドへ格納し、`headline` は `Head/Headline/Text` とする。`Head/Title` と `Control/Title` を混同しない。`serial` は空文字列も保持し、番号へ強制変換しない。`InfoKindVersion` は気象庁の原形式の版であり、本拡張の版や変換規則の版ではない。

`JmaValue.value` はXML要素の文字列値、他の項目は対応する属性とする。`xsi:nil="true"` または `"1"` は `nil: true` として保持し、省略・空文字列・nil・`NaN` を区別する。XMLの文字参照の綴り・属性順・接頭辞まで復元する型ではない。任意項目は原要素がなければ省略する。コードの `type` は原属性、または `CodeDefine/Type` の適用先に対応するコード体系とする。先頭ゼロを削らない。

### 3.2 震源と精度

```ts
type JmaHypocenter = {
  name: string;
  code?: JmaCode;
  coordinates?: JmaValue[];
  detailedName?: string;
  detailedCode?: JmaCode;
  nameFromMark?: string;
  markCode?: JmaCode;
  direction?: string;
  distance?: JmaValue;
  source?: string;
};
type JmaEarthquake = {
  originTime?: JmaValue;
  arrivalTime?: JmaValue;
  condition?: string;
  hypocenter?: JmaHypocenter;
  magnitude?: JmaValue;
};
type JmaAccuracyValue = { value: string; rank: string; rank2?: string };
type JmaEewEarthquake = JmaEarthquake & {
  hypocenter?: JmaHypocenter & {
    reduceName?: string;
    reduceCode?: JmaCode;
    landOrSea?: string;
    accuracy?: {
      epicenter: JmaAccuracyValue;
      depth: JmaAccuracyValue;
      magnitudeCalculation: JmaAccuracyValue;
      numberOfMagnitudeCalculation: string;
    };
  };
};
```

| 原XML（`Body/Earthquake`以下） | 配置と意味 |
|---|---|
| `OriginTime`・`ArrivalTime`・`Condition` | `originTime`・`arrivalTime`・`condition`。地震発生・検知時刻と震源要素の補足 |
| `Hypocenter/Area/Name`・`Code`・`jmx_eb:Coordinate` | `hypocenter.name`・`code`・`coordinates`。ISO 6709座標の原文字列と説明 |
| `Hypocenter/Area/ReduceName`・`ReduceCode`・`LandOrSea` | 同名のcamelCase項目 |
| `Hypocenter/Accuracy` | `hypocenter.accuracy`。値・rank・rank2・使用観測点数を保持 |
| `jmx_eb:Magnitude` | `magnitude`。種別・条件・説明を伴う値 |

共通の `detailedName` 等は0018の観測情報用であり、緊急地震速報で欠けている情報を補う要件ではない。`source` は原XMLの `Hypocenter/Source` であり、共通の出典拡張ではない。

`condition: "仮定震源要素"` は重要な条件である。PLUM法等の仮定による位置・深さ・マグニチュードを通常の確定震源と表示しない。`accuracy` の `NaN` とランクを落とさず、ランクの大きさだけで独自の信頼スコアを作らない。部内利用・変更可能とされるランク値から独自の警報判断を行わない。地域の到達時刻と `earthquake.arrivalTime` は別の項目である。

### 3.3 予測と警報対象

```ts
type JmaForecastRange = { from: string; to: string };
type JmaEewArea = JmaNamedCode & {
  category: JmaNamedCode;
  forecastInt: JmaForecastRange;
  forecastLgInt?: JmaForecastRange;
  arrivalTime?: JmaValue;
  condition?: string;
};
type JmaEewPref = JmaNamedCode & { areas: JmaEewArea[] };
type JmaEewForecast = {
  forecastInt: JmaForecastRange;
  forecastLgInt?: JmaForecastRange;
  appendix?: {
    maxIntChange?: string;
    maxLgIntChange?: string;
    maxIntChangeReason: string;
  };
  prefs?: JmaEewPref[];
};
type JmaEewHeadlineInformation = {
  type: string;
  items: {
    kind: JmaNamedCode;
    lastKind: JmaNamedCode;
    codeType: string;
    areas: JmaNamedCode[];
  }[];
};
type JmaEewExtension = {
  $type: "dev.oriom.mosimo.defs#jmaEew";
  message: JmaMessage;
  selection: JmaSelection;
  headlineInformation?: JmaEewHeadlineInformation[];
  body: {
    earthquake?: JmaEewEarthquake;
    forecast?: JmaEewForecast;
    text?: string;
    warningComment?: { text: string; code: JmaCode };
    nextAdvisory?: string;
  };
};
```

| 原XML | 配置 |
|---|---|
| `Head/Headline/Information` | `headlineInformation`。`type`、`Item`配列、`Kind`・`LastKind`、`Areas/@codeType`と地域を保持 |
| `Body/Intensity/Forecast` | `body.forecast` |
| `ForecastInt/From,To`・`ForecastLgInt/From,To` | 原階級の上下限。全国の最大値と地域の最大値を区別 |
| `Forecast/Appendix` | `forecast.appendix`。変化量区分と変化理由 |
| `Forecast/Pref/Area/Category/Kind` | `forecast.prefs[].areas[].category` |
| `Forecast/Pref/Area/ArrivalTime,Condition` | 地域別の `arrivalTime`・`condition` |
| `Body/Text`・`NextAdvisory` | `body.text`・`nextAdvisory` |
| `Body/Comments/WarningComment/Text,Code`と`@codeType` | `body.warningComment`。`code.type`に体系を保持 |

XMLの繰り返しは文書順の配列にする。同じ府県が複数の `Pref` として現れても、コードをキーにした辞書へまとめない。予測部の `CodeDefine` は各コードの適用位置に従って `code.type` に反映する。見出しの地域コード体系も、本文の府県・地域コードと混同しない。

`forecastInt` の `5-`・`5+` 等は原階級、上限の `over` は「程度以上」であり、算術値や無限大へ置換しない。`不明` は震度0ではない。長周期地震動階級は震度とは別尺度である。予測や地域一覧が存在しないケースもあり、架空の予測・地域を補わない。見出しの警報対象と本文の予測値の掲載範囲は役割が異なるため、自動的に同じ集合へ潰さない。

地域の `category.code` は警報／予報だけでなく、主要動の到達状況・PLUM法による予測かを示す。PLUM法では `arrivalTime` が「その階級震度を初めて予測した時刻」を表すため、すべての値を未来の到達予想時刻としてカウントダウンに使ってはならない。`condition: "既に主要動到達と推測"` と到達時刻の欠落も、その条件のまま保持する。採用するコード表の版と具体的なコード別動作は §9 の未決事項である。

`nextAdvisory` は原文の次回発表予定である。「この情報をもって、緊急地震速報：最終報とします。」は当該地震IDでの速報発表の終了を表す。警報の取消・危険の解消・配信成功を表すフラグに変換しない。

## 4. 地域別の投稿単位と本文

### 4.1 地域別の独立投稿（合意済み）

地域別に必要な共通文脈を持つ、単独で読める投稿を基本とする。1地域の投稿は1レコード・1 commit・1 Nostrイベントで完結し、全体投稿や別地域の投稿を取得してから組み立てる断片にはしない。管理部・ヘッダ部、原電文に存在する震源と精度、主要な共通文・注意事項・条件をそれぞれの投稿内に保持する。地域別にすることは、取り込み側が署名前に行う意味上の分割であり、中継者が署名済みイベントを分解することではない。

原電文全体の最大予測値・見出しと、選択地域の値・警報対象を区別する。地域別投稿を原電文全体の受領・再現と扱わず、届かない地域や掲載のない地域の安全を推測しない。地域分割の追加だけで、別種別の情報の取消や自動置換を認めることはない。

### 4.2 選択位置と格納方法（具体化案）

初期の具体化案は、`Body/Intensity/Forecast/Pref/Area` の細分区域ごととする。`selection.kind` を `region`、`path` を `[原Prefの位置, そのPref内の原Areaの位置]` とする。位置は原XMLの同名兄弟要素をゼロ始まりで数えた値であり、抽出後の配列位置や別電文の地域識別子ではない。

`body.forecast.prefs` は選択した `Pref` 1件、その `areas` は選択した `Area` 1件を保持する。親の名称・コード、地域の予測・カテゴリー・到達条件を一緒に残す。原電文の `forecast` 直下の最大値・変化情報、管理情報・見出し・`headlineInformation` は原電文全体の文脈として保持し、選択地域だけの集計値に再計算・改名しない。共通文に他地域への言及があっても、その記述だけで投稿の選択範囲を広げない。

例えば `selection: { kind: "region", path: [2, 0] }` は原電文の3番目のPref内の最初のAreaである。抽出後の `prefs[0].areas[0]` がその内容を持つ。異なる電文で同じ `path` が同じ地域になる保証はなく、続報の対応はコード体系・原情報の関係と分離して扱う。

取消や、地域別予測を持たない最大予測値だけの情報は `selection: { kind: "whole" }` として原電文の通知・対象内容を1投稿で伝える。存在しない地域を補って分割しない。`whole` は配信成功や原XML全量の保持を意味しない。粒度の具体化、さらに小さい地域への分割とサイズ超過時の扱いは §9 に残す。

### 4.3 本文

本文には、取得・変換者による気象庁情報の転載であること、警報／予報、原発表時刻、通常／訓練／試験、情報番号、訂正／取消、対象地域、予測階級と条件、主要な指示を明示する。必要に応じて仮定震源・既到達・予測手法による時刻の意味・最終報も伝える。コード列だけで本文を代替せず、未知拡張の端末でも通知の趣旨が分かるようにする。

本文には選択地域を明示し、原電文全体の最大値を掲載する場合は「原電文全体」、地域の値はその地域の予測と分かるようにする。地域以外の値がないことを、原電文に存在しなかったことや欠測と同一視しない。

取消電文では今回の管理情報・ヘッダ・取消本文を保持し、旧予測を現在の予測として引き継がない。予報や警報の名称を独自に入れ替えない。完全に決定的な本文の定型・要約規則は未確定である。

## 5. 続報・訂正・取消

### 5.1 原電文の運用

公式解説の共通部・共通別紙による対象電文の規則は次のとおりである [J1]。

- 「独立した情報単位」は **`Control/Title` + `Control/Status` + `Head/EventID`** で区別する。編集官署の切替があり得るため、官署名をこのキーに追加しない。
- 同種の最新情報の検索には **`Control/DateTime`** を用いる。`Serial`、取得順、Nostr時刻、投稿作成時刻だけで代用しない。
- `発表` には続報も含まれる。`訂正` はその独立単位で直前に発表されている管理部時刻が最も新しい電文の訂正、`取消` は独立単位全体の取消である。
- 取消では番号を更新せず、訂正では訂正対象の最新情報の番号を記載する。番号の一致だけで同じ電文とはしない。
- 警報と予報は別の情報名称である。片方の取消を、同じ地震IDのもう片方や地震観測情報の取消へ広げない。

`EventID` は地震発生時刻の証明ではなく、同じ物理的地震でも番号が一貫しない場合がある。同じ番号だから全種別を置換したり、近い時刻だから別番号を同一と確定したりしない。

### 5.2 本プロトコルの配送への対応（提案）

続報・訂正・取消は新しい署名付き投稿として発行し、既存投稿を編集しない。認可済みの同じ独立単位の新情報により、旧版の新たな広告・転送開始を止め、未着対象についても後着時の再拡散を抑える案とする。取消は同じ独立単位の旧投稿群に適用し、新通知自身や別の情報名称には広げない。この配送案を適用する場合、地域分割された旧投稿群も原情報の対象関係に含める。`selection.path` の一致した1地域だけへ取消対象を狭めず、逆に `path` の一致だけで別電文・別種別へ停止を広げない。地域別の新投稿1件を受信したことは、新しい原電文の全地域の受領を意味しない。

拡散停止には [0009 §5.1](0009-identity-attestations.md#51-公的情報の更新取消の認可合意済み) のルートへのチェーン・用途と範囲・対象投稿の由来の確認が必要である。一般の取り込み先への信頼、自己申告の `EventID`、URL、Nostr署名だけでは他者の投稿を停止しない。原XMLの名称や取得経路を気象庁の機関署名と表示しない。

原電文の運用と配送上の置換は別であり、この対応案・投稿間参照・欠落履歴や同時刻の競合・取消後再発表・停止記録の保持は未確定である。停止は履歴削除やNIP-09ではなく、未対応端末までの即時停止も保証しない。

## 6. 地域・時刻と即時通知

- 震源は影響範囲でも投稿者の端末位置でもない。`hypocenter` を `origin` や任意半径の共通 `area` へ自動変換しない。
- コード体系と境界を解決できる地域について、投稿の対象全体を覆う共通 `area` を提供できる。一部だけを切り捨てず、全体を解決できなければ省略する。近似は [0004 §5.1](0004-geography.md#51-原情報全体を覆う近似合意済み) に従う。地域別投稿では選択地域全体を共通領域の対象とし、文脈として残した全国見出し・他地域の記述で範囲を拡大しない。原電文全体を扱う通知の地域表現や境界データの版は別途定める。
- 原発表時刻、管理部時刻、発生・検知時刻、地域の予測時刻、投稿の作成時刻、配送期限を区別する。`ArrivalTime` を `validity.endsAt` や `deliveryExpiresAt` に無条件にコピーしない。
- 遅着した情報を受信時点からの新たな揺れの予告として扱わない。過去の情報の閲覧・保存と、即時の音声・振動・カウントダウン等を分離する。時刻・地域・予測手法を解釈できない場合に残り秒数を創作しない。
- 鮮度の閾値、時計不明・時計ずれ、重複通知、続報での再通知、取消の通知方法は未確定である。配送期限内であることだけを即時警報として動作させる条件にはしない。

## 7. 取得・配送と部分対応

気象庁の公開PULL型の電文一覧は、緊急地震速報等が公開されないことを明記している [J5]。対象の緊急地震速報は公開Atomから取得できるものとして設計せず、対応する別の配信経路・契約・公開再配布条件を取り込み側で確保する。本拡張への対応だけで取得経路が得られたり低遅延取得が保証されたりはしない。公開PULLの高頻度Atomは毎分更新で停止・遅延もあり [J4]、それを頻繁にポーリングしても対象EEWを取得できるわけではない。URLの推測取得や公開サーバーへの過剰なポーリングを行わない。BLE・DTNの配送も即時到達や揺れ前到達を保証せず、既存の緊急警報手段を置き換えるものではない。

本拡張は自己完結タグ `mosimo.oriom.dev:ext:dev.oriom.mosimo.defs#jmaEew` で運ぶ。値は `$type` を除く全フィールドのatproto CBORを通常Base64・paddingなしで表し、メッシュの既知変換では生バイト列にする（[0012](0012-bindings.md)）。標準タグへの不正確な重複投影は行わない。

32 KiBの上限は本文・commit等を含むイベントCBOR全体に適用する。中継者は署名済みの地域・条件・拡張を削って収めない。取り込み時に上限内で意味を保って表せない情報は、未定義の分割や黙った切捨てで投入せず、扱いを保留する案とする。

非対応端末は本文と配送期限で通常の中継・表示ができる。対応端末は予測・地域・時刻等を利用できるが、原文にない独自の予測や公的警報を発表したことにしない。未知の拡張・既知型内の未知フィールドは保持する。未知のXML要素まで汎用XMLバッグとして運ぶものではなく、意味に影響する未対応構造は取り込み側で扱いを保留する。

## 8. 処理の流れ

```text
原電文取得・保存
  → 公開条件と対象種別の判断
  → 管理情報・震源・予測・条件・付加文を型へ対応付け
  → 投稿単位ごとの本文・任意の共通地域／期間・配送期限を生成
  → 1投稿＝1レコード・1 commit・1 Nostrイベントとして署名
  → 共通CBOR・BPv7による配送とNostrへの公開
```

受信側は本文だけの表示、拡張を利用した詳細表示、認可を伴う旧版拡散停止、即時警報動作を別の能力として扱う。通常の中継のために全ての能力を実装することは要求しない。

## 9. 未決事項

- 地域別の独立投稿化は合意済み。細分区域単位と `selection` の具体化案、1地域でも32 KiBを超える場合の小分け・扱いを確定する。
- 対象となる `InfoKindVersion` とコード表・辞書の固定版、コード別の時刻解釈、未知版の扱い。
- 決定的な本文生成と、訓練・試験の投入・表示・通知条件。
- 同一独立単位内の更新を新規拡散停止へ対応付ける具体規則、投稿間参照、索引・保持・相反更新。
- 即時通知の鮮度・時計・対象地域・通知重複の規則と、権限・利用条件に応じた配信経路。
- 共通 `area` への対象の投影、境界データと近似区分、正式Lexiconと資源上限。

## 10. 参照と採用資料

- [J1: 気象庁 電文毎の解説資料セット（2026-10-09公開セット）](https://xml.kishou.go.jp/jmaxml_20261009_Manual(pdf).zip) 内「地震火山関連_解説資料.pdf」（表紙2025年12月）。共通部Ⅰ、共通別紙ア・イ・ウ、Ⅱ.21を参照。セットの更新日と内包文書の版は区別する。
- [J2: 公式サンプル（2026-09-17セット）](https://xml.kishou.go.jp/jmaxml_20260917_Samples.zip)。VXSE43・VXSE44の構造、属性、原語彙を参照。サンプルの地震は運用中の実情報として扱わない。
- [J3: XMLフォーマット技術資料一覧](https://xml.kishou.go.jp/tec_material.html)。共通フォーマットVer.1.3、コード表・辞書・スキーマへの入口。
- [J4: XML形式電文の公開（PULL型）](https://xml.kishou.go.jp/xmlpull.html)。更新周期・遅延・利用条件。
- [J5: 公開XML電文一覧（2026-08-06現在）](https://xml.kishou.go.jp/xmllist.pdf)。緊急地震速報等の非公開の注意書き。
- [0013: 公的情報の取り込み](0013-public-information.md)
- [0018: 地震観測情報](0018-jma-earthquake.md)
