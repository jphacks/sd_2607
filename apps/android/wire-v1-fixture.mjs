// TEST KEYS ONLY. Uses the existing PDS dev dependencies, never installs or modifies them.
// Run from apps/android: node wire-v1-fixture.mjs [--write]
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { encode, decode } from '../../pds/node_modules/@ipld/dag-cbor/src/index.js';
import { secp256k1, schnorr } from '../../pds/node_modules/@noble/curves/esm/secp256k1.js';
import { CID } from '../../pds/node_modules/multiformats/dist/src/cid.js';
import { create } from '../../pds/node_modules/multiformats/dist/src/hashes/digest.js';
const sha = x => new Uint8Array(createHash('sha256').update(x).digest());
const hex = x => Buffer.from(x).toString('hex');
const key = n => Uint8Array.from([...new Array(31).fill(0), n]);
const pub = n => hex(secp256k1.getPublicKey(key(n), true));
const hash = x => sha(encode(x));
const sign = (body, n = 1) => hex(secp256k1.sign(hash(body), key(n), { lowS: true }).toCompactRawBytes());
const signed = body => ({ body, sig: sign(body) });
const b64 = x => Buffer.from(x).toString('base64').replace(/=+$/, '');
const cid = x => CID.createV1(0x71, create(0x12, sha(x)));
const did = 'did:plc:aaaaaaaaaaaaaaaaaaaaaaaa';
const botDid = 'did:plc:bbbbbbbbbbbbbbbbbbbbbbbb';
const rootId = hex(sha(Buffer.from(pub(1), 'hex')));
const scope = { orgIds: ['test-org'], bsaf: [{ botDid, types: ['earthquake'] }] };
const body = { type: 'mosimo.oriom.dev/identity-certificate', version: 1, serial: '01'.repeat(16), rootId,
  issuerPubkey: pub(1), subjectPubkey: pub(4), subjectDid: did, orgId: 'test-org', orgName: 'TEST importer',
  role: 'importer', permissions: ['bsaf-import'], canDelegate: false, scope,
  notBefore: '2026-10-18T00:00:00Z', notAfter: '2026-10-20T00:00:00Z' };
const certificate = signed(body);
const declaration = { type: 'mndt.keyBinding', version: 1, did, atprotoPubkey: pub(4),
  nostrPubkey: hex(schnorr.getPublicKey(key(3))), consent: "Use this key pair for this DID's MNDT publications" };
const binding = { declaration, atprotoSig: Buffer.from(sign(declaration, 4), 'hex').toString('base64'),
  nostrSig: hex(schnorr.sign(hash(declaration), key(3), new Uint8Array(32))) };
const identity = { version: 1, chain: [certificate], binding };
const bsaf = { tags: ['bsaf:v1', 'type:earthquake', 'value:5+', 'time:2026-10-18T23:59:00Z', 'target:jp', 'source:jma'], langs: ['ja'],
  original: { uri: `at://${botDid}/app.bsky.feed.post/3mtest23456789`, cid: cid(encode({ text: 'Original test post' })).toString(), createdAt: '2026-10-18T23:59:01Z' } };
const priority = { version: 1, level: 3 };
const prefix = 'mosimo.oriom.dev:';
const createdAt = '2026-10-19T00:00:00Z', deliveryExpiresAt = '2026-10-19T01:00:00Z';
const extensions = [['dev.oriom.mosimo.defs#identity', identity], ['dev.oriom.mosimo.defs#bsafV1', bsaf], ['dev.oriom.mosimo.defs#priority', priority]];
const record = { $type: 'dev.oriom.mosimo.post', text: 'TEST public import — 原Bot署名ではない', createdAt, deliveryExpiresAt,
  extensions: extensions.map(([type, fields]) => ({ $type: type, ...fields })) };
// A valid commit signature, deliberately NOT an MST inclusion proof (0020 §5).
const unsigned = { did, version: 3, data: cid(encode({ e: [], l: null })), rev: '3mtest234567a', prev: null };
const commit = { ...unsigned, sig: Buffer.from(sign(unsigned, 4), 'hex') };
const tags = [['t', 'mosimo.oriom.dev'], [prefix + 'version', '1'], [prefix + 'commit', b64(encode(commit))], [prefix + 'base', ''],
  [prefix + 'path', 'dev.oriom.mosimo.post/3mtest234567a'], [prefix + 'created-at', createdAt], [prefix + 'delivery-expires-at', deliveryExpiresAt],
  ...extensions.map(([type, fields]) => [prefix + 'ext:' + type, b64(encode(fields))])];
const event = { pubkey: declaration.nostrPubkey, created_at: Date.parse(createdAt) / 1000, kind: 1, tags, content: record.text };
const eventHash = sha(Buffer.from(JSON.stringify([0, event.pubkey, event.created_at, event.kind, event.tags, event.content])));
event.id = hex(eventHash); event.sig = hex(schnorr.sign(eventHash, key(3), new Uint8Array(32)));
const snapshotBody = { type: 'mosimo.oriom.dev/trust-snapshot', version: 1, rootId, sequence: 7,
  issuedAt: '2026-10-19T00:00:00Z', validUntil: '2026-10-19T01:00:00Z', revokedCertIds: [], revokedPubkeys: [] };
const fixture = { testOnly: true, config: { roots: [{ rootId, rootPubkey: pub(1), enabled: true, scope,
  snapshotUrl: 'https://trust.example.invalid/snapshot.cbor', minSequence: 7 }], selected: [{ botDid, type: 'earthquake' }] },
  certificate, binding, event, snapshotHex: hex(encode(signed(snapshotBody))),
  certificateBodyHex: hex(encode(body)), certId: hex(hash(body)), snapshotBodyHex: hex(encode(snapshotBody)),
  snapshotBodyHash: hex(hash(snapshotBody)), recordHex: hex(encode(record)), commitHex: hex(encode(commit)) };
const path = new URL('./bundle-core/src/test/resources/wire-v1.json', import.meta.url);
if (process.argv.includes('--write')) writeFileSync(path, JSON.stringify(fixture, null, 2) + '\n');
const saved = JSON.parse(readFileSync(path, 'utf8'));
assert.deepEqual(saved, fixture);
for (const item of [certificate, decode(Buffer.from(saved.snapshotHex, 'hex'))])
  assert(secp256k1.verify(item.sig, hash(item.body), pub(1), { lowS: true }));
assert(secp256k1.verify(Buffer.from(binding.atprotoSig, 'base64'), hash(declaration), pub(4), { lowS: true }));
assert(schnorr.verify(binding.nostrSig, hash(declaration), declaration.nostrPubkey));
assert(schnorr.verify(event.sig, eventHash, event.pubkey));
assert(secp256k1.verify(commit.sig, hash(unsigned), pub(4), { lowS: true }));
console.log('Node wire-v1 fixture: canonical bytes, IDs, root/leaf/binding/commit/event/snapshot signatures PASS');
