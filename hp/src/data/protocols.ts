export type GroupId = 'core' | 'ext' | 'mesh' | 'pds' | 'attest' | 'public' | 'dm';

export interface Group {
  id: GroupId;
  no: string;
  title: string;
  en: string;
  lead: string;
}

export interface Protocol {
  /** URL とディレクトリ。コアは '' */
  slug: string;
  nsid: string;
  title: string;
  en: string;
  group: GroupId;
  /** 仕様の性質 */
  kind: '基本' | '投稿の拡張' | '配送' | 'サービス' | '鍵署名' | '形式' | '通信';
  /** 一文の要約。一覧に表示する */
  tagline: string;
  /** 概要。2〜3 文 */
  summary: string;
  /** 斜め読み用の要点 */
  points: string[];
  /** 前提とするプロトコルの slug */
  deps: string[];
  /** 定義する主な $type */
  types?: string[];
  /** 本文に図がない仕様の概要図（mermaid） */
  figure?: { source: string; caption: string };
}

export const groups: Group[] = [
  { id: 'core', no: '01', title: 'コア', en: 'CORE', lead: '投稿の単位、基本型、拡張の運び方、atproto と Nostr への対応。全ての拡張がこれを前提とする。' },
  { id: 'ext', no: '02', title: '投稿の拡張', en: 'EXTENSIONS', lead: '投稿に著者が署名して添える構造化情報。位置、期間、注釈、優先度。' },
  { id: 'mesh', no: '03', title: 'メッシュ配送', en: 'MESH', lead: '圏外の端末同士が BLE・LAN で接触したときに、署名済みイベントを保存・中継する手順。' },
  { id: 'pds', no: '04', title: 'PDS とリレー', en: 'PDS / RELAY', lead: '端末が署名したリポジトリを収集・復元し、atproto ネットワークへ公開するサーバー側の構成。' },
  { id: 'attest', no: '05', title: '発信者証明', en: 'ATTESTATION', lead: '鍵が鍵に署名する連なりで、自治体等の名称と公開鍵の対応をオフラインで確認する。失効と発行も扱う。' },
  { id: 'public', no: '06', title: '公的情報', en: 'PUBLIC INFORMATION', lead: 'CAP・EDXL・気象庁 XML・BSAF などの公的情報を共通投稿へ変換し、信頼できる経路でメッシュへ持ち込む。' },
  { id: 'dm', no: '07', title: '個別通信', en: 'DIRECT MESSAGE', lead: '鍵を持つ二者の間で暗号化したメッセージをメッシュとリレーで運ぶ。' },
];

export const protocols: Protocol[] = [
  {
    slug: '',
    nsid: 'dev.jisaku.mosimo',
    title: 'もしもプロトコル',
    en: 'Core',
    group: 'core',
    kind: '基本',
    tagline: '参加者、投稿単位、基本型、拡張の共通構造、atproto レコードと Nostr イベントへの対応',
    summary:
      '災害等でインターネットが途絶した状況でも、異なるアプリの間で署名済みの公開テキスト投稿を保存・中継し、接続回復時にインターネット上の情報網へ接続するための基本プロトコルである。端末が署名した atproto リポジトリを正本とし、同じ投稿を単一の Nostr イベントとして表現してメッシュとリレーで運ぶ。',
    points: [
      '1 投稿 ＝ 1 レコード追加 ＝ 1 署名済み commit ＝ 1 Nostr イベント。本文・レコード・commit は同じイベントに収まり、単体で署名を検証できる。',
      '発信者はアプリのインストールごとに独立した鍵・DID（did:plc）・リポジトリを持つ。Nostr 鍵、atproto 署名鍵、PLC 管理鍵は別々に生成する。',
      '拡張は $type 付きのオブジェクトの配列 ext であり、Nostr では「mosimo:ext:〈$type〉」の自己完結タグとして運ぶ。未知の拡張は意味を解釈せずに元の表現のまま保持・中継する。',
      '検証は「Nostr 署名の検証」「DID の認証」「署名済み状態への包含」の三段階に分かれ、通常の受信・中継・表示は第一段階だけで成立する。',
      '配送期限 exp は著者が署名する配送の上限であり、情報の有効期間や削除とは別である。削除は著者端末による制御処理（NIP-09 と削除後の commit）で表す。',
    ],
    deps: [],
    types: ['dev.jisaku.mosimo.post'],
  },
  {
    slug: 'geo',
    nsid: 'dev.jisaku.mosimo.geo',
    title: '地理情報',
    en: 'Geo',
    group: 'ext',
    kind: '投稿の拡張',
    tagline: '発信地点 #origin と影響範囲 #area。円と Geohash による端末だけで判定できる領域表現',
    summary:
      '投稿に付随する二つの地理情報、発信端末の位置を任意に公開する発信地点と、投稿内容が関係する影響範囲を定義する。行政境界データやサーバー照会なしに、端末だけで領域の復元と地点の包含判定ができる表現を選ぶ。',
    points: [
      '発信地点と影響範囲は独立した任意の拡張である。発信地点を影響範囲として代用してはならない。',
      '座標は 100 万分の 1 度、距離はミリメートルを単位とする整数で表す。',
      '影響範囲は円と Geohash セルの和集合であり、多角形や行政区域コードは全体を覆うセルへ近似して approximation: true を明示する。',
      '包含判定は「範囲内」「範囲外」「判定不明」を区別し、解釈できない要素があるときに範囲外と断定しない。',
    ],
    deps: [''],
    types: ['dev.jisaku.mosimo.geo#origin', 'dev.jisaku.mosimo.geo#area'],
  },
  {
    slug: 'validity',
    nsid: 'dev.jisaku.mosimo.validity',
    title: '情報の有効期間',
    en: 'Validity',
    group: 'ext',
    kind: '投稿の拡張',
    tagline: '内容がいつの状況・指示として有効かを表す期間。配送期限とは別の概念',
    summary:
      '避難指示の適用期間や警報の対象期間のように、原情報が明記する期間を from・until の端点として保持する。有効期間の終了は投稿の削除・撤回を意味せず、省略された端点は不明を表す。',
    points: [
      '作成時刻 created_at、有効期間 from〜until、配送期限 exp は所有者も意味も異なる三つの時間である。',
      '期間は from <= t < until。拡張を置くなら少なくとも一方の端点を持つ。',
      '不明な端点を配送期限・受信時刻・現在時刻から補ってはならない。',
      '原情報の発生時刻と有効開始時刻は別の概念であり、意味が一致する場合だけ対応付ける。',
    ],
    deps: [''],
    types: ['dev.jisaku.mosimo.validity'],
    figure: {
      caption: '三つの時間。有効期間と配送期限は互いに独立しており、有効期間の終了は削除を意味しない',
      source: `flowchart LR
  C["作成時刻 created_at<br/>Nostr イベント"]
  F["有効開始 from<br/>この時刻を含む"]
  U["有効終了 until<br/>この時刻を含まない"]
  E["配送期限 exp<br/>投稿の基本項目"]
  C --> F
  F -->|有効期間| U
  C -.->|独立に設定| E`,
    },
  },
  {
    slug: 'annotation',
    nsid: 'dev.jisaku.mosimo.annotation',
    title: '注釈',
    en: 'Annotation',
    group: 'ext',
    kind: '投稿の拡張',
    tagline: '既存の投稿を参照する訂正・補足・追認と、atproto 標準 Label との関係',
    summary:
      '既存の投稿を参照して内容を訂す訂正、関連情報を示す補足、賛同・確認を表明する追認を、新しい投稿の拡張として定義する。注釈は対象投稿の著者以外も発信でき、元投稿の署名済み表現を変えない。',
    points: [
      '注釈はコアの投稿単位に従う新しい投稿であり、対象投稿のリポジトリには書き込まない。',
      '対象は atproto の at:// URI（任意で CID）、または Nostr の event ID で参照する。',
      '元著者の訂正と他者の訂正の主張は著者で区別する。追認の件数は内容の真実性を保証しない。',
      'Labeler は注釈を集約して標準 Label として提示でき、Label の src はサービス DID、元の注釈発信者への信頼判断は署名チェーンで行う。',
    ],
    deps: ['', 'attest/chain'],
    types: ['dev.jisaku.mosimo.annotation'],
  },
  {
    slug: 'priority',
    nsid: 'dev.jisaku.mosimo.priority',
    title: '配送優先度ヒント',
    en: 'Priority',
    group: 'ext',
    kind: '投稿の拡張',
    tagline: '著者の優先度ヒント level と、受信ノードが決める実効優先度',
    summary:
      '著者が投稿に付ける配送優先度のヒントと、受信ノードがそれを用いて実効優先度を決める規則を定義する。認可されていない発信者のヒントによって優先度が引き上げられることはない。',
    points: [
      'level は 0 bulk、1 normal（既定）、2 important、3 urgent の四段階である。',
      '実効優先度は、発信者が署名チェーンまたは転載サーバーの認可を通っていれば hint、そうでなければ min(hint, 1) とする。',
      '受信者は実効優先度をさらに下げてよいが、組織名や自己署名だけで引き上げてはならない。',
      '優先度は資源上限内での順序の決定であり、上限の解除ではない。',
    ],
    deps: ['', 'attest/chain', 'attest/importer', 'pds'],
    types: ['dev.jisaku.mosimo.priority'],
  },
  {
    slug: 'mesh',
    nsid: 'dev.jisaku.mosimo.mesh',
    title: 'BPv7 メッシュ配送',
    en: 'Mesh',
    group: 'mesh',
    kind: '配送',
    tagline: 'メッシュ用 CBOR、BPv7 Bundle、BLE GATT・LAN・Nearby での接触時交換、Negentropy 差分照合',
    summary:
      'コアの署名済み Nostr イベントを、断続的な端末間リンク上で保存・中継して届ける配送プロファイルである。イベントをメッシュ用 CBOR で可逆に表現して BPv7 の Bundle に収め、BLE GATT を全参加機器に共通の基本経路として、接触した端末間で Bundle を交換する。',
    points: [
      'payload は 7 要素の CBOR 配列（版・公開鍵・作成時刻・kind・タグ・本文・署名）であり、元の Nostr イベントへ可逆に戻せる。固定タグの省略、Base64・CID・TID・時刻の型変換、鍵署名の参照化で縮める。',
      '接触は HELLO（能力交換）→ RECONCILE（Negentropy による差分照合）→ WANT/INVENTORY → BEGIN/DATA/CREDIT → RESULT の順に進む。全経路で同じ CBOR メッセージを使う。',
      'RESULT の保存受理は「直近の相手が検査して保存した」ことだけを示す。最終到達や全ノードへの配送は保証しない。',
      '送信順は実効優先度・鮮度・地域・サイズ・発信者の分散で決め、配送期限・hop・保存量の上限はヒントとは独立に適用する。',
      'ゲートウェイは元の Nostr イベントをそのままリレーへ搬出する。インターネットからメッシュへの投入は公的情報の認可を経た投稿に限る。',
    ],
    deps: ['', 'attest/chain', 'priority', 'geo', 'public', 'pds'],
  },
  {
    slug: 'mesh/adv',
    nsid: 'dev.jisaku.mosimo.mesh.adv',
    title: '広告要約',
    en: 'Advertisement summary',
    group: 'mesh',
    kind: '配送',
    tagline: 'BLE 広告の残り 4 バイトに配送候補集合の要約を載せ、接続の要否を接続前に判断する',
    summary:
      'BLE scan response の残り領域に配送候補集合の要約（イベント ID 列の SHA-256 先頭 3 バイト）と状態 flags を載せる。scan 側は前回接触時の要約と比べ、双方に変化がなければ接続を省く。',
    points: [
      'Service Data の 25〜27 バイトが要約、28 バイトが拡張 flags。拡張領域のない 25 バイトの広告はコアどおりに扱う。',
      '要約の一致は推定であり、所持の証明ではない。2^24 分の 1 の衝突は許容する誤りである。',
      'flags は自己申告であり、HELLO による能力交換を置き換えない。',
      '拡張広告が使える場合は SHA-256 全体と最高優先度の候補 ID を追加してよいが、legacy 広告は常に併用する。',
    ],
    deps: ['mesh'],
  },
  {
    slug: 'mesh/l2cap',
    nsid: 'dev.jisaku.mosimo.mesh.l2cap',
    title: 'L2CAP CoC 経路',
    en: 'L2CAP CoC',
    group: 'mesh',
    kind: '配送',
    tagline: '同じ BLE 接続上で GATT の代わりに L2CAP CoC を開き、全メッセージを高速経路へ移す',
    summary:
      'BLE 接続上で L2CAP Connection-oriented Channel を開き、共通交換メッセージの全てをそのチャネルへ移す経路を定める。GATT の書込み単位への分割と Mosimo の CREDIT を省け、大きな Bundle の転送時間を短縮できる。',
    points: [
      'HELLO の拡張に psm と mtu を載せ、双方に拡張があれば central が CoC を開く。peripheral だけが listen する。',
      'CoC が開いた後もセッションは継続し、HELLO は再送しない。開かなければ GATT で続ける。',
      '流量制御は L2CAP の credit に委ね、受け入れ可否は RESULT だけで示す。',
      'GATT 経路は後方互換の基本経路として常に残す。',
    ],
    deps: ['mesh'],
  },
  {
    slug: 'mesh/zstd',
    nsid: 'dev.jisaku.mosimo.mesh.zstd',
    title: '本文圧縮',
    en: 'Zstd compression',
    group: 'mesh',
    kind: '配送',
    tagline: '双方が対応する接触で payload 全体を zstd 圧縮して運ぶ。辞書は任意',
    summary:
      'Bundle の payload 全体を zstd（RFC 8878）で圧縮して運ぶ手段を定める。圧縮は運び方であり、受信側は復元後に通常の payload として検査する。発信者の署名対象は変わらない。',
    points: [
      '圧縮 payload は [1, "zstd", frame] の 3 要素配列。位置 1 が文字列であることで通常の payload と区別する。',
      '展開前に Content_Size が 32,768 バイト以下であることを確認し、超えるものは展開せずに拒否する。',
      '辞書は SHA-256 で内容アドレスされ、FETCH で取り寄せられる。ID の先頭 4 バイトしか知らない辞書は使わない。',
      '保存・中継・Nostr への搬出は展開後の payload で行う。',
    ],
    deps: ['mesh'],
  },
  {
    slug: 'mesh/time',
    nsid: 'dev.jisaku.mosimo.mesh.time',
    title: '時刻ヒント',
    en: 'Time hint',
    group: 'mesh',
    kind: '配送',
    tagline: 'HELLO に相手の現在時刻と自己申告の信頼度を載せ、自分の時計の大きなずれを検出する',
    summary:
      'HELLO の拡張配列に送信側の現在時刻と信頼度（0〜3）を載せ、受信側が自分の時計との差を記録できるようにする。時刻は自己申告であり、時刻の同期や正しい時刻の提供を保証しない。',
    points: [
      '差 delta = now − local を node ID・confidence とともに記録し、閾値を超え続ける場合に利用者へ時計の確認を促す。',
      '鍵署名の有効期間、配送期限、created_at の判定の時計として採用してはならない。',
      '時計が不明な端末はコアの規則どおり、期限内と確定できないものとしてローカル上限内で中継する。',
    ],
    deps: ['mesh', 'attest'],
  },
  {
    slug: 'pds',
    nsid: 'dev.jisaku.mosimo.pds',
    title: '論理 PDS と物理 PDS',
    en: 'PDS',
    group: 'pds',
    kind: 'サービス',
    tagline: '論理 PDS グループと物理 PDS、MST の再構築、Nostr リレープロファイル、PLC 代理登録、MOSIMO-REQ',
    summary:
      '端末が署名した atproto リポジトリを既存の atproto ネットワークへ公開するためのサーバー側の構成を定める。複数の物理 PDS が署名済みリポジトリを収集・復元・複製したうえで、DID ごとに一つの論理 PDS として公開する。物理 PDS は Nostr リレーも提供する。',
    points: [
      '論理 PDS グループは DID が正規公開先として指定する運用単位で、公開 HTTPS endpoint を一つ持つ。物理 PDS は複数のグループに所属できる。',
      '物理 PDS はレコード・path・適用元 CID から MST を再構築し、root を署名済み commit の data と照合する。欠落があれば保留し、推測した木を公開しない。',
      'PDS は著者の commit を再署名せず、NIP-09 削除要求だけで削除後の commit を生成しない。',
      'Nostr リレーは独自タグや古い作成時刻を理由に本プロトコルのイベントを拒否せず、PLC 初期操作の制御イベント（kind 8173）を代理登録する。',
      'MOSIMO-REQ は実効優先度順の取得を提供する任意機能であり、標準 REQ の結果は変えない。',
    ],
    deps: ['', 'priority', 'attest/chain', 'attest/revocation', 'attest/importer', 'mesh'],
  },
  {
    slug: 'attest',
    nsid: 'dev.jisaku.mosimo.attest',
    title: '鍵署名',
    en: 'Key attestation',
    group: 'attest',
    kind: '鍵署名',
    tagline: '鍵が鍵の所有者と役割に署名する KeyAttestation、役割、stratum、信頼度、チェーンの検証',
    summary:
      'ある鍵が別の鍵の所有者と役割に署名する「鍵署名」を定義し、受信者が事前に信頼したルート鍵から末端の鍵へ至る署名チェーンをオフラインで検証できるようにする。証明の対象は発行者が所有者・役割を確認した事実であり、投稿内容の真実性ではない。',
    points: [
      '共通符号化は atproto deterministic CBOR。32 バイト鍵は BIP-340 Schnorr、33 バイト鍵は低 S の compact ECDSA で、Nostr 鍵と atproto 鍵を変換なしに扱う。',
      '役割は attest・identity・public-information・import。子の役割は親の部分集合でなければならない。',
      'stratum はルート鍵からの距離。stratum 1 の認証局は無期限でよく、stratum 2 以降は keyValidity.until を必須とする。',
      'チェーン検証はルートの一致、各署名、issuer と subject の連なり、役割とブロックの包含、有効期間の順に進み、verified・expired・invalid・unverifiable・unknown-time を区別する。',
      '信頼度 trustLevel は確認過程の種類を表す序数であり、加算・平均してはならない。',
    ],
    deps: [],
    types: ['dev.jisaku.mosimo.attest#keyAttestation'],
  },
  {
    slug: 'attest/chain',
    nsid: 'dev.jisaku.mosimo.attest.chain',
    title: '署名チェーンの同梱',
    en: 'Attestation chain',
    group: 'attest',
    kind: '投稿の拡張',
    tagline: '投稿自身の拡張としてルートから末端までの鍵署名を同梱し、オフラインで発信者を確認する',
    summary:
      '投稿の著者鍵が、受信者の信頼するルート鍵からどの鍵署名を経て証明されているかを、投稿自身の拡張として同梱する。受信者は投稿とチェーンだけで、インターネットに接続せずに発信者を確認できる。',
    points: [
      '末端の subject が 32 バイト鍵なら投稿の pubkey と一致させ、33 バイト鍵なら binding（鍵対応宣言）で Nostr 鍵へ接続する。',
      '検証は投稿の created_at で行い、受信者が信頼できる時計を持つなら現在時刻でも別に評価する。両者を同じ状態として表示しない。',
      'identity は組織名・所有者名の表示、public-information は同じ orgId の旧版の拡散停止の認可、import は転載サーバーの認可を与える。',
      '本拡張のない投稿や検証に失敗した投稿は「発信者未確認」として本文の表示・中継をそのまま行う。',
    ],
    deps: ['attest', '', 'attest/revocation'],
    types: ['dev.jisaku.mosimo.attest.chain'],
  },
  {
    slug: 'attest/revocation',
    nsid: 'dev.jisaku.mosimo.attest.revocation',
    title: '失効通知',
    en: 'Revocation',
    group: 'attest',
    kind: '鍵署名',
    tagline: '鍵をある時刻から無効とする署名付きの永続的な事実。即時形と事前発行形',
    summary:
      '「鍵 S を時刻 T から無効とする」署名付きの事実を定義し、受信者がインターネットなしで手元に届いた通知だけで失効を適用できるようにする。親鍵が発行する即時形と、発行時に所有者へ渡され所有者が活性化する事前発行形がある。',
    points: [
      '受信者は「失効していない」と「通知を受け取っていない」を区別できない。被害の上限は鍵の keyValidity.until である。',
      '通知は notice.issuer が評価中のチェーンで subject の祖先、または subject 自身である場合にだけ適用する。',
      '事前発行形は所有者の activation 署名で成立し、流出した notice 単体では鍵は無効にならない。',
      'Revocation は kind 8173 の制御イベントとして運搬者の鍵で運び、受信者は検証済みの通知を永続保持して巻き戻さない。',
      '状態は revoked・revoked-later・not-revoked-as-known・unknown。seenAt を条件に含めて created_at の偽装を防ぐ。',
    ],
    deps: ['attest', ''],
    types: ['dev.jisaku.mosimo.attest.revocation'],
  },
  {
    slug: 'attest/revocation/list',
    nsid: 'dev.jisaku.mosimo.attest.revocation.list',
    title: '失効リスト配信',
    en: 'Revocation list',
    group: 'attest',
    kind: '鍵署名',
    tagline: '認証局が失効通知を自身のドメインで公開・購読・再掲し、接続できた参加者が確認する',
    summary:
      '認証局が失効通知を自身のドメインの well-known エンドポイントで公開・購読・再掲する方法と、クライアント・Mosimo サーバーがインターネットに接続できたときに行う確認を定義する。オフラインの受信者に要件を課さない。',
    points: [
      'GET https://D/.well-known/mosimo/attest/revocations が publisher 鍵で署名した RevocationList を返す。since と ETag に対応する。',
      '自身が発行した通知、活性化された事前発行形、親鍵や祖先による失効を掲載する。子孫ごとの個別通知は発行しない。',
      '接続できた参加者はチェーン中の issuerDomain を収集してリストを取得し、保持する投稿を再評価する。',
      '取得の失敗は最後の状態を保ち、表示・中継を止めない。',
    ],
    deps: ['attest/revocation', 'attest'],
    types: ['dev.jisaku.mosimo.attest.revocation.list'],
  },
  {
    slug: 'attest/revocation/request',
    nsid: 'dev.jisaku.mosimo.attest.revocation.request',
    title: '失効要求',
    en: 'Revocation request',
    group: 'attest',
    kind: '鍵署名',
    tagline: '認証局へ失効を求める入力。対象鍵自身の署名なら即時失効、それ以外は通報',
    summary:
      '認証局に対して特定の鍵の失効を求める入力を定義する。対象鍵自身が署名した要求は即時の失効へつながり、他者の署名または無署名の要求は通報として認証局の判断に委ねる。',
    points: [
      'POST https://D/.well-known/mosimo/attest/revocation-requests。応答は revoked・received・rejected・not-ancestor。',
      '証拠は evidence（対象鍵のチェーンを同梱したメッセージの ID）、subjectChain、reporterChain のブロックで示す。',
      'エンドポイントに届かない状況では、個別通信の構造化オブジェクトとして認証局の鍵へ送れる。',
      '通報者の鍵・署名は公開せず、失効通知にも含めない。',
    ],
    deps: ['attest/revocation/list', 'attest/revocation'],
    types: ['dev.jisaku.mosimo.attest.revocation.request'],
  },
  {
    slug: 'attest/revocation/subkey',
    nsid: 'dev.jisaku.mosimo.attest.revocation.subkey',
    title: '失効用サブ鍵',
    en: 'Revocation subkey',
    group: 'attest',
    kind: '鍵署名',
    tagline: '主鍵を失ったときに、別保管の副鍵で事前発行形の失効通知を活性化する',
    summary:
      '所有者が通常の署名鍵とは別に失効専用の副鍵を持ち、その公開鍵を鍵署名のブロックとして親に署名してもらう。主鍵を失ったときは、事前発行形の通知に失効日を付けて副鍵で署名し、配送する。',
    points: [
      'ブロックは key（副鍵の公開鍵）だけを持ち、一つの鍵署名に一つ。親子の包含規則はない。',
      '副鍵は主鍵・他用途と共用せず、主鍵の端末の外（紙・別端末・別担当者）に保管する。',
      '受信者は notice.issuer が祖先であること、activation が subject 鍵またはチェーン中の副鍵で検証できることを確認する。',
      'stratum 1 の無期限の鍵にも鍵の喪失に備える手段として推奨する。',
    ],
    deps: ['attest/revocation', 'attest'],
    types: ['dev.jisaku.mosimo.attest.revocation.subkey'],
  },
  {
    slug: 'attest/signing',
    nsid: 'dev.jisaku.mosimo.attest.signing',
    title: '署名要求と自動発行',
    en: 'Signing request',
    group: 'attest',
    kind: '鍵署名',
    tagline: '認証局への署名要求エンドポイント、自動発行プロファイル（NIP-05）、手動審査',
    summary:
      '鍵の所有者が認証局に鍵署名を要求する手順と、認証局が要求を自動または手動で審査して鍵署名と事前発行形の失効通知を返すエンドポイントを定義する。自動発行は検証できる外部の事実に限る。',
    points: [
      'SigningRequest は subject 鍵で署名し、宛先の issuer を含めて他の認証局への転用を防ぐ。応答は issued・pending・rejected。',
      '認証局は要求内容を縮小して発行してよいが、拡大してはならない。',
      'NIP-05 プロファイルは nostr.json の照合で identity 役割の鍵署名を trustLevel 1 で自動発行する。',
      'trustLevel 2 以上を要する発行は手動審査とし、確認資料と発行 ID を記録する。',
    ],
    deps: ['attest', 'attest/revocation'],
    types: ['dev.jisaku.mosimo.attest.signing'],
  },
  {
    slug: 'attest/external',
    nsid: 'dev.jisaku.mosimo.attest.external',
    title: '外部アカウント証明',
    en: 'External accounts',
    group: 'attest',
    kind: '鍵署名',
    tagline: 'NIP-39 の外部アカウント所持を認証局が検証し、鍵署名のブロックとして発行する',
    summary:
      'GitHub 等の外部アカウントの所持を主張する NIP-39 の検証を認証局が代行し、結果を鍵署名のブロックとして発行する。受信者は外部サービスへ接続せずに、検証時点でのアカウント所持を確認できる。',
    points: [
      'accounts は (platform, identity) で重複のない 1〜8 件。検証できたアカウントだけを含める。',
      '発行は identity 役割、organization なし、trustLevel 1。組織の公式発信として表示してはならない。',
      'アカウントの乗っ取りや改名は検証後に起こり得るため、認証局は期限を短くし更新時に再検証する。',
    ],
    deps: ['attest', 'attest/signing'],
    types: ['dev.jisaku.mosimo.attest.external'],
    figure: {
      caption: '外部アカウント証明の発行と検証。受信者は外部サービスへ接続しない',
      source: `sequenceDiagram
  participant K as 鍵の所有者
  participant X as 外部サービス
  participant CA as 認証局
  participant R as 受信者
  K->>X: npub を含む証明を掲載（NIP-39）
  K->>CA: 署名要求（subject, 本ブロック）
  CA->>X: 証明を取得
  CA->>CA: 証明が subject の npub を含むか照合
  CA->>K: 鍵署名（roles identity, level 1, nip39）
  K->>R: 投稿と署名チェーン
  R->>R: 外部サービスへ接続せずに検証`,
    },
  },
  {
    slug: 'attest/importer',
    nsid: 'dev.jisaku.mosimo.attest.importer',
    title: '転載サーバー',
    en: 'Importer',
    group: 'attest',
    kind: '鍵署名',
    tagline: 'import 役割と importer・organization ブロックで「誰がどの情報源を転載してよいか」を表し、受信者が自動投入を認可する',
    summary:
      '他者が発表した情報を取得し、自身の鍵で署名し直して投稿として流す転載サーバーについて、鍵署名で転載を認められた情報源を表し、受信者がそれとローカルの選択に基づいてインターネットからメッシュへの自動投入を認可する規則を定める。転載サーバーの署名は転載の証言であり、原発表者の署名ではない。',
    points: [
      '末端の鍵署名は import 役割と importer ブロック（botDid と types の組）、organization ブロックを持たなければならない。',
      '転載投稿は本文を原レコードと一致させ、public.bsaf の original を持つ。Nostr 専用であり atproto へ再公開しない。',
      '受信者はローカルで選択した (botDid, type) に限り、チェーンが作成時刻と現在時刻の両方で有効で、失効していない場合に投入する。',
      '認可に失敗した投稿を通常投稿として投入し直してはならない。',
    ],
    deps: ['attest/chain', 'attest', 'public/bsaf', 'attest/revocation', 'priority'],
    types: ['dev.jisaku.mosimo.attest.importer'],
  },
  {
    slug: 'public',
    nsid: 'dev.jisaku.mosimo.public',
    title: '公的情報の取り込み',
    en: 'Public information',
    group: 'public',
    kind: '形式',
    tagline: '取り込みサーバーへの信頼、出典拡張 #source、配送同値、更新による旧版の拡散停止',
    summary:
      'CAP・EDXL-DE・気象庁 XML などの公的情報を、人が読める本文と任意の構造化拡張を持つ共通投稿へ変換し、インターネットからメッシュへ持ち込む方式を定義する。信頼する変換者が規定の規則で変換した情報を、誰が持ち運んでも出典と変換経路を追跡でき、検証後に同じ元情報として扱える。',
    points: [
      '取得・変換・配布・原発表の責務を区別し、取り込みサーバーの署名を発表機関の署名として表示しない。',
      '取り込みサーバーは attest.importer の import 役割で認証し、受信者はローカルで選んだ情報源だけを自動投入する。',
      '出典拡張 source は「どこから得た何を、誰がどの規則で変換したか」を記録し、原文全量を同梱しない。',
      '同じ原情報の同じ版を表す検証済みの複数投稿は、受信側の判断で一つだけを再配信してよい。本文一致だけで個人投稿を統合しない。',
      '更新・取消は単独で読める新規投稿として発信し、public-information 役割または認可済み転載により旧版の新たな拡散を止める。',
    ],
    deps: ['', 'geo', 'validity', 'annotation', 'attest/chain', 'attest/importer', 'mesh'],
    types: ['dev.jisaku.mosimo.public#source'],
  },
  {
    slug: 'public/cap',
    nsid: 'dev.jisaku.mosimo.public.cap',
    title: 'CAP 1.2',
    en: 'CAP',
    group: 'public',
    kind: '形式',
    tagline: 'OASIS CAP 1.2 の原電文・情報単位・語彙を保持する拡張と、info 単位の投稿化',
    summary:
      'OASIS CAP 1.2 の原情報の意味を保持する拡張と、人が読める基本投稿・共通拡張への対応を定義する。各 info の意味を解釈した情報単位ごとに投稿を生成し、更新・取消は原電文単位で置換する。',
    points: [
      '原電文の識別、選択した info、更新取消参照、状態・更新種別・緊急性・強度・確実性などの語彙を原表記のまま保持する。',
      '異なる言語・対象・期間を一つの本文・領域・期間へ混ぜない。',
      'onset・expires は意味が一致する場合に限り validity へ、area は全体を覆う Geohash 近似として #area へ投影する。',
      'CAP XML の完全復元や全プロファイルへの対応は保証しない。',
    ],
    deps: ['', 'public', 'geo', 'validity'],
  },
  {
    slug: 'public/edxl',
    nsid: 'dev.jisaku.mosimo.public.edxl',
    title: 'EDXL-DE 1.0',
    en: 'EDXL-DE',
    group: 'public',
    kind: '形式',
    tagline: 'EDXL 配布容器を開き、内包内容ごとに形式と公開可否を判断して投稿化する',
    summary:
      'OASIS EDXL Distribution Element 1.0 の配布容器を開き、内包内容ごとに形式と公開可否を判断して投稿化する手順と、配布者・配布意図・選択した内容の位置を保持する拡張を定義する。容器送信者、内包文書の発表者、変換して投稿する著者の三者を区別する。',
    points: [
      '全ての内容を CAP とみなさず、名前空間で形式を識別する。',
      '容器の出典は source.containers に外側から内側の順で保持し、内包 CAP の発表者とは別に扱う。',
      'targetArea は配布対象であり警報の影響範囲ではないため、#area に置き換えない。',
      '容器の取消と内包警報の取消は別の層であり、置き換えない。',
    ],
    deps: ['', 'public', 'public/cap'],
  },
  {
    slug: 'public/jma',
    nsid: 'dev.jisaku.mosimo.public.jma',
    title: '気象庁 XML 共通',
    en: 'JMA XML',
    group: 'public',
    kind: '形式',
    tagline: '管理部・ヘッダ部の共通型、運用種別、独立した情報単位、地域別の単独で読める投稿',
    summary:
      '気象庁防災情報 XML を投稿へ変換する際に電文種別に共通する事項を定義する。管理部・ヘッダ部を保持する共通型、通常・訓練・試験の運用種別、続報・訂正・取消の関係、地域別の単独で読める投稿への分割と署名前の明示付き省略、原文の保存とハッシュを扱う。',
    points: [
      '独立した情報単位は情報名称・運用種別・地震識別情報などの組で区別し、同じ地震の全情報を一つとは扱わない。',
      '府県予報区等を基本単位とし、サイズ超過時だけ細分区域へ分ける。各投稿は必要な管理情報を自己完結して持つ。',
      '原文ハッシュは HTTP の符号化を解除した原文バイト列の SHA-256 である。',
      '共通型の共有は型定義の共有であり、投稿間の実行時依存ではない。',
    ],
    deps: ['', 'public', 'geo', 'validity'],
  },
  {
    slug: 'public/jma/eew',
    nsid: 'dev.jisaku.mosimo.public.jma.eew',
    title: '緊急地震速報',
    en: 'JMA EEW',
    group: 'public',
    kind: '形式',
    tagline: 'VXSE43・VXSE44 の震源・精度・予測・警報対象を保持し、府県予報区ごとに投稿化する',
    summary:
      '緊急地震速報（警報・予報）を投稿へ変換する拡張を定義する。震源と精度、予測震度、警報対象、付加文を保持し、府県予報区を基本とする地域別の単独で読める投稿を生成する。即時到達や揺れ前到達は保証せず、既存の緊急警報手段を代替しない。',
    points: [
      '予測震度は観測震度と区別して保持する。観測は地震観測情報が扱う。',
      '各投稿は共通ヘッダ拡張を取得せずに単体で読める。',
      '続報・訂正・取消は原電文の情報単位で扱う。',
    ],
    deps: ['', 'public', 'public/jma', 'geo', 'validity'],
  },
  {
    slug: 'public/jma/earthquake',
    nsid: 'dev.jisaku.mosimo.public.jma.earthquake',
    title: '地震観測情報',
    en: 'JMA earthquake',
    group: 'public',
    kind: '形式',
    tagline: '震度速報・震源情報・震源震度情報・長周期地震動観測情報の型と地域別投稿',
    summary:
      '震度速報、震源に関する情報、震源・震度に関する情報、長周期地震動に関する観測情報を投稿へ変換する拡張を定義する。管理情報・震源・観測階層・長周期地震動を保持し、府県を基本とし超過時だけ細分区域へ分ける地域別投稿を生成する。',
    points: [
      '観測震度は予測震度と区別し、未入電や掲載のない地点を震度 0 とみなさない。',
      'サイズ超過時は低優先の詳細を明示付きで省略し、中継時に削らない。',
      '津波・推計震度分布・火山情報は対象外である。',
    ],
    deps: ['', 'public', 'public/jma', 'geo', 'validity'],
  },
  {
    slug: 'public/jma/weather',
    nsid: 'dev.jisaku.mosimo.public.jma.weather',
    title: '気象防災情報',
    en: 'JMA weather',
    group: 'public',
    kind: '形式',
    tagline: '気象警報・注意報、土砂災害警戒情報、竜巻、記録的短時間大雨、洪水予報、台風などの構造化部分集合',
    summary:
      '気象警報・注意報、土砂災害警戒情報、竜巻注意情報、記録的短時間大雨、指定河川洪水予報、台風、線状降水帯、顕著な大雪、見通し・解説を投稿へ変換する拡張を定義する。地域ごとの現象と状態、構造化した気象内容の部分集合を保持し、対応範囲を limitations で明示する。',
    points: [
      '種別ごとに対応と非対応の境界を定め、部分対応を limitations で明示する。',
      '地域別の投稿単位と本文、領域、サイズ上限を定める。',
      '続報・解除・取消の認可は公的情報の規則に従う。',
    ],
    deps: ['', 'public', 'public/jma', 'geo', 'validity'],
    figure: {
      caption: '気象防災情報の変換手順。対応を宣言した種別・版・Part だけを構造化する',
      source: `flowchart TD
  X["JMA XML を取得"]
  S["原文を保存<br/>SHA-256 を記録"]
  K{"種別・版・Part が<br/>対応範囲か"}
  D["地域別または whole に分割"]
  P["本文 + 拡張 + source<br/>未対応部分は limitations"]
  H["原文のみ保存<br/>自動変換を保留"]
  X --> S --> K
  K -->|対応| D --> P
  K -->|未対応| H`,
    },
  },
  {
    slug: 'public/bsaf',
    nsid: 'dev.jisaku.mosimo.public.bsaf',
    title: 'BSAF v1 互換',
    en: 'BSAF',
    group: 'public',
    kind: '形式',
    tagline: 'Bluesky Structured Alert Feed の投稿との相互運用。収集、取り込み、新規作成、別途公開、循環抑制',
    summary:
      'Bluesky Structured Alert Feed（bsaf:v1）の投稿と本プロトコルの投稿との相互運用を定義する。原投稿のタグ・言語・URI・CID を改変せずに保持し、取り込み者の署名による転載であることを常に区別できるようにする。',
    points: [
      '三つの経路。既存 BSAF 投稿の AppView 収集、本プロトコルへの取り込み（選んだ Bot と種別に限る自動再配信）、本プロトコルからの新規作成と BSAF 形式での別途公開。',
      '取り込みは original（原投稿の URI・CID・作成時刻）を必須とし、attest.importer の認可を受ける。',
      '循環再配信を抑制し、表示では重複を折りたたむ。',
      'BSAF は汎用の Bot 投稿規約であり、タグの存在は公的警報の認定ではない。',
    ],
    deps: ['', 'public', 'attest/importer', 'geo', 'priority'],
    types: ['dev.jisaku.mosimo.public.bsaf'],
  },
  {
    slug: 'dm',
    nsid: 'dev.jisaku.mosimo.dm',
    title: '個別通信（DM）',
    en: 'Direct message',
    group: 'dm',
    kind: '通信',
    tagline: 'NIP-04 で暗号化した 1 対 1 メッセージ、構造化オブジェクトの運搬、配達通知、表示名',
    summary:
      'Mosimo の鍵を持つ二者の間で、暗号化した 1 対 1 のメッセージをメッシュとリレーで運ぶ方法を定義する。テキストのほか $type 付きの構造化オブジェクトを運べ、失効要求のようなプロトコル内の要求もエンドポイントに届かない状況でメッシュ経由で送れる。',
    points: [
      'メッセージと配達通知は kind 4、表示名は kind 0。いずれも配送期限タグを持ち、atproto commit は付けない。',
      '暗号化は NIP-04（ECDH と AES-256-CBC）。通信相手は隠さない。',
      '平文は $type を持つ JSON なら構造化オブジェクト、それ以外はテキストとして扱う。',
      '配送状態は送信中・中継・配達・期限切れの観測であり、期限切れの自動再送はしない。',
    ],
    deps: ['', 'mesh'],
    figure: {
      caption: 'メッセージと配達通知の流れ。中継者は暗号文と通信相手（p タグ）だけを見る',
      source: `sequenceDiagram
  participant A as 送信者
  participant M as メッシュ・リレー
  participant B as 宛先
  A->>A: ECDH 共有鍵で AES-256-CBC 暗号化
  A->>M: kind 4（p = 宛先, mosimo:exp）
  M->>B: 保存・中継
  B->>B: p が自分を指すものだけ復号
  B->>M: kind 4 配達通知（e = メッセージ ID）
  M->>A: 保存・中継
  A->>A: 状態を「配達」に更新`,
    },
  },
];

export const bySlug = new Map(protocols.map((p) => [p.slug, p]));

/** slug の URL。コアは一覧ページ内のコア節を指す。 */
export function urlOf(slug: string): string {
  return slug ? `/protocol/${slug}/` : '/protocol/#core';
}

/** 本プロトコルを前提とするプロトコル */
export function dependentsOf(slug: string): Protocol[] {
  return protocols.filter((p) => p.deps.includes(slug));
}

/** 実装の立場ごとの読む順序 */
export const readingPaths: { role: string; path: string[]; note?: string }[] = [
  { role: '発信アプリ', path: ['', 'geo', 'validity', 'annotation', 'mesh'], note: '身元を示す場合は attest/chain' },
  { role: '表示・中継だけのアプリ', path: ['', 'mesh'], note: '未知の拡張は書き換えず保持する' },
  { role: 'PDS・Nostr リレー', path: ['', 'pds', 'attest/revocation/list'] },
  { role: '公的情報の取り込み', path: ['public', 'public/cap', 'public/edxl', 'public/jma', 'attest/importer'] },
  { role: '認証局', path: ['attest', 'attest/revocation', 'attest/revocation/list', 'attest/revocation/request'], note: '自動発行は attest/signing、個人向けは attest/external、転載サーバーへの発行は attest/importer' },
  { role: 'ゲートウェイ（Android・ESP32）', path: ['mesh', 'attest/chain', 'attest/importer'] },
];
