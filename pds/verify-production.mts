#!/usr/bin/env node
// Node 24 native type stripping. No runtime imports from the implementation under test.
// Run from pds: node verify-production.mts prepare|relay|pipeline|reorder|upstream|upstream-live|compatibility-live|selftest
import assert from 'node:assert/strict';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { mkdir, readFile, writeFile, rename, chmod, open, unlink } from 'node:fs/promises';
import { unlinkSync } from 'node:fs';
import { setTimeout as sleep } from 'node:timers/promises';
import { randomUUID } from 'node:crypto';
import { Secp256k1Keypair, verifySignature } from '@atproto/crypto';
import { TID } from '@atproto/common-web';
import { MST, BlockMap, MemoryBlockstore, readCar, blocksToCarFile } from '@atproto/repo';
import * as cbor from '@ipld/dag-cbor';
import { decodeFirst } from 'cborg';
import { CID } from 'multiformats/cid';
import { base32 } from 'multiformats/bases/base32';
import { sha256 } from 'multiformats/hashes/sha2';
import { finalizeEvent, generateSecretKey, verifyEvent, type Event } from 'nostr-tools';

const production = 'https://pds.oriom.dev';
const relayUrl = 'wss://pds.oriom.dev';
const plc = 'https://plc.directory';
const namespace = 'mosimo.oriom.dev';
const collection = 'dev.oriom.mosimo.post';
const directory = join(homedir(), '.mosimo-pds-verification');
const statePath = join(directory, 'state.json');
const lockPath = join(directory, 'lock');
const stage = process.argv[2];
const deadline = Date.now() + 58_000;
const remaining = () => Math.max(1, Math.min(10_000, deadline - Date.now()));
const b64 = (bytes: Uint8Array) => Buffer.from(bytes).toString('base64').replace(/=+$/, '');
const unb64 = (text: string) => new Uint8Array(Buffer.from(text, 'base64'));
const cidFor = async (bytes: Uint8Array) => CID.createV1(cbor.code, await sha256.digest(bytes));

type Operation = {
  type: 'plc_operation'; verificationMethods: { atproto: string }; rotationKeys: string[];
  alsoKnownAs: string[]; services: { atproto_pds: { type: string; endpoint: string } };
  prev: null; sig: string;
};
type Fixture = {
  event: Event; commitCid: string; commitBytes: string; recordCid: string;
  recordBytes: string; path: string; rev: string; base: string | null;
};
type UpstreamEntry = { relay: string; fixture: Fixture; attemptedAt?: string; accepted?: boolean; evidence?: Json };
type State = {
  version: 1; run: string; did: string; rotationPrivate: string; repoPrivate: string;
  nostrPrivate: string; repoKey: string; rotationKey: string; genesis: Operation;
  registered: boolean; fixtures: Fixture[]; probes: Event[]; malformed: Event; liveControl?: Event;
  upstream?: UpstreamEntry;
  upstreamLive?: UpstreamEntry;
  compatibilityLive?: { fixture: Fixture; attemptedAt?: string; evidence?: Json };
  reorder?: { fixtures: Fixture[]; attemptedAt?: string; parentAttemptedAt?: string; gate?: Json };
};
type Ack = { id: string; accepted: boolean | null; message: string };
type RelayEvidence = { url: string; acknowledgements: Ack[]; live: string[]; history: string[]; eose: boolean };
type Json = Record<string, unknown>;
let state: State | undefined;
let locked = false;
let emitted = false;
const sockets = new Set<WebSocket>();
function emit(value: Json) {
  emitted = true;
  console.log(JSON.stringify({ stage, production, ...value }));
}
// Revisions are increasing at creation, independent of which optional stage ran first.
function fixtureChain(s: State, planned = false): Fixture[] {
  return [...s.fixtures,
    ...(s.upstream && (planned || s.upstream.attemptedAt) ? [s.upstream.fixture] : []),
    ...(s.reorder && (planned || s.reorder.attemptedAt) ? s.reorder.fixtures : []),
    ...(s.upstreamLive && (planned || s.upstreamLive.attemptedAt) ? [s.upstreamLive.fixture] : []),
    ...(s.compatibilityLive && (planned || s.compatibilityLive.attemptedAt) ? [s.compatibilityLive.fixture] : []),
  ].sort((a, b) => a.rev < b.rev ? -1 : a.rev > b.rev ? 1 : 0);
}
function publicIdentity(s: State) {
  return { run: s.run, did: s.did, repoKey: s.repoKey, rotationKey: s.rotationKey,
    registered: s.registered, commits: fixtureChain(s, true).map(f => ({ eventId: f.event.id,
      commitCid: f.commitCid, recordCid: f.recordCid, path: f.path, rev: f.rev, base: f.base })),
    upstream: s.upstream && { relay: s.upstream.relay, eventId: s.upstream.fixture.event.id,
      commitCid: s.upstream.fixture.commitCid, base: s.upstream.fixture.base },
    upstreamLive: s.upstreamLive && { relay: s.upstreamLive.relay, eventId: s.upstreamLive.fixture.event.id,
      commitCid: s.upstreamLive.fixture.commitCid, base: s.upstreamLive.fixture.base },
    compatibilityLive: s.compatibilityLive && { eventId: s.compatibilityLive.fixture.event.id,
      commitCid: s.compatibilityLive.fixture.commitCid, base: s.compatibilityLive.fixture.base,
      attemptedAt: s.compatibilityLive.attemptedAt },
    reorder: s.reorder && { eventIds: s.reorder.fixtures.map(f => f.event.id),
      attemptedAt: s.reorder.attemptedAt, parentAttemptedAt: s.reorder.parentAttemptedAt } };
}
async function save(s: State) {
  // Atomic replacement; the previous complete recovery state survives interrupted writes.
  const temporary = join(directory, 'state.json.tmp');
  await writeFile(temporary, JSON.stringify(s), { mode: 0o600, flush: true });
  await chmod(temporary, 0o600);
  await rename(temporary, statePath);
  await chmod(statePath, 0o600);
}
async function request(url: string, init: RequestInit = {}) {
  assert.ok(Date.now() < deadline, 'Stage deadline exceeded');
  // Keep the signal alive while the caller consumes the body, not just response headers.
  return fetch(url, { ...init, redirect: 'error', signal: AbortSignal.timeout(remaining()) });
}
async function json(url: string, init: RequestInit = {}): Promise<Json> {
  const response = await request(url, init);
  const body = await response.json() as Json;
  assert.ok(response.ok, `HTTP ${response.status}: ${JSON.stringify(body).slice(0, 300)}`);
  return body;
}
function sign(s: State, kind: number, content: string, tags: string[][] = []) {
  return finalizeEvent({ kind, content, tags, created_at: Math.floor(Date.now() / 1000) }, unb64(s.nostrPrivate));
}
async function fixture(s: State, previous: Fixture[], label = 'chained record'): Promise<Fixture> {
  const key = await Secp256k1Keypair.import(unb64(s.repoPrivate));
  const rev = TID.nextStr(previous.at(-1)?.rev);
  const path = `${collection}/${rev}`;
  const createdAt = new Date(Math.floor(Date.now() / 1000) * 1000).toISOString();
  const deliveryExpiresAt = new Date(Date.parse(createdAt) + 86400_000).toISOString();
  const text = `[PRODUCTION VERIFICATION TEST ${s.run}] ${label} ${previous.length + 1}; not disaster information`;
  const recordBytes = cbor.encode({ $type: collection, text, createdAt, deliveryExpiresAt, extensions: [] });
  const recordCid = await cidFor(recordBytes);
  let tree = await MST.create(new MemoryBlockstore());
  for (const old of previous) tree = await tree.add(old.path, CID.parse(old.recordCid));
  tree = await tree.add(path, recordCid);
  // Protocol v1 chains via the signed Nostr base tag; v3 commit prev remains null.
  const unsigned = { did: s.did, version: 3, data: await tree.getPointer(), rev, prev: null };
  const commitBytes = cbor.encode({ ...unsigned, sig: await key.sign(cbor.encode(unsigned)) });
  const base = previous.at(-1)?.commitCid ?? null;
  const event = finalizeEvent({ kind: 1, content: text, created_at: Date.parse(createdAt) / 1000, tags: [
    ['t', namespace], [`${namespace}:version`, '1'], [`${namespace}:commit`, b64(commitBytes)],
    [`${namespace}:base`, base ?? ''], [`${namespace}:path`, path],
    [`${namespace}:created-at`, createdAt], [`${namespace}:delivery-expires-at`, deliveryExpiresAt],
  ] }, unb64(s.nostrPrivate));
  return { event, commitCid: (await cidFor(commitBytes)).toString(), commitBytes: b64(commitBytes),
    recordCid: recordCid.toString(), recordBytes: b64(recordBytes), path, rev, base };
}
async function createState(): Promise<State> {
  const rotation = await Secp256k1Keypair.create({ exportable: true });
  const repo = await Secp256k1Keypair.create({ exportable: true });
  const unsigned = { type: 'plc_operation' as const, verificationMethods: { atproto: repo.did() },
    rotationKeys: [rotation.did()], alsoKnownAs: [], services: {
      atproto_pds: { type: 'AtprotoPersonalDataServer', endpoint: production },
    }, prev: null };
  // Reference: did-method-plc/did-method-plc main packages/lib/src/operations.ts
  const genesis = { ...unsigned, sig: Buffer.from(await rotation.sign(cbor.encode(unsigned))).toString('base64url') };
  const hash = await sha256.digest(cbor.encode(genesis));
  const did = `did:plc:${base32.baseEncode(hash.digest).slice(0, 24)}`;
  const s: State = { version: 1, run: `mosimo-production-test-${randomUUID()}`, did,
    rotationPrivate: b64(await rotation.export()), repoPrivate: b64(await repo.export()),
    nostrPrivate: b64(generateSecretKey()), repoKey: repo.did(), rotationKey: rotation.did(), genesis,
    registered: false, fixtures: [], probes: [], malformed: {} as Event };
  for (let i = 0; i < 3; i++) s.fixtures.push(await fixture(s, s.fixtures));
  const prefix = `[PRODUCTION VERIFICATION TEST ${s.run}] not disaster information`;
  s.probes = [
    sign(s, 0, JSON.stringify({ name: s.run, about: prefix })),
    sign(s, 1, `${prefix}; ordinary non-protocol text`),
    sign(s, 2, relayUrl),
    sign(s, 5, `${prefix}; delete nonexistent test event`, [['e', '0'.repeat(64)]]),
    sign(s, 7, '+', [['e', s.fixtures[0].event.id], ['p', s.fixtures[0].event.pubkey]]),
    sign(s, 30023, prefix, [['d', s.run], ['title', 'Production verification test']]),
    sign(s, 10002, '', [['r', relayUrl]]),
  ];
  const malformed = sign(s, 1, `${prefix}; intentionally invalid signature`);
  s.malformed = { ...malformed, sig: '0'.repeat(128) };
  return s;
}
async function validateState(s: State) {
  assert.equal(s.version, 1);
  assert.equal(s.genesis.services.atproto_pds.endpoint, production);
  assert.notEqual(s.repoKey, s.rotationKey);
  assert.equal((await Secp256k1Keypair.import(unb64(s.repoPrivate))).did(), s.repoKey);
  assert.equal((await Secp256k1Keypair.import(unb64(s.rotationPrivate))).did(), s.rotationKey);
  const { sig, ...unsigned } = s.genesis;
  assert.ok(await verifySignature(s.rotationKey, cbor.encode(unsigned), Buffer.from(sig, 'base64url')));
  assert.equal(s.did, `did:plc:${base32.baseEncode((await sha256.digest(cbor.encode(s.genesis))).digest).slice(0, 24)}`);
  assert.equal(s.fixtures.length, 3);
  let previous: Fixture | undefined;
  if (s.reorder) assert.equal(s.reorder.fixtures.length, 3);
  for (const f of fixtureChain(s, true)) {
    assert.ok(verifyEvent(JSON.parse(JSON.stringify(f.event))));
    assert.equal((await cidFor(unb64(f.commitBytes))).toString(), f.commitCid);
    assert.equal((await cidFor(unb64(f.recordBytes))).toString(), f.recordCid);
    assert.equal(f.base, previous?.commitCid ?? null);
    assert.ok(/^[234567abcdefghij][234567abcdefghijklmnopqrstuvwxyz]{12}$/.test(f.rev));
    if (previous) assert.ok(f.rev > previous.rev);
    const record = cbor.decode(unb64(f.recordBytes)) as { createdAt: string };
    assert.equal(Math.floor(Date.parse(record.createdAt) / 1000), f.event.created_at);
    const commit = cbor.decode(unb64(f.commitBytes)) as { sig: Uint8Array; did: string };
    const { sig: commitSig, ...data } = commit;
    assert.equal(commit.did, s.did);
    assert.ok(await verifySignature(s.repoKey, cbor.encode(data), commitSig));
    previous = f;
  }
}
async function prepare(s: State) {
  // Recovery state (including the *signed* genesis) is on disk before any PLC side effect.
  const existing = await request(`${plc}/${s.did}/data`);
  if (existing.status === 404 && !s.registered) {
    const response = await request(`${plc}/${s.did}`, { method: 'POST',
      headers: { 'content-type': 'application/json' }, body: JSON.stringify(s.genesis) });
    assert.ok(response.ok, `PLC registration HTTP ${response.status}: ${(await response.text()).slice(0, 300)}`);
  } else assert.ok(existing.ok, `PLC lookup HTTP ${existing.status}; refusing a new identity`);
  const data = await json(`${plc}/${s.did}/data`);
  assert.deepEqual(data.verificationMethods, s.genesis.verificationMethods);
  assert.deepEqual(data.rotationKeys, s.genesis.rotationKeys);
  assert.deepEqual(data.services, s.genesis.services);
  s.registered = true;
  await save(s);
  return { ok: true, identity: publicIdentity(s), plcVerified: true };
}

class Relay {
  socket: WebSocket;
  frames: unknown[][] = [];
  constructor(url: string) {
    this.socket = new WebSocket(url);
    sockets.add(this.socket);
    this.socket.addEventListener('message', event => {
      if (typeof event.data !== 'string') return;
      try { const value = JSON.parse(event.data); if (Array.isArray(value)) this.frames.push(value); } catch { /* Non-JSON is not evidence. */ }
    });
  }
  async connect() {
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('WebSocket connect timeout')), remaining());
      this.socket.addEventListener('open', () => { clearTimeout(timer); resolve(); }, { once: true });
      this.socket.addEventListener('error', () => { clearTimeout(timer); reject(new Error('WebSocket connection failed')); }, { once: true });
    });
  }
  send(frame: unknown[]) { this.socket.send(JSON.stringify(frame)); }
  async wait(predicate: () => boolean, milliseconds = 10_000) {
    const until = Math.min(deadline, Date.now() + milliseconds);
    while (!predicate() && Date.now() < until && this.socket.readyState === WebSocket.OPEN) await sleep(50);
    return predicate();
  }
  close() { this.socket.close(); sockets.delete(this.socket); }
  events(subscription: string) {
    return this.frames.filter(f => f[0] === 'EVENT' && f[1] === subscription).map(f => (f[2] as Event).id);
  }
}
async function relayProbe(s: State) {
  // A separate persisted ordinary event tests live delivery even if the protocol fixtures are retries.
  if (!s.liveControl) {
    s.liveControl = sign(s, 1, `[PRODUCTION VERIFICATION TEST ${s.run}] live delivery control; not disaster information`);
    await save(s);
  }
  const relay = new Relay(relayUrl);
  const events = [...s.probes, s.malformed, s.fixtures[1].event, s.fixtures[2].event, s.fixtures[0].event, s.liveControl];
  const evidence: RelayEvidence = { url: relayUrl, acknowledgements: [], live: [], history: [], eose: false };
  try {
    await relay.connect();
    // No wall-clock since filter: prepared events may be older than this invocation.
    relay.send(['REQ', 'live', { ids: events.map(e => e.id), limit: 100 }]);
    const liveReady = await relay.wait(() => relay.frames.some(f => f[0] === 'EOSE' && f[1] === 'live'));
    const liveStart = relay.frames.length;
    // Publish child and grandchild BEFORE base, on this one ordered connection.
    for (const event of events) relay.send(['EVENT', event]);
    await relay.wait(() => events.every(e => relay.frames.some(f => f[0] === 'OK' && f[1] === e.id)));
    await relay.wait(() => relay.frames.slice(liveStart).some(f => f[0] === 'EVENT'
      && f[1] === 'live' && (f[2] as Event).id === s.liveControl!.id), 2000);
    for (const event of events) {
      const ack = relay.frames.find(f => f[0] === 'OK' && f[1] === event.id);
      evidence.acknowledgements.push({ id: event.id, accepted: ack ? ack[2] === true : null,
        message: ack ? String(ack[3]).slice(0, 300) : 'ACK timeout' });
    }
    relay.send(['REQ', 'history', { ids: events.map(e => e.id), limit: 100 }]);
    evidence.eose = await relay.wait(() => relay.frames.some(f => f[0] === 'EOSE' && f[1] === 'history'));
    evidence.live = [...new Set(relay.frames.slice(liveStart).filter(f => f[0] === 'EVENT'
      && f[1] === 'live').map(f => (f[2] as Event).id))];
    evidence.history = [...new Set(relay.events('history'))];
    const invalid = evidence.acknowledgements.find(a => a.id === s.malformed.id);
    const malformedRejected = invalid?.accepted === false && !evidence.history.includes(s.malformed.id)
      && !evidence.live.includes(s.malformed.id);
    return { ok: malformedRejected, identity: publicIdentity(s), evidence, malformedRejected,
          liveSubscriptionReady: liveReady, liveControlId: s.liveControl.id,
          liveControlReceived: evidence.live.includes(s.liveControl.id),
      publicationOrder: events.map(e => ({ id: e.id, kind: e.kind })),
      capabilities: s.probes.map(e => ({ kind: e.kind, id: e.id,
        ack: evidence.acknowledgements.find(a => a.id === e.id),
        live: evidence.live.includes(e.id), history: evidence.history.includes(e.id) })),
      note: 'ACK means relay storage, not verified repository ingestion; unsupported kinds are reported, not asserted' };
  } finally { relay.close(); }
}

async function verifyCar(bytes: Uint8Array, s: State, fixtures: Fixture[], mode: 'snapshot' | 'proof' | 'sync' = 'snapshot') {
  const car = await readCar(bytes);
  const expected = fixtures.at(-1)!;
  assert.equal(car.roots.length, 1);
  assert.equal(car.roots[0].toString(), expected.commitCid, 'CAR root');
  const storage = new MemoryBlockstore();
  let blockCount = 0;
  for (const { cid, bytes: block } of car.blocks.entries()) {
    assert.equal(cid.version, 1);
    assert.equal(cid.code, cbor.code);
    assert.equal(cid.toString(), (await cidFor(block)).toString(), 'CAR block CID hash');
    await storage.putBlock(cid, block);
    blockCount++;
  }
  const commitBytes = car.blocks.get(CID.parse(expected.commitCid));
  assert.ok(commitBytes, 'Original commit block missing');
  assert.deepEqual(Buffer.from(commitBytes), Buffer.from(unb64(expected.commitBytes)), 'Original signed commit bytes');
  const commit = cbor.decode(commitBytes) as { sig: Uint8Array; did: string; rev: string; data: CID; version: number; prev: CID | null };
  const { sig, ...unsigned } = commit;
  assert.equal(commit.did, s.did);
  assert.equal(commit.version, 3);
  assert.equal(commit.rev, expected.rev);
  assert.equal(commit.prev, null);
  assert.ok(await verifySignature(s.repoKey, cbor.encode(unsigned), sig), 'Independent original commit signature');
  // Rebuild the known signed chain independently; a partial CAR must still bind to its exact MST root.
  let expectedTree = await MST.create(new MemoryBlockstore());
  for (const f of fixtures) expectedTree = await expectedTree.add(f.path, CID.parse(f.recordCid));
  assert.equal(commit.data.toString(), (await expectedTree.getPointer()).toString(), 'Expected MST root');
  if (mode === 'sync') {
    assert.ok(bytes.length <= 10_000, 'Sync CAR exceeds 10000 bytes');
    assert.equal(blockCount, 1, 'Commit-only sync CAR');
    return { mode, root: expected.commitCid, blockCount, records: 0,
      allCidHashes: true, originalCommitBytes: true, commitSignature: true, expectedMstRoot: true };
  }
  const tree = MST.load(storage, commit.data);
  if (mode === 'snapshot') assert.equal((await tree.list()).length, fixtures.length, 'MST record count');
  const records = mode === 'snapshot' ? fixtures : [expected];
  if (mode === 'proof') {
    // Require both neighbor paths, not merely an inclusion path for the changed record.
    for (const { cid, bytes: block } of (await expectedTree.getCoveringProof(expected.path)).entries()) {
      const received = car.blocks.get(cid);
      assert.ok(received, 'Covering proof node missing');
      assert.deepEqual(Buffer.from(received), Buffer.from(block), 'Covering proof node changed');
    }
  }
  for (const f of records) {
    assert.equal((await tree.get(f.path))?.toString(), f.recordCid, 'MST leaf');
    const record = car.blocks.get(CID.parse(f.recordCid));
    assert.ok(record, 'Original record missing');
    assert.deepEqual(Buffer.from(record), Buffer.from(unb64(f.recordBytes)), 'Original record bytes');
  }
  return { mode, root: expected.commitCid, blockCount, records: records.length,
    allCidHashes: true, originalCommitBytes: true, commitSignature: true, officialMst: true, originalRecordBytes: true };
}
function watchFirehose(s: State, cursor: number | null = 0) {
  const socket = new WebSocket(`${relayUrl}/xrpc/com.atproto.sync.subscribeRepos${cursor === null ? '' : `?cursor=${cursor}`}`);
  sockets.add(socket);
  socket.binaryType = 'arraybuffer';
  const frames: { header: Json; body: Json }[] = [];
  let frameError: string | undefined;
  socket.addEventListener('message', message => {
    try {
      assert.ok(message.data instanceof ArrayBuffer, 'Binary firehose frame required');
      const bytes = new Uint8Array(message.data);
      const [header, rest] = decodeFirst(bytes);
      const body = cbor.decode(rest) as Json;
      assert.deepEqual(Buffer.from(bytes.subarray(0, bytes.length - rest.length)), Buffer.from(cbor.encode(header)));
      if (body.repo === s.did || body.did === s.did) frames.push({ header: header as Json, body });
    } catch (error) { frameError = error instanceof Error ? error.message : 'Firehose decoding error'; }
  });
  socket.addEventListener('error', () => { frameError = 'Firehose WebSocket error'; });
  return { frames, error: () => frameError, isOpen: () => socket.readyState === WebSocket.OPEN,
    close: () => { socket.close(); sockets.delete(socket); } };
}
async function verifyRepoFrame(frame: { header: Json; body: Json }, s: State, fixtures: Fixture[]) {
  const f = fixtures.at(-1)!;
  const prior = fixtures.at(-2);
  const { header, body } = frame;
  assert.equal(header.op, 1);
  assert.equal(body.rev, f.rev);
  assert.ok(body.blocks instanceof Uint8Array);
  if (header.t === '#sync') {
    assert.equal(body.did, s.did);
    return verifyCar(body.blocks, s, fixtures, 'sync');
  }
  assert.equal(header.t, '#commit');
  assert.equal(body.repo, s.did);
  assert.equal(String(body.commit), f.commitCid);
  assert.equal(body.since, prior?.rev ?? null);
  assert.equal(body.rebase, false);
  assert.equal(body.tooBig, false);
  assert.deepEqual(body.blobs, []);
  assert.deepEqual(body.ops, [{ action: 'create', path: f.path, cid: CID.parse(f.recordCid) }]);
  if (prior) {
    const commit = cbor.decode(unb64(prior.commitBytes)) as { data: CID };
    assert.equal(String(body.prevData), commit.data.toString());
  } else assert.equal(body.prevData, undefined);
  return verifyCar(body.blocks, s, fixtures, 'proof');
}
const matchesFixture = (frame: { header: Json; body: Json }, f: Fixture) =>
  frame.header.t === '#commit' && String(frame.body.commit) === f.commitCid
  || frame.header.t === '#sync' && frame.body.rev === f.rev;
async function firehose(s: State, fixtures: Fixture[], cursor: number | null = 0) {
  const watch = watchFirehose(s, cursor);
  const { frames } = watch;
  try {
    const until = Math.min(deadline, Date.now() + 10_000);
    while (!watch.error() && Date.now() < until && !fixtures.every(f => frames.some(v =>
      matchesFixture(v, f)))) await sleep(50);
    assert.ok(!watch.error(), watch.error());
    const identity = frames.find(f => f.header.t === '#identity');
    const account = frames.find(f => f.header.t === '#account');
    assert.ok(identity && account, 'Firehose identity/account replay missing');
    assert.equal(account.body.active, true);
    let lastSeq = -1;
    for (const { header, body } of frames) {
      assert.equal(header.op, 1);
      assert.ok(Number.isSafeInteger(body.seq) && Number(body.seq) > lastSeq, 'Increasing firehose sequence');
      assert.ok(typeof body.time === 'string' && Number.isFinite(Date.parse(body.time)), 'Firehose UTC time');
      lastSeq = Number(body.seq);
    }
    const commits: Json[] = [];
    let previousCommitSeq = Number(account.body.seq);
    assert.ok(Number(identity.body.seq) < previousCommitSeq, 'Identity before account');
    for (let i = 0; i < fixtures.length; i++) {
      const f = fixtures[i];
      const frame = frames.find(v => matchesFixture(v, f));
      assert.ok(frame, `Firehose replay missing commit ${f.commitCid}`);
      const { body } = frame;
      assert.ok(Number(body.seq) > previousCommitSeq, 'Firehose commits must follow chain order');
      previousCommitSeq = Number(body.seq);
      const car = await verifyRepoFrame(frame, s, fixtures.slice(0, i + 1));
      commits.push({ seq: body.seq, header: frame.header, commitCid: f.commitCid, car });
    }
    return { identity: { header: identity.header, seq: identity.body.seq },
      account: { header: account.header, seq: account.body.seq }, commits };
  } finally { watch.close(); }
}
async function pipeline(s: State, fixtures = fixtureChain(s)) {
  const expected = fixtures.at(-1)!;
  let last: Json = {};
  let carEvidence: Awaited<ReturnType<typeof verifyCar>> | undefined;
  // Reserve ten seconds for replay. Every individual HTTP operation also has a ten-second cap.
  const until = deadline - 11_000;
  while (Date.now() < until) {
    try {
      const latest = await json(`${production}/xrpc/com.atproto.sync.getLatestCommit?did=${s.did}`);
      last = { latest };
      if (latest.cid === expected.commitCid) {
        assert.equal(latest.rev, expected.rev);
        const response = await request(`${production}/xrpc/com.atproto.sync.getRepo?did=${s.did}`);
        assert.ok(response.ok, `getRepo HTTP ${response.status}`);
        carEvidence = await verifyCar(new Uint8Array(await response.arrayBuffer()), s, fixtures);
        break;
      }
    } catch (error) { last = { error: error instanceof Error ? error.message : 'Pipeline request failed' }; }
    await sleep(Math.min(1000, Math.max(1, until - Date.now())));
  }
  if (!carEvidence) return { ok: false, identity: publicIdentity(s), expectedHead: expected.commitCid,
    timeout: true, last, gap: 'Repository did not reach the expected verified head within the bounded poll' };
  try {
    return { ok: true, identity: publicIdentity(s), car: carEvidence, firehose: await firehose(s, fixtures) };
  } catch (error) {
    return { ok: false, identity: publicIdentity(s), car: carEvidence,
      gap: error instanceof Error ? error.message : 'Firehose verification failed' };
  }
}
async function reorder(s: State) {
  const evidence: Json = {};
  let relay: Relay | undefined;
  let watch: ReturnType<typeof watchFirehose> | undefined;
  try {
    if (!s.reorder) {
      const previous = fixtureChain(s, true);
      const head = await json(`${production}/xrpc/com.atproto.sync.getLatestCommit?did=${s.did}`);
      assert.equal(head.cid, previous.at(-1)!.commitCid, 'Verify the existing planned chain before reorder');
      const fixtures: Fixture[] = [];
      for (let i = 0; i < 3; i++) fixtures.push(await fixture(s, [...previous, ...fixtures]));
      s.reorder = { fixtures };
      await save(s); // Exact three fixtures are durable before publishing any descendant.
      await validateState(s);
    }
    const group = s.reorder;
    const [parent, child, grandchild] = group.fixtures;
    const all = fixtureChain(s, true);
    const baseline = all[all.findIndex(f => f.commitCid === parent.commitCid) - 1];
    assert.ok(baseline, 'Reorder baseline missing');
    const acknowledgements: Ack[] = [];
    evidence.publicationOrder = [grandchild, child, parent].map(f => ({ eventId: f.event.id, commitCid: f.commitCid }));
    evidence.acknowledgements = acknowledgements;
    evidence.gateReused = Boolean(group.parentAttemptedAt);
    relay = new Relay(relayUrl);
    await relay.connect();
    const publish = async (fixtures: Fixture[]) => {
      for (const f of fixtures) relay!.send(['EVENT', f.event]);
      await relay!.wait(() => fixtures.every(f => relay!.frames.some(v => v[0] === 'OK' && v[1] === f.event.id)));
      for (const f of fixtures) {
        const frame = relay!.frames.find(v => v[0] === 'OK' && v[1] === f.event.id);
        const ack = { id: f.event.id, accepted: frame ? frame[2] === true : null,
          message: frame ? String(frame[3]).slice(0, 300) : 'ACK timeout' };
        acknowledgements.push(ack);
        assert.equal(ack.accepted, true, `Reorder relay rejected ${f.event.id}: ${ack.message}`);
      }
    };
    if (!group.parentAttemptedAt) {
      const head = await json(`${production}/xrpc/com.atproto.sync.getLatestCommit?did=${s.did}`);
      assert.equal(head.cid, baseline.commitCid, 'Head must start at the pre-reorder baseline');
      watch = watchFirehose(s);
      const readyUntil = Math.min(deadline, Date.now() + 10_000);
      while (!watch.error() && Date.now() < readyUntil && !watch.frames.some(f =>
        matchesFixture(f, baseline))) await sleep(50);
      assert.ok(!watch.error(), watch.error());
      assert.ok(watch.isOpen() && watch.frames.some(f => matchesFixture(f, baseline)),
        'Firehose must replay the baseline before the negative observation');
      group.attemptedAt ??= new Date().toISOString();
      await save(s);
      await publish([grandchild, child]);
      relay.send(['REQ', 'reorder-receipt', { ids: group.fixtures.map(f => f.event.id) }]);
      assert.ok(await relay.wait(() => relay!.frames.some(f => f[0] === 'EOSE' && f[1] === 'reorder-receipt')),
        'Raw receipt history EOSE missing');
      const receipts = relay.frames.filter(f => f[0] === 'EVENT' && f[1] === 'reorder-receipt').map(f => f[2] as Event);
      evidence.rawReceiptIds = receipts.map(f => f.id);
      for (const f of [child, grandchild]) {
        const received = receipts.find(v => v.id === f.event.id);
        assert.deepEqual(received, JSON.parse(JSON.stringify(f.event)), 'Exact raw descendant receipt');
      }
      assert.ok(!receipts.some(f => f.id === parent.event.id), 'Withheld parent must not exist in raw history');
      const startedAt = new Date().toISOString();
      const start = performance.now();
      const samples: { time: string; cid: unknown; rev: unknown }[] = [];
      const observe = async () => {
        const latest = await json(`${production}/xrpc/com.atproto.sync.getLatestCommit?did=${s.did}`);
        samples.push({ time: new Date().toISOString(), cid: latest.cid, rev: latest.rev });
        assert.equal(latest.cid, baseline.commitCid, 'Head advanced before withheld parent');
        assert.equal(latest.rev, baseline.rev);
        assert.ok(watch!.isOpen() && !watch!.error(), watch!.error() ?? 'Firehose disconnected during withholding');
        assert.ok(!watch!.frames.some(f => group.fixtures.some(v => matchesFixture(f, v))), 'Premature reorder firehose commit');
      };
      do { await observe(); await sleep(250); } while (performance.now() - start < 3200);
      await observe(); // A final head sample after at least three full seconds.
      const gate = { startedAt, finishedAt: new Date().toISOString(), withheldMs: Math.floor(performance.now() - start),
        baselineHead: baseline.commitCid, headSamples: samples, headUnchanged: true,
        rawReceiptIds: receipts.map(f => f.id), parentAbsentFromRawHistory: true,
        firehoseBaselineObserved: true, prematureCommitCount: 0, noPrematureFirehoseCommit: true };
      assert.ok(gate.withheldMs >= 3000);
      group.gate = gate;
      await save(s);
    }
    assert.ok(group.gate, 'Cannot release parent without durable withholding evidence');
    evidence.gate = group.gate;
    group.parentAttemptedAt ??= new Date().toISOString();
    await save(s); // A lost parent ACK cannot cause a retry to claim a new negative observation.
    if (watch) {
      assert.ok(watch.isOpen() && !watch.error(), watch.error() ?? 'Firehose disconnected before parent release');
      assert.ok(!watch.frames.some(f => group.fixtures.some(v => matchesFixture(f, v))), 'Premature commit before parent release');
    }
    await publish([parent]);
    watch?.close();
    watch = undefined;
    relay.close();
    relay = undefined;
    const { identity: _identity, ...verification } = await pipeline(s);
    return { ok: verification.ok, identity: publicIdentity(s), evidence, verification };
  } catch (error) {
    return { ok: false, identity: publicIdentity(s), evidence,
      gap: error instanceof Error ? error.message : 'Reorder verification failed' };
  } finally { watch?.close(); relay?.close(); }
}
async function upstream(s: State, live = false) {
  // Optional URL is persisted; retries must use the same one event and the same source.
  let entry = live ? s.upstreamLive : s.upstream;
  const source = process.argv[3] ?? entry?.relay ?? 'wss://relay.damus.io';
  const parsed = new URL(source);
  assert.equal(parsed.protocol, 'wss:');
  assert.notEqual(parsed.origin, new URL(relayUrl).origin, 'Upstream must not be production');
  if (!entry) {
    const head = await json(`${production}/xrpc/com.atproto.sync.getLatestCommit?did=${s.did}`);
    const previous = fixtureChain(s, true);
    assert.equal(head.cid, previous.at(-1)!.commitCid, 'Run pipeline successfully before upstream');
    entry = { relay: source, fixture: await fixture(s, previous) };
    if (live) s.upstreamLive = entry; else s.upstream = entry;
    await save(s); // Save the exact extra event before any source relay side effect.
    await validateState(s);
  }
  assert.equal(entry.relay, source, 'Upstream source is already fixed in recovery state');
  // A retry may report durable evidence, but must never relabel replay as a new live observation.
  if (live && entry.evidence?.ok === true) return { ...entry.evidence, ok: true, reusedEvidence: true };
  const event = entry.fixture.event;
  const sourceRelay = new Relay(source);
  const destination = new Relay(relayUrl);
  let ack: Ack | undefined;
  let sourceHistory = false;
  let received = false;
  try {
    await Promise.all([sourceRelay.connect(), destination.connect()]);
    const subscription = live ? 'upstream-live-destination' : 'upstream-destination';
    destination.send(['REQ', subscription, { ids: [event.id] }]);
    const eose = await destination.wait(() => destination.frames.some(f => f[0] === 'EOSE' && f[1] === subscription), live ? 10_000 : 1000);
    const eoseIndex = destination.frames.findIndex(f => f[0] === 'EOSE' && f[1] === subscription);
    const eoseAt = new Date().toISOString();
    if (live) {
      assert.ok(eose, 'Production history EOSE required before source publication');
      assert.equal(destination.events(subscription).includes(event.id), false,
        'Event already in production; cannot prove fresh live collection or regenerate on retry');
    }
    entry.attemptedAt ??= new Date().toISOString();
    await save(s);
    const publicationIndex = destination.frames.length;
    if (live) {
      assert.equal(destination.socket.readyState, WebSocket.OPEN);
      assert.equal(destination.events(subscription).includes(event.id), false, 'Event arrived before source publish');
    }
    const publishedAt = new Date().toISOString();
    // Deliberately NEVER publish this event to production.
    sourceRelay.send(['EVENT', event]);
    await sourceRelay.wait(() => sourceRelay.frames.some(f => f[0] === 'OK' && f[1] === event.id));
    const frame = sourceRelay.frames.find(f => f[0] === 'OK' && f[1] === event.id);
    ack = { id: event.id, accepted: frame ? frame[2] === true : null, message: frame ? String(frame[3]).slice(0, 300) : 'ACK timeout' };
    entry.accepted = ack.accepted === true;
    await save(s);
    sourceRelay.send(['REQ', 'source-history', { ids: [event.id] }]);
    await sourceRelay.wait(() => sourceRelay.frames.some(f => f[0] === 'EOSE' && f[1] === 'source-history'));
    sourceHistory = sourceRelay.events('source-history').includes(event.id);
    const receivedEvent = () => destination.frames.slice(live ? Math.max(eoseIndex + 1, publicationIndex) : 0)
      .find(f => f[0] === 'EVENT' && f[1] === subscription && (f[2] as Event).id === event.id);
    await destination.wait(() => Boolean(receivedEvent()), Math.min(30_000, remaining() + 20_000));
    received = Boolean(receivedEvent());
    const receivedAt = received ? new Date().toISOString() : null;
    if (live && received) assert.deepEqual(receivedEvent()![2], JSON.parse(JSON.stringify(event)), 'Exact signed live event');
    // A second history subscription distinguishes durable receipt from mere connection activity.
    destination.send(['REQ', 'destination-history', { ids: [event.id] }]);
    await destination.wait(() => destination.frames.some(f => f[0] === 'EOSE' && f[1] === 'destination-history'));
    const productionHistory = destination.events('destination-history').includes(event.id);
    const result = { ok: ack.accepted === true && sourceHistory && received && productionHistory,
      ...(live ? { did: s.did, eventId: event.id, commitCid: entry.fixture.commitCid, base: entry.fixture.base,
        mode: 'fresh-live-after-eose', absentAtEose: true, eoseAt, publishedAt, receivedAt,
        sameProductionSubscription: true, receivedAfterEoseAndSourcePublish: received, reusedEvidence: false }
        : { identity: publicIdentity(s) }),
      source, ack, sourceHistory, productionReceived: received,
      productionHistory, directlyPublishedToProduction: false,
      next: 'Run pipeline to independently verify the extra commit and replay' };
    if (live) { entry.evidence = result; await save(s); }
    return result;
  } finally { sourceRelay.close(); destination.close(); }
}
async function compatibilityLive(s: State) {
  let entry = s.compatibilityLive;
  if (entry?.evidence?.ok === true) return { ...entry.evidence, ok: true, reusedEvidence: true };
  if (!entry) {
    const previous = fixtureChain(s, true);
    const head = await json(`${production}/xrpc/com.atproto.sync.getLatestCommit?did=${s.did}`);
    assert.equal(head.cid, previous.at(-1)!.commitCid, 'Existing head must match the complete saved chain');
    entry = { fixture: await fixture(s, previous, 'COMPATIBILITY-LIVE test create') };
    s.compatibilityLive = entry;
    await validateState(s);
    await save(s); // Signed event and all recovery keys are durable before publication.
  }
  const all = fixtureChain(s, true);
  const index = all.findIndex(f => f.commitCid === entry.fixture.commitCid);
  assert.equal(index, all.length - 1, 'Finish compatibility-live before running another optional stage');
  const previous = all.slice(0, index);
  const head = await json(`${production}/xrpc/com.atproto.sync.getLatestCommit?did=${s.did}`);
  const recovered = head.cid === entry.fixture.commitCid;
  const retried = Boolean(entry.attemptedAt);
  assert.ok(head.cid === previous.at(-1)!.commitCid || (entry.attemptedAt && recovered),
    'Unexpected head; refusing to publish a fork');
  const bootstrap = await firehose(s, previous, null);
  const cursor = Number(bootstrap.commits.at(-1)!.seq);
  const watch = watchFirehose(s, cursor);
  const relay = new Relay(relayUrl);
  try {
    const until = Math.min(deadline, Date.now() + 10_000);
    while (!watch.isOpen() && !watch.error() && Date.now() < until) await sleep(50);
    assert.ok(watch.isOpen() && !watch.error(), watch.error() ?? 'Reconnect did not open');
    let ack: Ack | undefined;
    if (!recovered) {
      await relay.connect();
      entry.attemptedAt ??= new Date().toISOString();
      await save(s); // Persist the attempt before EVENT, including retries after an unknown ACK.
      relay.send(['EVENT', entry.fixture.event]);
      assert.ok(await relay.wait(() => relay.frames.some(f => f[0] === 'OK' && f[1] === entry!.fixture.event.id)),
        'Compatibility create ACK timeout');
      const frame = relay.frames.find(f => f[0] === 'OK' && f[1] === entry!.fixture.event.id)!;
      ack = { id: entry.fixture.event.id, accepted: frame[2] === true, message: String(frame[3]).slice(0, 300) };
      assert.equal(ack.accepted, true, 'Compatibility create rejected');
    }
    const untilCommit = Math.min(deadline, Date.now() + 10_000);
    while (!watch.error() && Date.now() < untilCommit && !watch.frames.some(f => matchesFixture(f, entry!.fixture))) await sleep(50);
    assert.ok(!watch.error(), watch.error());
    const frame = watch.frames.find(f => matchesFixture(f, entry!.fixture));
    assert.ok(frame, 'Reconnect missing compatibility create');
    let seq = cursor;
    for (const f of watch.frames) {
      assert.equal(f.header.op, 1);
      assert.ok(Number.isSafeInteger(f.body.seq) && Number(f.body.seq) > seq, 'Reconnect sequence strictly after cursor');
      assert.ok(typeof f.body.time === 'string' && Number.isFinite(Date.parse(f.body.time)));
      seq = Number(f.body.seq);
    }
    assert.equal(frame.header.t, '#commit', 'Small create must use compact #commit, not #sync');
    const proof = await verifyRepoFrame(frame, s, all);
    const car = await readCar(frame.body.blocks as Uint8Array);
    for (const f of previous) assert.equal(car.blocks.get(CID.parse(f.recordCid)), undefined, 'Compact CAR must omit unchanged records');
    entry.attemptedAt ??= new Date().toISOString();
    await save(s);
    const verification = await pipeline(s);
    assert.equal(verification.ok, true, 'Full getRepo and replay validation must pass');
    const result = { ok: true, identity: publicIdentity(s), reusedEvidence: false,
      mode: recovered || retried ? 'recovered-attempt-not-fresh-live' : 'fresh-create-with-cursor-reconnect',
      freshLive: !recovered && !retried, cursorlessBootstrap: bootstrap, reconnectCursor: cursor,
      commitSeq: frame.body.seq, proof, unchangedRecordsOmitted: true, ack, verification };
    entry.evidence = result;
    await save(s);
    return result;
  } finally { watch.close(); relay.close(); }
}
async function selftest(s: State) {
  // Ephemeral identity only: exercise every optional stage without opening recovery state or the network.
  s.upstream = { relay: 'wss://relay.damus.io', fixture: await fixture(s, fixtureChain(s, true)), attemptedAt: 'selftest' };
  s.reorder = { fixtures: [], attemptedAt: 'selftest' };
  for (let i = 0; i < 3; i++) s.reorder.fixtures.push(await fixture(s, fixtureChain(s, true)));
  s.upstreamLive = { relay: 'wss://relay.damus.io', fixture: await fixture(s, fixtureChain(s, true)), attemptedAt: 'selftest' };
  s.compatibilityLive = { fixture: await fixture(s, fixtureChain(s, true)), attemptedAt: 'selftest' };
  await validateState(s);
  const fixtures = fixtureChain(s, true);
  if (fixtures.length >= 7) {
    // The same seven signed fixtures model both optional-stage creation orders without publishing.
    for (const upstreamFirst of [true, false]) {
      const alternate: State = { ...s, fixtures: fixtures.slice(0, 3),
        upstream: { relay: 'wss://relay.damus.io', fixture: fixtures[upstreamFirst ? 3 : 6], attemptedAt: 'selftest' },
        reorder: { fixtures: upstreamFirst ? fixtures.slice(4, 7) : fixtures.slice(3, 6), attemptedAt: 'selftest' } };
      await validateState(alternate);
      assert.deepEqual(fixtureChain(alternate).map(f => f.commitCid), fixtures.map(f => f.commitCid));
      alternate.compatibilityLive = { fixture: s.compatibilityLive.fixture };
      assert.equal(fixtureChain(alternate).length, fixtures.length - 1);
      assert.equal(fixtureChain(alternate, true).length, fixtures.length);
      // Compatibility can precede other optional stages too; revisions, not fields, order the chain.
      alternate.compatibilityLive = { fixture: fixtures[3], attemptedAt: 'selftest' };
      alternate.upstream = { relay: 'wss://relay.damus.io', fixture: fixtures[8], attemptedAt: 'selftest' };
      alternate.reorder = { fixtures: fixtures.slice(4, 7), attemptedAt: 'selftest' };
      await validateState(alternate);
      assert.deepEqual(fixtureChain(alternate).map(f => f.commitCid), fixtures.map(f => f.commitCid));
    }
    const broken = { ...s, fixtures: [s.fixtures[1], s.fixtures[0], s.fixtures[2]] };
    // Sorting must not hide a fork or a missing signed predecessor.
    const fork = { ...fixtures[3], base: fixtures[0].commitCid };
    broken.reorder = { fixtures: [fork, fixtures[4], fixtures[5]], attemptedAt: 'selftest' };
    broken.upstream = { relay: 'wss://relay.damus.io', fixture: fixtures[6], attemptedAt: 'selftest' };
    await assert.rejects(validateState(broken));
  }
  const storage = new MemoryBlockstore();
  let tree = await MST.create(storage);
  for (const f of fixtures) tree = await tree.add(f.path, CID.parse(f.recordCid));
  const { blocks } = await tree.getUnstoredBlocks();
  const head = fixtures.at(-1)!;
  blocks.set(CID.parse(head.commitCid), unb64(head.commitBytes));
  for (const f of fixtures) blocks.set(CID.parse(f.recordCid), unb64(f.recordBytes));
  const car = await blocksToCarFile(CID.parse(head.commitCid), blocks);
  const evidence = await verifyCar(car, s, fixtures);
  const bad = { ...head, recordBytes: b64(cbor.encode({ tampered: true })) };
  await assert.rejects(verifyCar(car, s, [...fixtures.slice(0, -1), bad]));
  const proof = await tree.getCoveringProof(head.path);
  proof.set(CID.parse(head.commitCid), unb64(head.commitBytes));
  proof.set(CID.parse(head.recordCid), unb64(head.recordBytes));
  const compactCar = await blocksToCarFile(CID.parse(head.commitCid), proof);
  const prior = cbor.decode(unb64(fixtures.at(-2)!.commitBytes)) as { data: CID };
  const frame = { header: { op: 1, t: '#commit' }, body: { repo: s.did, commit: CID.parse(head.commitCid),
    rev: head.rev, since: fixtures.at(-2)!.rev, prevData: prior.data, rebase: false, tooBig: false,
    blobs: [], ops: [{ action: 'create', path: head.path, cid: CID.parse(head.recordCid) }], blocks: compactCar } };
  const compactEvidence = await verifyRepoFrame(frame, s, fixtures);
  assert.ok(compactCar.length < car.length);
  await assert.rejects(verifyCar(compactCar, s, fixtures)); // Compact proof is not a full getRepo snapshot.
  await assert.rejects(verifyRepoFrame({ ...frame, body: { ...frame.body, ops: [] } }, s, fixtures));
  await assert.rejects(verifyRepoFrame({ ...frame, body: { ...frame.body, since: null } }, s, fixtures));
  await assert.rejects(verifyRepoFrame({ ...frame, body: { ...frame.body, prevData: CID.parse(head.commitCid) } }, s, fixtures));
  await assert.rejects(verifyCar(compactCar, s, [...fixtures.slice(0, -1), bad], 'proof'));
  const commitOnly = new MemoryBlockstore();
  await commitOnly.putBlock(CID.parse(head.commitCid), unb64(head.commitBytes));
  const syncCar = await blocksToCarFile(CID.parse(head.commitCid), commitOnly.blocks);
  const syncFrame = { header: { op: 1, t: '#sync' }, body: { did: s.did, rev: head.rev, blocks: syncCar } };
  const syncEvidence = await verifyRepoFrame(syncFrame, s, fixtures);
  assert.ok(matchesFixture(frame, head) && matchesFixture(syncFrame, head));
  assert.equal(matchesFixture(syncFrame, fixtures[0]), false);
  await assert.rejects(verifyRepoFrame({ ...syncFrame, body: { ...syncFrame.body, rev: fixtures[0].rev } }, s, fixtures));
  const missingRecord = new BlockMap().addMap(proof);
  missingRecord.delete(CID.parse(head.recordCid));
  await assert.rejects(verifyCar(await blocksToCarFile(CID.parse(head.commitCid), missingRecord), s, fixtures, 'proof'));
  const corrupt = new BlockMap().addMap(proof);
  corrupt.set(CID.parse(head.recordCid), cbor.encode({ tampered: true }));
  await assert.rejects(verifyCar(await blocksToCarFile(CID.parse(head.commitCid), corrupt), s, fixtures, 'proof'));
  await assert.rejects(verifyRepoFrame({ ...frame, body: { ...frame.body, blocks: syncCar } }, s, fixtures));
  await assert.rejects(verifyRepoFrame({ ...syncFrame, body: { ...syncFrame.body, blocks: compactCar } }, s, fixtures));
  await assert.rejects(verifyRepoFrame({ ...syncFrame, body: { ...syncFrame.body, did: 'did:plc:wrong' } }, s, fixtures));
  for (const { cid } of (await tree.getCoveringProof(head.path)).entries()) {
    const incomplete = new BlockMap().addMap(proof);
    incomplete.delete(cid);
    await assert.rejects(verifyCar(await blocksToCarFile(CID.parse(head.commitCid), incomplete), s, fixtures, 'proof'));
  }
  assert.equal(verifyEvent(JSON.parse(JSON.stringify(s.malformed))), false);
  return { ok: true, identity: publicIdentity(s), evidence, compactEvidence, syncEvidence, tamperedRecordRejected: true,
    malformedSignatureRejectedLocally: true, optionalStageOrderingTested: fixtures.length >= 7, networkRequests: 0 };
}

const watchdog = setTimeout(() => {
  const completed = emitted;
  if (!emitted) emit({ ok: false, timeout: true, gap: 'Hard 60-second stage deadline',
    ...(state ? { identity: publicIdentity(state) } : {}) });
  process.exit(completed ? (process.exitCode ? 1 : 0) : 1);
}, 59_500);
process.on('exit', () => { if (locked) { try { unlinkSync(lockPath); } catch { /* Already cleaned. */ } } });
try {
  assert.ok(['prepare', 'relay', 'pipeline', 'reorder', 'upstream', 'upstream-live', 'compatibility-live', 'selftest'].includes(stage),
    'Usage: node verify-production.mts prepare|relay|pipeline|reorder|upstream|upstream-live [wss://source]|compatibility-live|selftest');
  if (stage === 'selftest') state = await createState();
  else {
    await mkdir(directory, { recursive: true, mode: 0o700 });
    // Exclusive lock avoids concurrent invocations generating or publishing different recovery state.
    const lock = await open(lockPath, 'wx', 0o600);
    locked = true;
    await lock.close();
    try { state = JSON.parse(await readFile(statePath, 'utf8')) as State; }
    catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
      assert.equal(stage, 'prepare', 'Run prepare first; no existing recovery state');
      state = await createState();
      await save(state);
    }
  }
  await validateState(state);
  if (stage !== 'prepare' && stage !== 'selftest') assert.ok(state.registered, 'Run prepare to verify PLC registration first');
  const result = stage === 'prepare' ? await prepare(state)
    : stage === 'relay' ? await relayProbe(state)
    : stage === 'pipeline' ? await pipeline(state)
    : stage === 'reorder' ? await reorder(state)
    : stage === 'upstream' ? await upstream(state)
    : stage === 'upstream-live' ? await upstream(state, true)
    : stage === 'compatibility-live' ? await compatibilityLive(state) : await selftest(state);
  emit(result);
  if (!result.ok) process.exitCode = 1;
} catch (error) {
  emit({ ok: false, error: error instanceof Error ? error.message : 'Verification failed',
    ...(state ? { identity: publicIdentity(state) } : {}) });
  process.exitCode = 1;
} finally {
  for (const socket of sockets) socket.close();
  // Unref lets successful shutdown exit immediately, while still bounding a stuck close handshake.
  watchdog.unref();
  if (locked) { await unlink(lockPath); locked = false; }
}
