import { readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import { openStore, validateConfig, type Config } from './store.mts';

const trigger = 'xrpc.query:dev.oriom.mosimo.getFeed';
const body = readFileSync(new URL('./feed.lua', import.meta.url), 'utf8');

// HappyView resolves query scripts from SQLite on every request. No admin
// credential or lexicon/subscription mutation is needed for this local install.
export function prepare(config: Config): void {
  validateConfig(config);
  const db = new DatabaseSync(config.databasePath, { readOnly: true });
  try {
    const lexicon = db.prepare('SELECT lexicon_json,target_collection FROM happyview_lexicons WHERE id = ?')
      .get('dev.oriom.mosimo.getFeed');
    if (!lexicon || lexicon.target_collection !== 'dev.oriom.mosimo.post' ||
        JSON.parse(String(lexicon.lexicon_json)).defs?.main?.type !== 'query') {
      throw new Error('Register the Mosimo getFeed query with target dev.oriom.mosimo.post first');
    }
    const script = db.prepare('SELECT body,script_type FROM happyview_scripts WHERE id = ?').get(trigger);
    if (script) {
      const managed = db.prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'mosimo_bsaf_installation'").get();
      // A saved managed v1 body may advance to v2; the live body must still
      // match that exact saved version, never just a spoofable version marker.
      const previous = managed ? db.prepare('SELECT body FROM mosimo_bsaf_installation WHERE id = 1').get() : undefined;
      if (script.script_type !== 'lua' || script.body !== (previous?.body ?? body)) {
        throw new Error('Refusing to replace an independently edited getFeed script');
      }
    }
  } finally { db.close(); }

  openStore(config.databasePath, config.authors, config.jetstreamUrl).close();
  const writable = new DatabaseSync(config.databasePath);
  try {
    writable.exec('PRAGMA busy_timeout = 3000; BEGIN IMMEDIATE;');
    writable.exec(`CREATE TABLE IF NOT EXISTS mosimo_bsaf_installation (id INTEGER PRIMARY KEY CHECK(id=1),body TEXT NOT NULL);
          CREATE INDEX IF NOT EXISTS mosimo_bsaf_feed_order ON happyview_records(collection,indexed_at DESC,uri DESC);`);
    const now = new Date().toISOString();
    writable.prepare(`INSERT INTO happyview_scripts(id,body,script_type,description,created_at,updated_at)
      VALUES(?,?,'lua',?,?,?) ON CONFLICT(id) DO UPDATE SET body=excluded.body,updated_at=excluded.updated_at`)
      .run(trigger, body, 'Mosimo and explicitly approved BSAF originals', now, now);
    writable.prepare('INSERT INTO mosimo_bsaf_installation VALUES(1,?) ON CONFLICT(id) DO UPDATE SET body=excluded.body').run(body);
    writable.exec('COMMIT');
  } catch (error) {
    if (writable.isTransaction) writable.exec('ROLLBACK');
    throw error;
  } finally { writable.close(); }
}

if (import.meta.main) {
  const config: unknown = JSON.parse(readFileSync(process.env.CONFIG_FILE ?? '', 'utf8'));
  validateConfig(config);
  prepare(config);
  console.log(JSON.stringify({ status: 'prepared', feedVersion: 2, approvedAuthors: config.authors.length }));
}
