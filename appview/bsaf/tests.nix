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
  publishing = evaluate {
    enable = true;
    authors = [ "did:plc:vwi7zhfyamyvrg5v6ycjgzxi" ];
    publisher = {
      enable = true; selectJmaDefaults = true;
      credentialsFile = "/var/lib/mosimo-importer/credentials.json";
      trustFile = "/var/lib/mosimo-importer/roots.json";
      relayUrl = "wss://pds.invalid/";
    };
  };
  both = evaluate {
    enable = true;
    authors = [ "did:plc:vwi7zhfyamyvrg5v6ycjgzxi" "did:plc:kxwz5cz7o6g4jlmxh6doyfsm" ];
    publisher = publishing.services.mosimo-bsaf.publisher // { selectKikikuruDefaults = true; };
  };
  mixed = evaluate {
    enable = true;
    authors = both.services.mosimo-bsaf.authors;
    publisher = both.services.mosimo-bsaf.publisher // {
      selection = [{ botDid = "did:plc:kxwz5cz7o6g4jlmxh6doyfsm"; types = [ "typhoon" ]; }];
    };
  };
  publisher = publishing.systemd.services.mosimo-bsaf-publisher;
  prepare = selected.systemd.services.mosimo-bsaf-prepare;
  collector = selected.systemd.services.mosimo-bsaf;
in
assert !(disabled.systemd.services ? mosimo-bsaf-prepare);
assert !(empty.systemd.services ? mosimo-bsaf);
assert !(empty.systemd.services ? mosimo-bsaf-publisher);
assert publisher.serviceConfig.LoadCredential == [ "importer:/var/lib/mosimo-importer/credentials.json" ];
assert builtins.match ".*publisher.mts" publisher.serviceConfig.ExecStart != null;
assert publisher.serviceConfig.MemoryMax == "128M";
assert publishing.services.mosimo-bsaf.publisher.priority == null;
assert !publishing.services.mosimo-bsaf.publisher.selectKikikuruDefaults;
assert builtins.all (a: a.assertion) (builtins.filter (a: builtins.match "BSAF publisher.*" a.message != null) both.assertions);
assert !(builtins.all (a: a.assertion) (builtins.filter (a: builtins.match "BSAF publisher.*" a.message != null) mixed.assertions));
assert publishing.services.mosimo-bsaf.publisher.jmaPriorityPolicy;
assert publishing.services.mosimo-bsaf.publisher.maxOriginalAgeSeconds == 3600;
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
