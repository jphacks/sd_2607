import pg from 'pg';

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

export async function openStore(connectionString) {
  const pool = new pg.Pool({ connectionString, max: 10, connectionTimeoutMillis: 10000 });
  pool.on('error', error => console.error('PostgreSQL connection lost:', error.message));
  const store = {
    query: (sql, values) => pool.query(sql, values),
    async transaction(fn) {
      const client = await pool.connect();
      try {
        await client.query('BEGIN');
        const result = await fn(client);
        await client.query('COMMIT');
        return result;
      } catch (error) {
        await client.query('ROLLBACK');
        throw error;
      } finally { client.release(); }
    },
    close: () => pool.end(),
  };
  await store.transaction(async tx => {
    await tx.query("SELECT pg_advisory_xact_lock(2607, 0)");
    await tx.query(schema);
  });
  return store;
}
