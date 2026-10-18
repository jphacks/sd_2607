# VPSへのデプロイ

## 構成

- 既定はNode.js 24＋SQLiteのネイティブsystemdサービス`mosimo-pds`。
  `pds/default.nix`のimportで`package.nix`からビルドする。OCIイメージも、peer未設定なら
  env-fileも不要。署名鍵をPDSへ配置しない。
- SQLiteは**単一物理ノード・永続ローカルディスク専用**。ネットワーク・共有ファイルシステムは
  使用しない。`StateDirectory=mosimo-pds`が管理する`/var/lib/mosimo-pds/pds.sqlite`へ保存する。
  `DynamicUser=true`、ディレクトリモード`0700`、`UMask=0077`で実行する。
- SQLiteはWAL、`synchronous=FULL`、`cache_size=-2048`（約2 MiB）、`temp_store=FILE`、
  `busy_timeout=5000`、`wal_autocheckpoint=1000`を設定し、トランザクションを直列化する。
- `MemoryHigh=384M`、`MemoryMax=512M`、Nodeの`--max-old-space-size=256`を設定する。
  old-space上限はプロセス全体のメモリ上限ではない。これらは資源保護の設定値であり、
  3 GiB VPS全体での安定稼働や処理性能を実測・保証した値ではない。
- **同じ論理PDSを複数物理ノードで提供する場合は、共通PostgreSQLを明示選択する。**
  リポジトリのhead、firehoseのseq・履歴をトランザクション内で更新する。
  別々のSQLite DBへ同じ公開名を負荷分散したり、別DBへ無条件に切り替えたりしない。
- 一つの物理ノードは複数グループを提供できる。`groups[].id` は再起動・ノード間で不変。
  イベント・検証済みcommit・ブロックは共有し、公開headとstreamはグループごとに分離する。
- 別DBのpeer間では、認証付き通知と必要時のイベント取得を行い、各自が検証する。
  peer同期はDBのレプリケーションやHAの代替ではない。
- 永続ディスク、TLS、DNS、バックアップは運用側で用意する。PostgreSQLを選ぶ場合は
  DBと必要なフェイルオーバーも運用側で管理する。バックエンド切替による自動データ移行はない。
- CockroachDBは自動的に軽量になる代替ではない。分散DBとして別の資源・運用要件があり、
  PostgreSQL互換を理由にこの実装のSQL・ロック・トランザクションの互換性や低メモリ動作を
  仮定しない。本実装での適合性は未検証であり、低メモリ単一ノードの既定はSQLiteとする。

## OCIイメージ（任意）

既定のネイティブ実行ではこの手順は不要。`services.mosimo-pds.image`にイメージ参照を
指定した場合だけPodmanのOCI実行に切り替わり、unitは`podman-mosimo-pds`となる。
SQLiteの場合、DBはホストのStateDirectoryではなくPodman名前付きvolume`mosimo-pds`の
コンテナ内`/var/lib/mosimo-pds/pds.sqlite`へ保存する。volumeもバックアップ対象に含める。
OCIにもメモリ予約384 MiB・上限512 MiB、Nodeのold-space上限256 MiBを設定する。

リポジトリルートで実行する。以下のregistry名は例であり、公開済みイメージではない。

```sh
podman build -f pds/Containerfile -t ghcr.io/your-org/mosimo-pds:release-1 pds
podman push ghcr.io/your-org/mosimo-pds:release-1
podman image inspect ghcr.io/your-org/mosimo-pds:release-1 --format '{{json .RepoDigests}}'
```

本番の`image`には公開後に確認した実際のdigestを指定する。Nixモジュールはイメージを
ビルドしない。`Containerfile`はlockfileで`npm ci --omit=dev`を実行し、非rootの
`node`ユーザーで起動する。`.dockerignore`は依存、テスト、設定・秘密ファイルを除外する。
Nodeベースイメージのタグは更新されるため、厳密な再現性が必要なら実digestに固定する。

## 外部non-flake import

運用先の[外部import規約](https://github.com/oriom-dev/podman-cluster-config/blob/main/containers/README.md)
に従う。以下は運用先で適用する構成例であり、実VPSへのデプロイ完了を示すものではない。
指定revisionにSQLite対応の`pds/default.nix`・`package.nix`等が含まれる必要がある。

```nix
# flake.nix の inputs
inputs.mosimo = {
  url = "github:jphacks/sd_2607";
  flake = false;
};
```

```nix
# hosts/kagoya_vps/default.nix
{ inputs, ... }:
{
  imports = [ (inputs.mosimo + "/pds") ];
  services.mosimo-pds = {
    enable = true;
    port = 2583;
    # databaseBackend = "sqlite"; # 既定値
    # imageとenvironmentFileは不要（peerなしの場合）。
    settings = {
      groups = [
        { id = "group-a"; endpoint = "https://group-a.example.org"; }
        { id = "group-b"; endpoint = "https://group-b.example.org"; }
      ];
      relays = [ "wss://relay.example.org" ];
      peers = [ ]; # peerを追加する場合は認証用environmentFileも設定する
    };
  };
}
```

入力revisionをlockし、運用先で更新差分を確認してビルドする。OCIを選ぶ場合のみイメージの
pinも別途管理する。ディレクトリ名は`hosts/kagoya_vps`だが、flakeの構成名はハイフン付きの
`kagoya-vps`である。以下の`switch`は対象VPS上で実行する。

```sh
nix flake lock --update-input mosimo
git --no-pager diff -- flake.lock
nix build .#nixosConfigurations.kagoya-vps.config.system.build.toplevel
sudo nixos-rebuild switch --flake .#kagoya-vps
```

### 設定と秘密

`settings`はJSONとしてNix storeに入る。秘密は入れない。設定項目:

| 項目 | 既定値・意味 |
| --- | --- |
| `groups` | 1件以上必須。`id`とcanonical HTTPS originの`endpoint` |
| `relays` | `[]`。収集先の`wss://` URL |
| `peers` | `[]`。管理者が承認した相互通知・取得先のHTTPS origin |
| `peers[].tokenEnv` | `PEER_TOKEN`。送信Bearer tokenを読む環境変数名 |
| `plcUrl` | `https://plc.directory`。信頼するPLC Directory |
| `maxEventBytes` | `131072`。Nostr JSON/WebSocketメッセージ上限 |
| `maxSubscriptions` | `20`。接続あたりの購読上限 |
| `maxResults` | `500`。取得・再送バッチ上限 |
| `maxBufferedBytes` | `4194304`。WebSocketの待機データ上限 |
| `maxEvents` | `1000000`。DB全体の保存イベント数上限 |

SQLiteでpeerがなければ`environmentFile`は不要。peer認証やPostgreSQLを使う場合のみ、
Nix store外の絶対パスにroot所有・モード`0600`のenv-fileを実行前に作成し、
`services.mosimo-pds.environmentFile`へ指定する。例の値は実環境へ置き換える。
SQLiteの場合、次の例の`DATABASE_URL`行は不要。

```dotenv
DATABASE_URL=postgresql://mosimo:REPLACE_WITH_URL_ENCODED_PASSWORD@db.internal:5432/mosimo
PEER_TOKEN=REPLACE_WITH_RANDOM_SHARED_SECRET
```

PostgreSQLは`databaseBackend = "postgresql";`と`environmentFile`を明示指定し、env-fileの
`DATABASE_URL`を必須とする。専用DB・専用ロールとスキーマ作成用DDL権限を用意する。
起動時にadvisory lock内でスキーマを初期化するが、既存DBからSQLiteへの自動移行はしない。
`DATABASE_PATH`と`DATABASE_URL`を同時に設定すると起動を拒否する。既存のPostgreSQL構成を
更新する場合も`databaseBackend = "postgresql";`を明示し、意図しない空DBへの切替を防ぐ。
peerを設定する場合は`PEER_TOKEN`と各`tokenEnv`が必須。
受信側は`PEER_TOKEN`で認証する。送信側のtokenは相手の`PEER_TOKEN`に一致させ、
peerの一覧は相互に登録する。公開Nostr/XRPCはこのBearer認証の対象ではない。
運用主体の異なるpeerへ同じ共有秘密を使い回さない。公開先・peer URLを投稿内容から
自動追加しない。通知先・取得先をallowlistに限定し、リダイレクトも拒否する。

sops-nixでは`environmentFile = config.sops.templates."mosimo-pds.env".path;`等を使用できる。
秘密を`builtins.readFile`でNix storeに取り込まない。`HOST`・`PORT`・`CONFIG_FILE`・
`DATABASE_PATH`をenv-fileで上書きしない。PostgreSQLには実行環境から到達できるprivate
接続先を使い、TLS/CA・pg_hba・Headscale ACLを設定する。OCIではlocalhostはコンテナ自身を
指すことに注意する。DBポートを公開しない。

## Caddyと公開endpoint

モジュールは`127.0.0.1:2583`にだけbindし、firewallを開かない。
運用先の既存`services.caddy.virtualHosts`に全論理グループ名と物理ノード名を追加する。

```nix
services.caddy.virtualHosts = {
  "group-a.example.org".extraConfig = ''
    reverse_proxy 127.0.0.1:2583
  '';
  "group-b.example.org".extraConfig = ''
    reverse_proxy 127.0.0.1:2583
  '';
  "pds-node-a.example.org".extraConfig = ''
    reverse_proxy 127.0.0.1:2583
  '';
};
```

Caddyは元のHostを保持し、WebSocket Upgradeも処理する。**全パス**を転送し、Hostを
upstreamのloopbackへ書き換えない。論理グループはHostで選択する。物理ノードの
Nostr endpointは`wss://pds-node-a.example.org/`、NIP-11は同じURLのHTTPS表現。
SQLite時のグループのDNSは単一物理ノードだけに向ける。PostgreSQLで複数ノードを使う場合も、
DNS/LBは同一DB・同一グループ設定のノードにだけ向ける。

## 起動と運用

1. SQLiteではsystemdがStateDirectoryを作成し、初回起動時に`store.mts`がスキーマを初期化する。
   ローカルディスクの空き容量・永続性を確認する。接続・初期化失敗時は起動失敗。
2. `systemctl status mosimo-pds`、`journalctl -u mosimo-pds`で確認する。
   `curl http://127.0.0.1:2583/health`で生存確認する。OCI選択時だけunit名は`podman-mosimo-pds`。
   `/health`はプロセスの生存確認であり、PLC/Relay/DB全体のreadiness保証ではない。
3. PLC登録は端末側が事前に行う。署名鍵とPDS endpointが解決できる署名済み投稿から
   自動受け入れする。手動アカウント作成や、サーバーへの著者秘密鍵の登録はない。
4. atproto Relayの取り込みは、対象Relayの参加条件を確認し、標準の
   `com.atproto.sync.requestCrawl`へ論理PDSのhostnameを登録する。自動crawl要求は送らない。
   署名の正しさだけでRelayの受け入れ・AppViewへの表示が保証されるわけではない。
5. `jobs`の`status,error,attempts,next_attempt`を監視する。欠落baseやPLC・鍵状態の
   検証失敗はバックオフ再試行、不正形式/MSTは`invalid`、分岐は`conflict`となる。
   `events`への保存と`jobs.status='validated'`、正規公開先への掲載は別の状態。
6. 下記のバックアップ・復元手順を実施する。設定JSONやパッケージだけではデータを復旧できない。
7. 保存・firehose履歴の自動削除はない。容量監視が必要。最大件数では新規受理を拒否する。
   peer通知は最大10回再試行し、その後通知キューから除去する。元イベントは保持する。

## バックアップと復元

SQLite稼働中の`pds.sqlite`だけを通常のファイルコピーで保存してはいけない。WAL内の
確定済み更新を失う可能性がある。次のいずれかで整合したバックアップを作成する。

- 推奨: SQLiteのオンラインバックアップ機能を使う。例えば`sqlite3`コマンドを導入したホストで、
  アクセス制限したバックアップ先ディレクトリを事前作成し、次を実行する。
  この出力は単体で整合したDBなので、元のWAL/SHMを別途混ぜない。

  ```sh
  sudo sqlite3 /var/lib/mosimo-pds/pds.sqlite ".backup '/var/backups/mosimo-pds/pds.sqlite'"
  ```

- ファイルコピーする場合: `sudo systemctl stop mosimo-pds`で停止完了を確認し、他の書き込み元も
  止めた状態で`pds.sqlite`と、存在する`pds.sqlite-wal`・`pds.sqlite-shm`を同一時点の組として
  コピーする。正常終了でWAL/SHMが消えている場合はDB本体だけでよい。コピー完了まで
  再起動しない。OCIでは対応unitを停止し、名前付きvolume内の同じ組を保存する。

バックアップは別ディスク等にも保管し、アクセス権を制限して復元試験を行う。
復元はサービス・公開を停止して実施し、復元先に残った別世代のWAL/SHMを混ぜない。
オンラインバックアップならDB単体を、停止コピーなら存在した組全体を戻し、StateDirectoryの
所有権・モードも正しく復元する。`DynamicUser`の一時UIDを固定値として扱わない。

**過去のDBへ戻したまま同じ公開名で再開すると、firehoseのseqを別の意味で再利用し得るため
禁止**。整合したバックアップでも巻き戻しによるseq再利用は防げない。欠損しないDB復旧・
公開の隔離と購読者の再同期方針を先に決める。peer同期だけでstream履歴の復旧を保証しない。
PostgreSQLの場合は`pg_dump -Fc`やWAL/PITRでDB全体・権限をバックアップし、同じseqの
注意事項を適用する。バックエンドの変更はデータ移行ではなく、自動PG→SQLite移行はない。

## 検証済み範囲と残る作業

既存のエージェント実施記録では、WSL NixOS上でネイティブパッケージとsystemd unitの
ビルドが成功し、Windows/Node.js 24上でランタイム確認・テストが成功している。
SQLiteのテストとPGlite上のPostgreSQL SQL統合テストを含むが、PGliteではadvisory lockを
置換しており、実PostgreSQL複数プロセスでのロック・障害切替試験は別途必要。

管理者によるホスト鍵の確認後、ネイティブ構成を実VPSへデプロイした。2026-10-18には
実PLC Directory・Damus・本番の公開APIを使い、commit/MST/CAR/firehose、順序逆転、
外部履歴・新規ライブ収集、サービス再起動後の永続化を検証した。検証中にPLC応答項目名と
外部履歴のキュー超過による再接続ループを修正し、本番へ反映した。
[本番動作検証報告](../docs/pds-production-verification-2026-10-18.md)に公開証拠と再実行手順を記録している。

実Bluesky Relayへの参加要求はHTTP 400 / HostNotFound / XRPCNotSupportedで拒否された。
同期APIを読めることを実Relayへの取り込み成功と混同しない。kind 1/5以外のNostrイベントも非対応。
短時間の機能確認は負荷・長時間稼働・障害復旧や上記メモリ制限での処理能力を保証しない。
OCIビルド・実行、実PostgreSQL複数プロセス、完全な相互運用性は今回検証していない。
プロトコル未確定部分・現時点の制限は[README](README.md)を参照。
