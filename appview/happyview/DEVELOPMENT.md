# HappyViewの運用設定

## NixOSでの設定

リポジトリの `nixos/` をimportし、次の項目を設定します。

| 設定 | 内容 |
|---|---|
| `services.happyview.enable` | サービスの有効化 |
| `services.happyview.publicUrl` | 公開オリジン。サブパスは指定しません |
| `services.happyview.environmentFile` | `SESSION_SECRET` と `TOKEN_ENCRYPTION_KEY` を含む、ホスト上の実行時ファイルへの絶対パス |
| `services.happyview.listenAddress` | ポート3000の公開先アドレス。既定はループバック |

秘密情報はリポジトリやNix式へ直接書かないでください。外部公開には別途リバースプロキシ等を用意します。

## 保存・更新時の注意

- データはPodmanの `happyview-data` ボリュームに永続化します。更新時にボリュームを削除しないでください。
- 使用するイメージは `Containerfile` で固定しています。HappyViewを更新するときは、既存DBのバックアップと、BSAF収集機能を含めた互換性確認を行ってください。
- フィードの登録手順は [Lexiconの開発文書](../../lexicons/DEVELOPMENT.md#happyviewへの登録)、BSAF収集を有効にする手順は [BSAFの開発文書](../bsaf/DEVELOPMENT.md)を参照してください。
