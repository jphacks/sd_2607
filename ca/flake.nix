{
  description = "mosimo-ca: dev.jisaku.mosimo.attest の認証局ツール";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  # Git 管理下の flake として評価されるため、Nix ストアにコピーされるのは追跡中のファイルだけ。
  # .gitignore で除外している keys/ と target/ はストアに入らない。
  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in
    {
      packages = forAllSystems (pkgs: {
        default = pkgs.rustPlatform.buildRustPackage {
          pname = "mosimo-ca";
          version = "0.1.0";
          src = pkgs.lib.cleanSourceWith {
            src = ./.;
            filter = path: _type: !(builtins.elem (baseNameOf path) [ "keys" "target" ]);
          };
          cargoLock.lockFile = ./Cargo.lock;
          meta.mainProgram = "mosimo-ca";
        };
      });

      apps = forAllSystems (pkgs: {
        default = {
          type = "app";
          program = "${self.packages.${pkgs.stdenv.hostPlatform.system}.default}/bin/mosimo-ca";
          meta.description = "dev.jisaku.mosimo.attest の認証局ツール";
        };
      });

      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          packages = [ pkgs.cargo pkgs.rustc pkgs.clippy pkgs.rustfmt pkgs.rust-analyzer pkgs.age ];
          RUST_SRC_PATH = "${pkgs.rustPlatform.rustLibSrc}";
        };
      });
    };
}
