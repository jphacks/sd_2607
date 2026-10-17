import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { setImmediate, setTimeout } from 'node:timers/promises';
import { spawnSync } from 'node:child_process';
import { openSqliteStore, openStore, sqliteNow } from './store.mjs';
import { startNetwork } from './network.mjs';

async function fixture(t) {
  const directory = await mkdtemp(join(tmpdir(), 'mosimo-sqlite-'));
  const path = join(directory, 'pds.sqlite');
  const store = await openSqliteStore(path);
  t.after(async () => {
    await store.close();
    await rm(directory, { recursive: true, force: true });
  });
  return { store, path };
}
const insert = (tx, id) => tx.query('INSERT INTO events(id,pubkey,created_at,kind,event) VALUES($1,$2,$3,$4,$5)',
  [id, 'author', 1, 1, JSON.stringify({ id, tags: [['t', 'mosimo.oriom.dev']] })]);

test('SQLite requires an absolute path; DATABASE_PATH selects SQLite', async t => {
  await assert.rejects(openSqliteStore('relative.sqlite'), /absolute/);
  const { path } = await fixture(t);
  const original = process.env.DATABASE_PATH;
  try {
    process.env.DATABASE_PATH = path;
    await assert.rejects(openStore('postgres://unused'), /not both/);
    const selected = await openStore();
    assert.equal(selected.dialect, 'sqlite');
    await selected.close();
  } finally {
    if (original === undefined) delete process.env.DATABASE_PATH;
    else process.env.DATABASE_PATH = original;
  }
});

test('SQLite uses bounded memory, WAL, FULL durability, foreign keys and busy timeout', async t => {
  const { store } = await fixture(t);
  for (const [pragma, expected] of Object.entries({ foreign_keys: 1, journal_mode: 'wal', synchronous: 2,
    cache_size: -2048, busy_timeout: 5000, temp_store: 1, wal_autocheckpoint: 1000 })) {
    assert.equal(Object.values((await store.query(`PRAGMA ${pragma}`)).rows[0])[0], expected);
  }
});

test('SQLite persists JSON, blobs and retry defaults through reopen without losing integer precision', async t => {
  const { store, path } = await fixture(t);
  await store.transaction(async tx => {
    await insert(tx, 'event');
    await tx.query('INSERT INTO jobs(event_id) VALUES($1)', ['event']);
    await tx.query('INSERT INTO outbox(peer,event_id) VALUES($1,$2)', ['peer', 'event']);
    await tx.query('INSERT INTO blocks(cid,bytes) VALUES($1,$2)', ['block', Buffer.from([0, 255, 1])]);
    await tx.query('INSERT INTO group_sequences(group_id,seq) VALUES($1,$2)', ['group', 9223372036854775807n]);
  });
  await store.close();
  const reopened = await openSqliteStore(path);
  try {
    assert.deepEqual((await reopened.query('SELECT event FROM events')).rows[0].event,
      { id: 'event', tags: [['t', 'mosimo.oriom.dev']] });
    assert.deepEqual((await reopened.query('SELECT bytes FROM blocks')).rows[0].bytes, Buffer.from([0, 255, 1]));
    assert.equal((await reopened.query('SELECT seq FROM group_sequences')).rows[0].seq, '9223372036854775807');
    for (const table of ['jobs', 'outbox']) {
      assert.ok(Number.isFinite(Date.parse((await reopened.query(`SELECT next_attempt FROM ${table}`)).rows[0].next_attempt)));
    }
    assert.equal((await reopened.query('PRAGMA integrity_check')).rows[0].integrity_check, 'ok');
  } finally { await reopened.close(); }
});

for (const rollback of [false, true]) test(`SQLite serializes whole async transactions and external queries (${rollback ? 'rollback' : 'commit'})`, async t => {
  const { store } = await fixture(t);
  const entered = Promise.withResolvers();
  const release = Promise.withResolvers();
  const order = [];
  const transaction = store.transaction(async tx => {
    await insert(tx, 'inside');
    entered.resolve();
    await release.promise;
    order.push('transaction');
    if (rollback) throw new Error('abort');
  });
  // Attach rejection handling before the deliberate rollback.
  const completed = rollback ? assert.rejects(transaction, /abort/) : transaction;
  await entered.promise;
  const read = store.query('SELECT id FROM events').then(result => { order.push('read'); return result; });
  const write = insert(store, 'outside').then(() => order.push('write'));
  const second = store.transaction(async tx => {
    order.push('second');
    await insert(tx, 'second');
  });
  try {
    await setImmediate();
    assert.deepEqual(order, []);
  } finally { release.resolve(); }
  await Promise.all([completed, write, second]);
  assert.deepEqual((await read).rows.map(row => row.id), rollback ? [] : ['inside']);
  assert.deepEqual(order, ['transaction', 'read', 'write', 'second']);
  assert.deepEqual((await store.query('SELECT id FROM events ORDER BY arrival')).rows.map(row => row.id),
    rollback ? ['outside', 'second'] : ['inside', 'outside', 'second']);
});

test('SQLite rollback is atomic, releases the queue, and rejects escaped/nested sessions', async t => {
  const { store } = await fixture(t);
  let escaped;
  await assert.rejects(store.transaction(async tx => {
    escaped = tx;
    await insert(tx, 'rolled-back');
    await tx.query('INSERT INTO jobs(event_id) VALUES($1)', ['rolled-back']);
    await assert.rejects(store.query('SELECT 1'), /transaction session/);
    await assert.rejects(store.transaction(async () => {}), /transaction session/);
    await assert.rejects(store.close(), /inside a transaction/);
    throw new Error('rollback');
  }), /rollback/);
  await assert.rejects(escaped.query('SELECT 1'), /no longer active/);
  assert.deepEqual((await store.query('SELECT * FROM events')).rows, []);
  assert.deepEqual((await store.query('SELECT * FROM jobs')).rows, []);
  await store.transaction(tx => insert(tx, 'after'));
  assert.equal((await store.query('SELECT arrival FROM events')).rows[0].arrival, 1);
  // Numbered binding order is independent of its textual occurrence and can repeat.
  assert.deepEqual((await store.query('SELECT $2 AS second, $1 AS first, $2 AS again', ['a', 'b'])).rows,
    [{ second: 'b', first: 'a', again: 'b' }]);
});

test('SQLite enforces every foreign key and rolls back a statement failure', async t => {
  const { store } = await fixture(t);
  for (const [sql, values] of [
    ['INSERT INTO jobs(event_id) VALUES($1)', ['missing']],
    ['INSERT INTO outbox(peer,event_id) VALUES($1,$2)', ['peer', 'missing']],
    ['INSERT INTO repo_blocks(did,cid) VALUES($1,$2)', ['did', 'missing']],
    ['INSERT INTO repos(group_id,did,head,rev) VALUES($1,$2,$3,$4)', ['group', 'did', 'missing', 'rev']],
  ]) await assert.rejects(store.query(sql, values), /FOREIGN KEY/);
  await assert.rejects(store.transaction(async tx => {
    await insert(tx, 'rollback');
    await tx.query('INSERT INTO jobs(event_id) VALUES($1)', ['missing']);
  }), /FOREIGN KEY/);
  assert.deepEqual((await store.query('SELECT * FROM events')).rows, []);
  // A COMMIT failure must roll back too, not leave the connection in a transaction.
  await assert.rejects(store.transaction(async tx => {
    await tx.query('PRAGMA defer_foreign_keys=ON');
    await insert(tx, 'deferred');
    await tx.query('INSERT INTO jobs(event_id) VALUES($1)', ['missing']);
  }), /FOREIGN KEY/);
  assert.deepEqual((await store.query('SELECT * FROM events')).rows, []);
  await insert(store, 'parent');
  await store.query('INSERT INTO jobs(event_id) VALUES($1)', ['parent']);
  await assert.rejects(store.query('DELETE FROM events WHERE id=$1', ['parent']), /FOREIGN KEY/);
});

test('SQLite recovers committed WAL data after a process exits without closing', async t => {
  const { store, path } = await fixture(t);
  await store.close();
  const child = spawnSync(process.execPath, ['--input-type=module', '-e', `
    import { openSqliteStore } from ${JSON.stringify(new URL('./store.mts', import.meta.url).href)};
    const store = await openSqliteStore(${JSON.stringify(path)});
    await store.transaction(async tx => {
      await tx.query('INSERT INTO blocks(cid,bytes) VALUES($1,$2)', ['durable', Buffer.from([1,2,3])]);
    });
    process.exit(0);
  `], { encoding: 'utf8', timeout: 10000 });
  assert.equal(child.status, 0, child.stderr);
  const reopened = await openSqliteStore(path);
  try {
    assert.deepEqual((await reopened.query('SELECT bytes FROM blocks WHERE cid=$1', ['durable'])).rows[0].bytes,
      Buffer.from([1, 2, 3]));
  } finally { await reopened.close(); }
});

test('SQLite outbox uses real due-date SQL, retries durably, then deletes successful notifications', async t => {
  const { store } = await fixture(t);
  await insert(store, 'notification');
  const peer = { url: 'https://peer.example', tokenEnv: 'SQLITE_TEST_UNUSED_TOKEN' };
  await store.query('INSERT INTO outbox(peer,event_id) VALUES($1,$2)', [peer.url, 'notification']);
  let attempts = 0;
  t.mock.method(console, 'error', () => {});
  t.mock.method(globalThis, 'fetch', async url => {
    assert.equal(url, `${peer.url}/internal/notify`);
    attempts++;
    return new Response('{}', { status: attempts === 1 ? 503 : 200 });
  });
  const network = startNetwork({ config: { relays: [], peers: [peer] }, store,
    ingest: async () => assert.fail('Notification should not ingest') });
  try {
    const deadline = Date.now() + 5000;
    let row;
    do {
      assert.ok(Date.now() < deadline, 'Timed out waiting for persisted retry');
      await setTimeout(10);
      row = (await store.query('SELECT * FROM outbox')).rows[0];
    } while (row.attempts !== 1);
    assert.ok(Date.parse(row.next_attempt) > Date.now());
    assert.equal((await store.query(`SELECT * FROM outbox WHERE next_attempt<=${sqliteNow}`)).rowCount, 0);
    await network.onNotify({ eventId: 'notification' }); // Existing event avoids a peer fetch.
    assert.equal(attempts, 1);
    await store.query(`UPDATE outbox SET next_attempt=${sqliteNow}`);
    while ((await store.query('SELECT * FROM outbox')).rowCount) {
      assert.ok(Date.now() < deadline, 'Timed out waiting for successful notification');
      await setTimeout(10);
    }
    assert.equal(attempts, 2);
  } finally { await network.close(); }
});

test('SQLite close waits for in-flight transaction and queued queries, then rejects new work', async t => {
  const { store } = await fixture(t);
  const entered = Promise.withResolvers();
  const release = Promise.withResolvers();
  const transaction = store.transaction(async tx => {
    entered.resolve();
    await release.promise;
    await insert(tx, 'closing');
  });
  await entered.promise;
  const read = store.query('SELECT id FROM events');
  const closed = store.close();
  await assert.rejects(store.query('SELECT 1'), /closing/);
  release.resolve();
  await transaction;
  assert.equal((await read).rows[0].id, 'closing');
  await closed;
  await store.close();
});
