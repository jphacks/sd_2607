# nix-instantiate --eval --strict tests.nix --arg nixpkgs /path/to/nixpkgs
{ nixpkgs }:
let
  evaluate = settings: (import (nixpkgs + "/nixos/lib/eval-config.nix") {
    system = "x86_64-linux";
    modules = [ ./default.nix { system.stateVersion = "26.05"; services.mosimo-bsaf = settings; } ];
  }).config;
  disabled = evaluate { };
  empty = evaluate { enable = true; };
  selected = evaluate { enable = true; authors = [ "did:plc:example" ]; };
  prepare = selected.systemd.services.mosimo-bsaf-prepare;
  collector = selected.systemd.services.mosimo-bsaf;
in
assert !(disabled.systemd.services ? mosimo-bsaf-prepare);
assert !(empty.systemd.services ? mosimo-bsaf);
assert empty.systemd.services.mosimo-bsaf-prepare.serviceConfig.Type == "oneshot";
assert builtins.elem "podman-happyview.service" prepare.wantedBy;
assert prepare.partOf == [ "podman-happyview.service" ];
assert collector.partOf == [ "podman-happyview.service" ];
assert collector.wantedBy == [ "mosimo-bsaf-prepare.service" ];
assert collector.after == [ "mosimo-bsaf-prepare.service" "network-online.target" ];
assert collector.serviceConfig.MemoryMax == "128M";
assert collector.serviceConfig.MemoryHigh == "96M";
assert collector.serviceConfig.ReadWritePaths == [ "/var/lib/containers/storage/volumes/happyview-data/_data" ];
assert builtins.match ".*collector.mts" collector.serviceConfig.ExecStart != null;
"BSAF module checks passed"
