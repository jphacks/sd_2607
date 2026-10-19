// Read-only independent public relay readback. Reuse installed PDS test dependencies.
// No PLC submission, publishing, private config, signing keys, or phone access.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { mkdir, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
const require = createRequire(new URL('../../../pds/public-information.mjs', import.meta.url));
const { default: WebSocket } = await import(pathToFileURL(require.resolve('ws')));
const { schnorr } = await import(pathToFileURL(require.resolve('@noble/curves/secp256k1.js')));
const { decodeCanonical } = await import(new URL('../../../pds/public-information.mjs', import.meta.url));
const id = process.argv[2];
assert.match(id ?? '', /^[0-9a-f]{64}$/);
const originalCheck = process.argv.includes('--original');
const event = await new Promise((resolve, reject) => {
  const ws = new WebSocket('wss://pds.oriom.dev/', { maxPayload: 16384, handshakeTimeout: 10000 });
  const timer = setTimeout(() => { ws.terminate(); reject(new Error('Bounded relay readback timed out')); }, 20000);
  const finish = (error, value) => { clearTimeout(timer); ws.terminate(); error ? reject(error) : resolve(value); };
  ws.on('error', error => finish(error));
  ws.on('open', () => ws.send(JSON.stringify(['REQ', 'esp-readback', { ids: [id], limit: 1 }])));
  ws.on('message', data => {
    try {
      const frame = JSON.parse(data.toString());
      if (frame[0] === 'EVENT' && frame[1] === 'esp-readback' && frame[2]?.id === id) finish(null, frame[2]);
      else if (frame[0] === 'EOSE' && frame[1] === 'esp-readback') finish(new Error('Exact event ID absent from relay'));
    } catch (error) { finish(error); }
  });
});
const hash = value => createHash('sha256').update(value).digest('hex');
assert.equal(hash(JSON.stringify([0, event.pubkey, event.created_at, event.kind, event.tags, event.content])), id);
assert(schnorr.verify(Buffer.from(event.sig, 'hex'), Buffer.from(id, 'hex'), Buffer.from(event.pubkey, 'hex')));
const summary = { id, authorSignature: true, kind: event.kind, contentBytes: Buffer.byteLength(event.content), contentSha256: hash(event.content) };
if (originalCheck) {
  const tag = event.tags.find(tag => tag[0] === 'mosimo.oriom.dev:ext:dev.oriom.mosimo.defs#bsafV1');
  assert(tag, 'Missing original BSAF reference');
  const bsaf = decodeCanonical(Buffer.from(tag[1], 'base64'));
  const uri = bsaf.original.uri;
  assert.match(uri, /^at:\/\/did:plc:[a-z2-7]{24}\/app\.bsky\.feed\.post\/[A-Za-z0-9_~.:-]+$/);
  const url = new URL('https://public.api.bsky.app/xrpc/app.bsky.feed.getPosts');
  url.searchParams.append('uris', uri);
  const response = await fetch(url, { signal: AbortSignal.timeout(10000) });
  assert(response.ok, `Original source read failed: HTTP ${response.status}`);
  const body = await response.json();
  const post = body.posts?.find(post => post.uri === uri);
  assert(post, 'Original public source unavailable');
  assert.equal(post.cid, bsaf.original.cid, 'Source CID changed; do not substitute a different original');
  assert.equal(event.content, post.record.text, 'Nostr content is not exactly the referenced original text');
  Object.assign(summary, { originalUri: uri, originalCid: post.cid, exactOriginalText: true,
    bsafType: bsaf.tags.find(tag => tag.startsWith('type:')) });
}
const directory = new URL('../.pio/', import.meta.url);
await mkdir(directory, { recursive: true });
await writeFile(new URL(`relay-${id}.json`, directory), JSON.stringify(event, null, 2) + '\n');
await writeFile(new URL(`relay-${id}-proof.json`, directory), JSON.stringify(summary, null, 2) + '\n');
console.log(JSON.stringify(summary));
