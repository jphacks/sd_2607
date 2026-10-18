"""Back up existing data, then write the private evaluation dictionary only."""

import argparse
from datetime import datetime, timezone
from pathlib import Path
import struct
import subprocess
import sys

ROOT = Path(__file__).resolve().parent
OFFSET = 0x400000
SIZE = 0x200000


def partitions(data):
    result = {}
    for at in range(0, min(len(data), 3072), 32):
        magic, kind, subtype, offset, size, label, flags = struct.unpack_from("<HBBII16sI", data, at)
        if magic == 0xFFFF or magic == 0xEBEB:
            break
        if magic != 0x50AA:
            raise ValueError("Invalid partition table entry")
        name = label.rstrip(b"\0").decode("ascii")
        if name in result:
            raise ValueError("Duplicate partition label")
        result[name] = (kind, subtype, offset, size, flags)
    if not result:
        raise ValueError("Empty partition table")
    return result


def check_layout(current, built):
    expected = {
        "nvs": (1, 2, 0x9000, 0x5000, 0),
        "otadata": (1, 0, 0xE000, 0x2000, 0),
        "app0": (0, 16, 0x10000, 0x140000, 0),
        "app1": (0, 17, 0x150000, 0x140000, 0),
        "spiffs": (1, 130, 0x290000, 0x160000, 0),
        "coredump": (1, 3, 0x3F0000, 0x10000, 0),
    }
    target = dict(expected, aqdic=(1, 0x40, OFFSET, SIZE, 0))
    if built != target or current not in (expected, target):
        raise ValueError("Partition layout differs; refusing automatic dictionary provisioning")


def tool(port, *arguments):
    command = [sys.executable, "-m", "platformio", "pkg", "exec", "--package",
               "tool-esptoolpy", "--", "esptool.py", "--chip", "esp32", "--port",
               port, "--baud", "460800", *map(str, arguments)]
    return subprocess.run(command, cwd=ROOT, check=True, timeout=150,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                          encoding="utf-8", errors="replace").stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("port", help="USB serial port, for example COM3")
    args = parser.parse_args()
    dictionary = ROOT / "private/aquestalk/aq_dic/aqdic_m.bin"
    built = partitions((ROOT / ".pio/build/m5stack-basic/partitions.bin").read_bytes())
    if dictionary.stat().st_size != 1_969_776:
        raise ValueError("Wrong Small 2.4.4 dictionary size")
    try:
        identity = tool(args.port, "flash_id")
        print(identity, flush=True)
        if "Detected flash size: 16MB" not in identity:
            raise ValueError("This provisioning layout requires a verified 16MB flash")
        backup = ROOT / "private/backups" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
        backup.mkdir(parents=True)
        print("Backup directory:", backup, flush=True)
        print(tool(args.port, "read_flash", "0x8000", "0x1000", backup / "partitions.bin"), flush=True)
        current = partitions((backup / "partitions.bin").read_bytes())
        check_layout(current, built)
        for name, offset, size in (("messages", 0x290000, 0x160000), ("dictionary-region", OFFSET, SIZE)):
            print(tool(args.port, "read_flash", hex(offset), hex(size), backup / (name + ".bin")), flush=True)
            if (backup / (name + ".bin")).stat().st_size != size:
                raise ValueError("Incomplete backup: " + name)
        print(tool(args.port, "write_flash", "--flash_size", "16MB", hex(OFFSET), dictionary), flush=True)
        print(tool(args.port, "verify_flash", hex(OFFSET), dictionary), flush=True)
    except subprocess.CalledProcessError as error:
        print(error.stdout, file=sys.stderr)
        raise
    print("Dictionary verified. Existing filesystem was NOT formatted or moved.")
    print("Next: uv run pio run --target upload --upload-port", args.port)


if __name__ == "__main__":
    main()
