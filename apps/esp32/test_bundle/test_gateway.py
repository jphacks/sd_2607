#!/usr/bin/env python3
"""Offline gateway transport/codec/PLC checks; C++11, ArduinoJson 7.4.2, mbedTLS 2.x, cbor2.

Uses real production helpers, synthetic sockets and public test keys only.
No Wi-Fi, PLC submission or public relay writes. All build outputs use a temp dir.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import test_bundle as fixtures

HERE = Path(__file__).resolve().parent
ESP32 = HERE.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cxx', default='g++')
    parser.add_argument('--arduinojson', type=Path, default=ESP32 / '.pio/libdeps/m5stack-basic/ArduinoJson/src')
    parser.add_argument('--mbedtls-include', type=Path)
    parser.add_argument('--mbedtls-library', type=Path)
    parser.add_argument('--no-sanitize', action='store_true')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='mesh-gateway-') as directory:
        temp = Path(directory)
        exe = temp / ('gateway.exe' if os.name == 'nt' else 'gateway')
        command = [args.cxx, '-std=c++11', '-Wall', '-Wextra', '-Werror', '-g',
                   '-I' + str(ESP32 / 'include'), '-I' + str(args.arduinojson)]
        if not args.no_sanitize:
            command += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
        if args.mbedtls_include:
            command += ['-I' + str(args.mbedtls_include)]
        command += [str(HERE / 'gateway_host.cpp'), str(ESP32 / 'src/MeshBundle.cpp'),
                    str(args.mbedtls_library) if args.mbedtls_library else '-lmbedcrypto', '-o', str(exe)]
        subprocess.run(command, check=True, timeout=90)
        subprocess.run([str(exe)], check=True, timeout=30)

        def run(mode, data, accepted=True):
            path = temp / 'input'
            path.write_bytes(data)
            result = subprocess.run([str(exe), mode, str(path)], capture_output=True, timeout=20)
            assert result.returncode == (0 if accepted else 1), result.stderr.decode()
            return result.stdout.decode()

        count = 0
        for case in json.loads((HERE / 'android-base64-fixtures.json').read_text())['cases']:
            exported = json.loads(run('export', bytes.fromhex(case['bundle_hex'])))
            assert exported == json.loads(case['event_json'])
            count += 1
        for kind in (1, 5, 8173):
            event, event_id, signing = fixtures.event(kind=kind)
            exported = json.loads(run('export', fixtures.bundle(event)))
            expected = json.loads(signing)
            assert exported == dict(id=event_id, pubkey=expected[1], created_at=expected[2], kind=kind,
                                    tags=expected[4], content=expected[5], sig=event[6].hex())
            event[6] = bytes(64)
            run('export', fixtures.bundle(event), False)
            count += 2
        # Locally bounded JSON expansion never silently edits an otherwise signed event.
        event, _, _ = fixtures.event(text='\x01' * 16000)
        run('export', fixtures.bundle(event), False)
        count += 1
        # Independent canonical DAG-CBOR / base32 oracle. Test operation signatures
        # are dummy bytes: real ECDSA acceptance is the configured Directory's job.
        op = dict(type='plc_operation', prev=None, sig='A' * 86, rotationKeys=['did:key:zTest'],
                  verificationMethods={'atproto': 'did:key:zRepo'}, alsoKnownAs=[],
                  services={'atproto_pds': {'type': 'AtprotoPersonalDataServer', 'endpoint': 'https://example.invalid'}})
        expected_did = 'did:plc:' + base64.b32encode(hashlib.sha256(fixtures.dumps(op)).digest()).decode().lower()[:24]
        for sort in (False, True):
            assert run('plc', json.dumps(op, sort_keys=sort).encode()).strip() == expected_did
            count += 1
        for bad in (dict(op, prev='previous'), dict(op, extra='unknown'), dict(op, sig=''), dict(op, rotationKeys=[])):
            run('plc', json.dumps(bad).encode(), False)
            count += 1
        print(f'PASS gateway signed-field/PLC oracle: {count} cases')


if __name__ == '__main__':
    main()
