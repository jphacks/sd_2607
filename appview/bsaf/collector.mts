import { readFile } from 'node:fs/promises';
import { setTimeout as delay } from 'node:timers/promises';
import WebSocket, { createWebSocketStream } from 'ws';
import { COLLECTION, CollectorError, openStore, validateConfig, type Config, type Store } from './store.mts';

export interface CollectorOptions {
  signal: AbortSignal;
  reconnectMs?: number;
  log?: (metadata: { status: string; cursor: number | null }) => void;
}

export function subscriptionUrl(config: Config, cursor: number | null): URL | null {
  validateConfig(config);
  if (!config.authors.length) return null;
  const url = new URL(config.jetstreamUrl);
  // Do not inherit upstream filters, compression, or a caller-supplied cursor.
  url.search = '';
  url.searchParams.set('compress', 'false');
  url.searchParams.set('wantedCollections', COLLECTION);
  for (const did of config.authors) url.searchParams.append('wantedDids', did);
  // Inclusive overlap handles a disconnect between events with equal timestamps.
  if (cursor !== null) url.searchParams.set('cursor', String(Math.max(0, cursor - 1)));
  return url;
}

export async function collect(config: Config, store: Store, options: CollectorOptions): Promise<void> {
  validateConfig(config);
  const { signal, log = () => {} } = options;
  if (!config.authors.length) return;
  while (!signal.aborted) {
    const ws = new WebSocket(subscriptionUrl(config, store.cursor)!, {
      maxPayload: 1024 * 1024,
      perMessageDeflate: false,
      handshakeTimeout: 10000,
    });
    // ws overrides objectMode:false internally: readableObjectMode is essential.
    // One chunk is one complete WS message (text -> string, binary -> Buffer),
    // not a JSON object and not a byte-stream fragment. push(false) pauses ws.
    const stream = createWebSocketStream(ws, { readableObjectMode: true, highWaterMark: 4 });
    const abort = () => { ws.terminate(); stream.destroy(); };
    signal.addEventListener('abort', abort, { once: true });
    if (signal.aborted) abort();
    let alive = true;
    ws.on('pong', () => { alive = true; });
    const heartbeat = setInterval(() => {
      if (ws.readyState !== WebSocket.OPEN) return;
      if (!alive) { ws.terminate(); return; }
      alive = false;
      ws.ping();
    }, 30000);
    let storageFailed = false;
    try {
      for await (const chunk of stream) {
        if (signal.aborted) break;
        // Binary JSON is accepted too; ws enforces maxPayload before allocation
        // of the complete message. Object mode prevents adjacent JSON merging.
        let event: unknown;
        try {
          if (typeof chunk !== 'string' && !Buffer.isBuffer(chunk)) throw new Error('Unexpected chunk');
          event = JSON.parse(chunk.toString());
        } catch { log({ status: 'invalid-json', cursor: store.cursor }); continue; }
        // Deliberately serial: no promise queue, and no cursor ahead of commits.
        const status = await store.ingest(event).catch(error => { storageFailed = true; throw error; });
        if (status === 'rejected') log({ status, cursor: store.cursor });
      }
    } catch (error) {
      // Network failures reconnect; DB failures must stop rather than skip data.
      if (storageFailed) throw error;
      log({ status: 'disconnected', cursor: store.cursor });
    } finally {
      clearInterval(heartbeat);
      signal.removeEventListener('abort', abort);
      ws.terminate();
      stream.destroy();
    }
    if (!signal.aborted) {
      log({ status: 'reconnecting', cursor: store.cursor });
      await delay(options.reconnectMs ?? 1000, undefined, { signal }).catch(() => {});
    }
  }
}

export async function main(): Promise<void> {
  if (!process.env.CONFIG_FILE) throw new CollectorError('config');
  let config: unknown;
  try { config = JSON.parse(await readFile(process.env.CONFIG_FILE, 'utf8')); }
  catch { throw new CollectorError('config'); }
  validateConfig(config);
  const store = openStore(config.databasePath, config.authors, config.jetstreamUrl);
  const controller = new AbortController();
  const stop = () => controller.abort();
  process.once('SIGINT', stop);
  process.once('SIGTERM', stop);
  try {
    await collect(config, store, {
      signal: controller.signal,
      log: metadata => console.error(JSON.stringify(metadata)),
    });
  } catch { throw new CollectorError('storage'); }
  finally {
    process.removeListener('SIGINT', stop);
    process.removeListener('SIGTERM', stop);
    store.close();
  }
}

if (import.meta.main) {
  main().catch(error => {
    // Only our fixed messages are safe; SQLite/upstream errors can contain payloads.
    console.error(JSON.stringify({ status: 'fatal',
      reason: error instanceof CollectorError ? error.reason : 'unexpected',
      message: error instanceof CollectorError ? error.message : 'Unexpected collector failure',
    }));
    process.exitCode = 1;
  });
}
