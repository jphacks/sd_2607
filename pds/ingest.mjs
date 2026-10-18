import { verifyEvent } from 'nostr-tools';
import { sqliteNow } from './store.mjs';
import { BlockMap, blocksToCarFile, readCar } from '@atproto/repo';
import * as cbor from '@ipld/dag-cbor';
import { CID } from 'multiformats/cid';
import { decodePost, verifyCommit, reconstruct, isBsafImport } from './repository.mjs';
import { commitFrame, encodeFrame, futureRevisionDelay } from './sync.mts';

const namespace = 'mosimo.oriom.dev';
const isPost = event => event.kind === 1 && event.tags.some(tag => tag[0] === 't' && tag[1] === namespace);

export function createIngestion({ config, store, resolveIdentity, clock = Date.now }) {
  const sqlite = store.dialect === 'sqlite';
  const now = sqlite ? sqliteNow : 'now()';
  async function ingest(input, { forward = true } = {}) {
    // JSON serialization removes nostr-tools' caller-controlled verification cache symbols.
    let event;
    try {
      if (Buffer.byteLength(JSON.stringify(input)) > config.maxEventBytes) throw new Error('Event too large');
      event = JSON.parse(JSON.stringify(input));
      if (![1, 5].includes(event.kind) || !Number.isSafeInteger(event.created_at) || event.created_at < 0
        || !/^[0-9a-f]{64}$/.test(event.id) || !/^[0-9a-f]{64}$/.test(event.pubkey)
        || !/^[0-9a-f]{128}$/.test(event.sig) || !verifyEvent(event)) throw new Error('Invalid event or signature');
      // Preserve only the standard signed envelope, never implementation-specific metadata.
      event = Object.fromEntries(['id', 'pubkey', 'created_at', 'kind', 'tags', 'content', 'sig'].map(key => [key, event[key]]));
    } catch (error) { return { accepted: false, message: `invalid: ${error.message}` }; }
    return store.transaction(async tx => {
      // Serializing insertion until COMMIT makes arrival a gap-free multi-node poll cursor.
      if (!sqlite) await tx.query('SELECT pg_advisory_xact_lock(2607, 1)');
      if (event.kind !== 5 && (await tx.query('SELECT 1 FROM deletions WHERE event_id=$1 AND pubkey=$2', [event.id, event.pubkey])).rowCount) {
        return { accepted: false, message: 'blocked: deleted by author' };
      }
      if ((await tx.query('SELECT 1 FROM events WHERE id=$1', [event.id])).rowCount) {
        return { accepted: true, duplicate: true, message: 'duplicate: already stored' };
      }
      if (Number((await tx.query('SELECT count(*) AS count FROM events')).rows[0].count) >= config.maxEvents) {
        return { accepted: false, message: 'restricted: storage capacity reached' };
      }
      await tx.query('INSERT INTO events(id,pubkey,created_at,kind,event) VALUES($1,$2,$3,$4,$5)',
        [event.id, event.pubkey, event.created_at, event.kind, JSON.stringify(event)]);
      if (event.kind === 5) {
        for (const tag of event.tags) {
          if (tag[0] === 'e' && /^[0-9a-f]{64}$/.test(tag[1])) {
            await tx.query(`INSERT INTO deletions(event_id,pubkey,created_at) VALUES($1,$2,$3)
              ON CONFLICT(event_id,pubkey) DO NOTHING`, [tag[1], event.pubkey, event.created_at]);
          }
        }
      } else if (isPost(event) && !isBsafImport(event)) {
        await tx.query('INSERT INTO jobs(event_id) VALUES($1)', [event.id]);
      }
      if (forward && (isPost(event) || event.kind === 5)) {
        for (const peer of config.peers) {
          await tx.query('INSERT INTO outbox(peer,event_id) VALUES($1,$2) ON CONFLICT DO NOTHING', [peer.url, event.id]);
        }
      }
      return { accepted: true, message: isPost(event) && isBsafImport(event)
        ? 'stored: Nostr-only BSAF import' : 'stored: repository verification is asynchronous' };
    });
  }

  async function processOne() {
    return store.transaction(async tx => {
      const job = (await tx.query(`SELECT j.*, e.event FROM jobs j JOIN events e ON e.id=j.event_id
        WHERE j.status IN ('pending','retry') AND j.next_attempt <= ${now}
        ORDER BY j.next_attempt ${sqlite ? '' : 'FOR UPDATE OF j SKIP LOCKED'} LIMIT 1`)).rows[0];
      if (!job) return false;
      if (isBsafImport(job.event)) {
        await tx.query("UPDATE jobs SET status='invalid',error='Nostr-only BSAF import; retained without atproto publication' WHERE event_id=$1", [job.event_id]);
        return true;
      }
      let decoded;
      try { decoded = await decodePost(job.event); }
      catch (error) {
        await tx.query("UPDATE jobs SET status='invalid',error=$2 WHERE event_id=$1", [job.event_id, error.message]);
        return true;
      }
      const { commit, commitCid, base } = decoded;
      // Serialize each DID across workers; group sequencing is separately serialized below.
      if (!sqlite) await tx.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [commit.did]);
      // An unregistered DID (registered later, e.g. by proxy) or a not-yet-verified base must not drift to an hour.
      const backoff = 2 ** Math.min(job.attempts + 1, 12);
      const retry = async (message, seconds = Math.min(3600, backoff)) => {
        await tx.query(`UPDATE jobs SET status='retry',error=$2,attempts=attempts+1,
          next_attempt=${sqlite ? "strftime('%Y-%m-%dT%H:%M:%fZ','now','+' || $3 || ' seconds')" : 'now()+make_interval(secs => $3)'} WHERE event_id=$1`,
        [job.event_id, message, seconds]);
      };
      // A fast author clock is known to clear exactly when the five-minute window opens; retry then, not on backoff.
      const ahead = futureRevisionDelay(commit.rev, clock());
      if (ahead > 0) {
        await retry('Future revision exceeds five-minute clock skew', Math.min(3600, Math.ceil(ahead / 1000)));
        return true;
      }
      let identity;
      try { identity = await resolveIdentity(commit.did); }
      catch (error) { await retry(`PLC resolution: ${error.message}`, Math.min(60, backoff)); return true; }
      try { await verifyCommit(decoded, identity.signingKey); }
      catch (error) { await retry(`Signature/key state: ${error.message}`); return true; }
      let previous;
      if (base) {
        previous = (await tx.query('SELECT * FROM commits WHERE cid=$1 AND did=$2', [base.toString(), commit.did])).rows[0];
        if (!previous) { await retry('Missing verified base commit', Math.min(60, backoff)); return true; }
        if (commit.rev <= previous.rev) {
          await tx.query("UPDATE jobs SET status='invalid',error='Non-increasing revision' WHERE event_id=$1", [job.event_id]);
          return true;
        }
      }
      let rebuilt;
      try {
        rebuilt = await reconstruct(decoded, (previous?.entries ?? []).map(entry => ({ key: entry.key, value: CID.parse(entry.value) })));
      } catch (error) {
        await tx.query("UPDATE jobs SET status='invalid',error=$2 WHERE event_id=$1", [job.event_id, error.message]);
        return true;
      }
      const blocks = new BlockMap();
      for (const block of rebuilt.blocks) blocks.set(block.cid, block.bytes);
      if (previous) {
        const old = await readCar(previous.car);
        for (const entry of previous.entries) {
          const cid = CID.parse(entry.value);
          const bytes = old.blocks.get(cid);
          if (!bytes) throw new Error('Stored base CAR is incomplete');
          blocks.set(cid, bytes);
        }
      }
      const car = await blocksToCarFile(commitCid, blocks);
      const entries = rebuilt.entries.map(entry => ({ key: entry.key, value: entry.value.toString() }));
      await tx.query(`INSERT INTO commits(cid,did,rev,base,entries,car,commit_bytes) VALUES($1,$2,$3,$4,$5,$6,$7)
        ON CONFLICT DO NOTHING`, [commitCid.toString(), commit.did, commit.rev, base?.toString() ?? null,
        JSON.stringify(entries), Buffer.from(car), Buffer.from(decoded.commitBytes)]);
      for (const { cid, bytes } of blocks.entries()) {
        await tx.query('INSERT INTO blocks(cid,bytes) VALUES($1,$2) ON CONFLICT DO NOTHING', [cid.toString(), Buffer.from(bytes)]);
        await tx.query('INSERT INTO repo_blocks(did,cid) VALUES($1,$2) ON CONFLICT DO NOTHING', [commit.did, cid.toString()]);
      }
      const group = config.groups.find(group => new URL(group.endpoint).origin === identity.endpoint);
      if (group) {
        await tx.query('INSERT INTO group_sequences(group_id) VALUES($1) ON CONFLICT DO NOTHING', [group.id]);
        const sequence = (await tx.query(`SELECT seq FROM group_sequences WHERE group_id=$1 ${sqlite ? '' : 'FOR UPDATE'}`, [group.id])).rows[0];
        const head = (await tx.query('SELECT * FROM repos WHERE group_id=$1 AND did=$2', [group.id, commit.did])).rows[0];
        if (head?.head !== commitCid.toString()) {
          if ((head?.head ?? null) !== (base?.toString() ?? null)) {
            await tx.query("UPDATE jobs SET status='conflict',error='Not an extension of canonical head' WHERE event_id=$1", [job.event_id]);
            return true;
          }
          let seq = BigInt(sequence.seq);
          const time = new Date(clock()).toISOString();
          const append = async (type, body) => {
            seq += 1n;
            if (seq > BigInt(Number.MAX_SAFE_INTEGER)) throw new Error('Firehose sequence exhausted');
            const bytes = encodeFrame(type, { ...body, seq: Number(seq) });
            if (bytes.length > config.maxBufferedBytes) throw new Error('Stream frame exceeds maxBufferedBytes');
            await tx.query('INSERT INTO streams(group_id,seq,frame) VALUES($1,$2,$3)', [group.id, seq.toString(), bytes]);
          };
          if (!head) {
            await append('#identity', { did: commit.did, time, ...(identity.handle ? { handle: identity.handle } : {}) });
            await append('#account', { did: commit.did, time, active: true });
          }
          seq += 1n;
          if (seq > BigInt(Number.MAX_SAFE_INTEGER)) throw new Error('Firehose sequence exhausted');
          const bytes = await commitFrame({ commitCid, commitBytes: decoded.commitBytes,
            data: commit.data, blocks, repo: commit.did, rev: commit.rev,
            since: head?.rev ?? null, prevData: previous ? cbor.decode(previous.commit_bytes).data : undefined,
            ops: [{ action: 'create', path: decoded.path, cid: decoded.recordCid }],
            time, seq: Number(seq), maxBufferedBytes: config.maxBufferedBytes });
          await tx.query('INSERT INTO streams(group_id,seq,frame) VALUES($1,$2,$3)', [group.id, seq.toString(), bytes]);
          await tx.query('UPDATE group_sequences SET seq=$2 WHERE group_id=$1', [group.id, seq.toString()]);
          await tx.query(`INSERT INTO repos(group_id,did,head,rev) VALUES($1,$2,$3,$4)
            ON CONFLICT(group_id,did) DO UPDATE SET head=excluded.head,rev=excluded.rev`,
          [group.id, commit.did, commitCid.toString(), commit.rev]);
        }
      }
      await tx.query("UPDATE jobs SET status='validated',error=NULL WHERE event_id=$1", [job.event_id]);
      // A just-arrived base can unblock a child immediately, regardless of arrival order.
      await tx.query(`UPDATE jobs SET next_attempt=${now} WHERE event_id IN (
        SELECT event_id FROM jobs WHERE status='retry' AND error='Missing verified base commit'
        ${sqlite ? '' : 'FOR UPDATE SKIP LOCKED'}
      )`);
      return true;
    });
  }
  return { ingest, processOne };
}
