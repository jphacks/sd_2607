import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { cidForLex } from '@atproto/lex-cbor';
import { WebSocketServer } from 'ws';
import { openStore } from './store.mts';
import { receiverConfigurations, presetSelection } from './identity-cli.mjs';
import { openPublicationStore, stageNext, pushOnce, publishEvent, validatePublisherConfig, refreshRevocations, publicationPriority,
  loadCredentials, loadRoots, encodeChain, type PublisherConfig, type Credentials } from './publisher.mts';
import { BLOCK, IMPORTER, BSAF, REVOCATION, JMA_SELECTION, KIKIKURU_SELECTION, timestamp, hex, unhex, canonical, randomSecret, nostrPublicKey,
  createAttestation, createImporter, createImportPost, createNotice, createRevocationList, authorizeImport, priorityHintOf } from '../../pds/attest.mjs';
import { decodePost } from '../../pds/repository.mjs';

const now = 1792368000;
const botDid = JMA_SELECTION[0]!.botDid;
async function fixture(t: any, selection: any = JMA_SELECTION) {
  const directory = await mkdtemp(join(tmpdir(), 'mosimo-publisher-'));
  const databasePath = join(directory, 'happyview.db');
  const db = new DatabaseSync(databasePath);
  db.exec(`CREATE TABLE happyview_records(uri TEXT PRIMARY KEY,did TEXT,collection TEXT,rkey TEXT,record TEXT,cid TEXT,indexed_at TEXT,created_at TEXT);
    CREATE TABLE happyview_lexicons(id TEXT PRIMARY KEY);`); db.close();
  const collector = openStore(databasePath, selection.map((e: any) => e.botDid), 'wss://jetstream.invalid/subscribe');
  const store = openPublicationStore(databasePath);
  t.after(async () => { collector.close(); store.close(); await rm(directory, { recursive: true, force: true }); });
  const sources = selection.map((e: any) => ({ botDid: e.botDid, types: [...e.types] }));
  const rootSecret = randomSecret(), caSecret = randomSecret();
  const root = nostrPublicKey(rootSecret), ca = nostrPublicKey(caSecret);
  const importer = createImporter({ endpoint: 'https://pds.example.org' });
  const caAttestation = createAttestation(rootSecret, root, { subject: ca, owner: { id: 'ca', name: 'Test CA' }, roles: ['attest', 'import'],
    blocks: [{ $type: BLOCK.delegation, maxDepth: 1 }, { $type: BLOCK.issuerDomain, domain: 'pds.example.org' }, { $type: IMPORTER, sources }] });
  const leaf = createAttestation(caSecret, ca, { subject: unhex(importer.nostrPubkey), owner: { id: importer.did, name: 'Test importer' }, roles: ['import'],
    blocks: [{ $type: BLOCK.keyValidity, from: timestamp(now - 60), until: timestamp(now + 86400) },
      { $type: BLOCK.organization, orgId: 'example.org:importer', orgName: 'Test importer, not JMA' }, { $type: IMPORTER, sources }] });
  const credentials: Credentials = { importer: { did: importer.did, atprotoSecret: importer.atprotoSecret, nostrSecret: importer.nostrSecret }, attestations: [caAttestation, leaf] };
  const roots = [{ pubkey: hex(root), roles: ['attest', 'import'], maxDepth: 2, enabled: true }];
  const config: PublisherConfig = { enabled: true, databasePath, credentialsPath: 'not-read-by-tests', trustPath: 'not-read-by-tests',
    relayUrl: 'wss://relay.invalid', selection: sources, maxOriginalAgeSeconds: 600, priority: 3 };
  let sequence = 0;
  async function ingest(overrides: Record<string, any> = {}, rkey = `test-${++sequence}`, did: string = botDid) {
    const record = { $type: 'app.bsky.feed.post', text: 'TEST ONLY — 地震情報（出典：気象庁）', createdAt: timestamp(now - 30),
      tags: ['bsaf:v1', 'type:earthquake', 'value:5+', `time:${timestamp(now - 30)}`, 'target:jp-kanto', 'source:jma'], langs: ['ja'], ...overrides };
    const cid = (await cidForLex(record)).toString();
    const event = { did, kind: 'commit', time_us: 1000 + sequence,
      commit: { operation: 'create', collection: 'app.bsky.feed.post', rkey, record, cid } };
    const status = await collector.ingest(event);
    return { record, cid, event, status };
  }
  const authorize = (event: any, at = now) => authorizeImport(event, { roots, selection: config.selection, revocations: store.revocations(), now: at });
  return { databasePath, store, collector, config, credentials, roots, rootSecret, root, caSecret, ca, leaf, importer, ingest, authorize };
}

test('receiver exports carry only public root settings, the selection and the revocation domain', () => {
  const root = { pubkey: hex(nostrPublicKey(randomSecret())), roles: ['attest', 'import'], maxDepth: 2, enabled: true, name: 'r', secretKey: 'must-never-appear' };
  const files = receiverConfigurations(root, 'pds.oriom.dev', presetSelection(['--jma', '--kikikuru']));
  assert.deepEqual(Object.keys(files.pds.publicInformation).sort(), ['revocationDomains', 'roots', 'selection']);
  assert.deepEqual(files.pds.publicInformation.revocationDomains, ['pds.oriom.dev']);
  assert.equal(files.android.selected.length, 28); assert.equal(files.pds.publicInformation.selection.length, 2);
  assert.ok(!JSON.stringify(files).includes('must-never-appear') && !JSON.stringify(files).includes('secretKey'));
  assert.throws(() => receiverConfigurations(root, 'pds.oriom.dev', [{ botDid, types: ['b', 'a'] }]));
});

test('fixed presets are independent and never auto-expand', () => {
  assert.deepEqual(presetSelection(), JMA_SELECTION.map(e => ({ botDid: e.botDid, types: [...e.types] })));
  assert.deepEqual(presetSelection(['--jma']).length, 1);
  assert.equal(presetSelection(['--kikikuru'])[0]!.types.length, 23);
  assert.throws(() => presetSelection(['--jma', '--jma']));
  const both = presetSelection(['--jma', '--kikikuru']);
  assert.equal(both.length, 2); assert.ok(both[0]!.botDid < both[1]!.botDid);
  assert.deepEqual(presetSelection(['--days=3']), presetSelection());
});

test('credentials and roots loaders reject secrets in the wrong file and chains that do not end in the importer key', async t => {
  const f = await fixture(t);
  const json = { importer: f.credentials.importer, chain: encodeChain(f.credentials.attestations) };
  const loaded = loadCredentials(JSON.parse(JSON.stringify(json)));
  assert.equal(loaded.attestations.length, 2); assert.equal(hex(loaded.attestations[1].body.subject), f.importer.nostrPubkey);
  assert.throws(() => loadCredentials({ ...json, importer: { ...json.importer, nostrSecret: hex(randomSecret()) } }), /leaf is not the importer/);
  assert.throws(() => loadCredentials({ ...json, importer: { ...json.importer, plcSecret: 'x' } }), /Importer-only/);
  assert.throws(() => loadCredentials({ ...json, chain: 'AAAA' }));
  assert.deepEqual(loadRoots(f.roots), f.roots);
  assert.throws(() => loadRoots([{ ...f.roots[0], secretKey: 'x' }]), /secrets/);
  assert.throws(() => loadRoots([]));
});

test('all 23 pinned weather types publish generically at normal priority, unknown/spoofed sources do not', async t => {
  const f = await fixture(t, presetSelection(['--jma', '--kikikuru']));
  const weather = KIKIKURU_SELECTION[0]!;
  const config = { ...f.config, priority: null };
  assert.equal(weather.types.length, 23);
  validatePublisherConfig(config);
  const types32 = Array.from({ length: 32 }, (_, i) => `type-${i}`);
  validatePublisherConfig({ ...config, selection: [{ botDid, types: types32 }, { botDid: weather.botDid, types: types32 }] });
  assert.throws(() => validatePublisherConfig({ ...config, selection: [{ botDid, types: [...types32, 'extra'] }] }));
  assert.throws(() => validatePublisherConfig({ ...config, selection: [{ botDid, types: types32 }, { botDid: weather.botDid, types: types32 }, { botDid: 'did:plc:aaaaaaaaaaaaaaaaaaaaaaaa', types: ['extra'] }] }));
  for (const type of weather.types) {
    const tags = ['bsaf:v1', `type:${type}`, 'value:level5', `time:${timestamp(now - 30)}`, 'target:jp', 'source:jma'];
    const source = await f.ingest({ text: `Weather ${type}`, tags }, type, weather.botDid);
    assert.equal(source.status, 'stored');
    const row = (await stageNext(f.store, config, f.credentials, f.roots, now))!;
    assert.ok(row);
    const event = JSON.parse(String(row.event_json));
    const verified = await f.authorize(event);
    assert.equal(verified.botDid, weather.botDid);
    assert.equal(verified.type, type);
    assert.equal(priorityHintOf(event), 1);
    assert.equal(publicationPriority(config, weather.botDid, tags), 1);
    assert.deepEqual(JSON.parse(String(row.original_json)).tags, tags);
    f.store.db.prepare("UPDATE mosimo_bsaf_publications SET status='accepted' WHERE seq=?").run(row.seq!);
  }
  await f.ingest({ tags: ['bsaf:v1', 'type:new-upstream-type', 'value:level5', `time:${timestamp(now - 30)}`, 'target:jp', 'source:jma'] }, 'unknown', weather.botDid);
  assert.equal(await stageNext(f.store, config, f.credentials, f.roots, now), null);
  const spoof = await f.ingest({ text: '@bsaf-kikikuru-bot.bsky.social source:jma', tags: ['bsaf:v1', 'type:typhoon', 'value:level5', `time:${timestamp(now - 30)}`, 'target:jp', 'source:jma'] }, 'spoof', 'did:plc:aaaaaaaaaaaaaaaaaaaaaaaa');
  assert.equal(spoof.status, 'ignored');
  assert.equal(await stageNext(f.store, config, f.credentials, f.roots, now), null);
});

test('publisher opt-in validates pinned JMA pairs; collection does not enable publication', async t => {
  const f = await fixture(t); await f.ingest();
  assert.equal(await stageNext(f.store, { ...f.config, enabled: false }, f.credentials, f.roots, now), null);
  assert.equal(await stageNext(f.store, { ...f.config, selection: [] }, f.credentials, f.roots, now), null);
  assert.equal(f.store.db.prepare('SELECT count(*) n FROM mosimo_bsaf_publications').get()!.n, 0);
  for (const changes of [{ maxOriginalAgeSeconds: 0 }, { relayUrl: 'ws://relay.invalid' }, { selection: [{ botDid, types: [] }] }, { priority: '3' }]) {
    assert.throws(() => validatePublisherConfig({ ...f.config, ...changes }));
  }
});

test('direct collector DB conversion archives original/provenance, durable dedup, repository state and the spec wire format', async t => {
  const f = await fixture(t); const source = await f.ingest(); assert.equal(source.status, 'stored');
  const row = await stageNext(f.store, f.config, f.credentials, f.roots, now); assert.ok(row);
  assert.deepEqual(JSON.parse(String(row.original_json)), source.record);
  const provenance = JSON.parse(String(row.provenance_json));
  assert.equal(provenance.did, botDid); assert.equal(provenance.sourceUrl, 'wss://jetstream.invalid/subscribe');
  const event = JSON.parse(String(row.event_json));
  assert.equal(event.content, source.record.text);
  assert.equal(row.deadline, timestamp(now + 570));
  assert.equal(event.pubkey, f.importer.nostrPubkey);
  assert.deepEqual(event.tags.slice(0, 2), [['t', 'mosimo.jisaku.dev'], ['mosimo:v', '1']]);
  assert.deepEqual(event.tags.slice(6).map((tag: string[]) => tag[0]), ['mosimo:ext:dev.jisaku.mosimo.public.bsaf', 'mosimo:ext:dev.jisaku.mosimo.attest.chain', 'mosimo:ext:dev.jisaku.mosimo.priority']);
  const decoded = await decodePost(event);
  assert.equal(decoded.commit.did, f.importer.did); assert.equal(decoded.record.exp, row.deadline);
  assert.deepEqual(decoded.record.ext[0].original, { uri: row.uri, cid: row.cid, createdAt: source.record.createdAt });
  assert.equal((await f.authorize(event)).authorized, true);
  const retry = await stageNext(f.store, f.config, f.credentials, f.roots, now + 20);
  assert.equal(retry!.event_json, row.event_json); assert.equal(retry!.deadline, row.deadline);
  const reopened = openPublicationStore(f.databasePath);
  try { assert.equal((await stageNext(reopened, f.config, f.credentials, f.roots, now + 30))!.event_json, row.event_json); }
  finally { reopened.close(); }
  assert.equal(f.store.db.prepare('SELECT count(*) n FROM mosimo_bsaf_publications').get()!.n, 1);
  assert.equal(JSON.parse(String(f.store.db.prepare('SELECT state_json FROM mosimo_bsaf_repository').get()!.state_json)).entries.length, 1);
});

test('publication preserves exact Unicode source text, including any original DID, without attribution additions', async t => {
  const f = await fixture(t);
  for (const text of ['地震\r\nが 🏠\n', `原文 did:plc:example / ${botDid} CID source:そのまま`]) {
    await f.ingest({ text });
    const row = (await stageNext(f.store, f.config, f.credentials, f.roots, now))!;
    assert.equal(JSON.parse(String(row.event_json)).content, text);
    f.store.db.prepare("UPDATE mosimo_bsaf_publications SET status='accepted' WHERE seq=?").run(row.seq!);
  }
});

test('a signed pending event with appended text is held unchanged and never sent or resigned', async t => {
  const f = await fixture(t); await f.ingest();
  const row = (await stageNext(f.store, f.config, f.credentials, f.roots, now))!;
  const original = JSON.parse(String(row.original_json));
  const { event: altered } = await createImportPost({ importer: f.credentials.importer, attestations: f.credentials.attestations,
    text: original.text + '\nattribution footer', ext: [{ $type: BSAF, tags: original.tags, langs: ['ja'], original: { uri: row.uri, cid: row.cid, createdAt: original.createdAt } }],
    createdAt: now, exp: String(row.deadline), priority: 3 });
  assert.equal((await f.authorize(altered)).authorized, true, 'the relay cannot know the text was altered; the publisher must hold it');
  f.store.db.prepare('UPDATE mosimo_bsaf_publications SET event_json=? WHERE seq=?').run(JSON.stringify(altered), row.seq!);
  let sent = 0;
  assert.equal(await pushOnce(f.store, f.config, f.credentials, f.roots, now, new AbortController().signal, async () => { sent++; return true; }), false);
  assert.equal(sent, 0);
  const saved = f.store.db.prepare('SELECT * FROM mosimo_bsaf_publications WHERE seq=?').get(row.seq!)!;
  assert.equal(saved.event_json, JSON.stringify(altered));
  assert.equal(saved.status, 'pending');
  assert.equal(saved.hold_reason, 'Original text mismatch; operator review required; signed EVENT retained');
  assert.deepEqual(await stageNext(f.store, f.config, f.credentials, f.roots, now), saved);
});

test('OK failure/loss retries exact EVENT; accepted dedup persists; CID changes are distinct', async t => {
  const f = await fixture(t); await f.ingest({}, 'same-uri');
  const sent: any[] = []; const signal = new AbortController().signal;
  const send = async (_url: string, event: any) => { sent.push(event); return sent.length > 1; };
  assert.equal(await pushOnce(f.store, f.config, f.credentials, f.roots, now, signal, send), false);
  assert.equal(await pushOnce(f.store, f.config, f.credentials, f.roots, now + 1, signal, send), false);
  assert.equal(sent.length, 1);
  assert.equal(await pushOnce(f.store, f.config, f.credentials, f.roots, now + 2, signal, send), true);
  assert.deepEqual(sent[0], sent[1]);
  assert.equal(await stageNext(f.store, f.config, f.credentials, f.roots, now + 10), null);
  // A collector update has the same URI but a different CID and does not rewrite the first event.
  const record = { $type: 'app.bsky.feed.post', text: '訂正 TEST ONLY', createdAt: timestamp(now - 20),
    tags: ['bsaf:v1', 'type:earthquake', 'value:5+', `time:${timestamp(now - 30)}`, 'target:jp-kanto', 'source:jma'], langs: ['ja'] };
  await f.collector.ingest({ did: botDid, kind: 'commit', time_us: 9999, commit: { operation: 'update', collection: 'app.bsky.feed.post', rkey: 'same-uri', record, cid: (await cidForLex(record)).toString() } });
  const next = await stageNext(f.store, f.config, f.credentials, f.roots, now + 10);
  assert.ok(next); assert.notEqual(JSON.parse(String(next.event_json)).id, sent[0].id);
  assert.equal(f.store.db.prepare('SELECT count(*) n FROM mosimo_bsaf_publications').get()!.n, 2);
});

test('unsupported original semantics, CID/envelope mismatch, old/future source are held without signing', async t => {
  const f = await fixture(t);
  await f.ingest({ embed: { $type: 'app.bsky.embed.images', images: [] } });
  await f.ingest({ reply: { root: {}, parent: {} } });
  await f.ingest({ labels: { values: [] } });
  await f.ingest({ createdAt: timestamp(now - 601) });
  await f.ingest({ createdAt: timestamp(now + 1) });
  await f.ingest({ facets: [{ index: { byteStart: 0, byteEnd: 4 }, features: [{ $type: 'app.bsky.richtext.facet#link', uri: 'https://not-visible.invalid' }] }] });
  await f.ingest({}, 'cid-mismatch');
  f.store.db.prepare(`UPDATE happyview_records SET record=json_set(record,'$.text','tampered') WHERE rkey='cid-mismatch'`).run();
  assert.equal(await stageNext(f.store, f.config, f.credentials, f.roots, now), null);
  assert.equal(f.store.db.prepare(`SELECT count(*) n FROM mosimo_bsaf_publications WHERE status='held'`).get()!.n, 7);
  assert.equal(f.store.db.prepare('SELECT count(*) n FROM mosimo_bsaf_repository').get()!.n, 0);
});

test('unselected source/type and source:jma claim never authorize; original selected tag strings preserved', async t => {
  const f = await fixture(t);
  await f.ingest({ tags: ['bsaf:v1', 'type:weather', 'value:warning', `time:${timestamp(now)}`, 'target:jp', 'source:jma'] });
  assert.equal(await stageNext(f.store, f.config, f.credentials, f.roots, now), null);
  const source = await f.ingest({ tags: ['source:jma', 'bsaf:v1', 'type:earthquake', 'value:05+', `time:${timestamp(now - 30)}`, 'target:jp-kanto', 'custom:unknown'] });
  const row = await stageNext(f.store, f.config, f.credentials, f.roots, now); assert.ok(row);
  assert.deepEqual(JSON.parse(String(row.original_json)).tags, source.record.tags);
});

test('failed staging rolls repo/outbox back; unknown roots and expired retries never renew', async t => {
  const f = await fixture(t); await f.ingest();
  await assert.rejects(stageNext(f.store, f.config, f.credentials, [{ ...f.roots[0], pubkey: hex(nostrPublicKey(randomSecret())) }], now), /unverifiable/);
  assert.equal(f.store.db.prepare('SELECT count(*) n FROM mosimo_bsaf_repository').get()!.n, 0);
  assert.equal(f.store.db.prepare('SELECT count(*) n FROM mosimo_bsaf_publications').get()!.n, 0);
  const row = await stageNext(f.store, f.config, f.credentials, f.roots, now); assert.ok(row);
  let sends = 0;
  await assert.rejects(pushOnce(f.store, f.config, f.credentials, f.roots, now + 570, new AbortController().signal, async () => { sends++; return true; }), /deadline/);
  assert.equal(sends, 0);
  assert.equal(f.store.db.prepare('SELECT event_json FROM mosimo_bsaf_publications').get()!.event_json, row.event_json);
});

test('revocation lists from the issuer domain are fetched, persisted across restarts and stop publication; failures keep state', async t => {
  const f = await fixture(t); await f.ingest();
  assert.equal(await pushOnce(f.store, f.config, f.credentials, f.roots, now, new AbortController().signal, async () => true), true);
  const visited: string[] = [];
  const respond = (body: Uint8Array | null, type = 'application/cbor', status = 200) => async (input: any) => {
    visited.push(String(input));
    return new Response(body as any, { status, headers: { 'content-type': type } });
  };
  // Failures: network, HTTP, wrong type, garbage. Nothing is stored, and the source status records it.
  await assert.doesNotReject(refreshRevocations(f.store, f.credentials, now, async () => { throw new Error('offline'); }));
  assert.deepEqual(await refreshRevocations(f.store, f.credentials, now, respond(null, 'application/cbor', 503)), { refreshed: 0, failed: 1 });
  assert.deepEqual(await refreshRevocations(f.store, f.credentials, now, respond(Uint8Array.of(0xa0), 'text/plain')), { refreshed: 0, failed: 1 });
  assert.deepEqual(await refreshRevocations(f.store, f.credentials, now, respond(Uint8Array.of(0xff))), { refreshed: 0, failed: 1 });
  assert.deepEqual(visited, Array(3).fill('https://pds.example.org/.well-known/mosimo/attest/revocations'));
  assert.equal(f.store.revocations().length, 0);
  assert.match(String(f.store.db.prepare('SELECT status FROM mosimo_bsaf_revocation_sources').get()!.status), /failed/);
  // An empty signed list succeeds and changes nothing.
  const empty = createRevocationList(f.caSecret, f.ca, { generatedAt: timestamp(now), revocations: [] });
  assert.deepEqual(await refreshRevocations(f.store, f.credentials, now, respond(canonical(empty))), { refreshed: 1, failed: 0 });
  await f.ingest();
  assert.ok(await stageNext(f.store, f.config, f.credentials, f.roots, now + 1));
  // The CA revokes the importer leaf; from then on staging and sending fail closed, also after reopening.
  const notice = createNotice(f.caSecret, f.ca, { subject: unhex(f.importer.nostrPubkey), issuedAt: timestamp(now + 2), effectiveAt: timestamp(now + 2), reason: { code: 'compromise' } });
  const list = createRevocationList(f.caSecret, f.ca, { generatedAt: timestamp(now + 2), revocations: [{ $type: REVOCATION, notice }] });
  assert.deepEqual(await refreshRevocations(f.store, f.credentials, now + 2, respond(canonical(list))), { refreshed: 1, failed: 0 });
  assert.equal(f.store.revocations().length, 1);
  let sends = 0;
  await assert.rejects(pushOnce(f.store, f.config, f.credentials, f.roots, now + 3, new AbortController().signal, async () => { sends++; return true; }), /revoked/);
  assert.equal(sends, 0);
  const reopened = openPublicationStore(f.databasePath);
  try {
    assert.equal(reopened.revocations().length, 1);
    assert.ok(await stageNext(reopened, f.config, f.credentials, f.roots, now + 4), 'the staged row stays pending for operator review');
    await assert.rejects(pushOnce(reopened, f.config, f.credentials, f.roots, now + 4, new AbortController().signal, async () => true), /revoked/);
    // A later list that omits the notice does not un-revoke.
    assert.deepEqual(await refreshRevocations(reopened, f.credentials, now + 5, respond(canonical(empty))), { refreshed: 1, failed: 0 });
    assert.equal(reopened.revocations().length, 1);
  } finally { reopened.close(); }
});

const priorityCases: [string, string, number][] = [
  ['tsunami', 'severe-warning', 3], ['tsunami', 'special-warning', 3], ['tsunami', 'warning', 2], ['tsunami', 'advisory', 2],
  ['nankai-trough', 'warning', 3], ['nankai-trough', 'advisory', 2], ['eruption', 'warning', 3], ['eruption', 'advisory', 2],
  ['earthquake', '5-', 2], ['earthquake', '5+', 2], ['earthquake', '6-', 3], ['earthquake', '6+', 3], ['earthquake', '7', 3],
  ['earthquake', '4', 1], ['earthquake', '05+', 1], ['earthquake', '5+ ', 1], ['tsunami', 'WARNING', 1],
  ['tsunami', 'future-warning', 1], ['nankai-trough', 'unknown', 1], ['ashfall', 'warning', 1], ['ashfall', 'severe-warning', 1],
];
test('JMA priority is an exact local selected Bot/type/value policy with explicit overrides', async t => {
  const f = await fixture(t); const config = { ...f.config, priority: null };
  for (const [type, value, expected] of priorityCases) {
    const tags = ['bsaf:v1', `type:${type}`, `value:${value}`, 'time:unchanged', 'target:unchanged', 'source:jma'];
    assert.equal(publicationPriority(config, botDid, tags), expected, `${type}/${value}`);
    assert.equal(publicationPriority(config, 'did:plc:aaaaaaaaaaaaaaaaaaaaaaaa', tags), 1);
    assert.equal(publicationPriority({ ...config, selection: [] }, botDid, tags), 1);
    assert.equal(publicationPriority({ ...config, jmaPriorityPolicy: false }, botDid, tags), 1);
    for (const priority of [0, 1, 2, 3] as const) assert.equal(publicationPriority({ ...config, priority }, botDid, tags), priority);
  }
  assert.equal(publicationPriority(config, botDid, ['type:tsunami', 'type:earthquake', 'value:warning']), 1);
  assert.equal(publicationPriority(config, botDid, ['type:tsunami', 'value:warning', 'value:advisory']), 1);
  assert.throws(() => validatePublisherConfig({ ...config, jmaPriorityPolicy: 'true' }));
  validatePublisherConfig({ ...config, priority: undefined });
});

test('mapped priorities are signed without changing raw tags, and only authorized verification elevates effective priority', async t => {
  const f = await fixture(t); const config = { ...f.config, priority: null };
  for (const [type, value, expected] of priorityCases) {
    const tags = ['bsaf:v1', `type:${type}`, `value:${value}`, `time:${timestamp(now - 30)}`, 'target:jp-kanto', 'source:jma'];
    await f.ingest({ tags });
    const row = await stageNext(f.store, config, f.credentials, f.roots, now); assert.ok(row);
    const event = JSON.parse(String(row.event_json));
    assert.equal(priorityHintOf(event), expected, `${type}/${value}`);
    assert.deepEqual(JSON.parse(String(row.original_json)).tags, tags);
    assert.equal((await f.authorize(event)).effectivePriority, expected);
    await assert.rejects(authorizeImport(event, { roots: [], selection: config.selection, revocations: [], now }));
    assert.equal(await pushOnce(f.store, config, f.credentials, f.roots, now, new AbortController().signal, async () => true), true);
  }
});

test('highest priority stages before the candidate limit, but never overtakes a pending repository base', async t => {
  const f = await fixture(t); const config = { ...f.config, priority: null };
  for (let i = 0; i < 10; i++) await f.ingest({ tags: ['bsaf:v1', 'type:ashfall', 'value:warning', `time:${timestamp(now)}`, 'target:jp', 'source:jma'] });
  const urgent = await f.ingest({ tags: ['bsaf:v1', 'type:tsunami', 'value:special-warning', `time:${timestamp(now)}`, 'target:jp', 'source:jma'] });
  const row = await stageNext(f.store, config, f.credentials, f.roots, now); assert.ok(row); assert.equal(row.cid, urgent.cid);
  assert.equal(priorityHintOf(JSON.parse(String(row.event_json))), 3);
  assert.equal(await pushOnce(f.store, config, f.credentials, f.roots, now, new AbortController().signal, async () => true), true);
  const low = await stageNext(f.store, config, f.credentials, f.roots, now); assert.ok(low);
  assert.equal(priorityHintOf(JSON.parse(String(low.event_json))), 1);
  await f.ingest({ text: 'Another urgent original', tags: ['bsaf:v1', 'type:eruption', 'value:warning', `time:${timestamp(now)}`, 'target:jp', 'source:jma'] });
  assert.equal((await stageNext(f.store, config, f.credentials, f.roots, now))!.event_json, low.event_json);
});

test('a bounded candidate page of unselected types cannot starve a later selected record', async t => {
  const f = await fixture(t);
  for (let i = 0; i < 10; i++) await f.ingest({ tags: ['bsaf:v1', 'type:weather', 'value:warning', `time:${timestamp(now)}`, 'target:jp', 'source:jma'] });
  const selected = await f.ingest();
  const row = await stageNext(f.store, f.config, f.credentials, f.roots, now);
  assert.ok(row); assert.equal(row.cid, selected.cid);
});

test('wire publisher requires exact positive matching OK, timeout/rejection/disconnect remain pending', async t => {
  const f = await fixture(t); await f.ingest();
  const row = await stageNext(f.store, f.config, f.credentials, f.roots, now); const event = JSON.parse(String(row!.event_json));
  const server = new WebSocketServer({ port: 0, host: '127.0.0.1' });
  await new Promise<void>(resolve => server.once('listening', resolve));
  t.after(() => new Promise<void>(resolve => { for (const client of server.clients) client.terminate(); server.close(() => resolve()); }));
  const address = server.address(); if (typeof address === 'string' || !address) throw new Error('No test address');
  const url = `ws://127.0.0.1:${address.port}`;
  let mode = 'positive';
  server.on('connection', ws => ws.on('message', data => {
    assert.deepEqual(JSON.parse(data.toString()), ['EVENT', event]);
    ws.send(JSON.stringify(['OK', '0'.repeat(64), true, 'unrelated']));
    if (mode === 'positive') ws.send(JSON.stringify(['OK', event.id, true, 'duplicate: accepted']));
    if (mode === 'negative') ws.send(JSON.stringify(['OK', event.id, false, 'restricted: denied']));
    if (mode === 'disconnect') ws.close();
  }));
  for (const [value, expected] of [['positive', true], ['negative', false], ['timeout', false], ['disconnect', false]] as const) {
    mode = value; assert.equal(await publishEvent(url, event, new AbortController().signal, 1000), expected);
  }
});
