{ lib, buildNpmPackage, nodejs_24, callPackage }:
let
  # Reuse the locked PDS crypto/repository dependencies without duplicating a lockfile.
  pdsRuntime = callPackage ../../pds/package.nix { };
in
buildNpmPackage {
  pname = "mosimo-importer-jma-xml";
  version = "0.1.0";
  nodejs = nodejs_24;
  src = lib.cleanSourceWith {
    src = ./.;
    filter = path: type:
      let name = baseNameOf path;
      in path == toString ./. || (type == "regular" && (
        name == "package.json" || name == "package-lock.json" || name == "areas.json"
        || (lib.hasSuffix ".mts" name && !(lib.hasInfix ".test." name))
      ));
  };
  # Not yet computed (no Nix on the authoring machine): the first build prints the value to put here.
  npmDepsHash = lib.fakeHash;
  npmFlags = [ "--omit=dev" ];
  dontNpmBuild = true;
  installPhase = ''
    runHook preInstall
    mkdir -p $out/lib/importer/jma-xml
    cp *.mts areas.json package.json $out/lib/importer/jma-xml/
    cp -r node_modules $out/lib/importer/jma-xml/
    ln -s ${pdsRuntime}/lib/mosimo-pds $out/lib/pds
    runHook postInstall
  '';
  meta = {
    description = "JMA disaster-prevention XML to Mosimo importer (attest.importer feed jma-xml)";
    license = lib.licenses.mit;
    platforms = lib.platforms.linux;
  };
}
