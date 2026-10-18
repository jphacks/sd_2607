#!/usr/bin/env node
// Read-only: Nostr REQ/CLOSE and fixed-origin HTTP GET only. Node >=24.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { writeFileSync } from 'node:fs';
import { parseArgs } from 'node:util';
import { resolve } from 'node:path';
import { WebSocket } from 'ws';
import { verifyEvent } from 'nostr-tools';
import { MST, MemoryBlockstore, readCar, blocksToCarFile } from '@atproto/repo';
import * as cbor from '@ipld/dag-cbor';
import { CID } from 'multiformats/cid';
import { sha256 } from 'multiformats/hashes/sha2';
import { decodePost, verifyCommit, reconstruct } from './repository.mjs';

const pds = 'https://pds.oriom.dev';
const requiredRelays = ['wss://pds.oriom.dev', 'wss://nos.lol'];
const hex = /^[0-9a-f]{64}$/;
const cidFor = async bytes => CID.createV1(cbor.code, await sha256.digest(bytes));
const bytesEqual = (a, b, message) => {
  assert.ok(a && b, message);
  assert.deepEqual(Buffer.from(a), Buffer.from(b), message);
};
function lexJson(value) {
  if (CID.asCID(value)) return { $link: value.toString() };
  if (value instanceof Uint8Array) return { $bytes: Buffer.from(value).toString('base64').replace(/=+$/, '') };
  if (Array.isArray(value)) return value.map(lexJson);
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, child]) => [key, lexJson(child)]));
  }
  return value;
}
function publicInput(input) {
  assert.ok(input && typeof input === 'object', 'Expected public JSON object');
  assert.ok(typeof input.run === 'string' && input.run.length > 0, 'Missing run');
  assert.match(input.did, /^did:plc:[a-z2-7]{24}$/);
  assert.match(input.pubkey, hex);
  assert.ok(Array.isArray(input.events) && input.events.length === 2, 'Exactly two events required');
  const events = input.events.map(({ id, content }) => {
    assert.match(id, hex);
    assert.equal(typeof content, 'string');
    return { id, content };
  });
  assert.notEqual(events[0].id, events[1].id, 'Duplicate event IDs');
  assert.ok(Array.isArray(input.relayOk), 'relayOk must be an array');
  const relayOk = input.relayOk.map(({ relay, eventId }) => {
    assert.equal(typeof relay, 'string');
    assert.ok(events.some(event => event.id === eventId), 'Receipt has unknown event ID');
    return { relay, eventId };
  });
  // Only project known public fields; never echo arbitrary input properties.
  return { run: input.run, did: input.did, pubkey: input.pubkey, events, relayOk };
}
function checkedEvent(event, expected, pubkey) {
  assert.ok(event && typeof event === 'object', 'Missing event');
  const { id, content, kind, created_at, tags, sig } = event;
  const clean = { id, pubkey: event.pubkey, content, kind, created_at, tags, sig };
  assert.equal(id, expected.id, 'Event ID mismatch');
  assert.equal(content, expected.content, 'Event content mismatch');
  assert.equal(clean.pubkey, pubkey, 'Event author mismatch');
  assert.equal(kind, 1, 'Expected kind 1');
  assert.ok(verifyEvent(clean), 'Invalid Nostr ID/signature');
  return clean;
}
async function carStorage(bytes) {
  const car = await readCar(bytes);
  const storage = new MemoryBlockstore();
  let blockCount = 0;
  for (const { cid, bytes: block } of car.blocks.entries()) {
    assert.equal(cid.toString(), (await cidFor(block)).toString(), 'CAR block CID hash');
    await storage.putBlock(cid, block);
    blockCount++;
  }
  return { car, storage, blockCount };
}

async function main() {
  const { values, positionals } = parseArgs({ options: {
    output: { type: 'string' }, damus: { type: 'boolean' },
    selftest: { type: 'boolean' }, help: { type: 'boolean' },
  }, allowPositionals: true });
  if (values.help) {
    console.log('node pds/verify-android-production.mjs <verification-public.json> [--damus] [--output <new-evidence.json>]\nnode pds/verify-android-production.mjs --selftest\nRead-only; 90s total, HTTP 10s, WS 15s. Output is public JSON; existing output files are not overwritten.');
    return;
  }
  if (values.selftest) {
    assert.equal(positionals.length, 0);
    assert.ok(!values.output && !values.damus);
    const input = { run: 'local-selftest', did: `did:plc:${'a'.repeat(24)}`, pubkey: '1'.repeat(64),
      events: [{ id: '2'.repeat(64), content: 'one' }, { id: '3'.repeat(64), content: 'two' }], relayOk: [],
      ignoredPrivateField: 'must not be copied' };
    assert.equal(Object.hasOwn(publicInput(input), 'ignoredPrivateField'), false);
    assert.throws(() => publicInput({ ...input, events: [input.events[0], input.events[0]] }));
    const record = cbor.encode({ text: 'public selftest', bytes: Uint8Array.of(251, 255) });
    const cid = await cidFor(record);
    let tree = await MST.create(new MemoryBlockstore());
    tree = await tree.add('test/selftest', cid);
    const root = await tree.getPointer();
    const { blocks } = await tree.getUnstoredBlocks();
    blocks.set(cid, record);
    const { car, storage } = await carStorage(await blocksToCarFile(root, blocks));
    assert.equal(car.roots[0].toString(), root.toString());
    assert.equal((await MST.load(storage, root).get('test/selftest')).toString(), cid.toString());
    bytesEqual(car.blocks.get(cid), record, 'CAR record roundtrip');
    assert.deepEqual(lexJson(cbor.decode(record)), { text: 'public selftest', bytes: { $bytes: '+/8' } });
    blocks.set(cid, cbor.encode({ text: 'tampered' }));
    await assert.rejects(carStorage(await blocksToCarFile(root, blocks)), /Not a valid CID for bytes|CAR block CID hash/);
    console.log(JSON.stringify({ ok: true, selftest: true, networkRequests: 0,
      inputValidation: true, officialCarMst: true, tamperedBlockRejected: true }));
    return;
  }
  assert.equal(positionals.length, 1, 'Supply verification-public.json (or --help)');
  if (values.output) assert.notEqual(resolve(values.output), resolve(positionals[0]), 'Output cannot replace input');
  const started = Date.now();
  const deadline = started + 90_000;
  const report = { ok: false, readOnly: true, startedAt: new Date(started).toISOString(),
    limits: { totalMs: 90_000, fetchMs: 10_000, wsMs: 15_000 }, relays: {}, atproto: { ok: false } };
  let emitted = false;
  const sockets = new Set();
  function emit() {
    if (emitted) return;
    emitted = true;
    report.finishedAt = new Date().toISOString();
    const text = JSON.stringify(report, null, 2) + '\n';
    if (values.output) {
      try { writeFileSync(values.output, text, { flag: 'wx' }); }
      catch (error) { process.stderr.write(`Evidence file not written: ${error.message}\n`); process.exitCode = 1; }
    }
    process.stdout.write(text);
  }
  const watchdog = setTimeout(() => {
    report.ok = false;
    report.error = '90s total deadline exceeded';
    for (const socket of sockets) socket.terminate();
    emit();
    process.exit(1);
  }, 90_000);
  function remaining(ms) {
    assert.ok(Date.now() < deadline, 'Total deadline exceeded');
    return Math.max(1, Math.min(ms, deadline - Date.now()));
  }
  async function get(url, json = true) {
    const response = await fetch(url, { method: 'GET', redirect: 'error',
      signal: AbortSignal.timeout(remaining(10_000)) });
    assert.ok(response.ok, `GET ${url}: HTTP ${response.status}`);
    return json ? response.json() : new Uint8Array(await response.arrayBuffer());
  }
  const xrpc = (method, params, json = true) => get(`${pds}/xrpc/${method}?${new URLSearchParams(params)}`, json);
  async function stage(evidence, action) {
    try { await action(); evidence.ok = true; }
    catch (error) { evidence.ok = false; evidence.error = error.message; }
  }
  function relay(url, input, evidence) {
    return new Promise((resolvePromise, reject) => {
      const socket = new WebSocket(url, { handshakeTimeout: remaining(10_000), followRedirects: false });
      sockets.add(socket);
      const received = new Map();
      let settled = false;
      const timer = setTimeout(() => finish(new Error('WS query timeout')), remaining(15_000));
      function finish(error) {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(['CLOSE', 'android-verification']));
        socket.terminate();
        sockets.delete(socket);
        if (error) reject(error);
        else resolvePromise(input.events.map(({ id }) => received.get(id)));
      }
      socket.on('error', finish);
      socket.on('unexpected-response', (_request, response) => {
        evidence.httpStatus = response.statusCode;
        response.resume();
        finish(new Error(`WS handshake HTTP ${response.statusCode}`));
      });
      socket.on('close', () => finish(new Error('WS closed before all requested events arrived')));
      socket.on('open', () => socket.send(JSON.stringify(['REQ', 'android-verification',
        { ids: input.events.map(event => event.id), limit: 2 }])));
      socket.on('message', (data, binary) => {
        try {
          assert.ok(!binary, 'Unexpected binary relay message');
          const frame = JSON.parse(data.toString());
          if (frame[1] !== 'android-verification') return;
          if (frame[0] === 'EVENT') {
            const expected = input.events.find(event => event.id === frame[2]?.id);
            assert.ok(expected, 'Relay returned an unrequested ID');
            const event = checkedEvent(frame[2], expected, input.pubkey);
            if (received.has(event.id)) assert.deepEqual(received.get(event.id), event, 'Conflicting duplicate');
            received.set(event.id, event);
            evidence.events = input.events.map(({ id }) => received.get(id)).filter(Boolean);
            if (received.size === 2) finish();
          } else if (frame[0] === 'EOSE' || frame[0] === 'CLOSED') {
            finish(new Error(`${frame[0]} before both IDs arrived${frame[2] ? `: ${frame[2]}` : ''}`));
          }
        } catch (error) { finish(error); }
      });
    });
  }
  try {
    const input = publicInput(JSON.parse(await readFile(positionals[0], 'utf8')));
    report.input = input;
    report.receiptNote = 'Android relayOk is reported acknowledgement metadata, not independent receipt proof; REQ signatures below are independently checked.';
    const urls = [...requiredRelays, ...(values.damus ? ['wss://relay.damus.io'] : [])];
    const relayJobs = urls.map(url => {
      const evidence = report.relays[url] = { required: requiredRelays.includes(url), ok: false, events: [] };
      return stage(evidence, () => relay(url, input, evidence));
    });
    const plcJob = stage(report.atproto, async () => {
      const data = await get(`https://plc.directory/${input.did}/data`);
      assert.equal(data.did, input.did, 'PLC DID');
      const key = data.verificationMethods?.atproto;
      assert.ok(typeof key === 'string' && key.startsWith('did:key:'), 'PLC atproto signing key missing');
      const service = data.services?.atproto_pds;
      assert.equal(service?.type, 'AtprotoPersonalDataServer');
      const endpoint = new URL(service.endpoint);
      assert.ok(!endpoint.username && !endpoint.password && !endpoint.search && !endpoint.hash);
      assert.ok(endpoint.pathname === '/' || endpoint.pathname === '');
      assert.equal(endpoint.origin, pds, 'PLC canonical PDS endpoint');
      report.atproto.plc = { url: `https://plc.directory/${input.did}/data`, did: data.did,
        signingKey: key, endpoint: service.endpoint };
      await relayJobs[0];
      assert.ok(report.relays[requiredRelays[0]].ok, 'PDS REQ verification failed');
      const posts = [];
      let entries = [];
      for (const event of report.relays[requiredRelays[0]].events) {
        const post = await decodePost(event);
        assert.equal(post.commit.did, input.did, 'Commit DID');
        await verifyCommit(post, key);
        assert.equal(post.base?.toString() ?? null, posts.at(-1)?.commitCid.toString() ?? null, 'Two-event base chain');
        entries = (await reconstruct(post, entries)).entries;
        posts.push(post);
      }
      report.atproto.commits = posts.map(post => ({ cid: post.commitCid.toString(), rev: post.commit.rev,
        base: post.base?.toString() ?? null, mstRoot: post.commit.data.toString(), path: post.path,
        recordCid: post.recordCid.toString(), signature: true, reconstructedMst: true }));
      const head = posts.at(-1);
      const latest = await xrpc('com.atproto.sync.getLatestCommit', { did: input.did });
      assert.equal(latest.cid, head.commitCid.toString(), 'Latest commit CID');
      assert.equal(latest.rev, head.commit.rev, 'Latest rev');
      report.atproto.latestCommit = latest;
      report.atproto.records = [];
      for (const post of posts) {
        const [collection, rkey] = post.path.split('/');
        const record = await xrpc('com.atproto.repo.getRecord', { repo: input.did, collection, rkey });
        assert.equal(record.uri, `at://${input.did}/${post.path}`);
        assert.equal(record.cid, post.recordCid.toString(), 'getRecord CID');
        assert.deepEqual(record.value, lexJson(post.record), 'getRecord original record');
        report.atproto.records.push(record);
      }
      const { car, storage, blockCount } = await carStorage(await xrpc('com.atproto.sync.getRepo', { did: input.did }, false));
      assert.deepEqual(car.roots.map(cid => cid.toString()), [head.commitCid.toString()], 'CAR head');
      bytesEqual(car.blocks.get(head.commitCid), head.commitBytes, 'CAR original signed commit');
      await verifyCommit({ commit: cbor.decode(car.blocks.get(head.commitCid)) }, key);
      const tree = MST.load(storage, head.commit.data);
      assert.deepEqual((await tree.list()).map(({ key, value }) => ({ key, value: value.toString() })),
        entries.map(({ key, value }) => ({ key, value: value.toString() })), 'Complete official MST');
      for (const post of posts) {
        assert.equal((await tree.get(post.path))?.toString(), post.recordCid.toString(), 'MST record CID');
        bytesEqual(car.blocks.get(post.recordCid), post.recordBytes, 'CAR original record');
      }
      report.atproto.car = { root: head.commitCid.toString(), blockCount, records: posts.length,
        allCidHashes: true, originalCommitBytes: true, commitSignature: true, officialMst: true, originalRecordBytes: true };
    });
    await Promise.all([...relayJobs, plcJob]);
    if (requiredRelays.every(url => report.relays[url].ok)) {
      // Compare the complete signed envelopes, not just their supplied ID/content.
      assert.deepEqual(report.relays[requiredRelays[0]].events, report.relays[requiredRelays[1]].events,
        'PDS/nos.lol signed events differ');
    }
    report.ok = requiredRelays.every(url => report.relays[url].ok) && report.atproto.ok;
  } catch (error) { report.error = error.message; }
  finally {
    clearTimeout(watchdog);
    for (const socket of sockets) socket.terminate();
    if (!report.ok) process.exitCode = 1;
    emit();
  }
}
main().catch(error => { process.stderr.write(`${error.message}\n`); process.exitCode = 1; });
