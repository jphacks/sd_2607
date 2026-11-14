# 気象庁XML転載サーバー（importer/jma-xml）

気象庁防災情報XMLの公開PULLフィードから電文を取得し、Mosimoの投稿に変換して署名し、PDSのNostrリレーへ送るサーバーです。転載の認可は [`attest.importer`](../../protocol/mosimo/attest/importer/README.md) のフィード `jma-xml` で受けます。BSAFの転載（`appview/bsaf`）とは別の鍵・別の証明書で動きます。

## できること

- **地震情報**: 「震源・震度に関する情報」を、震度を観測した都道府県ごとの投稿にします。震源・マグニチュード・観測震度（`hazard.earthquake`、`jp.jma.shindo`）と、震度を観測した細分区域の範囲が付きます。取消電文は取消の通知として1投稿にします。
- **気象警報・注意報（R06）**: 大雨・土砂・高潮・暴風・波浪・大雪・その他注意報の7種別について、1電文を1投稿にします。警戒レベル・警報の種別（`hazard.weather`、`jp.jma.warning-level`／`warning-class`）と、現に発表中の市町村等の範囲が付きます。解除だけの電文は「解除（AllClear）」になります。
- **解除・続報の表示**: R06電文は毎回、府県内の全市町村の状態を載せます。アプリは同じ系列の最新投稿だけを地図で強調します（[public.jma §5.3](../../protocol/mosimo/public/jma/README.md#53-表示上の置換)）。
- **原文の保存**: 取得した原XMLとSHA-256をすべて保存し、投稿の `public#source` にハッシュを載せます。

緊急地震速報、津波、火山、H27形式の警報、台風などは対象外です。訓練・試験の電文は取り込みません。

## 動き方

1. `extra.xml`（気象）と `eqvol.xml`（地震火山）を60秒ごとに条件付きGETで取得します。起動直後だけ `_l` の長期フィードも読み、`maxMessageAgeSeconds` 以内の電文を補います。気象警報は同じ府県・同じ種別の最新電文だけを取得します。
2. 新しい電文を取得して保存し、変換します（`convert.mts`）。メッシュの上限32KiBに収まらない地震投稿は、細分区域ごとに分割し、それでも収まらなければ観測点の詳細を省いて、省いたことを本文と拡張に明記します。
3. 転載鍵で署名し、受信側と同じ規則（チェーン・範囲・失効・容量）で自分の投稿を検証してから保存します。
4. 優先度の高い順にPDSへ送ります。失敗した場合は同じ署名済みイベントを再送します。配送期限は延ばしません。

範囲データ `areas.json` は気象庁の予報区等GISデータから `tools/area_cover.py` で生成しています。区域を約1km広げた範囲を、Geohashセル（警報の市町村等は5文字、1区域32セルを超える場合は4文字。地震の細分区域は4文字）で覆っています。

| 表 | 元データ |
|---|---|
| `warningCity`（気象警報・注意報の市町村等） | `20260226_AreaInformationCity_weather_GIS.zip` の「市町村等（気象警報等）」。2026年5月下旬以降の区域 |
| `quakeArea`（地震情報の細分区域） | `20240520_AreaForecastLocalE_GIS.zip` の「地震情報／細分区域」 |

気象庁『予報区等GISデータ』（https://www.data.jma.go.jp/developer/gis.html）を加工して作成。気象庁が区域を変更した場合は、新しいデータで作り直してください。電文の区域コードが表にない投稿には範囲を付けません。

## 鍵の発行（ルート鍵のあるPCで実行）

鍵の発行は認証局ツール [`mosimo-ca`](../../ca/README.md) で行います。転載範囲はプリセット `jma-xml`（フィード `jma-xml` の8種。[importer §1.1](../../protocol/mosimo/attest/importer/README.md#11-フィード)）です。チェーンは次の3段にします。

```text
oriom-root                 ルート鍵（受信側のルート設定は maxDepth 16）
  └─ import                役割ごとの認証局。attest・import、maxDepth 2、issuerDomain、転載範囲 jma・kikikuru・jma-xml
       └─ oriom            運営者。maxDepth 1、organization
            └─ jma-xml-1   ノード（転載サーバーの Nostr 鍵）。import、730日
```

- `feeds` を持つ鍵署名は、`feeds` に未対応の検証器ではチェーンごと不正になります。`feeds` に対応したアプリは、ルート設定の `maxDepth` も16に上げてあります。2段までしか受け付けない旧版アプリは、3段のこのチェーンをどのみち検証できません。そのため、`feeds` は現行の2段のチェーン（`oriom-mosimo-ca`）には入れず、新しい3段のチェーンの認証局に入れます。
- 認証局の `issuerDomain` は `pds.oriom.dev` 以外にします。失効リストは1ドメインに1つで、そのドメインを持つ認証局が署名するためです。`pds.oriom.dev` は現行の `oriom-mosimo-ca` が使っています。
- `import` が既にあって転載範囲に `jma-xml` を含まない場合は、子に `jma-xml` を足せません。別の認証局（例: `oriom-root/jma-xml`）を同じ形で発行してください。

`ca/` で `nix develop ./ca` に入るか、以下の `mosimo-ca` を `nix run ./ca --` に読み替えます。`appview/bsaf` では先に `npm ci && npm ci --prefix ../../pds` を実行しておきます。

```sh
# 1. 役割ごとの認証局（まだない場合）
mosimo-ca issue oriom-root import --max-depth 2 --domain <認証局のドメイン> \
  --owner-name 'Oriom Mosimo 転載 CA' --preset jma --preset kikikuru --preset jma-xml --age-passphrase
# 2. 運営者（まだない場合）。orgId は以下のノードすべてで共通になる
mosimo-ca issue oriom-root/import oriom --max-depth 1 --owner-name Oriom \
  --org-id oriom.dev:mosimo-importer --org-name 'Mosimo importer (not JMA)' --days 1095 --age-passphrase
# 3. ノードの DID と鍵（PLC 管理鍵・atproto 鍵・Nostr 鍵）を作る
node appview/bsaf/identity-cli.mjs importer-create https://pds.oriom.dev --out=importer-secret.json
# 4. 運営者がノードの Nostr 鍵に署名する。DID・atproto 鍵・PLC 管理鍵は jma-xml-1/_importer/ に入る
mosimo-ca issue oriom-root/import/oriom jma-xml-1 --leaf --owner-name '気象庁XML転載ノード 1' \
  --org-name 'Mosimo JMA XML importer (not JMA)' --preset jma-xml --importer-identity importer-secret.json --days 730
rm importer-secret.json   # PLC 管理鍵を平文で含む
# 5. 確認
mosimo-ca verify
mosimo-ca show oriom-root/import/oriom/jma-xml-1
```

受信者に表示される発信者名は、ノードの `organization.orgName` です。`orgId` は運営者と同じで、`orgName` だけをノードごとに変えられます。

## サーバーへの配置

サーバーに渡すのは、ノードの署名鍵2つと公開ファイルだけです。ルート・認証局・運営者・PLC管理鍵は渡しません。BSAF転載と同じ形式です（[PUBLISHER.md の Key placement](../../appview/bsaf/PUBLISHER.md#key-placement)）。

```sh
mosimo-ca credentials oriom-root/import/oriom/jma-xml-1 --out /tmp/jma-xml-importer
```

| ファイル | 種類 | 指定するオプション |
|---|---|---|
| `nostr.pem` | **秘密鍵**（PKCS#8 PEM、鍵署名されたNostr鍵） | `nostrKeyFile` に sops-nix のシークレット（`/run/secrets/...`） |
| `atproto.pem` | **秘密鍵**（PKCS#8 PEM、DIDのatproto署名鍵） | `atprotoKeyFile` に sops-nix のシークレット |
| `identity.json` | 公開（`{did, nostrPubkey, atprotoPubkey}`） | `identityFile` にNixのパス |
| `chain.cbor` | 公開（ルートからノードまでの鍵署名） | `chainFile` にNixのパス |
| `roots.json` | 公開（ルート設定） | `trustFile` にNixのパス |

秘密鍵はNixのパスで指定しないでください（評価時に拒否します）。Nixストアは誰でも読めるためです。起動時には、PEMがsecp256k1の鍵であること、`identity.json` と一致すること、チェーンの末端がNostr鍵であることを確かめます。

認証局の失効リスト（`_revocation-list/revocations.cbor`）は、最初に `mosimo-ca publish` を実行したときに作られます。作られたら `https://<認証局のドメイン>/.well-known/mosimo/attest/revocations` で配信してください。それまではリストがありません。取得に失敗しても転載は止まらず、受信側も前回の状態を保ちます。ただし、取り込みサーバーは10分ごとに `revocation-refresh-failed` を記録します。

## 設定

NixOSでは `services.mosimo-importer-jma-xml` を有効にします。

```nix
services.mosimo-importer-jma-xml = {
  enable = true;
  identityFile = ./mosimo-importer-jma-xml/identity.json;   # 公開
  chainFile = ./mosimo-importer-jma-xml/chain.cbor;         # 公開
  trustFile = ./mosimo-importer-jma-xml/roots.json;         # 公開
  nostrKeyFile = config.sops.secrets.mosimo_importer_jma_xml_nostr_pem.path;     # 秘密。Nixのパスにしない
  atprotoKeyFile = config.sops.secrets.mosimo_importer_jma_xml_atproto_pem.path; # 秘密。Nixのパスにしない
  relayUrl = "wss://pds.oriom.dev/";
};
```

鍵ファイルは systemd の `LoadCredential` で個別に渡され、サービスは `DynamicUser` で動きます。

`package.nix` の `npmDepsHash` はまだ計算していません（`lib.fakeHash`）。初回ビルドのエラーに表示される値に置き換えてください。

受信側の設定は `identity-cli.mjs receiver-export` で出力します。PDSの `publicInformation` に入れる `pds-public-information.json` と、アプリの `mosimo-public-trust.json` です。

```sh
node appview/bsaf/identity-cli.mjs receiver-export --anchor=ca/keys/oriom-root/anchor.json \
  --domain=pds.oriom.dev --domain=<認証局のドメイン> --jma --kikikuru --jma-xml --out=receiver
```

PDSは、`publicInformation.selection` に `{ "feed": "jma-xml", "types": [...] }` があるときだけ、この転載の優先度ヒントを認めます。また、ルート設定の `maxDepth` が3以上でないと、3段のチェーンを検証できません。

直接実行する場合は `IMPORTER_CONFIG_FILE=/absolute/config.json node importer.mts` です。設定項目は `importer.mts` の `ImporterConfig` を参照してください。

## 開発

```sh
npm ci && npm ci --prefix ../../pds
npm test && npm run typecheck
```

`fixtures/` には気象庁PULLフィードの実電文（2026-11）と公式サンプル（2026-09-17セット）を置いています。出典: 気象庁ホームページ（気象庁防災情報XML）。
