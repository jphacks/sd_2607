import assert from 'node:assert/strict';
import test from 'node:test';
import { once } from 'node:events';
import http from 'node:http';
import { WebSocket } from 'ws';
import { MST, MemoryBlockstore, blocksToCarFile, readCar } from '@atproto/repo';
import * as cbor from '@ipld/dag-cbor';
import { CID } from 'multiformats/cid';
import { sha256 } from 'multiformats/hashes/sha2';
import { createServer } from './server.mjs';

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
  const instance = createServer({ config: { groups: [{ id: 'one', endpoint: 'http://one.test' }, { id: 'two', endpoint: 'http://two.test' }], pollIntervalMs: 5, ...overrides }, store,
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
  assert.deepEqual((await info.json()).supported_nips, [1, 9, 11]);
  assert.equal((await app.request('/unknown')).status, 404);
  assert.equal((await app.xrpc('com.atproto.sync.listRepos', {}, { method: 'POST' })).status, 405);
  assert.equal((await app.xrpc('com.atproto.sync.listRepos', {}, { headers: { host: 'missing.test' } })).status, 404);
  assert.equal((await app.xrpc('com.atproto.sync.listRepos', { limit: '-1' })).status, 400);
  app.store.fail = true;
  const failed = await app.xrpc('com.atproto.sync.listRepos');
  assert.equal(failed.status, 500);
  assert.equal((await failed.json()).message, 'Internal server error');
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
  // Wait for the no-cursor snapshot, not an arbitrary timer, before publishing.
  const snapshot = new Promise(resolve => { app.store.onHighwater = resolve; });
  const live = await connect(t, app.base, '/xrpc/com.atproto.sync.subscribeRepos');
  await snapshot;
  app.store.streams.push({ group_id: 'one', seq: '3', frame: frame(3) });
  assert.deepEqual(await live.next(), frame(3));
  assert.deepEqual(await replay.next(), frame(3));
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
