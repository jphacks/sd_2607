import assert from 'node:assert/strict';
import test from 'node:test';
import { once } from 'node:events';
import { setImmediate as immediate } from 'node:timers/promises';
import { WebSocketServer } from 'ws';
import { startNetwork, createPriorityPolicy } from './network.mjs';
import { mkdtemp, rm, readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { PGlite } from '@electric-sql/pglite';
import { openSqliteStore, schema } from './store.mjs';
import { canonical, decodeCanonical, createSnapshot, createRoot, rootId, timestamp,
  createImporter, issueCertificate, createImporterEvent, keyPublic, BSAF, NS, certId } from './public-information.mjs';

for (const dialect of ['sqlite', 'postgres']) test(`${dialect}: priority trust highwater, cumulative root revocation and serial conflicts are durable`, async t => {
  const fixture = JSON.parse(await readFile(new URL('../apps/android/bundle-core/src/test/resources/wire-v1.json', import.meta.url), 'utf8'));
  const now = fixture.event.created_at;
  const directory = await mkdtemp(join(tmpdir(), 'mosimo-trust-'));
  const path = join(directory, dialect);
  const open = async () => {
    if (dialect === 'sqlite') return openSqliteStore(path);
    const db = new PGlite(path);
    await db.waitReady; await db.exec(schema);
    const query = async (sql, values) => /^SELECT pg_advisory_xact_lock/.test(sql) ? { rows: [] } : db.query(sql, values);
    return { dialect, query, transaction: fn => db.transaction(tx => fn({ query: (sql, values) => /^SELECT pg_advisory_xact_lock/.test(sql) ? Promise.resolve({ rows: [] }) : tx.query(sql, values) })), close: () => db.close() };
  };
  let store = await open();
  t.after(async () => { await store.close(); await rm(directory, { recursive: true, force: true }); });
  const root = fixture.config.roots[0];
  const second = createRoot({ scope: root.scope, snapshotUrl: 'https://second.invalid/snapshot.cbor' }).root;
  const config = { publicInformation: { roots: [root, second], selection: fixture.config.selected.map(e => ({ botDid: e.botDid, types: [e.type] })) } };
  let snapshot = decodeCanonical(Buffer.from(fixture.snapshotHex, 'hex'));
  const policy = () => createPriorityPolicy({ config, store, now: () => now, getBytes: async (url, options) => {
    assert.equal(options.maxBytes, 32768); assert.equal(options.contentType, 'application/cbor');
    if (url === second.snapshotUrl) throw new Error('independent root offline');
    return canonical(snapshot);
  } });
  assert.equal(await (await policy().capture())(fixture.event), 3);
  // Simulate an existing pre-marker database: startup adds the sidecar without losing serials.
  await store.query('DROP TABLE trust_serial_conflicts');
  await store.close(); store = await open();
  assert.equal(await (await policy().capture())(fixture.event), 3);
  let clock = now;
  const expiration = Math.min(Date.parse(snapshot.body.validUntil), Date.parse(fixture.certificate.body.notAfter),
    Date.parse(fixture.event.tags.find(tag => tag[0] === `${NS}:delivery-expires-at`)[1])) / 1000;
  const livePolicy = createPriorityPolicy({ config, store, now: () => clock,
    getBytes: async url => { if (url === second.snapshotUrl) throw new Error('offline'); return canonical(snapshot); } });
  const liveEvaluate = await livePolicy.capture();
  assert.equal(await liveEvaluate(fixture.event), 3);
  clock = expiration;
  assert.throws(() => liveEvaluate.assertFresh(), /expired\/clock changed/);
  assert.equal(await liveEvaluate(fixture.event), 1, 'authorization clock must not freeze at capture time');
  clock = now;
  const delayed = createPriorityPolicy({ config: { publicInformation: { ...config.publicInformation, roots: [root] } }, store,
    now: () => clock, getBytes: async () => { clock = expiration; return canonical(snapshot); } });
  assert.equal(await (await delayed.capture())(fixture.event), 1, 'fetch latency cannot authorize an expired event');
  const importer = createImporter();
  const certificateA = issueCertificate('01'.padStart(64, '0'), { ...fixture.certificate.body,
    serial: 'a'.repeat(32), subjectDid: importer.did, subjectPubkey: keyPublic(importer.atprotoSecret) });
  const certificateB = issueCertificate('01'.padStart(64, '0'), { ...certificateA.body, orgName: 'Conflicting body' });
  const bsafTag = fixture.event.tags.find(tag => tag[0] === `${NS}:ext:${BSAF}`);
  const extension = { $type: BSAF, ...decodeCanonical(Buffer.from(bsafTag[1], 'base64')) };
  const make = async certificate => (await createImporterEvent({ importer, chain: [certificate], text: 'Test sticky conflict',
    extensions: [extension], nowSeconds: now, deliveryExpiresAt: timestamp(now + 600), priority: 3 })).event;
  const eventA = await make(certificateA), eventB = await make(certificateB);
  const evaluate = await policy().capture();
  assert.equal(await evaluate(eventA), 3);
  const failingStore = { ...store, transaction: fn => store.transaction(tx => fn({ query: (sql, values) => {
    if (sql.startsWith('INSERT INTO trust_serial_conflicts')) throw new Error('marker disk failure');
    return tx.query(sql, values);
  } })) };
  const brokenMarker = createPriorityPolicy({ config: { publicInformation: { ...config.publicInformation, roots: [root] } },
    store: failingStore, now: () => now, getBytes: async () => canonical(snapshot) });
  await assert.rejects((await brokenMarker.capture())(eventB), error => error.trustStorage === true);
  assert.equal((await store.query('SELECT * FROM trust_serial_conflicts')).rows.length, 0);
  assert.equal(await evaluate(eventA), 3);
  assert.equal(await evaluate(eventB), 1);
  assert.deepEqual((await store.query('SELECT * FROM trust_serial_conflicts')).rows,
    [{ issuer: certificateA.body.issuerPubkey, serial: certificateA.body.serial }]);
  assert.equal(await evaluate(eventA), 1, 'A must remain rejected after A→B conflict');
  const serial = (await store.query('SELECT * FROM trust_serials WHERE serial=$1', [certificateA.body.serial])).rows[0];
  assert.equal(serial.cert_id, certId(certificateA));
  await store.close(); store = await open();
  assert.equal(await (await policy().capture())(eventA), 1, 'A rejection survives restart');
  assert.equal(await (await policy().capture())(eventB), 1, 'B rejection survives restart');
  const secret = '01'.padStart(64, '0');
  const signed = (sequence, extra = {}) => createSnapshot(secret, { sequence, issuedAt: timestamp(now), validUntil: timestamp(now + 600), ...extra });
  snapshot = signed(8, { revokedPubkeys: [root.rootPubkey] });
  assert.equal(await (await policy().capture())(fixture.event), 1);
  await store.close(); store = await open();
  assert.equal(JSON.parse((await store.query('SELECT snapshot FROM trust_snapshots WHERE root_id=$1', [rootId(root.rootPubkey)])).rows[0].snapshot).body.sequence, 8);
  // Rollback, same-sequence equivocation, CRL removal and stale/future data cannot erase revocation.
  for (const invalid of [signed(7), signed(8), signed(9), signed(9, { issuedAt: timestamp(now + 1), validUntil: timestamp(now + 601) }),
    signed(9, { issuedAt: timestamp(now - 601), validUntil: timestamp(now - 1) })]) {
    snapshot = invalid;
    assert.equal(await (await policy().capture())(fixture.event), 1);
    const saved = JSON.parse((await store.query('SELECT snapshot FROM trust_snapshots WHERE root_id=$1', [rootId(root.rootPubkey)])).rows[0].snapshot);
    assert.equal(saved.body.sequence, 8); assert.deepEqual(saved.body.revokedPubkeys, [root.rootPubkey]);
  }
  let elapsed = 0;
  const timer = t.mock.method(performance, 'now', () => elapsed);
  const fetched = [];
  const refreshing = createPriorityPolicy({ config: { publicInformation: { ...config.publicInformation, roots: [second, root] } },
    store, now: () => now, getBytes: async url => {
      fetched.push(url);
      if (url === second.snapshotUrl) throw new Error('first root offline');
      return canonical(snapshot);
    } });
  snapshot = signed(9, { revokedPubkeys: [root.rootPubkey] });
  await refreshing.capture();
  for (let i = 1; i < 30; i++) { elapsed = i * 1000; await refreshing.capture(); }
  assert.equal(fetched.length, 2, 'frequent captures neither refetch nor reset the per-root due time');
  elapsed = 30000;
  snapshot = signed(10, { revokedPubkeys: [root.rootPubkey] });
  await refreshing.capture();
  assert.deepEqual(fetched, [second.snapshotUrl, root.snapshotUrl, second.snapshotUrl, root.snapshotUrl]);
  assert.equal(JSON.parse((await store.query('SELECT snapshot FROM trust_snapshots WHERE root_id=$1', [rootId(root.rootPubkey)])).rows[0].snapshot).body.sequence, 10);
  timer.mock.restore();
  const broken = createPriorityPolicy({ config, store: { ...store, transaction: async () => { throw new Error('disk failure'); } }, now: () => now,
    getBytes: async () => canonical(signed(10, { revokedPubkeys: [root.rootPubkey] })) });
  await assert.rejects(broken.capture(), /Trust state update failed/);
});

const eventId = 'a'.repeat(64);
const event = { id: eventId, kind: 1, created_at: 1, tags: [['t', 'mosimo.oriom.dev']], content: 'offline post' };
const peer = { url: 'https://trusted.example', tokenEnv: 'NETWORK_TEST_TOKEN' };
const json = value => new Response(JSON.stringify(value), { headers: { 'content-type': 'application/json' } });
const deferred = () => Promise.withResolvers();

async function until(predicate) {
  const deadline = Date.now() + 2000;
  while (!predicate()) {
    assert.ok(Date.now() < deadline, 'Timed out waiting for network activity');
    await immediate();
  }
}

// Only model the network's SQL boundary, not ingestion or repository storage.
function stubStore({ outbox = [], known = [] } = {}) {
  const calls = [];
  const store = { calls, outbox: outbox.map(row => ({ ...row })), known: new Set(known), now: 0 };
  store.query = async (sql, params = []) => {
    calls.push({ sql, params });
    if (sql === 'SELECT 1 FROM events WHERE id=$1') return { rowCount: Number(store.known.has(params[0])), rows: [] };
    if (sql.startsWith('SELECT * FROM outbox')) {
      assert.match(sql, /next_attempt<=now\(\).*ORDER BY next_attempt LIMIT 1/);
      return { rows: store.outbox.filter(row => (row.due ?? 0) <= store.now).sort((a, b) => (a.due ?? 0) - (b.due ?? 0)).slice(0, 1).map(row => ({ ...row })) };
    }
    const index = store.outbox.findIndex(row => row.peer === params[0] && row.event_id === params[1]);
    if (sql.startsWith('DELETE FROM outbox')) {
      if (index !== -1) store.outbox.splice(index, 1);
      return { rowCount: Number(index !== -1), rows: [] };
    }
    if (sql.startsWith('UPDATE outbox SET attempts=attempts+1')) {
      assert.match(sql, /next_attempt=now\(\)\+make_interval\(secs => \$3\)/);
      assert.notEqual(index, -1);
      store.outbox[index].attempts++;
      store.outbox[index].due = store.now + params[2];
      return { rowCount: 1, rows: [] };
    }
    assert.fail(`Unexpected network SQL: ${sql}`);
  };
  return store;
}

function setup(t, { store = stubStore(), ingest = async () => ({ accepted: true }), peers = [], relays = [], maxEventBytes = 4096 } = {}) {
  const errors = t.mock.method(console, 'error', () => {});
  const fetch = t.mock.method(globalThis, 'fetch', async url => assert.fail(`Unexpected fetch: ${url}`));
  const network = startNetwork({ config: { peers, relays, maxEventBytes }, store, ingest });
  t.after(() => network.close());
  return { network, store, fetch, errors };
}

function token(t) {
  const old = process.env[peer.tokenEnv];
  process.env[peer.tokenEnv] = 'test-only-secret';
  t.after(() => { if (old === undefined) delete process.env[peer.tokenEnv]; else process.env[peer.tokenEnv] = old; });
}

async function relay(t) {
  const wss = new WebSocketServer({ host: '127.0.0.1', port: 0 });
  await once(wss, 'listening');
  t.after(async () => {
    for (const socket of wss.clients) socket.terminate();
    await new Promise(resolve => wss.close(resolve));
  });
  const connected = once(wss, 'connection').then(async ([socket]) => {
    const [bytes] = await once(socket, 'message');
    return { socket, request: JSON.parse(bytes.toString()) };
  });
  return { wss, connected, url: `ws://127.0.0.1:${wss.address().port}` };
}

const pendingRow = (attempts = 0, url = peer.url) => ({ peer: url, event_id: eventId, attempts });
const turn = async () => { for (let i = 0; i < 10; i++) await immediate(); };

// Top-level tests deliberately run serially: fetch, environment and clocks are process globals.
test('relay subscribes without a timestamp watermark and ingests only matching text EVENT frames in order', async t => {
  const server = await relay(t);
  const first = deferred();
  t.after(() => first.resolve());
  const ingested = [];
  const { network } = setup(t, { relays: [server.url], ingest: async (...args) => {
    ingested.push(args);
    if (ingested.length === 1) await first.promise;
    return { accepted: true };
  } });
  const { socket, request } = await server.connected;
  assert.deepEqual(request, ['REQ', 'mosimo', { kinds: [1], '#t': ['mosimo.oriom.dev'] }, { kinds: [5] }]);
  socket.send('not json');
  socket.send(JSON.stringify(['EVENT', 'mosimo', { id: 'binary' }]), { binary: true });
  socket.send(JSON.stringify(['EVENT', 'other-subscription', event]));
  socket.send(JSON.stringify(['EOSE', 'mosimo']));
  socket.send(JSON.stringify(['EVENT', 'mosimo', event]));
  const deletion = { ...event, id: 'b'.repeat(64), kind: 5 };
  socket.send(JSON.stringify(['EVENT', 'mosimo', deletion]));
  await until(() => ingested.length === 1);
  await turn();
  assert.deepEqual(ingested, [[event]]);
  first.resolve();
  await until(() => ingested.length === 2);
  assert.deepEqual(ingested, [[event], [deletion]]);
  const closed = once(socket, 'close');
  await network.close();
  await closed;
});

test('relay backpressures a history burst above 100 without dropping events or reconnecting', async t => {
  t.mock.timers.enable({ apis: ['setInterval'] });
  const server = await relay(t);
  const first = deferred();
  t.after(() => first.resolve());
  const history = Array.from({ length: 526 }, (_, index) => ({ ...event, id: index.toString(16).padStart(64, '0') }));
  const ingested = [];
  let connections = 0;
  server.wss.on('connection', () => connections++);
  const { network, errors } = setup(t, { relays: [server.url], ingest: async value => {
    ingested.push(value);
    if (ingested.length === 1) await first.promise;
    else await immediate();
    return { accepted: true };
  } });
  const { socket } = await server.connected;
  socket.send(JSON.stringify(['EVENT', 'mosimo', history[0]]));
  await until(() => ingested.length === 1);
  for (const value of history.slice(1)) socket.send(JSON.stringify(['EVENT', 'mosimo', value]));
  socket.send('not json');
  socket.send(JSON.stringify(['EVENT', 'mosimo', { id: 'binary' }]), { binary: true });
  socket.send(JSON.stringify(['EVENT', 'other-subscription', event]));
  await new Promise((resolve, reject) => socket.send(JSON.stringify(['EOSE', 'mosimo']), error => error ? reject(error) : resolve()));
  await turn();
  assert.deepEqual(ingested, [history[0]], 'only one ingestion may be active while storage is held');
  t.mock.timers.tick(90000);
  first.resolve();
  await until(() => ingested.length === history.length);
  assert.deepEqual(ingested, history);
  const live = { ...event, id: 'f'.repeat(64), kind: 5 };
  socket.send(JSON.stringify(['EVENT', 'mosimo', live]));
  await until(() => ingested.length === history.length + 1);
  assert.deepEqual(ingested, [...history, live]);
  assert.equal(connections, 1);
  assert.equal(socket.readyState, socket.OPEN);
  assert.equal(errors.mock.callCount(), 0);
  const closed = once(socket, 'close');
  await network.close();
  await closed;
});

test('relay drains buffered history when the upstream gracefully closes', async t => {
  const server = await relay(t);
  const first = deferred();
  t.after(() => first.resolve());
  const history = Array.from({ length: 150 }, (_, index) => ({ ...event, content: `post ${index}` }));
  const ingested = [];
  const { network, errors } = setup(t, { relays: [server.url], ingest: async value => {
    ingested.push(value);
    if (ingested.length === 1) await first.promise;
    return { accepted: true };
  } });
  const { socket } = await server.connected;
  socket.send(JSON.stringify(['EVENT', 'mosimo', history[0]]));
  await until(() => ingested.length === 1);
  for (const value of history.slice(1)) socket.send(JSON.stringify(['EVENT', 'mosimo', value]));
  const closed = once(socket, 'close');
  socket.close(1000, 'history complete');
  await turn();
  first.resolve();
  await closed;
  await until(() => ingested.length === history.length);
  assert.deepEqual(ingested, history);
  await network.close();
  assert.equal(errors.mock.callCount(), 0);
});

test('relay ingestion failure does not poison the next EVENT', async t => {
  const server = await relay(t);
  const ingested = [];
  const { errors } = setup(t, { relays: [server.url], ingest: async value => {
    ingested.push(value);
    if (ingested.length === 1) throw new Error('temporary ingestion failure');
    return { accepted: true };
  } });
  const { socket } = await server.connected;
  socket.send(JSON.stringify(['EVENT', 'mosimo', event]));
  socket.send(JSON.stringify(['EVENT', 'mosimo', { ...event, kind: 5 }]));
  await until(() => ingested.length === 2);
  assert.equal(errors.mock.callCount(), 1);
});

test('notify uses only configured peer URLs, disables redirects, authenticates and prevents forwarding loops', async t => {
  token(t);
  const ingested = [];
  const { network, fetch } = setup(t, { peers: [peer], ingest: async (...args) => { ingested.push(args); return { accepted: true }; } });
  fetch.mock.mockImplementation(async (url, options) => {
    assert.equal(url, `${peer.url}/internal/events/${eventId}`);
    assert.equal(options.redirect, 'error');
    assert.equal(options.headers.authorization, 'Bearer test-only-secret');
    assert.ok(options.signal instanceof AbortSignal);
    return json(event);
  });
  await network.onNotify({ eventId, url: 'http://169.254.169.254/latest/meta-data/' });
  assert.deepEqual(ingested, [[event, { forward: false }]]);
  assert.equal(fetch.mock.callCount(), 1);
});

test('notify skips known events and coalesces concurrent retrievals of the same ID', async t => {
  const response = deferred();
  const ingested = [];
  const store = stubStore({ known: ['b'.repeat(64)] });
  const { network, fetch } = setup(t, { store, peers: [peer], ingest: async value => { ingested.push(value); return { accepted: true }; } });
  fetch.mock.mockImplementation(() => response.promise);
  t.after(() => response.resolve(json(event)));
  await network.onNotify({ eventId: 'b'.repeat(64) });
  assert.equal(fetch.mock.callCount(), 0);
  const a = network.onNotify({ eventId });
  const b = network.onNotify({ eventId });
  await until(() => fetch.mock.callCount() === 1);
  response.resolve(json(event));
  await Promise.all([a, b]);
  assert.deepEqual(ingested, [event]);
  assert.equal(fetch.mock.callCount(), 1);
});

test('notify skips mismatched IDs and rejected ingestion before trying the next trusted peer', async t => {
  const peers = [peer, { ...peer, url: 'https://second.example' }, { ...peer, url: 'https://third.example' }];
  const ingested = [];
  const { network, fetch } = setup(t, { peers, ingest: async (...args) => {
    ingested.push(args);
    return { accepted: ingested.length > 1 };
  } });
  fetch.mock.mockImplementation(async url => json(url.startsWith(peer.url) ? { ...event, id: 'b'.repeat(64) } : event));
  await network.onNotify({ eventId });
  assert.deepEqual(fetch.mock.calls.map(call => call.arguments[0]), peers.map(p => `${p.url}/internal/events/${eventId}`));
  assert.deepEqual(ingested, [[event, { forward: false }], [event, { forward: false }]]);
});

test('notify fails on mismatched IDs, redirects, oversized responses or HTTP errors and can be retried', async t => {
  const ingested = [];
  const { network, fetch } = setup(t, { peers: [peer], maxEventBytes: 256, ingest: async value => { ingested.push(value); return { accepted: true }; } });
  for (const reply of [
    () => json({ ...event, id: 'b'.repeat(64) }),
    (_url, options) => { assert.equal(options.redirect, 'error'); throw new TypeError('unexpected redirect'); },
    () => json({ ...event, content: 'x'.repeat(257) }),
    () => new Response('unavailable', { status: 503 }),
  ]) {
    fetch.mock.mockImplementation(reply);
    await assert.rejects(network.onNotify({ eventId }), /No configured peer/);
  }
  assert.deepEqual(ingested, []);
  fetch.mock.mockImplementation(async () => json(event));
  await network.onNotify({ eventId });
  assert.deepEqual(ingested, [event]);
});

test('durable outbox sends only an event ID then deletes the successfully acknowledged row', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  token(t);
  const store = stubStore({ outbox: [pendingRow()] });
  const { fetch } = setup(t, { store, peers: [peer] });
  fetch.mock.mockImplementation(async (url, options) => {
    assert.equal(url, `${peer.url}/internal/notify`);
    assert.equal(options.method, 'POST');
    assert.equal(options.redirect, 'error');
    assert.deepEqual(options.headers, { 'content-type': 'application/json', authorization: 'Bearer test-only-secret' });
    assert.deepEqual(JSON.parse(options.body), { eventId });
    assert.equal(store.outbox.length, 1, 'retain durable row until acknowledgement');
    return json({ ok: true });
  });
  await until(() => store.outbox.length === 0);
  t.mock.timers.tick(1000);
  await turn();
  assert.equal(fetch.mock.callCount(), 1);
});

test('durable outbox retains failed delivery, schedules exponential retry and succeeds when due', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const store = stubStore({ outbox: [pendingRow()] });
  const { fetch } = setup(t, { store, peers: [peer] });
  fetch.mock.mockImplementation(async () => new Response('unavailable', { status: 503 }));
  await until(() => store.outbox[0]?.attempts === 1);
  assert.equal(store.outbox[0].due, 2);
  store.now = 1;
  t.mock.timers.tick(1000);
  await turn();
  assert.equal(fetch.mock.callCount(), 1);
  store.now = 2;
  fetch.mock.mockImplementation(async () => json({ ok: true }));
  t.mock.timers.tick(1000);
  await until(() => store.outbox.length === 0);
  assert.equal(fetch.mock.callCount(), 2);
});

test('outbox drops exhausted or removed peers without contacting an untrusted persisted URL', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const store = stubStore({ outbox: [pendingRow(10), pendingRow(0, 'http://169.254.169.254')] });
  const { fetch } = setup(t, { store, peers: [peer] });
  await until(() => store.outbox.length === 1);
  t.mock.timers.tick(1000);
  await until(() => store.outbox.length === 0);
  assert.equal(fetch.mock.callCount(), 0);
});

test('tenth failed outbox delivery is persisted then exhausted without an eleventh request', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const store = stubStore({ outbox: [pendingRow(9)] });
  const { fetch } = setup(t, { store, peers: [peer] });
  fetch.mock.mockImplementation(async () => { throw new Error('offline'); });
  await until(() => store.outbox[0]?.attempts === 10);
  assert.equal(store.outbox[0].due, 1024);
  store.now = 1024;
  t.mock.timers.tick(1000);
  await until(() => store.outbox.length === 0);
  assert.equal(fetch.mock.callCount(), 1);
});

test('close cancels scheduled outbox polls and waits for an active peer retrieval', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const response = deferred();
  const { network, fetch, store } = setup(t, { peers: [peer] });
  fetch.mock.mockImplementation(() => response.promise);
  t.after(() => response.resolve(json(event)));
  const notification = network.onNotify({ eventId });
  await until(() => fetch.mock.callCount() === 1);
  let closed = false;
  const closing = network.close().then(() => { closed = true; });
  await turn();
  assert.equal(closed, false);
  response.resolve(json(event));
  await Promise.all([closing, notification]);
  const calls = store.calls.length;
  t.mock.timers.tick(120000);
  await turn();
  assert.equal(store.calls.length, calls);
  await network.close();
});

test('close must not schedule a relay reconnect timer after clearing timers', async t => {
  const server = await relay(t);
  const { network } = setup(t, { relays: [server.url] });
  const { socket } = await server.connected;
  await turn();
  const timeouts = t.mock.method(globalThis, 'setTimeout');
  const closed = once(socket, 'close');
  await network.close();
  await closed;
  await turn();
  // Remove leaked handles ourselves so the regression test does not delay process exit.
  for (const call of timeouts.mock.calls) clearTimeout(call.result);
  assert.equal(timeouts.mock.callCount(), 0, 'socket close scheduled a reconnect timer after network shutdown');
});

test('close must drain active outbox delivery before main closes the store', async t => {
  const response = deferred();
  const store = stubStore({ outbox: [pendingRow()] });
  const { network, fetch } = setup(t, { store, peers: [peer] });
  fetch.mock.mockImplementation(() => response.promise);
  t.after(() => response.resolve(json({ ok: true })));
  await until(() => fetch.mock.callCount() === 1);
  let closed = false;
  const closing = network.close().then(() => { closed = true; });
  await turn();
  const closedBeforeDelivery = closed;
  response.resolve(json({ ok: true }));
  await closing;
  await until(() => store.outbox.length === 0);
  assert.equal(closedBeforeDelivery, false, 'network.close resolved while outbox still needed the store');
});

test('close must drain active relay ingestion before main closes the store', async t => {
  const server = await relay(t);
  const ingestion = deferred();
  let started = 0;
  const { network, errors } = setup(t, { relays: [server.url], ingest: async () => {
    started++;
    await ingestion.promise;
    return { accepted: true };
  } });
  t.after(() => ingestion.resolve());
  const { socket } = await server.connected;
  socket.send(JSON.stringify(['EVENT', 'mosimo', event]));
  await until(() => started === 1);
  socket.send(JSON.stringify(['EVENT', 'mosimo', { ...event, kind: 5 }]));
  await turn();
  let closed = false;
  const socketClosed = once(socket, 'close');
  const closing = network.close().then(() => { closed = true; });
  await socketClosed;
  await turn();
  const closedBeforeIngestion = closed;
  ingestion.resolve();
  await closing;
  assert.equal(closedBeforeIngestion, false, 'network.close resolved while relay ingestion still needed the store');
  assert.equal(started, 1, 'shutdown must not start ingestion of buffered messages');
  assert.equal(errors.mock.callCount(), 0);
});
