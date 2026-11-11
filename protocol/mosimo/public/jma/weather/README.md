# 気象庁 気象防災情報 拡張

識別子: `dev.jisaku.mosimo.public.jma.weather`

## 目的

本プロトコルは、気象庁防災情報 XML の気象警報・注意報、土砂災害警戒情報、竜巻注意情報、記録的短時間大雨、指定河川洪水予報、台風、線状降水帯、顕著な大雪、気象の見通し・解説を本プロトコルの投稿へ変換する拡張を定義する。定義する範囲は、管理情報・地域ごとの現象と状態・構造化した気象内容の部分集合を保持する型、種別別の対応と非対応の境界、地域別の投稿単位と `limitations` による部分対応の明示、時刻、続報・解除・取消の扱いである。

保証するのは、この型だけで原種別・運用種別・原日時・地域ごとの状態と、対応を宣言した部分集合を解釈できることである。保証しないのは、全気象電文・全要素への対応、津波・火山・降灰・南海トラフ等の電文、河川水位等の時系列詳細、台風の予報円・風域の幾何変換、全種別の自動更新系列・取消対象の確定である。

## 前提

| 識別子 | 文書 | 依存する理由 |
|---|---|---|
| `dev.jisaku.mosimo` | [../../../README.md](../../../README.md) | 投稿単位、`Timestamp`、拡張の自己完結タグ、32 KiB 上限 |
| `dev.jisaku.mosimo.public` | [../../README.md](../../README.md) | 取り込みの信頼モデル、出典 `#source`、更新による置換の認可 |
| `dev.jisaku.mosimo.public.jma` | [../README.md](../README.md) | 共通型 `JmaMessage`・`JmaValue`・`JmaCode`、取得経路、運用種別、時刻、原文ハッシュ、文字列の安全な解釈 |
| `dev.jisaku.mosimo.geo` | [../../../geo/README.md](../../../geo/README.md) | 影響範囲 `#area` |
| `dev.jisaku.mosimo.validity` | [../../../validity/README.md](../../../validity/README.md) | 情報の有効期間 |

## 1. 入力の分離

| 入力 | 原値の格納と対応範囲 |
|---|---|
| 実際に取得・保存した対象 JMA XML | 本拡張と `source`。原種別・版ごとの対応検証が必要 |
| Kikikuru Bot 等の既存 BSAF 投稿 | 原レコードを保存し [BSAF 拡張](../../bsaf/README.md)。Bot 本文・タグだけから本拡張を生成してはならない |
| BSAF 投稿から辿ったリンクの XML | XML を別途取得・照合・保存して初めて独立した XML 出典として変換できる。BSAF 投稿と XML の同一性をタグから推定しない |

`source:jma`、`value:cancelled`、Bot の名前や説明は JMA XML の存在、公式署名、地域の状態、取消権限の証拠ではない。Bot 本文から本拡張を合成すると、原 XML にない構造化された状態を機関発表として配送するためである。

## 2. 原文の保存と出典

取り込み側は取得した原 XML 全体を保存し、各投稿に `source` を付ける。`source.document.messageId` は取得電文の絶対 URL 等、`senderId` は原 `PublishingOffice`、`revision` は原 `Serial` の原値、`originalDigest` は [気象庁 XML 共通 §9](../README.md#9-原文の保存とハッシュ) の SHA-256 とする。原発表官署の名称、取得・変換者の署名、機関の認証を分離する。

## 3. データ型

`integer` は atproto 整数。`JmaValue`・`JmaCode`・`JmaMessage` は共通型を再利用する。先頭ゼロ、空文字列、省略、nil、条件・単位・説明を区別する。

### 3.1 管理情報・対象と状態

```ts
type JmaWeatherMessage = JmaMessage & {
  head: JmaMessage["head"] & {
    targetDTDubious?: string;
    targetDuration?: string;
  };
};
type WeatherArea = {
  name: string;
  code?: JmaCode;
  codeType?: string;
};
type WeatherKind = {
  name?: string;
  code?: JmaCode;
  condition?: string;
  status?: string;
  lastKind?: { name?: string; code?: JmaCode };
};
type WeatherHeadlineItem = {
  informationIndex: integer;
  itemIndex: integer;
  informationType?: string;
  kinds: WeatherKind[];
  areas: WeatherArea[];
};
type WeatherWarningItem = {
  warningIndex: integer;
  itemIndex: integer;
  warningType?: string;
  kinds: (WeatherKind & { properties?: WeatherProperty[] })[];
  areas: WeatherArea[];
  stations?: { name: string; code?: JmaCode; location?: string }[];
  changeStatus?: string;
  fullStatus?: string;
  editingMark?: string;
};
```

| 原 XML | 配置 |
|---|---|
| `Control`・`Head` の同名要素 | `message.control`・`message.head` |
| `Head/Headline/Text` | `message.head.headline` |
| `Head/TargetDTDubious`・`TargetDuration` | `message.head.targetDTDubious`・`targetDuration` |
| `Head/Headline/Information/Item` | `headlineItems`。`informationType` は親 `Information/@type` |
| `Body/Warning/Item` | `warnings`。`warningType` は親 `Warning/@type` |
| `Item/Kind` の `Name`・`Code`・`Condition`・`Status`・`LastKind` | `kinds[]` の同名フィールド。出現順を保つ |
| `Body` の `ChangeStatus`・`FullStatus`・`EditingMark` | `warnings[]` の同名フィールド。原文字列 |
| Item 直下の `Area` または `Areas/Area` | `areas[]`。`Areas/@codeType` を `codeType` に保持 |
| 河川の `Stations/Station` | `stations`。観測所であって影響範囲ではない |

各 index は同じ親内の同名要素のゼロ始まり位置であり、抽出・省略後も振り直さない。原電文にない ID・番号を生成して埋めてはならない。コード体系が確定しない場合に推測して埋めない。河川の浸水想定地区にある `Prefecture`・`City`・`SubCityList` 等は本型では構造化しないため、重要な対象地区は本文へ保持し `limitations` に記録する。

**電文全体の `Head/InfoType` と地域・現象ごとの `Kind/Status` は独立である。**「発表」の電文に継続・解除が混在し得る。未知の status を拒否用の列挙へ閉じ込めず原値を保持し、発表・継続・解除を原種別の規則で表示する。「解除」を電文の「取消」、全地域の解除、危険ゼロ、原投稿の削除へ変換してはならない。解除は当該地域・現象の警報の終了であり、電文や他地域の状態とは別の意味であるためである。見出し側の `Condition` と本文側の `Status` も区別して保持する。

### 3.2 気象内容の構造化した部分集合

```ts
type WeatherQuantity =
  | { element: "Significancy"; type?: string; name?: string; code?: JmaCode }
  | { element: "SnowfallDepth"; value: JmaValue; time?: string };
type WeatherTyphoon = {
  name?: string;
  nameKana?: string;
  number?: string;
  remark?: string;
  typhoonClass?: JmaValue;
  areaClass?: JmaValue;
  intensityClass?: JmaValue;
  center?: {
    coordinates: JmaValue[];
    location?: string;
    directions: JmaValue[];
    speeds: JmaValue[];
    pressures: JmaValue[];
  };
  windSpeeds?: JmaValue[];
};
type WeatherProperty = {
  propertyIndex: integer;
  type: string;
  texts?: JmaValue[];
  quantities?: WeatherQuantity[];
  typhoon?: WeatherTyphoon;
};
type WeatherMeteorologicalItem = {
  infosIndex: integer;
  infoIndex: integer;
  itemIndex: integer;
  infosType?: string;
  dateTime?: JmaValue;
  areas: WeatherArea[];
  stations?: { name: string; code?: JmaCode; location?: string }[];
  kinds: { name?: string; properties: WeatherProperty[] }[];
};
type JmaWeatherExtension = {
  $type: "dev.jisaku.mosimo.public.jma.weather";
  message: JmaWeatherMessage;
  selection:
    | { kind: "whole" }
    | { kind: "region"; areas: WeatherArea[] };
  headlineItems: WeatherHeadlineItem[];
  warnings: WeatherWarningItem[];
  meteorologicalItems: WeatherMeteorologicalItem[];
  notice?: string;
  comments?: JmaValue[];
  limitations?: string[];
};
```

`meteorologicalItems` は `Body/MeteorologicalInfos/MeteorologicalInfo/Item` の出現順と位置を保持する。`infosType` は `MeteorologicalInfos/@type`、`dateTime` は同じ `MeteorologicalInfo/DateTime`（実況・予報等の `@type` は `JmaValue.type`）、`areas`・`stations` は同じ Item の `Area`・`Station`。`propertyIndex` は Kind 直下の `Property` の位置、`type`・`texts` はその `Type`・`Text`。`notice` は `Body/Notice`、`comments` は `Body/Comment/Text`。

`Property` を起点とした部分集合の対応は次のとおりである。接頭辞は省略表記であり、名前空間 URI とローカル名で照合する。未知の Part を名前の類似だけで当てはめてはならない。

| 原 XML の相対位置 | 型のフィールド |
|---|---|
| `SignificancyPart/Base/Significancy` の `Name`・`Code`・`@type` | `quantities[].element: "Significancy"`、`name`・`code`・`type`。複合要素として保持し、数値レベルを創作しない |
| `SnowfallDepthPart/Base/SnowfallDepth`、または `SnowfallDepthPart/SnowfallDepth` | `quantities[]` の `SnowfallDepth`。同じ Part の `Time` がある場合は `time` |
| `TyphoonNamePart/Name`・`NameKana`・`Number`・`Remark` | `typhoon.name`・`nameKana`・`number`・`remark` |
| `ClassPart/TyphoonClass`・`AreaClass`・`IntensityClass` | `typhoon` の同名 camelCase フィールド |
| `CenterPart/Coordinate`・`Location`・`Direction`・`Speed`・`Pressure` | `typhoon.center` の各配列／文字列。複数単位・座標表現を原順で保持 |
| `WindPart/WindSpeed` | `typhoon.windSpeeds` |

`Precipitation`・`WaterLevel` は河川の `Body/MeteorologicalInfos/TimeSeriesInfo/Item/Kind/Property/PrecipitationPart/Precipitation`・`WaterLevelPart/WaterLevel` で確認した値だが、**本版では TimeSeriesInfo を構造化しない**。`refID` と `TimeDefines/TimeDefine/@timeId` の参照を解決せず単独の測定値にしてはならない。未対応の時系列は重要な時刻・値・条件を本文へ残して `limitations` に記録する。

台風の `ProbabilityCircle`、`WarningAreaPart`、`Item/Area/Circle` は上表の中心座標と別構造であり、本版では本文にのみ保持する。予報円を暴風域や被害範囲へ変換してはならない。台風の実況だけを載せた投稿を全予報点・風域対応済みと表示してはならない。数量の Part 内に本型で表せない重要な条件・参照がある場合は、それを本文で保持できることを確認するまで自動変換を保留する。

`limitations` は変換者による部分対応・省略の説明であり、気象庁の原フィールドではない。例: `TimeSeriesInfo未構造化: 河川水位の時刻別詳細は本文参照`。単に存在しない項目は省略し、未対応・省略を「値なし」「危険なし」として埋めてはならない。汎用 XML 属性辞書・XML ツリー・無制限の raw 文字列を拡張内へ入れない。

## 4. 種別別の対応と非対応の境界

次表の電文識別符は公式サンプル名等で確認した識別の補助であり、XML 本文の `Code` ではない。種別は取得 URL のファイル名ではなく、実際の `Control/Title`・`InfoKind`・`InfoKindVersion`・本文構造と採用した仕様版を照合して確定する。

| 対象 | 本拡張の構造化と制限 |
|---|---|
| 気象特別警報・警報・注意報、H27（VPWW53/54）、R06（VPWW55〜61） | `headlineItems`・`warnings` で地域・現象・発表／継続／解除・直前種別・変化状態を保持。R06 大雨／土砂／高潮と暴風／波浪／大雪／その他注意報を一つのレベル表へ潰さない。量的予想・危険度の全 Part 対応は範囲外 |
| 土砂災害警戒情報（VXWW50） | `warnings` に市町村ごとの警戒・継続・解除等を原値で保持。R06 土砂の電文とは別系列として扱う。共同発表官署と行動上の注意を残す |
| 竜巻注意情報（VPHW50/51） | 地域と原 `ValidDateTime`、目撃を含む見出し・注意事項を保持。竜巻発生の確定観測に書き換えない |
| 記録的短時間大雨（VPOA50、VPBS50 の該当サブ種別） | 地域と見出し、実況／解析・概数・時刻・雨量の条件を残す。旧 VPOA50 の本文にない構造化雨量を見出しから生成しない |
| 指定河川洪水予報（VXKOii） | 見出しの予報区域と Body の河川・観測所・主文を分離。行政区域、流域、浸水想定地区は同一領域ではない。時系列水位・雨量と浸水想定地区詳細の構造化は範囲外 |
| 台風解析・予報情報（VPTW60） | 呼称・強さ・実況／予報時刻・中心・気圧・風速の部分集合。時系列予報、予報円・風域の幾何変換は範囲外。台風消滅・温帯低気圧化を他警報の解除にしない |
| 府県気象防災速報（VPBS50）線状降水帯 | `Head/Headline/Information/Item/Kind/Condition` の原情報タグと対象地域を保持。`線状降水帯直前` と `線状降水帯発生` を区別。本文の「今後３時間以内」等を落とさず、予測を発生済みにしない |
| 府県気象防災速報（VPBS50）顕著な大雪 | 同じ箇所の原 `短時間大雪` と地域・観測所・降雪深・原時刻を保持。大雪警報（VPWW60）とは別種別。積雪深と期間降雪量を混同しない |
| 全般・地方・府県気象情報（VPZJ50/VPCJ50/VPFJ50）、気象解説情報（VPZJ51/VPCJ51/VPFJ51） | 原見出し・情報タグ、`Comment/Text` または気象概況等の `Property/Text` を保持。本文にしかない見通し・指示・時刻を構造化済みと偽らない |

線状降水帯の原情報タグに、本拡張独自の数値レベルを追加してはならない。原 XML にない等級を機関発表として配送することになるためである。Bot の `value:level4/level5` や英語 `type:` は Bot のマッピングであり、原 XML のコード定義ではない。コード表は採用版と適用先を確認して用い、未確認の公式コード番号を補わない。

海上警報、天気・週間・季節予報、熱中症、早期注意情報、河川の別種別（VXSUii 等）、台風の暴風域に入る確率（VPTA50）、警報時系列・集約通報等は本版の変換範囲に含まれない。対応は原種別・版・Part ごとに宣言する。未確認の版・構造は原文を保存して自動変換を保留する。

## 5. 投稿単位・本文・領域と上限

地域別にする場合は、同じ府県予報区等の必要な地域・現象・全状態をまとめ、`selection.areas` に選択元の名称・コード・体系を保持する。選択は変換者の分類であり、JMA XML に同名の selection 要素があるという意味ではない。選択した地域の全該当 Item を原 index 付きで残し、継続や解除だけを落とさない。見出しの他地域は文脈として区別し、地域選択だけで原電文全体を取り消さない。

河川は河川・予報区域と重要な対象地区を一緒に読める単位、台風・広域の一般情報・電文全体の取消は `whole` を基本とする。共通の管理情報・重要見出し・指示・条件・参照・対象を各投稿に同梱し、他地域の取得や原 XML へのオンライン接続なしで読める。全体の地域概況を本文へ残す場合も、それを選択地域の値に混ぜてはならない。

本文は原種別・地域・発表時刻・運用種別、発表／継続／解除または訂正／取消、重要な見通し・行動上の注意と出典・変換者を読める形で示す。正確な原文字列と出力先向けの安全な表示文字列は分離する（[気象庁 XML 共通 §10](../README.md#10-文字列の安全な解釈)）。

共通 `area` は、コード体系と版に対応する境界を解決でき、選択した全対象を覆える場合だけ付ける。全体を解決できなければ省略して本文と拡張に対象を残す。河川観測所・台風中心・予報円を影響範囲として、また官署や台風中心を `origin` として転記してはならない。

型の個別件数・文字数上限は未確定事項だが、完成イベント payload 32 KiB の上限は必ず適用する。署名前に完成 payload を測定し、必要な地域単位に分け、低優先の量的詳細・繰り返しを省略した場合は `limitations` と本文に明示する。管理情報、地域・現象・解除状態、重要な時刻・条件・指示、原文参照・ハッシュは落とさない。重要情報が未解釈、本文でも保持不能、または許容省略後も超過なら当該投稿を保留する。

## 6. 時刻・有効期間・配送期限

| 原項目 | 意味と変換 |
|---|---|
| `Control/DateTime` | 管理部時刻。取得時刻や投稿の作成時刻と分離 |
| `Head/ReportDateTime` | 原情報の発表時刻 |
| `Head/TargetDateTime`・`TargetDTDubious` | 基点とその曖昧さ。nil・曖昧さを保持し、確定した発生／開始時刻へ変換しない |
| `Head/TargetDuration` | 原基点に関する期間。電文別の意味を確認せず内容の寿命としない |
| `Head/ValidDateTime` | 原失効時刻。対象種別の定義と原状態を確認できる場合だけ共通 `validity.until` の候補 |
| 気象内容の `DateTime`・`Time`・時系列参照 | 実況／予報・集計区間等の時刻。発表時刻・有効期間と分離 |

原日時は原文字列、共通 `validity` は意味が確定できた端点だけ UTC `Timestamp` へ変換する。竜巻の明示された失効時刻等を利用できても、すべての警報に同じ失効時間を設けてはならない。`created_at` は変換投稿の作成時刻、`exp` はその通知について別途確定した配送期限であり、原文の失効・対象期間・基点時刻・Bot の `time:` とは別である。

## 7. 続報・訂正・解除・取消と認可

原 `InfoType` と原地域別状態は保持し、各通知を単独可読な新投稿として署名する。`Serial` の大小・受信順・同じ `EventID`・Bot タグの一致だけで旧情報を置換してはならない。地震の `Control/Title + Status + EventID` による独立単位の規則を全気象電文へ無条件に移植してはならない。気象では `EventID` が空の例もあり、地域・現象・予報区域・官署・原種別の版等を含む系列判定は電文別の解説を確認して定義する必要がある。

本版は機械的な系列キーと取消対象の確定規則を全種別について固定していない。したがって原状態・関係の主張は表示できても、自動の旧投稿配送停止は保留する。地域の解除を、別地域・別現象・別原種別の取消へ広げてはならない。項目が後続電文にないことも解除・取消の証拠ではない。訂正・取消通知には今回の管理情報と理由を載せ、旧値を現行として再掲してはならない。

将来、電文別の対象確定を実装して停止へ反映する場合も、[公的情報の取り込み §9.2](../../README.md#92-認可) の認可を必須とする。BSAF 取り込みの `import` 認可は転載の認可であって、機関による原 XML の更新取消権限ではない。認可できない取消は主張として保持し、旧署名・履歴は保持し、NIP-09 削除へ変換してはならない。

## 8. 配送・部分実装と検証要件

自己完結タグは `mosimo:ext:dev.jisaku.mosimo.public.jma.weather`。

- 基本端末: 本文表示、既存の署名・期限・上限による保持転送。未知拡張をそのまま保持する。
- 気象対応端末: 原種別・運用種別・原日時・地域ごとの状態・型で保持された部分集合を利用する。未知コード・未対応 Part を安全や値ゼロと表示してはならない。
- 取り込み側: 対応する原種別／`InfoKindVersion`／Part を明示し、raw 保存・出典・ハッシュ・重要情報保持・サイズ確認を実施する。「JMA XML 全面対応」と総称してはならない。

実装時の最低限の適合ケースは、同一電文内の発表／継続／解除の混在、訓練・試験、nil／空 ID・Serial、基点の曖昧さ、竜巻の明示的失効、線状降水帯の直前／発生、大雪の単位・観測時刻、河川の予報区と観測所の分離、台風の実況／予報と風域未対応、重要未知 Part 時の保留、32 KiB 超過、原文バイト列のハッシュ一致、権限のない取消で旧配送を止めないこと、である。

## 未確定事項

- 項目別資源上限と正式 Lexicon。
- 原種別ごとの仕様版固定と全サンプル試験。
- 未構造化 Part（時系列、予報円・風域、浸水想定地区等）の拡充。
- 境界データの版、配送期限の既定値。
- 電文別の更新系列・取消対象とその認可の具体化。

## 参照

- [気象庁 XML 技術資料一覧](https://xml.kishou.go.jp/tec_material.html)、[XML スキーマ一式（2024-10-31）](https://xml.kishou.go.jp/jmaxml_20241031_Schema(xsd).zip) — `jmx.xsd`・`jmx_ib.xsd`・`jmx_mete.xsd`、ヘッダの `TargetDateTime`（nillable）、`TargetDTDubious`、`TargetDuration`、`ValidDateTime`
- [気象庁 公式サンプル XML 一式（2026-09-17）](https://xml.kishou.go.jp/jmaxml_20260917_Samples.zip) — 警報 `15_16_03_241226_VPWW55.xml`、土砂 `15_16_01_241031_VPWW56.xml`、大雪 `15_16_06_241226_VPWW60.xml`、河川 `16_01_01_220728_VXKOii.xml`、土砂災害 `17_01_01_190529_VXWW50.xml`、記録雨 `18_01_01_100806_VPOA50.xml`、竜巻 `19_04_01_140425_VPHW51.xml`、台風 `10_03_01_171016_VPTW60.xml`、短時間大雪 `82_01_03_241031_VPBS50.xml`、記録雨 `82_01_02_250630_VPBS50.xml`、線状降水帯 `82_01_01_260324_VPBS50.xml`・`82_03_01_260324_VPBS50.xml`、一般情報 `20_01_01_100514_VPZJ50.xml`・`22_01_01_100514_VPFJ50.xml`、解説 `85(82)_02_01_250630_VPFJ51.xml`
- [気象庁 PULL 型情報提供](https://xml.kishou.go.jp/xmlpull.html)
