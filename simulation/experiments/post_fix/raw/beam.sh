#!/bin/bash
set -eo pipefail
# adoption=0: pure phone/satellite baseline, SD can help, baseline beam 
for mult in 0.1 1 10 100; do
  echo "# beam=$mult at adoption=0 direct-data ======"
  cargo run -q --release -p mosimo-sim-core --bin mosimo-sim -- noto 0 --seeds 2 --hours 72 --direct-data --beam $mult
done
# adoption=0.3 with SD data: how much more demand does SD see in modified model
echo "# beam=1 at adoption=0.3 direct-data (baseline) ======"
cargo run -q --release -p mosimo-sim-core --bin mosimo-sim -- noto 0.3 --seeds 2 --hours 72 --direct-data
# Diag run at adoption=0 with direct-data to see total SD traffic in modified model
echo "# diag at adoption=0 direct-data ======"
cargo run -q --release -p mosimo-sim-core --bin mosimo-sim -- noto 0 --hours 72 --direct-data --diag
