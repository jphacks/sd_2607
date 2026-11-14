{ lib, buildNpmPackage, importNpmLock, nodejs_24, appviewUrl, clientKey ? null, mapBounds ? null, relayUrl ? null }:
let
  src = lib.cleanSourceWith {
    src = ./.;
    filter = path: type:
      let name = baseNameOf path;
      in !(builtins.elem name [ "node_modules" "dist" ".wrangler" ])
        # Vite would read a developer's .env.local and override the deployment values.
        && !(lib.hasPrefix ".env" name)
        && !(lib.hasSuffix ".nix" name)
        && !(lib.hasSuffix ".md" name)
        && !(lib.hasInfix ".test." name);
  };
in
buildNpmPackage {
  pname = "mosimo-gov-web";
  version = "0.1.0";
  nodejs = nodejs_24;
  inherit src;

  # Fetch each package by its lockfile integrity, so lockfile updates need no hash.
  # Read the manifests from the source tree, not from the filtered store path:
  # `nix flake check --no-build` evaluates without writing to the store, so the
  # filtered path does not exist yet and reading it fails as "is not valid".
  npmDeps = importNpmLock {
    npmRoot = src;
    package = lib.importJSON ./package.json;
    packageLock = lib.importJSON ./package-lock.json;
  };
  npmConfigHook = importNpmLock.npmConfigHook;
  # Vite needs only the prebuilt native bindings; skip install scripts such as
  # wrangler's workerd and sharp, which are not used by the build.
  npmRebuildFlags = [ "--ignore-scripts" ];

  env = {
    VITE_APPVIEW_URL = appviewUrl;
  } // lib.optionalAttrs (clientKey != null) {
    VITE_CLIENT_KEY = clientKey;
  } // lib.optionalAttrs (mapBounds != null) {
    VITE_MAP_BOUNDS = mapBounds;
  } // lib.optionalAttrs (relayUrl != null) {
    VITE_RELAY_URL = relayUrl;
  };

  installPhase = ''
    runHook preInstall
    cp -r dist $out
    runHook postInstall
  '';

  meta = {
    description = "Mosimo post review dashboard for government offices (static files)";
    license = lib.licenses.mit;
  };
}
