import { statSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import { cidForLex, type LexValue } from '@atproto/lex-cbor';
import { parseCid } from '@atproto/lex-data';

export const COLLECTION = 'app.bsky.feed.post';
const failureMessages = {
  config: 'Check CONFIG_FILE: expected databasePath, unique approved authors, and a ws/wss jetstreamUrl',
  database: 'Cannot open existing databasePath: check the file and permissions',
  schema: 'Incompatible HappyView schema: run HappyView 2.16 migrations first',
  subscription: 'Remove native app.bsky.feed.post subscription before starting collector',
  initialization: 'Cannot initialize BSAF store: check SQLite locks, permissions, and sidecar tables',
  storage: 'BSAF processing failed: check SQLite locks, disk space, and schema',
} as const;
export class CollectorError extends Error {
  readonly reason: keyof typeof failureMessages;
  constructor(reason: keyof typeof failureMessages) {
    super(failureMessages[reason]);
    this.reason = reason;
  }
}
export interface Config {
  databasePath: string;
  authors: string[];
  jetstreamUrl: string;
}
export interface Store {
  readonly cursor: number | null;
  /** Await each call before the next; overlapping calls reject rather than race the cursor. */
  ingest(event: unknown): Promise<'stored' | 'deleted' | 'ignored' | 'rejected' | 'stale'>;
  close(): void;
}
export function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
export function validateConfig(value: unknown): asserts value is Config {
  if (!object(value) || typeof value.databasePath !== 'string' || !value.databasePath ||
      !Array.isArray(value.authors) || value.authors.length > 10000 ||
      !value.authors.every(did => typeof did === 'string' &&
        /^did:[a-z]+:[A-Za-z0-9._:%-]+$/.test(did)) ||
      new Set(value.authors).size !== value.authors.length || typeof value.jetstreamUrl !== 'string') {
    throw new CollectorError('config');
  }
  let url: URL;
  try { url = new URL(value.jetstreamUrl); } catch { throw new CollectorError('config'); }
  if (!['ws:', 'wss:'].includes(url.protocol) || url.username || url.password || url.hash) {
    throw new CollectorError('config');
  }
}

// Jetstream carries Lex JSON: links and bytes must become CBOR values for hashing,
// but this converted copy is never written back to HappyView.
function lex(value: unknown): LexValue {
  if (value === null || typeof value === 'string' || typeof value === 'boolean') return value;
  if (typeof value === 'number' && Number.isSafeInteger(value)) return value;
  if (Array.isArray(value)) return value.map(lex);
  if (!object(value)) throw new Error('Invalid Lex JSON');
  if ('$link' in value) {
    if (Object.keys(value).length !== 1 || typeof value.$link !== 'string') throw new Error('Invalid link');
    return parseCid(value.$link);
  }
  if ('$bytes' in value) {
    if (Object.keys(value).length !== 1 || typeof value.$bytes !== 'string' ||
        !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value.$bytes)) {
      throw new Error('Invalid bytes');
    }
    return Uint8Array.from(Buffer.from(value.$bytes, 'base64'));
  }
  return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, lex(item)]));
}

export function openStore(databasePath: string, authors: string[], jetstreamUrl: string): Store {
  validateConfig({ databasePath, authors, jetstreamUrl });
  let db: DatabaseSync;
  try {
    if (databasePath !== ':memory:' && !statSync(databasePath).isFile()) throw new CollectorError('database');
    db = new DatabaseSync(databasePath);
  } catch { throw new CollectorError('database'); }
  function transaction<T>(fn: () => T): T {
    db.exec('BEGIN IMMEDIATE');
    try {
      const result = fn();
      db.exec('COMMIT');
      return result;
    } catch (error) {
      db.exec('ROLLBACK');
      throw error;
    }
  }
  try {
    db.exec('PRAGMA busy_timeout = 3000; PRAGMA foreign_keys = ON;');
    // Required columns come from upstream v2.16.0 SQLite migrations, including
    // 20260626000000_prefix_table_names.sql. Never bootstrap a HappyView DB.
    try {
      db.prepare(`SELECT uri,did,collection,rkey,record,cid,indexed_at,created_at FROM happyview_records LIMIT 0`).all();
      const columns = db.prepare('PRAGMA table_info(happyview_records)').all();
      if (!columns.some(column => column.name === 'uri' && column.pk === 1 && column.type === 'TEXT')) {
        throw new CollectorError('schema');
      }
      db.prepare('SELECT id FROM happyview_lexicons LIMIT 0').all();
    } catch { throw new CollectorError('schema'); }
    transaction(() => {
      if (db.prepare('SELECT 1 FROM happyview_lexicons WHERE id = ?').get(COLLECTION)) {
        throw new CollectorError('subscription');
      }
      db.exec(`
        CREATE TABLE IF NOT EXISTS mosimo_bsaf_sources (did TEXT PRIMARY KEY);
        CREATE TABLE IF NOT EXISTS mosimo_bsaf_events (
          uri TEXT PRIMARY KEY, time_us INTEGER NOT NULL, cid TEXT,
          fetched_at TEXT, source_url TEXT
        );
        CREATE TABLE IF NOT EXISTS mosimo_bsaf_state (
          id INTEGER PRIMARY KEY CHECK(id = 1), cursor INTEGER
        );
        INSERT OR IGNORE INTO mosimo_bsaf_state(id,cursor) VALUES(1,NULL);
      `);
      const allowed = new Set(authors);
      for (const row of db.prepare('SELECT did FROM mosimo_bsaf_sources').all()) {
        if (!allowed.has(String(row.did))) {
          db.prepare(`DELETE FROM happyview_records WHERE did = ? AND collection = ?
            AND uri IN (SELECT uri FROM mosimo_bsaf_events)`).run(row.did, COLLECTION);
          db.prepare('DELETE FROM mosimo_bsaf_sources WHERE did = ?').run(row.did);
        }
      }
      for (const did of authors) db.prepare('INSERT OR IGNORE INTO mosimo_bsaf_sources VALUES(?)').run(did);
    });
    const advance = db.prepare('UPDATE mosimo_bsaf_state SET cursor = max(coalesce(cursor,0),?) WHERE id = 1');
    const sourceUrl = new URL(jetstreamUrl);
    sourceUrl.search = '';
    let ingesting = false;
    return {
      get cursor() {
        return db.prepare('SELECT cursor FROM mosimo_bsaf_state WHERE id = 1').get()!.cursor as number | null;
      },
      close() { db.close(); },
      async ingest(event) {
        if (ingesting) throw new Error('Await the previous ingest call before calling ingest again');
        ingesting = true;
        try {
          if (!object(event) || !Number.isSafeInteger(event.time_us) || (event.time_us as number) < 0) return 'rejected';
          const time = event.time_us as number;
          const commit = event.commit;
          if (event.kind !== 'commit' || typeof event.did !== 'string' || !object(commit) ||
              commit.collection !== COLLECTION || typeof commit.rkey !== 'string' ||
              !/^[A-Za-z0-9._~:-]{1,512}$/.test(commit.rkey) || ['.', '..'].includes(commit.rkey) ||
              !['create', 'update', 'delete'].includes(String(commit.operation))) {
            return transaction(() => { advance.run(time); return 'ignored' as const; });
          }
          const did = event.did;
          const rkey = commit.rkey;
          // Trust only the envelope DID, never source: tags or embedded original references.
          if (!db.prepare('SELECT 1 FROM mosimo_bsaf_sources WHERE did = ?').get(did)) {
            return transaction(() => { advance.run(time); return 'ignored' as const; });
          }
          const uri = `at://${did}/${COLLECTION}/${rkey}`;
          let valid = true;
          if (commit.operation !== 'delete') {
            try {
              valid = object(commit.record) && commit.record.$type === COLLECTION &&
                typeof commit.record.text === 'string' && typeof commit.record.createdAt === 'string' &&
                /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(commit.record.createdAt) &&
                Number.isFinite(Date.parse(commit.record.createdAt)) && typeof commit.cid === 'string' &&
                (await cidForLex(lex(commit.record))).toString() === commit.cid;
            } catch { valid = false; }
          }
          return transaction(() => {
            // Recheck inside the write transaction in case another process revoked a DID.
            if (!db.prepare('SELECT 1 FROM mosimo_bsaf_sources WHERE did = ?').get(did)) return 'ignored';
            advance.run(time);
            if (!valid) return 'rejected';
            const previous = db.prepare('SELECT time_us FROM mosimo_bsaf_events WHERE uri = ?').get(uri);
            if (previous && Number(previous.time_us) >= time) return 'stale';
            const record = commit.record;
            const keep = commit.operation !== 'delete' && object(record) && record.$type === COLLECTION &&
              Array.isArray(record.tags) && record.tags.includes('bsaf:v1');
            const now = new Date().toISOString();
            if (keep) {
              db.prepare(`INSERT INTO happyview_records(uri,did,collection,rkey,record,cid,indexed_at,created_at)
                VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(uri) DO UPDATE SET
                record=excluded.record,cid=excluded.cid,indexed_at=excluded.indexed_at`)
                .run(uri, did, COLLECTION, rkey, JSON.stringify(record), commit.cid as string, now, now);
            } else {
              db.prepare('DELETE FROM happyview_records WHERE uri = ? AND collection = ?').run(uri, COLLECTION);
            }
            db.prepare(`INSERT INTO mosimo_bsaf_events VALUES(?,?,?,?,?) ON CONFLICT(uri) DO UPDATE SET
              time_us=excluded.time_us,cid=excluded.cid,fetched_at=excluded.fetched_at,source_url=excluded.source_url`)
              .run(uri, time, keep ? commit.cid as string : null, now, sourceUrl.toString());
            return keep ? 'stored' : 'deleted';
          });
        } finally { ingesting = false; }
      },
    };
  } catch (error) {
    db.close();
    throw error instanceof CollectorError ? error : new CollectorError('initialization');
  }
}
