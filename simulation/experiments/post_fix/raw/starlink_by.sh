#!/bin/bash
set -eo pipefail
for h in 24 48 72 168; do
  echo "# starlink-by $h at adoption=0 ======"
  cargo run -q --release -p mosimo-sim-core --bin mosimo-sim -- noto 0 --seeds 2 --hours 72 --starlink-by $h
  echo "# starlink-by $h at adoption=0.3 ======"
  cargo run -q --release -p mosimo-sim-core --bin mosimo-sim -- noto 0.3 --seeds 2 --hours 72 --starlink-by $h
done
