#!/usr/bin/env python3
"""Compile the actual MeshBle.cpp against host-only boundary mocks; no dependencies.

From the repository root on Linux/WSL:
    python3 apps/esp32/test_bundle/test_ble.py --sanitize

Outputs and generated platform-header shims live only in a temporary directory.
This does not build/upload firmware or access serial/Bluetooth hardware.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cxx", default="g++", help="C++11 compiler (default: g++)")
    parser.add_argument("--sanitize", action="store_true", help="Enable ASan and UBSan")
    parser.add_argument("--combined", action="store_true")
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    esp32 = here.parent
    with tempfile.TemporaryDirectory(prefix="mesh-ble-host-") as directory:
        temp = Path(directory)
        for name in ("NimBLEDevice.h", "Arduino.h", "MeshRelay.h",
                     "freertos/FreeRTOS.h", "freertos/queue.h"):
            header = temp / name
            header.parent.mkdir(parents=True, exist_ok=True)
            header.write_text('#include "ble_host_mocks.h"\n', encoding="utf-8")
        exe = temp / ("ble-host.exe" if os.name == "nt" else "ble-host")
        command = [args.cxx, "-std=c++11", "-Wall", "-Wextra", "-Werror", "-g"]
        if args.combined:
            command += ["-DMESH_GATEWAY_TTS=1"]
        if args.sanitize:
            command += ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
        command += ["-I" + str(temp), "-I" + str(here), "-I" + str(esp32 / "include"),
                    str(here / "ble_host.cpp"), str(esp32 / "src/MeshBle.cpp"), "-o", str(exe)]
        print("+", " ".join(command), flush=True)
        subprocess.run(command, check=True)
        subprocess.run([str(exe)], check=True)


if __name__ == "__main__":
    main()
