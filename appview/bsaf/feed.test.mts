import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { prepare } from './prepare.mts';

const body = readFileSync(new URL('./feed.lua', import.meta.url), 'utf8');
const trigger = 'xrpc.query:dev.oriom.mosimo.getFeed';
const mosimo = 'dev.oriom.mosimo.post';
const bsaf = 'dev.oriom.mosimo.defs#bsafV1';

test('managed v1 migrates to v2 idempotently, but independently edited scripts are preserved', t => {
  const directory = mkdtempSync(join(tmpdir(), 'mosimo-feed-'));
  const databasePath = join(directory, 'happyview.db');
  const db = new DatabaseSync(databasePath);
  t.after(() => { db.close(); rmSync(directory, { recursive: true, force: true }); });
  db.exec(`CREATE TABLE happyview_records(uri TEXT PRIMARY KEY,did TEXT,collection TEXT,rkey TEXT,record TEXT,cid TEXT,indexed_at TEXT,created_at TEXT);
    CREATE TABLE happyview_lexicons(id TEXT PRIMARY KEY,lexicon_json TEXT,target_collection TEXT);
    CREATE TABLE happyview_scripts(id TEXT PRIMARY KEY,body TEXT,script_type TEXT,description TEXT,created_at TEXT,updated_at TEXT);
    CREATE TABLE mosimo_bsaf_installation(id INTEGER PRIMARY KEY,body TEXT);`);
  db.prepare('INSERT INTO happyview_lexicons VALUES(?,?,?)').run('dev.oriom.mosimo.getFeed', '{"defs":{"main":{"type":"query"}}}', mosimo);
  const old = '-- mosimo-bsaf-feed:v1 (managed by appview/bsaf/prepare.mts)\nfunction handle() return {} end';
  db.prepare('INSERT INTO happyview_scripts VALUES(?,?,?,NULL,NULL,NULL)').run(trigger, old, 'lua');
  db.prepare('INSERT INTO mosimo_bsaf_installation VALUES(1,?)').run(old);
  const config = { databasePath, authors: [], jetstreamUrl: 'wss://jetstream.invalid/subscribe' };
  prepare(config);
  assert.match(body, /^-- mosimo-bsaf-feed:v2/);
  assert.equal(db.prepare('SELECT body FROM happyview_scripts').get()!.body, body);
  assert.equal(db.prepare('SELECT body FROM mosimo_bsaf_installation').get()!.body, body);
  prepare(config);
  db.prepare('UPDATE happyview_scripts SET body=?').run('custom script');
  assert.throws(() => prepare(config), /independently edited/);
  assert.equal(db.prepare('SELECT body FROM happyview_scripts').get()!.body, 'custom script');
});

test('actual feed SQL excludes historical imports in list and single lookup, preserving originals, ordinary posts and DB bytes', () => {
  // Execute the exact two SQL fragments/bind construction used by the Lua script.
  const fragments = /local sql = \[\[([\s\S]*?)\]\] \.\. filter \.\. \[\[([\s\S]*?)\]\] \.\. filter \.\. "([^"]+)"/.exec(body)!;
  assert.ok(fragments);
  const db = new DatabaseSync(':memory:');
  try {
    db.exec(`CREATE TABLE happyview_records(uri TEXT PRIMARY KEY,did TEXT,collection TEXT,cid TEXT,record TEXT,indexed_at TEXT);
      CREATE TABLE mosimo_bsaf_events(uri TEXT,cid TEXT,fetched_at TEXT,source_url TEXT);
      CREATE TABLE mosimo_bsaf_sources(did TEXT); INSERT INTO mosimo_bsaf_sources VALUES('bot');`);
    const put = (uri: string, collection: string, record: unknown, did = 'user') => db.prepare('INSERT INTO happyview_records VALUES(?,?,?,?,?,?)')
      .run(uri, did, collection, 'cid', JSON.stringify(record), '2026-10-18T00:00:00Z');
    put('source', 'app.bsky.feed.post', { text: 'original', tags: ['bsaf:v1'] }, 'bot');
    db.exec("INSERT INTO mosimo_bsaf_events VALUES('source','cid','now','wss://source.invalid')");
    put('import', mosimo, { text: 'historical footer retained', extensions: [{ $type: bsaf, original: { uri: 'source' } }] });
    put('malformed-import', mosimo, { extensions: [{ $type: bsaf, original: null }] });
    put('ordinary', mosimo, { text: 'user', extensions: [] });
    put('ordinary-bsaf', mosimo, { extensions: [{ $type: bsaf, tags: ['bsaf:v1'] }] });
    put('unknown', mosimo, { extensions: [null, 'unknown', 42, { $type: 'unknown', original: {} }] });
    put('malformed-extensions', mosimo, { extensions: 'unknown' });
    const before = db.prepare('SELECT * FROM happyview_records ORDER BY uri').all();
    const query = (uri?: string) => {
      const filter = uri ? ' AND r.uri = ? ' : '';
      const sql = fragments[1] + filter + fragments[2] + filter + fragments[3];
      return db.prepare(sql).all(...(uri ? [uri, uri] : []), 101, 0).map(row => row.uri);
    };
    assert.deepEqual(new Set(query()), new Set(['source','ordinary','ordinary-bsaf','unknown','malformed-extensions']));
    assert.deepEqual(query('source'), ['source']);
    assert.deepEqual(query('import'), []);
    assert.deepEqual(query('malformed-import'), []);
    assert.deepEqual(query('ordinary-bsaf'), ['ordinary-bsaf']);
    assert.deepEqual(db.prepare('SELECT * FROM happyview_records ORDER BY uri').all(), before);
  } finally { db.close(); }
});
