#!/usr/bin/env python3
"""Build actual MeshSpeech.cpp with host boundary mocks; no SDK assets copied.

Run: python3 apps/esp32/test_bundle/test_speech.py --sanitize
No firmware upload, serial access, or third-party Python packages.
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
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    with tempfile.TemporaryDirectory(prefix="mesh-speech-host-") as directory:
        temp = Path(directory)
        for name in ("Arduino.h", "M5Unified.h", "aquestalk.h", "LocalAquesTalkConfig.h",
                     "esp_partition.h", "mbedtls/sha256.h", "freertos/FreeRTOS.h",
                     "freertos/task.h"):
            header = temp / name
            header.parent.mkdir(parents=True, exist_ok=True)
            header.write_text('#define SPEECH_MOCK_HEADER\n#include "speech_host.cpp"\n'
                              '#undef SPEECH_MOCK_HEADER\n', encoding="utf-8")
        exe = temp / ("speech-host.exe" if os.name == "nt" else "speech-host")
        command = [args.cxx, "-std=c++11", "-Wall", "-Wextra", "-Werror", "-g"]
        if args.combined:
            command += ["-DMESH_GATEWAY_TTS=1", "-pthread"]
        if args.sanitize:
            command += ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
        command += ["-I" + str(temp), "-I" + str(here), "-I" + str(here.parent / "include"),
                    str(here / "speech_host.cpp"), str(here.parent / "src/MeshSpeech.cpp"),
                    "-o", str(exe)]
        print("+", " ".join(command), flush=True)
        subprocess.run(command, check=True)
        subprocess.run([str(exe)], check=True)


if __name__ == "__main__":
    main()
