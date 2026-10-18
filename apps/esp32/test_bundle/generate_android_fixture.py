#!/usr/bin/env python3
"""Compile actual bundle-core sources + GenerateAndroidFixture.kt from cached jars.

Requires JDK 21, cached Kotlin 2.3.20 / bundle-core dependencies, and an existing
third_party/sd_2607-dtn7-kotlin/bpv7/build/libs/dtn7-bpv7.jar. No Gradle project,
Android source, or build output is modified. No dependencies are downloaded.
Compiler argfiles/output stay in ~/.cache/mesh-bundle-kotlin for reproduction.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default=shutil.which("java") or "java", help="JDK 21 java executable")
    parser.add_argument("--gradle-cache", type=Path,
                        default=Path.home() / ".gradle/caches/modules-2/files-2.1")
    parser.add_argument("--build-dir", type=Path, default=Path.home() / ".cache/mesh-bundle-kotlin")
    parser.add_argument("--bpv7-jar", type=Path,
                        default=ROOT / "third_party/sd_2607-dtn7-kotlin/bpv7/build/libs/dtn7-bpv7.jar")
    parser.add_argument("--output", type=Path, default=HERE / "android-base64-fixtures.json")
    args = parser.parse_args()
    if not args.bpv7_jar.is_file():
        parser.error("BPv7 jar missing; build :dtn7-bpv7:jar separately first")

    def jars(group, artifact, version, required=True):
        found = sorted((args.gradle_cache / group / artifact / version).rglob("*.jar"))
        if required and not found:
            parser.error(f"missing cached dependency: {group}:{artifact}:{version}")
        return found

    compiler = []
    for dependency in [
        ("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.3.20"),
        ("org.jetbrains.kotlin", "kotlin-stdlib", "2.3.20"),
        ("org.jetbrains.kotlin", "kotlin-script-runtime", "2.3.20"),
        ("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2"),
        ("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
        ("org.jetbrains", "annotations", "13.0"),
    ]:
        compiler += jars(*dependency)
    runtime = []
    for dependency in [
        ("org.jetbrains.kotlin", "kotlin-stdlib", "2.3.20"),
        ("com.fasterxml.jackson.core", "jackson-core", "2.21.3"),
        ("com.fasterxml.jackson.core", "jackson-databind", "2.21.3"),
        ("com.fasterxml.jackson.core", "jackson-annotations", "2.21"),
        ("com.fasterxml.jackson.dataformat", "jackson-dataformat-cbor", "2.21.3"),
        ("org.slf4j", "slf4j-api", "1.7.36"),
        ("org.bouncycastle", "bcprov-jdk18on", "1.83"),
        ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2"),
    ]:
        runtime += jars(*dependency)
    native = "mingw" if os.name == "nt" else "darwin" if sys.platform == "darwin" else "linux"
    for artifact in ["secp256k1-kmp-jvm", "secp256k1-kmp-jni-common",
                     "secp256k1-kmp-jni-jvm-extract", "secp256k1-kmp-jni-jvm-" + native]:
        runtime += jars("fr.acinq.secp256k1", artifact, "0.24.0")
    runtime += jars("fr.acinq.secp256k1", "secp256k1-kmp-jni-jvm", "0.24.0", required=False)
    runtime.append(args.bpv7_jar.resolve())
    build = args.build_dir.resolve()
    build.mkdir(parents=True, exist_ok=True)
    classpath = os.pathsep.join(p.resolve().as_posix() for p in runtime)

    def java(argfile, arguments):
        path = build / argfile
        path.write_text("\n".join('"' + arg + '"' for arg in arguments), encoding="utf-8")
        subprocess.run([args.java, "@" + str(path)], check=True, timeout=90)

    sources = sorted((ROOT / "apps/android/bundle-core/src/main/kotlin").rglob("*.kt"))
    java("compile.args", ["-cp", os.pathsep.join(p.resolve().as_posix() for p in compiler),
         "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17",
         "-classpath", classpath, "-d", (build / "producer.jar").as_posix()]
         + [p.as_posix() for p in sources] + [(HERE / "GenerateAndroidFixture.kt").as_posix()])
    java("run.args", ["-cp", (build / "producer.jar").as_posix() + os.pathsep + classpath,
         "org.mndt.core.GenerateAndroidFixtureKt", args.output.resolve().as_posix(), ROOT.as_posix()])


if __name__ == "__main__":
    main()
