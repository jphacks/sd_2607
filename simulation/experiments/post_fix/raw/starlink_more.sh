#!/bin/bash
set -eo pipefail
# Starlink-by at a=0.5 and a=0.9 to complete the adoption dimension
for h in 24 48; do
  echo "# starlink-by $h at adoption=0.5 ======"
  cargo run -q --release -p mosimo-sim-core --bin mosimo-sim -- noto 0.5 --seeds 2 --hours 72 --starlink-by $h
  echo "# starlink-by $h at adoption=0.9 ======"
  cargo run -q --release -p mosimo-sim-core --bin mosimo-sim -- noto 0.9 --seeds 2 --hours 72 --starlink-by $h
done
# diag run at a=0 with starlink-by 24 to see what shelters became known fast
echo "# diag at adoption=0 starlink-by 24 ======"
cargo run -q --release -p mosimo-sim-core --bin mosimo-sim -- noto 0 --hours 72 --starlink-by 24 --diag
