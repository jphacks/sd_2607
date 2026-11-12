import { MST, MemoryBlockstore, blocksToCarFile } from '@atproto/repo';
import { verifySignature } from '@atproto/crypto';
import * as cbor from '@ipld/dag-cbor';
import { CID } from 'multiformats/cid';
import { sha256 } from 'multiformats/hashes/sha2';
import { verifyEvent } from 'nostr-tools';

// dev.jisaku.mosimo core §7: discovery tag, basic tags and self-contained extension tags.
export const TAG = 'mosimo.jisaku.dev';
export const COLLECTION = 'dev.jisaku.mosimo.post';
export const EXT_PREFIX = 'mosimo:ext:';
export const BSAF_EXT = 'dev.jisaku.mosimo.public.bsaf';
const tid = /^[234567abcdefghij][234567abcdefghijklmnopqrstuvwxyz]{12}$/;

function requireValue(condition, message) {
  if (!condition) throw new Error(message);
}

export function object(value) {
  return value !== null && typeof value === 'object'
    && !Array.isArray(value) && !(value instanceof Uint8Array) && !CID.asCID(value);
}

function dataModel(value) {
  if (value === null || typeof value === 'string' || typeof value === 'boolean'
    || value instanceof Uint8Array || CID.asCID(value)) return;
  if (typeof value === 'number') {
    requireValue(Number.isSafeInteger(value) && !Object.is(value, -0), 'Invalid atproto integer');
    return;
  }
  requireValue(Array.isArray(value) || object(value), 'Invalid atproto data');
  for (const child of Object.values(value)) dataModel(child);
}

/** Core §3.3: unpadded standard Base64 for bytes carried in custom tags. */
export const encodeBytes = bytes => Buffer.from(bytes).toString('base64').replace(/=+$/, '');
export function decodeBytes(value) {
  requireValue(typeof value === 'string' && /^[A-Za-z0-9+/]*$/.test(value), 'Invalid unpadded Base64');
  const bytes = new Uint8Array(Buffer.from(value, 'base64'));
  requireValue(encodeBytes(bytes) === value, 'Noncanonical Base64');
  return bytes;
}

export function decodeCbor(bytes) {
  const value = cbor.decode(bytes);
  dataModel(value);
  requireValue(Buffer.from(cbor.encode(value)).equals(Buffer.from(bytes)), 'Noncanonical CBOR');
  return value;
}

function repositoryCid(value) {
  const cid = CID.asCID(value);
  requireValue(cid && cid.version === 1 && cid.code === cbor.code
    && cid.multihash.code === sha256.code && cid.multihash.size === 32, 'Invalid repository CID');
  return cid;
}

async function cidFor(bytes) {
  return CID.createV1(cbor.code, await sha256.digest(bytes));
}

/** Core §3.1 Timestamp: UTC, uppercase Z, optional fraction. Returns whole seconds. */
export function timestampSeconds(value) {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(\.\d+)?Z$/.exec(value);
  requireValue(match, 'Invalid UTC timestamp');
  const [year, month, day, hour, minute, second] = match.slice(1, 7).map(Number);
  requireValue(year >= 1 && month >= 1 && month <= 12 && day >= 1 && day <= 31
    && hour <= 23 && minute <= 59 && second <= 59, 'Invalid UTC calendar date');
  const date = new Date(0);
  date.setUTCFullYear(year, month - 1, day);
  date.setUTCHours(hour, minute, second, 0);
  requireValue(date.getUTCFullYear() === year && date.getUTCMonth() === month - 1
    && date.getUTCDate() === day, 'Invalid UTC calendar date');
  return date.getTime() / 1000;
}

/** report §1: NIP-56 report. The node policy (thresholds) belongs to clients; the PDS stores and relays it. */
export const REPORT_KIND = 1984;
const reportTypes = new Set(['spam', 'profanity', 'illegal', 'impersonation', 'nudity', 'malware', 'other']);

/** report §1 shape: t, e (target + type), p (author + same type), mosimo:exp in this order; comment <= 280 bytes.
 * Unknown NIP-56 types are kept (receivers count them as `other`); later unknown tags are preserved. */
export function validateReport(event) {
  const tags = event.tags;
  requireValue(Array.isArray(tags) && tags.length >= 4 && tags.length <= 32 && tags.every(Array.isArray), 'Malformed report tags');
  const [t, e, p, exp] = tags;
  requireValue(t.length === 2 && t[0] === 't' && t[1] === TAG, 'Report must start with the protocol t tag');
  requireValue(e.length === 3 && e[0] === 'e' && /^[0-9a-f]{64}$/.test(e[1]), 'Report target must be one e tag with a type');
  requireValue(p.length === 3 && p[0] === 'p' && /^[0-9a-f]{64}$/.test(p[1]), 'Report author must be one p tag with a type');
  requireValue(typeof e[2] === 'string' && e[2] === p[2] && /^[a-z]{1,32}$/.test(e[2]), 'Report e and p types must match');
  requireValue(exp.length === 2 && exp[0] === 'mosimo:exp', 'Report needs a delivery deadline');
  timestampSeconds(exp[1]);
  requireValue(tags.slice(4).every(tag => !['t', 'e', 'p', 'mosimo:exp'].includes(tag[0])), 'Report tags must appear once each');
  requireValue(typeof event.content === 'string' && Buffer.byteLength(event.content) <= 280, 'Report comment exceeds 280 bytes');
  return { target: e[1], author: p[1], type: reportTypes.has(e[2]) ? e[2] : 'other' };
}

export const isPost = event => event?.kind === 1 && Array.isArray(event.tags)
  && event.tags.some(tag => Array.isArray(tag) && tag[0] === 't' && tag[1] === TAG);

/** Routing, not authorization: a malformed claimed import also fails closed for atproto.
 * A valid BSAF extension without `original` (natively created) remains an ordinary post. */
export function isBsafImport(event) {
  const tags = event.tags.filter(tag => tag[0] === `${EXT_PREFIX}${BSAF_EXT}`);
  if (tags.length === 0) return false;
  if (tags.length !== 1) return true;
  try {
    const tag = tags[0];
    requireValue(tag.length === 2, 'Invalid BSAF extension tag');
    const fields = decodeCbor(decodeBytes(tag[1]));
    requireValue(object(fields) && !Object.hasOwn(fields, '$type'), 'Invalid BSAF fields');
    return Object.hasOwn(fields, 'original');
  } catch { return true; }
}

/** Validate the signed Nostr envelope and recover the original record and commit bytes (core §7.2). */
export async function decodePost(event) {
  requireValue(event && event.kind === 1 && typeof event.content === 'string'
    && Number.isSafeInteger(event.created_at) && Array.isArray(event.tags), 'Invalid post event');
  for (const [field, length] of [['id', 64], ['pubkey', 64], ['sig', 128]]) {
    requireValue(typeof event[field] === 'string'
      && new RegExp(`^[0-9a-f]{${length}}$`).test(event[field]), `Invalid Nostr ${field}`);
  }
  requireValue(event.tags.every(tag => Array.isArray(tag)
    && tag.every(value => typeof value === 'string')), 'Invalid Nostr tags');
  // nostr-tools caches verification on the object; never trust caller-owned cache symbols.
  const envelope = {
    kind: event.kind, content: event.content, created_at: event.created_at,
    tags: event.tags, id: event.id, pubkey: event.pubkey, sig: event.sig,
  };
  requireValue(verifyEvent(envelope), 'Invalid Nostr signature');

  // Fixed positions: t, mosimo:v, mosimo:commit, mosimo:base, mosimo:rkey, mosimo:exp, [mosimo:created], ext..., others.
  const tags = event.tags;
  const basic = (index, name) => {
    requireValue(tags[index]?.length === 2 && tags[index][0] === name, `Expected ${name} at tag position ${index}`);
    return tags[index][1];
  };
  requireValue(basic(0, 't') === TAG, 'Expected protocol discovery tag first');
  requireValue(tags.filter(tag => tag[0] === 't' && tag[1] === TAG).length === 1, 'Expected exactly one discovery tag');
  requireValue(basic(1, 'mosimo:v') === '1', 'Unsupported basic version');
  const commitBytes = decodeBytes(basic(2, 'mosimo:commit'));
  const baseText = basic(3, 'mosimo:base');
  const base = baseText === '' ? null : repositoryCid(CID.parse(baseText));
  const rkey = basic(4, 'mosimo:rkey');
  requireValue(tid.test(rkey), 'Invalid record key TID');
  const exp = basic(5, 'mosimo:exp');
  timestampSeconds(exp);
  let next = 6;
  let created;
  if (tags[6]?.[0] === 'mosimo:created') {
    created = basic(6, 'mosimo:created');
    requireValue(timestampSeconds(created) === event.created_at, 'created does not match created_at');
    next = 7;
  }
  const ext = [];
  while (tags[next]?.[0]?.startsWith(EXT_PREFIX)) {
    const tag = tags[next++];
    const type = tag[0].slice(EXT_PREFIX.length);
    requireValue(tag.length === 2 && type.length > 0, 'Invalid extension tag');
    const fields = decodeCbor(decodeBytes(tag[1]));
    requireValue(object(fields) && !Object.hasOwn(fields, '$type'), 'Extension value must be an object without $type');
    ext.push({ $type: type, ...fields });
  }
  for (const tag of tags.slice(next)) {
    requireValue(!tag[0].startsWith('mosimo:'), 'Basic/extension tags must precede other tags');
  }
  const record = { $type: COLLECTION, text: event.content, exp, ...(created ? { created } : {}), ext };
  const recordBytes = new Uint8Array(cbor.encode(record));
  const commit = decodeCbor(commitBytes);
  requireValue(object(commit)
    && Object.keys(commit).sort().join(',') === 'data,did,prev,rev,sig,version'
    && commit.version === 3 && /^did:plc:[a-z2-7]{24}$/.test(commit.did)
    && typeof commit.rev === 'string' && tid.test(commit.rev)
    && commit.sig instanceof Uint8Array && commit.sig.length === 64, 'Invalid signed v3 commit');
  repositoryCid(commit.data);
  if (commit.prev !== null) repositoryCid(commit.prev);
  return {
    commit, commitCid: await cidFor(commitBytes), commitBytes, base, path: `${COLLECTION}/${rkey}`, rkey,
    record, recordCid: await cidFor(recordBytes), recordBytes,
  };
}

/** Throws on an invalid commit signature; signingKeyDid is the resolved did:key. */
export async function verifyCommit(decoded, signingKeyDid) {
  const { sig, ...unsigned } = decoded.commit;
  requireValue(await verifySignature(signingKeyDid, cbor.encode(unsigned), sig), 'Invalid commit signature');
  return true;
}

/** Rebuild the complete MST, returning its blocks plus the unchanged commit and new record. */
export async function reconstruct(decoded, baseEntries = []) {
  requireValue(Array.isArray(baseEntries), 'baseEntries must be an array');
  requireValue(decoded.base !== null || baseEntries.length === 0, 'Initial commit cannot have base entries');
  // ponytail: rebuild O(n) state; retain MST nodes incrementally when repository size requires it.
  const storage = new MemoryBlockstore();
  let tree = await MST.create(storage);
  const seen = new Set();
  for (const entry of baseEntries) {
    requireValue(entry && typeof entry.key === 'string' && !seen.has(entry.key), 'Duplicate or invalid base path');
    seen.add(entry.key);
    tree = await tree.add(entry.key, repositoryCid(entry.value));
  }
  requireValue(!seen.has(decoded.path), 'Post path already exists');
  tree = await tree.add(decoded.path, decoded.recordCid);
  requireValue((await tree.getPointer()).equals(decoded.commit.data), 'Reconstructed MST root does not match commit');
  const { blocks } = await tree.getUnstoredBlocks();
  blocks.set(decoded.recordCid, decoded.recordBytes);
  blocks.set(decoded.commitCid, decoded.commitBytes);
  return {
    entries: (await tree.list()).map(({ key, value }) => ({ key, value })),
    blocks: [...blocks.entries()],
    car: await blocksToCarFile(decoded.commitCid, blocks),
  };
}
