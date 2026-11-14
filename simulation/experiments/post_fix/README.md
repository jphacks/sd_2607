# 修正後の実験ログ

コミット `176c156` 以降のモデル（孤立判定を本部・空港・地図外から再計算、道路で孤立した地域の基地局を先に停波）で回した結果を置く。

## 主な結果

- `report.md`: 全ての実験テーブルをまとめたもの。発表用のメインドキュメント。
- `noto_adoption.json`: 能登 × スマホ導入率（0/0.1/0.3/0.5/0.7/0.9、72h、2シード）の生 JSON。
- `features/`: 機能ablation、救助隊、Starlink Direct、Starlink セル容量の5実験（experiments バイナリ生成）。
- `small/`: 避難所と川内キャンパスの導入率スイープ。

## 診断ラン（--diag）

- `diag_noto_a0.log`: 導入率0%、72h、1シード。基本的なイベント集計と孤立集落・自主避難所の把握タイミング。
- `diag_noto_a90.log`: 導入率90%、72h、1シード。比較用。

## 個別スイープ（`raw/`）

- `starlink_by.out`, `starlink_by.err`, `starlink_more.out`: Starlink の開通時刻を24h/48h/72h に前倒ししたときの結果（導入率 0/0.3/0.5/0.9）。
- `beam.out`, `beam.err`: Starlink Direct のビーム容量倍率（0.1/1/10/100 倍、--direct-data 有効、adoption 0 と 0.3）と、`diag at a=0 --direct-data` の出力。

## 生成手順

```sh
# メインの導入率スイープ
cargo run -q --release -p mosimo-sim-core --bin mosimo-sim -- noto 0 0.1 0.3 0.5 0.7 0.9 --seeds 2 --hours 72 > noto_adoption.json

# 機能・救助隊・Starlink
cargo run -q --release -p mosimo-sim-core --bin experiments -- features --only noto_features,noto_starlink_congestion,noto_direct,noto_rescue_capacity,noto_rescue_capacity_0 --noto-hours 72 --seeds 2

# 避難所・川内
cargo run -q --release -p mosimo-sim-core --bin experiments -- small --only shelter_adoption,kawauchi_students,shelter_carnav_only --seeds 3

# Starlink 開通時刻（個別実験）
cargo run -q --release -p mosimo-sim-core --bin mosimo-sim -- noto 0 --seeds 2 --hours 72 --starlink-by 24
# など、raw/*.sh を参照
```
