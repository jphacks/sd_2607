import { BlockMap, MemoryBlockstore, MST, blocksToCarFile } from '@atproto/repo';
import { TID } from '@atproto/common-web';
import * as cbor from '@ipld/dag-cbor';
import type { CID } from 'multiformats/cid';

export const MAX_COMMIT_BYTES = 2_000_000;
export const MAX_COMMIT_OPS = 200;
export const MAX_SYNC_BYTES = 10_000;
export const MAX_FUTURE_MS = 5 * 60 * 1000;

export function futureRevisionDelay(rev: string, now: number): number {
  return Math.max(0, TID.fromStr(rev).timestamp() / 1000 - now - MAX_FUTURE_MS);
}

export const encodeFrame = (type: string, body: Parameters<typeof cbor.encode>[0]): Buffer =>
  Buffer.concat([cbor.encode({ op: 1, t: type }), cbor.encode(body)]);

type CreateOp = { action: 'create'; path: string; cid: CID };

/** Build the same covering proofs as Repo.formatCommit, without changing signed bytes. */
export async function commitFrame({ commitCid, commitBytes, data, blocks, ops, repo, rev,
  since, prevData, time, seq, maxBufferedBytes }: {
  commitCid: CID; commitBytes: Uint8Array; data: CID; blocks: BlockMap; ops: CreateOp[];
  repo: string; rev: string; since: string | null; prevData?: CID;
  time: string; seq: number; maxBufferedBytes: number;
}): Promise<Buffer> {
  const tree = MST.load(new MemoryBlockstore(blocks), data);
  const proof = new BlockMap();
  // Above the op limit, avoid building a proof that cannot be published anyway.
  if (ops.length <= MAX_COMMIT_OPS) {
    for (const op of ops) {
      proof.addMap(await tree.getCoveringProof(op.path));
      const record = blocks.get(op.cid);
      if (!record) throw new Error('Missing stream record block');
      proof.set(op.cid, record);
    }
    proof.set(commitCid, commitBytes);
    const car = await blocksToCarFile(commitCid, proof);
    if (car.length <= MAX_COMMIT_BYTES) {
      const frame = encodeFrame('#commit', { repo, commit: commitCid, rev, since,
        rebase: false, tooBig: false, blocks: car, ops, blobs: [], time, seq,
        ...(prevData ? { prevData } : {}) });
      if (frame.length <= maxBufferedBytes) return frame;
    }
  }
  const car = await blocksToCarFile(commitCid, new BlockMap().set(commitCid, commitBytes));
  if (car.length > MAX_SYNC_BYTES) throw new Error('Commit-only sync CAR exceeds 10000 bytes');
  const frame = encodeFrame('#sync', { did: repo, rev, blocks: car, time, seq });
  if (frame.length > maxBufferedBytes) throw new Error('Sync frame exceeds maxBufferedBytes');
  return frame;
}
