import assert from 'node:assert/strict';
import test from 'node:test';
import { once } from 'node:events';
import http from 'node:http';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { openSqliteStore, schema } from './store.mjs';
import { PGlite } from '@electric-sql/pglite';
import { canonical, decodeCanonical, NS, createSnapshot, timestamp } from './public-information.mjs';
import { WebSocket } from 'ws';
import { MST, MemoryBlockstore, blocksToCarFile, readCar } from '@atproto/repo';
import * as cbor from '@ipld/dag-cbor';
import { CID } from 'multiformats/cid';
import { sha256 } from 'multiformats/hashes/sha2';
import { createServer } from './server.mjs';
import { validateConfig } from './config.mjs';

const did = 'did:plc:abcdefghijklmnopqrstuvwx';
const collection = 'dev.oriom.mosimo.post';
const author = 'a'.repeat(64);
const cidFor = async bytes => CID.createV1(cbor.code, await sha256.digest(bytes));
const event = (index, extra = {}) => ({ id: index.toString(16).padStart(64, '0'), pubkey: author,
  kind: 1, created_at: index, tags: [['t', 'mosimo'], ['p', 'recipient']], content: String(index), sig: 'b'.repeat(128), ...extra });
const frame = seq => Buffer.concat([cbor.encode({ op: 1, t: '#commit' }), cbor.encode({ seq, repo: did })]);

// A deliberately small pg-shaped fake: reject any query not covered by the contract tests.
function fakeStore() {
  const state = { events: [], deletions: [], repos: [], streams: [], calls: [], beforeHistory: null, fail: false };
  state.add = value => state.events.push({ id: value.id, event: value, arrival: String(state.events.length + 1) });
  const visible = row => row.event.kind === 5 || !state.deletions.some(d => d.event_id === row.id && d.pubkey === row.event.pubkey);
  state.query = async (sql, params = []) => {
    state.calls.push({ sql, params });
    if (state.fail) throw new Error('secret database details');
    let rows;
    if (sql.includes('MAX(arrival)')) rows = [{ cursor: String(state.events.length) }];
    else if (sql.includes('e.arrival <= $1')) {
      if (state.beforeHistory) { const callback = state.beforeHistory; state.beforeHistory = null; await callback(); }
      rows = state.events.filter(row => BigInt(row.arrival) <= BigInt(params[0]) && visible(row) && [1, 5].includes(row.event.kind));
      for (const match of sql.matchAll(/e\.(id|pubkey|kind) = ANY\(\$(\d+)/g)) {
        const field = { id: 'id', pubkey: 'pubkey', kind: 'kind' }[match[1]];
        rows = rows.filter(row => params[Number(match[2]) - 1].includes(row.event[field]));
      }
      for (const match of sql.matchAll(/e.created_at (>=|<=) \$(\d+)/g)) {
        rows = rows.filter(row => match[1] === '>=' ? row.event.created_at >= params[Number(match[2]) - 1] : row.event.created_at <= params[Number(match[2]) - 1]);
      }
      for (const match of sql.matchAll(/tag->>0 = \$(\d+) AND tag->>1 = ANY\(\$(\d+)/g)) {
        rows = rows.filter(row => row.event.tags.some(tag => tag[0] === params[Number(match[1]) - 1] && params[Number(match[2]) - 1].includes(tag[1])));
      }
      rows = rows.sort((a, b) => b.event.created_at - a.event.created_at || a.id.localeCompare(b.id)).slice(0, params.at(-1));
    } else if (sql.includes('e.arrival > $1')) {
      rows = state.events.filter(row => BigInt(row.arrival) > BigInt(params[0])).slice(0, params[1]).map(row => ({ ...row, visible: visible(row) }));
    } else if (sql.startsWith('SELECT e.event FROM events e WHERE e.id = $1')) rows = state.events.filter(row => row.id === params[0] && visible(row));
    else if (sql.includes('JOIN repo_blocks')) {
      const available = new Map();
      for (const repo of state.repos.filter(repo => repo.did === params[0])) {
        const { blocks } = await readCar(repo.car);
        for (const { cid, bytes } of blocks.entries()) available.set(cid.toString(), bytes);
      }
      rows = params[1].filter(cid => available.has(cid)).map(cid => ({ cid, bytes: available.get(cid) }));
    }
    else if (sql.includes('MAX(seq)')) {
      rows = [{ cursor: String(Math.max(0, ...state.streams.filter(row => row.group_id === params[0]).map(row => Number(row.seq)))) }];
      state.onHighwater?.();
    }
    else if (sql.includes('FROM streams')) rows = state.streams.filter(row => row.group_id === params[0] && BigInt(row.seq) > BigInt(params[1])).sort((a, b) => Number(BigInt(a.seq) - BigInt(b.seq))).slice(0, params[2]);
    else if (sql.includes('JOIN commits')) rows = state.repos.filter(row => row.group_id === params[0] && row.did === params[1]);
    else if (sql.includes('FROM repos')) rows = state.repos.filter(row => row.group_id === params[0] && row.did > params[1]).sort((a, b) => a.did.localeCompare(b.did)).slice(0, params[2]).map(({ did, head, rev }) => ({ did, head, rev }));
    else throw new Error(`Unexpected query: ${sql}`);
    return { rows };
  };
  return state;
}

async function setup(t, overrides = {}) {
  const store = fakeStore();
  const ingested = [];
  const notifications = [];
  const instance = createServer({ config: { groups: [{ id: 'one', endpoint: 'https://one.test' }, { id: 'two', endpoint: 'https://two.test' }], pollIntervalMs: 5, ...overrides }, store,
    ingest: async value => { ingested.push(value); if (value.content === 'reject') return { accepted: false, message: 'invalid: rejected' }; if (store.events.some(row => row.id === value.id)) return { accepted: true, duplicate: true }; store.add(value); return { accepted: true }; },
    resolveIdentity: async () => ({ did, handle: 'alice.test', didDoc: { id: did } }),
    onNotify: async value => { notifications.push(value); } });
  instance.server.listen(0, '127.0.0.1');
  await once(instance.server, 'listening');
  t.after(() => instance.close());
  const base = `http://127.0.0.1:${instance.server.address().port}`;
  // Node's fetch overrides Host; use http.request to exercise virtual-host routing.
  const request = (path, init = {}) => new Promise((resolve, reject) => {
    const req = http.request(`${base}${path}`, { method: init.method ?? 'GET', headers: { host: 'one.test', ...init.headers } }, res => {
      const chunks = [];
      res.on('data', chunk => chunks.push(chunk));
      res.on('end', () => resolve(new Response(Buffer.concat(chunks), { status: res.statusCode, headers: res.headers })));
      res.on('error', reject);
    });
    req.on('error', reject);
    req.end(init.body);
  });
  const xrpc = (method, params = {}, init) => request(`/xrpc/${method}?${new URLSearchParams(params)}`, init);
  return { ...instance, base, store, ingested, notifications, request, xrpc };
}

async function connect(t, base, path = '/', host = 'one.test') {
  const ws = new WebSocket(`${base.replace('http:', 'ws:')}${path}`, { headers: { host } });
  const queue = [];
  const waiters = [];
  ws.on('message', (data, binary) => {
    const value = binary ? Buffer.from(data) : JSON.parse(data.toString());
    if (waiters.length) waiters.shift()(value); else queue.push(value);
  });
  t.after(() => ws.terminate());
  await once(ws, 'open');
  return { ws, queue, send: value => ws.send(JSON.stringify(value)), next: () => {
    if (queue.length) return Promise.resolve(queue.shift());
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Timed out waiting for WebSocket message')), 2000);
      waiters.push(value => { clearTimeout(timer); resolve(value); });
    });
  } };
}

async function prioritySetup(t, { trusted = true, maxResults = 500, dialect = 'sqlite' } = {}) {
  const fixture = JSON.parse(await readFile(new URL('../apps/android/bundle-core/src/test/resources/wire-v1.json', import.meta.url), 'utf8'));
  let seconds = fixture.event.created_at;
  t.mock.method(Date, 'now', () => seconds * 1000);
  const directory = await mkdtemp(join(tmpdir(), 'mosimo-priority-'));
  let store;
  if (dialect === 'sqlite') store = await openSqliteStore(join(directory, 'test.sqlite'));
  else {
    const db = new PGlite(join(directory, 'postgres'));
    await db.waitReady; await db.exec(schema);
    store = { dialect, query: (sql, values) => db.query(sql, values),
      transaction: fn => db.transaction(tx => fn({ query: (sql, values) => /^SELECT pg_advisory_xact_lock/.test(sql) ? Promise.resolve({ rows: [] }) : tx.query(sql, values) })), close: () => db.close() };
  }
  const config = { maxResults, publicInformation: { roots: trusted ? fixture.config.roots : [],
    selection: fixture.config.selected.map(e => ({ botDid: e.botDid, types: [e.type] })) } };
  const app = createServer({ config, store, ingest: async () => ({ accepted: true }) });
  app.server.listen(0, '127.0.0.1');
  await once(app.server, 'listening');
  t.after(async () => { await app.close(); await store.close(); await rm(directory, { recursive: true, force: true }); });
  const fetch = t.mock.method(globalThis, 'fetch', async url => {
    assert.equal(url, fixture.config.roots[0].snapshotUrl);
    return new Response(Buffer.from(fixture.snapshotHex, 'hex'), { headers: { 'content-type': 'application/cbor' } });
  });
  const insert = value => store.query('INSERT INTO events(id,pubkey,created_at,kind,event) VALUES($1,$2,$3,$4,$5)',
    [value.id, value.pubkey, value.created_at, value.kind, JSON.stringify(value)]);
  const socket = await connect(t, `http://127.0.0.1:${app.server.address().port}`);
  const request = (id, limit = 1, extra = {}) => socket.send(['MOSIMO-REQ', id, { order: 'priority-v1',
    filter: { kinds: [1], '#t': ['mosimo.oriom.dev'], limit, ...extra } }]);
  return { fixture, store, socket, insert, request, fetch, setNow: value => { seconds = value; } };
}

for (const dialect of ['sqlite', 'postgres']) test(`${dialect}: MOSIMO-REQ sorts before limit; REQ stays chronological and live`, async t => {
  const { fixture, insert, socket, request } = await prioritySetup(t, { dialect });
  await insert(fixture.event);
  // More than one newest result separates the older authorized event from limit=1.
  for (let i = 1; i <= 12; i++) await insert(event(i, { created_at: fixture.event.created_at + i,
    tags: [['t', 'mosimo.oriom.dev'], [`${NS}:ext:dev.oriom.mosimo.defs#priority`, Buffer.from(canonical({ version: 1, level: 3 })).toString('base64')]] }));
  request('priority');
  assert.equal((await socket.next())[2].id, fixture.event.id);
  assert.deepEqual(await socket.next(), ['EOSE', 'priority']);
  socket.send(['REQ', 'standard', { kinds: [1], '#t': ['mosimo.oriom.dev'], limit: 1, order: 'priority-v1' }]);
  assert.equal((await socket.next())[2].id, event(12).id);
  assert.deepEqual(await socket.next(), ['EOSE', 'standard']);
  request('standard');
  assert.equal((await socket.next())[0], 'CLOSED');
  socket.send(['CLOSE', 'standard']);
  request('priority', 2, { since: fixture.event.created_at + 1, until: fixture.event.created_at + 2 });
  assert.equal((await socket.next())[2].id, event(2).id);
  assert.equal((await socket.next())[2].id, event(1).id);
  assert.deepEqual(await socket.next(), ['EOSE', 'priority']);
});

for (const change of ['expiry', 'final-pass expiry', 'serial conflict', 'revocation']) test(`priority final gate rejects ${change} discovered after initial authorization`, async t => {
  const { fixture, store, socket, insert, request, setNow } = await prioritySetup(t);
  await insert(fixture.event);
  const transaction = store.transaction.bind(store);
  let changed = false;
  let serialReads = 0;
  t.mock.method(store, 'transaction', async fn => {
    let recorded = false;
    const result = await transaction(tx => fn({ query: async (sql, values) => {
      const value = await tx.query(sql, values);
      if (sql.includes('AS conflicted FROM trust_serials')) {
        serialReads++;
        if (change === 'final-pass expiry' && serialReads === 2) recorded = true;
      }
      if (sql.startsWith('INSERT INTO trust_serials') && change !== 'final-pass expiry') recorded = true;
      return value;
    } }));
    if (recorded && !changed) {
      changed = true;
      if (change.includes('expiry')) setNow(Date.parse(fixture.event.tags.find(tag => tag[0] === `${NS}:delivery-expires-at`)[1]) / 1000);
      else if (change === 'serial conflict') await store.query('INSERT INTO trust_serial_conflicts(issuer,serial) SELECT issuer,serial FROM trust_serials');
      else {
        const snapshot = createSnapshot('01'.padStart(64, '0'), { sequence: 8, issuedAt: timestamp(fixture.event.created_at),
          validUntil: timestamp(fixture.event.created_at + 600), revokedPubkeys: [fixture.config.roots[0].rootPubkey] });
        await store.query('UPDATE trust_snapshots SET snapshot=$1', [JSON.stringify(snapshot)]);
      }
    }
    return result;
  });
  request('changed');
  assert.equal((await socket.next())[0], 'CLOSED', 'no EVENT may escape with stale elevated ranking');
  assert.equal(changed, true);
});

test('empty trust never elevates self priority, authorization failures cap at one and ties use ID', async t => {
  const { fixture, insert, socket, request, fetch } = await prioritySetup(t, { trusted: false });
  await insert(fixture.event);
  const newer = event(1, { created_at: fixture.event.created_at + 1, tags: [['t', 'mosimo.oriom.dev']] });
  await insert(newer);
  await insert({ ...newer, id: event(2).id });
  request('empty', 3);
  assert.equal((await socket.next())[2].id, event(1).id);
  assert.equal((await socket.next())[2].id, event(2).id);
  assert.equal((await socket.next())[2].id, fixture.event.id);
  assert.deepEqual(await socket.next(), ['EOSE', 'empty']);
  assert.equal(fetch.mock.callCount(), 0);
});

test('custom request rejects invalid filters, limits and IDs with bounded CLOSED', async t => {
  const { socket, request } = await prioritySetup(t, { maxResults: 2 });
  for (const [id, limit, extra] of [['large', 3, {}], ['zero', 0, {}], ['kind', 1, { kinds: [1, 5] }],
    ['tag', 1, { '#t': ['other'] }], ['unknown', 1, { order: 'priority-v1' }], ['bad\nID', 1, {}]]) {
    request(id, limit, extra);
    const reply = await socket.next();
    assert.equal(reply[0], 'CLOSED'); assert.ok(JSON.stringify(reply).length < 256);
  }
  socket.send(['MOSIMO-REQ', 'bad', { order: 'priority-v1', filter: {}, extra: true }]);
  assert.equal((await socket.next())[0], 'CLOSED');
});

test('CLOSE can cancel a finite request queued in the same WebSocket batch', async t => {
  const { socket, request } = await prioritySetup(t, { trusted: false });
  request('queued');
  socket.send(['CLOSE', 'queued']);
  socket.send(['REQ', 'barrier', { limit: 0 }]);
  assert.deepEqual(await socket.next(), ['EOSE', 'barrier']);
});

test('finite request CLOSE cancels pending snapshot work and releases its ID without live delivery', async t => {
  const { fixture, insert, socket, request, fetch } = await prioritySetup(t);
  await insert(fixture.event);
  const started = Promise.withResolvers();
  const unblock = Promise.withResolvers();
  t.after(() => unblock.resolve());
  fetch.mock.mockImplementation(async () => {
    started.resolve(); await unblock.promise;
    return new Response(Buffer.from(fixture.snapshotHex, 'hex'), { headers: { 'content-type': 'application/cbor' } });
  });
  request('cancel');
  await started.promise;
  socket.send(['CLOSE', 'cancel']);

  await new Promise(resolve => setTimeout(resolve, 30));
  unblock.resolve();
  socket.send(['REQ', 'barrier', { limit: 0 }]);
  assert.deepEqual(await socket.next(), ['EOSE', 'barrier']);
  socket.send(['CLOSE', 'barrier']);
  request('cancel');
  assert.equal((await socket.next())[0], 'EVENT');
  assert.deepEqual(await socket.next(), ['EOSE', 'cancel']);
  await insert(event(99, { tags: [['t', 'mosimo.oriom.dev']] }));
  socket.send(['REQ', 'last', { limit: 0 }]);
  assert.deepEqual(await socket.next(), ['EOSE', 'last']);
});

test('candidate bytes are budgeted in SQL before materializing event JSON', async t => {
  const { insert, socket, request } = await prioritySetup(t, { trusted: false });
  const content = 'x'.repeat(131000);
  for (let i = 1; i <= 65; i++) await insert(event(i, { content, tags: [['t', 'mosimo.oriom.dev']] }));
  request('bytes');
  assert.equal((await socket.next())[0], 'CLOSED');
});

test('oversized candidate requests cannot starve due snapshot revocation refresh', async t => {
  const { fixture, insert, socket, request, fetch, store } = await prioritySetup(t);
  const snapshot = createSnapshot('01'.padStart(64, '0'), { sequence: 8, issuedAt: timestamp(fixture.event.created_at),
    validUntil: timestamp(fixture.event.created_at + 600), revokedPubkeys: [fixture.config.roots[0].rootPubkey] });
  fetch.mock.mockImplementation(async () => new Response(canonical(snapshot), { headers: { 'content-type': 'application/cbor' } }));
  for (let i = 1; i <= 1001; i++) await insert(event(i, { tags: [['t', 'mosimo.oriom.dev']] }));
  request('refresh-overflow');
  assert.equal((await socket.next())[0], 'CLOSED');
  assert.equal(fetch.mock.callCount(), 1);
  assert.deepEqual(JSON.parse((await store.query('SELECT snapshot FROM trust_snapshots')).rows[0].snapshot), snapshot);
});

test('candidate budget overflow is CLOSED, never a truncated success', async t => {
  const { insert, socket, request } = await prioritySetup(t, { trusted: false });
  for (let i = 1; i <= 1001; i++) await insert(event(i, { tags: [['t', 'mosimo.oriom.dev']] }));
  request('overflow');
  assert.equal((await socket.next())[0], 'CLOSED');
});

async function repositoryFixture() {
  const record = { $type: collection, text: 'hello', bytes: Uint8Array.of(251, 255), link: await cidFor(cbor.encode({ link: true })) };
  const bytes = cbor.encode(record);
  const cid = await cidFor(bytes);
  const key = `${collection}/3jzfcijpj2z2a`;
  const tree = await (await MST.create(new MemoryBlockstore())).add(key, cid);
  const { blocks } = await tree.getUnstoredBlocks();
  blocks.set(cid, bytes);
  const commitBytes = cbor.encode({ did, version: 3, data: await tree.getPointer(), prev: null, rev: '3jzfcijpj2z2a', sig: new Uint8Array(64) });
  const head = await cidFor(commitBytes);
  blocks.set(head, commitBytes);
  return { group_id: 'one', did, head: head.toString(), rev: '3jzfcijpj2z2a', entries: [{ key, value: cid.toString() }], car: await blocksToCarFile(head, blocks) };
}

test('health, NIP-11 capabilities, HTTP errors and method handling', async t => {
  const app = await setup(t);
  assert.deepEqual(await (await app.request('/health')).json(), { ok: true });
  const info = await app.request('/', { headers: { accept: 'application/nostr+json' } });
  assert.equal(info.headers.get('content-type'), 'application/nostr+json');
  const capabilities = await info.json();
  assert.deepEqual(capabilities.supported_nips, [1, 9, 11]);
  assert.equal(capabilities.mosimo.wire, 1);
  assert.deepEqual(capabilities.mosimo.priority_order, ['priority-v1']);
  assert.equal((await app.request('/unknown')).status, 404);
  assert.equal((await app.xrpc('com.atproto.sync.listRepos', {}, { method: 'POST' })).status, 405);
  assert.equal((await app.xrpc('com.atproto.sync.listRepos', {}, { headers: { host: 'missing.test' } })).status, 404);
  assert.equal((await app.xrpc('com.atproto.sync.listRepos', { limit: '-1' })).status, 400);
  app.store.fail = true;
  const failed = await app.xrpc('com.atproto.sync.listRepos');
  assert.equal(failed.status, 500);
  assert.equal((await failed.json()).message, 'Internal server error');
});

test('Relay discovery publishes matching per-host service DIDs without account or signing claims', async t => {
  const config = validateConfig({ groups: [
    { id: 'one', endpoint: 'https://ONE.test:443/' },
    { id: 'two', endpoint: 'https://two.test:8443/' },
  ] });
  const app = await setup(t, config);
  for (const [host, serviceDid, endpoint] of [
    ['one.test', 'did:web:one.test', 'https://one.test'],
    ['two.test:8443', 'did:web:two.test%3A8443', 'https://two.test:8443'],
  ]) {
    const headers = { host };
    const description = await app.request('/xrpc/com.atproto.server.describeServer', { headers });
    assert.equal(description.status, 200);
    assert.match(description.headers.get('content-type'), /^application\/json/);
    assert.deepEqual(await description.json(), { did: serviceDid, availableUserDomains: [] });
    const document = await app.request('/.well-known/did.json', { headers });
    assert.equal(document.status, 200);
    assert.deepEqual(await document.json(), {
      '@context': ['https://www.w3.org/ns/did/v1'], id: serviceDid,
      service: [{ id: `${serviceDid}#atproto_pds`, type: 'AtprotoPersonalDataServer', serviceEndpoint: endpoint }],
    });
    assert.notEqual(serviceDid, did);
  }
  for (const path of ['/xrpc/com.atproto.server.describeServer', '/.well-known/did.json']) {
    for (const host of ['missing.test', 'two.test', 'one.test:8443']) {
      const response = await app.request(path, { headers: { host } });
      assert.equal(response.status, 404);
      assert.equal((await response.json()).error, 'UnknownGroup');
    }
    for (const method of ['POST', 'PUT', 'DELETE']) {
      assert.equal((await app.request(path, { method })).status, 405);
    }
  }
  for (const method of ['com.atproto.server.createAccount', 'com.atproto.server.createSession', 'com.atproto.repo.uploadBlob']) {
    assert.equal((await app.xrpc(method)).status, 404);
    assert.equal((await app.xrpc(method, {}, { method: 'POST' })).status, 405);
  }
  assert.equal(app.store.calls.length, 0);
});

test('NIP-01 history filters use exact IDs/authors, AND tags, OR filters, order and bounds', async t => {
  const app = await setup(t, { maxResults: 3 });
  for (let i = 1; i <= 6; i++) app.store.add(event(i, i === 5 ? { tags: [['t', 'other']] } : {}));
  const client = await connect(t, app.base);
  client.send(['REQ', 'filtered', { authors: [author], kinds: [1], since: 2, until: 5, '#t': ['mosimo'], '#p': ['recipient'], limit: 2 }, { ids: [event(1).id] }]);
  assert.equal((await client.next())[2].id, event(4).id);
  assert.equal((await client.next())[2].id, event(3).id);
  assert.equal((await client.next())[2].id, event(1).id);
  assert.deepEqual(await client.next(), ['EOSE', 'filtered']);
  client.send(['REQ', 'exact', { ids: [event(2).id] }]);
  assert.equal((await client.next())[2].id, event(2).id);
  assert.deepEqual(await client.next(), ['EOSE', 'exact']);
  client.send(['REQ', 'prefix', { authors: [author.slice(0, 10)] }]);
  assert.equal((await client.next())[0], 'NOTICE');
  client.send(['REQ', 'empty', { ids: [] }]);
  assert.deepEqual(await client.next(), ['EOSE', 'empty']);
});

test('history/live boundary has no gap or duplicates, and limit only bounds history', async t => {
  const app = await setup(t, { maxResults: 2 });
  app.store.add(event(1));
  app.store.beforeHistory = async () => { app.store.add(event(2)); app.store.add(event(3)); };
  const client = await connect(t, app.base);
  client.send(['REQ', 'race', { kinds: [1], limit: 1 }, { ids: [event(1).id], limit: 1 }]);
  assert.deepEqual(await client.next(), ['EVENT', 'race', event(1)]);
  assert.deepEqual(await client.next(), ['EOSE', 'race']);
  assert.deepEqual(await client.next(), ['EVENT', 'race', event(2)]);
  assert.deepEqual(await client.next(), ['EVENT', 'race', event(3)]);
  const delayed = event(4, { created_at: 0 });
  app.store.add(delayed);
  assert.deepEqual(await client.next(), ['EVENT', 'race', delayed]);
  assert.equal(client.queue.length, 0);
});

test('NIP-09 author/event tombstones hide history and live, not another author', async t => {
  const app = await setup(t);
  app.store.add(event(1));
  app.store.add(event(2));
  app.store.deletions.push({ event_id: event(1).id, pubkey: author }, { event_id: event(2).id, pubkey: 'f'.repeat(64) });
  const client = await connect(t, app.base);
  client.send(['REQ', 'deletions', {}]);
  assert.deepEqual(await client.next(), ['EVENT', 'deletions', event(2)]);
  assert.deepEqual(await client.next(), ['EOSE', 'deletions']);
  app.store.deletions.push({ event_id: event(3).id, pubkey: author });
  app.store.add(event(3));
  const deletion = event(4, { kind: 5, tags: [['e', event(1).id]] });
  app.store.add(deletion);
  assert.deepEqual(await client.next(), ['EVENT', 'deletions', deletion]);
});

test('EVENT delegates verification, returns OK/rejection/duplicate, and supports kind 5', async t => {
  const app = await setup(t);
  const client = await connect(t, app.base);
  client.send(['EVENT', event(1)]);
  assert.deepEqual(await client.next(), ['OK', event(1).id, true, '']);
  client.send(['EVENT', event(1)]);
  assert.deepEqual(await client.next(), ['OK', event(1).id, true, 'duplicate: already stored']);
  client.send(['EVENT', event(2, { content: 'reject' })]);
  assert.deepEqual(await client.next(), ['OK', event(2).id, false, 'invalid: rejected']);
  client.send(['EVENT', event(3, { kind: 5 })]);
  assert.equal((await client.next())[2], true);
  client.send(['EVENT', event(4, { kind: 7 })]);
  assert.equal((await client.next())[2], false);
  assert.equal(app.ingested.length, 4);
});

test('subscription cap, replacement, CLOSE and invalid messages', async t => {
  const app = await setup(t, { maxSubscriptions: 1 });
  const client = await connect(t, app.base);
  client.ws.send('not json');
  // Malformed JSON is a protocol error, not a database or internal server error.
  assert.equal((await client.next())[0], 'NOTICE');
  client.send(['REQ', 'a', {}]);
  assert.deepEqual(await client.next(), ['EOSE', 'a']);
  client.send(['REQ', 'b', {}]);
  assert.equal((await client.next())[0], 'CLOSED');
  client.send(['REQ', 'a', { kinds: [5] }]);
  assert.deepEqual(await client.next(), ['EOSE', 'a']);
  client.send(['CLOSE', 'a']);
  client.send(['REQ', 'b', { limit: 0 }]);
  assert.deepEqual(await client.next(), ['EOSE', 'b']);
  client.send(['REQ', 'b', { until: -1 }]);
  assert.equal((await client.next())[0], 'NOTICE');
});

test('persisted subscribeRepos replay and live frames are byte-exact and group-isolated', async t => {
  const app = await setup(t, { maxResults: 1 });
  app.store.streams.push({ group_id: 'one', seq: '1', frame: frame(1) }, { group_id: 'one', seq: '2', frame: frame(2) }, { group_id: 'two', seq: '3', frame: frame(99) });
  const replay = await connect(t, app.base, '/xrpc/com.atproto.sync.subscribeRepos?cursor=0');
  assert.deepEqual(await replay.next(), frame(1));
  assert.deepEqual(await replay.next(), frame(2));
  const live = await connect(t, app.base, '/xrpc/com.atproto.sync.subscribeRepos');
  assert.deepEqual(await live.next(), frame(1));
  assert.deepEqual(await live.next(), frame(2));
  app.store.streams.push({ group_id: 'one', seq: '3', frame: frame(3) });
  assert.deepEqual(await live.next(), frame(3));
  assert.deepEqual(await replay.next(), frame(3));
});

test('cold subscribeRepos replays persisted identity, account and commits in order across bounded pages', async t => {
  const app = await setup(t, { maxResults: 1 });
  const persisted = [
    ['#identity', { seq: 1, did, handle: 'alice.test', time: '2026-10-18T00:00:00Z' }],
    ['#account', { seq: 3, did, active: true, time: '2026-10-18T00:00:01Z' }],
    ['#commit', { seq: 5, repo: did }],
    ['#commit', { seq: 7, repo: did }],
  ].map(([type, payload]) => ({ group_id: 'one', seq: String(payload.seq),
    frame: Buffer.concat([cbor.encode({ op: 1, t: type }), cbor.encode(payload)]) }));
  app.store.streams.push(...persisted, { group_id: 'two', seq: '2', frame: frame(99) });
  const before = structuredClone(app.store.streams);
  const cold = await connect(t, app.base, '/xrpc/com.atproto.sync.subscribeRepos');
  for (const row of persisted) assert.deepEqual(await cold.next(), row.frame);
  const resumed = await connect(t, app.base, '/xrpc/com.atproto.sync.subscribeRepos?cursor=3');
  for (const row of persisted.slice(2)) assert.deepEqual(await resumed.next(), row.frame);
  const other = await connect(t, app.base, '/xrpc/com.atproto.sync.subscribeRepos', 'two.test');
  assert.deepEqual(await other.next(), frame(99));
  app.store.streams.push({ group_id: 'one', seq: '9', frame: frame(9) });
  assert.deepEqual(await cold.next(), frame(9));
  assert.deepEqual(await resumed.next(), frame(9));
  assert.deepEqual(structuredClone(app.store.streams.slice(0, -1)), before);
  assert.ok(app.store.calls.filter(call => call.sql.includes('FROM streams') && !call.sql.includes('MAX(seq)'))
    .every(call => call.params[2] === 1 && call.sql.includes('seq > $2')));
});

test('subscribeRepos rejects future and malformed cursors using standard error frames', async t => {
  const app = await setup(t);
  for (const [cursor, error] of [['1', 'FutureCursor'], ['-1', 'InvalidRequest'], ['abc', 'InvalidRequest']]) {
    const client = await connect(t, app.base, `/xrpc/com.atproto.sync.subscribeRepos?cursor=${cursor}`);
    const closed = once(client.ws, 'close');
    assert.deepEqual(await client.next(), Buffer.concat([cbor.encode({ op: -1 }), cbor.encode({ error, message: error })]));
    assert.equal((await closed)[0], 1008);
  }
});

test('oversized stream frame emits ConsumerTooSlow and closes without an unbounded queue', async t => {
  const app = await setup(t, { maxBufferedBytes: 256 });
  app.store.streams.push({ group_id: 'one', seq: '1', frame: Buffer.alloc(1024) });
  const client = await connect(t, app.base, '/xrpc/com.atproto.sync.subscribeRepos?cursor=0');
  const closed = once(client.ws, 'close');
  assert.deepEqual(await client.next(), Buffer.concat([cbor.encode({ op: -1 }), cbor.encode({ error: 'ConsumerTooSlow', message: 'ConsumerTooSlow' })]));
  assert.equal((await closed)[0], 1008);
});

test('repository sync routes return CARs, head, status and bounded listRepos', async t => {
  const app = await setup(t);
  const repo = await repositoryFixture();
  app.store.repos.push(repo);
  for (const method of ['getRepo', 'getCheckout', 'getRecord']) {
    const response = await app.xrpc(`com.atproto.sync.${method}`, { did, collection, rkey: '3jzfcijpj2z2a' });
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('content-type'), 'application/vnd.ipld.car');
    assert.deepEqual(Buffer.from(await response.arrayBuffer()), Buffer.from(repo.car));
  }
  assert.deepEqual(await (await app.xrpc('com.atproto.sync.getHead', { did })).json(), { root: repo.head });
  assert.deepEqual(await (await app.xrpc('com.atproto.sync.getLatestCommit', { did })).json(), { cid: repo.head, rev: repo.rev });
  assert.deepEqual(await (await app.xrpc('com.atproto.sync.getRepoStatus', { did })).json(), { did, active: true, rev: repo.rev });
  const listed = await (await app.xrpc('com.atproto.sync.listRepos', { limit: 1 })).json();
  assert.equal(listed.repos[0].did, did);
  assert.equal(listed.cursor, did);
  assert.deepEqual(await (await app.xrpc('com.atproto.sync.listRepos', { cursor: did })).json(), { repos: [] });
  assert.equal((await app.xrpc('com.atproto.sync.getRepo', { did }, { headers: { host: 'two.test' } })).status, 404);
});

test('getBlocks allows current repository blocks only, never cross-group global blocks', async t => {
  const app = await setup(t);
  const repo = await repositoryFixture();
  app.store.repos.push(repo);
  const requested = repo.entries[0].value;
  const response = await app.xrpc('com.atproto.sync.getBlocks', { did, cids: requested });
  const parsed = await readCar(new Uint8Array(await response.arrayBuffer()));
  assert.equal(parsed.blocks.size, 1);
  assert.ok(parsed.blocks.has(CID.parse(requested)));
  const foreign = await cidFor(cbor.encode({ private: 'other group' }));
  assert.equal((await app.xrpc('com.atproto.sync.getBlocks', { did, cids: foreign.toString() })).status, 404);
  assert.equal((await app.xrpc('com.atproto.sync.getBlocks', { did, cids: 'bad' })).status, 400);
  assert.equal((await app.xrpc('com.atproto.sync.getBlocks', { did })).status, 400);
});

test('repo JSON routes encode CID/bytes, describe identity, paginate and report missing records/blobs', async t => {
  const app = await setup(t);
  const repo = await repositoryFixture();
  app.store.repos.push(repo);
  const result = await (await app.xrpc('com.atproto.repo.getRecord', { repo: 'alice.test', collection, rkey: '3jzfcijpj2z2a' })).json();
  assert.equal(result.uri, `at://${did}/${repo.entries[0].key}`);
  assert.equal(result.cid, repo.entries[0].value);
  assert.deepEqual(result.value.bytes, { $bytes: '+/8' });
  assert.equal(typeof result.value.link.$link, 'string');
  const description = await (await app.xrpc('com.atproto.repo.describeRepo', { repo: did })).json();
  assert.equal(description.didDoc.id, did);
  assert.deepEqual(description.collections, [collection]);
  const records = await (await app.xrpc('com.atproto.repo.listRecords', { repo: did, collection, limit: 1 })).json();
  assert.deepEqual(records.records, [result]);
  assert.equal(records.cursor, repo.entries[0].key);
  assert.deepEqual((await (await app.xrpc('com.atproto.repo.listRecords', { repo: did, collection, cursor: records.cursor })).json()).records, []);
  assert.equal((await app.xrpc('com.atproto.repo.getRecord', { repo: did, collection, rkey: 'missing' })).status, 404);
  const blob = await app.xrpc('com.atproto.sync.getBlob', { did, cid: repo.head });
  assert.equal(blob.status, 404);
  assert.equal((await blob.json()).error, 'BlobNotFound');
  assert.deepEqual(await (await app.xrpc('com.atproto.sync.listBlobs', { did })).json(), { cids: [] });
});

test('internal endpoints require bearer auth and notify accepts only eventId, never URLs', async t => {
  const previous = process.env.PEER_TOKEN;
  process.env.PEER_TOKEN = 'test-peer-secret';
  t.after(() => { if (previous === undefined) delete process.env.PEER_TOKEN; else process.env.PEER_TOKEN = previous; });
  const app = await setup(t, { maxEventBytes: 256 });
  app.store.add(event(1));
  const path = `/internal/events/${event(1).id}`;
  assert.equal((await app.request(path)).status, 401);
  assert.equal((await app.request(path, { headers: { authorization: 'Bearer wrong' } })).status, 401);
  const headers = { authorization: 'Bearer test-peer-secret' };
  assert.deepEqual(await (await app.request(path, { headers })).json(), event(1));
  assert.equal((await app.request(`/internal/events/${event(2).id}`, { headers })).status, 404);
  const notify = value => app.request('/internal/notify', { method: 'POST', headers, body: JSON.stringify(value) });
  assert.equal((await notify({ eventId: event(1).id })).status, 200);
  assert.deepEqual(app.notifications, [{ eventId: event(1).id }]);
  assert.equal((await notify({ eventId: event(1).id, url: 'http://untrusted.invalid' })).status, 400);
  assert.equal((await notify({ eventId: 'bad' })).status, 400);
  assert.equal((await notify({ eventId: 'x'.repeat(300) })).status, 413);
  delete process.env.PEER_TOKEN;
  assert.equal((await app.request(path, { headers })).status, 401);
});

test('binary NIP-01 messages are rejected and payload limits close oversized senders', async t => {
  const app = await setup(t, { maxEventBytes: 256 });
  const client = await connect(t, app.base);
  client.ws.send(Buffer.from('["REQ","binary",{}]'));
  assert.equal((await client.next())[0], 'NOTICE');
  const closed = once(client.ws, 'close');
  client.ws.send('x'.repeat(257));
  assert.equal((await closed)[0], 1009);
  assert.equal(app.ingested.length, 0);
});

test('live filtering applies every field and ORs filters without duplicate deliveries', async t => {
  const app = await setup(t);
  const client = await connect(t, app.base);
  client.send(['REQ', 'live', { authors: [author], kinds: [1], since: 2, until: 8, '#t': ['mosimo'], '#p': ['recipient'] }, { ids: [event(8).id] }]);
  assert.deepEqual(await client.next(), ['EOSE', 'live']);
  app.store.add(event(1));
  app.store.add(event(2, { pubkey: 'f'.repeat(64) }));
  app.store.add(event(3, { kind: 5 }));
  app.store.add(event(4, { tags: [['t', 'other'], ['p', 'recipient']] }));
  app.store.add(event(5, { tags: [['t', 'mosimo']] }));
  app.store.add(event(9));
  app.store.add(event(7));
  app.store.add(event(8));
  assert.deepEqual(await client.next(), ['EVENT', 'live', event(7)]);
  assert.deepEqual(await client.next(), ['EVENT', 'live', event(8)]);
  // A later matching event also proves that the OR match wasn't emitted twice.
  const last = event(10, { created_at: 8 });
  app.store.add(last);
  assert.deepEqual(await client.next(), ['EVENT', 'live', last]);
});

test('close is asynchronous, idempotent and shuts down open websockets', async t => {
  const app = await setup(t);
  const client = await connect(t, app.base);
  const closed = once(client.ws, 'close');
  const first = app.close();
  assert.equal(first, app.close());
  await first;
  await closed;
  assert.equal(app.server.listening, false);
});
