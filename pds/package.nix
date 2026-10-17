{ lib, buildNpmPackage, nodejs_24 }:

buildNpmPackage {
  pname = "mosimo-pds";
  version = "0.1.0";
  nodejs = nodejs_24;

  src = lib.cleanSourceWith {
    src = ./.;
    filter = path: type:
      let name = baseNameOf path;
      in path == toString ./.
        || (type == "regular" && (
          name == "package.json" || name == "package-lock.json"
          || ((lib.hasSuffix ".mjs" name || lib.hasSuffix ".mts" name)
            && !(lib.hasInfix ".test." name))
        ));
  };

  npmDepsHash = "sha256-xwdgZUJRyYGxOa+NRuAI1Sfh/F6/VG7r0xWYACnYt4U=";
  npmFlags = [ "--omit=dev" ];
  dontNpmBuild = true;

  installPhase = ''
    runHook preInstall
    mkdir -p $out/lib/mosimo-pds
    cp *.mjs *.mts package.json $out/lib/mosimo-pds/
    cp -r node_modules $out/lib/mosimo-pds/
    runHook postInstall
  '';

  meta = {
    description = "Mosimo PDS with native Node.js TypeScript support";
    license = lib.licenses.mit;
    platforms = lib.platforms.linux;
  };
}
