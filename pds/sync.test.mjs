import assert from 'node:assert/strict';
import test from 'node:test';
import { MemoryBlockstore, MST, Repo, blocksToCarFile, readCar, verifyProofs } from '@atproto/repo';
import { Secp256k1Keypair, verifySignature } from '@atproto/crypto';
import { TID } from '@atproto/common-web';
import * as cbor from '@ipld/dag-cbor';
import { decodeFirst } from 'cborg';
import { commitFrame, futureRevisionDelay, MAX_COMMIT_BYTES, MAX_COMMIT_OPS, MAX_SYNC_BYTES } from './sync.mts';

const repo = 'did:plc:abcdefghijklmnopqrstuvwx';
const collection = 'dev.oriom.mosimo.post';
const key = await Secp256k1Keypair.create();
const time = '2026-10-18T00:00:00.000Z';

async function fixture(count, textBytes) {
  // Construct real multi-leaf repository state directly, not giant production records.
  const writes = Array.from({ length: count }, (_, i) => ({
    collection, rkey: TID.fromTime(1_792_281_600_000_000 + i, 0).toString(),
    record: { $type: collection, text: `${i}:` + 'x'.repeat(textBytes) },
  }));
  assert.ok(writes.every(write => Buffer.byteLength(JSON.stringify(write.record)) < 131_072));
  const commit = await Repo.formatInitCommit(new MemoryBlockstore(), repo, key, writes);
  const commitBytes = commit.newBlocks.get(commit.cid);
  const decoded = cbor.decode(commitBytes);
  const tree = MST.load(new MemoryBlockstore(commit.newBlocks), decoded.data);
  const ops = await Promise.all(writes.map(async write => ({ action: 'create',
    path: `${collection}/${write.rkey}`, cid: await tree.get(`${collection}/${write.rkey}`) })));
  return { commitCid: commit.cid, commitBytes, data: decoded.data, blocks: commit.newBlocks,
    repo, rev: commit.rev, since: null, ops, time, seq: 3, maxBufferedBytes: 4_194_304 };
}

async function assertSync(frame, source) {
  assert.ok(frame.length <= source.maxBufferedBytes);
  const [header, bytes] = decodeFirst(frame, cbor.decodeOptions);
  assert.deepEqual(header, { op: 1, t: '#sync' });
  const body = cbor.decode(bytes);
  assert.deepEqual(Object.keys(body).sort(), ['blocks', 'did', 'rev', 'seq', 'time']);
  assert.equal(body.did, repo);
  assert.equal(body.rev, source.rev);
  assert.equal(body.seq, 3);
  assert.equal(body.time, time);
  assert.ok(body.blocks.length > 0 && body.blocks.length <= MAX_SYNC_BYTES);
  const car = await readCar(body.blocks);
  assert.equal(car.blocks.size, 1);
  assert.deepEqual(car.roots.map(cid => cid.toString()), [source.commitCid.toString()]);
  assert.deepEqual(Buffer.from(car.blocks.get(source.commitCid)), Buffer.from(source.commitBytes));
  const { sig, ...unsigned } = cbor.decode(car.blocks.get(source.commitCid));
  assert.equal(await verifySignature(key.did(), cbor.encode(unsigned), sig), true);
}

test('real covering proof above 2M bytes emits signed commit-only sync despite available frame budget', async () => {
  const source = await fixture(180, 12_000);
  assert.ok(source.ops.length <= MAX_COMMIT_OPS);
  // All paths changed: their covering proof contains every leaf, exceeding the CAR limit.
  const fullCar = await blocksToCarFile(source.commitCid, source.blocks);
  assert.ok(fullCar.length > MAX_COMMIT_BYTES && fullCar.length < source.maxBufferedBytes);
  await assertSync(await commitFrame(source), source);
});

test('exactly 200 operations use nonempty commit proof; 201 use sync', async () => {
  const source = await fixture(200, 16);
  const [header, bytes] = decodeFirst(await commitFrame(source), cbor.decodeOptions);
  assert.equal(header.t, '#commit');
  const body = cbor.decode(bytes);
  assert.equal(body.tooBig, false);
  assert.equal(body.ops.length, 200);
  assert.equal(body.since, null);
  assert.ok(body.blocks.length > 0 && body.blocks.length <= MAX_COMMIT_BYTES);
  const claims = source.ops.map(op => ({ collection, rkey: op.path.split('/')[1], cid: op.cid }));
  assert.deepEqual(await verifyProofs(body.blocks, claims, repo, key.did()), { verified: claims, unverified: [] });
  const oversized = await fixture(201, 16);
  await assertSync(await commitFrame(oversized), oversized);
});

test('encoded frame budget is enforced including CBOR overhead, even when the CAR fits', async () => {
  const source = await fixture(1, 1500);
  const [header, bytes] = decodeFirst(await commitFrame(source), cbor.decodeOptions);
  assert.equal(header.t, '#commit');
  source.maxBufferedBytes = cbor.decode(bytes).blocks.length;
  await assertSync(await commitFrame(source), source);
  await assert.rejects(commitFrame({ ...source, maxBufferedBytes: 1 }), /maxBufferedBytes/);
});

test('official TID microseconds enforce inclusive five-minute boundary without an old-revision cutoff', () => {
  const now = Date.parse(time);
  const rev = micros => TID.fromTime(micros, 0).toString();
  assert.equal(futureRevisionDelay(rev((now + 300_000) * 1000), now), 0);
  assert.ok(futureRevisionDelay(rev((now + 300_000) * 1000 + 1), now) > 0);
  assert.equal(futureRevisionDelay(rev((now + 300_000) * 1000 + 1), now + 1), 0);
  assert.equal(futureRevisionDelay('2222222222222', now), 0);
});
