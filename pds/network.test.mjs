import assert from 'node:assert/strict';
import test from 'node:test';
import { once } from 'node:events';
import { setImmediate as immediate } from 'node:timers/promises';
import { WebSocketServer } from 'ws';
import { startNetwork } from './network.mjs';

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
  let started = false;
  const { network } = setup(t, { relays: [server.url], ingest: async () => {
    started = true;
    await ingestion.promise;
    return { accepted: true };
  } });
  t.after(() => ingestion.resolve());
  const { socket } = await server.connected;
  socket.send(JSON.stringify(['EVENT', 'mosimo', event]));
  await until(() => started);
  let closed = false;
  const socketClosed = once(socket, 'close');
  const closing = network.close().then(() => { closed = true; });
  await socketClosed;
  await turn();
  const closedBeforeIngestion = closed;
  ingestion.resolve();
  await closing;
  assert.equal(closedBeforeIngestion, false, 'network.close resolved while relay ingestion still needed the store');
});
