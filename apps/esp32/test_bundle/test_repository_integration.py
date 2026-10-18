#!/usr/bin/env python3
"""Offline real Kotlin -> ESP32 C++ -> Kotlin -> PDS integration (no radios/network).

From the repository root, with cached Kotlin dependencies, the BPv7 jar and
`npm ci --offline --ignore-scripts --no-audit --no-fund` installed in pds:

python apps/esp32/test_bundle/test_repository_integration.py \
  --java "C:/Program Files/Microsoft/jdk-21.0.6.7-hotspot/bin/java.exe" \
  --wsl Ubuntu \
  --mbedtls-include /home/mchiba/.cache/mesh-bundle-host/deps/usr/include \
  --mbedtls-library /home/mchiba/.cache/mesh-bundle-host/deps/usr/lib/x86_64-linux-gnu/libmbedcrypto.a

In Git Bash prefix the command with MSYS_NO_PATHCONV=1 (not needed in PowerShell).
On Linux omit --wsl; system mbedTLS 2.x is used unless overridden. Requires
Node >=24, Python >=3.10 and GCC. Outputs/SQLite/argfiles live only in a temporary
directory, removed on exit. No existing fixtures or firmware files are changed.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]


def run(command, **kwargs):
    print("+", subprocess.list2cmdline([str(x) for x in command]), flush=True)
    try:
        return subprocess.run(command, check=True, timeout=120, cwd=ROOT, **kwargs)
    except subprocess.CalledProcessError as error:
        if error.stderr:
            print(error.stderr, file=sys.stderr)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--java", default="java", help="JDK 21 java executable")
    parser.add_argument("--node", default="node", help="Node >=24 executable (same OS as Python)")
    parser.add_argument("--wsl", help="Run only the C++ host under this WSL distribution")
    parser.add_argument("--mbedtls-include", help="mbedTLS 2.x include directory in the C++ host OS")
    parser.add_argument("--mbedtls-library", help="mbedTLS 2.x library in the C++ host OS")
    args = parser.parse_args()
    version = subprocess.check_output([args.node, "--version"], text=True).strip()
    if int(version.lstrip("v").split(".")[0]) < 24:
        parser.error(f"PDS needs Node >=24; found {version}")

    def host_path(path):
        if args.wsl:
            return subprocess.check_output(["wsl.exe", "-d", args.wsl, "--", "wslpath", "-u", Path(path).as_posix()], text=True).strip()
        return str(path)

    host_prefix = ["wsl.exe", "-d", args.wsl, "--cd", host_path(ROOT), "--"] if args.wsl else []
    with tempfile.TemporaryDirectory(prefix="mesh-repository-integration-") as temporary:
        work = Path(temporary)
        fixture = work / "repository.json"
        # Reuse the existing locked/cached Kotlin compiler and dependency setup.
        # Its documented argfiles let the new entry point compile with the same real sources.
        run([sys.executable, HERE / "generate_android_fixture.py", "--java", args.java,
             "--build-dir", work, "--output", fixture])
        compile_file = work / "compile.args"
        compile_file.write_text(compile_file.read_text(encoding="utf-8").replace(
            (HERE / "GenerateAndroidFixture.kt").as_posix(),
            (HERE / "GenerateRepositoryFixture.kt").as_posix()), encoding="utf-8")
        run([args.java, "@" + str(compile_file)])
        runtime = [json.loads(line) for line in (work / "run.args").read_text(encoding="utf-8").splitlines()]

        def kotlin(mode):
            arguments = runtime[:2] + ["org.mndt.core.GenerateRepositoryFixtureKt", mode,
                                       fixture.as_posix(), work.as_posix()]
            argfile = work / "repository.args"
            argfile.write_text("\n".join(json.dumps(value, ensure_ascii=False) for value in arguments), encoding="utf-8")
            run([args.java, "@" + str(argfile)])

        kotlin("generate")
        data = json.loads(fixture.read_text(encoding="utf-8"))
        assert len(data["posts"]) == 2
        assert data["posts"][0]["base"] is None
        assert data["posts"][1]["base"] == data["posts"][0]["commit_cid"]
        host = work / "bundle-host"
        command = host_prefix + ["g++", "-std=c++11", "-Wall", "-Wextra", "-Werror", "-g",
                                 "-fsanitize=address,undefined", "-fno-omit-frame-pointer",
                                 "-I" + host_path(HERE.parent / "include")]
        if args.mbedtls_include:
            command.append("-I" + args.mbedtls_include)
        command += [host_path(HERE / "host.cpp"), args.mbedtls_library or "-lmbedcrypto", "-o", host_path(host)]
        run(command)
        for index, post in enumerate(data["posts"]):
            source = work / f"source-{index}.bin"
            forwarded = work / f"forwarded-{index}.bin"
            source.write_bytes(bytes.fromhex(post["bundle_hex"]))
            for inputs in ([host_path(source), "1234", host_path(forwarded)], [host_path(forwarded)]):
                result = run(host_prefix + [host_path(host)] + inputs, capture_output=True, text=True)
                fields = result.stdout.split()
                assert fields[0] == json.loads(post["event_json"])["id"], result.stdout
                assert (int(fields[1]), int(fields[3])) == ((17, 1) if len(inputs) == 3 else (1251, 2)), result.stdout
        # The actual Kotlin BPv7 parser compares the raw forwarded payload, then
        # MeshEngine verifies/accepts it and EventCodec checks the round trip too.
        kotlin("receive")
        print("PASS ESP32: 2 real Author bundles, exact signed payloads/IDs, age 17->1251, hop 1->2", flush=True)
        environment = dict(os.environ, MESH_REPOSITORY_FIXTURE=str(fixture))
        run([args.node, "--test", ROOT / "pds" / "android-esp32.test.mjs"], env=environment)


if __name__ == "__main__":
    main()
