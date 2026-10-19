#!/usr/bin/env python3
"""Run the actual main.cpp setup/loop with host-only boundary mocks.

Run: python3 apps/esp32/test_bundle/test_main_controls.py --sanitize
No vendor SDK, hardware, source rewriting, or third-party Python packages.
Generated header shims and executable live only in a temporary directory.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cxx", default="g++")
    parser.add_argument("--sanitize", action="store_true")
    parser.add_argument("--combined", action="store_true")
    parser.add_argument("--diagnostic", action="store_true", help="also exercise opt-in UART test commands")
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    with tempfile.TemporaryDirectory(prefix="mesh-main-controls-host-") as directory:
        temp = Path(directory)
        for name in ("Arduino.h", "M5Unified.h", "esp_system.h", "MeshBle.h",
                     "MeshRelay.h", "MeshSpeech.h"):
            (temp / name).write_text("// Boundary supplied by main_controls_host.cpp\n", encoding="utf-8")
        exe = temp / ("main-controls-host.exe" if os.name == "nt" else "main-controls-host")
        command = [args.cxx, "-std=c++11", "-Wall", "-Wextra", "-Werror", "-g"]
        if args.sanitize:
            command += ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
        if args.combined:
            command += ["-DMESH_GATEWAY_BUILD=1", "-DMESH_GATEWAY_TTS=1"]
        if args.diagnostic:
            command += ["-DMESH_TEST_DIAGNOSTICS=1"]
        command += ["-I" + str(temp), "-I" + str(here.parent / "include"),
                    str(here / "main_controls_host.cpp"), "-o", str(exe)]
        print("+", " ".join(command), flush=True)
        subprocess.run(command, check=True, timeout=60)
        environment = os.environ.copy()
        if args.sanitize:
            environment["ASAN_OPTIONS"] = "halt_on_error=1:detect_leaks=1"
            environment["UBSAN_OPTIONS"] = "halt_on_error=1:print_stacktrace=1"
        subprocess.run([str(exe)], check=True, timeout=15, env=environment)


if __name__ == "__main__":
    main()
