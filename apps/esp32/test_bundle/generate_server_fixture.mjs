// TEST ONLY. Run as a separate CLI process; NEVER import into a server/device.
// Uses actual PDS exports and real @atproto/repo MST construction/reconstruction.
// No network, operational credentials, environment secrets or production DB reads.
import assert from 'node:assert/strict';
import { createHash, webcrypto } from 'node:crypto';
import { readFileSync, writeFileSync, openSync, fsyncSync, closeSync, renameSync, mkdtempSync, rmSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const mode = process.argv[2] ?? '--check';
const combined = process.argv[3] === '--combined';
assert(['--write', '--check'].includes(mode) && (process.argv.length <= 3 || (combined && process.argv.length === 4)), 'Use --write or --check [--combined]');
// Isolated deterministic TEST entropy for the real APIs' private-key generation
// and BIP340 auxiliary randomness. Installed dependencies are NOT modified.
// issueCertificate's node:crypto serial is overridden by an explicit test serial.
let counter = 0;
const sha = bytes => new Uint8Array(createHash('sha256').update(bytes).digest());
function testRandom(bytes) {
  for (let at = 0; at < bytes.length;) {
    const block = sha(Buffer.from(`ESP32 PUBLIC TEST ONLY actual importer fixture v1:${counter++}`));
    const n = Math.min(block.length, bytes.length - at); bytes.set(block.subarray(0, n), at); at += n;
  }
  return bytes;
}
// noble-hashes v1 selects node:crypto.webcrypto; newer versions use globalThis.
Object.defineProperty(webcrypto, 'getRandomValues', { configurable: true, value: testRandom });
Object.defineProperty(globalThis, 'crypto', { configurable: true, value: { getRandomValues: testRandom } });
// Dynamic imports MUST follow the test entropy setup; no publisher main() runs.
const serverUrl = new URL('../../../pds/public-information.mjs', import.meta.url);
const repositoryUrl = new URL('../../../pds/repository.mjs', import.meta.url);
const api = await import(serverUrl.href);
const { decodePost, reconstruct } = await import(repositoryUrl.href);
const hex = bytes => Buffer.from(bytes).toString('hex');
const rootDirectory = new URL('../../../', import.meta.url);
const sourceHash = path => hex(sha(Buffer.from(readFileSync(new URL(path, rootDirectory), 'utf8').replaceAll('\r\n', '\n'))));
function cid(bytes) {
  const raw = Uint8Array.from([1, 0x71, 0x12, 0x20, ...sha(bytes)]);
  const alphabet = 'abcdefghijklmnopqrstuvwxyz234567'; let value = 0, bits = 0, out = 'b';
  for (const byte of raw) {
    value = (value << 8) | byte; bits += 8;
    while (bits >= 5) { bits -= 5; out += alphabet[(value >>> bits) & 31]; }
    value &= (1 << bits) - 1;
  }
  if (bits) out += alphabet[(value << (5 - bits)) & 31];
  return out;
}
const now = 1792368000; // 2026-10-19T00:00:00Z, fixed test clock, never Date.now().
const selected = combined
  ? [...api.JMA_SELECTION, ...api.KIKIKURU_SELECTION].sort((a, b) => a.botDid.localeCompare(b.botDid))
  : [{ botDid: 'did:plc:bbbbbbbbbbbbbbbbbbbbbbbb', types: ['earthquake'] }];
const choices = combined ? selected.flatMap(({ botDid, types }) => types.map(type => ({ botDid, type })))
  : Array.from({ length: 2 }, () => ({ botDid: selected[0].botDid, type: 'earthquake' }));
if (combined) {
  assert.equal(choices.length, 28);
  // Pinned type options from the primary bot-definition.json (updated_at 2026-08-01).
  // https://raw.githubusercontent.com/osprey74/bsaf-kikikuru-bot/main/bot-definition.json
  assert.deepEqual(api.KIKIKURU_SELECTION[0].types, [
    'heavy-rain-warning', 'heavy-rain', 'landslide-warning', 'tornado-warning', 'storm-surge-warning',
    'wind-warning', 'wave-warning', 'snow-warning', 'thunderstorm-warning', 'melting-snow-warning',
    'dense-fog-warning', 'dry-air-warning', 'avalanche-warning', 'low-temperature-warning', 'frost-warning',
    'icing-warning', 'snow-accretion-warning', 'flood-warning', 'linear-rainband-warning',
    'significant-snowfall-warning', 'typhoon', 'weather-info', 'other-warning',
  ].sort());
  assert.equal(api.KIKIKURU_SELECTION[0].botDid, 'did:plc:kxwz5cz7o6g4jlmxh6doyfsm');
}
const checkedAt = now + choices.length;
const scope = { orgIds: ['esp32-server-fixture'], bsaf: selected };
const { secretKey: rootSecret, root } = api.createRoot({ scope, snapshotUrl: 'https://snapshot.example.invalid/test.cbor', minSequence: 41 });
const importer = api.createImporter({ endpoint: 'https://pds.example.invalid' });
const certificate = api.issueCertificate(rootSecret, {
  serial: 'e5'.repeat(16), subjectPubkey: api.keyPublic(importer.atprotoSecret), subjectDid: importer.did,
  orgId: scope.orgIds[0], orgName: 'TEST ONLY server-produced importer', scope,
  notBefore: api.timestamp(now - 60), notAfter: api.timestamp(now + 86400),
});
const snapshot = api.createSnapshot(rootSecret, { sequence: root.minSequence,
  issuedAt: api.timestamp(now - 1), validUntil: api.timestamp(now + 1800) });
assert.equal(api.verifyImporterGenesis({ genesis: importer.genesis, binding: importer.binding,
  plcPubkey: api.keyPublic(importer.plcSecret) }).did, importer.did);

// File-backed test store fulfills verifyPublicInformation's durable-callback
// boundary without touching any server database or credentials. Public data only.
const temp = mkdtempSync(fileURLToPath(new URL('../.pio/server-fixture-', import.meta.url)));
const statePath = `${temp}/trust.json`;
function persist(state) {
  const staged = `${statePath}.tmp`; const fd = openSync(staged, 'w');
  try { writeFileSync(fd, JSON.stringify(state)); fsyncSync(fd); } finally { closeSync(fd); }
  renameSync(staged, statePath);
}
try {
  persist({ snapshot: null, serials: {} });
  const snapshots = {
    async get(id) { assert.equal(id, api.rootId(root.rootPubkey)); return snapshot; },
    async accept(id, value) {
      assert.equal(id, api.rootId(root.rootPubkey)); const state = JSON.parse(readFileSync(statePath, 'utf8'));
      await api.verifySnapshot(value, { root, nowSeconds: combined ? checkedAt : now + 2, previous: state.snapshot });
      state.snapshot = value; persist(state);
    },
    async recordCertificate(issuer, serial, certId) {
      const state = JSON.parse(readFileSync(statePath, 'utf8')); const key = `${issuer}:${serial}`;
      assert(!state.serials[key] || state.serials[key] === certId, 'Test certificate serial equivocation');
      state.serials[key] = certId; persist(state);
    },
  };
  const events = [];
  let state; let previousEntries = []; let previousCommit = null;
  for (let i = 0; i < choices.length; ++i) {
    const { botDid, type } = choices[i];
    const original = { $type: 'app.bsky.feed.post', text: `TEST ONLY - NOT DISASTER INFORMATION (${i + 1})`,
      createdAt: api.timestamp(now - 30 + i), langs: ['ja'], tags: [
        'bsaf:v1', `type:${type}`, 'value:test', `time:${api.timestamp(now - 31 + i)}`, 'target:jp', 'source:jma',
      ] };
    const originalBytes = api.canonical(original); const originalCid = cid(originalBytes);
    const uri = `at://${botDid}/app.bsky.feed.post/test-only-${i + 1}`;
    const bsaf = { $type: api.BSAF, tags: original.tags, langs: original.langs,
      original: { uri, cid: originalCid, createdAt: original.createdAt } };
    // Same text/extension argument shape as appview/bsaf/publisher.mts stageNext.
    const text = `${original.text}\n\n[BSAF再配信 / importer: ${importer.did}]\n原投稿著者: ${botDid}\n原投稿: ${uri}\n取得経路: AppView/Jetstream（原Bot・気象庁の署名を検証したものではありません）`;
    const result = await api.createImporterEvent({ importer, chain: [certificate], text, extensions: [bsaf],
      nowSeconds: now + i, deliveryExpiresAt: api.timestamp(Date.parse(original.createdAt) / 1000 + 3600), state, priority: i === 0 ? 2 : 3 });
    const decoded = await decodePost(result.event);
    assert.equal(decoded.base?.toString() ?? null, previousCommit);
    assert.equal(result.state.base, decoded.commitCid.toString());
    assert.deepEqual(Buffer.from(decoded.recordBytes), Buffer.from(api.canonical(result.record)));
    // Actual PDS reconstruction rejects fake/stale MST roots. Second invocation
    // includes the first record, rather than rebuilding an unrelated empty tree.
    const rebuilt = await reconstruct(decoded, previousEntries);
    assert.equal(rebuilt.entries.length, i + 1);
    assert.deepEqual(result.state.entries, rebuilt.entries.map(e => ({ key: e.key, cid: e.value.toString() })));
    const authorization = await api.verifyPublicInformation(result.event, { roots: [root], snapshots,
      nowSeconds: combined ? checkedAt : now + 2, selection: selected });
    assert(authorization.authorized);
    const typedTags = result.event.tags.map(t => t[0] === `${api.NS}:commit` || t[0].startsWith(`${api.NS}:ext:`)
      ? [t[0], Buffer.from(t[1], 'base64')] : t[0] === `${api.NS}:version` ? [t[0], 1] : t);
    const payload = api.canonical([1, Buffer.from(result.event.pubkey, 'hex'), result.event.created_at, 1,
      typedTags, result.event.content, Buffer.from(result.event.sig, 'hex')], { signed: false });
    events.push({ event: result.event, authorization, recordHex: hex(decoded.recordBytes), recordCid: decoded.recordCid.toString(),
      commitHex: hex(decoded.commitBytes), commitCid: decoded.commitCid.toString(), base: decoded.base?.toString() ?? null,
      mstRoot: decoded.commit.data.toString(), mstReconstructed: true, repositoryEntries: result.state.entries,
      mstBlocks: rebuilt.blocks.map(({ cid: blockCid, bytes }) => ({ cid: blockCid.toString(), hex: hex(bytes) })),
      originalRecordHex: hex(originalBytes), originalRecordCid: originalCid, payloadHex: hex(payload),
      bounds: { eventJsonBytes: Buffer.byteLength(JSON.stringify(result.event)), payloadBytes: payload.length,
        contentBytes: Buffer.byteLength(text), commitBytes: decoded.commitBytes.length,
        identityBytes: Buffer.from(result.event.tags.find(t => t[0] === `${api.NS}:ext:${api.IDENTITY}`)[1], 'base64').length,
        certificateBytes: api.canonical(certificate).length, snapshotBytes: api.canonical(snapshot).length,
        ...(combined ? { scopeBytes: api.canonical(scope).length, selectedBytes: api.canonical(selected).length } : {}) } });
    state = result.state; previousEntries = rebuilt.entries; previousCommit = decoded.commitCid.toString();
  }
  let unselectedEvent;
  if (combined) {
    const bsaf = { $type: api.BSAF, tags: ['bsaf:v1', 'type:unselected-extra', 'value:test',
      `time:${api.timestamp(now)}`, 'target:jp', 'source:jma'], langs: ['ja'],
      original: { uri: `at://${api.KIKIKURU_SELECTION[0].botDid}/app.bsky.feed.post/test-extra`,
        cid: events[0].originalRecordCid, createdAt: api.timestamp(now) } };
    unselectedEvent = (await api.createImporterEvent({ importer, chain: [certificate],
      text: 'TEST ONLY unselected extra type', extensions: [bsaf], nowSeconds: checkedAt,
      deliveryExpiresAt: api.timestamp(now + 3600), state })).event;
    await assert.rejects(() => api.verifyPublicInformation(unselectedEvent, { roots: [root], snapshots,
      nowSeconds: checkedAt, selection: selected }));
  }
  const fixture = { testOnly: true, ...(combined ? { unselectedEvent } : {}), provenance: { producer: 'pds/public-information.mjs:createImporterEvent',
    mstVerifier: 'pds/repository.mjs:reconstruct', publisherArguments: 'appview/bsaf/publisher.mts:stageNext',
    entropy: 'isolated deterministic PUBLIC TEST ONLY SHA256 stream; no operational keys read or exported',
    sourceSha256NormalizedLF: Object.fromEntries(['pds/public-information.mjs', 'pds/repository.mjs', 'appview/bsaf/publisher.mts'].map(p => [p, sourceHash(p)])) },
    nowSeconds: combined ? checkedAt : now + 2, root: { ...root, rootId: api.rootId(root.rootPubkey) }, selection: selected,
    certificate, binding: importer.binding, importerDid: importer.did, genesis: importer.genesis,
    snapshotHex: hex(api.canonical(snapshot)), snapshotBodyHash: api.bodyHash(snapshot.body), certId: api.certId(certificate),
    events };
  const output = JSON.stringify(fixture, null, 2) + '\n';
  for (const secret of [rootSecret, importer.atprotoSecret, importer.nostrSecret, importer.plcSecret])
    assert(!output.includes(secret), 'Never export even the generated test private keys');
  for (const key of ['secretKey', 'atprotoSecret', 'nostrSecret', 'plcSecret'])
    assert(!output.includes(JSON.stringify(key)), 'Private-key fields must not enter the fixture');
  const target = new URL(combined ? './wire-v1-combined-server-fixture.json' : './wire-v1-server-fixture.json', import.meta.url);
  if (mode === '--write') writeFileSync(target, output);
  else {
    const expected = JSON.parse(readFileSync(target, 'utf8'));
    function difference(a, b, path = '$') {
      if (typeof a !== typeof b || a === null || b === null) return a === b ? null : path;
      if (typeof a !== 'object') return a === b ? null : path;
      if (Object.keys(a).join(',') !== Object.keys(b).join(',')) return `${path} keys`;
      for (const key of Object.keys(a)) { const diff = difference(a[key], b[key], `${path}.${key}`); if (diff) return diff; }
      return null;
    }
    const diff = difference(expected, fixture);
    assert(!diff, `Fixture/source drift at ${diff}: regenerate explicitly with --write`);
  }
  console.log(`PASS actual createImporterEvent: ${events.length} successive events, valid reconstructed MSTs, genesis/binding/root/snapshot/commit/event authorization`);
  for (const [i, e] of events.entries()) console.log(`event${i + 1}: JSON=${e.bounds.eventJsonBytes} payload=${e.bounds.payloadBytes} content=${e.bounds.contentBytes} commit=${e.bounds.commitBytes} base=${e.base ?? '(initial)'}`);
} finally { rmSync(temp, { recursive: true, force: true }); }
