{ lib, buildNpmPackage, nodejs_24 }:
buildNpmPackage {
  pname = "mosimo-bsaf";
  version = "0.1.0";
  nodejs = nodejs_24;
  src = lib.cleanSourceWith {
    src = ./.;
    filter = path: type:
      let name = baseNameOf path;
      in path == toString ./. || (type == "regular" && (
        name == "package.json" || name == "package-lock.json" || name == "feed.lua"
        || (lib.hasSuffix ".mts" name && !(lib.hasInfix ".test." name))
      ));
  };
  npmDepsHash = "sha256-71KTSgLbWBEdZhhHw/GksDGf/hbk0jGwoX1Or+RSink=";
  npmFlags = [ "--omit=dev" ];
  dontNpmBuild = true;
  installPhase = ''
    runHook preInstall
    mkdir -p $out/lib/mosimo-bsaf
    cp *.mts feed.lua package.json $out/lib/mosimo-bsaf/
    cp -r node_modules $out/lib/mosimo-bsaf/
    runHook postInstall
  '';
  meta = {
    description = "DID-allowlisted BSAF ingestion for HappyView SQLite";
    license = lib.licenses.mit;
    platforms = lib.platforms.linux;
  };
}
