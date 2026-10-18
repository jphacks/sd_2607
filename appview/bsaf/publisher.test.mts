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
import { openPublicationStore, cacheSnapshot, snapshotStore, stageNext, pushOnce, publishEvent, validatePublisherConfig, refreshSnapshots, publicationPriority, type PublisherConfig } from './publisher.mts';
import { createRoot, createImporter, issueCertificate, createSnapshot, JMA_SELECTION, KIKIKURU_SELECTION, timestamp, keyPublic,
  verifyPublicInformation, rootId, certId, canonical, priorityHint, effectivePriority } from '../../pds/public-information.mjs';

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
  const scope = { orgIds: ['importer'], bsaf: selection };
  const { secretKey, root } = createRoot({ scope, snapshotUrl: 'https://trust.invalid/snapshot.cbor' });
  const importer = createImporter();
  const certificate = issueCertificate(secretKey, { subjectDid: importer.did, subjectPubkey: keyPublic(importer.atprotoSecret),
    orgId: 'importer', orgName: 'Test importer, not JMA', scope, notBefore: timestamp(now - 60), notAfter: timestamp(now + 86400) });
  const credentials = { importer, chain: [certificate] };
  const snapshot = createSnapshot(secretKey, { sequence: 1, issuedAt: timestamp(now - 60), validUntil: timestamp(now + 3540) });
  const roots = [root]; await cacheSnapshot(store, roots, snapshot, now);
  const config: PublisherConfig = { enabled: true, databasePath, credentialsPath: 'not-read-by-tests', trustPath: 'not-read-by-tests',
    relayUrl: 'wss://relay.invalid', selection: selection.map((e: any) => ({ botDid: e.botDid, types: [...e.types] })), maxOriginalAgeSeconds: 600, priority: 3 };
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
  return { databasePath, store, collector, config, credentials, roots, root, secretKey, snapshot, certificate, ingest };
}

test('receiver exports match platform schemas, pin sequence, omit private fields and never enable ESP ingress/clock', () => {
  const scope = { orgIds: ['mosimo-bsaf-importer'], bsaf: JMA_SELECTION };
  const { root } = createRoot({ scope, snapshotUrl: 'https://pds.oriom.dev/.well-known/mosimo/trust/snapshot.cbor' });
  const files = receiverConfigurations({ ...root, secretKey: 'must-never-appear', plcSecret: 'also-private' }, 7, JMA_SELECTION);
  assert.deepEqual(Object.keys(files.android).sort(), ['roots', 'selected']);
  assert.equal(files.android.roots[0]!.rootId, rootId(root.rootPubkey));
  assert.deepEqual(files.android.selected, JMA_SELECTION.flatMap(e => e.types.map(type => ({ botDid: e.botDid, type }))));
  assert.equal(files.pds.publicInformation.roots[0]!.minSequence, 7);
  assert.deepEqual(files.pds.publicInformation.selection, JMA_SELECTION);
  const serialized = JSON.stringify(files);
  assert.ok(!serialized.includes('must-never-appear') && !serialized.includes('also-private') && !serialized.includes('secretKey'));
  assert.match(files.esp32, /^#define MESH_INGRESS_SNAPSHOT_CA R"PEM\(\)PEM"$/m);
  assert.match(files.esp32, /^#define MESH_INGRESS_MIN_SEQUENCE 7ULL$/m);
  assert.doesNotMatch(files.esp32, /^#define MESH_INGRESS_(ENABLED|TRUST_CLOCK)\b/m);
  for (const [name, expected] of [['SCOPE', scope], ['SELECTED', JMA_SELECTION]] as const) {
    const encoded = new RegExp(`#define MESH_INGRESS_${name}_CBOR_HEX "([0-9a-f]+)"`).exec(files.esp32)![1]!;
    assert.deepEqual(Buffer.from(encoded, 'hex'), Buffer.from(canonical(expected)));
  }
  assert.throws(() => receiverConfigurations(root, 0, JMA_SELECTION));
  assert.throws(() => receiverConfigurations({ ...root, snapshotUrl: 'http://unsafe.invalid' }, 7, JMA_SELECTION));
});

test('fixed presets are independent and receiver exports use explicit selected scope', () => {
  assert.deepEqual(presetSelection(), JMA_SELECTION);
  assert.deepEqual(presetSelection(['--jma']), JMA_SELECTION);
  assert.deepEqual(presetSelection(['--kikikuru']), KIKIKURU_SELECTION);
  assert.throws(() => presetSelection(['--all']));
  const both = presetSelection(['--jma', '--kikikuru']);
  const { root } = createRoot({ scope: { orgIds: ['mosimo-bsaf-importer'], bsaf: both }, snapshotUrl: 'https://trust.invalid/snapshot.cbor' });
  const files = receiverConfigurations(root, 8, both);
  assert.equal(files.android.selected.length, 28);
  assert.deepEqual(files.pds.publicInformation.selection, both);
  assert.equal(receiverConfigurations(root, 8, JMA_SELECTION).android.selected.length, 5);
  const limited = { ...root, scope: { ...root.scope, bsaf: JMA_SELECTION } };
  assert.throws(() => receiverConfigurations(limited, 8, both));
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
    const verified = await verifyPublicInformation(event, { roots: f.roots, snapshots: snapshotStore(f.store, f.roots, now), nowSeconds: now, selection: config.selection });
    assert.equal(verified.botDid, weather.botDid);
    assert.equal(verified.type, type);
    assert.equal(priorityHint(event), 1);
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

test('direct collector DB conversion archives original/provenance, durable dedup and repository state', async t => {
  const f = await fixture(t); const source = await f.ingest(); assert.equal(source.status, 'stored');
  const row = await stageNext(f.store, f.config, f.credentials, f.roots, now); assert.ok(row);
  assert.deepEqual(JSON.parse(String(row.original_json)), source.record);
  const provenance = JSON.parse(String(row.provenance_json));
  assert.equal(provenance.did, botDid); assert.equal(provenance.sourceUrl, 'wss://jetstream.invalid/subscribe');
  const event = JSON.parse(String(row.event_json));
  assert.ok(event.content.includes(source.record.text)); assert.ok(event.content.includes(botDid));
  assert.ok(event.content.includes('署名を検証したものではありません'));
  assert.equal(row.deadline, timestamp(now + 570));
  assert.equal((await verifyPublicInformation(event, { roots: f.roots, snapshots: snapshotStore(f.store, f.roots, now), nowSeconds: now, selection: f.config.selection })).authorized, true);
  const retry = await stageNext(f.store, f.config, f.credentials, f.roots, now + 20);
  assert.equal(retry!.event_json, row.event_json); assert.equal(retry!.deadline, row.deadline);
  const reopened = openPublicationStore(f.databasePath);
  try { assert.equal((await stageNext(reopened, f.config, f.credentials, f.roots, now + 30))!.event_json, row.event_json); }
  finally { reopened.close(); }
  assert.equal(f.store.db.prepare('SELECT count(*) n FROM mosimo_bsaf_publications').get()!.n, 1);
  assert.equal(JSON.parse(String(f.store.db.prepare('SELECT state_json FROM mosimo_bsaf_repository').get()!.state_json)).entries.length, 1);
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

test('failed staging rolls repo/outbox back; stale snapshots and expired retries never renew', async t => {
  const f = await fixture(t); await f.ingest();
  await assert.rejects(stageNext(f.store, f.config, f.credentials, [], now));
  assert.equal(f.store.db.prepare('SELECT count(*) n FROM mosimo_bsaf_repository').get()!.n, 0);
  assert.equal(f.store.db.prepare('SELECT count(*) n FROM mosimo_bsaf_publications').get()!.n, 0);
  const row = await stageNext(f.store, f.config, f.credentials, f.roots, now); assert.ok(row);
  let sends = 0;
  await assert.rejects(pushOnce(f.store, f.config, f.credentials, f.roots, now + 570, new AbortController().signal, async () => { sends++; return true; }));
  assert.equal(sends, 0);
  assert.equal(f.store.db.prepare('SELECT event_json FROM mosimo_bsaf_publications').get()!.event_json, row.event_json);
});

test('snapshot rollback/equivocation/revocation removal survive SQLite restart, and root revocation is sticky', async t => {
  const f = await fixture(t);
  const revoked = createSnapshot(f.secretKey, { sequence: 2, issuedAt: timestamp(now), validUntil: timestamp(now + 3600), revokedCertIds: [certId(f.certificate)] });
  await cacheSnapshot(f.store, f.roots, revoked, now);
  const reopened = openPublicationStore(f.databasePath);
  try {
    await assert.rejects(cacheSnapshot(reopened, f.roots, f.snapshot, now));
    const cleared = createSnapshot(f.secretKey, { sequence: 3, issuedAt: timestamp(now), validUntil: timestamp(now + 3600) });
    await assert.rejects(cacheSnapshot(reopened, f.roots, cleared, now));
    const rootRevoked = createSnapshot(f.secretKey, { sequence: 3, issuedAt: timestamp(now), validUntil: timestamp(now + 3600), revokedCertIds: [certId(f.certificate)], revokedPubkeys: [f.root.rootPubkey] });
    await cacheSnapshot(reopened, f.roots, rootRevoked, now);
    assert.ok(snapshotStore(reopened, f.roots, now).get(rootId(f.root.rootPubkey)).body.revokedPubkeys.includes(f.root.rootPubkey));
    const resurrection = createSnapshot(f.secretKey, { sequence: 4, issuedAt: timestamp(now), validUntil: timestamp(now + 3600) });
    await assert.rejects(cacheSnapshot(reopened, f.roots, resurrection, now));
  } finally { reopened.close(); }
});

test('valid A then conflicting valid B poisons both across publication rollback and SQLite restart', async t => {
  const f = await fixture(t);
  await f.ingest();
  assert.equal(await pushOnce(f.store, f.config, f.credentials, f.roots, now, new AbortController().signal, async () => true), true);
  const original = f.store.db.prepare('SELECT event_json FROM mosimo_bsaf_publications').get()!.event_json;
  const conflicting = issueCertificate(f.secretKey, { ...f.certificate.body, orgName: 'Conflicting certificate B' });
  assert.notEqual(certId(conflicting), certId(f.certificate));
  await f.ingest();
  await assert.rejects(stageNext(f.store, f.config, { ...f.credentials, chain: [conflicting] }, f.roots, now), /equivocation/);
  assert.equal(f.store.db.prepare('SELECT count(*) n FROM mosimo_bsaf_publications').get()!.n, 1);
  assert.equal(JSON.parse(String(f.store.db.prepare('SELECT state_json FROM mosimo_bsaf_repository').get()!.state_json)).entries.length, 1);
  assert.equal(f.store.db.prepare('SELECT count(*) n FROM mosimo_bsaf_certificate_equivocations').get()!.n, 1);
  const reopened = openPublicationStore(f.databasePath);
  try {
    const b = f.certificate.body;
    for (const id of [certId(f.certificate), certId(conflicting)]) {
      await assert.rejects(reopened.transaction(async () => snapshotStore(reopened, f.roots, now).recordCertificate(b.issuerPubkey, b.serial, id)), /equivocation/);
    }
    await assert.rejects(reopened.transaction(() => verifyPublicInformation(JSON.parse(String(original)), {
      roots: f.roots, snapshots: snapshotStore(reopened, f.roots, now), nowSeconds: now, selection: f.config.selection,
    })), /equivocation/);
    await assert.rejects(stageNext(reopened, f.config, f.credentials, f.roots, now), /equivocation/);
  } finally { reopened.close(); }
});

test('a caught serial conflict stays poisoned even if its transaction later rolls back for another error', async t => {
  const f = await fixture(t); const b = f.certificate.body;
  await f.store.transaction(async () => {
    snapshotStore(f.store, f.roots, now).recordCertificate(b.issuerPubkey, b.serial, certId(f.certificate));
  });
  await assert.rejects(f.store.transaction(async () => {
    assert.throws(() => snapshotStore(f.store, f.roots, now).recordCertificate(b.issuerPubkey, b.serial, 'f'.repeat(64)), /equivocation/);
    assert.throws(() => snapshotStore(f.store, f.roots, now).recordCertificate(b.issuerPubkey, b.serial, certId(f.certificate)), /equivocation/);
    throw new Error('Unrelated publication failure');
  }), /Unrelated/);
  assert.throws(() => snapshotStore(f.store, f.roots, now).recordCertificate(b.issuerPubkey, b.serial, certId(f.certificate)), /equivocation/);
});

test('equivocation persistence failure rejects and retains evidence for the next durable write', async t => {
  const f = await fixture(t); const b = f.certificate.body;
  snapshotStore(f.store, f.roots, now).recordCertificate(b.issuerPubkey, b.serial, certId(f.certificate));
  f.store.db.exec(`CREATE TRIGGER deny_equivocation BEFORE INSERT ON mosimo_bsaf_certificate_equivocations
    BEGIN SELECT RAISE(ABORT,'simulated disk failure'); END;`);
  await assert.rejects(f.store.transaction(async () => {
    snapshotStore(f.store, f.roots, now).recordCertificate(b.issuerPubkey, b.serial, 'f'.repeat(64));
  }), /simulated disk failure/);
  assert.throws(() => snapshotStore(f.store, f.roots, now).recordCertificate(b.issuerPubkey, b.serial, certId(f.certificate)), /simulated disk failure/);
  f.store.db.exec('DROP TRIGGER deny_equivocation');
  await assert.rejects(f.store.transaction(async () => {
    snapshotStore(f.store, f.roots, now).recordCertificate(b.issuerPubkey, b.serial, certId(f.certificate));
  }), /equivocation/);
  assert.equal(f.store.db.prepare('SELECT count(*) n FROM mosimo_bsaf_certificate_equivocations').get()!.n, 1);
});

test('snapshot refresh isolates earlier HTTP/validation failures and persists later root revocations', async t => {
  const f = await fixture(t);
  const scope = f.root.scope;
  const first = createRoot({ scope, snapshotUrl: 'https://first.invalid/snapshot.cbor' });
  const middle = createRoot({ scope, snapshotUrl: 'https://middle.invalid/snapshot.cbor' });
  const roots = [first.root, middle.root, f.root];
  const revoked = createSnapshot(f.secretKey, { sequence: 2, issuedAt: timestamp(now), validUntil: timestamp(now + 3600), revokedPubkeys: [f.root.rootPubkey] });
  for (const mode of ['network', 'http', 'cbor', 'signature']) {
    const visited: string[] = [];
    const request = t.mock.method(globalThis, 'fetch', async (input: any) => {
      const url = String(input); visited.push(url);
      if (url === first.root.snapshotUrl) {
        if (mode === 'network') throw new Error('First root unavailable');
        if (mode === 'http') return new Response(null, { status: 503 });
        if (mode === 'cbor') return new Response(Uint8Array.of(0xff), { headers: { 'content-type': 'application/cbor' } });
        const invalid = createSnapshot(first.secretKey, { sequence: 1, issuedAt: timestamp(now), validUntil: timestamp(now + 3600) });
        invalid.sig = '0'.repeat(128);
        return new Response(Uint8Array.from(canonical(invalid)), { headers: { 'content-type': 'application/cbor' } });
      }
      if (url === middle.root.snapshotUrl) return new Response(Uint8Array.of(0xa0), { headers: { 'content-type': 'text/plain' } });
      return new Response(Uint8Array.from(canonical(revoked)), { headers: { 'content-type': 'application/cbor' } });
    });
    try {
      assert.deepEqual(await refreshSnapshots(f.store, roots, now), { refreshed: 1, failed: 2 });
      assert.deepEqual(visited, roots.map(r => r.snapshotUrl));
      const accepted = snapshotStore(f.store, roots, now).get(rootId(f.root.rootPubkey));
      assert.equal(accepted.body.sequence, 2); assert.deepEqual(accepted.body.revokedPubkeys, [f.root.rootPubkey]);
    } finally { request.mock.restore(); }
  }
  const reopened = openPublicationStore(f.databasePath);
  try { assert.equal(snapshotStore(reopened, roots, now).get(rootId(f.root.rootPubkey)).body.sequence, 2); }
  finally { reopened.close(); }
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
    assert.equal(priorityHint(event), expected, `${type}/${value}`);
    assert.equal(effectivePriority(event), 1);
    assert.deepEqual(JSON.parse(String(row.original_json)).tags, tags);
    const checked = await f.store.transaction(() => verifyPublicInformation(event, { roots: f.roots,
      snapshots: snapshotStore(f.store, f.roots, now), nowSeconds: now, selection: config.selection }));
    assert.equal(checked.effectivePriority, expected);
    await assert.rejects(verifyPublicInformation(event, { roots: [], snapshots: snapshotStore(f.store, f.roots, now), nowSeconds: now, selection: config.selection }));
    assert.equal(await pushOnce(f.store, config, f.credentials, f.roots, now, new AbortController().signal, async () => true), true);
  }
});

test('highest priority stages before the candidate limit, but never overtakes a pending repository base', async t => {
  const f = await fixture(t); const config = { ...f.config, priority: null };
  for (let i = 0; i < 10; i++) await f.ingest({ tags: ['bsaf:v1', 'type:ashfall', 'value:warning', `time:${timestamp(now)}`, 'target:jp', 'source:jma'] });
  const urgent = await f.ingest({ tags: ['bsaf:v1', 'type:tsunami', 'value:special-warning', `time:${timestamp(now)}`, 'target:jp', 'source:jma'] });
  const row = await stageNext(f.store, config, f.credentials, f.roots, now); assert.ok(row); assert.equal(row.cid, urgent.cid);
  assert.equal(priorityHint(JSON.parse(String(row.event_json))), 3);
  assert.equal(await pushOnce(f.store, config, f.credentials, f.roots, now, new AbortController().signal, async () => true), true);
  const low = await stageNext(f.store, config, f.credentials, f.roots, now); assert.ok(low);
  assert.equal(priorityHint(JSON.parse(String(low.event_json))), 1);
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
