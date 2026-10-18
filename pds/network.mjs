import { WebSocket, createWebSocketStream } from 'ws';
import { fetchJson } from './identity.mjs';
import { sqliteNow } from './store.mjs';

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
          try { message = JSON.parse(bytes); } catch { continue; }
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
