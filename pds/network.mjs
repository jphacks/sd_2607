import { WebSocket, createWebSocketStream } from 'ws';
import { fetchJson, fetchBytes, parseNetworkJson } from './identity.mjs';
import { decodeCanonical, rootId, verifySnapshot, verifyPublicInformation, effectivePriority, NS, IDENTITY } from './public-information.mjs';
import { sqliteNow } from './store.mjs';

// Security state is never evicted, even when a root is disabled or a snapshot expires.
export function createPriorityPolicy({ config, store, getBytes = fetchBytes, now = () => Math.floor(Date.now() / 1000) }) {
  const { roots = [], selection = [] } = config.publicInformation ?? {};
  const enabled = roots.filter(root => root.enabled);
  const fetchedAt = new Map();
  const durable = async fn => {
    try { return await fn(); }
    catch (cause) { if (cause.trustAuthorization) throw cause; throw Object.assign(new Error('Trust persistence failed', { cause }), { trustStorage: true }); }
  };
  const adapter = () => ({
    async get(id) {
      const row = await durable(async () => (await store.query('SELECT snapshot FROM trust_snapshots WHERE root_id=$1', [id])).rows[0]);
      if (!row) throw new Error('Snapshot unavailable');
      return JSON.parse(row.snapshot);
    },
    async accept(id, snapshot) {
      const root = enabled.find(root => rootId(root.rootPubkey) === id);
      if (!root) throw new Error('Unknown root');
      await durable(() => store.transaction(async tx => {
        // PostgreSQL needs a lock even before the first row exists; SQLite uses BEGIN IMMEDIATE.
        if (store.dialect !== 'sqlite') await tx.query('SELECT pg_advisory_xact_lock(2607, 1)');
        const row = (await tx.query('SELECT snapshot FROM trust_snapshots WHERE root_id=$1', [id])).rows[0];
        if (!row && Number((await tx.query('SELECT COUNT(*) AS count FROM trust_snapshots')).rows[0].count) >= 64) throw new Error('Root state capacity reached');
        try { await verifySnapshot(snapshot, { root, nowSeconds: now(), previous: row ? JSON.parse(row.snapshot) : null }); }
        catch (error) { error.trustAuthorization = true; throw error; }
        await tx.query('INSERT INTO trust_snapshots(root_id,snapshot) VALUES($1,$2) ON CONFLICT(root_id) DO UPDATE SET snapshot=excluded.snapshot', [id, JSON.stringify(snapshot)]);
      }));
    },
    async recordCertificate(issuer, serial, certId) {
      const accepted = await durable(() => store.transaction(async tx => {
        if (store.dialect !== 'sqlite') await tx.query('SELECT pg_advisory_xact_lock(2607, 1)');
        const row = (await tx.query(`SELECT s.cert_id, EXISTS (
          SELECT 1 FROM trust_serial_conflicts c WHERE c.issuer=s.issuer AND c.serial=s.serial
        ) AS conflicted FROM trust_serials s WHERE s.issuer=$1 AND s.serial=$2`, [issuer, serial])).rows[0];
        if (row) {
          if (row.conflicted) return false;
          if (row.cert_id === certId) return true;
          await tx.query('INSERT INTO trust_serial_conflicts(issuer,serial) VALUES($1,$2) ON CONFLICT(issuer,serial) DO NOTHING', [issuer, serial]);
          return false;
        }
        const count = (await tx.query('SELECT COUNT(*) AS count FROM trust_serials')).rows[0];
        if (Number(count.count) >= 100000) throw new Error('Certificate state capacity reached');
        await tx.query('INSERT INTO trust_serials(issuer,serial,cert_id) VALUES($1,$2,$3)', [issuer, serial, certId]);
        return true;
      }));
      // Commit security evidence first; rejecting the event must not roll back the marker.
      if (!accepted) throw Object.assign(new Error('Certificate serial conflict'), { trustAuthorization: true });
    },
  });
  return {
    async capture() {
      const snapshots = adapter();
      // Fetch each provisioned root independently; a failed root cannot suppress another's CRL.
      const results = await Promise.allSettled(enabled.map(async root => {
        const id = rootId(root.rootPubkey);
        if (performance.now() - (fetchedAt.get(id) ?? -Infinity) < 30000) return;
        fetchedAt.set(id, performance.now());
        const snapshot = decodeCanonical(await getBytes(root.snapshotUrl, { maxBytes: 32768, contentType: 'application/cbor',
          headers: { accept: 'application/cbor' } }));
        await snapshots.accept(rootId(root.rootPubkey), snapshot);
      }));
      if (results.some(result => result.status === 'rejected' && result.reason.trustStorage)) throw new Error('Trust state update failed');
      const captured = new Map();
      for (const root of enabled) {
        try { captured.set(rootId(root.rootPubkey), await snapshots.get(rootId(root.rootPubkey))); }
        catch (error) { if (error.trustStorage) throw error; }
      }
      const frozen = { ...snapshots, async get(id) {
        if (!captured.has(id)) throw new Error('Snapshot unavailable');
        return captured.get(id);
      } };
      let checkedAt = 0;
      let validUntil = Infinity;
      const evaluate = async event => {
        try {
          const result = await verifyPublicInformation(event, { roots, selection, snapshots: frozen, nowSeconds: now() });
          if (result.effectivePriority > 1) {
            // The shared verifier has validated these exact fields; retain their earliest send deadline.
            const identity = decodeCanonical(Buffer.from(event.tags.find(tag => tag[0] === `${NS}:ext:${IDENTITY}`)[1], 'base64'));
            const delivery = event.tags.find(tag => tag[0] === `${NS}:delivery-expires-at`)[1];
            validUntil = Math.min(validUntil, Date.parse(result.snapshot.validUntil) / 1000,
              Date.parse(identity.chain[0].body.notAfter) / 1000, Date.parse(delivery) / 1000);
            checkedAt = Math.max(checkedAt, result.checkedAt);
          }
          return result.effectivePriority;
        } catch (error) {
          if (error.trustStorage) throw error;
          return effectivePriority(event, false);
        }
      };
      evaluate.assertFresh = () => {
        const current = now();
        if (!Number.isSafeInteger(current) || current < checkedAt || current >= validUntil) throw new Error('Priority authorization expired/clock changed');
      };
      return evaluate;
    },
  };
}

export function startNetwork({ config, store, ingest }) {
  const sqlite = store.dialect === 'sqlite';
  let stopped = false;
  const sockets = new Set();
  const timers = new Set();
  const inflight = new Map();
  const relayTasks = new Set();
  function later(fn, delay) {
    if (stopped) return;
    const timer = setTimeout(() => { timers.delete(timer); if (!stopped) fn(); }, delay);
    timers.add(timer);
  }
  function connect(url, attempt = 0) {
    if (stopped) return;
    const ws = new WebSocket(url, { maxPayload: config.maxEventBytes, handshakeTimeout: 10000 });
    sockets.add(ws);
    // Object mode preserves message boundaries and distinguishes text from binary.
    const stream = createWebSocketStream(ws, { readableObjectMode: true, highWaterMark: 1 });
    let alive = true;
    const heartbeat = setInterval(() => {
      // Backpressure also delays pong frames; a paused reader is not a dead relay.
      if (ws.isPaused) { alive = true; return; }
      if (!alive) return ws.terminate();
      alive = false;
      if (ws.readyState === WebSocket.OPEN) ws.ping();
    }, 30000);
    ws.on('pong', () => { alive = true; });
    ws.on('open', () => {
      attempt = 0;
      // Do not use a created_at watermark: disaster/offline posts may be arbitrarily old.
      ws.send(JSON.stringify(['REQ', 'mosimo', { kinds: [1], '#t': ['mosimo.oriom.dev'] }, { kinds: [5] }]));
    });
    const task = (async () => {
      try {
        for await (const bytes of stream) {
          if (stopped) break;
          if (typeof bytes !== 'string') continue;
          let message;
          try { message = parseNetworkJson(Buffer.from(bytes)); } catch { continue; }
          try {
            if (message[0] === 'EVENT' && message[1] === 'mosimo') await ingest(message[2]);
          } catch (error) { console.error('Relay ingestion:', error.message); }
        }
      } catch {
        // WebSocket errors are reported by the connection error listener below.
      }
    })().finally(() => relayTasks.delete(task));
    relayTasks.add(task);
    ws.on('error', error => console.error('Relay connection:', url, error.message));
    ws.on('close', () => {
      clearInterval(heartbeat);
      sockets.delete(ws);
      later(() => connect(url, attempt + 1), Math.min(60000, 1000 * 2 ** Math.min(attempt, 6)) + Math.random() * 1000);
    });
  }
  for (const relay of config.relays) connect(relay);

  async function onNotify({ eventId }) {
    if ((await store.query('SELECT 1 FROM events WHERE id=$1', [eventId])).rowCount) return;
    if (inflight.has(eventId)) return inflight.get(eventId);
    if (inflight.size >= 20) throw new Error('Peer fetch capacity reached');
    const task = (async () => {
      for (const peer of config.peers) {
        try {
          const event = await fetchJson(`${peer.url}/internal/events/${eventId}`, {
            maxBytes: config.maxEventBytes, headers: { authorization: `Bearer ${process.env[peer.tokenEnv]}` },
          });
          if (event.id !== eventId) continue;
          const result = await ingest(event, { forward: false });
          if (result.accepted) return;
        } catch (error) { console.error('Peer retrieval:', peer.url, error.message); }
      }
      throw new Error('No configured peer could supply a valid event');
    })().finally(() => inflight.delete(eventId));
    inflight.set(eventId, task);
    return task;
  }

  let outboxBusy = false;
  let outboxTask = Promise.resolve();
  function runOutbox() { outboxTask = forwardOne(); }
  async function forwardOne() {
    if (stopped || outboxBusy) return;
    outboxBusy = true;
    try {
      const row = (await store.query(`SELECT * FROM outbox WHERE next_attempt<=${sqlite ? sqliteNow : 'now()'} ORDER BY next_attempt LIMIT 1`)).rows[0];
      if (!row) return;
      const peer = config.peers.find(peer => peer.url === row.peer);
      if (!peer || row.attempts >= 10) {
        await store.query('DELETE FROM outbox WHERE peer=$1 AND event_id=$2', [row.peer, row.event_id]);
        return;
      }
      try {
        await fetchJson(`${peer.url}/internal/notify`, { method: 'POST',
          headers: { 'content-type': 'application/json', authorization: `Bearer ${process.env[peer.tokenEnv]}` },
          body: JSON.stringify({ eventId: row.event_id }),
        });
        await store.query('DELETE FROM outbox WHERE peer=$1 AND event_id=$2', [row.peer, row.event_id]);
      } catch (error) {
        console.error('Peer notification:', peer.url, error.message);
        await store.query(`UPDATE outbox SET attempts=attempts+1,next_attempt=${sqlite ? "strftime('%Y-%m-%dT%H:%M:%fZ','now','+' || $3 || ' seconds')" : 'now()+make_interval(secs => $3)'}
          WHERE peer=$1 AND event_id=$2`, [row.peer, row.event_id, Math.min(3600, 2 ** (row.attempts + 1))]);
      }
    } catch (error) { console.error('Peer outbox:', error.message); }
    finally { outboxBusy = false; if (!stopped) later(runOutbox, 1000); }
  }
  runOutbox();
  return {
    onNotify,
    async close() {
      stopped = true;
      for (const timer of timers) clearTimeout(timer);
      for (const socket of sockets) socket.terminate();
      await Promise.allSettled([outboxTask, ...relayTasks, ...inflight.values()]);
    },
  };
}
