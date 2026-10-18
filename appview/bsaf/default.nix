{ config, lib, pkgs, ... }:
let
  cfg = config.services.mosimo-bsaf;
  package = pkgs.callPackage ./package.nix { };
  settings = pkgs.writeText "mosimo-bsaf.json" (builtins.toJSON {
    inherit (cfg) databasePath authors jetstreamUrl;
  });
  runtime = "${package}/lib/appview/bsaf";
  jmaSelection = [{
    botDid = "did:plc:vwi7zhfyamyvrg5v6ycjgzxi";
    types = [ "ashfall" "earthquake" "eruption" "nankai-trough" "tsunami" ];
  }];
  kikikuruSelection = [{
    botDid = "did:plc:kxwz5cz7o6g4jlmxh6doyfsm";
    types = [
      "avalanche-warning" "dense-fog-warning" "dry-air-warning" "flood-warning"
      "frost-warning" "heavy-rain" "heavy-rain-warning" "icing-warning" "landslide-warning"
      "linear-rainband-warning" "low-temperature-warning" "melting-snow-warning" "other-warning"
      "significant-snowfall-warning" "snow-accretion-warning" "snow-warning" "storm-surge-warning"
      "thunderstorm-warning" "tornado-warning" "typhoon" "wave-warning" "weather-info" "wind-warning"
    ];
  }];
  selection = (if cfg.publisher.selectJmaDefaults then jmaSelection else [ ])
    ++ (if cfg.publisher.selectKikikuruDefaults then kikikuruSelection else [ ])
    ++ cfg.publisher.selection;
  publisherSettings = pkgs.writeText "mosimo-bsaf-publisher.json" (builtins.toJSON {
    enabled = cfg.publisher.enable;
    inherit (cfg) databasePath;
    credentialsPath = "/run/credentials/mosimo-bsaf-publisher.service/importer";
    trustPath = cfg.publisher.trustFile;
    inherit (cfg.publisher) relayUrl maxOriginalAgeSeconds priority jmaPriorityPolicy;
    inherit selection;
  });
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
    publisher = {
      enable = lib.mkEnableOption "opt-in signed BSAF conversion and EVENT publication to PDS";
      credentialsFile = lib.mkOption {
        type = lib.types.nullOr lib.types.str; default = null;
        description = "Absolute importer-only JSON secret path, loaded using systemd credentials; never a Nix path/root key.";
      };
      trustFile = lib.mkOption {
        type = lib.types.nullOr lib.types.str; default = null;
        description = "Absolute public roots JSON path; no trust anchor discovered from the relay.";
      };
      relayUrl = lib.mkOption { type = lib.types.str; default = ""; description = "Explicit wss PDS Nostr endpoint; no default posting destination."; };
      selectJmaDefaults = lib.mkOption {
        type = lib.types.bool; default = false;
        description = "Explicitly select only did:plc:vwi7zhfyamyvrg5v6ycjgzxi and ashfall/earthquake/eruption/nankai-trough/tsunami; never auto-expand.";
      };
      selectKikikuruDefaults = lib.mkOption {
        type = lib.types.bool; default = false;
        description = "Explicitly select only did:plc:kxwz5cz7o6g4jlmxh6doyfsm and its fixed 23 weather types; never auto-expand. May combine with JMA, not custom selection.";
      };
      selection = lib.mkOption {
        type = lib.types.listOf (lib.types.submodule { options = {
          botDid = lib.mkOption { type = lib.types.strMatching "did:plc:[a-z2-7]{24}"; };
          types = lib.mkOption { type = lib.types.listOf lib.types.str; };
        }; });
        default = [ ]; description = "Explicit Bot/type pairs. Empty means no publication. Cannot combine with either fixed preset; the two presets may combine with each other.";
      };
      maxOriginalAgeSeconds = lib.mkOption { type = lib.types.ints.between 1 86400; default = 3600; description = "Fixed deadline = original createdAt + this value, never retry/collection time."; };
      jmaPriorityPolicy = lib.mkOption {
        type = lib.types.bool; default = true;
        description = "Use the pinned exact JMA Bot/type/value local priority policy only for explicitly selected pairs; no institution authority or relevance guarantee.";
      };
      priority = lib.mkOption {
        type = lib.types.nullOr (lib.types.ints.between 0 3); default = null;
        description = "Optional explicit signed-priority override, including 0/1. Null uses the selected JMA policy or falls back to 1; receivers still independently authorize elevation.";
      };
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
    } {
      assertion = !cfg.publisher.enable || (
        cfg.publisher.credentialsFile != null && lib.hasPrefix "/" cfg.publisher.credentialsFile
        && cfg.publisher.trustFile != null && lib.hasPrefix "/" cfg.publisher.trustFile
        && lib.hasPrefix "wss://" cfg.publisher.relayUrl
        && !((cfg.publisher.selectJmaDefaults || cfg.publisher.selectKikikuruDefaults) && cfg.publisher.selection != [ ])
        && builtins.all (e: builtins.elem e.botDid cfg.authors) selection);
      message = "BSAF publisher needs absolute importer credentials/public trust files, wss relay, and explicitly collected source DIDs; select one selection mode";
    }];
    systemd.services.mosimo-bsaf-publisher = lib.mkIf cfg.publisher.enable {
      description = "Sign selected collected BSAF records and push durable EVENT outbox to PDS";
      wantedBy = [ "multi-user.target" ];
      requires = [ "mosimo-bsaf-prepare.service" ];
      after = [ "mosimo-bsaf-prepare.service" "network-online.target" ];
      wants = [ "network-online.target" ];
      restartTriggers = [ publisherSettings package ];
      environment.PUBLISHER_CONFIG_FILE = publisherSettings;
      serviceConfig = hardening // {
        Type = "simple";
        LoadCredential = [ "importer:${cfg.publisher.credentialsFile}" ];
        ExecStart = "${pkgs.nodejs_24}/bin/node --max-old-space-size=64 ${runtime}/publisher.mts";
        Restart = "on-failure";
        RestartSec = 5;
        TimeoutStopSec = 15;
      };
    };
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
