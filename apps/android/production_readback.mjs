// Read-only bounded relay/PDS/PLC/AppView checks for the approved marker only.
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { decodePost } from '../../pds/repository.mjs';
const [label, phase] = process.argv.slice(2);
assert(['ASUS', 'OPPO'].includes(label) && ['absence', 'proof'].includes(phase));
const directory = new URL('./.gradle/production-20261019/', import.meta.url);
const state = JSON.parse(readFileSync(new URL('state.json', directory)));
const device = state.devices[label];
const pubkey = /Nostr: ([0-9a-f]{64})/.exec(device.identityBefore.public)[1];
const did = /DID: (did:plc:[a-z2-7]{24})/.exec(device.identityBefore.public)[1];
const socket = new WebSocket('wss://pds.oriom.dev/');
let timer;
let events = [];
try {
  await new Promise((resolve, reject) => {
    timer = setTimeout(() => reject(new Error('20s relay read timeout')), 20000);
    socket.onopen = () => socket.send(JSON.stringify(['REQ', 'offline-proof', {
      authors: [pubkey], kinds: [1], since: Math.floor(Date.parse(state.startedAt) / 1000) - 60, limit: 1000,
    }]));
    socket.onerror = () => reject(new Error('Relay read failed'));
    socket.onmessage = message => {
      const frame = JSON.parse(message.data);
      if (frame[0] === 'EVENT' && frame[1] === 'offline-proof' && frame[2]?.content === device.marker) events.push(frame[2]);
      if (frame[0] === 'EOSE' && frame[1] === 'offline-proof') resolve();
    };
  });
} finally {
  clearTimeout(timer);
  socket.close();
}
const checkedAt = new Date().toISOString();
if (phase === 'absence') {
  assert(!device.newPostRequested, 'Already requested; never replace absence checkpoint');
  assert.equal(events.length, 0, 'Marker already exists: do NOT create');
  const proof = { checkedAt, absent: true, marker: device.marker, pubkey, relay: 'wss://pds.oriom.dev/', authorFilteredSince: state.startedAt, completedEOSE: true };
  writeFileSync(new URL(`${label}-absence.json`, directory), JSON.stringify(proof, null, 2) + '\n', { flag: 'wx' });
  console.log(JSON.stringify(proof));
} else {
  assert.equal(events.length, 1, 'Expected exactly one matching event');
  const event = events[0];
  assert.equal(event.pubkey, pubkey);
  assert.equal(event.content, device.marker); // No hidden footer or altered text.
  assert(event.id.startsWith(device.eventPrefix));
  const decoded = await decodePost(event); // Nostr ID/BIP340, canonical record and signed commit shape.
  assert.equal(decoded.commit.did, did);
  const path = decoded.path;
  const atUri = `at://${did}/${path}`;
  async function get(base, method, params = {}) {
    const url = new URL('/xrpc/' + method, base);
    for (const [key, value] of Object.entries(params)) url.searchParams.set(key, value);
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(15000) });
      const text = await response.text();
      let body;
      try { body = JSON.parse(text); } catch { body = { nonJsonBytes: text.length }; }
      return { url: url.href, status: response.status, body };
    } catch (error) { return { url: url.href, error: error.message }; }
  }
  const record = await get('https://pds.oriom.dev', 'com.atproto.repo.getRecord', { repo: did, collection: path.split('/')[0], rkey: path.split('/')[1] });
  const head = await get('https://pds.oriom.dev', 'com.atproto.sync.getLatestCommit', { did });
  const appview = await get('https://mosimo.oriom.dev', 'dev.oriom.mosimo.feed.getPostThread', { uri: atUri });
  const plcResponse = await fetch('https://plc.directory/' + did, { signal: AbortSignal.timeout(15000) });
  const plc = { status: plcResponse.status, body: await plcResponse.json() };
  const exactRecord = record.status === 200 && record.body.value?.text === device.marker && record.body.cid === decoded.recordCid.toString();
  const proof = { checkedAt, marker: device.marker, event, did, atUri, path,
    recordCid: decoded.recordCid.toString(), commitCid: decoded.commitCid.toString(), rev: decoded.commit.rev,
    base: decoded.base?.toString() ?? null, nostrIdAndSignatureVerified: true, exactContent: true,
    exactRecord, record, head, plc, appview };
  writeFileSync(new URL(`${label}-proof.json`, directory), JSON.stringify(proof, null, 2) + '\n');
  console.log(JSON.stringify({ ...proof, event: { id: event.id, pubkey, content: event.content } }, null, 2));
}
