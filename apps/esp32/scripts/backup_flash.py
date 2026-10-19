"""Read full flash twice, compare SHA-256, and record the actual partition table.

No erase/write commands. Bootloader entry resets the device; it runs again on exit.
Keep the ignored backup private: flash can contain credentials and retained posts.
"""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("port")
    parser.add_argument("--esptool", type=Path, required=True)
    parser.add_argument("--baud", default="921600")
    parser.add_argument("--resume", type=Path, help="existing backup directory after interrupted verification")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    target = args.resume.resolve() if args.resume else root / "private" / "backups" / stamp
    if not args.resume:
        target.mkdir(parents=True, exist_ok=False)
    tool = [sys.executable, str(args.esptool), "--chip", "esp32", "--port", args.port,
            "--baud", args.baud]
    files = [target / "full-16MiB.bin", target / "verification-16MiB.bin"]
    hashes = []
    for path in files:
        # Stay in the bootloader between reads so the running app cannot change NVS/FS.
        after = "hard_reset" if path == files[-1] else "no_reset"
        if not (args.resume and path == files[0] and path.is_file()):
            subprocess.run(tool + ["--after", after, "read_flash", "0", "0x1000000", str(path)],
                           check=True, timeout=360)
        if path.stat().st_size != 0x1000000:
            raise RuntimeError("Incomplete full-flash read; keep backup for investigation")
        hashes.append(hashlib.sha256(path.read_bytes()).hexdigest())
    if hashes[0] != hashes[1]:
        raise RuntimeError("Full-flash SHA-256 mismatch; both reads retained, do NOT flash")
    raw = files[0].read_bytes()
    table = raw[0x8000:0x9000]
    (target / "partitions.bin").write_bytes(table)
    partitions = []
    for at in range(0, 0xC00, 32):
        magic, kind, subtype, offset, size, label, flags = struct.unpack("<HBBII16sI", table[at:at+32])
        if magic != 0x50AA:
            break
        partitions.append(dict(name=label.rstrip(b"\0").decode("ascii"), type=kind,
                               subtype=subtype, offset=hex(offset), size=hex(size), flags=flags,
                               sha256=hashlib.sha256(raw[offset:offset+size]).hexdigest()))
    manifest = dict(createdUtc=stamp, port=args.port, bytes=len(raw),
                    sha256=hashes[0], verificationReadSha256=hashes[1],
                    partitionTableSha256=hashlib.sha256(table).hexdigest(), partitions=partitions)
    (target / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    files[1].unlink()
    print(json.dumps(dict(backup=str(target), **manifest), indent=2))


if __name__ == "__main__":
    main()
