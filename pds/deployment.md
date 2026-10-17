# VPSへのデプロイ

## 構成

- Node.js 24＋PostgreSQL。署名鍵をPDSへ配置しない。
- **同じ論理PDSを提供する全物理ノードは、同じPostgreSQLを参照する。**
  リポジトリのhead、firehoseのseq・履歴をトランザクション内で更新する。
  別DBのノードへ無条件に切り替えたり、物理ノード独自のseqを負荷分散しない。
- 一つの物理ノードは複数グループを提供できる。`groups[].id` は再起動・ノード間で不変。
  イベント・検証済みcommit・ブロックは共有し、公開headとstreamはグループごとに分離する。
- 別DBのpeer間では、認証付き通知と必要時のイベント取得を行い、各自が検証する。
  peer同期はDBのレプリケーションやHAの代替ではない。
- DB、TLS、DNS、バックアップ、DBフェイルオーバーは運用側で用意する。

## OCIイメージ

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
に従う。この作業では運用先リポジトリや実VPSを変更していない。
以下の変更は運用先で行う。指定revisionに`pds/`が含まれる必要がある。

```nix
# flake.nix の inputs
inputs.mosimo = {
  url = "github:jphacks/sd_2607";
  flake = false;
};
```

```nix
# hosts/kagoya_vps/default.nix
{ inputs, config, ... }:
{
  imports = [ (inputs.mosimo + "/pds") ];
  services.mosimo-pds = {
    enable = true;
    image = "ghcr.io/your-org/mosimo-pds:release-1";
    port = 2583;
    environmentFile = "/run/secrets/mosimo-pds.env";
    settings = {
      groups = [
        { id = "group-a"; endpoint = "https://group-a.example.org"; }
        { id = "group-b"; endpoint = "https://group-b.example.org"; }
      ];
      relays = [ "wss://relay.example.org" ];
      peers = [ { url = "https://pds-node-b.example.org"; } ];
    };
  };
}
```

入力とイメージのpinは別々に管理する。運用先で更新差分を確認してビルドする。

```sh
nix flake lock --update-input mosimo
git diff -- flake.lock
nix build .#nixosConfigurations.kagoya_vps.config.system.build.toplevel
sudo nixos-rebuild switch --flake .#kagoya_vps
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

root所有・モード`0600`のenv-fileを実行前に作成する。例の値は実環境へ置き換える。

```dotenv
DATABASE_URL=postgresql://mosimo:REPLACE_WITH_URL_ENCODED_PASSWORD@db.internal:5432/mosimo
PEER_TOKEN=REPLACE_WITH_RANDOM_SHARED_SECRET
```

`DATABASE_URL`必須。peerを設定する場合は`PEER_TOKEN`と各`tokenEnv`が必須。
受信側は`PEER_TOKEN`で認証する。送信側のtokenは相手の`PEER_TOKEN`に一致させ、
peerの一覧は相互に登録する。公開Nostr/XRPCはこのBearer認証の対象ではない。
運用主体の異なるpeerへ同じ共有秘密を使い回さない。公開先・peer URLを投稿内容から
自動追加しない。通知先・取得先をallowlistに限定し、リダイレクトも拒否する。

sops-nixでは`environmentFile = config.sops.templates."mosimo-pds.env".path;`等を使用できる。
秘密を`builtins.readFile`でNix storeに取り込まない。`PORT`・`CONFIG_FILE`をenv-fileで
上書きしない。DBのlocalhostはコンテナ自身を指すため、コンテナから到達できるprivate
接続先を使い、TLS/CA・pg_hba・Headscale ACLを設定する。DBポートを公開しない。

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
グループのDNS/LBは同一DB・同一グループ設定のノードにだけ向ける。

## 起動と運用

1. PostgreSQL専用DB・専用ロール・秘密ファイルを用意する。起動時に`store.mjs`の
   スキーマをadvisory lock内で初期化する。DDL権限が必要。接続・初期化失敗時は起動失敗。
2. `systemctl status podman-mosimo-pds`、`journalctl -u podman-mosimo-pds`で確認。
   `/health`はプロセスの生存確認であり、PLC/Relay/DB全体のreadiness保証ではない。
3. PLC登録は端末側が事前に行う。署名鍵とPDS endpointが解決できる署名済み投稿から
   自動受け入れする。手動アカウント作成や、サーバーへの著者秘密鍵の登録はない。
4. atproto Relayの取り込みは、対象Relayの参加条件を確認し、標準の
   `com.atproto.sync.requestCrawl`へ論理PDSのhostnameを登録する。自動crawl要求は送らない。
   署名の正しさだけでRelayの受け入れ・AppViewへの表示が保証されるわけではない。
5. `jobs`の`status,error,attempts,next_attempt`を監視する。欠落baseやPLC・鍵状態の
   検証失敗はバックオフ再試行、不正形式/MSTは`invalid`、分岐は`conflict`となる。
   `events`への保存と`jobs.status='validated'`、正規公開先への掲載は別の状態。
6. `pg_dump -Fc`やWAL/PITRを用い、DB全体・権限をバックアップして復元試験を行う。
   コンテナや設定JSONのバックアップだけでは不十分。**過去のDBへ戻したまま同じ公開名で
   再開するとseqを別の意味で再利用し得るため禁止**。欠損しないDB復旧・隔離を先に行う。
7. 保存・firehose履歴の自動削除はない。容量監視が必要。最大件数では新規受理を拒否する。
   peer通知は最大10回再試行し、その後通知キューから除去する。元イベントは保持する。

## 検証済み範囲と残る作業

Windows/Node.js 24で単体テストとPGlite上のPostgreSQL SQL統合テストを実施した。
PGliteテストではadvisory lockのみ置換しているため、実PostgreSQL複数プロセスでの
ロック・障害切替試験は別途必要。Nix未導入のためNixOS評価・ビルドは未実施。
Podman CLIはあるがVMへ接続できず、OCIビルド・実行、実VPSへの配置は未実施。
実atproto Relayとの相互運用試験も未実施であり、完全互換性の認証済みとは扱わない。
プロトコル未確定部分・現時点の制限は[README](README.md)を参照。
