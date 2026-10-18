import { createHash, randomBytes } from 'node:crypto';
import { secp256k1, schnorr } from '@noble/curves/secp256k1.js';
import { formatDidKey } from '@atproto/crypto';
import { MST, MemoryBlockstore } from '@atproto/repo';
import * as cbor from '@ipld/dag-cbor';
import { CID } from 'multiformats/cid';
import { base32 } from 'multiformats/bases/base32';
import { sha256 } from 'multiformats/hashes/sha2';
import { finalizeEvent } from 'nostr-tools';
import { decodePost } from './repository.mjs';

export const NS = 'mosimo.oriom.dev';
export const IDENTITY = 'dev.oriom.mosimo.defs#identity';
export const BSAF = 'dev.oriom.mosimo.defs#bsafV1';
export const PRIORITY = 'dev.oriom.mosimo.defs#priority';
export const JMA_SELECTION = Object.freeze([Object.freeze({ botDid: 'did:plc:vwi7zhfyamyvrg5v6ycjgzxi',
  types: Object.freeze(['ashfall', 'earthquake', 'eruption', 'nankai-trough', 'tsunami']) })]);
// Pinned from the public bot-definition; new upstream types require a separate review.
export const KIKIKURU_SELECTION = Object.freeze([Object.freeze({ botDid: 'did:plc:kxwz5cz7o6g4jlmxh6doyfsm',
  types: Object.freeze(['avalanche-warning', 'dense-fog-warning', 'dry-air-warning', 'flood-warning',
    'frost-warning', 'heavy-rain', 'heavy-rain-warning', 'icing-warning', 'landslide-warning',
    'linear-rainband-warning', 'low-temperature-warning', 'melting-snow-warning', 'other-warning',
    'significant-snowfall-warning', 'snow-accretion-warning', 'snow-warning', 'storm-surge-warning',
    'thunderstorm-warning', 'tornado-warning', 'typhoon', 'wave-warning', 'weather-info', 'wind-warning']) })]);
const DID = /^did:plc:[a-z2-7]{24}$/;
const hex = bytes => Buffer.from(bytes).toString('hex');
const unhex = value => Uint8Array.from(Buffer.from(value, 'hex'));
const hash = bytes => new Uint8Array(createHash('sha256').update(bytes).digest());
const b64 = bytes => Buffer.from(bytes).toString('base64').replace(/=+$/, '');
const fail = (ok, message) => { if (!ok) throw new Error(message); };
const exact = (value, fields) => fail(value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).sort().join(',') === fields.split(',').sort().join(','), 'Unexpected fields');
const isHex = (value, size) => typeof value === 'string' && new RegExp(`^[0-9a-f]{${size * 2}}$`).test(value);
const text = (value, max) => fail(typeof value === 'string' && Buffer.byteLength(value) >= 1
  && Buffer.byteLength(value) <= max && !/[\x00-\x1f\x7f\ud800-\udfff]/u.test(value), 'Invalid text');
const publicKey = value => {
  fail(isHex(value, 33) && /^(02|03)/.test(value), 'Invalid public key');
  secp256k1.Point.fromHex(value).assertValidity();
  return value;
};
const sorted = (values, max, check) => {
  fail(Array.isArray(values) && values.length <= max, 'Invalid list');
  values.forEach(check);
  fail(values.every((value, i) => i === 0 || Buffer.compare(Buffer.from(values[i - 1]), Buffer.from(value)) < 0), 'Unsorted/duplicate list');
};
export function canonical(value, { maxBytes = 32768, signed = true } = {}) {
  let items = 0;
  function walk(v, depth) {
    fail(++items <= 8192 && depth <= 16, 'CBOR resource limit');
    if (typeof v === 'number') fail(Number.isSafeInteger(v) && v >= 0 && !Object.is(v, -0), 'Invalid integer');
    else if (typeof v === 'string') fail(!/[\ud800-\udfff]/u.test(v), 'Invalid Unicode');
    else if (v === null || typeof v === 'boolean') return;
    else if (!signed && (v instanceof Uint8Array || CID.asCID(v))) return;
    else {
      fail(Array.isArray(v) || (v && Object.getPrototypeOf(v) === Object.prototype), 'Invalid CBOR value');
      for (const [k, child] of Object.entries(v)) {
        if (!Array.isArray(v)) walk(k, depth + 1);
        walk(child, depth + 1);
      }
    }
  }
  walk(value, 0);
  const bytes = cbor.encode(value);
  fail(bytes.length <= maxBytes, 'CBOR resource limit');
  return bytes;
}
export function decodeCanonical(bytes, options) {
  fail(bytes instanceof Uint8Array && bytes.length <= (options?.maxBytes ?? 32768), 'CBOR resource limit');
  // Bound nesting before the library recursively decodes attacker-controlled input.
  let offset = 0, items = 0;
  function scan(depth) {
    fail(depth <= 16 && ++items <= 8192 && offset < bytes.length, 'CBOR resource limit');
    const first = bytes[offset++], major = first >> 5, ai = first & 31;
    fail(ai < 28, 'Indefinite/invalid CBOR');
    let n = ai;
    if (ai >= 24) {
      const count = 2 ** (ai - 24);
      fail(offset + count <= bytes.length, 'Truncated CBOR');
      n = 0;
      for (let i = 0; i < count; i++) n = n * 256 + bytes[offset++];
      fail(Number.isSafeInteger(n), 'Invalid CBOR integer');
    }
    if (major === 2 || major === 3) { offset += n; fail(offset <= bytes.length, 'Truncated CBOR'); }
    else if (major === 4 || major === 5) {
      fail(n <= 8192, 'CBOR resource limit');
      for (let i = 0; i < n * (major === 5 ? 2 : 1); i++) scan(depth + 1);
    } else if (major === 6) { fail(options?.signed === false && n === 42, 'CBOR tag'); scan(depth + 1); }
    else fail(major === 0 || (major === 7 && [20, 21, 22].includes(ai)), 'Unsupported CBOR');
  }
  scan(0);
  fail(offset === bytes.length, 'Trailing CBOR');
  const value = cbor.decode(bytes);
  fail(Buffer.from(canonical(value, options)).equals(Buffer.from(bytes)), 'Noncanonical CBOR');
  return value;
}
export const bodyHash = body => hex(hash(canonical(body)));
export const rootId = key => hex(hash(unhex(publicKey(key))));
export const certId = certificate => bodyHash(certificate.body);
export const keyPublic = secret => hex(secp256k1.getPublicKey(unhex(secret), true));
const signHash = (digest, secret) => secp256k1.sign(digest, unhex(secret), { lowS: true, prehash: false }).toCompactHex();
function verifyHash(digest, sig, key) {
  fail(isHex(sig, 64), 'Invalid compact signature');
  publicKey(key);
  fail(secp256k1.verify(sig, digest, key, { lowS: true, prehash: false }), 'Invalid low-S signature');
}
const signBody = (body, secret) => ({ body, sig: signHash(hash(canonical(body)), secret) });
function time(value) {
  fail(typeof value === 'string' && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$/.test(value), 'Invalid second timestamp');
  const ms = Date.parse(value);
  fail(Number.isFinite(ms) && ms >= 0 && new Date(ms).toISOString().replace('.000Z', 'Z') === value, 'Invalid calendar date');
  return ms / 1000;
}
export const timestamp = seconds => {
  fail(Number.isSafeInteger(seconds) && seconds >= 0, 'Unknown clock');
  return new Date(seconds * 1000).toISOString().replace('.000Z', 'Z');
};
function clock(now) { fail(Number.isSafeInteger(now) && now >= 0 && now <= 253402300799, 'Unknown clock'); }
export function validateScope(scope) {
  exact(scope, 'orgIds,bsaf');
  sorted(scope.orgIds, 16, value => text(value, 128));
  fail(scope.orgIds.length > 0 && Array.isArray(scope.bsaf) && scope.bsaf.length > 0 && scope.bsaf.length <= 16, 'Empty scope');
  let pairs = 0;
  for (const entry of scope.bsaf) {
    exact(entry, 'botDid,types'); fail(DID.test(entry.botDid), 'Invalid Bot DID');
    sorted(entry.types, 32, value => text(value, 128));
    fail(entry.types.length > 0, 'Empty types'); pairs += entry.types.length;
  }
  sorted(scope.bsaf.map(entry => entry.botDid), 16, () => {});
  fail(pairs <= 64, 'Scope resource limit');
  return scope;
}
function includes(scope, botDid, type) { return Array.isArray(scope) && scope.some(e => e.botDid === botDid && e.types?.includes(type)); }
function subset(child, parent) {
  validateScope(child); validateScope(parent);
  fail(child.orgIds.every(id => parent.orgIds.includes(id)) && child.bsaf.every(e => e.types.every(t => includes(parent.bsaf, e.botDid, t))), 'Scope escalation');
}
export function createRoot({ scope, snapshotUrl, minSequence = 1 }) {
  validateScope(scope);
  const url = new URL(snapshotUrl);
  fail(url.protocol === 'https:' && !url.username && !url.password && !url.hash, 'HTTPS snapshot required');
  fail(Number.isSafeInteger(minSequence) && minSequence >= 1, 'Invalid minimum snapshot sequence');
  const secretKey = hex(secp256k1.utils.randomPrivateKey());
  return { secretKey, root: { rootPubkey: keyPublic(secretKey), enabled: true, scope, snapshotUrl, minSequence } };
}
export function issueCertificate(secretKey, fields) {
  const issuerPubkey = keyPublic(secretKey);
  const body = { type: 'mosimo.oriom.dev/identity-certificate', version: 1,
    serial: hex(randomBytes(16)), rootId: rootId(issuerPubkey), issuerPubkey,
    role: 'importer', permissions: ['bsaf-import'], canDelegate: false, ...fields };
  const certificate = signBody(body, secretKey);
  validateCertificate(certificate);
  return certificate;
}
function validateCertificate(certificate) {
  exact(certificate, 'body,sig'); canonical(certificate, { maxBytes: 4096 });
  const b = certificate.body;
  exact(b, 'type,version,serial,rootId,issuerPubkey,subjectPubkey,subjectDid,orgId,orgName,role,permissions,canDelegate,scope,notBefore,notAfter');
  fail(b.type === 'mosimo.oriom.dev/identity-certificate' && b.version === 1 && isHex(b.serial, 16) && isHex(b.rootId, 32), 'Invalid certificate');
  publicKey(b.issuerPubkey); publicKey(b.subjectPubkey); text(b.orgId, 128); text(b.orgName, 256);
  fail(JSON.stringify(b.permissions) === '["bsaf-import"]' && b.role === 'importer' && b.canDelegate === false && DID.test(b.subjectDid), 'Unsupported certificate role');
  validateScope(b.scope);
  fail(JSON.stringify(b.scope.orgIds) === JSON.stringify([b.orgId]), 'Invalid importer organization');
  fail(time(b.notBefore) < time(b.notAfter) && time(b.notAfter) - time(b.notBefore) <= 31622400, 'Invalid certificate period');
  verifyHash(hash(canonical(b)), certificate.sig, b.issuerPubkey);
}
export function verifyChain(chain, { roots, nowSeconds, eventSeconds = nowSeconds, selection, botDid, type }) {
  clock(nowSeconds); clock(eventSeconds);
  fail(Array.isArray(chain) && chain.length === 1, 'Unsupported chain: root-to-leaf only');
  const leaf = chain[0]; validateCertificate(leaf);
  const b = leaf.body;
  fail(Array.isArray(roots) && roots.length <= 64, 'Invalid roots');
  const matches = roots.filter(r => rootId(r.rootPubkey) === b.rootId);
  fail(matches.length === 1 && matches[0].enabled === true, 'Unknown/disabled root');
  const root = matches[0];
  fail(new URL(root.snapshotUrl).protocol === 'https:' && Number.isSafeInteger(root.minSequence) && root.minSequence >= 1, 'Invalid root settings');
  fail(b.issuerPubkey === root.rootPubkey && b.subjectPubkey !== root.rootPubkey, 'Invalid issuer linkage');
  subset(b.scope, root.scope);
  fail(time(b.notBefore) <= nowSeconds && nowSeconds < time(b.notAfter)
    && time(b.notBefore) <= eventSeconds && eventSeconds < time(b.notAfter) && eventSeconds <= nowSeconds, 'Certificate/event time');
  fail(includes(b.scope.bsaf, botDid, type) && includes(selection, botDid, type), 'Unselected Bot/type');
  return { root, leaf, rootId: b.rootId, leafCertId: certId(leaf) };
}
/** @param {string} secretKey
 * @param {{sequence:number, issuedAt:string, validUntil:string, revokedCertIds?:string[], revokedPubkeys?:string[]}} fields */
export function createSnapshot(secretKey, { sequence, issuedAt, validUntil, revokedCertIds = [], revokedPubkeys = [] }) {
  const snapshot = signBody({ type: 'mosimo.oriom.dev/trust-snapshot', version: 1,
    rootId: rootId(keyPublic(secretKey)), sequence, issuedAt, validUntil, revokedCertIds, revokedPubkeys }, secretKey);
  validateSnapshot(snapshot); return snapshot;
}
function validateSnapshot(snapshot) {
  exact(snapshot, 'body,sig'); canonical(snapshot);
  const b = snapshot.body;
  exact(b, 'type,version,rootId,sequence,issuedAt,validUntil,revokedCertIds,revokedPubkeys');
  fail(b.type === 'mosimo.oriom.dev/trust-snapshot' && b.version === 1 && isHex(b.rootId, 32)
    && Number.isSafeInteger(b.sequence) && b.sequence >= 1, 'Invalid snapshot');
  fail(time(b.validUntil) > time(b.issuedAt) && time(b.validUntil) - time(b.issuedAt) <= 3600, 'Invalid snapshot period');
  sorted(b.revokedCertIds, 256, id => fail(isHex(id, 32), 'Invalid cert ID'));
  sorted(b.revokedPubkeys, 256, publicKey);
  fail(b.revokedCertIds.length + b.revokedPubkeys.length <= 256, 'CRL limit');
}
/** store.get(rootId) -> signed snapshot; store.accept(rootId,snapshot) atomically
 * rejects rollback/equivocation/CRL removal AND issuer+serial body conflicts.
 * Serial persistence is through store.recordCertificate(issuer,serial,certId).
 * An issuer+serial conflict MUST permanently poison BOTH bodies, even after the
 * caller's event/publication transaction rolls back. Persist the equivocation
 * independently before returning rejection; future presentation of A or B fails.
 * All operations must finish durably before authorization. No in-memory fallback. */
export async function verifySnapshot(snapshot, { root, nowSeconds, previous = null }) {
  clock(nowSeconds); validateSnapshot(snapshot);
  const b = snapshot.body;
  fail(b.rootId === rootId(root.rootPubkey), 'Wrong snapshot root');
  verifyHash(hash(canonical(b)), snapshot.sig, root.rootPubkey);
  fail(b.sequence >= root.minSequence && time(b.issuedAt) <= nowSeconds && nowSeconds < time(b.validUntil), 'Stale/future snapshot');
  if (previous) {
    validateSnapshot(previous); verifyHash(hash(canonical(previous.body)), previous.sig, root.rootPubkey);
    const p = previous.body;
    fail(p.rootId === b.rootId && b.sequence >= p.sequence, 'Snapshot rollback');
    if (b.sequence === p.sequence) fail(bodyHash(b) === bodyHash(p), 'Snapshot equivocation');
    else fail(time(b.issuedAt) >= time(p.issuedAt) && p.revokedCertIds.every(id => b.revokedCertIds.includes(id))
      && p.revokedPubkeys.every(key => b.revokedPubkeys.includes(key)), 'Snapshot CRL removal/time rollback');
  }
  return snapshot;
}
export function createBinding({ did, atprotoSecret, nostrSecret }) {
  const declaration = { type: 'mndt.keyBinding', version: 1, did,
    atprotoPubkey: keyPublic(atprotoSecret), nostrPubkey: hex(schnorr.getPublicKey(unhex(nostrSecret))),
    consent: "Use this key pair for this DID's MNDT publications" };
  const digest = hash(canonical(declaration));
  return { declaration, atprotoSig: Buffer.from(signHash(digest, atprotoSecret), 'hex').toString('base64'),
    nostrSig: hex(schnorr.sign(digest, unhex(nostrSecret))) };
}
export function verifyBinding(binding, { did, atprotoPubkey, nostrPubkey }) {
  exact(binding, 'declaration,atprotoSig,nostrSig');
  const d = binding.declaration;
  exact(d, 'type,version,did,atprotoPubkey,nostrPubkey,consent');
  fail(d.type === 'mndt.keyBinding' && d.version === 1 && DID.test(d.did) && d.did === did
    && d.atprotoPubkey === atprotoPubkey && d.nostrPubkey === nostrPubkey
    && atprotoPubkey.slice(2) !== nostrPubkey
    && d.consent === "Use this key pair for this DID's MNDT publications", 'Binding mismatch');
  fail(typeof binding.atprotoSig === 'string' && /^[A-Za-z0-9+/]{86}==$/.test(binding.atprotoSig)
    && Buffer.from(binding.atprotoSig, 'base64').toString('base64') === binding.atprotoSig, 'Binding Base64');
  const digest = hash(canonical(d));
  verifyHash(digest, hex(Buffer.from(binding.atprotoSig, 'base64')), atprotoPubkey);
  fail(isHex(nostrPubkey, 32) && isHex(binding.nostrSig, 64)
    && schnorr.verify(binding.nostrSig, digest, nostrPubkey), 'Invalid Nostr binding signature');
  return true;
}
export function verifyPublicCommit(decoded, key) {
  const { sig, ...unsigned } = decoded.commit;
  verifyHash(hash(cbor.encode(unsigned)), hex(sig), key);
  return true;
}
function originalTime(value) {
  const m = typeof value === 'string' && /^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d):(\d\d)(?:\.\d+)?(?:Z|[+-](\d\d):(\d\d))$/.exec(value);
  fail(m, 'Invalid original timestamp');
  const [year, month, day, hour, minute, second] = m.slice(1, 7).map(Number);
  const date = new Date(0); date.setUTCFullYear(year, month - 1, day);
  fail(year >= 1 && month >= 1 && month <= 12 && day >= 1 && date.getUTCMonth() === month - 1 && date.getUTCDate() === day
    && hour <= 23 && minute <= 59 && second <= 59 && (!m[7] || Number(m[7]) <= 23) && (!m[8] || Number(m[8]) <= 59)
    && Number.isFinite(Date.parse(value)), 'Invalid original calendar date');
}
function languageTag(value) {
  if (typeof value !== 'string' || value.length < 1 || value.length > 64) return false;
  const grandfathered = ['en-gb-oed','i-ami','i-bnn','i-default','i-enochian','i-hak','i-klingon','i-lux','i-mingo','i-navajo','i-pwn','i-tao','i-tay','i-tsu','sgn-be-fr','sgn-be-nl','sgn-ch-de','art-lojban','cel-gaulish','no-bok','no-nyn','zh-guoyu','zh-hakka','zh-min','zh-min-nan','zh-xiang'];
  return grandfathered.includes(value.toLowerCase()) || /^(?:x(?:-[a-z0-9]{1,8})+|(?:[a-z]{2,3}(?:-[a-z]{3}){0,3}|[a-z]{4}|[a-z]{5,8})(?:-[a-z]{4})?(?:-(?:[a-z]{2}|[0-9]{3}))?(?:-(?:[a-z0-9]{5,8}|[0-9][a-z0-9]{3}))*(?:-[0-9a-wy-z](?:-[a-z0-9]{2,8})+)*(?:-x(?:-[a-z0-9]{1,8})+)?)$/i.test(value);
}
export function verifyBsaf(extension) {
  exact(extension, Object.hasOwn(extension, 'botDefinition') ? '$type,tags,langs,original,botDefinition' : '$type,tags,langs,original');
  if (Object.hasOwn(extension, 'botDefinition')) {
    const b = extension.botDefinition;
    fail(b && typeof b === 'object' && Object.keys(b).every(k => ['url', 'retrievedAt', 'sha256'].includes(k)), 'Invalid bot definition');
    text(b.url, 2048); fail(['https:', 'http:'].includes(new URL(b.url).protocol), 'Invalid bot definition URL');
    if (Object.hasOwn(b, 'retrievedAt')) originalTime(b.retrievedAt);
    if (Object.hasOwn(b, 'sha256')) fail(b.sha256 instanceof Uint8Array && b.sha256.length === 32, 'Invalid bot definition hash');
  }
  fail(extension.$type === BSAF && Array.isArray(extension.tags) && extension.tags.length <= 8
    && extension.tags.every(t => typeof t === 'string' && Buffer.byteLength(t) <= 640), 'Invalid BSAF tags');
  fail(Array.isArray(extension.langs) && extension.langs.length <= 3
    && extension.langs.every(languageTag), 'Invalid languages');
  const values = {};
  for (const name of ['bsaf', 'type', 'value', 'time', 'target', 'source']) {
    const tags = extension.tags.filter(t => t.startsWith(`${name}:`));
    fail(tags.length === 1 && tags[0].length > name.length + 1, 'Missing/ambiguous BSAF core');
    values[name] = tags[0].slice(name.length + 1);
  }
  fail(values.bsaf === 'v1', 'Unsupported BSAF version'); text(values.type, 128);
  exact(extension.original, 'uri,cid,createdAt');
  const o = extension.original;
  const match = /^at:\/\/(did:plc:[a-z2-7]{24})\/app\.bsky\.feed\.post\/([A-Za-z0-9._~:-]{1,512})$/.exec(o.uri);
  fail(match && !['.', '..'].includes(match[2]), 'Invalid original URI');
  const cid = CID.parse(o.cid);
  fail(cid.version === 1 && cid.code === cbor.code && cid.multihash.code === sha256.code && cid.multihash.size === 32 && cid.toString() === o.cid, 'Invalid original CID');
  originalTime(o.createdAt);
  return { botDid: match[1], type: values.type };
}
export function priorityHint(event) {
  try {
    const tags = event.tags.filter(t => t[0] === `${NS}:ext:${PRIORITY}`);
    if (tags.length !== 1 || tags[0].length !== 2) return 1;
    const value = decodeExtension(tags[0][1]); exact(value, 'version,level');
    return value.version === 1 && Number.isInteger(value.level) && value.level >= 0 && value.level <= 3 ? value.level : 1;
  } catch { return 1; }
}
export const effectivePriority = (event, authorized = false) => authorized ? priorityHint(event) : Math.min(priorityHint(event), 1);
function decodeExtension(value) {
  fail(typeof value === 'string' && /^[A-Za-z0-9+/]*$/.test(value), 'Invalid Base64');
  const bytes = Buffer.from(value, 'base64'); fail(b64(bytes) === value, 'Noncanonical Base64');
  return decodeCanonical(bytes, { signed: false });
}
/**
 * @param {object} event Signed NIP-01 event (never mutated).
 * @param {{roots: any[], snapshots: {get: Function, accept: Function, recordCertificate: Function}, nowSeconds: number, selection?: ReadonlyArray<{botDid: string, types: ReadonlyArray<string>}>}} options
 */
export async function verifyPublicInformation(event, { roots, snapshots, nowSeconds, selection = [] }) {
  clock(nowSeconds);
  fail(Buffer.byteLength(JSON.stringify(event)) <= 262144 && typeof event.content === 'string'
    && !/[\ud800-\udfff]/u.test(event.content) && Array.isArray(event.tags)
    && event.tags.every(t => Array.isArray(t) && t.every(v => typeof v === 'string' && !/[\ud800-\udfff]/u.test(v)))
    && event.tags.length <= 64 && event.tags.filter(t => t[0]?.startsWith(`${NS}:ext:`)).length <= 16, 'Event resource limit');
  for (const tag of event.tags) {
    if (tag[0]?.startsWith(`${NS}:ext:`)) {
      const fields = decodeExtension(tag[1]);
      fail(!Object.hasOwn(fields, '$type'), 'Extension must omit $type');
    }
    if (tag[0] === `${NS}:commit`) {
      fail(typeof tag[1] === 'string', 'Invalid commit');
      decodeCanonical(Buffer.from(tag[1], 'base64'), { signed: false });
    }
  }
  const decoded = await decodePost(event);
  // Seven-element mesh payload with binary tags, as specified by 0012.
  const meshTags = event.tags.map(t => t[0] === `${NS}:commit` || t[0].startsWith(`${NS}:ext:`)
    ? [t[0], Buffer.from(t[1], 'base64')] : t[0] === `${NS}:version` && t[1] === '1' ? [t[0], 1] : t);
  fail(cbor.encode([1, unhex(event.pubkey), event.created_at, event.kind, meshTags, event.content, unhex(event.sig)]).length <= 32768, 'Mesh payload limit');
  fail(event.created_at <= nowSeconds && Date.parse(decoded.record.deliveryExpiresAt) > nowSeconds * 1000, 'Event expired/future');
  const single = type => {
    const matches = decoded.record.extensions.filter(e => e.$type === type);
    fail(matches.length === 1, `Expected one ${type}`); return matches[0];
  };
  const identity = single(IDENTITY); exact(identity, '$type,version,chain,binding');
  fail(identity.version === 1, 'Identity version'); canonical(identity, { maxBytes: 20480 });
  const { botDid, type } = verifyBsaf(single(BSAF));
  const chain = verifyChain(identity.chain, { roots, nowSeconds, eventSeconds: event.created_at, selection, botDid, type });
  verifyBinding(identity.binding, { did: chain.leaf.body.subjectDid, atprotoPubkey: chain.leaf.body.subjectPubkey, nostrPubkey: event.pubkey });
  fail(decoded.commit.did === chain.leaf.body.subjectDid, 'Commit DID mismatch');
  verifyPublicCommit(decoded, chain.leaf.body.subjectPubkey);
  fail(snapshots && typeof snapshots.get === 'function' && typeof snapshots.accept === 'function'
    && typeof snapshots.recordCertificate === 'function', 'Durable snapshot store required');
  const snapshot = await snapshots.get(chain.rootId);
  await verifySnapshot(snapshot, { root: chain.root, nowSeconds });
  // accept MUST compare with stored high-water state within its write transaction.
  await snapshots.accept(chain.rootId, snapshot);
  await snapshots.recordCertificate(chain.leaf.body.issuerPubkey, chain.leaf.body.serial, chain.leafCertId);
  fail(!snapshot.body.revokedPubkeys.includes(chain.root.rootPubkey)
    && !snapshot.body.revokedCertIds.includes(chain.leafCertId)
    && !snapshot.body.revokedPubkeys.includes(chain.leaf.body.subjectPubkey), 'Root/certificate revoked');
  return { authorized: true, priority: priorityHint(event), effectivePriority: effectivePriority(event, true),
    rootId: chain.rootId, leafCertId: chain.leafCertId, botDid, type, snapshot: { sequence: snapshot.body.sequence,
      issuedAt: snapshot.body.issuedAt, validUntil: snapshot.body.validUntil }, checkedAt: nowSeconds,
    checks: { authorSignature: true, binding: true, chainScope: true, certificateTime: true, revocation: 'fresh-as-of' } };
}

export function createImporter({ endpoint = 'https://pds.invalid' } = {}) {
  fail(new URL(endpoint).protocol === 'https:', 'HTTPS PDS endpoint required');
  const atprotoSecret = hex(secp256k1.utils.randomPrivateKey());
  const nostrSecret = hex(secp256k1.utils.randomPrivateKey());
  const plcSecret = hex(secp256k1.utils.randomPrivateKey());
  const unsigned = { type: 'plc_operation', rotationKeys: [formatDidKey('ES256K', unhex(keyPublic(plcSecret)))],
    verificationMethods: { atproto: formatDidKey('ES256K', unhex(keyPublic(atprotoSecret))) }, alsoKnownAs: [],
    services: { atproto_pds: { type: 'AtprotoPersonalDataServer', endpoint } }, prev: null };
  const genesis = { ...unsigned, sig: Buffer.from(signHash(hash(cbor.encode(unsigned)), plcSecret), 'hex').toString('base64url') };
  const did = `did:plc:${base32.encode(hash(cbor.encode(genesis))).slice(1, 25)}`;
  return { did, atprotoSecret, nostrSecret, plcSecret, genesis,
    binding: createBinding({ did, atprotoSecret, nostrSecret }) };
}
export function verifyImporterGenesis(request) {
  const { genesis, binding } = request;
  exact(genesis, 'type,rotationKeys,verificationMethods,alsoKnownAs,services,prev,sig');
  fail(genesis.type === 'plc_operation' && genesis.prev === null && Array.isArray(genesis.rotationKeys)
    && genesis.rotationKeys.length === 1 && Array.isArray(genesis.alsoKnownAs) && genesis.alsoKnownAs.length === 0, 'Unsupported genesis');
  const plcKey = request.plcPubkey;
  fail(plcKey !== binding.declaration.atprotoPubkey && plcKey?.slice(2) !== binding.declaration.nostrPubkey, 'PLC key reuse');
  fail(genesis.rotationKeys[0] === formatDidKey('ES256K', unhex(publicKey(plcKey)))
    && genesis.verificationMethods.atproto === formatDidKey('ES256K', unhex(publicKey(binding.declaration.atprotoPubkey))), 'Genesis key mismatch');
  const { sig, ...unsigned } = genesis;
  fail(typeof sig === 'string' && Buffer.from(sig, 'base64url').toString('base64url') === sig, 'Genesis signature encoding');
  verifyHash(hash(cbor.encode(unsigned)), hex(Buffer.from(sig, 'base64url')), plcKey);
  const did = `did:plc:${base32.encode(hash(cbor.encode(genesis))).slice(1, 25)}`;
  verifyBinding(binding, { did, atprotoPubkey: binding.declaration.atprotoPubkey, nostrPubkey: binding.declaration.nostrPubkey });
  return { did, atprotoPubkey: binding.declaration.atprotoPubkey, status: 'unregistered-genesis-only' };
}
export async function createImporterEvent({ importer, chain, text, extensions, nowSeconds, deliveryExpiresAt, state = { entries: [], base: null, lastMicros: 0 }, priority = 1 }) {
  clock(nowSeconds); fail(state.entries.length < 10000, 'Repository capacity reached');
  fail((state.base === null) === (state.entries.length === 0), 'Inconsistent repository state');
  const micros = Math.max(nowSeconds * 1000000, state.lastMicros + 1);
  const alphabet = '234567abcdefghijklmnopqrstuvwxyz';
  let value = BigInt(micros) << 10n, tid = '';
  for (let i = 0; i < 13; i++) { tid = alphabet[Number(value & 31n)] + tid; value >>= 5n; }
  fail(value === 0n, 'TID overflow');
  const path = `dev.oriom.mosimo.post/${tid}`;
  const allExtensions = [...extensions, { $type: IDENTITY, version: 1, chain, binding: importer.binding }, { $type: PRIORITY, version: 1, level: priority }];
  const record = { $type: 'dev.oriom.mosimo.post', text, createdAt: timestamp(nowSeconds), deliveryExpiresAt, extensions: allExtensions };
  const recordCid = CID.createV1(cbor.code, await sha256.digest(cbor.encode(record)));
  let tree = await MST.create(new MemoryBlockstore());
  // ponytail: bounded 10k-entry rebuild matches existing PDS reconstruction; stop at capacity, never discard history.
  for (const e of state.entries) tree = await tree.add(e.key, CID.parse(e.cid));
  tree = await tree.add(path, recordCid);
  const unsigned = { did: importer.did, version: 3, data: await tree.getPointer(), rev: tid, prev: null };
  const commit = { ...unsigned, sig: unhex(signHash(hash(cbor.encode(unsigned)), importer.atprotoSecret)) };
  const bytes = cbor.encode(commit);
  const event = finalizeEvent({ kind: 1, content: text, created_at: nowSeconds, tags: [
    ['t', NS], [`${NS}:version`, '1'], [`${NS}:commit`, b64(bytes)], [`${NS}:base`, state.base ?? ''],
    [`${NS}:path`, path], [`${NS}:created-at`, record.createdAt], [`${NS}:delivery-expires-at`, deliveryExpiresAt],
    ...allExtensions.map(({ $type, ...fields }) => [`${NS}:ext:${$type}`, b64(cbor.encode(fields))]),
  ] }, unhex(importer.nostrSecret));
  return { event, record, state: { entries: [...state.entries, { key: path, cid: recordCid.toString() }],
    base: CID.createV1(cbor.code, await sha256.digest(bytes)).toString(), lastMicros: micros } };
}
