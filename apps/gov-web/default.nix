{ config, lib, pkgs, ... }:
let
  cfg = config.services.mosimo-gov-web;
  root = pkgs.callPackage ./package.nix {
    inherit (cfg) appviewUrl clientKey mapBounds relayUrl;
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
      description = "Public HappyView client key embedded at build time; null queries without a key. HappyView rate-limits per key, shared by every viewer, and not keyless queries.";
      example = "hvc_0123456789abcdef";
    };
    mapBounds = lib.mkOption {
      # Same rule as MapView.tsx; the regex keeps every field JSON-parsable for the range check.
      type = lib.types.nullOr (lib.types.addCheck (lib.types.strMatching "(-?(0|[1-9][0-9]*)(\\.[0-9]+)?,){3}-?(0|[1-9][0-9]*)(\\.[0-9]+)?") (s:
        let n = builtins.elemAt (builtins.fromJSON "[${s}]"); in
        -90 <= n 0 && n 0 < n 2 && n 2 <= 90 && -180 <= n 1 && n 1 < n 3 && n 3 <= 180) // {
        description = "\"south,west,north,east\" in degrees with south < north within ±90 and west < east within ±180";
      });
      default = null;
      description = "Initial map bounds as \"south,west,north,east\" in degrees, embedded at build time; null shows all of Japan.";
      example = "38.18,140.78,38.33,141.02";
    };
    relayUrl = lib.mkOption {
      type = lib.types.nullOr (lib.types.strMatching "wss?://[^?#[:space:]]+");
      default = null;
      description = "Mosimo Nostr relay used to map annotation targets given as Nostr event IDs to posts. Embedded at build time; null uses the app default (wss://pds.oriom.dev).";
      example = "wss://pds.example.org";
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
