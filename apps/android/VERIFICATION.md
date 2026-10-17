# Android 検証メモ

## 実行した検証

- 指定フォークの BPv7 テスト33件（Rust 由来の wire fixture、CRC、未知 block 等を含む）に成功。
- `bundle-core` の署名・MST・ホップ・v2 項目別タグ・open union・未知フィールド・旧 v1 継続25件と、時計方針・restart・age 保存境界8件、計33件に成功。
- `mesh-android` は wire / HELLO / JSON 展開 / 再送順序4件、PLC Directory のローカル HTTP 9件、標準 Nostr 購読12件（MockWebServer WebSocket 2件を含む）、restore clock 1件、オンライン通信方針3件の計29件に成功。BPv7 33件と合わせて合計95件、失敗・error・skip は0件。元ツリーで `--rerun-tasks` による新規実行を確認。
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

v2 更新後、現在の Kotlin コードから通常投稿2件・未知拡張付き投稿1件の公開 fixture を再生成し、Node 側で各タグからレコードを独立に再構成した。record/commit bytes・CID・署名・MST root・適用元 CID の一致を確認。未知拡張の bytes、raw CID link、53-bit を超える値と signed 64-bit 両端、null と省略、配列順も保持できた。Node の JSON parser では大きい整数を元の数値文字列から BigInt にしており、丸めた JavaScript number を参照値にしていない。

`bundle-core/src/test/resources/legacy-v1-post.json` は変更前に生成された公開鍵・署名等だけを含むテストデータの固定値。旧 event ID・署名・record/commit bytes の検証、メッシュ保存からの復元、旧 head を使った次の v2 投稿を確認した。旧レコードへの期限等の後付け・再署名は行わない。この旧 fixture も Node の別実装で署名・CID・MST root を照合した。

独立実装の参照値は `SignatureTest` に保持する。特に MST は0/1/2/10/64/128件で照合する。通常の Gradle テストに Node は不要。

## 今回の追加検証と境界

時計・PLC・オンライン搬出・取得の変更後、元ツリーで全テスト、両 APK、ライブラリ release AAR、app 両 variant と mesh の lint を実行した。現在の Kotlin から公開 fixture を再生成し、Node の独立署名・CID・MST・旧 v1 検証も再実行して成功した。APK 署名検証と demo の16 KiB zip alignment 検証も成功。

- 時計試験は信頼時の絶対期限、時計が2日進んだ／戻った不明時の中継、単調 age、同 boot の停止時間、経過不明時の寿命消費、満杯サイズの age checkpoint / restore を検査。OS 再起動や旧保存形式でメッシュ寿命を保守的に打ち切る方針を含む。
- PLC 試験は matching genesis、現在鍵・PDS・管理鍵の変更、明示 tombstone、混在 snapshot、HTTP / network failure、入力上限、登録許可なしでは POST しないことを検査。全履歴の暗号学的監査は実装していない。
- Nostr 試験は署名・ID・接続世代・subscription ID、EOSE 後の live、CLOSED と投稿 OK の分離、件数・byte・rate 上限、未知タグと両到着順の表示 dedup を検査。取得は揮発表示のみで、disk 保存・メッシュ再投入なし。
- Nearby 非依存オンライン通信は明示 opt-in / Activity visibility / service lifetime / fatal / overflow の方針を JVM で検査した。非表示でもサービス稼働中は許可し、両方なければ拒否する。新 API 不在の compile RED から実装後の GREEN であり、この項目の RED は Android の振る舞い失敗を再現したものではない。OS lifecycle は次の AVD 試験で別に確認した。

レビューで見つかった、JSON エスケープ後のリレー送信サイズ、先頭投稿の再送による後続の飢餓、Previous Node の持ち越し、避難所 LAN を Internet validation で拒否する問題は修正した。Previous Node は保存原本を変えず、送信用コピーから除去する。悪意ある設定先による OkHttp の受信時メモリ枯渇は未解決で、運用者が管理するリレーの使用を前提にする。

## オンライン通信の AVD 試験

Medium_Phone（API 37）の隔離した新規 userdata に demo APK をインストールし、既存データや実運用リレーを使わず、ホストの MockWebServer へ `adb reverse` で接続した。実機の無線や公開サービスの end-to-end 試験ではない。

- Nearby を開始せず、その実行時権限と通知権限を許可しない状態でも `OnlineService` が `dataSync`（通知 ID 72）で前景化することを確認。
- Home へ戻り Activity が非表示の間、未応答投稿を同じ event ID で再送し、リレー `OK true` を受け取った。署名済み fixture の新着を受信し、画面復帰後に検証済みの揮発表示を確認。
- 画面 OFF（`mWakefulness=Asleep`）中にローカルリレーから接続を切り、再接続と新しい `REQ` をサーバー側で確認。
- 通知許可後、実際の通知の「停止」を押してサービスが終了することを確認。プロセスを force-stop して再起動しても opt-out が維持され、自動開始しないこと、投稿・リレー受理状態が復元することを確認。
- `data_sync_fgs_timeout_duration` をサービス開始前に10秒へ短縮し、OS の `onTimeout` によるサービス終了、プロセス継続、画面のエラー表示を確認。画面を開くとサービスが再開し、未受理の元 event ID を再送・受理できた。試験後に OS 設定は元に戻した。
- 短縮タイムアウトは callback の検証であり、実時間6時間の連続稼働試験ではない。API 26〜36 と実機固有の省電力・Doze・通信制限・プロセス kill 下の配送は未検証。

## 未検証

次を成功したとは扱わない。

- 実機の Keystore、保存失敗時のサービス停止、OS によるプロセス回収後の配送。
- Nearby の権限ダイアログ、照合コード、広告・探索・無線接続。
- 実機 A→B→C、および異なる package / 署名証明書での相互通信。
- Android 端末から運用リレーへの WebSocket 接続・再接続・実 `OK` 応答。
- PLC の公開登録、PDS の受け入れ、sidecar 復元、atproto Relay / AppView 表示。

実機手順と暫定形式の変更箇所は [README](README.md) に記載。JVM 上のコーデック・署名・ホップ検証と、実機による end-to-end 検証を区別する。
