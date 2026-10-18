{ config, lib, pkgs, ... }:
let
  cfg = config.services.mosimo-web;
  root = pkgs.writeTextDir "index.html" (builtins.readFile ./index.html);
in
{
  options.services.mosimo-web = {
    enable = lib.mkEnableOption "Mosimo web timeline served by Caddy";
    domain = lib.mkOption {
      type = lib.types.strMatching "[A-Za-z0-9.-]+";
      description = "Public hostname for the timeline; Caddy manages HTTPS certificates.";
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
