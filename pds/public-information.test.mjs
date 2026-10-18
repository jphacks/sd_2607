import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';
import { secp256k1 } from '@noble/curves/secp256k1.js';
import * as cbor from '@ipld/dag-cbor';
import { CID } from 'multiformats/cid';
import { sha256 } from 'multiformats/hashes/sha2';
import { finalizeEvent } from 'nostr-tools';
import { decodePost, reconstruct } from './repository.mjs';
import { BSAF, IDENTITY, PRIORITY, NS, JMA_SELECTION, canonical, decodeCanonical, bodyHash, rootId, certId,
  createRoot, issueCertificate, createSnapshot, createImporter, verifyImporterGenesis, verifyBinding,
  verifyChain, verifySnapshot, verifyPublicInformation, createImporterEvent, effectivePriority, keyPublic, timestamp } from './public-information.mjs';

const now = 1792368000;
const scope = { orgIds: ['test-importer'], bsaf: JMA_SELECTION };
const rootSecret = '01'.padStart(64, '0'); // Test only, never an operational trust anchor.
const root = { rootPubkey: keyPublic(rootSecret), enabled: true, scope,
  snapshotUrl: 'https://trust.invalid/snapshot.cbor', minSequence: 1 };
const importer = createImporter();
const certificate = issueCertificate(rootSecret, { subjectDid: importer.did, subjectPubkey: keyPublic(importer.atprotoSecret),
  orgId: 'test-importer', orgName: 'Test importer, not JMA', scope,
  notBefore: timestamp(now - 10), notAfter: timestamp(now + 86400) });
const original = { $type: 'app.bsky.feed.post', text: '訓練 — TEST ONLY', createdAt: timestamp(now - 30),
  tags: ['bsaf:v1', 'type:earthquake', 'value:5+', `time:${timestamp(now - 30)}`, 'target:jp-kanto', 'source:jma'], langs: ['ja'] };
const originalCid = CID.createV1(cbor.code, await sha256.digest(cbor.encode(original))).toString();
const extension = { $type: BSAF, tags: original.tags, langs: original.langs,
  original: { uri: `at://${JMA_SELECTION[0].botDid}/app.bsky.feed.post/test`, cid: originalCid, createdAt: original.createdAt } };
const snapshot = (sequence = 1, options = {}) => createSnapshot(rootSecret, { sequence,
  issuedAt: timestamp(now - 1), validUntil: timestamp(now + 3599), ...options });
function memorySnapshots(initial = snapshot()) {
  let accepted = null;
  const serials = new Map();
  return { get: () => initial,
    async accept(id, value) { await verifySnapshot(value, { root, nowSeconds: now, previous: accepted }); accepted = value; },
    recordCertificate(issuer, serial, id) {
      const key = `${issuer}:${serial}`;
      if (serials.has(key) && serials.get(key) !== id) {
        serials.set(key, null); // Sticky test adapter: neither body can regain authorization.
        throw new Error('serial conflict');
      }
      serials.set(key, id);
    },
  };
}
const make = (options = {}) => createImporterEvent({ importer, chain: [certificate], text: original.text,
  extensions: [extension], nowSeconds: now, deliveryExpiresAt: timestamp(now + 600), priority: 3, ...options });
const options = (overrides = {}) => ({ roots: [root], snapshots: memorySnapshots(), nowSeconds: now, selection: JMA_SELECTION, ...overrides });
const resign = (event, changes) => finalizeEvent({ ...event, ...changes }, Buffer.from(importer.nostrSecret, 'hex'));

test('shared Android/ESP32 wire-v1 fixture matches exact Node CBOR bytes, hashes and all signatures', async () => {
  const fixture = JSON.parse(await readFile(new URL('../apps/android/bundle-core/src/test/resources/wire-v1.json', import.meta.url), 'utf8'));
  assert.equal(fixture.testOnly, true);
  const root = fixture.config.roots[0];
  const snapshot = decodeCanonical(Buffer.from(fixture.snapshotHex, 'hex'));
  assert.equal(Buffer.from(canonical(fixture.certificate.body)).toString('hex'), fixture.certificateBodyHex);
  assert.equal(certId(fixture.certificate), fixture.certId);
  assert.equal(Buffer.from(canonical(snapshot.body)).toString('hex'), fixture.snapshotBodyHex);
  assert.equal(bodyHash(snapshot.body), fixture.snapshotBodyHash);
  const decoded = await decodePost(fixture.event);
  assert.equal(Buffer.from(decoded.recordBytes).toString('hex'), fixture.recordHex);
  assert.equal(Buffer.from(decoded.commitBytes).toString('hex'), fixture.commitHex);
  // This shared receiver fixture proves signature/preimage interop, not MST inclusion.
  // Actual publisher MSTs are reconstructed independently in the successive-conversion test.
  const result = await verifyPublicInformation(fixture.event, {
    roots: fixture.config.roots, nowSeconds: fixture.event.created_at,
    selection: fixture.config.selected.map(e => ({ botDid: e.botDid, types: [e.type] })),
    snapshots: { get: () => snapshot,
      accept: async (_id, value) => verifySnapshot(value, { root, nowSeconds: fixture.event.created_at }),
      recordCertificate: () => {} },
  });
  assert.equal(result.authorized, true); assert.equal(result.priority, 3); assert.equal(result.snapshot.sequence, 7);
});

test('wire-v1 canonical bytes, domain hash, low-S signatures, genesis and Android reciprocal binding', () => {
  assert.equal(Buffer.from(canonical({ aa: 1, b: 2 })).toString('hex'), 'a261620262616101');
  assert.equal(rootId(root.rootPubkey), '0f715baf5d4c2ed329785cef29e562f73488c8a2bb9dbc5700b361d54b9b0554');
  assert.equal(certId(certificate), bodyHash(certificate.body));
  assert.deepEqual(decodeCanonical(canonical(certificate)), certificate);
  assert.equal(importer.binding.atprotoSig.length, 88);
  assert.equal(verifyBinding(importer.binding, { did: importer.did, atprotoPubkey: keyPublic(importer.atprotoSecret), nostrPubkey: importer.binding.declaration.nostrPubkey }), true);
  assert.equal(verifyImporterGenesis({ genesis: importer.genesis, binding: importer.binding, plcPubkey: keyPublic(importer.plcSecret) }).status, 'unregistered-genesis-only');
  const changed = structuredClone(importer.genesis); changed.alsoKnownAs.push('at://evil.invalid');
  assert.throws(() => verifyImporterGenesis({ genesis: changed, binding: importer.binding, plcPubkey: keyPublic(importer.plcSecret) }));
});

test('normal atproto record, signed commit/MST and Nostr chain survive successive conversion', async () => {
  const first = await make(); const decoded = await decodePost(first.event);
  const reconstructed = await reconstruct(decoded);
  assert.equal(reconstructed.entries.length, 1);
  const auth = await verifyPublicInformation(first.event, options());
  assert.equal(auth.authorized, true); assert.equal(auth.priority, 3); assert.equal(auth.effectivePriority, 3);
  assert.equal(auth.botDid, JMA_SELECTION[0].botDid); assert.equal(auth.snapshot.sequence, 1);
  assert.equal(auth.checks.revocation, 'fresh-as-of');
  const second = await make({ state: first.state });
  assert.notEqual(first.event.id, second.event.id);
  assert.equal((await reconstruct(await decodePost(second.event), reconstructed.entries)).entries.length, 2);
});

test('unknown root, clock, selection, DID/key, single-signature binding, and commit attacks fail closed', async () => {
  const { event } = await make();
  for (const override of [{ roots: [] }, { roots: [{ ...root, enabled: false }] }, { nowSeconds: undefined },
    { nowSeconds: now - 1 }, { selection: [] }, { selection: [{ botDid: JMA_SELECTION[0].botDid, types: ['tsunami'] }] },
    { snapshots: null }, { nowSeconds: now + 600 }, { roots: [{ ...root, scope: { ...scope, bsaf: [{ botDid: JMA_SELECTION[0].botDid, types: ['tsunami'] }] } }] }]) {
    await assert.rejects(verifyPublicInformation(event, options(override)));
  }
  await assert.rejects(verifyPublicInformation({ ...event, content: 'tampered' }, options()));
  for (const field of ['atprotoSig', 'nostrSig']) {
    const badImporter = structuredClone(importer);
    badImporter.binding[field] = field === 'atprotoSig' ? Buffer.alloc(64).toString('base64') : '0'.repeat(128);
    await assert.rejects(verifyPublicInformation((await make({ importer: badImporter })).event, options()));
  }
  const wrong = createImporter();
  await assert.rejects(verifyPublicInformation((await make({ importer: wrong })).event, options()));
  const tags = event.tags.map(t => t[0] === `${NS}:commit` ? [t[0], (() => {
    const commit = cbor.decode(Buffer.from(t[1], 'base64')); commit.sig[0] ^= 1;
    return Buffer.from(cbor.encode(commit)).toString('base64').replace(/=+$/, '');
  })()] : t);
  await assert.rejects(verifyPublicInformation(resign(event, { tags }), options()));
});

test('exact certificate fields, roles, times, root scope and high-S/DER reject; intermediates unsupported', () => {
  const args = { roots: [root], nowSeconds: now, eventSeconds: now, selection: JMA_SELECTION, botDid: JMA_SELECTION[0].botDid, type: 'earthquake' };
  for (const chain of [[], [certificate, certificate], Array(5).fill(certificate)]) assert.throws(() => verifyChain(chain, args));
  for (const overrides of [{ nowSeconds: now + 86400 }, { eventSeconds: now - 11 }, { eventSeconds: now + 1 }]) {
    assert.throws(() => verifyChain([certificate], { ...args, ...overrides }));
  }
  const signature = secp256k1.Signature.fromCompact(certificate.sig);
  const high = new secp256k1.Signature(signature.r, secp256k1.CURVE.n - signature.s).toCompactHex();
  for (const sig of [high, signature.toDERHex(), '00'.repeat(64)]) assert.throws(() => verifyChain([{ ...certificate, sig }], args));
  for (const change of [{ extra: true }, { role: 'issuer' }, { canDelegate: true }, { subjectDid: '' }, { permissions: ['other'] },
    { scope: { ...scope, orgIds: ['different'] } }, { serial: 'A'.repeat(32) }, { notBefore: '2026-02-30T00:00:00Z' }]) {
    assert.throws(() => issueCertificate(rootSecret, { ...certificate.body, ...change }));
  }
});

test('snapshot freshness, rollback, equivocation, cumulative CRL and durable failure are mandatory', async () => {
  const s = snapshot();
  await verifySnapshot(s, { root, nowSeconds: now });
  await assert.rejects(verifySnapshot(s, { root, nowSeconds: now + 3599 }));
  await assert.rejects(verifySnapshot(s, { root, nowSeconds: now - 2 }));
  await assert.rejects(verifySnapshot(s, { root: { ...root, minSequence: 2 }, nowSeconds: now }));
  await assert.rejects(verifySnapshot(s, { root, nowSeconds: now, previous: snapshot(2) }));
  await assert.rejects(verifySnapshot(snapshot(1, { validUntil: timestamp(now + 100) }), { root, nowSeconds: now, previous: s }));
  const revoked = snapshot(2, { revokedCertIds: [certId(certificate)] });
  await assert.rejects(verifySnapshot(snapshot(3), { root, nowSeconds: now, previous: revoked }));
  const { event } = await make();
  for (const s of [revoked, snapshot(2, { revokedPubkeys: [certificate.body.subjectPubkey] }), snapshot(2, { revokedPubkeys: [root.rootPubkey] })]) {
    await assert.rejects(verifyPublicInformation(event, options({ snapshots: memorySnapshots(s) })));
  }
  const snapshots = memorySnapshots(); snapshots.accept = async () => { throw new Error('disk failed'); };
  await assert.rejects(verifyPublicInformation(event, options({ snapshots })), /disk failed/);
});

test('the durable adapter rejects A and B permanently after valid issuer+serial equivocation', async () => {
  const snapshots = memorySnapshots();
  const a = (await make()).event;
  const bCertificate = issueCertificate(rootSecret, { ...certificate.body, orgName: 'Conflicting B' });
  const b = (await make({ chain: [bCertificate] })).event;
  await verifyPublicInformation(a, options({ snapshots }));
  await assert.rejects(verifyPublicInformation(b, options({ snapshots })), /serial conflict/);
  await assert.rejects(verifyPublicInformation(a, options({ snapshots })), /serial conflict/);
  await assert.rejects(verifyPublicInformation(b, options({ snapshots })), /serial conflict/);
});

test('priority is a bounded hint, never an authorization fallback', async () => {
  const { event } = await make();
  assert.equal(effectivePriority(event), 1); assert.equal(effectivePriority(event, true), 3);
  for (const priority of [0, 1, 2, 3, 4, -1, '3']) {
    const ev = (await make({ priority })).event;
    assert.equal(effectivePriority(ev, true), [0, 1, 2, 3].includes(priority) ? priority : 1);
  }
  const tag = event.tags.find(t => t[0] === `${NS}:ext:${PRIORITY}`);
  const duplicate = resign(event, { tags: [...event.tags, tag] });
  assert.equal((await verifyPublicInformation(duplicate, options())).priority, 1);
});

test('BSAF ambiguity, URI/CID, exact identity fields and mesh resources cannot be repaired', async () => {
  for (const changes of [{ tags: [...extension.tags, 'type:tsunami'] }, { tags: extension.tags.filter(t => !t.startsWith('source:')) },
    { original: { ...extension.original, uri: extension.original.uri + '?q=1' } },
    { original: { ...extension.original, cid: 'not-a-cid' } }, { langs: ['ja\n'] }, { original: null }]) {
    await assert.rejects(verifyPublicInformation((await make({ extensions: [{ ...extension, ...changes }] })).event, options()));
  }
  await assert.rejects(verifyPublicInformation((await make({ text: 'x'.repeat(32768) })).event, options()));
  const { event } = await make();
  const identityTag = event.tags.find(t => t[0] === `${NS}:ext:${IDENTITY}`);
  await assert.rejects(verifyPublicInformation(resign(event, { tags: [...event.tags, identityTag] }), options()));
});

test('noncanonical CBOR, duplicate keys, float, Unicode and resource limits reject before recursive parsing', () => {
  for (const bytes of [Buffer.from('bf ff', 'hex'), Buffer.from('a2616101616102', 'hex'), Buffer.from('a161611801', 'hex'),
    Buffer.from('a2616201616102', 'hex'), Buffer.from('a000', 'hex'), cbor.encode({ float: 1.5 }),
    Buffer.concat([Buffer.alloc(1000, 0x81), Buffer.from([0])])]) assert.throws(() => decodeCanonical(bytes));
  assert.throws(() => canonical({ bad: '\ud800' }));
  assert.throws(() => canonical({ negative: -1 }));
  assert.throws(() => createRoot({ scope: { orgIds: ['x'], bsaf: [{ botDid: JMA_SELECTION[0].botDid, types: ['tsunami', 'earthquake'] }] }, snapshotUrl: root.snapshotUrl }));
});
