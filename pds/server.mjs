import http from 'node:http';
import { createHash, timingSafeEqual } from 'node:crypto';
import { WebSocketServer, WebSocket } from 'ws';
import { BlockMap, blocksToCarFile, readCar } from '@atproto/repo';
import * as cbor from '@ipld/dag-cbor';
import { CID } from 'multiformats/cid';

const defaults = { maxEventBytes: 131072, maxSubscriptions: 20, maxResults: 500, maxBufferedBytes: 4194304 };
const hex = /^[0-9a-f]{64}$/;
const visible = '(e.kind = 5 OR NOT EXISTS (SELECT 1 FROM deletions d WHERE d.event_id = e.id AND d.pubkey = e.pubkey))';
const isObject = value => value !== null && typeof value === 'object' && !Array.isArray(value);

class RequestError extends Error {
  constructor(error, message = error, status = 400) {
    super(message);
    this.error = error;
    this.status = status;
  }
}
const invalid = message => new RequestError('InvalidRequest', message);
const notFound = (error = 'RepoNotFound') => new RequestError(error, error, 404);

function filterValue(value, max) {
  if (!isObject(value)) throw invalid('Filter must be an object');
  for (const [key, values] of Object.entries(value)) {
    if (['ids', 'authors', 'kinds'].includes(key) || /^#[a-zA-Z]$/.test(key)) {
      if (!Array.isArray(values) || values.length > max || !values.every(item =>
        key === 'kinds' ? Number.isSafeInteger(item) && item >= 0 && item <= 65535
          : typeof item === 'string' && (!['ids', 'authors'].includes(key) || hex.test(item)))) {
        throw invalid(`Invalid ${key} filter`);
      }
    } else if (['since', 'until', 'limit'].includes(key)
      && (!Number.isSafeInteger(values) || values < 0)) throw invalid(`Invalid ${key}`);
  }
  return value;
}

function matches(event, filter) {
  return Object.entries(filter).every(([key, values]) => {
    if (key === 'ids') return values.includes(event.id);
    if (key === 'authors') return values.includes(event.pubkey);
    if (key === 'kinds') return values.includes(event.kind);
    if (key === 'since') return event.created_at >= values;
    if (key === 'until') return event.created_at <= values;
    if (/^#[a-zA-Z]$/.test(key)) return event.tags.some(tag => tag[0] === key[1] && values.includes(tag[1]));
    return true;
  });
}

function filterSql(filter, params, sqlite) {
  const bind = value => { params.push(value); return `$${params.length}`; };
  const parts = [];
  for (const [key, value] of Object.entries(filter)) {
    if (['ids', 'authors', 'kinds'].includes(key)) {
      const column = { ids: 'id', authors: 'pubkey', kinds: 'kind' }[key];
      parts.push(sqlite ? `e.${column} IN (SELECT value FROM json_each(${bind(JSON.stringify(value))}))`
        : `e.${column} = ANY(${bind(value)}::${key === 'kinds' ? 'int' : 'text'}[])`);
    }
    if (key === 'since') parts.push(`e.created_at >= ${bind(value)}`);
    if (key === 'until') parts.push(`e.created_at <= ${bind(value)}`);
    if (/^#[a-zA-Z]$/.test(key)) parts.push(sqlite
      ? `EXISTS (SELECT 1 FROM json_each(e.event, '$.tags') AS t WHERE json_extract(t.value, '$[0]') = ${bind(key[1])} AND json_extract(t.value, '$[1]') IN (SELECT value FROM json_each(${bind(JSON.stringify(value))})))`
      : `EXISTS (SELECT 1 FROM jsonb_array_elements(e.event->'tags') AS t(tag) WHERE tag->>0 = ${bind(key[1])} AND tag->>1 = ANY(${bind(value)}::text[]))`);
  }
  return parts.length ? parts.join(' AND ') : 'TRUE';
}

function lexJson(value) {
  if (CID.asCID(value)) return { $link: value.toString() };
  if (value instanceof Uint8Array) return { $bytes: Buffer.from(value).toString('base64').replace(/=+$/, '') };
  if (Array.isArray(value)) return value.map(lexJson);
  if (isObject(value)) return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, lexJson(item)]));
  return value;
}

/**
 * store.query(sql, params) returns a pg-compatible { rows } result.
 * ingest(event) validates/persists the event and queues repository processing.
 * resolveIdentity(repo) returns { did, handle?, didDoc? } (or a DID string).
 * onNotify({ eventId }) performs trusted-peer retrieval; no request URL is accepted.
 * events.arrival must be committed in insertion order by the ingest transaction.
 */
export function createServer({ config, store, ingest, resolveIdentity, onNotify }) {
  const options = { ...defaults, ...config };
  const sqlite = store.dialect === 'sqlite';
  for (const name of Object.keys(defaults)) {
    if (!Number.isSafeInteger(options[name]) || options[name] < 1) throw new TypeError(`Invalid ${name}`);
  }
  const groups = new Map((options.groups ?? []).map(group => {
    const endpoint = new URL(group.endpoint);
    return [endpoint.host.toLowerCase(), { ...group, endpoint: endpoint.origin,
      serviceDid: `did:web:${encodeURIComponent(endpoint.host)}` }];
  }));
  const query = async (sql, params = []) => (await store.query(sql, params)).rows;
  const connections = new Set();
  let closing = false;
  const groupFor = req => {
    const group = groups.get((req.headers.host ?? '').toLowerCase());
    if (!group) throw notFound('UnknownGroup');
    return group;
  };
  const json = (res, status, value) => {
    res.writeHead(status, { 'content-type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify(value));
  };
  const carResponse = (res, bytes) => {
    res.writeHead(200, { 'content-type': 'application/vnd.ipld.car' });
    res.end(Buffer.from(bytes));
  };
  const parameter = (url, key) => {
    const value = url.searchParams.get(key);
    if (!value || url.searchParams.getAll(key).length !== 1) throw invalid(`Expected ${key}`);
    return value;
  };
  const limitFor = url => {
    const text = url.searchParams.get('limit');
    if (text !== null && (!/^[1-9]\d*$/.test(text) || !Number.isSafeInteger(Number(text)))) throw invalid('Invalid limit');
    return Math.min(Number(text ?? options.maxResults), options.maxResults);
  };
  const parseCid = value => {
    try { return CID.parse(value); } catch { throw invalid('Invalid CID'); }
  };
  const identityFor = async repo => {
    if (repo.startsWith('did:')) return { did: repo };
    const identity = await resolveIdentity?.(repo);
    if (!identity) throw notFound();
    return typeof identity === 'string' ? { did: identity } : identity;
  };
  const currentRepo = async (group, repo) => {
    const identity = await identityFor(repo);
    const [row] = await query('SELECT r.did, r.head, r.rev, c.entries, c.car FROM repos r JOIN commits c ON c.cid = r.head AND c.did = r.did WHERE r.group_id = $1 AND r.did = $2', [group.id, identity.did]);
    if (!row) throw notFound();
    return { ...row, identity };
  };
  const recordFor = async (repo, entry, blocks) => {
    // The current repository CAR is the authority, not the globally shared blocks table.
    blocks ??= (await readCar(repo.car)).blocks;
    const bytes = blocks.get(parseCid(entry.value));
    if (!bytes) throw notFound('RecordNotFound');
    return lexJson(cbor.decode(bytes));
  };
  const body = async req => {
    let size = 0;
    const chunks = [];
    for await (const chunk of req) {
      size += chunk.length;
      if (size > options.maxEventBytes) throw new RequestError('InvalidRequest', 'Body too large', 413);
      chunks.push(chunk);
    }
    try { return JSON.parse(Buffer.concat(chunks).toString()); } catch { throw invalid('Invalid JSON'); }
  };
  const authenticate = req => {
    const token = process.env.PEER_TOKEN;
    const expected = token ? `Bearer ${token}` : '';
    const actual = req.headers.authorization ?? '';
    const digest = value => createHash('sha256').update(value).digest();
    if (!timingSafeEqual(digest(actual), digest(expected)) || !token) {
      throw new RequestError('AuthRequired', 'Peer authentication required', 401);
    }
  };

  async function handle(req, res) {
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname.startsWith('/xrpc/')) {
      res.setHeader('access-control-allow-origin', '*');
      if (req.method === 'OPTIONS') {
        res.writeHead(204, { 'access-control-allow-methods': 'GET, OPTIONS', 'access-control-allow-headers': 'content-type' });
        return res.end();
      }
    }
    if (req.method === 'GET' && url.pathname === '/health') return json(res, 200, { ok: true });
    if (req.method === 'GET' && url.pathname === '/' && req.headers.accept?.split(',').some(value => value.trim().split(';')[0] === 'application/nostr+json')) {
      res.writeHead(200, { 'content-type': 'application/nostr+json', 'access-control-allow-origin': '*' });
      return res.end(JSON.stringify({ name: 'mosimo', supported_nips: [1, 9, 11],
        limitation: { max_message_length: options.maxEventBytes, max_subscriptions: options.maxSubscriptions, max_limit: options.maxResults },
        mosimo: { version: 1, kinds: [1, 5], publicTextOnly: true, blobs: false,
                  groups: (options.groups ?? []).map(({ id, endpoint }) => ({ id, endpoint })) } }));
    }
    if (url.pathname === '/.well-known/did.json') {
      if (req.method !== 'GET') throw new RequestError('InvalidRequest', 'GET required', 405);
      const group = groupFor(req);
      return json(res, 200, { '@context': ['https://www.w3.org/ns/did/v1'], id: group.serviceDid,
        service: [{ id: `${group.serviceDid}#atproto_pds`, type: 'AtprotoPersonalDataServer', serviceEndpoint: group.endpoint }] });
    }
    if (url.pathname.startsWith('/internal/')) {
      authenticate(req);
      if (req.method === 'GET' && /^\/internal\/events\/[0-9a-f]{64}$/.test(url.pathname)) {
        const [row] = await query(`SELECT e.event FROM events e WHERE e.id = $1 AND ${visible}`, [url.pathname.split('/').pop()]);
        if (!row) throw notFound('EventNotFound');
        return json(res, 200, row.event);
      }
      if (req.method === 'POST' && url.pathname === '/internal/notify') {
        const notification = await body(req);
        if (!isObject(notification) || typeof notification.eventId !== 'string' || !hex.test(notification.eventId) || Object.keys(notification).some(key => key !== 'eventId')) throw invalid('Expected only eventId');
        if (!onNotify) throw new RequestError('NotImplemented', 'Peer notifications unavailable', 501);
        await onNotify({ eventId: notification.eventId });
        return json(res, 200, { ok: true });
      }
      throw notFound('NotFound');
    }
    if (!url.pathname.startsWith('/xrpc/')) throw notFound('NotFound');
    if (req.method !== 'GET') throw new RequestError('InvalidRequest', 'GET required', 405);
    const group = groupFor(req);
    const method = url.pathname.slice('/xrpc/'.length);
    if (method === 'com.atproto.server.describeServer') {
      return json(res, 200, { did: group.serviceDid, availableUserDomains: [] });
    }
    if (method === 'com.atproto.sync.listRepos') {
      const limit = limitFor(url);
      const rows = await query('SELECT did, head, rev FROM repos WHERE group_id = $1 AND did > $2 ORDER BY did LIMIT $3', [group.id, url.searchParams.get('cursor') ?? '', limit]);
      return json(res, 200, { repos: rows.map(row => ({ ...row, active: true })), ...(rows.length === limit ? { cursor: rows.at(-1).did } : {}) });
    }
    const supported = new Set(['com.atproto.sync.getRepo', 'com.atproto.sync.getCheckout', 'com.atproto.sync.getHead',
      'com.atproto.sync.getLatestCommit', 'com.atproto.sync.getRepoStatus', 'com.atproto.sync.getBlocks',
      'com.atproto.sync.getRecord', 'com.atproto.sync.getBlob', 'com.atproto.sync.listBlobs',
      'com.atproto.repo.describeRepo', 'com.atproto.repo.getRecord', 'com.atproto.repo.listRecords']);
    if (!supported.has(method)) throw notFound('XRPCNotSupported');
    const repo = await currentRepo(group, parameter(url, method.startsWith('com.atproto.repo.') ? 'repo' : 'did'));
    switch (method) {
      case 'com.atproto.sync.getRepo':
      case 'com.atproto.sync.getCheckout':
        return carResponse(res, repo.car);
      case 'com.atproto.sync.getHead': return json(res, 200, { root: repo.head });
      case 'com.atproto.sync.getLatestCommit': return json(res, 200, { cid: repo.head, rev: repo.rev });
      case 'com.atproto.sync.getRepoStatus': return json(res, 200, { did: repo.did, active: true, rev: repo.rev });
      case 'com.atproto.sync.getBlob': throw notFound('BlobNotFound');
      case 'com.atproto.sync.listBlobs': return json(res, 200, { cids: [] });
      case 'com.atproto.sync.getBlocks': {
        const cids = url.searchParams.getAll('cids');
        if (!cids.length || cids.length > options.maxResults) throw invalid('Invalid cids');
        const requested = cids.map(text => parseCid(text).toString());
        // A Relay replaying an earlier commit may need historical MST nodes, not just the latest CAR.
        const rows = await query(`SELECT b.cid, b.bytes FROM blocks b JOIN repo_blocks r ON r.cid = b.cid
          WHERE r.did = $1 AND ${sqlite ? 'b.cid IN (SELECT value FROM json_each($2))' : 'b.cid = ANY($2::text[])'}`, [repo.did, sqlite ? JSON.stringify(requested) : requested]);
        const available = new Map(rows.map(row => [row.cid, row.bytes]));
        const selected = new BlockMap();
        for (const text of requested) {
          const bytes = available.get(text);
          if (!bytes) throw notFound('BlockNotFound');
          selected.set(parseCid(text), bytes);
        }
        return carResponse(res, await blocksToCarFile(parseCid(repo.head), selected));
      }
      case 'com.atproto.repo.describeRepo': {
        const resolved = await resolveIdentity?.(repo.did);
        const identity = typeof resolved === 'string' ? { did: resolved } : resolved ?? repo.identity;
        if (!identity.didDoc) throw new RequestError('UpstreamFailure', 'Identity document unavailable', 502);
        return json(res, 200, { did: repo.did, handle: identity.handle ?? 'handle.invalid', didDoc: identity.didDoc,
          collections: [...new Set(repo.entries.map(entry => entry.key.split('/')[0]))], handleIsCorrect: identity.handleVerified === true });
      }
      case 'com.atproto.sync.getRecord':
      case 'com.atproto.repo.getRecord': {
        const collection = parameter(url, 'collection');
        const rkey = parameter(url, 'rkey');
        const entry = repo.entries.find(entry => entry.key === `${collection}/${rkey}`);
        if (!entry || (url.searchParams.has('cid') && url.searchParams.get('cid') !== entry.value)) throw notFound('RecordNotFound');
        if (method === 'com.atproto.sync.getRecord') return carResponse(res, repo.car);
        return json(res, 200, { uri: `at://${repo.did}/${entry.key}`, cid: entry.value, value: await recordFor(repo, entry) });
      }
      case 'com.atproto.repo.listRecords': {
        const collection = parameter(url, 'collection');
        const limit = limitFor(url);
        const reverse = url.searchParams.get('reverse') ?? 'false';
        if (!['true', 'false'].includes(reverse)) throw invalid('Invalid reverse');
        const cursor = url.searchParams.get('cursor');
        const entries = repo.entries.filter(entry => entry.key.startsWith(`${collection}/`))
          .sort((a, b) => a.key < b.key ? -1 : a.key > b.key ? 1 : 0);
        if (reverse === 'true') entries.reverse();
        const selected = entries.filter(entry => !cursor || (reverse === 'true' ? entry.key < cursor : entry.key > cursor)).slice(0, limit);
        const { blocks } = await readCar(repo.car);
        const records = await Promise.all(selected.map(async entry => ({ uri: `at://${repo.did}/${entry.key}`, cid: entry.value, value: await recordFor(repo, entry, blocks) })));
        return json(res, 200, { records, ...(selected.length === limit ? { cursor: selected.at(-1).key } : {}) });
      }
    }
  }

  const server = http.createServer((req, res) => {
    handle(req, res).catch(error => {
      if (res.headersSent || res.destroyed) return res.destroy();
      json(res, error instanceof RequestError ? error.status : 500,
        { error: error instanceof RequestError ? error.error : 'InternalServerError', message: error instanceof RequestError ? error.message : 'Internal server error' });
    });
  });
  server.requestTimeout = 30000;
  server.headersTimeout = 15000;
  const wss = new WebSocketServer({ noServer: true, maxPayload: options.maxEventBytes, perMessageDeflate: false });
  server.on('upgrade', (req, socket, head) => {
    try {
      if (closing) throw new RequestError('Unavailable', 'Closing', 503);
      if (connections.size >= 1000) throw new RequestError('Unavailable', 'Connection limit reached', 503);
      const url = new URL(req.url, 'http://localhost');
      const stream = url.pathname === '/xrpc/com.atproto.sync.subscribeRepos';
      if (url.pathname !== '/' && !stream) throw notFound('NotFound');
      const group = stream ? groupFor(req) : null;
      wss.handleUpgrade(req, socket, head, ws => connect(ws, url, group));
    } catch (error) {
      socket.end(`HTTP/1.1 ${error.status ?? 400} Error\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
    }
  });

  function connect(ws, url, group) {
    connections.add(ws);
    let alive = true;
    let stopped = false;
    let busy = false;
    let streamReady = false;
    let streamCursor = 0n;
    let pendingBytes = 0;
    let pendingCount = 0;
    let work = Promise.resolve();
    const subscriptions = new Map();
    const streamError = (error, message = error) => Buffer.concat([cbor.encode({ op: -1 }), cbor.encode({ error, message })]);
    const stop = (code = 1011, reason = 'Internal server error') => {
      if (stopped) return;
      stopped = true;
      ws.close(code, reason);
      const timer = setTimeout(() => ws.terminate(), 1000);
      timer.unref();
      ws.once('close', () => clearTimeout(timer));
    };
    const send = data => {
      if (stopped || ws.readyState !== WebSocket.OPEN) return false;
      const size = typeof data === 'string' ? Buffer.byteLength(data) : data.length;
      if (ws.bufferedAmount + size > options.maxBufferedBytes) {
        if (group && ws.bufferedAmount + 128 <= options.maxBufferedBytes) ws.send(streamError('ConsumerTooSlow'));
        stop(1008, 'ConsumerTooSlow');
        return false;
      }
      ws.send(data, error => { if (error) stop(); });
      return true;
    };
    const sendJson = value => send(JSON.stringify(value));
    const failure = error => { send(streamError(error)); stop(1008, error); };
    const enqueue = task => {
      work = work.then(() => stopped ? undefined : task()).catch(() => stop());
    };

    async function subscribe(id, filters) {
      if (!subscriptions.has(id) && subscriptions.size >= options.maxSubscriptions) {
        sendJson(['CLOSED', id, 'restricted: too many subscriptions']);
        return;
      }
      const sub = { filters, cursor: 0n, ready: false };
      subscriptions.set(id, sub);
      const [high] = await query('SELECT CAST(COALESCE(MAX(arrival), 0) AS text) AS cursor FROM events');
      sub.cursor = BigInt(high.cursor);
      const found = new Map();
      for (const filter of filters) {
        const limit = Math.min(filter.limit ?? options.maxResults, options.maxResults);
        if (!limit) continue;
        const params = [sub.cursor.toString()];
        const where = filterSql(filter, params, sqlite);
        params.push(limit);
        const rows = await query(`SELECT e.id, e.event FROM events e WHERE e.arrival <= $1 AND e.kind IN (1, 5) AND ${visible} AND (${where}) ORDER BY e.created_at DESC, e.id ASC LIMIT $${params.length}`, params);
        for (const row of rows) found.set(row.id, row.event);
      }
      const events = [...found.values()].sort((a, b) => b.created_at - a.created_at || a.id.localeCompare(b.id)).slice(0, options.maxResults);
      for (const event of events) if (!sendJson(['EVENT', id, event])) return;
      sendJson(['EOSE', id]);
      sub.ready = true;
    }

    async function message(data, binary) {
      if (group) return;
      let value;
      try {
        if (binary) throw invalid('Expected text');
        try { value = JSON.parse(data.toString()); } catch { throw invalid('Invalid JSON'); }
        if (!Array.isArray(value)) throw invalid('Expected NIP-01 array');
        if (value[0] === 'EVENT' && value.length === 2) {
          const event = value[1];
          if (!isObject(event) || !hex.test(event.id) || ![1, 5].includes(event.kind)) {
            return sendJson(['OK', typeof event?.id === 'string' ? event.id.slice(0, 64) : '', false, 'invalid: expected kind 1 or 5 event']);
          }
          try {
            const result = await ingest(event);
            sendJson(['OK', event.id, Boolean(result.accepted), result.message ?? (result.duplicate ? 'duplicate: already stored' : result.accepted ? '' : 'invalid: rejected')]);
          } catch {
            sendJson(['OK', event.id, false, 'error: event ingestion failed']);
          }
          return;
        }
        if (value[0] === 'CLOSE' && value.length === 2 && typeof value[1] === 'string') {
          subscriptions.delete(value[1]);
          return;
        }
        if (value[0] === 'REQ' && value.length >= 3 && value.length <= options.maxSubscriptions + 2
          && typeof value[1] === 'string' && value[1].length > 0 && Buffer.byteLength(value[1]) <= 64) {
          const filters = value.slice(2).map(filter => filterValue(filter, options.maxResults));
          await subscribe(value[1], filters);
          return;
        }
        throw invalid('Invalid NIP-01 message');
      } catch (error) {
        if (!(error instanceof RequestError)) throw error;
        sendJson(['NOTICE', `invalid: ${error.message}`]);
      }
    }

    async function poll() {
      if (group) {
        if (!streamReady) return;
        const rows = await query('SELECT CAST(seq AS text) AS seq, frame FROM streams WHERE group_id = $1 AND seq > $2 ORDER BY streams.seq LIMIT $3', [group.id, streamCursor.toString(), options.maxResults]);
        for (const row of rows) {
          if (!send(Buffer.from(row.frame))) return;
          streamCursor = BigInt(row.seq);
        }
        return;
      }
      for (const [id, sub] of subscriptions) {
        if (!sub.ready) continue;
        const rows = await query(`SELECT e.id, e.event, CAST(e.arrival AS text) AS arrival, ${visible} AS visible FROM events e WHERE e.arrival > $1 ORDER BY e.arrival LIMIT $2`, [sub.cursor.toString(), options.maxResults]);
        for (const row of rows) {
          sub.cursor = BigInt(row.arrival);
          if (row.visible && [1, 5].includes(row.event.kind) && sub.filters.some(filter => matches(row.event, filter))) {
            if (!sendJson(['EVENT', id, row.event])) return;
          }
        }
      }
    }

    if (group) enqueue(async () => {
      const [row] = await query('SELECT CAST(COALESCE(MAX(seq), 0) AS text) AS cursor FROM streams WHERE group_id = $1', [group.id]);
      const latest = BigInt(row.cursor);
      const cursor = url.searchParams.get('cursor');
      if (url.searchParams.getAll('cursor').length > 1 || (cursor !== null && (!/^\d+$/.test(cursor) || cursor.length > 19 || BigInt(cursor) > 9223372036854775807n))) return failure('InvalidRequest');
      streamCursor = cursor === null ? 0n : BigInt(cursor);
      if (streamCursor > latest) return failure('FutureCursor');
      streamReady = true;
      await poll();
    });
    ws.on('message', (data, binary) => {
      if (stopped) return;
      pendingBytes += data.length;
      pendingCount++;
      if (pendingBytes > options.maxBufferedBytes || pendingCount > 100) return stop(1008, 'Too many pending messages');
      enqueue(async () => {
        try { await message(data, binary); } finally { pendingBytes -= data.length; pendingCount--; }
      });
    });
    ws.on('pong', () => { alive = true; });
    ws.on('error', () => stop());
    const heartbeat = setInterval(() => {
      if (!alive) return ws.terminate();
      alive = false;
      if (ws.readyState === WebSocket.OPEN) ws.ping();
    }, 30000);
    const timer = setInterval(() => {
      if (busy || stopped) return;
      busy = true;
      enqueue(async () => { try { await poll(); } finally { busy = false; } });
    }, options.pollIntervalMs ?? 100);
    timer.unref();
    heartbeat.unref();
    ws.on('close', () => {
      stopped = true;
      clearInterval(timer);
      clearInterval(heartbeat);
      subscriptions.clear();
      connections.delete(ws);
    });
  }

  let closePromise;
  return { server, close() {
    closePromise ??= (async () => {
      closing = true;
      for (const ws of connections) ws.terminate();
      await new Promise(resolve => wss.close(resolve));
      await new Promise((resolve, reject) => server.close(error => error && error.code !== 'ERR_SERVER_NOT_RUNNING' ? reject(error) : resolve()));
    })();
    return closePromise;
  } };
}
