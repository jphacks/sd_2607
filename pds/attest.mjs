// dev.jisaku.mosimo.attest: key attestations, signing chains, revocation notices and the
// importer authorization that receivers (this PDS, the BSAF publisher) apply to Nostr posts.
//
// Everything signed here is atproto deterministic CBOR. Keys are raw bytes: 32 bytes is a
// BIP-340 x-only Nostr key (Schnorr), 33 bytes is a compressed secp256k1 atproto/PLC key
// (low-S compact ECDSA). Objects are kept in CBOR-native form (Uint8Array fields); JSON files
// and the database carry them as unpadded Base64 of the canonical CBOR, never as re-shaped JSON.
import { createHash } from 'node:crypto';
import { secp256k1, schnorr } from '@noble/curves/secp256k1.js';
import { formatDidKey } from '@atproto/crypto';
import { MST, MemoryBlockstore } from '@atproto/repo';
import * as cbor from '@ipld/dag-cbor';
import { CID } from 'multiformats/cid';
import { base32 } from 'multiformats/bases/base32';
import { sha256 } from 'multiformats/hashes/sha2';
import { finalizeEvent } from 'nostr-tools';
import { TAG, COLLECTION, decodePost, encodeBytes, isBsafImport } from './repository.mjs';
export { encodeBytes };

export const ATTEST = 'dev.jisaku.mosimo.attest';
export const KEY_ATTESTATION = `${ATTEST}#keyAttestation`;
export const CHAIN = `${ATTEST}.chain`;
export const BINDING_DECLARATION = `${CHAIN}#bindingDeclaration`;
export const REVOCATION = `${ATTEST}.revocation`;
export const NOTICE = `${REVOCATION}#notice`;
export const ACTIVATION = `${REVOCATION}#activation`;
export const REVOCATION_LIST = `${REVOCATION}.list`;
export const IMPORTER = `${ATTEST}.importer`;
export const BSAF = 'dev.jisaku.mosimo.public.bsaf';
export const PRIORITY = 'dev.jisaku.mosimo.priority';
export const CONTROL_KIND = 8173;
export const REVOCATION_TAG = `${TAG}/revocation`;
export const CONSENT = "Use this key pair for this DID's Mosimo publications";
export const BLOCK = Object.freeze({
  keyValidity: `${ATTEST}#keyValidity`, delegation: `${ATTEST}#delegation`, organization: `${ATTEST}#organization`,
  issuerDomain: `${ATTEST}#issuerDomain`, subjectDomain: `${ATTEST}#subjectDomain`, trustLevel: `${ATTEST}#trustLevel`,
  importer: IMPORTER, subkey: `${REVOCATION}.subkey`,
});
export const JMA_SELECTION = Object.freeze([Object.freeze({ botDid: 'did:plc:vwi7zhfyamyvrg5v6ycjgzxi',
  types: Object.freeze(['ashfall', 'earthquake', 'eruption', 'nankai-trough', 'tsunami']) })]);
// Pinned from the public bot definition (updated_at 2026-08-01); new upstream types need review.
export const KIKIKURU_SELECTION = Object.freeze([Object.freeze({ botDid: 'did:plc:kxwz5cz7o6g4jlmxh6doyfsm',
  types: Object.freeze(['avalanche-warning', 'dense-fog-warning', 'dry-air-warning', 'flood-warning',
    'frost-warning', 'heavy-rain', 'heavy-rain-warning', 'icing-warning', 'landslide-warning',
    'linear-rainband-warning', 'low-temperature-warning', 'melting-snow-warning', 'other-warning',
    'significant-snowfall-warning', 'snow-accretion-warning', 'snow-warning', 'storm-surge-warning',
    'thunderstorm-warning', 'tornado-warning', 'typhoon', 'wave-warning', 'weather-info', 'wind-warning']) })]);

const DID = /^did:plc:[a-z2-7]{24}$/;
const DOMAIN = /^(?=.{1,253}$)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$/;
export const hex = bytes => Buffer.from(bytes).toString('hex');
export const unhex = value => { fail(typeof value === 'string' && /^([0-9a-f]{2})+$/.test(value), 'Invalid hex'); return Uint8Array.from(Buffer.from(value, 'hex')); };
export const b64 = bytes => Buffer.from(bytes).toString('base64').replace(/=+$/, '');
export function unb64(value) {
  fail(typeof value === 'string' && /^[A-Za-z0-9+/]*$/.test(value), 'Invalid Base64');
  const bytes = Uint8Array.from(Buffer.from(value, 'base64'));
  fail(b64(bytes) === value, 'Noncanonical Base64');
  return bytes;
}
const sha = bytes => new Uint8Array(createHash('sha256').update(bytes).digest());
const fail = (ok, message) => { if (!ok) throw new Error(message); };
const equal = (a, b) => a instanceof Uint8Array && b instanceof Uint8Array && a.length === b.length && Buffer.compare(Buffer.from(a), Buffer.from(b)) === 0;
const plain = value => value !== null && typeof value === 'object' && Object.getPrototypeOf(value) === Object.prototype;
const keys = (value, allowed, required = allowed) => {
  fail(plain(value), 'Expected an object');
  const present = Object.keys(value);
  fail(present.every(key => allowed.includes(key)) && required.every(key => present.includes(key)), `Unexpected fields: ${present.join(',')}`);
};
const text = (value, max = 256) => fail(typeof value === 'string' && Buffer.byteLength(value) >= 1 && Buffer.byteLength(value) <= max
  && !/[\x00-\x1f\x7f\ud800-\udfff]/u.test(value), 'Invalid text');
const utf8Less = (a, b) => Buffer.compare(Buffer.from(a), Buffer.from(b)) < 0;
const sortedUnique = (values, max, check) => {
  fail(Array.isArray(values) && values.length <= max, 'List too long');
  values.forEach(check);
  fail(values.every((value, i) => i === 0 || utf8Less(values[i - 1], value)), 'List must be sorted by UTF-8 bytes without duplicates');
};
export const sortUtf8 = values => [...values].sort((a, b) => utf8Less(a, b) ? -1 : utf8Less(b, a) ? 1 : 0);

// ---- canonical CBOR -------------------------------------------------------------------
export function canonical(value, { maxBytes = 32768, allowCid = false } = {}) {
  let items = 0;
  function walk(v, depth) {
    fail(++items <= 8192 && depth <= 16, 'CBOR resource limit');
    if (typeof v === 'number') fail(Number.isSafeInteger(v) && !Object.is(v, -0), 'Invalid integer');
    else if (typeof v === 'string') fail(!/[\ud800-\udfff]/u.test(v), 'Invalid Unicode');
    else if (v === null || typeof v === 'boolean' || v instanceof Uint8Array) return;
    else if (CID.asCID(v)) fail(allowCid, 'CID link not allowed here');
    else {
      fail(Array.isArray(v) || plain(v), 'Invalid CBOR value');
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
export function decodeCanonical(bytes, options = {}) {
  const maxBytes = options.maxBytes ?? 32768;
  fail(bytes instanceof Uint8Array && bytes.length <= maxBytes, 'CBOR resource limit');
  // Bound nesting and item count before the library recursively decodes attacker input.
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
    } else if (major === 6) { fail(options.allowCid && n === 42, 'CBOR tag'); scan(depth + 1); }
    else fail(major === 0 || major === 1 || (major === 7 && [20, 21, 22].includes(ai)), 'Unsupported CBOR');
  }
  scan(0);
  fail(offset === bytes.length, 'Trailing CBOR');
  const value = cbor.decode(bytes);
  fail(Buffer.from(canonical(value, options)).equals(Buffer.from(bytes)), 'Noncanonical CBOR');
  return value;
}
export const bodyHash = body => sha(canonical(body));
export const bodyId = body => hex(bodyHash(body));

// ---- keys, signatures, time -------------------------------------------------------------
export function publicKey(key) {
  fail(key instanceof Uint8Array && (key.length === 32 || key.length === 33), 'Public key must be 32 or 33 bytes');
  if (key.length === 32) schnorr.utils.lift_x(BigInt(`0x${hex(key)}`));
  else { fail(key[0] === 2 || key[0] === 3, 'Compressed SEC1 key required'); secp256k1.Point.fromHex(hex(key)).assertValidity(); }
  return key;
}
export const randomSecret = () => secp256k1.utils.randomSecretKey();
export const nostrPublicKey = secret => schnorr.getPublicKey(secret);
export const atprotoPublicKey = secret => secp256k1.getPublicKey(secret, true);
/** Signs a 32-byte digest with the key whose public form has the given length. */
export function signDigest(digest, secret, keyLength) {
  fail(digest instanceof Uint8Array && digest.length === 32, 'Digest required');
  if (keyLength === 32) return schnorr.sign(digest, secret);
  fail(keyLength === 33, 'Unsupported key length');
  return secp256k1.sign(digest, secret, { lowS: true, prehash: false }).toBytes('compact');
}
export function verifyDigest(digest, sig, key) {
  publicKey(key);
  fail(sig instanceof Uint8Array && sig.length === 64, 'Signature must be 64 bytes');
  if (key.length === 32) return schnorr.verify(sig, digest, key);
  return secp256k1.verify(sig, digest, key, { lowS: true, prehash: false });
}
export const sign = (body, secret, keyLength) => ({ body, sig: signDigest(bodyHash(body), secret, keyLength) });
const checkSigned = (signed, key) => fail(verifyDigest(bodyHash(signed.body), signed.sig, key), 'Invalid signature');

const TIME = /^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d):(\d\d)(\.\d+)?Z$/;
/** Timestamp text -> seconds (fractional allowed). Rejects offsets and impossible dates. */
export function parseTime(value) {
  const m = typeof value === 'string' && TIME.exec(value);
  fail(m, 'Invalid Timestamp');
  const [year, month, day, hour, minute, second] = m.slice(1, 7).map(Number);
  const date = new Date(0); date.setUTCFullYear(year, month - 1, day); date.setUTCHours(hour, minute, second, 0);
  fail(year >= 1 && date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day
    && hour <= 23 && minute <= 59 && second <= 59, 'Invalid calendar date');
  return date.getTime() / 1000 + (m[7] ? Number(m[7]) : 0);
}
export const timestamp = seconds => {
  fail(Number.isSafeInteger(seconds) && seconds >= 0 && seconds <= 253402300799, 'Unknown clock');
  return new Date(seconds * 1000).toISOString().replace('.000Z', 'Z');
};
const clock = now => fail(Number.isSafeInteger(now) && now >= 0 && now <= 253402300799, 'Unknown clock');

// ---- key attestations ---------------------------------------------------------------------
export function validateImporterBlock(block) {
  keys(block, ['$type', 'sources']);
  fail(Array.isArray(block.sources) && block.sources.length >= 1 && block.sources.length <= 16, 'Invalid importer sources');
  let pairs = 0;
  for (const source of block.sources) {
    keys(source, ['botDid', 'types']);
    fail(DID.test(source.botDid), 'Invalid Bot DID');
    sortedUnique(source.types, 32, type => text(type, 128));
    fail(source.types.length >= 1, 'Empty types'); pairs += source.types.length;
  }
  sortedUnique(block.sources.map(source => source.botDid), 16, () => {});
  fail(pairs <= 64, 'Importer scope limit');
  return block;
}
export const includesPair = (sources, botDid, type) => Array.isArray(sources) && sources.some(s => s.botDid === botDid && s.types?.includes(type));
function validateBlock(block) {
  fail(plain(block) && typeof block.$type === 'string' && block.$type.length > 0 && block.$type.length <= 256, 'Invalid block');
  switch (block.$type) {
    case BLOCK.keyValidity: {
      keys(block, ['$type', 'from', 'until'], ['$type']);
      if (block.from !== undefined) parseTime(block.from);
      if (block.until !== undefined) parseTime(block.until);
      if (block.from !== undefined && block.until !== undefined) fail(parseTime(block.from) < parseTime(block.until), 'Empty validity');
      break;
    }
    case BLOCK.delegation: keys(block, ['$type', 'maxDepth']); fail(Number.isSafeInteger(block.maxDepth) && block.maxDepth >= 0 && block.maxDepth <= 16, 'Invalid maxDepth'); break;
    case BLOCK.organization: keys(block, ['$type', 'orgId', 'orgName']); text(block.orgId); text(block.orgName);
      fail(/^[^:\s]+:[^\s].*$/u.test(block.orgId), 'orgId must be <prefix>:<identifier>'); break;
    case BLOCK.issuerDomain: keys(block, ['$type', 'domain']); fail(DOMAIN.test(block.domain), 'Invalid issuer domain'); break;
    case BLOCK.subjectDomain: keys(block, ['$type', 'domain', 'method']); fail(DOMAIN.test(block.domain), 'Invalid subject domain'); text(block.method, 64); break;
    case BLOCK.trustLevel: keys(block, ['$type', 'level', 'method'], ['$type', 'level']);
      fail(Number.isSafeInteger(block.level) && block.level >= 0 && block.level <= 3, 'Invalid trust level');
      if (block.method !== undefined) text(block.method, 64); break;
    case BLOCK.importer: validateImporterBlock(block); break;
    case BLOCK.subkey: keys(block, ['$type', 'key']); publicKey(block.key); break;
    default: break; // Unknown blocks are preserved and do not take part in containment.
  }
}
export const blockOf = (attestation, type) => attestation.body.blocks.find(block => block.$type === type);
/** Structural and signature validation of one KeyAttestation (spec §2, §7). */
export function validateAttestation(attestation) {
  keys(attestation, ['body', 'sig']);
  fail(canonical(attestation).length <= 4096, 'Attestation exceeds 4096 bytes');
  const b = attestation.body;
  keys(b, ['$type', 'issuer', 'subject', 'owner', 'roles', 'blocks']);
  fail(b.$type === KEY_ATTESTATION, 'Not a key attestation');
  publicKey(b.issuer); publicKey(b.subject);
  fail(!equal(b.issuer, b.subject), 'Self-signed attestation');
  keys(b.owner, ['id', 'name']); text(b.owner.id); text(b.owner.name);
  sortedUnique(b.roles, 32, role => text(role, 64));
  fail(Array.isArray(b.blocks) && b.blocks.length <= 16, 'Too many blocks');
  b.blocks.forEach(validateBlock);
  fail(new Set(b.blocks.map(block => block.$type)).size === b.blocks.length, 'Duplicate block type');
  checkSigned(attestation, b.issuer);
  return attestation;
}
export const attestationId = attestation => bodyId(attestation.body);
/** Issue with an explicit issuer public key (32 = Schnorr issuer, 33 = ECDSA issuer).
 * @param {Uint8Array} issuerSecret
 * @param {Uint8Array} issuer
 * @param {{subject: Uint8Array, owner: {id: string, name: string}, roles: string[], blocks?: any[]}} fields */
export function createAttestation(issuerSecret, issuer, { subject, owner, roles, blocks = [] }) {
  const body = { $type: KEY_ATTESTATION, issuer, subject, owner, roles: sortUtf8(roles), blocks };
  return validateAttestation(sign(body, issuerSecret, issuer.length));
}
export function rootConfig(root) {
  keys(root, ['pubkey', 'roles', 'maxDepth', 'enabled', 'name'], ['pubkey', 'roles', 'maxDepth']);
  const pubkey = typeof root.pubkey === 'string' ? unhex(root.pubkey) : root.pubkey;
  publicKey(pubkey);
  fail(Array.isArray(root.roles) && root.roles.length <= 32 && new Set(root.roles).size === root.roles.length, 'Invalid root roles');
  root.roles.forEach(role => text(role, 64));
  fail(Number.isSafeInteger(root.maxDepth) && root.maxDepth >= 1 && root.maxDepth <= 16, 'Root maxDepth must be 1..16');
  fail(root.enabled === undefined || typeof root.enabled === 'boolean', 'Invalid root enabled flag');
  if (root.name !== undefined) text(root.name);
  return { pubkey, roles: sortUtf8(root.roles), maxDepth: root.maxDepth, enabled: root.enabled ?? true, id: hex(pubkey) };
}
function validity(attestation) {
  const block = blockOf(attestation, BLOCK.keyValidity);
  return { from: block?.from === undefined ? null : parseTime(block.from), until: block?.until === undefined ? null : parseTime(block.until) };
}
const within = (v, t) => (v.from === null || v.from <= t) && (v.until === null || t < v.until);
/**
 * Chain verification (spec §5). `at` is the post's created_at; `now` the receiver clock or
 * null when the clock is not trusted. Returns a status instead of throwing so callers can
 * display `expired` and `unverifiable` separately from `invalid`.
 */
export function verifyChain(attestations, { roots, at, now = null }) {
  const result = { status: 'invalid', reason: '', root: null, leaf: null, nowStatus: now === null ? 'unknown-time' : 'valid', ids: [], keys: [] };
  try {
    fail(Array.isArray(attestations) && attestations.length >= 1 && attestations.length <= 8, 'Chain length');
    clock(at);
    for (const attestation of attestations) validateAttestation(attestation);
    const configured = roots.map(rootConfig).filter(root => root.enabled);
    const root = configured.find(root => equal(root.pubkey, attestations[0].body.issuer));
    if (!root) return { ...result, status: 'unverifiable', reason: 'Unknown root' };
    result.root = root;
    let parentRoles = root.roles, parentDepth = root.maxDepth, parentValidity = { from: null, until: null }, parentOrg = null, parentImporter = null;
    let parentHasImportRole = root.roles.includes('import');
    attestations.forEach((attestation, i) => {
      const b = attestation.body, leaf = i === attestations.length - 1;
      if (i > 0) fail(equal(attestations[i - 1].body.subject, b.issuer), 'Broken chain linkage');
      fail(b.roles.every(role => parentRoles.includes(role)), 'Role escalation');
      fail(leaf || b.roles.includes('attest'), 'Intermediate without attest role');
      const depth = blockOf(attestation, BLOCK.delegation)?.maxDepth ?? 0;
      fail(depth <= parentDepth - 1, 'Delegation depth escalation');
      fail(leaf || depth >= 1, 'Intermediate without delegation');
      const v = validity(attestation);
      fail(i === 0 || v.until !== null, 'Stratum 2 and below require keyValidity.until');
      fail((parentValidity.from === null || (v.from !== null && v.from >= parentValidity.from))
        && (parentValidity.until === null || (v.until !== null && v.until <= parentValidity.until)), 'Validity escalation');
      const org = blockOf(attestation, BLOCK.organization);
      fail(parentOrg === null || org?.orgId === parentOrg.orgId, 'Organization mismatch');
      const importer = blockOf(attestation, BLOCK.importer);
      if (importer) {
        fail(parentImporter ? importer.sources.every(s => s.types.every(type => includesPair(parentImporter.sources, s.botDid, type)))
          : parentHasImportRole, 'Importer scope escalation');
      }
      parentRoles = b.roles; parentDepth = depth; parentValidity = v; parentOrg = org ?? parentOrg;
      parentImporter = importer ?? parentImporter; parentHasImportRole = b.roles.includes('import');
      result.ids.push(attestationId(attestation)); result.keys.push(b.subject);
    });
    result.leaf = attestations[attestations.length - 1];
    for (const attestation of attestations) {
      const v = validity(attestation);
      if (!within(v, at)) return { ...result, status: 'expired', reason: 'Key validity does not include the creation time' };
      if (now !== null && !within(v, now)) result.nowStatus = 'expired-now';
    }
    result.status = 'verified';
    return result;
  } catch (error) {
    return { ...result, status: result.status === 'invalid' ? 'invalid' : result.status, reason: error.message };
  }
}
export const issuerDomains = attestations => [...new Set(attestations.map(a => blockOf(a, BLOCK.issuerDomain)?.domain).filter(Boolean))];

// ---- binding (33-byte leaf keys) ------------------------------------------------------------
export function createBinding({ did, atprotoSecret, nostrSecret }) {
  const declaration = { $type: BINDING_DECLARATION, did, atprotoPubkey: atprotoPublicKey(atprotoSecret), nostrPubkey: nostrPublicKey(nostrSecret), consent: CONSENT };
  const digest = bodyHash(declaration);
  return { declaration, atprotoSig: signDigest(digest, atprotoSecret, 33), nostrSig: signDigest(digest, nostrSecret, 32) };
}
export function verifyBinding(binding, { did, atprotoPubkey, nostrPubkey }) {
  keys(binding, ['declaration', 'atprotoSig', 'nostrSig']);
  const d = binding.declaration;
  keys(d, ['$type', 'did', 'atprotoPubkey', 'nostrPubkey', 'consent']);
  fail(d.$type === BINDING_DECLARATION && DID.test(d.did) && d.did === did && d.consent === CONSENT, 'Binding declaration mismatch');
  fail(equal(d.atprotoPubkey, atprotoPubkey) && d.atprotoPubkey.length === 33 && equal(d.nostrPubkey, nostrPubkey) && d.nostrPubkey.length === 32, 'Binding key mismatch');
  fail(!equal(d.atprotoPubkey.subarray(1), d.nostrPubkey), 'Shared key material across purposes');
  const digest = bodyHash(d);
  fail(verifyDigest(digest, binding.atprotoSig, d.atprotoPubkey) && verifyDigest(digest, binding.nostrSig, d.nostrPubkey), 'Invalid binding signature');
  return true;
}

// ---- revocation ------------------------------------------------------------------------------
export function createNotice(issuerSecret, issuer, { subject, issuedAt, effectiveAt, reason }) {
  const blocks = reason ? [{ $type: `${REVOCATION}#reason`, code: reason.code, ...(reason.text ? { text: reason.text } : {}) }] : [];
  const body = { $type: NOTICE, issuer, subject, issuedAt, ...(effectiveAt ? { effectiveAt } : {}), blocks };
  return sign(body, issuerSecret, issuer.length);
}
export function createActivation(subjectSecret, subject, notice, effectiveAt) {
  return sign({ $type: ACTIVATION, noticeId: bodyHash(notice.body), effectiveAt }, subjectSecret, subject.length);
}
/** Validates a Revocation and returns its durable facts. Pre-issued notices need an activation
 * signed by the subject (a subkey block is accepted when `subkey` is supplied). */
export function validateRevocation(revocation, { subkey = null } = {}) {
  keys(revocation, ['$type', 'notice', 'activation'], ['$type', 'notice']);
  fail(revocation.$type === REVOCATION, 'Not a revocation');
  fail(canonical(revocation, { maxBytes: 8192 }).length <= 8192, 'Revocation too large');
  const notice = revocation.notice;
  keys(notice, ['body', 'sig']);
  const n = notice.body;
  keys(n, ['$type', 'issuer', 'subject', 'issuedAt', 'effectiveAt', 'blocks'], ['$type', 'issuer', 'subject', 'issuedAt', 'blocks']);
  fail(n.$type === NOTICE, 'Not a revocation notice');
  publicKey(n.issuer); publicKey(n.subject);
  const issuedAt = parseTime(n.issuedAt);
  fail(Array.isArray(n.blocks) && n.blocks.length <= 16, 'Too many blocks');
  for (const block of n.blocks) {
    fail(plain(block) && typeof block.$type === 'string', 'Invalid block');
    if (block.$type === `${REVOCATION}#reason`) {
      keys(block, ['$type', 'code', 'text'], ['$type', 'code']);
      fail(['compromise', 'superseded', 'ceased', 'unspecified'].includes(block.code), 'Invalid reason code');
      if (block.text !== undefined) text(block.text);
    }
  }
  fail(new Set(n.blocks.map(block => block.$type)).size === n.blocks.length, 'Duplicate block type');
  checkSigned(notice, n.issuer);
  let effectiveAt;
  if (n.effectiveAt !== undefined) {
    effectiveAt = parseTime(n.effectiveAt);
    fail(revocation.activation === undefined, 'Immediate notices carry no activation');
  } else {
    const activation = revocation.activation;
    fail(activation !== undefined, 'Pre-issued notice without activation is not a revocation');
    keys(activation, ['body', 'sig']);
    const a = activation.body;
    keys(a, ['$type', 'noticeId', 'effectiveAt']);
    fail(a.$type === ACTIVATION && equal(a.noticeId, bodyHash(n)), 'Activation does not match notice');
    effectiveAt = parseTime(a.effectiveAt);
    fail(effectiveAt >= issuedAt, 'Activation precedes issuance');
    const digest = bodyHash(a);
    fail(verifyDigest(digest, activation.sig, n.subject) || (subkey && verifyDigest(digest, activation.sig, subkey)), 'Invalid activation signature');
  }
  return { id: bodyId(n), subject: hex(n.subject), issuer: hex(n.issuer), issuedAt, effectiveAt };
}
/**
 * Spec §6: notices apply to a chain only when the notice issuer is an ancestor of the subject in
 * that chain (the root or an earlier issuer) or the subject itself. `revocations` are rows
 * {subject, issuer, effectiveAt}; `chainKeys` are the chain's subject keys root-first.
 */
export function revocationStatus(chainKeys, rootKey, revocations, { createdAt, seenAt }) {
  const keyHex = chainKeys.map(hex);
  const ancestorsOf = index => [hex(rootKey), ...keyHex.slice(0, index), keyHex[index]];
  let later = false;
  for (const revocation of revocations) {
    const index = keyHex.indexOf(revocation.subject);
    if (index < 0 || !ancestorsOf(index).includes(revocation.issuer)) continue;
    if (createdAt >= revocation.effectiveAt || seenAt >= revocation.effectiveAt) return 'revoked';
    later = true;
  }
  return later ? 'revoked-later' : 'not-revoked-as-known';
}
export function createRevocationList(publisherSecret, publisher, { generatedAt, revocations }) {
  revocations.forEach(revocation => validateRevocation(revocation));
  return sign({ $type: REVOCATION_LIST, publisher, generatedAt, revocations }, publisherSecret, publisher.length);
}
/** Decodes a fetched list. Inner notices are validated independently; a bad list signature is
 * reported but does not discard valid inner notices (spec list §2). */
export function decodeRevocationList(bytes) {
  const list = decodeCanonical(bytes, { maxBytes: 1048576 });
  keys(list, ['body', 'sig']);
  const b = list.body;
  keys(b, ['$type', 'publisher', 'generatedAt', 'revocations']);
  fail(b.$type === REVOCATION_LIST && Array.isArray(b.revocations) && b.revocations.length <= 4096, 'Invalid revocation list');
  publicKey(b.publisher); parseTime(b.generatedAt);
  let listSignatureValid = false;
  try { checkSigned(list, b.publisher); listSignatureValid = true; } catch { /* reported below */ }
  const accepted = [], rejected = [];
  for (const revocation of b.revocations) {
    try { accepted.push({ ...validateRevocation(revocation), revocation }); } catch (error) { rejected.push(error.message); }
  }
  return { publisher: hex(b.publisher), generatedAt: b.generatedAt, listSignatureValid, accepted, rejected };
}
/** Carries one Revocation as a kind 8173 control event signed by any carrier key (spec §5). */
export function createRevocationEvent(revocation, { carrierSecret, createdAt, exp }) {
  validateRevocation(revocation);
  const { $type, ...fields } = revocation;
  return finalizeEvent({ kind: CONTROL_KIND, content: '', created_at: createdAt, tags: [
    ['t', TAG], ['t', REVOCATION_TAG], ['mosimo:exp', exp], [`mosimo:ext:${REVOCATION}`, encodeBytes(canonical(fields))],
  ] }, carrierSecret);
}
/** Extracts and validates the Revocation from a control event; the carrier signature is not
 * part of the revocation's authenticity and must already have been checked as a Nostr event. */
export function decodeRevocationEvent(event) {
  fail(event.kind === CONTROL_KIND && Array.isArray(event.tags) && event.tags.length >= 4, 'Not a control event');
  const [t0, t1, exp, ext] = event.tags;
  fail(t0?.[0] === 't' && t0[1] === TAG && t1?.[0] === 't' && t1[1] === REVOCATION_TAG, 'Not a revocation control event');
  fail(exp?.[0] === 'mosimo:exp' && exp.length === 2, 'Missing mosimo:exp'); parseTime(exp[1]);
  fail(ext?.[0] === `mosimo:ext:${REVOCATION}` && ext.length === 2, 'Missing revocation extension');
  fail(!event.tags.some(tag => tag[0] === 'mosimo:v'), 'Control events carry no mosimo:v');
  const fields = decodeCanonical(unb64(ext[1]), { maxBytes: 8192 });
  fail(plain(fields) && !Object.hasOwn(fields, '$type'), 'Extension value must omit $type');
  const revocation = { $type: REVOCATION, ...fields };
  return { ...validateRevocation(revocation), revocation };
}
export const controlType = event => event.kind === CONTROL_KIND && event.tags?.[0]?.[0] === 't' && event.tags[0][1] === TAG
  && event.tags[1]?.[0] === 't' && typeof event.tags[1][1] === 'string' && event.tags[1][1].startsWith(`${TAG}/`) ? event.tags[1][1].slice(TAG.length + 1) : null;

// ---- BSAF extension and priority --------------------------------------------------------------
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
/** Validates a public.bsaf extension; returns the original Bot DID and type when `original` is present. */
export function verifyBsaf(extension, { requireOriginal = true } = {}) {
  keys(extension, ['$type', 'tags', 'langs', 'original', 'botDefinition'], ['$type', 'tags', 'langs']);
  fail(extension.$type === BSAF, 'Not a BSAF extension');
  if (extension.botDefinition !== undefined) {
    const b = extension.botDefinition;
    keys(b, ['url', 'retrievedAt', 'sha256'], ['url']);
    text(b.url, 2048); fail(['https:', 'http:'].includes(new URL(b.url).protocol), 'Invalid bot definition URL');
    if (b.retrievedAt !== undefined) parseTime(b.retrievedAt);
    if (b.sha256 !== undefined) fail(b.sha256 instanceof Uint8Array && b.sha256.length === 32, 'Invalid bot definition hash');
  }
  fail(Array.isArray(extension.tags) && extension.tags.length <= 8
    && extension.tags.every(t => typeof t === 'string' && Buffer.byteLength(t) <= 640), 'Invalid BSAF tags');
  fail(Array.isArray(extension.langs) && extension.langs.length <= 3 && extension.langs.every(languageTag), 'Invalid languages');
  const values = {};
  for (const name of ['bsaf', 'type', 'value', 'time', 'target', 'source']) {
    const tags = extension.tags.filter(t => t.startsWith(`${name}:`));
    fail(tags.length === 1 && tags[0].length > name.length + 1, 'Missing/ambiguous BSAF core tag');
    values[name] = tags[0].slice(name.length + 1);
  }
  fail(values.bsaf === 'v1', 'Unsupported BSAF version'); text(values.type, 128);
  if (extension.original === undefined) { fail(!requireOriginal, 'Import requires original'); return { botDid: null, type: values.type }; }
  const o = extension.original;
  keys(o, ['uri', 'cid', 'createdAt']);
  const match = /^at:\/\/(did:plc:[a-z2-7]{24})\/app\.bsky\.feed\.post\/([A-Za-z0-9._~:-]{1,512})$/.exec(o.uri);
  fail(match && !['.', '..'].includes(match[2]), 'Invalid original URI');
  const cid = CID.parse(o.cid);
  fail(cid.version === 1 && cid.code === cbor.code && cid.multihash.code === sha256.code && cid.multihash.size === 32 && cid.toString() === o.cid, 'Invalid original CID');
  originalTime(o.createdAt);
  return { botDid: match[1], type: values.type };
}
export function priorityHint(record) {
  const hints = record.ext.filter(e => e.$type === PRIORITY);
  if (hints.length !== 1) return 1;
  const { level } = hints[0];
  return Object.keys(hints[0]).length === 2 && Number.isInteger(level) && level >= 0 && level <= 3 ? level : 1;
}
export const effectivePriority = (record, authorized) => authorized ? priorityHint(record) : Math.min(priorityHint(record), 1);
export function priorityHintOf(event) {
  try {
    const tags = event.tags.filter(t => t[0] === `mosimo:ext:${PRIORITY}`);
    if (tags.length !== 1) return 1;
    const value = decodeCanonical(unb64(tags[0][1]));
    return plain(value) && Object.keys(value).join() === 'level' && Number.isInteger(value.level) && value.level >= 0 && value.level <= 3 ? value.level : 1;
  } catch { return 1; }
}

// ---- importer authorization (importer §3) -----------------------------------------------------
const meshPayloadBytes = event => cbor.encode([1, unhex(event.pubkey), event.created_at, event.kind,
  event.tags.map(t => t[0] === 'mosimo:commit' || t[0].startsWith('mosimo:ext:') ? [t[0], unb64(t[1])] : t[0] === 'mosimo:v' && t[1] === '1' ? [t[0], 1] : t),
  event.content, unhex(event.sig)]).length;
/**
 * @param {object} event Signed NIP-01 event (never mutated).
 * @param {{roots: any[], selection: ReadonlyArray<{botDid: string, types: ReadonlyArray<string>}>, revocations: ReadonlyArray<{subject: string, issuer: string, effectiveAt: number}>, now: number, seenAt?: number}} options
 */
export async function authorizeImport(event, { roots, selection = [], revocations = [], now, seenAt = now }) {
  clock(now);
  fail(Buffer.byteLength(JSON.stringify(event)) <= 262144 && Array.isArray(event.tags) && event.tags.length <= 64
    && event.tags.filter(t => t[0]?.startsWith('mosimo:ext:')).length <= 16, 'Event resource limit');
  const decoded = await decodePost(event);
  fail(meshPayloadBytes(event) <= 32768, 'Mesh payload limit');
  fail(event.created_at <= now, 'Future creation time');
  fail(parseTime(decoded.record.exp) > now, 'Delivery deadline passed');
  const bsaf = decoded.record.ext.filter(e => e.$type === BSAF);
  fail(bsaf.length === 1, 'Expected exactly one BSAF extension');
  const { botDid, type } = verifyBsaf(bsaf[0]);
  fail(includesPair(selection, botDid, type), 'Unselected Bot/type');
  fail(decoded.record.ext.filter(e => e.$type === PRIORITY).length <= 1, 'Multiple priority hints');
  const chains = decoded.record.ext.filter(e => e.$type === CHAIN);
  fail(chains.length >= 1 && chains.length <= 4, 'Expected one to four signing chains');
  const reasons = [];
  for (const chain of chains) {
    try {
      keys(chain, ['$type', 'attestations', 'binding'], ['$type', 'attestations']);
      fail(canonical(chain, { maxBytes: 16384 }).length <= 16384, 'Chain extension too large');
      const verified = verifyChain(chain.attestations, { roots, at: event.created_at, now });
      fail(verified.status === 'verified', `${verified.status}: ${verified.reason}`);
      fail(verified.nowStatus === 'valid', 'Chain expired at the current time');
      const leaf = verified.leaf.body;
      if (leaf.subject.length === 32) {
        fail(chain.binding === undefined && hex(leaf.subject) === event.pubkey, 'Leaf key does not match the Nostr pubkey');
      } else {
        fail(chain.binding !== undefined, 'A 33-byte leaf requires a binding');
        verifyBinding(chain.binding, { did: decoded.commit.did, atprotoPubkey: leaf.subject, nostrPubkey: unhex(event.pubkey) });
        const { sig, ...unsigned } = decoded.commit;
        fail(verifyDigest(sha(cbor.encode(unsigned)), sig, leaf.subject), 'Commit signature does not match the leaf key');
      }
      fail(leaf.roles.includes('import'), 'Leaf lacks the import role');
      const importer = blockOf(verified.leaf, BLOCK.importer), organization = blockOf(verified.leaf, BLOCK.organization);
      fail(importer && organization, 'Leaf lacks importer/organization blocks');
      fail(includesPair(importer.sources, botDid, type), 'Bot/type outside the importer scope');
      const revocation = revocationStatus(verified.keys, verified.root.pubkey, revocations, { createdAt: event.created_at, seenAt });
      fail(revocation === 'not-revoked-as-known', `Revocation state ${revocation}`);
      const priority = priorityHint(decoded.record);
      return { authorized: true, botDid, type, priority, effectivePriority: effectivePriority(decoded.record, true), rootId: verified.root.id,
        leafId: verified.ids[verified.ids.length - 1], orgId: organization.orgId, orgName: organization.orgName,
        issuerDomains: issuerDomains(chain.attestations), checkedAt: now, revocation, record: decoded.record };
    } catch (error) { reasons.push(error.message); }
  }
  throw new Error(`No authorized chain: ${reasons.join('; ')}`);
}

// ---- importer identity and post creation -------------------------------------------------------
export function createImporter({ endpoint = 'https://pds.invalid' } = {}) {
  fail(new URL(endpoint).protocol === 'https:', 'HTTPS PDS endpoint required');
  const atprotoSecret = randomSecret(), nostrSecret = randomSecret(), plcSecret = randomSecret();
  const unsigned = { type: 'plc_operation', rotationKeys: [formatDidKey('ES256K', atprotoPublicKey(plcSecret))],
    verificationMethods: { atproto: formatDidKey('ES256K', atprotoPublicKey(atprotoSecret)) }, alsoKnownAs: [],
    services: { atproto_pds: { type: 'AtprotoPersonalDataServer', endpoint } }, prev: null };
  const genesis = { ...unsigned, sig: Buffer.from(signDigest(sha(cbor.encode(unsigned)), plcSecret, 33)).toString('base64url') };
  const did = `did:plc:${base32.encode(sha(cbor.encode(genesis))).slice(1, 25)}`;
  return { did, atprotoSecret: hex(atprotoSecret), nostrSecret: hex(nostrSecret), plcSecret: hex(plcSecret), genesis,
    nostrPubkey: hex(nostrPublicKey(nostrSecret)), atprotoPubkey: hex(atprotoPublicKey(atprotoSecret)) };
}
function tidFor(micros) {
  const alphabet = '234567abcdefghijklmnopqrstuvwxyz';
  let value = BigInt(micros) << 10n, tid = '';
  for (let i = 0; i < 13; i++) { tid = alphabet[Number(value & 31n)] + tid; value >>= 5n; }
  fail(value === 0n, 'TID overflow');
  return tid;
}
/**
 * Builds one import post: record, signed v3 commit over the importer's full MST, and the Nostr
 * event with the fixed tag order of core §7.2. `ext` is the author extension list before the
 * chain and priority extensions, which are appended in that order.
 * @param {{importer: {did: string, atprotoSecret: string, nostrSecret: string}, attestations: any[], text: string, ext?: any[], createdAt: number, exp: string, state?: {entries: {key: string, cid: string}[], base: string | null, lastMicros: number}, priority?: number}} options
 */
export async function createImportPost({ importer, attestations, text: body, ext = [], createdAt, exp, state = { entries: [], base: null, lastMicros: 0 }, priority = 1 }) {
  clock(createdAt); fail(state.entries.length < 10000, 'Repository capacity reached');
  fail((state.base === null) === (state.entries.length === 0), 'Inconsistent repository state');
  parseTime(exp);
  const micros = Math.max(createdAt * 1000000, state.lastMicros + 1);
  const rkey = tidFor(micros), path = `${COLLECTION}/${rkey}`;
  const extensions = [...ext, { $type: CHAIN, attestations }, { $type: PRIORITY, level: priority }];
  const record = { $type: COLLECTION, text: body, exp, ext: extensions };
  const recordBytes = cbor.encode(record);
  const recordCid = CID.createV1(cbor.code, await sha256.digest(recordBytes));
  let tree = await MST.create(new MemoryBlockstore());
  for (const e of state.entries) tree = await tree.add(e.key, CID.parse(e.cid));
  tree = await tree.add(path, recordCid);
  const unsigned = { did: importer.did, version: 3, data: await tree.getPointer(), rev: rkey, prev: null };
  const commit = { ...unsigned, sig: signDigest(sha(cbor.encode(unsigned)), unhex(importer.atprotoSecret), 33) };
  const commitBytes = cbor.encode(commit);
  const event = finalizeEvent({ kind: 1, content: body, created_at: createdAt, tags: [
    ['t', TAG], ['mosimo:v', '1'], ['mosimo:commit', encodeBytes(commitBytes)], ['mosimo:base', state.base ?? ''],
    ['mosimo:rkey', rkey], ['mosimo:exp', exp],
    ...extensions.map(({ $type, ...fields }) => [`mosimo:ext:${$type}`, encodeBytes(canonical(fields, { allowCid: true }))]),
  ] }, unhex(importer.nostrSecret));
  fail(isBsafImport(event) === extensions.some(e => e.$type === BSAF && e.original !== undefined), 'Import routing mismatch');
  return { event, record, recordCid: recordCid.toString(), state: { entries: [...state.entries, { key: path, cid: recordCid.toString() }],
    base: CID.createV1(cbor.code, await sha256.digest(commitBytes)).toString(), lastMicros: micros } };
}
