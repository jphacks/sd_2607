import pg from 'pg';
import { DatabaseSync } from 'node:sqlite';
import type { SQLInputValue } from 'node:sqlite';
import { isAbsolute } from 'node:path';
import { AsyncLocalStorage } from 'node:async_hooks';

export type Row = Record<string, unknown>;
export type QueryResult = { rows: Row[]; rowCount: number };
export interface Session {
  query(sql: string, values?: unknown[]): Promise<QueryResult>;
}
export interface Store extends Session {
  dialect: 'postgres' | 'sqlite';
  transaction<T>(fn: (tx: Session) => Promise<T>): Promise<T>;
  close(): Promise<void>;
}

export const schema = `
CREATE TABLE IF NOT EXISTS events (
  id text PRIMARY KEY, pubkey text NOT NULL, created_at bigint NOT NULL,
  kind int NOT NULL, event jsonb NOT NULL, arrival bigserial UNIQUE NOT NULL
);
CREATE INDEX IF NOT EXISTS events_created ON events(created_at DESC, id);
CREATE INDEX IF NOT EXISTS events_author ON events(pubkey);
CREATE TABLE IF NOT EXISTS deletions (
  event_id text NOT NULL, pubkey text NOT NULL, created_at bigint NOT NULL,
  PRIMARY KEY(event_id,pubkey)
);
CREATE TABLE IF NOT EXISTS jobs (
  event_id text PRIMARY KEY REFERENCES events(id), status text NOT NULL DEFAULT 'pending',
  error text, attempts int NOT NULL DEFAULT 0, next_attempt timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS commits (
  cid text PRIMARY KEY, did text NOT NULL, rev text NOT NULL, base text,
  entries jsonb NOT NULL, car bytea NOT NULL, commit_bytes bytea NOT NULL
);
CREATE TABLE IF NOT EXISTS blocks (cid text PRIMARY KEY, bytes bytea NOT NULL);
CREATE TABLE IF NOT EXISTS repo_blocks (
  did text NOT NULL, cid text NOT NULL REFERENCES blocks(cid), PRIMARY KEY(did,cid)
);
CREATE TABLE IF NOT EXISTS repos (
  group_id text NOT NULL, did text NOT NULL, head text NOT NULL REFERENCES commits(cid),
  rev text NOT NULL, PRIMARY KEY(group_id,did)
);
CREATE TABLE IF NOT EXISTS group_sequences (group_id text PRIMARY KEY, seq bigint NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS streams (
  group_id text NOT NULL, seq bigint NOT NULL, frame bytea NOT NULL, PRIMARY KEY(group_id,seq)
);
CREATE TABLE IF NOT EXISTS outbox (
  peer text NOT NULL, event_id text NOT NULL REFERENCES events(id), attempts int NOT NULL DEFAULT 0,
  next_attempt timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(peer,event_id)
);
`;

// ISO UTC text keeps retry ordering and Date parsing identical across backends.
export const sqliteNow = "strftime('%Y-%m-%dT%H:%M:%fZ','now')";
export const sqliteSchema = `
CREATE TABLE IF NOT EXISTS events (
  arrival INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL,
  pubkey TEXT NOT NULL, created_at INTEGER NOT NULL, kind INTEGER NOT NULL,
  event TEXT NOT NULL CHECK(json_valid(event))
);
CREATE INDEX IF NOT EXISTS events_created ON events(created_at DESC, id);
CREATE INDEX IF NOT EXISTS events_author ON events(pubkey);
CREATE TABLE IF NOT EXISTS deletions (
  event_id TEXT NOT NULL, pubkey TEXT NOT NULL, created_at INTEGER NOT NULL,
  PRIMARY KEY(event_id,pubkey)
);
CREATE TABLE IF NOT EXISTS jobs (
  event_id TEXT PRIMARY KEY NOT NULL REFERENCES events(id), status TEXT NOT NULL DEFAULT 'pending',
  error TEXT, attempts INTEGER NOT NULL DEFAULT 0, next_attempt TEXT NOT NULL DEFAULT (${sqliteNow})
);
CREATE TABLE IF NOT EXISTS commits (
  cid TEXT PRIMARY KEY NOT NULL, did TEXT NOT NULL, rev TEXT NOT NULL, base TEXT,
  entries TEXT NOT NULL CHECK(json_valid(entries)), car BLOB NOT NULL, commit_bytes BLOB NOT NULL
);
CREATE TABLE IF NOT EXISTS blocks (cid TEXT PRIMARY KEY NOT NULL, bytes BLOB NOT NULL);
CREATE TABLE IF NOT EXISTS repo_blocks (
  did TEXT NOT NULL, cid TEXT NOT NULL REFERENCES blocks(cid), PRIMARY KEY(did,cid)
);
CREATE TABLE IF NOT EXISTS repos (
  group_id TEXT NOT NULL, did TEXT NOT NULL, head TEXT NOT NULL REFERENCES commits(cid),
  rev TEXT NOT NULL, PRIMARY KEY(group_id,did)
);
CREATE TABLE IF NOT EXISTS group_sequences (group_id TEXT PRIMARY KEY NOT NULL, seq INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS streams (
  group_id TEXT NOT NULL, seq INTEGER NOT NULL, frame BLOB NOT NULL, PRIMARY KEY(group_id,seq)
);
CREATE TABLE IF NOT EXISTS outbox (
  peer TEXT NOT NULL, event_id TEXT NOT NULL REFERENCES events(id), attempts INTEGER NOT NULL DEFAULT 0,
  next_attempt TEXT NOT NULL DEFAULT (${sqliteNow}), PRIMARY KEY(peer,event_id)
);
`;

/** SQLite requires one physical node and local durable disk, never a shared/network filesystem. */
export async function openSqliteStore(path: string): Promise<Store> {
  if (!isAbsolute(path)) throw new Error('DATABASE_PATH must be an absolute local-disk path');
  const db = new DatabaseSync(path);
  try {
    db.exec(`PRAGMA busy_timeout=5000; PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL;
      PRAGMA synchronous=FULL; PRAGMA cache_size=-2048; PRAGMA temp_store=FILE;
      PRAGMA wal_autocheckpoint=1000; BEGIN IMMEDIATE; ${sqliteSchema} COMMIT;`);
  } catch (error) {
    db.close();
    throw error;
  }
  let pending: Promise<unknown> = Promise.resolve();
  let closing = false;
  let closeTask: Promise<void> | undefined;
  let broken = false;
  const context = new AsyncLocalStorage<boolean>();
  function enqueue<T>(fn: () => T | Promise<T>): Promise<T> {
    if (context.getStore()) return Promise.reject(new Error('Use the transaction session, not the store, inside a transaction'));
    if (closing) return Promise.reject(new Error('Store is closing'));
    const run = pending.then(() => {
      if (broken) throw new Error('SQLite rollback failed; reopen the store');
      return fn();
    });
    pending = run.catch(() => {});
    return run;
  }
  function query(sql: string, values: unknown[] = []): QueryResult {
    const statement = db.prepare(sql);
    statement.setReadBigInts(true);
    // Bind by name: $2 may precede $1 or appear more than once. No SQL rewriting.
    const bindings: Record<string, SQLInputValue> = {};
    values.forEach((value, index) => {
      if (value !== null && typeof value !== 'string' && typeof value !== 'number'
        && typeof value !== 'bigint' && !(value instanceof Uint8Array)) {
        throw new TypeError('SQLite parameters must be scalar values or bytes');
      }
      bindings[`$${index + 1}`] = value;
    });
    if (!statement.columns().length) {
      const result = statement.run(bindings);
      return { rows: [], rowCount: Number(result.changes) };
    }
    const rows = statement.all(bindings).map(row => Object.fromEntries(Object.entries(row).map(([key, value]) => {
      if ((key === 'event' || key === 'entries') && typeof value === 'string') return [key, JSON.parse(value) as unknown];
      if (value instanceof Uint8Array) return [key, Buffer.from(value)];
      if (typeof value === 'bigint') return [key, value <= BigInt(Number.MAX_SAFE_INTEGER) && value >= BigInt(Number.MIN_SAFE_INTEGER) ? Number(value) : value.toString()];
      return [key, value];
    })));
    return { rows, rowCount: rows.length };
  }
  return {
    dialect: 'sqlite',
    query: (sql, values) => enqueue(() => query(sql, values)),
    transaction: fn => enqueue(async () => {
      db.exec('BEGIN IMMEDIATE');
      let active = true;
      const tx: Session = {
        async query(sql, values) {
          if (!active) throw new Error('Transaction session is no longer active');
          return query(sql, values);
        },
      };
      try {
        const result = await context.run(true, () => fn(tx));
        active = false;
        db.exec('COMMIT');
        return result;
      } catch (error) {
        active = false;
        try { db.exec('ROLLBACK'); }
        catch (rollbackError) {
          broken = true;
          throw new AggregateError([error, rollbackError], 'SQLite transaction and rollback failed');
        }
        throw error;
      }
    }),
    close() {
      if (context.getStore()) return Promise.reject(new Error('Cannot close inside a transaction'));
      closing = true;
      return closeTask ??= pending.then(() => db.close());
    },
  };
}

/** Refuse ambiguous backend configuration rather than silently changing public history. */
export async function openStore(connectionString?: string): Promise<Store> {
  if (process.env.DATABASE_PATH !== undefined) {
    if (connectionString) throw new Error('Set DATABASE_PATH or DATABASE_URL, not both');
    return openSqliteStore(process.env.DATABASE_PATH);
  }
  const pool = new pg.Pool({ connectionString, max: 10, connectionTimeoutMillis: 10000 });
  pool.on('error', error => console.error('PostgreSQL connection lost:', error.message));
  const store: Store = {
    dialect: 'postgres',
    query: async (sql, values) => {
      const result = await pool.query(sql, values);
      return { rows: result.rows, rowCount: result.rowCount ?? 0 };
    },
    async transaction(fn) {
      const client = await pool.connect();
      let discard = false;
      try {
        await client.query('BEGIN');
        const result = await fn({ query: async (sql, values) => {
          const result = await client.query(sql, values);
          return { rows: result.rows, rowCount: result.rowCount ?? 0 };
        } });
        await client.query('COMMIT');
        return result;
      } catch (error) {
        try { await client.query('ROLLBACK'); }
        catch (rollbackError) {
          discard = true;
          throw new AggregateError([error, rollbackError], 'PostgreSQL transaction and rollback failed');
        }
        throw error;
      } finally { client.release(discard); }
    },
    close: () => pool.end(),
  };
  try {
    await store.transaction(async tx => {
      await tx.query('SELECT pg_advisory_xact_lock(2607, 0)');
      await tx.query(schema);
    });
    return store;
  } catch (error) {
    await pool.end();
    throw error;
  }
}
