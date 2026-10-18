// Read-only retrieval of the one existing Peer test event. No EVENT/PLC/crawl writes.
import assert from 'node:assert/strict';
import { writeFileSync } from 'node:fs';
import { decodePost } from '../../pds/repository.mjs';

const pubkey = '3503d6e4193c79c955f57704322b47a92bd7a78d5d8d3be971c6ba65021cc818';
const content = 'TEST ONLY - NOT DISASTER INFORMATION ISOLATED1338';
const socket = new WebSocket('wss://pds.oriom.dev');
let timer;
try {
  const event = await new Promise((resolve, reject) => {
    timer = setTimeout(() => reject(new Error('15s relay retrieval timeout')), 15000);
    socket.onopen = () => socket.send(JSON.stringify(['REQ', 'android-f441-read', { authors: [pubkey], kinds: [1], limit: 10 }]));
    socket.onerror = () => reject(new Error('Relay connection failed'));
    socket.onmessage = message => {
      const frame = JSON.parse(message.data);
      if (frame[0] === 'EVENT' && frame[1] === 'android-f441-read' && frame[2]?.id?.startsWith('f44162129aa3')) resolve(frame[2]);
      if (frame[0] === 'EOSE') reject(new Error('Target event absent at relay EOSE'));
    };
  });
  assert.equal(event.pubkey, pubkey);
  assert.equal(event.content, content);
  const decoded = await decodePost(event); // Independent Nostr signature, record reconstruction and commit-shape checks.
  assert.equal(decoded.commit.did, 'did:plc:uhgusj7lhdpjq3vpbxns4oam');
  assert.equal(decoded.base, null, 'Expected first post, not a missing-history chain');
  const evidence = {
    checkedAt: new Date().toISOString(), route: 'DIRECT Peer online export, not a proved BLE chain',
    relay: 'wss://pds.oriom.dev', event, did: decoded.commit.did, path: decoded.path,
    atUri: `at://${decoded.commit.did}/${decoded.path}`, recordCid: decoded.recordCid.toString(),
    commitCid: decoded.commitCid.toString(), rev: decoded.commit.rev,
    nostrSignatureVerified: true, base: null,
  };
  writeFileSync(new URL('./hardware-f441-public-event.json', import.meta.url), JSON.stringify(evidence, null, 2) + '\n', { flag: 'wx' });
  console.log(JSON.stringify({ ...evidence, event: { id: event.id, pubkey, content } }, null, 2));
} finally {
  clearTimeout(timer);
  if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(['CLOSE', 'android-f441-read']));
  socket.close();
}
