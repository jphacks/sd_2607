"""Install a user-acquired AquesTalk ESP32 Small 2.4.4 SDK for local evaluation only."""

import argparse
import hashlib
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parent
FILES = {
    "src/aquestalk.h": 64 * 1024,
    "src/esp32/libaquestalk_s.a": 2 * 1024 * 1024,
    "aq_dic/aqdic_m.bin": 3 * 1024 * 1024,
    "aq_dic/CREDITS": 64 * 1024,
    "readme.txt": 64 * 1024,
    "aquestalk_esp32_man.pdf": 2 * 1024 * 1024,
}


def install(archive, destination):
    # Only explicitly named entries are copied, never archive-controlled paths.
    with zipfile.ZipFile(archive) as sdk:
        contents = {}
        for name, limit in FILES.items():
            entry = sdk.getinfo("aquestalk-esp32_s/" + name)
            if not 0 < entry.file_size <= limit:
                raise ValueError("Unexpected SDK entry size: " + name)
            contents[name] = sdk.read(entry)
    dictionary = contents["aq_dic/aqdic_m.bin"]
    if len(dictionary) != 1_969_776:
        raise ValueError("Expected the Small 2.4.4 dictionary (1969776 bytes)")
    if not contents["src/esp32/libaquestalk_s.a"].startswith(b"!<arch>\n"):
        raise ValueError("Invalid ESP32 static library")
    # Never overwrite a different local SDK or licensed package.
    for name, data in contents.items():
        path = destination / name
        if path.exists() and path.read_bytes() != data:
            raise FileExistsError("Different local file already exists: " + str(path))
    for name, data in contents.items():
        path = destination / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    generated = destination / "generated"
    generated.mkdir(exist_ok=True)
    digest = hashlib.sha256(dictionary).hexdigest()
    (generated / "LocalAquesTalkConfig.h").write_text(
        '#pragma once\n#define AQ_DICT_BYTES 1969776\n'
        f'#define AQ_DICT_SHA256 "{digest}"\n', encoding="utf-8"
    )
    print("Local evaluation SDK installed. No license key configured.")
    print("Dictionary bytes:", len(dictionary), "SHA256:", digest)
    print("Do not redistribute private SDK files or linked evaluation firmware.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path, help="User-downloaded aquestalk-esp32_s_0244.zip")
    args = parser.parse_args()
    install(args.archive, ROOT / "private/aquestalk")


if __name__ == "__main__":
    main()
