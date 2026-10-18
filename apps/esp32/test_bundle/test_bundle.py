#!/usr/bin/env python3
"""Independent CBOR/CRC/BIP340 fixture oracle; requires cbor2 >= 5.6.

python3 test_bundle.py --generate fixtures.json
python3 test_bundle.py --host ./host

The tiny, slow Python signer is TEST ONLY (fixed secret and nonce, no secrets).
"""
import argparse
import base64
import copy
import csv
import hashlib

import json
from pathlib import Path
import random
import subprocess
import sys
import tempfile

import cbor2

HERE = Path(__file__).resolve().parent
P = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F
N = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
G = (0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798,
     0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8)
PREFIX = "mosimo.oriom.dev:"
DEADLINE = PREFIX + "delivery-expires-at"
DEST = [1, "//mosimo.oriom.dev/~post"]


def sha(b):
    return hashlib.sha256(b).digest()


def add(a, b):
    if a is None:
        return b
    if b is None:
        return a
    x, y = a
    u, v = b
    if x == u and (y != v or y == 0):
        return None
    m = ((3 * x * x) * pow(2 * y, P - 2, P) if a == b
         else (v - y) * pow((u - x) % P, P - 2, P)) % P
    rx = (m * m - x - u) % P
    return rx, (m * (x - rx) - y) % P


def mul(k, point=G):
    result = None
    while k:
        if k & 1:
            result = add(result, point)
        point = add(point, point)
        k >>= 1
    return result


def tagged(tag, value):
    t = sha(tag.encode())
    return sha(t + t + value)


def sign(message):
    secret = 3
    pub = mul(secret)
    if pub[1] & 1:
        secret = N - secret
    px = pub[0].to_bytes(32, "big")
    aux = tagged("BIP0340/aux", bytes(32))
    t = bytes(a ^ b for a, b in zip(secret.to_bytes(32, "big"), aux))
    nonce = int.from_bytes(tagged("BIP0340/nonce", t + px + message), "big") % N
    assert nonce
    r = mul(nonce)
    if r[1] & 1:
        nonce = N - nonce
    rx = r[0].to_bytes(32, "big")
    e = int.from_bytes(tagged("BIP0340/challenge", rx + px + message), "big") % N
    return px, rx + ((nonce + e * secret) % N).to_bytes(32, "big")


def dumps(value):
    return cbor2.dumps(value, canonical=True)


def b64encode(raw):
    return base64.b64encode(raw).decode().rstrip("=")


def event(text='日本語 🌐 "quote" \\ slash/\n\b\t\f\r\x00\x01\x1f\u2028', tags=None):
    if tags is None:
        tags = [["t", "mosimo.oriom.dev"], [PREFIX + "version", "1"],
                [PREFIX + "commit", b64encode(b"\x00\x01\xfe\xff")],
                [PREFIX + "ext:unknown", b64encode(dumps({"x": True}))],
                ["unknown", "", "unchanged", "日本語"],
                [DEADLINE, "2030-10-18T12:34:56.123456789Z"]]
    pub = mul(3)[0].to_bytes(32, "big")
    canonical = json.dumps([0, pub.hex(), 1792326896, 1, tags, text],
                           ensure_ascii=False, separators=(",", ":")).encode()
    digest = sha(canonical)
    pub, sig = sign(digest)
    typed = copy.deepcopy(tags)
    for tag in typed:
        if len(tag) != 2:
            continue
        name = tag[0]
        if name == PREFIX + "version" and tag[1] == "1":
            tag[1] = 1
        elif name == PREFIX + "commit" or (name.startswith(PREFIX + "ext:") and len(name) > len(PREFIX + "ext:")):
            try:
                if any(c not in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/" for c in tag[1]):
                    continue  # Android B64.decode rejects padding and URL-safe characters.
                raw = base64.b64decode(tag[1] + "=" * (-len(tag[1]) % 4), validate=True)
                if b64encode(raw) == tag[1]:
                    tag[1] = raw
            except ValueError:
                pass
    return [1, pub, 1792326896, 1, typed, text, sig], digest.hex(), canonical


def crc(data, kind):
    value = 0xFFFF if kind == 1 else 0xFFFFFFFF
    poly = 0x8408 if kind == 1 else 0x82F63B78
    for b in data:
        value ^= b
        for _ in range(8):
            value = (value >> 1) ^ (poly if value & 1 else 0)
    return (value ^ (0xFFFF if kind == 1 else 0xFFFFFFFF)).to_bytes(2 if kind == 1 else 4, "big")


def block(fields, kind=2, indefinite=False):
    fields = copy.deepcopy(fields)
    if kind:
        fields.append(bytes(2 if kind == 1 else 4))
    raw = (b"\x9f" + b"".join(dumps(x) for x in fields) + b"\xff") if indefinite else dumps(fields)
    if kind:
        size = 2 if kind == 1 else 4
        offset = len(raw) - size - int(indefinite)
        value = crc(raw, kind)
        raw = raw[:offset] + value + raw[offset + size:]
    return raw


def bundle(payload, age=0, hop=0, limit=8, lifetime=86400000, kind=2,
           extra=(), primary=None, indefinite_blocks=False, definite_outer=False,
           payload_bytes=None):
    if primary is None:
        primary = [7, 4, kind, DEST, [1, "//node.example/post"], [1, 0], [0, 1], lifetime]
    blocks = [block(primary, kind, indefinite_blocks)]
    blocks += list(extra)
    blocks += [block([10, 2, 0, kind, dumps([limit, hop])], kind, indefinite_blocks),
               block([7, 3, 0, kind, dumps(age)], kind, indefinite_blocks),
               block([1, 1, 0, kind, dumps(payload) if payload_bytes is None else payload_bytes], kind, indefinite_blocks)]
    return (bytes([0x80 + len(blocks)]) if definite_outer else b"\x9f") + b"".join(blocks) + (b"" if definite_outer else b"\xff")


def fixture():
    payload, event_id, canonical = event()
    valid = bundle(payload)
    bad = copy.deepcopy(payload)
    bad[6] = bad[6][:-1] + bytes([bad[6][-1] ^ 1])
    return {"description": "Android EventCodec v1 / MeshEngine unknown-clock profile; fixed test key 3",
            "event_id": event_id, "text": payload[5], "signing_json_hex": canonical.hex(),
            "payload_hex": dumps(payload).hex(), "valid_bundle_hex": valid.hex(),
            "tampered_signature_bundle_hex": bundle(bad).hex()}


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("--host", type=Path)
    parser.add_argument("--generate", type=Path)
    args = parser.parse_args()
    assert crc(b"123456789", 1).hex() == "906e"
    assert crc(b"123456789", 2).hex() == "e3069283"
    pub, signature = sign(bytes(32))
    assert pub.hex() == "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"
    assert signature.hex() == ("e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca821525"
                               "f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0")
    fixed = fixture()
    if args.generate:
        args.generate.write_text(json.dumps(fixed, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print("Generated", args.generate)
    if args.host is None:
        assert args.generate, "supply --host or --generate"
        return
    host = str(args.host.resolve())
    vectors = list(csv.DictReader((HERE / "bip340-vectors.csv").open()))
    checked = 0
    for v in vectors:
        if len(v["message"]) != 64:  # Nostr signs a 32-byte SHA-256 digest only.
            continue
        result = subprocess.run([host, "bip340", v["public key"], v["signature"], v["message"]], capture_output=True)
        expected = v["verification result"] == "TRUE"
        assert result.returncode == (0 if expected else 1), (v["index"], result.stderr)
        checked += 1
    assert checked == 15
    assert fixed == json.loads((HERE / "fixtures.json").read_text(encoding="utf-8")), "regenerate fixtures"
    cases = 0
    with tempfile.TemporaryDirectory() as temp:
        path, forwarded = Path(temp) / "input.bin", Path(temp) / "forwarded.bin"

        def run(raw, valid=True, residence=None):
            nonlocal cases
            path.write_bytes(raw)
            cmd = [host, str(path)]
            if residence is not None:
                cmd += [str(residence), str(forwarded)]
            result = subprocess.run(cmd, capture_output=True, text=True)
            assert result.returncode in (0, 1), (result.returncode, result.stderr)
            if valid is not None:
                assert (result.returncode == 0) == valid, (valid, result.stdout, result.stderr, raw.hex()[:120])
            cases += 1
            return result.stdout.strip().split()

        payload, event_id, _ = event()
        valid = bytes.fromhex(fixed["valid_bundle_hex"])
        for kind in (1, 2):
            for indef in (False, True):
                raw = bundle(payload, kind=kind, indefinite_blocks=indef, definite_outer=not indef)
                fields = run(raw, residence=1234)
                assert fields[:5] == [event_id, "0", "86400000", "0", "8"]
                assert bytes.fromhex(fields[5]).decode() == payload[5]
                sent = forwarded.read_bytes()
                fields = run(sent)
                assert fields[:5] == [event_id, "1234", "86400000", "1", "8"]
                assert cbor2.loads(sent)[-1][4] == dumps(payload)
                assert cbor2.loads(sent)[0] == cbor2.loads(raw)[0]
        run(bytes.fromhex(fixed["tampered_signature_bundle_hex"]), False)
        changed = copy.deepcopy(payload)
        changed[5] += "tampered"
        run(bundle(changed), False)
        changed = copy.deepcopy(payload)
        changed[1] = P.to_bytes(32, "big")
        run(bundle(changed), False)
        for age, hop, limit, lifetime in [(100, 0, 8, 100), (0, 9, 10, 100), (0, 1, 0, 100),
                                          (0, 0, 256, 100), (0, 0, 8, 0), (0, 0, 8, 86400001)]:
            run(bundle(payload, age=age, hop=hop, limit=limit, lifetime=lifetime), False)
        run(bundle(payload, hop=8), True)
        run(bundle(payload, hop=8), False, residence=0)
        run(bundle(payload, hop=1, limit=1), False, residence=0)
        for residence in (100, 86400000, 2**64 - 1):
            run(bundle(payload, lifetime=100), False, residence=residence)
        run(bundle(payload, lifetime=100, age=99), True, residence=0)
        run(bundle(payload, lifetime=100, age=99), False, residence=1)
        run(valid + b"\x00", False)
        run(b"", False)
        run(bytes(24001), False)
        run(b"\x9f\x9b" + bytes.fromhex("7fffffffffffffff"), False)
        for offset in sorted(set(range(min(40, len(valid)))) | {len(valid) // 2, len(valid) - 1}):
            run(valid[:offset], False)
        for field in (0, 1, 2, 3, 4, 5, 6, 7):
            primary = [7, 4, 2, DEST, [1, "//node.example/post"], [1, 0], [0, 1], 86400000]
            primary[field] = [8, 5, 0, [1, "//wrong/~post"], [1, "bad"], [99, 0], [-1, 0], -1][field]
            run(bundle(payload, primary=primary), False)
        run(bundle(payload, kind=0), False)
        # Canonical blocks may omit CRC under the Android profile; primary may not.
        primary = block([7, 4, 2, DEST, [1, "//node.example/post"], [1, 0], [0, 1], 86400000])
        no_crc = (b"\x9f" + primary + block([10, 2, 0, 0, dumps([8, 0])], 0)
                  + block([7, 3, 0, 0, dumps(0)], 0) + block([1, 1, 0, 0, dumps(payload)], 0) + b"\xff")
        run(no_crc, residence=256)
        run(forwarded.read_bytes())
        many = [block([99, i, 0, 2, b""]) for i in range(4, 65)]
        run(bundle(payload, extra=many))
        run(bundle(payload, extra=many + [block([99, 65, 0, 2, b""])]), False)
        for source in ("//node.example", "///post", "//node.example?query", "//node.example/%zz"):
            fields = [7, 4, 2, DEST, [1, source], [1, 0], [0, 1], 86400000]
            run(bundle(payload, primary=fields), False)
        extra = block([99, 5, 0, 0, b"preserve"], 0)
        run(bundle(payload, extra=[extra]), residence=0)
        assert cbor2.loads(forwarded.read_bytes())[1] == cbor2.loads(extra)
        for block_type in (11, 12):
            run(bundle(payload, extra=[block([block_type, 5, 0, 2, b"x"])]), False)
        for extra in ([block([99, 5, 4, 2, b"x"])], [block([7, 5, 0, 2, dumps(0)])],
                      [block([10, 5, 0, 2, dumps([8, 0])])], [block([99, 2, 0, 2, b"x"])],
                      [block([1, 1, 0, 2, dumps(payload)])]):
            run(bundle(payload, extra=extra), False)
        extras = [block([6, 4, 0, 2, dumps([1, "//previous.example/node"])]),
                  block([99, 5, 16, 2, b"discard"])]
        run(bundle(payload, extra=extras), residence=0)
        assert len(cbor2.loads(forwarded.read_bytes())) == 4
        run(bundle(payload, extra=extras[:1] * 2), False)
        for fields in ([1, payload[1], payload[2], payload[3], [], payload[5], payload[6]],
                       payload + [0]):
            run(bundle(fields), False)
        for date in ("2030-02-30T00:00:00Z", "2030-13-01T00:00:00Z", "2030-01-01T25:00:00Z",
                     "2030-01-01T00:60:00Z", "2030-01-01T00:00:61Z", "2030-01-01T00:00:00+00:00",
                     "2030-01-01T00:00:00.Z", "2030-01-01T00:00:00.1234567890Z"):
            altered = copy.deepcopy(payload)
            altered[4][-1][1] = date
            run(bundle(altered), False)
        # A past deadline is accepted without a trusted clock, as on Android.
        past, _, _ = event(tags=[[DEADLINE, "2000-02-29T00:00:00Z"]])
        run(bundle(past), True)
        for date in ("2000-01-01T24:00:00Z", "2000-01-01T23:59:60Z"):
            signed, _, _ = event(tags=[[DEADLINE, date]])
            run(bundle(signed))
        strings, _, _ = event(tags=[[DEADLINE, "2030-01-01T00:00:00Z"],
                                   [PREFIX + "commit", "not canonical base64"],
                                   [PREFIX + "ext:", "not a typed extension"],
                                   ["", "empty names preserved"]])
        run(bundle(strings))
        # Padded or noncanonical strings are not typed by Android EventCodec;
        # preserve their exact signed spelling instead of silently normalizing.
        for spelling in ("AA==", "/w==", "//8=", "+w==", "AB"):
            signed, _, _ = event(tags=[[DEADLINE, "2030-01-01T00:00:00Z"],
                                       [PREFIX + "commit", spelling], [PREFIX + "ext:regression", spelling]])
            assert signed[4][1][1] == spelling and signed[4][2][1] == spelling
            run(bundle(signed))
        for tags in ([[DEADLINE, "2030-01-01T00:00:00Z"]] + [["x"]] * 256,
                     [[DEADLINE, "2030-01-01T00:00:00Z"], ["x"] * 33]):
            signed, _, _ = event(tags=tags)
            run(bundle(signed), False)
        duplicate, _, _ = event(tags=[[DEADLINE, "2030-01-01T00:00:00Z"]] * 2)
        run(bundle(duplicate), False)
        long_content, _, _ = event("x" * 16385)
        run(bundle(long_content), False)
        # Minimal encoding and UTF-8 are mandatory inside signed EventCodec CBOR.
        encoded = dumps(payload)
        run(bundle(payload, payload_bytes=b"\x98\x07" + encoded[1:]), False)
        run(bundle(payload, payload_bytes=encoded + b"\x00"), False)
        altered = copy.deepcopy(payload)
        altered[4][0][1] = b"not an allowed byte tag"
        run(bundle(altered), False)
        altered = copy.deepcopy(payload)
        altered[4][0] = []
        run(bundle(altered), False)
        run(bundle(payload, payload_bytes=encoded.replace("日本語".encode(), b"\xff" * 9)), False)
        # Exactly-full frames are accepted; age growth must not create >24000-byte frames.
        overhead = len(bundle(payload, extra=[block([99, 5, 0, 2, b"x" * 23000])])) - 23000
        full = bundle(payload, extra=[block([99, 5, 0, 2, b"x" * (24000 - overhead)])])
        assert len(full) == 24000
        run(full)
        run(full, False, residence=256)
        # Deterministic byte mutations: either reject or remain valid, never crash.
        rng = random.Random(340)
        for _ in range(100):
            altered = bytearray(valid)
            pos = rng.randrange(len(altered))
            altered[pos] ^= 1 << rng.randrange(8)
            run(bytes(altered), None)
    print(f"PASS: {checked} official BIP340 vectors, {cases} bundle/forward/malformed checks", flush=True)
    subprocess.run([sys.executable, str(HERE / "test_base64.py"), "--host", host], check=True)


if __name__ == "__main__":
    main()
