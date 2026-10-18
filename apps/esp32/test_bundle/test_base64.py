#!/usr/bin/env python3
"""Regression: Android signs unpadded STANDARD Base64 for typed CBOR byte tags.

python3 test_base64.py --host /path/to/mesh-bundle-host
Written before the codec fix: the padded codec rejects commit/1 with
"invalid event signature" even though its BP CRC and BIP340 signature are valid.
Android fixtures come from actual Kotlin B64/EventCodec/MeshEngine, not Python.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
import test_bundle as fixtures

# Fixed spellings, not inferred from the production C++ encoder or fixture
# encoder. '+' and '/' distinguish standard Base64 from URL-safe Base64.
CASES = [(b"\xfb", "+w"), (b"\xff\xff", "//8"),
         (b"\x00\x01\xfe", "AAH+"), (b"\x00\x01\xfe\xff", "AAH+/w")]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", type=Path, required=True)
    parser.add_argument("--kotlin-only", action="store_true")
    args = parser.parse_args()
    host = str(args.host.resolve())
    checked = 0
    with tempfile.TemporaryDirectory(prefix="mesh-base64-") as directory:
        path = Path(directory) / "input.bin"
        sent = Path(directory) / "sent.bin"

        def check(label, wire, expected_id, payload):
            nonlocal checked
            path.write_bytes(wire)
            result = subprocess.run([host, str(path), "17", str(sent)], capture_output=True, text=True)
            assert result.returncode == 0, (label, result.returncode, result.stderr.strip())
            assert result.stdout.split()[0] == expected_id
            forwarded = subprocess.run([host, str(sent)], capture_output=True, text=True)
            assert forwarded.returncode == 0 and forwarded.stdout.split()[0] == expected_id
            assert fixtures.cbor2.loads(sent.read_bytes())[-1][4] == payload
            checked += 1

        android = json.loads((fixtures.HERE / "android-base64-fixtures.json").read_text(encoding="utf-8"))
        assert len(android["cases"]) == 8
        for case in android["cases"]:
            raw = bytes.fromhex(case["bytes_hex"])
            assert (raw, case["tag_value"]) in CASES
            signing = bytes.fromhex(case["signing_json_hex"])
            assert hashlib.sha256(signing).hexdigest() == case["event_id"]
            assert json.loads(signing)[4][1] == [case["tag_name"], case["tag_value"]]
            payload = bytes.fromhex(case["payload_hex"])
            assert fixtures.cbor2.loads(payload)[4][1][1] == raw
            wire = bytes.fromhex(case["bundle_hex"])
            assert fixtures.cbor2.loads(wire)[-1][4] == payload
            check(("actual Kotlin", case["tag_name"], len(raw)), wire, case["event_id"], payload)
        if not args.kotlin_only:
            for name in (fixtures.PREFIX + "commit", fixtures.PREFIX + "ext:regression"):
                for raw, spelling in CASES:
                    tags = [[fixtures.DEADLINE, "2030-01-01T00:00:00Z"], [name, spelling]]
                    payload, expected_id, signing_json = fixtures.event("unpadded regression", tags)
                    assert json.loads(signing_json)[4][1][1] == spelling and "=" not in spelling
                    # Force the wire byte string independently of Python's EventCodec
                    # conversion, which originally shared the C++ padding assumption.
                    payload[4][1][1] = raw
                    check(("Python", name, len(raw)), fixtures.bundle(payload), expected_id, fixtures.dumps(payload))
    print(f"PASS: {checked} signed unpadded typed-byte cases (8 actual Kotlin), including forwarded IDs/payloads")


if __name__ == "__main__":
    main()
