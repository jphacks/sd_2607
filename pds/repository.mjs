import { MST, MemoryBlockstore, blocksToCarFile } from '@atproto/repo';
import { verifySignature } from '@atproto/crypto';
import * as cbor from '@ipld/dag-cbor';
import { CID } from 'multiformats/cid';
import { sha256 } from 'multiformats/hashes/sha2';
import { verifyEvent } from 'nostr-tools';

const namespace = 'mosimo.oriom.dev';
const prefix = `${namespace}:`;
const collection = 'dev.oriom.mosimo.post';
const tid = /^[234567abcdefghij][234567abcdefghijklmnopqrstuvwxyz]{12}$/;

function requireValue(condition, message) {
  if (!condition) throw new Error(message);
}

function object(value) {
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

function decodeBytes(value) {
  requireValue(typeof value === 'string' && /^[A-Za-z0-9+/]*$/.test(value), 'Invalid unpadded Base64');
  const bytes = new Uint8Array(Buffer.from(value, 'base64'));
  requireValue(Buffer.from(bytes).toString('base64').replace(/=+$/, '') === value,
    'Noncanonical Base64');
  return bytes;
}

function decodeCbor(bytes) {
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

function timestamp(value) {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?Z$/.exec(value);
  requireValue(match, 'Invalid UTC timestamp');
  const [year, month, day, hour, minute, second] = match.slice(1).map(Number);
  requireValue(year >= 1 && month >= 1 && month <= 12 && day >= 1 && day <= 31
    && hour <= 23 && minute <= 59 && second <= 59, 'Invalid UTC calendar date');
  // setUTCFullYear avoids Date.UTC's special handling of years 00 through 99.
  const date = new Date(0);
  date.setUTCFullYear(year, month - 1, day);
  date.setUTCHours(hour, minute, second, 0);
  requireValue(date.getUTCFullYear() === year && date.getUTCMonth() === month - 1
    && date.getUTCDate() === day, 'Invalid UTC calendar date');
  return date.getTime() / 1000;
}

/** Routing, not authorization: malformed claimed BSAF imports also fail closed for atproto.
 * A valid ordinary BSAF extension without `original` remains a user post.
 * Keep this below the shared canonical decoder; never depend on PLC/root freshness.
 */
export function isBsafImport(event) {
  const tags = event.tags.filter(tag => tag[0] === `${prefix}ext:dev.oriom.mosimo.defs#bsafV1`);
  if (tags.length === 0) return false;
  if (tags.length !== 1) return true;
  try {
    const tag = tags[0];
    requireValue(tag.length === 2, 'Invalid BSAF extension tag');
    const fields = decodeCbor(decodeBytes(tag[1]));
    requireValue(object(fields) && (!Object.hasOwn(fields, '$type')
      || fields.$type === 'dev.oriom.mosimo.defs#bsafV1'), 'Invalid BSAF fields');
    return Object.hasOwn(fields, 'original');
  } catch { return true; }
}

/** Validate the signed Nostr envelope and recover the original record and commit bytes. */
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

  function single(name, predicate = tag => tag[0] === name) {
    const tags = event.tags.filter(predicate);
    requireValue(tags.length === 1 && tags[0].length === 2, `Expected one two-element ${name} tag`);
    return tags[0][1];
  }
  single('t', tag => tag[0] === 't' && tag[1] === namespace);
  requireValue(single(`${prefix}version`) === '1', 'Unsupported basic version');
  const commitBytes = decodeBytes(single(`${prefix}commit`));
  const baseText = single(`${prefix}base`);
  const base = baseText === '' ? null : repositoryCid(CID.parse(baseText));
  const path = single(`${prefix}path`);
  requireValue(path.startsWith(`${collection}/`) && tid.test(path.slice(collection.length + 1)),
    'Invalid post TID path');
  const createdAt = single(`${prefix}created-at`);
  const deliveryExpiresAt = single(`${prefix}delivery-expires-at`);
  requireValue(timestamp(createdAt) === event.created_at, 'created_at does not match createdAt');
  timestamp(deliveryExpiresAt);

  const extensions = [];
  for (const tag of event.tags) {
    if (!tag[0]?.startsWith(`${prefix}ext:`)) continue;
    const type = tag[0].slice(`${prefix}ext:`.length);
    requireValue(tag.length === 2 && type.length > 0, 'Invalid extension tag');
    const fields = decodeCbor(decodeBytes(tag[1]));
    requireValue(object(fields), 'Extension fields must be an object');
    requireValue(!Object.hasOwn(fields, '$type') || fields.$type === type, 'Conflicting extension $type');
    extensions.push({ ...fields, $type: type });
  }
  const record = { $type: collection, text: event.content, createdAt, deliveryExpiresAt, extensions };
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
    commit, commitCid: await cidFor(commitBytes), commitBytes, base, path,
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
