# BPv7・拡張ブロック・未知ブロック中継の調査

調査日: 2026-10-16

対象コミット: `7f188ad398940bc1a5b0392f4e4eafa96fe00ff3`

本書は実装の修正案をまとめた調査報告であり、製品コードは変更していない。
RFC 9171 を主な基準とし、RFC 9172/9173、RFC 9171 の errata と更新 RFC も確認した。
全 MUST/SHOULD の網羅的な適合認証ではないが、下記の不一致により「RFC 準拠の BPv7 実装」とは現状では判定できない。

## 1. 結論

| 質問 | 判定 | 根拠 |
| --- | --- | --- |
| BPv7 を正しく実装しているか | 部分実装。重要な不一致がある | ブロック処理フラグ、CRC 検証、ipn EID、入力検証、滞留時間処理など |
| 拡張ブロックに対応しているか | 対応機構と標準 3 種の実装がある。ただし制約検証等が不十分 | Previous Node、Bundle Age、Hop Count と codec 登録 API がある |
| 未知拡張を保持して中継できるか | 通常のシリアライズ経路ではできない | CRC なしでは読み取り後の再エンコードが失敗。CRC ありでは受信時の CRC 検証中にも同じ失敗が起きる |

最優先は **未知ブロックの不透明な byte string の往復、RFC のフラグ値、受信バイト列上での CRC 検証**を一緒に直すこと。
未知の型を一つずつ登録する回避策では、将来追加される型をそのまま中継する要件を満たさない。

## 2. RFC における「そのまま中継」の意味

[RFC 9171 §4.3.2](https://www.rfc-editor.org/rfc/rfc9171.html#section-4.3.2) により、
canonical block の型固有データは definite-length CBOR byte string である。
中継ノードは未知の拡張の内部形式を理解する必要はない。

ただし「未知なら無条件に転送」ではない。
[§4.2.4](https://www.rfc-editor.org/rfc/rfc9171.html#section-4.2.4) と
[§5.6 Step 4](https://www.rfc-editor.org/rfc/rfc9171.html#section-5.6) に従い、
処理できない拡張は次のように扱う。

| ビット / マスク | RFC の意味 |
| --- | --- |
| bit 0 / `0x01` | 各フラグメントへの複製 |
| bit 1 / `0x02` | status reporting が有効なら、処理不能を理由とする受信報告を生成する対象 |
| bit 2 / `0x04` | バンドル全体を削除し、受信処理を終了 |
| bit 4 / `0x10` | バンドル削除指定がなければ、このブロックを除去 |
| 削除・除去指定なし | 未知ブロックを保持して処理を続行 |

報告指定は、保持・削除・除去とは独立して評価する。バンドル削除はブロック除去より優先する。
未知の処理フラグ自体は無視する。BPv6 の「未処理のまま転送した」フラグを BPv7 に追加してはならない。

また、バンドル全体を byte-for-byte コピーすることが要件ではない。
[§5.4](https://www.rfc-editor.org/rfc/rfc9171.html#section-5.4) の Previous Node の除去・挿入や
Bundle Age の更新などは必要である。一方、未知ブロックのデータを解釈・改変する必要はなく、
ブロック番号やフラグも維持する。primary block の CBOR フィールド値は
[§4.3.1](https://www.rfc-editor.org/rfc/rfc9171.html#section-4.3.1) により不変である。
実装方針として、変更しない受信ブロックの元の符号化も保持するのが安全である。

## 3. 現在の対応範囲

[BlockExtensionManager.kt:16-69](bpv7/src/main/java/io/nodle/dtn/bpv7/BlockExtensionManager.kt#L16-L69) に次の codec が登録されている。

| 型 | 実装状況 |
| --- | --- |
| 6: Previous Node | EID の読み書きと、転送前のデータ更新あり |
| 7: Bundle Age | 整数の読み書きと、転送前の加算あり。ただし再試行・期限管理に問題 |
| 10: Hop Count | `[limit, count]` の読み書き、転送前の increment と上限超過判定あり |
| 11: BIB | ASB と独自 Ed25519 context の実装。現行 RFC の BPSec とは非互換な点あり |
| 12: BCB | ASB codec の登録はあるが、標準 AES-GCM context、復号処理等は実装されていない |
| その他 | `BlobBlockData` に読む fallback はあるが、書く fallback がない |

処理本体は [BundleProcessor.kt:224-258](agent/src/main/java/io/nodle/dtn/BundleProcessor.kt#L224-L258)。
RFC 9171 §4.4 が要求する標準 3 種を全く未実装というわけではないが、
「codec がある」ことと「RFC の意味論・制約を実装している」ことは区別すべきである。

`addBpv7BlockExtension()` は parser/encoder を追加する API であり、
任意の拡張の受信・転送時アクションまで登録する仕組みではない。
`isBpv7BlockExtensionKnown()` は parser の登録だけで「既知」と判定するため、
codec 登録だけでその拡張の処理仕様を満たしたことにはならない。

## 4. 優先度付きの問題点と修正案

### P0-1: 未知ブロックを読み取れても書き戻せない

**確認済み。**

- [CborUnmarshal.kt:99-105](bpv7/src/main/java/io/nodle/dtn/bpv7/CborUnmarshal.kt#L99-L105):
  未登録の型は `BlobBlockData(readByteArray())` として保持する。
- [CborMarshal.kt:148-158](bpv7/src/main/java/io/nodle/dtn/bpv7/CborMarshal.kt#L148-L158):
  未登録の型が payload 以外なら `CborEncodingException("block type is unknown")`。
- [CanonicalBlock.kt:59-66](bpv7/src/main/java/io/nodle/dtn/bpv7/CanonicalBlock.kt#L59-L66):
  CRC 検証でも書き戻しを呼ぶ。そのため CRC 付き未知ブロックは受信段階で失敗する。

HTTP CLA の送信も [ClaHttpClient.kt:69](cla/src/main/java/io/nodle/dtn/cla/http/ClaHttpClient.kt#L69) で再エンコードする。
SQL の [BundleConverter:72-79](storage/storage-sqldb/src/main/java/io/nodle/dtn/storage/BundleEntryUtils.kt#L72-L79)、
Room の [BundleConverter:129-138](storage/storage-roomdb/src/main/java/io/nodle/dtn/storage/BundleEntry.kt#L129-L138) も同じ経路なので、
単なる「ネットワーク転送だけ」の問題ではなく、store-carry-forward の永続化にも影響する。
断片化のコピーも [Fragmentation.kt:54-55](bpv7/src/main/java/io/nodle/dtn/bpv7/Fragmentation.kt#L54-L55) で同じ経路を通る。

**最小修正案:** 未登録の型で `data is BlobBlockData` なら、その `buffer` を `writeBinary()` で出力する。
内部データを CBOR として再解釈しない。型とデータ表現の不整合は明示的な encoding error とする。
まずはこの修正で未知の通常拡張の往復を可能にし、CRC 修正も同時に行う。

既知型の parser が投げた例外を無差別に catch して raw に戻すだけの修正は避ける。
既知の壊れたブロックと、正しいが未対応の拡張を区別できなくなるためである。

### P0-2: ブロック処理フラグの値が RFC と違う

**定義・受信動作とも確認済み。**

[BlockV7Flags.kt:19-26](bpv7/src/main/java/io/nodle/dtn/bpv7/BlockV7Flags.kt#L19-L26):

| 用途 | RFC | 実装 |
| --- | --- | --- |
| 処理不能の報告 | bit 1 / `0x02` | bit 2 / `0x04` |
| バンドル削除 | bit 2 / `0x04` | bit 3 / `0x08` |
| ブロック除去 | bit 4 / `0x10` | bit 1 / `0x02` |

つまり標準実装が `0x02` で「報告して保持」を要求すると、本実装では未知ブロックが除去される。
`0x04` でバンドル削除を要求しても削除されず、逆に予約 bit 3 で削除される。

**修正案:**

1. offset を `StatusReportIfNotProcessed(1)`、`DeleteBundleIfNotProcessed(2)`、
   `DiscardIfNotProcessed(4)` に修正する。
2. [受信処理:82-114](agent/src/main/java/io/nodle/dtn/BundleProcessor.kt#L82-L114) を報告 → バンドル削除 → ブロック除去の順にする。
3. RFC に存在しない `BlockIsEncrypted` は標準フラグとして扱わない。
   この名前の参照は宣言以外には見つからなかった。
4. 数値リテラルによるテストを追加する。同じ誤った enum で入力と期待値を組み立てると不一致を見逃す。

**移行上の注意:** これは wire compatibility の修正である。
旧版同士の通信・保存済みバンドルが旧値を使っている場合は移行方針が必要。
同じビット列に二つの意味を自動推測して適用してはならない。
標準値をデフォルトとし、旧形式が必要なら入口・保存形式のバージョンで明確に分離する。

### P0-3: CRC が「受信バイト列」ではなく再エンコード結果に対して検証される

**静的確認および正しい CRC を持つ indefinite-length block で再現。**

primary の [checkCRC:96-103](bpv7/src/main/java/io/nodle/dtn/bpv7/PrimaryBlock.kt#L96-L103)、
canonical の [checkCRC:59-66](bpv7/src/main/java/io/nodle/dtn/bpv7/CanonicalBlock.kt#L59-L66) は、
オブジェクトを再シリアライズして得た CRC と受信 CRC を比較している。

RFC 9171 §§4.1, 4.3.1, 4.3.2 は indefinite-length の配列も許すが、
本実装の block serializer は definite-length 配列を生成する。
受信した配列表現や内部データの符号化が変わると、正しい CRC を持つ入力でも拒否される。
未知ブロックでは P0-1 の例外にもなる。

**修正案:** ブロックの受信範囲と CRC value の位置を取得し、
受信ブロックの CRC value の 2/4 バイトだけをゼロに置いたバイト列で CRC を計算する。
CBOR byte string のヘッダと配列の break は変更しない。
CRC の型・長さ・構造を検証し、型固有データの意味的な処理とは切り離す。

[Verified erratum 8043](https://www.rfc-editor.org/errata/eid8043) も CRC value bytes だけをゼロにすることを明確化している。
未知データの serializer を追加するだけでは、この再エンコード依存の問題は残る。

### P1-1: 読み取りがブロック順序を変更し、追加時に番号が衝突する

**再現済み。**

[readBundle:41](bpv7/src/main/java/io/nodle/dtn/bpv7/CborUnmarshal.kt#L41) が
[addBlock:15-24](bpv7/src/main/java/io/nodle/dtn/bpv7/Bundle.kt#L15-L24) を使用する。
この関数は番号の降順ソートを行い、追加番号を `canonicalBlocks.size + 1` とする。
RFC 9171 §4.1 では番号と配置順は無関係であり、番号が連番である保証もない。

- 有効な配置 `[2, 4, 1]` は受信後 `[4, 2, 1]` になる。
- そこへ Previous Node を追加すると `[4, 4, 2, 1]` になる。
- 転送時の Previous Node 追加は dispatch の妥当性検査の後なので、
  その追加による衝突がその場で再検証されない。

順序変更だけで全てのバンドルが不正になるとは限らないが、未知の拡張を不用意に並べ替える理由はない。
また、受信時に重複 payload を黙って落とす動作も同じ `addBlock()` に含まれる。

**修正案:** 受信時は順序・番号・重複をそのまま格納して検証する。
生成時は未使用の正整数を採番し、番号変更で既存ブロックの参照を壊さない。
payload を末尾にする挿入規則と、既存拡張の順序維持を分ける。全体ソートは不要。

### P1-2: 基本構造・標準拡張の制約検証が不足

**下記の受理例を再現済み。**

[BundleValidator.kt:21-79, 110-116](bpv7/src/main/java/io/nodle/dtn/bpv7/BundleValidator.kt#L21-L116) は、
次の不正なオブジェクトを `isValid() == true` とする。

- `version = 6`。
- 異なるブロック番号を持つ payload が 2 個。
- Hop Count の `limit = 0`。
- Bundle Age が 2 個。

同様に Previous Node / Hop Count の複数出現制約、admin bundle の報告要求禁止、
admin/匿名 bundle の canonical block の報告要求禁止などを検証すべきである
（RFC 9171 §§4.1, 4.2.3, 4.2.4, 4.4）。
数値の読み取りも [CborUtil.kt:87-103](bpv7/src/main/java/io/nodle/dtn/utils/CborUtil.kt#L87-L103) の
`isNumeric` だけなので、整数型・非負性・表現範囲を区別して確認する必要がある。

**修正案:** 受信データを構造を消さずに検証し、payload がちょうど 1 個かつ番号 1・末尾、
全番号の一意性・非ゼロ性、標準拡張の個数と値域を検査する。
Hop Limit は 1..255。未知のフラグを「不正」として拒否する検査は加えない。
unsigned 64-bit を signed `Int`/`Long` で狭めているフィールドは、
少なくとも境界を明示し、全域対応には `ULong` または `BigInteger` と対応 codec を検討する。

parser はバンドルの整合性を隠す正規化をせず、BPA で意味的検証を行う。
CRC/構造エラーを `BlockUnintelligible` として扱えるようにする。
現在の HTTP 受信側は [ClaHttpClient.kt:93-102](cla/src/main/java/io/nodle/dtn/cla/http/ClaHttpClient.kt#L93-L102) で
例外を一括して無視するため、§5.6 Step 3 の削除・報告につながる経路も別途必要である。

### P1-3: ipn EID が自身の出力でも読み戻せない

**`ipn:1.2` の primary block round-trip で再現済み。**

[出力:185-192](bpv7/src/main/java/io/nodle/dtn/bpv7/CborMarshal.kt#L185-L192) は標準の `[2, [node, service]]`。
しかし [readEid:123-126](bpv7/src/main/java/io/nodle/dtn/bpv7/CborUnmarshal.kt#L123-L126) は
SSP の内側配列を開かずに `readInt(), readInt()` を呼ぶ。
`Previous Node` や BPSec の Security Source の ipn にも同じ問題が波及する。

**修正案:** SSP の配列を明示的に読み、要素数と unsigned integer を検証する。
まず RFC 9171 の二要素 SSP を直し、`Int` による範囲制限も解消する。
最新仕様を対象とするなら [RFC 9758 §6](https://www.rfc-editor.org/rfc/rfc9758.html#section-6) の
二要素・三要素 SSP と allocator、EID 同値比較、不変 primary の保持まで別途対応する。
単に中継時に EID を新形式へ書き換えるのは不可。

### P1-4: Bundle Age が再試行で二重加算され、保存時の期限計算も逆

**再現済み。**

[BundleProcessor.kt:245-249](agent/src/main/java/io/nodle/dtn/BundleProcessor.kt#L245-L249) は
呼び出す度に `age += now - desc.created` を実行するが、`desc.created` は更新しない。
最初の試行で既に加算した滞留時間を、次の試行でも加算する。
HTTP polling は [ClaHttpPolling.kt:45](cla/src/main/java/io/nodle/dtn/cla/http/ClaHttpPolling.kt#L45) で
この再開経路を使う。

また、creation time が 0 の場合、
[expireAt:95-99](bpv7/src/main/java/io/nodle/dtn/bpv7/BundleValidator.kt#L95-L99) は
`now + age - lifetime` となっており、残り寿命の符号が逆。
`isExpired()` は保存中の実時間経過も加味せず、age の既存値だけを比較する。
Memory GC も [Bpv7MemoryStorage.kt:28-30](agent/src/main/java/io/nodle/dtn/Bpv7MemoryStorage.kt#L28-L30) でこれを使う。

**修正案:** 受信 age と受信時点を基準に `effectiveAge = receivedAge + elapsed` を計算するか、
最後に計上した時点との差分だけを加える。同じ滞留区間を二度計上しない。
保存・再起動を含む基準時点の永続化を設計し、期限は基準時点と `lifetime - receivedAge` から導出する。
更新は RFC §5.4 に従って CLA の実送信直前に行う。
時計の精度・後退も考慮し、実行中の経過時間には monotonic clock が適するが、
その生の値を再起動後にも使えると仮定してはならない。

Hop Count も再試行ごとに増えるため、送信試行と実際の hop の関係を明示してテストすべきである。
この点は age の二重加算とは分けて、RFC §4.4.3 の SHOULD と運用上の試行数制限を踏まえて決める。

### P1-5: 「既知の型だが未知の security context」の透過中継ができない

**静的確認。標準 BPSec ベクトルとの構造比較。**

- [CborSecurityBlock.kt:89-99](bpv7/src/main/java/io/nodle/dtn/bpv7/bpsec/CborSecurityBlock.kt#L89-L99) は
  Ed25519 以外の context を受信時に拒否し、BPA の処理方針決定前に止まる。
- [AbstractSecurityBlockData.kt:12-14](bpv7/src/main/java/io/nodle/dtn/bpv7/bpsec/AbstractSecurityBlockData.kt#L12-L14) は
  context ID 1 を Ed25519 に使用している。
  [RFC 9173 §5.1](https://www.rfc-editor.org/rfc/rfc9173.html#section-5.1) と IANA の ID 1 は **BIB-HMAC-SHA2**、
  ID 2 は **BCB-AES-GCM** であり、現実の識別子衝突がある。
- [ASB 出力:19-20, 47](bpv7/src/main/java/io/nodle/dtn/bpv7/bpsec/CborSecurityBlock.kt#L19-L47) は
  全体を配列で囲む。RFC 9173 Appendix A/B の ASB の CBOR sequence と一致しない。
- BCB が登録済みという理由で既知扱いされるが、標準暗号処理の実装はない。

[RFC 9172 §§5.1, 7.1](https://www.rfc-editor.org/rfc/rfc9172.html#section-5.1) では、
全中継ノードが全 security context を理解する前提ではない。
一方、security acceptor/最終宛先で必要な処理を省略してアプリに渡してよいわけでもない。

**修正案:** 共通の ASB 構造と context 固有処理を分ける。
context 未対応と構文不正を区別し、処理対象ではない中継時は raw の保持を可能にする。
暗号化された「既知型ブロック」のデータも、復号前に平文 parser に渡さない。
security policy・役割に応じた必須処理は fail closed とする。
フラグだけで全 BPSec の処理規則を代替しない。

独自 Ed25519 context の識別子は標準 ID 1 から分離し、既存データの移行方法を決める。
標準 BPSec 対応を名乗るには RFC 9172 §9.1 の必須 context と RFC 9173 ベクトルでの確認が必要。
単なる codec 追加だけでは対応済みにならない。

### P2: 報告生成・再構成・CLA の追加課題

今回の主題以外にも、全面的な BPv7 準拠を目指すなら次を別タスクにする。

- **報告の既定無効化:** RFC 9171 §5.1 は生成をデフォルト無効とする。
  [AdministrativeAgent.kt:21-56](aa/src/main/java/io/nodle/dtn/aa/AdministrativeAgent.kt#L21-L56) に設定ゲートがない。
- **報告時刻:** [CborAdministrativeRecord.kt:69-91](bpv7/src/main/java/io/nodle/dtn/bpv7/administrative/CborAdministrativeRecord.kt#L69-L91) は
  assertion が true なら常に timestamp を出すが、§6.1.1 では元バンドルの Status Time 要求にも依存する。
  parser の [158-166](bpv7/src/main/java/io/nodle/dtn/bpv7/administrative/CborAdministrativeRecord.kt#L158-L166) も、
  時刻なしの `[true]` を扱えない。
- **fragment の報告・識別:** [statusReport:30-38](bpv7/src/main/java/io/nodle/dtn/bpv7/administrative/StatusReport.kt#L30-L38) は
  総 ADU 長を保存するが、§6.1.1 の報告に必要なのは当該 fragment の payload 長。
  [PrimaryBlock.ID:110-118](bpv7/src/main/java/io/nodle/dtn/bpv7/PrimaryBlock.kt#L110-L118) も
  fragment payload 長でなく総 ADU 長を使うため、同じ offset で長さの違う断片を区別できない。
- **overlap の再構成:** [mergeNextFragment:103-114](bpv7/src/main/java/io/nodle/dtn/bpv7/Fragmentation.kt#L103-L114) は
  offset 完全一致の連結のみ。§5.9 が扱う重複範囲の material extents に未対応。
  [doneProcessing:46-58](agent/src/main/java/io/nodle/dtn/BundleProtocolAgent.kt#L46-L58) は
  ReassemblyPending 単独の断片を保存継続の条件に含めず削除するため、ノード経由の再構成も要確認・修正。
- **TCPCL:** 今回確認した CLA は HTTP。
  RFC 9171 §5.4 のインターネット経由転送に適用される TCP CLA の MUST を満たす実装は見つからない。
  HTTP を独自 CLA として利用できることとは別問題である。

## 5. 推奨する修正単位

大規模な汎用プラグイン基盤への置換ではなく、次の順番を推奨する。

| 単位 | 内容 | 完了条件 |
| --- | --- | --- |
| 1. 通常の未知拡張の中継 | raw fallback 出力、フラグ修正、受信バイト列での CRC、順序維持と採番 | 未知 type 200 の受信 → BPA → byte 列送信、および DB 往復で破損しない |
| 2. BPv7 基本品質 | 構造制約、ipn、age/期限管理、報告生成 | 外部入力・境界値・再試行で RFC の期待結果になる |
| 3. BPSec 相互運用 | ASB sequence、context ID 分離、標準 context、policy と raw 保持 | RFC 9173 ベクトル、未知 context の中継、最終宛先の fail closed を確認 |
| 4. 運用プロファイル | RFC 9758/9713、fragment、TCPCL、独立実装との相互運用 | 対応/非対応の仕様範囲を README とテストで明示 |

raw fallback だけなら短い変更だが、上記 P0 の全体と順序・採番を同時に直さないと、
「CRC なしで一度だけ動く」修正にとどまる。

より堅牢なモデルとしては、受信 raw data を保持して、既知ブロックの型付き解釈を別に持ち、
明示的に更新したデータだけを再エンコードする案がある。
これは BPSec・primary 不変性にも適するが、public な mutable データとの同期規則が必要になる。
raw と型付きデータを無条件に二重管理して、更新後も古い raw を送る設計は避ける。
第 1 単位では既存 `BlobBlockData` を再利用し、第 3 単位までにこの更新境界を固めるのが現実的である。

## 6. 実行確認と再現用入力

### 既存テスト

実行:

```text
JAVA_HOME=<JDK 17> ./gradlew :bpv7:test :agent:test --no-daemon --console=plain
```

結果: **成功。bpv7 17 件、agent 18 件、計 35 件。failure/error/skipped は 0。**
既定の JDK 25 では Gradle 7.4 が `Unsupported class file major version 69` で起動失敗したため、
インストール済み Microsoft OpenJDK 17.0.18 に切り替えて検証した。build 設定は変更していない。

既存 [BundleEncodeTest](bpv7/src/test/java/io/nodle/dtn/bpv7/BundleEncodeTest.kt#L22-L108) は主に自分の serializer/parser の往復。
[TestBundleProcessor](agent/src/test/java/io/nodle/dtn/TestBundleProcessor.kt#L62-L110) の転送確認は
オブジェクトを収集する mock CLA なので、実際の wire encoding 失敗を検出しない。
従って既存テスト成功だけで RFC 相互運用を保証できない。

### 追加の実行プローブ

製品コードを変更せず、ビルド済み class と宣言バージョンの依存 jar を JDK 17 JShell から呼び出した。
以下は canonical block 単体の hex。CRC は別実装の反射型 CRC 計算
（X-25: polynomial `0x8408`、CRC32C: `0x82f63b78`、init/xorout は全 bit 1）で生成した。

| 入力 | 内容 | 観測結果 |
| --- | --- | --- |
| `8518c802000043010203` | type 200, number 2, flags 0, CRC なし, data `010203` | decode 成功、encode が `block type is unknown` |
| `8618c80200014301020342da1e` | 同上、正しい CRC16 | decode 中に `block type is unknown` |
| `8618c8020002430102034400c8b502` | 同上、正しい CRC32C | decode 中に `block type is unknown` |
| `9f010100014301020342be5cff` | indefinite-length payload block、正しい CRC16 | `wrong crc` |

最小の再現コード例（既存の Kotlin API を使用）:

```kotlin
val raw = "8518c802000043010203".hexToBa()
val block = cborUnmarshalCanonicalBlock(raw) // 成功
block.cborMarshal() // CborEncodingException: block type is unknown
```

フラグ動作は未知ブロックを付けた `Bundle` を BPA に渡し、
オブジェクトだけを受け取って成功を返す CLA で受信分岐を分離して確認した。
これはネットワーク中継成功のテストではない。

| flags | CLA 呼び出し回数 | 未知ブロック保持 | 意味 |
| --- | --- | --- | --- |
| `0x00` | 1 | yes | オブジェクト上は転送。しかし wire 化は別プローブの通り失敗 |
| `0x02` | 1 | no | RFC の報告指定がブロック除去として動作 |
| `0x04` | 2 | yes | RFC の削除指定が報告＋転送として動作 |
| `0x08` | 0 | yes | 予約 bit でバンドル削除 |
| `0x10` | 1 | yes | RFC の除去指定が無視される |

その他の実行確認:

- `ipn:1.2` の primary block encode → decode が `expected end array` で失敗。
- version 6、payload 2 個、Hop Limit 0、Bundle Age 2 個が `isValid() == true`。
- 配置 `[2,4,1]` が decode 後 `[4,2,1]`。次の拡張追加で `[4,4,2,1]`。
- 受信 age 100、受信約 1 秒後の失敗試行 → 即再試行で、
  age は一例で `1181 → 2367`。実際の追加経過時間ではなく受信以来の時間を再加算する。
- 同じバンドルの `expireAt() - now` は `-3597633` ms。
  lifetime 1 時間、age 約 2.4 秒でも期限が約 1 時間「過去」と算出される。

### 修正時に恒久化すべき回帰テスト

1. 未知 type 200、flags の `0x00/02/04/08/10` と複合値、未知上位 bit を、**RFC の数値そのもの**で検証。
2. CRC 0/16/32、正しい/壊れた CRC、definite/indefinite 配列。
3. `wire bytes → parse → BPA → serialize → parse` で未知の data・番号・flags・配置が維持されること。
4. SQL/Room の保存・読出し、再起動後の再送、断片化の raw コピー。
5. 飛び番号の追加、重複 payload/番号、標準拡張の複数出現、limit/数値の境界値。
6. 正しい ipn と不正 SSP、既知型＋未知 context、暗号化された既知型データ。
7. 時計注入による age の再試行・保管・期限切れ、報告の既定無効と時刻フラグ。
8. RFC 9173 の公開ベクトルと独立した BPv7 実装との通信。

今回未実施: 実ネットワーク上の多段中継、SQL/Room の実 DB 往復、Android 実行、
TCPCL、外部実装との相互運用、全 RFC 要求の網羅テスト。
これらを成功したとは主張しない。

## 7. 仕様資料・更新の扱い

以下は調査時に原文を取得した一次資料。

- [RFC 9171](https://www.rfc-editor.org/rfc/rfc9171.txt): BPv7 の形式・拡張・処理規則。
- [RFC 9171 errata](https://www.rfc-editor.org/errata/rfc9171):
  Verified の 8043（CRC）、8525（報告内 source EID）、7337（CDDL）を区別して確認。
  Reported/Held の提案を確定済みの MUST として扱っていない。
- [RFC 9172](https://www.rfc-editor.org/rfc/rfc9172.txt): BPSec、特に §§5.1, 7.1, 9.1。
- [RFC 9173](https://www.rfc-editor.org/rfc/rfc9173.txt): 標準 context ID、ASB sequence、公開ベクトル。
- [IANA Bundle Protocol registry](https://www.iana.org/assignments/bundle/bundle.xhtml):
  型・フラグ・context ID の割当。enum に BPv6 専用型があることは BPv7 対応の証拠ではない。
- [RFC 9713](https://www.rfc-editor.org/rfc/rfc9713.txt):
  管理レコード型レジストリの更新と、処理できない管理レコードの扱い。
- [RFC 9758](https://www.rfc-editor.org/rfc/rfc9758.txt):
  ipn URI と二/三要素 SSP の更新。元の RFC 9171 の不具合と最新仕様追加を分けて計画する。

本書のコード参照は対象コミット時点のパス・行番号。
外部の固定版ソースを確認する場合は、例えば
[CborMarshal.kt at 7f188ad](https://github.com/o-o-o-dev/dtn7-kotlin/blob/7f188ad398940bc1a5b0392f4e4eafa96fe00ff3/bpv7/src/main/java/io/nodle/dtn/bpv7/CborMarshal.kt#L148-L158)
のようにコミットを固定して参照する。
