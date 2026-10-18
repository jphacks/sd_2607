# nix-instantiate --eval --strict tests.nix --arg nixpkgs /path/to/nixpkgs
{ nixpkgs }:
let
  evaluate = settings: (import (nixpkgs + "/nixos/lib/eval-config.nix") {
    system = "x86_64-linux";
    modules = [
      ./default.nix
      {
        system.stateVersion = "26.05";
        services.happyview = settings;
      }
    ];
  }).config;
  disabled = evaluate { };
  enabled = evaluate {
    enable = true;
    publicUrl = "https://appview.example.org";
    environmentFile = "/run/secrets/happyview.env";
  };
  remote = evaluate {
    enable = true;
    publicUrl = "https://appview.example.org";
    environmentFile = "/run/secrets/happyview.env";
    listenAddress = "100.64.0.2";
  };
  container = enabled.virtualisation.oci-containers.containers.happyview;
in
assert !(disabled.virtualisation.oci-containers.containers ? happyview);
assert enabled.virtualisation.oci-containers.backend == "podman";
assert container.ports == [ "127.0.0.1:3000:3000" ];
assert container.volumes == [ "happyview-data:/data" ];
assert container.environment.PUBLIC_URL == "https://appview.example.org";
assert container.environmentFiles == [ "/run/secrets/happyview.env" ];
assert !(container.environment ? SESSION_SECRET);
assert !(container.environment ? TOKEN_ENCRYPTION_KEY);
assert container.pull == "never";
assert container.extraOptions == [ "--init" ];
assert remote.virtualisation.oci-containers.containers.happyview.ports == [ "100.64.0.2:3000:3000" ];
assert builtins.match ".*podman build.*" enabled.systemd.services.podman-happyview.preStart != null;
"HappyView module checks passed"
