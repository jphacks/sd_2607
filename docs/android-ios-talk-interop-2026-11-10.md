# Mosimo 防災（iOS・Android）とモシモトーク（Android）の相互中継検証（2026-11-10）

## 結果

**成功。** iOS 版 Mosimo 防災・Android 版 Mosimo 防災・Android 版モシモトークは、BLE と LAN で相互に Bundle を中継できた。要求された5経路と、トーク→防災→トーク（iOS 防災経由・Android 防災経由の両方）を実機で確認した。

リレーへの搬出は [0022 §4](../protocol/0022-direct-messages.md) のとおり。オンラインの防災ゲートウェイは、トークの kind 4（DM・配達通知）と kind 0（表示名）を一般の Nostr リレーへ搬出しうる（PDS へは kind 1・5 のみ）。2026-11-08 のトークの表示名 `Alice`・`Bob`（kind 0）が `wss://relay.damus.io` にあるのはこの仕様どおりの動作。

## 端末と分離

- Android: OPPO CPH2797。Play 版 0.2.2（versionCode 29、当日 22:27 導入）を削除し、作業ツリーの debug ビルド（防災 `dev.jisaku.mosimo`、トーク `dev.jisaku.hanashi` と第2識別 `dev.jisaku.hanashi.b`）を導入。開始時は Wi-Fi・モバイルデータとも OFF（ネットワークなし）。
- iPhone 13 mini、iPad Air 11-inch M3: 作業ツリーの DEBUG ビルド。
- 経路の強制: DEBUG ラボ設定（`mosimo.test.node`・`peer.node`）で各 iOS 端末が許可する隣接ノードを1つに絞った。HELLO で隔離されるため、直接リンクは成立しない。
- リレー: 一般リレーの代わりに Mac 上のローカル中継（全 EVENT を受理・記録）を使用。iOS は `ws://<Mac>:7447`、Android は `adb reverse` 経由の `ws://127.0.0.1:7447`。試験投稿を公開リレーへ出さないため、本番の Nostr 確認は `wss://pds.oriom.dev` のみ。

## 経路（Mosimo 防災の投稿、kind 1）

| 経路 | event | 受信側の観測 hop |
|---|---|---|
| iPhone → Android（防災）→ iPad | `be22e31b` | 2 |
| iPad → Android（防災）→ iPhone | `d550890c` | 2 |
| iPad → iPhone → Android（防災） | `05abc695` | 2（Android は iPhone から受信） |
| iPhone → Android（トーク）→ iPad | `bc073b94` | 2 |
| iPad → Android（トーク）→ iPhone | `2ade006f` | 2 |

いずれも BLE（Android は Wi-Fi OFF）で、受信から次ホップへの転送は1秒未満。トークはこれらを中継するが表示しない（受信対象は自分宛ての kind 4 と kind 0 のみ）。トークはオンライン搬出しない（ローカル中継に Android からの EVENT なし）。

## トーク → 防災 → トーク

Android 1台のため、トーク A とトーク B を同じ端末で**交互に**起動した（同時には動かさない）。両者の間で運べるのは防災ノードだけになる。

1. **iOS 防災経由（BLE）**: B→iPhone→A に DM `d13a44e4`、A の配達通知 `6152fb35` と返信 `5e14541b` が iPhone→B。B 側で「✓✓ 配達」、返信を受信。表示名（kind 0）も iPhone が運び、A・B は直接会わずに相手を連絡先に得た。
2. **Android 防災経由（同一端末の LAN、Wi-Fi を一時 ON）**: B→防災→A に DM `44f538ef`、配達通知 `82fd0758` と返信 `ccff439d` が防災→B。「✓✓ 配達」を確認。

## 表示・搬出の分離

- Android 防災のタイムラインは kind 1 だけを表示し、保持している DM・配達通知・表示名は出ない（S2・S4 はトーク B の保存分から届き、S4 は 4 hop）。iOS は同じ `kind == 1` 絞り込みのコードで、画面は目視確認待ち。
- `wss://pds.oriom.dev` に S1〜S5 の5件が届いた（Wi-Fi ON 時の Android 防災が搬出）。トークの8件（DM・通知・表示名）は pds.oriom.dev にない（PDS は kind 1・5 のみ）。
- 試験中は一時的に「kind 1・5 以外は一般リレーへも搬出しない」改変版で動かしたため、ローカル中継が受けた EVENT は kind 1 のみだった。この改変は 0022 §4 と矛盾するため取り消した。現行コードでは一般リレーへ kind 4・0 も搬出される。

## 修正

- 「メッシュ経由で更新」表示（`lastReceivedAt`）を kind 1 の受信だけで更新（DM 中継で投稿がないのに更新表示が出ていた）。Android `MeshCoordinator.kt`・iOS `MeshCoordinator.swift`。
- Android `GattLink` が Bluetooth の状態変化に追従しなかった。Bluetooth OFF のまま起動する、または起動中に OFF→ON すると、アプリを再起動するまで BLE の広告・スキャン・GATT サーバーが消えたままで、iOS から見えなくなっていた（試験後の利用者の操作で発生）。`ACTION_STATE_CHANGED` を受けて停止・再開するようにした。実機で、OFF→ON 後に同じプロセスのまま再広告し iOS 2台が再接続すること、Bluetooth OFF で起動して ON にすると接続できることを確認した。トークも同じ `GattLink` を使う。

## 未解決・注意

- **同一端末で防災とトークを同時に動かすと、iOS は片方としか通信しない**（0022 §6 の未決事項）。防災の起動中にトークを起動すると、端末の GATT データベースに同じ Service UUID が2つ並ぶ。iOS 2台はサービス変更で一度切断し、再接続先はどちらも防災で、トークには45秒間 HELLO が来なかった。アプリの起動・終了のたびに iOS のリンクが全部切れる。Android 1台のデモでは BLE で使うアプリを1つずつ起動する。同一端末の防災↔トークは LAN（Wi-Fi）でつながる。
- ラボ隔離中の iOS は、Service Data を読めなかった Android 広告へノード不明のまま接続し、HELLO で切断した直後に再接続を繰り返す（接続済みの切断にバックオフがない）。ラボ設定でのみ起き、通常構成には影響しないが、隣接リンクの確立を遅らせた。
- 新しい開発者証明書のビルドは、iPhone で開発元の信頼操作が必要だった。
