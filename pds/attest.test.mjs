import assert from 'node:assert/strict';
import test from 'node:test';
import { finalizeEvent, generateSecretKey } from 'nostr-tools';
import * as cbor from '@ipld/dag-cbor';
import { CID } from 'multiformats/cid';
import { sha256 } from 'multiformats/hashes/sha2';
import {
  BLOCK, CHAIN, IMPORTER, BSAF, PRIORITY, REVOCATION, CONTROL_KIND, JMA_SELECTION, KIKIKURU_SELECTION,
  canonical, decodeCanonical, randomSecret, nostrPublicKey, atprotoPublicKey, hex, unhex, b64, unb64, timestamp,
  createAttestation, validateAttestation, attestationId, verifyChain, verifyBinding, createBinding, issuerDomains,
  createNotice, createActivation, validateRevocation, revocationStatus, createRevocationList, decodeRevocationList,
  createRevocationEvent, decodeRevocationEvent, controlType, verifyBsaf, authorizeImport, createImporter, createImportPost,
  priorityHintOf, effectivePriority,
} from './attest.mjs';
import { decodePost, isBsafImport, TAG } from './repository.mjs';

const now = 1794000000; // 2026-11-06T...
const selection = [...JMA_SELECTION, ...KIKIKURU_SELECTION].sort((a, b) => a.botDid < b.botDid ? -1 : 1);
const org = { $type: BLOCK.organization, orgId: 'oriom.dev:mosimo-bsaf-importer', orgName: 'Mosimo BSAF importer (not JMA)' };

/** Root (32-byte Schnorr) -> CA (stratum 1, unlimited) -> importer leaf (stratum 2, Nostr key). */
function hierarchy({ leafUntil = timestamp(now + 365 * 86400), caDepth = 1, leafRoles = ['import'], leafBlocks, caBlocks, sources = selection } = {}) {
  const rootSecret = randomSecret(), caSecret = randomSecret();
  const root = nostrPublicKey(rootSecret), ca = nostrPublicKey(caSecret);
  const importer = createImporter({ endpoint: 'https://pds.example.org' });
  const leafKey = unhex(importer.nostrPubkey);
  const caAttestation = createAttestation(rootSecret, root, { subject: ca, owner: { id: 'ca-1', name: 'Oriom CA' },
    roles: ['attest', 'import'], blocks: caBlocks ?? [
      { $type: BLOCK.delegation, maxDepth: caDepth }, { $type: BLOCK.issuerDomain, domain: 'pds.example.org' },
      { $type: IMPORTER, sources }] });
  const leaf = createAttestation(caSecret, ca, { subject: leafKey, owner: { id: 'importer-1', name: 'Mosimo importer' },
    roles: leafRoles, blocks: leafBlocks ?? [
      { $type: BLOCK.keyValidity, from: timestamp(now - 86400), until: leafUntil }, org,
      { $type: IMPORTER, sources }, { $type: BLOCK.trustLevel, level: 2, method: 'manual' }] });
  const roots = [{ pubkey: hex(root), roles: ['attest', 'import', 'identity', 'public-information'], maxDepth: 2, enabled: true }];
  return { rootSecret, caSecret, root, ca, importer, leafKey, caAttestation, leaf, chain: [caAttestation, leaf], roots };
}
const original = { uri: 'at://did:plc:vwi7zhfyamyvrg5v6ycjgzxi/app.bsky.feed.post/3lxyzabcdefgh', createdAt: '2026-11-06T00:00:00.000Z',
  cid: CID.createV1(cbor.code, await sha256.digest(cbor.encode({ text: 'x' }))).toString() };
const bsaf = (type = 'earthquake', extra = {}) => ({ $type: BSAF, tags: ['bsaf:v1', `type:${type}`, 'value:5+', 'time:2026-11-06T00:00:00Z', 'target:jp-kanto', 'source:jma'],
  langs: ['ja'], original, ...extra });
async function importPost(h, { type = 'earthquake', priority = 2, createdAt = now - 60, exp = timestamp(now + 3600), ext, chain } = {}) {
  return createImportPost({ importer: h.importer, attestations: chain ?? h.chain, text: '【地震情報】震度5強', ext: ext ?? [bsaf(type)], createdAt, exp, priority });
}

test('canonical CBOR round-trips bytes and rejects floats, tags, indefinite lengths and trailing data', () => {
  const value = { b: new Uint8Array([1, 2]), a: 'x', n: -5, list: [true, null] };
  const bytes = canonical(value);
  assert.deepEqual(decodeCanonical(bytes), value);
  assert.throws(() => canonical({ f: 1.5 }), /Invalid integer/);
  assert.throws(() => decodeCanonical(new Uint8Array([0xf9, 0x3c, 0x00])), /Unsupported CBOR/);
  assert.throws(() => decodeCanonical(new Uint8Array([0x9f, 0xff])), /Indefinite/);
  assert.throws(() => decodeCanonical(new Uint8Array([...bytes, 0])), /Trailing/);
  assert.throws(() => decodeCanonical(new Uint8Array([0xd8, 0x2a, 0x40])), /CBOR tag/);
  assert.throws(() => decodeCanonical(new Uint8Array([0xa2, 0x61, 0x62, 0x01, 0x61, 0x61, 0x02])), /Noncanonical/);
  assert.equal(b64(new Uint8Array([1, 2])), 'AQI'); assert.equal(b64(new Uint8Array([0xfb, 0xff])), '+/8');
  assert.throws(() => unb64('AQI='), /Invalid Base64/); assert.throws(() => unb64('AQJ'), /Noncanonical/);
});

test('attestation structure: sorted roles, unique blocks, no self-signing, size limit, both key kinds sign', () => {
  const h = hierarchy();
  assert.equal(validateAttestation(h.leaf), h.leaf);
  assert.match(attestationId(h.leaf), /^[0-9a-f]{64}$/);
  const ecdsaSecret = randomSecret(), ecdsa = atprotoPublicKey(ecdsaSecret);
  const byEcdsa = createAttestation(ecdsaSecret, ecdsa, { subject: h.leafKey, owner: { id: 'o', name: 'n' }, roles: ['identity'], blocks: [] });
  assert.equal(byEcdsa.body.issuer.length, 33); validateAttestation(byEcdsa);
  const tamper = (change, pattern) => assert.throws(() => validateAttestation({ ...h.leaf, body: change(structuredClone(h.leaf.body)) }), pattern);
  tamper(b => ({ ...b, roles: ['import', 'attest'] }), /sorted/);
  tamper(b => ({ ...b, roles: ['import', 'import'] }), /sorted/);
  tamper(b => ({ ...b, blocks: [...b.blocks, org] }), /Duplicate block/);
  tamper(b => ({ ...b, subject: b.issuer }), /Self-signed/);
  tamper(b => ({ ...b, owner: { id: 'x', name: 'y' } }), /Invalid signature/);
  tamper(b => ({ ...b, blocks: [{ $type: BLOCK.organization, orgId: 'noprefix', orgName: 'n' }] }), /orgId/);
  tamper(b => ({ ...b, blocks: [{ $type: IMPORTER, sources: [{ botDid: 'did:plc:vwi7zhfyamyvrg5v6ycjgzxi', types: ['b', 'a'] }] }] }), /sorted/);
  tamper(b => ({ ...b, blocks: [{ $type: BLOCK.keyValidity, from: '2026-01-01T00:00:00+09:00' }] }), /Timestamp/);
  tamper(b => ({ ...b, owner: { id: 'x'.repeat(300), name: 'n' } }), /Invalid text/);
  assert.throws(() => createAttestation(h.caSecret, h.ca, { subject: h.leafKey, owner: { id: 'o', name: 'n' }, roles: [],
    blocks: [{ $type: 'x.unknown', pad: 'p'.repeat(5000) }] }), /4096/);
  // Unknown blocks are preserved and verified as part of the signed body.
  const unknown = createAttestation(h.caSecret, h.ca, { subject: h.leafKey, owner: { id: 'o', name: 'n' }, roles: ['identity'],
    blocks: [{ $type: 'org.example.custom', any: [1, 'two', null] }, { $type: BLOCK.keyValidity, until: timestamp(now + 10) }] });
  assert.deepEqual(decodeCanonical(canonical(unknown)).body.blocks[0], { $type: 'org.example.custom', any: [1, 'two', null] });
});

test('chain verification distinguishes verified, expired, invalid and unverifiable, and enforces containment', () => {
  const h = hierarchy();
  const ok = verifyChain(h.chain, { roots: h.roots, at: now, now });
  assert.equal(ok.status, 'verified'); assert.equal(ok.nowStatus, 'valid'); assert.equal(ok.root.id, hex(h.root));
  assert.deepEqual(ok.keys.map(hex), [hex(h.ca), hex(h.leafKey)]);
  assert.equal(verifyChain(h.chain, { roots: h.roots, at: now }).nowStatus, 'unknown-time');
  assert.equal(verifyChain(h.chain, { roots: [], at: now }).status, 'unverifiable');
  assert.equal(verifyChain(h.chain, { roots: [{ ...h.roots[0], enabled: false }], at: now }).status, 'unverifiable');
  const expired = verifyChain(h.chain, { roots: h.roots, at: now + 400 * 86400, now });
  assert.equal(expired.status, 'expired');
  const expiredNow = verifyChain(h.chain, { roots: h.roots, at: now, now: now + 400 * 86400 });
  assert.equal(expiredNow.status, 'verified'); assert.equal(expiredNow.nowStatus, 'expired-now');
  assert.match(verifyChain([h.leaf], { roots: h.roots, at: now }).reason, /Unknown root/);
  assert.match(verifyChain([h.leaf, h.caAttestation], { roots: h.roots, at: now }).reason, /Unknown root/);
  const swapped = verifyChain([h.caAttestation, hierarchy().leaf], { roots: h.roots, at: now });
  assert.equal(swapped.status, 'invalid'); assert.match(swapped.reason, /linkage/);
  // Role escalation, depth, validity and importer containment.
  assert.match(verifyChain(h.chain, { roots: [{ ...h.roots[0], roles: ['attest'] }], at: now }).reason, /Role escalation/);
  assert.match(verifyChain(h.chain, { roots: [{ ...h.roots[0], maxDepth: 1 }], at: now }).reason, /Delegation depth/);
  const noUntil = hierarchy({ leafBlocks: [org, { $type: IMPORTER, sources: selection }] });
  assert.match(verifyChain(noUntil.chain, { roots: noUntil.roots, at: now }).reason, /require keyValidity.until/);
  const bounded = hierarchy({ caBlocks: [{ $type: BLOCK.delegation, maxDepth: 1 }, { $type: BLOCK.keyValidity, until: timestamp(now + 100) }] });
  assert.match(verifyChain(bounded.chain, { roots: bounded.roots, at: now }).reason, /Validity escalation/);
  const widened = hierarchy({ sources: JMA_SELECTION });
  const wideLeaf = createAttestation(widened.caSecret, widened.ca, { subject: widened.leafKey, owner: { id: 'i', name: 'n' }, roles: ['import'],
    blocks: [{ $type: BLOCK.keyValidity, until: timestamp(now + 100) }, org, { $type: IMPORTER, sources: selection }] });
  assert.match(verifyChain([widened.caAttestation, wideLeaf], { roots: widened.roots, at: now }).reason, /Importer scope escalation/);
  const otherOrg = hierarchy({ caBlocks: [{ $type: BLOCK.delegation, maxDepth: 1 }, { ...org, orgId: 'oriom.dev:other' }] });
  assert.match(verifyChain(otherOrg.chain, { roots: otherOrg.roots, at: now }).reason, /Organization mismatch/);
  assert.deepEqual(issuerDomains(h.chain), ['pds.example.org']);
});

test('bindings tie a 33-byte atproto leaf to the Nostr key with both signatures and the fixed consent', () => {
  const importer = createImporter();
  const binding = createBinding({ did: importer.did, atprotoSecret: unhex(importer.atprotoSecret), nostrSecret: unhex(importer.nostrSecret) });
  assert.ok(verifyBinding(binding, { did: importer.did, atprotoPubkey: unhex(importer.atprotoPubkey), nostrPubkey: unhex(importer.nostrPubkey) }));
  assert.throws(() => verifyBinding(binding, { did: createImporter().did, atprotoPubkey: unhex(importer.atprotoPubkey), nostrPubkey: unhex(importer.nostrPubkey) }), /mismatch/);
  assert.throws(() => verifyBinding({ ...binding, nostrSig: binding.atprotoSig }, { did: importer.did, atprotoPubkey: unhex(importer.atprotoPubkey), nostrPubkey: unhex(importer.nostrPubkey) }), /signature/);
});

test('revocations: immediate and pre-issued forms, ancestor scoping, created_at/seenAt, lists and control events', () => {
  const h = hierarchy();
  const immediate = { $type: REVOCATION, notice: createNotice(h.caSecret, h.ca, { subject: h.leafKey, issuedAt: timestamp(now), effectiveAt: timestamp(now + 10), reason: { code: 'compromise' } }) };
  const facts = validateRevocation(immediate);
  assert.equal(facts.subject, hex(h.leafKey)); assert.equal(facts.effectiveAt, now + 10);
  const pre = createNotice(h.caSecret, h.ca, { subject: h.leafKey, issuedAt: timestamp(now) });
  assert.throws(() => validateRevocation({ $type: REVOCATION, notice: pre }), /without activation/);
  const activated = { $type: REVOCATION, notice: pre, activation: createActivation(unhex(h.importer.nostrSecret), h.leafKey, pre, timestamp(now + 5)) };
  assert.equal(validateRevocation(activated).effectiveAt, now + 5);
  assert.throws(() => validateRevocation({ ...activated, activation: createActivation(randomSecret(), h.leafKey, pre, timestamp(now + 5)) }), /activation signature/);
  assert.throws(() => validateRevocation({ ...activated, activation: createActivation(unhex(h.importer.nostrSecret), h.leafKey, pre, timestamp(now - 5)) }), /precedes/);
  // Notices apply only when the issuer is an ancestor in this chain or the subject itself.
  const keys = [h.ca, h.leafKey];
  const row = r => ({ subject: r.subject, issuer: r.issuer, effectiveAt: r.effectiveAt });
  assert.equal(revocationStatus(keys, h.root, [row(facts)], { createdAt: now + 20, seenAt: now + 20 }), 'revoked');
  assert.equal(revocationStatus(keys, h.root, [row(facts)], { createdAt: now, seenAt: now + 20 }), 'revoked');
  assert.equal(revocationStatus(keys, h.root, [row(facts)], { createdAt: now, seenAt: now }), 'revoked-later');
  assert.equal(revocationStatus(keys, h.root, [], { createdAt: now, seenAt: now }), 'not-revoked-as-known');
  const strangerSecret = randomSecret();
  const stranger = createNotice(strangerSecret, nostrPublicKey(strangerSecret), { subject: h.leafKey, issuedAt: timestamp(now), effectiveAt: timestamp(now) });
  assert.equal(revocationStatus(keys, h.root, [row(validateRevocation({ $type: REVOCATION, notice: stranger }))], { createdAt: now + 1, seenAt: now + 1 }), 'not-revoked-as-known');
  const caRevoked = validateRevocation({ $type: REVOCATION, notice: createNotice(h.rootSecret, h.root, { subject: h.ca, issuedAt: timestamp(now), effectiveAt: timestamp(now) }) });
  assert.equal(revocationStatus(keys, h.root, [row(caRevoked)], { createdAt: now, seenAt: now }), 'revoked');
  // Lists: inner notices stand on their own; the list signature is reported separately.
  const list = createRevocationList(h.caSecret, h.ca, { generatedAt: timestamp(now), revocations: [immediate, activated] });
  const decoded = decodeRevocationList(canonical(list));
  assert.equal(decoded.listSignatureValid, true); assert.equal(decoded.accepted.length, 2); assert.equal(decoded.rejected.length, 0);
  const forged = decodeRevocationList(canonical({ ...list, body: { ...list.body, generatedAt: timestamp(now + 1) } }));
  assert.equal(forged.listSignatureValid, false); assert.equal(forged.accepted.length, 2);
  // Control event carrier: any key, fixed tag order, no mosimo:v.
  const event = createRevocationEvent(immediate, { carrierSecret: generateSecretKey(), createdAt: now, exp: timestamp(now + 86400) });
  assert.equal(event.kind, CONTROL_KIND); assert.equal(controlType(event), 'revocation');
  assert.deepEqual(event.tags.slice(0, 2), [['t', TAG], ['t', `${TAG}/revocation`]]);
  assert.equal(decodeRevocationEvent(event).id, facts.id);
  assert.throws(() => decodeRevocationEvent({ ...event, tags: [...event.tags, ['mosimo:v', '1']] }), /no mosimo:v/);
  assert.throws(() => decodeRevocationEvent({ ...event, tags: [event.tags[0], ['t', `${TAG}/plc`], ...event.tags.slice(2)] }), /Not a revocation/);
});

test('import posts carry the fixed tag order, decode to the same record and are authorized end to end', async () => {
  const h = hierarchy();
  const { event, record, state } = await importPost(h);
  assert.deepEqual(event.tags.slice(0, 2).map(t => t[0]), ['t', 'mosimo:v']);
  assert.deepEqual(event.tags.slice(2, 6).map(t => t[0]), ['mosimo:commit', 'mosimo:base', 'mosimo:rkey', 'mosimo:exp']);
  assert.deepEqual(event.tags.slice(6).map(t => t[0]), [`mosimo:ext:${BSAF}`, `mosimo:ext:${CHAIN}`, `mosimo:ext:${PRIORITY}`]);
  assert.equal(event.pubkey, h.importer.nostrPubkey);
  assert.ok(isBsafImport(event));
  const decoded = await decodePost(event);
  assert.deepEqual(decodeCanonical(canonical(decoded.record, { allowCid: true }), { allowCid: true }), decodeCanonical(canonical(record, { allowCid: true }), { allowCid: true }));
  assert.equal(decoded.commit.did, h.importer.did); assert.equal(decoded.record.text, event.content);
  const result = await authorizeImport(event, { roots: h.roots, selection, revocations: [], now });
  assert.equal(result.authorized, true); assert.equal(result.effectivePriority, 2); assert.equal(result.priority, 2);
  assert.equal(result.botDid, 'did:plc:vwi7zhfyamyvrg5v6ycjgzxi'); assert.equal(result.type, 'earthquake');
  assert.equal(result.orgName, org.orgName); assert.deepEqual(result.issuerDomains, ['pds.example.org']);
  assert.equal(priorityHintOf(event), 2); assert.equal(effectivePriority(decoded.record, false), 1);
  // Chained second post keeps the repository state consistent.
  const second = await createImportPost({ importer: h.importer, attestations: h.chain, text: 't2', ext: [bsaf('tsunami')], createdAt: now - 30, exp: timestamp(now + 3600), state });
  assert.equal(second.event.tags[3][1], state.base); assert.equal(second.state.entries.length, 2);
  assert.ok((await decodePost(second.event)).base);
});

test('import authorization fails closed: unknown root, unselected pair, scope, roles, blocks, time, revocation, key mismatch', async () => {
  const h = hierarchy();
  const { event } = await importPost(h);
  const reject = async (options, pattern, ev = event) => assert.rejects(authorizeImport(ev, { roots: h.roots, selection, revocations: [], now, ...options }), pattern);
  await reject({ roots: [] }, /unverifiable/);
  await reject({ selection: KIKIKURU_SELECTION }, /Unselected/);
  await reject({ now: now - 120 }, /Future creation/);
  await reject({ now: now + 400 * 86400 }, /Delivery deadline/);
  const longLived = await importPost(h, { exp: timestamp(now + 500 * 86400) });
  await reject({ now: now + 400 * 86400 }, /expired/, longLived.event);
  const facts = validateRevocation({ $type: REVOCATION, notice: createNotice(h.caSecret, h.ca, { subject: h.leafKey, issuedAt: timestamp(now - 100), effectiveAt: timestamp(now - 90) }) });
  await reject({ revocations: [facts] }, /revoked/);
  const later = { ...facts, effectiveAt: now + 100 };
  await reject({ revocations: [later] }, /revoked-later/);
  // Leaf without the import role or without the blocks; scope narrower than the selection.
  const noRole = hierarchy({ leafRoles: ['attest'] });
  await assert.rejects(authorizeImport((await importPost(noRole)).event, { roots: noRole.roots, selection, now }), /import role/);
  const noBlocks = hierarchy({ leafBlocks: [{ $type: BLOCK.keyValidity, until: timestamp(now + 100) }] });
  await assert.rejects(authorizeImport((await importPost(noBlocks)).event, { roots: noBlocks.roots, selection, now }), /importer\/organization/);
  const jmaOnly = hierarchy({ sources: JMA_SELECTION });
  await assert.rejects(authorizeImport((await importPost(jmaOnly, { type: 'typhoon', ext: [bsaf('typhoon', { original: { ...original, uri: 'at://did:plc:kxwz5cz7o6g4jlmxh6doyfsm/app.bsky.feed.post/3lxyzabcdefgh' } })] })).event,
    { roots: jmaOnly.roots, selection, now }), /outside the importer scope/);
  // A chain whose leaf is a different Nostr key than the event signer.
  const other = hierarchy();
  await assert.rejects(authorizeImport((await importPost(h, { chain: other.chain })).event, { roots: other.roots, selection, now }), /does not match the Nostr pubkey/);
  // Two BSAF extensions or a native BSAF post (no original) are not imports.
  await assert.rejects(authorizeImport((await importPost(h, { ext: [bsaf(), bsaf()] })).event, { roots: h.roots, selection, now }), /exactly one BSAF/);
  const { original: _omit, ...native } = bsaf();
  await assert.rejects(authorizeImport((await importPost(h, { ext: [native] })).event, { roots: h.roots, selection, now }), /requires original/);
  // Tampering with any signed tag invalidates the Nostr signature before authorization.
  const tampered = { ...event, tags: event.tags.map(t => t[0] === 'mosimo:exp' ? [t[0], timestamp(now + 7200)] : t) };
  await reject({}, /Nostr signature/, tampered);
  // Unknown extension types are preserved; priority above 1 needs an authorized chain.
  const ordinary = finalizeEvent({ kind: 1, content: 'hi', created_at: now, tags: [['t', TAG], ['mosimo:v', '1'], ['mosimo:commit', 'AA'], ['mosimo:base', ''],
    ['mosimo:rkey', '3jzfcijpj2z2a'], ['mosimo:exp', timestamp(now + 10)], [`mosimo:ext:${PRIORITY}`, b64(canonical({ level: 3 }))]] }, generateSecretKey());
  assert.equal(priorityHintOf(ordinary), 3);
});

test('BSAF extension validation keeps original tag strings and requires one core tag each', () => {
  assert.deepEqual(verifyBsaf(bsaf()), { botDid: 'did:plc:vwi7zhfyamyvrg5v6ycjgzxi', type: 'earthquake' });
  assert.throws(() => verifyBsaf({ ...bsaf(), tags: [...bsaf().tags, 'type:tsunami'] }), /ambiguous/);
  assert.throws(() => verifyBsaf({ ...bsaf(), tags: bsaf().tags.map(t => t === 'bsaf:v1' ? 'bsaf:v2' : t) }), /version/);
  assert.throws(() => verifyBsaf({ ...bsaf(), langs: ['not a tag!'] }), /languages/);
  assert.throws(() => verifyBsaf({ ...bsaf(), original: { ...original, uri: 'at://alice.bsky.social/app.bsky.feed.post/abc' } }), /original URI/);
  assert.throws(() => verifyBsaf({ ...bsaf(), original: { ...original, cid: 'bafkreigh2akiscaildcqabsyg3dfr6chu3fgpregiymsck7e7aqa4s52zy' } }), /original CID/);
  assert.deepEqual(verifyBsaf({ $type: BSAF, tags: bsaf().tags, langs: [] }, { requireOriginal: false }), { botDid: null, type: 'earthquake' });
});
