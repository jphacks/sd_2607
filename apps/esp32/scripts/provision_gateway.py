"""Local-only provisioning for the explicitly approved TECHNOPOLIS 2.4 test.
Reads Windows keys via captured subprocess output; never prints credentials.
Exact saved profile first, current TECHNOPOLIS 5.0 key as an authentication trial
only (not an assertion that the two passwords match). Does not change host Wi-Fi.
TLS roots come from chains verified with the Windows/Python system trust store.
"""
import locale
from pathlib import Path
import re
import socket
import ssl
import subprocess
import json


def main():
    root = Path(__file__).resolve().parents[1]
    if (root / 'private/gateway/config.h').exists():
        raise SystemExit('Existing private gateway config preserved; edit it locally instead of reprovisioning')
    def key(profile):
        result = subprocess.run(['netsh', 'wlan', 'show', 'profile', 'name=' + profile, 'key=clear'], capture_output=True, timeout=10)
        text = result.stdout.decode(locale.getpreferredencoding(False), errors='replace')
        match = re.search(r'^\s*(?:主要なコンテンツ|キーのコンテンツ|Key Content)\s*:\s*(.+)$', text, re.M)
        return match.group(1).strip() if match else None
    password = key('TECHNOPOLIS 2.4')
    exact = bool(password)
    if not password:
        password = key('TECHNOPOLIS 5.0')
    if not password:
        raise SystemExit('No saved key available; ask operator for credentials')
    context = ssl.create_default_context()
    roots = {}
    for host in ('pds.oriom.dev', 'plc.directory'):
        with socket.create_connection((host, 443), timeout=10) as sock:
            with context.wrap_socket(sock, server_hostname=host) as tls:
                chain = tls._sslobj.get_verified_chain()
                roots[host] = chain[-1].public_bytes()
                print('System-verified TLS chain:', host, 'root bytes=', len(roots[host]))
    target = root / 'private/gateway'
    target.mkdir(parents=True, exist_ok=True)
    header = '\n'.join([
        '#pragma once', '#define MESH_GATEWAY_WIFI_SSID "TECHNOPOLIS 2.4"',
        '#define MESH_GATEWAY_WIFI_PASSWORD ' + json.dumps(password),
        '#define MESH_GATEWAY_RELAY_HOST "pds.oriom.dev"',
        '#define MESH_GATEWAY_RELAY_PORT 443', '#define MESH_GATEWAY_RELAY_PATH "/"',
        '#define MESH_GATEWAY_RELAY_CA ' + json.dumps(roots['pds.oriom.dev']),
        '#define MESH_GATEWAY_PLC_URL "https://plc.directory"',
        '#define MESH_GATEWAY_PLC_CA ' + json.dumps(roots['plc.directory']),
        '#define MESH_GATEWAY_NTP_SERVER "pool.ntp.org"',
        '#define MESH_INGRESS_ENABLED 1', '#define MESH_INGRESS_TRUST_CLOCK 1', ''])
    (target / 'config.h').write_text(header, encoding='utf-8')
    public = (root / '../../appview/bsaf/private/IngressTrust.generated.h').read_text(encoding='utf-8')
    if '#define MESH_INGRESS_MIN_SEQUENCE 4ULL' not in public:
        raise SystemExit('Unexpected public sequence floor; inspect export')
    public = public.replace('R"PEM()PEM"', json.dumps(roots['pds.oriom.dev']))
    (root / 'include/IngressTrust.generated.h').write_text(public, encoding='utf-8')
    print('Private config saved; exact-profile key=', exact, '; alternate key needs actual authentication check=', not exact)


if __name__ == '__main__':
    main()
