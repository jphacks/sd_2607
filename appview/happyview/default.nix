# Import this directory, then set services.happyview.enable, publicUrl and
# environmentFile. The runtime file must contain SESSION_SECRET and
# TOKEN_ENCRYPTION_KEY; never put their values in Nix expressions.
{ config, lib, ... }:
let
  cfg = config.services.happyview;
  image = "localhost/mndt-happyview:${builtins.hashFile "sha256" ./Containerfile}";
in
{
  options.services.happyview = {
    enable = lib.mkEnableOption "HappyView v2 AppView";
    publicUrl = lib.mkOption {
      type = lib.types.strMatching "https?://[^/?#[:space:]]+/?";
      description = "Public origin used by OAuth, without a subpath.";
      example = "https://appview.example.org";
    };
    environmentFile = lib.mkOption {
      type = lib.types.strMatching "/.*";
      description = "Absolute runtime path to the host-managed secrets file (not a Nix path literal).";
      example = "/run/secrets/happyview.env";
    };
    listenAddress = lib.mkOption {
      type = lib.types.nonEmptyStr;
      default = "127.0.0.1";
      description = "Host address to publish TCP/3000 on; enclose IPv6 addresses in brackets.";
    };
  };

  config = lib.mkIf cfg.enable {
    virtualisation.oci-containers = {
      backend = "podman";
      containers.happyview = {
        inherit image;
        pull = "never";
        environment.PUBLIC_URL = cfg.publicUrl;
        environmentFiles = [ cfg.environmentFile ];
        ports = [ "${cfg.listenAddress}:3000:3000" ];
        volumes = [ "happyview-data:/data" ];
        extraOptions = [ "--init" ];
      };
    };

    # Build before each start; Podman's cache reuses the pinned upstream image.
    systemd.services.podman-happyview.preStart = ''
      ${config.virtualisation.podman.package}/bin/podman build \
        --tag ${lib.escapeShellArg image} \
        --file ${./Containerfile} ${./.}
    '';
  };
}
