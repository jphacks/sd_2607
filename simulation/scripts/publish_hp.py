"""Copy the built installers into ../hp and update the download manifest.

Run after `mise run build` (and `mise run build-mac-x64` for the Intel .dmg). Only the installers found on this machine are replaced, so
running it on Windows updates the .msi/.exe entries and running it on macOS adds the .dmg
without touching the others. The Linux bundles come from `mise run build-linux`, which
builds in a container and writes to target/linux.

  hp/public/downloads/simulator/<file>   the installers (ASCII names for stable URLs)
  hp/src/data/simulator-downloads.json   what the download page lists
"""
import hashlib
import json
import re
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# host builds land in target/release; cross builds (--target) in target/<triple>/release
# and the Linux container build in target/linux/release
BUNDLES = [ROOT / "target" / "release" / "bundle", *(ROOT / "target").glob("*/release/bundle")]
HP = ROOT.parent / "hp"
OUT_DIR = HP / "public" / "downloads" / "simulator"
MANIFEST = HP / "src" / "data" / "simulator-downloads.json"

version = json.loads((ROOT / "src-tauri" / "tauri.conf.json").read_text(encoding="utf-8"))["version"]

# kind -> (bundle glob, platform, published name)
KINDS = {
    "exe": ("nsis/*_x64-setup.exe", "windows", f"mosimo-simulator_{version}_x64-setup.exe"),
    "msi": ("msi/*_x64_*.msi", "windows", f"mosimo-simulator_{version}_x64.msi"),
    "dmg-aarch64": ("dmg/*_aarch64.dmg", "macos", f"mosimo-simulator_{version}_aarch64.dmg"),
    "dmg-x64": ("dmg/*_x64.dmg", "macos", f"mosimo-simulator_{version}_x64.dmg"),
    "appimage": ("appimage/*_amd64.AppImage", "linux", f"mosimo-simulator_{version}_amd64.AppImage"),
    "deb": ("deb/*_amd64.deb", "linux", f"mosimo-simulator_{version}_amd64.deb"),
    "rpm": ("rpm/*.x86_64.rpm", "linux", f"mosimo-simulator-{version}-1.x86_64.rpm"),
}


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


manifest = json.loads(MANIFEST.read_text(encoding="utf-8")) if MANIFEST.exists() else {"files": []}
entries = {e["kind"]: e for e in manifest["files"]}
OUT_DIR.mkdir(parents=True, exist_ok=True)

found = 0
for kind, (pattern, platform, name) in KINDS.items():
    matches = [p for b in BUNDLES for p in b.glob(pattern) if version in p.name]
    if not matches:
        continue
    src = max(matches, key=lambda p: p.stat().st_mtime)
    old = entries.get(kind)
    if old and old["file"] != name:
        (OUT_DIR / old["file"]).unlink(missing_ok=True)
    shutil.copyfile(src, OUT_DIR / name)
    entries[kind] = {
        "kind": kind,
        "platform": platform,
        "version": version,
        "file": name,
        "bytes": src.stat().st_size,
        "sha256": sha256(src),
    }
    found += 1
    print(f"{kind:12} {name}  ({src.stat().st_size / 2**20:.2f} MiB)")

if not found:
    sys.exit(f"no installers for {version} under {', '.join(map(str, BUNDLES))}; run `mise run build` first")

order = list(KINDS)
manifest["files"] = sorted(entries.values(), key=lambda e: order.index(e["kind"]) if e["kind"] in order else len(order))
MANIFEST.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
print(f"updated {MANIFEST.relative_to(HP.parent)}")
# keep only files the manifest points at
keep = {e["file"] for e in manifest["files"]}
for p in OUT_DIR.iterdir():
    if p.is_file() and p.name not in keep and re.match(r"mosimo-simulator_", p.name):
        p.unlink()
