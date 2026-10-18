import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';
import http from 'node:http';
import { WebSocket } from 'ws';
import { MST, MemoryBlockstore, readCar } from '@atproto/repo';
import { Secp256k1Keypair } from '@atproto/crypto';
import * as cbor from '@ipld/dag-cbor';
import { CID } from 'multiformats/cid';
import { sha256 } from 'multiformats/hashes/sha2';
import { validateConfig } from './config.mjs';
import { createIngestion } from './ingest.mjs';
import { decodePost, verifyCommit, reconstruct } from './repository.mjs';
import { openSqliteStore } from './store.mjs';
import { createServer } from './server.mjs';

// Run via apps/esp32/test_bundle/test_repository_integration.py. No generated
// fixtures are committed; the runner supplies real Kotlin + ESP32 output.
const fixturePath = process.env.MESH_REPOSITORY_FIXTURE;
test('real Android Author -> ESP32 -> Android -> WebSocket PDS -> HTTP preserves signatures, CIDs and chained MST', {
  timeout: 60000,
  skip: fixturePath ? false : 'Run test_repository_integration.py to generate Kotlin/ESP32 fixtures',
}, async () => {
  const fixture = JSON.parse(await readFile(fixturePath, 'utf8'));
  assert.equal(fixture.posts.length, 2);
  assert.equal(fixture.pds_endpoint, 'https://integration.invalid');
  const directory = await mkdtemp(join(tmpdir(), 'mosimo-android-esp32-'));
  let store, app, ws;
  try {
    store = await openSqliteStore(join(directory, 'pds.sqlite'));
    const config = validateConfig({ groups: [{ id: 'integration', endpoint: fixture.pds_endpoint }], peers: [], relays: [] });
    const resolved = [];
    // Same injection seam as ingest.test.mjs: the public test key replaces PLC I/O,
    // not repository/commit/MST verification. No production directory is contacted.
    const resolveIdentity = async did => {
      assert.equal(did, fixture.did);
      resolved.push(did);
      return { did, signingKey: fixture.signing_key_did, endpoint: fixture.pds_endpoint, didDoc: { id: did } };
    };
    const ingestion = createIngestion({ config, store, clock: () => fixture.now_millis + 2000, resolveIdentity });
    app = createServer({ config, store, ingest: ingestion.ingest, resolveIdentity });
    app.server.listen(0, '127.0.0.1');
    await once(app.server, 'listening', { signal: AbortSignal.timeout(5000) });
    const base = `http://127.0.0.1:${app.server.address().port}`;
    ws = new WebSocket(base.replace('http:', 'ws:'), {
      headers: { host: 'integration.invalid' }, handshakeTimeout: 5000,
    });
    // Active once() waiters reject on errors; keep gaps between exchanges safe too.
    ws.on('error', () => {});
    await once(ws, 'open', { signal: AbortSignal.timeout(5000) });
    const publish = async event => {
      const reply = once(ws, 'message', { signal: AbortSignal.timeout(5000) });
      ws.send(JSON.stringify(['EVENT', event]));
      const [data, binary] = await reply;
      assert.equal(binary, false);
      const ack = JSON.parse(data.toString());
      assert.equal(ack.length, 4);
      assert.deepEqual(ack.slice(0, 2), ['OK', event.id]);
      assert.equal(typeof ack[3], 'string');
      return ack;
    };
    // Like server.test.mjs, use http rather than fetch so Host is not overridden.
    const xrpc = async (method, params) => {
      const [status, body] = await new Promise((resolve, reject) => {
        const req = http.get(`${base}/xrpc/${method}?${new URLSearchParams(params)}`, {
          headers: { host: 'integration.invalid' }, agent: false, signal: AbortSignal.timeout(5000),
        }, res => {
          const chunks = [];
          res.on('data', chunk => chunks.push(chunk));
          res.on('end', () => resolve([res.statusCode, Buffer.concat(chunks)]));
          res.on('error', reject);
        });
        req.on('error', reject);
      });
      assert.equal(status, 200, body.toString());
      return JSON.parse(body.toString());
    };
    const lexJson = value => {
      if (CID.asCID(value)) return { $link: value.toString() };
      if (value instanceof Uint8Array) return { $bytes: Buffer.from(value).toString('base64').replace(/=+$/, '') };
      if (Array.isArray(value)) return value.map(lexJson);
      if (value !== null && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, lexJson(v)]));
      return value;
    };
    const rows = async (sql, values = []) => (await store.query(sql, values)).rows;
    const bytes = hex => Buffer.from(hex, 'hex');
    const wrongKey = await Secp256k1Keypair.create();
    let baseEntries = [];
    for (const [index, post] of fixture.posts.entries()) {
      const event = JSON.parse(post.received_event_json);
      assert.deepEqual(event, JSON.parse(post.event_json));
      const decoded = await decodePost(event); // Real nostr-tools signature + exact record reconstruction.
      assert.equal(decoded.commit.did, fixture.did);
      assert.equal(decoded.path, post.path);
      assert.equal(decoded.base?.toString() ?? null, post.base);
      assert.equal(post.base, index === 0 ? null : fixture.posts[0].commit_cid);
      assert.deepEqual(Buffer.from(decoded.recordBytes), bytes(post.record_hex));
      assert.deepEqual(Buffer.from(decoded.commitBytes), bytes(post.commit_hex));
      assert.equal(decoded.recordCid.toString(), post.record_cid);
      assert.equal(decoded.commitCid.toString(), post.commit_cid);
      assert.equal(decoded.commit.data.toString(), post.mst_root);
      assert.equal(await verifyCommit(decoded, fixture.signing_key_did), true);
      await assert.rejects(verifyCommit(decoded, wrongKey.did()), /Invalid commit signature/);
      if (index === 0) assert.deepEqual(decoded.record.extensions, []);
      else {
        const extensions = decoded.record.extensions;
        assert.deepEqual(extensions.map(e => e.$type), [
          'org.example.integration.defs#opaque', 'org.example.integration.defs#other', 'org.example.integration.defs#opaque',
        ]);
        assert.deepEqual(extensions[0].bytes, Uint8Array.of(251, 255));
        assert.equal(extensions[0].link.toString(), fixture.posts[0].record_cid);
        assert.equal(extensions[0].nested.nullValue, null);
        assert.equal(Object.hasOwn(extensions[2], 'nested'), false);
        await assert.rejects(reconstruct(decoded, []), /MST root/);
      }
      const rebuilt = await reconstruct(decoded, baseEntries);
      assert.deepEqual(rebuilt.entries.map(({ key, value }) => ({ key, value: value.toString() })), post.entries);
      baseEntries = rebuilt.entries;
      const rejected = await publish({ ...event, content: event.content + 'tampered' });
      assert.equal(rejected[2], false);
      assert.match(rejected[3], /^invalid:/);
      const accepted = await publish(event);
      assert.equal(accepted[2], true, accepted[3]);
      assert.equal((await rows('SELECT status FROM jobs WHERE event_id=$1', [event.id]))[0].status, 'pending');
      assert.equal(await ingestion.processOne(), true);
      const [job] = await rows('SELECT status,error FROM jobs WHERE event_id=$1', [event.id]);
      assert.equal(job.status, 'validated', job.error);
      assert.equal(job.error, null);
      assert.deepEqual((await rows('SELECT event FROM events WHERE id=$1', [event.id]))[0].event, event);
      const [saved] = await rows('SELECT * FROM commits WHERE cid=$1', [post.commit_cid]);
      assert.equal(saved.base, post.base);
      assert.deepEqual(saved.entries, post.entries);
      assert.deepEqual(saved.commit_bytes, bytes(post.commit_hex));
      const car = await readCar(saved.car);
      assert.deepEqual(car.roots.map(cid => cid.toString()), [post.commit_cid]);
      const storage = new MemoryBlockstore();
      for (const { cid, bytes: block } of car.blocks.entries()) {
        assert.ok(cid.equals(CID.createV1(cbor.code, await sha256.digest(block))));
        await storage.putBlock(cid, block);
      }
      assert.deepEqual(Buffer.from(car.blocks.get(CID.parse(post.commit_cid))), bytes(post.commit_hex));
      for (const prior of fixture.posts.slice(0, index + 1)) {
        assert.deepEqual(Buffer.from(car.blocks.get(CID.parse(prior.record_cid))), bytes(prior.record_hex));
      }
      const tree = MST.load(storage, decoded.commit.data);
      assert.deepEqual((await tree.list()).map(({ key, value }) => ({ key, value: value.toString() })), post.entries);
      const [head] = await rows('SELECT head,rev FROM repos WHERE group_id=$1 AND did=$2', ['integration', fixture.did]);
      assert.equal(head.head, post.commit_cid);
      assert.equal(head.rev, decoded.commit.rev);
      for (const prior of fixture.posts.slice(0, index + 1)) {
        const [collection, rkey] = prior.path.split('/');
        const record = await xrpc('com.atproto.repo.getRecord', { repo: fixture.did, collection, rkey });
        assert.deepEqual(record, { uri: `at://${fixture.did}/${prior.path}`, cid: prior.record_cid,
          value: lexJson(cbor.decode(bytes(prior.record_hex))) });
      }
      assert.deepEqual(await xrpc('com.atproto.sync.getLatestCommit', { did: fixture.did }), {
        cid: post.commit_cid, rev: decoded.commit.rev,
      });
      const duplicate = await publish(event);
      assert.equal(duplicate[2], true, duplicate[3]);
      assert.match(duplicate[3], /^duplicate:/);
      console.log(`PASS PDS post ${index + 1}: WebSocket EVENT/OK, validated, ${post.entries.length} MST records, exact CAR/CIDs, HTTP getRecord/getLatestCommit`);
    }
    assert.equal(resolved.length, 2);
    assert.equal((await rows('SELECT * FROM events')).length, 2);
    assert.equal((await rows('SELECT * FROM commits')).length, 2);
    assert.equal((await rows('SELECT * FROM outbox')).length, 0);
    assert.equal(await ingestion.processOne(), false);
  } finally {
    ws?.terminate();
    try {
      await app?.close();
    } finally {
      try { await store?.close(); }
      finally { await rm(directory, { recursive: true, force: true }); }
    }
  }
});
