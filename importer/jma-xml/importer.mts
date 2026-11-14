// JMA XML → Mosimo importer (attest.importer feed dev.jisaku.mosimo.public.jma). Polls the JMA PULL Atom feeds, keeps every fetched
// original with its SHA-256, converts the selected titles (convert.mts), signs each draft as an import post with the
// importer's own keys and chain, checks it with the same rules a relay applies, and pushes it to the PDS relay.
// Posts are Nostr-only (importer §2). Signed events are stored and retried unchanged; deadlines are never renewed.
import { readFile } from 'node:fs/promises';
import { createHash, createPrivateKey } from 'node:crypto';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { setTimeout as delay } from 'node:timers/promises';
import WebSocket from 'ws';
import { convert, type Draft } from './convert.mts';
import { MAX_XML_BYTES } from './xml.mts';
import { createImportPost, authorizeImport, decodeCanonical, decodeRevocationList, rootConfig, validateAttestation,
  issuerDomains, timestamp, hex, unhex, nostrPublicKey, atprotoPublicKey, encodeBytes, canonical, selectionBlock, validateImporterBlock,
  JMA_XML_FEED } from '../../pds/attest.mjs';

export interface Selection { feed: string; types: string[] }
export interface ImporterConfig {
  enabled: boolean;
  databasePath: string;
  /** Directory with the key files of `mosimo-ca credentials` (CREDENTIAL_FILES), e.g. $CREDENTIALS_DIRECTORY. */
  credentialsDirectory: string;
  trustPath: string;
  relayUrl: string;
  selection: Selection[];
  /** High-frequency PULL feeds polled every pollSeconds; the matching long feeds (`_l`) fill the window on start. */
  feeds: string[];
  pollSeconds: number;
  /** Messages whose Control/DateTime is older than this are recorded but not converted. */
  maxMessageAgeSeconds: number;
  /** Delivery deadline (exp) of each post from its creation, per kind. */
  deliverySeconds: { earthquake: number; weather: number };
}
export interface Credentials { importer: { did: string; atprotoSecret: string; nostrSecret: string }; attestations: any[] }

const object = (v: unknown): v is Record<string, any> => v !== null && typeof v === 'object' && !Array.isArray(v);
const JMA_FEED = /^https:\/\/www\.data\.jma\.go\.jp\/developer\/xml\/feed\/[a-z]+\.xml$/;
const JMA_DATA = /^https:\/\/www\.data\.jma\.go\.jp\/developer\/xml\/data\/[0-9A-Za-z_]+\.xml$/;

export function validateConfig(value: unknown): asserts value is ImporterConfig {
  const seconds = (v: unknown, min: number, max: number) => Number.isSafeInteger(v) && Number(v) >= min && Number(v) <= max;
  if (!object(value) || typeof value.enabled !== 'boolean' || ![value.databasePath, value.credentialsDirectory, value.trustPath, value.relayUrl]
    .every(v => typeof v === 'string' && v) || !Array.isArray(value.selection) || !value.selection.every(e => object(e) && e.feed === JMA_XML_FEED)
    || !Array.isArray(value.feeds) || value.feeds.length < 1 || value.feeds.length > 8 || !value.feeds.every(f => typeof f === 'string' && JMA_FEED.test(f))
    || !seconds(value.pollSeconds, 60, 3600) || !seconds(value.maxMessageAgeSeconds, 600, 7 * 86400)
    || !object(value.deliverySeconds) || !seconds(value.deliverySeconds.earthquake, 3600, 30 * 86400)
    || !seconds(value.deliverySeconds.weather, 3600, 30 * 86400)) throw new Error('Invalid importer configuration');
  if (value.selection.length) validateImporterBlock(selectionBlock(value.selection));
  const url = new URL(value.relayUrl);
  if (url.protocol !== 'wss:' || url.username || url.password || url.hash) throw new Error('Importer requires a wss relay');
}
/**
 * Importer key material, as written by `mosimo-ca credentials` and loaded through systemd `LoadCredential`; the same
 * files as the BSAF publisher's (appview/bsaf/PUBLISHER.md, "Key placement"):
 *   identity.json  {did, nostrPubkey, atprotoPubkey}   public
 *   chain.cbor     canonical CBOR array of KeyAttestation, root-issued first, importer leaf last
 *   nostr.pem      PKCS#8 secp256k1, the attested importer key (signs Nostr events)
 *   atproto.pem    PKCS#8 secp256k1, the DID's atproto signing key (signs commits)
 */
export const CREDENTIAL_FILES = ['identity.json', 'chain.cbor', 'nostr.pem', 'atproto.pem'] as const;
export interface CredentialFiles { identity: string; chain: Uint8Array; nostrPem: string; atprotoPem: string }
/** secp256k1 PKCS#8 PEM -> hex secret scalar. Any other key type or curve is refused. */
export function secretFromPem(pem: string): string {
  if (typeof pem !== 'string' || !pem.includes('-----BEGIN PRIVATE KEY-----')) throw new Error('Unencrypted PKCS#8 PEM required');
  const key = createPrivateKey({ key: pem, format: 'pem' });
  if (key.asymmetricKeyType !== 'ec' || key.asymmetricKeyDetails?.namedCurve !== 'secp256k1') throw new Error('secp256k1 key required');
  const d = Buffer.from(String(key.export({ format: 'jwk' }).d), 'base64url');
  if (d.length !== 32) throw new Error('Invalid secp256k1 secret');
  return hex(d);
}
export function loadCredentials(files: CredentialFiles): Credentials {
  const identity: unknown = JSON.parse(files.identity);
  if (!object(identity) || Object.keys(identity).sort().join(',') !== 'atprotoPubkey,did,nostrPubkey'
    || typeof identity.did !== 'string' || !/^did:plc:[a-z2-7]{24}$/.test(identity.did)
    || typeof identity.nostrPubkey !== 'string' || typeof identity.atprotoPubkey !== 'string') throw new Error('Importer-only credentials required');
  const nostrSecret = secretFromPem(files.nostrPem), atprotoSecret = secretFromPem(files.atprotoPem);
  if (nostrSecret === atprotoSecret) throw new Error('Importer-only credentials required');
  if (hex(nostrPublicKey(unhex(nostrSecret))) !== identity.nostrPubkey) throw new Error('nostr.pem does not match identity.json');
  if (hex(atprotoPublicKey(unhex(atprotoSecret))) !== identity.atprotoPubkey) throw new Error('atproto.pem does not match identity.json');
  const attestations = decodeCanonical(files.chain, { maxBytes: 65536 });
  if (!Array.isArray(attestations) || attestations.length < 1 || attestations.length > 8) throw new Error('Credentials chain must hold 1..8 attestations');
  attestations.forEach(validateAttestation);
  if (hex(attestations.at(-1).body.subject) !== identity.nostrPubkey) throw new Error('Chain leaf is not the importer Nostr key');
  return { importer: { did: identity.did, atprotoSecret, nostrSecret }, attestations };
}
export async function readCredentials(directory: string): Promise<Credentials> {
  const [identity, chain, nostrPem, atprotoPem] = await Promise.all(CREDENTIAL_FILES.map(name => readFile(join(directory, name))));
  return loadCredentials({ identity: identity!.toString('utf8'), chain: new Uint8Array(chain!), nostrPem: nostrPem!.toString('utf8'), atprotoPem: atprotoPem!.toString('utf8') });
}
export function loadRoots(value: unknown) {
  if (!Array.isArray(value) || value.length < 1 || value.length > 64) throw new Error('Public roots required');
  for (const root of value) {
    if (!object(root) || Object.keys(root).some(key => /secret/i.test(key))) throw new Error('Public roots must not carry secrets');
    rootConfig(root);
  }
  return value;
}

// ---- store -------------------------------------------------------------------------------------

export function openStore(path: string) {
  const db = new DatabaseSync(path);
  db.exec(`PRAGMA busy_timeout=3000; PRAGMA journal_mode=WAL;
    CREATE TABLE IF NOT EXISTS jma_xml_feeds (url TEXT PRIMARY KEY, last_modified TEXT, fetched_at INTEGER, status TEXT);
    CREATE TABLE IF NOT EXISTS jma_xml_messages (
      url TEXT PRIMARY KEY, title TEXT NOT NULL, feed_updated TEXT, fetched_at INTEGER, digest TEXT, original BLOB,
      status TEXT NOT NULL, reason TEXT);
    CREATE TABLE IF NOT EXISTS jma_xml_posts (
      seq INTEGER PRIMARY KEY, url TEXT NOT NULL, part TEXT NOT NULL, event_json TEXT NOT NULL, priority INTEGER NOT NULL,
      status TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_attempt INTEGER NOT NULL DEFAULT 0, hold_reason TEXT,
      UNIQUE(url, part));
    CREATE TABLE IF NOT EXISTS jma_xml_repository (id INTEGER PRIMARY KEY CHECK(id=1), identity TEXT NOT NULL, state_json TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS jma_xml_revocations (
      id TEXT PRIMARY KEY, subject TEXT NOT NULL, issuer TEXT NOT NULL, effective_at INTEGER NOT NULL, seen_at INTEGER NOT NULL, revocation TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS jma_xml_revocation_sources (domain TEXT PRIMARY KEY, fetched_at INTEGER, status TEXT);`);
  return {
    db,
    revocations: () => db.prepare('SELECT subject, issuer, effective_at FROM jma_xml_revocations').all()
      .map(row => ({ subject: String(row.subject), issuer: String(row.issuer), effectiveAt: Number(row.effective_at) })),
    transaction<T>(fn: () => T): T {
      db.exec('BEGIN IMMEDIATE');
      try { const result = fn(); db.exec('COMMIT'); return result; } catch (error) { db.exec('ROLLBACK'); throw error; }
    },
    close: () => db.close(),
  };
}
export type Store = ReturnType<typeof openStore>;

// ---- feeds -------------------------------------------------------------------------------------

export interface Entry { url: string; title: string; updated: string }
/** Atom entries (id, title, updated) of a JMA PULL feed. Only data URLs on the JMA host are accepted. */
export function parseFeed(xml: string): Entry[] {
  const out: Entry[] = [];
  for (const [, body] of xml.matchAll(/<entry>([\s\S]*?)<\/entry>/g)) {
    const pick = (tag: string) => new RegExp(`<${tag}>([^<]*)</${tag}>`).exec(body!)?.[1];
    const url = pick('id'), title = pick('title'), updated = pick('updated');
    if (url && title && updated && JMA_DATA.test(url)) out.push({ url, title, updated });
  }
  return out;
}
/** VPWW55..61 messages of one office replace each other (weather §7.1); only the newest of each is worth fetching. */
const seriesOf = (e: Entry) => /_(VPWW\d\d)_(\d+)\.xml$/.exec(e.url)?.slice(1).join(':');

async function getBytes(url: string, maxBytes: number, headers: Record<string, string> = {}) {
  const response = await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(20000), headers: { 'user-agent': 'mosimo-importer-jma-xml', ...headers } });
  if (response.status === 304) return { status: 304 as const };
  if (!response.ok || !response.body) throw new Error(`HTTP ${response.status}`);
  const chunks: Uint8Array[] = []; let size = 0;
  for await (const chunk of response.body) { size += chunk.length; if (size > maxBytes) throw new Error('Response size limit'); chunks.push(chunk); }
  return { status: 200 as const, bytes: Buffer.concat(chunks), lastModified: response.headers.get('last-modified') ?? undefined };
}

/** Polls the feeds (conditional GET) and fetches each new selected original once; `long` also reads the `_l` feeds. */
export async function poll(store: Store, config: ImporterConfig, nowSeconds: number, long: boolean, fetchBytes = getBytes) {
  const titles = new Set(config.selection.flatMap(e => e.types));
  const entries: Entry[] = [];
  for (const feed of long ? config.feeds.flatMap(f => [f.replace(/\.xml$/, '_l.xml'), f]) : config.feeds) {
    const known = store.db.prepare('SELECT last_modified FROM jma_xml_feeds WHERE url=?').get(feed);
    let status = 'failed';
    try {
      const result = await fetchBytes(feed, 8 * 1024 * 1024, known?.last_modified ? { 'if-modified-since': String(known.last_modified) } : {});
      if (result.status === 200) { entries.push(...parseFeed(result.bytes.toString('utf8'))); status = 'ok'; } else status = 'not-modified';
      store.db.prepare(`INSERT INTO jma_xml_feeds VALUES(?,?,?,?) ON CONFLICT(url) DO UPDATE SET
        last_modified=coalesce(excluded.last_modified,last_modified),fetched_at=excluded.fetched_at,status=excluded.status`)
        .run(feed, result.status === 200 ? result.lastModified ?? null : null, nowSeconds, status);
    } catch (error) {
      store.db.prepare(`INSERT INTO jma_xml_feeds(url,fetched_at,status) VALUES(?,?,?) ON CONFLICT(url) DO UPDATE SET fetched_at=excluded.fetched_at,status=excluded.status`)
        .run(feed, nowSeconds, `failed: ${(error as Error).message}`.slice(0, 200));
    }
  }
  const newest = new Map<string, Entry>();
  for (const e of entries) {
    if (!titles.has(e.title) || Date.parse(e.updated) / 1000 < nowSeconds - config.maxMessageAgeSeconds) continue;
    const key = seriesOf(e) ?? e.url;
    if (!newest.has(key) || newest.get(key)!.updated < e.updated) newest.set(key, e);
  }
  let fetched = 0;
  for (const e of [...newest.values()].sort((a, b) => a.updated < b.updated ? -1 : 1)) {
    if (store.db.prepare('SELECT 1 FROM jma_xml_messages WHERE url=?').get(e.url)) continue;
    // An older message of a weather series is superseded once a newer one is known (weather §7.1).
    const series = seriesOf(e);
    if (series && store.db.prepare(`SELECT 1 FROM jma_xml_messages WHERE url LIKE ? AND feed_updated > ?`).get(`%_${series.replace(':', '_')}.xml`, e.updated)) continue;
    try {
      const result = await fetchBytes(e.url, MAX_XML_BYTES);
      if (result.status !== 200) continue;
      store.db.prepare(`INSERT INTO jma_xml_messages(url,title,feed_updated,fetched_at,digest,original,status) VALUES(?,?,?,?,?,?,'fetched')`)
        .run(e.url, e.title, e.updated, nowSeconds, createHash('sha256').update(result.bytes).digest('hex'), result.bytes);
      fetched++;
    } catch (error) {
      console.error(JSON.stringify({ status: 'jma-xml-fetch-failed', url: e.url, reason: (error as Error).message }));
    }
    await delay(200);
  }
  return fetched;
}

// ---- conversion and signing --------------------------------------------------------------------

/** Mesh payload estimate of a draft once signed; the relay-side check after signing is exact (attest authorizeImport). */
const MESH_LIMIT = 32768;
function estimate(draft: Draft, credentials: Credentials) {
  const ext = draft.ext.reduce((n, { $type, ...fields }) => n + canonical(fields, { maxBytes: 1 << 22 }).length + $type.length + 16, 0);
  const chain = canonical({ attestations: credentials.attestations }, { maxBytes: 65536 }).length;
  return Buffer.byteLength(draft.text) + ext + chain + 1024; // commit, base, rkey, exp, priority, signature and CBOR framing
}
/** Repository entries kept in the importer's MST; older records are removed so the state stays bounded. */
const KEEP_ENTRIES = 2000;

/** Converts every fetched message into signed pending posts. A failure holds that message only. */
export async function stage(store: Store, config: ImporterConfig, credentials: Credentials, roots: any[], nowSeconds: number) {
  const rows = store.db.prepare(`SELECT url,title,fetched_at,original FROM jma_xml_messages WHERE status='fetched' ORDER BY feed_updated LIMIT 32`).all();
  const importer = credentials.importer;
  const identity = `${importer.did}:${hex(atprotoPublicKey(unhex(importer.atprotoSecret)))}:${hex(nostrPublicKey(unhex(importer.nostrSecret)))}`;
  let staged = 0;
  for (const row of rows) {
    const url = String(row.url), original = row.original as Uint8Array;
    const ctx = { url, retrievedAt: timestamp(Number(row.fetched_at)), digest: new Uint8Array(createHash('sha256').update(original).digest()), converter: importer.did };
    const finish = (status: string, reason?: string) =>
      store.db.prepare('UPDATE jma_xml_messages SET status=?,reason=? WHERE url=?').run(status, reason?.slice(0, 500) ?? null, url);
    let result;
    try { result = convert(original, ctx, d => estimate(d, credentials) <= MESH_LIMIT); }
    catch (error) { finish('held', `Convert: ${(error as Error).message}`); continue; }
    if (result.status === 'skipped') { finish('skipped', result.reason); continue; }
    const kind = result.title.startsWith('震源') ? 'earthquake' : 'weather';
    const signed: { part: string; event: any; priority: number }[] = [];
    const previous = store.db.prepare('SELECT * FROM jma_xml_repository WHERE id=1').get();
    if (previous && previous.identity !== identity) throw new Error('Repository identity changed; explicit migration required');
    let state = previous ? JSON.parse(String(previous.state_json)) : undefined;
    const held = [...result.held];
    for (const draft of result.drafts) {
      if (state && state.entries.length > KEEP_ENTRIES) state = { ...state, entries: state.entries.slice(-KEEP_ENTRIES) };
      try {
        const post = await createImportPost({ importer, attestations: credentials.attestations, text: draft.text, ext: draft.ext,
          createdAt: nowSeconds, exp: timestamp(nowSeconds + config.deliverySeconds[kind]), state, priority: draft.priority });
        await authorizeImport(post.event, { roots, selection: config.selection, revocations: store.revocations(), now: nowSeconds });
        signed.push({ part: draft.part, event: post.event, priority: draft.priority });
        state = post.state;
      } catch (error) {
        const message = (error as Error).message;
        // The importer's own chain failing (root, expiry, revocation) stops staging instead of holding every message.
        if (/No authorized chain/.test(message) && !/outside the importer scope|Mesh payload/.test(message)) throw error;
        held.push(`${draft.part}: ${message}`);
      }
    }
    store.transaction(() => {
      for (const s of signed) store.db.prepare(`INSERT INTO jma_xml_posts(url,part,event_json,priority,status) VALUES(?,?,?,?,'pending')`)
        .run(url, s.part, JSON.stringify(s.event), s.priority);
      if (signed.length) store.db.prepare(`INSERT INTO jma_xml_repository VALUES(1,?,?) ON CONFLICT(id) DO UPDATE SET state_json=excluded.state_json`)
        .run(identity, JSON.stringify(state));
      finish(held.length ? (signed.length ? 'partial' : 'held') : 'converted', held.length ? `Held: ${held.join('; ')}` : undefined);
    });
    staged += signed.length;
  }
  return staged;
}

// ---- delivery ----------------------------------------------------------------------------------

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
        if (Array.isArray(msg) && msg.length === 4 && msg[0] === 'OK' && msg[1] === event.id && typeof msg[2] === 'boolean') finish(msg[2] === true);
      } catch { /* Malformed or unrelated responses cannot acknowledge a publication. */ }
    });
    if (signal.aborted) finish(false);
  });
}
/** Sends the most urgent pending post, rechecking authorization and deadline first. */
export async function pushOnce(store: Store, config: ImporterConfig, roots: any[], nowSeconds: number, signal: AbortSignal, send = publishEvent) {
  const row = store.db.prepare(`SELECT * FROM jma_xml_posts WHERE status='pending' AND next_attempt<=? ORDER BY priority DESC, seq LIMIT 1`).get(nowSeconds);
  if (!row) return false;
  const event = JSON.parse(String(row.event_json));
  try { await authorizeImport(event, { roots, selection: config.selection, revocations: store.revocations(), now: nowSeconds }); }
  catch (error) {
    const message = (error as Error).message;
    if (/No authorized chain/.test(message) && !/outside the importer scope/.test(message)) throw error;
    store.db.prepare(`UPDATE jma_xml_posts SET status='held',hold_reason=? WHERE seq=?`).run(`Send: ${message}`.slice(0, 500), row.seq);
    return false;
  }
  const accepted = await send(config.relayUrl, event, signal);
  const attempts = Number(row.attempts) + 1;
  store.db.prepare(`UPDATE jma_xml_posts SET status=?,attempts=?,next_attempt=? WHERE seq=?`)
    .run(accepted ? 'accepted' : 'pending', attempts, nowSeconds + Math.min(300, 2 ** Math.min(attempts, 8)), row.seq);
  return accepted;
}
/** attest.revocation.list §5: the revocation list of every issuer domain in the chain; failures keep the last state. */
export async function refreshRevocations(store: Store, credentials: Credentials, nowSeconds: number) {
  let failed = 0;
  for (const domain of issuerDomains(credentials.attestations)) {
    let status = 'failed';
    try {
      const result = await getBytes(`https://${domain}/.well-known/mosimo/attest/revocations`, 1048576, { accept: 'application/cbor' });
      if (result.status !== 200) throw new Error('Revocation list HTTP');
      const list = decodeRevocationList(result.bytes);
      store.transaction(() => {
        for (const entry of list.accepted) {
          const { $type, ...fields } = entry.revocation;
          store.db.prepare(`INSERT OR IGNORE INTO jma_xml_revocations VALUES(?,?,?,?,?,?)`)
            .run(entry.id, entry.subject, entry.issuer, Math.floor(entry.effectiveAt), nowSeconds, encodeBytes(canonical(fields)));
        }
      });
      status = `${list.listSignatureValid ? 'ok' : 'unsigned-list'}: ${list.accepted.length} notices`;
    } catch (error) { status = `failed: ${(error as Error).message}`.slice(0, 200); failed++; }
    store.db.prepare(`INSERT INTO jma_xml_revocation_sources VALUES(?,?,?) ON CONFLICT(domain) DO UPDATE SET fetched_at=excluded.fetched_at,status=excluded.status`)
      .run(domain, nowSeconds, status);
  }
  return failed;
}

export async function main() {
  if (!process.env.IMPORTER_CONFIG_FILE) throw new Error('IMPORTER_CONFIG_FILE required');
  const config: unknown = JSON.parse(await readFile(process.env.IMPORTER_CONFIG_FILE, 'utf8'));
  validateConfig(config);
  if (!config.enabled || !config.selection.length) return;
  const credentials = await readCredentials(config.credentialsDirectory);
  const roots = loadRoots(JSON.parse(await readFile(config.trustPath, 'utf8')));
  const store = openStore(config.databasePath);
  const controller = new AbortController(); const stop = () => controller.abort();
  process.once('SIGTERM', stop); process.once('SIGINT', stop);
  let polled = 0, refreshed = 0, first = true, lastError = '';
  try {
    while (!controller.signal.aborted) {
      const now = () => Math.floor(Date.now() / 1000);
      try {
        if (now() - refreshed >= 600) { if (await refreshRevocations(store, credentials, now())) console.error(JSON.stringify({ status: 'revocation-refresh-failed' })); refreshed = now(); }
        if (now() - polled >= config.pollSeconds) {
          const fetched = await poll(store, config, now(), first);
          polled = now(); first = false;
          const staged = await stage(store, config, credentials, roots, now());
          if (fetched || staged) console.log(JSON.stringify({ status: 'jma-xml-polled', fetched, staged }));
        }
        while (await pushOnce(store, config, roots, now(), controller.signal)) { /* drain accepted posts */ }
        lastError = '';
      } catch (error) {
        const reason = (error as Error).message;
        if (reason !== lastError) { lastError = reason; console.error(JSON.stringify({ status: 'jma-xml-held', reason })); }
      }
      await delay(1000, undefined, { signal: controller.signal }).catch(() => {});
    }
  } finally {
    process.removeListener('SIGTERM', stop); process.removeListener('SIGINT', stop); store.close();
  }
}
if (import.meta.main) main().catch(error => { console.error(JSON.stringify({ status: 'jma-xml-fatal', reason: (error as Error).message })); process.exitCode = 1; });
