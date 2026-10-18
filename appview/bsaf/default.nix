{ config, lib, pkgs, ... }:
let
  cfg = config.services.mosimo-bsaf;
  package = pkgs.callPackage ./package.nix { };
  settings = pkgs.writeText "mosimo-bsaf.json" (builtins.toJSON {
    inherit (cfg) databasePath authors jetstreamUrl;
  });
  runtime = "${package}/lib/mosimo-bsaf";
  hardening = {
    User = "root"; # The existing rootful HappyView volume is root-owned.
    NoNewPrivileges = true;
    ProtectSystem = "strict";
    ProtectHome = true;
    PrivateTmp = true;
    PrivateDevices = true;
    ProtectKernelTunables = true;
    ProtectKernelModules = true;
    ProtectControlGroups = true;
    RestrictSUIDSGID = true;
    CapabilityBoundingSet = [ ];
    ReadWritePaths = [ (builtins.dirOf cfg.databasePath) ];
    MemoryHigh = "96M";
    MemoryMax = "128M";
    UMask = "0077";
  };
in {
  options.services.mosimo-bsaf = {
    enable = lib.mkEnableOption "DID-allowlisted BSAF collection into HappyView SQLite";
    authors = lib.mkOption {
      type = lib.types.listOf (lib.types.strMatching "did:[a-z]+:[A-Za-z0-9._:%-]+");
      default = [ ];
      description = "Explicitly approved original Bot DIDs. Empty means no BSAF subscription; never enables retransmission.";
    };
    databasePath = lib.mkOption {
      type = lib.types.strMatching "/.*";
      default = "/var/lib/containers/storage/volumes/happyview-data/_data/happyview.db";
      description = "Existing local HappyView 2.16 SQLite database; not a new database or network filesystem.";
    };
    jetstreamUrl = lib.mkOption {
      type = lib.types.strMatching "wss://[^[:space:]?#]+";
      default = "wss://jetstream1.us-east.bsky.network/subscribe";
      description = "Trusted Jetstream subscription endpoint. Author and collection filters are added by the collector.";
    };
  };
  config = lib.mkIf cfg.enable {
    assertions = [{
      assertion = builtins.length cfg.authors <= 10000 && lib.unique cfg.authors == cfg.authors;
      message = "mosimo-bsaf.authors must contain at most 10000 distinct DIDs";
    }];
    systemd.services.mosimo-bsaf-prepare = {
      description = "Prepare approved BSAF sources and combined HappyView feed";
      wantedBy = [ "multi-user.target" "podman-happyview.service" ];
      partOf = [ "podman-happyview.service" ];
      requires = [ "podman-happyview.service" ];
      after = [ "podman-happyview.service" ];
      before = [ "mosimo-bsaf.service" ];
      restartTriggers = [ settings package ];
      environment.CONFIG_FILE = settings;
      serviceConfig = hardening // {
        Type = "oneshot";
        RemainAfterExit = true;
        ExecStart = "${pkgs.nodejs_24}/bin/node --max-old-space-size=64 ${runtime}/prepare.mts";
      };
    };
    systemd.services.mosimo-bsaf = lib.mkIf (cfg.authors != [ ]) {
      description = "Collect BSAF originals from explicitly approved DIDs";
      wantedBy = [ "mosimo-bsaf-prepare.service" ];
      partOf = [ "podman-happyview.service" ];
      requires = [ "mosimo-bsaf-prepare.service" ];
      after = [ "mosimo-bsaf-prepare.service" "network-online.target" ];
      wants = [ "network-online.target" ];
      restartTriggers = [ settings ];
      environment.CONFIG_FILE = settings;
      serviceConfig = hardening // {
        Type = "simple";
        ExecStart = "${pkgs.nodejs_24}/bin/node --max-old-space-size=64 ${runtime}/collector.mts";
        Restart = "on-failure";
        RestartSec = 5;
        TimeoutStopSec = 15;
      };
    };
  };
}
