import { readFile, stat } from 'node:fs/promises';
import { statSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import { setTimeout as delay } from 'node:timers/promises';
import WebSocket from 'ws';
import { cidForLex } from '@atproto/lex-cbor';
import { parseCid } from '@atproto/lex-data';
import { object } from './store.mts';
import { hazardExtensions } from './hazard.mts';
import { createImportPost, authorizeImport, verifyBsaf, decodeCanonical, decodeRevocationList, unb64, rootConfig,
  validateAttestation, issuerDomains, BSAF, JMA_SELECTION, timestamp, hex, nostrPublicKey, atprotoPublicKey, unhex, encodeBytes, canonical } from '../../pds/attest.mjs';

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
/** Worker-only material: the importer's signing secrets and its signing chain (root-issued CA
 * attestation first, importer leaf last). Never the root or CA secret. */
export interface Credentials { importer: { did: string; atprotoSecret: string; nostrSecret: string }; attestations: any[] }

// Local delivery hints only, never an interpretation of source:jma or original agency authority.
// Values follow the Bot vocabulary (public.bsaf.hazard §3): special-warning is its most severe class.
const jmaPriorityRules: ReadonlyArray<{ type: string; value: string; level: 2 | 3 }> = [
  { type: 'tsunami', value: 'special-warning', level: 3 },
  { type: 'tsunami', value: 'warning', level: 2 },
  { type: 'tsunami', value: 'advisory', level: 2 },
  { type: 'nankai-trough', value: 'special-warning', level: 3 },
  { type: 'nankai-trough', value: 'warning', level: 3 },
  { type: 'nankai-trough', value: 'advisory', level: 2 },
  { type: 'eruption', value: 'special-warning', level: 3 },
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
/** credentials.json: {importer:{did,atprotoSecret,nostrSecret}, chain:<Base64 canonical CBOR of the attestation array>}. */
export function loadCredentials(value: unknown): Credentials {
  if (!object(value) || Object.keys(value).sort().join(',') !== 'chain,importer' || !object(value.importer)
    || Object.keys(value.importer).sort().join(',') !== 'atprotoSecret,did,nostrSecret'
    || typeof value.importer.did !== 'string' || !/^did:plc:[a-z2-7]{24}$/.test(value.importer.did)
    || typeof value.importer.atprotoSecret !== 'string' || typeof value.importer.nostrSecret !== 'string'
    || !/^[0-9a-f]{64}$/.test(value.importer.atprotoSecret) || !/^[0-9a-f]{64}$/.test(value.importer.nostrSecret)
    || value.importer.atprotoSecret === value.importer.nostrSecret || typeof value.chain !== 'string') throw new Error('Importer-only credentials required');
  const attestations = decodeCanonical(unb64(value.chain));
  if (!Array.isArray(attestations) || attestations.length < 1 || attestations.length > 8) throw new Error('Credentials chain must hold 1..8 attestations');
  attestations.forEach(validateAttestation);
  const leaf = attestations[attestations.length - 1].body.subject as Uint8Array;
  if (hex(leaf) !== hex(nostrPublicKey(unhex(value.importer.nostrSecret)))) throw new Error('Chain leaf is not the importer Nostr key');
  return { importer: { did: value.importer.did, atprotoSecret: value.importer.atprotoSecret, nostrSecret: value.importer.nostrSecret }, attestations };
}
/** roots.json: array of receiver-local root settings {pubkey, roles, maxDepth, enabled?, name?}. */
export function loadRoots(value: unknown) {
  if (!Array.isArray(value) || value.length < 1 || value.length > 64) throw new Error('Public roots required');
  for (const root of value) {
    if (!object(root) || Object.keys(root).some(key => /secret/i.test(key))) throw new Error('Public roots must not carry secrets');
    rootConfig(root);
  }
  return value;
}
export const encodeChain = (attestations: any[]) => encodeBytes(canonical(attestations, { maxBytes: 65536 }));

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
      attempts INTEGER NOT NULL DEFAULT 0, next_attempt INTEGER NOT NULL DEFAULT 0, hold_reason TEXT,
      UNIQUE(uri,cid)
    );
    CREATE TABLE IF NOT EXISTS mosimo_bsaf_repository (id INTEGER PRIMARY KEY CHECK(id=1), identity TEXT NOT NULL, state_json TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS mosimo_bsaf_revocations (
      id TEXT PRIMARY KEY, subject TEXT NOT NULL, issuer TEXT NOT NULL, effective_at INTEGER NOT NULL, seen_at INTEGER NOT NULL, revocation TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS mosimo_bsaf_revocation_sources (domain TEXT PRIMARY KEY, fetched_at INTEGER, status TEXT);
  `);
  if (!db.prepare('PRAGMA table_info(mosimo_bsaf_publications)').all().some(column => column.name === 'hold_reason')) {
    db.exec('ALTER TABLE mosimo_bsaf_publications ADD COLUMN hold_reason TEXT');
  }
  let busy = false;
  return {
    db,
    revocations() {
      return db.prepare('SELECT subject, issuer, effective_at FROM mosimo_bsaf_revocations').all()
        .map(row => ({ subject: String(row.subject), issuer: String(row.issuer), effectiveAt: Number(row.effective_at) }));
    },
    async transaction<T>(fn: () => Promise<T>): Promise<T> {
      if (busy) throw new Error('Overlapping publisher operation');
      busy = true;
      try {
        db.exec('BEGIN IMMEDIATE');
        try { const result = await fn(); db.exec('COMMIT'); return result; }
        catch (error) { db.exec('ROLLBACK'); throw error; }
      } finally { busy = false; }
    },
    close() { db.close(); },
  };
}
export type PublicationStore = ReturnType<typeof openPublicationStore>;

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
/** Tolerated fast clock of the source Bot: a record at most this far ahead waits instead of being held. */
const SOURCE_CLOCK_SKEW_SECONDS = 300;
/**
 * A failure of the importer's own chain (unknown root, expiry, revocation) stops publication until the operator acts,
 * without consuming rows. Anything specific to one record (size, deadline, scope, semantics) only holds that row,
 * so one unsendable record never blocks the ones behind it.
 */
function rowSpecific(error: unknown) {
  const message = (error as Error)?.message ?? '';
  if (/Repository identity changed/.test(message)) return false;
  if (!message.startsWith('No authorized chain')) return true;
  return /outside the importer scope/.test(message) && !/revoked|unverifiable|expired|Revocation state/i.test(message);
}
/** Self-check with the same rules a receiving relay applies, using the publisher's own roots and known revocations. */
function selfAuthorize(store: PublicationStore, event: any, config: PublisherConfig, roots: any[], nowSeconds: number) {
  return authorizeImport(event, { roots, selection: config.selection, revocations: store.revocations(), now: nowSeconds });
}
export async function stageNext(store: PublicationStore, config: PublisherConfig, credentials: Credentials, roots: any[], nowSeconds: number) {
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
        // A slightly fast Bot clock is not a reason to drop a warning: leave the row for a later pass.
        if (originalSeconds > nowSeconds && originalSeconds <= nowSeconds + SOURCE_CLOCK_SKEW_SECONDS) continue;
        if (originalSeconds > nowSeconds || originalSeconds + config.maxOriginalAgeSeconds <= nowSeconds) throw new Error('Source age');
        deadline = timestamp(originalSeconds + config.maxOriginalAgeSeconds);
      } catch {
        db.prepare(`INSERT INTO mosimo_bsaf_publications(uri,cid,original_json,provenance_json,status) VALUES(?,?,?,?,'held')`)
          .run(row.uri, row.cid, row.record, JSON.stringify(provenance));
        continue;
      }
      const importer = credentials.importer;
      const identity = `${importer.did}:${hex(atprotoPublicKey(unhex(importer.atprotoSecret)))}:${hex(nostrPublicKey(unhex(importer.nostrSecret)))}`;
      const previous = db.prepare('SELECT * FROM mosimo_bsaf_repository WHERE id=1').get();
      if (previous && previous.identity !== identity) throw new Error('Repository identity changed; explicit migration required');
      // Common hazard meaning (public.bsaf.hazard); empty for Bots/types/values outside the rule's tables.
      let result: Awaited<ReturnType<typeof createImportPost>>;
      try {
        const hazard = hazardExtensions(String(row.did), record, { uri: String(row.uri), createdAt: record.createdAt,
          retrievedFrom: String(row.source_url), retrievedAt: String(row.fetched_at), converter: importer.did });
        result = await createImportPost({ importer, attestations: credentials.attestations, text: record.text, ext: [extension, ...hazard],
          createdAt: nowSeconds, exp: deadline, state: previous ? JSON.parse(String(previous.state_json)) : undefined,
          priority: publicationPriority(config, String(row.did), record.tags) });
        await selfAuthorize(store, result.event, config, roots, nowSeconds);
      } catch (error) {
        if (!rowSpecific(error)) throw error;
        // The repository state is not advanced, so the held row leaves no gap in the commit chain.
        db.prepare(`INSERT INTO mosimo_bsaf_publications(uri,cid,original_json,provenance_json,status,hold_reason) VALUES(?,?,?,?,'held',?)`)
          .run(row.uri, row.cid, row.record, JSON.stringify(provenance), `Staging: ${(error as Error).message}`.slice(0, 500));
        continue;
      }
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
export async function pushOnce(store: PublicationStore, config: PublisherConfig, credentials: Credentials, roots: any[], nowSeconds: number,
  signal: AbortSignal, send = publishEvent) {
  const row = await stageNext(store, config, credentials, roots, nowSeconds);
  if (!row || Number(row.next_attempt) > nowSeconds) return false;
  const event = JSON.parse(String(row.event_json));
  const hold = async (reason: string) => {
    // The signed EVENT stays for review; later rows build on its state, but it is never sent or re-signed.
    await store.transaction(async () => {
      store.db.prepare(`UPDATE mosimo_bsaf_publications SET status='held',hold_reason=? WHERE seq=? AND status='pending'`).run(reason, row.seq);
    });
    console.error(JSON.stringify({ status: 'publisher-held', seq: Number(row.seq), reason }));
    return false;
  };
  if (event.content !== JSON.parse(String(row.original_json)).text) return hold('Original text mismatch; operator review required; signed EVENT retained');
  // Recheck validity, revocation, selection and deadline at the actual send, including retries.
  try { await selfAuthorize(store, event, config, roots, nowSeconds); }
  catch (error) {
    if (!rowSpecific(error)) throw error;
    return hold(`Send: ${(error as Error).message}`.slice(0, 500));
  }
  const accepted = await send(config.relayUrl, event, signal);
  await store.transaction(async () => {
    const attempts = Number(row.attempts) + 1;
    store.db.prepare(`UPDATE mosimo_bsaf_publications SET status=?,attempts=?,next_attempt=? WHERE seq=? AND status='pending'`)
      .run(accepted ? 'accepted' : 'pending', attempts, nowSeconds + Math.min(300, 2 ** Math.min(attempts, 8)), row.seq);
  });
  return accepted;
}
/** attest.revocation.list §5: fetch the revocation list of every issuer domain in the chain. Failures
 * keep the last known state; a fetched notice is applied by the next stage/send self-check. */
export async function refreshRevocations(store: PublicationStore, credentials: Credentials, nowSeconds: number,
  fetchImpl: typeof fetch = fetch) {
  let refreshed = 0, failed = 0;
  for (const domain of issuerDomains(credentials.attestations)) {
    const url = `https://${domain}/.well-known/mosimo/attest/revocations`;
    let status = 'failed';
    try {
      const response = await fetchImpl(url, { redirect: 'error', signal: AbortSignal.timeout(10000), headers: { accept: 'application/cbor' } });
      if (!response.ok || response.headers.get('content-type')?.split(';')[0] !== 'application/cbor') throw new Error('Revocation list HTTP format');
      let size = 0; const chunks: Uint8Array[] = [];
      if (!response.body) throw new Error('Revocation list HTTP body');
      for await (const chunk of response.body) { size += chunk.length; if (size > 1048576) throw new Error('Revocation list size'); chunks.push(chunk); }
      const list = decodeRevocationList(Buffer.concat(chunks));
      await store.transaction(async () => {
        for (const entry of list.accepted) {
          const { $type, ...fields } = entry.revocation;
          store.db.prepare(`INSERT OR IGNORE INTO mosimo_bsaf_revocations VALUES(?,?,?,?,?,?)`)
            .run(entry.id, entry.subject, entry.issuer, Math.floor(entry.effectiveAt), nowSeconds, encodeBytes(canonical(fields)));
        }
      });
      status = `${list.listSignatureValid ? 'ok' : 'unsigned-list'}: ${list.accepted.length} notices`;
      refreshed++;
    } catch (error) { status = `failed: ${(error as Error).message}`.slice(0, 200); failed++; }
    store.db.prepare(`INSERT INTO mosimo_bsaf_revocation_sources VALUES(?,?,?) ON CONFLICT(domain) DO UPDATE SET fetched_at=excluded.fetched_at,status=excluded.status`)
      .run(domain, nowSeconds, status);
  }
  return { refreshed, failed };
}
export async function main() {
  if (!process.env.PUBLISHER_CONFIG_FILE) throw new Error('PUBLISHER_CONFIG_FILE required');
  const config: unknown = JSON.parse(await readFile(process.env.PUBLISHER_CONFIG_FILE, 'utf8'));
  validatePublisherConfig(config);
  if (!config.enabled || !config.selection.length) return;
  if (!(await stat(config.databasePath)).isFile()) throw new Error('Existing collector DB required');
  const credentials = loadCredentials(JSON.parse(await readFile(config.credentialsPath, 'utf8')));
  const roots = loadRoots(JSON.parse(await readFile(config.trustPath, 'utf8')));
  const store = openPublicationStore(config.databasePath);
  const controller = new AbortController(); const stop = () => controller.abort();
  process.once('SIGTERM', stop); process.once('SIGINT', stop);
  let refreshed = 0;
  let lastHold = '';
  try {
    while (!controller.signal.aborted) {
      const nowSeconds = Math.floor(Date.now() / 1000);
      if (nowSeconds - refreshed >= 600) {
        const result = await refreshRevocations(store, credentials, nowSeconds);
        refreshed = nowSeconds;
        if (result.failed) console.error(JSON.stringify({ status: 'revocation-refresh-failed', ...result }));
      }
      try { await pushOnce(store, config, credentials, roots, nowSeconds, controller.signal); lastHold = ''; }
      catch (error) {
        // Log a reason once per distinct failure instead of once per second.
        const reason = (error as Error).message;
        if (reason !== lastHold) { lastHold = reason; console.error(JSON.stringify({ status: 'publisher-held', reason })); }
      }
      await delay(1000, undefined, { signal: controller.signal }).catch(() => {});
    }
  } finally {
    process.removeListener('SIGTERM', stop); process.removeListener('SIGINT', stop); store.close();
  }
}
if (import.meta.main) main().catch(error => { console.error(JSON.stringify({ status: 'publisher-fatal', reason: (error as Error).message })); process.exitCode = 1; });
