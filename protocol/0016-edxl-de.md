# 0016: EDXL-DE 1.0 著者拡張と内容の取り込み

状態: draft-00 — 合意済みの独立拡張・内包内容別の投稿化を具体化した仕様草案。型名は管理ドメインの割り当て委任に基づく。フィールドと処理は実装案であり、§9 の未決事項を含む。正式なLexicon JSONは未実装。

## 1. 適用範囲と責務

OASIS EDXL Distribution Element 1.0（2006-05-01）、XML名前空間 `urn:oasis:names:tc:emergency:EDXL:DE:1.0` を対象とする。EDXL仕様群全体やDE 2.0の対応を宣言するものではない。DEは配布容器であり、その中の情報をすべてCAP・警報とみなさない。

[0013](0013-public-information.md) の公開取り込み方針に従い、内包内容ごとに形式と公開可否を判断する。CAPなら [0015](0015-cap.md) の情報単位ごとの投稿化を適用する。各投稿は1レコード・1 commit・1 Nostrイベントとし、DE原XML全量の配送は必須にしない。

本拡張は配布者・配布意図・選択した内容の容器内の位置を担当する。内包CAPの発表者・原電文参照・警報対象を置き換えない。容器送信者、内包文書の発表者、取得・変換して投稿する著者の三者を区別する。

## 2. 識別と独立性

`$type` は `dev.oriom.mosimo.defs#edxlDe10` とする。この型だけでDE 1.0とその配布メタデータを識別でき、CAP拡張や `source` を必須の依存先にしない。

CAPから生成した投稿には、必要に応じて `cap12` と `edxlDe10` を独立した拡張として併用できる。前者は警報の意味、後者は配布容器の意味を扱う。CAPに対応していてもDEに非対応の端末、またはその逆の部分実装を許容する。DEの情報を旧 `publicInformation.sourceFields` やCAP拡張の内部へ混在させない。

共通の `source` は取得経路・変換規則・容器の参照を担当する。出典追跡だけが目的なら `source.containers` の利用も可能であり、すべての変換投稿に本拡張の同梱を強制しない。ただし、同梱しないことを取り込み時の配布制約を無視する理由にしない。

## 3. データ型

以下はTypeScript風の説明用表記である。XMLの数値・日時の原表記は文字列で保持し、floatを使わない。共通 `Timestamp` へ変換する取得時刻等とは別の原形式の情報である。

```ts
type EdxlDistributionReference = {
  distributionID: string;
  senderID: string;
  dateTimeSent: string; // 原形式の日時。共通Timestampではない
};

type EdxlValueList = {
  valueListUrn: string;
  values: string[];
};

type EdxlExplicitAddress = {
  scheme: string;
  values: string[];
};

type EdxlTargetArea = {
  circles?: string[];
  polygons?: string[];
  countries?: string[];
  subdivisions?: string[];
  locCodesUN?: string[];
};

type EdxlNonXmlReference = {
  mimeType: string;
  size?: string;
  digest?: string;
  uri?: string;
};

type EdxlSelectedContent = {
  index: integer;                 // contentObjectのゼロ始まり位置
  embeddedXMLIndex?: integer;    // embeddedXMLContentのゼロ始まり位置
  documentIndex?: integer;       // その中の文書要素のゼロ始まり位置
  contentDescription?: string;
  contentKeywords?: EdxlValueList[];
  incidentID?: string;
  incidentDescription?: string;
  originatorRoles?: EdxlValueList[];
  consumerRoles?: EdxlValueList[];
  confidentiality?: string;
  nonXMLContent?: EdxlNonXmlReference;
};

type EdxlDeExtension = {
  $type: "dev.oriom.mosimo.defs#edxlDe10";
  distribution: EdxlDistributionReference;
  distributionStatus: string;
  distributionType: string;
  combinedConfidentiality: string;
  language?: string;
  senderRoles?: EdxlValueList[];
  recipientRoles?: EdxlValueList[];
  keywords?: EdxlValueList[];
  references: EdxlDistributionReference[];
  explicitAddresses?: EdxlExplicitAddress[];
  targetAreas?: EdxlTargetArea[];
  content?: EdxlSelectedContent;
};
```

`integer` はatprotoの整数であり、選択位置は0以上とする。内容を選択した投稿では `content` を含める。容器単位の取消通知では `content` を省略する。発行・配送規則は §7.1 に従う。その他の容器単位の通知の扱いは未決定である。`content` の省略を「すべての内包警報の取消」と解釈しない。

`distribution` は原DEの `distributionID`・`senderID`・`dateTimeSent` に対応する。`references` は原 `distributionReference` の三項目を分解した配列で、元の並びは **distributionID,senderID,dateTimeSent** である。CAPの **sender,identifier,sent** と混同しない。曖昧・解析不能な参照を推測で補完して置換対象にしない。

| 原DEの項目 | 拡張内の配置 |
|---|---|
| `senderRole`／`recipientRole`／`keyword` | `senderRoles`／`recipientRoles`／`keywords` |
| `valueListUrn`と複数の`value` | `EdxlValueList.valueListUrn`と`values` |
| `explicitAddressScheme`と複数の`explicitAddressValue` | `scheme`と`values` |
| `targetArea`内の`circle`／`polygon`／`country`／`subdivision`／`locCodeUN` | `circles`／`polygons`／`countries`／`subdivisions`／`locCodesUN` |
| 内容の`contentKeyword`／`originatorRole`／`consumerRole` | `contentKeywords`／`originatorRoles`／`consumerRoles` |

複数回出現する項目と値は配列で順序を保ち、同じURNやschemeをキーにした辞書へ統合しない。省略と空文字列を区別する。原文字列はXMLパーサーが返す値を保持し、元XMLの綴りを完全復元するものではない。未知の著者拡張・未知フィールドの保持は0011に従う。

`combinedConfidentiality` は付録Aのスキーマの綴りを採る。本文のデータ辞書に現れる `combindedConfidentiality` を新しいwireフィールドとして追加しない。この違いを理由に原XMLを書き換えたことにもせず、入力プロファイルでの扱いは別途固定する。

## 4. 内容の選択と投稿化

1. 容器全体の配布条件と公開再配布の可否を評価する。
2. `contentObject` を文書順に列挙し、その内容固有の条件も評価する。
3. `xmlContent/embeddedXMLContent` の各容器と、その中の各文書要素を列挙する。名前空間と形式を識別して、対応する変換処理へ渡す。
4. CAP等から投稿を作る際に、元の内容位置と必要な配布メタデータを本拡張へ格納する。位置は原DE内の位置であり、別のDEに移った同じCAPの同一性を示すものではない。
5. 取り込み・変換者が通常の投稿として署名する。中継者が既存の署名付きCAP投稿へ本拡張を後付けしない。

XML内包文書を選択した場合、`embeddedXMLIndex` と `documentIndex` を対で持ち、`nonXMLContent` は同じ選択に付けない。非XML内容を選択した場合は両インデックスを省略し、`nonXMLContent` に参照情報を持つ。`keyXMLContent` は抜粋・識別用の内容であり、それだけを完全な原文として独立警報へ変換しない。対応する完全な内容を取得できず意味を確定できない場合は保留する。

`nonXMLContent` については、内容の型を明確に扱う変換プロファイルがない限り、記述・参照を関連リンクとして扱う。`contentData` のBase64の本体をこの拡張へコピーせず、自動取得・実行・メディア配送を要求しない。リンクの表示と、その取得が安全・可能・許可済みであることを区別する。

非対応の形式、意味に影響する未知の内容固有拡張を、CAPに似ているという理由で警報として扱わない。入れ子のDEや特定分野のXMLへの対応は、専用の取り込みプロファイルで定義する。型・内容の対応に必要な情報を省いて無条件の転載へ変えない。

## 5. 公開条件と信頼

機密区分は自由な文字列を含むため、`combinedConfidentiality` や内容の `confidentiality` を共通の真偽値へ変換しない。`UNCLASSIFIED AND NOT SENSITIVE` 等の表記だけから無条件の公開許可を導かない。提供元の配信プロファイル・利用条件と、容器と内容の条件を併せて判断する。制約の意味が不明な場合は公開投入を保留する。

明示宛先・受信者の役割・内容の対象者の指定があれば、それが公開可能な選択条件か、公開してはならない制限かを区別する。本プロトコルは初期仕様で公開情報のみを扱うため、限定情報を取り込んでから中継端末の対応状況に期待して保護する構成にはしない。非対応端末が本拡張を無視しても漏えいしないよう、公開可否は署名前の取り込みで判断する。

`senderRole`・`originatorRole` は原形式上の申告であり、証明書の代わりではない。容器に記載された送信者と投稿の署名者を同一視しない。取り込みサーバーの署名は取得・変換の主張であり、配布元や内包情報の発表者自身の署名を再現するものではない。

## 6. 配送での利用と部分実装

`targetArea` は配布意図を示す地域であり、内包警報の影響範囲ではない。対応端末は、配布プロファイルとローカル方針の範囲で、その地域へ届く転送を優先できる。一方、内包内容の共通 `area` をこの値で上書き・統合せず、共通の `origin` にも置き換えない。

- 複数の `targetArea` の意味はDEが定める地域の和集合として読む。
- 国・行政区分・UNの地域コードをGeohashとみなさない。コード体系と境界を解決できなければ配布地域の判定は不明とする。
- 原DEの円・多角形の表記は本拡張内に保持する。DEのデータ辞書の円の表記説明と例には差があるため、CAP用の円パーサーを無条件に流用せず、入力プロファイルで受け付ける表記を定める。
- 配布地域や役割の申告だけで帯域・保存枠を優遇する権限を得るとはしない。信頼と資源上限を別に適用する。
- 配布意図の範囲外への中継やゲートウェイへの搬出を一律禁止するものではない。機密保護はこのbest-effortな地域選択に依存させない。

基本対応端末は本文と配送期限等だけを利用できる。CAP対応端末は `cap12` の警報内容を利用し、共通領域対応端末は `area` を利用する。DEの役割・地域・配布種別に非対応でも、未知拡張を保持して通常の上限内で中継できる。未知のURNの役割を端末権限や認証済みの所属と推測しない。

DEの `dateTimeSent` は容器の送信時刻であり、CAPの発表時刻・投稿の作成時刻・内容の有効開始・配送期限へ無条件にコピーしない。`distributionStatus` と `distributionType` は原語彙を保持し、`Actual` や `Report` だけで内包内容も実災害向けの公開警報であると判定しない。

## 7. 容器の更新・取消と内包情報

`distributionType: Update`／`Cancel` と `references` は、参照された配布容器への更新・取消を示す。内包CAPの `msgType: Update`／`Cancel` と `references` は、CAP原電文への更新・取消を示す。この二つの層を混同しない。

内包CAP自身の認可済み更新・取消による旧版拡散停止は [0015 §7.1](0015-cap.md#71-原電文単位の置換合意済み) に従う。同じCAPが別のDEや直接の公式経路でも配布されている場合、あるDEの取消だけでCAP自身を無効とし、別の出典に基づく投稿まで停止してはならない。同じ署名付き投稿を別リレーや別無線経路から受信しただけでは、別の出典に基づく新しい投稿にはならない。

容器の取消は §7.1、更新は §7.2 の範囲で配送・拡散停止に反映する。これらは本プロトコルへの取り込み・配送規則であり、DEの更新から内包CAPの更新・取消を推測する規則ではない。

CAPの `AllClear` や情報の有効期間終了、DEの `Ack`、MNDTの保存受理も互いに別である。DEの `Ack` をBundle配送完了や最終利用者の受領証として扱わない。

### 7.1 容器由来の投稿の取消（合意済み）

公開可能なDEの取消を取り込んだ場合、対象容器への参照を持つ独立した通知投稿を1件生成する。通常の基本投稿と同じ1レコード・1 commit・1 Nostrイベントとし、本拡張を次のように用いる。専用の別イベント種別や削除要求へ変換しない。

- `distribution` は今回の取消電文自身の識別とし、取り消す古い容器の識別へ置き換えない。
- `distributionType` は `Cancel`、`references` は取消対象のDE原参照を持つ。対象未取得でも参照を保持する。対象を確定できない参照から推測で拡散停止の範囲を作らない。
- 容器単位の通知なので `content` は省略する。内包CAPの `Cancel` を捏造した `cap12` 拡張は付けない。
- 本文には配布元、取り消された配布容器、配布の取消であることを示す。内包警報自体の取消を意味しないことも伝え、旧投稿を取得しなくても通知の意味が分かるようにする。
- 投稿作成時に通知自身の配送期限を確定し、旧投稿の期限や内包警報の有効終了を流用しない。取消通知自身も公開可否の判断を経て発行する。

対応端末は、事前保持したルート鍵へのチェーンと、対象容器の配布元に対応する取消権限を確認できた場合に限り、参照された容器由来の投稿の新たな `INVENTORY` 広告・転送開始を停止する。認可は [0009 §5.1](0009-identity-attestations.md#51-公的情報の更新取消の認可合意済み) に従う。対象投稿についても、確認できた容器の由来との対応を必要とし、本文や出典の自己申告の文字列一致だけで対象を広げない。取消対象を単に参照している別の通知まで止めない。

認可済みの対象容器を記録し、旧対象が後着しても再拡散を抑える。一つの容器から生成された言語・内容別の投稿群は対象となるが、同じCAPを別のDEや直接の公式配信から取り込んだ別投稿は、この容器の取消だけでは止めない。別リレーから受信しただけの同一イベントは別出典とは扱わない。

容器の取消は配布を撤回した状態として表示し、発表機関が警報を撤回したという表示やNIP-09による削除に置き換えない。旧投稿の署名・履歴は保持でき、取消通知自身は独立した配送候補として扱う。認可を確認できない場合は停止を保留し、未対応端末まで即時に止められることも保証しない。

具体的な出典索引、取消記録の保持期限・容量、相反する通知や証明の失効時の再評価は未決定である。一般の配送同値性によって別の容器由来の投稿を省略する場合も、容器の取消と内包情報の取消を混同してはならない。

### 7.2 新容器の内容による再発行（合意済み）

公開可能な `distributionType: Update` のDEを取り込んだ場合、新容器に含まれる内容だけを各形式の処理へ渡し、単独で読める新しい投稿群を生成する。旧容器との差分だけを配送せず、新容器にない旧内容を引き継がない。各投稿は通常の1レコード・1 commit・1 Nostrイベントとする。

各投稿の `edxlDe10` 拡張は、`distribution` に新容器自身の識別、`distributionType` に `Update`、`references` に原DEの更新対象参照、`content` に新容器内で選択した内容の位置を保持する。内包CAPの識別や `msgType` はCAP自身の値を保持し、DEが `Update` であることだけを理由にCAPも `Update` に書き換えない。本文・共通領域・有効期間・配送期限は新しい内容に基づいて定め、旧投稿の値を無条件にコピーしない。

対応端末は、事前保持したルート鍵へのチェーン、対象容器の配布元に対応する更新権限、および対象投稿の容器由来との対応を確認できた場合に限り、明示的に参照された旧容器由来の投稿群の新たな `INVENTORY` 広告・転送開始を停止する。認可の境界は §7.1 および0009 §5.1と同じであり、取り込み先への一般的な信頼や自己申告の出典一致だけでは停止しない。対象未着でも認可済みの更新対象を記録し、旧投稿の後着時に再拡散を抑える。全ての新投稿が到着することを停止の条件にはしないが、一部の到着をもって新容器の全内容を受領したとも扱わない。

新容器由来の投稿群は、それぞれ独立した配送候補とする。旧容器への更新参照を持つことだけで、新投稿自身を旧対象として止めない。同じ内包情報を別の容器・直接の公式配信から取り込んだ別投稿へ停止を広げず、旧署名・履歴は削除・改変しない。認可できない場合は旧対象の停止を保留し、未対応端末や未接触端末まで即時に止める保証はない。

例えば、旧容器D1に別々のCAP電文A・B、新容器D2にA・Cが含まれ、D2がD1を更新する場合、新規投稿はD2のA・Cから生成する。認可を確認した端末はD1由来のA・Bの新規拡散を止めるが、CAP B自体を取り消したり、Bの危険が解消したと表示したりしない。別容器や直接配信由来のBの別投稿は停止しない。内包CAP自身が旧CAPを参照する `Update`／`Cancel` であれば、その認可と効果は0015 §7.1に従って別に適用する。

新容器の全内容を表す受領・完了通知、内容を選択しない容器単位の更新通知、出典索引・更新記録の保持期限と容量、相反更新の扱いは本節では定めない。

## 8. データ表現と例

本拡張も0012の共通の自己完結タグで運ぶ。

```text
タグ名: mosimo.oriom.dev:ext:dev.oriom.mosimo.defs#edxlDe10
タグ値: $type以外の全フィールドのatproto CBORを通常Base64・paddingなしで符号化
```

メッシュでは共通規則で値を生バイト列にする。独自のNostr kind・BPv7の必須独自ブロックは追加しない。イベントの32 KiB上限に本拡張も含む。原情報や署名済み拡張を中継時に削って収めない。

次は0013の架空の例で選択したCAPを包むDEの情報である。原XMLの公開再配布条件は取り込み側で確認済みと仮定する。

```json
{
  "$type": "dev.oriom.mosimo.defs#edxlDe10",
  "distribution": {
    "distributionID": "distribution-001",
    "senderID": "dispatch@example.org",
    "dateTimeSent": "2026-10-17T12:00:00+09:00"
  },
  "distributionStatus": "Actual",
  "distributionType": "Report",
  "combinedConfidentiality": "UNCLASSIFIED AND NOT SENSITIVE",
  "references": [],
  "targetAreas": [{ "countries": ["JP"] }],
  "content": {
    "index": 0,
    "embeddedXMLIndex": 0,
    "documentIndex": 0,
    "contentDescription": "例市の避難情報",
    "confidentiality": "UNCLASSIFIED AND NOT SENSITIVE"
  }
}
```

この `JP` は配布地域であり、同梱するCAPの警報対象が日本全国であるという意味ではない。CAPの本文・期間・影響範囲は内包CAPから独立に生成する。

## 9. 残る判断

- 容器の取消通知は §7.1、新容器の内容だけによる再発行と旧容器由来だけの新規拡散停止は §7.2 で合意済み。出典索引、更新・取消記録の保持期限・容量、相反更新、内容を選択しない容器単位の更新通知は未決定。
- 配布元・国別・組織別の機密区分・役割URN・宛先schemeの意味を固定する取り込みプロファイル。
- DE 1.0の本文と付録スキーマの表記差、参照の厳密な分解、地域判定・入れ子の取り込み範囲。
- DEと内包内容の信頼の対応、証明書の用途・範囲の符号化、内容の配送同値性、サイズ超過時の扱い。

## 10. 参照

- [OASIS Emergency Data Exchange Language Distribution Element Version 1.0, OASIS Standard, 2006-05-01](https://docs.oasis-open.org/emergency/edxl-de/v1.0/EDXL-DE_Spec_v1.0.pdf) — §3.2、付録A。2026-10-18に本文・付録スキーマを再確認。
- [0013: 公的情報の取り込みと出典](0013-public-information.md)
- [0015: CAP 1.2 著者拡張](0015-cap.md)
- [0011: 著者署名付き拡張ブロック](0011-extensions.md)
- [0009: 署名チェーンによる身元証明](0009-identity-attestations.md)
