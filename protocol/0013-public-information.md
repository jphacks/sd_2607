# 0013: 公的情報の取り込みと出典の表現

状態: draft-01（既存合意の整理とデータ構造の新提案）

更新日・外部資料確認日: 2026-10-17

## 1. 目的と文書の位置付け

CAP、EDXL、気象庁防災情報XMLから、人が読める本文と任意の構造化拡張を持つ共通投稿を生成する処理を説明する。既存BSAF投稿は、共通投稿の生成とは別に、AppViewへの入力として扱う。

本書は実装者向けの設計草案である。「既存合意」は [0001](0001-repository-model.md)、[0002](0002-ingestion-and-trust.md)、[0004](0004-geography.md)、[README](README.md) による。「提案」と記した型・対応規則は採用済みのwire仕様ではない。以下の禁止・推奨も、外部標準の要件と本書の提案を区別して読む。

仕様群の分担は次のとおりとする。

| 文書 | 分担 |
|---|---|
| [0010: 基本プロトコル](0010-core.md) | 共通投稿と投稿単位 |
| [0011: 著者拡張](0011-extensions.md) | 拡張の共通構造。本書で `source`、`public-information` を具体化 |
| [0012: データ表現](0012-bindings.md) | atproto・Nostr・メッシュへの配置案と符号化 |
| 本書 | 公的情報の入力、出典、原情報の意味、変換とAppView統合 |

原文XML全量のmesh配送、変換の完全可逆性、XMLを復元する仕組みは未合意であり、本書でも要求しない。原文のローカル保存と、原文を投稿に同梱して配ることは別の判断である。

## 2. 既存合意と本書の提案の境界

### 2.1 既存合意

- 初期仕様は公開情報のみを対象とし、限定公開・暗号化・個別通信を提供しない。地理情報はアクセス制御ではない。
- 本プロトコルのatproto表現は独自Lexiconを本体とする。公式 NSID と型名は [0014](0014-namespaces.md) に割り当て済み。上限・個別のwire配置には未決定事項が残る。
- 通常投稿・訂正等は、1投稿につき1レコード追加・1 commit・1 Nostr投稿イベント。本文投稿は `kind: 1`、`content` は人が読める本文とする。複数投稿のバッチcommitは初期仕様に設けない。
- 訂正は新しい投稿であり、過去のレコードを上書きしない。取消の通知、期限切れ、著者による削除要求は別の概念である。
- 通常投稿の署名付き配送期限は必須。情報の有効期間は任意であり、省略は期間不明を意味する。配送期限から有効期間を補わない。
- 影響範囲は円・Geohashセルの和集合。保存は固定単位の整数とし、WGS 84の座標は100万分の1度、円の半径・測位精度はミリメートルを1とする。行政区域コードと多角形は初期の共通領域表現に含めない。
- 拡張一覧は Lexicon の open union とし、各要素は `$type` と拡張固有フィールドを持つ。共通の `type`・`version`・`value` の包みは用いない。Nostr では既定で1要素＝1自己完結タグとし、共通の運び方で未知型も保持・再構成する。未対応の署名付き拡張を削って中継しない。本文は拡張の非対応だけを理由に拒否しない。
- 受信者が信頼する複数の取り込みサーバーを用意し、既定ではそのうち1者の有効な署名で受理できる。複数者の一致は追加の確認であり必須ではない。
- 原情報の同値性と署名付きイベントの識別を分離する。変換者が違えば、Nostr event ID・署名、record CID・commitは別物として保持する。
- AppViewは既存BSAF投稿を別入力として収集する。元著者・出典・識別子を保持し、元投稿の変更や署名の捏造をしない。BSAFからNostr・meshへの再配信は未決定。

### 2.2 本書で新たに提案するもの

共通時刻型は [0010 §3.1](0010-core.md#31-共通時刻型合意済み) の合意に従う。以下の説明用型の個別フィールド、原情報参照の組、情報単位への分割、公開可否の判定方針、各形式からのマッピングは新提案である。外部標準のフィールドを記載していることと、その名前を本プロトコルのwireに登録することは同じではない。

## 3. 入力形式の役割

| 入力 | 原情報の構造 | 取り込み側の責務 |
|---|---|---|
| CAP 1.2 [C] | `alert` と複数の `info`。各 `info` に言語・指示・期間・複数 `area`・`resource` | 発表単位と、言語・対象ごとの情報単位を分けて読む |
| EDXL-DE 1.0 [D] | `EDXLDistribution` に配布メタデータと複数 `contentObject` | 配布容器を開き、各内容を対応する形式の処理へ渡す |
| 気象庁XML [J1–J4] | `Report` の `Control`・`Head`・情報種別ごとの `Body` | 共通ヘッダに加え、電文別の意味・更新規則を読む |
| BSAF v1 [B] | 既存 `app.bsky.feed.post` の本文、`tags`、`langs` とBot定義JSON | AppViewが原投稿を保持し、表示・検索用の索引を追加する |

EDXL（Emergency Data Exchange Language）は緊急情報交換の仕様群であり、一種類の警報XMLの名前ではない。DE（Distribution Element）はその配布容器の具体例である。DEにCAPを内包することはできるが、全EDXLをCAPとして解析してはならない。本書はDE 1.0の構造を説明例に用い、EDXL全仕様・全バージョンへの対応や最新版の採用を宣言しない。

## 4. 共通の説明用データ構造（提案）

### 4.1 共通投稿

```ts
type integer = number; // 整数を意味する説明用表記
type Timestamp = string; // 合意済み: atproto datetimeに適合するUTC・末尾Z。例: 2026-10-17T03:00:00Z
type Bytes = Uint8Array; // 生バイト列。Base64文字列そのものではない
type Microdegrees = integer; // 合意済み: WGS 84の100万分の1度
type Millimeters = integer; // 合意済み: 距離のミリメートル単位

// 0011で定義するopen union。未知の$typeも保持する。
type Extension = KnownExtension | UnknownExtension;

type Post = {
  text: string;
  createdAt: Timestamp;
  deliveryExpiresAt: Timestamp;
  extensions: Extension[];
};
```

`origin`、`area`、`validity`、`annotation`、`source`、`public-information`、`identity` は説明用名称であり、登録済み識別子ではない。公式の型参照は [0014](0014-namespaces.md) に従う。例えば出典は `dev.oriom.mosimo.defs#source` とする。版管理・個別のスキーマには未決定事項が残るが、open union と自己完結したタグによる運搬は合意済みである。

本書では、変換投稿の `createdAt` を変換者が新規投稿を作成した時刻とする案を使う。原発表時刻・取得時刻・現象発生時刻を別途保持する。配送期限は運用者が投稿時に絶対時刻として確定し、原情報の `expires` 等と同一視しない。具体的な既定期間や上限値は定めない。

### 4.2 `source`: 出典と変換経路（提案）

```ts
type SourceRef = {
  format: string;       // 形式と版。ローカルな説明名
  senderId: string;     // 原形式における発信者の識別子
  messageId: string;    // 原形式における電文・投稿の識別子
  sentAt?: Timestamp;
  revision?: string;    // 原形式が持つ版・情報番号等。大小比較規則は形式別
  part?: string;        // 文書内の内容・言語等の選択箇所
};

type Digest = { algorithm: string; value: Bytes };

type SourceExtension = {
  $type: "dev.oriom.mosimo.defs#source";
  document: SourceRef;           // 本文・構造化情報の直接の出典
  containers?: SourceRef[];      // 外側から内側の配布容器。内容の著者とは別
  retrievedFrom: string;         // 実際の取得URL
  retrievedAt: Timestamp;
  converter: string;             // 変換者DID等。対応する署名と結び付ける必要がある
  conversion: { rule: string; revision: string };
  originalDigest?: Digest;       // 取得した原文全体のバイト列のハッシュ
  links?: { url: string; mediaType?: string }[];
};
```

`source` は「どこから得た何を、誰がどの規則で変換したか」の主張であり、公的機関の認証結果を格納する真偽フラグではない。配布者・原発表者・取得者・変換者を混同しない。同じサーバーが取得と変換を行っても責務は区別する。`converter` の自己申告だけでは署名との対応は成立せず、鍵対応・信頼設定は共通の署名モデルに従う。

`part` は説明用の選択位置である。後述の `contentObject[0]` 等はゼロ始まりのローカル表記であり、XML標準のXPathや確定した正規化キーではない。容器内の位置は同じ内容でも変わり得るため、原情報IDの代用品にしない。

`originalDigest` の対象は、ここではHTTP等から取得した原文ファイルの全バイト列とする案であり、XMLの再直列化後のバイト列ではない。改行・文字コード・属性順序の違いを吸収する正規化ハッシュとは別物である。HTTPの符号化解除等を含む正確な取得バイト境界とアルゴリズムの採用は未決定。

型上の `Bytes` を atproto JSON で説明するときは、例えば `{"algorithm":"sha-256","value":{"$bytes":"AQID"}}` のように標準の bytes 表現を用いる。これは3バイトの表現例であって、有効なSHA-256ダイジェストの例ではない。CBOR 内では byte string とする。独自 Nostr タグの bytes 項目は [0012 §3.5](0012-bindings.md#35-独自タグのバイト列合意済み) の通常の Base64・paddingなしに従う。ハッシュアルゴリズムと具体的なタグ配置は別途定める。後述の変換例では架空のダイジェストを作らず、この任意項目を省略する。

### 4.3 `public-information`: 原情報の意味（提案）

```ts
type PublicInformationExtension = {
  $type: "dev.oriom.mosimo.defs#publicInformation";
  status: string;          // 実情報・訓練等。原形式の値を保持
  messageType: string;     // 発表・更新・取消等。原形式の値を保持
  scope: string;           // 原形式の公開区分。内部処理では限定・不明も区別
  references: SourceRef[]; // 原情報が指定する先行電文。未取得の対象も保持
  incidentIds?: string[];  // 現象・事案の関連付け。電文の同一性とは別
  language?: string;
  event?: string;
  categories?: string[];
  urgency?: string;
  severity?: string;
  certainty?: string;
  sourceFields: object;    // atproto互換値のみ。元のXML数値は文字列で保持（float不可）
};
```

`document.format` に従って `status` 等の語彙を読む。CAPの `Actual` と気象庁の「通常」を、共通の登録値として同一視する規則はここでは設けない。`sourceFields` は原文XMLを丸ごとJSON化する袋ではなく、以下のマッピングに必要な項目を保持する場所とする。任意のJSON objectではなく、入れ子を含めてatprotoデータモデル [A] に適合する値だけを格納し、浮動小数点数を含めない。原形式側に残すXML数値は文字列で保持する。一方、共通の発信地点・円の座標は `Microdegrees`、半径・測位精度は `Millimeters` の整数へ変換する。原値の文字列と変換後の整数を混同しない。項目別のスキーマ・サイズ上限は未決定。

原情報参照は `public-information.references`、本プロトコル内の対象投稿への参照は `annotation` が担う。原電文IDをNostr event IDやatURIの代わりに使わない。原情報から派生した投稿への対応索引は取り込み側・AppViewで持ち、対象投稿が判明した場合は [0011 §7](0011-extensions.md#7-注釈-annotation型案) の参照案と関連付ける。

`area` は配送にも使う円・セル、`validity` は内容の有効期間、`identity` は身元証明、`origin` は発信地点を担当する。0011と共通の説明用形は、`area` が `{ $type: string; regions: ({ shape: "circle"; latitudeMicrodegrees: Microdegrees; longitudeMicrodegrees: Microdegrees; radiusMillimeters: Millimeters } | { shape: "geohash"; hash: string })[] }`、`validity` が `{ $type: string; startsAt?: Timestamp; endsAt?: Timestamp }` である。公的情報の震源・観測地点やDEの配布対象地域を、変換サーバーの発信地点として `origin` に入れない。

## 5. 共通の処理の流れ（提案）

1. **取得する。** 公式配信元等の許可した入力経路から取得し、取得URL・時刻・原文識別情報を記録する。URLの掲載だけを公式性の証明にしない。
2. **入力を解釈する。** 名前空間と形式の版を識別し、安全なXMLパーサーで読む。外部実体・DTD・外部参照の無条件取得を避け、サイズ・展開量・処理時間を制限する。未知の形式をCAPと推測しない。
3. **公開可否を決める。** 容器・内容・提供元の条件を併せて評価する。限定・私信・解釈不能な制限は公開投稿へ変換しない。訓練・試験・内部制御情報は実災害情報と分け、実情報用の自動投入から外す案とする。
4. **意味のある情報単位に分ける。** DEの各内容、CAPの言語・対象・期間の違いを保つ。選択箇所と変換規則の版を記録する。非対応の内容を公開警報に見せない。
5. **本文と拡張を生成する。** 本文だけでも種別、発表元、対象、主要な指示、訂正・取消が分かるようにする。原時刻はUTCへ変換し、元表記や不確実性を必要な範囲で残す。期間・領域が不明なら捏造しない。
6. **変換者として新規発信する。** 配送期限を確定し、変換者自身のレコードと署名付きイベントを生成する。1件ごとに1レコード・1 commit・1 Nostr `kind: 1` とし、`content = Post.text` にする。`source`・`public-information`・`area`・`validity` は、それぞれ [0012 §3.2](0012-bindings.md#32-項目拡張ごとのタグ配置方式合意済み) の自己完結したタグで独立して運ぶ。PDS は各公的情報形式の意味を知らなくても元の拡張オブジェクトへ戻せる。本文・commit 等の配置も0012に従う。
7. **受信側で取り扱いを決める。** 署名の有効性、変換者への信頼、機関への帰属、内容の真偽を別々に扱う。0002の条件を満たした原情報同値性に限って配送重複を抑制し、通常の本文一致による統合はしない。

HTTPSは取得時の通信保護・サーバー認証であり、持ち運んだ本文に対する発表機関のオフライン署名ではない。取り込みサーバーの署名が証言する取得・変換と、発表機関自身の署名を区別する。原文ハッシュは同じバイト列の照合に役立つが、公式性や意味的同値性をそれだけで証明しない。

## 6. 形式別の対応

### 6.1 CAP 1.2

一次資料 [C] の §1.3、§3.2、§3.3 を参照する。

| CAP | 共通投稿への対応案 |
|---|---|
| `sender`・`identifier`・`sent` | `source.document` の発信者・電文ID・原発表時刻。`identifier` 単独では扱わない |
| `status`・`msgType`・`scope` | `public-information` に原値を保持し、公開可否と表示を決める |
| `references` | `sender,identifier,sent` の組ごとに原情報参照へ。更新・取消対象を保持 |
| `incidents` | `incidentIds`。同一事案というだけで電文を統合しない |
| 各 `info` の `language`・`headline`・`description`・`instruction` | 情報単位ごとの言語と本文。指示を省いて意味を変えない |
| `category`・`event`・`urgency`・`severity`・`certainty` | 原語彙を保持。ローカル配送優先度への直結はしない |
| `effective`・`onset`・`expires` | 内容の有効開始・現象開始・終了を区別。共通 `validity` への対応は選択した `info` ごと |
| `area` の `circle` | 緯度・経度を100万分の1度、半径kmをミリメートル単位の整数に変換して `area` の候補にする。丸め・誤差の規則は別途定める |
| `polygon`・`geocode`・`areaDesc`、高度条件 | 原形式側に保持。共通領域への変換・近似は別途規則が必要 |
| `resource`・`web` | リンク等を保持できるが、画像・音声・添付データ本体のmesh配送を要求しない |

CAPは複数の `info` による多言語表現を持つ。同一言語でも複数の対象・強度等を表せるため、「各 `info` は同じ内容の翻訳」と仮定しない。同一言語の重なる対象について、後続ブロックは先行情報を拡張できるが上書きしないというCAPの意味を守る。

本書では、各 `info` の順序と対象を保持して情報単位ごとに投稿を作る案とする。異なる言語・対象の領域や期間を混ぜて一投稿の意味にしない。言語選択・分割・本文生成規則を版付きで定める必要があり、取得サーバーごとの任意の要約だけで配送同値とはしない。`language` 省略・空値時のCAP上の既定は `en-US` であり、原文に明示されたかどうかも区別できるようにする。

`effective` 省略時はCAPの規則上 `sent` と同じになる。これは配送期限からの推測ではない。`expires` の省略は本書では終了不明として保持する。`onset` を投稿時刻や有効開始と同一視しない。CAP 1.2の時刻表記は数値オフセットを要求しUTCを `-00:00` とするため、共通型の `Z` 表記をCAP原文へ書き戻さない。

`Update` は `references` の先行電文を更新し、`Cancel` は先行電文を取り消す。`info` のない取消もあり得るため、対象参照と取消であることから単独で意味が分かる本文を作る。対象未取得なら関連を保留し、後着時に結び付ける。機関の取消通知を変換者が伝えることは、他者の投稿をNIP-09で削除する権限を得ることではない。

CAPの `scope: Public` は無制限の対象への一般配布、`Restricted` は業務上必要な利用者への配布、`Private` は指定アドレスへの配布である。後二者を地域絞り込みだけで公開配送してはならない。外側の容器により強い制限があれば、内側が `Public` でも公開しない。

### 6.2 EDXL-DE 1.0

一次資料 [D] の §1.3、§3.2、付録Aを参照する。DEの `senderID` は配布容器の送信者であり、内包CAPの `sender` と同一とは限らない。

- `distributionID`・`senderID`・`dateTimeSent` を容器の参照として `source.containers` に残す。
- `contentObject` ごとに `xmlContent` または `nonXMLContent` を読む。XML内容は `xmlContent/embeddedXMLContent` 等の内側の名前空間で再分類する。DE 1.0では一つのXML容器内に複数の内包XMLを持ち得るため、内包文書も列挙する。
- `nonXMLContent` はURI参照・Base64の `contentData` を持ち得る。リンクや記述を取り込むことと、外部データを自動取得してmeshに載せることを分ける。`keyXMLContent` の抜粋だけを完全な原文として扱わない。
- `distributionStatus`、`distributionType`、`distributionReference` を容器側の意味として残す。DEの参照順は `distributionID,senderID,dateTimeSent` であり、CAPの参照順とは異なる。
- `targetArea` は配布対象地域であり、内包警報の影響範囲の代用品にしない。受信者の所在地を絞る情報から、災害の影響範囲を推測しない。
- 容器全体の `combinedConfidentiality`、各内容の `confidentiality`、宛先・役割指定、内包情報の公開範囲を個別に扱う。機密区分は任意文字列を含み、単に `Public` と比較する共通列挙型ではない。`UNCLASSIFIED AND NOT SENSITIVE` や `Unclassified` だけから、無条件の再配布許可を導かない。

公開可能性は提供元のプロファイル・公開経路・権限条件も含めて決める。容器全体に制限がある場合、個別内容の区分だけを見て取り出し公開しない。制限の意味が分からない場合は公開投入を保留する。DEの機密区分の記載自体も、暗号化や漏えい防止を実現するものではない。

DE全体の更新・取消と、内包CAPの更新・取消は対象の層が違う。容器の `Cancel` を根拠に全内包警報が発表機関によって撤回されたと断定しない。容器間の関係と内包文書間の関係を分離して保持する。

### 6.3 気象庁防災情報XML

公式PULL型配信はAtomの一覧から個々のXML電文を取得する経路を提供する [J1, J2]。Atomの更新・掲載順を、電文の改訂順や現象の発生順と同一視しない。既取得電文の再取得を抑え、公開元の利用条件・負荷制限に従う。

| 原情報 | 対応案と注意 |
|---|---|
| `Control/Title`・`Status`・`PublishingOffice`・`EditorialOffice`・`DateTime` | 情報名称、運用種別、発表官署、編集官署、管理部時刻を原形式側に保持 |
| `Head/Title`・`ReportDateTime` | 標題と原発表時刻。変換投稿の `createdAt` と分離 |
| `EventID`・`Serial`・`InfoType`・`InfoKind`・`InfoKindVersion` | 現象識別・番号・発表/訂正/取消等・情報種別・版を保持し、電文別規則で関連付け |
| `TargetDateTime`・`TargetDTDubious`・`TargetDuration`・`ValidDateTime` | 基点時刻・あいまいさ・期間・失効を区別。nilや省略を確定時刻にしない |
| `Headline`、本文中の見出し・指示 | 人が読める本文に反映。訓練・訂正・取消の表示を残す |
| 地域・地点名、`Code` とコード体系 | 原値と体系を保持。行政区域・予報区等をGeohash文字列と扱わない |
| `Coordinate` 等 | ISO 6709表現や `type`、単位・不確実性を解釈し、必要な場合だけ座標へ変換 |

基本構造・時刻・座標表現は [J3]、実際の共通要素名は [J4] の `jmx.xsd`・`jmx_ib.xsd` で確認した。`Body` は気象・地震津波・火山等で異なり、ヘッダだけで全電文の指示・更新関係を確定できるとはしない。

`EventID` 単独や `Serial` 単独を全球一意の電文IDにしない。取得電文のURL等を記録しつつ、発表官署・情報種別・現象識別・情報番号・発表時刻等をどう組み合わせるかは電文別の変換規則で定める。取消の対象や「解除」の意味も電文別である。警報解除を誤発表の取消や投稿削除と一律に同一視しない。

基点時刻と期間から有効期間を構成してよいかは電文の意味による。曖昧な現象発生時刻を精密な `Timestamp` として偽装しない。地域コードから境界を解決できなければ、地域名・コードを表示用に残して共通 `area` を省略する。震源位置を影響範囲の円の中心として自動採用しない。

公式トップページ・取得案内では、電文ごとの署名と検証用公開鍵の案内は確認できていない。全配信経路で署名が存在しないとの断定ではない。取得者・変換者の署名であることを明示し、気象庁自身の署名と表示しない。

### 6.4 BSAF: AppViewの別入力

[B] で確認したBSAF v1は、`app.bsky.feed.post` の `tags` に `bsaf:v1`、`type:`、`value:`、`time:`、`target:`、`source:` を載せる規約である。本文の出典表示と `langs`、Bot定義JSONも定義されている。BSAFは防災専用ではなく、スポーツ等にも使われるため、タグがあるだけで公的警報とは扱わない。

```ts
type BsafAppViewInput = {
  authorDid: string;
  atUri: string;
  cid: string;
  record: object; // 取得したapp.bsky.feed.post。書き換えない
  retrievedFrom: string;
  retrievedAt: Timestamp;
  botDefinitionUrl?: string;
  botDefinitionRetrievedAt?: Timestamp;
};
```

これはAppView内部の収集データの説明であって、新しい投稿Lexiconではない。表示用の派生索引は、元の `record` と分離して次の情報を持つ案とする。

| BSAF入力 | 派生索引の型・用途 | 他の項目と混同しないもの |
|---|---|---|
| `bsaf:v1` | 対応する規約版 | 本プロトコルの版ではない |
| `type:` | 文字列。情報種別 | 防災以外の種別も存在する |
| `value:` | 文字列。規模・程度 | 数値と決め付けず、配送優先度にも直接変換しない |
| `time:` | 原イベント時刻の文字列 | 投稿の `createdAt`・有効期間・配送期限ではない |
| `target:` | 文字列。対象コード | Geohash・緯度経度ではない |
| `source:` | 文字列。原情報源の識別 | 発信BotのDIDや機関の署名ではない |
| `text`・`langs` | 本文と原投稿の言語配列 | 自動翻訳・要約で元レコードを置き換えない |
| Bot定義の `filters` | `type`・`value`・`target` の選択肢と表示ラベル | タグ値の意味をBot定義なしで勝手に数値化しない |

タグ値・未知の追加タグは元レコードで保持する。未対応版や不完全なタグの投稿は構造化条件を推測せず、本文と原投稿への参照を扱えるようにする。Bot定義からの補足は署名済み原レコードの一部ではない。

原レコード、著者DID、atURI、取得したレコードのCID、本文と `source:` による出典を保持する。Bot定義から補う出典URL等は、どの定義をいつ取得した情報か区別する。ハンドルだけを恒久的な著者IDにしない。atURIはレコードの場所、CIDは取得した内容の識別であり、後から同じatURIを再取得して内容が変わった場合に混同しない。

処理は、既存投稿の取得 → 元レコードの保持 → 対応版のタグ解釈 → Bot定義と照合した表示用索引 → 独自Lexicon投稿と併置した一覧・地図表示、の順とする案である。索引は派生データであり、元投稿の署名対象フィールドを補修・書換えしない。BSAF投稿へ独自Lexiconの `Post`、必須の配送期限、`source` 拡張等への適合を要求しない。

`time:` は[B]上では元のイベント時刻であり、Botの `createdAt` や取得時刻ではない。`target:` は地域とは限らず、`jp-kanto` 等から正確な境界を推測しない。境界への対応資料がなければ地図上では領域不明とし、本文・タグ値は表示できる。`source:jma` やBot定義の名称だけで気象庁公式Botや機関署名と認定しない。CIDの保持も、AppView応答だけによるリポジトリ署名・包含の確認を意味しない。

[B]の異なるBot間の `type + value + time + target` による重複折りたたみは表示規則である。この組は原電文ID・内容ハッシュ・真正性を含まないため、0002の配送同値キーや取消判定へ流用しない。折りたたんでも各原投稿・著者・CID・出典を失わない。

**BSAF → Nostr/meshの再配信は未決定であり、この経路から自動投入しない。** 将来検討する場合も、元著者と再配信者の帰属、再配信者の署名、期限、更新・削除、対象選択を別途決める。元Botに代わって署名したり、原投稿を本プロトコル適合済みとして扱ったりしない。

## 7. EDXL-DE入力から共通投稿への具体例（提案）

### 7.1 入力の読み取り結果

以下は架空の公開配信から取得したDE 1.0 XMLの要点を、解析後のJSONで示した説明例である。EDXLの正式JSON表現でも、XMLへ戻せる完全な表現でもない。`combinedConfidentiality` は[D]付録Aの要素名を用いる（データ辞書には `combindedConfidentiality` の綴りも現れる）。

```json
{
  "EDXLDistribution": {
    "distributionID": "distribution-001",
    "senderID": "dispatch@example.org",
    "dateTimeSent": "2026-10-17T12:00:00+09:00",
    "distributionStatus": "Actual",
    "distributionType": "Report",
    "combinedConfidentiality": "UNCLASSIFIED AND NOT SENSITIVE",
    "targetArea": [{ "country": "JP" }],
    "contentObject": [
      {
        "contentDescription": "例市の避難情報",
        "confidentiality": "UNCLASSIFIED AND NOT SENSITIVE",
        "xmlContent": {
          "embeddedXMLContent": [
            {
              "alert": {
                "namespace": "urn:oasis:names:tc:emergency:cap:1.2",
                "identifier": "alert-001",
                "sender": "alerts@example.org",
                "sent": "2026-10-17T12:00:00+09:00",
                "status": "Actual",
                "msgType": "Alert",
                "scope": "Public",
                "info": [
                  {
                    "language": "ja",
                    "category": ["Safety"],
                    "event": "避難情報",
                    "urgency": "Immediate",
                    "severity": "Severe",
                    "certainty": "Observed",
                    "effective": "2026-10-17T12:00:00+09:00",
                    "expires": "2026-10-17T15:00:00+09:00",
                    "senderName": "例市",
                    "headline": "例地区の避難情報",
                    "instruction": "例地区では指定避難所へ避難してください。",
                    "area": [
                      { "areaDesc": "例地区", "circle": "35.0,139.0 2.5" }
                    ]
                  }
                ]
              }
            }
          ]
        }
      },
      {
        "contentDescription": "避難所案内PDF",
        "nonXMLContent": {
          "mimeType": "application/pdf",
          "uri": "https://example.org/shelters.pdf"
        }
      }
    ]
  }
}
```

### 7.2 生成する共通投稿

この例では提供元の公開配信プロファイルにより、容器全体と両内容の公開再配布が可能と判断できたものとする。機密区分文字列だけで判断したのではない。CAPの `info[0]` から一件の共通投稿を生成する。PDFは関連リンクとして記録するだけとし、PDF本体をmeshに投入せず、警報本文へ推測で取り込まない。

```json
{
  "text": "【例市・避難情報】例地区では指定避難所へ避難してください。\n発表: 2026-10-17 12:00 JST\n情報の有効期間: 12:00〜15:00 JST\n出典: 例市（配布: dispatch@example.org）。取り込みサーバーによる変換。\n避難所案内: https://example.org/shelters.pdf",
  "createdAt": "2026-10-17T03:00:10Z",
  "deliveryExpiresAt": "2026-10-17T04:00:00Z",
  "extensions": [
    {
      "$type": "dev.oriom.mosimo.defs#source",
      "document": {
        "format": "cap-1.2",
        "senderId": "alerts@example.org",
        "messageId": "alert-001",
        "sentAt": "2026-10-17T03:00:00Z",
        "part": "info[0]"
      },
      "containers": [
        {
          "format": "edxl-de-1.0",
          "senderId": "dispatch@example.org",
          "messageId": "distribution-001",
          "sentAt": "2026-10-17T03:00:00Z",
          "part": "contentObject[0]/xmlContent/embeddedXMLContent[0]"
        }
      ],
      "retrievedFrom": "https://example.org/distributions/001.xml",
      "retrievedAt": "2026-10-17T03:00:05Z",
      "converter": "did:example:ingester",
      "conversion": { "rule": "example-de-cap-text", "revision": "draft-a" },
      "links": [
        { "url": "https://example.org/shelters.pdf", "mediaType": "application/pdf" }
      ]
    },
    {
      "$type": "dev.oriom.mosimo.defs#publicInformation",
      "status": "Actual",
      "messageType": "Alert",
      "scope": "Public",
      "references": [],
      "language": "ja",
      "event": "避難情報",
      "categories": ["Safety"],
      "urgency": "Immediate",
      "severity": "Severe",
      "certainty": "Observed",
      "sourceFields": {
        "senderName": "例市",
        "headline": "例地区の避難情報",
        "instruction": "例地区では指定避難所へ避難してください。",
        "areas": [{ "areaDesc": "例地区", "circle": "35.0,139.0 2.5" }],
        "distribution": {
          "distributionStatus": "Actual",
          "distributionType": "Report",
          "combinedConfidentiality": "UNCLASSIFIED AND NOT SENSITIVE",
          "confidentiality": "UNCLASSIFIED AND NOT SENSITIVE",
          "targetArea": [{ "country": "JP" }]
        }
      }
    },
    {
      "$type": "dev.oriom.mosimo.defs#area",
      "regions": [{ "shape": "circle", "latitudeMicrodegrees": 35000000, "longitudeMicrodegrees": 139000000, "radiusMillimeters": 2500000 }]
    },
    {
      "$type": "dev.oriom.mosimo.defs#validity",
      "startsAt": "2026-10-17T03:00:00Z",
      "endsAt": "2026-10-17T06:00:00Z"
    }
  ]
}
```

`area`・`validity` は0011と共通の説明用形に合わせている。共通の座標は100万分の1度、距離はミリメートル単位の整数とする。例の数値は丸めなしで変換できる。`validity` の `startsAt`・`endsAt` はそれぞれ任意であり、不明な端点を補わない。確定wire仕様を意味しない。`did:example:ingester`、URL、変換規則名、期限は架空の例示であり、実運用DID method・登録名・既定時間の採用ではない。外部標準の版番号や半径の単位換算と、本プロトコルの未決の上限値は区別する。

### 7.3 マッピングと処理結果

| 入力・判断 | 出力・処理 |
|---|---|
| DEの送信者・配布ID・送信時刻 | `source.containers[0]`。CAP発表者へ置き換えない |
| `contentObject[0]` 内のCAP | `source.document` と `public-information` の直接の出典 |
| CAPの `info[0]` | 日本語の本文と、その情報単位の期間・領域 |
| DEの `targetArea.country = JP` | 原配布意図として保持。全国を警報の影響範囲にはしない |
| CAPの `scope = Public` と提供元プロファイル等 | 公開可と判断した内容のみ投稿生成 |
| `headline`・`instruction`・`senderName` | 単独で読める本文、原情報の意味を持つ拡張 |
| `sent`、DEの `dateTimeSent` | UTC変換したそれぞれの原時刻。`createdAt` とは別 |
| 取得・投稿生成 | `retrievedAt = 03:00:05Z`、`createdAt = 03:00:10Z` |
| `effective`・`expires` | `validity` の `03:00:00Z`〜`06:00:00Z` |
| 運用者の配送判断 | `deliveryExpiresAt = 04:00:00Z`。内容の終了時刻ではない |
| CAPの座標 `35.0,139.0` と半径 `2.5` km | `latitudeMicrodegrees = 35000000`、`longitudeMicrodegrees = 139000000`、`radiusMillimeters = 2500000`。CAP文字列も原形式側に保持 |
| `contentObject[1]` のPDF参照 | 本文・出典の関連リンク。添付本体の配送や別の警報投稿は生成しない |
| `references` なし | 新規発表として空配列。訂正対象を推測しない |
| 生成された一件の `Post` | 1レコード・1 commit・1 Nostr `kind: 1`。`content` に `text` |

別の `contentObject` にもう一つのCAPがあれば、それも独立に公開可否を評価して情報単位ごとに投稿を生成する。一つのDEを一つの投稿・commitに固定しない。取り込み側には、どの内容を投稿化し、どの内容をリンク扱い・非対応・制限ありとして保留したかを記録する。原XML全体が配送されたかのような表示をしない。

## 8. 訂正・取消、領域変換、配送同値性

### 8.1 訂正・取消の扱い

先行電文への参照は、先行投稿が未取得でも保持する。更新・取消を受信したら新しい本文投稿として伝え、既存投稿の上書きや原著者の署名の再生成を行わない。取消を「この警報は取り消された」という現在の通知として伝える場合、先行警報の終了時刻を取消通知の配送期限へ機械的にコピーしない。

対象を関連付ける際は、原発表者・変換者への信頼と参照の層を考慮する。別の著者による訂正の主張を、原発表者の公式訂正と表示しない。訂正が先に到着した場合や、複数の変換者が同じ取消を伝えた場合も、個々の署名と出典を保持する。到着順だけで最新版や現在有効な情報を確定しない。

### 8.2 共通領域へ変換できない場合

CAP・DEの多角形、気象庁やBSAFの地域コードは、そのまま共通 `area` のGeohashではない。信頼できる境界資料と版付き変換規則がなければ、正確な領域を生成しない。近似を行う方式・誤差・表示は未決定であり、本書は必須にしない。

一部の円だけを取り出した場合、それを元領域全体と表示してはならない。未解決の要素が残り、解釈できる要素にも含まれない地点は、0004に従い範囲外ではなく判定不明とする。[0011 §5](0011-extensions.md#5-影響範囲-area型案) の型案には未解決部分の表現をまだ設けていないため、その場合は共通 `area` を省略し、原形式側に領域情報を保持する。高度条件を無視した二次元表示についても原情報の適用条件を失ったと誤認させない。

### 8.3 配送同値性

0002の候補は、正規化規則の版・情報源ID・原情報ID/版・正規化内容ハッシュである。本書ではハッシュ対象、キー形式、アルゴリズム、代表イベントの選択を確定しない。

- 原文バイト列のハッシュ、原電文の識別、選択した言語・対象、変換結果の同値性はそれぞれ別に評価する。
- 同じCAPが異なるDEに内包されても、内包元の容器や公開条件の違いは保持する。容器ID・配列位置だけで内包情報の同値性を決めない。
- 同じ電文から生成した異言語・異なる対象の投稿を、一件配送すれば他も配送済みとはしない。
- 原情報IDが同じでも内容差・版差・訂正・取消を潰さない。再取得・再バンドルを配送期限の延長理由にしない。
- 信頼する変換者の署名と変換内容等を確認する前に、自己申告の同値キーで配送を止めない。同値と判断しても他者の署名を合成しない。

## 9. 残る重要な判断

1. **変換プロファイルの固定:** 入力仕様の版、気象庁の電文種別、CAPの言語・対象別分割、本文生成、原電文ID・版の対応、変換規則の公開方法。
2. **拡張とbindings:** `source`・`public-information` の実スキーマ、参照表現、版、サイズ上限、Bytes 項目・時刻項目のタグ名と署名対象への配置。Bytes の独自タグでの文字列符号化は0012 §3.5の合意に従う。`area`・`validity`・`annotation` との接続。
3. **公開条件の解釈:** DEの自由記述の機密区分・宛先・役割・組織固有プロファイルから公開可否を決める運用。限定内容を取り除いた再編集の可否は自動承認しない。
4. **原文と取得証言:** 原文の保存場所・保持期間・ハッシュ対象・取得と変換の証言形式。原文全量配送と完全可逆変換は未合意のままとする。
5. **信頼と同値配送:** 信頼リストの発行・更新・失効、機関鍵の認証、意味正規化、同値キー、異なる変換者の署名・commit・適用元 CID・レコード・path の保持。
6. **訂正・取消の表示:** 原情報参照から派生投稿への対応、未着・相反・複数の訂正、容器単位の取消と内容単位の取消の関係。
7. **BSAFの境界:** 外部仕様の固定リビジョン、Bot定義の更新・信頼、地域境界資料。Nostr/mesh再配信を行うか、および帰属・署名・期限・更新/削除の方式は未決定。

## 10. 一次資料・引用URLと確認範囲

すべて2026-10-17に取得確認した。リンク先の変動するページを、固定リビジョンの規範仕様として採用したものではない。外部資料に記載された数値・語彙を、本プロトコルの既定値・登録名として自動採用しない。

| 記号 | 一次資料 | 今回確認した範囲と限界 |
|---|---|---|
| A | [atproto Data Model](https://atproto.com/specs/data-model) | Data Types、Why No Floats?、JSON Representationを確認。浮動小数点数は不可、整数は符号付き64 bit。共通の座標・距離は0010の合意済み固定単位の整数を使う。原形式の数値文字列の保持とは別であり、外部標準の専用座標型ではない |
| C | [OASIS CAP 1.2, OASIS Standard, 2010-07-01](https://docs.oasis-open.org/emergency/cap/v1.2/CAP-v1.2-os.html) | §1.3、§3.2、§3.3、スキーマ、多言語例。`info` の多重性、公開範囲、更新・取消参照、期間、円のkm、時刻、任意のXML署名。国別プロファイルや実発表の運用は未確認 |
| D | [OASIS EDXL-DE 1.0, OASIS Standard, 2006-05-01](https://docs.oasis-open.org/emergency/edxl-de/v1.0/EDXL-DE_Spec_v1.0.pdf) | PDF本文をテキスト抽出し、§1.3、§2、§3.2、付録Aを確認。複数内容、XML/非XML、容器・内容の機密区分、配布地域、参照形式。組織別の公開区分やEDXL仕様群全体は確認対象外 |
| J1 | [気象庁XML情報提供ページ](https://xml.kishou.go.jp/) | 公開PULL型の案内、技術資料への入口。電文署名・公開鍵の案内は確認できず、全経路についての結論ではない |
| J2 | [気象庁XML形式電文の公開（PULL型）](https://xml.kishou.go.jp/xmlpull.html) | Atomフィードと個別電文取得の案内、停止・遅延とアクセス量の留意事項。公開元の数値制限は本プロトコルの配送上限ではない |
| J3 | [技術資料一覧](https://xml.kishou.go.jp/tec_material.html)、[XMLフォーマット Ver.1.3 本文（2022-12-09版）](https://xml.kishou.go.jp/jmaxml_20221209_format_v1_3.pdf) | PDFの§1.2、§1.3の共通構造・時刻・曖昧さ・座標・地域コード・単位を確認。技術資料一覧は更新されるため、本文PDFの版と区別 |
| J4 | [XMLスキーマ一式（2024-10-31版）](https://xml.kishou.go.jp/jmaxml_20241031_Schema(xsd).zip) | ZIP内の共通部・ヘッダ部の要素名を確認。電文別解説資料セット、辞書・コード表全体の意味、全種別の更新・取消の運用までは確認していない |
| B | [BSAF日本語仕様](https://github.com/osprey74/bsaf-protocol/blob/main/docs/bsaf-spec-ja.md)、[今回取得したraw本文](https://raw.githubusercontent.com/osprey74/bsaf-protocol/main/docs/bsaf-spec-ja.md) | v1タグ、Bot定義JSON、本文の出典・言語、元イベント時刻、異なるBot間の表示折りたたみを確認。`main` 参照でcommit未固定。Bot実装・実運用・Bluesky Lexicon側の上限は独立確認していないため、BSAF文書中の上限値を本書の要件にしない |

取得時にDE 1.0の同名HTML URLは404だったため、公開されている公式PDF本文を参照した。DE 2.0については公式ディレクトリの存在までで本文の比較はしておらず、本書のDE 1.0のキーや語彙を他版へそのまま適用しない。
