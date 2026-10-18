#!/usr/bin/env python3
"""Build/run relay_host.cpp with real codec/ArduinoJson and mock LittleFS/time.

From the repository root (Linux host, mbedTLS 2.x development library):
    python3 -m pip install cbor2==5.6.2
    python3 apps/esp32/test_bundle/test_relay.py

ArduinoJson defaults to ../.pio/libdeps/m5stack-basic/ArduinoJson/src.
Override with --arduinojson /path/to/ArduinoJson/src. Non-system mbedTLS can
use --mbedtls-include /path/include --mbedtls-library /path/libmbedcrypto.a.
No downloads, firmware changes, or test outputs are written into the repo.
This tests same-session replacement, bounded replay history and state/storage
ordering, NOT actual LittleFS power-loss guarantees,
BLE transport, or ESP32 timing/heap. The Python signer uses public test keys.
The provisioned littlefs.bin has name_max=32 despite SDK headers saying 64.
The mock enforces 32-byte components and a 63-byte whole-path bound. Bundle
files occupy /mesh/00 through /mesh/15; identity comes only from verified data.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
ESP32 = HERE.parent

# The stat size deliberately remains stale until flush: Arduino FS uses buffered
# fwrite + stat, so checking size before flush is a real-device regression.
# Model the actual provisioning superblock: name_max=32, not SDK's 64.
# The 43-character base64url filename also fails. Use two-digit slots instead.
MOCK_FS = r'''
#pragma once
#include <algorithm>
#include <cerrno>
#include <cstring>
#include <map>
#include <memory>
#include <string>
#include <vector>

struct File {
    struct Handle {
        std::string path;
        size_t pos = 0, next = 0;
        bool directory = false, valid = true, dirty = false;
    };
    std::shared_ptr<Handle> h;
    File() {}
    File(const std::string& path, bool directory) : h(new Handle) {
        h->path = path; h->directory = directory;
    }
    explicit operator bool() const { return h && h->valid; }
    bool isDirectory() const { return bool(*this) && h->directory; }
    size_t size() const;
    size_t read(uint8_t*, size_t);
    size_t write(const uint8_t*, size_t);
    void flush();
    void close();
    const char* name() const { return h->path.c_str(); }
    File openNextFile();
};
struct MockFS {
    std::map<std::string, std::vector<uint8_t>> files;
    bool mounted = true, directory = false, format = false;
    bool failWrite = false, failRename = false, failRead = false, failFlush = false;
    bool failOpenTemp = false, failOpenFinal = false, failCloseTemp = false, failCloseFinal = false;
    bool silentErrors = false, corruptReadback = false, truncateOnRename = false;
    bool failRemove = false, requireStagedEviction = false, stagedFlushed = false, stagedClosed = false;
    size_t peakBytes = 0, peakFiles = 0;
    unsigned evictions = 0;
    int cleanupErrno = 0;
    unsigned flushes = 0, renames = 0, renameNameErrors = 0;
    bool validPath(const char* path) const {
        if (std::strlen(path) > 63) { errno = ENAMETOOLONG; return false; }
        size_t component = 0;
        for (const char* p = path; *p; ++p) {
            if (*p == '/') component = 0;
            else if (++component > 32) { errno = ENAMETOOLONG; return false; }
        }
        return true;
    }
    bool begin(bool f) { format = f; return mounted; }
    bool exists(const char* p) {
        if (!validPath(p)) return false;
        bool found = (std::string(p) == "/mesh" && directory) || files.count(p);
        if (!found) errno = ENOENT;
        return found;
    }
    bool mkdir(const char* p) { if (!validPath(p)) return false; directory = true; return true; }
    bool remove(const char* p) {
        if (!validPath(p)) return false;
        bool committed = std::string(p) != "/mesh/.incoming" && files.count(p);
        if (committed && failRemove) { errno = EACCES; return false; }
        if (committed && requireStagedEviction &&
            (!stagedFlushed || !stagedClosed || !files.count("/mesh/.incoming"))) { errno = EIO; return false; }
        bool removed = files.erase(p) > 0;
        if (removed && committed) ++evictions;
        if (cleanupErrno) errno = cleanupErrno;
        return removed;
    }
    bool rename(const char* a, const char* b) {
        if (!validPath(a) || !validPath(b)) { ++renameNameErrors; return false; }
        if (failRename) { errno = silentErrors ? 0 : EIO; return false; }
        if (!files.count(a)) { errno = ENOENT; return false; }
        files[b] = files[a]; files.erase(a); ++renames;
        if (truncateOnRename && !files[b].empty()) files[b].pop_back();
        return true;
    }
    File open(const char* p, const char* mode) {
        if (!validPath(p)) return File();
        if (std::string(p) == "/mesh") return directory ? File(p, true) : File();
        bool temporary = std::string(p) == "/mesh/.incoming";
        if ((temporary && failOpenTemp) || (!temporary && failOpenFinal)) {
            errno = silentErrors ? 0 : EACCES;
            return File();
        }
        if (*mode == 'w') { files[p].clear(); return File(p, false); }
        if (!files.count(p)) { errno = ENOENT; return File(); }
        return File(p, false);
    }
};
extern MockFS LittleFS;
inline size_t File::size() const {
    return bool(*this) && !h->directory && !h->dirty ? LittleFS.files[h->path].size() : 0;
}
inline size_t File::read(uint8_t* p, size_t n) {
    if (!bool(*this)) return 0;
    if (LittleFS.failRead) { errno = LittleFS.silentErrors ? 0 : EIO; return 0; }
    auto& bytes = LittleFS.files[h->path];
    n = std::min(n, bytes.size() - h->pos);
    if (n) std::memcpy(p, bytes.data() + h->pos, n);
    if (n && LittleFS.corruptReadback) p[0] ^= 1;
    h->pos += n;
    return n;
}
inline size_t File::write(const uint8_t* p, size_t n) {
    if (LittleFS.failWrite) { n /= 2; errno = LittleFS.silentErrors ? 0 : ENOSPC; }
    h->dirty = true;
    if (h->path == "/mesh/.incoming") LittleFS.stagedFlushed = LittleFS.stagedClosed = false;
    auto& bytes = LittleFS.files[h->path];
    bytes.insert(bytes.end(), p, p + n);
    size_t total = 0;
    for (const auto& file : LittleFS.files) total += file.second.size();
    LittleFS.peakBytes = std::max(LittleFS.peakBytes, total);
    LittleFS.peakFiles = std::max(LittleFS.peakFiles, LittleFS.files.size());
    return n;
}
inline void File::flush() {
    ++LittleFS.flushes;
    if (!LittleFS.failFlush) {
        h->dirty = false;
        if (h->path == "/mesh/.incoming") LittleFS.stagedFlushed = true;
    }
    else errno = LittleFS.silentErrors ? 0 : EIO;
}
inline void File::close() {
    if (!bool(*this)) return;
    bool temporary = h->path == "/mesh/.incoming";
    if (temporary) LittleFS.stagedClosed = true;
    if (!h->directory && ((temporary && LittleFS.failCloseTemp) || (!temporary && LittleFS.failCloseFinal)))
        errno = LittleFS.silentErrors ? 0 : EIO;
    h->valid = false;
}
inline File File::openNextFile() {
    auto it = LittleFS.files.begin();
    size_t i = 0;
    while (it != LittleFS.files.end() && i < h->next) { ++it; ++i; }
    if (it == LittleFS.files.end()) return File();
    ++h->next;
    return File(it->first, false);
}
'''
MOCK_CLOCK = '''#pragma once
#include <cstdint>
extern uint64_t fakeNow;
inline int64_t esp_timer_get_time() { return static_cast<int64_t>(fakeNow * 1000); }
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cxx", default=os.environ.get("CXX", shutil.which("g++") or "c++"),
                        help="host C++ compiler executable (not the ESP32 cross compiler)")
    parser.add_argument("--arduinojson", type=Path,
                        default=ESP32 / ".pio/libdeps/m5stack-basic/ArduinoJson/src")
    parser.add_argument("--mbedtls-include", type=Path)
    parser.add_argument("--mbedtls-library", type=Path)
    parser.add_argument("--no-sanitize", action="store_true",
                        help="for host toolchains without ASan/UBSan")
    args = parser.parse_args()
    if not (args.arduinojson / "ArduinoJson.h").is_file():
        parser.error("ArduinoJson headers missing; install 7.4.2 or supply --arduinojson")
    try:
        import cbor2  # noqa: F401; shared fixture generator dependency
    except ImportError:
        parser.error("cbor2 is required; install cbor2==5.6.2")

    sys.dont_write_bytecode = True
    spec = importlib.util.spec_from_file_location("relay_fixtures", HERE / "test_bundle.py")
    fixtures = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(fixtures)
    with tempfile.TemporaryDirectory(prefix="mesh-relay-host-") as temp:
        build = Path(temp)
        (build / "LittleFS.h").write_text(MOCK_FS, encoding="utf-8")
        (build / "esp_timer.h").write_text(MOCK_CLOCK, encoding="utf-8")
        fixed = json.loads((HERE / "fixtures.json").read_text(encoding="utf-8"))
        (build / "0.bin").write_bytes(bytes.fromhex(fixed["valid_bundle_hex"]))

        (build / "tampered.bin").write_bytes(bytes.fromhex(fixed["tampered_signature_bundle_hex"]))
        # 128 session completions plus a 129th offer exercise the history cap.
        for i in range(1, 129):
            payload, _, _ = fixtures.event("host relay test " + str(i))
            (build / f"{i}.bin").write_bytes(fixtures.bundle(payload))
            if i == 5:
                (build / "aged5.bin").write_bytes(fixtures.bundle(payload, age=86400000 - 2))
            if i == 16:
                payload[6] = payload[6][:-1] + bytes([payload[6][-1] ^ 1])
                (build / "tampered16.bin").write_bytes(fixtures.bundle(payload))

        executable = build / ("relay-host.exe" if os.name == "nt" else "relay-host")
        command = [args.cxx, "-std=c++11", "-Wall", "-Wextra", "-Werror", "-g"]
        if not args.no_sanitize:
            command += ["-fsanitize=address,undefined"]
        command += ["-I" + str(build), "-I" + str(args.arduinojson.resolve()),
                    "-I" + str(ESP32 / "include")]
        if args.mbedtls_include:
            command += ["-I" + str(args.mbedtls_include.resolve())]
        command += [str(HERE / "relay_host.cpp"), str(ESP32 / "src/MeshRelay.cpp"),
                    str(ESP32 / "src/MeshBundle.cpp")]
        command += [str(args.mbedtls_library.resolve()) if args.mbedtls_library else "-lmbedcrypto"]
        command += ["-o", str(executable)]
        subprocess.run(command, check=True, timeout=90)
        subprocess.run([str(executable), str(build)], check=True, timeout=90)


if __name__ == "__main__":
    main()
