{ config, lib, pkgs, ... }:
let
  cfg = config.services.mosimo-importer-jma-xml;
  package = pkgs.callPackage ./package.nix { };
  stateDir = "/var/lib/mosimo-importer-jma-xml";
  # importer §1.1: the titles this importer converts; the CA and leaf attestations must contain them too.
  selection = [{
    feed = "dev.jisaku.mosimo.public.jma";
    types = [
      "気象警報・注意報（Ｒ０６）（その他注意報）" "気象警報・注意報（Ｒ０６）（土砂）" "気象警報・注意報（Ｒ０６）（大雨）"
      "気象警報・注意報（Ｒ０６）（大雪）" "気象警報・注意報（Ｒ０６）（暴風）" "気象警報・注意報（Ｒ０６）（波浪）"
      "気象警報・注意報（Ｒ０６）（高潮）" "震源・震度に関する情報"
    ];
  }];
  settings = pkgs.writeText "mosimo-importer-jma-xml.json" (builtins.toJSON {
    enabled = true;
    databasePath = "${stateDir}/importer.db";
    credentialsDirectory = "/run/credentials/mosimo-importer-jma-xml.service";
    # Interpolation (not toString) copies a Nix path into the store and keeps its string context, so the
    # file is part of the system closure on the target host.
    trustPath = "${cfg.trustFile}";
    inherit (cfg) relayUrl pollSeconds maxMessageAgeSeconds deliverySeconds;
    inherit selection;
    feeds = [ "https://www.data.jma.go.jp/developer/xml/feed/extra.xml" "https://www.data.jma.go.jp/developer/xml/feed/eqvol.xml" ];
  });
  # Importer key material (`mosimo-ca credentials`), each file passed as its own systemd credential under the name
  # importer.mts reads from the credentials directory; the same layout as the BSAF publisher.
  credentialFiles = {
    "identity.json" = cfg.identityFile;
    "chain.cbor" = cfg.chainFile;
    "nostr.pem" = cfg.nostrKeyFile;
    "atproto.pem" = cfg.atprotoKeyFile;
  };
  absolute = value: value != null && lib.hasPrefix "/" (toString value);
  runtimeSecret = value: absolute value && !(lib.hasPrefix "/nix/store" (toString value));
in {
  options.services.mosimo-importer-jma-xml = {
    enable = lib.mkEnableOption "JMA XML (PULL feeds) conversion and signed EVENT publication to PDS";
    identityFile = lib.mkOption {
      type = lib.types.nullOr (lib.types.either lib.types.path lib.types.str); default = null;
      example = lib.literalExpression "./mosimo-importer-jma-xml/identity.json";
      description = "Public identity.json ({did, nostrPubkey, atprotoPubkey}) from `mosimo-ca credentials`. A Nix path is fine.";
    };
    chainFile = lib.mkOption {
      type = lib.types.nullOr (lib.types.either lib.types.path lib.types.str); default = null;
      example = lib.literalExpression "./mosimo-importer-jma-xml/chain.cbor";
      description = "Public chain.cbor (the importer's key-attestation chain, with the jma-xml feed) from `mosimo-ca credentials`. A Nix path is fine.";
    };
    nostrKeyFile = lib.mkOption {
      type = lib.types.nullOr lib.types.str; default = null;
      example = lib.literalExpression "config.sops.secrets.mosimo_importer_jma_xml_nostr_pem.path";
      description = "Runtime path (outside /nix/store, e.g. a sops-nix secret) of nostr.pem: the importer's attested Nostr key as unencrypted secp256k1 PKCS#8. Never a Nix path.";
    };
    atprotoKeyFile = lib.mkOption {
      type = lib.types.nullOr lib.types.str; default = null;
      example = lib.literalExpression "config.sops.secrets.mosimo_importer_jma_xml_atproto_pem.path";
      description = "Runtime path (outside /nix/store, e.g. a sops-nix secret) of atproto.pem: the DID's atproto signing key as unencrypted secp256k1 PKCS#8. Never a Nix path.";
    };
    trustFile = lib.mkOption {
      type = lib.types.nullOr (lib.types.either lib.types.path lib.types.str); default = null;
      description = "Public roots.json (root pubkey, roles, maxDepth) used for the self-check before each send. A Nix path is fine.";
    };
    relayUrl = lib.mkOption { type = lib.types.str; default = ""; description = "Explicit wss PDS Nostr endpoint."; };
    pollSeconds = lib.mkOption { type = lib.types.ints.between 60 3600; default = 60; description = "PULL feed polling interval; JMA updates the high-frequency feeds once a minute."; };
    maxMessageAgeSeconds = lib.mkOption { type = lib.types.ints.between 600 604800; default = 86400; description = "Older messages (by feed time) are not converted."; };
    deliverySeconds = {
      earthquake = lib.mkOption { type = lib.types.ints.between 3600 2592000; default = 259200; description = "Delivery deadline of earthquake posts from creation."; };
      weather = lib.mkOption { type = lib.types.ints.between 3600 2592000; default = 259200; description = "Delivery deadline of warning posts from creation."; };
    };
  };
  config = lib.mkIf cfg.enable {
    assertions = [{
      assertion = absolute cfg.identityFile && absolute cfg.chainFile
        && runtimeSecret cfg.nostrKeyFile && runtimeSecret cfg.atprotoKeyFile && cfg.nostrKeyFile != cfg.atprotoKeyFile
        && absolute cfg.trustFile && lib.hasPrefix "wss://" cfg.relayUrl;
      message = "mosimo-importer-jma-xml needs identity/chain/trust files, two distinct key files outside /nix/store and a wss relay";
    }];
    systemd.services.mosimo-importer-jma-xml = {
      description = "Convert JMA XML into signed Mosimo posts and push them to PDS";
      wantedBy = [ "multi-user.target" ];
      after = [ "network-online.target" ];
      wants = [ "network-online.target" ];
      restartTriggers = [ settings package ];
      environment.IMPORTER_CONFIG_FILE = settings;
      serviceConfig = {
        Type = "simple";
        DynamicUser = true;
        StateDirectory = "mosimo-importer-jma-xml";
        # Interpolation, not toString: public files given as Nix paths must end up in the closure.
        LoadCredential = lib.mapAttrsToList (name: file: "${name}:${file}") credentialFiles;
        ExecStart = "${pkgs.nodejs_24}/bin/node --max-old-space-size=96 ${package}/lib/importer/jma-xml/importer.mts";
        Restart = "on-failure";
        RestartSec = 5;
        TimeoutStopSec = 15;
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
        MemoryHigh = "128M";
        MemoryMax = "192M";
        UMask = "0077";
      };
    };
  };
}
