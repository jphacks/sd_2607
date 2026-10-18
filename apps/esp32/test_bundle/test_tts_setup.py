"""Local TTS setup/layout tests using synthetic data only; no SDK or device needed."""

import contextlib
import hashlib
import io
from pathlib import Path
import runpy
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SETUP = runpy.run_path(str(ROOT / "setup_tts.py"))
PROVISION = runpy.run_path(str(ROOT / "provision_tts.py"))
DICT_BYTES = 1_969_776
CURRENT = {
    "nvs": (1, 2, 0x9000, 0x5000, 0),
    "otadata": (1, 0, 0xE000, 0x2000, 0),
    "app0": (0, 16, 0x10000, 0x140000, 0),
    "app1": (0, 17, 0x150000, 0x140000, 0),
    "spiffs": (1, 130, 0x290000, 0x160000, 0),
    "coredump": (1, 3, 0x3F0000, 0x10000, 0),
}
TARGET = dict(CURRENT, aqdic=(1, 0x40, 0x400000, 0x200000, 0))


def partition_entry(name, values, magic=0x50AA):
    kind, subtype, offset, size, flags = values
    return struct.pack("<HBBII16sI", magic, kind, subtype, offset, size,
                       name.encode("ascii"), flags)


def partition_table(layout):
    return b"".join(partition_entry(name, values) for name, values in layout.items()) + b"\xff" * 32


class NoDeviceTest(unittest.TestCase):
    def setUp(self):
        guard = patch("subprocess.run", side_effect=AssertionError("No subprocess/device calls allowed"))
        self.subprocess_run = guard.start()
        self.addCleanup(guard.stop)
        self.addCleanup(self.subprocess_run.assert_not_called)


class InstallTests(NoDeviceTest):
    def setUp(self):
        super().setUp()
        temporary = tempfile.TemporaryDirectory(prefix="tts-setup-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.destination = self.root / "installed" / "sdk"
        self.archive = self.root / "synthetic-sdk.zip"
        self.payloads = {
            "src/aquestalk.h": b"/* Synthetic test header, not an SDK. */\n",
            "src/esp32/libaquestalk_s.a": b"!<arch>\nsynthetic-test-only\n",
            "aq_dic/aqdic_m.bin": bytes(DICT_BYTES),
            "aq_dic/CREDITS": b"Synthetic test credits\n",
            "readme.txt": b"Synthetic test readme\n",
            "aquestalk_esp32_man.pdf": b"Synthetic test manual, not a PDF\n",
        }

    def make_archive(self, extras=None):
        with zipfile.ZipFile(self.archive, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, data in self.payloads.items():
                archive.writestr("aquestalk-esp32_s/" + name, data)
            for name, data in (extras or {}).items():
                archive.writestr(name, data)

    def install(self):
        with contextlib.redirect_stdout(io.StringIO()):
            SETUP["install"](self.archive, self.destination)

    def snapshot(self):
        return {path.relative_to(self.destination).as_posix(): path.read_bytes()
                for path in self.destination.rglob("*") if path.is_file()}

    def test_valid_install_writes_exact_payloads_and_size_hash_header(self):
        self.make_archive()
        self.install()
        digest = hashlib.sha256(bytes(DICT_BYTES)).hexdigest()
        installed = self.snapshot()
        config = "generated/LocalAquesTalkConfig.h"
        self.assertEqual(set(installed), set(self.payloads) | {config})
        for name, data in self.payloads.items():
            with self.subTest(name=name):
                self.assertEqual(installed[name], data)
        self.assertEqual((self.destination / config).read_text(encoding="utf-8"),
                         '#pragma once\n#define AQ_DICT_BYTES 1969776\n'
                         f'#define AQ_DICT_SHA256 "{digest}"\n')
        self.assertEqual((self.destination / "aq_dic/aqdic_m.bin").stat().st_size, DICT_BYTES)

    def test_same_sdk_install_is_idempotent(self):
        self.make_archive()
        self.install()
        before = self.snapshot()
        self.install()
        self.assertEqual(self.snapshot(), before)

    def test_different_existing_file_rejected_without_any_writes(self):
        self.make_archive()
        conflicting = self.destination / "aquestalk_esp32_man.pdf"
        conflicting.parent.mkdir(parents=True)
        conflicting.write_bytes(b"Keep existing local file")
        header = self.destination / "src/aquestalk.h"
        header.parent.mkdir()
        header.write_bytes(self.payloads["src/aquestalk.h"])
        before = self.snapshot()
        with self.assertRaises(FileExistsError):
            self.install()
        self.assertEqual(self.snapshot(), before)
        self.assertFalse((self.destination / "generated").exists())

    def test_wrong_dictionary_size_rejected_before_destination_created(self):
        for size in (1, DICT_BYTES - 1, DICT_BYTES + 1):
            with self.subTest(size=size):
                self.payloads["aq_dic/aqdic_m.bin"] = bytes(size)
                self.make_archive()
                with self.assertRaisesRegex(ValueError, "dictionary"):
                    self.install()
                self.assertFalse(self.destination.exists())

    def test_extra_and_traversal_zip_entries_are_ignored(self):
        self.make_archive({
            "aquestalk-esp32_s/extra.txt": b"Do not install",
            "aquestalk-esp32_s/../escaped.txt": b"Do not traverse",
            "../escaped.txt": b"Do not traverse",
        })
        self.install()
        self.assertEqual(set(self.snapshot()), set(self.payloads) | {"generated/LocalAquesTalkConfig.h"})
        self.assertFalse(list(self.root.rglob("escaped.txt")))

    def test_each_oversized_allowlisted_entry_rejected_before_writes(self):
        for name, limit in SETUP["FILES"].items():
            with self.subTest(name=name):
                original = self.payloads[name]
                self.payloads[name] = bytes(limit + 1)
                self.make_archive()
                self.payloads[name] = original
                with self.assertRaisesRegex(ValueError, "Unexpected SDK entry size"):
                    self.install()
                self.assertFalse(self.destination.exists())


class PartitionTests(NoDeviceTest):
    def parse(self, layout):
        return PROVISION["partitions"](partition_table(layout))

    def test_current_six_regions_to_seven_region_target_accepted(self):
        current, built = self.parse(CURRENT), self.parse(TARGET)
        self.assertEqual(current, CURRENT)
        self.assertEqual(built, TARGET)
        self.assertIsNone(PROVISION["check_layout"](current, built))

    def test_already_provisioned_target_accepted(self):
        self.assertIsNone(PROVISION["check_layout"](self.parse(TARGET), self.parse(TARGET)))

    def test_any_existing_region_change_rejected(self):
        for layout in (CURRENT, TARGET):
            for name, values in layout.items():
                for field in range(5):
                    with self.subTest(regions=len(layout), name=name, field=field):
                        changed = list(values)
                        changed[field] += 1
                        bad = dict(layout, **{name: tuple(changed)})
                        with self.assertRaises(ValueError):
                            PROVISION["check_layout"](self.parse(bad), self.parse(TARGET))

    def test_unknown_overlapping_or_missing_existing_regions_rejected(self):
        for layout in (CURRENT, TARGET):
            cases = {
                "unknown": dict(layout, unknown=(1, 0x41, 0x600000, 0x1000, 0)),
                "overlap": dict(layout, spiffs=(1, 130, 0x150000, 0x160000, 0)),
                "extra_overlap": dict(layout, unknown=(1, 0x41, 0x400000, 0x1000, 0)),
                "missing": {name: values for name, values in layout.items() if name != "nvs"},
            }
            for label, bad in cases.items():
                with self.subTest(regions=len(layout), case=label):
                    with self.assertRaises(ValueError):
                        PROVISION["check_layout"](self.parse(bad), self.parse(TARGET))

    def test_invalid_built_layout_rejected(self):
        for name, values in TARGET.items():
            for field in range(5):
                with self.subTest(name=name, field=field):
                    changed = list(values)
                    changed[field] += 1
                    bad = dict(TARGET, **{name: tuple(changed)})
                    with self.assertRaises(ValueError):
                        PROVISION["check_layout"](self.parse(CURRENT), self.parse(bad))
        for bad in (CURRENT, dict(TARGET, unknown=(1, 0x41, 0x600000, 0x1000, 0))):
            with self.subTest(built_regions=len(bad)):
                with self.assertRaises(ValueError):
                    PROVISION["check_layout"](self.parse(CURRENT), self.parse(bad))

    def test_parser_rejects_wrong_magic(self):
        with self.assertRaisesRegex(ValueError, "Invalid partition table entry"):
            PROVISION["partitions"](partition_entry("nvs", CURRENT["nvs"], magic=0x1234))

    def test_parser_rejects_duplicate_labels(self):
        entry = partition_entry("nvs", CURRENT["nvs"])
        with self.assertRaisesRegex(ValueError, "Duplicate partition label"):
            PROVISION["partitions"](entry + entry + b"\xff" * 32)


if __name__ == "__main__":
    unittest.main()
