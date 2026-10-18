import { readFile, stat } from 'node:fs/promises';
import { statSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import { setTimeout as delay } from 'node:timers/promises';
import WebSocket from 'ws';
import { cidForLex } from '@atproto/lex-cbor';
import { parseCid } from '@atproto/lex-data';
import { object } from './store.mts';
import { createImporterEvent, verifyPublicInformation, verifySnapshot, decodeCanonical,
  verifyBsaf, BSAF, JMA_SELECTION, rootId, timestamp, keyPublic } from '../../pds/public-information.mjs';

export interface Selection { botDid: string; types: string[] }
export interface PublisherConfig {
  enabled: boolean;
  databasePath: string;
  credentialsPath: string;
  trustPath: string;
  relayUrl: string;
  selection: Selection[];
  maxOriginalAgeSeconds: number;
  priority?: 0 | 1 | 2 | 3 | null;
  jmaPriorityPolicy?: boolean;
}
// Local delivery hints only, never an interpretation of source:jma or original agency authority.
const jmaPriorityRules: ReadonlyArray<{ type: string; value: string; level: 2 | 3 }> = [
  { type: 'tsunami', value: 'severe-warning', level: 3 },
  { type: 'tsunami', value: 'special-warning', level: 3 },
  { type: 'tsunami', value: 'warning', level: 2 },
  { type: 'tsunami', value: 'advisory', level: 2 },
  { type: 'nankai-trough', value: 'warning', level: 3 },
  { type: 'nankai-trough', value: 'advisory', level: 2 },
  { type: 'eruption', value: 'warning', level: 3 },
  { type: 'eruption', value: 'advisory', level: 2 },
  { type: 'earthquake', value: '5-', level: 2 },
  { type: 'earthquake', value: '5+', level: 2 },
  { type: 'earthquake', value: '6-', level: 3 },
  { type: 'earthquake', value: '6+', level: 3 },
  { type: 'earthquake', value: '7', level: 3 },
];
function selectedJmaRules(config: PublisherConfig) {
  if (!config.enabled || config.priority != null || config.jmaPriorityPolicy === false) return [];
  const selected = config.selection.find(e => e.botDid === JMA_SELECTION[0]!.botDid);
  return jmaPriorityRules.filter(rule => selected?.types.includes(rule.type));
}
export function publicationPriority(config: PublisherConfig, botDid: string, tags: string[]): 0 | 1 | 2 | 3 {
  if (config.priority != null) return config.priority;
  if (botDid !== JMA_SELECTION[0]!.botDid) return 1;
  const types = tags.filter(t => t.startsWith('type:'));
  const values = tags.filter(t => t.startsWith('value:'));
  if (types.length !== 1 || values.length !== 1) return 1;
  return selectedJmaRules(config).find(rule => types[0] === `type:${rule.type}` && values[0] === `value:${rule.value}`)?.level ?? 1;
}
export function validatePublisherConfig(value: unknown): asserts value is PublisherConfig {
  if (!object(value) || typeof value.enabled !== 'boolean' || typeof value.databasePath !== 'string' || !value.databasePath
    || typeof value.credentialsPath !== 'string' || !value.credentialsPath || typeof value.trustPath !== 'string' || !value.trustPath
    || typeof value.relayUrl !== 'string' || !Array.isArray(value.selection) || value.selection.length > 16
    || !value.selection.every(e => object(e) && typeof e.botDid === 'string' && /^did:plc:[a-z2-7]{24}$/.test(e.botDid)
      && Array.isArray(e.types) && e.types.length > 0 && e.types.length <= 32 && new Set(e.types).size === e.types.length
      && e.types.every(t => typeof t === 'string' && Buffer.byteLength(t) >= 1 && Buffer.byteLength(t) <= 128 && !/[\x00-\x1f\x7f]/.test(t)))
    || new Set(value.selection.map(e => e.botDid)).size !== value.selection.length
    || value.selection.reduce((n, e) => n + e.types.length, 0) > 64
    || !Number.isSafeInteger(value.maxOriginalAgeSeconds) || Number(value.maxOriginalAgeSeconds) < 1 || Number(value.maxOriginalAgeSeconds) > 86400
    || (value.priority != null && (typeof value.priority !== 'number' || ![0, 1, 2, 3].includes(value.priority)))
    || (value.jmaPriorityPolicy !== undefined && typeof value.jmaPriorityPolicy !== 'boolean')) throw new Error('Invalid publisher configuration');
  const url = new URL(value.relayUrl);
  if (url.protocol !== 'wss:' || url.username || url.password || url.hash) throw new Error('Publisher requires wss relay');
}
// This connection is deliberately to the existing collector database, not a generic source hook.
export function openPublicationStore(path: string) {
  if (!statSync(path).isFile()) throw new Error('Existing collector DB required');
  const db = new DatabaseSync(path, { open: false });
  db.open();
  db.exec('PRAGMA busy_timeout=3000; PRAGMA foreign_keys=ON;');
  db.prepare(`SELECT r.uri,r.did,r.rkey,r.record,r.cid,e.cid,e.fetched_at,e.source_url,e.time_us
    FROM happyview_records r JOIN mosimo_bsaf_events e ON e.uri=r.uri
    JOIN mosimo_bsaf_sources s ON s.did=r.did LIMIT 0`).all();
  db.exec(`
    CREATE TABLE IF NOT EXISTS mosimo_bsaf_publications (
      seq INTEGER PRIMARY KEY, uri TEXT NOT NULL, cid TEXT NOT NULL, original_json TEXT NOT NULL,
      provenance_json TEXT NOT NULL, event_json TEXT, deadline TEXT, status TEXT NOT NULL,
      attempts INTEGER NOT NULL DEFAULT 0, next_attempt INTEGER NOT NULL DEFAULT 0,
      UNIQUE(uri,cid)
    );
    CREATE TABLE IF NOT EXISTS mosimo_bsaf_repository (id INTEGER PRIMARY KEY CHECK(id=1), identity TEXT NOT NULL, state_json TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS mosimo_bsaf_snapshots (root_id TEXT PRIMARY KEY, snapshot_json TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS mosimo_bsaf_certificate_serials (issuer TEXT NOT NULL, serial TEXT NOT NULL, cert_id TEXT NOT NULL, PRIMARY KEY(issuer,serial));
    CREATE TABLE IF NOT EXISTS mosimo_bsaf_certificate_equivocations (issuer TEXT NOT NULL, serial TEXT NOT NULL, PRIMARY KEY(issuer,serial));
  `);
  let busy = false;
  // Security evidence must survive rollback of the publication that discovered it.
  const equivocations = new Map<string, [string, string]>();
  function flushEquivocations() {
    for (const [key, [issuer, serial]] of equivocations) {
      db.prepare('INSERT OR IGNORE INTO mosimo_bsaf_certificate_equivocations VALUES(?,?)').run(issuer, serial);
      equivocations.delete(key);
    }
  }
  return {
    db,
    recordCertificate(issuer: string, serial: string, certId: string) {
      const standalone = !busy;
      if (standalone) db.exec('BEGIN IMMEDIATE');
      try {
        const key = `${issuer}:${serial}`;
        if (equivocations.has(key) || db.prepare('SELECT 1 FROM mosimo_bsaf_certificate_equivocations WHERE issuer=? AND serial=?').get(issuer, serial)) {
          throw new Error('Certificate serial equivocation');
        }
        const previous = db.prepare('SELECT cert_id FROM mosimo_bsaf_certificate_serials WHERE issuer=? AND serial=?').get(issuer, serial);
        if (previous && previous.cert_id !== certId) {
          equivocations.set(key, [issuer, serial]);
          throw new Error('Certificate serial equivocation');
        }
        db.prepare('INSERT OR IGNORE INTO mosimo_bsaf_certificate_serials VALUES(?,?,?)').run(issuer, serial, certId);
        if (standalone) db.exec('COMMIT');
      } catch (error) {
        if (standalone) db.exec('ROLLBACK');
        throw error;
      } finally { if (standalone) flushEquivocations(); }
    },
    async transaction<T>(fn: () => Promise<T>): Promise<T> {
      if (busy) throw new Error('Overlapping publisher operation');
      busy = true;
      try {
        flushEquivocations();
        db.exec('BEGIN IMMEDIATE');
        try { const result = await fn(); db.exec('COMMIT'); return result; }
        catch (error) { db.exec('ROLLBACK'); throw error; }
      } finally {
        try { flushEquivocations(); } finally { busy = false; }
      }
    },
    close() { db.close(); },
  };
}
export type PublicationStore = ReturnType<typeof openPublicationStore>;
export function snapshotStore(store: PublicationStore, roots: any[], nowSeconds: number) {
  const { db } = store;
  return {
    get(id: string) {
      const row = db.prepare('SELECT snapshot_json FROM mosimo_bsaf_snapshots WHERE root_id=?').get(id);
      if (!row) throw new Error('No fresh snapshot');
      return JSON.parse(String(row.snapshot_json));
    },
    async accept(id: string, snapshot: any) {
      const root = roots.find(r => rootId(r.rootPubkey) === id && r.enabled === true);
      if (!root) throw new Error('Unknown root');
      const previous = db.prepare('SELECT snapshot_json FROM mosimo_bsaf_snapshots WHERE root_id=?').get(id);
      // The caller holds BEGIN IMMEDIATE across this read/verify/write.
      await verifySnapshot(snapshot, { root, nowSeconds, previous: previous ? JSON.parse(String(previous.snapshot_json)) : null });
      db.prepare(`INSERT INTO mosimo_bsaf_snapshots VALUES(?,?) ON CONFLICT(root_id) DO UPDATE SET snapshot_json=excluded.snapshot_json`)
        .run(id, JSON.stringify(snapshot));
    },
    recordCertificate: store.recordCertificate,
  };
}
export async function cacheSnapshot(store: PublicationStore, roots: any[], snapshot: any, nowSeconds: number) {
  return store.transaction(() => snapshotStore(store, roots, nowSeconds).accept(snapshot.body.rootId, snapshot));
}
function lex(value: any): any {
  if (Array.isArray(value)) return value.map(lex);
  if (object(value)) {
    if (Object.keys(value).length === 1 && typeof value.$link === 'string') return parseCid(value.$link);
    if (Object.keys(value).length === 1 && typeof value.$bytes === 'string') return Uint8Array.from(Buffer.from(value.$bytes, 'base64'));
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, lex(v)]));
  }
  return value;
}
// Retain every field locally, but don't claim that plain text preserves an attachment/reply/label's meaning.
const supportedFields = new Set(['$type', 'text', 'createdAt', 'tags', 'langs', 'facets']);
function safePlainRecord(record: any) {
  if (!object(record) || record.$type !== 'app.bsky.feed.post' || typeof record.text !== 'string'
    || Object.keys(record).some(k => !supportedFields.has(k))) throw new Error('Unsupported source semantics');
  if (record.facets !== undefined) {
    if (!Array.isArray(record.facets)) throw new Error('Unsupported facets');
    for (const facet of record.facets) {
      if (!object(facet) || !object(facet.index) || !Number.isInteger(facet.index.byteStart) || !Number.isInteger(facet.index.byteEnd)
        || Number(facet.index.byteStart) < 0 || Number(facet.index.byteEnd) <= Number(facet.index.byteStart)
        || Number(facet.index.byteEnd) > Buffer.byteLength(record.text) || !Array.isArray(facet.features)
        || facet.features.length !== 1 || !object(facet.features[0])) throw new Error('Unsupported facets');
      if (Object.keys(facet).some(k => !['index', 'features'].includes(k))
        || Object.keys(facet.index).some(k => !['byteStart', 'byteEnd'].includes(k))) throw new Error('Unsupported facet fields');
      const f = facet.features[0];
      if (Object.keys(f).some(k => !['$type', 'uri'].includes(k))) throw new Error('Unsupported facet feature');
      const display = Buffer.from(record.text).subarray(Number(facet.index.byteStart), Number(facet.index.byteEnd)).toString('utf8');
      // Only an already-visible literal URL loses no semantics when its facet is omitted.
      if (f.$type !== 'app.bsky.richtext.facet#link' || typeof f.uri !== 'string' || display !== f.uri) throw new Error('Nonliteral facet');
    }
  }
}
export async function stageNext(store: PublicationStore, config: PublisherConfig, credentials: any, roots: any[], nowSeconds: number) {
  validatePublisherConfig(config);
  if (!config.enabled || config.selection.length === 0) return null;
  return store.transaction(async () => {
    const { db } = store;
    const existing = db.prepare(`SELECT * FROM mosimo_bsaf_publications WHERE status='pending' ORDER BY seq LIMIT 1`).get();
    if (existing) return existing;
    if (Number(db.prepare('SELECT count(*) n FROM mosimo_bsaf_publications').get()!.n) >= 10000) throw new Error('Publication capacity reached');
    const selectedSql = config.selection.map(e => `(r.did=? AND EXISTS (SELECT 1 FROM json_each(r.record,'$.tags') jt WHERE jt.value IN (${e.types.map(() => '?').join(',')})))`).join(' OR ');
    const selectedParams = config.selection.flatMap(e => [e.botDid, ...e.types.map(t => `type:${t}`)]);
    const priorityRules = selectedJmaRules(config);
    const priorityOrder = priorityRules.length ? `CASE ${priorityRules.map(rule => `WHEN r.did=?
      AND EXISTS (SELECT 1 FROM json_each(r.record,'$.tags') jt WHERE jt.value=?)
      AND EXISTS (SELECT 1 FROM json_each(r.record,'$.tags') jt WHERE jt.value=?) THEN ${rule.level}`).join(' ')} ELSE 1 END DESC,` : '';
    const priorityParams = priorityRules.flatMap(rule => [JMA_SELECTION[0]!.botDid, `type:${rule.type}`, `value:${rule.value}`]);
    const rows = db.prepare(`SELECT r.*,e.fetched_at,e.source_url,e.time_us FROM happyview_records r
      JOIN mosimo_bsaf_events e ON e.uri=r.uri AND e.cid=r.cid JOIN mosimo_bsaf_sources s ON s.did=r.did
      WHERE r.collection='app.bsky.feed.post' AND length(CAST(r.record AS BLOB))<=262144 AND (${selectedSql}) AND NOT EXISTS
      (SELECT 1 FROM mosimo_bsaf_publications p WHERE p.uri=r.uri AND p.cid=r.cid)
      ORDER BY ${priorityOrder} e.time_us,r.uri LIMIT 8`).all(...selectedParams, ...priorityParams);
    for (const row of rows) {
      if (!config.selection.some(e => e.botDid === row.did)) continue;
      let record: any, extension: any, deadline: string;
      const provenance = { uri: row.uri, did: row.did, cid: row.cid, rkey: row.rkey,
        fetchedAt: row.fetched_at, sourceUrl: row.source_url, timeUs: row.time_us,
        attribution: 'collector-jetstream-envelope; original repository signature/inclusion not verified' };
      try {
        if (Buffer.byteLength(String(row.record)) > 262144) throw new Error('Source size');
        record = JSON.parse(String(row.record));
        if (row.uri !== `at://${row.did}/app.bsky.feed.post/${row.rkey}` || typeof row.source_url !== 'string'
          || !['wss:', 'ws:'].includes(new URL(row.source_url).protocol) || typeof row.fetched_at !== 'string'
          || !Number.isFinite(Date.parse(row.fetched_at)) || row.did === credentials.importer.did
          || (await cidForLex(lex(record))).toString() !== row.cid) throw new Error('Source envelope/CID mismatch');
        safePlainRecord(record);
        extension = { $type: BSAF, tags: record.tags, langs: record.langs ?? [],
          original: { uri: row.uri, cid: row.cid, createdAt: record.createdAt } };
        const selected = verifyBsaf(extension);
        if (!config.selection.some(e => e.botDid === selected.botDid && e.types.includes(selected.type))) continue;
        const originalSeconds = Math.floor(Date.parse(record.createdAt) / 1000);
        if (originalSeconds > nowSeconds || originalSeconds + config.maxOriginalAgeSeconds <= nowSeconds) throw new Error('Source age');
        deadline = timestamp(originalSeconds + config.maxOriginalAgeSeconds);
      } catch {
        db.prepare(`INSERT INTO mosimo_bsaf_publications(uri,cid,original_json,provenance_json,status) VALUES(?,?,?,?,'held')`)
          .run(row.uri, row.cid, row.record, JSON.stringify(provenance));
        continue;
      }
      const importer = credentials.importer;
      const identity = `${importer.did}:${keyPublic(importer.atprotoSecret)}:${importer.binding.declaration.nostrPubkey}`;
      const previous = db.prepare('SELECT * FROM mosimo_bsaf_repository WHERE id=1').get();
      if (previous && previous.identity !== identity) throw new Error('Repository identity changed; explicit migration required');
      const result = await createImporterEvent({ importer, chain: credentials.chain,
        text: `${record.text}\n\n[BSAF再配信 / importer: ${importer.did}]\n原投稿著者: ${row.did}\n原投稿: ${row.uri}\n取得経路: AppView/Jetstream（原Bot・気象庁の署名を検証したものではありません）`,
        extensions: [extension], nowSeconds, deliveryExpiresAt: deadline,
        state: previous ? JSON.parse(String(previous.state_json)) : undefined,
                priority: publicationPriority(config, String(row.did), record.tags) });
      await verifyPublicInformation(result.event, { roots, snapshots: snapshotStore(store, roots, nowSeconds), nowSeconds, selection: config.selection });
      db.prepare(`INSERT INTO mosimo_bsaf_publications(uri,cid,original_json,provenance_json,event_json,deadline,status) VALUES(?,?,?,?,?,?,'pending')`)
        .run(row.uri, row.cid, row.record, JSON.stringify(provenance), JSON.stringify(result.event), deadline);
      db.prepare(`INSERT INTO mosimo_bsaf_repository VALUES(1,?,?) ON CONFLICT(id) DO UPDATE SET state_json=excluded.state_json`)
        .run(identity, JSON.stringify(result.state));
      return db.prepare(`SELECT * FROM mosimo_bsaf_publications WHERE uri=? AND cid=?`).get(row.uri, row.cid)!;
    }
    return null;
  });
}
export function publishEvent(url: string, event: any, signal: AbortSignal, timeoutMs = 10000): Promise<boolean> {
  return new Promise((resolve) => {
    const ws = new WebSocket(url, { maxPayload: 16384, handshakeTimeout: timeoutMs, perMessageDeflate: false });
    let done = false;
    function finish(ok: boolean) {
      if (done) return; done = true; clearTimeout(timer); signal.removeEventListener('abort', abort); ws.terminate(); resolve(ok);
    }
    const abort = () => finish(false);
    const timer = setTimeout(() => finish(false), timeoutMs);
    signal.addEventListener('abort', abort, { once: true });
    ws.on('error', () => finish(false)); ws.on('close', () => finish(false));
    ws.on('open', () => { if (signal.aborted) finish(false); else ws.send(JSON.stringify(['EVENT', event])); });
    ws.on('message', (data) => {
      try { const msg = JSON.parse(data.toString());
        if (Array.isArray(msg) && msg.length === 4 && msg[0] === 'OK' && msg[1] === event.id && typeof msg[2] === 'boolean' && typeof msg[3] === 'string') finish(msg[2] === true);
      } catch { /* Malformed/unrelated responses cannot acknowledge a publication. */ }
    });
    if (signal.aborted) finish(false);
  });
}
export async function pushOnce(store: PublicationStore, config: PublisherConfig, credentials: any, roots: any[], nowSeconds: number,
  signal: AbortSignal, send = publishEvent) {
  const row = await stageNext(store, config, credentials, roots, nowSeconds);
  if (!row || Number(row.next_attempt) > nowSeconds) return false;
  const event = JSON.parse(String(row.event_json));
  // Recheck freshness, revocation, selection and deadline at the actual send, including retries.
  await store.transaction(() => verifyPublicInformation(event, { roots, snapshots: snapshotStore(store, roots, nowSeconds), nowSeconds, selection: config.selection }));
  const accepted = await send(config.relayUrl, event, signal);
  await store.transaction(async () => {
    const attempts = Number(row.attempts) + 1;
    store.db.prepare(`UPDATE mosimo_bsaf_publications SET status=?,attempts=?,next_attempt=? WHERE seq=? AND status='pending'`)
      .run(accepted ? 'accepted' : 'pending', attempts, nowSeconds + Math.min(300, 2 ** Math.min(attempts, 8)), row.seq);
  });
  return accepted;
}
export async function refreshSnapshots(store: PublicationStore, roots: any[], nowSeconds: number) {
  let refreshed = 0, failed = 0;
  for (const root of roots) {
    if (!root.enabled) continue;
    try {
      const url = new URL(root.snapshotUrl);
      if (url.protocol !== 'https:' || url.username || url.password || url.hash) throw new Error('Invalid snapshot URL');
      const response = await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(10000) });
      if (!response.ok || response.headers.get('content-type')?.split(';')[0] !== 'application/cbor') throw new Error('Snapshot HTTP format');
      let size = 0; const chunks: Uint8Array[] = [];
      if (!response.body) throw new Error('Snapshot HTTP body');
      for await (const chunk of response.body) { size += chunk.length; if (size > 32768) throw new Error('Snapshot size'); chunks.push(chunk); }
      const snapshot = decodeCanonical(Buffer.concat(chunks));
      if (snapshot.body.rootId !== rootId(root.rootPubkey)) throw new Error('Snapshot URL/root mismatch');
      await cacheSnapshot(store, roots, snapshot, nowSeconds);
      refreshed++;
    } catch { failed++; }
  }
  return { refreshed, failed };
}
export async function main() {
  if (!process.env.PUBLISHER_CONFIG_FILE) throw new Error('PUBLISHER_CONFIG_FILE required');
  const config: unknown = JSON.parse(await readFile(process.env.PUBLISHER_CONFIG_FILE, 'utf8'));
  validatePublisherConfig(config);
  if (!config.enabled || !config.selection.length) return;
  if (!(await stat(config.databasePath)).isFile()) throw new Error('Existing collector DB required');
  const credentials = JSON.parse(await readFile(config.credentialsPath, 'utf8'));
  if (!object(credentials) || Object.keys(credentials).sort().join(',') !== 'chain,importer'
    || !object(credentials.importer) || Object.keys(credentials.importer).sort().join(',') !== 'atprotoSecret,binding,did,nostrSecret'
    || typeof credentials.importer.atprotoSecret !== 'string' || typeof credentials.importer.nostrSecret !== 'string'
    || credentials.importer.atprotoSecret === credentials.importer.nostrSecret || !Array.isArray(credentials.chain)) throw new Error('Importer-only credentials required');
  const roots = JSON.parse(await readFile(config.trustPath, 'utf8'));
  if (!Array.isArray(roots) || roots.length > 64 || roots.some(r => 'secretKey' in r)) throw new Error('Public roots required');
  const store = openPublicationStore(config.databasePath);
  const controller = new AbortController(); const stop = () => controller.abort();
  process.once('SIGTERM', stop); process.once('SIGINT', stop);
  let refreshed = 0;
  try {
    while (!controller.signal.aborted) {
      const nowSeconds = Math.floor(Date.now() / 1000);
      if (nowSeconds - refreshed >= 60) {
        const result = await refreshSnapshots(store, roots, nowSeconds);
        refreshed = nowSeconds;
        if (result.failed) console.error('{"status":"snapshot-refresh-failed"}');
      }
      try { await pushOnce(store, config, credentials, roots, nowSeconds, controller.signal); }
      catch { console.error('{"status":"publisher-held"}'); }
      await delay(1000, undefined, { signal: controller.signal }).catch(() => {});
    }
  } finally {
    process.removeListener('SIGTERM', stop); process.removeListener('SIGINT', stop); store.close();
  }
}
if (import.meta.main) main().catch(() => { console.error('{"status":"publisher-fatal"}'); process.exitCode = 1; });
