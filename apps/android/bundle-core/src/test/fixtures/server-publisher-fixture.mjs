// TEST ONLY, offline. --write uses fresh throwaway keys from the actual server exports.
// No secrets are written. Default mode verifies the checked-in public fixture, without regenerating it.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { createRoot, createImporter, issueCertificate, createSnapshot, createImporterEvent,
  canonical, decodeCanonical, rootId, certId, bodyHash, keyPublic, timestamp, BSAF, NS,
  verifyPublicInformation, verifySnapshot } from '../../../../../../pds/public-information.mjs';
import { decodePost, verifyCommit, reconstruct } from '../../../../../../pds/repository.mjs';
import { CID } from '../../../../../../pds/node_modules/multiformats/dist/src/cid.js';
import { create } from '../../../../../../pds/node_modules/multiformats/dist/src/hashes/digest.js';

const path = new URL('../resources/server-publisher-wire-v1.json', import.meta.url);
const now = 1792368000;
const checkedAtSeconds = now + 10;
const hex = bytes => Buffer.from(bytes).toString('hex');
const hash = bytes => createHash('sha256').update(bytes).digest();
const cidFor = bytes => CID.createV1(0x71, create(0x12, hash(bytes))).toString();
function meshPayload(event) {
  const tags = event.tags.map(t => t[0] === `${NS}:commit` || t[0].startsWith(`${NS}:ext:`)
    ? [t[0], Buffer.from(t[1], 'base64')] : t[0] === `${NS}:version` && t[1] === '1' ? [t[0], 1] : t);
  return canonical([1, Buffer.from(event.pubkey, 'hex'), event.created_at, event.kind, tags,
    event.content, Buffer.from(event.sig, 'hex')], { signed: false });
}
async function vector(event, baseEntries = []) {
  const decoded = await decodePost(event);
  const rebuilt = await reconstruct(decoded, baseEntries); // MUST match the signed commit's actual MST root.
  return { event, recordHex: hex(decoded.recordBytes), commitHex: hex(decoded.commitBytes),
    recordCid: decoded.recordCid.toString(), commitCid: decoded.commitCid.toString(), path: decoded.path,
    mstRoot: decoded.commit.data.toString(), meshPayloadHex: hex(meshPayload(event)),
    repositoryEntries: rebuilt.entries.map(e => ({ key: e.key, cid: e.value.toString() })),
    repositoryBlocks: rebuilt.blocks.map(b => ({ cid: b.cid.toString(), hex: hex(b.bytes) })) };
}
async function generate() {
  const botDid = 'did:plc:bbbbbbbbbbbbbbbbbbbbbbbb';
  const scope = { orgIds: ['test-server-importer'], bsaf: [{ botDid, types: ['earthquake'] }] };
  const rootKeys = createRoot({ scope, snapshotUrl: 'https://trust.example.invalid/server-test.cbor', minSequence: 7 });
  const importer = createImporter({ endpoint: 'https://pds.example.invalid' });
  const certificate = issueCertificate(rootKeys.secretKey, { subjectDid: importer.did,
    subjectPubkey: keyPublic(importer.atprotoSecret), orgId: scope.orgIds[0], orgName: 'TEST ONLY server importer',
    scope, notBefore: timestamp(now - 60), notAfter: timestamp(now + 86400) });
  const snapshot = createSnapshot(rootKeys.secretKey, { sequence: 7,
    issuedAt: timestamp(now - 1), validUntil: timestamp(now + 3599) });
  const original = { $type: 'app.bsky.feed.post', text: 'TEST ONLY earthquake — 訓練',
    createdAt: timestamp(now - 30), tags: ['bsaf:v1', 'type:earthquake', 'value:5+',
      `time:${timestamp(now - 30)}`, 'target:jp-kanto', 'source:jma'], langs: ['ja'] };
  const originalBytes = canonical(original);
  const extension = { $type: BSAF, tags: original.tags, langs: original.langs,
    original: { uri: `at://${botDid}/app.bsky.feed.post/test-server`, cid: cidFor(originalBytes), createdAt: original.createdAt } };
  const make = (seconds, state, text) => createImporterEvent({ importer, chain: [certificate], text,
    extensions: [extension], nowSeconds: seconds, deliveryExpiresAt: timestamp(now + 600), priority: 3, ...(state ? { state } : {}) });
  const first = await make(now, null, `${original.text}\n出典: ${extension.original.uri}（転載・原Bot署名ではない）`);
  const second = await make(now + 1, first.state, `${first.record.text}\nTEST ONLY second repository entry`);
  const firstVector = await vector(first.event);
  const entries = (await reconstruct(await decodePost(first.event))).entries;
  const secondVector = await vector(second.event, entries);
  assert.deepEqual((await decodePost(first.event)).record, first.record);
  assert.deepEqual((await decodePost(second.event)).record, second.record);
  return { testOnly: true, generatedBy: 'pds/public-information.mjs:createImporterEvent',
    nowSeconds: now, checkedAtSeconds, config: { roots: [{ rootId: rootId(rootKeys.root.rootPubkey), ...rootKeys.root }],
      selected: [{ botDid, type: 'earthquake' }] }, certificate, binding: importer.binding,
    plcGenesis: importer.genesis, originalRecordHex: hex(originalBytes), snapshotHex: hex(canonical(snapshot)),
    certificateBodyHex: hex(canonical(certificate.body)), certId: certId(certificate),
    snapshotBodyHex: hex(canonical(snapshot.body)), snapshotBodyHash: bodyHash(snapshot.body),
    ...firstVector, posts: [firstVector, secondVector] };
}
async function verify(fixture) {
  assert.equal(fixture.testOnly, true);
  assert.equal(fixture.generatedBy, 'pds/public-information.mjs:createImporterEvent');
  assert.equal(fixture.nowSeconds, now);
  assert.equal(fixture.checkedAtSeconds, checkedAtSeconds);
  const [root] = fixture.config.roots;
  assert.equal(rootId(root.rootPubkey), root.rootId);
  assert.equal(hex(canonical(fixture.certificate.body)), fixture.certificateBodyHex);
  assert.equal(certId(fixture.certificate), fixture.certId);
  const snapshot = decodeCanonical(Buffer.from(fixture.snapshotHex, 'hex'));
  assert.equal(hex(canonical(snapshot.body)), fixture.snapshotBodyHex);
  assert.equal(bodyHash(snapshot.body), fixture.snapshotBodyHash);
  const original = decodeCanonical(Buffer.from(fixture.originalRecordHex, 'hex'));
  const serials = new Map();
  let accepted = null;
  const snapshots = { get: () => snapshot,
    async accept(id, value) { assert.equal(id, root.rootId); await verifySnapshot(value, { root, nowSeconds: checkedAtSeconds, previous: accepted }); accepted = value; },
    recordCertificate(issuer, serial, id) { const key = `${issuer}:${serial}`; assert(!serials.has(key) || serials.get(key) === id); serials.set(key, id); } };
  let entries = [], base = null;
  assert.equal(fixture.posts.length, 2);
  for (const [index, post] of fixture.posts.entries()) {
    const decoded = await decodePost(post.event);
    assert.equal(await verifyCommit(decoded, fixture.plcGenesis.verificationMethods.atproto), true);
    assert.equal(decoded.base?.toString() ?? null, base);
    const rebuilt = await reconstruct(decoded, entries);
    assert.equal(rebuilt.entries.length, index + 1);
    assert.equal(hex(decoded.recordBytes), post.recordHex);
    assert.equal(hex(decoded.commitBytes), post.commitHex);
    assert.equal(decoded.recordCid.toString(), post.recordCid);
    assert.equal(decoded.commitCid.toString(), post.commitCid);
    assert.equal(decoded.path, post.path);
    assert.equal(decoded.commit.data.toString(), post.mstRoot);
    assert.equal(hex(meshPayload(post.event)), post.meshPayloadHex);
    assert.deepEqual(rebuilt.entries.map(e => ({ key: e.key, cid: e.value.toString() })), post.repositoryEntries);
    assert.deepEqual(rebuilt.blocks.map(b => ({ cid: b.cid.toString(), hex: hex(b.bytes) })), post.repositoryBlocks);
    for (const block of post.repositoryBlocks) assert.equal(cidFor(Buffer.from(block.hex, 'hex')), block.cid);
    const bsaf = decoded.record.extensions.find(e => e.$type === BSAF);
    assert.equal(bsaf.original.cid, cidFor(Buffer.from(fixture.originalRecordHex, 'hex')));
    assert.deepEqual(bsaf.tags, original.tags); assert.deepEqual(bsaf.langs, original.langs);
    const authorized = await verifyPublicInformation(post.event, { roots: fixture.config.roots, snapshots,
      nowSeconds: checkedAtSeconds, selection: fixture.config.selected.map(s => ({ botDid: s.botDid, types: [s.type] })) });
    assert.equal(authorized.authorized, true); assert.equal(authorized.priority, 3);
    assert.equal(authorized.leafCertId, fixture.certId); assert.equal(authorized.snapshot.sequence, 7);
    entries = rebuilt.entries; base = decoded.commitCid.toString();
  }
  for (const [name, value] of Object.entries(fixture.posts[0])) assert.deepEqual(fixture[name], value);
}
if (process.argv.includes('--write')) {
  const fixture = await generate();
  await verify(fixture);
  await writeFile(path, JSON.stringify(fixture, null, 2) + '\n');
}
await verify(JSON.parse(await readFile(path, 'utf8')));
console.log('Server publisher fixture PASS: 2 actual createImporterEvent posts, commit signatures, full chained MST reconstruction, identity/BSAF/priority authorization');
console.log(path.pathname);
