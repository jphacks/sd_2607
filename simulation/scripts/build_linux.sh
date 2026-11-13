#!/usr/bin/env bash
# Build the Linux bundles inside an Ubuntu 22.04 container so they run on ordinary distros
# (a build on the host, e.g. NixOS, would link against host-specific library paths).
#
#   mise run build-linux   ->  target/linux/release/bundle/{deb,rpm,appimage}
#
# Needs podman (or docker via CONTAINER=docker). node_modules and the cargo target live in
# container-only locations so they never mix with the host's.
set -euo pipefail
cd "$(dirname "$0")/.."

ENGINE="${CONTAINER:-podman}"
IMAGE=mosimo-simulation-linux-build

BUILD_ARGS=()
# Hosts without /etc/containers (e.g. podman from nix-shell) have no signature policy.
if [ "$ENGINE" = podman ] && [ ! -e /etc/containers/policy.json ] && [ ! -e "$HOME/.config/containers/policy.json" ]; then
  POLICY="$(mktemp)"
  trap 'rm -f "$POLICY"' EXIT
  echo '{"default":[{"type":"insecureAcceptAnything"}]}' > "$POLICY"
  BUILD_ARGS+=(--signature-policy "$POLICY")
fi

"$ENGINE" build "${BUILD_ARGS[@]}" -t "$IMAGE" -f scripts/linux/Containerfile scripts/linux
"$ENGINE" run --rm \
  -v "$PWD":/src \
  -v mosimo-sim-node-modules:/src/node_modules \
  -v mosimo-sim-cargo-registry:/opt/cargo/registry \
  -e CARGO_TARGET_DIR=/src/target/linux \
  "$IMAGE" \
  bash -c 'npm ci && npx tauri build --bundles deb,rpm,appimage'
