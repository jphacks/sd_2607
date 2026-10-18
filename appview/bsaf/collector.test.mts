import assert from 'node:assert/strict';
import { once } from 'node:events';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { test } from 'node:test';
import { setTimeout as delay } from 'node:timers/promises';
import { cidForLex } from '@atproto/lex-cbor';
import { parseCid } from '@atproto/lex-data';
import WebSocket, { createWebSocketStream, WebSocketServer } from 'ws';
import { collect, subscriptionUrl } from './collector.mts';
import { COLLECTION, openStore, type Store } from './store.mts';

const author = 'did:plc:approved';
const other = 'did:plc:other';
const upstream = 'wss://jetstream.example/subscribe';
const post = { $type: COLLECTION, text: 'raw information', tags: ['bsaf:v1'], createdAt: '2026-10-18T00:00:00Z' };
const uri = `at://${author}/${COLLECTION}/one`;
async function event(time_us = 1, record: Record<string, unknown> = post, operation = 'create', did = author) {
  return { did, time_us, kind: 'commit', commit: {
    operation, collection: COLLECTION, rkey: 'one', record,
    cid: (await cidForLex(record as Parameters<typeof cidForLex>[0])).toString(),
  } };
}
function fixture(t: { after: (fn: () => void) => void }, authors = [author]) {
  const dir = mkdtempSync(join(tmpdir(), 'bsaf-test-'));
  const path = join(dir, 'happyview.db');
  const db = new DatabaseSync(path);
  // Upstream v2.16 migrations: records + created_at + refs + table prefixes.
  db.exec(`PRAGMA foreign_keys=ON;
    CREATE TABLE happyview_records (
      uri TEXT PRIMARY KEY, did TEXT NOT NULL, collection TEXT NOT NULL,
      rkey TEXT NOT NULL, record TEXT NOT NULL, cid TEXT NOT NULL,
      indexed_at TEXT DEFAULT (datetime('now')), created_at TEXT NOT NULL DEFAULT (datetime('now'))
    );
    CREATE TABLE happyview_lexicons (
      id TEXT PRIMARY KEY, revision INTEGER NOT NULL DEFAULT 1, lexicon_json TEXT NOT NULL,
      backfill INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL DEFAULT (datetime('now')),
      updated_at TEXT NOT NULL DEFAULT (datetime('now'))
    );
    CREATE TABLE happyview_record_refs (
      source_uri TEXT NOT NULL REFERENCES happyview_records(uri) ON DELETE CASCADE,
      target_uri TEXT NOT NULL, collection TEXT NOT NULL, PRIMARY KEY(source_uri,target_uri)
    );`);
  let store = openStore(path, authors, upstream);
  t.after(() => { store.close(); db.close(); rmSync(dir, { recursive: true, force: true }); });
  return {
    db, path,
    get store() { return store; },
    reopen(authors: string[]) { store.close(); store = openStore(path, authors, upstream); return store; },
    rows() { return db.prepare('SELECT * FROM happyview_records').all(); },
  };
}

test('exact allowlist, original record/DID/URI/CID, raw incomplete core tags, provenance', async t => {
  const f = fixture(t);
  const raw = { ...post, tags: ['bsaf:v1', 'source:jma', 'target:unknown'],
    extension: { original: { did: other }, official: true } };
  const e = await event(1, raw);
  assert.equal(await f.store.ingest(e), 'stored');
  const row = f.rows()[0];
  assert.equal(row.uri, uri);
  assert.equal(row.did, author);
  assert.equal(row.cid, e.commit.cid);
  assert.deepEqual(JSON.parse(String(row.record)), raw);
  const provenance = f.db.prepare('SELECT * FROM mosimo_bsaf_events').get()!;
  assert.equal(provenance.source_url, upstream);
  assert.equal(provenance.cid, e.commit.cid);
  assert.ok(provenance.fetched_at);
  assert.equal(await f.store.ingest(await event(2, { ...post, tags: ['bsaf:v1', `source:${author}`],
    extension: { original: { did: author, uri } } }, 'create', other)), 'ignored');
  assert.equal(f.rows().length, 1);
  assert.equal(f.store.cursor, 2);
});

test('no promotion from extensions, missing/near-match marker, wrong type or collection', async t => {
  const f = fixture(t);
  for (const record of [
    { ...post, tags: [] }, { ...post, tags: ['bsaf:v10'] },
    { ...post, tags: ['BSAF:v1'] }, { ...post, tags: undefined },
    { ...post, tags: [], extension: { tags: ['bsaf:v1'] } },
    { ...post, $type: 'org.mosimo.post' },
  ]) {
    const json = JSON.parse(JSON.stringify(record));
    await f.store.ingest(await event(1 + Number(f.store.cursor ?? 0), json));
    assert.equal(f.rows().length, 0);
  }
  const wrong = await event(100);
  wrong.commit.collection = 'org.mosimo.post';
  assert.equal(await f.store.ingest(wrong), 'ignored');
});

test('created_at is first local fetch time, not record.createdAt, and survives updates', async t => {
  const f = fixture(t);
  const firstFetch = '2026-10-20T01:00:00.000Z';
  const nextFetch = '2026-10-20T02:00:00.000Z';
  t.mock.timers.enable({ apis: ['Date'], now: Date.parse(firstFetch) });
  assert.equal(await f.store.ingest(await event(1)), 'stored');
  assert.equal(f.rows()[0].created_at, firstFetch);
  assert.equal(f.rows()[0].indexed_at, firstFetch);
  t.mock.timers.setTime(Date.parse(nextFetch));
  const updated = { ...post, text: 'updated', createdAt: '2020-01-01T00:00:00Z' };
  assert.equal(await f.store.ingest(await event(2, updated, 'update')), 'stored');
  assert.equal(f.rows()[0].created_at, firstFetch);
  assert.equal(f.rows()[0].indexed_at, nextFetch);
  assert.deepEqual(JSON.parse(String(f.rows()[0].record)), updated);
});

test('missing/invalid required post fields reject without DB failure or changing the indexed row', async t => {
  const f = fixture(t);
  const original = await event(1);
  await f.store.ingest(original);
  const invalid = [
    { ...post, createdAt: undefined }, { ...post, createdAt: null },
    { ...post, createdAt: 123 }, { ...post, createdAt: '' },
    { ...post, createdAt: 'not a date' }, { ...post, createdAt: '2026-99-99T00:00:00Z' },
    { ...post, text: undefined }, { ...post, text: 123 },
    { ...post, $type: undefined }, { ...post, $type: 'org.mosimo.post' },
  ];
  for (const record of invalid) {
    const e = await event(1 + Number(f.store.cursor), JSON.parse(JSON.stringify(record)), 'update');
    assert.equal(await f.store.ingest(e), 'rejected');
    assert.equal(f.store.cursor, e.time_us);
    assert.equal(f.rows()[0].cid, original.commit.cid);
    assert.equal(f.db.prepare('SELECT time_us FROM mosimo_bsaf_events').get()!.time_us, 1);
  }
  assert.equal(await f.store.ingest(await event(100)), 'stored');
});

test('direct callers must await ingest; overlap rejects without advancing cursor', async t => {
  const f = fixture(t);
  const first = await event(10);
  const second = await event(20, { ...post, text: 'second' }, 'update');
  const pending = f.store.ingest(first);
  await assert.rejects(f.store.ingest(second), /Await the previous ingest/);
  assert.equal(await pending, 'stored');
  assert.equal(f.store.cursor, 10);
  assert.equal(await f.store.ingest(second), 'stored');
  assert.equal(f.store.cursor, 20);
});

test('fatal CLI diagnostics classify config/schema/subscription failures without leaking input', t => {
  const f = fixture(t);
  const configPath = join(f.path, '..', 'config.json');
  const secret = 'private-upstream-payload';
  function fatal(config: unknown) {
    writeFileSync(configPath, typeof config === 'string' ? config : JSON.stringify(config));
    const result = spawnSync(process.execPath, [fileURLToPath(new URL('./collector.mts', import.meta.url))], {
      env: { ...process.env, CONFIG_FILE: configPath }, encoding: 'utf8', timeout: 5000,
    });
    assert.equal(result.status, 1, result.stderr);
    assert.equal(result.stderr.includes(secret), false);
    const line = result.stderr.trim().split('\n').find(line => line.startsWith('{'));
    assert.ok(line, result.stderr);
    return JSON.parse(line);
  }
  assert.equal(fatal(`{${secret}`).reason, 'config');
  assert.equal(fatal({ databasePath: f.path, authors: [], jetstreamUrl: `wss://user:${secret}@example.org` }).reason, 'config');
  const config = { databasePath: ':memory:', authors: [], jetstreamUrl: upstream };
  assert.match(fatal(config).message, /HappyView 2.16 migrations/);
  assert.equal(fatal(config).reason, 'schema');
  assert.equal(fatal({ ...config, databasePath: join(f.path, secret) }).reason, 'database');
  f.db.prepare('INSERT INTO happyview_lexicons(id,lexicon_json) VALUES(?,?)').run(COLLECTION, '{}');
  const native = fatal({ ...config, databasePath: f.path });
  assert.equal(native.reason, 'subscription');
  assert.match(native.message, /Remove native app.bsky.feed.post subscription/);
});

test('bad CID never stores or deletes; Lex JSON links/bytes hash as CBOR', async t => {
  const f = fixture(t);
  const valid = await event();
  await f.store.ingest(valid);
  const bad = await event(2, { ...post, tags: [] }, 'update');
  bad.commit.cid = 'not-a-cid';
  assert.equal(await f.store.ingest(bad), 'rejected');
  assert.equal(f.rows()[0].cid, valid.commit.cid);
  assert.equal(f.db.prepare('SELECT time_us FROM mosimo_bsaf_events').get()!.time_us, 1);
  const linked = { ...post, embed: { ref: { $link: valid.commit.cid }, data: { $bytes: 'YWJj' } } };
  const linkedEvent = await event(3);
  linkedEvent.commit.record = linked;
  linkedEvent.commit.cid = (await cidForLex({ ...post,
    embed: { ref: parseCid(valid.commit.cid), data: new Uint8Array([97, 98, 99]) } })).toString();
  assert.equal(await f.store.ingest(linkedEvent), 'stored');
  assert.deepEqual(JSON.parse(String(f.rows()[0].record)), linked);
});

test('updates, marker loss, deletion tombstones, stale resurrection, equal timestamps, persisted cursor', async t => {
  const f = fixture(t);
  await f.store.ingest(await event(10));
  await f.store.ingest(await event(20, { ...post, text: 'updated' }, 'update'));
  assert.equal(JSON.parse(String(f.rows()[0].record)).text, 'updated');
  assert.equal(await f.store.ingest(await event(30, { ...post, tags: [] }, 'update')), 'deleted');
  assert.equal(await f.store.ingest(await event(20)), 'stale');
  assert.equal(f.rows().length, 0);
  await f.store.ingest(await event(40));
  f.db.prepare('INSERT INTO happyview_record_refs VALUES(?,?,?)').run(uri, 'at://target', COLLECTION);
  await f.store.ingest({ did: author, kind: 'commit', time_us: 50,
    commit: { collection: COLLECTION, rkey: 'one', operation: 'delete' } });
  assert.equal(f.db.prepare('SELECT count(*) AS n FROM happyview_record_refs').get()!.n, 0);
  assert.equal(f.rows().length, 0);
  assert.equal(f.db.prepare('SELECT cid FROM mosimo_bsaf_events').get()!.cid, null);
  f.reopen([author]);
  assert.equal(f.store.cursor, 50);
  assert.equal(await f.store.ingest(await event(50)), 'stale');
  assert.equal(await f.store.ingest(await event(49)), 'stale');
  assert.equal(f.store.cursor, 50);
  assert.equal(await f.store.ingest(await event(51)), 'stored');
});

test('revoked sources purge only owned BSAF rows, cascade refs, preserve Mosimo and tombstones', async t => {
  const f = fixture(t, [author, other]);
  await f.store.ingest(await event());
  await f.store.ingest(await event(2, post, 'create', other));
  f.db.prepare('INSERT INTO happyview_record_refs VALUES(?,?,?)').run(uri, 'target', COLLECTION);
  f.db.prepare('INSERT INTO happyview_records(uri,did,collection,rkey,record,cid) VALUES(?,?,?,?,?,?)')
    .run('at://mosimo', author, 'org.mosimo.post', 'one', '{}', 'mosimo-cid');
  f.reopen([other]);
  assert.deepEqual(f.db.prepare('SELECT did FROM mosimo_bsaf_sources').all().map(r => r.did), [other]);
  assert.equal(f.rows().length, 2);
  assert.ok(f.rows().some(r => r.uri === 'at://mosimo'));
  assert.equal(f.db.prepare('SELECT count(*) AS n FROM happyview_record_refs').get()!.n, 0);
  assert.equal(await f.store.ingest(await event(3)), 'ignored');
  f.reopen([]);
  assert.equal(f.rows().length, 1);
  assert.equal(f.db.prepare('PRAGMA foreign_key_check').all().length, 0);
  f.reopen([author]);
  assert.equal(await f.store.ingest(await event(1)), 'stale');
  assert.equal(await f.store.ingest(await event(4)), 'stored');
});

test('native subscription guard, missing DB/schema, config validation', t => {
  const f = fixture(t);
  f.db.prepare('INSERT INTO happyview_lexicons(id,lexicon_json) VALUES(?,?)').run(COLLECTION, '{}');
  assert.throws(() => openStore(f.path, [], upstream), /native/);
  assert.throws(() => openStore(join(f.path, 'missing'), [], upstream));
  assert.throws(() => openStore(':memory:', [], upstream), /schema/);
  assert.throws(() => openStore(f.path, ['*'], upstream), /CONFIG_FILE/);
  assert.throws(() => openStore(f.path, [author, author], upstream), /CONFIG_FILE/);
});

test('allowlist reconciliation is atomic when cleanup fails and journal mode stays unchanged', async t => {
  const f = fixture(t, [author, other]);
  await f.store.ingest(await event());
  const journal = f.db.prepare('PRAGMA journal_mode').get()!.journal_mode;
  f.db.exec(`CREATE TRIGGER reject_delete BEFORE DELETE ON happyview_records BEGIN SELECT RAISE(ABORT,'cleanup failed'); END;`);
  assert.throws(() => openStore(f.path, [], upstream), /initialize BSAF store/);
  assert.deepEqual(f.db.prepare('SELECT did FROM mosimo_bsaf_sources ORDER BY did').all().map(r => r.did), [author, other]);
  assert.equal(f.rows().length, 1);
  assert.equal(f.db.prepare('PRAGMA journal_mode').get()!.journal_mode, journal);
});

test('record failure rolls back cursor and provenance together', async t => {
  const f = fixture(t);
  f.db.exec(`CREATE TRIGGER reject_insert BEFORE INSERT ON happyview_records BEGIN SELECT RAISE(ABORT,'test failure'); END;`);
  await assert.rejects(f.store.ingest(await event(100)), /test failure/);
  assert.equal(f.store.cursor, null);
  assert.equal(f.db.prepare('SELECT count(*) AS n FROM mosimo_bsaf_events').get()!.n, 0);
  assert.equal(f.rows().length, 0);
});

async function server(t: { after: (fn: () => Promise<void>) => void }) {
  const wss = new WebSocketServer({ port: 0, host: '127.0.0.1' });
  await once(wss, 'listening');
  t.after(async () => { for (const ws of wss.clients) ws.terminate(); await new Promise<void>(resolve => wss.close(() => resolve())); });
  const address = wss.address();
  assert.ok(typeof address === 'object' && address);
  return { wss, url: `ws://127.0.0.1:${address.port}/subscribe` };
}

test('ws readable object mode preserves adjacent and fragmented text/binary messages with bounded queue', async t => {
  const { wss, url } = await server(t);
  wss.on('connection', ws => {
    ws.send('{"n":', { fin: false }); ws.send('1}', { fin: true });
    ws.send('{"n":2}'); ws.send(Buffer.from('{"n":3}')); ws.close();
  });
  const ws = new WebSocket(url);
  const stream = createWebSocketStream(ws, { readableObjectMode: true, highWaterMark: 2 });
  assert.equal(stream.readableObjectMode, true);
  assert.equal(stream.readableHighWaterMark, 2);
  await once(stream, 'readable');
  await delay(10);
  assert.equal(ws.isPaused, true);
  const chunks: unknown[] = [];
  for await (const chunk of stream) { chunks.push(chunk); await delay(5); }
  assert.equal(chunks.length, 3);
  assert.equal(typeof chunks[0], 'string');
  assert.ok(Buffer.isBuffer(chunks[2]));
  assert.deepEqual(chunks.map(c => JSON.parse(String(c))), [{ n: 1 }, { n: 2 }, { n: 3 }]);
  stream.destroy();
});

test('local upstream reconnect uses committed cursor, explicit filters; empty list never connects', { timeout: 10000 }, async t => {
  const { wss, url } = await server(t);
  const f = fixture(t);
  const config = { databasePath: f.path, authors: [author], jetstreamUrl: url + '?wantedDids=evil&compress=true' };
  const first = await event(100);
  const second = await event(101, { ...post, text: 'after reconnect' }, 'update');
  let connections = 0;
  const controller = new AbortController();
  wss.on('connection', (ws, request) => {
    const query = new URL(request.url!, url).searchParams;
    assert.deepEqual(query.getAll('wantedDids'), [author]);
    assert.deepEqual(query.getAll('wantedCollections'), [COLLECTION]);
    assert.deepEqual(query.getAll('compress'), ['false']);
    connections++;
    if (connections === 1) { assert.equal(query.has('cursor'), false); ws.send(JSON.stringify(first)); ws.close(); }
    else { assert.equal(query.get('cursor'), '99'); ws.send(JSON.stringify(second)); }
  });
  const running = collect(config, f.store, { signal: controller.signal, reconnectMs: 5 });
  t.after(() => controller.abort());
  for (let n = 0; f.store.cursor !== 101 && n < 200; n++) await delay(10);
  controller.abort();
  await running;
  assert.equal(f.store.cursor, 101);
  assert.equal(connections, 2);
  assert.equal(JSON.parse(String(f.rows()[0].record)).text, 'after reconnect');
  await collect({ ...config, authors: [] }, f.store, { signal: new AbortController().signal });
  assert.equal(connections, 2);
  assert.equal(subscriptionUrl({ ...config, authors: [] }, 1), null);
});

test('collector stops on store errors instead of reconnecting past them', { timeout: 5000 }, async t => {
  const { wss, url } = await server(t);
  wss.on('connection', ws => ws.send('{}'));
  const store: Store = { cursor: null, close() {}, async ingest() { throw new Error('db failed'); } };
  await assert.rejects(collect({ databasePath: ':memory:', authors: [author], jetstreamUrl: url }, store,
    { signal: new AbortController().signal }), /db failed/);
});
