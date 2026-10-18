// Node 24, local Podman (not a remote VM):
// HAPPYVIEW_IMAGE=localhost/your-existing-2.16-image node --test integration.test.mts
// Never accepts a database path, credentials, or records from a running deployment.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { randomBytes, randomUUID } from 'node:crypto';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { test } from 'node:test';
import { setTimeout as delay } from 'node:timers/promises';
import { cidForLex } from '@atproto/lex-cbor';
import { parseCid } from '@atproto/lex-data';
import { prepare } from './prepare.mts';
import { COLLECTION, openStore, type Store } from './store.mts';

const image = process.env.HAPPYVIEW_IMAGE;
const podman = process.env.PODMAN || 'podman';
const mosimoCollection = 'dev.oriom.mosimo.post';
const query = 'dev.oriom.mosimo.getFeed';
const author = 'did:plc:syntheticapprovedbsaf';
const stranger = 'did:plc:syntheticunapprovedbsaf';
const source = 'wss://synthetic-jetstream.invalid/subscribe';
const bsafUri = `at://${author}/${COLLECTION}/synthetic-one`;
const mosimoUri = `at://${author}/${mosimoCollection}/synthetic-mosimo`;

interface Provenance {
  did: string;
  cid: string;
  collection: string;
  indexedAt: string;
  retrievedAt?: string;
  source?: string;
}
interface Feed {
  records: Record<string, unknown>[];
  record?: Record<string, unknown>;
  provenance: Record<string, Provenance>;
  cursor?: string;
}

// Never include subprocess arguments, stderr, container logs, or environment in
// failures: these can contain generated secrets (even though all are disposable).
function command(args: string[]): string {
  const result = spawnSync(podman, args, { encoding: 'utf8', timeout: 30_000 });
  if (result.error || result.status !== 0) {
    throw new Error(`Podman ${args[0]} failed; check local Podman availability and image (output suppressed)`);
  }
  return result.stdout.trim();
}

test('real HappyView 2.16: isolated BSAF ingestion and combined HTTP feed', {
  skip: image ? false : 'Set HAPPYVIEW_IMAGE to an existing local HappyView 2.16 image',
  timeout: 180_000,
}, async () => {
  assert.equal(process.versions.node.split('.')[0], '24', 'Run with Node 24');
  command(['image', 'exists', image!]); // Never pull an image implicitly.
  const directory = mkdtempSync(join(tmpdir(), 'mosimo-bsaf-integration-'));
  const data = join(directory, 'data');
  mkdirSync(data);
  const databasePath = join(data, 'happyview.db');
  const name = `mosimo-bsaf-test-${randomUUID()}`;
  const network = `${name}-network`;
  const envFile = join(directory, 'dummy.env');
  let networkCreated = false;
  let containerCreated = false;
  let db: DatabaseSync | undefined;
  let store: Store | undefined;
  let origin = '';

  async function ready(): Promise<void> {
    const binding = command(['port', name, '3000/tcp']);
    assert.match(binding, /^127\.0\.0\.1:\d+$/, 'Only a loopback auto-assigned port may be published');
    origin = `http://${binding}`;
    const deadline = Date.now() + 40_000;
    let http = 'not attempted';
    while (Date.now() < deadline) {
      try {
        const response = await fetch(`${origin}/`, { signal: AbortSignal.timeout(1000) });
        await response.body?.cancel();
        if (response.ok) return;
        http = `status ${response.status}`;
      } catch (error) {
        const code = (error as { cause?: { code?: unknown } }).cause?.code;
        http = typeof code === 'string' && /^[A-Z_]+$/.test(code) ? code : 'request failed';
      }
      await delay(200);
    }
    const state = JSON.parse(command(['inspect', '--format', '{{json .State}}', name]));
    const addresses = JSON.parse(command(['inspect', '--format', '{{json .NetworkSettings.Networks}}', name]));
    let direct = 'unavailable';
    for (const settings of Object.values(addresses) as { IPAddress?: string }[]) {
      if (!settings.IPAddress || !/^\d+\.\d+\.\d+\.\d+$/.test(settings.IPAddress)) continue;
      try {
        const response = await fetch(`http://${settings.IPAddress}:3000/`, { signal: AbortSignal.timeout(1000) });
        direct = `status ${response.status}`;
        await response.body?.cancel();
      } catch { direct = 'request failed'; }
    }
    throw new Error(`Scratch HappyView readiness failed after 40s: running=${state.Running === true}, ` +
      `exit=${Number(state.ExitCode)}, oom=${state.OOMKilled === true}, published=${http}, direct=${direct}`);
  }
  async function feed(params: Record<string, string> = {}): Promise<Feed> {
    const response = await fetch(`${origin}/xrpc/${query}?${new URLSearchParams(params)}`, {
      signal: AbortSignal.timeout(5000),
    });
    assert.equal(response.status, 200, 'Scratch getFeed must succeed');
    return await response.json() as Feed;
  }
  async function onlyMosimo(): Promise<void> {
    const result = await feed();
    assert.deepEqual(result.records, [{ ...mosimo, uri: mosimoUri }]);
    assert.deepEqual(Object.keys(result.provenance), [mosimoUri]);
    assert.equal(result.cursor, undefined);
    assert.deepEqual(await feed({ did: stranger }), { records: [], provenance: {} });
    assert.deepEqual((await feed({ did: author })).records, result.records);
    const missing = await fetch(`${origin}/xrpc/${query}?${new URLSearchParams({ uri: bsafUri })}`, {
      signal: AbortSignal.timeout(5000),
    });
    await missing.body?.cancel();
    assert.ok(!missing.ok, 'Removed BSAF URI must not resolve successfully');
    assert.deepEqual(db!.prepare('SELECT * FROM happyview_records WHERE uri = ?').get(mosimoUri), mosimoRow);
  }

  const mosimo = {
    $type: mosimoCollection,
    text: 'SYNTHETIC TEST ONLY — not a real alert or signed repository record',
    createdAt: '2020-01-01T00:00:00.000Z',
    deliveryExpiresAt: '2020-01-02T00:00:00.000Z',
    extensions: [],
  };
  let mosimoRow: ReturnType<ReturnType<DatabaseSync['prepare']>['get']>;
  try {
    writeFileSync(envFile, [
      'DATABASE_URL=sqlite:///data/happyview.db?mode=rwc',
      'DATABASE_BACKEND=sqlite', 'HOST=0.0.0.0', 'PORT=3000',
      'PUBLIC_URL=http://127.0.0.1:3000', 'BASE_PATH=', 'PLUGIN_URLS=',
      `SESSION_SECRET=${randomBytes(48).toString('base64')}`,
      `TOKEN_ENCRYPTION_KEY=${randomBytes(32).toString('base64')}`,
      'JETSTREAM_URL=ws://127.0.0.1:1',
      'RELAY_URL=http://127.0.0.1:1', 'PLC_URL=http://127.0.0.1:1',
      // v2.16 has no telemetry-off env; consent defaults off, and is also
      // explicitly seeded below. Sink the known collector URL before boot.
      'TELEMETRY_COLLECTOR_URL=http://127.0.0.1:1', 'RUST_LOG=warn',
    ].join('\n') + '\n', { mode: 0o600 });
    // --internal disables bridge forwarding, including the published loopback
    // endpoint on Netavark. Keep DNAT, but omit the external route and DNS.
    command(['network', 'create', '--disable-dns', '--opt', 'no_default_route=true', network]);
    networkCreated = true;
    // Rootless keep-id lets Node read the mounted scratch SQLite file. Rootful
    // callers already share uid 0. No production volume is ever mounted.
    const userns = process.getuid?.() ? ['--userns=keep-id'] : [];
    command(['create', '--name', name, '--pull=never', '--network', network,
      '--publish', '127.0.0.1::3000', '--memory', '384m', '--log-level=warn',
      '--cap-drop=ALL', '--dns', '127.0.0.1',
      '--log-driver=none', '--env-file', envFile, ...userns,
      '--volume', `${data}:/data:Z`, image!]);
    containerCreated = true;
    command(['start', name]);
    await ready(); // Real HappyView creates and migrates a completely new DB.
    command(['stop', '--time', '5', name]);

    db = new DatabaseSync(databasePath);
    db.exec('PRAGMA busy_timeout=3000; PRAGMA foreign_keys=ON;');
    assert.equal(db.prepare('SELECT count(*) AS n FROM happyview_records').get()!.n, 0);
    // Columns/defaults verified against upstream v2.16.0 SQLite migrations:
    // create_lexicons, add_target_collection, scripts_by_trigger, prefix_table_names.
    const insertLexicon = db.prepare(`INSERT INTO happyview_lexicons
      (id,lexicon_json,backfill,target_collection) VALUES(?,?,0,?)`);
    for (const id of [mosimoCollection, query]) {
      const file = id === query ? 'getFeed' : 'post';
      const lexicon = JSON.parse(readFileSync(new URL(`../../lexicons/dev/oriom/mosimo/${file}.json`, import.meta.url), 'utf8'));
      insertLexicon.run(id, JSON.stringify(lexicon), id === query ? mosimoCollection : null);
    }
    db.prepare(`INSERT INTO happyview_instance_settings(key,value,updated_at)
      VALUES('telemetry.mode','off',?) ON CONFLICT(key) DO UPDATE SET value='off'`)
      .run(new Date().toISOString());
    const config = { databasePath, authors: [author], jetstreamUrl: source };
    prepare(config);
    prepare(config); // A managed installation is idempotent.
    assert.equal(db.prepare('SELECT 1 FROM happyview_lexicons WHERE id = ?').get(COLLECTION), undefined);
    const mosimoCid = (await cidForLex(mosimo)).toString();
    db.prepare(`INSERT INTO happyview_records(uri,did,collection,rkey,record,cid,indexed_at,created_at)
      VALUES(?,?,?,?,?,?,?,?)`).run(mosimoUri, author, mosimoCollection, 'synthetic-mosimo',
      JSON.stringify(mosimo), mosimoCid, '2020-01-01T00:00:00.000Z', '2020-01-01T00:00:00.000Z');
    mosimoRow = db.prepare('SELECT * FROM happyview_records WHERE uri = ?').get(mosimoUri);

    command(['start', name]); // Startup reloads the seeded lexicons.
    await ready();
    store = openStore(databasePath, [author], source);
    const blobCid = (await cidForLex({ synthetic: 'not real image bytes' })).toString();
    const original = {
      $type: COLLECTION,
      text: 'SYNTHETIC ONLY — https://example.invalid/test #test',
      createdAt: '2020-01-01T00:00:01.123Z',
      tags: ['source:synthetic', 'lang:ja', 'bsaf:v1', 'unrecognized:test', 'lang:en'],
      langs: ['ja', 'en'],
      facets: [{ index: { byteStart: 0, byteEnd: 9 },
        features: [{ $type: 'app.bsky.richtext.facet#link', uri: 'https://example.invalid/test' }] }],
      embed: { $type: 'app.bsky.embed.images', images: [{ alt: 'SYNTHETIC — no real image',
        image: { $type: 'blob', ref: { $link: blobCid }, mimeType: 'image/png', size: 1 },
        aspectRatio: { width: 1, height: 1 } }] },
      syntheticExtension: { bytes: { $bytes: 'AAEC' }, unknown: ['preserve', 'order'] },
    };
    // Hash CBOR links/bytes, while submitting the untouched Jetstream Lex JSON.
    async function event(time_us: number, record = original, operation = 'create', did = author) {
      const cbor = { ...record, embed: { ...record.embed, images: record.embed.images.map(image => ({
        ...image, image: { ...image.image, ref: parseCid(image.image.ref.$link) },
      })) }, syntheticExtension: { ...record.syntheticExtension, bytes: Uint8Array.from([0, 1, 2]) } };
      return { did, kind: 'commit', time_us, commit: { collection: COLLECTION,
        rkey: 'synthetic-one', operation, record, cid: (await cidForLex(cbor)).toString() } };
    }
    const created = await event(1);
    const before = Date.now();
    assert.equal(await store.ingest(created), 'stored');
    const after = Date.now();
    // A source tag/embedded identity must never promote an unapproved envelope DID.
    assert.equal(await store.ingest(await event(2, { ...original,
      tags: [...original.tags, `source:${author}`] }, 'create', stranger)), 'ignored');
    assert.equal(db.prepare('SELECT 1 FROM happyview_records WHERE did = ?').get(stranger), undefined);
    const stored = db.prepare('SELECT * FROM happyview_records WHERE uri = ?').get(bsafUri)!;
    assert.equal(stored.cid, created.commit.cid, 'Base HappyView cid must be the original CID');
    assert.deepEqual(JSON.parse(String(stored.record)), original);
    assert.equal(stored.did, author);
    assert.equal(stored.collection, COLLECTION);
    const metadata = db.prepare('SELECT * FROM mosimo_bsaf_events WHERE uri = ?').get(bsafUri)!;
    assert.equal(metadata.cid, created.commit.cid);
    assert.equal(metadata.source_url, source);
    assert.ok(Date.parse(String(metadata.fetched_at)) >= before && Date.parse(String(metadata.fetched_at)) <= after);

    const combined = await feed();
    assert.deepEqual(combined.records[0].tags, original.tags);
    assert.deepEqual(combined.records[0].langs, original.langs);
    assert.deepEqual(combined.records, [{ ...original, uri: bsafUri }, { ...mosimo, uri: mosimoUri }]);
    assert.equal(combined.cursor, undefined);
    assert.deepEqual(combined.provenance[bsafUri], {
      did: author, cid: created.commit.cid, collection: COLLECTION,
      indexedAt: stored.indexed_at, retrievedAt: metadata.fetched_at, source,
    });
    assert.deepEqual(combined.provenance[mosimoUri], {
      did: author, cid: mosimoCid, collection: mosimoCollection, indexedAt: mosimoRow!.indexed_at,
    });
    const first = await feed({ limit: '1' });
    assert.deepEqual(first.records, [combined.records[0]]);
    assert.equal(first.cursor, '1');
    const second = await feed({ limit: '1', cursor: first.cursor! });
    assert.deepEqual(second.records, [combined.records[1]]);
    assert.equal(second.cursor, undefined);
    assert.deepEqual((await feed({ limit: '1', cursor: '2' })).records, []);
    assert.deepEqual((await feed({ did: author })).records, combined.records);
    assert.deepEqual((await feed({ did: stranger })).records, []);
    const single = await feed({ uri: bsafUri });
    assert.deepEqual(single.record, combined.records[0]);
    assert.deepEqual(single.provenance, { [bsafUri]: combined.provenance[bsafUri] });
    assert.deepEqual((await feed({ uri: mosimoUri, did: author })).record, combined.records[1]);

    assert.equal(await store.ingest(await event(3, { ...original, tags: ['source:synthetic'] }, 'update')), 'deleted');
    await onlyMosimo();
    assert.equal(await store.ingest(await event(4)), 'stored');
    assert.equal((await feed()).records.length, 2);
    assert.equal(await store.ingest({ did: author, kind: 'commit', time_us: 5,
      commit: { collection: COLLECTION, rkey: 'synthetic-one', operation: 'delete' } }), 'deleted');
    assert.equal(db.prepare('SELECT 1 FROM happyview_records WHERE uri = ?').get(bsafUri), undefined);
    assert.equal(db.prepare('SELECT cid FROM mosimo_bsaf_events WHERE uri = ?').get(bsafUri)!.cid, null);
    await onlyMosimo();
    assert.equal(await store.ingest(await event(6)), 'stored');
    assert.equal((await feed()).records.length, 2);
    store.close();
    store = undefined;
    prepare({ ...config, authors: [] });
    store = openStore(databasePath, [], source);
    assert.equal(await store.ingest(await event(7)), 'ignored');
    assert.equal(db.prepare('SELECT 1 FROM happyview_records WHERE uri = ?').get(bsafUri), undefined);
    assert.equal(db.prepare('SELECT count(*) AS n FROM mosimo_bsaf_sources').get()!.n, 0);
    await onlyMosimo();

    const trigger = `xrpc.query:${query}`;
    const userBody = '-- synthetic independently edited script\nfunction handle() return { userOwned = true } end';
    db.prepare('UPDATE happyview_scripts SET body = ? WHERE id = ?').run(userBody, trigger);
    assert.throws(() => prepare(config), /Refusing to replace an independently edited/);
    assert.equal(db.prepare('SELECT body FROM happyview_scripts WHERE id = ?').get(trigger)!.body, userBody);
    assert.equal(db.prepare('SELECT count(*) AS n FROM mosimo_bsaf_sources').get()!.n, 0,
      'Refused setup must not change the allowlist');
    assert.deepEqual(db.prepare('SELECT * FROM happyview_records WHERE uri = ?').get(mosimoUri), mosimoRow);
  } finally {
    // Try every cleanup even if a preceding step fails. rm never touches anything
    // outside the directory this test just created; no logs/secrets are printed.
    try { store?.close(); } finally {
      try { db?.close(); } finally {
        try {
          if (containerCreated) command(['rm', '--force', '--time', '5', name]);
        } finally {
          try {
            if (networkCreated) command(['network', 'rm', network]);
          } finally { rmSync(directory, { recursive: true, force: true }); }
        }
      }
    }
  }
});
