import assert from 'node:assert/strict';
import { after, before, beforeEach, describe, test } from 'node:test';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';
import http from 'node:http';
import { WebSocket, WebSocketServer } from 'ws';
import { PGlite } from '@electric-sql/pglite';
import { MST, MemoryBlockstore, readCar, verifyProofs } from '@atproto/repo';
import { TID } from '@atproto/common-web';
import { Secp256k1Keypair, verifySignature } from '@atproto/crypto';
import * as cbor from '@ipld/dag-cbor';
import { decodeFirst } from 'cborg';
import { CID } from 'multiformats/cid';
import { sha256 } from 'multiformats/hashes/sha2';
import { finalizeEvent, generateSecretKey, verifyEvent } from 'nostr-tools';
import { schema, openSqliteStore, sqliteNow } from './store.mjs';
import { validateConfig } from './config.mjs';
import { createIngestion } from './ingest.mjs';
import { createServer } from './server.mjs';
import { startNetwork } from './network.mjs';

const namespace = 'mosimo.oriom.dev';
const collection = 'dev.oriom.mosimo.post';
const did = 'did:plc:abcdefghijklmnopqrstuvwx';
const repoKey = await Secp256k1Keypair.create();
const nostrKey = generateSecretKey();
const otherNostrKey = generateSecretKey();
const cidFor = async bytes => CID.createV1(cbor.code, await sha256.digest(bytes));
const b64 = bytes => Buffer.from(bytes).toString('base64').replace(/=+$/, '');
const signEvent = (event, key = nostrKey) => finalizeEvent({
  kind: event.kind ?? 1, created_at: event.created_at ?? 1792240496,
  tags: (event.tags ?? []).map(tag => [...tag]), content: event.content ?? '',
}, key);
const envelope = event => JSON.parse(JSON.stringify(event));

async function fixture({ index = 0, parent = null, key = repoKey, identity = did,
  badRoot = false, badSignature = false, author = nostrKey, rev, text } = {}) {
  const record = { $type: collection, text: text ?? `避難所 ${index}\r\nか\u3099 🏠`,
    createdAt: '2026-10-17T12:34:56.120Z', deliveryExpiresAt: '2026-10-18T12:34:56Z', extensions: [] };
  const path = `${collection}/3jzfcijpj2z2${'234567abcdefghijklmnopqrstuvwxyz'[index]}`;
  const recordBytes = new Uint8Array(cbor.encode(record));
  const recordCid = await cidFor(recordBytes);
  let tree = await MST.create(new MemoryBlockstore());
  for (const entry of parent?.entries ?? []) tree = await tree.add(entry.key, entry.value);
  tree = await tree.add(path, recordCid);
  const unsigned = { did: identity, version: 3, rev: rev ?? path.split('/')[1], prev: null,
    data: badRoot ? await cidFor(cbor.encode({ not: 'an MST' })) : await tree.getPointer() };
  const sig = await key.sign(cbor.encode(unsigned));
  if (badSignature) sig[0] ^= 1;
  const commit = { ...unsigned, sig };
  const commitBytes = new Uint8Array(cbor.encode(commit));
  const commitCid = await cidFor(commitBytes);
  const event = signEvent({ content: record.text, tags: [
    ['t', namespace], [`${namespace}:version`, '1'], [`${namespace}:commit`, b64(commitBytes)],
    [`${namespace}:base`, parent?.commitCid.toString() ?? ''], [`${namespace}:path`, path],
    [`${namespace}:created-at`, record.createdAt], [`${namespace}:delivery-expires-at`, record.deliveryExpiresAt],
  ] }, author);
  return { event, record, path, recordBytes, recordCid, commit, commitBytes, commitCid,
    entries: [...(parent?.entries ?? []), { key: path, value: recordCid }] };
}

async function assertRepositoryCar(bytes, source, { proof = false } = {}) {
  const car = await readCar(bytes);
  assert.deepEqual(car.roots.map(cid => cid.toString()), [source.commitCid.toString()]);
  for (const { cid, bytes } of car.blocks.entries()) assert.ok((await cidFor(bytes)).equals(cid));
  assert.deepEqual(Buffer.from(car.blocks.get(source.commitCid)), Buffer.from(source.commitBytes));
  const { sig, ...unsigned } = cbor.decode(car.blocks.get(source.commitCid));
  assert.equal(await verifySignature(repoKey.did(), cbor.encode(unsigned), sig), true);
  const storage = new MemoryBlockstore();
  for (const { cid, bytes } of car.blocks.entries()) await storage.putBlock(cid, bytes);
  const tree = MST.load(storage, unsigned.data);
  if (proof) {
    const claim = { collection, rkey: source.path.split('/')[1], cid: source.recordCid };
    assert.deepEqual(await verifyProofs(bytes, [claim], did, repoKey.did()), { verified: [claim], unverified: [] });
    // Covering proofs must also support removing the create to recover the previous root.
    const previous = await tree.delete(source.path);
    let expected = await MST.create(new MemoryBlockstore());
    for (const entry of source.entries.filter(entry => entry.key !== source.path)) expected = await expected.add(entry.key, entry.value);
    assert.ok((await previous.getPointer()).equals(await expected.getPointer()));
    assert.deepEqual(Buffer.from(car.blocks.get(source.recordCid)), Buffer.from(source.recordBytes));
    return;
  }
  const entries = await tree.list();
  assert.deepEqual(entries.map(({ key, value }) => [key, value.toString()]),
    source.entries.map(({ key, value }) => [key, value.toString()]).sort(([a], [b]) => a.localeCompare(b)));
  for (const { value } of entries) assert.equal(cbor.decode(car.blocks.get(value)).$type, collection);
  assert.deepEqual(Buffer.from(car.blocks.get(source.recordCid)), Buffer.from(source.recordBytes));
}

for (const dialect of ['postgres', 'sqlite']) describe(dialect, () => {
let db, directory, store, config, ingestion;
let identityEndpoint;
let resolutionError;
const resolveIdentity = async identity => {
  if (resolutionError) throw resolutionError;
  return { did: identity, signingKey: repoKey.did(), endpoint: identityEndpoint,
    didDoc: { id: identity }, handle: 'alice.example' };
};

async function openDatabase() {
  if (dialect === 'sqlite') {
    store = await openSqliteStore(join(directory, 'pds.sqlite'));
    db = { close: () => store.close() };
    return;
  }
  db = new PGlite(join(directory, 'postgres'));
  await db.waitReady;
  await db.exec(schema);
  let pending = Promise.resolve();
  store = {
    dialect,
    async query(sql, values = []) {
      // Tests have one serialized session; PGlite does not implement advisory locks.
      if (/^\s*SELECT pg_advisory_xact_lock\(/i.test(sql)) return { rows: [], rowCount: 1 };
      const result = await db.query(sql, values);
      return { rows: result.rows, rowCount: result.affectedRows || result.rows.length };
    },
    transaction(fn) {
      const run = pending.then(async () => {
        await store.query('BEGIN');
        try {
          const result = await fn(store);
          await store.query('COMMIT');
          return result;
        } catch (error) {
          await store.query('ROLLBACK');
          throw error;
        }
      });
      pending = run.catch(() => {});
      return run;
    },
  };
}
const rows = async (sql, values) => (await store.query(sql, values)).rows;
const job = async source => (await rows('SELECT * FROM jobs WHERE event_id=$1', [source.event.id]))[0];
const head = async (group = 'alpha', identity = did) =>
  (await rows('SELECT * FROM repos WHERE group_id=$1 AND did=$2', [group, identity]))[0];
async function process(source) {
  assert.equal((await ingestion.ingest(source.event)).accepted, true);
  assert.equal(await ingestion.processOne(), true);
  return job(source);
}

before(async () => {
  directory = await mkdtemp(join(tmpdir(), 'mosimo-ingest-test-'));
  await openDatabase();
});
after(async () => {
  await db?.close();
  if (directory) await rm(directory, { recursive: true, force: true });
});
beforeEach(async () => {
  if (dialect === 'sqlite') {
      await store.transaction(async tx => {
        for (const table of ['outbox', 'streams', 'group_sequences', 'repos', 'repo_blocks', 'blocks', 'commits', 'jobs', 'deletions', 'events', 'sqlite_sequence']) {
          await tx.query(`DELETE FROM ${table}`);
        }
      });
    } else await db.exec('TRUNCATE outbox, streams, group_sequences, repos, blocks, commits, jobs, deletions, events RESTART IDENTITY CASCADE');
  config = validateConfig({ groups: [{ id: 'alpha', endpoint: 'https://alpha.example' },
    { id: 'beta', endpoint: 'https://beta.example' }], peers: [{ url: 'https://peer.example' }] }, { PEER_TOKEN: 'test-only' });
  identityEndpoint = 'https://alpha.example';
  resolutionError = null;
  ingestion = createIngestion({ config, store, resolveIdentity });
});

test('durable dedup survives database reopen; arrival follows receipt, not created_at', async () => {
  const source = await fixture();
  const original = envelope(source.event);
  assert.equal((await ingestion.ingest({ ...source.event, unsignedMetadata: 'discard me' })).accepted, true);
  assert.deepEqual((await rows('SELECT event FROM events'))[0].event, original);
  await db.close();
  await openDatabase();
  ingestion = createIngestion({ config, store, resolveIdentity });
  assert.equal((await ingestion.ingest(source.event)).duplicate, true);
  const older = signEvent({ content: 'older timestamp, later arrival', created_at: 1 });
  assert.equal((await ingestion.ingest(older)).accepted, true);
  const saved = await rows('SELECT id, arrival FROM events ORDER BY arrival');
  assert.deepEqual(saved.map(row => row.id), [source.event.id, older.id]);
  assert.deepEqual(saved.map(row => Number(row.arrival)), [1, 2]);
  assert.equal((await rows('SELECT * FROM jobs')).length, 1);
  assert.equal((await rows('SELECT * FROM outbox')).length, 1);
  assert.deepEqual(envelope(source.event), original);
});

test('rejects forged signatures, IDs, cached verification, malformed envelopes and size before SQL writes', async () => {
  const { event } = await fixture();
  assert.equal(verifyEvent(event), true);
  for (const value of [null, {}, { ...event, content: 'tampered' }, { ...event, id: '0'.repeat(64) },
    { ...event, sig: '0'.repeat(128) }, { ...event, pubkey: event.pubkey.toUpperCase() },
    signEvent({ kind: 2 }), signEvent({ created_at: -1 }), { ...event, created_at: 1.5 }]) {
    const result = await ingestion.ingest(value);
    assert.equal(result.accepted, false);
    assert.match(result.message, /^invalid:/);
  }
  config.maxEventBytes = Buffer.byteLength(JSON.stringify(event)) - 1;
  assert.match((await ingestion.ingest(event)).message, /too large/i);
  assert.equal((await rows('SELECT * FROM events')).length, 0);
  assert.equal((await rows('SELECT * FROM jobs')).length, 0);
});

test('capacity preserves duplicate acknowledgements and forward=false suppresses only outbox', async () => {
  config.maxEvents = 1;
  const source = await fixture();
  assert.equal((await ingestion.ingest(source.event, { forward: false })).accepted, true);
  assert.equal((await ingestion.ingest(source.event)).duplicate, true);
  assert.match((await ingestion.ingest(signEvent({ content: 'full' }))).message, /capacity/);
  assert.match((await ingestion.ingest(signEvent({ kind: 5 }))).message, /capacity/);
  assert.equal((await rows('SELECT * FROM events')).length, 1);
  assert.equal((await rows('SELECT * FROM jobs')).length, 1);
  assert.equal((await rows('SELECT * FROM outbox')).length, 0);
});

test('NIP-09 tombstones are author-scoped, including deletion before original arrival', async () => {
  const target = await fixture();
  const deletion = key => signEvent({ kind: 5, tags: [['e', target.event.id], ['e', target.event.id],
    ['e', 'invalid-id']], content: 'delete' }, key);
  assert.equal((await ingestion.ingest(deletion(otherNostrKey))).accepted, true);
  assert.equal((await ingestion.ingest(target.event)).accepted, true);
  assert.equal((await ingestion.ingest(deletion(nostrKey))).accepted, true);
  assert.equal((await rows('SELECT * FROM deletions')).length, 2);
  assert.match((await ingestion.ingest(target.event)).message, /deleted by author/);
  const visible = await rows(`SELECT e.id FROM events e WHERE e.id=$1 AND NOT EXISTS
    (SELECT 1 FROM deletions d WHERE d.event_id=e.id AND d.pubkey=e.pubkey)`, [target.event.id]);
  assert.deepEqual(visible, []);
  const later = await fixture({ index: 1 });
  assert.equal((await ingestion.ingest(signEvent({ kind: 5, tags: [['e', later.event.id]] }))).accepted, true);
  assert.match((await ingestion.ingest(later.event)).message, /deleted by author/);
  assert.equal((await rows('SELECT * FROM events WHERE id=$1', [later.event.id])).length, 0);
});

test('initial and chained commits preserve original signatures and complete earlier record CAR blocks', async () => {
  let parent = null;
  for (let index = 0; index < 4; index++) {
    const source = await fixture({ index, parent });
    const original = envelope(source.event);
    assert.equal((await process(source)).status, 'validated');
    const saved = (await rows('SELECT * FROM commits WHERE cid=$1', [source.commitCid.toString()]))[0];
    assert.equal(saved.base, parent?.commitCid.toString() ?? null);
    assert.deepEqual(Buffer.from(saved.commit_bytes), Buffer.from(source.commitBytes));
    const car = await readCar(saved.car);
    assert.ok(car.roots[0].equals(source.commitCid));
    assert.deepEqual(Buffer.from(car.blocks.get(source.commitCid)), Buffer.from(source.commitBytes));
    for (const entry of source.entries) {
      assert.ok(car.blocks.get(entry.value), `complete CAR includes ${entry.key}`);
      assert.ok((await cidFor(car.blocks.get(entry.value))).equals(entry.value));
    }
    const loaded = new MemoryBlockstore();
    for (const entry of car.blocks.entries()) await loaded.putBlock(entry.cid, entry.bytes);
    const entries = await MST.load(loaded, source.commit.data).list();
    assert.deepEqual(entries.map(entry => entry.key), source.entries.map(entry => entry.key).sort());
    const { sig, ...unsigned } = cbor.decode(car.blocks.get(source.commitCid));
    assert.equal(await verifySignature(repoKey.did(), cbor.encode(unsigned), sig), true);
    assert.notEqual(source.event.pubkey, repoKey.did());
    assert.deepEqual((await rows('SELECT event FROM events WHERE id=$1', [source.event.id]))[0].event, original);
    assert.deepEqual(envelope(source.event), original);
    assert.equal((await head()).head, source.commitCid.toString());
    parent = source;
  }
  assert.equal(await ingestion.processOne(), false);
  assert.equal((await rows('SELECT * FROM streams')).length, 6);
  assert.equal((await rows('SELECT * FROM repos WHERE group_id=$1', ['beta'])).length, 0);
});

test('out-of-order child retries, then a verified base immediately unblocks it', async () => {
  const first = await fixture();
  const second = await fixture({ index: 1, parent: first });
  const third = await fixture({ index: 2, parent: second });
  assert.equal((await process(third)).status, 'retry');
  assert.equal((await job(third)).error, 'Missing verified base commit');
  assert.equal(Number((await job(third)).attempts), 1);
  assert.ok(new Date((await job(third)).next_attempt).getTime() > Date.now());
  assert.equal((await process(second)).status, 'retry');
  assert.equal((await process(first)).status, 'validated');
  for (let count = 0; count < 5 && await ingestion.processOne(); count++) {}
  assert.equal((await job(second)).status, 'validated');
  assert.equal((await job(third)).status, 'validated');
  assert.equal((await head()).head, third.commitCid.toString());
});

test('missing-base retries survive reopen and resume a gap-free byte-exact firehose', async t => {
  const first = await fixture();
  const second = await fixture({ index: 1, parent: first });
  const third = await fixture({ index: 2, parent: second });
  assert.equal((await process(third)).status, 'retry');
  assert.equal((await process(second)).status, 'retry');
  const pending = await rows('SELECT * FROM jobs ORDER BY event_id');
  await db.close();
  await openDatabase();
  ingestion = createIngestion({ config, store, resolveIdentity });
  assert.deepEqual(await rows('SELECT * FROM jobs ORDER BY event_id'), pending);
  assert.equal((await ingestion.ingest(third.event)).duplicate, true);
  assert.equal((await process(first)).status, 'validated');
  for (let count = 0; count < 5 && await ingestion.processOne(); count++) {}
  for (const source of [first, second, third]) assert.equal((await job(source)).status, 'validated');
  assert.equal((await head()).head, third.commitCid.toString());
  const saved = await rows('SELECT * FROM streams ORDER BY seq');
  assert.deepEqual(saved.map(row => Number(row.seq)), [1, 2, 3, 4, 5]);
  await db.close();
  await openDatabase();
  ingestion = createIngestion({ config, store, resolveIdentity });
  assert.deepEqual(await rows('SELECT * FROM streams ORDER BY seq'), saved);
  const app = createServer({ config: { ...config, pollIntervalMs: 10, maxResults: 2 }, store,
    ingest: ingestion.ingest, resolveIdentity });
  t.after(() => app.close());
  app.server.listen(0, '127.0.0.1');
  await once(app.server, 'listening');
  const client = await connect(t, app, '/xrpc/com.atproto.sync.subscribeRepos?cursor=2');
  for (const [index, source] of [first, second, third].entries()) {
    const frame = await client.next();
    assert.deepEqual(frame, Buffer.from(saved[index + 2].frame));
    const [header, body] = decodeFirst(frame, cbor.decodeOptions);
    assert.deepEqual(header, { op: 1, t: '#commit' });
    assert.equal(cbor.decode(body).seq, index + 3);
    await assertRepositoryCar(cbor.decode(body).blocks, source, { proof: true });
  }
  const fourth = await fixture({ index: 3, parent: third });
  assert.equal((await process(fourth)).status, 'validated');
  const [header, body] = decodeFirst(await client.next(), cbor.decodeOptions);
  assert.deepEqual(header, { op: 1, t: '#commit' });
  assert.equal(cbor.decode(body).seq, 6);
  await assertRepositoryCar(cbor.decode(body).blocks, fourth, { proof: true });
  assert.deepEqual((await rows('SELECT seq FROM streams ORDER BY seq')).map(row => Number(row.seq)), [1, 2, 3, 4, 5, 6]);
});

test('re-signed changed content cannot bypass the signed MST and changed commit bytes cannot bypass its signature', async () => {
  const source = await fixture();
  const content = { ...source, event: signEvent({ ...source.event, content: 'changed but validly Nostr-signed' }) };
  assert.equal((await process(content)).status, 'invalid');
  assert.match((await job(content)).error, /MST root/);
  const commitBytes = cbor.encode({ ...source.commit, rev: '3jzfcijpj2z23' });
  const commit = { ...source, event: signEvent({ ...source.event,
    tags: source.event.tags.map(tag => tag[0] === `${namespace}:commit` ? [tag[0], b64(commitBytes)] : tag) }) };
  assert.equal((await process(commit)).status, 'retry');
  assert.match((await job(commit)).error, /Signature\/key state/);
  for (const table of ['commits', 'blocks', 'repos', 'streams']) assert.deepEqual(await rows(`SELECT * FROM ${table}`), []);
});

test('identity failures and bad repository signatures retry without canonical state or blocks', async () => {
  const first = await fixture();
  resolutionError = new Error('offline');
  assert.equal((await process(first)).status, 'retry');
  assert.match((await job(first)).error, /PLC resolution: offline/);
  // A DID registered later (e.g. by proxy) is retried within a minute, however many attempts failed before.
  await store.query(`UPDATE jobs SET attempts=20,next_attempt=${dialect === 'sqlite' ? sqliteNow : 'now()'} WHERE event_id=$1`, [first.event.id]);
  assert.equal(await ingestion.processOne(), true);
  assert.ok(new Date((await job(first)).next_attempt).getTime() <= Date.now() + 61_000);
  resolutionError = null;
  for (const source of [await fixture({ index: 1, badSignature: true }),
    await fixture({ index: 2, key: await Secp256k1Keypair.create() })]) {
    assert.equal((await process(source)).status, 'retry');
    assert.match((await job(source)).error, /Signature\/key state/);
  }
  assert.deepEqual(await rows('SELECT * FROM repos'), []);
  assert.deepEqual(await rows('SELECT * FROM blocks'), []);
  assert.deepEqual(await rows('SELECT * FROM streams'), []);
  await store.query(`UPDATE jobs SET next_attempt=${dialect === 'sqlite' ? sqliteNow : 'now()'} WHERE event_id=$1`, [first.event.id]);
  assert.equal(await ingestion.processOne(), true);
  assert.equal((await job(first)).status, 'validated');
});

test('malformed repository tags and validly signed invalid MST roots become invalid', async () => {
  const badTags = await fixture();
  badTags.event = signEvent({ ...badTags.event,
    tags: badTags.event.tags.filter(tag => tag[0] !== `${namespace}:path`) });
  assert.equal((await process(badTags)).status, 'invalid');
  assert.match((await job(badTags)).error, /path/);
  const badTree = await fixture({ index: 1, badRoot: true });
  assert.equal((await process(badTree)).status, 'invalid');
  assert.match((await job(badTree)).error, /MST root/);
  assert.deepEqual(await rows('SELECT * FROM commits'), []);
  assert.deepEqual(await rows('SELECT * FROM streams'), []);
});

test('forks and late initial commits never roll the canonical head or firehose back', async () => {
  const first = await fixture();
  const second = await fixture({ index: 1, parent: first });
  assert.equal((await process(first)).status, 'validated');
  assert.equal((await process(second)).status, 'validated');
  const frames = await rows('SELECT * FROM streams ORDER BY seq');
  for (const source of [await fixture({ index: 2, parent: first }), await fixture({ index: 3 })]) {
    assert.equal((await process(source)).status, 'conflict');
    assert.equal((await head()).head, second.commitCid.toString());
    assert.equal((await rows('SELECT * FROM commits WHERE cid=$1', [source.commitCid.toString()])).length, 1);
  }
  assert.deepEqual(await rows('SELECT * FROM streams ORDER BY seq'), frames);
  const earlierRev = await fixture({ index: 4, parent: second, rev: first.commit.rev });
  assert.equal((await process(earlierRev)).status, 'invalid');
  assert.match((await job(earlierRev)).error, /Non-increasing revision/);
});

test('canonical endpoint selects exactly one group; shared commits accept independently signed envelopes', async () => {
  identityEndpoint = 'https://elsewhere.example';
  const first = await fixture();
  assert.equal((await process(first)).status, 'validated');
  assert.deepEqual(await rows('SELECT * FROM repos'), []);
  identityEndpoint = 'https://beta.example';
  const shared = { ...first, event: signEvent(first.event, otherNostrKey) };
  assert.notEqual(shared.event.id, first.event.id);
  assert.notEqual(shared.event.pubkey, first.event.pubkey);
  assert.equal((await process(shared)).status, 'validated');
  assert.equal((await head('beta')).head, first.commitCid.toString());
  assert.equal(await head('alpha'), undefined);
  assert.equal((await rows('SELECT * FROM commits')).length, 1);
  const replay = { ...first, event: signEvent({ ...first.event, tags: [...first.event.tags, ['client', 'another']] }) };
  assert.equal((await process(replay)).status, 'validated');
  assert.equal((await rows('SELECT * FROM streams')).length, 3);
  assert.ok((await rows('SELECT group_id FROM streams')).every(row => row.group_id === 'beta'));
});

test('persisted firehose has separate CBOR header/body, original commit CAR, and continuous group sequence', async () => {
  const first = await fixture();
  const second = await fixture({ index: 1, parent: first });
  await process(first);
  await process(second);
  const saved = await rows('SELECT * FROM streams ORDER BY seq');
  await db.close();
  await openDatabase();
  assert.deepEqual(await rows('SELECT * FROM streams ORDER BY seq'), saved);
  const frames = saved.map(row => {
    const [header, remaining] = decodeFirst(row.frame, cbor.decodeOptions);
    return { header, body: cbor.decode(remaining) };
  });
  assert.deepEqual(frames.map(value => value.header), ['#identity', '#account', '#commit', '#commit'].map(t => ({ op: 1, t })));
  assert.deepEqual(frames.map(value => value.body.seq), [1, 2, 3, 4]);
  assert.equal(frames[0].body.did, did);
  assert.equal(frames[0].body.handle, 'alice.example');
  assert.equal(frames[1].body.active, true);
  for (const [index, source] of [first, second].entries()) {
    const body = frames[index + 2].body;
    assert.equal(body.repo, did);
    assert.ok(body.commit.equals(source.commitCid));
    assert.equal(body.rev, source.commit.rev);
    assert.equal(body.since, index ? first.commit.rev : null);
    assert.equal(body.rebase, false);
    assert.equal(body.tooBig, false);
    assert.deepEqual(body.blobs, []);
    assert.equal(body.ops[0].action, 'create');
    assert.equal(body.ops[0].path, source.path);
    assert.ok(body.ops[0].cid.equals(source.recordCid));
    assert.ok(index ? body.prevData.equals(first.commit.data) : !Object.hasOwn(body, 'prevData'));
    assert.deepEqual((await readCar(body.blocks)).blocks.get(source.commitCid), source.commitBytes);
    assert.ok(Number.isFinite(Date.parse(body.time)));
  }
});

test('real SQL-backed server routes serve complete repository data and isolate groups', async t => {
  const first = await fixture();
  const second = await fixture({ index: 1, parent: first });
  await process(first);
  await process(second);
  const app = createServer({ config, store, ingest: ingestion.ingest, resolveIdentity });
  t.after(() => app.close());
  app.server.listen(0, '127.0.0.1');
  await once(app.server, 'listening');
  const request = (method, params = {}, host = 'alpha.example') => new Promise((resolve, reject) => {
    const req = http.get({ hostname: '127.0.0.1', port: app.server.address().port,
      path: `/xrpc/${method}?${new URLSearchParams(params)}`, headers: { host } }, res => {
      const chunks = [];
      res.on('data', chunk => chunks.push(chunk));
      res.on('end', () => resolve({ status: res.statusCode, bytes: Buffer.concat(chunks) }));
      res.on('error', reject);
    });
    req.on('error', reject);
  });
  const repo = await request('com.atproto.sync.getRepo', { did });
  assert.equal(repo.status, 200);
  await assertRepositoryCar(repo.bytes, second);
  assert.deepEqual(Buffer.from((await readCar(repo.bytes)).blocks.get(first.recordCid)), Buffer.from(first.recordBytes));
  const record = await request('com.atproto.repo.getRecord', { repo: did, collection, rkey: first.path.split('/')[1] });
  assert.equal(record.status, 200);
  assert.deepEqual(JSON.parse(record.bytes).value, first.record);
  const listed = await request('com.atproto.repo.listRecords', { repo: did, collection });
  assert.equal(listed.status, 200);
  assert.equal(JSON.parse(listed.bytes).records.length, 2);
  const latest = await request('com.atproto.sync.getLatestCommit', { did });
  assert.deepEqual(JSON.parse(latest.bytes), { cid: second.commitCid.toString(), rev: second.commit.rev });
  const description = await request('com.atproto.repo.describeRepo', { repo: did });
  assert.equal(description.status, 200);
  assert.deepEqual(JSON.parse(description.bytes).didDoc, { id: did });
  const blocks = await request('com.atproto.sync.getBlocks', { did, cids: first.recordCid.toString() });
  assert.equal(blocks.status, 200);
  assert.deepEqual(Buffer.from((await readCar(blocks.bytes)).blocks.get(first.recordCid)), Buffer.from(first.recordBytes));
  const historical = await request('com.atproto.sync.getBlocks', { did, cids: first.commit.data.toString() });
  assert.equal(historical.status, 200);
  assert.ok((await readCar(historical.bytes)).blocks.get(first.commit.data));
  assert.equal(JSON.parse(description.bytes).handleIsCorrect, false);
  assert.equal((await request('com.atproto.sync.getRepo', { did }, 'beta.example')).status, 404);
  const other = await request('com.atproto.sync.listRepos', {}, 'beta.example');
  assert.deepEqual(JSON.parse(other.bytes), { repos: [] });
});

async function connect(t, app, path = '/', host = 'alpha.example') {
  const ws = new WebSocket(`ws://127.0.0.1:${app.server.address().port}${path}`, { headers: { host } });
  t.after(() => ws.terminate());
  const queue = [];
  const waiters = [];
  ws.on('message', (bytes, binary) => {
    const value = binary ? Buffer.from(bytes) : JSON.parse(bytes.toString());
    if (waiters.length) waiters.shift()(value);
    else queue.push(value);
  });
  await once(ws, 'open', { signal: AbortSignal.timeout(5000) });
  return {
    send: value => ws.send(JSON.stringify(value)),
    next: () => queue.length ? Promise.resolve(queue.shift()) : new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('WebSocket message timeout')), 5000);
      waiters.push(value => { clearTimeout(timer); resolve(value); });
    }),
  };
}

test('real relay EVENT acknowledgement is separate from repository validation and rejects arbitrary kinds', async t => {
  const app = createServer({ config, store, ingest: ingestion.ingest, resolveIdentity });
  t.after(() => app.close());
  app.server.listen(0, '127.0.0.1');
  await once(app.server, 'listening');
  const client = await connect(t, app);
  for (const kind of [0, 2, 7, 30023]) {
    const event = signEvent({ kind, content: `kind ${kind}` });
    client.send(['EVENT', event]);
    const ack = await client.next();
    assert.deepEqual(ack.slice(0, 3), ['OK', event.id, false]);
    assert.match(ack[3], /kind 1 or 5/);
  }
  assert.deepEqual(await rows('SELECT * FROM events'), []);
  const source = await fixture({ badRoot: true });
  client.send(['EVENT', source.event]);
  const ack = await client.next();
  assert.deepEqual(ack.slice(0, 3), ['OK', source.event.id, true]);
  assert.match(ack[3], /asynchronous/);
  assert.equal((await job(source)).status, 'pending');
  assert.equal(await head(), undefined);
  assert.equal(await ingestion.processOne(), true);
  assert.equal((await job(source)).status, 'invalid');
  assert.equal(await head(), undefined);
  assert.deepEqual(await rows('SELECT * FROM streams'), []);
});

test('configured upstream WebSocket feeds signed events into real SQL ingestion and repository reconstruction', { timeout: 10000 }, async t => {
  const upstream = new WebSocketServer({ host: '127.0.0.1', port: 0 });
  await once(upstream, 'listening');
  t.after(async () => {
    for (const socket of upstream.clients) socket.terminate();
    await new Promise(resolve => upstream.close(resolve));
  });
  const connected = once(upstream, 'connection');
  const delivered = Promise.withResolvers();
  const network = startNetwork({ config: { ...config, peers: [],
    relays: [`ws://127.0.0.1:${upstream.address().port}`] }, store,
    ingest: async event => {
      try { delivered.resolve(await ingestion.ingest(event)); }
      catch (error) { delivered.reject(error); throw error; }
    } });
  t.after(() => network.close());
  const [socket] = await connected;
  const [request] = await once(socket, 'message', { signal: AbortSignal.timeout(5000) });
  assert.deepEqual(JSON.parse(request.toString()), ['REQ', 'mosimo',
    { kinds: [1], '#t': [namespace] }, { kinds: [5] }]);
  const source = await fixture();
  socket.send(JSON.stringify(['EVENT', 'mosimo', source.event]));
  assert.equal((await delivered.promise).accepted, true);
  assert.deepEqual((await rows('SELECT event FROM events'))[0].event, envelope(source.event));
  assert.equal((await job(source)).status, 'pending');
  assert.equal(await ingestion.processOne(), true);
  assert.equal((await job(source)).status, 'validated');
  assert.equal((await head()).head, source.commitCid.toString());
  await assertRepositoryCar((await rows('SELECT car FROM commits'))[0].car, source);
  await network.close();
});

test('real Nostr history SQL applies tag filters and only original-author deletions hide posts', async t => {
  const source = await fixture();
  await ingestion.ingest(source.event);
  const app = createServer({ config: { ...config, pollIntervalMs: 10 }, store,
    ingest: ingestion.ingest, resolveIdentity });
  t.after(() => app.close());
  app.server.listen(0, '127.0.0.1');
  await once(app.server, 'listening');
  const client = await connect(t, app);
  const filter = { ids: [source.event.id], authors: [source.event.pubkey], kinds: [1],
    '#t': [namespace], since: source.event.created_at, until: source.event.created_at, limit: 1 };
  client.send(['REQ', 'initial', filter]);
  assert.deepEqual(await client.next(), ['EVENT', 'initial', envelope(source.event)]);
  assert.deepEqual(await client.next(), ['EOSE', 'initial']);
  for (const empty of [{ ids: [] }, { authors: [] }, { kinds: [] }, { '#t': [] }, { '#t': [namespace], '#e': ['missing'] }]) {
    client.send(['REQ', 'no-match', empty]);
    assert.deepEqual(await client.next(), ['EOSE', 'no-match']);
  }
  client.send(['CLOSE', 'no-match']);
  await ingestion.ingest(signEvent({ kind: 5, tags: [['e', source.event.id]] }, otherNostrKey));
  client.send(['REQ', 'foreign-deletion', filter]);
  assert.deepEqual(await client.next(), ['EVENT', 'foreign-deletion', envelope(source.event)]);
  assert.deepEqual(await client.next(), ['EOSE', 'foreign-deletion']);
  const deletion = signEvent({ kind: 5, tags: [['e', source.event.id]] });
  await ingestion.ingest(deletion);
  client.send(['REQ', 'author-deletion', filter]);
  assert.deepEqual(await client.next(), ['EOSE', 'author-deletion']);
  await ingestion.ingest(signEvent({ kind: 5, tags: [['e', deletion.id]], content: 'cannot delete deletion' }));
  client.send(['REQ', 'deletion-retained', { ids: [deletion.id] }]);
  assert.deepEqual(await client.next(), ['EVENT', 'deletion-retained', envelope(deletion)]);
  assert.deepEqual(await client.next(), ['EOSE', 'deletion-retained']);
  const live = signEvent({ content: 'later receipt', created_at: 1, tags: [['t', namespace]] });
  client.send(['REQ', 'live', { kinds: [1], '#t': [namespace], limit: 0 }]);
  assert.deepEqual(await client.next(), ['EOSE', 'live']);
  assert.equal((await ingestion.ingest(live)).accepted, true);
  assert.deepEqual(await client.next(), ['EVENT', 'live', envelope(live)]);
});

test('SQL-backed subscribeRepos replays persisted bytes and maintains independent group sequences', async t => {
  let first = await fixture();
  await process(first);
  // Cross the decimal boundary to catch accidental text sorting of sequence cursors.
  for (let index = 1; index <= 9; index++) {
    first = await fixture({ index, parent: first });
    await process(first);
  }
  identityEndpoint = 'https://beta.example';
  const beta = await fixture({ identity: 'did:plc:aaaaaaaaaaaaaaaaaaaaaaaa', index: 1 });
  await process(beta);
  const saved = await rows('SELECT * FROM streams WHERE group_id=$1 ORDER BY seq', ['alpha']);
  assert.deepEqual((await rows('SELECT seq FROM streams WHERE group_id=$1 ORDER BY seq', ['beta']))
    .map(row => Number(row.seq)), [1, 2, 3]);
  const app = createServer({ config: { ...config, pollIntervalMs: 10 }, store,
    ingest: ingestion.ingest, resolveIdentity });
  t.after(() => app.close());
  app.server.listen(0, '127.0.0.1');
  await once(app.server, 'listening');
  // No cursor and explicit zero both replay the persisted stream from sequence zero.
  const client = await connect(t, app, '/xrpc/com.atproto.sync.subscribeRepos');
  const explicitZero = await connect(t, app, '/xrpc/com.atproto.sync.subscribeRepos?cursor=0');
  for (const row of saved) {
    assert.deepEqual(await client.next(), Buffer.from(row.frame));
    assert.deepEqual(await explicitZero.next(), Buffer.from(row.frame));
  }
  identityEndpoint = 'https://alpha.example';
  const second = await fixture({ index: 12, parent: first });
  await process(second);
  const frame = await client.next();
  assert.deepEqual(frame, Buffer.from((await rows('SELECT frame FROM streams WHERE group_id=$1 AND seq=$2', ['alpha', saved.length + 1]))[0].frame));
  const [header, bodyBytes] = decodeFirst(frame, cbor.decodeOptions);
  assert.deepEqual(header, { op: 1, t: '#commit' });
  assert.ok(cbor.decode(bodyBytes).commit.equals(second.commitCid));
});

test('full snapshots above 900KB retain all records while publishing compact nonempty commit proofs', async () => {
  let source;
  for (let index = 0; index < 14; index++) {
    source = await fixture({ index, parent: source, text: `${index}:` + 'x'.repeat(72_000) });
    assert.ok(Buffer.byteLength(JSON.stringify(source.event)) < config.maxEventBytes);
    assert.equal((await process(source)).status, 'validated');
  }
  const saved = (await rows('SELECT * FROM commits WHERE cid=$1', [source.commitCid.toString()]))[0];
  assert.ok(saved.car.length > 900_000);
  await assertRepositoryCar(saved.car, source);
  const stream = (await rows('SELECT frame FROM streams ORDER BY seq DESC LIMIT 1'))[0].frame;
  assert.ok(stream.length <= config.maxBufferedBytes);
  const [header, bodyBytes] = decodeFirst(stream, cbor.decodeOptions);
  const body = cbor.decode(bodyBytes);
  assert.equal(header.t, '#commit');
  assert.equal(body.tooBig, false);
  assert.ok(body.blocks.length > 0 && body.blocks.length < 100_000);
  assert.deepEqual(body.ops, [{ action: 'create', path: source.path, cid: source.recordCid }]);
  await assertRepositoryCar(body.blocks, source, { proof: true });
  const full = await readCar(saved.car);
  const storage = new MemoryBlockstore(full.blocks);
  const previousRoot = await MST.load(storage, source.commit.data).delete(source.path);
  assert.ok(body.prevData.equals(await previousRoot.getPointer()));
});

for (const offset of [-365 * 24 * 3600_000, 0, 300_000]) {
  test(`revision time offset ${offset}ms is tolerated without rewriting signed bytes`, async () => {
    const now = Date.UTC(2026, 9, 18);
    ingestion = createIngestion({ config, store, resolveIdentity, clock: () => now });
    const source = await fixture({ rev: TID.fromTime((now + offset) * 1000, 0).toString() });
    assert.equal((await process(source)).status, 'validated');
    assert.equal((await head()).head, source.commitCid.toString());
    const saved = (await rows('SELECT commit_bytes FROM commits'))[0];
    assert.deepEqual(Buffer.from(saved.commit_bytes), Buffer.from(source.commitBytes));
  });
}

test('future revision retries without repository writes and becomes valid at the five-minute boundary', async () => {
  let now = Date.UTC(2026, 9, 18);
  const rev = TID.fromTime((now + 300_000) * 1000 + 1, 0).toString();
  ingestion = createIngestion({ config, store, resolveIdentity, clock: () => now });
  const source = await fixture({ rev });
  const before = Date.now();
  const pending = await process(source);
  assert.equal(pending.status, 'retry');
  assert.match(pending.error, /Future revision/);
  assert.equal(pending.attempts, 1);
  assert.ok(new Date(pending.next_attempt).getTime() >= before);
  assert.ok(new Date(pending.next_attempt).getTime() <= Date.now() + 3600_000);
  for (const table of ['commits', 'blocks', 'repo_blocks', 'repos', 'streams', 'group_sequences']) {
    assert.deepEqual(await rows(`SELECT * FROM ${table}`), []);
  }
  assert.deepEqual((await rows('SELECT event FROM events'))[0].event, envelope(source.event));
  now += 1;
  await store.query(`UPDATE jobs SET next_attempt=${dialect === 'sqlite' ? sqliteNow : 'now()'} WHERE event_id=$1`, [source.event.id]);
  assert.equal(await ingestion.processOne(), true);
  assert.equal((await job(source)).status, 'validated');
  assert.equal((await head()).head, source.commitCid.toString());
  const saved = (await rows('SELECT commit_bytes FROM commits'))[0];
  assert.deepEqual(Buffer.from(saved.commit_bytes), Buffer.from(source.commitBytes));
});

test('configured frame budget triggers sync rather than publishing an undeliverable commit', async () => {
  config.maxBufferedBytes = 1500;
  const source = await fixture({ text: 'x'.repeat(5000) });
  assert.equal((await process(source)).status, 'validated');
  const saved = await rows('SELECT frame FROM streams ORDER BY seq');
  assert.ok(saved.every(row => row.frame.length <= config.maxBufferedBytes));
  const [header, bytes] = decodeFirst(saved[2].frame, cbor.decodeOptions);
  assert.equal(header.t, '#sync');
  const body = cbor.decode(bytes);
  assert.deepEqual(Object.keys(body).sort(), ['blocks', 'did', 'rev', 'seq', 'time']);
  const car = await readCar(body.blocks);
  assert.equal(car.blocks.size, 1);
  assert.deepEqual(Buffer.from(car.blocks.get(source.commitCid)), Buffer.from(source.commitBytes));
  await assertRepositoryCar((await rows('SELECT car FROM commits'))[0].car, source);
});

test('processing errors roll back commit, blocks, streams, head and job status atomically', async () => {
  const source = await fixture();
  await ingestion.ingest(source.event);
  await store.query('INSERT INTO group_sequences(group_id,seq) VALUES($1,$2)',
    ['alpha', Number.MAX_SAFE_INTEGER.toString()]);
  await assert.rejects(ingestion.processOne(), /Firehose sequence exhausted/);
  assert.equal((await job(source)).status, 'pending');
  assert.deepEqual(await rows('SELECT * FROM commits'), []);
  assert.deepEqual(await rows('SELECT * FROM blocks'), []);
  assert.deepEqual(await rows('SELECT * FROM repos'), []);
  assert.deepEqual(await rows('SELECT * FROM streams'), []);
  await store.query('UPDATE group_sequences SET seq=0 WHERE group_id=$1', ['alpha']);
  assert.equal(await ingestion.processOne(), true);
  assert.equal((await job(source)).status, 'validated');
});
});
