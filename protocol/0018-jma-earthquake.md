# 0018: 気象庁地震観測情報の著者拡張と投稿への変換

状態: draft-00 — 対象範囲は確認済み。独立拡張のデータ型・処理を具体化する仕様草案であり、フィールド、投稿粒度、更新関係の配送への反映には未決事項を含む。本書の提案を新たな合意済み事項とはしない。正式な Lexicon JSON と取り込み実装は本書の対象ではない。

## 1. 適用範囲

気象庁防災情報XMLの地震・津波分野のうち、次の観測情報を対象とする。緊急地震速報の予測・警報とは区別し、観測震度を予測震度として扱わない。

| `Control/Title` | 対象とする内容 | 主な電文名 |
|---|---|---|
| `震度速報` | 都道府県・地域別の観測最大震度 | VXSE51 |
| `震源に関する情報` | 発生・発現時刻、震源、マグニチュード、付加文 | VXSE52 |
| `震源・震度に関する情報` | 震源と都道府県・地域・市町村・観測点別の震度 | VXSE53 |
| `長周期地震動に関する観測情報` | 震源、震度、長周期地震動階級、周期別観測値 | VXSE62 |

電文名やファイル名だけで意味を決めず、管理部・ヘッダ部と内容部を対応付ける。津波警報・注意報、津波観測、推計震度分布図、地震回数、火山情報は対象外である。津波に関する付加文を保持することは、津波情報全体への対応を意味しない。

[0013](0013-public-information.md) の公開取り込み方針と [0011](0011-extensions.md) の任意拡張モデルに従う。1投稿は1レコード・1 commit・1 Nostrイベントとする。原XML全量は取り込み側で保存し、メッシュへ原XML全量を配送したり、中継端末へXML解析を要求したりしない。本書はデータ表現と処理上の意味を扱い、検証アルゴリズム・適合性判定・テストベクトルは定めない。

## 2. 識別と独立性

`$type` は `dev.oriom.mosimo.defs#jmaEarthquake` とする。本拡張だけで対象電文の管理部・ヘッダ部・観測内容を解釈でき、緊急地震速報拡張や共通 `source` の同梱を前提としない。

- `jmaEarthquake`: 原電文の管理情報、震源、観測階層、付加文、発表・訂正・取消の意味を担当する。
- `source`: 取得URL、取得時刻、原文参照、変換者・変換規則、外側の容器等の追跡を担当する。
- `area`: 原情報の対象範囲を適切に表現できる場合に、共通の地域別配送判断へ提供する。
- `validity`: 原情報から確定できる内容有効期間だけを担当する。

共通型は [0017 §3](0017-jma-eew.md#3-データ型提案) の `JmaMessage`・`JmaValue`・`JmaCode`・`JmaNamedCode`・`JmaEarthquake` を参照する。これは型定義の共有であり、緊急地震速報の投稿や拡張への実行時依存ではない。`InfoKindVersion` は原XMLのスキーマ運用上の版であり、本拡張や変換規則の版ではない。

## 3. データ型（提案）

以下は TypeScript 風の説明用表記である。XMLの日時、数値、震度、階級、コードは原文字列を保持し、floatや独自の数値スケールへ置き換えない。`JmaValue` は `value` と任意の `type`・`unit`・`description`・`condition`・`nil`、`JmaCode` は `value` と任意の `type` を持つ。`JmaNamedCode` は `name` と `code: JmaCode` を持つ。

### 3.1 共通情報と震源

`message: JmaMessage` は次を保持する。

- `control`: `title`・`dateTime`・`status`・`editorialOffice`・`publishingOffice`。
- `head`: `title`・`reportDateTime`・`eventID`・`infoType`・`serial`・`infoKind`・`infoKindVersion`。任意の `targetDateTime`・`validDateTime` は `JmaValue`、`headline` は見出しの `Text` の文字列である。

`Control/Title` と `Head/Title` は別々に保持する。`Head/Headline/Information` の構造全体を汎用XMLオブジェクトとして格納せず、本書の観測階層と見出し文を用いる。

```ts
// 共通の管理情報・原値・コード・震源は0017 §3を参照
type JmaObservedEarthquake = JmaEarthquake;
```

`earthquake` は共通型の `originTime`・`arrivalTime`・`condition`・`magnitude` と、上記の震源表現を用いる。詳細な震源項目は0017 §3.2の共通型に含め、原電文に存在する項目だけを保持する。

| 原XML | 配置 |
|---|---|
| `Body/Earthquake/OriginTime`・`ArrivalTime` | `earthquake.originTime`・`arrivalTime` |
| `Hypocenter/Area/Name`・`Code`・`jmx_eb:Coordinate` | `hypocenter.name`・`code`・`coordinates` |
| `Hypocenter/Area/DetailedName`・`DetailedCode` | `hypocenter.detailedName`・`detailedCode` |
| `Hypocenter/Area/NameFromMark`・`MarkCode`・`Direction`・`Distance` | `hypocenter.nameFromMark`・`markCode`・`direction`・`distance` |
| `Hypocenter/Source` | `hypocenter.source`。震源決定機関であり、取得経路の共通 `source` とは別 |
| `Body/Earthquake/jmx_eb:Magnitude` | `earthquake.magnitude` |

震源の座標はISO 6709の原表記と説明を保持する。「ごく浅い」、深さ不明、700 km以上、震源要素不明を、精密な点や確定した深さへ読み替えない。マグニチュードの `NaN`・`condition: "不明"`・`description` は区別して保持し、「Ｍ８を超える巨大地震」を数値8や通常の欠測と同一視しない [J1 pp.127–134, 147–150]。

### 3.2 震度速報と詳細震度

```ts
type JmaQuickIntensityArea = JmaNamedCode & {
  maxInt: string;
};

type JmaQuickIntensityPref = JmaNamedCode & {
  maxInt: string;
  areas: JmaQuickIntensityArea[];
};

type JmaQuickIntensityObservation = {
  maxInt: string;
  prefs: JmaQuickIntensityPref[];
};

type JmaIntensityStation = JmaNamedCode & {
  int: string;
  revise?: string;
};

type JmaIntensityCity = JmaNamedCode & {
  maxInt?: string;
  condition?: string;
  revise?: string;
  stations: JmaIntensityStation[];
};

type JmaIntensityArea = JmaNamedCode & {
  maxInt?: string;
  revise?: string;
  cities: JmaIntensityCity[];
};

type JmaIntensityPref = JmaNamedCode & {
  maxInt?: string;
  revise?: string;
  areas: JmaIntensityArea[];
};

type JmaIntensityObservation = {
  maxInt: string;
  prefs: JmaIntensityPref[];
};
```

`Body/Intensity/Observation` の `Pref`・`Area`・`City`・`IntensityStation` を、それぞれ `prefs`・`areas`・`cities`・`stations` に対応付ける。震度速報は都道府県と地域までであり、架空の市町村・観測点を補わない。詳細震度は市町村と観測点までの親子関係を保持する。

`CodeDefine/Type` が各相対位置に指定する体系を対応する `JmaCode.type` に反映する。都道府県等、細分区域、市町村等、震度観測点のコードを区別し、桁数や値だけで体系を推定しない。先頭ゼロを削らず、地域名やコードをキーにした辞書へ潰さない。

震度は `1`・`2`・`3`・`4`・`5-`・`5+`・`6-`・`6+`・`7` 等の原語彙を保持する。震度速報で掲載される震度は3以上である。詳細震度では未入電により都道府県・地域・市町村の `MaxInt` がない場合があり、市町村の `Condition`、観測点の `Int` に「震度５弱以上未入電」が現れる。これを震度0、確定した震度５弱、観測点の不存在に置き換えない。掲載のない地域の震度も0とはしない [J1 pp.124–125, 134–138]。

`Revise` の「追加」「上方修正」「下方修正」は各階層の更新説明として保持する。`Head/InfoType` の「訂正」や、投稿の更新権限と同じ意味ではない。観測点名の「＊」と、それを説明する付加文も保持する。

### 3.3 長周期地震動

```ts
type JmaPeriodValue = JmaValue & {
  periodicBand: string;
  periodUnit: string;
};

type JmaLongPeriodStation = JmaNamedCode & {
  int: string;
  lgInt: string;
  lgIntPerPeriod: JmaPeriodValue[];
  sva: JmaValue;
  svaPerPeriod: JmaPeriodValue[];
  revise?: string;
};

type JmaLongPeriodArea = JmaNamedCode & {
  maxInt?: string;
  maxLgInt: string;
  revise?: string;
  stations: JmaLongPeriodStation[];
};

type JmaLongPeriodPref = JmaNamedCode & {
  maxInt?: string;
  maxLgInt: string;
  revise?: string;
  areas: JmaLongPeriodArea[];
};

type JmaLongPeriodObservation = {
  maxInt: string;
  maxLgInt: string;
  lgCategory: string;
  prefs: JmaLongPeriodPref[];
};
```

長周期地震動の階層は `Pref/Area/IntensityStation` とし、通常の詳細震度の `City` を挿入しない。観測点のコード体系は「長周期地震動観測点」であり、「震度観測点」と無条件に同一視しない。解説資料の一部の説明・例には市町村や `City` への言及があるが、CodeDefine、観測点要素の定義位置、公式サンプルの実構造に従う [J1 pp.150–155; J2 `78_01_01_240613_VXSE62.xml`]。

- `MaxLgInt`／`LgInt`: 最大長周期地震動階級／観測点の階級。震度と別の尺度であり、`0` を欠測にしない。
- `LgIntPerPeriod`: 周期別階級。XML属性 `PeriodicBand`・`PeriodUnit` を `periodicBand`・`periodUnit` に対応付ける。
- `Sva`: 絶対速度応答スペクトルの1.6秒から7.8秒までの周期帯の最大値。
- `SvaPerPeriod`: 指定周期帯の絶対速度応答スペクトルの最大値。`unit` と周期帯を値とともに保持する。

周期別階級と周期別スペクトル値は別々の配列とし、配列位置だけで対応を決めない。周期帯・単位を省いて数値列だけにせず、スペクトルから独自に階級を再計算しない。気象庁以外の観測点ではスペクトル値の小数第一位が0となる運用があり、文字列の小数桁だけから精度を推定しない [J1 pp.153–155]。

`LgCategory` は震度と長周期地震動階級の観測状況の分類であり、配送優先度ではない [J1 pp.150–151]。

| 原値 | 原情報の分類 |
|---|---|
| `1` | 全国の最大階級が2以下で、階級1以上の全地域の最大震度が５弱以上 |
| `2` | 全国の最大階級が2以下で、階級1以上の地域に最大震度4以下の地域がある |
| `3` | 全国の最大階級が3以上で、階級3以上の全地域の最大震度が５弱以上 |
| `4` | 全国の最大階級が3以上で、階級3以上の地域に最大震度4以下の地域がある |

### 3.4 内容部と拡張全体

```ts
type JmaFixedComment = {
  text: string;
  code: JmaCode;
};

type JmaEarthquakeComments = {
  forecastComment?: JmaFixedComment;
  varComment?: JmaFixedComment;
  freeFormComment?: string;
  uri?: string;
};

type JmaEarthquakeBody = {
  earthquake?: JmaObservedEarthquake;
  observation?:
    | JmaQuickIntensityObservation
    | JmaIntensityObservation
    | JmaLongPeriodObservation;
  text?: string;
  comments?: JmaEarthquakeComments;
};

type JmaEarthquakeExtension = {
  $type: "dev.oriom.mosimo.defs#jmaEarthquake";
  message: JmaMessage;
  body: JmaEarthquakeBody;
};
```

`observation` の意味は `message.control.title` に従い、フィールド名の類似だけから判定しない。震源に関する情報では `observation` を持たず、震度速報では `earthquake` を補わない。震源・震度に関する情報で国内の観測震度がなければ `observation` は省略される。取消では原電文の `Body/Text` 等を保持し、旧電文から震源・観測・付加文を引き継がない。

`Body/Text` は `body.text`、付加文は `body.comments` に配置する。`ForecastComment`・`VarComment` の `Text` と `Code` を一組として保持し、`code.type` に `codeType` を対応付ける。`Code` が空白区切りの複数コードでも `code.value` に原文字列を保持し、全文と各コードの一対一対応を創作しない。長周期地震動の `Comments/URI` は参考リンクであり、取得必須の観測本体や添付の実行指示ではない [J1 pp.125–126, 129–130, 138–140, 155–156]。

任意項目の省略、存在する空文字列、`nil`、不明を区別する。日時・数量の属性と説明を落とさず、未知の語彙を既知の最大値や通常値へ読み替えない。配列は原文の出現順を保つ。文字列の保持はXMLパーサーが返す値の保持であり、元のタグ・属性順・文字参照の綴りの完全復元ではない。

未知の著者拡張と既知型内の未知フィールドは0011に従って保持する。一方、未知のXML要素・属性をすべて汎用のXMLバッグへ格納する方式は採らない。意味を落とさず表せない新しい原情報は取り込み側で保留し、型の追加・改訂を検討する。未知の内容を削って既存型に収まったことにはしない。

## 4. 投稿単位と本文（提案・未決）

**原電文1通の対象内容全体を1投稿とする案と、地域別に必要な共通文脈を持つ独立投稿へ分ける案があり、投稿粒度は未決定である。** 都道府県・地域・市町村・観測点単位への分割の具体型は本草案では定義しない。同一地震の異なる種類の電文を一つに合成する案でもない。

基本本文は、本拡張を知らない端末でも少なくとも次を把握できるようにする。

- 気象庁発表情報の取得・変換者による転載であること、情報名称、原発表時刻。
- 通常・訓練・試験等の運用種別と、発表・訂正・取消の区別。
- 発表されている範囲で、発生時刻、震央地名、深さの説明、マグニチュードと不明条件。
- 最大震度・最大長周期地震動階級、観測地域、未入電など解釈に重要な条件。
- 見出し、本文、津波への言及、注意事項、自由付加文、取消理由等の重要な文章。

基本本文はXMLやコード列だけにしない。観測点の全数値表を必ず文章へ重複掲載するという意味ではないが、要約を全地点の詳細一覧と表示したり、掲載されない地域が安全であると表現したりしない。「今後の情報に注意」と「津波の心配なし」と「津波の有無は調査中」を一律の文へ置換しない。本文の詳細な生成・要約規則も未決定である。

32 KiB上限は本拡張だけではなく、本文・署名・タグ・commit等を含むメッシュ payload 全体に適用される。地域全体や多数の観測点・周期別値を含む電文が、この上限に必ず収まるとは宣言しない。上限を超える場合に観測点・地域・周期別値・重要文を黙って削除せず、投稿の分割や複数イベントによる継ぎ合わせも暗黙には行わない。

超過時の投稿粒度、全体と部分の識別、更新・取消の対象関係は未解決であり、方式の合意までは当該電文のメッシュ投稿化を保留する案とする。原文リンクだけの投稿を完全な観測情報の配送に代える方式も、本書では採用しない。

## 5. 原電文の同一性と更新・訂正・取消

### 5.1 気象庁の原情報上の規則

次は提案する配送規則ではなく、一次資料に記載された原電文の運用である [J1 pp.10, 48, 58]。

- 本書の地震情報の「独立した情報単位」は **`Control/Title` + `Control/Status` + `Head/EventID`** の組で区別する。編集官署は切り替わる場合があり、`EditorialOffice` をこのキーに加えない。`PublishingOffice` もこのキーではない。
- 同じ種類の最新情報は **`Control/DateTime`** を参照する。`Head/Serial` の大小、取得順、Atomの掲載順、投稿の `createdAt` で代用しない。空の `Serial` もそのまま保持する。
- `Head/InfoType: 発表` は情報の発表であり、同じ独立単位の続報も含み得る。続報をCAPの `Update` へ書き換えない。
- `訂正` は、その独立単位で直前の時点に発表されている `Control/DateTime` が最も新しい電文を訂正する。任意の過去版を `Serial` で指定する操作ではない。
- `取消` は、その独立単位全体を取り消す。直前の1通だけ、ある観測点だけ、同じ地震に関する全種類の電文を取り消すものではない。

`EventID` が同じでも、震度速報、震源に関する情報、震源・震度に関する情報、長周期地震動に関する観測情報は別単位である。後続の震源・震度情報だけで震度速報や震源情報の取消・自動置換を導かない。津波や緊急地震速報を含む別の情報名称にも取消を広げない。震度の下方修正や新しい観測の追加は、地震そのものの取消や危険の解消を意味しない。

この独立単位のキーは一連の情報の関連付けであり、個々の電文、投稿、atURI、Nostr event IDの識別子ではない。原電文の取得URL・管理部時刻等は出典追跡として別に保持する。同一時刻の競合、欠落した履歴、取消後の再発表等の境界で、受信順による確定した更新関係を創作しない。

### 5.2 投稿と配送への反映（提案）

発表・続報・訂正・取消は、それぞれ単独で読める新しい投稿として生成する。既存の署名付き投稿を編集しない。取消投稿は今回の管理部・ヘッダ部と取消理由を持ち、旧観測値を現在の値として再掲しない。配送期限も今回の通知自身について確定する。

原情報の関係を旧投稿の新規拡散停止へ反映する場合は、[0009 §5.1](0009-identity-attestations.md#51-公的情報の更新取消の認可合意済み) の既存の認可境界に従う。**事前保持したルート公開鍵への署名チェーン、対象の発信元・用途・範囲に対応する更新取消権限、および対象投稿の由来との対応が必要である。** 同じ `EventID` の自己申告、取得URLの文字列一致、取り込みサーバーへの一般的な信頼、Nostr署名だけでは他者の投稿を停止しない。

本書では、原情報上の同一独立単位の旧版を対象として、認可済みの続報・訂正では旧版の新たな `INVENTORY` 広告・転送開始を停止し、認可済みの取消では当該独立単位の旧情報全体の新規拡散を停止する案を置く。対象未着の場合の関係の記録と後着旧版の抑止を含むが、具体的な出典索引、履歴不足時の対象確定、競合・再発表、記録の保持期限・容量は未決定である。この配送案と投稿間参照の表現は追加確認を必要とし、原電文の規則を根拠に合意済みとはしない。

認可できない関係は主張として保持し、旧投稿の停止は保留する。新通知自体は独立した配送候補であり、旧署名や履歴を削除・改変しない。NIP-09の削除に変換せず、未対応端末や未接触端末まで直ちに停止する保証もしない。

## 6. 共通領域と時刻への対応

### 6.1 領域

観測情報に記載された地域・市町村・観測点と、震源位置を区別する。震源・震央を共通 `area` の中心とし、任意の半径を付けて影響範囲を作ってはならない。震源は投稿者端末の位置ではなく、共通 `origin` へ自動転記もしない。

地域・市町村のコードを使う場合は、コード体系と対応する境界を解決する。共通 `area` を付ける場合は、対象とする原情報の領域全体を覆う表現とし、[0004 §5.1](0004-geography.md#51-原情報全体を覆う近似合意済み) の近似に従う。解決できた一部の地域だけを共通 `area` として付けず、全体を扱えなければ `area` を省略して、地域名・コード・観測値を本拡張と本文に残す。

観測点の位置だけから市町村全体の揺れを確定せず、ある地域の最大震度・最大階級をその地域の全地点の観測値として扱わない。共通領域は配送判断の補助であり、実際に揺れた範囲全体や将来の被害範囲を断定するものではない。どの観測階層を共通領域へ対応付けるか、その境界データの版の扱いは未決定である。

### 6.2 時刻

| 項目 | 意味 |
|---|---|
| `Control/DateTime` | 管理部の発表時刻。同種情報の最新判定に利用 |
| `Head/ReportDateTime` | 原情報の発表時刻 |
| `Head/TargetDateTime` | 原情報の基点時刻 |
| `Earthquake/OriginTime` | 地震発生時刻 |
| `Earthquake/ArrivalTime` | 電文の定義に従う地震発現・検知時刻 |
| 投稿の `createdAt` | 変換投稿の作成時刻 |
| 投稿の `deliveryExpiresAt` | 投稿作成時に別途確定する配送期限 |

国外地震などで `ArrivalTime` に発生時刻が記載される運用を、独立した観測による検知の証拠と表示しない [J1 pp.131, 147]。原時刻の原表記・条件・nilを保持し、曖昧な基点や発生時刻から正確な有効期間を捏造しない。

観測時刻・基点時刻・原発表時刻を一律に `validity.startsAt` へコピーせず、固定時間後を内容の失効時刻ともしない。原情報から意味を確定できる端点だけを共通 `validity` の候補とする。有効期間と配送期限は別であり、訂正・取消通知へ旧情報の期限を流用しない。具体的な配送期間の既定値は本書では定めない。

## 7. 公開取り込みと信頼

公開再配布条件と取得経路の利用条件に従い、公式PULL型配信等から得た原電文を出典と対応付ける。管理部の通常・訓練・試験等を保持して表示し、訓練情報を通常の実災害情報へ書き換えない。運用種別ごとの自動投入条件は別途定める。

公式XMLを取得したこと、XMLに気象庁の名称があること、取得・変換者が投稿へ署名したことは、いずれも気象庁自身による当該投稿への機関署名ではない。取り込み者の署名は取得・変換の主張であり、発表機関の署名を再現したとは表示しない。公式情報の取得経路、原文の意味、投稿著者の署名、更新取消の権限を分離する。

## 8. 配送と部分実装

本拡張は通常の基本投稿の `extensions` に配置する。Nostrでは `mosimo.oriom.dev:ext:dev.oriom.mosimo.defs#jmaEarthquake` という自己完結タグに `$type` 以外の全フィールドを格納し、atproto CBORとBase64の配置は [0012 §3.2](0012-bindings.md#32-項目拡張ごとのタグ配置方式合意済み) に従う。メッシュでは既定のCBOR表現を利用し、新しい専用イベント種別やXML添付配送方式を追加しない。

基本対応端末は本文表示と通常の署名・期限・資源上限に従う保持転送を行える。共通領域対応端末は同梱された `area` を利用でき、地震観測情報対応端末は原電文の種別、観測階層、未入電、長周期地震動の値を利用できる。いずれも未知拡張・未知フィールドを保持する。

観測震度・長周期地震動階級・付加文のコードを、共通の単一優先度や自動避難命令へ変換しない。専用の表示・通知・配送順位は端末と利用者・運用者の方針に従い、高い値の自己申告だけで帯域・保存枠・端末権限を得ることはない。

## 9. 残る意味上の判断

1. 原電文全体／地域別の投稿単位の選択。地域別等にする場合の粒度と、部分・全体の表現。
2. 32 KiBを超える多数の観測点・周期別値を含む電文の扱い。欠落させない格納・配送方法と投稿粒度の整合。
3. 読める本文の要約範囲、観測一覧との区別、重要文と条件を保つ生成規則。
4. 原電文の続報・訂正・取消を配送停止へ反映する案、投稿間参照・出典索引、欠落・同時刻競合・取消後の再発表と後着旧版の扱い。
5. 共通 `area` に対応付ける観測階層、境界コード表の版、原情報全体を覆えない場合の運用。
6. 運用種別ごとの取り込み条件、共通有効期間の対応、配送期限の既定方針。
7. 採用する原形式・コード表の版、未対応の原XML要素・将来の語彙追加への対応範囲、正式Lexiconと資源上限。

## 10. 処理の流れ

```text
原電文取得・保存
  → 公開条件と対象種別の判断
  → 管理情報・震源・観測階層・条件・付加文の対応付け
  → 投稿単位ごとの本文・任意の共通地域／期間・配送期限の生成
  → 1投稿＝1レコード・1 commit・1 Nostrイベントとして署名
  → 共通CBOR・BPv7による配送とNostrへの公開
```

周期帯の属性は本書固有の `JmaPeriodValue` に置き、共通の `JmaValue` へ汎用XML属性辞書は追加しない。取得・変換は取り込み側、内容の詳細表示は対応クライアント、未知拡張を含む運搬は基本対応端末の責務とする。

## 11. 一次資料と確認範囲

- [J1] 気象庁「地震火山関連 XML 電文解説資料」、令和7年12月。公式 [2026-10-09 解説資料ZIP](https://xml.kishou.go.jp/jmaxml_20261009_Manual(pdf).zip) 内の `地震火山関連_解説資料.pdf`。ページ番号はPDF先頭を1とする。p.10: InfoType・Serialと最新判定、p.48: 独立した情報単位、p.58: 取消運用、pp.124–126: 震度速報、pp.127–130: 震源に関する情報、pp.131–140: 震源・震度に関する情報、pp.147–156: 長周期地震動に関する観測情報を確認した。ZIP公開日とPDFの資料年月は区別する。
- [J2] 気象庁 [2026-09-17 サンプルXML ZIP](https://xml.kishou.go.jp/jmaxml_20260917_Samples.zip)。`Control/Title` が本書の4種に一致する電文を確認した。代表例は `32-35_01_01_100806_VXSE51.xml`、`32-35_01_02_240613_VXSE52.xml`、`32-35_01_03_240613_VXSE53.xml`、`78_01_01_240613_VXSE62.xml`。サンプルに現れない任意項目についてもJ1の記載を参照しており、サンプルの一致だけで全電文への対応完了とはしない。
- [J3] 気象庁 [防災情報XMLフォーマット](https://xml.kishou.go.jp/)、[PULL型情報提供](https://xml.kishou.go.jp/xmlpull.html)。取得経路・公開取り込みの共通方針は0013を参照する。

原資料のXML上の要素名・属性名と本書の説明用フィールド名は対応関係を示すものであり、XMLの全面的なJSON化や気象庁による本プロトコルの承認を意味しない。
