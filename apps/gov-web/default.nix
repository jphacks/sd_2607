{ config, lib, pkgs, ... }:
let
  cfg = config.services.mosimo-gov-web;
  root = pkgs.callPackage ./package.nix {
    inherit (cfg) appviewUrl clientKey;
  };
in
{
  options.services.mosimo-gov-web = {
    enable = lib.mkEnableOption "Mosimo post review dashboard served by Caddy";
    domain = lib.mkOption {
      type = lib.types.strMatching "[A-Za-z0-9.-]+";
      description = "Public hostname for the dashboard; Caddy manages HTTPS certificates.";
    };
    appviewUrl = lib.mkOption {
      type = lib.types.strMatching "https?://[^/?#[:space:]]+/?";
      description = "Public AppView origin, without a subpath. Embedded at build time.";
      example = "https://appview.example.org";
    };
    clientKey = lib.mkOption {
      type = lib.types.nullOr lib.types.nonEmptyStr;
      default = null;
      description = "Public HappyView client key embedded at build time; null queries without a key.";
      example = "hvc_0123456789abcdef";
    };
  };

  config = lib.mkIf cfg.enable {
    services.caddy = {
      enable = true;
      virtualHosts.${cfg.domain}.extraConfig = ''
        root * ${root}
        encode zstd gzip
        # Vite fingerprints everything under assets/.
        header /assets/* Cache-Control "public, max-age=31536000, immutable"
        file_server
      '';
    };
  };
}
