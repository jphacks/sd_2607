"""Read-only ESP32 lexical-parser differential for observed Android HELLOs.

Compiles the current production JsonShape directly (no copied/reimplemented parser)
under WSL on Windows (native c++ elsewhere); writes only an ignored Android .gradle executable. No device access.
"""
import json
import re
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[3]
source = (ROOT / "apps/esp32/src/MeshRelay.cpp").read_text(encoding="utf-8")
start = source.index("struct JsonShape {")
end = source.index("\n};", start) + len("\n};")
shape = source[start:end]
program = "#include <cstdint>\n#include <cstring>\n#include <iostream>\n#include <string>\n" + shape + r'''
int main() {
    std::string input;
    while (std::getline(std::cin, input)) {
        JsonShape shape(reinterpret_cast<const uint8_t*>(input.data()), input.size());
        bool valid = shape.check();
        std::cout << valid << " " << input.size() << " " << shape.members << "\n";
    }
}
'''
host = ["wsl.exe", "-d", "Ubuntu", "--"] if shutil.which("wsl.exe") else []
subprocess.run(host + ["g++", "-std=c++11", "-Wall", "-Wextra", "-Werror",
                "-x", "c++", "-o", "apps/android/.gradle/hello-shape", "-"],
               input=program, text=True, check=True, cwd=ROOT, timeout=30)
nodes = ["707b1f7b29e16363", "07a6b6ee338d7419"]
# Current Android HELLO (Wire.encode) adds the "dids" capability; ESP32 must still accept it as a 5-member object.
hellos = [json.dumps(dict(v=1, type="hello", node=node, max=24000, dids=1), separators=(",", ":")) for node in nodes]
observed = []
trace = ROOT / "apps/android/evidence/hardware-hello-tx.json"
if trace.exists():
    for line in json.loads(trace.read_text(encoding="utf-8"))["lines"]:
        match = re.search(r"\[HELLO-PROBE\].*offset=0.*bytes=([0-9a-f]+)$", line)
        if match:
            frame = bytes.fromhex(match[1])
            assert len(frame) == 64 and int.from_bytes(frame[:4], "big") == 60
            hello = frame[4:].decode("utf-8")
            if hello not in observed:
                observed.append(hello)
cases = hellos + observed + [hellos[0][:-1], hellos[0] + "{}"]
result = subprocess.run(host + ["apps/android/.gradle/hello-shape"],
                        input=("\n".join(cases) + "\n").encode("utf-8"), capture_output=True,
                        check=True, cwd=ROOT, timeout=10)
lines = result.stdout.decode("utf-8").splitlines()
assert len(lines) == len(cases), result.stdout
valid_count = len(hellos) + len(observed)
assert lines[:len(hellos)] == ["1 69 5"] * len(hellos), result.stdout
assert lines[len(hellos):valid_count] == ["1 60 4"] * len(observed), result.stdout
assert all(line.startswith("0 ") for line in lines[valid_count:]), result.stdout
print(json.dumps(dict(ok=True, parser="current production JsonShape", nodes=nodes,
                      jsonBytes=69, framedBytes=73, legacyJsonBytes=60, validAccepted=valid_count, actualQueueFrames=len(observed), malformedRejected=2)))
