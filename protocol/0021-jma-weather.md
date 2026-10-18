# 0021: 気象庁気象防災情報の著者拡張と投稿への変換

状態: draft-00 — `dev.oriom.mosimo.defs#jmaWeather` の具体的な型・変換規則の提案。正式Lexicon・実装完了・全気象電文対応の宣言ではない。0020のBSAF wire-v1とは別の原XML入力である。

## 1. 適用範囲と入力の分離

本書は気象庁防災情報XMLの気象警報・注意報、土砂災害警戒情報、竜巻注意情報、記録的短時間大雨、指定河川洪水予報、台風、線状降水帯、顕著な大雪、気象の見通し・解説を扱う。地震は0017・0018、BSAF投稿の取り込みは0019・0020による。津波・火山・降灰・南海トラフ等を本拡張へ入れない。

| 入力 | 原値の格納と対応範囲 |
|---|---|
| 実際に取得・保存した対象JMA XML | 本書の `jmaWeather` と0013の `source`。原種別・版ごとの対応検証が必要 |
| `@bsaf-kikikuru-bot.bsky.social` の既存投稿 | 原レコードを保存し `bsafV1`。23種のソース選択は0019 §5.2.3。Bot本文・タグだけから `jmaWeather` を生成しない |
| BSAF投稿から辿ったリンクのXML | XMLを別途取得・照合・保存して初めて独立したXML出典として変換可能。BSAF投稿とXMLの同一性をタグから推定しない |

`source:jma`、`value:cancelled`、Botの名前や説明はJMA XMLの存在、公式署名、地域の状態、取消権限の証拠ではない。Botの対応範囲と本書のXML対応範囲は別であり、23種のBSAF取り込みにXMLパーサーの完成を要求しない。

## 2. 識別と原文の保存

型参照は `dev.oriom.mosimo.defs#jmaWeather`。通常の基本投稿の `extensions` 内に自己完結して格納し、`jmaEew`・`jmaEarthquake`・`publicInformation` の同梱を要求しない。共通型の再利用は実行時の他投稿への依存ではない。

取り込み側は取得した原XML全体を保存する。本ドラフトでは各投稿に `source` を付け、`retrievedFrom`、`retrievedAt`、変換者、変換規則名と版、保存原文への `links`、`originalDigest` を保持する。ダイジェストはHTTPのtransfer/content codingを解除したXMLエンティティの全バイト列に対するSHA-256（32 bytes）とし、文字コード変換・BOM除去・改行変換・XML再直列化の**前**に計算する提案とする。保存対象も同じバイト列である。これは0013の未確定な取得境界を本拡張用に具体化する案であり、他形式へ強制しない。

`source.document.messageId` は取得電文の絶対URL等の追跡用識別子、`senderId` は原 `PublishingOffice` の文字列、`revision` は原 `Serial` がある場合の原値とする。原URLを取得し直して別内容になった場合はハッシュ・取得時刻を別に記録する。URL・`EventID`・`Serial` のいずれも単独で全球一意の電文IDや更新系列とはしない。`part` は以下の選択位置の説明用表記であり、取消対象の識別子ではない。

原XMLの全量をメッシュ配送する義務や可逆変換の保証は設けない。保存参照のオンライン到達性もオフライン端末の表示・転送の前提にしない。原発表官署の名称、取得・変換者の署名、機関の認証を分離する。

## 3. データ型（提案）

TypeScript風の説明型。`integer` はatproto整数。`JmaValue`・`JmaCode`・`JmaNamedCode`・`JmaMessage` は [0017 §3.1](0017-jma-eew.md#31-共通の原値と管理情報) の原文字列保持型を再利用する。floatで数量・時刻・コードを置き換えず、先頭ゼロ、空文字列、省略、nil、条件・単位・説明を区別する。

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

`message.control`・`message.head` は `Control`・`Head` の同名要素、`headline` は `Head/Headline/Text`。追加の `targetDTDubious`・`targetDuration` は `Head/TargetDTDubious`・`Head/TargetDuration` [J1]。`Status`（通常・訓練・試験等）、`InfoType`、`InfoKind`、`InfoKindVersion`、`Serial`、`EventID`、官署・管理部日時・発表日時・基点日時を保持する。原電文にないID・番号を生成して埋めない。

`headlineItems` は `Head/Headline/Information/Item`、`warnings` は `Body/Warning/Item` に対応する。各indexは同じ親内の同名要素のゼロ始まり位置であり、抽出・省略後も振り直さない。`informationType`・`warningType` はそれぞれ親の `type` 属性。`kinds` は同じItem内の `Kind` の出現順、各フィールドは `Name`・`Code`・`Condition`・`Status`・`LastKind` に対応する。Bodyの `ChangeStatus`・`FullStatus`・`EditingMark` も原文字列で保持する [J2]。

対象はItem直下の `Area` または `Areas/Area`。後者の `Areas/@codeType` を各 `WeatherArea.codeType` に保持し、コード自身の `type` または適用されるコード定義も残す。コード体系が確定しない場合に推測して埋めない。河川の `Stations/Station` は観測所であって影響範囲ではない。河川の浸水想定地区にある `Prefecture`・`City`・`SubCityList` 等はこの最小型では構造化しないため、重要な対象地区は本文へ保持し `limitations` に記録する。

**電文全体の `Head/InfoType` と地域・現象ごとの `Kind/Status` は独立。** 「発表」の電文に継続・解除が混在し得る。未知のstatusを拒否用enumへ閉じ込めず原値を保持し、発表・継続・解除を原種別の規則で表示する。「解除」を電文の「取消」、全地域の解除、危険ゼロ、原投稿の削除へ変換しない。見出し側の `Condition` と本文側の `Status` も潰して一つにしない。

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
  $type: "dev.oriom.mosimo.defs#jmaWeather";
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

`meteorologicalItems` は `Body/MeteorologicalInfos/MeteorologicalInfo/Item` の出現順と位置を保持する。`infosType` は `MeteorologicalInfos/@type`、`dateTime` は同じ `MeteorologicalInfo/DateTime`、`areas`・`stations` は同じItemの `Area`・`Station` に対応する。実況・予報等の原 `DateTime/@type` も `JmaValue.type` に残す。`propertyIndex` はKind直下の `Property` の位置、`type`・`texts` はその `Type`・`Text`。`notice` は `Body/Notice`、`comments` は `Body/Comment/Text` [J2]。

以下は**Propertyを起点**とした、サンプルで確認した部分集合の対応である。接頭辞は省略表記であり、名前空間URIとローカル名で照合する。未知のPartを名前の類似だけで当てはめない。

| 原XMLの相対位置 [J2] | 型のフィールド |
|---|---|
| `SignificancyPart/Base/Significancy` の `Name`・`Code`・`@type` | `quantities[].element: "Significancy"`、`name`・`code`・`type`。複合要素として保持し、数値レベルを創作しない |
| `SnowfallDepthPart/Base/SnowfallDepth`、または `SnowfallDepthPart/SnowfallDepth` | `quantities[]` の `SnowfallDepth`。同じPartの `Time` がある場合は `time` |
| `TyphoonNamePart/Name`・`NameKana`・`Number`・`Remark` | `typhoon.name`・`nameKana`・`number`・`remark` |
| `ClassPart/TyphoonClass`・`AreaClass`・`IntensityClass` | `typhoon` の同名camelCaseフィールド |
| `CenterPart/Coordinate`・`Location`・`Direction`・`Speed`・`Pressure` | `typhoon.center` の各配列／文字列。複数単位・座標表現を原順で保持 |
| `WindPart/WindSpeed` | `typhoon.windSpeeds` |

`Precipitation`・`WaterLevel` は河川の `Body/MeteorologicalInfos/TimeSeriesInfo/Item/Kind/Property/PrecipitationPart/Precipitation`・`WaterLevelPart/WaterLevel` で確認した値だが、**この版ではTimeSeriesInfoを構造化しない**。この版の `quantities` の型・格納対象には含めない。`refID` と `TimeDefines/TimeDefine/@timeId` の参照を解決せず単独の測定値にしない。未対応の時系列は重要な時刻・値・条件を本文へ残して `limitations` に記録する。

台風の `ProbabilityCircle`、`WarningAreaPart`、`Item/Area/Circle` は上表の中心座標と別構造であり、本版では構造化しない。予報円を暴風域や被害範囲へ変換しない。台風の実況だけを載せた投稿を全予報点・風域対応済みと表示しない。数量のPart内に本型で表せない重要な条件・参照がある場合は、それを本文で保持できることを確認するまで自動変換を保留する。

`limitations` は変換者による部分対応・省略の説明であり、気象庁の原フィールドではない。例: `TimeSeriesInfo未構造化: 河川水位の時刻別詳細は本文参照`。単に存在しない項目は省略し、未対応・省略を「値なし」「危険なし」として埋めない。汎用XML属性辞書・XMLツリー・無制限のraw文字列を拡張内へ入れない。

## 4. 種別別の対応と非対応境界

次表の電文識別符は[J2]のサンプル名等で確認した識別の補助であり、XML本文の架空の `Code` ではない。取得URLのファイル名だけで種別を確定せず、実際の `Control/Title`・`InfoKind`・`InfoKindVersion`・本文構造と採用した仕様版を照合する。

| 対象 | 本ドラフトの構造化と制限 |
|---|---|
| 気象特別警報・警報・注意報、H27（VPWW53/54）、R06（VPWW55〜61） | `headlineItems`・`warnings` で地域・現象・発表／継続／解除・直前種別・変化状態を保持。R06大雨／土砂／高潮と暴風／波浪／大雪／その他注意報を一つのレベル表へ潰さない。量的予想・危険度の全Part対応はしない |
| 土砂災害警戒情報（VXWW50） | `warnings` に市町村ごとの警戒・継続・解除等を原値で保持。R06土砂の電文と同一系列にしない。共同発表官署と行動上の注意を残す |
| 竜巻注意情報（VPHW50/51） | 地域と原 `ValidDateTime`、目撃を含む見出し・注意事項を保持。竜巻発生の確定観測に書き換えない |
| 記録的短時間大雨（VPOA50、VPBS50の該当サブ種別） | 地域と見出し、実況／解析・概数・時刻・雨量の条件を残す。旧VPOA50の本文にない構造化雨量を見出しから生成しない |
| 指定河川洪水予報（VXKOii） | 見出しの予報区域とBodyの河川・観測所・主文を分離。行政区域、流域、浸水想定地区は同一領域ではない。時系列水位・雨量と浸水想定地区詳細の構造化は未対応 |
| 台風解析・予報情報（VPTW60） | 呼称・強さ・実況／予報時刻・中心・気圧・風速の部分集合。時系列予報、予報円・風域の幾何変換は未対応。台風消滅・温帯低気圧化を他警報の解除にしない |
| 府県気象防災速報（VPBS50）線状降水帯 | `Head/Headline/Information/Item/Kind/Condition` の原情報タグと対象地域を保持。サンプルの `線状降水帯直前` と `線状降水帯発生` を区別。本文の「今後３時間以内」等を落とさず、予測を発生済みにしない |
| 府県気象防災速報（VPBS50）顕著な大雪 | 同じ箇所の原 `短時間大雪` と地域・観測所・降雪深・原時刻を保持。大雪警報（VPWW60）とは別種別。積雪深と期間降雪量を混同しない |
| 全般・地方・府県気象情報（VPZJ50/VPCJ50/VPFJ50）、気象解説情報（VPZJ51/VPCJ51/VPFJ51） | 原見出し・情報タグ、`Comment/Text` または気象概況等の `Property/Text` を保持。本文にしかない見通し・指示・時刻を構造化済みと偽らない |

線状降水帯の原情報タグに、本書独自の数値レベルは追加しない。Botの `value:level4/level5` や英語 `type:` はBotのマッピングであり、原XMLのコード定義ではない。コード表は採用版と適用先を確認して用い、本書で未確認の公式コード番号を補わない。

海上警報、天気・週間・季節予報、熱中症、早期注意情報、河川の別種別（VXSUii等）、台風の暴風域に入る確率（VPTA50）、警報時系列・集約通報等は本版の変換対象外。上表も全要素対応を意味せず、原種別・版・Partごとに対応を宣言する。未確認の版・構造は自動変換せず原文を保存し、BSAFなら別途0019の可否を判断する。

## 5. 投稿単位・本文・領域と上限

地域別にする場合は、同じ府県予報区等の必要な地域・現象・全状態をまとめ、`selection.areas` に選択元の名称・コード・体系を保持する。選択は変換者の分類であり、JMA XMLに同名のselection要素があるという意味ではない。選択した地域の全該当Itemを原index付きで残し、継続や解除だけを落とさない。見出しの他地域は文脈として区別し、地域選択だけで原電文全体を取り消さない。

河川は河川・予報区域と重要な対象地区を一緒に読める単位、台風・広域の一般情報・電文全体の取消は `whole` を基本とする案。共通の管理情報・重要見出し・指示・条件・参照・対象を各投稿に同梱し、他地域の取得や原XMLへのオンライン接続を必要としない。必要に応じ全体の地域概況を本文へ残すが、それを選択地域の値に混ぜない。

本文は原種別・地域・発表時刻・運用種別、発表／継続／解除または訂正／取消、重要な見通し・行動上の注意と出典・変換者を読める形で示す。正確な原文字列と出力先向けの安全な表示文字列は分離する。

共通 `area` は、コード体系と版に対応する境界を解決でき、選択した全対象を覆える場合だけ0013・0004の保守的なGeohash近似を用いる。解決できた一部だけを載せず、不能なら省略して本文と拡張に対象を残す。河川観測所・台風中心・予報円を影響範囲としない。`origin` に官署や台風中心を自動転記しない。

型の個別件数・文字数上限は正式Lexicon前に固定する未決事項だが、現行の完成イベントpayload 32 KiB等の上限は必ず適用する。巨大な配列・本文を無制限に受理しない。取り込み側でXMLのbyte数・深さ・要素数・処理時間を制限し、外部実体・DTDや外部スキーマの自動取得を無効化する。

署名前に完成payloadを測定し、必要な地域単位に分け、低優先の量的詳細・繰り返しを省略した場合は `limitations` と本文に明示する。管理情報、地域・現象・解除状態、重要な時刻・条件・指示、原文参照・ハッシュは落とさない。重要情報が未解釈、本文でも保持不能、または許容省略後も超過なら当該投稿を保留する。中継者が署名後に縮約することは認めない。

## 6. 時刻・有効期間・配送期限

| 原項目 | 意味と変換 |
|---|---|
| `Control/DateTime` | 管理部時刻。取得時刻や投稿の作成時刻と分離 |
| `Head/ReportDateTime` | 原情報の発表時刻 |
| `Head/TargetDateTime`・`TargetDTDubious` | 基点とその曖昧さ。nil・曖昧さを保持し、確定した発生／開始時刻へ変換しない |
| `Head/TargetDuration` | 原基点に関する期間。電文別の意味を確認せず内容の寿命としない |
| `Head/ValidDateTime` | 原失効時刻。対象種別の定義と原状態を確認できる場合だけ共通 `validity.endsAt` の候補 |
| 気象内容の `DateTime`・`Time`・時系列参照 | 実況／予報・集計区間等の時刻。発表時刻・有効期間と分離 |

原日時は原文字列、共通 `validity` は意味が確定できた端点だけUTC `Timestamp` へ変換する。竜巻の明示された失効時刻等を利用できても、すべての警報に同じ失効時間を設けない。「継続中」だから無限の有効期間ともせず、期間欠落を解除ともしない。共通有効期間の欠落は不明である。

`createdAt` は変換投稿の作成時刻、`deliveryExpiresAt` はその通知について別途確定した配送期限。原文の失効・対象期間・基点時刻・Botの `time:` と同一視しない。古い原文の再取得で配送期限を繰り返し延ばさず、解除・訂正・取消通知自身の配送期間を旧情報から流用しない。種別ごとの配送期限の既定値は未決定。

## 7. 続報・訂正・解除・取消と認可

原 `InfoType` と原地域別状態は保持し、各通知を単独可読な新投稿として署名する。`Serial` の大小・受信順・同じ `EventID`・Botタグの一致だけで旧情報を置換しない。地震の `Control/Title + Status + EventID` による独立単位の規則（0018）を全気象電文へ無条件に移植しない。気象ではEventIDが空の例もあり、地域・現象・予報区域・官署・原種別の版等を含む系列判定は電文別の解説を確認して定義する必要がある。

本版は**機械的な系列キーと取消対象の確定規則を全種別について固定していない**。したがって原状態・関係の主張は表示できても、自動の旧投稿配送停止は保留する。地域の解除を、別地域・別現象・別原種別の取消へ広げない。項目が後続電文にないことも解除・取消の証拠ではない。訂正・取消通知には今回の管理情報と理由を載せ、旧値を現行として再掲しない。

将来、電文別の対象確定を実装して停止へ反映する場合も、[0009 §5.1](0009-identity-attestations.md#51-公的情報の更新取消の認可合意済み) の事前保持したルート公開鍵への署名チェーン、対象発信元・用途・範囲の更新取消権限、対象投稿の由来との対応を必須とする。0020の `bsaf-import` は取り込みの認可であって、機関による原XMLの更新取消権限ではない。一般的なサーバー信頼やBSAFタグで代用しない。認可できない取消は主張として保持し、旧署名・履歴を編集削除せず、NIP-09削除へ変換しない。

## 8. 配送・部分実装と検証要件

Nostr自己完結タグは `mosimo.oriom.dev:ext:dev.oriom.mosimo.defs#jmaWeather`。0012のatproto CBOR／通常Base64 paddingなし、メッシュ既定CBORとBPv7を使い、専用kind・巨大XML添付方式を追加しない。未知拡張の保持・再構成と内容解釈は別である。

- 基本端末: 本文表示、既存の署名・期限・上限による保持転送。未知拡張を改変しない。
- 気象対応端末: 原種別・運用種別・原日時・地域ごとの状態・型で保持された部分集合を利用する。未知コード・未対応Partを安全や値ゼロと表示しない。
- 取り込み側: 対応する原種別／InfoKindVersion／Partを明示し、raw保存・出典・ハッシュ・重要情報保持・サイズ確認を実施する。「JMA XML全面対応」と総称しない。

実装時の最低限の適合ケースは、同一電文内の発表／継続／解除の混在、訓練・試験、nil／空ID・Serial、基点の曖昧さ、竜巻の明示的失効、線状降水帯の直前／発生、大雪の単位・観測時刻、河川の予報区と観測所の分離、台風の実況／予報と風域未対応、重要未知Part時の保留、32 KiB超過、原文バイト列のハッシュ一致、権限のない取消で旧配送を止めないこと。これらは実装の検証要件であり、本書追加時にパーサーや端末試験を実施したとの主張ではない。

## 9. 一次資料と確認範囲

2026-10-19確認。XML名の相対位置はReport基準、接頭辞省略表記。実際には管理部 `http://xml.kishou.go.jp/jmaxml1/`、Head `http://xml.kishou.go.jp/jmaxml1/informationBasis1/`、気象Body `http://xml.kishou.go.jp/jmaxml1/body/meteorology1/` 等の名前空間を区別する。

- [J1] 気象庁 [技術資料](https://xml.kishou.go.jp/tec_material.html)、[XMLスキーマ一式（2024-10-31）](https://xml.kishou.go.jp/jmaxml_20241031_Schema(xsd).zip)。`jmx.xsd`・`jmx_ib.xsd`・`jmx_mete.xsd` の名前空間と、ヘッダの `TargetDateTime`（nillable）、`TargetDTDubious`、`TargetDuration`、`ValidDateTime` を確認した。全Bodyスキーマの網羅検証はしていない。
- [J2] 気象庁 [サンプルXML一式（2026-09-17）](https://xml.kishou.go.jp/jmaxml_20260917_Samples.zip)。警報の `15_16_03_241226_VPWW55.xml`、土砂 `15_16_01_241031_VPWW56.xml`、大雪 `15_16_06_241226_VPWW60.xml`、河川 `16_01_01_220728_VXKOii.xml`、土砂災害 `17_01_01_190529_VXWW50.xml`、記録雨 `18_01_01_100806_VPOA50.xml`、竜巻 `19_04_01_140425_VPHW51.xml`、台風 `10_03_01_171016_VPTW60.xml`、短時間大雪 `82_01_03_241031_VPBS50.xml`、記録雨 `82_01_02_250630_VPBS50.xml`、線状降水帯 `82_01_01_260324_VPBS50.xml`・`82_03_01_260324_VPBS50.xml`、一般情報 `20_01_01_100514_VPZJ50.xml`・`22_01_01_100514_VPFJ50.xml`、解説 `85(82)_02_01_250630_VPFJ51.xml` の構造を参照した。サンプルの列挙・代表構造の確認であり全組合せ・将来版への保証ではない。
- [J3] 気象庁 [PULL型情報提供](https://xml.kishou.go.jp/xmlpull.html)。取得・再配布条件と共通の信頼境界は0013。Atomの掲載順を更新関係に使わない。
- [B1] Kikikuru Botの固定commit `76ec562305cddd10da3b91828033dd6066c99e74` の [bot-definition.json](https://github.com/osprey74/bsaf-kikikuru-bot/blob/76ec562305cddd10da3b91828033dd6066c99e74/bot-definition.json) と [README-ja.md](https://github.com/osprey74/bsaf-kikikuru-bot/blob/76ec562305cddd10da3b91828033dd6066c99e74/README-ja.md)。BotのDID・23種の選択肢・非公式であることの補助資料であってJMAの公式XML仕様ではない。READMEの旧17種表や記録雨・土砂の担当記述には定義JSON／他節との不一致があり、稼働中の投稿範囲を断定する根拠にしない。

残る事項は正式Lexiconと項目別資源上限、原種別ごとの仕様版固定・全サンプル試験、未構造化Partの拡充、境界データの版、期限の既定値、電文別更新系列・取消対象とその認可の具体化である。
