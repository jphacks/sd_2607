# 0017: 気象庁緊急地震速報の著者拡張と投稿への変換

状態: draft-01 — 緊急地震速報の警報・予報への対応範囲と、地域別の単独可読な投稿化は合意済み。型とマッピングを具体化した仕様草案であり、適度な地域粒度、全国概況の併記、署名前の低優先項目の省略を採用する。府県を基本・細分区域を最小とする具体化、更新の配送への適用・即時通知条件には未決事項を含む。正式Lexicon JSONや取得サービスの実装を意味しない。

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
  | { kind: "region"; path: integer[] };

type JmaNationalOverview = { prefectures: JmaNamedCode[] };
type JmaReduction = { omitted: string[] };

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
  nationalOverview?: JmaNationalOverview;
  reduction?: JmaReduction;
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

### 4.2 適度な粒度と選択位置

細かく分けるほど望ましいとはせず、地域の関係・共通条件を保てる適度な単位を使う。初期の具体化案は府県単位を基本とし、32 KiBに収まらない場合だけ原 `Pref/Area` の細分区域まで分ける。市町村・観測点や任意の地理メッシュへはさらに分割しない。緊急地震速報の `Pref` は府県予報区であり、行政上の都道府県と常に一対一とは限らない。

`selection.kind` は `region`、`path` は `[原Prefの位置]` または `[原Prefの位置, そのPref内の原Areaの位置]` とする。1要素は府県単位、2要素は細分区域であり、空配列や3要素以上は本プロファイルでは用いない。位置は原XMLの同名兄弟要素をゼロ始まりで数えた値で、抽出後の配列位置や別電文の地域識別子ではない。

`body.forecast.prefs` は選択したPrefだけを1件保持する。府県単位ではその原Prefの全Area、細分区域単位では選択したAreaだけを保持し、親の名称・コード、地域の予測・カテゴリー・到達条件を残す。原電文の最大値・変化情報・見出しは原電文全体の文脈であり、選択範囲だけの値へ再計算・改名しない。同じ府県名のPrefが原文で繰り返されても、それぞれ原位置を保持し、無断で統合しない。

例えば `selection: { kind: "region", path: [2] }` は原電文の3番目のPref全体、`path: [2, 0]` はその最初のAreaである。抽出後はいずれも `prefs[0]` 内に格納する。異なる電文の `path` の一致は、同一地域・同一投稿・更新関係の根拠にはしない。

取消や、地域別予測を持たない最大予測値だけの情報は `selection: { kind: "whole" }` として通知・対象内容を1投稿で伝える。存在しない地域を補わず、`whole` を原XML全量の保持や配送完了の意味にしない。

### 4.3 全国概況とサイズ調整

全国の関連する府県を把握するため、原電文に該当する府県一覧がある場合、各地域投稿に小さな `nationalOverview` を併記する。サイズ超過時だけ、下記の `nationalOverview` カテゴリによる明示付き省略を認める。`prefectures` は原電文の予測部に掲載された府県予報区の名称・コード体系・コードの一覧とし、同じ体系・コードの重複は初出順でまとめる。観測情報では0018に定める観測部の府県一覧を使う。原電文に一覧がなければ推測で作らない。

この一覧は「原電文に掲載された府県等」の概況であり、被害の発生、全住民への警報、全国の影響範囲の完全把握、掲載されない地域の安全を表さない。府県単位の全体像は残すが、全国分の詳細予測・観測値を各投稿に必ず複製するものではない。基本本文にも一覧を簡潔に示し、行政区画と異なる場合は原名称を用いる。共通 `area` は選択地域のままとし、全国概況で配送地域を広げない。

必要な共通文脈を含めても大きい場合、取り込み側は署名前に低優先の詳細を省ける。原XMLは保存し、省略は `reduction.omitted` と本文の両方に明示する。受信側は省略を欠測・原情報の不存在・値0と扱わない。省略カテゴリは次のとおりとし、本書にないカテゴリの意味を推測しない。

| `omitted` の値 | 選択範囲内で省略した項目 |
|---|---|
| `periodSpectra` | 0018の観測点の `sva`・`svaPerPeriod` |
| `periodClasses` | 0018の観測点の `lgIntPerPeriod` |
| `stationDetails` | 0018の個別観測点の `stations`。上位の最大値・条件は保持 |
| `headlineDetails` | 本書の `headlineInformation` の構造化一覧。重要な警報対象・条件は選択地域の情報と本文に保持 |
| `nationalOverview` | 全国概況の構造と本文の対応一覧。原文に一覧がない場合とは区別 |

`periodSpectra`・`periodClasses`・`stationDetails` は選択範囲内の対応項目全体、`headlineDetails`・`nationalOverview` は投稿に同梱した原電文全体の対応フィールドの全体を省略する。`nationalOverview` では本文の対応一覧も省く。一部の高い値の地点だけを残した標本や、黙った件数切捨ては作らない。省略がなければ `reduction` を省略し、少なくとも1カテゴリを省いた場合にだけ空でない `omitted` を付ける。本文でも「観測点詳細省略」等、どの情報が省略されたかを短く示す。

府県単位で収まらないときはまず細分区域まで分け、その最小単位でも超える場合に低優先項目を減らす具体化案とする。緊急地震速報では `headlineDetails`、次に必要なら `nationalOverview` を省略候補とする。ただし、重要な対象・指示・適用条件をそれらだけが表す場合は落とさず、本文等へ意味を残せることを必要とする。観測情報の詳細の順序は0018に従う。

警報／予報・訓練等の区別、原電文識別、訂正取消、必要な由来・認可の情報、震源の主要値と精度・仮定条件、選択地域の予測値・到達条件、重要な指示は省略しない。全国概況は詳細より優先して残すが、安全上重要な本文よりは優先しない。共通必須項目・commit・署名を削って収めず、必要な意味を残しても収まらなければその投稿化を保留する。中継者による署名後の項目削除は引き続き禁止する。

### 4.4 文字列の安全な解釈

名称は構造化フィールドの文字列として扱い、改行・空白・タブ・区切り記号をプロトコル上の構造区切りと推測しない。`ReduceName` 等を含む名称に改行があるだけで、正常に構造を読める原電文や投稿全体の処理を失敗させない。XMLパーサー・JSON/CBORの文字列として解釈し、行分割・正規表現による擬似XML解析や名称の完全一致だけによるコード判定に依存しない。

原文字列は署名対象の拡張に保持する。1行の表示・音声通知・外部配信APIの名称欄には派生した表示用文字列を用い、CR・LF・TAB・Unicodeの行／段落区切りを空白に置き換え、連続する表示用空白を整理できる。指示本文全体の改行まで無条件に削除しない。出力先の正式なエスケープ・長さ・文字種制約に従い、ログやCSV等へ文字列連結で埋め込まない。整形が必要でも元の署名付きフィールドを書き換えない。

名称を整形しても出力先に渡せない場合、対応するコードと固定したコード表による名称を利用できる。解決できなければ「震央名を表示できません」等の代替表示とし、元の名称だけの問題で他の警報内容まで失わせない。コードが未知・名称と不一致の場合に推測で既知の地域を割り当てない。これは個別の通信事業者の事案の原因を断定する規則ではない。

### 4.5 M9.9の表示上の扱い

本緊急地震速報プロファイルでは、数値として9.9のマグニチュードを表示する場合に「M9.9以上」とする。ただし、これは本プロトコル独自の上限値を想定した表示方針であり、確認した気象庁XML仕様の定義として「以上」を付与したものではない。気象庁の2026-10-16の報道発表も「9.9と過大に推定」と説明しており、真の規模の下限を示した発表ではない [J6]。原 `value`・`type`・`condition`・`description` はそのまま保持し、機関が「以上」と発表したとの表示や、変換後の値への置換はしない。

詳細表示では「本プロファイルでは上限値として表示。原配信値は9.9」と分かるようにする。原値9.9だけで真の規模が9.9以上と確認できたことにはならず、誤推定・訂正・仮定震源の可能性も残る。`NaN`、不明、仮定条件、原文の説明を優先して併記し、名称や説明文の文字列中の「9.9」を機械的に置換しない。この表示方針を0018の観測情報や他のマグニチュード種別へ無条件に広げず、数値の再計算、予測震度の引上げ、配送優先権の根拠にはしない。

### 4.6 本文

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

32 KiBの上限は本文・commit等を含むイベントCBOR全体に適用する。中継者は署名済みの地域・条件・拡張を削って収めない。取り込み側は §4.2–§4.3 の適度な分割・明示付き省略を用い、それでも上限内で必要な意味を保てない投稿は保留する。未定義の細分化や重要文の黙った切捨ては行わない。

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

- 適度な粒度・低優先項目の明示付き省略・全国概況の併記を採用。府県を基本、細分区域までの追加分割と `selection`・`reduction` の具体化案、フィールド上限・本文生成の詳細を確定する。
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
- [J6: 2026-10-16発表の緊急地震速報について（第2報）](https://www.jma.go.jp/jma/press/2610/16c/202610161730.html)。9.9を過大推定と説明。上限値の定義や名称の改行による特定事業者の配信障害の原因を示す資料ではない。
- [0013: 公的情報の取り込み](0013-public-information.md)
- [0018: 地震観測情報](0018-jma-earthquake.md)
