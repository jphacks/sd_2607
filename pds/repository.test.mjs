import assert from 'node:assert/strict';
import test from 'node:test';
import { MST, MemoryBlockstore, readCar } from '@atproto/repo';
import { Secp256k1Keypair, P256Keypair } from '@atproto/crypto';
import * as cbor from '@ipld/dag-cbor';
import { CID } from 'multiformats/cid';
import { sha256 } from 'multiformats/hashes/sha2';
import { finalizeEvent, generateSecretKey, verifyEvent } from 'nostr-tools';
import { decodePost, verifyCommit, reconstruct } from './repository.mjs';

const ns = 'mosimo.oriom.dev';
const collection = 'dev.oriom.mosimo.post';
const did = 'did:plc:abcdefghijklmnopqrstuvwx';
const nostrKey = generateSecretKey();
const signingKey = await Secp256k1Keypair.create();
const b64 = bytes => Buffer.from(bytes).toString('base64').replace(/=+$/, '');
const cidFor = async bytes => CID.createV1(cbor.code, await sha256.digest(bytes));
const encode = value => b64(cbor.encode(value));
const signEvent = event => finalizeEvent({
  kind: event.kind, content: event.content, created_at: event.created_at,
  tags: event.tags.map(tag => [...tag]),
}, nostrKey);

async function fixture({ index = 0, entries = [], base = null, extensions = [], key = signingKey } = {}) {
  const record = {
    $type: collection,
    text: `避難所\r\nか\u3099・が・🏠 ${index}`,
    createdAt: '2026-10-17T12:34:56.1200000000001Z',
    deliveryExpiresAt: '2026-10-18T12:34:56Z',
    extensions,
  };
  const path = `${collection}/3jzfcijpj2z2${'abcdefghij'[index]}`;
  const recordBytes = new Uint8Array(cbor.encode(record));
  const recordCid = await cidFor(recordBytes);
  let tree = await MST.create(new MemoryBlockstore());
  for (const entry of entries) tree = await tree.add(entry.key, entry.value);
  tree = await tree.add(path, recordCid);
  const unsigned = { did, version: 3, data: await tree.getPointer(), rev: path.split('/')[1], prev: null };
  const commit = { ...unsigned, sig: await key.sign(cbor.encode(unsigned)) };
  const commitBytes = new Uint8Array(cbor.encode(commit));
  const tags = [
    ['t', ns], [`${ns}:version`, '1'], [`${ns}:commit`, b64(commitBytes)],
    [`${ns}:base`, base?.toString() ?? ''], [`${ns}:path`, path],
    [`${ns}:created-at`, record.createdAt], [`${ns}:delivery-expires-at`, record.deliveryExpiresAt],
    ...extensions.map(({ $type, ...fields }) => [`${ns}:ext:${$type}`, encode(fields)]),
  ];
  const event = signEvent({ kind: 1, content: record.text, created_at: 1792240496, tags });
  return { event, record, recordBytes, recordCid, commit, commitBytes, path };
}

function changeTag(event, name, value) {
  return signEvent({ ...event, tags: event.tags.map(tag => tag[0] === `${ns}:${name}` ? [tag[0], value] : tag) });
}

async function changeCommit(event, change) {
  const original = cbor.decode(Buffer.from(event.tags.find(tag => tag[0] === `${ns}:commit`)[1], 'base64'));
  return changeTag(event, 'commit', encode(change(original)));
}

test('decodes exact bytes, CIDs, text, and ordered unknown extension data without mutation', async () => {
  const link = await cidFor(cbor.encode({ unknown: true }));
  const extensions = [
    { $type: 'org.example.future#opaque', bytes: Uint8Array.of(251, 255), link,
      nested: { missingIsNotNull: null, list: [true, 42, 'unchanged'] } },
    { $type: 'org.example.future#opaque', future: [] },
    { $type: 'org.example.other#value', flag: false },
  ];
  const source = await fixture({ extensions });
  const before = JSON.stringify(source.event);
  const decoded = await decodePost(source.event);
  assert.deepEqual(decoded.record, source.record);
  assert.deepEqual(decoded.recordBytes, source.recordBytes);
  assert.deepEqual(decoded.commitBytes, source.commitBytes);
  assert.ok(decoded.recordCid.equals(source.recordCid));
  assert.ok(decoded.commitCid.equals(await cidFor(source.commitBytes)));
  assert.equal(decoded.base, null);
  assert.equal(JSON.stringify(source.event), before);
  assert.equal(await verifyCommit(decoded, signingKey.did()), true);
});

test('verifies official secp256k1 and P-256 commit signatures', async () => {
  for (const key of [signingKey, await P256Keypair.create()]) {
    const { event } = await fixture({ key });
    const decoded = await decodePost(event);
    assert.equal(await verifyCommit(decoded, key.did()), true);
    await assert.rejects(verifyCommit(decoded, (await Secp256k1Keypair.create()).did()));
    decoded.commit.sig[0] ^= 1;
    await assert.rejects(verifyCommit(decoded, key.did()));
  }
});

test('reconstructs multiple posts, complete MST blocks, and a CAR rooted in the original commit', async () => {
  let entries = [];
  let base = null;
  for (let index = 0; index < 8; index++) {
    const source = await fixture({ index, entries, base });
    const decoded = await decodePost(source.event);
    assert.ok(base === null ? decoded.base === null : decoded.base.equals(base));
    await verifyCommit(decoded, signingKey.did());
    const result = await reconstruct(decoded, [...entries].reverse());
    assert.equal(result.entries.length, index + 1);
    assert.ok(result.entries.every(entry => Object.getPrototypeOf(entry) === Object.prototype));
    const car = await readCar(result.car);
    assert.equal(car.roots.length, 1);
    assert.ok(car.roots[0].equals(decoded.commitCid));
    assert.deepEqual(car.blocks.get(decoded.commitCid), source.commitBytes);
    assert.deepEqual(car.blocks.get(decoded.recordCid), source.recordBytes);
    const storage = new MemoryBlockstore();
    for (const { cid, bytes } of result.blocks) {
      assert.ok(cid.equals(await cidFor(bytes)));
      await storage.putBlock(cid, bytes);
    }
    const loaded = MST.load(storage, decoded.commit.data);
    const loadedEntries = await loaded.list();
        assert.deepEqual(loadedEntries.map(entry => entry.key), result.entries.map(entry => entry.key));
        assert.ok(loadedEntries.every((entry, i) => entry.value.equals(result.entries[i].value)));
    entries = result.entries;
    base = decoded.commitCid;
  }
});

test('rejects changed content, ID, signature, and forged nostr-tools verification cache', async () => {
  const { event } = await fixture();
  assert.equal(verifyEvent(event), true);
  await assert.rejects(decodePost({ ...event, content: 'tampered' }), /Nostr signature/);
  await assert.rejects(decodePost({ ...event, id: '0'.repeat(64) }), /Nostr signature/);
  await assert.rejects(decodePost({ ...event, sig: '0'.repeat(128) }), /Nostr signature/);
  await assert.rejects(decodePost({ ...event, pubkey: event.pubkey.toUpperCase() }), /Nostr pubkey/);
  await assert.rejects(decodePost(signEvent({ ...event, kind: 5 })), /post event/);
});

for (const name of ['version', 'commit', 'base', 'path', 'created-at', 'delivery-expires-at']) {
  test(`requires one exact two-element ${name} tag`, async () => {
    const { event } = await fixture();
    const tag = event.tags.find(tag => tag[0] === `${ns}:${name}`);
    for (const tags of [
      event.tags.filter(value => value !== tag),
      [...event.tags, tag],
      event.tags.map(value => value === tag ? [...tag, 'extra'] : value),
    ]) await assert.rejects(decodePost(signEvent({ ...event, tags })));
  });
}

test('requires the namespace tag exactly once but permits unrelated search and unknown tags', async () => {
  const { event } = await fixture();
  for (const tags of [event.tags.slice(1), [...event.tags, ['t', ns]],
    event.tags.map(tag => tag[0] === 't' ? [...tag, 'extra'] : tag)]) {
    await assert.rejects(decodePost(signEvent({ ...event, tags })));
  }
  const decoded = await decodePost(signEvent({ ...event, tags: [
    ['t', 'other'], ['unknown', 'kept in event', 'extra'], ...event.tags,
  ] }));
  assert.equal(decoded.record.extensions.length, 0);
  await assert.rejects(decodePost(changeTag(event, 'version', '2')), /version/);
});

test('rejects padded, URL-safe, whitespace, and noncanonical Base64 or CBOR', async () => {
  const { event } = await fixture();
  const tag = event.tags.find(tag => tag[0] === `${ns}:commit`)[1];
  for (const value of [`${tag}=`, `${tag}\n`, '-_8', 'A', 'AB']) {
    await assert.rejects(decodePost(changeTag(event, 'commit', value)));
  }
  for (const bytes of [
    Uint8Array.of(0xbf, 0xff), // indefinite map
    Uint8Array.of(0xa1, 0x61, 0x78, 0x18, 0x01), // nonminimal integer
    Uint8Array.of(0xa2, 0x61, 0x62, 0x01, 0x61, 0x61, 0x02), // unordered map
    Uint8Array.of(0xa2, 0x61, 0x61, 0x01, 0x61, 0x61, 0x02), // duplicate map key
    Uint8Array.of(0xa0, 0x00), // trailing data
    cbor.encode({ float: 1.5 }),
  ]) {
    const tags = [...event.tags, [`${ns}:ext:org.example.future#value`, b64(bytes)]];
    await assert.rejects(decodePost(signEvent({ ...event, tags })));
  }
});

test('rejects non-object extensions, absent types, conflicting types, and extra tag values', async () => {
  const { event } = await fixture();
  for (const tag of [
    [`${ns}:ext:`, encode({})],
    [`${ns}:ext:org.example.future#value`, encode([])],
    [`${ns}:ext:org.example.future#value`, encode(null)],
    [`${ns}:ext:org.example.future#value`, encode({ $type: 'org.example.other#value' })],
    [`${ns}:ext:org.example.future#value`, encode({}), 'extra'],
  ]) await assert.rejects(decodePost(signEvent({ ...event, tags: [...event.tags, tag] })));
});

test('validates actual UTC calendar dates and exact Unix seconds without rounding fractions', async () => {
  const { event } = await fixture();
  for (const value of ['2026-02-29T12:34:56Z', '2026-04-31T12:34:56Z',
    '2026-10-17T24:00:00Z', '2026-10-17T12:34:60Z', '2026-10-17T12:34:56+00:00',
    '2026-10-17t12:34:56z', '0000-01-01T00:00:00Z', '2026-13-01T00:00:00Z']) {
    await assert.rejects(decodePost(changeTag(event, 'delivery-expires-at', value)), /UTC/);
  }
  for (const value of ['2024-02-29T00:00:00Z', '0001-01-01T00:00:00Z',
    '2000-02-29T12:00:00.0000000000000000001Z']) {
    assert.equal((await decodePost(changeTag(event, 'delivery-expires-at', value))).record.deliveryExpiresAt, value);
  }
  await assert.rejects(decodePost(signEvent({ ...event, created_at: event.created_at + 1 })), /created_at/);
  await assert.rejects(decodePost(signEvent({ ...event, created_at: event.created_at + 0.5 })));
});

test('rejects invalid TID paths, base CIDs, and signed v3 commit shapes', async () => {
  const { event } = await fixture();
  for (const value of [`${collection}/kjzfcijpj2z2a`, `${collection}/3JZFCIJPJ2Z2A`,
    `${collection}/3jzfcijpj2z2a/extra`, 'app.bsky.feed.post/3jzfcijpj2z2a']) {
    await assert.rejects(decodePost(changeTag(event, 'path', value)), /path/);
  }
  await assert.rejects(decodePost(changeTag(event, 'base', 'not-a-cid')));
  for (const change of [
    c => ({ ...c, version: 2 }), c => ({ ...c, rev: 'kjzfcijpj2z2a' }),
    c => ({ ...c, did: 'did:web:example.com' }), c => ({ ...c, data: c.data.toString() }),
    c => ({ ...c, prev: undefined }), c => ({ ...c, sig: new Uint8Array(63) }),
    c => ({ ...c, extra: true }),
  ]) {
    // Undefined is not CBOR data; removing prev represents the malformed wire value.
    const changed = await changeCommit(event, c => {
      const next = change(c);
      if (next.prev === undefined) delete next.prev;
      return next;
    });
    await assert.rejects(decodePost(changed));
  }
});

test('rejects duplicate base paths, overwrites, and signed roots inconsistent with the record', async () => {
  const first = await decodePost((await fixture()).event);
  const initial = await reconstruct(first);
  await assert.rejects(reconstruct(first, initial.entries), /Initial commit/);
  const secondSource = await fixture({ index: 1, entries: initial.entries, base: first.commitCid });
  const second = await decodePost(secondSource.event);
  await assert.rejects(reconstruct(second, [...initial.entries, ...initial.entries]), /Duplicate/);
  await assert.rejects(reconstruct(second, [...initial.entries, { key: second.path, value: second.recordCid }]), /already exists/);
  await assert.rejects(reconstruct(second), /root/);
  const tampered = await decodePost(signEvent({ ...secondSource.event, content: 'changed but Nostr-signed' }));
  assert.equal(await verifyCommit(tampered, signingKey.did()), true);
  await assert.rejects(reconstruct(tampered, initial.entries), /root/);
  assert.deepEqual(second.commitBytes, secondSource.commitBytes);
});
