"""Explicit, resumable physical-device test. Evidence/ciphertext stay in ignored .gradle.

Never clears data, decrypts keys, retries a publish click, or resets the ESP32.
Run one phase at a time; relay absence must be recorded before `post`.
"""
import argparse
import datetime as dt
import hashlib
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE / '.gradle' / 'production-20261019'
STATE = OUT / 'state.json'
PACKAGE = 'org.mndt.demo'
DEVICES = {'ASUS': 'T4AIOC732121JRB', 'OPPO': '4f4ebc6f'}


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def adb(label, *args, binary=False):
    result = subprocess.check_output(['adb', '-s', DEVICES[label], *args], timeout=45)
    return result if binary else result.decode('utf-8', 'replace')


def save(state):
    OUT.mkdir(parents=True, exist_ok=True)
    temp = STATE.with_suffix('.tmp')
    temp.write_text(json.dumps(state, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    temp.replace(STATE)


def ui(label):
    adb(label, 'shell', 'uiautomator', 'dump', '/sdcard/mndt-production-ui.xml')
    return ET.fromstring(adb(label, 'shell', 'cat', '/sdcard/mndt-production-ui.xml'))


def values(root):
    return [n.get('text', '') for n in root.iter('node') if n.get('text')]


def swipe(label, down=False):
    size = list(map(int, re.findall(r'\d+', adb(label, 'shell', 'wm', 'size').splitlines()[-1])))
    w, h = size[-2:]
    start, end = (0.28, 0.85) if down else (0.87, 0.64)
    adb(label, 'shell', 'input', 'swipe', str(int(w * 0.65)), str(int(h * start)), str(int(w * 0.65)), str(int(h * end)), '500')


def top(label):
    for _ in range(30):
        if any(v.startswith('DID:') for v in values(ui(label))):
            return
        swipe(label, True)
    raise RuntimeError('Cannot reach identity header')


def seek(label, predicate):
    for _ in range(12):
        root = ui(label)
        found = [n for n in root.iter('node') if predicate(n) and n.get('bounds') != '[0,0][0,0]']
        if found:
            return found[0]
        swipe(label)
    raise RuntimeError('Required UI control not visible; no blind click')


def tap(label, node):
    assert node.get('enabled') == 'true', 'Control disabled'
    a, b, c, d = map(int, re.findall(r'\d+', node.get('bounds')))
    adb(label, 'shell', 'input', 'tap', str((a+c)//2), str((b+d)//2))


def click(label, text):
    tap(label, seek(label, lambda n: n.get('text') == text))


def launch(label):
    adb(label, 'shell', 'am', 'start', '-n', PACKAGE + '/org.mndt.app.MainActivity')
    time.sleep(1)
    top(label)


def identity(label):
    launch(label)
    root = ui(label)
    ids = [v for v in values(root) if v.startswith('DID:')]
    assert len(ids) == 1, 'Identity UI unavailable (locked device?)'
    return {'public': ids[0], 'legacyPdsWarning': any('旧インストール' in v for v in values(root))}


def network(label, tag):
    stamp = now()
    radio = {k: adb(label, 'shell', 'settings', 'get', 'global', k).strip()
             for k in ('airplane_mode_on', 'wifi_on', 'bluetooth_on', 'mobile_data')}
    raw = {}
    for service in ('connectivity', 'wifi', 'telephony.registry', 'tethering', 'bluetooth_manager'):
        raw[service] = adb(label, 'shell', 'dumpsys', service)
        (OUT / f'{label}-{tag}-{service}.txt').write_text(raw[service], encoding='utf-8')
    for name, args in {'routes': ('shell', 'ip', 'route', 'show', 'table', 'all'),
                       'interfaces': ('shell', 'ip', 'addr'), 'reverse': ('reverse', '--list')}.items():
        raw[name] = adb(label, *args)
        (OUT / f'{label}-{tag}-{name}.txt').write_text(raw[name], encoding='utf-8')
    # Current Android releases use network{ID}, not the older NetworkAgentInfo{network{ form.
    current = raw['connectivity'].split('Current Networks:', 1)
    assert len(current) == 2, 'Unknown connectivity dump; inspect before asserting offline'
    section = current[1].split('Network Requests:', 1)[0]
    agents = [l.strip() for l in section.splitlines() if 'NetworkAgentInfo' in l]
    default = [l.strip() for l in raw['connectivity'].splitlines() if 'Active default network:' in l]
    return {'at': stamp, 'radio': radio, 'agents': agents, 'default': default,
            'reverseEmpty': not raw['reverse'].strip(),
            'wifiState': [l.strip() for l in raw['wifi'].splitlines() if 'Wi-Fi is ' in l],
            'dataStates': sorted(set(re.findall(r'mDataConnectionState=\d+', raw['telephony.registry']))),
            'bluetoothState': [l.strip() for l in raw['bluetooth_manager'].splitlines() if 'state: ON' in l]}


def offline_check(state, tag):
    checks = {}
    for label in DEVICES:
        n = network(label, tag)
        checks[label] = n
        state.setdefault('offlineChecks', []).append({'label': label, 'tag': tag, **n})
        save(state)
        assert n['radio']['airplane_mode_on'] == '1' and n['radio']['wifi_on'] == '0', n
        assert n['radio']['bluetooth_on'] == '1', n
        assert all('Transports: VPN' in a and 'UnderlyingNetworks: []' in a for a in n['agents']), n
        assert n['reverseEmpty'], n
        assert n['dataStates'] and all(v == 'mDataConnectionState=0' for v in n['dataStates']), n
        assert n['wifiState'] and all(v == 'Wi-Fi is disabled' for v in n['wifiState']), n
        assert n['default'] and all('none' in line.lower() for line in n['default']), n
    return checks


def policy_off(label):
    launch(label)
    root = ui(label)
    stop = [n for n in root.iter('node') if n.get('text') == '中継停止' and n.get('enabled') == 'true']
    if stop:
        tap(label, stop[0])
    node = seek(label, lambda n: n.get('text') in ('オンライン搬出を停止', 'オンライン搬出を再開'))
    if node.get('text') == 'オンライン搬出を停止':
        tap(label, node)
    seek(label, lambda n: n.get('text') == 'オンライン搬出を再開')
    top(label)


def prepare(state):
    if not state:
        state.update(startedAt=now(), devices={})
    save(state)
    for label in DEVICES:
        info = state['devices'].setdefault(label, {})
        if info.get('preparedAt'):
            continue
        if 'baselineNetwork' not in info:
            info['baselineNetwork'] = network(label, 'baseline')
        if 'identityBefore' not in info:
            info['identityBefore'] = identity(label)
        assert not info['identityBefore']['legacyPdsWarning'], 'Legacy PDS: report before migration'
        marker = 'TEST ONLY - NOT DISASTER INFORMATION ESP32-OFFLINE-' + label + '-' + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
        info.update(marker=marker, newPostRequested=False, package=PACKAGE)
        save(state)
        paths = adb(label, 'shell', 'pm', 'path', PACKAGE).splitlines()
        assert len(paths) == 1 and paths[0].startswith('package:'), 'Unexpected split APK'
        backup = OUT / (label + '-installed.apk')
        if not backup.exists():
            subprocess.run(['adb', '-s', DEVICES[label], 'pull', paths[0][8:], str(backup)], check=True, capture_output=True, timeout=60)
        info['apkBackupSha256'] = hashlib.sha256(backup.read_bytes()).hexdigest()
        policy_off(label)
        adb(label, 'shell', 'am', 'force-stop', PACKAGE)
        # Byte-for-byte ciphertext only. Android Keystore key stays hardware/OS-bound.
        encrypted = subprocess.run(['adb', '-s', DEVICES[label], 'exec-out', 'run-as', PACKAGE,
                                    'cat', 'no_backup/mosimo-state.aesgcm'], capture_output=True, timeout=20)
        if encrypted.returncode == 0:
            assert encrypted.stdout[:1] == b'\x01' and len(encrypted.stdout) >= 29
            (OUT / (label + '-mosimo-state.aesgcm')).write_bytes(encrypted.stdout)
            info['ciphertextBackup'] = {'bytes': len(encrypted.stdout), 'sha256': hashlib.sha256(encrypted.stdout).hexdigest(), 'portableKeystoreBackup': False}
        else:
            info['ciphertextBackup'] = {'available': False, 'reason': 'run-as unavailable; no privileged extraction attempted'}
        info['preparedAt'] = now()
        save(state)
    print(json.dumps({'prepared': True, 'devices': state['devices']}, ensure_ascii=True))


def install(state):
    apk = HERE / 'app/build/outputs/apk/demo/debug/app-demo-debug.apk'
    for label, info in state['devices'].items():
        assert not info['newPostRequested']
        adb(label, 'shell', 'am', 'force-stop', PACKAGE)
        assert 'Success' in adb(label, 'install', '-r', str(apk))
        info['installedApkSha256'] = hashlib.sha256(apk.read_bytes()).hexdigest()
        info['identityAfter'] = identity(label)
        save(state)
        assert info['identityBefore'] == info['identityAfter'], 'Identity changed; no posting'
        policy_off(label)
        adb(label, 'shell', 'am', 'force-stop', PACKAGE)
    state['installedAt'] = now()
    save(state)
    print('Both install-r updates preserved public identities; online export OFF')


def isolate(state):
    for label in DEVICES:
        assert adb(label, 'reverse', '--list').strip() == '', 'Existing host reverse route; do not delete user routes'
        adb(label, 'shell', 'svc', 'wifi', 'disable')
        adb(label, 'shell', 'svc', 'data', 'disable')
        assert adb(label, 'shell', 'settings', 'get', 'global', 'airplane_mode_on').strip() == '1', 'Enable airplane through UI first'
        assert adb(label, 'shell', 'settings', 'get', 'global', 'bluetooth_on').strip() == '1'
        for package in adb(label, 'shell', 'pm', 'list', 'packages', 'org.mndt').splitlines():
            adb(label, 'shell', 'am', 'force-stop', package.removeprefix('package:'))
    offline_check(state, 'isolated')
    print('Both devices have no physical networks/default/reverse; Wi-Fi/data OFF, airplane ON, Bluetooth ON; any dormant VPN has no underlying network')


def post(state, label):
    info = state['devices'][label]
    assert not info['newPostRequested'], 'Publish already requested: NEVER retry'
    absence = json.loads((OUT / (label + '-absence.json')).read_text(encoding='utf-8'))
    assert absence['absent'] and absence['marker'] == info['marker']
    info['relayAbsenceBefore'] = absence
    offline_check(state, label + '-before-create')
    policy_off(label)
    seek(label, lambda n: n.get('text', '').startswith('公開する本文'))
    root = ui(label)
    fields = [n for n in root.iter('node') if n.get('class') == 'android.widget.EditText'
              and any(c.get('text', '').startswith('公開する本文') for c in n.iter('node'))]
    assert len(fields) == 1 and not fields[0].get('text'), 'Existing draft: do not overwrite'
    tap(label, fields[0])
    adb(label, 'shell', 'input', 'text', info['marker'].replace(' ', '%s'))
    adb(label, 'shell', 'input', 'keyevent', '4')
    assert any(n.get('class') == 'android.widget.EditText' and n.get('text') == info['marker'] for n in ui(label).iter('node'))
    button = seek(label, lambda n: n.get('text') == '署名して保存・配送')
    info.update(newPostRequested=True, publishRequestedAt=now())
    save(state)  # Persist intent before the only creation click, including timeouts/crashes.
    tap(label, button)
    time.sleep(3)
    for _ in range(12):
        root = ui(label)
        cards = []
        for node in root.iter('node'):
            texts = values(node)
            if info['marker'] in texts and any(re.match('[0-9a-f]{12} /', t) for t in texts):
                cards.append(texts)
        if cards:
            texts = min(cards, key=len)
            info['eventPrefix'] = next(re.match('([0-9a-f]{12}) /', t)[1] for t in texts if re.match('[0-9a-f]{12} /', t))
            info['createdAt'] = now()
            save(state)
            offline_check(state, label + '-after-create')
            print(json.dumps({'label': label, 'marker': info['marker'], 'prefix': info['eventPrefix']}))
            return
        swipe(label)
    raise RuntimeError('Creation clicked; no visible prefix. NEVER create again')


def mesh(state, label):
    other = next(v for v in DEVICES if v != label)
    policy_off(other)
    adb(other, 'shell', 'am', 'force-stop', PACKAGE)
    policy_off(label)
    offline_check(state, label + '-before-mesh')
    click(label, '中継開始')
    # Only explicit Android permission dialogs, never arbitrary app buttons.
    for _ in range(3):
        root = ui(label)
        allowed = [n for n in root.iter('node') if n.get('resource-id') in (
            'com.android.permissioncontroller:id/permission_allow_button',
            'com.android.permissioncontroller:id/permission_allow_foreground_only_button',
            'com.android.packageinstaller:id/permission_allow_button')]
        if allowed:
            tap(label, allowed[0])
            time.sleep(1)
        else:
            break
    state['devices'][label]['meshStartedAt'] = now()
    save(state)
    print(label + ' mesh start requested; other phone force-stopped, both offline')


def stop(state, label):
    policy_off(label)
    adb(label, 'shell', 'am', 'force-stop', PACKAGE)
    state['devices'][label]['meshStoppedAt'] = now()
    offline_check(state, label + '-after-mesh')
    save(state)
    print(label + ' mesh stopped, online OFF; both offline')


def restore(state):
    for label, info in state['devices'].items():
        policy_off(label)
        adb(label, 'shell', 'am', 'force-stop', PACKAGE)
        baseline = info['baselineNetwork']['radio']
        assert baseline['airplane_mode_on'] == '1' and baseline['bluetooth_on'] == '1'
        adb(label, 'shell', 'svc', 'data', 'enable' if baseline['mobile_data'] == '1' else 'disable')
        # wifi_on=3 means disabled in airplane mode, not an enabled WLAN.
        adb(label, 'shell', 'svc', 'wifi', 'enable' if baseline['wifi_on'] in ('1', '2') else 'disable')
        if baseline['wifi_on'] == '3':
            adb(label, 'shell', 'settings', 'put', 'global', 'wifi_on', '3')
        info['finalNetwork'] = network(label, 'final')
        info['identityFinal'] = identity(label)
        assert info['identityFinal'] == info['identityBefore']
        policy_off(label)
        adb(label, 'shell', 'am', 'force-stop', PACKAGE)
        assert info['finalNetwork']['radio'] == baseline
        save(state)
    state['restoredAt'] = now()
    save(state)
    print('Original radio preferences restored; identities unchanged; meshing/online export OFF')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=('prepare', 'install', 'isolate', 'check', 'post', 'mesh', 'stop', 'restore'))
    parser.add_argument('--label', choices=DEVICES)
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    state = json.loads(STATE.read_text(encoding='utf-8')) if STATE.exists() else {}
    if args.phase in ('post', 'mesh', 'stop'):
        assert args.label
        globals()[args.phase](state, args.label)
    elif args.phase == 'check':
        print(json.dumps(offline_check(state, 'check-' + str(int(time.time())))), flush=True)
    else:
        globals()[args.phase](state)
