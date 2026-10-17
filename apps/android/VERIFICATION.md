# Android 検証メモ

## 実行した検証

- 指定フォークの BPv7 テスト33件（Rust 由来の wire fixture、CRC、未知 block 等を含む）に成功。
- `bundle-core` の署名・MST・ホップ等のテスト16件に成功。
- `mesh-android` の wire / HELLO 順序・入力サイズ制限・JSON 展開・リレー再送順序のテスト4件に成功。上記合計53件、失敗・error・skip は0件。
- `demoDebug` / `peerDebug` APK のビルドに成功。`aapt` で異なる application ID、minSdk 26 / targetSdk 35 / compileSdk 36 と必要な権限を確認。
- 両 APK の `apksigner verify` に成功。demo APK は `zipalign -c -P 16 4` に成功。
- `mesh-android:assembleRelease` によるライブラリ AAR の生成に成功（依存を同梱する fat AAR ではない）。
- app 両 variant と mesh ライブラリの Android lint に成功（error なし）。更新可能な依存、targetSdk、旧 API では無視される manifest 属性等の warning は残る。最新版への一括更新や warning の一括無効化は行っていない。

## 署名の独立照合

Kotlin の公開 fixture を現在のコードから生成し、Node の別実装で以下を確認した。テスト用の既知鍵を使っており、fixture に秘密鍵は含めない。

- `nostr-tools/pure`: event ID と BIP-340 署名。
- `@ipld/dag-cbor`: commit / record の正確な canonical bytes。
- `multiformats`: record CID / commit CID。
- `@atproto/crypto`: 標準の compact ECDSA commit 署名。
- `@atproto/repo`: 初回・2投稿目の MST root と、適用元 CID の連続性。
- `@did-plc/lib`: PLC genesis の署名と DID 導出（オフライン検証。Directory 登録ではない）。
- 鍵対応宣言の ECDSA / Schnorr 両署名。

独立実装の参照値は `SignatureTest` に保持する。特に MST は0/1/2/10/64/128件で照合する。通常の Gradle テストに Node は不要。

## 未検証

レビューで見つかった、JSON エスケープ後のリレー送信サイズ、先頭投稿の再送による後続の飢餓、Previous Node の持ち越し、避難所 LAN を Internet validation で拒否する問題は修正した。Previous Node は保存原本を変えず、送信用コピーから除去する。悪意ある設定先による OkHttp の受信時メモリ枯渇は未解決で、運用者が管理するリレーの使用を前提にする。

実行環境に接続された Android 実機・作成済み AVD がないため、次を成功したとは扱わない。

- Android 上の初回起動、Keystore の暗号化保存と実際のプロセス kill / 再起動。
- Nearby の権限ダイアログ、照合コード、広告・探索・無線接続。
- 実機 A→B→C、および異なる package / 署名証明書での相互通信。
- Android 端末から運用リレーへの WebSocket 接続・再接続・実 `OK` 応答。
- PLC の公開登録、PDS の受け入れ、sidecar 復元、atproto Relay / AppView 表示。

実機手順と暫定形式の変更箇所は [README](README.md) に記載。JVM 上のコーデック・署名・ホップ検証と、実機による end-to-end 検証を区別する。
