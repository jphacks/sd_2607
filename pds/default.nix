{ config, lib, pkgs, ... }:
let
  inherit (lib) mkEnableOption mkIf mkOption types;
  cfg = config.services.mosimo-pds;
  json = pkgs.formats.json { };
  https = types.strMatching "https://[^[:space:]@?#]+";
  configFile = json.generate "mosimo-pds-config.json" cfg.settings;
in
{
  options.services.mosimo-pds = {
    enable = mkEnableOption "Mosimo PDS";
    image = mkOption {
      type = types.nullOr types.nonEmptyStr;
      default = null;
      description = "Optional prebuilt OCI image reference; null runs the native Node.js package. Use an immutable digest in production. No image is built by this module.";
    };
    port = mkOption {
      type = types.port;
      default = 2583;
      description = "Host loopback port; forwarded to container port 2583 in OCI mode.";
    };
    databaseBackend = mkOption {
      type = types.enum [ "sqlite" "postgresql" ];
      default = "sqlite";
      description = "SQLite needs no database process. PostgreSQL requires DATABASE_URL in environmentFile.";
    };
    environmentFile = mkOption {
      type = types.nullOr types.str;
      default = null;
      description = "Optional absolute runtime env-file path outside the Nix store. Required for PostgreSQL (DATABASE_URL); configure peer tokens here when peers are enabled.";
    };
    settings = mkOption {
      default = { };
      description = "Public application configuration. Never put secrets here: the generated JSON is in the Nix store.";
      type = types.submodule {
        options = {
          groups = mkOption {
            default = [ ];
            type = types.listOf (types.submodule {
              options = {
                id = mkOption { type = types.nonEmptyStr; };
                endpoint = mkOption {
                  type = https;
                  description = "Canonical HTTPS endpoint for this logical group.";
                };

              };
            });
          };
          relays = mkOption {
            type = types.listOf (types.strMatching "wss://[^[:space:]@#]+");
            default = [ ];
          };
          peers = mkOption {
            default = [ ];
            type = types.listOf (types.submodule {
              options = {
                url = mkOption {
                  type = https;
                  description = "Peer HTTPS base URL, not an individual API path.";
                };
                tokenEnv = mkOption {
                  type = types.strMatching "[A-Za-z_][A-Za-z0-9_]*";
                  default = "PEER_TOKEN";
                  description = "Environment variable containing the outbound Bearer token, never the token itself.";
                };
              };
            });
          };
          publicInformation = mkOption {
            type = json.type;
            default = { roots = [ ]; selection = [ ]; };
            description = "Public wire-v1 trust roots and Bot/type selection; empty grants no priority authority. Validated at startup. Never include private credentials.";
          };
          plcUrl = mkOption { type = https; default = "https://plc.directory"; };
          maxEventBytes = mkOption { type = types.ints.positive; default = 131072; };
          maxSubscriptions = mkOption { type = types.ints.positive; default = 20; };
          maxResults = mkOption { type = types.ints.positive; default = 500; };
          maxBufferedBytes = mkOption { type = types.ints.positive; default = 4194304; };
          maxEvents = mkOption { type = types.ints.positive; default = 1000000; };
        };
      };
    };
  };

  config = mkIf cfg.enable {
    assertions = [
      {
        assertion = cfg.databaseBackend != "postgresql" || cfg.environmentFile != null;
        message = "services.mosimo-pds.environmentFile is required for PostgreSQL (DATABASE_URL).";
      }
      {
        assertion = cfg.environmentFile == null || (lib.hasPrefix "/" cfg.environmentFile
          && !(lib.hasPrefix "/nix/store/" cfg.environmentFile));
        message = "services.mosimo-pds.environmentFile must be an absolute runtime path outside /nix/store.";
      }
      {
        assertion = cfg.settings.groups != [ ];
        message = "services.mosimo-pds.settings.groups must contain at least one logical group.";
      }
      {
        assertion = let ids = map (group: group.id) cfg.settings.groups;
          in builtins.length ids == builtins.length (lib.unique ids);
        message = "services.mosimo-pds.settings.groups IDs must be unique.";
      }
    ];

    systemd.services.mosimo-pds = mkIf (cfg.image == null) {
      description = "Mosimo PDS";
      wantedBy = [ "multi-user.target" ];
      wants = [ "network-online.target" ];
      after = [ "network-online.target" ];
      environment = {
        NODE_ENV = "production";
        HOST = "127.0.0.1";
        PORT = toString cfg.port;
        CONFIG_FILE = toString configFile;
      } // lib.optionalAttrs (cfg.databaseBackend == "sqlite") {
        DATABASE_PATH = "/var/lib/mosimo-pds/pds.sqlite";
      };
      serviceConfig = {
        ExecStart = "${pkgs.nodejs_24}/bin/node --max-old-space-size=256 ${pkgs.callPackage ./package.nix { }}/lib/mosimo-pds/main.mjs";
        EnvironmentFile = lib.optional (cfg.environmentFile != null) cfg.environmentFile;
        DynamicUser = true;
        StateDirectory = "mosimo-pds";
        StateDirectoryMode = "0700";
        UMask = "0077";
        MemoryHigh = "384M";
        MemoryMax = "512M";
        Restart = "on-failure";
        RestartSec = 5;
        NoNewPrivileges = true;
        ProtectSystem = "strict";
        ProtectHome = true;
        PrivateTmp = true;
      };
    };

    virtualisation.oci-containers = mkIf (cfg.image != null) {
      backend = "podman";
      containers.mosimo-pds = {
        image = cfg.image;
        user = "node";
        cmd = [ "node" "--max-old-space-size=256" "main.mjs" ];
        environment = {
          NODE_ENV = "production";
          HOST = "0.0.0.0";
          PORT = "2583";
          CONFIG_FILE = "/etc/mosimo/config.json";
        } // lib.optionalAttrs (cfg.databaseBackend == "sqlite") {
          DATABASE_PATH = "/var/lib/mosimo-pds/pds.sqlite";
        };
        environmentFiles = lib.optional (cfg.environmentFile != null) cfg.environmentFile;
        ports = [ "127.0.0.1:${toString cfg.port}:2583" ];
        volumes = [ "${configFile}:/etc/mosimo/config.json:ro" ]
          ++ lib.optional (cfg.databaseBackend == "sqlite") "mosimo-pds:/var/lib/mosimo-pds:U";
        extraOptions = [
          "--cap-drop=ALL" "--security-opt=no-new-privileges"
          "--memory-reservation=384m" "--memory=512m"
        ];
      };
    };
  };
}
