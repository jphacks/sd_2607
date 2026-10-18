#!/usr/bin/env python3
"""wire-v1 independent Python CBOR/ECDSA/BIP340 oracle + actual ESP32 verifier.

Public TEST ONLY keys: root=7, atproto=5, Nostr=3. No network or device access.
Same mbedTLS 2.x host setup/flags as test_gateway.py; ASan/UBSan by default.
"""
import argparse
import base64
import copy
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import cbor2
import test_bundle as f

HERE = Path(__file__).resolve().parent
ESP32 = HERE.parent
NOW = 1792326896
DID = 'did:plc:' + 'a' * 24
BOT = 'did:plc:' + 'b' * 24
PREFIX = 'mosimo.oriom.dev:'
EXT = PREFIX + 'ext:dev.oriom.mosimo.defs#'


def utc(seconds):
    return datetime.fromtimestamp(seconds, timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')


def pub(secret):
    x, y = f.mul(secret)
    return bytes([2 | (y & 1)]) + x.to_bytes(32, 'big')


def ecdsa(body, secret, salt=b''):
    digest = f.sha(f.dumps(body))
    k = int.from_bytes(f.sha(b'wire-v1-test-only' + secret.to_bytes(32, 'big') + salt + digest), 'big') % f.N or 1
    r = f.mul(k)[0] % f.N
    s = pow(k, -1, f.N) * (int.from_bytes(digest, 'big') + r * secret) % f.N
    s = min(s, f.N - s)
    return r.to_bytes(32, 'big') + s.to_bytes(32, 'big')


def high_s(signature):
    return signature[:32] + (f.N - int.from_bytes(signature[32:], 'big')).to_bytes(32, 'big')


def cid(raw):
    return 'b' + base64.b32encode(bytes([1, 0x71, 0x12, 0x20]) + f.sha(raw)).decode().lower().rstrip('=')


def materials():
    scope = dict(orgIds=['org:jma-importer'], bsaf=[dict(botDid=BOT, types=['earthquake', 'weather'])])
    binding = dict(type='mndt.keyBinding', version=1, did=DID, atprotoPubkey=pub(5).hex(),
                   nostrPubkey=f.mul(3)[0].to_bytes(32, 'big').hex(), consent="Use this key pair for this DID's MNDT publications")
    cert = dict(type='mosimo.oriom.dev/identity-certificate', version=1, serial='01' * 16,
                rootId=f.sha(pub(7)).hex(), issuerPubkey=pub(7).hex(), subjectPubkey=pub(5).hex(),
                subjectDid=DID, orgId='org:jma-importer', orgName='TEST ONLY JMA importer', role='importer',
                permissions=['bsaf-import'], canDelegate=False, scope=scope,
                notBefore=utc(NOW - 3600), notAfter=utc(NOW + 86400))
    commit = dict(did=DID, version=3, data=cbor2.CBORTag(42, b'\0' + bytes([1, 0x71, 0x12, 0x20]) + f.sha(b'test-only-MST')),
                  rev='3' + '2' * 12, prev=None)
    bsaf = dict(tags=['bsaf:v1', 'type:earthquake', 'value:test', 'time:' + utc(NOW), 'target:jp', 'source:jma'],
                langs=['ja'], original=dict(uri='at://' + BOT + '/app.bsky.feed.post/test-only', cid=cid(b'original'), createdAt=utc(NOW - 120)))
    return dict(cert=cert, binding=binding, commit=commit, bsaf=bsaf, priority=dict(version=1, level=3))


def event(parts, cert_sig=None, at_sig=None, nostr_sig=None, commit_sig=None, chain_count=1, priority_count=1, extra_tag=None, identity_raw=None):
    binding = dict(declaration=parts['binding'], atprotoSig=base64.b64encode(at_sig or ecdsa(parts['binding'], 5)).decode(),
                   nostrSig=(nostr_sig or f.sign(f.sha(f.dumps(parts['binding'])))[1]).hex())
    cert = dict(body=parts['cert'], sig=(cert_sig or ecdsa(parts['cert'], 7)).hex())
    identity = dict(version=1, chain=[cert] * chain_count, binding=binding)
    signed_commit = dict(parts['commit'], sig=commit_sig or ecdsa(parts['commit'], 5))
    tags = [['t', 'mosimo.oriom.dev'], [PREFIX + 'version', '1'], [PREFIX + 'commit', f.b64encode(f.dumps(signed_commit))],
            [PREFIX + 'base', ''], [PREFIX + 'path', 'dev.oriom.mosimo.post/' + '3' + '2' * 12],
            [PREFIX + 'created-at', utc(NOW)], [PREFIX + 'delivery-expires-at', utc(NOW + 3600)],
            [EXT + 'identity', f.b64encode(identity_raw if identity_raw is not None else f.dumps(identity))],
            [EXT + 'bsafV1', f.b64encode(f.dumps(parts['bsaf']))]]
    tags += [[EXT + 'priority', f.b64encode(f.dumps(parts['priority']))] for _ in range(priority_count)]
    if extra_tag:
        tags.append(extra_tag)
    payload, event_id, signing = f.event('TEST ONLY - NOT DISASTER INFORMATION', tags)
    fields = json.loads(signing)
    obj = dict(id=event_id, pubkey=fields[1], created_at=fields[2], kind=fields[3], tags=fields[4], content=fields[5], sig=payload[6].hex())
    return obj, payload, cert, binding


def snapshot(**changes):
    body = dict(type='mosimo.oriom.dev/trust-snapshot', version=1, rootId=f.sha(pub(7)).hex(), sequence=10,
                issuedAt=utc(NOW - 60), validUntil=utc(NOW + 1800), revokedCertIds=[], revokedPubkeys=[])
    body.update(changes)
    return dict(body=body, sig=ecdsa(body, 7).hex())


def fixture():
    parts = materials()
    obj, payload, cert, binding = event(parts)
    snap = snapshot()
    return dict(test_only='Public test keys root=7, atproto=5, Nostr=3; NEVER configure as production trust',
                root_pubkey=pub(7).hex(), scope_cbor_hex=f.dumps(parts['cert']['scope']).hex(),
                selected_cbor_hex=f.dumps(parts['cert']['scope']['bsaf']).hex(), now=NOW,
                certificate_body_cbor_hex=f.dumps(cert['body']).hex(), cert_id=f.sha(f.dumps(cert['body'])).hex(), certificate=cert,
                binding_declaration_cbor_hex=f.dumps(binding['declaration']).hex(), binding_hash=f.sha(f.dumps(binding['declaration'])).hex(), binding=binding,
                snapshot_cbor_hex=f.dumps(snap).hex(), snapshot_body_cbor_hex=f.dumps(snap['body']).hex(),
                snapshot_body_hash=f.sha(f.dumps(snap['body'])).hex(), event=obj, payload_cbor_hex=f.dumps(payload).hex())


def restored_event(event):
    """Independent expected wire payload/record, retaining signed tag order."""
    typed = copy.deepcopy(event['tags'])
    fields = {tag[0]: tag[1] for tag in typed if len(tag) == 2}
    extensions = []
    commit_bytes = None
    for tag in typed:
        if len(tag) != 2:
            continue
        if tag[0] == PREFIX + 'version':
            tag[1] = 1
        elif tag[0] == PREFIX + 'commit' or tag[0].startswith(PREFIX + 'ext:'):
            raw = base64.b64decode(tag[1] + '=' * (-len(tag[1]) % 4))
            if tag[0] == PREFIX + 'commit':
                commit_bytes = raw
            else:
                extensions.append(dict({'$type': tag[0][len(PREFIX + 'ext:'):]}, **cbor2.loads(raw)))
            tag[1] = raw
    payload = [1, bytes.fromhex(event['pubkey']), event['created_at'], 1, typed, event['content'], bytes.fromhex(event['sig'])]
    record = dict({'$type': 'dev.oriom.mosimo.post'}, text=event['content'], createdAt=fields[PREFIX + 'created-at'],
                  deliveryExpiresAt=fields[PREFIX + 'delivery-expires-at'], extensions=extensions)
    return payload, record, commit_bytes


def verify_mst(case):
    """Audit supplied real MST blocks independently of the server's PASS flag.

    This is fixture validation, not an on-device MST inclusion-proof feature.
    """
    blocks = {block['cid']: bytes.fromhex(block['hex']) for block in case['mstBlocks']}
    for block_cid, raw in blocks.items():
        assert cid(raw) == block_cid
        assert f.dumps(cbor2.loads(raw)) == raw
    visited = set()
    entries = {}

    def link(value):
        assert isinstance(value, cbor2.CBORTag) and value.tag == 42 and value.value[0] == 0
        raw = value.value[1:]
        assert len(raw) == 36 and raw[:4] == bytes([1, 0x71, 0x12, 0x20])
        return 'b' + base64.b32encode(raw).decode().lower().rstrip('=')

    def walk(pointer):
        assert pointer not in visited and len(visited) < 8
        visited.add(pointer)
        node = cbor2.loads(blocks[pointer])
        assert set(node) == {'l', 'e'}
        if node['l'] is not None:
            walk(link(node['l']))
        previous = b''
        for entry in node['e']:
            assert set(entry) == {'p', 'k', 'v', 't'} and 0 <= entry['p'] <= len(previous)
            key = previous[:entry['p']] + entry['k']
            assert key.decode() not in entries
            entries[key.decode()] = link(entry['v'])
            if entry['t'] is not None:
                walk(link(entry['t']))
            previous = key

    walk(case['mstRoot'])
    assert entries == {entry['key']: entry['cid'] for entry in case['repositoryEntries']}
    assert entries[dict(case['event']['tags'])[PREFIX + 'path']] == case['recordCid']
    assert link(cbor2.loads(bytes.fromhex(case['commitHex']))['data']) == case['mstRoot']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cxx', default='g++')
    parser.add_argument('--mbedtls-include', type=Path)
    parser.add_argument('--mbedtls-library', type=Path)
    parser.add_argument('--no-sanitize', action='store_true')
    parser.add_argument('--generate', type=Path, help='write a public cross-implementation test fixture, then exit')
    args = parser.parse_args()
    golden = fixture()
    if args.generate:
        args.generate.write_text(json.dumps(golden, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        return
    fixed = HERE / 'wire-v1-fixture.json'
    if fixed.exists():
        assert json.loads(fixed.read_text(encoding='utf-8')) == golden, 'public fixture drift'
    with tempfile.TemporaryDirectory(prefix='mesh-ingress-') as directory:
        temp = Path(directory)
        exe = temp / ('ingress.exe' if os.name == 'nt' else 'ingress')
        command = [args.cxx, '-std=c++11', '-Wall', '-Wextra', '-Werror', '-g', '-I' + str(ESP32 / 'include')]
        if not args.no_sanitize:
            command += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
        if args.mbedtls_include:
            command += ['-I' + str(args.mbedtls_include)]
        command += [str(HERE / 'ingress_host.cpp'), str(ESP32 / 'src/MeshBundle.cpp'), str(ESP32 / 'src/MeshIngress.cpp'),
                    str(args.mbedtls_library) if args.mbedtls_library else '-lmbedcrypto', '-o', str(exe)]
        subprocess.run(command, check=True, timeout=90)
        subprocess.run([str(exe)], check=True, timeout=30)
        scope = golden['scope_cbor_hex']
        selected = golden['selected_cbor_hex']
        root = golden['root_pubkey']
        count = 0

        def run(obj=None, snap=None, accepted=False, trust=True, selection=selected, root_key=root, now=NOW, mode='authorize', previous=None, conflict=None, raw_snapshot=None, root_scope=scope, min_sequence=1, successive=None):
            nonlocal count
            count += 1
            (temp / 'snapshot').write_bytes(raw_snapshot if raw_snapshot is not None else f.dumps(snap or snapshot()))
            (temp / 'event').write_text(json.dumps(obj or golden['event'], ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
            if previous:
                (temp / 'snapshot').write_bytes(f.dumps(previous))
                (temp / 'event').write_bytes(f.dumps(snap))
            cmd = [str(exe), mode, root_key, root_scope, selection, str(temp / 'snapshot'), str(temp / 'event'), str(now), '1' if trust else '0']
            if conflict or successive:
                (temp / 'next').write_text(json.dumps(conflict or successive), encoding='utf-8')
                cmd += [str(temp / 'next')]
            if min_sequence != 1:
                cmd += ['--min-sequence', str(min_sequence)]
            result = subprocess.run(cmd, capture_output=True, timeout=30)
            assert result.returncode == (0 if accepted else 1), (count, result.returncode, result.stderr.decode(), result.stdout.decode())
            return result.stdout.decode()

        output = run(accepted=True).splitlines()
        assert json.loads(output[0]) == golden['event']
        assert output[2] == golden['payload_cbor_hex']
        assert output[1].split() == ['3', golden['certificate']['body']['rootId'], golden['cert_id'], golden['snapshot_body_hash']]
        for priority in (None, dict(version=1, level=9), dict(version=1, level=3, extra=True)):
            parts = materials()
            if priority is not None:
                parts['priority'] = priority
            obj = event(parts, priority_count=0 if priority is None else 1)[0]
            assert run(obj, accepted=True).splitlines()[1].split()[0] == '1'
        assert run(event(materials(), priority_count=2)[0], accepted=True).splitlines()[1].split()[0] == '1'
        odd_root = f.N - 7
        parts = materials(); odd_root_pub = pub(odd_root).hex(); odd_root_id = f.sha(pub(odd_root)).hex()
        parts['cert'].update(rootId=odd_root_id, issuerPubkey=odd_root_pub)
        odd_snapshot = snapshot(rootId=odd_root_id); odd_snapshot['sig'] = ecdsa(odd_snapshot['body'], odd_root).hex()
        run(event(parts, cert_sig=ecdsa(parts['cert'], odd_root))[0], snap=odd_snapshot, root_key=odd_root_pub, accepted=True)
        odd_leaf = f.N - 5
        parts = materials(); parts['cert']['subjectPubkey'] = pub(odd_leaf).hex(); parts['binding']['atprotoPubkey'] = pub(odd_leaf).hex()
        run(event(parts, at_sig=ecdsa(parts['binding'], odd_leaf), commit_sig=ecdsa(parts['commit'], odd_leaf))[0], accepted=True)
        reused = materials(); reused['cert']['subjectPubkey'] = pub(3).hex(); reused['binding']['atprotoPubkey'] = pub(3).hex()
        run(event(reused, at_sig=ecdsa(reused['binding'], 3), commit_sig=ecdsa(reused['commit'], 3))[0])
        run(trust=False)
        run(mode='disabled')
        run(mode='floor')
        run(now=NOW - 1)
        run(root_key='02' + 'ff' * 32)
        run(root_key=pub(11).hex())
        run(selection=f.dumps([dict(botDid=BOT, types=['weather'])]).hex())
        bad = copy.deepcopy(golden['event']); bad['sig'] = '00' * 64; run(bad)
        bad = copy.deepcopy(golden['event']); bad['id'] = '00' * 32; run(bad)
        for section, key, value in [
            ('cert', 'rootId', '00' * 32), ('cert', 'issuerPubkey', pub(11).hex()),
            ('cert', 'subjectDid', 'did:plc:' + 'c' * 24), ('cert', 'subjectPubkey', pub(11).hex()),
            ('cert', 'subjectPubkey', root), ('cert', 'canDelegate', True), ('cert', 'role', 'issuer'),
            ('cert', 'notBefore', utc(NOW + 1)), ('cert', 'notAfter', utc(NOW)),
            ('cert', 'notAfter', utc(NOW + 400 * 86400)), ('cert', 'orgName', 'bad\nname'),
            ('cert', 'permissions', ['bsaf-import', 'root-add']), ('cert', 'extra', True),
            ('binding', 'did', 'did:plc:' + 'c' * 24), ('binding', 'nostrPubkey', '00' * 32),
            ('binding', 'atprotoPubkey', root), ('binding', 'type', 'mosimo.keyBinding'), ('binding', 'extra', True),
            ('commit', 'did', 'did:plc:' + 'c' * 24), ('commit', 'version', 2), ('commit', 'extra', True)]:
            parts = materials(); parts[section][key] = value; run(event(parts)[0])
        parts = materials(); parts['cert']['scope']['bsaf'][0]['types'] += ['zz-escalated']; run(event(parts)[0])
        parts = materials(); parts['bsaf']['tags'][1] = 'type:unselected'; run(event(parts)[0])
        parts = materials(); parts['bsaf']['tags'] += ['type:earthquake']; run(event(parts)[0])
        parts = materials(); parts['bsaf']['original']['uri'] += '?query=forbidden'; run(event(parts)[0])
        parts = materials(); parts['bsaf']['original']['uri'] = 'at://not-a-did/app.bsky.feed.post/x'; run(event(parts)[0])
        parts = materials(); parts['bsaf']['original']['cid'] = 'bnotcid'; run(event(parts)[0])
        parts = materials(); parts['bsaf']['langs'] = ['123']; run(event(parts)[0])
        identity = dict(version=1, chain=[golden['certificate']], binding=golden['binding'])
        raw_identity = f.dumps(identity)
        run(event(materials(), identity_raw=raw_identity + b'\0')[0])
        run(event(materials(), identity_raw=b'\xa4' + raw_identity[1:] + f.dumps('version') + b'\x01')[0])
        run(event(materials(), extra_tag=[EXT + 'not-an-nsid', f.b64encode(f.dumps({}))])[0])
        run(event(materials(), chain_count=2)[0]); run(event(materials(), chain_count=5)[0])
        run(event(materials(), cert_sig=bytes(64))[0])
        run(event(materials(), cert_sig=high_s(ecdsa(materials()['cert'], 7)))[0])
        run(event(materials(), cert_sig=b'\x30' + bytes(69))[0])
        run(event(materials(), at_sig=bytes(64))[0])
        run(event(materials(), at_sig=high_s(ecdsa(materials()['binding'], 5)))[0])
        run(event(materials(), nostr_sig=bytes(64))[0])
        run(event(materials(), commit_sig=high_s(ecdsa(materials()['commit'], 5)))[0])
        run(event(materials(), extra_tag=[PREFIX + 'version', '1'])[0])
        leaf = golden['cert_id']
        for snap in [snapshot(issuedAt=utc(NOW + 1)), snapshot(validUntil=utc(NOW)),
                     snapshot(validUntil=utc(NOW + 4000)), snapshot(revokedCertIds=[leaf]),
                     snapshot(revokedPubkeys=[pub(5).hex()]), snapshot(revokedPubkeys=[root]), snapshot(version=2),
                     snapshot(extra=True), snapshot(rootId='00' * 32)]:
            run(snap=snap)
        normal_snapshot = snapshot()
        run(raw_snapshot=b'\xa2' + f.dumps('body') + f.dumps(normal_snapshot['body']) + f.dumps('sig') + f.dumps(normal_snapshot['sig']))
        run(snap=snapshot(revokedCertIds=['ff' * 32, '00' * 32]))
        run(snap=snapshot(revokedCertIds=['00' * 32] * 2))
        run(snap=snapshot(revokedCertIds=[hashlib.sha256(str(i).encode()).hexdigest() for i in range(33)]))
        bad_snapshot = snapshot(); bad_snapshot['sig'] = high_s(bytes.fromhex(bad_snapshot['sig'])).hex(); run(snap=bad_snapshot)
        run(mode='observe', previous=snapshot(), snap=snapshot(sequence=9))
        run(mode='observe', previous=snapshot(), snap=snapshot(validUntil=utc(NOW + 1700)))
        run(mode='observe', previous=snapshot(), snap=snapshot(sequence=11, issuedAt=utc(NOW - 61)))
        run(mode='observe', previous=snapshot(revokedCertIds=[leaf]), snap=snapshot(sequence=11))
        run(mode='observe', previous=snapshot(revokedPubkeys=[root]), snap=snapshot(sequence=11))
        run(mode='observe', previous=snapshot(), snap=snapshot(sequence=11, revokedCertIds=[leaf]), accepted=True)
        same = snapshot(); same['sig'] = ecdsa(same['body'], 7, salt=b'resign').hex()
        run(mode='observe', previous=snapshot(), snap=same, accepted=True)
        parts = materials(); parts['cert']['orgName'] = 'Conflicting same-serial certificate'
        run(accepted=True, conflict=event(parts)[0])
        # Public fixture from the independent Android worker's Node generator
        # (apps/android/wire-v1-fixture.mjs, @ipld/dag-cbor + @noble/curves).
        # Read the frozen local copy only: no external tree writes or dependency.
        peer_path = HERE / 'wire-v1-node-fixture.json'
        if peer_path.exists():
            peer = json.loads(peer_path.read_text(encoding='utf-8'))
            anchor = peer['config']['roots'][0]
            grouped = {}
            for choice in peer['config']['selected']:
                grouped.setdefault(choice['botDid'], set()).add(choice['type'])
            peer_selected = [dict(botDid=bot, types=sorted(types)) for bot, types in sorted(grouped.items())]
            peer_output = run(peer['event'], accepted=True, raw_snapshot=bytes.fromhex(peer['snapshotHex']),
                              root_key=anchor['rootPubkey'], root_scope=f.dumps(anchor['scope']).hex(),
                              selection=f.dumps(peer_selected).hex(), now=peer['event']['created_at'] + 1).splitlines()
            assert json.loads(peer_output[0]) == peer['event']
            assert peer_output[1].split() == ['3', anchor['rootId'], peer['certId'], peer['snapshotBodyHash']]
            assert f.dumps(peer['certificate']['body']).hex() == peer['certificateBodyHex']
            snapshot_body = cbor2.loads(bytes.fromhex(peer['snapshotHex']))['body']
            assert f.dumps(snapshot_body).hex() == peer['snapshotBodyHex']
            assert f.sha(f.dumps(snapshot_body)).hex() == peer['snapshotBodyHash']
            peer_payload, record, commit_bytes = restored_event(peer['event'])
            assert commit_bytes.hex() == peer['commitHex']
            assert f.dumps(peer_payload).hex() == peer_output[2]
            assert f.dumps(record).hex() == peer['recordHex']
            print('PASS independent Node fixture: certificate/snapshot/record/commit bytes, hashes, signatures, exact mesh payload')
        server = json.loads((HERE / 'wire-v1-server-fixture.json').read_text(encoding='utf-8'))
        assert server['testOnly'] is True and len(server['events']) == 2
        anchor = server['root']
        options = dict(root_key=anchor['rootPubkey'], root_scope=f.dumps(anchor['scope']).hex(),
                       selection=f.dumps(server['selection']).hex(), raw_snapshot=bytes.fromhex(server['snapshotHex']),
                       now=server['nowSeconds'], min_sequence=anchor['minSequence'])
        previous = None
        expected_entries = {}
        for index, case in enumerate(server['events']):
            lines = run(case['event'], accepted=True, **options).splitlines()
            assert json.loads(lines[0]) == case['event']
            assert lines[1].split() == [str(case['authorization']['effectivePriority']), anchor['rootId'], server['certId'], server['snapshotBodyHash']]
            payload, record, commit_bytes = restored_event(case['event'])
            assert f.dumps(payload).hex() == lines[2] == case['payloadHex']
            assert f.dumps(record).hex() == case['recordHex']
            assert commit_bytes.hex() == case['commitHex']
            assert cid(commit_bytes) == case['commitCid'] and cid(f.dumps(record)) == case['recordCid']
            assert cid(bytes.fromhex(case['originalRecordHex'])) == case['originalRecordCid']
            assert case['base'] == previous
            assert dict(case['event']['tags'])[PREFIX + 'base'] == (previous or '')
            expected_entries[dict(case['event']['tags'])[PREFIX + 'path']] = case['recordCid']
            assert {entry['key']: entry['cid'] for entry in case['repositoryEntries']} == expected_entries
            assert len(case['repositoryEntries']) == index + 1
            verify_mst(case)
            previous = case['commitCid']
            print(f"PASS actual server event {index + 1}: JSON={case['bounds']['eventJsonBytes']} payload={len(f.dumps(payload))}, valid MST with {index + 1} record(s), base={'nonempty' if case['base'] else 'initial'}")
        sequence = run(server['events'][0]['event'], accepted=True, mode='successive',
                       successive=server['events'][1]['event'], **options)
        assert 'PASS successive real-server events' in sequence
        print('PASS actual server events sequentially across persisted snapshot/serial state')
        print(f'PASS wire-v1 ingress: {count} signed oracle/negative/state cases')


if __name__ == '__main__':
    main()
