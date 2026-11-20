{
  description = "Mosimo Protocol ウェブサイト（hp/）の開発環境";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in
    {
      devShells = forAllSystems (pkgs:
        let
          inherit (pkgs) lib stdenv;
          npmArch = if stdenv.hostPlatform.isAarch64 then "arm64" else "64";
          # npm が配る workerd は FHS の動的リンカーを前提にしており、NixOS ではそのまま起動できない。
          # node_modules の workerd を初回起動時に複製して interpreter を書き換え、miniflare にはこちらを使わせる。
          # 複製は node_modules/.cache に置くので、npm ci で workerd が更新されれば作り直される。
          workerd = pkgs.writeShellScriptBin "workerd" ''
            set -eu
            root="''${MOSIMO_HP_ROOT:?nix develop を hp/ で実行してください}"
            src="$root/node_modules/@cloudflare/workerd-linux-${npmArch}/bin/workerd"
            dst="$root/node_modules/.cache/workerd-nix/workerd"
            if [ ! -e "$src" ]; then
              echo "workerd が見つかりません。先に hp/ で npm ci を実行してください: $src" >&2
              exit 1
            fi
            if [ ! -x "$dst" ] || [ "$src" -nt "$dst" ]; then
              mkdir -p "$(dirname "$dst")"
              tmp="$dst.$$"
              cp "$src" "$tmp"
              chmod u+w "$tmp"
              ${lib.getExe pkgs.patchelf} --set-interpreter "$(cat ${stdenv.cc}/nix-support/dynamic-linker)" "$tmp"
              mv "$tmp" "$dst"
            fi
            exec "$dst" "$@"
          '';
        in
        {
          default = pkgs.mkShell {
            packages = [ pkgs.nodejs_24 ] ++ lib.optional stdenv.hostPlatform.isLinux workerd;
            shellHook = ''
              export MOSIMO_HP_ROOT="$PWD"
            '' + lib.optionalString stdenv.hostPlatform.isLinux ''
              export MINIFLARE_WORKERD_PATH="${lib.getExe workerd}"
            '';
          };
        });
    };
}
