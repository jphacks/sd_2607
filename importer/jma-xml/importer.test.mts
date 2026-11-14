import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';
import { createECDH, createPrivateKey } from 'node:crypto';
import { BLOCK, IMPORTER, JMA_XML_SELECTION, canonical, createAttestation, createImporter, randomSecret, nostrPublicKey, hex, unhex, timestamp,
  authorizeImport, atprotoPublicKey } from '../../pds/attest.mjs';
import { openStore, poll, stage, pushOnce, parseFeed, validateConfig, loadCredentials, type ImporterConfig } from './importer.mts';
import { useTables } from './area.mts';

const any = (prefix: string) => new Proxy({}, { has: () => true, get: (_t, k) => [`${prefix}${String(k).slice(-3)}`],
  getOwnPropertyDescriptor: () => ({ value: [], configurable: true, enumerable: true }) }) as Record<string, string[]>;
useTables({ warningCity: any('xn'), quakeArea: any('xp') });
const now = Math.floor(Date.parse('2026-11-14T09:00:00Z') / 1000);
const selection = JMA_XML_SELECTION.map(e => ({ feed: e.feed, types: [...e.types] }));

/** root -> CA (feeds) -> importer leaf (feeds), as `mosimo-ca issue ... --preset jma-xml` writes it. */
function credentials() {
  const rootSecret = randomSecret(), caSecret = randomSecret(), root = nostrPublicKey(rootSecret), ca = nostrPublicKey(caSecret);
  const importer = createImporter({ endpoint: 'https://pds.example.org' });
  const block = { $type: IMPORTER, feeds: selection };
  const caAttestation = createAttestation(rootSecret, root, { subject: ca, owner: { id: 'ca', name: 'CA' }, roles: ['attest', 'import'],
    blocks: [{ $type: BLOCK.delegation, maxDepth: 1 }, { $type: BLOCK.issuerDomain, domain: 'pds.example.org' }, block] });
  const leaf = createAttestation(caSecret, ca, { subject: unhex(importer.nostrPubkey), owner: { id: importer.did, name: 'JMA XML importer' }, roles: ['import'],
    blocks: [{ $type: BLOCK.keyValidity, from: timestamp(now - 86400), until: timestamp(now + 86400) },
      { $type: BLOCK.organization, orgId: 'oriom.dev:mosimo-jma-xml-importer', orgName: 'Mosimo JMA XML importer (not JMA)' }, block] });
  return { credentials: { importer: { did: importer.did, atprotoSecret: importer.atprotoSecret, nostrSecret: importer.nostrSecret }, attestations: [caAttestation, leaf] },
    roots: [{ pubkey: hex(root), roles: ['attest', 'import'], maxDepth: 2, enabled: true }] };
}
const config: ImporterConfig = { enabled: true, databasePath: ':memory:', credentialsDirectory: 'c', trustPath: 't', relayUrl: 'wss://pds.example.org/',
  selection, feeds: ['https://www.data.jma.go.jp/developer/xml/feed/extra.xml'], pollSeconds: 60, maxMessageAgeSeconds: 86400,
  deliverySeconds: { earthquake: 172800, weather: 259200 } };
const data = (name: string) => `https://www.data.jma.go.jp/developer/xml/data/${name}`;
const feed = (entries: [string, string, string][]) => Buffer.from(`<feed>${entries.map(([url, title, updated]) =>
  `<entry><title>${title}</title><id>${url}</id><updated>${updated}</updated></entry>`).join('')}</feed>`);

test('config and feed parsing accept only the JMA host and the jma-xml feed', () => {
  validateConfig(config);
  assert.throws(() => validateConfig({ ...config, feeds: ['https://example.org/feed.xml'] }), /Invalid importer configuration/);
  assert.throws(() => validateConfig({ ...config, selection: [{ botDid: 'did:plc:vwi7zhfyamyvrg5v6ycjgzxi', types: ['earthquake'] }] }), /Invalid/);
  assert.deepEqual(parseFeed(feed([[data('a.xml'), 't', '2026-11-14T08:00:00Z'], ['https://evil.example/a.xml', 't', 'x']]).toString()),
    [{ url: data('a.xml'), title: 't', updated: '2026-11-14T08:00:00Z' }]);
});

test('poll fetches the newest of each weather series once, stage signs authorized posts, push retries the same event', async () => {
  const { credentials: creds, roots } = credentials();
  const store = openStore(':memory:');
  const files: Record<string, Buffer> = {
    [data('20261114081809_0_VPWW56_420000.xml')]: readFileSync(new URL('./fixtures/VPWW56_nagasaki.xml', import.meta.url)),
    [data('20261114064205_0_VXSE53_010000.xml')]: readFileSync(new URL('./fixtures/VXSE53_hokkaido.xml', import.meta.url)),
  };
  const requested: string[] = [];
  const fetchBytes = async (url: string) => {
    requested.push(url);
    if (url.endsWith('/feed/extra.xml')) return { status: 200 as const, bytes: feed([
      [data('20261114070000_0_VPWW56_420000.xml'), '気象警報・注意報（Ｒ０６）（土砂）', '2026-11-14T07:00:00Z'],
      [data('20261114081809_0_VPWW56_420000.xml'), '気象警報・注意報（Ｒ０６）（土砂）', '2026-11-14T08:18:08Z'],
      [data('20261114064205_0_VXSE53_010000.xml'), '震源・震度に関する情報', '2026-11-14T06:42:05Z'],
      [data('20261114060000_0_VPWW54_420000.xml'), '気象警報・注意報（Ｈ２７）', '2026-11-14T06:00:00Z']]) };
    if (files[url]) return { status: 200 as const, bytes: files[url]! };
    throw new Error(`unexpected ${url}`);
  };
  assert.equal(await poll(store, config, now, false, fetchBytes as any), 2);
  assert.ok(!requested.some(u => u.includes('20261114070000') || u.includes('VPWW54')));
  assert.equal(await poll(store, config, now + 60, false, fetchBytes as any), 0); // already fetched
  assert.equal(await stage(store, config, creds, roots, now), 2);
  const rows = store.db.prepare('SELECT * FROM jma_xml_posts ORDER BY seq').all();
  for (const row of rows) {
    const result = await authorizeImport(JSON.parse(String(row.event_json)), { roots, selection, revocations: [], now });
    assert.equal(result.feed, 'jma-xml');
  }
  let sent = 0;
  const failing = async () => { sent++; return false; };
  assert.equal(await pushOnce(store, config, roots, now, new AbortController().signal, failing), false);
  const first = store.db.prepare(`SELECT * FROM jma_xml_posts WHERE attempts=1`).get()!;
  assert.equal(await pushOnce(store, config, roots, now + 10, new AbortController().signal, async (_u, e) => { assert.equal(e.id, JSON.parse(String(first.event_json)).id); return true; }), true);
  assert.equal(sent, 1);
  store.close();
});

/** PKCS#8 PEM of a secp256k1 secret, as `mosimo-ca credentials` writes nostr.pem and atproto.pem. */
function pem(secretHex: string) {
  const ecdh = createECDH('secp256k1'); ecdh.setPrivateKey(Buffer.from(secretHex, 'hex'));
  const point = ecdh.getPublicKey(), b64 = (b: Uint8Array) => Buffer.from(b).toString('base64url');
  return String(createPrivateKey({ key: { kty: 'EC', crv: 'secp256k1', d: b64(Buffer.from(secretHex, 'hex')), x: b64(point.subarray(1, 33)), y: b64(point.subarray(33)) },
    format: 'jwk' }).export({ format: 'pem', type: 'pkcs8' }));
}

test('loads the key files of mosimo-ca credentials and refuses mismatched ones', () => {
  const { credentials: creds } = credentials();
  const { did, nostrSecret, atprotoSecret } = creds.importer;
  const identity = JSON.stringify({ did, nostrPubkey: hex(nostrPublicKey(unhex(nostrSecret))), atprotoPubkey: hex(atprotoPublicKey(unhex(atprotoSecret))) });
  const files = { identity, chain: canonical(creds.attestations, { maxBytes: 65536 }), nostrPem: pem(nostrSecret), atprotoPem: pem(atprotoSecret) };
  const loaded = loadCredentials(files);
  assert.deepEqual(loaded.importer, creds.importer);
  assert.equal(loaded.attestations.length, 2);
  assert.throws(() => loadCredentials({ ...files, nostrPem: files.atprotoPem }), /Importer-only|does not match/);
  assert.throws(() => loadCredentials({ ...files, chain: canonical(creds.attestations.slice(0, 1), { maxBytes: 65536 }) }), /Chain leaf/);
});
