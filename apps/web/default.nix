{ config, lib, pkgs, ... }:
let
  cfg = config.services.mosimo-web;
  index = pkgs.writeTextDir "index.html" (builtins.readFile ./index.html);
  webConfig = pkgs.writeTextDir "config.js" ''
    globalThis.MOSIMO_CONFIG = ${builtins.toJSON {
      inherit (cfg) appviewUrl clientKey;
    }};
  '';
  root = pkgs.symlinkJoin {
    name = "mosimo-web";
    paths = [ index webConfig ];
  };
in
{
  options.services.mosimo-web = {
    enable = lib.mkEnableOption "Mosimo web timeline served by Caddy";
    domain = lib.mkOption {
      type = lib.types.strMatching "[A-Za-z0-9.-]+";
      description = "Public hostname for the timeline; Caddy manages HTTPS certificates.";
    };
    appviewUrl = lib.mkOption {
      type = lib.types.strMatching "https?://[^/?#[:space:]]+/?";
      description = "Default public AppView origin, without a subpath.";
      example = "https://appview.example.org";
    };
    clientKey = lib.mkOption {
      type = lib.types.nonEmptyStr;
      description = "Public HappyView client key used by the timeline.";
      example = "hvc_0123456789abcdef";
    };
  };

  config = lib.mkIf cfg.enable {
    services.caddy = {
      enable = true;
      virtualHosts.${cfg.domain}.extraConfig = ''
        root * ${root}
        file_server
      '';
    };
  };
}
