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
    enable = mkEnableOption "Mosimo PDS (external PostgreSQL required)";
    image = mkOption {
      type = types.nonEmptyStr;
      description = "Prebuilt OCI image reference; use a published immutable digest in production. No image is built by this module.";
    };
    port = mkOption {
      type = types.port;
      default = 2583;
      description = "Host loopback port, forwarded to container port 2583.";
    };
    environmentFile = mkOption {
      type = types.str;
      description = "Absolute runtime env-file path outside the Nix store. DATABASE_URL is required; PEER_TOKEN is required when peers are configured.";
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
        assertion = lib.hasPrefix "/" cfg.environmentFile
          && !(lib.hasPrefix "/nix/store/" cfg.environmentFile);
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

    virtualisation.oci-containers = {
      backend = "podman";
      containers.mosimo-pds = {
        image = cfg.image;
        user = "node";
        cmd = [ "node" "main.mjs" ];
        environment = {
          NODE_ENV = "production";
          PORT = "2583";
          CONFIG_FILE = "/etc/mosimo/config.json";
        };
        environmentFiles = [ cfg.environmentFile ];
        ports = [ "127.0.0.1:${toString cfg.port}:2583" ];
        volumes = [ "${configFile}:/etc/mosimo/config.json:ro" ];
        extraOptions = [ "--cap-drop=ALL" "--security-opt=no-new-privileges" ];
      };
    };
  };
}
